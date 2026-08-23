package com.cerocoder.meshtest.connection

import android.util.Log
import com.cerocoder.meshtest.transport.MeshProtocol
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import com.cerocoder.meshtest.transport.RadioTransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.ToRadio
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Единственный владелец активного транспорта.
 *
 * Ведёт двухстадийный handshake, публикует состояние соединения и поток
 * принятых кадров. Не знает, что под ним — демо-устройство или BLE.
 */
class RadioConnectionManager(
    private val factory: RadioTransportFactory,
    private val scope: CoroutineScope,
    private val handshakeTimeout: Duration = 30.seconds,
    private val heartbeatInterval: Duration = 30.seconds,
    private val silenceTimeout: Duration = 60.seconds,
    // Источник времени вынесен из System.currentTimeMillis(): тесты идут на
    // виртуальных часах runTest, и без этого параметра детектор тишины не смог бы
    // отличить настоящую тишину от мгновенного прохода теста.
    private val now: () -> Long = { System.currentTimeMillis() },
    private val recoveryDelay: Duration = 5.seconds,
) : RadioTransportCallback {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Channel, а не SharedFlow: строгий FIFO обязателен — порядок кадров конфигурации
    // определяет корректность handshake. При переполнении отбрасывается новейший кадр,
    // чтобы уже принятые сохранили порядок.
    private val _packets = Channel<FromRadio>(capacity = PACKET_QUEUE_CAPACITY)
    val packets: Flow<FromRadio> = _packets.receiveAsFlow()

    // Отдельный накопитель для диагностического экрана: у Channel один потребитель,
    // и переподписка UI теряла бы кадры.
    private val _packetLog = MutableStateFlow<List<FromRadio>>(emptyList())
    val packetLog: StateFlow<List<FromRadio>> = _packetLog.asStateFlow()

    private val droppedFrameCount = AtomicInteger(0)
    val droppedFrames: Int get() = droppedFrameCount.get()

    private val transportMutex = Mutex()

    @Volatile
    private var transport: RadioTransport? = null

    @Volatile
    private var currentAddress: String? = null
    // @Volatile обязателен: оба таймера создаются из onConnect и onDataReceived,
    // то есть с потоков транспорта и без замка, а отменяются из connect, disconnect
    // и onDisconnect, которые замок держат. Чтение устаревшей ссылки означает
    // переживший сессию таймер, который потом закроет уже чужое соединение.
    @Volatile
    private var watchdog: Job? = null

    @Volatile
    private var keepAlive: Job? = null

    @Volatile
    private var lastFrameAt: Long = 0

    private val heartbeatNonce = AtomicInteger(0)

    @Volatile
    private var recovery: Job? = null

    /** Сколько раз подряд менеджер сам поднимал транспорт после собственного отказа. */
    private var recoveryAttempts = 0

    /** Подключиться к устройству по внутреннему адресу. */
    fun connect(address: String) = connect(address, byUser = true)

    /**
     * @param byUser тап пользователя даёт свежий лимит самостоятельных попыток
     *   восстановления, а сама попытка восстановления — нет, иначе лимит никогда
     *   бы не исчерпывался и цикл стал бы вечным.
     */
    private fun connect(address: String, byUser: Boolean) {
        scope.launch {
            transportMutex.withLock {
                // Идемпотентность: повторный тап по уже подключённому устройству не
                // должен пересоздавать транспорт — иначе останутся два транспорта,
                // пишущих в один канал.
                if (address == currentAddress && _connectionState.value !is ConnectionState.Disconnected) {
                    Log.d(TAG, "уже подключены к этому адресу, повтор игнорируем")
                    return@withLock
                }
                // Снимаем сторожевой таймер прошлой сессии: иначе он может сработать уже по
                // новому транспорту и оборвать здоровое соединение.
                watchdog?.cancel()
                // И heartbeat прошлой сессии — иначе он переживёт транспорт и будет
                // писать в уже мёртвое соединение.
                keepAlive?.cancel()
                recovery?.cancel()
                if (byUser) recoveryAttempts = 0
                closeTransportLocked()
                _packetLog.value = emptyList()
                // Осушаем канал: иначе кадры прошлой сессии занимают буфер, и новая
                // сессия теряет свои собственные, показывая при этом нулевой счётчик потерь.
                @Suppress("ControlFlowWithEmptyBody")
                while (_packets.tryReceive().isSuccess) {}
                droppedFrameCount.set(0)
                currentAddress = address
                try {
                    val created = factory.create(address, this@RadioConnectionManager)
                    transport = created
                    created.start()
                } catch (e: Throwable) {
                    Log.w(TAG, "не удалось создать транспорт для адреса", e)
                    transport = null
                    _connectionState.value =
                        ConnectionState.Disconnected("не удалось подключиться к устройству")
                }
            }
        }
    }

    /** Отключиться и освободить транспорт. */
    suspend fun disconnect() {
        withContext(NonCancellable) {
            transportMutex.withLock {
                watchdog?.cancel()
                keepAlive?.cancel()
                recovery?.cancel()
                recoveryAttempts = 0
                transport?.let { active ->
                    // Вежливое прощание: даём ноде понять, что разрыв намеренный.
                    active.send(ToRadio(disconnect = true).encode())
                }
                closeTransportLocked()
                currentAddress = null
            }
            _connectionState.value = ConnectionState.Disconnected()
        }
    }

    private suspend fun closeTransportLocked() {
        transport?.let { active ->
            try {
                active.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "ошибка при закрытии транспорта", e)
            }
        }
        transport = null
    }

    override fun onConnect() {
        _connectionState.value = ConnectionState.Connecting
        Log.i(TAG, "связь установлена, запускаем стадию 1 handshake")
        startHandshakeWatchdog()
        sendToRadio(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE))
    }

    override fun onDisconnect(isPermanent: Boolean, reason: String?) {
        watchdog?.cancel()
        keepAlive?.cancel()
        Log.i(TAG, "связь потеряна (постоянно=$isPermanent)")
        _connectionState.value = ConnectionState.Disconnected(
            reason ?: if (isPermanent) "соединение разорвано" else null,
        )
        if (isPermanent) {
            // Владелец обязан освободить транспорт: сам он о себе не позаботится,
            // а за швом это будет живое GATT-соединение.
            // Запоминаем именно ту сессию, о смерти которой сообщили: пока корутина
            // ждёт замок, пользователь может успеть подключиться заново, и без этой
            // проверки мы снесли бы уже живое новое соединение.
            val doomed = transport ?: return
            scope.launch {
                transportMutex.withLock {
                    try {
                        doomed.close()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "ошибка при закрытии оборванного транспорта", e)
                    }
                    if (transport === doomed) {
                        transport = null
                        currentAddress = null
                    }
                }
            }
        }
    }

    override fun onDataReceived(bytes: ByteArray) {
        if (bytes.size > MeshProtocol.MAX_FRAME_BYTES) {
            Log.w(TAG, "кадр ${bytes.size} байт превышает лимит ${MeshProtocol.MAX_FRAME_BYTES}, отброшен")
            return
        }
        // Признак жизни канала — сами байты с провода, а не то, разберутся ли они
        // в валидный FromRadio: битый кадр всё равно доказывает, что линк не молчит.
        lastFrameAt = now()

        val frame = try {
            FromRadio.ADAPTER.decode(bytes)
        } catch (e: IOException) {
            // Один битый кадр не должен обрывать приём: это единственный канал,
            // по которому в приложение вообще попадают данные.
            Log.w(TAG, "не удалось разобрать FromRadio (${bytes.size} байт)", e)
            return
        }

        when (frame.config_complete_id) {
            MeshProtocol.CONFIG_NONCE -> {
                Log.i(TAG, "стадия 1 завершена, запрашиваем базу нод")
                sendToRadio(ToRadio(want_config_id = MeshProtocol.NODE_INFO_NONCE))
            }

            MeshProtocol.NODE_INFO_NONCE -> {
                watchdog?.cancel()
                Log.i(TAG, "handshake завершён")
                _connectionState.value = ConnectionState.Connected
                // Соединение состоялось — прошлые самостоятельные попытки больше не в
                // счёт, иначе редкие отказы за день исчерпали бы лимит.
                recoveryAttempts = 0
                startKeepAlive()
            }
        }

        if (_packets.trySend(frame).isFailure) {
            droppedFrameCount.incrementAndGet()
        }
        _packetLog.update { log -> (log + frame).takeLast(PACKET_LOG_LIMIT) }
    }

    private fun sendToRadio(message: ToRadio) {
        val active = transport
        if (active == null) {
            Log.w(TAG, "нет активного транспорта, пакет отброшен")
            return
        }
        active.send(message.encode())
    }

    /**
     * Страховка от «тихого» зависания: физическая связь есть, но нода не отвечает
     * на want_config_id. Без неё приложение осталось бы в Connecting навсегда.
     */
    private fun startHandshakeWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(handshakeTimeout)
            transportMutex.withLock {
                if (_connectionState.value == ConnectionState.Connecting) {
                    Log.w(TAG, "handshake не завершился за $handshakeTimeout, разрываем связь")
                    closeTransportLocked()
                    _connectionState.value = ConnectionState.Disconnected("нода не ответила на запрос конфигурации за $handshakeTimeout")
                    scheduleRecovery()
                }
            }
        }
    }

    /**
     * Поддержание связи и обнаружение «зомби»-сессии.
     *
     * Прошивка держит собственный таймер простоя и рвёт связь, если приложение
     * молчит (спека §7) — heartbeat закрывает эту сторону. Обратная сторона —
     * детектор тишины: если за [silenceTimeout] с провода не пришло ни одного
     * кадра (в том числе ответа на сам heartbeat), считаем стек Android
     * зависшим и рвём сессию сами, не дожидаясь колбэка от транспорта.
     *
     * Нонс обязан расти: у прошивки есть фильтр повторов на одинаковые записи,
     * и одинаковые байты она молча отбросит, а мы решим, что связь жива.
     */
    private fun startKeepAlive() {
        keepAlive?.cancel()
        lastFrameAt = now()
        keepAlive = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                sendToRadio(ToRadio(heartbeat = Heartbeat(nonce = heartbeatNonce.incrementAndGet())))
                val silence = now() - lastFrameAt
                if (silence > silenceTimeout.inWholeMilliseconds) {
                    Log.w(TAG, "нет кадров $silence мс — считаем сессию мёртвой")
                    // Закрываем транспорт сами, как это делает сторожевой таймер
                    // handshake. Одного лишь перевода состояния в Disconnected мало:
                    // зомби-сессия тем и опасна, что снизу о разрыве никто не
                    // сообщит, и брошенное GATT-соединение продолжило бы висеть до
                    // следующей попытки подключения.
                    // Закрытие и смена состояния — под одним замком, как в
                    // сторожевом таймере handshake. Если выставить состояние после
                    // освобождения замка, откроется окно, где транспорта уже нет, а
                    // connectionState всё ещё Connected: попавший в это окно connect()
                    // сработает по гейту идемпотентности вхолостую, и приложение
                    // останется без транспорта и без единой попытки переподключиться.
                    transportMutex.withLock {
                        closeTransportLocked()
                        _connectionState.value =
                            ConnectionState.Disconnected("нода перестала отвечать")
                        scheduleRecovery()
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Поднять транспорт заново после отказа, объявленного самим менеджером.
     *
     * Сторожевой таймер handshake и детектор тишины закрывают транспорт, а вместе
     * с ним умирает и его собственный цикл переподключения: создать транспорт
     * может только [connect]. Без этого метода приложение после такого отказа
     * стояло бы мёртвым до тапа пользователя, хотя нода могла просто
     * перезагружаться.
     *
     * Попыток ограниченное число. Нода, которая подключается, но не отвечает на
     * запрос конфигурации, сломана всерьёз, и бесконечный цикл лишь жёг бы
     * батарею и прятал причину. Исчерпав попытки, оставляем состояние с причиной
     * как есть. Счётчик обнуляется успешным handshake и действиями пользователя.
     */
    private fun scheduleRecovery() {
        val address = currentAddress ?: return
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.w(TAG, "попытки восстановления исчерпаны, ждём действия пользователя")
            return
        }
        recoveryAttempts++
        recovery?.cancel()
        recovery = scope.launch {
            delay(recoveryDelay)
            Log.i(TAG, "попытка восстановления $recoveryAttempts из $MAX_RECOVERY_ATTEMPTS")
            connect(address, byUser = false)
        }
    }

    private companion object {
        const val TAG = "RadioConnectionManager"
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val PACKET_QUEUE_CAPACITY = 256
        const val PACKET_LOG_LIMIT = 500
    }
}

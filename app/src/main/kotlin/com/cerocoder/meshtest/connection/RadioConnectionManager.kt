package com.cerocoder.meshtest.connection

import android.util.Log
import com.cerocoder.meshtest.transport.MeshProtocol
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import com.cerocoder.meshtest.transport.RadioTransportFactory
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio
import java.io.IOException
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
) : RadioTransportCallback {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
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

    var droppedFrames: Int = 0
        private set

    private val transportMutex = Mutex()
    private var transport: RadioTransport? = null
    private var currentAddress: String? = null
    private var watchdog: Job? = null

    /** Подключиться к устройству по внутреннему адресу. */
    fun connect(address: String) {
        scope.launch {
            transportMutex.withLock {
                // Идемпотентность: повторный тап по уже подключённому устройству не
                // должен пересоздавать транспорт — иначе останутся два транспорта,
                // пишущих в один канал.
                if (address == currentAddress && _connectionState.value != ConnectionState.Disconnected) {
                    Log.d(TAG, "уже подключены к этому адресу, повтор игнорируем")
                    return@withLock
                }
                // Снимаем сторожевой таймер прошлой сессии: иначе он может сработать уже по
                // новому транспорту и оборвать здоровое соединение.
                watchdog?.cancel()
                closeTransportLocked()
                _packetLog.value = emptyList()
                droppedFrames = 0
                currentAddress = address
                try {
                    val created = factory.create(address, this@RadioConnectionManager)
                    transport = created
                    created.start()
                } catch (e: Throwable) {
                    Log.w(TAG, "не удалось создать транспорт для адреса", e)
                    transport = null
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    /** Отключиться и освободить транспорт. */
    suspend fun disconnect() {
        withContext(NonCancellable) {
            transportMutex.withLock {
                watchdog?.cancel()
                transport?.let { active ->
                    // Вежливое прощание: даём ноде понять, что разрыв намеренный.
                    active.send(ToRadio(disconnect = true).encode())
                }
                closeTransportLocked()
                currentAddress = null
            }
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private suspend fun closeTransportLocked() {
        transport?.let { active ->
            try {
                active.close()
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

    override fun onDisconnect(isPermanent: Boolean) {
        watchdog?.cancel()
        Log.i(TAG, "связь потеряна (постоянно=$isPermanent)")
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun onDataReceived(bytes: ByteArray) {
        if (bytes.size > MeshProtocol.MAX_FRAME_BYTES) {
            Log.w(TAG, "кадр ${bytes.size} байт превышает лимит ${MeshProtocol.MAX_FRAME_BYTES}, отброшен")
            return
        }

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
            }
        }

        if (_packets.trySend(frame).isFailure) {
            droppedFrames++
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
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    private companion object {
        const val TAG = "RadioConnectionManager"
        const val PACKET_QUEUE_CAPACITY = 256
        const val PACKET_LOG_LIMIT = 500
    }
}

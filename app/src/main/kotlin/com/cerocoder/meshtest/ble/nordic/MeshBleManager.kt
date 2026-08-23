package com.cerocoder.meshtest.ble.nordic

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.asFlow
import no.nordicsemi.android.ble.ktx.state.ConnectionState
import no.nordicsemi.android.ble.ktx.stateAsFlow
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID

/**
 * Подключение к ноде Meshtastic поверх Nordic BLE Library.
 *
 * Библиотека берёт на себя то, ради чего её и выбрали: очередь GATT-операций
 * (Android не переваривает параллельные запросы), ретраи, согласование MTU и
 * обходы ошибок отдельных производителей.
 */
class MeshBleManager(context: Context) : BleManager(context) {

    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null

    private var subscriptionReady = CompletableDeferred<Unit>()

    /**
     * Поток уведомлений FROMNUM: значение не важно, важен сам факт.
     *
     * Инициализация однократная и намеренно ленивая. `setNotificationCallback`
     * в модели Nordic не добавляет слушателя, а заменяет единственного, причём
     * `asFlow()` закрывается пустым `awaitClose`: вытесненный коллектор не
     * получит ни ошибки, ни завершения — он просто навсегда замолчит. Геттер,
     * вычисляющий это заново на каждое обращение, превращал бы второе чтение
     * свойства в тихую потерю входящего потока. Ленивость нужна потому, что
     * `fromNum` появляется только внутри подключения, в
     * [isRequiredServiceSupported].
     */
    val notifications: Flow<Unit> by lazy {
        val characteristic = checkNotNull(fromNum) {
            "FROMNUM ещё не найден: подписка запрошена до подключения"
        }
        setNotificationCallback(characteristic).asFlow().map { }
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID) ?: return false
        toRadio = service.getCharacteristic(TORADIO_UUID)
        fromRadio = service.getCharacteristic(FROMRADIO_UUID)
        fromNum = service.getCharacteristic(FROMNUM_UUID)
        return toRadio != null && fromRadio != null && fromNum != null
    }

    override fun initialize() {
        // Android по умолчанию даёт ATT MTU 23, то есть 20 байт полезной нагрузки,
        // а кадры Meshtastic доходят до 512. Без этого запроса они не пролезут.
        requestMtu(MTU).enqueue()
        // Локальная ссылка обязательна: onServicesInvalidated подменяет поле, и
        // колбэк, замкнувшийся на поле, завершил бы уже другой Deferred, оставив
        // ожидающего висеть навсегда.
        val latch = subscriptionReady
        enableNotifications(fromNum)
            .done { latch.complete(Unit) }
            .fail { _, status -> latch.completeExceptionally(IllegalStateException("CCCD не записан, статус $status")) }
            .enqueue()
    }

    override fun onServicesInvalidated() {
        toRadio = null
        fromRadio = null
        fromNum = null
        subscriptionReady = CompletableDeferred()
    }

    /**
     * Подключиться к устройству.
     *
     * Спаривания здесь нет намеренно: оно выполняется до подключения, в
     * [ensureBondedBeforeConnect]. Вызов `ensureBond()` после `connect()` уже
     * опоздал бы — запись CCCD происходит внутри самого подключения, в
     * [initialize], и без шифрованного канала прошивка её отклоняет.
     *
     * @param autoConnect для спаренного устройства без свежей рекламы обязателен:
     *   прямое подключение на Android часто отваливается со статусом 133, особенно
     *   если нода использует меняющийся адрес.
     */
    suspend fun connectTo(device: BluetoothDevice, autoConnect: Boolean) {
        connect(device)
            .useAutoConnect(autoConnect)
            .retry(CONNECT_RETRIES, CONNECT_RETRY_DELAY_MS)
            .timeout(CONNECT_TIMEOUT_MS)
            .suspend()
    }

    /** Приостановиться до фактической записи CCCD. */
    suspend fun awaitReady() {
        subscriptionReady.await()
    }

    /**
     * Приостановиться до разрыва связи.
     *
     * `stateAsFlow()` — горячий поток с `replay = 1`, поэтому если связь успела
     * оборваться до подписки, текущее состояние придёт немедленно и ждать не
     * придётся. Наблюдателя подключений больше никто не ставит: библиотека
     * позволяет только одного и бросает при попытке поставить второго.
     */
    suspend fun awaitDisconnect(): String {
        val state = stateAsFlow().first { it is ConnectionState.Disconnected }
        return disconnectReasonText((state as ConnectionState.Disconnected).reason)
    }

    private fun disconnectReasonText(reason: ConnectionState.Disconnected.Reason): String = when (reason) {
        ConnectionState.Disconnected.Reason.LINK_LOSS -> "связь потеряна: нода вне зоны действия"
        ConnectionState.Disconnected.Reason.TERMINATE_PEER_USER -> "нода разорвала связь сама"
        ConnectionState.Disconnected.Reason.TERMINATE_LOCAL_HOST -> "связь разорвана телефоном"
        ConnectionState.Disconnected.Reason.TIMEOUT -> "нода не ответила на подключение"
        ConnectionState.Disconnected.Reason.NOT_SUPPORTED -> "у ноды нет нужного сервиса Meshtastic"
        ConnectionState.Disconnected.Reason.CANCELLED -> "подключение отменено"
        ConnectionState.Disconnected.Reason.SUCCESS -> "связь закрыта штатно"
        ConnectionState.Disconnected.Reason.UNKNOWN -> "связь потеряна по неизвестной причине"
    }

    suspend fun read(): ByteArray = readCharacteristic(fromRadio).suspend().value ?: ByteArray(0)

    suspend fun write(bytes: ByteArray) {
        writeCharacteristic(toRadio, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .suspend()
    }

    /**
     * Разорвать связь и освободить ресурсы библиотеки.
     *
     * Порядок важен: `close()` закрывает `BluetoothGatt`, но не разрывает ACL-связь.
     * Закрытие без предшествующего `disconnect()` — классическая причина того, что
     * соединение остаётся висеть до таймаута на стороне ноды, а следующая попытка
     * подключения падает со статусом 133. Цикл переподключения проходит здесь при
     * каждом разрыве, так что цена ошибки набегает быстро.
     */
    suspend fun release() {
        try {
            // Таймаут обязателен. Закрытие идёт под замком менеджера соединения, а
            // detector тишины существует именно потому, что стек Android умеет не
            // присылать колбэк никогда. Без ограничения такой случай навсегда
            // запер бы замок, и ни connect, ни disconnect больше не завершились бы.
            disconnect().timeout(DISCONNECT_TIMEOUT_MS).suspend()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Уже отключены или связь потеряна — это ожидаемо, закрывать всё равно надо.
            Log.d(TAG, "штатное отключение не удалось, закрываем принудительно", e)
        }
        try {
            close()
        } catch (e: Throwable) {
            Log.w(TAG, "ошибка при закрытии менеджера", e)
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

        private const val MTU = 512
        private const val CONNECT_RETRIES = 3
        private const val CONNECT_RETRY_DELAY_MS = 200
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
        private const val TAG = "MeshBleManager"
    }
}

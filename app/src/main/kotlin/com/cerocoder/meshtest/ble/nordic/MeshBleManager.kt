package com.cerocoder.meshtest.ble.nordic

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.asFlow
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

    /** Поток уведомлений FROMNUM: значение не важно, важен сам факт. */
    val notifications: Flow<Unit>
        get() = setNotificationCallback(fromNum).asFlow().map { }

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
        enableNotifications(fromNum)
            .done { subscriptionReady.complete(Unit) }
            .fail { _, status -> subscriptionReady.completeExceptionally(IllegalStateException("CCCD не записан, статус $status")) }
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
        ensureBond().suspend()
    }

    /** Приостановиться до фактической записи CCCD. */
    suspend fun awaitReady() {
        subscriptionReady.await()
    }

    suspend fun read(): ByteArray = readCharacteristic(fromRadio).suspend().value ?: ByteArray(0)

    suspend fun write(bytes: ByteArray) {
        writeCharacteristic(toRadio, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .suspend()
    }

    /** Разорвать связь и освободить ресурсы библиотеки. */
    fun release() {
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
        private const val TAG = "MeshBleManager"
    }
}

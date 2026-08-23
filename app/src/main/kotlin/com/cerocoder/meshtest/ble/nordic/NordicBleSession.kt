package com.cerocoder.meshtest.ble.nordic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.cerocoder.meshtest.ble.protocol.BleSession
import com.cerocoder.meshtest.ble.protocol.MeshGattClient

/** Сессия поверх [MeshBleManager]: держит менеджер и закрывает его. */
private class NordicBleSession(private val manager: MeshBleManager) : BleSession {

    override val client: MeshGattClient = NordicMeshGattClient(manager)

    override suspend fun close() = manager.release()
}

/**
 * Открыть сессию с нодой по MAC-адресу.
 *
 * Порядок: подключение (внутри него библиотека выполняет `initialize` —
 * запрос MTU и запись CCCD), затем `ensureBond`, затем ожидание того, что
 * подписка действительно состоялась.
 *
 * Известный риск, проверяемый вручную на живой ноде: характеристики
 * Meshtastic требуют шифрования, а CCCD пишется до бондинга. Если прошивка
 * отклонит запись, `awaitReady` бросит исключение, сессия закроется, и
 * попытку повторит цикл переподключения — к тому моменту Android обычно уже
 * инициировал бондинг сам. Если ручная приёмка покажет, что первая попытка
 * срывается всегда, бондинг придётся выносить до `connect` через
 * `device.createBond()`.
 */
@SuppressLint("MissingPermission")
suspend fun openNordicSession(context: Context, mac: String): BleSession {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: error("Bluetooth недоступен на этом устройстве")
    val device: BluetoothDevice = adapter.getRemoteDevice(mac)
    val bonded = device.bondState == BluetoothDevice.BOND_BONDED

    val manager = MeshBleManager(context)
    try {
        // Спаренному устройству без свежей рекламы нужен терпеливый autoConnect.
        manager.connectTo(device, autoConnect = bonded)
        manager.awaitReady()
    } catch (e: Throwable) {
        manager.release()
        throw e
    }
    return NordicBleSession(manager)
}

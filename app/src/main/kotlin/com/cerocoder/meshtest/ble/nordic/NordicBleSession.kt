package com.cerocoder.meshtest.ble.nordic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import com.cerocoder.meshtest.ble.protocol.BleFailure
import com.cerocoder.meshtest.ble.protocol.BleSession
import com.cerocoder.meshtest.ble.protocol.MeshGattClient

/** Сессия поверх [MeshBleManager]: держит менеджер и закрывает его. */
private class NordicBleSession(private val manager: MeshBleManager) : BleSession {

    override val client: MeshGattClient = NordicMeshGattClient(manager)

    override suspend fun awaitDisconnect(): String = manager.awaitDisconnect()

    override suspend fun close() = manager.release()
}

/**
 * Открыть сессию с нодой по MAC-адресу.
 *
 * Порядок жёсткий: спаривание, затем подключение, затем ожидание подписки.
 * Причина в [ensureBondedBeforeConnect] — прошивке нужен шифрованный канал, и
 * подключение первым обрекает запись CCCD на отказ.
 *
 * `autoConnect` берётся по состоянию **до** спаривания. Устройство, которое мы
 * только что видели в скане и спариваем впервые, имеет свежую рекламу — к нему
 * подключаются напрямую. Уже спаренное устройство рекламы может и не давать, и
 * прямое подключение к нему выдаёт статус 133; там нужен терпеливый autoConnect.
 */
@SuppressLint("MissingPermission")
suspend fun openNordicSession(context: Context, mac: String): BleSession {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: error("Bluetooth недоступен на этом устройстве")
    val device: BluetoothDevice = adapter.getRemoteDevice(mac)
    val wasBonded = device.bondState == BluetoothDevice.BOND_BONDED

    ensureBondedBeforeConnect(device)

    val manager = MeshBleManager(context)
    try {
        manager.connectTo(device, autoConnect = wasBonded)
        manager.awaitReady()
    } catch (e: CancellationException) {
        manager.release()
        throw e
    } catch (e: Throwable) {
        manager.release()
        // Отказ описывается здесь, пока типы Nordic ещё под рукой: выше по стеку
        // живёт чистый JVM-код, которому знать про них незачем.
        throw BleFailure(describeBleFailure(e), e)
    }
    return NordicBleSession(manager)
}

package com.cerocoder.meshtest.ble.nordic

import com.cerocoder.meshtest.ble.protocol.MeshGattClient
import kotlinx.coroutines.flow.Flow

/** [MeshGattClient] поверх настоящего GATT. Логики здесь нет — только перевод вызовов. */
class NordicMeshGattClient(private val manager: MeshBleManager) : MeshGattClient {

    override val fromNumNotifications: Flow<Unit> get() = manager.notifications

    override suspend fun awaitSubscriptionReady() = manager.awaitReady()

    override suspend fun readFromRadio(): ByteArray = manager.read()

    override suspend fun writeToRadio(bytes: ByteArray) = manager.write(bytes)
}

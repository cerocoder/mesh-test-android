package com.cerocoder.meshtest.ble.nordic

import com.cerocoder.meshtest.ble.protocol.BleFailure
import com.cerocoder.meshtest.ble.protocol.MeshGattClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** [MeshGattClient] поверх настоящего GATT. Логики здесь нет — только перевод вызовов. */
class NordicMeshGattClient(private val manager: MeshBleManager) : MeshGattClient {

    override val fromNumNotifications: Flow<Unit> get() = manager.notifications

    override suspend fun awaitSubscriptionReady() = manager.awaitReady()

    override suspend fun readFromRadio(): ByteArray = described { manager.read() }

    override suspend fun writeToRadio(bytes: ByteArray) = described { manager.write(bytes) }

    /**
     * Обернуть отказ Nordic в описанный [BleFailure].
     *
     * Делается на самой границе: выше живёт чистый JVM-код, который обязан
     * донести причину до экрана, но знать про типы библиотеки не должен.
     */
    private inline fun <T> described(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw BleFailure(describeBleFailure(e), e)
    }
}

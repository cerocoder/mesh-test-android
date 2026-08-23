package com.cerocoder.meshtest.ble.protocol

import kotlinx.coroutines.CompletableDeferred

/**
 * Управляемый двойник BLE-сессии.
 *
 * Позволяет тестировать логику переподключения и освобождения ресурсов
 * транспорта на чистой JVM без Android.
 */
class FakeBleSession(override val client: FakeMeshGattClient = FakeMeshGattClient()) : BleSession {

    /** Закрыта ли сессия. */
    var closed = false
        private set

    private val disconnected = CompletableDeferred<Unit>()

    /**
     * Сообщить о разрыве связи так, как это сделал бы стек Bluetooth.
     *
     * Отдельный рычаг нужен именно потому, что настоящий разрыв не проявляется
     * отказом какой-нибудь операции: связь просто замолкает.
     */
    fun signalDisconnect() {
        if (!disconnected.isCompleted) disconnected.complete(Unit)
    }

    override suspend fun awaitDisconnect() {
        disconnected.await()
    }

    override suspend fun close() {
        closed = true
    }
}

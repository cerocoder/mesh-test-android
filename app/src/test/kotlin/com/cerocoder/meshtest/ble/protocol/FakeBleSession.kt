package com.cerocoder.meshtest.ble.protocol

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

    override suspend fun close() {
        closed = true
    }
}

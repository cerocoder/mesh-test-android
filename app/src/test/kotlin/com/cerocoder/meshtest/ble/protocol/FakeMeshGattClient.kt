package com.cerocoder.meshtest.ble.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException

/**
 * Управляемый двойник GATT-клиента.
 *
 * Очередь кадров имитирует буфер прошивки: [readFromRadio] отдаёт их по одному
 * и возвращает пустой массив, когда очередь исчерпана — ровно как настоящая нода.
 */
class FakeMeshGattClient : MeshGattClient {

    private val queue = ArrayDeque<ByteArray>()
    private val notifications = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private val subscriptionReady = CompletableDeferred<Unit>()

    override val fromNumNotifications: SharedFlow<Unit> = notifications

    /** Записанные приложением кадры, в порядке отправки. */
    val writes = mutableListOf<ByteArray>()

    /** Сколько раз протокол обратился к FROMRADIO. */
    var reads = 0
        private set

    /** Если true, следующее чтение бросит IOException и сбросит флаг. */
    var failNextRead = false

    /** Положить кадры в очередь «прошивки». */
    fun enqueue(vararg frames: ByteArray) {
        queue.addAll(frames)
    }

    /** Сымитировать уведомление FROMNUM. */
    suspend fun emitNotification() {
        notifications.emit(Unit)
    }

    /** Разрешить протоколу продолжить после ожидания CCCD. */
    fun markSubscriptionReady() {
        if (!subscriptionReady.isCompleted) subscriptionReady.complete(Unit)
    }

    override suspend fun awaitSubscriptionReady() {
        subscriptionReady.await()
    }

    override suspend fun readFromRadio(): ByteArray {
        reads++
        if (failNextRead) {
            failNextRead = false
            throw IOException("сымитированный сбой чтения")
        }
        return queue.removeFirstOrNull() ?: ByteArray(0)
    }

    override suspend fun writeToRadio(bytes: ByteArray) {
        writes += bytes
    }
}

package com.cerocoder.meshtest.ble.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRadioProfileTest {

    private fun frame(marker: Byte) = byteArrayOf(marker, 0x01, 0x02)

    @Test
    fun `подписка вычитывает очередь до пустого ответа`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(1), frame(2), frame(3))
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()

        val job = launch { profile.fromRadio.take(3).toList(received) }
        client.markSubscriptionReady()
        client.emitNotification()
        advanceUntilIdle()
        job.join()

        assertEquals(3, received.size)
        assertArrayEquals(frame(1), received[0])
        assertArrayEquals(frame(3), received[2])
        // Четыре чтения: три кадра плюс одно пустое, закрывающее цикл.
        assertEquals(4, client.reads)
    }

    @Test
    fun `затравочное чтение выполняется без единой нотификации`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(7))
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()

        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()
        job.join()

        assertArrayEquals(
            "прошивка не шлёт FROMNUM до состояния отправки пакетов — цикл обязан стартовать сам",
            frame(7),
            received.single(),
        )
    }

    @Test
    fun `отправка в TORADIO триггерит вычитывание ответа`() = runTest {
        val client = FakeMeshGattClient()
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()
        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()

        client.enqueue(frame(9))
        profile.send(frame(8))
        advanceUntilIdle()
        job.join()

        assertArrayEquals(frame(8), client.writes.single())
        assertArrayEquals(frame(9), received.single())
    }

    @Test
    fun `транзиентная ошибка чтения не завершает поток`() = runTest {
        val client = FakeMeshGattClient()
        val profile = MeshRadioProfile(client)
        val received = mutableListOf<ByteArray>()
        val job = launch { profile.fromRadio.take(1).toList(received) }
        client.markSubscriptionReady()
        advanceUntilIdle()

        client.failNextRead = true
        client.emitNotification()
        advanceUntilIdle()

        client.enqueue(frame(5))
        client.emitNotification()
        advanceUntilIdle()
        job.join()

        assertArrayEquals(
            "после сбоя чтения поток обязан продолжить работу на следующей нотификации",
            frame(5),
            received.single(),
        )
    }

    @Test
    fun `протокол ждёт готовности подписки перед первым чтением`() = runTest {
        val client = FakeMeshGattClient()
        client.enqueue(frame(4))
        val profile = MeshRadioProfile(client)

        val job = launch { profile.fromRadio.take(1).toList() }
        advanceUntilIdle()

        assertEquals("до записи CCCD читать нельзя", 0, client.reads)

        client.markSubscriptionReady()
        advanceUntilIdle()
        job.join()

        assertTrue("после готовности подписки чтение обязано начаться", client.reads > 0)
    }
}

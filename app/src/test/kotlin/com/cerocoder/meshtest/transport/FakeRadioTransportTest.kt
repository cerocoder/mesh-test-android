package com.cerocoder.meshtest.transport

import com.cerocoder.meshtest.emulator.Scenarios
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.ToRadio
import kotlin.time.Duration.Companion.ZERO

/** Записывает всё, что транспорт отдаёт наверх. */
private class RecordingCallback : RadioTransportCallback {
    var connected = false
    var disconnectedPermanently: Boolean? = null
    val frames = mutableListOf<FromRadio>()

    override fun onConnect() {
        connected = true
    }

    override fun onDisconnect(isPermanent: Boolean, reason: String?) {
        disconnectedPermanently = isPermanent
    }

    override fun onDataReceived(bytes: ByteArray) {
        frames += FromRadio.ADAPTER.decode(bytes)
    }
}

class FakeRadioTransportTest {

    /**
     * Транспорт на невязанном тестовом диспетчере: корутины выполняются сразу при запуске,
     * поэтому порядок планирования не влияет на результат. Scope намеренно не потомок job
     * теста — SupervisorJob транспорта сам не завершается, и runTest ждал бы его вечно.
     */
    private fun TestScope.transport(callback: RadioTransportCallback) =
        FakeRadioTransport(
            scenario = requireNotNull(Scenarios.byId(Scenarios.FIVE_NODES_ID)),
            callback = callback,
            parentScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connectDelay = ZERO,
            frameDelay = ZERO,
        )

    @Test
    fun `после старта транспорт сообщает о подключении`() = runTest {
        val callback = RecordingCallback()

        transport(callback).start()
        advanceUntilIdle()

        assertTrue(callback.connected)
    }

    @Test
    fun `на запрос конфигурации отдаёт стадию 1 в правильном порядке`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        assertNotNull(callback.frames.first().my_info)
        assertEquals(MeshProtocol.CONFIG_NONCE, callback.frames.last().config_complete_id)
    }

    @Test
    fun `на запрос базы нод отдаёт стадию 2`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.NODE_INFO_NONCE).encode())
        advanceUntilIdle()

        assertEquals(5, callback.frames.count { it.node_info != null })
        assertEquals(MeshProtocol.NODE_INFO_NONCE, callback.frames.last().config_complete_id)
    }

    @Test
    fun `кадрам присваиваются возрастающие идентификаторы`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        val ids = callback.frames.map { it.id }
        assertTrue("кадры не доставлены — тест прошёл бы вхолостую", ids.isNotEmpty())
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `на heartbeat отвечает статусом очереди`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(heartbeat = Heartbeat(nonce = 7)).encode())
        advanceUntilIdle()

        assertEquals(1, callback.frames.count { it.queueStatus != null })
    }

    @Test
    fun `на прощальный пакет отвечает постоянным отключением`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(ToRadio(disconnect = true).encode())
        advanceUntilIdle()

        assertEquals(true, callback.disconnectedPermanently)
    }

    @Test
    fun `мусорные байты не роняют транспорт`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.send(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        advanceUntilIdle()

        assertTrue(callback.frames.isEmpty())
        assertNull(callback.disconnectedPermanently)
    }

    @Test
    fun `после close новые кадры не приходят`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback)

        subject.start()
        subject.close()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        assertTrue(callback.frames.isEmpty())
    }

    @Test
    fun `подтверждение стадии возвращает полученный нонс, а не константу`() = runTest {
        val callback = RecordingCallback()
        val scenario = requireNotNull(Scenarios.byId(Scenarios.FIVE_NODES_ID))

        val frames = scenario.configStageFrames(4242)

        assertEquals(4242, frames.last().config_complete_id)
    }
}

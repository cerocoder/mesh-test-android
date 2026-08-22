package com.cerocoder.meshtest.transport

import com.cerocoder.meshtest.emulator.Scenarios
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
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

    override fun onDisconnect(isPermanent: Boolean) {
        disconnectedPermanently = isPermanent
    }

    override fun onDataReceived(bytes: ByteArray) {
        frames += FromRadio.ADAPTER.decode(bytes)
    }
}

class FakeRadioTransportTest {

    private fun transport(callback: RadioTransportCallback, scope: kotlinx.coroutines.CoroutineScope) =
        FakeRadioTransport(
            scenario = requireNotNull(Scenarios.byId(Scenarios.FIVE_NODES_ID)),
            callback = callback,
            parentScope = scope,
            connectDelay = ZERO,
            frameDelay = ZERO,
        )

    @Test
    fun `после старта транспорт сообщает о подключении`() = runTest {
        val callback = RecordingCallback()

        transport(callback, backgroundScope).start()
        advanceUntilIdle()

        assertTrue(callback.connected)
    }

    @Test
    fun `на запрос конфигурации отдаёт стадию 1 в правильном порядке`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        assertNotNull(callback.frames.first().my_info)
        assertEquals(MeshProtocol.CONFIG_NONCE, callback.frames.last().config_complete_id)
    }

    @Test
    fun `на запрос базы нод отдаёт стадию 2`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.NODE_INFO_NONCE).encode())
        advanceUntilIdle()

        assertEquals(5, callback.frames.count { it.node_info != null })
        assertEquals(MeshProtocol.NODE_INFO_NONCE, callback.frames.last().config_complete_id)
    }

    @Test
    fun `кадрам присваиваются возрастающие идентификаторы`() = runTest {
        val callback = RecordingCallback()
        val subject = transport(callback, backgroundScope)

        subject.start()
        subject.send(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE).encode())
        advanceUntilIdle()

        val ids = callback.frames.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
    }
}

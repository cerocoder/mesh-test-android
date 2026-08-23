package com.cerocoder.meshtest.connection

import com.cerocoder.meshtest.emulator.Scenarios
import com.cerocoder.meshtest.transport.FakeRadioTransport
import com.cerocoder.meshtest.transport.MeshProtocol
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import com.cerocoder.meshtest.transport.RadioTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/** Фабрика, отдающая фейковый транспорт без задержек. */
private class TestFactory(private val scope: CoroutineScope) : RadioTransportFactory {
    var createdCount = 0

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
        createdCount++
        val scenarioId = requireNotNull(MeshProtocol.scenarioIdOrNull(address))
        return FakeRadioTransport(
            scenario = requireNotNull(Scenarios.byId(scenarioId)),
            callback = callback,
            parentScope = scope,
            connectDelay = ZERO,
            frameDelay = ZERO,
        )
    }
}

/** Транспорт, запоминающий порядок вызовов, чтобы проверить прощание перед закрытием. */
private class RecordingTransport(private val callback: RadioTransportCallback) : RadioTransport {
    val events = mutableListOf<String>()

    override fun start() {
        callback.onConnect()
    }

    override fun send(bytes: ByteArray) {
        val message = ToRadio.ADAPTER.decode(bytes)
        if (message.disconnect == true) events += "прощание"
    }

    override suspend fun close() {
        events += "закрытие"
    }
}

/** Транспорт, который подключается, но никогда не отвечает на запросы. */
private class SilentTransport(private val callback: RadioTransportCallback) : RadioTransport {
    override fun start() = callback.onConnect()
    override fun send(bytes: ByteArray) = Unit
    override suspend fun close() = Unit
}

private class SilentFactory : RadioTransportFactory {
    override fun create(address: String, callback: RadioTransportCallback): RadioTransport =
        SilentTransport(callback)
}

class RadioConnectionManagerTest {

    private fun TestScope.scope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `изначально соединение отсутствует`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        assertTrue(
            "ожидалось отключённое состояние, получено ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
    }

    @Test
    fun `полный handshake доводит состояние до Connected`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, manager.connectionState.value)
    }

    @Test
    fun `все ноды сценария попадают в лог пакетов`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(5, manager.packetLog.value.count { it.node_info != null })
    }

    @Test
    fun `порядок кадров в логе совпадает с порядком отправки`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        val ids = manager.packetLog.value.map { it.id }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `первым в логе идёт MyNodeInfo`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertTrue(manager.packetLog.value.first().my_info != null)
    }

    @Test
    fun `отключение возвращает состояние в Disconnected`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        manager.disconnect()
        advanceUntilIdle()

        assertTrue(
            "ожидалось отключённое состояние, получено ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
    }

    @Test
    fun `состояние остаётся Connecting пока не завершилась вторая стадия`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.onConnect()
        assertEquals(ConnectionState.Connecting, manager.connectionState.value)

        manager.onDataReceived(FromRadio(config_complete_id = MeshProtocol.CONFIG_NONCE).encode())
        assertEquals(
            "после первой стадии соединение ещё не готово",
            ConnectionState.Connecting,
            manager.connectionState.value,
        )

        manager.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_NONCE).encode())
        assertEquals(ConnectionState.Connected, manager.connectionState.value)
    }

    @Test
    fun `кадры проходят через packets в порядке отправки`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        val received = manager.packets.take(18).toList()

        assertNotNull("первым идёт MyNodeInfo", received.first().my_info)
        assertEquals(
            "последним — подтверждение второй стадии",
            MeshProtocol.NODE_INFO_NONCE,
            received.last().config_complete_id,
        )
        val ids = received.map { it.id }
        assertEquals("идентификаторы не переупорядочены", ids.sorted(), ids)
        assertEquals(5, received.count { it.node_info != null })
    }

    @Test
    fun `отключение отправляет прощальный кадр до закрытия транспорта`() = runTest {
        var transport: RecordingTransport? = null
        val factory = object : RadioTransportFactory {
            override fun create(address: String, callback: RadioTransportCallback): RadioTransport =
                RecordingTransport(callback).also { transport = it }
        }
        val manager = RadioConnectionManager(factory, scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        runCurrent()
        manager.disconnect()
        advanceUntilIdle()

        assertEquals(listOf("прощание", "закрытие"), requireNotNull(transport).events)
    }

    @Test
    fun `молчащая нода переводит соединение в Disconnected по таймауту`() = runTest {
        val manager = RadioConnectionManager(
            factory = SilentFactory(),
            scope = scope(),
            handshakeTimeout = 30.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertTrue(
            "ожидалось отключённое состояние, получено ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
    }

    @Test
    fun `до истечения таймаута соединение остаётся в Connecting`() = runTest {
        val manager = RadioConnectionManager(
            factory = SilentFactory(),
            scope = scope(),
            handshakeTimeout = 30.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(29.seconds)

        assertEquals(ConnectionState.Connecting, manager.connectionState.value)
    }

    @Test
    fun `повторное подключение к тому же адресу не пересоздаёт транспорт`() = runTest {
        val factory = TestFactory(scope())
        val manager = RadioConnectionManager(factory, scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(1, factory.createdCount)
    }

    @Test
    fun `подключение к другому адресу пересоздаёт транспорт`() = runTest {
        val factory = TestFactory(scope())
        val manager = RadioConnectionManager(factory, scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        manager.connect("m:${Scenarios.EMPTY_MESH_ID}")
        advanceUntilIdle()

        assertEquals(2, factory.createdCount)
    }

    @Test
    fun `слишком большой кадр отбрасывается`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        // Кадр валиден и декодируем — единственная причина не попасть в лог это защита по размеру.
        val oversized = FromRadio(node_info = NodeInfo(user = User(long_name = "x".repeat(600)))).encode()
        assertTrue("кадр должен превышать лимит", oversized.size > MeshProtocol.MAX_FRAME_BYTES)

        manager.onDataReceived(oversized)
        advanceUntilIdle()

        assertTrue(manager.packetLog.value.isEmpty())
    }

    @Test
    fun `битый кадр не попадает в лог и не роняет менеджер`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.onDataReceived(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        advanceUntilIdle()

        assertTrue(manager.packetLog.value.isEmpty())
    }

    @Test
    fun `после таймаута повторное подключение к тому же адресу создаёт транспорт заново`() = runTest {
        var created = 0
        val factory = object : RadioTransportFactory {
            override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
                created++
                return SilentTransport(callback)
            }
        }
        val manager = RadioConnectionManager(factory, scope(), handshakeTimeout = 30.seconds)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(31.seconds)
        advanceUntilIdle()
        assertTrue(
            "ожидалось отключённое состояние, получено ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(
            "после разрыва по таймауту тот же адрес должен подключаться заново",
            2,
            created,
        )
    }
}

package com.cerocoder.meshtest.connection

import com.cerocoder.meshtest.emulator.Scenarios
import com.cerocoder.meshtest.transport.FakeRadioTransport
import com.cerocoder.meshtest.transport.MeshProtocol
import com.cerocoder.meshtest.transport.RadioTransport
import com.cerocoder.meshtest.transport.RadioTransportCallback
import com.cerocoder.meshtest.transport.RadioTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO

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

class RadioConnectionManagerTest {

    @Test
    fun `изначально соединение отсутствует`() = runTest {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = RadioConnectionManager(TestFactory(testScope), testScope)

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `полный handshake доводит состояние до Connected`() = runTest {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = RadioConnectionManager(TestFactory(testScope), testScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, manager.connectionState.value)
    }

    @Test
    fun `все ноды сценария попадают в лог пакетов`() = runTest {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = RadioConnectionManager(TestFactory(testScope), testScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(5, manager.packetLog.value.count { it.node_info != null })
    }

    @Test
    fun `порядок кадров в логе совпадает с порядком отправки`() = runTest {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = RadioConnectionManager(TestFactory(testScope), testScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        val ids = manager.packetLog.value.map { it.id }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `первым в логе идёт MyNodeInfo`() = runTest {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = RadioConnectionManager(TestFactory(testScope), testScope)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertTrue(manager.packetLog.value.first().my_info != null)
    }

    @Test
    fun `отключение возвращает состояние в Disconnected`() = runTest {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = RadioConnectionManager(TestFactory(testScope), testScope)
        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        manager.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }
}

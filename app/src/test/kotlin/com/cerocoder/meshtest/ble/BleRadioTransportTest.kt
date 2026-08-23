package com.cerocoder.meshtest.ble

import com.cerocoder.meshtest.ble.protocol.FakeBleSession
import com.cerocoder.meshtest.transport.RadioTransportCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

private class RecordingCallback : RadioTransportCallback {
    var connects = 0
    var disconnects = 0
    val frames = mutableListOf<ByteArray>()

    override fun onConnect() {
        connects++
    }

    override fun onDisconnect(isPermanent: Boolean) {
        disconnects++
    }

    override fun onDataReceived(bytes: ByteArray) {
        frames += bytes
    }
}

class BleRadioTransportTest {

    private fun TestScope.scope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `открытая сессия доводит кадры до коллбэка`() = runTest {
        val callback = RecordingCallback()
        val session = FakeBleSession()
        session.client.enqueue(byteArrayOf(1), byteArrayOf(2))
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = { session },
        )

        transport.start()
        session.client.markSubscriptionReady()
        advanceTimeBy(4.seconds)
        advanceUntilIdle()

        assertEquals(1, callback.connects)
        assertEquals(2, callback.frames.size)
    }

    @Test
    fun `отказ открытия повторяется с откатом`() = runTest {
        val callback = RecordingCallback()
        var attempts = 0
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                attempts++
                throw IllegalStateException("нода недоступна")
            },
        )

        transport.start()
        advanceTimeBy(30.seconds)
        advanceUntilIdle()

        assertTrue("после отказа обязаны быть новые попытки, было $attempts", attempts >= 2)
        assertTrue("каждая неудача сообщается наверх", callback.disconnects >= 2)
    }

    @Test
    fun `сессия закрывается при завершении работы транспорта`() = runTest {
        val callback = RecordingCallback()
        val session = FakeBleSession()
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = { session },
        )

        transport.start()
        session.client.markSubscriptionReady()
        advanceTimeBy(4.seconds)
        advanceUntilIdle()
        transport.close()
        advanceUntilIdle()

        assertTrue("незакрытая сессия — это утёкшее GATT-соединение", session.closed)
    }

    @Test
    fun `после close переподключение прекращается`() = runTest {
        val callback = RecordingCallback()
        var attempts = 0
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                attempts++
                FakeBleSession()
            },
        )

        transport.start()
        advanceTimeBy(10.seconds)
        val afterClose = attempts
        transport.close()

        advanceTimeBy(120.seconds)
        advanceUntilIdle()

        assertEquals("закрытый транспорт не имеет права оживать", afterClose, attempts)
    }
}

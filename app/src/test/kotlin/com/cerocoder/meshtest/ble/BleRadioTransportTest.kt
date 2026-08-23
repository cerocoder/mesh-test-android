package com.cerocoder.meshtest.ble

import com.cerocoder.meshtest.ble.protocol.FakeBleSession
import com.cerocoder.meshtest.transport.RadioTransportCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
        // advanceUntilIdle() здесь звать нельзя: цикл переподключения бесконечен,
        // и прокрутка «до простоя» гоняла бы виртуальное время, пока runTest не
        // убьёт тест по таймауту.

        // Расписание: пауза 3 с перед каждой попыткой, откат 5, 10, 20 с. Значит
        // попытки приходятся на 3-ю, 11-ю и 24-ю секунды, а четвёртая ушла бы за
        // 44-ю. Точное число — это и есть проверка отката: без него попытка шла бы
        // каждые три секунды, то есть их набралось бы около десяти.
        assertEquals("откат не соблюдён: при нулевой паузе попыток было бы вдесятеро больше", 3, attempts)
        assertEquals("каждая неудача сообщается наверх", 3, callback.disconnects)

        transport.close()
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
    fun `молчаливый разрыв завершает сессию и запускает новую`() = runTest {
        val callback = RecordingCallback()
        val sessions = mutableListOf<FakeBleSession>()
        val transport = BleRadioTransport(
            mac = "AA:BB:CC:DD:EE:FF",
            callback = callback,
            parentScope = scope(),
            now = { currentTime },
            openSession = {
                FakeBleSession().also {
                    it.client.markSubscriptionReady()
                    sessions += it
                }
            },
        )

        transport.start()
        advanceTimeBy(4.seconds)
        assertEquals("первая сессия обязана открыться", 1, sessions.size)

        // Связь умирает молча: ни одна операция не падает, кадры просто перестают
        // приходить. Ровно так выглядит ушедшая из зоны нода — и ровно этот случай
        // раньше вешал цикл переподключения навсегда, потому что завершения сессии
        // никто не дожидался.
        sessions[0].signalDisconnect()
        advanceTimeBy(30.seconds)

        assertTrue("после разрыва обязана открыться новая сессия", sessions.size >= 2)
        assertTrue("сессия разорванной связи обязана быть закрыта", sessions[0].closed)

        transport.close()
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
                throw IllegalStateException("нода недоступна")
            },
        )

        transport.start()
        advanceTimeBy(30.seconds)
        val beforeClose = attempts
        // Без этой проверки тест был бы пустым: если цикл по любой причине встанет
        // на первой попытке, счётчик замрёт сам собой и равенство ниже сойдётся
        // даже при полностью сломанном close().
        assertTrue("цикл обязан крутиться до закрытия, иначе стеречь нечего", beforeClose >= 2)

        transport.close()
        advanceTimeBy(120.seconds)

        assertEquals("закрытый транспорт не имеет права оживать", beforeClose, attempts)
    }
}

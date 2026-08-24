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
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes
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
    var created = 0
        private set

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
        created++
        return SilentTransport(callback)
    }
}

/** Транспорт, который завершает handshake и после этого не шлёт ничего. */
private class SilentAfterConnectTransport(private val callback: RadioTransportCallback) : RadioTransport {
    override fun start() {
        callback.onConnect()
    }

    override fun send(bytes: ByteArray) {
        val message = ToRadio.ADAPTER.decode(bytes)
        when (message.want_config_id) {
            MeshProtocol.CONFIG_NONCE ->
                callback.onDataReceived(FromRadio(config_complete_id = MeshProtocol.CONFIG_NONCE).encode())

            MeshProtocol.NODE_INFO_NONCE ->
                callback.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_NONCE).encode())
        }
        // На heartbeat намеренно не отвечаем — именно так выглядит зомби-сессия:
        // физическая связь есть (транспорт не сообщал о разрыве), а данных больше нет.
    }

    var closed = false
        private set

    override suspend fun close() {
        closed = true
    }
}

private class SilentAfterConnectFactory : RadioTransportFactory {
    lateinit var last: SilentAfterConnectTransport
        private set

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport =
        SilentAfterConnectTransport(callback).also { last = it }
}

class RadioConnectionManagerTest {

    // scope() строится поверх backgroundScope, а не голого CoroutineScope(UnconfinedTestDispatcher(...)):
    // keepAlive из task-9 — это НАСТОЯЩИЙ бесконечный while(isActive) { delay(...) }, который сам себя
    // перепланирует, пока соединение живо. advanceUntilIdle() у kotlinx-coroutines-test 1.11.0 крутит
    // время вперёд, пока в очереди остаётся хоть одна foreground-задача, и останавливается, только когда
    // таких не осталось. Обычный (foreground) scope держит heartbeat вечно занятым в очереди — здоровое
    // соединение (FakeRadioTransport отвечает на каждый heartbeat) значит, что advanceUntilIdle() после
    // подключения никогда не увидит пустую очередь и зависнет навсегда, обрывая ВСЕ тесты, где handshake
    // доходит до Connected, а не только новые. Проверено эмпирически прогоном мини-репродукции на этой же
    // версии библиотеки: с обычным scope() advanceUntilIdle() зависает намертво; с задачами, помеченными
    // background (унаследовано от backgroundScope.coroutineContext), advanceUntilIdle() корректно
    // завершается, а advanceTimeBy(...) по-прежнему прокручивает background-задачи в своём окне — именно
    // поэтому все существующие тесты, использующие advanceTimeBy для таймаутов, не меняют поведение.
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

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

        assertEquals(5, manager.packetLog.value.count { it.frame.node_info != null })
    }

    @Test
    fun `порядок кадров в логе совпадает с порядком отправки`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        val ids = manager.packetLog.value.map { it.frame.id }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `первым в логе идёт MyNodeInfo`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertTrue(manager.packetLog.value.first().frame.my_info != null)
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

    @Test
    fun `после срыва handshake менеджер сам поднимает транспорт`() = runTest {
        val factory = SilentFactory()
        val manager = RadioConnectionManager(
            factory = factory,
            scope = scope(),
            handshakeTimeout = 30.seconds,
            recoveryDelay = 5.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(31.seconds)
        assertEquals("сторожевой таймер обязан закрыть первый транспорт", 1, factory.created)

        advanceTimeBy(6.seconds)

        // Сторожевой таймер закрывает транспорт, а вместе с ним и его собственный
        // цикл переподключения. Без этой правки приложение стояло бы мёртвым до
        // тапа пользователя, хотя нода могла просто перезагружаться.
        assertEquals("менеджер обязан поднять транспорт сам", 2, factory.created)
    }

    @Test
    fun `самовосстановление не бесконечно`() = runTest {
        val factory = SilentFactory()
        val manager = RadioConnectionManager(
            factory = factory,
            scope = scope(),
            handshakeTimeout = 30.seconds,
            recoveryDelay = 5.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        // Каждый круг это таймаут плюс пауза, то есть 35 секунд. Пяти минут хватает
        // на все попытки с большим запасом.
        advanceTimeBy(5.minutes)

        // Первый транспорт плюс три восстановления: нода, которая подключается, но
        // не отвечает, сломана всерьёз, и вечный цикл лишь жёг бы батарею.
        assertEquals("попытки обязаны кончиться", 4, factory.created)

        // По этому признаку интерфейс гасит foreground-сервис. Пока попытки идут,
        // процесс обязан оставаться защищённым; после сдачи держать его незачем, а
        // уведомление о соединении лгало бы.
        val state = manager.connectionState.value
        assertTrue("состояние должно быть Disconnected, а было $state", state is ConnectionState.Disconnected)
        assertEquals(
            "после исчерпания попыток признак повторов обязан погаснуть",
            false,
            (state as ConnectionState.Disconnected).retrying,
        )
    }

    @Test
    fun `непостоянный разрыв помечается как продолжающиеся попытки`() {
        val manager = RadioConnectionManager(SilentFactory(), CoroutineScope(UnconfinedTestDispatcher()))

        // Ровно то, что присылает транспорт на каждом круге своего цикла: связь
        // потеряна, но он продолжает пытаться сам.
        manager.onDisconnect(isPermanent = false, reason = "нода вне зоны действия")

        val state = manager.connectionState.value as ConnectionState.Disconnected
        assertEquals("нода вне зоны действия", state.reason)
        assertEquals("сервис не должен гаснуть, пока транспорт пытается", true, state.retrying)
    }

    @Test
    fun `после подключения heartbeat уходит по расписанию`() = runTest {
        val factory = TestFactory(scope())
        val manager = RadioConnectionManager(factory, scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        val before = manager.packetLog.value.count { it.frame.queueStatus != null }

        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertTrue(
            "нода отвечает на heartbeat статусом очереди — значит он был отправлен",
            manager.packetLog.value.count { it.frame.queueStatus != null } > before,
        )
    }

    @Test
    fun `молчание дольше таймаута разрывает зомби-сессию`() = runTest {
        // now = { currentTime }: без виртуальных часов TestScope детектор тишины
        // сравнивал бы System.currentTimeMillis() (реальное время, которое за время
        // прогона теста почти не сдвигается) с ним же самим — тест прошёл бы
        // даже без работающего детектора, ничего не проверив.
        val factory = SilentAfterConnectFactory()
        val manager = RadioConnectionManager(
            factory = factory,
            scope = scope(),
            silenceTimeout = 60.seconds,
            now = { currentTime },
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        // Тишина проверяется только В МОМЕНТ отправки очередного heartbeat, а не
        // непрерывно, и сравнение строгое (>). При heartbeatInterval=30s и
        // silenceTimeout=60s пороговое условие впервые выполняется на ТРЕТЬЕМ
        // heartbeat: t=30s (тишина 30s, не больше 60s), t=60s (тишина ровно 60s,
        // не больше — строгое неравенство не пропускает), t=90s (тишина 90s,
        // больше 60s — разрыв). 91 секунда гарантированно захватывает этот тик.
        advanceTimeBy(91.seconds)
        advanceUntilIdle()

        assertTrue(
            "при тишине связь считается мёртвой, даже если стек молчит о разрыве",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
        // Одного состояния мало: зомби-сессия тем и опасна, что снизу о разрыве
        // никто не сообщит, и незакрытый транспорт остался бы висеть.
        assertTrue("транспорт зомби-сессии обязан быть закрыт", factory.last.closed)
    }

    @Test
    fun `лента хранит запись с размером и источником`() = runTest {
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:тест")
        runCurrent()
        val bytes = FromRadio(my_info = MyNodeInfo(my_node_num = 7)).encode()

        manager.onDataReceived(bytes)

        val record = manager.packetLog.value.single()
        assertEquals(1L, record.seq)
        assertEquals(bytes.size, record.sizeBytes)
        assertEquals("m:тест", record.sourceAddress)
        assertEquals(7, record.frame.my_info?.my_node_num)
    }

    @Test
    fun `время приёма берётся из часов, переданных менеджеру`() = runTest {
        val manager = RadioConnectionManager(
            SilentFactory(),
            scope(),
            now = { 1_700_000_009_000 },
        )
        manager.connect("m:тест")
        runCurrent()

        manager.onDataReceived(FromRadio(id = 1).encode())

        assertEquals(1_700_000_009_000, manager.packetLog.value.single().receivedAtMillis)
    }

    @Test
    fun `номер записи растёт и после того, как лента упёрлась в потолок`() = runTest {
        // Размер ленты насыщается на 500, а число принятых — нет. Именно из
        // номера последней записи экран берёт «принято за подключение»: сам
        // размер после насыщения перестал бы расти, и человек решил бы, что
        // приём встал.
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:тест")
        runCurrent()

        repeat(600) { manager.onDataReceived(FromRadio(id = it + 1).encode()) }

        assertEquals(500, manager.packetLog.value.size)
        assertEquals(600L, manager.packetLog.value.last().seq)
    }

    @Test
    fun `подключение очищает ленту и обнуляет номер`() = runTest {
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:первый")
        runCurrent()
        manager.onDataReceived(FromRadio(id = 1).encode())

        manager.connect("m:второй")
        runCurrent()

        assertTrue(manager.packetLog.value.isEmpty())

        manager.onDataReceived(FromRadio(id = 2).encode())
        assertEquals(1L, manager.packetLog.value.single().seq)
    }

    @Test
    fun `открытая запись не меняется после вытеснения`() = runTest {
        // Так проверяется требование «снимок»: экран держит запись, а не
        // позицию в списке. Держал бы позицию — после вытеснения тот же индекс
        // указал бы на другой кадр, и открытый вид молча подменился бы.
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:тест")
        runCurrent()
        manager.onDataReceived(FromRadio(my_info = MyNodeInfo(my_node_num = 7)).encode())
        val opened = manager.packetLog.value.single()

        repeat(600) { manager.onDataReceived(FromRadio(id = it + 1).encode()) }

        assertEquals(1L, opened.seq)
        assertEquals(7, opened.frame.my_info?.my_node_num)
        assertTrue(manager.packetLog.value.none { it.seq == 1L })
    }

    @Test
    fun `открытая запись не меняется после очистки ленты`() = runTest {
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:первый")
        runCurrent()
        manager.onDataReceived(FromRadio(my_info = MyNodeInfo(my_node_num = 7)).encode())
        val opened = manager.packetLog.value.single()

        manager.connect("m:второй")
        runCurrent()

        assertTrue(manager.packetLog.value.isEmpty())
        assertEquals(7, opened.frame.my_info?.my_node_num)
        assertEquals("m:первый", opened.sourceAddress)
    }
}

package com.cerocoder.meshtest.transport

import android.app.Application
import com.cerocoder.meshtest.ble.BleRadioTransport
import com.cerocoder.meshtest.emulator.Scenarios
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private object NoopCallback : RadioTransportCallback {
    override fun onConnect() = Unit
    override fun onDisconnect(isPermanent: Boolean, reason: String?) = Unit
    override fun onDataReceived(bytes: ByteArray) = Unit
}

/**
 * Заглушка контекста для конструктора фабрики.
 *
 * Фабрика лишь сохраняет контекст и передаёт его в отложенный [openSession] —
 * в этих тестах он не вызывается (BLE-сессия открывается только при
 * `BleRadioTransport.start`), поэтому достаточно объекта нужного типа.
 * `Application` выбран ради конструктора без аргументов: не нужно гадать про
 * нулабельность параметра `base` у `ContextWrapper`, а `testOptions.unitTests
 * .isReturnDefaultValues` в этом модуле уже делает вызовы на нём безопасными.
 */
private fun fakeContext() = Application()

class RadioTransportFactoryImplTest {

    @Test
    fun `демо-адрес отдаёт фейковый транспорт`() = runTest {
        val factory = RadioTransportFactoryImpl(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            isDebugBuild = true,
            context = fakeContext(),
        )

        val transport = factory.create("m:${Scenarios.FIVE_NODES_ID}", NoopCallback)

        assertTrue(transport is FakeRadioTransport)
    }

    @Test
    fun `в release-сборке демо-устройство недоступно`() = runTest {
        val factory = RadioTransportFactoryImpl(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            isDebugBuild = false,
            context = fakeContext(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            factory.create("m:${Scenarios.FIVE_NODES_ID}", NoopCallback)
        }
    }

    @Test
    fun `неизвестный сценарий отвергается`() = runTest {
        val factory = RadioTransportFactoryImpl(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            isDebugBuild = true,
            context = fakeContext(),
        )

        assertThrows(IllegalStateException::class.java) {
            factory.create("m:нет-такого", NoopCallback)
        }
    }

    @Test
    fun `BLE-адрес отдаёт BLE-транспорт`() = runTest {
        val factory = RadioTransportFactoryImpl(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            isDebugBuild = true,
            context = fakeContext(),
        )

        val transport = factory.create("xAA:BB:CC:DD:EE:FF", NoopCallback)

        assertTrue(transport is BleRadioTransport)
    }
}

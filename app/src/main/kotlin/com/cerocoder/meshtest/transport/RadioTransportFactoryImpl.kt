package com.cerocoder.meshtest.transport

import android.content.Context
import com.cerocoder.meshtest.ble.BleRadioTransport
import com.cerocoder.meshtest.ble.nordic.openNordicSession
import com.cerocoder.meshtest.emulator.Scenarios
import kotlinx.coroutines.CoroutineScope

/**
 * Выбирает реализацию транспорта по префиксу адреса.
 *
 * Это единственная точка ветвления между способами подключения: на этапе 2
 * здесь появится вторая ветка для BLE, и больше нигде менять ничего не придётся.
 */
class RadioTransportFactoryImpl(
    private val scope: CoroutineScope,
    private val isDebugBuild: Boolean,
    private val context: Context,
) : RadioTransportFactory {

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
        MeshProtocol.scenarioIdOrNull(address)?.let { scenarioId ->
            // Демо-устройства не должны существовать в релизе: адрес приходит извне
            // (список устройств, сохранённые настройки), и релизная сборка обязана
            // отказать даже если такой адрес каким-то образом сохранился.
            require(isDebugBuild) { "демо-устройства доступны только в debug-сборке" }
            val scenario = checkNotNull(Scenarios.byId(scenarioId)) { "неизвестный сценарий: $scenarioId" }
            return FakeRadioTransport(scenario = scenario, callback = callback, parentScope = scope)
        }

        MeshProtocol.bleMacOrNull(address)?.let { mac ->
            return BleRadioTransport(
                mac = mac,
                callback = callback,
                parentScope = scope,
                openSession = { address -> openNordicSession(context, address) },
            )
        }

        error("неизвестный формат адреса: $address")
    }
}

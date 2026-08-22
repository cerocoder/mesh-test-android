package com.cerocoder.meshtest

import com.cerocoder.meshtest.connection.RadioConnectionManager
import com.cerocoder.meshtest.emulator.Scenarios
import com.cerocoder.meshtest.transport.DeviceListEntry
import com.cerocoder.meshtest.transport.RadioTransportFactory
import com.cerocoder.meshtest.transport.RadioTransportFactoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Ручной контейнер зависимостей уровня приложения.
 *
 * Живёт столько же, сколько процесс: соединение переживает пересоздание Activity.
 */
class AppContainer(isDebugBuild: Boolean) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val factory: RadioTransportFactory = RadioTransportFactoryImpl(scope, isDebugBuild)

    val connectionManager = RadioConnectionManager(factory, scope)

    /** В release демо-устройств нет, а BLE-сканирование появится на этапе 2. */
    val devices: List<DeviceListEntry> =
        if (isDebugBuild) {
            Scenarios.all.map { DeviceListEntry.Demo(it.id, it.displayName) }
        } else {
            emptyList()
        }
}

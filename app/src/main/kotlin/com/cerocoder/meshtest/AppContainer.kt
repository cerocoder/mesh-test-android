package com.cerocoder.meshtest

import android.content.Context
import android.util.Log
import com.cerocoder.meshtest.ble.BleScanner
import com.cerocoder.meshtest.ble.BluetoothAvailability
import com.cerocoder.meshtest.ble.nordic.BleScannerImpl
import com.cerocoder.meshtest.connection.RadioConnectionManager
import com.cerocoder.meshtest.emulator.Scenarios
import com.cerocoder.meshtest.transport.DeviceListEntry
import com.cerocoder.meshtest.transport.RadioTransportFactory
import com.cerocoder.meshtest.transport.RadioTransportFactoryImpl
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Ручной контейнер зависимостей уровня приложения.
 *
 * Живёт столько же, сколько процесс: соединение переживает пересоздание Activity.
 */
class AppContainer(
    private val context: Context,
    isDebugBuild: Boolean,
) {

    private val errors = CoroutineExceptionHandler { _, e ->
        Log.e("AppContainer", "необработанное исключение в области приложения", e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    /** Состояние разрешений и адаптера — для экрана устройств. */
    val availability = BluetoothAvailability(context)

    /** Поиск нод в эфире. */
    val scanner: BleScanner = BleScannerImpl()

    private val factory: RadioTransportFactory =
        RadioTransportFactoryImpl(scope, isDebugBuild, context)

    val connectionManager = RadioConnectionManager(factory, scope)

    /** Демо-устройства только в debug; реальные приходят из сканера. */
    val devices: List<DeviceListEntry> =
        if (isDebugBuild) {
            Scenarios.all.map { DeviceListEntry.Demo(it.id, it.displayName) }
        } else {
            emptyList()
        }
}

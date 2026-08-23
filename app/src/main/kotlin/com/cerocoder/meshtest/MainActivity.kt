package com.cerocoder.meshtest

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cerocoder.meshtest.ble.BleReadiness
import com.cerocoder.meshtest.connection.ConnectionState
import com.cerocoder.meshtest.service.MeshForegroundService
import com.cerocoder.meshtest.transport.DeviceListEntry
import com.cerocoder.meshtest.ui.DeviceListScreen
import com.cerocoder.meshtest.ui.PacketLogScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Готовность Bluetooth. Живёт на уровне активности, а не композиции, потому
     * что перечитывать её нужно при каждом возврате на экран: разрешение выдают
     * в системных настройках, а адаптер включают шторкой — оба события
     * происходят вне приложения. Без этого один отказ в диалоге запирал
     * пользователя на пояснительном тексте до перезапуска процесса.
     */
    private val readinessState = mutableStateOf(BleReadiness.UNSUPPORTED)

    override fun onResume() {
        super.onResume()
        readinessState.value = (application as MeshTestApp).container.availability.check()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MeshTestApp).container
        readinessState.value = container.availability.check()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    var showLog by rememberSaveable { mutableStateOf(false) }
                    val state by container.connectionManager.connectionState.collectAsState()
                    val packets by container.connectionManager.packetLog.collectAsState()
                    val scope = rememberCoroutineScope()

                    val context = LocalContext.current
                    val found = remember { mutableStateMapOf<String, DeviceListEntry.Ble>() }
                    var readiness by readinessState

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { readiness = container.availability.check() }

                    // Разрешение на уведомления спрашивается заодно с Bluetooth, но
                    // в готовность не входит: без него приложение полностью работает,
                    // просто уведомление foreground-сервиса не показывается.
                    val requested = remember {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            container.availability.requiredPermissions + Manifest.permission.POST_NOTIFICATIONS
                        } else {
                            container.availability.requiredPermissions
                        }
                    }

                    // Сервис поднимается уже на стадии подключения, а не после её
                    // завершения: handshake занимает секунды, и всё это время процесс
                    // должен быть защищён от усыпления.
                    LaunchedEffect(state) {
                        when (state) {
                            ConnectionState.Connecting,
                            ConnectionState.Connected,
                            -> MeshForegroundService.start(context)

                            is ConnectionState.Disconnected -> MeshForegroundService.stop(context)
                        }
                    }

                    LaunchedEffect(readiness) {
                        when (readiness) {
                            BleReadiness.PERMISSIONS_MISSING ->
                                permissionLauncher.launch(requested)

                            // Сканируем, пока экран жив. Дедупликация по адресу: устройство
                            // повторяется при каждом объявлении.
                            BleReadiness.READY ->
                                container.scanner.scan().collect { found[it.mac] = it }

                            BleReadiness.ADAPTER_OFF, BleReadiness.UNSUPPORTED -> Unit
                        }
                    }

                    val allDevices = container.devices + found.values.sortedBy { it.name }

                    if (showLog) {
                        PacketLogScreen(packets = packets, onBack = { showLog = false })
                    } else {
                        DeviceListScreen(
                            devices = allDevices,
                            state = state,
                            readiness = readiness,
                            onSelect = { device -> container.connectionManager.connect(device.address) },
                            onDisconnect = { scope.launch { container.connectionManager.disconnect() } },
                            onOpenLog = { showLog = true },
                        )
                    }
                }
            }
        }
    }
}

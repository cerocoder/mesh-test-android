package com.cerocoder.meshtest

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
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
                    var connectRequested by rememberSaveable { mutableStateOf(false) }
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

                    // Адаптер включают из шторки, а она активность не останавливает —
                    // onResume в этом случае не вызывается, и без подписки на событие
                    // экран остался бы с устаревшей готовностью.
                    DisposableEffect(Unit) {
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(ctx: Context?, intent: Intent?) {
                                readinessState.value = container.availability.check()
                            }
                        }
                        ContextCompat.registerReceiver(
                            context,
                            receiver,
                            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        onDispose { context.unregisterReceiver(receiver) }
                    }

                    // Сканирование прекращается, как только пользователь выбрал ноду.
                    // Скан в режиме низкой задержки одновременно с активной GATT-связью
                    // на многих телефонах сам по себе рвёт соединение — а обвиняли бы в
                    // этом цикл переподключения. Ключом стоит намерение, а не состояние
                    // соединения: состояние скачет на каждой попытке, и перезапуск скана
                    // упёрся бы в системный лимит в пять стартов за тридцать секунд.
                    val scanning = readiness == BleReadiness.READY && !showLog && !connectRequested

                    LaunchedEffect(readiness, scanning) {
                        when {
                            readiness == BleReadiness.PERMISSIONS_MISSING ->
                                permissionLauncher.launch(requested)

                            // Дедупликация по адресу: устройство повторяется при каждом
                            // объявлении.
                            scanning -> container.scanner.scan().collect { found[it.mac] = it }
                        }
                    }

                    // Гасим сервис только когда попытки прекращены совсем. Пока цикл
                    // переподключения работает, процесс обязан оставаться защищённым —
                    // именно в паузах между попытками система его и усыпила бы. Признак
                    // берётся из состояния, потому что причина разрыва есть и у обычной
                    // неудачной попытки: по ней сдачу от продолжения не отличить.
                    LaunchedEffect(state) {
                        val current = state
                        if (current is ConnectionState.Disconnected && !current.retrying) {
                            connectRequested = false
                            MeshForegroundService.stop(context)
                        }
                    }

                    val allDevices = container.devices + found.values.sortedBy { it.name }

                    if (showLog) {
                        PacketLogScreen(
                            packets = packets,
                            // Открытие детального вида — задача 7: она заменит эту
                            // пустую лямбду на сохраняемое состояние выбранной записи.
                            onSelect = { },
                            onBack = { showLog = false },
                        )
                    } else {
                        DeviceListScreen(
                            devices = allDevices,
                            state = state,
                            readiness = readiness,
                            onSelect = { device ->
                                // Сервис привязан к намерению пользователя быть на связи,
                                // а не к текущему состоянию соединения. Причин две.
                                // Первая: startForegroundService из фона на Android 12+
                                // бросает ForegroundServiceStartNotAllowedException, а
                                // BleRadioTransport переподключается сам, своим циклом, и
                                // проводит состояние через Connecting когда угодно — в том
                                // числе пока приложение свёрнуто. Запуск отсюда, из тапа
                                // пользователя, всегда происходит на переднем плане.
                                // Вторая: каждая неудачная попытка внутри цикла проводит
                                // состояние через Disconnected, и привязка к состоянию
                                // гасила бы сервис ровно на время отката — то есть именно
                                // тогда, когда защита процесса и нужна.
                                connectRequested = true
                                MeshForegroundService.start(context)
                                container.connectionManager.connect(device.address)
                            },
                            onDisconnect = {
                                connectRequested = false
                                scope.launch {
                                    container.connectionManager.disconnect()
                                    MeshForegroundService.stop(context)
                                }
                            },
                            onOpenLog = { showLog = true },
                        )
                    }
                }
            }
        }
    }
}

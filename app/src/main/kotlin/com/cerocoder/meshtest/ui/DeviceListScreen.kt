package com.cerocoder.meshtest.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cerocoder.meshtest.ble.BleReadiness
import com.cerocoder.meshtest.connection.ConnectionState
import com.cerocoder.meshtest.transport.DeviceListEntry

@Composable
fun DeviceListScreen(
    devices: List<DeviceListEntry>,
    state: ConnectionState,
    readiness: BleReadiness,
    onSelect: (DeviceListEntry) -> Unit,
    onDisconnect: () -> Unit,
    onOpenLog: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text("Состояние: ${stateLabel(state)}", style = MaterialTheme.typography.titleMedium)

        Button(onClick = onOpenLog, modifier = Modifier.padding(top = 8.dp)) {
            Text("Лента пакетов")
        }

        Button(onClick = onDisconnect, modifier = Modifier.padding(top = 8.dp)) {
            Text("Отключиться")
        }

        val explanation = when (readiness) {
            BleReadiness.PERMISSIONS_MISSING -> "Нет разрешений Bluetooth — выдайте их, чтобы искать ноды."
            BleReadiness.ADAPTER_OFF -> "Bluetooth выключен — включите его, чтобы искать ноды."
            BleReadiness.UNSUPPORTED -> "Это устройство не поддерживает Bluetooth LE."
            BleReadiness.READY -> if (devices.isEmpty()) "Ноды поблизости не найдены. Поиск продолжается." else null
        }
        if (explanation != null) {
            Text(explanation, modifier = Modifier.padding(top = 16.dp))
        }

        LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
            items(devices) { device ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(device) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                    Text(deviceDetails(device), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * Вторая строка карточки устройства.
 *
 * Для живой ноды сканер приносит уровень сигнала и признак спаривания — по ним
 * и выбирают, к какой из нескольких нод подключаться. Показывать один адрес,
 * имея эти данные на руках, значило бы собирать их впустую.
 */
private fun deviceDetails(device: DeviceListEntry): String = when (device) {
    is DeviceListEntry.Demo -> device.address
    is DeviceListEntry.Ble -> buildString {
        append(device.mac)
        device.rssi?.let { append("  ·  $it dBm") }
        if (device.bonded) append("  ·  спарено")
    }
}

private fun stateLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected ->
        state.reason?.let { "отключено: $it" } ?: "отключено"

    ConnectionState.Connecting -> "подключение (идёт handshake)"
    ConnectionState.Connected -> "подключено"
}

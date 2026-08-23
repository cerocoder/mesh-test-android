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
import com.cerocoder.meshtest.connection.ConnectionState
import com.cerocoder.meshtest.transport.DeviceListEntry

@Composable
fun DeviceListScreen(
    devices: List<DeviceListEntry>,
    state: ConnectionState,
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

        if (devices.isEmpty()) {
            Text(
                "Устройств нет. Демо-устройства доступны только в debug-сборке, " +
                    "BLE-сканирование появится на этапе 2.",
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
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
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun stateLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected ->
        state.reason?.let { "отключено: $it" } ?: "отключено"

    ConnectionState.Connecting -> "подключение (идёт handshake)"
    ConnectionState.Connected -> "подключено"
}

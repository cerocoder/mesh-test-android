package com.cerocoder.meshtest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cerocoder.meshtest.ui.DeviceListScreen
import com.cerocoder.meshtest.ui.PacketLogScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MeshTestApp).container

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier) {
                    var showLog by rememberSaveable { mutableStateOf(false) }
                    val state by container.connectionManager.connectionState.collectAsState()
                    val packets by container.connectionManager.packetLog.collectAsState()
                    val scope = rememberCoroutineScope()

                    if (showLog) {
                        PacketLogScreen(packets = packets, onBack = { showLog = false })
                    } else {
                        DeviceListScreen(
                            devices = container.devices,
                            state = state,
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

package com.cerocoder.meshtest.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Готовность подсистемы Bluetooth к работе. */
enum class BleReadiness {
    READY,
    PERMISSIONS_MISSING,
    ADAPTER_OFF,
    UNSUPPORTED,
}

/**
 * Отвечает на вопрос «можем ли мы сейчас сканировать и подключаться».
 *
 * Спека требует, чтобы отсутствие разрешений и выключенный адаптер были
 * состояниями, а не исключениями: пользователь должен увидеть понятную причину,
 * а не пустой список устройств.
 */
class BluetoothAvailability(private val context: Context) {

    /** Разрешения, которые нужно запросить на этой версии Android. */
    val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun check(): BleReadiness {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: return BleReadiness.UNSUPPORTED
        val adapter = manager.adapter ?: return BleReadiness.UNSUPPORTED

        val granted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return BleReadiness.PERMISSIONS_MISSING

        return if (adapter.isEnabled) BleReadiness.READY else BleReadiness.ADAPTER_OFF
    }
}

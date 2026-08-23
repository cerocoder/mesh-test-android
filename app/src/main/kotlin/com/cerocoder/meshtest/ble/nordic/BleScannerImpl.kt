package com.cerocoder.meshtest.ble.nordic

import android.annotation.SuppressLint
import android.content.Context
import com.cerocoder.meshtest.ble.BleScanner
import com.cerocoder.meshtest.transport.DeviceListEntry
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import android.os.ParcelUuid

/**
 * Сканер с аппаратным фильтром по сервису Meshtastic.
 *
 * Фильтр задаётся на уровне ОС, а не в коде: так радиомодуль не будит процесс
 * на каждое чужое объявление, а список не засоряется наушниками и часами.
 */
class BleScannerImpl(private val context: Context) : BleScanner {

    @SuppressLint("MissingPermission")
    override fun scan(): Flow<DeviceListEntry.Ble> = callbackFlow {
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareFilteringIfSupported(true)
            .build()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(MeshBleManager.SERVICE_UUID))
                .build(),
        )

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.toEntry())
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it.toEntry()) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("сканирование не запустилось, код $errorCode"))
            }
        }

        scanner.startScan(filters, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toEntry(): DeviceListEntry.Ble = DeviceListEntry.Ble(
        name = device.name ?: "неизвестная нода",
        mac = device.address,
        bonded = device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED,
        rssi = rssi,
    )
}

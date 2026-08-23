package com.cerocoder.meshtest.ble.nordic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid
import android.util.Log
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

/**
 * Сканер с аппаратным фильтром по сервису Meshtastic.
 *
 * Фильтр задаётся на уровне ОС, а не в коде: так радиомодуль не будит процесс
 * на каждое чужое объявление, а список не засоряется наушниками и часами.
 */
class BleScannerImpl : BleScanner {

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

        // Буфер callbackFlow конечен, а trySend при переполнении молча теряет
        // значение. Потеря не смертельна — нода объявляет себя снова и снова, —
        // но без записи в лог настоящая проблема с давлением была бы невидима.
        val emit: (ScanResult) -> Unit = { result ->
            if (trySend(result.toEntry()).isFailure) {
                Log.w(TAG, "буфер сканера переполнен, находка отброшена")
            }
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emit(result)
            }

            // При setReportDelay(0) система пакетную доставку не использует, но
            // колбэк оставлен: он ничего не стоит и переживёт смену настроек.
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(emit)
            }

            override fun onScanFailed(errorCode: Int) {
                // Поток закрывается без исключения намеренно. Потребитель собирает
                // его внутри LaunchedEffect, где неперехваченное исключение убивает
                // композицию, то есть роняет приложение. А отказ здесь — штатное
                // дело: Android глушит слишком частые сканирования, и повторный
                // заход на экран получает именно этот код. Список остаётся с тем,
                // что успели найти, вместо аварийного завершения.
                Log.w(TAG, "сканирование не запустилось, код $errorCode")
                close()
            }
        }

        scanner.startScan(filters, settings, callback)
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Throwable) {
                // Разрешение могли отозвать между стартом и остановкой.
                Log.w(TAG, "не удалось остановить сканирование", e)
            }
        }
    }

    /**
     * Превратить результат скана в элемент списка.
     *
     * Имя берётся из рекламного пакета, а не из [BluetoothDevice.getName]:
     * начиная с Android 12 геттер требует `BLUETOOTH_CONNECT` и бросает
     * `SecurityException`, если выдан только `BLUETOOTH_SCAN`. Произошло бы это
     * на потоке системного колбэка, вне корутин, — то есть падением приложения,
     * которого не поймает ни `awaitClose`, ни потребитель потока.
     * `@SuppressLint` подавляет проверку линта, но не саму проверку ОС.
     *
     * `bondState` требует того же разрешения, поэтому обёрнут: спаренность —
     * лишь подсказка для списка, а транспорт всё равно перечитывает её заново
     * при подключении.
     */
    @SuppressLint("MissingPermission")
    private fun ScanResult.toEntry(): DeviceListEntry.Ble = DeviceListEntry.Ble(
        name = scanRecord?.deviceName ?: "неизвестная нода",
        mac = device.address,
        bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
        rssi = rssi,
    )

    private companion object {
        const val TAG = "BleScannerImpl"
    }
}

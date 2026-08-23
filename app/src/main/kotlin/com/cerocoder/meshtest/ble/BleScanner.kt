package com.cerocoder.meshtest.ble

import com.cerocoder.meshtest.transport.DeviceListEntry
import kotlinx.coroutines.flow.Flow

/** Поиск нод Meshtastic в эфире. */
interface BleScanner {

    /**
     * Найденные устройства. Поток живёт, пока на него подписаны, и повторяет
     * устройство при каждом новом объявлении — потребитель обязан
     * дедуплицировать по адресу.
     *
     * Отказ сканирования завершает поток нормально, без исключения: система
     * глушит слишком частые сканирования, и это не повод ронять экран.
     * Потребитель, которому нужен повтор, обязан подписаться заново — сам поток
     * попыток не возобновляет.
     */
    fun scan(): Flow<DeviceListEntry.Ble>
}

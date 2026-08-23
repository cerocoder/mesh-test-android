package com.cerocoder.meshtest.ble

import com.cerocoder.meshtest.transport.DeviceListEntry
import kotlinx.coroutines.flow.Flow

/** Поиск нод Meshtastic в эфире. */
interface BleScanner {

    /**
     * Найденные устройства. Поток живёт, пока на него подписаны, и повторяет
     * устройство при каждом новом объявлении — потребитель обязан
     * дедуплицировать по адресу.
     */
    fun scan(): Flow<DeviceListEntry.Ble>
}

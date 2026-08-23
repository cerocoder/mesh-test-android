package com.cerocoder.meshtest.transport

/** Пункт списка устройств. Адрес однозначно определяет транспорт. */
sealed class DeviceListEntry {

    abstract val name: String
    abstract val address: String

    /** Виртуальное устройство, проигрывающее сценарий. Только в debug-сборке. */
    data class Demo(val scenarioId: String, override val name: String) : DeviceListEntry() {
        override val address: String get() = "${MeshProtocol.DEMO_PREFIX}$scenarioId"
    }

    /** Реальная нода, найденная в эфире сканером. */
    data class Ble(
        override val name: String,
        val mac: String,
        val bonded: Boolean,
        val rssi: Int?,
    ) : DeviceListEntry() {
        override val address: String get() = "${MeshProtocol.BLE_PREFIX}$mac"
    }
}

// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// core/ble/src/commonMain/kotlin/org/meshtastic/core/ble/MeshtasticBleConstants.kt
// (лицензионный заголовок GPL-3.0 срезан; код приведён без изменений)
// ============================================================================

 */
package org.meshtastic.core.ble

import kotlin.uuid.Uuid

/** Constants for Meshtastic Bluetooth LE interaction. */
object MeshtasticBleConstants {
    /** Pattern for Meshtastic device names (e.g., Meshtastic_1234). */
    const val BLE_NAME_PATTERN = "^.*_([0-9a-fA-F]{4})$"

    /** The Meshtastic service UUID. */
    val SERVICE_UUID: Uuid = Uuid.parse("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

    /** Characteristic for sending data to the radio. */
    val TORADIO_CHARACTERISTIC: Uuid = Uuid.parse("f75c76d2-129e-4dad-a1dd-7866124401e7")

    /** Characteristic for receiving packet count notifications. */
    val FROMNUM_CHARACTERISTIC: Uuid = Uuid.parse("ed9da18c-a800-4f66-a670-aa7547e34453")

    /** Characteristic for reading data from the radio. */
    val FROMRADIO_CHARACTERISTIC: Uuid = Uuid.parse("2c55e69e-4993-11ed-b878-0242ac120002")

    /** Characteristic for receiving log notifications from the radio. */
    val LOGRADIO_CHARACTERISTIC: Uuid = Uuid.parse("5a3d6e49-06e6-4423-9944-e9de8cdf9547")

    // --- OTA Characteristics ---

    /** The Meshtastic OTA service UUID (ESP32 Unified OTA). */
    val OTA_SERVICE_UUID: Uuid = Uuid.parse("4FAFC201-1FB5-459E-8FCC-C5C9C331914B")

    /** Characteristic for writing OTA commands and firmware data. */
    val OTA_WRITE_CHARACTERISTIC: Uuid = Uuid.parse("62ec0272-3ec5-11eb-b378-0242ac130005")

    /** Characteristic for receiving OTA status notifications/ACKs. */
    val OTA_NOTIFY_CHARACTERISTIC: Uuid = Uuid.parse("62ec0272-3ec5-11eb-b378-0242ac130003")
}

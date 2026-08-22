// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// core/ble/src/commonMain/kotlin/org/meshtastic/core/ble/MeshtasticRadioProfile.kt
// (лицензионный заголовок GPL-3.0 срезан; код приведён без изменений)
// ============================================================================

 */
package org.meshtastic.core.ble

import kotlinx.coroutines.flow.Flow

/** A definition of the Meshtastic BLE Service profile. */
interface MeshtasticRadioProfile {
    /** The flow of incoming packets from the radio. */
    val fromRadio: Flow<ByteArray>

    /** The flow of incoming log packets from the radio. */
    val logRadio: Flow<ByteArray>

    /** Sends a packet to the radio. */
    suspend fun sendToRadio(packet: ByteArray)

    /**
     * Requests a drain of the FROMRADIO characteristic without writing to TORADIO.
     *
     * This is useful when the firmware has queued a response (e.g. `queueStatus` after a heartbeat) but did not send a
     * FROMNUM notification. Without an explicit drain trigger the response would sit unread until the next unrelated
     * FROMNUM notification arrives.
     */
    fun requestDrain() {}

    /**
     * Suspends until GATT notifications are enabled (CCCD written) for the primary observation characteristic.
     *
     * Callers should await this before triggering the Meshtastic handshake (`want_config_id`) to guarantee that FROMNUM
     * notifications will be delivered. The default implementation returns immediately for profiles where CCCD readiness
     * is not observable (e.g. fakes and non-BLE transports).
     */
    suspend fun awaitSubscriptionReady() {}
}

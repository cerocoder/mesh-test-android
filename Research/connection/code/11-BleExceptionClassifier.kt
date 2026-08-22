// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// core/ble/src/commonMain/kotlin/org/meshtastic/core/ble/BleExceptionClassifier.kt
// (лицензионный заголовок GPL-3.0 срезан; код приведён без изменений)
// ============================================================================

 */
@file:Suppress("MatchingDeclarationName") // File groups the classifier function and its result type.

package org.meshtastic.core.ble

import com.juul.kable.GattRequestRejectedException
import com.juul.kable.GattStatusException
import com.juul.kable.NotConnectedException
import com.juul.kable.UnmetRequirementException

/**
 * Classification of a BLE-layer exception for the transport layer to act on.
 *
 * @property isPermanent `true` if the condition cannot resolve without explicit user re-selection of the device.
 *   Currently always `false` — all known BLE exceptions can resolve without user intervention (BT toggling, permission
 *   grants, transient GATT errors). Reserved for future use.
 * @property gattStatus the platform GATT status code when available (Android-specific).
 * @property message a human-readable description of the failure.
 */
data class BleExceptionInfo(val isPermanent: Boolean, val gattStatus: Int? = null, val message: String)

/**
 * Inspects this [Throwable] and returns a [BleExceptionInfo] if it is a known Kable exception, or `null` if it is
 * unrelated to the BLE layer.
 *
 * This keeps Kable type knowledge inside `core:ble` so that `core:network` (and other consumers) can classify BLE
 * exceptions without depending on Kable directly.
 */
fun Throwable.classifyBleException(): BleExceptionInfo? = when (this) {
    is GattStatusException ->
        BleExceptionInfo(
            isPermanent = false,
            gattStatus = status,
            message = "GATT error (status $status): $message",
        )

    is NotConnectedException -> BleExceptionInfo(isPermanent = false, message = "Not connected")

    is GattRequestRejectedException ->
        BleExceptionInfo(isPermanent = false, message = "GATT request rejected (busy)")

    is UnmetRequirementException ->
        // Bluetooth disabled or runtime permission missing. Both can resolve without re-selecting the
        // device (user re-enables BT, or grants permission). Surface as transient so the transport keeps
        // retrying; UI can show a hint based on the message.
        BleExceptionInfo(isPermanent = false, message = message ?: "Bluetooth LE unavailable")

    else -> null
}

/**
 * GATT status codes that indicate the BLE session is irrecoverably broken.
 *
 * Used by [isSessionFatalBleException] and shared across the BLE stack so classification stays in one place.
 */
@Suppress("MagicNumber")
private val FATAL_GATT_STATUSES =
    setOf(
        // 0x08 — link-layer supervision timeout (peer out of range or asleep)
        8, // GATT_CONN_TIMEOUT
        // 0x13 — peer-initiated disconnect (firmware reboot, user power-cycle, nRF52 link drop).
        // The most common Meshtastic disconnect signal; without it fromRadio would spin-retry a dead link.
        19, // GATT_CONN_TERMINATE_PEER_USER
        // 0x16 — link manager protocol timeout (radio firmware/hardware hang)
        22, // GATT_CONN_LMP_TIMEOUT
        // 0x3E — connection establishment failed (discovered during connect handshake)
        62, // GATT_CONN_FAIL_ESTABLISH
        // 0x85 — generic connection failure; commonly fires at runtime against a stale GATT handle
        133, // GATT_ERROR
        // 0x81 — unrecoverable operation failure
        129, // GATT_FAILURE
    )

/**
 * Returns `true` if this throwable indicates the BLE session is irrecoverably broken and should be torn down
 * (triggering reconnection), as opposed to a transient condition that can be retried.
 *
 * Also checks the cause chain — if a session-fatal exception is wrapped inside another exception (e.g., by coroutine
 * machinery or retry logic), it is still detected. Depth-limited to prevent stack overflow on malformed cause chains.
 */
fun Throwable.isSessionFatalBleException(): Boolean = isSessionFatalBleExceptionInternal(maxDepth = 10)

private fun Throwable.isSessionFatalBleExceptionInternal(maxDepth: Int): Boolean {
    if (maxDepth <= 0) return false
    return when (this) {
        is NotConnectedException -> true

        is GattStatusException ->
            status in FATAL_GATT_STATUSES || cause?.isSessionFatalBleExceptionInternal(maxDepth - 1) ?: false

        else -> cause?.isSessionFatalBleExceptionInternal(maxDepth - 1) ?: false
    }
}

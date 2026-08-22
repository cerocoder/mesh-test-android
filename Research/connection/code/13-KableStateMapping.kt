// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// core/ble/src/commonMain/kotlin/org/meshtastic/core/ble/KableStateMapping.kt
// (лицензионный заголовок GPL-3.0 срезан; код приведён без изменений)
// ============================================================================

 */
package org.meshtastic.core.ble

import com.juul.kable.State

/**
 * Maps Kable's [State] to Meshtastic's [BleConnectionState].
 *
 * @param hasStartedConnecting whether we have seen a Connecting state. This is used to ignore the initial Disconnected
 *   state emitted by StateFlow upon subscription.
 * @return the mapped [BleConnectionState], or null if the state should be ignored.
 */
fun State.toBleConnectionState(hasStartedConnecting: Boolean): BleConnectionState? = when (this) {
    is State.Connecting -> BleConnectionState.Connecting

    is State.Connected -> BleConnectionState.Connected

    is State.Disconnecting -> BleConnectionState.Disconnecting

    is State.Disconnected ->
        if (hasStartedConnecting) BleConnectionState.Disconnected(status.toDisconnectReason()) else null
}

/**
 * Maps Kable's [State.Disconnected.Status] to [DisconnectReason].
 *
 * Groups platform-specific GATT/CBError codes into broad categories that the reconnect logic can act on without leaking
 * platform details.
 */
fun State.Disconnected.Status?.toDisconnectReason(): DisconnectReason = when (this) {
    null -> DisconnectReason.Unknown

    State.Disconnected.Status.CentralDisconnected -> DisconnectReason.LocalDisconnect

    State.Disconnected.Status.PeripheralDisconnected -> DisconnectReason.RemoteDisconnect

    State.Disconnected.Status.Failed,
    State.Disconnected.Status.L2CapFailure,
    -> DisconnectReason.ConnectionFailed

    State.Disconnected.Status.Timeout,
    State.Disconnected.Status.LinkManagerProtocolTimeout,
    -> DisconnectReason.Timeout

    State.Disconnected.Status.Cancelled -> DisconnectReason.Cancelled

    State.Disconnected.Status.EncryptionTimedOut -> DisconnectReason.EncryptionFailed

    State.Disconnected.Status.ConnectionLimitReached -> DisconnectReason.ConnectionFailed

    State.Disconnected.Status.UnknownDevice -> DisconnectReason.ConnectionFailed

    is State.Disconnected.Status.Unknown -> DisconnectReason.PlatformSpecific(status)
}

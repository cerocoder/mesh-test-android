// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// core/network/src/commonMain/kotlin/org/meshtastic/core/network/transport/HeartbeatSender.kt
// (лицензионный заголовок GPL-3.0 срезан; код приведён без изменений)
// ============================================================================

 */
package org.meshtastic.core.network.transport

import co.touchlab.kermit.Logger
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.ToRadio
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Shared heartbeat sender for Meshtastic radio transports.
 *
 * Constructs and sends a `ToRadio(heartbeat = Heartbeat(nonce = ...))` message to keep the firmware's idle timer from
 * expiring. Each call uses a monotonically increasing nonce to prevent the firmware's per-connection duplicate-write
 * filter from silently dropping it.
 *
 * @param sendToRadio callback to transmit the encoded heartbeat bytes to the radio
 * @param afterHeartbeat optional suspend callback invoked after sending (e.g. to schedule a drain)
 * @param logTag tag for log messages
 */
class HeartbeatSender(
    private val sendToRadio: (ByteArray) -> Unit,
    private val afterHeartbeat: (suspend () -> Unit)? = null,
    private val logTag: String = "HeartbeatSender",
) {
    @OptIn(ExperimentalAtomicApi::class)
    private val nonce = AtomicInt(0)

    /**
     * Sends a heartbeat to the radio.
     *
     * The firmware responds to heartbeats by queuing a `queueStatus` FromRadio packet, proving the link is alive and
     * keeping the local node's lastHeard timestamp current.
     */
    @OptIn(ExperimentalAtomicApi::class)
    suspend fun sendHeartbeat() {
        val n = nonce.fetchAndAdd(1)
        Logger.v { "[$logTag] Sending ToRadio heartbeat (nonce=$n)" }
        sendToRadio(ToRadio(heartbeat = Heartbeat(nonce = n)).encode())
        afterHeartbeat?.invoke()
    }
}

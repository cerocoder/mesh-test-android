// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// core/ble/src/commonMain/kotlin/org/meshtastic/core/ble/KableMeshtasticRadioProfile.kt
// (лицензионный заголовок GPL-3.0 срезан; код приведён без изменений)
// ============================================================================

 */
package org.meshtastic.core.ble

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.meshtastic.core.ble.MeshtasticBleConstants.FROMNUM_CHARACTERISTIC
import org.meshtastic.core.ble.MeshtasticBleConstants.FROMRADIO_CHARACTERISTIC
import org.meshtastic.core.ble.MeshtasticBleConstants.LOGRADIO_CHARACTERISTIC
import org.meshtastic.core.ble.MeshtasticBleConstants.TORADIO_CHARACTERISTIC
import kotlin.time.Duration.Companion.milliseconds

/**
 * [MeshtasticRadioProfile] implementation using Kable BLE characteristics.
 *
 * Uses the standard Meshtastic BLE protocol: FROMNUM notifications trigger polling reads on the FROMRADIO
 * characteristic. The firmware gates FROMNUM notifications behind `STATE_SEND_PACKETS`, so during the config handshake
 * we seed the drain trigger to poll proactively.
 */
class KableMeshtasticRadioProfile(private val service: BleService) : MeshtasticRadioProfile {

    private val toRadio = service.characteristic(TORADIO_CHARACTERISTIC)
    private val fromRadioChar = service.characteristic(FROMRADIO_CHARACTERISTIC)
    private val fromNum = service.characteristic(FROMNUM_CHARACTERISTIC)
    private val logRadioChar = service.characteristic(LOGRADIO_CHARACTERISTIC)

    /**
     * Cached preferred write type for [toRadio]. Resolved once at construction so the hot send path doesn't have to
     * walk the discovered services list on every packet.
     */
    private val toRadioWriteType: BleWriteType = service.preferredWriteType(toRadio)

    companion object {
        private val TRANSIENT_RETRY_DELAY = 500.milliseconds
    }

    private val subscriptionReady = CompletableDeferred<Unit>()

    /**
     * Latched signal: a single buffered slot collapses bursts of drain triggers into one pending poll. Capacity 1 with
     * DROP_OLDEST means we never block writers and never let stale drain requests pile up.
     */
    private val triggerDrain =
        MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override val fromRadio: Flow<ByteArray> = channelFlow {
        launch {
            if (service.hasCharacteristic(fromNum)) {
                try {
                    service
                        .observe(fromNum) {
                            Logger.d { "FROMNUM CCCD written — notifications enabled" }
                            subscriptionReady.complete(Unit)
                        }
                        .collect { triggerDrain.tryEmit(Unit) }
                } catch (e: CancellationException) {
                    // Propagate cancellation — don't complete subscription as that would
                    // let setup proceed on a cancelled scope.
                    throw e
                } catch (e: Exception) {
                    // Complete subscriptionReady exceptionally so awaitSubscriptionReady()
                    // throws promptly instead of waiting for the transport's 5s timeout.
                    subscriptionReady.completeExceptionally(e)
                    throw e
                }
            } else {
                subscriptionReady.complete(Unit)
            }
        }
        triggerDrain.tryEmit(Unit)
        triggerDrain.collect {
            var keepReading = true
            while (keepReading) {
                try {
                    if (!service.hasCharacteristic(fromRadioChar)) {
                        keepReading = false
                        continue
                    }
                    val packet = service.read(fromRadioChar)
                    if (packet.isEmpty()) keepReading = false else send(packet)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Session-fatal BLE exceptions must propagate so the transport layer detects the
                    // broken session via its .catch handler and triggers reconnection.
                    if (e.isSessionFatalBleException()) {
                        Logger.w(e) { "FROMRADIO read hit session-fatal BLE exception — propagating for reconnect" }
                        throw e
                    }
                    Logger.w(e) { "FROMRADIO read error (transient), pausing before next drain trigger" }
                    keepReading = false
                    delay(TRANSIENT_RETRY_DELAY)
                }
            }
        }
    }

    /**
     * Observes the LOGRADIO characteristic. Session-fatal exceptions propagate to trigger reconnect.
     *
     * Note: a transient (non-fatal) observation error terminates this flow permanently for the current session. Unlike
     * [fromRadio] which has a retry loop, [logRadio] does not recover from transient errors. This is intentional —
     * logRadio is diagnostic-only, and the flow is recreated on the next reconnect cycle.
     */
    override val logRadio: Flow<ByteArray> = flow {
        if (!service.hasCharacteristic(logRadioChar)) return@flow
        emitAll(
            service.observe(logRadioChar).catch { e ->
                if (e is CancellationException) throw e
                if (e.isSessionFatalBleException()) {
                    Logger.w(e) { "logRadio observation hit session-fatal BLE exception — propagating for reconnect" }
                    throw e
                }
                // logRadio is optional — log at debug for diagnostics but don't surface to callers.
                Logger.d(e) { "logRadio observation failure suppressed" }
            },
        )
    }

    override suspend fun sendToRadio(packet: ByteArray) {
        service.write(toRadio, packet, toRadioWriteType)
        triggerDrain.tryEmit(Unit)
    }

    override fun requestDrain() {
        triggerDrain.tryEmit(Unit)
    }

    override suspend fun awaitSubscriptionReady() {
        subscriptionReady.await()
    }
}

// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// Контракты транспортного слоя (core/repository) + handshake-константы
// ============================================================================

// ---------- core/repository/.../RadioTransport.kt ----------
 */
package org.meshtastic.core.repository

/**
 * Interface for hardware transports (BLE, Serial, TCP, etc.) that handles raw byte communication. This is the
 * KMP-compatible replacement for the legacy Android-specific IRadioInterface.
 */
interface RadioTransport {
    /** Sends a raw byte array to the radio hardware. */
    fun handleSendToRadio(p: ByteArray)

    /**
     * Initializes the transport after construction. Called by the factory once the transport has been fully created.
     *
     * This separates construction from side effects (connecting, launching coroutines), making transports easier to
     * test and reason about.
     */
    fun start() {}

    /**
     * If we think we are connected, but we don't hear anything from the device, we might be in a zombie state. This
     * function can be implemented by transports to see if we are really connected.
     */
    fun keepAlive() {}

    /**
     * Closes the connection to the device.
     *
     * Implementations that perform potentially-blocking teardown (e.g. BLE GATT disconnect) MUST run that work inside
     * `withContext(NonCancellable)` so a cancelled caller cannot skip cleanup, leaving the underlying resource leaked.
     * Callers must invoke this from a coroutine — it must never be called from a blocking context (no `runBlocking`).
     */
    suspend fun close()
}

// ---------- core/repository/.../RadioTransportCallback.kt ----------
 */
package org.meshtastic.core.repository

/**
 * Narrow callback interface for transport → service communication.
 *
 * Transport implementations ([RadioTransport]) need only these three methods to report lifecycle events and deliver
 * data. This replaces the previous pattern of passing the full [RadioInterfaceService] to transport constructors,
 * decoupling transports from the service layer.
 */
interface RadioTransportCallback {
    /** Called when the transport has successfully established a connection. */
    fun onConnect()

    /**
     * Called when the transport has disconnected.
     *
     * @param isPermanent true when the current connection attempt should stop retrying (e.g. user disconnect,
     *   permission denied, max retries exhausted), false when it may come back (e.g. BLE range, TCP transient).
     * @param errorMessage optional user-facing error message describing the disconnect reason.
     * @param reason optional structured reason when the transport can classify the disconnect without owning
     *   user-facing text.
     */
    fun onDisconnect(isPermanent: Boolean, errorMessage: String? = null, reason: TransportDisconnectReason? = null)

    /** Called when the transport has received raw data from the radio. */
    fun handleFromRadio(bytes: ByteArray)
}

// ---------- core/repository/.../RadioTransportFactory.kt ----------
 */
package org.meshtastic.core.repository

import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.InterfaceId

/**
 * Creates [RadioTransport] instances for specific device addresses.
 *
 * Implemented per-platform to provide the correct hardware transport (BLE, Serial, TCP).
 */
interface RadioTransportFactory {
    /** The device types supported by this factory. */
    val supportedDeviceTypes: List<DeviceType>

    /** Whether we are currently forced into using a mock transport (e.g., Firebase Test Lab). */
    fun isMockTransport(): Boolean

    /** Creates a transport for the given [address], or a NOP implementation if invalid/unsupported. */
    fun createTransport(address: String, service: RadioInterfaceService): RadioTransport

    /** Checks if the given [address] represents a valid, supported transport type. */
    fun isAddressValid(address: String?): Boolean

    /** Constructs a full radio address for the specific [interfaceId] and [rest] identifier. */
    fun toInterfaceAddress(interfaceId: InterfaceId, rest: String): String
}

// ---------- core/repository/.../HandshakeConstants.kt ----------
 */
package org.meshtastic.core.repository

/**
 * Shared constants for the two-stage mesh handshake protocol.
 *
 * Stage 1 (`CONFIG_NONCE`): requests device config, module config, and channels. Stage 2 (`NODE_INFO_NONCE`): requests
 * the full node database.
 *
 * Both [MeshConfigFlowManager] (consumer) and [MeshConnectionManager] (sender) reference these.
 */
object HandshakeConstants {
    /** Nonce sent in `want_config_id` to request config-only (Stage 1). */
    const val CONFIG_NONCE = 69420

    /** Nonce sent in `want_config_id` to request node info only (Stage 2). */
    const val NODE_INFO_NONCE = 69421
}

// ---------- core/ble/.../BluetoothRepository.kt ----------
 */
package org.meshtastic.core.ble

import kotlinx.coroutines.flow.StateFlow

/** Repository responsible for Bluetooth availability and bonding. */
interface BluetoothRepository {
    /** The current state of Bluetooth on the device. */
    val state: StateFlow<BluetoothState>

    /** Refreshes the Bluetooth state. */
    fun refreshState()

    /** Returns true if the given address is valid. */
    fun isValid(bleAddress: String): Boolean

    /** Returns true if the given address is bonded. */
    fun isBonded(address: String): Boolean

    /** Initiates bonding with the given device. */
    suspend fun bond(device: BleDevice)

    /**
     * Removes any existing bond for [address]. Returns true if a bond was present and removal was initiated.
     *
     * Needed before connecting to a nRF Legacy-DFU bootloader that re-advertises at the *same* address as the app (e.g.
     * AdaDFU): a leftover bond makes the OS force stale link encryption the fresh bootloader can't satisfy, so it drops
     * the link on the first DFU command. Default no-op for platforms/impls that don't manage bonds.
     */
    suspend fun removeBond(address: String): Boolean = false
}

/** Represents the state of Bluetooth on the device. */
data class BluetoothState(
    /** True if the application has the required Bluetooth permissions. */
    val hasPermissions: Boolean = false,

    /** True if Bluetooth is enabled on the device. */
    val enabled: Boolean = false,

    /** A list of bonded devices. */
    val bondedDevices: List<BleDevice> = emptyList(),
)

// ---------- core/ble/.../BleScanner.kt + BleDevice.kt + BleConnectionState.kt ----------
 */
package org.meshtastic.core.ble

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** A scanner for BLE devices. */
interface BleScanner {
    /**
     * Scans for BLE devices.
     *
     * @param timeout The duration of the scan.
     * @return A [Flow] of discovered [BleDevice]s.
     */
    fun scan(timeout: Duration, serviceUuid: kotlin.uuid.Uuid? = null, address: String? = null): Flow<BleDevice>
}
 */
package org.meshtastic.core.ble

import kotlinx.coroutines.flow.StateFlow

/** Represents a BLE device. */
interface BleDevice {
    /** The device's name. */
    val name: String?

    /** The device's address. */
    val address: String

    /** The current connection state of the device. */
    val state: StateFlow<BleConnectionState>

    /** Whether the device is bonded. */
    val isBonded: Boolean

    /** Whether the device is currently connected. */
    val isConnected: Boolean

    /**
     * The RSSI reported by the most recent scan advertisement for this device, in dBm.
     *
     * `null` for devices that have not been observed via a scan (e.g. bonded-only devices retrieved from the OS). This
     * is a snapshot — to see live updates, observe a flow of [BleDevice] instances from [BleScanner].
     */
    val rssi: Int?
        get() = null

    /**
     * Reads the current RSSI value in dBm, or `null` when no reading is available (no live connection and no scan
     * advertisement). 0 dBm is the strongest value on this scale, so it must never stand in for "unknown".
     */
    suspend fun readRssi(): Int?

    /** Bond the device. */
    suspend fun bond()
}
 */
package org.meshtastic.core.ble

/** Represents the state of a BLE connection. */
sealed interface BleConnectionState {

    /**
     * The peripheral is disconnected.
     *
     * @param reason why the disconnect occurred. [DisconnectReason.Unknown] when the platform doesn't provide status
     *   information (e.g. JavaScript) or when the disconnect was synthesised locally without a GATT callback.
     */
    data class Disconnected(val reason: DisconnectReason = DisconnectReason.Unknown) : BleConnectionState

    /** The peripheral is connecting. */
    data object Connecting : BleConnectionState

    /** The peripheral is connected. */
    data object Connected : BleConnectionState

    /** The peripheral is disconnecting. */
    data object Disconnecting : BleConnectionState
}

/**
 * Platform-agnostic reason for a BLE disconnect.
 *
 * Mapped from Kable's [com.juul.kable.State.Disconnected.Status] in `KableStateMapping`.
 */
sealed interface DisconnectReason {
    /** Cause is unknown or the platform did not report one. */
    data object Unknown : DisconnectReason

    /** The local app/central initiated the disconnect. */
    data object LocalDisconnect : DisconnectReason

    /** The remote peripheral (firmware) initiated the disconnect. */
    data object RemoteDisconnect : DisconnectReason

    /** A connection attempt failed to establish. */
    data object ConnectionFailed : DisconnectReason

    /** The BLE link supervision timed out (device went out of range). */
    data object Timeout : DisconnectReason

    /** The connection was explicitly cancelled. */
    data object Cancelled : DisconnectReason

    /** An encryption or authentication failure occurred. */
    data object EncryptionFailed : DisconnectReason

    /** Platform-specific status code that doesn't map to a known reason. */
    data class PlatformSpecific(val code: Int) : DisconnectReason
}

// ---------- core/model/.../InterfaceId.kt ----------
 */
package org.meshtastic.core.model

/** Address identifiers for all supported radio backend implementations. */
enum class InterfaceId(val id: Char) {
    BLUETOOTH('x'),
    MOCK('m'),
    NOP('n'),
    REPLAY('r'),
    SERIAL('s'),
    TCP('t'),
    ;

    companion object {
        fun forIdChar(id: Char): InterfaceId? = entries.firstOrNull { it.id == id }
    }
}

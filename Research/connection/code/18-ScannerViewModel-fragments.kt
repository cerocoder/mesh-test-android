// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// feature/connections/.../ScannerViewModel.kt + AndroidScannerViewModel.kt
// Выборка ключевых фрагментов: UI-слой выбора устройства.
// ============================================================================

// ─────────────────────────────────────────────────────────────────────────
// Список BLE-устройств для UI: bonded (видимые в скане) + найденные сканом
// feature/connections/src/commonMain/kotlin/org/meshtastic/feature/connections/ScannerViewModel.kt : строки 236-290
// ─────────────────────────────────────────────────────────────────────────


    // ── Device lists for UI ──────────────────────────────────────────────────────────────────

    /**
     * BLE devices for the UI — restricted to those currently visible via an active scan.
     *
     * Previously bonded / system-paired peripherals that aren't advertising right now are intentionally excluded so the
     * list reflects what's actually nearby. The currently-selected device is the one exception: it's always kept so the
     * active connection stays visible (a connected radio stops advertising and would otherwise drop out).
     *
     * Sorted for stability to prevent "shifting" as advertisements arrive: bonded devices appear first (sorted by
     * name), followed by unbonded scanned devices in the order they were first discovered. RSSI updates are reflected
     * on the cards but do not trigger a re-sort.
     */
    val bleDevicesForUi: StateFlow<List<DeviceListEntry>> =
        combine(
            discoveredDevicesFlow,
            scannedBleDevices,
            discoveryOrder,
            radioInterfaceService.currentDeviceAddressFlow,
        ) { discovered, scannedMap, order, selectedAddress ->
            // Surface a bonded device only when it's currently visible via scan (advertising) or it's the selected
            // device — this hides stale system-bonded peripherals that aren't nearby.
            val bonded =
                discovered.bleDevices.filterIsInstance<DeviceListEntry.Ble>().filter {
                    it.address in scannedMap || it.fullAddress == selectedAddress
                }
            val bondedAddresses = bonded.mapTo(mutableSetOf()) { it.address }

            // Scanned-but-not-bonded devices are explicitly flagged unbonded so the UI routes through
            // requestBonding() — which on Android triggers createBond() for the pairing dialog before connecting.
            // Preserves discovery order to prevent items jumping around during the scan burst.
            val unbondedScanned =
                order
                    .filter { it !in bondedAddresses }
                    .mapNotNull { address ->
                        scannedMap[address]?.let { DeviceListEntry.Ble(device = it, bonded = false) }
                    }

            // For bonded devices, attach the latest scan RSSI (if we've seen an advertisement this session) so the
            // UI can show the signal indicator, but keep them sorted by name for stability.
            val bondedForUi =
                bonded
                    .map { entry ->
                        val scanned = scannedMap[entry.address]
                        if (scanned != null && scanned.rssi != null) entry.copy(device = scanned) else entry
                    }
                    .sortedBy { it.name }

            bondedForUi + unbondedScanned
        }
            .flowOn(dispatchers.default)
            .distinctUntilChanged()
            .stateInWhileSubscribed(initialValue = emptyList())


// ─────────────────────────────────────────────────────────────────────────
// Запуск/остановка BLE-скана (бесконечный поток по SERVICE_UUID)
// feature/connections/src/commonMain/kotlin/org/meshtastic/feature/connections/ScannerViewModel.kt : строки 348-441
// ─────────────────────────────────────────────────────────────────────────

    // ── Scan commands ────────────────────────────────────────────────────────────────────────

    /**
     * Starts BLE scanning. Enforces mutual exclusion (cancels any active network scan first). No-op if already scanning
     * or if [bleScanner] is null.
     *
     * The `finally` that clears [_isBleScanning] is guarded by a generation counter so a stale cancellation from a
     * prior scan cannot reset the flag on this new scan's state.
     */
    fun startBleScan() {
        if (_isBleScanning.value || bleScanner == null || scanStartFailureCooldownActive.value) return
        // Cancel the other scan first so only one flag is ever true. Both stop methods are idempotent.
        stopNetworkScan()

        _isBleScanning.value = true
        val generation = scanGeneration.value + 1
        scanGeneration.value = generation

        scanJob =
            safeLaunch(tag = "startBleScan") {
                try {
                    bleScanner
                        .scan(timeout = Duration.INFINITE, serviceUuid = MeshtasticBleConstants.SERVICE_UUID)
                        .flowOn(dispatchers.io)
                        .collect { device ->
                            scannedBleDevices.update { current ->
                                val existing = current[device.address]
                                // Replace if RSSI changed so the UI reflects the latest advertisement. Keep the same
                                // instance otherwise to avoid unnecessary recomposition.
                                if (existing != null && existing.rssi == device.rssi) {
                                    current
                                } else {
                                    current + (device.address to device)
                                }
                            }
                            if (device.address !in discoveryOrder.value) {
                                discoveryOrder.update { it + device.address }
                            }
                        }
                } catch (ex: BleScanStartException) {
                    handleBleScanStartFailure(ex, generation)
                } finally {
                    if (scanGeneration.value == generation) {
                        _isBleScanning.value = false
                        scanJob = null
                    }
                }
            }
    }

    /**
     * Starts BLE scanning for screen-entry auto-scan only when no device is already selected. Manual scan toggles call
     * [startBleScan] directly so users can still discover/switch devices while connected.
     */
    fun startBleAutoScan() {
        if (activeTransport.value != DeviceType.BLE) return
        val selectedAddress = selectedAddressFlow.value
        if (selectedAddress != null && selectedAddress != NO_DEVICE_SELECTED) return
        startBleScan()
    }

    /**
     * Cancels the active BLE scan and resets the scanning flag. Idempotent.
     *
     * Bumps [scanGeneration] so any in-flight `finally` from the cancelled job cannot reset `_isBleScanning` after a
     * subsequent [startBleScan] has flipped it back to `true`.
     */
    fun stopBleScan() {
        scanJob?.cancel()
        scanJob = null
        scanGeneration.value = scanGeneration.value + 1
        _isBleScanning.value = false
    }

    /**
     * Toggles BLE scanning. Persists the auto-scan preference only when the scan actually activates, and clears the
     * opposite [networkAutoScan] preference to keep persisted state consistent with the runtime mutual-exclusion
     * invariant.
     */
    fun toggleBleScan() {
        if (_isBleScanning.value) {
            stopBleScan()
            uiPrefs.setBleAutoScan(false)
        } else {
            startBleScan()
            // Only persist enable-intent (and clear the opposite pref) if start actually worked — e.g. not
            // blocked by a null bleScanner.
            if (_isBleScanning.value) {
                uiPrefs.setBleAutoScan(true)
                uiPrefs.setNetworkAutoScan(false)
            }
        }
    }


// ─────────────────────────────────────────────────────────────────────────
// Выбор устройства: stopAllScans -> (bond) -> setDeviceAddress
// feature/connections/src/commonMain/kotlin/org/meshtastic/feature/connections/ScannerViewModel.kt : строки 558-665
// ─────────────────────────────────────────────────────────────────────────

    // ── Device selection / disconnect ───────────────────────────────────────────────────────

    /** Asynchronously tells the radio controller to connect to [address]. */
    fun changeDeviceAddress(address: String) {
        Logger.i { "Attempting to change device address to ${address.anonymize()}" }
        safeLaunch(tag = "changeDeviceAddress") { radioController.setDeviceAddress(address) }
    }

    /**
     * Persists [address] in the recent-TCP list under [name]. No-op when [address] does not start with
     * [TCP_DEVICE_PREFIX].
     */
    fun addRecentAddress(address: String, name: String) {
        if (!address.startsWith(TCP_DEVICE_PREFIX)) return
        safeLaunch(tag = "addRecentAddress") { recentAddressesDataSource.add(RecentAddress(address, name)) }
    }

    /** Removes [address] from the recent-TCP list. */
    fun removeRecentAddress(address: String) {
        safeLaunch(tag = "removeRecentAddress") { recentAddressesDataSource.remove(address) }
    }

    /**
     * Connects to a manually-entered TCP address. Wraps the manual-entry flow with the same scan-cancel invariant as
     * [onSelected]: stops discovery before connection setup so the manual connect does not race an in-progress
     * BLE/network scan for radio resources.
     */
    fun connectToManualAddress(fullAddress: String) {
        val displayAddress = fullAddress.removePrefix(TCP_DEVICE_PREFIX)
        stopAllScans()
        uiPrefs.setSelectedConnectionTransport(DeviceType.TCP)
        addRecentAddress(fullAddress, displayAddress)
        changeDeviceAddress(fullAddress)
    }

    /**
     * Called by the UI when a device has been tapped. BLE and USB entries may still need bonding/permission — the
     * concrete return value tells the caller whether the connection was initiated immediately.
     *
     * @return `true` if the connection has been initiated; `false` if bonding/permission is pending.
     */
    fun onSelected(entry: DeviceListEntry): Boolean {
        // Stop discovery the moment the user picks a device, before any connection setup runs. The connect
        // attempt (BLE GATT or TCP) contends with an active BLE scan for the same radio resources during the
        // handshake; cancelling here keeps the lifecycle ordered: scan → stop → connect.
        stopAllScans()
        recordSelectedTransport(entry.fullAddress)
        radioPrefs.setDevName(entry.name)
        addRecentAddress(entry.fullAddress, entry.name)
        return when (entry) {
            is DeviceListEntry.Ble -> {
                if (entry.bonded) {
                    changeDeviceAddress(entry.fullAddress)
                    true
                } else {
                    requestBonding(entry)
                    false
                }
            }

            is DeviceListEntry.Usb -> {
                if (entry.bonded) {
                    changeDeviceAddress(entry.fullAddress)
                    true
                } else {
                    requestPermission(entry)
                    false
                }
            }

            is DeviceListEntry.Tcp -> {
                safeLaunch(tag = "onSelectedTcp") { changeDeviceAddress(entry.fullAddress) }
                true
            }

            is DeviceListEntry.Mock -> {
                changeDeviceAddress(entry.fullAddress)
                true
            }

            is DeviceListEntry.Replay -> {
                changeDeviceAddress(entry.fullAddress)
                true
            }
        }
    }

    /**
     * Initiates the bonding process and connects to the device upon success.
     *
     * The default implementation connects directly without explicit bonding, which is correct for Desktop/JVM where the
     * OS Bluetooth stack handles pairing during the GATT connection. Android overrides this to call `createBond()`
     * first.
     */
    protected open fun requestBonding(entry: DeviceListEntry.Ble) {
        changeDeviceAddress(entry.fullAddress)
    }

    /** Platform hook for requesting USB permission before connecting; default is a no-op. */
    protected open fun requestPermission(entry: DeviceListEntry.Usb) = Unit

    /** Clears the persisted device name and tells the radio controller to disconnect. */
    fun disconnect() {
        radioPrefs.setDevName(null)
        changeDeviceAddress(NO_DEVICE_SELECTED)
    }

    /**

// ─────────────────────────────────────────────────────────────────────────
// Android: bonding перед подключением
// feature/connections/src/androidMain/kotlin/org/meshtastic/feature/connections/AndroidScannerViewModel.kt : строки 74-120
// ─────────────────────────────────────────────────────────────────────────

    bleScanner,
) {
    override fun requestBonding(entry: DeviceListEntry.Ble) {
        Logger.i { "Starting bonding for ${entry.device.address.anonymize}" }
        viewModelScope.launch {
            @Suppress("TooGenericExceptionCaught")
            val armTransport =
                try {
                    bluetoothRepository.bond(entry.device)
                    Logger.i { "Bonding complete for ${entry.device.address.anonymize}, selecting device..." }
                    true
                } catch (ex: SecurityException) {
                    // No BLUETOOTH_CONNECT permission — connecting would fail the same way, so surface the
                    // error and do not arm the transport.
                    Logger.w(ex) { "Bonding failed for ${entry.device.address.anonymize} Permissions not granted" }
                    serviceRepository.setErrorMessage(
                        text = getString(Res.string.bonding_failed_permissions),
                        severity = Severity.Warn,
                    )
                    false
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    if (bluetoothRepository.isBonded(entry.device.address)) {
                        Logger.w(ex) {
                            "Bonding did not complete cleanly for ${entry.device.address.anonymize}, " +
                                "but Android now reports it bonded; selecting device"
                        }
                        true
                    } else {
                        Logger.w(ex) {
                            "Bonding did not complete cleanly for ${entry.device.address.anonymize}; " +
                                "waiting for an explicit retry"
                        }
                        serviceRepository.setErrorMessage(
                            text = getString(Res.string.bonding_failed_retry),
                            severity = Severity.Warn,
                        )
                        false
                    }
                }
            if (armTransport) {
                changeDeviceAddress(entry.fullAddress)
            }
        }
    }


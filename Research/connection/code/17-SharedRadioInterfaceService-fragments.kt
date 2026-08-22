// ============================================================================
// ИСТОЧНИК: Meshtastic-Android v2.8.0
// core/service/src/commonMain/kotlin/org/meshtastic/core/service/SharedRadioInterfaceService.kt
// Выборка ключевых фрагментов (файл целиком — 1094 строки).
// Роль: единственный владелец активного транспорта; сессии, heartbeat,
//       liveness, очередь входящих кадров, transport-level ConnectionState.
// ============================================================================

// ─────────────────────────────────────────────────────────────────────────
// Выбор устройства пользователем -> пересоздание транспорта
// core/service/src/commonMain/kotlin/org/meshtastic/core/service/SharedRadioInterfaceService.kt : строки 694-735
// ─────────────────────────────────────────────────────────────────────────


    override fun setDeviceAddress(deviceAddr: String?): Boolean {
        val sanitized = if (deviceAddr == "n" || deviceAddr.isNullOrBlank()) null else deviceAddr

        if (getBondedDeviceAddress() == sanitized && isStarted && _connectionState.value == ConnectionState.Connected) {
            Logger.w { "Ignoring setBondedDevice ${sanitized?.anonymize}, already using that device" }
            return false
        }

        val previousAddress = getBondedDeviceAddress()

        analytics.track("mesh_bond")

        Logger.d { "Setting bonded device to ${sanitized?.anonymize}" }
        radioPrefs.setDevAddr(sanitized)
        _currentDeviceAddressFlow.value = sanitized

        processLifecycle.coroutineScope.launch {
            transportMutex.withLock {
                // The sanitized address is the single source of truth for the connectionRequested
                // gate: a real address arms the lifecycle (connect() equivalent) so environmental
                // listeners cannot race the rebind into a "down" state; null/("n") is a deselect
                // that MUST clear the gate so subsequent BT/network recovery cannot resurrect a
                // transport for a device the user explicitly tore down. Only start a fresh
                // transport when an address was actually selected.
                connectionRequested = sanitized != null
                if (sanitized != null && previousAddress != null && sanitized != previousAddress) {
                    gattCacheInvalidationRequested.value = false
                }
                ignoreExceptionSuspend { stopTransportLocked() }
                if (sanitized != null) {
                    // setDeviceAddress() is fire-and-forget. startTransportLocked() has already rolled back any
                    // partially admitted session, so contain a recoverable factory failure instead of crashing the
                    // process-lifecycle scope. Explicit suspend restart callers still receive their failures.
                    ignoreExceptionSuspend { startTransportLocked() }
                }
            }
        }
        return true
    }

    /** Must be called under [transportMutex]. */

// ─────────────────────────────────────────────────────────────────────────
// Старт транспорта: новая сессия (generation) + фабрика транспорта
// core/service/src/commonMain/kotlin/org/meshtastic/core/service/SharedRadioInterfaceService.kt : строки 736-795
// ─────────────────────────────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private suspend fun startTransportLocked() {
        if (radioTransport != null) return

        // Never autoconnect to the simulated node. The mock transport may be offered in the
        // device-picker UI on debug builds, but it must only connect when the user explicitly
        // selects it (i.e. its address is stored in radioPrefs).
        val address = getBondedDeviceAddress()

        if (address == null) {
            Logger.d { "No valid address to connect to" }
            return
        }

        // Build a fresh per-instance session and admit it BEFORE constructing the transport so the wrapper captures
        // the new session. Bumping the public generation also signals downstream consumers (e.g.
        // RadioControllerImpl)
        // to invalidate session-scoped state from any previous transport instance.
        val generation = sessionGenerationCounter.incrementAndGet()
        val session = RadioTransportSession(generation = generation, address = address)
        synchronized(sessionCallbackLock) {
            check(activeTransportSession == null && admittedSessionOperations == 0) {
                "Cannot admit a transport while the previous session is still draining"
            }
            activeTransportSession = session
            sessionAdmissionOpen = true
            _activeSession.value = session.context
            _sessionGeneration.value = generation
        }
        val sessionBoundService = SessionBoundRadioInterfaceService(session)
        val connectionStateBeforeStart = _connectionState.value

        Logger.i { "Starting radio transport for ${address.anonymize} (generation=$generation)" }
        val newTransport =
            try {
                transportFactory.createTransport(address, sessionBoundService)
            } catch (failure: Throwable) {
                // A failed factory call consumed a generation that may already have reached observers. Keep the
                // generation monotonic, but revoke the admitted session and every transport-lifecycle field before
                // rethrowing the original failure. A later retry will receive a strictly newer generation.
                revokeTransportSession(session)
                radioTransport = null
                runningTransportId = null
                isStarted = false
                _connectionState.value = connectionStateBeforeStart
                throw failure
            }
        radioTransport = newTransport
        runningTransportId = address.firstOrNull()?.let { InterfaceId.forIdChar(it) }
        isStarted = true
        startHeartbeat()
    }

    /**
     * Must be called under [transportMutex].
     *
     * @param notifyPermanent When `true`, emits a permanent disconnect state to [connectionState]. Set `false` during
     *   automatic liveness recovery to avoid surfacing a user-facing disconnect.
     * @param sendPoliteDisconnect When `true`, sends a `ToRadio(disconnect = true)` frame to the firmware before
     *   tearing down. Set `false` when the transport is already dead (zombie session) to avoid writing into a broken

// ─────────────────────────────────────────────────────────────────────────
// Остановка транспорта: 'вежливое прощание' ToRadio(disconnect=true)
// core/service/src/commonMain/kotlin/org/meshtastic/core/service/SharedRadioInterfaceService.kt : строки 796-847
// ─────────────────────────────────────────────────────────────────────────

     *   link.
     */
    private suspend fun stopTransportLocked(notifyPermanent: Boolean = true, sendPoliteDisconnect: Boolean = true) =
        withContext(NonCancellable) { finishTransportTeardown(notifyPermanent, sendPoliteDisconnect) }

    /** Completes teardown after admission closes, even if its requester is cancelled while leases drain. */
    private suspend fun finishTransportTeardown(notifyPermanent: Boolean, sendPoliteDisconnect: Boolean) {
        val currentTransport = radioTransport
        val currentSession = synchronized(sessionCallbackLock) { activeTransportSession }
        // Reject queued callbacks and new suspend work immediately, then drain existing leases before admitting a
        // replacement generation or closing the old transport.
        revokeTransportSession(currentSession)
        Logger.i { "Stopping transport $currentTransport" }
        // Best-effort polite goodbye: tell the firmware we're disconnecting on purpose so it can
        // tear down its side of the link cleanly instead of relying on timeouts / hardware events.
        // Flip isStopping before sending so any concurrent sendToRadio() drops incoming traffic —
        // we don't want normal packets racing behind the disconnect frame. Skip only when already
        // Disconnected; firmware can still consume the goodbye while handshaking or sleeping, so
        // it's worth sending in every other state. The send is fire-and-forget through the
        // transport's own scope; the drain delay gives async transports a window to flush before
        // close() cancels their write scope. BLE's retry path backs off 500ms, so this window
        // also covers one retry on flaky GATT links.
        try {
            if (
                sendPoliteDisconnect &&
                currentTransport != null &&
                _connectionState.value != ConnectionState.Disconnected
            ) {
                isStopping = true
                ignoreExceptionSuspend {
                    currentTransport.handleSendToRadio(ToRadio(disconnect = true).encode())
                    delay(POLITE_DISCONNECT_DRAIN_MS)
                }
            }
        } finally {
            isStarted = false
            radioTransport = null
            runningTransportId = null
            isStopping = false
            try {
                currentTransport?.close()
            } finally {
                _serviceScope.cancel("stopping transport")
                _serviceScope = CoroutineScope(dispatchers.io + SupervisorJob())

                if (notifyPermanent && currentTransport != null) {
                    onDisconnect(isPermanent = true)
                }
            }
        }
    }


// ─────────────────────────────────────────────────────────────────────────
// Heartbeat (30 c) и детектор 'зомби'-соединения (только BLE)
// core/service/src/commonMain/kotlin/org/meshtastic/core/service/SharedRadioInterfaceService.kt : строки 848-942
// ─────────────────────────────────────────────────────────────────────────

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastDataReceivedMillis = now()
        heartbeatJob =
            serviceScope.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                    keepAlive()
                    checkLiveness()
                }
            }
    }

    /**
     * Detects zombie connections where the BLE stack didn't report a disconnect.
     *
     * If we believe we're connected but haven't received any data from the radio within [LIVENESS_TIMEOUT_MILLIS], the
     * connection is likely dead. Signal a non-permanent disconnect so the reconnect machinery can take over.
     *
     * Uses [clockMillis] for the current time so tests can inject a deterministic clock.
     */
    internal fun checkLiveness() {
        if (_connectionState.value != ConnectionState.Connected) return

        val silenceMs = now() - lastDataReceivedMillis
        if (silenceMs > LIVENESS_TIMEOUT_MILLIS) {
            // "Silence" = lastDataReceivedMillis not updated by handleFromRadio (no inbound
            // packets). Only BLE suffers from silent zombie sessions (no disconnect signal from
            // stack), so the liveness-restart path is BLE-only. For non-BLE transports we return
            // WITHOUT emitting a disconnect or mutating ConnectionState — there is no
            // transport-level timeout contract proving that silence past this threshold means
            // the session is dead.
            if (runningTransportId != InterfaceId.BLUETOOTH) {
                Logger.d { "Ignoring liveness timeout for non-BLE transport (silence: ${silenceMs}ms)" }
                return
            }

            Logger.w {
                "Liveness check failed: no data received for ${silenceMs}ms " +
                    "(threshold: ${LIVENESS_TIMEOUT_MILLIS}ms). Restarting BLE transport."
            }

            // Force transport restart to recover from silent zombie sessions where the BLE stack
            // did not report a disconnect. Uses the same processLifecycle scope and transportMutex
            // pattern as setDeviceAddress() to guarantee clean teardown/restart sequencing.
            // The onDisconnect notification is emitted INSIDE the compareAndSet guard so that a
            // double liveness-timeout (timer not cancelled between fires) does not produce
            // duplicate disconnect notifications for a single restart cycle.
            if (isRestarting.compareAndSet(expect = false, update = true)) {
                // Silent recovery: emit the non-permanent state transition (DeviceSleep) so the
                // reconnect machinery takes over, but do NOT pass an errorMessage. Automatic
                // liveness recovery is self-healing — surfacing a modal dialog for a transient
                // condition the app already handled is confusing UX. The warning log above
                // remains the observability surface for this event.
                //
                // Ordering note (pre-existing): onDisconnect fires here, BEFORE the launched
                // restart coroutine's `connectionRequested` check below. If an explicit disconnect()
                // races this timeout, a spurious DeviceSleep emission can leak to observers. The
                // connectionRequested gate still prevents the worse outcome — transport resurrection
                // — so this is a benign UI-level transient, not a state-machine bug.
                onDisconnect(isPermanent = false)
                processLifecycle.coroutineScope.launch {
                    try {
                        transportMutex.withLock {
                            // Defense against a race between checkLiveness() firing and a
                            // concurrent disconnect(): if the user has torn the connection down
                            // since the heartbeat scheduled this restart, leave it down. The
                            // transport is already null after disconnect()'s stopTransportLocked().
                            if (!connectionRequested) {
                                Logger.d { "Skipping liveness restart: connection no longer requested" }
                                return@withLock
                            }
                            ignoreExceptionSuspend {
                                stopTransportLocked(notifyPermanent = false, sendPoliteDisconnect = false)
                            }
                            // This restart is launched from a heartbeat callback and has no caller to receive a
                            // recoverable factory exception. The start path rolls back the failed session first;
                            // contain the rethrow here so a failed reconnect does not crash the app.
                            ignoreExceptionSuspend { startTransportLocked() }
                        }
                    } finally {
                        isRestarting.value = false
                    }
                }
            }
        }
    }

    fun keepAlive(now: Long = now()) {
        if (now - lastHeartbeatMillis > HEARTBEAT_INTERVAL_MILLIS) {
            radioTransport?.keepAlive()
            lastHeartbeatMillis = now
        }
    }


// ─────────────────────────────────────────────────────────────────────────
// Приём/отправка кадров: Channel вместо SharedFlow ради строгого FIFO
// core/service/src/commonMain/kotlin/org/meshtastic/core/service/SharedRadioInterfaceService.kt : строки 943-1020
// ─────────────────────────────────────────────────────────────────────────

    override fun sendToRadio(bytes: ByteArray) {
        if (isStopping) {
            Logger.d { "sendToRadio: transport stopping, dropping ${bytes.size} bytes" }
            return
        }
        // Snapshot the transport to avoid calling handleSendToRadio on a null reference.
        // There is still a benign race: stopTransportLocked() may cancel _serviceScope
        // between the null-check and the launch, causing the coroutine to be silently
        // dropped. This is acceptable — if the transport is shutting down, dropping the
        // send is the correct behavior.
        val currentTransport =
            radioTransport
                ?: run {
                    Logger.w { "sendToRadio: no active radio transport, dropping ${bytes.size} bytes" }
                    return
                }
        _serviceScope.handledLaunch {
            currentTransport.handleSendToRadio(bytes)
            _meshActivity.tryEmit(MeshActivity.Send)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun handleFromRadio(bytes: ByteArray) {
        val admitted =
            synchronized(sessionCallbackLock) {
                val session = activeTransportSession ?: return@synchronized false
                enqueueReceivedData(bytes, session)
                true
            }
        if (!admitted) {
            Logger.d { "Dropping ${bytes.size} received bytes without an active transport session" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun enqueueReceivedData(bytes: ByteArray, session: RadioTransportSession) {
        try {
            lastDataReceivedMillis = now()
            // trySend synchronously onto the Channel so packet order matches arrival order. The
            // previous `launch { emit() }` pattern dispatched each packet onto a fresh coroutine,
            // letting the scheduler reorder them — which broke the firmware config handshake
            // (see PhoneAPI.cpp initial-handshake sequence).
            // Reject before the copy: the channel bounds the frame COUNT, so without a per-frame ceiling a transport
            // handing over an oversized buffer defeats the memory bound the capacity is supposed to give. The stream
            // codec already enforces this on its own path; BLE passes through whatever the GATT read returned.
            if (bytes.size > MAX_FRAME_BYTES) {
                Logger.w { "Discarding oversized ${bytes.size}-byte frame (max $MAX_FRAME_BYTES)" }
                return
            }
            val frame = ReceivedRadioFrame(payload = bytes.toByteString(), session = session.context)
            val result = _receivedData.trySend(frame)
            if (result.isFailure) {
                // Rate-limited on purpose: drops only happen under sustained inbound traffic, and Kermit forwards to
                // Datadog/Crashlytics, so logging every drop would turn a bounded memory problem into unbounded
                // network and battery use. The counter is deliberately unsynchronised — a racy count only skews a
                // diagnostic line. A full queue reports failure with no exception; a closed channel carries one.
                val drops = ++droppedFrameCount
                if (drops == 1L || drops % DROP_LOG_INTERVAL == 0L) {
                    Logger.w(result.exceptionOrNull()) {
                        "Dropped ${bytes.size} received bytes ($drops total); receive queue at capacity " +
                            "$RECEIVE_QUEUE_CAPACITY or closed"
                    }
                }
            }
            _meshActivity.tryEmit(MeshActivity.Receive)
        } catch (t: Throwable) {
            Logger.e(t) { "handleFromRadio failed while emitting data" }
        }
    }

    override fun resetReceivedBuffer() {
        // Drain any bytes buffered while no collector was attached. Without this, a stop/start cycle
        // would replay stale bytes ahead of the next session's firmware handshake, since the channel
        // outlives the orchestrator's per-start scope.
        @Suppress("EmptyWhileBlock", "ControlFlowWithEmptyBody")
        while (_receivedData.tryReceive().isSuccess) {}
    }

// ─────────────────────────────────────────────────────────────────────────
// Публикация состояния + session-bound обёртка коллбэков транспорта
// core/service/src/commonMain/kotlin/org/meshtastic/core/service/SharedRadioInterfaceService.kt : строки 1021-1094
// ─────────────────────────────────────────────────────────────────────────


    override fun onConnect() {
        synchronized(sessionCallbackLock) { publishConnected() }
    }

    /** Applies a callback already admitted under [sessionCallbackLock]. */
    private fun publishConnected() {
        // MutableStateFlow.value is thread-safe (backed by atomics) — assign directly rather than
        // launching a coroutine. The async launch pattern introduced a window where a concurrent
        // onDisconnect launch could execute AFTER an onConnect launch, leaving the service stuck
        // in Connected while the transport was actually disconnected.
        lastDataReceivedMillis = now()
        if (_connectionState.value != ConnectionState.Connected) {
            Logger.d { "Broadcasting connection state change to Connected" }
            _connectionState.value = ConnectionState.Connected
        }
    }

    override fun onDisconnect(isPermanent: Boolean, errorMessage: String?, reason: TransportDisconnectReason?) {
        synchronized(sessionCallbackLock) { publishDisconnected(isPermanent, errorMessage, reason) }
    }

    /** Applies a callback already admitted under [sessionCallbackLock]. */
    private fun publishDisconnected(isPermanent: Boolean, errorMessage: String?, reason: TransportDisconnectReason?) {
        val resolvedErrorMessage = errorMessage ?: reason?.toConnectionErrorMessage()
        if (resolvedErrorMessage != null) {
            processLifecycle.coroutineScope.launch(dispatchers.default) { _connectionError.emit(resolvedErrorMessage) }
        }
        val newTargetState = if (isPermanent) ConnectionState.Disconnected else ConnectionState.DeviceSleep
        if (_connectionState.value != newTargetState) {
            Logger.d { "Broadcasting connection state change to $newTargetState" }
            _connectionState.value = newTargetState
        }
    }

    /**
     * Per-transport-session wrapper around this service. Delegates every [RadioInterfaceService] surface (scope,
     * address, sendToRadio, etc.) to the enclosing service; only the three [RadioTransportCallback] entry points are
     * gated on the captured [session] still being the [activeTransportSession]. Admission and the synchronous callback
     * side effect share [sessionCallbackLock] with teardown, eliminating a check-then-use window. Late callbacks from a
     * torn-down transport are dropped BEFORE bytes enter the shared received-data channel or connection/config state
     * mutates. Address is logged with [anonymize] and the session generation only — never the raw address bytes.
     */
    private inner class SessionBoundRadioInterfaceService(val session: RadioTransportSession) :
        RadioInterfaceService by this@SharedRadioInterfaceService {
        override fun onConnect() {
            val admitted = runIfTransportSessionActive(session, ::publishConnected)
            if (!admitted) {
                Logger.d { "Dropping stale onConnect gen=${session.generation} addr=${session.address.anonymize}" }
            }
        }

        override fun onDisconnect(isPermanent: Boolean, errorMessage: String?, reason: TransportDisconnectReason?) {
            val admitted =
                runIfTransportSessionActive(session) { publishDisconnected(isPermanent, errorMessage, reason) }
            if (!admitted) {
                Logger.d { "Dropping stale onDisconnect gen=${session.generation} addr=${session.address.anonymize}" }
            }
        }

        override fun handleFromRadio(bytes: ByteArray) {
            val admitted =
                runIfTransportSessionActive(session) {
                    this@SharedRadioInterfaceService.enqueueReceivedData(bytes, session)
                }
            if (!admitted) {
                Logger.d {
                    "Dropping stale handleFromRadio (${bytes.size} bytes) gen=${session.generation} " +
                        "addr=${session.address.anonymize}"
                }
            }
        }
    }
}

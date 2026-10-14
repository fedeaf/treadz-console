package com.treadz.console.devices

import com.treadz.console.hrm.DiscoveredStrap
import com.treadz.console.hrm.HeartRateMonitor
import com.treadz.console.hrm.HrmTransportException
import com.treadz.console.hrm.StrapConnectionState
import com.treadz.console.hrm.StrapProblem
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.settings.SettingsStore
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProfileRepository
import com.treadz.console.userdata.StrapSaveResult
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The heart rate the app is showing now and where it comes from; `bpm` is `null` without a usable reading. */
data class LiveHeartRate(val bpm: Int? = null, val source: HeartRateSourceKind? = null)

/** What the heart-rate strap of the active user looks like to the UI (top bar, Devices, pairing card). */
data class StrapUiState(
    /** The active user's strap as stored in the profile (`null` = none paired). */
    val address: String? = null,
    val name: String? = null,
    val connection: StrapConnectionState = StrapConnectionState.Inactive,
    /** Bluetooth off or permission missing: the only things about the strap the user can fix. */
    val problem: StrapProblem? = null,
    val batteryPercent: Int? = null,
    val heartRate: LiveHeartRate = LiveHeartRate(),
    val scanning: Boolean = false,
    val scanFinished: Boolean = false,
    val devices: List<DiscoveredStrap> = emptyList(),
    /** Why the last strap scan from Devices failed, if it did. */
    val scanProblem: StrapProblem? = null,
    /** A strap seen at start-up that the active user has never been asked about: "Use this strap?". */
    val pairingOffer: DiscoveredStrap? = null,
    /** Saving the strap to the profile failed (it stays as it was). */
    val saveFailed: Boolean = false,
) {
    val paired: Boolean get() = address != null
    val connected: Boolean get() = connection is StrapConnectionState.Connected

    /** The heart rate of the strap connected to the app, `null` when it is not the source or has no reading. */
    val strapBpm: Int? get() = heartRate.bpm.takeIf { connected && heartRate.source == HeartRateSourceKind.DirectStrap }
}

/**
 * Everything the app does with the heart-rate strap connected directly to it, apart from reading heart rate (the
 * runners and the workout history use the automatic source):
 *
 *  - keeps the monitor on the active user's strap: at start and on every user switch it calls
 *    [HeartRateMonitor.use] (`null` without a strap), silently; it is released only when the app exits;
 *  - offers, once, to pair the first strap seen at start-up when the active user has none and was never asked;
 *  - the strap scan and pairing of the Devices screen;
 *  - the live heart rate for the screens.
 *
 * [scope] lives as long as the process.
 */
class StrapController(
    private val monitor: HeartRateMonitor,
    private val source: HeartRateSource,
    /** Anything that changes when the treadmill console's heart rate may have changed (the controller's state). */
    consoleChanges: Flow<Any?>,
    private val profiles: ProfileRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val pairingScanMillis: Long = PAIRING_SCAN_MILLIS,
    private val scanMillis: Long = SCAN_MILLIS,
) {
    private data class Local(
        val scanning: Boolean = false,
        val scanFinished: Boolean = false,
        val devices: List<DiscoveredStrap> = emptyList(),
        val scanProblem: StrapProblem? = null,
        val offer: DiscoveredStrap? = null,
        val saveFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val started = CompletableDeferred<Unit>()

    @Volatile
    private var activeUser: UserProfile? = null

    @Volatile
    private var released = false

    @Volatile
    private var permissionsGranted = false
    private var pairingScanDone = false
    private var scanJob: Job? = null

    /** The heart rate in use (strap connected to the app, else the console), as the UI shows it. */
    val heartRate: Flow<LiveHeartRate> =
        combine(consoleChanges, monitor.connection, monitor.latest) { _, _, _ -> source.latest().let { LiveHeartRate(it.bpm, it.source) } }
            .distinctUntilChanged()

    private val profileAndLink = combine(profiles.activeUser, monitor.connection, monitor.problem, monitor.batteryPercent) { user, connection, problem, battery ->
        StrapUiState(
            address = user?.strapAddress,
            name = user?.strapName,
            connection = connection,
            problem = problem,
            batteryPercent = battery,
        )
    }

    val state: Flow<StrapUiState> = combine(profileAndLink, heartRate, local) { base, live, local ->
        base.copy(
            heartRate = live,
            scanning = local.scanning,
            scanFinished = local.scanFinished,
            devices = local.devices,
            scanProblem = local.scanProblem,
            pairingOffer = local.offer,
            saveFailed = local.saveFailed,
        )
    }

    /** Starts following the active user. Call it as soon as the app opens (before the treadmill is touched). */
    fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            profiles.activeUser.collect { user ->
                val previous = activeUser
                activeUser = user
                if (!released) monitor.use(user?.strapAddress)
                if (previous?.id != user?.id) local.update { it.copy(offer = null, saveFailed = false) }
                started.complete(Unit)
                maybeOfferPairing()
            }
        }
    }

    /** Suspends until the strap of the active user has been handed to the monitor (the treadmill waits for it). */
    suspend fun awaitStarted() = started.await()

    /** The Bluetooth permissions are granted: the one-time pairing scan may run. */
    fun onPermissionsGranted() {
        permissionsGranted = true
        maybeOfferPairing()
    }

    /** The app is visible again: after an exit that released the strap, the active user's strap is wanted once more. */
    fun onForeground() {
        if (!released) return
        released = false
        monitor.use(activeUser?.strapAddress)
        maybeOfferPairing()
    }

    /** The app exits (the one place the strap is let go of). */
    fun release() {
        released = true
        scanJob?.cancel()
        scope.launch { monitor.release() }
    }

    // ------------------------------------------------------------------ pairing question

    private fun maybeOfferPairing() {
        if (pairingScanDone || !permissionsGranted || released) return
        val user = activeUser ?: return
        pairingScanDone = true
        if (user.strapAddress != null || settings.strapQuestionAnswered(user.id)) return
        scope.launch { scanForOffer(user.id) }
    }

    private suspend fun scanForOffer(userId: Long) {
        val found = mutableMapOf<String, DiscoveredStrap>()
        try {
            withTimeoutOrNull(pairingScanMillis) { monitor.scan().collect { found[it.address] = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bluetooth off, no permission…: nothing to ask now; a later start tries again.
            pairingScanDone = false
            return
        }
        val strongest = found.values.maxByOrNull { it.rssi } ?: return
        val user = activeUser
        if (user?.id == userId && user.strapAddress == null && !settings.strapQuestionAnswered(userId)) {
            local.update { it.copy(offer = strongest) }
        }
    }

    /** "Yes": the strap becomes the active user's. */
    fun acceptPairingOffer() {
        val offer = local.value.offer ?: return
        local.update { it.copy(offer = null) }
        pair(offer)
    }

    /** "No": remembered for this user, never asked again (pairing stays possible from Devices). */
    fun declinePairingOffer() {
        val user = activeUser ?: return
        settings.setStrapQuestionAnswered(user.id)
        local.update { it.copy(offer = null) }
    }

    // ------------------------------------------------------------------ Devices

    /** Looks for straps nearby (a strap that is connected to the console or to the app does not show up). */
    fun scan() {
        if (scanJob?.isActive == true) return
        local.update { it.copy(scanning = true, scanFinished = false, devices = emptyList(), scanProblem = null) }
        scanJob = scope.launch {
            try {
                withTimeoutOrNull(scanMillis) {
                    monitor.scan().collect { found ->
                        local.update { state ->
                            state.copy(devices = (state.devices.filterNot { it.address == found.address } + found).sortedByDescending { it.rssi })
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(scanProblem = (e as? HrmTransportException)?.problem ?: StrapProblem.BluetoothOff) }
            } finally {
                local.update { it.copy(scanning = false, scanFinished = true) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    /** Pairs the active user with [strap] (a scan result), replacing the previous one. */
    fun pair(strap: DiscoveredStrap) {
        val user = activeUser ?: return
        stopScan()
        scope.launch {
            val result = profiles.setStrap(user.id, strap.address, strap.name)
            local.update { it.copy(saveFailed = result !is StrapSaveResult.Saved, devices = emptyList(), scanFinished = false) }
            if (result is StrapSaveResult.Saved) settings.setStrapQuestionAnswered(user.id)
        }
    }

    /** Forgets the active user's strap: the monitor lets go of it and stops looking. */
    fun forget() {
        val user = activeUser ?: return
        scope.launch {
            val result = profiles.clearStrap(user.id)
            local.update { it.copy(saveFailed = result !is DataResult.Success) }
        }
    }

    companion object {
        /** How long the start-up scan for the pairing question lasts. */
        const val PAIRING_SCAN_MILLIS = 6_000L

        /** How long a scan started from Devices lasts. */
        const val SCAN_MILLIS = 20_000L
    }
}

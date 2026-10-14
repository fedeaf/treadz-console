package com.treadz.console.fakes

import com.treadz.console.hrm.BodySensorLocation
import com.treadz.console.hrm.DiscoveredStrap
import com.treadz.console.hrm.HeartRateMonitor
import com.treadz.console.hrm.HrmObserver
import com.treadz.console.hrm.HrmTransportException
import com.treadz.console.hrm.SensorContact
import com.treadz.console.hrm.StrapConnectionState
import com.treadz.console.hrm.StrapProblem
import com.treadz.console.hrm.StrapReading
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Fake of the heart-rate strap connected to the app: every flow is mutable, `use()` is recorded (and, like the real
 * monitor, ignores the address already in use), and there are shortcuts for the states worth seeing.
 */
class FakeHeartRateMonitor : HeartRateMonitor {
    override val connection = MutableStateFlow<StrapConnectionState>(StrapConnectionState.Inactive)
    override val problem = MutableStateFlow<StrapProblem?>(null)
    override val latest = MutableStateFlow<StrapReading?>(null)
    override val readings = MutableSharedFlow<StrapReading>(extraBufferCapacity = 64)
    override val batteryPercent = MutableStateFlow<Int?>(null)
    override val sensorLocation = MutableStateFlow<BodySensorLocation?>(null)
    override val address = MutableStateFlow<String?>(null)

    /** The addresses given to `use()` that were not already in use, in order (`null` = let go). */
    val useCalls = mutableListOf<String?>()
    var releaseCalls = 0

    /** Straps the scan "finds". */
    var scanResults: List<DiscoveredStrap> = emptyList()
    var scanNeverEnds = true
    var scanError: Exception? = null
    var scanCalls = 0

    override fun use(address: String?) {
        val wanted = address?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (wanted == this.address.value) return
        useCalls += wanted
        this.address.value = wanted
        connection.value = if (wanted == null) StrapConnectionState.Inactive else StrapConnectionState.Searching(wanted)
        if (wanted == null) {
            latest.value = null
            batteryPercent.value = null
        }
    }

    override suspend fun release() {
        releaseCalls++
        use(null)
    }

    override fun scan(): Flow<DiscoveredStrap> = flow {
        scanCalls++
        scanError?.let { throw it }
        scanResults.forEach { emit(it) }
        if (scanNeverEnds) awaitCancellation()
    }

    override fun addObserver(observer: HrmObserver) {}
    override fun removeObserver(observer: HrmObserver) {}

    // ---- Scenario shortcuts ----

    fun showConnected(address: String = STRAP_ADDRESS, name: String? = STRAP_NAME, bpm: Int? = 72, battery: Int? = 87) {
        this.address.value = address
        connection.value = StrapConnectionState.Connected(address, name)
        batteryPercent.value = battery
        if (bpm != null) showReading(bpm)
    }

    fun showSearching(address: String = STRAP_ADDRESS) {
        this.address.value = address
        connection.value = StrapConnectionState.Searching(address)
        latest.value = null
    }

    fun showReading(bpm: Int, contact: SensorContact = SensorContact.Detected, atMillis: Long = System.currentTimeMillis()) {
        val reading = StrapReading(bpm, contact, emptyList(), null, atMillis)
        latest.value = reading
        readings.tryEmit(reading)
    }

    fun scanFails(problem: StrapProblem) {
        scanError = HrmTransportException("scan failed", problem = problem)
    }

    companion object {
        const val STRAP_ADDRESS = "C1:A2:B3:00:11:22"
        const val STRAP_NAME = "HRM-H9Z 4821"

        val SAMPLE_STRAPS = listOf(
            DiscoveredStrap(STRAP_ADDRESS, STRAP_NAME, rssi = -52),
            DiscoveredStrap("D4:11:22:33:44:55", "Polar H10 8A2C41", rssi = -74),
        )
    }
}

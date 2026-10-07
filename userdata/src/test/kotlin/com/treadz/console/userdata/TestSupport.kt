package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillCapabilities
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

/** 2026-10-07 12:00 UTC. */
const val T0 = 1_791_374_400_000L

val TODAY: LocalDate = LocalDate.of(2026, 10, 7)

class MutableClock(var nowMillis: Long = T0) : Clock {
    override fun nowMillis(): Long = nowMillis
}

private val openDatabases = mutableListOf<UserDataDatabase>()

/** Closes the in-memory databases opened by the test (call from `@After`). */
internal fun closeRoomDatabases() {
    openDatabases.forEach { runCatching { it.close() } }
    openDatabases.clear()
}

internal fun newRoomDatabase(queryContext: CoroutineContext? = null): UserDataDatabase {
    val context = org.robolectric.RuntimeEnvironment.getApplication() as Context
    return Room.inMemoryDatabaseBuilder(context, UserDataDatabase::class.java)
        .allowMainThreadQueries()
        .apply { if (queryContext != null) setQueryCoroutineContext(queryContext) }
        .build()
        .also { openDatabases += it }
}

/** [seed] = create the generic profile on the empty database (the app does; most tests want a clean slate). */
fun newRoomUserData(clock: Clock, queryContext: CoroutineContext? = null, seed: Boolean = false): UserData =
    RoomUserData(newRoomDatabase(queryContext), clock, ZoneOffset.UTC, seedGenericProfile = seed)

fun validInput(name: String = "Ana") = ProfileInput(
    name = name,
    sex = Sex.Female,
    heightCm = 168,
    birthDate = LocalDate.of(1986, 10, 8), // turns 40 tomorrow: 39 today
)

/** Treadmill whose flows are driven by the test. Any command is recorded: the observer must never send one. */
class FakeTreadmill : TreadmillController {
    override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connected("AA:BB", "FAKE"))
    override val state = MutableStateFlow(TreadmillState())
    override val capabilities = MutableStateFlow<TreadmillCapabilities?>(null)
    override val events = MutableSharedFlow<TreadmillEvent>(extraBufferCapacity = 64)
    override val pendingAlerts = MutableStateFlow<List<TreadmillAlert>>(emptyList())
    override val limits = MutableStateFlow(SafetyLimits())

    val commands = mutableListOf<String>()

    override fun acknowledgeAlert(alert: TreadmillAlert) { commands += "acknowledgeAlert" }
    override fun setLimits(limits: SafetyLimits) { commands += "setLimits" }
    override fun scan(): Flow<DiscoveredTreadmill> { commands += "scan"; return emptyFlow() }
    override suspend fun connect(address: String): CommandResult { commands += "connect"; return CommandResult.Success }
    override suspend fun disconnect() { commands += "disconnect" }
    override suspend fun start(): CommandResult { commands += "start"; return CommandResult.Success }
    override suspend fun pause(): CommandResult { commands += "pause"; return CommandResult.Success }
    override suspend fun resume(): CommandResult { commands += "resume"; return CommandResult.Success }
    override suspend fun stop(): CommandResult { commands += "stop"; return CommandResult.Success }
    override suspend fun setSpeed(kmh: Double): CommandResult { commands += "setSpeed"; return CommandResult.Success }
    override suspend fun setIncline(percent: Double): CommandResult { commands += "setIncline"; return CommandResult.Success }
}

fun state(
    status: BeltStatus = BeltStatus.Running,
    speed: Double = 8.0,
    incline: Double = 0.0,
    distance: Int = 0,
    elapsed: Int = 0,
    calories: Int = 0,
    hr: Int? = null,
    stale: Boolean = false,
) = TreadmillState(
    status = status,
    speedKmh = speed,
    targetSpeedKmh = speed,
    inclinePercent = incline,
    distanceMeters = distance,
    elapsedSeconds = elapsed,
    calories = calories,
    heartRateBpm = hr,
    isStale = stale,
)

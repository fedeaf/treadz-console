package com.treadz.console.sync

import android.content.Context
import com.treadz.console.sync.engine.InMemorySyncStateStore
import com.treadz.console.sync.engine.SyncEngine
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.github.RestGitHubClient
import com.treadz.console.sync.service.DefaultSyncService
import com.treadz.console.sync.token.InMemoryTokenStore
import com.treadz.console.sync.work.SyncScheduler
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.workout.WorkoutSessionStore
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.SyncableUserData
import com.treadz.console.userdata.TreadZUserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.sync.InMemoryProgramDocuments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** 2026-10-08 00:40 UTC. */
const val T0 = 1_791_420_000_000L

const val TOKEN = "ghp_SECRET_TOKEN_VALUE_1234567890"

class MutableClock(var nowMillis: Long = T0) : Clock {
    override fun nowMillis(): Long = nowMillis
}

class RecordingScheduler : SyncScheduler {
    var pushes = 0
    var cancels = 0
    override fun schedulePush() { pushes++ }
    override fun cancel() { cancels++ }
}

/**
 * One simulated device: its own database (the real Room data layer, in memory), program store, sync state, token and
 * clock, talking to the shared [github].
 */
class Device(
    val name: String,
    val github: FakeGitHub,
    seed: Long,
    val clock: MutableClock = MutableClock(),
    val repo: RepositoryRef = RepositoryRef("fede", "treadz-data", "main"),
    seedGenericProfile: Boolean = true,
    maxAttempts: Int = 5,
) {
    private var uuidCounter = 0L
    val data: SyncableUserData = TreadZUserData.createInMemory(
        RuntimeEnvironment.getApplication() as Context,
        clock = clock,
        deviceId = name,
        zone = ZoneOffset.UTC,
        seedGenericProfile = seedGenericProfile,
        uuidSource = { UUID(seed, ++uuidCounter).toString() },
    )
    val programs = InMemoryProgramDocuments()
    val state = InMemorySyncStateStore()
    val tokens = InMemoryTokenStore(TOKEN)
    val scheduler = RecordingScheduler()

    /** Runs before every request of this device reaches GitHub: lets a test make another device act in between. */
    var beforeRequest: ((okhttp3.Request) -> Unit)? = null
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> beforeRequest?.invoke(chain.request()); chain.proceed(chain.request()) }
        .addInterceptor(github)
        .build()
    val client = RestGitHubClient(http, tokens::get, nowMillis = { clock.nowMillis })
    val engine = SyncEngine(client, data.sync, programs, state, clock, ZoneOffset.UTC, maxAttempts = maxAttempts, pauseBeforeRetry = {})
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** Created on first use: it restores its status from the token and state as they are at that moment. */
    val service: DefaultSyncService by lazy { DefaultSyncService(client, engine, tokens, state, data.sync, programs, scheduler, scope, clock) }

    /** Moves this device's clock and returns it. */
    fun at(millis: Long): Device { clock.nowMillis = millis; return this }

    fun sync(): SyncReport = runBlocking { engine.sync(repo) }

    fun createUser(name: String, sex: Sex = Sex.Female): UserProfile = runBlocking {
        (data.profiles.create(ProfileInput(name, sex, 170, LocalDate.of(1986, 10, 8))) as DataResult.Success).value
    }

    fun addWeight(user: UserProfile, date: LocalDate, kg: Double) = runBlocking { data.weights.add(user.id, date, kg) }

    /** Records and finishes a workout of [seconds] seconds for [user] (null: nobody) starting at [startedAt]. */
    fun finishWorkout(user: UserProfile?, startedAt: Long, seconds: Int = 3, meters: Int = 100): Long = runBlocking {
        val store = data.workouts as WorkoutSessionStore // the Room implementation records through it
        val id = store.begin(user?.id, startedAt)
        val samples = (0 until seconds).map {
            WorkoutSample(it, startedAt + it * 1000L, it + 1, 8.0, 8.0, 1.0, meters * (it + 1) / seconds, it / 2, 120 + it, BeltStatus.Running)
        }
        store.finish(
            id, samples,
            WorkoutStats(durationSeconds = seconds, distanceMeters = meters, calories = seconds / 2, averageSpeedKmh = 8.0, maxSpeedKmh = 8.0, averageHeartRateBpm = 121, maxHeartRateBpm = 120 + seconds, maxInclinePercent = 1.0),
            startedAt + seconds * 1000L, WorkoutEndReason.Stopped,
        )
        id
    }

    fun profiles(): List<UserProfile> = runBlocking { data.profiles.profiles.first() }
}

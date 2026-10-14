package com.treadz.console.userdata

import com.treadz.console.hrm.resting.RestingHeartRateResult
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.RemoteChanges
import com.treadz.console.userdata.sync.SyncRestingHeartRate
import com.treadz.console.userdata.sync.SyncedRecords
import com.treadz.console.userdata.sync.SyncedRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

private const val DAY = 86_400_000L

/** The pure rules of the resting heart rate history: reference, provisional and stale values, elevated readings. */
class RestingHeartRatesTest {

    private val now = T0

    private fun at(daysAgo: Double, bpm: Int) = RestingPoint(now - (daysAgo * DAY).toLong(), bpm)

    private fun measurement(id: Long, daysAgo: Double, bpm: Int) = RestingHeartRateMeasurement(id, 1, now - (daysAgo * DAY).toLong(), bpm, RestingHeartRateSource.Strap)

    @Test fun `without measurements there is no reference`() {
        assertNull(RestingHeartRates.reference(emptyList(), now))
        assertNull(RestingHeartRates.snapshotBpm(emptyList()))
    }

    @Test fun `the reference is the median of the last 30 days, established from three measurements`() {
        val r = RestingHeartRates.reference(listOf(at(1.0, 60), at(3.0, 58), at(5.0, 70)), now)!!
        assertEquals(60, r.bpm)
        assertEquals(ReferenceStatus.Established, r.status)
        assertEquals(3, r.measurementsCount)
        assertEquals(now - DAY, r.latestAtMillis)
        // the median ignores an outlier, the mean would not
        assertEquals(58, RestingHeartRates.reference(listOf(at(1.0, 57), at(2.0, 58), at(3.0, 58), at(4.0, 59), at(5.0, 90)), now)!!.bpm)
    }

    @Test fun `an even number gives the mean of the two middle values, rounded half up`() {
        val r = RestingHeartRates.reference(listOf(at(1.0, 60), at(2.0, 61), at(3.0, 70), at(4.0, 50)), now)!!
        assertEquals(61, r.bpm) // (60 + 61) / 2 = 60.5
        assertEquals(ReferenceStatus.Established, r.status)
        assertEquals(60, RestingHeartRates.reference(listOf(at(1.0, 60), at(2.0, 60)), now)!!.bpm)
    }

    @Test fun `one or two measurements in 30 days are provisional`() {
        val one = RestingHeartRates.reference(listOf(at(2.0, 57)), now)!!
        assertEquals(57, one.bpm)
        assertEquals(ReferenceStatus.Provisional, one.status)
        assertEquals(1, one.measurementsCount)
        val two = RestingHeartRates.reference(listOf(at(2.0, 57), at(9.0, 59)), now)!!
        assertEquals(58, two.bpm)
        assertEquals(ReferenceStatus.Provisional, two.status)
    }

    @Test fun `with none in 30 days the latest measurement is the reference, marked stale`() {
        val r = RestingHeartRates.reference(listOf(at(100.0, 70), at(45.0, 62), at(60.0, 64)), now)!!
        assertEquals(62, r.bpm)
        assertEquals(ReferenceStatus.Stale, r.status)
        assertEquals(1, r.measurementsCount)
        assertEquals(now - 45 * DAY, r.latestAtMillis)
    }

    @Test fun `the window is exactly 30 days`() {
        val inside = RestingPoint(now - 30 * DAY, 60)
        val outside = RestingPoint(now - 30 * DAY - 1, 40)
        val r = RestingHeartRates.reference(listOf(inside, outside), now)!!
        assertEquals(60, r.bpm)
        assertEquals(ReferenceStatus.Provisional, r.status)
        assertEquals(ReferenceStatus.Stale, RestingHeartRates.reference(listOf(outside), now)!!.status)
    }

    @Test fun `a measurement later than now is not used unless there is nothing else`() {
        assertEquals(60, RestingHeartRates.reference(listOf(at(1.0, 60), RestingPoint(now + DAY, 90)), now)!!.bpm)
        assertEquals(90, RestingHeartRates.reference(listOf(RestingPoint(now + DAY, 90)), now)!!.bpm)
    }

    @Test fun `the snapshot of the profile file does not depend on the clock`() {
        val points = listOf(at(40.0, 80), at(3.0, 60), at(2.0, 62), at(1.0, 61))
        // as of the newest measurement: the three of the last 30 days
        assertEquals(61, RestingHeartRates.snapshotBpm(points))
        assertEquals(61, RestingHeartRates.snapshotBpm(points.reversed()))
    }

    @Test fun `a measurement 7 bpm above the reference computed without it is elevated`() {
        val history = listOf(measurement(1, 6.0, 55), measurement(2, 5.0, 56), measurement(3, 3.0, 54), measurement(4, 2.0, 55))
        val seven = measurement(5, 0.0, 62)
        val a = RestingHeartRates.assess(seven, history + seven)
        assertEquals(55, a.referenceBpm) // the median of the four before, without the new one
        assertEquals(ReferenceStatus.Established, a.referenceStatus)
        assertEquals(7, a.deltaBpm)
        assertTrue(a.isElevated)
        assertFalse(RestingHeartRates.assess(measurement(6, 0.0, 61), history).isElevated)
        assertTrue(RestingHeartRates.isElevated(70, now, history))
        assertFalse(RestingHeartRates.isElevated(50, now, history))
    }

    @Test fun `the reference of the comparison leaves out the measurement itself and later ones`() {
        val history = listOf(measurement(1, 4.0, 55), measurement(2, 3.0, 55), measurement(3, 2.0, 55))
        val subject = measurement(4, 1.0, 66)
        val later = measurement(5, 0.5, 99)
        val a = RestingHeartRates.assess(subject, history + subject + later)
        assertEquals(55, a.referenceBpm)
        assertTrue(a.isElevated)
        // assessing an old one does not see what came after it
        assertEquals(null, RestingHeartRates.assess(history[0], history + subject + later).referenceBpm)
    }

    @Test fun `a provisional reference is compared, a stale one or none is not`() {
        val two = listOf(measurement(1, 3.0, 55), measurement(2, 2.0, 55))
        val provisional = RestingHeartRates.assess(measurement(3, 0.0, 63), two)
        assertEquals(ReferenceStatus.Provisional, provisional.referenceStatus)
        assertTrue(provisional.isElevated)

        val old = listOf(measurement(1, 90.0, 50), measurement(2, 80.0, 50))
        val stale = RestingHeartRates.assess(measurement(3, 0.0, 70), old)
        assertNull(stale.referenceBpm)
        assertNull(stale.deltaBpm)
        assertFalse(stale.isElevated)
        assertFalse(RestingHeartRates.assess(measurement(1, 0.0, 70), emptyList()).isElevated)
    }

    @Test fun `only a valid measurement session result becomes an input`() {
        val valid = RestingHeartRateResult(T0 - 180_000, T0, true, 54, 53.6, emptyList(), 0.97, 2, 1, 170)
        val input = RestingHeartRateInput.fromResult(valid, RestingHeartRateSource.Strap, rmssdMs = 41.5)!!
        assertEquals(RestingHeartRateInput(T0, 54, RestingHeartRateSource.Strap, 53.6, 0.97, 41.5), input)
        assertNull(RestingHeartRateInput.fromResult(valid.copy(valid = false), RestingHeartRateSource.Strap))
        assertNull(RestingHeartRateInput.fromResult(valid.copy(bpm = null), RestingHeartRateSource.Strap))
    }

    @Test fun `the measurement that stands for an old profile value has a uuid that depends on the user only`() {
        assertEquals("resting-abc", RestingHeartRates.legacyMeasurementUuid("abc"))
    }
}

/** The history through the repository (Room and in-memory), and the profile's resting heart rate derived from it. */
@RunWith(RobolectricTestRunner::class)
abstract class RestingHeartRateContractTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    protected val data by lazy { create() }

    protected fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    private fun input(daysAgo: Int, bpm: Int, source: RestingHeartRateSource = RestingHeartRateSource.Strap) =
        RestingHeartRateInput(clock.nowMillis - daysAgo * DAY, bpm, source)

    private suspend fun ana() = data.profiles.create(validInput("Ana")).ok()

    @Test fun `measurements are listed oldest first and edited and deleted`() = runBlocking<Unit> {
        val u = ana()
        val late = data.restingHeartRates.add(u.id, input(1, 60)).ok()
        val early = data.restingHeartRates.add(u.id, input(5, 58, RestingHeartRateSource.Manual)).ok()
        val withDetails = data.restingHeartRates.add(
            u.id, RestingHeartRateInput(clock.nowMillis - 3 * DAY, 57, RestingHeartRateSource.Console, 56.6, 0.95, 38.2),
        ).ok()
        assertEquals(listOf(early.id, withDetails.id, late.id), data.restingHeartRates.measurements(u.id).first().map { it.id })
        assertEquals(RestingHeartRateSource.Manual, early.source)
        assertEquals(56.6, withDetails.lowestMeanBpm!!, 0.0)
        assertEquals(0.95, withDetails.coverage!!, 0.0)
        assertEquals(38.2, withDetails.rmssdMs!!, 0.0)
        assertNull(late.rmssdMs)

        val edited = data.restingHeartRates.update(late.id, input(1, 64)).ok()
        assertEquals(late.id, edited.id)
        assertEquals(64, edited.bpm)
        assertEquals(listOf(58, 57, 64), data.restingHeartRates.measurements(u.id).first().map { it.bpm })

        data.restingHeartRates.delete(early.id).ok()
        assertEquals(listOf(57, 64), data.restingHeartRates.measurements(u.id).first().map { it.bpm })
        assertEquals(DataResult.NotFound, data.restingHeartRates.delete(early.id))
        assertEquals(DataResult.NotFound, data.restingHeartRates.update(early.id, input(1, 60)))
        assertEquals(DataResult.NotFound, data.restingHeartRates.add(999, input(1, 60)))
    }

    @Test fun `only valid measurements are stored`() = runBlocking<Unit> {
        val u = ana()
        suspend fun issues(i: RestingHeartRateInput) = (data.restingHeartRates.add(u.id, i) as DataResult.Invalid).issues
        val oor = listOf(ValidationIssue(InputField.RestingHeartRate, ValidationCode.OutOfRange))
        assertEquals(oor, issues(input(0, 10)))
        assertEquals(oor, issues(input(0, 121)))
        assertEquals(listOf(ValidationIssue(InputField.RestingHeartRate, ValidationCode.InFuture)), issues(input(0, 60).copy(measuredAtMillis = clock.nowMillis + 3_600_000)))
        assertEquals(oor, issues(input(0, 60).copy(coverage = 1.5)))
        assertEquals(oor, issues(input(0, 60).copy(rmssdMs = -3.0)))
        assertEquals(oor, issues(input(0, 60).copy(lowestMeanBpm = Double.NaN)))
        assertTrue(data.restingHeartRates.measurements(u.id).first().isEmpty())
        // a minute from now is still a "now" (clocks differ a little)
        data.restingHeartRates.add(u.id, input(0, 60).copy(measuredAtMillis = clock.nowMillis + 60_000)).ok()
        assertTrue(data.restingHeartRates.update(data.restingHeartRates.measurements(u.id).first().single().id, input(0, 200)) is DataResult.Invalid)
    }

    @Test fun `the reference follows the measurements and the profile shows it`() = runBlocking<Unit> {
        val u = ana()
        assertNull(data.restingHeartRates.reference(u.id).first())
        assertNull(data.profiles.profile(u.id).first()!!.restingHeartRateBpm)

        data.restingHeartRates.add(u.id, input(2, 60)).ok()
        val provisional = data.restingHeartRates.reference(u.id).first()!!
        assertEquals(60, provisional.bpm)
        assertEquals(ReferenceStatus.Provisional, provisional.status)
        data.restingHeartRates.add(u.id, input(1, 70)).ok()
        data.restingHeartRates.add(u.id, input(0, 62)).ok()
        val established = data.restingHeartRates.reference(u.id).first()!!
        assertEquals(62, established.bpm)
        assertEquals(ReferenceStatus.Established, established.status)
        assertEquals(62, data.profiles.profile(u.id).first()!!.restingHeartRateBpm)
        assertEquals(62, data.profiles.activeUser.first()!!.restingHeartRateBpm)
        assertEquals(62, data.profiles.profiles.first().single().restingHeartRateBpm)

        // a month later nothing is recent: the latest measurement, stale
        clock.nowMillis += 40 * DAY
        data.restingHeartRates.add(u.id, input(41, 63).copy(measuredAtMillis = T0 + 2 * DAY)).ok() // forces a new emission
        assertEquals(ReferenceStatus.Stale, data.restingHeartRates.reference(u.id).first()!!.status)
        assertEquals(63, data.profiles.profile(u.id).first()!!.restingHeartRateBpm)
    }

    @Test fun `saving a profile with a changed resting heart rate adds a manual measurement`() = runBlocking<Unit> {
        val u = data.profiles.create(validInput("Ana").copy(restingHeartRateBpm = 58)).ok()
        assertEquals(58, u.restingHeartRateBpm)
        val first = data.restingHeartRates.measurements(u.id).first().single()
        assertEquals(RestingHeartRateSource.Manual, first.source)
        assertEquals(T0, first.measuredAtMillis)

        // the form pre-filled with the reference and saved again changes nothing in the history
        data.profiles.update(u.id, validInput("Ana").copy(restingHeartRateBpm = 58, heightCm = 170)).ok()
        assertEquals(1, data.restingHeartRates.measurements(u.id).first().size)
        // clearing the field does not erase the history
        val cleared = data.profiles.update(u.id, validInput("Ana").copy(restingHeartRateBpm = null)).ok()
        assertEquals(1, data.restingHeartRates.measurements(u.id).first().size)
        assertEquals(58, cleared.restingHeartRateBpm)

        clock.nowMillis += 1_000
        val changed = data.profiles.update(u.id, validInput("Ana").copy(restingHeartRateBpm = 50)).ok()
        val all = data.restingHeartRates.measurements(u.id).first()
        assertEquals(listOf(58, 50), all.map { it.bpm })
        assertEquals(RestingHeartRateSource.Manual, all.last().source)
        assertEquals(T0 + 1_000, all.last().measuredAtMillis)
        assertEquals(54, changed.restingHeartRateBpm) // the median of 58 and 50
        // a value out of range is still rejected by the profile
        val bad = data.profiles.update(u.id, validInput("Ana").copy(restingHeartRateBpm = 10))
        assertEquals(listOf(ValidationIssue(InputField.RestingHeartRate, ValidationCode.OutOfRange)), (bad as DataResult.Invalid).issues)
        assertEquals(2, data.restingHeartRates.measurements(u.id).first().size)
    }

    @Test fun `the elevated warning is answered for a stored measurement`() = runBlocking<Unit> {
        val u = ana()
        for (d in 6 downTo 3) data.restingHeartRates.add(u.id, input(d, 55)).ok()
        val high = data.restingHeartRates.add(u.id, input(0, 63)).ok()
        val a = data.restingHeartRates.assessment(high.id)!!
        assertTrue(a.isElevated)
        assertEquals(55, a.referenceBpm)
        assertEquals(8, a.deltaBpm)
        assertNull(data.restingHeartRates.assessment(12345))
    }

    @Test fun `deleting the profile deletes its measurements`() = runBlocking<Unit> {
        val a = ana()
        val b = data.profiles.create(validInput("Beto")).ok()
        data.restingHeartRates.add(a.id, input(1, 60)).ok()
        data.restingHeartRates.add(b.id, input(1, 70)).ok()
        data.profiles.delete(a.id).ok()
        assertTrue(data.restingHeartRates.measurements(a.id).first().isEmpty())
        assertNull(data.restingHeartRates.reference(a.id).first())
        assertEquals(listOf(70), data.restingHeartRates.measurements(b.id).first().map { it.bpm })
    }
}

class RoomRestingHeartRateTest : RestingHeartRateContractTest() {
    override fun create(): UserData = newRoomUserData(clock)

    private fun roomData() = data as com.treadz.console.userdata.room.RoomUserData

    private suspend fun anaUuid() = roomData().sync.pendingChanges().profiles.single().uuid

    @Test fun `measurements are pending with the weights rules, deletions are tombstones`() = runBlocking<Unit> {
        val u = data.profiles.create(validInput("Ana")).ok()
        val sync = roomData().sync
        val m = data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - DAY, 58, RestingHeartRateSource.Strap, 57.5, 0.96, 44.0)).ok()
        var pending = sync.pendingChanges()
        val record = pending.restingHeartRates.single()
        assertEquals(anaUuid(), record.userUuid)
        assertEquals(58, record.bpm)
        assertEquals(RestingHeartRateSource.Strap, record.source)
        assertEquals(44.0, record.rmssdMs!!, 0.0)
        assertEquals(2, sync.pendingCount.first()) // the profile and the measurement
        assertEquals(2, pending.count)

        sync.markSynced(SyncedRecords(restingHeartRates = listOf(SyncedRef(record.uuid, record.version))))
        assertTrue(sync.pendingChanges().restingHeartRates.isEmpty())
        assertEquals(1, sync.pendingCount.first())

        clock.nowMillis += 1_000
        data.restingHeartRates.update(m.id, RestingHeartRateInput(T0 - DAY, 59, RestingHeartRateSource.Strap)).ok()
        pending = sync.pendingChanges()
        assertEquals(59, pending.restingHeartRates.single().bpm)
        assertTrue(pending.restingHeartRates.single().version > record.version)
        sync.markSynced(SyncedRecords(restingHeartRates = pending.restingHeartRates.map { SyncedRef(it.uuid, it.version) }))

        clock.nowMillis += 1_000
        data.restingHeartRates.delete(m.id).ok()
        val tombstone = sync.pendingChanges().restingHeartRates.single()
        assertTrue(tombstone.deleted)
        assertEquals(record.uuid, tombstone.uuid)
        assertEquals(listOf(tombstone), sync.restingHeartRates(anaUuid()))
        assertTrue(data.restingHeartRates.measurements(u.id).first().isEmpty())
    }

    @Test fun `the profile file shows the reference of the measurements, as of the newest`() = runBlocking<Unit> {
        val u = data.profiles.create(validInput("Ana")).ok()
        assertNull(roomData().sync.pendingChanges().profiles.single().restingHeartRateBpm)
        for ((d, bpm) in listOf(6 to 55, 4 to 57, 2 to 59)) {
            data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - d * DAY, bpm, RestingHeartRateSource.Strap)).ok()
        }
        assertEquals(57, roomData().sync.pendingChanges().profiles.single().restingHeartRateBpm)
        clock.nowMillis += 90 * DAY // time passes: the file does not change
        assertEquals(57, roomData().sync.profile(anaUuid())!!.restingHeartRateBpm)
    }

    @Test fun `a remote measurement is applied with last writer wins and is not pending`() = runBlocking<Unit> {
        val u = data.profiles.create(validInput("Ana")).ok()
        val sync = roomData().sync
        val uuid = anaUuid()
        fun remote(id: String, at: Long, bpm: Int, device: String = "dev-b", deleted: Boolean = false, user: String = uuid) =
            SyncRestingHeartRate(id, user, RecordVersion(at, device), deleted, T0 - DAY, bpm, RestingHeartRateSource.Console)

        val applied = sync.applyRemote(RemoteChanges(restingHeartRates = listOf(remote("r1", T0 + 10, 61), remote("r2", T0 + 10, 99, user = "unknown"))))
        assertEquals(1, applied.applied)
        assertEquals(1, applied.skipped)
        assertEquals(listOf(61), data.restingHeartRates.measurements(u.id).first().map { it.bpm })
        assertTrue(sync.pendingChanges().restingHeartRates.isEmpty())

        // newer replaces, older is ignored, a tombstone deletes, a tombstone of an unknown record is skipped
        sync.applyRemote(RemoteChanges(restingHeartRates = listOf(remote("r1", T0 + 20, 62))))
        assertEquals(listOf(62), data.restingHeartRates.measurements(u.id).first().map { it.bpm })
        sync.applyRemote(RemoteChanges(restingHeartRates = listOf(remote("r1", T0 + 5, 40))))
        assertEquals(listOf(62), data.restingHeartRates.measurements(u.id).first().map { it.bpm })
        val skipped = sync.applyRemote(RemoteChanges(restingHeartRates = listOf(remote("r9", T0 + 30, 0, deleted = true))))
        assertEquals(1, skipped.skipped)
        sync.applyRemote(RemoteChanges(restingHeartRates = listOf(remote("r1", T0 + 40, 0, deleted = true))))
        assertTrue(data.restingHeartRates.measurements(u.id).first().isEmpty())
        assertTrue(sync.pendingChanges().restingHeartRates.isEmpty())
        // the tombstone is kept and is what is written to the file
        assertTrue(sync.restingHeartRates(uuid).single().deleted)
    }

    @Test fun `a local edit that is newer than the remote one is kept and stays pending`() = runBlocking<Unit> {
        val u = data.profiles.create(validInput("Ana")).ok()
        val sync = roomData().sync
        val m = data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - DAY, 58, RestingHeartRateSource.Strap)).ok()
        val local = sync.pendingChanges().restingHeartRates.single()
        val remote = local.copy(version = RecordVersion(local.version.updatedAtMillis - 1, "dev-b"), bpm = 70)
        val result = sync.applyRemote(RemoteChanges(restingHeartRates = listOf(remote)))
        assertEquals(1, result.keptLocal)
        assertEquals(58, data.restingHeartRates.measurements(u.id).first().single().bpm)
        assertEquals(1, sync.pendingChanges().restingHeartRates.size)
        assertNotNull(m)
    }

    @Test fun `deleting the profile deletes its measurements without uploading them one by one`() = runBlocking<Unit> {
        val u = data.profiles.create(validInput("Ana")).ok()
        data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - DAY, 58, RestingHeartRateSource.Strap)).ok()
        val sync = roomData().sync
        sync.markSynced(SyncedRecords(restingHeartRates = sync.pendingChanges().restingHeartRates.map { SyncedRef(it.uuid, it.version) }))
        data.profiles.delete(u.id).ok()
        assertTrue(sync.pendingChanges().restingHeartRates.isEmpty())
        assertEquals(1, sync.pendingChanges().profiles.count { it.deleted })
    }
}

@RunWith(JUnit4::class)
class InMemoryRestingHeartRateTest : RestingHeartRateContractTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}

package app.circlet.data

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.Confidence
import app.circlet.core.LiveWorkout
import app.circlet.core.WorkoutPause
import app.circlet.core.GravityVector
import app.circlet.core.HealthKind
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.ReferenceRange
import app.circlet.core.Sample
import app.circlet.core.SleepCycle
import app.circlet.core.SleepEpoch
import app.circlet.core.SleepKind
import app.circlet.core.SleepSession
import app.circlet.core.SleepStage
import app.circlet.core.SkinTempFlag
import app.circlet.core.SkinTemperature
import app.circlet.core.Sport
import app.circlet.core.ZoneMinutes
import app.circlet.core.emptyDay
import app.circlet.data.codec.ActivityCodec
import app.circlet.data.codec.DayCodec
import app.circlet.data.codec.LiveCodec
import app.circlet.data.codec.packGravity
import app.circlet.data.codec.packRr
import app.circlet.data.codec.toEntity
import app.circlet.data.codec.toSample
import app.circlet.data.codec.unpackGravity
import app.circlet.data.codec.unpackRr
import app.circlet.data.db.CircletDatabase
import app.circlet.data.db.CircletMigrations
import app.circlet.data.health.HealthRecordClient
import app.circlet.data.health.HealthWrite
import app.circlet.data.health.healthWrites
import app.circlet.data.health.writeMeasured
import app.circlet.data.pipeline.affectedDates
import app.circlet.data.pipeline.sampleWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric supplies a real org.json. The Android unit-test stubs return null from JSONObject.toString. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PureTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 6, 15)

    @Test
    fun rrAndGravityRoundTrip() {
        val rr = listOf(200, 1000, 2500)
        assertEquals(rr, unpackRr(packRr(rr)))
        assertNull(packRr(emptyList()))
        val gravity = GravityVector(-0.25f, 0.5f, 0.97f)
        assertEquals(gravity, unpackGravity(packGravity(gravity)))
        assertNull(unpackGravity(null))
    }

    @Test
    fun eveningSampleDirtiesTheNextMorningToo() {
        val evening = date.atTime(18, 0).atZone(zone).toEpochSecond()
        val before = date.atTime(17, 59).atZone(zone).toEpochSecond()
        assertEquals(setOf(date, date.plusDays(1)), affectedDates(listOf(evening), zone))
        assertEquals(setOf(date), affectedDates(listOf(before), zone))
        val window = sampleWindow(date, zone)
        assertEquals(date.minusDays(1).atTime(18, 0).atZone(zone).toEpochSecond(), window.first)
        assertEquals(date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1, window.last)
    }

    @Test
    fun schemaVersionHasNoMissingMigrations() {
        assertEquals(2, CircletDatabase.VERSION)
        assertTrue(CircletMigrations.missingSteps().isEmpty())
    }

    @Test
    fun dayCodecRoundTripsAbsenceAndASession() {
        val absent = emptyDay(date, AbsentReason.NOT_WORN)
        assertEquals(absent, DayCodec.decode(DayCodec.encode(absent)))
        val start = date.atTime(23, 0).atZone(zone).toInstant()
        val session = SleepSession(
            id = "night",
            kind = SleepKind.MAIN,
            start = start,
            end = start.plus(Duration.ofHours(2)),
            epochs = listOf(SleepEpoch(start, SleepStage.REM, Duration.ofHours(2))),
            cycles = listOf(SleepCycle(1, start, start.plus(Duration.ofHours(2)))),
            latency = Duration.ofMinutes(12),
            efficiency = Metric.of(91, Confidence.HIGH),
            wakeEvents = emptyList(),
            timeInBed = Duration.ofHours(2),
            timeAsleep = Duration.ofHours(2),
        )
        val activity = Activity(
            id = "run",
            source = ActivitySource.AUTO,
            sport = Sport.RUNNING,
            sportConfidence = Confidence.MEDIUM,
            start = start,
            end = start.plus(Duration.ofMinutes(30)),
            strain = Metric.of(8.5, Confidence.MEDIUM),
            averageHeartRateBpm = Metric.of(150, Confidence.HIGH),
            maxHeartRateBpm = Metric.of(172, Confidence.HIGH),
            calories = Metric.of(240, Confidence.LOW),
            zoneMinutes = ZoneMinutes.of(0, 1, 2, 10, 15, 2),
            heartRateRecovery = null,
        )
        val day = absent.copy(
            sleep = Metric.of(session, Confidence.HIGH),
            calories = Metric.of(240, Confidence.LOW),
            activities = listOf(activity),
            heartRateByMinute = listOf(null, 60, null),
        )
        assertEquals(day, DayCodec.decode(DayCodec.encode(day)))
    }

    @Test
    fun dayCodecRoundTripsSkinTemperatureAndReferences() {
        val day = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA).copy(
            skinTemperature = SkinTemperature(
                deviation = Metric.of(0.4, Confidence.MEDIUM),
                baselineNights = 12,
                flag = SkinTempFlag.NORMAL,
            ),
            references = listOf(
                MetricReference(
                    kind = MetricKind.HRV,
                    personal = ReferenceRange(38.0, 52.0),
                    personalDays = 12,
                    ideal = ReferenceRange(19.0, 75.0),
                    idealLabel = "Typical adult range",
                    idealSource = "Nunan et al. 2010",
                    higherIsBetter = true,
                ),
                MetricReference(
                    kind = MetricKind.STRAIN,
                    personal = null,
                    personalDays = 3,
                    ideal = null,
                    idealLabel = null,
                    idealSource = null,
                    higherIsBetter = null,
                ),
            ),
        )
        assertEquals(day, DayCodec.decode(DayCodec.encode(day)))
    }

    @Test
    fun sampleEntityRoundTripsTheSkinTempCount() {
        val withCount = Sample(
            epochSecond = 1000,
            heartRateBpm = 60,
            rrIntervalsMs = emptyList(),
            gravity = null,
            optical = null,
            rawRecord = null,
            skinTempCount = 935,
        )
        assertEquals(935, withCount.toEntity().skinTempCount)
        assertEquals(withCount, withCount.toEntity().toSample())
        val withoutCount = withCount.copy(skinTempCount = null)
        assertNull(withoutCount.toEntity().skinTempCount)
        assertNull(withoutCount.toEntity().toSample().skinTempCount)
    }

    @Test
    fun healthWritesOmitAbsentValuesAndKeepStableIds() {
        val now = Instant.parse("2026-06-16T18:00:00Z")
        assertTrue(healthWrites(emptyDay(date, AbsentReason.NOT_WORN), zone, now).isEmpty())

        val day = measuredDay()
        val writes = healthWrites(day, ZoneOffset.UTC, now)
        assertEquals(writes.map { it.clientRecordId }, healthWrites(day, ZoneOffset.UTC, now).map { it.clientRecordId })
        assertEquals(
            setOf(
                HealthKind.SLEEP,
                HealthKind.RESTING_HEART_RATE,
                HealthKind.HRV,
                HealthKind.RESPIRATORY_RATE,
                HealthKind.ACTIVE_ENERGY,
                HealthKind.WORKOUTS,
                HealthKind.STEPS,
            ),
            writes.map { it.kind }.toSet(),
        )
        val sleep = writes.single { it.kind == HealthKind.SLEEP }
        assertEquals("circlet:sleep:night", sleep.clientRecordId)
        assertEquals(listOf(SleepStage.LIGHT, SleepStage.DEEP, SleepStage.REM), sleep.stages.map { it.stage })
        assertEquals(240.0, writes.single { it.kind == HealthKind.ACTIVE_ENERGY }.activeKcal)
        assertEquals(8000L, writes.single { it.kind == HealthKind.STEPS }.steps)

        val caloriesOnly = emptyDay(date, AbsentReason.NOT_ENOUGH_DATA).copy(calories = Metric.of(900, Confidence.LOW))
        assertTrue(healthWrites(caloriesOnly, ZoneOffset.UTC, now).none { it.kind == HealthKind.ACTIVE_ENERGY })

        val future = day.activities.single().copy(
            start = now.plus(Duration.ofHours(1)),
            end = now.plus(Duration.ofHours(2)),
        )
        assertTrue(healthWrites(day.copy(activities = listOf(future)), ZoneOffset.UTC, now).none { it.kind == HealthKind.WORKOUTS })
    }

    @Test
    fun healthExportDeletesByClientIdBeforeInsert() = runBlocking {
        val client = RecordingClient()
        val writes = healthWrites(measuredDay(), ZoneOffset.UTC, Instant.parse("2026-06-16T18:00:00Z"))
        writeMeasured(client, writes)
        writeMeasured(client, writes)
        val kinds = writes.map { it.kind }.distinct()
        assertEquals(kinds.size * 2, client.deleted.size)
        val firstPass = client.deleted.take(kinds.size).flatMap { it.second }.toSet()
        val secondPass = client.deleted.drop(kinds.size).flatMap { it.second }.toSet()
        assertEquals(writes.map { it.clientRecordId }.toSet(), firstPass)
        assertEquals(firstPass, secondPass)
        assertEquals(writes.size * 2, client.inserted.size)
    }

    @Test
    fun pauseFieldsRoundTripAndOldRowsDecodeWithoutThem() {
        val start = Instant.parse("2026-06-15T12:00:00Z")
        val pause = WorkoutPause(start.plusSeconds(10), start.plusSeconds(40))
        val live = LiveWorkout(
            sport = Sport.YOGA,
            startedAt = start,
            heartRateBpm = 110,
            strain = 1.5,
            caloriesKcal = 12,
            zoneMinutes = ZoneMinutes.ZERO,
            averageHeartRateBpm = 100,
            maxHeartRateBpm = 110,
            pausedAt = start.plusSeconds(50),
            pausedSeconds = 30,
            pauses = listOf(pause),
        )
        val stored = LiveCodec.decode(LiveCodec.encode(live, 2, start.toEpochMilli(), listOf(100, 110)))
        assertEquals(live, stored.workout)
        assertEquals(listOf(100, 110), stored.beats)
        assertEquals(2, stored.samples)

        val oldLive = """{"sport":"YOGA","startedAt":${start.toEpochMilli()},"strain":1.5,"calories":12,"zones":[0,0,0,0,0,0],"samples":4,"lastTick":${start.toEpochMilli()}}"""
        val legacy = LiveCodec.decode(oldLive)
        assertNull(legacy.workout.pausedAt)
        assertEquals(0L, legacy.workout.pausedSeconds)
        assertTrue(legacy.workout.pauses.isEmpty())
        assertTrue(legacy.beats.isEmpty())
        assertEquals(4, legacy.samples)

        val activity = Activity(
            id = "paused",
            source = ActivitySource.MANUAL,
            sport = Sport.YOGA,
            sportConfidence = null,
            start = start,
            end = start.plusSeconds(120),
            strain = Metric.of(2.0, Confidence.LOW),
            averageHeartRateBpm = Metric.of(100, Confidence.LOW),
            maxHeartRateBpm = Metric.of(110, Confidence.LOW),
            calories = Metric.of(20, Confidence.LOW),
            zoneMinutes = ZoneMinutes.ZERO,
            heartRateRecovery = null,
            pausedSeconds = 30,
            pauses = listOf(pause),
        )
        assertEquals(activity, ActivityCodec.decode(ActivityCodec.encode(activity)))

        val oldActivity = """{"id":"old","source":"MANUAL","sport":"YOGA","start":${start.toEpochMilli()},"end":${start.plusSeconds(60).toEpochMilli()},"strain":{"confidence":"LOW","absent":"NOT_ENOUGH_DATA"},"average":{"confidence":"LOW","absent":"NOT_ENOUGH_DATA"},"max":{"confidence":"LOW","absent":"NOT_ENOUGH_DATA"},"calories":{"confidence":"LOW","absent":"NOT_ENOUGH_DATA"},"zones":[0,0,0,0,0,0]}"""
        val decoded = ActivityCodec.decode(oldActivity)
        assertEquals(0L, decoded.pausedSeconds)
        assertTrue(decoded.pauses.isEmpty())
        assertEquals("old", decoded.id)
    }

    private fun measuredDay() = run {
        val start = Instant.parse("2026-06-15T20:00:00Z")
        val session = SleepSession(
            id = "night",
            kind = SleepKind.MAIN,
            start = start,
            end = start.plus(Duration.ofHours(8)),
            epochs = listOf(
                SleepEpoch(start, SleepStage.LIGHT, Duration.ofHours(3)),
                SleepEpoch(start.plus(Duration.ofHours(3)), SleepStage.DEEP, Duration.ofHours(2)),
                SleepEpoch(start.plus(Duration.ofHours(5)), SleepStage.REM, Duration.ofHours(3)),
            ),
            cycles = emptyList(),
            latency = Duration.ofMinutes(15),
            efficiency = Metric.of(90, Confidence.HIGH),
            wakeEvents = emptyList(),
            timeInBed = Duration.ofHours(8),
            timeAsleep = Duration.ofHours(8),
        )
        val activity = Activity(
            id = "run",
            source = ActivitySource.MANUAL,
            sport = Sport.RUNNING,
            sportConfidence = null,
            start = Instant.parse("2026-06-16T06:00:00Z"),
            end = Instant.parse("2026-06-16T06:40:00Z"),
            strain = Metric.of(10.0, Confidence.MEDIUM),
            averageHeartRateBpm = Metric.of(155, Confidence.HIGH),
            maxHeartRateBpm = Metric.of(178, Confidence.HIGH),
            calories = Metric.of(240, Confidence.LOW),
            zoneMinutes = ZoneMinutes.ZERO,
            heartRateRecovery = null,
        )
        emptyDay(LocalDate.of(2026, 6, 16), AbsentReason.NOT_ENOUGH_DATA).copy(
            sleep = Metric.of(session, Confidence.HIGH),
            heart = app.circlet.core.DayHeart(
                resting = app.circlet.core.RestingHeartRate(Metric.of(48, Confidence.HIGH)),
                hrv = app.circlet.core.Hrv(
                    rmssdMs = Metric.of(62.0, Confidence.HIGH),
                    sdnnMs = Metric.of(70.0, Confidence.MEDIUM),
                    frequency = null,
                ),
                respiratoryRate = app.circlet.core.RespiratoryRate(Metric.of(14.5, Confidence.MEDIUM)),
                averageBpm = Metric.of(70, Confidence.MEDIUM),
                maxBpm = Metric.of(178, Confidence.HIGH),
            ),
            steps = app.circlet.core.Steps(Metric.of(8000, Confidence.LOW)),
            activities = listOf(activity),
        )
    }
}

private class RecordingClient : HealthRecordClient {
    val deleted = mutableListOf<Pair<HealthKind, List<String>>>()
    val inserted = mutableListOf<HealthWrite>()

    override suspend fun delete(kind: HealthKind, clientRecordIds: List<String>) {
        deleted += kind to clientRecordIds
    }

    override suspend fun insert(writes: List<HealthWrite>) {
        inserted += writes
    }
}

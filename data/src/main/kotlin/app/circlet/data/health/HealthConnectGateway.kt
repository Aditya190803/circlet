package app.circlet.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import app.circlet.core.HealthConnectStatus
import app.circlet.core.HealthKind
import app.circlet.core.SleepStage
import app.circlet.core.Sport
import androidx.health.connect.client.records.metadata.Device
import java.time.ZoneOffset
import kotlin.reflect.KClass

internal class HealthConnectGateway(private val context: Context) : HealthRecordClient {
    suspend fun status(enabled: Boolean): HealthConnectStatus {
        val available = sdkAvailable()
        val granted = if (available) grantedKinds() else emptySet()
        return HealthConnectStatus(available = available, enabled = enabled, granted = granted)
    }

    fun writePermissions(): Set<String> = try {
        KIND_BY_TYPE.keys.map { HealthPermission.getWritePermission(it) }.toSet()
    } catch (error: Throwable) {
        emptySet()
    }

    override suspend fun delete(kind: HealthKind, clientRecordIds: List<String>) {
        if (clientRecordIds.isEmpty() || !sdkAvailable()) return
        val type = recordType(kind) ?: return
        client().deleteRecords(type, emptyList(), clientRecordIds)
    }

    override suspend fun insert(writes: List<HealthWrite>) {
        if (writes.isEmpty() || !sdkAvailable()) return
        client().insertRecords(writes.map { it.toRecord() })
    }

    /** Best-effort. The platform may not allow an app to drop grants on its own. */
    suspend fun revokeGranted() {
        if (!sdkAvailable()) return
        client().permissionController.revokeAllPermissions()
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    private fun sdkAvailable(): Boolean = try {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    } catch (error: Throwable) {
        false
    }

    private suspend fun grantedKinds(): Set<HealthKind> = try {
        val granted = client().permissionController.getGrantedPermissions()
        KIND_BY_TYPE.mapNotNull { (type, kind) ->
            if (HealthPermission.getWritePermission(type) in granted) kind else null
        }.toSet()
    } catch (error: Throwable) {
        emptySet()
    }

    private fun recordType(kind: HealthKind): KClass<out Record>? = when (kind) {
        HealthKind.SLEEP -> SleepSessionRecord::class
        HealthKind.RESTING_HEART_RATE -> RestingHeartRateRecord::class
        HealthKind.HRV -> HeartRateVariabilityRmssdRecord::class
        HealthKind.RESPIRATORY_RATE -> RespiratoryRateRecord::class
        HealthKind.ACTIVE_ENERGY -> ActiveCaloriesBurnedRecord::class
        HealthKind.WORKOUTS -> ExerciseSessionRecord::class
        HealthKind.STEPS -> StepsRecord::class
    }
}

private val KIND_BY_TYPE: Map<KClass<out Record>, HealthKind> = mapOf(
    SleepSessionRecord::class to HealthKind.SLEEP,
    RestingHeartRateRecord::class to HealthKind.RESTING_HEART_RATE,
    HeartRateVariabilityRmssdRecord::class to HealthKind.HRV,
    RespiratoryRateRecord::class to HealthKind.RESPIRATORY_RATE,
    ActiveCaloriesBurnedRecord::class to HealthKind.ACTIVE_ENERGY,
    ExerciseSessionRecord::class to HealthKind.WORKOUTS,
    StepsRecord::class to HealthKind.STEPS,
)

private val WATCH = Device(manufacturer = "Circlet", model = "WHOOP 4.0", type = Device.TYPE_WATCH)

private fun HealthWrite.toRecord(): Record {
    val offset = ZoneOffset.ofTotalSeconds(offsetTotalSeconds)
    val metadata = if (manual) {
        Metadata.activelyRecorded(device = WATCH, clientRecordId = clientRecordId)
    } else {
        Metadata.autoRecorded(device = WATCH, clientRecordId = clientRecordId)
    }
    return when (kind) {
        HealthKind.SLEEP -> SleepSessionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = offset,
            stages = stages.map { stage ->
                SleepSessionRecord.Stage(
                    startTime = stage.start,
                    endTime = stage.end,
                    stage = stage.stage.toHealth(),
                )
            },
            metadata = metadata,
        )
        HealthKind.RESTING_HEART_RATE -> RestingHeartRateRecord(
            time = start,
            zoneOffset = offset,
            beatsPerMinute = checkNotNull(bpm),
            metadata = metadata,
        )
        HealthKind.HRV -> HeartRateVariabilityRmssdRecord(
            time = start,
            zoneOffset = offset,
            heartRateVariabilityMillis = checkNotNull(hrvRmssdMs),
            metadata = metadata,
        )
        HealthKind.RESPIRATORY_RATE -> RespiratoryRateRecord(
            time = start,
            zoneOffset = offset,
            rate = checkNotNull(breathsPerMinute),
            metadata = metadata,
        )
        HealthKind.ACTIVE_ENERGY -> ActiveCaloriesBurnedRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = offset,
            energy = Energy.kilocalories(checkNotNull(activeKcal)),
            metadata = metadata,
        )
        HealthKind.WORKOUTS -> ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = offset,
            exerciseType = checkNotNull(sport).toExerciseType(),
            metadata = metadata,
        )
        HealthKind.STEPS -> StepsRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = offset,
            count = checkNotNull(steps),
            metadata = metadata,
        )
    }
}

private fun SleepStage.toHealth(): Int = when (this) {
    SleepStage.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
    SleepStage.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
    SleepStage.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
    SleepStage.REM -> SleepSessionRecord.STAGE_TYPE_REM
}

private fun Sport.toExerciseType(): Int = when (this) {
    Sport.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
    Sport.CYCLING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    Sport.WALKING -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
    Sport.HIKING -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
    Sport.SWIMMING -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
    Sport.ROWING -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
    Sport.ELLIPTICAL -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
    Sport.STAIR_CLIMBER -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
    Sport.STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
    Sport.HIIT -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
    Sport.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
    Sport.PILATES -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
    Sport.DANCE -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
    Sport.BOXING -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING
    Sport.MARTIAL_ARTS -> ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS
    Sport.CLIMBING -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
    Sport.BASKETBALL -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
    Sport.SOCCER -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
    Sport.TENNIS -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
    Sport.GOLF -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF
    Sport.SKIING -> ExerciseSessionRecord.EXERCISE_TYPE_SKIING
    Sport.SNOWBOARDING -> ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING
    Sport.SKATING -> ExerciseSessionRecord.EXERCISE_TYPE_SKATING
    Sport.SURFING -> ExerciseSessionRecord.EXERCISE_TYPE_SURFING
    Sport.JUMP_ROPE, Sport.RUCKING, Sport.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}

internal fun exerciseTypeFor(sport: Sport): Int = sport.toExerciseType()

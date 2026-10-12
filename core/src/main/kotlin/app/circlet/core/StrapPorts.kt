package app.circlet.core

import java.time.Duration
import java.time.Instant

/**
 * One stored historical record, kept so a later decoder can re-read the bytes.
 * [epochSecond] is the unix second from the record when the layout has one, otherwise 0.
 */
data class RawRecord(
    val epochSecond: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawRecord) return false
        return epochSecond == other.epochSecond && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * epochSecond.hashCode() + bytes.contentHashCode()
}

/**
 * Durable sample store. [commit] returns only after [samples] and [rawRecords]
 * are stored. The strap link acknowledges a history batch after that return.
 */
interface SampleSink {
    suspend fun commit(samples: List<Sample>, rawRecords: List<RawRecord>)
}

/** Durable strap events. [record] returns only after [event] is stored. */
interface StrapEventSink {
    suspend fun record(event: StrapEvent)
}

/**
 * Pairing and alarm preferences the data module owns.
 * A null address means no strap is paired.
 */
interface StrapPrefs {
    suspend fun pairedAddress(): String?

    suspend fun setPairedAddress(address: String?)

    suspend fun strapName(): String?

    suspend fun setStrapName(name: String?)

    suspend fun lastSync(): Instant?

    suspend fun setLastSync(at: Instant?)

    suspend fun alarms(): List<StrapAlarm>

    suspend fun setAlarms(alarms: List<StrapAlarm>)
}

/**
 * Finished activities. The data module implements this.
 * [saveLive] and [currentLive] persist the in-progress session, including a pause, across process death.
 * The defaults do nothing so a store that only keeps finished activities still compiles.
 */
interface WorkoutStore {
    suspend fun insert(activity: Activity)

    suspend fun update(activity: Activity)

    suspend fun delete(id: String)

    suspend fun get(id: String): Activity?

    suspend fun saveLive(session: LiveSession?) {}

    suspend fun currentLive(): LiveSession? = null
}

/**
 * The wearer's current sleep stage, supplied later by analytics.
 * Null means there is no estimate.
 */
fun interface SleepStageEstimate {
    fun current(): SleepStage?

    companion object {
        val UNKNOWN: SleepStageEstimate = SleepStageEstimate { null }
    }
}

/** What a double tap should do, read from settings when the tap arrives. */
fun interface DoubleTapPolicy {
    suspend fun current(): DoubleTapAction
}

/**
 * Strain, calories, and zone minutes for a workout that is still being recorded.
 * The default reports those as absent. Heart rate stays with the workout controller,
 * which counted the beats itself.
 */
interface LiveStrainEstimator {
    fun load(elapsed: Duration, heartRatesBpm: List<Int>): LiveLoad

    companion object {
        val NONE: LiveStrainEstimator = object : LiveStrainEstimator {
            override fun load(elapsed: Duration, heartRatesBpm: List<Int>): LiveLoad = LiveLoad(
                strain = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                calories = Metric.absent(AbsentReason.NOT_ENOUGH_DATA),
                zoneMinutes = ZoneMinutes.ZERO,
            )
        }
    }
}

/** The numbers [LiveStrainEstimator] adds to a live workout. */
data class LiveLoad(
    val strain: Metric<Double>,
    val calories: Metric<Int>,
    val zoneMinutes: ZoneMinutes,
)

/**
 * Everything the strap module needs from the rest of the app.
 * [liveLoad] defaults to an estimator that does not invent strain or calories.
 */
data class StrapPorts(
    val samples: SampleSink,
    val events: StrapEventSink,
    val prefs: StrapPrefs,
    val workouts: WorkoutStore,
    val sleepStage: SleepStageEstimate,
    val doubleTap: DoubleTapPolicy,
    val liveLoad: LiveStrainEstimator = LiveStrainEstimator.NONE,
)

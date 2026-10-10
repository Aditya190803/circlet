package app.circlet.data.pipeline

import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.data.codec.ActivityCodec
import app.circlet.data.db.ActivityEntity
import java.time.LocalDate
import kotlin.math.abs

private const val TOMBSTONE_SLACK_MS = 120_000L

/**
 * Keeps manual rows and user edits, drops auto detections the user deleted,
 * and lets a matching engine result refresh strain and heart numbers on a kept row.
 */
internal fun blendActivities(
    date: LocalDate,
    stored: List<ActivityEntity>,
    computed: List<Activity>,
): List<ActivityEntity> {
    val tombstones = stored.filter { it.suppressed }
    val sticky = stored.filter { !it.suppressed && (it.source == ActivitySource.MANUAL.name || it.userEdited) }
    val stickyIds = sticky.map { it.id }.toSet()
    val kept = sticky.map { row ->
        val match = computed.find { it.id == row.id }
        val base = ActivityCodec.decode(row.payload)
        val blended = if (match == null) {
            base
        } else {
            base.copy(
                strain = match.strain,
                averageHeartRateBpm = match.averageHeartRateBpm,
                maxHeartRateBpm = match.maxHeartRateBpm,
                calories = match.calories,
                zoneMinutes = match.zoneMinutes,
                heartRateRecovery = match.heartRateRecovery,
            )
        }
        row.copy(
            payload = ActivityCodec.encode(blended),
            startMillis = blended.start.toEpochMilli(),
            endMillis = blended.end.toEpochMilli(),
        )
    }
    val autos = computed.filter { activity ->
        activity.source == ActivitySource.AUTO &&
            activity.id !in stickyIds &&
            tombstones.none { abs(activity.start.toEpochMilli() - it.startMillis) <= TOMBSTONE_SLACK_MS } &&
            kept.none { overlaps(it.startMillis, it.endMillis, activity.start.toEpochMilli(), activity.end.toEpochMilli()) }
    }.map { it.toEntity(date, userEdited = false, suppressed = false) }
    return kept + autos + tombstones
}

internal fun Activity.toEntity(date: LocalDate, userEdited: Boolean, suppressed: Boolean): ActivityEntity = ActivityEntity(
    id = id,
    localDate = date.toString(),
    source = source.name,
    userEdited = userEdited,
    suppressed = suppressed,
    startMillis = start.toEpochMilli(),
    endMillis = end.toEpochMilli(),
    payload = ActivityCodec.encode(this),
)

private fun overlaps(start: Long, end: Long, otherStart: Long, otherEnd: Long): Boolean =
    start < otherEnd && otherStart < end

package app.circlet.data.repo

import app.circlet.core.StrapAlarm
import app.circlet.data.db.AlarmEntity
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

internal fun AlarmEntity.toAlarm(): StrapAlarm = StrapAlarm(
    id = id,
    time = LocalTime.parse(time),
    weekdays = if (weekdays.isBlank()) {
        emptySet()
    } else {
        weekdays.split(",").map { DayOfWeek.valueOf(it) }.toSet()
    },
    smartWake = Duration.ofMillis(smartWakeMillis),
    enabled = enabled,
)

internal fun StrapAlarm.toEntity(): AlarmEntity = AlarmEntity(
    id = id,
    time = time.toString(),
    weekdays = weekdays.sortedBy { it.value }.joinToString(",") { it.name },
    smartWakeMillis = smartWake.toMillis(),
    enabled = enabled,
)

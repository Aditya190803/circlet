package app.circlet.data.codec

import app.circlet.core.AbsentReason
import app.circlet.core.Activity
import app.circlet.core.ActivitySource
import app.circlet.core.CalibrationStatus
import app.circlet.core.Confidence
import app.circlet.core.DayHeart
import app.circlet.core.DayStress
import app.circlet.core.DaySummary
import app.circlet.core.FrequencyDomain
import app.circlet.core.HeartRateRecovery
import app.circlet.core.HeartRateZone
import app.circlet.core.Hrv
import app.circlet.core.HrvSpotCheck
import app.circlet.core.JournalEntry
import app.circlet.core.LiveWorkout
import app.circlet.core.Metric
import app.circlet.core.MetricKind
import app.circlet.core.MetricReference
import app.circlet.core.Profile
import app.circlet.core.Recovery
import app.circlet.core.RecoveryDriver
import app.circlet.core.RecoveryDriverKind
import app.circlet.core.ReferenceRange
import app.circlet.core.RespiratoryRate
import app.circlet.core.RestingHeartRate
import app.circlet.core.Sex
import app.circlet.core.SkinTempFlag
import app.circlet.core.SkinTemperature
import app.circlet.core.SleepConsistency
import app.circlet.core.SleepCycle
import app.circlet.core.SleepDebt
import app.circlet.core.SleepEpoch
import app.circlet.core.SleepKind
import app.circlet.core.SleepNeed
import app.circlet.core.SleepPerformance
import app.circlet.core.SleepSession
import app.circlet.core.SleepStage
import app.circlet.core.Sport
import app.circlet.core.Steps
import app.circlet.core.Strain
import app.circlet.core.StressPoint
import app.circlet.core.TargetStrain
import app.circlet.core.Vo2Max
import app.circlet.core.WakeEvent
import app.circlet.core.WellnessFlag
import app.circlet.core.WellnessKind
import app.circlet.core.WorkoutPause
import app.circlet.core.ZoneMinutes
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

internal object DayCodec {
    fun encode(day: DaySummary): String = JSONObject().apply {
        put("date", day.date.toString())
        put("sleep", metricJson(day.sleep) { put("value", sleepJson(it)) })
        put("naps", JSONArray().apply { day.naps.forEach { put(sleepJson(it)) } })
        put("recovery", recoveryJson(day.recovery))
        put("strain", strainJson(day.strain))
        put("stress", stressJson(day.stress))
        put("heart", heartJson(day.heart))
        put("steps", metricJson(day.steps.count) { put("value", it) })
        put("calories", metricJson(day.calories) { put("value", it) })
        put("activities", JSONArray().apply { day.activities.forEach { put(JSONObject(ActivityCodec.encode(it))) } })
        put("heartRateByMinute", JSONArray().apply {
            day.heartRateByMinute.forEach { bpm -> if (bpm == null) put(JSONObject.NULL) else put(bpm) }
        })
        put("sleepNeed", JSONObject().apply {
            put("duration", metricJson(day.sleepNeed.duration) { put("value", it.toMillis()) })
            day.sleepNeed.recommendedBedtime?.let { put("bedtime", it.toEpochMilli()) }
        })
        put("sleepDebt", metricJson(day.sleepDebt.shortfall) { put("value", it.toMillis()) })
        put("sleepPerformance", metricJson(day.sleepPerformance.percent) { put("value", it) })
        put("sleepConsistency", metricJson(day.sleepConsistency.index) { put("value", it) })
        put("targetStrain", metricJson(day.targetStrain.score) { put("value", it) })
        put("vo2Max", metricJson(day.vo2Max.mlPerKgMin) { put("value", it) })
        put("wellness", JSONArray().apply { day.wellness.forEach { put(wellnessJson(it)) } })
        day.skinTemperature?.let { put("skinTemperature", skinTempJson(it)) }
        put("references", JSONArray().apply { day.references.forEach { put(referenceJson(it)) } })
    }.toString()

    fun decode(payload: String): DaySummary {
        val json = JSONObject(payload)
        val date = LocalDate.parse(json.getString("date"))
        return DaySummary(
            date = date,
            sleep = json.readMetric("sleep") { sleep(getJSONObject("value")) },
            naps = json.getJSONArray("naps").objects().map { sleep(it) },
            recovery = recovery(json.getJSONObject("recovery")),
            strain = strain(json.getJSONObject("strain")),
            stress = stress(json.getJSONObject("stress")),
            heart = heart(json.getJSONObject("heart")),
            steps = Steps(json.readMetric("steps") { getInt("value") }),
            calories = json.readMetric("calories") { getInt("value") },
            activities = json.getJSONArray("activities").objects().map { ActivityCodec.decode(it.toString()) },
            heartRateByMinute = json.getJSONArray("heartRateByMinute").let { array ->
                List(array.length()) { index -> if (array.isNull(index)) null else array.getInt(index) }
            },
            sleepNeed = json.getJSONObject("sleepNeed").let { need ->
                SleepNeed(
                    duration = need.readMetric("duration") { Duration.ofMillis(getLong("value")) },
                    recommendedBedtime = if (need.has("bedtime") && !need.isNull("bedtime")) {
                        Instant.ofEpochMilli(need.getLong("bedtime"))
                    } else {
                        null
                    },
                )
            },
            sleepDebt = SleepDebt(json.readMetric("sleepDebt") { Duration.ofMillis(getLong("value")) }),
            sleepPerformance = SleepPerformance(json.readMetric("sleepPerformance") { getInt("value") }),
            sleepConsistency = SleepConsistency(json.readMetric("sleepConsistency") { getDouble("value") }),
            targetStrain = TargetStrain(json.readMetric("targetStrain") { getDouble("value") }),
            vo2Max = Vo2Max(json.readMetric("vo2Max") { getDouble("value") }),
            wellness = json.getJSONArray("wellness").objects().map { wellness(it) },
            skinTemperature = if (json.has("skinTemperature") && !json.isNull("skinTemperature")) {
                skinTemp(json.getJSONObject("skinTemperature"))
            } else {
                null
            },
            references = if (json.has("references") && !json.isNull("references")) {
                json.getJSONArray("references").objects().map { reference(it) }
            } else {
                emptyList()
            },
        )
    }
}

internal object ActivityCodec {
    fun encode(activity: Activity): String = activityJson(activity).toString()

    fun decode(payload: String): Activity = activity(JSONObject(payload))
}

internal object ProfileCodec {
    fun encode(profile: Profile): String = JSONObject().apply {
        profile.birthDate?.let { put("birthDate", it.toString()) }
        profile.ageYears?.let { put("ageYears", it) }
        put("sex", profile.sex.name)
        profile.heightCm?.let { put("heightCm", it) }
        profile.weightKg?.let { put("weightKg", it) }
        profile.maxHeartRateBpm?.let { put("maxHeartRateBpm", it) }
        put("zones", JSONArray().apply {
            profile.customZones.forEach { zone ->
                put(JSONObject().apply {
                    put("index", zone.index)
                    put("from", zone.fromBpm)
                    put("until", zone.untilBpm)
                })
            }
        })
    }.toString()

    fun decode(payload: String): Profile {
        val json = JSONObject(payload)
        return Profile(
            birthDate = json.optString("birthDate").ifBlank { null }?.let(LocalDate::parse),
            ageYears = if (json.has("ageYears") && !json.isNull("ageYears")) json.getInt("ageYears") else null,
            sex = Sex.valueOf(json.getString("sex")),
            heightCm = if (json.has("heightCm") && !json.isNull("heightCm")) json.getDouble("heightCm") else null,
            weightKg = if (json.has("weightKg") && !json.isNull("weightKg")) json.getDouble("weightKg") else null,
            maxHeartRateBpm = if (json.has("maxHeartRateBpm") && !json.isNull("maxHeartRateBpm")) json.getInt("maxHeartRateBpm") else null,
            customZones = json.getJSONArray("zones").objects().map { zone ->
                HeartRateZone(zone.getInt("index"), zone.getInt("from"), zone.getInt("until"))
            },
        )
    }
}

internal fun defaultProfile(): Profile = Profile(
    birthDate = null,
    ageYears = null,
    sex = Sex.UNSPECIFIED,
    heightCm = null,
    weightKg = null,
    maxHeartRateBpm = null,
    customZones = emptyList(),
)

internal object SpotCodec {
    fun encode(check: HrvSpotCheck): String = JSONObject().apply {
        put("id", check.id)
        put("at", check.at.toEpochMilli())
        put("duration", check.duration.toMillis())
        put("hrv", hrvJson(check.hrv))
    }.toString()

    fun decode(payload: String): HrvSpotCheck {
        val json = JSONObject(payload)
        return HrvSpotCheck(
            id = json.getString("id"),
            at = Instant.ofEpochMilli(json.getLong("at")),
            duration = Duration.ofMillis(json.getLong("duration")),
            hrv = hrv(json.getJSONObject("hrv")),
        )
    }
}

internal object LiveCodec {
    fun encode(workout: LiveWorkout, samples: Int, lastTickMillis: Long, beats: List<Int> = emptyList()): String =
        JSONObject().apply {
            put("sport", workout.sport.name)
            put("startedAt", workout.startedAt.toEpochMilli())
            workout.heartRateBpm?.let { put("heartRate", it) }
            put("strain", workout.strain)
            put("calories", workout.caloriesKcal)
            put("zones", zonesJson(workout.zoneMinutes))
            workout.averageHeartRateBpm?.let { put("average", it) }
            workout.maxHeartRateBpm?.let { put("max", it) }
            put("samples", samples)
            put("lastTick", lastTickMillis)
            workout.pausedAt?.let { put("pausedAt", it.toEpochMilli()) }
            put("pausedSeconds", workout.pausedSeconds)
            put("pauses", pausesJson(workout.pauses))
            put("beats", beatsJson(beats))
        }.toString()

    fun decode(payload: String): StoredLive {
        val json = JSONObject(payload)
        val workout = LiveWorkout(
            sport = Sport.valueOf(json.getString("sport")),
            startedAt = Instant.ofEpochMilli(json.getLong("startedAt")),
            heartRateBpm = json.optionalInt("heartRate"),
            strain = json.getDouble("strain"),
            caloriesKcal = json.getInt("calories"),
            zoneMinutes = zones(json.getJSONArray("zones")),
            averageHeartRateBpm = json.optionalInt("average"),
            maxHeartRateBpm = json.optionalInt("max"),
            pausedAt = if (json.has("pausedAt") && !json.isNull("pausedAt")) {
                Instant.ofEpochMilli(json.getLong("pausedAt"))
            } else {
                null
            },
            pausedSeconds = json.optLong("pausedSeconds"),
            pauses = if (json.has("pauses") && !json.isNull("pauses")) pauses(json.getJSONArray("pauses")) else emptyList(),
        )
        val beats = if (json.has("beats") && !json.isNull("beats")) readBeats(json.getJSONArray("beats")) else emptyList()
        return StoredLive(
            workout,
            json.optInt("samples"),
            json.optLong("lastTick", workout.startedAt.toEpochMilli()),
            beats,
        )
    }
}

internal data class StoredLive(
    val workout: LiveWorkout,
    val samples: Int,
    val lastTickMillis: Long,
    val beats: List<Int> = emptyList(),
)

internal fun journalTagsJson(ids: Set<String>): String = JSONArray().apply { ids.sorted().forEach { put(it) } }.toString()

internal fun journalTags(payload: String): Set<String> {
    val array = JSONArray(payload)
    return buildSet {
        for (index in 0 until array.length()) add(array.getString(index))
    }
}

internal fun JournalEntry.toEntityNote(): String = note

private fun sleepJson(session: SleepSession): JSONObject = JSONObject().apply {
    put("id", session.id)
    put("kind", session.kind.name)
    put("start", session.start.toEpochMilli())
    put("end", session.end.toEpochMilli())
    put("epochs", JSONArray().apply {
        session.epochs.forEach { epoch ->
            put(JSONObject().apply {
                put("start", epoch.start.toEpochMilli())
                put("stage", epoch.stage.name)
                put("duration", epoch.duration.toMillis())
            })
        }
    })
    put("cycles", JSONArray().apply {
        session.cycles.forEach { cycle ->
            put(JSONObject().apply {
                put("number", cycle.number)
                put("start", cycle.start.toEpochMilli())
                put("end", cycle.end.toEpochMilli())
            })
        }
    })
    put("latency", session.latency.toMillis())
    put("efficiency", metricJson(session.efficiency) { put("value", it) })
    put("wakes", JSONArray().apply {
        session.wakeEvents.forEach { wake ->
            put(JSONObject().apply {
                put("at", wake.at.toEpochMilli())
                put("duration", wake.duration.toMillis())
            })
        }
    })
    put("inBed", session.timeInBed.toMillis())
    put("asleep", session.timeAsleep.toMillis())
}

private fun sleep(json: JSONObject): SleepSession = SleepSession(
    id = json.getString("id"),
    kind = SleepKind.valueOf(json.getString("kind")),
    start = Instant.ofEpochMilli(json.getLong("start")),
    end = Instant.ofEpochMilli(json.getLong("end")),
    epochs = json.getJSONArray("epochs").objects().map { epoch ->
        SleepEpoch(
            Instant.ofEpochMilli(epoch.getLong("start")),
            SleepStage.valueOf(epoch.getString("stage")),
            Duration.ofMillis(epoch.getLong("duration")),
        )
    },
    cycles = json.getJSONArray("cycles").objects().map { cycle ->
        SleepCycle(cycle.getInt("number"), Instant.ofEpochMilli(cycle.getLong("start")), Instant.ofEpochMilli(cycle.getLong("end")))
    },
    latency = Duration.ofMillis(json.getLong("latency")),
    efficiency = json.readMetric("efficiency") { getInt("value") },
    wakeEvents = json.getJSONArray("wakes").objects().map { wake ->
        WakeEvent(Instant.ofEpochMilli(wake.getLong("at")), Duration.ofMillis(wake.getLong("duration")))
    },
    timeInBed = Duration.ofMillis(json.getLong("inBed")),
    timeAsleep = Duration.ofMillis(json.getLong("asleep")),
)

private fun recoveryJson(recovery: Recovery): JSONObject = JSONObject().apply {
    put("score", metricJson(recovery.score) { put("value", it) })
    put("calibration", recovery.calibration.name)
    put("drivers", JSONArray().apply {
        recovery.drivers.forEach { driver ->
            put(JSONObject().apply {
                put("kind", driver.kind.name)
                if (driver.contribution == null) put("absent", driver.absentReason!!.name)
                else put("contribution", driver.contribution)
            })
        }
    })
}

private fun recovery(json: JSONObject): Recovery = Recovery(
    score = json.readMetric("score") { getInt("value") },
    calibration = CalibrationStatus.valueOf(json.getString("calibration")),
    drivers = json.getJSONArray("drivers").objects().map { driver ->
        val absent = if (driver.has("absent") && !driver.isNull("absent")) AbsentReason.valueOf(driver.getString("absent")) else null
        RecoveryDriver(
            kind = RecoveryDriverKind.valueOf(driver.getString("kind")),
            contribution = if (absent == null) driver.getDouble("contribution") else null,
            absentReason = absent,
        )
    },
)

private fun strainJson(strain: Strain): JSONObject = JSONObject().apply {
    put("score", metricJson(strain.score) { put("value", it) })
    put("zones", zonesJson(strain.zoneMinutes))
}

private fun strain(json: JSONObject): Strain = Strain(
    score = json.readMetric("score") { getDouble("value") },
    zoneMinutes = zones(json.getJSONArray("zones")),
)

private fun stressJson(stress: DayStress): JSONObject = JSONObject().apply {
    put("score", metricJson(stress.score) { put("value", it) })
    put("timeline", JSONArray().apply {
        stress.timeline.forEach { point ->
            put(JSONObject().apply {
                put("at", point.at.toEpochMilli())
                put("score", point.score)
            })
        }
    })
}

private fun stress(json: JSONObject): DayStress = DayStress(
    score = json.readMetric("score") { getDouble("value") },
    timeline = json.getJSONArray("timeline").objects().map { point ->
        StressPoint(Instant.ofEpochMilli(point.getLong("at")), point.getDouble("score"))
    },
)

private fun heartJson(heart: DayHeart): JSONObject = JSONObject().apply {
    put("resting", metricJson(heart.resting.bpm) { put("value", it) })
    put("hrv", hrvJson(heart.hrv))
    put("respiratory", metricJson(heart.respiratoryRate.breathsPerMinute) { put("value", it) })
    put("average", metricJson(heart.averageBpm) { put("value", it) })
    put("max", metricJson(heart.maxBpm) { put("value", it) })
}

private fun heart(json: JSONObject): DayHeart = DayHeart(
    resting = RestingHeartRate(json.readMetric("resting") { getInt("value") }),
    hrv = hrv(json.getJSONObject("hrv")),
    respiratoryRate = RespiratoryRate(json.readMetric("respiratory") { getDouble("value") }),
    averageBpm = json.readMetric("average") { getInt("value") },
    maxBpm = json.readMetric("max") { getInt("value") },
)

private fun hrvJson(hrv: Hrv): JSONObject = JSONObject().apply {
    put("rmssd", metricJson(hrv.rmssdMs) { put("value", it) })
    put("sdnn", metricJson(hrv.sdnnMs) { put("value", it) })
    hrv.frequency?.let { frequency ->
        put("frequency", JSONObject().apply {
            put("lf", frequency.lfMs2)
            put("hf", frequency.hfMs2)
            put("ratio", frequency.lfHfRatio)
        })
    }
}

private fun hrv(json: JSONObject): Hrv = Hrv(
    rmssdMs = json.readMetric("rmssd") { getDouble("value") },
    sdnnMs = json.readMetric("sdnn") { getDouble("value") },
    frequency = if (json.has("frequency") && !json.isNull("frequency")) {
        val frequency = json.getJSONObject("frequency")
        FrequencyDomain(frequency.getDouble("lf"), frequency.getDouble("hf"), frequency.getDouble("ratio"))
    } else {
        null
    },
)

private fun activityJson(activity: Activity): JSONObject = JSONObject().apply {
    put("id", activity.id)
    put("source", activity.source.name)
    put("sport", activity.sport.name)
    activity.sportConfidence?.let { put("sportConfidence", it.name) }
    put("start", activity.start.toEpochMilli())
    put("end", activity.end.toEpochMilli())
    put("strain", metricJson(activity.strain) { put("value", it) })
    put("average", metricJson(activity.averageHeartRateBpm) { put("value", it) })
    put("max", metricJson(activity.maxHeartRateBpm) { put("value", it) })
    put("calories", metricJson(activity.calories) { put("value", it) })
    put("zones", zonesJson(activity.zoneMinutes))
    activity.heartRateRecovery?.let { recovery ->
        put("hrr", metricJson(recovery.dropBpm) { put("value", it) })
    }
    put("pausedSeconds", activity.pausedSeconds)
    put("pauses", pausesJson(activity.pauses))
}

private fun activity(json: JSONObject): Activity = Activity(
    id = json.getString("id"),
    source = ActivitySource.valueOf(json.getString("source")),
    sport = Sport.valueOf(json.getString("sport")),
    sportConfidence = json.optString("sportConfidence").ifBlank { null }?.let(Confidence::valueOf),
    start = Instant.ofEpochMilli(json.getLong("start")),
    end = Instant.ofEpochMilli(json.getLong("end")),
    strain = json.readMetric("strain") { getDouble("value") },
    averageHeartRateBpm = json.readMetric("average") { getInt("value") },
    maxHeartRateBpm = json.readMetric("max") { getInt("value") },
    calories = json.readMetric("calories") { getInt("value") },
    zoneMinutes = zones(json.getJSONArray("zones")),
    heartRateRecovery = if (json.has("hrr") && !json.isNull("hrr")) {
        HeartRateRecovery(json.readMetric("hrr") { getInt("value") })
    } else {
        null
    },
    pausedSeconds = json.optLong("pausedSeconds"),
    pauses = if (json.has("pauses") && !json.isNull("pauses")) pauses(json.getJSONArray("pauses")) else emptyList(),
)

private fun wellnessJson(flag: WellnessFlag): JSONObject = JSONObject().apply {
    put("kind", flag.kind.name)
    put("confidence", flag.confidence.name)
    put("detail", flag.detail)
}

private fun wellness(json: JSONObject): WellnessFlag = WellnessFlag(
    kind = WellnessKind.valueOf(json.getString("kind")),
    confidence = Confidence.valueOf(json.getString("confidence")),
    detail = json.getString("detail"),
)

private fun skinTempJson(temp: SkinTemperature): JSONObject = JSONObject().apply {
    put("unit", temp.unit.name)
    put("deviation", metricJson(temp.deviation) { put("value", it) })
    put("baselineNights", temp.baselineNights)
    put("flag", temp.flag.name)
    temp.settledMeanCounts?.let { put("settledMeanCounts", it) }
    temp.importedCelsius?.let { put("importedCelsius", it) }
}

private fun skinTemp(json: JSONObject): SkinTemperature = SkinTemperature(
    unit = app.circlet.core.SkinTemperatureUnit.valueOf(json.optString("unit", "ADC_COUNTS")),
    deviation = json.readMetric("deviation") { getDouble("value") },
    baselineNights = json.getInt("baselineNights"),
    flag = SkinTempFlag.valueOf(json.getString("flag")),
    settledMeanCounts = if (json.has("settledMeanCounts")) json.getDouble("settledMeanCounts") else null,
    importedCelsius = if (json.has("importedCelsius")) json.getDouble("importedCelsius") else null,
)

private fun referenceJson(ref: MetricReference): JSONObject = JSONObject().apply {
    put("kind", ref.kind.name)
    ref.personal?.let { personal ->
        put("personal", JSONObject().apply {
            put("low", personal.low)
            put("high", personal.high)
        })
    }
    put("personalDays", ref.personalDays)
    ref.ideal?.let { ideal ->
        put("ideal", JSONObject().apply {
            put("low", ideal.low)
            put("high", ideal.high)
        })
    }
    ref.idealLabel?.let { put("idealLabel", it) }
    ref.idealSource?.let { put("idealSource", it) }
    ref.higherIsBetter?.let { put("higherIsBetter", it) }
}

private fun reference(json: JSONObject): MetricReference {
    fun range(key: String): ReferenceRange? {
        if (!json.has(key) || json.isNull(key)) return null
        val band = json.getJSONObject(key)
        return ReferenceRange(band.getDouble("low"), band.getDouble("high"))
    }
    return MetricReference(
        kind = MetricKind.valueOf(json.getString("kind")),
        personal = range("personal"),
        personalDays = json.getInt("personalDays"),
        ideal = range("ideal"),
        idealLabel = json.optString("idealLabel").ifBlank { null },
        idealSource = json.optString("idealSource").ifBlank { null },
        higherIsBetter = if (json.has("higherIsBetter") && !json.isNull("higherIsBetter")) {
            json.getBoolean("higherIsBetter")
        } else {
            null
        },
    )
}

private fun pausesJson(pauses: List<WorkoutPause>): JSONArray = JSONArray().apply {
    pauses.forEach { pause ->
        put(JSONObject().apply {
            put("start", pause.start.toEpochMilli())
            put("end", pause.end.toEpochMilli())
        })
    }
}

private fun pauses(array: JSONArray): List<WorkoutPause> = array.objects().map { pause ->
    WorkoutPause(Instant.ofEpochMilli(pause.getLong("start")), Instant.ofEpochMilli(pause.getLong("end")))
}

private fun beatsJson(beats: List<Int>): JSONArray = JSONArray().apply { beats.forEach { put(it) } }

private fun readBeats(array: JSONArray): List<Int> = List(array.length()) { array.getInt(it) }

private fun zonesJson(zones: ZoneMinutes): JSONArray = JSONArray().apply { zones.minutes.forEach { put(it) } }

private fun zones(array: JSONArray): ZoneMinutes = ZoneMinutes(List(array.length()) { array.getInt(it) })

private fun <T> metricJson(metric: Metric<T>, write: JSONObject.(T) -> Unit): JSONObject {
    val json = JSONObject()
    json.put("confidence", metric.confidence.name)
    val value = metric.value
    if (value == null) json.put("absent", checkNotNull(metric.absentReason).name)
    else json.write(value)
    return json
}

private fun <T> JSONObject.readMetric(key: String, read: JSONObject.() -> T): Metric<T> {
    val json = getJSONObject(key)
    val confidence = Confidence.valueOf(json.getString("confidence"))
    if (json.has("absent") && !json.isNull("absent")) {
        return Metric.absent(AbsentReason.valueOf(json.getString("absent")), confidence)
    }
    return Metric.of(json.read(), confidence)
}

private fun JSONObject.optionalInt(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

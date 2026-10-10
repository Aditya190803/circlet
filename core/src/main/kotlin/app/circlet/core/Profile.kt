package app.circlet.core

import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

/** Sex used by the calorie and VO2max estimates. [UNSPECIFIED] means the user has not answered. */
enum class Sex {
    FEMALE,
    MALE,
    UNSPECIFIED,
}

/**
 * One custom heart-rate zone, numbered 1 through 5.
 * [fromBpm] is inclusive and [untilBpm] is exclusive, in beats per minute.
 * The top zone's upper bound is the user's max heart rate and is inclusive when applied.
 */
data class HeartRateZone(
    val index: Int,
    val fromBpm: Int,
    val untilBpm: Int,
)

/**
 * The body the metrics are computed for.
 *
 * [birthDate] wins over [ageYears] when both are set. Age is in whole years.
 * [heightCm] is centimetres. [weightKg] is kilograms; the UI converts for display.
 * [maxHeartRateBpm] is an override in beats per minute. Null means "estimate it".
 * An empty [customZones] means the default reserve zones.
 */
data class Profile(
    val birthDate: LocalDate?,
    val ageYears: Int?,
    val sex: Sex,
    val heightCm: Double?,
    val weightKg: Double?,
    val maxHeartRateBpm: Int?,
    val customZones: List<HeartRateZone>,
)

/**
 * Whole years of age on [on].
 * Returns the birth-date age when [Profile.birthDate] is set, otherwise [Profile.ageYears].
 * Returns null when the birth date is after [on].
 */
fun Profile.ageOn(on: LocalDate): Int? {
    val birth = birthDate ?: return ageYears
    if (on < birth) return null
    var age = on.year - birth.year
    if (on.monthValue < birth.monthValue || (on.monthValue == birth.monthValue && on.dayOfMonth < birth.dayOfMonth)) {
        age--
    }
    return age
}

/** Human-readable reasons [validate] would reject. Empty means the profile can be stored. */
fun Profile.problems(): List<String> {
    val problems = mutableListOf<String>()
    ageYears?.let { age ->
        if (age !in 1..120) problems += "age must be between 1 and 120"
    }
    birthDate?.let { birth ->
        if (birth.year !in 1900..2100) problems += "birth date is out of range"
    }
    heightCm?.let { height ->
        if (height !in 50.0..250.0) problems += "height must be between 50 and 250 cm"
    }
    weightKg?.let { weight ->
        if (weight !in 20.0..350.0) problems += "weight must be between 20 and 350 kg"
    }
    maxHeartRateBpm?.let { maxHr ->
        if (maxHr !in 120..230) problems += "max heart rate must be between 120 and 230 bpm"
    }
    if (customZones.isNotEmpty() && !zonesAreContiguous(customZones)) {
        problems += "custom zones must be five contiguous zones numbered 1 to 5"
    }
    return problems
}

/** [Outcome.Ok] with this profile, or [CoreError.Invalid] listing [problems]. */
fun Profile.validate(): Outcome<Profile> {
    val problems = problems()
    if (problems.isNotEmpty()) return Outcome.Err(CoreError.Invalid(problems.joinToString("; ")))
    return Outcome.Ok(this)
}

private fun zonesAreContiguous(zones: List<HeartRateZone>): Boolean {
    if (zones.size != 5) return false
    val ordered = zones.sortedBy { it.index }
    if (ordered.map { it.index } != (1..5).toList()) return false
    for (zone in ordered) {
        if (zone.fromBpm !in 30..230 || zone.untilBpm !in 30..230) return false
        if (zone.fromBpm >= zone.untilBpm) return false
    }
    for (i in 1 until ordered.size) {
        if (ordered[i].fromBpm != ordered[i - 1].untilBpm) return false
    }
    return true
}

/**
 * The stored profile.
 * [update] rejects a profile that fails [Profile.validate] and leaves the previous value in place.
 */
interface ProfileRepository {
    val profile: StateFlow<Profile>

    suspend fun update(profile: Profile): Outcome<Profile>
}

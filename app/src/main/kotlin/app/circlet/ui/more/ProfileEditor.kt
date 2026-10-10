package app.circlet.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.circlet.core.MassUnit
import app.circlet.core.Profile
import app.circlet.core.Sex
import app.circlet.core.ageOn
import app.circlet.ui.design.Divider
import app.circlet.ui.design.Field
import app.circlet.ui.design.Segmented
import app.circlet.ui.design.label
import java.time.LocalDate
import kotlin.math.roundToInt

private const val POUNDS_PER_KILOGRAM = 2.20462

/** What the user has typed into the profile form, kept as text until it parses. */
class ProfileDraft(age: String, height: String, weight: String, maxHeartRate: String, sex: Sex) {
    var age by mutableStateOf(age)
    var height by mutableStateOf(height)
    var weight by mutableStateOf(weight)
    var maxHeartRate by mutableStateOf(maxHeartRate)
    var sex by mutableStateOf(sex)

    val parsedAge: Int? get() = age.toIntOrNull()?.takeIf { it in 10..100 }
    val parsedHeight: Double? get() = height.toDoubleOrNull()?.takeIf { it in 100.0..230.0 }
    val parsedMax: Int? get() = maxHeartRate.toIntOrNull()?.takeIf { it in 120..230 }

    fun parsedWeightKg(unit: MassUnit): Double? {
        val typed = weight.toDoubleOrNull() ?: return null
        val kilograms = if (unit == MassUnit.POUND) typed / POUNDS_PER_KILOGRAM else typed
        return kilograms.takeIf { it in 30.0..250.0 }
    }

    /**
     * [base] with the form applied, or null while a field does not parse.
     * A birth date is kept while the typed age still agrees with it, so the age keeps advancing.
     */
    fun toProfile(base: Profile, unit: MassUnit, today: LocalDate): Profile? {
        val years = parsedAge ?: return null
        val centimetres = parsedHeight ?: return null
        val kilograms = parsedWeightKg(unit) ?: return null
        if (maxHeartRate.isNotBlank() && parsedMax == null) return null
        val keepBirthDate = base.birthDate != null && base.ageOn(today) == years
        return base.copy(
            birthDate = if (keepBirthDate) base.birthDate else null,
            ageYears = if (keepBirthDate) base.ageYears else years,
            sex = sex,
            heightCm = centimetres,
            weightKg = kilograms,
            maxHeartRateBpm = parsedMax,
        )
    }

    companion object {
        val Saver = listSaver<ProfileDraft, String>(
            save = { listOf(it.age, it.height, it.weight, it.maxHeartRate, it.sex.name) },
            restore = { ProfileDraft(it[0], it[1], it[2], it[3], Sex.valueOf(it[4])) },
        )
    }
}

private fun Double.trimmed(): String = ((this * 10).roundToInt() / 10.0).toString().removeSuffix(".0")

@Composable
fun rememberProfileDraft(profile: Profile, unit: MassUnit, today: LocalDate): ProfileDraft =
    rememberSaveable(unit, saver = ProfileDraft.Saver) {
        ProfileDraft(
            age = profile.ageOn(today)?.toString().orEmpty(),
            height = profile.heightCm?.trimmed().orEmpty(),
            weight = profile.weightKg?.let { if (unit == MassUnit.POUND) it * POUNDS_PER_KILOGRAM else it }?.trimmed().orEmpty(),
            maxHeartRate = profile.maxHeartRateBpm?.toString().orEmpty(),
            sex = profile.sex,
        )
    }

/** The profile rows, to sit inside a card. [onEdit] fires on every change. */
@Composable
fun ProfileFields(draft: ProfileDraft, unit: MassUnit, showMaxHeartRate: Boolean, onEdit: () -> Unit = {}) {
    val pounds = unit == MassUnit.POUND
    val ageWrong = draft.age.isNotEmpty() && draft.parsedAge == null
    Field("AGE", draft.age, "Enter an age from 10 to 100".takeIf { ageWrong }, isError = ageWrong) {
        draft.age = it
        onEdit()
    }
    Divider()
    val heightWrong = draft.height.isNotEmpty() && draft.parsedHeight == null
    Field("HEIGHT (CM)", draft.height, "Enter a height from 100 to 230 cm".takeIf { heightWrong }, isError = heightWrong) {
        draft.height = it
        onEdit()
    }
    Divider()
    val weightWrong = draft.weight.isNotEmpty() && draft.parsedWeightKg(unit) == null
    Field(
        if (pounds) "WEIGHT (LB)" else "WEIGHT (KG)", draft.weight,
        (if (pounds) "Enter a weight from 66 to 550 lb" else "Enter a weight from 30 to 250 kg").takeIf { weightWrong },
        isError = weightWrong,
    ) {
        draft.weight = it
        onEdit()
    }
    if (showMaxHeartRate) {
        Divider()
        val wrong = draft.maxHeartRate.isNotBlank() && draft.parsedMax == null
        Field(
            "MAX HEART RATE", draft.maxHeartRate,
            if (wrong) "Enter 120 to 230, or leave empty" else "Optional. Estimated from age when empty.",
            isError = wrong,
        ) {
            draft.maxHeartRate = it
            onEdit()
        }
    }
    Divider()
    Column(Modifier.padding(vertical = 12.dp)) {
        Text("SEX", style = label())
        Spacer(Modifier.height(10.dp))
        val options = listOf(Sex.FEMALE, Sex.MALE, Sex.UNSPECIFIED)
        Segmented(listOf("FEMALE", "MALE", "NOT SAID"), options.indexOf(draft.sex)) {
            draft.sex = options[it]
            onEdit()
        }
    }
}

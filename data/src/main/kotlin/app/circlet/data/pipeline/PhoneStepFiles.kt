package app.circlet.data.pipeline

import app.circlet.core.StepCsv
import app.circlet.core.StepReading
import java.io.File
import java.time.LocalDate

/** Where the app records the phone's step counter: one [StepCsv] file per local date. */
internal fun phoneStepsDir(filesDir: File): File = File(filesDir, "steps")

/**
 * The readings [DayInput.phoneSteps][app.circlet.core.DayInput.phoneSteps] expects for [date]:
 * the day's own file, led by the day before's last reading so the first rise of the day has a baseline.
 */
internal fun readPhoneSteps(dir: File, date: LocalDate): List<StepReading> {
    val today = readDay(dir, date)
    if (today.isEmpty()) return today
    val baseline = readDay(dir, date.minusDays(1)).maxByOrNull { it.epochMs }
    return listOfNotNull(baseline) + today
}

private fun readDay(dir: File, date: LocalDate): List<StepReading> {
    val file = File(dir, StepCsv.fileName(date))
    if (!file.isFile) return emptyList()
    return try {
        file.useLines { StepCsv.parse(it) }
    } catch (error: java.io.IOException) {
        emptyList()
    }
}

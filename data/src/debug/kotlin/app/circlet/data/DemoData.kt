package app.circlet.data

import app.circlet.core.GravityVector
import app.circlet.core.OpticalChannels
import app.circlet.core.Sample
import app.circlet.core.SampleSink
import java.time.LocalDate
import java.time.ZoneId

/**
 * Debug-only 1 Hz history so the UI can be exercised without a strap.
 * The same [seed], zone, and [endDate] always produce the same samples.
 * Day index 0 is the oldest of the [DAY_COUNT] days. [EMPTY_DAY_INDEX] has no rows.
 */
object DemoData {
    const val DAY_COUNT = 21
    const val EMPTY_DAY_INDEX = 10
    private const val BATCH = 4_000

    fun dayIndex(endDate: LocalDate, date: LocalDate): Int? {
        val start = endDate.minusDays((DAY_COUNT - 1).toLong())
        if (date.isBefore(start) || date.isAfter(endDate)) return null
        return (date.toEpochDay() - start.toEpochDay()).toInt()
    }

    /** Null when this second is outside the window, on the empty day, or in a recording gap. */
    fun sampleOrNull(seed: Long, zone: ZoneId, endDate: LocalDate, epochSecond: Long): Sample? {
        val local = java.time.Instant.ofEpochSecond(epochSecond).atZone(zone)
        val index = dayIndex(endDate, local.toLocalDate()) ?: return null
        if (index == EMPTY_DAY_INDEX) return null
        val hour = local.hour
        val minute = local.minute
        if (hour == 15 && minute < 30) return null
        val kind = kind(index, hour, minute)
        return sample(seed, epochSecond, kind)
    }

    suspend fun seed(sink: SampleSink, seed: Long, zone: ZoneId, endDate: LocalDate) {
        val startDate = endDate.minusDays((DAY_COUNT - 1).toLong())
        var date = startDate
        while (!date.isAfter(endDate)) {
            val batch = ArrayList<Sample>(BATCH)
            val from = date.atStartOfDay(zone).toEpochSecond()
            val until = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
            var epoch = from
            while (epoch < until) {
                val sample = sampleOrNull(seed, zone, endDate, epoch)
                if (sample != null) {
                    batch += sample
                    if (batch.size == BATCH) {
                        sink.commit(batch.toList(), emptyList())
                        batch.clear()
                    }
                }
                epoch++
            }
            if (batch.isNotEmpty()) sink.commit(batch.toList(), emptyList())
            date = date.plusDays(1)
        }
    }

    private enum class Kind { SLEEP, NAP, RUN, WALK, INTERVALS, OFF_WRIST, DAILY }

    private fun kind(index: Int, hour: Int, minute: Int): Kind {
        val minutes = hour * 60 + minute
        if (index == 4 && minutes in 9 * 60 until 17 * 60) return Kind.OFF_WRIST
        if (minutes in 7 * 60 + 5 until 7 * 60 + 25) return Kind.OFF_WRIST
        if ((index % 6 == 1 || index % 6 == 4) && minutes in 13 * 60 + 30 until 14 * 60 + 10) return Kind.NAP
        if (index % 3 == 0 && minutes in 8 * 60 until 8 * 60 + 40) return Kind.RUN
        if (index % 3 == 1 && minutes in 17 * 60 + 10 until 18 * 60) return Kind.WALK
        if (index % 3 == 2 && minutes in 18 * 60 + 40 until 19 * 60 + 20) return Kind.INTERVALS
        val onset = 22 * 60 + 20 + (index % 5) * 12
        if (minutes >= onset || minutes < 7 * 60) return Kind.SLEEP
        return Kind.DAILY
    }

    private fun sample(seed: Long, epoch: Long, kind: Kind): Sample {
        if (kind == Kind.OFF_WRIST) {
            return Sample(epoch, heartRateBpm = 0, rrIntervalsMs = emptyList(), gravity = null, optical = null, rawRecord = null)
        }
        val heart = when (kind) {
            Kind.SLEEP -> 46 + mix(seed, epoch, 10)
            Kind.NAP -> 50 + mix(seed, epoch, 8)
            Kind.RUN -> 148 + mix(seed, epoch, 18)
            Kind.WALK -> 98 + mix(seed, epoch, 14)
            Kind.INTERVALS -> if (((epoch / 60) % 2L) == 0L) 156 else 102
            Kind.DAILY -> 64 + mix(seed, epoch, 18)
            Kind.OFF_WRIST -> 0
        }
        val rr = when (kind) {
            Kind.SLEEP, Kind.NAP -> listOf(860 + mix(seed, epoch, 220), 840 + mix(seed, epoch xor 3, 220))
            Kind.RUN, Kind.INTERVALS -> listOf(360 + mix(seed, epoch, 80))
            else -> if (mix(seed, epoch, 3) == 0) listOf(700 + mix(seed, epoch, 250)) else emptyList()
        }
        val wobble = (mix(seed, epoch, 7) - 3) / 100f
        // Wake and workouts flip arm elevation every second. A still vector all day
        // is one motionless block, and sleep detection then has no night to keep.
        val gravity = when (kind) {
            Kind.SLEEP, Kind.NAP -> GravityVector(0.02f + wobble / 4f, -0.04f, 0.98f)
            else -> if (epoch % 2L == 0L) {
                GravityVector(0.35f, 0.2f + wobble, 0.9f)
            } else {
                GravityVector(0.35f, -0.2f, -0.9f)
            }
        }
        val optical = OpticalChannels(
            green = 40_000 + mix(seed, epoch, 20_000),
            red = 8_000 + mix(seed, epoch xor 1, 4_000),
            infrared = 12_000 + mix(seed, epoch xor 2, 4_000),
            ambient = 80 + mix(seed, epoch xor 4, 40),
        )
        // A slow night-to-night drift with a little jitter, so the relative skin-temperature path has something to score.
        val skinTemp = 900 + mix(seed, epoch / 86_400, 12) + mix(seed, epoch, 3)
        return Sample(epoch, heart, rr, gravity, optical, rawRecord = null).copy(skinTempCount = skinTemp)
    }

    private fun mix(seed: Long, epoch: Long, bound: Int): Int {
        var z = seed xor epoch * -0x61C8864680B583EBL
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = z xor (z ushr 27)
        return ((z and Long.MAX_VALUE) % bound).toInt()
    }
}

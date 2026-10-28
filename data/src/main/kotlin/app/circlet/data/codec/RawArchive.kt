package app.circlet.data.codec

import app.circlet.core.RawRecord
import app.circlet.core.StrapGeneration
import app.circlet.data.db.RawRecordEntity

internal fun RawRecord.toEntity(): RawRecordEntity {
    val key = if (generation == StrapGeneration.GEN4) epochSecond.toString() else {
        val version = bytes.getOrNull(1)?.toInt()?.and(255)
        val counter = if (bytes.size >= 7) (3..6).fold(0L) { acc, i -> acc or ((bytes[i].toLong() and 255) shl ((i - 3) * 8)) } else 0L
        "GEN5:$epochSecond:$version:$counter"
    }
    return RawRecordEntity(epochSecond, bytes, key)
}

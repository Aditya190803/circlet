// Ported from OpenStrap analytics/lib/src/onehz/sleep/accounting.dart.
// 3-class stage label shared across the sleep family; unobserved time is not a member.

package app.circlet.analytics.sleep

/**
 * 3-class label used across the sleep family. There is no unobserved member:
 * unobserved time is carried per-second by [SleepSegmentation.stages4] and in
 * aggregate by [SleepSegmentation.unobservedSec].
 *
 * Not [app.circlet.core.SleepStage].
 */
enum class OsStage { WAKE, NREM, REM }

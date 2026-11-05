package app.circlet.strap.call

import app.circlet.core.CallSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Gap between two pulses while a call rings. */
internal const val CALL_PULSE_GAP_MILLIS = 2_000L

/** Pulses before a call that is still ringing stops buzzing, about 45 seconds. */
internal const val CALL_PULSES = 22

/** The strap haptics a ringing call uses. */
internal interface CallHaptics {
    suspend fun pulse()

    suspend fun stop()
}

/**
 * Buzzes the strap while a call notification rings, for the sources the user switched on.
 * One call is one notification key. A tap on the strap silences every ringing call until its
 * notification goes away; a call that rings out stays silent if its notification is updated.
 * Called on one thread: the strap scope and the notification listener both run on the main thread.
 */
internal class CallBuzz(
    private val scope: CoroutineScope,
    private val enabled: () -> Set<CallSource>,
    private val haptics: CallHaptics,
) {
    private val buzzing = mutableMapOf<String, Job>()
    private val quiet = mutableSetOf<String>()

    fun posted(key: String, source: CallSource?, ringing: Boolean) {
        if (source == null || !ringing || source !in enabled()) {
            removed(key)
            return
        }
        if (key in buzzing || key in quiet) return
        buzzing[key] = scope.launch {
            repeat(CALL_PULSES) {
                haptics.pulse()
                delay(CALL_PULSE_GAP_MILLIS)
            }
            buzzing.remove(key)
            quiet += key
        }
    }

    fun removed(key: String) {
        quiet -= key
        val job = buzzing.remove(key) ?: return
        job.cancel()
        scope.launch { haptics.stop() }
    }

    /** Silences every ringing call. False when nothing was ringing, so the tap keeps its usual action. */
    fun silence(): Boolean {
        if (buzzing.isEmpty()) return false
        buzzing.values.forEach { it.cancel() }
        quiet += buzzing.keys
        buzzing.clear()
        scope.launch { haptics.stop() }
        return true
    }
}

package app.circlet.core

/**
 * The result of a one-shot call across the UI boundary.
 * Failures are values. Callers do not catch exceptions from these interfaces.
 */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: CoreError) : Outcome<Nothing>
}

/** Why a one-shot call did not succeed. */
sealed interface CoreError {
    /** The call needs a paired strap and none is stored. */
    data object NotPaired : CoreError

    /** Another strap or workout operation is already running. */
    data object Busy : CoreError

    /** The arguments failed validation. [detail] is safe to show. */
    data class Invalid(val detail: String) : CoreError

    /** The strap rejected the command or the link failed. */
    data class Strap(val error: StrapError) : CoreError

    /** The database, export file, or backup folder could not be used. */
    data class Storage(val detail: String) : CoreError
}

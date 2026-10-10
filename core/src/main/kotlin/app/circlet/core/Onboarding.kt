package app.circlet.core

import kotlinx.coroutines.flow.StateFlow

/**
 * The checks the onboarding flow and the settings screen need.
 * Each flag is true when that permission, exemption, or step is done.
 */
data class OnboardingChecks(
    val bluetoothScan: Boolean,
    val bluetoothConnect: Boolean,
    val notifications: Boolean,
    val batteryOptimisationExempt: Boolean,
    val companionDeviceAssociated: Boolean,
    val profileSaved: Boolean,
    val strapPaired: Boolean,
    val firstSyncFinished: Boolean,
)

/**
 * Whether onboarding has been finished, and the live permission checks.
 * [setCompleted] records the user's progress. It does not grant permissions.
 */
interface OnboardingState {
    val completed: StateFlow<Boolean>
    val checks: StateFlow<OnboardingChecks>

    suspend fun setCompleted(completed: Boolean): Outcome<Unit>
}

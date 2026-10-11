package app.circlet

import android.content.Context
import app.circlet.core.AlarmController
import app.circlet.core.DataPortability
import app.circlet.core.JournalRepository
import app.circlet.core.MetricsRepository
import app.circlet.core.OnboardingState
import app.circlet.core.ProfileRepository
import app.circlet.core.SettingsRepository
import app.circlet.core.StrapController
import app.circlet.core.SystemTimeSource
import app.circlet.core.TimeSource
import app.circlet.core.WorkoutController
import app.circlet.core.fake.FakeCirclet
import app.circlet.core.fake.FakeScenario

/**
 * The one place the app's dependencies are bound.
 * View models take these core interfaces and nothing else, so swapping the backend
 * means changing [bind] and no screen.
 */
class AppContainer(
    val time: TimeSource,
    val metrics: MetricsRepository,
    val strap: StrapController,
    val alarms: AlarmController,
    val workouts: WorkoutController,
    val journal: JournalRepository,
    val profile: ProfileRepository,
    val settings: SettingsRepository,
    val portability: DataPortability,
    val onboarding: OnboardingState,
) {
    companion object {
        @Volatile
        private var instance: AppContainer? = null

        /** The demo the fakes start in. Read by [bind] when the process is on the fake backend. */
        @Volatile
        var demo: FakeScenario = FakeScenario.PAIRED

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) { instance ?: bind(context.applicationContext).also { instance = it } }

        /**
         * Real storage and the strap, unless this is a core-only build or the debug
         * switch `circlet_debug` / `prefer_fake` is set. That switch defaults to off.
         */
        private fun bind(context: Context): AppContainer {
            if (!AppBootstrap.bindsInApplication || BackendSwitch.preferFake(context)) {
                return of(FakeCirclet(SystemTimeSource, demo), SystemTimeSource)
            }
            return AppBootstrap.openReal(context)
        }

        /** A container over one [FakeCirclet], which implements every core interface. */
        fun of(fake: FakeCirclet, time: TimeSource): AppContainer = AppContainer(
            time = time,
            metrics = fake,
            strap = fake,
            alarms = fake,
            workouts = fake,
            journal = fake,
            profile = fake,
            settings = fake,
            portability = fake,
            onboarding = fake,
        )
    }
}

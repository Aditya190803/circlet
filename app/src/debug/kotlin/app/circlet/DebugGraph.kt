package app.circlet

import app.circlet.core.SystemTimeSource
import app.circlet.core.TimeSource
import app.circlet.core.fake.FakeCirclet
import app.circlet.core.fake.FakeScenario

/** Debug-only handle. The UI wires a [FakeCirclet] here until the real backend exists. */
fun demoBackend(
    time: TimeSource = SystemTimeSource,
    scenario: FakeScenario = FakeScenario.PAIRED,
): FakeCirclet = FakeCirclet(time, scenario)

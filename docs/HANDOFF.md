## Strap

`StrapModule.open(context, ports, time)` in `app.circlet.strap` returns `StrapBindings` (`strap`, `alarms`, `workouts`). Call it from `Application.onCreate` and put those three controllers on `AppContainer`. The service, boot receiver, and watchdog can start the process before `open`; the Bluetooth link exists only after `open`.

Implement the `app.circlet.core` ports, not the parallel types in `app.circlet.data`:

- `SampleSink.commit` returns only after the batch is durable. Upsert samples on `epochSecond`. A lost acknowledgement makes the strap send the same batch again. `RawRecord` is `app.circlet.core.RawRecord`.
- `StrapEventSink.record`.
- `StrapPrefs` for the paired address, name, last sync, and the weekly alarm list.
- `WorkoutStore` for a finished activity.
- The app supplies `SleepStageEstimate` (`UNKNOWN` until analytics has a stage), `DoubleTapPolicy`, and `LiveStrainEstimator` (`NONE` leaves strain and calories absent).

`StrapCompanion.attach(activity)` before the user pairs, so the association dialog has an activity. Forward the result to `StrapCompanion.handleActivityResult`. The request code is `StrapCompanion.REQUEST_CODE` (`0x431E`). `StrapCompanion.isAssociated` is the onboarding companion flag. `StrapAccess` lists the runtime permissions and builds the battery-optimisation exemption intent.

Manifest components, all in this module: `app.circlet.strap.bg.StrapService` (foreground service type `connectedDevice`), `StrapPresenceService` (companion device presence), `StrapBootReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `QUICKBOOT_POWERON`), and `StrapWatchdog` (WorkManager, unique name `circlet_strap_watchdog`, every 15 minutes). No `INTERNET` permission. The paired flag the worker reads is SharedPreferences `circlet_strap`, written on pair and cleared on forget.

A double tap is stored as `StrapEvent.DoubleTap`. `DoubleTapAction.STOP_ALARM` also sends stop-haptics.

## data

`DataModule.open(context, dayEngine, insightEngine, time)` in `app.circlet.data` returns `CircletData`. Call it from `Application.onCreate`, before WorkManager can run. `CircletBackupWorker` (unique work `circlet-backup`) retries while that open has not run.

Pass `CircletData.ports` into `StrapModule.open`. Those ports are the on-disk `StrapPorts`: `SampleSink`, `StrapEventSink`, `StrapPrefs`, `WorkoutStore`, `StoredSleepStage`, `SettingsDoubleTap`, and `ProfileLiveLoad`. Put the strap module's `WorkoutController` and `AlarmController` on `AppContainer`. `CircletData.workouts` and `CircletData.alarms` only persist rows. They do not arm the strap or stream heart rate.

Health Connect permission UI belongs in `:app`. The write permissions are the seven `android.permission.health.WRITE_*` entries declared by this module. Request `CircletData.healthWritePermissions()`. Android 14 also needs an activity-alias for `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` and `android.intent.action.VIEW_PERMISSION_USAGE`. This module has no Activity, so it cannot show the sheet.

`exportDatabase` writes a `file:` URI under the cache directory (`circlet-*.db`). Sharing that file needs a FileProvider in `:app`.

`DemoData.seed` is debug-only. It writes 21 days of 1 Hz samples through `SampleSink`.

## ui requests (for the integration and features agents)

- `AlarmController` has no way to delete an alarm, so the alarms screen cannot offer it. Add a `remove(id)` (add-only) with storage and strap re-arm, and tell the UI.
- `DayStress` has no minutes per band (low / medium / high). The stress screen derives them from the timeline as a stand-in. Expose them from analytics.
- The trends package maps `MetricKind` to `DaySummary` fields itself (`app/.../ui/trends/MetricReading.kt`) because that mapping is private to `FakeCirclet`. Move one public mapping into `:core` (for example `DaySummary.reading(kind): Metric<Double>`), so the fake, the insight engine and the UI share it.
- The month calendar has no per-day absent reason; blank days show no explanation. Low priority.

## features

Skin temperature and reference ranges. Both are computed by the day pipeline and stored on `DaySummary` (`skinTemperature`, `references`); nothing here is wired into the engine yet.

- Nightly settled mean: `app.circlet.analytics.temperature.nightlySkinTemp(counts: List<Int?>, minSamples: Int = 60, minSettledFraction: Double = 0.80, settleBandLow: Double = 40.0): Metric<SettledSkinTemp>`. Input is the target night's sleep-window `Sample.skinTempCount` values in order (null where the layout did not carry the word; non-positive counts are ignored). Output `SettledSkinTemp(meanCounts, settledFraction)` is ADC counts, never degrees. Absent (`NOT_ENOUGH_DATA`) when the window has under 60 valid samples or the settled fraction is under 0.80; drop the night's temperature then, do not substitute.
- Night score: `app.circlet.analytics.temperature.scoreSkinTemp(nights: List<NightlyTemp>, baselineDays: Int = 21, zThresh: Double = 2.0, persistDays: Int = 2, minBaseline: Int = 7, luteal: List<Boolean>? = null): SkinTemperature`. Input is trailing settled means (`NightlyTemp(date, meanCounts)`, null when the night has none) in ascending date order with the target night last; rebuild the means from stored samples with `nightlySkinTemp`, going back `baselineDays` calendar days. Pass `luteal = null` (Circlet tracks no cycle). Output goes straight onto `DaySummary.skinTemperature`: deviation is tonight's robust z in SDs of the user's own nights, flag is `ELEVATED` only after 2 consecutive nights at z >= 2.0, else `NORMAL`, or `NOT_ENOUGH_BASELINE` until 7 prior nights exist. Constants `SKIN_TEMP_*` in the same package name the gates.
- Reference list: `app.circlet.analytics.reference.references(profile: Profile, date: LocalDate, history: List<DaySummary>, windowDays: Int = 28, minDays: Int = 7): List<MetricReference>`. Input is the stored profile, the target date, and prior day summaries (only days before `date` and within the window are used). Output goes straight onto `DaySummary.references`: ten entries (HRV, resting heart rate, respiratory rate, sleep duration, sleep efficiency, VO2max, strain, recovery, stress, skin temperature) with personal bands (null until 7 valid days) and published ideals with citations where they exist (null for strain, recovery, stress). No profile or extra input is needed beyond what is listed.

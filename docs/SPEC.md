# Circlet specification

## 1. Product

A local companion for the WHOOP 4.0. The user owns the strap and wants the familiar
experience (three dials for Sleep, Recovery and Strain, the same vocabulary, a dark premium
UI) without WHOOP's cloud. Everything is computed and stored on the phone. The prototype in
`..\companion` shows the approved look; this repository is the production rebuild.

## 2. Features

### Strap link
- Scan (service-filtered), pair with bonding, reconnect automatically, survive Bluetooth
  toggles, reboots and app updates.
- Session start: set the clock, read hello (identity, firmware, battery, wrist), battery,
  data range, alarm time.
- History drain with the batch acknowledge loop; resumable; commit before acknowledge.
- Live heart rate while the app is in the foreground or a workout is being recorded.
- Battery, charging, wrist on/off, double tap, and high-frequency-sync prompt events.
- Background: foreground service (`connectedDevice`), CompanionDeviceManager association with
  device-presence re-attach, a 15 minute WorkManager watchdog, battery-optimisation exemption.

### Strap features
- Haptic wake alarm stored on the strap (fires with no phone nearby), weekly repeating
  schedule, smart wake window that fires early when light sleep is seen while connected.
- Buzz test, find-my-strap buzz, configurable double-tap action (stop alarm, mark a moment).
- Heart rate broadcast toggle (standard heart rate profile for gym equipment).
- Rename the strap's advertised name.

### Metrics (all local, each with value, confidence tier and a reason when absent)
- Sleep: main sleep and naps from motion (van Hees) fused with heart rate; stages (awake,
  light, deep, REM) with cycles; time in bed, efficiency, latency, wake events; sleep need,
  sleep debt, sleep performance, consistency (sleep regularity index).
- Heart: resting heart rate, nightly HRV (RMSSD, plus SDNN and frequency domain for the
  detail screen), respiratory rate from beat intervals, HRV spot check.
- Recovery (readiness): from ln-RMSSD against the personal baseline, resting heart rate,
  sleep, and respiratory rate deviation. Baseline calibration status is exposed.
- Strain: TRIMP against the user's own heart-rate reserve mapped to 0-21; day strain and
  per-activity strain; zone minutes; calories; strain target for today given recovery.
- Stress: all-day score from heart rate and HRV with a timeline.
- Workouts: auto-detected with a sport guess, manual start and stop with live screen data,
  edit, delete, merge; heart-rate recovery after a workout; observed max heart rate.
- Steps (estimated from 1 Hz motion; labelled as an estimate), VO2max estimate.
- Wellness flags: illness/overreaching signals from baseline deviations.
- Not offered because the strap does not give usable data: absolute skin temperature and
  SpO2 percentage. Do not surface them.

### History and insight
- Day view for any date; week and month trends for every metric; month grid calendar.
- Weekly recap; personal records; journal (daily tags and notes) with correlations against
  recovery, HRV and sleep ("what moves your numbers").
- Sleep coach: recommended bedtime for tonight's need.

### Data ownership
- Health Connect export: sleep stages, resting heart rate, HRV, respiratory rate, active
  energy, workouts, steps. Measured values only, idempotent.
- Export the whole database to a file; scheduled local backup to a user-chosen folder; restore.
- Home-screen widgets: the three dials; strap battery.

### App
- Onboarding (welcome, profile, permissions, pairing, first sync), designed empty states for
  every screen, settings, about and licences.
- Units (kg/lb), 12/24 hour clock follow system or user choice.

## 3. Protocol facts (Gen 4)

Verified by OpenStrap on real bands and consistent with the decompiled official app. The
authoritative references are `..\ref\research\PROTOCOL.md` and `..\ref\protocol\lib\src\*`.

- Frame: `AA | u16 LE size | crc8(size, poly 0x07) | inner padded to 4 | u32 LE crc32(inner)`,
  `size = len(padded inner) + 4`. Reassembly is length based; payloads contain `AA` bytes.
- Inner: `[type][seq][opcode | event id | record type][body]`.
- Types: 0x23 command, 0x24 response, 0x28 realtime, 0x2B realtime raw, 0x2F historical,
  0x30 event, 0x31 metadata, 0x32 console logs.
- Characteristics on service `61080001-8d6d-82b8-614a-1c8cb0f8dcc6`: `…0002` write,
  `…0003` command responses, `…0004` events, `…0005` data. All notify characteristics need
  their own reassembler.
- Response: `[0x24][seq][cmd][origin seq][result][data…]`, result 1 = success.
- The clock ships unset: always `SET_CLOCK` (0x0A, `[u32 epoch][u32 0]`) before draining.
- Drain: `SEND_HISTORICAL_DATA` (0x16). Metadata sub-type at `inner[2]`: 1 start, 2 end,
  3 complete. On end, commit the batch, then send `HISTORICAL_DATA_RESULT` (0x17) with
  `[0x01] + inner[13:21]` using a write with response. Never acknowledge complete.
- 1 Hz record (type 24, about 96 bytes): counter `[3:7]`, epoch `[7:11]`, sub-seconds
  `[11:13]`, heart rate `[17]` (0 = off wrist), rr count `[18]`, rr `[19…]` u16 ms, green PPG
  `[29]`, gravity vector 3 x f32 `[36:48]`, red `[64]`, IR `[66]`, ambient `[70]`. Older
  layouts and the plausibility gate are handled as in OpenStrap's `FirmwareAwareR24Decoder`.
  Store the raw record bytes so a better decoder can re-read them later.
- Battery: `GET_BATTERY_LEVEL` (0x1A) response `inner[5:7]` u16 / 10. The standard battery
  service is wrong; ignore it.
- Hello offsets drift across firmware: parse by content as OpenStrap's `parse_hello` does.
- Alarm: `SET_ALARM_TIME` (0x42) in the rev-1 form for Gen 4, `GET_ALARM_TIME` (0x43),
  `RUN_ALARM` (0x44), `DISABLE_ALARM` (0x45), haptics `RUN_HAPTICS_PATTERN` (0x4F),
  `STOP_HAPTICS` (0x7A). Follow `..\ref\protocol\lib\src\commands.dart` exactly.
- `..\ref\protocol\decode_parity_cases.json` holds 2934 real captured cases with expected
  output. `:protocol` must pass every Gen 4 case in it.

## 4. Architecture

- `:core` holds immutable domain models and repository/use-case interfaces. No Android.
- `:protocol` and `:analytics` are pure functions over bytes and samples. No I/O, no clocks:
  time and time zone are parameters.
- `:data` implements storage with Room. Raw 1 Hz samples are the source of truth. Derived
  results are stored per day with an algorithm version so an update can recompute without
  silently overwriting; recomputation is incremental and off the main thread.
- `:strap` owns the single BLE connection through one serialised operation queue and exposes
  state as `StateFlow`. Its foreground service keeps the process alive.
- `:app` wires everything in one `AppContainer` (manual dependency injection), and the UI
  reads only `:core` interfaces through view models. The UI never touches Room or BLE types.
- Coroutines and Flow throughout. No blocking calls on the main thread.
- Privacy: no `INTERNET` permission. Nothing leaves the phone except through explicit export.

## 5. Contract between backend and UI

The scaffold task turns this into Kotlin in `:core` (`app.circlet.core`). The UI is built
against these and nothing else, so backend tasks must keep them stable and extend them only
by adding members.

- `Metric<T>`: `value: T?`, `confidence: Confidence`, `absentReason: AbsentReason?`.
- `DaySummary` for a local date: sleep, naps, recovery, strain, stress, heart (resting heart
  rate, HRV, respiratory rate), steps, calories, activities, per-minute heart rate, zone
  minutes, sleep need and target strain, calibration status.
- `MetricsRepository`: `observeDay(date): Flow<DaySummary>`, `observeRange(from, to)`,
  `trend(metric, range)`, `records()`, `weeklyRecap(weekStart)`.
- `StrapController`: `state: StateFlow<StrapState>` (link, name, battery, charging, wrist,
  live heart rate, sync progress, last sync, firmware, error), scan results, `pair`, `forget`,
  `syncNow`, `setLiveHeartRate`, `buzz`, `setBroadcast`, `rename`.
- `AlarmController`: alarms list, `set`, `disable`, smart-wake window, next alarm.
- `WorkoutController`: `start(sport)`, `stop()`, live session state, `edit`, `delete`, `merge`.
- `JournalRepository`: tags, entries per day, correlations.
- `ProfileRepository` and `SettingsRepository`: profile, units, zones, double-tap action.
- `DataPortability`: export database, backup schedule and folder, restore, Health Connect
  status and toggle.
- `Onboarding`: completion flag and the permission/exemption checks the flow needs.

## 6. Quality bar

- `gradlew.bat test` and `gradlew.bat :app:assembleDebug` pass from a clean checkout.
- Pure modules are covered by unit tests on real fixtures where they exist (protocol parity
  cases; the test vectors in `..\ref\analytics\test`).
- A demo data source (debug builds only) can fill the database with realistic samples so
  every screen can be exercised in an emulator without a strap.

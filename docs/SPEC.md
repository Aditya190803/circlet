# Circlet specification

## 1. Product

A local companion for WHOOP 4.0 (Gen 4) and WHOOP 5.0 / MG (Gen 5). The user owns the strap and wants the familiar
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

### Gen 5 (WHOOP 5.0 / MG)

Sources: OpenStrap `protocol/lib/src/{band,crc,commands,control,gen5_records}.dart`,
`edge/docs/isgen5-inventory.md`, `edge/lib/ble/{ble_engine,ble_state}.dart` and
`edge/lib/ble/adapters/_registry.dart`. The inventory's line numbers have drifted;
the current source's HELLO timestamp supplies the bootstrap clock reading.

- Service `fd4b0001-cce1-4033-93ce-002d5875f58a`; same suffix with `0002` write,
  `0003` command responses, `0004` events and `0005` history. Scan both generations;
  discovered service selects the profile, overriding scan or saved hints. Persist it with pairing.
- Envelope: `[AA][01][u16 declared LE][00 01 outbound][u16 CRC16-Modbus LE]`,
  then inner padded to four bytes and IEEE CRC-32 LE. Declared length includes the
  padded inner and CRC-32. CRC16 covers header bytes 0..5 (init FFFF, polynomial A001).
  Real inbound headers carry `01 00`; direction bytes never gate frame acceptance.
  Each notification characteristic retains its own length-based reassembler.
- After discovery, pause 600 ms before notification registration and 500 ms after it.
  Send and await HELLO `0x91`, body `01`, before clock work. Successful bodies need
  104 bytes; revision is at body[0], battery u32 tenths at [1], charging bit 0 at [5],
  clock u32 seconds at [6], Q15 subseconds u32 at [10], serial [14:25], firmware
  bytes [91:94] plus u32 build suffix [94], wrist flag [102]. A missing/failed HELLO
  or invalid identity fails setup. Replies correlate opcode and originating sequence.
  Gen 5 can emit two HELLO responses; a duplicate never completes the next command.
  Official-app cross-check (`uj0/v.java`): revision-3 bodies of at least 111 bytes
  report account lock in bit 0x40 of body[103], with a u32 seconds counter at [107].
  Body[102] is wrist status. A locked strap fails setup with a clear account-lock
  error and stops automatic reconnects; no certificate/unlock commands 0x9B–0x9F
  are sent. Older/truncated layouts do not invent lock status.
  The official parser identifies body[87:91] as PCBA id, while OpenStrap calls
  it opticalDiscriminator and labels [83] PCBA. The supplied decompiler findings
  label 0–37 Maverick/WHOOP 5.0 and 48–85 Goose/MG; OpenStrap's HELLO tests label
  discriminator 0 MG and 82 ordinary WHOOP 5.0. Until hardware resolves those
  conflicting product names, the UI uses the combined WHOOP 5.0 / MG label.
- Revision-1 HELLO timestamps within 2000 ms of freshly sampled phone time skip
  SET_CLOCK. Unknown HELLO revisions or larger drift require an awaited SET_CLOCK
  `0x0A`, `[u32 seconds][u32 Q15 fraction]`. Unanswered/rejected corrections stop setup.
  No redundant GET_CLOCK is sent after a usable HELLO. If decoding a clock reply,
  opcode `0x0B` seconds start at body[0]; `0x93` has revision 1 then seconds at body[1].
  Offset choice follows the received opcode, never the connected generation.
- GET_CUSTOM_ADVERTISING_NAME `0x8D`, body `01`, is awaited before INIT, but a missing
  reply is advisory. SET_CUSTOM_ADVERTISING_NAME `0x8C` shares Gen 4's rename body.
  Gen 5 INIT is GET_DATA_RANGE `0x22`, pause 120 ms, SEND_HISTORICAL_DATA `0x16`;
  both have empty bodies. Do not replay Gen 4's five-packet INIT.
- Live/workout HR uses the shared `0x03` toggle. Do not send R10/R11 `0x3F` or use
  ENABLE_OPTICAL_DATA `0x6B` as a live toggle (it persists optical history on Gen 5).
- Historical packet `0x2F` uses Gen 5 layouts, chiefly v18. The common header is
  version [1], flags [2], u32 counter [3:7], epoch [7:11], Q15 fraction [11:13].
  v18 needs at least 109 readable inner bytes (normally 112): HR [14], RR count [15]
  and up to four signed u16-sized RR slots at [16:24], dynamic acceleration f32 [33],
  mean acceleration f32 axes [37,41,45] (finite, per-axis ±16 g), signed skin centi-°C
  i16 [65:67], coarse sleep state bits 4–5 of [73] (0 wake, 1 still, 2 sleep, 3 up).
  Invalid HR or acceleration removes that field only; RR uses 200..2500 ms.
  Skin sentinel -5000 is absent. No optical ADC or SpO2 metric is invented from v18.
  v20/v21 deep buffers, v22 research and v26 waveforms stay raw; v26 contains no HR/RR.
  Raw archive keys distinguish Gen 5 version/counter packets sharing a second.
- Gen 5 burst validation requires START and counts types `0x2F`, `0x30`, `0x32`,
  `0x35`, `0x36`, `0x37` once each, including undecoded historical records. Metadata
  does not count. END's expected u32 count is at inner[9:13]. Require expected <=
  received; after three failures allow two missing packets. Surplus passes. Attempts
  1–14 return `HISTORICAL_DATA_RESULT` body `00 00`; attempt 15 aborts. Freeze the
  tally at END. A valid batch is durable before its success `01 + token` ACK.
  Gen 4 counts remain advisory. COMPLETE is never acknowledged.
- Before arming an alarm, send awaited SET_CLOCK then settle 120 ms. SET_ALARM
  `0x42` uses 21 bytes: `[04][slot=01][u32 epoch][u16 fraction]`, twelve haptic bytes
  `2F 98 00 00 00 00 00 00 00 00 07 1E`, then crescendo 0. GET_ALARM body is
  `04 01`; DISABLE_ALARM body is `02 FF` (all slots). Immediate alarm/find/test buzz
  uses Maverick opcode `0x13`, body `01 2F 98 00 00 00 00 00 00 00 00 01`.
  Do not preface Gen 5 buzz with STOP_HAPTICS.
  The official app's RUN_ALARM `0x44` body `02 + alarmId` exists, but OpenStrap
  reports it did not buzz on tested Gen 5 hardware; use its verified `0x13` path.
- Stamp the measuring generation on samples. Analytics HR ceilings are Gen 4 230,
  Gen 5 210 bpm. Gen 4 ADC temperature counts and Gen 5 signed centi-°C use separate
  fields; units never share temperature baselines. OpenStrap has no measured Gen 5
  settling band, so nightly temperature metrics abstain pending calibration.
  Gen 5 coarse sleep state only corroborates an existing sleep window; it never
  creates a night or supplies sleep stages.
- Never enable Gen 5 deep buffers/SET_FF_VALUE or persistent R20/R21, firmware,
  certificates, DFU, lock, power-cycle or reboot. Puffin battery-pack follow-up is
  outside scope. Protocol fixtures do not replace real Circlet/strap validation.

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

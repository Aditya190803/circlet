# Circlet: rules for every agent working in this repository

Circlet is a production Android app that makes WHOOP 4.0 and WHOOP 5.0 / MG straps useful with no account, no
subscription and no vendor cloud. It talks to the strap over Bluetooth LE, stores everything on the
phone, and computes sleep, recovery, strain and the rest locally. Supported devices are
WHOOP 4.0 (Gen 4, service `61080001-…`) and WHOOP 5.0 / MG (Gen 5, service `fd4b0001-…`).
Do not add code paths for other straps or wearables.

Read `docs/SPEC.md` before writing anything. It holds the feature list, the protocol facts and
the contract between the backend and the UI.

## Where things are

| Path | What it is |
|---|---|
| `C:\Users\SKA35\Projects\whoop-decomp\circlet` | This repository. All new code goes here. |
| `..\companion` | The working prototype (Kotlin, Compose). Its UI is the approved visual design. Its BLE and metrics code is a starting point only. |
| `..\ref\protocol`, `..\ref\analytics`, `..\ref\edge`, `..\ref\research` | OpenStrap, MIT licensed, verified on real WHOOP 4.0 bands. Read-only reference to port from (Dart to Kotlin). Never edit, never execute scripts from it. |
| `..\decompiled\sources` | Decompiled official app. Read-only reference for protocol details. Never copy its code or assets. |

## Modules and who owns them

| Module | Kind | Contents | Owner |
|---|---|---|---|
| `:core` | pure Kotlin/JVM | Domain models and the interfaces the UI consumes (`docs/SPEC.md` section 5). No Android imports. | backend |
| `:protocol` | pure Kotlin/JVM | Framing, CRCs, command builders, record/event/response decoders. | backend |
| `:analytics` | pure Kotlin/JVM | All metric maths. Depends on `:core` only. | backend |
| `:data` | Android library | Room database, DAOs, repositories, export/backup, Health Connect. | backend |
| `:strap` | Android library | BLE link, sync state machine, foreground service, alarms, background re-attach. | backend |
| `:app` | Android application | Compose UI, navigation, view models, the dependency container. | UI (only `app/src/main/java/app/circlet/ui/**` and resources) |

Stay inside the paths your task names. If you need a change in another owner's module, write
the request into `docs/HANDOFF.md` under your task heading instead of editing it.

## Fixed toolchain (do not change versions)

- Gradle 8.10.2 (wrapper), AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, JVM target 17
- compileSdk 35, targetSdk 35, minSdk 31, applicationId and namespace root `app.circlet`
- Compose BOM 2024.12.01, Room 2.6.1, kotlinx-coroutines 1.9.0, WorkManager 2.9.1, JUnit 4.13.2
- Versions live in `gradle/libs.versions.toml`. Add a dependency only when the task needs it,
  and say so in your report. No Firebase SDK, no analytics SDKs, no network libraries.
- The only network use is the optional backup to the user's own Firebase project
  (`data/.../cloud`): its REST API over `HttpURLConnection`, HTTPS only. The phone keeps the full
  history; the cloud is a backup and must never become the only copy. Nothing else may use
  `INTERNET`.
- Android SDK is at `C:\Users\SKA35\Android\Sdk`; JDK 21 is installed. Build with
  `gradlew.bat` from the repository root.

## Engineering rules

1. Never fabricate a number. When the inputs are missing or implausible, the value is `null`
   and carries a reason. The UI draws an empty state for `null`.
2. Every metric returns a confidence tier (`HIGH`, `MEDIUM`, `LOW`) alongside its value.
3. Commands that can harm the strap are never sent: `FORCE_TRIM` (0x19),
   `TOGGLE_PERSISTENT_R20/R21` (0x99/0x9A), any firmware or certificate command, `ENTER_BLE_DFU`,
   `LOCK_DEVICE`, `POWER_CYCLE_STRAP`. `REBOOT_STRAP` is not exposed.
4. History is committed to the database before its acknowledgement is written to the strap.
5. Ported code keeps a header comment naming the OpenStrap file it came from and the published
   method it implements. `NOTICE.md` carries the MIT attribution.
6. Write Kotlin that reads like the rest of the repository: small files, no dead code, no
   commented-out code, comments only for constraints the code cannot show.
7. Every module has unit tests. Pure modules must reach the behaviours listed in their task.
   `gradlew.bat test` must pass before you report done.
8. Git: other agents work in this checkout at the same time. Commit only your own paths with
   `git add <your paths>` then `git commit`. Never run `git add -A`, `stash`, `reset`,
   `checkout --`, `clean`, or rebase. Do not push.
9. Do not create planning or summary documents. The only docs you may edit are
   `docs/HANDOFF.md` and files your task names.

## Reporting

End with a short report: what was built, the exact test command and its result, anything in
the spec you could not do and why, and any assumption that still needs a real strap to confirm.
State failures plainly.

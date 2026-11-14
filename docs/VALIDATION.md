# Validation record — 13 November 2026

## Responsive prompts — 14 November 2026

The user reported a 20–30 second fifth-gear prompt delay while above 50 mph in fourth. Read saved preferences: Beginner, 15-second minimum gap, voice enabled. Code had independent 15-second coaching and voice cooldowns and required 51 mph for the nominal 50 mph upshift guide. These were verified possible delay sources; the complete driving sequence was not logged, so load/trend/uncertainty contributions cannot be reconstructed.

Changed Beginner candidate dwell to 1.5 seconds and Intermediate to 3 seconds. The new `promptGapSeconds` preference defaults to 3 seconds (6 effective seconds in Intermediate) rather than inheriting the legacy 15-second default. Settings exposes the gap with a 1–60 second range. Coach is now the sole change-timing gate: voice deduplicates approved messages and enforces enabled/mode rules without starting another cooldown. Upshifts begin at the configured guide, with reverse downshift eligibility still 1 mph lower. Calibration, load, predicted-rev, continuity, uncertainty and read-only protections remain intact.

Local `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` passed: **58 JVM tests**, **0 failures**, lint **0 errors / 9 warnings**. New regressions check 81 km/h (about 50.3 mph) in calibrated fourth, fifth available after two seconds of suitable simulated samples, immediate matching speech after approval, no stale 15-second wait after an earlier fourth prompt, mode-dependent timing, continued repeat suppression and high-load suppression. These are controlled algorithm timings, not a guarantee of a prompt whenever actual road speed crosses 50 mph.

The user initially confirmed still driving, so preparation/build/tests were laptop-only and no update was installed then. After explicit confirmation of being safely parked, both updated APKs were installed over USB, ending the old live session. The app still requires deliberate live reconnection after installation.

Ran only `SimulationTest` with physical-reader diagnostics disabled: **OK (8 tests)** in **32.121 seconds**. The suite restores original calibration, endpoint and personal settings while saving the new responsive timing default. Actual road behaviour and audible latency of this timing update still need an instructor/passenger assessment.

## Display follow-up — 14 November 2026

Replaced the dashboard's Unknown selected-gear text with an em dash, and updated the calibration explanation and existing clutch-uncertainty UI test. Estimation/uncertainty rules and logs are unchanged. Build and Android lint succeeded; the updated APK was installed over USB. Ran only `SimulationTest#scenariosAndManualControlsAreAvailable`: **OK (1 test)** in 1.767 seconds, verifying the uncertain simulated gear displays a dash. No physical reader diagnostic was activated. Saved calibration remains vehicle-measured; first through fifth were present when inspected. Factory ratios for the exact UK vehicle/gearbox/tyre combination have not been verified and were not substituted.

## Specific suggested-gear coaching — 14 November 2026

Implemented the user's selected **Suggested gear focus** preview as native Compose: large suggested gear and specific advisory message, a separate estimated current gear, speed/RPM, engine load, coaching-level chips and voice toggle. A recommendation and an estimate are independent values; uncertainty displays a dash. The actual Pixel screenshot shows simulated **26 mph / 3,264 RPM**, estimated **2**, suggested **3** and **Consider third gear**. RPM differs from the earlier preview because the implemented simulator consistently uses its educational gear ratios, never the user's live calibration.

Replaced the earlier generic coaching implementation with `core/GearCoaching.kt`: typed decisions contain target, advice, displayed message and explanation. `AudioCoach` consumes that same decision, with exact matching text, duplicate suppression, mode-dependent intervals and offline English TTS (UK preferred). Independent produces no routine target/speech; Intermediate waits five seconds and doubles intervals; Beginner waits three seconds. Valid suggestions remain visible while supported rather than disappearing after four seconds. Speech stops on uncertainty, completed changes, disabled audio or Independent mode. Actual audible voice playback/voice installation has not been independently verified; pure voice policy tests and Android integration were verified.

Saved five-speed teaching boundaries default to **10 / 25 / 35 / 50 mph**, with a **2,000–3,000 RPM** usual band. They are editable local instructor guidance rather than verified manufacturer shift points. Current and target calibration, load, speed trend, a one-mph guide dead band, predicted target RPM, fresh synchronised data, dwell and minimum intervals constrain suggestions. Changes are adjacent only and never suggest sixth. First-gear advice remains conditional on slow progress and known/recent second, including a four-second maximum history when the current gear is not knowable. Stationary preparation does not claim an engaged gear. High load, incompatible ratios, missing sensors, predicted lugging/over-rev, clutch uncertainty and rapid speed changes can withhold a target. Suggested gear is appended to lesson-log rows; older rows remain readable.

Read-only transport/allowlist were unchanged. No additional PIDs or live-hardware diagnostic tests were enabled. Existing `ratio1` through `ratio5`, reader endpoint, vehicle name, audio enabled choice and volume were read back and confirmed unchanged after installation/tests. New keys `shiftMin=2000`, `shiftMax=3000`, `speedGuide1..4=10/25/35/50` are saved locally; the obsolete `up` preference is not reinterpreted as a new band. Five capture controls are shown; sixth is excluded from live estimation and recommendations. Voice/level changes preserve the current estimate; changing calibration resets it.

Validation:

- `:core:test`: **55 passed / 0 failures** — 17 estimation/calibration tests, 22 specific-coaching/voice-policy tests, 16 protocol/safety tests. The previous generic coaching tests were replaced by tests of the new decision engine, retaining the meaningful safety/uncertainty scenarios. One initial boundary test incorrectly expected a blank where maintaining the current gear is valid; corrected it to verify no upshift within the dead band, then reran successfully.
- `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: **BUILD SUCCESSFUL**, lint **0 errors / 9 warnings**.
- Both APK installs over authorised USB ADB returned **Success**. Explicitly ran only `SimulationTest`, without physical-reader flags: **OK (8 tests)** in **28.854 seconds**. Tests cover launch/pause, clutch uncertainty, settings/profile persistence, calibration retention, deliberate live activation/background rules, first-gear conditional advice, five-gear capture controls, specific third-gear display, Independent suppression and high-load suppression. Tests silence speech and restore the user's settings with a synchronous disk flush.
- First device run had seven passes and one activity-destroyed error before test setup, with no AndroidRuntime crash logged. Waited for idle before obtaining the test activity, reran the full suite with the latest settings-update behaviour, and all eight passed.
- Captured and visually inspected the Pixel's specific-third-gear dashboard during the UI test. Suggested/current values, labels, source status, coaching chips and voice toggle rendered correctly.

Road validation of the new teaching-profile recommendations remains outstanding. No new calibration was invented or saved during automated testing. The installed app starts in simulation and requires deliberate live reconnection. Feature work is committed separately on `feature/specific-gear-coaching`; publishing/merging is a separate action.

## Environment and source inspection

- Cloned `https://github.com/xxchaosxx210/LearnerAssist.git` into the existing, initially empty workspace `C:\Users\chaos\Documents\dev\LearnerAssist`. Repository contained only `.gitignore`; no existing app was overwritten.
- Branch: `feature/native-android-first-version`. No RoadBook files or dependencies used.
- Detected Android Studio, Adoptium JDK 25.0.5, Android SDK and ADB. Existing SDK had only API 33-ext5 / Build Tools 33.0.2.
- Downloaded Gradle 9.4.1 and Android CLI tools into ignored `.tools`. Installed stable `platforms;android-37.0`, Build Tools 36.0.0 and platform tools into a project-local SDK, reusing the machine's existing accepted SDK licenses. `local.properties` is ignored.
- Confirmed Pixel 9a reports Android 17 / API 37. User supplied wireless pairing details; pairing and connection succeeded. Installation/testing below used Wi-Fi ADB. No pairing secrets are checked in.

## Software checks executed

Command:

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

Result: **BUILD SUCCESSFUL**.

- JVM: **28 passed, 0 failures, 0 errors**: 16 protocol/safety tests and 12 driving/calibration/coaching tests.
- APK: debug application and Android test APK both built successfully.
- Lint: **0 errors, 10 warnings**. Warnings concern available dependency upgrades, backwards-compatible permission/backup attributes and a SharedPreferences KTX convenience suggestion. No baseline suppresses errors. Dependency versions are pinned; the test runner/Espresso were updated specifically for Android 17 compatibility.
- `git diff --check`: passed before final commit.

Pixel installation and device tests:

```powershell
adb -s DEVICE install -r app\build\outputs\apk\debug\app-debug.apk
adb -s DEVICE install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w uk.co.learnerassist.test/androidx.test.runner.AndroidJUnitRunner
```

Both installs returned **Success**. Instrumentation returned **OK (4 tests)** in 4.43 seconds:

1. Default simulation launch, source/status labels, updating calibrated estimate and RPM/mph values, pause and resume.
2. Clutch-disengaged scenario selection and Unknown gear display.
3. Settings round-trip persistence and activity recreation; audio disabled in saved test configuration.
4. Live access does not start without deliberate activation; background/foreground does not resume a live session. This test makes no real connection.

Initial device test execution failed inside older Espresso because Android 17 removed reflective `InputManager.getInstance`. Updated Espresso to 3.7.0, AndroidX runner to 1.7.0 and test JUnit to 1.3.0; all four tests then passed. Initial lint errors (Windows properties escaping and Android 12 coarse/fine location pairing) were fixed and lint rerun successfully.

Launched the installed application in simulation and inspected an ADB screenshot of the Pixel dashboard. Large RPM/mph/gear readings, source banner, coaching card, connection status and bottom navigation rendered correctly. The captured initial Unknown gear is expected during the stability dwell.

## Safety evidence

- Exhaustive 65,536 four-hex-digit command cases allow only `0100`, `0104`, `010C`, `010D`.
- Explicit tests reject reset, persistent protocol save, headers/manufacturer-address alteration, ECU clear/reset, UDS write/security/service commands, line-break injection and unapproved representations.
- Typed transport API accepts `SafeCommand`, not user strings; every write calls the central encoder/allowlist.
- All protocol tests use in-memory streams or fake sessions, including timeouts, partial input, disconnection, malformed replies and PID capability checks. No physical adapter is contacted by tests.
- Live configuration defaults to blank endpoint and simulation defaults on at every process launch. The live activation decision is never persisted. Two bounded reconnects are confined to an explicitly activated foreground session. Backgrounding closes the socket and Wi-Fi request.
- No ECU-changing operation, raw diagnostic console, saved adapter protocol operation, or background auto-connect path exists.

## Hardware-dependent work not performed

No Streetwize SWOBD3 or vehicle connection was attempted. No ECU traffic was generated on physical hardware. Milestone 3 is **outstanding**.

Manufacturer manual confirms Wi-Fi SSID and supported OBD protocols but leaves IP/port, exact ELM firmware, TCP framing and command compatibility undocumented. Supplier/device verification remains necessary. ELM identity/setup validation is intentionally conservative; an adapter with a non-ELM identity may be rejected.

Not yet validated: real RPM/speed/load against an independent reader, actual Fiesta gear calibration, clutch/neutral false matches, Wi-Fi selection on the SWOBD3, parallel cellular internet, real sensor latency, disconnection/reconnection with the reader, offline TTS voice behaviour and real-lesson suitability. UI tests do not claim these checks passed.

First version limitations: foreground monitoring only; open SSID selection only; literal IPv4 endpoints; no independent clutch/neutral sensor; settings/calibration for one named vehicle; no chart or export in local review; offline TTS may be unavailable without a downloaded voice. Instructor evaluation is required before lesson use.

## Suggested next validation

1. Confirm the actual SWOBD3 endpoint and TCP/ELM support with its supplier.
2. With an explicitly authorised operator and safe stationary vehicle, enable a live session and compare the approved current-data PIDs only. Do not add commands or bypass validation to force compatibility.
3. Test permission denial, no-internet Wi-Fi, mobile data coexistence, latency and reader disconnects on the Pixel.
4. Capture actual gear ratios with an instructor/passenger, then conservatively assess uncertainty and advisory behaviour under controlled conditions.

## Follow-up: second-to-first slow-progress coaching

Implemented conditional **Consider first gear if continuing** guidance, with optional speech, based on valid first/second calibration, plausible second-gear ratio, low RPM, slow progress/trend and predicted first RPM. Recent second-gear evidence expires after four seconds and never replaces an Unknown selected-gear display. Uncertain readings reset prompt dwell. Generic higher-gear downshift suggestions continue to require load; the specific slow-progress prompt does not. An eighth simulator scenario demonstrates gentle slowing from second to approximately 7 mph / 900 RPM. Teaching bounds are documented in README; they have not been validated against the Fiesta.

Executed `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: **BUILD SUCCESSFUL**, **36 JVM tests passed** (20 driving/coaching and 16 protocol), lint **0 errors / 10 warnings**. Eight new core tests cover low-RPM second-to-first advice, bounded recent-gear history with Unknown current gear, calibration/ratio constraints, predicted first RPM, uncertainty/clutch/trend/mode suppression, dwell reset, repeat suppression and the full simulator/estimator/coach path. A dwell test initially expected the prompt one sample too early after an unsynchronised interval; corrected it to include the full clean-reading dwell, then reran successfully.

Installed both updated APKs on the Pixel through its authorised USB ADB connection (the earlier Wi-Fi endpoint was offline). Device instrumentation returned **OK (5 tests)** in 19.081 seconds, including the new scenario's conditional first-gear prompt and estimated second-gear display. All tests remained in simulation; no reader or ECU connection was attempted. Actual car calibration and instructor assessment remain outstanding.

## Follow-up: physical reader endpoint identification

The user connected the SWOBD3 and Pixel to its Wi-Fi, then replaced an unstable USB cable. ADB successfully detected the Pixel over USB. Observed Wi-Fi SSID **WIFI_OBDII**, phone IPv4 **192.168.0.100**, gateway **192.168.0.10** and matching gateway BSSID/neighbour entry. An interface-bound ping reached the gateway.

Shell TCP attempts initially timed out. `ip route get` showed shell traffic selected cellular routing despite setting the Wi-Fi source address. Added `ReaderEndpointProbe` in the Android test APK, explicitly gated by `readerProbe=true`; all ordinary runs skip it. It selects the existing Wi-Fi network by the observed phone IP, binds each socket to that network, and checks a bounded port list on the explicitly supplied reader IP. No process-wide network binding or network setting changes were used. Granted the app's declared Nearby Wi-Fi/local-network permissions via ADB for this expressly requested diagnostic.

Manual diagnostic invocation used `readerProbe=true`, `readerHost=192.168.0.10`, `readerPhoneIp=192.168.0.100`, `readerIdentity=true`, `saveReaderEndpoint=true`, `readerSsid=WIFI_OBDII`, and only the `ReaderEndpointProbe` class. Result: **OK (1 test)**, TCP **192.168.0.10:35000** opened, then approved `ATI` returned an echoed command and **ELM327 v1.5**. Identity traffic used `ElmSession` / `SafeCommand.IDENTITY` / central `CommandPolicy`, not a raw diagnostic send path.

Checking the saved XML caught that the short-lived instrumentation process had exited before the asynchronous settings write persisted. Added an explicit disk flush to the manual diagnostic. A repeat attempt failed closed because the phone had disconnected from the reader Wi-Fi; no diagnostic request was sent during that failed attempt. Persisted the already confirmed endpoint and exact SSID through USB ADB by stopping the app, updating only its three connection preferences and preserving all other values, then reopened the app. Read back the saved XML and confirmed `host=192.168.0.10`, `port=35000`, `ssid=WIFI_OBDII`. Live-monitor activation was not changed. The reader Wi-Fi must be reconnected for the next live step.

Build of the manual diagnostic and `:core:test :app:lintDebug` succeeded. Lint remains 0 errors / 10 warnings. Also invoked only `ReaderEndpointProbe` without any activation arguments: its assumption gate skipped the diagnostic body in 0.051 seconds, making no network connection. Existing simulation tests were not rerun against the physical reader.

This physical check sent **no Mode 01 or other ECU requests**. It confirms TCP reachability and a reported ELM identity only; exact clone firmware capabilities, temporary setup support, PID support, real RPM/speed/load, vehicle protocol and calibration are still unverified. The next step is a deliberate in-app live connection with the vehicle safely parked and an authorised operator present.

## Follow-up: live data observation and lower-speed first-gear calibration

The user explicitly enabled the in-app live session and reported RPM and moving speed matched the car reasonably well. Inspected the dashboard over USB without issuing additional diagnostic requests: it displayed **LIVE ECU DATA · READ ONLY**, **875 RPM**, **0 mph**, **16% engine load**, **Unknown gear**, and **Prepare for moving off**. Saved settings contained no gear ratios. This establishes an operating live read-only session and plausible idle data; moving-speed accuracy was user-reported rather than independently measured. Stationary first gear cannot be inferred from these sensors.

At the user's request, changed first-gear capture to accept steady readings from **8 km/h (approximately 5 mph)** and **800 RPM**. Other forward gears retain **15 km/h / 1,000 RPM** floors. The thresholds are measurement limits rather than prescribed driving speeds. The capture now requires five full seconds, at least six continuous samples, fresh synchronised values, finite sensor readings and a stable RPM/speed ratio. First-gear speed spread is limited to 1 km/h. The rolling six-second window eventually drops previous manoeuvres. Stationary, creeping, engine-off, stale, mixed-source, interrupted, clutch-uncertain and unstable samples are rejected.

Settings displays readiness/rejection reasons for each gear and enables capture only when suitable live samples exist. Capture requires an instructor/passenger to confirm the known gear and clutch engagement throughout the sample window. The app cannot independently verify those statements. Readiness is checked again on confirmation; saved gear ratios must still decrease distinctly. The current selected-gear uncertainty floor remains 10 km/h; first gear can therefore be calibrated below the speed where a current gear is displayed.

Executed `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`: **BUILD SUCCESSFUL**, **42 JVM tests passed** (26 driving/calibration and 16 protocol), lint **0 errors / 10 warnings**. Six new core tests cover lower-speed first capture versus other gears, excluded stationary/creeping/invalid-gear cases, actual elapsed time, clutch/ratio/speed uncertainty, invalid/missing/stale/interrupted readings and recovery after earlier manoeuvres.

Installed the updated app and test APK over USB, ending the existing live session. Explicitly ran only `uk.co.learnerassist.SimulationTest`: **OK (6 tests)** in 20.483 seconds, including the lower first-gear threshold explanation and disabled simulated capture. The gated physical-reader probe was not invoked; automated verification sent no commands to the reader. Existing endpoint/settings were retained. Actual first-gear calibration at the lower floor has not yet been physically validated and must be carried out by an instructor/passenger under appropriate conditions. The app starts in simulation and requires deliberate live reconnection after installation.

## Follow-up: first-gear capture remained grey at 5–7 mph

The user reported the button never enabled during first-gear progress around 5–7 mph. The phone was at its home screen when inspected, so the original live rejection message was not observed. Code review found a sufficient explanation: the first-gear speed spread limit was only **1 km/h (0.6 mph)** across the recent window, rejecting that reported range even when an engaged gear could produce consistent RPM/speed readings. The flat 3.5% ratio tolerance could additionally reject consistent data near the whole-km/h boundaries of speed PID 0D.

Changed only first-gear capture to permit a **4 km/h (2.5 mph)** total speed spread, retaining the 8 km/h / 800 RPM floors, five-second minimum, continuity, freshness, synchronisation, known-gear confirmation and clutch safeguards. Fit RPM against speed within the common ratio interval supported by every sample, allowing a half-km/h speed-step term plus the existing 3.5% RPM tolerance. This is an explicit sensor-error model, not a guarantee of ECU sensor accuracy or proof of clutch engagement. Inconsistent/slipping ratios still fail closed. Higher-gear stability criteria are unchanged. No transport, diagnostic command, selected-gear uncertainty floor or ECU access policy was changed.

Added live RPM/speed and explicit simulation/disconnected messages beside calibration controls. Four new core regression tests exercise gentle 5–7 mph progress, alternating adjacent integer speed readings, incompatible/slipping ratios, and unchanged higher-gear rejection rules. The adjacent-integer test initially exposed median-fit bias; replaced that fit with a bounded least-squares fit and reran successfully. `:core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` passed: **46 JVM tests**, lint **0 errors / 10 warnings**. Actual physical first-gear capture remains unverified; this fix addresses the demonstrated software rejection rules rather than claiming an observed successful vehicle calibration.

Installed the app/test APKs over USB and explicitly ran only `SimulationTest`: **OK (6 tests)** in 21.227 seconds. The UI regression also checks that simulation explains why live capture is disabled. No hardware diagnostic was enabled during tests; saved reader preferences were retained. Reopened the installed app in its default simulation mode; a deliberate live reconnect is required before attempting instructor/passenger-operated calibration.

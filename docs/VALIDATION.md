# Validation record — 13 November 2026

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

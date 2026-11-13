# LearnerAssist

A standalone native Android teaching aid for learner drivers in a manual car. Kotlin, Jetpack Compose, Material 3, ViewModel, coroutines and StateFlow. No account, cloud service, subscription or RoadBook dependency.

**Read-only monitoring only.** This app never implements ECU writes, code clearing, actuator control, programming, security access or arbitrary diagnostic commands. Advice cannot account for every road condition or driver intention. The instructor remains responsible. Configure while parked; calibration controls are for an instructor/passenger.

## First version

- High-contrast dashboard: RPM, mph, load, estimated gear, calm advisory coaching, connection/source status.
- Default simulation: eight scenarios plus adjustable RPM, speed, load and clutch uncertainty, including second-to-first slow progress.
- Beginner, Intermediate and Independent modes; sensitivity and prompt interval settings.
- Optional offline Android Text-to-Speech (off by default), voice volume and repeat suppression.
- Local settings and vehicle-specific ratio capture. No unverified Fiesta transmission ratios.
- Explicitly enabled Wi-Fi TCP reader sessions, socket-specific Wi-Fi binding, PID capability discovery, sequential polling, timeouts, stale-data suppression and two bounded reconnect attempts within an activated session.
- Optional local lesson log and last-20-sample review, with data source on every row. No GPS collected. Log limited to approximately 1 MB, then rotated by clearing; delete in Review.

## Tools and Android Studio setup

Open this directory in Android Studio. Tested build configuration: Android Gradle plugin 9.2.0, Gradle 9.4.1 wrapper, Kotlin/Compose compiler 2.2.10, compile/target Android 17 API 37.0, SDK Build Tools 36.0.0. Minimum Android 10/API 29. Use a JDK supported by Gradle (17–26); development here used installed Adoptium JDK 25. AGP 9 includes built-in Kotlin for Android.

Install **Android SDK Platform 37.0** and **Build Tools 36.0.0** through SDK Manager. Package ID is `platforms;android-37.0`, not `platforms;android-37`. Point ignored `local.properties` to your SDK:

```properties
sdk.dir=C\:/Users/YOUR_USER/AppData/Local/Android/Sdk
```

Development downloads on this laptop are under ignored `.tools/`; they are not needed in a fresh checkout when Android Studio supplies the SDK. This project lives in `C:\Users\chaos\Documents\dev\LearnerAssist` so it stays in the authorised workspace.

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. JVM reports: `core/build/reports/tests/test/index.html`. Android lint: `app/build/reports/lint-results-debug.html`.

## Pixel 9a deployment

USB: enable Developer options and USB debugging, unlock the Pixel and approve the laptop key. Wireless: put laptop/phone on the same ordinary Wi-Fi, enable Wireless debugging, select **Pair device with pairing code**, then:

```powershell
adb pair PHONE_IP:PAIRING_PORT
# Enter the code when prompted. The connection port is different:
adb connect PHONE_IP:CONNECTION_PORT
adb devices -l
adb -s PHONE_IP:CONNECTION_PORT install -r app\build\outputs\apk\debug\app-debug.apk
adb -s PHONE_IP:CONNECTION_PORT shell am start -n uk.co.learnerassist/.MainActivity
```

Use the connection address from the main Wireless debugging screen, or `adb mdns services`. Ports may change. Once connected over Wi-Fi, USB is unnecessary. Keep wireless debugging on during development; switching to the reader's Wi-Fi may interrupt ADB. If multiple entries represent the same phone, always select one with `-s`.

To run simulation-only on-device tests (no ECU connection):

```powershell
adb -s DEVICE install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w uk.co.learnerassist.test/androidx.test.runner.AndroidJUnitRunner
```

Or select one device using `ANDROID_SERIAL` and run `:app:connectedDebugAndroidTest`. Never enable a reader connection for automated tests.

## Simulation

App launch always starts simulation, including after a process restart; live activation is never saved. The amber **SIMULATED DATA** banner and demo-ratio description identify the source. In Simulator select Moving away, Through the gears, Steady at 30 mph, Slowing for a junction, Approaching a stop, Low RPM/high load, or Clutch disengaged. Sliders switch to manual sensor control. Speed changes provide acceleration/deceleration input. Gear is derived from these readings, not a forced gear value.

Simulation fixture ratios are educational values, not Ford specifications, and are never written into vehicle calibration. A changed gear takes at least 1.5 seconds of a stable matching ratio. At very low speed, stopped, clutch disengaged, stale/unsynchronised readings or no confident ratio match, gear is **Unknown**. Stationary first gear is never claimed. The **Second to first · slow progress** scenario slows in second and holds approximately 7 mph / 900 RPM so the conditional first-gear prompt can be demonstrated.

## SWOBD3 reader procedure — physical verification outstanding

**Observed on the user's physical reader, 13 November 2026:** SSID `WIFI_OBDII` (case-sensitive), gateway/adapter `192.168.0.10`, TCP port `35000`. A socket explicitly bound to the Pixel's reader Wi-Fi connected successfully, and the centrally allowlisted `ATI` request returned `ELM327 v1.5`. These connection details were saved on the Pixel. This confirms a responding ELM interface, not firmware authenticity, full setup compatibility or valid ECU sensor data. New installations still use blank endpoint defaults; do not assume all readers share this endpoint. Sensor monitoring/calibration remains outstanding.

Source: [Streetwize SWOBD3 manual (2022)](https://www.streetwizeaccessories.com/files/e54f56c5-910c-4a30-a9eb-aee700f2c4dc/SWOBD3-Manual%20AW%202022.pdf), pages 4–6. It specifies Wi-Fi SSID `WiFi_OBDII` and standard OBD-II protocols including CAN, ISO9141-2, KWP and J1850. It lists Scan Master ELM among compatible software. **It does not document an IP address, port, exact ELM firmware/version, socket protocol or detailed command compatibility.** ELM compatibility is plausible, not physically verified. Obtain verified connection parameters from the actual device/support; the app deliberately ships blank IP/port fields.

1. With an authorised operator present and the vehicle switched off, insert the reader as directed by its manual. Power it by switching the vehicle on according to the manual, in a suitable safe location.
2. Confirm the reader's network, endpoint and TCP/ELM capabilities. In Settings enter its SSID, verified literal IPv4 address and port. Current in-app Wi-Fi selection supports an open SSID; secured variants need additional implementation.
3. Disable Simulation. This alone sends nothing. On Drive tap **Connect reader**, review the live-access dialog and deliberately enable it. Accept Nearby devices/Wi-Fi permission (and local-network permission on Android 17). On Android 10–12 grant location permission; system location services may need enabling for Wi-Fi selection.
4. Accept Android's Wi-Fi selection dialog for the reader. Internet capability is not requested; only the diagnostic socket is bound to this specific network. The app never binds the entire process. Continued mobile internet access is device/network dependent and untested.
5. App queries adapter identity, checks minimal temporary setup replies and discovers sensor support. Only then does it poll RPM/speed and optional load. An incompatible adapter fails closed.
6. Disconnect with the button. Backgrounding/closing the app also closes the connection and cancels reconnection. Returning to foreground requires a new deliberate connection. No boot receiver or background reader service exists.

Physical testing has so far verified only the adapter TCP endpoint and its reported identity. Do not interpret successful Android simulation tests or adapter identification as validation of protocol negotiation, actual ECU values, or safe coaching in lessons.

## Read-only safety architecture

`core/Protocol.kt` separates typed `SafeCommand` definitions, exact `CommandPolicy` validation, a typed `DiagnosticSession`, prompt-framed `ElmSession` and strict `ObdDecoder`. `ReaderConnection` owns network/socket setup; features receive no raw socket or arbitrary send method. Every emitted command is encoded by the central allowlist immediately before writing.

| Approved diagnostic request | Purpose |
| --- | --- |
| `0100` | Discover supported current-data PIDs |
| `010C` | RPM = (256A + B) / 4 |
| `010D` | Speed in km/h; dashboard converts to mph |
| `0104` | Load = 100A / 255, only when advertised |

Approved interface commands: `ATI` (identify), `ATE0` (echo off), `ATL0` (linefeeds off), `ATH0` (headers off), `ATTP0` (try automatic protocol without saving the protocol to non-volatile memory). No adapter reset, persistent configuration or protocol-save command is implemented. The distinction between TP and SP is documented in the [ELM327 data sheet](https://elmelectronics.com/wp-content/uploads/2020/05/ELM327DSL.pdf). Actual clone compliance still requires verification.

There is no diagnostic text input/console. Endpoint fields cannot become commands. Only approved Mode 01 requests are available: no clear codes, freeze-frame erasure, ECU reset, UDS security access/writes, coding, actuator control or programming paths. Strict response lengths/PIDs, prompt limit, read and overall deadlines, EOF handling and connection poisoning prevent late fragments being reused as fresh data. Disagreeing ECU responses fail closed.

Unit tests use byte streams and typed fake sessions, never physical sockets. Real access requires changing mode, pressing Connect, confirming the dialog and granting system permissions. Reconnect is capped at two retries during that one foreground activation; no automatic discovery-to-connect path exists. Command validation reduces software risks but cannot guarantee every hardware/firmware implementation behaves correctly.

The Android test APK also contains `ReaderEndpointProbe`, a manual diagnostic which is skipped unless explicitly enabled with `readerProbe=true` and an explicitly supplied reader/phone IP. It checks a small bounded set of ports on that one device through its Wi-Fi-specific socket. Optional `readerIdentity=true` sends only `SafeCommand.IDENTITY` through the central encoder; it never polls ECU data. Saving a confirmed endpoint additionally requires `saveReaderEndpoint=true` and the observed `readerSsid`. Ordinary test execution supplies none of these flags and cannot activate discovery. On this Pixel, shell netcat checks followed cellular routing even with a Wi-Fi source address; their timeouts did not establish port availability. Wi-Fi-bound socket testing resolved this without redirecting the phone's internet traffic.

## Calibration and coaching

Live ratios start empty. First-gear calibration accepts steady progress from **8 km/h (about 5 mph)** and **800 RPM**. Gears 2–6 retain minimums of **15 km/h (about 9.3 mph)** and **1,000 RPM**. These are measurement-quality floors, not recommended driving speeds; do not force a manoeuvre to satisfy capture. All gears require at least five actual seconds and six continuous samples, a stable RPM/speed ratio, fresh synchronised data and a fully engaged clutch. First-gear speed spread is limited to 1 km/h; other gears allow 3 km/h. A recent six-second window is used so old manoeuvres eventually stop blocking capture. Stationary, very slow, interrupted, mixed-source, non-finite or uncertain samples cannot be saved.

Settings shows live readiness and the reason capture is unavailable for each gear. Capture is enabled only with suitable live readings. An instructor/passenger must then confirm the known gear and fully engaged clutch throughout the sample period; the app rechecks readiness before saving. Never operate capture as the driver. Capture first and second for second-to-first coaching. At least two distinct, decreasing ratios are needed for estimation. Clear/recalibrate after transmission, wheel or tyre changes. Captured values are local and tied to the manually named vehicle profile; clear them when changing cars. The lower calibration floor does not lower the selected-gear estimator's uncertainty floor: below 10 km/h (6.2 mph), selected gear still displays Unknown.

OBD does not provide a clutch or neutral sensor here. Ratio matching and instability checks suppress uncertain estimates but cannot prove clutch engagement or reliably detect every neutral/clutch situation. Gears below 10 km/h are always Unknown. Samples spanning more than 1.5 seconds are treated as unsynchronised; readings older than 2.5 seconds are removed.

Coaching considers RPM, speed, calibrated gear, load and acceleration trend, with stable-candidate dwell, minimum intervals and duplicate suppression. High load or deceleration suppress upshifts; low RPM plus load can suggest a lower gear. General gear-change suggestions require load data.

For second-to-first slow progress, **Consider first gear if continuing** is conditional on valid first/second calibration, a plausible second-gear RPM/speed ratio, low RPM, slow stable/decelerating progress and a modest predicted RPM in first. This does not require high load, so slowing gently can trigger it. Software teaching bounds are 8–12 km/h, at least 800 RPM, at most the configured downshift RPM, and predicted first-gear RPM 900–1800. These are conservative demonstration bounds, not verified Ford shift recommendations. The ratio and trend must remain plausible throughout the 3-second beginner / 5-second intermediate dwell. Below the gear estimator's 10 km/h floor, a confirmed second gear may support the conditional prompt for at most four seconds; the displayed selected gear stays Unknown. Missing first calibration, expired history, ratio mismatch, stale/asynchronous data, clutch uncertainty, acceleration or substantial braking suppress the prompt. Stationary coaching remains Prepare for moving off and does not claim an engaged gear. Independent mode suppresses assistance. If stopping, the instructor's stopping routine takes precedence.

Sensitivity values are teaching defaults, not validated Fiesta shift points. The app cannot sense gradients, hazards, junctions or intentions. Audio is optional, offline-voice only, off by default; install a UK English offline TTS voice if necessary. No frequent forced gear commands are issued.

## Tests and troubleshooting

`core/src/test` covers the complete 16-bit diagnostic command space, unsafe AT commands/injection, typed-only transport, scalar PIDs and capability masks, malformed/ambiguous/unsupported responses, fragmented input, EOF, timeouts, oversize/slow responses, simulated monitor polling, uncertainty/calibration, scenarios and coaching suppression/dwell. `app/src/androidTest` covers simulation launch/pause, scenario selection and preference persistence. See `docs/VALIDATION.md` for executed results and outstanding device/hardware checks.

- **Unauthorized ADB:** unlock and approve the host key, or pair using wireless debugging. Pairing and connection ports differ.
- **SDK not found:** set `local.properties` and install stable package `platforms;android-37.0`.
- **Cannot connect:** verify SSID, literal IPv4 and TCP port; blank defaults are intentional. Grant Nearby Wi-Fi and Android 17 local-network permission in app settings. Do not substitute guessed endpoints.
- **No internet on reader Wi-Fi:** the reader is a local device without internet. Simultaneous mobile data is not guaranteed.
- **ELM identity/setup rejected:** firmware compatibility is unverified. Do not bypass validation or add arbitrary commands; obtain device documentation.
- **Unknown gear:** check fresh speed/RPM, sufficient calibration and stable clutch engagement. This is expected while stopped or changing gears.
- **Load unavailable:** PID may not be supported. Monitoring continues without load; change advice is suppressed.
- **Stale/error status:** values are cleared; only two retries are attempted. After failure reconnect deliberately.
- **No speech:** enable audio, check volume and install an offline UK English TTS voice; system language/voice availability varies.

## Next steps

Verify SWOBD3 TCP/ELM identity and temporary setup compatibility with its supplier, then perform an explicitly authorised stationary read-only sensor comparison. Validate Wi-Fi/mobile-data behaviour and latency on the Pixel. Calibrate the actual car with an instructor/passenger and evaluate coaching conservatively before using it in lessons. No vehicle-testing milestone is marked complete by the software build.

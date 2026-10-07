# TreadZ Console

Native Android app to control a **Reebok Z-Tech** treadmill over Bluetooth LE using the standard
**FTMS** (Fitness Machine Service) protocol.

> **Status: milestone 1, proof of concept.** The app has only been tested against a simulator.
> It has not been tried on real hardware yet.

## Milestone 1

- Connects to the treadmill. It remembers the last one and reconnects automatically.
- Shows live data: speed, incline, distance, time, calories, heart rate and belt state.
- Has a single **Start** button. Everything else is done with the console's physical controls.
- Shows full-screen safety alerts for:
  - safety key removed,
  - connection lost,
  - frozen data,
  - a command the treadmill accepted but did not carry out.
- Records every session in full for debugging, as JSON Lines files in `Download/TreadZConsole/`, and lets you share
  them.
- Available in English and Spanish, with layouts for phone (portrait) and tablet (landscape). Dark theme by default.

## Safety design

- All commands go through a single controller. It checks each command against the treadmill data, because an OK
  from the FTMS Control Point does not guarantee the command was carried out.
  - A stop or pause only counts as done when the belt actually slows down to 0.
  - Speed and incline changes count as done when the measured value moves towards the target.
- Only the FTMS service (`0x1826`) and Device Information are used. The vendor channel and the OTA service are
  never touched.
- FTMS time and distance targets are never sent: on this treadmill they turn those fields into countdowns.

## Modules

| Module | Contents |
|---|---|
| `:treadmill` | Pure Kotlin: controller contract, FTMS codec, safety layer, treadmill simulator and session recorder. Fully tested on the JVM. |
| `:treadmill-android` | `BluetoothGatt` transport, foreground service, MediaStore storage and share intent. |
| `:app` | Jetpack Compose UI. |

## Building

Requirements: JDK 21 and the Android SDK (compile SDK 37). The app needs Android 13 (API 33) or later.

```bash
./gradlew :app:assembleDebug
```

Tests:

```bash
./gradlew :treadmill:test :app:testDebugUnitTest
```

Screenshot tests (Roborazzi + Robolectric) write PNGs to `app/build/outputs/roborazzi/`:

```bash
./gradlew :app:recordRoborazziDebug
```

## Disclaimer

This is an independent project, not affiliated with or endorsed by Reebok or the treadmill manufacturer. It
controls a moving machine: use it at your own risk and always keep the safety key attached.

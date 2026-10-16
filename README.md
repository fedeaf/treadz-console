# TreadZ

**TreadZ** is a native Android app that turns a phone or tablet into the console of a **Reebok Z-Tech** treadmill.
It talks to the treadmill over Bluetooth LE with the standard **FTMS** (Fitness Machine Service) protocol: live data,
speed and incline control, training programs, heart-rate guided workouts, fitness tests, several users, weight
tracking and a complete workout history, with optional sync between devices.

No accounts, no ads, no analytics: everything stays on the device unless you choose to sync it to your own private
GitHub repository.

## Features

### Treadmill control and live data

- Finds the treadmill, remembers it and connects on its own when the app opens; it never searches in the background.
- Live speed, incline, distance, time, calories and heart rate, in big figures readable while running.
- Speed − / + in 0.1 km/h steps and quick presets (4 · 6 · 8 · 10 · 12 km/h); incline − / + and presets (0 · 3 · 6 ·
  9 · 12 %). Holding a button repeats it.
- Start, pause and resume, and a large **STOP** button that is always one tap away: on every screen and in the
  notification.
- Changes made with the console's own buttons are picked up and respected.
- Keeps working in the background and with the screen off; a notification shows the session and has its own STOP.

### Safety first

- Every command goes through a single controller that checks its effect on the treadmill data: an OK from the
  treadmill is not taken as proof. A stop or a pause only counts once the belt has actually slowed down.
- Full-screen alerts for a removed safety key, a lost connection, frozen data and commands the treadmill accepted but
  did not carry out.
- The workout is saved as it happens, so a dropped connection, the safety key or a closed app never loses it.
- Only the standard FTMS service is used. The vendor channel and the firmware update service are never touched.

### Training programs

- **Standard programs:** hill, rolling hills, intervals, pyramid, progressive, fat burn, incline walk and glutes /
  strength. Choose the duration and your own easy and top speeds (and maximum incline); the app remembers them for
  each user and program. A global ± % adjusts the speed during the session.
- **Free programs:** build your own with an editor of blocks by time or distance, with speed, incline or a heart-rate
  range, and repeated groups (for example 4 × [4 min hard + 3 min easy]).
- **Personalised programs**, generated from your heart-rate zones and fitness: zone 2 easy run, tempo / threshold,
  Norwegian 4x4 and 30/30 intervals. Repeat or regenerate them at any time.
- **Couch to 5K:** an adaptive plan that gives you the next session each time, moves on when you complete one,
  repeats it when it was too hard and steps back when you could not finish it.
- Every plan is shown as a chart with your position on it, the current block, the time left and the totals.
- Manual changes are respected until the next block. After an interruption the app never restarts the belt on its
  own: you choose to continue or end the program.

### Heart rate

- Connects directly to a standard Bluetooth chest strap, or uses the heart rate the treadmill console receives. The
  source is picked automatically and the app falls back to the other one if a strap drops out.
- **Heart-rate guided blocks:** the app adjusts speed or incline to keep you in a target range, with safety limits:
  it slows down when you go above your maximum and never pushes harder right after easing off.
- Heart-rate zones computed with the Karvonen formula when your resting heart rate is known.
- **Resting heart rate** measurement (no treadmill needed), with heart-rate variability (RMSSD) and a history; the
  reference is the median of the last 30 days.
- **Maximum heart rate:** measured by the fitness tests, offered when a workout reaches a new peak, or set by hand.

### Fitness tests

- **Ebbeling walking test:** a gentle submaximal test that estimates your VO2max.
- **Maximal running test:** the speed rises every minute until exhaustion. It gives your maximal aerobic speed (MAS),
  your VO2max and your maximum heart rate.
- **Maximum heart rate test:** a walking test where the incline rises every minute until exhaustion, designed to
  measure your maximum heart rate efficiently.
- Clear on-screen instructions, warm-up and cool-down, and a validity check of every result (heart-rate quality,
  real maximal effort, interruptions).
- A fitness summary in each profile: VO2max weighted by accuracy and age of each test, MAS and measured maximum heart
  rate, with fitness norms for your age and sex.

### Users, weight and history

- Several users on the same device, each with their own workouts, programs, tests, weight log and strap.
- Weight log with a chart and a target weight. The latest weight feeds the calorie estimates.
- Workout history per user, with charts of speed, incline and heart rate, and the planned versus the actual workout
  for programs.
- Keep or discard each workout when it ends.
- Export any workout as **CSV** (second by second, for a spreadsheet or analysis on a PC) or **TCX** (for Strava,
  Garmin Connect and similar), saved in `Download/TreadZ/` and ready to share.

### Sync between devices (optional)

- Syncs users, weights, workouts, programs and test results through a **private GitHub repository** of your own,
  so several devices share the same data and you keep a readable copy (JSON and CSV) of everything.
- Uploads in the background, retries when there is no network and shows the sync state in the history.

### Made for the treadmill

- Layouts for phones (portrait) and tablets (landscape), designed to be used while walking or running.
- English and Spanish. Dark and light themes.

## Compatibility

- **Android 13 (API 33) or later**, with Bluetooth LE.
- Developed and tested on a **Reebok Z-Tech** treadmill. Other treadmills that implement FTMS may work, but they have
  not been tested.
- Heart rate: any chest strap with the standard Bluetooth Heart Rate service, or one paired with the treadmill console.

## Installation

Download the APK from the [latest release](https://github.com/fedeaf/treadz-console/releases/latest) and open it on
the device (Android will ask you to allow installing apps from that source). On first start the app asks for the
nearby devices (Bluetooth) and notifications permissions.

## Building from source

Requirements: JDK 21 and the Android SDK (compile SDK 37).

```bash
./gradlew :app:assembleDebug
```

A debug build also records every session in full (raw Bluetooth traffic, decoded data and every command sent) in
`Download/TreadZ/` as JSON Lines, and Settings can share the last recording. Release builds record nothing.

Tests (pure Kotlin modules on the JVM, Android modules with Robolectric):

```bash
./gradlew :programs:test :treadmill:test :hrm:test :userdata:testDebugUnitTest :sync:testDebugUnitTest :app:testDebugUnitTest
```

Screenshot tests (Roborazzi) write PNGs to `app/build/outputs/roborazzi/`:

```bash
./gradlew :app:recordRoborazziDebug
```

A signed release build reads its key from a properties file outside the repository (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`), given by the `TREADZ_SIGNING_PROPERTIES` environment variable or found at
`~/.config/treadz-console/release-signing.properties`. Without it the release APK is built unsigned:

```bash
./gradlew :app:assembleRelease
```

### Modules

| Module | Contents |
|---|---|
| `:treadmill` | Pure Kotlin: controller contract, FTMS codec, safety layer, treadmill simulator and session recorder. |
| `:treadmill-android` | Bluetooth LE transport, foreground service and notification, MediaStore storage. |
| `:hrm` | Pure Kotlin: heart-rate strap support (codec, connection logic, automatic source, resting heart rate). |
| `:hrm-android` | Bluetooth LE transport for the heart-rate strap. |
| `:programs` | Pure Kotlin: programs (model, resolver, runner, heart-rate control), standard catalogue, personalised generators, Couch to 5K and fitness tests. |
| `:userdata` | Local data (Room): profiles, weights, workout history, programs, test results, heart-rate measurements, export. |
| `:sync` | Optional sync with a private GitHub repository. |
| `:app` | Jetpack Compose UI. |

## License

TreadZ is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3 of the License,
or (at your option) any later version. It is distributed in the hope that it will be useful, but **without any
warranty**; see the license for details.

## Disclaimer

TreadZ is an independent project. It is not affiliated with, endorsed by or supported by Reebok or the manufacturer
of the treadmill; all trademarks belong to their owners.

TreadZ controls a moving machine and gives training guidance; it is not a medical device. Use it at your own risk:
keep the safety key attached, check with a doctor before hard efforts or fitness tests, and stop if you feel unwell.

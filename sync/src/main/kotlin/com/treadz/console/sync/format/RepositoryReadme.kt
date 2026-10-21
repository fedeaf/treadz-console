package com.treadz.console.sync.format

/** The `README.md` placed at the root of the data repository the first time the app writes to it. */
internal object RepositoryReadme {

    val TEXT = """
# TreadZ data

Data of the [TreadZ](https://github.com/fedeaf/treadz-console) app, synchronised between the devices that
use this repository. The app writes here; you can read (and analyse) everything with plain `git`.
Format version: 1.

## Layout

```
users/<name>-<uuid>/profile.json
                    weight.csv
                    resting-heart-rate.csv
                    workouts/<yyyy>/<MM>/<yyyyMMdd_HHmmss>.csv    per-second data of one workout
                    workouts/<yyyy>/<MM>/<yyyyMMdd_HHmmss>.json   its summary and metadata
                    programs/<uuid>.json                          one training program
                    fitness-tests/<id>.json                       one fitness test result
```

- One folder per user. `<name>` is the lower-case ASCII form of the profile name (readable, may change); `<uuid>`
  identifies the user for good. If the name changes, the folder is renamed.
- Workout files are named after the start time of the workout in the local time of the device that recorded it.
- Every record has a `uuid`, an `updated_at` time (ISO-8601, UTC) and an `updated_by` device id. When two devices
  change the same record, the latest `updated_at` wins (ties: the greater `updated_by`).

## Files

`profile.json`: `name`, `sex` (`male`/`female`), `height_cm`, `birth_date`, `resting_hr_bpm`, `max_hr_bpm`,
`fitness_level`, `goal`, `target_weight_kg`, `created_at`. Unset optional values are `null`.
`resting_hr_bpm` is the user's resting heart rate: the **reference** of `resting-heart-rate.csv` (below), written for
people to read; the app takes the measurements from the csv. A profile file with a `resting_hr_bpm` in a repository that
has no `resting-heart-rate.csv` for the user was written by an older version: the app reads that value as the user's first
manual measurement.
`strap_address` and `strap_name`, present only when the user has paired a heart rate strap, are the Bluetooth address
(`AA:BB:CC:DD:EE:FF`) and the name of the strap, so that every device of the user finds the same strap.
`max_hr_bpm` is the maximum heart rate the user entered; `max_hr_set_at`, present only when known, is when it was
entered or last confirmed (values entered before the field existed lack it and count as older than any fitness test). When there is no manual value the app uses the maximum heart
rate measured by a maximal fitness test and, failing that, the Tanaka estimate `208 - 0.7 * age`.

`profile.json` may also contain a `fitness` block: the user's fitness profile (combined VO2max, maximal aerobic speed,
measured maximum heart rate and thresholds, with the tests they were computed from and the weighting rules), derived from
the `fitness-tests` of the user. It is **read-only**, there for people and for analysis: the app ignores it on reading and
computes it again on every device from the fitness tests, so every device writes exactly the same block. Its
`computedAt` is the start time of the newest valid test, not the time it was written: results are weighted by their
age relative to that test. The file keeps the profile's own `updated_at`/`updated_by` when only the block changes.
The block stays at `schemaVersion` 1: the fields below were added as optional ones (readers must ignore unknown fields).

- `vo2max.discrepancy` (only present, `true`, when it applies): two of the tests used differ by more than 1.5 times
  `sqrt(SEE1^2 + SEE2^2)` (their standard errors as weighted), so one of the measurements is probably wrong; those sources
  carry `discrepant: true`. It is data only. A source also carries `weightFactor` when it is not 1: 0.25 for a submaximal
  test (Ebbeling) while a valid maximal test of the last 90 days exists. `rules` lists `submaximalFactorWithMaximal`,
  `maximalRecencyDays` and `discrepancyFactor`. A source with `lowerBound: true` (the test only says "at least") is not
  part of the mean: it has `weight` 0, unless it is above the combination of the other tests (or there are no others),
  in which case the profile takes its value and it is the only source with weight. Only sources that are not lower
  bounds can be `discrepant`.
- `mas` is the latest **measured** value of the maximal running test, or `estimated: true` (VO2max / 3.5). The estimated
  MAS of a maximal walking test (`masIsEstimated` in the test) is never used as a measured one.
- `thresholds`, present when a valid maximal test has VT1 or VT2: `rules` (weights by quality, 30-day half-life, tests
  older than 180 days left out, the jump rule), `vt1` and `vt2` (`heartRateBpm` combined, `vo2MlKgMin` at the threshold,
  the `quality` of the source that weighs most and the `sources`: `resultId`, `testType`, `testedAt`, `heartRateBpm`,
  `vo2MlKgMin`, `quality`, `ageDays`, `weight`, and `jump`/`corroborated`) and `history` (every valid test with a
  threshold, newest first: `resultId`, `testType`, `testedAt`, `vt1Bpm`, `vt2Bpm`, `quality`). Only tests with a `high`
  or `medium` quality detection count; a test more than 6 bpm away from the value the earlier tests give weighs half
  until another test of the next 60 days is within 4 bpm of it.

`weight.csv`: `uuid,date,weight_kg,deleted,updated_at,updated_by,created_at`, one row per weight entry, ordered by
`date`, then `created_at`, then `uuid`. The last row that is not deleted is the user's current weight, the same on every device
(several entries on one day are allowed). `created_at` is when the entry was created and never changes; files
written by older versions of the app lack the column, and `updated_at` is used instead.

`resting-heart-rate.csv`: `uuid,measured_at,bpm,source,lowest_mean_bpm,coverage,rmssd_ms,deleted,updated_at,updated_by`, one
row per resting heart rate measurement, ordered by `measured_at`, then `uuid` (deleted rows first). `source` is `manual`
(typed by the user), `strap` (measured with a strap connected to the app) or `console` (measured through the treadmill
console). `lowest_mean_bpm` (the lowest 30-second mean of the measurement), `coverage` (fraction of the analysed seconds
with a valid reading, 0-1) and `rmssd_ms` (RMSSD of the RR intervals, milliseconds) are empty when unknown. Only valid
measurements are stored. The **reference** value is the median of the measurements of the last 30 days (with fewer than 3
it is provisional; with none in 30 days, the latest measurement); a measurement 7 bpm or more above the reference
computed without it makes the app suggest taking it easy. Deleted rows keep only `uuid`, `deleted` and the version. Same
rules as `weight.csv`: the last edit of a row wins.

`workouts/.../<time>.csv`: one row per sample (about one per second, repeated packets collapsed).

| column | content |
|---|---|
| `seq` | position of the sample in the workout |
| `timestamp` | ISO-8601 UTC with milliseconds |
| `elapsed_s` | treadmill session time in seconds (keeps counting during a pause) |
| `speed_kmh`, `target_speed_kmh` | real belt speed; requested speed (empty if unknown) |
| `incline_pct` | incline in percent |
| `distance_m`, `kcal` | accumulated distance and energy as reported by the treadmill |
| `hr_bpm` | heart rate, empty without a monitor |
| `belt_status` | `idle`, `starting`, `running`, `paused` or `safety_key_removed` |
| `stale` | `true` if the values were not current (frozen data) |
| `elevation_gain_m` | cumulative climb computed from incline and distance |
| `hr_source` | where `hr_bpm` came from: `direct_strap` (strap connected to the app), `treadmill_console`, or empty |
| `rr_ms` | RR intervals (ms between heart beats) received since the previous row, separated by `;`; empty without a strap. No interval is repeated or left out between rows |

Files written before the last two columns existed lack them.
`workouts/.../<time>.json`: `uuid`, `user_uuid`, `started_at`, `ended_at`, `end_reason` (`stopped`,
`safety_key_removed`, `connection_lost`, `disconnected`, `closed`, `interrupted`) and `stats` (`duration_s`,
`distance_m`, `calories`, `avg_speed_kmh`, `max_speed_kmh` (both rounded to 2 decimals), `avg_hr_bpm`, `max_hr_bpm`,
`max_incline_pct` (1 decimal)), plus, when the workout has a usable heart rate, `peak_hr_bpm`, `peak_hr_source` and
`peak_hr_at`: the highest 5-second mean heart rate of the workout (impossible readings, such as a fall of 25 bpm in 5
seconds when the strap is taken off, are discarded first), the source of its readings and when it was reached.

A workout done for a program or a fitness test also has `program_id` and `program_name` (what the program was called
then), `fitness_test_id` (the file `fitness-tests/<id>.json`) and `plan`: the plan that was executed, a self-contained
copy (the blocks with absolute speed in km/h and incline in %, the start parameters chosen and the warnings), so it
stays meaningful if the program is later edited or deleted. Workouts without these fields are ordinary workouts.
A workout done with a personalised (generated) program also has `generator_id` (which generator made the program) and
`session_record`: how the session went (outcome, manual speed reductions, per block the planned and final values and the
heart rate at the end, and where the heart rate settled in heart-rate blocks), which the generators read to adapt the
next session. Heart-rate blocks run with a chest strap connected to the app may also carry `alpha1Reductions` (load
reductions made by the real-time DFA-α1 brake; absent when the brake could not act in the block), `alpha1Mean` and
`alpha1Windows` (mean DFA-α1 of the valid 2-min windows evaluated in the block, and how many).

`programs/<uuid>.json`: `uuid`, `user_uuid`, and the program itself under `program` (a free or personalised program:
blocks that end by time or distance, with absolute or relative speed and incline; standard programs are built into the
app and are not stored here). A personalised program carries its `origin` (generator, version and the inputs it used).
In an absolute target (also the `start` of a heart-rate target), an absent `speedKmh` or `inclinePercent` means "keep":
the block starts with the value the treadmill has at that moment (never in the first block). A program that uses it
has `schemaVersion` 2, and the plan copy of a workout done with it has `planSchemaVersion` 2, with `keepsSpeed` /
`keepsIncline` next to the expected value.

`fitness-tests/<id>.json`: `uuid` (the test id), `user_uuid`, and the result under `result`: validity and flags, the
inputs (age, sex, weight, maximum heart rate), the outputs (VO2max, maximal aerobic speed, measured maximum heart rate,
classification), the measurements and every minute of the test. Tests that are not valid are kept too (they do not count
in the fitness profile). The inputs name the source of the heart rate (`directStrap` or `treadmillConsole`).

The result has its own `schemaVersion` (1 or 2). A result is written with the lowest version that holds it, so a document
with nothing new stays at 1 and older apps keep reading it; apps that know a lower version reject a higher one and keep the
file untouched. **Version 2** (added in October 2026) adds:

- The **maximal walking test** (`testType` `maxHeartRateWalk`, a modified Balke ramp: +1 % incline per minute until
  exhaustion) now has `outputs` when it is valid: `method` `acsmWalkRamp` (the ACSM walking equation, running above
  7.5 km/h, at the mean real speed and incline of the last 60 seconds of the effort, from the workout samples; from the
  stage records when the workout is not available), `vo2maxSeeMlKgMin` 3.5 (an assumption), `vo2maxIsLowerBound`,
  `classification`, `hrMaxCandidateBpm`, `effort`, and `masKmh` = VO2max / 3.5 with **`masIsEstimated: true`**: the maximal
  aerobic speed of the walking test is an estimate, only the maximal running test measures it (its `masKmh` has no
  `masIsEstimated`). Walking results written before version 2 have no `outputs`, only the measured maximum heart rate in
  the measurements, until the app recalculates them.
- **`thresholds`**, on the valid maximal tests (walking and running): the heart rates of the first and second ventilatory
  thresholds (VT1 and VT2) found with the DFA-alpha1 of the RR intervals of the test, from the start of the warm-up to the
  end of the effort (alpha1 = 0.75 for VT1, 0.5 for VT2, from the windows of 120 s where alpha1 falls as the heart rate
  rises; algorithm version 1 looked at the ramp only). It needs RR intervals from a strap connected to the app
  (`hr_source` `direct_strap` in the workout csv); the console's heart rate and imported workouts have none.

  ```
  "thresholds": {"algorithmVersion": 2, "quality": "high",
    "vt1": {"heartRateBpm": 132.4, "speedKmh": 6.2, "inclinePercent": 11.3, "vo2MlKgMin": 31.6, "percentVo2max": 72.5,
            "percentHeartRateReserve": 61.0},
    "vt2": {"heartRateBpm": 151.2, "...": "same fields"},
    "metrics": {"stretchWindows": 31, "validWindows": 48, "totalWindows": 50, "rSquared": 0.82,
                "meanArtifactPercent": 1.2, "slopePerBpm": -0.021, "interceptAlpha1": 3.53}}
  ```

  `algorithmVersion` is the version of the algorithm that produced the block. `quality` is `high`, `medium` or `low` (low
  detections are kept but do not count in the profile). `vt1`/`vt2` are points: `heartRateBpm`, and the load there
  (`speedKmh`, `inclinePercent`), its oxygen demand `vo2MlKgMin` (ACSM), `percentVo2max` and `percentHeartRateReserve`
  (the last one only when the user's resting heart rate is known). VT2 is never given without VT1. `metrics` describes the
  detection: valid windows in the stretch used (`stretchWindows`), in the whole analysed part (`validWindows`) and in total
  (`totalWindows`), the `rSquared` of the alpha1-heart-rate regression, the mean share of corrected beats
  (`meanArtifactPercent`), `slopePerBpm` and `interceptAlpha1`. Without thresholds the block holds `algorithmVersion` and
  a `reason`: `noWorkoutSamples` (the workout of the test is not available: deleted or not synchronised yet), `noRrIntervals`
  (no RR intervals from a strap connected to the app), `notEnoughValidWindows`, `noAlpha1Decline` or `vt1OutOfRange`
  (VT1 was not crossed in the test); `quality` and `metrics` may then still be present. A missing block means the result
  has not been analysed (it was written by an app that did not know the analysis).

Derived values are recalculated, not trusted: when the app finds a valid maximal result without `thresholds`, with an older
`algorithmVersion`, or marked `noWorkoutSamples` while its workout (the workout whose `fitness_test_id` is the result's id)
is now available, it computes them again from that workout's samples and rewrites the result (`updated_at` and
`updated_by` change; the measured data in the result do not, except the peak heart rate of a maximal test: since
version 2 it is the highest 5-second mean from 2 minutes before the end of the effort to the end of the test, the
seconds after the exhaustion included, so `peakHeartRateBpm`, `hrMaxCandidateBpm`, `effort` and the flags that explain
it may change; the peak never goes down). Stale samples are ignored. The resting heart rate used for
`percentHeartRateReserve` is the reference of `resting-heart-rate.csv` as it was at the start time of the test (only the
measurements up to then; without any, the earliest one), so every device that recalculates the same result from the same
data writes the same document. A result with the current `algorithmVersion` is never recalculated again.

## Deletions (tombstones)

Nothing is removed silently: a deletion is a record that says so, so that every device learns about it.

- A deleted **workout** keeps its `.json` with `"deleted": true` (only identity, start time and version); its `.csv`
  is removed.
- A deleted **weight entry** stays in `weight.csv` as a row with `deleted` = `true` and no date or weight.
- A deleted **resting heart rate measurement** stays in `resting-heart-rate.csv` as a row with `deleted` = `true` and no
  values.
- A deleted **program** keeps its `.json` with `"deleted": true` and no `program`.
- A deleted **fitness test** keeps its `.json` with `"deleted": true` and no `result`; the `fitness` block of
  `profile.json` no longer counts it.
- A deleted **user** keeps only `profile.json` with `"deleted": true` and the last name; everything else in the
  folder is removed. A deleted user is never restored.

A file that merely disappears from the repository is not a deletion: devices keep their copy.
""".trimIndent() + "\n"
}

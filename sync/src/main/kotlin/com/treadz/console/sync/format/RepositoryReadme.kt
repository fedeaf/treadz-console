package com.treadz.console.sync.format

/** The `README.md` placed at the root of the data repository the first time the app writes to it. */
internal object RepositoryReadme {

    val TEXT = """
# TreadZ Console data

Data of the [TreadZ Console](https://github.com/fedeaf/treadz-console) app, synchronised between the devices that
use this repository. The app writes here; you can read (and analyse) everything with plain `git`.
Format version: 1.

## Layout

```
users/<name>-<uuid>/profile.json
                    weight.csv
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
`max_hr_bpm` is the maximum heart rate the user entered; `max_hr_set_at`, present only when known, is when it was
entered or last confirmed (values entered before the field existed lack it and count as older than any fitness test). When there is no manual value the app uses the maximum heart
rate measured by a maximal fitness test and, failing that, the Tanaka estimate `208 - 0.7 * age`.

`profile.json` may also contain a `fitness` block: the user's fitness profile (combined VO2max, maximal aerobic speed
and measured maximum heart rate, with the tests it was computed from and the weighting rules), derived from the
`fitness-tests` of the user. It is **read-only**, there for people and for analysis: the app ignores it on reading and
computes it again on every device from the fitness tests, so every device writes exactly the same block. Its
`computedAt` is the start time of the newest valid test, not the time it was written: results are weighted by their
age relative to that test. The file keeps the profile's own `updated_at`/`updated_by` when only the block changes.

`weight.csv`: `uuid,date,weight_kg,deleted,updated_at,updated_by,created_at`, one row per weight entry, ordered by
`date`, then `created_at`, then `uuid`. The last row that is not deleted is the user's current weight, the same on every device
(several entries on one day are allowed). `created_at` is when the entry was created and never changes; files
written by older versions of the app lack the column, and `updated_at` is used instead.

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

`workouts/.../<time>.json`: `uuid`, `user_uuid`, `started_at`, `ended_at`, `end_reason` (`stopped`,
`safety_key_removed`, `connection_lost`, `disconnected`, `closed`, `interrupted`) and `stats` (`duration_s`,
`distance_m`, `calories`, `avg_speed_kmh`, `max_speed_kmh` (both rounded to 2 decimals), `avg_hr_bpm`, `max_hr_bpm`,
`max_incline_pct` (1 decimal)).

A workout done for a program or a fitness test also has `program_id` and `program_name` (what the program was called
then), `fitness_test_id` (the file `fitness-tests/<id>.json`) and `plan`: the plan that was executed, a self-contained
copy (the blocks with absolute speed in km/h and incline in %, the start parameters chosen and the warnings), so it
stays meaningful if the program is later edited or deleted. Workouts without these fields are ordinary workouts.

`programs/<uuid>.json`: `uuid`, `user_uuid`, and the program itself under `program` (a free or personalised program:
blocks that end by time or distance, with absolute or relative speed and incline; standard programs are built into the
app and are not stored here).

`fitness-tests/<id>.json`: `uuid` (the test id), `user_uuid`, and the result under `result`: validity and flags, the
inputs (age, sex, weight, maximum heart rate), the outputs (VO2max, maximal aerobic speed, measured maximum heart rate,
classification), the measurements and every minute of the test. Tests that are not valid are kept too (they do not count
in the fitness profile).

## Deletions (tombstones)

Nothing is removed silently: a deletion is a record that says so, so that every device learns about it.

- A deleted **workout** keeps its `.json` with `"deleted": true` (only identity, start time and version); its `.csv`
  is removed.
- A deleted **weight entry** stays in `weight.csv` as a row with `deleted` = `true` and no date or weight.
- A deleted **program** keeps its `.json` with `"deleted": true` and no `program`.
- A deleted **fitness test** keeps its `.json` with `"deleted": true` and no `result`; the `fitness` block of
  `profile.json` no longer counts it.
- A deleted **user** keeps only `profile.json` with `"deleted": true` and the last name; everything else in the
  folder is removed. A deleted user is never restored.

A file that merely disappears from the repository is not a deletion: devices keep their copy.
""".trimIndent() + "\n"
}

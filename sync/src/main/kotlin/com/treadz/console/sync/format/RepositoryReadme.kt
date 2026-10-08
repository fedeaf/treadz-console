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
```

- One folder per user. `<name>` is the lower-case ASCII form of the profile name (readable, may change); `<uuid>`
  identifies the user for good. If the name changes, the folder is renamed.
- Workout files are named after the start time of the workout in the local time of the device that recorded it.
- Every record has a `uuid`, an `updated_at` time (ISO-8601, UTC) and an `updated_by` device id. When two devices
  change the same record, the latest `updated_at` wins (ties: the greater `updated_by`).

## Files

`profile.json`: `name`, `sex` (`male`/`female`), `height_cm`, `birth_date`, `resting_hr_bpm`, `max_hr_bpm`,
`fitness_level`, `goal`, `target_weight_kg`, `created_at`. Unset optional values are `null`.

`weight.csv`: `uuid,date,weight_kg,deleted,updated_at,updated_by`, one row per weight entry, ordered by date.

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
`distance_m`, `calories`, `avg_speed_kmh`, `max_speed_kmh`, `avg_hr_bpm`, `max_hr_bpm`, `max_incline_pct`).

`programs/<uuid>.json`: `uuid`, `user_uuid`, and the program itself under `program`.

## Deletions (tombstones)

Nothing is removed silently: a deletion is a record that says so, so that every device learns about it.

- A deleted **workout** keeps its `.json` with `"deleted": true` (only identity, start time and version); its `.csv`
  is removed.
- A deleted **weight entry** stays in `weight.csv` as a row with `deleted` = `true` and no date or weight.
- A deleted **program** keeps its `.json` with `"deleted": true` and no `program`.
- A deleted **user** keeps only `profile.json` with `"deleted": true` and the last name; everything else in the
  folder is removed. A deleted user is never restored.

A file that merely disappears from the repository is not a deletion: devices keep their copy.
""".trimIndent() + "\n"
}

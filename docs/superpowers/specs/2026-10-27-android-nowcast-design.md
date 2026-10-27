# Rain Nowcast for Android — design

Fork of `ha-rain-nowcast` (branch `android`). The forecast runs **on the phone**;
the only network traffic is radar frames (ORD/OPERA) and the 48 h outlook
(Open-Meteo). The Python add-on stays in the tree as the reference
implementation and scoring harness.

## Layout

```
rain-nowcast-android/
  app/                      Android app (Kotlin, Compose), minSdk 26
  rain-nowcast/             existing Python add-on = reference + backtest
    (twin of Ensemble.kt: local_weather_model/nowcast/stepsport.py)
  regions.json              shared by app and add-on
  docs/superpowers/specs/
```

## Engine (`app/.../engine/`)

| File | Mirrors | Job |
|---|---|---|
| `Radar.kt` | `live.py` | ORD listing + download (disk cache, pruned >3 h), jHDF read, crop to region bbox, dBZ→mm/h (Marshall-Palmer, as pysteps) |
| `Ensemble.kt` | pysteps `steps` | **faithful STEPS port** (decision 2026-10-27, after STEPS-lite failed gate 0): pure-Kotlin dense Lucas–Kanade motion; mixed-radix FFT (`Fft.kt`, native grid size) Gaussian-bandpass cascade (6 levels, `BASELINE_CONFIG`), AR(2) per level from the last 3 frames, nonparametric noise filter, BPS velocity perturbations, incremental mask, CDF probability matching, semi-Lagrangian advection — pysteps defaults otherwise; 10 members, 12 × 5 min leads. Python twin `local_weather_model/nowcast/stepsport.py` defines the exact algorithm |
| `Model.kt` | `mlmodel.py` | SmaAt-UNet via onnxruntime-android; same transforms, pad-to-16, non-finite / >500 mm/h rejection |
| `Combine.kt` | `combine.py` | `out_i = clip(steps_i + w·(ml − mean))`, per-lead weights |
| `Point.kt` | `_point_state`, `interpolate.py`, `_summary` | 1-min point curves along motion, member probability, P10/P90 + min/max, summary text counted from *now* |
| `Render.kt` | `render.py` | Mercator resample to 8-bit level bitmaps, palettes |
| `Regions.kt` | new | load bundled + user `regions.json`, pick region for a point |
| `Outlook.kt` | new | Open-Meteo ensemble fetch, 30-min bars, nowcast blend, optional local model |

Cycle: list frames → skip if newest unchanged → download/decode/crop → flow →
ensemble → model + combine (fallback: ensemble alone) → home point → render.
Ensemble is held in memory (float32, ~59 MB) so map taps re-trace points only.

Scheduling: WorkManager every 15 min (refresh + "rain in N min" notification);
foreground every 5 min while the app is visible. Outlook fetch hourly.

### Errors
- Model failure → ensemble-only, shown once in UI, not retried until restart.
- Network / 429 → keep last good forecast, label "radar N min old"; never present stale as current.
- No API key → first-run screen with link to meteogate.eu.
- Outlook unavailable → "Outlook unavailable, last fetched HH:MM"; never fabricated.

## Regions & models

```json
{"id":"be","name":"Belgium","bbox":[2.0,49.0,6.8,51.8],
 "source":"opera","model":"smaat.onnx","weights":[0.59, 0.59, 0.62, …, 0.69],
 "outlook":"icon_d2"}
```

- Bundled: BE, NL, LU, DE-NRW, FR-Nord — all OPERA + `smaat.onnx`.
- Active region = the one containing home or the tapped point.
- `source` is a `when` in `Radar.kt`; `opera` only for now.
- Missing/unknown model → `smaat.onnx` with a "model not trained on this region" note.
- SmaAt was trained on Belgium; backtest scores each region separately before any claim.

## UI (Jetpack Compose, Material 3, system dark/light, dynamic colour ≥ Android 12)

Bottom navigation, three screens, plus a Glance widget.

**Now**
- Segmented toggle **Nowcast | Next 48 h**, last choice remembered.
- *Nowcast*: summary sentence, rain-chance %, current rate, radar age, region; Canvas
  graph −60…+60 min — mean line, P10–P90 band, min/max lines, now marker, probability
  strip. Pull to refresh.
- *Next 48 h*: 96 bars × 30 min; height = expected mm, colour = probability, P10–P90
  whisker, midnight separators; tap → "Tue 14:00–14:30 · 60% · 0.4–1.8 mm"; outlook
  sentence. 0–60 min comes from the nowcast so it never contradicts the other tab; from
  60 min the outlook (gate 2 result, below).

**Map** — MapLibre Android, OSM raster basemap, forecast overlay as image source;
timeline slider past→future, play/pause, palette picker; tap → bottom sheet with that
point's graph + summary and "Set as home".

**Settings**
- API keys per source (EncryptedSharedPreferences).
- Home: pick on map or one-off GPS.
- Region: auto / manual.
- **Regions editor**: add/edit/delete; bbox drawn on map or typed; source picker;
  **upload own `.onnx`** via system file picker → copied to app storage → validated by
  a test forward pass (12→12 frames, finite, ≤500 mm/h) before it is accepted; optional
  per-lead weights (default bundled).
- Notifications: lead time, probability threshold; background refresh on/off; use ML model on/off.

**Widget** — summary sentence + next-hour probability bar.

## 48 h outlook

Source: the local model (below) on Open-Meteo forecast API deterministic ICON-D2,
ICON-EU and IFS; fallback ICON-D2-EPS (20 members, 2 km, 48 h). Hourly data, so each
30-min bar is half its hour. Probability = share of members > 0.1 mm per 30 min; spread = P10/P90.

Nowcast join: **switch, not blend** — nowcast for the first hour, outlook from +1 h.
Gate 2 (`PREREGISTRATION_OUTLOOK2.md`, 2026-10-27) tested a quantile blend at hours 1–3
and FAILED (margin 0.0041 < 0.005; hour 3 worse than outlook alone). Hourly CRPS,
nowcast / outlook / blend: h1 0.146 / 0.179 / 0.128, h2 0.416 / 0.225 / 0.220,
h3 0.390 / 0.135 / 0.147. Pre-registered fallback: switch where train CRPS crosses
(between h1 and h2). Nowcast in that test was STEPS-lite; a first-hour-only blend is a
candidate for a new pre-registration.

### Local model — tried, gated: **shipped** (result 2026-10-27)
Trained and scored in the research repo (`local_weather_model/nowcast/outlook*.py`),
where the radar archive and venv live; exported to `app/src/main/assets/outlook/`.

- **Truth:** OPERA CIRRUS radar, hourly totals at a 7×7 grid of points (48 km apart)
  over the Belgium crop. 12 809 radar hours: the seeded archive sample, every Dec–May
  day 2024/25 + 2025/26, and every day 2026-07-26 → 10-26 (range-request harvest).
- **Inputs:** Open-Meteo Previous Runs API — ICON-D2, ICON-EU, IFS deterministic, at the
  freshest run (~0–3 h) and the run ~24 h earlier. Ensemble *members* are not archived
  (~3 days only); ensemble *mean + spread* are, from 2026-07-26.
- **Split by time:** train < 2026-07-26, test ≥ 2026-07-26.
- **Baselines:** ICON-D2-EPS and WeatherNext 2 mean/spread, each as a gamma with that
  mean and variance (assumed shape — members unavailable).

| CRPS (mm/h) | lead ~0–3 h (93 days) | lead ~24 h (31 days) |
|---|---|---|
| ICON-D2-EPS | 0.1720 | 0.2314 |
| WeatherNext 2 | 0.2199 | 0.2488 |
| quantile mapping | 0.2418 — FAIL | 0.4685 — FAIL |
| **LightGBM (9 quantiles)** | **0.1666** (+0.0053) | **0.2180** (+0.0133) |

**Decision:** LightGBM passes (≥ 0.005 better than the best raw source at every lead) and
ships as `outlook_gbm.onnx` (8.3 MB, parity with LightGBM 2.7e-6). Used from **+3 h**
onward — the 0–3 h margin is at the floor and the nowcast covers it. Raw fallback is
ICON-D2-EPS (beats WeatherNext 2 at both leads); WeatherNext is not added.
Later challengers, all FAILED against v1 on Single Runs at true leads 3–48 h (test runs
2026-07-26 → 10-26): v2 with five models (AIFS, HARMONIE added) + neighbourhood features
0.1983 vs v1 0.1980 (gate 1); a CRPS-trained MLP 0.2068 vs 0.1978 (`PREREGISTRATION_IDEAS3.md`).
v1 stays.
The app sends the features listed in `outlook_gbm.json`, sorts the 9 outputs and clips
at 0. **Unverified:** winter (test months are Jul–Oct) and real-member baselines.

## Validation
- `stepsport.py` (NumPy twin of `Ensemble.kt`) ports pysteps 1.21.5 `steps` as the add-on
  calls it. Its selfcheck runs real pysteps with the twin's motion and random numbers and
  gets the same members (max |Δ| 1e-13 dB). Deliberate differences, for Kotlin parity
  only: motion is `lite.motion` (dense LK, last 3 frames) instead of OpenCV LK; SplitMix64
  streams instead of numpy RandomState; stable sort in probability matching; 10 members,
  not 12; an all-dry incremental mask stays 0 instead of NaN.
- Gate (`PREREGISTRATION_STEPSPORT.md`, `stepsporttest.py`, one look): 20 CIRRUS
  validation wet windows, both shifted toward SmaAt — need `crps(port) <= crps(steps) +
  0.012` (CIRRUS noise floor) and wet bias ≤ 1.15. **PASS 2026-10-27:** port 0.3172 vs
  STEPS 0.3230 (port lower by 0.0057), wet bias 1.099 (STEPS 1.106), port lower on 13/20.
  STEPS-lite had failed the same gate (0.3713, wet bias 1.46). Reference Python is the
  research repo's `rain-nowcast/nowcast/` (the fork's copy is stale).
- Kotlin/Python parity JVM test (`EnsembleTest`, `stepsport_fixture.json`, 45×52 grid):
  FFT, filters, cascade, AR parameters, noise, mask and probability matching at 1e-9;
  float32 members at 1e-7; AR(1) and dry-input paths; same result for any thread count.
  JVM: 10 members × 12 leads on 338×364 in 1.9 s on 12 threads (6.9 s on one).
- Instrumented AVD test (`EngineOnDeviceTest`, API 36 x86_64, 4 cores, 192 MB heap): one
  full cycle on the bundled ODIM sample, no network — 23.5 s with SmaAt. The ensemble
  caps its threads by free heap (four members in flight overflowed 192 MB).
- adb screenshots of each screen on an x86_64 AVD (SDK installed to `~/Android`).

## Out of scope
- National radar feeds (KNMI, DWD, Météo-France) — region `source` leaves room.
- 48 h data on the map (needs gridded GRIB).
- Regions outside OPERA coverage.

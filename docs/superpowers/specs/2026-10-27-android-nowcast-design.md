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
    nowcast/lite.py         NumPy twin of Ensemble.kt (new)
  regions.json              shared by app and add-on
  docs/superpowers/specs/
```

## Engine (`app/.../engine/`)

| File | Mirrors | Job |
|---|---|---|
| `Radar.kt` | `live.py` | ORD listing + download (disk cache, pruned >3 h), jHDF read, crop to region bbox, dBZ→mm/h (Marshall-Palmer, as pysteps) |
| `Ensemble.kt` | new, replaces STEPS | OpenCV DIS flow on last 3 frames; 10 members = perturbed motion + semi-Lagrangian advection + spatially correlated log-normal growth/decay noise, 12 × 5 min leads |
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
  sentence. First 2 h blended from the nowcast so it never contradicts the other tab.

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

Nowcast join: for 0–3 h, point curves blended nowcast→NWP with a lead-dependent weight
set where the two skills cross (measured, not tuned).

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
The app sends the features listed in `outlook_gbm.json`, sorts the 9 outputs and clips
at 0. **Unverified:** winter (test months are Jul–Oct) and real-member baselines.

## Validation
- `lite.py` (NumPy twin of `Ensemble.kt`) scored by `backtest.py` vs STEPS on the same
  archive windows (CSI, CRPS). Gate: |dCRPS| ≤ 0.005, or a documented accepted loss.
- Kotlin/Python parity JVM test on a fixed seeded input, fields within tolerance.
- Instrumented AVD test: one full cycle on bundled sample ODIM files, no network.
- adb screenshots of each screen on an x86_64 AVD (SDK installed to `~/Android`).

## Out of scope
- National radar feeds (KNMI, DWD, Météo-France) — region `source` leaves room.
- 48 h data on the map (needs gridded GRIB).
- Regions outside OPERA coverage.

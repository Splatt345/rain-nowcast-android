# Train the models for your own region

The app and add-on ship models trained on Belgium. They run anywhere in Europe,
but outside Belgium they are guessing: the nowcast model has never seen your
weather, and the outlook model is switched off outside its training area. This
guide trains both models for any region, using the same code and checks that
produced the Belgian ones.

| model | what it does | trained on | needed? |
|---|---|---|---|
| **nowcast** (SmaAt-UNet, `smaat.onnx`) | next hour, minute by minute, from the last hour of radar | ~150 days of radar over your region | no: without it the app uses the Belgian model, with a note |
| **outlook** (LightGBM, `outlook_gbm.onnx`) | 2 to 48 hours, from three weather models | Open-Meteo forecasts compared against radar | no: without it the app shows an ensemble (ICON-D2-EPS in Belgium) |

The region is set in one place. `python -m nowcast.region --init` writes
`regions/<name>.json`, and every command run with `NOWCAST_REGION=<name>` reads
it. Data for the region goes to `data/regions/<name>/`. Without the variable,
everything stays Belgian and nothing already in `data/` is touched.

## Before you start

- **The research environment:** `HANDOVER.md` §9 (Python 3.10, pysteps, torch).
  Check it with `.venv/bin/python selfcheck.py`.
- **A GPU** with 8–12 GB of memory for training. The Belgian model trained on an
  RTX 4070. A CPU can run steps 1, 2 and 5, but training takes days instead of hours.
- **Bandwidth and disk:** each radar day is a ~780 MB download, cropped to
  ~11 MB on disk. 150 days is about 120 GB downloaded, 1.7 GB kept, and about two
  hours of harvesting.
- **No API key.** The OPERA archive and Open-Meteo's archives are open.

## 1. Check the region

Pick a centre and a short name (letters, digits and `-`):

```bash
.venv/bin/python -m nowcast.region --init lisbon --lat 38.9 --lon -8.6
```

This checks, against live data:

- **Radar coverage.** The model works on a 384 × 384 km square around the
  centre. The command reads one recent OPERA frame there and reports how much of
  the square has radar. Sea far from the coast and the edge of radar range have
  none. Below 90 % it lowers the threshold for keeping frames; below about 70 %,
  move the centre inland.
- **Weather models.** It picks the first three Open-Meteo models with an archive
  there since August 2024. Local high-resolution models come first (ICON-D2,
  AROME, HARMONIE, UKV), then global ones (ICON-EU, IFS, GFS). It also picks the
  ensemble the app falls back on, and the archived ensemble means the outlook
  must beat.

It writes `regions/lisbon.json` and prints the steps below with your region's
name in them. Print them again at any time with
`NOWCAST_REGION=lisbon .venv/bin/python -m nowcast.region`.

**Choosing the centre.** Rain usually comes from the west in Europe, so put the
place you care about east of centre, with 100–150 km of radar upwind of it. You
can edit `centre` in the JSON file and rerun `--init`, but only before step 2:
moving the square afterwards invalidates the data.

An example output, for Madrid (2026-10-28):

```json
{"name": "madrid", "centre": [40.4, -3.7],
 "det_models": ["meteofrance_arome_france", "icon_eu", "ecmwf_ifs025"],
 "ensemble": "icon_eu",
 "baselines": {"dwd_icon_eu_eps": "...", "google_weathernext2": "...", "ecmwf_ifs025": "...", "ncep_gefs025": "..."},
 "min_finite": 0.9}
```

## 2. Download radar

```bash
export NOWCAST_REGION=lisbon
.venv/bin/python -m nowcast.archive --harvest --days 150
.venv/bin/python -m nowcast.archive --status
```

This draws 150 days at random (a fixed seed) from the OPERA CIRRUS archive
(5-minute, 1 km, August 2024 onward), so every season is covered. It can be
stopped and restarted. The frozen test days in `nowcast/cirrus_test_days.txt`
are never downloaded for training.

`--status` must show at least **10 000 training windows**, or training refuses
to run. Each full day gives about 265. If many days are skipped as incomplete,
coverage is the problem, not the code: see step 1.

## 3. Train the nowcast model, then check it

**First, write down what counts as a pass**, before any score exists. The
Belgian bar was: lower MAE than both "persistence" (the last frame, held still)
and "zeros" (always dry) at every lead time, on the validation days. In a dry
climate "zeros" is the harder of the two. Write the bar in a file, commit it,
and do not change it after seeing a score (`PREREGISTRATION.md` shows how).

```bash
.venv/bin/python -m nowcast.smaat --train          # hours on a GPU; keeps the best epoch
.venv/bin/python -m nowcast.smaat --evaluate       # the held-out days, per lead
```

About 20 % of the days are held out, chosen by a hash of the date, so a day
never moves between training and validation however many you add. The Belgian
model scored, for comparison:

```
lead (min)      5     10     15     20     30     45     60
zeros       0.388  0.388  0.387  0.388  0.387  0.386  0.385
persistence 0.234  0.307  0.349  0.377  0.413  0.449  0.472
SmaAt-UNet  0.157  0.184  0.204  0.221  0.248  0.273  0.291
```

**If it fails, it fails.** Don't retune and re-score on the same days until it
passes. That only selects for luck on those days. Harvest more days (step 2
adds to the set without moving any), or keep the Belgian model.

If it passes, export it. The export checks that the ONNX file matches PyTorch
before writing it:

```bash
.venv/bin/python -m nowcast.smaat --export-onnx smaat-lisbon.onnx
```

## 4. Use it in the app

Copy `smaat-lisbon.onnx` to the phone. Then in **Settings → Regions and models
→ Add region**:

- **West / South / East / North:** the box `region` printed in step 4 of its
  list. It is the crop with 32 km trimmed off each side, which leaves room for
  rain to arrive from.
- **Upload your own .onnx**, pick the file, then choose it under **Model**. The
  app test-runs the file and refuses it unless it returns 12 finite frames of
  at most 500 mm/h.

The app uses the region whose box the home location is deepest inside. The
"trained on Belgium only" note disappears for that region.

For the Home Assistant add-on, replace `rain-nowcast/models/smaat.onnx` and
rebuild. The add-on has one model, so only do this if it serves one region.

## 5. The outlook model (optional)

This needs more data and passes less often. It is only worth it where the raw
ensembles are poor.

```bash
.venv/bin/python -m nowcast.outlook --fetch --fetch-means   # Open-Meteo, cached per point
.venv/bin/python -m nowcast.outlook --harvest               # every radar day since 2026-07-26
.venv/bin/python -m nowcast.outlook --harvest-cool          # every Dec–May day: winter and spring
.venv/bin/python -m nowcast.outlook --truth                 # radar -> hourly totals at 49 points
.venv/bin/python -m nowcast.outlook --train
```

Training uses every hour before 2026-07-26 and is scored on every hour after
that, at 49 points 48 km apart around the centre. The gate is fixed in the
code: CRPS lower than **the best archived ensemble** by at least 0.005, at both
lead times (about 0–3 h and 24–27 h). With no archived ensemble there, it must
beat the three models taken as a three-member ensemble instead.
`--train` prints `gate gbm: PASS` or `FAIL`.

**Ship it only on a PASS.** Export it into the Android source and rebuild the
app. The outlook is bundled, not imported:

```bash
python -m venv /tmp/onnxenv && /tmp/onnxenv/bin/pip install lightgbm==4.6.0 \
    onnxmltools==1.13.0 onnx==1.16.2 onnxruntime==1.23.2 numpy==2.2.6
NOWCAST_REGION=lisbon /tmp/onnxenv/bin/python nowcast/outlook_export.py \
    ~/Projects/rain-nowcast-android/app/src/main/assets/outlook
```

`outlook_gbm.json` now carries the region's models, its fallback ensemble and
the area it was trained on (`trained_bbox`). The app reads all three from the
file, so nothing in the Kotlin changes. Outside `trained_bbox` it shows the
ensemble instead, with a note: lat and lon are inputs to the model, and outside
its points it makes up rain. That happened with the Belgian model at Amsterdam.
One app build holds one outlook model.

## Outside Europe

The Android app reads live radar from OPERA only (`Validate.SOURCES`), so
outside Europe it needs a new live radar source before any model matters. That
is app work, not training.

Without radar, the app still works anywhere: **Settings → Next hour from →
Open-Meteo** uses Open-Meteo's 15-minute forecast models as a small ensemble
(five or more models answer anywhere on Earth). It needs no key and is free
for non-commercial use. It is model output, not observed rain, so nothing here
can be trained on it.

### Live radar elsewhere

Open sources found on 2026-10-28. Training needs months of past frames, so
"history" matters as much as the live feed. Where a source keeps only the last
few hours, you have to save frames yourself for a season before training.
"Checked" means the listing or file was fetched that day. The others come from
the sources' documentation or from
[LibreWXR](https://github.com/JoshuaKimsey/LibreWXR), which uses them all. Read
each licence before you publish anything built on it.

| where | source | product | grid, cadence | access | history | licence | checked |
|---|---|---|---|---|---|---|---|
| Europe | EUMETNET OPERA | CIRRUS composite, ODIM HDF5 | 1 km, 5 min | archive: anonymous S3 `openradar-archive`; live: MeteoGate key | Aug 2024 → (CIRRUS) | CC BY 4.0 | yes, what this project uses |
| USA, Alaska, Hawaii, Caribbean, Guam | NOAA MRMS | `PrecipRate_00.00`, GRIB2 (gzip) | 0.01° (~1 km), 2 min | anonymous S3 `noaa-mrms-pds`, `CONUS/PrecipRate_00.00/YYYYMMDD/` | Oct 2020 → | US government, open | yes: listing from 2020-10-14 |
| Canada and the USA | ECCC MSC GeoMet | `RADAR_1KM_RRAI` rain rate, mm/h | 1 km, 6 min | WMS images (`geo.weather.gc.ca`), no key | last 3 h | ECCC end-user licence | docs only |
| Japan | JMA | high-resolution precipitation nowcast (`hrpns`), PNG map tiles | 5 min, colours to decode | `jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json`, then tiles | about 3 h | Public Data License 1.0 (CC BY 4.0 compatible), cite JMA | yes: times and a tile fetched |
| Taiwan | CWA | `O-A0059-001` composite reflectivity, JSON/XML | ~1.4 km, 10 min | anonymous S3 `cwaopendata`, `Observation/O-A0059-001.json` | latest frame only | Open Government Data Licence 1.0 | yes: one file, overwritten |
| Malaysia, Brunei, Singapore | MET Malaysia | 12-radar composite, animated GIF | 1 km CAPPI, 10 min | `api.met.gov.my`, anonymous | 6 frames | CC BY 4.0 | per LibreWXR |
| Philippines | PAGASA | 9-radar mosaic, PNG | 15 min | `cdn.panahon.gov.ph`, anonymous | short timeline | public domain (RA 8293 §176) | per LibreWXR |
| El Salvador | MARN | one radar, colour images | 5 min | anonymous Google Cloud Storage | short | MARN open data, attribution | per LibreWXR |
| Australia | Bureau of Meteorology | radar images, Rainfields | 5–10 min | `ftp://ftp.bom.gov.au/anon/gen/radar/` | none anonymous | personal use only; no republishing or commercial use without a Registered User agreement | docs only |
| Korea | KMA | HSR composite | 1 km | KMA API Hub, key required | not found | not found | not checked |
| anywhere 60° S – 70° N | NOAA Enterprise Rain Rate | satellite (infrared) rain rate, not radar | ~2–4 km, 10–15 min | anonymous S3 `noaa-enterprise-rainrate-pds` (`BLEND/`, `G16/`, `G18/`, `G19/`) | yes | NOAA open data | yes: bucket listed |

Notes:

- **MRMS is the only one outside Europe that is as good as OPERA here:** open,
  1 km, a real archive, and actual rain rates rather than colours. A US
  version of the app could train on it directly.
- **Image sources** (GeoMet, JMA, MET Malaysia, PAGASA) need their colour
  legend mapped back to mm/h before training. Each colour stands for a band
  of rates, so the model loses detail inside each band.
- **Satellite rain rate is a last resort.** Infrared rain rate does well on
  convective storms and badly on steady frontal rain. Its pixels are several
  km across, so it does not match the 1 km model.
- **Not found as open live data:** most of Africa, South America, China,
  India, New Zealand and the Middle East. National services there publish
  images on their own sites under terms that rarely allow reuse.

### Training on another radar

The training side only assumes a stack of radar frames, so any 5-minute,
~1 km composite works. Write one file per day to
`data/regions/<name>/opera_archive_5min/YYYYMMDD.npz` with:

| key | contents |
|---|---|
| `rain` | float32 `(frames, 384, 384)`, mm/h, NaN where there is no radar, row 0 at the north |
| `times` | ISO 8601 UTC strings, one per frame |
| `cadence_minutes` | `5.0`. Store it explicitly: ODIM metadata lies about it (`HANDOVER.md` §8) |
| `km_per_px` | `1.0` |

Clean the data the way `archive.read_frame` does: mask fill values, and clip
reflectivity at 55 dBZ (`live.clean_dbz`) before converting to rain rate.
Otherwise clutter shows up as 3000 mm/h. Sources that already give mm/h (MRMS)
skip the conversion but still need clipping at about 100 mm/h. Resample other
cadences to 5 minutes. For MRMS's 2-minute frames, take the frame nearest each
5-minute mark. The model was built for 12 frames of 5 minutes. Steps 3 and 4
then run unchanged.
`region --init` stops when the square leaves OPERA's grid. Write the region
JSON by hand in that case: `name`, `centre`, `det_models` and `ensemble`, with
model names from Open-Meteo's documentation.

## Rules that keep the numbers honest

These rules came from mistakes in this project, each recorded in `HANDOVER.md`:

- **Split by day, never by window.** Neighbouring windows share 23 of 24 frames,
  so a split by window leaks the answers into training.
- **Write the gate before the score**, and record failures too.
- **Validation days choose the epoch**, so the score on them is a validation
  number. For a real test number, score once on days used for nothing else.
- **Never pool eras.** CIRRUS (5 min, 1 km) and ODYSSEY (15 min, 2 km) are
  different products. Everything here is CIRRUS.

## When something goes wrong

| symptom | cause |
|---|---|
| every day "skipped (incomplete)" | under 83 % of a day's frames had enough radar coverage: move the centre inland, or lower `min_finite` |
| `train()` refuses: too few samples | under 10 000 windows: harvest more days |
| `--train` (outlook) has no test rows | the archived ensembles do not cover the points: rerun `--init` (it drops them), or remove `baselines` |
| app says "Outside the area the local outlook model was trained on" | home is outside `trained_bbox`: expected, the ensemble is shown |
| app refuses the imported model | wrong input/output shape or non-finite output: export with `--export-onnx`, not by hand |

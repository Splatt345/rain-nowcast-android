# ha-rain-nowcast

A Home Assistant add-on that shows live rain radar over Belgium and forecasts
the next hour, minute by minute.

Forked from `local_weather_model`, which is the research side of this:
figuring out *which* nowcasting method actually works and measuring how well.
This repo is that method packaged as something you can click on.

The bundled models are trained on Belgium. To train them for another region
and load them into the Android app, follow `docs/TRAIN_YOUR_REGION.md` in
`local_weather_model`; it starts with `python -m nowcast.region --init`.

- **Radar view** — the last hour of real radar, animated, on a real map, plus
  the next hour extrapolated forward. Scrub or let it loop, pick a colour
  scheme, and click anywhere on the map to forecast that spot instead.
- **Rain graph** — rain rate at your exact location, per minute, past hour
  through next hour, with a plain-language summary ("Rain starting in 25 min,
  up to 3.4 mm/h") and a band showing how much the forecast runs disagree.
- **Sensors** — eight entities (is it raining, minutes until rain, chance of
  rain, peak rate, expected total…) so automations can act before rain lands.

---

## Before you install

**Check two things, or it will not work.**

1. **Your Home Assistant machine must be x86-64 (amd64).** A Raspberry Pi will
   not work — `aarch64` is not built. The add-on simply will not appear in the
   store on an unsupported machine, which is deliberate: it used to be
   advertised and then fail at install time with nothing explaining why.
2. **You need a free API key.** The radar is EUMETNET's OPERA composite via the
   MeteoGate Open Radar Data API, and anonymous access is rate-limited to the
   point of being useless for a 5-minute poll. Register at
   <https://meteogate.eu/> (sign in with GitHub/Google/Azure AD, no approval
   wait) and keep the key for the `api_key` option.

Budget roughly **1.1 GB of disk** for the image and **0.5–0.7 GB of RAM** while
running.

---

## Install

### A. Home Assistant OS or Supervised — as a local add-on

No GitHub account needed, and this is the working path today. Get the
`rain-nowcast` folder onto the Home Assistant machine's `/addons` share — the
*Samba share* or *Advanced SSH & Web Terminal* add-ons both expose it — so that
`/addons/rain-nowcast/config.yaml` exists.

Then **Settings → Add-ons → Add-on Store → ⋮ → Check for updates**, and Rain
Nowcast appears under **Local add-ons**. Click it and **Install**. The first
build compiles pysteps from source and takes several minutes.

### B. Home Assistant OS or Supervised — from a Git repository

Push this repository somewhere Home Assistant can reach, then **Settings →
Add-ons → Add-on Store → ⋮ → Repositories**, paste the URL, and install Rain
Nowcast from the section that appears.

> This repository has no remote configured yet, so option A is the one that
> works right now. `git remote add origin …` and push first for option B.

### C. Home Assistant Container or docker-compose — no Supervisor

No add-on store here, so run the container directly. **You must set
`MQTT_HOST`**: with no Supervisor there is nothing to ask for the broker, and
the sensors would otherwise silently not publish.

```bash
docker build -t rain-nowcast rain-nowcast/

docker run -d --name rain-nowcast \
  -p 8061:8061 \
  -e ORD_API_KEY=your_meteogate_key \
  -e LATITUDE=50.85 -e LONGITUDE=4.35 \
  -e MQTT_HOST=192.168.1.10 -e MQTT_PORT=1883 \
  -e MQTT_USERNAME=homeassistant -e MQTT_PASSWORD=secret \
  -v rain_nowcast_data:/data \
  rain-nowcast
```

The panel is then at `http://<host>:8061` directly, rather than behind ingress.

---

## Optional: skip MQTT entirely

The add-on publishes sensors over MQTT discovery, which needs a broker. If you
would rather not run one, this repo also ships a **custom integration** that
polls the add-on over HTTP and creates the same eight entities natively.

1. Copy `custom_components/rain_nowcast` to your Home Assistant `/config/custom_components/`.
2. Restart Home Assistant.
3. **Settings → Devices & Services → Add Integration → Rain Nowcast**. On HA OS
   the default host `local_rain_nowcast` port `8061` is already right; elsewhere
   give the host running the add-on.
4. Set **`publish_sensors: false`** in the add-on, or you will get two copies of
   every entity and Home Assistant will suffix the second set with `_2`.

The entities are identical in name, unit and device class to the MQTT ones, so
automations written against either keep working. They are proper registry
entities: verified to survive a Home Assistant restart, and to go `unavailable`
rather than stale when the add-on stops.

> The add-on's third path - the Supervisor REST API, used automatically when
> there is no broker - needs no setup at all, but Home Assistant treats the
> states it creates as ephemeral: **they disappear on every restart** until the
> next radar frame. That is the limitation this integration removes.

## The dashboard card

The integration also ships a Lovelace card, so the radar lives on a dashboard
instead of behind the ingress panel. It needs the integration (above), because
the browser generally cannot reach the add-on directly - on Home Assistant OS
the add-on has no published port - so Home Assistant proxies the radar frames
under its own authentication.

Nothing to copy: the integration serves the card itself. Add the resource once,
at **Settings → Dashboards → ⋮ → Resources → Add**:

    URL   /rain_nowcast_static/rain-nowcast-card.js
    Type  JavaScript module

then add a card to any dashboard:

```yaml
type: custom:rain-nowcast-card
# all optional:
height: 320          # map height in px
show_chart: true     # the per-minute graph
palette: viridis     # blue-green (default), viridis or classic
entity: sensor.rain_nowcast_rain_summary
```

It has everything the panel has: animated radar with play/pause and a scrubber,
pan and zoom, the colour schemes, an opacity slider, the per-minute chart with
its uncertainty fan, and tap-the-map to forecast a different spot. Colour scheme,
opacity and the chosen point are remembered per browser.

## After installing

Set **`latitude`/`longitude`** to the spot you care about, paste the
**`api_key`**, and start it. The first run downloads an hour of radar (~35 MB)
before it can forecast anything, so give it a minute or two.

Sensors arrive over **MQTT discovery** and group under one *Rain Nowcast*
device. They survive a Home Assistant restart without waiting for the next
radar frame. With no broker at all, install the *Mosquitto broker* add-on
first — on HA OS the Supervisor then wires it up with nothing to configure.

Every option, all eight entities, an example automation and the honest limits of
the forecast are in [`rain-nowcast/DOCS.md`](rain-nowcast/DOCS.md), which is
also the add-on's Documentation tab.

---

## What's behind it

`pysteps` **STEPS**: optical-flow radar extrapolation with a 12-member
stochastic ensemble. The ensemble is the point — it is what `rain_chance` and
the uncertainty band are made of, and a single averaged field cannot produce
either.

Since v1.1 that ensemble is shifted toward a **SmaAt-UNet** neural network
(4.02 M parameters) trained here on 149 days of 5-minute 1 km OPERA radar. It is
combined rather than substituted, because head-to-head the network produces the
better single field but *loses* on CRPS — it is deterministic, and this add-on
serves a probability. Combining gained **+0.0167 CRPS on 25 of 30 validation
windows** against a pre-registered floor of 0.005. Inference runs on the CPU
through ONNX Runtime in about 0.25 s; no GPU, no cloud service.

Global AI weather models (GraphCast, Pangu-Weather, WeatherNext) are the wrong
tool here: 6h+ timesteps on 25–100 km grids. DeepMind's DGMR is the right
*class* of model, but the only public checkpoint produces physically impossible
output (over 3000 mm/h) and lost badly to pysteps head-to-head — 0.055 CSI
against 0.52.

## Accuracy

Measured on **20 independent Belgian events** (2021–2024, KNMI 1 km 5-minute
composites), scored only on the 10 never used for tuning:

| lead | 5 min | 15 min | 30 min | 45 min | 60 min |
|---|---|---|---|---|---|
| CSI@1mm/h | 0.72 | 0.55 | 0.41 | 0.31 | 0.25 |

Held-out mean **0.404**. In plain terms: **the first half hour is genuinely
useful, the second half hour is a hint.** That decay is the physical limit of
extrapolation, not an implementation flaw — it moves existing rain correctly
but cannot invent showers that have not formed yet.

It does not cry wolf: on 10 near-dry events it predicted wet pixels 23× *less*
often than they actually occurred. The failure mode is being too cautious.

Two limits worth knowing before automating on it, both measured and both
detailed in DOCS.md: `rain_chance` over-promises above about 40%, and at 5 mm/h
the forecast has essentially no skill at telling heavy rain from light.

## Layout

```
repository.yaml            Home Assistant add-on repository manifest
rain-nowcast/
  config.yaml  build.yaml  Dockerfile   add-on packaging
  DOCS.md                               user-facing docs
  models/smaat.onnx                     trained SmaAt-UNet, exported for serving
  web/index.html  web/vendor/           the UI (Leaflet map + canvas chart)
custom_components/rain_nowcast/          optional integration: same entities, no broker
  nowcast/
    server.py       background refresh loop + HTTP server
    publish.py      Home Assistant entities, over MQTT or the core REST API
    render.py       radar field -> Web-Mercator PNG overlay, point series
    live.py         fetch OPERA composites, crop to Belgium
    mlmodel.py      SmaAt-UNet inference via ONNX Runtime
    combine.py      spread-preserving combination of the two forecasts
    backtest.py     the shared STEPS core, plus offline scoring
```

## Development

```bash
python3 -m venv .venv
.venv/bin/pip install -r rain-nowcast/requirements.txt
cd rain-nowcast
ORD_API_KEY=... LATITUDE=50.85 LONGITUDE=4.35 ../.venv/bin/python -m nowcast.server
# http://localhost:8061
```

Every module carries its own self-check, and they need no network:

```bash
for m in render publish interpolate combine mlmodel server; do
  python -m nowcast.$m --selfcheck
done
```

## License

Copyright (C) 2026 the Rain Nowcast contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.

Third-party data and libraries keep their own licences: see the app's Settings → Open-source
licences (`app/src/main/assets/licenses.txt`).

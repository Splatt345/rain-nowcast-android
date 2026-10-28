# Store listing (Google Play, English)

**App name** (30 max): Rain Nowcast

**Short description** (80 max, this is 77):
Minute-by-minute rain for the next hour, worked out on your phone from radar.

**Category:** Weather · **Tags:** weather radar, rain alerts, forecast

**Contact email:** _your public support address_ (Play shows it on the listing)

**Privacy policy URL:** where you host `privacy-policy.md` (see README)

## Full description (4000 max)

Will it rain in the next hour, and when exactly? Rain Nowcast answers minute by minute, from the latest European weather radar.

The forecast runs on your phone. Every five minutes it takes the last hour of radar from EUMETNET OPERA (the network of Europe's national weather services), works out how the rain is moving and growing, and runs it forward an hour as an ensemble of ten possible futures, sharpened by a neural network trained on radar. You see the expected rain, the likely range and the chance of rain, not just one guess.

WHAT YOU GET
• Next hour, minute by minute: "Rain starting in 12 min (70% chance), up to 2.4 mm/h"
• A graph of the last hour of radar and the next hour of forecast, with the 80% range and the chance of rain
• The next 48 hours in half-hour bars, from the national weather models (ICON-D2, ICON-EU, IFS)
• A rain map: play the last hour of radar and the next hour of forecast, minute by minute; tap any place for its own forecast
• Rain alerts: "Rain in 15 min" at home, with your own warning time and chance threshold
• Five home-screen widgets: summary, next-hour graph, 48-hour bars, radar around home, and a combined one
• Follow your location, or set home on the map
• Light and dark themes, wallpaper colours, several radar colour scales

RADAR, OR NO KEY AT ALL
The radar comes from MeteoGate's Open Radar Data API, which needs a free personal key (meteogate.eu). Without a key, or outside Europe, the app uses Open-Meteo's forecast models instead: it works anywhere in the world, with no key.

COVERAGE
Ready-made regions: Belgium, the Netherlands, Luxembourg, North Rhine-Westphalia and Nord de France. Add your own region anywhere OPERA covers Europe; an in-app guide shows how to train a neural network for it.

PRIVATE BY DESIGN
No account, no ads, no tracking, no analytics. The forecast is computed on the device. Your location is used only to fetch the weather for where you are and is never stored by us.

Data: radar © EUMETNET OPERA via MeteoGate (CC BY 4.0); forecasts by Open-Meteo.com (CC BY 4.0); map © OpenFreeMap, OpenMapTiles, OpenStreetMap contributors.

## Graphics

| Asset | File | Play requirement |
|---|---|---|
| App icon | `graphics/icon-512.png` | 512 × 512 PNG |
| Feature graphic | `graphics/feature-1024x500.png` | 1024 × 500, no transparency |
| Phone screenshots | `screenshots/1-now.png` … `5-settings.png` | 2–8, 1080 × 2400 is fine |

The screenshots replay a real rainy hour of OPERA radar (3 May 2026); the 48-hour bars after the first hour are the live forecast of the day they were taken. `screenshots/take.sh` retakes them from a running app.

## Release notes for 1.0.0 (500 max)

First release: minute-by-minute rain for the next hour from live radar, a 48-hour outlook, a rain map, rain alerts and five widgets.

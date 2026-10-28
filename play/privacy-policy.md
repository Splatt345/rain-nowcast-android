# Rain Nowcast – Privacy policy

_Effective 28 October 2026. Developer: **your name** · Contact: **your support email**_

Rain Nowcast computes rain forecasts on your phone. It has no account, no ads, no analytics and no crash reporting, and the developer runs no server: nothing you do in the app is sent to the developer.

## What the app uses, and where it goes

**Your location.** If you allow it, the app reads your device's location to set "home". With "Follow my location" and "Allow all the time", it reads one location fix per background refresh (about every 15 minutes) to keep widgets and rain alerts right for where you are. You can instead set home by hand on the map, and the app works without location permission. Home is stored only on your device.

To get the weather for home, the app contacts these services directly from your phone:

| Service | What it receives | Why |
|---|---|---|
| **Open-Meteo** (open-meteo.com) | Home's coordinates (to about 10 m), your IP address, and your Open-Meteo API key if you entered one | Forecasts for the next 48 hours, and the next hour when Open-Meteo is the source |
| **MeteoGate** (meteogate.eu) | Your MeteoGate API key and your IP address; the radar image parts it sends back cover the area around home | Live radar for the next-hour forecast and the map |
| **OpenFreeMap** (openfreemap.org) | The map area you view and your IP address | Map tiles |

Each service handles this under its own privacy policy. The app sends no other identifiers, no advertising ID and no usage data.

**API keys** you enter are stored on your device only, encrypted with a key held in the Android Keystore, and are sent only to the service they belong to.

**Notifications** (rain alerts) are produced on your device.

## What stays on your device

Settings, home, the last forecast (so the app opens instantly), downloaded radar, any models you import, and your API keys. Uninstalling the app or clearing its data deletes all of it. The app does not back up its data to the cloud.

## Children

The app is not directed at children and collects no personal information from anyone.

## Changes

If this policy changes, the new version will be published at this address with a new effective date.

## Contact

Questions: **your support email**

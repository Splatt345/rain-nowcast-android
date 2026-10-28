# Play Console: "App content" answers

Answers for each form under **Policy and programs → App content**, matching what the code does (checked 28 Oct 2026).

## Privacy policy
The URL where `privacy-policy.md` is hosted.

## Ads
**No**, the app contains no ads.

## App access
**All or some functionality is restricted** → add instructions:

> No login. On the first screen, tap "No key? Use Open-Meteo's forecast instead" to use the app with no key (Now screen, 48-hour outlook, widgets, alerts).
> The radar features (next-hour radar forecast and the rain map) need a free personal MeteoGate key: enter it on the first screen, or in Settings → MeteoGate ORD key.

Optionally paste a MeteoGate key of your own into the Console's credentials fields so reviewers can see the radar. Only do this with a key you can revoke.

## Content rating (IARC questionnaire)
- Category: **Reference, News, or Educational** (a weather utility)
- Violence, sexuality, language, controlled substances, gambling: **No** to all
- User interaction / user-generated content shared with others: **No**
- Shares the user's location with other users: **No**
- Digital purchases: **No**

Expected rating: Everyone / PEGI 3.

## Target audience and content
Target age: **18 and over** (or 13+). Don't include under-13 groups: that brings in the Families policy, which the third-party services aren't certified for. Appeals to children: **No**.

## News app
**No**

## Health / Financial features / Government app
**No** to all.

## Data safety
**Does your app collect or share any of the required user data types?** Yes

**Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS only)

**Do you provide a way for users to request that their data is deleted?** No. The developer holds no user data; uninstalling deletes everything on the device. Explain this in the free-text field if Play asks.

Data types to declare:

| Type | Collected | Shared | Ephemeral | Required? | Purpose |
|---|---|---|---|---|---|
| Location → Approximate location | Yes | No | Yes | Optional (users can set home by hand) | App functionality |
| Location → Precise location | Yes | No | Yes | Optional | App functionality |

Why "collected, not shared": the coordinates leave the device to Open-Meteo, and the radar request reveals the area to MeteoGate. Play counts that as collection. Sending data to a service that provides the app's own function is not "sharing" under Play's definition.

Nothing else is collected: no personal info, no app activity, no device or other IDs, no crash logs, no diagnostics. API keys the user types in are credentials sent to the service they belong to, not a Play data type.

## Sensitive permissions → Location permissions declaration
Needed because the manifest requests `ACCESS_BACKGROUND_LOCATION`.

**Feature that uses background location:**
> Home-screen widgets and rain alerts ("Rain in 15 min") for the place the user is, refreshed every 15 minutes while the app is closed. Only when the user turns on Settings → "Follow my location" and then allows location "All the time".

**Why foreground location is not enough:**
> Widgets and alerts update from a background worker every 15 minutes while the app is not open, so they need the current location then.

**Video** (up to 30 s, unlisted YouTube link). Record on the emulator or a phone:
1. Settings → turn on **Follow my location** → Android's location prompt → allow "While using the app".
2. The app's **"Follow you in the background?"** dialog (the prominent disclosure) → **Continue**.
3. Android's location settings → **Allow all the time**.
4. Back to the home screen: a rain widget showing the forecast for the current place.

If Play rejects the declaration, the fallback is to remove `ACCESS_BACKGROUND_LOCATION` from the manifest. Widgets then use the last home set while the app was open (`Locator.canInBackground` already handles that case).

## Foreground service, exact alarms, photo/video, SMS/call log
Not used.

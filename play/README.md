# Publishing Rain Nowcast on Google Play

Everything Play asks for is in this folder. What only you can do is marked **you**.

| File | For |
|---|---|
| `listing.md` | Store listing texts, graphics table, release notes |
| `graphics/` | 512 px icon, 1024×500 feature graphic (SVG sources next to them) |
| `screenshots/` | Phone screenshots; `take.sh` retakes them |
| `privacy-policy.md` | The privacy policy to host |
| `app-content.md` | Answers for every "App content" form: data safety, rating, background location… |

## 1. Decide before the first upload (cannot change afterwards)

- **Package name**: `applicationId = "nowcast.app"` in `app/build.gradle.kts`. It is free on Play (checked 28 Oct 2026), but it reads as the domain nowcast.app, which you don't own. If you'd rather use one you control (e.g. `com.yourdomain.rainnowcast`), change it now. **you**

## 2. Build the bundle

```bash
./gradlew :app:bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab
```

It is signed with the **upload key** in `~/.android-keys/rain-nowcast/` (`upload.jks`, `keystore.properties` with its password). **Back that folder up somewhere safe** (a password manager or an encrypted drive). With Play App Signing, a lost upload key can be reset through Play support, but it takes days. **you**

Upload key SHA-256: `09:45:1F:61:6E:6D:2C:4F:BC:FB:49:27:E5:B4:35:86:BA:15:E6:A6:DA:C4:90:1C:38:48:2E:D7:54:AB:DB:71`

For each update, raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.

## 3. Play Console steps **you**

1. Create a developer account at play.google.com/console (one-time US$25, identity verification).
2. **Create app** → name "Rain Nowcast", App, Free, accept the declarations.
3. Host `privacy-policy.md` at a public URL (GitHub Pages, your website) and fill in your name and email in it.
4. **App content**: fill each form from `app-content.md`.
5. **Store listing**: texts and graphics from `listing.md`. Category Weather. Add your contact email.
6. **Testing → Closed testing**: upload `app-release.aab`, and accept Play App Signing when asked. New personal developer accounts must run a closed test with at least 12 testers for 14 days before they can apply for production.
7. **Production**: create a release with the same bundle and the release notes from `listing.md`, then send it for review.

## What was checked

- Release build (R8 shrinking) on the Android 16 emulator: Open-Meteo first run, radar forecast with both ONNX models, rain map; no crashes.
- Target API 36: meets Play's rule for new apps from 31 Aug 2026.
- Native libraries 16 KB page aligned: required for updates from 1 Feb 2027 (`zipalign -c -P 16` passes).
- Signed with the upload key (apksigner).
- Open-source licence notices: Settings → "Open-source licences".

## Worth knowing

- **Size**: the bundle is large (ONNX Runtime, MapLibre and two neural networks). Play delivers only the phone's own CPU type, so a download is about half the universal APK's 121 MB.
- **Open-Meteo's free API is for non-commercial use.** A free app without ads qualifies. If you ever charge for the app or add ads, you need an Open-Meteo plan; users can enter that key in Settings.
- **Background location** is the most likely review issue. `app-content.md` has the declaration text, a video script and a fallback.

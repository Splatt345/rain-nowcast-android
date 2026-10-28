# Plan 1 — On-device nowcast engine (no UI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pure-Kotlin nowcast engine that turns 12 OPERA radar frames into a 10-member, 12-lead ensemble shifted toward SmaAt-UNet, plus per-point curves and a summary — proven equal to a Python twin that has passed the CIRRUS gate.

**Architecture:** Gradle project with two modules. `engine/` is a plain Kotlin/JVM library (fast JUnit tests on the desktop, no Android); `app/` is the Android app that depends on it and supplies `onnxruntime-android` at runtime (same `ai.onnxruntime` API the engine compiles against). The ensemble is first written in NumPy in the research repo, scored against shipped STEPS, and only then ported line-for-line to Kotlin with a parity test. STEPS-lite (Task 2 as first written) failed that gate; the shipped ensemble is a faithful port of pysteps STEPS (`nowcast/stepsport.py`, gate PASS — see the spec's Validation section).

**Tech Stack:** Kotlin 2.4.20, Gradle 9.8.2, AGP 9.3.3 (built-in Kotlin), JDK 21, jHDF 0.12.0, onnxruntime 1.29.0 (desktop jar for tests, `onnxruntime-android` 1.29.0 in the app), kotlinx-serialization-json 1.11.0, JUnit 4.13.2. Python side: the research venv `~/Projects/local_weather_model/.venv`.

## Global Constraints

- minSdk 26, compileSdk 36, targetSdk 36. Test device: x86_64 AVD, SDK in `~/Android`.
- The **reference implementation is the research repo's add-on copy**, `~/Projects/local_weather_model/rain-nowcast/nowcast/` — NOT the fork's `rain-nowcast/`, which is stale (no `clean_dbz`; SmaAt was trained on cleaned input).
- Rain threshold 0.1 mm/h everywhere. dBZ cleaning: `>= 128` is fill → NaN; clip at 55 dBZ. Z–R: Marshall–Palmer, a = 200, b = 1.6.
- SmaAt: 12 frames in, 12 out, `log1p(x)/log1p(100)` transform, pad to multiple of 16, refuse non-finite or > 500 mm/h. ONNX input `x` `[b,12,h,w]`, output `y`.
- Combine weights: `0.59 0.59 0.62 0.62 0.63 0.65 0.66 0.66 0.67 0.68 0.68 0.69`; `out = clip(m + w·(ml − mean(m)), 0)`.
- Gate for STEPS-lite: on CIRRUS **validation** days (never the frozen test days), wet windows, `crps(lite+combine) <= crps(steps+combine) + 0.012` (the measured CIRRUS noise floor, `data/cirrustest_noisefloor.json`) **and** wet bias ≤ 1.15. **If it fails, stop and report. Do not search parameters to make it pass.**
- No new dependency beyond the Tech Stack list. No OpenCV (motion is pure Kotlin so it is JVM-testable and bit-comparable with the twin).
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File map

```
~/Projects/local_weather_model/nowcast/lite.py        motion + RNG twin (Task 2; its ensemble failed gate 0)
~/Projects/local_weather_model/nowcast/stepsport.py   Python twin of Ensemble.kt (Task 2, redone)
~/Projects/local_weather_model/nowcast/stepsporttest.py  gate (PREREGISTRATION_STEPSPORT.md)

~/Projects/rain-nowcast-android/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/wrapper/*
  engine/build.gradle.kts
  engine/src/main/kotlin/nowcast/engine/
    Grid.kt        grid type, box blur, bilinear sampling, pyramid      (Task 3)
    Rng.kt         counter-based SplitMix64 + Box–Muller                (Task 3)
    Flow.kt        dense coarse-to-fine Lucas–Kanade                    (Task 3)
    Fft.kt         mixed-radix complex FFT, any size                    (Task 4)
    Ensemble.kt    STEPS port                                           (Task 4)
    Combine.kt     shift toward the model                               (Task 5)
    Point.kt       point curves, spread, probability, summary           (Task 5)
    Laea.kt        ellipsoidal Lambert azimuthal equal-area             (Task 6)
    Regions.kt     region list + pick                                   (Task 6)
    Radar.kt       ORD listing/download, jHDF crop, dBZ -> mm/h         (Task 7)
    Model.kt       SmaAt-UNet via onnxruntime                           (Task 8)
    Nowcaster.kt   one cycle end to end                                 (Task 9)
  engine/src/test/kotlin/nowcast/engine/*Test.kt
  engine/src/test/resources/{lite_fixture.json, stepsport_fixture.json, opera_sample.h5}
  app/build.gradle.kts, app/src/main/AndroidManifest.xml
  app/src/main/kotlin/nowcast/app/MainActivity.kt                       (Task 1)
  app/src/main/assets/{regions.json, smaat.onnx, outlook/*}
  app/src/androidTest/kotlin/nowcast/app/EngineOnDeviceTest.kt          (Task 10)
```

Later plans consume `Nowcaster`, `Forecast`, `PointForecast`, `Region`, `RadarSource` from this one.

---

### Task 1: Toolchain and empty two-module project

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `engine/build.gradle.kts`, `engine/src/test/kotlin/nowcast/engine/SmokeTest.kt`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/nowcast/app/MainActivity.kt`
- Modify: `.gitignore`

**Interfaces:** Produces the `:engine` and `:app` modules every later task builds in.

- [ ] **Step 1: Install the Android SDK command-line tools**

```bash
mkdir -p ~/Android/cmdline-tools && cd ~/Android/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
unzip -q commandlinetools-linux-15859902_latest.zip && mv cmdline-tools latest && rm *.zip
export ANDROID_HOME=~/Android
yes | ~/Android/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
~/Android/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" \
  "build-tools;36.0.0" "emulator" "system-images;android-36;google_apis;x86_64"
echo no | ~/Android/cmdline-tools/latest/bin/avdmanager create avd -n nowcast \
  -k "system-images;android-36;google_apis;x86_64" -d pixel_7
```

Expected: `~/Android/platforms/android-36` exists and `avdmanager list avd` shows `nowcast`.

- [ ] **Step 2: Gradle wrapper**

```bash
cd ~/Projects/rain-nowcast-android
curl -LO https://services.gradle.org/distributions/gradle-9.8.2-bin.zip
unzip -q gradle-9.8.2-bin.zip -d /tmp/claude-gradle && rm gradle-9.8.2-bin.zip
/tmp/claude-gradle/gradle-9.8.2/bin/gradle wrapper --gradle-version 9.8.2
```

Expected: `gradlew`, `gradle/wrapper/gradle-wrapper.properties` created.

- [ ] **Step 3: Build files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "rain-nowcast-android"
include(":engine", ":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "9.3.3" apply false
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g
android.useAndroidX=true
```

`engine/build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("io.jhdf:jhdf:0.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // The app supplies onnxruntime-android (same ai.onnxruntime API) at runtime.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { maxHeapSize = "3g" }
```

`app/build.gradle.kts`:
```kotlin
plugins { id("com.android.application") }
android {
    namespace = "nowcast.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "nowcast.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += listOf("onnx", "h5") }
}
dependencies {
    implementation(project(":engine"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
```

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:label="Rain Nowcast">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/kotlin/nowcast/app/MainActivity.kt` (placeholder screen; Plan 2 replaces it):
```kotlin
package nowcast.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Rain Nowcast engine build" })
    }
}
```

`engine/src/test/kotlin/nowcast/engine/SmokeTest.kt`:
```kotlin
package nowcast.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test fun runs() = assertEquals(4, 2 + 2)
}
```

Append to `.gitignore`:
```
.gradle/
build/
local.properties
*.apk
```

Write `local.properties` (gitignored): `sdk.dir=/home/efernet/Android`

- [ ] **Step 4: Build both modules**

Run: `./gradlew :engine:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If AGP 9.3.3 rejects Gradle 9.8.2, the error names the required range — pin `gradle wrapper --gradle-version` to the newest version inside it and rerun.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle engine app .gitignore
git commit -m "Two-module Gradle project: JVM engine and Android app"
```

---

### Task 2: Python twin of STEPS-lite, and the gate

> **Outcome 2026-10-27:** STEPS-lite FAILED (CRPS 0.3713 vs 0.3230, wet bias 1.46). Redone as `nowcast/stepsport.py`, a port of pysteps `steps` with lite's motion and RNG, pre-registered in `PREREGISTRATION_STEPSPORT.md` and gated once: PASS (0.3172 vs 0.3230, wet bias 1.099). The lite code below is kept for the record; `lite.motion` and the RNG are still used.

**Repo:** `~/Projects/local_weather_model` (venv `.venv`).

**Files:**
- Create: `nowcast/lite.py`, `nowcast/litetest.py`

**Interfaces:**
- Produces `lite.ensemble(train_mm_h, n_lead=12, n_members=10, seed=42) -> (members (m,n_lead,H,W) float32 mm/h, vx, vy)` and `lite.fixture(path)` which writes `lite_fixture.json` for Task 4.
- Kotlin (Tasks 3–4) must reproduce every function here exactly; names map 1:1.

- [ ] **Step 1: Write `nowcast/lite.py`**

```python
"""STEPS-lite: the Android app's ensemble, in NumPy, so it can be scored.

Every function has a line-for-line Kotlin twin in the Android repo
(engine/src/main/kotlin/nowcast/engine/). Change one, change both, regenerate
the parity fixture (`python -m nowcast.lite --fixture <path>`).

    motion    dense coarse-to-fine Lucas-Kanade on dB fields, two frame pairs,
              filled into dry areas by confidence-weighted smoothing
    members   motion scaled by s ~ N(1, 0.10) and rotated by N(0, 5 deg),
              fixed per member - chosen a priori, NOT tuned against a score
    advection backward semi-Lagrangian from the last frame, NaN off-domain
    growth    per member a random walk in dB, spatially smooth, step sigma
              measured from this case's own one-step Lagrangian residual
"""
import numpy as np

GOLDEN = 0x9E3779B97F4A7C15
MASK = (1 << 64) - 1
DRY_DB, WET_DB = -15.0, -10.0           # -10 dB == 0.1 mm/h
MAX_DB = 20.0                           # 100 mm/h: MAX_DBZ and mlmodel.LOG_SCALE agree


# ---- RNG: counter-based SplitMix64, identical in Kotlin (Rng.kt) ----------

def uniforms(seed, n):
    i = np.arange(1, n + 1, dtype=np.uint64)
    z = np.uint64(seed & MASK) + i * np.uint64(GOLDEN)
    z = (z ^ (z >> np.uint64(30))) * np.uint64(0xBF58476D1CE4E5B9)
    z = (z ^ (z >> np.uint64(27))) * np.uint64(0x94D049BB133111EB)
    z = z ^ (z >> np.uint64(31))
    return (z >> np.uint64(11)).astype(np.float64) * 2.0 ** -53


def normals(seed, n):
    u = uniforms(seed, 2 * n)
    return np.sqrt(-2.0 * np.log(1.0 - u[0::2])) * np.cos(2.0 * np.pi * u[1::2])


def stream(seed, member, lead):
    return (seed * 1_000_003 + member * 1000 + lead) & MASK


# ---- grid helpers (Grid.kt) -----------------------------------------------

def blur(f, r):
    """Separable box mean of radius r, edge-clamped. r = 0 is identity."""
    if r == 0:
        return f.copy()
    k = 2 * r + 1
    for axis in (0, 1):
        p = np.pad(f, [(r, r) if a == axis else (0, 0) for a in (0, 1)], mode="edge")
        c = np.cumsum(np.insert(p, 0, 0.0, axis=axis), axis=axis)
        n = f.shape[axis]
        f = (np.take(c, np.arange(k, k + n), axis=axis)
             - np.take(c, np.arange(0, n), axis=axis)) / k
    return f


def sample_clamped(f, r, c):
    """Bilinear, coordinates clamped to the grid, NaN read as 0."""
    h, w = f.shape
    g = np.nan_to_num(f, nan=0.0)
    r = np.clip(r, 0, h - 1)
    c = np.clip(c, 0, w - 1)
    r0 = np.floor(r).astype(int); c0 = np.floor(c).astype(int)
    r1 = np.minimum(r0 + 1, h - 1); c1 = np.minimum(c0 + 1, w - 1)
    fr, fc = r - r0, c - c0
    return ((1 - fr) * ((1 - fc) * g[r0, c0] + fc * g[r0, c1])
            + fr * ((1 - fc) * g[r1, c0] + fc * g[r1, c1]))


def sample_or_nan(f, r, c):
    """Bilinear; NaN outside [0,h-1]x[0,w-1] or next to a NaN."""
    h, w = f.shape
    out = np.full(np.shape(r), np.nan)
    ok = (r >= 0) & (r <= h - 1) & (c >= 0) & (c <= w - 1)
    rr, cc = r[ok], c[ok]
    r0 = np.floor(rr).astype(int); c0 = np.floor(cc).astype(int)
    r1 = np.minimum(r0 + 1, h - 1); c1 = np.minimum(c0 + 1, w - 1)
    fr, fc = rr - r0, cc - c0
    out[ok] = ((1 - fr) * ((1 - fc) * f[r0, c0] + fc * f[r0, c1])
               + fr * ((1 - fc) * f[r1, c0] + fc * f[r1, c1]))
    return out


def down2(f):
    h, w = f.shape[0] // 2 * 2, f.shape[1] // 2 * 2
    f = f[:h, :w]
    return 0.25 * (f[0::2, 0::2] + f[1::2, 0::2] + f[0::2, 1::2] + f[1::2, 1::2])


def up2(f, h, w):
    """Nearest x2, then crop/edge-pad to (h, w)."""
    g = np.repeat(np.repeat(f, 2, 0), 2, 1)[:h, :w]
    return np.pad(g, ((0, h - g.shape[0]), (0, w - g.shape[1])), mode="edge")


def shift(f, dr, dc):
    """f[clamp(r+dr), clamp(c+dc)]."""
    h, w = f.shape
    return f[np.clip(np.arange(h) + dr, 0, h - 1)][:, np.clip(np.arange(w) + dc, 0, w - 1)]


# ---- dB transform ----------------------------------------------------------

def to_db(rain):
    r = np.nan_to_num(np.asarray(rain, float), nan=0.0)
    return np.where(r >= 0.1, 10.0 * np.log10(np.maximum(r, 0.1)), DRY_DB)


def from_db(db):
    return np.where(db >= WET_DB, 10.0 ** (db / 10.0), 0.0)


# ---- motion (Flow.kt) ------------------------------------------------------

LEVELS, WINDOW, ITERS, FILL_RADIUS, FILL_PASSES, EPS = 4, 7, 3, 40, 2, 1e-6


def lk_pair(a, b):
    """Flow (vx, vy) in px/step taking a to b, plus confidence (finest level)."""
    pa, pb = [a], [b]
    for _ in range(LEVELS - 1):
        pa.append(down2(pa[-1])); pb.append(down2(pb[-1]))
    vx = np.zeros_like(pa[-1]); vy = np.zeros_like(pa[-1])
    conf = None
    for lvl in range(LEVELS - 1, -1, -1):
        A, B = pa[lvl], pb[lvl]
        h, w = A.shape
        if vx.shape != A.shape:
            vx = 2.0 * up2(vx, h, w); vy = 2.0 * up2(vy, h, w)
        rr, cc = np.mgrid[0:h, 0:w].astype(float)
        for _ in range(ITERS):
            bw = sample_clamped(B, rr + vy, cc + vx)
            m = 0.5 * (A + bw)
            ix = 0.5 * (shift(m, 0, 1) - shift(m, 0, -1))
            iy = 0.5 * (shift(m, 1, 0) - shift(m, -1, 0))
            it = bw - A
            sxx, syy, sxy = blur(ix * ix, WINDOW), blur(iy * iy, WINDOW), blur(ix * iy, WINDOW)
            sxt, syt = blur(ix * it, WINDOW), blur(iy * it, WINDOW)
            det = sxx * syy - sxy * sxy
            safe = np.where(det > EPS, det, 1.0)
            du = np.where(det > EPS, -(syy * sxt - sxy * syt) / safe, 0.0)
            dv = np.where(det > EPS, -(sxx * syt - sxy * sxt) / safe, 0.0)
            vx = vx + du; vy = vy + dv
        conf = np.maximum(det, 0.0)
    return vx, vy, conf


def motion(db_frames):
    """Average of the last two pairs, filled into textureless (dry) areas by
    confidence-weighted smoothing, so rain can advect INTO dry pixels."""
    fx = np.zeros_like(db_frames[-1]); fy = np.zeros_like(fx); fc = np.zeros_like(fx)
    for a, b in ((db_frames[-3], db_frames[-2]), (db_frames[-2], db_frames[-1])):
        vx, vy, c = lk_pair(a, b)
        fx += c * vx; fy += c * vy; fc += c
    nx, ny, nc = fx, fy, fc
    for _ in range(FILL_PASSES):
        nx, ny, nc = blur(nx, FILL_RADIUS), blur(ny, FILL_RADIUS), blur(nc, FILL_RADIUS)
    safe = np.where(nc > 0, nc, 1.0)
    return np.where(nc > 0, nx / safe, 0.0), np.where(nc > 0, ny / safe, 0.0)


# ---- ensemble (Ensemble.kt) -----------------------------------------------

SPEED_SD, ANGLE_SD_DEG, NOISE_RADIUS, NOISE_PASSES = 0.10, 5.0, 8, 3


def smooth_noise(seed, h, w):
    e = normals(seed, h * w).reshape(h, w)
    for _ in range(NOISE_PASSES):
        e = blur(e, NOISE_RADIUS)
    sd = e.std()
    return e / sd if sd > 0 else e


def residual_sigma(db_prev, db_last, vx, vy):
    """Std of the one-step Lagrangian residual, in dB, where it rains in BOTH.

    Not where it rains in either: the dry floor is -15 dB, so every rain edge
    would read as a >= 5 dB "growth" (measured 4-7 dB/step that way, which made
    the random walk explode). Growth and decay of intensity is what this models."""
    h, w = db_last.shape
    rr, cc = np.mgrid[0:h, 0:w].astype(float)
    adv = sample_or_nan(db_prev, rr - vy, cc - vx)
    ok = np.isfinite(adv) & (db_last >= WET_DB) & (adv >= WET_DB)
    return float(np.std(db_last[ok] - adv[ok])) if ok.sum() >= 100 else 0.0


def ensemble(train, n_lead=12, n_members=10, seed=42):
    db = np.stack([to_db(f) for f in np.asarray(train)[-3:]])
    vx, vy = motion(db)
    sigma = residual_sigma(db[-2], db[-1], vx, vy)
    h, w = db[-1].shape
    rr, cc = np.mgrid[0:h, 0:w].astype(float)
    out = np.empty((n_members, n_lead, h, w), dtype=np.float32)
    for m in range(n_members):
        z = normals(stream(seed, m, 999), 2)
        s, th = 1.0 + SPEED_SD * z[0], np.deg2rad(ANGLE_SD_DEG) * z[1]
        mx = s * (np.cos(th) * vx - np.sin(th) * vy)
        my = s * (np.sin(th) * vx + np.cos(th) * vy)
        pr, pc = rr.copy(), cc.copy()
        walk = np.zeros((h, w))
        for k in range(n_lead):
            pr, pc = (pr - sample_clamped(my, pr, pc), pc - sample_clamped(mx, pr, pc))
            adv = sample_or_nan(db[-1], pr, pc)
            walk = walk + sigma * smooth_noise(stream(seed, m, k), h, w)
            field = np.where(adv >= WET_DB, np.minimum(adv + walk, MAX_DB), adv)
            out[m, k] = np.where(np.isfinite(field), from_db(field), np.nan)
    return out, vx, vy


# ---- parity fixture for the Kotlin tests ----------------------------------

def _synthetic(h=48, w=56, n=4):
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    noise = normals(7, h * w).reshape(h, w)
    return np.stack([np.clip(6.0 * np.exp(-(((xx - 14 - 2.0 * t) ** 2) + (yy - 20 - 1.0 * t) ** 2)
                                         / 60.0) + 0.3 * noise - 0.2, 0, None)
                     for t in range(n)])


def fixture(path):
    import json

    train = _synthetic()
    members, vx, vy = ensemble(train, n_lead=4, n_members=3, seed=42)
    db = np.stack([to_db(f) for f in train[-3:]])
    js = lambda a: [None if not np.isfinite(v) else float(v) for v in np.ravel(a)]
    with open(path, "w") as f:
        json.dump({"h": train.shape[1], "w": train.shape[2], "train": js(train),
                   "uniforms_seed5": js(uniforms(5, 8)), "normals_seed5": js(normals(5, 8)),
                   "blur_r2": js(blur(train[0], 2)),
                   "sigma": residual_sigma(db[-2], db[-1], vx, vy),
                   "vx": js(vx), "vy": js(vy), "members": js(members)}, f)


def _selfcheck():
    u = uniforms(1, 200000)                  # sd of the mean ~0.0006
    assert 0 <= u.min() and u.max() < 1 and abs(u.mean() - 0.5) < 0.005
    z = normals(1, 200000)
    assert abs(z.mean()) < 0.01 and abs(z.std() - 1) < 0.01
    f = np.arange(30.0).reshape(5, 6)
    assert np.allclose(blur(np.ones((5, 6)) * 3, 2), 3)
    assert np.allclose(blur(f, 0), f)
    assert np.isclose(sample_clamped(f, np.array([1.5]), np.array([2.5]))[0], 11.5)
    assert np.isnan(sample_or_nan(f, np.array([-0.1]), np.array([0.0]))[0])
    # motion recovers a known shift of a textured field
    rng = np.random.default_rng(0)
    base = blur(rng.normal(size=(96, 96)), 3) * 10
    a, b = base, np.roll(base, (1, 2), axis=(0, 1))       # +1 row, +2 cols per step
    vx, vy, _ = lk_pair(a, b)
    assert abs(np.median(vx[20:-20, 20:-20]) - 2) < 0.2, np.median(vx)
    assert abs(np.median(vy[20:-20, 20:-20]) - 1) < 0.2, np.median(vy)
    # the ensemble moves a blob the right way and stays physical
    train = _synthetic(n=12)
    members, _, _ = ensemble(train, n_lead=4, n_members=3)
    assert members.shape == (3, 4, 48, 56)
    fin = members[np.isfinite(members)]
    assert fin.min() >= 0 and fin.max() < 100
    last = np.unravel_index(np.argmax(train[-1]), train[-1].shape)
    lead4 = np.unravel_index(np.nanargmax(np.nanmean(members[:, 3], 0)), (48, 56))
    assert lead4[1] > last[1], (last, lead4)              # moved right (+x)
    print("lite selfcheck ok")


if __name__ == "__main__":
    import sys

    if "--fixture" in sys.argv:
        fixture(sys.argv[sys.argv.index("--fixture") + 1])
    else:
        _selfcheck()
```

- [ ] **Step 2: Run the self-check**

Run: `cd ~/Projects/local_weather_model && .venv/bin/python -m nowcast.lite`
Expected: `lite selfcheck ok`. If the shift test fails, the bug is in `lk_pair`'s sign convention (`It = warped_b − a`, `du = −(Syy·Sxt − Sxy·Syt)/det`) — fix that, not the tolerance.

- [ ] **Step 3: Write `nowcast/litetest.py`**

```python
"""Gate STEPS-lite against shipped STEPS, both shifted toward SmaAt-UNet.

VALIDATION days only (archive.split_days), never the frozen CIRRUS test days.
Floor is the measured CIRRUS noise floor. One look; no parameter search.

    python -m nowcast.litetest
"""
import json
import os

import numpy as np

from nowcast import archive as A
from nowcast import cirrustest as T
from nowcast import combine as comb, lite, mlmodel as ml, probscore as P

FLOOR = json.load(open(os.path.join(os.path.dirname(__file__), "..", "data",
                                    "cirrustest_noisefloor.json")))["floor"]


def main():
    _, val = A.split_days()
    wins = T.windows(A.cfg("cirrus")["dir"], val, "wet")
    rows, pw = [], {"steps": 0.0, "lite": 0.0, "obs": 0.0}
    for i, (d, s0, w) in enumerate(wins, 1):
        train, truth = w[:T.N_IN], w[T.N_IN:]
        other = ml.forecast(train, T.N_OUT)
        steps = comb.combine(T.steps(train, 42), other, comb.WEIGHTS)
        members, _, _ = lite.ensemble(train, T.N_OUT, 10, 42)
        mine = comb.combine(members, other, comb.WEIGHTS)
        r = {k: float(np.mean([P.crps(e[:, j], truth[j]) for j in range(T.N_OUT)]))
             for k, e in (("steps", steps), ("lite", mine))}
        for k, e in (("steps", steps), ("lite", mine)):
            pw[k] += T.wet_frac(np.nanmean(e, axis=0))
        pw["obs"] += T.wet_frac(truth)
        rows.append({"day": d, **r})
        print(f"  [{i}/{len(wins)}] {d} steps {r['steps']:.4f} lite {r['lite']:.4f}", flush=True)
    s = np.mean([r["steps"] for r in rows]); l = np.mean([r["lite"] for r in rows])
    res = {"n": len(rows), "crps_steps": s, "crps_lite": l, "d": s - l, "floor": FLOOR,
           "wet_bias_lite": pw["lite"] / pw["obs"], "rows": rows}
    res["pass"] = bool(l <= s + FLOOR and res["wet_bias_lite"] <= 1.15)
    print(json.dumps({k: v for k, v in res.items() if k != "rows"}, indent=1))
    with open(os.path.join(os.path.dirname(__file__), "..", "data", "litetest.json"), "w") as f:
        json.dump(res, f, indent=1)


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Run the gate (one look)**

Run: `.venv/bin/python -m nowcast.litetest 2>&1 | grep -v Pysteps` (≈15 min; STEPS is ~12 s per window)
Expected: a JSON block with `"pass": true`. **If `false`: stop the plan here, commit the result, and report numbers to the user.** Do not change constants and re-run.

- [ ] **Step 5: Write the fixture and commit**

```bash
.venv/bin/python -m nowcast.lite --fixture ~/Projects/rain-nowcast-android/engine/src/test/resources/lite_fixture.json
git add nowcast/lite.py nowcast/litetest.py
git commit -m "STEPS-lite twin of the Android ensemble, gated against shipped STEPS"
```

---

### Task 3: Grid, RNG and motion in Kotlin

**Files:**
- Create: `engine/src/main/kotlin/nowcast/engine/Grid.kt`, `Rng.kt`, `Flow.kt`
- Test: `engine/src/test/kotlin/nowcast/engine/FlowTest.kt`, `engine/src/test/kotlin/nowcast/engine/Fixture.kt`

**Interfaces:**
- Consumes: `lite_fixture.json` from Task 2.
- Produces:
  - `class Grid(val h: Int, val w: Int, val v: DoubleArray = DoubleArray(h * w))` with `get(r,c)`, `set(r,c,x)`, `copy()`, `map((Double)->Double)`
  - `fun blur(f: Grid, r: Int): Grid`, `fun sampleClamped(f: Grid, r: Double, c: Double): Double`, `fun sampleOrNaN(f: Grid, r: Double, c: Double): Double`, `fun down2(f: Grid): Grid`, `fun up2(f: Grid, h: Int, w: Int): Grid`, `fun shift(f: Grid, dr: Int, dc: Int): Grid`
  - `object Rng { fun uniforms(seed: Long, n: Int): DoubleArray; fun normals(seed: Long, n: Int): DoubleArray; fun stream(seed: Long, member: Int, lead: Int): Long }`
  - `fun toDb(rain: Double): Double`, `fun fromDb(db: Double): Double`, `const val WET_DB = -10.0`, `const val DRY_DB = -15.0`
  - `class Motion(val vx: Grid, val vy: Grid)`; `fun lkPair(a: Grid, b: Grid): Triple<Grid, Grid, Grid>`; `fun motion(db: List<Grid>): Motion` (uses the last three)

- [ ] **Step 1: Write the fixture loader and failing tests**

`engine/src/test/kotlin/nowcast/engine/Fixture.kt`:
```kotlin
package nowcast.engine

import kotlinx.serialization.json.*

object Fixture {
    val js: JsonObject by lazy {
        Json.parseToJsonElement(
            Fixture::class.java.getResource("/lite_fixture.json")!!.readText()).jsonObject
    }
    val h get() = js["h"]!!.jsonPrimitive.int
    val w get() = js["w"]!!.jsonPrimitive.int
    fun arr(key: String): DoubleArray = js[key]!!.jsonArray
        .map { if (it is JsonNull) Double.NaN else it.jsonPrimitive.double }.toDoubleArray()
    fun num(key: String) = js[key]!!.jsonPrimitive.double
    fun frames(): List<Grid> {
        val a = arr("train"); val n = h * w
        return (0 until a.size / n).map { Grid(h, w, a.copyOfRange(it * n, (it + 1) * n)) }
    }
}

fun assertClose(want: DoubleArray, got: DoubleArray, tol: Double, what: String) {
    require(want.size == got.size) { "$what: size ${want.size} vs ${got.size}" }
    for (i in want.indices) {
        val a = want[i]; val b = got[i]
        if (a.isNaN() || b.isNaN()) {
            require(a.isNaN() && b.isNaN()) { "$what[$i]: NaN mismatch $a vs $b" }
        } else require(kotlin.math.abs(a - b) <= tol * (1 + kotlin.math.abs(a))) {
            "$what[$i]: want $a got $b"
        }
    }
}
```

`engine/src/test/kotlin/nowcast/engine/FlowTest.kt`:
```kotlin
package nowcast.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTest {
    @Test fun rngMatchesPython() {
        assertClose(Fixture.arr("uniforms_seed5"), Rng.uniforms(5, 8), 1e-15, "uniforms")
        assertClose(Fixture.arr("normals_seed5"), Rng.normals(5, 8), 1e-12, "normals")
    }

    @Test fun blurMatchesPython() {
        assertClose(Fixture.arr("blur_r2"), blur(Fixture.frames()[0], 2).v, 1e-12, "blur")
    }

    @Test fun samplingAgreesWithHandValues() {
        val f = Grid(5, 6, DoubleArray(30) { it.toDouble() })
        assertEquals(11.5, sampleClamped(f, 1.5, 2.5), 1e-12)
        assertTrue(sampleOrNaN(f, -0.1, 0.0).isNaN())
        assertEquals(29.0, sampleClamped(f, 9.0, 9.0), 1e-12)
    }

    @Test fun motionMatchesPython() {
        val db = Fixture.frames().takeLast(3).map { g -> g.map(::toDb) }
        val m = motion(db)
        assertClose(Fixture.arr("vx"), m.vx.v, 1e-6, "vx")
        assertClose(Fixture.arr("vy"), m.vy.v, 1e-6, "vy")
    }
}
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :engine:test --tests nowcast.engine.FlowTest`
Expected: compilation failure (`Grid`, `Rng`, `motion` unresolved).

- [ ] **Step 3: Implement `Grid.kt`**

```kotlin
package nowcast.engine

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Row-major 2-D field. Twin of the helpers at the top of nowcast/lite.py. */
class Grid(val h: Int, val w: Int, val v: DoubleArray = DoubleArray(h * w)) {
    operator fun get(r: Int, c: Int) = v[r * w + c]
    operator fun set(r: Int, c: Int, x: Double) { v[r * w + c] = x }
    fun copy() = Grid(h, w, v.copyOf())
    inline fun map(f: (Double) -> Double) = Grid(h, w, DoubleArray(v.size) { f(v[it]) })
}

const val WET_DB = -10.0   // 0.1 mm/h
const val DRY_DB = -15.0
const val MAX_DB = 20.0    // 100 mm/h, same ceiling as Odim.MAX_DBZ and the model transform

fun toDb(rain: Double): Double =
    if (!rain.isNaN() && rain >= 0.1) 10.0 * log10(rain) else DRY_DB

fun fromDb(db: Double): Double = if (db >= WET_DB) 10.0.pow(db / 10.0) else 0.0

private fun clampI(i: Int, n: Int) = if (i < 0) 0 else if (i >= n) n - 1 else i

/** Separable box mean, edge-clamped; radius 0 copies. */
fun blur(f: Grid, r: Int): Grid {
    if (r == 0) return f.copy()
    val k = 2 * r + 1
    val tmp = Grid(f.h, f.w)
    for (row in 0 until f.h) {
        var s = 0.0
        for (j in -r..r) s += f[row, clampI(j, f.w)]
        for (c in 0 until f.w) {
            tmp[row, c] = s / k
            s += f[row, clampI(c + r + 1, f.w)] - f[row, clampI(c - r, f.w)]
        }
    }
    val out = Grid(f.h, f.w)
    for (c in 0 until f.w) {
        var s = 0.0
        for (j in -r..r) s += tmp[clampI(j, f.h), c]
        for (row in 0 until f.h) {
            out[row, c] = s / k
            s += tmp[clampI(row + r + 1, f.h), c] - tmp[clampI(row - r, f.h), c]
        }
    }
    return out
}

/** Bilinear, coordinates clamped, NaN read as 0. */
fun sampleClamped(f: Grid, r: Double, c: Double): Double {
    val rr = r.coerceIn(0.0, (f.h - 1).toDouble()); val cc = c.coerceIn(0.0, (f.w - 1).toDouble())
    val r0 = floor(rr).toInt(); val c0 = floor(cc).toInt()
    val r1 = minOf(r0 + 1, f.h - 1); val c1 = minOf(c0 + 1, f.w - 1)
    val fr = rr - r0; val fc = cc - c0
    fun g(a: Int, b: Int) = f[a, b].let { if (it.isNaN()) 0.0 else it }
    return (1 - fr) * ((1 - fc) * g(r0, c0) + fc * g(r0, c1)) +
        fr * ((1 - fc) * g(r1, c0) + fc * g(r1, c1))
}

/** Bilinear; NaN off-grid or next to a NaN. */
fun sampleOrNaN(f: Grid, r: Double, c: Double): Double {
    if (r < 0 || r > f.h - 1 || c < 0 || c > f.w - 1 || r.isNaN() || c.isNaN()) return Double.NaN
    val r0 = floor(r).toInt(); val c0 = floor(c).toInt()
    val r1 = minOf(r0 + 1, f.h - 1); val c1 = minOf(c0 + 1, f.w - 1)
    val fr = r - r0; val fc = c - c0
    return (1 - fr) * ((1 - fc) * f[r0, c0] + fc * f[r0, c1]) +
        fr * ((1 - fc) * f[r1, c0] + fc * f[r1, c1])
}

fun down2(f: Grid): Grid {
    val h = f.h / 2; val w = f.w / 2
    val out = Grid(h, w)
    for (r in 0 until h) for (c in 0 until w)
        out[r, c] = 0.25 * (f[2 * r, 2 * c] + f[2 * r + 1, 2 * c] + f[2 * r, 2 * c + 1] + f[2 * r + 1, 2 * c + 1])
    return out
}

/** Nearest x2, then crop/edge-pad to (h, w). */
fun up2(f: Grid, h: Int, w: Int): Grid {
    val out = Grid(h, w)
    for (r in 0 until h) for (c in 0 until w)
        out[r, c] = f[minOf(r, 2 * f.h - 1) / 2, minOf(c, 2 * f.w - 1) / 2]
    return out
}

/** out[r, c] = f[clamp(r + dr), clamp(c + dc)]. */
fun shift(f: Grid, dr: Int, dc: Int): Grid {
    val out = Grid(f.h, f.w)
    for (r in 0 until f.h) for (c in 0 until f.w) out[r, c] = f[clampI(r + dr, f.h), clampI(c + dc, f.w)]
    return out
}
```

Note on `up2`: Python's `np.pad(..., mode="edge")` after cropping repeats the last row/col of the doubled grid, which is source index `f.h - 1` — `minOf(r, 2*f.h-1)/2` gives exactly that.

- [ ] **Step 4: Implement `Rng.kt`**

```kotlin
package nowcast.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Counter-based SplitMix64 + Box-Muller; bit-identical to lite.uniforms/normals. */
object Rng {
    private const val GOLDEN = -0x61c8864680b583ebL           // 0x9E3779B97F4A7C15
    private const val M1 = -0x40a7b892e31b1a47L               // 0xBF58476D1CE4E5B9
    private const val M2 = -0x6b2fb644ecceee15L               // 0x94D049BB133111EB

    fun uniforms(seed: Long, n: Int) = DoubleArray(n) { i ->
        var z = seed + (i + 1).toLong() * GOLDEN
        z = (z xor (z ushr 30)) * M1
        z = (z xor (z ushr 27)) * M2
        z = z xor (z ushr 31)
        (z ushr 11).toDouble() * (1.0 / (1L shl 53))
    }

    fun normals(seed: Long, n: Int): DoubleArray {
        val u = uniforms(seed, 2 * n)
        return DoubleArray(n) { sqrt(-2.0 * ln(1.0 - u[2 * it])) * cos(2.0 * PI * u[2 * it + 1]) }
    }

    fun stream(seed: Long, member: Int, lead: Int): Long = seed * 1_000_003L + member * 1000L + lead
}
```

- [ ] **Step 5: Implement `Flow.kt`**

```kotlin
package nowcast.engine

/** Dense coarse-to-fine Lucas-Kanade. Twin of lite.lk_pair / lite.motion. */
class Motion(val vx: Grid, val vy: Grid)

private const val LEVELS = 4
private const val WINDOW = 7
private const val ITERS = 3
private const val FILL_RADIUS = 40
private const val FILL_PASSES = 2
private const val EPS = 1e-6

fun lkPair(a: Grid, b: Grid): Triple<Grid, Grid, Grid> {
    val pa = mutableListOf(a); val pb = mutableListOf(b)
    repeat(LEVELS - 1) { pa += down2(pa.last()); pb += down2(pb.last()) }
    var vx = Grid(pa.last().h, pa.last().w); var vy = Grid(vx.h, vx.w)
    var conf = Grid(a.h, a.w)
    for (lvl in LEVELS - 1 downTo 0) {
        val A = pa[lvl]; val B = pb[lvl]; val h = A.h; val w = A.w
        if (vx.h != h || vx.w != w) { vx = up2(vx, h, w).map { 2 * it }; vy = up2(vy, h, w).map { 2 * it } }
        var det = Grid(h, w)
        repeat(ITERS) {
            val bw = Grid(h, w)
            for (r in 0 until h) for (c in 0 until w) bw[r, c] = sampleClamped(B, r + vy[r, c], c + vx[r, c])
            val m = Grid(h, w, DoubleArray(h * w) { 0.5 * (A.v[it] + bw.v[it]) })
            val px = shift(m, 0, 1); val nx = shift(m, 0, -1); val py = shift(m, 1, 0); val ny = shift(m, -1, 0)
            val ix = DoubleArray(h * w) { 0.5 * (px.v[it] - nx.v[it]) }
            val iy = DoubleArray(h * w) { 0.5 * (py.v[it] - ny.v[it]) }
            val it_ = DoubleArray(h * w) { bw.v[it] - A.v[it] }
            fun s(f: (Int) -> Double) = blur(Grid(h, w, DoubleArray(h * w, f)), WINDOW)
            val sxx = s { ix[it] * ix[it] }; val syy = s { iy[it] * iy[it] }; val sxy = s { ix[it] * iy[it] }
            val sxt = s { ix[it] * it_[it] }; val syt = s { iy[it] * it_[it] }
            det = Grid(h, w, DoubleArray(h * w) { sxx.v[it] * syy.v[it] - sxy.v[it] * sxy.v[it] })
            for (i in 0 until h * w) {
                val d = det.v[i]
                if (d > EPS) {
                    vx.v[i] += -(syy.v[i] * sxt.v[i] - sxy.v[i] * syt.v[i]) / d
                    vy.v[i] += -(sxx.v[i] * syt.v[i] - sxy.v[i] * sxt.v[i]) / d
                }
            }
        }
        conf = det.map { maxOf(it, 0.0) }
    }
    return Triple(vx, vy, conf)
}

/** Last two pairs, confidence-weighted, filled into dry areas. */
fun motion(db: List<Grid>): Motion {
    val last = db.last(); val n = last.h * last.w
    var fx = Grid(last.h, last.w); var fy = Grid(last.h, last.w); var fc = Grid(last.h, last.w)
    for ((a, b) in listOf(db[db.size - 3] to db[db.size - 2], db[db.size - 2] to db[db.size - 1])) {
        val (vx, vy, c) = lkPair(a, b)
        for (i in 0 until n) { fx.v[i] += c.v[i] * vx.v[i]; fy.v[i] += c.v[i] * vy.v[i]; fc.v[i] += c.v[i] }
    }
    repeat(FILL_PASSES) { fx = blur(fx, FILL_RADIUS); fy = blur(fy, FILL_RADIUS); fc = blur(fc, FILL_RADIUS) }
    return Motion(
        Grid(last.h, last.w, DoubleArray(n) { if (fc.v[it] > 0) fx.v[it] / fc.v[it] else 0.0 }),
        Grid(last.h, last.w, DoubleArray(n) { if (fc.v[it] > 0) fy.v[it] / fc.v[it] else 0.0 }))
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :engine:test --tests nowcast.engine.FlowTest`
Expected: 4 tests PASS. A `vx`/`vy` mismatch at 1e-6 means an operation-order difference against `lite.py` — compare the two functions line by line; do not loosen the tolerance.

- [ ] **Step 7: Commit**

```bash
git add engine/src
git commit -m "Grid helpers, SplitMix RNG and dense Lucas-Kanade motion, matched to the Python twin"
```

---

### Task 4: STEPS-lite ensemble in Kotlin

> **Superseded 2026-10-27:** implemented instead as `Fft.kt` + `Ensemble.kt`, the STEPS port, twin of `stepsport.py`, tested by `EnsembleTest` against `stepsport_fixture.json`. Public interface kept (`EnsembleResult`, `ensemble(train, nLead, nMembers, seed)`), plus defaulted `kmPerPixel`, `timestep`, `threads`; `residualSigma` no longer exists (STEPS has no such quantity).

**Files:**
- Create: `engine/src/main/kotlin/nowcast/engine/Ensemble.kt`
- Test: `engine/src/test/kotlin/nowcast/engine/EnsembleTest.kt`

**Interfaces:**
- Consumes: Task 3 (`Grid`, `Rng`, `motion`, `sampleClamped`, `sampleOrNaN`, `blur`, `toDb`, `fromDb`).
- Produces: `class EnsembleResult(val members: Array<Array<FloatArray>>, val motion: Motion, val h: Int, val w: Int)` (indexed `members[m][lead][r*w+c]`, mm/h, NaN off-domain); `fun ensemble(train: List<Grid>, nLead: Int = 12, nMembers: Int = 10, seed: Long = 42): EnsembleResult`; `fun residualSigma(prev: Grid, last: Grid, m: Motion): Double`.

- [ ] **Step 1: Failing test**

```kotlin
package nowcast.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EnsembleTest {
    @Test fun sigmaMatchesPython() {
        val db = Fixture.frames().takeLast(3).map { g -> g.map(::toDb) }
        assertEquals(Fixture.num("sigma"), residualSigma(db[1], db[2], motion(db)), 1e-9)
    }

    @Test fun membersMatchPython() {
        val r = ensemble(Fixture.frames(), nLead = 4, nMembers = 3, seed = 42)
        val flat = r.members.flatMap { m -> m.flatMap { l -> l.map { it.toDouble() } } }.toDoubleArray()
        // float32 storage on both sides: compare at float precision
        assertClose(Fixture.arr("members"), flat, 1e-5, "members")
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./gradlew :engine:test --tests nowcast.engine.EnsembleTest`
Expected: compilation failure (`ensemble` unresolved).

- [ ] **Step 3: Implement `Ensemble.kt`**

```kotlin
package nowcast.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** STEPS-lite. Twin of nowcast/lite.py:ensemble - see its docstring for the why. */
class EnsembleResult(val members: Array<Array<FloatArray>>, val motion: Motion, val h: Int, val w: Int)

private const val SPEED_SD = 0.10
private const val ANGLE_SD_DEG = 5.0
private const val NOISE_RADIUS = 8
private const val NOISE_PASSES = 3

private fun smoothNoise(seed: Long, h: Int, w: Int): Grid {
    var e = Grid(h, w, Rng.normals(seed, h * w))
    repeat(NOISE_PASSES) { e = blur(e, NOISE_RADIUS) }
    val mean = e.v.average()
    val sd = sqrt(e.v.sumOf { (it - mean) * (it - mean) } / e.v.size)
    return if (sd > 0) e.map { it / sd } else e
}

fun residualSigma(prev: Grid, last: Grid, m: Motion): Double {
    val res = ArrayList<Double>()
    for (r in 0 until last.h) for (c in 0 until last.w) {
        val adv = sampleOrNaN(prev, r - m.vy[r, c], c - m.vx[r, c])
        if (!adv.isNaN() && last[r, c] >= WET_DB && adv >= WET_DB) res += last[r, c] - adv
    }
    if (res.size < 100) return 0.0
    val mean = res.average()
    return sqrt(res.sumOf { (it - mean) * (it - mean) } / res.size)
}

fun ensemble(train: List<Grid>, nLead: Int = 12, nMembers: Int = 10, seed: Long = 42): EnsembleResult {
    val db = train.takeLast(3).map { g -> g.map(::toDb) }
    val mo = motion(db)
    val sigma = residualSigma(db[1], db[2], mo)
    val last = db[2]; val h = last.h; val w = last.w; val n = h * w
    val members = Array(nMembers) { m ->
        val z = Rng.normals(Rng.stream(seed, m, 999), 2)
        val s = 1.0 + SPEED_SD * z[0]; val th = Math.toRadians(ANGLE_SD_DEG) * z[1]
        val mx = Grid(h, w, DoubleArray(n) { s * (cos(th) * mo.vx.v[it] - sin(th) * mo.vy.v[it]) })
        val my = Grid(h, w, DoubleArray(n) { s * (sin(th) * mo.vx.v[it] + cos(th) * mo.vy.v[it]) })
        val pr = DoubleArray(n) { (it / w).toDouble() }; val pc = DoubleArray(n) { (it % w).toDouble() }
        val walk = DoubleArray(n)
        Array(nLead) { k ->
            for (i in 0 until n) {
                val dy = sampleClamped(my, pr[i], pc[i]); val dx = sampleClamped(mx, pr[i], pc[i])
                pr[i] -= dy; pc[i] -= dx
            }
            val noise = smoothNoise(Rng.stream(seed, m, k), h, w)
            FloatArray(n) { i ->
                walk[i] += sigma * noise.v[i]
                val adv = sampleOrNaN(last, pr[i], pc[i])
                val f = if (adv >= WET_DB) minOf(adv + walk[i], MAX_DB) else adv
                if (f.isNaN()) Float.NaN else fromDb(f).toFloat()
            }
        }
    }
    return EnsembleResult(members, mo, h, w)
}
```

Note: Python's `np.std` is the population std — the `/ size` above matches it.

- [ ] **Step 4: Run tests**

Run: `./gradlew :engine:test --tests nowcast.engine.EnsembleTest`
Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src
git commit -m "STEPS-lite ensemble in Kotlin, matched member-for-member to the gated twin"
```

---

### Task 5: Combine and point curves

**Files:**
- Create: `engine/src/main/kotlin/nowcast/engine/Combine.kt`, `engine/src/main/kotlin/nowcast/engine/Point.kt`
- Test: `engine/src/test/kotlin/nowcast/engine/PointTest.kt`

**Interfaces:**
- Consumes: `Grid`, `Motion`, `sampleClamped`.
- Produces:
  - `object Combine { val WEIGHTS: DoubleArray; fun combine(members: Array<Array<FloatArray>>, other: Array<FloatArray>, w: DoubleArray): Array<Array<FloatArray>> }`
  - `fun pointSeries(frames: List<FloatArray>, h: Int, w: Int, motion: Motion?, row: Int, col: Int, subSteps: Int, box: Int = 1): DoubleArray`
  - `fun percentile(xs: DoubleArray, q: Double): Double` (numpy "linear")
  - `class PointForecast(val minutes: IntArray, val values: DoubleArray, val probability: DoubleArray, val pmin: DoubleArray, val p10: DoubleArray, val p25: DoubleArray, val p75: DoubleArray, val p90: DoubleArray, val pmax: DoubleArray, val rainChance: Double, val summary: String, val inCoverage: Boolean)`
  - `fun summary(minutes: IntArray, series: DoubleArray, probability: DoubleArray, age: Int): String`
  - `const val RAIN_THRESHOLD = 0.1`

- [ ] **Step 1: Failing tests (ported from the Python self-checks)**

```kotlin
package nowcast.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class PointTest {
    // interpolate._demo: a blob crossing at 4 px/frame, closest approach between frames
    @Test fun advectedSeriesRecoversPeakBetweenFrames() {
        val ny = 64; val nx = 64; val vx = 4.0; val sigma = 2.5; val peak = 10.0
        fun field(t: Double) = FloatArray(ny * nx) { i ->
            val y = i / nx; val x = i % nx
            (peak * exp(-((x - (10 + vx * t)).let { it * it } + (y - 32.0) * (y - 32.0)) / (2 * sigma * sigma))).toFloat()
        }
        val frames = (0 until 6).map { field(it.toDouble()) }
        val m = Motion(Grid(ny, nx, DoubleArray(ny * nx) { vx }), Grid(ny, nx))
        val got = pointSeries(frames, ny, nx, m, 32, 28, subSteps = 5, box = 0)
        assertEquals(26, got.size)
        for (k in 0 until 6) assertEquals(frames[k][32 * nx + 28].toDouble(), got[k * 5], 1e-5)
        val truth = DoubleArray(got.size) { field(it / 5.0)[32 * nx + 28].toDouble() }
        assertTrue(got.indices.maxOf { abs(got[it] - truth[it]) } < 0.35)
        assertTrue(abs(got.max() - peak) < 0.35)
    }

    @Test fun percentileIsNumpyLinear() {
        val x = doubleArrayOf(3.0, 1.0, 2.0, 10.0)
        assertEquals(1.3, percentile(x, 10.0), 1e-12)     // np.percentile([3,1,2,10], 10)
        assertEquals(7.9, percentile(x, 90.0), 1e-12)
    }

    @Test fun combineShiftsMeanAndKeepsSpread() {
        val members = Array(3) { m -> Array(1) { FloatArray(1) { 2f + m } } }   // 2, 3, 4
        val other = Array(1) { FloatArray(1) { 5f } }
        val out = Combine.combine(members, other, doubleArrayOf(0.5))
        // mean 3 -> 3 + 0.5*(5-3) = 4; deviations -1, 0, +1 kept
        assertEquals(3f, out[0][0][0], 1e-6f); assertEquals(5f, out[2][0][0], 1e-6f)
    }

    @Test fun summaryWording() {
        val minutes = IntArray(11) { it * 6 - 6 }          // -6..54
        val dry = DoubleArray(11)
        val p0 = DoubleArray(11)
        assertEquals("Dry for the next 54 minutes", summary(minutes, dry, p0, 0))
        val wet = DoubleArray(11) { if (it >= 4) 2.0 else 0.0 }
        val p = DoubleArray(11) { if (it >= 4) 0.8 else 0.0 }
        assertEquals("Rain starting in 18 min (80% chance), up to 2.0 mm/h", summary(minutes, wet, p, 0))
        val now = DoubleArray(11) { if (it <= 3) 1.5 else 0.0 }
        assertEquals("Raining now, 1.5 mm/h, stopping in 18 min", summary(minutes, now, p0, 0))
        val maybe = DoubleArray(11) { if (it == 6) 0.3 else 0.0 }
        assertEquals("Probably dry, but a 30% chance of rain in the next 54 min",
            summary(minutes, dry, maybe, 0))
    }
}
```

- [ ] **Step 2: Run to see failure**

Run: `./gradlew :engine:test --tests nowcast.engine.PointTest`
Expected: compilation failure.

- [ ] **Step 3: Implement `Combine.kt`**

```kotlin
package nowcast.engine

/** Shift the ensemble toward the model, keeping spread. Twin of rain-nowcast/nowcast/combine.py. */
object Combine {
    val WEIGHTS = doubleArrayOf(0.59, 0.59, 0.62, 0.62, 0.63, 0.65, 0.66, 0.66, 0.67, 0.68, 0.68, 0.69)

    fun combine(members: Array<Array<FloatArray>>, other: Array<FloatArray>, w: DoubleArray):
            Array<Array<FloatArray>> {
        val nLead = other.size; val n = other[0].size
        val out = Array(members.size) { Array(nLead) { FloatArray(n) } }
        for (k in 0 until nLead) for (i in 0 until n) {
            var s = 0.0; var c = 0
            for (m in members) { val x = m[k][i]; if (!x.isNaN()) { s += x; c++ } }
            val mean = if (c > 0) s / c else Double.NaN
            val shift = w[minOf(k, w.size - 1)] * (other[k][i] - mean)
            for (m in members.indices) out[m][k][i] = maxOf(0.0, members[m][k][i] + shift).toFloat()
                .let { if (members[m][k][i].isNaN() || mean.isNaN()) Float.NaN else it }
        }
        return out
    }
}
```

- [ ] **Step 4: Implement `Point.kt`**

```kotlin
package nowcast.engine

import kotlin.math.floor
import kotlin.math.roundToInt

const val RAIN_THRESHOLD = 0.1

class PointForecast(
    val minutes: IntArray, val values: DoubleArray, val probability: DoubleArray,
    val pmin: DoubleArray, val p10: DoubleArray, val p25: DoubleArray,
    val p75: DoubleArray, val p90: DoubleArray, val pmax: DoubleArray,
    val rainChance: Double, val summary: String, val inCoverage: Boolean,
)

/** Twin of rain-nowcast/nowcast/interpolate.py:point_series. motion is px per frame step. */
fun pointSeries(frames: List<FloatArray>, h: Int, w: Int, motion: Motion?, row: Int, col: Int,
                subSteps: Int, box: Int = 1): DoubleArray {
    val grids = frames.map { f -> Grid(h, w, DoubleArray(f.size) { f[it].toDouble() }) }
    val offs = (-box..box).flatMap { dr -> (-box..box).map { dc -> dr to dc } }
    val rows = offs.map { (row + it.first).toDouble() }; val cols = offs.map { (col + it.second).toDouble() }
    val vx = rows.indices.map { if (motion == null) 0.0 else sampleClamped(motion.vx, rows[it], cols[it]) }
    val vy = rows.indices.map { if (motion == null) 0.0 else sampleClamped(motion.vy, rows[it], cols[it]) }
    val out = ArrayList<Double>()
    for (k in 0 until grids.size - 1) for (s in 0 until subSteps) {
        val d = s.toDouble() / subSteps
        out += rows.indices.map { j ->
            val back = sampleClamped(grids[k], rows[j] - vy[j] * d, cols[j] - vx[j] * d)
            val fwd = sampleClamped(grids[k + 1], rows[j] + vy[j] * (1 - d), cols[j] + vx[j] * (1 - d))
            (1 - d) * back + d * fwd
        }.average()
    }
    out += rows.indices.map { sampleClamped(grids.last(), rows[it], cols[it]) }.average()
    return out.toDoubleArray()
}

/** numpy.percentile, default "linear" method. */
fun percentile(xs: DoubleArray, q: Double): Double {
    val s = xs.sorted(); val pos = q / 100.0 * (s.size - 1)
    val lo = floor(pos).toInt(); val hi = minOf(lo + 1, s.size - 1)
    return s[lo] + (pos - lo) * (s[hi] - s[lo])
}

/** Twin of server.Nowcaster._summary - wording must not drift. */
fun summary(minutes: IntArray, series: DoubleArray, probability: DoubleArray, age: Int): String {
    val now = minutes.indexOfFirst { it >= age }.let { if (it < 0) minutes.size - 1 else it }
    val future = series.copyOfRange(now, series.size)
    val ahead = IntArray(minutes.size - now) { minutes[now + it] - age }
    val chance = (100 * probability.copyOfRange(now, probability.size).max()).roundToInt()
    if (future[0] >= RAIN_THRESHOLD) {
        val dry = future.indexOfFirst { it < RAIN_THRESHOLD }
        val stops = if (dry >= 0) ", stopping in ${ahead[dry]} min" else ""
        return "Raining now, ${"%.1f".format(future[0])} mm/h$stops"
    }
    val wet = future.indexOfFirst { it >= RAIN_THRESHOLD }
    if (wet >= 0) return "Rain starting in ${ahead[wet]} min ($chance% chance), " +
        "up to ${"%.1f".format(future.copyOfRange(wet, future.size).max())} mm/h"
    if (chance >= 20) return "Probably dry, but a $chance% chance of rain in the next ${ahead.last()} min"
    return "Dry for the next ${ahead.last()} minutes"
}
```

`"%.1f".format` uses the default locale, which on a Dutch or French phone writes `1,5`. Task 10's on-device test checks for a decimal comma; if it fires, change both calls to `String.format(java.util.Locale.ROOT, "%.1f", x)`.

- [ ] **Step 5: Run tests**

Run: `./gradlew :engine:test --tests nowcast.engine.PointTest`
Expected: 4 PASS.

- [ ] **Step 6: Commit**

```bash
git add engine/src
git commit -m "Combine and point curves: advected per-minute series, percentiles, summary wording"
```

---

### Task 6: Projection and regions

**Files:**
- Create: `engine/src/main/kotlin/nowcast/engine/Laea.kt`, `engine/src/main/kotlin/nowcast/engine/Regions.kt`, `app/src/main/assets/regions.json`
- Test: `engine/src/test/kotlin/nowcast/engine/RegionsTest.kt`

**Interfaces:**
- Produces:
  - `class Laea(lat0: Double, lon0: Double, x0: Double, y0: Double) { fun forward(lat: Double, lon: Double): Pair<Double, Double> }`; `Laea.parse(projdef: String): Laea` for `+proj=laea +lat_0=.. +lon_0=.. +x_0=.. +y_0=.. +ellps=WGS84`
  - `@Serializable data class Region(val id: String, val name: String, val bbox: List<Double>, val source: String = "opera", val model: String = "smaat.onnx", val weights: List<Double>? = null, val outlook: String = "icon_d2")` with `fun contains(lat: Double, lon: Double): Boolean`
  - `object Regions { fun parse(json: String): List<Region>; fun pick(regions: List<Region>, lat: Double, lon: Double): Region? }`

- [ ] **Step 1: Failing test (pyproj reference values, computed 2026-10-27)**

```kotlin
package nowcast.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionsTest {
    private val opera = Laea.parse("+proj=laea +lat_0=55.0 +lon_0=10.0 +x_0=1950000.0 +y_0=-2100000.0 +units=m +ellps=WGS84")

    @Test fun laeaMatchesPyproj() {
        fun check(lat: Double, lon: Double, x: Double, y: Double) {
            val (gx, gy) = opera.forward(lat, lon)
            assertEquals(x, gx, 0.5); assertEquals(y, gy, 0.5)
        }
        check(50.85, 4.35, 1552343.7575896685, -2545914.669368048)
        check(49.0, 2.0, 1365249.6702690017, -2734530.7415946946)
        check(51.8, 6.8, 1729263.2655303152, -2451114.123408953)
        check(35.0, -10.0, 120716.40479281009, -4064567.455407303)
    }

    @Test fun pickPrefersTheRegionCentredNearest() {
        val rs = Regions.parse(javaClass.getResource("/regions.json")!!.readText())
        assertEquals("be", Regions.pick(rs, 50.85, 4.35)!!.id)          // Brussels
        assertEquals("nl", Regions.pick(rs, 52.37, 4.90)!!.id)          // Amsterdam
        assertEquals(null, Regions.pick(rs, 40.0, -3.7))                // Madrid
    }
}
```

Also copy the regions file into test resources: `engine/src/test/resources/regions.json` is a symlink-free copy (`cp app/src/main/assets/regions.json engine/src/test/resources/`).

- [ ] **Step 2: Write `app/src/main/assets/regions.json`**

```json
[
 {"id":"be","name":"Belgium","bbox":[2.0,49.0,6.8,51.8]},
 {"id":"nl","name":"Netherlands","bbox":[3.2,50.6,7.3,53.7]},
 {"id":"lu","name":"Luxembourg","bbox":[4.8,48.8,7.4,50.8]},
 {"id":"de-nrw","name":"North Rhine-Westphalia","bbox":[5.7,50.2,9.6,52.6]},
 {"id":"fr-nord","name":"Nord de France","bbox":[1.4,49.4,4.4,51.2]}
]
```

`bbox` is `[lon_min, lat_min, lon_max, lat_max]`, matching `live.BELGIUM_BBOX`. Luxembourg is padded far beyond its border so SmaAt and the motion field have context.

- [ ] **Step 3: Run to see failure**

Run: `cp app/src/main/assets/regions.json engine/src/test/resources/ && ./gradlew :engine:test --tests nowcast.engine.RegionsTest`
Expected: compilation failure.

- [ ] **Step 4: Implement `Laea.kt` (Snyder, ellipsoidal, WGS84)**

```kotlin
package nowcast.engine

import kotlin.math.*

/** Ellipsoidal Lambert azimuthal equal-area (Snyder 1987, eqs 3-12, 24-2..24-15), WGS84. */
class Laea(lat0: Double, lon0: Double, private val x0: Double, private val y0: Double) {
    private val a = 6378137.0
    private val e2 = (2 - 1 / 298.257223563) / 298.257223563
    private val e = sqrt(e2)
    private val lam0 = Math.toRadians(lon0)
    private fun q(phi: Double): Double {
        val s = sin(phi)
        return (1 - e2) * (s / (1 - e2 * s * s) - 1 / (2 * e) * ln((1 - e * s) / (1 + e * s)))
    }
    private val qp = q(PI / 2)
    private val rq = a * sqrt(qp / 2)
    private val phi1 = Math.toRadians(lat0)
    private val beta1 = asin(q(phi1) / qp)
    private val d = a * cos(phi1) / (sqrt(1 - e2 * sin(phi1).pow(2)) * rq * cos(beta1))

    fun forward(lat: Double, lon: Double): Pair<Double, Double> {
        val beta = asin(q(Math.toRadians(lat)) / qp)
        val dl = Math.toRadians(lon) - lam0
        val b = rq * sqrt(2 / (1 + sin(beta1) * sin(beta) + cos(beta1) * cos(beta) * cos(dl)))
        return (x0 + b * d * cos(beta) * sin(dl)) to
            (y0 + (b / d) * (cos(beta1) * sin(beta) - sin(beta1) * cos(beta) * cos(dl)))
    }

    companion object {
        fun parse(projdef: String): Laea {
            val p = Regex("""\+(\w+)=([-\d.]+)""").findAll(projdef).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            require("laea" in projdef) { "only LAEA composites are supported: $projdef" }
            return Laea(p.getValue("lat_0"), p.getValue("lon_0"), p["x_0"] ?: 0.0, p["y_0"] ?: 0.0)
        }
    }
}
```

- [ ] **Step 5: Implement `Regions.kt`**

```kotlin
package nowcast.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Region(
    val id: String, val name: String, val bbox: List<Double>,
    val source: String = "opera", val model: String = "smaat.onnx",
    val weights: List<Double>? = null, val outlook: String = "icon_d2",
) {
    fun contains(lat: Double, lon: Double) = lon in bbox[0]..bbox[2] && lat in bbox[1]..bbox[3]
}

object Regions {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<Region> = json.decodeFromString(text)

    /** The containing region whose centre is nearest; null outside them all. */
    fun pick(regions: List<Region>, lat: Double, lon: Double): Region? =
        regions.filter { it.contains(lat, lon) }.minByOrNull {
            val cy = (it.bbox[1] + it.bbox[3]) / 2; val cx = (it.bbox[0] + it.bbox[2]) / 2
            (lat - cy) * (lat - cy) + (lon - cx) * (lon - cx)
        }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :engine:test --tests nowcast.engine.RegionsTest`
Expected: 2 PASS.

- [ ] **Step 7: Commit**

```bash
git add engine/src app/src/main/assets/regions.json
git commit -m "LAEA projection and the bundled Benelux regions"
```

---

### Task 7: Radar — ORD listing, download, jHDF crop

**Files:**
- Create: `engine/src/main/kotlin/nowcast/engine/Radar.kt`
- Test: `engine/src/test/kotlin/nowcast/engine/RadarTest.kt`, `engine/src/test/resources/opera_sample.h5`, `engine/src/test/resources/ord_listing.json`

**Interfaces:**
- Consumes: `Laea`, `Region`, `Grid`.
- Produces:
  - `class GridGeo(val laea: Laea, val x1: Double, val y2: Double, val px: Double, val py: Double, val h: Int, val w: Int) { fun rowCol(lat: Double, lon: Double): Pair<Int, Int> }`
  - `class Frame(val time: java.time.Instant, val rain: Grid, val geo: GridGeo)`
  - `object Odim { fun readCrop(file: java.io.File, region: Region): Frame; fun dbzToRain(raw: Double, gain: Double, offset: Double, nodata: Double, undetect: Double): Double; fun parseTime(name: String): java.time.Instant? }`
  - `class RadarException(msg: String) : Exception(msg)`
  - `class RadarSource(val apiKey: String?, val cacheDir: java.io.File) { fun listUrls(lookbackMinutes: Int = 100): List<String>; fun download(url: String): java.io.File; fun latestFrames(region: Region, n: Int = 12): List<Frame>; fun prune(maxAgeMinutes: Int = 180) }`
  - *Added later (engine efficiency pass):* `RadarSource.frame(url: String, bbox: List<Double>): Frame` reads a crop through HTTP Range requests (16 kB blocks, cached on disk in `<file>.blocks/`, so a frame is fetched once across cycles and boxes; bit-identical to the whole-file crop; falls back to the whole file); `latestFrames(region, n = 12, bbox = region.bbox)`; `var bytesFetched: Long`; `Odim.readCrop(file, bbox)` and `Odim.crop(hdf, bbox, name)`; `prune(maxAgeMinutes = 70)` (was 180; deletes block caches too); top-level `pointBbox(lat, lon, boxKm): List<Double>`.
  - `fun extractUrls(json: String): List<String>` (internal helper, tested directly)

- [ ] **Step 1: Test fixtures**

`engine/src/test/resources/opera_sample.h5` is already in the working tree (written while this plan was checked): `2026/09/14/OPERA/COMP/OPERA@20260914T0320@0@DBZH.h5` from the CloudFerro archive. The reference path (pysteps `import_odim_hdf5` → `clean_dbz` → `crop_to_belgium` → `to_rainrate`) gives on it: shape **338 × 364**, max **3.9183773079681647** mm/h, **0** NaN, `r[150,180]` = **0.000486** (an undetect pixel: pysteps puts undetect at its threshold, 4.9e-4 mm/h; this port puts it at 0 — inside the 1e-3 tolerance, and the same known difference HANDOVER.md documents for ranged reads). If the file is missing, re-fetch it:

```bash
curl -s -o engine/src/test/resources/opera_sample.h5 \
  "https://s3.waw3-1.cloudferro.com/openradar-archive/2026/09/14/OPERA/COMP/OPERA@20260914T0320@0@DBZH.h5"
```

`engine/src/test/resources/ord_listing.json` — a trimmed real response: run once with the key from `~/Projects/local_weather_model/.env`:
```bash
set -a; source ~/Projects/local_weather_model/.env; set +a
curl -s -H "apikey: $ORD_API_KEY" "https://api.meteogate.eu/eu-eumetnet-weather-radar/collections/observations/locations/0-20010-0-OPERA?datetime=$(date -u -d '-30 min' +%Y-%m-%dT%H:%MZ)/$(date -u +%Y-%m-%dT%H:%MZ)&standard_name=DBZH&format=ODIM&method=comp&f=CoverageJSON" > engine/src/test/resources/ord_listing.json
grep -o '\.h5' engine/src/test/resources/ord_listing.json | wc -l    # expect >= 3
```

- [ ] **Step 2: Failing test**

```kotlin
package nowcast.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RadarTest {
    private val be = Region("be", "Belgium", listOf(2.0, 49.0, 6.8, 51.8))
    private val sample = File(javaClass.getResource("/opera_sample.h5")!!.toURI())

    // From the reference path (Step 1), pysteps + clean_dbz + crop_to_belgium.
    private val EXPECTED_H = 338; private val EXPECTED_W = 364
    private val EXPECTED_MAX = 3.9183773079681647; private val EXPECTED_NAN = 0
    private val EXPECTED_150_180 = 0.000486

    @Test fun cropMatchesReference() {
        val f = Odim.readCrop(sample, be)
        assertEquals(EXPECTED_H, f.rain.h); assertEquals(EXPECTED_W, f.rain.w)
        assertEquals(EXPECTED_NAN, f.rain.v.count { it.isNaN() })
        assertEquals(EXPECTED_MAX, f.rain.v.filter { !it.isNaN() }.max(), 1e-3)
        assertEquals(EXPECTED_150_180, f.rain[150, 180], 1e-3)
        assertEquals(java.time.Instant.parse("2026-09-14T03:20:00Z"), f.time)
    }

    @Test fun cleaningMasksFillAndClipsClutter() {
        assertTrue(Odim.dbzToRain(128.0, 1.0, 0.0, -9999000.0, -8888000.0).isNaN())
        assertTrue(Odim.dbzToRain(-9999000.0, 1.0, 0.0, -9999000.0, -8888000.0).isNaN())
        assertEquals(0.0, Odim.dbzToRain(-8888000.0, 1.0, 0.0, -9999000.0, -8888000.0), 0.0)
        assertEquals(Odim.dbzToRain(55.0, 1.0, 0.0, -1.0, -2.0), Odim.dbzToRain(78.0, 1.0, 0.0, -1.0, -2.0), 1e-9)
        assertEquals(1.0, Odim.dbzToRain(10 * kotlin.math.log10(200.0), 1.0, 0.0, -1.0, -2.0), 1e-9)
    }

    @Test fun listingIsParsedOldestFirst() {
        val urls = extractUrls(javaClass.getResource("/ord_listing.json")!!.readText())
        assertTrue(urls.size >= 3)
        assertEquals(urls.sortedBy { Odim.parseTime(it) }, urls)
    }

    @Test fun pointLandsInTheCrop() {
        val f = Odim.readCrop(sample, be)
        val (r, c) = f.geo.rowCol(50.85, 4.35)
        assertTrue(r in 0 until f.rain.h && c in 0 until f.rain.w)
    }
}
```

- [ ] **Step 3: Run, see failure**

Run: `./gradlew :engine:test --tests nowcast.engine.RadarTest`
Expected: compilation failure (`Odim` unresolved).

- [ ] **Step 4: Implement `Radar.kt`**

```kotlin
package nowcast.engine

import io.jhdf.HdfFile
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

class RadarException(msg: String) : Exception(msg)

class GridGeo(val laea: Laea, val x1: Double, val y2: Double, val px: Double, val py: Double, val h: Int, val w: Int) {
    fun rowCol(lat: Double, lon: Double): Pair<Int, Int> {
        val (x, y) = laea.forward(lat, lon)
        return ((y2 - y) / py).roundToInt() to ((x - x1) / px).roundToInt()
    }
}

class Frame(val time: Instant, val rain: Grid, val geo: GridGeo)

object Odim {
    const val FILL_DBZ = 128.0
    const val MAX_DBZ = 55.0

    /** clean_dbz + Marshall-Palmer (a=200, b=1.6). nodata/fill -> NaN, undetect -> 0. */
    fun dbzToRain(raw: Double, gain: Double, offset: Double, nodata: Double, undetect: Double): Double {
        if (raw == nodata) return Double.NaN
        if (raw == undetect) return 0.0
        val dbz = raw * gain + offset
        if (dbz >= FILL_DBZ) return Double.NaN
        return (10.0.pow(minOf(dbz, MAX_DBZ) / 10.0) / 200.0).pow(1 / 1.6)
    }

    fun parseTime(name: String): Instant? =
        Regex("""(\d{8}T\d{4})""").find(name)?.groupValues?.get(1)?.let {
            java.time.LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")).toInstant(ZoneOffset.UTC)
        }

    private fun attr(f: HdfFile, path: String, key: String): Any = f.getByPath(path).getAttribute(key)!!.data
    private fun num(a: Any): Double = when (a) {
        is Number -> a.toDouble(); is DoubleArray -> a[0]; is LongArray -> a[0].toDouble()
        is Array<*> -> (a[0] as Number).toDouble(); else -> error("attr $a")
    }

    /** Crop to region.bbox exactly as live.crop_to_belgium: project the four corners. */
    fun readCrop(file: File, region: Region): Frame = HdfFile(file.toPath()).use { f ->
        val projdef = attr(f, "where", "projdef").toString()
        val laea = Laea.parse(projdef)
        val nx = num(attr(f, "where", "xsize")).toInt(); val ny = num(attr(f, "where", "ysize")).toInt()
        val xs = num(attr(f, "where", "xscale")); val ys = num(attr(f, "where", "yscale"))
        val (x1, _) = laea.forward(num(attr(f, "where", "LL_lat")), num(attr(f, "where", "LL_lon")))
        val (_, y2) = laea.forward(num(attr(f, "where", "UR_lat")), num(attr(f, "where", "UR_lon")))
        val (lo0, la0, lo1, la1) = region.bbox
        val corners = listOf(la0 to lo0, la0 to lo1, la1 to lo1, la1 to lo0).map { laea.forward(it.first, it.second) }
        val c0 = floor((corners.minOf { it.first } - x1) / xs).toInt().coerceIn(0, nx)
        val c1 = floor((corners.maxOf { it.first } - x1) / xs).toInt().coerceIn(0, nx)
        val r0 = floor((y2 - corners.maxOf { it.second }) / ys).toInt().coerceIn(0, ny)
        val r1 = floor((y2 - corners.minOf { it.second }) / ys).toInt().coerceIn(0, ny)
        val ds = f.getDatasetByPath("dataset1/data1/data")
        val what = "dataset1/data1/what"
        val gain = num(attr(f, what, "gain")); val offset = num(attr(f, what, "offset"))
        val nodata = num(attr(f, what, "nodata")); val undetect = num(attr(f, what, "undetect"))
        @Suppress("UNCHECKED_CAST")
        val slab = ds.getData(longArrayOf(r0.toLong(), c0.toLong()), intArrayOf(r1 - r0, c1 - c0)) as Array<DoubleArray>
        val h = r1 - r0; val w = c1 - c0
        val g = Grid(h, w, DoubleArray(h * w) { dbzToRain(slab[it / w][it % w], gain, offset, nodata, undetect) })
        val date = attr(f, "what", "date").toString(); val time = attr(f, "what", "time").toString()
        Frame(parseTime("${date}T${time.take(4)}") ?: parseTime(file.name)!!, g,
            GridGeo(laea, x1 + c0 * xs, y2 - r0 * ys, xs, ys, h, w))
    }
}

/** (time, url) pairs anywhere in the CoverageJSON-ish response, oldest first. Twin of live._extract_download_urls. */
fun extractUrls(json: String): List<String> {
    val found = ArrayList<Pair<String, String>>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                val u = (e["href"] ?: e["url"] ?: e["download"])?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (u != null && u.lowercase().substringBefore('?').let { it.endsWith(".h5") || it.endsWith(".hdf") || it.endsWith(".hdf5") })
                    found += ((e["datetime"] ?: e["time"])?.jsonPrimitive?.contentOrNull ?: "") to u
                e.values.forEach(::walk)
            }
            is JsonArray -> e.forEach(::walk)
            else -> {}
        }
    }
    walk(Json.parseToJsonElement(json))
    return found.sortedBy { Odim.parseTime(it.second) ?: Instant.EPOCH }.map { it.second }.distinct()
}

class RadarSource(private val apiKey: String?, private val cacheDir: File) {
    private val base = "https://api.meteogate.eu/eu-eumetnet-weather-radar/collections/observations/locations/0-20010-0-OPERA"

    fun listUrls(lookbackMinutes: Int = 100): List<String> {
        val now = Instant.now(); val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC)
        val q = mapOf("datetime" to "${fmt.format(now.minusSeconds(lookbackMinutes * 60L))}/${fmt.format(now)}",
            "standard_name" to "DBZH", "format" to "ODIM", "method" to "comp", "f" to "CoverageJSON")
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val c = URI("$base?$q").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 30_000; c.readTimeout = 30_000
        // "apikey" is the only header ORD accepts (live._session); never put the key in the URL.
        apiKey?.let { c.setRequestProperty("apikey", it) }
        when (c.responseCode) {
            429 -> throw RadarException("Radar API rate limit hit (resets in ${c.getHeaderField("X-RateLimit-Reset") ?: "?"} s)")
            401, 403 -> throw RadarException("Radar API rejected the key")
            !in 200..299 -> throw RadarException("Radar API HTTP ${c.responseCode}")
        }
        val urls = extractUrls(c.inputStream.bufferedReader().readText())
        if (urls.isEmpty()) throw RadarException("No radar frames in the last $lookbackMinutes min")
        return urls
    }

    /** Bare request, no headers: the URL is a pre-signed S3 link that rejects ours (live.download_composite). */
    fun download(url: String): File {
        cacheDir.mkdirs()
        val out = File(cacheDir, url.substringBefore('?').substringAfterLast('/'))
        if (!out.exists()) {
            val tmp = File(cacheDir, out.name + ".part")
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 30_000; c.readTimeout = 60_000
            if (c.responseCode !in 200..299) throw RadarException("Download HTTP ${c.responseCode}")
            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(out)
        }
        return out
    }

    fun latestFrames(region: Region, n: Int = 12): List<Frame> =
        listUrls().takeLast(n).map { Odim.readCrop(download(it), region) }

    fun prune(maxAgeMinutes: Int = 180) {
        val cut = System.currentTimeMillis() - maxAgeMinutes * 60_000L
        cacheDir.listFiles()?.filter { it.lastModified() < cut }?.forEach { it.delete() }
    }
}
```

jHDF note: `Dataset.getData(long[] sliceOffset, int[] sliceDimensions)` reads only the touched chunks. If the 0.12.0 API differs, check `https://javadoc.io/doc/io.jhdf/jhdf/0.12.0/io/jhdf/api/Dataset.html` for the slice method and adapt that one call — reading the whole 4400×3800 grid (134 MB) is not an acceptable fallback on a phone.

- [ ] **Step 5: Run tests**

Run: `./gradlew :engine:test --tests nowcast.engine.RadarTest`
Expected: 4 PASS. If `cropMatchesReference` is off by exactly one row/col, the corner rounding differs from `crop_to_belgium`'s `int(...)` — Python's `int` truncates toward zero, which equals `floor` here only because all offsets are positive; check the sign of `y2 - y`.

- [ ] **Step 6: Commit**

```bash
git add engine/src
git commit -m "Radar: ORD listing and download, jHDF chunk-sliced crop, dBZ cleaning"
```

---

### Task 8: SmaAt-UNet via onnxruntime

**Files:**
- Create: `engine/src/main/kotlin/nowcast/engine/Model.kt`, `app/src/main/assets/smaat.onnx` (copy of `~/Projects/local_weather_model/rain-nowcast/models/smaat.onnx`)
- Test: `engine/src/test/kotlin/nowcast/engine/ModelTest.kt`

**Interfaces:**
- Produces: `class SmaatModel(modelBytes: ByteArray) : AutoCloseable { fun forecast(train: List<Grid>, nLead: Int): Array<FloatArray> }` (returns `nLead` fields of `h*w`, mm/h); `object Transform { fun toModel(x: Double): Float; fun fromModel(y: Float): Float }`; throws `ModelException` on non-finite or > 500 mm/h.

- [ ] **Step 1: Failing test (ported from `mlmodel._demo`)**

```kotlin
package nowcast.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelTest {
    private val bytes = File(System.getProperty("user.dir"), "../app/src/main/assets/smaat.onnx").readBytes()

    @Test fun transformsRoundTripAndClamp() {
        for (x in listOf(0.0, 0.1, 1.0, 10.0, 100.0))
            assertEquals(x, Transform.fromModel(Transform.toModel(x)).toDouble(), 1e-3 * (1 + x))
        assertTrue(Transform.fromModel(2.0f) <= 100.001f)
        assertEquals(0f, Transform.toModel(Double.NaN))
    }

    @Test fun runsAtTheLiveSizeAndKeepsDryDry() {
        SmaatModel(bytes).use { m ->
            val rng = java.util.Random(0)
            val train = List(12) { Grid(338, 364, DoubleArray(338 * 364) { -2.0 * kotlin.math.ln(1 - rng.nextDouble()) }) }
            val out = m.forecast(train, 12)
            assertEquals(12, out.size); assertEquals(338 * 364, out[0].size)
            assertTrue(out.all { f -> f.all { it.isFinite() && it >= 0f && it <= 100.001f } })
            val dry = m.forecast(List(12) { Grid(64, 64) }, 12)
            assertTrue(dry.all { f -> f.all { it < 1f } })
        }
    }
}
```

- [ ] **Step 2: Copy the model, run, see failure**

```bash
cp ~/Projects/local_weather_model/rain-nowcast/models/smaat.onnx app/src/main/assets/smaat.onnx
./gradlew :engine:test --tests nowcast.engine.ModelTest
```
Expected: compilation failure.

- [ ] **Step 3: Implement `Model.kt`**

```kotlin
package nowcast.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p

class ModelException(msg: String) : Exception(msg)

/** Twin of mlmodel.to_model / from_model. */
object Transform {
    private val LOG_SCALE = ln(101.0)
    fun toModel(x: Double): Float = if (x.isNaN()) 0f else (ln1p(maxOf(x, 0.0)) / LOG_SCALE).toFloat()
    fun fromModel(y: Float): Float = expm1(y.toDouble().coerceIn(0.0, 1.0) * LOG_SCALE).toFloat()
}

/** SmaAt-UNet, 12 in -> 12 out. Same session settings as mlmodel._session (threads <= 4, no arena). */
class SmaatModel(modelBytes: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
        setCPUArenaAllocator(false)
    })

    fun forecast(train: List<Grid>, nLead: Int): Array<FloatArray> {
        val frames = train.takeLast(12)
        val h = frames[0].h; val w = frames[0].w
        val ph = (16 - h % 16) % 16; val pw = (16 - w % 16) % 16
        val H = h + ph; val W = w + pw
        val buf = FloatBuffer.allocate(12 * H * W)
        for (f in frames) for (r in 0 until H) for (c in 0 until W)
            buf.put(if (r < h && c < w) Transform.toModel(f[r, c]) else 0f)
        buf.rewind()
        val y = OnnxTensor.createTensor(env, buf, longArrayOf(1, 12, H.toLong(), W.toLong())).use { x ->
            session.run(mapOf("x" to x)).use { res ->
                (res[0].value as Array<Array<Array<FloatArray>>>)[0]
            }
        }
        val out = Array(nLead) { k -> FloatArray(h * w) { Transform.fromModel(y[k][it / w][it % w]) } }
        if (out.any { f -> f.any { !it.isFinite() } }) throw ModelException("model produced non-finite rain rates")
        val mx = out.maxOf { f -> f.max() }
        if (mx > 500f) throw ModelException("model produced ${mx.toInt()} mm/h - refusing to serve it")
        return out
    }

    override fun close() = session.close()
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :engine:test --tests nowcast.engine.ModelTest`
Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src app/src/main/assets/smaat.onnx
git commit -m "SmaAt-UNet through onnxruntime, same transforms and refusals as the add-on"
```

---

### Task 9: Nowcaster — one cycle end to end

**Files:**
- Create: `engine/src/main/kotlin/nowcast/engine/Nowcaster.kt`
- Test: `engine/src/test/kotlin/nowcast/engine/NowcasterTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces (Plan 2's entry points):
  - `class Forecast(val region: Region, val geo: GridGeo, val past: List<Frame>, val members: Array<Array<FloatArray>>, val mean: Array<FloatArray>, val motion: Motion, val issued: java.time.Instant, val modelUsed: Boolean, val modelError: String?) { val intervalMinutes: Int; fun point(lat: Double, lon: Double, now: java.time.Instant = java.time.Instant.now()): PointForecast }`
  - `class Nowcaster(private val model: SmaatModel?, private val nMembers: Int = 10, private val seed: Long = 42) { fun run(region: Region, frames: List<Frame>): Forecast }`
  - *Added later (engine efficiency pass):* `fun runPoint(region: Region, frames: List<Frame>, lat: Double, lon: Double, boxKm: Double = 128.0, now: Instant = Instant.now()): PointForecast` — `run` on a boxKm square around the point (clamped inside `frames`, which may be region frames or `RadarSource.latestFrames(region, bbox = pointBbox(lat, lon, boxKm))`), then `point`. For the background notification only; the app still does the full `run` when opened. Also `Frame.crop(r0, c0, h, w)`.
  - *Speed pass:* `Forecast.timings` / `EnsembleResult.timings` (seconds per stage: flow, setup, members, model, combine, mean; plus the `threads` and `ways` used). `Combine.combine` now works in place on `members`. Members' output is bit-identical to the previous engine (fixture parity unchanged, and 118 M member values on 8 archive windows compared equal). How: the STEPS cascade's AR recursion runs on half spectra (one inverse FFT per two leads instead of five FFTs per lead), radix-2/4 and symmetric odd-radix FFT butterflies, a stable radix argsort over the wet pixels only, the mask as a clamped L1 distance transform, `toInt` for `floor` in bilinear sampling (ART's `floor` was most of the AVD time), reused per-member buffers, and pixel loops split `ways`-fold (`Par`) when the heap allows fewer member threads than cores. AVD (4 cores, 192 MB): cycle 22.1 s -> 6.2 s; JVM: 2.0 s -> 0.75 s. Measured against the full run at Brussels on 8 archive windows (`PointBoxEval`, opt-in via `NOWCAST_WINDOWS`): ~10x faster (0.24-0.39 s vs 2.8-3.4 s JVM); future probability differs by mean 0.00-0.30 (max 1.0 on one convective window), about the seed-to-seed noise floor on 6 of 8 windows; 192 km halves the worst errors at ~2x the cost.

- [ ] **Step 1: Failing test (synthetic frames, no network, no model file)**

```kotlin
package nowcast.engine

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.exp

class NowcasterTest {
    private val be = Region("be", "Belgium", listOf(2.0, 49.0, 6.8, 51.8))
    private val geo = GridGeo(Laea.parse("+proj=laea +lat_0=55.0 +lon_0=10.0 +x_0=1950000.0 +y_0=-2100000.0"),
        1365000.0, -2450000.0, 1000.0, 1000.0, 120, 140)

    private fun frames(): List<Frame> {
        val t0 = Instant.parse("2026-10-27T12:00:00Z")
        return (0 until 12).map { t ->
            Frame(t0.plusSeconds(300L * t), Grid(120, 140, DoubleArray(120 * 140) { i ->
                val y = i / 140; val x = i % 140
                4.0 * exp(-(((x - 20 - 3.0 * t) * (x - 20 - 3.0 * t) + (y - 60.0) * (y - 60.0)) / 80.0))
            }), geo)
        }
    }

    @Test fun cycleWithoutModelProducesCoherentPoint() {
        val fc = Nowcaster(model = null, nMembers = 4).run(be, frames())
        assertFalse(fc.modelUsed)
        assertEquals(5, fc.intervalMinutes)
        assertEquals(4, fc.members.size); assertEquals(12, fc.members[0].size)
        // a pixel in the blob's path, ahead of it: rain should be forecast to arrive
        val (lat, lon) = 50.0 to 4.0
        val p = fc.point(lat, lon, now = Instant.parse("2026-10-27T12:58:00Z"))
        assertEquals(p.minutes.size, p.values.size)
        assertEquals(-55, p.minutes.first()); assertEquals(60, p.minutes.last())
        assertTrue(p.probability.all { it in 0.0..1.0 })
        assertTrue(p.p10.indices.all { p.p10[it] <= p.values[it] + 1e-9 && p.values[it] <= p.p90[it] + 1e-9 })
        assertTrue(p.summary.isNotBlank())
    }
}
```

The test's `geo` is a hand-made grid; `fc.point` must clamp an out-of-coverage point to the edge and report `inCoverage = false` rather than throw — `assertTrue(p.summary.isNotBlank())` covers both branches whichever side 50.0/4.0 lands.

- [ ] **Step 2: Run, see failure**

Run: `./gradlew :engine:test --tests nowcast.engine.NowcasterTest`
Expected: compilation failure.

- [ ] **Step 3: Implement `Nowcaster.kt`**

```kotlin
package nowcast.engine

import java.time.Duration
import java.time.Instant

class Forecast(
    val region: Region, val geo: GridGeo, val past: List<Frame>,
    val members: Array<Array<FloatArray>>, val mean: Array<FloatArray>, val motion: Motion,
    val issued: Instant, val modelUsed: Boolean, val modelError: String?,
) {
    /** Median frame gap in whole minutes - never ODIM accutime, which lies (it says 15). */
    val intervalMinutes: Int = past.zipWithNext { a, b -> Duration.between(a.time, b.time).toMinutes() }
        .filter { it > 0 }.sorted().let { if (it.isEmpty()) 5 else it[it.size / 2].toInt() }

    /** Twin of server.Nowcaster._point_state. */
    fun point(lat: Double, lon: Double, now: Instant = Instant.now()): PointForecast {
        val h = geo.h; val w = geo.w
        val (r0, c0) = geo.rowCol(lat, lon)
        val inCoverage = r0 in 0 until h && c0 in 0 until w
        val row = r0.coerceIn(0, h - 1); val col = c0.coerceIn(0, w - 1)
        val pastF = past.map { f -> FloatArray(h * w) { f.rain.v[it].toFloat() } }
        val first = -intervalMinutes * (past.size - 1); val last = intervalMinutes * mean.size
        val minutes = IntArray(last - first + 1) { first + it }
        val series = pointSeries(pastF + mean.toList(), h, w, motion, row, col, intervalMinutes)
        val curves = members.map { m -> pointSeries(pastF + m.toList(), h, w, motion, row, col, intervalMinutes) }
        val prob = DoubleArray(minutes.size) { t -> curves.count { it[t] >= RAIN_THRESHOLD }.toDouble() / curves.size }
        fun band(q: Double, lower: Boolean) = DoubleArray(minutes.size) { t ->
            val p = percentile(DoubleArray(curves.size) { curves[it][t] }, q)
            if (lower) minOf(p, series[t]) else maxOf(p, series[t])
        }
        val age = maxOf(0L, Duration.between(past.last().time, now).toMinutes()).toInt()
        val zero = minutes.indexOfFirst { it >= 0 }
        return PointForecast(
            minutes, series, prob,
            DoubleArray(minutes.size) { t -> minOf(curves.minOf { it[t] }, series[t]) },
            band(10.0, true), band(25.0, true), band(75.0, false), band(90.0, false),
            DoubleArray(minutes.size) { t -> maxOf(curves.maxOf { it[t] }, series[t]) },
            prob.copyOfRange(zero, prob.size).max(), summary(minutes, series, prob, age), inCoverage)
    }
}

class Nowcaster(private val model: SmaatModel?, private val nMembers: Int = 10, private val seed: Long = 42) {
    fun run(region: Region, frames: List<Frame>): Forecast {
        val train = frames.map { it.rain }
        val ens = ensemble(train, 12, nMembers, seed)
        var members = ens.members; var used = false; var err: String? = null
        if (model != null) {
            try {
                val ml = model.forecast(train, 12)
                val w = region.weights?.toDoubleArray() ?: Combine.WEIGHTS
                members = Combine.combine(members, ml, w); used = true
            } catch (e: Exception) {
                // A nowcast that stops because a network could not run is worse than one without it.
                err = "${e::class.simpleName}: ${e.message}"
            }
        }
        val n = ens.h * ens.w
        val mean = Array(12) { k -> FloatArray(n) { i ->
            var s = 0.0; var c = 0
            for (m in members) { val x = m[k][i]; if (!x.isNaN()) { s += x; c++ } }
            if (c > 0) (s / c).toFloat() else Float.NaN
        } }
        return Forecast(region, frames.last().geo, frames, members, mean, ens.motion,
            frames.last().time, used, err)
    }
}
```

- [ ] **Step 4: Run all engine tests**

Run: `./gradlew :engine:test`
Expected: every test PASS.

- [ ] **Step 5: Commit**

```bash
git add engine/src
git commit -m "Nowcaster: ensemble, model shift with graceful fallback, per-point forecast"
```

---

### Task 10: The engine on the AVD

**Files:**
- Create: `app/src/androidTest/kotlin/nowcast/app/EngineOnDeviceTest.kt`, `app/src/androidTest/assets/opera_sample.h5` (copy of the engine test resource)

**Interfaces:** Consumes `Odim`, `Regions`, `SmaatModel`, `Nowcaster`. Proves jHDF and onnxruntime-android work on ART at API 26+ and measures cycle time on the device.

- [ ] **Step 1: Write the instrumented test**

```kotlin
package nowcast.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nowcast.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    @Test fun fullCycleOnDevice() {
        val sample = File(ctx.cacheDir, "opera_sample.h5")
        testCtx.assets.open("opera_sample.h5").use { i -> sample.outputStream().use { i.copyTo(it) } }
        val regions = Regions.parse(ctx.assets.open("regions.json").bufferedReader().readText())
        val be = regions.first { it.id == "be" }

        val t0 = System.nanoTime()
        val frame = Odim.readCrop(sample, be)
        val frames = List(12) { k -> Frame(frame.time.plusSeconds(300L * (k - 11)), frame.rain, frame.geo) }
        val model = SmaatModel(ctx.assets.open("smaat.onnx").readBytes())
        val fc = Nowcaster(model).run(be, frames)
        val p = fc.point(50.85, 4.35, fc.issued)
        val secs = (System.nanoTime() - t0) / 1e9
        Log.i("EngineOnDevice", "cycle %.1f s, model=%s, summary=%s".format(secs, fc.modelUsed, p.summary))

        assertTrue("model must run on device: ${fc.modelError}", fc.modelUsed)
        assertEquals(10, fc.members.size)
        assertTrue(p.inCoverage)
        assertTrue(p.probability.all { it in 0.0..1.0 })
        assertFalse("decimal comma: ${p.summary}", Regex("""\d,\d""").containsMatchIn(p.summary))
        assertTrue("cycle took $secs s", secs < 120)
    }
}
```

- [ ] **Step 2: Boot the AVD and run**

```bash
mkdir -p app/src/androidTest/assets && cp engine/src/test/resources/opera_sample.h5 app/src/androidTest/assets/
~/Android/emulator/emulator -avd nowcast-engine -port 5584 -no-window -no-audio -no-snapshot &
~/Android/platform-tools/adb wait-for-device shell 'while [ -z "$(getprop sys.boot_completed)" ]; do sleep 2; done'
./gradlew :app:connectedDebugAndroidTest
~/Android/platform-tools/adb logcat -d -s EngineOnDevice
```
Expected: `BUILD SUCCESSFUL`, 1 test passed, and a logcat line like `cycle NN.N s, model=true, summary=...`. Record the cycle time in the commit message — it sets the foreground refresh budget for Plan 2. If the summary contains a decimal comma, switch both `"%.1f".format` calls in `Point.kt` to `String.format(java.util.Locale.ROOT, "%.1f", x)` and rerun.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest
git commit -m "Engine runs end to end on the x86_64 AVD (cycle <N> s)"
```

---

## What comes after this plan

- **Plan 2 — UI:** Compose screens (Now with the Nowcast/48 h toggle, Map with MapLibre, Settings), rendering overlays from `Forecast.mean`/`past`, foreground refresh, first-run API-key screen.
- **Plan 3 — Outlook in the app:** Open-Meteo deterministic fetch, `outlook_gbm.onnx` with the `outlook_gbm.json` feature contract, ICON-D2-EPS fallback, 0–3 h nowcast blend, 30-min bars.
- **Plan 4 — Regions editor, model upload, widget, notifications:** SAF model import with validation pass, per-source keys in EncryptedSharedPreferences, WorkManager, Glance widget.

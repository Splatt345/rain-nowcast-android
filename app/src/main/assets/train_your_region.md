# Your own region

This app ships with a nowcast model trained on Belgian radar. This guide covers
what you can do somewhere else with the app alone, and, if you have a computer
with Python, how to train a model for your own region and load it into the app.
Everything you need is on this page.

| what you want | where | what it takes |
|---|---|---|
| **The next hour, anywhere on Earth** | everywhere | nothing: switch to Open-Meteo |
| **Radar and a radar nowcast** | Europe | a free MeteoGate key and a region box |
| **A model trained on your weather** | Europe | a computer, about two days of downloading, a few hours of GPU time |

## Anywhere: Open-Meteo

**Settings → Next hour from → Open-Meteo.** The Now screen and the widgets then
use Open-Meteo's 15-minute forecasts from several weather models, treated as a
small ensemble. It needs no key, works anywhere on Earth and is free for
non-commercial use.

It is model output, not observed rain, and there is no radar map. A shower that
is already overhead but that the models missed will not show. Where there is
radar (Europe), the radar nowcast is better for the first hour.

## In Europe: radar for your region

The radar is EUMETNET OPERA's European composite: 5-minute frames on a 1 km
grid, covering roughly 30–75° N and 30° W–45° E.

**Get a key.** Register on the MeteoGate portal at
[devportal.meteogate.eu](https://devportal.meteogate.eu/) and create an API
key. Paste it into **Settings → MeteoGate ORD key**. The app keeps it encrypted
on the phone and sends it only to MeteoGate.

**Add your region.** Go to **Settings → Regions and models → Add region**:

- **Name** and **Id**: anything. The Id is lower-case letters, digits and
  dashes.
- **The box:** pan and zoom the small map until it shows your area, then tap
  **Use visible area**. Alternatively, type **West, South, East, North** in
  degrees. The app downloads radar for exactly this box.
- **Radar source:** OPERA, the only one the app reads.
- **Model:** the bundled one to start with. See below.

How to draw a good box:

- **Leave room upwind.** Rain can only be forecast if it is already on the
  radar. In most of Europe it comes from the west or southwest, so put home in
  the eastern half of the box with 100–150 km of radar west of it.
- **Not too big.** The whole box is computed on the phone, ten times over for
  the ensemble, so time and memory grow with its area. The bundled boxes are
  about 350 × 350 km. The app refuses boxes over 15° on a side.
- **Overlapping boxes are fine.** With **Region: Automatic**, home gets the box
  it is deepest inside, so that rain has the most room to arrive from. If you
  pick a region by hand, the app loads that region even when home is outside it,
  and the Now screen says so.

**Choose a model, live.** On the map, the chip button under the colour button
switches the region on screen between its models:

- **smaat (bundled):** trained on Belgium. It still helps elsewhere in western
  Europe, but it has never seen your weather, and the Now screen says so.
- **Ensemble only:** the radar moved along its own motion, ten times with small
  differences, and no neural network. It is honest everywhere, and the safer
  choice far from Belgium (mountains, the Mediterranean, the far north).
- **Your own:** anything you upload (see the last part of this guide).

The forecast reruns as soon as you pick, on the radar already downloaded. Drag
along the timeline under the map and compare how each handles the same
shower.

**Per-lead weights** (optional, in the region editor): 12 numbers from 0 to 1,
one for each 5 minutes ahead. They set how far the model pulls the ensemble
towards its own forecast: 0 ignores the model, 1 moves the ensemble's mean all
the way to it. Blank uses the bundled 0.59 rising to 0.69. Lower them if your
model is only good for the first minutes.

## Train your own nowcast model

This part needs a computer. A GPU with 8 GB or more makes it a few hours instead
of days. The scripts below are complete: copy each into a file and run it. They
use Python 3.10 or newer:

```bash
python -m venv rain-env && . rain-env/bin/activate
pip install numpy requests h5py pyproj torch onnx onnxruntime
```

### What the app expects

Any model that meets this contract can be uploaded. The bundled one is a
SmaAt-UNet. The small U-Net below is simpler and also meets it.

| part | the contract |
|---|---|
| input | named `x`, float32, shape [1, 12, H, W]: the last 12 radar frames, 5 minutes apart, oldest first |
| units in | `ln(1 + rain) / ln(101)`, rain in mm/h, no radar as 0. So 0 mm/h is 0 and 100 mm/h is 1 |
| size | H and W are whatever the region's box is, padded with zeros to a multiple of 16. The model must accept any size (dynamic axes) |
| output | the first output, shape [1, 12, H, W]: 5, 10, … 60 minutes ahead, in the same units. The app clips it to 0–1 and turns it back into mm/h |
| checks | on upload the app runs it once on a moving test shower. All 12 frames must come back finite and at most 500 mm/h, or it is refused |

### Step 1: download radar history

OPERA keeps an open archive with no key needed. The 1 km, 5-minute product
("CIRRUS") starts in August 2024. Older files are a different product (2 km,
15 minutes); do not mix them in.

This script draws random days from that period, so every season is covered.
It cuts a 384 × 384 km square around your centre, converts it to the model's
units, and saves one file per day. Set **LAT** and **LON** to the centre of your
region's box. Each day is about 780 MB to download (288 files) and 85 MB to
keep. You want about 150 days:

```python
# harvest.py: python harvest.py 150
import datetime as dt, io, os, random, re, sys
import h5py, numpy as np, pyproj, requests

LAT, LON = 50.5, 4.5          # your region's centre
SIZE = 384                    # km = pixels; a multiple of 16
BUCKET = "https://s3.waw3-1.cloudferro.com/openradar-archive"
LOG = np.log1p(100.0)

def keys(day):
    out, token = [], None
    while True:
        p = {"list-type": "2", "max-keys": "1000",
             "prefix": f"{day:%Y/%m/%d}/OPERA/COMP/"}
        if token:
            p["continuation-token"] = token
        t = requests.get(BUCKET, params=p, timeout=60).text
        out += re.findall(r"<Key>([^<]+)</Key>", t)
        m = re.search(r"<NextContinuationToken>([^<]+)<", t)
        if not m:
            break
        token = m.group(1)
    # the 1 km 5-minute product only
    return sorted(k for k in out if k.endswith("@0@DBZH.h5"))

def frame(blob):
    with h5py.File(io.BytesIO(blob), "r") as f:
        s = lambda v: v.decode() if isinstance(v, bytes) else v
        w, q = f["where"].attrs, f["dataset1/data1/what"].attrs
        proj = pyproj.Proj(s(w["projdef"]))
        x1, _ = proj(float(w["LL_lon"]), float(w["LL_lat"]))
        _, y2 = proj(float(w["UR_lon"]), float(w["UR_lat"]))
        x, y = proj(LON, LAT)
        r0 = round((y2 - y) / float(w["yscale"]) - SIZE / 2)
        c0 = round((x - x1) / float(w["xscale"]) - SIZE / 2)
        raw = f["dataset1/data1/data"][r0:r0 + SIZE, c0:c0 + SIZE].astype(float)
        if r0 < 0 or c0 < 0 or raw.shape != (SIZE, SIZE):
            sys.exit("the square leaves OPERA's grid: move LAT/LON")
        dbz = raw * float(q["gain"]) + float(q["offset"])
        # Marshall-Palmer, with clutter clipped at 55 dBZ (the app does the same)
        rain = (10 ** (np.minimum(dbz, 55) / 10) / 200) ** (1 / 1.6)
        rain[raw == float(q["undetect"])] = 0
        rain[(raw == float(q["nodata"])) | (dbz >= 128)] = np.nan
        when = s(f["what"].attrs["date"]) + s(f["what"].attrs["time"])[:4]
    t = dt.datetime.strptime(when, "%Y%m%d%H%M").replace(tzinfo=dt.timezone.utc)
    return rain, int(t.timestamp())

os.makedirs("radar", exist_ok=True)
start, end = dt.date(2024, 8, 1), dt.date.today() - dt.timedelta(days=2)
random.seed(1)
days = random.sample([start + dt.timedelta(d) for d in range((end - start).days)],
                     int(sys.argv[1]))
for day in days:
    out = f"radar/{day:%Y%m%d}"
    if os.path.exists(out + ".npy"):
        continue                      # restartable
    xs, ts = [], []
    for k in keys(day):
        try:
            rain, t = frame(requests.get(f"{BUCKET}/{k}", timeout=120).content)
        except Exception as e:
            print("skip", k, e)
            continue
        if np.isfinite(rain).mean() < 0.9:
            continue                  # a radar outage over the square
        x = np.log1p(np.clip(np.nan_to_num(rain), 0, None)) / LOG
        xs.append(x.astype(np.float16)); ts.append(t)
    if xs:
        np.save(out + "_t.npy", np.array(ts))
        np.save(out + ".npy", np.stack(xs))
    print(day, len(xs), "frames")
```

If most frames are skipped for coverage, part of your square is sea beyond
radar range or outside OPERA. Move the centre inland, or lower 0.9 to 0.7 at the
very least.

### Step 2: train, then check

**Decide first what counts as a pass**, before you see any score, and write it
down. The Belgian model had to beat two baselines on the held-out days, at
every lead time:

- **persistence:** the last frame, held still;
- **zeros:** always dry. In a dry climate this is the harder one to beat.

If it fails, it fails. Retuning until the same held-out days say yes only finds
luck on those days. Download more days instead, or keep the bundled model or
the ensemble.

About one day in five is held out for checking. A hash of the date picks them,
so a day never moves between the two sets. Never split by window: neighbouring
windows share 23 of their 24 frames, so the held-out score would leak.

```python
# train.py: python train.py
import glob, hashlib, os
import numpy as np, torch, torch.nn as nn, torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

LOG = np.log1p(100.0)
CROP = 256                     # centre crop for training; a multiple of 16

def held_out(path):
    day = os.path.basename(path)[:8]
    return int(hashlib.md5(day.encode()).hexdigest(), 16) % 5 == 0

class Windows(Dataset):
    """24 consecutive frames, 5 minutes apart: 12 in, 12 to predict."""
    def __init__(self, files):
        self.x = [np.load(f, mmap_mode="r") for f in files]
        ts = [np.load(f.replace(".npy", "_t.npy")) for f in files]
        self.idx = [(d, s) for d, t in enumerate(ts) for s in range(len(t) - 23)
                    if np.all(np.diff(t[s:s + 24]) == 300)]
    def __len__(self):
        return len(self.idx)
    def __getitem__(self, i):
        d, s = self.idx[i]
        o = (self.x[d].shape[1] - CROP) // 2
        w = torch.from_numpy(np.asarray(
            self.x[d][s:s + 24, o:o + CROP, o:o + CROP], dtype=np.float32))
        return w[:12], w[12:]

def block(i, o):
    return nn.Sequential(
        nn.Conv2d(i, o, 3, padding=1), nn.BatchNorm2d(o), nn.ReLU(inplace=True),
        nn.Conv2d(o, o, 3, padding=1), nn.BatchNorm2d(o), nn.ReLU(inplace=True))

class UNet(nn.Module):
    def __init__(self, c=(32, 64, 128, 256, 512)):
        super().__init__()
        self.down = nn.ModuleList([block(12, c[0])] +
                                  [block(c[i], c[i + 1]) for i in range(4)])
        self.up = nn.ModuleList([nn.ConvTranspose2d(c[i + 1], c[i], 2, stride=2)
                                 for i in reversed(range(4))])
        self.dec = nn.ModuleList([block(2 * c[i], c[i]) for i in reversed(range(4))])
        self.out = nn.Conv2d(c[0], 12, 1)
    def forward(self, x):
        skips = []
        for i, d in enumerate(self.down):
            x = d(x if i == 0 else F.max_pool2d(x, 2))
            skips.append(x)
        x = skips.pop()
        for up, dec in zip(self.up, self.dec):
            x = dec(torch.cat([up(x), skips.pop()], 1))
        return self.out(x)

if __name__ == "__main__":
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    files = sorted(glob.glob("radar/????????.npy"))
    train = Windows([f for f in files if not held_out(f)])
    val = Windows([f for f in files if held_out(f)])
    print(len(train), "training windows,", len(val), "held out")
    assert len(train) >= 10000, "too few windows: download more days"

    net = UNet().to(dev)
    opt = torch.optim.AdamW(net.parameters(), lr=1e-3)
    sched = torch.optim.lr_scheduler.ReduceLROnPlateau(opt, patience=4)
    best = float("inf")
    for epoch in range(50):
        net.train()
        for x, y in DataLoader(train, 16, shuffle=True, num_workers=4):
            # MAE, not MSE: MSE learns to blur showers away
            loss = F.l1_loss(net(x.to(dev)), y.to(dev))
            opt.zero_grad(); loss.backward(); opt.step()
        net.eval(); tot = n = 0
        with torch.no_grad():
            for x, y in DataLoader(val, 16, num_workers=2):
                tot += F.l1_loss(net(x.to(dev)), y.to(dev), reduction="sum").item()
                n += y.numel()
        sched.step(tot / n)
        print(epoch, tot / n)
        if tot / n < best:
            best = tot / n
            torch.save(net.state_dict(), "best.pt")

    # the check, in mm/h, per lead: 5, 10, ... 60 minutes
    mm = lambda v: np.expm1(np.clip(v, 0, 1) * LOG)
    net.load_state_dict(torch.load("best.pt", weights_only=True)); net.eval()
    err = {k: np.zeros(12) for k in ("zeros", "persistence", "model")}; n = 0
    with torch.no_grad():
        for x, y in DataLoader(val, 16):
            p = net(x.to(dev)).cpu().numpy()
            x, y = x.numpy(), mm(y.numpy())
            err["model"] += np.abs(mm(p) - y).mean((2, 3)).sum(0)
            err["persistence"] += np.abs(mm(x[:, -1:]) - y).mean((2, 3)).sum(0)
            err["zeros"] += y.mean((2, 3)).sum(0)
            n += len(y)
    for k, e in err.items():
        print(f"{k:12s}", " ".join(f"{v:.3f}" for v in e / n))
```

You need at least 10 000 training windows. A complete day gives 265, so that
is about 50 days once a fifth are held out. The bundled model used 150 days. For comparison, its mean absolute error in mm/h on
held-out Belgian days (a different network, but scored the same way):

```
lead (min)      5     10     15     20     30     45     60
zeros       0.388  0.388  0.387  0.388  0.387  0.386  0.385
persistence 0.234  0.307  0.349  0.377  0.413  0.449  0.472
SmaAt-UNet  0.157  0.184  0.204  0.221  0.248  0.273  0.291
```

The held-out days also chose the best epoch, so their score flatters the model
a little. For an honest number, keep a few more days aside and score on them
once, at the end.

### Step 3: export for the app

```python
# export.py: python export.py
import numpy as np, onnxruntime as ort, torch
from train import UNet

net = UNet(); net.load_state_dict(torch.load("best.pt", map_location="cpu", weights_only=True))
net.eval()
torch.onnx.export(net, torch.zeros(1, 12, 256, 256), "my-region.onnx",
                  input_names=["x"], output_names=["y"], opset_version=17,
                  dynamic_axes={"x": {0: "b", 2: "h", 3: "w"},
                                "y": {0: "b", 2: "h", 3: "w"}})
# same answers as PyTorch, at a size it was not exported at
x = torch.rand(1, 12, 336, 400) * 0.5
a = net(x).detach().numpy()
b = ort.InferenceSession("my-region.onnx").run(None, {"x": x.numpy()})[0]
print("largest difference:", float(np.abs(a - b).max()))   # want < 1e-4
```

### Step 4: load it into the app

Copy `my-region.onnx` to the phone however you like: USB, a cloud drive, or
email it to yourself. Then go to **Settings → Regions and models**, open your
region, tap **Upload your own .onnx** and pick the file. The app test-runs it
before keeping it. Choose it under **Model** and save, or pick it later from
the chip button on the map.

Uploaded models stay on the phone when you delete or reset regions.

## What the app cannot do on its own

- **The 48-hour outlook model is built into the app.** Outside the area it was
  trained on (around Belgium), the app shows a weather-model ensemble for that
  part instead, with a note. A new outlook model needs a new build of the app.
- **No radar outside Europe.** The app reads OPERA only. Use Open-Meteo there.
- **One radar product.** The model sees 1 km, 5-minute frames. A model trained
  on anything else (other grids, 10- or 15-minute radar, satellite rain) will
  load, but its forecasts will be wrong.

## When something goes wrong

| symptom | what it means |
|---|---|
| Radar key needed | no MeteoGate key yet, or it was removed: Settings → MeteoGate ORD key |
| Box is outside OPERA coverage | the box must lie within 30–75° N and 30° W–45° E |
| Home is outside (region) | you picked a region in Settings that does not contain home: pick Automatic, or a box around home |
| The bundled model was trained on Belgium only | expected outside Belgium: compare it with Ensemble only on the map |
| Model rejected | wrong input name or shape, a fixed size, or output that is not finite. Re-export with export.py |
| ML model failed | the model crashed on this region's size: check that export.py's own test (at 336 × 400, not the export size) passes |
| every day downloads but almost every frame is skipped | the square is mostly sea or outside the radar: move the centre inland |
| too few windows | under 10 000: download more days. Gaps in a day break windows too |

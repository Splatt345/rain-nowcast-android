# 12 archive frames ending at index k -> demo_frames.bin for the app's debug demo mode (big-endian):
# int n, h, w; double x1, y2, px, py; UTF projdef; n * h * w float32 mm/h (NaN off-radar)
import sys, struct, numpy as np, h5py
sample, npz, k, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
r0, c0 = 2394, 1368                                       # the archive's 384 km crop around 50.5 N, 4.5 E
with h5py.File(sample) as f:
    w = f["where"].attrs; pd = w["projdef"]; pd = pd.decode() if isinstance(pd, bytes) else str(pd)
    import pyproj; p = pyproj.Proj(pd)
    xs, ys = float(w["xscale"]), float(w["yscale"])
    x1 = p(float(w["LL_lon"]), float(w["LL_lat"]))[0]; y2 = p(float(w["UR_lon"]), float(w["UR_lat"]))[1]
z = np.load(npz); a = z["rain"][k - 11:k + 1].astype(">f4"); n, h, ww = a.shape
print("frames", z["times"][k - 11], "->", z["times"][k], a.shape, "max %.1f mm/h" % np.nanmax(a))
with open(out, "wb") as o:
    o.write(struct.pack(">iii", n, h, ww)); o.write(struct.pack(">dddd", x1 + c0 * xs, y2 - r0 * ys, xs, ys))
    b = pd.encode(); o.write(struct.pack(">H", len(b)) + b); o.write(a.tobytes())

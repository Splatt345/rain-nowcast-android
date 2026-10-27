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

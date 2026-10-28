package nowcast.engine

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Splits [0, n) into [ways] contiguous chunks run at once, one on the caller and the rest on
 * [pool]. Only for loops whose iterations are independent, so results never depend on [ways].
 */
class Par(val pool: java.util.concurrent.ExecutorService?, val ways: Int) {
    fun run(n: Int, body: (Int, Int) -> Unit) {
        if (pool == null || ways <= 1 || n < ways) { body(0, n); return }
        val step = (n + ways - 1) / ways
        val rest = (1 until ways).map { k -> pool.submit { body(minOf(n, k * step), minOf(n, (k + 1) * step)) } }
        body(0, step)
        rest.forEach { it.get() }
    }

    companion object { val SERIAL = Par(null, 1) }
}

/** Row-major 2-D field. */
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
    var out = 0.0
    clampedCell(f.h, f.w, r, c) { i00, i01, i10, i11, fr, fc -> out = bilinear0(f.v, i00, i01, i10, i11, fr, fc) }
    return out
}

/** The cell and weights sampleClamped uses at (r, c); [use] gets the four corner indices and
 *  the fractions. Coordinates are clamped to >= 0 first, so toInt is floor (NaN gives 0, as
 *  floor(NaN).toInt() does). */
internal inline fun clampedCell(h: Int, w: Int, r: Double, c: Double,
                                use: (Int, Int, Int, Int, Double, Double) -> Unit) {
    val rr = r.coerceIn(0.0, (h - 1).toDouble()); val cc = c.coerceIn(0.0, (w - 1).toDouble())
    val r0 = rr.toInt(); val c0 = cc.toInt()
    val r1 = minOf(r0 + 1, h - 1); val c1 = minOf(c0 + 1, w - 1)
    use(r0 * w + c0, r0 * w + c1, r1 * w + c0, r1 * w + c1, rr - r0, cc - c0)
}

/** sampleClamped's interpolation of v at a clampedCell, NaN read as 0. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun bilinear0(v: DoubleArray, i00: Int, i01: Int, i10: Int, i11: Int, fr: Double, fc: Double): Double {
    val a = v[i00].let { if (it.isNaN()) 0.0 else it }; val b = v[i01].let { if (it.isNaN()) 0.0 else it }
    val c = v[i10].let { if (it.isNaN()) 0.0 else it }; val d = v[i11].let { if (it.isNaN()) 0.0 else it }
    return (1 - fr) * ((1 - fc) * a + fc * b) + fr * ((1 - fc) * c + fc * d)
}

/** Bilinear; NaN off-grid or next to a NaN. */
fun sampleOrNaN(f: Grid, r: Double, c: Double): Double {
    if (r < 0 || r > f.h - 1 || c < 0 || c > f.w - 1 || r.isNaN() || c.isNaN()) return Double.NaN
    val r0 = r.toInt(); val c0 = c.toInt()      // r, c >= 0: floor
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

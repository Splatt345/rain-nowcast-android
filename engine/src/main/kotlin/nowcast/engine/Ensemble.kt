package nowcast.engine

import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * pysteps STEPS, ported. Twin of local_weather_model/nowcast/stepsport.py - see its
 * docstring for the configuration and the (parity-only) differences from pysteps.
 * Every function below names the stepsport function it mirrors.
 */
class EnsembleResult(val members: Array<Array<FloatArray>>, val motion: Motion, val h: Int, val w: Int)

internal const val N_LEVELS = 6
private const val AR_ORDER = 2
internal const val THR_DB = -10.0
private const val GAUSS_SCALE = 0.5
private const val TUKEY_ALPHA = 0.2
private const val MASK_F = 1.0
private const val MASK_RIM = 10
private val P_PAR = doubleArrayOf(10.88, 0.23, -7.68)
private val P_PERP = doubleArrayOf(5.76, 0.31, -2.72)
private const val VEL_STREAM = 999

private fun mean(a: DoubleArray) = a.sum() / a.size
private fun std(a: DoubleArray, m: Double = mean(a)): Double {
    var s = 0.0; for (x in a) s += (x - m) * (x - m); return sqrt(s / a.size)
}

/** tukey */
internal fun tukey(h: Int, w: Int): DoubleArray = DoubleArray(h * w) { i ->
    val x = (i % w).toDouble() / w - 0.5; val y = (i / w).toDouble() / h - 0.5
    val r = sqrt(x * x + y * y)
    when {
        r >= 0.5 -> 0.0
        r > 0.5 * (1.0 - TUKEY_ALPHA) -> 0.5 * (1.0 + cos(PI * (r / (TUKEY_ALPHA * 0.5) - 1.0 / TUKEY_ALPHA + 1.0)))
        else -> 1.0
    }
}

/** has_rain */
internal fun hasRain(db: DoubleArray, taper: DoubleArray): Boolean {
    val mn = db.min()
    return db.indices.any { (if (taper[it] == 0.0) mn else db[it]) > THR_DB }
}

private fun freq(i: Int, n: Int) = (if (i < (n + 1) / 2) i else i - n).toDouble()

/** bandpass, but on the full complex spectrum (h*w per level): the weight is radial, so the
 *  mirrored half is the half-spectrum's own value. */
internal fun bandpass(h: Int, w: Int): Array<DoubleArray> {
    val q = (0.5 * maxOf(h, w)).pow(1.0 / N_LEVELS)
    fun lq(x: Double) = if (x > 0) ln(x) / ln(q) else 0.0
    val centres = DoubleArray(N_LEVELS) { lq(0.5 * (q.pow(it) + q.pow(it + 1))) }
    val out = Array(N_LEVELS) { DoubleArray(h * w) }
    for (r in 0 until h) for (c in 0 until w) {
        val ky = freq(r, h); val kx = abs(freq(c, w))
        val l = lq(sqrt(kx * kx + ky * ky)); val i = r * w + c
        var s = 0.0
        for (k in 0 until N_LEVELS) { val d = l - centres[k]; out[k][i] = exp(-(d * d) / (2.0 * GAUSS_SCALE * GAUSS_SCALE)); s += out[k][i] }
        for (k in 0 until N_LEVELS) out[k][i] /= s
    }
    for (k in 0 until N_LEVELS) out[k][0] = if (k == 0) 1.0 else 0.0
    return out
}

internal class Cascade(val levels: Array<DoubleArray>, val mu: DoubleArray, val sd: DoubleArray)

/** decompose. Two levels share one inverse FFT (both spectra are Hermitian, so the real part is
 *  one level and the imaginary part the other). */
internal fun decompose(field: DoubleArray, wts: Array<DoubleArray>, fft: Fft2): Cascade {
    val n = field.size
    val sr = field.copyOf(); val si = DoubleArray(n)
    fft.forward(sr, si)
    val levels = Array(N_LEVELS) { DoubleArray(n) }
    var k = 0
    while (k < N_LEVELS) {
        val a = wts[k]; val b = if (k + 1 < N_LEVELS) wts[k + 1] else null
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until n) {
            re[i] = sr[i] * a[i]; im[i] = si[i] * a[i]
            if (b != null) { re[i] -= si[i] * b[i]; im[i] += sr[i] * b[i] }
        }
        fft.inverse(re, im)
        levels[k] = re; if (b != null) levels[k + 1] = im
        k += 2
    }
    val mu = DoubleArray(N_LEVELS); val sd = DoubleArray(N_LEVELS)
    for (j in 0 until N_LEVELS) {
        mu[j] = mean(levels[j]); sd[j] = std(levels[j], mu[j])
        val l = levels[j]; for (i in 0 until n) l[i] = (l[i] - mu[j]) / sd[j]
    }
    return Cascade(levels, mu, sd)
}

/** corrcoef */
internal fun corrcoef(a: DoubleArray, b: DoubleArray): Double {
    val ma = mean(a); val mb = mean(b)
    var ab = 0.0; var aa = 0.0; var bb = 0.0
    for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; ab += x * y; aa += x * x; bb += y * y }
    return ab / sqrt(aa * bb)
}

/** ar_params: returns (phi_1..phi_p, phi_pert) and the adjusted gammas. */
internal fun arParams(gamma: DoubleArray): Pair<DoubleArray, DoubleArray> {
    val g = gamma.copyOf()
    val phi: DoubleArray
    val maxRoot: Double
    if (g.size == 2) {
        val g1 = g[0]
        val g2 = maxOf(g[1], 2 * g1 * g[1] - 1)
        g[1] = maxOf(g2, (3 * g1 * g1 - 2 + 2 * (1 - g1 * g1).pow(1.5)) / (g1 * g1))
        val d = 1.0 - g[0] * g[0]
        phi = doubleArrayOf((g[0] - g[0] * g[1]) / d, (g[1] - g[0] * g[0]) / d)
        val disc = phi[0] * phi[0] + 4 * phi[1]          // roots of z^2 - phi1 z - phi2
        maxRoot = if (disc >= 0) maxOf(abs((phi[0] + sqrt(disc)) / 2), abs((phi[0] - sqrt(disc)) / 2))
                  else sqrt(-phi[1])
    } else {
        phi = doubleArrayOf(g[0]); maxRoot = abs(g[0])
    }
    if (maxRoot >= 1) throw IllegalStateException("nonstationary AR(p) process")
    var c = 1.0
    for (i in g.indices) c -= g[i] * phi[i]
    return (phi + (if (c >= 0) sqrt(c) else 0.0)) to g
}

/** noise_filter: |mean of the frames' FFTs| on the full spectrum. */
internal fun noiseFilter(frames: List<DoubleArray>, h: Int, w: Int, fft: Fft2): DoubleArray {
    val f = frames.map { it.copyOf() }
    val mn = f.minOf { it.min() }
    var minAbove = Double.POSITIVE_INFINITY
    for (a in f) for (x in a) if (x > mn && x < minAbove) minAbove = x
    for (a in f) for (i in a.indices) if (a[i] > mn) a[i] -= minAbove - mn
    val t = tukey(h, w)
    val ar = DoubleArray(h * w); val ai = DoubleArray(h * w)
    for (a in f) {
        val m = a.min()
        val re = DoubleArray(h * w) { (a[it] - m) * t[it] }; val im = DoubleArray(h * w)
        fft.forward(re, im)
        for (i in ar.indices) { ar[i] += re[i]; ai[i] += im[i] }
    }
    return DoubleArray(h * w) { val r = ar[it] / f.size; val i = ai[it] / f.size; sqrt(r * r + i * i) }
}

/** noise */
internal fun noiseField(filt: DoubleArray, white: DoubleArray, fft: Fft2): DoubleArray {
    val re = white.copyOf(); val im = DoubleArray(white.size)
    fft.forward(re, im)
    for (i in re.indices) { re[i] *= filt[i]; im[i] *= filt[i] }
    fft.inverse(re, im)
    val m = mean(re); val s = std(re, m)
    for (i in re.indices) re[i] = (re[i] - m) / s
    return re
}

/** laplace: numpy's legacy RandomState.laplace(scale = 1/sqrt 2) on a given uniform. */
internal fun laplace(u: Double): Double {
    val b = 1.0 / sqrt(2.0)
    return if (u >= 0.5) -b * ln(2.0 - u - u) else b * ln(u + u)
}

/** sl_step: one semi-Lagrangian step (n_iter 1), displacement updated in place. */
private fun slStep(dx: DoubleArray, dy: DoubleArray, vx: Grid, vy: Grid) {
    val w = vx.w
    for (i in dx.indices) {
        val r = (i / w) + dy[i]; val c = (i % w) + dx[i]
        val ix = sampleClamped(vx, r, c); val iy = sampleClamped(vy, r, c)
        dx[i] -= sampleClamped(vx, r - iy / 2.0, c - ix / 2.0)
        dy[i] -= sampleClamped(vy, r - iy / 2.0, c - ix / 2.0)
    }
}

/** warp */
private fun warp(field: Grid, dx: DoubleArray, dy: DoubleArray, outval: Double = Double.NaN) =
    DoubleArray(dx.size) { i ->
        val v = sampleOrNaN(field, (i / field.w) + dy[i], (i % field.w) + dx[i])
        if (v.isNaN()) outval else v
    }

/** advect: extrapolate(field, V, steps, outval='min')[-1] */
internal fun advect(field: DoubleArray, mo: Motion, steps: Int): DoubleArray {
    val dx = DoubleArray(field.size); val dy = DoubleArray(field.size)
    repeat(steps) { slStep(dx, dy, mo.vx, mo.vy) }
    return warp(Grid(mo.vx.h, mo.vx.w, field), dx, dy, field.min())
}

/** cross: binary dilation by the 3x3 cross, zero border. */
private fun cross(b: BooleanArray, h: Int, w: Int) = BooleanArray(b.size) { i ->
    val r = i / w; val c = i % w
    b[i] || (r > 0 && b[i - w]) || (r < h - 1 && b[i + w]) || (c > 0 && b[i - 1]) || (c < w - 1 && b[i + 1])
}

internal fun structRadius(kmPerPixel: Double, timestep: Double) =
    maxOf(1, ((MASK_F * timestep / kmPerPixel - 1) / 2.0).toInt())

/** dilated_mask */
internal fun dilatedMask(b: BooleanArray, h: Int, w: Int, radius: Int, rim: Int = MASK_RIM): DoubleArray {
    var d = b
    repeat(radius) { d = cross(d, h, w) }
    val m = DoubleArray(b.size) { if (d[it]) 1.0 else 0.0 }
    repeat(rim) { d = cross(d, h, w); for (i in m.indices) if (d[i]) m[i] += 1.0 }
    val mx = m.max()
    if (mx > 0) for (i in m.indices) m[i] /= mx
    return m
}

/** percentile on an already sorted array: np.percentile 'linear', with numpy's two-sided lerp. */
internal fun percentileSorted(s: DoubleArray, q: Double): Double {
    val v = (s.size - 1) * (q / 100.0)
    val lo = floor(v).toInt(); val hi = minOf(lo + 1, s.size - 1)
    val g = v - lo; val d = s[hi] - s[lo]
    return if (g >= 0.5) s[hi] - d * (1 - g) else s[lo] + d * g
}

/** Stable argsort (bottom-up merge sort on indices). */
internal fun argsortStable(v: DoubleArray): IntArray {
    var a = IntArray(v.size) { it }; var b = IntArray(v.size)
    var width = 1
    while (width < v.size) {
        var lo = 0
        while (lo < v.size) {
            val mid = minOf(lo + width, v.size); val hi = minOf(lo + 2 * width, v.size)
            var i = lo; var j = mid; var k = lo
            while (i < mid && j < hi) b[k++] = if (v[a[j]] < v[a[i]]) a[j++] else a[i++]
            while (i < mid) b[k++] = a[i++]
            while (j < hi) b[k++] = a[j++]
            lo = hi
        }
        val t = a; a = b; b = t; width *= 2
    }
    return a
}

/** probmatch, with the target pre-sorted (its zeroing below the percentile keeps it sorted,
 *  since the zero value is its minimum). */
internal fun probmatch(initial: DoubleArray, targetSorted: DoubleArray): DoubleArray {
    val z = initial.min(); val zt = targetSorted[0]
    var nWet = 0; for (x in initial) if (x > z) nWet++
    var nWetT = 0; for (x in targetSorted) if (x > zt) nWetT++
    val p = if (nWetT > nWet) percentileSorted(targetSorted, 100 * (1 - nWet.toDouble() / initial.size))
            else Double.NEGATIVE_INFINITY
    val order = argsortStable(initial)
    val out = DoubleArray(initial.size)
    for (rank in order.indices) {
        val i = order[rank]
        val t = targetSorted[rank]
        out[i] = if (initial[i] == z) zt else if (t < p) zt else t
    }
    return out
}

/** from_db: pysteps' inverse dB transform with threshold -10 dB. */
internal fun fromDbSteps(db: Double): Float {
    if (db.isNaN()) return Float.NaN
    val r = 10.0.pow(db / 10.0)
    return if (r < 0.1) 0f else r.toFloat()
}

internal class StepsSetup(
    val p: Int, val wts: Array<DoubleArray>, val filt: DoubleArray, val cascades: List<Cascade>,
    val gammaRaw: Array<DoubleArray>, val gamma: Array<DoubleArray>, val phi: Array<DoubleArray>,
    val last: DoubleArray, val lastSorted: DoubleArray, val fft: Fft2,
)

/** setup; null when pysteps would return a zero forecast. */
internal fun setup(db3: List<DoubleArray>, mo: Motion): StepsSetup? {
    val h = mo.vx.h; val w = mo.vx.w
    val taper = tukey(h, w)
    val rain = db3.map { hasRain(it, taper) }
    if (!rain[2] || !rain[1]) return null
    val p = if (rain[0]) AR_ORDER else 1
    val frames = db3.takeLast(p + 1).mapIndexed { i, f -> if (i < p) advect(f, mo, p - i) else f.copyOf() }
    val fft = Fft2(h, w)
    val wts = bandpass(h, w)
    val filt = noiseFilter(frames, h, w, fft)
    val dec = frames.map { decompose(it, wts, fft) }
    val gammaRaw = Array(N_LEVELS) { i -> DoubleArray(p) { l -> corrcoef(dec.last().levels[i], dec[dec.size - l - 2].levels[i]) } }
    val fits = gammaRaw.map { arParams(it) }
    return StepsSetup(p, wts, filt, dec, gammaRaw, fits.map { it.second }.toTypedArray(),
        fits.map { it.first }.toTypedArray(), frames.last(), frames.last().sorted().toDoubleArray(), fft)
}

/** member: n_lead fields in dB, NaN outside the domain. */
internal fun member(s: StepsSetup, mo: Motion, m: Int, nLead: Int, seed: Long, kmPerPixel: Double,
                    timestep: Double, onFirst: ((DoubleArray, DoubleArray) -> Unit)? = null): Array<DoubleArray> {
    val h = mo.vx.h; val w = mo.vx.w; val n = h * w; val p = s.p
    // casc[i][0] oldest .. casc[i][p-1] newest
    val casc = Array(N_LEVELS) { i -> Array(p) { j -> s.cascades[s.cascades.size - p + j].levels[i].copyOf() } }
    val mu = s.cascades.last().mu; val sd = s.cascades.last().sd
    val radius = structRadius(kmPerPixel, timestep)
    var mask = dilatedMask(BooleanArray(n) { s.last[it] >= THR_DB }, h, w, radius)
    val u = Rng.uniforms(Rng.stream(seed, m, VEL_STREAM), 2)
    val epsPar = laplace(u[0]); val epsPerp = laplace(u[1])
    // BPS unit vectors, fixed per run
    val vsf = 60.0 / (timestep * (1.0 / kmPerPixel))
    val parX = DoubleArray(n); val parY = DoubleArray(n)
    for (i in 0 until n) {
        val a = mo.vx.v[i]; val b = mo.vy.v[i]; val nn = sqrt(a * a + b * b)
        if (nn > 1e-12) { parX[i] = a / nn; parY[i] = b / nn }
    }
    val dx = DoubleArray(n); val dy = DoubleArray(n)
    return Array(nLead) { k ->
        val eps = noiseField(s.filt, Rng.normals(Rng.stream(seed, m, k), n), s.fft)
        val epsLv = decompose(eps, s.wts, s.fft).levels
        val f = DoubleArray(n)
        for (i in 0 until N_LEVELS) {
            val phi = s.phi[i]; val c = casc[i]
            val new = DoubleArray(n) { j ->
                var x = 0.0
                for (l in 0 until p) x += phi[l] * c[p - 1 - l][j]
                x + phi[p] * epsLv[i][j]
            }
            for (j in 0 until p - 1) c[j] = c[j + 1]
            c[p - 1] = new
            for (j in 0 until n) f[j] += new[j] * sd[i] + mu[i]
        }
        // stepsport.recompose sums level by level in this order too
        val fmin = f.min()
        for (j in 0 until n) { f[j] = fmin + (f[j] - fmin) * mask[j]; if (!(f[j] > fmin)) f[j] = fmin }
        val pm = probmatch(f, s.lastSorted)
        mask = dilatedMask(BooleanArray(n) { pm[it] >= THR_DB }, h, w, radius)
        val t = (k + 1) * timestep
        val gPar = P_PAR[0] * t.pow(P_PAR[1]) + P_PAR[2]
        val gPerp = P_PERP[0] * t.pow(P_PERP[1]) + P_PERP[2]
        val vx = Grid(h, w, DoubleArray(n) { mo.vx.v[it] + (gPar * epsPar * parX[it] + gPerp * epsPerp * -parY[it]) / vsf })
        val vy = Grid(h, w, DoubleArray(n) { mo.vy.v[it] + (gPar * epsPar * parY[it] + gPerp * epsPerp * parX[it]) / vsf })
        slStep(dx, dy, vx, vy)
        if (k == 0) onFirst?.invoke(eps, pm)
        warp(Grid(h, w, pm), dx, dy)
    }
}

/**
 * The ensemble: members[m][lead][r*w+c] in mm/h, NaN off-domain. Members run in parallel;
 * each draws only from its own random streams, so the result does not depend on scheduling.
 */
fun ensemble(train: List<Grid>, nLead: Int = 12, nMembers: Int = 10, seed: Long = 42,
             kmPerPixel: Double = 1.0, timestep: Double = 5.0, threads: Int = Runtime.getRuntime().availableProcessors()): EnsembleResult {
    val db = train.takeLast(3).map { g -> g.map(::toDb) }
    val mo = motion(db)
    val h = db.last().h; val w = db.last().w
    val s = setup(db.map { it.v }, mo)
        ?: return EnsembleResult(Array(nMembers) { Array(nLead) { FloatArray(h * w) } }, mo, h, w)
    val pool = Executors.newFixedThreadPool(maxOf(1, minOf(threads, nMembers)))
    try {
        val jobs = (0 until nMembers).map { m ->
            pool.submit<Array<FloatArray>> {
                member(s, mo, m, nLead, seed, kmPerPixel, timestep).map { f -> FloatArray(f.size) { fromDbSteps(f[it]) } }.toTypedArray()
            }
        }
        return EnsembleResult(jobs.map { it.get() }.toTypedArray(), mo, h, w)
    } finally {
        pool.shutdown()
    }
}

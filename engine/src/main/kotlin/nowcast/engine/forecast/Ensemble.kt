package nowcast.engine.forecast

import nowcast.engine.math.*
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
 * pysteps' STEPS ensemble nowcast, ported. Functions are named after the pysteps steps they port.
 */
class EnsembleResult(val members: Members, val motion: Motion, val h: Int, val w: Int,
                     val timings: Map<String, Double> = emptyMap())

/** Seconds since [t0] (System.nanoTime). */
internal fun since(t0: Long) = (System.nanoTime() - t0) / 1e9

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

internal fun tukey(h: Int, w: Int): DoubleArray = DoubleArray(h * w) { i ->
    val x = (i % w).toDouble() / w - 0.5; val y = (i / w).toDouble() / h - 0.5
    val r = sqrt(x * x + y * y)
    when {
        r >= 0.5 -> 0.0
        r > 0.5 * (1.0 - TUKEY_ALPHA) -> 0.5 * (1.0 + cos(PI * (r / (TUKEY_ALPHA * 0.5) - 1.0 / TUKEY_ALPHA + 1.0)))
        else -> 1.0
    }
}

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
private fun slStep(dx: DoubleArray, dy: DoubleArray, vx: Grid, vy: Grid, par: Par = Par.SERIAL) {
    val w = vx.w
    par.run(dx.size) { from, until ->
        for (i in from until until) {
            val r = (i / w) + dy[i]; val c = (i % w) + dx[i]
            // sampleClamped of vx and vy at the same point, sharing the cell and weights
            var ix = 0.0; var iy = 0.0
            clampedCell(vx.h, w, r, c) { a, b, e, d, fr, fc -> ix = bilinear0(vx.v, a, b, e, d, fr, fc); iy = bilinear0(vy.v, a, b, e, d, fr, fc) }
            clampedCell(vx.h, w, r - iy / 2.0, c - ix / 2.0) { a, b, e, d, fr, fc ->
                dx[i] -= bilinear0(vx.v, a, b, e, d, fr, fc); dy[i] -= bilinear0(vy.v, a, b, e, d, fr, fc) }
        }
    }
}

private fun warp(field: Grid, dx: DoubleArray, dy: DoubleArray, outval: Double = Double.NaN,
                 out: DoubleArray = DoubleArray(dx.size), par: Par = Par.SERIAL): DoubleArray {
    par.run(dx.size) { from, until ->
        for (i in from until until) {
            val v = sampleOrNaN(field, (i / field.w) + dy[i], (i % field.w) + dx[i])
            out[i] = if (v.isNaN()) outval else v
        }
    }
    return out
}

/** advect: extrapolate(field, V, steps, outval='min')[-1] */
internal fun advect(field: DoubleArray, mo: Motion, steps: Int, par: Par = Par.SERIAL): DoubleArray {
    val dx = DoubleArray(field.size); val dy = DoubleArray(field.size)
    repeat(steps) { slStep(dx, dy, mo.vx, mo.vy, par) }
    return warp(Grid(mo.vx.h, mo.vx.w, field), dx, dy, field.min(), par = par)
}

/** L1 distance to the nearest true pixel, inside the grid (two-pass chamfer; exact for the
 *  4-neighbour metric), in place: [d] comes in as 0 at true pixels and [FAR] elsewhere. */
private const val FAR = Int.MAX_VALUE / 2
private fun l1Distance(d: IntArray, h: Int, w: Int) {
    for (r in 0 until h) for (c in 0 until w) {
        val i = r * w + c
        if (r > 0 && d[i - w] + 1 < d[i]) d[i] = d[i - w] + 1
        if (c > 0 && d[i - 1] + 1 < d[i]) d[i] = d[i - 1] + 1
    }
    for (r in h - 1 downTo 0) for (c in w - 1 downTo 0) {
        val i = r * w + c
        if (r < h - 1 && d[i + w] + 1 < d[i]) d[i] = d[i + w] + 1
        if (c < w - 1 && d[i + 1] + 1 < d[i]) d[i] = d[i + 1] + 1
    }
}

internal fun structRadius(kmPerPixel: Double, timestep: Double) =
    maxOf(1, ((MASK_F * timestep / kmPerPixel - 1) / 2.0).toInt())

/** dilated_mask. Dilating k times by the 3x3 cross (zero border) reaches exactly the pixels
 *  within L1 distance k, so the mask is a clamp of that distance: 1 + rim inside the radius,
 *  falling by one per pixel across the rim. */
internal fun dilatedMask(b: BooleanArray, h: Int, w: Int, radius: Int, rim: Int = MASK_RIM): DoubleArray =
    maskFromDistance(IntArray(b.size) { if (b[it]) 0 else FAR }, h, w, radius, rim, DoubleArray(b.size))

/** dilatedMask of the pixels where [d] is 0 ([d] is overwritten), into [m]. */
private fun maskFromDistance(d: IntArray, h: Int, w: Int, radius: Int, rim: Int, m: DoubleArray): DoubleArray {
    l1Distance(d, h, w)
    var mx = 0.0
    for (i in m.indices) {
        val x = d[i]; m[i] = if (x <= radius) 1.0 + rim else maxOf(0, radius + rim + 1 - x).toDouble()
        if (m[i] > mx) mx = m[i]
    }
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

/** Stable argsort of non-NaN values: LSD radix sort, 16 bits a pass, on the doubles' bits
 *  mapped to unsigned order (-0.0 as 0.0, the ties of `<`). Skips passes where all agree. */
internal fun argsortStable(v: DoubleArray): IntArray = argsortStable(v, v.size, SortBuf(v.size))

/** Reusable buffers for probmatch and its argsort, one set per member. */
internal class SortBuf(n: Int) {
    var key = LongArray(n); var idx = IntArray(n); var k2 = LongArray(n); var i2 = IntArray(n)
    val count = IntArray(1 shl 16); val wet = IntArray(n); val vals = DoubleArray(n)
}

/** argsortStable of v[0 until n]; the result is one of [b]'s arrays. */
internal fun argsortStable(v: DoubleArray, n: Int, b: SortBuf): IntArray {
    var key = b.key; var idx = b.idx; var k2 = b.k2; var i2 = b.i2; val count = b.count
    for (i in 0 until n) {
        val bits = java.lang.Double.doubleToRawLongBits(if (v[i] == 0.0) 0.0 else v[i])
        key[i] = if (bits < 0) bits.inv() else bits xor Long.MIN_VALUE
        idx[i] = i
    }
    for (shift in 0 until 64 step 16) {
        count.fill(0)
        for (i in 0 until n) count[((key[i] ushr shift) and 0xFFFF).toInt()]++
        if (n == 0 || count[((key[0] ushr shift) and 0xFFFF).toInt()] == n) continue
        var sum = 0
        for (d in count.indices) { val c = count[d]; count[d] = sum; sum += c }
        for (i in 0 until n) {
            val d = ((key[i] ushr shift) and 0xFFFF).toInt(); val o = count[d]++
            k2[o] = key[i]; i2[o] = idx[i]
        }
        val tk = key; key = k2; k2 = tk; val ti = idx; idx = i2; i2 = ti
    }
    b.key = key; b.idx = idx; b.k2 = k2; b.i2 = i2
    return idx
}

/** probmatch, with the target pre-sorted (its zeroing below the percentile keeps it sorted,
 *  since the zero value is its minimum). */
internal fun probmatch(initial: DoubleArray, targetSorted: DoubleArray,
                       out: DoubleArray = DoubleArray(initial.size), buf: SortBuf = SortBuf(initial.size)): DoubleArray {
    val z = initial.min(); val zt = targetSorted[0]
    var nWet = 0; for (x in initial) if (x > z) nWet++
    var nWetT = 0; for (x in targetSorted) if (x > zt) nWetT++
    val p = if (nWetT > nWet) percentileSorted(targetSorted, 100 * (1 - nWet.toDouble() / initial.size))
            else Double.NEGATIVE_INFINITY
    // A stable argsort puts every pixel at the minimum z first, in index order, and those all
    // map to zt: so sort only the others, ranked after them. Same ranks, a fraction of the work.
    out.fill(zt)
    val wet = buf.wet; val vals = buf.vals; var j = 0
    for (i in initial.indices) if (initial[i] > z) { wet[j] = i; vals[j] = initial[i]; j++ }   // the nWet predicate: NaN-safe
    val order = argsortStable(vals, nWet, buf)
    val base = initial.size - nWet
    for (r in 0 until nWet) {
        val t = targetSorted[base + r]
        out[wet[order[r]]] = if (t < p) zt else t
    }
    return out
}

/** from_db: pysteps' inverse dB transform with threshold -10 dB. */
internal fun fromDbSteps(db: Double): Float {
    if (db.isNaN()) return Float.NaN
    val r = 10.0.pow(db / 10.0)
    return if (r < 0.1) 0f else r.toFloat()
}

/** Holds only what members read: the band weights and the cascades go after init, ~27 MB
 *  for Belgium that would otherwise sit on the heap through the whole ensemble. */
internal class StepsSetup(
    val p: Int, wts: Array<DoubleArray>, val filt: DoubleArray, cascades: List<Cascade>,
    val gammaRaw: Array<DoubleArray>, val gamma: Array<DoubleArray>, val phi: Array<DoubleArray>,
    val last: DoubleArray, val lastSorted: DoubleArray, val fft: Fft2, val par: Par,
) {
    /** The last cascade's level means and stds (its levels are [last] decomposed again). */
    val mu = cascades.last().mu; val sd = cascades.last().sd
    /** Half spectra (columns 0..w/2) of the last p cascades' normalised levels, [level][lag] as
     *  re, im pairs: the AR(p) recursion is linear, so members run it in Fourier space. */
    val spec: Array<Array<Pair<DoubleArray, DoubleArray>>> = Array(N_LEVELS) { i -> Array(p) { j ->
        val re = cascades[cascades.size - p + j].levels[i].copyOf(); val im = DoubleArray(re.size)
        fft.forward(re, im)
        half(re, fft.h, fft.w) to half(im, fft.h, fft.w)
    } }
    /** filt * band per level on the half spectrum, DC zeroed (the noise levels are normalised). */
    val noiseBand: Array<DoubleArray> = Array(N_LEVELS) { i ->
        val hw = fft.w / 2 + 1
        DoubleArray(fft.h * hw) { o -> if (o == 0) 0.0 else filt[(o / hw) * fft.w + o % hw] * wts[i][(o / hw) * fft.w + o % hw] }
    }
    /** Parseval weight of each half-spectrum cell in the full one: columns 0 and w/2 are their own mirror. */
    val parseval: DoubleArray = DoubleArray(fft.h * (fft.w / 2 + 1)) { o ->
        val c = o % (fft.w / 2 + 1); if (c == 0 || 2 * c == fft.w) 1.0 else 2.0
    }
}

private fun half(full: DoubleArray, h: Int, w: Int): DoubleArray {
    val hw = w / 2 + 1
    return DoubleArray(h * hw) { full[(it / hw) * w + it % hw] }
}


/** setup; null when pysteps would return a zero forecast. */
internal fun setup(db3: List<DoubleArray>, mo: Motion, par: Par = Par.SERIAL): StepsSetup? {
    val h = mo.vx.h; val w = mo.vx.w
    val taper = tukey(h, w)
    val rain = db3.map { hasRain(it, taper) }
    if (!rain[2] || !rain[1]) return null
    val p = if (rain[0]) AR_ORDER else 1
    val frames = db3.takeLast(p + 1).mapIndexed { i, f -> if (i < p) advect(f, mo, p - i, par) else f.copyOf() }
    val fft = Fft2(h, w, par)
    val wts = bandpass(h, w)
    val filt = noiseFilter(frames, h, w, fft)
    val dec = frames.map { decompose(it, wts, fft) }
    val gammaRaw = Array(N_LEVELS) { i -> DoubleArray(p) { l -> corrcoef(dec.last().levels[i], dec[dec.size - l - 2].levels[i]) } }
    val fits = gammaRaw.map { arParams(it) }
    return StepsSetup(p, wts, filt, dec, gammaRaw, fits.map { it.second }.toTypedArray(),
        fits.map { it.first }.toTypedArray(), frames.last(), frames.last().sorted().toDoubleArray(), fft, par)
}

/** member: n_lead fields in mm/h (from_db applied), NaN outside the domain. */
internal fun member(s: StepsSetup, mo: Motion, m: Int, nLead: Int, seed: Long, kmPerPixel: Double,
                    timestep: Double, onFirst: ((DoubleArray, DoubleArray) -> Unit)? = null, par: Par = s.par): Array<FloatArray> {
    val h = mo.vx.h; val w = mo.vx.w; val n = h * w; val p = s.p
    val mu = s.mu; val sd = s.sd
    val radius = structRadius(kmPerPixel, timestep)
    val dist = IntArray(n); val sortBuf = SortBuf(n)
    val mask = DoubleArray(n)
    for (i in 0 until n) dist[i] = if (s.last[i] >= THR_DB) 0 else FAR
    maskFromDistance(dist, h, w, radius, MASK_RIM, mask)
    val u = Rng.uniforms(Rng.stream(seed, m, VEL_STREAM), 2)
    val epsPar = laplace(u[0]); val epsPerp = laplace(u[1])
    // BPS unit vectors, fixed per run
    val vsf = 60.0 / (timestep * (1.0 / kmPerPixel))
    val dx = DoubleArray(n); val dy = DoubleArray(n)
    // The cascade lives in Fourier space (half spectrum, h x hw): the noise is W * filt * band,
    // each level normalised by its Parseval std, and only the recomposed field is transformed
    // back. Same algebra as pysteps; and since the AR recursion does not see the field,
    // two leads share one forward FFT (their white noise as real + i*imag) and one inverse.
    val hw = w / 2 + 1; val nh = h * hw
    // lre[i][0] oldest .. lre[i][p-1] newest
    val lre = Array(N_LEVELS) { i -> Array(p) { j -> s.spec[i][j].first.copyOf() } }
    val lim = Array(N_LEVELS) { i -> Array(p) { j -> s.spec[i][j].second.copyOf() } }
    val zr = DoubleArray(n); val zi = DoubleArray(n)
    val wr = arrayOf(DoubleArray(nh), DoubleArray(nh)); val wi = arrayOf(DoubleArray(nh), DoubleArray(nh))
    val fr = arrayOf(DoubleArray(nh), DoubleArray(nh)); val fi = arrayOf(DoubleArray(nh), DoubleArray(nh))
    val muSum = mu.sum()

    /** One AR step of every level, driven by white-noise spectrum (nr, ni); recomposed spectrum into (outR, outI). */
    fun step(nr: DoubleArray, ni: DoubleArray, outR: DoubleArray, outI: DoubleArray) {
        outR.fill(0.0); outI.fill(0.0)
        for (i in 0 until N_LEVELS) {
            val phi = s.phi[i]; val g = s.noiseBand[i]; val pw = s.parseval; val cr = lre[i]; val ci = lim[i]
            var ss = 0.0
            for (o in 0 until nh) ss += pw[o] * g[o] * g[o] * (nr[o] * nr[o] + ni[o] * ni[o])
            val inv = phi[p] * n / sqrt(ss)       // phi_p / level std (Parseval)
            val newR = cr[0]; val newI = ci[0]   // the oldest lag, read before written at each o
            val sdi = sd[i]
            par.run(nh) { from, until -> for (o in from until until) {
                val gi = g[o] * inv
                var xr = gi * nr[o]; var xi = gi * ni[o]
                for (l in 0 until p) { xr += phi[l] * cr[p - 1 - l][o]; xi += phi[l] * ci[p - 1 - l][o] }
                newR[o] = xr; newI[o] = xi
                outR[o] += xr * sdi; outI[o] += xi * sdi
            } }
            for (j in 0 until p - 1) { cr[j] = cr[j + 1]; ci[j] = ci[j + 1] }
            cr[p - 1] = newR; ci[p - 1] = newI
        }
    }

    // per-lead buffers, reused: allocation, not arithmetic, is what a small Android heap pays for
    val fBuf = arrayOf(DoubleArray(n), DoubleArray(n)); val pm = DoubleArray(n)
    val vx = Grid(h, w); val vy = Grid(h, w)
    return Array(nLead) { k ->
        var eps: DoubleArray? = null
        val f = fBuf[k % 2]
        if (k % 2 == 0) {
            val two = k + 1 < nLead
            Rng.normalsInto(Rng.stream(seed, m, k), zr, par)
            if (k == 0 && onFirst != null) eps = noiseField(s.filt, zr.copyOf(), s.fft)
            if (two) Rng.normalsInto(Rng.stream(seed, m, k + 1), zi, par) else zi.fill(0.0)
            s.fft.forward(zr, zi)
            par.run(h) { r0, r1 -> for (r in r0 until r1) for (c in 0 until hw) {
                val a = r * w + c; val b = ((h - r) % h) * w + (w - c) % w; val o = r * hw + c
                // Z = A + iB with A, B Hermitian: A = (Z + conj Z-) / 2, B = (Z - conj Z-) / 2i
                wr[0][o] = 0.5 * (zr[a] + zr[b]); wi[0][o] = 0.5 * (zi[a] - zi[b])
                wr[1][o] = 0.5 * (zi[a] + zi[b]); wi[1][o] = -0.5 * (zr[a] - zr[b])
            } }
            step(wr[0], wi[0], fr[0], fi[0])
            if (two) step(wr[1], wi[1], fr[1], fi[1]) else { fr[1].fill(0.0); fi[1].fill(0.0) }
            // full spectrum of F0 + i F1 (each Hermitian), so the inverse is f0 + i f1
            par.run(h) { r0, r1 -> for (r in r0 until r1) for (c in 0 until w) {
                val a = r * w + c
                val o: Int; val sg: Double
                if (c < hw) { o = r * hw + c; sg = 1.0 } else { o = ((h - r) % h) * hw + (w - c); sg = -1.0 }
                zr[a] = fr[0][o] - sg * fi[1][o]; zi[a] = sg * fi[0][o] + fr[1][o]
            } }
            s.fft.inverse(zr, zi)
            for (j in 0 until n) f[j] = zr[j] + muSum
            if (two) { val g = fBuf[1]; for (j in 0 until n) g[j] = zi[j] + muSum }
        }
        val fmin = f.min()
        for (j in 0 until n) { f[j] = fmin + (f[j] - fmin) * mask[j]; if (!(f[j] > fmin)) f[j] = fmin }
        probmatch(f, s.lastSorted, pm, sortBuf)
        for (i in 0 until n) dist[i] = if (pm[i] >= THR_DB) 0 else FAR
        maskFromDistance(dist, h, w, radius, MASK_RIM, mask)
        val t = (k + 1) * timestep
        val gPar = P_PAR[0] * t.pow(P_PAR[1]) + P_PAR[2]
        val gPerp = P_PERP[0] * t.pow(P_PERP[1]) + P_PERP[2]
        par.run(n) { from, until -> for (i in from until until) {
            // the unit vectors along the motion, recomputed rather than held: two fields less per member
            val a = mo.vx.v[i]; val b = mo.vy.v[i]; val nn = sqrt(a * a + b * b)
            val parX = if (nn > 1e-12) a / nn else 0.0; val parY = if (nn > 1e-12) b / nn else 0.0
            vx.v[i] = a + (gPar * epsPar * parX + gPerp * epsPerp * -parY) / vsf
            vy.v[i] = b + (gPar * epsPar * parY + gPerp * epsPerp * parX) / vsf
        } }
        slStep(dx, dy, vx, vy, par)
        if (eps != null) onFirst?.invoke(eps, pm.copyOf())
        val pg = Grid(h, w, pm)
        // warp's NaN outval straight to mm/h, without a double field in between
        FloatArray(n).also { o -> par.run(n) { from, until -> for (i in from until until)
            o[i] = fromDbSteps(sampleOrNaN(pg, (i / w) + dy[i], (i % w) + dx[i])) } }
    }
}

/**
 * The ensemble: members[m][lead][r*w+c] in mm/h, NaN off-domain. Members run in parallel;
 * each draws only from its own random streams, so the result does not depend on scheduling.
 */
fun ensemble(train: List<Grid>, nLead: Int = 12, nMembers: Int = 10, seed: Long = 42,
             kmPerPixel: Double = 1.0, timestep: Double = 5.0, threads: Int = Runtime.getRuntime().availableProcessors(),
             dir: java.io.File? = null): EnsembleResult {
    // Flow and setup split their pixel loops threads-fold; then members run nThreads at a time
    // (capped by the heap left after setup) and each splits its loops ways-fold, so at most
    // nThreads * ways <= threads cores are busy.
    val h = train.last().h; val w = train.last().w
    val rows = if (threads > 1) Executors.newFixedThreadPool(threads - 1) else null
    var pool: java.util.concurrent.ExecutorService? = null
    try {
        val t0 = System.nanoTime()
        val db = train.takeLast(3).map { g -> g.map(::toDb) }
        val mo = motion(db, Par(rows, threads))
        val tFlow = since(t0); val t1 = System.nanoTime()
        val s = setup(db.map { it.v }, mo, Par(rows, threads))
            ?: return EnsembleResult(Members.alloc(nMembers, nLead, h * w, dir), mo, h, w, mapOf("flow" to tFlow))
        val tSetup = since(t1); val t2 = System.nanoTime()
        // ~40 float64 fields per member (cascade spectra, buffers, transients) plus its 12
        // float32 leads until they go off-heap; cap the threads so a 192 MB Android heap does not run
        // out. Measured, not derived: 40 was fine on a 192 MB emulator; a finer cap would need a per-member peak.
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val byHeap = (free / ((40L + nLead / 2) * 8 * h * w)).toInt()
        val nThreads = maxOf(1, minOf(threads, nMembers, byHeap))
        val ways = maxOf(1, threads / nThreads)
        val par = Par(rows, ways)
        pool = Executors.newFixedThreadPool(nThreads)
        val members = Members.alloc(nMembers, nLead, h * w, dir)
        val jobs = (0 until nMembers).map { m ->
            pool.submit<Unit> { members.put(m, member(s, mo, m, nLead, seed, kmPerPixel, timestep, par = par)) }
        }
        jobs.forEach { it.get() }
        return EnsembleResult(members, mo, h, w, mapOf("flow" to tFlow, "setup" to tSetup, "members" to since(t2),
            "threads" to nThreads.toDouble(), "ways" to ways.toDouble()))
    } finally {
        pool?.shutdown(); rows?.shutdown()
    }
}

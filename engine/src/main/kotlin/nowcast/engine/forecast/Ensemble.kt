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
class EnsembleResult(val members: Members, val motion: Motion, val rows: Int, val cols: Int,
                     val timings: Map<String, Double> = emptyMap())

/** Seconds since [start] (System.nanoTime). */
internal fun since(start: Long) = (System.nanoTime() - start) / 1e9

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

internal fun tukey(rows: Int, cols: Int): DoubleArray = DoubleArray(rows * cols) { i ->
    val x = (i % cols).toDouble() / cols - 0.5; val y = (i / cols).toDouble() / rows - 0.5
    val radius = sqrt(x * x + y * y)
    when {
        radius >= 0.5 -> 0.0
        radius > 0.5 * (1.0 - TUKEY_ALPHA) -> 0.5 * (1.0 + cos(PI * (radius / (TUKEY_ALPHA * 0.5) - 1.0 / TUKEY_ALPHA + 1.0)))
        else -> 1.0
    }
}

internal fun hasRain(db: DoubleArray, taper: DoubleArray): Boolean {
    val minimum = db.min()
    return db.indices.any { (if (taper[it] == 0.0) minimum else db[it]) > THR_DB }
}

private fun freq(index: Int, size: Int) = (if (index < (size + 1) / 2) index else index - size).toDouble()

/** bandpass, but on the full complex spectrum (h*w per level): the weight is radial, so the
 *  mirrored half is the half-spectrum's own value. */
internal fun bandpass(rows: Int, cols: Int): Array<DoubleArray> {
    val q = (0.5 * maxOf(rows, cols)).pow(1.0 / N_LEVELS)
    fun logQ(value: Double) = if (value > 0) ln(value) / ln(q) else 0.0
    val centres = DoubleArray(N_LEVELS) { logQ(0.5 * (q.pow(it) + q.pow(it + 1))) }
    val out = Array(N_LEVELS) { DoubleArray(rows * cols) }
    for (row in 0 until rows) for (col in 0 until cols) {
        val ky = freq(row, rows); val kx = abs(freq(col, cols))
        val logRadius = logQ(sqrt(kx * kx + ky * ky)); val cell = row * cols + col
        var sum = 0.0
        for (k in 0 until N_LEVELS) { val distance = logRadius - centres[k]; out[k][cell] = exp(-(distance * distance) / (2.0 * GAUSS_SCALE * GAUSS_SCALE)); sum += out[k][cell] }
        for (k in 0 until N_LEVELS) out[k][cell] /= sum
    }
    for (k in 0 until N_LEVELS) out[k][0] = if (k == 0) 1.0 else 0.0
    return out
}

internal class Cascade(val levels: Array<DoubleArray>, val mu: DoubleArray, val sd: DoubleArray)

/** decompose. Two levels share one inverse FFT (both spectra are Hermitian, so the real part is
 *  one level and the imaginary part the other). */
internal fun decompose(field: DoubleArray, weights: Array<DoubleArray>, fft: Fft2): Cascade {
    val cells = field.size
    val specRe = field.copyOf(); val specIm = DoubleArray(cells)
    fft.forward(specRe, specIm)
    val levels = Array(N_LEVELS) { DoubleArray(cells) }
    var k = 0
    while (k < N_LEVELS) {
        val even = weights[k]; val odd = if (k + 1 < N_LEVELS) weights[k + 1] else null
        val re = DoubleArray(cells); val im = DoubleArray(cells)
        for (i in 0 until cells) {
            re[i] = specRe[i] * even[i]; im[i] = specIm[i] * even[i]
            if (odd != null) { re[i] -= specIm[i] * odd[i]; im[i] += specRe[i] * odd[i] }
        }
        fft.inverse(re, im)
        levels[k] = re; if (odd != null) levels[k + 1] = im
        k += 2
    }
    val mu = DoubleArray(N_LEVELS); val sd = DoubleArray(N_LEVELS)
    for (j in 0 until N_LEVELS) {
        mu[j] = mean(levels[j]); sd[j] = std(levels[j], mu[j])
        val level = levels[j]; for (i in 0 until cells) level[i] = (level[i] - mu[j]) / sd[j]
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
internal fun noiseFilter(frames: List<DoubleArray>, rows: Int, cols: Int, fft: Fft2): DoubleArray {
    val shifted = frames.map { it.copyOf() }
    val minimum = shifted.minOf { it.min() }
    var minAbove = Double.POSITIVE_INFINITY
    for (frame in shifted) for (value in frame) if (value > minimum && value < minAbove) minAbove = value
    for (frame in shifted) for (i in frame.indices) if (frame[i] > minimum) frame[i] -= minAbove - minimum
    val taper = tukey(rows, cols)
    val sumRe = DoubleArray(rows * cols); val sumIm = DoubleArray(rows * cols)
    for (frame in shifted) {
        val frameMin = frame.min()
        val re = DoubleArray(rows * cols) { (frame[it] - frameMin) * taper[it] }; val im = DoubleArray(rows * cols)
        fft.forward(re, im)
        for (i in sumRe.indices) { sumRe[i] += re[i]; sumIm[i] += im[i] }
    }
    return DoubleArray(rows * cols) { val real = sumRe[it] / shifted.size; val imag = sumIm[it] / shifted.size; sqrt(real * real + imag * imag) }
}

internal fun noiseField(filter: DoubleArray, white: DoubleArray, fft: Fft2): DoubleArray {
    val re = white.copyOf(); val im = DoubleArray(white.size)
    fft.forward(re, im)
    for (i in re.indices) { re[i] *= filter[i]; im[i] *= filter[i] }
    fft.inverse(re, im)
    val average = mean(re); val spread = std(re, average)
    for (i in re.indices) re[i] = (re[i] - average) / spread
    return re
}

/** laplace: numpy's legacy RandomState.laplace(scale = 1/sqrt 2) on a given uniform. */
internal fun laplace(u: Double): Double {
    val b = 1.0 / sqrt(2.0)
    return if (u >= 0.5) -b * ln(2.0 - u - u) else b * ln(u + u)
}

/** sl_step: one semi-Lagrangian step (n_iter 1), displacement updated in place. */
private fun slStep(dx: DoubleArray, dy: DoubleArray, vx: Grid, vy: Grid, par: Par = Par.SERIAL) {
    val cols = vx.cols
    par.run(dx.size) { from, until ->
        for (i in from until until) {
            val row = (i / cols) + dy[i]; val col = (i % cols) + dx[i]
            // sampleClamped of vx and vy at the same point, sharing the cell and weights
            var ix = 0.0; var iy = 0.0
            clampedCell(vx.rows, cols, row, col) { i00, i01, i10, i11, rowFrac, colFrac -> ix = bilinear0(vx.values, i00, i01, i10, i11, rowFrac, colFrac); iy = bilinear0(vy.values, i00, i01, i10, i11, rowFrac, colFrac) }
            clampedCell(vx.rows, cols, row - iy / 2.0, col - ix / 2.0) { i00, i01, i10, i11, rowFrac, colFrac ->
                dx[i] -= bilinear0(vx.values, i00, i01, i10, i11, rowFrac, colFrac); dy[i] -= bilinear0(vy.values, i00, i01, i10, i11, rowFrac, colFrac) }
        }
    }
}

private fun warp(field: Grid, dx: DoubleArray, dy: DoubleArray, outval: Double = Double.NaN,
                 out: DoubleArray = DoubleArray(dx.size), par: Par = Par.SERIAL): DoubleArray {
    par.run(dx.size) { from, until ->
        for (i in from until until) {
            val value = sampleOrNaN(field, (i / field.cols) + dy[i], (i % field.cols) + dx[i])
            out[i] = if (value.isNaN()) outval else value
        }
    }
    return out
}

/** advect: extrapolate(field, V, steps, outval='min')[-1] */
internal fun advect(field: DoubleArray, motion: Motion, steps: Int, par: Par = Par.SERIAL): DoubleArray {
    val dx = DoubleArray(field.size); val dy = DoubleArray(field.size)
    repeat(steps) { slStep(dx, dy, motion.vx, motion.vy, par) }
    return warp(Grid(motion.vx.rows, motion.vx.cols, field), dx, dy, field.min(), par = par)
}

/** L1 distance to the nearest true pixel, inside the grid (two-pass chamfer; exact for the
 *  4-neighbour metric), in place: [distance] comes in as 0 at true pixels and [FAR] elsewhere. */
private const val FAR = Int.MAX_VALUE / 2
private fun l1Distance(distance: IntArray, rows: Int, cols: Int) {
    for (row in 0 until rows) for (col in 0 until cols) {
        val cell = row * cols + col
        if (row > 0 && distance[cell - cols] + 1 < distance[cell]) distance[cell] = distance[cell - cols] + 1
        if (col > 0 && distance[cell - 1] + 1 < distance[cell]) distance[cell] = distance[cell - 1] + 1
    }
    for (row in rows - 1 downTo 0) for (col in cols - 1 downTo 0) {
        val cell = row * cols + col
        if (row < rows - 1 && distance[cell + cols] + 1 < distance[cell]) distance[cell] = distance[cell + cols] + 1
        if (col < cols - 1 && distance[cell + 1] + 1 < distance[cell]) distance[cell] = distance[cell + 1] + 1
    }
}

internal fun structRadius(kmPerPixel: Double, timestep: Double) =
    maxOf(1, ((MASK_F * timestep / kmPerPixel - 1) / 2.0).toInt())

/** dilated_mask. Dilating k times by the 3x3 cross (zero border) reaches exactly the pixels
 *  within L1 distance k, so the mask is a clamp of that distance: 1 + rim inside the radius,
 *  falling by one per pixel across the rim. */
internal fun dilatedMask(wet: BooleanArray, rows: Int, cols: Int, radius: Int, rim: Int = MASK_RIM): DoubleArray =
    maskFromDistance(IntArray(wet.size) { if (wet[it]) 0 else FAR }, rows, cols, radius, rim, DoubleArray(wet.size))

/** dilatedMask of the pixels where [distance] is 0 ([distance] is overwritten), into [mask]. */
private fun maskFromDistance(distance: IntArray, rows: Int, cols: Int, radius: Int, rim: Int, mask: DoubleArray): DoubleArray {
    l1Distance(distance, rows, cols)
    var peak = 0.0
    for (i in mask.indices) {
        val steps = distance[i]; mask[i] = if (steps <= radius) 1.0 + rim else maxOf(0, radius + rim + 1 - steps).toDouble()
        if (mask[i] > peak) peak = mask[i]
    }
    if (peak > 0) for (i in mask.indices) mask[i] /= peak
    return mask
}

/** percentile on an already sorted array: np.percentile 'linear', with numpy's two-sided lerp. */
internal fun percentileSorted(sorted: DoubleArray, percent: Double): Double {
    val position = (sorted.size - 1) * (percent / 100.0)
    val lo = floor(position).toInt(); val hi = minOf(lo + 1, sorted.size - 1)
    val fraction = position - lo; val span = sorted[hi] - sorted[lo]
    return if (fraction >= 0.5) sorted[hi] - span * (1 - fraction) else sorted[lo] + span * fraction
}

/** Stable argsort of non-NaN values: LSD radix sort, 16 bits a pass, on the doubles' bits
 *  mapped to unsigned order (-0.0 as 0.0, the ties of `<`). Skips passes where all agree. */
internal fun argsortStable(values: DoubleArray): IntArray = argsortStable(values, values.size, SortBuf(values.size))

/** Reusable buffers for probmatch and its argsort, one set per member. */
internal class SortBuf(size: Int) {
    var key = LongArray(size); var idx = IntArray(size); var k2 = LongArray(size); var i2 = IntArray(size)
    val count = IntArray(1 shl 16); val wet = IntArray(size); val vals = DoubleArray(size)
}

/** argsortStable of values[0 until size]; the result is one of [buffers]'s arrays. */
internal fun argsortStable(values: DoubleArray, size: Int, buffers: SortBuf): IntArray {
    var key = buffers.key; var idx = buffers.idx; var k2 = buffers.k2; var i2 = buffers.i2; val count = buffers.count
    for (i in 0 until size) {
        val bits = java.lang.Double.doubleToRawLongBits(if (values[i] == 0.0) 0.0 else values[i])
        key[i] = if (bits < 0) bits.inv() else bits xor Long.MIN_VALUE
        idx[i] = i
    }
    for (shift in 0 until 64 step 16) {
        count.fill(0)
        for (i in 0 until size) count[((key[i] ushr shift) and 0xFFFF).toInt()]++
        if (size == 0 || count[((key[0] ushr shift) and 0xFFFF).toInt()] == size) continue
        var sum = 0
        for (digit in count.indices) { val bucketSize = count[digit]; count[digit] = sum; sum += bucketSize }
        for (i in 0 until size) {
            val digit = ((key[i] ushr shift) and 0xFFFF).toInt(); val slot = count[digit]++
            k2[slot] = key[i]; i2[slot] = idx[i]
        }
        val swapKey = key; key = k2; k2 = swapKey; val swapIdx = idx; idx = i2; i2 = swapIdx
    }
    buffers.key = key; buffers.idx = idx; buffers.k2 = k2; buffers.i2 = i2
    return idx
}

/** probmatch, with the target pre-sorted (its zeroing below the percentile keeps it sorted,
 *  since the zero value is its minimum). */
internal fun probmatch(initial: DoubleArray, targetSorted: DoubleArray,
                       out: DoubleArray = DoubleArray(initial.size), buf: SortBuf = SortBuf(initial.size)): DoubleArray {
    val minimum = initial.min(); val targetMin = targetSorted[0]
    var wetCount = 0; for (value in initial) if (value > minimum) wetCount++
    var targetWetCount = 0; for (value in targetSorted) if (value > targetMin) targetWetCount++
    val threshold = if (targetWetCount > wetCount) percentileSorted(targetSorted, 100 * (1 - wetCount.toDouble() / initial.size))
            else Double.NEGATIVE_INFINITY
    // A stable argsort puts every pixel at the minimum z first, in index order, and those all
    // map to zt: so sort only the others, ranked after them. Same ranks, a fraction of the work.
    out.fill(targetMin)
    val wet = buf.wet; val vals = buf.vals; var next = 0
    for (i in initial.indices) if (initial[i] > minimum) { wet[next] = i; vals[next] = initial[i]; next++ }   // the nWet predicate: NaN-safe
    val order = argsortStable(vals, wetCount, buf)
    val base = initial.size - wetCount
    for (rank in 0 until wetCount) {
        val target = targetSorted[base + rank]
        out[wet[order[rank]]] = if (target < threshold) targetMin else target
    }
    return out
}

/** from_db: pysteps' inverse dB transform with threshold -10 dB. */
internal fun fromDbSteps(db: Double): Float {
    if (db.isNaN()) return Float.NaN
    val rate = 10.0.pow(db / 10.0)
    return if (rate < 0.1) 0f else rate.toFloat()
}

/** Holds only what members read: the band weights and the cascades go after init, ~27 MB
 *  for Belgium that would otherwise sit on the heap through the whole ensemble. */
internal class StepsSetup(
    val p: Int, weights: Array<DoubleArray>, val filter: DoubleArray, cascades: List<Cascade>,
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
        half(re, fft.rows, fft.cols) to half(im, fft.rows, fft.cols)
    } }
    /** filter * band per level on the half spectrum, DC zeroed (the noise levels are normalised). */
    val noiseBand: Array<DoubleArray> = Array(N_LEVELS) { i ->
        val halfCols = fft.cols / 2 + 1
        DoubleArray(fft.rows * halfCols) { cell -> if (cell == 0) 0.0 else filter[(cell / halfCols) * fft.cols + cell % halfCols] * weights[i][(cell / halfCols) * fft.cols + cell % halfCols] }
    }
    /** Parseval weight of each half-spectrum cell in the full one: columns 0 and w/2 are their own mirror. */
    val parseval: DoubleArray = DoubleArray(fft.rows * (fft.cols / 2 + 1)) { cell ->
        val col = cell % (fft.cols / 2 + 1); if (col == 0 || 2 * col == fft.cols) 1.0 else 2.0
    }
}

private fun half(full: DoubleArray, rows: Int, cols: Int): DoubleArray {
    val halfCols = cols / 2 + 1
    return DoubleArray(rows * halfCols) { full[(it / halfCols) * cols + it % halfCols] }
}


/** setup; null when pysteps would return a zero forecast. */
internal fun setup(db3: List<DoubleArray>, motion: Motion, par: Par = Par.SERIAL): StepsSetup? {
    val rows = motion.vx.rows; val cols = motion.vx.cols
    val taper = tukey(rows, cols)
    val rain = db3.map { hasRain(it, taper) }
    if (!rain[2] || !rain[1]) return null
    val p = if (rain[0]) AR_ORDER else 1
    val frames = db3.takeLast(p + 1).mapIndexed { i, frame -> if (i < p) advect(frame, motion, p - i, par) else frame.copyOf() }
    val fft = Fft2(rows, cols, par)
    val weights = bandpass(rows, cols)
    val filter = noiseFilter(frames, rows, cols, fft)
    val cascades = frames.map { decompose(it, weights, fft) }
    val gammaRaw = Array(N_LEVELS) { i -> DoubleArray(p) { lag -> corrcoef(cascades.last().levels[i], cascades[cascades.size - lag - 2].levels[i]) } }
    val fits = gammaRaw.map { arParams(it) }
    return StepsSetup(p, weights, filter, cascades, gammaRaw, fits.map { it.second }.toTypedArray(),
        fits.map { it.first }.toTypedArray(), frames.last(), frames.last().sorted().toDoubleArray(), fft, par)
}

/** member: n_lead fields in mm/h (from_db applied), NaN outside the domain. */
internal fun member(steps: StepsSetup, motion: Motion, memberIndex: Int, nLead: Int, seed: Long, kmPerPixel: Double,
                    timestep: Double, onFirst: ((DoubleArray, DoubleArray) -> Unit)? = null, par: Par = steps.par): Array<FloatArray> {
    val rows = motion.vx.rows; val cols = motion.vx.cols; val cells = rows * cols; val p = steps.p
    val mu = steps.mu; val sd = steps.sd
    val radius = structRadius(kmPerPixel, timestep)
    val distance = IntArray(cells); val sortBuf = SortBuf(cells)
    val mask = DoubleArray(cells)
    for (i in 0 until cells) distance[i] = if (steps.last[i] >= THR_DB) 0 else FAR
    maskFromDistance(distance, rows, cols, radius, MASK_RIM, mask)
    val draws = Rng.uniforms(Rng.stream(seed, memberIndex, VEL_STREAM), 2)
    val epsPar = laplace(draws[0]); val epsPerp = laplace(draws[1])
    // BPS unit vectors, fixed per run
    val velocityScale = 60.0 / (timestep * (1.0 / kmPerPixel))
    val dx = DoubleArray(cells); val dy = DoubleArray(cells)
    // The cascade lives in Fourier space (half spectrum, rows x halfCols): the noise is W * filter * band,
    // each level normalised by its Parseval std, and only the recomposed field is transformed
    // back. Same algebra as pysteps; and since the AR recursion does not see the field,
    // two leads share one forward FFT (their white noise as real + i*imag) and one inverse.
    val halfCols = cols / 2 + 1; val halfCells = rows * halfCols
    // levelLagsRe[i][0] oldest .. levelLagsRe[i][p-1] newest
    val levelLagsRe = Array(N_LEVELS) { i -> Array(p) { j -> steps.spec[i][j].first.copyOf() } }
    val levelLagsIm = Array(N_LEVELS) { i -> Array(p) { j -> steps.spec[i][j].second.copyOf() } }
    val bufRe = DoubleArray(cells); val bufIm = DoubleArray(cells)
    val whiteRe = arrayOf(DoubleArray(halfCells), DoubleArray(halfCells)); val whiteIm = arrayOf(DoubleArray(halfCells), DoubleArray(halfCells))
    val stepRe = arrayOf(DoubleArray(halfCells), DoubleArray(halfCells)); val stepIm = arrayOf(DoubleArray(halfCells), DoubleArray(halfCells))
    val muSum = mu.sum()

    /** One AR step of every level, driven by white-noise spectrum (noiseRe, noiseIm); recomposed spectrum into (outRe, outIm). */
    fun step(noiseRe: DoubleArray, noiseIm: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        outRe.fill(0.0); outIm.fill(0.0)
        for (i in 0 until N_LEVELS) {
            val phi = steps.phi[i]; val band = steps.noiseBand[i]; val weight = steps.parseval; val lagsRe = levelLagsRe[i]; val lagsIm = levelLagsIm[i]
            var power = 0.0
            for (cell in 0 until halfCells) power += weight[cell] * band[cell] * band[cell] * (noiseRe[cell] * noiseRe[cell] + noiseIm[cell] * noiseIm[cell])
            val scale = phi[p] * cells / sqrt(power)       // phi_p / level std (Parseval)
            val newestRe = lagsRe[0]; val newestIm = lagsIm[0]   // the oldest lag, read before written at each o
            val levelSd = sd[i]
            par.run(halfCells) { from, until -> for (cell in from until until) {
                val bandScale = band[cell] * scale
                var valueRe = bandScale * noiseRe[cell]; var valueIm = bandScale * noiseIm[cell]
                for (lag in 0 until p) { valueRe += phi[lag] * lagsRe[p - 1 - lag][cell]; valueIm += phi[lag] * lagsIm[p - 1 - lag][cell] }
                newestRe[cell] = valueRe; newestIm[cell] = valueIm
                outRe[cell] += valueRe * levelSd; outIm[cell] += valueIm * levelSd
            } }
            for (j in 0 until p - 1) { lagsRe[j] = lagsRe[j + 1]; lagsIm[j] = lagsIm[j + 1] }
            lagsRe[p - 1] = newestRe; lagsIm[p - 1] = newestIm
        }
    }

    // per-lead buffers, reused: allocation, not arithmetic, is what a small Android heap pays for
    val fieldBufs = arrayOf(DoubleArray(cells), DoubleArray(cells)); val matched = DoubleArray(cells)
    val vx = Grid(rows, cols); val vy = Grid(rows, cols)
    return Array(nLead) { lead ->
        var eps: DoubleArray? = null
        val field = fieldBufs[lead % 2]
        if (lead % 2 == 0) {
            val pairOfLeads = lead + 1 < nLead
            Rng.normalsInto(Rng.stream(seed, memberIndex, lead), bufRe, par)
            if (lead == 0 && onFirst != null) eps = noiseField(steps.filter, bufRe.copyOf(), steps.fft)
            if (pairOfLeads) Rng.normalsInto(Rng.stream(seed, memberIndex, lead + 1), bufIm, par) else bufIm.fill(0.0)
            steps.fft.forward(bufRe, bufIm)
            par.run(rows) { rowStart, rowEnd -> for (row in rowStart until rowEnd) for (col in 0 until halfCols) {
                val cell = row * cols + col; val mirror = ((rows - row) % rows) * cols + (cols - col) % cols; val halfCell = row * halfCols + col
                // Z = A + iB with A, B Hermitian: A = (Z + conj Z-) / 2, B = (Z - conj Z-) / 2i
                whiteRe[0][halfCell] = 0.5 * (bufRe[cell] + bufRe[mirror]); whiteIm[0][halfCell] = 0.5 * (bufIm[cell] - bufIm[mirror])
                whiteRe[1][halfCell] = 0.5 * (bufIm[cell] + bufIm[mirror]); whiteIm[1][halfCell] = -0.5 * (bufRe[cell] - bufRe[mirror])
            } }
            step(whiteRe[0], whiteIm[0], stepRe[0], stepIm[0])
            if (pairOfLeads) step(whiteRe[1], whiteIm[1], stepRe[1], stepIm[1]) else { stepRe[1].fill(0.0); stepIm[1].fill(0.0) }
            // full spectrum of F0 + i F1 (each Hermitian), so the inverse is f0 + i f1
            par.run(rows) { rowStart, rowEnd -> for (row in rowStart until rowEnd) for (col in 0 until cols) {
                val cell = row * cols + col
                val halfCell: Int; val sign: Double
                if (col < halfCols) { halfCell = row * halfCols + col; sign = 1.0 } else { halfCell = ((rows - row) % rows) * halfCols + (cols - col); sign = -1.0 }
                bufRe[cell] = stepRe[0][halfCell] - sign * stepIm[1][halfCell]; bufIm[cell] = sign * stepIm[0][halfCell] + stepRe[1][halfCell]
            } }
            steps.fft.inverse(bufRe, bufIm)
            for (j in 0 until cells) field[j] = bufRe[j] + muSum
            if (pairOfLeads) { val second = fieldBufs[1]; for (j in 0 until cells) second[j] = bufIm[j] + muSum }
        }
        val fieldMin = field.min()
        for (j in 0 until cells) { field[j] = fieldMin + (field[j] - fieldMin) * mask[j]; if (!(field[j] > fieldMin)) field[j] = fieldMin }
        probmatch(field, steps.lastSorted, matched, sortBuf)
        for (i in 0 until cells) distance[i] = if (matched[i] >= THR_DB) 0 else FAR
        maskFromDistance(distance, rows, cols, radius, MASK_RIM, mask)
        val minutes = (lead + 1) * timestep
        val gPar = P_PAR[0] * minutes.pow(P_PAR[1]) + P_PAR[2]
        val gPerp = P_PERP[0] * minutes.pow(P_PERP[1]) + P_PERP[2]
        par.run(cells) { from, until -> for (i in from until until) {
            // the unit vectors along the motion, recomputed rather than held: two fields less per member
            val vxAt = motion.vx.values[i]; val vyAt = motion.vy.values[i]; val speed = sqrt(vxAt * vxAt + vyAt * vyAt)
            val parX = if (speed > 1e-12) vxAt / speed else 0.0; val parY = if (speed > 1e-12) vyAt / speed else 0.0
            vx.values[i] = vxAt + (gPar * epsPar * parX + gPerp * epsPerp * -parY) / velocityScale
            vy.values[i] = vyAt + (gPar * epsPar * parY + gPerp * epsPerp * parX) / velocityScale
        } }
        slStep(dx, dy, vx, vy, par)
        if (eps != null) onFirst?.invoke(eps, matched.copyOf())
        val matchedGrid = Grid(rows, cols, matched)
        // warp's NaN outval straight to mm/h, without a double field in between
        FloatArray(cells).also { out -> par.run(cells) { from, until -> for (i in from until until)
            out[i] = fromDbSteps(sampleOrNaN(matchedGrid, (i / cols) + dy[i], (i % cols) + dx[i])) } }
    }
}

/**
 * The ensemble: members[member][lead][row * cols + col] in mm/h, NaN off-domain. Members run in parallel;
 * each draws only from its own random streams, so the result does not depend on scheduling.
 */
fun ensemble(train: List<Grid>, nLead: Int = 12, nMembers: Int = 10, seed: Long = 42,
             kmPerPixel: Double = 1.0, timestep: Double = 5.0, threads: Int = Runtime.getRuntime().availableProcessors(),
             dir: java.io.File? = null): EnsembleResult {
    // Flow and setup split their pixel loops threads-fold; then members run nThreads at a time
    // (capped by the heap left after setup) and each splits its loops ways-fold, so at most
    // nThreads * ways <= threads cores are busy.
    val rows = train.last().rows; val cols = train.last().cols
    val loopPool = if (threads > 1) Executors.newFixedThreadPool(threads - 1) else null
    var pool: java.util.concurrent.ExecutorService? = null
    try {
        val flowStart = System.nanoTime()
        val db = train.takeLast(3).map { grid -> grid.map(::toDb) }
        val velocity = motion(db, Par(loopPool, threads))
        val tFlow = since(flowStart); val setupStart = System.nanoTime()
        val stepsSetup = setup(db.map { it.values }, velocity, Par(loopPool, threads))
            ?: return EnsembleResult(Members.alloc(nMembers, nLead, rows * cols, dir), velocity, rows, cols, mapOf("flow" to tFlow))
        val tSetup = since(setupStart); val membersStart = System.nanoTime()
        // ~40 float64 fields per member (cascade spectra, buffers, transients) plus its 12
        // float32 leads until they go off-heap; cap the threads so a 192 MB Android heap does not run
        // out. Measured, not derived: 40 was fine on a 192 MB emulator; a finer cap would need a per-member peak.
        val runtime = Runtime.getRuntime()
        val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val byHeap = (free / ((40L + nLead / 2) * 8 * rows * cols)).toInt()
        val nThreads = maxOf(1, minOf(threads, nMembers, byHeap))
        val ways = maxOf(1, threads / nThreads)
        val par = Par(loopPool, ways)
        pool = Executors.newFixedThreadPool(nThreads)
        val members = Members.alloc(nMembers, nLead, rows * cols, dir)
        val jobs = (0 until nMembers).map { index ->
            pool.submit<Unit> { members.put(index, member(stepsSetup, velocity, index, nLead, seed, kmPerPixel, timestep, par = par)) }
        }
        jobs.forEach { it.get() }
        return EnsembleResult(members, velocity, rows, cols, mapOf("flow" to tFlow, "setup" to tSetup, "members" to since(membersStart),
            "threads" to nThreads.toDouble(), "ways" to ways.toDouble()))
    } finally {
        pool?.shutdown(); loopPool?.shutdown()
    }
}

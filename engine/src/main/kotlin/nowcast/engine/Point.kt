package nowcast.engine

import kotlin.math.floor

const val RAIN_THRESHOLD = 0.1

class PointForecast(
    val minutes: IntArray, val values: DoubleArray, val probability: DoubleArray,
    val pmin: DoubleArray, val p10: DoubleArray, val p25: DoubleArray,
    val p75: DoubleArray, val p90: DoubleArray, val pmax: DoubleArray,
    val rainChance: Double, val summary: String, val inCoverage: Boolean,
)

/** Twin of rain-nowcast/nowcast/interpolate.py:point_series. motion is px per frame step. */
fun pointSeries(frames: List<FloatArray>, h: Int, w: Int, motion: Motion?, row: Int, col: Int,
                subSteps: Int, box: Int = 1): DoubleArray =
    pointSeries(frames.size, h, w, motion, row, col, subSteps, box) { k, i -> frames[k][i].toDouble() }

/** Pixel i of frame k; a fun interface, so no boxing. */
internal fun interface Pixels { fun at(k: Int, i: Int): Double }

/** pointSeries reading frame k's pixel i through [at]: it samples a few pixels per step, so
 *  copying every frame to a Grid first cost ~25 MB per curve. Same arithmetic as sampleClamped. */
internal fun pointSeries(nFrames: Int, h: Int, w: Int, motion: Motion?, row: Int, col: Int,
                         subSteps: Int, box: Int, at: Pixels): DoubleArray {
    val side = 2 * box + 1; val nb = side * side
    val rows = DoubleArray(nb) { (row + it / side - box).toDouble() }; val cols = DoubleArray(nb) { (col + it % side - box).toDouble() }
    val vx = DoubleArray(nb) { if (motion == null) 0.0 else sampleClamped(motion.vx, rows[it], cols[it]) }
    val vy = DoubleArray(nb) { if (motion == null) 0.0 else sampleClamped(motion.vy, rows[it], cols[it]) }
    fun samp(k: Int, r: Double, c: Double): Double {
        var out = 0.0
        clampedCell(h, w, r, c) { i00, i01, i10, i11, fr, fc ->
            val a = at.at(k, i00).let { if (it.isNaN()) 0.0 else it }; val b = at.at(k, i01).let { if (it.isNaN()) 0.0 else it }
            val cc = at.at(k, i10).let { if (it.isNaN()) 0.0 else it }; val d = at.at(k, i11).let { if (it.isNaN()) 0.0 else it }
            out = (1 - fr) * ((1 - fc) * a + fc * b) + fr * ((1 - fc) * cc + fc * d)
        }
        return out
    }
    val out = DoubleArray((nFrames - 1) * subSteps + 1); var o = 0
    for (k in 0 until nFrames - 1) for (s in 0 until subSteps) {
        val d = s.toDouble() / subSteps
        var sum = 0.0
        for (j in 0 until nb) {
            val back = samp(k, rows[j] - vy[j] * d, cols[j] - vx[j] * d)
            val fwd = samp(k + 1, rows[j] + vy[j] * (1 - d), cols[j] + vx[j] * (1 - d))
            sum += (1 - d) * back + d * fwd
        }
        out[o++] = sum / nb
    }
    var sum = 0.0
    for (j in 0 until nb) sum += samp(nFrames - 1, rows[j], cols[j])
    out[o] = sum / nb
    return out
}

/** numpy.percentile, default "linear" method. */
fun percentile(xs: DoubleArray, q: Double): Double {
    val s = xs.sorted(); val pos = q / 100.0 * (s.size - 1)
    val lo = floor(pos).toInt(); val hi = minOf(lo + 1, s.size - 1)
    return s[lo] + (pos - lo) * (s[hi] - s[lo])
}

/** Twin of server.Nowcaster._summary - wording must not drift. */
fun summary(minutes: IntArray, series: DoubleArray, probability: DoubleArray, age: Int): String {
    val now = minutes.indexOfFirst { it >= age }
    if (now < 0) return "No current forecast: the newest radar is $age min old"
    val future = series.copyOfRange(now, series.size)
    val ahead = IntArray(minutes.size - now) { minutes[now + it] - age }
    // rint: Python's round() is half-to-even
    val chance = Math.rint(100 * probability.copyOfRange(now, probability.size).max()).toInt()
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

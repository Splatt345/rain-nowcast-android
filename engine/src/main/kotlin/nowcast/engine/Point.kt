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

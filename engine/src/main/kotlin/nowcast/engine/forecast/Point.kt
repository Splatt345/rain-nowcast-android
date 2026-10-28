package nowcast.engine.forecast

import nowcast.engine.math.*
import kotlin.math.floor

const val RAIN_THRESHOLD = 0.1

class PointForecast(
    val minutes: IntArray, val values: DoubleArray, val probability: DoubleArray,
    val pmin: DoubleArray, val p10: DoubleArray, val p25: DoubleArray,
    val p75: DoubleArray, val p90: DoubleArray, val pmax: DoubleArray,
    val rainChance: Double, val summary: String, val inCoverage: Boolean,
)

/** Twin of ha-rain-nowcast's rain-nowcast/nowcast/interpolate.py:point_series. motion is px per frame step. */
fun pointSeries(frames: List<FloatArray>, rows: Int, cols: Int, motion: Motion?, row: Int, col: Int,
                subSteps: Int, box: Int = 1): DoubleArray =
    pointSeries(frames.size, rows, cols, motion, row, col, subSteps, box) { frame, cell -> frames[frame][cell].toDouble() }

/** Pixel [cell] of [frame]; a fun interface, so no boxing. */
internal fun interface Pixels { fun at(frame: Int, cell: Int): Double }

/** pointSeries reading each frame's pixels through [at]: it samples a few pixels per step, so
 *  copying every frame to a Grid first cost ~25 MB per curve. Same arithmetic as sampleClamped. */
internal fun pointSeries(frameCount: Int, rows: Int, cols: Int, motion: Motion?, row: Int, col: Int,
                         subSteps: Int, box: Int, at: Pixels): DoubleArray {
    val side = 2 * box + 1; val boxCells = side * side
    val sampleRows = DoubleArray(boxCells) { (row + it / side - box).toDouble() }; val sampleCols = DoubleArray(boxCells) { (col + it % side - box).toDouble() }
    val vx = DoubleArray(boxCells) { if (motion == null) 0.0 else sampleClamped(motion.vx, sampleRows[it], sampleCols[it]) }
    val vy = DoubleArray(boxCells) { if (motion == null) 0.0 else sampleClamped(motion.vy, sampleRows[it], sampleCols[it]) }
    fun sample(frame: Int, atRow: Double, atCol: Double): Double {
        var out = 0.0
        clampedCell(rows, cols, atRow, atCol) { i00, i01, i10, i11, rowFrac, colFrac ->
            val v00 = at.at(frame, i00).let { if (it.isNaN()) 0.0 else it }; val v01 = at.at(frame, i01).let { if (it.isNaN()) 0.0 else it }
            val v10 = at.at(frame, i10).let { if (it.isNaN()) 0.0 else it }; val v11 = at.at(frame, i11).let { if (it.isNaN()) 0.0 else it }
            out = (1 - rowFrac) * ((1 - colFrac) * v00 + colFrac * v01) + rowFrac * ((1 - colFrac) * v10 + colFrac * v11)
        }
        return out
    }
    val out = DoubleArray((frameCount - 1) * subSteps + 1); var next = 0
    for (frame in 0 until frameCount - 1) for (subStep in 0 until subSteps) {
        val fraction = subStep.toDouble() / subSteps
        var sum = 0.0
        for (j in 0 until boxCells) {
            val back = sample(frame, sampleRows[j] - vy[j] * fraction, sampleCols[j] - vx[j] * fraction)
            val forward = sample(frame + 1, sampleRows[j] + vy[j] * (1 - fraction), sampleCols[j] + vx[j] * (1 - fraction))
            sum += (1 - fraction) * back + fraction * forward
        }
        out[next++] = sum / boxCells
    }
    var sum = 0.0
    for (j in 0 until boxCells) sum += sample(frameCount - 1, sampleRows[j], sampleCols[j])
    out[next] = sum / boxCells
    return out
}

/** numpy.percentile, default "linear" method. */
fun percentile(values: DoubleArray, percent: Double): Double {
    val sorted = values.sorted(); val pos = percent / 100.0 * (sorted.size - 1)
    val lo = floor(pos).toInt(); val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo])
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

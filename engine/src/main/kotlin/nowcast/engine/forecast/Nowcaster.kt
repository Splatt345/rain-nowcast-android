package nowcast.engine.forecast

import nowcast.engine.math.*
import nowcast.engine.radar.*
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

class Forecast(
    val region: Region, val geo: GridGeo, val past: List<Frame>,
    val members: Members, val mean: Array<FloatArray>, val motion: Motion,
    val issued: Instant, val modelUsed: Boolean, val modelError: String?,
    /** Seconds per stage: flow, setup, members (the ensemble), model, combine, mean. */
    val timings: Map<String, Double> = emptyMap(),
) {
    /** Median frame gap in whole minutes - never ODIM accutime, which lies (it says 15). */
    val intervalMinutes: Int = medianGapMinutes(past)

    /** Twin of server.Nowcaster._point_state. */
    fun point(lat: Double, lon: Double, now: Instant = Instant.now()): PointForecast {
        val rows = geo.rows; val cols = geo.cols
        val (rawRow, rawCol) = geo.rowCol(lat, lon)
        val inCoverage = rawRow in 0 until rows && rawCol in 0 until cols
        val row = rawRow.coerceIn(0, rows - 1); val col = rawCol.coerceIn(0, cols - 1)
        val first = -intervalMinutes * (past.size - 1); val last = intervalMinutes * mean.size
        val minutes = IntArray(last - first + 1) { first + it }
        val pastCount = past.size
        // past frames as the float32 they are in the members' frame list, without copying them
        fun curve(future: (Int, Int) -> Float) = pointSeries(pastCount + mean.size, rows, cols, motion, row, col, intervalMinutes, 1) { frame, cell ->
            if (frame < pastCount) past[frame].rain.values[cell].toFloat().toDouble() else future(frame - pastCount, cell).toDouble() }
        val series = curve { lead, cell -> mean[lead][cell] }
        val curves = (0 until members.count).map { member -> curve { lead, cell -> members.at(member, lead, cell) } }
        val probability = DoubleArray(minutes.size) { index -> curves.count { it[index] >= RAIN_THRESHOLD }.toDouble() / curves.size }
        fun band(percent: Double, lower: Boolean) = DoubleArray(minutes.size) { index ->
            val value = percentile(DoubleArray(curves.size) { curves[it][index] }, percent)
            if (lower) minOf(value, series[index]) else maxOf(value, series[index])
        }
        val age = maxOf(0L, Duration.between(past.last().time, now).toMinutes()).toInt()
        val zero = minutes.indexOfFirst { it >= 0 }
        return PointForecast(
            minutes, series, probability,
            DoubleArray(minutes.size) { index -> minOf(curves.minOf { it[index] }, series[index]) },
            band(10.0, true), band(25.0, true), band(75.0, false), band(90.0, false),
            DoubleArray(minutes.size) { index -> maxOf(curves.maxOf { it[index] }, series[index]) },
            probability.copyOfRange(zero, probability.size).max(), summary(minutes, series, probability, age), inCoverage)
    }
}

internal fun medianGapMinutes(past: List<Frame>): Int =
    past.zipWithNext { earlier, later -> Duration.between(earlier.time, later.time).toMinutes() }
        .filter { it > 0 }.sorted().let { if (it.isEmpty()) 5 else it[it.size / 2].toInt() }

fun Frame.crop(firstRow: Int, firstCol: Int, rows: Int, cols: Int) = Frame(time,
    Grid(rows, cols, DoubleArray(rows * cols) { rain[firstRow + it / cols, firstCol + it % cols] }),
    GridGeo(geo.laea, geo.left + firstCol * geo.pixelWidth, geo.top - firstRow * geo.pixelHeight, geo.pixelWidth, geo.pixelHeight, rows, cols))

/** [dir]: where the members are memory-mapped (see [Members]); null keeps them in a direct buffer. */
class Nowcaster(private val model: SmaatModel?, private val nMembers: Int = 10, private val seed: Long = 42,
                private val dir: java.io.File? = null) {
    fun run(region: Region, frames: List<Frame>): Forecast {
        val train = frames.map { it.rain }
        val geo = frames.last().geo
        // STEPS' velocity perturbations and incremental mask are in km and minutes
        val result = ensemble(train, 12, nMembers, seed, kmPerPixel = geo.pixelWidth / 1000.0,
            timestep = medianGapMinutes(frames).toDouble(), dir = dir)
        val timings = LinkedHashMap(result.timings)
        var members = result.members; var used = false; var error: String? = null
        if (model != null) {
            try {
                var start = System.nanoTime()
                val modelFrames = model.forecast(train, 12)
                timings["model"] = since(start); start = System.nanoTime()
                val weights = region.weights?.toDoubleArray() ?: Combine.WEIGHTS
                members = Combine.combine(members, modelFrames, weights); used = true
                timings["combine"] = since(start)
            } catch (problem: Exception) {
                // A nowcast that stops because a network could not run is worse than one without it.
                error = "${problem::class.simpleName}: ${problem.message}"
            }
        }
        val start = System.nanoTime()
        val cells = result.rows * result.cols
        val mean = Array(12) { lead -> FloatArray(cells) { cell ->
            var sum = 0.0; var count = 0
            for (member in 0 until members.count) { val value = members.at(member, lead, cell); if (!value.isNaN()) { sum += value; count++ } }
            if (count > 0) (sum / count).toFloat() else Float.NaN
        } }
        timings["mean"] = since(start)
        members.seal()
        return Forecast(region, geo, frames, members, mean, result.motion, frames.last().time, used, error, timings)
    }

    /**
     * The whole cycle (motion, ensemble, model, combine) on a boxKm square around the point only:
     * the cheap path for background "rain in N min" notifications. [frames] may be region frames
     * or already cropped to [pointBbox]; the box is clamped inside them. Not the full forecast -
     * a small box sees less context.
     */
    fun runPoint(region: Region, frames: List<Frame>, lat: Double, lon: Double, boxKm: Double = 128.0,
                 now: Instant = Instant.now()): PointForecast {
        val geo = frames.last().geo
        val (row, col) = geo.rowCol(lat, lon)
        val boxRows = minOf(geo.rows, (boxKm * 1000 / geo.pixelHeight).roundToInt()); val boxCols = minOf(geo.cols, (boxKm * 1000 / geo.pixelWidth).roundToInt())
        val firstRow = (row - boxRows / 2).coerceIn(0, geo.rows - boxRows); val firstCol = (col - boxCols / 2).coerceIn(0, geo.cols - boxCols)
        return run(region, frames.map { it.crop(firstRow, firstCol, boxRows, boxCols) }).point(lat, lon, now)
    }
}

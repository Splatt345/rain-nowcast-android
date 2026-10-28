package nowcast.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

class Forecast(
    val region: Region, val geo: GridGeo, val past: List<Frame>,
    val members: Array<Array<FloatArray>>, val mean: Array<FloatArray>, val motion: Motion,
    val issued: Instant, val modelUsed: Boolean, val modelError: String?,
    /** Seconds per stage: flow, setup, members (the ensemble), model, combine, mean. */
    val timings: Map<String, Double> = emptyMap(),
) {
    /** Median frame gap in whole minutes - never ODIM accutime, which lies (it says 15). */
    val intervalMinutes: Int = medianGapMinutes(past)

    /** Twin of server.Nowcaster._point_state. */
    fun point(lat: Double, lon: Double, now: Instant = Instant.now()): PointForecast {
        val h = geo.h; val w = geo.w
        val (r0, c0) = geo.rowCol(lat, lon)
        val inCoverage = r0 in 0 until h && c0 in 0 until w
        val row = r0.coerceIn(0, h - 1); val col = c0.coerceIn(0, w - 1)
        val first = -intervalMinutes * (past.size - 1); val last = intervalMinutes * mean.size
        val minutes = IntArray(last - first + 1) { first + it }
        val np = past.size
        // past frames as the float32 they are in the members' frame list, without copying them
        fun curve(fut: Array<FloatArray>) = pointSeries(np + fut.size, h, w, motion, row, col, intervalMinutes, 1) { k, i ->
            if (k < np) past[k].rain.v[i].toFloat().toDouble() else fut[k - np][i].toDouble() }
        val series = curve(mean)
        val curves = members.map(::curve)
        val prob = DoubleArray(minutes.size) { t -> curves.count { it[t] >= RAIN_THRESHOLD }.toDouble() / curves.size }
        fun band(q: Double, lower: Boolean) = DoubleArray(minutes.size) { t ->
            val p = percentile(DoubleArray(curves.size) { curves[it][t] }, q)
            if (lower) minOf(p, series[t]) else maxOf(p, series[t])
        }
        val age = maxOf(0L, Duration.between(past.last().time, now).toMinutes()).toInt()
        val zero = minutes.indexOfFirst { it >= 0 }
        return PointForecast(
            minutes, series, prob,
            DoubleArray(minutes.size) { t -> minOf(curves.minOf { it[t] }, series[t]) },
            band(10.0, true), band(25.0, true), band(75.0, false), band(90.0, false),
            DoubleArray(minutes.size) { t -> maxOf(curves.maxOf { it[t] }, series[t]) },
            prob.copyOfRange(zero, prob.size).max(), summary(minutes, series, prob, age), inCoverage)
    }
}

internal fun medianGapMinutes(past: List<Frame>): Int =
    past.zipWithNext { a, b -> Duration.between(a.time, b.time).toMinutes() }
        .filter { it > 0 }.sorted().let { if (it.isEmpty()) 5 else it[it.size / 2].toInt() }

fun Frame.crop(r0: Int, c0: Int, h: Int, w: Int) = Frame(time,
    Grid(h, w, DoubleArray(h * w) { rain[r0 + it / w, c0 + it % w] }),
    GridGeo(geo.laea, geo.x1 + c0 * geo.px, geo.y2 - r0 * geo.py, geo.px, geo.py, h, w))

class Nowcaster(private val model: SmaatModel?, private val nMembers: Int = 10, private val seed: Long = 42) {
    fun run(region: Region, frames: List<Frame>): Forecast {
        val train = frames.map { it.rain }
        val geo = frames.last().geo
        // STEPS' velocity perturbations and incremental mask are in km and minutes
        val ens = ensemble(train, 12, nMembers, seed, kmPerPixel = geo.px / 1000.0,
            timestep = medianGapMinutes(frames).toDouble())
        val timings = LinkedHashMap(ens.timings)
        var members = ens.members; var used = false; var err: String? = null
        if (model != null) {
            try {
                var t = System.nanoTime()
                val ml = model.forecast(train, 12)
                timings["model"] = since(t); t = System.nanoTime()
                val w = region.weights?.toDoubleArray() ?: Combine.WEIGHTS
                members = Combine.combine(members, ml, w); used = true
                timings["combine"] = since(t)
            } catch (e: Exception) {
                // A nowcast that stops because a network could not run is worse than one without it.
                err = "${e::class.simpleName}: ${e.message}"
            }
        }
        val t = System.nanoTime()
        val n = ens.h * ens.w
        val mean = Array(12) { k -> FloatArray(n) { i ->
            var s = 0.0; var c = 0
            for (m in members) { val x = m[k][i]; if (!x.isNaN()) { s += x; c++ } }
            if (c > 0) (s / c).toFloat() else Float.NaN
        } }
        timings["mean"] = since(t)
        return Forecast(region, geo, frames, members, mean, ens.motion, frames.last().time, used, err, timings)
    }

    /**
     * The whole cycle (motion, ensemble, model, combine) on a boxKm square around the point only:
     * the cheap path for background "rain in N min" notifications. [frames] may be region frames
     * or already cropped to [pointBbox]; the box is clamped inside them. Not the full forecast -
     * a small box sees less context (see the plan's interface notes for its measured error).
     */
    fun runPoint(region: Region, frames: List<Frame>, lat: Double, lon: Double, boxKm: Double = 128.0,
                 now: Instant = Instant.now()): PointForecast {
        val g = frames.last().geo
        val (r, c) = g.rowCol(lat, lon)
        val bh = minOf(g.h, (boxKm * 1000 / g.py).roundToInt()); val bw = minOf(g.w, (boxKm * 1000 / g.px).roundToInt())
        val r0 = (r - bh / 2).coerceIn(0, g.h - bh); val c0 = (c - bw / 2).coerceIn(0, g.w - bw)
        return run(region, frames.map { it.crop(r0, c0, bh, bw) }).point(lat, lon, now)
    }
}

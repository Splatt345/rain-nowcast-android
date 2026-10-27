package nowcast.engine

import java.time.Duration
import java.time.Instant

class Forecast(
    val region: Region, val geo: GridGeo, val past: List<Frame>,
    val members: Array<Array<FloatArray>>, val mean: Array<FloatArray>, val motion: Motion,
    val issued: Instant, val modelUsed: Boolean, val modelError: String?,
) {
    /** Median frame gap in whole minutes - never ODIM accutime, which lies (it says 15). */
    val intervalMinutes: Int = medianGapMinutes(past)

    /** Twin of server.Nowcaster._point_state. */
    fun point(lat: Double, lon: Double, now: Instant = Instant.now()): PointForecast {
        val h = geo.h; val w = geo.w
        val (r0, c0) = geo.rowCol(lat, lon)
        val inCoverage = r0 in 0 until h && c0 in 0 until w
        val row = r0.coerceIn(0, h - 1); val col = c0.coerceIn(0, w - 1)
        val pastF = past.map { f -> FloatArray(h * w) { f.rain.v[it].toFloat() } }
        val first = -intervalMinutes * (past.size - 1); val last = intervalMinutes * mean.size
        val minutes = IntArray(last - first + 1) { first + it }
        val series = pointSeries(pastF + mean.toList(), h, w, motion, row, col, intervalMinutes)
        val curves = members.map { m -> pointSeries(pastF + m.toList(), h, w, motion, row, col, intervalMinutes) }
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

class Nowcaster(private val model: SmaatModel?, private val nMembers: Int = 10, private val seed: Long = 42) {
    fun run(region: Region, frames: List<Frame>): Forecast {
        val train = frames.map { it.rain }
        val geo = frames.last().geo
        // STEPS' velocity perturbations and incremental mask are in km and minutes
        val ens = ensemble(train, 12, nMembers, seed, kmPerPixel = geo.px / 1000.0,
            timestep = medianGapMinutes(frames).toDouble())
        var members = ens.members; var used = false; var err: String? = null
        if (model != null) {
            try {
                val ml = model.forecast(train, 12)
                val w = region.weights?.toDoubleArray() ?: Combine.WEIGHTS
                members = Combine.combine(members, ml, w); used = true
            } catch (e: Exception) {
                // A nowcast that stops because a network could not run is worse than one without it.
                err = "${e::class.simpleName}: ${e.message}"
            }
        }
        val n = ens.h * ens.w
        val mean = Array(12) { k -> FloatArray(n) { i ->
            var s = 0.0; var c = 0
            for (m in members) { val x = m[k][i]; if (!x.isNaN()) { s += x; c++ } }
            if (c > 0) (s / c).toFloat() else Float.NaN
        } }
        return Forecast(region, geo, frames, members, mean, ens.motion, frames.last().time, used, err)
    }
}

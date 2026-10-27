package nowcast.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.exp

// STAND-INS for Plan 1 Task 9 so the app builds before the engine branch lands.
// Same signatures as Task 9. DELETE THIS FILE AT MERGE - nothing else lives here.

class Forecast(
    val region: Region, val geo: GridGeo, val past: List<Frame>,
    val members: Array<Array<FloatArray>>, val mean: Array<FloatArray>, val motion: Motion,
    val issued: Instant, val modelUsed: Boolean, val modelError: String?,
) {
    val intervalMinutes: Int = past.zipWithNext { a, b -> Duration.between(a.time, b.time).toMinutes() }
        .filter { it > 0 }.sorted().let { if (it.isEmpty()) 5 else it[it.size / 2].toInt() }

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

/** Stand-in ensemble: advected persistence with perturbed speed and intensity per member. */
class Nowcaster(private val model: SmaatModel?, private val nMembers: Int = 10, private val seed: Long = 42) {
    fun run(region: Region, frames: List<Frame>): Forecast {
        val train = frames.map { it.rain }
        val last = train.last(); val h = last.h; val w = last.w; val n = h * w
        val mot = motion(train.map { g -> g.map(::toDb) })
        var members = Array(nMembers) { m ->
            val z = Rng.normals(Rng.stream(seed, m, 0), 3)
            val su = 1 + 0.15 * z[0]; val sv = 1 + 0.15 * z[1]; val gain = exp(0.15 * z[2])
            Array(12) { k ->
                val s = k + 1.0
                FloatArray(n) { i ->
                    val r = i / w; val c = i % w
                    val x = sampleOrNaN(last, r - mot.vy.v[i] * sv * s, c - mot.vx.v[i] * su * s)
                    if (x.isNaN()) 0f else (x * gain).toFloat()
                }
            }
        }
        var used = false; var err: String? = null
        if (model != null) {
            try {
                val ml = model.forecast(train, 12)
                val wts = region.weights?.toDoubleArray() ?: Combine.WEIGHTS
                members = Combine.combine(members, ml, wts); used = true
            } catch (e: Exception) {
                err = "${e::class.simpleName}: ${e.message}"
            }
        }
        val mean = Array(12) { k -> FloatArray(n) { i ->
            var s = 0.0; var c = 0
            for (m in members) { val x = m[k][i]; if (!x.isNaN()) { s += x; c++ } }
            if (c > 0) (s / c).toFloat() else Float.NaN
        } }
        return Forecast(region, frames.last().geo, frames, members, mean, mot, frames.last().time, used, err)
    }
}

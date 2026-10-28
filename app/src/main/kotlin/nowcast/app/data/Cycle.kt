package nowcast.app.data

import nowcast.engine.forecast.*
import nowcast.engine.radar.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

object Cycle {
    /** Background point path: a 192 km box halves the 128 km box's worst errors for about 1 s more. */
    const val BOX_KM = 192.0

    /** Nothing new to compute: the newest frame is what the last run (for the same region or home) was issued on. */
    fun skip(newest: Instant, lastIssued: Instant?, sameTarget: Boolean, force: Boolean): Boolean =
        !force && sameTarget && lastIssued != null && newest.epochSecond == lastIssued.epochSecond

    fun sameHome(a: Pair<Double, Double>, b: Pair<Double, Double>?): Boolean =
        b != null && abs(a.first - b.first) < 1e-4 && abs(a.second - b.second) < 1e-4

    /**
     * [Nowcaster.runPoint]'s crop and run, but keeping the box [Forecast]: the radar widget needs its
     * frames at +30 and +60 min, which runPoint throws away. Same pixels, same result for the point.
     */
    fun box(n: Nowcaster, region: Region, frames: List<Frame>, lat: Double, lon: Double, boxKm: Double = BOX_KM): Forecast {
        val g = frames.last().geo
        val (r, c) = g.rowCol(lat, lon)
        val bh = minOf(g.h, (boxKm * 1000 / g.py).roundToInt()); val bw = minOf(g.w, (boxKm * 1000 / g.px).roundToInt())
        val r0 = (r - bh / 2).coerceIn(0, g.h - bh); val c0 = (c - bw / 2).coerceIn(0, g.w - bw)
        return n.run(region, frames.map { it.crop(r0, c0, bh, bw) })
    }
}

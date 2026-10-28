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

    fun sameHome(home: Pair<Double, Double>, other: Pair<Double, Double>?): Boolean =
        other != null && abs(home.first - other.first) < 1e-4 && abs(home.second - other.second) < 1e-4

    /**
     * [Nowcaster.runPoint]'s crop and run, but keeping the box [Forecast]: the radar widget needs its
     * frames at +30 and +60 min, which runPoint throws away. Same pixels, same result for the point.
     */
    fun box(nowcaster: Nowcaster, region: Region, frames: List<Frame>, lat: Double, lon: Double, boxKm: Double = BOX_KM): Forecast {
        val geo = frames.last().geo
        val (row, col) = geo.rowCol(lat, lon)
        val boxRows = minOf(geo.rows, (boxKm * 1000 / geo.pixelHeight).roundToInt()); val boxCols = minOf(geo.cols, (boxKm * 1000 / geo.pixelWidth).roundToInt())
        val top = (row - boxRows / 2).coerceIn(0, geo.rows - boxRows); val left = (col - boxCols / 2).coerceIn(0, geo.cols - boxCols)
        return nowcaster.run(region, frames.map { it.crop(top, left, boxRows, boxCols) })
    }
}

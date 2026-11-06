package nowcast.engine.outlook

import kotlinx.serialization.json.*
import nowcast.engine.forecast.SHOWN_AHEAD_MIN
import nowcast.engine.forecast.SHOWN_PAST_MIN
import nowcast.engine.radar.boxAround
import nowcast.engine.radar.getBytes
import nowcast.engine.radar.inParallel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * The map without a radar key, as Open-Meteo's own weather maps draw it: a model's precipitation on its own
 * grid, read from the .om files they publish on S3 (open data, no key, no API quota), a box around home only.
 */
object OpenMeteoMap {
    private const val BASE = "https://openmeteo.s3.amazonaws.com/data_spatial"
    /** Finest first, as their map's seamless layers: (model, minutes per step). Regular lat/lon grids only. */
    // ponytail: HARMONIE, UKMO 2 km, HRRR and ICON-CH are on rotated or projected grids; add them with a projection each
    private val MODELS = listOf("meteofrance_arome_france_hd_15min" to 15, "dwd_icon_d2" to 60, "dwd_icon_eu" to 60,
        "jma_msm" to 60, "dwd_icon" to 60)
    private val RUN = DateTimeFormatter.ofPattern("yyyy/MM/dd/HHmm'Z'").withZone(ZoneOffset.UTC)
    private val FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm").withZone(ZoneOffset.UTC)

    fun fetch(lat: Double, lon: Double, now: Instant = Instant.now()): RainGrid {
        for ((model, step) in MODELS) {
            val (code, body) = getBytes("$BASE/$model/latest.json")
            if (code !in 200..299) continue
            val meta = Json.parseToJsonElement(body.decodeToString()).jsonObject
            val (south, west, north, east) = Regex("""BBOX\[([^\]]*)]""").find(meta.getValue("crs_wkt").jsonPrimitive.content)
                ?.groupValues?.get(1)?.split(",")?.map { it.trim().toDouble() } ?: continue
            if (lat !in south..north || lon !in west..east) continue
            val run = Instant.parse(meta.getValue("reference_time").jsonPrimitive.content)
            // hourly models get a longer look ahead, or the map would have only two or three frames
            // and a step further back than the graph's past, so the curve reaches its left edge
            val ahead = if (step < 60) SHOWN_AHEAD_MIN + step else 240
            val valid = meta.getValue("valid_times").jsonArray.map { Instant.parse(it.jsonPrimitive.content.replace("Z", ":00Z")) }
                .filter { val mid = it.minusSeconds(step * 30L); mid.isAfter(now.minusSeconds((SHOWN_PAST_MIN + step) * 60L)) && !mid.isAfter(now.plusSeconds(ahead * 60L)) }
            if (valid.size < 2) continue
            return grid(model, run, valid, step, lat, lon, listOf(west, south, east, north), now) ?: continue
        }
        throw OutlookException("no Open-Meteo map covers home")
    }

    /**
     * The box around home from each step's file; null if home is outside the model's data (NaN). The files in
     * parallel, two requests each on one connection: the tail (metadata and chunk index), then one span over the
     * box's chunk rows (a few times the bytes of a read per row, but a phone's cost is the round trips).
     */
    private fun grid(model: String, run: Instant, valid: List<Instant>, step: Int, lat: Double, lon: Double, bounds: List<Double>, now: Instant): RainGrid? {
        val (west, south, east, north) = bounds
        val (boxWest, boxSouth, boxEast, boxNorth) = boxAround(lat, lon)
        val windows = inParallel(valid.size, valid.map { time -> {
            val url = "$BASE/$model/${RUN.format(run)}/${FILE.format(time)}.om"
            val (range, tail) = ranged(url, "-$TAIL")
            val size = range?.substringAfterLast('/')?.toLongOrNull() ?: throw IOException("Open-Meteo map: no Content-Range")
            val om = OmFile(size, tail) { offset, length -> ranged(url, "$offset-${offset + length - 1}").second }
            val a = om.arrays()["precipitation"] ?: throw OmException("no precipitation in $url")
            val dy = (north - south) / (a.rows - 1); val dx = (east - west) / (a.cols - 1)
            val row0 = ((boxSouth - south) / dy).toInt().coerceIn(0, a.rows - 2)
            val row1 = ceil((boxNorth - south) / dy).toInt().coerceIn(row0 + 2, a.rows)
            val col0 = ((boxWest - west) / dx).toInt().coerceIn(0, a.cols - 2)
            val col1 = ceil((boxEast - west) / dx).toInt().coerceIn(col0 + 2, a.cols)
            val w = om.Window(a, row0, row1, col0, col1)
            val (first, length) = w.span
            w to w.decode(ranged(url, "$first-${first + length - 1}").second)
        } })
        val values = windows.map { it.second }
        val w = windows[0].first
        val dy = (north - south) / (w.a.rows - 1); val dx = (east - west) / (w.a.cols - 1)
        val rows = w.row1 - w.row0; val cols = w.col1 - w.col0
        if (values[0][(((lat - south) / dy).roundToInt() - w.row0) * cols + ((lon - west) / dx).roundToInt() - w.col0].isNaN()) return null
        val perHour = 60f / step
        // the files run south to north; the map's grids north to south
        val fields = values.map { v -> FloatArray(rows * cols) { val x = v[(rows - 1 - it / cols) * cols + it % cols]; if (x.isNaN()) 0f else x * perHour } }
        return RainGrid(listOf(west + w.col0 * dx, south + w.row0 * dy, west + (w.col1 - 1) * dx, south + (w.row1 - 1) * dy), rows, cols,
            valid.map { it.minusSeconds(step * 30L) }, fields, now)
    }

    /** Enough for AROME's metadata and chunk indexes (44 kB); a file that needs more reads the rest separately. */
    private const val TAIL = 49152

    /** The bytes of [range] ("first-last", or "-n" for the last n) and the answer's Content-Range. */
    private fun ranged(url: String, range: String): Pair<String?, ByteArray> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 30_000
        connection.setRequestProperty("Range", "bytes=$range")
        if (connection.responseCode != 206) throw IOException("Open-Meteo map HTTP ${connection.responseCode}")
        return connection.getHeaderField("Content-Range") to connection.inputStream.use { it.readBytes() }
    }
}

package nowcast.engine.radar

import kotlinx.serialization.json.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.*

/**
 * The keyless radar: DWD's RV composite (radar every 5 min, 1 km) and its two-hour RADVOR nowcast, through
 * Bright Sky's free /radar endpoint. Germany and its neighbours only: Benelux, the edges of France, Denmark,
 * Poland, Czechia, Switzerland and Austria. A ~400 km box around the place, clipped at the composite's edge.
 */
object BrightSky {
    const val SOURCE = "dwd"
    /** Around the composite (west, south, east, north), a little generous: its corners decide, by a 404, inside this. */
    private val BOUNDS = listOf(1.0, 45.5, 19.0, 56.2)
    /** 0.01 mm per 5 minutes, as mm/h. */
    private const val TO_MM_H = 0.01 * 12

    fun url(lat: Double, lon: Double, now: Instant): String {
        val minute = now.truncatedTo(ChronoUnit.MINUTES)
        return "https://api.brightsky.dev/radar?lat=%.4f&lon=%.4f&format=plain".format(Locale.ROOT, lat, lon) +
            "&date=${minute.minusSeconds(35 * 60L)}&last_date=${minute.plusSeconds(130 * 60L)}"
    }

    /** null when the place is outside the composite. */
    fun fetch(lat: Double, lon: Double, now: Instant = Instant.now()): RadarClip? {
        if (!covers(BOUNDS, lat, lon)) return null
        val (code, body) = get(url(lat, lon, now))         // gzip: the plain format is refused without it
        if (code in 400..499 && "outside" in body) return null      // 404 "lat/lon lies outside the radar data range"
        if (code !in 200..299) throw IOException("Bright Sky HTTP $code")
        return parse(body)
    }

    fun parse(json: String): RadarClip {
        val root = Json.parseToJsonElement(json).jsonObject
        val frames = root.getValue("radar").jsonArray.map { it.jsonObject }
        if (frames.size < 2) throw RadarException("Bright Sky sent ${frames.size} radar frames")
        fun instant(text: String) = OffsetDateTime.parse(text).toInstant()
        val times = frames.map { instant(it.getValue("timestamp").jsonPrimitive.content) }
        // a radar frame's source is its own time; a forecast's, the radar frame it was run from
        val sources = frames.map { instant(it.getValue("source").jsonPrimitive.content.substringAfterLast("::")) }
        val newest = times.indices.last { times[it] == sources[it] }
        val grids = frames.map { it.getValue("precipitation_5").jsonArray }
        val rows = grids[0].size; val cols = grids[0][0].jsonArray.size
        val fields = grids.map { grid ->
            FloatArray(rows * cols).also { out ->
                grid.forEachIndexed { row, line -> line.jsonArray.forEachIndexed { col, v -> out[row * cols + col] = (v.jsonPrimitive.int * TO_MM_H).toFloat() } }
            }
        }
        val corners = root.getValue("geometry").jsonObject.getValue("coordinates").jsonArray.map { corner ->
            corner.jsonArray.let { it[1].jsonPrimitive.double to it[0].jsonPrimitive.double } }         // (lat, lon)
        val projected = corners.map { (lat, lon) -> project(lat, lon) }
        val byLon = corners.sortedBy { it.second }; val byLat = corners.sortedBy { it.first }
        // the lat/lon rectangle inside the clip: the map shows no dry margin where there is no radar
        val bbox = listOf(byLon[1].second, byLat[1].first, byLon[2].second, byLat[2].first)
        val west = projected.minOf { it.first }; val north = projected.maxOf { it.second }
        return RadarClip(SOURCE, rows, cols, bbox, times, fields, times[newest], newest) { lat, lon ->
            val (x, y) = project(lat, lon)
            north - y - 0.5 to x - west - 0.5
        }
    }

    /** DWD's RADOLAN grid: polar stereographic on WGS84, true at 60° N, centred on 10° E, in km. */
    fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val a = 6378.137; val e = 0.0818191908426
        fun t(phi: Double) = tan(PI / 4 - phi / 2) / ((1 - e * sin(phi)) / (1 + e * sin(phi))).pow(e / 2)
        val trueAt = Math.toRadians(60.0)
        val rho = a * cos(trueAt) / sqrt(1 - e * e * sin(trueAt).pow(2)) * t(Math.toRadians(lat)) / t(trueAt)
        val lambda = Math.toRadians(lon - 10.0)
        return rho * sin(lambda) to -rho * cos(lambda)
    }
}

/**
 * Rain rates (mm/h) on a clip of a keyless source's grid ([source]: [BrightSky.SOURCE], [RmiInca.SOURCE], ...).
 * [bbox] (west, south, east, north) is the lat/lon rectangle inside it; [cell] gives a place's (row, col) in
 * [fields], pixel centres on whole numbers. [issued] is frame [newest], the last observed or analysed one.
 */
class RadarClip(val source: String, val rows: Int, val cols: Int, val bbox: List<Double>, val times: List<Instant>,
                val fields: List<FloatArray>, val issued: Instant, val newest: Int, val cell: (Double, Double) -> Pair<Double, Double>)

/** GET as text, gzip accepted; the status and the body, an error's too. */
internal fun get(url: String, userAgent: String = "rain-nowcast-android"): Pair<Int, String> = getBytes(url, userAgent).let { (code, bytes) -> code to bytes.decodeToString() }

internal fun getBytes(url: String, userAgent: String = "rain-nowcast-android"): Pair<Int, ByteArray> {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000; connection.readTimeout = 30_000
    connection.setRequestProperty("User-Agent", userAgent)
    connection.setRequestProperty("Accept-Encoding", "gzip")
    val code = connection.responseCode
    val stream = (if (code in 200..299) connection.inputStream else connection.errorStream) ?: return code to ByteArray(0)
    return code to (if (connection.contentEncoding == "gzip") GZIPInputStream(stream) else stream).use { it.readBytes() }
}

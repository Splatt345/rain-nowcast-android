package nowcast.engine.outlook

import kotlinx.serialization.json.*
import nowcast.engine.forecast.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.cos

/**
 * The no-radar nowcast: Open-Meteo's 15-minute precipitation from every model that covers the place,
 * as a small ensemble. Free without a key (non-commercial use); a key moves it to the customer API.
 * Model output, not observed rain: true 15-minute data from the local models (ICON-D2, AROME, HRRR,
 * JMA MSM…), hourly spread over the quarter hours from the global ones elsewhere.
 */
object OpenMeteoNowcast {
    /** Five or more of these answer anywhere (checked 2026-10-28: Brussels 11, New York 6, Tokyo 6, Nairobi 5). */
    val MODELS = listOf("icon_d2", "meteofrance_arome_france_hd", "knmi_harmonie_arome_europe", "dmi_harmonie_arome_europe",
        "ukmo_uk_deterministic_2km", "ncep_hrrr_conus", "jma_msm", "icon_eu", "ecmwf_ifs025", "gfs_global", "icon_global",
        "gem_seamless", "ukmo_global_deterministic_10km")
    const val SLOT_MIN = 15
    const val PAST_MIN = 60
    const val AHEAD_MIN = 60

    /** Open-Meteo takes the key only as a query parameter, so it travels in the URL (HTTPS); it is never logged here. */
    fun url(lat: Double, lon: Double, apiKey: String?): String =
        (if (apiKey == null) "https://api.open-meteo.com" else "https://customer-api.open-meteo.com") +
            "/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.ROOT, lat, lon) +
            "&minutely_15=precipitation&past_minutely_15=${PAST_MIN / SLOT_MIN + 1}&forecast_minutely_15=${AHEAD_MIN / SLOT_MIN + 1}" +
            "&models=${MODELS.joinToString(",")}&timezone=GMT" + (apiKey?.let { "&apikey=$it" } ?: "")

    fun fetch(lat: Double, lon: Double, apiKey: String?, now: Instant = Instant.now()): Pair<PointForecast, Instant> =
        point(OpenMeteo.parse(OpenMeteo.get(url(lat, lon, apiKey)), "minutely_15"), now)

    /**
     * Minute by minute from [PAST_MIN] before to [AHEAD_MIN] after the current quarter hour, which is the
     * issue time. A value at t is the precipitation of the 15 minutes before t, so a minute takes the slot
     * ending at or after it, times 4 for mm/h.
     */
    fun point(hourly: Hourly, now: Instant): Pair<PointForecast, Instant> {
        val members = hourly.series.values.filter { series -> series.isNotEmpty() && series.all { it.isFinite() } }
        if (members.isEmpty()) throw OutlookException("Open-Meteo has no 15-minute precipitation here")
        val issued = Instant.ofEpochSecond(now.epochSecond - Math.floorMod(now.epochSecond, SLOT_MIN * 60L))
        val minutes = IntArray(PAST_MIN + AHEAD_MIN + 1) { it - PAST_MIN }
        fun slot(minute: Int): Int? {
            val time = issued.plusSeconds(60L * minute)
            return hourly.time.indexOfFirst { !it.isBefore(time) && Duration.between(time, it).toMinutes() < SLOT_MIN }.takeIf { it >= 0 }
        }
        val slots = minutes.map(::slot)
        if (slots.any { it == null }) throw OutlookException("Open-Meteo's 15-minute series does not cover the hour around now")
        val curves = members.map { series -> DoubleArray(minutes.size) { series[slots[it]!!] * 60.0 / SLOT_MIN } }
        fun at(index: Int) = DoubleArray(curves.size) { curves[it][index] }
        val mean = DoubleArray(minutes.size) { at(it).average() }
        val probability = DoubleArray(minutes.size) { index -> curves.count { it[index] >= RAIN_THRESHOLD }.toDouble() / curves.size }
        fun quantile(percent: Double) = DoubleArray(minutes.size) { percentile(at(it), percent) }
        val zero = minutes.indexOfFirst { it >= 0 }
        val age = Duration.between(issued, now).toMinutes().toInt()
        return PointForecast(minutes, mean, probability, quantile(0.0), quantile(10.0), quantile(25.0), quantile(75.0), quantile(90.0), quantile(100.0),
            probability.copyOfRange(zero, probability.size).max(), summary(minutes, mean, probability, age), true) to issued
    }
    /**
     * The no-radar map: [GRID]×[GRID] points over a box [GRID_HALF_DEG]° of latitude each way from home, all
     * in one request (Open-Meteo's multi-location form), best-match model only. Each point counts against the
     * free 10 000 calls a day, so the caller fetches it at most once a quarter hour, and only for the map.
     */
    fun gridUrl(bbox: List<Double>, apiKey: String?): String {
        val (west, south, east, north) = bbox
        val lats = ArrayList<String>(); val lons = ArrayList<String>()
        for (row in 0 until GRID) for (col in 0 until GRID) {
            lats += "%.3f".format(Locale.ROOT, north + (south - north) * row / (GRID - 1))
            lons += "%.3f".format(Locale.ROOT, west + (east - west) * col / (GRID - 1))
        }
        return (if (apiKey == null) "https://api.open-meteo.com" else "https://customer-api.open-meteo.com") +
            "/v1/forecast?latitude=${lats.joinToString(",")}&longitude=${lons.joinToString(",")}" +
            "&minutely_15=precipitation&past_minutely_15=${PAST_MIN / SLOT_MIN + 1}&forecast_minutely_15=${AHEAD_MIN / SLOT_MIN + 1}" +
            "&timezone=GMT" + (apiKey?.let { "&apikey=$it" } ?: "")
    }

    fun gridBbox(lat: Double, lon: Double): List<Double> {
        val halfLon = GRID_HALF_DEG / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        return listOf(lon - halfLon, lat - GRID_HALF_DEG, lon + halfLon, lat + GRID_HALF_DEG)
    }

    fun fetchGrid(lat: Double, lon: Double, apiKey: String?, now: Instant = Instant.now()): RainGrid {
        val bbox = gridBbox(lat, lon)
        return parseGrid(OpenMeteo.get(gridUrl(bbox, apiKey)), bbox, now)
    }

    /**
     * One field (mm/h, row 0 north) per quarter hour, timed at the middle of the 15 minutes it sums,
     * [PAST_MIN] before to [AHEAD_MIN] after the current quarter hour.
     */
    fun parseGrid(json: String, bbox: List<Double>, now: Instant): RainGrid {
        val points = Json.parseToJsonElement(json).jsonArray
        if (points.size != GRID * GRID) throw OutlookException("Open-Meteo answered ${points.size} of ${GRID * GRID} map points")
        val blocks = points.map { it.jsonObject["minutely_15"]?.jsonObject ?: throw OutlookException("Open-Meteo answered without minutely_15 data") }
        val issued = Instant.ofEpochSecond(now.epochSecond - Math.floorMod(now.epochSecond, SLOT_MIN * 60L))
        val ends = blocks[0].getValue("time").jsonArray.map { LocalDateTime.parse(it.jsonPrimitive.content).toInstant(ZoneOffset.UTC) }
        val values = blocks.map { block -> block.getValue("precipitation").jsonArray.map { it.jsonPrimitive.doubleOrNull ?: 0.0 } }
        val shown = ends.indices.filter { val minute = Duration.between(issued, ends[it]).toMinutes(); minute > -PAST_MIN && minute <= AHEAD_MIN }
        if (shown.size < 2) throw OutlookException("Open-Meteo's map does not cover the time around now")
        return RainGrid(bbox, GRID, GRID, shown.map { ends[it].minusSeconds(SLOT_MIN * 30L) },
            shown.map { slot -> FloatArray(GRID * GRID) { point -> (values[point][slot] * 60.0 / SLOT_MIN).toFloat() } }, issued)
    }

    const val GRID = 16
    const val GRID_HALF_DEG = 2.0
}

/** Rain rates (mm/h) on a regular lat/lon grid over [bbox] (west, south, east, north), row 0 north. */
class RainGrid(val bbox: List<Double>, val rows: Int, val cols: Int, val times: List<Instant>, val fields: List<FloatArray>, val issued: Instant)

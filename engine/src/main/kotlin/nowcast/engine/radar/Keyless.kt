package nowcast.engine.radar

import io.jhdf.HdfFile
import kotlinx.serialization.json.*
import nowcast.engine.forecast.*
import nowcast.engine.math.clampedCell
import nowcast.engine.outlook.RainGrid
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * The keyless nowcasts besides [BrightSky]: national services that publish their own radar-based forecast.
 * Each covers a country and its surroundings; [inside] with the country outlines below says whose home it is.
 */

/**
 * The best keyless radar map for a place: its own country's service (RMI, GeoSphere), else DWD's composite, else
 * whichever of the others reaches it; null where none does. A source that fails is skipped.
 */
fun keylessRadar(lat: Double, lon: Double): RadarClip? {
    val tries = buildList<() -> RadarClip?> {
        if (inside(lat, lon, RmiInca.HOME)) add { RmiInca.fetch(lat, lon) }
        if (inside(lat, lon, GeoSphereNowcast.HOME)) add { GeoSphereNowcast.fetch(lat, lon) }
        // ponytail: a failed home service is asked once more at the end; cheap, and rare
        add { BrightSky.fetch(lat, lon) }; add { GeoSphereNowcast.fetch(lat, lon) }; add { RmiInca.fetch(lat, lon) }
    }
    return tries.firstNotNullOfOrNull { runCatching(it).getOrNull() }
}

/** Whether (lat, lon) is inside [ring], a closed outline of (lon, lat) points. */
fun inside(lat: Double, lon: Double, ring: List<Pair<Double, Double>>): Boolean {
    var crossings = false
    for (i in ring.indices) {
        val (x1, y1) = ring[i]; val (x2, y2) = ring[(i + 1) % ring.size]
        if ((y1 > lat) != (y2 > lat) && lon < x1 + (lat - y1) / (y2 - y1) * (x2 - x1)) crossings = !crossings
    }
    return crossings
}

/** How far the keyless maps reach from home, in degrees of latitude. */
const val KEYLESS_HALF_DEG = 1.2
private val WORLD = listOf(-180.0, -90.0, 180.0, 90.0)

/** A box of [halfLat] degrees each way from (lat, lon), roughly square on the ground, clipped to [bounds] (west, south, east, north). */
fun boxAround(lat: Double, lon: Double, halfLat: Double = KEYLESS_HALF_DEG, bounds: List<Double> = WORLD): List<Double> {
    val halfLon = halfLat / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
    return listOf(max(lon - halfLon, bounds[0]), max(lat - halfLat, bounds[1]), min(lon + halfLon, bounds[2]), min(lat + halfLat, bounds[3]))
}

fun covers(bounds: List<Double>, lat: Double, lon: Double) = lon in bounds[0]..bounds[2] && lat in bounds[1]..bounds[3]

/** [tasks] on [threads] threads, their results in order; the first failure is thrown. */
internal fun <T> inParallel(threads: Int, tasks: List<() -> T>): List<T> {
    val pool = Executors.newFixedThreadPool(threads)
    try {
        return pool.invokeAll(tasks.map { task -> Callable { task() } }).map { it.get() }
    } finally {
        pool.shutdown()
    }
}

/**
 * RMI's INCA-BE nowcast (radar, stations and model blended): rain rate in mm/h on a ~1.2 km lat/lon grid every
 * 10 minutes, about 4 hours ahead, over Benelux and its edges. From RMI's open GeoServer (WCS), one request per step.
 */
object RmiInca {
    const val SOURCE = "rmi"
    /** The coverage (west, south, east, north), from its DescribeCoverage. */
    val BOUNDS = listOf(0.0251, 47.8864, 9.0102, 53.2703)
    /** Belgium and Luxembourg, coarsely: where RMI is the home service. */
    // ponytail: ~15-point outline, a few km off along the borders; a real boundary file if that ever matters
    val HOME = listOf(2.54 to 51.09, 3.37 to 51.37, 4.24 to 51.37, 5.0 to 51.49, 5.85 to 51.15, 5.64 to 50.75, 6.4 to 50.32,
        6.13 to 50.13, 6.5 to 49.8, 6.37 to 49.47, 5.82 to 49.5, 5.47 to 49.5, 4.86 to 49.79, 4.79 to 50.15, 4.15 to 49.98, 3.15 to 50.79)
    private const val OWS = "https://opendata.meteo.be/service/ows?service=WCS&version=2.0.1&coverageId=inca__Precipitation"
    private const val AHEAD_MIN = 120L
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** null outside the coverage. The first step is the analysis, the issue time. */
    fun fetch(lat: Double, lon: Double): RadarClip? {
        if (!covers(BOUNDS, lat, lon)) return null
        val (code, described) = get("$OWS&request=DescribeCoverage")
        if (code !in 200..299) throw IOException("RMI HTTP $code")
        val first = Instant.parse(Regex("<gml:beginPosition>(.*?)</gml:beginPosition>").find(described)!!.groupValues[1])
        val last = Instant.parse(Regex("<gml:endPosition>(.*?)</gml:endPosition>").find(described)!!.groupValues[1])
        val times = generateSequence(first) { it.plusSeconds(600) }.takeWhile { it <= minOf(last, first.plusSeconds(60 * AHEAD_MIN)) }.toList()
        val box = boxAround(lat, lon, bounds = BOUNDS)
        // one request per step, ~0.5 s each
        val grids = inParallel(4, times.map { time -> {
            val (status, text) = get("$OWS&request=GetCoverage&format=text%2Fplain" +
                "&subset=Lat%28${box[1]}%2C${box[3]}%29&subset=Long%28${box[0]}%2C${box[2]}%29" +
                "&subset=time%28%22${TIME.format(time).replace(":", "%3A")}%22%29")
            if (status !in 200..299) throw IOException("RMI HTTP $status")
            parse(text)
        } })
        return clip(grids[0].bounds, grids[0].rows, grids[0].cols, times, grids.map { it.fields })
    }

    class Grid(val bounds: List<Double>, val rows: Int, val cols: Int, val fields: FloatArray)
    private val SPACES = Regex("\\s+")

    /** GeoServer's text/plain coverage: a header with the outer bounds, then "Band 0:" and the rows, north first; below 0 is dry. */
    fun parse(text: String): Grid {
        val lines = text.lines()
        val bounds = Regex("""-?\d+(\.\d+)?(E-?\d+)?""").findAll(lines.first { it.startsWith("Grid bounds") }).map { it.value.toDouble() }.toList()
        val band = lines.indexOfFirst { it.startsWith("Band 0") }
        if (bounds.size != 4 || band < 0) throw RadarException("RMI sent no grid")
        val rows = lines.drop(band + 1).filter { it.isNotBlank() }.map { line -> line.trim().split(SPACES).map { max(0.0, it.toDouble()).toFloat() } }
        val cols = rows[0].size
        return Grid(bounds, rows.size, cols, FloatArray(rows.size * cols) { rows[it / cols][it % cols] })
    }

    fun clip(bounds: List<Double>, rows: Int, cols: Int, times: List<Instant>, fields: List<FloatArray>): RadarClip {
        val (west, south, east, north) = bounds
        return RadarClip(SOURCE, rows, cols, bounds, times, fields, times[0], 0) { lat, lon ->
            (north - lat) / (north - south) * rows - 0.5 to (lon - west) / (east - west) * cols - 0.5
        }
    }
}

/**
 * GeoSphere Austria's INCA nowcast: 15-minute rain sums on a 1 km grid in Austria's Lambert projection, 3 hours
 * ahead from the last quarter hour, over Austria and the Alps around it. One NetCDF request for a box.
 */
object GeoSphereNowcast {
    const val SOURCE = "geosphere"
    /** The grid's extent (west, south, east, north), from the dataset's metadata. */
    val BOUNDS = listOf(8.0981, 45.5029, 17.7423, 49.4782)
    /** Austria, coarsely: where GeoSphere is the home service. */
    // ponytail: ~22-point outline, a few km off along the borders
    val HOME = listOf(9.53 to 47.27, 9.6 to 47.06, 10.47 to 46.87, 12.13 to 47.0, 12.24 to 46.72, 13.71 to 46.52, 14.88 to 46.61,
        16.0 to 46.68, 16.11 to 46.87, 16.53 to 47.5, 16.9 to 47.72, 17.16 to 48.01, 16.95 to 48.6, 16.0 to 48.75, 15.0 to 49.0,
        14.0 to 48.6, 13.6 to 48.57, 13.0 to 47.85, 12.9 to 47.53, 12.21 to 47.6, 11.1 to 47.4, 10.2 to 47.27)
    private const val URL = "https://dataset.api.hub.geosphere.at/v1/grid/forecast/nowcast-v1-15min-1km?parameters=rr&output_format=netcdf"

    /** null outside the grid. */
    fun fetch(lat: Double, lon: Double): RadarClip? {
        if (!covers(BOUNDS, lat, lon)) return null
        val box = boxAround(lat, lon, bounds = BOUNDS)
        val (code, bytes) = getBytes(URL + "&bbox=%.4f,%.4f,%.4f,%.4f".format(Locale.ROOT, box[1], box[0], box[3], box[2]))
        if (code !in 200..299) throw IOException("GeoSphere HTTP $code")
        return parse(bytes, box)
    }

    /**
     * rr: int16 hundredths of a mm per quarter hour ending at each time, -999 missing; x, y: cell centres (m), y
     * northward, so row 0 is south. Each step is timed at the middle of its quarter hour, as Open-Meteo's are.
     */
    fun parse(netcdf: ByteArray, bbox: List<Double>): RadarClip = HdfFile.fromBytes(netcdf).use { hdf ->
        @Suppress("UNCHECKED_CAST")
        val rr = hdf.getDatasetByPath("rr").data as Array<Array<ShortArray>>
        val x = hdf.getDatasetByPath("x").data as IntArray; val y = hdf.getDatasetByPath("y").data as IntArray
        val minutes = hdf.getDatasetByPath("time").data as LongArray
        val lead = hdf.getDatasetByPath("leadtime").data as DoubleArray
        val start = LocalDateTime.parse(hdf.getDatasetByPath("time").getAttribute("units")!!.data.toString().removePrefix("minutes since ").replace(' ', 'T'))
            .toInstant(ZoneOffset.UTC)
        val ends = minutes.map { start.plusSeconds(60 * it) }
        val issued = ends[0].minusSeconds((lead[0] * 3600).roundToLong())
        val rows = y.size; val cols = x.size
        val fields = rr.map { step ->
            FloatArray(rows * cols) { val v = step[it / cols][it % cols]; if (v < 0) 0f else (v * 0.01 * 4).toFloat() }
        }
        val dy = (y[1] - y[0]).toDouble(); val dx = (x[1] - x[0]).toDouble()
        RadarClip(SOURCE, rows, cols, bbox, ends.map { it.minusSeconds(450) }, fields, issued, 0) { lat, lon ->
            val (px, py) = project(lat, lon)
            (py - y[0]) / dy to (px - x[0]) / dx
        }
    }

    /** EPSG:31287, MGI / Austria Lambert: conformal conic on Bessel 1841, metres. The datum shift (~100 m) is left out. */
    fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val a = 6377397.155; val f = 1 / 299.1528128; val e = sqrt(2 * f - f * f)
        fun m(phi: Double) = cos(phi) / sqrt(1 - (e * sin(phi)).pow(2))
        fun t(phi: Double) = tan(PI / 4 - phi / 2) / ((1 - e * sin(phi)) / (1 + e * sin(phi))).pow(e / 2)
        val p1 = Math.toRadians(49.0); val p2 = Math.toRadians(46.0); val p0 = Math.toRadians(47.5)
        val n = (ln(m(p1)) - ln(m(p2))) / (ln(t(p1)) - ln(t(p2)))
        val big = m(p1) / (n * t(p1).pow(n))
        val r0 = a * big * t(p0).pow(n); val r = a * big * t(Math.toRadians(lat)).pow(n)
        val theta = n * Math.toRadians(lon - (13 + 1.0 / 3))
        return 400000 + r * sin(theta) to 400000 + r0 - r * cos(theta)
    }
}

/**
 * MET Norway's radar nowcast for one place: rain rate every 5 minutes, two hours ahead, in the Nordic area.
 * A single radar run, so no spread: every quantile is the value. For the Now screen; there is no grid for a map.
 */
object MetNorwayNowcast {
    const val NAME = "MET Norway"
    /** MET asks for an identifying User-Agent. */
    private const val AGENT = "rain-nowcast-android github.com/Splatt345/rain-nowcast-android"

    /** null outside the Nordic area or where the radar does not reach. */
    fun fetch(lat: Double, lon: Double, now: Instant = Instant.now()): Pair<PointForecast, Instant>? {
        if (lat < 53.5 || lon < -1.0 || lon > 33.0) return null      // the rest answers 422 anyway
        val (code, body) = get("https://api.met.no/weatherapi/nowcast/2.0/complete?lat=%.4f&lon=%.4f".format(Locale.ROOT, lat, lon), AGENT)
        if (code == 422) return null
        if (code !in 200..299) throw IOException("MET Norway HTTP $code")
        return parse(body, now)
    }

    /** Minute by minute from the first step, the issue time, linear between the 5-minute steps. */
    fun parse(json: String, now: Instant): Pair<PointForecast, Instant>? {
        val properties = Json.parseToJsonElement(json).jsonObject.getValue("properties").jsonObject
        if (properties["meta"]?.jsonObject?.get("radar_coverage")?.jsonPrimitive?.content != "ok") return null
        val steps = properties.getValue("timeseries").jsonArray.mapNotNull { step ->
            val rate = step.jsonObject["data"]?.jsonObject?.get("instant")?.jsonObject?.get("details")?.jsonObject?.get("precipitation_rate")
                ?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Instant.parse(step.jsonObject.getValue("time").jsonPrimitive.content) to rate
        }
        if (steps.size < 2) return null
        val issued = steps[0].first
        return singleRun(steps.map { Duration.between(issued, it.first).toMinutes().toInt() }, steps.map { it.second }, issued, now)
    }
}

/** The rain this clip shows at (lat, lon), as the Now screen's curve; null outside it. */
fun RadarClip.point(lat: Double, lon: Double, now: Instant = Instant.now()): Pair<PointForecast, Instant>? =
    cell(lat, lon).let { (row, col) -> mapPoint(times, fields, rows, cols, row, col, times[newest], now) }

/** The rain this model grid shows at (lat, lon), as the Now screen's curve from now (a forecast does not age); null outside it. */
fun RainGrid.point(lat: Double, lon: Double, now: Instant = Instant.now()): Pair<PointForecast, Instant>? {
    val (west, south, east, north) = bbox
    val issued = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
    return mapPoint(times, fields, rows, cols, (north - lat) / (north - south) * (rows - 1), (lon - west) / (east - west) * (cols - 1), issued, now)
}

/** A map's frames at one place, as [singleRun]. */
private fun mapPoint(times: List<Instant>, fields: List<FloatArray>, rows: Int, cols: Int, row: Double, col: Double,
                     issued: Instant, now: Instant): Pair<PointForecast, Instant>? {
    if (row < -0.5 || col < -0.5 || row > rows - 0.5 || col > cols - 0.5) return null
    val series = fields.map { f ->
        var value = 0.0
        clampedCell(rows, cols, row, col) { i00, i01, i10, i11, rowFrac, colFrac ->
            value = (f[i00] * (1 - colFrac) + f[i01] * colFrac) * (1 - rowFrac) + (f[i10] * (1 - colFrac) + f[i11] * colFrac) * rowFrac
        }
        value
    }
    return singleRun(times.map { Duration.between(issued, it).toMinutes().toInt() }, series, issued, now)
}

/**
 * [series] at minutes [at] from [issued] (minute 0), minute by minute and linear between them, as the map
 * cross-fades its frames. One run, so no spread: every quantile is the value.
 */
private fun singleRun(at: List<Int>, series: List<Double>, issued: Instant, now: Instant): Pair<PointForecast, Instant> {
    val minutes = (at.first()..at.last()).toList().toIntArray()
    val values = DoubleArray(minutes.size) { i ->
        val minute = minutes[i]
        val k = at.indexOfLast { it <= minute }.coerceIn(0, at.size - 2)
        val span = (at[k + 1] - at[k]).toDouble()
        series[k] + (series[k + 1] - series[k]) * ((minute - at[k]) / span).coerceIn(0.0, 1.0)
    }
    val probability = DoubleArray(values.size) { if (values[it] >= RAIN_THRESHOLD) 1.0 else 0.0 }
    val zero = minutes.indexOfFirst { it >= 0 }.coerceAtLeast(0)
    val age = Duration.between(issued, now).toMinutes().toInt()
    return PointForecast(minutes, values, probability, values, values, values, values, values, values,
        probability.copyOfRange(zero, probability.size).max(), summary(minutes, values, probability, age), true) to issued
}

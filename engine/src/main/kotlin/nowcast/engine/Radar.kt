package nowcast.engine

import io.jhdf.HdfFile
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

class RadarException(msg: String) : Exception(msg)

class GridGeo(val laea: Laea, val x1: Double, val y2: Double, val px: Double, val py: Double, val h: Int, val w: Int) {
    fun rowCol(lat: Double, lon: Double): Pair<Int, Int> {
        val (x, y) = laea.forward(lat, lon)
        return ((y2 - y) / py).roundToInt() to ((x - x1) / px).roundToInt()
    }
}

class Frame(val time: Instant, val rain: Grid, val geo: GridGeo)

object Odim {
    const val FILL_DBZ = 128.0
    const val MAX_DBZ = 55.0

    /** clean_dbz + Marshall-Palmer (a=200, b=1.6). nodata/fill -> NaN, undetect -> 0. */
    fun dbzToRain(raw: Double, gain: Double, offset: Double, nodata: Double, undetect: Double): Double {
        if (raw == nodata) return Double.NaN
        if (raw == undetect) return 0.0
        val dbz = raw * gain + offset
        if (dbz >= FILL_DBZ) return Double.NaN
        return (10.0.pow(minOf(dbz, MAX_DBZ) / 10.0) / 200.0).pow(1 / 1.6)
    }

    fun parseTime(name: String): Instant? =
        Regex("""(\d{8}T\d{4})""").find(name)?.groupValues?.get(1)?.let {
            java.time.LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")).toInstant(ZoneOffset.UTC)
        }

    private fun attr(f: HdfFile, path: String, key: String): Any = f.getByPath(path).getAttribute(key)!!.data
    private fun num(a: Any): Double = when (a) {
        is Number -> a.toDouble(); is DoubleArray -> a[0]; is LongArray -> a[0].toDouble()
        is Array<*> -> (a[0] as Number).toDouble(); else -> error("attr $a")
    }

    /** Crop to region.bbox exactly as live.crop_to_belgium: project the four corners. */
    fun readCrop(file: File, region: Region): Frame = HdfFile(file.toPath()).use { f ->
        val projdef = attr(f, "where", "projdef").toString()
        val laea = Laea.parse(projdef)
        val nx = num(attr(f, "where", "xsize")).toInt(); val ny = num(attr(f, "where", "ysize")).toInt()
        val xs = num(attr(f, "where", "xscale")); val ys = num(attr(f, "where", "yscale"))
        val (x1, _) = laea.forward(num(attr(f, "where", "LL_lat")), num(attr(f, "where", "LL_lon")))
        val (_, y2) = laea.forward(num(attr(f, "where", "UR_lat")), num(attr(f, "where", "UR_lon")))
        val (lo0, la0, lo1, la1) = region.bbox
        val corners = listOf(la0 to lo0, la0 to lo1, la1 to lo1, la1 to lo0).map { laea.forward(it.first, it.second) }
        val c0 = floor((corners.minOf { it.first } - x1) / xs).toInt().coerceIn(0, nx)
        val c1 = floor((corners.maxOf { it.first } - x1) / xs).toInt().coerceIn(0, nx)
        val r0 = floor((y2 - corners.maxOf { it.second }) / ys).toInt().coerceIn(0, ny)
        val r1 = floor((y2 - corners.minOf { it.second }) / ys).toInt().coerceIn(0, ny)
        val ds = f.getDatasetByPath("dataset1/data1/data")
        val what = "dataset1/data1/what"
        val gain = num(attr(f, what, "gain")); val offset = num(attr(f, what, "offset"))
        val nodata = num(attr(f, what, "nodata")); val undetect = num(attr(f, what, "undetect"))
        @Suppress("UNCHECKED_CAST")
        val slab = ds.getData(longArrayOf(r0.toLong(), c0.toLong()), intArrayOf(r1 - r0, c1 - c0)) as Array<DoubleArray>
        val h = r1 - r0; val w = c1 - c0
        val g = Grid(h, w, DoubleArray(h * w) { dbzToRain(slab[it / w][it % w], gain, offset, nodata, undetect) })
        val date = attr(f, "what", "date").toString(); val time = attr(f, "what", "time").toString()
        Frame(parseTime("${date}T${time.take(4)}") ?: parseTime(file.name)!!, g,
            GridGeo(laea, x1 + c0 * xs, y2 - r0 * ys, xs, ys, h, w))
    }
}

/** (time, url) pairs anywhere in the CoverageJSON-ish response, oldest first. Twin of live._extract_download_urls. */
fun extractUrls(json: String): List<String> {
    val found = ArrayList<Pair<String, String>>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                val u = (e["href"] ?: e["url"] ?: e["download"])?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (u != null && u.lowercase().substringBefore('?').let { it.endsWith(".h5") || it.endsWith(".hdf") || it.endsWith(".hdf5") })
                    found += ((e["datetime"] ?: e["time"])?.jsonPrimitive?.contentOrNull ?: "") to u
                e.values.forEach(::walk)
            }
            is JsonArray -> e.forEach(::walk)
            else -> {}
        }
    }
    walk(Json.parseToJsonElement(json))
    return found.sortedBy { Odim.parseTime(it.second) ?: Instant.EPOCH }.map { it.second }.distinct()
}

class RadarSource(private val apiKey: String?, private val cacheDir: File) {
    private val base = "https://api.meteogate.eu/eu-eumetnet-weather-radar/collections/observations/locations/0-20010-0-OPERA"

    fun listUrls(lookbackMinutes: Int = 100): List<String> {
        val now = Instant.now(); val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC)
        val q = mapOf("datetime" to "${fmt.format(now.minusSeconds(lookbackMinutes * 60L))}/${fmt.format(now)}",
            "standard_name" to "DBZH", "format" to "ODIM", "method" to "comp", "f" to "CoverageJSON")
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val c = URI("$base?$q").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 30_000; c.readTimeout = 30_000
        // "apikey" is the only header ORD accepts (live._session); never put the key in the URL.
        apiKey?.let { c.setRequestProperty("apikey", it) }
        when (c.responseCode) {
            429 -> throw RadarException("Radar API rate limit hit (resets in ${c.getHeaderField("X-RateLimit-Reset") ?: "?"} s)")
            401, 403 -> throw RadarException("Radar API rejected the key")
            !in 200..299 -> throw RadarException("Radar API HTTP ${c.responseCode}")
        }
        val urls = extractUrls(c.inputStream.bufferedReader().readText())
        if (urls.isEmpty()) throw RadarException("No radar frames in the last $lookbackMinutes min")
        return urls
    }

    /** Bare request, no headers: the URL is a pre-signed S3 link that rejects ours (live.download_composite). */
    fun download(url: String): File {
        cacheDir.mkdirs()
        val out = File(cacheDir, url.substringBefore('?').substringAfterLast('/'))
        if (!out.exists()) {
            val tmp = File(cacheDir, out.name + ".part")
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 30_000; c.readTimeout = 60_000
            if (c.responseCode !in 200..299) throw RadarException("Download HTTP ${c.responseCode}")
            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(out)
        }
        return out
    }

    fun latestFrames(region: Region, n: Int = 12): List<Frame> =
        listUrls().takeLast(n).map { Odim.readCrop(download(it), region) }

    fun prune(maxAgeMinutes: Int = 180) {
        val cut = System.currentTimeMillis() - maxAgeMinutes * 60_000L
        cacheDir.listFiles()?.filter { it.lastModified() < cut }?.forEach { it.delete() }
    }
}

package nowcast.engine.radar

import io.jhdf.HdfFile
import kotlinx.serialization.json.*
import nowcast.engine.math.*
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

class GridGeo(val laea: Laea, val left: Double, val top: Double, val pixelWidth: Double, val pixelHeight: Double, val rows: Int, val cols: Int) {
    fun rowCol(lat: Double, lon: Double): Pair<Int, Int> {
        val (x, y) = laea.forward(lat, lon)
        return ((top - y) / pixelHeight).roundToInt() to ((x - left) / pixelWidth).roundToInt()
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

    private fun attr(hdf: HdfFile, path: String, key: String): Any = hdf.getByPath(path).getAttribute(key)!!.data
    private fun num(value: Any): Double = when (value) {
        is Number -> value.toDouble(); is DoubleArray -> value[0]; is LongArray -> value[0].toDouble()
        is Array<*> -> (value[0] as Number).toDouble(); else -> error("attr $value")
    }

    fun readCrop(file: File, region: Region): Frame = readCrop(file, region.bbox)
    fun readCrop(file: File, bbox: List<Double>): Frame = HdfFile(file.toPath()).use { crop(it, bbox, file.name) }

    /** Crop to bbox (west, south, east, north) exactly as live.crop_to_belgium: project the four corners. */
    fun crop(hdf: HdfFile, bbox: List<Double>, name: String): Frame {
        val projdef = attr(hdf, "where", "projdef").toString()
        val laea = Laea.parse(projdef)
        val xSize = num(attr(hdf, "where", "xsize")).toInt(); val ySize = num(attr(hdf, "where", "ysize")).toInt()
        val xScale = num(attr(hdf, "where", "xscale")); val yScale = num(attr(hdf, "where", "yscale"))
        val (left, _) = laea.forward(num(attr(hdf, "where", "LL_lat")), num(attr(hdf, "where", "LL_lon")))
        val (_, top) = laea.forward(num(attr(hdf, "where", "UR_lat")), num(attr(hdf, "where", "UR_lon")))
        val (west, south, east, north) = bbox
        val corners = listOf(south to west, south to east, north to east, north to west).map { laea.forward(it.first, it.second) }
        val firstCol = floor((corners.minOf { it.first } - left) / xScale).toInt().coerceIn(0, xSize)
        val lastCol = floor((corners.maxOf { it.first } - left) / xScale).toInt().coerceIn(0, xSize)
        val firstRow = floor((top - corners.maxOf { it.second }) / yScale).toInt().coerceIn(0, ySize)
        val lastRow = floor((top - corners.minOf { it.second }) / yScale).toInt().coerceIn(0, ySize)
        val dataset = hdf.getDatasetByPath("dataset1/data1/data")
        val what = "dataset1/data1/what"
        val gain = num(attr(hdf, what, "gain")); val offset = num(attr(hdf, what, "offset"))
        val nodata = num(attr(hdf, what, "nodata")); val undetect = num(attr(hdf, what, "undetect"))
        @Suppress("UNCHECKED_CAST")
        val slab = dataset.getData(longArrayOf(firstRow.toLong(), firstCol.toLong()), intArrayOf(lastRow - firstRow, lastCol - firstCol)) as Array<DoubleArray>
        val rows = lastRow - firstRow; val cols = lastCol - firstCol
        val rain = Grid(rows, cols, DoubleArray(rows * cols) { dbzToRain(slab[it / cols][it % cols], gain, offset, nodata, undetect) })
        val date = attr(hdf, "what", "date").toString(); val time = attr(hdf, "what", "time").toString()
        return Frame(parseTime("${date}T${time.take(4)}") ?: parseTime(name)!!, rain,
            GridGeo(laea, left + firstCol * xScale, top - firstRow * yScale, xScale, yScale, rows, cols))
    }
}

/** A lon/lat bbox holding a boxKm square around the point (with 10% margin; crop it exactly in pixels). */
fun pointBbox(lat: Double, lon: Double, boxKm: Double): List<Double> {
    val dLat = 0.55 * boxKm / 111.2; val dLon = dLat / kotlin.math.cos(Math.toRadians(lat))
    return listOf(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
}

/**
 * Read-only view of a remote file through HTTP Range requests, in 16 kB blocks fetched on
 * demand, so jHDF pulls only the metadata and the chunks a crop touches (16 kB blocks: 82 kB
 * for a dry frame in 3 requests; 64 kB blocks took 197 kB). The missing blocks of one read go
 * in a single request. Blocks are kept as files in [dir]: a frame read again
 * in a later cycle, or for a smaller box, costs nothing. Bare requests, as [RadarSource.download].
 */
internal class RangedChannel(private val url: String, private val dir: File, private val block: Int = 1 shl 14) :
    java.nio.channels.SeekableByteChannel {
    var fetched = 0L; private set
    var requests = 0; private set
    private var pos = 0L
    private val blocks = HashMap<Long, ByteArray>()
    private val size: Long

    init {
        dir.mkdirs()
        val sizeFile = File(dir, "size")
        size = sizeFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: fetch(0, 0, -1).also { put(sizeFile, "$it".toByteArray()) }
    }

    private fun put(file: File, bytes: ByteArray) {
        val tmp = File(file.path + ".part"); tmp.writeBytes(bytes); tmp.renameTo(file)
    }

    /** Blocks first..last in one request; returns the file size. A server that ignores
     *  Range and sends the whole file is fine too: every block is then stored. */
    private fun fetch(first: Long, last: Long, known: Long): Long {
        val fromByte = first * block; val toByte = (last + 1) * block - 1
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 60_000
        connection.setRequestProperty("Range", "bytes=$fromByte-$toByte")
        requests++
        val (start, total) = when (connection.responseCode) {
            206 -> fromByte to (connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: throw RadarException("Range reply without a size"))
            200 -> 0L to connection.contentLengthLong
            else -> throw RadarException("Range download HTTP ${connection.responseCode}")
        }
        if (known >= 0 && total != known) throw RadarException("file changed size: $total vs $known")
        val body = connection.inputStream.use { it.readBytes() }
        fetched += body.size
        var offset = 0
        while (offset < body.size) {
            val index = (start + offset) / block
            val length = minOf(block.toLong(), total - index * block).toInt()
            if (offset + length > body.size) break      // short read: leave the tail missing
            val chunk = body.copyOfRange(offset, offset + length)
            blocks[index] = chunk; put(File(dir, "$index"), chunk)
            offset += length
        }
        return total
    }

    private fun blockAt(i: Long): ByteArray? =
        blocks[i] ?: File(dir, "$i").takeIf { it.exists() }?.readBytes()?.also { blocks[i] = it }

    override fun read(dst: java.nio.ByteBuffer): Int {
        val count = minOf(dst.remaining().toLong(), size - pos).toInt()
        if (count <= 0) return -1
        val first = pos / block; val last = (pos + count - 1) / block
        val missing = (first..last).filter { blockAt(it) == null }
        if (missing.isNotEmpty()) fetch(missing.first(), missing.last(), size)
        var left = count
        while (left > 0) {
            val bytes = blockAt(pos / block) ?: throw RadarException("block ${pos / block} missing after fetch")
            val offset = (pos % block).toInt(); val length = minOf(left, bytes.size - offset)
            dst.put(bytes, offset, length); pos += length; left -= length
        }
        return count
    }

    override fun position() = pos
    override fun position(newPosition: Long) = apply { pos = newPosition }
    override fun size() = size
    override fun write(src: java.nio.ByteBuffer): Int = throw java.nio.channels.NonWritableChannelException()
    override fun truncate(newSize: Long) = throw java.nio.channels.NonWritableChannelException()
    override fun isOpen() = true
    override fun close() {}
}

/** (time, url) pairs anywhere in the CoverageJSON-ish response, oldest first. Twin of live._extract_download_urls. */
fun extractUrls(json: String): List<String> {
    val found = ArrayList<Pair<String, String>>()
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val url = (element["href"] ?: element["url"] ?: element["download"])?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (url != null && url.lowercase().substringBefore('?').let { it.endsWith(".h5") || it.endsWith(".hdf") || it.endsWith(".hdf5") })
                    found += ((element["datetime"] ?: element["time"])?.jsonPrimitive?.contentOrNull ?: "") to url
                element.values.forEach(::walk)
            }
            is JsonArray -> element.forEach(::walk)
            else -> {}
        }
    }
    walk(Json.parseToJsonElement(json))
    return found.sortedBy { Odim.parseTime(it.second) ?: Instant.EPOCH }.map { it.second }.distinct()
}

class RadarSource(private val apiKey: String?, private val cacheDir: File) {
    private val base = "https://api.meteogate.eu/eu-eumetnet-weather-radar/collections/observations/locations/0-20010-0-OPERA"

    fun listUrls(lookbackMinutes: Int = 100): List<String> {
        val now = Instant.now(); val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC)
        val query = mapOf("datetime" to "${timeFormat.format(now.minusSeconds(lookbackMinutes * 60L))}/${timeFormat.format(now)}",
            "standard_name" to "DBZH", "format" to "ODIM", "method" to "comp", "f" to "CoverageJSON")
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val connection = URI("$base?$query").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 30_000
        // "apikey" is the only header ORD accepts (live._session); never put the key in the URL.
        apiKey?.let { connection.setRequestProperty("apikey", it) }
        when (connection.responseCode) {
            429 -> throw RadarException("Radar API rate limit hit (resets in ${connection.getHeaderField("X-RateLimit-Reset") ?: "?"} s)")
            401, 403 -> throw RadarException("Radar API rejected the key")
            !in 200..299 -> throw RadarException("Radar API HTTP ${connection.responseCode}")
        }
        val urls = extractUrls(connection.inputStream.bufferedReader().readText())
        if (urls.isEmpty()) throw RadarException("No radar frames in the last $lookbackMinutes min")
        return urls
    }

    /** Bare request, no headers: the URL is a pre-signed S3 link that rejects ours (live.download_composite). */
    fun download(url: String): File {
        cacheDir.mkdirs()
        val out = File(cacheDir, name(url))
        if (!out.exists()) {
            val tmp = File(cacheDir, out.name + ".part")
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000; connection.readTimeout = 60_000
            if (connection.responseCode !in 200..299) throw RadarException("Download HTTP ${connection.responseCode}")
            bytesFetched += connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(out)
        }
        return out
    }

    /** Bytes downloaded by this source so far: ranged blocks plus whole files. */
    var bytesFetched = 0L; private set

    private fun name(url: String) = url.substringBefore('?').substringAfterLast('/')

    /** One frame cropped to bbox: ranged reads, the whole file if they fail, the cached file if present. */
    fun frame(url: String, bbox: List<Double>): Frame {
        val full = File(cacheDir, name(url))
        if (!full.exists()) {
            val channel = try { RangedChannel(url, File(cacheDir, name(url) + ".blocks")) } catch (problem: Exception) { null }
            try {
                if (channel != null) return HdfFile(channel, URI(url.substringBefore('?'))).use { Odim.crop(it, bbox, full.name) }
            } catch (problem: Exception) {
                // fall through to the whole file
            } finally {
                bytesFetched += channel?.fetched ?: 0
            }
        }
        return Odim.readCrop(download(url), bbox)
    }

    /** [bbox] defaults to the region; pass [pointBbox] for the background point path. */
    fun latestFrames(region: Region, count: Int = 12, bbox: List<Double> = region.bbox): List<Frame> =
        listUrls().takeLast(count).map { frame(it, bbox) }

    /** Drop files and block caches older than [maxAgeMinutes] (by download time). The default
     *  holds the 12 input frames (55 min apart end to end) plus slack for publication delay. */
    fun prune(maxAgeMinutes: Int = 70) {
        val cut = System.currentTimeMillis() - maxAgeMinutes * 60_000L
        cacheDir.listFiles()?.filter { it.lastModified() < cut }?.forEach { it.deleteRecursively() }
    }
}

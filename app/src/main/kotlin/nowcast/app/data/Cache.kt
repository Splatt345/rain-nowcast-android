package nowcast.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nowcast.engine.forecast.*
import nowcast.engine.math.Grid
import nowcast.engine.math.toDb
import nowcast.engine.outlook.*
import nowcast.engine.radar.*
import nowcast.engine.render.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The last results on disk, so a cold start shows them at once while the refresh runs. The screens'
 * age chip and stale banners already say how old they are. Only for the same home: a moved home
 * starts empty rather than showing somewhere else's rain.
 */
@Serializable
data class Saved(
    val version: Int = VERSION,
    val homeLat: Double, val homeLon: Double,
    val issued: Long? = null, val modelOnly: Boolean = false, val regionName: String? = null, val updated: Long? = null,
    val point: SPoint? = null, val outlook: SOutlook? = null,
) {
    fun state(): UiState = UiState(
        point = point?.engine(), outlook = outlook?.engine(), issued = issued?.let(Instant::ofEpochSecond),
        modelOnly = modelOnly, regionName = regionName, updated = updated?.let(Instant::ofEpochSecond))

    companion object {
        const val VERSION = 2      // 2: radar amounts gauge-corrected
        private const val FILE = "last.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun of(state: UiState, home: Pair<Double, Double>) = Saved(
            homeLat = home.first, homeLon = home.second, issued = state.issued?.epochSecond, modelOnly = state.modelOnly,
            regionName = state.regionName, updated = state.updated?.epochSecond,
            point = state.point?.let(SPoint::of), outlook = state.outlook?.let(SOutlook::of))

        fun read(dir: File, home: Pair<Double, Double>): Saved? = runCatching {
            json.decodeFromString<Saved>(File(dir, FILE).readText())
        }.getOrNull()?.takeIf { it.version == VERSION && !Locator.moved(home, it.homeLat, it.homeLon) }

        fun write(dir: File, saved: Saved) {
            val file = File(dir, FILE); val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(saved))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}

@Serializable
data class SPoint(
    val minutes: List<Int>, val values: List<Double>, val probability: List<Double>, val pmin: List<Double>,
    val p10: List<Double>, val p25: List<Double>, val p75: List<Double>, val p90: List<Double>, val pmax: List<Double>,
    val rainChance: Double, val summary: String, val inCoverage: Boolean, val wet: Double = RAIN_THRESHOLD,
) {
    fun engine() = PointForecast(minutes.toIntArray(), values.toDoubleArray(), probability.toDoubleArray(), pmin.toDoubleArray(),
        p10.toDoubleArray(), p25.toDoubleArray(), p75.toDoubleArray(), p90.toDoubleArray(), pmax.toDoubleArray(),
        rainChance, summary, inCoverage, wet)

    companion object {
        fun of(forecast: PointForecast) = SPoint(forecast.minutes.toList(), forecast.values.toList(), forecast.probability.toList(), forecast.pmin.toList(),
            forecast.p10.toList(), forecast.p25.toList(), forecast.p75.toList(), forecast.p90.toList(), forecast.pmax.toList(), forecast.rainChance, forecast.summary, forecast.inCoverage, forecast.wet)
    }
}

@Serializable
data class SOutlook(
    val bars: List<SOBar>, val fetched: Long, val usedModel: Boolean, val note: String?,
    val models: List<String>, val ensemble: String, val trainedBbox: List<Double>,
) {
    fun engine() = Outlook(bars.map { Bar(Instant.ofEpochSecond(it.start), it.mm, it.p10, it.p90, it.probability, BarSource.valueOf(it.source)) },
        Instant.ofEpochSecond(fetched), usedModel, note, OutlookMeta(models, ensemble, trainedBbox))

    companion object {
        fun of(outlook: Outlook) = SOutlook(outlook.bars.map { SOBar(it.start.epochSecond, it.mm, it.p10, it.p90, it.probability, it.source.name) },
            outlook.fetched.epochSecond, outlook.usedModel, outlook.note, outlook.meta.models, outlook.meta.ensemble, outlook.meta.trainedBbox)
    }
}

@Serializable
data class SOBar(val start: Long, val mm: Double, val p10: Double, val p90: Double, val probability: Double, val source: String)

/**
 * The map's rendered frames: render levels on a Mercator raster over [bbox] (lon0, lat0, lon1, lat1), and the
 * motion that fills the minutes between them. The map runs on minutes from the first frame.
 */
class MapFrames(val levels: List<ByteArray>, val width: Int, val height: Int, val times: List<Instant>, val bbox: List<Double>,
                val regionId: String, val issued: Instant, val newest: Int, val motion: Render.RasterMotion) {
    val minutes: Int get() = minuteOf(times.size - 1)
    fun minuteOf(frame: Int) = Duration.between(times[0], times[frame]).toMinutes().toInt()
    fun time(minute: Int): Instant = times[0].plusSeconds(60L * minute)
    /** A keyless map: a box around home, not a radar region. */
    val keyless: Boolean get() = regionId in KEYLESS
    fun covers(lat: Double, lon: Double) = covers(bbox, lat, lon)

    /** The frame at or before [minute] and how far on towards the next one, 0 until 1. */
    fun locate(minute: Int): Pair<Int, Double> {
        val instant = time(minute)
        val frame = (times.indexOfLast { it <= instant }).coerceIn(0, maxOf(0, times.size - 2))
        if (frame + 1 >= times.size) return frame to 0.0
        val span = Duration.between(times[frame], times[frame + 1]).seconds.toDouble()
        return frame to (Duration.between(times[frame], instant).seconds / span).coerceIn(0.0, 1.0)
    }

    fun levelsAt(minute: Int): ByteArray {
        val (frame, fraction) = locate(minute)
        return when {
            fraction < 1e-6 -> levels[frame]
            fraction > 1 - 1e-6 -> levels[frame + 1]
            else -> Render.warp(levels[frame], levels[frame + 1], width, height, motion, fraction)
        }
    }

    companion object {
        /** Every radar frame and every forecast step, drawn once for the map. A few seconds for a region. */
        fun of(forecast: Forecast): MapFrames {
            val sampler = Render.mercatorSampler(forecast.geo, forecast.region.bbox, width = 512)
            val past = forecast.past.filter { Duration.between(it.time, forecast.issued).toMinutes() <= SHOWN_PAST_MIN }
            val fields = past.map { frame -> FloatArray(frame.rain.values.size) { frame.rain.values[it].toFloat() } } + forecast.mean.toList()
            val times = past.map { it.time } + (1..forecast.mean.size).map { forecast.issued.plusSeconds(60L * forecast.intervalMinutes * it) }
            return MapFrames(fields.map { Render.levels(it, forecast.geo.rows, forecast.geo.cols, sampler) }, sampler.width, sampler.height, times, forecast.region.bbox,
                forecast.region.id, forecast.issued, past.size - 1, Render.rasterMotion(forecast.motion, sampler))
        }

        /** Open-Meteo's model on a grid around home, for the map without a radar key. */
        fun of(grid: RainGrid): MapFrames = still(OPEN_METEO, grid.fields, grid.rows, grid.cols, grid.times, grid.bbox, grid.issued,
            grid.times.indexOfLast { it <= grid.issued }.coerceAtLeast(0), Render.gridSampler(grid.bbox, grid.rows, grid.cols, width = 512))

        /** A keyless source's radar or nowcast around home (DWD, RMI, GeoSphere), for the map without a radar key. */
        fun of(clip: RadarClip): MapFrames = still(clip.source, clip.fields, clip.rows, clip.cols, clip.times, clip.bbox, clip.issued, clip.newest,
            Render.sampler(clip.bbox, 512, clip.cell))

        /**
         * Frames that came without a motion field: the optical flow of the three around [newest] moves rain between
         * steps, so a shower travels rather than fading in. Fewer than three frames, or dry: cross-faded.
         * These rates are true mm/h, a level is the radar's own rate (see [Render.lut]).
         */
        private fun still(id: String, fields: List<FloatArray>, rows: Int, cols: Int, times: List<Instant>, bbox: List<Double>, issued: Instant,
                          newest: Int, sampler: Render.Sampler): MapFrames {
            val nodeCols = (sampler.width - 1) / 8 + 2; val nodeRows = (sampler.height - 1) / 8 + 2
            val around = if (fields.size < 3) emptyList() else (newest + 1).coerceIn(2, fields.size - 1).let { fields.subList(it - 2, it + 1) }
            val flow = if (around.none { f -> f.any { it > 0f } }) Render.RasterMotion(nodeCols, nodeRows, 8, FloatArray(nodeCols * nodeRows), FloatArray(nodeCols * nodeRows))
            // ponytail: one field for every step, from the three around now; per-step flow if long hourly loops drift
            else Render.rasterMotion(motion(around.map { f -> Grid(rows, cols, DoubleArray(f.size) { toDb(f[it].toDouble()) }) }), sampler)
            val levels = fields.map { f -> Render.levels(FloatArray(f.size) { f[it] / GAUGE_FACTOR.toFloat() }, rows, cols, sampler) }
            return MapFrames(levels, sampler.width, sampler.height, times, bbox, id, issued, newest, flow)
        }

        const val OPEN_METEO = "open-meteo"
        const val DWD = BrightSky.SOURCE
        /** [regionId]s of the keyless maps. */
        private val KEYLESS = setOf(OPEN_METEO, DWD, RmiInca.SOURCE, GeoSphereNowcast.SOURCE)

        private const val FILE = "map.bin"
        private const val VERSION = 2

        /** gzip: dry pixels are zero, so a 24-frame region is a few hundred kB instead of several MB. */
        fun write(dir: File, frames: MapFrames) {
            val tmp = File(dir, "$FILE.tmp")
            DataOutputStream(GZIPOutputStream(tmp.outputStream().buffered())).use { out ->
                out.writeInt(VERSION); out.writeInt(frames.width); out.writeInt(frames.height); out.writeInt(frames.levels.size); out.writeInt(frames.newest)
                out.writeUTF(frames.regionId); out.writeLong(frames.issued.epochSecond)
                frames.bbox.forEach(out::writeDouble)
                frames.times.forEach { out.writeLong(it.epochSecond) }
                frames.levels.forEach(out::write)
                val motion = frames.motion
                out.writeInt(motion.cols); out.writeInt(motion.rows); out.writeInt(motion.step)
                motion.dx.forEach(out::writeFloat); motion.dy.forEach(out::writeFloat)
            }
            val target = File(dir, FILE)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }

        fun read(dir: File): MapFrames? = runCatching {
            DataInputStream(GZIPInputStream(File(dir, FILE).inputStream().buffered())).use { input ->
                if (input.readInt() != VERSION) return null
                val width = input.readInt(); val height = input.readInt(); val count = input.readInt(); val newest = input.readInt()
                val region = input.readUTF(); val issued = Instant.ofEpochSecond(input.readLong())
                val bbox = List(4) { input.readDouble() }
                val times = List(count) { Instant.ofEpochSecond(input.readLong()) }
                val levels = List(count) { ByteArray(width * height).also(input::readFully) }
                val motionWidth = input.readInt(); val motionHeight = input.readInt(); val step = input.readInt()
                val dx = FloatArray(motionWidth * motionHeight) { input.readFloat() }; val dy = FloatArray(motionWidth * motionHeight) { input.readFloat() }
                MapFrames(levels, width, height, times, bbox, region, issued, newest, Render.RasterMotion(motionWidth, motionHeight, step, dx, dy))
            }
        }.getOrNull()
    }
}

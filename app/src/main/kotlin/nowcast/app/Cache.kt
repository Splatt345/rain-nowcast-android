package nowcast.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nowcast.engine.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
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
    val v: Int = VERSION,
    val homeLat: Double, val homeLon: Double,
    val issued: Long? = null, val modelOnly: Boolean = false, val regionName: String? = null, val updated: Long? = null,
    val point: SPoint? = null, val outlook: SOutlook? = null,
) {
    fun state(): UiState = UiState(
        point = point?.engine(), outlook = outlook?.engine(), issued = issued?.let(Instant::ofEpochSecond),
        modelOnly = modelOnly, regionName = regionName, updated = updated?.let(Instant::ofEpochSecond))

    companion object {
        const val VERSION = 1
        private const val FILE = "last.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun of(st: UiState, home: Pair<Double, Double>) = Saved(
            homeLat = home.first, homeLon = home.second, issued = st.issued?.epochSecond, modelOnly = st.modelOnly,
            regionName = st.regionName, updated = st.updated?.epochSecond,
            point = st.point?.let(SPoint::of), outlook = st.outlook?.let(SOutlook::of))

        fun read(dir: File, home: Pair<Double, Double>): Saved? = runCatching {
            json.decodeFromString<Saved>(File(dir, FILE).readText())
        }.getOrNull()?.takeIf { it.v == VERSION && !Locator.moved(home, it.homeLat, it.homeLon) }

        fun write(dir: File, s: Saved) {
            val f = File(dir, FILE); val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}

@Serializable
data class SPoint(
    val minutes: List<Int>, val values: List<Double>, val probability: List<Double>, val pmin: List<Double>,
    val p10: List<Double>, val p25: List<Double>, val p75: List<Double>, val p90: List<Double>, val pmax: List<Double>,
    val rainChance: Double, val summary: String, val inCoverage: Boolean,
) {
    fun engine() = PointForecast(minutes.toIntArray(), values.toDoubleArray(), probability.toDoubleArray(), pmin.toDoubleArray(),
        p10.toDoubleArray(), p25.toDoubleArray(), p75.toDoubleArray(), p90.toDoubleArray(), pmax.toDoubleArray(),
        rainChance, summary, inCoverage)

    companion object {
        fun of(p: PointForecast) = SPoint(p.minutes.toList(), p.values.toList(), p.probability.toList(), p.pmin.toList(),
            p.p10.toList(), p.p25.toList(), p.p75.toList(), p.p90.toList(), p.pmax.toList(), p.rainChance, p.summary, p.inCoverage)
    }
}

@Serializable
data class SOutlook(
    val bars: List<SOBar>, val fetched: Long, val usedModel: Boolean, val note: String?,
    val models: List<String>, val ensemble: String, val trainedBbox: List<Double>,
) {
    fun engine() = Outlook(bars.map { Bar(Instant.ofEpochSecond(it.start), it.mm, it.p10, it.p90, it.prob, BarSource.valueOf(it.source)) },
        Instant.ofEpochSecond(fetched), usedModel, note, OutlookMeta(models, ensemble, trainedBbox))

    companion object {
        fun of(o: Outlook) = SOutlook(o.bars.map { SOBar(it.start.epochSecond, it.mm, it.p10, it.p90, it.probability, it.source.name) },
            o.fetched.epochSecond, o.usedModel, o.note, o.meta.models, o.meta.ensemble, o.meta.trainedBbox)
    }
}

@Serializable
data class SOBar(val start: Long, val mm: Double, val p10: Double, val p90: Double, val prob: Double, val source: String)

/** The map's rendered frames: render levels on a Mercator raster over [bbox] (lon0, lat0, lon1, lat1). */
class MapFrames(val levels: List<ByteArray>, val w: Int, val h: Int, val times: List<Instant>, val bbox: List<Double>,
                val regionId: String, val issued: Instant, val newest: Int) {
    companion object {
        private const val FILE = "map.bin"
        private const val VERSION = 1

        /** gzip: dry pixels are zero, so a 24-frame region is a few hundred kB instead of several MB. */
        fun write(dir: File, f: MapFrames) {
            val tmp = File(dir, "$FILE.tmp")
            DataOutputStream(GZIPOutputStream(tmp.outputStream().buffered())).use { o ->
                o.writeInt(VERSION); o.writeInt(f.w); o.writeInt(f.h); o.writeInt(f.levels.size); o.writeInt(f.newest)
                o.writeUTF(f.regionId); o.writeLong(f.issued.epochSecond)
                f.bbox.forEach(o::writeDouble)
                f.times.forEach { o.writeLong(it.epochSecond) }
                f.levels.forEach(o::write)
            }
            val out = File(dir, FILE)
            if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        }

        fun read(dir: File): MapFrames? = runCatching {
            DataInputStream(GZIPInputStream(File(dir, FILE).inputStream().buffered())).use { i ->
                if (i.readInt() != VERSION) return null
                val w = i.readInt(); val h = i.readInt(); val n = i.readInt(); val newest = i.readInt()
                val region = i.readUTF(); val issued = Instant.ofEpochSecond(i.readLong())
                val bbox = List(4) { i.readDouble() }
                val times = List(n) { Instant.ofEpochSecond(i.readLong()) }
                val levels = List(n) { ByteArray(w * h).also(i::readFully) }
                MapFrames(levels, w, h, times, bbox, region, issued, newest)
            }
        }.getOrNull()
    }
}

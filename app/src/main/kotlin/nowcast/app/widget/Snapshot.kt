package nowcast.app.widget

import nowcast.app.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nowcast.engine.*
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * What every widget type reads when the process is cold: written after each refresh, whether that
 * was the full region run or a box-only run around home. Times are epoch seconds; minutes count
 * from [issued], the newest radar frame, so a reader shifts them by its own age.
 */
@Serializable
data class Snapshot(
    val v: Int = VERSION,
    val issued: Long,
    val written: Long,
    val region: String,
    /** false: a cheap background run on the box around home, not the whole region. */
    val full: Boolean = true,
    val minutes: List<Int>,
    val mean: List<Float>,
    val p10: List<Float>,
    val p90: List<Float>,
    val prob: List<Float>,
    val bars: List<SBar> = emptyList(),
    val radar: SRadar? = null,
    /** What the age counts from: "radar", or "Open-Meteo" when the nowcast is model output. */
    val source: String = "radar",
) {
    fun age(now: Instant): Int = Duration.between(Instant.ofEpochSecond(issued), now).toMinutes().toInt().coerceAtLeast(0)

    /** The engine's sentence, re-worded for the minutes that passed since the radar frame. */
    fun summary(now: Instant): String =
        if (minutes.isEmpty()) "No forecast yet · open the app"
        else nowcast.engine.summary(minutes.toIntArray(), DoubleArray(mean.size) { mean[it].toDouble() },
            DoubleArray(prob.size) { prob[it].toDouble() }, age(now))

    /** Highest chance from now to the end of the series; null once the series is all in the past. */
    fun chance(now: Instant): Int? {
        val a = age(now)
        return minutes.indices.filter { minutes[it] >= a }.maxOfOrNull { prob[it] }?.let { Math.rint(100.0 * it).toInt() }
    }

    fun engineBars(): List<Bar> = bars.map {
        Bar(Instant.ofEpochSecond(it.start), it.mm.toDouble(), it.p10.toDouble(), it.p90.toDouble(), it.prob.toDouble(),
            if (it.nowcast) BarSource.NOWCAST else BarSource.MODEL)
    }

    fun write(dir: File) {
        val f = File(dir, FILE); val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json.encodeToString(this))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }      // rename replaces atomically on Android
    }

    companion object {
        const val VERSION = 1
        const val FILE = "widget.json"
        /** Radar crop around home: half side in km and pixels (OPERA is ~1 km/px, so 128 px is native). */
        const val HALF_KM = 64.0
        const val RADAR_PX = 128
        val LEADS = listOf(0, 30, 60)
        private val json = Json { ignoreUnknownKeys = true }

        fun read(dir: File): Snapshot? = runCatching { decode(File(dir, FILE).readText()) }.getOrNull()
        fun decode(text: String): Snapshot? = runCatching { json.decodeFromString<Snapshot>(text) }.getOrNull()
            ?.takeIf { it.v == VERSION && it.minutes.size == it.mean.size && it.mean.size == it.prob.size &&
                it.p10.size == it.mean.size && it.p90.size == it.mean.size }
        fun encode(s: Snapshot): String = json.encodeToString(s)

        fun box(lat: Double, lon: Double): List<Double> {
            val dLat = HALF_KM / 110.57; val dLon = HALF_KM / (111.32 * cos(Math.toRadians(lat)))
            return listOf(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
        }

        /** From any run's forecast: [fc] may be the full region or the box around home. */
        fun of(fc: Forecast, p: PointForecast, bars: List<Bar?>, home: Pair<Double, Double>, full: Boolean,
               now: Instant = Instant.now()): Snapshot {
            val (lat, lon) = home
            val bbox = box(lat, lon)
            val s = Render.mercatorSampler(fc.geo, bbox, width = RADAR_PX)
            val step = fc.intervalMinutes
            val frames = LEADS.map { lead ->
                val field = if (lead == 0) fc.past.last().rain.v.let { v -> FloatArray(v.size) { v[it].toFloat() } }
                else fc.mean[(lead / step - 1).coerceIn(0, fc.mean.size - 1)]
                val actual = if (lead == 0) 0 else ((lead / step).coerceIn(1, fc.mean.size)) * step
                SFrame(actual, Base64.getEncoder().encodeToString(Render.levels(field, fc.geo.h, fc.geo.w, s)))
            }
            fun f(a: DoubleArray) = a.map { (it * 100).roundToInt() / 100f }
            return Snapshot(
                issued = fc.issued.epochSecond, written = now.epochSecond, region = fc.region.name, full = full,
                minutes = p.minutes.toList(), mean = f(p.values), p10 = f(p.p10), p90 = f(p.p90), prob = f(p.probability),
                bars = bars.filterNotNull().map { SBar(it.start.epochSecond, it.mm.toFloat(), it.p10.toFloat(), it.p90.toFloat(),
                    it.probability.toFloat(), it.source == BarSource.NOWCAST) },
                radar = SRadar(bbox, s.width, s.height, lat, lon, frames),
            )
        }

        /** Open-Meteo's point nowcast: no radar crop, so the radar widget says why it is empty. */
        fun ofPoint(p: PointForecast, issued: Instant, bars: List<Bar?>, full: Boolean, now: Instant = Instant.now()): Snapshot {
            fun f(a: DoubleArray) = a.map { (it * 100).roundToInt() / 100f }
            return Snapshot(issued = issued.epochSecond, written = now.epochSecond, region = "Open-Meteo", full = full,
                minutes = p.minutes.toList(), mean = f(p.values), p10 = f(p.p10), p90 = f(p.p90), prob = f(p.probability),
                bars = bars.filterNotNull().map { SBar(it.start.epochSecond, it.mm.toFloat(), it.p10.toFloat(), it.p90.toFloat(),
                    it.probability.toFloat(), it.source == BarSource.NOWCAST) },
                source = SOURCE_OPEN_METEO)
        }

        const val SOURCE_OPEN_METEO = "Open-Meteo"

        /** Made-up but plausible data for the picker preview and the configuration screen before a first run. */
        fun sample(now: Instant = Instant.now()): Snapshot {
            val issued = now.epochSecond - now.epochSecond % 300 - 300
            val minutes = (-55..60).toList()
            val mean = minutes.map { m -> (2.4 * exp(-((m - 30.0) / 14).let { it * it })).toFloat() }
            val bars = (0 until 96).map { i ->
                val t0 = now.epochSecond - now.epochSecond % 1800 + 1800L * i
                val wet = (0.5 + 0.5 * kotlin.math.sin(i / 7.0)).toFloat() * (if (i in 30..50) 1f else 0.3f)
                SBar(t0, 0.6f * wet * wet, 0.1f * wet, 1.4f * wet, wet, i < 2)
            }
            val px = RADAR_PX
            val lv = ByteArray(px * px) { k ->
                val x = k % px - 40.0; val y = k / px - 70.0
                Render.level(6 * exp(-(x * x / 900 + y * y / 300)) + 0.6 * exp(-((x - 60) * (x - 60) + (y + 40) * (y + 40)) / 400.0)).toByte()
            }
            val enc = Base64.getEncoder().encodeToString(lv)
            val (lat, lon) = Settings.DEFAULT_HOME
            return Snapshot(issued = issued, written = now.epochSecond, region = "Belgium", minutes = minutes, mean = mean,
                p10 = mean.map { it * 0.4f }, p90 = mean.map { it * 1.8f + 0.05f },
                prob = minutes.map { m -> (0.75 * exp(-((m - 32.0) / 22).let { it * it })).toFloat() },
                bars = bars, radar = SRadar(box(lat, lon), px, px, lat, lon, LEADS.map { SFrame(it, enc) }))
        }
    }
}

@Serializable
data class SBar(val start: Long, val mm: Float, val p10: Float, val p90: Float, val prob: Float, val nowcast: Boolean = false)

/** Render levels (0–255, [Render.level]) on a Mercator raster over [bbox] = lon0, lat0, lon1, lat1. */
@Serializable
data class SRadar(val bbox: List<Double>, val w: Int, val h: Int, val homeLat: Double, val homeLon: Double,
                  val frames: List<SFrame>) {
    fun frame(lead: Int): SFrame? = frames.minByOrNull { kotlin.math.abs(it.lead - lead) }
}

/** [lead] minutes after the snapshot's issue time; [levels] base64, w × h bytes. */
@Serializable
data class SFrame(val lead: Int, val levels: String) {
    fun bytes(): ByteArray = Base64.getDecoder().decode(levels)
}

/** "radar 7 min ago" while fresh, "Radar 47 min old" once it is not. */
object Freshness {
    const val STALE_MIN = 30

    fun stale(age: Int) = age >= STALE_MIN

    fun text(age: Int, source: String = "radar"): String {
        val big = source.replaceFirstChar { it.uppercase() }
        return when {
            age < 1 -> "$source just now"
            age < STALE_MIN -> "$source $age min ago"
            age < 120 -> "$big $age min old"
            age < 48 * 60 -> "$big ${age / 60} h old"
            else -> "$big ${age / 1440} days old"
        }
    }
}

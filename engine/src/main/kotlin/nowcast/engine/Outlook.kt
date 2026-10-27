package nowcast.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.FloatBuffer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The 48 h outlook: Open-Meteo deterministic runs through the local GBM, ICON-D2-EPS as fallback. */
class OutlookException(msg: String) : Exception(msg)

enum class BarSource { NOWCAST, MODEL, ENSEMBLE }

/** One 30-min bar. mm, p10 and p90 are millimetres in the half hour. */
class Bar(val start: Instant, val mm: Double, val p10: Double, val p90: Double, val probability: Double,
          val source: BarSource) {
    val end: Instant get() = start.plusSeconds(1800)
}

/** Hourly series. time[i] is the END of the hour the value covers (Open-Meteo "preceding hour"); NaN = missing. */
class Hourly(val time: List<Instant>, val series: Map<String, DoubleArray>)

class Outlook(val bars: List<Bar>, val fetched: Instant, val usedModel: Boolean, val note: String?)

/** The feature row of outlook_train.assemble, in outlook_gbm.json order. */
object OutlookFeatures {
    val MODELS = listOf("icon_d2", "icon_eu", "ecmwf_ifs025")
    val NAMES = MODELS + MODELS.map { "${it}_prev_hour" } + MODELS.map { "${it}_next_hour" } +
        listOf("model_mean", "model_max", "sin_hour", "cos_hour", "sin_doy", "cos_doy", "lat", "lon", "lead_bucket")

    /** null at the series edges or when any model value is missing (training dropped those rows too). */
    fun row(h: Hourly, j: Int, lat: Double, lon: Double, now: Instant): FloatArray? {
        if (j <= 0 || j >= h.time.size - 1) return null
        // the trainer holds model values as float32
        fun at(k: Int) = MODELS.map { h.series[it]?.get(k)?.toFloat()?.toDouble() ?: Double.NaN }
        val m = at(j); val prev = at(j - 1); val next = at(j + 1)
        if ((m + prev + next).any { !it.isFinite() }) return null
        val t = h.time[j].atOffset(ZoneOffset.UTC)
        val hod = t.hour / 24.0 * 2 * PI
        val doy = (t.dayOfYear - 1) / 365.25 * 2 * PI
        val leadHours = Duration.between(now, h.time[j]).seconds / 3600.0
        val v = m + prev + next + listOf(m.average(), m.max(), sin(hod), cos(hod), sin(doy), cos(doy),
            lat, lon, if (leadHours < 12) 0.0 else 1.0)
        return FloatArray(v.size) { v[it].toFloat() }
    }
}

/** outlook_gbm.onnx: input "x" (n, 18), output "quantiles" (n, 9); sorted and clipped like predict_gbm. */
class OutlookModel(bytes: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(bytes, OrtSession.SessionOptions())

    fun quantiles(x: List<FloatArray>): List<DoubleArray> {
        if (x.isEmpty()) return emptyList()
        val nf = x[0].size
        val buf = FloatBuffer.allocate(x.size * nf).apply { x.forEach { put(it) }; rewind() }
        return OnnxTensor.createTensor(env, buf, longArrayOf(x.size.toLong(), nf.toLong())).use { t ->
            session.run(mapOf("x" to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                (res.get("quantiles").get().value as Array<FloatArray>).map { q ->
                    q.map { maxOf(0.0, it.toDouble()) }.sorted().toDoubleArray()
                }
            }
        }
    }

    override fun close() = session.close()
}

object OpenMeteo {
    private const val DET = "https://api.open-meteo.com/v1/forecast"
    private const val ENS = "https://ensemble-api.open-meteo.com/v1/ensemble"

    /** Every hourly key starting with "precipitation", the prefix stripped ("precipitation_icon_d2" -> "icon_d2"). */
    fun parse(json: String): Hourly {
        val h = Json.parseToJsonElement(json).jsonObject["hourly"]?.jsonObject
            ?: throw OutlookException("Open-Meteo answered without hourly data")
        val time = h.getValue("time").jsonArray.map { LocalDateTime.parse(it.jsonPrimitive.content).toInstant(ZoneOffset.UTC) }
        val series = h.filterKeys { it.startsWith("precipitation") }.map { (k, v) ->
            k.removePrefix("precipitation").removePrefix("_").ifEmpty { "precipitation" } to
                v.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: Double.NaN }.toDoubleArray()
        }.toMap()
        return Hourly(time, series)
    }

    private fun get(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "rain-nowcast-android")
        if (c.responseCode !in 200..299) throw OutlookException("Open-Meteo HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    }

    private fun q(lat: Double, lon: Double) = "latitude=%.4f&longitude=%.4f".format(Locale.ROOT, lat, lon)

    fun deterministic(lat: Double, lon: Double): Hourly = parse(get("$DET?${q(lat, lon)}&hourly=precipitation" +
        "&models=${OutlookFeatures.MODELS.joinToString(",")}&timezone=GMT&past_days=1&forecast_days=3"))

    /** ICON-D2-EPS: control + 19 members. */
    fun ensemble(lat: Double, lon: Double): Hourly =
        parse(get("$ENS?${q(lat, lon)}&hourly=precipitation&models=icon_d2&timezone=GMT&forecast_days=3"))
}

object OutlookBars {
    const val N = 96
    const val WET_MM = 0.1          // per 30 min
    private const val HALF = 1800L

    fun floor30(t: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(t.epochSecond, HALF) * HALF)

    private fun halves(hourEnd: Instant, xs: DoubleArray, p10: Double, p90: Double, src: BarSource) =
        listOf(hourEnd.minusSeconds(2 * HALF), hourEnd.minusSeconds(HALF)).map {
            Bar(it, xs.average(), p10, p90, xs.count { v -> v > WET_MM }.toDouble() / xs.size, src)
        }

    /** The GBM's 9 hourly quantiles as 9 members; each 30-min bar is half the hour. */
    fun fromQuantiles(hourEnd: Instant, q: DoubleArray): List<Bar> {
        val h = DoubleArray(q.size) { q[it] / 2 }.sorted().toDoubleArray()
        return halves(hourEnd, h, h.first(), h.last(), BarSource.MODEL)
    }

    fun fromMembers(hourEnd: Instant, members: DoubleArray): List<Bar> {
        val h = members.filter { it.isFinite() }.map { it / 2 }.toDoubleArray()
        if (h.size < 2) return emptyList()
        return halves(hourEnd, h, percentile(h, 10.0), percentile(h, 90.0), BarSource.ENSEMBLE)
    }

    fun assemble(det: Hourly?, ens: Hourly?, model: ((List<FloatArray>) -> List<DoubleArray>)?,
                 lat: Double, lon: Double, now: Instant): Outlook {
        val lo = floor30(now); val hi = lo.plusSeconds(HALF * (N + 2))
        fun inWindow(t: Instant) = t > lo && t <= hi
        val byHour = sortedMapOf<Instant, List<Bar>>()
        var note: String? = null
        if (det != null && model != null) {
            val js = det.time.indices.filter { inWindow(det.time[it]) }
                .mapNotNull { j -> OutlookFeatures.row(det, j, lat, lon, now)?.let { j to it } }
            try {
                if (js.isNotEmpty()) model(js.map { it.second }).forEachIndexed { i, q ->
                    val end = det.time[js[i].first]; byHour[end] = fromQuantiles(end, q)
                }
            } catch (e: Exception) {
                note = "Local outlook model failed (${e.message}); showing ICON-D2-EPS"
            }
        }
        val usedModel = byHour.isNotEmpty()
        if (!usedModel && note == null) note = "Local outlook model unavailable; showing ICON-D2-EPS"
        if (ens != null) for ((j, end) in ens.time.withIndex()) {
            if (!inWindow(end) || end in byHour) continue
            val b = fromMembers(end, ens.series.values.map { it[j] }.toDoubleArray())
            if (b.isNotEmpty()) byHour[end] = b
        }
        if (byHour.isEmpty()) throw OutlookException("No outlook data for this place")
        return Outlook(byHour.values.flatten(), now, usedModel, note)
    }

    /** Nowcast for every bar its point curve fully covers (~ the first hour), the outlook after: a switch, not a blend. */
    fun join(outlook: Outlook?, point: PointForecast?, issued: Instant?, now: Instant): List<Bar?> {
        val t0 = floor30(now)
        val fromOutlook = outlook?.bars?.associateBy { it.start } ?: emptyMap()
        return List(N) { i ->
            val s = t0.plusSeconds(HALF * i)
            nowcastBar(point, issued, s) ?: fromOutlook[s]
        }
    }

    private fun nowcastBar(p: PointForecast?, issued: Instant?, s: Instant): Bar? {
        if (p == null || issued == null) return null
        val off = Duration.between(issued, s).seconds
        if (off % 60 != 0L) return null
        val first = (off / 60).toInt(); val last = first + 30
        if (first < p.minutes.first() || last > p.minutes.last()) return null
        val idx = p.minutes.indices.filter { p.minutes[it] in first until last }
        return Bar(s, idx.sumOf { p.values[it] } / 60, idx.sumOf { p.p10[it] } / 60, idx.sumOf { p.p90[it] } / 60,
            idx.maxOf { p.probability[it] }, BarSource.NOWCAST)
    }

    private val DAY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private fun pct(p: Double) = Math.rint(100 * p).toInt()
    private fun mm(x: Double) = "%.1f".format(Locale.ROOT, x)

    fun label(b: Bar, zone: ZoneId): String =
        "${DAY.format(b.start.atZone(zone))}–${HM.format(b.end.atZone(zone))} · ${pct(b.probability)}% · " +
            "${mm(b.p10)}–${mm(b.p90)} mm"

    fun sentence(bars: List<Bar?>, zone: ZoneId): String {
        val known = bars.filterNotNull()
        if (known.isEmpty()) return "Outlook unavailable"
        val wet = known.firstOrNull { it.probability >= 0.5 }
        if (wet != null) {
            val total = known.filter { it.start >= wet.start && it.start < wet.start.plusSeconds(6 * 3600) }.sumOf { it.mm }
            val whenText = if (wet === known.first()) "now" else "from ${DAY.format(wet.start.atZone(zone))}"
            return "Rain likely $whenText (${pct(wet.probability)}%), about ${mm(total)} mm over the following 6 h"
        }
        val best = known.maxBy { it.probability }
        if (best.probability >= 0.2)
            return "Mostly dry; the best chance of rain is ${DAY.format(best.start.atZone(zone))} (${pct(best.probability)}%)"
        val hours = Duration.between(known.first().start, known.last().end).toHours()
        return "Dry for the next $hours hours"
    }
}

/** Fetch and assemble. A failing deterministic fetch or model drops to the ensemble; nothing is invented. */
fun fetchOutlook(lat: Double, lon: Double, model: OutlookModel?, now: Instant = Instant.now()): Outlook {
    val det = runCatching { OpenMeteo.deterministic(lat, lon) }.getOrNull()
    val first = runCatching { OutlookBars.assemble(det, null, model?.let { m -> m::quantiles }, lat, lon, now) }.getOrNull()
    // the ensemble fills hours the model could not cover (D2 ends near +48 h, a missing value, a failure)
    val complete = first != null && first.usedModel &&
        first.bars.last().end >= OutlookBars.floor30(now).plusSeconds(1800L * OutlookBars.N)
    if (complete) return first!!
    val ens = runCatching { OpenMeteo.ensemble(lat, lon) }.getOrNull()
    if (det == null && ens == null) throw OutlookException("Open-Meteo unreachable")
    return OutlookBars.assemble(det, ens, model?.let { m -> m::quantiles }, lat, lon, now)
}

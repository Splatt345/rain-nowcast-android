package nowcast.engine.outlook

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.*
import nowcast.engine.forecast.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.FloatBuffer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The 48 h outlook: Open-Meteo deterministic runs through the local GBM, an ensemble (ICON-D2-EPS) as fallback. */
class OutlookException(msg: String) : Exception(msg)

enum class BarSource { NOWCAST, MODEL, ENSEMBLE }

/** One 30-min bar. mm, p10 and p90 are millimetres in the half hour. */
class Bar(val start: Instant, val mm: Double, val p10: Double, val p90: Double, val probability: Double,
          val source: BarSource) {
    val end: Instant get() = start.plusSeconds(1800)
}

/** Hourly series. time[i] is the END of the hour the value covers (Open-Meteo "preceding hour"); NaN = missing. */
class Hourly(val time: List<Instant>, val series: Map<String, DoubleArray>)

class Outlook(val bars: List<Bar>, val fetched: Instant, val usedModel: Boolean, val note: String?,
              val meta: OutlookMeta = OutlookMeta.BELGIUM)

/**
 * What outlook_gbm.json says about its model: the Open-Meteo
 * models it reads, the ensemble that fills in, and the extent of its training points. lat and lon
 * are features, so outside that extent the trees extrapolate: at Amsterdam the Belgian model turned
 * all-dry NWP into 0.44 mm at 33 % in every hour. There the ensemble is used instead.
 */
class OutlookMeta(val models: List<String>, val ensemble: String, val trainedBbox: List<Double>) {
    fun trainedAt(lat: Double, lon: Double) =
        lon in trainedBbox[0]..trainedBbox[2] && lat in trainedBbox[1]..trainedBbox[3]

    val modelNames get() = models.map { NAMES[it] ?: it }
    val ensembleName get() = if (ensemble == "icon_d2") "ICON-D2-EPS" else "the ${NAMES[ensemble] ?: ensemble} ensemble"

    companion object {
        private val NAMES = mapOf("icon_d2" to "ICON-D2", "icon_eu" to "ICON-EU", "ecmwf_ifs025" to "IFS",
            "meteofrance_arome_france" to "AROME", "gfs_global" to "GFS", "gfs025" to "GFS", "icon_global" to "ICON",
            "icon_seamless" to "ICON-EPS")
        /** Hourly, 40 members, anywhere: used where the model's own ensemble has no data (ICON-D2 is Central Europe only). */
        const val GLOBAL_ENSEMBLE = "icon_seamless"
        /** The bundled model: 7×7 points 48 km apart around Belgium. */
        val BELGIUM = OutlookMeta(listOf("icon_d2", "icon_eu", "ecmwf_ifs025"), "icon_d2",
            listOf(2.2678, 49.0895, 6.6213, 51.871))

        /** Files exported before these keys existed are the Belgian model. */
        fun parse(json: String): OutlookMeta {
            val root = Json.parseToJsonElement(json).jsonObject
            return OutlookMeta(
                root["models"]?.jsonArray?.map { it.jsonPrimitive.content } ?: BELGIUM.models,
                root["ensemble"]?.jsonPrimitive?.contentOrNull ?: BELGIUM.ensemble,
                root["trained_bbox"]?.jsonArray?.map { it.jsonPrimitive.double } ?: BELGIUM.trainedBbox)
        }
    }
}

/** The model's feature row, in outlook_gbm.json order. */
object OutlookFeatures {
    fun names(models: List<String>) = models + models.map { "${it}_prev_hour" } + models.map { "${it}_next_hour" } +
        listOf("model_mean", "model_max", "sin_hour", "cos_hour", "sin_doy", "cos_doy", "lat", "lon", "lead_bucket")

    /** null at the series edges or when any model value is missing (training dropped those rows too). */
    fun row(hourly: Hourly, hour: Int, lat: Double, lon: Double, now: Instant,
            models: List<String> = OutlookMeta.BELGIUM.models): FloatArray? {
        if (hour <= 0 || hour >= hourly.time.size - 1) return null
        // the trainer holds model values as float32
        fun at(index: Int) = models.map { hourly.series[it]?.get(index)?.toFloat()?.toDouble() ?: Double.NaN }
        val current = at(hour); val prev = at(hour - 1); val next = at(hour + 1)
        if ((current + prev + next).any { !it.isFinite() }) return null
        val time = hourly.time[hour].atOffset(ZoneOffset.UTC)
        val hourAngle = time.hour / 24.0 * 2 * PI
        val dayAngle = (time.dayOfYear - 1) / 365.25 * 2 * PI
        val leadHours = Duration.between(now, hourly.time[hour]).seconds / 3600.0
        val features = current + prev + next + listOf(current.average(), current.max(), sin(hourAngle), cos(hourAngle), sin(dayAngle), cos(dayAngle),
            lat, lon, if (leadHours < 12) 0.0 else 1.0)
        return FloatArray(features.size) { features[it].toFloat() }
    }
}

/** outlook_gbm.onnx: input "x" (n, 3 × models + 9), output "quantiles" (n, 9); quantiles sorted and clipped. */
class OutlookModel(bytes: ByteArray, val meta: OutlookMeta = OutlookMeta.BELGIUM) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(bytes, OrtSession.SessionOptions())

    fun quantiles(rows: List<FloatArray>): List<DoubleArray> {
        if (rows.isEmpty()) return emptyList()
        val featureCount = rows[0].size
        val buf = FloatBuffer.allocate(rows.size * featureCount).apply { rows.forEach { put(it) }; rewind() }
        return OnnxTensor.createTensor(env, buf, longArrayOf(rows.size.toLong(), featureCount.toLong())).use { input ->
            session.run(mapOf("x" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result.get("quantiles").get().value as Array<FloatArray>).map { predicted ->
                    predicted.map { maxOf(0.0, it.toDouble()) }.sorted().toDoubleArray()
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
    fun parse(json: String, block: String = "hourly"): Hourly {
        val data = Json.parseToJsonElement(json).jsonObject[block]?.jsonObject
            ?: throw OutlookException("Open-Meteo answered without $block data")
        val time = data.getValue("time").jsonArray.map { LocalDateTime.parse(it.jsonPrimitive.content).toInstant(ZoneOffset.UTC) }
        val series = data.filterKeys { it.startsWith("precipitation") }.map { (key, values) ->
            key.removePrefix("precipitation").removePrefix("_").ifEmpty { "precipitation" } to
                values.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: Double.NaN }.toDoubleArray()
        }.toMap()
        return Hourly(time, series)
    }

    internal fun get(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "rain-nowcast-android")
        if (connection.responseCode !in 200..299) throw OutlookException("Open-Meteo HTTP ${connection.responseCode}")
        return connection.inputStream.bufferedReader().readText()
    }

    private fun coords(lat: Double, lon: Double) = "latitude=%.4f&longitude=%.4f".format(Locale.ROOT, lat, lon)

    fun deterministic(lat: Double, lon: Double, models: List<String>): Hourly = parse(get("$DET?${coords(lat, lon)}" +
        "&hourly=precipitation&models=${models.joinToString(",")}&timezone=GMT&past_days=1&forecast_days=3"))

    /** Every member, e.g. ICON-D2-EPS: control + 19. */
    fun ensemble(lat: Double, lon: Double, model: String): Hourly =
        parse(get("$ENS?${coords(lat, lon)}&hourly=precipitation&models=$model&timezone=GMT&forecast_days=3"))
}

object OutlookBars {
    const val BAR_COUNT = 96
    const val WET_MM = 0.1          // per 30 min
    private const val HALF = 1800L

    fun floor30(t: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(t.epochSecond, HALF) * HALF)

    private fun halves(hourEnd: Instant, values: DoubleArray, p10: Double, p90: Double, source: BarSource) =
        listOf(hourEnd.minusSeconds(2 * HALF), hourEnd.minusSeconds(HALF)).map {
            Bar(it, values.average(), p10, p90, values.count { value -> value > WET_MM }.toDouble() / values.size, source)
        }

    /** The GBM's 9 hourly quantiles as 9 members; each 30-min bar is half the hour. */
    fun fromQuantiles(hourEnd: Instant, quantiles: DoubleArray): List<Bar> {
        val halfHour = DoubleArray(quantiles.size) { quantiles[it] / 2 }.sorted().toDoubleArray()
        return halves(hourEnd, halfHour, halfHour.first(), halfHour.last(), BarSource.MODEL)
    }

    fun fromMembers(hourEnd: Instant, members: DoubleArray): List<Bar> {
        val halfHour = members.filter { it.isFinite() }.map { it / 2 }.toDoubleArray()
        if (halfHour.size < 2) return emptyList()
        return halves(hourEnd, halfHour, percentile(halfHour, 10.0), percentile(halfHour, 90.0), BarSource.ENSEMBLE)
    }

    fun assemble(deterministic: Hourly?, ensemble: Hourly?, model: ((List<FloatArray>) -> List<DoubleArray>)?,
                 lat: Double, lon: Double, now: Instant, meta: OutlookMeta = OutlookMeta.BELGIUM): Outlook {
        val windowStart = floor30(now); val windowEnd = windowStart.plusSeconds(HALF * (BAR_COUNT + 2))
        fun inWindow(t: Instant) = t > windowStart && t <= windowEnd
        val byHour = sortedMapOf<Instant, List<Bar>>()
        var note: String? = null
        if (deterministic != null && model != null) {
            val hours = deterministic.time.indices.filter { inWindow(deterministic.time[it]) }
                .mapNotNull { hour -> OutlookFeatures.row(deterministic, hour, lat, lon, now, meta.models)?.let { hour to it } }
            try {
                if (hours.isNotEmpty()) model(hours.map { it.second }).forEachIndexed { index, quantiles ->
                    val end = deterministic.time[hours[index].first]; byHour[end] = fromQuantiles(end, quantiles)
                }
            } catch (problem: Exception) {
                note = "Local outlook model failed (${problem.message}); showing ${meta.ensembleName}"
            }
        }
        val usedModel = byHour.isNotEmpty()
        if (!usedModel && note == null) note = "Local outlook model unavailable; showing ${meta.ensembleName}"
        if (ensemble != null) for ((hour, end) in ensemble.time.withIndex()) {
            if (!inWindow(end) || end in byHour) continue
            val halves = fromMembers(end, ensemble.series.values.map { it[hour] }.toDoubleArray())
            if (halves.isNotEmpty()) byHour[end] = halves
        }
        if (byHour.isEmpty()) throw OutlookException("No outlook data for this place")
        return Outlook(byHour.values.flatten(), now, usedModel, note, meta)
    }

    /** Nowcast for every bar its point curve fully covers (~ the first hour), the outlook after: a switch, not a blend. */
    fun join(outlook: Outlook?, point: PointForecast?, issued: Instant?, now: Instant): List<Bar?> {
        val start = floor30(now)
        val fromOutlook = outlook?.bars?.associateBy { it.start } ?: emptyMap()
        return List(BAR_COUNT) { i ->
            val barStart = start.plusSeconds(HALF * i)
            nowcastBar(point, issued, barStart) ?: fromOutlook[barStart]
        }
    }

    private fun nowcastBar(point: PointForecast?, issued: Instant?, start: Instant): Bar? {
        if (point == null || issued == null) return null
        val offsetSeconds = Duration.between(issued, start).seconds
        if (offsetSeconds % 60 != 0L) return null
        val first = (offsetSeconds / 60).toInt(); val last = first + 30
        if (first < point.minutes.first() || last > point.minutes.last()) return null
        val inBar = point.minutes.indices.filter { point.minutes[it] in first until last }
        return Bar(start, inBar.sumOf { point.values[it] } / 60, inBar.sumOf { point.p10[it] } / 60, inBar.sumOf { point.p90[it] } / 60,
            inBar.maxOf { point.probability[it] }, BarSource.NOWCAST)
    }

    private val DAY = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private fun percent(probability: Double) = Math.rint(100 * probability).toInt()
    private fun mm(value: Double) = "%.1f".format(Locale.ROOT, value)

    fun label(bar: Bar, zone: ZoneId): String =
        "${DAY.format(bar.start.atZone(zone))}–${TIME.format(bar.end.atZone(zone))} · ${percent(bar.probability)}% · " +
            "${mm(bar.p10)}–${mm(bar.p90)} mm"

    fun sentence(bars: List<Bar?>, zone: ZoneId): String {
        val known = bars.filterNotNull()
        if (known.isEmpty()) return "Outlook unavailable"
        val wet = known.firstOrNull { it.probability >= 0.5 }
        if (wet != null) {
            val total = known.filter { it.start >= wet.start && it.start < wet.start.plusSeconds(6 * 3600) }.sumOf { it.mm }
            val whenText = if (wet === known.first()) "now" else "from ${DAY.format(wet.start.atZone(zone))}"
            return "Rain likely $whenText (${percent(wet.probability)}%), about ${mm(total)} mm over the following 6 h"
        }
        val best = known.maxBy { it.probability }
        if (best.probability >= 0.2)
            return "Mostly dry; the best chance of rain is ${DAY.format(best.start.atZone(zone))} (${percent(best.probability)}%)"
        val hours = Duration.between(known.first().start, known.last().end).toHours()
        return if (hours == 1L) "Dry for the next hour" else "Dry for the next $hours hours"
    }
}

/** Fetch and assemble. A failing deterministic fetch or model drops to the ensemble; nothing is invented. */
fun fetchOutlook(lat: Double, lon: Double, given: OutlookModel?, now: Instant = Instant.now()): Outlook {
    val meta = given?.meta ?: OutlookMeta.BELGIUM
    val outside = given != null && !meta.trainedAt(lat, lon)
    val model = given.takeIf { !outside }
    val deterministic = runCatching { OpenMeteo.deterministic(lat, lon, meta.models) }.getOrNull()
    val first = runCatching { OutlookBars.assemble(deterministic, null, model?.let { loaded -> loaded::quantiles }, lat, lon, now, meta) }.getOrNull()
    // the ensemble fills hours the model could not cover (D2 ends near +48 h, a missing value, a failure)
    val complete = first != null && first.usedModel &&
        first.bars.last().end >= OutlookBars.floor30(now).plusSeconds(1800L * OutlookBars.BAR_COUNT)
    if (complete) return first!!
    var shown = meta
    val ensemble = runCatching { OpenMeteo.ensemble(lat, lon, meta.ensemble) }.getOrNull()
        ?: runCatching { OpenMeteo.ensemble(lat, lon, OutlookMeta.GLOBAL_ENSEMBLE) }.getOrNull()
            ?.also { shown = OutlookMeta(meta.models, OutlookMeta.GLOBAL_ENSEMBLE, meta.trainedBbox) }
    if (deterministic == null && ensemble == null) throw OutlookException("Open-Meteo unreachable")
    val outlook = OutlookBars.assemble(deterministic, ensemble, model?.let { loaded -> loaded::quantiles }, lat, lon, now, shown)
    return if (outside) Outlook(outlook.bars, outlook.fetched, false,
        "Outside the area the local outlook model was trained on; showing ${shown.ensembleName}", shown)
    else outlook
}

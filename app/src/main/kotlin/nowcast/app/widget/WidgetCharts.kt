package nowcast.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import nowcast.app.ui.now.*
import nowcast.engine.outlook.*
import nowcast.engine.render.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Glance cannot draw, so charts are bitmaps. Each is a stack of one-channel masks that the widget
 * tints with theme colours (day and night resolved by the launcher), so a chart follows dark mode and
 * dynamic colour without being redrawn, and costs one byte per pixel per ink.
 */
enum class Ink { MUTED, BASE, PRIMARY, ACCENT }

class Layer(val ink: Ink, val bitmap: Bitmap)

private class Masks(widthDp: Float, heightDp: Float, density: Float) {
    /** Full density up to 2.5×, then down until a mask is at most [MAX_PX]: RemoteViews carry every bitmap. */
    val scale: Float = minOf(density, 2.5f).let { cap -> minOf(cap, sqrt(MAX_PX / (widthDp * heightDp).coerceAtLeast(1f))) }
    val width = max(1, (widthDp * scale).toInt()); val height = max(1, (heightDp * scale).toInt())
    private val maps = linkedMapOf<Ink, Canvas>()
    private val bitmaps = linkedMapOf<Ink, Bitmap>()
    operator fun get(ink: Ink): Canvas = maps.getOrPut(ink) {
        Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also { bitmaps[ink] = it }.let(::Canvas)
    }
    fun dp(value: Float) = value * scale
    fun paint(alpha: Float = 1f, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        if (stroke > 0) { style = Paint.Style.STROKE; strokeWidth = dp(stroke); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    }
    fun text(sizeDp: Float = 10f, alpha: Float = 1f) = paint(alpha).apply { textSize = dp(sizeDp) }
    fun layers() = Ink.entries.mapNotNull { ink -> bitmaps[ink]?.let { Layer(ink, it) } }

    companion object { const val MAX_PX = 150_000f }
}

object WidgetCharts {
    private val DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

    /** Axis and day labels: 11.5 dp at full ink, up from 10 dp at 90 %, so they read at arm's length. */
    const val LABEL_DP = 11.5f

    fun niceCeil(value: Double): Double =
        doubleArrayOf(1.0, 2.0, 4.0, 5.0, 10.0, 20.0, 40.0, 50.0, 100.0, 200.0).firstOrNull { it >= value } ?: ceil(value)

    /** −30…+60 min around now: P10–P90 band, expected line (past quieter), now marker; axes when there is room. */
    fun minutes(snapshot: Snapshot, now: Instant, widthDp: Float, heightDp: Float, density: Float, units: RainUnits, axes: Boolean,
                textScale: Float = 1f): List<Layer> {
        val masks = Masks(widthDp, heightDp, density)
        val labelSize = LABEL_DP * textScale
        val age = snapshot.age(now)
        val visible = snapshot.minutes.indices.filter { snapshot.minutes[it] - age in -30..60 }
        val top = visible.maxOfOrNull { maxOf(snapshot.p90[it], snapshot.mean[it]).toDouble() } ?: 0.0
        val yMax = niceCeil(maxOf(1.0, top * 1.15))
        val left = if (axes) masks.dp((if (units == RainUnits.WORDS) 46f else 26f) * labelSize / 10f) else masks.dp(2f)
        val right = masks.width - masks.dp(4f)
        val plotTop = if (axes) masks.dp(labelSize + 3f) else masks.dp(3f)
        val bottom = masks.height - if (axes) masks.dp(labelSize + 5f) else masks.dp(2f)
        fun x(min: Int) = left + (min + 30) / 90f * (right - left)
        fun y(value: Double) = bottom - (value / yMax).toFloat().coerceIn(0f, 1.04f) * (bottom - plotTop)

        val muted = masks[Ink.MUTED]
        val grid = masks.paint(0.35f, 1f).apply { pathEffect = DashPathEffect(floatArrayOf(masks.dp(3f), masks.dp(4f)), 0f) }
        muted.drawLine(left, bottom, right, bottom, masks.paint(0.5f, 1f))
        val label = masks.text(labelSize)
        if (axes) {
            val ticks = if (units == RainUnits.WORDS) listOf(2.5 to "moderate", 10.0 to "heavy").filter { it.first <= yMax }
                .ifEmpty { listOf(yMax to "light") }
            else listOf(yMax / 2 to formatRate(yMax / 2), yMax to "${formatRate(yMax)} mm/h")
            // big text on a short chart: the labels would sit on each other, keep only the top one
            for ((value, text) in if (bottom - plotTop < masks.dp(labelSize * 3)) ticks.takeLast(1) else ticks) {
                muted.drawLine(left, y(value), right, y(value), grid)
                muted.drawText(text, masks.dp(1f), y(value) - masks.dp(2.5f), label)     // above its line, never across it
            }
            for (min in listOf(-30, 0, 30, 60)) {
                val text = if (min == 0) "now" else if (min > 0) "+$min" else "$min"
                val textWidth = label.measureText(text)
                muted.drawText(text, (x(min) - textWidth / 2).coerceIn(left, right - textWidth), masks.height - masks.dp(3f), label)
            }
        }
        if (visible.size >= 2) {
            val primary = masks[Ink.PRIMARY]
            val band = Path().apply {
                visible.forEachIndexed { k, i -> val pointX = x(snapshot.minutes[i] - age); val pointY = y(snapshot.p90[i].toDouble()); if (k == 0) moveTo(pointX, pointY) else lineTo(pointX, pointY) }
                visible.asReversed().forEach { i -> lineTo(x(snapshot.minutes[i] - age), y(snapshot.p10[i].toDouble())) }
                close()
            }
            primary.drawPath(band, masks.paint(0.22f))
            fun line(points: List<Int>) = Path().apply {
                points.forEachIndexed { k, i -> val pointX = x(snapshot.minutes[i] - age); val pointY = y(snapshot.mean[i].toDouble()); if (k == 0) moveTo(pointX, pointY) else lineTo(pointX, pointY) }
            }
            val past = visible.filter { snapshot.minutes[it] - age <= 0 }; val future = visible.filter { snapshot.minutes[it] - age >= 0 }
            if (past.size >= 2) muted.drawPath(line(past), masks.paint(0.9f, 1.6f))
            if (future.size >= 2) primary.drawPath(line(future), masks.paint(1f, 2.6f))
        }
        val accent = masks[Ink.ACCENT]
        accent.drawLine(x(0), plotTop, x(0), bottom, masks.paint(1f, 1.4f))
        accent.drawCircle(x(0), plotTop, masks.dp(2.6f), masks.paint())
        return masks.layers()
    }

    /** Half-hour bars from now: height = expected mm (square root, so drizzle shows), colour = chance. */
    fun hours(snapshot: Snapshot, now: Instant, hours: Int, widthDp: Float, heightDp: Float, density: Float, textScale: Float = 1f,
              zone: ZoneId = ZoneId.systemDefault()): List<Layer> {
        val masks = Masks(widthDp, heightDp, density)
        val labelSize = LABEL_DP * textScale
        val count = hours * 2
        val start = OutlookBars.floor30(now).epochSecond
        val byStart = snapshot.bars.associateBy { it.start }
        val slots = List(count) { byStart[start + 1800L * it] }
        val yMax = maxOf(1.0, slots.filterNotNull().maxOfOrNull { it.mm.toDouble() } ?: 0.0)
        val days = heightDp >= 50f          // a 2×1 has no room for a row of day names
        val plotTop = if (days) masks.dp(labelSize + 3f) else masks.dp(3f); val bottom = masks.height - masks.dp(labelSize + 4f)
        val slot = masks.width.toFloat() / count
        val barWidth = (slot * 0.72f).coerceAtLeast(1f)
        val muted = masks[Ink.MUTED]; val base = masks[Ink.BASE]; val primary = masks[Ink.PRIMARY]
        val label = masks.text(labelSize)
        muted.drawLine(0f, bottom, masks.width.toFloat(), bottom, masks.paint(0.5f, 1f))
        val every = if (hours <= 12) 3 else 6
        val solid = masks.paint(); val dot = masks.paint(0.6f)
        slots.forEachIndexed { i, bar ->
            val time = java.time.Instant.ofEpochSecond(start + 1800L * i).atZone(zone)
            val centerX = i * slot + slot / 2
            if (days && ((i == 0 && time.hour < 20) || (time.hour == 0 && time.minute == 0))) {
                if (i > 0) muted.drawLine(i * slot, masks.dp(2f), i * slot, bottom, masks.paint(0.5f, 1f))
                muted.drawText(DAY.format(time), i * slot + if (i > 0) masks.dp(3f) else 0f, masks.dp(labelSize), label)
            }
            if (time.minute == 0 && time.hour % every == 0) {
                val hourText = "%02d".format(time.hour); val textWidth = label.measureText(hourText)
                muted.drawText(hourText, (i * slot - textWidth / 2).coerceIn(0f, masks.width - textWidth), masks.height - masks.dp(2f), label)
            }
            if (bar == null) { muted.drawCircle(centerX, bottom - masks.dp(2f), masks.dp(0.9f), dot); return@forEachIndexed }
            val barHeight = if (bar.mm < 0.02f) masks.dp(1f) else (sqrt((bar.mm / yMax).coerceIn(0.0, 1.0)) * (bottom - plotTop)).toFloat().coerceAtLeast(masks.dp(2f))
            val rect = RectF(centerX - barWidth / 2, bottom - barHeight, centerX + barWidth / 2, bottom)
            base.drawRoundRect(rect, barWidth / 3, barWidth / 3, solid)
            primary.drawRoundRect(rect, barWidth / 3, barWidth / 3, masks.paint((0.15f + 0.85f * bar.probability).coerceIn(0f, 1f)))
            if (bar.nowcast) masks[Ink.ACCENT].drawRect(i * slot + 0.5f, bottom + masks.dp(1f), (i + 1) * slot - 0.5f, bottom + masks.dp(3f), solid)
        }
        return masks.layers()
    }

    /** The radar crop in its own colours (opaque-free ARGB at its native 128 px). */
    fun rain(frame: SFrame, radar: SRadar, lut: IntArray): Bitmap =
        Bitmap.createBitmap(Render.argb(frame.bytes(), lut), radar.width, radar.height, Bitmap.Config.ARGB_8888)

    /** Coast, borders, a 25 km ring and home, as masks the same shape as [rain]. */
    fun radarMarks(ctx: Context, radar: SRadar, sizeDp: Float, density: Float): List<Layer> {
        val masks = Masks(sizeDp, sizeDp * radar.height / radar.width, density)
        val (west, south, east, north) = radar.bbox
        fun mercatorY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
        val mercatorSouth = mercatorY(south); val mercatorNorth = mercatorY(north)
        fun toX(lon: Double) = ((lon - west) / (east - west) * masks.width).toFloat()
        fun toY(lat: Double) = ((mercatorNorth - mercatorY(lat)) / (mercatorNorth - mercatorSouth) * masks.height).toFloat()
        val ink = masks[Ink.MUTED]; val pen = masks.paint(0.75f, 1.1f)
        val margin = 0.5
        for (line in Outline.lines(ctx)) {
            val path = Path(); var open = false
            for (k in line.indices step 2) {
                val lon = line[k].toDouble(); val lat = line[k + 1].toDouble()
                val inside = lon in west - margin..east + margin && lat in south - margin..north + margin
                if (!inside) { open = false; continue }
                if (!open) path.moveTo(toX(lon), toY(lat)) else path.lineTo(toX(lon), toY(lat))
                open = true
            }
            ink.drawPath(path, pen)
        }
        val homeX = toX(radar.homeLon); val homeY = toY(radar.homeLat)
        val ring = 25.0 / Snapshot.HALF_KM * masks.width / 2
        ink.drawCircle(homeX, homeY, ring.toFloat(), masks.paint(0.5f, 1f).apply { pathEffect = DashPathEffect(floatArrayOf(masks.dp(3f), masks.dp(3f)), 0f) })
        val accent = masks[Ink.ACCENT]
        accent.drawCircle(homeX, homeY, masks.dp(5.5f), masks.paint(0.35f))
        accent.drawCircle(homeX, homeY, masks.dp(3f), masks.paint())
        return masks.layers()
    }

    fun formatRate(rate: Double): String = if (rate >= 10 || rate == Math.rint(rate)) "%.0f".format(Locale.ROOT, rate) else "%.1f".format(Locale.ROOT, rate)
}

/** Natural Earth 1:50m coastlines and land borders over Europe (public domain), lon,lat pairs per line. */
object Outline {
    @Volatile private var cache: List<FloatArray>? = null

    fun lines(ctx: Context): List<FloatArray> = cache ?: runCatching {
        ctx.assets.open("outline.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
            val points = line.split(' ')
            FloatArray(points.size * 2).also { coords -> points.forEachIndexed { i, point -> val comma = point.indexOf(','); coords[2 * i] = point.substring(0, comma).toFloat(); coords[2 * i + 1] = point.substring(comma + 1).toFloat() } }
        }
    }.getOrDefault(emptyList()).also { cache = it }
}

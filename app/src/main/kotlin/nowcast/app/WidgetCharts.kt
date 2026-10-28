package nowcast.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import nowcast.engine.OutlookBars
import nowcast.engine.Render
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

private class Masks(wDp: Float, hDp: Float, density: Float) {
    /** Full density up to 2.5×, then down until a mask is at most [MAX_PX]: RemoteViews carry every bitmap. */
    val scale: Float = minOf(density, 2.5f).let { s -> minOf(s, sqrt(MAX_PX / (wDp * hDp).coerceAtLeast(1f))) }
    val w = max(1, (wDp * scale).toInt()); val h = max(1, (hDp * scale).toInt())
    private val maps = linkedMapOf<Ink, Canvas>()
    private val bitmaps = linkedMapOf<Ink, Bitmap>()
    operator fun get(ink: Ink): Canvas = maps.getOrPut(ink) {
        Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { bitmaps[ink] = it }.let(::Canvas)
    }
    fun dp(v: Float) = v * scale
    fun paint(alpha: Float = 1f, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        if (stroke > 0) { style = Paint.Style.STROKE; strokeWidth = dp(stroke); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    }
    fun text(sizeDp: Float = 10f, alpha: Float = 1f) = paint(alpha).apply { textSize = dp(sizeDp) }
    fun layers() = Ink.entries.mapNotNull { i -> bitmaps[i]?.let { Layer(i, it) } }

    companion object { const val MAX_PX = 150_000f }
}

object WidgetCharts {
    private val DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

    fun niceCeil(v: Double): Double =
        doubleArrayOf(1.0, 2.0, 4.0, 5.0, 10.0, 20.0, 40.0, 50.0, 100.0, 200.0).firstOrNull { it >= v } ?: ceil(v)

    /** −30…+60 min around now: P10–P90 band, expected line (past quieter), now marker; axes when there is room. */
    fun minutes(s: Snapshot, now: Instant, wDp: Float, hDp: Float, density: Float, units: RainUnits, axes: Boolean): List<Layer> {
        val m = Masks(wDp, hDp, density)
        val age = s.age(now)
        val vis = s.minutes.indices.filter { s.minutes[it] - age in -30..60 }
        val top = vis.maxOfOrNull { maxOf(s.p90[it], s.mean[it]).toDouble() } ?: 0.0
        val yMax = niceCeil(maxOf(1.0, top * 1.15))
        val left = if (axes) m.dp(if (units == RainUnits.WORDS) 46f else 26f) else m.dp(2f)
        val right = m.w - m.dp(4f)
        val y0 = if (axes) m.dp(8f) else m.dp(3f)
        val bottom = m.h - if (axes) m.dp(15f) else m.dp(2f)
        fun x(min: Int) = left + (min + 30) / 90f * (right - left)
        fun y(v: Double) = bottom - (v / yMax).toFloat().coerceIn(0f, 1.04f) * (bottom - y0)

        val muted = m[Ink.MUTED]
        val grid = m.paint(0.35f, 1f).apply { pathEffect = DashPathEffect(floatArrayOf(m.dp(3f), m.dp(4f)), 0f) }
        muted.drawLine(left, bottom, right, bottom, m.paint(0.5f, 1f))
        val label = m.text(10f, 0.9f)
        if (axes) {
            val ticks = if (units == RainUnits.WORDS) listOf(2.5 to "moderate", 10.0 to "heavy").filter { it.first <= yMax }
                .ifEmpty { listOf(yMax to "light") }
            else listOf(yMax / 2 to fmt(yMax / 2), yMax to "${fmt(yMax)} mm/h")
            for ((v, t) in ticks) {
                muted.drawLine(left, y(v), right, y(v), grid)
                muted.drawText(t, m.dp(1f), (y(v) + m.dp(3.5f)).coerceAtLeast(m.dp(9f)), label)
            }
            for (min in listOf(-30, 0, 30, 60)) {
                val t = if (min == 0) "now" else if (min > 0) "+$min" else "$min"
                val tw = label.measureText(t)
                muted.drawText(t, (x(min) - tw / 2).coerceIn(left, right - tw), m.h - m.dp(3f), label)
            }
        }
        if (vis.size >= 2) {
            val pr = m[Ink.PRIMARY]
            val band = Path().apply {
                vis.forEachIndexed { k, i -> val px = x(s.minutes[i] - age); val py = y(s.p90[i].toDouble()); if (k == 0) moveTo(px, py) else lineTo(px, py) }
                vis.asReversed().forEach { i -> lineTo(x(s.minutes[i] - age), y(s.p10[i].toDouble())) }
                close()
            }
            pr.drawPath(band, m.paint(0.22f))
            fun line(idx: List<Int>) = Path().apply {
                idx.forEachIndexed { k, i -> val px = x(s.minutes[i] - age); val py = y(s.mean[i].toDouble()); if (k == 0) moveTo(px, py) else lineTo(px, py) }
            }
            val past = vis.filter { s.minutes[it] - age <= 0 }; val fut = vis.filter { s.minutes[it] - age >= 0 }
            if (past.size >= 2) muted.drawPath(line(past), m.paint(0.9f, 1.6f))
            if (fut.size >= 2) pr.drawPath(line(fut), m.paint(1f, 2.6f))
        }
        val acc = m[Ink.ACCENT]
        acc.drawLine(x(0), y0, x(0), bottom, m.paint(1f, 1.4f))
        acc.drawCircle(x(0), y0, m.dp(2.6f), m.paint())
        return m.layers()
    }

    /** Half-hour bars from now: height = expected mm (square root, so drizzle shows), colour = chance. */
    fun hours(s: Snapshot, now: Instant, hours: Int, wDp: Float, hDp: Float, density: Float, zone: ZoneId = ZoneId.systemDefault()): List<Layer> {
        val m = Masks(wDp, hDp, density)
        val n = hours * 2
        val t0 = OutlookBars.floor30(now).epochSecond
        val by = s.bars.associateBy { it.start }
        val slots = List(n) { by[t0 + 1800L * it] }
        val yMax = maxOf(1.0, slots.filterNotNull().maxOfOrNull { it.mm.toDouble() } ?: 0.0)
        val y0 = m.dp(13f); val bottom = m.h - m.dp(14f)
        val slot = m.w.toFloat() / n
        val bw = (slot * 0.72f).coerceAtLeast(1f)
        val muted = m[Ink.MUTED]; val base = m[Ink.BASE]; val pr = m[Ink.PRIMARY]
        val label = m.text(10f, 0.9f)
        muted.drawLine(0f, bottom, m.w.toFloat(), bottom, m.paint(0.5f, 1f))
        val every = if (hours <= 12) 3 else 6
        val solid = m.paint(); val dot = m.paint(0.6f)
        slots.forEachIndexed { i, b ->
            val t = java.time.Instant.ofEpochSecond(t0 + 1800L * i).atZone(zone)
            val cx = i * slot + slot / 2
            if ((i == 0 && t.hour < 20) || (t.hour == 0 && t.minute == 0)) {
                if (i > 0) muted.drawLine(i * slot, m.dp(2f), i * slot, bottom, m.paint(0.5f, 1f))
                muted.drawText(DAY.format(t), i * slot + if (i > 0) m.dp(3f) else 0f, m.dp(10f), label)
            }
            if (t.minute == 0 && t.hour % every == 0) {
                val txt = "%02d".format(t.hour); val tw = label.measureText(txt)
                muted.drawText(txt, (i * slot - tw / 2).coerceIn(0f, m.w - tw), m.h - m.dp(2f), label)
            }
            if (b == null) { muted.drawCircle(cx, bottom - m.dp(2f), m.dp(0.9f), dot); return@forEachIndexed }
            val h = if (b.mm < 0.02f) m.dp(1f) else (sqrt((b.mm / yMax).coerceIn(0.0, 1.0)) * (bottom - y0)).toFloat().coerceAtLeast(m.dp(2f))
            val r = RectF(cx - bw / 2, bottom - h, cx + bw / 2, bottom)
            base.drawRoundRect(r, bw / 3, bw / 3, solid)
            pr.drawRoundRect(r, bw / 3, bw / 3, m.paint((0.15f + 0.85f * b.prob).coerceIn(0f, 1f)))
            if (b.nowcast) m[Ink.ACCENT].drawRect(i * slot + 0.5f, bottom + m.dp(1f), (i + 1) * slot - 0.5f, bottom + m.dp(3f), solid)
        }
        return m.layers()
    }

    /** The radar crop in its own colours (opaque-free ARGB at its native 128 px). */
    fun rain(f: SFrame, r: SRadar, palette: String): Bitmap =
        Bitmap.createBitmap(Render.argb(f.bytes(), palette), r.w, r.h, Bitmap.Config.ARGB_8888)

    /** Coast, borders, a 25 km ring and home, as masks the same shape as [rain]. */
    fun radarMarks(ctx: Context, r: SRadar, sizeDp: Float, density: Float): List<Layer> {
        val m = Masks(sizeDp, sizeDp * r.h / r.w, density)
        val (lon0, lat0, lon1, lat1) = r.bbox
        fun my(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
        val my0 = my(lat0); val my1 = my(lat1)
        fun px(lon: Double) = ((lon - lon0) / (lon1 - lon0) * m.w).toFloat()
        fun py(lat: Double) = ((my1 - my(lat)) / (my1 - my0) * m.h).toFloat()
        val ink = m[Ink.MUTED]; val pen = m.paint(0.75f, 1.1f)
        val pad = 0.5
        for (line in Outline.lines(ctx)) {
            val path = Path(); var open = false
            for (k in line.indices step 2) {
                val lon = line[k].toDouble(); val lat = line[k + 1].toDouble()
                val inside = lon in lon0 - pad..lon1 + pad && lat in lat0 - pad..lat1 + pad
                if (!inside) { open = false; continue }
                if (!open) path.moveTo(px(lon), py(lat)) else path.lineTo(px(lon), py(lat))
                open = true
            }
            ink.drawPath(path, pen)
        }
        val cx = px(r.homeLon); val cy = py(r.homeLat)
        val ring = 25.0 / Snapshot.HALF_KM * m.w / 2
        ink.drawCircle(cx, cy, ring.toFloat(), m.paint(0.5f, 1f).apply { pathEffect = DashPathEffect(floatArrayOf(m.dp(3f), m.dp(3f)), 0f) })
        val acc = m[Ink.ACCENT]
        acc.drawCircle(cx, cy, m.dp(5.5f), m.paint(0.35f))
        acc.drawCircle(cx, cy, m.dp(3f), m.paint())
        return m.layers()
    }

    fun fmt(v: Double): String = if (v >= 10 || v == Math.rint(v)) "%.0f".format(Locale.ROOT, v) else "%.1f".format(Locale.ROOT, v)
}

/** Natural Earth 1:50m coastlines and land borders over Europe (public domain), lon,lat pairs per line. */
object Outline {
    @Volatile private var cache: List<FloatArray>? = null

    fun lines(ctx: Context): List<FloatArray> = cache ?: runCatching {
        ctx.assets.open("outline.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map { l ->
            val pts = l.split(' ')
            FloatArray(pts.size * 2).also { a -> pts.forEachIndexed { i, p -> val c = p.indexOf(','); a[2 * i] = p.substring(0, c).toFloat(); a[2 * i + 1] = p.substring(c + 1).toFloat() } }
        }
    }.getOrDefault(emptyList()).also { cache = it }
}

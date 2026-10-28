package nowcast.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import nowcast.engine.PointForecast
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val SPAN = 60   // minutes either side of now

/**
 * −60…+60 min around now: P10–P90 band, min/max, mean line, now marker and a probability strip.
 * Drag or tap to read a minute.
 */
@Composable
fun NowcastGraph(p: PointForecast, issued: Instant, now: Instant, modifier: Modifier = Modifier) {
    val age = Duration.between(issued, now).toMinutes().toInt().coerceAtLeast(0)
    // index range of minutes visible, in "minutes from now"
    val rel = remember(p, age) { IntArray(p.minutes.size) { p.minutes[it] - age } }
    val vis = remember(rel) { rel.indices.filter { rel[it] in -SPAN..SPAN } }
    val yMax = remember(p, vis) {
        val top = vis.maxOfOrNull { maxOf(p.p90[it], p.values[it]) } ?: 0.0
        niceCeil(maxOf(1.0, top * 1.15))
    }
    var sel by remember(p) { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current
    val selAlpha by animateFloatAsState(if (sel != null) 1f else 0f, label = "sel")

    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant)
    val bubble = MaterialTheme.typography.labelMedium.copy(color = cs.inverseOnSurface)

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(220.dp)
                .semantics { contentDescription = "Rain rate graph from an hour ago to an hour ahead. ${p.summary}" }
                .pointerInput(rel) {
                    fun pick(x: Float) {
                        val left = 36.dp.toPx(); val w = size.width - left - 8.dp.toPx()
                        val m = (((x - left) / w) * 2 * SPAN - SPAN).roundToInt().coerceIn(-SPAN, SPAN)
                        val i = rel.indexOfFirst { it == m }.takeIf { it >= 0 }
                        if (i != null && i != sel) { sel = i; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.x)
                        do {
                            val e = awaitPointerEvent()
                            e.changes.firstOrNull()?.let { c ->
                                pick(c.position.x)
                                // horizontal scrubbing is ours; vertical motion goes to the scroll/refresh parent
                                if (kotlin.math.abs(c.position.x - c.previousPosition.x) > kotlin.math.abs(c.position.y - c.previousPosition.y)) c.consume()
                            }
                        } while (e.changes.any { it.pressed })
                        sel = null
                    }
                },
        ) {
            val left = 36.dp.toPx(); val right = size.width - 8.dp.toPx()
            val top = 28.dp.toPx(); val stripH = 10.dp.toPx()
            val axisH = 18.dp.toPx()
            val plotBottom = size.height - axisH - stripH - 8.dp.toPx()
            fun x(m: Int) = left + (m + SPAN) / (2f * SPAN) * (right - left)
            fun y(v: Double) = plotBottom - (v / yMax).toFloat().coerceIn(0f, 1.04f) * (plotBottom - top)

            // grid + y labels
            val ticks = listOf(0.0, yMax / 2, yMax)
            for (t in ticks) {
                drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(left, y(t)), Offset(right, y(t)), 1f,
                    pathEffect = if (t == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                text(measurer, fmt(t), label, Offset(4.dp.toPx(), y(t) - 7.dp.toPx()))
            }
            text(measurer, "mm/h", label, Offset(4.dp.toPx(), 0f))
            // x labels
            for (m in listOf(-60, -30, 0, 30, 60)) {
                val s = if (m == 0) "now" else if (m > 0) "+$m" else "$m"
                val w = measurer.measure(s, label).size.width
                text(measurer, s, label, Offset((x(m) - w / 2f).coerceIn(left - 4f, right - w), size.height - axisH + 2.dp.toPx()))
            }
            // future tint
            drawRect(cs.primary.copy(alpha = 0.05f), Offset(x(0), top), Size(right - x(0), plotBottom - top))

            if (vis.isNotEmpty()) {
                // band
                val band = Path().apply {
                    vis.forEachIndexed { k, i -> if (k == 0) moveTo(x(rel[i]), y(p.p90[i])) else lineTo(x(rel[i]), y(p.p90[i])) }
                    vis.asReversed().forEach { i -> lineTo(x(rel[i]), y(p.p10[i])) }
                    close()
                }
                drawPath(band, cs.primary.copy(alpha = 0.18f))
                line(vis, rel, { p.p90[it] }, ::x, ::y, cs.primary.copy(alpha = 0.45f), 1.dp.toPx())
                line(vis, rel, { p.p10[it] }, ::x, ::y, cs.primary.copy(alpha = 0.45f), 1.dp.toPx())
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
                line(vis, rel, { p.pmax[it] }, ::x, ::y, cs.onSurfaceVariant.copy(alpha = 0.55f), 1.dp.toPx(), dash)
                line(vis, rel, { p.pmin[it] }, ::x, ::y, cs.onSurfaceVariant.copy(alpha = 0.55f), 1.dp.toPx(), dash)
                // mean: observed part quieter than the forecast part
                val past = vis.filter { rel[it] <= 0 }; val fut = vis.filter { rel[it] >= 0 }
                line(past, rel, { p.values[it] }, ::x, ::y, cs.onSurface.copy(alpha = 0.7f), 2.dp.toPx())
                line(fut, rel, { p.values[it] }, ::x, ::y, cs.primary, 3.dp.toPx())

                // probability strip
                val sy = plotBottom + 6.dp.toPx()
                val cw = (right - left) / (2f * SPAN) + 0.5f
                for (i in vis) if (rel[i] >= 0)
                    drawRect(cs.primary.copy(alpha = p.probability[i].toFloat().coerceIn(0f, 1f)), Offset(x(rel[i]), sy), Size(cw, stripH))
                drawRoundRect(cs.outlineVariant, Offset(x(0), sy), Size(right - x(0), stripH), CornerRadius(3.dp.toPx()), style = Stroke(1f))
                text(measurer, "chance", label, Offset((x(0) - measurer.measure("chance", label).size.width - 6.dp.toPx()), sy - 3.dp.toPx()))
            }
            // now marker
            drawLine(cs.tertiary, Offset(x(0), top - 6.dp.toPx()), Offset(x(0), plotBottom), 1.5.dp.toPx())
            drawCircle(cs.tertiary, 3.dp.toPx(), Offset(x(0), top - 6.dp.toPx()))

            // scrub readout
            sel?.let { i ->
                val m = rel[i]; val cx = x(m)
                drawLine(cs.onSurface.copy(alpha = 0.5f * selAlpha), Offset(cx, top), Offset(cx, plotBottom), 1.dp.toPx())
                drawCircle(cs.surface, 6.dp.toPx(), Offset(cx, y(p.values[i]))); drawCircle(cs.primary, 4.dp.toPx(), Offset(cx, y(p.values[i])))
                val t = "${if (m == 0) "now" else if (m > 0) "+$m min" else "$m min"} · ${fmt(p.values[i])} mm/h" +
                    if (m >= 0) " · ${(100 * p.probability[i]).roundToInt()}%" else ""
                val lay = measurer.measure(t, bubble)
                val bw = lay.size.width + 16.dp.toPx(); val bh = lay.size.height + 8.dp.toPx()
                val bx = (cx - bw / 2).coerceIn(left, right - bw)
                drawRoundRect(cs.inverseSurface.copy(alpha = selAlpha), Offset(bx, 0f), Size(bw, bh), CornerRadius(bh / 2))
                drawText(lay, topLeft = Offset(bx + 8.dp.toPx(), 4.dp.toPx()), alpha = selAlpha)
            }
        }
        Legend()
    }
}

@Composable
private fun Legend() {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Key("Expected") { drawLine(cs.primary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(), StrokeCap.Round) }
        Key("80% range") { drawRect(cs.primary.copy(alpha = 0.25f)) }
        Key("Min / max") { drawLine(cs.onSurfaceVariant, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
    }
}

@Composable
private fun Key(text: String, draw: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp, 8.dp), onDraw = draw)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun DrawScope.line(idx: List<Int>, rel: IntArray, v: (Int) -> Double, x: (Int) -> Float, y: (Double) -> Float,
                           c: Color, w: Float, effect: PathEffect? = null) {
    if (idx.size < 2) return
    val path = Path().apply { idx.forEachIndexed { k, i -> if (k == 0) moveTo(x(rel[i]), y(v(i))) else lineTo(x(rel[i]), y(v(i))) } }
    drawPath(path, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun DrawScope.text(m: TextMeasurer, s: String, style: TextStyle, at: Offset) = drawText(m, s, at, style)

fun fmt(v: Double): String = if (v >= 10) "%.0f".format(Locale.ROOT, v) else "%.1f".format(Locale.ROOT, v)

internal fun niceCeil(v: Double): Double {
    val steps = doubleArrayOf(1.0, 2.0, 4.0, 5.0, 10.0, 20.0, 40.0, 50.0, 100.0, 200.0)
    return steps.firstOrNull { it >= v } ?: ceil(v)
}

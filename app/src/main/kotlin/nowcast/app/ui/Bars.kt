package nowcast.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import nowcast.engine.Bar
import nowcast.engine.BarSource
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

private val DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

/**
 * 96 half-hour bars: height = expected mm (square-root scale, so drizzle shows), colour = probability,
 * whisker = P10–P90. Midnight separators, nowcast bars outlined. Tap or drag to select.
 */
@Composable
fun OutlookChart(bars: List<Bar?>, zone: ZoneId, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
                 description: String) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant)
    val haptic = LocalHapticFeedback.current
    val yMax = remember(bars) { maxOf(0.5, bars.filterNotNull().maxOfOrNull { maxOf(it.p90, it.mm) } ?: 0.0) }
    val hl = remember { Animatable(selected?.toFloat() ?: -1f) }
    LaunchedEffect(selected) {
        if (selected == null) return@LaunchedEffect
        if (hl.value < 0) hl.snapTo(selected.toFloat()) else hl.animateTo(selected.toFloat(), spring(stiffness = 900f))
    }
    val n = bars.size.coerceAtLeast(1)

    Canvas(
        modifier.fillMaxWidth().height(190.dp)
            .semantics { contentDescription = description }
            .pointerInput(bars) {
                fun pick(x: Float) {
                    val i = (x / size.width * n).toInt().coerceIn(0, n - 1)
                    if (i != selected && bars.getOrNull(i) != null) { onSelect(i); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
                awaitEachGesture {
                    val d = awaitFirstDown(); pick(d.position.x)
                    do {
                        val e = awaitPointerEvent()
                        e.changes.firstOrNull()?.let { c ->
                            pick(c.position.x)
                            if (abs(c.position.x - c.previousPosition.x) > abs(c.position.y - c.previousPosition.y)) c.consume()
                        }
                    } while (e.changes.any { it.pressed })
                }
            },
    ) {
        val top = 22.dp.toPx(); val bottom = size.height - 20.dp.toPx()
        val slot = size.width / n
        val bw = (slot * 0.72f).coerceAtLeast(1.5f)
        fun y(mm: Double) = bottom - sqrt((mm / yMax).coerceIn(0.0, 1.0)).toFloat() * (bottom - top)

        // selection column
        if (selected != null && hl.value >= 0)
            drawRoundRect(cs.primary.copy(alpha = 0.12f), Offset(hl.value * slot - slot * 0.6f, top - 6.dp.toPx()),
                Size(slot * 2.2f, bottom - top + 6.dp.toPx()), CornerRadius(4.dp.toPx()))

        drawLine(cs.outlineVariant, Offset(0f, bottom), Offset(size.width, bottom), 1f)
        for (frac in listOf(0.25, 1.0)) {
            val yy = y(yMax * frac)
            drawLine(cs.outlineVariant.copy(alpha = 0.5f), Offset(0f, yy), Offset(size.width, yy), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        drawText(measurer, "${fmt(yMax)} mm", Offset(2f, y(yMax) - 16.dp.toPx()), label)

        bars.forEachIndexed { i, b ->
            val cx = i * slot + slot / 2
            if (b == null) {
                drawCircle(cs.outlineVariant, 1.2.dp.toPx(), Offset(cx, bottom - 3.dp.toPx())); return@forEachIndexed
            }
            val t = b.start.atZone(zone)
            if (t.hour == 0 && t.minute == 0 && i > 0) {
                drawLine(cs.outline.copy(alpha = 0.6f), Offset(i * slot, top - 14.dp.toPx()), Offset(i * slot, bottom), 1.dp.toPx())
                drawText(measurer, DAY.format(t), Offset(i * slot + 4.dp.toPx(), top - 20.dp.toPx()), label)
            }
            if (t.minute == 0 && t.hour % 6 == 0) {
                val s = "%02d".format(t.hour)
                val w = measurer.measure(s, label).size.width
                drawText(measurer, s, Offset(i * slot - w / 2f, bottom + 3.dp.toPx()), label)
            }
            val c = lerp(cs.surfaceVariant, cs.primary, (0.15 + 0.85 * b.probability).toFloat().coerceIn(0f, 1f))
            val h = (bottom - y(b.mm)).coerceAtLeast(if (b.probability > 0) 2.dp.toPx() else 1f)
            drawRoundRect(c, Offset(cx - bw / 2, bottom - h), Size(bw, h), CornerRadius(bw / 3))
            if (b.p90 > b.p10 && b.p90 > 0.01)
                drawLine(cs.onSurface.copy(alpha = 0.45f), Offset(cx, y(b.p90)), Offset(cx, y(b.p10)), 1.dp.toPx(), StrokeCap.Round)
            if (b.source == BarSource.NOWCAST)
                drawRoundRect(cs.tertiary, Offset(cx - bw / 2 - 1.5f, top), Size(bw + 3f, bottom - top),
                    CornerRadius(bw / 2), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
        }
        // first bar = now
        drawText(measurer, "now", Offset(0f, bottom + 3.dp.toPx()), label.copy(color = cs.tertiary))
    }
}

package nowcast.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import nowcast.app.MapFrames
import nowcast.engine.PointForecast
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The map's time control and home's rain in one: drag anywhere on it to pick the minute the map shows.
 * Radar on the left, forecast on the right, a tick for each real radar frame, the wall clock as "now".
 * [p] (minutes counted from [pIssued]) is drawn when there is one; the control works without it.
 */
@Composable
fun MapTimeline(f: MapFrames, minute: Int, onMinute: (Int) -> Unit, p: PointForecast?, pIssued: Instant?, now: Instant,
                modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant)
    val haptic = LocalHapticFeedback.current
    val pickMinute by rememberUpdatedState(onMinute)
    val zone = remember { ZoneId.systemDefault() }
    val frameMinutes = remember(f) { f.times.indices.map(f::minuteOf).toSet() }
    // home's curve on the map's minute axis: (map minute, index into p)
    val pts = remember(f, p, pIssued) {
        if (p == null || pIssued == null) emptyList() else p.minutes.indices.mapNotNull { i ->
            val m = Duration.between(f.times[0], pIssued.plusSeconds(60L * p.minutes[i])).toMinutes().toInt()
            if (m in 0..f.minutes) m to i else null
        }
    }
    val yMax = remember(pts) { niceCeil(maxOf(1.0, (pts.maxOfOrNull { (_, i) -> maxOf(p!!.p90[i], p.values[i]) } ?: 0.0) * 1.15)) }
    val nowMin = Duration.between(f.times[0], now).toMinutes().toInt()
    val issuedMin = Duration.between(f.times[0], f.issued).toMinutes().toInt()

    Canvas(modifier.fillMaxWidth().height(104.dp)
        .semantics {
            contentDescription = "Timeline. Drag to choose the time shown on the map."
            stateDescription = HM.format(f.time(minute).atZone(zone))
            setProgress { v -> onMinute(v.roundToInt().coerceIn(0, f.minutes)); true }
        }
        .pointerInput(f) {
            val pad = 12.dp.toPx()
            var last = -1
            fun pick(x: Float) {
                val m = (((x - pad) / (size.width - 2 * pad)) * f.minutes).roundToInt().coerceIn(0, f.minutes)
                if (m != last) {
                    last = m; pickMinute(m)
                    if (m in frameMinutes) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(); pick(down.position.x)
                do {
                    val e = awaitPointerEvent()
                    e.changes.firstOrNull()?.let { c ->
                        pick(c.position.x)
                        if (abs(c.position.x - c.previousPosition.x) > abs(c.position.y - c.previousPosition.y)) c.consume()
                    }
                } while (e.changes.any { it.pressed })
            }
        }) {
        val left = 12.dp.toPx(); val right = size.width - 12.dp.toPx()
        val top = 16.dp.toPx(); val bottom = size.height - 26.dp.toPx()
        fun x(m: Int) = left + m.toFloat() / f.minutes.coerceAtLeast(1) * (right - left)
        fun y(v: Double) = bottom - (v / yMax).toFloat().coerceIn(0f, 1.04f) * (bottom - top)
        val split = x(issuedMin.coerceIn(0, f.minutes))

        drawRoundRect(cs.onSurface.copy(alpha = 0.05f), Offset(left, top), Size(split - left, bottom - top), CornerRadius(6.dp.toPx()))
        drawRoundRect(cs.primary.copy(alpha = 0.10f), Offset(split, top), Size(right - split, bottom - top), CornerRadius(6.dp.toPx()))
        drawText(measurer, "RADAR", Offset(left + 6.dp.toPx(), top + 3.dp.toPx()), label)
        drawText(measurer, "FORECAST", Offset(split + 6.dp.toPx(), top + 3.dp.toPx()), label.copy(color = cs.primary))

        if (pts.isNotEmpty()) {
            val q = p!!
            val band = Path().apply {
                pts.forEachIndexed { k, (m, i) -> if (k == 0) moveTo(x(m), y(q.p90[i])) else lineTo(x(m), y(q.p90[i])) }
                pts.asReversed().forEach { (m, i) -> lineTo(x(m), y(q.p10[i])) }
                close()
            }
            drawPath(band, cs.primary.copy(alpha = 0.18f))
            val line = Path().apply { pts.forEachIndexed { k, (m, i) -> if (k == 0) moveTo(x(m), y(q.values[i])) else lineTo(x(m), y(q.values[i])) } }
            drawPath(line, cs.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            // above the plot, clear of the RADAR / FORECAST labels; the "now" label is near the middle
            val peak = pts.maxOf { (_, i) -> q.values[i] }
            val s = if (peak < 0.1) "home: dry" else "home: up to ${fmt(peak)} mm/h"
            val w = measurer.measure(s, label).size.width
            drawText(measurer, s, Offset(right - w, 0f), label.copy(color = cs.primary))
        }

        // axis: a tick per radar frame, clock times on the half hour
        drawLine(cs.outlineVariant, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        for (m in frameMinutes) drawLine(cs.onSurfaceVariant.copy(alpha = 0.6f), Offset(x(m), bottom), Offset(x(m), bottom + 4.dp.toPx()), 1.dp.toPx())
        for (m in 0..f.minutes) {
            val t = f.time(m).atZone(zone)
            if (t.minute % 30 != 0) continue
            val s = HM.format(t); val w = measurer.measure(s, label).size.width
            drawText(measurer, s, Offset((x(m) - w / 2f).coerceIn(0f, size.width - w), bottom + 6.dp.toPx()), label)
        }

        if (nowMin in 0..f.minutes) {
            val nx = x(nowMin)
            drawLine(cs.tertiary, Offset(nx, top), Offset(nx, bottom), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            val w = measurer.measure("now", label).size.width
            drawText(measurer, "now", Offset((nx - w / 2f).coerceIn(0f, size.width - w), 0f), label.copy(color = cs.tertiary))
        }

        val cx = x(minute)
        drawLine(cs.onSurface, Offset(cx, top - 2.dp.toPx()), Offset(cx, bottom), 2.dp.toPx())
        drawCircle(cs.surface, 9.dp.toPx(), Offset(cx, bottom)); drawCircle(cs.onSurface, 7.dp.toPx(), Offset(cx, bottom))
    }
}

package nowcast.app.ui.map

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
import nowcast.app.data.*
import nowcast.app.ui.now.*
import nowcast.engine.forecast.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The map's time control and home's rain in one: drag anywhere on it to pick the minute the map shows.
 * Radar on the left, forecast on the right, a tick for each real radar frame, the wall clock as "now".
 * [point] (minutes counted from [pointIssued]) is drawn when there is one; the control works without it.
 */
@Composable
fun MapTimeline(frames: MapFrames, minute: Int, onMinute: (Int) -> Unit, point: PointForecast?, pointIssued: Instant?, now: Instant,
                modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val haptic = LocalHapticFeedback.current
    val pickMinute by rememberUpdatedState(onMinute)
    val zone = remember { ZoneId.systemDefault() }
    val frameMinutes = remember(frames) { frames.times.indices.map(frames::minuteOf).toSet() }
    // home's curve on the map's minute axis: (map minute, index into p)
    val homeCurve = remember(frames, point, pointIssued) {
        if (point == null || pointIssued == null) emptyList() else point.minutes.indices.mapNotNull { i ->
            val mapMinute = Duration.between(frames.times[0], pointIssued.plusSeconds(60L * point.minutes[i])).toMinutes().toInt()
            if (mapMinute in 0..frames.minutes) mapMinute to i else null
        }
    }
    val yMax = remember(homeCurve) { niceCeil(maxOf(1.0, (homeCurve.maxOfOrNull { (_, i) -> maxOf(point!!.p90[i], point.values[i]) } ?: 0.0) * 1.15)) }
    val nowMin = Duration.between(frames.times[0], now).toMinutes().toInt()
    val issuedMin = Duration.between(frames.times[0], frames.issued).toMinutes().toInt()

    Canvas(modifier.fillMaxWidth().height(104.dp)
        .semantics {
            contentDescription = "Timeline. Drag to choose the time shown on the map."
            stateDescription = TIME_FORMAT.format(frames.time(minute).atZone(zone))
            setProgress { progress -> onMinute(progress.roundToInt().coerceIn(0, frames.minutes)); true }
        }
        .pointerInput(frames) {
            val pad = 12.dp.toPx()
            var last = -1
            fun pick(x: Float) {
                val picked = (((x - pad) / (size.width - 2 * pad)) * frames.minutes).roundToInt().coerceIn(0, frames.minutes)
                if (picked != last) {
                    last = picked; pickMinute(picked)
                    if (picked in frameMinutes) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
            awaitEachGesture {
                val down = awaitFirstDown(); pick(down.position.x)
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.let { change ->
                        pick(change.position.x)
                        if (abs(change.position.x - change.previousPosition.x) > abs(change.position.y - change.previousPosition.y)) change.consume()
                    }
                } while (event.changes.any { it.pressed })
            }
        }) {
        val left = 12.dp.toPx(); val right = size.width - 12.dp.toPx()
        val top = 16.dp.toPx(); val bottom = size.height - 26.dp.toPx()
        fun x(mapMinute: Int) = left + mapMinute.toFloat() / frames.minutes.coerceAtLeast(1) * (right - left)
        fun y(value: Double) = bottom - (value / yMax).toFloat().coerceIn(0f, 1.04f) * (bottom - top)
        val split = x(issuedMin.coerceIn(0, frames.minutes))

        drawRoundRect(colors.onSurface.copy(alpha = 0.05f), Offset(left, top), Size(split - left, bottom - top), CornerRadius(6.dp.toPx()))
        drawRoundRect(colors.primary.copy(alpha = 0.10f), Offset(split, top), Size(right - split, bottom - top), CornerRadius(6.dp.toPx()))
        drawText(measurer, "RADAR", Offset(left + 6.dp.toPx(), top + 3.dp.toPx()), labelStyle)
        drawText(measurer, "FORECAST", Offset(split + 6.dp.toPx(), top + 3.dp.toPx()), labelStyle.copy(color = colors.primary))

        if (homeCurve.isNotEmpty()) {
            val home = point!!
            val band = Path().apply {
                homeCurve.forEachIndexed { k, (mapMinute, i) -> if (k == 0) moveTo(x(mapMinute), y(home.p90[i])) else lineTo(x(mapMinute), y(home.p90[i])) }
                homeCurve.asReversed().forEach { (mapMinute, i) -> lineTo(x(mapMinute), y(home.p10[i])) }
                close()
            }
            drawPath(band, colors.primary.copy(alpha = 0.18f))
            val line = Path().apply { homeCurve.forEachIndexed { k, (mapMinute, i) -> if (k == 0) moveTo(x(mapMinute), y(home.values[i])) else lineTo(x(mapMinute), y(home.values[i])) } }
            drawPath(line, colors.primary, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            // above the plot, clear of the RADAR / FORECAST labels; the "now" label is near the middle
            val peak = homeCurve.maxOf { (_, i) -> home.values[i] }
            val caption = if (peak < 0.1) "home: dry" else "home: up to ${formatRate(peak)} mm/h"
            val textWidth = measurer.measure(caption, labelStyle).size.width
            drawText(measurer, caption, Offset(right - textWidth, 0f), labelStyle.copy(color = colors.primary))
        }

        // axis: a tick per radar frame, clock times on the half hour
        drawLine(colors.outlineVariant, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        for (mapMinute in frameMinutes) drawLine(colors.onSurfaceVariant.copy(alpha = 0.6f), Offset(x(mapMinute), bottom), Offset(x(mapMinute), bottom + 4.dp.toPx()), 1.dp.toPx())
        for (mapMinute in 0..frames.minutes) {
            val time = frames.time(mapMinute).atZone(zone)
            if (time.minute % 30 != 0) continue
            val caption = TIME_FORMAT.format(time); val textWidth = measurer.measure(caption, labelStyle).size.width
            drawText(measurer, caption, Offset((x(mapMinute) - textWidth / 2f).coerceIn(0f, size.width - textWidth), bottom + 6.dp.toPx()), labelStyle)
        }

        if (nowMin in 0..frames.minutes) {
            val nowX = x(nowMin)
            drawLine(colors.tertiary, Offset(nowX, top), Offset(nowX, bottom), 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            val textWidth = measurer.measure("now", labelStyle).size.width
            drawText(measurer, "now", Offset((nowX - textWidth / 2f).coerceIn(0f, size.width - textWidth), 0f), labelStyle.copy(color = colors.tertiary))
        }

        val selectedX = x(minute)
        drawLine(colors.onSurface, Offset(selectedX, top - 2.dp.toPx()), Offset(selectedX, bottom), 2.dp.toPx())
        drawCircle(colors.surface, 9.dp.toPx(), Offset(selectedX, bottom)); drawCircle(colors.onSurface, 7.dp.toPx(), Offset(selectedX, bottom))
    }
}

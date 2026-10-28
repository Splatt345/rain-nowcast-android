package nowcast.app.ui.now

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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import nowcast.engine.outlook.*
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
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val haptic = LocalHapticFeedback.current
    val yMax = remember(bars) { maxOf(1.0, bars.filterNotNull().maxOfOrNull { maxOf(it.p90, it.mm) } ?: 0.0) }
    val highlight = remember { Animatable(selected?.toFloat() ?: -1f) }
    LaunchedEffect(selected) {
        if (selected == null) return@LaunchedEffect
        if (highlight.value < 0) highlight.snapTo(selected.toFloat()) else highlight.animateTo(selected.toFloat(), spring(stiffness = 900f))
    }
    val count = bars.size.coerceAtLeast(1)

    Canvas(
        modifier.fillMaxWidth().height(190.dp)
            .semantics { contentDescription = description }
            .pointerInput(bars) {
                fun pick(x: Float) {
                    val index = (x / size.width * count).toInt().coerceIn(0, count - 1)
                    if (index != selected && bars.getOrNull(index) != null) { onSelect(index); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
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
            },
    ) {
        val top = 22.dp.toPx(); val bottom = size.height - 20.dp.toPx()
        val slot = size.width / count
        val barWidth = (slot * 0.72f).coerceAtLeast(1.5f)
        fun y(mm: Double) = bottom - sqrt((mm / yMax).coerceIn(0.0, 1.0)).toFloat() * (bottom - top)

        if (selected != null && highlight.value >= 0)
            drawRoundRect(colors.primary.copy(alpha = 0.12f), Offset(highlight.value * slot - slot * 0.6f, top - 6.dp.toPx()),
                Size(slot * 2.2f, bottom - top + 6.dp.toPx()), CornerRadius(4.dp.toPx()))

        drawLine(colors.outlineVariant, Offset(0f, bottom), Offset(size.width, bottom), 1f)
        for (frac in listOf(0.25, 1.0)) {
            val lineY = y(yMax * frac)
            drawLine(colors.outlineVariant.copy(alpha = 0.5f), Offset(0f, lineY), Offset(size.width, lineY), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
        }
        val maxLabel = measurer.measure("${formatRate(yMax)} mm", labelStyle)
        // under the line at the left edge: day labels own the row above, and no midnight divider is drawn at x = 0
        drawText(maxLabel, topLeft = Offset(0f, y(yMax) + 2.dp.toPx()))

        bars.forEachIndexed { i, bar ->
            val centerX = i * slot + slot / 2
            if (bar == null) {
                drawCircle(colors.outlineVariant, 1.2.dp.toPx(), Offset(centerX, bottom - 3.dp.toPx())); return@forEachIndexed
            }
            val time = bar.start.atZone(zone)
            if ((i == 0 && (time.hour < 20 || time.hour == 0)) || (time.hour == 0 && time.minute == 0)) {   // no first-day label crammed before midnight
                if (i > 0) drawLine(colors.outline.copy(alpha = 0.6f), Offset(i * slot, top - 14.dp.toPx()), Offset(i * slot, bottom), 1.dp.toPx())
                drawText(measurer, DAY.format(time), Offset(i * slot + (if (i > 0) 4.dp.toPx() else 0f), top - 20.dp.toPx()),
                    labelStyle.copy(color = colors.onSurface))
            }
            if (time.minute == 0 && time.hour % 6 == 0) {
                val hourText = "%02d".format(time.hour)
                val textWidth = measurer.measure(hourText, labelStyle).size.width
                drawText(measurer, hourText, Offset((i * slot - textWidth / 2f).coerceAtLeast(0f), bottom + 5.dp.toPx()), labelStyle)
            }
            val barColor = lerp(colors.surfaceVariant, colors.primary, (0.15 + 0.85 * bar.probability).toFloat().coerceIn(0f, 1f))
            // under 0.02 mm is dry for anyone standing in it: a hairline, not a bar
            val barHeight = if (bar.mm < 0.02) 1.dp.toPx() else (bottom - y(bar.mm)).coerceAtLeast(2.dp.toPx())
            drawRoundRect(barColor, Offset(centerX - barWidth / 2, bottom - barHeight), Size(barWidth, barHeight), CornerRadius(barWidth / 3))
            // the 80% range only where it reaches wet (> 0.1 mm), or every dry half hour grows a whisker
            if (bar.p90 > bar.p10 && bar.p90 > OutlookBars.WET_MM)
                drawLine(colors.onSurface.copy(alpha = 0.45f), Offset(centerX, y(bar.p90)), Offset(centerX, y(bar.p10)), 1.dp.toPx(), StrokeCap.Round)
            if (bar.source == BarSource.NOWCAST)      // radar-based half hours are underlined
                drawRoundRect(colors.tertiary, Offset(i * slot + 0.5f, bottom + 1.dp.toPx()), Size(slot - 1f, 3.dp.toPx()), CornerRadius(2f))
        }
    }
}

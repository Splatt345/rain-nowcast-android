package nowcast.app.ui.now

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
import nowcast.engine.forecast.*
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
fun NowcastGraph(point: PointForecast, issued: Instant, now: Instant, modifier: Modifier = Modifier) {
    val age = Duration.between(issued, now).toMinutes().toInt().coerceAtLeast(0)
    // index range of minutes visible, in "minutes from now"
    val fromNow = remember(point, age) { IntArray(point.minutes.size) { point.minutes[it] - age } }
    val visible = remember(fromNow) { fromNow.indices.filter { fromNow[it] in -SPAN..SPAN } }
    val yMax = remember(point, visible) {
        val top = visible.maxOfOrNull { maxOf(point.p90[it], point.values[it]) } ?: 0.0
        niceCeil(maxOf(1.0, top * 1.15))
    }
    var selected by remember(point) { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current
    val selectedAlpha by animateFloatAsState(if (selected != null) 1f else 0f, label = "sel")

    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val bubbleStyle = MaterialTheme.typography.labelMedium.copy(color = colors.inverseOnSurface)

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(220.dp)
                .semantics { contentDescription = "Rain rate graph from an hour ago to an hour ahead. ${point.summary}" }
                .pointerInput(fromNow) {
                    fun pick(x: Float) {
                        val left = 36.dp.toPx(); val plotWidth = size.width - left - 8.dp.toPx()
                        val minute = (((x - left) / plotWidth) * 2 * SPAN - SPAN).roundToInt().coerceIn(-SPAN, SPAN)
                        val index = fromNow.indexOfFirst { it == minute }.takeIf { it >= 0 }
                        if (index != null && index != selected) { selected = index; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.x)
                        do {
                            val event = awaitPointerEvent()
                            event.changes.firstOrNull()?.let { change ->
                                pick(change.position.x)
                                // horizontal scrubbing is ours; vertical motion goes to the scroll/refresh parent
                                if (kotlin.math.abs(change.position.x - change.previousPosition.x) > kotlin.math.abs(change.position.y - change.previousPosition.y)) change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        selected = null
                    }
                },
        ) {
            val left = 36.dp.toPx(); val right = size.width - 8.dp.toPx()
            val top = 28.dp.toPx(); val stripHeight = 10.dp.toPx()
            val axisHeight = 18.dp.toPx()
            val plotBottom = size.height - axisHeight - stripHeight - 8.dp.toPx()
            fun x(minute: Int) = left + (minute + SPAN) / (2f * SPAN) * (right - left)
            fun y(value: Double) = plotBottom - (value / yMax).toFloat().coerceIn(0f, 1.04f) * (plotBottom - top)

            val ticks = listOf(0.0, yMax / 2, yMax)
            for (tick in ticks) {
                drawLine(colors.outlineVariant.copy(alpha = 0.6f), Offset(left, y(tick)), Offset(right, y(tick)), 1f,
                    pathEffect = if (tick == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                text(measurer, formatRate(tick), axisStyle, Offset(4.dp.toPx(), y(tick) - 7.dp.toPx()))
            }
            text(measurer, "mm/h", axisStyle, Offset(4.dp.toPx(), 0f))
            for (minute in listOf(-60, -30, 0, 30, 60)) {
                val caption = if (minute == 0) "now" else if (minute > 0) "+$minute" else "$minute"
                val labelWidth = measurer.measure(caption, axisStyle).size.width
                text(measurer, caption, axisStyle, Offset((x(minute) - labelWidth / 2f).coerceIn(left - 4f, right - labelWidth), size.height - axisHeight + 2.dp.toPx()))
            }
            drawRect(colors.primary.copy(alpha = 0.05f), Offset(x(0), top), Size(right - x(0), plotBottom - top))

            if (visible.isNotEmpty()) {
                val band = Path().apply {
                    visible.forEachIndexed { k, i -> if (k == 0) moveTo(x(fromNow[i]), y(point.p90[i])) else lineTo(x(fromNow[i]), y(point.p90[i])) }
                    visible.asReversed().forEach { i -> lineTo(x(fromNow[i]), y(point.p10[i])) }
                    close()
                }
                drawPath(band, colors.primary.copy(alpha = 0.18f))
                line(visible, fromNow, { point.p90[it] }, ::x, ::y, colors.primary.copy(alpha = 0.45f), 1.dp.toPx())
                line(visible, fromNow, { point.p10[it] }, ::x, ::y, colors.primary.copy(alpha = 0.45f), 1.dp.toPx())
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
                line(visible, fromNow, { point.pmax[it] }, ::x, ::y, colors.onSurfaceVariant.copy(alpha = 0.55f), 1.dp.toPx(), dash)
                line(visible, fromNow, { point.pmin[it] }, ::x, ::y, colors.onSurfaceVariant.copy(alpha = 0.55f), 1.dp.toPx(), dash)
                // mean: observed part quieter than the forecast part
                val past = visible.filter { fromNow[it] <= 0 }; val future = visible.filter { fromNow[it] >= 0 }
                line(past, fromNow, { point.values[it] }, ::x, ::y, colors.onSurface.copy(alpha = 0.7f), 2.dp.toPx())
                line(future, fromNow, { point.values[it] }, ::x, ::y, colors.primary, 3.dp.toPx())

                val stripY = plotBottom + 6.dp.toPx()
                val cellWidth = (right - left) / (2f * SPAN) + 0.5f
                for (i in visible) if (fromNow[i] >= 0)
                    drawRect(colors.primary.copy(alpha = point.probability[i].toFloat().coerceIn(0f, 1f)), Offset(x(fromNow[i]), stripY), Size(cellWidth, stripHeight))
                drawRoundRect(colors.outlineVariant, Offset(x(0), stripY), Size(right - x(0), stripHeight), CornerRadius(3.dp.toPx()), style = Stroke(1f))
                text(measurer, "chance", axisStyle, Offset((x(0) - measurer.measure("chance", axisStyle).size.width - 6.dp.toPx()), stripY - 3.dp.toPx()))
            }
            drawLine(colors.tertiary, Offset(x(0), top - 6.dp.toPx()), Offset(x(0), plotBottom), 1.5.dp.toPx())
            drawCircle(colors.tertiary, 3.dp.toPx(), Offset(x(0), top - 6.dp.toPx()))

            selected?.let { index ->
                val minute = fromNow[index]; val selectedX = x(minute)
                drawLine(colors.onSurface.copy(alpha = 0.5f * selectedAlpha), Offset(selectedX, top), Offset(selectedX, plotBottom), 1.dp.toPx())
                drawCircle(colors.surface, 6.dp.toPx(), Offset(selectedX, y(point.values[index]))); drawCircle(colors.primary, 4.dp.toPx(), Offset(selectedX, y(point.values[index])))
                val caption = "${if (minute == 0) "now" else if (minute > 0) "+$minute min" else "$minute min"} · ${formatRate(point.values[index])} mm/h" +
                    if (minute >= 0) " · ${(100 * point.probability[index]).roundToInt()}%" else ""
                val layout = measurer.measure(caption, bubbleStyle)
                val bubbleWidth = layout.size.width + 16.dp.toPx(); val bubbleHeight = layout.size.height + 8.dp.toPx()
                val bubbleX = (selectedX - bubbleWidth / 2).coerceIn(left, right - bubbleWidth)
                drawRoundRect(colors.inverseSurface.copy(alpha = selectedAlpha), Offset(bubbleX, 0f), Size(bubbleWidth, bubbleHeight), CornerRadius(bubbleHeight / 2))
                drawText(layout, topLeft = Offset(bubbleX + 8.dp.toPx(), 4.dp.toPx()), alpha = selectedAlpha)
            }
        }
        Legend()
    }
}

@Composable
private fun Legend() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Key("Expected") { drawLine(colors.primary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 3.dp.toPx(), StrokeCap.Round) }
        Key("80% range") { drawRect(colors.primary.copy(alpha = 0.25f)) }
        Key("Min / max") { drawLine(colors.onSurfaceVariant, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(),
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

private fun DrawScope.line(indices: List<Int>, rel: IntArray, valueAt: (Int) -> Double, x: (Int) -> Float, y: (Double) -> Float,
                           color: Color, width: Float, effect: PathEffect? = null) {
    if (indices.size < 2) return
    val path = Path().apply { indices.forEachIndexed { k, i -> if (k == 0) moveTo(x(rel[i]), y(valueAt(i))) else lineTo(x(rel[i]), y(valueAt(i))) } }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun DrawScope.text(measurer: TextMeasurer, content: String, style: TextStyle, at: Offset) = drawText(measurer, content, at, style)

fun formatRate(value: Double): String = if (value >= 10) "%.0f".format(Locale.ROOT, value) else "%.1f".format(Locale.ROOT, value)

internal fun niceCeil(value: Double): Double {
    val steps = doubleArrayOf(1.0, 2.0, 4.0, 5.0, 10.0, 20.0, 40.0, 50.0, 100.0, 200.0)
    return steps.firstOrNull { it >= value } ?: ceil(value)
}

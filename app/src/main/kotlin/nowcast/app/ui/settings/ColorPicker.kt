package nowcast.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nowcast.app.data.*
import nowcast.engine.render.*

private fun rgbColor(rgb: Int) = Color(0xFF000000.toInt() or rgb)

private val SWATCHES = listOf(0x1F5FA8, 0x2E7D7B, 0x3A8D3F, 0x8A6D1F, 0xC0592B, 0xB3261E, 0xA8326E, 0x6A4FB8, 0x46535F)

/** Hue, saturation and brightness sliders, a hex field and a few swatches. Returns RGB. */
@Composable
fun ColorPickerDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial or 0xFF000000.toInt(), it) } }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF
    var hex by remember { mutableStateOf(Settings.hex(initial)) }
    fun set(c: Int) {
        val t = FloatArray(3).also { android.graphics.Color.colorToHSV(c or 0xFF000000.toInt(), it) }
        h = t[0]; s = t[1]; v = t[2]; hex = Settings.hex(c)
    }
    AlertDialog(onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(rgbColor(rgb)))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SWATCHES.forEach { c ->
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(rgbColor(c)).clickable { set(c) }
                            .semantics { contentDescription = "#${Settings.hex(c)}" })
                    }
                }
                Channel("Hue", h, 0f..360f, Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })) {
                    h = it; hex = Settings.hex(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
                }
                Channel("Saturation", s, 0f..1f, Brush.horizontalGradient(listOf(Color.hsv(h, 0f, v), Color.hsv(h, 1f, v)))) {
                    s = it; hex = Settings.hex(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
                }
                Channel("Brightness", v, 0f..1f, Brush.horizontalGradient(listOf(Color.Black, Color.hsv(h, s, 1f)))) {
                    v = it; hex = Settings.hex(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
                }
                OutlinedTextField(hex, { t -> hex = t.take(6); Settings.parseHex(t)?.let { c -> set(c); hex = t.take(6) } },
                    singleLine = true, label = { Text("Hex") }, prefix = { Text("#") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ onPick(rgb) }) { Text("Use colour") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun Channel(label: String, value: Float, range: ClosedFloatingPointRange<Float>, track: Brush, set: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(track))
            Slider(value, set, valueRange = range, modifier = Modifier.semantics { contentDescription = label },
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    thumbColor = MaterialTheme.colorScheme.onSurface))
        }
    }
}

private fun rateLabel(r: Double) = if (r % 1.0 == 0.0) "${r.toInt()} mm/h" else "$r mm/h"

/** One colour per rain rate; tap a row to change it. Saving selects the custom scale. */
@Composable
fun RadarColorsDialog(current: List<Int>, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    var colors by remember { mutableStateOf(current) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val rates = Render.PALETTES.getValue(Render.DEFAULT_PALETTE).map { it.first }
    AlertDialog(onDismiss, title = { Text("Custom radar colours") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val lut = remember(colors) { Render.lut(Render.stops(colors)) }
                Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(Brush.horizontalGradient((0..255 step 15).map { rgbColor(lut[it]) })))
                Spacer(Modifier.height(6.dp))
                rates.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editing = i }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(rgbColor(colors[i]))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        Spacer(Modifier.width(14.dp))
                        Text(rateLabel(r), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("#" + Settings.hex(colors[i]), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton({ colors = Settings.DEFAULT_RADAR }) { Text("Start from blue-green") }
            }
        },
        confirmButton = { TextButton({ onSave(colors) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
    editing?.let { i ->
        ColorPickerDialog("Colour for ${rateLabel(rates[i])}", colors[i], { editing = null }) { c ->
            colors = colors.toMutableList().also { it[i] = c }; editing = null
        }
    }
}

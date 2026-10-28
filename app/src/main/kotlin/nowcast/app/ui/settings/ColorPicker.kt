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
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var brightness by remember { mutableFloatStateOf(start[2]) }
    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF
    var hex by remember { mutableStateOf(Settings.hex(initial)) }
    fun set(color: Int) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color or 0xFF000000.toInt(), it) }
        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]; hex = Settings.hex(color)
    }
    AlertDialog(onDismiss, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(rgbColor(rgb)))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SWATCHES.forEach { swatch ->
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape).background(rgbColor(swatch)).clickable { set(swatch) }
                            .semantics { contentDescription = "#${Settings.hex(swatch)}" })
                    }
                }
                Channel("Hue", hue, 0f..360f, Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })) {
                    hue = it; hex = Settings.hex(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))
                }
                Channel("Saturation", saturation, 0f..1f, Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, brightness), Color.hsv(hue, 1f, brightness)))) {
                    saturation = it; hex = Settings.hex(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))
                }
                Channel("Brightness", brightness, 0f..1f, Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hue, saturation, 1f)))) {
                    brightness = it; hex = Settings.hex(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))
                }
                OutlinedTextField(hex, { typed -> hex = typed.take(6); Settings.parseHex(typed)?.let { color -> set(color); hex = typed.take(6) } },
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

private fun rateLabel(rate: Double) = if (rate % 1.0 == 0.0) "${rate.toInt()} mm/h" else "$rate mm/h"

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
                rates.forEachIndexed { i, rate ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editing = i }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(rgbColor(colors[i]))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        Spacer(Modifier.width(14.dp))
                        Text(rateLabel(rate), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
        ColorPickerDialog("Colour for ${rateLabel(rates[i])}", colors[i], { editing = null }) { color ->
            colors = colors.toMutableList().also { it[i] = color }; editing = null
        }
    }
}

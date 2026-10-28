package nowcast.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** Asks for the MeteoGate ORD key, or goes on with Open-Meteo, which needs none. */
@Composable
fun FirstRun(onKey: (String) -> Unit, onOpenMeteo: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp).widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RainArt(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(32.dp)))
            Text("RAIN NOWCAST", style = Overline, color = MaterialTheme.colorScheme.primary)
            Text("Rain, minute by minute, worked out on your phone.", style = MaterialTheme.typography.headlineSmall)
            Text("The forecast runs here, from the last hour of European radar (EUMETNET OPERA). " +
                "The radar comes from MeteoGate's Open Radar Data API, which needs a free personal key.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                key, { key = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text("ORD API key") }, singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) onKey(key) }),
                trailingIcon = {
                    IconButton({ keyVisible = !keyVisible }) {
                        Icon(if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (keyVisible) "Hide key" else "Show key")
                    }
                },
                supportingText = { Text("Stored encrypted on this device and sent only to MeteoGate.") },
            )
            Button({ onKey(key) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = key.isNotBlank()) { Text("Continue") }
            TextButton({ uri.openUri("https://meteogate.eu") }, Modifier.heightIn(min = 48.dp)) {
                Text("Get a key at meteogate.eu"); Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
            }
            TextButton(onOpenMeteo, Modifier.heightIn(min = 48.dp)) { Text("No key? Use Open-Meteo's forecast instead (no radar map)") }
        }
    }
}

@Composable
private fun RainArt(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val drops = remember { val random = Random(7); List(46) { Triple(random.nextFloat(), random.nextFloat(), 0.4f + random.nextFloat() * 0.6f) } }
    Canvas(modifier.semantics { contentDescription = "" }) {
        drawRect(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surfaceContainerHigh)))
        val center = Offset(size.width * 0.52f, size.height * 0.36f)
        val radius = size.minDimension * 0.17f
        listOf(Offset(-1.3f, 0.25f) to 0.75f, Offset(-0.3f, -0.1f) to 1f, Offset(0.8f, 0.12f) to 0.85f, Offset(1.6f, 0.38f) to 0.6f)
            .forEach { (offset, scale) -> drawCircle(colors.primary, radius * scale, Offset(center.x + offset.x * radius, center.y + offset.y * radius)) }
        drawRect(colors.primary, Offset(center.x - 1.3f * radius, center.y + 0.2f * radius), androidx.compose.ui.geometry.Size(2.9f * radius, 0.75f * radius))
        for ((x, y, strength) in drops) {
            val dropX = size.width * x; val dropY = size.height * (0.55f + y * 0.45f)
            drawLine(colors.primary.copy(alpha = 0.35f * strength), Offset(dropX, dropY), Offset(dropX - 6f, dropY + 22f * strength), 3f, StrokeCap.Round)
        }
        val stripY = size.height - 22f
        for (i in 0 until 24) {
            val level = (kotlin.math.sin(i / 3.5f) * 0.5f + 0.5f)
            drawRoundRect(colors.primary.copy(alpha = 0.15f + 0.75f * level), Offset(24f + i * (size.width - 48f) / 24, stripY),
                androidx.compose.ui.geometry.Size((size.width - 48f) / 24 - 4f, 10f), androidx.compose.ui.geometry.CornerRadius(4f))
        }
    }
}

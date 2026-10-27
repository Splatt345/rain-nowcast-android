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

/** Asks for the MeteoGate ORD key; nothing else works without it. */
@Composable
fun FirstRun(onKey: (String) -> Unit, onDemo: (() -> Unit)?) {
    var key by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
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
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) onKey(key) }),
                trailingIcon = {
                    IconButton({ show = !show }) {
                        Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (show) "Hide key" else "Show key")
                    }
                },
                supportingText = { Text("Stored encrypted on this device and sent only to MeteoGate.") },
            )
            Button({ onKey(key) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = key.isNotBlank()) { Text("Continue") }
            TextButton({ uri.openUri("https://meteogate.eu") }, Modifier.heightIn(min = 48.dp)) {
                Text("Get a key at meteogate.eu"); Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
            }
            if (onDemo != null) TextButton(onDemo, Modifier.heightIn(min = 48.dp)) { Text("Try it with the bundled sample radar (debug)") }
        }
    }
}

@Composable
private fun RainArt(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val drops = remember { val r = Random(7); List(46) { Triple(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * 0.6f) } }
    Canvas(modifier.semantics { contentDescription = "" }) {
        drawRect(Brush.verticalGradient(listOf(cs.primaryContainer, cs.surfaceContainerHigh)))
        // cloud
        val c = Offset(size.width * 0.52f, size.height * 0.36f)
        val r = size.minDimension * 0.17f
        listOf(Offset(-1.3f, 0.25f) to 0.75f, Offset(-0.3f, -0.1f) to 1f, Offset(0.8f, 0.12f) to 0.85f, Offset(1.6f, 0.38f) to 0.6f)
            .forEach { (o, s) -> drawCircle(cs.primary.copy(alpha = 0.9f), r * s, Offset(c.x + o.x * r, c.y + o.y * r)) }
        drawRect(cs.primary.copy(alpha = 0.9f), Offset(c.x - 1.3f * r, c.y + 0.2f * r), androidx.compose.ui.geometry.Size(2.9f * r, 0.75f * r))
        // rain streaks
        for ((x, y, a) in drops) {
            val px = size.width * x; val py = size.height * (0.55f + y * 0.45f)
            drawLine(cs.primary.copy(alpha = 0.35f * a), Offset(px, py), Offset(px - 6f, py + 22f * a), 3f, StrokeCap.Round)
        }
        // probability strip, the app's own motif
        val sy = size.height - 22f
        for (i in 0 until 24) {
            val p = (kotlin.math.sin(i / 3.5f) * 0.5f + 0.5f)
            drawRoundRect(cs.primary.copy(alpha = 0.15f + 0.75f * p), Offset(24f + i * (size.width - 48f) / 24, sy),
                androidx.compose.ui.geometry.Size((size.width - 48f) / 24 - 4f, 10f), androidx.compose.ui.geometry.CornerRadius(4f))
        }
    }
}

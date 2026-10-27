package nowcast.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider as GlanceColor
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Summary sentence + next-hour probability bar, from the snapshot Repo writes after every cycle. */
class RainWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val sp = context.getSharedPreferences(Repo.WIDGET, Context.MODE_PRIVATE)
        val summary = sp.getString("summary", null) ?: "Open Rain Nowcast to load the forecast"
        val probs = sp.getString("probs", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: List(12) { 0.0 }
        val issued = sp.getLong("issued", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        val region = sp.getString("region", null)
        provideContent { GlanceTheme { Content(summary, probs, issued, region) } }
    }

    @androidx.compose.runtime.Composable
    private fun Content(summary: String, probs: List<Double>, issued: Instant?, region: String?) {
        val chance = Math.rint(100 * (probs.maxOrNull() ?: 0.0)).toInt()
        Column(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp)
                .padding(16.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$chance%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(10.dp))
                Text(listOfNotNull(region, issued?.let { "radar " + HM.format(it.atZone(ZoneId.systemDefault())) }).joinToString(" · "),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(summary, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(10.dp))
            Row(GlanceModifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "Rain chance next hour, up to $chance%" }) {
                probs.forEachIndexed { i, p ->
                    Box(GlanceModifier.defaultWeight().fillMaxHeight().cornerRadius(3.dp).background(cell(p))) {}
                    if (i < probs.size - 1) Spacer(GlanceModifier.width(3.dp))
                }
            }
            Spacer(GlanceModifier.height(4.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Text("now", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
                Spacer(GlanceModifier.defaultWeight())
                Text("+60 min", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
            }
        }
    }

    private fun cell(p: Double): GlanceColor {
        fun mix(a: Color, b: Color) = Color(
            a.red + (b.red - a.red) * p.toFloat(), a.green + (b.green - a.green) * p.toFloat(), a.blue + (b.blue - a.blue) * p.toFloat())
        return ColorProvider(day = mix(Color(0xFFDDE5EF), Color(0xFF1F5FA8)), night = mix(Color(0xFF26313D), Color(0xFF9CC7FF)))
    }

    private companion object { val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm") }
}

class RainWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RainWidget()
}

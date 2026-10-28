package nowcast.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.BundleCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColor
import nowcast.app.*
import nowcast.app.R
import nowcast.app.background.*
import nowcast.app.data.*
import nowcast.app.ui.*
import nowcast.app.ui.now.*
import nowcast.engine.forecast.*
import nowcast.engine.outlook.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

/**
 * One implementation behind five picker entries; each entry only sets the type a new widget starts with.
 * Picker previews are previewImage/previewLayout: Glance's generated previews drop the chart images.
 * Options live in the widget's Glance state, the data in [Snapshot]; [Widgets.REV] bumps make a running
 * session re-read both.
 */
abstract class RainWidgetBase(val defaultType: WidgetType) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(WidgetLayout.BUCKETS.map { DpSize(it.first.dp, it.second.dp) }.toSet())

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val opts = Widgets.options(prefs, defaultType)
            val snap = remember(prefs[Widgets.REV]) { Snapshot.read(context.filesDir) }
            WidgetContent(context, opts, snap, Instant.now(), actualSizes(LocalAppWidgetOptions.current))
        }
    }
}

class RainWidget : RainWidgetBase(WidgetType.SUMMARY)
class MinutesWidget : RainWidgetBase(WidgetType.MINUTES)
class HoursWidget : RainWidgetBase(WidgetType.HOURS)
class RadarWidget : RainWidgetBase(WidgetType.RADAR)
class CombinedWidget : RainWidgetBase(WidgetType.COMBINED)

/** RainWidget is the Summary widget. */
class RainWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = RainWidget() }
class MinutesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MinutesWidget() }
class HoursWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = HoursWidget() }
class RadarWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = RadarWidget() }
class CombinedWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = CombinedWidget() }

object Widgets {
    val REV = longPreferencesKey("rev")
    val ALL: List<Pair<Class<out GlanceAppWidgetReceiver>, RainWidgetBase>> = listOf(
        RainWidgetReceiver::class.java to RainWidget(), MinutesWidgetReceiver::class.java to MinutesWidget(),
        HoursWidgetReceiver::class.java to HoursWidget(), RadarWidgetReceiver::class.java to RadarWidget(),
        CombinedWidgetReceiver::class.java to CombinedWidget())

    fun options(prefs: Preferences, default: WidgetType) =
        WidgetOptions.fromMap(prefs.asMap().mapKeys { it.key.name }, default)

    /** The widget class an appWidgetId was placed from. */
    fun forAppWidget(ctx: Context, appWidgetId: Int): RainWidgetBase {
        val cls = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(appWidgetId)?.provider?.className
        return ALL.firstOrNull { it.first.name == cls }?.second ?: ALL.first().second
    }

    /** Re-render every widget: after each refresh (full or box run, new data or not) and on option changes. */
    suspend fun updateAll(ctx: Context) {
        val m = GlanceAppWidgetManager(ctx)
        val rev = System.currentTimeMillis()
        for ((_, w) in ALL) for (id in m.getGlanceIds(w.javaClass)) {
            updateAppWidgetState(ctx, id) { it[REV] = rev }
            w.update(ctx, id)
        }
    }
}

class RefreshNow : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = RefreshWorker.now(context)
}

/** Sizes the launcher really shows (portrait and landscape), so bitmaps match them rather than the bucket. */
fun actualSizes(b: Bundle): List<DpSize> {
    if (Build.VERSION.SDK_INT >= 31) BundleCompat.getParcelableArrayList(b, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        ?.takeIf { it.isNotEmpty() }?.let { l -> return l.map { DpSize(it.width.dp, it.height.dp) } }
    val minW = b.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val maxW = b.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
    val minH = b.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT); val maxH = b.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
    if (minW == 0 || minH == 0) return emptyList()
    return listOf(DpSize(minW.dp, maxH.dp), DpSize(maxW.dp, minH.dp))
}

private fun schemes(ctx: Context, t: WidgetTheme, s: Settings): Pair<ColorScheme, ColorScheme> {
    val (light, dark) = appSchemes(ctx, s)
    return when (t) {
        WidgetTheme.LIGHT -> light to light
        WidgetTheme.DARK -> dark to dark
        WidgetTheme.DYNAMIC -> if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(ctx) to dynamicDarkColorScheme(ctx) else AppLight to AppDark
        WidgetTheme.SYSTEM -> light to dark
    }
}

private fun tapAction(ctx: Context, t: TapAction): Action = when (t) {
    TapAction.OPEN_NOW -> actionStartActivity(MainActivity.intent(ctx, Tab.NOW))
    TapAction.OPEN_MAP -> actionStartActivity(MainActivity.intent(ctx, Tab.MAP))
    TapAction.REFRESH -> actionRunCallback<RefreshNow>()
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val PAD = 12.dp

/** Everything a layout needs, computed once per composition. */
private class W(val ctx: Context, val o: WidgetOptions, val s: Snapshot, val now: Instant,
                val light: ColorScheme, val dark: ColorScheme, wDp: Float, hDp: Float, settings: Settings) {
    val age = s.age(now)
    val density = ctx.resources.displayMetrics.density
    val chance = if (o.showChance) s.chance(now) else null
    val sentence = RainWords.widget(s.summary(now), o.units, chance != null)
    /** One line on a 2×1. */
    val short = RainWords.short(s.summary(now))
    /** Text scale: fonts and the room reserved for text grow together, the charts take what is left. */
    val t = o.text.scale
    fun sp(v: Int) = (v * t).sp
    fun px(v: Int) = v * t
    val lut by lazy { settings.radarLut() }
    /** Inner size, inside the padding. */
    val iw = wDp - 2 * PAD.value; val ih = hDp - 2 * PAD.value
    fun mix(p: Float): GlanceColor = ColorProvider(day = lerp(light.surfaceVariant, light.primary, p.coerceIn(0f, 1f)),
        night = lerp(dark.surfaceVariant, dark.primary, p.coerceIn(0f, 1f)))
}

@Composable
private fun WidgetContent(ctx: Context, o: WidgetOptions, snap: Snapshot?, now: Instant, actual: List<DpSize>) {
    val bucket = LocalSize.current
    val b = WidgetLayout.bucket(bucket.width.value, bucket.height.value)
    val mine = actual.filter { WidgetLayout.bucket(it.width.value, it.height.value) == b }
    // the launcher never shows a bucket no real size maps to: skip its bitmaps, RemoteViews carry all of them
    if (actual.isNotEmpty() && mine.isEmpty()) { Box(GlanceModifier.fillMaxSize()) {}; return }
    val target = mine.maxByOrNull { it.width.value * it.height.value } ?: bucket
    val settings = (ctx.applicationContext as? App)?.repo?.prefs?.value ?: Settings()
    val (light, dark) = schemes(ctx, o.theme, settings)
    val a = o.opacity / 100f
    GlanceTheme(colors = ColorProviders(light, dark)) {
        val (type, cls) = WidgetLayout.pick(o.type, target.width.value, target.height.value)
        Box(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                .background(ColorProvider(day = light.surface.copy(alpha = a), night = dark.surface.copy(alpha = a)))
                .clickable(tapAction(ctx, if (snap == null) TapAction.OPEN_NOW else o.tap)),
        ) {
            // a bottom button gets its own strip: charts and axis labels run into that corner
            val reserve = if (o.refresh == RefreshButton.BOTTOM && cls != SizeClass.SMALL) 22f else 0f
            Box(GlanceModifier.fillMaxSize().padding(start = PAD, top = PAD, end = PAD, bottom = PAD + reserve.dp),
                contentAlignment = Alignment.CenterStart) {
                if (snap == null) { Empty("No radar yet · open the app", cls, o.text.scale); return@Box }
                val w = W(ctx, o, snap, now, light, dark, target.width.value, target.height.value - reserve, settings)
                when (type) {
                    WidgetType.SUMMARY -> Summary(w, cls)
                    WidgetType.MINUTES -> Minutes(w, cls)
                    WidgetType.HOURS -> Hours(w, cls)
                    WidgetType.RADAR -> Radar(w, cls)
                    WidgetType.COMBINED -> Combined(w)
                }
            }
            if (o.refresh != RefreshButton.OFF) RefreshButtonOverlay(o.refresh, cls)
        }
    }
}

/** A small round button in a corner, over the content; runs one refresh whatever background updates are set to. */
@Composable
private fun RefreshButtonOverlay(at: RefreshButton, cls: SizeClass) {
    val d = if (cls == SizeClass.SMALL) 24 else 30
    Box(GlanceModifier.fillMaxSize().padding(4.dp),
        contentAlignment = if (at == RefreshButton.TOP) Alignment.TopEnd else Alignment.BottomEnd) {
        Image(ImageProvider(R.drawable.ic_refresh), contentDescription = "Refresh now",
            modifier = GlanceModifier.size(d.dp).cornerRadius((d / 2).dp).background(GlanceTheme.colors.secondaryContainer)
                .padding((d / 5).dp).clickable(actionRunCallback<RefreshNow>()),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer))
    }
}

@Composable
private fun Empty(msg: String, cls: SizeClass, t: Float) {
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (cls != SizeClass.SMALL) Text("Rain Nowcast", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = (13 * t).sp, fontWeight = FontWeight.Medium))
        Text(msg, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = ((if (cls == SizeClass.SMALL) 13 else 16) * t).sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun AgeText(w: W, size: Int = 13, prefix: String? = null) {
    val stale = Freshness.stale(w.age)
    Text(listOfNotNull(prefix?.takeIf { it != w.s.source }, Freshness.text(w.age, w.s.source)).joinToString(" · "), maxLines = 1,
        style = TextStyle(color = if (stale) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
            fontSize = w.sp(size), fontWeight = if (stale) FontWeight.Medium else FontWeight.Normal))
}

/** "40%" + region · age. */
@Composable
private fun Header(w: W, chanceSp: Int = 26) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        w.chance?.let {
            Text("$it%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = w.sp(chanceSp), fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(10.dp))
        }
        AgeText(w, prefix = w.s.region)
    }
}

@Composable
private fun Sentence(w: W, text: String = w.sentence, sp: Int = 15, lines: Int = 2) =
    Text(text, maxLines = lines, modifier = GlanceModifier.fillMaxWidth(),
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = w.sp(sp), fontWeight = FontWeight.Medium))

@Composable
private fun Chart(layers: List<Layer>, desc: String, modifier: GlanceModifier) {
    val c = GlanceTheme.colors
    Box(modifier.semantics { contentDescription = desc }) {
        layers.forEach { l ->
            val tint = when (l.ink) { Ink.MUTED -> c.onSurfaceVariant; Ink.BASE -> c.surfaceVariant; Ink.PRIMARY -> c.primary; Ink.ACCENT -> c.tertiary }
            Image(ImageProvider(l.bitmap), contentDescription = null, modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(tint))
        }
    }
}

@Composable
private fun MinuteChart(w: W, hDp: Float, axes: Boolean) {
    val h = hDp.coerceAtLeast(16f)
    Chart(WidgetCharts.minutes(w.s, w.now, w.iw, h, w.density, w.o.units, axes, w.t),
        "Rain rate from 30 minutes ago to an hour ahead. ${w.sentence}", GlanceModifier.fillMaxWidth().height(h.dp))
}

@Composable
private fun HourChart(w: W, hours: Int, hDp: Float) {
    val h = hDp.coerceAtLeast(20f)
    Chart(WidgetCharts.hours(w.s, w.now, hours, w.iw, h, w.density, w.t),
        "Rain over the next $hours hours, in half hours; darker is likelier. ${hoursSentence(w)}",
        GlanceModifier.fillMaxWidth().height(h.dp))
}

private fun hoursSentence(w: W): String {
    val t0 = OutlookBars.floor30(w.now)
    return OutlookBars.sentence(w.s.engineBars().filter { !it.start.isBefore(t0) }, ZoneId.systemDefault())
}

@Composable
private fun Summary(w: W, cls: SizeClass) {
    if (cls == SizeClass.SMALL) {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            w.chance?.let {
                Text("$it%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = w.sp(if (w.t > 1.2f) 20 else 24), fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(8.dp))
            }
            Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                // larger text: the sentence gets two lines and the age shows only when it is a warning
                val roomy = w.t == 1f
                // a 2×1 has 56 dp inside: two lines fit up to Large, so Extra large stays at Large here
                Sentence(w, w.short, sp = if (w.t > 1.2f) 12 else 14, lines = if (roomy) 1 else 2)
                if (roomy || Freshness.stale(w.age)) AgeText(w, 12)
            }
        }
        return
    }
    val big = cls == SizeClass.LARGE
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Header(w, if (big) 34 else 26)
        Spacer(GlanceModifier.height(4.dp))
        Sentence(w, sp = if (big) 18 else 15, lines = if (big) 3 else 2)
        Spacer(GlanceModifier.height(if (big) 16.dp else 10.dp))
        val a = w.age
        val cells = (0 until 6).map { k -> w.s.minutes.indices.filter { w.s.minutes[it] - a in k * 10 until k * 10 + 10 }.maxOfOrNull { w.s.prob[it] } ?: 0f }
        Row(GlanceModifier.fillMaxWidth().height(if (big) 16.dp else 10.dp)
            .semantics { contentDescription = "Rain chance in each 10 minutes of the next hour, up to ${Math.rint(100.0 * (cells.maxOrNull() ?: 0f)).toInt()}%" }) {
            // Glance rows hold at most 10 children: gaps are padding, not spacers
            cells.forEachIndexed { i, p ->
                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(end = if (i < cells.size - 1) 4.dp else 0.dp)) {
                    Box(GlanceModifier.fillMaxSize().cornerRadius(3.dp).background(w.mix(p))) {}
                }
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            Text("now", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = w.sp(12)))
            Spacer(GlanceModifier.defaultWeight())
            Text("+60 min", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = w.sp(12)))
        }
        if (big) {
            Spacer(GlanceModifier.height(18.dp))
            Text("Next 12 h", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = w.sp(12)))
            HourChart(w, 12, 64f)
        }
    }
}

@Composable
private fun Minutes(w: W, cls: SizeClass) {
    if (cls == SizeClass.SMALL) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                w.chance?.let { Text("$it% ", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = w.sp(14), fontWeight = FontWeight.Bold)) }
                Text(w.short, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = w.sp(13), fontWeight = FontWeight.Medium))
            }
            MinuteChart(w, w.ih - w.px(19), axes = false)
        }
        return
    }
    val big = cls == SizeClass.LARGE
    Column(GlanceModifier.fillMaxSize()) {
        Header(w, if (big) 26 else 18)
        Sentence(w, sp = if (big) 15 else 13, lines = 2)
        Spacer(GlanceModifier.height(4.dp))
        MinuteChart(w, w.ih - w.px((if (big) 34 else 24) + (if (big) 40 else 36)) - 4, axes = true)
    }
}

@Composable
private fun Hours(w: W, cls: SizeClass) {
    val hours = WidgetLayout.hours(cls)
    Column(GlanceModifier.fillMaxSize()) {
        if (cls == SizeClass.SMALL) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Next 12\u00A0h", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = w.sp(13), fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.defaultWeight())
                AgeText(w, 12)
            }
            HourChart(w, hours, w.ih - w.px(17))
        } else {
            val big = cls == SizeClass.LARGE
            Sentence(w, RainWords.widget(hoursSentence(w), RainUnits.MMH, false), sp = if (big) 16 else 13, lines = 2)
            AgeText(w, 12, prefix = "Next $hours\u00A0h")
            Spacer(GlanceModifier.height(6.dp))
            HourChart(w, hours, w.ih - w.px((if (big) 42 else 36) + 16) - 6)
        }
    }
}

@Composable
private fun Radar(w: W, cls: SizeClass) {
    val r = w.s.radar
    val f = r?.frame(w.o.lead)
    if (r == null || f == null) {
        Empty(if (w.s.source == Snapshot.SOURCE_OPEN_METEO) "No radar map in Open-Meteo mode" else "No radar yet · open the app", cls, w.t)
        return
    }
    val t = HM.format(Instant.ofEpochSecond(w.s.issued + 60L * f.lead).atZone(ZoneId.systemDefault()))
    val what = if (f.lead == 0) "Radar $t" else "Forecast $t (+${f.lead} min)"
    if (cls == SizeClass.LARGE) {
        Column(GlanceModifier.fillMaxSize()) {
            Header(w, 20)
            Sentence(w, sp = 13, lines = 2)
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RadarMap(w, r, f, min(w.iw, w.ih - w.px(28 + 36 + 20) - 6), what)
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(what, modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = w.sp(13), textAlign = androidx.glance.text.TextAlign.Center))
        }
        return
    }
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        RadarMap(w, r, f, min(w.ih, w.iw * 0.45f), what)
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            if (cls == SizeClass.MEDIUM) {
                w.chance?.let { Text("$it%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = w.sp(20), fontWeight = FontWeight.Bold)) }
                Sentence(w, sp = 13, lines = 3)
                Spacer(GlanceModifier.height(4.dp))
            }
            Text(what, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = w.sp(13), fontWeight = FontWeight.Medium))
            AgeText(w, 12)
        }
    }
}

@Composable
private fun RadarMap(w: W, r: SRadar, f: SFrame, sizeDp: Float, what: String) {
    val s = sizeDp.coerceAtLeast(24f)
    Box(GlanceModifier.size(s.dp).cornerRadius(14.dp).background(GlanceTheme.colors.surfaceVariant)) {
        Image(ImageProvider(WidgetCharts.rain(f, r, w.lut)), contentDescription = null, modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
        Chart(WidgetCharts.radarMarks(w.ctx, r, s, w.density),
            "$what around home, ${Snapshot.HALF_KM.toInt() * 2} km across. ${w.sentence}", GlanceModifier.fillMaxSize())
    }
}

@Composable
private fun Combined(w: W) {
    Column(GlanceModifier.fillMaxSize()) {
        Header(w, 26)
        Sentence(w, sp = 15, lines = 2)
        Spacer(GlanceModifier.height(4.dp))
        val bars = 62f
        MinuteChart(w, w.ih - w.px(34 + 40) - 4 - 8 - bars, axes = true)
        Spacer(GlanceModifier.height(8.dp))
        HourChart(w, 12, bars)
    }
}

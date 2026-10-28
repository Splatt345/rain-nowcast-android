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
            val options = Widgets.options(prefs, defaultType)
            val snapshot = remember(prefs[Widgets.REV]) { Snapshot.read(context.filesDir) }
            WidgetContent(context, options, snapshot, Instant.now(), actualSizes(LocalAppWidgetOptions.current))
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
        val className = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(appWidgetId)?.provider?.className
        return ALL.firstOrNull { it.first.name == className }?.second ?: ALL.first().second
    }

    /** Re-render every widget: after each refresh (full or box run, new data or not) and on option changes. */
    suspend fun updateAll(ctx: Context) {
        val manager = GlanceAppWidgetManager(ctx)
        val rev = System.currentTimeMillis()
        for ((_, widget) in ALL) for (id in manager.getGlanceIds(widget.javaClass)) {
            updateAppWidgetState(ctx, id) { it[REV] = rev }
            widget.update(ctx, id)
        }
    }
}

class RefreshNow : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = RefreshWorker.now(context)
}

/** Sizes the launcher really shows (portrait and landscape), so bitmaps match them rather than the bucket. */
fun actualSizes(bundle: Bundle): List<DpSize> {
    if (Build.VERSION.SDK_INT >= 31) BundleCompat.getParcelableArrayList(bundle, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        ?.takeIf { it.isNotEmpty() }?.let { sizes -> return sizes.map { DpSize(it.width.dp, it.height.dp) } }
    val minWidth = bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val maxWidth = bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
    val minHeight = bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT); val maxHeight = bundle.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
    if (minWidth == 0 || minHeight == 0) return emptyList()
    return listOf(DpSize(minWidth.dp, maxHeight.dp), DpSize(maxWidth.dp, minHeight.dp))
}

private fun schemes(ctx: Context, theme: WidgetTheme, settings: Settings): Pair<ColorScheme, ColorScheme> {
    val (light, dark) = appSchemes(ctx, settings)
    return when (theme) {
        WidgetTheme.LIGHT -> light to light
        WidgetTheme.DARK -> dark to dark
        WidgetTheme.DYNAMIC -> if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(ctx) to dynamicDarkColorScheme(ctx) else AppLight to AppDark
        WidgetTheme.SYSTEM -> light to dark
    }
}

private fun tapAction(ctx: Context, action: TapAction): Action = when (action) {
    TapAction.OPEN_NOW -> actionStartActivity(MainActivity.intent(ctx, Tab.NOW))
    TapAction.OPEN_MAP -> actionStartActivity(MainActivity.intent(ctx, Tab.MAP))
    TapAction.REFRESH -> actionRunCallback<RefreshNow>()
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val PAD = 12.dp

/** Everything a layout needs, computed once per composition. */
private class WidgetView(val ctx: Context, val options: WidgetOptions, val snapshot: Snapshot, val now: Instant,
                val light: ColorScheme, val dark: ColorScheme, widthDp: Float, heightDp: Float, settings: Settings) {
    val age = snapshot.age(now)
    val density = ctx.resources.displayMetrics.density
    val chance = if (options.showChance) snapshot.chance(now) else null
    val sentence = RainWords.widget(snapshot.summary(now), options.units, chance != null)
    /** One line on a 2×1. */
    val short = RainWords.short(snapshot.summary(now))
    /** Text scale: fonts and the room reserved for text grow together, the charts take what is left. */
    val textScale = options.text.scale
    fun sp(value: Int) = (value * textScale).sp
    fun px(value: Int) = value * textScale
    val lut by lazy { settings.radarLut() }
    /** Inner size, inside the padding. */
    val innerWidth = widthDp - 2 * PAD.value; val innerHeight = heightDp - 2 * PAD.value
    fun mix(chance: Float): GlanceColor = ColorProvider(day = lerp(light.surfaceVariant, light.primary, chance.coerceIn(0f, 1f)),
        night = lerp(dark.surfaceVariant, dark.primary, chance.coerceIn(0f, 1f)))
}

@Composable
private fun WidgetContent(ctx: Context, options: WidgetOptions, snapshot: Snapshot?, now: Instant, actual: List<DpSize>) {
    val bucket = LocalSize.current
    val shownBucket = WidgetLayout.bucket(bucket.width.value, bucket.height.value)
    val mine = actual.filter { WidgetLayout.bucket(it.width.value, it.height.value) == shownBucket }
    // the launcher never shows a bucket no real size maps to: skip its bitmaps, RemoteViews carry all of them
    if (actual.isNotEmpty() && mine.isEmpty()) { Box(GlanceModifier.fillMaxSize()) {}; return }
    val target = mine.maxByOrNull { it.width.value * it.height.value } ?: bucket
    val settings = (ctx.applicationContext as? App)?.repo?.prefs?.value ?: Settings()
    val (light, dark) = schemes(ctx, options.theme, settings)
    val surfaceAlpha = options.opacity / 100f
    GlanceTheme(colors = ColorProviders(light, dark)) {
        val (type, sizeClass) = WidgetLayout.pick(options.type, target.width.value, target.height.value)
        Box(
            GlanceModifier.fillMaxSize().cornerRadius(24.dp)
                .background(ColorProvider(day = light.surface.copy(alpha = surfaceAlpha), night = dark.surface.copy(alpha = surfaceAlpha)))
                .clickable(tapAction(ctx, if (snapshot == null) TapAction.OPEN_NOW else options.tap)),
        ) {
            // a bottom button gets its own strip: charts and axis labels run into that corner
            val reserve = if (options.refresh == RefreshButton.BOTTOM && sizeClass != SizeClass.SMALL) 22f else 0f
            Box(GlanceModifier.fillMaxSize().padding(start = PAD, top = PAD, end = PAD, bottom = PAD + reserve.dp),
                contentAlignment = Alignment.CenterStart) {
                if (snapshot == null) { Empty("No radar yet · open the app", sizeClass, options.text.scale); return@Box }
                val view = WidgetView(ctx, options, snapshot, now, light, dark, target.width.value, target.height.value - reserve, settings)
                when (type) {
                    WidgetType.SUMMARY -> Summary(view, sizeClass)
                    WidgetType.MINUTES -> Minutes(view, sizeClass)
                    WidgetType.HOURS -> Hours(view, sizeClass)
                    WidgetType.RADAR -> Radar(view, sizeClass)
                    WidgetType.COMBINED -> Combined(view)
                }
            }
            if (options.refresh != RefreshButton.OFF) RefreshButtonOverlay(options.refresh, sizeClass)
        }
    }
}

/** A small round button in a corner, over the content; runs one refresh whatever background updates are set to. */
@Composable
private fun RefreshButtonOverlay(at: RefreshButton, sizeClass: SizeClass) {
    val diameter = if (sizeClass == SizeClass.SMALL) 24 else 30
    Box(GlanceModifier.fillMaxSize().padding(4.dp),
        contentAlignment = if (at == RefreshButton.TOP) Alignment.TopEnd else Alignment.BottomEnd) {
        Image(ImageProvider(R.drawable.ic_refresh), contentDescription = "Refresh now",
            modifier = GlanceModifier.size(diameter.dp).cornerRadius((diameter / 2).dp).background(GlanceTheme.colors.secondaryContainer)
                .padding((diameter / 5).dp).clickable(actionRunCallback<RefreshNow>()),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer))
    }
}

@Composable
private fun Empty(message: String, sizeClass: SizeClass, textScale: Float) {
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (sizeClass != SizeClass.SMALL) Text("Rain Nowcast", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = (13 * textScale).sp, fontWeight = FontWeight.Medium))
        Text(message, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = ((if (sizeClass == SizeClass.SMALL) 13 else 16) * textScale).sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun AgeText(view: WidgetView, size: Int = 13, prefix: String? = null) {
    val stale = Freshness.stale(view.age)
    Text(listOfNotNull(prefix?.takeIf { it != view.snapshot.source }, Freshness.text(view.age, view.snapshot.source)).joinToString(" · "), maxLines = 1,
        style = TextStyle(color = if (stale) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
            fontSize = view.sp(size), fontWeight = if (stale) FontWeight.Medium else FontWeight.Normal))
}

/** "40%" + region · age. */
@Composable
private fun Header(view: WidgetView, chanceSp: Int = 26) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        view.chance?.let {
            Text("$it%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = view.sp(chanceSp), fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(10.dp))
        }
        AgeText(view, prefix = view.snapshot.region)
    }
}

@Composable
private fun Sentence(view: WidgetView, text: String = view.sentence, sp: Int = 15, lines: Int = 2) =
    Text(text, maxLines = lines, modifier = GlanceModifier.fillMaxWidth(),
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = view.sp(sp), fontWeight = FontWeight.Medium))

@Composable
private fun Chart(layers: List<Layer>, desc: String, modifier: GlanceModifier) {
    val colors = GlanceTheme.colors
    Box(modifier.semantics { contentDescription = desc }) {
        layers.forEach { layer ->
            val tint = when (layer.ink) { Ink.MUTED -> colors.onSurfaceVariant; Ink.BASE -> colors.surfaceVariant; Ink.PRIMARY -> colors.primary; Ink.ACCENT -> colors.tertiary }
            Image(ImageProvider(layer.bitmap), contentDescription = null, modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(tint))
        }
    }
}

@Composable
private fun MinuteChart(view: WidgetView, heightDp: Float, axes: Boolean) {
    val height = heightDp.coerceAtLeast(16f)
    Chart(WidgetCharts.minutes(view.snapshot, view.now, view.innerWidth, height, view.density, view.options.units, axes, view.textScale),
        "Rain rate from 30 minutes ago to an hour ahead. ${view.sentence}", GlanceModifier.fillMaxWidth().height(height.dp))
}

@Composable
private fun HourChart(view: WidgetView, hours: Int, heightDp: Float) {
    val height = heightDp.coerceAtLeast(20f)
    Chart(WidgetCharts.hours(view.snapshot, view.now, hours, view.innerWidth, height, view.density, view.textScale),
        "Rain over the next $hours hours, in half hours; darker is likelier. ${hoursSentence(view)}",
        GlanceModifier.fillMaxWidth().height(height.dp))
}

private fun hoursSentence(view: WidgetView): String {
    val start = OutlookBars.floor30(view.now)
    return OutlookBars.sentence(view.snapshot.engineBars().filter { !it.start.isBefore(start) }, ZoneId.systemDefault())
}

@Composable
private fun Summary(view: WidgetView, sizeClass: SizeClass) {
    if (sizeClass == SizeClass.SMALL) {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            view.chance?.let {
                Text("$it%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = view.sp(if (view.textScale > 1.2f) 20 else 24), fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(8.dp))
            }
            Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                // larger text: the sentence gets two lines and the age shows only when it is a warning
                val roomy = view.textScale == 1f
                // a 2×1 has 56 dp inside: two lines fit up to Large, so Extra large stays at Large here
                Sentence(view, view.short, sp = if (view.textScale > 1.2f) 12 else 14, lines = if (roomy) 1 else 2)
                if (roomy || Freshness.stale(view.age)) AgeText(view, 12)
            }
        }
        return
    }
    val big = sizeClass == SizeClass.LARGE
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Header(view, if (big) 34 else 26)
        Spacer(GlanceModifier.height(4.dp))
        Sentence(view, sp = if (big) 18 else 15, lines = if (big) 3 else 2)
        Spacer(GlanceModifier.height(if (big) 16.dp else 10.dp))
        val age = view.age
        val cells = (0 until 6).map { k -> view.snapshot.minutes.indices.filter { view.snapshot.minutes[it] - age in k * 10 until k * 10 + 10 }.maxOfOrNull { view.snapshot.probability[it] } ?: 0f }
        Row(GlanceModifier.fillMaxWidth().height(if (big) 16.dp else 10.dp)
            .semantics { contentDescription = "Rain chance in each 10 minutes of the next hour, up to ${Math.rint(100.0 * (cells.maxOrNull() ?: 0f)).toInt()}%" }) {
            // Glance rows hold at most 10 children: gaps are padding, not spacers
            cells.forEachIndexed { i, chance ->
                Box(GlanceModifier.defaultWeight().fillMaxHeight().padding(end = if (i < cells.size - 1) 4.dp else 0.dp)) {
                    Box(GlanceModifier.fillMaxSize().cornerRadius(3.dp).background(view.mix(chance))) {}
                }
            }
        }
        Spacer(GlanceModifier.height(4.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            Text("now", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = view.sp(12)))
            Spacer(GlanceModifier.defaultWeight())
            Text("+60 min", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = view.sp(12)))
        }
        if (big) {
            Spacer(GlanceModifier.height(18.dp))
            Text("Next 12 h", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = view.sp(12)))
            HourChart(view, 12, 64f)
        }
    }
}

@Composable
private fun Minutes(view: WidgetView, sizeClass: SizeClass) {
    if (sizeClass == SizeClass.SMALL) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                view.chance?.let { Text("$it% ", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = view.sp(14), fontWeight = FontWeight.Bold)) }
                Text(view.short, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = view.sp(13), fontWeight = FontWeight.Medium))
            }
            MinuteChart(view, view.innerHeight - view.px(19), axes = false)
        }
        return
    }
    val big = sizeClass == SizeClass.LARGE
    Column(GlanceModifier.fillMaxSize()) {
        Header(view, if (big) 26 else 18)
        Sentence(view, sp = if (big) 15 else 13, lines = 2)
        Spacer(GlanceModifier.height(4.dp))
        MinuteChart(view, view.innerHeight - view.px((if (big) 34 else 24) + (if (big) 40 else 36)) - 4, axes = true)
    }
}

@Composable
private fun Hours(view: WidgetView, sizeClass: SizeClass) {
    val hours = WidgetLayout.hours(sizeClass)
    Column(GlanceModifier.fillMaxSize()) {
        if (sizeClass == SizeClass.SMALL) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Next 12\u00A0h", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = view.sp(13), fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.defaultWeight())
                AgeText(view, 12)
            }
            HourChart(view, hours, view.innerHeight - view.px(17))
        } else {
            val big = sizeClass == SizeClass.LARGE
            Sentence(view, RainWords.widget(hoursSentence(view), RainUnits.MMH, false), sp = if (big) 16 else 13, lines = 2)
            AgeText(view, 12, prefix = "Next $hours\u00A0h")
            Spacer(GlanceModifier.height(6.dp))
            HourChart(view, hours, view.innerHeight - view.px((if (big) 42 else 36) + 16) - 6)
        }
    }
}

@Composable
private fun Radar(view: WidgetView, sizeClass: SizeClass) {
    val radar = view.snapshot.radar
    val frame = radar?.frame(view.options.lead)
    if (radar == null || frame == null) {
        Empty(if (view.snapshot.source == Snapshot.SOURCE_OPEN_METEO) "No radar map in Open-Meteo mode" else "No radar yet · open the app", sizeClass, view.textScale)
        return
    }
    val time = TIME_FORMAT.format(Instant.ofEpochSecond(view.snapshot.issued + 60L * frame.lead).atZone(ZoneId.systemDefault()))
    val what = if (frame.lead == 0) "Radar $time" else "Forecast $time (+${frame.lead} min)"
    if (sizeClass == SizeClass.LARGE) {
        Column(GlanceModifier.fillMaxSize()) {
            Header(view, 20)
            Sentence(view, sp = 13, lines = 2)
            Spacer(GlanceModifier.height(6.dp))
            Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RadarMap(view, radar, frame, min(view.innerWidth, view.innerHeight - view.px(28 + 36 + 20) - 6), what)
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(what, modifier = GlanceModifier.fillMaxWidth(),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = view.sp(13), textAlign = androidx.glance.text.TextAlign.Center))
        }
        return
    }
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        RadarMap(view, radar, frame, min(view.innerHeight, view.innerWidth * 0.45f), what)
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            if (sizeClass == SizeClass.MEDIUM) {
                view.chance?.let { Text("$it%", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = view.sp(20), fontWeight = FontWeight.Bold)) }
                Sentence(view, sp = 13, lines = 3)
                Spacer(GlanceModifier.height(4.dp))
            }
            Text(what, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = view.sp(13), fontWeight = FontWeight.Medium))
            AgeText(view, 12)
        }
    }
}

@Composable
private fun RadarMap(view: WidgetView, radar: SRadar, frame: SFrame, sizeDp: Float, what: String) {
    val side = sizeDp.coerceAtLeast(24f)
    Box(GlanceModifier.size(side.dp).cornerRadius(14.dp).background(GlanceTheme.colors.surfaceVariant)) {
        Image(ImageProvider(WidgetCharts.rain(frame, radar, view.lut)), contentDescription = null, modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.Fit)
        Chart(WidgetCharts.radarMarks(view.ctx, radar, side, view.density),
            "$what around home, ${Snapshot.HALF_KM.toInt() * 2} km across. ${view.sentence}", GlanceModifier.fillMaxSize())
    }
}

@Composable
private fun Combined(view: WidgetView) {
    Column(GlanceModifier.fillMaxSize()) {
        Header(view, 26)
        Sentence(view, sp = 15, lines = 2)
        Spacer(GlanceModifier.height(4.dp))
        val bars = 62f
        MinuteChart(view, view.innerHeight - view.px(34 + 40) - 4 - 8 - bars, axes = true)
        Spacer(GlanceModifier.height(8.dp))
        HourChart(view, 12, bars)
    }
}

package nowcast.app.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nowcast.app.*
import nowcast.app.ui.*
import nowcast.app.ui.settings.*

/** Opened when a widget is added and, on Android 12+, from "Reconfigure". Saves into that widget's Glance state. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val done = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, done)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        val widget = Widgets.forAppWidget(this, id)
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(id)
        setContent {
            NowcastTheme {
                var options by remember { mutableStateOf<WidgetOptions?>(null) }
                LaunchedEffect(Unit) {
                    val state = getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
                    options = Widgets.options(state, widget.defaultType)
                }
                val scope = rememberCoroutineScope()
                options?.let { chosen ->
                    ConfigScreen(chosen, widget, { options = it }) {
                        scope.launch {
                            save(this@WidgetConfigActivity, glanceId, widget, chosen)
                            setResult(RESULT_OK, done); finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        suspend fun save(ctx: Context, id: GlanceId, widget: RainWidgetBase, options: WidgetOptions) {
            updateAppWidgetState(ctx, id) { prefs ->
                options.toMap().forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
                prefs[Widgets.REV] = System.currentTimeMillis()
            }
            widget.update(ctx, id)
        }
    }
}

private fun stateOf(options: WidgetOptions): Preferences =
    mutablePreferencesOf().apply { options.toMap().forEach { (key, value) -> this[stringPreferencesKey(key)] = value } }

/** Representative launcher sizes for 2×1, 4×2 and 4×4. */
private val PREVIEW = linkedMapOf(SizeClass.SMALL to DpSize(180.dp, 76.dp), SizeClass.MEDIUM to DpSize(330.dp, 160.dp),
    SizeClass.LARGE to DpSize(330.dp, 330.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(options: WidgetOptions, widget: RainWidgetBase, onChange: (WidgetOptions) -> Unit, onSave: () -> Unit) {
    var size by remember { mutableStateOf(if (options.type == WidgetType.COMBINED) SizeClass.LARGE else SizeClass.MEDIUM) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Rain widget") }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(onSave, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp)) { Text("Save widget") }
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Preview(options, widget, PREVIEW.getValue(size))
            Choice(SizeClass.entries, size, { when (it) { SizeClass.SMALL -> "2×1"; SizeClass.MEDIUM -> "4×2"; SizeClass.LARGE -> "4×4" } }) { size = it }

            Section("Shows")
            WidgetType.entries.forEach { choice ->
                ListItem(headlineContent = { Text(choice.label) }, supportingContent = { Text(blurb(choice)) },
                    leadingContent = { RadioButton(options.type == choice, null) },
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).selectable(options.type == choice) { onChange(options.copy(type = choice)); if (choice == WidgetType.COMBINED) size = SizeClass.LARGE },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            if (options.type == WidgetType.RADAR) {
                Section("Radar shows")
                Choice(WidgetOptions.LEADS, options.lead, { if (it == 0) "Now" else "+$it min" }) { onChange(options.copy(lead = it)) }
            }
            Section("Look")
            Choice(WidgetTheme.entries, options.theme, { it.label }) { onChange(options.copy(theme = it)) }
            Text("Background ${options.opacity}%", style = MaterialTheme.typography.bodyMedium)
            Slider(options.opacity.toFloat(), { onChange(options.copy(opacity = it.toInt())) }, valueRange = 0f..100f, steps = 9,
                modifier = Modifier.semantics { contentDescription = "Background opacity" })
            Section("Text size")
            Choice(TextSize.entries, options.text, { it.label }) { onChange(options.copy(text = it)) }
            Section("Rain as")
            Choice(RainUnits.entries, options.units, { if (it == RainUnits.WORDS) "Light / moderate / heavy" else it.label }) { onChange(options.copy(units = it)) }
            ListItem(headlineContent = { Text("Show chance %") },
                trailingContent = { Switch(options.showChance, { onChange(options.copy(showChance = it)) }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            Section("Tap opens")
            Choice(TapAction.entries, options.tap, { it.label }) { onChange(options.copy(tap = it)) }
            Section("Refresh button")
            Choice(RefreshButton.entries, options.refresh, { it.label }) { onChange(options.copy(refresh = it)) }
            val background = (LocalContext.current.applicationContext as App).repo.prefs.value.background
            Text(if (background) "Updates every 15 min anyway; the button fetches the latest radar now."
                 else "Background updates are off, so this widget only changes when you open the app or press the button.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun blurb(type: WidgetType) = when (type) {
    WidgetType.SUMMARY -> "The sentence, the chance and the next hour in six steps"
    WidgetType.MINUTES -> "Rain rate from 30 min ago to an hour ahead, with its range"
    WidgetType.HOURS -> "Half-hour bars for two days; 12 h when small"
    WidgetType.RADAR -> "The latest radar around home, or the forecast"
    WidgetType.COMBINED -> "Sentence, the next-hour graph and 12 h of bars; for 4×4"
}

@Composable
private fun Section(text: String) =
    Text(text.uppercase(), style = Overline, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun <T> Choice(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, it ->
            SegmentedButton(it == selected, { onPick(it) }, SegmentedButtonDefaults.itemShape(index, items.size)) {
                Text(label(it), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The real widget, composed by Glance into RemoteViews and inflated here, over a wallpaper-ish backdrop. */
@Composable
private fun Preview(widgetOptions: WidgetOptions, widget: RainWidgetBase, size: DpSize) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var remoteViews by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(widgetOptions, size) {
        remoteViews = withContext(Dispatchers.Default) {
            runCatching {
                val sizes = Bundle().apply {
                    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(size.width.value, size.height.value)))
                }
                widget.compose(ctx, options = sizes, size = size, state = stateOf(widgetOptions))
            }.getOrNull()
        }
    }
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(370.dp).clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(scheme.tertiaryContainer, scheme.primaryContainer, scheme.secondaryContainer))),
        contentAlignment = Alignment.Center) {
        AndroidView(factory = { NoTouch(it) }, modifier = Modifier.size(size.width, size.height),
            update = { view -> view.removeAllViews(); remoteViews?.let { views -> runCatching { view.addView(views.apply(view.context, view)) } } })
    }
}

/** The preview is for looking at: taps must not fire the widget's actions. */
@SuppressLint("ViewConstructor")
private class NoTouch(ctx: Context) : FrameLayout(ctx) {
    override fun onInterceptTouchEvent(event: MotionEvent?) = true
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = true
}

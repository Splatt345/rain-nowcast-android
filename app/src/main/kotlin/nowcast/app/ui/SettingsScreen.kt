package nowcast.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nowcast.app.BuildConfig
import nowcast.app.Locator
import androidx.lifecycle.compose.LifecycleResumeEffect
import nowcast.app.RefreshWorker
import nowcast.app.Repo
import nowcast.app.Settings
import nowcast.app.AppColors
import nowcast.app.DarkMode
import nowcast.app.NowcastSource
import nowcast.engine.Render
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(repo: Repo, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf("main") }      // main, regions or guide
    BackHandler(page != "main") { page = "main" }
    AnimatedContent(page, label = "settings", transitionSpec = {
        val d = if (targetState != "main") 1 else -1
        (slideInHorizontally(tween(260)) { it / 4 * d } + fadeIn(tween(200))) togetherWith
            (slideOutHorizontally(tween(220)) { -it / 4 * d } + fadeOut(tween(150)))
    }) { p ->
        when (p) {
            "regions" -> RegionsScreen(repo, onBack = { page = "main" }, modifier)
            "guide" -> GuideScreen(onBack = { page = "main" }, modifier)
            else -> Main(repo, { page = "regions" }, { page = "guide" }, modifier)
        }
    }
}

@Composable
private fun Main(repo: Repo, openRegions: () -> Unit, openGuide: () -> Unit, modifier: Modifier) {
    val s by repo.prefs.settings.collectAsState()
    val keys by repo.secrets.sources.collectAsState()
    val list by repo.regionStore.regions.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editKey by remember { mutableStateOf<String?>(null) }      // the source whose key is being edited
    var locating by remember { mutableStateOf<String?>(null) }

    fun update(f: (Settings) -> Settings) = repo.prefs.update(f)
    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { locating = "Locating…"; oneFix(ctx) { loc -> locating = if (loc == null) "No location fix" else null
            loc?.let { scope.launch { repo.setHome(it.latitude, it.longitude, manual = false) } } } }
        else locating = "Location permission denied"
    }
    // following: foreground permission first, then (Android 10+) "Allow all the time" for widgets and alerts
    var background by remember { mutableStateOf(Locator.canInBackground(ctx)) }
    LifecycleResumeEffect(Unit) { background = Locator.canInBackground(ctx); onPauseOrDispose { } }
    val bgPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { background = it }
    var whyBackground by remember { mutableStateOf(false) }
    // Android wants the reason before its "all the time" page, which it opens instead of a dialog
    fun askBackground() { if (Build.VERSION.SDK_INT >= 29) whyBackground = true }
    var precise by remember { mutableStateOf(Locator.isPrecise(ctx)) }
    LifecycleResumeEffect(Unit) { precise = Locator.isPrecise(ctx); onPauseOrDispose { } }
    // precise and approximate in one request: Android shows the choice, either one is enough to follow
    val followPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        precise = Locator.isPrecise(ctx)
        if (r.values.any { it }) { update { it.copy(followLocation = true) }; repo.kick(); if (!background) askBackground() }
        else locating = "Location permission denied"
    }
    if (whyBackground) AlertDialog({ whyBackground = false }, title = { Text("Follow you in the background?") },
        // Play's prominent disclosure: what is collected, that it happens when closed, why, and who gets it
        text = { Text("Rain Nowcast collects your location to update widgets and rain alerts for where you are, " +
            "even when the app is closed or not in use. It reads one fix per refresh (every 15 minutes) and keeps no history. " +
            "To fetch the forecast, an area around you is sent to MeteoGate (radar) and your coordinates to Open-Meteo. " +
            "Android will ask you to allow location \"All the time\".") },
        confirmButton = { TextButton({ whyBackground = false
            if (Build.VERSION.SDK_INT >= 29) bgPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Continue") } },
        dismissButton = { TextButton({ whyBackground = false }) { Text("Only while open") } })

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)
        .widthIn(max = 720.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 12.dp))

        Section("Radar")
        Group {
            Segmented("Next hour from", NowcastSource.entries, s.nowcastSource, { it.label }) { v ->
                update { it.copy(nowcastSource = v) }; repo.kick(force = true) }
            Text(if (s.nowcastSource == NowcastSource.RADAR)
                    "Observed radar (EUMETNET OPERA, Europe), moved forward by the nowcast. Needs a free MeteoGate key."
                else "Open-Meteo's forecast models every 15 min, as a small ensemble: anywhere in the world, no key, " +
                    "free for non-commercial use. Model output, not observed rain: less sharp in the first hour than radar.",
                Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Item(Icons.Outlined.Key, "MeteoGate ORD key",
                when {
                    Repo.SOURCE_OPERA in keys -> "Saved · tap to replace or remove"
                    s.nowcastSource == NowcastSource.RADAR -> "Not set — the radar needs it"
                    else -> "Not set — only the radar map needs it"
                }) { editKey = Repo.SOURCE_OPERA }
            if (s.nowcastSource == NowcastSource.OPEN_METEO) Item(Icons.Outlined.Key, "Open-Meteo API key (optional)",
                if (Repo.SOURCE_OPEN_METEO in keys) "Saved · requests go to the paid customer API"
                else "Not needed for personal use; for commercial use or higher limits") { editKey = Repo.SOURCE_OPEN_METEO }
            if (BuildConfig.DEBUG) Toggle(Icons.Outlined.Science, "Sample radar (debug)", "Bundled OPERA file, no key, no network",
                s.demo) { v -> update { it.copy(demo = v) }; repo.kick(force = true) }
        }

        Section("Place")
        Group {
            val (lat, lon) = s.home
            val where = "%.3f, %.3f".format(java.util.Locale.ROOT, lat, lon)
            Item(Icons.Outlined.Home, "Home", when {
                s.followLocation -> "Your location · $where"
                s.homeLat == null -> "Default (Brussels) · $where · tap a place on the map to change"
                else -> "$where · tap a place on the map to change"
            }, onClick = null)
            Toggle(Icons.Outlined.NearMe, "Follow my location", when {
                !s.followLocation -> "Home moves with you"
                !precise -> "Approximate location: Android updates it every 10 min at most" +
                    if (background) "" else "; only while the app is open"
                background -> "Live while the app is open, at each refresh in the background"
                else -> "Live while the app is open; widgets and alerts use the last place"
            }, s.followLocation) { on ->
                if (!on) update { it.copy(followLocation = false) }
                else if (Locator.canLocate(ctx)) { update { it.copy(followLocation = true) }; repo.kick(); if (!background) askBackground() }
                else followPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            if (s.followLocation && !background)
                Item(Icons.Outlined.LocationOn, "Follow in the background too", "Allow location \"all the time\" so widgets and alerts move with you") {
                    askBackground()
                }
            if (!s.followLocation) Item(Icons.Outlined.MyLocation, "Use my location once", locating ?: "One fix now, not tracked") {
                locPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            var regionMenu by remember { mutableStateOf(false) }
            Box {
                Item(Icons.Outlined.Public, "Region", s.regionId?.let { id -> list.firstOrNull { it.id == id }?.name ?: id }
                    ?: "Automatic (the one containing home)") { regionMenu = true }
                DropdownMenu(regionMenu, { regionMenu = false }) {
                    DropdownMenuItem({ Text("Automatic") }, { update { it.copy(regionId = null) }; regionMenu = false })
                    list.forEach { r -> DropdownMenuItem({ Text(r.name) }, { update { it.copy(regionId = r.id) }; regionMenu = false }) }
                }
            }
            Item(Icons.Outlined.Layers, "Regions and models", "${list.size} regions · add, edit, upload a model", onClick = openRegions)
            Item(Icons.Outlined.School, "Your own region", "Use the app outside Belgium, or train a model for it",
                onClick = openGuide)
        }

        Section("Forecast")
        Group {
            Toggle(Icons.Outlined.Psychology, "Use the ML model", "SmaAt-UNet shifts the ensemble toward its forecast", s.useModel) { v ->
                update { it.copy(useModel = v) }; repo.kick(force = true)
            }
        }

        Section("Background")
        Group {
            Toggle(Icons.Outlined.Sync, "Background updates",
                if (s.background) "Every 15 min on any network: a small radar crop around home, for widgets and alerts"
                else "Off: nothing is downloaded unless the app is open or you tap a widget's refresh button", s.background) { v ->
                update { it.copy(background = v) }; RefreshWorker.schedule(ctx, v)
            }
        }

        Section("Look")
        Group {
            var accent by remember { mutableStateOf(false) }
            var radar by remember { mutableStateOf(false) }
            var radarMenu by remember { mutableStateOf(false) }
            val choices = AppColors.entries.filter { it != AppColors.WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
            Segmented("App colours", choices, s.appColors.takeIf { it in choices } ?: AppColors.RAIN, { it.label }) { c ->
                repo.updateLook { it.copy(appColors = c) } }
            if (s.appColors == AppColors.CUSTOM)
                Item(Icons.Outlined.Palette, "Accent colour", "#${Settings.hex(s.accent)} · tap to change",
                    Brush.linearGradient(listOf(rgb(s.accent), rgb(s.accent)))) { accent = true }
            Segmented("Dark mode", DarkMode.entries, s.darkMode, { it.label }) { m -> update { it.copy(darkMode = m) } }
            Box {
                val lut = remember(s.palette, s.radarColors) { s.radarLut() }
                Item(Icons.Outlined.Gradient, "Radar colours", s.palette.replaceFirstChar { it.uppercase() },
                    Brush.horizontalGradient(listOf(30, 120, 200, 250).map { rgb(lut[it]) })) { radarMenu = true }
                DropdownMenu(radarMenu, { radarMenu = false }) {
                    Render.PALETTES.keys.forEach { name ->
                        DropdownMenuItem({ Text(name.replaceFirstChar { it.uppercase() }) }, {
                            repo.updateLook { it.copy(palette = name) }; radarMenu = false })
                    }
                    DropdownMenuItem({ Text("Custom…") }, { radarMenu = false; radar = true })
                }
            }
            if (accent) ColorPickerDialog("Accent colour", s.accent, { accent = false }) { c ->
                repo.updateLook { it.copy(accent = c) }; accent = false }
            if (radar) RadarColorsDialog(s.radarColors, { radar = false }) { c ->
                repo.updateLook { it.copy(palette = Render.CUSTOM, radarColors = c) }; radar = false }
        }

        Section("Rain alerts")
        Group {
            Toggle(Icons.Outlined.NotificationsActive, "Notify before rain",
                if (s.background) "\"Rain in N min\" at home" else "Needs background updates", s.notify) { v -> update { it.copy(notify = v) } }
            SliderItem("Warn up to", "${s.notifyLeadMin} min ahead", s.notifyLeadMin.toFloat(),
                Settings.LEAD_RANGE.first.toFloat()..Settings.LEAD_RANGE.last.toFloat(), 10, s.notify) { v ->
                update { it.copy(notifyLeadMin = (v / 5).roundToInt() * 5) } }
            SliderItem("When the chance is at least", "${(s.notifyThreshold * 100).roundToInt()}%", s.notifyThreshold.toFloat(),
                0.1f..0.9f, 7, s.notify) { v -> update { it.copy(notifyThreshold = (v * 10).roundToInt() / 10.0) } }
        }
        Text("Radar: EUMETNET OPERA via MeteoGate · Outlook" + (if (s.nowcastSource == NowcastSource.OPEN_METEO) " and nowcast" else "") +
            ": Open-Meteo (weather data by Open-Meteo.com, CC BY 4.0) · Map: OpenFreeMap © OpenMapTiles, data © OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp))
    }

    editKey?.let { src ->
        KeyDialog(if (src == Repo.SOURCE_OPERA) "ORD API key" else "Open-Meteo API key", onDismiss = { editKey = null }, onSave = { k ->
            repo.secrets.set(src, k); editKey = null; repo.kick(force = true)
        }, onRemove = { repo.secrets.set(src, null); editKey = null; repo.kick(force = true) })
    }
}

@SuppressLint("MissingPermission")
private fun oneFix(ctx: Context, done: (Location?) -> Unit) {
    if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return done(null)
    val lm = ctx.getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
        ?: return done(null)
    if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, ctx.mainExecutor) { done(it ?: lm.getLastKnownLocation(provider)) }
    else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, { done(it) }, ctx.mainLooper)
}

@Composable
private fun KeyDialog(title: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var k by remember { mutableStateOf("") }
    AlertDialog(onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(k, { k = it.trim() }, singleLine = true, label = { Text("New key") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton({ onSave(k) }, enabled = k.isNotBlank()) { Text("Save") } },
        dismissButton = { Row { TextButton(onRemove) { Text("Remove key") }; TextButton(onDismiss) { Text("Cancel") } } })
}

@Composable
fun Section(title: String) =
    Text(title.uppercase(), style = Overline, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))

@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) =
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
    }

private fun rgb(c: Int) = Color(0xFF000000.toInt() or c)

@Composable
private fun <T> Segmented(title: String, items: List<T>, sel: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.padding(start = 56.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, it ->
                SegmentedButton(it == sel, { onPick(it) }, SegmentedButtonDefaults.itemShape(i, items.size)) {
                    Text(label(it), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun Item(icon: ImageVector, title: String, sub: String, swatch: Brush? = null, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        swatch?.let { Box(Modifier.padding(end = 8.dp).size(40.dp, 20.dp).clip(RoundedCornerShape(10.dp)).background(it)) }
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Toggle(icon: ImageVector, title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(on, role = Role.Switch, onValueChange = set).heightIn(min = 64.dp)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, null)
    }
}

@Composable
private fun SliderItem(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean,
                       set: (Float) -> Unit) {
    Column(Modifier.padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 2.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(v, set, valueRange = range, steps = steps, enabled = enabled)
    }
}

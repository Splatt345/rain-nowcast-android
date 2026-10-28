package nowcast.app.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nowcast.app.data.*
import nowcast.app.ui.*
import nowcast.app.ui.now.*
import nowcast.app.ui.settings.*
import nowcast.engine.forecast.*
import nowcast.engine.render.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Point
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** OpenFreeMap vector basemaps (no key, no limits): Dark at night, Positron by day. */
fun mapStyle(dark: Boolean): Style.Builder =
    Style.Builder().fromUri("https://tiles.openfreemap.org/styles/" + if (dark) "dark" else "positron")

const val MAP_CREDIT = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"

/** The app's own light/dark (Settings can force one), not the system's. */
@Composable
fun mapIsDark() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** A MapView bound to the composition's lifecycle. */
@Composable
fun rememberMapView(): MapView {
    val ctx = LocalContext.current
    val view = remember { MapView(ctx).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart(); Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause(); Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view.onPause(); view.onStop(); view.onDestroy() }
    }
    return view
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(repo: Repo, state: UiState, now: Instant) {
    val settings by repo.prefs.settings.collectAsState()
    val dark = mapIsDark()
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val forecast = state.forecast
    val frames by repo.frames.collectAsState()
    var minute by rememberSaveable { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<LatLng?>(null) }
    /** The tapped point and the time its minutes count from; [sheetFailed] when the look-up gave nothing. */
    var sheetPoint by remember { mutableStateOf<Pair<PointForecast, Instant>?>(null) }
    var sheetFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(frames) {
        val loaded = frames ?: return@LaunchedEffect
        if (minute !in 0..loaded.minutes) minute = loaded.minuteOf(loaded.newest)          // the newest radar frame
    }
    LaunchedEffect(playing, frames) {
        val loaded = frames ?: return@LaunchedEffect
        while (playing) { delay(100); minute = (minute + 1) % (loaded.minutes + 1) }          // a minute at a time
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.uiSettings.isRotateGesturesEnabled = false; readyMap.uiSettings.isTiltGesturesEnabled = false
            readyMap.uiSettings.isLogoEnabled = false
            readyMap.uiSettings.isAttributionEnabled = false    // shown as text on the map instead
            readyMap.addOnMapClickListener { tapped ->
                sheet = tapped; sheetPoint = null; sheetFailed = false
                scope.launch {
                    val result = repo.pointAt(tapped.latitude, tapped.longitude)
                    if (sheet == tapped) { sheetPoint = result; sheetFailed = result == null }     // a later tap wins
                }
                true
            }
        }
    }
    LaunchedEffect(map, dark) {
        val current = map ?: return@LaunchedEffect
        style = null                                 // the old one is invalid once the new one loads
        current.setStyle(mapStyle(dark)) { loaded -> style = loaded }
    }
    // camera: frame the region once per region
    val regionId = frames?.regionId
    val density = LocalDensity.current
    LaunchedEffect(map, regionId) {
        val current = map ?: return@LaunchedEffect; val bbox = frames?.bbox ?: return@LaunchedEffect
        // the region in the part of the map left visible: under the top buttons, above the time panel
        val pixels = { dp: Int -> (dp * density.density).toInt() }
        current.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(bbox[3], bbox[2], bbox[1], bbox[0]), pixels(12), pixels(72), pixels(12), pixels(236)))
    }
    val lut = remember(settings.palette, settings.radarColors) { settings.radarLut() }
    LaunchedEffect(style, frames, minute, lut) {
        val loadedStyle = style ?: return@LaunchedEffect; val loaded = frames ?: return@LaunchedEffect
        if (minute !in 0..loaded.minutes) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(Render.argb(loaded.levelsAt(minute), lut), loaded.width, loaded.height, Bitmap.Config.ARGB_8888)
        }
        val bbox = loaded.bbox
        val quad = LatLngQuad(LatLng(bbox[3], bbox[0]), LatLng(bbox[3], bbox[2]), LatLng(bbox[1], bbox[2]), LatLng(bbox[1], bbox[0]))
        val source = loadedStyle.getSourceAs<ImageSource>("rain")
        if (source == null) {
            loadedStyle.addSource(ImageSource("rain", quad, bitmap))
            // under the place names, so towns stay readable through the rain
            val labels = loadedStyle.layers.firstOrNull { it is SymbolLayer }?.id
            val layer = RasterLayer("rain", "rain").withProperties(rasterFadeDuration(0f), rasterOpacity(0.9f), rasterResampling("linear"))
            if (labels != null) loadedStyle.addLayerBelow(layer, labels) else loadedStyle.addLayer(layer)
        } else { source.setCoordinates(quad); source.setImage(bitmap) }
    }
    LaunchedEffect(style, settings.home) {
        val loadedStyle = style ?: return@LaunchedEffect
        val homePoint = Point.fromLngLat(settings.home.second, settings.home.first)
        val source = loadedStyle.getSourceAs<GeoJsonSource>("home")
        if (source == null) {
            loadedStyle.addSource(GeoJsonSource("home", homePoint))
            loadedStyle.addLayer(CircleLayer("home", "home").withProperties(circleRadius(7f), circleColor("#1F5FA8"),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)))
        } else source.setGeoJson(homePoint)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize().semantics { contentDescription = "Rain map. Tap a place for its forecast." })
        Text(MAP_CREDIT, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PalettePicker(settings.palette, settings.radarColors, { chosen -> repo.updateLook { it.copy(palette = chosen) } },
                { colors -> repo.updateLook { it.copy(palette = Render.CUSTOM, radarColors = colors) } }, Modifier)
            ModelPicker(repo, frames?.regionId ?: state.forecast?.region?.id)
        }

        val shownFrames = frames
        Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 640.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
            shadowElevation = 6.dp) {
            Column(Modifier.padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 10.dp)) {
                // a model swap or refresh re-running the forecast
                if (state.loading && shownFrames != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 10.dp, bottom = 6.dp))
                if (forecast == null && shownFrames == null && (!repo.hasKey || settings.nowcastSource == NowcastSource.OPEN_METEO)) {
                    Text(if (!repo.hasKey) "The radar map needs a MeteoGate key (Settings). The Now screen and widgets use Open-Meteo without one."
                        else "Open-Meteo mode loads no radar for home. Tap a place in Europe to load the radar there.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                } else if (shownFrames == null || minute !in 0..shownFrames.minutes) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                        Text(if (forecast == null) "Waiting for the radar…" else "Drawing the forecast…")
                    }
                } else {
                    val time = shownFrames.time(minute)
                    val minutesFromNow = Duration.between(now, time).toMinutes().toInt()
                    val future = time > shownFrames.issued
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton({ playing = !playing }, Modifier.size(48.dp)) {
                            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play")
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(TIME_FORMAT.format(time.atZone(java.time.ZoneId.systemDefault())), style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.width(8.dp))
                                Text(if (abs(minutesFromNow) < 1) "now" else if (minutesFromNow > 0) "in $minutesFromNow min" else "${-minutesFromNow} min ago",
                                    Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val modelLabel = state.forecast?.takeIf { it.region.id == shownFrames.regionId }
                                ?.let { if (it.modelUsed) " · ${it.region.model.removeSuffix(".onnx")}" else " · ensemble only" } ?: ""
                            Text(if (future) "Forecast$modelLabel" else "Radar", style = MaterialTheme.typography.labelMedium,
                                color = if (future) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RateLegend(lut)
                    }
                    Spacer(Modifier.height(6.dp))
                    MapTimeline(shownFrames, minute, { minute = it; playing = false }, state.point, state.issued, now,
                        Modifier.padding(start = 4.dp))
                }
            }
        }
    }

    sheet?.let { place ->
        ModalBottomSheet({ sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("%.3f, %.3f".format(java.util.Locale.ROOT, place.latitude, place.longitude), style = Overline,
                    color = MaterialTheme.colorScheme.primary)
                val (point, issued) = sheetPoint ?: (null to null)
                if (sheetFailed) {
                    Text((state.problem as? Problem.Network)?.let { "No forecast here: ${it.message}" }
                        ?: (state.problem as? Problem.Radar)?.let { "No forecast here: ${it.message}" }
                        ?: if (!repo.hasKey) "The radar forecast for a place needs a MeteoGate key (Settings)." else "No forecast here right now.",
                        style = MaterialTheme.typography.bodyLarge)
                } else if (point == null || issued == null) {
                    Shimmer(Modifier.fillMaxWidth().height(30.dp)); Shimmer(Modifier.fillMaxWidth().height(220.dp))
                } else {
                    Text(point.summary, style = MaterialTheme.typography.headlineSmall)
                    if (!point.inCoverage) Text("Outside the radar crop — values are from its nearest edge.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    NowcastGraph(point, issued, now)
                }
                Button({ scope.launch { repo.setHome(place.latitude, place.longitude) }; sheet = null },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.Home, null); Spacer(Modifier.width(8.dp)); Text("Set as home")
                }
            }
        }
    }
}

@Composable
private fun RateLegend(lut: IntArray) {
    val colors = remember(lut) { listOf(30, 90, 140, 190, 240).map { Color(0xFF000000.toInt() or lut[it]) } }
    Column(horizontalAlignment = Alignment.End) {
        Box(Modifier.size(84.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(colors)))
        Text("0.1 → 80 mm/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PalettePicker(current: String, custom: List<Int>, onPick: (String) -> Unit, onCustom: (List<Int>) -> Unit,
                          modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    if (editing) RadarColorsDialog(custom, { editing = false }) { onCustom(it); editing = false }
    Box(modifier) {
        FilledTonalIconButton({ open = true }, Modifier.size(48.dp)) { Icon(Icons.Outlined.Palette, "Colour scale") }
        DropdownMenu(open, { open = false }) {
            (Render.PALETTES.keys + Render.CUSTOM).forEach { name ->
                val lut = if (name == Render.CUSTOM) Render.lut(Render.stops(custom)) else Render.lut(name)
                DropdownMenuItem(
                    text = { Text(name.replaceFirstChar { it.uppercase() }) },
                    leadingIcon = {
                        Box(Modifier.size(36.dp, 12.dp).clip(RoundedCornerShape(6.dp))
                            .background(Brush.horizontalGradient(listOf(30, 120, 200, 250).map { Color(0xFF000000.toInt() or lut[it]) })))
                    },
                    trailingIcon = { if (name == current) RadioButton(true, null) },
                    onClick = { onPick(name); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem({ Text("Edit custom colours…") }, { open = false; editing = true },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) })
        }
    }
}

/** Swap the nowcast model for the region on the map, live: the forecast re-runs on the radar already in. */
@Composable
private fun ModelPicker(repo: Repo, regionId: String?) {
    val settings by repo.prefs.settings.collectAsState()
    val regions by repo.regionStore.regions.collectAsState()
    val models by repo.modelStore.models.collectAsState()
    val region = regions.firstOrNull { it.id == regionId } ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton({ open = true }, Modifier.size(48.dp)) { Icon(Icons.Outlined.Memory, "Nowcast model") }
        DropdownMenu(open, { open = false }) {
            Text("Model for ${region.name}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            models.forEach { name ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(name.removeSuffix(".onnx"))
                            if (name == ModelStore.BUNDLED) Text("bundled · trained on Belgium", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (settings.useModel && region.model == name) RadioButton(true, null) },
                    onClick = { repo.setModel(name); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Ensemble only")
                        Text("motion extrapolation, no ML", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingIcon = { if (!settings.useModel) RadioButton(true, null) },
                onClick = { repo.setModel(null); open = false },
            )
        }
    }
}

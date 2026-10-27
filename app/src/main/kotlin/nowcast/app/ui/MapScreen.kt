package nowcast.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import nowcast.app.Repo
import nowcast.app.UiState
import nowcast.engine.Forecast
import nowcast.engine.PointForecast
import nowcast.engine.Render
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
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Point
import java.time.Duration
import java.time.Instant

/** OSM raster basemap. Dark mode inverts its lightness (brightness min/max swapped) and turns the hue back. */
fun osmStyle(dark: Boolean): String {
    val paint = if (dark) """"raster-brightness-min":0.92,"raster-brightness-max":0.06,"raster-saturation":-0.75,"raster-hue-rotate":180,"raster-contrast":0.05"""
    else """"raster-saturation":-0.35,"raster-contrast":-0.05"""
    return """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
        "tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},
        "layers":[{"id":"osm","type":"raster","source":"osm","paint":{$paint}}]}"""
}

/** A MapView bound to the composition's lifecycle. */
@Composable
fun rememberMapView(): MapView {
    val ctx = LocalContext.current
    val view = remember { MapView(ctx).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart(); Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause(); Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); view.onPause(); view.onStop(); view.onDestroy() }
    }
    return view
}

private class Frames(val levels: List<ByteArray>, val w: Int, val h: Int, val times: List<Instant>, val fc: Forecast)

private fun render(fc: Forecast): Frames {
    val s = Render.mercatorSampler(fc.geo, fc.region.bbox, width = 512)
    val fields = fc.past.map { f -> FloatArray(f.rain.v.size) { f.rain.v[it].toFloat() } } + fc.mean.toList()
    val times = fc.past.map { it.time } + (1..fc.mean.size).map { fc.issued.plusSeconds(60L * fc.intervalMinutes * it) }
    return Frames(fields.map { Render.levels(it, fc.geo.h, fc.geo.w, s) }, s.width, s.height, times, fc)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(repo: Repo, state: UiState, now: Instant) {
    val settings by repo.prefs.settings.collectAsState()
    val dark = isSystemInDarkTheme()
    val mapView = rememberMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val fc = state.forecast
    var frames by remember { mutableStateOf<Frames?>(null) }
    var index by rememberSaveable { mutableIntStateOf(-1) }
    var playing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<LatLng?>(null) }
    var sheetPoint by remember { mutableStateOf<PointForecast?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(fc) {
        if (fc == null) return@LaunchedEffect
        val f = withContext(Dispatchers.Default) { render(fc) }
        frames = f
        if (index !in f.levels.indices) index = fc.past.size - 1          // the newest radar frame
    }
    LaunchedEffect(playing, frames) {
        val f = frames ?: return@LaunchedEffect
        while (playing) { delay(450); index = (index + 1) % f.levels.size }
    }

    LaunchedEffect(mapView, dark) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false; m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false    // shown as text on the map instead
            m.setStyle(Style.Builder().fromJson(osmStyle(dark))) { st -> style = st }
            m.addOnMapClickListener { ll ->
                sheet = ll; sheetPoint = null
                scope.launch { sheetPoint = repo.pointAt(ll.latitude, ll.longitude) }
                true
            }
        }
    }
    // camera: frame the region once per region
    val regionId = fc?.region?.id
    LaunchedEffect(map, regionId) {
        val m = map ?: return@LaunchedEffect; val b = fc?.region?.bbox ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(b[3], b[2], b[1], b[0]), 40))
    }
    // overlay
    LaunchedEffect(style, frames, index, settings.palette) {
        val st = style ?: return@LaunchedEffect; val f = frames ?: return@LaunchedEffect
        if (index !in f.levels.indices) return@LaunchedEffect
        val bmp = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(Render.argb(f.levels[index], settings.palette), f.w, f.h, Bitmap.Config.ARGB_8888)
        }
        val b = f.fc.region.bbox
        val quad = LatLngQuad(LatLng(b[3], b[0]), LatLng(b[3], b[2]), LatLng(b[1], b[2]), LatLng(b[1], b[0]))
        val src = st.getSourceAs<ImageSource>("rain")
        if (src == null) {
            st.addSource(ImageSource("rain", quad, bmp))
            st.addLayer(RasterLayer("rain", "rain").withProperties(rasterFadeDuration(0f), rasterOpacity(0.9f), rasterResampling("linear")))
        } else { src.setCoordinates(quad); src.setImage(bmp) }
    }
    // home marker
    LaunchedEffect(style, settings.home) {
        val st = style ?: return@LaunchedEffect
        val pt = Point.fromLngLat(settings.home.second, settings.home.first)
        val src = st.getSourceAs<GeoJsonSource>("home")
        if (src == null) {
            st.addSource(GeoJsonSource("home", pt))
            st.addLayer(CircleLayer("home", "home").withProperties(circleRadius(7f), circleColor("#1F5FA8"),
                circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)))
        } else src.setGeoJson(pt)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize().semantics { contentDescription = "Rain map. Tap a place for its forecast." })
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        PalettePicker(settings.palette, { p -> repo.prefs.update { it.copy(palette = p) } },
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp))

        val f = frames
        Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 640.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
            shadowElevation = 6.dp) {
            Column(Modifier.padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 10.dp)) {
                if (f == null || index !in f.levels.indices) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                        Text(if (fc == null) "Waiting for the radar…" else "Drawing the forecast…")
                    }
                } else {
                    val t = f.times[index]
                    val rel = Duration.between(now, t).toMinutes().toInt()
                    val future = t > f.fc.issued
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledIconButton({ playing = !playing }, Modifier.size(48.dp)) {
                            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play")
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${HM.format(t.atZone(java.time.ZoneId.systemDefault()))} · ${if (rel == 0) "now" else if (rel > 0) "+$rel min" else "$rel min"}",
                                style = MaterialTheme.typography.titleSmall)
                            Text(if (future) "Forecast (ensemble mean)" else "Radar", style = MaterialTheme.typography.labelSmall,
                                color = if (future) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RateLegend(settings.palette)
                    }
                    Slider(index.toFloat(), { v -> val i = v.toInt(); if (i != index) { index = i; playing = false
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } },
                        valueRange = 0f..(f.levels.size - 1).toFloat(), steps = f.levels.size - 2,
                        modifier = Modifier.padding(start = 10.dp).semantics { stateDescription = if (rel >= 0) "plus $rel minutes" else "$rel minutes" })
                }
            }
        }
    }

    sheet?.let { ll ->
        ModalBottomSheet({ sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("%.3f, %.3f".format(java.util.Locale.ROOT, ll.latitude, ll.longitude), style = Overline,
                    color = MaterialTheme.colorScheme.primary)
                val p = sheetPoint
                val iss = state.forecast?.issued
                if (p == null || iss == null) {
                    Shimmer(Modifier.fillMaxWidth().height(30.dp)); Shimmer(Modifier.fillMaxWidth().height(220.dp))
                } else {
                    Text(p.summary, style = MaterialTheme.typography.headlineSmall)
                    if (!p.inCoverage) Text("Outside the radar crop — values are from its nearest edge.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    NowcastGraph(p, iss, now)
                }
                Button({ scope.launch { repo.setHome(ll.latitude, ll.longitude) }; sheet = null },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.Home, null); Spacer(Modifier.width(8.dp)); Text("Set as home")
                }
            }
        }
    }
}

@Composable
private fun RateLegend(palette: String) {
    val lut = remember(palette) { Render.lut(palette) }
    val colors = remember(lut) { listOf(30, 90, 140, 190, 240).map { Color(0xFF000000.toInt() or lut[it]) } }
    Column(horizontalAlignment = Alignment.End) {
        Box(Modifier.size(84.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(colors)))
        Text("0.1 → 80 mm/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PalettePicker(current: String, onPick: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalIconButton({ open = true }, Modifier.size(48.dp)) { Icon(Icons.Outlined.Palette, "Colour scale") }
        DropdownMenu(open, { open = false }) {
            Render.PALETTES.keys.forEach { name ->
                val lut = Render.lut(name)
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
        }
    }
}

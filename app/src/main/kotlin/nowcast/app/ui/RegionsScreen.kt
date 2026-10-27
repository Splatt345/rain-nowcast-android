package nowcast.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nowcast.app.ModelStore
import nowcast.app.Repo
import nowcast.engine.Region
import nowcast.engine.Validate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionsScreen(repo: Repo, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val list by repo.regionStore.regions.collectAsState()
    var editing by remember { mutableStateOf<Region?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Region?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    editing?.let { r ->
        BackHandler { editing = null }
        RegionEditor(repo, r, isNew, others = list.filter { it.id != r.id || isNew }.map { it.id }, onDone = { saved ->
            if (saved != null) repo.regionStore.save(if (isNew) list + saved else list.map { if (it.id == r.id) saved else it })
            editing = null
        }, modifier)
        return
    }

    Scaffold(modifier, containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar({ Text("Regions and models") }, windowInsets = WindowInsets(0),
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = { IconButton({ confirmReset = true }) { Icon(Icons.Outlined.RestartAlt, "Reset to bundled regions") } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { isNew = true; editing = Region("", "", listOf(4.0, 50.0, 6.0, 51.5)) },
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add region") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { r ->
                ElevatedCard(onClick = { isNew = false; editing = r }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Text("${r.id} · ${r.source.uppercase()} · ${r.model}" + if (r.weights != null) " · own weights" else "",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(r.bbox.joinToString("  ") { "%.1f".format(Locale.ROOT, it) },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ confirmDelete = r }, enabled = list.size > 1) { Icon(Icons.Outlined.Delete, "Delete ${r.name}") }
                    }
                }
            }
        }
    }
    confirmDelete?.let { r ->
        AlertDialog({ confirmDelete = null }, title = { Text("Delete ${r.name}?") },
            text = { Text("Its radar crop and settings go; uploaded models stay.") },
            confirmButton = { TextButton({ repo.regionStore.save(list - r); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } })
    }
    if (confirmReset) AlertDialog({ confirmReset = false }, title = { Text("Reset regions?") },
        text = { Text("Back to the five bundled Benelux regions. Your own regions are removed.") },
        confirmButton = { TextButton({ repo.regionStore.reset(); confirmReset = false }) { Text("Reset") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegionEditor(repo: Repo, start: Region, isNew: Boolean, others: List<String>, onDone: (Region?) -> Unit, modifier: Modifier) {
    var id by remember { mutableStateOf(start.id) }
    var name by remember { mutableStateOf(start.name) }
    val box = remember { start.bbox.map { "%.2f".format(Locale.ROOT, it) }.toMutableStateList() }
    var source by remember { mutableStateOf(start.source) }
    var model by remember { mutableStateOf(start.model) }
    var weights by remember { mutableStateOf(start.weights?.joinToString(", ") ?: "") }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val models by repo.modelStore.models.collectAsState()
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try { model = withContext(Dispatchers.IO) { repo.modelStore.import(uri) } }
            catch (e: Exception) { importError = e.message ?: "The file could not be read" }
            importing = false
        }
    }
    val bbox = box.map { it.toDoubleOrNull() ?: Double.NaN }

    Scaffold(modifier, containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar({ Text(if (isNew) "New region" else start.name) }, windowInsets = WindowInsets(0),
                navigationIcon = { IconButton({ onDone(null) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Cancel") } },
                actions = {
                    TextButton({
                        val w = runCatching { Validate.weights(weights) }
                        val r = Region(id.trim(), name.trim(), bbox, source, model, w.getOrNull(), start.outlook)
                        errors = Validate.region(r, others) + listOfNotNull(w.exceptionOrNull()?.message)
                        if (errors.isEmpty()) onDone(r)
                    }, Modifier.heightIn(min = 48.dp)) { Text("Save") }
                })
        }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (errors.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) { errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1.4f), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(id, { id = it.lowercase() }, Modifier.weight(1f), label = { Text("Id") }, singleLine = true, enabled = isNew)
            }
            BoxMap(bbox) { b -> b.forEachIndexed { i, v -> box[i] = "%.2f".format(Locale.ROOT, v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("West", "South", "East", "North").forEachIndexed { i, l ->
                    OutlinedTextField(box[i], { box[i] = it }, Modifier.weight(1f), label = { Text(l) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
            Dropdown("Radar source", source, Validate.SOURCES, { it.uppercase() }) { source = it }
            Dropdown("Model", model, models, { if (it == ModelStore.BUNDLED) "$it · bundled" else it }) { model = it }
            FilledTonalButton({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !importing) {
                if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.UploadFile, null)
                Spacer(Modifier.width(8.dp)); Text(if (importing) "Test-running the model…" else "Upload your own .onnx")
            }
            Text("An uploaded model must take 12 frames and return 12, all finite and at most 500 mm/h; it is test-run before it is kept.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(weights, { weights = it }, Modifier.fillMaxWidth(), label = { Text("Per-lead weights (optional)") },
                placeholder = { Text("12 values 0–1, blank = bundled") }, minLines = 2)
        }
    }
    importError?.let { e ->
        AlertDialog({ importError = null }, icon = { Icon(Icons.Outlined.ErrorOutline, null) }, title = { Text("Model rejected") },
            text = { Text(e) }, confirmButton = { TextButton({ importError = null }) { Text("OK") } })
    }
}

/** The box on a map; "Use visible area" draws the box from the current view. */
@Composable
private fun BoxMap(bbox: List<Double>, onBox: (List<Double>) -> Unit) {
    val mapView = rememberMapView()
    val dark = isSystemInDarkTheme()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    LaunchedEffect(mapView, dark) {
        mapView.getMapAsync { m -> map = m; m.uiSettings.isRotateGesturesEnabled = false; m.uiSettings.isLogoEnabled = false; m.uiSettings.isAttributionEnabled = false
            m.setStyle(Style.Builder().fromJson(osmStyle(dark))) { style = it } }
    }
    val valid = bbox.size == 4 && bbox.all { it.isFinite() } && bbox[0] < bbox[2] && bbox[1] < bbox[3]
    LaunchedEffect(map) {
        if (valid) map?.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(bbox[3] + 0.5, bbox[2] + 0.8, bbox[1] - 0.5, bbox[0] - 0.8), 24))
    }
    LaunchedEffect(style, bbox) {
        val st = style ?: return@LaunchedEffect
        if (!valid) return@LaunchedEffect
        val (w, s, e, n) = bbox
        val poly = Polygon.fromLngLats(listOf(listOf(Point.fromLngLat(w, s), Point.fromLngLat(e, s), Point.fromLngLat(e, n),
            Point.fromLngLat(w, n), Point.fromLngLat(w, s))))
        val src = st.getSourceAs<GeoJsonSource>("box")
        if (src == null) {
            st.addSource(GeoJsonSource("box", poly))
            st.addLayer(FillLayer("box-fill", "box").withProperties(fillColor("#1F5FA8"), fillOpacity(0.18f)))
            st.addLayer(LineLayer("box-line", "box").withProperties(lineColor("#1F5FA8"), lineWidth(2.5f)))
        } else src.setGeoJson(poly)
    }
    Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp))) {
        AndroidView({ mapView }, Modifier.matchParentSize())
        FilledTonalButton({
            map?.projection?.visibleRegion?.latLngBounds?.let { b ->
                // inset a little so the box is visible inside the view it came from
                val dx = (b.longitudeEast - b.longitudeWest) * 0.08; val dy = (b.latitudeNorth - b.latitudeSouth) * 0.08
                onBox(listOf(b.longitudeWest + dx, b.latitudeSouth + dy, b.longitudeEast - dx, b.latitudeNorth - dy))
            }
        }, Modifier.align(Alignment.TopEnd).padding(8.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.CropFree, null); Spacer(Modifier.width(6.dp)); Text("Use visible area")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(label: String, value: T, options: List<T>, show: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(show(value), {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem({ Text(show(o)) }, { onPick(o); open = false }) }
        }
    }
}

package nowcast.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nowcast.engine.*
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

sealed interface Problem {
    data object NoKey : Problem
    data class Network(val msg: String) : Problem
    data class Radar(val msg: String) : Problem
}

data class UiState(
    val loading: Boolean = false,
    val forecast: Forecast? = null,
    /** At home, computed when the forecast's region contains home. */
    val point: PointForecast? = null,
    val outlook: Outlook? = null,
    val outlookError: String? = null,
    val problem: Problem? = null,
    val modelNote: String? = null,
    val updated: Instant? = null,
    /** The region a running cycle is for, so the header is right before the first forecast. */
    val regionName: String? = null,
    /** What [point]'s minutes count from: the radar frame, or the Open-Meteo quarter hour. */
    val issued: Instant? = null,
    /** [point] is Open-Meteo model output, not radar. */
    val modelOnly: Boolean = false,
)

/** One cycle for the screens, the worker and the widget. Holds the ensemble in memory so taps only re-trace points. */
class Repo(private val ctx: Context) {
    init { ctx.deleteSharedPreferences("widget") }    // the old one-line snapshot; widget.json replaced it
    val prefs = Prefs(ctx)
    val secrets = Secrets(ctx)
    val regionStore = RegionStore(ctx)
    val modelStore = ModelStore(ctx)

    private val flow = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = flow
    private val lock = Mutex()
    /** Cycles outlive the screen that asked for them: a dialog or rotation must not throw away 20 s of work. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun kick(force: Boolean = false) { if (force || !lock.isLocked) scope.launch { refresh(force) } }

    /** A colour change: the widgets draw with these settings too, so redraw them now rather than at the next refresh. */
    fun updateLook(f: (Settings) -> Settings) { prefs.update(f); scope.launch { runCatching { Widgets.updateAll(ctx) } } }

    private var modelBroken: String? = null           // a failed model is not retried until restart
    private var smaat: Pair<String, SmaatModel>? = null
    private val outlookModel by lazy {
        runCatching {
            OutlookModel(ctx.assets.open("outlook/outlook_gbm.onnx").readBytes(),
                OutlookMeta.parse(ctx.assets.open("outlook/outlook_gbm.json").bufferedReader().readText()))
        }.getOrNull()
    }
    private var outlookAt: Pair<Double, Double>? = null

    val demo: Boolean get() = BuildConfig.DEBUG && prefs.value.demo
    val hasKey: Boolean get() = demo || secrets.get(SOURCE_OPERA) != null
    /** Something can run without the user: radar with its key, or Open-Meteo, which needs none. */
    val canRun: Boolean get() = hasKey || prefs.value.nowcastSource == NowcastSource.OPEN_METEO
    private val openMeteo get() = prefs.value.nowcastSource == NowcastSource.OPEN_METEO

    fun regionFor(lat: Double, lon: Double): Region {
        val regions = regionStore.regions.value
        val s = prefs.value
        return s.regionId?.let { id -> regions.firstOrNull { it.id == id && it.contains(lat, lon) } }
            ?: Regions.pick(regions, lat, lon)
            ?: s.regionId?.let { id -> regions.firstOrNull { it.id == id } }
            ?: regions.first()
    }

    /** Run a cycle for the region containing home (or [at], a map tap elsewhere). */
    suspend fun refresh(force: Boolean = false, at: Pair<Double, Double>? = null) = lock.withLock {
        if (at == null) follow()
        val s = prefs.value
        if (openMeteo && at == null) { refreshOpenMeteo(s); return@withLock }
        val key = secrets.get(SOURCE_OPERA)
        // Open-Meteo users without a key: the map has no radar, which is not a problem for the Now screen
        if (key == null && !demo) { if (!openMeteo) flow.update { it.copy(problem = Problem.NoKey, loading = false) }; return@withLock }
        val (lat, lon) = at ?: s.home
        val region = regionFor(lat, lon)
        flow.update { it.copy(loading = true, regionName = if (openMeteo) it.regionName else region.name) }
        try {
            val frames = withContext(Dispatchers.IO) {
                if (demo) Demo.frames(ctx, region)
                else RadarSource(key, File(ctx.cacheDir, "radar")).run { prune(); latestFrames(region) }
            }
            val old = flow.value.forecast
            val reuse = old != null && Cycle.skip(frames.last().time, old.issued, old.region == region, force)
            // another region: let go of the old ensemble first, two of them do not fit a 192 MB heap
            if (!reuse && old != null && old.region != region) flow.update { it.copy(forecast = null) }
            val fc = if (reuse) old!!
            else withContext(Dispatchers.Default) { Nowcaster(if (s.useModel) model(region) else null).run(region, frames) }
            if (fc.modelError != null && modelBroken == null) modelBroken = fc.modelError
            val note = when {
                !s.useModel -> null
                modelBroken != null -> "ML model failed (${modelBroken}); ensemble only until the app restarts"
                region.model != ModelStore.BUNDLED && !modelStore.isKnown(region.model) ->
                    "${region.model} not found; using the bundled model, which was not trained on this region"
                region.id != "be" && region.model == ModelStore.BUNDLED -> "The bundled model was trained on Belgium only"
                else -> null
            }
            if (openMeteo) flow.update { it.copy(forecast = fc) }      // a map look-up; home stays on Open-Meteo
            else {
                val (hl, ho) = s.home
                val home = fc.region.contains(hl, ho)
                val point = if (home) withContext(Dispatchers.Default) { fc.point(hl, ho) } else flow.value.point
                flow.update { it.copy(forecast = fc, point = point, issued = if (home) fc.issued else it.issued, modelOnly = false,
                    problem = null, modelNote = note, updated = Instant.now()) }
            }
        } catch (e: RadarException) {
            flow.update { it.copy(problem = Problem.Radar(e.message ?: "Radar unavailable")) }
        } catch (e: IOException) {
            flow.update { it.copy(problem = Problem.Network(e.message ?: e::class.simpleName ?: "offline")) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flow.update { it.copy(problem = Problem.Radar("${e::class.simpleName}: ${e.message}")) }
        } finally {
            flow.update { it.copy(loading = false) }
        }
        refreshOutlook(s)
        val st = flow.value
        if (!openMeteo) publish(st.forecast, st.point, full = true)
    }

    /** The Now screen, widgets and alert from Open-Meteo: one small request, no radar, no key needed. */
    private suspend fun refreshOpenMeteo(s: Settings) {
        flow.update { it.copy(loading = true, regionName = Snapshot.SOURCE_OPEN_METEO) }
        try {
            val (p, issued) = withContext(Dispatchers.IO) { OpenMeteoNowcast.fetch(s.home.first, s.home.second, secrets.get(SOURCE_OPEN_METEO)) }
            flow.update { it.copy(point = p, issued = issued, modelOnly = true, problem = null, modelNote = null, updated = Instant.now()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            flow.update { it.copy(problem = Problem.Network(e.message ?: e::class.simpleName ?: "offline")) }
        } catch (e: Exception) {
            flow.update { it.copy(problem = Problem.Radar("Open-Meteo: ${e.message}")) }
        } finally {
            flow.update { it.copy(loading = false) }
        }
        refreshOutlook(s)
        publishPoint(full = true)
    }

    private suspend fun publishPoint(full: Boolean) {
        val st = flow.value
        val p = st.point; val issued = st.issued
        if (p != null && issued != null && st.modelOnly) withContext(Dispatchers.IO) {
            val now = Instant.now()
            runCatching { Snapshot.ofPoint(p, issued, OutlookBars.join(st.outlook, p, issued, now), full, now).write(ctx.filesDir) }
        }
        runCatching { Widgets.updateAll(ctx) }
    }

    /**
     * Background: the point path on a [Cycle.BOX_KM] box around home, ~1 s instead of a full region run.
     * Feeds the widgets and the alert, not the screens (their graph is tied to the full forecast's issue
     * time). Returns the home point and its issue time; null when there was nothing new or it failed.
     */
    suspend fun refreshPoint(force: Boolean = false): Pair<PointForecast, Instant>? = lock.withLock {
        follow()
        val s = prefs.value
        if (openMeteo) {
            refreshOpenMeteo(s)
            val st = flow.value
            return@withLock if (st.problem == null && st.modelOnly) st.point?.let { p -> st.issued?.let { p to it } } else null
        }
        val key = secrets.get(SOURCE_OPERA)
        if (key == null && !demo) return@withLock null
        val home = s.home; val (lat, lon) = home
        val region = regionFor(lat, lon)
        var result: Pair<PointForecast, Instant>? = null
        var fc: Forecast? = null
        try {
            val frames = withContext(Dispatchers.IO) {
                if (demo) Demo.frames(ctx, region)
                else RadarSource(key, File(ctx.cacheDir, "radar")).run { prune(); latestFrames(region, 12, bbox = pointBbox(lat, lon, Cycle.BOX_KM)) }
            }
            val last = withContext(Dispatchers.IO) { Snapshot.read(ctx.filesDir) }
            val lastHome = last?.radar?.let { it.homeLat to it.homeLon }
            if (!Cycle.skip(frames.last().time, last?.let { Instant.ofEpochSecond(it.issued) }, Cycle.sameHome(home, lastHome), force)) {
                fc = withContext(Dispatchers.Default) { Cycle.box(Nowcaster(if (s.useModel) model(region) else null), region, frames, lat, lon) }
                if (fc.modelError != null && modelBroken == null) modelBroken = fc.modelError
                result = withContext(Dispatchers.Default) { fc.point(lat, lon) } to fc.issued
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the widgets show the age of what they have; the next cycle tries again
        }
        if (result != null) refreshOutlook(s)
        publish(fc, result?.first, full = false)
        result
    }

    /** Outlook at home, hourly or when home moved. Never replaced by anything invented on failure. */
    private suspend fun refreshOutlook(s: Settings) {
        val home = s.home
        val o = flow.value.outlook
        val moved = outlookAt?.let { abs(it.first - home.first) + abs(it.second - home.second) > 0.01 } ?: true
        if (o != null && !moved && Duration.between(o.fetched, Instant.now()).toMinutes() < 60) return
        try {
            val fresh = withContext(Dispatchers.IO) { fetchOutlook(home.first, home.second, outlookModel) }
            outlookAt = home
            flow.update { it.copy(outlook = fresh, outlookError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flow.update { it.copy(outlookError = e.message ?: "unreachable", outlook = if (moved) null else it.outlook) }
        }
    }

    private fun model(region: Region): SmaatModel? {
        if (modelBroken != null) return null
        val name = if (modelStore.isKnown(region.model)) region.model else ModelStore.BUNDLED
        smaat?.let { if (it.first == name) return it.second; it.second.close() }
        return try {
            SmaatModel(modelStore.bytes(name)).also { smaat = name to it }
        } catch (e: Exception) {
            modelBroken = e.message ?: e::class.simpleName; null
        }
    }

    /** Point for a map tap; another region's forecast is fetched first. */
    suspend fun pointAt(lat: Double, lon: Double): PointForecast? {
        val fc = flow.value.forecast
        if (fc == null || !fc.region.contains(lat, lon)) refresh(at = lat to lon)
        val now = flow.value.forecast ?: return null
        return withContext(Dispatchers.Default) { now.point(lat, lon) }
    }

    /** Following: home is where the phone is, checked before every cycle. Without a fix, home stays put. */
    private suspend fun follow() {
        if (!prefs.value.followLocation) return
        val l = Locator.fix(ctx) ?: return
        if (Locator.moved(prefs.value.home, l.latitude, l.longitude))
            prefs.update { it.copy(homeLat = l.latitude, homeLon = l.longitude) }
    }

    /** [manual]: picked on the map or by hand, which stops following; a live fix keeps it on. */
    suspend fun setHome(lat: Double, lon: Double, manual: Boolean = true) {
        prefs.update { it.copy(homeLat = lat, homeLon = lon, followLocation = it.followLocation && !manual) }
        val fc = flow.value.forecast
        if (fc != null && fc.region.contains(lat, lon) && !openMeteo)
            flow.update { it.copy(point = fc.point(lat, lon), issued = fc.issued) }
        refresh()
    }

    /** Snapshot for the widgets from either run, then re-render them all, so their age stays true even when a run failed. */
    private suspend fun publish(fc: Forecast?, p: PointForecast?, full: Boolean) {
        val home = prefs.value.home
        if (p != null && fc != null && fc.region.contains(home.first, home.second)) withContext(Dispatchers.IO) {
            val now = Instant.now()
            runCatching { Snapshot.of(fc, p, OutlookBars.join(flow.value.outlook, p, fc.issued, now), home, full, now).write(ctx.filesDir) }
        }
        runCatching { Widgets.updateAll(ctx) }
    }

    companion object {
        const val SOURCE_OPERA = "opera"
        /** Optional: a paid Open-Meteo key moves requests to the customer API. */
        const val SOURCE_OPEN_METEO = "open-meteo"
    }
}

/** Debug builds: the bundled OPERA sample, moved 2 px east per frame, stamped as if it were live. */
object Demo {
    fun frames(ctx: Context, region: Region): List<Frame> {
        val f = File(ctx.cacheDir, "demo.h5")
        if (!f.exists()) ctx.assets.open("opera_sample.h5").use { i -> f.outputStream().use { i.copyTo(it) } }
        val base = Odim.readCrop(f, region)
        val now = Instant.now().epochSecond
        val t0 = Instant.ofEpochSecond(now - now % 300)
        val g = base.rain
        return List(12) { k ->
            val dx = 2 * (11 - k)
            Frame(t0.minusSeconds(300L * (11 - k)),
                Grid(g.h, g.w, DoubleArray(g.h * g.w) { i -> val c = i % g.w + dx; if (c < g.w) g.v[i - i % g.w + c] else 0.0 }),
                base.geo)
        }
    }
}

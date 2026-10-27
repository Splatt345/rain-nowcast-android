package nowcast.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
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
)

/** One cycle for the screens, the worker and the widget. Holds the ensemble in memory so taps only re-trace points. */
class Repo(private val ctx: Context) {
    val prefs = Prefs(ctx)
    val secrets = Secrets(ctx)
    val regionStore = RegionStore(ctx)
    val modelStore = ModelStore(ctx)

    private val flow = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = flow
    private val lock = Mutex()

    private var modelBroken: String? = null           // a failed model is not retried until restart
    private var smaat: Pair<String, SmaatModel>? = null
    private val outlookModel by lazy { runCatching { OutlookModel(ctx.assets.open("outlook/outlook_gbm.onnx").readBytes()) }.getOrNull() }
    private var outlookAt: Pair<Double, Double>? = null

    val demo: Boolean get() = BuildConfig.DEBUG && prefs.value.demo
    val hasKey: Boolean get() = demo || secrets.get(SOURCE_OPERA) != null

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
        val s = prefs.value
        val key = secrets.get(SOURCE_OPERA)
        if (key == null && !demo) { flow.update { it.copy(problem = Problem.NoKey, loading = false) }; return@withLock }
        val (lat, lon) = at ?: s.home
        val region = regionFor(lat, lon)
        flow.update { it.copy(loading = true) }
        try {
            val frames = withContext(Dispatchers.IO) {
                if (demo) Demo.frames(ctx, region)
                else RadarSource(key, File(ctx.cacheDir, "radar")).run { prune(); latestFrames(region) }
            }
            val old = flow.value.forecast
            val fc = if (!force && old != null && old.region == region && old.issued == frames.last().time) old
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
            val (hl, ho) = s.home
            val point = if (fc.region.contains(hl, ho)) withContext(Dispatchers.Default) { fc.point(hl, ho) }
            else flow.value.point
            flow.update { it.copy(forecast = fc, point = point, problem = null, modelNote = note, updated = Instant.now()) }
        } catch (e: RadarException) {
            flow.update { it.copy(problem = Problem.Radar(e.message ?: "Radar unavailable")) }
        } catch (e: IOException) {
            flow.update { it.copy(problem = Problem.Network(e.message ?: e::class.simpleName ?: "offline")) }
        } catch (e: Exception) {
            flow.update { it.copy(problem = Problem.Radar("${e::class.simpleName}: ${e.message}")) }
        } finally {
            flow.update { it.copy(loading = false) }
        }
        refreshOutlook(s)
        writeSnapshot()
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

    suspend fun setHome(lat: Double, lon: Double) {
        prefs.update { it.copy(homeLat = lat, homeLon = lon) }
        val fc = flow.value.forecast
        if (fc != null && fc.region.contains(lat, lon))
            flow.update { it.copy(point = fc.point(lat, lon)) }
        refresh()
    }

    /** What the widget and the notification read when the process is cold. */
    private suspend fun writeSnapshot() {
        val st = flow.value
        val p = st.point ?: return
        val fc = st.forecast ?: return
        val age = Duration.between(fc.issued, Instant.now()).toMinutes().toInt()
        val probs = (0 until 12).map { k ->
            p.minutes.indices.filter { p.minutes[it] - age in k * 5 until k * 5 + 5 }.maxOfOrNull { p.probability[it] } ?: 0.0
        }
        ctx.getSharedPreferences(WIDGET, Context.MODE_PRIVATE).edit()
            .putString("summary", p.summary)
            .putString("probs", probs.joinToString(","))
            .putLong("issued", fc.issued.toEpochMilli())
            .putString("region", fc.region.name)
            .apply()
        runCatching { RainWidget().updateAll(ctx) }
    }

    companion object {
        const val SOURCE_OPERA = "opera"
        const val WIDGET = "widget"
    }
}

/** Debug builds: the bundled OPERA sample, moved 2 px east per frame, stamped as if it were live. */
object Demo {
    fun frames(ctx: Context, region: Region): List<Frame> {
        val f = File(ctx.cacheDir, "demo.h5")
        if (!f.exists()) ctx.assets.open("opera_sample.h5").use { i -> f.outputStream().use { i.copyTo(it) } }
        val base = Odim.readCrop(f, region)
        val now = Instant.now().epochSecond
        val t0 = Instant.ofEpochSecond(now - now % 300 - 300)
        val g = base.rain
        return List(12) { k ->
            val dx = 2 * (11 - k)
            Frame(t0.minusSeconds(300L * (11 - k)),
                Grid(g.h, g.w, DoubleArray(g.h * g.w) { i -> val c = i % g.w + dx; if (c < g.w) g.v[i - i % g.w + c] else 0.0 }),
                base.geo)
        }
    }
}

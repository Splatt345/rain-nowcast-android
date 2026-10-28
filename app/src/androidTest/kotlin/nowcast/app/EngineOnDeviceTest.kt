package nowcast.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nowcast.engine.forecast.*
import nowcast.engine.math.*
import nowcast.engine.radar.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    /** Peak used heap while [block] runs, sampled every 5 ms. */
    private fun <T> peakHeap(block: () -> T): Pair<T, Long> {
        val rt = Runtime.getRuntime(); System.gc()
        var peak = 0L; val run = AtomicBoolean(true)
        val th = Thread { while (run.get()) { peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()); Thread.sleep(5) } }
        th.start()
        try { return block() to peak } finally { run.set(false); th.join() }
    }

    private fun secs(t0: Long) = (System.nanoTime() - t0) / 1e9

    /** Stage by stage through the public API, so old and new engines time the same things.
     *  A function of its own so none of it is still reachable during the timed cycle. */
    private fun stages(frames: List<Frame>, model: SmaatModel, km: Double): String {
        val train = frames.map { it.rain }
        var t = System.nanoTime(); motion(train.takeLast(3).map { g -> Grid(g.h, g.w, DoubleArray(g.v.size) { toDb(g.v[it]) }) }); val tFlow = secs(t)
        t = System.nanoTime(); val ens = ensemble(train, 12, 10, 42, km, 5.0); val tEns = secs(t)
        t = System.nanoTime(); val ml = model.forecast(train, 12); val tModel = secs(t)
        t = System.nanoTime(); Combine.combine(ens.members, ml, Combine.WEIGHTS); val tComb = secs(t)
        return "flow %.2f, ensemble (incl. flow) %.2f, model %.2f, combine %.2f".format(tFlow, tEns, tModel, tComb)
    }

    @Test fun fullCycleOnDevice() {
        val sample = File(ctx.cacheDir, "opera_sample.h5")
        testCtx.assets.open("opera_sample.h5").use { i -> sample.outputStream().use { i.copyTo(it) } }
        val regions = Regions.parse(ctx.assets.open("regions.json").bufferedReader().readText())
        val be = regions.first { it.id == "be" }

        val t0 = System.nanoTime()
        val frame = Odim.readCrop(sample, be)
        // the sample moving east 2 px a frame: twelve identical frames would make STEPS' lag-1
        // correlation exactly 1 and its AR fit NaN, which no real sequence does
        val g = frame.geo
        val frames = List(12) { k -> val d = 2 * (11 - k)
            Frame(frame.time.plusSeconds(300L * (k - 11)), Grid(g.h, g.w, DoubleArray(g.h * g.w) { i ->
                frame.rain[i / g.w, minOf(g.w - 1, i % g.w + d)] }), g) }
        val tRead = secs(t0)
        var t = System.nanoTime()
        val model = SmaatModel(ctx.assets.open("smaat.onnx").readBytes())
        val tLoad = secs(t)
        // the background path first, as cold as it would be in a worker
        t = System.nanoTime()
        // at the sample's wettest spot: Brussels is dry in it, and a dry box skips STEPS altogether
        val (wLat, wLon) = (0..28).flatMap { i -> (0..48).map { j -> 49.0 + 0.1 * i to 2.0 + 0.1 * j } }.maxBy { (la, lo) ->
            val (r, c) = g.rowCol(la, lo); if (r in 0 until g.h && c in 0 until g.w) frame.rain[r, c] else -1.0 }
        val (bp, peakBox) = peakHeap { Nowcaster(model).runPoint(be, frames, wLat, wLon, now = frames.last().time) }
        val tBox = secs(t)
        val stages = stages(frames, model, frame.geo.px / 1000.0)
        // the cycle as the app runs it
        t = System.nanoTime()
        val (fc, peak) = peakHeap { Nowcaster(model).run(be, frames) }
        val tRun = secs(t)
        t = System.nanoTime(); val p = fc.point(50.85, 4.35, fc.issued); val tPoint = secs(t)
        Log.i("EngineOnDevice", ("cycle %.1f s (read %.2f + run %.1f + point %.2f), %dx%d, %d cores, peak heap %d MB of %d, " +
            "model=%s, summary=%s").format(tRead + tRun + tPoint, tRead, tRun, tPoint, fc.geo.h, fc.geo.w,
            Runtime.getRuntime().availableProcessors(), peak shr 20, Runtime.getRuntime().maxMemory() shr 20, fc.modelUsed, p.summary))
        Log.i("EngineOnDevice", "stages: model load %.2f, %s; run timings %s".format(
            tLoad, stages, fc.timings.entries.joinToString { "%s %.2f".format(it.key, it.value) }))
        Log.i("EngineOnDevice", "runPoint at %.1f N %.1f E %.2f s, peak heap %d MB, summary=%s".format(wLat, wLon, tBox, peakBox shr 20, bp.summary))

        assertTrue("model must run on device: ${fc.modelError}", fc.modelUsed)
        assertEquals(10, fc.members.size)
        assertTrue(p.inCoverage)
        assertTrue(p.probability.all { it in 0.0..1.0 })
        assertFalse("decimal comma: ${p.summary}", Regex("""\d,\d""").containsMatchIn(p.summary))
        assertTrue("cycle took ${tRead + tRun} s", tRead + tRun < 120)
    }
}

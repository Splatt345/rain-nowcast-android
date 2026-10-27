package nowcast.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nowcast.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    @Test fun fullCycleOnDevice() {
        val sample = File(ctx.cacheDir, "opera_sample.h5")
        testCtx.assets.open("opera_sample.h5").use { i -> sample.outputStream().use { i.copyTo(it) } }
        val regions = Regions.parse(ctx.assets.open("regions.json").bufferedReader().readText())
        val be = regions.first { it.id == "be" }

        val t0 = System.nanoTime()
        val frame = Odim.readCrop(sample, be)
        val frames = List(12) { k -> Frame(frame.time.plusSeconds(300L * (k - 11)), frame.rain, frame.geo) }
        val t1 = System.nanoTime()
        val model = SmaatModel(ctx.assets.open("smaat.onnx").readBytes())
        val fc = Nowcaster(model).run(be, frames)
        val t2 = System.nanoTime()
        val p = fc.point(50.85, 4.35, fc.issued)
        val secs = (System.nanoTime() - t0) / 1e9
        Log.i("EngineOnDevice", "cycle %.1f s (read %.1f, ensemble+model %.1f), %dx%d, %d cores, model=%s, summary=%s".format(
            secs, (t1 - t0) / 1e9, (t2 - t1) / 1e9, fc.geo.h, fc.geo.w,
            Runtime.getRuntime().availableProcessors(), fc.modelUsed, p.summary))

        assertTrue("model must run on device: ${fc.modelError}", fc.modelUsed)
        assertEquals(10, fc.members.size)
        assertTrue(p.inCoverage)
        assertTrue(p.probability.all { it in 0.0..1.0 })
        assertFalse("decimal comma: ${p.summary}", Regex("""\d,\d""").containsMatchIn(p.summary))
        assertTrue("cycle took $secs s", secs < 120)
    }
}

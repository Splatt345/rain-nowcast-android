package nowcast.app

import nowcast.engine.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class CacheTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t = Instant.parse("2026-10-28T12:00:00Z")
    private fun arr(v: Double) = DoubleArray(3) { v + it }

    @Test fun nowScreenStateSurvivesARestartAtTheSameHomeOnly() {
        val p = PointForecast(intArrayOf(-5, 0, 5), arr(0.1), arr(0.2), arr(0.3), arr(0.4), arr(0.5), arr(0.6), arr(0.7), arr(0.8),
            0.4, "Rain in 5 min", true)
        val o = Outlook(listOf(Bar(t, 1.0, 0.5, 2.0, 0.7, BarSource.MODEL)), t, true, null, OutlookMeta(listOf("icon_d2"), "icon_d2", listOf(2.0, 49.0, 7.0, 52.0)))
        val home = 50.85 to 4.35
        Saved.write(tmp.root, Saved.of(UiState(point = p, outlook = o, issued = t, modelOnly = true, regionName = "Belgium", updated = t), home))

        val st = Saved.read(tmp.root, home)!!.state()
        assertArrayEquals(p.pmax, st.point!!.pmax, 0.0); assertArrayEquals(p.minutes, st.point!!.minutes)
        assertEquals("Rain in 5 min", st.point!!.summary)
        assertEquals(t, st.issued); assertTrue(st.modelOnly); assertEquals("Belgium", st.regionName)
        assertEquals(BarSource.MODEL, st.outlook!!.bars.single().source); assertEquals(listOf("icon_d2"), st.outlook!!.meta.models)
        assertNull(Saved.read(tmp.root, 40.4 to -3.7))                  // home moved: nothing shown
    }

    @Test fun mapFramesRoundTrip() {
        val f = MapFrames(listOf(ByteArray(6) { it.toByte() }, ByteArray(6)), 3, 2, listOf(t, t.plusSeconds(300)),
            listOf(2.0, 49.0, 7.0, 52.0), "belgium", t, 0, Render.RasterMotion(2, 2, 8, floatArrayOf(1f, 2f, 3f, 4f), FloatArray(4)))
        MapFrames.write(tmp.root, f)
        val g = MapFrames.read(tmp.root)!!
        assertArrayEquals(f.levels[0], g.levels[0]); assertEquals(f.times, g.times); assertEquals(f.bbox, g.bbox)
        assertEquals("belgium", g.regionId); assertEquals(t, g.issued); assertEquals(3, g.w)
        assertArrayEquals(f.motion.dx, g.motion.dx, 0f)

        assertEquals(5, g.minutes)
        assertEquals(0 to 0.0, g.locate(0)); assertEquals(0 to 0.4, g.locate(2)); assertEquals(1 to 0.0, g.locate(5).let { (k, d) -> if (d == 1.0) k + 1 to 0.0 else k to d })
        assertSame(g.levels[0], g.levelsAt(0)); assertSame(g.levels[1], g.levelsAt(5))
    }
}

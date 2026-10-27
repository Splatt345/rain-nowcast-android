package nowcast.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RadarTest {
    private val be = Region("be", "Belgium", listOf(2.0, 49.0, 6.8, 51.8))
    private val sample = File(javaClass.getResource("/opera_sample.h5")!!.toURI())

    // From the reference path (Step 1), pysteps + clean_dbz + crop_to_belgium.
    private val EXPECTED_H = 338; private val EXPECTED_W = 364
    private val EXPECTED_MAX = 3.9183773079681647; private val EXPECTED_NAN = 0
    private val EXPECTED_150_180 = 0.000486

    @Test fun cropMatchesReference() {
        val f = Odim.readCrop(sample, be)
        assertEquals(EXPECTED_H, f.rain.h); assertEquals(EXPECTED_W, f.rain.w)
        assertEquals(EXPECTED_NAN, f.rain.v.count { it.isNaN() })
        assertEquals(EXPECTED_MAX, f.rain.v.filter { !it.isNaN() }.max(), 1e-3)
        assertEquals(EXPECTED_150_180, f.rain[150, 180], 1e-3)
        assertEquals(java.time.Instant.parse("2026-09-14T03:20:00Z"), f.time)
    }

    @Test fun cleaningMasksFillAndClipsClutter() {
        assertTrue(Odim.dbzToRain(128.0, 1.0, 0.0, -9999000.0, -8888000.0).isNaN())
        assertTrue(Odim.dbzToRain(-9999000.0, 1.0, 0.0, -9999000.0, -8888000.0).isNaN())
        assertEquals(0.0, Odim.dbzToRain(-8888000.0, 1.0, 0.0, -9999000.0, -8888000.0), 0.0)
        assertEquals(Odim.dbzToRain(55.0, 1.0, 0.0, -1.0, -2.0), Odim.dbzToRain(78.0, 1.0, 0.0, -1.0, -2.0), 1e-9)
        assertEquals(1.0, Odim.dbzToRain(10 * kotlin.math.log10(200.0), 1.0, 0.0, -1.0, -2.0), 1e-9)
    }

    @Test fun listingIsParsedOldestFirst() {
        val urls = extractUrls(javaClass.getResource("/ord_listing.json")!!.readText())
        assertTrue(urls.size >= 3)
        assertEquals(urls.sortedBy { Odim.parseTime(it) }, urls)
    }

    @Test fun pointLandsInTheCrop() {
        val f = Odim.readCrop(sample, be)
        val (r, c) = f.geo.rowCol(50.85, 4.35)
        assertTrue(r in 0 until f.rain.h && c in 0 until f.rain.w)
    }
}

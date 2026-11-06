package nowcast.engine.radar

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class KeylessTest {
    @Test fun outlinesPickTheHomeService() {
        assertTrue(inside(50.85, 4.35, RmiInca.HOME))             // Brussels
        assertTrue(inside(49.61, 6.13, RmiInca.HOME))             // Luxembourg
        assertFalse(inside(51.92, 4.48, RmiInca.HOME))            // Rotterdam
        assertFalse(inside(50.63, 3.06, RmiInca.HOME))            // Lille
        assertTrue(inside(48.21, 16.37, GeoSphereNowcast.HOME))   // Vienna
        assertTrue(inside(47.27, 11.39, GeoSphereNowcast.HOME))   // Innsbruck
        assertFalse(inside(48.14, 11.58, GeoSphereNowcast.HOME))  // Munich
        assertFalse(inside(46.05, 14.51, GeoSphereNowcast.HOME))  // Ljubljana
    }

    // GeoServer's text/plain answer, shrunk to 2×3
    private val inca = """Grid bounds: GeneralBounds[(4.0, 50.0), (4.03, 50.02)]
        |Grid CRS: EPSG:4326
        |Band 0:
        |-1.0 0.5 2.0
        |0.0 -1.0 3.0
        |""".trimMargin()

    @Test fun incaRowsNorthFirstAndDryBelowZero() {
        val grid = RmiInca.parse(inca)
        assertEquals(listOf(4.0, 50.0, 4.03, 50.02), grid.bounds)
        assertEquals(2, grid.rows); assertEquals(3, grid.cols)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 2f, 0f, 0f, 3f), grid.fields, 0f)
        val t = Instant.parse("2026-11-04T20:00:00Z")
        val clip = RmiInca.clip(grid.bounds, grid.rows, grid.cols, listOf(t, t.plusSeconds(600)), listOf(grid.fields, grid.fields))
        val (row, col) = clip.cell(50.015, 4.025)                 // centre of the north-east pixel
        assertEquals(0.0, row, 1e-9); assertEquals(2.0, col, 1e-9)
        assertEquals(t, clip.issued)
    }

    @Test fun geoSphereTimesAndPlaces() {
        val bytes = javaClass.getResource("/geosphere_nowcast.nc")!!.readBytes()
        val clip = GeoSphereNowcast.parse(bytes, listOf(11.3, 47.2, 11.45, 47.3))
        assertEquals(11, clip.rows); assertEquals(11, clip.cols); assertEquals(12, clip.times.size)
        assertEquals(Instant.parse("2026-11-04T20:30:00Z"), clip.issued)              // the first quarter ends 20:45
        assertEquals(Instant.parse("2026-11-04T20:37:30Z"), clip.times[0])
        assertTrue(clip.fields.all { field -> field.all { it >= 0f } })
        // the file's own lat/lon of its first and last cells, row 0 south
        val (r0, c0) = clip.cell(47.2026, 11.3124)
        assertEquals(0.0, r0, 0.15); assertEquals(0.0, c0, 0.15)
        val (r1, c1) = clip.cell(47.2948, 11.4412)
        assertEquals(10.0, r1, 0.15); assertEquals(10.0, c1, 0.15)
    }

    @Test fun metNorwayMinuteByMinute() {
        fun step(time: String, rate: Double) = """{"time":"$time","data":{"instant":{"details":{"precipitation_rate":$rate}}}}"""
        val json = """{"properties":{"meta":{"radar_coverage":"ok"},"timeseries":[${step("2026-11-04T20:00:00Z", 0.0)},
            ${step("2026-11-04T20:05:00Z", 1.0)},${step("2026-11-04T20:10:00Z", 3.0)}]}}"""
        val (point, issued) = MetNorwayNowcast.parse(json, Instant.parse("2026-11-04T20:02:00Z"))!!
        assertEquals(Instant.parse("2026-11-04T20:00:00Z"), issued)
        assertEquals(11, point.minutes.size)
        assertEquals(0.6, point.values[3], 1e-9)
        assertEquals(1.8, point.values[7], 1e-9)
        assertEquals(1.0, point.rainChance, 0.0)
        assertNull(MetNorwayNowcast.parse(json.replace("\"ok\"", "\"temporarily unavailable\""), Instant.now()))
    }

    @Test fun theCurveIsWhatTheMapShowsThere() {
        val grid = RmiInca.parse(inca)
        val t = Instant.parse("2026-11-04T20:00:00Z")
        val wetter = FloatArray(grid.fields.size) { grid.fields[it] + 2f }
        val clip = RmiInca.clip(grid.bounds, grid.rows, grid.cols, listOf(t, t.plusSeconds(600)), listOf(grid.fields, wetter))
        val (point, issued) = clip.point(50.015, 4.025, t.plusSeconds(120))!!               // the north-east pixel: 2.0, then 4.0
        assertEquals(t, issued)
        assertEquals(11, point.minutes.size)
        assertEquals(2.0, point.values[0], 1e-6); assertEquals(3.0, point.values[5], 1e-6); assertEquals(4.0, point.values[10], 1e-6)
        assertNull(clip.point(51.0, 4.0))                                                    // off the map
    }
}

package nowcast.engine.radar

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BrightSkyTest {
    // Bright Sky's answer for Brussels on 2026-11-04, shrunk to 2×3 pixels: its corners and its latlon_position (125.737, 200.071)
    private fun frame(time: String, source: String, wet: Int) =
        """{"timestamp":"$time+00:00","source":"RADARCOMP::RV::$source+00:00","precipitation_5":[[0,$wet,0],[0,0,0]]}"""
    private val json = """{"radar":[${frame("2026-11-04T20:05:00", "2026-11-04T20:05:00", 0)},${frame("2026-11-04T20:10:00", "2026-11-04T20:10:00", 10)},
        ${frame("2026-11-04T20:15:00", "2026-11-04T20:10:00", 25)}],
        "geometry":{"type":"Polygon","coordinates":[[2.29587,52.43617],[2.98751,49.03688],[7.1933,49.27395],[6.91378,52.70206]]},
        "bbox":[397,0,797,326],"latlon_position":{"x":125.737,"y":200.071}}"""

    @Test fun radarThenNowcast() {
        val clip = BrightSky.parse(json)
        assertEquals(Instant.parse("2026-11-04T20:10:00Z"), clip.issued)    // the last frame that is its own source
        assertEquals(1, clip.newest)
        assertEquals(1.2f, clip.fields[1][1], 1e-6f)                         // 0.10 mm in 5 min = 1.2 mm/h
        assertEquals(3.0f, clip.fields[2][1], 1e-6f)
        assertEquals(2, clip.rows); assertEquals(3, clip.cols)
    }

    @Test fun placesLandWhereBrightSkyPutsThem() {
        val (row, col) = BrightSky.parse(json).cell(50.85, 4.35)
        assertEquals(200.071, row, 0.05); assertEquals(125.737, col, 0.05)
    }

    @Test fun theMapRectangleIsInsideTheClip() {
        val (west, south, east, north) = BrightSky.parse(json).bbox
        assertEquals(2.98751, west, 1e-9); assertEquals(49.27395, south, 1e-9)
        assertEquals(6.91378, east, 1e-9); assertEquals(52.43617, north, 1e-9)
    }
}

package nowcast.engine.render

import nowcast.engine.math.*
import nowcast.engine.radar.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Reference values from ha-rain-nowcast's rain-nowcast/nowcast/render.py, run 2026-10-27. */
class RenderTest {
    private val geo = GridGeo(Laea.parse("+proj=laea +lat_0=55.0 +lon_0=10.0 +x_0=1950000.0 +y_0=-2100000.0"),
        1365000.0, -2450000.0, 1000.0, 1000.0, 300, 340)

    @Test fun levelsMatchPython() = assertEquals(listOf(0, 0, 24, 105, 182, 255, 60, 133),
        listOf(0.0, 0.02, 0.1, 1.0, 10.0, 100.0, 0.3, 2.5).map(Render::level))

    @Test fun levelEdges() = assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 255),
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 1e-3, 0.03, 0.05, 1e9).map(Render::level))

    @Test fun alphaMatchesPython() = assertEquals(listOf(0, 3, 34, 69, 138, 215, 215, 215, 215),
        listOf(0, 1, 10, 20, 40, 63, 64, 128, 255).map { Render.alphaLut()[it] })

    @Test fun lutMatchesPython() {
        fun rgb(t: IntArray, i: Int) = listOf(t[i] shr 16 and 255, t[i] shr 8 and 255, t[i] and 255)
        val bg = Render.lut("blue-green")
        assertEquals(listOf(150, 200, 255), rgb(bg, 0))
        assertEquals(listOf(99, 171, 251), rgb(bg, 64)); assertEquals(listOf(248, 169, 49), rgb(bg, 200))
        assertEquals(listOf(200, 50, 180), rgb(bg, 255))
        assertEquals(listOf(47, 106, 141), rgb(Render.lut("viridis"), 128))
        assertEquals(listOf(242, 109, 30), rgb(Render.lut("classic"), 200))
    }

    @Test fun mercatorRowsAreNotUniformAndNorthFirst() {
        val s = Render.mercatorSampler(geo, listOf(2.0, 49.0, 6.8, 51.8), width = 64)
        assertEquals(64 * s.height, s.rows.size)
        val col0 = (0 until s.height).map { s.rows[it * 64] }
        assertTrue(col0.first() < col0.last())                            // north edge first
        val d = col0.zipWithNext { a, b -> b - a }
        assertTrue(abs(d.first() - d.last()) > 1e-3)                       // Mercator spacing
    }

    @Test fun smoothingKeepsMassAndPeakButSoftensBlocks() {
        val rng = java.util.Random(1)
        val base = DoubleArray(16 * 16) { rng.nextDouble() * 20 }
        val blocky = Grid(64, 64, DoubleArray(64 * 64) { base[(it / 64 / 4) * 16 + (it % 64) / 4] })
        val soft = Render.gaussian(blocky, Render.SMOOTH_PX)
        fun jump(g: Grid) = (0 until 64).maxOf { r -> (0 until 63).maxOf { c -> abs(g[r, c + 1] - g[r, c]) } }
        assertTrue(jump(soft) < jump(blocky))
        assertEquals(blocky.v.average(), soft.v.average(), 0.05)
        assertTrue(soft.v.all { it >= 0 })
    }

    @Test fun overlayIsClearWhereDryAndColouredWhereWet() {
        val s = Render.mercatorSampler(geo, listOf(2.0, 49.0, 6.8, 51.8), width = 32)
        val field = FloatArray(300 * 340) { if (it % 340 > 170) 5f else 0f }
        val px = Render.argb(Render.levels(field, 300, 340, s), "viridis")
        assertEquals(32 * s.height, px.size)
        assertTrue(px.any { it ushr 24 == 0 } && px.any { it ushr 24 == 215 })
    }

    @Test fun customColoursSitOnTheDefaultRates() {
        assertTrue(Render.lut(Render.stops(emptyList())).contentEquals(Render.lut(Render.DEFAULT_PALETTE)))
        val red = Render.lut(Render.stops(List(9) { 0xFF0000 }))
        assertTrue(red.all { it == 0xFF0000 })
        assertEquals(0x123456, Render.stops(listOf(0x7F123456))[0].second)     // alpha is not a colour
    }
}

package nowcast.engine.forecast

import nowcast.engine.math.*
import nowcast.engine.render.*
import org.junit.Assert.*
import org.junit.Test

class WarpTest {
    private val w = 32; private val h = 16
    private fun blob(x: Int) = ByteArray(w * h).also { for (y in 6..9) for (c in x..x + 3) it[y * w + c] = 200.toByte() }
    private fun centre(l: ByteArray): Double {
        var s = 0.0; var n = 0.0
        for (i in l.indices) { val v = (l[i].toInt() and 255).toDouble(); s += v * (i % w); n += v }
        return s / n
    }

    @Test fun anEchoMovesBetweenFramesInsteadOfFading() {
        val m = Render.RasterMotion(5, 3, 8, FloatArray(15) { 4f }, FloatArray(15))
        val mid = Render.warp(blob(10), blob(14), w, h, m, 0.5)
        assertEquals(13.5, centre(mid), 0.01)                          // blob(12)'s centre: moved, not two half-ghosts
        assertEquals(200, mid[7 * w + 13].toInt() and 255)             // full strength in the middle
        assertEquals(0, mid[7 * w + 10].toInt() and 255)               // nothing left behind
    }

    @Test fun motionCarriesOntoTheRaster() {
        // a raster that IS the source grid, flipped: raster x = source col, raster y = rows counted from the bottom
        val rows = DoubleArray(w * h) { (h - 1 - it / w).toDouble() }; val cols = DoubleArray(w * h) { (it % w).toDouble() }
        val vx = Grid(h, w, DoubleArray(w * h) { 2.0 }); val vy = Grid(h, w, DoubleArray(w * h) { 1.0 })
        val m = Render.rasterMotion(Motion(vx, vy), Render.Sampler(rows, cols, w, h))
        assertEquals(2f, m.dx[m.cols + 1], 1e-6f); assertEquals(-1f, m.dy[m.cols + 1], 1e-6f)
    }
}

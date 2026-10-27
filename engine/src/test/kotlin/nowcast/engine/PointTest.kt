package nowcast.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class PointTest {
    // interpolate._demo: a blob crossing at 4 px/frame, closest approach between frames
    @Test fun advectedSeriesRecoversPeakBetweenFrames() {
        val ny = 64; val nx = 64; val vx = 4.0; val sigma = 2.5; val peak = 10.0
        fun field(t: Double) = FloatArray(ny * nx) { i ->
            val y = i / nx; val x = i % nx
            (peak * exp(-((x - (10 + vx * t)).let { it * it } + (y - 32.0) * (y - 32.0)) / (2 * sigma * sigma))).toFloat()
        }
        val frames = (0 until 6).map { field(it.toDouble()) }
        val m = Motion(Grid(ny, nx, DoubleArray(ny * nx) { vx }), Grid(ny, nx))
        val got = pointSeries(frames, ny, nx, m, 32, 28, subSteps = 5, box = 0)
        assertEquals(26, got.size)
        for (k in 0 until 6) assertEquals(frames[k][32 * nx + 28].toDouble(), got[k * 5], 1e-5)
        val truth = DoubleArray(got.size) { field(it / 5.0)[32 * nx + 28].toDouble() }
        assertTrue(got.indices.maxOf { abs(got[it] - truth[it]) } < 0.35)
        assertTrue(abs(got.max() - peak) < 0.35)
    }

    @Test fun percentileIsNumpyLinear() {
        val x = doubleArrayOf(3.0, 1.0, 2.0, 10.0)
        assertEquals(1.3, percentile(x, 10.0), 1e-12)     // np.percentile([3,1,2,10], 10)
        assertEquals(7.9, percentile(x, 90.0), 1e-12)
    }

    @Test fun combineShiftsMeanAndKeepsSpread() {
        val members = Array(3) { m -> Array(1) { FloatArray(1) { 2f + m } } }   // 2, 3, 4
        val other = Array(1) { FloatArray(1) { 5f } }
        val out = Combine.combine(members, other, doubleArrayOf(0.5))
        // mean 3 -> 3 + 0.5*(5-3) = 4; deviations -1, 0, +1 kept
        assertEquals(3f, out[0][0][0], 1e-6f); assertEquals(5f, out[2][0][0], 1e-6f)
    }

    @Test fun summaryWording() {
        val minutes = IntArray(11) { it * 6 - 6 }          // -6..54
        val dry = DoubleArray(11)
        val p0 = DoubleArray(11)
        assertEquals("Dry for the next 54 minutes", summary(minutes, dry, p0, 0))
        val wet = DoubleArray(11) { if (it >= 4) 2.0 else 0.0 }
        val p = DoubleArray(11) { if (it >= 4) 0.8 else 0.0 }
        assertEquals("Rain starting in 18 min (80% chance), up to 2.0 mm/h", summary(minutes, wet, p, 0))
        val now = DoubleArray(11) { if (it <= 3) 1.5 else 0.0 }
        assertEquals("Raining now, 1.5 mm/h, stopping in 18 min", summary(minutes, now, p0, 0))
        val maybe = DoubleArray(11) { if (it == 6) 0.3 else 0.0 }
        assertEquals("Probably dry, but a 30% chance of rain in the next 54 min",
            summary(minutes, dry, maybe, 0))
        assertEquals("No current forecast: the newest radar is 60 min old", summary(minutes, dry, p0, 60))
    }
}

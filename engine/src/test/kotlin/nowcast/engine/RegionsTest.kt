package nowcast.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionsTest {
    private val opera = Laea.parse("+proj=laea +lat_0=55.0 +lon_0=10.0 +x_0=1950000.0 +y_0=-2100000.0 +units=m +ellps=WGS84")

    @Test fun laeaMatchesPyproj() {
        fun check(lat: Double, lon: Double, x: Double, y: Double) {
            val (gx, gy) = opera.forward(lat, lon)
            assertEquals(x, gx, 0.5); assertEquals(y, gy, 0.5)
        }
        check(50.85, 4.35, 1552343.7575896685, -2545914.669368048)
        check(49.0, 2.0, 1365249.6702690017, -2734530.7415946946)
        check(51.8, 6.8, 1729263.2655303152, -2451114.123408953)
        check(35.0, -10.0, 120716.40479281009, -4064567.455407303)
    }

    @Test fun pickPrefersTheRegionThePointIsDeepestIn() {
        val rs = Regions.parse(javaClass.getResource("/regions.json")!!.readText())
        assertEquals("be", Regions.pick(rs, 50.85, 4.35)!!.id)          // Brussels
        assertEquals("nl", Regions.pick(rs, 52.37, 4.90)!!.id)          // Amsterdam
        assertEquals("be", Regions.pick(rs, 50.6, 6.2)!!.id)            // Eifel: Luxembourg's centre is nearer
        assertEquals(null, Regions.pick(rs, 40.0, -3.7))                // Madrid
    }
}

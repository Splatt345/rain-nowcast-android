package nowcast.app

import nowcast.engine.PointForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class AlertsTest {
    private val issued = Instant.parse("2026-10-27T12:00:00Z")
    private val now = issued.plusSeconds(300)                       // radar 5 min old
    private fun point(wetFrom: Int?, p: Double, wetNow: Boolean = false): PointForecast {
        val mins = IntArray(116) { it - 55 }
        val v = DoubleArray(116) { i -> if ((wetFrom != null && mins[i] >= wetFrom) || (wetNow && mins[i] in 0..10)) 1.0 else 0.0 }
        return PointForecast(mins, v, DoubleArray(116) { if (v[it] > 0) p else 0.0 }, v, v, v, v, v, v, p, "", true)
    }

    @Test fun dryIsQuiet() = assertNull(Alerts.minutesToRain(point(null, 0.0), issued, now, 30, 0.5))
    @Test fun rainAheadInsideTheLead() = assertEquals(20, Alerts.minutesToRain(point(25, 0.8), issued, now, 30, 0.5))
    @Test fun rainBeyondTheLead() = assertNull(Alerts.minutesToRain(point(25, 0.8), issued, now, 15, 0.5))
    @Test fun unlikelyRain() = assertNull(Alerts.minutesToRain(point(25, 0.3), issued, now, 30, 0.5))
    @Test fun alreadyRaining() = assertNull(Alerts.minutesToRain(point(25, 0.8, wetNow = true), issued, now, 30, 0.5))
}

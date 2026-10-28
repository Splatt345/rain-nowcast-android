package nowcast.engine

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MinutelyTest {
    // 11:00 .. 13:15 every 15 min, values for the quarter hour ENDING at each time
    private val times = (0..9).map { "\"2026-10-28T%02d:%02d\"".format(11 + it * 15 / 60, it * 15 % 60) }
    private fun json(a: List<Double?>, b: List<Double?>) = """{"minutely_15":{"time":[${times.joinToString(",")}],
        "precipitation_icon_d2":[${a.joinToString(",")}],"precipitation_gfs_global":[${b.joinToString(",")}]}}"""

    @Test fun quarterHoursBecomeMinutesOfAnEnsemble() {
        val now = Instant.parse("2026-10-28T12:07:00Z")
        val h = OpenMeteo.parse(json(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 0.0, 0.0),
                                     listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)), "minutely_15")
        val (p, issued) = OpenMeteoNowcast.point(h, now)
        assertEquals(Instant.parse("2026-10-28T12:00:00Z"), issued)
        assertEquals(-60, p.minutes.first()); assertEquals(60, p.minutes.last())
        fun at(m: Int) = p.minutes.indexOf(m)
        // 12:00 belongs to the slot ending 12:00; 12:01 to the one ending 12:15 (0 mm), 12:16..12:30 to 0.5 mm -> 2 mm/h
        assertEquals(0.0, p.values[at(1)], 1e-12)
        assertEquals(1.0, p.values[at(16)], 1e-12)                 // mean of 2 and 0 mm/h
        assertEquals(0.5, p.probability[at(16)], 1e-12)            // one member of two is wet
        assertEquals(2.0, p.values[at(31)], 1e-12)                 // 1 mm in 12:30-12:45 -> 4 mm/h, mean 2
        assertEquals(4.0, p.pmax[at(31)], 1e-12); assertEquals(0.0, p.pmin[at(31)], 1e-12)
        assertEquals(0.5, p.rainChance, 1e-12)
        assertTrue(p.summary, p.summary.contains("Rain", ignoreCase = true))
    }

    @Test fun membersWithGapsAreDropped() {
        val h = OpenMeteo.parse(json(List(10) { 0.0 }, List(10) { if (it == 3) null else 0.2 }), "minutely_15")
        val (p, _) = OpenMeteoNowcast.point(h, Instant.parse("2026-10-28T12:07:00Z"))
        assertEquals(0.0, p.rainChance, 0.0)                         // only the complete, dry member is left
    }

    @Test fun theKeyOnlyGoesToTheCustomerApi() {
        val free = OpenMeteoNowcast.url(50.85, 4.35, null)
        assertTrue(free.startsWith("https://api.open-meteo.com/")); assertFalse("apikey" in free)
        val paid = OpenMeteoNowcast.url(50.85, 4.35, "k")
        assertTrue(paid.startsWith("https://customer-api.open-meteo.com/")); assertTrue(paid.endsWith("&apikey=k"))
    }
}

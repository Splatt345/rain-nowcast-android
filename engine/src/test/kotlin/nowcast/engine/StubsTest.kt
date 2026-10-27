package nowcast.engine

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.exp

/** Plan 1 Task 9's NowcasterTest, run against the stand-ins; it must pass against the real engine too. */
class StubsTest {
    private val be = Region("be", "Belgium", listOf(2.0, 49.0, 6.8, 51.8))
    private val geo = GridGeo(Laea.parse("+proj=laea +lat_0=55.0 +lon_0=10.0 +x_0=1950000.0 +y_0=-2100000.0"),
        1365000.0, -2450000.0, 1000.0, 1000.0, 120, 140)

    private fun frames(): List<Frame> {
        val t0 = Instant.parse("2026-10-27T12:00:00Z")
        return (0 until 12).map { t ->
            Frame(t0.plusSeconds(300L * t), Grid(120, 140, DoubleArray(120 * 140) { i ->
                val y = i / 140; val x = i % 140
                4.0 * exp(-(((x - 20 - 3.0 * t) * (x - 20 - 3.0 * t) + (y - 60.0) * (y - 60.0)) / 80.0))
            }), geo)
        }
    }

    @Test fun cycleWithoutModelProducesCoherentPoint() {
        val fc = Nowcaster(model = null, nMembers = 4).run(be, frames())
        assertFalse(fc.modelUsed)
        assertEquals(5, fc.intervalMinutes)
        assertEquals(4, fc.members.size); assertEquals(12, fc.members[0].size)
        val p = fc.point(50.0, 4.0, now = Instant.parse("2026-10-27T12:58:00Z"))
        assertEquals(p.minutes.size, p.values.size)
        assertEquals(-55, p.minutes.first()); assertEquals(60, p.minutes.last())
        assertTrue(p.probability.all { it in 0.0..1.0 })
        assertTrue(p.p10.indices.all { p.p10[it] <= p.values[it] + 1e-9 && p.values[it] <= p.p90[it] + 1e-9 })
        assertTrue(p.summary.isNotBlank())
    }
}

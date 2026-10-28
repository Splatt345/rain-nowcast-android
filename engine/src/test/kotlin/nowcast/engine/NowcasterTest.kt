package nowcast.engine

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.math.exp

class NowcasterTest {
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
        val (lat, lon) = 50.0 to 4.0
        val p = fc.point(lat, lon, now = Instant.parse("2026-10-27T12:58:00Z"))
        assertEquals(p.minutes.size, p.values.size)
        assertEquals(-55, p.minutes.first()); assertEquals(60, p.minutes.last())
        assertTrue(p.probability.all { it in 0.0..1.0 })
        assertTrue(p.p10.indices.all { p.p10[it] <= p.values[it] + 1e-9 && p.values[it] <= p.p90[it] + 1e-9 })
        assertTrue(p.summary.isNotBlank())
    }

    @Test fun membersInAMappedFileMatchAndLeaveNoFile() {
        val d = java.nio.file.Files.createTempDirectory("members").toFile()
        val a = Nowcaster(model = null, nMembers = 4, dir = d).run(be, frames())
        val b = Nowcaster(model = null, nMembers = 4).run(be, frames())
        for (m in 0 until 4) for (k in 0 until 12) assertArrayEquals(b.members[m][k], a.members[m][k], 0f)
        assertEquals(0, d.listFiles()!!.size); d.delete()
    }

    @Test fun rainAheadOfTheBlobArrives() {
        val fc = Nowcaster(model = null, nMembers = 4).run(be, frames())
        // blob centre at x = 53 in the last frame, moving +3 px/frame: x = 70 is reached in ~30 min
        val ahead = fc.mean.map { it[60 * 140 + 70] }
        assertTrue("mean along the track: $ahead", ahead.drop(3).maxOf { it.toDouble() } > 0.5)
    }

    @Test fun pointRunOnABoxAgreesWithTheFullRun() {
        // put (50, 4) on the blob's track, 17 px ahead of its last position
        val (x, y) = geo.laea.forward(50.0, 4.0)
        val g = GridGeo(geo.laea, x - 70 * 1000.0, y + 60 * 1000.0, 1000.0, 1000.0, 120, 140)
        val fr = frames().map { Frame(it.time, it.rain, g) }
        val now = Instant.parse("2026-10-27T12:58:00Z")
        val nc = Nowcaster(model = null, nMembers = 4)
        val full = nc.run(be, fr).point(50.0, 4.0, now)
        val box = nc.runPoint(be, fr, 50.0, 4.0, boxKm = 64.0, now = now)
        assertTrue(full.minutes.contentEquals(box.minutes))
        assertTrue(box.inCoverage)
        val dv = full.values.indices.maxOf { kotlin.math.abs(full.values[it] - box.values[it]) }
        val dp = full.probability.indices.maxOf { kotlin.math.abs(full.probability[it] - box.probability[it]) }
        println("POINTBOX synthetic: max |dvalue| %.3f mm/h, max |dprob| %.2f, chance %.2f vs %.2f".format(dv, dp, full.rainChance, box.rainChance))
        assertTrue(full.rainChance > 0.5 && box.rainChance > 0.5)
        // a 64 km box tapers the blob at its edge: same arrival, peak within 30%, not the same curve
        fun arrival(p: PointForecast) = p.minutes.indices.first { p.minutes[it] > 0 && p.values[it] >= 1.0 }
        assertTrue(kotlin.math.abs(full.minutes[arrival(full)] - box.minutes[arrival(box)]) <= 5)
        assertEquals(full.values.max(), box.values.max(), 0.3 * full.values.max())
    }
}

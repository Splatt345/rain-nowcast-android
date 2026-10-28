package nowcast.app.widget

import nowcast.engine.outlook.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class SnapshotTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = Instant.parse("2026-10-28T12:02:00Z")

    @Test fun roundTripsThroughJsonAndDisk() {
        val s = Snapshot.sample(now)
        assertEquals(s, Snapshot.decode(Snapshot.encode(s)))
        s.write(tmp.root)
        assertEquals(s, Snapshot.read(tmp.root))
        assertFalse(File(tmp.root, "${Snapshot.FILE}.tmp").exists())
        val f = s.radar!!.frame(30)!!
        assertEquals(s.radar!!.w * s.radar!!.h, f.bytes().size)
        assertEquals(30, s.radar!!.frame(40)!!.lead)
    }

    @Test fun openMeteoSnapshotHasNoRadarAndSaysWhereItIsFrom() {
        val now = Instant.parse("2026-10-28T12:07:00Z")
        val t = (0..9).map { "\"2026-10-28T%02d:%02d\"".format(11 + it * 15 / 60, it * 15 % 60) }.joinToString(",")
        val h = OpenMeteo.parse("""{"minutely_15":{"time":[$t],"precipitation_icon_d2":[0,0,0,0,0,0,0.5,1,0,0]}}""", "minutely_15")
        val (p, issued) = OpenMeteoNowcast.point(h, now)
        val s = Snapshot.decode(Snapshot.encode(Snapshot.ofPoint(p, issued, emptyList(), full = true, now = now)))!!
        assertNull(s.radar); assertEquals(Snapshot.SOURCE_OPEN_METEO, s.source)
        assertEquals(7, s.age(now)); assertEquals(100, s.chance(now))
    }

    @Test fun missingBrokenOrMismatchedIsNull() {
        assertNull(Snapshot.read(tmp.root))
        File(tmp.root, Snapshot.FILE).writeText("{\"issued\": 1")
        assertNull(Snapshot.read(tmp.root))
        val s = Snapshot.sample(now)
        assertNull(Snapshot.decode(Snapshot.encode(s.copy(prob = s.prob.drop(1)))))
        assertNull(Snapshot.decode(Snapshot.encode(s.copy(v = 99))))
        // a newer writer's extra fields do not break an older reader
        assertEquals(s, Snapshot.decode(Snapshot.encode(s).replaceFirst("{", "{\"future\":1,")))
    }

    @Test fun sentenceAndChanceAgeWithTheRadar() {
        val issued = now.epochSecond
        val s = Snapshot(issued = issued, written = issued, region = "Belgium", minutes = (0..60).toList(),
            mean = (0..60).map { if (it >= 30) 1f else 0f }, p10 = List(61) { 0f }, p90 = List(61) { 2f },
            prob = (0..60).map { if (it >= 30) 0.8f else 0.1f })
        assertTrue(s.summary(now).startsWith("Rain starting in 30 min (80% chance), up to 1"))
        assertTrue(s.summary(now.plusSeconds(600)).startsWith("Rain starting in 20 min"))
        assertEquals(80, s.chance(now.plusSeconds(600)))
        assertNull(s.chance(now.plusSeconds(61 * 60L)))
        assertTrue(s.summary(now.plusSeconds(75 * 60L)).startsWith("No current forecast"))
    }

    @Test fun freshness() {
        assertEquals("radar 7 min ago", Freshness.text(7))
        assertEquals("Radar 47 min old", Freshness.text(47))
        assertEquals("Radar 3 h old", Freshness.text(185))
        assertEquals("Open-Meteo 7 min ago", Freshness.text(7, Snapshot.SOURCE_OPEN_METEO))
        assertFalse(Freshness.stale(29)); assertTrue(Freshness.stale(30))
    }
}

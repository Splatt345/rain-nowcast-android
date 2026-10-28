package nowcast.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CycleTest {
    private val t = Instant.parse("2026-10-28T12:05:00Z")

    @Test fun skipsOnlyTheSameFrameForTheSameTarget() {
        assertTrue(Cycle.skip(t, t, sameTarget = true, force = false))
        assertTrue(Cycle.skip(t, t.plusMillis(400), sameTarget = true, force = false))    // whole seconds, as stored
        assertFalse(Cycle.skip(t.plusSeconds(300), t, sameTarget = true, force = false))   // a new frame
        assertFalse(Cycle.skip(t, t, sameTarget = false, force = false))                   // other region or home moved
        assertFalse(Cycle.skip(t, null, sameTarget = true, force = false))                 // first run
        assertFalse(Cycle.skip(t, t, sameTarget = true, force = true))                     // pull to refresh
    }

    @Test fun homeMatchesWithinAMetreOrSo() {
        assertTrue(Cycle.sameHome(50.85 to 4.35, 50.850001 to 4.350001))
        assertFalse(Cycle.sameHome(50.85 to 4.35, 50.86 to 4.35))
        assertFalse(Cycle.sameHome(50.85 to 4.35, null))
    }
}

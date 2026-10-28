package nowcast.app

import nowcast.engine.Regions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RegionsTest {
    private val bundled = Regions.parse(File("src/main/assets/regions.json").readText())

    // ponytail: deepest-box picking; Arlon, on the Luxembourg border, still goes to Luxembourg's box (radar-wise fine)
    @Test fun belgianTownsGetBelgium() {
        // Antwerp and Turnhout used to land in the Netherlands' box, which reached deeper around them
        val towns = mapOf("Antwerp" to (51.22 to 4.40), "Turnhout" to (51.32 to 4.94), "Bruges" to (51.21 to 3.22),
            "Ostend" to (51.23 to 2.92), "Ghent" to (51.05 to 3.72), "Brussels" to (50.85 to 4.35), "Kortrijk" to (50.83 to 3.27),
            "Mons" to (50.45 to 3.95), "Hasselt" to (50.93 to 5.34), "Liège" to (50.63 to 5.57), "Namur" to (50.47 to 4.87))
        for ((name, ll) in towns) assertEquals(name, "be", Regions.pick(bundled, ll.first, ll.second)?.id)
    }

    @Test fun neighboursKeepTheirOwn() {
        assertEquals("nl", Regions.pick(bundled, 52.37, 4.90)?.id)      // Amsterdam
        assertEquals("lu", Regions.pick(bundled, 49.61, 6.13)?.id)      // Luxembourg
        assertEquals("de-nrw", Regions.pick(bundled, 50.94, 6.96)?.id)  // Cologne
        assertEquals("fr-nord", Regions.pick(bundled, 50.29, 2.78)?.id) // Arras
    }
}

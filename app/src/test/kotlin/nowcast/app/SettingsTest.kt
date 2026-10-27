package nowcast.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test fun roundTrips() {
        val s = Settings(homeLat = 51.2, homeLon = 4.4, regionId = "nl", view = NowView.OUTLOOK, palette = "viridis",
            notify = false, notifyLeadMin = 15, notifyThreshold = 0.7, background = false, useModel = false)
        assertEquals(s, Settings.fromMap(s.toMap()))
    }

    @Test fun emptyIsDefaultsAndHomeFallsBackToBrussels() {
        assertEquals(Settings(), Settings.fromMap(emptyMap<String, String>()))
        assertEquals(Settings.DEFAULT_HOME, Settings().home)
    }

    @Test fun garbageIsToleratedAndRangesClamped() {
        val s = Settings.fromMap(mapOf("homeLat" to "north", "homeLon" to "4.4", "view" to "MAP", "notify" to "yes",
            "notifyLeadMin" to "500", "notifyThreshold" to "NaN", "regionId" to ""))
        assertEquals(null, s.homeLat); assertEquals(null, s.homeLon)    // half a home is no home
        assertEquals(NowView.NOWCAST, s.view); assertEquals(true, s.notify)
        assertEquals(60, s.notifyLeadMin); assertEquals(0.5, s.notifyThreshold, 0.0)
        assertEquals(null, s.regionId)
        assertEquals(0.1, Settings.fromMap(mapOf("notifyThreshold" to "0.01")).notifyThreshold, 0.0)
    }
}

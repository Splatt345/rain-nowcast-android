package nowcast.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test fun roundTrips() {
        val s = Settings(homeLat = 51.2, homeLon = 4.4, followLocation = true, regionId = "nl", view = NowView.OUTLOOK, palette = "viridis",
            notify = false, notifyLeadMin = 15, notifyThreshold = 0.7, background = false, useModel = false,
            radarColors = List(9) { it * 0x111111 }, appColors = AppColors.CUSTOM, accent = 0xAB12CD, darkMode = DarkMode.DARK,
            nowcastSource = NowcastSource.OPEN_METEO)
        assertEquals(s, Settings.fromMap(s.toMap()))
        assertEquals(s.copy(palette = "custom"), Settings.fromMap(s.copy(palette = "custom").toMap()))
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
        val c = Settings.fromMap(mapOf("palette" to "rainbow", "radarColors" to "FF0000,zz", "accent" to "#12345", "appColors" to "NEON"))
        assertEquals(Settings(), c)
        assertEquals(0x00FF00, Settings.fromMap(mapOf("accent" to "#00ff00")).accent)
        assertEquals(0.1, Settings.fromMap(mapOf("notifyThreshold" to "0.01")).notifyThreshold, 0.0)
    }
}

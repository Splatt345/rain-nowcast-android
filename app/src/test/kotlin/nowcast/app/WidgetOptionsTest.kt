package nowcast.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetOptionsTest {
    @Test fun roundTrips() {
        val o = WidgetOptions(WidgetType.RADAR, WidgetTheme.DYNAMIC, 40, RainUnits.WORDS, false, TapAction.REFRESH, 30, RefreshButton.BOTTOM)
        assertEquals(o, WidgetOptions.fromMap(o.toMap()))
    }

    @Test fun widgetsFromBeforeOptionsKeepTheirLookAndProviderType() {
        assertEquals(WidgetOptions(), WidgetOptions.fromMap(emptyMap<String, Any>()))
        assertEquals(WidgetOptions(type = WidgetType.HOURS), WidgetOptions.fromMap(mapOf("rev" to 12L), WidgetType.HOURS))
        val d = WidgetOptions()
        assertEquals(WidgetTheme.SYSTEM, d.theme); assertEquals(100, d.opacity); assertEquals(true, d.showChance)
        assertEquals(TapAction.OPEN_NOW, d.tap); assertEquals(0, d.lead); assertEquals(RainUnits.MMH, d.units)
        assertEquals(RefreshButton.OFF, d.refresh)
    }

    @Test fun garbageFallsBackOneByOne() {
        val o = WidgetOptions.fromMap(mapOf("type" to "PIE", "theme" to "DARK", "opacity" to "250", "showChance" to "yes",
            "lead" to "45", "tap" to "OPEN_MAP", "refresh" to "LEFT"), WidgetType.MINUTES)
        assertEquals(WidgetOptions(type = WidgetType.MINUTES, theme = WidgetTheme.DARK, tap = TapAction.OPEN_MAP), o)
        assertEquals(0, WidgetOptions.fromMap(mapOf("opacity" to "-3")).opacity)
    }

    @Test fun layoutPerSize() {
        assertEquals(SizeClass.SMALL, WidgetLayout.sizeClass(150f, 70f))     // 2×1
        assertEquals(SizeClass.SMALL, WidgetLayout.sizeClass(80f, 30f))      // smaller than every bucket
        assertEquals(SizeClass.SMALL, WidgetLayout.sizeClass(350f, 80f))     // 4×1 stays one line
        assertEquals(SizeClass.MEDIUM, WidgetLayout.sizeClass(350f, 180f))   // 4×2
        assertEquals(SizeClass.LARGE, WidgetLayout.sizeClass(350f, 400f))    // 4×4
        assertEquals(SizeClass.SMALL, WidgetLayout.sizeClass(150f, 400f))    // narrow and tall
        assertEquals(WidgetType.MINUTES to SizeClass.MEDIUM, WidgetLayout.pick(WidgetType.COMBINED, 350f, 180f))
        assertEquals(WidgetType.COMBINED to SizeClass.LARGE, WidgetLayout.pick(WidgetType.COMBINED, 350f, 400f))
        assertEquals(WidgetType.RADAR to SizeClass.SMALL, WidgetLayout.pick(WidgetType.RADAR, 150f, 70f))
        assertEquals(12, WidgetLayout.hours(SizeClass.SMALL)); assertEquals(48, WidgetLayout.hours(SizeClass.MEDIUM))
    }

    @Test fun rainWords() {
        assertEquals("Moderate rain now, stopping in 20 min", RainWords.sentence("Raining now, 3.1 mm/h, stopping in 20 min", RainUnits.WORDS))
        assertEquals("Rain starting in 5 min (60% chance), up to heavy",
            RainWords.sentence("Rain starting in 5 min (60% chance), up to 12,4 mm/h", RainUnits.WORDS))
        assertEquals("Raining now, 3.1 mm/h", RainWords.sentence("Raining now, 3.1 mm/h", RainUnits.MMH))
    }
}

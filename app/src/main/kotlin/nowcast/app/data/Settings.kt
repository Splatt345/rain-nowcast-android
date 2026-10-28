package nowcast.app.data

import nowcast.engine.render.*

/** What the user chose. Plain Kotlin so it is JVM-tested; Prefs stores toMap() as strings. */
enum class NowView { NOWCAST, OUTLOOK }
enum class AppColors(val label: String) { WALLPAPER("Wallpaper"), RAIN("Rain"), CUSTOM("Custom") }
enum class DarkMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
/** Where the next hour comes from: OPERA radar (MeteoGate key) or Open-Meteo's models (free, no key, worldwide). */
enum class NowcastSource(val label: String) { RADAR("Radar"), OPEN_METEO("Open-Meteo") }

data class Settings(
    val homeLat: Double? = null, val homeLon: Double? = null,
    /** Home moves with the phone (coarse location). */
    val followLocation: Boolean = false,
    val regionId: String? = null,                      // null = auto (the region containing home)
    val view: NowView = NowView.NOWCAST,
    val palette: String = Render.DEFAULT_PALETTE,
    /** RGB per rate stop, used when [palette] is [Render.CUSTOM]. */
    val radarColors: List<Int> = DEFAULT_RADAR,
    val appColors: AppColors = AppColors.WALLPAPER,
    val accent: Int = 0x1F5FA8,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val notify: Boolean = true,
    val notifyLeadMin: Int = 30,
    val notifyThreshold: Double = 0.5,
    val background: Boolean = true,
    val useModel: Boolean = true,
    val nowcastSource: NowcastSource = NowcastSource.RADAR,
) {
    fun radarLut(): IntArray = if (palette == Render.CUSTOM) Render.lut(Render.stops(radarColors)) else Render.lut(palette)

    val home: Pair<Double, Double> get() = if (homeLat != null && homeLon != null) homeLat to homeLon else DEFAULT_HOME

    fun toMap(): Map<String, String> = buildMap {
        homeLat?.let { put("homeLat", it.toString()) }; homeLon?.let { put("homeLon", it.toString()) }
        regionId?.let { put("regionId", it) }
        put("followLocation", followLocation.toString())
        put("view", view.name); put("palette", palette)
        put("radarColors", radarColors.joinToString(",") { hex(it) }); put("appColors", appColors.name)
        put("accent", hex(accent)); put("darkMode", darkMode.name)
        put("notify", notify.toString())
        put("notifyLeadMin", notifyLeadMin.toString()); put("notifyThreshold", notifyThreshold.toString())
        put("background", background.toString()); put("useModel", useModel.toString())
        put("nowcastSource", nowcastSource.name)
    }

    companion object {
        /** Brussels: where the bundled model was trained. */
        val DEFAULT_HOME = 50.85 to 4.35
        val LEAD_RANGE = 5..60
        val THRESHOLD_RANGE = 0.1..0.9
        val DEFAULT_RADAR = Render.PALETTES.getValue(Render.DEFAULT_PALETTE).map { it.second }

        fun hex(rgb: Int) = "%06X".format(rgb and 0xFFFFFF)
        fun parseHex(s: String): Int? = s.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)

        fun fromMap(m: Map<String, *>): Settings {
            val d = Settings()
            fun s(k: String) = m[k]?.toString()
            fun b(k: String, def: Boolean) = s(k)?.toBooleanStrictOrNull() ?: def
            val lat = s("homeLat")?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
            val lon = s("homeLon")?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
            return Settings(
                homeLat = if (lon != null) lat else null, homeLon = if (lat != null) lon else null,
                followLocation = b("followLocation", d.followLocation),
                regionId = s("regionId")?.takeIf { it.isNotBlank() },
                view = NowView.entries.firstOrNull { it.name == s("view") } ?: d.view,
                palette = s("palette")?.takeIf { it == Render.CUSTOM || it in Render.PALETTES } ?: d.palette,
                radarColors = s("radarColors")?.split(",")?.map { parseHex(it) }
                    ?.takeIf { l -> l.size == DEFAULT_RADAR.size && l.all { it != null } }?.map { it!! } ?: d.radarColors,
                appColors = AppColors.entries.firstOrNull { it.name == s("appColors") } ?: d.appColors,
                accent = s("accent")?.let(::parseHex) ?: d.accent,
                darkMode = DarkMode.entries.firstOrNull { it.name == s("darkMode") } ?: d.darkMode,
                notify = b("notify", d.notify),
                notifyLeadMin = (s("notifyLeadMin")?.toIntOrNull() ?: d.notifyLeadMin)
                    .coerceIn(LEAD_RANGE.first, LEAD_RANGE.last),
                notifyThreshold = (s("notifyThreshold")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: d.notifyThreshold)
                    .coerceIn(THRESHOLD_RANGE.start, THRESHOLD_RANGE.endInclusive),
                background = b("background", d.background), useModel = b("useModel", d.useModel),
                nowcastSource = NowcastSource.entries.firstOrNull { it.name == s("nowcastSource") } ?: d.nowcastSource,
            )
        }
    }
}

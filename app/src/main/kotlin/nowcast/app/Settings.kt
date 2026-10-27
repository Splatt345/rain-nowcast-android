package nowcast.app

/** What the user chose. Plain Kotlin so it is JVM-tested; Prefs stores toMap() as strings. */
enum class NowView { NOWCAST, OUTLOOK }

data class Settings(
    val homeLat: Double? = null, val homeLon: Double? = null,
    val regionId: String? = null,                      // null = auto (the region containing home)
    val view: NowView = NowView.NOWCAST,
    val palette: String = "blue-green",
    val notify: Boolean = true,
    val notifyLeadMin: Int = 30,
    val notifyThreshold: Double = 0.5,
    val background: Boolean = true,
    val useModel: Boolean = true,
    val demo: Boolean = false,                         // debug builds only: bundled sample radar, no key
) {
    val home: Pair<Double, Double> get() = if (homeLat != null && homeLon != null) homeLat to homeLon else DEFAULT_HOME

    fun toMap(): Map<String, String> = buildMap {
        homeLat?.let { put("homeLat", it.toString()) }; homeLon?.let { put("homeLon", it.toString()) }
        regionId?.let { put("regionId", it) }
        put("view", view.name); put("palette", palette); put("notify", notify.toString())
        put("notifyLeadMin", notifyLeadMin.toString()); put("notifyThreshold", notifyThreshold.toString())
        put("background", background.toString()); put("useModel", useModel.toString()); put("demo", demo.toString())
    }

    companion object {
        /** Brussels: where the bundled model was trained. */
        val DEFAULT_HOME = 50.85 to 4.35
        val LEAD_RANGE = 5..60
        val THRESHOLD_RANGE = 0.1..0.9

        fun fromMap(m: Map<String, *>): Settings {
            val d = Settings()
            fun s(k: String) = m[k]?.toString()
            fun b(k: String, def: Boolean) = s(k)?.toBooleanStrictOrNull() ?: def
            val lat = s("homeLat")?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
            val lon = s("homeLon")?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
            return Settings(
                homeLat = if (lon != null) lat else null, homeLon = if (lat != null) lon else null,
                regionId = s("regionId")?.takeIf { it.isNotBlank() },
                view = NowView.entries.firstOrNull { it.name == s("view") } ?: d.view,
                palette = s("palette") ?: d.palette,
                notify = b("notify", d.notify),
                notifyLeadMin = (s("notifyLeadMin")?.toIntOrNull() ?: d.notifyLeadMin)
                    .coerceIn(LEAD_RANGE.first, LEAD_RANGE.last),
                notifyThreshold = (s("notifyThreshold")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: d.notifyThreshold)
                    .coerceIn(THRESHOLD_RANGE.start, THRESHOLD_RANGE.endInclusive),
                background = b("background", d.background), useModel = b("useModel", d.useModel),
                demo = b("demo", d.demo),
            )
        }
    }
}

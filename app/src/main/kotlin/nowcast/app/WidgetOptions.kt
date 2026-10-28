package nowcast.app

/** Per-widget choices, stored as strings in that widget's Glance state. Plain Kotlin so it is JVM-tested. */
enum class WidgetType(val label: String) {
    SUMMARY("Summary"), MINUTES("Next hour, per minute"), HOURS("Next 48 h"), RADAR("Radar"), COMBINED("Combined")
}

enum class WidgetTheme(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), DYNAMIC("Dynamic colour") }
enum class RainUnits(val label: String) { MMH("mm/h"), WORDS("Words") }
enum class TapAction(val label: String) { OPEN_NOW("Open Now"), OPEN_MAP("Open Map"), REFRESH("Refresh now") }

data class WidgetOptions(
    val type: WidgetType = WidgetType.SUMMARY,
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val opacity: Int = 100,
    val units: RainUnits = RainUnits.MMH,
    val showChance: Boolean = true,
    val tap: TapAction = TapAction.OPEN_NOW,
    /** Radar type: minutes after the newest frame, one of [LEADS]. */
    val lead: Int = 0,
) {
    fun toMap(): Map<String, String> = mapOf(
        "v" to VERSION.toString(), "type" to type.name, "theme" to theme.name, "opacity" to opacity.toString(),
        "units" to units.name, "showChance" to showChance.toString(), "tap" to tap.name, "lead" to lead.toString())

    companion object {
        const val VERSION = 1
        val LEADS = listOf(0, 30, 60)
        val KEYS = WidgetOptions().toMap().keys

        /**
         * Widgets placed before options existed have an empty state: they get [default] (the provider's type)
         * and the look they always had. Unknown or broken values fall back one by one.
         */
        fun fromMap(m: Map<String, *>, default: WidgetType = WidgetType.SUMMARY): WidgetOptions {
            fun s(k: String) = m[k]?.toString()
            val d = WidgetOptions(type = default)
            return WidgetOptions(
                type = WidgetType.entries.firstOrNull { it.name == s("type") } ?: d.type,
                theme = WidgetTheme.entries.firstOrNull { it.name == s("theme") } ?: d.theme,
                opacity = (s("opacity")?.toIntOrNull() ?: d.opacity).coerceIn(0, 100),
                units = RainUnits.entries.firstOrNull { it.name == s("units") } ?: d.units,
                showChance = s("showChance")?.toBooleanStrictOrNull() ?: d.showChance,
                tap = TapAction.entries.firstOrNull { it.name == s("tap") } ?: d.tap,
                lead = s("lead")?.toIntOrNull()?.takeIf { it in LEADS } ?: d.lead,
            )
        }
    }
}

enum class SizeClass { SMALL, MEDIUM, LARGE }

/** The responsive buckets (dp) and what each type draws in them. */
object WidgetLayout {
    val SMALL = 110f to 40f        // 2×1
    val MEDIUM = 230f to 100f      // 4×2
    val LARGE = 230f to 220f       // 4×4
    val BUCKETS = listOf(SMALL, MEDIUM, LARGE)

    /** What the launcher does with responsive layouts: the largest bucket that fits, else the smallest. */
    fun bucket(w: Float, h: Float): Pair<Float, Float> =
        BUCKETS.filter { it.first <= w && it.second <= h }.maxByOrNull { it.first * it.second } ?: SMALL

    fun sizeClass(w: Float, h: Float): SizeClass = when (bucket(w, h)) {
        LARGE -> SizeClass.LARGE
        MEDIUM -> SizeClass.MEDIUM
        else -> SizeClass.SMALL
    }

    /** Combined only fits a large widget; smaller, it is the per-minute graph. */
    fun pick(type: WidgetType, w: Float, h: Float): Pair<WidgetType, SizeClass> {
        val c = sizeClass(w, h)
        return (if (type == WidgetType.COMBINED && c != SizeClass.LARGE) WidgetType.MINUTES else type) to c
    }

    /** Next 48 h shows 12 h when small. */
    fun hours(c: SizeClass): Int = if (c == SizeClass.SMALL) 12 else 48
}

/** light / moderate / heavy instead of mm/h. */
object RainWords {
    fun word(mmh: Double): String = when {
        mmh < 2.5 -> "light"
        mmh < 10.0 -> "moderate"
        else -> "heavy"
    }

    private val now = Regex("""^Raining now, ([\d.,]+) mm/h""")
    private val upTo = Regex("""up to ([\d.,]+) mm/h""")

    fun sentence(s: String, units: RainUnits): String {
        if (units == RainUnits.MMH) return s
        return s.replace(now) { m -> "${word(m.groupValues[1].replace(',', '.').toDouble()).replaceFirstChar { it.uppercase() }} rain now" }
            .replace(upTo) { m -> "up to ${word(m.groupValues[1].replace(',', '.').toDouble())}" }
    }
}

package nowcast.app.widget

/** Per-widget choices, stored as strings in that widget's Glance state. Plain Kotlin so it is JVM-tested. */
enum class WidgetType(val label: String) {
    SUMMARY("Summary"), MINUTES("Next hour, per minute"), HOURS("Next 48 h"), RADAR("Radar"), COMBINED("Combined")
}

/** SYSTEM, LIGHT and DARK use the app's colours (Settings → Look); DYNAMIC is always the wallpaper's. */
enum class WidgetTheme(val label: String) { SYSTEM("App"), LIGHT("Light"), DARK("Dark"), DYNAMIC("Wallpaper") }
enum class RainUnits(val label: String) { MMH("mm/h"), WORDS("Words") }
enum class TapAction(val label: String) { OPEN_NOW("Open Now"), OPEN_MAP("Open Map"), REFRESH("Refresh now") }
enum class TextSize(val label: String, val scale: Float) { NORMAL("Normal", 1f), LARGE("Large", 1.2f), HUGE("Extra large", 1.4f) }
enum class RefreshButton(val label: String) { OFF("None"), TOP("Top corner"), BOTTOM("Bottom corner") }

data class WidgetOptions(
    val type: WidgetType = WidgetType.SUMMARY,
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val opacity: Int = 100,
    val units: RainUnits = RainUnits.MMH,
    val showChance: Boolean = true,
    val tap: TapAction = TapAction.OPEN_NOW,
    /** Radar type: minutes after the newest frame, one of [LEADS]. */
    val lead: Int = 0,
    val refresh: RefreshButton = RefreshButton.OFF,
    val text: TextSize = TextSize.NORMAL,
) {
    fun toMap(): Map<String, String> = mapOf(
        "v" to VERSION.toString(), "type" to type.name, "theme" to theme.name, "opacity" to opacity.toString(),
        "units" to units.name, "showChance" to showChance.toString(), "tap" to tap.name, "lead" to lead.toString(),
        "refresh" to refresh.name, "text" to text.name)

    companion object {
        const val VERSION = 1
        val LEADS = listOf(0, 30, 60)
        val KEYS = WidgetOptions().toMap().keys

        /**
         * An empty state gets [default] (the provider's type) and the default look.
         * Unknown or broken values fall back one by one.
         */
        fun fromMap(stored: Map<String, *>, default: WidgetType = WidgetType.SUMMARY): WidgetOptions {
            fun value(key: String) = stored[key]?.toString()
            val defaults = WidgetOptions(type = default)
            return WidgetOptions(
                type = WidgetType.entries.firstOrNull { it.name == value("type") } ?: defaults.type,
                theme = WidgetTheme.entries.firstOrNull { it.name == value("theme") } ?: defaults.theme,
                opacity = (value("opacity")?.toIntOrNull() ?: defaults.opacity).coerceIn(0, 100),
                units = RainUnits.entries.firstOrNull { it.name == value("units") } ?: defaults.units,
                showChance = value("showChance")?.toBooleanStrictOrNull() ?: defaults.showChance,
                tap = TapAction.entries.firstOrNull { it.name == value("tap") } ?: defaults.tap,
                lead = value("lead")?.toIntOrNull()?.takeIf { it in LEADS } ?: defaults.lead,
                refresh = RefreshButton.entries.firstOrNull { it.name == value("refresh") } ?: defaults.refresh,
                text = TextSize.entries.firstOrNull { it.name == value("text") } ?: defaults.text,
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
    fun bucket(width: Float, height: Float): Pair<Float, Float> =
        BUCKETS.filter { it.first <= width && it.second <= height }.maxByOrNull { it.first * it.second } ?: SMALL

    fun sizeClass(width: Float, height: Float): SizeClass = when (bucket(width, height)) {
        LARGE -> SizeClass.LARGE
        MEDIUM -> SizeClass.MEDIUM
        else -> SizeClass.SMALL
    }

    /** Combined only fits a large widget; smaller, it is the per-minute graph. */
    fun pick(type: WidgetType, width: Float, height: Float): Pair<WidgetType, SizeClass> {
        val size = sizeClass(width, height)
        return (if (type == WidgetType.COMBINED && size != SizeClass.LARGE) WidgetType.MINUTES else type) to size
    }

    /** Next 48 h shows 12 h when small. */
    fun hours(size: SizeClass): Int = if (size == SizeClass.SMALL) 12 else 48
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

    private val nbsp = Regex("""(\d) (mm/h|mm|min|minutes|h|hours)\b""")
    private val chanceAside = Regex(""" \(\d+% chance\)""")

    /**
     * What a widget prints: [sentence] in the chosen units, the chance in brackets dropped when the widget
     * shows it big anyway, and numbers glued to their units so "6 h" never breaks across lines.
     */
    fun widget(text: String, units: RainUnits, chanceShown: Boolean): String =
        sentence(text, units).let { if (chanceShown) it.replace(chanceAside, "") else it }.replace(nbsp, "$1\u00A0$2")

    /** For one line on a 2×1: the engine's four sentences (Point.summary) cut to their point. */
    fun short(text: String): String {
        Regex("""^Raining now.*stopping in (\d+) min""").find(text)?.let { return "Rain now, stops in ${it.groupValues[1]}\u00A0min" }
        if (text.startsWith("Raining now")) return "Raining now"
        Regex("""^Rain starting in (\d+) min""").find(text)?.let { return "Rain in ${it.groupValues[1]}\u00A0min" }
        if (text.startsWith("Probably dry")) return "Probably dry"
        Regex("""^Dry for the next (\d+) minutes""").find(text)?.let { return "Dry for ${it.groupValues[1]}\u00A0min" }
        return text
    }

    fun sentence(text: String, units: RainUnits): String {
        if (units == RainUnits.MMH) return text
        return text.replace(now) { match -> "${word(match.groupValues[1].replace(',', '.').toDouble()).replaceFirstChar { it.uppercase() }} rain now" }
            .replace(upTo) { match -> "up to ${word(match.groupValues[1].replace(',', '.').toDouble())}" }
    }
}

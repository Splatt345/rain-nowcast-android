package nowcast.app

import nowcast.engine.PointForecast
import nowcast.engine.RAIN_THRESHOLD
import java.time.Duration
import java.time.Instant

object Alerts {
    /** Minutes from now to the first likely-wet minute within the lead; null if raining already or nothing qualifies. */
    fun minutesToRain(p: PointForecast, issued: Instant, now: Instant, leadMin: Int, threshold: Double): Int? {
        val age = Duration.between(issued, now).toMinutes().toInt()
        val at = p.minutes.indexOfFirst { it >= age }
        if (at < 0 || p.values[at] >= RAIN_THRESHOLD) return null
        for (i in at until p.minutes.size) {
            val ahead = p.minutes[i] - age
            if (ahead > leadMin) return null
            if (p.values[i] >= RAIN_THRESHOLD && p.probability[i] >= threshold) return ahead
        }
        return null
    }
}

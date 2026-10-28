package nowcast.app.background

import nowcast.engine.forecast.*
import java.time.Duration
import java.time.Instant

object Alerts {
    /** Minutes from now to the first likely-wet minute within the lead; null if raining already or nothing qualifies. */
    fun minutesToRain(forecast: PointForecast, issued: Instant, now: Instant, leadMin: Int, threshold: Double): Int? {
        val age = Duration.between(issued, now).toMinutes().toInt()
        val at = forecast.minutes.indexOfFirst { it >= age }
        if (at < 0 || forecast.values[at] >= RAIN_THRESHOLD) return null
        for (i in at until forecast.minutes.size) {
            val ahead = forecast.minutes[i] - age
            if (ahead > leadMin) return null
            if (forecast.values[i] >= RAIN_THRESHOLD && forecast.probability[i] >= threshold) return ahead
        }
        return null
    }
}

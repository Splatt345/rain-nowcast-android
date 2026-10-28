package nowcast.engine.outlook

import nowcast.engine.forecast.*
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * The no-radar nowcast: Open-Meteo's 15-minute precipitation from every model that covers the place,
 * as a small ensemble. Free without a key (non-commercial use); a key moves it to the customer API.
 * Model output, not observed rain: true 15-minute data from the local models (ICON-D2, AROME, HRRR,
 * JMA MSM…), hourly spread over the quarter hours from the global ones elsewhere.
 */
object OpenMeteoNowcast {
    /** Five or more of these answer anywhere (checked 2026-10-28: Brussels 11, New York 6, Tokyo 6, Nairobi 5). */
    val MODELS = listOf("icon_d2", "meteofrance_arome_france_hd", "knmi_harmonie_arome_europe", "dmi_harmonie_arome_europe",
        "ukmo_uk_deterministic_2km", "ncep_hrrr_conus", "jma_msm", "icon_eu", "ecmwf_ifs025", "gfs_global", "icon_global",
        "gem_seamless", "ukmo_global_deterministic_10km")
    const val SLOT_MIN = 15
    const val PAST_MIN = 60
    const val AHEAD_MIN = 60

    /** Open-Meteo takes the key only as a query parameter, so it travels in the URL (HTTPS); it is never logged here. */
    fun url(lat: Double, lon: Double, apiKey: String?): String =
        (if (apiKey == null) "https://api.open-meteo.com" else "https://customer-api.open-meteo.com") +
            "/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.ROOT, lat, lon) +
            "&minutely_15=precipitation&past_minutely_15=${PAST_MIN / SLOT_MIN + 1}&forecast_minutely_15=${AHEAD_MIN / SLOT_MIN + 1}" +
            "&models=${MODELS.joinToString(",")}&timezone=GMT" + (apiKey?.let { "&apikey=$it" } ?: "")

    fun fetch(lat: Double, lon: Double, apiKey: String?, now: Instant = Instant.now()): Pair<PointForecast, Instant> =
        point(OpenMeteo.parse(OpenMeteo.get(url(lat, lon, apiKey)), "minutely_15"), now)

    /**
     * Minute by minute from [PAST_MIN] before to [AHEAD_MIN] after the current quarter hour, which is the
     * issue time. A value at t is the precipitation of the 15 minutes before t, so a minute takes the slot
     * ending at or after it, times 4 for mm/h.
     */
    fun point(h: Hourly, now: Instant): Pair<PointForecast, Instant> {
        val members = h.series.values.filter { s -> s.isNotEmpty() && s.all { it.isFinite() } }
        if (members.isEmpty()) throw OutlookException("Open-Meteo has no 15-minute precipitation here")
        val issued = Instant.ofEpochSecond(now.epochSecond - Math.floorMod(now.epochSecond, SLOT_MIN * 60L))
        val minutes = IntArray(PAST_MIN + AHEAD_MIN + 1) { it - PAST_MIN }
        fun slot(m: Int): Int? {
            val t = issued.plusSeconds(60L * m)
            return h.time.indexOfFirst { !it.isBefore(t) && Duration.between(t, it).toMinutes() < SLOT_MIN }.takeIf { it >= 0 }
        }
        val idx = minutes.map(::slot)
        if (idx.any { it == null }) throw OutlookException("Open-Meteo's 15-minute series does not cover the hour around now")
        val curves = members.map { s -> DoubleArray(minutes.size) { s[idx[it]!!] * 60.0 / SLOT_MIN } }
        fun at(t: Int) = DoubleArray(curves.size) { curves[it][t] }
        val mean = DoubleArray(minutes.size) { at(it).average() }
        val prob = DoubleArray(minutes.size) { t -> curves.count { it[t] >= RAIN_THRESHOLD }.toDouble() / curves.size }
        fun q(p: Double) = DoubleArray(minutes.size) { percentile(at(it), p) }
        val zero = minutes.indexOfFirst { it >= 0 }
        val age = Duration.between(issued, now).toMinutes().toInt()
        return PointForecast(minutes, mean, prob, q(0.0), q(10.0), q(25.0), q(75.0), q(90.0), q(100.0),
            prob.copyOfRange(zero, prob.size).max(), summary(minutes, mean, prob, age), true) to issued
    }
}

package nowcast.engine.radar

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Region(
    val id: String, val name: String, val bbox: List<Double>,
    val source: String = "opera", val model: String = "smaat.onnx",
    val weights: List<Double>? = null, val outlook: String = "icon_d2",
) {
    fun contains(lat: Double, lon: Double) = lon in bbox[0]..bbox[2] && lat in bbox[1]..bbox[3]
}

object Regions {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<Region> = json.decodeFromString(text)

    /** The containing region where the point is deepest inside (distance to the nearest
     *  edge, in km), so rain has the most room to arrive from; null outside them all. */
    fun pick(regions: List<Region>, lat: Double, lon: Double): Region? =
        regions.filter { it.contains(lat, lon) }.maxByOrNull {
            val kx = 111.32 * Math.cos(Math.toRadians(lat))
            minOf((lon - it.bbox[0]) * kx, (it.bbox[2] - lon) * kx,
                  (lat - it.bbox[1]) * 110.57, (it.bbox[3] - lat) * 110.57)
        }
}

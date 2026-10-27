package nowcast.engine

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

    /** The containing region whose centre is nearest; null outside them all. */
    fun pick(regions: List<Region>, lat: Double, lon: Double): Region? =
        regions.filter { it.contains(lat, lon) }.minByOrNull {
            val cy = (it.bbox[1] + it.bbox[3]) / 2; val cx = (it.bbox[0] + it.bbox[2]) / 2
            (lat - cy) * (lat - cy) + (lon - cx) * (lon - cx)
        }
}

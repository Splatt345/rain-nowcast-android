package nowcast.engine.forecast

import nowcast.engine.math.*
import nowcast.engine.radar.*
import kotlin.math.exp

/** Checks a user region or model must pass before the app accepts it. */
object Validate {
    val SOURCES = listOf("opera")
    private val ID = Regex("[a-z0-9-]{1,32}")
    private const val MAX_SIDE_DEG = 15.0          // ~1500 km: keeps the 10-member ensemble within memory

    fun region(candidate: Region, otherIds: Collection<String>): List<String> {
        val errors = ArrayList<String>()
        if (!ID.matches(candidate.id)) errors += "Id: 1-32 lower-case letters, digits or dashes"
        else if (candidate.id in otherIds) errors += "Id '${candidate.id}' is already used"
        if (candidate.name.isBlank()) errors += "Name is empty"
        val box = candidate.bbox
        if (box.size != 4 || box.any { !it.isFinite() }) errors += "Box needs four numbers: west, south, east, north"
        else {
            val (west, south, east, north) = box
            when {
                west >= east || south >= north -> errors += "Box: west must be < east and south < north"
                west < -30 || east > 45 || south < 30 || north > 75 -> errors += "Box is outside OPERA coverage (30-75°N, 30°W-45°E)"
                east - west > MAX_SIDE_DEG || north - south > MAX_SIDE_DEG -> errors += "Box is larger than $MAX_SIDE_DEG° on a side"
            }
        }
        if (candidate.source !in SOURCES) errors += "Unknown radar source '${candidate.source}'"
        candidate.weights?.let { if (it.size != 12 || it.any { weight -> weight !in 0.0..1.0 }) errors += "Weights: 12 values between 0 and 1" }
        return errors
    }

    /** Blank -> null (use the bundled weights). */
    fun weights(text: String): List<Double>? {
        if (text.isBlank()) return null
        val values = text.split(',', ' ', ';').filter { it.isNotBlank() }.map {
            it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$it' is not a number")
        }
        require(values.size == 12) { "Need 12 weights, one per 5-min lead, got ${values.size}" }
        require(values.all { it in 0.0..1.0 }) { "Weights must be between 0 and 1" }
        return values
    }

    /** null = accepted. A test forward pass: 12 frames in, 12 out, finite, <= 500 mm/h (SmaatModel refuses the rest). */
    fun model(bytes: ByteArray): String? = try {
        SmaatModel(bytes).use { model ->
            val side = 64
            val frames = List(12) { step -> Grid(side, side, DoubleArray(side * side) { index ->
                val y = index / side; val x = index % side; val dx = x - 16 - 2.0 * step; val dy = y - 32.0
                8.0 * exp(-(dx * dx + dy * dy) / 60.0)
            }) }
            val out = model.forecast(frames, 12)
            if (out.size != 12 || out.any { it.size != side * side }) "Model must return 12 frames of the input size" else null
        }
    } catch (problem: Throwable) {
        if (problem is VirtualMachineError && problem !is OutOfMemoryError) throw problem
        "Not a usable 12-frame rain model: ${problem.message ?: problem::class.simpleName}"
    }
}

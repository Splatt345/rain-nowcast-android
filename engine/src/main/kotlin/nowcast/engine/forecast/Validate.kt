package nowcast.engine.forecast

import nowcast.engine.math.*
import nowcast.engine.radar.*
import kotlin.math.exp

/** Checks a user region or model must pass before the app accepts it. */
object Validate {
    val SOURCES = listOf("opera")
    private val ID = Regex("[a-z0-9-]{1,32}")
    private const val MAX_SIDE_DEG = 15.0          // ~1500 km: keeps the 10-member ensemble within memory

    fun region(r: Region, otherIds: Collection<String>): List<String> {
        val e = ArrayList<String>()
        if (!ID.matches(r.id)) e += "Id: 1-32 lower-case letters, digits or dashes"
        else if (r.id in otherIds) e += "Id '${r.id}' is already used"
        if (r.name.isBlank()) e += "Name is empty"
        val b = r.bbox
        if (b.size != 4 || b.any { !it.isFinite() }) e += "Box needs four numbers: west, south, east, north"
        else {
            val (w, s, ea, n) = b
            when {
                w >= ea || s >= n -> e += "Box: west must be < east and south < north"
                w < -30 || ea > 45 || s < 30 || n > 75 -> e += "Box is outside OPERA coverage (30-75°N, 30°W-45°E)"
                ea - w > MAX_SIDE_DEG || n - s > MAX_SIDE_DEG -> e += "Box is larger than $MAX_SIDE_DEG° on a side"
            }
        }
        if (r.source !in SOURCES) e += "Unknown radar source '${r.source}'"
        r.weights?.let { if (it.size != 12 || it.any { w -> w !in 0.0..1.0 }) e += "Weights: 12 values between 0 and 1" }
        return e
    }

    /** Blank -> null (use the bundled weights). */
    fun weights(text: String): List<Double>? {
        if (text.isBlank()) return null
        val v = text.split(',', ' ', ';').filter { it.isNotBlank() }.map {
            it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$it' is not a number")
        }
        require(v.size == 12) { "Need 12 weights, one per 5-min lead, got ${v.size}" }
        require(v.all { it in 0.0..1.0 }) { "Weights must be between 0 and 1" }
        return v
    }

    /** null = accepted. A test forward pass: 12 frames in, 12 out, finite, <= 500 mm/h (SmaatModel refuses the rest). */
    fun model(bytes: ByteArray): String? = try {
        SmaatModel(bytes).use { m ->
            val n = 64
            val frames = List(12) { t -> Grid(n, n, DoubleArray(n * n) { i ->
                val y = i / n; val x = i % n; val dx = x - 16 - 2.0 * t; val dy = y - 32.0
                8.0 * exp(-(dx * dx + dy * dy) / 60.0)
            }) }
            val out = m.forecast(frames, 12)
            if (out.size != 12 || out.any { it.size != n * n }) "Model must return 12 frames of the input size" else null
        }
    } catch (e: Throwable) {
        if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
        "Not a usable 12-frame rain model: ${e.message ?: e::class.simpleName}"
    }
}

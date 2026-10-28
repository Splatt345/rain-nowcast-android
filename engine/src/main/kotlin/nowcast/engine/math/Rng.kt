package nowcast.engine.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Counter-based SplitMix64 + Box-Muller: each value depends only on the seed and its index. */
object Rng {
    private const val GOLDEN = -0x61c8864680b583ebL           // 0x9E3779B97F4A7C15
    private const val M1 = -0x40a7b892e31b1a47L               // 0xBF58476D1CE4E5B9
    private const val M2 = -0x6b2fb644ecceee15L               // 0x94D049BB133111EB

    private fun uniform(seed: Long, i: Int): Double {
        var z = seed + (i + 1).toLong() * GOLDEN
        z = (z xor (z ushr 30)) * M1
        z = (z xor (z ushr 27)) * M2
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() * (1.0 / (1L shl 53))
    }

    fun uniforms(seed: Long, n: Int) = DoubleArray(n) { uniform(seed, it) }

    /** Normal i from uniforms 2i and 2i+1; each depends only on its index, so [par] may split it. */
    fun normals(seed: Long, n: Int, par: Par = Par.SERIAL): DoubleArray = normalsInto(seed, DoubleArray(n), par)

    fun normalsInto(seed: Long, out: DoubleArray, par: Par = Par.SERIAL): DoubleArray {
        par.run(out.size) { a, b ->
            for (i in a until b)
                out[i] = sqrt(-2.0 * ln(1.0 - uniform(seed, 2 * i))) * cos(2.0 * PI * uniform(seed, 2 * i + 1))
        }
        return out
    }

    fun stream(seed: Long, member: Int, lead: Int): Long = seed * 1_000_003L + member * 1000L + lead
}

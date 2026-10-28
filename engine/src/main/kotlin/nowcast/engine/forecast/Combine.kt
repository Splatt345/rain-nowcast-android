package nowcast.engine.forecast

import nowcast.engine.math.*

/** Shift the ensemble toward the model, keeping spread. Twin of ha-rain-nowcast's rain-nowcast/nowcast/combine.py. */
object Combine {
    val WEIGHTS = doubleArrayOf(0.59, 0.59, 0.62, 0.62, 0.63, 0.65, 0.66, 0.66, 0.67, 0.68, 0.68, 0.69)

    /** In place: [members] is overwritten and returned. */
    fun combine(members: Members, other: Array<FloatArray>, weights: DoubleArray): Members {
        val leads = other.size; val cells = other[0].size
        for (k in 0 until leads) for (i in 0 until cells) {
            var sum = 0.0; var count = 0
            for (m in 0 until members.count) { val value = members.at(m, k, i); if (!value.isNaN()) { sum += value; count++ } }
            val mean = if (count > 0) sum / count else Double.NaN
            val shift = weights[minOf(k, weights.size - 1)] * (other[k][i] - mean)
            for (m in 0 until members.count) {
                val value = members.at(m, k, i)
                members.set(m, k, i, if (value.isNaN() || mean.isNaN()) Float.NaN else maxOf(0.0, value + shift).toFloat())
            }
        }
        return members
    }
}

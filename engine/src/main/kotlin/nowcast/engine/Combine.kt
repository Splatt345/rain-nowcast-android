package nowcast.engine

/** Shift the ensemble toward the model, keeping spread. Twin of ha-rain-nowcast's rain-nowcast/nowcast/combine.py. */
object Combine {
    val WEIGHTS = doubleArrayOf(0.59, 0.59, 0.62, 0.62, 0.63, 0.65, 0.66, 0.66, 0.67, 0.68, 0.68, 0.69)

    /** In place: [members] is overwritten and returned. */
    fun combine(members: Members, other: Array<FloatArray>, w: DoubleArray): Members {
        val nLead = other.size; val n = other[0].size
        for (k in 0 until nLead) for (i in 0 until n) {
            var s = 0.0; var c = 0
            for (m in 0 until members.count) { val x = members.at(m, k, i); if (!x.isNaN()) { s += x; c++ } }
            val mean = if (c > 0) s / c else Double.NaN
            val shift = w[minOf(k, w.size - 1)] * (other[k][i] - mean)
            for (m in 0 until members.count) {
                val x = members.at(m, k, i)
                members.set(m, k, i, if (x.isNaN() || mean.isNaN()) Float.NaN else maxOf(0.0, x + shift).toFloat())
            }
        }
        return members
    }
}

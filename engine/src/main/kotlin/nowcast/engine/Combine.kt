package nowcast.engine

/** Shift the ensemble toward the model, keeping spread. Twin of rain-nowcast/nowcast/combine.py. */
object Combine {
    val WEIGHTS = doubleArrayOf(0.59, 0.59, 0.62, 0.62, 0.63, 0.65, 0.66, 0.66, 0.67, 0.68, 0.68, 0.69)

    fun combine(members: Array<Array<FloatArray>>, other: Array<FloatArray>, w: DoubleArray):
            Array<Array<FloatArray>> {
        val nLead = other.size; val n = other[0].size
        val out = Array(members.size) { Array(nLead) { FloatArray(n) } }
        for (k in 0 until nLead) for (i in 0 until n) {
            var s = 0.0; var c = 0
            for (m in members) { val x = m[k][i]; if (!x.isNaN()) { s += x; c++ } }
            val mean = if (c > 0) s / c else Double.NaN
            val shift = w[minOf(k, w.size - 1)] * (other[k][i] - mean)
            for (m in members.indices) out[m][k][i] = maxOf(0.0, members[m][k][i] + shift).toFloat()
                .let { if (members[m][k][i].isNaN() || mean.isNaN()) Float.NaN else it }
        }
        return out
    }
}

package nowcast.engine.radar

import kotlin.math.*

/** Ellipsoidal Lambert azimuthal equal-area (Snyder 1987, eqs 3-12, 24-2..24-15), WGS84. */
class Laea(lat0: Double, lon0: Double, private val x0: Double, private val y0: Double) {
    private val a = 6378137.0
    private val e2 = (2 - 1 / 298.257223563) / 298.257223563
    private val e = sqrt(e2)
    private val lam0 = Math.toRadians(lon0)
    private fun q(phi: Double): Double {
        val s = sin(phi)
        return (1 - e2) * (s / (1 - e2 * s * s) - 1 / (2 * e) * ln((1 - e * s) / (1 + e * s)))
    }
    private val qp = q(PI / 2)
    private val rq = a * sqrt(qp / 2)
    private val phi1 = Math.toRadians(lat0)
    private val beta1 = asin(q(phi1) / qp)
    private val d = a * cos(phi1) / (sqrt(1 - e2 * sin(phi1).pow(2)) * rq * cos(beta1))

    fun forward(lat: Double, lon: Double): Pair<Double, Double> {
        val beta = asin(q(Math.toRadians(lat)) / qp)
        val dl = Math.toRadians(lon) - lam0
        val b = rq * sqrt(2 / (1 + sin(beta1) * sin(beta) + cos(beta1) * cos(beta) * cos(dl)))
        return (x0 + b * d * cos(beta) * sin(dl)) to
            (y0 + (b / d) * (cos(beta1) * sin(beta) - sin(beta1) * cos(beta) * cos(dl)))
    }

    companion object {
        fun parse(projdef: String): Laea {
            val p = Regex("""\+(\w+)=([-\d.]+)""").findAll(projdef).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            require("laea" in projdef) { "only LAEA composites are supported: $projdef" }
            return Laea(p.getValue("lat_0"), p.getValue("lon_0"), p["x_0"] ?: 0.0, p["y_0"] ?: 0.0)
        }
    }
}

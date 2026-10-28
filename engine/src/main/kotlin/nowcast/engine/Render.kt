package nowcast.engine

import kotlin.math.*

/** Twin of rain-nowcast/nowcast/render.py: level bitmaps on a Mercator raster, coloured by palette. */
object Render {
    const val MIN_RATE = 0.05
    const val MAX_RATE = 80.0
    const val LEVEL_STEPS = 64
    const val SMOOTH_PX = 0.8
    const val DEFAULT_PALETTE = "blue-green"
    /** The user's own colours, one per rate stop of [DEFAULT_PALETTE]. */
    const val CUSTOM = "custom"

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    val PALETTES: Map<String, List<Pair<Double, Int>>> = linkedMapOf(
        "blue-green" to listOf(
            0.10 to rgb(150, 200, 255), 0.50 to rgb(80, 160, 250), 1.00 to rgb(35, 110, 235),
            2.50 to rgb(25, 190, 180), 5.00 to rgb(60, 205, 90), 10.0 to rgb(245, 215, 60),
            20.0 to rgb(250, 150, 45), 40.0 to rgb(235, 60, 60), 80.0 to rgb(200, 50, 180)),
        "viridis" to listOf(
            0.10 to rgb(68, 1, 84), 0.50 to rgb(71, 45, 123), 1.00 to rgb(59, 82, 139),
            2.50 to rgb(44, 114, 142), 5.00 to rgb(33, 145, 140), 10.0 to rgb(40, 174, 128),
            20.0 to rgb(94, 201, 98), 40.0 to rgb(173, 220, 48), 80.0 to rgb(253, 231, 37)),
        "classic" to listOf(
            0.10 to rgb(100, 240, 240), 0.50 to rgb(30, 180, 240), 1.00 to rgb(20, 90, 220),
            2.50 to rgb(20, 220, 60), 5.00 to rgb(240, 240, 40), 10.0 to rgb(250, 180, 30),
            20.0 to rgb(240, 80, 30), 40.0 to rgb(200, 20, 20), 80.0 to rgb(255, 255, 255)),
    )

    private val LO = log10(MIN_RATE)
    private val HI = log10(MAX_RATE)
    private fun logRate(i: Int) = LO + (HI - LO) * i / 255.0

    fun level(rate: Double): Int {
        val r = if (rate.isFinite()) rate else 0.0
        val unit = ((log10(max(r, 1e-3)) - LO) / (HI - LO)).coerceIn(0.0, 1.0)
        return (Math.rint(unit * (LEVEL_STEPS - 1)) * (255.0 / (LEVEL_STEPS - 1))).toInt()
    }

    fun alphaLut(): IntArray = IntArray(256) { i ->
        if (i == 0) 0 else (((logRate(i) - LO) / (log10(0.3) - LO)).coerceIn(0.0, 1.0) * 215.0).toInt()
    }

    /** np.interp: linear between stops, clamped at both ends. */
    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs[0]) return ys[0]
        if (x >= xs.last()) return ys.last()
        val j = xs.indexOfFirst { it > x } - 1
        return ys[j] + (x - xs[j]) * (ys[j + 1] - ys[j]) / (xs[j + 1] - xs[j])
    }

    /** [DEFAULT_PALETTE]'s rates with [colors] (RGB) in their place; missing ones keep the default. */
    fun stops(colors: List<Int>): List<Pair<Double, Int>> =
        PALETTES.getValue(DEFAULT_PALETTE).mapIndexed { i, (r, c) -> r to (colors.getOrNull(i)?.and(0xFFFFFF) ?: c) }

    fun lut(name: String): IntArray = lut(PALETTES[name] ?: PALETTES.getValue(DEFAULT_PALETTE))

    fun lut(stops: List<Pair<Double, Int>>): IntArray {
        val xs = DoubleArray(stops.size) { log10(stops[it].first) }
        fun ch(shift: Int) = DoubleArray(stops.size) { (stops[it].second shr shift and 255).toDouble() }
        val r = ch(16); val g = ch(8); val b = ch(0)
        return IntArray(256) { i ->
            val x = logRate(i)
            rgb(interp(x, xs, r).toInt(), interp(x, xs, g).toInt(), interp(x, xs, b).toInt())
        }
    }

    class Sampler(val rows: DoubleArray, val cols: DoubleArray, val width: Int, val height: Int)

    private const val R_EARTH = 6378137.0
    private fun mercY(lat: Double) = R_EARTH * ln(tan(PI / 4 + Math.toRadians(lat) / 2))

    /** render.mercator_sampler: source (row, col) for each pixel of a Mercator raster over bbox. */
    fun mercatorSampler(geo: GridGeo, bbox: List<Double>, width: Int = 640): Sampler {
        val (lon0, lat0, lon1, lat1) = bbox
        val mx0 = R_EARTH * Math.toRadians(lon0); val mx1 = R_EARTH * Math.toRadians(lon1)
        val my0 = mercY(lat0); val my1 = mercY(lat1)
        val height = max(1, Math.rint(width * (my1 - my0) / (mx1 - mx0)).toInt())
        val rows = DoubleArray(width * height); val cols = DoubleArray(width * height)
        for (y in 0 until height) {
            val my = if (height == 1) my1 else my1 + (my0 - my1) * y / (height - 1)
            val lat = Math.toDegrees(atan(sinh(my / R_EARTH)))
            for (x in 0 until width) {
                val mx = if (width == 1) mx0 else mx0 + (mx1 - mx0) * x / (width - 1)
                val (px, py) = geo.laea.forward(lat, Math.toDegrees(mx / R_EARTH))
                cols[y * width + x] = (px - geo.x1) / geo.px
                rows[y * width + x] = (geo.y2 - py) / geo.py
            }
        }
        return Sampler(rows, cols, width, height)
    }

    /** scipy.ndimage.gaussian_filter: truncate 4, mode "reflect" (d c b a | a b c d). */
    fun gaussian(f: Grid, sigma: Double): Grid {
        val rad = (4.0 * sigma + 0.5).toInt()
        val k = DoubleArray(2 * rad + 1) { exp(-0.5 * (it - rad) * (it - rad) / (sigma * sigma)) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        fun refl(i: Int, n: Int): Int {
            var j = i
            while (j < 0 || j >= n) j = if (j < 0) -j - 1 else 2 * n - j - 1
            return j
        }
        val tmp = Grid(f.h, f.w); val out = Grid(f.h, f.w)
        for (r in 0 until f.h) for (c in 0 until f.w) {
            var s = 0.0; for (j in k.indices) s += k[j] * f[r, refl(c + j - rad, f.w)]; tmp[r, c] = s
        }
        for (r in 0 until f.h) for (c in 0 until f.w) {
            var s = 0.0; for (j in k.indices) s += k[j] * tmp[refl(r + j - rad, f.h), c]; out[r, c] = s
        }
        return out
    }

    /** render.resample: NaN -> 0, display smoothing, bilinear with zeros outside (map_coordinates constant). */
    fun resample(field: FloatArray, h: Int, w: Int, s: Sampler, smooth: Double = SMOOTH_PX): DoubleArray {
        var g = Grid(h, w, DoubleArray(h * w) { field[it].let { v -> if (v.isNaN()) 0.0 else v.toDouble() } })
        if (smooth > 0) g = gaussian(g, smooth)
        fun at(r: Int, c: Int) = if (r in 0 until h && c in 0 until w) g[r, c] else 0.0
        return DoubleArray(s.rows.size) { i ->
            val r = s.rows[i]; val c = s.cols[i]
            if (r.isNaN() || c.isNaN() || r <= -1 || c <= -1 || r >= h || c >= w) 0.0 else {
                val r0 = floor(r).toInt(); val c0 = floor(c).toInt(); val fr = r - r0; val fc = c - c0
                (1 - fr) * ((1 - fc) * at(r0, c0) + fc * at(r0, c0 + 1)) +
                    fr * ((1 - fc) * at(r0 + 1, c0) + fc * at(r0 + 1, c0 + 1))
            }
        }
    }

    fun levels(field: FloatArray, h: Int, w: Int, s: Sampler): ByteArray =
        resample(field, h, w, s).let { v -> ByteArray(v.size) { level(v[it]).toByte() } }

    private val alpha = alphaLut()

    fun argb(levels: ByteArray, palette: String): IntArray = argb(levels, lut(palette))

    fun argb(levels: ByteArray, t: IntArray): IntArray {
        return IntArray(levels.size) { i -> val l = levels[i].toInt() and 255; (alpha[l] shl 24) or t[l] }
    }
}

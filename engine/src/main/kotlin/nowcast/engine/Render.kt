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
    /** level of anything at or below the 1e-3 floor: most of a map is dry, and log10 is its cost. */
    private val DRY_LEVEL = levelOf(0.0)
    private fun logRate(i: Int) = LO + (HI - LO) * i / 255.0

    fun level(rate: Double): Int = if (!(rate > 1e-3) || rate.isInfinite()) DRY_LEVEL else levelOf(rate)

    private fun levelOf(rate: Double): Int {
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
        val h = f.h; val w = f.w; val tmp = DoubleArray(h * w); val out = DoubleArray(h * w); val v = f.v
        // reflection only within rad of an edge; inside, the same sums without it
        for (r in 0 until h) for (c in 0 until w) {
            var s = 0.0; val o = r * w + c - rad
            if (c >= rad && c < w - rad) for (j in k.indices) s += k[j] * v[o + j]
            else for (j in k.indices) s += k[j] * v[r * w + refl(c + j - rad, w)]
            tmp[r * w + c] = s
        }
        for (r in 0 until h) {
            val inside = r >= rad && r < h - rad
            for (c in 0 until w) {
                var s = 0.0
                if (inside) { val o = (r - rad) * w + c; for (j in k.indices) s += k[j] * tmp[o + j * w] }
                else for (j in k.indices) s += k[j] * tmp[refl(r + j - rad, h) * w + c]
                out[r * w + c] = s
            }
        }
        return Grid(h, w, out)
    }

    /** render.resample: NaN -> 0, display smoothing, bilinear with zeros outside (map_coordinates constant). */
    fun resample(field: FloatArray, h: Int, w: Int, s: Sampler, smooth: Double = SMOOTH_PX): DoubleArray =
        DoubleArray(s.rows.size).also { o -> resampleInto(field, h, w, s, smooth) { i, v -> o[i] = v } }

    private fun at(g: Grid, r: Int, c: Int) = if (r in 0 until g.h && c in 0 until g.w) g[r, c] else 0.0

    /** [resample], each value handed to [put] instead of kept: [levels] needs only its byte. */
    private inline fun resampleInto(field: FloatArray, h: Int, w: Int, s: Sampler, smooth: Double, put: (Int, Double) -> Unit) {
        var g = Grid(h, w, DoubleArray(h * w) { field[it].let { v -> if (v.isNaN()) 0.0 else v.toDouble() } })
        if (smooth > 0) g = gaussian(g, smooth)
        for (i in s.rows.indices) {
            val r = s.rows[i]; val c = s.cols[i]
            put(i, if (r.isNaN() || c.isNaN() || r <= -1 || c <= -1 || r >= h || c >= w) 0.0 else {
                val r0 = floor(r).toInt(); val c0 = floor(c).toInt(); val fr = r - r0; val fc = c - c0
                (1 - fr) * ((1 - fc) * at(g, r0, c0) + fc * at(g, r0, c0 + 1)) +
                    fr * ((1 - fc) * at(g, r0 + 1, c0) + fc * at(g, r0 + 1, c0 + 1))
            })
        }
    }

    /** Motion on a coarse grid over a Mercator raster, in raster pixels per frame step: the card's state.motion. */
    class RasterMotion(val w: Int, val h: Int, val step: Int, val dx: FloatArray, val dy: FloatArray) {
        /** Per raster pixel (vx, vy), interpolated once: half of [warp]'s work, the same every minute.
         *  Doubles, as warp computed them (3.8 MB for Belgium's 512 px raster, made when the map first plays). */
        private class Pixels(val w: Int, val h: Int, val vx: DoubleArray, val vy: DoubleArray)
        @Volatile private var pixels: Pixels? = null

        internal fun pixels(w: Int, h: Int): Pair<DoubleArray, DoubleArray> {
            pixels?.let { if (it.w == w && it.h == h) return it.vx to it.vy }
            val vx = DoubleArray(w * h); val vy = DoubleArray(w * h); val mw = this.w
            for (y in 0 until h) {
                val fy = y.toDouble() / step; val y0 = minOf(fy.toInt(), this.h - 2); val ty = fy - y0
                for (x in 0 until w) {
                    val fx = x.toDouble() / step; val x0 = minOf(fx.toInt(), mw - 2); val tx = fx - x0
                    val i = y0 * mw + x0
                    val w00 = (1 - tx) * (1 - ty); val w10 = tx * (1 - ty); val w01 = (1 - tx) * ty; val w11 = tx * ty
                    vx[y * w + x] = dx[i] * w00 + dx[i + 1] * w10 + dx[i + mw] * w01 + dx[i + mw + 1] * w11
                    vy[y * w + x] = dy[i] * w00 + dy[i + 1] * w10 + dy[i + mw] * w01 + dy[i + mw + 1] * w11
                }
            }
            pixels = Pixels(w, h, vx, vy)
            return vx to vy
        }
    }

    /**
     * [m] (source pixels per frame step) carried onto the raster [s] samples: at each coarse node the
     * raster->source Jacobian, from its neighbours, is inverted. The field is smooth, so every [step] px is plenty.
     */
    fun rasterMotion(m: Motion, s: Sampler, step: Int = 8): RasterMotion {
        val W = s.width; val H = s.height
        val mw = (W - 1) / step + 2; val mh = (H - 1) / step + 2
        val dx = FloatArray(mw * mh); val dy = FloatArray(mw * mh)
        for (gy in 0 until mh) for (gx in 0 until mw) {
            val x = minOf(gx * step, W - 1); val y = minOf(gy * step, H - 1)
            val xa = maxOf(x - 1, 0); val xb = minOf(x + 1, W - 1); val ya = maxOf(y - 1, 0); val yb = minOf(y + 1, H - 1)
            fun d(a: DoubleArray, i: Int, j: Int, n: Int) = if (n == 0) 0.0 else (a[j] - a[i]) / n
            val dcdx = d(s.cols, y * W + xa, y * W + xb, xb - xa); val drdx = d(s.rows, y * W + xa, y * W + xb, xb - xa)
            val dcdy = d(s.cols, ya * W + x, yb * W + x, yb - ya); val drdy = d(s.rows, ya * W + x, yb * W + x, yb - ya)
            val det = dcdx * drdy - dcdy * drdx
            if (abs(det) < 1e-12) continue
            val r = s.rows[y * W + x]; val c = s.cols[y * W + x]
            val vx = sampleClamped(m.vx, r, c); val vy = sampleClamped(m.vy, r, c)
            dx[gy * mw + gx] = ((vx * drdy - dcdy * vy) / det).toFloat()
            dy[gy * mw + gx] = ((dcdx * vy - drdx * vx) / det).toFloat()
        }
        return RasterMotion(mw, mh, step, dx, dy)
    }

    /**
     * A minute between two frames, fraction [d] of the way from [a] to [b]: [a] traced forward along the motion,
     * [b] traced back, blended. A shower moves rather than fading in. The card's warpBetween, blended in level
     * as it is there.
     */
    fun warp(a: ByteArray, b: ByteArray, w: Int, h: Int, m: RasterMotion, d: Double): ByteArray {
        fun samp(src: ByteArray, x0: Double, y0: Double): Double {
            val x = x0.coerceIn(0.0, w - 1.0); val y = y0.coerceIn(0.0, h - 1.0)
            val xi = x.toInt(); val yi = y.toInt(); val tx = x - xi; val ty = y - yi
            val x1 = minOf(xi + 1, w - 1); val y1 = minOf(yi + 1, h - 1)
            fun v(r: Int, c: Int) = (src[r * w + c].toInt() and 255).toDouble()
            return v(yi, xi) * (1 - tx) * (1 - ty) + v(yi, x1) * tx * (1 - ty) + v(y1, xi) * (1 - tx) * ty + v(y1, x1) * tx * ty
        }
        val out = ByteArray(w * h); val (pvx, pvy) = m.pixels(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val vx = pvx[y * w + x]; val vy = pvy[y * w + x]
                val back = samp(a, x - vx * d, y - vy * d)
                val fwd = samp(b, x + vx * (1 - d), y + vy * (1 - d))
                out[y * w + x] = ((1 - d) * back + d * fwd + 0.5).toInt().toByte()
            }
        }
        return out
    }

    fun levels(field: FloatArray, h: Int, w: Int, s: Sampler): ByteArray =
        ByteArray(s.rows.size).also { o -> resampleInto(field, h, w, s, SMOOTH_PX) { i, v -> o[i] = level(v).toByte() } }

    private val alpha = alphaLut()

    fun argb(levels: ByteArray, palette: String): IntArray = argb(levels, lut(palette))

    fun argb(levels: ByteArray, t: IntArray): IntArray {
        return IntArray(levels.size) { i -> val l = levels[i].toInt() and 255; (alpha[l] shl 24) or t[l] }
    }
}

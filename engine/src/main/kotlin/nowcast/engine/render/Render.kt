package nowcast.engine.render

import nowcast.engine.forecast.*
import nowcast.engine.math.*
import nowcast.engine.radar.*
import kotlin.math.*

/** Twin of ha-rain-nowcast's rain-nowcast/nowcast/render.py: level bitmaps on a Mercator raster, coloured by palette. */
object Render {
    const val MIN_RATE = 0.05
    const val MAX_RATE = 80.0
    const val LEVEL_STEPS = 64
    const val SMOOTH_PX = 0.8
    const val DEFAULT_PALETTE = "blue-green"
    /** The user's own colours, one per rate stop of [DEFAULT_PALETTE]. */
    const val CUSTOM = "custom"

    private fun rgb(red: Int, green: Int, blue: Int) = (red shl 16) or (green shl 8) or blue

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
        val finite = if (rate.isFinite()) rate else 0.0
        val unit = ((log10(max(finite, 1e-3)) - LO) / (HI - LO)).coerceIn(0.0, 1.0)
        return (Math.rint(unit * (LEVEL_STEPS - 1)) * (255.0 / (LEVEL_STEPS - 1))).toInt()
    }

    fun alphaLut(): IntArray = IntArray(256) { level ->
        if (level == 0) 0 else (((logRate(level) - LO) / (log10(0.3) - LO)).coerceIn(0.0, 1.0) * 215.0).toInt()
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
        PALETTES.getValue(DEFAULT_PALETTE).mapIndexed { i, (rate, color) -> rate to (colors.getOrNull(i)?.and(0xFFFFFF) ?: color) }

    fun lut(name: String): IntArray = lut(PALETTES[name] ?: PALETTES.getValue(DEFAULT_PALETTE))

    fun lut(stops: List<Pair<Double, Int>>): IntArray {
        val xs = DoubleArray(stops.size) { log10(stops[it].first) }
        fun channel(shift: Int) = DoubleArray(stops.size) { (stops[it].second shr shift and 255).toDouble() }
        val red = channel(16); val green = channel(8); val blue = channel(0)
        return IntArray(256) { level ->
            val x = logRate(level)
            rgb(interp(x, xs, red).toInt(), interp(x, xs, green).toInt(), interp(x, xs, blue).toInt())
        }
    }

    class Sampler(val rows: DoubleArray, val cols: DoubleArray, val width: Int, val height: Int)

    private const val R_EARTH = 6378137.0
    private fun mercY(lat: Double) = R_EARTH * ln(tan(PI / 4 + Math.toRadians(lat) / 2))

    /** render.mercator_sampler: source (row, col) for each pixel of a Mercator raster over bbox. */
    fun mercatorSampler(geo: GridGeo, bbox: List<Double>, width: Int = 640): Sampler {
        val (west, south, east, north) = bbox
        val mercWest = R_EARTH * Math.toRadians(west); val mercEast = R_EARTH * Math.toRadians(east)
        val mercSouth = mercY(south); val mercNorth = mercY(north)
        val height = max(1, Math.rint(width * (mercNorth - mercSouth) / (mercEast - mercWest)).toInt())
        val rows = DoubleArray(width * height); val cols = DoubleArray(width * height)
        for (y in 0 until height) {
            val northing = if (height == 1) mercNorth else mercNorth + (mercSouth - mercNorth) * y / (height - 1)
            val lat = Math.toDegrees(atan(sinh(northing / R_EARTH)))
            for (x in 0 until width) {
                val easting = if (width == 1) mercWest else mercWest + (mercEast - mercWest) * x / (width - 1)
                val (laeaX, laeaY) = geo.laea.forward(lat, Math.toDegrees(easting / R_EARTH))
                cols[y * width + x] = (laeaX - geo.left) / geo.pixelWidth
                rows[y * width + x] = (geo.top - laeaY) / geo.pixelHeight
            }
        }
        return Sampler(rows, cols, width, height)
    }

    /** scipy.ndimage.gaussian_filter: truncate 4, mode "reflect" (d c b a | a b c d). */
    fun gaussian(field: Grid, sigma: Double): Grid {
        val radius = (4.0 * sigma + 0.5).toInt()
        val kernel = DoubleArray(2 * radius + 1) { exp(-0.5 * (it - radius) * (it - radius) / (sigma * sigma)) }
        val kernelSum = kernel.sum(); for (i in kernel.indices) kernel[i] /= kernelSum
        fun reflect(index: Int, size: Int): Int {
            var j = index
            while (j < 0 || j >= size) j = if (j < 0) -j - 1 else 2 * size - j - 1
            return j
        }
        val rows = field.rows; val cols = field.cols; val rowPass = DoubleArray(rows * cols); val out = DoubleArray(rows * cols); val values = field.values
        // reflection only within rad of an edge; inside, the same sums without it
        for (row in 0 until rows) for (col in 0 until cols) {
            var sum = 0.0; val offset = row * cols + col - radius
            if (col >= radius && col < cols - radius) for (j in kernel.indices) sum += kernel[j] * values[offset + j]
            else for (j in kernel.indices) sum += kernel[j] * values[row * cols + reflect(col + j - radius, cols)]
            rowPass[row * cols + col] = sum
        }
        for (row in 0 until rows) {
            val inside = row >= radius && row < rows - radius
            for (col in 0 until cols) {
                var sum = 0.0
                if (inside) { val offset = (row - radius) * cols + col; for (j in kernel.indices) sum += kernel[j] * rowPass[offset + j * cols] }
                else for (j in kernel.indices) sum += kernel[j] * rowPass[reflect(row + j - radius, rows) * cols + col]
                out[row * cols + col] = sum
            }
        }
        return Grid(rows, cols, out)
    }

    /** render.resample: NaN -> 0, display smoothing, bilinear with zeros outside (map_coordinates constant). */
    fun resample(field: FloatArray, rows: Int, cols: Int, sampler: Sampler, smooth: Double = SMOOTH_PX): DoubleArray =
        DoubleArray(sampler.rows.size).also { out -> resampleInto(field, rows, cols, sampler, smooth) { index, value -> out[index] = value } }

    private fun at(grid: Grid, row: Int, col: Int) = if (row in 0 until grid.rows && col in 0 until grid.cols) grid[row, col] else 0.0

    /** [resample], each value handed to [put] instead of kept: [levels] needs only its byte. */
    private inline fun resampleInto(field: FloatArray, rows: Int, cols: Int, sampler: Sampler, smooth: Double, put: (Int, Double) -> Unit) {
        var grid = Grid(rows, cols, DoubleArray(rows * cols) { field[it].let { value -> if (value.isNaN()) 0.0 else value.toDouble() } })
        if (smooth > 0) grid = gaussian(grid, smooth)
        for (i in sampler.rows.indices) {
            val row = sampler.rows[i]; val col = sampler.cols[i]
            put(i, if (row.isNaN() || col.isNaN() || row <= -1 || col <= -1 || row >= rows || col >= cols) 0.0 else {
                val row0 = floor(row).toInt(); val col0 = floor(col).toInt(); val rowFrac = row - row0; val colFrac = col - col0
                (1 - rowFrac) * ((1 - colFrac) * at(grid, row0, col0) + colFrac * at(grid, row0, col0 + 1)) +
                    rowFrac * ((1 - colFrac) * at(grid, row0 + 1, col0) + colFrac * at(grid, row0 + 1, col0 + 1))
            })
        }
    }

    /** Motion on a coarse grid over a Mercator raster, in raster pixels per frame step: the card's state.motion. */
    class RasterMotion(val cols: Int, val rows: Int, val step: Int, val dx: FloatArray, val dy: FloatArray) {
        /** Per raster pixel (vx, vy), interpolated once: half of [warp]'s work, the same every minute.
         *  Doubles, as warp computed them (3.8 MB for Belgium's 512 px raster, made when the map first plays). */
        private class Pixels(val cols: Int, val rows: Int, val vx: DoubleArray, val vy: DoubleArray)
        @Volatile private var pixels: Pixels? = null

        internal fun pixels(width: Int, height: Int): Pair<DoubleArray, DoubleArray> {
            pixels?.let { if (it.cols == width && it.rows == height) return it.vx to it.vy }
            val vx = DoubleArray(width * height); val vy = DoubleArray(width * height); val nodeCols = this.cols
            for (y in 0 until height) {
                val fy = y.toDouble() / step; val y0 = minOf(fy.toInt(), this.rows - 2); val ty = fy - y0
                for (x in 0 until width) {
                    val fx = x.toDouble() / step; val x0 = minOf(fx.toInt(), nodeCols - 2); val tx = fx - x0
                    val node = y0 * nodeCols + x0
                    val w00 = (1 - tx) * (1 - ty); val w10 = tx * (1 - ty); val w01 = (1 - tx) * ty; val w11 = tx * ty
                    vx[y * width + x] = dx[node] * w00 + dx[node + 1] * w10 + dx[node + nodeCols] * w01 + dx[node + nodeCols + 1] * w11
                    vy[y * width + x] = dy[node] * w00 + dy[node + 1] * w10 + dy[node + nodeCols] * w01 + dy[node + nodeCols + 1] * w11
                }
            }
            pixels = Pixels(width, height, vx, vy)
            return vx to vy
        }
    }

    /**
     * [motion] (source pixels per frame step) carried onto the raster [sampler] samples: at each coarse node the
     * raster->source Jacobian, from its neighbours, is inverted. The field is smooth, so every [step] px is plenty.
     */
    fun rasterMotion(motion: Motion, sampler: Sampler, step: Int = 8): RasterMotion {
        val width = sampler.width; val height = sampler.height
        val nodeCols = (width - 1) / step + 2; val nodeRows = (height - 1) / step + 2
        val dx = FloatArray(nodeCols * nodeRows); val dy = FloatArray(nodeCols * nodeRows)
        for (nodeY in 0 until nodeRows) for (nodeX in 0 until nodeCols) {
            val x = minOf(nodeX * step, width - 1); val y = minOf(nodeY * step, height - 1)
            val xa = maxOf(x - 1, 0); val xb = minOf(x + 1, width - 1); val ya = maxOf(y - 1, 0); val yb = minOf(y + 1, height - 1)
            fun slope(values: DoubleArray, from: Int, to: Int, span: Int) = if (span == 0) 0.0 else (values[to] - values[from]) / span
            val dcdx = slope(sampler.cols, y * width + xa, y * width + xb, xb - xa); val drdx = slope(sampler.rows, y * width + xa, y * width + xb, xb - xa)
            val dcdy = slope(sampler.cols, ya * width + x, yb * width + x, yb - ya); val drdy = slope(sampler.rows, ya * width + x, yb * width + x, yb - ya)
            val det = dcdx * drdy - dcdy * drdx
            if (abs(det) < 1e-12) continue
            val row = sampler.rows[y * width + x]; val col = sampler.cols[y * width + x]
            val vx = sampleClamped(motion.vx, row, col); val vy = sampleClamped(motion.vy, row, col)
            dx[nodeY * nodeCols + nodeX] = ((vx * drdy - dcdy * vy) / det).toFloat()
            dy[nodeY * nodeCols + nodeX] = ((dcdx * vy - drdx * vx) / det).toFloat()
        }
        return RasterMotion(nodeCols, nodeRows, step, dx, dy)
    }

    /**
     * A minute between two frames, [fraction] of the way from [from] to [to]: [from] traced forward along the motion,
     * [to] traced back, blended. A shower moves rather than fading in. The card's warpBetween, blended in level
     * as it is there.
     */
    fun warp(from: ByteArray, to: ByteArray, width: Int, height: Int, motion: RasterMotion, fraction: Double): ByteArray {
        fun sample(src: ByteArray, x0: Double, y0: Double): Double {
            val x = x0.coerceIn(0.0, width - 1.0); val y = y0.coerceIn(0.0, height - 1.0)
            val xi = x.toInt(); val yi = y.toInt(); val tx = x - xi; val ty = y - yi
            val x1 = minOf(xi + 1, width - 1); val y1 = minOf(yi + 1, height - 1)
            fun byte(row: Int, col: Int) = (src[row * width + col].toInt() and 255).toDouble()
            return byte(yi, xi) * (1 - tx) * (1 - ty) + byte(yi, x1) * tx * (1 - ty) + byte(y1, xi) * (1 - tx) * ty + byte(y1, x1) * tx * ty
        }
        val out = ByteArray(width * height); val (pixelVx, pixelVy) = motion.pixels(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val vx = pixelVx[y * width + x]; val vy = pixelVy[y * width + x]
                val back = sample(from, x - vx * fraction, y - vy * fraction)
                val forward = sample(to, x + vx * (1 - fraction), y + vy * (1 - fraction))
                out[y * width + x] = ((1 - fraction) * back + fraction * forward + 0.5).toInt().toByte()
            }
        }
        return out
    }

    fun levels(field: FloatArray, rows: Int, cols: Int, sampler: Sampler): ByteArray =
        ByteArray(sampler.rows.size).also { out -> resampleInto(field, rows, cols, sampler, SMOOTH_PX) { index, value -> out[index] = level(value).toByte() } }

    private val alpha = alphaLut()

    fun argb(levels: ByteArray, palette: String): IntArray = argb(levels, lut(palette))

    fun argb(levels: ByteArray, lut: IntArray): IntArray {
        return IntArray(levels.size) { i -> val index = levels[i].toInt() and 255; (alpha[index] shl 24) or lut[index] }
    }
}

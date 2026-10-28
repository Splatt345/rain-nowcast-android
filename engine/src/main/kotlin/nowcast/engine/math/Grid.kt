package nowcast.engine.math

import kotlin.math.floor
import kotlin.math.log10

/**
 * Splits [0, count) into [ways] contiguous chunks run at once, one on the caller and the rest on
 * [pool]. Only for loops whose iterations are independent, so results never depend on [ways].
 */
class Par(val pool: java.util.concurrent.ExecutorService?, val ways: Int) {
    fun run(count: Int, body: (Int, Int) -> Unit) {
        if (pool == null || ways <= 1 || count < ways) { body(0, count); return }
        val step = (count + ways - 1) / ways
        val others = (1 until ways).map { chunk -> pool.submit { body(minOf(count, chunk * step), minOf(count, (chunk + 1) * step)) } }
        body(0, step)
        others.forEach { it.get() }
    }

    companion object { val SERIAL = Par(null, 1) }
}

/** Row-major 2-D field. */
class Grid(val rows: Int, val cols: Int, val values: DoubleArray = DoubleArray(rows * cols)) {
    operator fun get(r: Int, c: Int) = values[r * cols + c]
    operator fun set(r: Int, c: Int, x: Double) { values[r * cols + c] = x }
    fun copy() = Grid(rows, cols, values.copyOf())
    inline fun map(f: (Double) -> Double) = Grid(rows, cols, DoubleArray(values.size) { f(values[it]) })
}

const val DRY_DB = -15.0
const val MAX_DB = 20.0    // 100 mm/h, same ceiling as Odim.MAX_DBZ and the model transform

fun toDb(rain: Double): Double =
    if (!rain.isNaN() && rain >= 0.1) 10.0 * log10(rain) else DRY_DB


private fun clampI(i: Int, n: Int) = if (i < 0) 0 else if (i >= n) n - 1 else i

/** Separable box mean, edge-clamped; radius 0 copies. */
fun blur(field: Grid, radius: Int): Grid {
    if (radius == 0) return field.copy()
    val width = 2 * radius + 1
    val rowPass = Grid(field.rows, field.cols)
    for (row in 0 until field.rows) {
        var sum = 0.0
        for (j in -radius..radius) sum += field[row, clampI(j, field.cols)]
        for (col in 0 until field.cols) {
            rowPass[row, col] = sum / width
            sum += field[row, clampI(col + radius + 1, field.cols)] - field[row, clampI(col - radius, field.cols)]
        }
    }
    val out = Grid(field.rows, field.cols)
    for (col in 0 until field.cols) {
        var sum = 0.0
        for (j in -radius..radius) sum += rowPass[clampI(j, field.rows), col]
        for (row in 0 until field.rows) {
            out[row, col] = sum / width
            sum += rowPass[clampI(row + radius + 1, field.rows), col] - rowPass[clampI(row - radius, field.rows), col]
        }
    }
    return out
}

/** Bilinear, coordinates clamped, NaN read as 0. */
fun sampleClamped(field: Grid, row: Double, col: Double): Double {
    var out = 0.0
    clampedCell(field.rows, field.cols, row, col) { i00, i01, i10, i11, rowFrac, colFrac -> out = bilinear0(field.values, i00, i01, i10, i11, rowFrac, colFrac) }
    return out
}

/** The cell and weights sampleClamped uses at (row, col); [use] gets the four corner indices and
 *  the fractions. Coordinates are clamped to >= 0 first, so toInt is floor (NaN gives 0, as
 *  floor(NaN).toInt() does). */
internal inline fun clampedCell(rows: Int, cols: Int, row: Double, col: Double,
                                use: (Int, Int, Int, Int, Double, Double) -> Unit) {
    val clampedRow = row.coerceIn(0.0, (rows - 1).toDouble()); val clampedCol = col.coerceIn(0.0, (cols - 1).toDouble())
    val row0 = clampedRow.toInt(); val col0 = clampedCol.toInt()
    val row1 = minOf(row0 + 1, rows - 1); val col1 = minOf(col0 + 1, cols - 1)
    use(row0 * cols + col0, row0 * cols + col1, row1 * cols + col0, row1 * cols + col1, clampedRow - row0, clampedCol - col0)
}

/** sampleClamped's interpolation of values at a clampedCell, NaN read as 0. */
@Suppress("NOTHING_TO_INLINE")
internal inline fun bilinear0(values: DoubleArray, i00: Int, i01: Int, i10: Int, i11: Int, rowFrac: Double, colFrac: Double): Double {
    val v00 = values[i00].let { if (it.isNaN()) 0.0 else it }; val v01 = values[i01].let { if (it.isNaN()) 0.0 else it }
    val v10 = values[i10].let { if (it.isNaN()) 0.0 else it }; val v11 = values[i11].let { if (it.isNaN()) 0.0 else it }
    return (1 - rowFrac) * ((1 - colFrac) * v00 + colFrac * v01) + rowFrac * ((1 - colFrac) * v10 + colFrac * v11)
}

/** Bilinear; NaN off-grid or next to a NaN. */
fun sampleOrNaN(field: Grid, row: Double, col: Double): Double {
    if (row < 0 || row > field.rows - 1 || col < 0 || col > field.cols - 1 || row.isNaN() || col.isNaN()) return Double.NaN
    val row0 = row.toInt(); val col0 = col.toInt()      // row, col >= 0: floor
    val row1 = minOf(row0 + 1, field.rows - 1); val col1 = minOf(col0 + 1, field.cols - 1)
    val rowFrac = row - row0; val colFrac = col - col0
    return (1 - rowFrac) * ((1 - colFrac) * field[row0, col0] + colFrac * field[row0, col1]) +
        rowFrac * ((1 - colFrac) * field[row1, col0] + colFrac * field[row1, col1])
}

fun down2(field: Grid): Grid {
    val rows = field.rows / 2; val cols = field.cols / 2
    val out = Grid(rows, cols)
    for (row in 0 until rows) for (col in 0 until cols)
        out[row, col] = 0.25 * (field[2 * row, 2 * col] + field[2 * row + 1, 2 * col] + field[2 * row, 2 * col + 1] + field[2 * row + 1, 2 * col + 1])
    return out
}

/** Nearest x2, then crop/edge-pad to (rows, cols). */
fun up2(field: Grid, rows: Int, cols: Int): Grid {
    val out = Grid(rows, cols)
    for (row in 0 until rows) for (col in 0 until cols)
        out[row, col] = field[minOf(row, 2 * field.rows - 1) / 2, minOf(col, 2 * field.cols - 1) / 2]
    return out
}

/** out[row, col] = field[clamp(row + dRow), clamp(col + dCol)]. */
fun shift(field: Grid, dRow: Int, dCol: Int): Grid {
    val out = Grid(field.rows, field.cols)
    for (row in 0 until field.rows) for (col in 0 until field.cols) out[row, col] = field[clampI(row + dRow, field.rows), clampI(col + dCol, field.cols)]
    return out
}

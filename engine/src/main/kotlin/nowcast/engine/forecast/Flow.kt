package nowcast.engine.forecast

import nowcast.engine.math.*

/** Dense coarse-to-fine Lucas-Kanade optical flow. */
class Motion(val vx: Grid, val vy: Grid)

private const val LEVELS = 4
private const val WINDOW = 7
private const val ITERS = 3
private const val FILL_RADIUS = 40
private const val FILL_PASSES = 2
private const val EPS = 1e-6

fun lkPair(first: Grid, second: Grid): Triple<Grid, Grid, Grid> {
    val firstPyramid = mutableListOf(first); val secondPyramid = mutableListOf(second)
    repeat(LEVELS - 1) { firstPyramid += down2(firstPyramid.last()); secondPyramid += down2(secondPyramid.last()) }
    var vx = Grid(firstPyramid.last().rows, firstPyramid.last().cols); var vy = Grid(vx.rows, vx.cols)
    var confidence = Grid(first.rows, first.cols)
    for (level in LEVELS - 1 downTo 0) {
        val levelFirst = firstPyramid[level]; val levelSecond = secondPyramid[level]; val rows = levelFirst.rows; val cols = levelFirst.cols
        if (vx.rows != rows || vx.cols != cols) { vx = up2(vx, rows, cols).map { 2 * it }; vy = up2(vy, rows, cols).map { 2 * it } }
        var det = Grid(rows, cols)
        repeat(ITERS) {
            val warped = Grid(rows, cols)
            for (row in 0 until rows) for (col in 0 until cols) warped[row, col] = sampleClamped(levelSecond, row + vy[row, col], col + vx[row, col])
            val average = Grid(rows, cols, DoubleArray(rows * cols) { 0.5 * (levelFirst.values[it] + warped.values[it]) })
            val right = shift(average, 0, 1); val left = shift(average, 0, -1); val down = shift(average, 1, 0); val up = shift(average, -1, 0)
            val gradX = DoubleArray(rows * cols) { 0.5 * (right.values[it] - left.values[it]) }
            val gradY = DoubleArray(rows * cols) { 0.5 * (down.values[it] - up.values[it]) }
            val gradT = DoubleArray(rows * cols) { warped.values[it] - levelFirst.values[it] }
            fun windowSum(term: (Int) -> Double) = blur(Grid(rows, cols, DoubleArray(rows * cols, term)), WINDOW)
            val sxx = windowSum { gradX[it] * gradX[it] }; val syy = windowSum { gradY[it] * gradY[it] }; val sxy = windowSum { gradX[it] * gradY[it] }
            val sxt = windowSum { gradX[it] * gradT[it] }; val syt = windowSum { gradY[it] * gradT[it] }
            det = Grid(rows, cols, DoubleArray(rows * cols) { sxx.values[it] * syy.values[it] - sxy.values[it] * sxy.values[it] })
            for (i in 0 until rows * cols) {
                val value = det.values[i]
                if (value > EPS) {
                    vx.values[i] += -(syy.values[i] * sxt.values[i] - sxy.values[i] * syt.values[i]) / value
                    vy.values[i] += -(sxx.values[i] * syt.values[i] - sxy.values[i] * sxt.values[i]) / value
                }
            }
        }
        confidence = det.map { maxOf(it, 0.0) }
    }
    return Triple(vx, vy, confidence)
}

/** Last two pairs, confidence-weighted, filled into dry areas. */
fun motion(db: List<Grid>, par: Par = Par.SERIAL): Motion {
    val last = db.last(); val cells = last.rows * last.cols
    var sumX = Grid(last.rows, last.cols); var sumY = Grid(last.rows, last.cols); var weight = Grid(last.rows, last.cols)
    val pairs = listOf(db[db.size - 3] to db[db.size - 2], db[db.size - 2] to db[db.size - 1])
    val flows = arrayOfNulls<Triple<Grid, Grid, Grid>>(2)
    Par(par.pool, minOf(par.ways, 2)).run(2) { start, end -> for (k in start until end) flows[k] = lkPair(pairs[k].first, pairs[k].second) }
    for (flow in flows) {
        val (vx, vy, confidence) = flow!!
        for (i in 0 until cells) { sumX.values[i] += confidence.values[i] * vx.values[i]; sumY.values[i] += confidence.values[i] * vy.values[i]; weight.values[i] += confidence.values[i] }
    }
    repeat(FILL_PASSES) { sumX = blur(sumX, FILL_RADIUS); sumY = blur(sumY, FILL_RADIUS); weight = blur(weight, FILL_RADIUS) }
    return Motion(
        Grid(last.rows, last.cols, DoubleArray(cells) { if (weight.values[it] > 0) sumX.values[it] / weight.values[it] else 0.0 }),
        Grid(last.rows, last.cols, DoubleArray(cells) { if (weight.values[it] > 0) sumY.values[it] / weight.values[it] else 0.0 }))
}

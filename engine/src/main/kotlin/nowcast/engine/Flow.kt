package nowcast.engine

/** Dense coarse-to-fine Lucas-Kanade. Twin of lite.lk_pair / lite.motion. */
class Motion(val vx: Grid, val vy: Grid)

private const val LEVELS = 4
private const val WINDOW = 7
private const val ITERS = 3
private const val FILL_RADIUS = 40
private const val FILL_PASSES = 2
private const val EPS = 1e-6

fun lkPair(a: Grid, b: Grid): Triple<Grid, Grid, Grid> {
    val pa = mutableListOf(a); val pb = mutableListOf(b)
    repeat(LEVELS - 1) { pa += down2(pa.last()); pb += down2(pb.last()) }
    var vx = Grid(pa.last().h, pa.last().w); var vy = Grid(vx.h, vx.w)
    var conf = Grid(a.h, a.w)
    for (lvl in LEVELS - 1 downTo 0) {
        val A = pa[lvl]; val B = pb[lvl]; val h = A.h; val w = A.w
        if (vx.h != h || vx.w != w) { vx = up2(vx, h, w).map { 2 * it }; vy = up2(vy, h, w).map { 2 * it } }
        var det = Grid(h, w)
        repeat(ITERS) {
            val bw = Grid(h, w)
            for (r in 0 until h) for (c in 0 until w) bw[r, c] = sampleClamped(B, r + vy[r, c], c + vx[r, c])
            val m = Grid(h, w, DoubleArray(h * w) { 0.5 * (A.v[it] + bw.v[it]) })
            val px = shift(m, 0, 1); val nx = shift(m, 0, -1); val py = shift(m, 1, 0); val ny = shift(m, -1, 0)
            val ix = DoubleArray(h * w) { 0.5 * (px.v[it] - nx.v[it]) }
            val iy = DoubleArray(h * w) { 0.5 * (py.v[it] - ny.v[it]) }
            val it_ = DoubleArray(h * w) { bw.v[it] - A.v[it] }
            fun s(f: (Int) -> Double) = blur(Grid(h, w, DoubleArray(h * w, f)), WINDOW)
            val sxx = s { ix[it] * ix[it] }; val syy = s { iy[it] * iy[it] }; val sxy = s { ix[it] * iy[it] }
            val sxt = s { ix[it] * it_[it] }; val syt = s { iy[it] * it_[it] }
            det = Grid(h, w, DoubleArray(h * w) { sxx.v[it] * syy.v[it] - sxy.v[it] * sxy.v[it] })
            for (i in 0 until h * w) {
                val d = det.v[i]
                if (d > EPS) {
                    vx.v[i] += -(syy.v[i] * sxt.v[i] - sxy.v[i] * syt.v[i]) / d
                    vy.v[i] += -(sxx.v[i] * syt.v[i] - sxy.v[i] * sxt.v[i]) / d
                }
            }
        }
        conf = det.map { maxOf(it, 0.0) }
    }
    return Triple(vx, vy, conf)
}

/** Last two pairs, confidence-weighted, filled into dry areas. */
fun motion(db: List<Grid>): Motion {
    val last = db.last(); val n = last.h * last.w
    var fx = Grid(last.h, last.w); var fy = Grid(last.h, last.w); var fc = Grid(last.h, last.w)
    for ((a, b) in listOf(db[db.size - 3] to db[db.size - 2], db[db.size - 2] to db[db.size - 1])) {
        val (vx, vy, c) = lkPair(a, b)
        for (i in 0 until n) { fx.v[i] += c.v[i] * vx.v[i]; fy.v[i] += c.v[i] * vy.v[i]; fc.v[i] += c.v[i] }
    }
    repeat(FILL_PASSES) { fx = blur(fx, FILL_RADIUS); fy = blur(fy, FILL_RADIUS); fc = blur(fc, FILL_RADIUS) }
    return Motion(
        Grid(last.h, last.w, DoubleArray(n) { if (fc.v[it] > 0) fx.v[it] / fc.v[it] else 0.0 }),
        Grid(last.h, last.w, DoubleArray(n) { if (fc.v[it] > 0) fy.v[it] / fc.v[it] else 0.0 }))
}

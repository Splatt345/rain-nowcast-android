package nowcast.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex FFT of any length: KissFFT's mixed-radix recursion (factors 4, 2, 3, 5, 7, ...)
 * with generic butterflies. numpy's convention: forward exp(-2 pi i jk/n), unscaled;
 * [inverse] is unscaled too (Fft2 divides). Immutable, so one instance serves all threads.
 */
class Fft(val n: Int) {
    private val twr = DoubleArray(n) { cos(-2.0 * PI * it / n) }
    private val twi = DoubleArray(n) { sin(-2.0 * PI * it / n) }
    private val factors: IntArray

    init {
        require(n >= 1)
        val f = ArrayList<Int>()
        var m = n; var p = 4
        val root = kotlin.math.floor(kotlin.math.sqrt(n.toDouble())).toInt()
        while (m > 1) {
            while (m % p != 0) {
                p = when (p) { 4 -> 2; 2 -> 3; else -> p + 2 }
                if (p > root) p = m
            }
            m /= p; f += p; f += m
        }
        if (f.isEmpty()) { f += 1; f += 1 }
        factors = f.toIntArray()
    }

    /** out = DFT(in), in read at inOff + j*inStride. */
    fun transform(inRe: DoubleArray, inIm: DoubleArray, inOff: Int, inStride: Int,
                  outRe: DoubleArray, outIm: DoubleArray, inverse: Boolean) {
        if (n == 1) { outRe[0] = inRe[inOff]; outIm[0] = inIm[inOff]; return }
        work(outRe, outIm, 0, inRe, inIm, inOff, 1, inStride, 0, inverse)
    }

    private fun work(oRe: DoubleArray, oIm: DoubleArray, oOff: Int, fRe: DoubleArray, fIm: DoubleArray,
                     fOff: Int, fstride: Int, inStride: Int, fi: Int, inv: Boolean) {
        val p = factors[fi]; val m = factors[fi + 1]
        if (m == 1) {
            for (j in 0 until p) { val s = fOff + j * fstride * inStride; oRe[oOff + j] = fRe[s]; oIm[oOff + j] = fIm[s] }
        } else {
            for (j in 0 until p) work(oRe, oIm, oOff + j * m, fRe, fIm, fOff + j * fstride * inStride,
                fstride * p, inStride, fi + 2, inv)
        }
        val sr = DoubleArray(p); val si = DoubleArray(p)
        val sg = if (inv) -1.0 else 1.0
        for (u in 0 until m) {
            var k = u
            for (q in 0 until p) { sr[q] = oRe[oOff + k]; si[q] = oIm[oOff + k]; k += m }
            k = u
            for (q1 in 0 until p) {
                var tw = 0
                var ar = sr[0]; var ai = si[0]
                for (q in 1 until p) {
                    tw += fstride * k; if (tw >= n) tw -= n
                    val wr = twr[tw]; val wi = sg * twi[tw]
                    ar += sr[q] * wr - si[q] * wi; ai += sr[q] * wi + si[q] * wr
                }
                oRe[oOff + k] = ar; oIm[oOff + k] = ai
                k += m
            }
        }
    }
}

/** Row-major 2-D FFT, in place. [inverse] divides by h*w, as numpy's ifft2. */
class Fft2(val h: Int, val w: Int) {
    private val rows = Fft(w)
    private val cols = Fft(h)

    fun forward(re: DoubleArray, im: DoubleArray) = run(re, im, false)
    fun inverse(re: DoubleArray, im: DoubleArray) {
        run(re, im, true)
        val s = 1.0 / (h * w)
        for (i in re.indices) { re[i] *= s; im[i] *= s }
    }

    private fun run(re: DoubleArray, im: DoubleArray, inv: Boolean) {
        val tr = DoubleArray(w); val ti = DoubleArray(w)
        for (r in 0 until h) {
            rows.transform(re, im, r * w, 1, tr, ti, inv)
            System.arraycopy(tr, 0, re, r * w, w); System.arraycopy(ti, 0, im, r * w, w)
        }
        val cr = DoubleArray(h); val ci = DoubleArray(h)
        for (c in 0 until w) {
            cols.transform(re, im, c, w, cr, ci, inv)
            for (r in 0 until h) { re[r * w + c] = cr[r]; im[r * w + c] = ci[r] }
        }
    }
}

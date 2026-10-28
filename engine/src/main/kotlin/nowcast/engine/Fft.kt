package nowcast.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex FFT of any length: KissFFT's mixed-radix recursion (factors 4, 2, 3, 5, 7, ...)
 * with generic butterflies, or Bluestein's chirp-z when a prime factor is large (as pocketfft,
 * numpy's FFT, does). numpy's convention: forward exp(-2 pi i jk/n), unscaled;
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
                  outRe: DoubleArray, outIm: DoubleArray, inverse: Boolean, sc: Scratch = scratch()) {
        if (n == 1) { outRe[0] = inRe[inOff]; outIm[0] = inIm[inOff]; return }
        if (inner != null) bluestein(inRe, inIm, inOff, inStride, outRe, outIm, inverse, sc)
        else work(outRe, outIm, 0, inRe, inIm, inOff, 1, inStride, 0, inverse, sc.sc)
    }

    private val maxP = (0 until factors.size step 2).maxOf { factors[it] }

    // Bluestein: the DFT as a convolution with the chirp w_k = exp(-i pi k^2/n), done by power-of-2
    // FFTs of m >= 2n-1 points: O(m log m) instead of the odd butterfly's O(n p). Measured on the JVM it
    // only pays for big primes: 2x on 397, 1.4x on 331, a tie on 382 = 2*191, 2-3x slower on 366 = 2*3*61.
    private val m2 = (Integer.highestOneBit(2 * n - 1) shl 1).let { m ->
        if (n.toLong() * maxP > 8L * m * Integer.numberOfTrailingZeros(m)) m else 0 }
    private val inner = if (m2 > 0) Fft(m2) else null
    private val cr = DoubleArray(if (m2 > 0) n else 0); private val ci = DoubleArray(cr.size)
    /** FFT of the conjugate chirp (forward) and of the chirp (inverse), wrapped and scaled by 1/m. */
    private val bf = arrayOf(DoubleArray(m2), DoubleArray(m2)); private val bi = arrayOf(DoubleArray(m2), DoubleArray(m2))

    init {
        if (inner != null) {
            for (k in 0 until n) {
                val a = PI * ((k.toLong() * k) % (2L * n)) / n   // k^2 mod 2n keeps the angle exact
                cr[k] = cos(a); ci[k] = -sin(a)
            }
            val sc = inner.scratch()
            for ((t, sg) in listOf(0 to -1.0, 1 to 1.0)) {
                val r = DoubleArray(m2); val i = DoubleArray(m2)
                for (k in 0 until n) { r[k] = cr[k] / m2; i[k] = sg * ci[k] / m2; if (k > 0) { r[m2 - k] = r[k]; i[m2 - k] = i[k] } }
                inner.transform(r, i, 0, 1, bf[t], bi[t], false, sc)
            }
        }
    }

    /** [transform]'s working arrays; pass one in to reuse it across calls. */
    class Scratch internal constructor(p: Int, m: Int, internal val inner: Scratch?) {
        internal val sc = DoubleArray(4 * p)
        internal val ar = DoubleArray(m); internal val ai = DoubleArray(m)
        internal val br = DoubleArray(m); internal val bj = DoubleArray(m)
    }
    fun scratch(): Scratch = Scratch(if (inner == null) maxP else 0, m2, inner?.scratch())

    private fun bluestein(inRe: DoubleArray, inIm: DoubleArray, inOff: Int, inStride: Int,
                          outRe: DoubleArray, outIm: DoubleArray, inv: Boolean, s: Scratch) {
        // the inverse is the same with the chirp conjugated
        val sg = if (inv) -1.0 else 1.0
        val ar = s.ar; val ai = s.ai; val br = s.br; val bj = s.bj
        for (j in 0 until n) {
            val xr = inRe[inOff + j * inStride]; val xi = inIm[inOff + j * inStride]
            val wr = cr[j]; val wi = sg * ci[j]
            ar[j] = xr * wr - xi * wi; ai[j] = xr * wi + xi * wr
        }
        ar.fill(0.0, n, m2); ai.fill(0.0, n, m2)
        inner!!.transform(ar, ai, 0, 1, br, bj, false, s.inner!!)
        val t = if (inv) 1 else 0; val fr = bf[t]; val fi = bi[t]
        for (k in 0 until m2) { val r = br[k]; val i = bj[k]; br[k] = r * fr[k] - i * fi[k]; bj[k] = r * fi[k] + i * fr[k] }
        inner.transform(br, bj, 0, 1, ar, ai, true, s.inner)
        for (k in 0 until n) {
            val wr = cr[k]; val wi = sg * ci[k]
            outRe[k] = ar[k] * wr - ai[k] * wi; outIm[k] = ar[k] * wi + ai[k] * wr
        }
    }

    private fun work(oRe: DoubleArray, oIm: DoubleArray, oOff: Int, fRe: DoubleArray, fIm: DoubleArray,
                     fOff: Int, fstride: Int, inStride: Int, fi: Int, inv: Boolean, sc: DoubleArray) {
        val p = factors[fi]; val m = factors[fi + 1]
        if (m == 1) {
            for (j in 0 until p) { val s = fOff + j * fstride * inStride; oRe[oOff + j] = fRe[s]; oIm[oOff + j] = fIm[s] }
        } else {
            for (j in 0 until p) work(oRe, oIm, oOff + j * m, fRe, fIm, fOff + j * fstride * inStride,
                fstride * p, inStride, fi + 2, inv, sc)
        }
        when (p) {
            2 -> bfly2(oRe, oIm, oOff, fstride, m, inv)
            4 -> bfly4(oRe, oIm, oOff, fstride, m, inv)
            else -> bflyOdd(oRe, oIm, oOff, fstride, p, m, inv, sc)
        }
    }

    private fun bfly2(re: DoubleArray, im: DoubleArray, off: Int, fstride: Int, m: Int, inv: Boolean) {
        val sg = if (inv) -1.0 else 1.0
        for (u in 0 until m) {
            val a = off + u; val b = a + m
            val wr = twr[u * fstride]; val wi = sg * twi[u * fstride]
            val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
            re[b] = re[a] - tr; im[b] = im[a] - ti
            re[a] += tr; im[a] += ti
        }
    }

    /** kf_bfly4 */
    private fun bfly4(re: DoubleArray, im: DoubleArray, off: Int, fstride: Int, m: Int, inv: Boolean) {
        val sg = if (inv) -1.0 else 1.0
        for (u in 0 until m) {
            val i0 = off + u; val i1 = i0 + m; val i2 = i1 + m; val i3 = i2 + m
            val t1 = u * fstride; val t2 = 2 * t1; val t3 = 3 * t1
            val w1r = twr[t1]; val w1i = sg * twi[t1]; val w2r = twr[t2]; val w2i = sg * twi[t2]
            val w3r = twr[t3]; val w3i = sg * twi[t3]
            val s0r = re[i1] * w1r - im[i1] * w1i; val s0i = re[i1] * w1i + im[i1] * w1r
            val s1r = re[i2] * w2r - im[i2] * w2i; val s1i = re[i2] * w2i + im[i2] * w2r
            val s2r = re[i3] * w3r - im[i3] * w3i; val s2i = re[i3] * w3i + im[i3] * w3r
            val s5r = re[i0] - s1r; val s5i = im[i0] - s1i
            val ar = re[i0] + s1r; val ai = im[i0] + s1i
            val s3r = s0r + s2r; val s3i = s0i + s2i
            val s4r = s0r - s2r; val s4i = s0i - s2i
            re[i2] = ar - s3r; im[i2] = ai - s3i
            re[i0] = ar + s3r; im[i0] = ai + s3i
            if (inv) {
                re[i1] = s5r - s4i; im[i1] = s5i + s4r
                re[i3] = s5r + s4i; im[i3] = s5i - s4r
            } else {
                re[i1] = s5r + s4i; im[i1] = s5i - s4r
                re[i3] = s5r - s4i; im[i3] = s5i + s4r
            }
        }
    }

    /** X[q1] = s0 + T + iU, X[p-q1] = s0 + T - iU */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun out(re: DoubleArray, im: DoubleArray, o: Int, m: Int, p: Int, q1: Int, tr: Double, ti: Double, ur: Double, ui: Double) {
        re[o + q1 * m] = tr - ui; im[o + q1 * m] = ti + ur
        re[o + (p - q1) * m] = tr + ui; im[o + (p - q1) * m] = ti - ur
    }

    /** Odd radix (the factoriser takes every 2 first): twiddle the inputs, then a p-point DFT
     *  that pairs q with p-q, whose weights are conjugate - a quarter of the generic multiplies. */
    private fun bflyOdd(re: DoubleArray, im: DoubleArray, off: Int, fstride: Int, p: Int, m: Int, inv: Boolean, sc: DoubleArray) {
        val sg = if (inv) -1.0 else 1.0
        val sr = sc; val si = sc   // sc: [0, p) inputs re, [p, 2p) im, [2p, 4p) a_q / b_q re, im
        val step = n / p; val hp = (p - 1) / 2
        for (u in 0 until m) {
            sr[0] = re[off + u]; si[p] = im[off + u]
            var tw = 0
            for (q in 1 until p) {
                tw += fstride * u; if (tw >= n) tw -= n
                val k = off + u + q * m
                val wr = twr[tw]; val wi = sg * twi[tw]
                sr[q] = re[k] * wr - im[k] * wi; si[p + q] = re[k] * wi + im[k] * wr
            }
            var x0r = sr[0]; var x0i = si[p]
            for (q in 1..hp) {
                val ar = sr[q] + sr[p - q]; val ai = si[p + q] + si[2 * p - q]
                sc[2 * p + q] = sr[q] - sr[p - q]; sc[3 * p + q] = si[p + q] - si[2 * p - q]   // b_q
                sc[2 * p + p - q] = ar; sc[3 * p + p - q] = ai                                // a_q
                x0r += ar; x0i += ai
            }
            // Four outputs per pass: each sum is the same chain, but four chains hide the adds' latency
            // (a 191-point radix is most of a 382-row FFT).
            var q1 = 1
            while (q1 + 3 <= hp) {
                var tr0 = sr[0]; var ti0 = si[p]; var ur0 = 0.0; var ui0 = 0.0
                var tr1 = sr[0]; var ti1 = si[p]; var ur1 = 0.0; var ui1 = 0.0
                var tr2 = sr[0]; var ti2 = si[p]; var ur2 = 0.0; var ui2 = 0.0
                var tr3 = sr[0]; var ti3 = si[p]; var ur3 = 0.0; var ui3 = 0.0
                var t0 = 0; var t1 = 0; var t2 = 0; var t3 = 0
                val d0 = q1 * step; val d1 = d0 + step; val d2 = d1 + step; val d3 = d2 + step
                for (q in 1..hp) {
                    t0 += d0; if (t0 >= n) t0 -= n
                    t1 += d1; if (t1 >= n) t1 -= n
                    t2 += d2; if (t2 >= n) t2 -= n
                    t3 += d3; if (t3 >= n) t3 -= n
                    val ar = sc[2 * p + p - q]; val ai = sc[3 * p + p - q]; val br = sc[2 * p + q]; val bi = sc[3 * p + q]
                    var wr = twr[t0]; var wi = sg * twi[t0]
                    tr0 += ar * wr; ti0 += ai * wr; ur0 += br * wi; ui0 += bi * wi
                    wr = twr[t1]; wi = sg * twi[t1]
                    tr1 += ar * wr; ti1 += ai * wr; ur1 += br * wi; ui1 += bi * wi
                    wr = twr[t2]; wi = sg * twi[t2]
                    tr2 += ar * wr; ti2 += ai * wr; ur2 += br * wi; ui2 += bi * wi
                    wr = twr[t3]; wi = sg * twi[t3]
                    tr3 += ar * wr; ti3 += ai * wr; ur3 += br * wi; ui3 += bi * wi
                }
                out(re, im, off + u, m, p, q1, tr0, ti0, ur0, ui0)
                out(re, im, off + u, m, p, q1 + 1, tr1, ti1, ur1, ui1)
                out(re, im, off + u, m, p, q1 + 2, tr2, ti2, ur2, ui2)
                out(re, im, off + u, m, p, q1 + 3, tr3, ti3, ur3, ui3)
                q1 += 4
            }
            while (q1 <= hp) {
                var tr = sr[0]; var ti = si[p]; var ur = 0.0; var ui = 0.0
                var t = 0
                for (q in 1..hp) {
                    t += q1 * step; if (t >= n) t -= n
                    val wr = twr[t]; val wi = sg * twi[t]
                    tr += sc[2 * p + p - q] * wr; ti += sc[3 * p + p - q] * wr
                    ur += sc[2 * p + q] * wi; ui += sc[3 * p + q] * wi
                }
                out(re, im, off + u, m, p, q1, tr, ti, ur, ui)
                q1++
            }
            re[off + u] = x0r; im[off + u] = x0i
        }
    }
}

/** Row-major 2-D FFT, in place. [inverse] divides by h*w, as numpy's ifft2. */
class Fft2(val h: Int, val w: Int, private val par: Par = Par.SERIAL) {
    private val rows = Fft(w)
    private val cols = Fft(h)

    fun forward(re: DoubleArray, im: DoubleArray) = run(re, im, false)
    fun inverse(re: DoubleArray, im: DoubleArray) {
        run(re, im, true)
        val s = 1.0 / (h * w)
        for (i in re.indices) { re[i] *= s; im[i] *= s }
    }

    /** Rows, then columns; each 1-D transform is independent, so [par] splits them. */
    private fun run(re: DoubleArray, im: DoubleArray, inv: Boolean) {
        par.run(h) { a, b ->
            val tr = DoubleArray(w); val ti = DoubleArray(w); val sc = rows.scratch()
            for (r in a until b) {
                rows.transform(re, im, r * w, 1, tr, ti, inv, sc)
                System.arraycopy(tr, 0, re, r * w, w); System.arraycopy(ti, 0, im, r * w, w)
            }
        }
        par.run(w) { a, b ->
            val cr = DoubleArray(h); val ci = DoubleArray(h); val sc = cols.scratch()
            for (c in a until b) {
                cols.transform(re, im, c, w, cr, ci, inv, sc)
                for (r in 0 until h) { re[r * w + c] = cr[r]; im[r * w + c] = ci[r] }
            }
        }
    }
}

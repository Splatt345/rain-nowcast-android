package nowcast.engine.math

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
    private val twiddleRe = DoubleArray(n) { cos(-2.0 * PI * it / n) }
    private val twiddleIm = DoubleArray(n) { sin(-2.0 * PI * it / n) }
    private val factors: IntArray

    init {
        require(n >= 1)
        val found = ArrayList<Int>()
        var m = n; var p = 4
        val root = kotlin.math.floor(kotlin.math.sqrt(n.toDouble())).toInt()
        while (m > 1) {
            while (m % p != 0) {
                p = when (p) { 4 -> 2; 2 -> 3; else -> p + 2 }
                if (p > root) p = m
            }
            m /= p; found += p; found += m
        }
        if (found.isEmpty()) { found += 1; found += 1 }
        factors = found.toIntArray()
    }

    /** out = DFT(in), in read at inOff + j*inStride. */
    fun transform(inRe: DoubleArray, inIm: DoubleArray, inOff: Int, inStride: Int,
                  outRe: DoubleArray, outIm: DoubleArray, inverse: Boolean, buffers: Scratch = scratch()) {
        if (n == 1) { outRe[0] = inRe[inOff]; outIm[0] = inIm[inOff]; return }
        if (inner != null) bluestein(inRe, inIm, inOff, inStride, outRe, outIm, inverse, buffers)
        else work(outRe, outIm, 0, inRe, inIm, inOff, 1, inStride, 0, inverse, buffers.sc)
    }

    private val maxP = (0 until factors.size step 2).maxOf { factors[it] }

    // Bluestein: the DFT as a convolution with the chirp w_k = exp(-i pi k^2/n), done by power-of-2
    // FFTs of m >= 2n-1 points: O(m log m) instead of the odd butterfly's O(n p). Measured on the JVM it
    // only pays for big primes: 2x on 397, 1.4x on 331, a tie on 382 = 2*191, 2-3x slower on 366 = 2*3*61.
    private val chirpSize = (Integer.highestOneBit(2 * n - 1) shl 1).let { m ->
        if (n.toLong() * maxP > 8L * m * Integer.numberOfTrailingZeros(m)) m else 0 }
    private val inner = if (chirpSize > 0) Fft(chirpSize) else null
    private val chirpRe = DoubleArray(if (chirpSize > 0) n else 0); private val chirpIm = DoubleArray(chirpRe.size)
    /** FFT of the conjugate chirp (forward) and of the chirp (inverse), wrapped and scaled by 1/m. */
    private val chirpFftRe = arrayOf(DoubleArray(chirpSize), DoubleArray(chirpSize)); private val chirpFftIm = arrayOf(DoubleArray(chirpSize), DoubleArray(chirpSize))

    init {
        if (inner != null) {
            for (k in 0 until n) {
                val a = PI * ((k.toLong() * k) % (2L * n)) / n   // k^2 mod 2n keeps the angle exact
                chirpRe[k] = cos(a); chirpIm[k] = -sin(a)
            }
            val buffers = inner.scratch()
            for ((direction, sign) in listOf(0 to -1.0, 1 to 1.0)) {
                val r = DoubleArray(chirpSize); val i = DoubleArray(chirpSize)
                for (k in 0 until n) { r[k] = chirpRe[k] / chirpSize; i[k] = sign * chirpIm[k] / chirpSize; if (k > 0) { r[chirpSize - k] = r[k]; i[chirpSize - k] = i[k] } }
                inner.transform(r, i, 0, 1, chirpFftRe[direction], chirpFftIm[direction], false, buffers)
            }
        }
    }

    /** [transform]'s working arrays; pass one in to reuse it across calls. */
    class Scratch internal constructor(p: Int, m: Int, internal val inner: Scratch?) {
        internal val sc = DoubleArray(4 * p)
        internal val ar = DoubleArray(m); internal val ai = DoubleArray(m)
        internal val br = DoubleArray(m); internal val bj = DoubleArray(m)
    }
    fun scratch(): Scratch = Scratch(if (inner == null) maxP else 0, chirpSize, inner?.scratch())

    private fun bluestein(inRe: DoubleArray, inIm: DoubleArray, inOff: Int, inStride: Int,
                          outRe: DoubleArray, outIm: DoubleArray, inverse: Boolean, buffers: Scratch) {
        // the inverse is the same with the chirp conjugated
        val sign = if (inverse) -1.0 else 1.0
        val ar = buffers.ar; val ai = buffers.ai; val br = buffers.br; val bj = buffers.bj
        for (j in 0 until n) {
            val xr = inRe[inOff + j * inStride]; val xi = inIm[inOff + j * inStride]
            val wr = chirpRe[j]; val wi = sign * chirpIm[j]
            ar[j] = xr * wr - xi * wi; ai[j] = xr * wi + xi * wr
        }
        ar.fill(0.0, n, chirpSize); ai.fill(0.0, n, chirpSize)
        inner!!.transform(ar, ai, 0, 1, br, bj, false, buffers.inner!!)
        val direction = if (inverse) 1 else 0; val fr = chirpFftRe[direction]; val fi = chirpFftIm[direction]
        for (k in 0 until chirpSize) { val r = br[k]; val i = bj[k]; br[k] = r * fr[k] - i * fi[k]; bj[k] = r * fi[k] + i * fr[k] }
        inner.transform(br, bj, 0, 1, ar, ai, true, buffers.inner)
        for (k in 0 until n) {
            val wr = chirpRe[k]; val wi = sign * chirpIm[k]
            outRe[k] = ar[k] * wr - ai[k] * wi; outIm[k] = ar[k] * wi + ai[k] * wr
        }
    }

    private fun work(oRe: DoubleArray, oIm: DoubleArray, oOff: Int, fRe: DoubleArray, fIm: DoubleArray,
                     fOff: Int, fstride: Int, inStride: Int, factorIndex: Int, inverse: Boolean, sc: DoubleArray) {
        val p = factors[factorIndex]; val m = factors[factorIndex + 1]
        if (m == 1) {
            for (j in 0 until p) { val s = fOff + j * fstride * inStride; oRe[oOff + j] = fRe[s]; oIm[oOff + j] = fIm[s] }
        } else {
            for (j in 0 until p) work(oRe, oIm, oOff + j * m, fRe, fIm, fOff + j * fstride * inStride,
                fstride * p, inStride, factorIndex + 2, inverse, sc)
        }
        when (p) {
            2 -> bfly2(oRe, oIm, oOff, fstride, m, inverse)
            4 -> bfly4(oRe, oIm, oOff, fstride, m, inverse)
            else -> bflyOdd(oRe, oIm, oOff, fstride, p, m, inverse, sc)
        }
    }

    private fun bfly2(re: DoubleArray, im: DoubleArray, off: Int, fstride: Int, m: Int, inverse: Boolean) {
        val sign = if (inverse) -1.0 else 1.0
        for (u in 0 until m) {
            val a = off + u; val b = a + m
            val wr = twiddleRe[u * fstride]; val wi = sign * twiddleIm[u * fstride]
            val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
            re[b] = re[a] - tr; im[b] = im[a] - ti
            re[a] += tr; im[a] += ti
        }
    }

    /** kf_bfly4 */
    private fun bfly4(re: DoubleArray, im: DoubleArray, off: Int, fstride: Int, m: Int, inverse: Boolean) {
        val sign = if (inverse) -1.0 else 1.0
        for (u in 0 until m) {
            val i0 = off + u; val i1 = i0 + m; val i2 = i1 + m; val i3 = i2 + m
            val t1 = u * fstride; val t2 = 2 * t1; val t3 = 3 * t1
            val w1r = twiddleRe[t1]; val w1i = sign * twiddleIm[t1]; val w2r = twiddleRe[t2]; val w2i = sign * twiddleIm[t2]
            val w3r = twiddleRe[t3]; val w3i = sign * twiddleIm[t3]
            val s0r = re[i1] * w1r - im[i1] * w1i; val s0i = re[i1] * w1i + im[i1] * w1r
            val s1r = re[i2] * w2r - im[i2] * w2i; val s1i = re[i2] * w2i + im[i2] * w2r
            val s2r = re[i3] * w3r - im[i3] * w3i; val s2i = re[i3] * w3i + im[i3] * w3r
            val s5r = re[i0] - s1r; val s5i = im[i0] - s1i
            val ar = re[i0] + s1r; val ai = im[i0] + s1i
            val s3r = s0r + s2r; val s3i = s0i + s2i
            val s4r = s0r - s2r; val s4i = s0i - s2i
            re[i2] = ar - s3r; im[i2] = ai - s3i
            re[i0] = ar + s3r; im[i0] = ai + s3i
            if (inverse) {
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
    private fun bflyOdd(re: DoubleArray, im: DoubleArray, off: Int, fstride: Int, p: Int, m: Int, inverse: Boolean, sc: DoubleArray) {
        val sign = if (inverse) -1.0 else 1.0
        val sr = sc; val si = sc   // sc: [0, p) inputs re, [p, 2p) im, [2p, 4p) a_q / b_q re, im
        val step = n / p; val hp = (p - 1) / 2
        for (u in 0 until m) {
            sr[0] = re[off + u]; si[p] = im[off + u]
            var tw = 0
            for (q in 1 until p) {
                tw += fstride * u; if (tw >= n) tw -= n
                val k = off + u + q * m
                val wr = twiddleRe[tw]; val wi = sign * twiddleIm[tw]
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
                    var wr = twiddleRe[t0]; var wi = sign * twiddleIm[t0]
                    tr0 += ar * wr; ti0 += ai * wr; ur0 += br * wi; ui0 += bi * wi
                    wr = twiddleRe[t1]; wi = sign * twiddleIm[t1]
                    tr1 += ar * wr; ti1 += ai * wr; ur1 += br * wi; ui1 += bi * wi
                    wr = twiddleRe[t2]; wi = sign * twiddleIm[t2]
                    tr2 += ar * wr; ti2 += ai * wr; ur2 += br * wi; ui2 += bi * wi
                    wr = twiddleRe[t3]; wi = sign * twiddleIm[t3]
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
                    val wr = twiddleRe[t]; val wi = sign * twiddleIm[t]
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

/** Row-major 2-D FFT, in place. [inverse] divides by rows * cols, as numpy's ifft2. */
class Fft2(val rows: Int, val cols: Int, private val par: Par = Par.SERIAL) {
    private val rowFft = Fft(cols)
    private val colFft = Fft(rows)

    fun forward(re: DoubleArray, im: DoubleArray) = run(re, im, false)
    fun inverse(re: DoubleArray, im: DoubleArray) {
        run(re, im, true)
        val scale = 1.0 / (rows * cols)
        for (i in re.indices) { re[i] *= scale; im[i] *= scale }
    }

    /** Rows, then columns; each 1-D transform is independent, so [par] splits them. */
    private fun run(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        par.run(rows) { start, end ->
            val rowRe = DoubleArray(cols); val rowIm = DoubleArray(cols); val buffers = rowFft.scratch()
            for (row in start until end) {
                rowFft.transform(re, im, row * cols, 1, rowRe, rowIm, inverse, buffers)
                System.arraycopy(rowRe, 0, re, row * cols, cols); System.arraycopy(rowIm, 0, im, row * cols, cols)
            }
        }
        par.run(cols) { start, end ->
            val colRe = DoubleArray(rows); val colIm = DoubleArray(rows); val buffers = colFft.scratch()
            for (col in start until end) {
                colFft.transform(re, im, col, cols, colRe, colIm, inverse, buffers)
                for (row in 0 until rows) { re[row * cols + col] = colRe[row]; im[row * cols + col] = colIm[row] }
            }
        }
    }
}

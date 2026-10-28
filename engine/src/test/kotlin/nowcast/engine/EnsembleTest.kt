package nowcast.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Parity with nowcast/stepsport.py (stepsport_fixture.json). Tolerances: everything up to
 * the members is float64 arithmetic in a different order (pocketfft vs Fft.kt, pairwise vs
 * naive sums), ~1e-13 relative; 1e-9 leaves four decades of headroom yet would catch any
 * real slip, which shows up at 1e-3 or worse. Members are float32 on both sides: 1e-7
 * (float32 epsilon 6e-8, plus round-off of the float64 field before the cast).
 */
class EnsembleTest {
    private val F = StepsFixture
    private val h = F.h; private val w = F.w
    private val half = w / 2 + 1
    private fun halfOf(full: DoubleArray) = DoubleArray(h * half) { full[(it / half) * w + it % half] }
    private val mo get() = Motion(Grid(h, w, F.arr("vx")), Grid(h, w, F.arr("vy")))
    private fun db() = F.frames().takeLast(3).map { g -> g.map(::toDb).v }

    @Test fun fftMatchesNumpy() {
        val re = F.frames()[0].v.copyOf(); val im = DoubleArray(h * w)
        Fft2(h, w).forward(re, im)
        assertClose(F.arr("fft_re"), re, 1e-10, "fft re"); assertClose(F.arr("fft_im"), im, 1e-10, "fft im")
        Fft2(h, w).inverse(re, im)
        assertClose(F.frames()[0].v, re, 1e-12, "ifft")
        // against a naive DFT; 331 and 397 take Bluestein
        for (n in listOf(1, 2, 7, 13, 37, 64, 191, 331, 338, 364, 366, 382, 397)) {
            val x = DoubleArray(n) { kotlin.math.sin(1.3 * it * it) }; val y = DoubleArray(n) { kotlin.math.cos(0.7 * it) }
            val or = DoubleArray(n); val oi = DoubleArray(n)
            val f = Fft(n)
            f.transform(x, y, 0, 1, or, oi, false)
            for (k in 0 until n) {
                var a = 0.0; var b = 0.0
                for (j in 0 until n) {
                    val t = -2 * Math.PI * j * k / n; val c = kotlin.math.cos(t); val s = kotlin.math.sin(t)
                    a += x[j] * c - y[j] * s; b += x[j] * s + y[j] * c
                }
                assertEquals(a, or[k], 1e-9 * n); assertEquals(b, oi[k], 1e-9 * n)
            }
            val br = DoubleArray(n); val bi = DoubleArray(n)
            f.transform(or, oi, 0, 1, br, bi, true)
            for (j in 0 until n) { assertEquals(x[j], br[j] / n, 1e-12 * n); assertEquals(y[j], bi[j] / n, 1e-12 * n) }
        }
    }

    @Test fun filtersMatch() {
        assertClose(F.arr("tukey"), tukey(h, w), 1e-14, "tukey")
        val bp = bandpass(h, w)
        assertClose(F.arr("bandpass"), bp.flatMap { halfOf(it).toList() }.toDoubleArray(), 1e-12, "bandpass")
    }

    @Test fun setupMatches() {
        val s = setup(db(), mo)!!
        assertEquals(2, s.p)
        assertClose(F.arr("last"), s.last, 1e-12, "last")
        assertClose(F.arr("noise_filter"), halfOf(s.filt), 1e-9, "noise filter")
        assertClose(F.arr("levels_last"), decompose(s.last, bandpass(h, w), Fft2(h, w)).levels.flatMap { it.toList() }.toDoubleArray(), 1e-9, "levels")
        assertClose(F.arr("mu"), s.mu, 1e-9, "mu")
        assertClose(F.arr("sd"), s.sd, 1e-9, "sd")
        assertClose(F.arr("gamma_raw"), s.gammaRaw.flatMap { it.toList() }.toDoubleArray(), 1e-9, "gamma raw")
        assertClose(F.arr("gamma"), s.gamma.flatMap { it.toList() }.toDoubleArray(), 1e-9, "gamma")
        assertClose(F.arr("phi"), s.phi.flatMap { it.toList() }.toDoubleArray(), 1e-9, "phi")
    }

    @Test fun membersMatch() {
        val s = setup(db(), mo)!!
        var eps = DoubleArray(0); var f1 = DoubleArray(0)
        val dbm = (0 until 3).map { m -> member(s, mo, m, 4, 42, 1.0, 5.0, if (m == 0) { e, f -> eps = e; f1 = f } else null) }
        assertClose(F.arr("eps_m0k0"), eps, 1e-9, "eps")
        assertClose(F.arr("f1_m0"), f1, 1e-9, "f1")
        val u = Rng.uniforms(Rng.stream(42, 0, 999), 2)
        assertEquals(F.num("eps_par_m0"), laplace(u[0]), 1e-15); assertEquals(F.num("eps_perp_m0"), laplace(u[1]), 1e-15)
        val got = dbm.flatMap { m -> m.flatMap { l -> l.map { it.toDouble() } } }.toDoubleArray()
        assertClose(F.arr("members"), got, 1e-7, "members")
    }

    @Test fun wholeEnsembleMatchesWithKotlinMotion() {
        // Flow agrees with lite.motion to ~1e-7 px, which moves members by ~1e-6 relative
        val r = ensemble(F.frames(), nLead = 4, nMembers = 3, seed = 42)
        val got = r.members.flatMap { m -> m.flatMap { l -> l.map { it.toDouble() } } }.toDoubleArray()
        assertClose(F.arr("members"), got, 1e-4, "members")
    }

    @Test fun ar1PathMatches() {
        val fr = F.frames().toMutableList(); fr[fr.size - 3] = Grid(h, w)
        val d = fr.takeLast(3).map { g -> g.map(::toDb).v }
        val m1 = Motion(Grid(h, w, F.arr("ar1_vx")), Grid(h, w, F.arr("ar1_vy")))
        val s1 = setup(d, m1)!!
        assertEquals(F.num("ar1_p").toInt(), s1.p)
        assertClose(F.arr("ar1_phi"), s1.phi.flatMap { it.toList() }.toDoubleArray(), 1e-9, "ar1 phi")
        val got = (0 until 2).flatMap { m -> member(s1, m1, m, 2, 42, 1.0, 5.0).flatMap { l -> l.map { it.toDouble() } } }
        assertClose(F.arr("ar1_members"), got.toDoubleArray(), 1e-6, "ar1 members")
    }

    @Test fun maskProbmatchPercentile() {
        val b = F.arr("mask_in").map { it > 0.5 }.toBooleanArray()
        assertClose(F.arr("mask_r2"), dilatedMask(b, h, w, 2), 0.0, "mask")
        val pa = F.arr("pm_a"); val pb = F.arr("pm_b").sorted().toDoubleArray()
        assertClose(F.arr("pm_out"), probmatch(pa, pb), 0.0, "probmatch")
        assertEquals(F.num("pct_37"), percentileSorted(F.arr("pct_x"), 37.3), 0.0)
        assertEquals(1, structRadius(2.0, 5.0)); assertEquals(2, structRadius(1.0, 5.0))
    }

    @Test fun argsortIsStableWithTheTiesOfLessThan() {
        val rng = java.util.Random(3)
        val v = DoubleArray(5000) { when (it % 7) { 0 -> 0.0; 1 -> -0.0; 2 -> -15.0; else -> rng.nextGaussian() * 1e3 } }
        val want = v.indices.sortedWith { a, b -> if (v[a] < v[b]) -1 else if (v[b] < v[a]) 1 else 0 }
        assertArrayEquals(want.toIntArray(), argsortStable(v))
    }

    @Test fun deterministicAcrossThreadCounts() {
        val a = ensemble(F.frames(), nLead = 3, nMembers = 4, threads = 1)
        val b = ensemble(F.frames(), nLead = 3, nMembers = 4, threads = 4)
        val c = ensemble(F.frames(), nLead = 3, nMembers = 4, threads = 16)    // 4 members x 4-way pixel loops
        assertEquals(4.0, c.timings["ways"]!!, 0.0)
        for (m in 0 until 4) for (k in 0 until 3) {
            assertArrayEquals(a.members[m][k], b.members[m][k], 0f); assertArrayEquals(a.members[m][k], c.members[m][k], 0f)
        }
    }

    @Test fun dryInputGivesZeroForecast() {
        val r = ensemble(List(3) { Grid(h, w) }, nLead = 2, nMembers = 2)
        assertTrue(r.members.all { m -> m.all { l -> l.all { it == 0f } } })
    }

    @Test fun timingFullGrid() {
        val hh = 338; val ww = 364
        val frames = (0 until 3).map { t -> Grid(hh, ww, DoubleArray(hh * ww) { i ->
            val y = i / ww; val x = i % ww
            val g = 6.0 * exp(-(((x - 120 - 3.0 * t) * (x - 120 - 3.0 * t) + (y - 160.0) * (y - 160.0)) / 2000.0))
            g + 2.0 * (1 + kotlin.math.sin(x * 0.11 + y * 0.07 + t * 0.3)) * exp(-((x - 260.0) * (x - 260.0)) / 3000.0)
        }) }
        val t0 = System.nanoTime()
        val r = ensemble(frames, nLead = 12, nMembers = 10)
        val secs = (System.nanoTime() - t0) / 1e9
        assertEquals(10, r.members.size)
        assertTrue("took $secs s", secs < 30)
    }
}

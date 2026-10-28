package nowcast.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant

/**
 * Timings, allocations and output hashes of the app's hot paths on a Belgian-size grid: the real
 * OPERA sample cropped to Belgium, translated 2 px right and 1 px down per frame. Opt-in
 * (NOWCAST_BENCH=1). For peak heap run Bench.main with "cycle" under -Xmx and see if it survives.
 */
class Bench {
    @Test fun bench() {
        assumeTrue("set NOWCAST_BENCH to benchmark", System.getenv("NOWCAST_BENCH") != null)
        Bench.main(arrayOf("all"))
    }

    companion object {
        private val be = Region("be", "Belgium", listOf(2.0, 49.0, 6.8, 52.2))
        private val tmx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        private val dir = File(System.getProperty("bench.dir") ?: File(System.getProperty("user.dir")).let {
            if (File(it, "src/test/resources").isDirectory) it.path else "engine" })

        fun frames(): List<Frame> {
            val s = Odim.readCrop(File(dir, "src/test/resources/opera_sample.h5"), be)
            val h = s.geo.h; val w = s.geo.w
            return (0 until 12).map { t ->
                val dc = 2 * (t - 11); val dr = t - 11
                Frame(s.time.plusSeconds(300L * (t - 11)), Grid(h, w, DoubleArray(h * w) { i ->
                    val r = i / w - dr; val c = i % w - dc
                    if (r in 0 until h && c in 0 until w) s.rain[r, c] else 0.0
                }), s.geo)
            }
        }

        fun model() = SmaatModel(File(dir, "../app/src/main/assets/smaat.onnx").readBytes())

        private fun hash(a: FloatArray) = a.contentHashCode()
        private fun hash(a: DoubleArray) = a.contentHashCode()
        private fun hash(a: ByteArray) = a.contentHashCode()

        /** best-of-[n] seconds and the last run's allocated MB (all threads). */
        private fun <T> time(name: String, n: Int, f: () -> T): T {
            var best = Double.MAX_VALUE; var r: T? = null; var mb = 0.0
            repeat(n) {
                System.gc()
                val a0 = tmx.totalThreadAllocatedBytes; val t0 = System.nanoTime()
                r = f()
                val t = (System.nanoTime() - t0) / 1e9
                mb = (tmx.totalThreadAllocatedBytes - a0) / 1e6
                if (t < best) best = t
            }
            println("BENCH %-22s %8.1f ms  %8.1f MB allocated".format(name, best * 1000, mb))
            return r!!
        }

        /** The app's MapFrames.of, on the engine side. */
        fun mapLevels(fc: Forecast, s: Render.Sampler): List<ByteArray> {
            val fields = fc.past.map { f -> FloatArray(f.rain.v.size) { f.rain.v[it].toFloat() } } + fc.mean.toList()
            return fields.map { Render.levels(it, fc.geo.h, fc.geo.w, s) }
        }

        @JvmStatic fun main(args: Array<String>) {
            val what = args.firstOrNull() ?: "all"
            val fr = frames()
            val now = fr.last().time.plusSeconds(120)
            val (lat, lon) = 50.85 to 4.35
            println("BENCH grid ${fr[0].geo.h} x ${fr[0].geo.w}, threads ${Runtime.getRuntime().availableProcessors()}, Xmx ${Runtime.getRuntime().maxMemory() / 1048576} MB")
            if (what == "cycle") {
                // what Repo.refresh then drawMap hold at once: forecast, point, map frames
                val fc = model().use { m -> Nowcaster(m).run(be, fr) }
                val p = fc.point(lat, lon, now)
                val s = Render.mercatorSampler(fc.geo, be.bbox, width = 512)
                val lv = mapLevels(fc, s); Render.rasterMotion(fc.motion, s)
                println("BENCH cycle ok: model ${fc.modelUsed} ${fc.modelError ?: ""} point ${hash(p.values)} levels ${lv.size}")
                return
            }
            val m = model()
            val n = if (what == "quick") 1 else 3
            val fc0 = time("run (no model)", n) { Nowcaster(null).run(be, fr) }
            val fc = time("run (model)", n) { Nowcaster(m).run(be, fr) }
            println("BENCH timings ${fc.timings}")
            // the wettest point at +30 min on a 0.05 degree lattice: a dry point would test little
            var best = -1f; var wet = lat to lon
            for (i in 0..56) for (j in 0..90) {
                val la = 49.2 + 0.05 * i; val lo = 2.2 + 0.05 * j; val (r, c) = fc.geo.rowCol(la, lo)
                if (r in 0 until fc.geo.h && c in 0 until fc.geo.w && fc.mean[5][r * fc.geo.w + c] > best) { best = fc.mean[5][r * fc.geo.w + c]; wet = la to lo }
            }
            println("BENCH point at $wet, $best mm/h at +30 min")
            val p = time("point", 5 * n) { fc.point(wet.first, wet.second, now) }
            val s = time("mercatorSampler", n) { Render.mercatorSampler(fc.geo, be.bbox, width = 512) }
            val lv = time("levels x${fc.past.size + fc.mean.size}", n) { mapLevels(fc, s) }
            val rm = time("rasterMotion", n) { Render.rasterMotion(fc.motion, s) }
            val wp = time("warp", 20 * n) { Render.warp(lv[11], lv[12], s.width, s.height, rm, 0.4) }
            val lut = Render.lut(Render.DEFAULT_PALETTE)
            time("argb", 20 * n) { Render.argb(wp, lut) }
            println("BENCH hash members0 ${fc0.members.sumOf { mm -> mm.sumOf { hash(it) } }} members ${fc.members.sumOf { mm -> mm.sumOf { hash(it) } }} mean ${fc.mean.sumOf { hash(it) }}")
            println("BENCH hash point ${listOf(p.values, p.probability, p.pmin, p.p10, p.p25, p.p75, p.p90, p.pmax).map { hash(it) }} ${p.summary}")
            println("BENCH hash levels ${lv.sumOf { hash(it) }} motion ${hash(rm.dx)} ${hash(rm.dy)} warp ${hash(wp)} sampler ${hash(s.rows)} ${hash(s.cols)}")
            m.close()
        }
    }
}

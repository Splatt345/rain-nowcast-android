package nowcast.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.abs

/**
 * runPoint against the full run at home. Opt-in: NOWCAST_WINDOWS names a little-endian float32
 * file of [n, 12, 384, 384] archive crops (1 km, centred on 50.5N 4.5E), exported from
 * local_weather_model/data/opera_archive_5min*. Prints one line per window; asserts nothing.
 */
class PointBoxEval {
    private val home = 50.85 to 4.35
    private val be = Region("be", "Belgium", listOf(2.0, 49.0, 6.8, 51.8))
    private val model by lazy { SmaatModel(File(System.getProperty("user.dir"), "../app/src/main/assets/smaat.onnx").readBytes()) }

    private fun compare(name: String, frames: List<Frame>) {
        val now = frames.last().time
        val t0 = System.nanoTime(); val full = Nowcaster(model).run(be, frames).point(home.first, home.second, now)
        val tFull = (System.nanoTime() - t0) / 1e9
        val fut = full.minutes.indices.filter { full.minutes[it] >= 0 }
        fun line(what: String, p: PointForecast, secs: Double) {
            fun stat(a: DoubleArray, b: DoubleArray) = fut.map { abs(a[it] - b[it]) }.let { it.max() to it.average() }
            val (pMax, pMean) = stat(full.probability, p.probability)
            val (vMax, vMean) = stat(full.values, p.values)
            println("POINTEVAL %s %s: %.2f s (full %.2f) | prob max %.2f mean %.3f | mm/h max %.2f mean %.3f (peak %.2f) | chance %.2f vs %.2f | \"%s\" vs \"%s\"".format(
                name, what, secs, tFull, pMax, pMean, vMax, vMean, fut.maxOf { full.values[it] },
                full.rainChance, p.rainChance, full.summary, p.summary))
        }
        // the noise floor: the same full run with another seed
        line("seed43", Nowcaster(model, seed = 43).run(be, frames).point(home.first, home.second, now), 0.0)
        for (km in listOf(128.0, 192.0)) {
            val t = System.nanoTime(); val p = Nowcaster(model).runPoint(be, frames, home.first, home.second, km, now)
            line("box${km.toInt()}", p, (System.nanoTime() - t) / 1e9)
        }
    }

    @Test fun sampleAndArchiveWindows() {
        val path = System.getenv("NOWCAST_WINDOWS")
        assumeTrue("set NOWCAST_WINDOWS for the archive windows", path != null)
        val sample = Odim.readCrop(File(javaClass.getResource("/opera_sample.h5")!!.toURI()), be)
        compare("sample (static)", List(12) { k -> Frame(sample.time.plusSeconds(300L * (k - 11)), sample.rain, sample.geo) })
        val n = 384 * 384
        val buf = ByteBuffer.wrap(File(path!!).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val (xc, yc) = sample.geo.laea.forward(50.5, 4.5)
        val geo = GridGeo(sample.geo.laea, xc - 192_000.0, yc + 192_000.0, 1000.0, 1000.0, 384, 384)
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        for (wi in 0 until buf.capacity() / (12 * n)) {
            val frames = List(12) { k -> Frame(t0.plusSeconds(300L * k),
                Grid(384, 384, DoubleArray(n) { buf.get((wi * 12 + k) * n + it).toDouble() }), geo) }
            compare("window $wi", frames)
        }
    }
}

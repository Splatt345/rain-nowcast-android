package nowcast.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelTest {
    private val bytes = File(System.getProperty("user.dir"), "../app/src/main/assets/smaat.onnx").readBytes()

    @Test fun transformsRoundTripAndClamp() {
        for (x in listOf(0.0, 0.1, 1.0, 10.0, 100.0))
            assertEquals(x, Transform.fromModel(Transform.toModel(x)).toDouble(), 1e-3 * (1 + x))
        assertTrue(Transform.fromModel(2.0f) <= 100.001f)
        assertEquals(0f, Transform.toModel(Double.NaN))
    }

    @Test fun runsAtTheLiveSizeAndKeepsDryDry() {
        SmaatModel(bytes).use { m ->
            val rng = java.util.Random(0)
            val train = List(12) { Grid(338, 364, DoubleArray(338 * 364) { -2.0 * kotlin.math.ln(1 - rng.nextDouble()) }) }
            val out = m.forecast(train, 12)
            assertEquals(12, out.size); assertEquals(338 * 364, out[0].size)
            assertTrue(out.all { f -> f.all { it.isFinite() && it >= 0f && it <= 100.001f } })
            val dry = m.forecast(List(12) { Grid(64, 64) }, 12)
            assertTrue(dry.all { f -> f.all { it < 1f } })
        }
    }
}

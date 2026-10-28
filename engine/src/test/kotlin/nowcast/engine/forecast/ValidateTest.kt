package nowcast.engine.forecast

import nowcast.engine.radar.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ValidateTest {
    private val be = Region("be", "Belgium", listOf(2.0, 49.0, 6.8, 51.8))

    @Test fun regions() {
        assertEquals(emptyList<String>(), Validate.region(be, listOf("nl")))
        assertEquals(1, Validate.region(be, listOf("be")).size)                                  // duplicate id
        assertEquals(1, Validate.region(be.copy(id = "Be l"), emptyList()).size)                 // bad id
        assertEquals(1, Validate.region(be.copy(name = " "), emptyList()).size)
        assertEquals(1, Validate.region(be.copy(bbox = listOf(6.8, 49.0, 2.0, 51.8)), emptyList()).size)   // flipped
        assertEquals(1, Validate.region(be.copy(bbox = listOf(-80.0, 40.0, -75.0, 42.0)), emptyList()).size) // not OPERA
        assertEquals(1, Validate.region(be.copy(bbox = listOf(-10.0, 35.0, 20.0, 60.0)), emptyList()).size) // too big
        assertEquals(1, Validate.region(be.copy(bbox = listOf(2.0, 49.0)), emptyList()).size)
        assertEquals(1, Validate.region(be.copy(source = "knmi"), emptyList()).size)
        assertEquals(1, Validate.region(be.copy(weights = List(11) { 0.6 }), emptyList()).size)
    }

    @Test fun weights() {
        assertNull(Validate.weights("  "))
        assertEquals(Combine.WEIGHTS.toList(), Validate.weights(Combine.WEIGHTS.joinToString(", ")))
        assertThrows(IllegalArgumentException::class.java) { Validate.weights("0.5, 0.6") }
        assertThrows(IllegalArgumentException::class.java) { Validate.weights(List(12) { "1.5" }.joinToString(",")) }
        assertThrows(IllegalArgumentException::class.java) { Validate.weights("a,b") }
    }

    @Test fun models() {
        val smaat = File(System.getProperty("user.dir"), "../app/src/main/assets/smaat.onnx").readBytes()
        assertNull(Validate.model(smaat))
        assertNotNull(Validate.model(ByteArray(1000) { it.toByte() }))
        assertNotNull(Validate.model(smaat.copyOf(smaat.size / 2)))
        // the GBM is a valid ONNX file but not a 12 -> 12 frame model
        val gbm = File(System.getProperty("user.dir"), "../app/src/main/assets/outlook/outlook_gbm.onnx").readBytes()
        assertNotNull(Validate.model(gbm))
    }
}

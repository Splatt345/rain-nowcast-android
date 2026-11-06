package nowcast.engine.outlook

import org.junit.Assert.*
import org.junit.Test

class OmFileTest {
    // written by Open-Meteo's own Python writer: 70×90 rain at 0.1 mm, chunks 32×32, spikes of 300, a NaN edge, a dry corner
    private val bytes = javaClass.getResource("/om_sample.om")!!.readBytes()
    // a short tail, so the chunk index and some metadata come through [read] too
    private val om = OmFile(bytes.size.toLong(), bytes.copyOfRange(bytes.size - 100, bytes.size)) { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }

    private fun window(a: OmFile.Array2D, row0: Int, row1: Int, col0: Int, col1: Int): FloatArray {
        val w = om.Window(a, row0, row1, col0, col1)
        val (offset, length) = w.span
        return w.decode(bytes.copyOfRange(offset.toInt(), offset.toInt() + length))
    }

    @Test fun readsWhatTheirReaderReads() {
        val a = om.arrays().getValue("precipitation")
        assertEquals(70, a.rows); assertEquals(90, a.cols); assertEquals(32, a.chunkRows)
        val all = window(a, 0, 70, 0, 90)
        assertEquals(840, all.count { it.isNaN() })
        assertEquals(18240.4, all.filter { !it.isNaN() }.sumOf { it.toDouble() }, 0.05)    // omfiles' nansum
        assertEquals(7.9f, all[35 * 90 + 50], 1e-4f); assertEquals(300f, all.filter { !it.isNaN() }.max(), 0f)
        val part = window(a, 20, 50, 30, 75)                                            // across chunk edges
        assertEquals(7604.6, part.sumOf { it.toDouble() }, 0.05)
        assertEquals(0.3f, part[0], 1e-4f); assertEquals(0.1f, part[29 * 45 + 44], 1e-4f)
    }
}

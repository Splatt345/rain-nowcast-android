package nowcast.engine.radar

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** A local S3 stand-in serving opera_sample.h5, with or without Range support. */
class RangedReadTest {
    private val be = Region("be", "Belgium", listOf(2.0, 49.0, 6.8, 51.8))
    private val sample = File(javaClass.getResource("/opera_sample.h5")!!.toURI())
    private val bytes = sample.readBytes()
    private var ranges = true
    var served = 0L; var requests = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requests++
            val r = ex.requestHeaders.getFirst("Range")
            assertNull("no key may reach S3", ex.requestHeaders.getFirst("apikey"))
            if (ranges && r != null) {
                val (a, b) = r.removePrefix("bytes=").split('-').map { it.toLong() }
                val end = minOf(b, bytes.size - 1L)
                ex.responseHeaders.add("Content-Range", "bytes $a-$end/${bytes.size}")
                ex.sendResponseHeaders(206, end - a + 1)
                ex.responseBody.use { it.write(bytes, a.toInt(), (end - a + 1).toInt()) }
                served += end - a + 1
            } else {
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                served += bytes.size
            }
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/2026/09/14/OPERA/COMP/OPERA@20260914T0320@0@DBZH.h5"
    private val cache = Files.createTempDirectory("radar").toFile()

    @After fun stop() { server.stop(0); cache.deleteRecursively() }

    private fun assertSame(want: Frame, got: Frame) {
        assertEquals(want.time, got.time)
        assertEquals(want.rain.rows, got.rain.rows); assertEquals(want.rain.cols, got.rain.cols)
        for (i in want.rain.values.indices)      // bit-identical, NaN included
            assertEquals("pixel $i", want.rain.values[i].toRawBits(), got.rain.values[i].toRawBits())
        assertEquals(want.geo.left, got.geo.left, 0.0); assertEquals(want.geo.top, got.geo.top, 0.0)
    }

    @Test fun rangedCropIsBitIdenticalAndSmall() {
        val src = RadarSource(null, cache)
        val got = src.frame(url, be.bbox)
        assertSame(Odim.readCrop(sample, be), got)
        println("RANGED region ${be.bbox}: ${src.bytesFetched} of ${bytes.size} bytes in $requests requests")
        assertEquals(served, src.bytesFetched)
        assertTrue("fetched ${src.bytesFetched}", src.bytesFetched < bytes.size / 3)
        // the next cycle, or a smaller box in the same frame, reads from the block cache
        val before = served
        assertSame(got, RadarSource(null, cache).frame(url, be.bbox))
        val box = pointBbox(50.85, 4.35, 128.0)
        val small = RadarSource(null, cache).frame(url, box)
        assertEquals(before, served)
        assertTrue(small.rain.rows in 128..200 && small.rain.cols in 128..200)
    }

    @Test fun pointBoxFetchesNoMoreThanTheRegion() {
        val a = RadarSource(null, File(cache, "a")); a.frame(url, be.bbox)
        val b = RadarSource(null, File(cache, "b")); b.frame(url, pointBbox(50.85, 4.35, 128.0))
        println("RANGED point box: ${b.bytesFetched} bytes, region ${a.bytesFetched}, file ${bytes.size}")
        assertTrue(b.bytesFetched <= a.bytesFetched)
    }

    @Test fun fallsBackToTheWholeFileWithoutRangeSupport() {
        ranges = false
        val src = RadarSource(null, cache)
        assertSame(Odim.readCrop(sample, be), src.frame(url, be.bbox))
        assertEquals(bytes.size.toLong(), src.bytesFetched)
    }
}

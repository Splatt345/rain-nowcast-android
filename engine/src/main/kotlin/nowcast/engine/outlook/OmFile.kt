package nowcast.engine.outlook

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Just enough of Open-Meteo's .om file format (version 3) to read a window of a 2D float array stored as
 * pfor_delta_2d_int16, as the files behind their weather maps are. A port of the reader in
 * github.com/open-meteo/om-file-format (GPL-2.0-or-later; its integer codec is TurboPFor by powturbo).
 * [tail] is the end of the file, which holds the trailer, the arrays' metadata and their chunk indexes;
 * [read] returns `length` bytes from `offset` for the rest.
 */
class OmFile(private val size: Long, private val tail: ByteArray, private val read: (offset: Long, length: Int) -> ByteArray) {

    class Array2D(val name: String, val rows: Int, val cols: Int, val chunkRows: Int, val chunkCols: Int,
                  val scale: Float, val offset: Float, val lutOffset: Long, val lutSize: Long)

    private fun bytes(offset: Long, length: Int): ByteArray {
        val inTail = offset - (size - tail.size)
        return if (inTail >= 0) tail.copyOfRange(inTail.toInt(), inTail.toInt() + length) else read(offset, length)
    }

    /** The file's arrays by name, from the trailer and the root's children. */
    fun arrays(): Map<String, Array2D> {
        val trailer = le(bytes(size - 24, 24))
        if (trailer.get(0) != 'O'.code.toByte() || trailer.get(1) != 'M'.code.toByte() || trailer.get(2) != 3.toByte()) throw OmException("not an OM v3 file")
        val root = le(bytes(trailer.getLong(8), trailer.getLong(16).toInt()))
        val count = root.getInt(4)
        val head = if (root.get(0) in ARRAY_TYPES) 40 else 8
        val sizes = LongArray(count) { root.getLong(head + 8 * it) }; val offsets = LongArray(count) { root.getLong(head + 8 * (count + it)) }
        if (count == 0) return emptyMap()
        val from = offsets.min(); val span = le(bytes(from, (offsets.indices.maxOf { offsets[it] + sizes[it] } - from).toInt()))
        return offsets.indices.mapNotNull { i -> array(span, (offsets[i] - from).toInt()) }.associateBy { it.name }
    }

    private fun array(b: ByteBuffer, at: Int): Array2D? {
        if (b.get(at) != FLOAT_ARRAY || b.get(at + 1) != PFOR_DELTA2D_INT16) return null
        val nameSize = b.getShort(at + 2).toInt() and 0xffff; val children = b.getInt(at + 4)
        if (b.getLong(at + 24) != 2L) return null
        val dims = at + 40 + 16 * children
        val name = ByteArray(nameSize).also { for (k in it.indices) it[k] = b.get(dims + 32 + k) }.decodeToString()
        return Array2D(name, b.getLong(dims).toInt(), b.getLong(dims + 8).toInt(), b.getLong(dims + 16).toInt(), b.getLong(dims + 24).toInt(),
            b.getFloat(at + 32), b.getFloat(at + 36), b.getLong(at + 16), b.getLong(at + 8))
    }

    /** A window of an array: rows [row0, row1) and columns [col0, col1), and the byte range holding it. */
    inner class Window(val a: Array2D, val row0: Int, val row1: Int, val col0: Int, val col1: Int) {
        private val chunkCols = (a.cols + a.chunkCols - 1) / a.chunkCols
        private val lut = lut(bytes(a.lutOffset, a.lutSize.toInt()), ((a.rows + a.chunkRows - 1) / a.chunkRows) * chunkCols)
        private val cx0 = col0 / a.chunkCols; private val cx1 = (col1 - 1) / a.chunkCols
        private val chunkRows = row0 / a.chunkRows..(row1 - 1) / a.chunkRows
        private val first = lut[chunkRows.first * chunkCols + cx0]
        /** (offset, length) to read: from the window's first chunk to its last. */
        val span = first to (lut[chunkRows.last * chunkCols + cx1 + 1] - first).toInt()

        /** Row-major values from the bytes of [span]; NaN where the file has no value. */
        fun decode(bytes: ByteArray): FloatArray {
            val width = col1 - col0
            val out = FloatArray((row1 - row0) * width)
            for (cy in chunkRows) {
                for (cx in cx0..cx1) {
                    val chunk = cy * chunkCols + cx
                    val h = minOf(a.chunkRows, a.rows - cy * a.chunkRows); val w = minOf(a.chunkCols, a.cols - cx * a.chunkCols)
                    val values = pfor(bytes, (lut[chunk] - first).toInt(), (lut[chunk + 1] - first).toInt(), h * w, 16, zigzag = true)
                    for (r in 1 until h) for (c in 0 until w) values[r * w + c] += values[(r - 1) * w + c]      // 2D delta: rows add up
                    for (r in 0 until h) {
                        val row = cy * a.chunkRows + r - row0
                        if (row !in 0 until row1 - row0) continue
                        for (c in 0 until w) {
                            val col = cx * a.chunkCols + c - col0
                            if (col !in 0 until width) continue
                            val v = values[r * w + c].toShort()
                            out[row * width + col] = if (v == Short.MAX_VALUE) Float.NaN else v / a.scale - a.offset
                        }
                    }
                }
            }
            return out
        }
    }

    companion object {
        private const val FLOAT_ARRAY: Byte = 20
        private const val PFOR_DELTA2D_INT16: Byte = 0
        private val ARRAY_TYPES = (12..21).map { it.toByte() }
        private const val LUT_CHUNK_COUNT = 64

        private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        /** The chunk index: chunks + 1 absolute file offsets, chunk i spanning [i, i+1). */
        private fun lut(bytes: ByteArray, chunks: Int): LongArray {
            val entries = chunks + 1
            val blockSize = bytes.size / (chunks / LUT_CHUNK_COUNT + 1)
            val out = LongArray(entries)
            var i = 0
            while (i < entries) {
                val n = minOf(LUT_CHUNK_COUNT, entries - i)
                pfor(bytes, (i / LUT_CHUNK_COUNT) * blockSize, (i / LUT_CHUNK_COUNT + 1) * blockSize, n, 64, zigzag = false).copyInto(out, i)
                i += n
            }
            return out
        }

        /**
         * om_pfor_decode: a variable-byte first value, then blocks of up to 128 deltas (zigzagged if [zigzag]),
         * full 16-bit blocks bit-packed in SIMD lanes, the rest packed in one stream. Values are [bits] wide: 16 or 64.
         */
        private fun pfor(src: ByteArray, from: Int, to: Int, count: Int, bits: Int, zigzag: Boolean): LongArray {
            val out = LongArray(count)
            if (count == 0) return out
            val mask = if (bits == 64) -1L else (1L shl bits) - 1
            var pos = from
            val (seed, used) = seed(src, pos)
            pos += used
            var start = seed and mask
            out[0] = start
            var k = 1
            val block = LongArray(128)
            while (k < count) {
                val n = minOf(count - k, 128)
                pos = block(src, pos, n, bits, block, lanes = n == 128 && bits == 16)
                for (i in 0 until n) {
                    val d = block[i] and mask
                    start = (start + if (zigzag) (d ushr 1) xor -(d and 1) else d) and mask
                    out[k + i] = start
                }
                k += n
            }
            if (pos > to) throw OmException("chunk overrun")
            return out
        }

        /** vbxget: the length is the leading ones of the first byte. */
        private fun seed(src: ByteArray, at: Int): Pair<Long, Int> {
            fun u(i: Int) = (src[at + i].toLong() and 0xff)
            val h = u(0).toInt()
            var bytes = 1
            var m = 128; while (m != 0 && h and m != 0) { bytes++; m = m shr 1 }
            if (bytes == 1) return h.toLong() to 1
            var v: Long
            when (bytes) {
                2, 4, 8 -> { v = (h and (255 shr bytes)).toLong(); for (i in 1 until bytes) v = (v shl 8) or u(i) }
                7 -> { v = ((h and 1).toLong() shl 48) or (u(1) shl 32) or (u(2) shl 40); for (i in 0 until 4) v = v or (u(3 + i) shl (8 * i)) }
                6 -> { v = ((((h and 3) shl 8) or u(1).toInt()).toLong() shl 32); for (i in 0 until 4) v = v or (u(2 + i) shl (8 * i)) }
                else -> { v = 0; for (i in 1 until bytes) v = v or (u(i) shl (8 * (i - 1))); if (bytes < 9) v = v or ((h and (255 shr bytes)).toLong() shl (8 * (bytes - 1))) }
            }
            return v to bytes
        }

        /** One TurboPFor block of [n] values into [out]; returns the position after it. */
        private fun block(src: ByteArray, at: Int, n: Int, bits: Int, out: LongArray, lanes: Boolean): Int {
            var pos = at
            var b = src[pos++].toInt() and 0xff
            fun unpack(p: Int, b: Int) = if (lanes) unpackLanes(src, p, n, b, bits, out) else unpack(src, p, n, b, out)
            when {
                b and 0xc0 == 0xc0 -> {                         // all equal
                    b = b and 0x3f; if (bits == 64 && b == 63) b = 64
                    val u = bitsAt(src, pos.toLong() * 8, b)
                    out.fill(u, 0, n)
                    return pos + (b + 7) / 8
                }
                b and 0x40 == 0 -> {                            // bit-packed, maybe with exceptions marked in a bitmap
                    if (b and 0x80 == 0) { if (bits == 64 && b == 63) b = 64; return unpack(pos, b) }
                    val bx = src[pos++].toInt() and 0xff
                    b = b and 0x7f
                    val bitmap = pos
                    val marked = (0 until n).filter { src[bitmap + it / 8].toInt() shr (it % 8) and 1 == 1 }
                    val ex = LongArray(marked.size)
                    pos = unpack(src, bitmap + (if (lanes) 16 else (n + 7) / 8), marked.size, bx, ex)
                    pos = unpack(pos, b)
                    marked.forEachIndexed { k, i -> out[i] += ex[k] shl b }
                    return pos
                }
                else -> {                                       // bit-packed, exceptions variable-byte with positions
                    b = b and 0x3f
                    val count = src[pos++].toInt() and 0xff
                    pos = unpack(pos, b)
                    val ex = LongArray(count)
                    if (src[pos].toInt() and 0xff == 255) {
                        pos++
                        for (k in 0 until count) { ex[k] = bitsAt(src, pos.toLong() * 8, bits); pos += bits / 8 }
                    } else for (k in 0 until count) {
                        val x = src[pos++].toInt() and 0xff
                        ex[k] = when {
                            x < VB_OFS1 -> x.toLong()
                            x < VB_BA2 -> ((x.toLong() shl 8) + (src[pos++].toLong() and 0xff)) + (VB_OFS1 - (VB_OFS1 shl 8))
                            x < VB_BA3 -> bitsAt(src, pos.toLong() * 8, 16).also { pos += 2 } + ((x - VB_BA2).toLong() shl 16) + VB_OFS2
                            else -> { val extra = x - VB_BA3; bitsAt(src, pos.toLong() * 8, 24 + 8 * extra).also { pos += 3 + extra } }
                        }
                    }
                    for (k in 0 until count) out[src[pos + k].toInt() and 0xff] = out[src[pos + k].toInt() and 0xff] or (ex[k] shl b)
                    return pos + count
                }
            }
        }

        private const val VB_OFS1 = 177; private const val VB_BA2 = 241; private const val VB_BA3 = 249; private const val VB_OFS2 = 16561L

        /** [n] values of [b] bits in one little-endian stream. */
        private fun unpack(src: ByteArray, at: Int, n: Int, b: Int, out: LongArray): Int {
            for (i in 0 until n) out[i] = bitsAt(src, at.toLong() * 8 + i.toLong() * b, b)
            return at + (n * b + 7) / 8
        }

        /** 128 values packed vertically: 128/[bits] lanes, value i in lane i % lanes, each lane a stream of [bits]-wide words. */
        private fun unpackLanes(src: ByteArray, at: Int, n: Int, b: Int, bits: Int, out: LongArray): Int {
            val lanes = 128 / bits; val wordBytes = bits / 8
            for (i in 0 until n) {
                val lane = i % lanes; var q = (i / lanes) * b
                var v = 0L
                for (got in 0 until b) {
                    val byte = at + ((q / bits) * lanes + lane) * wordBytes + (q % bits) / 8
                    if (byte < src.size && (src[byte].toInt() shr (q % 8)) and 1 == 1) v = v or (1L shl got)
                    q++
                }
                out[i] = v
            }
            return at + n * b / 8
        }

        /** [b] bits from bit [bit] of [src], least significant first; past the end reads as 0. */
        private fun bitsAt(src: ByteArray, bit: Long, b: Int): Long {
            var v = 0L; var got = 0; var p = bit
            while (got < b) {
                val byte = (p ushr 3).toInt()
                val off = (p and 7).toInt(); val take = minOf(8 - off, b - got)
                if (byte < src.size) v = v or ((((src[byte].toInt() and 0xff) ushr off).toLong() and ((1L shl take) - 1)) shl got)
                got += take; p += take
            }
            return v
        }
    }
}

class OmException(msg: String) : Exception(msg)

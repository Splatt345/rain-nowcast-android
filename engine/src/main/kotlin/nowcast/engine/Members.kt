package nowcast.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The ensemble, members[m][lead][r*w+c] in mm/h, off the Java heap: ~67 MB for Belgium, which a
 * 192 MB heap cannot spare. With a [dir] it is a mapping of an unlinked file there: once [seal]ed its
 * pages are clean, so Android can drop them when memory is short (the app in the background) and read
 * them back from flash on the next map tap - no re-run. As a List, [get] copies a member out (tests);
 * the hot loops use [at] and [set].
 */
class Members private constructor(val count: Int, val nLead: Int, val n: Int, private val buf: ByteBuffer) :
    AbstractList<Array<FloatArray>>() {
    private val f = buf.asFloatBuffer()
    override val size get() = count
    override fun get(index: Int) = Array(nLead) { k -> FloatArray(n).also { f.duplicate().apply { position((index * nLead + k) * n) }.get(it) } }
    fun at(m: Int, k: Int, i: Int): Float = f.get((m * nLead + k) * n + i)
    fun set(m: Int, k: Int, i: Int, v: Float) { f.put((m * nLead + k) * n + i, v) }
    /** Member [m]'s leads; threads may put different members at once. */
    fun put(m: Int, leads: Array<FloatArray>) { for (k in 0 until nLead) f.duplicate().apply { position((m * nLead + k) * n) }.put(leads[k]) }
    /** Write the pages back, so they are clean and the OS may drop them. */
    fun seal() { (buf as? MappedByteBuffer)?.force() }

    companion object {
        fun alloc(count: Int, nLead: Int, n: Int, dir: File? = null): Members {
            val bytes = 4L * count * nLead * n
            // unlinked at once: the mapping keeps it alive, nothing is left behind, and a new run never
            // writes into a file an older forecast still reads. A nearly full disk falls back to memory:
            // the file is sparse, and a page that finds no space on write is a SIGBUS, not an exception.
            // ponytail: free-space check, not a reservation; posix_fallocate (android.system.Os) if it ever bites.
            val buf = dir?.takeIf { it.usableSpace > 2 * bytes + (64L shl 20) }?.let {
                runCatching {
                    val file = File.createTempFile("members", ".f32", dir)
                    try { RandomAccessFile(file, "rw").use { it.channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes) } } finally { file.delete() }
                }.getOrNull()
            } ?: ByteBuffer.allocateDirect(bytes.toInt())
            return Members(count, nLead, n, buf.order(ByteOrder.nativeOrder()))
        }

        fun of(a: Array<Array<FloatArray>>) = alloc(a.size, a[0].size, a[0][0].size).also { m -> a.forEachIndexed(m::put) }
    }
}

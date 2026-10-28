package nowcast.engine.forecast

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The ensemble, members[member][lead][row * cols + col] in mm/h, off the Java heap: ~67 MB for Belgium, which a
 * 192 MB heap cannot spare. With a [dir] it is a mapping of an unlinked file there: once [seal]ed its
 * pages are clean, so Android can drop them when memory is short (the app in the background) and read
 * them back from flash on the next map tap - no re-run. As a List, [get] copies a member out (tests);
 * the hot loops use [at] and [set].
 */
class Members private constructor(val count: Int, val leadCount: Int, val cellCount: Int, private val buf: ByteBuffer) :
    AbstractList<Array<FloatArray>>() {
    private val floats = buf.asFloatBuffer()
    override val size get() = count
    override fun get(index: Int) = Array(leadCount) { lead -> FloatArray(cellCount).also { floats.duplicate().apply { position((index * leadCount + lead) * cellCount) }.get(it) } }
    fun at(member: Int, lead: Int, cell: Int): Float = floats.get((member * leadCount + lead) * cellCount + cell)
    fun set(member: Int, lead: Int, cell: Int, value: Float) { floats.put((member * leadCount + lead) * cellCount + cell, value) }
    /** Member [member]'s leads; threads may put different members at once. */
    fun put(member: Int, leads: Array<FloatArray>) { for (lead in 0 until leadCount) floats.duplicate().apply { position((member * leadCount + lead) * cellCount) }.put(leads[lead]) }
    /** Write the pages back, so they are clean and the OS may drop them. */
    fun seal() { (buf as? MappedByteBuffer)?.force() }

    companion object {
        fun alloc(count: Int, leadCount: Int, cellCount: Int, dir: File? = null): Members {
            val bytes = 4L * count * leadCount * cellCount
            // unlinked at once: the mapping keeps it alive, nothing is left behind, and a new run never
            // writes into a file an older forecast still reads. A nearly full disk falls back to memory:
            // the file is sparse, and a page that finds no space on write is a SIGBUS, not an exception.
            // A free-space check, not a reservation; posix_fallocate (android.system.Os) would reserve it.
            val buf = dir?.takeIf { it.usableSpace > 2 * bytes + (64L shl 20) }?.let {
                runCatching {
                    val file = File.createTempFile("members", ".f32", dir)
                    try { RandomAccessFile(file, "rw").use { it.channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes) } } finally { file.delete() }
                }.getOrNull()
            } ?: ByteBuffer.allocateDirect(bytes.toInt())
            return Members(count, leadCount, cellCount, buf.order(ByteOrder.nativeOrder()))
        }

        fun of(arrays: Array<Array<FloatArray>>) = alloc(arrays.size, arrays[0].size, arrays[0][0].size).also { members -> arrays.forEachIndexed(members::put) }
    }
}

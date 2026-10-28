package nowcast.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p

class ModelException(msg: String) : Exception(msg)

/** mm/h to and from the model's scale: log1p, so 100 mm/h is 1. */
object Transform {
    private val LOG_SCALE = ln(101.0)
    fun toModel(x: Double): Float = if (x.isNaN()) 0f else (ln1p(maxOf(x, 0.0)) / LOG_SCALE).toFloat()
    fun fromModel(y: Float): Float = expm1(y.toDouble().coerceIn(0.0, 1.0) * LOG_SCALE).toFloat()
}

/** SmaAt-UNet, 12 in -> 12 out. At most 4 threads, no memory arena. */
class SmaatModel(modelBytes: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
        setCPUArenaAllocator(false)
    })

    fun forecast(train: List<Grid>, nLead: Int): Array<FloatArray> {
        val frames = train.takeLast(12)
        val h = frames[0].h; val w = frames[0].w
        val ph = (16 - h % 16) % 16; val pw = (16 - w % 16) % 16
        val H = h + ph; val W = w + pw
        val buf = FloatBuffer.allocate(12 * H * W)
        for (f in frames) for (r in 0 until H) for (c in 0 until W)
            buf.put(if (r < h && c < w) Transform.toModel(f[r, c]) else 0f)
        buf.rewind()
        val y = OnnxTensor.createTensor(env, buf, longArrayOf(1, 12, H.toLong(), W.toLong())).use { x ->
            session.run(mapOf("x" to x)).use { res ->
                (res[0].value as Array<Array<Array<FloatArray>>>)[0]
            }
        }
        val out = Array(nLead) { k -> FloatArray(h * w) { Transform.fromModel(y[k][it / w][it % w]) } }
        if (out.any { f -> f.any { !it.isFinite() } }) throw ModelException("model produced non-finite rain rates")
        val mx = out.maxOf { f -> f.max() }
        if (mx > 500f) throw ModelException("model produced ${mx.toInt()} mm/h - refusing to serve it")
        return out
    }

    override fun close() = session.close()
}

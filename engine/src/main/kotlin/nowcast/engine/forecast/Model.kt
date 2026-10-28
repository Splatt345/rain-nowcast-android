package nowcast.engine.forecast

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import nowcast.engine.math.*
import java.nio.FloatBuffer
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.ln1p

class ModelException(msg: String) : Exception(msg)

/** mm/h to and from the model's scale: log1p, so 100 mm/h is 1. */
object Transform {
    private val LOG_SCALE = ln(101.0)
    fun toModel(rate: Double): Float = if (rate.isNaN()) 0f else (ln1p(maxOf(rate, 0.0)) / LOG_SCALE).toFloat()
    fun fromModel(scaled: Float): Float = expm1(scaled.toDouble().coerceIn(0.0, 1.0) * LOG_SCALE).toFloat()
}

/** SmaAt-UNet, 12 in -> 12 out. At most 4 threads, no memory arena. */
class SmaatModel(modelBytes: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
        setCPUArenaAllocator(false)
    })

    fun forecast(train: List<Grid>, leads: Int): Array<FloatArray> {
        val frames = train.takeLast(12)
        val rows = frames[0].rows; val cols = frames[0].cols
        val padRows = (16 - rows % 16) % 16; val padCols = (16 - cols % 16) % 16
        val paddedRows = rows + padRows; val paddedCols = cols + padCols
        val buf = FloatBuffer.allocate(12 * paddedRows * paddedCols)
        for (frame in frames) for (row in 0 until paddedRows) for (col in 0 until paddedCols)
            buf.put(if (row < rows && col < cols) Transform.toModel(frame[row, col]) else 0f)
        buf.rewind()
        val output = OnnxTensor.createTensor(env, buf, longArrayOf(1, 12, paddedRows.toLong(), paddedCols.toLong())).use { input ->
            session.run(mapOf("x" to input)).use { result ->
                (result[0].value as Array<Array<Array<FloatArray>>>)[0]
            }
        }
        val out = Array(leads) { lead -> FloatArray(rows * cols) { Transform.fromModel(output[lead][it / cols][it % cols]) } }
        if (out.any { frame -> frame.any { !it.isFinite() } }) throw ModelException("model produced non-finite rain rates")
        val peak = out.maxOf { frame -> frame.max() }
        if (peak > 500f) throw ModelException("model produced ${peak.toInt()} mm/h - refusing to serve it")
        return out
    }

    override fun close() = session.close()
}

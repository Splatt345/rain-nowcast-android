package nowcast.engine

import kotlinx.serialization.json.*
import nowcast.engine.math.*

object Fixture : FixtureFile("/lite_fixture.json")
object StepsFixture : FixtureFile("/stepsport_fixture.json")

open class FixtureFile(private val path: String) {
    val js: JsonObject by lazy {
        Json.parseToJsonElement(FixtureFile::class.java.getResource(path)!!.readText()).jsonObject
    }
    val h get() = js["h"]!!.jsonPrimitive.int
    val w get() = js["w"]!!.jsonPrimitive.int
    fun arr(key: String): DoubleArray = js[key]!!.jsonArray
        .map { if (it is JsonNull) Double.NaN else it.jsonPrimitive.double }.toDoubleArray()
    fun num(key: String) = js[key]!!.jsonPrimitive.double
    fun frames(): List<Grid> {
        val a = arr("train"); val n = h * w
        return (0 until a.size / n).map { Grid(h, w, a.copyOfRange(it * n, (it + 1) * n)) }
    }
}

fun assertClose(want: DoubleArray, got: DoubleArray, tol: Double, what: String) {
    require(want.size == got.size) { "$what: size ${want.size} vs ${got.size}" }
    for (i in want.indices) {
        val a = want[i]; val b = got[i]
        if (a.isNaN() || b.isNaN()) {
            require(a.isNaN() && b.isNaN()) { "$what[$i]: NaN mismatch $a vs $b" }
        } else require(kotlin.math.abs(a - b) <= tol * (1 + kotlin.math.abs(a))) {
            "$what[$i]: want $a got $b"
        }
    }
}

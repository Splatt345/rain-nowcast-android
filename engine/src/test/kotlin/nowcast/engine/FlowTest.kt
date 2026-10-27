package nowcast.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTest {
    @Test fun rngMatchesPython() {
        assertClose(Fixture.arr("uniforms_seed5"), Rng.uniforms(5, 8), 1e-15, "uniforms")
        assertClose(Fixture.arr("normals_seed5"), Rng.normals(5, 8), 1e-12, "normals")
    }

    @Test fun blurMatchesPython() {
        assertClose(Fixture.arr("blur_r2"), blur(Fixture.frames()[0], 2).v, 1e-12, "blur")
    }

    @Test fun samplingAgreesWithHandValues() {
        val f = Grid(5, 6, DoubleArray(30) { it.toDouble() })
        assertEquals(11.5, sampleClamped(f, 1.5, 2.5), 1e-12)
        assertTrue(sampleOrNaN(f, -0.1, 0.0).isNaN())
        assertEquals(29.0, sampleClamped(f, 9.0, 9.0), 1e-12)
    }

    @Test fun motionMatchesPython() {
        val db = Fixture.frames().takeLast(3).map { g -> g.map(::toDb) }
        val m = motion(db)
        assertClose(Fixture.arr("vx"), m.vx.v, 1e-6, "vx")
        assertClose(Fixture.arr("vy"), m.vy.v, 1e-6, "vy")
    }
}

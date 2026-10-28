package nowcast.engine.outlook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nowcast.engine.forecast.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

class OutlookTest {
    private val assets = File(System.getProperty("user.dir"), "../app/src/main/assets/outlook")
    private fun t(s: String) = Instant.parse("2026-10-27T${s}:00Z")
    private val now = t("12:10")

    private val det = Hourly((11..16).map { t("%02d:00".format(it)) }, mapOf(
        "icon_d2" to doubleArrayOf(0.0, 0.0, 0.2, 1.0, 0.4, 0.0),
        "icon_eu" to doubleArrayOf(0.0, 0.1, 0.3, 0.6, 0.2, 0.0),
        "ecmwf_ifs025" to doubleArrayOf(0.0, 0.0, 0.5, 0.2, 0.1, 0.1)))

    @Test fun featureRowMatchesHandComputed() {
        val r = OutlookFeatures.row(det, 4, 50.85, 4.35, now)!!   // hour ending 15:00 UTC
        val want = floatArrayOf(0.4f, 0.2f, 0.1f, 1.0f, 0.6f, 0.2f, 0.0f, 0.0f, 0.1f,
            0.23333333f, 0.4f,                       // mean, max of (0.4, 0.2, 0.1)
            -0.70710677f, -0.70710677f,              // 15 h UTC: sin, cos(15/24 * 2pi)
            -0.9084917f, 0.4179029f,                 // day 299 (0-based) / 365.25 * 2pi, from numpy
            50.85f, 4.35f, 0f)                       // lead 2.8 h -> bucket 0
        assertArrayEquals(want, r, 1e-6f)
        assertNull(OutlookFeatures.row(det, 0, 50.85, 4.35, now))           // no previous hour
        assertNull(OutlookFeatures.row(det, 5, 50.85, 4.35, now))           // no next hour
        assertEquals(1f, OutlookFeatures.row(det, 4, 50.85, 4.35, t("02:00"))!![17])   // lead 13 h
    }

    @Test fun featureNamesAreTheExportedContract() {
        val j = Json.parseToJsonElement(File(assets, "outlook_gbm.json").readText()).jsonObject
        val meta = OutlookMeta.parse(File(assets, "outlook_gbm.json").readText())
        assertEquals(j["features"]!!.jsonArray.map { it.jsonPrimitive.content }, OutlookFeatures.names(meta.models))
    }

    @Test fun quantilesBecomeTwoHalfHourBars() {
        val b = OutlookBars.fromQuantiles(t("15:00"), doubleArrayOf(0.0, 0.0, 0.1, 0.2, 0.3, 0.4, 0.6, 1.0, 2.0))
        assertEquals(listOf(t("14:00"), t("14:30")), b.map { it.start })
        assertEquals(2.3 / 9, b[0].mm, 1e-9); assertEquals(0.0, b[0].p10, 1e-12); assertEquals(1.0, b[0].p90, 1e-12)
        assertEquals(5.0 / 9, b[1].probability, 1e-12)                      // halves > 0.1 mm
        assertEquals(BarSource.MODEL, b[0].source)
    }

    @Test fun membersBecomeBarsWithNumpyPercentiles() {
        val b = OutlookBars.fromMembers(t("15:00"), DoubleArray(20) { it * 0.1 } + Double.NaN)
        assertEquals(0.475, b[0].mm, 1e-9)
        assertEquals(0.095, b[0].p10, 1e-9); assertEquals(0.855, b[0].p90, 1e-9)
        assertEquals(0.85, b[0].probability, 1e-12); assertEquals(BarSource.ENSEMBLE, b[0].source)
    }

    private val q = DoubleArray(9) { 0.1 * it }

    @Test fun assembleUsesTheModelWhereRowsAreComplete() {
        val o = OutlookBars.assemble(det, null, { x -> x.map { q } }, 50.85, 4.35, now)
        assertTrue(o.usedModel); assertNull(o.note)
        assertEquals((12..14).flatMap { listOf(t("$it:00"), t("$it:30")) }, o.bars.map { it.start })
        assertTrue(o.bars.all { it.source == BarSource.MODEL })
    }

    @Test fun assembleFallsBackToTheEnsembleWhenTheModelFails() {
        val ens = Hourly(det.time, (0 until 20).associate { m -> "m$m" to DoubleArray(6) { m * 0.05 } })
        val o = OutlookBars.assemble(det, ens, { throw IllegalStateException("boom") }, 50.85, 4.35, now)
        assertFalse(o.usedModel); assertNotNull(o.note)
        assertEquals(8, o.bars.size)                                          // hours ending 13..16
        assertTrue(o.bars.all { it.source == BarSource.ENSEMBLE })
        try { OutlookBars.assemble(null, null, null, 50.85, 4.35, now); fail() } catch (_: OutlookException) {}
    }

    @Test fun joinSwitchesFromTheNowcastAtOneHour() {
        val issued = t("12:05")
        val mins = IntArray(116) { it - 55 }
        val p = PointForecast(mins, DoubleArray(116) { 1.2 }, DoubleArray(116) { 0.7 }, DoubleArray(116),
            DoubleArray(116) { 0.6 }, DoubleArray(116), DoubleArray(116), DoubleArray(116) { 2.4 },
            DoubleArray(116), 0.7, "x", true)
        val o = OutlookBars.assemble(det, null, { x -> x.map { q } }, 50.85, 4.35, now)
        val bars = OutlookBars.join(o, p, issued, now)
        assertEquals(OutlookBars.BAR_COUNT, bars.size)
        assertEquals(t("12:00"), bars[0]!!.start)
        assertEquals(BarSource.NOWCAST, bars[0]!!.source); assertEquals(BarSource.NOWCAST, bars[1]!!.source)
        assertEquals(0.6, bars[0]!!.mm, 1e-9); assertEquals(0.7, bars[1]!!.probability, 1e-12)
        assertEquals(0.3, bars[0]!!.p10, 1e-9); assertEquals(1.2, bars[0]!!.p90, 1e-9)
        assertEquals(BarSource.MODEL, bars[2]!!.source)                     // 13:00-13:30 ends after 13:05
        assertNull(bars[6])                                                   // past the outlook's data: a gap
        assertEquals(BarSource.MODEL, OutlookBars.join(o, null, null, now)[0]!!.source)
    }

    @Test fun labelAndSentence() {
        val b = Bar(t("13:00"), 1.0, 0.4, 1.8, 0.6, BarSource.MODEL)
        assertEquals("Tue 13:00–13:30 · 60% · 0.4–1.8 mm", OutlookBars.label(b, ZoneOffset.UTC))
        val dry = List(96) { Bar(t("12:00").plusSeconds(1800L * it), 0.0, 0.0, 0.0, 0.0, BarSource.MODEL) }
        assertEquals("Dry for the next 48 hours", OutlookBars.sentence(dry, ZoneOffset.UTC))
        assertEquals("Dry for the next hour", OutlookBars.sentence(dry.take(2), ZoneOffset.UTC))
        val wet = dry.mapIndexed { i, x -> if (i == 6) Bar(x.start, 1.0, 0.2, 2.0, 0.7, BarSource.MODEL) else x }
        assertEquals("Rain likely from Tue 15:00 (70%), about 1.0 mm over the following 6 h",
            OutlookBars.sentence(wet, ZoneOffset.UTC))
        assertEquals("Outlook unavailable", OutlookBars.sentence(List(96) { null }, ZoneOffset.UTC))
    }

    @Test fun gbmRunsOnARealRow() {
        OutlookModel(File(assets, "outlook_gbm.onnx").readBytes()).use { m ->
            val out = m.quantiles(listOf(OutlookFeatures.row(det, 4, 50.85, 4.35, now)!!))
            assertEquals(1, out.size); assertEquals(9, out[0].size)
            assertTrue(out[0].all { it >= 0 }); assertEquals(out[0].sorted(), out[0].toList())
        }
    }

    @Test fun parsesOpenMeteo() {
        val h = OpenMeteo.parse("""{"hourly":{"time":["2026-10-27T13:00","2026-10-27T14:00"],
            "precipitation_icon_d2":[0.1,null],"precipitation_member01":[0.0,0.2]}}""")
        assertEquals(listOf(t("13:00"), t("14:00")), h.time)
        assertEquals(0.1, h.series.getValue("icon_d2")[0], 0.0); assertTrue(h.series.getValue("icon_d2")[1].isNaN())
        assertEquals(0.2, h.series.getValue("member01")[1], 0.0)
    }

    @Test fun modelOnlyWhereItWasTrained() {
        val be = OutlookMeta.parse(File(assets, "outlook_gbm.json").readText())
        assertTrue(be.trainedAt(50.85, 4.35))       // Brussels
        assertFalse(be.trainedAt(52.37, 4.90))      // Amsterdam: north of every training point
        assertFalse(be.trainedAt(51.0, 7.0))        // Cologne side
    }

    @Test fun anotherRegionsModelBringsItsOwnModelsAndArea() {
        val m = OutlookMeta.parse("""{"models":["meteofrance_arome_france","icon_eu","ecmwf_ifs025"],
            "ensemble":"icon_eu","trained_bbox":[-6.2,38.5,-1.2,42.3],"features":[]}""")
        assertTrue(m.trainedAt(40.4, -3.7)); assertFalse(m.trainedAt(50.85, 4.35))
        assertEquals("the ICON-EU ensemble", m.ensembleName)
        assertEquals(listOf("AROME", "ICON-EU", "IFS"), m.modelNames)
        val h = Hourly(det.time, mapOf("meteofrance_arome_france" to det.series.getValue("icon_d2"),
            "icon_eu" to det.series.getValue("icon_eu"), "ecmwf_ifs025" to det.series.getValue("ecmwf_ifs025")))
        assertArrayEquals(OutlookFeatures.row(det, 4, 50.85, 4.35, now), OutlookFeatures.row(h, 4, 50.85, 4.35, now, m.models), 0f)
        assertNull(OutlookFeatures.row(det, 4, 50.85, 4.35, now, m.models))     // AROME missing from the fetch
    }
}

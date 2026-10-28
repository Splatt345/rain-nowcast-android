package nowcast.app.ui.settings

import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GuideTest {
    private val asset = File(System.getProperty("user.dir"), "src/main/assets/$GUIDE")

    @Test fun parsesEveryKindOfBlock() {
        val b = Markdown.parse("# Title\n\nOne\nparagraph.\n\n- a\n  more\n- b\n\n```bash\nrun it\n```\n\n| k | v |\n|---|---|\n| x | `1` |\n")
        assertEquals(listOf(Block.Heading(1, "Title"), Block.Para("One paragraph."), Block.Bullets(listOf("a more", "b")),
            Block.Code("run it"), Block.Table(listOf("k", "v"), listOf(listOf("x", "`1`")))), b)
    }

    @Test fun inlineMarkupIsStripped() {
        val s = Markdown.inline("**bold** `code` [web](https://x.org) [file](docs/a.md)", SpanStyle(), SpanStyle())
        assertEquals("bold code web file", s.text)
    }

    @Test fun theBundledGuideParses() {
        val b = Markdown.parse(asset.readText())
        assertEquals(Block.Heading(1, "Your own region"), b.first())
        assertTrue(b.filterIsInstance<Block.Table>().any { t -> t.header.first() == "part" })     // the model contract
        assertEquals(listOf("harvest.py", "train.py", "export.py"),
            b.filterIsInstance<Block.Code>().mapNotNull { Regex("^# (\\w+\\.py)").find(it.text)?.groupValues?.get(1) })
    }

    /** The reader has the app and nothing else: no repository files, folders or commands from it. */
    @Test fun needsNothingButTheApp() {
        val text = asset.readText()
        for (s in listOf("nowcast.", "nowcast/", ".venv", "HANDOVER", "PREREGISTRATION", "NOWCAST_REGION", "regions/",
                "selfcheck", "outlook_export", "data/regions", "rain-nowcast-android", "local_weather_model"))
            assertFalse(s, s in text)
    }
}

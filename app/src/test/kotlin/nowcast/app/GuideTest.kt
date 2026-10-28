package nowcast.app

import androidx.compose.ui.text.SpanStyle
import nowcast.app.ui.Block
import nowcast.app.ui.GUIDE
import nowcast.app.ui.Markdown
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
        assertEquals(Block.Heading(1, "Train the models for your own region"), b.first())
        assertTrue(b.filterIsInstance<Block.Table>().any { t -> t.rows.any { it.first() == "Japan" } })
    }

    /** The guide lives in local_weather_model; this copy must follow it. */
    @Test fun matchesTheResearchRepo() {
        val src = File(System.getProperty("user.home"), "Projects/local_weather_model/docs/TRAIN_YOUR_REGION.md")
        assumeTrue("research repo not checked out", src.exists())
        assertEquals("cp ${src.path} app/src/main/assets/$GUIDE", src.readText(), asset.readText())
    }
}

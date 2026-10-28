package nowcast.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** The Markdown this app ships: headings, paragraphs, "- " lists, fenced code and pipe tables. */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Para(val text: String) : Block
    data class Bullets(val items: List<String>) : Block
    data class Code(val text: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
}

object Markdown {
    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.isBlank() -> i++
                l.startsWith("```") -> {
                    val end = (i + 1 until lines.size).firstOrNull { lines[it].startsWith("```") } ?: lines.size
                    out += Block.Code(lines.subList(i + 1, end).joinToString("\n")); i = end + 1
                }
                l.startsWith("#") -> {
                    val level = l.takeWhile { it == '#' }.length
                    out += Block.Heading(level, l.drop(level).trim()); i++
                }
                l.startsWith("|") -> {
                    val rows = lines.drop(i).takeWhile { it.startsWith("|") }
                    out += Block.Table(cells(rows[0]), rows.drop(2).map(::cells)); i += rows.size
                }
                l.startsWith("- ") -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && (lines[i].startsWith("- ") || lines[i].startsWith("  ") && items.isNotEmpty())) {
                        if (lines[i].startsWith("- ")) items += lines[i].drop(2).trim() else items[items.lastIndex] += " " + lines[i].trim()
                        i++
                    }
                    out += Block.Bullets(items)
                }
                else -> {
                    val para = lines.drop(i).takeWhile { it.isNotBlank() && !it.startsWith("```") && !it.startsWith("#") &&
                        !it.startsWith("|") && !it.startsWith("- ") }
                    out += Block.Para(para.joinToString(" ") { it.trim() }); i += para.size
                }
            }
        }
        return out
    }

    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)""")

    /** **bold**, `code` and [links](https://…); a relative link is shown as plain text. */
    fun inline(s: String, code: SpanStyle, link: SpanStyle): AnnotatedString = buildAnnotatedString {
        var at = 0
        for (m in INLINE.findAll(s)) {
            append(s.substring(at, m.range.first))
            val (bold, mono, label, url) = m.destructured
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(bold, code, link)) }
                mono.isNotEmpty() -> withStyle(code) { append(mono) }
                url.startsWith("http") -> withLink(LinkAnnotation.Url(url, TextLinkStyles(link))) { append(label) }
                else -> append(label)
            }
            at = m.range.last + 1
        }
        append(s.substring(at))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val blocks = remember { Markdown.parse(ctx.assets.open(GUIDE).bufferedReader().readText()) }
    val cs = MaterialTheme.colorScheme
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = cs.surfaceContainerHighest)
    val link = SpanStyle(color = cs.primary, textDecoration = TextDecoration.Underline)
    fun t(s: String) = Markdown.inline(s, code, link)

    Scaffold(modifier, containerColor = cs.surface, contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar({ Text("Your own region") }, windowInsets = WindowInsets(0),
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } })
        }) { pad ->
        SelectionContainer {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(blocks.drop(1)) { b ->      // the first heading is the top bar
                    when (b) {
                        is Block.Heading -> Text(t(b.text), Modifier.padding(top = if (b.level <= 2) 14.dp else 6.dp).semantics { heading() },
                            style = if (b.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                        is Block.Para -> Text(t(b.text), style = MaterialTheme.typography.bodyMedium)
                        is Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            b.items.forEach { Row { Text("•  "); Text(t(it), style = MaterialTheme.typography.bodyMedium) } }
                        }
                        is Block.Code -> Text(b.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(cs.surfaceContainerHighest).horizontalScroll(rememberScrollState()).padding(12.dp),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                        // wide tables do not fit a phone: one card per row, "column: value"
                        is Block.Table -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            b.rows.forEach { row ->
                                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(t(row.first()), style = MaterialTheme.typography.titleSmall)
                                        b.header.drop(1).zip(row.drop(1)).filter { it.second.isNotEmpty() }.forEach { (h, v) ->
                                            Text(buildAnnotatedString {
                                                withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append("$h: ") }
                                                append(t(v))
                                            }, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

const val GUIDE = "train_your_region.md"

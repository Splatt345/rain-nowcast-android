package nowcast.app.ui.settings

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
            val line = lines[i]
            when {
                line.isBlank() -> i++
                line.startsWith("```") -> {
                    val end = (i + 1 until lines.size).firstOrNull { lines[it].startsWith("```") } ?: lines.size
                    out += Block.Code(lines.subList(i + 1, end).joinToString("\n")); i = end + 1
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    out += Block.Heading(level, line.drop(level).trim()); i++
                }
                line.startsWith("|") -> {
                    val rows = lines.drop(i).takeWhile { it.startsWith("|") }
                    out += Block.Table(cells(rows[0]), rows.drop(2).map(::cells)); i += rows.size
                }
                line.startsWith("- ") -> {
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
    fun inline(text: String, code: SpanStyle, link: SpanStyle): AnnotatedString = buildAnnotatedString {
        var at = 0
        for (match in INLINE.findAll(text)) {
            append(text.substring(at, match.range.first))
            val (bold, mono, label, url) = match.destructured
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(bold, code, link)) }
                mono.isNotEmpty() -> withStyle(code) { append(mono) }
                url.startsWith("http") -> withLink(LinkAnnotation.Url(url, TextLinkStyles(link))) { append(label) }
                else -> append(label)
            }
            at = match.range.last + 1
        }
        append(text.substring(at))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val blocks = remember { Markdown.parse(ctx.assets.open(GUIDE).bufferedReader().readText()) }
    val scheme = MaterialTheme.colorScheme
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = scheme.surfaceContainerHighest)
    val link = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline)
    fun styled(markdown: String) = Markdown.inline(markdown, code, link)

    Scaffold(modifier, containerColor = scheme.surface, contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar({ Text("Your own region") }, windowInsets = WindowInsets(0),
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } })
        }) { innerPadding ->
        SelectionContainer {
            LazyColumn(Modifier.padding(innerPadding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(blocks.drop(1)) { block ->      // the first heading is the top bar
                    when (block) {
                        is Block.Heading -> Text(styled(block.text), Modifier.padding(top = if (block.level <= 2) 14.dp else 6.dp).semantics { heading() },
                            style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                        is Block.Para -> Text(styled(block.text), style = MaterialTheme.typography.bodyMedium)
                        is Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            block.items.forEach { Row { Text("•  "); Text(styled(it), style = MaterialTheme.typography.bodyMedium) } }
                        }
                        is Block.Code -> Text(block.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainerHighest).horizontalScroll(rememberScrollState()).padding(12.dp),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
                        // wide tables do not fit a phone: one card per row, "column: value"
                        is Block.Table -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            block.rows.forEach { row ->
                                Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(styled(row.first()), style = MaterialTheme.typography.titleSmall)
                                        block.header.drop(1).zip(row.drop(1)).filter { it.second.isNotEmpty() }.forEach { (column, value) ->
                                            Text(buildAnnotatedString {
                                                withStyle(SpanStyle(color = scheme.onSurfaceVariant)) { append("$column: ") }
                                                append(styled(value))
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

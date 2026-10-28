package nowcast.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nowcast.app.NowView
import nowcast.app.Problem
import nowcast.app.UiState
import nowcast.engine.OutlookBars
import nowcast.engine.RAIN_THRESHOLD
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
const val STALE_MIN = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowScreen(state: UiState, now: Instant, view: NowView, onView: (NowView) -> Unit, onRefresh: () -> Unit,
              onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    PullToRefreshBox(isRefreshing = state.loading && state.forecast != null, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp).align(Alignment.TopCenter)
                .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val fc = state.forecast
            val p = state.point
            val age = fc?.let { Duration.between(it.issued, now).toMinutes().toInt() }
            Header(fc?.region?.name ?: state.regionName, age, state.loading)

            when {
                state.problem == Problem.NoKey -> ProblemCard(Icons.Outlined.Key, "Radar key needed",
                    "Add your MeteoGate ORD API key in Settings to load the radar.", "Open settings", onOpenSettings)
                fc == null && state.problem != null -> ProblemCard(Icons.Outlined.CloudOff, "No forecast yet",
                    problemText(state.problem), "Try again", onRefresh)
                fc == null || p == null -> Skeleton()
                else -> {
                    val stale = (age ?: 0) > STALE_MIN
                    Text(if (stale) "Radar from ${HM.format(fc.issued.atZone(zone))}: ${p.summary.replaceFirstChar { it.lowercase() }}" else p.summary,
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Stats(p, age ?: 0)
                    if (state.problem != null) Banner(Icons.Outlined.CloudOff,
                        "${problemText(state.problem)} — showing radar from ${HM.format(fc.issued.atZone(zone))}", warn = true)
                    else if (stale) Banner(Icons.Outlined.Schedule, "Radar is $age min old; the next refresh will catch up", warn = true)
                    if (!p.inCoverage) Banner(Icons.Outlined.WrongLocation, "Home is outside the radar crop; showing its nearest edge", warn = true)
                }
            }

            if (fc != null || state.outlook != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    NowView.entries.forEachIndexed { i, v ->
                        SegmentedButton(selected = view == v, onClick = { onView(v) },
                            shape = SegmentedButtonDefaults.itemShape(i, NowView.entries.size),
                            icon = { SegmentedButtonDefaults.Icon(view == v) },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (v == NowView.NOWCAST) "Nowcast" else "Next 48 h")
                        }
                    }
                }
                AnimatedContent(view, label = "view", transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(220, 60)) + slideInHorizontally(tween(260)) { it / 6 * dir }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { -it / 6 * dir }) using SizeTransform(clip = false)
                }) { v ->
                    if (v == NowView.NOWCAST) NowcastCard(state, now)
                    else OutlookCard(state, now, zone)
                }
            }
            state.modelNote?.let { Banner(Icons.Outlined.Info, it, warn = false) }
        }
    }
}

@Composable
private fun Header(region: String?, age: Int?, loading: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("RAIN NOWCAST", style = Overline, color = MaterialTheme.colorScheme.primary)
            Text(region ?: "—", style = MaterialTheme.typography.titleLarge)
        }
        if (age != null) {
            val stale = age > STALE_MIN
            val bg = if (stale) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
            val fg = if (stale) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
            Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = fg)
                    else Icon(Icons.Outlined.Radar, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (age <= 0) "radar now" else "radar $age min old", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun Stats(p: nowcast.engine.PointForecast, age: Int) {
    val i = p.minutes.indexOfFirst { it >= age }.coerceAtLeast(0)
    val rate = p.values[i]
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // counted from now, like the summary sentence (rainChance starts at the radar frame)
        val chance = p.probability.copyOfRange(i, p.probability.size).max()
        Tile("Chance", "${(100 * chance).roundToInt()}", "%", "next hour", Modifier.weight(1.2f), highlight = true)
        Tile("Now", if (rate > 0.0 && rate < RAIN_THRESHOLD) "<0.1" else fmt(rate), "mm/h", if (rate >= RAIN_THRESHOLD) "raining" else "dry", Modifier.weight(1f))
        Tile("Peak", fmt(p.values.copyOfRange(i, p.values.size).max()), "mm/h", "in the hour", Modifier.weight(1f))
    }
}

@Composable
private fun Tile(title: String, value: String, unit: String, sub: String, modifier: Modifier, highlight: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier.fillMaxHeight(), shape = RoundedCornerShape(20.dp),
        color = if (highlight) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (highlight) cs.onPrimaryContainer else cs.onSurface) {
        Column(Modifier.padding(14.dp)) {
            Text(title.uppercase(), style = Overline, color = LocalContentColor.current.copy(alpha = 0.75f))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = if (highlight) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun NowcastCard(state: UiState, now: Instant) {
    val p = state.point; val fc = state.forecast
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(start = 8.dp, end = 12.dp, top = 16.dp, bottom = 14.dp)) {
            if (p != null && fc != null) NowcastGraph(p, fc.issued, now)
            else Box(Modifier.fillMaxWidth().height(240.dp)) { Shimmer(Modifier.matchParentSize()) }
        }
    }
}

@Composable
private fun OutlookCard(state: UiState, now: Instant, zone: ZoneId) {
    val bars = remember(state.outlook, state.point, now.epochSecond / 60) {
        OutlookBars.join(state.outlook, state.point, state.forecast?.issued, now)
    }
    var sel by rememberSaveable(state.outlook) { mutableStateOf<Int?>(null) }
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val sentence = OutlookBars.sentence(bars, zone)
            if (bars.all { it == null }) {
                val o = state.outlook
                Text(if (state.outlookError != null) "Outlook unavailable" + (o?.let { ", last fetched ${HM.format(it.fetched.atZone(zone))}" } ?: "")
                    else "Loading the outlook…", style = MaterialTheme.typography.titleMedium)
                if (state.outlookError == null) Shimmer(Modifier.fillMaxWidth().height(190.dp))
                return@Column
            }
            Text(sentence, style = MaterialTheme.typography.titleMedium)
            val label = sel?.let { bars.getOrNull(it) }?.let { OutlookBars.label(it, zone) }
            AnimatedContent(label, label = "barLabel", transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(90)) }) { l ->
                Text(l ?: "Tap a bar for its half hour", style = MaterialTheme.typography.labelLarge,
                    color = if (l == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlookChart(bars, zone, sel, { sel = it }, description = sentence)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ProbScale()
                Box(Modifier.size(14.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.tertiary))
                Text("radar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("line = 80% range", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val o = state.outlook
            val src = when {
                o == null -> ""
                o.usedModel -> "First hour from radar, then the local model on " +
                    o.meta.modelNames.let { n -> if (n.size > 1) n.dropLast(1).joinToString(", ") + " and " + n.last() else n.joinToString() }
                else -> "First hour from radar, then ${o.meta.ensembleName}"
            }
            if (src.isNotEmpty()) Text(src + " · fetched ${HM.format(o!!.fetched.atZone(zone))}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            o?.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ProbScale() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("0%", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Box(Modifier.padding(horizontal = 4.dp).size(44.dp, 8.dp).clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.lerp(cs.surfaceVariant, cs.primary, 0.15f), cs.primary))))
        Text("100%", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@Composable
fun Banner(icon: ImageVector, text: String, warn: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(color = if (warn) cs.tertiaryContainer else cs.surfaceContainerHigh,
        contentColor = if (warn) cs.onTertiaryContainer else cs.onSurfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProblemCard(icon: ImageVector, title: String, body: String, action: String, onAction: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onAction, Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

fun problemText(p: Problem?): String = when (p) {
    is Problem.Network -> "No network"
    is Problem.Radar -> p.msg
    Problem.NoKey -> "No radar key"
    null -> ""
}

@Composable
private fun Skeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Shimmer(Modifier.fillMaxWidth(0.9f).height(28.dp))
        Shimmer(Modifier.fillMaxWidth(0.6f).height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { Shimmer(Modifier.weight(1f).height(96.dp)) }
        }
        Text("Fetching the last hour of radar and running the ensemble — about half a minute…", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Box(modifier.clip(RoundedCornerShape(14.dp)).graphicsLayer { alpha = a }
        .background(MaterialTheme.colorScheme.surfaceContainerHighest))
}


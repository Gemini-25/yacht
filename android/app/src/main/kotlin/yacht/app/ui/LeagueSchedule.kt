package yacht.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.ClockView
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.WeekRow
import yacht.engine.league.Article
import yacht.engine.league.Comp
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.WeekStage

// ---- names -------------------------------------------------------------------------------------

/** A competition's short name for tight lines (챔스, 클럽, ...). */
fun compShort(c: Comp): String = when (c) {
    Comp.CHAMPIONS -> "챔스"
    Comp.CLUB -> "클럽"
    Comp.FAMILY -> "계열전"
    Comp.WORLD_CUP -> "월드컵"
    Comp.TITLE_PLAYOFF -> "우승 PO"
    Comp.PROMOTION_PLAYOFF -> "승강 PO"
    else -> c.label
}

/**
 * "🏆 요트컵 64강", "📋 리그 12라운드", "🥇 슈퍼컵" ([short]: 챔스, 클럽, ..., and a double
 * elimination stage as "4강 · 패자 1R" through [shortStage]).
 */
fun stageName(comp: Comp, label: String, short: Boolean = false): String {
    val name = if (short) compShort(comp) else comp.label
    // The super cup and the all-star game are one match: their label is their name.
    val rest = if (label == comp.label || label.isBlank() || (comp == Comp.PROMOTION_PLAYOFF && label == "승강전")) "" else " " + if (short) shortStage(label) else label
    return compIcon(comp) + " " + name + rest
}

fun stageName(s: WeekStage, short: Boolean = false): String = stageName(s.comp, s.label, short)

/** "12주차", "포스트시즌 1주차", "개막 전", "시즌 마감"; [leagueWeeks] null when not known (another season). */
fun weekName(w: Int, leagueWeeks: Int?): String = when {
    w == SeasonCalendar.PRESEASON -> "개막 전"
    w >= SeasonCalendar.CLOSING -> "시즌 마감"
    w < 0 -> ""
    leagueWeeks != null && w > leagueWeeks -> "포스트시즌 ${w - leagueWeeks}주차"
    else -> "${w}주차"
}

/**
 * The week of something from [season] ([comp] when known): named with this season's length when
 * it is the clock's season; otherwise "N주차", or "포스트시즌" for the playoffs (that season's
 * length is not known here).
 */
fun weekLabel(week: Int, season: Int, clock: ClockView?, comp: Comp? = null): String = when {
    week < 0 -> ""
    clock != null && season == clock.season -> weekName(week, clock.leagueWeeks)
    week == SeasonCalendar.PRESEASON || week >= SeasonCalendar.CLOSING -> weekName(week, null)
    comp == Comp.TITLE_PLAYOFF || comp == Comp.PROMOTION_PLAYOFF -> "포스트시즌"
    else -> "${week}주차"
}

/** The clock's title: "12주차 / 49주", "포스트시즌 1주차", or that the season is closing. */
fun clockTitle(c: ClockView): String = when {
    c.over -> "시즌을 마감하고 있어요"
    c.week <= c.leagueWeeks -> "${c.week}주차 / ${c.leagueWeeks}주"
    else -> weekName(c.week, c.leagueWeeks)
}

/** One line on what this week holds besides the league, or what comes next. */
fun clockLine(c: ClockView): String {
    if (c.over) return "시상식과 승강, 이적 시장이 이어지고 다음 시즌이 열려요"
    val now = c.row(c.week)?.stages.orEmpty().filter { it.comp != Comp.LEAGUE }
    if (now.isNotEmpty()) return "이번 주: " + now.joinToString(" · ") { stageName(it, short = true) }
    val next = c.upcoming(c.week + 1).firstOrNull()
    return if (next == null) "이번 주는 리그 경기만 있어요" else "이번 주는 리그 경기만 · 다음은 ${stageName(next, short = true)} (${weekName(next.week, c.leagueWeeks)})"
}

// ---- the clock in the control card ----------------------------------------------------------------

/** The season's clock under the league's controls: the week, what is on, and a bar of the weeks. */
@Composable
fun ClockLine(c: ClockView, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpen).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📅 시즌 ${c.season} · ${clockTitle(c)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("일정 ›", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
        Text(clockLine(c), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        WeekBar(c, Modifier.fillMaxWidth().height(9.dp))
    }
}

/** The weeks as a bar: played weeks filled, the week in play marked, finals as dots above. */
@Composable
fun WeekBar(c: ClockView, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val done = cs.primary
    val now = cs.tertiary
    val later = cs.outlineVariant
    val final = cs.secondary
    val finals = remember(c.weeks) { c.weeks.filter { r -> r.stages.any { it.final } }.map { it.week } }
    Canvas(modifier) {
        val n = maxOf(1, c.lastWeek)
        val gap = 1.dp.toPx()
        val w = size.width / n
        val barTop = size.height * 0.45f
        val barH = size.height - barTop
        for (i in 1..n) {
            val color = when {
                c.over || i < c.week -> done
                i == c.week -> now
                else -> later
            }
            drawRoundRect(color, Offset((i - 1) * w, barTop), Size(maxOf(1f, w - gap), barH), CornerRadius(1.5f, 1.5f))
        }
        for (f in finals) drawCircle(final, radius = minOf(w * 0.5f, barTop * 0.42f).coerceAtLeast(1.5f), center = Offset((f - 0.5f) * w, barTop * 0.42f))
    }
}

// ---- the 일정 tab ----------------------------------------------------------------------------------

private enum class ScheduleMode(val label: String) { WEEK("이번 주"), SEASON("시즌 전체") }

/**
 * 일정: the week in play (or one picked) with every stage and how far it is, the weeks behind
 * and ahead, and the whole season week by week. Tapping a stage opens its table or bracket.
 */
@Composable
fun ScheduleTab(lg: LeagueController, v: LeagueView, onLeague: () -> Unit, onComp: (Comp) -> Unit) {
    val c = v.clock
    if (c == null) {
        CenterText("리그를 시작하면 시즌 일정이 짜여요.\n리그 한 라운드가 한 주예요. 요트컵·챔피언스 리그 같은 대회는 정해진 주에 하나씩 열리고, 리그가 끝나면 포스트시즌에 플레이오프를 치러요.")
        return
    }
    var mode by remember { mutableStateOf(ScheduleMode.WEEK) }
    val open: (WeekStage) -> Unit = { s -> if (s.comp == Comp.LEAGUE) onLeague() else onComp(s.comp) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in ScheduleMode.entries) FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 12.sp) })
        }
        when (mode) {
            ScheduleMode.WEEK -> WeekView(lg, v, c, open)
            ScheduleMode.SEASON -> SeasonWeeks(c) { w ->
                lg.scheduleWeek = w
                mode = ScheduleMode.WEEK
            }
        }
    }
}

@Composable
private fun WeekView(lg: LeagueController, v: LeagueView, c: ClockView, open: (WeekStage) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val w = (lg.scheduleWeek ?: c.week).coerceIn(1, maxOf(1, c.lastWeek))
    val row = c.row(w)
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { lg.scheduleWeek = (w - 1).coerceAtLeast(1) }, enabled = w > 1) { Text("◀ 지난 주") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(weekName(w, c.leagueWeeks), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            c.over -> "끝났어요"
                            w < c.week -> if (row?.done != false) "끝났어요" else "남은 경기가 있어요"
                            w == c.week -> "진행 중"
                            w == c.week + 1 -> "다음 주"
                            else -> "${w - c.week}주 뒤"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (w == c.week && !c.over) cs.tertiary else cs.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { lg.scheduleWeek = (w + 1).coerceAtMost(c.lastWeek) }, enabled = w < c.lastWeek) { Text("다음 주 ▶") }
            }
        }
        if (lg.scheduleWeek != null && w != c.week && !c.over) {
            item { AssistChip(onClick = { lg.scheduleWeek = null }, label = { Text("지금 주로 가기 (${weekName(c.week, c.leagueWeeks)})", fontSize = 12.sp) }) }
        }
        if (row != null) item { WeekCard(c, row, v.season?.rules?.doubleElimination == true, open) }
        if (w == c.week && !c.over) {
            val late = c.late
            if (late.isNotEmpty()) item { LateCard(c, late, open) }
        }
        item { WeekNews(v.news, c, w) }
        if (w == c.week && !c.over) c.row(w + 1)?.let { next -> item { NextWeek(c, next) } }
        item { BigGames(c) }
        item {
            Text(
                "리그 한 라운드가 한 주예요. 요트컵·챔피언스 리그 같은 대회는 시즌이 시작될 때 정해진 주에 하나씩 열리고, 클럽 리그는 몇 주마다 한 라운드씩 치러요. " +
                    "생각이 오래 걸리는 AI가 있으면 경기가 다음 주로 조금 밀리기도 해요. 리그가 끝나면 포스트시즌에 플레이오프를 치르고 시즌을 마감해요.",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** A week's stages: each with how far it is; a tap opens it ([double]: the season plays 패자부활). */
@Composable
private fun WeekCard(c: ClockView, row: WeekRow, double: Boolean, open: (WeekStage) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val past = c.over || row.week < c.week
    val total = row.stages.filter { it.set }.sumOf { it.total }
    val played = row.stages.filter { it.set }.sumOf { it.played }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (row.week == c.week && !c.over) cs.secondaryContainer.copy(alpha = 0.45f) else cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    row.stages.isEmpty() -> "이 주에는 경기가 없어요."
                    past && played >= total -> "이 주에 ${total}경기를 치렀어요"
                    played == 0 && row.week > c.week -> "${total.takeIf { it > 0 }?.let { "${it}경기가 " } ?: ""}${row.week - c.week}주 뒤에 열려요"
                    else -> "이 주의 ${total}경기 중 ${played}경기가 끝났어요"
                },
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
            for (s in row.stages) StageLine(c, s, double, open)
        }
    }
}

@Composable
private fun StageLine(c: ClockView, s: WeekStage, double: Boolean, open: (WeekStage) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val live = c.live[s.comp to s.stage] ?: 0
    // A playoff not set up yet has nothing to open.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = s.set) { open(s) }.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stageName(s), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    !s.set -> "대진 미정"
                    s.done -> "✓ ${s.total}경기"
                    else -> "${s.played}/${s.total}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (s.done) cs.primary else cs.onSurfaceVariant,
                fontWeight = if (s.done) FontWeight.Bold else FontWeight.Normal,
            )
            if (s.set) Text(" ›", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
        if (s.set && s.total > 0 && !s.done && (s.played > 0 || live > 0)) {
            LinearProgressIndicator(progress = { s.played.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
        }
        val note = when {
            live > 0 -> "지금 ${live}경기 진행 중이에요"
            !s.set && s.comp == Comp.TITLE_PLAYOFF -> if (double) "리그가 끝나면 1부 1~4위가 패자부활 토너먼트로 맞붙어요" else "리그가 끝나면 1부 1~4위가 맞붙어요"
            !s.set && s.comp == Comp.PROMOTION_PLAYOFF -> "리그가 끝나면 부마다 승강을 걸고 맞붙어요"
            s.set && s.total == 0 -> "앞 단계가 끝나면 대진이 정해져요"
            else -> null
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (live > 0) cs.tertiary else cs.onSurfaceVariant) }
    }
}

/** Stages of earlier weeks with matches still to play (slow AIs hold them up). */
@Composable
private fun LateCard(c: ClockView, late: List<WeekStage>, open: (WeekStage) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("⏳ 아직 안 끝난 지난 주 경기", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            for (s in late) {
                Text(
                    "${stageName(s)} (${weekName(s.week, c.leagueWeeks)}) · 남은 ${s.total - s.played}경기",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clickable { open(s) },
                )
            }
            Text("생각이 오래 걸리는 AI가 있어서 조금 늦어지고 있어요. 끝나면 원래 주의 결과로 들어가요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** The week's top news. */
@Composable
private fun WeekNews(news: List<Article>, c: ClockView, w: Int) {
    val cs = MaterialTheme.colorScheme
    val top = remember(news, c.season, w) { news.filter { it.season == c.season && it.week == w }.sortedByDescending { it.importance }.take(3) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("📰 이 주의 소식", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            if (top.isEmpty()) {
                Text(
                    when {
                        c.over || w < c.week -> "남아 있는 소식이 없어요."
                        w == c.week -> "아직 이 주의 소식이 없어요."
                        else -> "아직 열리지 않은 주예요."
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            for (a in top) {
                Text(
                    (a.comp?.let { compIcon(it) + " " } ?: "") + a.headline,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (a.importance >= 4) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NextWeek(c: ClockView, next: WeekRow) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("다음 주 · ${weekName(next.week, c.leagueWeeks)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            if (next.stages.isEmpty()) Text("경기가 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            for (s in next.stages) {
                Text(
                    stageName(s) + if (s.set && s.total > 0) " · ${s.total}경기" + (if (s.played > 0) " (${s.played}경기는 먼저 치렀어요)" else "") else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The finals and events ahead. */
@Composable
private fun BigGames(c: ClockView) {
    val cs = MaterialTheme.colorScheme
    if (c.over) return
    val ahead = c.weeks.flatMap { r -> r.stages.filter { it.final && !it.done } }.take(5)
    if (ahead.isEmpty()) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("🔜 다가오는 큰 경기", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            for (s in ahead) {
                val left = s.week - c.week
                Text(
                    "${stageName(s)} · ${weekName(s.week, c.leagueWeeks)}" + when {
                        left > 0 -> " · ${left}주 남았어요"
                        else -> " · 이번 주"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The whole season, a row a week; a tap opens that week. */
@Composable
private fun SeasonWeeks(c: ClockView, onWeek: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (c.week - 3).coerceIn(0, maxOf(0, c.weeks.size - 1)))
    LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(c.weeks, key = { "w-${it.week}" }) { r -> Column {
            if (r.week == c.leagueWeeks + 1) {
                Text("포스트시즌", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.secondary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
            val now = r.week == c.week && !c.over
            val done = c.over || r.week < c.week || r.done
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(if (now) cs.secondaryContainer.copy(alpha = 0.6f) else cs.surface)
                    .clickable { onWeek(r.week) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (r.week > c.leagueWeeks) "PO ${r.week - c.leagueWeeks}주" else "${r.week}주",
                    modifier = Modifier.width(52.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (now) FontWeight.ExtraBold else FontWeight.Bold,
                    color = if (done || now) cs.onSurface else cs.onSurfaceVariant,
                )
                Box(Modifier.width(20.dp)) {
                    // A past week with matches left (slow AIs) waits with an hourglass.
                    Text(
                        when {
                            now -> "●"
                            r.done || c.over -> "✓"
                            done -> "⏳"
                            else -> ""
                        },
                        color = if (now) cs.tertiary else cs.primary, style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    r.stages.joinToString(" · ") { s -> if (s.comp == Comp.LEAGUE) "📋 ${s.label}" else stageName(s, short = true) }.ifEmpty { "–" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (done || now) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } }
        item {
            Column {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    "시즌 마감 · 🏅 시상식 · 승강 · 이적 시장 · 다음 시즌 대진 추첨",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }
    }
}

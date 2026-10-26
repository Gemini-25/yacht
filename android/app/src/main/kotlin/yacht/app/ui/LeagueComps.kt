package yacht.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.ClockView
import yacht.app.core.LeagueController
import yacht.engine.league.BracketRound
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.GroupTable
import yacht.engine.league.WeekStage

/** 대회: this season's cups, champions league, super cup, all-star game, family cup and playoffs. */
@Composable
fun CompsTab(lg: LeagueController, comps: List<Pair<Comp, CompetitionView>>, clock: ClockView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (comps.isEmpty()) {
        CenterText(
            "이번 시즌에는 열린 대회가 없어요.\n리그가 시작되면 요트컵·챔피언스 리그·올스타전이 열리고, 시즌 2부터 슈퍼컵, " +
                "3시즌마다 계열 대항전이 열려요. 리그가 끝나면 우승·승강 플레이오프를 치러요. (규칙에서 끄고 켤 수 있어요)",
        )
        return
    }
    val shown = comps.firstOrNull { it.first == lg.compFocus } ?: comps.first()
    // Each competition's stages on the calendar, in order.
    val stages = remember(clock?.weeks) { clock?.weeks.orEmpty().flatMap { it.stages }.filter { it.comp != Comp.LEAGUE }.groupBy { it.comp }.mapValues { (_, l) -> l.sortedBy { it.stage } } }
    val mine = stages[shown.first].orEmpty()
    // The same two functions for every snapshot (they read the newest squads), so rows whose
    // tie did not change skip recomposing.
    val squads by rememberUpdatedState(shown.second.squads)
    val openAi by rememberUpdatedState(onOpen)
    val label: (String) -> String = remember(lg) { { k -> if (squads.containsKey(k)) Competitions.teamLabel(k) else lg.name(k) } }
    // A team (all-star side, family) has no page of its own: its squad is listed below.
    val open: (String) -> Unit = remember { { k -> if (!squads.containsKey(k)) openAi(k) } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((c, v) in comps) {
                    val status = clock?.let { compStatus(stages[c].orEmpty(), it) }
                    FilterChip(
                        selected = c == shown.first, onClick = { lg.compFocus = c },
                        label = { Text(compIcon(c) + " " + v.title + (status?.let { " · $it" } ?: ""), fontSize = 12.sp) },
                    )
                }
            }
        }
        if (clock != null && mine.isNotEmpty()) item { StageStrip(mine, clock) }
        when (val v = shown.second) {
            is CompetitionView.Bracket -> item { BracketCard(v, label, open, roundWeeks(shown.first, mine, v.rounds.size, clock?.leagueWeeks ?: Int.MAX_VALUE)) }
            is CompetitionView.Groups -> {
                items(v.groups, key = { "g-${it.label}" }) { g -> GroupCard(g, label, open) }
                v.knockout?.let { k -> item { BracketCard(k, label, open, roundWeeks(shown.first, mine, k.rounds.size, clock?.leagueWeeks ?: Int.MAX_VALUE)) } }
            }
        }
        val squads = shown.second.squads
        if (squads.isNotEmpty()) {
            item { Text("명단 (1번 보드가 에이스)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            items(squads.entries.toList(), key = { "s-${it.key}" }) { (team, members) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(Competitions.teamLabel(team), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            members.mapIndexed { i, k -> "${i + 1}. ${lg.name(k)}" }.joinToString("  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A chip's state: the stage in play ("64강"), the week of the next one ("13주"), or "끝". */
private fun compStatus(stages: List<WeekStage>, c: ClockView): String? {
    if (stages.isEmpty()) return null
    val cur = stages.firstOrNull { !it.done } ?: return "끝"
    return when {
        cur.week > c.week -> "${cur.week}주"
        cur.label == cur.comp.label -> "진행 중"
        else -> cur.label
    }
}

/** The weeks of a bracket's rounds ("14주", "PO 1주"): the competition's last stages (one stage for the promotion playoffs). */
private fun roundWeeks(comp: Comp, stages: List<WeekStage>, rounds: Int, leagueWeeks: Int): List<String> {
    if (stages.isEmpty()) return emptyList()
    fun name(w: Int) = if (w > leagueWeeks) "PO ${w - leagueWeeks}주" else "${w}주"
    if (comp == Comp.PROMOTION_PLAYOFF) return List(rounds) { name(stages.first().week) }
    val offset = stages.size - rounds
    return if (offset < 0) emptyList() else List(rounds) { name(stages[offset + it].week) }
}

/** Where the competition stands, and every stage with its week (✓ played, ● now). */
@Composable
private fun StageStrip(stages: List<WeekStage>, c: ClockView) {
    val cs = MaterialTheme.colorScheme
    val cur = stages.firstOrNull { !it.done }
    val weeks = c.leagueWeeks
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    cur == null -> "모든 경기가 끝났어요"
                    cur.week > c.week -> "${cur.label} · ${weekName(cur.week, weeks)}에 열려요 (${cur.week - c.week}주 뒤)"
                    !cur.set || cur.total == 0 -> "${cur.label} · ${weekName(cur.week, weeks)} · 앞 단계가 끝나면 대진이 정해져요"
                    else -> "${cur.label} · ${weekName(cur.week, weeks)} · ${cur.total}경기 중 ${cur.played}경기 끝났어요"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stages.joinToString("  ·  ") { s ->
                    val mark = when {
                        s.done -> " ✓"
                        s.week <= c.week -> " ●"
                        else -> ""
                    }
                    // No-break spaces keep each stage on one line when the list wraps.
                    "${s.label} ${if (s.week > weeks) "PO ${s.week - weeks}주" else "${s.week}주"}$mark".replace(' ', '\u00A0')
                },
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

fun compIcon(c: Comp): String = when (c) {
    Comp.CUP -> "🏆"
    Comp.CHAMPIONS -> "⭐"
    Comp.SUPER -> "🥇"
    Comp.ALL_STAR -> "🌟"
    Comp.FAMILY -> "🌏"
    Comp.CLUB -> "🛡"
    Comp.TITLE_PLAYOFF -> "👑"
    Comp.PROMOTION_PLAYOFF -> "↕"
    Comp.LEGEND -> "🏛"
    Comp.LAB -> "🧪"
    Comp.FRIENDLY -> "🤝"
    Comp.LEAGUE -> "📋"
}

/** A bracket, one round at a time (a 256-team cup has 128 ties in its first round). */
@Composable
private fun BracketCard(b: CompetitionView.Bracket, label: (String) -> String, onOpen: (String) -> Unit, weeks: List<String> = emptyList()) {
    val cs = MaterialTheme.colorScheme
    // The round being played (the first with an unplayed known tie), else the last.
    val current = b.rounds.indexOfFirst { r -> r.ties.any { !it.played && it.winner == null && it.a != null && it.b != null } }
        .takeIf { it >= 0 } ?: b.rounds.indexOfLast { r -> r.ties.any { it.played } }.coerceAtLeast(0)
    var round by remember(b.title) { mutableStateOf(-1) }
    val r = (if (round < 0) current else round).coerceIn(0, maxOf(0, b.rounds.size - 1))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(b.title, fontWeight = FontWeight.Bold)
            if (b.rounds.isEmpty()) {
                Text("대진이 아직 정해지지 않았어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((i, br) in b.rounds.withIndex()) {
                        val wk = weeks.getOrNull(i)?.let { " · $it" } ?: ""
                        FilterChip(selected = i == r, onClick = { round = i }, label = { Text(br.label + wk, fontSize = 11.sp) })
                    }
                }
                RoundTies(b.rounds[r], label, onOpen)
                val final = b.rounds.last().ties.singleOrNull()
                val champion = final?.winner
                if (final != null && final.played && champion != null) {
                    Text("🏆 우승 ${label(champion)}", fontWeight = FontWeight.ExtraBold, color = cs.secondary)
                }
            }
        }
    }
}

@Composable
private fun RoundTies(round: BracketRound, label: (String) -> String, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val byes = round.ties.count { it.winner != null && !it.played && (it.a == null || it.b == null) }
    if (round.ties.all { it.a == null && it.b == null }) {
        Text("앞 단계가 끝나면 대진이 정해져요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (byes > 0) Text("부전승 ${byes}팀", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        for (t in round.ties) {
            if (t.winner != null && !t.played && (t.a == null || t.b == null)) continue
            TieLine(t.a, t.b, t.scoreA, t.scoreB, t.winner, t.played, t.legs, label, onOpen)
        }
    }
}

/** One tie, from plain values: a tie that did not change since the last snapshot skips recomposing. */
@Composable
private fun TieLine(
    a: String?,
    b: String?,
    scoreA: Double,
    scoreB: Double,
    winner: String?,
    played: Boolean,
    legs: Int,
    label: (String) -> String,
    onOpen: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Side(a, winner, label, onOpen, Modifier.weight(1f), TextAlign.Start)
        Text(
            when {
                played -> "${score(scoreA)} : ${score(scoreB)}"
                a == null || b == null -> "미정"
                else -> if (legs > 1) "${legs}경기" else "vs"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (played) FontWeight.Bold else FontWeight.Normal,
            color = if (played) cs.onSurface else cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Side(b, winner, label, onOpen, Modifier.weight(1f), TextAlign.End)
    }
}

@Composable
private fun Side(key: String?, winner: String?, label: (String) -> String, onOpen: (String) -> Unit, modifier: Modifier, align: TextAlign) {
    val won = key != null && key == winner
    Text(
        key?.let(label) ?: "?",
        modifier = modifier.then(if (key != null) Modifier.clickable { onOpen(key) } else Modifier),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (won) FontWeight.ExtraBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

private fun score(x: Double): String = if (x == Math.floor(x)) "%.0f".format(x) else "%.1f".format(x)

@Composable
private fun GroupCard(g: GroupTable, label: (String) -> String, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(g.label, fontWeight = FontWeight.Bold)
            Row {
                Text("팀", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("경기", Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("승-무-패", Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("득실", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("승점", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
            }
            HorizontalDivider()
            for ((i, row) in g.rows.withIndex()) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(row.key) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(18.dp).background(if (i < 2) gameColors.good else androidx.compose.ui.graphics.Color.Transparent))
                    Text(" ${i + 1} ${label(row.key)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${row.played}", Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                    Text("${row.wins}-${row.draws}-${row.losses}", Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                    Text("%+d".format(row.diff), Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                    Text("${row.points}", Modifier.width(34.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
                }
            }
        }
    }
}

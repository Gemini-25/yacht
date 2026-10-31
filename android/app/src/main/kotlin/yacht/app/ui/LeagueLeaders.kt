package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AggregateStats
import yacht.app.core.AppController
import yacht.app.core.FactsView
import yacht.app.core.FunFact
import yacht.app.core.LeaderArea
import yacht.app.core.LeaderFilter
import yacht.app.core.LeaderKind
import yacht.app.core.LeaderLine
import yacht.app.core.LeadersView
import yacht.app.core.LeagueAnalytics
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.Sheet
import yacht.app.core.StatScope

// 기록 › 리더: the leaderboard hub (eight boards as cards with their top 3, the other four folded,
// one row of filters: 이번 시즌 / 통산 / 최근 10경기 and 내 리그 / 전체 / 국가 / 클럽), each board's page
// (Page.Leaderboard), the filter sheet, and 탐구 (Page.Facts: fun facts and the league comparison).

/** A board's icon on its card and page. */
internal fun leaderIcon(k: LeaderKind): String = when (k) {
    LeaderKind.SCORER -> "⚽"
    LeaderKind.YACHTS -> "🎲"
    LeaderKind.MVP -> "⭐"
    LeaderKind.SWEEPS -> "🧹"
    LeaderKind.BONUS -> "🎯"
    LeaderKind.COMEBACK -> "🔄"
    LeaderKind.CLUTCH -> "🧊"
    LeaderKind.GIANT -> "🗡"
    LeaderKind.LUCKY -> "🍀"
    LeaderKind.UNLUCKY -> "🌧"
    LeaderKind.STEADY -> "📏"
    LeaderKind.OVER300 -> "💯"
}

/** The filter's period in words ("이번 시즌", "통산", "최근 10경기"). */
internal fun periodLabel(f: LeaderFilter): String = if (f.recent) "최근 ${LeaderFilter.RECENT}경기" else f.scope.label

/**
 * 기록 › 리더: the filter row, then the hub's eight boards as cards (top 3, a tap: the board's page),
 * "다른 부문 4개" folded, the league comparison and 탐구. Loaded again each week.
 */
@Composable
internal fun LeadersHub(lg: LeagueController, v: LeagueView) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    val filter = nav.leaders
    LaunchedEffect(filter, v.season?.number, v.clock?.week) { lg.analytics.loadLeaders(filter) }
    // While another filter loads, the boards loaded before stay (the area line waits for the new ones).
    val view = lg.analytics.leaders(filter) ?: lg.analytics.anyLeaders()
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterRow(lg, filter, view?.takeIf { it.filter == filter }?.area) }
        if (view == null) {
            item { Text("리더보드를 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
            return@LazyColumn
        }
        val boards = view.boards.toMap()
        items(LeagueAnalytics.HUB.chunked(2), key = { "hub-${it.first()}" }) { row -> CardRow(row, boards, view, onBoard = { nav.open(Page.Leaderboard(it)) }) }
        item(key = "others") {
            var open by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (open) "다른 부문 접기 ▴" else "다른 부문 ${LeagueAnalytics.OTHERS.size}개 ▾", style = MaterialTheme.typography.labelLarge,
                    color = cs.primary, modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
                )
                if (open) for (row in LeagueAnalytics.OTHERS.chunked(2)) CardRow(row, boards, view, onBoard = { nav.open(Page.Leaderboard(it)) })
            }
        }
        item(key = "links") {
            Column {
                if (v.season?.national == true) LinkLine("🌍 리그 비교", "리그마다 평균 점수, 요트율, 보너스율, 평점과 한 줄 스타일") { nav.open(Page.Facts(leagues = true)) }
                LinkLine("🔎 탐구", "가장 낮은 점수로 이긴 판, 천적 관계, 레이팅보다 잘한 선수…") { nav.open(Page.Facts()) }
            }
        }
    }
}

/** The one filter row: the period and the area, each a chip that opens [Sheet.LEADER_FILTER]; [area] the name of the boards' area once loaded. */
@Composable
private fun FilterRow(lg: LeagueController, filter: LeaderFilter, area: String?) {
    val nav = lg.nav
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = true, onClick = { nav.sheet = Sheet.LEADER_FILTER }, label = { Text("📅 ${periodLabel(filter)} ▾", fontSize = 12.sp, maxLines = 1) })
            FilterChip(
                selected = true, onClick = { nav.sheet = Sheet.LEADER_FILTER },
                label = { Text("🌐 ${filter.area.label}${if (filter.tierOnly) " · 1부" else ""} ▾", fontSize = 12.sp, maxLines = 1) },
            )
        }
        area?.let { Text("$it · ${periodLabel(filter)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun CardRow(row: List<LeaderKind>, boards: Map<LeaderKind, List<LeaderLine>>, view: LeadersView, onBoard: (LeaderKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in row) LeaderCard(k, boards[k].orEmpty(), k in view.missing, view.filter, Modifier.weight(1f)) { onBoard(k) }
        if (row.size == 1) Spacer(Modifier.weight(1f))
    }
}

/** One board's card: its icon and name, its top 3 (rank, name, value), or why it has none. */
@Composable
internal fun LeaderCard(kind: LeaderKind, lines: List<LeaderLine>, missing: Boolean, filter: LeaderFilter, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${leaderIcon(kind)} ${LeagueAnalytics.title(kind)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Text("›", color = cs.onSurfaceVariant)
            }
            when {
                missing -> Text(missingText(kind, filter), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, minLines = 3)
                lines.isEmpty() -> Text("아직 기준을 채운 선수가 없어요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, minLines = 3)
                else -> for ((i, l) in lines.withIndex()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (i == 0) cs.primary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                        Text(l.shown, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (kind == LeaderKind.MVP) RatingText(l.value, digits = 2) else Text(l.text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Why a board has no lines in this filter: no data kept for it ("최근 10경기"), or a season-only board over careers. */
private fun missingText(kind: LeaderKind, filter: LeaderFilter): String = when {
    filter.recent -> "최근 ${LeaderFilter.RECENT}경기 기록으로는 셀 수 없는 부문이에요."
    kind == LeaderKind.SWEEPS -> "시즌마다 세는 부문이에요."
    else -> "이 범위에는 기록이 없어요."
}

/**
 * [Page.Leaderboard]: the filter row, the board's rule, its area and players on it, then its first
 * [LeagueAnalytics.BOARD_TOP] lines and the followed players further down. A line opens the player.
 */
@Composable
internal fun LeaderBoardPage(lg: LeagueController, v: LeagueView, page: Page.Leaderboard) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    val filter = nav.leaders
    LaunchedEffect(page.kind, filter, v.season?.number, v.clock?.week) { lg.analytics.loadBoard(page.kind, filter) }
    val board = lg.analytics.board(page.kind, filter)
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { FilterRow(lg, filter, board?.area) }
        item { Text(board?.rule ?: LeagueAnalytics.ruleOf(page.kind, filter), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
        when {
            board == null -> item { Text("불러오는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            board.missing -> item { Text(missingText(page.kind, filter), style = MaterialTheme.typography.bodySmall) }
            board.lines.isEmpty() -> item { Text("아직 기준을 채운 선수가 없어요.", style = MaterialTheme.typography.bodySmall) }
            else -> {
                item { Text("%,d명".format(board.eligible), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                items(board.lines.withIndex().toList(), key = { "line-${it.value.key}" }) { (i, l) -> BoardLine(i + 1, l, page.kind, l.key in lg.favourites) { nav.open(Page.Player(l.key)) } }
                if (board.followed.isNotEmpty()) {
                    item { Text("★ 응원 선수", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
                    items(board.followed, key = { "fav-${it.second.key}" }) { (rank, l) -> BoardLine(rank, l, page.kind, true) { nav.open(Page.Player(l.key)) } }
                }
            }
        }
    }
}

/** One line of a board: its place, the player (badge, league), its value and sample. */
@Composable
internal fun BoardLine(rank: Int, l: LeaderLine, kind: LeaderKind, followed: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (rank <= 3) cs.surfaceVariant.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (rank <= 3) cs.primary else cs.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
        Spacer(Modifier.width(8.dp))
        ClubMark(l.badge)
        Column(Modifier.weight(1f)) {
            Text((if (followed) "★ " else "") + l.shown, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            l.league?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1) }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (kind == LeaderKind.MVP) RatingText(l.value, digits = 2, tagged = true, style = MaterialTheme.typography.bodyMedium)
            else Text(l.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(l.sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The 기록 tab's sheets (drawn over the league screen): the leaderboards' filter. */
@Composable
internal fun RecordsSheets(c: AppController) {
    val lg = c.league
    val nav = lg.nav
    if (nav.sheet != Sheet.LEADER_FILTER) return
    LeagueSheet("리더보드 필터", { nav.sheet = Sheet.NONE }) { LeaderFilterSheet(lg) }
}

/** The period (이번 시즌 / 통산 / 최근 10경기), the area (내 리그 / 전체 / 국가 / 클럽) and 1부만. */
@Composable
private fun ColumnScope.LeaderFilterSheet(lg: LeagueController) {
    val nav = lg.nav
    val f = nav.leaders
    SheetHeading("기간")
    SheetChoice("이번 시즌", "이번 시즌 공식 경기와 리그 순위표", !f.recent && f.scope == StatScope.SEASON) { nav.leaders = f.copy(scope = StatScope.SEASON, recent = false) }
    SheetChoice("통산", "리그에 남아 있는 모든 기록 (완승왕은 시즌마다만 세요)", !f.recent && f.scope == StatScope.CAREER) { nav.leaders = f.copy(scope = StatScope.CAREER, recent = false) }
    SheetChoice(
        "최근 ${LeaderFilter.RECENT}경기", "선수마다 이번 시즌 마지막 리그 ${LeaderFilter.RECENT}경기 (평점·역전·이변은 빼요)", f.recent,
    ) { nav.leaders = f.copy(scope = StatScope.SEASON, recent = true) }
    SheetHeading("범위")
    for (a in LeaderArea.entries) SheetChoice(a.label, areaText(a), f.area == a) { nav.leaders = f.copy(area = a) }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().clickable { nav.leaders = f.copy(tierOnly = !f.tierOnly) }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("1부 선수만", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = f.tierOnly, onCheckedChange = { nav.leaders = f.copy(tierOnly = it) })
    }
}

private fun areaText(a: LeaderArea): String = when (a) {
    LeaderArea.LEAGUE -> "순위 › 리그에서 보고 있는 리그 (없으면 응원 리그)"
    LeaderArea.ALL -> "리그에 있는 모든 선수"
    LeaderArea.NATION -> "응원하는 나라의 선수 (없으면 보고 있는 리그의 나라, 한국)"
    LeaderArea.CLUB -> "응원하는 클럽 (없으면 응원 선수의 클럽)의 선수단"
}

// ---- 탐구 and the league comparison ---------------------------------------------------------------

/** [Page.Facts]: the league comparison (first when [Page.Facts.leagues]) and this season's fun facts. */
@Composable
internal fun FactsPage(lg: LeagueController, v: LeagueView, page: Page.Facts) {
    val nav = lg.nav
    LaunchedEffect(v.season?.number, v.clock?.week) { lg.analytics.loadFacts() }
    val f = lg.analytics.facts()
    if (f == null) {
        CenterText("탐구할 기록을 모으는 중…")
        return
    }
    FactsBody(
        f, page.leagues,
        onPlayer = { k -> if (lg.isAi(k)) nav.open(Page.Player(k)) },
        onMatch = { nav.open(Page.Match(it)) },
        onLeague = { nav.open(Page.League(it)) },
    )
}

/** The facts page's body (the harness draws it with hand-made facts too). */
@Composable
internal fun FactsBody(f: FactsView, leaguesFirst: Boolean, onPlayer: (String) -> Unit, onMatch: (String) -> Unit, onLeague: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fun comparison() {
            if (f.leagues.isEmpty()) return
            item(key = "cmp-head") {
                Column {
                    Text("🌍 리그 비교 · 시즌 ${f.season}", fontWeight = FontWeight.Bold)
                    f.world?.let { w -> Text("전체 평균 ${"%.1f".format(w.avgScore)}점 · 요트 ${pctText(w.yachtRate)} · 보너스 ${pctText(w.bonusRate)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                }
            }
            items(f.leagues, key = { "cmp-${it.id}" }) { l -> LeagueCompareRow(l.icon, l.label, l.agg, l.style) { onLeague(l.id) } }
        }
        fun funFacts() {
            item(key = "facts-head") { Text("🔎 탐구 · 시즌 ${f.season}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
            if (f.facts.isEmpty()) item(key = "facts-none") { Text("이번 시즌 경기가 쌓이면 재미있는 기록이 보여요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            items(f.facts, key = { "fact-${it.title}" }) { fact -> FactCard(fact, onPlayer, onMatch) }
        }
        if (leaguesFirst) {
            comparison()
            funFacts()
        } else {
            funFacts()
            comparison()
        }
    }
}

/** A league in the comparison: its numbers in one line and its style in a sentence. */
@Composable
private fun LeagueCompareRow(icon: String, label: String, a: AggregateStats, style: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$icon $label", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!a.avgRating.isNaN()) RatingText(a.avgRating, digits = 2)
            }
            Text(
                "평균 ${"%.1f".format(a.avgScore)}점 · 요트 ${pctText(a.yachtRate)} · 보너스 ${pctText(a.bonusRate)} · 선수 ${a.members}명",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
            if (style.isNotEmpty()) Text(style, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** One fun fact: its title and rule, then its lines (a tap: the match, else the first player). */
@Composable
internal fun FactCard(f: FunFact, onPlayer: (String) -> Unit, onMatch: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${f.icon} ${f.title}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(f.rule, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            for (l in f.lines) {
                Text(
                    l.text + if (l.ref != null) "  ›" else "", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = l.ref != null || l.keys.isNotEmpty()) {
                        l.ref?.let(onMatch) ?: l.keys.firstOrNull()?.let(onPlayer)
                    }.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** A group's numbers and their sentence ([styleSentence]) as a club, league or nation page shows them. */
@Composable
internal fun StyleMeta(title: String, a: AggregateStats, sentence: String) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(
            "평균 ${"%.1f".format(a.avgScore)}점 · 요트 ${pctText(a.yachtRate)} · 보너스 ${pctText(a.bonusRate)}" + if (a.avgRating.isNaN()) "" else " · 평점 ${"%.2f".format(a.avgRating)}",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
        Text(sentence, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * A group's style this season in a sentence against everyone's ([LeagueAnalytics.loadGroup]:
 * "l:<league>", "c:<club>"; loaded on open and again each week), for the league and club pages.
 */
@Composable
internal fun GroupStyleLine(lg: LeagueController, key: String) {
    LaunchedEffect(key, lg.view?.season?.number, lg.view?.clock?.week) { lg.analytics.loadGroup(key) }
    val (_, sentence) = lg.analytics.group(key) ?: return
    Text("🎨 $sentence", style = MaterialTheme.typography.bodySmall)
}

/** A nation's players this season in numbers and a sentence (국적 선수 통계, the nation page), loaded on open. */
@Composable
internal fun NationStatsLine(lg: LeagueController, code: String) {
    val key = "n:$code"
    LaunchedEffect(key, lg.view?.season?.number, lg.view?.clock?.week) { lg.analytics.loadGroup(key) }
    val (agg, sentence) = lg.analytics.group(key) ?: return
    StyleMeta("📊 국적 선수 통계 (이번 시즌 ${agg.members}명)", agg, sentence)
}

private fun pctText(x: Double): String = if (x.isNaN()) "–" else "%.0f%%".format(100 * x)

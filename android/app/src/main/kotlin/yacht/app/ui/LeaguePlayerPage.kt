package yacht.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.LadderRow
import yacht.app.core.LeagueController
import yacht.app.core.LeagueTab
import yacht.app.core.LeagueView
import yacht.app.core.Matchup
import yacht.app.core.Page
import yacht.app.core.PlayerMatch
import yacht.app.core.PlayerPage
import yacht.app.core.PlayerTab
import yacht.app.core.RecordsView
import yacht.app.core.Screen
import yacht.app.core.Sheet
import yacht.app.core.StatCell
import yacht.app.core.StatScope
import yacht.app.core.TableMode
import yacht.app.core.TablesView
import yacht.engine.Category
import yacht.engine.explain.Ko
import yacht.engine.game.label
import yacht.engine.league.AiStats
import yacht.engine.league.Awards
import yacht.engine.league.Comp
import yacht.engine.league.CustomAis
import yacht.engine.league.Dynasty
import yacht.engine.league.HonourBook
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueIds
import yacht.engine.league.Origin

// The player page (Page.Player): its header and quick buttons above four tabs (개요, 기록, 경기,
// 수상), the page bar's ☆/★, ⚖ and ⋯, and the ⋯ sheet of actions. The data comes from
// LeagueAnalytics.loadPlayer; club badges and league labels only from the league's host.

/**
 * [Page.Player]: loads the page (again after each of the player's matches) and draws the last one
 * loaded for it at once, so going back from a rival's page shows this player's data and tab.
 * Each player's page is a composition of its own: a rival opened from far down starts at the top,
 * with its own folds and filters, not the ones of the page under it.
 */
@Composable
internal fun PlayerPageBody(c: AppController, v: LeagueView, page: Page.Player) {
    key(page.key) { PlayerPageContent(c, v, page) }
}

@Composable
private fun PlayerPageContent(c: AppController, v: LeagueView, page: Page.Player) {
    val lg = c.league
    val nav = lg.nav
    val live = v.rows.firstOrNull { it.key == page.key }
    LaunchedEffect(page.loadKey, live?.games, v.season?.number) { lg.analytics.loadPlayer(page.key, page.scope) }
    val loaded = lg.analytics.player(page)
    // While another scope loads, everything but its numbers is drawn from the scope loaded before.
    val p = loaded ?: StatScope.entries.firstNotNullOfOrNull { lg.analytics.player(page.copy(scope = it)) }
    if (p == null) {
        CenterText("선수 기록을 불러오는 중…")
        return
    }
    // Another player's page (an article's first key can be this player, a team or a club).
    val open: (String) -> Unit = { k -> if (k != page.key && lg.isAi(k)) nav.open(Page.Player(k)) }
    val row = live ?: p.row
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerHeader(lg, v, p, row)
            // I play no league matches: no buttons to them.
            if (page.key != Ladder.ME) QuickButtons(lg, p)
        }
        TabRow(selectedTabIndex = page.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
            for (t in PlayerTab.entries) {
                Tab(selected = t == page.tab, onClick = { nav.replaceTop(page.copy(tab = t)) }, text = { Text(t.label, fontSize = 14.sp) })
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (page.tab) {
                PlayerTab.OVERVIEW -> OverviewTab(lg, p, row, open)
                PlayerTab.STATS -> StatsTab(lg, page, loaded, row)
                PlayerTab.MATCHES -> MatchesTab(lg, v, p, open)
                PlayerTab.HONOURS -> HonoursTab(lg, p)
            }
        }
    }
}

/**
 * The page bar's actions on a player's page: ☆/★ (응원), ⚖ (비교) and ⋯ (the actions sheet); none on
 * my own line. A player no longer in the league (retired) has nothing to do in the sheet and is not
 * followed anew (☆ stays only to stop following it).
 */
@Composable
internal fun PlayerBarActions(lg: LeagueController, page: Page.Player) {
    if (page.key == Ladder.ME) return
    val nav = lg.nav
    val fav = page.key in lg.favourites
    val plays = lg.view?.rows?.any { it.key == page.key } == true
    if (plays || fav) {
        IconButton(onClick = { lg.toggleFavourite(page.key) }) {
            Text(if (fav) "★" else "☆", fontSize = 20.sp, color = if (fav) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                modifier = Modifier.clearAndSetSemantics { contentDescription = if (fav) "응원 그만하기" else "응원하기" })
        }
    }
    IconButton(onClick = { nav.open(Page.Compare(page.key)) }) {
        Text("⚖", fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics { contentDescription = "다른 선수와 비교" })
    }
    if (plays) {
        IconButton(onClick = { nav.sheet = Sheet.PLAYER_ACTIONS }) {
            Text("⋯", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics { contentDescription = "더 하기" })
        }
    }
}

/**
 * [Sheet.PLAYER_ACTIONS] over the league screen while a player's page is on top: a ranked game
 * against it, a rated friendly with its rival to watch, the lab, and the makers for a league
 * player or my own AI.
 */
@Composable
internal fun PlayerActionsSheet(c: AppController) {
    val lg = c.league
    val nav = lg.nav
    val page = nav.page as? Page.Player ?: return
    if (nav.sheet != Sheet.PLAYER_ACTIONS) return
    val key = page.key
    val close = { nav.sheet = Sheet.NONE }
    val row = lg.view?.rows?.firstOrNull { it.key == key } ?: return
    LeagueSheet(lg.name(key), close) {
        LinkLine("🎮 이 선수와 랭크전", "내 레이팅이 걸린 한 판이에요") {
            close()
            c.newRankedGame(key)
        }
        // Its rival (the page's) while it still plays in the league, else someone near its rating.
        val rival = StatScope.entries.firstNotNullOfOrNull { lg.analytics.player(page.copy(scope = it)) }?.rival?.other
            ?.takeIf { r -> lg.view?.rows?.any { it.key == r && !it.isMe } == true }
        val with = rival?.let { "라이벌 ${Ko.wa(lg.name(it))}" } ?: "레이팅이 비슷한 상대와"
        LinkLine("🤝 친선 경기 보기 (레이팅 반영)", "$with 새로 한 판을 두는 걸 지켜봐요") {
            val other = rival ?: lg.rivalOf(key, java.util.Random())
            if (other != null) {
                close()
                lg.center.watchFriendly(key, other)
                c.screen = Screen.WATCH
            }
        }
        LinkLine("⚗ 연구소에서 붙이기", "AI 연구소에서 이 선수를 골라 다른 AI와 수백 번 붙여 봐요") {
            close()
            nav.open(Page.Lab)
        }
        // A league player (generated, evolved or made): remade in 선수 만들기.
        if (row.origin == Origin.GENERATED || row.origin == Origin.EVOLVED || row.origin == Origin.CREATED) {
            LinkLine("✏️ 선수 고치기", "능력치와 스타일을 바꿔요") {
                close()
                c.editingPlayer = key
                c.screen = Screen.MAKER
            }
        }
        lg.customs.firstOrNull { it.key == key }?.let { mine ->
            LinkLine("🛠 고치기", "내가 만든 AI의 설정을 바꿔요") {
                close()
                c.editingAi = mine
                c.screen = Screen.BUILDER
            }
        }
    }
}

// ---- header ------------------------------------------------------------------------------------

/**
 * The header above the tabs: tier badge, flag and name (and nickname), club, "{league} {tier}
 * {place}위" (a tap: 순위 › 리그 at that division) or the retirement, then the rating line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerHeader(lg: LeagueController, v: LeagueView, p: PlayerPage, row: LadderRow?) {
    val cs = MaterialTheme.colorScheme
    val nav = lg.nav
    val profile = p.profile
    val id = profile.identity
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (row != null) {
            TierBadge(row.tier, row.games, Modifier.width(78.dp), if (row.isMe) Ladder.ME_PLACEMENT else Ladder.PLACEMENT)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(id?.flag, profile.name).joinToString(" "),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            id?.nickname?.let { Text("“$it”", style = MaterialTheme.typography.labelMedium, color = cs.tertiary, fontWeight = FontWeight.Bold) }
            p.badge?.let { b ->
                Row(Modifier.clickable { nav.open(Page.Club(b.id)) }, verticalAlignment = Alignment.CenterVertically) {
                    ClubEmblem(b.emblem, 18.dp)
                    Text(" ${b.name}", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                }
            }
            val place = p.position?.let { pos -> listOfNotNull(p.leagueLabel, p.tierLabel).joinToString(" ") + " ${pos}위" }
            when {
                profile.retired != null -> Text("은퇴 · 시즌 ${profile.retired}" + if (profile.legend) " · 🏛 레전드" else "", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                place != null && p.division != null -> Text(
                    "$place ›", style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { openDivision(lg, v, p.division) },
                )
                row != null && row.division == null && !row.isMe -> Text("이번 시즌은 리그 경기가 없어요", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            if (row != null) {
                // Its parts wrap whole: Korean text breaks between any two syllables ("최\n고 1539" at 360 dp).
                val parts = listOfNotNull(
                    "레이팅 ${row.rating ?: "–"}" + (row.margin?.let { " ±$it" } ?: ""),
                    row.rank?.let { "전체 ${it}위" },
                    row.peak?.let { "최고 $it" },
                )
                FlowRow {
                    for ((i, part) in parts.withIndex()) {
                        Text(if (i < parts.lastIndex) "$part · " else part, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, softWrap = false)
                    }
                }
            }
        }
    }
}

/** 순위 › 리그 at division [d] (its tier and group), the table view. */
private fun openDivision(lg: LeagueController, v: LeagueView, d: Int) {
    val nav = lg.nav
    val season = v.season ?: return
    nav.go(LeagueTab.TABLES)
    nav.tables = TablesView.LEAGUE
    nav.tableMode = TableMode.TABLE
    nav.showDivision(season.tiers.takeIf { it.size == season.divisions.size } ?: yacht.engine.league.Tiers.linear(season.divisions.size), d)
}

/** "▶ 최근 경기" and "🔮 다음 경기", side by side: the last played league match's page and the next one's. */
@Composable
private fun QuickButtons(lg: LeagueController, p: PlayerPage) {
    val last = p.matches.firstOrNull { it.ref != null && it.result != null }
    val next = p.next
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { last?.let { openMatch(lg, p, it.ref, it.week) } }, enabled = last != null, modifier = Modifier.weight(1f)) {
            Text("▶ 최근 경기", maxLines = 1)
        }
        OutlinedButton(onClick = { next?.let { openMatch(lg, p, it.ref, it.week) } }, enabled = next != null, modifier = Modifier.weight(1f)) {
            Text("🔮 다음 경기", maxLines = 1)
        }
    }
}

/** A league match's page ([week] its league week): its round is read first, so the page finds the fixture. */
private fun openMatch(lg: LeagueController, p: PlayerPage, ref: String?, week: Int) {
    ref ?: return
    p.division?.let { if (week >= 1) lg.loadRound(it, week - 1) }
    lg.nav.open(Page.Match(ref))
}

// ---- 개요 ----------------------------------------------------------------------------------------

/**
 * 개요: the clone's note, where it comes from, its form and rating line, the next match, this
 * season in a line, its identity (nation, abilities, style), its rival and matchups, the family tree.
 */
@Composable
private fun ColumnScope.OverviewTab(lg: LeagueController, p: PlayerPage, row: LadderRow?, open: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val profile = p.profile
    if (profile.key == CustomAis.CLONE) {
        lg.clone?.let { c ->
            Note(
                "내 플레이를 닮은 AI예요. 혼자 둔 최근 ${c.games}판에서 남기기 ${c.keepDecisions}번 중 ${"%.0f".format(100 * c.keepSlip)}%, " +
                    "칸 고르기 ${c.boxDecisions}번 중 ${"%.0f".format(100 * c.boxSlip)}%를 추천과 다르게 뒀어요. 분신도 그만큼 차선을 둬요.",
            )
        }
    }
    Text(
        listOfNotNull(
            profile.origin.label,
            profile.hometown.takeIf { it.isNotEmpty() }?.let { "$it 출신" },
            profile.born?.let { "시즌 $it 데뷔" },
            if (profile.legend && profile.retired == null) "🏛 레전드" else null,
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium, color = cs.secondary,
    )
    if (row != null && profile.identity == null) {
        Text("${row.family.label} · ${row.desc}" + if (row.heavy) " (승률 탐색을 써서 계산이 무거워요)" else "", style = MaterialTheme.typography.bodySmall)
    }
    if (row != null && row.recent.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("최근 ${row.recent.length}판  ", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            FormDots(row.recent)
            StreakText(row.streak)
        }
    }
    val history = profile.ratingHistory.takeLast(RATING_POINTS)
    if (history.size >= 2) {
        Text("레이팅 흐름 (최근 ${history.size}경기)", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        RatingLine(history)
    }
    p.next?.let { n ->
        Card(
            Modifier.fillMaxWidth().clickable { openMatch(lg, p, n.ref, n.week) },
            colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("🔮 다음 경기 · ${n.week}주차 · ${n.where}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("vs ${n.opponentName}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    n.winChance?.let { Text("예상 승률 $it%", style = MaterialTheme.typography.labelMedium, color = cs.primary) }
                }
            }
        }
    }
    profile.season?.takeIf { it.matches > 0 }?.let { s ->
        Column {
            Text("이번 시즌 ${s.matches}경기 ${s.matchWins}승 ${s.matchDraws}무 ${s.matchLosses}패", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "평균 ${"%.1f".format(s.avgScore)}점" + if (s.avgRating.isNaN()) "" else " · 평점 ${"%.2f".format(s.avgRating)}",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
    }
    // The nickname is in the header, the rival (tappable) below.
    profile.identity?.let { IdentityPart(it.copy(nickname = null, rival = null), alive = profile.retired == null, onOpen = open) }
    profile.genes?.takeIf { profile.identity?.style == null && profile.origin in MADE }?.let {
        Text("스타일: $it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
    p.rival?.let { r ->
        Text(
            "⚔ 라이벌: ${r.name} · ${r.games}판 승점 ${"%.0f".format(100 * r.score)}% ›",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp).clickable { open(r.other) },
        )
    }
    if (profile.best.isNotEmpty()) {
        SectionLabel("😊 잘 잡는 상대")
        for (m in profile.best) MatchupLine(m, open)
    }
    if (profile.worst.isNotEmpty()) {
        SectionLabel("😣 천적")
        for (m in profile.worst) MatchupLine(m, open)
    }
    if (profile.ancestors.any { g -> g.any { it != null } } || profile.descendants.isNotEmpty()) {
        var tree by remember(profile.key) { mutableStateOf(false) }
        Text(
            if (tree) "🌳 계보도 ▾" else "🌳 계보도 ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp).clickable { tree = !tree },
        )
        if (tree) Genealogy(p)
    }
}

/** 계보도: its teachers up and its students down, a generation a line. */
@Composable
private fun Genealogy(p: PlayerPage) {
    val small = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val up = listOf("스승", "스승의 스승", "3대 위 스승", "4대 위 스승")
    for ((g, row) in p.profile.ancestors.withIndex()) {
        val names = row.filterNotNull().distinct()
        if (names.isNotEmpty()) Text("${up.getOrElse(g) { "${g + 1}대 위" }}: ${names.joinToString(", ")}", style = small, color = color)
    }
    val down = listOf("제자", "제자의 제자", "3대 아래 제자", "4대 아래 제자")
    for ((g, row) in p.profile.descendants.withIndex()) {
        Text("${down.getOrElse(g) { "${g + 1}대 아래" }} ${row.size}: ${row.take(8).joinToString(", ")}${if (row.size > 8) " 외" else ""}", style = small, color = color)
    }
}

@Composable
private fun MatchupLine(m: Matchup, open: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { open(m.other) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(m.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("%.0f%%".format(100 * m.score), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text("  ${m.games}판", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 레이팅 흐름: its rating after each of its last matches, at least 20 points tall; the first and the last value under its ends. */
@Composable
private fun RatingLine(points: List<Int>) {
    val line = gameColors.me
    val grid = MaterialTheme.colorScheme.outlineVariant
    // At least 20 points tall, so a 1-point wobble does not look like a swing.
    val mid = (points.min() + points.max()) / 2
    val lo = minOf(points.min(), mid - 10)
    val hi = maxOf(points.max(), mid + 10)
    Column {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
            val span = maxOf(1, hi - lo).toFloat()
            val step = size.width / (points.size - 1)
            fun y(v: Int) = size.height * (1f - (v - lo) / span) * 0.9f + size.height * 0.05f
            for (i in 1 until points.size) {
                drawLine(line, Offset(step * (i - 1), y(points[i - 1])), Offset(step * i, y(points[i])), strokeWidth = 4f)
            }
        }
        Row {
            Text("${points.first()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${points.last()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- 기록 ----------------------------------------------------------------------------------------

/**
 * 기록: 이번 시즌 / 통산, the stat grid with league places ("기록 더 보기" for the rest), 칸별 평균
 * (a table, or bars), 점수 분포, 시즌별 기록 with the end-of-season rating, and the national team.
 * [p] is the page of [page]'s own scope (null while it loads).
 */
@Composable
private fun ColumnScope.StatsTab(lg: LeagueController, page: Page.Player, p: PlayerPage?, row: LadderRow?) {
    val cs = MaterialTheme.colorScheme
    // My games are ranked games and friendlies, never official ones: my ladder line's numbers, as before the page.
    if (page.key == Ladder.ME) {
        if (row == null || row.games == 0) {
            Text("아직 레이팅이 걸린 판이 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        } else {
            Text("랭크전과 친선 경기 등 레이팅이 걸린 모든 판 기준이에요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            CellGrid(ladderCells(row))
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in StatScope.entries) {
            FilterChip(selected = page.scope == s, onClick = { lg.nav.replaceTop(page.copy(scope = s)) }, label = { Text(s.label, fontSize = 13.sp) })
        }
    }
    if (p == null) {
        Text("기록을 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        return
    }
    if (p.cells.isEmpty()) {
        val none = when {
            p.scope == StatScope.CAREER -> "공식 경기 기록이 없어요."
            p.profile.retired != null -> "은퇴해서 이번 시즌 기록이 없어요."
            else -> "이번 시즌 공식 경기 기록이 아직 없어요."
        }
        Text(none, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    } else {
        Text("공식 경기 기준이에요. 순위는 같은 리그 선수들 사이에서 매겨요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        CellGrid(p.cells.take(FIRST_CELLS))
        if (p.cells.size > FIRST_CELLS) {
            var more by remember(page.key) { mutableStateOf(false) }
            if (more) CellGrid(p.cells.drop(FIRST_CELLS))
            Text(
                if (more) "접기 ▴" else "기록 더 보기 ▾", style = MaterialTheme.typography.labelMedium, color = cs.primary,
                modifier = Modifier.clickable { more = !more }.padding(vertical = 2.dp),
            )
        }
    }
    if (p.box.isNotEmpty()) BoxTable(p)
    p.histogram?.takeIf { h -> h.any { it > 0 } }?.let { h ->
        SectionLabel("📊 점수 분포")
        ScoreHistogram(h, p.median, AiStats.BIN)
        Text(
            "막대 하나가 ${AiStats.BIN}점 구간에서 나온 판 수예요." + if (p.median.isNaN()) "" else " 점선은 리그의 중앙값(${"%.0f".format(p.median)}점)이에요.",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
    }
    SeasonLines(p)
    p.profile.identity?.takeIf { it.caps > 0 }?.let { id ->
        SectionLabel("🎽 국가대표")
        Text(
            "${id.flag} ${id.nation} 대표 ${id.caps}회 선발" + (id.capBoards?.takeIf { (w, d, l) -> w + d + l > 0 }?.let { (w, d, l) -> " · ${w}승 ${d}무 ${l}패" } ?: "") +
                if (id.capTitles > 0) " · 우승 ${id.capTitles}회" else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** My ladder line in cells (every rated game: ranked games and friendlies). */
private fun ladderCells(r: LadderRow): List<StatCell> = listOf(
    StatCell("판 수", "${r.games}판", null, null),
    StatCell("승-무-패", "${r.wins}-${r.ties}-${r.losses}", null, null),
    StatCell("승률", "%.1f%%".format(100 * r.winRate), null, null),
    StatCell("평균 점수", "%.1f".format(r.avgScore), null, null),
    StatCell("최고 점수", "${r.best}점", null, null),
    StatCell("최다 연승", "${r.bestStreak}연승", null, null),
    StatCell("요트", "%.0f%%".format(100.0 * r.yachts / r.games), null, null),
    StatCell("보너스", "%.0f%%".format(100.0 * r.bonuses / r.games), null, null),
)

/** Stat cells three to a row: label, value, and its place in the league. */
@Composable
private fun CellGrid(cells: List<StatCell>) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in cells.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (cell in row) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(cell.label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                        Text(cell.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cell.rank?.let { "리그 ${it}위" } ?: " ", style = MaterialTheme.typography.labelSmall, color = if (cell.rank != null && cell.rank <= 3) cs.primary else cs.onSurfaceVariant)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 칸별 평균: the 12 boxes, the upper total and the bonus, with the league's difference and the
 * zero rate (an 84 dp label column and three numbers, or "막대로 보기": the player's bar over the league's).
 */
@Composable
private fun BoxTable(p: PlayerPage) {
    val cs = MaterialTheme.colorScheme
    var bars by remember(p.profile.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🎲 칸별 평균", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(if (bars) "표로 보기" else "막대로 보기", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { bars = !bars }.padding(4.dp))
    }
    if (bars) {
        for (b in p.box) {
            val top = maxOf(b.avg, b.league).takeIf { it > 0 } ?: 1.0
            BarRow(b.category.label, b.avg, top / 0.9, "%.1f".format(b.avg))
            BarRow("  리그", b.league, top / 0.9, "%.1f".format(b.league), cs.outline)
        }
        return
    }
    val num = MaterialTheme.typography.labelMedium
    @Composable
    fun line(label: String, avg: String, diff: String, zero: String, tint: Color = Color.Unspecified, bold: Boolean = false, head: Boolean = false) {
        val weight = if (bold || head) FontWeight.Bold else FontWeight.Normal
        val color = if (head) cs.onSurfaceVariant else Color.Unspecified
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = num, fontWeight = weight, color = color, maxLines = 1, modifier = Modifier.width(84.dp))
            Text(avg, style = num, fontWeight = weight, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(diff, style = num, fontWeight = weight, color = if (head) color else tint, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(zero, style = num, fontWeight = weight, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
    val good = gameColors.good
    fun diffColor(d: Double) = when {
        d.isNaN() || Math.abs(d) < 0.05 -> Color.Unspecified
        d > 0 -> good
        else -> cs.error
    }
    line("칸", "평균", "리그 대비", "0점", head = true)
    for (b in p.box) {
        val d = b.avg - b.league
        line(b.category.label, fmt(b.avg, "%.1f"), signed(d, 1), fmt(100 * b.zeroRate, "%.0f%%"), diffColor(d))
        if (b.category == Category.SIXES) {
            val (u, lu) = p.upper
            line("윗칸 합계", fmt(u, "%.1f"), signed(u - lu, 1), "–", diffColor(u - lu), bold = true)
            val (bo, lb) = p.bonus
            line("보너스", fmt(100 * bo, "%.0f%%"), signed(100 * (bo - lb), 0, "%p"), "–", diffColor(100 * (bo - lb)), bold = true)
        }
    }
}

private fun fmt(x: Double, f: String) = if (x.isNaN()) "–" else f.format(x)

/** [x] with its sign and [digits] decimals, and no sign when it rounds to zero ("+1.4", "-0.3", "0.0"). */
private fun signed(x: Double, digits: Int, unit: String = ""): String {
    if (x.isNaN()) return "–"
    val s = "%.${digits}f".format(Math.abs(x))
    val sign = if (s.all { it == '0' || it == '.' }) "" else if (x > 0) "+" else "-"
    return sign + s + unit
}

/** 시즌별 기록: one row a season (newest first) and the rating at each season's end as a line, title seasons ringed. */
@Composable
private fun SeasonLines(p: PlayerPage) {
    val lines = p.profile.lines
    if (lines.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    SectionLabel("📅 시즌별 기록")
    val rated = lines.filter { !it.rating.isNaN() }
    if (rated.size >= 2) {
        val titled = p.profile.honours.filter { HonourBook.isTitle(it.kind) }.map { it.season }.toSet()
        LineChart(
            rated.map { it.rating }, labels = "시즌 ${rated.first().season}" to "시즌 ${rated.last().season}",
            mark = rated.withIndex().filter { it.value.season in titled }.map { it.index }.toSet(),
        )
    }
    val clubs = lines.any { it.club != null }
    val num = MaterialTheme.typography.labelSmall
    @Composable
    fun row(season: String, where: String, place: String, record: String, rating: String, club: String?, head: Boolean = false) {
        val color = if (head) cs.onSurfaceVariant else Color.Unspecified
        val weight = if (head) FontWeight.Bold else FontWeight.Normal
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(season, style = num, color = color, fontWeight = weight, modifier = Modifier.width(40.dp))
            Text(where, style = num, color = color, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f))
            Text(place, style = num, color = color, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
            Text(record, style = num, color = color, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
            Text(rating, style = num, color = color, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            if (club != null) Text(club, style = num, color = color, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
    row("시즌", "리그·부", "순위", "승-무-패", "레이팅", if (clubs) "클럽" else null, head = true)
    for (l in lines.asReversed().take(SEASON_ROWS)) {
        row(
            "${l.season}", LeagueIds.division(l.league, l.division), "${l.position}위", "${l.wins}-${l.draws}-${l.losses}",
            if (l.rating.isNaN()) "–" else "%.0f".format(l.rating), if (clubs) l.club?.let { p.clubNames[it] ?: it } ?: "–" else null,
        )
    }
    if (lines.size > SEASON_ROWS) Text("최근 ${SEASON_ROWS}시즌만 보여요 (모두 ${lines.size}시즌).", style = num, color = cs.onSurfaceVariant)
}

// ---- 경기 ----------------------------------------------------------------------------------------

private enum class MatchFilter(val label: String) { ALL("전체"), LEAGUE("리그"), COMPS("대회") }

/**
 * 경기: 전체 / 리그 / 대회, this season's league matches by week (a tap: the match page) and its
 * knockout ties, then its highlights and the news about it (3, "더 보기" for the rest).
 */
@Composable
private fun ColumnScope.MatchesTab(lg: LeagueController, v: LeagueView, p: PlayerPage, open: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val nav = lg.nav
    var filter by remember(p.profile.key) { mutableStateOf(MatchFilter.ALL) }
    val league = p.matches.filter { it.comp == Comp.LEAGUE }
    val ties = p.matches.filter { it.comp != Comp.LEAGUE }
    if (p.matches.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in MatchFilter.entries) FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label, fontSize = 13.sp) })
        }
    }
    val shownLeague = if (filter == MatchFilter.COMPS) emptyList() else league
    val shownTies = if (filter == MatchFilter.LEAGUE) emptyList() else ties
    if (shownLeague.isEmpty() && shownTies.isEmpty()) {
        val none = when {
            p.profile.retired != null -> if (p.highlights.isEmpty()) "은퇴한 선수예요." else "은퇴한 선수예요. 남은 하이라이트를 볼 수 있어요."
            p.profile.key == Ladder.ME -> "리그 경기에는 나가지 않아요."
            filter == MatchFilter.LEAGUE -> "이번 시즌 치른 리그 경기가 아직 없어요."
            filter == MatchFilter.COMPS -> "이번 시즌 대회 토너먼트에서 치른 경기가 아직 없어요."
            else -> "이번 시즌 치른 경기가 아직 없어요."
        }
        Text(none, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    for (m in shownLeague) MatchRow(m, "${m.week}주차 · ${compIcon(Comp.LEAGUE)} ${m.where}") { openMatch(lg, p, m.ref, m.week) }
    if (shownTies.isNotEmpty()) {
        SectionLabel("🏆 대회 토너먼트")
        for (m in shownTies) MatchRow(m, "${m.comp?.let { compIcon(it) + " " } ?: ""}${m.where}", null)
    }
    if (p.highlights.isNotEmpty()) {
        SectionLabel("✨ 하이라이트")
        for (h in p.highlights) HighlightCard(lg.name(h.a), lg.name(h.b), h, "리포트 ›") { nav.open(Page.Match(h.id)) }
    }
    if (p.news.isNotEmpty()) {
        SectionLabel("📰 뉴스")
        var all by remember(p.profile.key) { mutableStateOf(false) }
        for (a in if (all) p.news else p.news.take(NEWS_FIRST)) ArticleCard(a, v.clock, open)
        if (p.news.size > NEWS_FIRST) {
            Text(
                if (all) "접기 ▴" else "더 보기 (${p.news.size - NEWS_FIRST}) ▾", style = MaterialTheme.typography.labelMedium, color = cs.primary,
                modifier = Modifier.clickable { all = !all }.padding(vertical = 2.dp),
            )
        }
    }
}

/** A match of the list: [where] above, the opponent and the result with its score below; [onClick] null for one that cannot be opened. */
@Composable
private fun MatchRow(m: PlayerMatch, where: String, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val (word, color) = when (m.result) {
        1 -> "승" to gameColors.good
        -1 -> "패" to cs.error
        0 -> "무" to cs.onSurfaceVariant
        else -> "–" to cs.onSurfaceVariant
    }
    Card(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(where, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("vs ${m.opponentName}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text(word, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
                }
                Text("  ${m.score}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- 수상 ----------------------------------------------------------------------------------------

/**
 * 수상: titles (major ones counted), awards, runners-up and places, 이 주의 선수, milestones, the
 * national team, records held (a tap: 기록실), the hall of fame and its dynasties.
 */
@Composable
private fun ColumnScope.HonoursTab(lg: LeagueController, p: PlayerPage) {
    val cs = MaterialTheme.colorScheme
    val nav = lg.nav
    val profile = p.profile
    val shown = profile.honours.filter { trophy(it.kind) != null && it.kind != POTW }
    val titles = shown.filter { HonourBook.isTitle(it.kind) }
    val awards = shown.filter { it !in titles && (Awards.isAward(it.kind) || it.kind in OTHER_AWARDS) }
    val places = shown.filter { it !in titles && it !in awards }
    val weeks = profile.honours.count { it.kind == POTW }
    var any = false
    if (titles.isNotEmpty()) {
        any = true
        SectionLabel("🏆 우승 · 주요 우승 ${titles.count { HonourBook.isMajor(it) }}회 · 전체 ${profile.titles}회")
        for (h in titles) HonourLine("${trophy(h.kind)} 시즌 ${h.season} · ${honourLabel(h.kind)}", HonourBook.isMajor(h))
    }
    if (awards.isNotEmpty()) {
        any = true
        SectionLabel("🏅 수상")
        for (h in awards) HonourLine("${trophy(h.kind)} 시즌 ${h.season} · ${honourLabel(h.kind)}")
    }
    if (places.isNotEmpty()) {
        any = true
        SectionLabel("🥈 준우승·입상")
        for (h in places) HonourLine("${trophy(h.kind)} 시즌 ${h.season} · ${honourLabel(h.kind)}")
    }
    if (weeks > 0) {
        any = true
        SectionLabel("⭐ 이 주의 선수 ${weeks}회")
    }
    if (p.milestones.isNotEmpty()) {
        any = true
        SectionLabel("🎯 이정표")
        for (e in p.milestones.take(MILESTONES)) HonourLine("시즌 ${e.season} · ${milestoneText(e.args)}")
    }
    profile.identity?.takeIf { it.caps > 0 }?.let { id ->
        any = true
        SectionLabel("🎽 국가대표")
        HonourLine("${id.flag} ${id.nation} 대표 ${id.caps}회 선발" + if (id.capTitles > 0) " · 국가대항전 우승 ${id.capTitles}회" else "")
    }
    if (p.records.isNotEmpty()) {
        any = true
        SectionLabel("📜 보유 기록")
        for (r in p.records) {
            Text(
                "${r.kind.label} · ${r.kind.text(r.value)} (시즌 ${r.season}) ›", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().clickable {
                    nav.go(LeagueTab.RECORDS)
                    nav.records = RecordsView.ROOM
                }.padding(vertical = 2.dp),
            )
        }
    }
    if (profile.hallOfFame || p.dynasties.isNotEmpty()) {
        any = true
        SectionLabel("🏛 명예의 전당")
        if (profile.hallOfFame) HonourLine("명예의 전당에 올랐어요.")
        for (d in p.dynasties) HonourLine("👑 " + dynastyText(d))
    }
    if (!any) Text("아직 우승이나 수상 기록이 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
}

@Composable
private fun HonourLine(text: String, strong: Boolean = false) =
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)

/** A dynasty in words: "요트컵 3연패 (시즌 4–6)", "더블 (시즌 7)". */
private fun dynastyText(d: Dynasty): String = when (d.kind) {
    "RUN" -> "${honourLabel(d.title)} ${d.count}연패 (시즌 ${d.from}–${d.to})"
    "DOUBLE" -> "더블 (리그·요트컵, 시즌 ${d.to})"
    "TREBLE" -> "트레블 (리그·요트컵·챔스, 시즌 ${d.to})"
    "UNBEATEN" -> "${honourLabel(d.title).removeSuffix(" 우승")} 무패 시즌 (시즌 ${d.to})"
    else -> d.kind
}

// ---- shared parts --------------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)

/** The origins of league players with genes (generated, evolved, made). */
private val MADE = setOf(Origin.GENERATED, Origin.EVOLVED, Origin.CREATED)

/** 이 주의 선수 (counted, not listed), and the awards that are not [Awards.isAward]'s (득점왕, 최다 요트상, the world cup's). */
private const val POTW = "POTW"
private val OTHER_AWARDS = setOf("SCORER", "YACHT", "WCMVP", "WCTOP")

/** Cells shown before "기록 더 보기", rating points in the line, season rows, articles before "더 보기", milestones listed. */
private const val FIRST_CELLS = 6
private const val RATING_POINTS = 40
private const val SEASON_ROWS = 12
private const val NEWS_FIRST = 3
private const val MILESTONES = 20

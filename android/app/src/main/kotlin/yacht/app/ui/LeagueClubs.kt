package yacht.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.ClubBadge
import yacht.app.core.ClubStandingRow
import yacht.app.core.ClubsView
import yacht.app.core.LeagueController
import yacht.app.core.LeagueSelector
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.SeasonView
import yacht.engine.league.Club
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubTable
import yacht.engine.league.Comp
import yacht.engine.league.Nations
import yacht.engine.league.Emblem
import yacht.engine.league.MatchFormat
import yacht.engine.league.Money
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A club's badge: shield, circle, diamond, hexagon, star or square, two colours and the initial. */
@Composable
fun ClubEmblem(e: Emblem, size: Dp = 28.dp) {
    val c1 = Color(e.colour1)
    val c2 = Color(e.colour2)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val path = when (e.shape % Emblem.SHAPES) {
                0 -> Path().apply { // shield
                    moveTo(0.1f * w, 0.08f * h); lineTo(0.9f * w, 0.08f * h); lineTo(0.9f * w, 0.5f * h)
                    quadraticBezierTo(0.9f * w, 0.85f * h, 0.5f * w, h); quadraticBezierTo(0.1f * w, 0.85f * h, 0.1f * w, 0.5f * h); close()
                }
                1 -> Path().apply { addOval(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)) }
                2 -> Path().apply { moveTo(w / 2, 0f); lineTo(w, h / 2); lineTo(w / 2, h); lineTo(0f, h / 2); close() }
                3 -> polygon(6, w, h, 0.0)
                4 -> star(w, h)
                else -> Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, w * 0.18f, h * 0.18f)) }
            }
            drawPath(path, c1)
            drawPath(path, c2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.09f))
        }
        Text(e.initial.toString(), color = c2, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp)
    }
}

private fun polygon(n: Int, w: Float, h: Float, turn: Double): Path = Path().apply {
    for (k in 0 until n) {
        val a = turn + 2 * PI * k / n - PI / 2
        val x = w / 2 + (w / 2) * cos(a).toFloat()
        val y = h / 2 + (h / 2) * sin(a).toFloat()
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun star(w: Float, h: Float): Path = Path().apply {
    for (k in 0 until 10) {
        val r = if (k % 2 == 0) 0.5f else 0.24f
        val a = PI * k / 5 - PI / 2
        val x = w / 2 + w * r * cos(a).toFloat()
        val y = h / 2 + h * r * sin(a).toFloat()
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * 순위 › 클럽: in national leagues the shown league's club table ([NationalClubsTab]); a legacy
 * season's club league as before ([LegacyClubsTab]).
 */
@Composable
fun ClubsTab(lg: LeagueController, v: LeagueView) {
    val season = v.season
    when {
        season?.national == true && v.clubs == null -> NationalClubsTab(lg, v, season)
        // No club league to show while the rules are national leagues': before the first season,
        // or a season of the old single league without one (the clubs come with the next).
        v.clubs == null && lg.config.national -> CenterText(
            (if (season == null) "▶ 리그를 시작하면" else "이번 시즌에는 클럽 리그가 없어요. 다음 시즌부터") +
                " 선수마다 자기가 뛰는 나라의 클럽에 들어가요. 클럽 순위는 클럽마다 성적이 좋은 선수 ${ClubTable.COUNTED}명의 리그 승점과 수상 점수로 매기고, 시즌이 끝나면 이적 시장이 열려요.",
        )
        else -> LegacyClubsTab(lg, v.clubs, season?.format)
    }
}

/**
 * A legacy season's club league: this season's table (a row opens [Page.Club]), every club by its
 * titles (folded), and "이적·드래프트 ›" ([Page.Market]). [format] is the season's match rule (how
 * a board is decided), null when not known.
 */
@Composable
private fun LegacyClubsTab(lg: LeagueController, v: ClubsView?, format: MatchFormat? = null) {
    if (v == null || v.clubs.isEmpty()) {
        CenterText("클럽 리그는 시즌이 시작될 때 만들어져요. 클럽마다 AI 여섯 명이 모여 다섯 보드 단체전을 치르고, 시즌이 끝나면 상금으로 이적 시장과 드래프트를 열어요. (규칙에서 클럽 수를 바꾸거나 끌 수 있어요)")
        return
    }
    val nav = lg.nav
    var all by remember { mutableStateOf(false) }
    val byId = v.clubs.associateBy { it.id }
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("클럽 순위", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium) }
        if (v.table.isEmpty()) {
            item { Text("이번 시즌에는 클럽 리그가 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        } else {
            item {
                val played = v.matches.count { it.result != null }
                // Each board is a match under the season's rule; level boards go to the totals unless 판 승점제.
                val board = when (format) {
                    MatchFormat.SINGLE -> "보드마다 한 판"
                    MatchFormat.BEST_OF_3 -> "보드마다 같은 주사위 두 판 (비기면 세 번째 판)"
                    MatchFormat.GAMES -> "보드마다 같은 주사위 두 판 (판 승점: 1승 1패면 그 보드는 무승부)"
                    else -> "보드마다 같은 주사위 두 판"
                }
                val level = if (format?.totals == true) ", 이긴 보드 수가 같으면 합계 점수로" else ""
                Text("단체전: $board, 이긴 보드가 많은 클럽이 승리$level (승 3·무 1). $played/${v.matches.size}경기", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            itemsIndexed(v.table, key = { _, r -> "ct-${r.club}" }) { i, r ->
                val club = byId[r.club]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable { nav.open(Page.Club(r.club)) }.padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", Modifier.width(22.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    club?.let { ClubEmblem(it.emblem, 22.dp) }
                    Text(" " + (club?.name ?: r.club), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.wins}-${r.draws}-${r.losses}", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                    Text("%+d".format(r.boardDiff), Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                    Text("${r.points}", Modifier.width(32.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
                }
            }
        }
        item {
            Column(Modifier.padding(top = 6.dp)) {
                LinkLine(if (all) "클럽 전체 (우승 순) 접기" else "클럽 전체 (우승 순)", "${v.clubs.size}개 클럽의 우승과 자금") { all = !all }
                LinkLine("이적·드래프트", "시즌이 끝나면 상금으로 열리는 이적 시장과 드래프트") { nav.open(Page.Market()) }
            }
        }
        if (all) {
            items(v.clubs.sortedByDescending { it.honours * 1000 + it.budget }, key = { "cc-${it.id}" }) { c ->
                Card(Modifier.fillMaxWidth().clickable { nav.open(Page.Club(c.id)) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ClubEmblem(c.emblem, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Bold)
                            Text(c.squad.joinToString(", ") { lg.name(it) }, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("💰 ${c.budget}", style = MaterialTheme.typography.labelMedium)
                            if (c.honours > 0) Text("🏆 ${c.honours}", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                        }
                    }
                }
            }
        }
    }
}

/** A legacy club league's transfer window and draft, newest first. */
@Composable
internal fun LegacyMarket(lg: LeagueController, v: ClubsView?) {
    val moves = v?.moves.orEmpty()
    if (moves.isEmpty()) {
        CenterText("시즌이 끝나면 상금을 받고 이적 시장과 드래프트가 열려요.")
        return
    }
    val byId = v?.clubs.orEmpty().associateBy { it.id }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(moves, key = { m -> "mv-${m.season}-${m.kind}-${m.ai}-${m.from}-${m.to}" }) { m -> MoveLine(lg, m, byId) }
    }
}

@Composable
private fun MoveLine(lg: LeagueController, m: ClubMove, byId: Map<String, Club>) = MoveText(lg, m) { id -> byId[id]?.name ?: id }

/** A legacy club league's club: its badge and standing, its players (this season's lineup first), recent club matches and moves. */
@Composable
internal fun LegacyClubPage(lg: LeagueController, v: ClubsView?, id: String, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val c = v?.clubs?.firstOrNull { it.id == id }
    if (v == null || c == null) {
        CenterText("이 클럽은 이제 리그에 없어요.")
        return
    }
    val row = v.table.indexOfFirst { it.club == c.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClubEmblem(c.emblem, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(c.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        if (row >= 0) "이번 시즌 ${row + 1}위" else null,
                        "자금 ${c.budget}",
                        if (c.honours > 0) "우승 ${c.honours}회" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        // This season's lineup as it plays (fixed at the season's start), then the bench;
        // without a club league this season, the squad by rating.
        val lineup = v.lineups[c.id].orEmpty().filter { it in c.squad }
        val ratings = lg.view?.rows.orEmpty().associate { it.key to (it.rating ?: Int.MIN_VALUE) }
        val bench = c.squad.filter { it !in lineup }.sortedByDescending { ratings[it] ?: Int.MIN_VALUE }
        Text(
            if (lineup.isNotEmpty()) "선수 (이번 시즌 출전 순서: 1~${lineup.size}번 보드, 나머지는 후보)" else "선수 (레이팅 순)",
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
        )
        for ((i, k) in (lineup + bench).withIndex()) {
            Text(
                "${i + 1}. ${lg.name(k)}" + if (lineup.isNotEmpty() && i >= lineup.size) " (후보)" else "",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable { onOpen(k) }.padding(vertical = 3.dp),
            )
        }
        val games = v.matches.filter { (it.a == c.id || it.b == c.id) && it.result != null }.takeLast(6)
        if (games.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("최근 경기", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            for (g in games) {
                val r = g.result!!
                val home = g.a == c.id
                val other = v.clubs.firstOrNull { it.id == if (home) g.b else g.a }?.name ?: "?"
                val (w, l) = if (home) r.won to r.lost else r.lost to r.won
                val res = (if (home) r.winner else -r.winner).let { if (it > 0) "승" else if (it < 0) "패" else "무" }
                Text("$res  $other  보드 $w:$l", style = MaterialTheme.typography.bodySmall)
            }
        }
        val moves = v.moves.filter { it.from == c.id || it.to == c.id }.take(10)
        if (moves.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("이적·드래프트", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            val byId = v.clubs.associateBy { it.id }
            for (m in moves) MoveLine(lg, m, byId)
        }
    }
}

// ---- national clubs ----------------------------------------------------------------------------

/** League fixtures between two loads of a club page or table while the league plays. */
internal const val CLUB_REFRESH = 200

/** A club's badge in a line of names ([size] high), nothing without one. */
@Composable
internal fun ClubMark(badge: ClubBadge?, size: Dp = 16.dp) {
    if (badge == null) return
    ClubEmblem(badge.emblem, size)
    Spacer(Modifier.width(4.dp))
}

/**
 * 순위 › 클럽 of a national league (UX 4.3): the league selector's header, the club table (F1
 * constructors' style: points of each club's best 6 members, its members' results this week; a
 * row opens [Page.Club]), every club of the league's nations by titles (folded), "이적·드래프트 ›"
 * and the 클럽 대항전 when it runs.
 */
@Composable
private fun NationalClubsTab(lg: LeagueController, v: LeagueView, season: SeasonView) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    val shown = season.league
    androidx.compose.runtime.LaunchedEffect(shown, season.number, season.played / CLUB_REFRESH) { lg.worldPages.loadClubTable(shown) }
    val t = lg.worldPages.clubTable?.takeIf { it.league == shown && it.season == season.number }
    var all by remember { mutableStateOf(false) }
    val favs = lg.favourites
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { LeagueHeader(lg, season) }
        item {
            Text("클럽 순위", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            Text(
                "클럽마다 성적이 좋은 선수 ${ClubTable.COUNTED}명의 리그 승점(1부 1.0배, 2부 0.6배, 3부 0.4배 …)과 수상 점수를 더해요. 선수는 모두 한 클럽에 속해요.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        if (t == null) {
            item { Text("클럽 순위를 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        } else {
            item {
                Row(Modifier.padding(horizontal = 6.dp)) {
                    Text("#", Modifier.width(22.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text("클럽", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text("이번 주", Modifier.width(78.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                    Text("점수", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                }
            }
            // With a club world, a club whose fans are angry is marked 😡 (UX 4.6).
            val angry = t.all.filter { it.angry }.mapTo(HashSet()) { it.badge.id }
            items(t.rows, key = { "nct-${it.badge.id}" }) { r ->
                ClubTableRow(r, LeagueSelector.CLUB + r.badge.id in favs, r.badge.id in angry) { nav.open(Page.Club(r.badge.id)) }
            }
            if (t.rows.isEmpty()) item { Text("이 리그에는 아직 클럽이 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        item {
            Column(Modifier.padding(top = 6.dp)) {
                LinkLine(if (all) "클럽 전체 (우승 순) 접기" else "클럽 전체 (우승 순)", t?.let { "${it.all.size}개 클럽의 우승과 자금" }) { all = !all }
                // With a club world shown the market is the 이적 센터 (UX 4.6), and the club rankings come with it
                // (rule 4: no link before its page has content).
                val world = t?.all?.any { it.health != null } == true
                if (world) {
                    LinkLine("💸 이적 센터", "이적설과 협상, 이적 내역, 최고 이적료, 감독 이동") { nav.open(Page.Market()) }
                } else {
                    LinkLine("이적·드래프트", "해외 이적, 귀국, 국내 이적과 드래프트") { nav.open(Page.Market()) }
                }
                if (world) LinkLine("🏅 세계 클럽 랭킹", "가치·성적·수입·인기·재정으로 본 클럽 순위") { nav.open(Page.ClubRanking()) }
                if (v.comps.any { it.first == Comp.CLUB_CUP }) LinkLine("🏟 ${Comp.CLUB_CUP.label}", "나라마다 우승 클럽이 겨루는 단체전") { nav.open(Page.Competition(Comp.CLUB_CUP)) }
            }
        }
        if (all && t != null) {
            items(t.all, key = { "nca-${it.badge.id}" }) { c ->
                Card(Modifier.fillMaxWidth().clickable { nav.open(Page.Club(c.badge.id)) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ClubEmblem(c.badge.emblem, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.badge.name, fontWeight = FontWeight.Bold)
                            Text("${Nations.of(c.badge.nation)?.let { "${it.flag} ${it.short}" } ?: c.badge.nation} · 선수 ${c.members}명", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            // With a club world its money as it is and its health grade; else today's budget.
                            Text(c.health?.let { g -> "💰 ${Money.text(c.balance)} · $g" } ?: "💰 ${c.budget}", style = MaterialTheme.typography.labelMedium)
                            if (c.titles > 0) Text("🏆 ${c.titles}", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                        }
                    }
                }
            }
        }
    }
}

/** A club's line in the club table: place, badge (😡 after it when its fans are [angry]) and name (★ when followed), its members' week, its points. */
@Composable
private fun ClubTableRow(r: ClubStandingRow, followed: Boolean, angry: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${r.position}", Modifier.width(22.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        ClubEmblem(r.badge.emblem, 22.dp)
        Text(
            (if (angry) "😡" else "") + " " + (if (followed) "★ " else "") + r.badge.name, Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(r.week.ifEmpty { "–" }, Modifier.width(78.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1)
        Text(clubPoints(r.points), Modifier.width(48.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
}

/** Points as the club table keeps them (to 0.1): "60" or "60.4". */
internal fun clubPoints(x: Double): String = if (x == Math.floor(x)) "%.0f".format(x) else "%.1f".format(x)

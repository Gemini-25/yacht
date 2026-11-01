package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import yacht.app.core.ClubBadge
import yacht.app.core.FeeLine
import yacht.app.core.InfoTopic
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.MarketPages
import yacht.app.core.MarketTab
import yacht.app.core.MarketView
import yacht.app.core.Page
import yacht.app.core.PageChips
import yacht.app.core.StoryView
import yacht.engine.explain.Ko
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubMoveKind
import yacht.engine.league.ClubMoveType
import yacht.engine.league.LeagueIds
import yacht.engine.league.Money
import yacht.engine.league.Nations
import yacht.engine.league.StoryStage

// 이적 센터 (UX 6): the page's body (from `lg.marketPages`) and a move's row, which the club pages
// list too. Without a club world (a legacy season, a save not yet migrated, `clubWorld` off) the
// page is today's 이적·드래프트; with one, its four tabs 이적설 · 이적 내역 · 최고 이적료 · 감독. The
// texts with names in them are MarketTexts' functions (StoryTextsTest reads them).

/** [Page.Market]: the national clubs' moves ([NationalMarket]) or the 이적 센터, or a legacy club league's window and draft. */
@Composable
internal fun MarketBody(lg: LeagueController, v: LeagueView, page: Page.Market) {
    if (v.clubs != null || v.badges.isEmpty()) LegacyMarket(lg, v.clubs) else NationalMarket(lg, v, page)
}

/**
 * [Page.Market] of national clubs: with a club world shown the 이적 센터 ([TransferCentre]); else the
 * last window in numbers, then every kept move, newest first (해외 이적, 귀국, 유스 입단, 국내 이적,
 * 드래프트, 방출, 은퇴). Loaded again when the week moves on.
 */
@Composable
private fun NationalMarket(lg: LeagueController, v: LeagueView, page: Page.Market) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(v.season?.number, v.clock?.week) { lg.marketPages.load() }
    val m = lg.marketPages.view
    if (m == null) {
        CenterText("이적 기록을 불러오는 중…")
        return
    }
    if (m.centre) {
        TransferCentre(lg, v, page, m)
        return
    }
    if (m.moves.isEmpty()) {
        CenterText("시즌이 끝나면 클럽마다 이적 시장이 열리고, 강한 선수는 더 강한 리그의 클럽으로 해외 이적을 가요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        m.digest?.let { d ->
            item {
                Text(
                    "시즌 ${d.season} 이적 시장: 신인 ${d.rookies}명 · 국내 이적 ${d.domestic}건 · 해외 이적 ${d.abroad}건 · 귀국 ${d.returns}건",
                    style = MaterialTheme.typography.labelMedium, color = cs.secondary,
                )
            }
        }
        items(m.moves, key = { x -> "nm-${x.season}-${x.type}-${x.ai}-${x.from}-${x.to}" }) { x -> MoveText(lg, x) { id -> v.badges[id]?.name ?: id } }
    }
}

/**
 * One move of a club's history: its type ([ClubMoveType.label]), the player, the clubs by [clubName].
 * With [money] (a club world's moves: fees in [Money] units) the fee reads as money and the club
 * world's move types and [flags] get their marks (UX 6.3: 💰 기록, ⚔ 라이벌, ⏰ 마감일, 🔓 바이아웃, …);
 * without, today's line. [highlight]: the replay's newest move.
 */
@Composable
internal fun MoveText(
    lg: LeagueController, m: ClubMove, money: Boolean = false, flags: String = "", highlight: Boolean = false, clubName: (String) -> String,
) {
    val cs = MaterialTheme.colorScheme
    val from = m.from?.let(clubName)
    val to = m.to?.let(clubName)
    val who = lg.name(m.ai)
    val text = if (money) MarketTexts.move(m.type, who, from, to, m.amount, flags) else when (m.type) {
        ClubMoveType.ABROAD -> "✈️ $who: $from → $to (해외 이적 · 이적료 ${m.amount})"
        ClubMoveType.HOME -> "🏠 $who: $from → $to (귀국)"
        ClubMoveType.YOUTH -> "🌱 $who: $to 유스 입단"
        else -> when (m.kind) {
            ClubMoveKind.TRANSFER -> "🔁 $who: ${from ?: "자유 계약"} → $to (이적료 ${m.amount})"
            ClubMoveKind.DRAFT -> "📝 $who: $to 드래프트 ${m.amount}순위"
            ClubMoveKind.RELEASED -> "👋 $who: ${from}에서 방출"
            ClubMoveKind.RETIRED -> "🌅 $who: $from 떠남 (은퇴)"
        }
    }
    val row = Modifier.fillMaxWidth().let { if (highlight) it.clip(RoundedCornerShape(6.dp)).background(cs.tertiaryContainer) else it }
    Row(row.padding(vertical = 2.dp)) {
        Text("S${m.season}", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- 이적 센터 ----------------------------------------------------------------------------------------

/**
 * 이적 센터 (UX 6.1): the scrolling chip row of [MarketTab] and the tab's list; a page opened without
 * a tab lands on 이적 내역 before the rumour weeks and on 이적설 from them ([MarketPages.tabOf]).
 */
@Composable
private fun TransferCentre(lg: LeagueController, v: LeagueView, page: Page.Market, m: MarketView) {
    val nav = lg.nav
    val tab = MarketPages.tabOf(page, m)
    Column(Modifier.fillMaxSize()) {
        ChipRow(nav.chipState(PageChips.MARKET), MarketTab.entries, tab, { it.label }) { nav.replaceTop(page.copy(tab = it, replay = -1)) }
        Box(Modifier.weight(1f)) {
            when (tab) {
                MarketTab.LIVE -> LiveTab(lg, m)
                MarketTab.MOVES -> if (page.replay >= 0 && m.window.isNotEmpty()) Replay(lg, v, page, m) else MovesTab(lg, v, page, m)
                MarketTab.RECORDS -> RecordsTab(lg, m)
                MarketTab.COACHES -> CoachesTab(lg, m)
            }
        }
    }
}

/** The list every tab draws in. */
@Composable
private fun CentreList(content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

// ---- 이적설 --------------------------------------------------------------------------------------------

/**
 * 이적설 (UX 6.2): the window line (quiet, the winter window open, the rumour weeks), 📝 협상 중 and 💬 소문
 * (followed stories first), and the last windows' results of the stories that reached talks.
 */
@Composable
private fun LiveTab(lg: LeagueController, m: MarketView) {
    val nav = lg.nav
    val talks = m.live.filter { it.stage == StoryStage.TALKS }
    val rumours = m.live.filter { it.stage == StoryStage.RUMOUR }
    CentreList {
        item { Text(MarketTexts.windowLine(m.week, m.rumoursFrom, m.winterWeek), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
        // The first story shown: a first-screen block (UX 15.2).
        val first = (talks + rumours).firstOrNull()
        for ((head, list) in listOf("📝 협상 중 (${talks.size})" to talks, "💬 소문 (${rumours.size})" to rumours)) {
            if (list.isEmpty()) continue
            item(key = "h-$head") { Heading(head) }
            items(list, key = { "s-${it.id}" }) { s ->
                StoryRow(s, if (s === first) Modifier.shotSection(MARKET_SECTION) else Modifier) { nav.open(Page.Player(s.key)) }
            }
        }
        if (m.live.isEmpty()) item { Small("아직 이적설이 없어요.") }
        // The last windows' results of the stories that reached talks: this season's winter, then last summer.
        for ((head, list) in listOf("❄ 겨울 이적시장 결과" to m.ended.filter { it.winter }, "지난 여름 이적시장 결과" to m.ended.filter { !it.winter })) {
            if (list.isEmpty()) continue
            item(key = "h-$head") { Heading(head) }
            items(list, key = { "e-${it.id}" }) { s ->
                Line(MarketTexts.outcome(s.stage, s.shown, s.buyer?.name, s.by?.name, Money.text(s.fee), s.reason), WRAP) { nav.open(Page.Player(s.key)) }
            }
        }
    }
}

/**
 * A story's two lines (UX 6.2, 40 dp): ★ when followed, the player and the clubs (long names wrap to a second
 * line), the fee on the right (never cut); its weeks and marks.
 */
@Composable
private fun StoryRow(s: StoryView, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MarketTexts.storyLine(s.followed, s.shown, s.seller?.name, s.buyer?.name), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, maxLines = WRAP, overflow = TextOverflow.Ellipsis,
            )
            Text(Money.text(s.fee), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        Text(MarketTexts.storyWeeks(s.rumourWeek, s.talksWeek, s.rival, s.winter), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

// ---- 이적 내역 --------------------------------------------------------------------------------------------

/**
 * 이적 내역 (UX 6.3): "☀ 이적시장 다시 보기" (the newest window move by move), then the kept moves newest
 * first under their windows' headings (시즌 {s} 여름 / 겨울 이적시장), the newest summer with its numbers.
 */
@Composable
private fun MovesTab(lg: LeagueController, v: LeagueView, page: Page.Market, m: MarketView) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    val name: (String) -> String = { id -> v.badges[id]?.name ?: id }
    CentreList {
        if (m.window.isNotEmpty()) {
            item {
                TextButton(onClick = {
                    lg.marketPages.replayPaused = false
                    nav.replaceTop(page.copy(tab = MarketTab.MOVES, replay = 1))
                }) { Text("☀ 이적시장 다시 보기 (시즌 ${m.windowSeason} 여름 ${m.window.size}건)") }
            }
        }
        if (m.moves.isEmpty()) item { Small("시즌이 끝나면 클럽마다 이적 시장이 열려요. 겨울에는 시즌 중간에 한 번 더 열려요.") }
        var at: Pair<Int, Boolean>? = null
        // Keys by content (the list grows at its top as windows close), a repeated move numbered.
        val seen = HashMap<String, Int>()
        for ((i, x) in m.moves.withIndex()) {
            val winter = i in m.winterMoves
            if (at != (x.season to winter)) {
                at = x.season to winter
                item(key = "mh-${x.season}-$winter") {
                    Heading(MarketTexts.windowHeading(x.season, winter))
                    m.digest?.takeIf { !winter && it.season == x.season }?.let { d ->
                        Text(
                            "신인 ${d.rookies}명 · 국내 이적 ${d.domestic}건 · 해외 이적 ${d.abroad}건 · 귀국 ${d.returns}건",
                            style = MaterialTheme.typography.labelSmall, color = cs.secondary,
                        )
                    }
                }
            }
            val key = "mv-${x.season}-${x.type.code}-${x.ai}-${x.from}-${x.to}"
            val n = seen.merge(key, 1, Int::plus)!!
            item(key = "$key-$n") { MoveText(lg, x, money = true, flags = m.flags[i].orEmpty(), clubName = name) }
        }
    }
}

/**
 * ☀ 이적시장 다시 보기 (UX 6.3): the newest off-season window move by move in its own order, one more
 * every [REPLAY_MS] (the page's `replay`, the moves shown), each round's name above its first move, the
 * newest highlighted; "⏸ 멈추기" / "▶ 이어 보기" and "모두 보기" (back to the whole list).
 */
@Composable
private fun Replay(lg: LeagueController, v: LeagueView, page: Page.Market, m: MarketView) {
    val nav = lg.nav
    val shown = page.replay.coerceIn(0, m.window.size)
    val paused = lg.marketPages.replayPaused
    LaunchedEffect(page.replay, paused) {
        if (!paused && shown < m.window.size) {
            delay(REPLAY_MS)
            nav.replaceTop(page.copy(replay = shown + 1))
        }
    }
    val name: (String) -> String = { id -> v.badges[id]?.name ?: id }
    val starts = m.rounds.associate { (round, i) -> i to round }
    CentreList {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "☀ 시즌 ${m.windowSeason} 여름 · $shown/${m.window.size}", Modifier.weight(1f),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                )
                if (shown < m.window.size) {
                    TextButton(onClick = { lg.marketPages.replayPaused = !paused }) { Text(if (paused) "▶ 이어 보기" else "⏸ 멈추기") }
                }
                TextButton(onClick = { nav.replaceTop(page.copy(replay = -1)) }) { Text("모두 보기") }
            }
        }
        for (i in 0 until shown) {
            starts[i]?.let { round -> item(key = "rh-$i") { Heading(round) } }
            val x = m.window[i]
            item(key = "rv-$i") { MoveText(lg, x.move, money = true, flags = x.flags, highlight = i == shown - 1, clubName = name) }
        }
    }
}

// ---- 최고 이적료 ------------------------------------------------------------------------------------------

/** 최고 이적료 (UX 6.4): the world record, its progression, the all-time top 20, the leagues' records and last summer's top 10. */
@Composable
private fun RecordsTab(lg: LeagueController, m: MarketView) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    val r = m.records ?: return
    val open: (FeeLine) -> Unit = { f -> if (f.key.isNotEmpty()) nav.open(Page.Player(f.key)) }
    CentreList {
        item {
            Card(Modifier.fillMaxWidth().clickable { r.world?.let(open) }, colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("🌍 세계 최고 이적료", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer)
                    Text(
                        r.world?.let { MarketTexts.worldRecord(Money.text(it.fee), it.shown, it.from?.name, it.to?.name, it.season) } ?: "아직 이적료 기록이 없어요.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer,
                    )
                }
            }
        }
        if (r.progression.size > 1) {
            item { Heading("📈 기록 변천") }
            items(r.progression, key = { "p-${it.season}-${it.key}-${it.fee}" }) { f -> Line("시즌 ${f.season} · ${Money.text(f.fee)} · ${f.shown}") { open(f) } }
        }
        if (r.top.isNotEmpty()) {
            item { Heading("🏆 역대 톱 ${r.top.size}") }
            items(r.top.withIndex().toList(), key = { "t-${it.index}" }) { (i, f) ->
                Line("${i + 1}. ${MarketTexts.fee(f.shown, f.from?.name, f.to?.name)}", "${Money.text(f.fee)} · 시즌 ${f.season}") { open(f) }
            }
        }
        if (r.leagues.isNotEmpty()) {
            item { Heading("🏳 리그별 최고 이적료") }
            items(r.leagues, key = { "l-${it.first}" }) { (lg2, f) ->
                Line(MarketTexts.leagueRecord(leagueName(lg2), Money.text(f.fee), f.shown.takeIf { f.key.isNotEmpty() })) { open(f) }
            }
        }
        if (r.lastSummer.isNotEmpty()) {
            item { Heading("☀ 지난 여름 톱 ${r.lastSummer.size} (시즌 ${r.lastSummer.first().season})") }
            items(r.lastSummer.withIndex().toList(), key = { "s-${it.index}" }) { (i, f) ->
                Line("${i + 1}. ${MarketTexts.fee(f.shown, f.from?.name, f.to?.name)}", Money.text(f.fee)) { open(f) }
            }
        }
        item { Small("이적료 기록은 최근 20시즌까지 남아요.") }
    }
}

/** A league with its flag ("🇰🇷 한국 리그"; a region's or a merged league's without). */
private fun leagueName(id: String): String = Nations.of(id)?.flag?.let { "$it ${LeagueIds.label(id)}" } ?: LeagueIds.label(id)

// ---- 감독 --------------------------------------------------------------------------------------------------

/**
 * 감독 (UX 6.5): ⚠ 경질 위기 (the best-ranked leagues' and the followed clubs' coaches at half their
 * board's threshold or more, by how close they are), this season's coach moves, the best free coaches and
 * the coach records.
 */
@Composable
private fun CoachesTab(lg: LeagueController, m: MarketView) {
    val nav = lg.nav
    val c = m.coaches ?: return
    CentreList {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⚠ 경질 위기", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                InfoMark(nav, InfoTopic.BOARD)
            }
        }
        if (c.hotSeat.isEmpty()) item { Small("지금은 흔들리는 감독이 없어요.") }
        items(c.hotSeat, key = { "h-${it.club.id}" }) { h ->
            // Two lines (the club and its coach; the board's target, the place and the level), the dots on the right.
            Row(Modifier.fillMaxWidth().clickable { nav.open(Page.Coach(h.coach.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                ClubMark(h.club, 14.dp)
                Column(Modifier.weight(1f).padding(start = 4.dp, end = 6.dp)) {
                    Text(MarketTexts.hotSeat(h.club.name, h.coach.shown), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        MarketTexts.hotSeatBoard(h.target, h.place, h.coach.pressure?.word), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
                h.coach.pressure?.let { PressureDots(it) }
            }
        }
        item { Heading("🔁 이번 시즌 감독 이동") }
        if (c.moves.isEmpty()) item { Small("이번 시즌에는 아직 감독이 바뀐 클럽이 없어요.") }
        items(c.moves.withIndex().toList(), key = { "m-${it.index}" }) { (_, x) ->
            Line(MarketTexts.coachMove(x.season, x.winter, x.club?.name, x.kind, x.shown, x.from?.name), WRAP) { nav.open(Page.Coach(x.coach)) }
        }
        if (c.free.isNotEmpty()) {
            item { Heading("😴 쉬고 있는 감독") }
            items(c.free, key = { "f-${it.id}" }) { f -> Line("${f.shown} · ${f.philosophy.label} · 평판 ${f.reputation}") { nav.open(Page.Coach(f.id)) } }
        }
        if (c.records.isNotEmpty()) {
            item { Heading("🏅 감독 기록") }
            items(c.records, key = { "r-${it.label}" }) { r -> Line("${r.label} · ${r.shown} · ${r.value}") { nav.open(Page.Coach(r.coach)) } }
        }
    }
}

// ---- texts ----------------------------------------------------------------------------------------------

/** 이적 센터's texts with names and numbers in them (UX 6, 해요체); StoryTextsTest renders each. */
internal object MarketTexts {
    /** 이적설's window line (UX 6.2) in the week in play [week]: the winter window ([winterWeek] − 2 to it), quiet before the rumour weeks ([from]), else the rumour weeks. */
    fun windowLine(week: Int, from: Int, winterWeek: Int): String = when {
        winterWeek > 0 && week in winterWeek - 2..winterWeek -> "❄ 겨울 이적시장이 열려 있어요. ${winterWeek}주차가 끝나면 닫혀요."
        week < from -> "지금은 조용해요. ${from}주차부터 이적설이 돌기 시작해요."
        else -> "🔥 이적설이 도는 시기예요. 시즌이 끝나면 협상 결과가 나와요."
    }

    /** A story's first line: "★ {player} · {seller} → {buyer}". */
    fun storyLine(followed: Boolean, player: String, seller: String?, buyer: String?): String =
        (if (followed) "★ " else "") + "$player · ${seller ?: "–"} → ${buyer ?: "–"}"

    /** A story's second line: "{w}주차 소문 → {t}주차 협상 · ⚔ 라이벌 이적 · ❄ 겨울". */
    fun storyWeeks(rumourWeek: Int, talksWeek: Int, rival: Boolean, winter: Boolean): String =
        "${rumourWeek}주차 소문" + (if (talksWeek > 0) " → ${talksWeek}주차 협상" else "") + (if (rival) " · ⚔ 라이벌 이적" else "") + (if (winter) " · ❄ 겨울" else "")

    /** How a story that reached talks ended: ✅ 성사, ❌ 무산 (with its [reason]), 🦅 가로채기 ([by] instead of [buyer]). */
    fun outcome(stage: StoryStage, player: String, buyer: String?, by: String?, fee: String, reason: String?): String = when (stage) {
        StoryStage.DONE -> "✅ 성사 · $player → ${buyer ?: "–"} · $fee"
        StoryStage.HIJACKED -> "🦅 가로채기 · $player → ${by ?: "–"} (원래 ${buyer ?: "–"})"
        else -> "❌ 무산 · $player" + (reason?.let { " · $it" } ?: "")
    }

    /** A window's heading in 이적 내역: "시즌 {s} 여름 이적시장" (the off-season after season s) or "시즌 {s} 겨울 이적시장". */
    fun windowHeading(season: Int, winter: Boolean): String = "시즌 $season ${if (winter) "겨울" else "여름"} 이적시장"

    /**
     * A move with a club world's money (UX 6.3): "🔁 {who}: {from} → {to} · {fee}" and its marks
     * ([marks]); the other kinds as today's lines.
     */
    fun move(type: ClubMoveType, who: String, from: String?, to: String?, amount: Int, flags: String): String {
        val base = when (type) {
            ClubMoveType.ABROAD -> "✈️ $who: $from → $to · 해외 이적 · ${Money.text(amount)}"
            ClubMoveType.HOME -> "🏠 $who: $from → $to (귀국)"
            ClubMoveType.YOUTH -> "🌱 $who: $to 유스 입단"
            ClubMoveType.GRADUATE -> "🌱 $who: $to 유스 졸업"
            ClubMoveType.DRAFT -> "📝 $who: $to 드래프트 ${amount}순위"
            ClubMoveType.RELEASED -> "👋 $who: ${from}에서 방출"
            ClubMoveType.RETIRED -> "🌅 $who: $from 떠남 (은퇴)"
            else -> "🔁 $who: ${from ?: "자유 계약"} → $to" + if (amount > 0) " · ${Money.text(amount)}" else ""
        }
        val marks = marks(type, flags)
        return if (marks.isEmpty()) base else "$base $marks"
    }

    /** The marks after a move (UX 6.3): 💰 기록 (a world or league record), ⚔ 라이벌, ⏰ 마감일, 🔓 바이아웃, then its type's. */
    fun marks(type: ClubMoveType, flags: String): String = buildList {
        if ('W' in flags || 'L' in flags) add("💰 기록")
        if ('V' in flags) add("⚔ 라이벌")
        if ('D' in flags) add("⏰ 마감일")
        if ('K' in flags || type == ClubMoveType.RELEASE_CLAUSE) add("🔓 바이아웃")
        when (type) {
            ClubMoveType.FREE_AGENT -> add("🆓 자유계약")
            ClubMoveType.LOAN -> add("↔ 임대")
            ClubMoveType.LOAN_BACK -> add("↩ 임대 복귀")
            ClubMoveType.WINTER -> add("❄ 겨울")
            else -> {}
        }
        if ('F' in flags || type == ClubMoveType.FORCED_SALE) add("💸 강제 매각")
    }.joinToString(" ")

    /** The world record's line: "{fee} · {player} · {from} → {to} · 시즌 {s}". */
    fun worldRecord(fee: String, player: String, from: String?, to: String?, season: Int): String = "$fee · $player · ${from ?: "–"} → ${to ?: "–"} · 시즌 $season"

    /** A fee row's player and clubs: "{player} · {from} → {to}". */
    fun fee(player: String, from: String?, to: String?): String = "$player · ${from ?: "–"} → ${to ?: "–"}"

    /** A league's record: "{league} · {fee} · {player}" (no player when its move is no longer kept). */
    fun leagueRecord(league: String, fee: String, player: String?): String = "$league · $fee" + (player?.let { " · $it" } ?: "")

    /** A coach on the hot seat (UX 6.5), its first line: "{club} · {coach}". */
    fun hotSeat(club: String, coach: String): String = "$club · $coach"

    /** Its second line: "목표 {t}위 → 지금 {p}위 · {level word}". */
    fun hotSeatBoard(target: Int, place: Int, word: String?): String = "목표 ${target}위 → 지금 ${place}위" + (word?.let { " · $it" } ?: "")

    /** A coach move's kind in words (UX 6.5). */
    fun coachKind(kind: Char): String = when (kind) {
        'S' -> "경질"
        'H' -> "선임"
        'P' -> "영입"
        'R' -> "은퇴"
        'L' -> "계약 만료"
        'C' -> "선수에서 감독으로"
        else -> "이동"
    }

    /**
     * A coach move: "시즌 {s} {겨울|종료 후} · {club} · {kind} · {coach}" ("({from}에서)" after a poached or
     * hired coach's name, the club it came from; no club for a retirement).
     */
    fun coachMove(season: Int, winter: Boolean, club: String?, kind: Char, coach: String, from: String?): String =
        "시즌 $season ${if (winter) "겨울" else "종료 후"} · " + (club?.let { "$it · " } ?: "") + "${coachKind(kind)} · $coach" +
            (from?.takeIf { kind == 'P' || kind == 'H' }?.let { " (${it}에서)" } ?: "")

    /** The match report's rival line (UX 6.6): "⚔ {name} · 통산 {a} {w}승 {d}무 {l}패" (before any meeting: the first one). */
    fun rivalLine(name: String, club: String, w: Int, d: Int, l: Int): String =
        if (w + d + l == 0) "⚔ $name · 라이벌전 첫 맞대결이에요" else "⚔ $name · 통산 $club ${w}승 ${d}무 ${l}패"

    /** The club page's chip: "💸 이적설 {n}건 ›". */
    fun storiesChip(n: Int): String = "💸 이적설 ${n}건 ›"
}

/** The first story of 이적설 (a first-screen block, UX 15.2). */
internal const val MARKET_SECTION = "market_story"

/** One more move of ☀ 이적시장 다시 보기 every this many milliseconds. */
private const val REPLAY_MS = 900L

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A tappable line with a note on its right ([lines]: the lines its text may take, the last one cut). */
@Composable
private fun Line(name: String, note: String = "", lines: Int = 1, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = lines, overflow = TextOverflow.Ellipsis)
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Line(name: String, lines: Int, onClick: () -> Unit) = Line(name, "", lines, onClick)

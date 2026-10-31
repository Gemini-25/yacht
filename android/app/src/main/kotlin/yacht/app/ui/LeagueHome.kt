package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.BroadcastPick
import yacht.app.core.FeedItem
import yacht.app.core.FollowCard
import yacht.app.core.FollowKind
import yacht.app.core.FollowMatch
import yacht.app.core.HomeBanner
import yacht.app.core.HomeView
import yacht.app.core.LeagueController
import yacht.app.core.LeagueTab
import yacht.app.core.LeagueView
import yacht.app.core.MatchesView
import yacht.app.core.Page
import yacht.app.core.RecordsView
import yacht.app.core.ResultsFilter
import yacht.app.core.Screen
import yacht.app.core.SeasonRow
import yacht.app.core.Sheet
import yacht.app.core.TableMode
import yacht.app.core.TablesView
import yacht.app.core.WorldSnippet
import yacht.engine.explain.Ko
import yacht.engine.league.Article
import yacht.engine.league.ChaseLine
import yacht.engine.league.CloneProfile
import yacht.engine.league.Comp
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueIds
import yacht.engine.league.MatchFormat
import yacht.engine.league.MatchRef
import yacht.engine.league.Origin

// 홈: the league's daily landing page (UX design 2). One banner (a season just over, the first
// national season, a reform to come, an empty league), ★ 응원 (followed players, clubs, nations and
// leagues: last and next match, ▶), this week's big matches, the latest results, 내 리그, the
// international stage, the 요트협회's vote, the record chase, the top news, next week, and the footer
// (ranked game, the makers, the clone, the counts). Sections without anything to show fold away.

/** 홈: the dashboard, scrolled ([onRanked]: the ranked game's picker); its view is built again each time the tab comes up. */
@Composable
internal fun LeagueHome(c: AppController, v: LeagueView, onRanked: () -> Unit) {
    LaunchedEffect(Unit) { c.league.home.refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { HomeBody(c, v, onRanked) }
}

/** The home's content in one column (the screenshot harness measures its height). */
@Composable
internal fun HomeBody(c: AppController, v: LeagueView, onRanked: () -> Unit) {
    val lg = c.league
    // The home built for this season (a league replaced since: none until it is built again).
    val hv = lg.home.view?.takeIf { it.season == (v.clock?.season ?: 0) }
    // Tight: a home with every section up fits in 1,600 dp at a phone's width (UX 2).
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HomeBannerCard(c, v, hv?.banner ?: HomeBanner.Empty.takeIf { v.matches == 0L })
        if (v.season != null) Follows(c, hv)
        BigMatches(c, v)
        LatestResults(c, v)
        MyLeague(lg, v)
        hv?.world?.let { WorldCard(lg, it) }
        hv?.takeIf { it.pendingProposals > 0 }?.let { FederationLine(lg, it) }
        hv?.chase?.takeIf { it.isNotEmpty() }?.let { ChaseCard(lg, it) }
        TopNews(c, v)
        NextWeek(lg, v)
        Footer(c, v, onRanked)
    }
}

/** A section's title row, with its link at the end ([more] opens it). */
@Composable
private fun SectionTitle(title: String, more: String? = null, onMore: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp)).let { if (onMore != null) it.clickable(onClick = onMore) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (more != null) Text(more, style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

// ---- the banner ------------------------------------------------------------------------------------

/** The one banner (UX 2: a season's end, the first national season, a reform to come, an empty league). */
@Composable
internal fun HomeBannerCard(c: AppController, v: LeagueView, b: HomeBanner?) {
    val lg = c.league
    when (b) {
        null -> {}
        HomeBanner.Empty -> EmptyBanner(lg, v)
        is HomeBanner.SeasonEnd -> SeasonEndBanner(b, onDismiss = { lg.dismissBanner(b) }) { lg.nav.open(Page.Review(b.season)) }
        is HomeBanner.Reform -> NoticeBanner(
            "🌍 이번 시즌부터 국가 리그예요",
            listOf("선수들이 클럽이 있는 나라의 리그로 나뉘었어요. 리그마다 1부·2부가 있고, 국제 대회 성적으로 리그 랭킹이 매겨져요."),
            "리그 고르기" to { lg.nav.sheet = Sheet.LEAGUES },
        ) { lg.dismissBanner(b) }
        is HomeBanner.Coming -> NoticeBanner(
            "🌍 다음 시즌부터 국가 리그로 바뀌어요",
            listOf("응원 선수가 어느 리그로 가는지 미리 볼 수 있어요.") + b.moves.map { (name, league) -> "$name: 다음 시즌 $league" }
                .ifEmpty { listOf("선수를 응원하면 다음 시즌에 뛸 리그를 여기서 알려 줘요.") },
            null,
        ) { lg.dismissBanner(b) }
    }
}

@Composable
private fun SeasonEndBanner(b: HomeBanner.SeasonEnd, onDismiss: () -> Unit, onReview: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer)) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("🏆 ${Ko.i("시즌 ${b.season}")} 끝났어요", fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer, style = MaterialTheme.typography.titleSmall)
            for (line in b.lines) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BannerActions(listOf("닫기" to onDismiss, "결산 보기" to onReview))
        }
    }
}

/** A notice ([title], [lines]) with an action ([action]: its label and what it does) and 닫기 ([onDismiss]). */
@Composable
private fun NoticeBanner(title: String, lines: List<String>, action: Pair<String, () -> Unit>?, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer, style = MaterialTheme.typography.titleSmall)
            for (line in lines.take(4)) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BannerActions(listOfNotNull("닫기" to onDismiss, action))
        }
    }
}

/** A banner's actions at its bottom right, as compact text buttons (the banner stays within its height). */
@Composable
private fun BannerActions(actions: List<Pair<String, () -> Unit>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
        for ((label, go) in actions) {
            Text(
                label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = go).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** An empty league: what it is, how the first season is split, and how to start (from the seed too). */
@Composable
private fun EmptyBanner(lg: LeagueController, v: LeagueView) {
    val cs = MaterialTheme.colorScheme
    val players = v.rows.count { !it.isMe && (lg.config.baseAis || it.origin != Origin.BASE) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AI 리그를 시작해요", fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer, style = MaterialTheme.typography.titleSmall)
            Text(
                "선수 ${"%,d".format(players)}명이 " + (if (lg.config.national) "국가 리그" else "리그") +
                    "와 컵 대회에서 겨뤄요. 첫 시즌에는 레이팅으로 부를 나누고, 경기마다 레이팅이 ±로 바뀌어요.",
                style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer,
            )
            // The first season's match rule (the rules saved now).
            val match = when (lg.config.matchFormat) {
                MatchFormat.SINGLE -> "한 경기는 한 판이에요."
                MatchFormat.BEST_OF_3 -> "한 경기는 같은 주사위로 선공·후공을 바꿔 두 판이고, 승부가 안 나면 세 번째 판을 둬요."
                else -> "한 경기는 같은 주사위로 선공·후공을 바꿔 두 판이에요."
            }
            Text(
                "$match ${Ladder.PLACEMENT}판을 두면 배치가 끝나 티어가 붙어요.",
                style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer,
            )
            if (lg.hasSeed && !lg.running) {
                OutlinedButton(onClick = { lg.loadSeed() }, modifier = Modifier.fillMaxWidth()) { Text("⚡ 미리 돌린 리그로 시작해요") }
            }
            Button(onClick = { lg.start() }, enabled = !lg.running, modifier = Modifier.fillMaxWidth()) { Text("▶ 시작") }
        }
    }
}

// ---- ★ 응원 -------------------------------------------------------------------------------------------

/** What a followed card opens: the player's, club's, nation's or league's page. */
private fun openFollowed(lg: LeagueController, card: FollowCard) {
    lg.nav.open(
        when (card.kind) {
            FollowKind.PLAYER -> Page.Player(card.target)
            FollowKind.CLUB -> Page.Club(card.target)
            FollowKind.NATION -> Page.Nation(card.target)
            FollowKind.LEAGUE -> Page.League(card.target)
        },
    )
}

/** "승", "무", "패" of a result from the followed side. */
private fun resultWord(r: Int?): String = when (r) {
    1 -> "승"
    0 -> "무"
    -1 -> "패"
    else -> ""
}

/** ★ 응원: the first [HomeView.FOLLOW_ROWS] followed entities in two lines each, "응원 전체 ›"; or how to follow someone (nothing until the home is built). */
@Composable
private fun Follows(c: AppController, hv: HomeView?) {
    if (hv == null) return
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    val cards = hv.followed
    val more = cards.size - HomeView.FOLLOW_ROWS + hv.followedMore
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("★ 응원", if (cards.isEmpty()) null else if (more > 0) "응원 전체 (+$more) ›" else "응원 전체 ›") { lg.nav.open(Page.Follows) }
        if (cards.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
                    Text(
                        "선수나 클럽 페이지에서 ‘☆ 응원하기’를 누르면 여기에 결과와 다음 경기가 모여요.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        lg.nav.go(LeagueTab.TABLES)
                        lg.nav.tables = TablesView.LADDER
                    }, modifier = Modifier.align(Alignment.End)) { Text("선수 찾아보기") }
                }
            }
            return@Column
        }
        for (card in cards.take(HomeView.FOLLOW_ROWS)) FollowRow(c, card)
    }
}

/** A followed entity in two lines: who and where it stands; its last result (▶) and its next match (🔮 and the chance). */
@Composable
private fun FollowRow(c: AppController, card: FollowCard) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable { openFollowed(lg, card) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClubMark(card.badge, 14.dp)
                Text(card.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (card.line.isNotEmpty()) Text(" · ${card.line}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val text = followLine(card, lg.view?.clock?.leagueWeeks, lg::name)
                Text(text, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                card.last?.takeIf { it.replay }?.let { m -> MiniAction("▶") { watchMatch(c, m.ref) } }
                card.next?.takeIf { it.ref.isNotEmpty() }?.let { m -> MiniAction("🔮") { lg.nav.open(Page.Match(m.ref)) } }
            }
        }
    }
}

/** A small tappable mark at a row's end (▶, 🔮). */
@Composable
private fun MiniAction(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** True when [m] is a board of a team match (a club cup's, a nation cup's): its result and score are the teams'. */
private fun teamBoard(m: FollowMatch): Boolean = (MatchRef.decode(m.ref)?.board ?: -1) >= 0

/**
 * A followed row's second line: "최근 {opp}에게 2:0 승 · 다음 15주차 {opp} 62%", else its detail or
 * note; a player's opponents by [name] alone (no flag: the line is short), a team match's by the
 * other team; weeks as the schedule names them ([leagueWeeks]: "개막 전", "포스트시즌 1주차").
 */
internal fun followLine(card: FollowCard, leagueWeeks: Int?, name: (String) -> String): String {
    val parts = ArrayList<String>()
    fun opp(m: FollowMatch) = if (m.opponent.isNotEmpty() && card.kind == FollowKind.PLAYER && !teamBoard(m)) name(m.opponent) else m.opponentName
    card.last?.let { m ->
        parts += if (card.kind == FollowKind.CLUB) "${m.where} ${m.score ?: ""} ${resultWord(m.result)}".trim()
        else "최근 ${if (m.opponentName.isEmpty()) m.where else "${opp(m)}에게"} ${m.score ?: ""} ${resultWord(m.result)}".replace("  ", " ").trim()
    }
    card.next?.let { m ->
        val week = weekName(m.week, leagueWeeks).ifEmpty { null }
        parts += listOfNotNull("다음", week, if (m.opponent.isEmpty()) "${m.where} · 상대 미정" else opp(m) + (m.winChance?.let { " $it%" } ?: "")).joinToString(" ")
    }
    if (parts.isEmpty()) card.detail?.let { parts += it }
    if (parts.isEmpty()) card.note?.let { parts += it }
    return parts.joinToString(" · ")
}

/** [Page.Follows]: every followed entity as a full card (its place, form, last match with ▶ and 📋, next match with 🔮). */
@Composable
internal fun FollowsPage(c: AppController, v: LeagueView) {
    val lg = c.league
    LaunchedEffect(Unit) { lg.home.refresh() }
    val hv = lg.home.view?.takeIf { it.season == (v.clock?.season ?: 0) }
    if (hv == null) {
        CenterText("불러오는 중…")
        return
    }
    if (hv.followed.isEmpty()) {
        CenterText("아직 응원하는 선수나 클럽이 없어요. 선수, 클럽, 리그 페이지나 국가대표 페이지에서 ‘☆ 응원’을 누르면 여기에 모여요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hv.followed, key = { it.key }) { card -> FollowCardView(c, v, card) }
        if (hv.followedMore > 0) item { Text("외 ${hv.followedMore}개는 응원 목록에만 있어요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun FollowCardView(c: AppController, v: LeagueView, card: FollowCard) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable { openFollowed(lg, card) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClubMark(card.badge, 18.dp)
                Text(card.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (card.form.isNotEmpty()) FormDots(card.form)
            }
            val sub = listOf(card.line, card.detail.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            card.last?.let { m -> MatchLine(c, v, "최근", m, card.kind == FollowKind.CLUB) }
            card.next?.let { m -> MatchLine(c, v, "다음", m, false) }
            card.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
        }
    }
}

/** A followed side's last or next match on the 응원 page, with "▶ 다시 보기" and "📋 리포트", or "🔮 프리뷰". */
@Composable
private fun MatchLine(c: AppController, v: LeagueView, head: String, m: FollowMatch, club: Boolean) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    val week = weekLabel(m.week, v.clock?.season ?: 0, v.clock)
    // A team match is the teams' (their result and score); the followed player's own board says whom it met.
    val board = if (!club && teamBoard(m) && m.opponent.isNotEmpty()) " · 상대 ${lg.name(m.opponent)}" else ""
    val text = when {
        m.opponent.isEmpty() && m.result == null -> "$week ${m.where} · 상대가 아직 정해지지 않았어요"
        m.result == null -> "$week ${m.where} · ${m.opponentName}" + (m.winChance?.let { " · 예상 승률 $it%" } ?: "") + board
        club -> "$week ${m.where} ${m.score ?: ""} ${resultWord(m.result)}"
        else -> "$week ${m.where} · ${m.opponentName}에게 ${m.score ?: ""} ${resultWord(m.result)}$board"
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text("$head  $text".replace("  ", " "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (m.ref.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (m.result != null && m.replay) TextButton(onClick = { watchMatch(c, m.ref) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("▶ 다시 보기", fontSize = 12.sp) }
                if (m.result != null) TextButton(onClick = { lg.nav.open(Page.Match(m.ref)) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("📋 리포트", fontSize = 12.sp) }
                else TextButton(onClick = { lg.nav.open(Page.Match(m.ref)) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("🔮 프리뷰", fontSize = 12.sp, color = cs.primary) }
            }
        }
    }
}

// ---- this week's big matches, the latest results ------------------------------------------------------

/** 📺 이번 주 빅매치: the week's broadcast picks as cards in a row (▶ played, 🔮 to come) and "📺 연속 재생". */
@Composable
private fun BigMatches(c: AppController, v: LeagueView) {
    val lg = c.league
    val clock = v.clock ?: return
    val season = clock.season
    val b = lg.center.broadcast
    LaunchedEffect(season, clock.week, b == null) { if (b == null) lg.center.refreshPicks() }
    val picks = b?.takeIf { it.season == season }?.picks.orEmpty()
    if (picks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("📺 이번 주 빅매치 · ${b!!.week}주차", if (picks.any { it.played }) "📺 연속 재생 ›" else "중계 ›") {
            if (picks.any { it.played }) startBroadcast(c) else {
                lg.nav.go(LeagueTab.MATCHES)
                lg.nav.matches = MatchesView.BROADCAST
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(picks, key = { it.ref }) { p -> PickCard(c, p) }
        }
    }
}

@Composable
private fun PickCard(c: AppController, p: BroadcastPick) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    Card(Modifier.width(180.dp).clickable { lg.nav.open(Page.Match(p.ref)) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(compIcon(p.comp) + " " + p.title, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A side by its name: a player's, a club's (a club cup's sides are club ids), a nation's or a team's.
            Text("${lg.name(p.a)} 대 ${lg.name(p.b)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Its status and why it was picked on one line, then ▶ or 🔮.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = when {
                    p.played -> "끝" + (p.result?.let { " · $it" } ?: "")
                    lg.view?.live?.any { "${lg.view?.clock?.season}:${it.comp.code}:${it.id}" == p.ref } == true -> "경기 중"
                    else -> "곧 시작"
                }
                Text(status, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    " · ${p.reason}", style = MaterialTheme.typography.labelSmall, color = cs.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (p.played) MiniAction(if (p.watched) "✓" else "▶") { startBroadcast(c, p.ref) } else MiniAction("🔮") { lg.nav.open(Page.Match(p.ref)) }
            }
        }
    }
}

/**
 * ⚡ 최근 결과: the newest official results worth a look (the competitions' and the shown league's
 * 1부 before the rest), each opening its report (▶ when it can be played again), "결과 전체 ›".
 */
@Composable
private fun LatestResults(c: AppController, v: LeagueView) {
    val lg = c.league
    val top = remember(v.season) { v.season?.divisions?.firstOrNull()?.map { it.key }?.toSet().orEmpty() }
    val shown = remember(v.feed, top) { latestResults(v.feed, top) }
    if (shown.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle("⚡ 최근 결과", "결과 전체 ›") {
            lg.nav.go(LeagueTab.MATCHES)
            lg.nav.matches = MatchesView.RESULTS
            lg.nav.results = ResultsFilter.ALL
        }
        for (m in shown) {
            // A result is a board's (a team match's: its players), named as the feed names them.
            val won = m.games.count { it.a > it.b }
            val lost = m.games.count { it.a < it.b }
            Row(
                Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = m.ref != null) { m.ref?.let { lg.nav.open(Page.Match(it)) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m.comp?.let { compIcon(it) } ?: "🎲", fontSize = 12.sp, modifier = Modifier.widthIn(min = 22.dp))
                Text(
                    "${m.aName}  $won:$lost  ${m.bName}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Text(stageName(m.comp ?: Comp.LEAGUE, m.label, short = true, icon = false), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                if (m.replay && m.ref != null) MiniAction("▶") { watchMatch(c, m.ref) }
            }
        }
    }
}

/**
 * The home's results: official ones of a competition or between players of [top] (the shown 1부)
 * first, the newest [RESULTS] of them, else the newest; one board of a team match (its others
 * would fill the section with one tie).
 */
internal fun latestResults(feed: List<FeedItem>, top: Set<String>): List<FeedItem> {
    val official = feed.filter { it.comp != null && it.comp != Comp.FRIENDLY && it.comp != Comp.LAB && it.comp != Comp.LEGEND }
        .distinctBy { m -> m.ref?.let { MatchRef.decode(it)?.whole?.encode() } ?: "${m.time}|${m.a}|${m.b}" }
    val big = official.filter { it.comp != Comp.LEAGUE || (it.a in top && it.b in top) }
    return (big.take(RESULTS) + official.filter { it !in big }).take(RESULTS)
}

/** Results on the home. */
private const val RESULTS = 3

// ---- 내 리그, the international stage, the 요트협회, the record chase ---------------------------------------

/** 내 리그: the shown league's header, its 1부's top three and up to two followed players elsewhere, with "순위표 전체 ›". */
@Composable
private fun MyLeague(lg: LeagueController, v: LeagueView) {
    val season = v.season ?: return
    val top = season.divisions.firstOrNull() ?: return
    val cs = MaterialTheme.colorScheme
    val favs = lg.favourites
    val tiers = season.tiers.takeIf { it.size == season.divisions.size } ?: yacht.engine.league.Tiers.linear(season.divisions.size)
    // Followed players outside the top three: (division, place, row).
    val followed = season.divisions.withIndex().flatMap { (d, rows) -> rows.withIndex().filter { (i, r) -> r.key in favs && (d > 0 || i >= 3) }.map { (i, r) -> Triple(d, i, r) } }.take(2)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val round = if (season.matchdays > 0) " · ${minOf(season.currentMatchday + 1, season.matchdays)}/${season.matchdays}R" else ""
            SectionTitle("📋 내 리그 · ${tiers.label(0)}$round", "순위표 전체 ›") {
                // The card's table: 1부, in full.
                val nav = lg.nav
                nav.go(LeagueTab.TABLES)
                nav.tables = TablesView.LEAGUE
                nav.tableMode = TableMode.TABLE
                nav.showDivision(tiers, 0)
            }
            // A national season: which league this is, with its selector and its page.
            LeagueHeader(lg, season)
            val open: (String) -> Unit = { lg.nav.open(Page.Player(it)) }
            for ((i, r) in top.take(3).withIndex()) TableLine("${i + 1}", r, r.key in favs, open)
            for ((d, i, r) in followed) TableLine("${tiers.label(d)} ${i + 1}위", r, true, open)
        }
    }
}

/** A row of the league card: its place, the zone stripe, ★ when followed, the name and the points; a tap opens the player ([onOpen]). */
@Composable
private fun TableLine(place: String, r: SeasonRow, followed: Boolean, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(6.dp)).clickable { onOpen(r.key) }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(20.dp).background(if (r.zone > 0) gameColors.good else if (r.zone < 0) cs.error else cs.surface))
        // A top-three place is one digit; a followed player's line says its division ("2부 A조 12위").
        Text(place, Modifier.widthIn(min = 28.dp).padding(start = 6.dp, end = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text((if (followed) "★ " else "") + r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${r.wins}-${r.draws}-${r.losses}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        Text("${r.points}점", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
    }
}

/**
 * 🌍 국제 무대: where the 챔피언스 리그 and the 대륙 챔피언스 are (tap: the competition), the league
 * ranking's top 3 in a line (with their moves and 국제 성적 점수 once a ranking is published; by
 * strength before), this season's race, "랭킹 전체 ›".
 */
@Composable
private fun WorldCard(lg: LeagueController, w: WorldSnippet) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionTitle("🌍 국제 무대", "랭킹 전체 ›") {
                lg.nav.go(LeagueTab.TABLES)
                lg.nav.tables = TablesView.RANKING
            }
            if (w.stages.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((comp, stage) in w.stages) {
                        Text(
                            "${compIcon(comp)} ${compShort(comp)} $stage", style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { lg.nav.open(Page.Competition(comp)) }.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (w.ranking.isNotEmpty()) {
                // Before the first national close the ranking goes by strength: no points or moves yet.
                val published = w.ranking.any { it.rank.coefficient > 0.0 }
                Text(
                    (if (published) "📈 리그 랭킹 " else "📈 리그 전력 ") + w.ranking.withIndex().joinToString("  ") { (i, r) ->
                        val move = r.rank.change.takeIf { published && it != 0 }?.let { if (it > 0) " ▲$it" else " ▼${-it}" }.orEmpty()
                        val points = if (published) " ${"%.1f".format(r.rank.coefficient)}" else ""
                        "${i + 1} ${r.icon} ${r.short}$move$points"
                    },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                        lg.nav.go(LeagueTab.TABLES)
                        lg.nav.tables = TablesView.RANKING
                    }.padding(vertical = 2.dp),
                )
            }
            if (w.live.isNotEmpty()) {
                Text(
                    "이번 시즌 점수 레이스: " + w.live.joinToString(" · ") { (id, x) -> "${LeagueIds.short(id)} +${"%.1f".format(x)}" },
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 🏛 요트협회: one line for the assembly's open proposals ("투표하기 ›", or how many the player voted on). */
@Composable
private fun FederationLine(lg: LeagueController, hv: HomeView) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable { lg.nav.open(Page.Federation) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🏛 총회 안건 ${hv.pendingProposals}개", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (hv.voted == 0) "투표하기 ›" else "${hv.voted}/${hv.pendingProposals} 투표함 ›",
                style = MaterialTheme.typography.labelMedium, color = cs.primary,
            )
        }
    }
}

/** 🏃 기록 추격: the nearest chases, a line each (who, how far from which record, the record), "기록실 ›". */
@Composable
private fun ChaseCard(lg: LeagueController, lines: List<ChaseLine>) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionTitle("🏃 기록 추격", "기록실 ›") {
                lg.nav.go(LeagueTab.RECORDS)
                lg.nav.records = RecordsView.ROOM
            }
            for (l in lines) {
                // A count or a run names its record ("통산 최다 요트까지 4개"); a pace or an average does not.
                val how = if (l.paced || l.average) "${l.kind.label} ${l.text()}" else l.text()
                Row(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).clickable { lg.nav.open(Page.Player(l.key)) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (l.key in lg.favourites) "★ " else ""}${lg.name(l.key)} · $how",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(" 기록 ${l.kind.withUnit(l.record)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

// ---- the top news, next week, the footer --------------------------------------------------------------

/** 📰 주요 뉴스: two articles, the big ones of the last two weeks first, with "뉴스 전체 ›". */
@Composable
private fun TopNews(c: AppController, v: LeagueView) {
    if (v.news.isEmpty()) return
    val lg = c.league
    val shown = topNews(v)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("📰 주요 뉴스", "뉴스 전체 ›") { lg.nav.open(Page.News) }
        for (a in shown) ArticleCard(c, v, a, compact = true)
    }
}

/** The home's two articles: importance 4 or more of this week and the last, newest first, then the most important. */
private fun topNews(v: LeagueView): List<Article> {
    val clock = v.clock
    val recent = v.news.filter { a -> clock != null && a.season == clock.season && a.week >= clock.week - 1 && a.importance >= 4 }.take(2)
    return (recent + v.news.filter { it !in recent }.sortedByDescending { it.importance }).take(2)
}

/** 🗓 다음 주: next week's stages and the big matches picked for it so far (🔮). */
@Composable
private fun NextWeek(lg: LeagueController, v: LeagueView) {
    val clock = v.clock ?: return
    if (clock.over) return
    val week = clock.week + 1
    val row = clock.row(week) ?: return
    val coming = lg.center.broadcast?.takeIf { it.season == clock.season }?.coming.orEmpty().take(2)
    if (row.stages.isEmpty() && coming.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionTitle("🗓 다음 주 · ${weekName(week, clock.leagueWeeks)}", "일정 ›") {
                lg.nav.go(LeagueTab.MATCHES)
                lg.nav.matches = MatchesView.WEEK
                lg.nav.week = week
            }
            if (row.stages.isNotEmpty()) {
                Text(row.stages.joinToString(" · ") { stageName(it, short = true) }, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            for (p in coming) {
                Text(
                    "🔮 ${lg.name(p.a)} 대 ${lg.name(p.b)} · ${p.reason}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { lg.nav.open(Page.Match(p.ref)) }.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** The footer: the ranked game and the makers, the clone's invitation, and the league's counts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(c: AppController, v: LeagueView, onRanked: () -> Unit) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Wrapped, not scrolled: at 360 dp the third chip went off the edge.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = onRanked, label = { Text("🎮 랭크전") })
            AssistChip(onClick = { c.editingPlayer = null; c.screen = Screen.MAKER }, label = { Text("🧑 선수 만들기") })
            AssistChip(onClick = { c.editingAi = null; c.screen = Screen.BUILDER }, label = { Text("🛠 AI 만들기") })
        }
        if (lg.clone == null) {
            Text(
                "🧬 ‘혼자 두기’로 ${CloneProfile.MIN_GAMES}판 이상 끝내면 내 플레이를 닮은 AI가 리그에 들어와요.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        Text(countsLine(c, v), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

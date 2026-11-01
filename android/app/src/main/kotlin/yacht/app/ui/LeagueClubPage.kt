package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AccountItem
import yacht.app.core.ClubLifeView
import yacht.app.core.ClubMember
import yacht.app.core.ClubPage
import yacht.app.core.ClubRankKind
import yacht.app.core.ClubTab
import yacht.app.core.CoachLine
import yacht.app.core.InfoTopic
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.MarketTab
import yacht.app.core.MatchQuery
import yacht.app.core.Page
import yacht.app.core.PressureLevel
import yacht.app.core.Sheet
import yacht.app.core.StoryView
import yacht.app.core.TermsView
import yacht.app.core.accountItems
import yacht.app.core.pressureOf
import yacht.engine.explain.Ko
import yacht.engine.league.BoardOutlook
import yacht.engine.league.Comp
import yacht.engine.league.Money
import yacht.engine.league.Nations

// The club page (UX 4): a national club's page, with the club world's four tabs (개요 · 선수단 · 구단
// · 역사) once its league has a club world, and as it was before without one (a legacy season, a
// save not yet migrated); a legacy club league's club stays in LeagueClubs.kt. The texts with
// names in them are ClubTexts' functions (StoryTextsTest reads them).

/** [Page.Club]: a national club's page ([NationalClubPage]), or a club of a legacy season's club league. */
@Composable
internal fun ClubPageBody(lg: LeagueController, v: LeagueView, page: Page.Club, onOpen: (String) -> Unit) {
    if (v.clubs != null || v.badges.isEmpty()) LegacyClubPage(lg, v.clubs, page.id, onOpen) else NationalClubPage(lg, v, page, onOpen)
}

/**
 * A national club's page (UX 4.3): its standing with the 6 counted members and what each brings,
 * this week, the squad (tier, place, rating, season record, 해외), the members' latest league
 * matches, the club's record (places, titles, money, founded), its moves and its numbers.
 */
@Composable
private fun NationalClubPage(lg: LeagueController, v: LeagueView, page: Page.Club, onOpen: (String) -> Unit) {
    val season = v.season
    LaunchedEffect(page.loadKey, season?.number, (season?.played ?: 0) / CLUB_REFRESH) { lg.worldPages.loadClub(page.id) }
    val p = lg.worldPages.club(page)
    if (p == null) {
        CenterText(if (page.id in v.badges) "클럽 기록을 불러오는 중…" else "이 클럽은 이제 리그에 없어요.")
        return
    }
    // Without a club world (and so without its tabs) the page is today's.
    if (p.life == null && p.history == null) ClubPageContent(lg, v, p, onOpen) else ClubWorldPage(lg, v, page, p, onOpen)
}

@Composable
private fun ClubPageContent(lg: LeagueController, v: LeagueView, p: ClubPage, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val nation = Nations.of(p.badge.nation)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClubEmblem(p.badge.emblem, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(p.badge.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        p.badge.city.ifEmpty { null }?.let { c -> nation?.let { "$c · ${it.flag} ${it.short}" } ?: c } ?: nation?.let { "${it.flag} ${it.short}" },
                        p.leagueLabel.ifEmpty { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                p.gone?.let { Text("시즌 $it 종료 후 해체됐어요.", style = MaterialTheme.typography.labelSmall, color = cs.error) }
            }
        }
        p.standing?.let { st ->
            Heading("📊 클럽 순위")
            Text("${p.leagueLabel} ${st.position}위 · ${clubPoints(st.points)}점", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            // 리그 점수 and 수상 보너스 as one stacked bar.
            val total = (st.leaguePart + st.bonus).coerceAtLeast(0.001)
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
                if (st.leaguePart > 0) Box(Modifier.weight((st.leaguePart / total).toFloat()).fillMaxHeight().background(cs.primary))
                if (st.bonus > 0) Box(Modifier.weight((st.bonus / total).toFloat()).fillMaxHeight().background(cs.tertiary))
            }
            Small("리그 점수 ${clubPoints(st.leaguePart)} · 수상 보너스 ${clubPoints(st.bonus)} · 상위 ${st.counted.size}명 합산 (선수 ${st.members}명)")
            val byKey = p.members.associateBy { it.key }
            for (k in st.counted) {
                val m = byKey[k] ?: continue
                Line("${m.shown} ${m.tierLabel.orEmpty()}", m.contribution?.let { "→ ${clubPoints(it)}" }.orEmpty()) { onOpen(k) }
            }
        }
        if (p.week.isNotEmpty()) {
            Heading("📅 이번 주")
            Small("선수단 ${p.week}")
        }
        if (p.members.isNotEmpty()) {
            Heading("👥 선수단 (${p.members.size}명)")
            for (m in p.members) {
                val place = listOfNotNull(m.tierLabel, m.position?.let { "${it}위" }).joinToString(" ")
                Line(
                    (if (m.abroad) "✈️ " else "") + m.shown,
                    listOfNotNull(place.ifEmpty { null }, m.rating?.toString(), m.record.ifEmpty { null }).joinToString(" · "),
                ) { onOpen(m.key) }
            }
            if (p.members.any { it.abroad }) Small("✈️ 해외: 다른 나라 클럽에서 뛰는 선수예요.")
        }
        if (p.recent.isNotEmpty()) {
            Heading("🎲 최근 결과")
            for (m in p.recent) {
                val res = when (m.result) {
                    null -> ""
                    1 -> "승"
                    0 -> "무"
                    else -> "패"
                }
                Row(Modifier.fillMaxWidth().clickable { m.ref?.let { lg.nav.open(Page.Match(it)) } }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(res, Modifier.width(22.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f)) {
                        Text(m.where, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("상대 ", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            ClubMark(m.badge, 12.dp)
                            Text(m.opponentName, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(m.score, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Every match of the squad this season, cup ties and the 클럽 대항전 included.
        LinkLine("선수단 경기 전체", "리그와 대회 경기를 한눈에 봐요.") {
            lg.nav.open(Page.Matches(MatchQuery.OfClub(p.key), "${p.badge.name} 선수단 경기"))
        }
        Heading("📜 클럽 기록")
        Small(
            listOfNotNull(
                "클럽 순위 우승 ${p.titles}회",
                p.cupTitles.takeIf { it > 0 }?.let { "${Comp.CLUB_CUP.label} 우승 ${it}회" },
                "자금 ${p.budget}",
                p.founded.takeIf { it > 0 }?.let { "시즌 $it 창단" },
            ).joinToString(" · "),
        )
        if (p.places.isNotEmpty()) Small(p.places.joinToString(" · ") { (s, place) -> "시즌 $s ${place}위" })
        if (p.moves.isNotEmpty()) {
            Heading("🔁 이적")
            for (m in p.moves) MoveText(lg, m) { id -> v.badges[id]?.name ?: id }
        }
        p.aggregate?.let { a ->
            Heading("📈 클럽 통계 (이번 시즌)")
            Small(
                listOfNotNull(
                    "${a.games}판", "승률 ${"%.1f".format(100 * a.winRate)}%", "평균 ${"%.1f".format(a.avgScore)}점",
                    "요트 ${"%.2f".format(a.yachtRate)}개/판", "보너스 ${"%.0f".format(100 * a.bonusRate)}%",
                    a.avgRating.takeIf { !it.isNaN() }?.let { "평점 ${"%.2f".format(it)}" }, a.avgElo?.let { "평균 레이팅 $it" },
                ).joinToString(" · "),
            )
            if (a.over300 > 0 || a.comebacks > 0 || a.sweeps > 0) Small("300점 이상 ${a.over300}판 · 역전승 ${a.comebacks}판 · 완승 ${a.sweeps}번")
            GroupStyleLine(lg, "c:${p.badge.id}")
        }
    }
}

// ---- the club world's page (UX 4.1-4.5) --------------------------------------------------------

/**
 * A national club's page with its club world (UX 4.1): the header (in full on 개요, one line on the
 * other tabs) over the tab row, then the tab's blocks. A gone club has 개요 and 역사 only.
 */
@Composable
private fun ClubWorldPage(lg: LeagueController, v: LeagueView, page: Page.Club, p: ClubPage, onOpen: (String) -> Unit) {
    val nav = lg.nav
    val life = p.life
    val tabs = if (life != null) ClubTab.entries else listOf(ClubTab.OVERVIEW, ClubTab.HISTORY)
    val tab = page.tab.takeIf { it in tabs } ?: ClubTab.OVERVIEW
    val season = v.season?.number ?: 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (tab == ClubTab.OVERVIEW) ClubHeader(nav, p) else SlimHeader(nav, p)
        }
        TabRow(selectedTabIndex = tabs.indexOf(tab), containerColor = MaterialTheme.colorScheme.background) {
            for (t in tabs) Tab(selected = t == tab, onClick = { nav.replaceTop(page.copy(tab = t)) }, text = { Text(t.label, fontSize = 14.sp) })
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (tab) {
                ClubTab.OVERVIEW -> OverviewTab(lg, v, page, p, onOpen)
                ClubTab.SQUAD -> life?.let { SquadTab(lg, v, p, it, season, onOpen) }
                ClubTab.BUSINESS -> life?.let { BusinessTab(lg, p, it, season) }
                ClubTab.HISTORY -> HistoryTab(lg, v, p, onOpen)
            }
        }
    }
}

/** 개요's header (56 dp): the emblem, the name, "“{nickname}” · {city} · {flag} {nation} · 창단 {year}년", the colours and the 📖 pill. */
@Composable
private fun ClubHeader(nav: yacht.app.core.LeagueNav, p: ClubPage) {
    val cs = MaterialTheme.colorScheme
    val life = p.life
    val nation = Nations.of(p.badge.nation)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ClubEmblem(p.badge.emblem, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.badge.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ClubTexts.identity(life?.nickname, p.badge.city, nation?.let { "${it.flag} ${it.short}" }, life?.foundedYear ?: 0),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (life != null && life.colours.isNotEmpty()) {
                    ColourDot(Color(p.badge.emblem.colour1))
                    ColourDot(Color(p.badge.emblem.colour2))
                    Text(" 팀 컬러 ${life.colours}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                EncPill(nav, "c:${p.badge.id}")
            }
            p.gone?.let { Text("시즌 $it 종료 후 해체됐어요.", style = MaterialTheme.typography.labelSmall, color = cs.error) }
        }
    }
}

/** The other tabs' header: the emblem (24 dp), the name and the 📖 pill on one line. */
@Composable
private fun SlimHeader(nav: yacht.app.core.LeagueNav, p: ClubPage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ClubEmblem(p.badge.emblem, 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(p.badge.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        EncPill(nav, "c:${p.badge.id}")
    }
}

@Composable
private fun ColourDot(c: Color) {
    Box(Modifier.padding(end = 2.dp).size(10.dp).clip(CircleShape).background(c).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
}

// ---- 개요 ----------------------------------------------------------------------------------------

/**
 * 개요 (UX 4.2): the status chips, 📊 클럽 순위, 👔 감독 and the board, © 주장, ⚔ 라이벌 and the next
 * rival match, 💬 이적설, then this week, the latest results and the squad's matches (today's).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(lg: LeagueController, v: LeagueView, page: Page.Club, p: ClubPage, onOpen: (String) -> Unit) {
    val nav = lg.nav
    val life = p.life
    if (life != null) {
        val (_, face) = moodWord(life.mood)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("💰 재정 ${life.health} · ${healthWord(life.health)}") { nav.replaceTop(page.copy(tab = ClubTab.BUSINESS)) }
            StatusChip("📣 팬 만족 ${life.mood} $face") { nav.replaceTop(page.copy(tab = ClubTab.BUSINESS)) }
            life.valueRank?.let { StatusChip("🏅 가치 세계 ${it}위") { nav.open(Page.ClubRanking(ClubRankKind.VALUE)) } }
            // Its open stories (UX 4.2): to 이적 센터's 이적설.
            maxOf(life.openStories, life.stories.size).takeIf { it > 0 }?.let { n -> StatusChip(MarketTexts.storiesChip(n)) { nav.open(Page.Market(MarketTab.LIVE)) } }
        }
    }
    StandingBlock(p, onOpen)
    if (life != null) {
        life.coach?.let { c -> CoachBlock(lg, c, life.board, life.winterNext) }
        life.captain?.let { k ->
            val m = p.members.firstOrNull { it.key == k }
            val since = m?.terms?.since ?: v.season?.number ?: 0
            Heading("© 주장")
            Line(ClubTexts.captain(m?.shown ?: lg.name(k), (v.season?.number ?: since) - since + 1), "›") { onOpen(k) }
        }
        if (life.rivals.isNotEmpty()) {
            Heading("⚔ 라이벌")
            for (r in life.rivals.take(2)) {
                val (w, d, l) = r.record?.of(p.badge.id) ?: Triple(0, 0, 0)
                Line(ClubTexts.rival(r.name, r.badge.name, w, d, l), "›", WRAP) { nav.open(Page.Club(r.badge.id)) }
            }
            life.nextRivalMatch?.let { m ->
                Line("🔮 ${m.week}주차 · ${m.where}: ${m.opponentName}", "›", WRAP) { nav.open(Page.Match(m.ref)) }
            }
        }
        if (life.stories.isNotEmpty()) {
            Heading("💬 이적설")
            for (s in life.stories) StoryLine(s) { onOpen(s.key) }
        }
    }
    if (p.week.isNotEmpty()) {
        Heading("📅 이번 주")
        Small("선수단 ${p.week}")
    }
    RecentBlock(lg, p, rivals = life != null)
    if (p.gone == null) {
        LinkLine("선수단 경기 전체", "리그와 대회 경기를 한눈에 봐요.") {
            lg.nav.open(Page.Matches(MatchQuery.OfClub(p.key), "${p.badge.name} 선수단 경기"))
        }
    }
}

/** A status chip of 개요 (a tap opens what it is about). */
@Composable
private fun StatusChip(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 📊 클럽 순위: the place and points, the stacked bar of league points and honour points, the counted members (today's block). */
@Composable
private fun StandingBlock(p: ClubPage, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val st = p.standing ?: return
    Heading("📊 클럽 순위")
    Text("${p.leagueLabel} ${st.position}위 · ${clubPoints(st.points)}점", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    val total = (st.leaguePart + st.bonus).coerceAtLeast(0.001)
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
        if (st.leaguePart > 0) Box(Modifier.weight((st.leaguePart / total).toFloat()).fillMaxHeight().background(cs.primary))
        if (st.bonus > 0) Box(Modifier.weight((st.bonus / total).toFloat()).fillMaxHeight().background(cs.tertiary))
    }
    Small("리그 점수 ${clubPoints(st.leaguePart)} · 수상 보너스 ${clubPoints(st.bonus)} · 상위 ${st.counted.size}명 합산 (선수 ${st.members}명)")
    val byKey = p.members.associateBy { it.key }
    for (k in st.counted) {
        val m = byKey[k] ?: continue
        Line("${m.shown} ${m.tierLabel.orEmpty()}", m.contribution?.let { "→ ${clubPoints(it)}" }.orEmpty()) { onOpen(k) }
    }
}

/**
 * 👔 감독 (UX 4.2, a first-screen block): the coach (a tap: its page), the board's target, the place
 * and the pressure with ⓘ, and the warning when the next checkpoint would pass the threshold.
 */
@Composable
private fun CoachBlock(lg: LeagueController, c: CoachLine, board: BoardOutlook?, winterNext: Boolean) {
    val nav = lg.nav
    Column(Modifier.shotSection(COACH_SECTION), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Heading("👔 감독")
        Line("${c.shown} · ${c.philosophy.label} · 시즌 ${c.since}부터", "›", WRAP) { nav.open(Page.Coach(c.id)) }
        if (board != null) {
            BoardLine(nav, board.target, board.place, c.pressure ?: pressureOf(board))
            if (board.threshold > 0 && board.projected >= board.threshold) {
                Text(ClubTexts.warning(winterNext), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** "목표 {t}위 · 지금 {p}위 · 압박 ●●●○○ {word}" with ⓘ (the board's sheet). */
@Composable
internal fun BoardLine(nav: yacht.app.core.LeagueNav, target: Int, place: Int, level: PressureLevel) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("목표 ${target}위 · 지금 ${place}위 · 압박 ", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        PressureDots(level)
        Text(" ${level.word}", style = MaterialTheme.typography.labelSmall, color = pressureColor(level), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
        InfoMark(nav, InfoTopic.BOARD)
    }
}

/** The pressure's five dots, [level]'s filled: the good colour up to 2, tertiary at 3, error from 4 (UX 5). */
@Composable
internal fun PressureDots(level: PressureLevel) {
    val cs = MaterialTheme.colorScheme
    val on = pressureColor(level)
    Row {
        for (i in 1..PressureLevel.entries.size) {
            Box(Modifier.padding(end = 2.dp).size(8.dp).clip(CircleShape).background(if (i <= level.dots) on else cs.surfaceVariant))
        }
    }
}

@Composable
private fun pressureColor(level: PressureLevel): Color = when {
    level.dots <= 2 -> gameColors.good
    level.dots == 3 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/** ⓘ: [topic]'s explanation in the info sheet. */
@Composable
internal fun InfoMark(nav: yacht.app.core.LeagueNav, topic: InfoTopic) {
    Text(
        " ⓘ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable {
            nav.info = topic
            nav.sheet = Sheet.INFO
        }.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** A transfer story's line (UX 6.2's line 1): its stage, the player, the clubs and the fee. */
@Composable
private fun StoryLine(s: StoryView, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            ClubTexts.story(storyStage(s.stage), s.shown, s.seller?.name, s.buyer?.name),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = WRAP, overflow = TextOverflow.Ellipsis,
        )
        Text(Money.text(s.fee), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** 🎲 최근 결과 (today's block); with [rivals] (a club world's page) a rival match has "⚔" before its score (UX 6.6). */
@Composable
private fun RecentBlock(lg: LeagueController, p: ClubPage, rivals: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    if (p.recent.isEmpty()) return
    if (rivals) LaunchedEffect(p.key, p.recent.firstOrNull()?.ref) { lg.center.loadRivals() }
    val marks = if (rivals) lg.center.rivals() else null
    Heading("🎲 최근 결과")
    for (m in p.recent) {
        val res = when (m.result) {
            null -> ""
            1 -> "승"
            0 -> "무"
            else -> "패"
        }
        Row(Modifier.fillMaxWidth().clickable { m.ref?.let { lg.nav.open(Page.Match(it)) } }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(res, Modifier.width(22.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Column(Modifier.weight(1f)) {
                Text(m.where, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("상대 ", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    ClubMark(m.badge, 12.dp)
                    Text(m.opponentName, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text((if (marks?.of(m.ref) != null) RIVAL_MARK else "") + m.score, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---- 선수단 --------------------------------------------------------------------------------------

/**
 * 선수단 (UX 4.3): the lead sentence and the squad's value, the members in two lines (number,
 * captain, 해외, name and marks; contract and value), those out on loan, the academy's graduates and
 * the season's numbers.
 */
@Composable
private fun SquadTab(lg: LeagueController, v: LeagueView, p: ClubPage, life: ClubLifeView, season: Int, onOpen: (String) -> Unit) {
    val share = life.income?.takeIf { it > 0 }?.let { Math.round(100.0 * life.wages / it).toInt() }
    Text(ClubTexts.squadLead(p.members.size, life.averageAge, share), style = MaterialTheme.typography.bodyMedium)
    Small("선수단 가치 ${Money.text(life.squadValue)}")
    Heading("👥 선수단 (${p.members.size}명)")
    for (m in p.members) MemberRow(m, season) { onOpen(m.key) }
    if (p.members.any { it.abroad }) Small("✈️ 해외: 다른 나라 클럽에서 뛰는 선수예요.")
    if (p.members.any { m -> m.terms?.let { memberMarks(it, season) }.orEmpty().isNotEmpty() }) Small("🌱 유스 · ↔ 임대 · ⏳ 마지막 시즌 · 🔓 바이아웃")
    if (p.loansOut.isNotEmpty()) {
        Heading("↔ 임대 보낸 선수")
        for (m in p.loansOut) {
            val club = v.rows.firstOrNull { it.key == m.key }?.club?.let { v.badges[it] }
            val place = listOfNotNull(m.tierLabel, m.position?.let { "${it}위" }).joinToString(" ")
            Line("${m.shown} → ${club?.name ?: "–"}", place) { onOpen(m.key) }
        }
    }
    if (life.graduates > 0) {
        Heading("🌱 유스 출신")
        Small(ClubTexts.graduates(life.graduates))
    }
    AggregateBlock(lg, p)
}

/** A member in two lines (40 dp): number, ©, ✈️, name and marks, its place on the right; its contract's end and value under it. */
@Composable
private fun MemberRow(m: ClubMember, season: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = m.terms
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t?.number?.toString().orEmpty(), Modifier.width(26.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(ClubTexts.member(m.shown, t?.captain == true, m.abroad, t?.let { memberMarks(it, season) }.orEmpty()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(m.tierLabel, m.position?.let { "${it}위" }).joinToString(" "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        if (t != null) Text("시즌 ${t.until}까지 · 몸값 ${Money.text(t.value)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp))
    }
}

/** A member's marks (UX 4.3): 🌱 its club's academy graduate, ↔ on loan, ⏳ its contract's last season, 🔓 a release clause. */
internal fun memberMarks(t: TermsView, season: Int): String = buildString {
    if (t.academy) append("🌱")
    if (t.loanFrom != null) append("↔")
    if (t.until <= season) append("⏳")
    if (t.clause > 0) append("🔓")
}

/** 📊 클럽 통계 (이번 시즌): today's aggregate block under its new icon (UX 14.2). */
@Composable
private fun AggregateBlock(lg: LeagueController, p: ClubPage) {
    val a = p.aggregate ?: return
    Heading("📊 클럽 통계 (이번 시즌)")
    Small(
        listOfNotNull(
            "${a.games}판", "승률 ${"%.1f".format(100 * a.winRate)}%", "평균 ${"%.1f".format(a.avgScore)}점",
            "요트 ${"%.2f".format(a.yachtRate)}개/판", "보너스 ${"%.0f".format(100 * a.bonusRate)}%",
            a.avgRating.takeIf { !it.isNaN() }?.let { "평점 ${"%.2f".format(it)}" }, a.avgElo?.let { "평균 레이팅 $it" },
        ).joinToString(" · "),
    )
    if (a.over300 > 0 || a.comebacks > 0 || a.sweeps > 0) Small("300점 이상 ${a.over300}판 · 역전승 ${a.comebacks}판 · 완승 ${a.sweeps}번")
    GroupStyleLine(lg, "c:${p.badge.id}")
}

// ---- 구단 ----------------------------------------------------------------------------------------

/** 구단 (UX 4.4): the trouble card, 💰 재정, 🏢 구단주 · 🪧 스폰서, 🏟 홈구장·시설 and 📣 팬. */
@Composable
private fun BusinessTab(lg: LeagueController, p: ClubPage, life: ClubLifeView, season: Int) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    ClubTexts.trouble(life.trouble)?.let { text ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.errorContainer)) {
            Text("⚠ $text", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer, fontWeight = FontWeight.SemiBold)
        }
    }
    FinanceBlock(nav, life, season)
    Heading("🏢 구단주 · 🪧 스폰서")
    val o = life.owner
    if (o.name.isNotEmpty()) {
        Text("${ownerIcon(o.type)} ${o.type.label} · ${o.name}" + (if (o.since > 0) " · 시즌 ${o.since}부터" else ""), style = MaterialTheme.typography.bodySmall)
        Small("야망 ${stars(o.ambition)} · 인내심 ${stars(o.patience)}")
        Small(o.type.desc)
    }
    val sp = life.sponsor
    if (sp != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ClubTexts.sponsor(sp.name, Money.text(sp.perSeason), sp.until), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
            if (sp.record) StatusChip("역대 최고 계약") {}
        }
    } else {
        Small("메인 스폰서가 없어요. 새 스폰서를 찾고 있어요.")
    }
    life.stadium.naming?.let { Small("구장 명명권: $it") }
    StadiumBlock(nav, life)
    Heading("📣 팬")
    val (_, face) = moodWord(life.mood)
    Text(ClubTexts.fans(life.fans, life.fansRank, Nations.of(p.badge.nation)?.short ?: p.badge.nation, life.fansNationRank, life.mood, face), style = MaterialTheme.typography.bodySmall)
    FillBar(life.mood / 100.0, if (life.mood < 25) cs.error else gameColors.good)
}

/**
 * 💰 재정 (UX 4.4, a first-screen block): the lead sentence, the balance and grade, value and
 * transfer budget; a bar a side with its three biggest items (all of them folded under "항목 모두
 * 보기"), and one chart of the kept seasons with a 자금 / 팬 toggle; before the first accounts, when they come.
 */
@Composable
private fun FinanceBlock(nav: yacht.app.core.LeagueNav, life: ClubLifeView, season: Int) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.shotSection(FINANCE_SECTION), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Heading("💰 재정")
            InfoMark(nav, InfoTopic.FINANCE)
        }
        val a = life.last
        if (a != null) {
            Text(ClubTexts.accounts(Money.text(a.income), Money.text(a.expenses), a.net), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        } else {
            Text(ClubTexts.firstAccounts(season), style = MaterialTheme.typography.bodyMedium)
        }
        Small("자금 ${Money.text(life.balance)} · 재정 ${life.health} ${healthWord(life.health)}")
        // In 재정 위기 or 법정관리 the club may not buy: no budget to show.
        val budget = if (life.trouble >= 2) "선수 영입 금지" else "이적 예산 약 ${Money.text(life.budget)}"
        Small("클럽 가치 ${Money.text(life.value)}" + (life.valueRank?.let { " (세계 ${it}위)" } ?: "") + " · $budget")
        if (a != null) {
            val (incomes, expenses) = accountItems(a)
            val top = maxOf(a.income, a.expenses).coerceAtLeast(1)
            var all by remember { mutableStateOf(false) }
            ItemsBar("수입", if (all) incomes else life.incomeTop, a.income, top, gameColors.good)
            ItemsBar("지출", if (all) expenses else life.expenseTop, a.expenses, top, cs.error)
            if (incomes.size > life.incomeTop.size || expenses.size > life.expenseTop.size) {
                Text(
                    if (all) "항목 접기 ▴" else "항목 모두 보기 ▾", style = MaterialTheme.typography.labelSmall, color = cs.primary,
                    modifier = Modifier.clickable { all = !all }.padding(vertical = 2.dp),
                )
            }
        }
        if (life.years.size >= 2) {
            var fans by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("최근 ${life.years.size}시즌", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                FilterChip(selected = !fans, onClick = { fans = false }, label = { Text("자금", fontSize = 12.sp) })
                FilterChip(selected = fans, onClick = { fans = true }, label = { Text("팬", fontSize = 12.sp) })
            }
            val ys = life.years.reversed()
            val values = ys.map { (if (fans) it.fans else it.balance).toDouble() }
            fun text(x: Double) = if (fans) "%,d명".format(x.toLong()) else Money.text(x.toInt())
            LineChart(values, labels = "시즌 ${ys.first().season} · ${text(values.first())}" to "시즌 ${ys.last().season} · ${text(values.last())}")
        }
    }
}

/**
 * One side of the accounts: its total's share of [top] as a bar, each of [items] its part of the
 * total (a lighter shade each; the items not listed together, lightest), and the items under it.
 */
@Composable
private fun ItemsBar(label: String, items: List<AccountItem>, total: Int, top: Int, color: Color) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${Money.text(total)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
        Row(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
            val whole = total.coerceAtLeast(1).toFloat()
            val share = (total.toFloat() / top).coerceIn(0f, 1f)
            for ((i, it) in items.withIndex()) {
                val w = share * it.amount / whole
                if (w > 0f) Box(Modifier.weight(w).fillMaxHeight().background(color.copy(alpha = 1f - 0.2f * i.coerceAtMost(3))))
            }
            val rest = share * (total - items.sumOf { it.amount }).coerceAtLeast(0) / whole
            if (rest > 0f) Box(Modifier.weight(rest).fillMaxHeight().background(color.copy(alpha = 0.25f)))
            if (share < 1f) Spacer(Modifier.weight(1f - share))
        }
    }
    Small(items.joinToString(" · ") { "${it.label} ${Money.text(it.amount)}" })
}

/** 🏟 홈구장·시설: the ground and its crowd (with a fill bar), a project under way, the facilities with ⓘ. */
@Composable
private fun StadiumBlock(nav: yacht.app.core.LeagueNav, life: ClubLifeView) {
    val st = life.stadium
    Heading("🏟 홈구장·시설")
    Text(ClubTexts.stadium(st.name, st.capacity, st.attendance, Math.round(100 * st.fill).toInt()), style = MaterialTheme.typography.bodySmall)
    FillBar(st.fill, gameColors.me)
    st.project?.let { pr -> Small(ClubTexts.project(pr.kind, pr.target, pr.opens, Money.text(pr.paid), Money.text(pr.cost))) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Small("훈련 시설 ${levelDots(life.training)} ${facilityWord(life.training)} · 유스 아카데미 ${levelDots(life.academy)} ${facilityWord(life.academy)}")
        InfoMark(nav, InfoTopic.FACILITIES)
    }
}

/** A thin bar filled to [fraction] (0..1). */
@Composable
private fun FillBar(fraction: Double, color: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

/** A level 1..5 as five dots ("●●●○○"). */
internal fun levelDots(level: Int): String = "●".repeat(level.coerceIn(0, 5)) + "○".repeat(5 - level.coerceIn(0, 5))

/** A 1..5 score as five stars ("★★★★☆"). */
internal fun stars(n: Int): String = "★".repeat(n.coerceIn(0, 5)) + "☆".repeat(5 - n.coerceIn(0, 5))

// ---- 역사 ----------------------------------------------------------------------------------------

/**
 * 역사 (UX 4.5): 🏆 우승 and the kept places, 👑 클럽 명예의 전당, 📜 클럽 기록, ⚔ 라이벌 전적, 👔 역대 감독,
 * 💸 주요 이적 and the moves (today's list).
 */
@Composable
private fun HistoryTab(lg: LeagueController, v: LeagueView, p: ClubPage, onOpen: (String) -> Unit) {
    val nav = lg.nav
    val h = p.history
    Heading("🏆 우승")
    Small(
        listOfNotNull(
            ClubTexts.titles(p.titles, h?.titles.orEmpty().sorted()),
            p.cupTitles.takeIf { it > 0 }?.let { "${Comp.CLUB_CUP.label} 우승 ${it}회" },
            h?.memberTitles?.takeIf { it > 0 }?.let { "소속 선수 주요 우승 ${it}회" },
        ).joinToString(" · "),
    )
    if (p.places.isNotEmpty()) Small(p.places.joinToString(" · ") { (s, place) -> "시즌 $s ${place}위" })
    if (h != null) {
        if (h.legends.isNotEmpty()) {
            Heading("👑 클럽 명예의 전당")
            for (l in h.legends) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(l.key) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ClubTexts.legend(l.number, l.shown, l.seasons, l.titles), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = WRAP, overflow = TextOverflow.Ellipsis)
                    if (l.retired) StatusChip("영구결번") { onOpen(l.key) }
                    if (l.oneClub) {
                        Spacer(Modifier.width(4.dp))
                        StatusChip("원클럽맨") { onOpen(l.key) }
                    }
                }
            }
        }
        if (h.records.isNotEmpty()) {
            Heading("📜 클럽 기록")
            for (r in h.records) {
                val line = ClubTexts.record(r.label, r.holder, r.value, r.season)
                if (r.key != null) Line(line, "›", WRAP) { onOpen(r.key) } else Small(line)
            }
        }
    }
    p.life?.let { life ->
        Heading("⚔ 라이벌 전적")
        if (life.rivals.isEmpty()) Small(ClubTexts.NO_RIVALS)
        for (r in life.rivals) {
            val (w, d, l) = r.record?.of(p.badge.id) ?: Triple(0, 0, 0)
            Line(ClubTexts.rivalHistory(r.name, r.badge.name, w, d, l, r.season, r.since), "›", WRAP) { nav.open(Page.Club(r.badge.id)) }
        }
    }
    if (h != null && h.tenures.isNotEmpty()) {
        Heading("👔 역대 감독")
        for (t in h.tenures) Line(ClubTexts.tenure(t.from, t.to, t.shown, t.best, t.titles, tenureEnd(t.end)), "›", WRAP) { nav.open(Page.Coach(t.coach)) }
    }
    if (h != null && h.notable.isNotEmpty()) {
        Heading("💸 주요 이적")
        for (f in h.notable) {
            val sold = f.from?.id == p.badge.id
            Line(ClubTexts.fee(f.season, f.shown, sold, (if (sold) f.to else f.from)?.name ?: "–", Money.text(f.fee)), "›", WRAP) { onOpen(f.key) }
        }
    }
    if (p.moves.isNotEmpty()) {
        Heading("🔁 이적")
        for (m in p.moves) MoveText(lg, m, money = true) { id -> v.badges[id]?.name ?: id }
    }
}

/** The lines a list row of the club world's tabs may take: long names (a rivalry, a player with its flag) wrap rather than being cut. */
internal const val WRAP = 2

/** The blocks the first-screen checks look for (UX 15.2): 개요's 👔 and 구단's 💰. */
internal const val COACH_SECTION = "club_coach"
internal const val FINANCE_SECTION = "club_finance"

/**
 * The club pages' texts with names and numbers in them (UX 4, 해요체), as functions so StoryTextsTest
 * reads each with names ending with and without a final consonant.
 */
internal object ClubTexts {
    /** 개요's identity line: "“{nickname}” · {city} · {flag nation} · 창단 {year}년" (the parts it has). */
    fun identity(nickname: String?, city: String, nation: String?, founded: Int): String = listOfNotNull(
        nickname?.ifEmpty { null }?.let { "“$it”" }, city.ifEmpty { null }, nation, founded.takeIf { it > 0 }?.let { "창단 ${it}년" },
    ).joinToString(" · ")

    /** The board's warning (UX 4.2): the next checkpoint would pass the sacking threshold. */
    fun warning(winterNext: Boolean): String =
        if (winterNext) "⚠ 지금 성적이면 겨울 점검에서 감독이 바뀔 수 있어요." else "⚠ 이 성적으로 시즌을 마치면 감독이 바뀔 수 있어요."

    fun captain(name: String, seasons: Int): String = "$name · 이 클럽 ${seasons.coerceAtLeast(1)}시즌째"

    fun rival(name: String, rival: String, w: Int, d: Int, l: Int): String = "$name · vs $rival · 통산 ${w}승 ${d}무 ${l}패"

    fun rivalHistory(name: String, rival: String, w: Int, d: Int, l: Int, season: List<Int>, since: Int): String =
        rival(name, rival, w, d, l) + " · 이번 시즌 ${season.getOrElse(0) { 0 }}승 ${season.getOrElse(1) { 0 }}무 ${season.getOrElse(2) { 0 }}패 · 시즌 ${since}부터"

    const val NO_RIVALS = "아직 라이벌이 없어요. 우승을 다투거나 큰 이적이 오가면 라이벌이 생겨요."

    /** A story's line: "📝 협상 중 {player}: {seller} → {buyer}". */
    fun story(stage: String, player: String, seller: String?, buyer: String?): String = "$stage $player: ${seller ?: "–"} → ${buyer ?: "–"}"

    /** 선수단's lead: "선수 {n}명, 평균 {age}세예요. 연봉으로 수입의 {p}%를 써요." (the second only with an income). */
    fun squadLead(members: Int, age: Double, wageShare: Int?): String =
        (if (age > 0) "선수 ${members}명, 평균 ${"%.1f".format(age)}세예요." else "선수 ${members}명이에요.") +
            (wageShare?.let { " 연봉으로 수입의 $it%를 써요." } ?: "")

    /** A member's first line: ©, ✈️, the name and its marks. */
    fun member(shown: String, captain: Boolean, abroad: Boolean, marks: String): String =
        (if (captain) "© " else "") + (if (abroad) "✈️ " else "") + shown + (if (marks.isEmpty()) "" else " $marks")

    fun graduates(n: Int): String = "이 클럽 유스 출신 ${n}명이 뛰고 있어요 (다른 클럽 포함)."

    /** 구단's ⚠ card by the trouble level (1 적자, 2 재정 위기, 3 법정관리); null for none. */
    fun trouble(level: Int): String? = when (level) {
        1 -> "자금이 마이너스예요. 이대로면 다음 이적시장이 어려워져요."
        2 -> "재정 위기예요. 이번 이적시장에서는 선수를 살 수 없고, 주축 선수를 팔아야 할 수도 있어요."
        3 -> "법정관리 중이에요. 다음 시즌 클럽 순위 승점이 10% 깎여요. 새 주인을 찾고 있어요."
        else -> null
    }

    /** 💰 재정's lead: "지난 시즌 {inc}을 벌고 {exp}을 써서 {net} {남았어요|모자랐어요}." (amounts end in 억: 을). */
    fun accounts(income: String, expenses: String, net: Int): String =
        "지난 시즌 ${income}을 벌고 ${expenses}을 써서 ${Money.text(Math.abs(net))} ${if (net >= 0) "남았어요" else "모자랐어요"}."

    fun firstAccounts(season: Int): String = "첫 결산은 ${Ko.i("시즌 $season")} 끝나면 나와요."

    fun sponsor(name: String, perSeason: String, until: Int): String = "메인 스폰서 $name · 한 시즌 $perSeason · 시즌 ${until}까지"

    fun stadium(name: String, capacity: Int, attendance: Int, fill: Int): String =
        "$name · ${"%,d".format(capacity)}석 · 평균 관중 ${"%,d".format(attendance)}명 ($fill%)"

    /** A stadium project's line: an expansion ('E') or a new ground. */
    fun project(kind: Char, target: Int, opens: Int, paid: String, cost: String): String =
        if (kind == 'E') "🏗 증축 공사 중: ${"%,d".format(target)}석, 시즌 $opens 완공 (공사 중에는 좌석이 10% 줄어요)"
        else "🏗 새 구장 건설 중: ${"%,d".format(target)}석, 시즌 $opens 개장 · 공사비 $paid/$cost"

    fun fans(fans: Int, rank: Int, nation: String, nationRank: Int, mood: Int, face: String): String =
        "팬 ${"%,d".format(fans)}명 · 세계 인기 ${rank}위 · $nation ${nationRank}위 · 팬 만족도 $mood $face"

    /** "클럽 순위 우승 {n}회 ({seasons})": the seasons the kept places show. */
    fun titles(n: Int, seasons: List<Int>): String =
        "클럽 순위 우승 ${n}회" + (if (seasons.isEmpty()) "" else " (시즌 ${seasons.joinToString("·")})")

    fun legend(number: Int?, shown: String, seasons: Int, titles: Int): String =
        (number?.let { "$it " } ?: "") + "$shown · ${seasons}시즌 · 우승 ${titles}회"

    fun record(label: String, holder: String, value: String, season: Int): String = "$label · $holder · $value (시즌 $season)"

    /** A coach's spell: "시즌 {from}–{to} · {name} · 최고 {best}위 · 우승 {t}회 · {end}" (no end season while it goes on). */
    fun tenure(from: Int, to: Int, name: String, best: Int, titles: Int, end: String): String =
        "시즌 $from–${if (to == 0) "" else to.toString()} · $name · " + (if (best > 0) "최고 ${best}위 · " else "") + "우승 ${titles}회 · $end"

    /** A notable transfer: "시즌 {s} · {player} {→ | ←} {club} · {fee}" (→ sold to the club, ← bought from it). */
    fun fee(season: Int, player: String, sold: Boolean, club: String, fee: String): String = "시즌 $season · $player ${if (sold) "→" else "←"} $club · $fee"
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A tappable line with a note on its right ([lines]: the lines its text may take, the last one cut). */
@Composable
private fun Line(name: String, note: String, lines: Int = 1, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = lines, overflow = TextOverflow.Ellipsis)
        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

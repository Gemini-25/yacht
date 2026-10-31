package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.LeagueController
import yacht.app.core.LeagueNav
import yacht.app.core.LeagueTab
import yacht.app.core.LeagueView
import yacht.app.core.MatchesView
import yacht.app.core.Page
import yacht.app.core.RecordsView
import yacht.app.core.Screen
import yacht.app.core.SeasonView
import yacht.app.core.Sheet
import yacht.app.core.TableMode
import yacht.app.core.TablesView
import yacht.engine.league.Comp
import yacht.engine.league.Nations
import yacht.engine.league.WeekStage

/** Shows a followed player's division in 순위 › 리그 (the first division without one). */
private fun LeagueNav.showFollowed(season: SeasonView?, favourites: Set<String>) {
    val divisions = season?.divisions ?: return
    val tiers = season.tiers.takeIf { it.size == divisions.size } ?: yacht.engine.league.Tiers.linear(divisions.size)
    showDivision(tiers, divisions.indices.firstOrNull { d -> divisions[d].any { it.key in favourites } } ?: 0)
}

/**
 * The AI league: the control bar (or the open page's bar), the week bar, the tab's chip row and
 * sub-view (or the page), the bottom bar with 홈 · 경기 · 순위 · 기록, and the sheet over them.
 * Everything shown is a state of the controller's [LeagueNav].
 */
@Composable
fun LeagueScreen(c: AppController) {
    val lg = c.league
    val v = lg.view
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    var resetAsk by remember { mutableStateOf(false) }
    var rankedAsk by remember { mutableStateOf(false) }
    val page = nav.page
    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize()) {
            if (page == null) {
                ControlBar(
                    c,
                    onExit = { c.screen = Screen.HOME },
                    onWeek = { showWeek(nav) },
                    onSettings = { nav.sheet = Sheet.SETTINGS },
                )
            } else {
                PageBar(c, v, page)
            }
            val clock = v?.clock
            if (clock != null) WeekBar(clock, Modifier.fillMaxWidth().height(4.dp).clickable { showWeek(nav) }) else Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    v == null -> CenterText("리그 기록을 불러오는 중…")
                    page != null -> PageBody(c, v, page)
                    else -> TabBody(c, v) { rankedAsk = true }
                }
            }
            LeagueTabBar(nav.tab) { t ->
                val from = nav.tab
                nav.go(t)
                // 순위 opened from another tab shows a followed player's division, as the old 리그 tab did.
                if (t == LeagueTab.TABLES && from != t && nav.tables == TablesView.LEAGUE) nav.showFollowed(v?.season, lg.favourites)
            }
        }
        LeagueSheets(c, v) {
            nav.sheet = Sheet.NONE
            resetAsk = true
        }
        PlayerActionsSheet(c)
    }
    if (rankedAsk) RankedStartDialog(c) { rankedAsk = false }
    lg.review?.let { SeasonReviewDialog(lg, it) }
    if (resetAsk) {
        AlertDialog(
            onDismissRequest = { resetAsk = false },
            confirmButton = { TextButton(onClick = { resetAsk = false; lg.reset() }) { Text("초기화") } },
            dismissButton = { TextButton(onClick = { resetAsk = false }) { Text("취소") } },
            title = { Text("리그를 처음부터 다시 할까요?") },
            text = { Text("모든 경기 기록과 레이팅이 지워져요.", style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

/** 경기 › 이번 주 at the week in play. */
private fun showWeek(nav: LeagueNav) {
    nav.go(LeagueTab.MATCHES)
    nav.matches = MatchesView.WEEK
    nav.week = null
}

/** The bottom bar (64 dp): the four tabs, [selected] lit (also while a page opened from it is up). */
@Composable
private fun LeagueTabBar(selected: LeagueTab, onTab: (LeagueTab) -> Unit) {
    NavigationBar(Modifier.height(64.dp), containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
        for (t in LeagueTab.entries) {
            NavigationBarItem(
                selected = t == selected,
                onClick = { onTab(t) },
                icon = { Text(t.icon, fontSize = 18.sp) },
                label = { Text(t.label, fontSize = 11.sp) },
            )
        }
    }
}

/** A tab's sub-views as one row of chips that never needs scrolling at a phone's width ([state] hoisted for the harness). */
@Composable
private fun <T> ChipRow(state: LazyListState, entries: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().height(40.dp),
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(entries) { t -> FilterChip(selected = t == selected, onClick = { onSelect(t) }, label = { Text(label(t), fontSize = 12.sp, maxLines = 1) }) }
    }
}

/** The tab shown: 홈, or the chip row of 경기, 순위 or 기록 and its sub-view ([onRanked]: the ranked game's picker). */
@Composable
private fun TabBody(c: AppController, v: LeagueView, onRanked: () -> Unit) {
    val lg = c.league
    val nav = lg.nav
    val open: (String) -> Unit = { nav.open(Page.Player(it)) }
    when (nav.tab) {
        LeagueTab.HOME -> LeagueHome(c, v, onRanked)
        LeagueTab.MATCHES -> Column {
            ChipRow(nav.chipState(LeagueTab.MATCHES), MatchesView.entries, nav.matches, { it.label }) { nav.matches = it }
            Box(Modifier.weight(1f)) {
                when (nav.matches) {
                    MatchesView.WEEK -> WeekTab(
                        lg, v,
                        onLeague = { s -> showRound(lg, v, s) },
                        onComp = { comp -> showComp(nav, comp) },
                        onNews = { nav.open(Page.News) },
                    )
                    MatchesView.RESULTS -> FeedList(c, v)
                    MatchesView.BROADCAST -> LiveBoardView(c)
                    MatchesView.HIGHLIGHTS -> Highlights(c, v)
                }
            }
        }
        LeagueTab.TABLES -> Column {
            ChipRow(nav.chipState(LeagueTab.TABLES), TablesView.entries, nav.tables, { it.label }) { nav.tables = it }
            Box(Modifier.weight(1f)) {
                when (nav.tables) {
                    TablesView.LEAGUE -> SeasonList(lg, v.season, open)
                    TablesView.COMPS -> CompsTab(lg, v)
                    TablesView.CLUBS -> ClubsTab(lg, v.clubs, v.season?.format)
                    TablesView.RANKING -> RankingTab(lg)
                    TablesView.LADDER -> LadderList(lg, v, open)
                }
            }
        }
        LeagueTab.RECORDS -> Column {
            ChipRow(nav.chipState(LeagueTab.RECORDS), RecordsView.entries, nav.records, { it.label }) { nav.records = it }
            Box(Modifier.weight(1f)) { RecordsTab(lg, v, open) }
        }
    }
}

/** A league round of the week tapped: 순위 › 리그 at that round, on a followed player's division. */
private fun showRound(lg: LeagueController, v: LeagueView, s: WeekStage) {
    val nav = lg.nav
    nav.go(LeagueTab.TABLES)
    nav.tables = TablesView.LEAGUE
    nav.showFollowed(v.season, lg.favourites)
    nav.tableMode = TableMode.ROUND
    nav.round = s.stage
}

/** A competition's stage tapped: its page (the club league is 순위 › 클럽). */
private fun showComp(nav: LeagueNav, comp: Comp) {
    if (comp == Comp.CLUB) {
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.CLUBS
    } else {
        nav.open(Page.Competition(comp))
    }
}

/** 순위 › 랭킹: the league ranking comes with the national leagues; the national teams' ranking is a page. */
@Composable
private fun RankingTab(lg: LeagueController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("리그 랭킹", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
        Text(
            "나라마다 리그가 열리면 국제 대회 성적으로 리그 랭킹을 매겨요. 지금은 모든 선수가 한 리그에서 겨뤄서 리그 랭킹이 없어요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinkLine("🏳 국가대표 랭킹", "나라별 대표팀의 대회 성적과 전력") { lg.nav.open(Page.Nations) }
    }
}

/** A page's bar (56 dp): ←, its title, its actions, and 🔴 while the league runs (a tap: back to the tab). */
@Composable
private fun PageBar(c: AppController, v: LeagueView?, page: Page) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    val nav = lg.nav
    Row(Modifier.fillMaxWidth().height(56.dp).background(cs.background).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.close(page) }) { Text("←", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics { contentDescription = "뒤로" }) }
        Text(pageTitle(lg, v, page), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when (page) {
            is Page.Nation -> {
                val fav = nationFavourite(page.code) in lg.favourites
                TextButton(onClick = { lg.toggleFavourite(nationFavourite(page.code)) }) { Text(if (fav) "★ 응원 중" else "☆ 응원") }
            }
            Page.News -> TextButton(onClick = { nav.newsFollowed = !nav.newsFollowed }) { Text(if (nav.newsFollowed) "★ 응원만" else "☆ 전체") }
            is Page.Player -> PlayerBarActions(lg, page)
            is Page.Match -> MatchBarAction(c, page)
            else -> {}
        }
        if (lg.running) {
            IconButton(onClick = { nav.go(nav.tab) }) { Text("🔴", fontSize = 12.sp, modifier = Modifier.clearAndSetSemantics { contentDescription = "리그 진행 중 · 탭으로 돌아가기" }) }
        }
    }
}

/** A page's title in its bar. */
private fun pageTitle(lg: LeagueController, v: LeagueView?, page: Page): String = when (page) {
    is Page.Competition -> v?.comps?.firstOrNull { it.first == page.comp }?.second?.title ?: page.comp.label
    is Page.Match -> "경기 리포트"
    is Page.Club -> v?.clubs?.clubs?.firstOrNull { it.id == page.id }?.name ?: "클럽"
    Page.Market -> "이적·드래프트"
    is Page.Nation -> Nations.of(page.code)?.let { "${it.flag} ${it.name} 대표팀" } ?: page.code
    Page.Nations -> "국가대표 랭킹"
    is Page.Compare -> "선수 비교"
    is Page.Rules -> "규칙 설정"
    Page.Lab -> "AI 연구소"
    Page.News -> "뉴스"
    Page.Prospects -> "유망주"
    Page.Legends -> "레전드 보관소"
    Page.Families -> "계열별"
    Page.Meta -> "메타 리포트"
    Page.Follows -> "응원"
    Page.Federation -> "요트협회"
    is Page.Leaderboard -> page.kind.label
    is Page.Review -> "시즌 ${page.season} 결산"
    is Page.Matches -> page.title
    is Page.League -> page.id
    is Page.Player -> lg.name(page.key)
}

/** The open page's body. */
@Composable
private fun PageBody(c: AppController, v: LeagueView, page: Page) {
    val lg = c.league
    val nav = lg.nav
    val open: (String) -> Unit = { nav.open(Page.Player(it)) }
    when (page) {
        is Page.Player -> PlayerPageBody(c, v, page)
        is Page.Competition -> CompetitionScreen(lg, v, page, open)
        is Page.Match -> MatchReportPage(c, v, page)
        is Page.Club -> ClubPageBody(lg, v.clubs, page.id, open)
        Page.Market -> MarketBody(lg, v.clubs)
        is Page.Nation -> NationPage(lg, page.code, open)
        Page.Nations -> NationRanking(lg)
        is Page.Compare -> ComparePage(lg, page, v.rows)
        is Page.Rules -> RulesPage(lg, page.part) { nav.close(page) }
        Page.Lab -> LabPage(lg)
        Page.News -> NewsPage(lg, v)
        Page.Prospects -> Prospects(lg, open)
        Page.Legends -> Legends(lg, open)
        Page.Families -> FamilyList(lg, v, open)
        Page.Meta -> {
            androidx.compose.runtime.LaunchedEffect(v.season?.number) { lg.loadRecords() }
            MetaReportView(lg.records)
        }
        // The followed players, the federation, the leaderboards, the reviews, match lists and the league pages come later.
        else -> CenterText("곧 볼 수 있어요.")
    }
}

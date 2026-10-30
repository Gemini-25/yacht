@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.LeagueNav
import yacht.app.core.LeagueTab
import yacht.app.core.MatchesView
import yacht.app.core.Page
import yacht.app.core.Screen
import yacht.app.core.SeasonView
import yacht.app.core.TablesView
import yacht.engine.league.Comp

/** The tab bar's seven tabs, each shown by a state of the controller's [LeagueNav] ([screenTab], [show]). */
private enum class ScreenTab(val label: String) {
    LADDER("순위표"),
    SCHEDULE("일정"),
    SEASON("리그"),
    COMPS("대회"),
    CLUBS("클럽"),
    NEWS("소식"),
    RECORDS("기록"),
}

/** The tab that shows [nav]'s state: 홈 is the ladder, the league opens on it. */
private fun screenTab(nav: LeagueNav): ScreenTab = when (nav.tab) {
    LeagueTab.HOME -> ScreenTab.LADDER
    LeagueTab.MATCHES -> if (nav.matches == MatchesView.WEEK) ScreenTab.SCHEDULE else ScreenTab.NEWS
    LeagueTab.TABLES -> when (nav.tables) {
        TablesView.LEAGUE -> ScreenTab.SEASON
        TablesView.COMPS -> ScreenTab.COMPS
        TablesView.CLUBS -> ScreenTab.CLUBS
        TablesView.RANKING, TablesView.LADDER -> ScreenTab.LADDER
    }
    LeagueTab.RECORDS -> ScreenTab.RECORDS
}

/** Opens tab [t] at its root (pages and the sheet close). */
private fun LeagueNav.show(t: ScreenTab) {
    when (t) {
        ScreenTab.LADDER -> go(LeagueTab.HOME)
        ScreenTab.SCHEDULE -> {
            go(LeagueTab.MATCHES)
            matches = MatchesView.WEEK
        }
        ScreenTab.NEWS -> {
            go(LeagueTab.MATCHES)
            if (matches == MatchesView.WEEK) matches = MatchesView.RESULTS
        }
        ScreenTab.SEASON -> {
            go(LeagueTab.TABLES)
            tables = TablesView.LEAGUE
        }
        ScreenTab.COMPS -> {
            go(LeagueTab.TABLES)
            tables = TablesView.COMPS
        }
        ScreenTab.CLUBS -> {
            go(LeagueTab.TABLES)
            tables = TablesView.CLUBS
        }
        ScreenTab.RECORDS -> go(LeagueTab.RECORDS)
    }
}

/** Opens the 리그 tab on a followed player's division (the first division without one). */
private fun LeagueNav.showSeason(season: SeasonView?, favourites: Set<String>) {
    show(ScreenTab.SEASON)
    val divisions = season?.divisions ?: return
    val tiers = season.tiers.takeIf { it.size == divisions.size } ?: yacht.engine.league.Tiers.linear(divisions.size)
    showDivision(tiers, divisions.indices.firstOrNull { d -> divisions[d].any { it.key in favourites } } ?: 0)
}

/** Opens competition [comp] in the 대회 tab (the club league has its own tab). */
private fun LeagueNav.showComp(comp: Comp) {
    if (comp == Comp.CLUB) return show(ScreenTab.CLUBS)
    show(ScreenTab.COMPS)
    open(Page.Competition(comp))
}

/** The AI league: start/stop, the live ladder, match news and families. */
@Composable
fun LeagueScreen(c: AppController) {
    val lg = c.league
    val v = lg.view
    val nav = lg.nav
    val open: (String) -> Unit = { nav.open(Page.Player(it)) }
    // Leaving the league (a ranked game, watching) closes a nation sheet or comparison left open.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            lg.closeNation()
            lg.closeCompare()
            for (p in nav.pages.filterIsInstance<Page.Compare>()) nav.close(p)
        }
    }
    var resetAsk by remember { mutableStateOf(false) }
    var rankedAsk by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                title = {
                    Column {
                        Text("AI 리그", fontWeight = FontWeight.Bold)
                        Text(
                            // A league of generated players: its players (the hand-made AIs left it).
                            (if (lg.config.baseAis) "${lg.size}개 AI" else "선수 ${"%,d".format(v?.rows?.count { !it.isMe && it.origin != yacht.engine.league.Origin.BASE } ?: 0)}명") +
                                (v?.let { " · 총 ${"%,d".format(it.matches)}경기" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = { c.screen = Screen.HOME }) { Text("홈") } },
                actions = {
                    TextButton(onClick = { nav.open(Page.Lab) }) { Text("연구소") }
                    TextButton(onClick = { nav.open(Page.Rules()) }) { Text("규칙") }
                    TextButton(onClick = { resetAsk = true }) { Text("초기화") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ControlCard(c, v) {
                nav.week = null
                nav.show(ScreenTab.SCHEDULE)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(onClick = { rankedAsk = true }, label = { Text("🎮 랭크전") })
                AssistChip(
                    onClick = { watchFeatured(c) },
                    label = { Text("👀 경기 관전") },
                )
                AssistChip(onClick = { c.editingPlayer = null; c.screen = Screen.MAKER }, label = { Text("🧑 선수 만들기") })
                AssistChip(onClick = { c.editingAi = null; c.screen = Screen.BUILDER }, label = { Text("🛠 AI 만들기") })
            }
            lg.seasonNews?.let { SeasonNews(it) { lg.seasonNews = null } }
            val tabs = ScreenTab.entries
            val current = screenTab(nav)
            // Seven short tabs fit a phone's width side by side (a scrolling row hid the last ones):
            // without the default 16 dp padding on each side of a tab's text.
            TabRow(selectedTabIndex = current.ordinal, containerColor = cs.background) {
                for (t in tabs) {
                    Tab(
                        selected = current == t,
                        onClick = {
                            when (t) {
                                // The tab shown tapped again: it stays as it is (its chip, its division).
                                current -> {}
                                ScreenTab.SEASON -> nav.showSeason(v?.season, lg.favourites)
                                else -> nav.show(t)
                            }
                        },
                    ) {
                        Text(t.label, maxLines = 1, fontSize = 13.sp, softWrap = false, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
            if (v == null) {
                CenterText("리그 기록을 불러오는 중…")
            } else {
                when (current) {
                    ScreenTab.LADDER -> LadderList(lg, v, open)
                    ScreenTab.SCHEDULE -> ScheduleTab(lg, v, onLeague = { nav.showSeason(v.season, lg.favourites) }, onComp = { nav.showComp(it) })
                    ScreenTab.SEASON -> SeasonList(lg, v.season, open)
                    ScreenTab.COMPS -> CompsTab(lg, v.comps, v.clock, v.season?.format, open)
                    ScreenTab.CLUBS -> ClubsTab(lg, v.clubs, v.season?.format, open)
                    ScreenTab.NEWS -> NewsTab(c, v)
                    ScreenTab.RECORDS -> RecordsTab(lg, v, open)
                }
            }
        }
    }
    lg.nationSheet?.let { NationSheetDialog(lg, it, open) }
    // Until the new screens come, the pages are dialogs over the tabs (the top one drawn last).
    for (p in nav.pages) {
        androidx.compose.runtime.key(p) {
            when (p) {
                is Page.Player -> {
                    val row = v?.rows?.firstOrNull { it.key == p.key }
                    if (row != null) AiDetail(c, row, open, onCompare = { nav.replaceTop(Page.Compare(row.key)) }) { nav.close(p) }
                    else if (v != null) PastProfileDialog(lg, p.key, open) { nav.close(p) }
                }
                is Page.Compare -> CompareDialog(lg, p.a, v?.rows.orEmpty()) { nav.close(p) }
                is Page.Rules -> LeagueRulesDialog(lg) { nav.close(p) }
                Page.Lab -> LabDialog(lg) { nav.close(p) }
                // The others pick a chip of their tab (기록's, 대회's).
                else -> {}
            }
        }
    }
    if (rankedAsk) RankedStartDialog(c) { rankedAsk = false }
    lg.preview?.let { PreviewDialog(lg, it) }
    lg.review?.let { if (nav.page !is Page.Player) SeasonReviewDialog(lg, it) }
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

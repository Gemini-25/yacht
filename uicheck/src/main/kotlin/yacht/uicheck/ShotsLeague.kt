package yacht.uicheck

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import yacht.app.core.AppController
import yacht.app.core.LeagueTab
import yacht.app.core.MatchesView
import yacht.app.core.Page
import yacht.app.core.RecordsView
import yacht.app.core.ResultsFilter
import yacht.app.core.RulesPart
import yacht.app.core.Sheet
import yacht.app.core.TableMode
import yacht.app.core.TablesView
import yacht.app.ui.BarStatus
import yacht.app.ui.BarTone
import yacht.app.ui.BracketCard
import yacht.app.ui.ControlBarRow
import yacht.app.ui.GroupCard
import yacht.app.ui.HomeBody
import yacht.engine.league.BracketSide
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.DrawEntry
import yacht.engine.league.GroupRow
import yacht.engine.league.GroupTable
import yacht.engine.league.Knockout
import yacht.engine.league.KnockoutLength
import yacht.engine.league.TieScore
import java.io.File

/**
 * The league screen's own shots (lg00-lg45 that need no new data, and the pages S6 turned the old
 * dialogs into), and the width checks: no tab's chip row may scroll at 392 dp, and the home's
 * content stays within [HOME_MAX_DP].
 */
internal object ShotsLeague : LeagueShots {
    /** The home's height budget at 392 dp (UX 2). */
    private const val HOME_MAX_DP = 1600f

    override fun empty(c: AppController, out: File) {
        shot(c, out, "lg00_empty")
    }

    override fun running(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        val v = lg.view ?: error("no league view")
        // A followed player for the ★ rows (put back at the end: the other files' shots see the league as it was).
        val top = v.rows.filter { it.rank != null && !it.isMe && it.division != null }.sortedBy { it.rank }
        val followedBefore = lg.favourites
        top.firstOrNull()?.key?.let { if (it !in lg.favourites) lg.toggleFavourite(it) }

        // 홈 (v1), its height budget, and the settings sheet.
        shot(c, out, "lg01_home", heightDp = 1600)
        shot(c, out, "lg01_home_dark", dark = true, heightDp = 1600)
        shot(c, out, "lg01_home_360", heightDp = 1600, widthDp = 360)
        checkHomeHeight(c, out)
        nav.sheet = Sheet.SETTINGS
        shot(c, out, "lg02_settings", heightDp = 1100)
        nav.sheet = Sheet.NONE

        // 경기.
        nav.go(LeagueTab.MATCHES)
        nav.matches = MatchesView.WEEK
        shot(c, out, "lg10_week", heightDp = 1500)
        checkChips(c, LeagueTab.MATCHES)
        shot(c, out, "lg10_week_360", heightDp = 1500, widthDp = 360)
        nav.sheet = Sheet.WEEKS
        shot(c, out, "lg15_weeks", heightDp = 1300)
        nav.sheet = Sheet.NONE
        nav.matches = MatchesView.RESULTS
        nav.results = ResultsFilter.ALL
        shot(c, out, "lg11_results", heightDp = 1300)
        nav.results = ResultsFilter.FOLLOWED
        shot(c, out, "lg12_results_followed", heightDp = 1100)
        nav.results = ResultsFilter.ALL
        nav.matches = MatchesView.BROADCAST
        shot(c, out, "lg13_broadcast", heightDp = 1300, settleMs = 1500)
        nav.matches = MatchesView.HIGHLIGHTS
        shot(c, out, "lg14_highlights", heightDp = 1300)

        // 순위 › 리그 in each view, on a followed player's division.
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.LEAGUE
        nav.tableMode = TableMode.TABLE
        shot(c, out, "lg20_table", heightDp = 1500)
        checkChips(c, LeagueTab.TABLES)
        shot(c, out, "lg20_table_dark", dark = true, heightDp = 1500)
        shot(c, out, "lg20_table_360", heightDp = 1500, widthDp = 360)
        tableShots(c, out)
        nav.tableMode = TableMode.TABLE
        nav.sheet = Sheet.TABLE_MODE
        shot(c, out, "lg27_table_mode", heightDp = 900)
        nav.sheet = Sheet.NONE

        // 순위 › 대회 and the competition pages.
        nav.tables = TablesView.COMPS
        shot(c, out, "lg28_comps", heightDp = 1300)
        competitionShot(c, out, Comp.CUP, "lg29_cup")
        if (competitionShot(c, out, Comp.CHAMPIONS, "lg29b_cl") || competitionShot(c, out, Comp.CUP, null)) {
            nav.sheet = Sheet.STAGE
            shot(c, out, "lg29d_stage", heightDp = 1100)
            nav.sheet = Sheet.NONE
        }
        nav.go(LeagueTab.TABLES)
        bracketShots(c, out)

        // 순위 › 클럽 and its pages, 국가대표 랭킹, a nation's page, 레이팅.
        nav.tables = TablesView.CLUBS
        shot(c, out, "lg30_clubs", heightDp = 1300)
        lg.view?.clubs?.let { clubs ->
            (clubs.table.firstOrNull()?.club ?: clubs.clubs.firstOrNull()?.id)?.let { id ->
                nav.open(Page.Club(id))
                shot(c, out, "lg55_club", heightDp = 1500)
                nav.go(LeagueTab.TABLES)
            }
            nav.open(Page.Market)
            shot(c, out, "lg74_market", heightDp = 1300)
            nav.go(LeagueTab.TABLES)
        }
        nav.open(Page.Nations)
        lg.loadNations()
        waitFor("nations") { lg.nations.isNotEmpty() }
        shot(c, out, "lg32_nations", heightDp = 1300)
        nav.open(Page.Nation("KR"))
        lg.loadNation("KR")
        waitFor("nation page") { lg.nationSheet?.row?.code == "KR" }
        shot(c, out, "lg57_nation", heightDp = 1500)
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.LADDER
        shot(c, out, "lg33_ladder", heightDp = 1500)
        nav.sheet = Sheet.LADDER_FILTER
        shot(c, out, "lg34_ladder_filter", heightDp = 900)
        nav.sheet = Sheet.NONE

        // 기록.
        nav.go(LeagueTab.RECORDS)
        nav.records = RecordsView.LEADERS
        lg.loadRecords()
        waitFor("records") { lg.records != null }
        shot(c, out, "lg40_leaders", heightDp = 1500)
        checkChips(c, LeagueTab.RECORDS)
        shot(c, out, "lg40_leaders_360", heightDp = 1500, widthDp = 360)
        nav.records = RecordsView.ROOM
        shot(c, out, "lg42_room", heightDp = 1500)
        nav.records = RecordsView.HALL
        shot(c, out, "lg43_hall", heightDp = 1300)
        nav.records = RecordsView.SEASONS
        shot(c, out, "lg44_seasons", heightDp = 1100)
        nav.records = RecordsView.MORE
        shot(c, out, "lg45_more", heightDp = 900)
        nav.open(Page.Prospects)
        lg.loadProspects()
        waitFor("prospects") { lg.prospects.isNotEmpty() }
        shot(c, out, "lg45b_prospects", heightDp = 1300)
        nav.go(LeagueTab.RECORDS)

        // The pages the old dialogs became, and a match's page before the reports.
        nav.open(Page.News)
        shot(c, out, "lg70_news", heightDp = 1500)
        nav.go(LeagueTab.HOME)
        nav.open(Page.Rules(RulesPart.CUPS))
        shot(c, out, "lg72_rules", heightDp = 1300, settleMs = 500)
        nav.go(LeagueTab.HOME)
        nav.open(Page.Lab)
        shot(c, out, "lg73_lab", heightDp = 1300)
        nav.go(LeagueTab.HOME)
        lg.view?.highlights?.firstOrNull()?.let { h ->
            nav.go(LeagueTab.MATCHES)
            nav.matches = MatchesView.HIGHLIGHTS
            nav.open(Page.Match(h.id))
            shot(c, out, "lg60_report", heightDp = 1100)
        }
        nav.go(LeagueTab.HOME)
        if (lg.favourites != followedBefore) top.firstOrNull()?.key?.let { lg.toggleFavourite(it) }
    }

    override fun weekDone(c: AppController, out: File) {
        // A season that closed on the way shows its review once: read (closed) first, as a player would.
        if (c.league.review != null) c.league.dismissReview()
        shot(c, out, "lg04_week_done", heightDp = 850)
        // A run stopped by an error, as the bar says it (a hand-made reason: the league never fails here).
        shotOf(out, "lg05_status_error", 120) {
            ControlBarRow(
                "시즌 1 · 7주차 / 42주", BarStatus("⚠ 저장 공간이 부족해서 멈췄어요. 공간을 비우고 ▶를 눌러 주세요.", BarTone.ERROR),
                running = false, preparing = false, enabled = true, onExit = {}, onToggle = {}, onTap = {}, onSettings = {},
            )
        }
    }

    /** 순위 › 리그's other views of the division shown, each with its data loaded first. */
    private fun tableShots(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        val season = lg.view?.season ?: return
        val tiers = season.tiers.takeIf { it.size == season.divisions.size } ?: yacht.engine.league.Tiers.linear(season.divisions.size)
        val d = tiers.groups(nav.tier.coerceIn(0, tiers.count - 1)).let { g -> (g.first + nav.group).coerceIn(g.first, g.last) }
        nav.tableMode = TableMode.FORM
        lg.loadForm(d)
        waitFor("form") { lg.form?.division == d }
        shot(c, out, "lg21_form", heightDp = 1300, settleMs = 500)
        nav.tableMode = TableMode.LUCK
        shot(c, out, "lg22_luck", heightDp = 1300)
        nav.tableMode = TableMode.ROUND
        lg.loadRound(d)
        waitFor("round") { lg.calendar?.division == d }
        shot(c, out, "lg23_round", heightDp = 1300, settleMs = 500)
        nav.tableMode = TableMode.CHART
        nav.chartKeys = season.divisions[d].take(4).map { it.key }
        shot(c, out, "lg24_chart", heightDp = 1300)
        nav.chartKeys = emptyList()
        nav.tableMode = TableMode.H2H
        val label = tiers.label(d)
        lg.loadH2h(label, season.divisions[d].map { it.key })
        waitFor("h2h", 120_000) { lg.h2h?.title == label }
        shot(c, out, "lg25_h2h", heightDp = 1300, settleMs = 500)
        nav.tableMode = TableMode.ODDS
        // The odds come every 10% of the season: shot with or without them.
        runCatching { waitFor("odds", 30_000) { lg.view?.season?.divisions?.getOrNull(d)?.any { it.odds != null } == true } }
        shot(c, out, "lg26_odds", heightDp = 1300)
    }

    /** [comp]'s page at the stage in play (shot as [name] when given); false when the season has no such competition. */
    private fun competitionShot(c: AppController, out: File, comp: Comp, name: String?): Boolean {
        val lg = c.league
        if (lg.view?.comps?.none { it.first == comp } != false) return false
        lg.nav.go(LeagueTab.TABLES)
        lg.nav.tables = TablesView.COMPS
        lg.nav.open(Page.Competition(comp))
        lg.worldPages.loadCompetition(comp)
        waitFor("competition $comp") { lg.worldPages.competitions[Page.Competition(comp).loadKey] != null }
        if (name != null) shot(c, out, name, heightDp = 1500)
        return true
    }

    /** Fails the run when tab [t]'s chip row (shown in the last shot, at 392 dp) can scroll. */
    private fun checkChips(c: AppController, t: LeagueTab) {
        val state = c.league.nav.chipState(t)
        check(state.layoutInfo.totalItemsCount > 0) { "the ${t.name} chip row was not drawn" }
        check(!state.canScrollForward && !state.canScrollBackward) { "the ${t.name} chip row scrolls at 392 dp" }
        println("chip row ${t.name}: fits")
    }

    /** Fails the run when the home's content is taller than [HOME_MAX_DP] at 392 dp (rendered alone, as lg01_home_body). */
    private fun checkHomeHeight(c: AppController, out: File) {
        val v = c.league.view ?: return
        var height by mutableStateOf(0f)
        shotOf(out, "lg01_home_body", (HOME_MAX_DP + 200).toInt()) {
            val density = LocalDensity.current
            Box(Modifier.onSizeChanged { height = with(density) { it.height.toDp().value } }) { HomeBody(c, v) {} }
        }
        check(height in 1f..HOME_MAX_DP) { "the home is ${height.toInt()} dp high (at most ${HOME_MAX_DP.toInt()})" }
        println("home: ${height.toInt()} dp high")
    }
}

/**
 * A double-elimination bracket played out by hand (deterministic, whatever the league did):
 * the losers' side and the grand final, and a group table under the 완승 bonus.
 */
private fun bracketShots(c: AppController, out: File) {
    val keys = c.league.view?.rows?.filter { it.rank != null }?.sortedBy { it.rank }?.map { it.key }?.take(8) ?: emptyList()
    if (keys.size < 8) return
    val entries = keys.mapIndexed { i, k -> DrawEntry(k, i + 1, 1500.0 - 20 * i) }
    val labels = listOf("8강", "4강", "결승")
    val k = Knockout(
        Knockout.pairings(entries), { 1 }, { labels[it] }, { 0.0 },
        winsOf = { r -> KnockoutLength.wins(8 shr r, true, grandAfter = true) },
        double = true,
        losersWins = { KnockoutLength.losersWins(it, true) },
        grandWins = KnockoutLength.grandWins(true),
    )
    // Wins of the winner side by tie, upsets every third tie, one series with a drawn game.
    var n = 0
    while (true) {
        val t = k.all.firstOrNull { it.ready } ?: break
        val need = t.firstTo.coerceAtLeast(1).toDouble()
        val upset = n % 3 == 1
        val other = if (n == 4) need - 0.5 else (n % (need.toInt())).toDouble().coerceAtMost(need - 1)
        val score = if (upset) TieScore(other, need, 230 * 3, 245 * 3, -1) else TieScore(need, other, 250 * 3, 228 * 3, 1)
        k.record(t.id, t.a!!.key, t.b!!.key, score)
        n++
    }
    val bracket = CompetitionView.Bracket("요트컵 (예시)", k.view())
    // The losers' final and the grand final (the stage sheet picks a round on the page).
    val losers = bracket.rounds.indexOfLast { it.side == BracketSide.LOSERS }
    val grand = bracket.rounds.indexOfLast { it.side == BracketSide.GRAND }
    shotOf(out, "16i_bracket_losers", 1100) { BracketCard(bracket, c.league::name, {}, round = losers) }
    shotOf(out, "16j_bracket_grand", 900) { BracketCard(bracket, c.league::name, {}, round = grand) }
    shotOf(out, "16j_bracket_grand_dark", 900, dark = true) { BracketCard(bracket, c.league::name, {}, round = grand) }
    val rows = keys.take(4).mapIndexed { i, key ->
        val w = 3 - i
        val d = if (i == 1) 1 else 0
        val sw = maxOf(0, 2 - i)
        GroupRow(key, played = 4, wins = w, draws = d, losses = 4 - w - d, points = 3 * w + d + sw, diff = 60 - 40 * i, sweeps = sw)
    }
    shotOf(out, "16k_group_bonus", 500) { GroupCard(GroupTable("A조", rows, bonus = true), c.league::name, {}) }
}

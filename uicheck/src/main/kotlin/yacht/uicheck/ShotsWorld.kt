package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.LeagueController
import yacht.app.core.LeagueInfo
import yacht.app.core.LeagueTab
import yacht.app.core.MatchesView
import yacht.app.core.Page
import yacht.app.core.SeasonView
import yacht.app.core.Sheet
import yacht.app.core.TableMode
import yacht.app.core.TablesView
import yacht.engine.league.Comp
import yacht.engine.league.LeagueIds
import java.io.File

/**
 * The world's shots: the league selector and the tables per league (clubs, leagues, the ranking
 * and the federation come with S16). A legacy season has one league and no selector, so the
 * harness's run (today's rules until S16) takes none of them; RunCheck `national` takes them on a
 * national league ([nationalShots], [everyLeague]).
 */
internal object ShotsWorld : LeagueShots {
    /** A national season (the harness's own league from S16 on): the selector over 순위 › 리그. */
    override fun running(c: AppController, out: File) {
        if (c.league.view?.season?.national == true) selectorShot(c, out)
    }

    /** lg03_selector ([name]): 리그 고르기 over 순위 › 리그. */
    fun selectorShot(c: AppController, out: File, name: String = "lg03_selector") {
        val nav = c.league.nav
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.LEAGUE
        nav.tableMode = TableMode.TABLE
        nav.sheet = Sheet.LEAGUES
        shot(c, out, name, heightDp = 1300)
        nav.go(LeagueTab.HOME)
    }

    /**
     * A national league's screens for review: the selector, the shown league's table (also dark
     * and at 360 dp), the home, this week, the competition cards and the 대륙 챔피언스 and title
     * playoff pages when the season has them, and the best-ranked player's page.
     */
    fun nationalShots(c: AppController, out: File, prefix: String) {
        val lg = c.league
        val nav = lg.nav
        selectorShot(c, out, "${prefix}_lg03_selector")
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.LEAGUE
        nav.tableMode = TableMode.TABLE
        shot(c, out, "${prefix}_lg20_table", heightDp = 1500)
        shot(c, out, "${prefix}_lg20_table_dark", dark = true, heightDp = 1500)
        shot(c, out, "${prefix}_lg20_table_360", heightDp = 1500, widthDp = 360)
        nav.go(LeagueTab.HOME)
        shot(c, out, "${prefix}_lg01_home", heightDp = 1600)
        nav.go(LeagueTab.MATCHES)
        nav.matches = MatchesView.WEEK
        shot(c, out, "${prefix}_lg10_week", heightDp = 1500)
        competitionShots(c, out, prefix)
        lg.view?.rows?.filter { it.rank != null && it.division != null && !it.isMe }?.minByOrNull { it.rank!! }?.let { top ->
            nav.open(Page.Player(top.key))
            lg.analytics.loadPlayer(top.key, yacht.app.core.StatScope.SEASON)
            waitFor("player page") { lg.analytics.player(Page.Player(top.key)) != null }
            shot(c, out, "${prefix}_lg50_player", heightDp = 1800)
        }
        nav.go(LeagueTab.HOME)
    }

    /**
     * 순위 › 대회 and the national competitions' pages with their stage sheets (one heading a
     * section): the 대륙 챔피언스 and the title playoffs (a bracket a league), when the season has
     * them. Returns the pages drawn.
     */
    fun competitionShots(c: AppController, out: File, prefix: String): Int {
        val lg = c.league
        val nav = lg.nav
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.COMPS
        shot(c, out, "${prefix}_lg28_comps", heightDp = 1300)
        var drawn = 0
        for ((comp, name) in listOf(Comp.CONT_CHAMPIONS to "lg29c_ccl", Comp.TITLE_PLAYOFF to "lg75_playoffs")) {
            if (lg.view?.comps?.none { it.first == comp } != false) continue
            nav.open(Page.Competition(comp))
            lg.worldPages.loadCompetition(comp)
            waitFor("competition $comp") { lg.worldPages.competitions[Page.Competition(comp).loadKey] != null }
            shot(c, out, "${prefix}_$name", heightDp = 1500)
            nav.sheet = Sheet.STAGE
            shot(c, out, "${prefix}_${name}_stage", heightDp = 1100)
            nav.sheet = Sheet.NONE
            nav.go(LeagueTab.TABLES)
            drawn++
        }
        return drawn
    }

    /**
     * Every league's 순위 › 리그 (RunCheck `national`): each league at every tier in the table
     * view and its 1부 in each other view, its data loaded first and checked against the
     * selector's row, each drawn as "<prefix>_<league>_<tier>_<view>"; the lower tiers' rounds and
     * form are loaded and checked too (not drawn: a shorter tier spreads its rounds over the
     * weeks). Fails on the first view that is wrong or cannot be drawn. With [out] null only the
     * snapshot of each league is checked (nothing loaded or drawn: the league may play on
     * meanwhile). Returns the views drawn. Ends on 홈 with the default league.
     */
    fun everyLeague(c: AppController, out: File?, prefix: String): Int {
        val lg = c.league
        val nav = lg.nav
        val infos = lg.view?.season?.leagues.orEmpty()
        check(infos.size > 1 && infos.all { it.national }) { "not a national season: ${infos.map { it.id }}" }
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.LEAGUE
        var drawn = 0
        for (info in infos) {
            for (tier in 0 until info.tiers) {
                nav.selectLeague(info.id, tier)
                waitFor("league ${info.id}") { lg.view?.season?.league == info.id }
                val season = lg.view!!.season!!
                checkLeague(lg, season, season.leagues.first { it.id == info.id })
                if (out == null) continue
                if (tier > 0) for (mode in listOf(TableMode.ROUND, TableMode.FORM)) load(lg, season, tier, mode)
                for (mode in if (tier == 0) TableMode.entries else listOf(TableMode.TABLE)) {
                    nav.tableMode = mode
                    load(lg, season, tier, mode)
                    shot(c, out, "${prefix}_${info.id}_${tier + 1}_${mode.name.lowercase()}", heightDp = 1300)
                    drawn++
                }
            }
        }
        nav.tableMode = TableMode.TABLE
        nav.selectLeague(null)
        nav.go(LeagueTab.HOME)
        return drawn
    }

    /** Fails unless [season] shows league [info] whole: its divisions, sizes, labels and every member's ladder line. */
    private fun checkLeague(lg: LeagueController, season: SeasonView, info: LeagueInfo) {
        val id = info.id
        check(season.firstDivision == info.first) { "$id: first division ${season.firstDivision}, its row says ${info.first}" }
        check(season.divisions.map { it.size } == info.sizes) { "$id: divisions ${season.divisions.map { it.size }}, its row says ${info.sizes}" }
        check(season.tiers.size == info.tiers && season.tiers.linear) { "$id: tiers ${season.tiers.size}, not a ladder of ${info.tiers}" }
        check(season.positions.size == info.tiers && season.order.size == info.tiers && season.rounds.size == info.tiers) { "$id: positions, order or rounds not per division" }
        check(info.members == info.sizes.sum()) { "$id: ${info.members} members in ${info.sizes}" }
        val rows = lg.view!!.rows.associateBy { it.key }
        for ((t, table) in season.divisions.withIndex()) {
            check(table.map { it.key }.toSet() == season.order[t].toSet()) { "$id ${t + 1}부: the table and the chart's order differ" }
            for (r in table) {
                val row = rows[r.key] ?: error("$id ${t + 1}부: ${r.key} has no ladder line")
                check(row.league == id && row.division == info.first + t) { "$id ${t + 1}부: ${r.key}'s line says ${row.league} ${row.division}" }
                check(row.divisionLabel == LeagueIds.division(id, t)) { "$id ${t + 1}부: ${r.key}'s division is called ${row.divisionLabel}" }
            }
        }
    }

    /** [mode]'s data for the shown league's [tier] (the season's index for the loaders), checked when it comes. */
    private fun load(lg: LeagueController, season: SeasonView, tier: Int, mode: TableMode) {
        val gd = season.firstDivision + tier
        val keys = season.divisions[tier].map { it.key }
        when (mode) {
            TableMode.FORM -> {
                lg.loadForm(gd)
                waitFor("form of $gd") { lg.form?.let { it.division == gd && it.season == season.number } == true }
                check(lg.form!!.form.keys == keys.toSet()) { "form of $gd: other players" }
            }
            TableMode.ROUND -> {
                lg.loadRound(gd)
                waitFor("round of $gd") { lg.calendar?.let { it.division == gd && it.season == season.number } == true }
                val cal = lg.calendar!!
                check(cal.rounds == season.roundsOf(tier)) { "round of $gd: ${cal.rounds} rounds, the table says ${season.roundsOf(tier)}" }
                check(cal.round in 0 until cal.rounds && cal.currentRound in 0..cal.rounds) { "round of $gd: round ${cal.round}, in play ${cal.currentRound} of ${cal.rounds}" }
                check(cal.items.isNotEmpty() && cal.items.all { it.a in keys && it.b in keys }) { "round of $gd: no fixture, or one of another division" }
                // Its rounds before and after are its own: each step lands on a round of this division.
                check((cal.prev == null) == (cal.round == 0) && (cal.next == null) == (cal.round == cal.rounds - 1)) { "round of $gd: ◀ ${cal.prev} ▶ ${cal.next} at ${cal.round}" }
            }
            TableMode.H2H -> {
                val title = LeagueIds.division(season.league, tier)
                lg.loadH2h(title, keys)
                waitFor("head to head of $gd", 120_000) { lg.h2h?.title == title }
            }
            TableMode.ODDS -> waitFor("odds of ${season.league}", 120_000) { lg.view?.season?.takeIf { it.league == season.league }?.divisions?.getOrNull(tier)?.any { it.odds != null } == true }
            TableMode.TABLE, TableMode.LUCK, TableMode.CHART -> {}
        }
    }
}

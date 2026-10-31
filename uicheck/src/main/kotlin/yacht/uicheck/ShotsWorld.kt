package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.CompetitionPage
import yacht.app.core.FederationView
import yacht.app.core.LeagueController
import yacht.app.core.LeagueInfo
import yacht.app.core.LeagueTab
import yacht.app.core.MatchesView
import yacht.app.core.Page
import yacht.app.core.RankRow
import yacht.app.core.RankingView
import yacht.app.core.RulesPart
import yacht.app.core.SeasonView
import yacht.app.core.Sheet
import yacht.app.core.TableMode
import yacht.app.core.TablesView
import yacht.app.core.competitionStages
import yacht.app.core.proposalView
import yacht.app.ui.CompetitionBody
import yacht.app.ui.FederationSection
import yacht.app.ui.RankingBody
import yacht.engine.league.BracketRound
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.Confed
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueIds
import yacht.engine.league.MatchFormat
import yacht.engine.league.Proposal
import yacht.engine.league.ProposalKind
import yacht.engine.league.TieView
import java.io.File

/**
 * The world's shots: the league selector, the tables per league with their club badges, the
 * club table and a club's page, a league's page, the league ranking, the 요트협회 and the rules of
 * national leagues (the harness's own league is national since the flip); then a legacy pass on a
 * league of the old single-league rules ([last]). RunCheck `national` takes the per-league ones on
 * a national league of its own ([nationalShots], [everyLeague]).
 */
internal object ShotsWorld : LeagueShots {
    /** The national season while it runs: the selector, 순위's world views and the world's pages. */
    override fun running(c: AppController, out: File) {
        if (c.league.view?.season?.national != true) return
        selectorShot(c, out)
        tableShots(c, out)
        clubShots(c, out)
        rankingShots(c, out)
        federationShots(c, out)
        playoffShot(c, out)
        val lg = c.league
        val nav = lg.nav
        // 대륙 챔피언스, when this season has it.
        if (lg.view?.comps?.any { it.first == Comp.CONT_CHAMPIONS } == true) {
            nav.go(LeagueTab.TABLES)
            nav.tables = TablesView.COMPS
            nav.open(Page.Competition(Comp.CONT_CHAMPIONS))
            lg.worldPages.loadCompetition(Comp.CONT_CHAMPIONS)
            waitFor("대륙 챔피언스") { lg.worldPages.competitions[Page.Competition(Comp.CONT_CHAMPIONS).loadKey] != null }
            shot(c, out, "lg29c_ccl", heightDp = 1500)
        }
        nav.go(LeagueTab.HOME)
    }

    /**
     * The legacy pass (UX 7.1): a new league of the single league's rules ([LeagueConfig] as the
     * app had it before national leagues) from the seed, a short run, 순위 › 리그 and its club
     * league, then the same table once the next season's rules are national leagues again (the
     * note under the header). The league is left so, for the passes after this one.
     */
    override fun last(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        lg.stop()
        waitFor("league stopped", 120_000) { !lg.running }
        lg.changeConfig(LeagueConfig())
        lg.reset()
        waitFor("legacy league", 120_000) { lg.view?.let { it.matches == 0L && it.season == null } == true && lg.config == LeagueConfig() }
        if (lg.hasSeed) {
            lg.loadSeed()
            waitFor("legacy seed", 120_000) { (lg.view?.matches ?: 0L) > 0L }
        }
        lg.changeRunMode(yacht.app.core.LeagueRunMode.ON)
        lg.start()
        waitFor("legacy season", 600_000) { lg.view?.season?.let { !it.national && it.played >= 300 } == true }
        lg.stop()
        waitFor("league stopped", 120_000) { !lg.running }
        check(lg.view?.clubs != null) { "a legacy season without its club league" }
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.LEAGUE
        nav.tableMode = TableMode.TABLE
        nav.selectLeague(null)
        shot(c, out, "lg20_table_legacy", heightDp = 1500)
        nav.tables = TablesView.CLUBS
        shot(c, out, "lg30_clubs_legacy", heightDp = 1300)
        // The next season's rules national again: the season in play says it ends as it began.
        lg.changeConfig(LeagueConfig.DEFAULT)
        waitFor("national next") { lg.view?.season?.nextNational == true }
        nav.tables = TablesView.LEAGUE
        shot(c, out, "lg20_table_legacy_next", heightDp = 900)
        nav.go(LeagueTab.HOME)
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

    /**
     * 순위 › 리그's other views of the shown league's division (lg21-lg26, each with its data
     * loaded first by the season's division index, [SeasonView.firstDivision] + its place).
     */
    private fun tableShots(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.LEAGUE
        val season = lg.view?.season ?: return
        val tier = nav.tier.coerceIn(0, season.divisions.size - 1)
        val d = season.firstDivision + tier
        val keys = season.divisions[tier].map { it.key }
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
        nav.chartKeys = keys.take(4)
        shot(c, out, "lg24_chart", heightDp = 1300)
        nav.chartKeys = emptyList()
        nav.tableMode = TableMode.H2H
        val title = LeagueIds.division(season.league, tier)
        lg.loadH2h(title, keys)
        waitFor("h2h", 120_000) { lg.h2h?.title == title }
        shot(c, out, "lg25_h2h", heightDp = 1300, settleMs = 500)
        nav.tableMode = TableMode.ODDS
        // The odds come every 10% of the season: shot with or without them.
        runCatching { waitFor("odds", 30_000) { lg.view?.season?.divisions?.getOrNull(tier)?.any { it.odds != null } == true } }
        shot(c, out, "lg26_odds", heightDp = 1300)
        nav.tableMode = TableMode.TABLE
        nav.go(LeagueTab.HOME)
    }

    /** The best-ranked player in a league with a club: its club, its league. */
    private fun top(lg: LeagueController) = lg.view?.rows?.filter { it.rank != null && !it.isMe && it.club != null && it.league.isNotEmpty() }?.minByOrNull { it.rank!! }

    /** lg30_clubs (순위 › 클럽 of the shown league), lg55_club (the best player's club), lg56_league (its league), lg74_market. */
    private fun clubShots(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        val season = lg.view?.season ?: return
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.CLUBS
        lg.worldPages.loadClubTable(season.league)
        waitFor("club table") { lg.worldPages.clubTable?.league == season.league }
        check(lg.worldPages.clubTable!!.rows.isNotEmpty()) { "${season.league}: no club in its table" }
        shot(c, out, "lg30_clubs", heightDp = 1300)
        val top = top(lg) ?: error("no player with a club")
        val club = Page.Club(top.club!!)
        nav.open(club)
        lg.worldPages.loadClub(club.id)
        waitFor("club page") { lg.worldPages.club(club) != null }
        check(lg.worldPages.club(club)!!.members.any { it.key == top.key }) { "${club.id}: ${top.key} not among its members" }
        shot(c, out, "lg55_club", heightDp = 1500)
        shot(c, out, "lg55_club_dark", dark = true, heightDp = 1500)
        val league = Page.League(top.league)
        nav.open(league)
        lg.worldPages.loadLeague(league.id)
        waitFor("league page") { lg.worldPages.league(league) != null }
        shot(c, out, "lg56_league", heightDp = 1500)
        nav.go(LeagueTab.TABLES)
        nav.open(Page.Market)
        lg.worldPages.loadMarket()
        waitFor("market") { lg.worldPages.market != null }
        shot(c, out, "lg74_market", heightDp = 1300)
        nav.go(LeagueTab.HOME)
    }

    /** lg31_ranking (the season's own, by strength in a first national season), its confederation sheet, lg31b (a filled one, hand-made). */
    private fun rankingShots(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.RANKING
        lg.worldPages.loadRanking()
        waitFor("ranking") { lg.worldPages.ranking?.rows?.isNotEmpty() == true }
        val ranking = lg.worldPages.ranking!!
        shot(c, out, "lg31_ranking", heightDp = 1300)
        nav.sheet = Sheet.CONFED
        shot(c, out, "lg31c_confed", heightDp = 900)
        nav.sheet = Sheet.NONE
        nav.rankingConfed = Confed.ASIA
        shot(c, out, "lg31d_ranking_asia", heightDp = 1100)
        nav.rankingConfed = null
        nav.go(LeagueTab.HOME)
        // A ranking after a few national seasons: coefficients, five seasons each, climbs and falls, this season's points.
        val rows = ranking.rows.mapIndexed { i, r ->
            val seasons = (0 until 5).map { s -> ((ranking.rows.size - i) * 0.21 + ((i * 7 + s * 3) % 5) * 0.13).let { Math.round(it * 1000) / 1000.0 } }
            val rank = r.rank.copy(coefficient = Math.round(seasons.sum() * 1000) / 1000.0, seasons = seasons, change = listOf(0, 2, -1, 0, 3, -2, 1, 0, -3)[i % 9])
            RankRow(rank, r.label, r.short, r.icon, Math.round((seasons[0] * 0.6) * 1000) / 1000.0)
        }
        val filled = RankingView(ranking.season + 2, rows, byStrength = false, published = ranking.season + 1)
        shotOf(out, "lg31b_ranking_filled", 1500) { RankingBody(filled, null, {}, {}, {}, {}) }
    }

    /**
     * lg71_federation (a hand-made assembly: 3 proposals on the vote, one chosen, 3 decisions and
     * the awards), the real page of this season (lg71b), and the rules page at its national
     * league and 요트협회 parts.
     */
    private fun federationShots(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        val keys = lg.view?.rows?.filter { it.rank != null && !it.isMe && it.origin != yacht.engine.league.Origin.BASE }?.sortedBy { it.rank }?.map { it.key }.orEmpty()
        fun p(number: Int, kind: ProposalKind, from: String, to: String, args: List<String>, season: Int = 3) = Proposal(season, number, kind, from, to, 0.8, args)
        val pending = listOf(
            p(1, ProposalKind.SWEEP_OFF, "1", "0", listOf("8412", "6138", "73")).copy(choice = Proposal.YES),
            p(2, ProposalKind.SWAP_DOWN, "2", "1", listOf("56", "31", "55", "0.12")),
            p(3, ProposalKind.CL_DIRECT_UP, "7", "8", listOf("0.41", "1.83", "1.42")),
        )
        val decided = listOf(
            p(1, ProposalKind.LONG_KO_OFF, "1", "0", listOf("21", "1", "5"), 2).copy(result = Proposal.YES, by = Proposal.USER),
            p(2, ProposalKind.TITLE_PO_OFF, "1", "0", listOf("12", "7", "58"), 2).copy(result = Proposal.NO, by = Proposal.COMMITTEE, yes = 3, no = 6),
            p(1, ProposalKind.SIZE_DOWN, "20", "18", listOf("46", "42", "49"), 1).copy(result = Proposal.VOID, by = Proposal.COMMITTEE, yes = 6, no = 3),
        )
        val awards = listOf("MVP", "ROOKIE", "MIP", "SCORER", "YACHT", "LMVP.KR", "LMVP.JP").zip(keys.ifEmpty { listOf("?") }.let { k -> List(7) { k[it % k.size] } })
            .map { (kind, key) -> kind to lg.name(key) }
        val view = FederationView(true, pending.map(::proposalView), decided.map(::proposalView), awards, 4, 3)
        shotOf(out, "lg71_federation", 1500) { FederationSection(view, { _, _ -> }) }
        nav.go(LeagueTab.HOME)
        nav.open(Page.Federation)
        lg.worldPages.loadFederation()
        waitFor("federation") { lg.worldPages.federation != null }
        shot(c, out, "lg71b_federation_page", heightDp = 1100)
        nav.go(LeagueTab.HOME)
        nav.open(Page.Rules(RulesPart.LEAGUE))
        shot(c, out, "lg72b_rules_league", heightDp = 1500, settleMs = 500)
        nav.go(LeagueTab.HOME)
        nav.open(Page.Rules(RulesPart.CLUBS))
        shot(c, out, "lg72c_rules_clubs", heightDp = 1300, settleMs = 500)
        nav.go(LeagueTab.HOME)
    }

    /** lg75_playoffs: the title playoffs of three leagues (hand-made: a season's come only after its league), one heading a league. */
    private fun playoffShot(c: AppController, out: File) {
        val lg = c.league
        val v = lg.view ?: return
        val infos = v.season?.leagues.orEmpty().filter { it.national && it.sizes.firstOrNull()?.let { n -> n >= 4 } == true }.take(3)
        if (infos.size < 2) return
        fun tie(a: String, b: String, played: Boolean, aWins: Boolean) =
            TieView(a, b, if (played && aWins) 3.0 else if (played) 1.5 else 0.0, if (played && !aWins) 3.0 else if (played) 1.0 else 0.0, if (played) (if (aWins) a else b) else null, played, 3, firstTo = 3)
        val parts = infos.mapIndexed { i, info ->
            val top = v.rows.filter { it.league == info.id && it.division == info.first }.sortedBy { it.rank ?: Int.MAX_VALUE }.map { it.key }
            val k = (top + List(4) { "?" }).take(4)
            val semis = BracketRound("4강", listOf(tie(k[0], k[3], true, i != 1), tie(k[1], k[2], true, true)), stage = 0)
            val w0 = if (i != 1) k[0] else k[3]
            val final = BracketRound("결승", listOf(tie(w0, k[1], i == 0, true)), stage = 1)
            CompetitionView.Groups(info.label, emptyList(), CompetitionView.Bracket(info.label, listOf(semis, final)))
        }
        val view = CompetitionView.Sections(Comp.TITLE_PLAYOFF.label, parts)
        val stages = competitionStages(view)
        val page = CompetitionPage(Page.Competition(Comp.TITLE_PLAYOFF).loadKey, Comp.TITLE_PLAYOFF, stages.map { it.label }, stages.indexOfFirst { it.part == 0 && it.round == 1 }.coerceAtLeast(0), view)
        val clubOf = v.rows.mapNotNull { r -> r.club?.let { id -> v.badges[id]?.let { r.key to it } } }.toMap()
        shotOf(out, "lg75_playoffs", 1100) { CompetitionBody(page, lg::name, lg::name, {}, null, MatchFormat.GAMES, badge = { clubOf[it] }) {} }
    }
}

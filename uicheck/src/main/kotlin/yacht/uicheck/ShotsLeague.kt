package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.LeagueTab
import yacht.app.core.MatchesView
import yacht.app.core.Page
import yacht.app.core.TablesView
import yacht.app.ui.BracketCard
import yacht.app.ui.GroupCard
import yacht.engine.league.BracketSide
import yacht.engine.league.CompetitionView
import yacht.engine.league.DrawEntry
import yacht.engine.league.GroupRow
import yacht.engine.league.GroupTable
import yacht.engine.league.Knockout
import yacht.engine.league.KnockoutLength
import yacht.engine.league.TieScore
import java.io.File

/** The league screen's own shots: its tabs, the control card, the sheets and competition pages. */
internal object ShotsLeague : LeagueShots {
    override fun empty(c: AppController, out: File) {
        shot(c, out, "16a_league_empty")
    }

    override fun running(c: AppController, out: File) {
        shot(c, out, "16_league_ladder", heightDp = 1500)
        // Every screen below is a state of the league's navigation (today's tabs are its sub-views).
        val nav = c.league.nav
        nav.go(LeagueTab.MATCHES)
        nav.matches = MatchesView.WEEK
        shot(c, out, "16e_league_schedule", heightDp = 1500)
        nav.go(LeagueTab.TABLES)
        nav.tables = TablesView.LEAGUE
        shot(c, out, "16b_league_season", heightDp = 1500)
        nav.tables = TablesView.COMPS
        shot(c, out, "16c_league_comps", heightDp = 1500)
        // The cup's own bracket (승자조/패자조 chips, N선승 ties), then the rules dialog.
        val cup = Page.Competition(yacht.engine.league.Comp.CUP)
        nav.open(cup)
        shot(c, out, "16g_league_cup", heightDp = 1500)
        nav.close(cup)
        val rules = Page.Rules()
        nav.open(rules)
        shot(c, out, "16h_league_rules", heightDp = 2600)
        nav.close(rules)
        bracketShots(c, out)
        nav.tables = TablesView.CLUBS
        shot(c, out, "16d_league_clubs", heightDp = 1500)
        nav.go(LeagueTab.MATCHES)
        nav.matches = MatchesView.RESULTS
        shot(c, out, "17_league_feed", heightDp = 1100)
        nav.go(LeagueTab.RECORDS)
        shot(c, out, "18_league_records", heightDp = 1500)
        // 기록 › 국가 랭킹, a nation's sheet, 유망주 (each screen loads its data when composed:
        // here before the shot, which is the first composition).
        nav.open(Page.Nations)
        c.league.loadNations()
        waitFor("nations") { c.league.nations.isNotEmpty() }
        shot(c, out, "18b_league_nations", heightDp = 1500)
        c.league.loadNation("KR")
        waitFor("nation sheet") { c.league.nationSheet != null }
        shot(c, out, "18c_league_nation_sheet", heightDp = 1500)
        c.league.closeNation()
        nav.go(LeagueTab.RECORDS)
        nav.open(Page.Prospects)
        c.league.loadProspects()
        waitFor("prospects") { c.league.prospects.isNotEmpty() }
        shot(c, out, "18d_league_prospects", heightDp = 1300)
    }

    override fun weekDone(c: AppController, out: File) {
        shot(c, out, "16f_league_week_done", heightDp = 1000)
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
    shotOf(out, "16i_bracket_losers", 1100) { BracketCard(bracket, c.league::name, {}, start = BracketSide.LOSERS) }
    shotOf(out, "16j_bracket_grand", 900) { BracketCard(bracket, c.league::name, {}, start = BracketSide.GRAND) }
    shotOf(out, "16j_bracket_grand_dark", 900, dark = true) { BracketCard(bracket, c.league::name, {}, start = BracketSide.GRAND) }
    val rows = keys.take(4).mapIndexed { i, key ->
        val w = 3 - i
        val d = if (i == 1) 1 else 0
        val sw = maxOf(0, 2 - i)
        GroupRow(key, played = 4, wins = w, draws = d, losses = 4 - w - d, points = 3 * w + d + sw, diff = 60 - 40 * i, sweeps = sw)
    }
    shotOf(out, "16k_group_bonus", 500) { GroupCard(GroupTable("A조", rows, bonus = true), c.league::name, {}) }
}

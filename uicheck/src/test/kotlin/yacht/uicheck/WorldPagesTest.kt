package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import yacht.app.core.ClubBadge
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.WorldPages
import yacht.app.ui.mergedRules
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.league.BoardResult
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubTable
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.TieRule
import yacht.engine.league.WinEstimate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The world pages' loaders (`lg.worldPages`, UX 4.3-4.6) on a national league: a league's club
 * table and its clubs' pages agree, a new league shows an empty ranking and the 요트협회 before its
 * first season, and what was read from a league goes when the league is replaced (a reset, a
 * restore), also when the new one has nothing to show yet. And the rules page's edits merged with
 * the rules saved while it was open (the 요트협회's adopted changes).
 */
class WorldPagesTest {
    @Test
    fun aClubTableAndItsClubsPagesAgree() {
        val w = national()
        play(w) { it.plan!!.league.progress >= 0.3 }
        val host = Host(w)
        val pages = WorldPages(host)
        val p = w.plan!!
        val book = assertNotNull(w.clubs)
        assertEquals(ClubBook.AFFILIATION, book.format)
        val lg = p.league.leagues.list.first { it.national }
        pages.loadClubTable(lg.id)
        val t = wait { pages.clubTable?.takeIf { it.league == lg.id } }
        assertEquals(p.number, t.season)
        assertTrue(t.rows.isNotEmpty(), "no club in ${lg.id}")
        assertEquals((1..t.rows.size).toList(), t.rows.map { it.position })
        assertEquals(t.rows.map { it.badge.id }.toSet(), t.all.map { it.badge.id }.toSet(), "the table and 클럽 전체 list the same clubs")
        // The leader's page: its league, its place and points, the counted members' shares adding up.
        val top = t.rows.first()
        pages.loadClub(top.badge.id)
        val page = wait { pages.club(Page.Club(top.badge.id)) }
        assertEquals(lg.id, page.league)
        val st = assertNotNull(page.standing)
        assertEquals(1, st.position)
        assertEquals(top.points, st.points)
        assertTrue(st.counted.size <= ClubTable.COUNTED)
        val shares = page.members.filter { it.key in st.counted }.sumOf { assertNotNull(it.contribution) }
        // Each share and the total are kept to 0.1.
        assertEquals(st.points, shares, 0.05 * (st.counted.size + 1) + 1e-9)
        assertEquals(book.members(top.badge.id).toSet(), page.members.map { it.key }.toSet())
        assertTrue(page.recent.size <= 10 && page.recent.all { it.ref?.startsWith("${p.number}:") == true })
        // Its 클럽 대항전 titles from the honours (a club's id is the key).
        assertEquals(0, page.cupTitles)
        w.honours.add(listOf(yacht.engine.league.Honour(p.number - 1, "CLUBCUP", top.badge.id), yacht.engine.league.Honour(p.number - 1, "CLUBCUP2", top.badge.id)))
        pages.loadClub(top.badge.id)
        assertEquals(1, wait { pages.club(Page.Club(top.badge.id))?.takeIf { it.cupTitles > 0 } }.cupTitles)
    }

    @Test
    fun aNewLeagueShowsItsRankingAndFederationBeforeItsFirstSeason() {
        val fresh = LeagueWorld(Ladder(listOf(Ladder.ME)), LeagueConfig.DEFAULT)
        val pages = WorldPages(Host(fresh))
        pages.loadRanking()
        pages.loadFederation()
        val r = wait { pages.ranking }
        assertEquals(0, r.season)
        assertTrue(r.rows.isEmpty())
        val f = wait { pages.federation }
        assertEquals(1, f.nextClose)
        assertTrue(f.pending.isEmpty() && f.history.isEmpty() && f.awards.isEmpty())
    }

    @Test
    fun anotherLeaguesViewsGoEvenWhenItHasNothingToShow() {
        val w = national()
        val host = Host(w)
        val pages = WorldPages(host)
        val lg = w.plan!!.league.leagues.list.first { it.national }.id
        pages.loadRanking()
        pages.loadFederation()
        pages.loadMarket()
        pages.loadClubTable(lg)
        wait { pages.ranking?.takeIf { it.rows.isNotEmpty() } }
        wait { pages.federation }
        wait { pages.market }
        wait { pages.clubTable }
        // A reset: a new league with no season and no clubs yet. A loader with nothing to read
        // there still drops the old league's views.
        host.w = LeagueWorld(Ladder(listOf(Ladder.ME)), LeagueConfig.DEFAULT)
        pages.loadClubTable(lg)
        wait { if (pages.ranking == null) Unit else null }
        assertNull(pages.federation)
        assertNull(pages.market)
        assertNull(pages.clubTable)
        pages.loadRanking()
        assertEquals(0, wait { pages.ranking }.season)
    }

    @Test
    fun aRulesPageTakesInTheRulesSavedWhileItIsOpen() {
        val base = LeagueConfig.DEFAULT
        // Adopted at a close while the page was open: the sweep bonus off, 3 promoted.
        val saved = base.copy(sweepBonus = false, autoSwap = 3)
        // Nothing edited: the page reads the saved rules.
        assertEquals(saved, mergedRules(base, base, saved))
        // Edited: the page's own choices win, the others come in.
        val edited = base.copy(autoSwap = 1, clubCup = false)
        assertEquals(base.copy(sweepBonus = false, autoSwap = 1, clubCup = false), mergedRules(edited, base, saved))
        // Nothing saved meanwhile: the edits as they are.
        assertEquals(edited, mergedRules(edited, base, base))
    }

    /** A national league of 300 generated players, its first season open. */
    private fun national(): LeagueWorld {
        val cfg = LeagueConfig.DEFAULT.copy(generated = 300)
        val w = LeagueWorld(Ladder(listOf(Ladder.ME)), cfg)
        w.players.ensureGenerated(cfg.generated)
        w.members = w.players.alive.map { it.key }
        w.ladder = Ladder(w.members + Ladder.ME)
        w.present = { true }
        w.openFirstSeason(20261031L)
        assertTrue(w.plan!!.league.leagues.national)
        return w
    }

    /** [get] once it gives a value (the loaders read off the main thread). */
    private fun <T : Any> wait(get: () -> T?): T {
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            get()?.let { return it }
            Thread.sleep(10)
        }
        error("nothing loaded in time")
    }

    /** Plays [w]'s season with made-up results (the stronger side by the key's hash wins more often) until [stop] or its end. */
    private fun play(w: LeagueWorld, stop: (LeagueWorld) -> Boolean) {
        var guard = 0
        while (!w.seasonOver && !stop(w)) {
            val f = w.next(emptySet(), 0, 2) ?: break
            w.recordJob(result(f))
            check(++guard < 400_000)
        }
    }

    private fun games(f: Fixture, a: String, b: String, n: Int, salt: Int): List<GameScore> {
        val rng = java.util.Random(f.seed * 31 + salt)
        val p = 1 / (1 + Math.pow(10.0, ((b.hashCode() and 63) - (a.hashCode() and 63)) / 100.0))
        return List(n) { if (rng.nextDouble() < p) GameScore(230, 190, aYacht = it == 0) else GameScore(190, 230) }
    }

    private fun result(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f, f.a, f.b, 2, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f, f.a, f.b, 2 * f.legs, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).mapIndexed { i, (x, y) -> BoardResult(x, y, games(f, x, y, 2, i + 1), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    /** A host of world [w] alone (no snapshot, names as keys). */
    private class Host(var w: LeagueWorld) : LeagueHost {
        override val scope = CoroutineScope(Dispatchers.Unconfined)
        override val lock = Any()
        override val folder = File(".")
        override fun world(): LeagueWorld? = w
        override fun tables(): Tables? = null
        override fun extra(): LeagueTables? = null
        override fun estimate(): WinEstimate? = null
        override fun ai(key: String): LeagueAi? = null
        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? = null
        override fun adaptive(key: String) = false
        override fun name(key: String) = key
        override fun shown(key: String) = key
        override fun badge(key: String): ClubBadge? = null
        override fun leagueLabel(key: String): String? = null
        override fun favourites() = emptySet<String>()
        override fun view(): LeagueView? = null
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = 0
    }
}

package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import yacht.app.core.ClubBadge
import yacht.app.core.LeagueAnalytics
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.LeaderKind
import yacht.app.core.Page
import yacht.app.core.PlayerTab
import yacht.app.core.StatScope
import yacht.app.core.statCells
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.league.AiStats
import yacht.engine.league.BracketRound
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.TieView
import yacht.engine.league.WinEstimate
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The player page's loader (`LeagueAnalytics.loadPlayer`) and its numbers. */
class PlayerPageTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    /** Just what the loader reads: a world and a snapshot; names are keys. */
    private inner class Host(@Volatile var w: LeagueWorld?, @Volatile var v: LeagueView? = null) : LeagueHost {
        override val scope: CoroutineScope get() = this@PlayerPageTest.scope
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
        override fun favourites(): Set<String> = emptySet()
        override fun view(): LeagueView? = v
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = 0
    }

    private fun LeagueAnalytics.load(key: String, s: StatScope = StatScope.SEASON) {
        val before = player(Page.Player(key, scope = s))
        loadPlayer(key, s)
        waitFor { player(Page.Player(key, scope = s)).let { it != null && it !== before } }
    }

    @Test
    fun aPageOpenedAgainIsThereAtOnceAndTheLastFourAreKept() {
        val host = Host(LeagueWorld(Ladder(listOf("a", "b", "c", "d", "e"))))
        val an = LeagueAnalytics(host)
        an.load("a")
        an.load("b")
        // A → B → back to A on another tab: the load key has no tab, so A's page is drawn at once.
        assertEquals("a", an.player(Page.Player("a", PlayerTab.STATS))?.profile?.key)
        assertEquals("p:a:SEASON", an.player(Page.Player("a"))?.key)
        // Each scope is a page of its own.
        assertNull(an.player(Page.Player("a", scope = StatScope.CAREER)))
        an.load("a", StatScope.CAREER)
        assertEquals(StatScope.CAREER, an.player(Page.Player("a", scope = StatScope.CAREER))?.scope)
        // Four kept: a (career) and c, d, e push out a and b of this season...
        for (k in listOf("c", "d", "e")) an.load(k)
        assertEquals(listOf("p:a:CAREER", "p:c:SEASON", "p:d:SEASON", "p:e:SEASON"), an.players.keys.toList())
        // ...and a page loaded again moves last.
        an.load("c")
        assertEquals("p:c:SEASON", an.players.keys.last())
        // A league replaced (a reset, a seed): its pages are not shown, and the next load starts afresh.
        host.w = LeagueWorld(Ladder(listOf("a")))
        assertNull(an.player(Page.Player("c")))
        an.load("a")
        assertEquals(listOf("p:a:SEASON"), an.players.keys.toList())
    }

    @Test
    fun knockoutTiesAreListedNewestRoundFirstFromThePlayersSide() {
        fun tie(a: String?, b: String?, sa: Double, sb: Double, winner: String?, played: Boolean, bye: Boolean = false) =
            TieView(a, b, sa, sb, winner, played, legs = 1, bye = bye)
        val cup = CompetitionView.Bracket(
            "요트컵",
            listOf(
                BracketRound("16강", listOf(tie("a", "b", 2.0, 0.0, "a", true), tie("a", null, 0.0, 0.0, "a", false, bye = true), tie("c", "d", 1.0, 0.0, "c", true))),
                BracketRound("8강", listOf(tie("d", "a", 1.5, 0.5, "d", true))),
                BracketRound("4강", listOf(tie("a", "e", 0.0, 0.0, null, false))),
            ),
        )
        // A team competition's sides are teams, not players: left out.
        val family = CompetitionView.Bracket("계열 대항전", listOf(BracketRound("결승", listOf(tie("a", "b", 3.0, 2.0, "a", true)))))
        val view = LeagueView(emptyList(), emptyList(), 0, 0, emptyList(), comps = listOf(Comp.CUP to cup, Comp.FAMILY to family))
        val an = LeagueAnalytics(Host(LeagueWorld(Ladder(listOf("a", "b", "c", "d", "e"))), view))
        an.load("a")
        val m = an.player(Page.Player("a"))!!.matches
        assertEquals(listOf("요트컵 8강", "요트컵 16강"), m.map { it.where })
        assertEquals(listOf(-1, 1), m.map { it.result })
        assertEquals(listOf("0.5:1.5", "2:0"), m.map { it.score })
        assertEquals(listOf("d", "b"), m.map { it.opponent })
        assertTrue(m.all { it.ref == null && it.comp == Comp.CUP })
    }

    private fun stats(games: Int, wins: Int, avg: Int, yachts: Int = 0, sd: Int = 0) = AiStats().apply {
        this.games = games
        gameWins = wins
        scoreSum = games.toLong() * avg
        // A spread of [sd] around the average.
        scoreSquares = games.toLong() * (avg.toLong() * avg + sd.toLong() * sd)
        this.yachts = yachts
    }

    @Test
    fun cellsArePlacedAmongTheEligibleMembers() {
        val a = stats(40, 30, 250, yachts = 12, sd = 30)
        val b = stats(40, 20, 260, yachts = 9, sd = 45)
        // Too few games for the averages: not ranked by them and never counted above.
        val c = stats(10, 10, 300, yachts = 20, sd = 10)
        val members = listOf("a" to a, "b" to b, "c" to c)
        val cells = statCells(a, members, "a", StatScope.SEASON).associateBy { it.label }
        assertEquals(12, cells.size)
        assertEquals(1, cells.getValue("승률").rank)
        assertNull(cells.getValue("승률").board)
        assertEquals(2, cells.getValue("평균 점수").rank)
        assertEquals(LeaderKind.SCORER, cells.getValue("평균 점수").board)
        // Counts need no minimum: c's 20 yachts are more.
        assertEquals(2, cells.getValue("요트").rank)
        assertEquals("12개 · 30%", cells.getValue("요트").value)
        // 꾸준함 needs 40 games in a season and is lower-is-better.
        assertEquals(1, cells.getValue("꾸준함").rank)
        assertEquals(2, statCells(b, members, "b", StatScope.SEASON).first { it.label == "꾸준함" }.rank)
        assertNull(statCells(c, members, "c", StatScope.SEASON).first { it.label == "평균 점수" }.rank)
        assertNull(cells.getValue("역전패").rank)
        // Over careers the averages need 200 games.
        assertNull(statCells(a, members, "a", StatScope.CAREER).first { it.label == "평균 점수" }.rank)
    }

    @Test
    fun theMedianIsReadInsideItsBar() {
        assertTrue(LeagueAnalytics.median(IntArray(AiStats.BINS)).isNaN())
        assertEquals(212.5, LeagueAnalytics.median(IntArray(AiStats.BINS).also { it[8] = 10 }))
        assertEquals(125.0, LeagueAnalytics.median(IntArray(AiStats.BINS).also { it[4] = 5; it[12] = 5 }))
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }
}

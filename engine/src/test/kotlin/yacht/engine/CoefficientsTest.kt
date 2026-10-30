package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.ChampionsBracket
import yacht.engine.league.ChampionsLeague
import yacht.engine.league.Coefficients
import yacht.engine.league.Comp
import yacht.engine.league.Competition
import yacht.engine.league.Confed
import yacht.engine.league.ContinentalChampions
import yacht.engine.league.DrawEntry
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.International
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Knockout
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.Leagues
import yacht.engine.league.MatchFormat
import yacht.engine.league.MemoryCoefficients
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.TieRule
import yacht.engine.league.TieScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** League coefficients (ENG 5.1): the points table, per-entry values, the 5-season ranking, inheritance, tie-breaks, change. */
class CoefficientsTest {
    /** Group A a1..a4 and B b1..b4, a1 the strongest, then b1, a2, b2, ... */
    private val order = listOf("a1", "b1", "a2", "b2", "a3", "b3", "a4", "b4")

    private fun bracket(double: Boolean): ChampionsBracket {
        val a = listOf("a1", "a2", "a3", "a4").mapIndexed { i, k -> DrawEntry(k, 2 * i + 1, 2000.0 - order.indexOf(k)) }
        val b = listOf("b1", "b2", "b3", "b4").mapIndexed { i, k -> DrawEntry(k, 2 * i + 2, 2000.0 - order.indexOf(k)) }
        val stages = Knockout.stageSizes(2, double).size
        return ChampionsBracket(
            Comp.CONT_CHAMPIONS, 1, 5L, listOf(a, b), 0, false, false, double, false,
            ChampionsBracket.Timing({ 0.0 }, { 0.0 }, List(stages) { 0.5 }, List(stages) { 0.5 }),
        )
    }

    /** Plays [b] out: the stronger wins every match, except a1-a2's first meeting, a draw. */
    private fun playOut(b: ChampionsBracket) {
        var drawn = false
        while (!b.finished) {
            val f = b.next(1.0, emptySet()) ?: error("stuck")
            val level = !drawn && f.kind == JobKind.MATCH && setOf(f.a, f.b) == setOf("a1", "a2")
            if (level) drawn = true
            val aWins = order.indexOf(f.a) < order.indexOf(f.b)
            val n = if (f.kind == JobKind.TIE) 2 * f.legs else 2
            val g = List(n) { if (level) (if (it == 0) GameScore(220, 180) else GameScore(180, 220)) else if (aWins) GameScore(220, 180) else GameScore(180, 220) }
            val res = TieRule.match(g, MatchFormat.GAMES).let { if (f.kind == JobKind.TIE && it == 0) 1 else it }
            b.record(f.id, f.a, f.b, TieScore.of(JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), res, 0, MatchFormat.GAMES)))
        }
    }

    @Test
    fun thePointsTable() {
        // Single elimination: a1 5 group wins and a draw (11), the knockout (1), two series (4), the title (3);
        // a2 4 wins and the draw (9), the knockout, the last four (1).
        val single = bracket(double = false).also(::playOut)
        assertEquals(
            mapOf("a1" to 11 + 1 + 4 + 3, "a2" to 9 + 1 + 0 + 1, "a3" to 4, "a4" to 0, "b1" to 12 + 1 + 2 + 2, "b2" to 8 + 1 + 0 + 1, "b3" to 4, "b4" to 0),
            single.points(),
        )
        // Double elimination: series on the losers' side count; a2 wins its losers' tie, then loses the losers' final.
        val double = bracket(double = true).also(::playOut)
        assertEquals(
            mapOf("a1" to 11 + 1 + 6 + 3, "a2" to 9 + 1 + 2 + 1, "a3" to 4, "a4" to 0, "b1" to 12 + 1 + 4 + 2, "b2" to 8 + 1 + 0 + 1, "b3" to 4, "b4" to 0),
            double.points(),
        )
        assertEquals(listOf(0, 0, 0, 1, 2, 3), listOf(null, 5, 3, 2, 1, 0).map { Coefficients.placePoints(it) })
        // A champions league place adds 2 for everyone.
        val keys = (1..16).map { "ai%02d".format(it) }
        val start = yacht.engine.league.SeasonStart(1, 3L, LeagueConfig(), keys, { 1800.0 - keys.indexOf(it) }, listOf(keys), emptyList(), emptyList(), { null }, { "" })
        val cl = assertNotNull(ChampionsLeague.create(start))
        assertEquals(keys.associateWith { Coefficients.CL_ENTRY }, cl.coefficientPoints())
        assertEquals(cl.entries.map { it.key }, cl.entrantKeys())
    }

    /** A competition that only reports coefficient points. */
    private class Points(override val comp: Comp, private val points: Map<String, Int>) : Competition, International {
        override fun next(p: Double, busy: Set<String>): Fixture? = null
        override fun release(f: Fixture) {}
        override fun record(r: JobResult): List<LeagueEvent> = emptyList()
        override val finished: Boolean get() = true
        override val fixturesLeft: Int get() = 0
        override val fixturesTotal: Int get() = 0
        override fun honours(): List<Honour> = emptyList()
        override fun encode(out: StringBuilder) {}
        override fun coefficientPoints(): Map<String, Int> = points
        override fun entrantKeys(): List<String> = points.keys.toList()
    }

    @Test
    fun aSeasonCreditsTheLeagueEachEntrantPlaysInPerEntry() {
        val divisions = listOf(listOf("k1", "k2", "j9"), listOf("k3", "k4"), listOf("j1", "j2"), listOf("x1", "x2"))
        val leagues = Leagues.national(listOf("KR", "JP", "XWO"), listOf(2, 1, 1), Confed.ASIA)
        val plan = SeasonPlan(Season.start(2, 9L, divisions, true, leagues), LeagueConfig(national = true))
        // j9 is Japanese but plays in Korea: its points are Korea's. k1 plays both (two entries).
        plan.comps += Points(Comp.CHAMPIONS, mapOf("k1" to 10, "j1" to 4, "j9" to 6))
        plan.comps += Points(Comp.CONT_CHAMPIONS, mapOf("k1" to 7, "k2" to 3, "j2" to 0, "gone" to 50))
        val s = Coefficients.season(plan)
        assertEquals(listOf("KR", "JP", "XWO"), s.keys.toList())
        assertEquals(listOf(26, 4), s.getValue("KR").toList())
        assertEquals(listOf(4, 2), s.getValue("JP").toList())
        // A league without entrants has a line of nothing (it existed: no inheritance).
        assertEquals(listOf(0, 0), s.getValue("XWO").toList())
        assertEquals(6.5, Coefficients.value(s.getValue("KR")))
        assertEquals(0.0, Coefficients.value(s.getValue("XWO")))
        // A legacy season has none.
        assertTrue(Coefficients.season(SeasonPlan(Season.start(2, 9L, divisions), LeagueConfig())).isEmpty())
    }

    @Test
    fun aRealSeasonsCompetitionsEarnPoints() {
        val ten = listOf(10, 10)
        for (confed in listOf(Confed.AMERICAS, Confed.ASIA)) {
            val w = NationalStart(listOf("KR" to ten, "JP" to ten, "CN" to ten, "VN" to ten, "XWO" to ten), worldConfed = confed)
            val start = w.start()
            val plan = SeasonPlan(Season.start(2, start.seed, w.divisions, true, w.leagues), start.config)
            plan.comps += assertNotNull(ChampionsLeague.create(start))
            plan.comps += assertNotNull(ContinentalChampions.create(start))
            val print = CompetitionPrint(w::rating)
            for (c in plan.comps) {
                var p = 0.0
                while (!c.finished) {
                    val f = c.next(p, emptySet())
                    if (f == null) {
                        p += 0.05
                        check(p < 2.0)
                        continue
                    }
                    c.record(print.play(f))
                }
            }
            val s = Coefficients.season(plan)
            // 16 CL places and the Asian section's 16: 32 entries (the 국제 연합 리그 of the Americas has no section).
            assertEquals(32, s.values.sumOf { it[1] })
            val all = plan.comps.flatMap { c -> (c as International).coefficientPoints().values }
            assertEquals(all.sum(), s.values.sumOf { it[0] })
            // The 국제 연합 리그 earns what its players earn, like any league; in Asia it has places in the section.
            val xwo = plan.comps.flatMap { c -> (c as International).coefficientPoints().filterKeys { it.startsWith("xwo") }.values }
            assertEquals(listOf(xwo.sum(), xwo.size), s.getValue("XWO").toList(), "$confed")
            val section = (plan.comps[1] as ContinentalChampions).sections.single()
            assertEquals(if (confed == Confed.ASIA) 3 else 0, section.groups.flatten().count { it.key.startsWith("xwo") }, "$confed")
            if (confed == Confed.ASIA) assertTrue(s.getValue("XWO")[0] > 0)
            // Group matches alone hand out 2 a win, 1 each a draw; with places and series every entrant of the CL has at least 2.
            assertTrue((plan.comps[0] as International).coefficientPoints().values.all { it >= 2 })
        }
    }

    @Test
    fun theRankingAddsFiveSeasonsAndInheritsWhereALeagueHadNoLine() {
        val store = MemoryCoefficients()
        // KR 5 a season; JP 3, but 9 in season 2 (too old for a ranking after season 7).
        for (s in 1..7) store.putCoefficients(s, mapOf("KR" to intArrayOf(10, 2), "JP" to intArrayOf(if (s == 2) 18 else 6, 2)))
        // VN played in the 아시아 연합 리그 until season 5 (XAS 4 a season), then on its own: 2.
        for (s in 3..5) store.putCoefficients(s, store.lines(s) + ("XAS" to intArrayOf(8, 2)))
        for (s in 6..7) store.putCoefficients(s, store.lines(s) + ("VN" to intArrayOf(4, 2)) + ("TH" to intArrayOf(12, 2)) + ("XWO" to intArrayOf(14, 2)))
        val leagues = Leagues.national(listOf("KR", "JP", "VN", "XAS", "XWO"), listOf(1, 1, 1, 1, 1), Confed.EUROPE)
        val r = Coefficients.ranking(store, leagues, 7, { 0.0 }, LeagueConfig(national = true, clDirect = 2))
        assertEquals(7, r.season)
        assertEquals(listOf(5.0, 5.0, 5.0, 5.0, 5.0), r.of("KR")!!.seasons)
        assertEquals(25.0, r.of("KR")!!.coefficient)
        assertEquals(15.0, r.of("JP")!!.coefficient)
        // VN: 2, 2 on its own, then XAS's 4, 4, 4 (seasons 5, 4, 3).
        assertEquals(listOf(2.0, 2.0, 4.0, 4.0, 4.0), r.of("VN")!!.seasons)
        // XAS in seasons 6 and 7 (merged into nations): the mean of Asia's nation leagues (KR 5, JP 3, VN 2, TH 6).
        assertEquals(listOf(4.0, 4.0, 4.0, 4.0, 4.0), r.of("XAS")!!.seasons)
        // XWO earns its own; no line, no inheritance: 0.
        assertEquals(listOf(7.0, 7.0, 0.0, 0.0, 0.0), r.of("XWO")!!.seasons)
        assertEquals(listOf("KR", "XAS", "VN", "JP", "XWO"), r.rows.map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 4), r.rows.map { it.rank })
        assertEquals(listOf(true, true, false, false, false), r.rows.map { it.clDirect })
        // Asia has 4 leagues (a section of 16: 4 each); the 국제 연합 리그 is European, alone (no section).
        assertEquals(listOf(4, 4, 4, 4, 0), r.rows.map { it.cclSlots })
        assertEquals(listOf(0, 1, 2, 3, 0), r.rows.map { it.confedRank })
        assertEquals(Confed.EUROPE, r.of("XWO")!!.confed)
        assertEquals(0, Coefficients.ranking(store, leagues, 7, { 0.0 }, LeagueConfig(national = true, continentalCl = false)).rows.sumOf { it.cclSlots })
        // The store keeps 10 seasons.
        for (s in 8..12) store.putCoefficients(s, mapOf("KR" to intArrayOf(1, 1)))
        assertEquals((3..12).toList(), store.stored)
        assertNull(store.coefficient("KR", 2))
    }

    @Test
    fun tiesGoToTheNewestSeasonThenStrengthThenId() {
        val store = MemoryCoefficients()
        // Every league 6 over two seasons: KR 2 then 4 (newest 4), JP 4 then 2, CN and VN 3 and 3.
        store.putCoefficients(1, mapOf("KR" to intArrayOf(2, 1), "JP" to intArrayOf(4, 1), "CN" to intArrayOf(3, 1), "VN" to intArrayOf(3, 1)))
        store.putCoefficients(2, mapOf("KR" to intArrayOf(4, 1), "JP" to intArrayOf(2, 1), "CN" to intArrayOf(3, 1), "VN" to intArrayOf(3, 1)))
        val leagues = Leagues.national(listOf("KR", "JP", "CN", "VN"), listOf(1, 1, 1, 1))
        val strength = mapOf("KR" to 1500.0, "JP" to 1500.0, "CN" to 1400.0, "VN" to 1450.0)
        val r = Coefficients.ranking(store, leagues, 2, { strength.getValue(it) }, LeagueConfig(national = true))
        // KR's newest is 4; CN and VN are level on 3: VN is stronger; JP last.
        assertEquals(listOf("KR", "VN", "CN", "JP"), r.rows.map { it.id })
        // Level everywhere: by id.
        val same = Coefficients.ranking(MemoryCoefficients(), leagues, 2, { 1500.0 }, LeagueConfig(national = true))
        assertEquals(listOf("CN", "JP", "KR", "VN"), same.rows.map { it.id })
        // Before any value: by strength (the first national season).
        val first = Coefficients.ranking(MemoryCoefficients(), leagues, 0, { strength.getValue(it) }, LeagueConfig(national = true))
        assertEquals(listOf("JP", "KR", "VN", "CN"), first.rows.map { it.id })
        assertTrue(first.rows.all { it.coefficient == 0.0 && it.seasons.isEmpty() })
    }

    @Test
    fun theChangeComparesWithTheRankingASeasonEarlier() {
        val store = MemoryCoefficients()
        store.putCoefficients(1, mapOf("KR" to intArrayOf(8, 1), "JP" to intArrayOf(4, 1), "CN" to intArrayOf(0, 1)))
        store.putCoefficients(2, mapOf("KR" to intArrayOf(0, 1), "JP" to intArrayOf(1, 1), "CN" to intArrayOf(20, 1)))
        val leagues = Leagues.national(listOf("KR", "JP", "CN"), listOf(1, 1, 1))
        val r = Coefficients.ranking(store, leagues, 2, { 0.0 }, LeagueConfig(national = true))
        assertEquals(listOf("CN", "KR", "JP"), r.rows.map { it.id })
        // After season 1: KR, JP, CN.
        assertEquals(listOf(2, -1, -1), r.rows.map { it.change })
        // The first close: against the strength order the season was played under.
        val first = Coefficients.ranking(store, leagues, 1, { if (it == "CN") 2000.0 else 1000.0 }, LeagueConfig(national = true))
        assertEquals(listOf("KR", "JP", "CN"), first.rows.map { it.id })
        assertEquals(listOf(2, 0, -2), first.rows.map { it.change })
        // Leagues of a season without national leagues: no ranking rows.
        assertTrue(Coefficients.ranking(store, Leagues.legacy(yacht.engine.league.Tiers.linear(2)), 2, { 0.0 }, LeagueConfig()).rows.isEmpty())
    }

    /** Season [s]'s lines of [this] store as a map (to add to). */
    private fun MemoryCoefficients.lines(s: Int): Map<String, IntArray> =
        listOf("KR", "JP", "VN", "TH", "XAS", "XWO").mapNotNull { id -> sums(id, s)?.let { id to it } }.toMap()
}

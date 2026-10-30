package yacht.engine

import yacht.engine.league.AiOdds
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Leagues
import yacht.engine.league.Odds
import yacht.engine.league.OddsTable
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The odds of a national season (ENG 1.11): one league at a time with its own divisions,
 * fixtures, boundaries and title playoff; the international places of its 1부 (대륙 챔피언스, its
 * champion first, and the champions league for a league whose champion goes straight in).
 */
class OddsNationalTest {
    /** KR in three tiers of 10, JP two of 10, CN one of 8, GB one of 3; KR the strongest, GB the weakest. */
    private val spec = listOf("KR" to listOf(10, 10, 10), "JP" to listOf(10, 10), "CN" to listOf(8), "GB" to listOf(3))
    private val leagues = Leagues.national(spec.map { it.first }, spec.map { it.second.size })
    private val divisions = spec.flatMap { (id, sizes) -> sizes.mapIndexed { t, n -> List(n) { i -> "${id.lowercase()}$t-%02d".format(i) } } }
    private val ratings = spec.withIndex().flatMap { (l, s) ->
        val (id, sizes) = s
        sizes.flatMapIndexed { t, n -> List(n) { i -> "${id.lowercase()}$t-%02d".format(i) to 1900.0 - 60 * l - 100 * t - 8 * i } }
    }.toMap()

    private fun rating(k: String) = ratings.getValue(k)

    private val config = LeagueConfig(national = true, doubleRoundRobin = true)

    /** A national plan with [share] of its league fixtures played (the better rated wins 70%). */
    private fun plan(share: Double, cfg: LeagueConfig = config): SeasonPlan {
        val p = SeasonPlan(Season.start(1, 17L, divisions, true, leagues), cfg)
        val s = p.league
        val rng = Random(5)
        val stop = (s.fixturesTotal * share).toInt()
        var n = 0
        while (n < stop) {
            val f = s.next(emptySet()) ?: break
            val aWins = (rng.nextDouble() < 0.7) == (rating(f.a) > rating(f.b))
            s.record(f.id, if (aWins) listOf(GameScore(220, 180), GameScore(210, 200)) else listOf(GameScore(180, 220), GameScore(200, 210)))
            n++
        }
        return p
    }

    private fun sum(rows: List<AiOdds>, f: (AiOdds) -> Double) = rows.sumOf(f)

    private fun odds(p: SeasonPlan, id: String, runs: Int = 600): OddsTable = Odds.compute(p, ::rating, runs = runs, seed = 3L, leagueId = id)

    @Test
    fun eachLeagueHasItsOwnOdds() {
        val p = plan(0.5)
        val kr = odds(p, "KR")
        val lg = leagues.byId("KR")!!
        assertEquals("KR", kr.league)
        assertEquals(lg.divisions.flatMap { divisions[it] }.toSet(), kr.rows.map { it.key }.toSet())
        for (r in kr.rows) {
            assertEquals("KR", r.league)
            assertTrue(r.division in lg.divisions)
            assertEquals(r.division - lg.first, r.tier)
        }
        for (d in lg.divisions) assertEquals(1.0, sum(kr.division(d)) { it.leader }, 1e-9)
        // KR's own title playoff (its 1부 of 10) and its boundaries: 2 up and down, the playoff tie's share.
        val top = kr.division(lg.top)
        assertEquals(1.0, sum(top) { it.title }, 1e-9)
        assertEquals(4.0, sum(top) { it.playoff }, 1e-9)
        val up = sum(kr.division(lg.top + 1)) { it.promotion }
        val down = sum(top) { it.relegation }
        assertTrue(up in 2.0..3.0 && down in 2.0..3.0, "$up $down")
        assertEquals(0.0, sum(kr.division(lg.top + 2)) { it.relegation }, 1e-9)
        assertEquals(0.0, sum(top) { it.promotion }, 1e-9)
        // Only KR's fixtures are run.
        val left = lg.divisions.sumOf { d -> p.league.unplayed().count { it.division == d } }
        assertEquals(left, Odds.input(p, ::rating, "KR").fixtures)
        // The runs a refresh affords count the league's own fixtures (a legacy season's: all of them).
        assertEquals(left, Odds.left(Odds.input(p, ::rating, "KR")))
        val legacy = SeasonPlan(Season.start(1, 17L, divisions.take(3)), LeagueConfig(divisions = 3))
        assertEquals(legacy.league.fixturesLeft, Odds.left(Odds.input(legacy, ::rating)))
        // The first league by default; GB (3 players) has no title playoff and no boundary.
        assertEquals("KR", Odds.input(p, ::rating).let { Odds.run(it, 10, 1L) }.league)
        val gb = odds(p, "GB")
        assertTrue(gb.rows.all { it.title == it.leader && it.playoff == 0.0 && it.promotion == 0.0 && it.relegation == 0.0 })
        assertEquals(leagues.byId("GB")!!.top, gb.rows.first().division)
        assertFailsWith<IllegalArgumentException> { Odds.input(p, ::rating, "FR") }
        // The same input gives the same odds.
        assertEquals(kr.rows, odds(p, "KR").rows)
    }

    private fun ranking(vararg rows: Triple<String, Int, Boolean>): LeagueRanking =
        LeagueRanking(0, rows.mapIndexed { r, (id, slots, direct) -> LeagueRank(id, r, 0, 0.0, emptyList(), 0.0, slots, direct, 0, leagues.confedOf(id)) })

    @Test
    fun internationalPlacesComeFromTheRanking() {
        val p = plan(0.5)
        p.ranking = ranking(Triple("KR", 3, true), Triple("JP", 2, false), Triple("CN", 0, true), Triple("GB", 0, false))
        val places = mapOf("KR" to 3.0, "JP" to 2.0, "CN" to 1.0, "GB" to 0.0)
        for ((id, n) in places) {
            val t = odds(p, id)
            assertEquals(n, sum(t.rows) { it.champions }, 1e-9, id)
            // Only the 1부 goes; the champion always does.
            val top = leagues.byId(id)!!.top
            assertTrue(t.rows.filter { it.division != top }.all { it.champions == 0.0 })
            if (n > 0) assertTrue(t.division(top).all { it.champions >= it.title - 1e-9 }, id)
        }
        // KR's three places: the title playoff's winner from 4th takes the third's.
        val kr = odds(p, "KR").division(leagues.byId("KR")!!.top)
        assertTrue(kr.count { it.champions > 0.0 } >= 4, kr.toString())
        // 대륙 챔피언스 off: a champion straight into the champions league only.
        val off = p.config.copy(continentalCl = false)
        val q = SeasonPlan(p.league, off).also { it.ranking = p.ranking }
        assertEquals(1.0, sum(odds(q, "KR").rows) { it.champions }, 1e-9)
        assertEquals(0.0, sum(odds(q, "JP").rows) { it.champions }, 1e-9)
    }

    @Test
    fun withoutARankingThePlacesFollowTheStrengthOrder() {
        // Asia's three leagues share 8 places (KR 3, JP 3, CN 2), GB has none in Europe; every
        // league is among the seven whose champion goes straight into the champions league.
        val p = plan(0.3)
        assertEquals(LeagueRanking.EMPTY, p.ranking)
        val expected = mapOf("KR" to 3.0, "JP" to 3.0, "CN" to 2.0, "GB" to 1.0)
        for ((id, n) in expected) assertEquals(n, sum(odds(p, id).rows) { it.champions }, 1e-9, id)
    }

    @Test
    fun aFinishedLeagueIsCertain() {
        val p = plan(1.0)
        assertTrue(p.league.finished)
        for (id in leagues.list.map { it.id }) {
            // One exact run: the tables are final, the title playoff still to play.
            val t = odds(p, id, 50)
            assertEquals(1, t.runs)
            assertTrue(t.rows.all { it.leader == 0.0 || it.leader == 1.0 }, id)
            val top = leagues.byId(id)!!.top
            assertEquals(1.0, t.division(top).first().leader)
            assertEquals(1.0, sum(t.division(top)) { it.title }, 1e-9)
            assertEquals(p.league.table(top).first().key, t.division(top).first().key)
        }
    }
}

package yacht.engine

import yacht.engine.league.AiOdds
import yacht.engine.league.BoardResult
import yacht.engine.league.ContinentalChampions
import yacht.engine.league.GameScore
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.LeagueTitlePlayoffs
import yacht.engine.league.Leagues
import yacht.engine.league.Odds
import yacht.engine.league.OddsTable
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.TieRule
import yacht.engine.league.TitlePlayoff
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    /** [p] with this season's 대륙 챔피언스 (Asia's KR, JP and CN: one section; GB alone in Europe has none). */
    private fun withContinental(p: SeasonPlan): SeasonPlan {
        val start = SeasonStart(
            p.number, p.seed, p.config, ratings.keys.sortedByDescending(::rating), ::rating, divisions, emptyList(), emptyList(), { null }, { "서울" },
            leagues = leagues, leagueRanking = p.ranking,
        )
        val ccl = assertNotNull(ContinentalChampions.create(start))
        assertEquals(1, ccl.sections.size)
        return SeasonPlan(p.league, p.config, mutableListOf(ccl)).also { it.ranking = p.ranking }
    }

    /** Each league's international places in all (rounded: shares add up to whole places). */
    private fun places(p: SeasonPlan): Map<String, Double> = leagues.list.associate { it.id to Math.round(sum(odds(p, it.id).rows) { r -> r.champions } * 1e6) / 1e6 }

    @Test
    fun continentalPlacesComeFromTheRanking() {
        // The champions league off: the 대륙 챔피언스 places alone.
        val p = plan(0.5, config.copy(champions = false))
        p.ranking = ranking(Triple("KR", 3, true), Triple("JP", 2, false), Triple("CN", 0, true), Triple("GB", 0, false))
        assertEquals(mapOf("KR" to 3.0, "JP" to 2.0, "CN" to 0.0, "GB" to 0.0), places(withContinental(p)))
        for (id in listOf("KR", "JP")) {
            val t = odds(p, id)
            // Only the 1부 goes; the champion always does.
            val top = leagues.byId(id)!!.top
            assertTrue(t.rows.filter { it.division != top }.all { it.champions == 0.0 })
            assertTrue(t.division(top).all { it.champions >= it.title - 1e-9 }, id)
        }
        // KR's three places: the title playoff's winner from 4th takes the third's.
        val kr = odds(p, "KR").division(leagues.byId("KR")!!.top)
        assertTrue(kr.count { it.champions > 0.0 } >= 4, kr.toString())
        // Without a ranking: Asia's three leagues share 8 places by strength (KR 3, JP 3, CN 2), GB none.
        val q = plan(0.3, config.copy(champions = false))
        assertEquals(LeagueRanking.EMPTY, q.ranking)
        assertEquals(mapOf("KR" to 3.0, "JP" to 3.0, "CN" to 2.0, "GB" to 0.0), places(q))
    }

    @Test
    fun afterAContinentalSeasonTheChampionsLeagueTakesChampionsAndRunnersUp() {
        val p = plan(0.5)
        p.ranking = ranking(Triple("KR", 3, true), Triple("JP", 2, false), Triple("CN", 0, true), Triple("GB", 0, false))
        // One section's champion and runner-up and the four champions leave 9 places after the
        // holder: every league's runner-up too (ChampionsLeague.worldEntrants), so a direct
        // champion's league sends its top two. GB's champion is not direct: one place.
        val q = withContinental(p)
        assertEquals(mapOf("KR" to 3.0, "JP" to 2.0, "CN" to 2.0, "GB" to 1.0), places(q))
        val cn = odds(q, "CN").division(leagues.byId("CN")!!.top)
        assertTrue(cn.all { it.champions >= it.title - 1e-9 })
        // 대륙 챔피언스 off next season: the champions league's places alone (KR's three are two).
        val off = SeasonPlan(q.league, q.config.copy(continentalCl = false), q.comps).also { it.ranking = p.ranking }
        assertEquals(mapOf("KR" to 2.0, "JP" to 1.0, "CN" to 2.0, "GB" to 1.0), places(off))
    }

    @Test
    fun withoutAContinentalSeasonEachLeagueTakesItsTurnOnTheWorldStage() {
        // No 대륙 챔피언스 this season: after the holder each league in rank order adds its next
        // player until 16, so 15 places go 4, 4, 4, 3 (GB's 1부 has 3); more than KR's 3 대륙 places.
        val p = plan(0.5)
        p.ranking = ranking(Triple("KR", 3, true), Triple("JP", 2, false), Triple("CN", 0, true), Triple("GB", 0, false))
        assertEquals(mapOf("KR" to 4.0, "JP" to 4.0, "CN" to 4.0, "GB" to 3.0), places(p))
        // By strength without a ranking; with GB first in a ranking, KR is the one of three.
        assertEquals(mapOf("KR" to 4.0, "JP" to 4.0, "CN" to 4.0, "GB" to 3.0), places(plan(0.3)))
        val q = plan(0.3).also { it.ranking = ranking(Triple("GB", 0, true), Triple("CN", 2, true), Triple("JP", 3, true), Triple("KR", 3, true)) }
        assertEquals(mapOf("KR" to 3.0, "JP" to 4.0, "CN" to 4.0, "GB" to 3.0), places(q))
        // No champions league: nobody.
        assertEquals(mapOf("KR" to 0.0, "JP" to 0.0, "CN" to 0.0, "GB" to 0.0), places(plan(0.3, config.copy(champions = false, continentalCl = false))))
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

    @Test
    fun aLeaguesTitleIsCertainOnceItsOwnFinalIsPlayed() {
        val p = plan(1.0)
        val titles = assertIs<LeagueTitlePlayoffs>(TitlePlayoff.create(p))
        p.post += titles
        // The better rated wins every tie; KR's final comes first, the other leagues' are still to play.
        while (titles.honours().none { it.kind == "L0.KR" }) {
            val f = assertNotNull(titles.next(1.0, emptySet()))
            val g = List(2 * f.legs) { if (rating(f.a) > rating(f.b)) GameScore(220, 180) else GameScore(180, 220) }
            titles.record(JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0))
        }
        assertFalse(titles.finished)
        val champion = titles.honours().single { it.kind == "L0.KR" }.key
        val kr = odds(p, "KR", 50)
        assertEquals(1.0, kr[champion]!!.title)
        assertEquals(1.0, sum(kr.division(leagues.byId("KR")!!.top)) { it.title }, 1e-9)
        // JP's final is open: its title is still a chance for more than one.
        assertTrue(titles.honours().none { it.kind == "L0.JP" })
        assertTrue(odds(p, "JP", 50).rows.count { it.title > 0.0 } > 1)
    }
}

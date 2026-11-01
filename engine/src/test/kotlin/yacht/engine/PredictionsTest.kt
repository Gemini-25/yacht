package yacht.engine

import yacht.engine.league.AiOdds
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueScorecard
import yacht.engine.league.Leagues
import yacht.engine.league.Media
import yacht.engine.league.Odds
import yacht.engine.league.Offseason
import yacht.engine.league.PredictionScorecard
import yacht.engine.league.ScoreSwing
import yacht.engine.league.Season
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonPlan
import yacht.engine.league.WorldBook
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Preseason predictions (시즌 전망, design ENG 5.2): [Odds.preseason] is week one's odds whatever has
 * been played since, deterministic for its seed, with the top-4 share; the favourites, the dark
 * horse and the relegation picks; the scorecard of the close on hand-made results, recomputing the
 * open's predictions.
 */
class PredictionsTest {
    /** KR in three tiers of 10, JP two of 10, CN one of 8 (as OddsNationalTest). */
    private val spec = listOf("KR" to listOf(10, 10, 10), "JP" to listOf(10, 10), "CN" to listOf(8))
    private val leagues = Leagues.national(spec.map { it.first }, spec.map { it.second.size })
    private val divisions = spec.flatMap { (id, sizes) -> sizes.mapIndexed { t, n -> List(n) { i -> "${id.lowercase()}$t-%02d".format(i) } } }
    private val ratings = spec.withIndex().flatMap { (l, s) ->
        val (id, sizes) = s
        sizes.flatMapIndexed { t, n -> List(n) { i -> "${id.lowercase()}$t-%02d".format(i) to 1900.0 - 60 * l - 100 * t - 8 * i } }
    }.toMap()

    private fun drawn(k: String) = ratings.getValue(k)

    /** A national plan with [share] of its league fixtures played (the better rated wins 70%). */
    private fun plan(share: Double, cfg: LeagueConfig = LeagueConfig(national = true, doubleRoundRobin = true)): SeasonPlan {
        val p = SeasonPlan(Season.start(1, 17L, divisions, true, leagues), cfg)
        val s = p.league
        val rng = Random(5)
        val stop = (s.fixturesTotal * share).toInt()
        var n = 0
        while (n < stop) {
            val f = s.next(emptySet()) ?: break
            val aWins = (rng.nextDouble() < 0.7) == (drawn(f.a) > drawn(f.b))
            s.record(f.id, if (aWins) listOf(GameScore(220, 180), GameScore(210, 200)) else listOf(GameScore(180, 220), GameScore(200, 210)))
            n++
        }
        return p
    }

    @Test
    fun theOddsBeforeTheFirstMatchAreWeekOnesOdds() {
        val p = plan(0.0)
        for (id in listOf("KR", "JP", "CN")) {
            val pre = Odds.preseason(p, ::drawn, id, runs = 400, seed = 9L)
            val week1 = Odds.compute(p, ::drawn, runs = 400, seed = 9L, leagueId = id)
            assertEquals(week1.rows, pre.rows, id)
            assertEquals(0, pre.played)
            assertEquals(week1.drawRate, pre.drawRate)
            // The default seed is the league's own.
            assertEquals(Odds.preseason(p, ::drawn, id, runs = 400, seed = Odds.preseasonSeed(p, id)).rows, Odds.preseason(p, ::drawn, id).rows)
            assertEquals(Odds.PRESEASON_RUNS, Odds.preseason(p, ::drawn, id).runs)
        }
        // A legacy season (the cup unplayed) too.
        val legacy = SeasonPlan(Season.start(1, 3L, divisions.take(3)), LeagueConfig())
        assertEquals(Odds.compute(legacy, ::drawn, runs = 300, seed = 4L).rows, Odds.preseason(legacy, ::drawn, runs = 300, seed = 4L).rows)
    }

    @Test
    fun whatHasBeenPlayedSinceChangesNothingAndTheSeedDecides() {
        val before = plan(0.0)
        for (share in listOf(0.3, 0.7, 1.0)) {
            val later = plan(share)
            for (id in listOf("KR", "JP", "CN")) {
                assertEquals(Odds.preseason(before, ::drawn, id).rows, Odds.preseason(later, ::drawn, id).rows, "$id at $share")
                assertNotEquals(Odds.compute(later, ::drawn, runs = 400, seed = Odds.preseasonSeed(later, id), leagueId = id).rows, Odds.preseason(later, ::drawn, id).rows)
            }
        }
        val p = plan(0.5)
        assertEquals(Odds.preseason(p, ::drawn, "KR").rows, Odds.preseason(p, ::drawn, "KR").rows)
        assertNotEquals(Odds.preseason(p, ::drawn, "KR", seed = 1L).rows, Odds.preseason(p, ::drawn, "KR", seed = 2L).rows)
        assertNotEquals(Odds.preseasonSeed(p, "KR"), Odds.preseasonSeed(p, "JP"))
    }

    @Test
    fun theTopFourShareIsTheFirstDivisionsOnly() {
        for (titlePlayoff in listOf(true, false)) {
            val p = plan(0.4, LeagueConfig(national = true, doubleRoundRobin = true, titlePlayoff = titlePlayoff))
            val kr = Odds.compute(p, ::drawn, runs = 500, seed = 2L, leagueId = "KR")
            val top = leagues.byId("KR")!!.top
            assertEquals(4.0, kr.division(top).sumOf { it.top4 }, 1e-9)
            assertTrue(kr.rows.filter { it.division != top }.all { it.top4 == 0.0 })
            // With the title playoff its places are the top 4.
            if (titlePlayoff) for (r in kr.division(top)) assertEquals(r.playoff, r.top4, 1e-12)
        }
    }

    @Test
    fun favouritesDarkHorseAndRelegationPicks() {
        for (titlePlayoff in listOf(true, false)) {
            val p = plan(0.6, LeagueConfig(national = true, doubleRoundRobin = true, titlePlayoff = titlePlayoff))
            val pr = assertNotNull(Media.prediction(p, ::drawn, "KR"))
            val rows = Odds.preseason(p, ::drawn, "KR").division(leagues.byId("KR")!!.top)
            assertEquals("KR", pr.league)
            assertEquals(rows.sortedWith(compareBy<AiOdds> { it.expectedPosition }.thenBy { it.key }), pr.order)
            assertEquals(rows.sortedWith(compareByDescending<AiOdds> { it.title }.thenBy { it.expectedPosition }.thenBy { it.key }).take(3), pr.favourites)
            if (!titlePlayoff) for (f in pr.favourites) assertEquals(f.leader, f.title)
            assertEquals(rows.filter { it.relegation > 0 }.sortedByDescending { it.relegation }.take(3).map { it.key }, pr.relegation.map { it.key })
            // The dark horse: of those predicted 6th or lower, the best top-4 chance; a promoted player a little ahead.
            val low = pr.order.drop(5)
            assertEquals(low.maxBy { it.top4 }.key, pr.dark?.key)
            val second = low.sortedByDescending { it.top4 }[1]
            val promoted = assertNotNull(Media.prediction(p, ::drawn, "KR") { it == second.key })
            val expect = if (second.top4 + Media.PROMOTED_BONUS > low.maxOf { it.top4 }) second.key else low.maxBy { it.top4 }.key
            assertEquals(expect, promoted.dark?.key)
            assertEquals(1, pr.predicted(pr.order[0].key))
            assertNull(pr.predicted("kr1-00"))
        }
        // A single-tier league has no relegation picks.
        assertTrue(assertNotNull(Media.prediction(plan(0.0), ::drawn, "CN")).relegation.isEmpty())
        assertNull(Media.prediction(plan(0.0), ::drawn, "XX"))
    }

    @Test
    fun theOpensNewsNamesTheBestLeaguesFavourites() {
        val p = plan(0.0)
        val e = Media.predictionEvents(p, ::drawn)
        assertEquals(2, e.size)
        val kr = Media.prediction(p, ::drawn, "KR")!!
        assertEquals("predictions", e[0].kind)
        assertEquals(listOf(kr.favourites[0].key, kr.dark?.key ?: "-") + kr.relegation.map { it.key }, e[0].keys)
        assertEquals(listOf("KR", Math.round(kr.favourites[0].title * 100).toString()), e[0].args)
        assertEquals(SeasonCalendar.PRESEASON, e[0].week)
        val others = listOf("JP", "CN").map { Media.prediction(p, ::drawn, it)!!.favourites[0] }
        assertEquals(others.map { it.key }, e[1].keys)
        assertEquals("*", e[1].args[0])
        assertEquals(listOf("JP", "CN"), e[1].args.drop(1).map { it.substringBefore(':') })
        // A legacy season has none.
        assertTrue(Media.predictionEvents(SeasonPlan(Season.start(1, 3L, divisions.take(3)), LeagueConfig()), ::drawn).isEmpty())
    }

    /** A season played to its end by the drawn ratings, but for fixtures whose id is a multiple of 7 (the lower-rated wins). */
    private fun played(): SeasonPlan {
        val p = SeasonPlan(Season.start(1, 17L, divisions, true, leagues), LeagueConfig(national = true, doubleRoundRobin = true, titlePlayoff = false, promotionPlayoff = false))
        val s = p.league
        while (true) {
            val f = s.next(emptySet()) ?: break
            val aWins = (drawn(f.a) > drawn(f.b)) != (f.id % 7 == 0)
            s.record(f.id, if (aWins) listOf(GameScore(220, 180), GameScore(210, 200)) else listOf(GameScore(180, 220), GameScore(200, 210)))
        }
        return p
    }

    @Test
    fun theScorecardOnHandMadeResultsRecomputesTheOpensPredictions() {
        val p = played()
        val s = p.league
        // The close's predictions are the open's.
        for (id in listOf("KR", "JP", "CN")) {
            val open = Media.prediction(plan(0.0, p.config), ::drawn, id)!!
            val close = Media.prediction(p, ::drawn, id)!!
            assertEquals(open.order, close.order)
            assertEquals(open.favourites, close.favourites)
            assertEquals(open.dark, close.dark)
            assertEquals(open.relegation, close.relegation)
        }
        // JP's title goes elsewhere by the honours.
        val honours = listOf(Honour(1, HonourKind.of("L0", "JP"), "jp0-05"))
        val sc = assertNotNull(Media.scorecard(p, ::drawn, honours))
        val moves = Offseason.moves(p)
        var hits = 0
        var top3 = 0
        var rel = 0
        var picks = 0
        val rhos = ArrayList<Double>()
        val swings = ArrayList<Triple<String, Int, Int>>()
        for (id in Media.leagues(p, ::drawn)) {
            val pr = Media.prediction(p, ::drawn, id)!!
            val final = s.table(leagues.byId(id)!!.top).map { it.key }
            val champion = honours.firstOrNull { it.kind == HonourKind.of("L0", id) }?.key ?: final[0]
            val hit = pr.favourites[0].key == champion
            val inTop = pr.favourites.any { it.key == champion }
            val down = moves.filter { it.league == id && it.from == 0 && it.to > 0 }.map { it.key }.toSet()
            val r = pr.relegation.count { it.key in down }
            val n = final.size.toDouble()
            val d2 = final.withIndex().sumOf { (i, k) -> val d = (pr.predicted(k)!! - (i + 1)).toDouble(); d * d }
            val rho = 1 - 6 * d2 / (n * (n * n - 1))
            assertEquals(LeagueScorecard(id, hit, inTop, r, Math.round(rho * 100).toInt()), sc.perLeague.first { it.league == id })
            if (hit) hits++
            if (inTop) top3++
            rel += r
            picks += pr.relegation.size
            rhos += rho
            for ((i, k) in final.withIndex()) swings += Triple(k, pr.predicted(k)!!, i + 1)
        }
        assertEquals(Media.leagues(p, ::drawn), sc.perLeague.map { it.league })
        assertTrue(sc.perLeague.first { it.league == "JP" }.let { !it.hit || Media.prediction(p, ::drawn, "JP")!!.favourites[0].key == "jp0-05" })
        assertEquals(PredictionScorecard(1, hits, 3, top3, rel, picks, Math.round(rhos.average() * 100).toInt(), sc.over, sc.flop, sc.perLeague), sc)
        val best = swings.maxOf { it.second - it.third }
        val worst = swings.minOf { it.second - it.third }
        assertEquals(best, sc.over!!.predicted - sc.over!!.final)
        assertEquals(worst, sc.flop!!.predicted - sc.flop!!.final)
        assertTrue(best > 0 && worst < 0)
        // The close's news.
        val e = Media.scorecardEvent(sc)
        assertEquals("prediction_scorecard", e.kind)
        assertEquals(listOf(sc.over!!.key, sc.flop!!.key), e.keys)
        assertEquals(listOf(sc.hits, sc.leagues, sc.over!!.predicted, sc.over!!.final, sc.flop!!.predicted, sc.flop!!.final, sc.top3, sc.relHits, sc.relPicks, sc.rho).map { it.toString() }, e.args)
        assertEquals(SeasonCalendar.CLOSING, e.week)
        // A legacy season has no scorecard.
        assertNull(Media.scorecard(SeasonPlan(Season.start(1, 3L, divisions.take(3)), LeagueConfig()), ::drawn))
    }

    @Test
    fun theWorldBookKeepsThirtyScorecardsAndTheNewestsLeagues() {
        val w = WorldBook()
        for (season in 1..33) {
            val per = listOf(LeagueScorecard("KR", season % 2 == 0, true, 1, 40), LeagueScorecard("JP", false, false, 0, -12))
            w.media.putScorecard(PredictionScorecard(season, season % 2, 2, 1, 1, 6, 14, ScoreSwing("kr0-0$season", 9, 2), null, per))
        }
        assertEquals((4..33).toList(), w.media.allScorecards.map { it.season })
        assertTrue(w.media.allScorecards.dropLast(1).all { it.perLeague.isEmpty() })
        assertEquals(2, w.media.scorecard(33)!!.perLeague.size)
        val back = WorldBook.decode(w.encode())
        assertEquals(w.media.allScorecards, back.media.allScorecards)
        // A season replayed keeps its own scorecard in place.
        w.media.putScorecard(w.media.scorecard(33)!!.copy(hits = 0))
        assertEquals(0, w.media.scorecard(33)!!.hits)
        assertEquals(30, w.media.allScorecards.size)
    }
}

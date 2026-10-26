package yacht.engine

import yacht.engine.league.AiOdds
import yacht.engine.league.BoardResult
import yacht.engine.league.BoxProfile
import yacht.engine.league.ChampionsLeague
import yacht.engine.league.Comp
import yacht.engine.league.Competitions
import yacht.engine.league.Cup
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomBase
import yacht.engine.league.CustomMistake
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LabBook
import yacht.engine.league.LabSession
import yacht.engine.league.LabStats
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Odds
import yacht.engine.league.OddsTable
import yacht.engine.league.Offseason
import yacht.engine.league.Preview
import yacht.engine.league.PromotionPlayoff
import yacht.engine.league.Scouting
import yacht.engine.league.Season
import yacht.engine.league.SeasonStart
import yacht.engine.league.Temper
import yacht.engine.league.TieRule
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisTest {
    // Synthetic league: ai01 is the strongest, 10 rating points apart.
    private fun key(i: Int) = "ai%02d".format(i)
    private fun index(k: String) = k.removePrefix("ai").toInt()
    private fun rating(k: String) = 1700.0 - 10 * index(k)
    private fun keys(n: Int) = (1..n).map(::key)

    /** Games of a match the stronger side wins with the Bradley-Terry chance (a level match now and then). */
    private fun games(a: String, b: String, rng: Random, rating: (String) -> Double = ::rating): List<GameScore> {
        val x = rng.nextDouble()
        val p = Preview.expected(rating(a), rating(b))
        return when {
            x < 0.03 -> listOf(GameScore(200, 200), GameScore(190, 190))
            x < 0.03 + 0.97 * p -> listOf(GameScore(220, 180, aYacht = true), GameScore(200, 190))
            else -> listOf(GameScore(180, 220), GameScore(190, 200, bBonus = true))
        }
    }

    /** Plays [count] fixtures of [season] (every one when null). */
    private fun play(season: Season, count: Int? = null, rng: Random = Random(3)) {
        var played = 0
        while (count == null || played < count) {
            val f = season.next(emptySet()) ?: break
            season.record(f.id, games(f.a, f.b, rng))
            played++
        }
    }

    private fun job(f: Fixture, games: List<GameScore>): JobResult {
        val w = TieRule.match(games).let { if (f.kind == JobKind.TIE && it == 0) 1 else it }
        return JobResult(f, listOf(BoardResult(f.a, f.b, games, emptyList())), w, 0)
    }

    private fun assertProbabilities(t: OddsTable) {
        for (r in t.rows) {
            for (p in listOf(r.leader, r.title, r.playoff, r.champions, r.promotion, r.relegation, r.promotionPlayoff, r.relegationPlayoff)) {
                assertTrue(p in -1e-12..1 + 1e-12, "$r")
            }
        }
    }

    private fun sum(rows: List<AiOdds>, f: (AiOdds) -> Double) = rows.sumOf(f)

    // ---- odds ----------------------------------------------------------------------------------

    @Test
    fun aDominantAiAlmostSurelyTakesTheTitle() {
        val season = Season.start(1, 5L, Season.split(keys(12), 2))
        fun r(k: String) = if (k == "ai01") 2600.0 else rating(k)
        val plain = Odds.compute(season, LeagueConfig(divisions = 2, titlePlayoff = false), ::r, 0.02, 2000, 1L)
        val top = assertNotNull(plain["ai01"])
        assertTrue(top.title > 0.97 && top.leader > 0.97, "$top")
        assertEquals(top.leader, top.title)
        assertEquals(0.0, top.playoff)
        assertProbabilities(plain)
        // With the title playoff: in the top 4 for sure, and through the playoff almost surely.
        val playoff = Odds.compute(season, LeagueConfig(divisions = 2), ::r, 0.02, 2000, 1L)
        val p = assertNotNull(playoff["ai01"])
        assertTrue(p.playoff > 0.999 && p.title > 0.97, "$p")
        // Deterministic from its seed.
        assertEquals(playoff.rows, Odds.compute(season, LeagueConfig(divisions = 2), ::r, 0.02, 2000, 1L).rows)
    }

    @Test
    fun probabilitiesAddUpToThePlaces() {
        val season = Season.start(1, 9L, Season.split(keys(60), 3))
        play(season, season.fixturesTotal * 2 / 5)
        val draw = Odds.drawRate(season)
        assertTrue(draw in 0.005..0.08, "draw rate $draw")
        val t = Odds.compute(season, LeagueConfig(divisions = 3), ::rating, draw, 2000, 4L)
        assertProbabilities(t)
        assertEquals(60, t.rows.size)
        val d = (0..2).map { t.division(it) }
        for ((i, rows) in d.withIndex()) {
            assertEquals(20, rows.size)
            assertEquals(1.0, sum(rows) { it.leader }, 1e-9, "leader $i")
            assertEquals(1.0, sum(rows) { it.title }, 1e-9, "title $i")
            assertEquals(210.0, sum(rows) { it.expectedPosition }, 1e-6, "positions $i")
            // Rows come in the table's order.
            assertEquals(season.table(i).map { it.key }, rows.map { it.key })
            assertEquals((1..20).toList(), rows.map { it.position })
        }
        assertEquals(4.0, sum(d[0]) { it.playoff }, 1e-9)
        assertEquals(0.0, sum(d[1]) { it.playoff }, 1e-9)
        assertEquals(12.0, sum(d[0]) { it.champions }, 1e-9)
        assertEquals(2.0, sum(d[1]) { it.champions }, 1e-9)
        assertEquals(0.0, sum(d[2]) { it.champions }, 1e-9)
        for (b in 0..1) {
            // Two automatic places each way, and the playoff: who goes up is who comes down.
            val upSum = sum(d[b + 1]) { it.promotion }
            assertEquals(upSum, sum(d[b]) { it.relegation }, 1e-9)
            assertTrue(upSum > 2.0 && upSum < 3.0, "boundary $b: $upSum")
            assertEquals(1.0, sum(d[b + 1]) { it.promotionPlayoff }, 1e-9)
            assertEquals(1.0, sum(d[b]) { it.relegationPlayoff }, 1e-9)
        }
        assertEquals(0.0, sum(d[0]) { it.promotion }, 1e-9)
        assertEquals(0.0, sum(d[2]) { it.relegation }, 1e-9)
        // The best of division 1 is the favourite; the bottom of division 3 cannot go down.
        assertTrue(d[0].maxBy { it.title }.key in season.table(0).take(5).map { it.key })
        // Without the promotion playoff exactly the automatic places move.
        val auto = Odds.compute(season, LeagueConfig(divisions = 3, promotionPlayoff = false), ::rating, draw, 500, 4L)
        assertEquals(2.0, sum(auto.division(1)) { it.promotion }, 1e-9)
        assertEquals(2.0, sum(auto.division(0)) { it.relegation }, 1e-9)
        assertEquals(0.0, sum(auto.division(1)) { it.promotionPlayoff }, 1e-9)
        assertTrue(!t.stale(season))
        // The input is a copy: the season moving on afterwards does not change its runs.
        val input = Odds.input(season, LeagueConfig(divisions = 3), ::rating, draw)
        assertEquals(season.fixturesLeft, input.fixtures)
        play(season, season.fixturesTotal / 10 + 1)
        assertTrue(t.stale(season))
        assertEquals(t.rows, Odds.run(input, 2000, 4L).rows)
    }

    @Test
    fun aFinishedSeasonIsCertain() {
        val season = Season.start(1, 11L, Season.split(keys(30), 2))
        play(season)
        assertTrue(season.finished)
        val t = Odds.compute(season, LeagueConfig(divisions = 2, titlePlayoff = false, promotionPlayoff = false), ::rating, 0.02, 2000, 1L)
        assertEquals(1, t.runs)
        for (r in t.rows) {
            for (p in listOf(r.leader, r.title, r.playoff, r.champions, r.promotion, r.relegation)) assertTrue(p == 0.0 || p == 1.0, "$r")
            assertEquals(r.position.toDouble(), r.expectedPosition)
        }
        val top = season.table(0).map { it.key }
        val second = season.table(1).map { it.key }
        assertEquals(1.0, t[top[0]]!!.title)
        assertEquals(top.takeLast(2).toSet(), t.division(0).filter { it.relegation == 1.0 }.map { it.key }.toSet())
        assertEquals(second.take(2).toSet(), t.division(1).filter { it.promotion == 1.0 }.map { it.key }.toSet())
        assertEquals((top.take(12) + second.take(2)).toSet(), t.rows.filter { it.champions == 1.0 }.map { it.key }.toSet())
        // With the playoffs on: places are certain, only the ties' winners are estimates.
        val po = Odds.compute(season, LeagueConfig(divisions = 2), ::rating, 0.02, 2000, 1L)
        assertEquals(top.take(4).toSet(), po.division(0).filter { it.playoff == 1.0 }.map { it.key }.toSet())
        assertEquals(1.0, sum(po.division(0)) { it.title }, 1e-9)
        assertEquals(1.0, po[second[2]]!!.promotionPlayoff)
        assertEquals(po[second[2]]!!.promotion, po[top[top.size - 3]]!!.relegation, 1e-12)
    }

    @Test
    fun finishedPlayoffsDecideTheOdds() {
        val keys = keys(24)
        val ctx = Offseason.Context(keys, ::rating, { null }, { "서울" }, LeagueConfig(divisions = 2, cup = false, champions = false))
        val plan = Offseason.first(21L, ctx)
        val rng = Random(8)
        fun drain() {
            while (true) {
                val f = plan.next(emptySet(), 0, 1) ?: break
                plan.record(job(f, games(f.a, f.b, rng)))
            }
        }
        drain()
        plan.startPost(Competitions.postFactories())
        drain()
        assertTrue(plan.finished)
        val t = Odds.compute(plan, ::rating, 500)
        val champion = plan.competition(Comp.TITLE_PLAYOFF)!!.honours().first { it.kind == "L0" }.key
        for (r in t.division(0)) assertEquals(if (r.key == champion) 1.0 else 0.0, r.title, r.key)
        val promo = plan.competition(Comp.PROMOTION_PLAYOFF) as PromotionPlayoff
        val spot = promo.spots.single()
        val up = promo.honours().any { it.kind == "UP0" }
        assertEquals(if (up) 1.0 else 0.0, t[spot.lower]!!.promotion)
        assertEquals(if (up) 1.0 else 0.0, t[spot.upper]!!.relegation)
        // The moves the off-season makes are the certain ones.
        val moves = Offseason.moves(plan)
        assertEquals(moves.filter { it.to < it.from }.map { it.key }.toSet(), t.rows.filter { it.promotion == 1.0 }.map { it.key }.toSet())
        assertEquals(moves.filter { it.to > it.from }.map { it.key }.toSet(), t.rows.filter { it.relegation == 1.0 }.map { it.key }.toSet())
    }

    @Test
    fun championsPlacesFollowTheEntrantsRule() {
        // No cup: the cup's two places pass down the 1st division (its 13th and 14th get in).
        val season = Season.start(1, 15L, Season.split(keys(30), 2))
        play(season, season.fixturesTotal / 2)
        val noCup = LeagueConfig(divisions = 2, cup = false)
        val mid = Odds.compute(season, noCup, ::rating, 0.02, 1000, 1L)
        assertProbabilities(mid)
        assertEquals(16.0, sum(mid.rows) { it.champions }, 1e-9)
        assertEquals(14.0, sum(mid.division(0)) { it.champions }, 1e-9)
        assertEquals(2.0, sum(mid.division(1)) { it.champions }, 1e-9)
        play(season)
        val top = season.table(0).map { it.key }
        val second = season.table(1).map { it.key }
        val end = Odds.compute(season, noCup, ::rating, 0.02, 100, 1L)
        assertEquals((top.take(14) + second.take(2)).toSet(), end.rows.filter { it.champions == 1.0 }.map { it.key }.toSet())
        // One division without a cup: its top 16.
        val one = Season.start(1, 16L, listOf(keys(20)))
        play(one)
        val single = Odds.compute(one, LeagueConfig(divisions = 1, cup = false), ::rating, 0.02, 100, 1L)
        assertEquals(one.table(0).take(16).map { it.key }.toSet(), single.rows.filter { it.champions == 1.0 }.map { it.key }.toSet())
        // With the cup on: league places only until its finalists are known, then the rule.
        val keys = keys(40)
        val ctx = Offseason.Context(
            keys, ::rating, { null }, { "서울" }, LeagueConfig(divisions = 3, superCup = false, allStar = false, familyCup = false, clubs = 0),
            listOf(Cup::create),
        )
        val plan = Offseason.first(23L, ctx)
        val cup = assertNotNull(plan.competition(Comp.CUP))
        val rng = Random(4)
        val before = Odds.compute(plan, ::rating, 300)
        assertEquals(14.0, sum(before.rows) { it.champions }, 1e-9)
        while (!cup.finished) {
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(job(f, games(f.a, f.b, rng)))
        }
        assertTrue(cup.finished && !plan.league.finished)
        val after = Odds.compute(plan, ::rating, 300)
        assertProbabilities(after)
        assertEquals(16.0, sum(after.rows) { it.champions }, 1e-9)
        for (h in cup.honours()) assertEquals(1.0, after[h.key]!!.champions, h.toString())
        // The rule as the champions league applies it, on the final tables of a finished season.
        while (true) {
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(job(f, games(f.a, f.b, rng)))
        }
        assertTrue(plan.league.finished)
        val final = Odds.compute(plan, ::rating, 300)
        val lastTables = plan.league.divisions.indices.map { d -> plan.league.table(d).map { it.key } }
        val start = SeasonStart(2, 1L, plan.config, keys.sortedByDescending(::rating), ::rating, lastTables, lastTables, cup.honours(), { null }, { "서울" })
        assertEquals(ChampionsLeague.entrants(start).toSet(), final.rows.filter { it.champions == 1.0 }.map { it.key }.toSet())
        assertEquals(16, final.rows.count { it.champions == 1.0 })
    }

    @Test
    fun anInfiniteRatingDoesNotPoisonTheOdds() {
        val season = Season.start(1, 17L, Season.split(keys(12), 2))
        play(season, 10)
        fun r(k: String) = if (k == "ai03") Double.POSITIVE_INFINITY else rating(k)
        val t = Odds.compute(season, LeagueConfig(divisions = 2), ::r, 0.02, 500, 1L)
        assertProbabilities(t)
        assertEquals(1.0, sum(t.division(0)) { it.title }, 1e-9)
    }

    @Test
    fun oddsRunFastOnAFullLeague() {
        val season = Season.start(1, 13L, Season.split(keys(204), 4))
        assertEquals(5100, season.fixturesTotal)
        val cfg = LeagueConfig()
        Odds.compute(season, cfg, ::rating, 0.02, 300, 1L)
        val t0 = System.nanoTime()
        val t = Odds.compute(season, cfg, ::rating, 0.02, 2000, 2L)
        val ms = (System.nanoTime() - t0) / 1e6
        println("odds: 5100 fixtures x 2000 runs in ${"%.0f".format(ms)} ms")
        assertEquals(2000, t.runs)
        assertTrue(ms < 2000, "odds took $ms ms")
        assertProbabilities(t)
    }

    // ---- preview and scouting ------------------------------------------------------------------

    /** A profile [shift] points a box above the league in Full House and below in Aces. */
    private fun profile(games: Int, house: Double = 0.0, aces: Double = 0.0, yacht: Double = 0.3, bonus: Double = 0.5, comeback: Double = 0.03, blown: Double = 0.03): BoxProfile {
        val avg = LEAGUE_AVG.toMutableList()
        avg[Category.FULL_HOUSE.ordinal] += house
        avg[Category.ACES.ordinal] += aces
        avg[Category.YACHT.ordinal] = 50 * yacht
        val zeros = LEAGUE_ZERO.toMutableList()
        zeros[Category.YACHT.ordinal] = 1 - yacht
        return BoxProfile(avg, zeros, games, yacht, bonus, comeback, blown)
    }

    private val placeholder = Regex("null|NaN|Infinity|\\$|\\{|\\}|%s|%d")

    private fun assertText(lines: List<String>) {
        assertTrue(lines.isNotEmpty())
        for (l in lines) {
            assertTrue(l.isNotBlank(), "blank line")
            assertTrue(!placeholder.containsMatchIn(l), l)
            assertTrue(l.trimEnd().endsWith('.') || l.trimEnd().endsWith(')'), l)
        }
    }

    @Test
    fun previewSaysWhatTheMatchIsAbout() {
        val keys = keys(20)
        val ladder = Ladder(keys + "ev")
        val rng = Random(5)
        repeat(40) { ladder.record("ai03", "ai05", games("ai03", "ai05", rng)) }
        repeat(400) {
            val a = keys[rng.nextInt(20)]
            val b = keys[rng.nextInt(20)]
            if (a != b) ladder.record(a, b, games(a, b, rng))
        }
        ladder.fit()
        val season = Season.start(1, 3L, listOf(keys))
        play(season, 120)
        val odds = Odds.compute(season, LeagueConfig(divisions = 1), ::rating, 0.02, 1000, 1L)
        val league = profile(500)
        val names = mapOf("ai03" to "김민준", "ai05" to "승률 AI (앱 기본)")
        val p = Preview.of("ai03", "ai05", ladder, season, odds, profile(300, house = 2.5, aces = -0.8), profile(300, house = -1.2), league, { names[it] ?: it })
        p.lines.forEach(::println)
        p.stakes.forEach(::println)
        assertText(p.lines)
        assertNotNull(p.winA)
        assertTrue(p.winA!! in 0.0..1.0)
        assertEquals(ladder.headToHead("ai03", "ai05").second, p.h2hGames)
        assertTrue(p.h2hGames >= 40)
        assertTrue(p.formA.length in 1..Preview.FORM && p.formA.all { it in "WDL" })
        assertEquals(Category.FULL_HOUSE, p.weaponA?.category)
        assertEquals(Category.ACES, p.weaknessA?.category)
        assertEquals(Category.FULL_HOUSE, p.weaknessB?.category)
        assertTrue(p.lines.any { it.contains("주무기는 풀하우스로") }, p.lines.toString())
        assertTrue(p.stakes.any { it.contains("1부 순위") })
        // A cup tie says what it decides.
        val cup = Preview.of("ai03", "ai05", ladder, null, null, null, null, null, fixture = Fixture(Comp.CUP, 1, "ai03", "ai05", 1L, JobKind.TIE, label = "16강"))
        assertTrue(cup.stakes.any { it.contains("요트컵 16강이에요") }, cup.stakes.toString())
        assertText(cup.stakes)
        // Odds of another season (before a rollover's first estimate) are left out.
        val next = Season.start(2, 4L, listOf(keys))
        val old = Preview.of("ai03", "ai05", ladder, next, odds, null, null, null)
        assertTrue(old.stakes.none { it.contains("확률") }, old.stakes.toString())
    }

    @Test
    fun aCupTieAcrossDivisionsNamesEachSidesTitle() {
        val season = Season.start(1, 6L, Season.split(keys(20), 2))
        play(season, 20)
        val odds = Odds.compute(season, LeagueConfig(divisions = 2), ::rating, 0.02, 2000, 1L)
        val tie = Fixture(Comp.CUP, 3, "ai01", "ai11", 1L, JobKind.TIE, label = "8강")
        val p = Preview.of("ai01", "ai11", null, season, odds, null, null, null, fixture = tie)
        p.stakes.forEach(::println)
        assertText(p.stakes)
        assertTrue(odds["ai11"]!!.title in 0.03..0.97, "${odds["ai11"]}")
        assertTrue(p.stakes.any { it.startsWith("ai11의 2부 우승 확률은") }, p.stakes.toString())
        assertTrue(p.stakes.none { it.startsWith("ai11의 우승 확률") }, p.stakes.toString())
    }

    @Test
    fun previewWithNothingKnownIsStillReadable() {
        val p = Preview.of("x", "y", null, null, null, null, null, null)
        p.lines.forEach(::println)
        assertText(p.lines)
        assertNull(p.winA)
        assertEquals("", p.formA)
        assertNull(p.weaponA)
        // A fresh ladder, a season not started, a profile with too few games.
        val q = Preview.of("ai01", "ai02", Ladder(keys(4)), Season.start(1, 1L, listOf(keys(4))), null, profile(5), null, profile(500))
        q.lines.forEach(::println)
        assertText(q.lines)
        assertTrue(q.lines.any { it.contains("처음") })
        assertNull(q.weaponA)
    }

    @Test
    fun scoutingReportsReadNaturally() {
        val league = profile(5000)
        val spec = CustomAiSpec(
            "g7", "박서준", CustomBase.RISK, theta = 0.06, mistake = CustomMistake.SLIP, amount = 0.08, temper = Temper.TILT,
        )
        val full = Scouting.report("박서준", Family.MINE, spec, profile(400, house = 1.8, aces = -0.9, yacht = 0.45, bonus = 0.3, comeback = 0.08, blown = 0.02), league)
        full.lines.forEach(::println)
        println("→ ${full.advice}")
        assertText(full.lines + full.advice)
        assertTrue(full.lines.size >= 5)
        assertTrue(full.lines.any { it.contains("모험형") && it.contains("θ=+0.06") })
        assertTrue(full.lines.any { it.startsWith("가장 강한 칸은") && it.contains("풀하우스") }, full.lines.toString())
        assertTrue(full.lines.any { it.startsWith("가장 약한 칸은 ‘1’") }, full.lines.toString())
        assertTrue(full.lines.any { it.contains("‘0점 뒤 흔들림’ 기질") })
        assertTrue(full.lines.any { it == "수를 고를 때 8%의 확률로 최선 대신 차선을 둬요." }, full.lines.toString())
        // An EV gene AI with tastes and a belief.
        val taste = CustomAiSpec("g8", "이하은", yacht = 25.0, zero = 8.0, saveChoice = true, future = 0.8, belief = "no-bonus", mistake = CustomMistake.FEEL, amount = 1.5)
        val t = Scouting.report("이하은", null, taste, profile(100), league)
        t.lines.forEach(::println)
        assertText(t.lines + t.advice)
        assertTrue(t.lines.any { it.contains("요트를 실제보다 25점") })
        assertTrue(t.lines.any { it.startsWith("규칙을 잘못 알고 있어서(‘보너스를 모름’) ") }, t.lines.toString())
        // A base AI: the family, and θ read from its description.
        val base = Scouting.report("철벽", Family.RISK, null, null, null, desc = "크게 망하는 걸 극도로 싫어함(θ=−0.1)")
        assertText(base.lines + base.advice)
        assertTrue(base.lines.any { it.contains("신중형") && it.contains("θ=−0.1") }, base.lines.toString())
        assertTrue(base.advice.contains("과감하게"))
        // Nothing known at all.
        val none = Scouting.report("무명", null, null, null, null)
        none.lines.forEach(::println)
        assertText(none.lines + none.advice)
        val few = Scouting.report("새내기", Family.HUMAN, null, profile(3), league)
        assertText(few.lines + few.advice)
        assertTrue(few.lines.any { it.contains("3판") })
    }

    // ---- lab -----------------------------------------------------------------------------------

    /** A's game wins with chance [p] on each match's own deal. */
    private fun labMatch(f: Fixture, p: Double): JobResult {
        val rng = Random(f.seed)
        val games = List(2) {
            if (rng.nextDouble() < p) GameScore(200 + rng.nextInt(60), 190 - rng.nextInt(40)) else GameScore(185 - rng.nextInt(40), 205 + rng.nextInt(60))
        }
        return job(f, games)
    }

    private fun runAll(s: LabSession, p: Double) {
        while (true) {
            val f = s.next() ?: break
            assertTrue(s.record(labMatch(f, p)))
        }
    }

    @Test
    fun labIntervalsShrinkWithMoreMatches() {
        val s = LabSession(1, "ai01", "ai02", 1000, 77L)
        var at200: Double? = null
        while (true) {
            val f = s.next() ?: break
            assertEquals(Comp.LAB, f.comp)
            assertTrue(!f.rated)
            assertEquals(DiceStream.mix(77L, 'X'.code.toLong(), f.id.toLong()), f.seed)
            s.record(labMatch(f, 0.6))
            if (s.matches == 200) at200 = s.results.shareCi
        }
        assertTrue(s.done && !s.stopped)
        val r = s.results
        println("lab: ${r.matches} matches, share ${r.share} ± ${r.shareCi}, Elo ${r.ratingDiff} [${r.ratingLow}, ${r.ratingHigh}], diff ${r.scoreDiff} ± ${r.scoreDiffCi}, P(A stronger) ${r.pStronger}")
        assertEquals(1000, r.matches)
        assertEquals(2000, r.games)
        assertTrue(r.shareCi < at200!! * 0.6, "${r.shareCi} vs $at200")
        assertTrue(abs(r.share - 0.6) < 0.04, "${r.share}")
        assertTrue(r.ratingLow < r.ratingDiff && r.ratingDiff < r.ratingHigh)
        assertTrue(r.ratingLow < 70.4 && 70.4 < r.ratingHigh, "${r.ratingLow}..${r.ratingHigh}")
        assertTrue(r.pStronger > 0.999)
        assertTrue(r.scoreDiff > 0 && r.scoreDiffCi > 0 && r.scoreDiffCi < r.scoreDiff)
        assertEquals(r.games, r.diffHistogram.sum())
        assertEquals(r.games, r.scoreHistogramA.sum())
        assertEquals(r.games, r.gameWins + r.gameDraws + r.gameLosses)
        assertEquals(r.matches, r.matchWins + r.matchDraws + r.matchLosses)
        assertEquals(10, LabStats.diffBin(0))
        assertEquals(11, LabStats.diffBin(10))
        assertEquals(0, LabStats.diffBin(-500))
        assertEquals(-200, LabStats.diffBinCenter(0))
        assertNull(s.next())
    }

    @Test
    fun untilPreciseStopsAtOnePercentPoint() {
        val s = LabSession(2, "ai01", "ai02", LabSession.UNTIL_PRECISE, 5L)
        runAll(s, 0.55)
        val r = s.results
        println("until ±1%p: ${r.matches} matches, share ${r.share} ± ${r.shareCi}")
        assertTrue(s.done && !s.stopped)
        assertTrue(r.shareCi <= LabSession.PRECISION)
        assertTrue(r.matches in LabSession.MIN_MATCHES until LabSession.CAP)
        // One more match would not have been needed: the previous count was not precise enough.
        assertTrue(r.matches > 3000, "${r.matches}")
        // No spread at all: stops as soon as it may.
        val sure = LabSession(3, "ai01", "ai02", LabSession.UNTIL_PRECISE, 5L)
        runAll(sure, 1.0)
        assertEquals(LabSession.MIN_MATCHES, sure.results.matches)
        assertEquals(1.0, sure.results.share)
        assertEquals(1.0, sure.results.pStronger)
    }

    @Test
    fun labResultsDoNotDependOnWhichMatchFinishesFirst() {
        fun run(order: (List<Fixture>) -> List<Fixture>): LabSession {
            val s = LabSession(4, "ai01", "ai02", LabSession.UNTIL_PRECISE, 9L)
            val rng = Random(1)
            while (!s.done) {
                val batch = generateSequence { s.next() }.take(1 + rng.nextInt(8)).toList()
                if (batch.isEmpty()) break
                // A stopped worker hands one back now and then: played again later.
                val (back, play) = batch.partition { rng.nextInt(10) == 0 }
                back.forEach(s::release)
                for (f in order(play)) s.record(labMatch(f, 0.6))
            }
            return s
        }
        val inOrder = run { it }
        val shuffled = run { it.shuffled(Random(it.size.toLong())) }
        val reversed = run { it.reversed() }
        assertTrue(inOrder.done)
        for (s in listOf(shuffled, reversed)) {
            assertEquals(inOrder.results.matches, s.results.matches)
            assertEquals(inOrder.results.share, s.results.share, 1e-12)
            assertEquals(inOrder.results.diffHistogram, s.results.diffHistogram)
        }
        // A result of another session, or one not handed out, is refused.
        val other = LabSession(5, "ai01", "ai02", 200, 10L)
        val f = other.next()!!
        assertTrue(!inOrder.record(labMatch(f, 0.5)))
        assertTrue(!other.record(labMatch(f.copy(id = 7, seed = other.seedOf(7)), 0.5)))
        assertTrue(other.record(labMatch(f, 0.5)))
        assertTrue(!other.record(labMatch(f, 0.5)))
    }

    @Test
    fun labBookSavesAndResumes() {
        val book = LabBook()
        // Two finished sessions, then one stopped half-way.
        runAll(book.start("ai01", "ai02", 200, 1L, "승률 AI (앱 기본)", "김 민준"), 0.6)
        assertTrue(book.current!!.done)
        val s2 = book.start("ai03", "ai04", 200, 2L)
        runAll(s2, 0.4)
        val third = book.start("ai05", "ai06", 1000, 3L)
        repeat(150) { book.record(labMatch(book.next()!!, 0.55)) }
        // In flight at the save: 0..4 handed out, 1, 2 and 4 in, 0 and 3 still playing.
        val flight = List(5) { book.next()!! }
        for (i in listOf(1, 2, 4)) book.record(labMatch(flight[i], 0.55))
        assertEquals(153, third.matches)
        assertEquals(2, third.inFlight)
        assertEquals(2, book.history.size)
        assertEquals("김 민준", book.history.last().nameB)
        val text = book.encode()
        println(text.lines().take(4).joinToString("\n"))
        val back = LabBook.decode(text)
        assertEquals(book.history.map { it.id to it.stats() }, back.history.map { it.id to it.stats() })
        val resumed = assertNotNull(back.current)
        assertEquals(153, resumed.matches)
        assertEquals(0, resumed.inFlight)
        // Both finish the same: the resumed one plays the two lost matches again.
        for (i in listOf(0, 3)) book.record(labMatch(flight[i], 0.55))
        runAll(third, 0.55)
        runAll(resumed, 0.55)
        assertEquals(third.results.stats(), resumed.results.stats())
        assertEquals(1000, resumed.results.matches)
        // A new session after a restart gets a new id; the newest 10 are kept.
        val b2 = LabBook.decode(text)
        val ids = (1..12).map { b2.start("ai01", "ai02", 200, it.toLong()).id.also { _ -> runAll(b2.current!!, 0.5) } }
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(ids.first() > 3)
        assertEquals(LabBook.KEEP, b2.history.size)
        assertEquals(ids.last(), b2.history.first().id)
        // Stopping keeps what was played.
        val b3 = LabBook()
        b3.start("ai01", "ai02", 1000, 4L)
        repeat(30) { b3.record(labMatch(b3.next()!!, 0.5)) }
        val stopped = assertNotNull(b3.stop())
        assertTrue(stopped.stopped && stopped.done)
        assertEquals(30, stopped.matches)
        assertNull(b3.next())
        assertEquals(stopped.stats(), LabBook.decode(b3.encode()).history.single().stats())
        // A finished session whose totals line was lost is played again to the same end.
        val b4 = LabBook()
        b4.start("ai01", "ai02", LabSession.UNTIL_PRECISE, 6L)
        while (true) b4.record(labMatch(b4.next() ?: break, 0.7))
        val whole = b4.current!!.results.stats()
        val lost = LabBook.decode(b4.encode().lines().filter { !it.startsWith("agg ") }.joinToString("\n"))
        val again = lost.current!!
        assertTrue(!again.done)
        runAll(again, 0.7)
        assertEquals(whole, again.results.stats())
        assertEquals(whole, lost.history.first().stats())
    }

    @Test
    fun aLabSessionWhoseAiIsGoneStops() {
        val book = LabBook()
        book.start("ai01", "ai02", 1000, 3L)
        repeat(40) { assertTrue(book.record(labMatch(book.next()!!, 0.6))) }
        val late = book.next()!!
        // The app forfeits a job whose AI is gone (retired at the season's end): one game, 1-0.
        val f = book.next()!!
        assertTrue(book.record(job(f, listOf(GameScore(1, 0)))))
        val s = assertNotNull(book.current)
        assertTrue(s.done && s.stopped)
        assertEquals(40, s.matches)
        assertNull(book.next())
        // A match still in flight is not taken any more.
        assertTrue(!book.record(labMatch(late, 0.6)))
        val r = book.history.single()
        assertTrue(r.stopped && r.done)
        assertEquals(40, r.matches)
        assertEquals(80, r.games)
        // Saved and read back, it stays over.
        val back = LabBook.decode(book.encode())
        assertTrue(back.current!!.done)
        assertNull(back.next())
        assertEquals(r.stats(), back.history.single().stats())
        // Gone before any match: stopped, and nothing to keep.
        val empty = LabBook()
        empty.start("ai01", "gone", 200, 4L)
        assertTrue(empty.record(job(empty.next()!!, listOf(GameScore(0, 1)))))
        assertTrue(empty.current!!.done)
        assertTrue(empty.history.isEmpty())
        assertNull(empty.next())
    }

    @Test
    fun aCutLabFileLosesOnlyItsTail() {
        val book = LabBook()
        runAll(book.start("ai01", "ai02", 200, 1L, "이름 있음"), 0.6)
        book.start("ai03", "ai04", 1000, 2L)
        repeat(120) { book.record(labMatch(book.next()!!, 0.5)) }
        val flight = List(4) { book.next()!! }
        book.record(labMatch(flight[2], 0.5))
        val text = book.encode()
        val first = book.history.single().stats()
        val whole = LabBook.decode(text).current!!.also { runAll(it, 0.5) }.results.stats()
        for (cut in LabBook.HEADER.length until text.length) {
            val b = LabBook.decode(text.substring(0, cut))
            // The finished result is kept whole or not at all.
            assertTrue(b.history.all { it.stats() == first }, "cut $cut")
            b.current?.let { s ->
                assertTrue(s.matches <= 121, "cut $cut: ${s.matches}")
                // Whatever was lost is played again: the session finishes as the whole file's does.
                runAll(s, 0.5)
                assertTrue(s.done, "cut $cut")
                assertEquals(whole, s.results.stats(), "cut $cut")
            }
        }
        assertFailsWith<IllegalArgumentException> { LabBook.decode("ladder 1\np x") }
        assertFailsWith<IllegalArgumentException> { LabBook.decode("") }
    }

    /** The saved figures of a result, for comparing two of them. */
    private fun yacht.engine.league.LabResult.stats(): List<Any> =
        listOf(matches, games, gameWins, gameDraws, matchWins, matchDraws, share, shareCi, scoreDiff, diffHistogram, scoreHistogramA, scoreHistogramB, nameA, nameB, target, stopped)

    private companion object {
        // Roughly the league's average points per box and zero rates.
        val LEAGUE_AVG = listOf(2.1, 5.3, 8.4, 11.6, 14.6, 17.7, 21.8, 13.0, 17.5, 11.0, 18.0, 15.0)
        val LEAGUE_ZERO = listOf(0.05, 0.03, 0.02, 0.02, 0.02, 0.02, 0.0, 0.35, 0.25, 0.27, 0.4, 0.7)
    }
}

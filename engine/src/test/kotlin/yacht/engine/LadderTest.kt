package yacht.engine

import yacht.engine.league.GameScore
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.Matchmaker
import yacht.engine.league.LeagueTables
import yacht.engine.league.Tier
import yacht.engine.league.TierName
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderTest {
    @Test
    fun aCutFileLosesOnlyItsLastLine() {
        val text = java.io.File("../android/app/src/main/assets/league_seed.txt").readText()
        val keys = text.lines().filter { it.startsWith("p ") }.map { it.split(' ')[1] }
        val full = Ladder.decode(keys, text)
        // Older versions saved in place: a kill left the file cut at a page boundary.
        for (cut in listOf(4096, 65536, 100_000, text.length - 7)) {
            val l = Ladder.decode(keys, text.substring(0, cut))
            assertTrue(l.keys.count { (l.entry(it)?.games ?: 0) > 0 } > 0, "cut at $cut kept nothing")
            if (cut > 100_000) assertEquals(full.rating("win"), l.rating("win"), 1.0)
        }
    }

    @Test
    fun tiersFollowRatingAndRank() {
        assertEquals(TierName.CHALLENGER, Tier.of(1515.0, 1).name)
        assertEquals(TierName.GRANDMASTER, Tier.of(1515.0, 5).name)
        assertEquals(TierName.MASTER, Tier.of(1515.0, 20).name)
        assertEquals(TierName.MASTER, Tier.of(1492.0, 1).name)
        assertEquals(Tier(TierName.DIAMOND, 3, 10), Tier.of(1461.0, 30))
        assertEquals(TierName.IRON, Tier.of(37.0, 76).name)
        assertEquals(4, Tier.of(-50.0, 76).division)
        // Higher ratings never get a lower tier.
        val orders = (0..1600 step 5).map { Tier.of(it.toDouble(), 50).order }
        assertTrue(orders.zipWithNext().all { (a, b) -> a <= b })
        assertEquals("다이아몬드 III", Tier.of(1461.0, 30).label)
    }

    @Test
    fun ratingsRecoverKnownStrengths() {
        // True Elo: ev 1500, b 1400, c 1200; results drawn from the Bradley-Terry model.
        val elo = mapOf("ev" to 1500.0, "b" to 1400.0, "c" to 1200.0)
        val ladder = Ladder(elo.keys.toList())
        val rng = Random(1)
        fun p(x: String, y: String) = 1 / (1 + Math.pow(10.0, (elo.getValue(y) - elo.getValue(x)) / 400))
        val pairs = listOf("ev" to "b", "ev" to "c", "b" to "c")
        repeat(3000) { k ->
            val (x, y) = pairs[k % 3]
            val games = List(2) { if (rng.nextDouble() < p(x, y)) GameScore(200, 150) else GameScore(150, 200) }
            ladder.record(x, y, games)
        }
        assertEquals(1500.0, ladder.rating("ev"), 1e-9)
        // Within four standard errors (about 6 points each with 2,000 games per player): this
        // seed's draws are themselves off (ev won 82.5% against c where the model says 84.9%).
        assertEquals(1400.0, ladder.rating("b"), 4 * ladder.error("b"))
        assertEquals(1200.0, ladder.rating("c"), 4 * ladder.error("c"))
        assertTrue(ladder.error("b") in 2.0..10.0, "${ladder.error("b")}")
        assertEquals(listOf("ev", "b", "c"), ladder.ranking())
    }

    @Test
    fun placementStreaksAndTheFeed() {
        val ladder = Ladder(listOf("ev", "x"))
        val matches = Ladder.PLACEMENT / 2
        repeat(matches - 1) { ladder.record("ev", "x", listOf(GameScore(200, 100), GameScore(210, 120))) }
        assertNull(ladder.rankOf("x"))
        val r = ladder.record("ev", "x", listOf(GameScore(310, 100), GameScore(200, 120)))
        assertEquals(1, ladder.rankOf("ev"))
        assertTrue(r.notes.any { it.startsWith("배치 완료|ev") }, r.notes.toString())
        assertTrue(r.notes.any { it == "연승|ev|${Ladder.PLACEMENT}" }, r.notes.toString())
        assertTrue(r.notes.any { it == "연패|x|${Ladder.PLACEMENT}" }, r.notes.toString())
        assertTrue(r.notes.any { it == "대기록|ev|310" }, r.notes.toString())
        assertEquals(Ladder.PLACEMENT, ladder.entry("ev")!!.streak)
        assertEquals("WWWWWWWWWW", ladder.entry("ev")!!.recent.joinToString(""))
        assertEquals(matches, ladder.feed.size)
        assertEquals(ladder.rating("ev"), ladder.entry("ev")!!.peak)
    }

    @Test
    fun savingKeepsEverything() {
        val keys = listOf("ev", "a", "b", "c")
        val ladder = Ladder(keys)
        val rng = Random(2)
        repeat(60) {
            val (x, y) = Matchmaker.pick(ladder, rng, emptySet())!!
            assertNotEquals(x, y)
            ladder.record(x, y, List(2) { GameScore(rng.nextInt(300), rng.nextInt(300), rng.nextBoolean(), false, true, rng.nextBoolean()) }, time = it.toLong())
        }
        // A match nudges only its two players; the full fit (as a decode runs) settles everyone.
        ladder.fit()
        val back = Ladder.decode(keys, ladder.encode())
        for (k in keys) {
            assertEquals(ladder.rating(k), back.rating(k), 0.05, k)
            val a = ladder.entry(k)!!
            val b = back.entry(k)!!
            assertEquals(listOf(a.games, a.wins, a.ties, a.losses, a.best, a.yachts, a.bonuses, a.streak), listOf(b.games, b.wins, b.ties, b.losses, b.best, b.yachts, b.bonuses, b.streak))
            assertEquals(a.recent.toList(), b.recent.toList())
            assertEquals(a.history.toList(), b.history.toList())
        }
        assertEquals(ladder.feed.toList(), back.feed.toList())
        assertEquals(ladder.matches, back.matches)
        // A newer roster without "c" and with "d": c's games leave the fit, d starts fresh.
        val next = Ladder.decode(listOf("ev", "a", "b", "d"), ladder.encode())
        assertEquals(0, next.entry("d")!!.games)
        assertTrue(next.rating("d").isNaN())
    }

    @Test
    fun quickStepsStayNearTheFullFitAndTheSplitFitMatchesIt() {
        val text = java.io.File("../android/app/src/main/assets/league_seed.txt").readText()
        val keys = text.lines().filter { it.startsWith("p ") }.map { it.split(' ')[1] }
        val ladder = Ladder.decode(keys, text)
        val rng = Random(5)
        val t0 = System.nanoTime()
        repeat(2000) {
            val (x, y) = Matchmaker.pick(ladder, rng, emptySet())!!
            ladder.record(x, y, List(2) { GameScore(150 + rng.nextInt(120), 150 + rng.nextInt(120)) })
        }
        val perRecord = (System.nanoTime() - t0) / 2000 / 1e6
        val quick = keys.associateWith { ladder.rating(it) }
        val input = ladder.fitInput()
        val solved = Ladder.solve(input)
        ladder.applyFit(solved)
        val full = keys.associateWith { ladder.rating(it) }
        val worst = keys.filter { !full.getValue(it).isNaN() }.maxOf { abs(quick.getValue(it) - full.getValue(it)) }
        assertTrue(worst < 15.0, "quick ratings drift $worst from the full fit")
        // Ranks follow the ratings.
        val ranked = ladder.ranking().filter { ladder.rankOf(it) != null }
        assertTrue(ranked.zipWithNext().all { (a, b) -> ladder.rankScore(a) >= ladder.rankScore(b) })
        assertEquals((1..ranked.size).toList(), ranked.map { ladder.rankOf(it) })
        // Matches recorded while the fit is solved elsewhere are not lost.
        val before = ladder.entry("ev")!!.games
        val pending = ladder.fitInput()
        repeat(20) { ladder.record("ev", keys[1 + it % 5], listOf(GameScore(300, 100))) }
        ladder.applyFit(Ladder.solve(pending))
        assertEquals(before + 20, ladder.entry("ev")!!.games)
        ladder.fit()
        val again = Ladder.decode(keys, ladder.encode())
        for (k in keys) assertEquals(ladder.rating(k), again.rating(k), 0.05, k)
        assertTrue(perRecord < 1.0, "a match takes $perRecord ms on the ladder")
    }

    @Test
    fun matchmakingSkipsBusyAndPrefersCloseRatings() {
        val keys = listOf("ev") + (1..20).map { "p$it" }
        val ladder = Ladder(keys)
        // Fake a ladder spread: p_k loses to ev more the larger k is.
        for (k in 1..20) repeat(Ladder.PLACEMENT) { g -> ladder.record("ev", "p$k", listOf(if (g < k) GameScore(100, 200) else GameScore(200, 100))) }
        val rng = Random(3)
        var far = 0
        repeat(2000) {
            val (a, b) = Matchmaker.pick(ladder, rng, setOf("p1"))!!
            assertTrue(a != "p1" && b != "p1")
            if (abs(ladder.rating(a) - ladder.rating(b)) > 400) far++
        }
        assertTrue(far < 300, "far pairs $far")
    }

    @Test
    fun everyLeagueAiFinishesAMatch() {
        val tables = SolverTest.tables
        val roster = LeagueRoster.build(tables, LeagueTables(tables.rules, threads = Runtime.getRuntime().availableProcessors()))
        assertEquals(roster.size, roster.map { it.key }.toSet().size)
        assertTrue(roster.size >= 100, "${roster.size} AIs")
        val ev = roster.first { it.key == "ev" }
        val scores = HashMap<String, Double>()
        for ((i, ai) in roster.withIndex()) {
            val games = LeagueMatch.play(tables.rules, ai, ev, seed = i.toLong())
            assertEquals(2, games.size)
            scores[ai.key] = games.map { it.a }.average()
        }
        // The best stay well above random play on the same deals.
        assertTrue(scores.getValue("win") > scores.getValue("random") + 60, "$scores")
        println(scores.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        // Same seed, same dice, same choices: a match replays exactly.
        val win = roster.first { it.key == "win" }
        assertEquals(LeagueMatch.play(tables.rules, win, ev, 9), LeagueMatch.play(tables.rules, win, ev, 9))
    }
}

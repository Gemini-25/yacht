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

    @Test
    fun luckyNewcomersDoNotTopTheLadder() {
        // 100 established AIs with thousands of games, then 100 newcomers (true level at most
        // 1500) with 20 games each, mostly against each other and weaker AIs: the luckiest come
        // out far above their level, but none ranks in the top 10.
        for (seed in 1L..2L) {
            val rng = Random(seed)
            val est = listOf("ev") + (1..99).map { "e$it" }
            val news = (1..100).map { "n$it" }
            val truth = HashMap<String, Double>()
            for ((i, k) in est.withIndex()) truth[k] = if (k == "ev") 1500.0 else 1100.0 + 420.0 * i / 99
            for (k in news) truth[k] = minOf(1500.0, 1430 + 40 * rng.nextGaussian())
            val l = Ladder(est + news + Ladder.ME)
            fun game(a: String, b: String): GameScore {
                val p = 1 / (1 + Math.pow(10.0, (truth.getValue(b) - truth.getValue(a)) / 400))
                return if (rng.nextDouble() < p) GameScore(200, 180) else GameScore(180, 200)
            }
            repeat(60_000) {
                val a = est[rng.nextInt(est.size)]
                var b = est[rng.nextInt(est.size)]
                while (b == a) b = est[rng.nextInt(est.size)]
                l.record(a, b, listOf(game(a, b), game(a, b)))
            }
            val low = est.filter { truth.getValue(it) in 1250.0..1420.0 }
            for (k in news) repeat(10) {
                val b = if (rng.nextDouble() < 0.6) news.filter { it != k }[rng.nextInt(99)] else low[rng.nextInt(low.size)]
                l.record(k, b, listOf(game(k, b), game(k, b)))
            }
            l.fit()
            assertTrue(news.maxOf { l.rating(it) } > 1550, "seed $seed: no lucky newcomer to test with")
            val top = l.ranking().filter { it != Ladder.ME }.take(10)
            assertTrue(top.none { it in news }, "seed $seed: ${top.filter { it in news }.map { it to l.rating(it).toInt() }}")
        }
    }

    @Test
    fun establishedAisRankByRatingWhateverTheirGameCount() {
        // Some AIs play four times as many games as others (light ones used to get friendlies):
        // once each has enough games, the ranking follows the rating, not the count.
        val rng = Random(9)
        val keys = listOf("ev") + (1..39).map { "a$it" }
        val truth = keys.withIndex().associate { (i, k) -> k to if (k == "ev") 1500.0 else 1300.0 + 5.0 * i }
        val l = Ladder(keys + Ladder.ME)
        fun game(a: String, b: String): GameScore {
            val p = 1 / (1 + Math.pow(10.0, (truth.getValue(b) - truth.getValue(a)) / 400))
            return if (rng.nextDouble() < p) GameScore(200, 180) else GameScore(180, 200)
        }
        repeat(40_000) {
            // Odd-numbered AIs are chosen four times as often.
            fun pick(): String { while (true) { val k = keys[rng.nextInt(keys.size)]; if (k == "ev" || k.drop(1).toInt() % 2 == 1 || rng.nextInt(4) == 0) return k } }
            val a = pick()
            var b = pick()
            while (b == a) b = pick()
            l.record(a, b, listOf(game(a, b), game(a, b)))
        }
        l.fit()
        val few = keys.filter { it != "ev" && it.drop(1).toInt() % 2 == 0 }
        val many = keys.filter { it != "ev" && it.drop(1).toInt() % 2 == 1 }
        assertTrue(few.sumOf { l.entry(it)!!.games } * 2 < many.sumOf { l.entry(it)!!.games }, "the counts should differ")
        assertTrue(keys.all { l.error(it) < Ladder.RANK_FREE_ERROR }, "everyone should have enough games")
        val ranked = l.ranking().filter { it != Ladder.ME }
        assertEquals(ranked.sortedByDescending { l.rating(it) }, ranked)
    }

    @Test
    fun ratingsFollowAPlayerWhoChangedAndTheRosterChangesInPlace() {
        // b was weak for many games, then became as strong as ev: after a fade, its rating moves up much faster.
        val keys = listOf("ev", "b", "c")
        fun build() = Ladder(keys).also { l ->
            repeat(400) { l.record("ev", "b", listOf(GameScore(200, 150), GameScore(200, 150))) }
            repeat(400) { l.record("ev", "c", listOf(GameScore(200, 150), GameScore(150, 200))) }
            l.fit()
        }
        val plain = build()
        val faded = build().also { it.decay(setOf("b"), 0.5, 1); it.decay(setOf("b"), 0.5, 2); it.decay(setOf("b"), 0.5, 3); it.fit() }
        // Fading again for the same season changes nothing.
        val again = faded.encode()
        faded.decay(setOf("b"), 0.5, 3)
        assertEquals(again, faded.encode())
        for (l in listOf(plain, faded)) {
            repeat(30) { l.record("ev", "b", listOf(GameScore(200, 150), GameScore(150, 200))) }
            l.fit()
        }
        assertTrue(faded.rating("b") > plain.rating("b") + 100, "${faded.rating("b")} vs ${plain.rating("b")}")
        // The career record is untouched by the fade.
        assertEquals(plain.headToHead("ev", "b"), faded.headToHead("ev", "b"))
        assertEquals(plain.entry("b")!!.games, faded.entry("b")!!.games)
        // Saved and read back with the faded weights and strengths: the same ratings.
        val back = Ladder.decode(keys, faded.encode())
        for (k in keys) assertEquals(faded.rating(k), back.rating(k), 0.01)
        assertEquals(faded.decayed, back.decayed)
        // Rekeyed in place: a player gone, one new; the rest keep everything.
        val re = faded.rekey(listOf("ev", "b", "d"))
        assertEquals(faded.rating("b"), re.rating("b"), 0.5)
        assertEquals(faded.headToHead("ev", "b"), re.headToHead("ev", "b"))
        assertEquals(0, re.entry("d")!!.games)
        assertEquals(0 to 0, re.headToHead("ev", "c").let { it.first.toInt() to it.second })
        assertEquals(Ladder.decode(listOf("ev", "b", "d"), faded.encode()).encode(), re.encode())
    }

    @Test
    fun oldPairsOfAFewGamesAreDroppedWhenTheRosterChanges() {
        val keys = listOf("ev", "a", "b", "c")
        val l = Ladder(keys)
        // a met b once long ago; a and c met often; b and c met once this season.
        l.record("a", "b", listOf(GameScore(200, 150), GameScore(150, 200)))
        repeat(10) { l.record("a", "c", listOf(GameScore(200, 150), GameScore(200, 150))) }
        for (season in 1..4) l.decay(setOf("a", "b", "c"), 0.5, season)
        l.record("b", "c", listOf(GameScore(200, 150), GameScore(150, 200)))
        val re = l.rekey(keys)
        assertEquals(0, re.headToHead("a", "b").second, "faded and few: gone")
        assertEquals(20, re.headToHead("a", "c").second, "many games: kept")
        assertEquals(2, re.headToHead("b", "c").second, "recent: kept")
        // The career records stay.
        assertEquals(l.entry("b")!!.games, re.entry("b")!!.games)
    }

    @Test
    fun aLadderOfThousandsStaysSmallAndFitsQuickly() {
        // 3000 AIs, each playing 40 others near it: 60k pairs.
        val n = 3000
        val keys = listOf("ev") + (1 until n).map { "g$it" }
        val l = Ladder(keys)
        val rng = Random(4)
        val level = DoubleArray(n) { 1500 - it * 0.2 }
        for (i in 0 until n) repeat(20) {
            val j = ((i + 1 + rng.nextInt(80)) % n)
            if (j == i) return@repeat
            val pa = 1 / (1 + Math.pow(10.0, (level[j] - level[i]) / 400))
            l.record(keys[i], keys[j], List(2) { if (rng.nextDouble() < pa) GameScore(200, 150) else GameScore(150, 200) })
        }
        val t0 = System.nanoTime()
        l.fit()
        val cold = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        l.fit()
        val warm = (System.nanoTime() - t1) / 1e6
        println("pairs ${l.pairCount}, cold fit $cold ms, warm $warm ms")
        assertTrue(l.pairCount in 40_000..70_000)
        assertTrue(warm < 2_000, "warm fit $warm ms")
        // Read back warm from the saved strengths.
        val t2 = System.nanoTime()
        val back = Ladder.decode(keys, l.encode())
        println("decode ${(System.nanoTime() - t2) / 1e6} ms")
        assertEquals(l.rating("g1500"), back.rating("g1500"), 0.5)
    }
}

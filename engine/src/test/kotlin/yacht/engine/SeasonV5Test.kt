package yacht.engine

import yacht.engine.league.Confed
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueFixture
import yacht.engine.league.Leagues
import yacht.engine.league.Season
import yacht.engine.league.Tiers
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A season of national leagues (`season 5`): the aligned schedule, its save, and the new questions a season answers. */
class SeasonV5Test {
    private fun keys(prefix: String, n: Int) = (0 until n).map { prefix + "%02d".format(it) }

    /** Division sizes 20, 20, 19 (KR), 12, 11 (JP), 4 (XAS), 16 (XWO): 38, 38, 38, 22, 22, 6 and 30 rounds home and away. */
    private val sizes = listOf(20, 20, 19, 12, 11, 4, 16)
    private val divisions = sizes.mapIndexed { d, n -> keys("d${d}k", n) }
    private val leagues = Leagues.national(listOf("KR", "JP", "XAS", "XWO"), listOf(3, 2, 1, 1), Confed.AMERICAS)

    private fun national(double: Boolean = true, seed: Long = 0xA11CEL) = Season.start(5, seed, divisions, double, leagues)

    private val rng = Random(3)

    private fun games(): List<GameScore> = List(2) { GameScore(150 + rng.nextInt(120), 150 + rng.nextInt(120), aYacht = rng.nextInt(9) == 0) }

    /** Plays [count] fixtures in the order the season hands them out. */
    private fun play(s: Season, count: Int = Int.MAX_VALUE, result: (LeagueFixture) -> List<GameScore> = { games() }) {
        var n = 0
        while (n < count) {
            val f = s.next(emptySet()) ?: break
            s.record(f.id, result(f))
            n++
        }
    }

    @Test
    fun everyDivisionPlaysItsFirstRoundInTheFirstWeekAndItsLastInTheLast() {
        val s = national()
        assertTrue(s.leagues.national)
        assertEquals(38, s.matchdays)
        assertEquals(listOf(38, 38, 38, 22, 22, 6, 30), s.divisions.indices.map { s.roundsOf(it) })
        for (d in s.divisions.indices) {
            val weeks = (0 until s.roundsOf(d)).map { s.weekOf(d, it) }
            assertEquals(0, weeks.first(), "division $d")
            assertEquals(s.matchdays - 1, weeks.last(), "division $d")
            assertTrue(weeks.zipWithNext().all { (a, b) -> b > a }, "division $d: distinct weeks in order")
        }
        val all = (0 until s.fixturesTotal).map { s.fixture(it) }
        for (f in all) assertEquals(s.weekOf(f.division, f.round), f.matchday)
        // A round is played in one week; ids in week order, then division.
        assertTrue(all.groupBy { it.division to it.round }.values.all { fs -> fs.map { it.matchday }.toSet().size == 1 })
        assertTrue(all.zipWithNext().all { (a, b) -> a.matchday < b.matchday || (a.matchday == b.matchday && a.division <= b.division) })
        // Nobody plays twice in a week; a division idles in its gap weeks.
        for (md in 0 until s.matchdays) {
            val day = s.matchday(md)
            val sides = day.flatMap { listOf(it.a, it.b) }
            assertEquals(sides.size, sides.toSet().size, "week $md")
        }
        assertTrue((0 until s.matchdays).any { md -> s.matchday(md).none { it.division == 3 } }, "the 22 rounds of 12 players leave weeks free")
        // Jobs: the division's own round, the league stage's week.
        val f = all.first { it.division == 3 && it.round == 5 }
        val job = s.job(f)
        assertEquals("6라운드", job.label)
        assertEquals(f.matchday + 1, job.week)
        assertTrue(f.matchday > 5)
        // Every division's rounds are the same pairs a legacy season of these divisions plays: only the weeks change.
        val legacy = Season.start(5, 0xA11CEL, divisions, true)
        val pairs = { x: Season -> (0 until x.fixturesTotal).map { x.fixture(it) }.groupBy({ it.division to it.round }, { it.a to it.b }) }
        assertEquals(pairs(legacy), pairs(s))
        assertFalse(legacy.leagues.national)
    }

    @Test
    fun positionsGainARowPerRoundAndTheCountsFollowTheRounds() {
        val s = national()
        play(s, s.fixturesTotal / 2)
        for (d in s.divisions.indices) {
            val counts = s.roundCounts(d)
            assertEquals(s.roundsOf(d), counts.size)
            assertEquals(s.positions[d].size, counts.count { it.done }, "division $d")
        }
        play(s)
        assertTrue(s.finished)
        for (d in s.divisions.indices) {
            assertEquals(s.roundsOf(d), s.positions[d].size, "division $d")
            assertTrue(s.roundCounts(d).all { it.done && it.total == s.divisions[d].size / 2 })
        }
        assertTrue((0 until s.matchdays).all { s.dayDone(it) })
    }

    @Test
    fun aNationalSeasonSavesAsSeason5AndComesBack() {
        val s = national()
        s.bonus = true
        play(s, 900)
        val taken = s.next(emptySet())!!
        val text = s.encode()
        assertTrue(text.startsWith("season 5\n"))
        assertTrue("\nleague KR 0 3\nleague JP 3 2\nleague XAS 5 1\nleague XWO 6 1 AMERICAS\nfixtures " in text)
        val all = divisions.flatten()
        val back = Season.decode(text, all)
        assertEquals(text, back.encode())
        assertEquals(s.leagues, back.leagues)
        assertEquals(s.fixturesPlayed, back.fixturesPlayed)
        assertFalse(back.isPlayed(taken.id), "a fixture in progress is played again")
        for (k in all) {
            val a = s.standings.getValue(k)
            val b = back.standings.getValue(k)
            assertEquals(listOf(a.played, a.wins, a.draws, a.losses, a.points, a.scored, a.sweeps), listOf(b.played, b.wins, b.draws, b.losses, b.points, b.scored, b.sweeps), k)
        }
        assertEquals((0 until s.fixturesTotal).map { s.fixture(it) }, (0 until back.fixturesTotal).map { back.fixture(it) })
        assertEquals(s.positions.map { ds -> ds.map { it.toList() } }, back.positions.map { ds -> ds.map { it.toList() } })
        // The fixtures must rebuild, and the leagues must cover the divisions.
        assertFailsWith<IllegalArgumentException> { Season.decode(text.replace(Regex("fixtures \\d+"), "fixtures 7"), all) }
        assertFailsWith<IllegalArgumentException> { Season.decode(text.replace("league XWO 6 1 AMERICAS\n", ""), all) }
        assertFailsWith<IllegalArgumentException> { Season.decode(text.lines().filter { !it.startsWith("league ") }.joinToString("\n"), all) }
        // A league of a nation a newer version added: unreadable here (a fresh season starts).
        assertFailsWith<IllegalArgumentException> { Season.decode(text.replace("\nleague JP 3 2\n", "\nleague ZZ 3 2\n"), all) }
    }

    @Test
    fun aSeason4StillReadsWithTheScheduleItWasBuiltWith() {
        val legacy = Season.start(2, 77L, divisions.take(4), true, Tiers.linear(4))
        play(legacy, 300)
        val text = legacy.encode()
        assertTrue(text.startsWith("season 4\n") && "\nleague " !in text)
        val back = Season.decode(text, divisions.flatten())
        assertEquals(text, back.encode())
        assertFalse(back.leagues.national)
        // Not aligned: the 12 players of division 3 are done after week 22 of 38.
        assertEquals(38, back.matchdays)
        assertEquals(21, (0 until back.fixturesTotal).map { back.fixture(it) }.filter { it.division == 3 }.maxOf { it.matchday })
        assertTrue((0 until back.fixturesTotal).all { back.fixture(it).let { f -> f.round == f.matchday } })
        assertFailsWith<IllegalArgumentException> { Season.decode(text.replace(Regex("fixtures \\d+"), "fixtures 7"), divisions.flatten()) }
    }

    @Test
    fun theWeekPickGoesToTheHigherTierOfAnyLeague() {
        // Two leagues of two divisions of 4: in week 1 the winners of KR 2부 and JP 1부 score the same.
        val divs = listOf(keys("a", 4), keys("b", 4), keys("c", 4), keys("e", 4))
        val s = Season.start(1, 9L, divs, false, Leagues.national(listOf("KR", "JP"), listOf(2, 2)))
        while (true) {
            val f = s.next(emptySet()) ?: break
            if (f.matchday > 0) {
                s.release(f.id)
                break
            }
            val win = f.division == 1 || f.division == 2
            s.record(f.id, if (win) List(2) { GameScore(210, 150) } else List(2) { GameScore(180, 180) })
        }
        val pick = s.playersOfWeek(0..0).getValue(0)
        assertEquals(2, pick.division, "JP 1부 (tier 0) before KR 2부 (tier 1)")
        assertEquals(210.0, pick.average)
    }

    @Test
    fun theSweepPointComesOnlyWithTheBonus() {
        val s = Season.start(1, 4L, listOf(keys("a", 4)), false, Leagues.national(listOf("KR"), listOf(1)))
        assertFalse(s.bonus, "a season on its own has no bonus")
        val sweep = List(2) { GameScore(220, 180) }
        play(s, 1) { sweep }
        val first = s.standings.values.first { it.wins == 1 }
        assertEquals(3, first.points)
        assertEquals(0, first.sweeps)
        s.bonus = true
        play(s) { sweep }
        assertTrue(s.standings.values.sumOf { it.sweeps } == s.fixturesTotal - 1)
    }

    @Test
    fun eachPlayerCountsItsOwnFixtures() {
        val s = national()
        assertEquals(38, s.fixturesOf("d0k00"), "20 players home and away")
        assertEquals(36, s.fixturesOf("d2k05"), "19 players: 2 of the 38 rounds off")
        assertEquals(20, s.fixturesOf("d4k10"), "11 players: 2 of 22 off")
        assertEquals(6, s.fixturesOf("d5k00"))
        assertEquals(0, s.fixturesOf("nobody"))
        assertEquals(10, national(double = false).fixturesOf("d4k00"), "once each: 10 of 11 rounds")
        // Leagues and tiers of a player.
        assertEquals("JP", s.leagueOf("d4k00")?.id)
        assertEquals(1, s.tierOf("d4k00"))
        assertEquals(0, s.tierOf("d6k03"))
        assertNull(s.leagueOf("nobody"))
        assertNull(s.tierOf("nobody"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun theOldTiersNeverThrow() {
        val s = national()
        val flat = s.tiers
        assertEquals(s.divisions.size, flat.size)
        assertTrue(flat.linear)
        for (d in s.divisions.indices) {
            assertEquals(d, flat.tier(d))
            assertTrue(flat.label(d).isNotEmpty())
        }
        // One league alone keeps its own tiers; a legacy season its pyramid.
        val one = Season.start(1, 2L, divisions.take(3), false, Leagues.national(listOf("KR"), listOf(3)))
        assertEquals(Tiers.linear(3), one.tiers)
        val pyramid = Tiers.of(listOf(1, 2), listOf("", "북", "남"))
        val legacy = Season.start(1, 2L, divisions.take(3), false, pyramid)
        assertEquals(pyramid, legacy.tiers)
        assertEquals(Leagues.legacy(pyramid), legacy.leagues)
    }

    @Test
    fun emptyDivisionsAndLeaguesLeftWithNoneAreDropped() {
        val divs = listOf(keys("a", 6), emptyList(), emptyList(), keys("w", 4))
        val s = Season.start(1, 3L, divs, true, Leagues.national(listOf("KR", "JP", "XWO"), listOf(2, 1, 1), Confed.EUROPE))
        assertEquals(listOf("KR", "XWO"), s.leagues.list.map { it.id })
        assertEquals(listOf(1, 1), s.leagues.list.map { it.size })
        assertEquals(Confed.EUROPE, s.leagues.worldConfed)
        assertEquals(2, s.divisions.size)
        // A legacy Leagues builds exactly what the tiers overload builds.
        val pyramid = Tiers.of(listOf(1, 2))
        val divs3 = listOf(keys("a", 6), keys("b", 5), keys("c", 5))
        assertEquals(Season.start(1, 3L, divs3, false, pyramid).encode(), Season.start(1, 3L, divs3, false, Leagues.legacy(pyramid)).encode())
        // The same seed builds the same season.
        assertEquals(national().encode(), national().encode())
    }
}

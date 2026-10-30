package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.BracketSide
import yacht.engine.league.Comp
import yacht.engine.league.Cup
import yacht.engine.league.DrawEntry
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Knockout
import yacht.engine.league.KnockoutLength
import yacht.engine.league.KnockoutTie
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import yacht.engine.league.MatchFormat
import yacht.engine.league.Season
import yacht.engine.league.SeasonStart
import yacht.engine.league.TieRule
import yacht.engine.league.TieScore
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 판 승점제 with the 2-0 bonus, and series knockouts with double elimination (version 2 rules). */
class NewRulesTest {
    private fun g(a: Int, b: Int) = GameScore(a, b)

    @Test
    fun gamesDecideAMatchAndATotalNeverDoes() {
        val f = MatchFormat.GAMES
        assertEquals(1, TieRule.match(listOf(g(200, 180), g(190, 170)), f), "2-0")
        assertEquals(1, TieRule.match(listOf(g(200, 180), g(190, 190)), f), "a win and a draw")
        assertEquals(0, TieRule.match(listOf(g(250, 150), g(170, 180)), f), "1-1 is a draw however far apart the totals")
        assertEquals(0, TieRule.match(listOf(g(190, 190), g(200, 200)), f), "two draws")
        assertEquals(-1, TieRule.match(listOf(g(150, 180), g(190, 190)), f))
        // The two-game total, as before.
        assertEquals(1, TieRule.match(listOf(g(250, 150), g(170, 180)), MatchFormat.MIRRORED))
        assertTrue(TieRule.sweptByA(listOf(g(200, 180), g(190, 170))))
        assertFalse(TieRule.sweptByA(listOf(g(200, 180), g(190, 190))))
        assertTrue(TieRule.sweptByB(listOf(g(100, 180), g(150, 170))))
        // A team match level on boards is a draw under 판 승점제 (a knockout then goes by the totals).
        val boards = listOf(
            BoardResult("a", "b", listOf(g(200, 180), g(200, 180)), emptyList()),
            BoardResult("c", "d", listOf(g(100, 180), g(100, 180)), emptyList()),
        )
        assertEquals(0, TieRule.team(boards, f))
        assertEquals(-1, TieRule.team(boards, MatchFormat.MIRRORED))
    }

    @Test
    fun aSweepEarnsTheBonusPointInTheTable() {
        val keys = listOf("a", "b", "c", "d")
        val s = Season.start(1, 5L, listOf(keys))
        s.format = MatchFormat.GAMES
        // As a season plan sets it from its rules (LeagueConfig.sweepPoints).
        s.bonus = true
        while (true) {
            val f = s.next(emptySet()) ?: break
            // a sweeps everyone, b and c split, d loses the rest.
            val games = when {
                f.a == "a" -> listOf(g(220, 180), g(220, 180))
                f.b == "a" -> listOf(g(180, 220), g(180, 220))
                setOf(f.a, f.b) == setOf("b", "c") -> listOf(g(220, 180), g(180, 220))
                f.a == "d" -> listOf(g(180, 220), g(200, 200))
                else -> listOf(g(200, 200), g(220, 180))
            }
            s.record(f.id, games)
        }
        val st = s.standings
        assertEquals(3 * 4, st.getValue("a").points, "three sweeps: 4 points each")
        assertEquals(3, st.getValue("a").sweeps)
        // b and c drew (1-1), each beat d by a win and a draw (3, no bonus).
        assertEquals(1 + 3, st.getValue("b").points)
        assertEquals(0, st.getValue("b").sweeps)
        assertEquals(1, st.getValue("b").draws)
        assertEquals(0, st.getValue("d").points)
    }

    @Test
    fun oldSettingsKeepTheirRulesAndALeagueMovesOnFromItsNextSeason() {
        val old = "config 1\ndivisions 3\nformat MIRRORED\nlongKnockouts 1\n"
        val read = LeagueConfig.decode(old)
        assertEquals(MatchFormat.MIRRORED, read.matchFormat)
        assertFalse(read.seriesKnockouts)
        assertFalse(read.doubleElimination)
        assertEquals(3, read.divisions)
        // A version 1 file saying nothing of the format played the total.
        assertEquals(MatchFormat.MIRRORED, LeagueConfig.decode("config 1\ndivisions 2\n").matchFormat)
        val moved = LeagueConfig.migrated(old)
        assertEquals(MatchFormat.GAMES, moved.matchFormat)
        assertTrue(moved.seriesKnockouts && moved.doubleElimination)
        assertEquals(3, moved.divisions)
        // Single games stay single.
        assertEquals(MatchFormat.SINGLE, LeagueConfig.migrated("config 1\nformat SINGLE\n").matchFormat)
        // Version 2 reads back as written, defaults the new rules.
        val now = LeagueConfig()
        assertEquals(MatchFormat.GAMES, now.matchFormat)
        assertEquals(now, LeagueConfig.decode(now.encode()))
        assertEquals(now, LeagueConfig.migrated(now.encode()))
        val off = now.copy(matchFormat = MatchFormat.MIRRORED, seriesKnockouts = false, doubleElimination = false)
        assertEquals(off, LeagueConfig.decode(off.encode()))
    }

    private fun entries(n: Int) = (1..n).map { DrawEntry("ai%02d".format(it), it, 1700.0 - 20 * it) }

    /** Plays [k] to the end, the side [pick] chooses winning each tie. */
    private fun drive(k: Knockout, pick: (KnockoutTie) -> Boolean = { t -> t.a!!.seed < t.b!!.seed }): List<KnockoutTie> {
        val played = ArrayList<KnockoutTie>()
        while (true) {
            val f = k.next(1.0, emptySet()) { t -> Fixture(Comp.CUP, t.id, t.a!!.key, t.b!!.key, 0L, JobKind.TIE, legs = t.legs, firstTo = t.firstTo) } ?: break
            val t = k.tie(f.id)!!
            val aWins = pick(t)
            val s = if (aWins) TieScore(3.0, 1.0, 800, 700, 1) else TieScore(1.0, 3.0, 700, 800, -1)
            assertNotNull(k.record(f.id, f.a, f.b, s))
            played += t
        }
        assertTrue(k.finished, "the bracket ends")
        return played
    }

    @Test
    fun aDoubleEliminationTakesTwoLossesToGoOut() {
        for (n in listOf(4, 6, 8, 13, 16)) {
            val size = Knockout.size(n)
            val k = Knockout(
                Knockout.pairings(entries(n)), { 1 }, { Cup.label(size, it) }, { 0.0 },
                winsOf = { r -> KnockoutLength.wins(size shr r, true, grandAfter = true) }, double = true,
                losersWins = { KnockoutLength.losersWins(it, true) }, grandWins = KnockoutLength.grandWins(true),
            )
            assertEquals(2 * Integer.numberOfTrailingZeros(size), k.stageCount, "stages for $n")
            assertEquals(2 * (Integer.numberOfTrailingZeros(size)) - 2, k.losers.size)
            val rng = Random(n.toLong())
            val played = drive(k) { rng.nextBoolean() }
            // Every entry but the champion loses exactly twice, or once in the grand final.
            val losses = HashMap<String, Int>()
            for (t in played) losses.merge(t.loser!!.key, 1, Int::plus)
            val champion = assertNotNull(k.champion).key
            assertNull(losses[champion]?.takeIf { it > 1 }, "the champion lost twice ($n)")
            for (e in entries(n)) if (e.key != champion) {
                val l = losses[e.key] ?: 0
                assertTrue(l == 2 || (l == 1 && e.key == k.runnerUp!!.key && (losses[champion] ?: 0) <= 1), "${e.key} lost $l times ($n)")
            }
            // n - 1 eliminations need 2n - 2 losses, the final's winner may have one: 2n - 2 or 2n - 1 ties.
            assertTrue(played.size in (2 * n - 2)..(2 * n - 1), "$n entries: ${played.size} ties")
            assertEquals(Knockout.GRAND_LABEL, k.label(k.final))
            assertEquals(BracketSide.GRAND, k.final.side)
            // Places: champion 0, runner-up 1, the last four 2.
            val places = k.places()
            assertEquals(0, places[champion])
            assertEquals(1, places[k.runnerUp!!.key])
            assertEquals(2, k.lastFour().size)
            for (e in k.lastFour()) assertEquals(2, places[e.key])
            // Series: winners' early rounds first to 2, the winners' final 3, the losers' final 3, the grand final 4.
            assertEquals(4, k.final.firstTo)
            assertEquals(3, k.losers.last().single().firstTo)
            assertEquals(3, k.rounds.last().single().firstTo)
            assertTrue(k.losers.dropLast(1).all { r -> r.all { it.firstTo == 2 } })
        }
    }

    @Test
    fun aLosersTieFedByTwoByesShowsAsABye() {
        // 20 entries in 32 slots: 12 byes, so some neighbouring first-round ties are both byes and
        // the losers' tie they feed has no one in it. The screen must not wait for it.
        val k = Knockout(Knockout.pairings(entries(20)), { 1 }, { Cup.label(32, it) }, { 0.0 }, double = true)
        val empty = k.view().filter { it.side == BracketSide.LOSERS }.flatMap { it.ties }.filter { it.a == null && it.b == null && it.bye }
        assertEquals(4, empty.size, "empty losers' ties")
        assertTrue(empty.all { it.winner == null && !it.played })
        drive(k)
        for (round in k.view()) for (t in round.ties) assertTrue(t.played || t.bye, "${round.label}: a tie neither played nor a bye")
        // A bye with a side still names it as the winner.
        assertTrue(k.view().first().ties.any { it.bye && it.a != null && it.winner == it.a })
    }

    @Test
    fun tiesAreHandedOutInPlayOrderAndReadBackInIdOrder() {
        val k = Knockout(Knockout.pairings(entries(8)), { 1 }, { Cup.label(8, it) }, { st -> 0.1 * st }, double = true)
        // At progress 0 only the first stage (the winners' first round) is open.
        val first = k.next(0.0, emptySet()) { t -> Fixture(Comp.CUP, t.id, t.a!!.key, t.b!!.key, 0L) }!!
        assertEquals(0, k.tie(first.id)!!.stage)
        k.release(first.id)
        val played = drive(k)
        assertEquals(played.map { it.id }.sorted(), played.map { it.id }, "ids follow the play order")
        assertTrue(played.zipWithNext().all { (x, y) -> x.stage <= y.stage })
        // The seeds win: the top seed takes it unbeaten, the second seed is the runner-up.
        assertEquals("ai01", k.champion!!.key)
        assertEquals("ai02", k.runnerUp!!.key)
        // Saved and read back, the same bracket.
        val again = Knockout(Knockout.pairings(entries(8)), { 1 }, { Cup.label(8, it) }, { st -> 0.1 * st }, double = true)
        val text = StringBuilder().also { k.encode(it) }.toString()
        again.restore(TieScore.Saved.parse(text.lines()))
        assertTrue(again.finished)
        assertEquals(k.view(), again.view())
    }

    @Test
    fun aSeriesStopsOnceASideHasItsWinsAndDealsAsTheLegsDo() {
        val tables = SolverTest.tables
        val roster = LeagueRoster.build(tables, LeagueTables(tables.rules)).associateBy { it.key }
        val a = roster.getValue("ev")
        val b = roster.getValue("slip20")
        for (seed in 1L..6L) {
            val r = LeagueMatch.playTie(tables.rules, a, b, seed, legs = KnockoutLength.pairs(3), firstTo = 3)
            var wa = 0.0
            var wb = 0.0
            for ((i, x) in r.games.withIndex()) {
                assertFalse((wa >= 3 || wb >= 3) && wa != wb, "a game after the series was won (seed $seed, game $i)")
                if (x.a > x.b) wa += 1.0 else if (x.a < x.b) wb += 1.0 else { wa += 0.5; wb += 0.5 }
            }
            assertTrue(maxOf(wa, wb) >= 3 || r.games.size == 2 * KnockoutLength.pairs(3), "seed $seed: $wa-$wb")
            // Each game again from its deal, as a highlight replays it.
            val job = Fixture(Comp.CUP, 1, a.key, b.key, seed, JobKind.TIE, legs = KnockoutLength.pairs(3), firstTo = 3)
            for (i in r.games.indices) {
                val again = LeagueMatch.playGame(tables.rules, LeagueMatch.deal(job, 0, i, 2), a, b).score
                assertEquals(r.games[i].a to r.games[i].b, again.a to again.b, "seed $seed game $i")
            }
        }
    }

    @Test
    fun aNewCupIsDrawnWithTheNewRulesAndAnOldOneKeepsItsOwn() {
        val keys = (1..12).map { "ai%02d".format(it) }
        fun start(config: LeagueConfig) = SeasonStart(1, 9L, config, keys, { 1700.0 - 20 * keys.indexOf(it) }, Season.split(keys, 2), emptyList(), emptyList(), { null }, { "서울" }, emptyList())
        val cup = assertNotNull(Cup.create(start(LeagueConfig())))
        val text = StringBuilder().also { cup.encode(it) }.toString()
        assertTrue(KnockoutLength.SERIES_LINE in text && KnockoutLength.DOUBLE_LINE in text)
        assertTrue(cup.stages().size == 8, "four winners' rounds, eight stages: ${cup.stages().map { it.label }}")
        val old = assertNotNull(Cup.create(start(LeagueConfig(seriesKnockouts = false, doubleElimination = false))))
        val oldText = StringBuilder().also { old.encode(it) }.toString()
        assertFalse(KnockoutLength.SERIES_LINE in oldText || KnockoutLength.DOUBLE_LINE in oldText)
        assertEquals(4, old.stages().size)
        // Read back: each keeps its rules.
        val back = Cup.codec.decode(text.lines().filter { it.isNotEmpty() }, 1, 9L)
        assertEquals(8, back.stages().size)
        val oldBack = Cup.codec.decode(oldText.lines().filter { it.isNotEmpty() }, 1, 9L)
        assertEquals(4, oldBack.stages().size)
        val f = assertNotNull(back.next(1.0, emptySet()))
        assertEquals(2, f.firstTo)
        assertEquals(0, assertNotNull(oldBack.next(1.0, emptySet())).firstTo)
        // A JobResult carries the format a job was played in.
        val job = Fixture(Comp.LEAGUE, 1, "ai01", "ai02", 3L)
        val gs = listOf(g(200, 180), g(170, 190))
        assertEquals(MatchFormat.GAMES, JobResult(job, listOf(BoardResult("ai01", "ai02", gs, emptyList())), 0, 0, MatchFormat.GAMES).format)
    }
}

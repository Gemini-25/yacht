package yacht.engine

import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomAis
import yacht.engine.league.CustomBase
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import yacht.engine.league.Origin
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 상대 맞춤: the opponent read from its record instead of a max-expectation player. */
class OppModelTest {
    private val tables get() = SolverTest.tables

    private fun moments(c: WinCurve.Curve): Pair<Double, Double> {
        val p = c.ties
        val m = p.indices.sumOf { it * p[it] }
        return m to sqrt(p.indices.sumOf { (it - m) * (it - m) * p[it] })
    }

    private fun board(rng: Random, filled: Int): Board {
        var b = Board()
        for (c in Category.ALL.shuffled(rng).take(filled)) b = b.with(c, tables.scores[Match.throwDice(rng, Dice.EMPTY_KEEP), c.ordinal])
        return b
    }

    @Test
    fun aRecordMakesAModelOnlyWhenItSaysSomething() {
        val (mean, variance) = OppModel.root(tables)
        assertEquals(191.8, mean, 0.1)
        assertEquals(37.9, sqrt(variance), 0.1)
        // Too few games, or a max-expectation player's record: no model (the plain win AI).
        assertNull(OppModel.of(tables, OppModel.MIN_GAMES - 1, 150.0, 30.0))
        assertNull(OppModel.of(tables, 500, mean + 0.1, sqrt(variance)))
        // 30 games are weighed as much as the prior: half the difference, in half points.
        val weak = assertNotNull(OppModel.of(tables, 30, mean - 40, Double.NaN))
        assertEquals(-20.0, weak.shift)
        assertEquals(1.0, weak.spread)
        // Many games: nearly the whole difference; the spread as a ratio, in 2% steps.
        val wild = assertNotNull(OppModel.of(tables, 3000, mean - 10, 1.5 * sqrt(variance)))
        assertEquals(-10.0, wild.shift, 0.5)
        assertEquals(1.5, wild.spread, 0.02)
        assertEquals(wild.spread, Math.round(wild.spread * 50) / 50.0)
    }

    @Test
    fun theOpponentsFinalMovesByTheShareOfTheGameLeft() {
        val start = Board()
        val plain = WinCurve.against(tables, start)
        val (m0, sd0) = moments(plain)
        // Whole game: the mean by the full shift, the spread by the ratio.
        val (m1, sd1) = moments(WinCurve.against(tables, start, model = OppModel(-20.0, 1.0)))
        assertEquals(m0 - 20, m1, 0.05)
        assertEquals(sd0, sd1, 0.05)
        val (_, sd2) = moments(WinCurve.against(tables, start, model = OppModel(0.0, 1.5)))
        assertEquals(1.5 * sd0, sd2, 0.3)
        // Nothing to say: the same curve.
        val same = WinCurve.against(tables, start, model = OppModel(0.0, 1.0))
        for (f in plain.values.indices) assertEquals(plain.values[f], same.values[f], 1e-9)
        // Halfway (the upper boxes filled): the shift by the share of the game's points still to come.
        val half = Category.ALL.filter { it.isUpper }.fold(Board()) { b, c -> b.with(c, 3 * (c.ordinal + 1)) }
        val (mh, _) = moments(WinCurve.against(tables, half))
        val (mh2, _) = moments(WinCurve.against(tables, half, model = OppModel(-20.0, 1.0)))
        val share = tables.ev[half.mask, half.u] / OppModel.root(tables).first
        assertEquals(-20 * share, mh2 - mh, 0.1)
        // At the very end (only the Yacht: nothing or 50) a weaker player has the same chances:
        // nothing below zero, so barely any change.
        val late = Category.ALL.filter { it != Category.YACHT }.fold(Board()) { b, c -> b.with(c, 10) }
        val (ml, _) = moments(WinCurve.against(tables, late))
        val (ml2, _) = moments(WinCurve.against(tables, late, model = OppModel(-20.0, 1.0)))
        assertTrue(ml2 <= ml && ml - ml2 <= 20 * 2.3 / 191.8 + 1e-9, "$ml -> $ml2")
        // A finished opponent is its total, whatever its record.
        val done = Category.ALL.fold(Board()) { b, c -> b.with(c, 10) }
        val fin = WinCurve.against(tables, done, model = OppModel(-30.0, 2.0))
        assertEquals(done.total to done.total, fin.oppMin to fin.oppMax)
    }

    @Test
    fun reshapedDistributionsStayPossibleAndTheSearchStaysExact() {
        val rng = Random(5)
        repeat(40) { i ->
            val opp = board(rng, i % 11)
            val model = OppModel(if (i % 2 == 0) -25.0 else 15.0, listOf(0.6, 1.0, 1.4)[i % 3])
            val c = WinCurve.against(tables, opp, model = model)
            // A distribution of possible finals: summing to one, within what can still be scored.
            assertEquals(1.0, c.ties.sum(), 1e-9)
            assertTrue(c.oppMin >= opp.total && c.oppMax <= opp.total + OppModel.maxRemaining(tables, opp), "$i: ${c.oppMin}..${c.oppMax}")
            assertTrue(c.ties.indices.all { c.ties[it] == 0.0 || it in c.oppMin..c.oppMax })
        }
        // The closed-form shortcut of decided scores still matches a search without it.
        repeat(6) { i ->
            val me = board(rng, 7 + i % 4)
            val opp = board(rng, 7 + i % 4)
            val curve = WinCurve.against(tables, opp, model = OppModel(-18.0, 1.3))
            val full = WinCurve.Curve(curve.values, -10_000, 10_000)
            for (depth in listOf(1, Category.COUNT)) {
                val fast = TurnPlan(me, WinSearch(tables, me, curve, depth)).startValue
                val slow = TurnPlan(me, WinSearch(tables, me, full, depth)).startValue
                assertTrue(abs(fast - slow) < 1e-6, "depth $depth: $fast vs $slow")
            }
        }
    }

    @Test
    fun theAdaptiveAiPlaysAsTheWinAiWithoutARecordAndOtherwiseWithOne() {
        val roster = LeagueRoster.build(tables, LeagueTables(tables.rules))
        val win = roster.first { it.key == "win" }
        val adapt = roster.first { it.key == LeagueRoster.ADAPTIVE }
        val greedy = roster.first { it.key == "greedy" }
        assertTrue(adapt.adapts && adapt.heavy)
        assertEquals(Origin.GUEST, Origin.of(adapt.key))
        // No record: the very games of the app's win AI.
        for (seed in 1L..3L) {
            val a = LeagueMatch.playMatch(tables.rules, adapt, greedy, seed).games
            val w = LeagueMatch.playMatch(tables.rules, win, greedy, seed).games
            assertEquals(w.map { it.a to it.b }, a.map { it.a to it.b }, "seed $seed")
        }
        // A record of a much weaker opponent: it plays (somewhere) otherwise, the deals the same.
        val weak = OppModel(-45.0, 1.2)
        var differs = false
        for (seed in 1L..12L) {
            val a = LeagueMatch.playMatch(tables.rules, adapt, greedy, seed, scout = { if (it == greedy.key) weak else null }).games
            val w = LeagueMatch.playMatch(tables.rules, win, greedy, seed).games
            assertEquals(w.map { it.b }, a.map { it.b }, "the opponent's own play does not change (seed $seed)")
            if (w.map { it.a } != a.map { it.a }) differs = true
        }
        assertTrue(differs, "a model that never changes a move")
    }

    @Test
    fun theRecordIsThisSeasonsOnceItHasEnoughElseTheCareers() {
        val w = yacht.engine.league.LeagueWorld(yacht.engine.league.Ladder(listOf("a", "b")), yacht.engine.league.LeagueConfig())
        fun job(score: Int, comp: yacht.engine.league.Comp): yacht.engine.league.JobResult {
            val g = listOf(yacht.engine.league.GameScore(score, 190), yacht.engine.league.GameScore(score, 190))
            return yacht.engine.league.JobResult(
                yacht.engine.league.Fixture(comp, 0, "a", "b", 0L),
                listOf(yacht.engine.league.BoardResult("a", "b", g, emptyList())), yacht.engine.league.TieRule.match(g), 0,
            )
        }
        val ctx = yacht.engine.league.StatsContext(1, { 1500.0 })
        assertNull(w.oppModel(tables, "a"), "no record yet")
        // Friendlies count in the career only: a weak player by its career.
        repeat(20) { w.stats.record(job(140, yacht.engine.league.Comp.FRIENDLY), ctx) }
        assertTrue(assertNotNull(w.oppModel(tables, "a")).shift < -20)
        // This season's official games, once enough: a strong one now.
        repeat(20) { w.stats.record(job(230, yacht.engine.league.Comp.LEAGUE), ctx) }
        assertTrue(assertNotNull(w.oppModel(tables, "a")).shift > 20)
    }

    @Test
    fun myOwnAiCanAdaptToo() {
        val spec = CustomAiSpec("a1", "맞춤이", CustomBase.WIN, adapt = true)
        assertTrue(spec.adapts)
        assertEquals("승률 계산 · 상대 맞춤 · 실수 없음", spec.describe())
        assertEquals(spec, CustomAiSpec.decode(spec.encode()))
        assertFalse(spec.copy(base = CustomBase.EV).adapts)
        // A line of the plain kind reads as before (no field added).
        val plain = spec.copy(adapt = false)
        assertEquals(12, plain.encode().split('|').size)
        val ai = CustomAis.build(spec, tables, LeagueTables(tables.rules))
        assertTrue(ai.adapts && ai.heavy)
        assertFalse(CustomAis.build(plain, tables, LeagueTables(tables.rules)).adapts)
        assertNotEquals(spec.encode(), plain.encode())
    }
}

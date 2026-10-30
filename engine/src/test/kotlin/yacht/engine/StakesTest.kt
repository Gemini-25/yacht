package yacht.engine

import yacht.engine.league.GameScore
import yacht.engine.league.TieRule
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 경기 승리: a game played for the match (the total over its two games) instead of alone. */
class StakesTest {
    private val tables get() = SolverTest.tables

    @Test
    fun aTwoGameLeagueMatchIsWonOnTheTotal() {
        // More games won, else the higher total, else a draw: the same as the total, every way
        // two games can go (a drawn game included).
        for (a1 in 0..4) for (b1 in 0..4) for (a2 in 0..4) for (b2 in 0..4) {
            val r = TieRule.match(listOf(GameScore(a1, b1), GameScore(a2, b2)))
            assertEquals(Integer.signum(a1 + a2 - b1 - b2), r, "$a1-$b1, $a2-$b2")
        }
    }

    @Test
    fun theLastGameIsTheGameWithTheLeadCarried() {
        val rng = java.util.Random(3)
        for (i in 0 until 20) {
            var opp = Board()
            for (c in Category.ALL.shuffled(rng).take(i % 12)) opp = opp.with(c, tables.scores[Match.throwDice(rng, Dice.EMPTY_KEEP), c.ordinal])
            val carry = listOf(-40, -7, 0, 12, 35)[i % 5]
            val a = WinCurve.against(tables, opp, stakes = Stakes.last(carry, 0.5))
            val b = WinCurve.against(tables, opp, 0.5, oppShift = -carry)
            for (f in a.values.indices) assertEquals(b.values[f], a.values[f], 1e-12, "$i at $f")
            assertEquals(b.oppMin to b.oppMax, a.oppMin to a.oppMax)
        }
    }

    @Test
    fun aLeadBeyondWhatTheOpponentCanScoreIsAWinFromAnyFinal() {
        // Ahead by 250 with the opponent's game just begun: it scores below 250 nearly always.
        val c = WinCurve.against(tables, Board(), stakes = Stakes.last(250, 0.5))
        val dist = tables.dist!!
        val p = dist.pmf(0, 0)
        val lo = dist.lo(0, 0)
        val below = p.indices.filter { lo + it < 250 }.sumOf { p[it] } / p.sum()
        val level = p.indices.filter { lo + it == 250 }.sumOf { p[it] } / p.sum()
        assertEquals(below + 0.5 * level, c.values[0], 1e-9)
        assertTrue(c.oppMin < 0)
        // Behind by 400: no final I can reach wins (the most is far below 400).
        val lost = WinCurve.against(tables, Board(), stakes = Stakes.last(-400, 0.5))
        assertTrue((0..400).all { lost.values[it] - WinCurve.SCORE_WEIGHT * it < 1e-12 })
        // A finished opponent is still a margin to beat, carried or not.
        val done = Category.ALL.fold(Board()) { b, cat -> b.with(cat, 10) }
        val fin = WinCurve.against(tables, done, stakes = Stakes.last(-15, 1.0 / 3))
        assertEquals(done.total + 15, fin.oppMin)
        assertEquals(done.total + 15, fin.oppMax)
        assertEquals(1.0 / 3, fin.values[done.total + 15] - WinCurve.SCORE_WEIGHT * (done.total + 15), 1e-12)
    }

    @Test
    fun theFirstGameReadsTheSecondAsTwoMaxExpectationPlayers() {
        val (rest, restLo) = Stakes.oneGame(tables)
        assertEquals(1.0, rest.sum(), 1e-9)
        val m = rest.indices.sumOf { (restLo + it) * rest[it] }
        val sd = sqrt(rest.indices.sumOf { (restLo + it - m) * (restLo + it - m) * rest[it] })
        assertEquals(0.0, m, 1e-6)
        assertEquals(sqrt(2.0) * sqrt(OppModel.root(tables).second), sd, 0.05)
        // Symmetric: a margin of d as likely as -d.
        for (i in rest.indices) assertEquals(rest[i], rest[rest.size - 1 - i], 1e-12)
        assertEquals(-(restLo + rest.size - 1), restLo)
        // The worth of a first-game margin d: P(d + r > 0) + tie P(d + r = 0), rising from 0 to 1.
        val s = Stakes.firstOfTwo(tables, 0.5)
        val worth = { d: Int -> if (d < s.lo) 0.0 else if (d >= s.lo + s.value.size) 1.0 else s.value[d - s.lo] }
        for (d in listOf(-100, -30, -1, 0, 1, 30, 100)) {
            val direct = rest.indices.sumOf { i -> val r = restLo + i; rest[i] * (if (d + r > 0) 1.0 else if (d + r == 0) 0.5 else 0.0) }
            assertEquals(direct, worth(d), 1e-12, "margin $d")
        }
        assertEquals(0.5, worth(0), 1e-9)
        assertTrue((s.lo until s.lo + s.value.size - 1).all { worth(it) <= worth(it + 1) })
        // The curve over my first-game final: the match's worth, smooth (no step at the
        // opponent's final), half of it at a level first game.
        val done = Category.ALL.fold(Board()) { b, cat -> b.with(cat, 15) }
        val c = WinCurve.against(tables, done, stakes = s)
        assertEquals(0.5, c.values[done.total] - WinCurve.SCORE_WEIGHT * done.total, 1e-9)
        assertTrue(c.values[done.total + 20] in 0.6..0.7 && c.values[done.total - 20] in 0.3..0.4, "${c.values[done.total + 20]}")
        // Against an opponent still playing: the worth of the margin over its finals.
        val open = WinCurve.against(tables, Board(), stakes = s)
        val dist = tables.dist!!
        val p = dist.pmf(0, 0)
        val plo = dist.lo(0, 0)
        val f = 200
        val direct = p.indices.sumOf { p[it] * worth(f - plo - it) } / p.sum()
        assertEquals(direct, open.values[f] - WinCurve.SCORE_WEIGHT * f, 1e-9)
    }

    @Test
    fun theLastBoxIsWorthWhatTheMatchNeedsFromIt() {
        // Only the Yacht left, level with a finished opponent: alone, the game is won by a Yacht
        // only; one point ahead from the first game, the match is won whatever comes; fifty
        // behind, a Yacht only levels it (a draw, worth a third).
        val me = Category.ALL.filter { it != Category.YACHT }.fold(Board()) { b, c -> b.with(c, 5) }
        val level = Category.ALL.filter { it != Category.YACHT }.fold(Board()) { b, c -> b.with(c, 5) }.with(Category.YACHT, 0)
        assertEquals(me.total, level.total)
        val yacht = tables.ev[me.mask, me.u] / 50
        assertEquals(0.046, yacht, 0.001)
        val advisor = Advisor(tables)
        fun value(stakes: Stakes?) = advisor.planTurn(me, level, Mode.WIN, depth = 1, stakes = stakes).startValue
        assertEquals(yacht, value(null), 1e-3)
        assertEquals(1.0, value(Stakes.last(1, 1.0 / 3)), 1e-3)
        assertEquals(yacht / 3, value(Stakes.last(-50, 1.0 / 3)), 1e-3)
        assertEquals(0.0, value(Stakes.last(-51, 1.0 / 3)), 1e-3)
        // A game alone with its tie valued: the same as the plain curve with that tie value.
        assertEquals(advisor.planTurn(me, level, Mode.WIN, depth = 1, tieValue = 0.5).startValue, value(Stakes.alone(0.5)), 1e-12)
    }
}

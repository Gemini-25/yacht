package yacht.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolverTest {
    companion object {
        val tables: Tables by lazy {
            val b = TableBuilder(RuleSet.NINTENDO).build(withDist = true)
            Tables(RuleSet.NINTENDO, b.evTable(), b.distTable())
        }
    }

    @Test
    fun optimalExpectedScoreMatchesPrototype() {
        // prototype/ev_solver.py --fh-yacht
        assertEquals(191.7744, tables.ev[0, 0], 5e-4)
    }

    @Test
    fun ruleVariantsMatchPrototype() {
        val noYachtFh = TableBuilder(RuleSet(fullHouseAcceptsYacht = false)).build(withDist = false)
        assertEquals(191.7609, noYachtFh.ev[0], 5e-4)
    }

    @Test
    fun distributionMeansEqualExpectedValues() {
        val dist = tables.dist!!
        for ((mask, u) in listOf(0 to 0, 0b101010101010 to 20, 0b000000111111 to 63, 0b011111111110 to 0)) {
            val p = dist.pmf(mask, u)
            // Stored as half floats: allow small rounding error.
            assertEquals(1.0, p.sum(), 2e-3)
            assertEquals(tables.ev[mask, u], dist.mean(mask, u), 0.1)
        }
    }

    @Test
    fun yachtOnLastTurnIsKnownProbability() {
        // Only Yacht left, 10 points behind a finished opponent: must roll a Yacht.
        var me = Board()
        for (c in Category.ALL) if (c != Category.YACHT) me = me.with(c, 0)
        var opp = Board()
        for (c in Category.ALL) opp = opp.with(c, if (c == Category.CHOICE) 10 else 0)
        val turn = Advisor(tables).planTurn(me, opp)
        assertEquals(Mode.WIN, turn.mode)
        // Includes the tiny expected-score tie-break term.
        assertEquals(0.0460286, turn.startValue, 1e-5)
    }

    @Test
    fun exactEndgameNeverWorseThanOneStep() {
        val rng = java.util.Random(7)
        repeat(20) {
            val (me, opp) = randomBoards(rng, filled = 9)
            val advisor = Advisor(tables)
            val shallow = advisor.planTurn(me, opp, depth = 1).startValue
            val exact = advisor.planTurn(me, opp, depth = 3).startValue
            assertTrue(exact >= shallow - 1e-9, "exact $exact < shallow $shallow")
        }
    }

    @Test
    fun winProbabilityWithoutOpponentProgressIsSane() {
        val advisor = Advisor(tables)
        val start = advisor.planTurn(Board(), Board(), depth = 1).startValue
        // Symmetric start, opponent assumed max-expectation; ties count as losses.
        // EV vs EV wins (1 - P(tie)) / 2 ~ 0.4963; one improved turn can only add a little.
        assertTrue(start in 0.495..0.51, "start win prob $start")
    }

    private fun randomBoards(rng: java.util.Random, filled: Int): Pair<Board, Board> {
        fun board(): Board {
            var b = Board()
            val cats = Category.ALL.shuffled(rng).take(filled)
            for (c in cats) {
                val r = Match.throwDice(rng, Dice.EMPTY_KEEP)
                b = b.with(c, tables.scores[r, c.ordinal])
            }
            return b
        }
        return board() to board()
    }
}

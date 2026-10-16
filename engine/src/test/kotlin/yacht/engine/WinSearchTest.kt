package yacht.engine

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the exact end-game search against playing its own policy many times. */
class WinSearchTest {
    private val tables get() = SolverTest.tables

    private fun board(vararg filled: Pair<Category, Int>): Board =
        filled.fold(Board()) { b, (c, p) -> b.with(c, p) }

    /** Plays the remaining turns re-planning each turn (as the app does); returns P(win). */
    private fun simulate(me: Board, opp: Board, games: Int, seed: Long): Double {
        val advisor = Advisor(tables)
        val rng = java.util.Random(seed)
        val plans = HashMap<Board, PlannedTurn>()
        var wins = 0
        repeat(games) {
            var b = me
            while (!b.isComplete) {
                val turn = plans.getOrPut(b) { advisor.planTurn(b, opp, depth = Category.COUNT) }
                var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
                for (rolls in 1..2) {
                    val k = turn.bestKeep(rolls, r)
                    if (k == Dice.rollAsKeep[r]) break
                    r = Match.throwDice(rng, k)
                }
                val c = turn.bestCategory(r)
                b = b.with(c, tables.scores[r, c.ordinal])
            }
            if (b.total > opp.total) wins++
        }
        return wins.toDouble() / games
    }

    private fun check(me: Board, opp: Board) {
        val exact = Advisor(tables).planTurn(me, opp, depth = Category.COUNT).startValue
        val n = 200_000
        val sim = simulate(me, opp, n, seed = 42)
        val sd = sqrt(sim * (1 - sim) / n)
        // startValue includes the tiny expected-score tie-break term (< 4e-4).
        println("exact $exact vs simulated $sim (sd $sd)")
        assertTrue(kotlin.math.abs(exact - sim) < 4 * sd + 4e-4, "exact $exact vs simulated $sim (sd $sd)")
    }

    @Test
    fun bonusRaceWithTwoBoxesLeft() {
        // Upper sum 60 with Aces open: three aces earn the 35 bonus. Yacht also open.
        val me = board(
            Category.DEUCES to 8, Category.THREES to 12, Category.FOURS to 12, Category.FIVES to 10,
            Category.SIXES to 18, Category.CHOICE to 22, Category.FOUR_OF_A_KIND to 0,
            Category.FULL_HOUSE to 24, Category.SMALL_STRAIGHT to 15, Category.LARGE_STRAIGHT to 30,
        )
        val opp = Category.ALL.fold(Board()) { b, c -> b.with(c, if (c == Category.CHOICE) 190 else 0) }
        check(me, opp)
    }

    @Test
    fun threeBoxesLeftAgainstUnfinishedOpponent() {
        val me = board(
            Category.ACES to 3, Category.DEUCES to 6, Category.THREES to 9, Category.FOURS to 12,
            Category.FIVES to 15, Category.SIXES to 18, Category.CHOICE to 23, Category.FOUR_OF_A_KIND to 20,
            Category.SMALL_STRAIGHT to 15,
        )
        // Opponent still has Yacht left (scored with the max-expectation policy, as assumed).
        val opp = Category.ALL.filter { it != Category.YACHT }
            .fold(Board()) { b, c -> b.with(c, if (c == Category.CHOICE) 200 else 0) }
        val exact = Advisor(tables).planTurn(me, opp, depth = Category.COUNT).startValue
        val n = 200_000
        val advisor = Advisor(tables)
        val rng = java.util.Random(9)
        val plans = HashMap<Board, PlannedTurn>()
        val oppPlan = advisor.planTurn(opp, me, Mode.EXPECTED_SCORE)
        var wins = 0
        repeat(n) {
            // Opponent's last turn with the max-expectation policy, independent of my play.
            var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
            for (rolls in 1..2) {
                val k = oppPlan.bestKeep(rolls, r)
                if (k == Dice.rollAsKeep[r]) break
                r = Match.throwDice(rng, k)
            }
            val oppFinal = opp.total + tables.scores[r, Category.YACHT.ordinal]
            var b = me
            while (!b.isComplete) {
                val turn = plans.getOrPut(b) { advisor.planTurn(b, opp, depth = Category.COUNT) }
                var x = Match.throwDice(rng, Dice.EMPTY_KEEP)
                for (rolls in 1..2) {
                    val k = turn.bestKeep(rolls, x)
                    if (k == Dice.rollAsKeep[x]) break
                    x = Match.throwDice(rng, k)
                }
                val c = turn.bestCategory(x)
                b = b.with(c, tables.scores[x, c.ordinal])
            }
            if (b.total > oppFinal) wins++
        }
        val sim = wins.toDouble() / n
        val sd = sqrt(sim * (1 - sim) / n)
        println("exact $exact vs simulated $sim (sd $sd)")
        assertTrue(kotlin.math.abs(exact - sim) < 4 * sd + 4e-4, "exact $exact vs simulated $sim (sd $sd)")
    }
}

class DuelSearchTest {
    private val tables get() = SolverTest.tables

    /** Plays my remaining turns (re-planning with the duel search) against the max-expectation opponent. */
    private fun simulate(me0: Board, opp0: Board, meFirst: Boolean, games: Int, seed: Long): Double {
        val advisor = Advisor(tables)
        val rng = java.util.Random(seed)
        val plans = HashMap<Pair<Board, Board>, PlannedTurn>()
        val oppPlans = HashMap<Board, PlannedTurn>()
        fun play(turn: PlannedTurn, b: Board): Board {
            var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
            for (rolls in 1..2) {
                val k = turn.bestKeep(rolls, r)
                if (k == Dice.rollAsKeep[r]) break
                r = Match.throwDice(rng, k)
            }
            val c = turn.bestCategory(r)
            return b.with(c, tables.scores[r, c.ordinal])
        }
        var wins = 0
        repeat(games) {
            var me = me0
            var opp = opp0
            var myMove = true
            if (!meFirst) check(Category.COUNT - opp.filledCount == Category.COUNT - me.filledCount - 1)
            while (!me.isComplete || !opp.isComplete) {
                if (myMove && !me.isComplete) {
                    val p = plans.getOrPut(me to opp) { advisor.planTurn(me, opp, duel = true) }
                    me = play(p, me)
                } else if (!myMove && !opp.isComplete) {
                    val p = oppPlans.getOrPut(opp) { advisor.planTurn(opp, null, Mode.EXPECTED_SCORE) }
                    opp = play(p, opp)
                }
                myMove = !myMove
            }
            if (me.total > opp.total) wins++
        }
        return wins.toDouble() / games
    }

    private fun boards(): Pair<Board, Board> {
        // Two boxes left each: Yacht and Choice for me, Large Straight and Sixes for the opponent.
        var me = Board()
        for (c in Category.ALL) if (c != Category.YACHT && c != Category.CHOICE) me = me.with(c, if (c.isUpper) 3 * c.face else 0)
        var opp = Board()
        for (c in Category.ALL) if (c != Category.LARGE_STRAIGHT && c != Category.SIXES) opp = opp.with(c, if (c == Category.CHOICE) 100 else 0)
        return me to opp
    }

    @Test
    fun duelMatchesSimulationOfItsOwnPolicy() {
        val (me, opp) = boards()
        val turn = Advisor(tables).planTurn(me, opp, duel = true)
        assertTrue(turn.duel)
        val n = 100_000
        val sim = simulate(me, opp, meFirst = true, n, 3)
        val sd = sqrt(sim * (1 - sim) / n)
        println("duel exact ${turn.startValue} vs simulated $sim (sd $sd)")
        assertTrue(kotlin.math.abs(turn.startValue - sim) < 4 * sd + 1e-3, "duel ${turn.startValue} vs simulated $sim")
    }

    @Test
    fun duelIsAtLeastTheFixedOpponentSearch() {
        val (me, opp) = boards()
        val advisor = Advisor(tables)
        val fixed = advisor.planTurn(me, opp, depth = Category.COUNT).startValue
        val duel = advisor.planTurn(me, opp, duel = true).startValue
        println("fixed-opponent exact $fixed, duel $duel")
        assertTrue(duel >= fixed - 1e-3, "duel $duel < fixed $fixed")
    }
}

class WindowTest {
    private val tables get() = SolverTest.tables

    @Test
    fun decidedScoreShortcutDoesNotChangeValues() {
        val rng = java.util.Random(21)
        repeat(12) { i ->
            val filled = 7 + i % 4
            fun board(): Board {
                var b = Board()
                for (c in Category.ALL.shuffled(rng).take(filled)) b = b.with(c, tables.scores[Match.throwDice(rng, Dice.EMPTY_KEEP), c.ordinal])
                return b
            }
            val me = board()
            val opp = board()
            val curve = WinCurve.against(tables, opp)
            // Same curve but bounds that make every score look undecided: no shortcut.
            val full = WinCurve.Curve(curve.values, -10_000, 10_000)
            for (depth in listOf(1, 2, Category.COUNT)) {
                val fast = TurnPlan(me, WinSearch(tables, me, curve, depth)).startValue
                val slow = TurnPlan(me, WinSearch(tables, me, full, depth)).startValue
                // Decided leaves use the exact expected score; searched ones the stored (half
                // precision) distribution's mean, so only the 1e-6 tie-break term may differ.
                assertTrue(kotlin.math.abs(fast - slow) < 1e-8, "depth $depth: $fast vs $slow")
            }
        }
    }
}

class DuelTableReuseTest {
    private val tables get() = SolverTest.tables

    private fun play(advisor: Advisor, b: Board, opp: Board?, rng: java.util.Random): Board {
        val turn = advisor.planTurn(b, opp, if (opp == null) Mode.EXPECTED_SCORE else Mode.WIN)
        var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
        for (rolls in 1..2) {
            val k = turn.bestKeep(rolls, r)
            if (k == Dice.rollAsKeep[r]) break
            r = Match.throwDice(rng, k)
        }
        val c = turn.bestCategory(r)
        return b.with(c, tables.scores[r, c.ordinal])
    }

    private fun boards(rng: java.util.Random, mine: Int, theirs: Int): Pair<Board, Board> {
        fun board(n: Int): Board {
            var b = Board()
            for (c in Category.ALL.shuffled(rng).take(n)) b = b.with(c, tables.scores[Match.throwDice(rng, Dice.EMPTY_KEEP), c.ordinal])
            return b
        }
        return board(mine) to board(theirs)
    }

    @Test
    fun tableRootedAtMyTurnAnswersLaterTurns() {
        val rng = java.util.Random(5)
        val advisor = Advisor(tables)
        repeat(4) {
            var (me, opp) = boards(rng, 9, 9)
            val table = advisor.buildDuelTable(me, opp, meToMove = true)
            assertEquals(
                advisor.planTurn(me, opp, duel = true).startValue,
                advisor.planTurn(me, opp, table = table).startValue, 1e-12,
            )
            me = play(advisor, me, opp, rng)
            opp = play(advisor, opp, null, rng)
            val reused = advisor.planTurn(me, opp, table = table)
            assertTrue(reused.duel)
            val fresh = advisor.planTurn(me, opp, duel = true)
            assertEquals(fresh.startValue, reused.startValue, 1e-9)
        }
    }

    @Test
    fun tableRootedAtOpponentTurnAnswersMyNextTurn() {
        val rng = java.util.Random(6)
        val advisor = Advisor(tables)
        repeat(4) {
            // I just scored my 10th box; the opponent (9 boxes) is to move.
            var (me, opp) = boards(rng, 10, 9)
            val table = advisor.buildDuelTable(me, opp, meToMove = false)
            opp = play(advisor, opp, null, rng)
            val reused = advisor.planTurn(me, opp, table = table)
            assertTrue(reused.duel)
            val fresh = advisor.planTurn(me, opp, duel = true)
            assertEquals(fresh.startValue, reused.startValue, 1e-9)
            // A position not reachable from the root is refused.
            assertEquals(null, table.after(Board(), opp))
        }
    }
}

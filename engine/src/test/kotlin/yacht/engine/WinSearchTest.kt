package yacht.engine

import kotlin.math.sqrt
import kotlin.test.Test
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

package yacht.engine

import java.util.Random

/** A player that decides from roll indices. Plans are refreshed at the start of each turn. */
interface Player {
    val name: String
    fun startTurn(me: Board, opp: Board)
    fun chooseKeep(rollsDone: Int, r: Int): Int
    fun chooseCategory(r: Int): Category
}

class AdvisorPlayer(
    private val advisor: Advisor,
    private val mode: Mode,
    private val budgetOps: Long = Advisor.DEFAULT_BUDGET,
    private val fixedDepth: Int? = null,
    override val name: String = mode.name.lowercase(),
) : Player {
    private lateinit var turn: PlannedTurn

    override fun startTurn(me: Board, opp: Board) {
        turn = advisor.planTurn(me, opp, mode, budgetOps, depth = fixedDepth)
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = turn.bestKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = turn.bestCategory(r)
}

object Match {
    fun throwDice(rng: Random, keep: Int): Int {
        val counts = Dice.keepCounts[keep].copyOf()
        repeat(5 - Dice.keepSize[keep]) { counts[rng.nextInt(6)]++ }
        return Dice.rollIndex(counts)
    }

    /** Plays one game; [first] moves first. Returns final totals (first, second). */
    fun play(rules: RuleSet, first: Player, second: Player, rng: Random): IntArray {
        val boards = arrayOf(Board(rules), Board(rules))
        val players = arrayOf(first, second)
        val scores = ScoreTable.of(rules)
        repeat(Category.COUNT) {
            for (p in 0..1) {
                val me = boards[p]
                val pl = players[p]
                pl.startTurn(me, boards[1 - p])
                var r = throwDice(rng, Dice.EMPTY_KEEP)
                for (rollsDone in 1..2) {
                    val keep = pl.chooseKeep(rollsDone, r)
                    if (keep == Dice.rollAsKeep[r]) break
                    r = throwDice(rng, keep)
                }
                val c = pl.chooseCategory(r)
                boards[p] = me.with(c, scores[r, c.ordinal])
            }
        }
        return intArrayOf(boards[0].total, boards[1].total)
    }
}

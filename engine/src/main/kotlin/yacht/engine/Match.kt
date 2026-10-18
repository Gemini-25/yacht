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
    /** Use the exact duel search once its estimated cost is below this many operations. */
    private val duelOps: Long = 0,
) : Player {
    private lateinit var turn: PlannedTurn

    /** Duel table built once it fits [duelOps], then reused for the rest of the game (as the app does). */
    private var table: DuelSearch? = null

    override fun startTurn(me: Board, opp: Board) {
        if (me.filledCount == 0) table = null
        if (mode == Mode.WIN && duelOps > 0 && table?.after(me, opp) == null && advisor.canDuel(me, opp) &&
            DuelSearch.estimateOps(advisor.tables, me, opp, limit = duelOps, adversarial = advisor.adversarial) <= duelOps
        ) {
            table = advisor.buildDuelTable(me, opp, meToMove = true)
        }
        turn = advisor.planTurn(me, opp, mode, budgetOps, depth = fixedDepth, table = table)
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

/**
 * Dice fixed per (turn, throw, slot): rerolling n dice always takes slots 0..n-1 of that throw,
 * so two strategies given the same stream see the same luck wherever their choices agree.
 */
class DiceStream(private val seed: Long) {
    fun throwDice(keep: Int, turn: Int, throwNo: Int): Int {
        val counts = Dice.keepCounts[keep].copyOf()
        for (slot in 0 until 5 - Dice.keepSize[keep]) counts[face(turn, throwNo, slot)]++
        return Dice.rollIndex(counts)
    }

    private fun face(turn: Int, throwNo: Int, slot: Int): Int {
        var z = seed + 0x9E3779B97F4A7C15uL.toLong() * (1 + turn * 16 + throwNo * 5 + slot)
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 1) % 6).toInt()
    }

    companion object {
        fun mix(vararg parts: Long): Long = parts.fold(0x51ED270B27C8F0A3L) { h, p -> (h xor p) * -0x61c8864680b583ebL + 0x632BE59BD9B4E019L }
    }
}

object Duplicate {
    /** One player's whole game with [stream]; [visibleOpp] gives the opponent's card at each of my turns. */
    fun playTurns(
        rules: RuleSet,
        player: Player,
        stream: DiceStream,
        visibleOpp: (turn: Int) -> Board,
    ): Board {
        val scores = ScoreTable.of(rules)
        var me = Board(rules)
        for (turn in 0 until Category.COUNT) {
            player.startTurn(me, visibleOpp(turn))
            var r = stream.throwDice(Dice.EMPTY_KEEP, turn, 0)
            for (rollsDone in 1..2) {
                val keep = player.chooseKeep(rollsDone, r)
                if (keep == Dice.rollAsKeep[r]) break
                r = stream.throwDice(keep, turn, rollsDone)
            }
            val c = player.chooseCategory(r)
            me = me.with(c, scores[r, c.ordinal])
        }
        return me
    }

    /** Opponent cards after 0..12 turns of a player that ignores the other side (e.g. max-expectation). */
    fun independentGame(rules: RuleSet, player: Player, stream: DiceStream): List<Board> {
        val boards = ArrayList<Board>()
        boards += Board(rules)
        val empty = Board(rules)
        val scores = ScoreTable.of(rules)
        var b = empty
        for (turn in 0 until Category.COUNT) {
            player.startTurn(b, empty)
            var r = stream.throwDice(Dice.EMPTY_KEEP, turn, 0)
            for (rollsDone in 1..2) {
                val keep = player.chooseKeep(rollsDone, r)
                if (keep == Dice.rollAsKeep[r]) break
                r = stream.throwDice(keep, turn, rollsDone)
            }
            val c = player.chooseCategory(r)
            b = b.with(c, scores[r, c.ordinal])
            boards += b
        }
        return boards
    }
}

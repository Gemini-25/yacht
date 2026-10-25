package yacht.engine.league

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.DiceStream
import yacht.engine.Player
import yacht.engine.RuleSet
import java.util.Random
import java.util.concurrent.CancellationException

/**
 * A league match: two games on the same dice with the seats swapped, so the luck of the deal is
 * shared and only the decisions differ.
 */
object LeagueMatch {
    /**
     * Plays [a] against [b] on the deal [seed]; [a] moves first in the first game. Throws
     * [CancellationException] between turns once [cancelled] says so.
     */
    fun play(rules: RuleSet, a: LeagueAi, b: LeagueAi, seed: Long, cancelled: () -> Boolean = { false }): List<GameScore> {
        val first = DiceStream(DiceStream.mix(seed, 0))
        val second = DiceStream(DiceStream.mix(seed, 1))
        fun rng(game: Int, seat: Int) = Random(DiceStream.mix(seed, 7, game.toLong(), seat.toLong()))
        val (a1, b1) = game(rules, a.make(rng(0, 0)), b.make(rng(0, 1)), first, second, cancelled)
        val (b2, a2) = game(rules, b.make(rng(1, 0)), a.make(rng(1, 1)), first, second, cancelled)
        return listOf(score(a1, b1), score(a2, b2))
    }

    private fun score(a: Board, b: Board) = GameScore(
        a.total, b.total, (a[Category.YACHT] ?: 0) > 0, (b[Category.YACHT] ?: 0) > 0, a.bonus > 0, b.bonus > 0,
    )

    private fun game(rules: RuleSet, first: Player, second: Player, s0: DiceStream, s1: DiceStream, cancelled: () -> Boolean): Pair<Board, Board> {
        var x = Board(rules)
        var y = Board(rules)
        for (turn in 0 until Category.COUNT) {
            if (cancelled()) throw CancellationException("league stopped")
            x = turn(rules, first, x, y, s0, turn)
            y = turn(rules, second, y, x, s1, turn)
        }
        return x to y
    }

    private fun turn(rules: RuleSet, p: Player, me: Board, opp: Board, stream: DiceStream, turn: Int): Board {
        p.startTurn(me, opp)
        var r = stream.throwDice(Dice.EMPTY_KEEP, turn, 0)
        for (rollsDone in 1..2) {
            val keep = p.chooseKeep(rollsDone, r)
            if (keep == Dice.rollAsKeep[r]) break
            r = stream.throwDice(keep, turn, rollsDone)
        }
        val c = p.chooseCategory(r)
        return me.with(c, rules.score(Dice.rollCounts[r], c))
    }
}

/** One turn of a watched game: who moved (0 = first player), its throws, held dice and box. */
data class WatchTurn(
    val side: Int,
    val throws: List<List<Int>>,
    val held: List<List<Int>>,
    val category: yacht.engine.Category,
    val points: Int,
    /** Both cards after this turn (first player, second player). */
    val first: Board,
    val second: Board,
)

/** A single game kept turn by turn, for watching it back. */
object WatchMatch {
    fun play(rules: RuleSet, first: Player, second: Player, rng: Random): List<WatchTurn> {
        var a = Board(rules)
        var b = Board(rules)
        val out = ArrayList<WatchTurn>()
        for (turn in 0 until Category.COUNT) {
            for (side in 0..1) {
                val p = if (side == 0) first else second
                val me = if (side == 0) a else b
                val opp = if (side == 0) b else a
                p.startTurn(me, opp)
                val throws = ArrayList<List<Int>>()
                val held = ArrayList<List<Int>>()
                var r = yacht.engine.Match.throwDice(rng, Dice.EMPTY_KEEP)
                throws += Dice.rollFaces(r)
                for (rollsDone in 1..2) {
                    val keep = p.chooseKeep(rollsDone, r)
                    if (keep == Dice.rollAsKeep[r]) break
                    held += Dice.keepFaces(keep)
                    r = yacht.engine.Match.throwDice(rng, keep)
                    throws += Dice.rollFaces(r)
                }
                val c = p.chooseCategory(r)
                val pts = rules.score(Dice.rollCounts[r], c)
                if (side == 0) a = a.with(c, pts) else b = b.with(c, pts)
                out += WatchTurn(side, throws, held, c, pts, a, b)
            }
        }
        return out
    }
}

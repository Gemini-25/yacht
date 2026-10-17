package yacht.engine.game

import yacht.engine.Advice
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Mode
import yacht.engine.RuleSet
import java.util.Random

enum class Side { ME, OPP }

/** MANUAL: the player types in dice thrown elsewhere (advisor). RANDOM: the app throws (vs AI). */
enum class DiceSource { MANUAL, RANDOM }

val Category.label: String
    get() = when (this) {
        Category.ACES -> "1"
        Category.DEUCES -> "2"
        Category.THREES -> "3"
        Category.FOURS -> "4"
        Category.FIVES -> "5"
        Category.SIXES -> "6"
        Category.CHOICE -> "초이스"
        Category.FOUR_OF_A_KIND -> "포카드"
        Category.FULL_HOUSE -> "풀하우스"
        Category.SMALL_STRAIGHT -> "S.스트레이트"
        Category.LARGE_STRAIGHT -> "L.스트레이트"
        Category.YACHT -> "요트"
    }

/** Every score a category can show; used for entering the opponent's scores. */
fun possibleScores(rules: RuleSet, c: Category): List<Int> =
    yacht.engine.Dice.rollCounts.map { rules.score(it, c) }.toSortedSet().toList()

/** Dice of the turn in progress. [rollsDone] counts completed throws (0..3). */
data class TurnDraft(
    val rollsDone: Int = 0,
    val dice: List<Int?> = List(5) { null },
    val held: List<Boolean> = List(5) { false },
    /** Slots filled during the current manual entry, oldest first (for backspace). */
    val entryOrder: List<Int> = emptyList(),
) {
    val complete: Boolean get() = dice.all { it != null }
    val faces: List<Int> get() = dice.map { requireNotNull(it) }
    val heldFaces: List<Int> get() = dice.indices.filter { held[it] }.map { dice[it]!! }.sorted()

    /** Dice are complete and the player is deciding (hold / throw again / score). */
    val deciding: Boolean get() = complete && rollsDone >= 1
}

enum class DecisionKind { KEEP, SCORE }

/** One of my decisions with what the advisor thought of it, for the review screen. */
data class DecisionRecord(
    val turn: Int,
    val rollsDone: Int,
    val dice: List<Int>,
    val kind: DecisionKind,
    val chosen: String,
    val best: String,
    val mode: Mode,
    /** P(win) in WIN mode, expected final score otherwise. */
    val chosenValue: Double,
    val bestValue: Double,
    val chosenExpected: Double,
    val bestExpected: Double,
    /** Why the recommendation was better (reason only), written when the choice differed from it. */
    val note: String? = null,
) {
    val loss: Double get() = (bestValue - chosenValue).coerceAtLeast(0.0)
    val expectedLoss: Double get() = (bestExpected - chosenExpected).coerceAtLeast(0.0)
    val isMistake: Boolean get() = if (mode == Mode.WIN) loss > 0.0005 else loss > 0.05
}

/** One box written by either side, with my P(win) right after it when known (WIN mode). */
data class MoveRecord(
    val side: Side,
    val category: Category,
    val points: Int,
    val winAfter: Double? = null,
)

data class GameState(
    val source: DiceSource,
    val first: Side,
    val me: Board,
    val opp: Board,
    val toMove: Side,
    val draft: TurnDraft = TurnDraft(),
    val decisions: List<DecisionRecord> = emptyList(),
    /** Human-readable log of the opponent's turns (vs AI). */
    val oppLog: List<String> = emptyList(),
    /** Every box written, in order. */
    val moves: List<MoveRecord> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val rules: RuleSet get() = me.rules
    val isOver: Boolean get() = me.isComplete && opp.isComplete
    val myTurnNumber: Int get() = me.filledCount + 1

    fun boardOf(side: Side) = if (side == Side.ME) me else opp

    companion object {
        fun new(source: DiceSource, first: Side, rules: RuleSet = RuleSet.NINTENDO) =
            GameState(source, first, Board(rules), Board(rules), toMove = first)
    }
}

/** Pure state transitions. Every function returns a new state and leaves invalid moves unchanged. */
object Game {
    private fun nextToMove(s: GameState, justMoved: Side): Side {
        val other = if (justMoved == Side.ME) Side.OPP else Side.ME
        return if (!s.boardOf(other).isComplete) other else justMoved
    }

    /** Manual entry of one die face into the next empty slot. */
    fun enterFace(s: GameState, face: Int): GameState {
        val d = s.draft
        if (s.toMove != Side.ME || s.isOver || d.complete || d.rollsDone >= 3 || face !in 1..6) return s
        val slot = d.dice.indexOfFirst { it == null }
        val dice = d.dice.toMutableList().also { it[slot] = face }
        var next = d.copy(dice = dice, entryOrder = d.entryOrder + slot)
        if (next.complete) next = next.copy(rollsDone = d.rollsDone + 1, entryOrder = next.entryOrder)
        return s.copy(draft = next)
    }

    /** Undo the last manually entered face (also re-opens a just-completed throw). */
    fun backspace(s: GameState): GameState {
        val d = s.draft
        if (s.toMove != Side.ME || d.entryOrder.isEmpty()) return s
        val slot = d.entryOrder.last()
        val dice = d.dice.toMutableList().also { it[slot] = null }
        val rolls = if (d.complete) d.rollsDone - 1 else d.rollsDone
        return s.copy(draft = d.copy(rollsDone = rolls, dice = dice, entryOrder = d.entryOrder.dropLast(1)))
    }

    /** Clear one manually entered die so it can be re-typed. */
    fun clearSlot(s: GameState, slot: Int): GameState {
        val d = s.draft
        if (s.toMove != Side.ME || d.complete || d.dice[slot] == null || d.held[slot]) return s
        val dice = d.dice.toMutableList().also { it[slot] = null }
        return s.copy(draft = d.copy(dice = dice, entryOrder = d.entryOrder - slot))
    }

    fun toggleHold(s: GameState, slot: Int): GameState {
        val d = s.draft
        if (s.toMove != Side.ME || !d.deciding || d.rollsDone >= 3) return s
        return s.copy(draft = d.copy(held = d.held.toMutableList().also { it[slot] = !it[slot] }))
    }

    /** Hold exactly the given faces (e.g. the advisor's recommendation). */
    fun holdFaces(s: GameState, faces: List<Int>): GameState {
        val d = s.draft
        if (!d.deciding) return s
        val left = faces.toMutableList()
        val held = d.faces.map { f -> left.remove(f) }
        return s.copy(draft = d.copy(held = held))
    }

    /**
     * Throw the non-held dice again. MANUAL clears them for typing; RANDOM throws with [rng].
     * [advice] (for the current dice) is used to record the decision.
     */
    fun reroll(s: GameState, advice: Advice?, rng: Random? = null, note: String? = null): GameState {
        val d = s.draft
        if (s.toMove != Side.ME || !d.deciding || d.rollsDone >= 3 || d.held.all { it }) return s
        val decisions = if (advice != null) s.decisions + keepRecord(s, advice, note) else s.decisions
        val next = when (s.source) {
            DiceSource.MANUAL -> d.copy(
                dice = d.dice.mapIndexed { i, f -> if (d.held[i]) f else null },
                entryOrder = emptyList(),
            )
            DiceSource.RANDOM -> {
                val r = requireNotNull(rng)
                d.copy(
                    rollsDone = d.rollsDone + 1,
                    dice = d.dice.mapIndexed { i, f -> if (d.held[i]) f else r.nextInt(6) + 1 },
                    held = List(5) { false },
                    entryOrder = emptyList(),
                )
            }
        }
        return s.copy(draft = next, decisions = decisions)
    }

    /** RANDOM mode: first throw of my turn. */
    fun firstThrow(s: GameState, rng: Random): GameState {
        if (s.toMove != Side.ME || s.draft.rollsDone != 0 || s.source != DiceSource.RANDOM) return s
        return s.copy(draft = TurnDraft(rollsDone = 1, dice = List(5) { rng.nextInt(6) + 1 }))
    }

    fun score(s: GameState, c: Category, advice: Advice?, note: String? = null): GameState {
        val d = s.draft
        if (s.toMove != Side.ME || !d.deciding || s.me.isFilled(c)) return s
        val points = s.rules.score(d.faces, c)
        val decisions = if (advice != null) s.decisions + scoreRecord(s, c, advice, note) else s.decisions
        val win = advice?.takeIf { it.mode == Mode.WIN }?.categories?.firstOrNull { it.category == c }?.value
        val me = s.me.with(c, points)
        val moved = s.copy(me = me, draft = TurnDraft(), decisions = decisions, moves = s.moves + MoveRecord(Side.ME, c, points, win))
        return moved.copy(toMove = nextToMove(moved, Side.ME))
    }

    fun opponentScore(s: GameState, c: Category, points: Int, log: String? = null): GameState {
        if (s.toMove != Side.OPP || s.opp.isFilled(c)) return s
        val moved = s.copy(
            opp = s.opp.with(c, points),
            oppLog = if (log != null) s.oppLog + log else s.oppLog,
            moves = s.moves + MoveRecord(Side.OPP, c, points),
        )
        return moved.copy(toMove = nextToMove(moved, Side.OPP))
    }

    /** Records my P(win) after the opponent's last move, once my next turn has been planned. */
    fun noteWinAfterOpponent(s: GameState, win: Double): GameState {
        val last = s.moves.lastOrNull() ?: return s
        if (last.side != Side.OPP || last.winAfter == win) return s
        return s.copy(moves = s.moves.dropLast(1) + last.copy(winAfter = win))
    }

    fun keepText(held: List<Int>) = if (held.isEmpty()) "모두 다시" else held.joinToString(" ") + " 고정"

    /** "초이스 23점", "‘6’ 18점" (upper boxes are numbers, so they are quoted). */
    fun scoreText(c: Category, points: Int) = (if (c.isUpper) "‘${c.face}’" else c.label) + " ${points}점"

    private fun bestText(a: Advice): String =
        if (a.shouldStop) scoreText(a.bestCategory.category, a.bestCategory.points) else keepText(a.bestKeep!!.held)

    private fun bestExpected(a: Advice): Double =
        if (a.shouldStop) a.bestCategory.expectedFinal else a.bestKeep!!.expectedFinal

    private fun keepRecord(s: GameState, a: Advice, note: String?): DecisionRecord {
        val held = s.draft.heldFaces
        val opt = a.keeps.first { it.held == held }
        return DecisionRecord(
            s.myTurnNumber, s.draft.rollsDone, s.draft.faces.sorted(), DecisionKind.KEEP,
            keepText(held), bestText(a), a.mode, opt.value, a.value, opt.expectedFinal, bestExpected(a), note,
        )
    }

    private fun scoreRecord(s: GameState, c: Category, a: Advice, note: String?): DecisionRecord {
        val opt = a.categories.first { it.category == c }
        return DecisionRecord(
            s.myTurnNumber, s.draft.rollsDone, s.draft.faces.sorted(), DecisionKind.SCORE,
            scoreText(c, opt.points), bestText(a), a.mode, opt.value, a.value, opt.expectedFinal, bestExpected(a), note,
        )
    }
}

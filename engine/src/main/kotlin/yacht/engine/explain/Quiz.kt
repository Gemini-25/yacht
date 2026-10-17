package yacht.engine.explain

import yacht.engine.Advice
import yacht.engine.Advisor
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.ExpectedAfter
import yacht.engine.Match
import yacht.engine.Mode
import yacht.engine.PlannedTurn
import yacht.engine.TurnPlan
import yacht.engine.game.DecisionRecord
import yacht.engine.game.Game
import java.util.Random

/** A position to decide: my card, the opponent's, and the dice after [rollsDone] throws. */
data class QuizQuestion(
    val me: Board,
    val opp: Board,
    val rollsDone: Int,
    val dice: List<Int>,
    /** Set when the question replays one of my own past moves. */
    val replay: DecisionRecord? = null,
)

/** The graded answer: how much P(win) it gave up and why the best move is better. */
data class QuizResult(
    val correct: Boolean,
    val loss: Double,
    val chosen: String,
    val best: String,
    val chosenValue: Double,
    val bestValue: Double,
    val explanation: Explanation,
    /** The best move: a box to write now, or the dice to hold. */
    val bestCategory: Category?,
    val bestHeld: List<Int>?,
)

/**
 * Practice positions where the right move is not the obvious one: the decision matters (at least
 * 1%p between the best and the next move) and either the max-expectation move or a simple rule
 * of thumb (most points now, keep the most common face) gets it wrong.
 */
class Quiz(private val advisor: Advisor, private val explainer: Explainer) {
    private val tables = advisor.tables
    private val rules = tables.rules

    /** Plan used for grading: a fixed budget, so the same position always gets the same answer. */
    fun plan(q: QuizQuestion): PlannedTurn = advisor.planTurn(q.me, q.opp, Mode.WIN, BUDGET)

    fun advice(plan: PlannedTurn, q: QuizQuestion): Advice = plan.advise(q.rollsDone, q.dice)

    /**
     * A new question with its plan, or null if none was found in [tries] attempts. [wantKeep]
     * asks for a question whose answer is to hold dice and throw again (true) or to write a box
     * (false); null takes either.
     */
    fun generate(rng: Random, tries: Int = 40, wantKeep: Boolean? = null): Pair<QuizQuestion, PlannedTurn>? {
        repeat(tries) {
            val q = randomPosition(rng, if (wantKeep == true) 1 + rng.nextInt(2) else 1 + rng.nextInt(3))
            val plan = plan(q)
            if (plan.mode != Mode.WIN) return@repeat
            val a = advice(plan, q)
            if (wantKeep != null && a.shouldStop == wantKeep) return@repeat
            if (isInteresting(a)) return q to plan
        }
        return if (wantKeep != null) generate(rng, tries, null) else null
    }

    /** A realistic position: both sides played for max expectation so far, on random dice. */
    private fun randomPosition(rng: Random, rollsDone: Int): QuizQuestion {
        val filled = rng.nextInt(Category.COUNT)
        val meFirst = rng.nextBoolean()
        var me = Board(rules)
        var opp = Board(rules)
        val oppTurns = if (meFirst) filled else minOf(Category.COUNT, filled + 1)
        repeat(filled) { me = playTurn(me, rng) }
        repeat(oppTurns) { opp = playTurn(opp, rng) }
        val plan = TurnPlan(me, ExpectedAfter(tables.ev, me.mask))
        var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
        for (k in 1 until rollsDone) {
            var keep = plan.bestKeep(k, r)
            // Keep the turn going to the asked throw even where max expectation would stop.
            if (keep == Dice.rollAsKeep[r]) keep = Dice.EMPTY_KEEP
            r = Match.throwDice(rng, keep)
        }
        return QuizQuestion(me, opp, rollsDone, Dice.rollFaces(r))
    }

    private fun playTurn(b: Board, rng: Random): Board {
        val plan = TurnPlan(b, ExpectedAfter(tables.ev, b.mask))
        var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
        for (k in 1..2) {
            val keep = plan.bestKeep(k, r)
            if (keep == Dice.rollAsKeep[r]) break
            r = Match.throwDice(rng, keep)
        }
        val c = plan.bestCategory(r)
        return b.with(c, tables.scores[r, c.ordinal])
    }

    /** Values of the distinct decisions: each re-throw, and writing into each box now. */
    private fun choices(a: Advice): List<Triple<String, Double, Double>> {
        val keeps = a.keeps.filter { it.held.size < 5 }.map { Triple("k:${it.held}", it.value, it.expectedFinal) }
        val boxes = a.categories.map { Triple("c:${it.category}", it.value, it.expectedFinal) }
        return keeps + boxes
    }

    private fun isInteresting(a: Advice): Boolean {
        if (a.value < 0.03 || a.value > 0.97) return false
        val all = choices(a).sortedByDescending { it.second }
        if (all.size < 2 || all[0].second - all[1].second < 0.01) return false
        val best = all[0].first
        val evBest = all.maxBy { it.third }.first
        return evBest != best || ruleOfThumb(a) != best
    }

    /** What a casual player would do: keep the most common face, or write the most points. */
    private fun ruleOfThumb(a: Advice): String {
        if (a.rollsDone < 3) {
            val counts = Dice.countsOf(a.dice)
            val face = (6 downTo 1).maxBy { counts[it - 1] }
            if (counts[face - 1] < 5) return "k:${List(counts[face - 1]) { face }}"
        }
        return "c:${a.categories.maxBy { it.points }.category}"
    }

    fun gradeKeep(plan: PlannedTurn, q: QuizQuestion, held: List<Int>): QuizResult {
        val a = advice(plan, q)
        val opt = a.keeps.first { it.held == held.sorted() }
        val e = if (a.bestKeep?.held == opt.held) explainer.advice(plan, q.opp, a) else explainer.keepOption(plan, q.opp, a, opt)
        return result(a, Game.keepText(opt.held), opt.value, e)
    }

    fun gradeCategory(plan: PlannedTurn, q: QuizQuestion, c: Category): QuizResult {
        val a = advice(plan, q)
        val opt = a.categories.first { it.category == c }
        val best = a.shouldStop && a.bestCategory.category == c
        val e = if (best) explainer.advice(plan, q.opp, a) else explainer.categoryOption(plan, q.opp, a, opt)
        return result(a, Game.scoreText(c, opt.points), opt.value, e)
    }

    private fun result(a: Advice, chosen: String, value: Double, e: Explanation): QuizResult {
        val best = if (a.shouldStop) Game.scoreText(a.bestCategory.category, a.bestCategory.points) else Game.keepText(a.bestKeep!!.held)
        val loss = (a.value - value).coerceAtLeast(0.0)
        return QuizResult(
            loss < 0.0005, loss, chosen, best, value, a.value, e,
            bestCategory = if (a.shouldStop) a.bestCategory.category else null,
            bestHeld = if (a.shouldStop) null else a.bestKeep!!.held,
        )
    }

    companion object {
        /** Search budget for grading (a few hundred ms on a phone). */
        const val BUDGET = 100_000_000L

        /** My past moves that differed from the advice and can be replayed. */
        fun replays(decisions: List<DecisionRecord>): List<QuizQuestion> =
            decisions.filter { it.isMistake && it.mode == Mode.WIN && it.me != null && it.opp != null }
                .map { QuizQuestion(it.me!!, it.opp!!, it.rollsDone, it.dice, it) }
    }
}

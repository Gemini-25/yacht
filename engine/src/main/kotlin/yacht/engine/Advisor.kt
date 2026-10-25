package yacht.engine

import java.util.concurrent.ExecutorService

enum class Mode { EXPECTED_SCORE, WIN }

data class KeepOption(
    val held: List<Int>,
    /** P(win) in WIN mode, expected final score in EXPECTED_SCORE mode. */
    val value: Double,
    val expectedFinal: Double,
    /** P(tie) along the plan when the search tracks ties, else NaN. */
    val tie: Double = Double.NaN,
)

data class CategoryOption(
    val category: Category,
    val points: Int,
    val value: Double,
    val expectedFinal: Double,
    val tie: Double = Double.NaN,
)

/** Win, draw and loss probabilities of a position (they sum to 1). */
data class Outlook(val win: Double, val tie: Double) {
    val loss: Double get() = (1.0 - win - tie).coerceAtLeast(0.0)
}

class Advice(
    val mode: Mode,
    val rollsDone: Int,
    val dice: List<Int>,
    /** Best first. Empty after the third roll. Holding all five dice means "stop and score". */
    val keeps: List<KeepOption>,
    /** Best first. */
    val categories: List<CategoryOption>,
    /** Value of the position right now with optimal play from here. */
    val value: Double,
    /** Win/draw/loss right now (P(win) without the points tie-break); null unless ties are tracked. */
    val outlook: Outlook? = null,
) {
    val bestKeep: KeepOption? get() = keeps.firstOrNull()
    val bestCategory: CategoryOption get() = categories.first()

    /** True when the best move is to stop rolling and score now. */
    val shouldStop: Boolean get() = rollsDone == 3 || (bestKeep?.held?.size == 5)
}

/** Plans of one turn for both objectives; advice for any throw of that turn is read from them. */
class PlannedTurn internal constructor(
    val me: Board,
    val mode: Mode,
    val depth: Int,
    private val evPlan: TurnPlan,
    private val winPlan: TurnPlan?,
    /** Exact to the end with the opponent's turns inside the search (DuelSearch). */
    val duel: Boolean = false,
    /**
     * What the points tie-break in plan values is measured against: 0 for a fixed-opponent
     * search (my final score), the opponent's expected final for a duel (score difference).
     */
    private val weightBase: Double = 0.0,
) {
    private val plan get() = winPlan ?: evPlan
    private val total = me.total

    /** True when P(tie) is tracked, so [startOutlook] and the advice's outlooks are known. */
    val hasTies: Boolean get() = winPlan != null && !winPlan.startTie.isNaN()

    /**
     * P(win) and P(tie) from plan [value] and [tie], removing the points tie-break (estimated
     * from the max-expectation final [expectedFinal]; off by well under 0.001%p).
     */
    private fun outlook(value: Double, tie: Double, expectedFinal: Double): Outlook? {
        if (winPlan == null || tie.isNaN()) return null
        val w = (value - WinCurve.SCORE_WEIGHT * (expectedFinal - weightBase)).coerceIn(0.0, 1.0)
        return Outlook(w, tie.coerceIn(0.0, 1.0 - w))
    }

    /** Win/draw/loss before the first throw of the turn; null unless ties are tracked. */
    val startOutlook: Outlook?
        get() = winPlan?.let { outlook(it.startValue, it.startTie, total + evPlan.startValue) }

    /** Win/draw/loss after holding [keep] (index) following [rollsDone] throws. */
    fun keepOutlook(rollsDone: Int, keep: Int): Outlook? {
        val w = winPlan ?: return null
        return outlook(w.keepValue(rollsDone, keep), w.keepTie(rollsDone, keep), total + evPlan.keepValue(rollsDone, keep))
    }

    /** The plan whose decisions are followed (win plan in WIN mode). */
    internal val active: TurnPlan get() = plan

    /** Plan values in the units shown to the player (P(win), or expected final score). */
    internal fun shown(v: Double) = if (winPlan != null) v else total + v

    /** Value before the first throw of the turn. */
    val startValue: Double get() = if (winPlan != null) winPlan.startValue else total + evPlan.startValue

    fun advise(rollsDone: Int, dice: List<Int>): Advice {
        require(rollsDone in 1..3)
        require(dice.size == 5)
        val r = Dice.rollIndex(dice)
        val keeps = if (rollsDone == 3) emptyList() else {
            (Dice.subStart[r] until Dice.subStart[r + 1]).map { j ->
                val k = Dice.subKeep[j]
                if (Dice.keepSize[k] == 5) {
                    // Holding everything ends the turn now (the kernel's value would allow a later reroll).
                    KeepOption(Dice.keepFaces(k), shown(plan.rollValue(3, r)), total + evPlan.rollValue(3, r), tieOf { it.rollTie(3, r) })
                } else {
                    KeepOption(
                        Dice.keepFaces(k), shown(plan.keepValue(rollsDone, k)), total + evPlan.keepValue(rollsDone, k),
                        tieOf { it.keepTie(rollsDone, k) },
                    )
                }
            }.sortedWith(compareByDescending<KeepOption> { it.value }.thenByDescending { it.held.size })
        }
        val categories = me.openCategories.map { c ->
            CategoryOption(
                c, plan.points(r, c.ordinal), shown(plan.categoryValue(r, c.ordinal)), total + evPlan.categoryValue(r, c.ordinal),
                tieOf { it.categoryTie(r, c.ordinal) },
            )
        }.sortedWith(compareByDescending<CategoryOption> { it.value }.thenByDescending { it.expectedFinal })
        val now = winPlan?.let { outlook(it.rollValue(rollsDone, r), it.rollTie(rollsDone, r), total + evPlan.rollValue(rollsDone, r)) }
        return Advice(mode, rollsDone, dice.sorted(), keeps, categories, shown(plan.rollValue(rollsDone, r)), now)
    }

    private inline fun tieOf(f: (TurnPlan) -> Double): Double = winPlan?.let(f) ?: Double.NaN

    /** Value of holding [held] (fewer than five dice) after [rollsDone] throws, in shown units. */
    fun keepValue(rollsDone: Int, held: List<Int>): Double =
        shown(plan.keepValue(rollsDone, Dice.keepIndex(Dice.countsOf(held))))

    /** Index-level decisions for simulations. */
    fun bestKeep(rollsDone: Int, r: Int): Int = plan.bestKeep(rollsDone, r)
    fun bestCategory(r: Int): Category = plan.bestCategory(r)
}

/**
 * [adversarial]: in the exact end-game search the opponent plays to beat me (minimax) instead of
 * for max expectation, so the plan maximises the P(win) I can guarantee against any opponent.
 */
class Advisor(val tables: Tables, private val pool: ExecutorService? = null, val adversarial: Boolean = false) {
    /**
     * Plans my turn. With [opp] and the distribution table it maximises P(win) (ties count as
     * [tieValue]); otherwise it maximises expected score. [budgetOps] bounds the exact search.
     */
    fun planTurn(
        me: Board,
        opp: Board?,
        mode: Mode = if (opp != null) Mode.WIN else Mode.EXPECTED_SCORE,
        budgetOps: Long = DEFAULT_BUDGET,
        tieValue: Double = 0.0,
        depth: Int? = null,
        deadlineNanos: Long = Long.MAX_VALUE,
        duel: Boolean = false,
        /** A duel table built earlier in this game; used as is when it covers this position. */
        table: DuelSearch? = null,
        /** Also compute P(tie) along the plan (about twice the search cost). */
        ties: Boolean = false,
        /** Points added to the opponent's final in the fixed-opponent model (misjudging it; 0 = as modelled). */
        oppShift: Int = 0,
    ): PlannedTurn {
        require(me.rules == tables.rules)
        val evPlan = TurnPlan(me, ExpectedAfter(tables.ev, me.mask))
        if (mode == Mode.EXPECTED_SCORE || opp == null || tables.dist == null) {
            return PlannedTurn(me, Mode.EXPECTED_SCORE, 0, evPlan, null)
        }
        val remaining = Category.COUNT - me.filledCount
        val oppExpected = opp.total + if (opp.isComplete) 0.0 else tables.ev[opp.mask, opp.u]
        table?.after(me, opp)?.let { after ->
            return PlannedTurn(me, Mode.WIN, remaining, evPlan, TurnPlan(me, after), duel = true, weightBase = oppExpected)
        }
        if (duel && canDuel(me, opp) && DuelSearch.fitsMemory(tables, me, opp, ties = ties)) {
            val search = DuelSearch(tables, me, opp, true, pool, deadlineNanos, adversarial = adversarial, ties = ties)
            return PlannedTurn(me, Mode.WIN, remaining, evPlan, TurnPlan(me, search), duel = true, weightBase = oppExpected)
        }
        val curve = WinCurve.against(tables, opp, tieValue, oppShift)
        val d = depth ?: WinSearch.chooseDepth(tables, me, budgetOps, opp)
        val search = WinSearch(tables, me, curve, d, pool, deadlineNanos, ties = ties)
        return PlannedTurn(me, Mode.WIN, d, evPlan, TurnPlan(me, search))
    }

    /**
     * Win-mode plan that deepens the exact search until [deadlineNanos] (System.nanoTime()).
     * The one-turn plan is always produced first; [onImproved] receives every finished depth.
     * A depth is only started when the previous depth's speed predicts it will finish in time.
     */
    fun planTurnAnytime(
        me: Board,
        opp: Board,
        deadlineNanos: Long,
        tieValue: Double = 0.0,
        table: DuelSearch? = null,
        /** False when a duel table is already being built elsewhere (avoid doing it twice). */
        allowDuel: Boolean = true,
        /**
         * Also give P(tie): after the decisions are settled, the deepest search is repeated with
         * ties tracked (same values, same decisions) and handed over as a last improvement. The
         * time budget is spent on the decisions first, so tracking ties never makes them shallower.
         */
        ties: Boolean = false,
        onImproved: (PlannedTurn) -> Unit = {},
    ): PlannedTurn {
        table?.after(me, opp)?.let {
            val p = planTurn(me, opp, Mode.WIN, tieValue = tieValue, table = table)
            onImproved(p)
            return p
        }
        val begin = System.nanoTime()
        var t0 = begin
        var best = planTurn(me, opp, Mode.WIN, tieValue = tieValue, depth = 1)
        onImproved(best)
        if (best.mode != Mode.WIN) return best
        var lastNanos = System.nanoTime() - t0
        var lastOps = WinSearch.estimateOps(tables, me, 1, opp).coerceAtLeast(1)
        val remaining = Category.COUNT - me.filledCount
        for (d in 2..remaining) {
            val ops = WinSearch.estimateOps(tables, me, d, opp)
            t0 = System.nanoTime()
            val predicted = (lastNanos.toDouble() * ops / lastOps).toLong()
            if (t0 + predicted > deadlineNanos) break
            try {
                best = planTurn(me, opp, Mode.WIN, tieValue = tieValue, depth = d, deadlineNanos = deadlineNanos)
            } catch (e: SearchTimeout) {
                break
            }
            lastNanos = (System.nanoTime() - t0).coerceAtLeast(1)
            lastOps = ops.coerceAtLeast(1)
            onImproved(best)
        }
        // Exact to the end already: if time allows, also let later turns react to the opponent.
        if (allowDuel && best.depth >= remaining && canDuel(me, opp)) {
            val ops = DuelSearch.estimateOps(tables, me, opp, limit = DUEL_MAX_OPS, adversarial = adversarial)
            val predicted = (lastNanos.toDouble() * ops / lastOps).toLong()
            if (ops < DUEL_MAX_OPS && System.nanoTime() + predicted <= deadlineNanos && DuelSearch.fitsMemory(tables, me, opp, ties = ties)) {
                try {
                    t0 = System.nanoTime()
                    best = planTurn(me, opp, Mode.WIN, tieValue = tieValue, deadlineNanos = deadlineNanos, duel = true)
                    lastNanos = (System.nanoTime() - t0).coerceAtLeast(1)
                    onImproved(best)
                } catch (e: SearchTimeout) {
                    // keep the exact fixed-opponent plan
                }
            }
        }
        if (ties) {
            // P(tie) for display: the same search once more with ties (about twice its time),
            // allowed to run past the deadline by at most the thinking time again.
            val tieDeadline = deadlineNanos + (deadlineNanos - begin)
            if (System.nanoTime() + 2 * lastNanos <= tieDeadline) {
                try {
                    best = if (best.duel) {
                        planTurn(me, opp, Mode.WIN, tieValue = tieValue, deadlineNanos = tieDeadline, duel = true, ties = true)
                    } else {
                        planTurn(me, opp, Mode.WIN, tieValue = tieValue, depth = best.depth, deadlineNanos = tieDeadline, ties = true)
                    }
                    onImproved(best)
                } catch (e: SearchTimeout) {
                    // decisions stand; only the draw chance is missing
                }
            }
        }
        return best
    }

    /**
     * Builds a duel table rooted at this position ([meToMove] = whose turn it is) for reuse in
     * all my later turns. Throws [SearchTimeout] when [stop] is set.
     */
    fun buildDuelTable(
        me: Board,
        opp: Board,
        meToMove: Boolean,
        executor: ExecutorService? = pool,
        stop: java.util.concurrent.atomic.AtomicBoolean? = null,
        ties: Boolean = false,
    ): DuelSearch = DuelSearch(tables, me, opp, meToMove, executor, stop = stop, adversarial = adversarial, ties = ties)

    /** The duel search needs it to be my turn: the opponent has as many boxes left as me, or one fewer. */
    fun canDuel(me: Board, opp: Board): Boolean {
        val kMe = Category.COUNT - me.filledCount
        val kOpp = Category.COUNT - opp.filledCount
        return kMe > 0 && (kOpp == kMe || kOpp == kMe - 1)
    }

    companion object {
        /** Ops budget for deterministic depth choice (simulations); the app uses a deadline instead. */
        const val DEFAULT_BUDGET = 300_000_000L

        /** Never start a duel search above this many operations, whatever the time budget. */
        const val DUEL_MAX_OPS = 3_000_000_000L
    }
}

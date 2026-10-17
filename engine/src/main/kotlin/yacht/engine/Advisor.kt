package yacht.engine

import java.util.concurrent.ExecutorService

enum class Mode { EXPECTED_SCORE, WIN }

data class KeepOption(
    val held: List<Int>,
    /** P(win) in WIN mode, expected final score in EXPECTED_SCORE mode. */
    val value: Double,
    val expectedFinal: Double,
)

data class CategoryOption(
    val category: Category,
    val points: Int,
    val value: Double,
    val expectedFinal: Double,
)

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
) {
    private val plan get() = winPlan ?: evPlan
    private val total = me.total

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
                    KeepOption(Dice.keepFaces(k), shown(plan.rollValue(3, r)), total + evPlan.rollValue(3, r))
                } else {
                    KeepOption(Dice.keepFaces(k), shown(plan.keepValue(rollsDone, k)), total + evPlan.keepValue(rollsDone, k))
                }
            }.sortedWith(compareByDescending<KeepOption> { it.value }.thenByDescending { it.held.size })
        }
        val categories = me.openCategories.map { c ->
            CategoryOption(c, plan.points(r, c.ordinal), shown(plan.categoryValue(r, c.ordinal)), total + evPlan.categoryValue(r, c.ordinal))
        }.sortedWith(compareByDescending<CategoryOption> { it.value }.thenByDescending { it.expectedFinal })
        return Advice(mode, rollsDone, dice.sorted(), keeps, categories, shown(plan.rollValue(rollsDone, r)))
    }

    /** Index-level decisions for simulations. */
    fun bestKeep(rollsDone: Int, r: Int): Int = plan.bestKeep(rollsDone, r)
    fun bestCategory(r: Int): Category = plan.bestCategory(r)
}

class Advisor(val tables: Tables, private val pool: ExecutorService? = null) {
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
    ): PlannedTurn {
        require(me.rules == tables.rules)
        val evPlan = TurnPlan(me, ExpectedAfter(tables.ev, me.mask))
        if (mode == Mode.EXPECTED_SCORE || opp == null || tables.dist == null) {
            return PlannedTurn(me, Mode.EXPECTED_SCORE, 0, evPlan, null)
        }
        val remaining = Category.COUNT - me.filledCount
        table?.after(me, opp)?.let { after ->
            return PlannedTurn(me, Mode.WIN, remaining, evPlan, TurnPlan(me, after), duel = true)
        }
        if (duel && canDuel(me, opp)) {
            val search = DuelSearch(tables, me, opp, true, pool, deadlineNanos)
            return PlannedTurn(me, Mode.WIN, remaining, evPlan, TurnPlan(me, search), duel = true)
        }
        val curve = WinCurve.against(tables, opp, tieValue)
        val d = depth ?: WinSearch.chooseDepth(tables, me, budgetOps, opp)
        val search = WinSearch(tables, me, curve, d, pool, deadlineNanos)
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
        onImproved: (PlannedTurn) -> Unit = {},
    ): PlannedTurn {
        table?.after(me, opp)?.let {
            val p = planTurn(me, opp, Mode.WIN, tieValue = tieValue, table = table)
            onImproved(p)
            return p
        }
        var t0 = System.nanoTime()
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
            val ops = DuelSearch.estimateOps(tables, me, opp, limit = DUEL_MAX_OPS)
            val predicted = (lastNanos.toDouble() * ops / lastOps).toLong()
            if (ops < DUEL_MAX_OPS && System.nanoTime() + predicted <= deadlineNanos) {
                try {
                    best = planTurn(me, opp, Mode.WIN, tieValue = tieValue, deadlineNanos = deadlineNanos, duel = true)
                    onImproved(best)
                } catch (e: SearchTimeout) {
                    // keep the exact fixed-opponent plan
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
    ): DuelSearch = DuelSearch(tables, me, opp, meToMove, executor, stop = stop)

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

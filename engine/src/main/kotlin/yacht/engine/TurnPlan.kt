package yacht.engine

/** Value of ending the turn by writing [points] (plus [bonus]) into category [c], reaching capped upper sum [u2]. */
fun interface AfterTurn {
    fun value(c: Int, points: Int, bonus: Int, u2: Int): Double

    /** P(tie) from there with the plan behind [value], or NaN when not tracked. */
    fun tie(c: Int, points: Int, bonus: Int, u2: Int): Double = Double.NaN
}

/** Max-expectation continuation: points now plus expected remaining points. */
class ExpectedAfter(private val ev: EvTable, private val mask: Int) : AfterTurn {
    override fun value(c: Int, points: Int, bonus: Int, u2: Int): Double =
        points + bonus + ev[mask or (1 shl c), u2]
}

/**
 * Optimal play of one turn for a given end-of-turn valuation. All decisions of the turn
 * (both keeps and the category) come from the arrays computed once here.
 */
class TurnPlan(val board: Board, private val after: AfterTurn) {
    private val rules = board.rules
    private val scores = ScoreTable.of(rules)
    private val kernel = TurnKernel(1)
    private val bestCat = IntArray(Dice.ROLLS)

    init {
        require(!board.isComplete)
        val open = board.openCategories.map { it.ordinal }
        val e3 = kernel.e3
        for (r in 0 until Dice.ROLLS) {
            var best = Double.NEGATIVE_INFINITY
            var bc = -1
            for (c in open) {
                val v = categoryValue(r, c)
                if (v > best) {
                    best = v
                    bc = c
                }
            }
            e3[r] = best
            bestCat[r] = bc
        }
        kernel.run()
    }

    /** Value before the first throw of this turn. */
    val startValue: Double get() = kernel.start[0]

    fun points(r: Int, c: Int): Int = scores[r, c]

    fun categoryValue(r: Int, c: Int): Double = afterCategory(r, c, after::value)

    /** P(tie) after writing roll [r] into [c] and following the plan; NaN when not tracked. */
    fun categoryTie(r: Int, c: Int): Double = afterCategory(r, c, after::tie)

    private inline fun afterCategory(r: Int, c: Int, f: (Int, Int, Int, Int) -> Double): Double {
        val s = scores[r, c]
        val u = board.u
        if (c < 6) {
            val cap = rules.bonusThreshold
            val bonus = if (u < cap && u + s >= cap) rules.bonusPoints else 0
            return f(c, s, bonus, minOf(cap, u + s))
        }
        return f(c, s, 0, u)
    }

    /**
     * P(tie) along this plan's own decisions (policy evaluation, no maximising): per final roll
     * after the 3rd/2nd/1st throw, and per keep before the 3rd/2nd. Null when not tracked.
     */
    private class Ties(val t3: DoubleArray, val k2: DoubleArray, val t2: DoubleArray, val k1: DoubleArray, val t1: DoubleArray, val start: Double)

    private val ties: Ties? by lazy {
        val t3 = DoubleArray(Dice.ROLLS) { categoryTie(it, bestCat[it]) }
        if (t3.any { it.isNaN() }) return@lazy null
        val k2 = TurnKernel.expectOne(t3)
        val t2 = DoubleArray(Dice.ROLLS) { k2[bestKeep(2, it)] }
        val k1 = TurnKernel.expectOne(t2)
        val t1 = DoubleArray(Dice.ROLLS) { k1[bestKeep(1, it)] }
        var start = 0.0
        for (r in 0 until Dice.ROLLS) start += Dice.firstRoll[r] * t1[r]
        Ties(t3, k2, t2, k1, t1, start)
    }

    /** P(tie) before the first throw; NaN when the valuation does not track ties. */
    val startTie: Double get() = ties?.start ?: Double.NaN

    /** P(tie) of holding [keep] after [rollsDone] throws and following the plan. */
    fun keepTie(rollsDone: Int, keep: Int): Double {
        val t = ties ?: return Double.NaN
        return when (rollsDone) {
            1 -> t.k1[keep]
            2 -> t.k2[keep]
            else -> error("no keep decision after roll $rollsDone")
        }
    }

    /** P(tie) of having roll [r] after [rollsDone] throws and following the plan. */
    fun rollTie(rollsDone: Int, r: Int): Double {
        val t = ties ?: return Double.NaN
        return when (rollsDone) {
            1 -> t.t1[r]
            2 -> t.t2[r]
            3 -> t.t3[r]
            else -> error("bad roll number $rollsDone")
        }
    }

    fun bestCategory(r: Int): Category = Category.of(bestCat[r])

    /** Value of holding [keep] and throwing the rest, after [rollsDone] (1 or 2) throws. */
    fun keepValue(rollsDone: Int, keep: Int): Double = when (rollsDone) {
        1 -> kernel.k1[keep]
        2 -> kernel.k2[keep]
        else -> error("no keep decision after roll $rollsDone")
    }

    /** Value of having roll [r] after [rollsDone] throws, playing on optimally. */
    fun rollValue(rollsDone: Int, r: Int): Double = when (rollsDone) {
        1 -> kernel.e1[r]
        2 -> kernel.e2[r]
        3 -> kernel.e3[r]
        else -> error("bad roll number $rollsDone")
    }

    fun bestKeep(rollsDone: Int, r: Int): Int = kernel.argBestKeep(if (rollsDone == 1) kernel.k1 else kernel.k2, r)

    /**
     * Distribution of the turn's final roll after holding [keep] following [rollsDone] (1 or 2)
     * throws, when the rest of the turn follows this plan. Holding all five dice ends the turn.
     */
    fun finalRolls(rollsDone: Int, keep: Int): DoubleArray {
        require(rollsDone == 1 || rollsDone == 2)
        val next = DoubleArray(Dice.ROLLS)
        for (j in Dice.tStart[keep] until Dice.tStart[keep + 1]) next[Dice.tRoll[j]] += Dice.tProb[j]
        if (rollsDone == 2 || Dice.keepSize[keep] == 5) return next
        val out = DoubleArray(Dice.ROLLS)
        for (r in 0 until Dice.ROLLS) {
            val p = next[r]
            if (p == 0.0) continue
            val k = bestKeep(2, r)
            for (j in Dice.tStart[k] until Dice.tStart[k + 1]) out[Dice.tRoll[j]] += p * Dice.tProb[j]
        }
        return out
    }
}

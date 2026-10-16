package yacht.engine

/** Value of ending the turn by writing [points] (plus [bonus]) into category [c], reaching capped upper sum [u2]. */
fun interface AfterTurn {
    fun value(c: Int, points: Int, bonus: Int, u2: Int): Double
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

    fun categoryValue(r: Int, c: Int): Double {
        val s = scores[r, c]
        val u = board.u
        if (c < 6) {
            val cap = rules.bonusThreshold
            val bonus = if (u < cap && u + s >= cap) rules.bonusPoints else 0
            return after.value(c, s, bonus, minOf(cap, u + s))
        }
        return after.value(c, s, 0, u)
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
}

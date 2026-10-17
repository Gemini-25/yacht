package yacht.engine

import java.util.Random

/** Maximises this turn's points only (bonus included), ignoring what the boxes are worth later. */
class GreedyPlayer(override val name: String = "greedy") : Player {
    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        plan = TurnPlan(me) { _, points, bonus, _ -> (points + bonus).toDouble() }
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = plan.bestKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

/**
 * A casual player's rules of thumb: take a made hand, chase a four-dice straight, otherwise keep
 * the most common face; write big hands first, three or more of a face in its upper box, and zero
 * the least useful box when nothing scores.
 */
class RulePlayer(private val rules: RuleSet, override val name: String = "rule") : Player {
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
    }

    private fun open(c: Category) = !me.isFilled(c)

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val counts = Dice.rollCounts[r]
        val c = chooseCategory(r)
        val pts = rules.score(counts, c)
        val made = c == Category.YACHT || c == Category.LARGE_STRAIGHT || c == Category.FULL_HOUSE ||
            (c == Category.SMALL_STRAIGHT && !open(Category.LARGE_STRAIGHT)) ||
            (c == Category.FOUR_OF_A_KIND && pts >= 20) || (c.isUpper && counts[c.ordinal] >= 4)
        if (made && pts > 0) return Dice.rollAsKeep[r]
        if (open(Category.LARGE_STRAIGHT) || open(Category.SMALL_STRAIGHT)) {
            for (start in 0..2) {
                if ((start until start + 4).all { counts[it] > 0 }) {
                    val keep = IntArray(6)
                    for (f in start until start + 4) keep[f] = 1
                    return Dice.keepIndex(keep)
                }
            }
        }
        val face = (5 downTo 0).maxBy { counts[it] }
        val keep = IntArray(6)
        if (counts[face] >= 2) keep[face] = counts[face]
        return Dice.keepIndex(keep)
    }

    override fun chooseCategory(r: Int): Category {
        val counts = Dice.rollCounts[r]
        fun pts(c: Category) = rules.score(counts, c)
        if (open(Category.YACHT) && pts(Category.YACHT) > 0) return Category.YACHT
        val upper = Category.ALL.filter { it.isUpper && open(it) && counts[it.ordinal] >= 3 }.maxByOrNull { it.face }
        if (upper != null) return upper
        val lower = listOf(Category.LARGE_STRAIGHT, Category.SMALL_STRAIGHT, Category.FULL_HOUSE, Category.FOUR_OF_A_KIND)
            .filter { open(it) && pts(it) > 0 }.maxByOrNull { pts(it) }
        if (lower != null) return lower
        if (open(Category.CHOICE) && pts(Category.CHOICE) >= 22) return Category.CHOICE
        val anyUpper = Category.ALL.filter { it.isUpper && open(it) && pts(it) > 0 }.maxByOrNull { pts(it) }
        if (anyUpper != null) return anyUpper
        if (open(Category.CHOICE)) return Category.CHOICE
        return SCRATCH.first { open(it) }
    }

    companion object {
        private val SCRATCH = listOf(
            Category.ACES, Category.YACHT, Category.DEUCES, Category.FOUR_OF_A_KIND, Category.LARGE_STRAIGHT,
            Category.THREES, Category.FULL_HOUSE, Category.SMALL_STRAIGHT, Category.FOURS, Category.FIVES,
            Category.SIXES, Category.CHOICE,
        )
    }
}

/**
 * Max-expectation play that, at each decision, takes the second-best option with probability
 * [slip]: a strong player who sometimes misjudges.
 */
class SlipPlayer(
    private val ev: EvTable,
    private val slip: Double,
    private val rng: Random,
    override val name: String = "slip",
) : Player {
    private lateinit var plan: TurnPlan
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
        plan = TurnPlan(me, ExpectedAfter(ev, me.mask))
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val best = plan.bestKeep(rollsDone, r)
        if (rng.nextDouble() >= slip) return best
        val second = (Dice.subStart[r] until Dice.subStart[r + 1]).map { Dice.subKeep[it] }
            .filter { it != best }.maxByOrNull { plan.keepValue(rollsDone, it) }
        return second ?: best
    }

    override fun chooseCategory(r: Int): Category {
        val best = plan.bestCategory(r)
        if (rng.nextDouble() >= slip) return best
        return me.openCategories.filter { it != best }.maxByOrNull { plan.categoryValue(r, it.ordinal) } ?: best
    }
}

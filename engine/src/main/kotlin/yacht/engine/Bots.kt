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
 * Follows a turn plan, but at each decision takes the second-best option with probability
 * [slip]: a strong player who sometimes misjudges. The plan comes from [planner] (max
 * expectation by default, or e.g. the win search).
 */
class SlipPlayer(
    private val planner: (Board, Board) -> TurnPlan,
    private val slip: Double,
    private val rng: Random,
    override val name: String = "slip",
) : Player {
    constructor(ev: EvTable, slip: Double, rng: Random, name: String = "slip") :
        this({ me, _ -> TurnPlan(me, ExpectedAfter(ev, me.mask)) }, slip, rng, name)

    private lateinit var plan: TurnPlan
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
        plan = planner(me, opp)
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

/** Plays the best moves of the turn plan [planner] builds each turn (any end-of-turn valuation). */
class PlanPlayer(override val name: String, private val planner: (Board, Board) -> TurnPlan) : Player {
    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        plan = planner(me, opp)
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = plan.bestKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

/**
 * Max-expectation planning with a distorted view of the board: the end of a turn is worth
 * points + bonus + [extra] + [future] times the expected rest. [future] < 1 is short-sighted,
 * > 1 over-plans; [extra] adds a taste (for Yachts, straights, the upper section, ...).
 */
fun shapedPlanner(
    ev: EvTable,
    future: Double = 1.0,
    extra: (me: Board, c: Category, points: Int, u2: Int) -> Double = { _, _, _, _ -> 0.0 },
): (Board, Board) -> TurnPlan = { me, _ ->
    TurnPlan(me) { c, points, bonus, u2 ->
        points + bonus + extra(me, Category.of(c), points, u2) + future * ev[me.mask or (1 shl c), u2]
    }
}

/**
 * Plays for the exponential utility E[exp(theta * points)] (risk seeking for theta > 0, averse
 * below 0); [evTheta] is TableBuilder's table for that theta (sign(theta) * E[exp(theta * rest)]).
 */
fun riskPlanner(evTheta: EvTable, theta: Double): (Board, Board) -> TurnPlan = { me, _ ->
    TurnPlan(me) { c, points, bonus, u2 -> Math.exp(theta * (points + bonus)) * evTheta[me.mask or (1 shl c), u2] }
}

/**
 * A player who goes by feel: every option is taken with probability proportional to
 * exp(value / [tau]) under max-expectation values (in points), so close calls are coin flips and
 * clear mistakes are rare. Small [tau] plays almost perfectly.
 */
class SoftmaxPlayer(private val ev: EvTable, private val tau: Double, private val rng: Random, override val name: String = "soft") : Player {
    private lateinit var plan: TurnPlan
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
        plan = TurnPlan(me, ExpectedAfter(ev, me.mask))
    }

    private fun <T> pick(options: List<T>, value: (T) -> Double): T {
        val vs = options.map(value)
        val top = vs.max()
        val w = vs.map { Math.exp((it - top) / tau) }
        var x = rng.nextDouble() * w.sum()
        for (i in options.indices) {
            x -= w[i]
            if (x <= 0) return options[i]
        }
        return options.last()
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val keeps = (Dice.subStart[r] until Dice.subStart[r + 1]).map { Dice.subKeep[it] }
        // Holding all five stops the turn: worth the best box now.
        return pick(keeps) { k -> if (k == Dice.rollAsKeep[r]) plan.rollValue(3, r) else plan.keepValue(rollsDone, k) }
    }

    override fun chooseCategory(r: Int): Category = pick(me.openCategories) { plan.categoryValue(r, it.ordinal) }
}

/** Never throws again: writes the first roll where it scores the most this turn. */
class LazyPlayer(override val name: String = "lazy") : Player {
    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        plan = TurnPlan(me) { _, points, bonus, _ -> (points + bonus).toDouble() }
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = Dice.rollAsKeep[r]
    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

/**
 * Always keeps the most common face (the higher one on ties) and throws the rest, chasing a
 * Yacht; writes where it scores the most now, or with [upperFirst] three or more of a face into
 * that face's upper box first.
 */
class KeeperPlayer(private val rules: RuleSet, private val upperFirst: Boolean, override val name: String = "keeper") : Player {
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val counts = Dice.rollCounts[r]
        val face = (5 downTo 0).maxBy { counts[it] }
        if (counts[face] == 5) return Dice.rollAsKeep[r]
        val keep = IntArray(6)
        keep[face] = counts[face]
        return Dice.keepIndex(keep)
    }

    override fun chooseCategory(r: Int): Category {
        val counts = Dice.rollCounts[r]
        val open = me.openCategories
        if (upperFirst) {
            open.filter { it.isUpper && counts[it.ordinal] >= 3 }.maxByOrNull { it.face }?.let { return it }
        }
        val best = open.maxBy { rules.score(counts, it) }
        if (rules.score(counts, best) > 0) return best
        return open.minBy { SCRATCH_ORDER.indexOf(it) }
    }

    private companion object {
        val SCRATCH_ORDER = listOf(
            Category.ACES, Category.YACHT, Category.DEUCES, Category.FOUR_OF_A_KIND, Category.LARGE_STRAIGHT,
            Category.THREES, Category.FULL_HOUSE, Category.SMALL_STRAIGHT, Category.FOURS, Category.FIVES,
            Category.SIXES, Category.CHOICE,
        )
    }
}

/** Throws like the max-expectation plan but writes wherever the dice score the most right now. */
class HalfEvPlayer(private val ev: EvTable, private val rules: RuleSet, override val name: String = "half-ev") : Player {
    private lateinit var plan: TurnPlan
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
        plan = TurnPlan(me, ExpectedAfter(ev, me.mask))
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = plan.bestKeep(rollsDone, r)

    override fun chooseCategory(r: Int): Category {
        val counts = Dice.rollCounts[r]
        return me.openCategories.maxWith(compareBy<Category> { rules.score(counts, it) }.thenBy { plan.categoryValue(r, it.ordinal) })
    }
}

/** Keeps each die with probability 1/2 and writes into a random open box: the floor of the league. */
class RandomPlayer(private val rng: Random, override val name: String = "random") : Player {
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val keep = IntArray(6)
        for (f in 0 until 6) repeat(Dice.rollCounts[r][f]) { if (rng.nextBoolean()) keep[f]++ }
        return Dice.keepIndex(keep)
    }

    override fun chooseCategory(r: Int): Category = me.openCategories[rng.nextInt(me.openCategories.size)]
}

/** The win search's plan, for [SlipPlayer] (a win AI that sometimes slips). */
fun winPlanner(advisor: Advisor, budgetOps: Long): (Board, Board) -> TurnPlan = { me, opp ->
    advisor.planTurn(me, opp, Mode.WIN, budgetOps).active
}

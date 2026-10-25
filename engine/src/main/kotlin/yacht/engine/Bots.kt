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
 * expectation by default, or e.g. the win search). [slipOf] sets the chance per turn instead
 * (tired, tilted, moody players); [keeps]/[boxes] choose which decisions can slip.
 */
class SlipPlayer(
    private val planner: (Board, Board) -> TurnPlan,
    private val slip: Double,
    private val rng: Random,
    override val name: String = "slip",
    private val keeps: Boolean = true,
    private val boxes: Boolean = true,
    private val slipOf: ((me: Board, opp: Board) -> Double)? = null,
    /** A separate chance for box choices (e.g. a player's own rate); null = the same as for dice. */
    private val boxSlip: Double? = null,
) : Player {
    constructor(ev: EvTable, slip: Double, rng: Random, name: String = "slip") :
        this({ me, _ -> TurnPlan(me, ExpectedAfter(ev, me.mask)) }, slip, rng, name)

    private lateinit var plan: TurnPlan
    private lateinit var me: Board
    private var now = slip

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
        plan = planner(me, opp)
        now = slipOf?.invoke(me, opp) ?: slip
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val best = plan.bestKeep(rollsDone, r)
        if (!keeps || rng.nextDouble() >= now) return best
        val second = (Dice.subStart[r] until Dice.subStart[r + 1]).map { Dice.subKeep[it] }
            .filter { it != best }.maxByOrNull { plan.keepValue(rollsDone, it) }
        return second ?: best
    }

    override fun chooseCategory(r: Int): Category {
        val best = plan.bestCategory(r)
        if (!boxes || rng.nextDouble() >= (boxSlip ?: now)) return best
        return me.openCategories.filter { it != best }.maxByOrNull { plan.categoryValue(r, it.ordinal) } ?: best
    }
}

/** Plays like [early] until only [lateFrom] boxes are left, then like [late]. */
class PhasePlayer(override val name: String, private val early: Player, private val late: Player, private val lateFrom: Int) : Player {
    private var active: Player = early

    override fun startTurn(me: Board, opp: Board) {
        active = if (Category.COUNT - me.filledCount <= lateFrom) late else early
        active.startTurn(me, opp)
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = active.chooseKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = active.chooseCategory(r)
}

/**
 * Reads the scoreboard each turn: by the expected final scores (points so far plus
 * max-expectation of the rest) it plays [ahead] when leading by at least [margin], [behind]
 * when trailing by that much, and max expectation in between. Null planners mean max expectation.
 */
class SituationalPlayer(
    override val name: String,
    private val ev: EvTable,
    private val margin: Double,
    private val ahead: ((Board, Board) -> TurnPlan)?,
    private val behind: ((Board, Board) -> TurnPlan)?,
) : Player {
    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        fun expected(b: Board) = b.total + if (b.isComplete) 0.0 else ev[b.mask, b.u]
        val lead = expected(me) - expected(opp)
        val pick = when {
            lead >= margin -> ahead
            lead <= -margin -> behind
            else -> null
        }
        plan = pick?.invoke(me, opp) ?: TurnPlan(me, ExpectedAfter(ev, me.mask))
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = plan.bestKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

/** Max-expectation play, but never throws more than [maxThrows] times in a turn (a habit). */
class LimitedThrowsPlayer(private val ev: EvTable, private val maxThrows: Int, override val name: String = "throws$maxThrows") : Player {
    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        plan = TurnPlan(me, ExpectedAfter(ev, me.mask))
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = if (rollsDone >= maxThrows) Dice.rollAsKeep[r] else plan.bestKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

/** Keeps the most common face like [KeeperPlayer], but writes its box like the max-expectation plan. */
class KeeperEvBoxPlayer(private val ev: EvTable, override val name: String = "keeper-ev") : Player {
    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        plan = TurnPlan(me, ExpectedAfter(ev, me.mask))
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val counts = Dice.rollCounts[r]
        val face = (5 downTo 0).maxBy { counts[it] }
        if (counts[face] == 5) return Dice.rollAsKeep[r]
        val keep = IntArray(6)
        keep[face] = counts[face]
        return Dice.keepIndex(keep)
    }

    override fun chooseCategory(r: Int) = plan.bestCategory(r)
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
 * exp(value / [tau]) under the values of [planner] (max expectation by default, in points), so
 * close calls are coin flips and clear mistakes are rare. Small [tau] plays almost perfectly.
 */
class SoftmaxPlayer(
    private val planner: (Board, Board) -> TurnPlan,
    private val tau: Double,
    private val rng: Random,
    override val name: String = "soft",
) : Player {
    constructor(ev: EvTable, tau: Double, rng: Random, name: String = "soft") :
        this({ me, _ -> TurnPlan(me, ExpectedAfter(ev, me.mask)) }, tau, rng, name)

    private lateinit var plan: TurnPlan
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
        plan = planner(me, opp)
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

/**
 * Fills the boxes in a fixed [order]: each turn aims only at the first open box of the list,
 * holding dice for the most points there (the classic beginner's top-to-bottom sheet).
 */
class OrderPlayer(private val order: List<Category>, override val name: String = "order") : Player {
    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        val target = order.first { !me.isFilled(it) }.ordinal
        plan = TurnPlan(me) { c, points, bonus, _ -> if (c == target) (points + bonus).toDouble() else -1e9 }
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = plan.bestKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

/**
 * Plays max expectation under rules it believes ([belief], with the same bonus threshold), with
 * [beliefEv] the expectation table for those rules: a player who misremembers a rule.
 */
class BeliefPlayer(private val belief: RuleSet, private val beliefEv: EvTable, override val name: String = "belief") : Player {
    init {
        require(belief.upperStates == beliefEv.rules.upperStates)
    }

    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        val seen = Board.of(belief, me.toList())
        plan = TurnPlan(seen, ExpectedAfter(beliefEv, seen.mask))
    }

    override fun chooseKeep(rollsDone: Int, r: Int) = plan.bestKeep(rollsDone, r)
    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

/**
 * Ignores the opponent and maximises P(final score >= [target]) with the win search, as if the
 * opponent had already finished on [target] - 1.
 */
fun targetPlanner(advisor: Advisor, target: Int, budgetOps: Long): (Board, Board) -> TurnPlan = { me, _ ->
    val goal = Board.of(me.rules, Category.ALL.map { if (it == Category.CHOICE) target - 1 else 0 })
    advisor.planTurn(me, goal, Mode.WIN, budgetOps).active
}

/** Always holds its longest run of faces, chasing straights; writes like the max-expectation plan. */
class StraightChaserPlayer(private val ev: EvTable, override val name: String = "straight-chaser") : Player {
    private lateinit var plan: TurnPlan

    override fun startTurn(me: Board, opp: Board) {
        plan = TurnPlan(me, ExpectedAfter(ev, me.mask))
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val counts = Dice.rollCounts[r]
        var bestStart = 0
        var bestLen = 0
        var start = 0
        for (f in 0..6) {
            if (f < 6 && counts[f] > 0) continue
            if (f - start > bestLen) {
                bestLen = f - start
                bestStart = start
            }
            start = f + 1
        }
        if (bestLen == 5) return Dice.rollAsKeep[r]
        val keep = IntArray(6)
        for (f in bestStart until bestStart + bestLen) keep[f] = 1
        return Dice.keepIndex(keep)
    }

    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

/**
 * Believes in luck, not in holding dice: keeps the roll when some box scores at least [enough]
 * now, otherwise throws all five again; writes like the max-expectation plan.
 */
class LuckBelieverPlayer(private val ev: EvTable, private val enough: Int = 25, override val name: String = "luck") : Player {
    private lateinit var plan: TurnPlan
    private lateinit var me: Board

    override fun startTurn(me: Board, opp: Board) {
        this.me = me
        plan = TurnPlan(me, ExpectedAfter(ev, me.mask))
    }

    override fun chooseKeep(rollsDone: Int, r: Int): Int {
        val best = me.openCategories.maxOf { plan.points(r, it.ordinal) }
        return if (best >= enough) Dice.rollAsKeep[r] else Dice.EMPTY_KEEP
    }

    override fun chooseCategory(r: Int) = plan.bestCategory(r)
}

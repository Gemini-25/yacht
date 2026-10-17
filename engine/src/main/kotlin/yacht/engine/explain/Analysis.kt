package yacht.engine.explain

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.PlannedTurn
import yacht.engine.Tables
import kotlin.math.sqrt

/**
 * How a turn ends: the box written and roughly how well. Upper boxes split into 0 dice, one or two
 * dice (below the three-per-face pace of the bonus) and three or more; other boxes into 0 points
 * or made.
 */
internal object Outcome {
    const val ZERO = 0
    const val FEW = 1
    const val MADE = 2
    const val COUNT = Category.COUNT * 3

    fun of(c: Int, r: Int, points: Int): Int {
        val kind = when {
            points == 0 -> ZERO
            c < 6 && Dice.rollCounts[r][c] < 3 -> FEW
            else -> MADE
        }
        return c * 3 + kind
    }

    fun category(o: Int): Category = Category.of(o / 3)
    fun kind(o: Int): Int = o % 3
}

/**
 * What happens in the rest of this turn after one decision, following the plan: the probability
 * of each [Outcome], the plan's value inside each, points, and my final score under
 * max-expectation play afterwards.
 */
class Profile internal constructor(
    internal val prob: DoubleArray,
    internal val valueMass: DoubleArray,
    internal val pointsMass: DoubleArray,
    /** Plan value (P(win) or expected final score) of the decision. */
    val value: Double,
    /** Expected final score with max-expectation play after this turn. */
    val expectedFinal: Double,
    /** Standard deviation of that final score; NaN without the distribution table. */
    val finalSd: Double,
    /** P(upper bonus) at the end of the game; NaN without the bonus table. */
    val bonusProb: Double,
) {
    internal fun p(o: Int) = prob[o]
    internal fun cond(o: Int) = if (prob[o] > 1e-12) valueMass[o] / prob[o] else Double.NaN
    internal fun points(o: Int) = if (prob[o] > 1e-12) pointsMass[o] / prob[o] else Double.NaN
}

/** Facts about writing the final dice of the turn into one box. */
class CategoryFacts internal constructor(
    val category: Category,
    val points: Int,
    /** Upper bonus completed by this box right now (0 or the bonus points). */
    val bonusNow: Int,
    /** Expected remaining points afterwards (max-expectation play). */
    val future: Double,
    /** P(upper bonus) afterwards; NaN without the bonus table. */
    val bonusProb: Double,
    val value: Double,
    val expectedFinal: Double,
)

/** Turn-level facts for one plan; cheap (a few forward passes), built on demand for explanations. */
class TurnAnalysis(private val tables: Tables, val turn: PlannedTurn) {
    val me: Board = turn.me
    private val plan = turn.active
    private val rules = tables.rules
    private val cap = rules.bonusThreshold
    private val scores = tables.scores
    private val leafCache = java.util.concurrent.ConcurrentHashMap<Int, DoubleArray>()

    /** After [rollsDone] throws holding [keep] (holding all five = stop now). */
    fun keepProfile(rollsDone: Int, keep: Int): Profile = profileOf(plan.finalRolls(rollsDone, keep))

    /** The turn ends now with roll [r]. */
    fun stopProfile(r: Int): Profile = profileOf(DoubleArray(Dice.ROLLS).also { it[r] = 1.0 })

    /** P(the turn ends worse than [value]) after holding [keep] following [rollsDone] throws. */
    fun probWorse(rollsDone: Int, keep: Int, value: Double): Double {
        val final = plan.finalRolls(rollsDone, keep)
        val eps = 1e-9 * maxOf(1.0, kotlin.math.abs(value))
        var p = 0.0
        for (r in 0 until Dice.ROLLS) {
            if (final[r] == 0.0) continue
            val v = turn.shown(plan.categoryValue(r, plan.bestCategory(r).ordinal))
            if (v < value - eps) p += final[r]
        }
        return p
    }

    fun category(r: Int, c: Category): CategoryFacts {
        val pts = scores[r, c.ordinal]
        val (bonus, u2) = after(c.ordinal, pts)
        val child = me.mask or c.bit
        return CategoryFacts(
            c, pts, bonus, tables.ev[child, u2], bonusAt(child, u2),
            turn.shown(plan.categoryValue(r, c.ordinal)), me.total + pts + bonus + tables.ev[child, u2],
        )
    }

    /** P(upper bonus) now, with max-expectation play; NaN without the table. */
    val bonusProbNow: Double get() = bonusAt(me.mask, me.u)

    private fun after(c: Int, pts: Int): Pair<Int, Int> {
        if (c >= 6) return 0 to me.u
        val u = me.u
        val bonus = if (u < cap && u + pts >= cap) rules.bonusPoints else 0
        return bonus to minOf(cap, u + pts)
    }

    private fun bonusAt(mask: Int, u: Int): Double = tables.bonus?.get(mask, u) ?: Double.NaN

    /** [mean, variance] of remaining points from a state under max-expectation play. */
    private fun leaf(mask: Int, u: Int): DoubleArray? {
        val dist = tables.dist ?: return null
        if (mask == Category.FULL_MASK) return doubleArrayOf(0.0, 0.0)
        val key = mask * rules.upperStates + u
        return leafCache.getOrPut(key) {
            val p = dist.pmf(mask, u)
            val lo = dist.lo(mask, u)
            var m1 = 0.0
            var m2 = 0.0
            for (i in p.indices) {
                val x = (lo + i).toDouble()
                m1 += p[i] * x
                m2 += p[i] * x * x
            }
            doubleArrayOf(m1, m2 - m1 * m1)
        }
    }

    private fun profileOf(final: DoubleArray): Profile {
        val prob = DoubleArray(Outcome.COUNT)
        val valueMass = DoubleArray(Outcome.COUNT)
        val pointsMass = DoubleArray(Outcome.COUNT)
        var value = 0.0
        var ef = 0.0
        var m1 = 0.0
        var m2 = 0.0
        var sdKnown = tables.dist != null
        var pb = 0.0
        for (r in 0 until Dice.ROLLS) {
            val p = final[r]
            if (p == 0.0) continue
            val c = plan.bestCategory(r).ordinal
            val pts = scores[r, c]
            val v = turn.shown(plan.categoryValue(r, c))
            val o = Outcome.of(c, r, pts)
            prob[o] += p
            valueMass[o] += p * v
            pointsMass[o] += p * pts
            value += p * v
            val (bonus, u2) = after(c, pts)
            val child = me.mask or (1 shl c)
            val gain = pts + bonus
            ef += p * (gain + tables.ev[child, u2])
            val l = leaf(child, u2)
            if (l == null) sdKnown = false else {
                val x = gain + l[0]
                m1 += p * x
                m2 += p * (l[1] + x * x)
            }
            pb += p * bonusAt(child, u2)
        }
        val sd = if (sdKnown) sqrt((m2 - m1 * m1).coerceAtLeast(0.0)) else Double.NaN
        return Profile(prob, valueMass, pointsMass, value, me.total + ef, sd, pb)
    }

    companion object {
        private val chaseCache = HashMap<Pair<Category, yacht.engine.RuleSet>, Double>()

        /**
         * P(making [c] with a non-zero score in one turn when that is the only goal): e.g. about
         * 4.6% for a Yacht.
         */
        fun chance(tables: Tables, c: Category): Double = synchronized(chaseCache) {
            chaseCache.getOrPut(c to tables.rules) {
                val onlyOpen = Board.of(tables.rules, Category.ALL.map { if (it == c) null else 0 })
                val plan = yacht.engine.TurnPlan(onlyOpen) { _, points, _, _ -> if (points > 0) 1.0 else 0.0 }
                plan.startValue
            }
        }
    }
}

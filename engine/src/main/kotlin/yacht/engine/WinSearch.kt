package yacht.engine

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/** P(win) as a function of my final score, against an opponent playing for max expectation. */
object WinCurve {
    const val SIZE = 512

    /**
     * Tie-break weight per point: among moves with (nearly) equal P(win) — e.g. once the game is
     * decided — prefer more points. Adds at most ~0.03%p to displayed probabilities.
     */
    const val SCORE_WEIGHT = 1e-6

    /** curve[f] = P(opp final < f) + tieValue * P(opp final == f) + SCORE_WEIGHT * f. */
    fun against(tables: Tables, opp: Board, tieValue: Double = 0.0): DoubleArray {
        val base = opp.total
        val pmf: DoubleArray
        val lo: Int
        if (opp.isComplete) {
            pmf = doubleArrayOf(1.0)
            lo = 0
        } else {
            val dist = requireNotNull(tables.dist) { "win mode needs the distribution table" }
            pmf = dist.pmf(opp.mask, opp.u)
            lo = dist.lo(opp.mask, opp.u)
        }
        val mass = pmf.sum()
        val curve = DoubleArray(SIZE)
        var below = 0.0
        for (f in 0 until SIZE) {
            val x = f - base - lo
            val p = if (x in pmf.indices) pmf[x] / mass else 0.0
            curve[f] = below + tieValue * p + SCORE_WEIGHT * f
            below += p
        }
        return curve
    }
}

/**
 * Maximises P(win) for the current turn. The next [depth] - 1 turns are solved exactly with my
 * running score as part of the state; beyond that the max-expectation policy's score
 * distribution is used. With depth == remaining turns the result is exact for the given
 * opponent model.
 */
/** Thrown when a search passes its deadline; the caller keeps its previous (shallower) plan. */
class SearchTimeout : RuntimeException("search deadline passed", null, false, false)

class WinSearch(
    private val tables: Tables,
    private val me: Board,
    private val curve: DoubleArray,
    val depth: Int,
    private val pool: ExecutorService? = null,
    /** System.nanoTime() after which the search gives up with [SearchTimeout]. */
    private val deadline: Long = Long.MAX_VALUE,
) : AfterTurn {
    private val rules = me.rules
    private val cap = rules.bonusThreshold
    private val scores = tables.scores
    private val mask0 = me.mask
    private val u0 = me.u
    private val s0 = me.total
    private val level0 = Integer.bitCount(mask0)

    /** Bonus already earned or out of reach: the upper sum stops mattering. */
    private val bonusDecided = u0 >= cap || u0 + remainingUpperMax(mask0) < cap

    private class Grid(val nU: Int, val nS: Int, val values: DoubleArray)

    private val grids = arrayOfNulls<Grid>(Category.FULL_MASK + 1)

    init {
        require(depth >= 1)
        require(!me.isComplete)
        val remaining = Category.COUNT - level0
        val d = minOf(depth, remaining)
        val levels = masksByLevel(mask0)
        for (j in d downTo 1) {
            val masks = levels[j].filter { it != Category.FULL_MASK }
            val leaf = j == d
            if (pool == null || masks.size < 2) {
                val kernel = TurnKernel()
                for (m in masks) {
                    checkDeadline()
                    grids[m] = if (leaf) leafGrid(m) else interiorGrid(m, kernel)
                }
            } else {
                val kernels = ThreadLocal.withInitial { TurnKernel() }
                val tasks = masks.map { m ->
                    Callable {
                        checkDeadline()
                        grids[m] = if (leaf) leafGrid(m) else interiorGrid(m, kernels.get())
                    }
                }
                for (f in pool.invokeAll(tasks)) {
                    try {
                        f.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause ?: e
                    }
                }
            }
        }
    }

    private fun checkDeadline() {
        if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) throw SearchTimeout()
    }

    private fun remainingUpperMax(mask: Int): Int =
        (0 until 6).sumOf { if (mask and (1 shl it) == 0) 5 * (it + 1) else 0 }

    private fun collapsed(m: Int) = bonusDecided || (m and Category.UPPER_MASK) == Category.UPPER_MASK

    private fun nU(m: Int) = if (collapsed(m)) 1 else cap - u0 + 1

    /** Most points that can have been added since the root by the time mask [m] is filled. */
    private fun gainMax(m: Int): Int {
        val added = m and mask0.inv()
        var g = 0
        for (c in 0 until Category.COUNT) if (added and (1 shl c) != 0) g += scores.maxPoints[c]
        if (!bonusDecided && added and Category.UPPER_MASK != 0) g += rules.bonusPoints
        return g
    }

    private fun curveAt(s: Int) = if (s < curve.size) curve[s] else curve[curve.size - 1]

    /** Win probability once mask [m] is filled with capped upper sum [u] and score [s]. */
    fun valueOf(m: Int, u: Int, s: Int): Double {
        if (m == Category.FULL_MASK) return curveAt(s)
        val g = grids[m] ?: error("mask $m outside search")
        val ui = if (g.nU == 1) 0 else u - u0
        val si = minOf(s - s0, g.nS - 1)
        return g.values[ui * g.nS + si]
    }

    override fun value(c: Int, points: Int, bonus: Int, u2: Int): Double =
        valueOf(mask0 or (1 shl c), u2, s0 + points + bonus)

    /** Leaves continue with whichever stored policy (max expectation or a risk attitude) wins most. */
    private fun leafGrid(m: Int): Grid {
        val dists = tables.leafDists
        require(dists.isNotEmpty()) { "depth-limited search needs the distribution table" }
        val nU = nU(m)
        val nS = gainMax(m) + 1
        val values = DoubleArray(nU * nS) { Double.NEGATIVE_INFINITY }
        for (ui in 0 until nU) {
            val u = if (nU == 1) (if (u0 >= cap) cap else u0) else u0 + ui
            for (dist in dists) {
                val pmf = dist.pmf(m, u)
                val lo = dist.lo(m, u)
                for (si in 0 until nS) {
                    val base = s0 + si + lo
                    var v = 0.0
                    for (x in pmf.indices) v += pmf[x] * curveAt(base + x)
                    if (v > values[ui * nS + si]) values[ui * nS + si] = v
                }
            }
        }
        return Grid(nU, nS, values)
    }

    private fun interiorGrid(m: Int, kernel: TurnKernel): Grid {
        val nU = nU(m)
        val nS = gainMax(m) + 1
        val n = nU * nS
        kernel.resize(n)
        val e3 = kernel.e3
        java.util.Arrays.fill(e3, 0, Dice.ROLLS * n, Double.NEGATIVE_INFINITY)
        for (c in 0 until Category.COUNT) {
            if (m and (1 shl c) != 0) continue
            val child = m or (1 shl c)
            val full = child == Category.FULL_MASK
            val g = grids[child]
            for (r in 0 until Dice.ROLLS) {
                val pts = scores[r, c]
                val rb = r * n
                for (ui in 0 until nU) {
                    val u = if (nU == 1) u0 else u0 + ui
                    var gain = pts
                    var u2 = u
                    if (c < 6 && nU > 1) {
                        u2 = minOf(cap, u + pts)
                        if (u < cap && u + pts >= cap) gain += rules.bonusPoints
                    }
                    val ob = rb + ui * nS
                    if (full) {
                        for (si in 0 until nS) {
                            val v = curveAt(s0 + si + gain)
                            if (v > e3[ob + si]) e3[ob + si] = v
                        }
                    } else {
                        g!!
                        val cb = (if (g.nU == 1) 0 else u2 - u0) * g.nS
                        val last = g.nS - 1
                        for (si in 0 until nS) {
                            val v = g.values[cb + minOf(si + gain, last)]
                            if (v > e3[ob + si]) e3[ob + si] = v
                        }
                    }
                }
            }
        }
        kernel.run()
        return Grid(nU, nS, kernel.start.copyOf(n))
    }

    companion object {
        /** Supersets of [mask0] grouped by how many more boxes they fill. */
        fun masksByLevel(mask0: Int): List<List<Int>> {
            val open = (0 until Category.COUNT).filter { mask0 and (1 shl it) == 0 }
            val levels = List(open.size + 1) { ArrayList<Int>() }
            for (sub in 0 until (1 shl open.size)) {
                var m = mask0
                for (i in open.indices) if (sub and (1 shl i) != 0) m = m or (1 shl open[i])
                levels[Integer.bitCount(sub)].add(m)
            }
            return levels
        }

        private val KERNEL_OPS =
            2L * (Dice.addFace.size + Dice.ROLLS) + 2L * (Dice.downKeep.size + Dice.KEEPS + Dice.ROLLS) + Dice.ROLLS

        /** Rough operation count of a search, used to pick the deepest affordable depth. */
        fun estimateOps(tables: Tables, me: Board, depth: Int): Long {
            val rules = me.rules
            val cap = rules.bonusThreshold
            val mask0 = me.mask
            val u0 = me.u
            val remainingUpper = (0 until 6).sumOf { if (mask0 and (1 shl it) == 0) 5 * (it + 1) else 0 }
            val decided = u0 >= cap || u0 + remainingUpper < cap
            val levels = masksByLevel(mask0)
            val d = minOf(depth, levels.size - 1)
            var ops = 0L
            for (j in 1..d) for (m in levels[j]) {
                if (m == Category.FULL_MASK) continue
                val nU = if (decided || (m and Category.UPPER_MASK) == Category.UPPER_MASK) 1 else cap - u0 + 1
                val added = m and mask0.inv()
                var g = 0
                for (c in 0 until Category.COUNT) if (added and (1 shl c) != 0) g += tables.scores.maxPoints[c]
                if (!decided && added and Category.UPPER_MASK != 0) g += rules.bonusPoints
                val contexts = nU.toLong() * (g + 1)
                ops += if (j == d) {
                    contexts * tables.leafDists.sumOf { it.len(m, minOf(u0, cap)).toLong() }
                } else {
                    val open = Category.COUNT - Integer.bitCount(m)
                    contexts * (KERNEL_OPS + Dice.ROLLS.toLong() * open)
                }
            }
            return ops
        }

        /** Deepest depth whose estimated cost fits in [budgetOps] (at least 1). */
        fun chooseDepth(tables: Tables, me: Board, budgetOps: Long): Int {
            val remaining = Category.COUNT - me.filledCount
            for (d in remaining downTo 2) if (estimateOps(tables, me, d) <= budgetOps) return d
            return 1
        }
    }
}

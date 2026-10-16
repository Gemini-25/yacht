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

/** Thrown when a search passes its deadline; the caller keeps its previous (shallower) plan. */
class SearchTimeout : RuntimeException("search deadline passed", null, false, false)

/**
 * Maximises P(win) for the current turn. The next [depth] - 1 turns are solved exactly with my
 * running score as part of the state; beyond that the max-expectation policy's score
 * distribution is used. With depth == remaining turns the result is exact for the given
 * opponent model.
 */
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
    private val bonusDecided = bonusDecided(me)

    /**
     * Values over (reachable capped upper sum, score since the root). [uIndex] maps a capped
     * upper sum to its row, or is null when the upper sum no longer matters (one row).
     */
    private class Grid(val uList: IntArray, val uIndex: IntArray?, val nS: Int, val values: DoubleArray) {
        fun row(u: Int): Int = if (uIndex == null) 0 else uIndex[u].also { check(it >= 0) { "unreachable u=$u" } }
    }

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

    private fun newGrid(m: Int, values: (IntArray, Int) -> DoubleArray): Grid {
        val uList = reachableUpper(me, bonusDecided, m)
        val uIndex = if (uList.size == 1 && collapses(m)) null else IntArray(cap + 1) { -1 }.also { idx ->
            uList.forEachIndexed { i, u -> idx[u] = i }
        }
        val nS = gainMax(tables, me, bonusDecided, m) + 1
        return Grid(uList, uIndex, nS, values(uList, nS))
    }

    /** The upper sum stops mattering once the bonus is decided or every upper box is filled. */
    private fun collapses(m: Int) = bonusDecided || (m and Category.UPPER_MASK) == Category.UPPER_MASK

    private fun curveAt(s: Int) = if (s < curve.size) curve[s] else curve[curve.size - 1]

    /** Win probability once mask [m] is filled with capped upper sum [u] and score [s]. */
    fun valueOf(m: Int, u: Int, s: Int): Double {
        if (m == Category.FULL_MASK) return curveAt(s)
        val g = grids[m] ?: error("mask $m outside search")
        return g.values[g.row(u) * g.nS + minOf(s - s0, g.nS - 1)]
    }

    override fun value(c: Int, points: Int, bonus: Int, u2: Int): Double =
        valueOf(mask0 or (1 shl c), u2, s0 + points + bonus)

    /** Leaves continue with whichever stored policy (max expectation or a risk attitude) wins most. */
    private fun leafGrid(m: Int): Grid = newGrid(m) { uList, nS ->
        val dists = tables.leafDists
        require(dists.isNotEmpty()) { "depth-limited search needs the distribution table" }
        val values = DoubleArray(uList.size * nS) { Double.NEGATIVE_INFINITY }
        for (ui in uList.indices) {
            val u = uList[ui]
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
        values
    }

    private fun interiorGrid(m: Int, kernel: TurnKernel): Grid = newGrid(m) { uList, nS ->
        val nU = uList.size
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
                    val u = uList[ui]
                    var gain = pts
                    var u2 = u
                    if (c < 6 && !bonusDecided) {
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
                        val cb = g.row(u2) * g.nS
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
        kernel.start.copyOf(n)
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

        /** Bonus already earned or out of reach from [me]: the upper sum stops mattering. */
        fun bonusDecided(me: Board): Boolean {
            val cap = me.rules.bonusThreshold
            val remainingUpper = (0 until 6).sumOf { if (me.mask and (1 shl it) == 0) 5 * (it + 1) else 0 }
            return me.u >= cap || me.u + remainingUpper < cap
        }

        /** Capped upper sums reachable once [m] is filled, starting from [me] (one value if irrelevant). */
        fun reachableUpper(me: Board, decided: Boolean, m: Int): IntArray {
            val cap = me.rules.bonusThreshold
            if (decided) return intArrayOf(minOf(me.u, cap))
            val added = m and me.mask.inv() and Category.UPPER_MASK
            if ((m and Category.UPPER_MASK) == Category.UPPER_MASK && added == 0) return intArrayOf(me.u)
            var set = BooleanArray(cap + 1).also { it[me.u] = true }
            for (c in 0 until 6) {
                if (added and (1 shl c) == 0) continue
                val next = BooleanArray(cap + 1)
                for (a in 0..cap) if (set[a]) for (k in 0..5) next[minOf(cap, a + k * (c + 1))] = true
                set = next
            }
            if ((m and Category.UPPER_MASK) == Category.UPPER_MASK) {
                // Every upper box filled: only "bonus earned or not" matters, already in the score.
                return intArrayOf(set.indexOfFirst { it })
            }
            return (0..cap).filter { set[it] }.toIntArray()
        }

        /** Most points that can have been added since [me] by the time mask [m] is filled. */
        fun gainMax(tables: Tables, me: Board, decided: Boolean, m: Int): Int {
            val added = m and me.mask.inv()
            var g = 0
            for (c in 0 until Category.COUNT) if (added and (1 shl c) != 0) g += tables.scores.maxPoints[c]
            if (!decided && added and Category.UPPER_MASK != 0) g += me.rules.bonusPoints
            return g
        }

        private val KERNEL_OPS =
            2L * (Dice.addFace.size + Dice.ROLLS) + 2L * (Dice.downKeep.size + Dice.KEEPS + Dice.ROLLS) + Dice.ROLLS

        /** Rough operation count of a search, used to pick the deepest affordable depth. */
        fun estimateOps(tables: Tables, me: Board, depth: Int): Long {
            val decided = bonusDecided(me)
            val levels = masksByLevel(me.mask)
            val d = minOf(depth, levels.size - 1)
            var ops = 0L
            for (j in 1..d) for (m in levels[j]) {
                if (m == Category.FULL_MASK) continue
                val uList = reachableUpper(me, decided, m)
                val contexts = uList.size.toLong() * (gainMax(tables, me, decided, m) + 1)
                ops += if (j == d) {
                    contexts * tables.leafDists.sumOf { it.len(m, uList[0]).toLong() }
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

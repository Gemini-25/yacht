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

    /**
     * values[f] = P(opp final < f) + tieValue * P(opp final == f) + SCORE_WEIGHT * f, and
     * ties[f] = P(opp final == f). The win part is 0 for f < oppMin and 1 for f > oppMax.
     */
    class Curve(val values: DoubleArray, val oppMin: Int, val oppMax: Int, val ties: DoubleArray = DoubleArray(values.size))

    /**
     * [oppShift]: points added to every opponent final (a player that misjudges the opponent's
     * strength). [model]: the opponent as its record shows it ([OppModel]) instead of a
     * max-expectation player; a finished opponent is its total either way.
     */
    fun against(tables: Tables, opp: Board, tieValue: Double = 0.0, oppShift: Int = 0, model: OppModel? = null): Curve {
        val base = opp.total + oppShift
        var pmf: DoubleArray
        var lo: Int
        if (opp.isComplete) {
            pmf = doubleArrayOf(1.0)
            lo = 0
        } else {
            val dist = requireNotNull(tables.dist) { "win mode needs the distribution table" }
            pmf = dist.pmf(opp.mask, opp.u)
            lo = dist.lo(opp.mask, opp.u)
            if (model != null) {
                val (p2, lo2) = model.reshape(tables, opp, pmf, lo)
                pmf = p2
                lo = lo2
            }
        }
        val mass = pmf.sum()
        val curve = DoubleArray(SIZE)
        val ties = DoubleArray(SIZE)
        var below = 0.0
        for (f in 0 until SIZE) {
            val x = f - base - lo
            val p = if (x in pmf.indices) pmf[x] / mass else 0.0
            curve[f] = below + tieValue * p + SCORE_WEIGHT * f
            ties[f] = p
            below += p
        }
        return Curve(curve, base + lo, base + lo + pmf.size - 1, ties)
    }
}

/**
 * An opponent as its record shows it (상대 맞춤), for the win search: its finals average [shift]
 * points above the max-expectation player's (negative: weaker) and spread [spread] times as
 * wide. From any of its states the remaining points of the max-expectation player are read so:
 * the mean moved by [shift] times the share of the game's points still to come, the spread
 * scaled toward [spread] by the share of the game's variance still to come (so a lumpy end game,
 * a Yacht or nothing, keeps its shape), points kept within what can still be scored. Made from
 * a record by [of]: null (the plain max-expectation opponent) with too few games, or when the
 * record is that of a max-expectation player.
 */
class OppModel(val shift: Double, val spread: Double = 1.0) {
    /** [pmf] over [lo, lo + pmf.size) of [opp]'s remaining points, reshaped; returns the new pmf and its lo. */
    fun reshape(tables: Tables, opp: Board, pmf: DoubleArray, lo: Int): Pair<DoubleArray, Int> {
        val dist = tables.dist ?: return pmf to lo
        val (rootMean, rootVar) = root(tables)
        val (m, v) = DistTable.moments(pmf, lo)
        if (rootMean <= 0.0) return pmf to lo
        val target = m + shift * (m / rootMean)
        val rho = maxOf(0.0, 1.0 + (spread - 1.0) * (if (rootVar > 0) v / rootVar else 0.0))
        val most = maxRemaining(tables, opp)
        val ys = DoubleArray(pmf.size) { i -> (target + rho * (lo + i - m)).coerceIn(0.0, most.toDouble()) }
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in ys) {
            minY = minOf(minY, Math.floor(y).toInt())
            maxY = maxOf(maxY, Math.ceil(y).toInt())
        }
        val out = DoubleArray(maxY - minY + 1)
        for (i in pmf.indices) {
            val y = ys[i]
            val f = Math.floor(y).toInt()
            val frac = y - f
            out[f - minY] += pmf[i] * (1 - frac)
            if (frac > 0) out[f + 1 - minY] += pmf[i] * frac
        }
        val sum = out.sum()
        if (!(sum > 0)) return pmf to lo
        for (i in out.indices) out[i] /= sum
        // Mass lost to rounding at the ends: the support starts and ends where there is some.
        var a = 0
        var b = out.size - 1
        while (a < b && out[a] < 1e-12) a++
        while (b > a && out[b] < 1e-12) b--
        return out.copyOfRange(a, b + 1) to minY + a
    }

    override fun equals(other: Any?): Boolean = other is OppModel && other.shift == shift && other.spread == spread
    override fun hashCode(): Int = shift.hashCode() * 31 + spread.hashCode()
    override fun toString(): String = "OppModel(shift=$shift, spread=$spread)"

    companion object {
        /** Games of an opponent before its record is used (fewer: the plain opponent). */
        const val MIN_GAMES = 30

        /** Games a record is weighed against (its mean and spread are shrunk toward max expectation by n / (n + this)). */
        const val PRIOR_GAMES = 30

        /** Smallest difference worth a model: half a point of mean, 2% of spread. */
        private const val MIN_SHIFT = 0.5
        private const val MIN_SPREAD = 0.02

        /**
         * The model of an opponent with [games] finals averaging [mean] with deviation [sd] (NaN:
         * the mean only), against [tables]' max-expectation player; null when it would say nothing
         * (a difference within chance: two standard errors). Rounded (half points, 2% of spread)
         * so that small changes of a record do not change it.
         */
        fun of(tables: Tables, games: Int, mean: Double, sd: Double = Double.NaN): OppModel? {
            if (tables.dist == null || games < MIN_GAMES || mean.isNaN()) return null
            val (rootMean, rootVar) = root(tables)
            val w = games.toDouble() / (games + PRIOR_GAMES)
            val rootSd = Math.sqrt(rootVar)
            // Only a difference beyond chance: two standard errors of the mean (sd/√n) and of the
            // deviation (about sd/√(2n)); a max-expectation player's record wanders that much.
            val diff = (mean - rootMean).takeIf { Math.abs(it) > 2 * rootSd / Math.sqrt(games.toDouble()) } ?: 0.0
            val shift = Math.round(diff * w * 2) / 2.0
            val r0 = if (sd.isNaN() || sd <= 0 || rootSd <= 0) 1.0 else sd / rootSd
            val ratio = if (Math.abs(r0 - 1.0) > 2 / Math.sqrt(2.0 * games)) 1.0 + (r0 - 1.0) * w else 1.0
            val spread = Math.round(ratio.coerceIn(0.5, 2.0) * 50) / 50.0
            if (Math.abs(shift) < MIN_SHIFT && Math.abs(spread - 1.0) < MIN_SPREAD) return null
            return OppModel(shift, spread)
        }

        /** The max-expectation player's whole game: mean and variance of its final (cached per tables). */
        fun root(tables: Tables): Pair<Double, Double> = synchronized(roots) {
            roots.getOrPut(tables.dist ?: return 0.0 to 0.0) { tables.dist.moments(0, 0) }
        }

        private val roots = java.util.WeakHashMap<DistTable, Pair<Double, Double>>()

        /** Most points [b] can still score (bonus included while still open). */
        fun maxRemaining(tables: Tables, b: Board): Int {
            var g = 0
            for (c in 0 until Category.COUNT) if (b.mask and (1 shl c) == 0) g += tables.scores.maxPoints[c]
            if (!WinSearch.bonusDecided(b)) g += b.rules.bonusPoints
            return g
        }
    }
}

/** Thrown when a search passes its deadline or is cancelled; callers keep their previous plan. */
class SearchTimeout : RuntimeException("search stopped", null, false, false)

/**
 * Maximises P(win) for the current turn. The next [depth] - 1 turns are solved exactly with my
 * running score as part of the state; beyond that the max-expectation policy's score
 * distribution is used. With depth == remaining turns the result is exact for the given
 * opponent model.
 *
 * Scores whose outcome is already certain (ahead of the opponent's best final, or unable to
 * reach its worst) are filled in closed form instead of searched.
 *
 * With [ties] the search also carries P(tie) along its own decisions (see [BlockKernel]): the
 * exact probability of a draw when this plan is followed, at about twice the cost.
 */
class WinSearch(
    private val tables: Tables,
    private val me: Board,
    private val win: WinCurve.Curve,
    val depth: Int,
    private val pool: ExecutorService? = null,
    /** System.nanoTime() after which the search gives up with [SearchTimeout]. */
    private val deadline: Long = Long.MAX_VALUE,
    private val stop: java.util.concurrent.atomic.AtomicBoolean? = null,
    val ties: Boolean = false,
) : AfterTurn {
    private val curve = win.values
    private val tieCurve = win.ties
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
    private class Grid(val uList: IntArray, val uIndex: IntArray?, val nS: Int, val values: DoubleArray, val ties: DoubleArray?) {
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
                val kernel = BlockKernel()
                for (m in masks) {
                    checkDeadline()
                    grids[m] = if (leaf) leafGrid(m) else interiorGrid(m, kernel)
                }
            } else {
                // Work buffers shared by this search's tasks and dropped with it (a ThreadLocal on
                // the app's long-lived pool kept every search's buffers alive: hundreds of MB).
                val kernels = java.util.concurrent.ConcurrentLinkedQueue<BlockKernel>()
                val tasks = masks.map { m ->
                    Callable {
                        checkDeadline()
                        if (leaf) {
                            grids[m] = leafGrid(m)
                        } else {
                            val kernel = kernels.poll() ?: BlockKernel()
                            try {
                                grids[m] = interiorGrid(m, kernel)
                            } finally {
                                kernels.add(kernel)
                            }
                        }
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
        if (stop?.get() == true || (deadline != Long.MAX_VALUE && System.nanoTime() > deadline)) throw SearchTimeout()
    }

    /** [fill] gets (upper rows, score columns, values, ties or null) and fills the grid. */
    private fun newGrid(m: Int, fill: (IntArray, Int, DoubleArray, DoubleArray?) -> Unit): Grid {
        val rows = upperRows(me, bonusDecided, m)
        val nS = gainMax(tables, me, bonusDecided, m) + 1
        val values = DoubleArray(rows.values.size * nS)
        val tieValues = if (ties) DoubleArray(values.size) else null
        fill(rows.values, nS, values, tieValues)
        return Grid(rows.values, rows.index, nS, values, tieValues)
    }

    private fun curveAt(s: Int) = if (s < curve.size) curve[s] else curve[curve.size - 1]
    private fun tieAt(s: Int) = if (s < tieCurve.size) tieCurve[s] else 0.0

    /** Win probability once mask [m] is filled with capped upper sum [u] and score [s]. */
    fun valueOf(m: Int, u: Int, s: Int): Double {
        if (m == Category.FULL_MASK) return curveAt(s)
        val g = grids[m] ?: error("mask $m outside search")
        return g.values[g.row(u) * g.nS + minOf(s - s0, g.nS - 1)]
    }

    /** P(tie) once mask [m] is filled with capped upper sum [u] and score [s] (needs [ties]). */
    fun tieOf(m: Int, u: Int, s: Int): Double {
        if (m == Category.FULL_MASK) return tieAt(s)
        val g = grids[m] ?: error("mask $m outside search")
        val t = checkNotNull(g.ties) { "search without ties" }
        return t[g.row(u) * g.nS + minOf(s - s0, g.nS - 1)]
    }

    override fun value(c: Int, points: Int, bonus: Int, u2: Int): Double =
        valueOf(mask0 or (1 shl c), u2, s0 + points + bonus)

    override fun tie(c: Int, points: Int, bonus: Int, u2: Int): Double =
        if (ties) tieOf(mask0 or (1 shl c), u2, s0 + points + bonus) else Double.NaN

    /** Most points still to come once [m] is filled (bonus included while still possible). */
    private fun remainingMax(m: Int): Int {
        var g = 0
        for (c in 0 until Category.COUNT) if (m and (1 shl c) == 0) g += scores.maxPoints[c]
        if (!bonusDecided && (m and Category.UPPER_MASK) != Category.UPPER_MASK) g += rules.bonusPoints
        return g
    }

    /**
     * Score offsets si (s = s0 + si) whose outcome is uncertain once [m] is filled; outside this
     * range the game is decided and the value is closed-form (see [fillDecided]).
     */
    private fun window(m: Int, nS: Int): IntRange {
        val lo = maxOf(0, win.oppMin - remainingMax(m) - s0)
        val hi = minOf(nS - 1, win.oppMax - s0)
        return lo..hi
    }

    /** Decided scores: certain loss below the window, certain win above; then max expectation. */
    private fun fillDecided(m: Int, uList: IntArray, nS: Int, w: IntRange, values: DoubleArray) {
        for (ui in uList.indices) {
            val future = tables.ev[m, uList[ui]]
            for (si in 0 until nS) {
                if (si in w) continue
                val s = s0 + si
                values[ui * nS + si] = (if (s > win.oppMax) 1.0 else 0.0) + WinCurve.SCORE_WEIGHT * (s + future)
            }
        }
    }

    /** Leaves continue with whichever stored policy (max expectation or a risk attitude) wins most. */
    private fun leafGrid(m: Int): Grid = newGrid(m) { uList, nS, values, tieValues ->
        val dists = tables.leafDists
        require(dists.isNotEmpty()) { "depth-limited search needs the distribution table" }
        val w = window(m, nS)
        fillDecided(m, uList, nS, w, values)
        for (ui in uList.indices) {
            val u = uList[ui]
            for (si in w) values[ui * nS + si] = Double.NEGATIVE_INFINITY
            for (dist in dists) {
                val pmf = dist.pmf(m, u)
                val lo = dist.lo(m, u)
                for (si in w) {
                    val base = s0 + si + lo
                    var v = 0.0
                    for (x in pmf.indices) v += pmf[x] * curveAt(base + x)
                    if (v > values[ui * nS + si]) {
                        values[ui * nS + si] = v
                        if (tieValues != null) {
                            var t = 0.0
                            for (x in pmf.indices) t += pmf[x] * tieAt(base + x)
                            tieValues[ui * nS + si] = t
                        }
                    }
                }
            }
        }
    }

    private fun interiorGrid(m: Int, kernel: BlockKernel): Grid = newGrid(m) { uList, nS, values, tieValues ->
        val nU = uList.size
        val w = window(m, nS)
        fillDecided(m, uList, nS, w, values)
        val wLen = w.last - w.first + 1
        if (wLen > 0 && tieValues != null) {
            interiorWithTies(m, uList, nS, w, values, tieValues, kernel)
        } else if (wLen > 0) {
            val open = (0 until Category.COUNT).filter { m and (1 shl it) == 0 }
            val children = open.map { c -> grids[m or (1 shl c)] }
            val solved = DoubleArray(nU * wLen)
            kernel.runBlocks(nU * wLen, solved) { start, len, e3 ->
                for ((ci, c) in open.withIndex()) {
                    val g = children[ci]
                    for (r in 0 until Dice.ROLLS) {
                        val pts = scores[r, c]
                        val rb = r * len - start
                        var k = start
                        while (k < start + len) {
                            val ui = k / wLen
                            val rowEnd = minOf(start + len, (ui + 1) * wLen)
                            val u = uList[ui]
                            var gain = pts
                            var u2 = u
                            if (c < 6 && !bonusDecided) {
                                u2 = minOf(cap, u + pts)
                                if (u < cap && u + pts >= cap) gain += rules.bonusPoints
                            }
                            val siStart = w.first + (k - ui * wLen)
                            if (g == null) {
                                for (kk in k until rowEnd) {
                                    val v = curveAt(s0 + siStart + (kk - k) + gain).toFloat()
                                    if (v > e3[rb + kk]) e3[rb + kk] = v
                                }
                            } else {
                                val cb = g.row(u2) * g.nS
                                val last = g.nS - 1
                                val vals = g.values
                                val off = siStart + gain - k
                                for (kk in k until rowEnd) {
                                    val v = vals[cb + minOf(kk + off, last)].toFloat()
                                    if (v > e3[rb + kk]) e3[rb + kk] = v
                                }
                            }
                            k = rowEnd
                        }
                    }
                }
            }
            for (ui in 0 until nU) System.arraycopy(solved, ui * wLen, values, ui * nS + w.first, wLen)
        }
    }

    /** [interiorGrid]'s search with the tie channel (kept apart so the plain loop stays lean). */
    private fun interiorWithTies(
        m: Int, uList: IntArray, nS: Int, w: IntRange, values: DoubleArray, tieValues: DoubleArray, kernel: BlockKernel,
    ) {
        val nU = uList.size
        val wLen = w.last - w.first + 1
        val open = (0 until Category.COUNT).filter { m and (1 shl it) == 0 }
        val children = open.map { c -> grids[m or (1 shl c)] }
        val solved = DoubleArray(nU * wLen)
        val solvedTies = DoubleArray(nU * wLen)
        kernel.runBlocksWithTies(nU * wLen, solved, solvedTies) { start, len, e3, t3 ->
            for ((ci, c) in open.withIndex()) {
                val g = children[ci]
                for (r in 0 until Dice.ROLLS) {
                    val pts = scores[r, c]
                    val rb = r * len - start
                    var k = start
                    while (k < start + len) {
                        val ui = k / wLen
                        val rowEnd = minOf(start + len, (ui + 1) * wLen)
                        val u = uList[ui]
                        var gain = pts
                        var u2 = u
                        if (c < 6 && !bonusDecided) {
                            u2 = minOf(cap, u + pts)
                            if (u < cap && u + pts >= cap) gain += rules.bonusPoints
                        }
                        val siStart = w.first + (k - ui * wLen)
                        if (g == null) {
                            for (kk in k until rowEnd) {
                                val s = s0 + siStart + (kk - k) + gain
                                val v = curveAt(s).toFloat()
                                if (v > e3[rb + kk]) {
                                    e3[rb + kk] = v
                                    t3[rb + kk] = tieAt(s).toFloat()
                                }
                            }
                        } else {
                            val cb = g.row(u2) * g.nS
                            val last = g.nS - 1
                            val vals = g.values
                            val tv = g.ties!!
                            val off = siStart + gain - k
                            for (kk in k until rowEnd) {
                                val at = cb + minOf(kk + off, last)
                                val v = vals[at].toFloat()
                                if (v > e3[rb + kk]) {
                                    e3[rb + kk] = v
                                    t3[rb + kk] = tv[at].toFloat()
                                }
                            }
                        }
                        k = rowEnd
                    }
                }
            }
        }
        for (ui in 0 until nU) {
            System.arraycopy(solved, ui * wLen, values, ui * nS + w.first, wLen)
            System.arraycopy(solvedTies, ui * wLen, tieValues, ui * nS + w.first, wLen)
        }
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

        /**
         * Rows of capped upper sums for a grid at mask [m]: the reachable values, with every value
         * from which the bonus is out of reach merged into one row (they all play the same).
         * [index] maps a capped upper sum to its row; null means a single row for any value.
         */
        class UpperRows(val values: IntArray, val index: IntArray?)

        fun upperRows(me: Board, decided: Boolean, m: Int): UpperRows {
            val cap = me.rules.bonusThreshold
            val list = reachableUpper(me, decided, m)
            if (list.size == 1) return UpperRows(list, null)
            val remainingUpper = (0 until 6).sumOf { if (m and (1 shl it) == 0) 5 * (it + 1) else 0 }
            val hopeless = list.filter { it < cap && it + remainingUpper < cap }
            val rows = if (hopeless.size > 1) (list.filter { it !in hopeless } + hopeless.first()).sorted() else list.toList()
            val index = IntArray(cap + 1) { -1 }
            for (u in list) index[u] = rows.indexOf(if (u in hopeless) hopeless.first() else u)
            return UpperRows(rows.toIntArray(), index)
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

        /**
         * Rough operation count of a search, used to pick the deepest affordable depth. With
         * [opp] only the undecided score band is counted, as the search does.
         */
        fun estimateOps(tables: Tables, me: Board, depth: Int, opp: Board? = null): Long {
            val decided = bonusDecided(me)
            val levels = masksByLevel(me.mask)
            val d = minOf(depth, levels.size - 1)
            var oppMin = Int.MIN_VALUE / 2
            var oppMax = Int.MAX_VALUE / 2
            if (opp != null && tables.dist != null) {
                if (opp.isComplete) {
                    oppMin = opp.total
                    oppMax = opp.total
                } else {
                    oppMin = opp.total + tables.dist.lo(opp.mask, opp.u)
                    oppMax = oppMin + tables.dist.len(opp.mask, opp.u) - 1
                }
            }
            var ops = 0L
            for (j in 1..d) for (m in levels[j]) {
                if (m == Category.FULL_MASK) continue
                val uList = upperRows(me, decided, m).values
                val nS = gainMax(tables, me, decided, m) + 1
                var rest = 0
                for (c in 0 until Category.COUNT) if (m and (1 shl c) == 0) rest += tables.scores.maxPoints[c]
                if (!decided && (m and Category.UPPER_MASK) != Category.UPPER_MASK) rest += me.rules.bonusPoints
                val lo = maxOf(0, oppMin - rest - me.total)
                val hi = minOf(nS - 1, oppMax - me.total)
                val contexts = uList.size.toLong() * (hi - lo + 1).coerceAtLeast(0)
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
        fun chooseDepth(tables: Tables, me: Board, budgetOps: Long, opp: Board? = null): Int {
            val remaining = Category.COUNT - me.filledCount
            for (d in remaining downTo 2) if (estimateOps(tables, me, d, opp) <= budgetOps) return d
            return 1
        }
    }
}

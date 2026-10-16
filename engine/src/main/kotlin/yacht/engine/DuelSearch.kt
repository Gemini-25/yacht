package yacht.engine

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/** Outcomes of one turn played with the max-expectation policy from (mask, u). */
class TurnOutcomes(val category: IntArray, val gain: IntArray, val u2: IntArray, val prob: DoubleArray) {
    val size: Int get() = prob.size

    companion object {
        fun of(tables: Tables, mask: Int, u: Int): TurnOutcomes {
            val rules = tables.rules
            val cap = rules.bonusThreshold
            val scores = tables.scores
            val ev = tables.ev
            val kernel = TurnKernel(1)
            val bestCat = IntArray(Dice.ROLLS)
            val e3 = kernel.e3
            for (r in 0 until Dice.ROLLS) {
                var best = Double.NEGATIVE_INFINITY
                for (c in 0 until Category.COUNT) {
                    if (mask and (1 shl c) != 0) continue
                    val s = scores[r, c]
                    var g = s
                    var nu = u
                    if (c < 6) {
                        nu = minOf(cap, u + s)
                        if (u < cap && u + s >= cap) g += rules.bonusPoints
                    }
                    val v = g + ev[mask or (1 shl c), nu]
                    if (v > best) {
                        best = v
                        bestCat[r] = c
                    }
                }
                e3[r] = best
            }
            kernel.run()
            val p1 = Dice.firstRoll.copyOf()
            val p2 = push(kernel, kernel.k1, p1)
            val p3 = push(kernel, kernel.k2, p2)
            val acc = LinkedHashMap<Long, Double>()
            for (r in 0 until Dice.ROLLS) {
                if (p3[r] == 0.0) continue
                val c = bestCat[r]
                val s = scores[r, c]
                var g = s
                var nu = u
                if (c < 6) {
                    nu = minOf(cap, u + s)
                    if (u < cap && u + s >= cap) g += rules.bonusPoints
                }
                val key = (c.toLong() shl 32) or (nu.toLong() shl 16) or g.toLong()
                acc[key] = (acc[key] ?: 0.0) + p3[r]
            }
            val keys = acc.keys.toList()
            return TurnOutcomes(
                IntArray(keys.size) { (keys[it] ushr 32).toInt() },
                IntArray(keys.size) { (keys[it] and 0xffff).toInt() },
                IntArray(keys.size) { ((keys[it] ushr 16) and 0xffff).toInt() },
                DoubleArray(keys.size) { acc.getValue(keys[it]) },
            )
        }

        private fun push(kernel: TurnKernel, k: DoubleArray, pin: DoubleArray): DoubleArray {
            val q = DoubleArray(Dice.KEEPS)
            for (r in 0 until Dice.ROLLS) if (pin[r] != 0.0) q[kernel.argBestKeep(k, r)] += pin[r]
            val out = DoubleArray(Dice.ROLLS)
            for (keep in 0 until Dice.KEEPS) {
                val w = q[keep]
                if (w == 0.0) continue
                for (j in Dice.tStart[keep] until Dice.tStart[keep + 1]) out[Dice.tRoll[j]] += w * Dice.tProb[j]
            }
            return out
        }
    }
}

/**
 * Exact P(win) for the rest of the game against a max-expectation opponent, with the
 * opponent's turns inside the search: before each of my later turns I know what the opponent
 * scored, and the plan uses that. State: (my mask, my upper sum, opponent mask, opponent upper
 * sum, my total - opponent total).
 *
 * The root may be my turn or the opponent's ([meToMove]). The table covers every position
 * reachable from the root, so once built it answers all my remaining turns ([after]) without
 * searching again. Scores whose outcome is already certain are filled in closed form.
 */
class DuelSearch(
    private val tables: Tables,
    val me: Board,
    val opp: Board,
    val meToMove: Boolean = true,
    private val pool: ExecutorService? = null,
    private val deadline: Long = Long.MAX_VALUE,
    private val stop: java.util.concurrent.atomic.AtomicBoolean? = null,
) : AfterTurn {
    private val rules = me.rules
    private val cap = rules.bonusThreshold
    private val scores = tables.scores
    private val ev = tables.ev
    private val myDecided = WinSearch.bonusDecided(me)
    private val oppDecided = WinSearch.bonusDecided(opp)
    private val kMe = Category.COUNT - me.filledCount
    private val kOpp = Category.COUNT - opp.filledCount
    private val d0 = me.total - opp.total

    /** Number of half-moves to the end, alternating from the root's mover. */
    private val halfMoves = kMe + kOpp

    private fun myLevel(h: Int) = if (meToMove) (h + 1) / 2 else h / 2
    private fun oppLevel(h: Int) = if (meToMove) h / 2 else (h + 1) / 2
    private fun myMoveAt(h: Int) = (h % 2 == 0) == meToMove

    private class Grid(
        val myU: IntArray, val myIdx: IntArray?,
        val oppU: IntArray, val oppIdx: IntArray?,
        val lo: Int, val width: Int,
        /** Win values stored in single precision: tables for 6 boxes stay in the tens of MB. */
        val values: FloatArray,
    ) {
        /** Offset of the diff row for these upper sums, or -1 if unreachable. */
        fun rowOrNull(mu: Int, ou: Int): Int {
            val a = if (myIdx == null) 0 else myIdx.getOrElse(mu) { -1 }
            val b = if (oppIdx == null) 0 else oppIdx.getOrElse(ou) { -1 }
            return if (a < 0 || b < 0) -1 else (a * oppU.size + b) * width
        }

        fun row(mu: Int, ou: Int): Int = rowOrNull(mu, ou).also { check(it >= 0) { "unreachable upper sums $mu/$ou" } }
    }

    private val grids = HashMap<Long, Grid>()
    private val outcomes = HashMap<Int, TurnOutcomes>()

    init {
        require(!(me.isComplete && opp.isComplete))
        if (meToMove) require(kOpp == kMe || kOpp == kMe - 1) { "not my turn: $kMe vs $kOpp boxes left" }
        else require(kMe == kOpp || kMe == kOpp - 1) { "not the opponent's turn: $kMe vs $kOpp boxes left" }
        val myLevels = WinSearch.masksByLevel(me.mask)
        val oppLevels = WinSearch.masksByLevel(opp.mask)
        for (h in halfMoves - 1 downTo 1) {
            val levelStart = System.nanoTime()
            val myTurn = myMoveAt(h)
            val pairs = ArrayList<Pair<Int, Int>>()
            for (mm in myLevels[myLevel(h)]) for (om in oppLevels[oppLevel(h)]) pairs += mm to om
            if (!myTurn) for (om in oppLevels[oppLevel(h)]) for (u in rows(opp, oppDecided, om).values) outcomeOf(om, u)
            val results = if (pool == null || pairs.size < 2) {
                val kernel = BlockKernel()
                pairs.map { (mm, om) -> checkStop(); key(mm, om) to node(mm, om, myTurn, kernel) }
            } else {
                val kernels = ThreadLocal.withInitial { BlockKernel() }
                pool.invokeAll(pairs.map { (mm, om) ->
                    Callable { checkStop(); key(mm, om) to node(mm, om, myTurn, kernels.get()) }
                }).map {
                    try {
                        it.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause ?: e
                    }
                }
            }
            for ((k, g) in results) grids[k] = g
            if (PROFILE) {
                val ctx = results.sumOf { it.second.values.size.toLong() }
                System.err.println("duel h=$h ${if (myTurn) "me " else "opp"} pairs=${pairs.size} cells=$ctx t=${(System.nanoTime() - levelStart) / 1_000_000} ms")
            }
        }
    }

    private fun key(mm: Int, om: Int) = (mm.toLong() shl 12) or om.toLong()

    private fun checkStop() {
        if (stop?.get() == true || (deadline != Long.MAX_VALUE && System.nanoTime() > deadline)) throw SearchTimeout()
    }

    private fun rows(b: Board, decided: Boolean, m: Int) = WinSearch.upperRows(b, decided, m)

    private fun outcomeOf(om: Int, u: Int): TurnOutcomes =
        synchronized(outcomes) { outcomes.getOrPut(om * 64 + u) { TurnOutcomes.of(tables, om, u) } }

    private fun terminal(diff: Int): Double = (if (diff > 0) 1.0 else 0.0) + WinCurve.SCORE_WEIGHT * diff

    /** Value with both cards and the score difference (both full = game over). */
    private fun valueOf(mm: Int, om: Int, mu: Int, ou: Int, diff: Int): Double {
        if (mm == Category.FULL_MASK && om == Category.FULL_MASK) return terminal(diff)
        val g = grids.getValue(key(mm, om))
        return g.values[g.row(mu, ou) + (diff - g.lo).coerceIn(0, g.width - 1)].toDouble()
    }

    /** Root plan (only when it is my turn at the root). */
    override fun value(c: Int, points: Int, bonus: Int, u2: Int): Double {
        check(meToMove)
        return valueOf(me.mask or (1 shl c), opp.mask, u2, opp.u, d0 + points + bonus)
    }

    /**
     * End-of-turn values for my turn at a later position ([myCard], [oppCard]) reached from the
     * root, or null if this table does not cover it (different game, undo past the root, ...).
     */
    fun after(myCard: Board, oppCard: Board): AfterTurn? {
        if (myCard.mask and me.mask != me.mask || oppCard.mask and opp.mask != opp.mask) return null
        val h = (myCard.filledCount - me.filledCount) + (oppCard.filledCount - opp.filledCount)
        if (h >= halfMoves || !myMoveAt(h) || myCard.isComplete) return null
        if (myLevel(h) != myCard.filledCount - me.filledCount) return null
        val diff = myCard.total - oppCard.total
        if (h > 0) {
            val g = grids[key(myCard.mask, oppCard.mask)] ?: return null
            if (g.rowOrNull(myCard.u, oppCard.u) < 0 || diff < g.lo || diff >= g.lo + g.width) return null
        } else if (myCard != me || oppCard != opp) {
            return null
        }
        // Every child of this position must be in the table with this upper sum and score range.
        for (c in 0 until Category.COUNT) {
            if (myCard.isFilled(Category.of(c))) continue
            val mm = myCard.mask or (1 shl c)
            if (mm == Category.FULL_MASK && oppCard.isComplete) continue
            val g = grids[key(mm, oppCard.mask)] ?: return null
            if (g.oppIdx != null && g.oppIdx.getOrElse(oppCard.u) { -1 } < 0) return null
        }
        return AfterTurn { c, points, bonus, u2 ->
            valueOf(myCard.mask or (1 shl c), oppCard.mask, u2, oppCard.u, diff + points + bonus)
        }
    }

    /** True when ([myCard], [oppCard]) is the root or a position reachable from it in this table. */
    fun contains(myCard: Board, oppCard: Board): Boolean {
        if (myCard == me && oppCard == opp) return true
        if (myCard.mask and me.mask != me.mask || oppCard.mask and opp.mask != opp.mask) return false
        if (myCard.isComplete && oppCard.isComplete) return false
        val g = grids[key(myCard.mask, oppCard.mask)] ?: return false
        val diff = myCard.total - oppCard.total
        return g.rowOrNull(myCard.u, oppCard.u) >= 0 && diff >= g.lo && diff < g.lo + g.width
    }

    /** Approximate memory held by the table, in bytes. */
    val bytes: Long get() = grids.values.sumOf { it.values.size.toLong() * 4 }

    /** Most points still to come for a card once [m] is filled. */
    private fun remainingMax(m: Int, decided: Boolean): Int {
        var g = 0
        for (c in 0 until Category.COUNT) if (m and (1 shl c) == 0) g += scores.maxPoints[c]
        if (!decided && (m and Category.UPPER_MASK) != Category.UPPER_MASK) g += rules.bonusPoints
        return g
    }

    private fun node(mm: Int, om: Int, myTurn: Boolean, kernel: BlockKernel): Grid {
        val myRows = rows(me, myDecided, mm)
        val oppRows = rows(opp, oppDecided, om)
        val myU = myRows.values
        val oppU = oppRows.values
        val myIdx = myRows.index
        val oppIdx = oppRows.index
        val lo = d0 - WinSearch.gainMax(tables, opp, oppDecided, om)
        val width = WinSearch.gainMax(tables, me, myDecided, mm) + (d0 - lo) + 1
        val slices = myU.size * oppU.size
        val values = FloatArray(slices * width)
        // Undecided differences: I can still be caught (diff <= opponent's best) and can still
        // get ahead (diff + my best > 0). Everything else is closed form.
        val meBest = remainingMax(mm, myDecided)
        val oppBest = remainingMax(om, oppDecided)
        val wLo = maxOf(0, -meBest + 1 - lo)
        val wHi = minOf(width - 1, oppBest - lo)
        for (a in myU.indices) for (b in oppU.indices) {
            val future = ev[mm, myU[a]] - ev[om, oppU[b]]
            val base = (a * oppU.size + b) * width
            for (di in 0 until width) {
                if (di in wLo..wHi) continue
                val diff = lo + di
                values[base + di] = ((if (diff > oppBest) 1.0 else 0.0) + WinCurve.SCORE_WEIGHT * (diff + future)).toFloat()
            }
        }
        val wLen = wHi - wLo + 1
        if (wLen > 0) {
            if (myTurn) myNode(mm, om, myU, oppU, lo, width, wLo, wLen, values, kernel)
            else oppNode(mm, om, myU, oppU, lo, width, wLo, wLen, values)
        }
        return Grid(myU, myIdx, oppU, oppIdx, lo, width, values)
    }

    private fun myNode(
        mm: Int, om: Int, myU: IntArray, oppU: IntArray, lo: Int, width: Int,
        wLo: Int, wLen: Int, values: FloatArray, kernel: BlockKernel,
    ) {
        val slices = myU.size * oppU.size
        val open = (0 until Category.COUNT).filter { mm and (1 shl it) == 0 }
        val children = open.map { c ->
            val child = mm or (1 shl c)
            if (child == Category.FULL_MASK && om == Category.FULL_MASK) null else grids.getValue(key(child, om))
        }
        val solved = DoubleArray(slices * wLen)
        kernel.runBlocks(slices * wLen, solved) { start, len, e3 ->
            for ((ci, c) in open.withIndex()) {
                val g = children[ci]
                for (r in 0 until Dice.ROLLS) {
                    val pts = scores[r, c]
                    val rb = r * len - start
                    var k = start
                    while (k < start + len) {
                        val slice = k / wLen
                        val rowEnd = minOf(start + len, (slice + 1) * wLen)
                        val u = myU[slice / oppU.size]
                        val ou = oppU[slice % oppU.size]
                        var gain = pts
                        var u2 = u
                        if (c < 6 && !myDecided) {
                            u2 = minOf(cap, u + pts)
                            if (u < cap && u + pts >= cap) gain += rules.bonusPoints
                        }
                        // diff at context kk = lo + wLo + (kk - slice * wLen)
                        val diffAtK = lo + wLo + (k - slice * wLen)
                        if (g == null) {
                            for (kk in k until rowEnd) {
                                val v = terminal(diffAtK + (kk - k) + gain).toFloat()
                                if (v > e3[rb + kk]) e3[rb + kk] = v
                            }
                        } else {
                            val row = g.row(u2, ou)
                            val top = g.width - 1
                            val vals = g.values
                            val off = diffAtK + gain - g.lo - k
                            for (kk in k until rowEnd) {
                                val v = vals[row + (kk + off).coerceIn(0, top)]
                                if (v > e3[rb + kk]) e3[rb + kk] = v
                            }
                        }
                        k = rowEnd
                    }
                }
            }
        }
        for (slice in 0 until slices) for (i in 0 until wLen) values[slice * width + wLo + i] = solved[slice * wLen + i].toFloat()
    }

    private fun oppNode(
        mm: Int, om: Int, myU: IntArray, oppU: IntArray, lo: Int, width: Int,
        wLo: Int, wLen: Int, values: FloatArray,
    ) {
        val acc = DoubleArray(myU.size * wLen)
        for (b in oppU.indices) {
            val oc = outcomeOf(om, oppU[b])
            java.util.Arrays.fill(acc, 0.0)
            for (k in 0 until oc.size) {
                val p = oc.prob[k]
                val gain = oc.gain[k]
                val child = om or (1 shl oc.category[k])
                val g = if (mm == Category.FULL_MASK && child == Category.FULL_MASK) null else grids.getValue(key(mm, child))
                for (a in myU.indices) {
                    val base = a * wLen
                    val diff0 = lo + wLo - gain
                    if (g == null) {
                        for (i in 0 until wLen) acc[base + i] += p * terminal(diff0 + i)
                    } else {
                        val row = g.row(myU[a], oc.u2[k])
                        val off = diff0 - g.lo
                        val top = g.width - 1
                        val vals = g.values
                        for (i in 0 until wLen) acc[base + i] += p * vals[row + (i + off).coerceIn(0, top)]
                    }
                }
            }
            for (a in myU.indices) {
                val base = (a * oppU.size + b) * width + wLo
                for (i in 0 until wLen) values[base + i] = acc[a * wLen + i].toFloat()
            }
        }
    }

    companion object {
        private val PROFILE = System.getProperty("yacht.profile") != null
        private const val KERNEL_OPS = 7_500L

        /**
         * Rough operation count (undecided contexts only), to decide whether the duel table
         * fits the time budget. [meToMove] as for the constructor.
         */
        fun estimateOps(tables: Tables, me: Board, opp: Board, meToMove: Boolean = true): Long {
            val kMe = Category.COUNT - me.filledCount
            val kOpp = Category.COUNT - opp.filledCount
            val ok = if (meToMove) kOpp == kMe || kOpp == kMe - 1 else kMe == kOpp || kMe == kOpp - 1
            if (!ok || kMe + kOpp == 0) return Long.MAX_VALUE
            val myDecided = WinSearch.bonusDecided(me)
            val oppDecided = WinSearch.bonusDecided(opp)
            val myLevels = WinSearch.masksByLevel(me.mask)
            val oppLevels = WinSearch.masksByLevel(opp.mask)
            val d0 = me.total - opp.total
            var ops = 0L
            for (h in kMe + kOpp - 1 downTo 1) {
                val myTurn = (h % 2 == 0) == meToMove
                val jm = if (meToMove) (h + 1) / 2 else h / 2
                val jo = if (meToMove) h / 2 else (h + 1) / 2
                for (mm in myLevels[jm]) for (om in oppLevels[jo]) {
                    val slices = WinSearch.upperRows(me, myDecided, mm).values.size.toLong() *
                        WinSearch.upperRows(opp, oppDecided, om).values.size
                    val lo = d0 - WinSearch.gainMax(tables, opp, oppDecided, om)
                    val hi = d0 + WinSearch.gainMax(tables, me, myDecided, mm)
                    val meBest = remaining(tables, mm, myDecided)
                    val oppBest = remaining(tables, om, oppDecided)
                    val w = (minOf(hi, oppBest) - maxOf(lo, -meBest + 1) + 1).coerceAtLeast(0)
                    ops += slices * w * if (myTurn) KERNEL_OPS + 252L * (Category.COUNT - Integer.bitCount(mm)) else 120L
                }
            }
            return ops
        }

        private fun remaining(tables: Tables, m: Int, decided: Boolean): Int {
            var g = 0
            for (c in 0 until Category.COUNT) if (m and (1 shl c) == 0) g += tables.scores.maxPoints[c]
            if (!decided && (m and Category.UPPER_MASK) != Category.UPPER_MASK) g += tables.rules.bonusPoints
            return g
        }
    }
}

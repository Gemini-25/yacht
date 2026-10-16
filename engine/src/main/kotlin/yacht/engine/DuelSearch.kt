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
 * sum, my total - opponent total). It must be my turn, and the opponent has as many boxes left
 * as me (I move first in the round) or one fewer (I move second).
 */
class DuelSearch(
    private val tables: Tables,
    private val me: Board,
    private val opp: Board,
    private val pool: ExecutorService? = null,
    private val deadline: Long = Long.MAX_VALUE,
) : AfterTurn {
    private val rules = me.rules
    private val cap = rules.bonusThreshold
    private val scores = tables.scores
    private val myDecided = WinSearch.bonusDecided(me)
    private val oppDecided = WinSearch.bonusDecided(opp)
    private val kMe = Category.COUNT - me.filledCount
    private val kOpp = Category.COUNT - opp.filledCount
    private val d0 = me.total - opp.total

    /** Number of half-moves to the end: me, opponent, me, ... */
    private val halfMoves = kMe + kOpp

    private class Grid(
        val myU: IntArray, val myIdx: IntArray?,
        val oppU: IntArray, val oppIdx: IntArray?,
        val lo: Int, val width: Int,
        val values: DoubleArray,
    ) {
        /** Offset of the diff row for these upper sums. */
        fun row(mu: Int, ou: Int): Int {
            val a = if (myIdx == null) 0 else myIdx[mu]
            val b = if (oppIdx == null) 0 else oppIdx[ou]
            check(a >= 0 && b >= 0) { "unreachable upper sums $mu/$ou" }
            return (a * oppU.size + b) * width
        }

        fun at(mu: Int, ou: Int, diff: Int): Double = values[row(mu, ou) + (diff - lo).coerceIn(0, width - 1)]
    }

    private val grids = HashMap<Long, Grid>()
    private val outcomes = HashMap<Int, TurnOutcomes>()

    init {
        require(!me.isComplete)
        require(kOpp == kMe || kOpp == kMe - 1) { "not my turn: $kMe vs $kOpp boxes left" }
        val myLevels = WinSearch.masksByLevel(me.mask)
        val oppLevels = WinSearch.masksByLevel(opp.mask)
        for (h in halfMoves - 1 downTo 1) {
            val jm = (h + 1) / 2
            val jo = h / 2
            val myTurn = h % 2 == 0
            val pairs = ArrayList<Pair<Int, Int>>()
            for (mm in myLevels[jm]) for (om in oppLevels[jo]) pairs += mm to om
            if (!myTurn) {
                // Pre-compute (single-threaded, cached) the opponent's turn outcomes needed now.
                for (om in oppLevels[jo]) for (u in reach(opp, oppDecided, om)) outcomeOf(om, u)
            }
            val results = if (pool == null || pairs.size < 2) {
                val kernel = TurnKernel()
                pairs.map { (mm, om) -> checkDeadline(); key(mm, om) to node(mm, om, myTurn, h, kernel) }
            } else {
                val kernels = ThreadLocal.withInitial { TurnKernel() }
                pool.invokeAll(pairs.map { (mm, om) ->
                    Callable { checkDeadline(); key(mm, om) to node(mm, om, myTurn, h, kernels.get()) }
                }).map {
                    try {
                        it.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause ?: e
                    }
                }
            }
            for ((k, g) in results) grids[k] = g
        }
    }

    private fun key(mm: Int, om: Int) = (mm.toLong() shl 12) or om.toLong()

    private fun checkDeadline() {
        if (deadline != Long.MAX_VALUE && System.nanoTime() > deadline) throw SearchTimeout()
    }

    private fun reach(b: Board, decided: Boolean, m: Int) = WinSearch.reachableUpper(b, decided, m)

    private fun outcomeOf(om: Int, u: Int): TurnOutcomes =
        synchronized(outcomes) { outcomes.getOrPut(om * 64 + u) { TurnOutcomes.of(tables, om, u) } }

    private fun terminal(diff: Int): Double = (if (diff > 0) 1.0 else 0.0) + WinCurve.SCORE_WEIGHT * diff

    /** Value at half-move [h] (0 = my current turn) with the given cards and score difference. */
    private fun valueAt(h: Int, mm: Int, om: Int, mu: Int, ou: Int, diff: Int): Double {
        if (h >= halfMoves) return terminal(diff)
        return grids.getValue(key(mm, om)).at(mu, ou, diff)
    }

    override fun value(c: Int, points: Int, bonus: Int, u2: Int): Double =
        valueAt(1, me.mask or (1 shl c), opp.mask, u2, opp.u, d0 + points + bonus)

    private fun node(mm: Int, om: Int, myTurn: Boolean, h: Int, kernel: TurnKernel): Grid {
        val myU = reach(me, myDecided, mm)
        val oppU = reach(opp, oppDecided, om)
        val myIdx = if (myU.size == 1) null else IntArray(cap + 1) { -1 }.also { i -> myU.forEachIndexed { k, u -> i[u] = k } }
        val oppIdx = if (oppU.size == 1) null else IntArray(cap + 1) { -1 }.also { i -> oppU.forEachIndexed { k, u -> i[u] = k } }
        val lo = d0 - WinSearch.gainMax(tables, opp, oppDecided, om)
        val width = WinSearch.gainMax(tables, me, myDecided, mm) + (d0 - lo) + 1
        val slices = myU.size * oppU.size
        val values = DoubleArray(slices * width)
        if (myTurn) myNode(mm, om, h, myU, oppU, lo, width, values, kernel) else oppNode(mm, om, h, myU, oppU, lo, width, values)
        return Grid(myU, myIdx, oppU, oppIdx, lo, width, values)
    }

    private fun myNode(
        mm: Int, om: Int, h: Int, myU: IntArray, oppU: IntArray, lo: Int, width: Int,
        out: DoubleArray, kernel: TurnKernel,
    ) {
        val n = myU.size * oppU.size * width
        kernel.resize(n)
        val e3 = kernel.e3
        java.util.Arrays.fill(e3, 0, Dice.ROLLS * n, Double.NEGATIVE_INFINITY)
        val last = h + 1 >= halfMoves
        for (c in 0 until Category.COUNT) {
            if (mm and (1 shl c) != 0) continue
            val g = if (last) null else grids.getValue(key(mm or (1 shl c), om))
            for (r in 0 until Dice.ROLLS) {
                val pts = scores[r, c]
                val rb = r * n
                for (a in myU.indices) {
                    val u = myU[a]
                    var gain = pts
                    var u2 = u
                    if (c < 6 && !myDecided) {
                        u2 = minOf(cap, u + pts)
                        if (u < cap && u + pts >= cap) gain += rules.bonusPoints
                    }
                    for (b in oppU.indices) {
                        val ob = rb + (a * oppU.size + b) * width
                        if (g == null) {
                            for (di in 0 until width) {
                                val v = terminal(lo + di + gain)
                                if (v > e3[ob + di]) e3[ob + di] = v
                            }
                        } else {
                            val row = g.row(u2, oppU[b])
                            val off = lo + gain - g.lo
                            val top = g.width - 1
                            val vals = g.values
                            for (di in 0 until width) {
                                val v = vals[row + (di + off).coerceIn(0, top)]
                                if (v > e3[ob + di]) e3[ob + di] = v
                            }
                        }
                    }
                }
            }
        }
        kernel.run()
        System.arraycopy(kernel.start, 0, out, 0, n)
    }

    private fun oppNode(mm: Int, om: Int, h: Int, myU: IntArray, oppU: IntArray, lo: Int, width: Int, out: DoubleArray) {
        val last = h + 1 >= halfMoves
        for (b in oppU.indices) {
            val oc = outcomeOf(om, oppU[b])
            for (k in 0 until oc.size) {
                val p = oc.prob[k]
                val gain = oc.gain[k]
                val g = if (last) null else grids.getValue(key(mm, om or (1 shl oc.category[k])))
                for (a in myU.indices) {
                    val base = (a * oppU.size + b) * width
                    if (g == null) {
                        for (di in 0 until width) out[base + di] += p * terminal(lo + di - gain)
                    } else {
                        val row = g.row(myU[a], oc.u2[k])
                        val off = lo - gain - g.lo
                        val top = g.width - 1
                        val vals = g.values
                        for (di in 0 until width) out[base + di] += p * vals[row + (di + off).coerceIn(0, top)]
                    }
                }
            }
        }
    }

    companion object {
        private val KERNEL_OPS = 7_500L

        /** Rough operation count, to decide whether the exact duel search fits the time budget. */
        fun estimateOps(tables: Tables, me: Board, opp: Board): Long {
            val kMe = Category.COUNT - me.filledCount
            val kOpp = Category.COUNT - opp.filledCount
            if (kOpp != kMe && kOpp != kMe - 1) return Long.MAX_VALUE
            val myDecided = WinSearch.bonusDecided(me)
            val oppDecided = WinSearch.bonusDecided(opp)
            val myLevels = WinSearch.masksByLevel(me.mask)
            val oppLevels = WinSearch.masksByLevel(opp.mask)
            var ops = 0L
            for (h in kMe + kOpp - 1 downTo 1) {
                val jm = (h + 1) / 2
                val jo = h / 2
                for (mm in myLevels[jm]) for (om in oppLevels[jo]) {
                    val slices = WinSearch.reachableUpper(me, myDecided, mm).size.toLong() *
                        WinSearch.reachableUpper(opp, oppDecided, om).size
                    val width = WinSearch.gainMax(tables, me, myDecided, mm) + WinSearch.gainMax(tables, opp, oppDecided, om) + 1
                    ops += slices * width * if (h % 2 == 0) KERNEL_OPS + 252L * (Category.COUNT - Integer.bitCount(mm)) else 120L
                }
            }
            return ops
        }
    }
}

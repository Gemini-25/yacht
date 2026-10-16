package yacht.engine

import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Builds the max-expectation value table and, optionally, the distribution of remaining points
 * under that policy, by backward induction over (filled mask, capped upper sum).
 */
class TableBuilder(
    val rules: RuleSet = RuleSet.NINTENDO,
    private val threads: Int = Runtime.getRuntime().availableProcessors(),
) {
    private val cap = rules.bonusThreshold
    private val nu = rules.upperStates
    private val scores = ScoreTable.of(rules)
    private val states = Category.FULL_MASK.inc() * nu

    val ev = DoubleArray(states)
    val distLo = IntArray(states)
    val dist = arrayOfNulls<DoubleArray>(states)

    /** reachableU[upperMask * nu + u]: can the filled upper boxes sum (capped) to u? */
    private val reachableU = BooleanArray(64 * nu).also { reach ->
        for (sub in 0 until 64) {
            var set = BooleanArray(nu).also { it[0] = true }
            for (f in 0 until 6) {
                if (sub and (1 shl f) == 0) continue
                val next = BooleanArray(nu)
                for (a in 0 until nu) if (set[a]) for (k in 0..5) next[minOf(cap, a + k * (f + 1))] = true
                set = next
            }
            for (u in 0 until nu) reach[sub * nu + u] = set[u]
        }
    }

    fun build(withDist: Boolean): TableBuilder {
        val full = Category.FULL_MASK
        val terminal = doubleArrayOf(1.0)
        for (u in 0 until nu) dist[full * nu + u] = terminal

        val levels = (0 until full).groupBy { Integer.bitCount(it) }.toSortedMap(compareByDescending { it })
        val workers = ThreadLocal.withInitial { Worker() }
        val pool = Executors.newFixedThreadPool(threads)
        try {
            for ((_, masks) in levels) {
                val chunk = maxOf(1, masks.size / (threads * 4))
                val tasks = masks.chunked(chunk).map { part ->
                    Callable { part.forEach { workers.get().process(it, withDist) } }
                }
                pool.invokeAll(tasks).forEach { it.get() }
            }
        } finally {
            pool.shutdown()
        }
        if (withDist) aliasUnreachable()
        return this
    }

    /** Unreachable states never occur in play; point them at a reachable state of the same mask. */
    private fun aliasUnreachable() {
        for (m in 0 until Category.FULL_MASK) {
            val base = m * nu
            var alias = (0 until nu).first { dist[base + it] != null }
            for (u in 0 until nu) {
                if (dist[base + u] != null) alias = u
                else {
                    dist[base + u] = dist[base + alias]
                    distLo[base + u] = distLo[base + alias]
                }
            }
        }
    }

    fun evTable() = EvTable(rules, ev)

    fun distTable(valueBytes: Int = 2): DistTable {
        val bytes = java.io.ByteArrayOutputStream()
        TableIO.writeDist(bytes, rules, distLo, dist, valueBytes)
        return TableIO.readDist(java.nio.ByteBuffer.wrap(bytes.toByteArray()), rules)
    }

    private inner class Worker {
        val kernel = TurnKernel(nu)
        val bestCat = IntArray(Dice.ROLLS * nu)
        val p1 = DoubleArray(Dice.ROLLS)
        val p2 = DoubleArray(Dice.ROLLS)
        val p3 = DoubleArray(Dice.ROLLS)
        val q = DoubleArray(Dice.KEEPS)
        val group = DoubleArray(Category.COUNT * nu * GAIN_SLOTS)
        val touched = IntArray(Dice.ROLLS)

        fun process(m: Int, withDist: Boolean) {
            // Once every upper box is filled the upper sum no longer matters.
            val collapsed = (m and Category.UPPER_MASK) == Category.UPPER_MASK
            val n = if (collapsed) 1 else nu
            kernel.resize(n)
            val e3 = kernel.e3
            for (r in 0 until Dice.ROLLS) {
                val rb = r * n
                java.util.Arrays.fill(e3, rb, rb + n, Double.NEGATIVE_INFINITY)
                for (c in 0 until Category.COUNT) {
                    if (m and (1 shl c) != 0) continue
                    val s = scores[r, c]
                    val child = (m or (1 shl c)) * nu
                    for (i in 0 until n) {
                        val v = if (c < 6) {
                            val u2 = minOf(cap, i + s)
                            val bonus = if (i < cap && i + s >= cap) rules.bonusPoints else 0
                            s + bonus + ev[child + u2]
                        } else {
                            s + ev[child + i]
                        }
                        if (v > e3[rb + i]) {
                            e3[rb + i] = v
                            bestCat[rb + i] = c
                        }
                    }
                }
            }
            kernel.run()
            val base = m * nu
            if (collapsed) {
                java.util.Arrays.fill(ev, base, base + nu, kernel.start[0])
            } else {
                System.arraycopy(kernel.start, 0, ev, base, nu)
            }
            if (!withDist) return
            if (collapsed) {
                val d = distribution(m, 0, n)
                for (u in 0 until nu) {
                    dist[base + u] = d
                    distLo[base + u] = lastLo
                }
            } else {
                val upper = m and Category.UPPER_MASK
                for (i in 0 until nu) {
                    if (!reachableU[upper * nu + i]) continue
                    dist[base + i] = distribution(m, i, n)
                    distLo[base + i] = lastLo
                }
            }
        }

        var lastLo = 0

        private fun push(k: DoubleArray, pin: DoubleArray, pout: DoubleArray, i: Int) {
            java.util.Arrays.fill(q, 0.0)
            for (r in 0 until Dice.ROLLS) {
                if (pin[r] == 0.0) continue
                q[kernel.argBestKeep(k, r, i)] += pin[r]
            }
            java.util.Arrays.fill(pout, 0.0)
            for (keep in 0 until Dice.KEEPS) {
                val w = q[keep]
                if (w == 0.0) continue
                for (j in Dice.tStart[keep] until Dice.tStart[keep + 1]) pout[Dice.tRoll[j]] += w * Dice.tProb[j]
            }
        }

        /** Remaining-points pmf from (m, u=i) following the policy encoded in the kernel. */
        private fun distribution(m: Int, i: Int, n: Int): DoubleArray {
            System.arraycopy(Dice.firstRoll, 0, p1, 0, Dice.ROLLS)
            push(kernel.k1, p1, p2, i)
            push(kernel.k2, p2, p3, i)

            var touchedCount = 0
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (r in 0 until Dice.ROLLS) {
                val p = p3[r]
                if (p == 0.0) continue
                val c = bestCat[r * n + i]
                val s = scores[r, c]
                var u2 = i
                var gain = s
                if (c < 6) {
                    u2 = minOf(cap, i + s)
                    if (i < cap && i + s >= cap) gain += rules.bonusPoints
                }
                val key = (c * nu + u2) * GAIN_SLOTS + gain
                if (group[key] == 0.0) {
                    if (touchedCount == touched.size) error("too many outcomes")
                    touched[touchedCount++] = key
                }
                group[key] += p
            }
            for (t in 0 until touchedCount) {
                val key = touched[t]
                val gain = key % GAIN_SLOTS
                val child = childState(m, key)
                lo = minOf(lo, gain + distLo[child])
                hi = maxOf(hi, gain + distLo[child] + dist[child]!!.size - 1)
            }
            val out = DoubleArray(hi - lo + 1)
            for (t in 0 until touchedCount) {
                val key = touched[t]
                val w = group[key]
                group[key] = 0.0
                val gain = key % GAIN_SLOTS
                val child = childState(m, key)
                val d = dist[child]!!
                val off = gain + distLo[child] - lo
                for (x in d.indices) out[off + x] += w * d[x]
            }
            var a = 0
            var b = out.size - 1
            while (a < b && out[a] < TRIM) a++
            while (b > a && out[b] < TRIM) b--
            lastLo = lo + a
            return out.copyOfRange(a, b + 1)
        }

        private fun childState(m: Int, key: Int): Int {
            val cu = key / GAIN_SLOTS
            val c = cu / nu
            val u2 = cu % nu
            return (m or (1 shl c)) * nu + u2
        }
    }

    companion object {
        private const val GAIN_SLOTS = 128
        private const val TRIM = 1e-8
    }
}

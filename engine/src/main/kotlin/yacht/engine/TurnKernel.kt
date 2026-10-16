package yacht.engine

/**
 * Backward pass through one turn (roll -> keep -> roll -> keep -> roll) for [n] independent
 * contexts at once. Fill [e3] (value after the final roll, index r*n+i), then call [run].
 *
 * After [run]: k2/k1 hold the value of each keep before the 3rd/2nd roll, e2/e1 the value of
 * each roll after the 2nd/1st throw (best keep taken), and start the value before throwing.
 */
class TurnKernel(capacity: Int = 1) {
    var n: Int = 0
        private set
    var e3 = DoubleArray(0); private set
    var k2 = DoubleArray(0); private set
    var e2 = DoubleArray(0); private set
    var k1 = DoubleArray(0); private set
    var e1 = DoubleArray(0); private set
    var start = DoubleArray(0); private set
    private var best = DoubleArray(0)
    private var cap = 0

    init {
        resize(capacity)
    }

    fun resize(contexts: Int) {
        n = contexts
        if (contexts <= cap) return
        cap = contexts
        e3 = DoubleArray(Dice.ROLLS * cap)
        k2 = DoubleArray(Dice.KEEPS * cap)
        e2 = DoubleArray(Dice.ROLLS * cap)
        k1 = DoubleArray(Dice.KEEPS * cap)
        e1 = DoubleArray(Dice.ROLLS * cap)
        start = DoubleArray(cap)
        best = DoubleArray(Dice.KEEPS * cap)
    }

    /**
     * Solves [total] contexts in blocks of at most [block] to bound memory: [fill] writes e3 for
     * contexts start until start+len (layout r*len + i, pre-filled with -inf), and the start
     * values land in out[start + i].
     */
    fun runBlocks(total: Int, out: DoubleArray, block: Int = BLOCK, fill: (start: Int, len: Int, e3: DoubleArray) -> Unit) {
        var start = 0
        while (start < total) {
            val len = minOf(block, total - start)
            resize(len)
            java.util.Arrays.fill(e3, 0, Dice.ROLLS * len, Double.NEGATIVE_INFINITY)
            fill(start, len, e3)
            run()
            System.arraycopy(this.start, 0, out, start, len)
            start += len
        }
    }

    fun run() {
        expect(e3, k2)
        bestOfSubsets(k2, e2)
        expect(e2, k1)
        bestOfSubsets(k1, e1)
        val n = n
        java.util.Arrays.fill(start, 0, n, 0.0)
        for (r in 0 until Dice.ROLLS) {
            val p = Dice.firstRoll[r]
            val rb = r * n
            for (i in 0 until n) start[i] += p * e1[rb + i]
        }
    }

    /**
     * Value of each keep = mean over the next die of the keep with that die added: throwing the
     * missing dice one at a time gives the same distribution (1260 terms instead of 4368).
     */
    private fun expect(e: DoubleArray, k: DoubleArray) {
        val n = n
        for (r in 0 until Dice.ROLLS) System.arraycopy(e, r * n, k, Dice.rollAsKeep[r] * n, n)
        val add = Dice.addFace
        for (keep in Dice.PARTIAL_KEEPS - 1 downTo 0) {
            val kb = keep * n
            val a = keep * 6
            val b0 = add[a] * n
            val b1 = add[a + 1] * n
            val b2 = add[a + 2] * n
            val b3 = add[a + 3] * n
            val b4 = add[a + 4] * n
            val b5 = add[a + 5] * n
            for (i in 0 until n) {
                k[kb + i] = (k[b0 + i] + k[b1 + i] + k[b2 + i] + k[b3 + i] + k[b4 + i] + k[b5 + i]) * SIXTH
            }
        }
    }

    /** Best keep value over all sub-multisets of each roll, propagated up the subset lattice. */
    private fun bestOfSubsets(k: DoubleArray, e: DoubleArray) {
        val n = n
        val m = best
        val downStart = Dice.downStart
        val downKeep = Dice.downKeep
        for (keep in 0 until Dice.KEEPS) {
            val kb = keep * n
            System.arraycopy(k, kb, m, kb, n)
            for (j in downStart[keep] until downStart[keep + 1]) {
                val cb = downKeep[j] * n
                for (i in 0 until n) {
                    val v = m[cb + i]
                    if (v > m[kb + i]) m[kb + i] = v
                }
            }
        }
        for (r in 0 until Dice.ROLLS) System.arraycopy(m, Dice.rollAsKeep[r] * n, e, r * n, n)
    }

    /** Best keep for roll [r] in context [i] given keep values [k] (k1 or k2). Ties keep more dice. */
    fun argBestKeep(k: DoubleArray, r: Int, i: Int = 0): Int {
        var best = -1
        var bestV = Double.NEGATIVE_INFINITY
        for (j in Dice.subStart[r] until Dice.subStart[r + 1]) {
            val keep = Dice.subKeep[j]
            val v = k[keep * n + i]
            if (best < 0 || v > bestV + EPS * Math.abs(bestV)) {
                bestV = v
                best = keep
            }
        }
        return best
    }

    companion object {
        /** Relative difference below which values are treated as ties (floating-point noise). */
        const val EPS = 1e-12
        private const val SIXTH = 1.0 / 6.0

        /** Contexts per block: ~9 MB of work arrays per thread. */
        const val BLOCK = 512
    }
}

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

    private fun expect(e: DoubleArray, k: DoubleArray) {
        val n = n
        val tStart = Dice.tStart
        val tRoll = Dice.tRoll
        val tProb = Dice.tProb
        for (keep in 0 until Dice.KEEPS) {
            val kb = keep * n
            java.util.Arrays.fill(k, kb, kb + n, 0.0)
            for (j in tStart[keep] until tStart[keep + 1]) {
                val p = tProb[j]
                val rb = tRoll[j] * n
                for (i in 0 until n) k[kb + i] += p * e[rb + i]
            }
        }
    }

    private fun bestOfSubsets(k: DoubleArray, e: DoubleArray) {
        val n = n
        val subStart = Dice.subStart
        val subKeep = Dice.subKeep
        for (r in 0 until Dice.ROLLS) {
            val rb = r * n
            val j0 = subStart[r]
            System.arraycopy(k, subKeep[j0] * n, e, rb, n)
            for (j in j0 + 1 until subStart[r + 1]) {
                val kb = subKeep[j] * n
                for (i in 0 until n) {
                    val v = k[kb + i]
                    if (v > e[rb + i]) e[rb + i] = v
                }
            }
        }
    }

    /** Best keep for roll [r] in context [i] given keep values [k] (k1 or k2). Ties keep more dice. */
    fun argBestKeep(k: DoubleArray, r: Int, i: Int = 0): Int {
        var best = -1
        var bestV = Double.NEGATIVE_INFINITY
        for (j in Dice.subStart[r] until Dice.subStart[r + 1]) {
            val keep = Dice.subKeep[j]
            val v = k[keep * n + i]
            if (v > bestV + EPS) {
                bestV = v
                best = keep
            }
        }
        return best
    }

    companion object {
        /** Values closer than this are treated as ties (floating-point noise). */
        const val EPS = 1e-12
    }
}

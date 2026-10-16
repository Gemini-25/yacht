package yacht.engine

/**
 * Single-precision [TurnKernel] for the bulk of the win searches, which only need the value
 * before the first throw of each context. Floats halve memory traffic and double the SIMD
 * width (especially on phones); win probabilities stay accurate to about 1e-6.
 */
class BlockKernel {
    private var cap = 0
    private var e3 = FloatArray(0)
    private var keeps = FloatArray(0)
    private var best = FloatArray(0)
    private var rolls = FloatArray(0)
    private val first = FloatArray(Dice.ROLLS) { Dice.firstRoll[it].toFloat() }

    private fun ensure(len: Int) {
        if (len <= cap) return
        cap = len
        e3 = FloatArray(Dice.ROLLS * cap)
        keeps = FloatArray(Dice.KEEPS * cap)
        best = FloatArray(Dice.KEEPS * cap)
        rolls = FloatArray(Dice.ROLLS * cap)
    }

    /**
     * Solves [total] contexts in blocks of at most [block]: [fill] writes e3 for contexts start
     * until start+len (layout r*len + i, pre-filled with -inf); start values land in out[start + i].
     */
    fun runBlocks(total: Int, out: DoubleArray, block: Int = TurnKernel.BLOCK, fill: (start: Int, len: Int, e3: FloatArray) -> Unit) {
        var start = 0
        while (start < total) {
            val len = minOf(block, total - start)
            ensure(len)
            java.util.Arrays.fill(e3, 0, Dice.ROLLS * len, Float.NEGATIVE_INFINITY)
            fill(start, len, e3)
            expect(e3, len)
            bestOfSubsets(len)
            gather(len)
            expect(rolls, len)
            bestOfSubsets(len)
            for (i in 0 until len) out[start + i] = 0.0
            for (r in 0 until Dice.ROLLS) {
                val p = first[r]
                val mb = Dice.rollAsKeep[r] * len
                for (i in 0 until len) out[start + i] += (p * best[mb + i]).toDouble()
            }
            start += len
        }
    }

    /** keeps <- expected value of each keep, one added die at a time. */
    private fun expect(e: FloatArray, n: Int) {
        val k = keeps
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

    /** best <- max over sub-multisets of each keep. */
    private fun bestOfSubsets(n: Int) {
        val k = keeps
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
    }

    /** rolls <- best keep value of each roll. */
    private fun gather(n: Int) {
        for (r in 0 until Dice.ROLLS) System.arraycopy(best, Dice.rollAsKeep[r] * n, rolls, r * n, n)
    }

    private companion object {
        const val SIXTH = 1f / 6f
    }
}

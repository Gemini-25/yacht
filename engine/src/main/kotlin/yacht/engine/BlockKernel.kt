package yacht.engine

/**
 * Single-precision [TurnKernel] for the bulk of the win searches, which only need the value
 * before the first throw of each context. Floats halve memory traffic and double the SIMD
 * width (especially on phones); win probabilities stay accurate to about 1e-6.
 *
 * Optionally a second quantity (P(tie)) is carried along: it is averaged over the dice like the
 * value but never maximised, following the keeps that maximise the value instead. It is the
 * exact P(tie) of the policy the value search plays.
 */
class BlockKernel {
    private var cap = 0
    private var e3 = FloatArray(0)
    private var keeps = FloatArray(0)
    private var best = FloatArray(0)
    private var rolls = FloatArray(0)
    private var tieCap = 0
    private var t3 = FloatArray(0)
    private var tKeeps = FloatArray(0)
    private var tBest = FloatArray(0)
    private var tRolls = FloatArray(0)
    private val first = FloatArray(Dice.ROLLS) { Dice.firstRoll[it].toFloat() }

    private fun ensure(len: Int, ties: Boolean) {
        if (len > cap) {
            cap = len
            e3 = FloatArray(Dice.ROLLS * cap)
            keeps = FloatArray(Dice.KEEPS * cap)
            best = FloatArray(Dice.KEEPS * cap)
            rolls = FloatArray(Dice.ROLLS * cap)
        }
        if (ties && len > tieCap) {
            tieCap = len
            t3 = FloatArray(Dice.ROLLS * tieCap)
            tKeeps = FloatArray(Dice.KEEPS * tieCap)
            tBest = FloatArray(Dice.KEEPS * tieCap)
            tRolls = FloatArray(Dice.ROLLS * tieCap)
        }
    }

    /**
     * Solves [total] contexts in blocks of at most [block]: [fill] writes e3 for contexts start
     * until start+len (layout r*len + i, pre-filled with -inf); start values land in out[start + i].
     */
    fun runBlocks(total: Int, out: DoubleArray, block: Int = TurnKernel.BLOCK, fill: (start: Int, len: Int, e3: FloatArray) -> Unit) {
        var start = 0
        while (start < total) {
            val len = minOf(block, total - start)
            ensure(len, false)
            java.util.Arrays.fill(e3, 0, Dice.ROLLS * len, Float.NEGATIVE_INFINITY)
            fill(start, len, e3)
            expect(e3, keeps, len)
            bestOfSubsets(len)
            gather(best, rolls, len)
            expect(rolls, keeps, len)
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

    /**
     * [runBlocks] with the tie channel: [fill] also writes, wherever it raises e3, the tie value
     * of that choice into t3 (same layout); start ties land in outTie.
     */
    fun runBlocksWithTies(
        total: Int,
        out: DoubleArray,
        outTie: DoubleArray,
        block: Int = TurnKernel.BLOCK,
        fill: (start: Int, len: Int, e3: FloatArray, t3: FloatArray) -> Unit,
    ) {
        var start = 0
        while (start < total) {
            val len = minOf(block, total - start)
            ensure(len, true)
            java.util.Arrays.fill(e3, 0, Dice.ROLLS * len, Float.NEGATIVE_INFINITY)
            java.util.Arrays.fill(t3, 0, Dice.ROLLS * len, 0f)
            fill(start, len, e3, t3)
            expect(e3, keeps, len)
            expect(t3, tKeeps, len)
            bestOfSubsetsWithTies(len)
            gather(best, rolls, len)
            gather(tBest, tRolls, len)
            expect(rolls, keeps, len)
            expect(tRolls, tKeeps, len)
            bestOfSubsetsWithTies(len)
            for (i in 0 until len) {
                out[start + i] = 0.0
                outTie[start + i] = 0.0
            }
            for (r in 0 until Dice.ROLLS) {
                val p = first[r]
                val mb = Dice.rollAsKeep[r] * len
                for (i in 0 until len) {
                    out[start + i] += (p * best[mb + i]).toDouble()
                    outTie[start + i] += (p * tBest[mb + i]).toDouble()
                }
            }
            start += len
        }
    }

    /** k <- expected value of each keep, one added die at a time. */
    private fun expect(e: FloatArray, k: FloatArray, n: Int) {
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

    /** [bestOfSubsets] with tBest taken from the same (maximising) sub-multiset. */
    private fun bestOfSubsetsWithTies(n: Int) {
        val k = keeps
        val m = best
        val tk = tKeeps
        val tm = tBest
        val downStart = Dice.downStart
        val downKeep = Dice.downKeep
        for (keep in 0 until Dice.KEEPS) {
            val kb = keep * n
            System.arraycopy(k, kb, m, kb, n)
            System.arraycopy(tk, kb, tm, kb, n)
            for (j in downStart[keep] until downStart[keep + 1]) {
                val cb = downKeep[j] * n
                for (i in 0 until n) {
                    val v = m[cb + i]
                    if (v > m[kb + i]) {
                        m[kb + i] = v
                        tm[kb + i] = tm[cb + i]
                    }
                }
            }
        }
    }

    /** to <- best keep value of each roll. */
    private fun gather(from: FloatArray, to: FloatArray, n: Int) {
        for (r in 0 until Dice.ROLLS) System.arraycopy(from, Dice.rollAsKeep[r] * n, to, r * n, n)
    }

    private companion object {
        const val SIXTH = 1f / 6f
    }
}

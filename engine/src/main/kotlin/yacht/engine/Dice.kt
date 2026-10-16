package yacht.engine

/**
 * Order-free dice combinatorics. A "roll" is a multiset of 5 dice (252 of them); a "keep" is a
 * multiset of 0..5 held dice (462 of them). Both are identified by per-face counts.
 */
object Dice {
    const val ROLLS = 252
    const val KEEPS = 462
    const val EMPTY_KEEP = 0

    val keepCounts: Array<IntArray>
    val keepSize: IntArray
    val rollCounts: Array<IntArray>

    /** Keep index of the 5-dice keep equal to roll r (holding everything = stop rolling). */
    val rollAsKeep: IntArray

    /** Sparse keep -> roll transition: rows tStart[k] until tStart[k+1]. */
    val tStart: IntArray
    val tRoll: IntArray
    val tProb: DoubleArray

    /** Distinct sub-multisets (as keep indices) of each roll: subStart[r] until subStart[r+1]. */
    val subStart: IntArray
    val subKeep: IntArray

    /** Probability of each roll when throwing all five dice. */
    val firstRoll: DoubleArray

    private val keepByKey = IntArray(46656) { -1 }
    private val rollByKey = IntArray(46656) { -1 }

    fun key(counts: IntArray): Int {
        var k = 0
        for (f in 5 downTo 0) k = k * 6 + counts[f]
        return k
    }

    fun keepIndex(counts: IntArray): Int = keepByKey[key(counts)].also {
        require(it >= 0) { "not a keep: ${counts.toList()}" }
    }

    fun rollIndex(counts: IntArray): Int = rollByKey[key(counts)].also {
        require(it >= 0) { "not a roll: ${counts.toList()}" }
    }

    fun rollIndex(faces: List<Int>): Int = rollIndex(countsOf(faces))

    fun countsOf(faces: List<Int>): IntArray {
        val c = IntArray(6)
        for (f in faces) {
            require(f in 1..6) { "bad face $f" }
            c[f - 1]++
        }
        return c
    }

    fun facesOf(counts: IntArray): List<Int> = buildList {
        for (f in 0 until 6) repeat(counts[f]) { add(f + 1) }
    }

    fun keepFaces(k: Int): List<Int> = facesOf(keepCounts[k])
    fun rollFaces(r: Int): List<Int> = facesOf(rollCounts[r])

    private fun multisets(size: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        fun rec(face: Int, left: Int, cur: IntArray) {
            if (face == 5) {
                cur[5] = left
                out.add(cur.copyOf())
                return
            }
            for (n in left downTo 0) {
                cur[face] = n
                rec(face + 1, left - n, cur)
            }
        }
        rec(0, size, IntArray(6))
        return out
    }

    private fun factorial(n: Int): Int = (1..n).fold(1) { a, b -> a * b }

    init {
        val keeps = (0..5).flatMap { multisets(it) }
        check(keeps.size == KEEPS)
        keepCounts = keeps.toTypedArray()
        keepSize = IntArray(KEEPS) { keepCounts[it].sum() }
        keeps.forEachIndexed { i, c -> keepByKey[key(c)] = i }

        val rolls = multisets(5)
        check(rolls.size == ROLLS)
        rollCounts = rolls.toTypedArray()
        rolls.forEachIndexed { i, c -> rollByKey[key(c)] = i }
        rollAsKeep = IntArray(ROLLS) { keepByKey[key(rollCounts[it])] }

        val extras = Array(6) { multisets(it) }
        val starts = IntArray(KEEPS + 1)
        val rollsOut = ArrayList<Int>()
        val probsOut = ArrayList<Double>()
        for (k in 0 until KEEPS) {
            starts[k] = rollsOut.size
            val n = 5 - keepSize[k]
            val denom = Math.pow(6.0, n.toDouble())
            for (e in extras[n]) {
                var ways = factorial(n)
                for (x in e) ways /= factorial(x)
                val fin = IntArray(6) { keepCounts[k][it] + e[it] }
                rollsOut.add(rollByKey[key(fin)])
                probsOut.add(ways / denom)
            }
        }
        starts[KEEPS] = rollsOut.size
        tStart = starts
        tRoll = rollsOut.toIntArray()
        tProb = probsOut.toDoubleArray()

        val sStart = IntArray(ROLLS + 1)
        val subs = ArrayList<Int>()
        for (r in 0 until ROLLS) {
            sStart[r] = subs.size
            val c = rollCounts[r]
            val cur = IntArray(6)
            fun rec(f: Int) {
                if (f == 6) {
                    subs.add(keepByKey[key(cur)])
                    return
                }
                for (n in c[f] downTo 0) {
                    cur[f] = n
                    rec(f + 1)
                }
            }
            rec(0)
        }
        sStart[ROLLS] = subs.size
        subStart = sStart
        subKeep = subs.toIntArray()

        firstRoll = DoubleArray(ROLLS)
        for (j in tStart[EMPTY_KEEP] until tStart[EMPTY_KEEP + 1]) firstRoll[tRoll[j]] += tProb[j]
    }
}

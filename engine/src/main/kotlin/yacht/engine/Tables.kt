package yacht.engine

import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Points of every (roll, category) pair under a rule set. */
class ScoreTable private constructor(val rules: RuleSet) {
    private val points = IntArray(Dice.ROLLS * Category.COUNT)

    /** Highest score each category can ever give. */
    val maxPoints = IntArray(Category.COUNT)

    init {
        for (r in 0 until Dice.ROLLS) for (c in 0 until Category.COUNT) {
            val p = rules.score(Dice.rollCounts[r], Category.of(c))
            points[r * Category.COUNT + c] = p
            if (p > maxPoints[c]) maxPoints[c] = p
        }
    }

    operator fun get(r: Int, c: Int): Int = points[r * Category.COUNT + c]

    companion object {
        private val cache = HashMap<RuleSet, ScoreTable>()
        fun of(rules: RuleSet): ScoreTable = synchronized(cache) { cache.getOrPut(rules) { ScoreTable(rules) } }
    }
}

/** Expected remaining points (bonus included) from (mask, u) under the max-expectation policy. */
class EvTable(val rules: RuleSet, val values: DoubleArray) {
    init {
        require(values.size == Category.FULL_MASK.inc() * rules.upperStates)
    }

    operator fun get(mask: Int, u: Int): Double = values[mask * rules.upperStates + u]
}

/** P(upper bonus by the end of the game) from (mask, u) under the max-expectation policy. */
class BonusTable(val rules: RuleSet, private val values: FloatArray) {
    init {
        require(values.size == Category.FULL_MASK.inc() * rules.upperStates)
    }

    operator fun get(mask: Int, u: Int): Double = values[mask * rules.upperStates + u].toDouble()
}

/**
 * Distribution of remaining points from (mask, u) when playing the max-expectation policy.
 * Stored sparsely as half or single precision floats: pmf over [lo, lo + len).
 */
class DistTable(
    val rules: RuleSet,
    private val offset: IntArray,
    private val lo: IntArray,
    private val len: IntArray,
    private val data: ByteBuffer,
    private val valueBytes: Int,
    /** Risk attitude of the policy the distribution follows (0 = max expectation). */
    val theta: Double = 0.0,
) {
    private fun state(mask: Int, u: Int) = mask * rules.upperStates + u

    fun lo(mask: Int, u: Int): Int = lo[state(mask, u)]
    fun len(mask: Int, u: Int): Int = len[state(mask, u)]

    /** Normalised pmf (rounding in storage would otherwise bias comparisons between states). */
    fun pmf(mask: Int, u: Int): DoubleArray {
        val s = state(mask, u)
        val base = offset[s]
        val p = if (valueBytes == 2) {
            DoubleArray(len[s]) { Half.toDouble(data.getShort(2 * (base + it))) }
        } else {
            DoubleArray(len[s]) { data.getFloat(4 * (base + it)).toDouble() }
        }
        val sum = p.sum()
        for (i in p.indices) p[i] /= sum
        return p
    }

    fun mean(mask: Int, u: Int): Double {
        val p = pmf(mask, u)
        val l = lo(mask, u)
        return p.indices.sumOf { (l + it) * p[it] }
    }

    /** Mean and variance of the remaining points from (mask, u). */
    fun moments(mask: Int, u: Int): Pair<Double, Double> = moments(pmf(mask, u), lo(mask, u))

    companion object {
        /** Mean and variance of a pmf over [lo, lo + p.size). */
        fun moments(p: DoubleArray, lo: Int): Pair<Double, Double> {
            var m = 0.0
            var q = 0.0
            for (i in p.indices) {
                val x = (lo + i).toDouble()
                m += x * p[i]
                q += x * x * p[i]
            }
            return m to maxOf(0.0, q - m * m)
        }
    }
}

/**
 * [dist] is the max-expectation policy's distribution (the opponent model and the default leaf).
 * [riskDists] are extra policies (theta != 0); the win search continues with whichever of all
 * these policies wins most often from each leaf.
 */
class Tables(
    val rules: RuleSet,
    val ev: EvTable,
    val dist: DistTable?,
    val riskDists: List<DistTable> = emptyList(),
    /** For explanations only (never used by the search). */
    val bonus: BonusTable? = null,
) {
    val scores: ScoreTable = ScoreTable.of(rules)

    /** Policies available for evaluating search leaves. */
    val leafDists: List<DistTable> = listOfNotNull(dist) + riskDists

    fun withoutRisk(): Tables = Tables(rules, ev, dist, bonus = bonus)
}

/** IEEE 754 half precision for non-negative values up to 1 (probabilities). */
internal object Half {
    private const val SUBNORMAL_STEP = 5.9604644775390625e-8 // 2^-24

    fun fromDouble(v: Double): Short {
        require(v >= 0.0 && v <= 1.0) { "probability out of range: $v" }
        if (v < SUBNORMAL_STEP / 2) return 0
        var e = Math.getExponent(v)
        if (e < -14) return Math.round(v / SUBNORMAL_STEP).toInt().toShort()
        var m = Math.round((v / Math.scalb(1.0, e) - 1.0) * 1024).toInt()
        if (m == 1024) {
            e++
            m = 0
        }
        return (((e + 15) shl 10) or m).toShort()
    }

    fun toDouble(h: Short): Double {
        val bits = h.toInt() and 0xffff
        val e = bits ushr 10
        val m = bits and 0x3ff
        return if (e == 0) m * SUBNORMAL_STEP else Math.scalb(1.0 + m / 1024.0, e - 15)
    }
}

object TableIO {
    private const val EV_MAGIC = 0x59455631 // "YEV1"
    private const val DIST_MAGIC = 0x59445334 // "YDS4"
    private const val BONUS_MAGIC = 0x59424e31 // "YBN1"

    private fun writeFloats(out: OutputStream, magic: Int, rules: RuleSet, values: DoubleArray) {
        val buf = ByteBuffer.allocate(12 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(magic).putInt(rules.fingerprint).putInt(rules.upperStates)
        for (v in values) buf.putFloat(v.toFloat())
        out.write(buf.array())
    }

    private fun readFloats(buf: ByteBuffer, magic: Int, rules: RuleSet, what: String): ByteBuffer {
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        check(b.getInt() == magic) { "not a $what table" }
        check(b.getInt() == rules.fingerprint) { "$what table was built for different rules" }
        check(b.getInt() == rules.upperStates)
        return b
    }

    fun writeEv(out: OutputStream, rules: RuleSet, values: DoubleArray) = writeFloats(out, EV_MAGIC, rules, values)

    fun readEv(buf: ByteBuffer, rules: RuleSet): EvTable {
        val b = readFloats(buf, EV_MAGIC, rules, "EV")
        val n = Category.FULL_MASK.inc() * rules.upperStates
        return EvTable(rules, DoubleArray(n) { b.getFloat().toDouble() })
    }

    fun writeBonus(out: OutputStream, rules: RuleSet, values: DoubleArray) = writeFloats(out, BONUS_MAGIC, rules, values)

    fun readBonus(buf: ByteBuffer, rules: RuleSet): BonusTable {
        val b = readFloats(buf, BONUS_MAGIC, rules, "bonus")
        val n = Category.FULL_MASK.inc() * rules.upperStates
        return BonusTable(rules, FloatArray(n) { b.getFloat() })
    }

    /** [valueBytes] 2 stores half floats (compact), 4 single floats. */
    fun writeDist(
        out: OutputStream,
        rules: RuleSet,
        lo: IntArray,
        pmfs: Array<DoubleArray?>,
        valueBytes: Int = 2,
        theta: Double = 0.0,
    ) {
        require(valueBytes == 2 || valueBytes == 4)
        val n = pmfs.size
        val data = DataOutputStream(out.buffered(1 shl 20))
        val header = ByteBuffer.allocate(28 + n * 12).order(ByteOrder.LITTLE_ENDIAN)
        // States sharing one pmf array (u-independent states) are stored once.
        val seen = java.util.IdentityHashMap<DoubleArray, Int>()
        var total = 0
        val offsets = IntArray(n)
        val order = ArrayList<DoubleArray>()
        for (s in 0 until n) {
            val p = pmfs[s]!!
            offsets[s] = seen.getOrPut(p) {
                order.add(p)
                total.also { total += p.size }
            }
        }
        header.putInt(DIST_MAGIC).putInt(rules.fingerprint).putInt(rules.upperStates).putInt(total).putInt(valueBytes).putDouble(theta)
        for (s in 0 until n) header.putInt(offsets[s]).putInt(lo[s]).putInt(pmfs[s]!!.size)
        data.write(header.array())
        val chunk = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN)
        for (p in order) for (v in p) {
            if (valueBytes == 2) chunk.putShort(Half.fromDouble(v)) else chunk.putFloat(v.toFloat())
            if (!chunk.hasRemaining()) {
                data.write(chunk.array(), 0, chunk.position())
                chunk.clear()
            }
        }
        data.write(chunk.array(), 0, chunk.position())
        data.flush()
    }

    /** [buf] may be a memory-mapped file; pmf data is read lazily from it. */
    fun readDist(buf: ByteBuffer, rules: RuleSet): DistTable {
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        check(b.getInt() == DIST_MAGIC) { "not a distribution table" }
        check(b.getInt() == rules.fingerprint) { "distribution table was built for different rules" }
        check(b.getInt() == rules.upperStates)
        val total = b.getInt()
        val valueBytes = b.getInt()
        val theta = b.getDouble()
        val n = Category.FULL_MASK.inc() * rules.upperStates
        val offset = IntArray(n)
        val lo = IntArray(n)
        val len = IntArray(n)
        for (s in 0 until n) {
            offset[s] = b.getInt()
            lo[s] = b.getInt()
            len[s] = b.getInt()
        }
        val data = b.slice().order(ByteOrder.LITTLE_ENDIAN)
        check(data.remaining() >= total.toLong() * valueBytes) { "distribution table truncated" }
        return DistTable(rules, offset, lo, len, data, valueBytes, theta)
    }
}

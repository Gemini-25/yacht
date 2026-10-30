package yacht.engine

import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The exact remaining-points distribution of a player that plays its own card only (its choices
 * depend on its boxes and upper sum, never on the opponent or the scores): every state asks the
 * player each decision of a turn (each roll's keep, each final roll's box) and follows the dice,
 * by backward induction over (filled mask, capped upper sum) as [TableBuilder] does for max
 * expectation. [play] makes one player per worker (players keep a turn's plan).
 */
class PolicyDist private constructor(private val rules: RuleSet, private val lo: IntArray, private val pmf: Array<DoubleArray?>) {
    private val nu = rules.upperStates

    /** Remaining points from (mask, u): pmf over [lo, lo + size). */
    fun pmf(mask: Int, u: Int): DoubleArray = pmf[mask * nu + u]!!
    fun lo(mask: Int, u: Int): Int = lo[mask * nu + u]

    companion object {
        fun build(rules: RuleSet, threads: Int = Runtime.getRuntime().availableProcessors(), play: () -> Player): PolicyDist {
            val nu = rules.upperStates
            val cap = rules.bonusThreshold
            val scores = ScoreTable.of(rules)
            val states = Category.FULL_MASK.inc() * nu
            val lo = IntArray(states)
            val dist = arrayOfNulls<DoubleArray>(states)
            for (u in 0 until nu) dist[Category.FULL_MASK * nu + u] = doubleArrayOf(1.0)
            val empty = Board(rules)
            val players = ThreadLocal.withInitial(play)

            // A card of mask m with upper sum (capped) u, lower boxes at 0; null when unreachable.
            fun card(m: Int, u: Int): Board? {
                val ups = (0 until 6).filter { m and (1 shl it) != 0 }
                val pts = IntArray(6)
                fun fit(i: Int, left: Int): Boolean {
                    if (i == ups.size) return if (u >= cap) left <= 0 else left == 0
                    val f = ups[i] + 1
                    for (k in 5 downTo 0) {
                        pts[ups[i]] = k * f
                        if (fit(i + 1, left - k * f)) return true
                    }
                    return false
                }
                if (!fit(0, u)) return null
                var b = Board(rules)
                for (c in Category.ALL) if (m and (1 shl c.ordinal) != 0) b = b.with(c, if (c.isUpper) pts[c.ordinal] else 0)
                return b
            }

            fun state(m: Int, u: Int) {
                val board = card(m, u) ?: return
                val p = players.get()
                p.startTurn(board, empty)
                val final = DoubleArray(Dice.ROLLS)
                var cur = Dice.firstRoll.copyOf()
                for (rollsDone in 1..2) {
                    val next = DoubleArray(Dice.ROLLS)
                    for (r in 0 until Dice.ROLLS) {
                        val w = cur[r]
                        if (w == 0.0) continue
                        val keep = p.chooseKeep(rollsDone, r)
                        if (keep == Dice.rollAsKeep[r]) {
                            final[r] += w
                            continue
                        }
                        for (j in Dice.tStart[keep] until Dice.tStart[keep + 1]) next[Dice.tRoll[j]] += w * Dice.tProb[j]
                    }
                    cur = next
                }
                for (r in 0 until Dice.ROLLS) final[r] += cur[r]
                // The outcomes: box, points with the bonus, the child state; then the child's rest.
                var outLo = Int.MAX_VALUE
                var outHi = Int.MIN_VALUE
                val parts = ArrayList<Triple<Double, Int, Int>>()
                for (r in 0 until Dice.ROLLS) {
                    val w = final[r]
                    if (w == 0.0) continue
                    val c = p.chooseCategory(r).ordinal
                    val s = scores[r, c]
                    var u2 = u
                    var g = s
                    if (c < 6) {
                        u2 = minOf(cap, u + s)
                        if (u < cap && u + s >= cap) g += rules.bonusPoints
                    }
                    val child = (m or (1 shl c)) * nu + u2
                    val d = dist[child] ?: error("child state not built: mask ${m or (1 shl c)} u $u2")
                    parts += Triple(w, g, child)
                    outLo = minOf(outLo, g + lo[child])
                    outHi = maxOf(outHi, g + lo[child] + d.size - 1)
                }
                val out = DoubleArray(outHi - outLo + 1)
                for ((w, g, child) in parts) {
                    val d = dist[child]!!
                    val off = g + lo[child] - outLo
                    for (x in d.indices) out[off + x] += w * d[x]
                }
                val sum = out.sum()
                for (i in out.indices) out[i] /= sum
                lo[m * nu + u] = outLo
                dist[m * nu + u] = out
            }

            val levels = (0 until Category.FULL_MASK).groupBy { Integer.bitCount(it) }.toSortedMap(compareByDescending { it })
            val pool = Executors.newFixedThreadPool(threads)
            try {
                for ((_, masks) in levels) {
                    val chunk = maxOf(1, masks.size / (threads * 4))
                    pool.invokeAll(masks.chunked(chunk).map { part -> Callable { for (m in part) for (u in 0 until nu) state(m, u) } }).forEach { it.get() }
                }
            } finally {
                pool.shutdown()
            }
            // Unreachable states never occur: point them at a reachable one of the same mask.
            for (m in 0 until Category.FULL_MASK) {
                val base = m * nu
                var alias = (0 until nu).first { dist[base + it] != null }
                for (u in 0 until nu) {
                    if (dist[base + u] != null) alias = u else {
                        dist[base + u] = dist[base + alias]
                        lo[base + u] = lo[base + alias]
                    }
                }
            }
            return PolicyDist(rules, lo, dist)
        }
    }
}

/**
 * Knowing the opponent completely (an AI league can): its exact remaining-points distribution
 * from every state ([PolicyDist], or a [DistTable] of its policy) instead of a model from its
 * record. For measuring what perfect knowledge would add; [reshape] ignores the
 * max-expectation distribution it is given.
 */
class ExactOpp(private val pmfOf: (Int, Int) -> DoubleArray, private val loOf: (Int, Int) -> Int) : OppModel(0.0, 1.0) {
    constructor(d: PolicyDist) : this(d::pmf, d::lo)
    constructor(d: DistTable) : this(d::pmf, d::lo)

    override fun reshape(tables: Tables, opp: Board, pmf: DoubleArray, lo: Int): Pair<DoubleArray, Int> =
        pmfOf(opp.mask, opp.u).copyOf() to loOf(opp.mask, opp.u)

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun toString(): String = "ExactOpp"
}

package yacht.engine.explain

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Tables
import yacht.engine.TurnOutcomes
import yacht.engine.WinCurve
import yacht.engine.game.label

/**
 * Before the opponent's turn: how its result would move my P(win). The opponent is modelled as
 * in the search (max expectation), so its results follow that policy's turn distribution.
 */
class OpponentOutlook(
    /** My P(win) before its turn (from the plans), when known. */
    val now: Double?,
    /** My P(win) when the opponent's turn goes well for it (10th percentile), typically, and badly (90th). */
    val low: Double,
    val typical: Double,
    val high: Double,
    /** (P(opponent makes a Yacht), my P(win) then), when it is open and not too unlikely. */
    val yacht: Pair<Double, Double>?,
    /** Where the opponent will most likely write, with probabilities. */
    val targets: List<Pair<Category, Double>>,
    /** Values are exact (end-game table) rather than quick estimates anchored to [now]. */
    val exact: Boolean,
    val summary: String,
    val details: List<String>,
)

class OutlookAnalysis(private val tables: Tables) {
    private val rules = tables.rules

    /**
     * My P(win) at my next turn if I played on with the best stored policy (no further search):
     * a quick estimate, exact once I have nothing left to play.
     */
    fun quick(me: Board, opp: Board): Double {
        val curve = WinCurve.against(tables, opp)
        fun at(s: Int) = curve.values[s.coerceIn(0, curve.values.size - 1)] - WinCurve.SCORE_WEIGHT * s.coerceIn(0, curve.values.size - 1)
        if (me.isComplete) return at(me.total)
        var best = 0.0
        for (dist in tables.leafDists) {
            val p = dist.pmf(me.mask, me.u)
            val lo = dist.lo(me.mask, me.u)
            var v = 0.0
            for (x in p.indices) v += p[x] * at(me.total + lo + x)
            best = maxOf(best, v)
        }
        return best
    }

    /**
     * [now]: my P(win) before the opponent's turn from the plans (the quick estimates are shifted
     * to average to it); [exactAt]: my exact P(win) after a given opponent card when an end-game
     * table has it.
     */
    fun opponentTurn(me: Board, opp: Board, now: Double?, exactAt: ((Board) -> Double?)? = null): OpponentOutlook? {
        if (opp.isComplete || tables.dist == null) return null
        val cap = rules.bonusThreshold
        val oc = TurnOutcomes.of(tables, opp.mask, opp.u)
        val cards = ArrayList<Board>(oc.size)
        for (k in 0 until oc.size) {
            val c = oc.category[k]
            val gain = oc.gain[k]
            val pts = if (c < 6 && opp.u < cap && gain >= rules.bonusPoints) gain - rules.bonusPoints else gain
            cards += opp.with(Category.of(c), pts)
        }
        // With my card full the quick value is exact: P(the opponent ends below my total).
        val exact = if (me.isComplete) cards.map { quick(me, it) }
        else exactAt?.let { f -> cards.map { f(it) } }?.takeIf { v -> v.all { it != null } }?.map { it!! }
        var wins = exact ?: cards.map { quick(me, it) }
        if (exact == null && now != null) {
            val mean = (0 until oc.size).sumOf { oc.prob[it] * wins[it] }
            val shift = now - mean
            wins = wins.map { (it + shift).coerceIn(0.0, 1.0) }
        }
        val order = (0 until oc.size).sortedBy { wins[it] }
        fun quantile(q: Double): Double {
            var acc = 0.0
            for (k in order) {
                acc += oc.prob[k]
                if (acc >= q) return wins[k]
            }
            return wins[order.last()]
        }
        val low = quantile(0.1)
        val typical = quantile(0.5)
        val high = quantile(0.9)
        var yacht: Pair<Double, Double>? = null
        if (!opp.isFilled(Category.YACHT)) {
            val ks = (0 until oc.size).filter { oc.category[it] == Category.YACHT.ordinal && oc.gain[it] > 0 }
            val p = ks.sumOf { oc.prob[it] }
            if (p >= 0.01) yacht = p to ks.sumOf { oc.prob[it] * wins[it] } / p
        }
        val byBox = DoubleArray(Category.COUNT)
        for (k in 0 until oc.size) byBox[oc.category[k]] += oc.prob[k]
        val targets = (0 until Category.COUNT).filter { byBox[it] >= 0.1 }.sortedByDescending { byBox[it] }.take(2)
            .map { Category.of(it) to byBox[it] }
        val (summary, details) = words(opp, low, typical, high, yacht, targets, exact != null)
        return OpponentOutlook(now, low, typical, high, yacht, targets, exact != null, summary, details)
    }

    private fun box(c: Category) = if (c.isUpper) "‘${c.face}’" else c.label

    private fun words(
        opp: Board, low: Double, typical: Double, high: Double, yacht: Pair<Double, Double>?,
        targets: List<Pair<Category, Double>>, exact: Boolean,
    ): Pair<String, List<String>> {
        val (a, b) = Ko.pctPair(low, high)
        val about = if (exact) "" else "대략 "
        val yachtMatters = yacht != null && yacht.second < low - 0.005
        val flat = high - low < 0.005 && !yachtMatters
        val summary = when {
            flat && high < 0.0005 -> "상대 턴 결과와 상관없이 역전은 어려워요."
            flat && low > 0.9995 -> "상대 턴 결과와 상관없이 이긴 판이에요."
            flat -> "상대 턴 결과와 상관없이 내 승률은 ${about}${Ko.pct(typical)} 그대로예요."
            high - low < 0.005 -> "상대가 요트를 만들지 않으면 내 승률은 ${about}${Ko.pct(typical)} 그대로예요."
            else -> "상대 턴 결과에 따라 내 승률이 ${about}$a~$b 사이로 움직여요."
        }
        val details = ArrayList<String>()
        val last = Category.COUNT - opp.filledCount == 1
        if (last) details += "상대의 마지막 턴이에요."
        if (high - low >= 0.005) {
            details += "상대가 무난하게 두면 내 승률은 ${Ko.pct(typical)}쯤이에요. 상대가 잘 풀리면 ${a}, 꼬이면 ${b}가 돼요."
        }
        if (yacht != null && yachtMatters) {
            details += "상대가 요트를 만들면(확률 ${Ko.pct(yacht.first)}) ${Ko.ro(Ko.pct(yacht.second))} 떨어져요."
        }
        if (targets.isNotEmpty() && !last) {
            details += "상대는 주로 " + targets.joinToString(", ") { "${box(it.first)}(${Ko.pct(it.second)})" } + " 쪽에 넣게 될 거예요."
        }
        return summary to details
    }
}

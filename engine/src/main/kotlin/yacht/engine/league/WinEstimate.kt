package yacht.engine.league

import yacht.engine.Board
import yacht.engine.Tables

/**
 * Quick P(win) of a position, for statistics only (per-turn ratings, win-probability curves,
 * highlights, luck): each player's remaining points follow the max-expectation policy's
 * distribution, independently, so a tie counts half. Microseconds per call, unlike the search.
 */
class WinEstimate(private val tables: Tables) {
    /** P([a] finishes ahead of [b]) + half P(level). */
    fun pWin(a: Board, b: Board): Double {
        val dist = tables.dist
        if (dist == null || (a.isComplete && b.isComplete)) {
            return if (a.total > b.total) 1.0 else if (a.total == b.total) 0.5 else 0.0
        }
        val pa = if (a.isComplete) ONE else dist.pmf(a.mask, a.u)
        val la = if (a.isComplete) 0 else dist.lo(a.mask, a.u)
        val pb = if (b.isComplete) ONE else dist.pmf(b.mask, b.u)
        val lb = if (b.isComplete) 0 else dist.lo(b.mask, b.u)
        val cdf = DoubleArray(pb.size)
        var acc = 0.0
        for (i in pb.indices) {
            acc += pb[i]
            cdf[i] = acc
        }
        // P(b's rest <= v)
        fun below(v: Int): Double {
            val i = v - lb
            return if (i < 0) 0.0 else if (i >= cdf.size) 1.0 else cdf[i]
        }
        val d = a.total - b.total
        var win = 0.0
        var tie = 0.0
        for (i in pa.indices) {
            val p = pa[i]
            if (p == 0.0) continue
            val x = d + la + i
            val under = below(x - 1)
            win += p * under
            tie += p * (below(x) - under)
        }
        return (win + 0.5 * tie).coerceIn(0.0, 1.0)
    }

    private companion object {
        val ONE = doubleArrayOf(1.0)
    }
}

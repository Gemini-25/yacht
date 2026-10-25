package yacht.engine.league

import yacht.engine.Board

/**
 * One finished game in full, for the statistics: both final cards, the running totals and the
 * first player's estimated P(win) after every half-turn, and what they say about the game
 * (comebacks, lead changes, match ratings). See docs/LEAGUE_SPEC.md section 3.
 */
class GameFacts(
    /** Final cards: the first player's and the second's. */
    val first: Board,
    val second: Board,
    /** Totals after each half-turn: first, second, first, second, ... (24 each). */
    val totals: ShortArray,
    /** The first player's P(win) after each half-turn, in percent (24 values). */
    val curve: ByteArray,
) {
    val firstWon: Boolean get() = first.total > second.total
    val secondWon: Boolean get() = second.total > first.total
    val margin: Int get() = first.total - second.total

    /** P(win) of the first player before half-turn [h] (0.5 before the first). */
    private fun pBefore(h: Int): Double = if (h == 0) 0.5 else curve[h - 1] / 100.0
    private fun pAfter(h: Int): Double = curve[h] / 100.0

    /** Win probability added by each player over its own turns (first, second). */
    val wpa: Pair<Double, Double>
        get() {
            var f = 0.0
            var s = 0.0
            for (h in curve.indices) {
                val d = pAfter(h) - pBefore(h)
                if (h % 2 == 0) f += d else s -= d
            }
            return f to s
        }

    /** Match ratings (평점, 3.0-10.0) of the first and the second player. */
    val ratings: Pair<Double, Double>
        get() {
            val (f, s) = wpa
            return rating(f) to rating(s)
        }

    /** The winner's lowest P(win) during the game (a comeback when small); 0.5 for a draw. */
    val winnerMinP: Double
        get() = when {
            firstWon -> minOf(0.5, curve.minOf { it.toInt() } / 100.0)
            secondWon -> minOf(0.5, curve.minOf { 100 - it } / 100.0)
            else -> 0.5
        }

    /** The loser's highest P(win) (a blown lead when large); 0.5 for a draw. */
    val loserMaxP: Double
        get() = when {
            firstWon -> maxOf(0.5, curve.maxOf { 100 - it } / 100.0)
            secondWon -> maxOf(0.5, curve.maxOf { it.toInt() } / 100.0)
            else -> 0.5
        }

    /** Times the leader on points changed (after full turns). */
    val leadChanges: Int
        get() {
            var last = 0
            var n = 0
            for (t in 0 until totals.size / 4) {
                // After full turn t: half-turn 2t+1.
                val h = 2 * t + 1
                val d = totals[2 * h] - totals[2 * h + 1].toInt()
                val now = if (d > 0) 1 else if (d < 0) -1 else 0
                if (now != 0 && last != 0 && now != last) n++
                if (now != 0) last = now
            }
            return n
        }

    /** The first half-turn after which the eventual winner never trailed on P(win) again. */
    val decisiveHalfTurn: Int
        get() {
            if (!firstWon && !secondWon) return curve.size - 1
            var h = curve.size - 1
            while (h > 0) {
                val p = curve[h - 1].toInt()
                val winnerAhead = if (firstWon) p > 50 else p < 50
                if (!winnerAhead) break
                h--
            }
            return h
        }

    /** Encoded for highlights and the feed: totals and curve. */
    fun encodeCurve(): String = curve.joinToString(",") { it.toString() }

    companion object {
        fun rating(wpa: Double): Double = (6.5 + 3.5 * wpa).coerceIn(3.0, 10.0)

        /**
         * Builds the facts of a game from the boards after each half-turn ([boards]: first's and
         * second's card after half-turns 0, 1, 2, ...; 24 entries).
         */
        fun of(boards: List<Pair<Board, Board>>, estimate: WinEstimate): GameFacts {
            val totals = ShortArray(boards.size * 2)
            val curve = ByteArray(boards.size)
            for ((h, bs) in boards.withIndex()) {
                val (x, y) = bs
                totals[2 * h] = x.total.toShort()
                totals[2 * h + 1] = y.total.toShort()
                curve[h] = Math.round(100 * estimate.pWin(x, y)).toInt().coerceIn(0, 100).toByte()
            }
            val last = boards.last()
            return GameFacts(last.first, last.second, totals, curve)
        }
    }
}

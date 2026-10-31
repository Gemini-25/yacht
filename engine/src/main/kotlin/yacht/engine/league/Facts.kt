package yacht.engine.league

import yacht.engine.Board
import yacht.engine.Category

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

    /**
     * Match ratings (평점 2.0, [rating]) of the first and the second player on the fallback scale
     * ([RatingScale.FALLBACK]), which a league of generated players is far from: the stats and the
     * match report rate on the season's ([ratings] of [StatsBook.ratingScale]).
     */
    val ratings: Pair<Double, Double> get() = ratings(RatingScale.FALLBACK)

    /** Match ratings of the first and the second player on [scale] (the season's: [StatsBook.ratingScale]). */
    fun ratings(scale: RatingScale): Pair<Double, Double> {
        val (f, s) = wpa
        if (scale.version < 2) return rating(f) to rating(s)
        val x = first.total
        val y = second.total
        return rating(x, y, f, minP(true), first, scale) to rating(y, x, s, minP(false), second, scale)
    }

    /** The first player's (or the second's) lowest P(win) during the game. */
    private fun minP(firstPlayer: Boolean): Double {
        var lo = 100
        var hi = 0
        for (p in curve) {
            lo = minOf(lo, p.toInt())
            hi = maxOf(hi, p.toInt())
        }
        return if (firstPlayer) lo / 100.0 else (100 - hi) / 100.0
    }

    /**
     * The largest deficit the first player (or the second) came back from after a full turn:
     * the most the opponent led by at half-turns 2t+1 (0 when it never trailed).
     */
    fun deficit(firstPlayer: Boolean): Int {
        var d = 0
        for (t in 0 until totals.size / 4) {
            val h = 2 * t + 1
            val x = totals[2 * h].toInt()
            val y = totals[2 * h + 1].toInt()
            d = maxOf(d, if (firstPlayer) y - x else x - y)
        }
        return d
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
        /** The first match rating (평점 1.0, seasons before 평점 2.0): 6.5 + 3.5 x WPA in [3, 10]. */
        fun rating(wpa: Double): Double = (6.5 + 3.5 * wpa).coerceIn(3.0, 10.0)

        /**
         * 평점 2.0 of one side of a game: [RatingScale.BASE] + [Z] x its score against the league's
         * ([RatingScale.mean], [RatingScale.sd]) + [WPA] x its win probability added + [RESULT]
         * for a win (minus for a loss) + [YACHT], [BONUS] (upper bonus), [COMEBACK] (a win after
         * its P(win) fell to [RatingScale.COMEBACK_P]) and [BIG] (300 points or more), less the
         * league's mean of those parts ([RatingScale.center]), so a season's average stays at 6.0
         * however the league plays. In [1, 10], in steps of 0.005 (the sums stay exact).
         */
        fun rating(own: Int, opp: Int, wpa: Double, minP: Double, card: Board, scale: RatingScale): Double =
            rating(own, parts(own, opp, wpa, minP, (card[Category.YACHT] ?: 0) > 0, card.bonus > 0), scale)

        /** [rating] of a side that scored [own], with the other [parts] of its rating. */
        fun rating(own: Int, parts: Double, scale: RatingScale): Double = (Math.round(raw(own, parts, scale) * STEPS) / STEPS).coerceIn(1.0, 10.0)

        /** [rating] before its steps and its bounds: tells two games at 10.0 apart. */
        fun raw(own: Int, parts: Double, scale: RatingScale): Double =
            RatingScale.BASE + Z * (own - scale.mean) / (scale.sd.takeIf { it > 0 } ?: RatingScale.FALLBACK.sd) + parts - scale.center

        /** The parts of [rating] besides the score: what [RatingScale.center] averages over a season. */
        fun parts(own: Int, opp: Int, wpa: Double, minP: Double, yacht: Boolean, bonus: Boolean): Double {
            var x = WPA * wpa + when {
                own > opp -> RESULT
                own < opp -> -RESULT
                else -> 0.0
            }
            if (yacht) x += YACHT
            if (bonus) x += BONUS
            if (own > opp && minP <= RatingScale.COMEBACK_P) x += COMEBACK
            if (own >= Drama.BIG_SCORE) x += BIG
            return x
        }

        // 평점 2.0's weights, tuned on three played seasons of 1000 players (S15 hand-off): a
        // game's rating mostly between 2.3 and 9.9, eligible players' season averages from about
        // 4.5 to 7.8 (1부 SD 0.45-0.55), the league's average 6.0.
        const val Z = 1.4
        const val WPA = 1.5
        const val RESULT = 0.4
        const val YACHT = 0.2
        const val BONUS = 0.3
        const val COMEBACK = 0.3
        const val BIG = 0.3
        private const val STEPS = 200.0

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

/**
 * What a season's match ratings (평점 2.0, [GameFacts.rating]) are measured against: the league's
 * game scores ([mean], [sd]) and the mean of a rating's other parts ([center], [GameFacts.parts]),
 * all over the official games of the season before (in a league's first season its own games so
 * far, [StatsBook.ratingScale]). The same for every league of a season. [version] 1 is the first
 * rating (평점 1.0, WPA only), kept for a season that began before 평점 2.0.
 */
data class RatingScale(val mean: Double, val sd: Double, val center: Double = 0.0, val version: Int = 2) {
    companion object {
        /** A season's average match rating. */
        const val BASE = 6.0

        /** A win whose own P(win) fell to this or below is a comeback (the statistics' [AiStats.COMEBACK_P] counts below it). */
        const val COMEBACK_P = 0.2

        /** Before the league's first games: about a league of the hand-made AIs. */
        val FALLBACK = RatingScale(230.0, 45.0)

        /** The first rating (평점 1.0), for a season that began with it. */
        val V1 = RatingScale(230.0, 45.0, version = 1)
    }
}

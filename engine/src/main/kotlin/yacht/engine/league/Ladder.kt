package yacht.engine.league

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Ranked-ladder tiers by rating (the max-expectation AI is 1500). */
enum class TierName(val label: String, val short: String) {
    IRON("아이언", "I"),
    BRONZE("브론즈", "B"),
    SILVER("실버", "S"),
    GOLD("골드", "G"),
    PLATINUM("플래티넘", "P"),
    EMERALD("에메랄드", "E"),
    DIAMOND("다이아몬드", "D"),
    MASTER("마스터", "M"),
    GRANDMASTER("그랜드마스터", "GM"),
    CHALLENGER("챌린저", "C"),
}

/** A tier with its division (4 = lowest .. 1; null from master up) and LP within it. */
data class Tier(val name: TierName, val division: Int?, val lp: Int) {
    val label: String get() = name.label + (division?.let { " ${ROMAN[it]}" } ?: "")

    /** Higher is better, for comparing promotions. */
    val order: Int get() = name.ordinal * 4 + (4 - (division ?: 4))

    companion object {
        private val ROMAN = arrayOf("", "I", "II", "III", "IV")

        /**
         * Lower rating bound of each tier from iron to diamond, then master: finer near the top,
         * where the strong AIs are only a few points apart (7 points = 1%p of win rate).
         */
        private val FLOORS = doubleArrayOf(0.0, 600.0, 1000.0, 1200.0, 1320.0, 1400.0, 1450.0, 1490.0)
        const val MASTER = 1490.0

        /** Grandmaster: the top [GM_RANK] from 1500; challenger: the top [CHALLENGER_RANK] from 1505. */
        const val GM_RANK = 10
        const val CHALLENGER_RANK = 3

        fun of(rating: Double, rank: Int): Tier {
            if (rating >= MASTER) {
                val lp = ((rating - MASTER) * 10).roundToInt()
                return when {
                    rank <= CHALLENGER_RANK && rating >= 1505 -> Tier(TierName.CHALLENGER, null, lp)
                    rank <= GM_RANK && rating >= 1500 -> Tier(TierName.GRANDMASTER, null, lp)
                    else -> Tier(TierName.MASTER, null, lp)
                }
            }
            var t = 0
            while (t < 6 && rating >= FLOORS[t + 1]) t++
            val lo = FLOORS[t]
            val width = (FLOORS[t + 1] - lo) / 4
            if (rating < lo) return Tier(TierName.entries[t], 4, 0)
            val step = ((rating - lo) / width).toInt().coerceIn(0, 3)
            val lp = (((rating - lo) - step * width) / width * 100).toInt().coerceIn(0, 99)
            return Tier(TierName.entries[t], 4 - step, lp)
        }
    }
}

/** Running record of one AI. */
class LadderEntry(val key: String) {
    var games = 0
    var wins = 0
    var ties = 0
    var losses = 0
    var scoreSum = 0L
    var best = 0
    var yachts = 0
    var bonuses = 0

    /** Positive: wins in a row; negative: losses in a row. */
    var streak = 0
    var bestStreak = 0

    /** Last results, oldest first: 'W', 'D', 'L'. */
    val recent = ArrayDeque<Char>()

    /** Rating after each of its recent matches. */
    val history = ArrayDeque<Int>()
    var peak = Double.NaN

    val winRate: Double get() = if (games == 0) 0.0 else (wins + 0.5 * ties) / games
    val avgScore: Double get() = if (games == 0) 0.0 else scoreSum.toDouble() / games

    internal fun copyFrom(o: LadderEntry) {
        games = o.games
        wins = o.wins
        ties = o.ties
        losses = o.losses
        scoreSum = o.scoreSum
        best = o.best
        yachts = o.yachts
        bonuses = o.bonuses
        streak = o.streak
        bestStreak = o.bestStreak
        recent.clear()
        recent.addAll(o.recent)
        history.clear()
        history.addAll(o.history)
        peak = o.peak
    }

    internal fun add(score: Int, other: Int, yacht: Boolean, bonus: Boolean) {
        games++
        scoreSum += score
        best = maxOf(best, score)
        if (yacht) yachts++
        if (bonus) bonuses++
        val r = when {
            score > other -> 'W'
            score == other -> 'D'
            else -> 'L'
        }
        when (r) {
            'W' -> {
                wins++
                streak = if (streak > 0) streak + 1 else 1
            }
            'L' -> {
                losses++
                streak = if (streak < 0) streak - 1 else -1
            }
            else -> {
                ties++
                streak = 0
            }
        }
        bestStreak = maxOf(bestStreak, streak)
        recent.addLast(r)
        while (recent.size > RECENT) recent.removeFirst()
    }

    companion object {
        const val RECENT = 10
        const val HISTORY = 40
    }
}

/** One game of a match: final totals, and whether each side made a Yacht and the bonus. */
data class GameScore(val a: Int, val b: Int, val aYacht: Boolean = false, val bYacht: Boolean = false, val aBonus: Boolean = false, val bBonus: Boolean = false)

/** A finished match (two games on the same dice, seats swapped) as the feed shows it. */
data class MatchRecord(
    val a: String,
    val b: String,
    val games: List<GameScore>,
    /** Rating change of each side over the match. */
    val deltaA: Int,
    val deltaB: Int,
    val time: Long,
    /** Short news for the feed: promotions, streaks, upsets. */
    val notes: List<String> = emptyList(),
    /** Where it was played: the competition (null for matches saved before), its stage or board label, the week. */
    val comp: Comp? = null,
    val label: String = "",
    val week: Int = SeasonCalendar.NONE,
    /** The season it was played in (0 when unknown): week names depend on the season's length. */
    val season: Int = 0,
)

/**
 * The live ladder: everyone's record, results between every pair, and ratings fitted to all
 * games so far (Bradley-Terry with ties as half a win, on the Elo scale; the max-expectation AI
 * is pinned at 1500). Not thread-safe: callers synchronise.
 *
 * A match moves only its two players ([record]: two minorisation steps each, O(n)); the full
 * fit, which every match nudges a little away from, runs now and then: [fit] here, or
 * [fitInput] / [solve] / [applyFit] with the solving off the caller's lock.
 */
class Ladder(keys: List<String>, private val anchor: String = "ev") {
    val keys: List<String> = keys.toList()
    private val index = this.keys.withIndex().associate { it.value to it.index }
    private val meIndex = index[ME] ?: -1
    private val n = this.keys.size
    val entries: List<LadderEntry> = this.keys.map { LadderEntry(it) }

    /** Results between the pairs that played (a league of thousands plays a small share of all pairs). */
    private val pairs = Pairs(n)
    private val strength = DoubleArray(n) { 1.0 }

    /** PRIOR plus the points each AI won: the numerator of its minorisation step. */
    private val winSum = DoubleArray(n) { PRIOR }
    private val ratings = DoubleArray(n) { Double.NaN }
    private val errors = DoubleArray(n) { Double.NaN }

    /** Placed AIs by rating, best first ([orderSize] of them), and each one's place in it (-1: not placed). */
    private val order = IntArray(n)
    private var orderSize = 0
    private val pos = IntArray(n) { -1 }

    /** AIs that played since the last [fitInput] (their games are not in that fit yet). */
    private val dirty = BooleanArray(n)

    /** Bumped by every change of the ratings, so views can tell they are stale. */
    var version = 0L
        private set
    val feed = ArrayDeque<MatchRecord>()
    var matches = 0L
        private set
    var games = 0L
        private set

    fun entry(key: String): LadderEntry? = index[key]?.let { entries[it] }
    fun rating(key: String): Double = index[key]?.let { ratings[it] } ?: Double.NaN
    fun error(key: String): Double = index[key]?.let { errors[it] } ?: Double.NaN
    fun placed(key: String): Boolean = (entry(key)?.games ?: 0) >= placementOf(key)

    /** Results between [a] and [b] over their careers: (a's points, games), ties counting half. */
    fun headToHead(a: String, b: String): Pair<Double, Int> {
        val i = index[a] ?: return 0.0 to 0
        val j = index[b] ?: return 0.0 to 0
        val s = pairs.slot(i, j)
        if (s < 0) return 0.0 to 0
        return pairs.points(s, i) to pairs.games[s]
    }

    /** Pairs of AIs that have played each other. */
    val pairCount: Int get() = pairs.size

    /** The last season whose end faded the results ([decay]). */
    var decayed = 0
        private set

    /**
     * What ranks are by: the rating less [RANK_ERRORS] times the part of its standard error above
     * [RANK_FREE_ERROR], so AIs fresh out of placement (a range of a hundred points or more) do
     * not jump over the established ones (of a hundred newcomers the luckiest is about 2.5
     * errors above its true level, and one error let several of them top the ladder), while AIs
     * with enough games (an error of a few points) are ranked by their rating alone, however
     * many games each played. Me with one error: a single player is not the luckiest of many.
     */
    private fun rankScore(i: Int): Double {
        val e = errors[i].let { if (it.isNaN()) 0.0 else it }
        return ratings[i] - if (i == meIndex) e else RANK_ERRORS * maxOf(0.0, e - RANK_FREE_ERROR)
    }

    /** The rank score of [key] (see [rankScore]); NaN before its first game. */
    fun rankScore(key: String): Double = index[key]?.let { rankScore(it) } ?: Double.NaN

    /** Placed AIs by rank score, best first, then the ones still in placement (most games first). */
    fun ranking(): List<String> {
        val out = ArrayList<String>(n)
        for (r in 0 until orderSize) out += keys[order[r]]
        (0 until n).filter { pos[it] < 0 }.sortedByDescending { entries[it].games }.mapTo(out) { keys[it] }
        return out
    }

    /** 1-based rank among placed AIs, or null while in placement. */
    fun rankOf(key: String): Int? {
        val i = index[key] ?: return null
        return if (pos[i] < 0) null else pos[i] + 1
    }

    private fun isPlaced(i: Int) = entries[i].games >= placementOf(keys[i]) && !ratings[i].isNaN()

    /** Moves [i] to its place in [order] after its rating changed (or it was just placed). */
    private fun reorder(i: Int) {
        if (pos[i] < 0) {
            if (!isPlaced(i)) return
            order[orderSize] = i
            pos[i] = orderSize++
        }
        var at = pos[i]
        val r = rankScore(i)
        while (at > 0 && rankScore(order[at - 1]) < r) {
            val k = order[at - 1]
            order[at] = k
            pos[k] = at
            at--
        }
        while (at < orderSize - 1 && rankScore(order[at + 1]) > r) {
            val k = order[at + 1]
            order[at] = k
            pos[k] = at
            at++
        }
        order[at] = i
        pos[i] = at
    }

    private fun rebuildOrder() {
        val placed = (0 until n).filter { isPlaced(it) }.sortedByDescending { rankScore(it) }
        pos.fill(-1)
        orderSize = 0
        for (i in placed) {
            order[orderSize] = i
            pos[i] = orderSize++
        }
    }

    /**
     * The tier by the rating shown (the legend's thresholds); the rank for the top tiers. An AI
     * fresh out of placement can sit below one with a lower tier until its error shrinks.
     */
    fun tier(key: String): Tier? {
        val rank = rankOf(key) ?: return null
        return Tier.of(rating(key), rank)
    }

    /** Records a match of [a] against [b] and refits the ratings. */
    fun record(
        a: String,
        b: String,
        results: List<GameScore>,
        time: Long = System.currentTimeMillis(),
        comp: Comp? = null,
        label: String = "",
        week: Int = SeasonCalendar.NONE,
        season: Int = 0,
    ): MatchRecord {
        val i = index.getValue(a)
        val j = index.getValue(b)
        val before = mapOf(a to rating(a), b to rating(b))
        val tierBefore = mapOf(a to tier(a), b to tier(b))
        val slot = pairs.slotOrAdd(i, j)
        for (g in results) {
            entries[i].add(g.a, g.b, g.aYacht, g.aBonus)
            entries[j].add(g.b, g.a, g.bYacht, g.bBonus)
            val s = if (g.a > g.b) 1.0 else if (g.a == g.b) 0.5 else 0.0
            pairs.add(slot, i, s)
            winSum[i] += s
            winSum[j] += 1 - s
            games++
        }
        matches++
        dirty[i] = true
        dirty[j] = true
        // One match moves the fit only a little, and mostly for its two players: a couple of
        // steps for them (the full fit, all pairs, runs now and then off the lock).
        settle(i, j)
        for (k in intArrayOf(i, j)) {
            val e = entries[k]
            val r = ratings[k]
            if (!r.isNaN()) {
                e.history.addLast(r.roundToInt())
                while (e.history.size > LadderEntry.HISTORY) e.history.removeFirst()
            }
        }
        fun delta(k: String) = (rating(k) - before.getValue(k)).takeIf { !it.isNaN() }?.roundToInt() ?: 0
        val rec = MatchRecord(a, b, results, delta(a), delta(b), time, notes(a, b, results, before, tierBefore), comp, label, week, season)
        feed.addFirst(rec)
        while (feed.size > FEED) feed.removeLast()
        return rec
    }

    private fun notes(a: String, b: String, results: List<GameScore>, before: Map<String, Double>, tierBefore: Map<String, Tier?>): List<String> {
        val out = ArrayList<String>()
        for (k in listOf(a, b)) {
            val e = entry(k)!!
            val was = tierBefore[k]
            val now = tier(k)
            // Tiers, not divisions: a division changes every few games near the top.
            when {
                was == null && now != null -> out += "배치 완료|$k|${now.label}"
                was != null && now != null && now.name > was.name -> out += "승급|$k|${now.name.label}"
                was != null && now != null && now.name < was.name -> out += "강등|$k|${now.name.label}"
            }
            if (e.streak >= STREAK_NEWS && e.streak % STREAK_NEWS == 0) out += "연승|$k|${e.streak}"
            if (e.streak <= -STREAK_NEWS && e.streak % STREAK_NEWS == 0) out += "연패|$k|${-e.streak}"
            results.map { if (k == a) it.a else it.b }.filter { it >= RECORD_SCORE }.maxOrNull()?.let { out += "대기록|$k|$it" }
        }
        // An upset: the lower-rated side took both games against someone rated well above it.
        val ra = before.getValue(a)
        val rb = before.getValue(b)
        if (!ra.isNaN() && !rb.isNaN() && placed(a) && placed(b)) {
            val aWonAll = results.all { it.a > it.b }
            val bWonAll = results.all { it.b > it.a }
            if (aWonAll && rb - ra >= UPSET) out += "이변|$a|${(rb - ra).roundToInt()}"
            if (bWonAll && ra - rb >= UPSET) out += "이변|$b|${(ra - rb).roundToInt()}"
        }
        return out
    }

    /** One minorisation step of [i]'s strength against everyone it played (O(its opponents)). */
    private fun step(i: Int) {
        val p = strength[i]
        val row = pairs.rows[i]
        var denom = 2 * PRIOR / (p + 1.0)
        for (k in 0 until row.size) {
            val s = row[k]
            val g = pairs.fitGames[s]
            if (g > 0) denom += g / (p + strength[pairs.other(s, i)])
        }
        strength[i] = winSum[i] / denom
    }

    /** The anchor's index while it has games (its rating is 1500 by definition), else null. */
    private fun anchorIndex(): Int? = index[anchor]?.takeIf { entries[it].games > 0 }

    /** Rating scale offset: the anchor's log-strength, or the mean over everyone with games. */
    private fun base(): Double {
        anchorIndex()?.let { return log10(strength[it]) }
        var sum = 0.0
        var count = 0
        for (i in 0 until n) if (entries[i].games > 0) {
            sum += log10(strength[i])
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** Standard error of [i]'s rating from the curvature of the log-likelihood. */
    private fun errorOf(i: Int): Double {
        val p = strength[i]
        var info = 2 * PRIOR * p / ((p + 1.0) * (p + 1.0))
        val row = pairs.rows[i]
        for (k in 0 until row.size) {
            val slot = row[k]
            val g = pairs.fitGames[slot]
            if (g > 0) {
                val q = strength[pairs.other(slot, i)]
                val s = p + q
                info += g * p * q / (s * s)
            }
        }
        return 400 / ln(10.0) / sqrt(info)
    }

    /**
     * Fades the fit's weight of every result of [changed] (players whose play changed: they grew
     * or declined) by [factor], so ratings follow who they are now; their career records and
     * head-to-heads stay. Refit afterwards ([fit] or [fitInput]).
     */
    fun decay(changed: Set<String>, factor: Double, season: Int = decayed + 1) {
        // Once per season: a season end replayed after a kill fades nothing twice.
        if (season <= decayed) return
        decayed = season
        val idx = changed.mapNotNull { index[it] }.toHashSet()
        if (idx.isEmpty()) return
        for (s in 0 until pairs.size) {
            if (pairs.a[s] !in idx && pairs.b[s] !in idx) continue
            pairs.fitPts[s] *= factor
            pairs.fitGames[s] *= factor
            // Faded out: no longer in the fit (the career record stays).
            if (pairs.fitGames[s] < MIN_WEIGHT) {
                pairs.fitPts[s] = 0.0
                pairs.fitGames[s] = 0.0
            }
        }
        recountWins()
        for (i in idx) dirty[i] = true
        version++
    }

    private fun recountWins() {
        winSum.fill(PRIOR)
        for (s in 0 until pairs.size) {
            winSum[pairs.a[s]] += pairs.fitPts[s]
            winSum[pairs.b[s]] += pairs.fitGames[s] - pairs.fitPts[s]
        }
    }

    /**
     * This ladder for [newKeys] (the roster changed): the records, results and ratings of the
     * AIs in both, empty entries for the new ones; AIs gone take their results with them. A warm
     * fit follows (O(results), no copy through text).
     */
    fun rekey(newKeys: List<String>): Ladder {
        val l = Ladder(newKeys, anchor)
        val map = IntArray(n) { i -> l.index[keys[i]] ?: -1 }
        for (i in 0 until n) {
            val t = map[i]
            if (t < 0) continue
            l.entries[t].copyFrom(entries[i])
            l.strength[t] = strength[i]
            l.ratings[t] = ratings[i]
            l.errors[t] = errors[i]
        }
        for (s in 0 until pairs.size) {
            val x = map[pairs.a[s]]
            val y = map[pairs.b[s]]
            if (x < 0 || y < 0) continue
            l.pairs.copy(pairs, s, x, y)
        }
        l.recountWins()
        l.matches = matches
        l.games = games
        l.decayed = decayed
        for (m in feed) if (m.a in l.index && m.b in l.index) l.feed.addLast(m)
        l.fit()
        return l
    }

    /**
     * After [i] and [j] played: two alternating steps for each (the anchor is never stepped, so
     * nobody else's rating moves), then their ratings, ranges, peaks and places.
     */
    private fun settle(i: Int, j: Int) {
        val a = anchorIndex()
        repeat(2) {
            if (i != a) step(i)
            if (j != a) step(j)
        }
        // Without the anchor the scale is the mean over everyone, which this match moved; and
        // the anchor's first match moves everyone onto its scale.
        val rescale = a == null || ratings[a].isNaN()
        if (rescale) {
            val b = base()
            for (k in 0 until n) if (entries[k].games > 0) ratings[k] = 1500 + 400 * (log10(strength[k]) - b)
        } else {
            val b = log10(strength[a!!])
            for (k in intArrayOf(i, j)) ratings[k] = 1500 + 400 * (log10(strength[k]) - b)
        }
        for (k in intArrayOf(i, j)) {
            errors[k] = errorOf(k)
            val e = entries[k]
            if (isPlaced(k) && (e.peak.isNaN() || ratings[k] > e.peak)) e.peak = ratings[k]
            if (!rescale) reorder(k)
        }
        if (rescale) rebuildOrder()
        version++
    }

    /**
     * The results the full fit needs, copied (under the caller's lock) so it can be solved
     * elsewhere: the AIs with games ([active]) and the weighted results between them as edges
     * (local indices, side A's points, games).
     */
    class FitInput internal constructor(
        internal val active: IntArray,
        internal val edgeA: IntArray,
        internal val edgeB: IntArray,
        internal val edgePoints: DoubleArray,
        internal val edgeGames: DoubleArray,
        internal val strength: DoubleArray,
        internal val anchor: Int,
        internal val owner: Ladder,
    )

    /** A solved full fit, for [applyFit]. */
    class FitResult internal constructor(
        internal val input: FitInput,
        internal val strength: DoubleArray,
        internal val ratings: DoubleArray,
        internal val errors: DoubleArray,
    )

    /** Copies what the full fit needs (O(results) copying, no solving): see [solve] and [applyFit]. */
    fun fitInput(): FitInput {
        val active = (0 until n).filter { entries[it].games > 0 }.toIntArray()
        val local = IntArray(n) { -1 }
        for ((a, i) in active.withIndex()) local[i] = a
        var e = 0
        for (s in 0 until pairs.size) if (pairs.fitGames[s] > 0 && local[pairs.a[s]] >= 0 && local[pairs.b[s]] >= 0) e++
        val ea = IntArray(e)
        val eb = IntArray(e)
        val ep = DoubleArray(e)
        val eg = DoubleArray(e)
        var k = 0
        for (s in 0 until pairs.size) {
            if (pairs.fitGames[s] <= 0) continue
            val x = local[pairs.a[s]]
            val y = local[pairs.b[s]]
            if (x < 0 || y < 0) continue
            ea[k] = x
            eb[k] = y
            ep[k] = pairs.fitPts[s]
            eg[k] = pairs.fitGames[s]
            k++
        }
        dirty.fill(false)
        val anchorAt = anchorIndex()?.let { local[it] } ?: -1
        return FitInput(active, ea, eb, ep, eg, DoubleArray(active.size) { strength[active[it]] }, anchorAt, this)
    }

    /** Puts a solved fit in place; AIs that played since [fitInput] get their quick steps again. */
    fun applyFit(r: FitResult) {
        val input = r.input
        require(input.owner === this) { "a fit of another ladder" }
        for ((a, i) in input.active.withIndex()) {
            strength[i] = r.strength[a]
            ratings[i] = r.ratings[a]
            errors[i] = r.errors[a]
        }
        val since = (0 until n).filter { dirty[it] }
        for (i in since) {
            if (i != anchorIndex()) {
                step(i)
                step(i)
            }
        }
        if (since.isNotEmpty()) {
            val b = base()
            for (i in 0 until n) if (entries[i].games > 0) ratings[i] = 1500 + 400 * (log10(strength[i]) - b)
            for (i in since) errors[i] = errorOf(i)
        }
        // Every refit moves everyone a little: peaks follow all placed AIs.
        for ((k, e) in entries.withIndex()) {
            val x = ratings[k]
            if (isPlaced(k) && (e.peak.isNaN() || x > e.peak)) e.peak = x
        }
        rebuildOrder()
        version++
    }

    /** The full fit, solved here. */
    fun fit() {
        if (entries.none { it.games > 0 }) return
        applyFit(solve(fitInput()))
    }

    // ---- saving --------------------------------------------------------------------------------

    fun encode(): String = buildString {
        append("ladder 1\n")
        append("totals $matches $games\n")
        if (decayed > 0) append("decayed $decayed\n")
        for (e in entries) {
            if (e.games == 0) continue
            append("p ${e.key} ${e.games} ${e.wins} ${e.ties} ${e.losses} ${e.scoreSum} ${e.best} ${e.yachts} ${e.bonuses} ")
            append("${e.streak} ${e.bestStreak} ${e.recent.joinToString("").ifEmpty { "-" }} ")
            append("${if (e.peak.isNaN()) "-" else "%.2f".format(java.util.Locale.ROOT, e.peak)} ${e.history.joinToString(",").ifEmpty { "-" }}")
            // The fitted strength, so a league read back starts warm (older versions stop at the history).
            append(' ').append(strength[index.getValue(e.key)]).append('\n')
        }
        for (s in 0 until pairs.size) {
            val i = pairs.a[s]
            val j = pairs.b[s]
            append("h ${keys[i]} ${keys[j]} ${pairs.pts[s]} ${pairs.games[s]}")
            // The fit's faded weights, when they differ from the career record.
            if (pairs.fitGames[s] != pairs.games[s].toDouble() || pairs.fitPts[s] != pairs.pts[s]) append(" ${pairs.fitPts[s]} ${pairs.fitGames[s]}")
            append('\n')
        }
        for (m in feed.reversed()) {
            val gs = m.games.joinToString(";") { g -> "${g.a},${g.b},${flag(g.aYacht)}${flag(g.bYacht)}${flag(g.aBonus)}${flag(g.bBonus)}" }
            append("m ${m.a} ${m.b} $gs ${m.deltaA} ${m.deltaB} ${m.time} ${m.notes.joinToString("\u001f").ifEmpty { "-" }.replace(' ', ' ')}")
            // Where it was played, a field older versions do not read.
            m.comp?.let { c -> append(" ${c.code}:${m.week}:${MediaText.esc(m.label)}").append(if (m.season > 0) " ${m.season}" else "") }
            append('\n')
        }
    }

    private fun flag(b: Boolean) = if (b) '1' else '0'

    companion object {
        /** Games before an AI gets a rank and a tier (ten matches: ratings swing a lot before that). */
        const val PLACEMENT = 20

        /** The player's own key: rated from ranked games against the AIs, never scheduled by the league. */
        const val ME = "me"

        /** A person plays one game at a time: placement after five. */
        const val ME_PLACEMENT = 5

        fun placementOf(key: String) = if (key == ME) ME_PLACEMENT else PLACEMENT
        const val FEED = 80
        const val STREAK_NEWS = 5
        const val UPSET = 150.0

        /** A game score worth a headline. */
        const val RECORD_SCORE = 300
        private const val PRIOR = 0.1

        /** Results faded below this weight leave the fit ([decay]). */
        private const val MIN_WEIGHT = 1e-3

        /** Standard errors taken off the rating for the ranking (see [rankScore]). */
        const val RANK_ERRORS = 3.0

        /** An error up to this (about ±20 shown, a few hundred games) costs no ranking place. */
        const val RANK_FREE_ERROR = 10.0
        private const val MAX_ITER = 3000

        /** Newton steps and conjugate-gradient passes per step at most; the largest log-strength step. */
        private const val MAX_NEWTON = 50
        private const val MAX_CG = 400
        private const val MAX_STEP = 2.0

        /**
         * Bradley-Terry strengths by minorisation-maximisation, from the last strengths. Each AI
         * also gets a tenth of a win and of a loss against a virtual average player, which keeps
         * unbeaten or winless records finite. Pure: may run off the lock the input came from.
         */
        fun solve(input: FitInput, maxSweeps: Int = MAX_ITER): FitResult {
            val m = input.active.size
            // Each AI's opponents as one compact adjacency (both directions of every edge).
            val deg = IntArray(m + 1)
            for (k in input.edgeA.indices) {
                deg[input.edgeA[k] + 1]++
                deg[input.edgeB[k] + 1]++
            }
            for (a in 0 until m) deg[a + 1] += deg[a]
            val nbr = IntArray(deg[m])
            val wt = DoubleArray(deg[m])
            val fill = deg.copyOf(m)
            val wins = DoubleArray(m) { PRIOR }
            for (k in input.edgeA.indices) {
                val x = input.edgeA[k]
                val y = input.edgeB[k]
                val g = input.edgeGames[k]
                nbr[fill[x]] = y; wt[fill[x]++] = g
                nbr[fill[y]] = x; wt[fill[y]++] = g
                wins[x] += input.edgePoints[k]
                wins[y] += g - input.edgePoints[k]
            }
            val theta = DoubleArray(m) { ln(input.strength[it]) }
            newton(m, deg, nbr, wt, wins, theta, maxSweeps)
            val pi = DoubleArray(m) { exp(theta[it]) }
            val base = if (input.anchor >= 0) log10(pi[input.anchor]) else (0 until m).sumOf { log10(pi[it]) } / maxOf(1, m)
            val ratings = DoubleArray(m) { 1500 + 400 * (log10(pi[it]) - base) }
            val errors = DoubleArray(m) { a ->
                val p = pi[a]
                var info = 2 * PRIOR * p / ((p + 1.0) * (p + 1.0))
                for (k in deg[a] until deg[a + 1]) {
                    val q = pi[nbr[k]]
                    val s = p + q
                    info += wt[k] * p * q / (s * s)
                }
                400 / ln(10.0) / sqrt(info)
            }
            return FitResult(input, pi, ratings, errors)
        }

        /**
         * The maximum of the Bradley-Terry likelihood (with each AI's tenth of a win and of a loss
         * against a virtual average player) by Newton steps on the log-strengths [theta], each
         * solved by conjugate gradients with a diagonal preconditioner: a few steps from any start,
         * where minorisation needs hundreds of sweeps across a league of thousands (its divisions
         * are linked by a few games only). Stops when no log-strength moves by 1e-7 (0.00002
         * rating points), or after [maxWork] passes over the results.
         */
        private fun newton(m: Int, deg: IntArray, nbr: IntArray, wt: DoubleArray, wins: DoubleArray, theta: DoubleArray, maxWork: Int) {
            if (m == 0) return
            val grad = DoubleArray(m)
            val diag = DoubleArray(m)
            val off = DoubleArray(nbr.size)
            val x = DoubleArray(m)
            val r = DoubleArray(m)
            val z = DoubleArray(m)
            val d = DoubleArray(m)
            val q = DoubleArray(m)
            var work = 0
            repeat(MAX_NEWTON) {
                // Gradient and (minus) Hessian at theta.
                for (a in 0 until m) {
                    val ea = exp(theta[a])
                    val sp = ea / (ea + 1.0)
                    var expected = 2 * PRIOR * sp
                    var h = 2 * PRIOR * sp * (1 - sp)
                    for (k in deg[a] until deg[a + 1]) {
                        val p = 1.0 / (1.0 + exp(theta[nbr[k]] - theta[a]))
                        expected += wt[k] * p
                        val c = wt[k] * p * (1 - p)
                        off[k] = c
                        h += c
                    }
                    grad[a] = wins[a] - expected
                    diag[a] = h
                }
                // Solve (-H) x = grad: -H is diag minus the off-diagonal weights.
                fun apply(v: DoubleArray, out: DoubleArray) {
                    for (a in 0 until m) {
                        var t = diag[a] * v[a]
                        for (k in deg[a] until deg[a + 1]) t -= off[k] * v[nbr[k]]
                        out[a] = t
                    }
                }
                x.fill(0.0)
                for (a in 0 until m) {
                    r[a] = grad[a]
                    z[a] = r[a] / diag[a]
                    d[a] = z[a]
                }
                var rz = (0 until m).sumOf { r[it] * z[it] }
                val target = 1e-20 + 1e-18 * m
                for (iter in 0 until MAX_CG) {
                    if (rz <= target) break
                    apply(d, q)
                    work++
                    val dq = (0 until m).sumOf { d[it] * q[it] }
                    if (dq <= 0) break
                    val alpha = rz / dq
                    for (a in 0 until m) {
                        x[a] += alpha * d[a]
                        r[a] -= alpha * q[a]
                        z[a] = r[a] / diag[a]
                    }
                    val next = (0 until m).sumOf { r[it] * z[it] }
                    val beta = next / rz
                    rz = next
                    for (a in 0 until m) d[a] = z[a] + beta * d[a]
                }
                // A damped step (the likelihood is concave, a long step can overshoot from far away).
                var step = 0.0
                for (a in 0 until m) step = maxOf(step, abs(x[a]))
                val scale = if (step > MAX_STEP) MAX_STEP / step else 1.0
                for (a in 0 until m) theta[a] += scale * x[a]
                if (step * scale < 1e-7 || work > maxWork) return
            }
        }

        /** Restores [text] onto a ladder for [keys]; unknown AIs (an older roster) are skipped. */
        /** One saved line into [l]; every field is read before anything is set (a cut line changes nothing). */
        private fun decodeLine(l: Ladder, f: List<String>) {
            when (f.firstOrNull()) {
                "decayed" -> l.decayed = f[1].toInt()
                "totals" -> {
                    val m = f[1].toLong()
                    val g = f[2].toLong()
                    l.matches = m
                    l.games = g
                }
                "p" -> {
                    val e = l.entry(f[1]) ?: return
                    val nums = (2..11).map { if (it == 6) 0 else f[it].toInt() }
                    val scoreSum = f[6].toLong()
                    val recent = if (f[12] != "-") f[12].toList() else emptyList()
                    val peak = if (f[13] == "-") Double.NaN else f[13].toDouble()
                    val history = if (f[14] != "-") f[14].split(',').map { it.toInt() } else emptyList()
                    e.games = nums[0]
                    e.wins = nums[1]
                    e.ties = nums[2]
                    e.losses = nums[3]
                    e.scoreSum = scoreSum
                    e.best = nums[5]
                    e.yachts = nums[6]
                    e.bonuses = nums[7]
                    e.streak = nums[8]
                    e.bestStreak = nums[9]
                    recent.forEach { e.recent.addLast(it) }
                    val strength = f.getOrNull(15)?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
                    e.peak = peak
                    history.forEach { e.history.addLast(it) }
                    strength?.let { l.strength[l.index.getValue(e.key)] = it }
                }
                "h" -> {
                    val i = l.index[f[1]] ?: return
                    val j = l.index[f[2]] ?: return
                    val pts = f[3].toDouble()
                    val n = f[4].toInt()
                    val fitPts = f.getOrNull(5)?.toDoubleOrNull() ?: pts
                    val fitGames = f.getOrNull(6)?.toDoubleOrNull() ?: n.toDouble()
                    require(n >= 0 && pts in 0.0..n.toDouble() && fitGames >= 0 && fitPts in 0.0..fitGames)
                    l.pairs.set(i, j, pts, n, fitPts, fitGames)
                }
                "m" -> {
                    if (l.index[f[1]] == null || l.index[f[2]] == null) return
                    val games = f[3].split(';').map { g ->
                        val p = g.split(',')
                        val fl = p[2]
                        GameScore(p[0].toInt(), p[1].toInt(), fl[0] == '1', fl[1] == '1', fl[2] == '1', fl[3] == '1')
                    }
                    val notes = f[7].replace('\u00a0', ' ').takeIf { it != "-" }?.split('\u001f') ?: emptyList()
                    val where = f.getOrNull(8)?.split(':', limit = 3)?.takeIf { it.size == 3 }
                    val comp = where?.get(0)?.singleOrNull()?.let { Comp.of(it) }
                    val week = where?.get(1)?.toIntOrNull() ?: SeasonCalendar.NONE
                    val label = if (comp == null) "" else MediaText.unesc(where!![2])
                    val season = if (comp == null) 0 else f.getOrNull(9)?.toIntOrNull() ?: 0
                    l.feed.addFirst(MatchRecord(f[1], f[2], games, f[4].toInt(), f[5].toInt(), f[6].toLong(), notes, comp, label, if (comp == null) SeasonCalendar.NONE else week, season))
                }
            }
        }

        fun decode(keys: List<String>, text: String, anchor: String = "ev"): Ladder {
            val l = Ladder(keys, anchor)
            // Line by line: a line cut off by a kill mid-save (older versions saved in place)
            // loses only itself, not the whole league.
            for (line in text.lines()) {
                val f = line.split(' ')
                // A bad line is skipped; running out of memory is not (it would lose the league).
                try {
                    decodeLine(l, f)
                } catch (e: Exception) {
                    continue
                }
            }
            l.recountWins()
            // Entries of AIs no longer in the roster are gone: their games stay out of the fit
            // (from the saved strengths it converges in a few sweeps).
            l.fit()
            return l
        }
    }
}

/** A map from non-negative longs to ints without boxing (open addressing); -1 for a missing key. */
internal class LongIntMap {
    private var keys = LongArray(64) { EMPTY }
    private var values = IntArray(64)
    private var count = 0

    private fun at(k: Long, ks: LongArray): Int {
        val mask = ks.size - 1
        var h = ((k * -0x61c8864680b583ebL) ushr 32).toInt() and mask
        while (ks[h] != EMPTY && ks[h] != k) h = (h + 1) and mask
        return h
    }

    operator fun get(k: Long): Int {
        val h = at(k, keys)
        return if (keys[h] == k) values[h] else -1
    }

    operator fun set(k: Long, v: Int) {
        if ((count + 1) * 10 > keys.size * 7) grow()
        val h = at(k, keys)
        if (keys[h] != k) {
            keys[h] = k
            count++
        }
        values[h] = v
    }

    private fun grow() {
        val ok = keys
        val ov = values
        keys = LongArray(ok.size * 2) { EMPTY }
        values = IntArray(ok.size * 2)
        for (i in ok.indices) if (ok[i] != EMPTY) {
            val h = at(ok[i], keys)
            keys[h] = ok[i]
            values[h] = ov[i]
        }
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}

/** A growable list of ints. */
internal class IntList {
    private var data = IntArray(4)
    var size = 0
        private set

    operator fun get(i: Int): Int = data[i]

    fun add(x: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = x
    }
}

/**
 * The results between pairs of AIs that played (slot per pair, a < b by index): career points
 * and games for head-to-heads, and the fit's weights ([fitPts], [fitGames]), which [Ladder.decay]
 * fades as players change. [rows] lists each AI's slots.
 */
internal class Pairs(n: Int) {
    var size = 0
        private set
    var a = IntArray(16)
        private set
    var b = IntArray(16)
        private set

    /** Side a's points and the games (career). */
    var pts = DoubleArray(16)
        private set
    var games = IntArray(16)
        private set

    /** The same as the fit weighs them. */
    var fitPts = DoubleArray(16)
        private set
    var fitGames = DoubleArray(16)
        private set
    val rows: Array<IntList> = Array(n) { IntList() }
    private val slots = LongIntMap()

    private fun key(i: Int, j: Int): Long = if (i < j) (i.toLong() shl 32) or j.toLong() else (j.toLong() shl 32) or i.toLong()

    fun slot(i: Int, j: Int): Int = slots[key(i, j)]

    fun slotOrAdd(i: Int, j: Int): Int {
        val k = key(i, j)
        slots[k].let { if (it >= 0) return it }
        if (size == a.size) {
            val c = size * 2
            a = a.copyOf(c); b = b.copyOf(c); pts = pts.copyOf(c); games = games.copyOf(c)
            fitPts = fitPts.copyOf(c); fitGames = fitGames.copyOf(c)
        }
        val s = size++
        a[s] = minOf(i, j)
        b[s] = maxOf(i, j)
        slots[k] = s
        rows[i].add(s)
        rows[j].add(s)
        return s
    }

    fun other(s: Int, i: Int): Int = if (a[s] == i) b[s] else a[s]

    /** [i]'s career points in slot [s]. */
    fun points(s: Int, i: Int): Double = if (a[s] == i) pts[s] else games[s] - pts[s]

    /** One game in slot [s]: [i] scored [x] (1 won, 0.5 drawn, 0 lost). */
    fun add(s: Int, i: Int, x: Double) {
        val forA = if (a[s] == i) x else 1 - x
        pts[s] += forA
        games[s]++
        fitPts[s] += forA
        fitGames[s] += 1.0
    }

    /** A saved pair: [i]'s points (career and fit) against [j]. */
    fun set(i: Int, j: Int, points: Double, n: Int, fitPoints: Double, fitN: Double) {
        val s = slotOrAdd(i, j)
        val flip = a[s] != i
        pts[s] = if (flip) n - points else points
        games[s] = n
        fitPts[s] = if (flip) fitN - fitPoints else fitPoints
        fitGames[s] = fitN
    }

    /** Slot [s] of [from] as the pair ([x], [y]) here (x was from's a). */
    fun copy(from: Pairs, s: Int, x: Int, y: Int) = set(x, y, from.pts[s], from.games[s], from.fitPts[s], from.fitGames[s])
}

/**
 * Who plays next: a random AI (now and then the one with the fewest games, so newcomers place
 * quickly) against someone of similar rating, with a little chance of anyone at all.
 */
object Matchmaker {
    fun pick(ladder: Ladder, rng: Random, busy: Set<String>, among: Collection<String>? = null): Pair<String, String>? {
        val free = (among ?: ladder.keys).filter { it !in busy && it != Ladder.ME }
        if (free.isEmpty()) return null
        val a = if (rng.nextDouble() < 0.3) {
            val least = free.minOf { ladder.entry(it)!!.games }
            free.filter { ladder.entry(it)!!.games == least }.let { it[rng.nextInt(it.size)] }
        } else free[rng.nextInt(free.size)]
        val others = ladder.keys.filter { it != a && it !in busy && it != Ladder.ME }
        if (others.isEmpty()) return null
        val ra = ladder.rating(a)
        val weights = others.map { b ->
            val rb = ladder.rating(b)
            when {
                !ladder.placed(a) || ra.isNaN() -> 1.0
                !ladder.placed(b) || rb.isNaN() -> 0.3
                else -> exp(-((ra - rb) / SPREAD).pow(2)) + EXPLORE
            }
        }
        var x = rng.nextDouble() * weights.sum()
        for ((k, w) in weights.withIndex()) {
            x -= w
            if (x <= 0) return a to others[k]
        }
        return a to others.last()
    }

    /**
     * An opponent for the player's ranked game: a placed AI near [rating] (my rating, or a
     * starting guess while I am in placement), rarely someone further away.
     */
    fun opponentFor(ladder: Ladder, rating: Double, rng: Random, among: Collection<String>? = null): String? {
        val pool = (among ?: ladder.keys).filter { it != Ladder.ME && ladder.placed(it) && !ladder.rating(it).isNaN() }
        return opponentFor(pool.map { it to ladder.rating(it) }, rating, rng)
    }

    /** [opponentFor] from placed AIs and their ratings as a snapshot shows them. */
    fun opponentFor(pool: List<Pair<String, Double>>, rating: Double, rng: Random): String? {
        if (pool.isEmpty()) return null
        val weights = pool.map { (_, r) -> exp(-((r - rating) / (SPREAD / 2)).pow(2)) + EXPLORE / 10 }
        var x = rng.nextDouble() * weights.sum()
        for ((k, w) in weights.withIndex()) {
            x -= w
            if (x <= 0) return pool[k].first
        }
        return pool.last().first
    }

    /** Opponents are usually within about this many rating points. */
    private const val SPREAD = 150.0
    private const val EXPLORE = 0.03
}

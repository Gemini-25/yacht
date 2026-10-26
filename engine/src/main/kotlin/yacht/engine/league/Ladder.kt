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

    /** points[i][j]: games won by i against j (ties count half); played[i][j]: games between them. */
    private val points = Array(n) { DoubleArray(n) }
    private val played = Array(n) { IntArray(n) }
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

    /** Results between [a] and [b]: (a's points, games), ties counting half. */
    fun headToHead(a: String, b: String): Pair<Double, Int> {
        val i = index[a] ?: return 0.0 to 0
        val j = index[b] ?: return 0.0 to 0
        return points[i][j] to played[i][j]
    }

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
    fun record(a: String, b: String, results: List<GameScore>, time: Long = System.currentTimeMillis()): MatchRecord {
        val i = index.getValue(a)
        val j = index.getValue(b)
        val before = mapOf(a to rating(a), b to rating(b))
        val tierBefore = mapOf(a to tier(a), b to tier(b))
        for (g in results) {
            entries[i].add(g.a, g.b, g.aYacht, g.aBonus)
            entries[j].add(g.b, g.a, g.bYacht, g.bBonus)
            val s = if (g.a > g.b) 1.0 else if (g.a == g.b) 0.5 else 0.0
            points[i][j] += s
            points[j][i] += 1 - s
            winSum[i] += s
            winSum[j] += 1 - s
            played[i][j]++
            played[j][i]++
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
        val rec = MatchRecord(a, b, results, delta(a), delta(b), time, notes(a, b, results, before, tierBefore))
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

    /** One minorisation step of [i]'s strength against everyone it played (O(n)). */
    private fun step(i: Int) {
        val p = strength[i]
        val row = played[i]
        var denom = 2 * PRIOR / (p + 1.0)
        for (j in 0 until n) {
            val g = row[j]
            if (g > 0) denom += g / (p + strength[j])
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
        val row = played[i]
        for (j in 0 until n) {
            val g = row[j]
            if (g > 0) {
                val s = p + strength[j]
                info += g * p * strength[j] / (s * s)
            }
        }
        return 400 / ln(10.0) / sqrt(info)
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

    /** The results the full fit needs, copied (under the caller's lock) so it can be solved elsewhere. */
    class FitInput internal constructor(
        internal val active: IntArray,
        internal val points: Array<DoubleArray>,
        internal val played: Array<IntArray>,
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

    /** Copies what the full fit needs (O(n²) copying, no solving): see [solve] and [applyFit]. */
    fun fitInput(): FitInput {
        val active = (0 until n).filter { entries[it].games > 0 }.toIntArray()
        val m = active.size
        val pts = Array(m) { a -> DoubleArray(m) { b -> points[active[a]][active[b]] } }
        val pl = Array(m) { a -> IntArray(m) { b -> played[active[a]][active[b]] } }
        dirty.fill(false)
        val anchorAt = anchorIndex()?.let { active.indexOf(it) } ?: -1
        return FitInput(active, pts, pl, DoubleArray(m) { strength[active[it]] }, anchorAt, this)
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
        for (e in entries) {
            if (e.games == 0) continue
            append("p ${e.key} ${e.games} ${e.wins} ${e.ties} ${e.losses} ${e.scoreSum} ${e.best} ${e.yachts} ${e.bonuses} ")
            append("${e.streak} ${e.bestStreak} ${e.recent.joinToString("").ifEmpty { "-" }} ")
            append("${if (e.peak.isNaN()) "-" else "%.2f".format(java.util.Locale.ROOT, e.peak)} ${e.history.joinToString(",").ifEmpty { "-" }}\n")
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            if (played[i][j] > 0) append("h ${keys[i]} ${keys[j]} ${points[i][j]} ${played[i][j]}\n")
        }
        for (m in feed.reversed()) {
            val gs = m.games.joinToString(";") { g -> "${g.a},${g.b},${flag(g.aYacht)}${flag(g.bYacht)}${flag(g.aBonus)}${flag(g.bBonus)}" }
            append("m ${m.a} ${m.b} $gs ${m.deltaA} ${m.deltaB} ${m.time} ${m.notes.joinToString("\u001f").ifEmpty { "-" }.replace(' ', ' ')}\n")
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

        /** Standard errors taken off the rating for the ranking (see [rankScore]). */
        const val RANK_ERRORS = 3.0

        /** An error up to this (about ±20 shown, a few hundred games) costs no ranking place. */
        const val RANK_FREE_ERROR = 10.0
        private const val MAX_ITER = 3000

        /**
         * Bradley-Terry strengths by minorisation-maximisation, from the last strengths. Each AI
         * also gets a tenth of a win and of a loss against a virtual average player, which keeps
         * unbeaten or winless records finite. Pure: may run off the lock the input came from.
         */
        fun solve(input: FitInput, maxSweeps: Int = MAX_ITER): FitResult {
            val m = input.active.size
            val pi = input.strength.copyOf()
            val wins = DoubleArray(m) { a -> PRIOR + input.points[a].sum() }
            // Converged when no rating moves relative to the others: all strengths also drift
            // together under the weak prior, which a plain change test kept chasing for thousands
            // of sweeps (1e-6 here is 0.0002 rating points).
            val delta = DoubleArray(m)
            for (iter in 0 until maxSweeps) {
                for (a in 0 until m) {
                    val p = pi[a]
                    val row = input.played[a]
                    var denom = 2 * PRIOR / (p + 1.0)
                    for (b in 0 until m) {
                        val g = row[b]
                        if (g > 0) denom += g / (p + pi[b])
                    }
                    val next = wins[a] / denom
                    delta[a] = ln(next / p)
                    pi[a] = next
                }
                var mean = 0.0
                for (a in 0 until m) mean += delta[a]
                mean /= maxOf(1, m)
                var change = 0.0
                for (a in 0 until m) change = maxOf(change, abs(delta[a] - mean))
                if (change < 1e-6) break
            }
            val base = if (input.anchor >= 0) log10(pi[input.anchor]) else (0 until m).sumOf { log10(pi[it]) } / maxOf(1, m)
            val ratings = DoubleArray(m) { 1500 + 400 * (log10(pi[it]) - base) }
            val errors = DoubleArray(m) { a ->
                val p = pi[a]
                var info = 2 * PRIOR * p / ((p + 1.0) * (p + 1.0))
                val row = input.played[a]
                for (b in 0 until m) {
                    val g = row[b]
                    if (g > 0) {
                        val s = p + pi[b]
                        info += g * p * pi[b] / (s * s)
                    }
                }
                400 / ln(10.0) / sqrt(info)
            }
            return FitResult(input, pi, ratings, errors)
        }

        /** Restores [text] onto a ladder for [keys]; unknown AIs (an older roster) are skipped. */
        /** One saved line into [l]; every field is read before anything is set (a cut line changes nothing). */
        private fun decodeLine(l: Ladder, f: List<String>) {
            when (f.firstOrNull()) {
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
                    e.peak = peak
                    history.forEach { e.history.addLast(it) }
                }
                "h" -> {
                    val i = l.index[f[1]] ?: return
                    val j = l.index[f[2]] ?: return
                    val pts = f[3].toDouble()
                    val n = f[4].toInt()
                    l.winSum[i] += pts - l.points[i][j]
                    l.winSum[j] += (n - pts) - l.points[j][i]
                    l.points[i][j] = pts
                    l.points[j][i] = n - pts
                    l.played[i][j] = n
                    l.played[j][i] = n
                }
                "m" -> {
                    if (l.index[f[1]] == null || l.index[f[2]] == null) return
                    val games = f[3].split(';').map { g ->
                        val p = g.split(',')
                        val fl = p[2]
                        GameScore(p[0].toInt(), p[1].toInt(), fl[0] == '1', fl[1] == '1', fl[2] == '1', fl[3] == '1')
                    }
                    val notes = f[7].replace('\u00a0', ' ').takeIf { it != "-" }?.split('\u001f') ?: emptyList()
                    l.feed.addFirst(MatchRecord(f[1], f[2], games, f[4].toInt(), f[5].toInt(), f[6].toLong(), notes))
                }
            }
        }

        fun decode(keys: List<String>, text: String, anchor: String = "ev"): Ladder {
            val l = Ladder(keys, anchor)
            // Line by line: a line cut off by a kill mid-save (older versions saved in place)
            // loses only itself, not the whole league.
            for (line in text.lines()) {
                val f = line.split(' ')
                runCatching { decodeLine(l, f) }
            }
            // Entries of AIs no longer in the roster are gone: their games stay out of the fit.
            l.fit()
            return l
        }
    }
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

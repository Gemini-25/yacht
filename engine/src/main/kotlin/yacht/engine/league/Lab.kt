package yacht.engine.league

import yacht.engine.DiceStream
import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Running totals of a lab session's matches, seen from A: everything a [LabResult] shows,
 * without keeping the matches themselves (a session of 20000 matches stays a few hundred bytes).
 */
class LabStats {
    var matches = 0
        private set
    var games = 0
        private set
    var gameWins = 0
        private set
    var gameDraws = 0
        private set
    var matchWins = 0
        private set
    var matchDraws = 0
        private set

    /** Sum of the per-match shares (games won / games, a level game half) and of their squares. */
    var shareSum = 0.0
        private set
    var shareSq = 0.0
        private set

    /** Sum of the per-match mean score differences (A − B per game) and of their squares. */
    var diffSum = 0.0
        private set
    var diffSq = 0.0
        private set
    var scoreA = 0L
        private set
    var scoreB = 0L
        private set

    /** Per-game score differences in bins of [DIFF_BIN] points ([diffBin]). */
    val diffHistogram = IntArray(DIFF_BINS)

    /** Each side's game scores in bins of [SCORE_BIN] points. */
    val scoreHistogramA = IntArray(SCORE_BINS)
    val scoreHistogramB = IntArray(SCORE_BINS)

    /** Adds one match ([games] from A's side). */
    fun add(games: List<GameScore>) {
        if (games.isEmpty()) return
        matches++
        this.games += games.size
        var won = 0.0
        var d = 0
        for (g in games) {
            when {
                g.a > g.b -> {
                    gameWins++
                    won += 1.0
                }
                g.a == g.b -> {
                    gameDraws++
                    won += 0.5
                }
            }
            scoreA += g.a
            scoreB += g.b
            d += g.a - g.b
            diffHistogram[diffBin(g.a - g.b)]++
            scoreHistogramA[(g.a / SCORE_BIN).coerceIn(0, SCORE_BINS - 1)]++
            scoreHistogramB[(g.b / SCORE_BIN).coerceIn(0, SCORE_BINS - 1)]++
        }
        // The lab plays the two-game total, whatever the league's matches (its statistics assume it).
        when (TieRule.match(games, MatchFormat.MIRRORED)) {
            1 -> matchWins++
            0 -> matchDraws++
        }
        val share = won / games.size
        shareSum += share
        shareSq += share * share
        val md = d.toDouble() / games.size
        diffSum += md
        diffSq += md * md
    }

    fun copy(): LabStats = LabStats().also { c -> c.merge(this) }

    /** Adds every match of [o]. */
    fun merge(o: LabStats) {
        matches += o.matches
        games += o.games
        gameWins += o.gameWins
        gameDraws += o.gameDraws
        matchWins += o.matchWins
        matchDraws += o.matchDraws
        shareSum += o.shareSum
        shareSq += o.shareSq
        diffSum += o.diffSum
        diffSq += o.diffSq
        scoreA += o.scoreA
        scoreB += o.scoreB
        for (i in diffHistogram.indices) diffHistogram[i] += o.diffHistogram[i]
        for (i in scoreHistogramA.indices) {
            scoreHistogramA[i] += o.scoreHistogramA[i]
            scoreHistogramB[i] += o.scoreHistogramB[i]
        }
    }

    /** Mean per-match share and the half-width of its 95% interval (0.5 with fewer than two matches). */
    fun share(): Pair<Double, Double> {
        if (matches == 0) return 0.5 to 0.5
        val mean = shareSum / matches
        if (matches < 2) return mean to 0.5
        return mean to Z95 * sqrt(variance(shareSum, shareSq) / matches)
    }

    private fun variance(sum: Double, sq: Double): Double = maxOf(0.0, (sq - sum * sum / matches) / (matches - 1))

    /** Half-width of the 95% interval of the mean score difference per game; NaN with fewer than two matches. */
    fun scoreDiffCi(): Double = if (matches < 2) Double.NaN else Z95 * sqrt(variance(diffSum, diffSq) / matches)

    /** [FIELDS] space-free tokens. */
    fun encode(): String = listOf(
        matches, games, gameWins, gameDraws, matchWins, matchDraws, shareSum, shareSq, diffSum, diffSq, scoreA, scoreB,
        diffHistogram.joinToString(","), scoreHistogramA.joinToString(","), scoreHistogramB.joinToString(","),
    ).joinToString(" ")

    companion object {
        const val FIELDS = 15
        const val DIFF_BIN = 20

        /** Bins of the score difference: [-210, -190) … [-10, 10) … [190, 210), the ends open. */
        const val DIFF_BINS = 21
        const val SCORE_BIN = 25
        const val SCORE_BINS = 16

        /** 95% two-sided normal quantile. */
        const val Z95 = 1.959964

        /** The bin of a game's score difference [d] (A − B). */
        fun diffBin(d: Int): Int = Math.floorDiv(d + DIFF_BIN * (DIFF_BINS / 2) + DIFF_BIN / 2, DIFF_BIN).coerceIn(0, DIFF_BINS - 1)

        /** The middle of bin [i]: -200, -180, …, 200. */
        fun diffBinCenter(i: Int): Int = (i - DIFF_BINS / 2) * DIFF_BIN

        /** Reads [encode]'s tokens from [f] at [at]; throws on a short or bad line. */
        fun decode(f: List<String>, at: Int): LabStats {
            require(f.size >= at + FIELDS) { "short lab stats" }
            fun ints(s: String, n: Int) = s.split(',').map { it.toInt() }.also { require(it.size == n) }
            val s = LabStats()
            val dh = ints(f[at + 12], DIFF_BINS)
            val ha = ints(f[at + 13], SCORE_BINS)
            val hb = ints(f[at + 14], SCORE_BINS)
            s.matches = f[at].toInt()
            s.games = f[at + 1].toInt()
            s.gameWins = f[at + 2].toInt()
            s.gameDraws = f[at + 3].toInt()
            s.matchWins = f[at + 4].toInt()
            s.matchDraws = f[at + 5].toInt()
            s.shareSum = f[at + 6].toDouble()
            s.shareSq = f[at + 7].toDouble()
            s.diffSum = f[at + 8].toDouble()
            s.diffSq = f[at + 9].toDouble()
            s.scoreA = f[at + 10].toLong()
            s.scoreB = f[at + 11].toLong()
            // A line cut inside its last number still has every field: the counts must add up.
            require(s.matches >= 0 && s.games >= s.matches && dh.sum() == s.games && ha.sum() == s.games && hb.sum() == s.games)
            dh.forEachIndexed { i, x -> s.diffHistogram[i] = x }
            ha.forEachIndexed { i, x -> s.scoreHistogramA[i] = x }
            hb.forEachIndexed { i, x -> s.scoreHistogramB[i] = x }
            return s
        }
    }
}

/**
 * What a lab session found, A against B: an immutable snapshot. Every interval is a 95% one
 * from the per-match figures (the two games of a mirrored match share their dice, so the match
 * is the independent unit).
 */
class LabResult internal constructor(
    val id: Int,
    val a: String,
    val b: String,
    val nameA: String,
    val nameB: String,
    /** Matches asked for; 0 = until the share is known to ±[LabSession.PRECISION]. */
    val target: Int,
    /** The session is over: its target reached, precise enough, or [stopped]. */
    val done: Boolean,
    /** Stopped before its target: by the player, or because one of its AIs could not play. */
    val stopped: Boolean,
    stats: LabStats,
) {
    internal val stats: LabStats = stats.copy()

    val matches: Int = stats.matches
    val games: Int = stats.games
    val gameWins: Int = stats.gameWins
    val gameDraws: Int = stats.gameDraws
    val gameLosses: Int = stats.games - stats.gameWins - stats.gameDraws
    val matchWins: Int = stats.matchWins
    val matchDraws: Int = stats.matchDraws
    val matchLosses: Int = stats.matches - stats.matchWins - stats.matchDraws

    /** A's mean share per match (games won / games, a level game half) and its interval's half-width. */
    val share: Double
    val shareCi: Double
    val shareLow: Double get() = (share - shareCi).coerceAtLeast(0.0)
    val shareHigh: Double get() = (share + shareCi).coerceAtMost(1.0)

    /** Rating difference A − B on the Elo scale (`400 log10(p / (1 - p))` of the share) and its interval. */
    val ratingDiff: Double
    val ratingLow: Double
    val ratingHigh: Double

    /** Average game scores of A and B, and their mean difference per game with its interval's half-width. */
    val avgA: Double = if (games == 0) 0.0 else stats.scoreA.toDouble() / games
    val avgB: Double = if (games == 0) 0.0 else stats.scoreB.toDouble() / games
    val scoreDiff: Double = avgA - avgB
    val scoreDiffCi: Double = stats.scoreDiffCi()

    /** P(A is the stronger), the normal approximation of the share around one half. */
    val pStronger: Double

    /** Per-game score differences (bins of [LabStats.DIFF_BIN], see [LabStats.diffBinCenter]) and each side's scores (bins of 25). */
    val diffHistogram: List<Int> = stats.diffHistogram.toList()
    val scoreHistogramA: List<Int> = stats.scoreHistogramA.toList()
    val scoreHistogramB: List<Int> = stats.scoreHistogramB.toList()

    init {
        val (m, ci) = stats.share()
        share = m
        shareCi = ci
        ratingDiff = elo(m)
        ratingLow = elo(shareLow)
        ratingHigh = elo(shareHigh)
        val se = ci / LabStats.Z95
        pStronger = when {
            matches == 0 -> 0.5
            se == 0.0 -> if (m > 0.5) 1.0 else if (m < 0.5) 0.0 else 0.5
            else -> phi((m - 0.5) / se)
        }
    }

    companion object {
        /** Shares are clipped to this distance from 0 and 1 before the logit (±1200 points). */
        private const val P_MIN = 0.001

        fun elo(p: Double): Double {
            val q = p.coerceIn(P_MIN, 1 - P_MIN)
            return 400 * log10(q / (1 - q))
        }

        /** Standard normal distribution function (Abramowitz and Stegun 7.1.26, error under 1e-7). */
        fun phi(z: Double): Double {
            val x = abs(z) / sqrt(2.0)
            val t = 1 / (1 + 0.3275911 * x)
            val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
            val erf = 1 - poly * exp(-x * x)
            return if (z >= 0) 0.5 * (1 + erf) else 0.5 * (1 - erf)
        }
    }
}

/**
 * One AI 연구소 experiment (docs/LEAGUE_SPEC.md section 6): [a] against [b] over mirrored
 * matches, handed to the league's workers as low-priority, unrated [Comp.LAB] jobs. Match n is
 * dealt from `DiceStream.mix(seed, 'X', n)`, so a stopped or lost match is replayed identically.
 *
 * Results are taken in match order: a match finished ahead of an earlier one waits until the
 * earlier is in, so the figures (and when the until-precise mode stops) do not depend on which
 * worker was faster. Not thread-safe: callers synchronise.
 */
class LabSession(
    val id: Int,
    val a: String,
    val b: String,
    /** Matches to play (200, 1000, 5000, at most [CAP]), or [UNTIL_PRECISE]. */
    val target: Int,
    val seed: Long,
    val nameA: String = a,
    val nameB: String = b,
) {
    init {
        require(target in 0..CAP) { "lab target $target" }
    }

    /** The matches 0 until [LabStats.matches], all in. */
    private var prefix = LabStats()

    /** Matches in beyond the prefix (a later one finished first), by number. */
    private val ahead = TreeMap<Int, List<GameScore>>()
    private val taken = HashSet<Int>()

    /** Matches handed out and given back (stopped): handed out again first. */
    private val again = TreeSet<Int>()

    /** Matches handed out so far (numbers 0 until this). */
    private var issued = 0

    var done = false
        private set
    var stopped = false
        private set

    /** The most matches it plays. */
    val limit: Int get() = if (target == UNTIL_PRECISE) CAP else target

    /** Matches in so far. */
    val matches: Int get() = prefix.matches + ahead.size

    /** Matches being played now. */
    val inFlight: Int get() = taken.size

    /** The deal of match [n]. */
    fun seedOf(n: Int): Long = DiceStream.mix(seed, Comp.LAB.code.code.toLong(), n.toLong())

    /** True for this session's own fixtures. */
    fun owns(f: Fixture): Boolean = f.comp == Comp.LAB && f.a == a && f.b == b && f.id in 0 until CAP && f.seed == seedOf(f.id)

    /** The next match to play, or null when it is over or every match left is being played. */
    fun next(): Fixture? {
        if (done) return null
        val n = again.pollFirst() ?: if (issued < limit) issued++ else return null
        taken += n
        return Fixture(Comp.LAB, n, a, b, seedOf(n), JobKind.MATCH, rated = false, label = "${n + 1}번째")
    }

    /** A match handed out by [next] that was not played (stopped): played again later. */
    fun release(f: Fixture) {
        if (owns(f) && taken.remove(f.id) && !done) again += f.id
    }

    /**
     * Takes a finished match; false when it is not one of this session's open matches. A result
     * that is not a mirrored match of its two AIs (fewer than [GAMES] games: the app forfeits a
     * job whose AI is gone, e.g. retired at the season's end) would come back the same every
     * time, so the session stops there with the matches it has ([stopped]).
     */
    fun record(r: JobResult): Boolean {
        val f = r.fixture
        if (done || !owns(f) || f.id !in taken) return false
        taken.remove(f.id)
        val games = r.boards.singleOrNull()?.takeIf { it.a == a && it.b == b }?.games
        if (games == null || games.size < GAMES) stop() else put(f.id, games)
        return true
    }

    private fun put(n: Int, games: List<GameScore>) {
        if (n < prefix.matches || ahead.containsKey(n)) return
        ahead[n] = games
        advance()
    }

    /** Moves the matches that are next in order into the prefix, stopping once it is enough. */
    private fun advance() {
        while (!done) {
            if (reached()) {
                finish()
                return
            }
            val g = ahead.remove(prefix.matches) ?: return
            prefix.add(g)
        }
    }

    private fun reached(): Boolean {
        if (prefix.matches >= limit) return true
        return target == UNTIL_PRECISE && prefix.matches >= MIN_MATCHES && prefix.share().second <= PRECISION
    }

    private fun finish() {
        done = true
        // Matches played past the stopping point are left out, so the result is the same
        // whichever matches happened to be in flight.
        ahead.clear()
        again.clear()
        taken.clear()
    }

    /** Ends the session now with every match in so far. */
    fun stop() {
        if (done) return
        for (g in ahead.values) prefix.add(g)
        stopped = true
        finish()
    }

    /** The figures so far (every match in, in order or not); final once [done]. */
    val results: LabResult
        get() {
            val s = if (ahead.isEmpty()) prefix else prefix.copy().also { c -> for (g in ahead.values) c.add(g) }
            return LabResult(id, a, b, nameA, nameB, target, done, stopped, s)
        }

    internal fun encode(out: StringBuilder) {
        out.append("cur $id $a $b ${Lab.esc(nameA)} ${Lab.esc(nameB)} $target $seed $issued ${if (done) 1 else 0} ${if (stopped) 1 else 0}\n")
        out.append("agg ").append(prefix.encode()).append('\n')
        // The trailing "." tells a whole line from one cut inside its last game.
        for ((n, g) in ahead) out.append("x $n ").append(g.joinToString(";") { Season.encodeGame(it) }).append(" .\n")
    }

    /** Puts back a saved state: matches not in are played again. */
    internal fun restore(issued: Int, done: Boolean, stopped: Boolean, prefix: LabStats?, ahead: Map<Int, List<GameScore>>) {
        val kept = prefix?.takeIf { it.matches <= limit }
        this.prefix = kept ?: LabStats()
        this.issued = issued.coerceIn(this.prefix.matches, limit)
        this.stopped = stopped
        // A finished session whose totals were lost is played again (it ends the same); a
        // stopped one cannot be, and keeps its result in the history.
        this.done = done && (kept != null || stopped)
        if (this.done) return
        for ((n, g) in ahead) if (n >= this.prefix.matches && n < this.issued) this.ahead[n] = g
        for (n in this.prefix.matches until this.issued) if (n !in this.ahead) again += n
        // A save cut between a match and the check after it (or a lost prefix line): catch up.
        advance()
    }

    companion object {
        /** Target meaning: until the share's 95% interval is within ±[PRECISION]. */
        const val UNTIL_PRECISE = 0

        /** The targets the lab offers. */
        val TARGETS = listOf(200, 1000, 5000, UNTIL_PRECISE)

        /** Most matches of a session (and of the until-precise mode). */
        const val CAP = 20_000

        /** Half-width of the share's 95% interval the until-precise mode plays down to (1%p). */
        const val PRECISION = 0.01

        /** Matches before the until-precise mode may stop (a few equal results have no spread). */
        const val MIN_MATCHES = 200

        /** Games of a mirrored match (a decider may add one): a result with fewer was not played. */
        const val GAMES = 2
    }
}

/**
 * The AI 연구소's book: the current session and the newest [KEEP] finished results, newest first
 * (league/lab.txt). Not thread-safe: callers synchronise.
 */
class LabBook {
    var current: LabSession? = null
        private set
    private val finished = ArrayDeque<LabResult>()
    private var nextId = 1

    /** Finished results (stopped ones too), newest first. */
    val history: List<LabResult>
        get() {
            settle()
            return finished.toList()
        }

    /**
     * Starts a session of [a] against [b] (an unfinished current one is stopped and kept with
     * what it has). [seed] deals its matches.
     */
    fun start(a: String, b: String, target: Int, seed: Long, nameA: String = a, nameB: String = b): LabSession {
        stop()
        settle()
        val s = LabSession(nextId++, a, b, target, seed, nameA, nameB)
        current = s
        return s
    }

    /** Stops the current session; its result when it had any match. */
    fun stop(): LabResult? {
        val s = current ?: return null
        if (s.done) return null
        s.stop()
        return if (s.matches > 0) archive(s) else null
    }

    /** A current session that finished (fed directly, or restored) goes to the history once. */
    private fun settle() {
        val s = current ?: return
        if (s.done && s.matches > 0 && finished.none { it.id == s.id }) archive(s)
    }

    /** The current session's next match (see [LabSession.next]). */
    fun next(): Fixture? = current?.next()

    fun release(f: Fixture) {
        current?.release(f)
    }

    /** Takes a finished lab match; false when it is not the current session's. */
    fun record(r: JobResult): Boolean {
        val s = current ?: return false
        if (!s.record(r)) return false
        if (s.done && s.matches > 0) archive(s)
        return true
    }

    private fun archive(s: LabSession): LabResult {
        val r = s.results
        finished.removeAll { it.id == r.id }
        finished.addFirst(r)
        while (finished.size > KEEP) finished.removeLast()
        return r
    }

    fun encode(): String = buildString {
        settle()
        append("$HEADER\n")
        append("id $nextId\n")
        current?.encode(this)
        for (r in finished) {
            append("res ${r.id} ${r.a} ${r.b} ${Lab.esc(r.nameA)} ${Lab.esc(r.nameB)} ${r.target} ${if (r.stopped) 1 else 0} ")
            append(r.stats.encode()).append('\n')
        }
    }

    companion object {
        const val HEADER = "lab 1"
        const val FILE = "lab.txt"

        /** Finished results kept. */
        const val KEEP = 10

        /**
         * Reads lab.txt line by line: a cut or unknown line is skipped (a lost match is played
         * again). Throws only when the file is not a lab file at all.
         */
        fun decode(text: String): LabBook {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == HEADER) { "not a lab file" }
            val book = LabBook()
            var cur: LabSession? = null
            var state: Triple<Int, Boolean, Boolean>? = null
            var prefix: LabStats? = null
            val ahead = HashMap<Int, List<GameScore>>()
            for (line in lines.drop(1)) {
                runCatching {
                    val f = line.trim().split(' ')
                    when (f[0]) {
                        "id" -> book.nextId = maxOf(book.nextId, f[1].toInt())
                        "cur" -> {
                            require(f.size >= 11)
                            val s = LabSession(f[1].toInt(), f[2], f[3], f[6].toInt(), f[7].toLong(), Lab.unesc(f[4]), Lab.unesc(f[5]))
                            state = Triple(f[8].toInt(), f[9] == "1", f[10] == "1")
                            cur = s
                        }
                        "agg" -> prefix = LabStats.decode(f, 1)
                        "x" -> {
                            require(f.size >= 4 && f[3] == ".")
                            val n = f[1].toInt()
                            ahead[n] = f[2].split(';').map { Season.decodeGame(it) }
                        }
                        "res" -> {
                            require(f.size >= 8)
                            val r = LabResult(
                                f[1].toInt(), f[2], f[3], Lab.unesc(f[4]), Lab.unesc(f[5]), f[6].toInt(), true, f[7] == "1", LabStats.decode(f, 8),
                            )
                            if (book.finished.size < KEEP && book.finished.none { it.id == r.id }) book.finished.addLast(r)
                        }
                    }
                }
            }
            cur?.let { s ->
                val (issued, done, stopped) = state!!
                s.restore(issued, done, stopped, prefix, ahead)
                book.current = s
                book.nextId = maxOf(book.nextId, s.id + 1)
            }
            for (r in book.finished) book.nextId = maxOf(book.nextId, r.id + 1)
            return book
        }
    }
}

/** Save-file helpers of the lab. */
internal object Lab {
    /** A name as one space-free token (percent escapes, "-" for empty), as roster.txt writes names. */
    fun esc(s: String): String =
        if (s.isEmpty()) "-" else if (s == "-") "%2D" else s.replace("%", "%25").replace(" ", "%20").replace("\n", "%0A").replace("\r", "%0D")

    fun unesc(s: String): String =
        if (s == "-") "" else s.replace("%20", " ").replace("%0A", "\n").replace("%0D", "\r").replace("%2D", "-").replace("%25", "%")
}

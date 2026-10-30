package yacht.engine.league

import yacht.engine.Board
import yacht.engine.Category
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * One AI's numbers over a season or over its career (docs/LEAGUE_SPEC.md section 3). Fixed
 * size whatever the number of games, so memory and stats.txt stay bounded however long the
 * league runs. Box sums and zeros count only the games whose final cards are known ([carded]).
 */
class AiStats {
    var games = 0
    var gameWins = 0
    var gameDraws = 0
    var matchWins = 0
    var matchDraws = 0
    var matchLosses = 0
    var scoreSum = 0L
    var scoreSquares = 0L
    var best = 0
    var worst = 0

    /** Games with both final cards known: [boxSums] and [boxZeros] are over these. */
    var carded = 0

    /** Points written in each box, [Category.ALL] order. */
    val boxSums = IntArray(Category.COUNT)

    /** Times each box was written as 0. */
    val boxZeros = IntArray(Category.COUNT)
    var yachts = 0
    var bonuses = 0
    var over300 = 0

    /** Games won after its own P(win) fell below [COMEBACK_P]. */
    var comebacks = 0

    /** Games lost after its own P(win) rose above [BLOWN_P]. */
    var blownLeads = 0

    /** Games won or lost by at most [CLOSE] points. */
    var closeWins = 0
    var closeLosses = 0

    /** Matches won against an AI rated at least [GIANT] higher before the match. */
    var giantKills = 0

    /** Single decider games of best-of-three matches and knockout ties. */
    var deciderWins = 0
    var deciderLosses = 0

    /**
     * Sum and count of the per-game match ratings (평점). Every rating is a multiple of 0.005
     * ([GameFacts.rating] of whole percents), so the sum is kept exactly in those steps: the
     * same after a reload as before it (the MVP race and the rating record depend on it).
     */
    var ratingSum: Double
        get() = ratingUnits / RATING_STEPS
        set(v) {
            ratingUnits = Math.round(v * RATING_STEPS)
        }
    private var ratingUnits = 0L

    /** A sum read from a file of 0.01 steps (older versions) into 0.005 steps. */
    internal fun doubleRatingUnits() {
        ratingUnits *= 2
    }
    var ratingCount = 0

    /** 운 보정 승점: 3 when its total over a match's mirrored games beat the opponent's, 1 when level. */
    var aggPoints = 0

    /** Games by score, [BIN]-point bins (the last bin takes everything above). */
    val histogram = IntArray(BINS)

    /** Moving average of its ms per game (NaN before the first timed job). */
    var cost = Double.NaN

    /** Games won in a row (negative: lost in a row), and the longest winning run. */
    var streak = 0
    var bestStreak = 0

    /** Matches won and lost against AIs of its own hometown (derbies). */
    var derbyWins = 0
    var derbyLosses = 0

    /** League seasons finished (career only). */
    var seasons = 0

    val gameLosses: Int get() = games - gameWins - gameDraws
    val matches: Int get() = matchWins + matchDraws + matchLosses

    /** Share of games won, a level game counting half. */
    val winRate: Double get() = if (games == 0) 0.0 else (gameWins + 0.5 * gameDraws) / games
    val avgScore: Double get() = if (games == 0) 0.0 else scoreSum.toDouble() / games

    /** Standard deviation of its game scores. */
    val scoreSd: Double
        get() {
            if (games < 2) return 0.0
            val m = avgScore
            return sqrt(maxOf(0.0, scoreSquares.toDouble() / games - m * m))
        }

    /** Average match rating (평점), NaN before the first rated game. */
    val avgRating: Double get() = if (ratingCount == 0) Double.NaN else ratingSum / ratingCount

    fun boxAverage(c: Category): Double = if (carded == 0) Double.NaN else boxSums[c.ordinal].toDouble() / carded
    fun zeroRate(c: Category): Double = if (carded == 0) Double.NaN else boxZeros[c.ordinal].toDouble() / carded

    internal fun addGame(g: SideGame) {
        val won = g.own > g.opp
        val lost = g.own < g.opp
        games++
        if (won) gameWins++ else if (!lost) gameDraws++
        scoreSum += g.own
        scoreSquares += g.own.toLong() * g.own
        if (games == 1) {
            best = g.own
            worst = g.own
        } else {
            best = maxOf(best, g.own)
            worst = minOf(worst, g.own)
        }
        histogram[(g.own / BIN).coerceIn(0, BINS - 1)]++
        if (g.yacht) yachts++
        if (g.bonus) bonuses++
        if (g.own >= 300) over300++
        g.card?.let { card ->
            carded++
            for (c in Category.ALL) {
                val p = card[c] ?: 0
                boxSums[c.ordinal] += p
                if (p == 0) boxZeros[c.ordinal]++
            }
        }
        if (!g.minP.isNaN()) {
            if (won && g.minP < COMEBACK_P) comebacks++
            if (lost && g.maxP > BLOWN_P) blownLeads++
        }
        if (won && g.own - g.opp <= CLOSE) closeWins++
        if (lost && g.opp - g.own <= CLOSE) closeLosses++
        if (g.decider) {
            if (won) deciderWins++ else if (lost) deciderLosses++
        }
        if (!g.rating.isNaN()) {
            ratingUnits += Math.round(g.rating * RATING_STEPS)
            ratingCount++
        }
        streak = when {
            won -> maxOf(streak, 0) + 1
            lost -> minOf(streak, 0) - 1
            else -> 0
        }
        bestStreak = maxOf(bestStreak, streak)
    }

    /** One match (a board) from this AI's side: [result] +1 won, 0 level, -1 lost; [agg] its total minus the opponent's. */
    internal fun addMatch(result: Int, agg: Int, giantKill: Boolean, derby: Boolean) {
        when {
            result > 0 -> matchWins++
            result < 0 -> matchLosses++
            else -> matchDraws++
        }
        aggPoints += if (agg > 0) 3 else if (agg == 0) 1 else 0
        if (result > 0 && giantKill) giantKills++
        if (derby) {
            if (result > 0) derbyWins++ else if (result < 0) derbyLosses++
        }
    }

    internal fun addCost(ms: Double) {
        if (ms.isNaN() || ms < 0) return
        cost = if (cost.isNaN()) ms else cost * (1 - COST_ALPHA) + ms * COST_ALPHA
    }

    /** Adds [o]'s numbers to these (streaks and cost are kept from the newer side, [o]). */
    fun add(o: AiStats) {
        if (o.games > 0) {
            best = if (games == 0) o.best else maxOf(best, o.best)
            worst = if (games == 0) o.worst else minOf(worst, o.worst)
        }
        games += o.games
        gameWins += o.gameWins
        gameDraws += o.gameDraws
        matchWins += o.matchWins
        matchDraws += o.matchDraws
        matchLosses += o.matchLosses
        scoreSum += o.scoreSum
        scoreSquares += o.scoreSquares
        carded += o.carded
        for (i in 0 until Category.COUNT) {
            boxSums[i] += o.boxSums[i]
            boxZeros[i] += o.boxZeros[i]
        }
        yachts += o.yachts
        bonuses += o.bonuses
        over300 += o.over300
        comebacks += o.comebacks
        blownLeads += o.blownLeads
        closeWins += o.closeWins
        closeLosses += o.closeLosses
        giantKills += o.giantKills
        deciderWins += o.deciderWins
        deciderLosses += o.deciderLosses
        ratingUnits += o.ratingUnits
        ratingCount += o.ratingCount
        aggPoints += o.aggPoints
        for (i in 0 until BINS) histogram[i] += o.histogram[i]
        if (!o.cost.isNaN()) cost = o.cost
        if (o.games > 0) streak = o.streak
        bestStreak = maxOf(bestStreak, o.bestStreak)
        derbyWins += o.derbyWins
        derbyLosses += o.derbyLosses
        seasons += o.seasons
    }

    fun copy(): AiStats = AiStats().also { it.add(this) }

    /**
     * Space-separated fields ([FIELDS] of them), numbers in base 36, the score bins (from the
     * first one in use), box sums and zeros as fixed-width runs ([Field.run]). 400 AIs' season
     * and career lines take 160 KB this way instead of 230 in plain decimal lists. The box runs
     * come last: their exact length makes a line cut anywhere unreadable rather than wrong.
     */
    fun encode(): String {
        val out = StringBuilder(256)
        fun n(x: Long) = out.append(java.lang.Long.toString(x, 36)).append(' ')
        for (x in intArrayOf(games, gameWins, gameDraws, matchWins, matchDraws, matchLosses)) n(x.toLong())
        n(scoreSum)
        n(scoreSquares)
        for (x in intArrayOf(best, worst, carded, yachts, bonuses, over300, comebacks, blownLeads, closeWins, closeLosses, giantKills, deciderWins, deciderLosses)) {
            n(x.toLong())
        }
        n(ratingUnits)
        n(ratingCount.toLong())
        n(aggPoints.toLong())
        // The lowest and highest bins are nearly always empty.
        val lo = histogram.indexOfFirst { it != 0 }
        if (lo < 0) out.append("- ") else out.append(lo.toString(36)).append(Field.run(histogram.copyOfRange(lo, histogram.indexOfLast { it != 0 } + 1))).append(' ')
        if (cost.isNaN()) out.append("- ") else n((cost * 10).roundToLong())
        for (x in intArrayOf(streak, bestStreak, derbyWins, derbyLosses, seasons)) n(x.toLong())
        out.append(Field.run(boxSums)).append(' ')
        out.append(Field.run(boxZeros))
        return out.toString()
    }

    override fun equals(other: Any?): Boolean = other is AiStats && other.encode() == encode()
    override fun hashCode(): Int = encode().hashCode()
    override fun toString(): String = "AiStats(${encode()})"

    companion object {
        const val BINS = 16
        const val BIN = 25
        const val COMEBACK_P = 0.2
        const val BLOWN_P = 0.8
        const val CLOSE = 5
        const val GIANT = 100.0
        private const val COST_ALPHA = 0.2

        /** Match ratings come in steps of 1/200 (0.005). */
        private const val RATING_STEPS = 200.0
        internal const val RATING_UNITS = 200

        /** Space-separated fields of [encode]. */
        const val FIELDS = 33

        /** Reads the [FIELDS] fields of [encode] from [f] at [from]; throws on a short or bad line. */
        fun decode(f: List<String>, from: Int): AiStats {
            require(f.size >= from + FIELDS) { "short stats line" }
            fun int(t: String) = t.toInt(36)
            fun arr(t: String, n: Int): IntArray = Field.unrun(t).also { require(it.size == n) }
            var i = from
            val s = AiStats()
            val counts = IntArray(6) { int(f[i++]) }
            val scoreSum = f[i++].toLong(36)
            val squares = f[i++].toLong(36)
            val best = int(f[i++])
            val worst = int(f[i++])
            val carded = int(f[i++])
            val more = IntArray(10) { int(f[i++]) }
            val ratingUnits = f[i++].toLong(36)
            val ratingCount = int(f[i++])
            val agg = int(f[i++])
            val hist = IntArray(BINS)
            f[i++].takeIf { it != "-" }?.let { t ->
                val lo = t[0].digitToInt(36)
                val used = Field.unrun(t.substring(1))
                require(lo + used.size <= BINS)
                used.copyInto(hist, lo)
            }
            val cost = f[i++].let { if (it == "-") Double.NaN else it.toLong(36) / 10.0 }
            val tail = IntArray(5) { int(f[i++]) }
            val sums = arr(f[i++], Category.COUNT)
            val zeros = arr(f[i++], Category.COUNT)
            // Everything read: a bad field above leaves nothing half set.
            s.games = counts[0]
            s.gameWins = counts[1]
            s.gameDraws = counts[2]
            s.matchWins = counts[3]
            s.matchDraws = counts[4]
            s.matchLosses = counts[5]
            s.scoreSum = scoreSum
            s.scoreSquares = squares
            s.best = best
            s.worst = worst
            s.carded = carded
            sums.copyInto(s.boxSums)
            zeros.copyInto(s.boxZeros)
            s.yachts = more[0]
            s.bonuses = more[1]
            s.over300 = more[2]
            s.comebacks = more[3]
            s.blownLeads = more[4]
            s.closeWins = more[5]
            s.closeLosses = more[6]
            s.giantKills = more[7]
            s.deciderWins = more[8]
            s.deciderLosses = more[9]
            s.ratingUnits = ratingUnits
            s.ratingCount = ratingCount
            s.aggPoints = agg
            hist.copyInto(s.histogram)
            s.cost = cost
            s.streak = tail[0]
            s.bestStreak = tail[1]
            s.derbyWins = tail[2]
            s.derbyLosses = tail[3]
            s.seasons = tail[4]
            return s
        }
    }
}

/** One game seen from one side, as [AiStats] takes it (NaN where the facts were not collected). */
internal class SideGame(
    val own: Int,
    val opp: Int,
    val yacht: Boolean,
    val bonus: Boolean,
    val card: Board?,
    /** Its lowest and highest P(win) during the game. */
    val minP: Double,
    val maxP: Double,
    val rating: Double,
    val decider: Boolean,
)

/**
 * What [StatsBook.record] needs to know besides the result. [rating] must give the ratings from
 * before this match (record the stats before the ladder). [counts] says whether a fixture counts
 * for the season's numbers; the career counts every rated game.
 */
class StatsContext(
    val season: Int,
    val rating: (String) -> Double,
    val hometown: (String) -> String = { "" },
    val counts: (Fixture) -> Boolean = { StatsBook.countsForSeason(it.comp) },
)

/** The share of one trait value among the top fifth by rating and among the rest (메타 리포트). */
data class TraitShare(
    val trait: String,
    val value: String,
    val top: Double,
    val rest: Double,
    /** Change of [top] since the season before (NaN when it was not seen then). */
    val change: Double,
)

/**
 * League meta of a season (리그 메타): average points and zero rate per box over every counted
 * game, and which traits the generated and evolved AIs at the top have.
 */
data class MetaReport(
    val season: Int,
    /** Cards counted (two per game when the facts were collected). */
    val cards: Int,
    val avgScore: Double,
    /** Per box, [Category.ALL] order (NaN before any card). */
    val boxAverages: List<Double>,
    val zeroRates: List<Double>,
    val traits: List<TraitShare> = emptyList(),
) {
    fun boxAverage(c: Category): Double = boxAverages[c.ordinal]
    fun zeroRate(c: Category): Double = zeroRates[c.ordinal]

    /** Change of box [c]'s average points since [before] (NaN without it). */
    fun boxChange(before: MetaReport?, c: Category): Double = if (before == null) Double.NaN else boxAverage(c) - before.boxAverage(c)

    internal fun encode(out: StringBuilder) {
        out.append("meta $season $cards ${Field.num(avgScore, 2)} ")
        boxAverages.joinTo(out, ",") { Field.num(it, 2) }.append(' ')
        zeroRates.joinTo(out, ",") { Field.num(it, 4) }.append('\n')
        for (t in traits) {
            out.append("mt $season ${Field.esc(t.trait)} ${Field.esc(t.value)} ${Field.num(t.top, 4)} ${Field.num(t.rest, 4)} ${Field.num(t.change, 4)}\n")
        }
    }

    companion object {
        internal fun decode(f: List<String>): MetaReport {
            val boxes = f[4].split(',').map { Field.dbl(it) }
            val zeros = f[5].split(',').map { Field.dbl(it) }
            require(boxes.size == Category.COUNT && zeros.size == Category.COUNT)
            return MetaReport(f[1].toInt(), f[2].toInt(), Field.dbl(f[3]), boxes, zeros)
        }
    }
}

/**
 * Every AI's season and career [AiStats], the league records, milestones and the league meta
 * (league/stats.txt, docs/LEAGUE_SPEC.md section 3). [record] takes every finished job, O(1)
 * per game; [startSeason] and [endSeason] frame a season. Not thread-safe: callers synchronise.
 */
class StatsBook(
    /** Official games before broken records make news (early on everything is a record). */
    private val recordWarmup: Int = RECORD_WARMUP,
) {
    /** The season being recorded. */
    var season = 0
        private set

    /** The last season [endSeason] closed (it is not closed twice). */
    var ended = 0
        private set

    /** Official games recorded in the whole league (each game once). */
    var officialGames = 0L
        private set

    private val seasonStats = HashMap<String, AiStats>()
    private val careerStats = HashMap<String, AiStats>()
    private val startRatings = HashMap<String, Double>()
    private val rookieKeys = HashSet<String>()

    /**
     * Titles won here per AI over its whole career (division champions, cup, champions league,
     * super cup). The honours book keeps only the newest [HonourBook.MAX_SEASONS] seasons, so
     * counting there would forget old titles in a long league and repeat title milestones.
     */
    private val titleCounts = HashMap<String, Int>()

    /** MVP awards per AI over its career, for the same reason. */
    private val mvpCounts = HashMap<String, Int>()

    val records = Records()

    /** Records broken this season (the latest break per kind), for the season summary. */
    private val breaks = LinkedHashMap<RecordKind, RecordBreak>()
    private val milestoneList = ArrayDeque<LeagueEvent>()

    // League meta of the season so far: cards, their box points and zeros; side-games and points.
    private var metaCards = 0
    private val metaSums = LongArray(Category.COUNT)
    private val metaZeros = IntArray(Category.COUNT)
    private var metaGames = 0
    private var metaScore = 0L

    /** The meta of the last finished season and of the one before. */
    var meta: MetaReport? = null
        private set
    var previousMeta: MetaReport? = null
        private set

    fun season(key: String): AiStats? = seasonStats[key]
    fun career(key: String): AiStats? = careerStats[key]

    /** Titles [key] has won here over its career (also those older than the honours book keeps). */
    fun titles(key: String): Int = titleCounts[key] ?: 0

    /** Times [key] was MVP over its career. */
    fun mvps(key: String): Int = mvpCounts[key] ?: 0

    /** AIs with a career here (a copy). */
    val keys: Set<String> get() = careerStats.keys.toSet()

    /** The rating [key] started this season with (NaN when unknown). */
    fun startRating(key: String): Double = startRatings[key] ?: Double.NaN

    fun isRookie(key: String): Boolean = key in rookieKeys

    /** Records broken this season so far. */
    val brokenThisSeason: List<RecordBreak> get() = breaks.values.toList()

    /** The newest milestones (마일스톤), newest first. */
    val milestones: List<LeagueEvent> get() = milestoneList.toList()

    /** The best [n] AIs by [by] over their careers (or this season), with their stats. */
    fun top(n: Int, career: Boolean = true, min: (AiStats) -> Boolean = { true }, by: (AiStats) -> Double): List<Pair<String, AiStats>> =
        (if (career) careerStats else seasonStats).entries.filter { min(it.value) && !by(it.value).isNaN() }
            .map { Triple(it.key, it.value, by(it.value)) }
            // Level: by key, not by the map's order.
            .sortedWith(compareByDescending<Triple<String, AiStats, Double>> { it.third }.thenBy { it.first })
            .take(n).map { it.first to it.second }

    /** The meta of the season so far (boxes only; traits come at the season's end). */
    fun liveMeta(): MetaReport = boxMeta(season)

    /**
     * Starts recording season [number]: remembers everyone's rating at the start (for 기량발전상)
     * and the rookies (default: AIs that never finished a season here). Called again for the
     * same season (a restart), it keeps what it has and adds AIs it did not know. A new number
     * starts from nothing: at a rollover call it after [endSeason] has closed the old season.
     */
    fun startSeason(number: Int, ratings: Map<String, Double>, rookies: Collection<String>? = null) {
        if (number != season) {
            season = number
            startRatings.clear()
            rookieKeys.clear()
            seasonStats.clear()
            breaks.clear()
            resetMeta()
        }
        // Whole points, as stats.txt keeps them (the same after a restart).
        for ((k, r) in ratings) startRatings.putIfAbsent(k, if (r.isNaN()) r else Math.rint(r))
        if (rookies != null) rookieKeys += rookies
        else for (k in ratings.keys) if ((careerStats[k]?.seasons ?: 0) == 0) rookieKeys += k
    }

    internal fun putCareer(key: String, s: AiStats) {
        careerStats[key] = s
    }

    internal fun putSeason(key: String, s: AiStats) {
        seasonStats[key] = s
    }

    internal fun putCounts(key: String, titles: Int, mvps: Int) {
        titleCounts[key] = titles
        mvpCounts[key] = mvps
    }

    internal fun putMeta(m: MetaReport) {
        previousMeta = meta
        meta = m
    }

    /** Forgets AIs no longer in the league (retired or deleted), so the file stays bounded. */
    fun retain(alive: Collection<String>) {
        val keep = alive.toHashSet()
        careerStats.keys.retainAll(keep)
        seasonStats.keys.retainAll(keep)
        startRatings.keys.retainAll(keep)
        rookieKeys.retainAll(keep)
        titleCounts.keys.retainAll(keep)
        mvpCounts.keys.retainAll(keep)
    }

    /**
     * Records a finished job: per game and side into the season's stats (when [StatsContext.counts]
     * says so) and the career (rated jobs); lab and unrated jobs are left out. Returns the news:
     * "record" events for broken records and "milestone" events.
     */
    fun record(r: JobResult, ctx: StatsContext): List<LeagueEvent> {
        val f = r.fixture
        if (!f.rated || f.comp == Comp.LAB) return emptyList()
        val official = ctx.counts(f)
        val events = ArrayList<LeagueEvent>(0)
        val jobGames = r.boards.sumOf { it.games.size }
        // Half of each game's time per side, as the app's cost meter does.
        val ms = if (jobGames == 0 || r.nanos <= 0) Double.NaN else r.nanos / 1e6 / jobGames / 2
        for (b in r.boards) {
            if (b.games.isEmpty()) continue
            val result = if (f.kind != JobKind.TEAM && r.boards.size == 1) r.winner else TieRule.match(b.games, r.format)
            val ra = ctx.rating(b.a)
            val rb = ctx.rating(b.b)
            val town = ctx.hometown(b.a)
            val derby = town.isNotEmpty() && town == ctx.hometown(b.b)
            val nd = b.games.size - b.deciders
            var agg = 0
            for (i in 0 until nd) agg += b.games[i].a - b.games[i].b
            val (sideA, sideB) = sides(b, pairs = f.kind == JobKind.TIE && f.firstTo > 0)
            val sa = if (official) seasonStats.getOrPut(b.a) { AiStats() } else null
            val sb = if (official) seasonStats.getOrPut(b.b) { AiStats() } else null
            val ca = careerStats.getOrPut(b.a) { AiStats() }
            val cb = careerStats.getOrPut(b.b) { AiStats() }
            for (i in b.games.indices) {
                val ga = sideA[i]
                val gb = sideB[i]
                sa?.addGame(ga)
                sb?.addGame(gb)
                ca.addGame(ga)
                cb.addGame(gb)
                milestones(b.a, ca, ga, ctx.season, events)
                milestones(b.b, cb, gb, ctx.season, events)
                if (official) {
                    officialGames++
                    meta(ga)
                    meta(gb)
                    gameRecords(b.a, b.b, ga, ctx.season, f.comp, events, pair = true)
                    gameRecords(b.b, b.a, gb, ctx.season, f.comp, events, pair = false)
                }
                // The run and the career counts are over every rated game (as the ladder and the
                // milestones), but held only by players of official games: not the 기준 경기's
                // anchor, which plays friendlies all season.
                if (official) {
                    streakRecord(b.a, ca, ga, ctx.season, f.comp, events)
                    streakRecord(b.b, cb, gb, ctx.season, f.comp, events)
                    careerRecords(b.a, ca, ctx.season, events)
                    careerRecords(b.b, cb, ctx.season, events)
                }
            }
            if (official) {
                // Ratings before the match: the best ever (once the rating has settled) and the
                // biggest gap a winner overcame.
                peakRecord(b.a, ra, ca, ctx.season, f.comp, events)
                peakRecord(b.b, rb, cb, ctx.season, f.comp, events)
                if (!ra.isNaN() && !rb.isNaN() && result != 0) {
                    val (w, l, gap) = if (result > 0) Triple(b.a, b.b, rb - ra) else Triple(b.b, b.a, ra - rb)
                    val g = gap.roundToInt().toDouble()
                    if (g > 0 && records.beats(RecordKind.UPSET, g)) offer(LeagueRecord(RecordKind.UPSET, w, g, ctx.season, l, f.comp), events)
                }
            }
            val giantA = !ra.isNaN() && !rb.isNaN() && rb - ra >= AiStats.GIANT
            val giantB = !ra.isNaN() && !rb.isNaN() && ra - rb >= AiStats.GIANT
            for ((stats, mine) in listOf(sa to true, sb to false, ca to true, cb to false)) {
                stats ?: continue
                stats.addMatch(if (mine) result else -result, if (mine) agg else -agg, if (mine) giantA else giantB, derby)
                stats.addCost(ms)
            }
            if (official) {
                // One match (경기) at a time: each leg of a knockout tie, without its single
                // deciders; a match (best of three included) or a board whole.
                val played = if (f.kind == JobKind.TIE) nd else b.games.size
                // A series: each pair of games on the same dice is a match (the last may be one game).
                val series = f.kind == JobKind.TIE && f.firstTo > 0
                val legs = if (series) (played + 1) / 2 else if (f.kind == JobKind.TIE && f.legs > 1 && played % f.legs == 0) f.legs else 1
                val per = if (series) 2 else played / legs
                if (per > 0) {
                    for (leg in 0 until legs) {
                        var ya = 0
                        var yb = 0
                        for (i in leg * per until minOf(played, (leg + 1) * per)) {
                            if (b.games[i].aYacht) ya++
                            if (b.games[i].bYacht) yb++
                        }
                        yachtRecord(b.a, b.b, ya, ctx.season, f.comp, events)
                        yachtRecord(b.b, b.a, yb, ctx.season, f.comp, events)
                    }
                }
            }
        }
        return events
    }

    private fun yachtRecord(key: String, opp: String, yachts: Int, season: Int, comp: Comp, out: MutableList<LeagueEvent>) {
        val y = yachts.toDouble()
        if (records.beats(RecordKind.YACHTS_MATCH, y)) offer(LeagueRecord(RecordKind.YACHTS_MATCH, key, y, season, opp, comp), out)
    }

    private fun meta(g: SideGame) {
        metaGames++
        metaScore += g.own
        val card = g.card ?: return
        metaCards++
        for (c in Category.ALL) {
            val p = card[c] ?: 0
            metaSums[c.ordinal] += p.toLong()
            if (p == 0) metaZeros[c.ordinal]++
        }
    }

    private fun resetMeta() {
        metaCards = 0
        metaSums.fill(0)
        metaZeros.fill(0)
        metaGames = 0
        metaScore = 0
    }

    private fun boxMeta(n: Int, traits: List<TraitShare> = emptyList()) = MetaReport(
        n, metaCards, if (metaGames == 0) Double.NaN else metaScore.toDouble() / metaGames,
        List(Category.COUNT) { if (metaCards == 0) Double.NaN else metaSums[it].toDouble() / metaCards },
        List(Category.COUNT) { if (metaCards == 0) Double.NaN else metaZeros[it].toDouble() / metaCards },
        traits,
    )

    private fun milestones(key: String, c: AiStats, g: SideGame, season: Int, out: MutableList<LeagueEvent>) {
        if (isMilestone(c.games, 1000)) milestone(key, "games", c.games, season, out)
        if (g.own > g.opp && isMilestone(c.gameWins, 100)) milestone(key, "wins", c.gameWins, season, out)
        if (g.yacht && isMilestone(c.yachts, 50)) milestone(key, "yachts", c.yachts, season, out)
        if (g.own >= 300 && (c.over300 == 1 || isMilestone(c.over300, 10))) milestone(key, "300", c.over300, season, out)
    }

    internal fun milestone(key: String, kind: String, value: Int, season: Int, out: MutableList<LeagueEvent>) {
        val e = LeagueEvent("milestone", season, listOf(key), listOf(kind, value.toString()))
        out += e
        milestoneList.addFirst(e)
        while (milestoneList.size > MAX_MILESTONES) milestoneList.removeLast()
    }

    /** One side's game records; [pair]: this side also offers the game's two-player total (once a game). */
    private fun gameRecords(key: String, opp: String, g: SideGame, season: Int, comp: Comp, out: MutableList<LeagueEvent>, pair: Boolean) {
        if (records.beats(RecordKind.BEST_GAME, g.own.toDouble())) offer(LeagueRecord(RecordKind.BEST_GAME, key, g.own.toDouble(), season, opp, comp), out)
        val both = (g.own + g.opp).toDouble()
        if (pair && records.beats(RecordKind.BEST_PAIR, both)) offer(LeagueRecord(RecordKind.BEST_PAIR, key, both, season, opp, comp), out)
        if (g.own <= g.opp) return
        if (records.beats(RecordKind.LOW_WIN, g.own.toDouble())) offer(LeagueRecord(RecordKind.LOW_WIN, key, g.own.toDouble(), season, opp, comp), out)
        val margin = (g.own - g.opp).toDouble()
        if (records.beats(RecordKind.MARGIN, margin)) offer(LeagueRecord(RecordKind.MARGIN, key, margin, season, opp, comp), out)
        if (!g.minP.isNaN()) {
            val p = (g.minP * 100).roundToInt().toDouble()
            if (records.beats(RecordKind.COMEBACK, p)) offer(LeagueRecord(RecordKind.COMEBACK, key, p, season, opp, comp), out)
        }
    }

    private fun streakRecord(key: String, c: AiStats, g: SideGame, season: Int, comp: Comp, out: MutableList<LeagueEvent>) {
        if (g.own <= g.opp) return
        val s = c.streak.toDouble()
        if (records.beats(RecordKind.STREAK, s)) offer(LeagueRecord(RecordKind.STREAK, key, s, season, null, comp), out)
    }

    /** Career counts (every rated game, as the milestones): games, wins, Yachts, 300-point games. */
    private fun careerRecords(key: String, c: AiStats, season: Int, out: MutableList<LeagueEvent>) {
        if (records.beats(RecordKind.CAREER_GAMES, c.games.toDouble())) offer(LeagueRecord(RecordKind.CAREER_GAMES, key, c.games.toDouble(), season), out)
        if (records.beats(RecordKind.CAREER_WINS, c.gameWins.toDouble())) offer(LeagueRecord(RecordKind.CAREER_WINS, key, c.gameWins.toDouble(), season), out)
        if (records.beats(RecordKind.CAREER_YACHTS, c.yachts.toDouble())) offer(LeagueRecord(RecordKind.CAREER_YACHTS, key, c.yachts.toDouble(), season), out)
        if (records.beats(RecordKind.CAREER_300, c.over300.toDouble())) offer(LeagueRecord(RecordKind.CAREER_300, key, c.over300.toDouble(), season), out)
    }

    /** The best rating ever, from [rating] before an official match once [c] has [PEAK_GAMES] games (a new rating still moves a lot). */
    private fun peakRecord(key: String, rating: Double, c: AiStats, season: Int, comp: Comp, out: MutableList<LeagueEvent>) {
        if (rating.isNaN() || c.games < PEAK_GAMES) return
        val v = rating.roundToInt().toDouble()
        if (records.beats(RecordKind.PEAK_RATING, v)) offer(LeagueRecord(RecordKind.PEAK_RATING, key, v, season, null, comp), out)
    }

    /** Puts [r] in the records if it beats the holder; news once the league has warmed up. */
    internal fun offer(r: LeagueRecord, out: MutableList<LeagueEvent>, warm: Boolean = officialGames >= recordWarmup) {
        if (r.value <= 0 && !r.kind.lowerIsBetter) return
        val b = records.offer(r) ?: return
        val prev = b.previous
        if (prev == null) {
            // Set first in a league long past its warm-up: a record this version added.
            if (officialGames >= recordWarmup) firstSeason.putIfAbsent(r.kind, r.season)
            return
        }
        // A record new to this league (an older league updated) settles over its first season:
        // the players who already passed it come in one by one, which is no news.
        if (!warm || firstSeason[r.kind] == r.season) return
        // A winning run, a career count or the best rating makes the news once a season: going
        // on past it, or another overtaking, updates the season's break quietly.
        if (r.kind.running && breaks[r.kind] != null) {
            breaks[r.kind] = RecordBreak(r, breaks.getValue(r.kind).previous)
            return
        }
        breaks[r.kind] = b
        out += LeagueEvent("record", r.season, listOf(r.holder, prev.holder), listOf(r.kind.name, r.kind.plain(r.value), r.kind.plain(prev.value)), r.comp)
    }

    /** The season each record new to an older league was first set in (saved): its news waits until the season after. */
    private val firstSeason = java.util.EnumMap<RecordKind, Int>(RecordKind::class.java)

    /**
     * Records from the honours once season [season]'s are in [honours] (이 주의 선수 included):
     * most 이 주의 선수, and the youngest and oldest 1부 champion by [ageAt] (a player's age in a
     * season; null when unknown). Earlier seasons count too (an older league's champions are
     * found the first time), without news; returns the news of this season's.
     */
    fun honourRecords(honours: HonourBook, season: Int, ageAt: (String, Int) -> Int?): List<LeagueEvent> {
        val events = ArrayList<LeagueEvent>()
        val warm = season > 1
        // Earlier champions once (a league that had them before these records): their ages are
        // read from today's identities, which a league whose ages stand still moves every season.
        val backfill = records[RecordKind.YOUNGEST_CHAMPION] == null && records[RecordKind.OLDEST_CHAMPION] == null
        honours.all.filter { it.kind == "POTW" }.groupingBy { it.key }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.let {
                offer(LeagueRecord(RecordKind.POTW, it.key, it.value.toDouble(), season), events, warm)
            }
        for (h in honours.all.filter { it.kind == "L0" && (backfill || it.season == season) }.sortedBy { it.season }) {
            val age = ageAt(h.key, h.season)?.takeIf { it > 0 } ?: continue
            val now = warm && h.season == season
            offer(LeagueRecord(RecordKind.YOUNGEST_CHAMPION, h.key, age.toDouble(), h.season), events, now)
            offer(LeagueRecord(RecordKind.OLDEST_CHAMPION, h.key, age.toDouble(), h.season), events, now)
        }
        return events
    }

    /**
     * Closes season [plan] (docs/LEAGUE_SPEC.md section 5 step 2): awards (MVP, 신인왕, 기량발전상;
     * 득점왕 and 최다 요트상 come from [Offseason]), records of the season, title milestones,
     * the hall of fame, dynasties, season lines, the season summary and review ([archive]), and
     * the league meta; then resets the season's stats. Call it after [Offseason.next] with the
     * season's honours it returned ([seasonHonours]) and add [SeasonEnd.awards] together with
     * them ([HonourBook.add] replaces a whole season). [honours] holds the earlier seasons (this
     * one may be in it already). Closing the same season again returns the saved awards only.
     */
    fun endSeason(
        plan: SeasonPlan,
        seasonHonours: List<Honour>,
        honours: HonourBook,
        archive: Archive,
        rating: (String) -> Double,
        family: (String) -> Family? = { null },
        name: (String) -> String = { it },
        isRookie: ((String) -> Boolean)? = null,
        traits: (String) -> Map<String, String>? = { null },
        /** AIs deleted during the season: no award (the next AI gets it). */
        gone: Set<String> = emptySet(),
    ): SeasonEnd {
        val n = plan.number
        if (n <= ended) {
            // Closed already: the awards as kept (the book's, should the archive have missed the season).
            val saved = archive.summary(n)?.honours?.filter { it.kind in Awards.KINDS }
                ?: honours.of(n).filter { it.kind in Awards.KINDS }
            return SeasonEnd(n, saved, emptyList(), emptyList(), emptyList(), archive.review?.takeIf { it.season == n })
        }
        val league = plan.league
        // Sorted, so that ties come out the same every time.
        val keys = league.standings.keys.sorted()
        val events = ArrayList<LeagueEvent>()

        val awards = ArrayList<Honour>()
        // A season closed before (the app was killed while saving it) keeps the awards it was given.
        val given = honours.of(n).filter { it.kind in Awards.KINDS }
        if (given.isNotEmpty()) awards += given
        else {
            val awardKeys = keys.filter { it !in gone }
            Awards.mvp(plan) { if (it in gone) null else seasonStats[it] }?.let { awards += Honour(n, "MVP", it) }
            Awards.rookie(awardKeys, isRookie ?: { it in rookieKeys }, rating)?.let { awards += Honour(n, "ROOKIE", it) }
            Awards.mip(awardKeys, { startRating(it) }, rating)?.let { awards += Honour(n, "MIP", it) }
        }
        // The season's titles as Offseason gave them (or as already added to the book).
        val titles = seasonHonours.filter { it.season == n }.ifEmpty { honours.of(n).filter { it.kind !in Awards.KINDS } }
        val won = titles + awards
        for (h in won) if (h.kind in Awards.ANNOUNCED) events += LeagueEvent("award", n, listOf(h.key), listOf(h.kind))

        // Every season's honours with this one in place, for titles and MVPs.
        val book = HonourBook().apply { add(honours.all.filter { it.season != n } + won) }
        val titleCount = book.all.filter { Awards.isAiTitle(it.kind) }.groupingBy { it.key }.eachCount()
        val mvpCount = book.all.filter { it.kind == "MVP" }.groupingBy { it.key }.eachCount()

        // Career counts: the book forgets seasons older than its newest 100 (and is all there is
        // when the stats are new to an old league), so each count is the larger of the two.
        val newTitles = ArrayList<Triple<String, Int, Int>>()
        for ((k, w) in won.filter { Awards.isAiTitle(it.kind) }.groupingBy { it.key }.eachCount().toSortedMap()) {
            val before = maxOf(titleCounts[k] ?: 0, (titleCount[k] ?: 0) - w)
            titleCounts[k] = before + w
            careerStats.getOrPut(k) { AiStats() }
            newTitles += Triple(k, before, before + w)
        }
        for (h in awards) if (h.kind == "MVP") {
            mvpCounts[h.key] = maxOf(mvpCounts[h.key] ?: 0, (mvpCount[h.key] ?: 0) - 1) + 1
            careerStats.getOrPut(h.key) { AiStats() }
        }
        fun titlesOf(k: String) = maxOf(titleCount[k] ?: 0, titleCounts[k] ?: 0)

        // Records of the season (its best only, so one break per kind): points, match rating, titles.
        val warm = n > 1
        // Level: the higher division's (tables in order), then the first key.
        league.divisions.indices.flatMap { league.table(it) }.filter { it.played > 0 }.maxByOrNull { it.points }?.let { s ->
            offer(LeagueRecord(RecordKind.SEASON_POINTS, s.key, s.points.toDouble(), n, null, Comp.LEAGUE), events, warm)
        }
        seasonStats.entries.filter { it.value.matches >= Awards.RATING_MATCHES && !it.value.avgRating.isNaN() }
            .maxWithOrNull(compareBy<Map.Entry<String, AiStats>> { it.value.avgRating }.thenByDescending { it.key })?.let { (k, st) ->
                offer(LeagueRecord(RecordKind.SEASON_RATING, k, Math.round(st.avgRating * 100) / 100.0, n), events, warm)
            }
        // A season's best average score, match win rate and Yachts (over enough matches), and the
        // biggest rating gain since the season began.
        // Enough matches: 20, or three quarters of a short season's rounds.
        val enough = minOf(Awards.RATING_MATCHES, maxOf(1, league.matchdays * 3 / 4))
        val full = seasonStats.entries.filter { it.value.matches >= enough && it.key !in gone }
        full.maxWithOrNull(compareBy<Map.Entry<String, AiStats>> { it.value.avgScore }.thenByDescending { it.key })?.let { (k, st) ->
            offer(LeagueRecord(RecordKind.SEASON_SCORE, k, Math.round(st.avgScore * 10) / 10.0, n), events, warm)
        }
        full.maxWithOrNull(compareBy<Map.Entry<String, AiStats>> { it.value.matchWins.toDouble() / it.value.matches }.thenByDescending { it.key })?.let { (k, st) ->
            offer(LeagueRecord(RecordKind.SEASON_WINRATE, k, Math.round(1000.0 * st.matchWins / st.matches) / 10.0, n), events, warm)
        }
        seasonStats.entries.filter { it.key !in gone }.maxWithOrNull(compareBy<Map.Entry<String, AiStats>> { it.value.yachts }.thenByDescending { it.key })?.let { (k, st) ->
            offer(LeagueRecord(RecordKind.SEASON_YACHTS, k, st.yachts.toDouble(), n), events, warm)
        }
        keys.filter { it !in gone }.mapNotNull { k ->
            val gain = rating(k) - startRating(k)
            if (gain.isNaN()) null else k to gain
        }.maxWithOrNull(compareBy<Pair<String, Double>> { it.second }.thenByDescending { it.first })?.let { (k, gain) ->
            offer(LeagueRecord(RecordKind.RATING_GAIN, k, gain.roundToInt().toDouble(), n), events, warm)
        }
        mvpCounts.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.let {
            offer(LeagueRecord(RecordKind.MVPS, it.key, it.value.toDouble(), n), events, warm)
        }
        // Most titles: the book's count (earliest to reach it when level) or a longer career's.
        val careerBest = titleCounts.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
            ?.let { LeagueRecord(RecordKind.TITLES, it.key, it.value.toDouble(), n) }
        listOfNotNull(Records.mostTitles(book), careerBest).maxByOrNull { it.value }?.let { offer(it, events, warm) }

        // Title milestones: the first, then every fifth (two titles in a season can pass one).
        for ((k, before, after) in newTitles) {
            for (t in before + 1..after) if (t == 1 || t % 5 == 0) milestone(k, "title", t, n, events)
        }

        // Hall of fame (AIs only: team competitions' honours are keyed by the team). Sorted, so
        // that the order of the inducted (and of archive.txt) does not depend on the map's.
        val inducted = ArrayList<HallOfFameEntry>()
        val candidates = java.util.TreeSet<String>().apply {
            addAll(keys)
            addAll(careerStats.keys)
        }
        for (k in candidates) {
            if (archive.inHallOfFame(k)) continue
            val c = careerStats[k]
            val titles = titlesOf(k)
            val mvps = maxOf(mvpCount[k] ?: 0, mvpCounts[k] ?: 0)
            val reason = Awards.hallOfFame(titles, mvps, c) ?: continue
            val e = HallOfFameEntry(k, name(k), n, reason, titles, c?.games ?: 0, c?.winRate ?: 0.0)
            // A full hall makes room by an AI that is gone for good (it could never be inducted
            // again), never by one still playing: that one would come back every season.
            if (!archive.induct(e) { it !in candidates }) continue
            inducted += e
            events += LeagueEvent("hall_of_fame", n, listOf(e.key), listOf(e.reason))
        }

        // Dynasties: against each title's previous edition (the family cup is every third season).
        val dynasties = Awards.dynasties(n, won, book.all.filter { it.season < n }, league, archive.dynasties)
        for (d in dynasties) {
            archive.putDynasty(d)
            events += LeagueEvent("dynasty", n, listOf(d.key), listOf(d.kind, d.count.toString(), d.title))
        }

        // Season lines (성장 곡선) and careers.
        for (d in league.divisions.indices) {
            for ((pos, s) in league.table(d).withIndex()) {
                val st = seasonStats[s.key]
                // No rating now (off the ladder): the line's rating as kept, else none.
                val r = rating(s.key).takeIf { !it.isNaN() } ?: archive.lines(s.key).firstOrNull { it.season == n }?.rating ?: Double.NaN
                // By tier: a pyramid's group is not kept (a season line packs its tier in one digit).
                archive.addLine(s.key, SeasonLine(n, league.tiers.tier(d), pos + 1, s.wins, s.draws, s.losses, r, st?.avgRating ?: Double.NaN))
                careerStats.getOrPut(s.key) { AiStats() }.seasons++
            }
        }

        // League meta: boxes over the season, traits among the generated and evolved AIs.
        val report = boxMeta(n, traitShares(keys, rating, traits, meta))
        val before = meta
        previousMeta = before
        meta = report

        val broken = breaks.values.toList()
        val scorer = won.firstOrNull { it.kind == "SCORER" }?.key
        archive.putSummary(
            SeasonSummary(
                n, won.filter { Awards.inSummary(it.kind) }, scorer, scorer?.let { league.standings[it]?.avgScore } ?: Double.NaN,
                broken, metaGames / 2, report.avgScore,
            ),
        )
        val review = SeasonReview.of(
            plan, won, keys, { startRating(it) }, rating, { seasonStats[it]?.avgRating ?: Double.NaN }, broken, report, before, family, name,
        )
        archive.putReview(review)

        // The next season starts from nothing.
        seasonStats.clear()
        breaks.clear()
        resetMeta()
        ended = n
        return SeasonEnd(n, awards, events, inducted, dynasties, review)
    }

    private fun traitShares(keys: List<String>, rating: (String) -> Double, traits: (String) -> Map<String, String>?, last: MetaReport?): List<TraitShare> {
        val pop = keys.mapNotNull { k -> traits(k)?.let { t -> Triple(k, rating(k), t) } }.filter { !it.second.isNaN() }
            .sortedByDescending { it.second }
        if (pop.size < 5) return emptyList()
        val topN = (pop.size + 4) / 5
        val counts = HashMap<Pair<String, String>, IntArray>()
        for ((i, p) in pop.withIndex()) for ((t, v) in p.third) counts.getOrPut(t to v) { IntArray(2) }[if (i < topN) 0 else 1]++
        val rest = pop.size - topN
        val order = compareByDescending<Map.Entry<Pair<String, String>, IntArray>> { it.value[0] + it.value[1] }
            .thenBy { it.key.first }.thenBy { it.key.second }
        return counts.entries.sortedWith(order).take(MAX_TRAITS).map { (tv, c) ->
            val top = c[0].toDouble() / topN
            val was = last?.traits?.firstOrNull { it.trait == tv.first && it.value == tv.second }?.top
            TraitShare(tv.first, tv.second, top, if (rest == 0) Double.NaN else c[1].toDouble() / rest, if (was == null) Double.NaN else top - was)
        }.sortedWith(compareBy<TraitShare> { it.trait }.thenByDescending { it.top })
    }

    // ---- saving --------------------------------------------------------------------------------

    fun encode(): String = buildString {
        append("stats 1\n")
        // Rating sums are in 0.005 steps; files before this line kept them in 0.01 steps.
        append("units ${AiStats.RATING_UNITS}\n")
        append("book $season $ended $officialGames\n")
        for (k in (startRatings.keys + seasonStats.keys + rookieKeys).sorted()) {
            val s = seasonStats[k] ?: AiStats()
            append("s ${Field.esc(k)} ${Field.num(startRatings[k] ?: Double.NaN, 0)} ${if (k in rookieKeys) 1 else 0} ${s.encode()}\n")
        }
        for (k in careerStats.keys.sorted()) {
            append("c ${Field.esc(k)} ${careerStats.getValue(k).encode()}")
            // Career titles and MVPs, only when there are any.
            val t = titleCounts[k] ?: 0
            val m = mvpCounts[k] ?: 0
            if (t > 0 || m > 0) append(' ').append(t.toString(36)).append(' ').append(m.toString(36))
            append('\n')
        }
        for (r in records.all) append("rec ${r.encode()}\n")
        for ((k, n) in firstSeason) append("first ${k.name} $n\n")
        for (b in breaks.values) append("brk ${b.encode()}\n")
        append("box $metaGames $metaScore $metaCards ${metaSums.joinToString(",")} ${metaZeros.joinToString(",")}\n")
        previousMeta?.encode(this)
        meta?.encode(this)
        for (e in milestoneList.reversed()) append("ms ${e.encode()}\n")
    }

    companion object {
        const val RECORD_WARMUP = 2000

        /** Games before a player's rating counts for 역대 최고 레이팅 (a new one still moves a lot). */
        const val PEAK_GAMES = 60
        const val MAX_MILESTONES = 100
        const val MAX_TRAITS = 32

        /** League, cups, champions league, club boards, playoffs: yes; friendlies, the all-star game, exhibitions and the lab: no. */
        fun countsForSeason(c: Comp): Boolean = c != Comp.FRIENDLY && c != Comp.ALL_STAR && c != Comp.LEGEND && c != Comp.LAB

        /** 1000, 5000, 10000, 50000, ... from [first] (wins from 100, Yachts from 50). */
        internal fun isMilestone(n: Int, first: Int): Boolean {
            if (n < first) return false
            var p = 1L
            while (p * 10 <= n) p *= 10
            return n.toLong() == p || n.toLong() == 5 * p
        }

        /**
         * Each game of a pairing from A's side and from B's: which card is whose comes from the
         * totals, and for level games from the seat order [LeagueMatch] plays (mirrored games
         * alternate from A, single games all have A first, deciders alternate from A).
         */
        internal fun sides(b: BoardResult, pairs: Boolean = false): Pair<List<SideGame>, List<SideGame>> {
            val first = seats(b, pairs)
            val nd = b.games.size - b.deciders
            val xs = ArrayList<SideGame>(b.games.size)
            val ys = ArrayList<SideGame>(b.games.size)
            for ((i, g) in b.games.withIndex()) {
                val f = b.facts.getOrNull(i)
                val decider = i >= nd
                if (f == null) {
                    xs += SideGame(g.a, g.b, g.aYacht, g.aBonus, null, Double.NaN, Double.NaN, Double.NaN, decider)
                    ys += SideGame(g.b, g.a, g.bYacht, g.bBonus, null, Double.NaN, Double.NaN, Double.NaN, decider)
                    continue
                }
                var lo = 100
                var hi = 0
                for (p in f.curve) {
                    lo = minOf(lo, p.toInt())
                    hi = maxOf(hi, p.toInt())
                }
                val (r1, r2) = f.ratings
                // The first player's P range; the second's is its mirror.
                val firstGame = { own: Int, opp: Int, y: Boolean, bo: Boolean -> SideGame(own, opp, y, bo, f.first, lo / 100.0, hi / 100.0, r1, decider) }
                val secondGame = { own: Int, opp: Int, y: Boolean, bo: Boolean -> SideGame(own, opp, y, bo, f.second, (100 - hi) / 100.0, (100 - lo) / 100.0, r2, decider) }
                if (first[i]) {
                    xs += firstGame(g.a, g.b, g.aYacht, g.aBonus)
                    ys += secondGame(g.b, g.a, g.bYacht, g.bBonus)
                } else {
                    xs += secondGame(g.a, g.b, g.aYacht, g.aBonus)
                    ys += firstGame(g.b, g.a, g.bYacht, g.bBonus)
                }
            }
            return xs to ys
        }

        /** True where A moved first in game i of [b]. */
        internal fun seats(b: BoardResult, pairs: Boolean = false): BooleanArray {
            val nd = b.games.size - b.deciders
            // Mirrored matches have an even number of games; single-game legs may too, so a game
            // whose totals differ tells them apart. A series is pairs whatever its count ([pairs]).
            var single = !pairs && nd % 2 == 1
            if (!single && !pairs) {
                for (i in 1 until minOf(nd, b.facts.size) step 2) {
                    val g = b.games[i]
                    if (g.a == g.b) continue
                    single = b.facts[i].first.total == g.a
                    break
                }
            }
            return BooleanArray(b.games.size) { i ->
                val f = b.facts.getOrNull(i)
                val g = b.games[i]
                when {
                    f != null && g.a != g.b -> f.first.total == g.a
                    i < nd -> single || i % 2 == 0
                    else -> (i - nd) % 2 == 0
                }
            }
        }

        /** Reads stats.txt; throws when it is not a stats file at all (the caller starts fresh). */
        fun decode(text: String, recordWarmup: Int = RECORD_WARMUP): StatsBook {
            val all = text.lines()
            require(all.firstOrNull()?.trim() == "stats 1") { "not a stats file" }
            // Every line ends in a newline: a last one without it was cut (a torn write).
            val lines = if (text.endsWith('\n')) all else all.dropLast(1)
            val book = StatsBook(recordWarmup)
            // Rating sums in 0.01 steps (files from before the 0.005 steps): such files have no
            // "units" line, and no career line with title or MVP counts although seasons ended.
            val units = lines.firstOrNull { it.startsWith("units ") }?.substringAfter(' ')?.trim()?.toIntOrNull()
            val careerLines = lines.filter { it.startsWith("c ") }
            val legacy = units == null && careerLines.isNotEmpty() && careerLines.none { it.split(' ').size > 2 + AiStats.FIELDS } &&
                lines.any { it.startsWith("book ") && (it.split(' ').getOrNull(2)?.toIntOrNull() ?: 0) > 0 }
            val metas = ArrayList<MetaReport>()
            val shares = ArrayList<Pair<Int, TraitShare>>()
            for (line in lines) {
                runCatching {
                    val f = line.split(' ')
                    when (f[0]) {
                        "book" -> {
                            val s = f[1].toInt()
                            val e = f[2].toInt()
                            val g = f[3].toLong()
                            book.season = s
                            book.ended = e
                            book.officialGames = g
                        }
                        "s" -> {
                            val k = Field.unesc(f[1])
                            val start = Field.dbl(f[2])
                            val rookie = f[3] == "1"
                            val st = AiStats.decode(f, 4).also { if (legacy) it.doubleRatingUnits() }
                            // Kept when unknown (NaN) too: a restart's startSeason must not fill
                            // it with a rating from the middle of the season.
                            book.startRatings[k] = start
                            if (rookie) book.rookieKeys += k
                            if (st.games > 0 || st.matches > 0) book.seasonStats[k] = st
                        }
                        "c" -> {
                            val k = Field.unesc(f[1])
                            val st = AiStats.decode(f, 2).also { if (legacy) it.doubleRatingUnits() }
                            val t = f.getOrNull(2 + AiStats.FIELDS)?.toInt(36) ?: 0
                            val m = f.getOrNull(3 + AiStats.FIELDS)?.toInt(36) ?: 0
                            book.careerStats[k] = st
                            if (t > 0) book.titleCounts[k] = t
                            if (m > 0) book.mvpCounts[k] = m
                        }
                        "rec" -> LeagueRecord.decode(f, 1)?.let { book.records.put(it) }
                        "first" -> runCatching { book.firstSeason[RecordKind.valueOf(f[1])] = f[2].toInt() }
                        "brk" -> RecordBreak.decode(f, 1)?.let { book.breaks[it.record.kind] = it }
                        "box" -> {
                            val games = f[1].toInt()
                            val score = f[2].toLong()
                            val cards = f[3].toInt()
                            val sums = f[4].split(',').map { it.toLong() }
                            val zeros = f[5].split(',').map { it.toInt() }
                            require(sums.size == Category.COUNT && zeros.size == Category.COUNT)
                            book.metaGames = games
                            book.metaScore = score
                            book.metaCards = cards
                            sums.forEachIndexed { i, x -> book.metaSums[i] = x }
                            zeros.forEachIndexed { i, x -> book.metaZeros[i] = x }
                        }
                        "meta" -> metas += MetaReport.decode(f)
                        "mt" -> shares += f[1].toInt() to TraitShare(Field.unesc(f[2]), Field.unesc(f[3]), Field.dbl(f[4]), Field.dbl(f[5]), Field.dbl(f[6]))
                        "ms" -> LeagueEvent.decode(line.substringAfter(' '))?.let { book.milestoneList.addFirst(it) }
                    }
                }
            }
            val reports = metas.sortedBy { it.season }.takeLast(2).map { m -> m.copy(traits = shares.filter { it.first == m.season }.map { it.second }) }
            book.meta = reports.lastOrNull()
            book.previousMeta = reports.getOrNull(reports.size - 2)
            while (book.milestoneList.size > MAX_MILESTONES) book.milestoneList.removeLast()
            return book
        }
    }
}

/** Text fields of stats.txt and archive.txt: no spaces or separators inside, "-" for none. */
internal object Field {
    private const val SPECIAL = "% ,;:|\n\r\t\u001f"

    fun esc(s: String?): String {
        if (s.isNullOrEmpty()) return "-"
        if (s == "-") return "%2D"
        if (s.none { it in SPECIAL }) return s
        val b = StringBuilder(s.length + 8)
        for (ch in s) if (ch in SPECIAL) b.append('%').append(String.format(Locale.ROOT, "%02X", ch.code)) else b.append(ch)
        return b.toString()
    }

    /** The text of [esc] back ("" for "-"). */
    fun unesc(s: String): String {
        if (s == "-") return ""
        if ('%' !in s) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                b.append(s.substring(i + 1, i + 3).toInt(16).toChar())
                i += 3
            } else {
                b.append(ch)
                i++
            }
        }
        return b.toString()
    }

    fun opt(s: String): String? = if (s == "-") null else unesc(s)

    /** [x] with up to [digits] decimals and no trailing zeros; "-" for NaN. */
    fun num(x: Double, digits: Int): String {
        if (x.isNaN() || x.isInfinite()) return "-"
        val s = String.format(Locale.ROOT, "%.${digits}f", x)
        return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
    }

    fun dbl(s: String): Double = if (s == "-") Double.NaN else s.toDouble()

    /**
     * Counts as one fixed-width base-36 run: the width in one digit, then every value padded to
     * it, with no separators ("3" + "01k" + "2b0" + ... for 12 box sums). Counts are never
     * negative; one would be saved as 0 rather than fail the save.
     */
    fun run(xs: IntArray): String {
        val digits = xs.map { it.coerceAtLeast(0).toString(36) }
        val w = maxOf(1, digits.maxOfOrNull { it.length } ?: 1)
        val b = StringBuilder(1 + w * xs.size).append(w.toString(36))
        for (d in digits) {
            repeat(w - d.length) { b.append('0') }
            b.append(d)
        }
        return b.toString()
    }

    /** The values of [run]; throws on a cut or bad run. */
    fun unrun(s: String): IntArray {
        val w = s[0].digitToInt(36)
        require(w >= 1 && (s.length - 1) % w == 0) { "bad run" }
        return IntArray((s.length - 1) / w) { s.substring(1 + it * w, 1 + (it + 1) * w).toInt(36) }
    }
}

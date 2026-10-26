package yacht.engine.league

import kotlin.math.roundToInt

/** One AI's league season (성장 곡선): its division (0 = top) and place, record, final rating and average match rating. */
data class SeasonLine(
    val season: Int,
    val division: Int,
    val position: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val rating: Double,
    val avgRating: Double,
) {
    val points: Int get() = 3 * wins + draws
}

/**
 * A season in short for the archive (시즌 아카이브): titles, runners-up and awards ([honours]),
 * the top scorer with its average, the records broken, and the league's games and average score.
 */
data class SeasonSummary(
    val season: Int,
    val honours: List<Honour>,
    val topScorer: String?,
    val topScore: Double,
    val records: List<RecordBreak>,
    val games: Int,
    val avgScore: Double,
) {
    fun of(kind: String): String? = honours.firstOrNull { it.kind == kind }?.key
}

/**
 * The league's history (league/archive.txt, docs/LEAGUE_SPEC.md section 3): season summaries
 * of the newest [MAX_SEASONS] seasons, every AI's season lines over the newest [LINE_SEASONS],
 * dynasties, the hall of fame, the highlights the media module keeps per season, and the newest
 * season review until it has been shown. Not thread-safe: callers synchronise.
 */
class Archive {
    private val summaryMap = java.util.TreeMap<Int, SeasonSummary>()
    private val lineMap = HashMap<String, ArrayList<SeasonLine>>()
    private val dynastyList = ArrayList<Dynasty>()
    private val hof = ArrayList<HallOfFameEntry>()
    private val highlightMap = java.util.TreeMap<Int, List<String>>()

    /** The newest season review (시즌 결산). */
    var review: SeasonReview? = null
        private set
    private var reviewSeen = false

    /** The review while it has not been shown yet ([reviewShown] once it has). */
    val pendingReview: SeasonReview? get() = review?.takeIf { !reviewSeen }

    fun reviewShown() {
        reviewSeen = true
    }

    fun summary(season: Int): SeasonSummary? = summaryMap[season]

    /** Every summary kept, oldest first. */
    val summaries: List<SeasonSummary> get() = summaryMap.values.toList()

    // The views are copies: a snapshot built under the lock stays as it was after a rollover.

    /** [key]'s season lines, oldest first. */
    fun lines(key: String): List<SeasonLine> = lineMap[key]?.toList().orEmpty()

    /** AIs with season lines. */
    val lineKeys: Set<String> get() = lineMap.keys.toSet()

    val dynasties: List<Dynasty> get() = dynastyList.toList()
    val hallOfFame: List<HallOfFameEntry> get() = hof.toList()

    fun inHallOfFame(key: String): Boolean = hof.any { it.key == key }

    /**
     * Keeps the media module's highlight lines of [season] (opaque to the archive, at most
     * [MAX_HIGHLIGHTS], each up to [MAX_HIGHLIGHT_BYTES] bytes in UTF-8; longer ones are left
     * out), replacing what the season had. The newest [HIGHLIGHT_SEASONS] seasons keep theirs.
     */
    fun putHighlights(season: Int, lines: List<String>) {
        val keep = lines.map { it.replace('\n', ' ').replace('\r', ' ') }
            .filter { it.isNotBlank() && it.encodeToByteArray().size <= MAX_HIGHLIGHT_BYTES }.take(MAX_HIGHLIGHTS)
        if (keep.isEmpty()) highlightMap.remove(season) else highlightMap[season] = keep
        while (highlightMap.size > HIGHLIGHT_SEASONS) highlightMap.pollFirstEntry()
    }

    fun highlights(season: Int): List<String> = highlightMap[season].orEmpty()

    internal fun putSummary(s: SeasonSummary) {
        summaryMap[s.season] = s
        while (summaryMap.size > MAX_SEASONS) summaryMap.pollFirstEntry()
        // Season lines older than the newest LINE_SEASONS seasons go, retired AIs' with them.
        val oldest = s.season - LINE_SEASONS
        val it = lineMap.values.iterator()
        while (it.hasNext()) {
            val list = it.next()
            list.removeAll { l -> l.season <= oldest }
            if (list.isEmpty()) it.remove()
        }
    }

    /** Adds [line] in the precision the file keeps (2 rating points, 0.01 of match rating), so a reload changes nothing. */
    internal fun addLine(key: String, line: SeasonLine) {
        val list = lineMap.getOrPut(key) { ArrayList() }
        list.removeAll { it.season == line.season }
        list += line.copy(rating = ratingOf(ratingCode(line.rating)), avgRating = avgOf(avgCode(line.avgRating)))
        list.sortBy { it.season }
        while (list.size > LINE_SEASONS) list.removeAt(0)
    }

    /** A dynasty started, or a run extended (same holder, kind, title and first season). */
    internal fun putDynasty(d: Dynasty) {
        dynastyList.removeAll { it.key == d.key && it.kind == d.kind && it.title == d.title && it.from == d.from }
        dynastyList += d
        if (dynastyList.size > MAX_DYNASTIES) {
            dynastyList.sortBy { it.to }
            while (dynastyList.size > MAX_DYNASTIES) dynastyList.removeAt(0)
        }
    }

    /**
     * Inducts [e]; a full hall first lets its oldest member for which [evictable] says yes go.
     * False when [e] is a member already or no member may go (then it stays out).
     */
    internal fun induct(e: HallOfFameEntry, evictable: (String) -> Boolean = { true }): Boolean {
        if (inHallOfFame(e.key)) return false
        if (hof.size >= MAX_HALL_OF_FAME) {
            val i = hof.indexOfFirst { evictable(it.key) }
            if (i < 0) return false
            hof.removeAt(i)
        }
        hof += e
        return true
    }

    internal fun putReview(r: SeasonReview) {
        review = r
        reviewSeen = false
    }

    // ---- saving --------------------------------------------------------------------------------

    fun encode(): String = buildString {
        append("archive 1\n")
        for (s in summaryMap.values) {
            append("sum ${s.season} ${s.games} ${Field.num(s.avgScore, 2)} ${Field.esc(s.topScorer)} ${Field.num(s.topScore, 2)} ")
            append(s.honours.joinToString(",") { "${it.kind}:${Field.esc(it.key)}" }.ifEmpty { "-" }).append(' ')
            append(s.records.joinToString(",") { RecordBreak.short(it) }.ifEmpty { "-" }).append('\n')
        }
        for (k in lineMap.keys.sorted()) {
            val list = lineMap.getValue(k)
            if (list.isEmpty()) continue
            append("sl ${Field.esc(k)} ${list.first().season} ")
            packLines(list, this)
            append('\n')
        }
        for (d in dynastyList) append("dyn ${Field.esc(d.key)} ${d.kind} ${d.title} ${d.count} ${d.from} ${d.to}\n")
        for (e in hof) append("hof ${Field.esc(e.key)} ${Field.esc(e.name)} ${e.season} ${e.reason} ${e.titles} ${e.games} ${Field.num(e.winRate, 3)}\n")
        for ((season, lines) in highlightMap) for (l in lines) append("hl $season $l\n")
        review?.encode(this, reviewSeen)
    }

    companion object {
        const val MAX_SEASONS = 100

        /** Season lines kept per AI: the newest this many league seasons. */
        const val LINE_SEASONS = 30
        const val MAX_DYNASTIES = 200
        const val MAX_HALL_OF_FAME = 200
        const val MAX_HIGHLIGHTS = 3
        /** A team match's line carries both lineups: about 190-270 bytes. */
        const val MAX_HIGHLIGHT_BYTES = 360

        /** Seasons whose best games are kept (older ones' AIs have mostly retired: no replay). */
        const val HIGHLIGHT_SEASONS = 30

        // A season line packs into WIDTH base-36 digits: division 1, place 2, W/D/L 2 each, the
        // rating 2 ((rating + RATING_OFFSET) / 2: -798..1790 in 2-point steps, the league spans
        // about 0..1550; 0 for none), average match rating x100 2 (NONE: none). Seasons run on
        // from the line's first season, '.' for a season the AI was not in the league. 12,000
        // lines (400 AIs over 30 seasons) take 160 KB this way instead of 300 in plain fields.
        private const val WIDTH = 13
        private const val RATING_OFFSET = 800
        private const val NONE = 36 * 36 - 1

        private fun ratingCode(r: Double) = if (r.isNaN()) 0 else ((r + RATING_OFFSET) / 2).roundToInt().coerceIn(1, NONE)
        private fun ratingOf(code: Int) = if (code == 0) Double.NaN else (2 * code - RATING_OFFSET).toDouble()
        private fun avgCode(a: Double) = if (a.isNaN()) NONE else (a * 100).roundToInt().coerceIn(0, NONE - 1)
        private fun avgOf(code: Int) = if (code == NONE) Double.NaN else code / 100.0

        private fun put(out: StringBuilder, v: Int, width: Int) {
            var max = 1
            repeat(width) { max *= 36 }
            val s = v.coerceIn(0, max - 1).toString(36)
            repeat(width - s.length) { out.append('0') }
            out.append(s)
        }

        private fun packLines(list: List<SeasonLine>, out: StringBuilder) {
            var next = list.first().season
            for (l in list) {
                while (next < l.season) {
                    out.append('.')
                    next++
                }
                put(out, l.division, 1)
                put(out, l.position, 2)
                put(out, l.wins, 2)
                put(out, l.draws, 2)
                put(out, l.losses, 2)
                put(out, ratingCode(l.rating), 2)
                put(out, avgCode(l.avgRating), 2)
                next = l.season + 1
            }
        }

        /** The lines of a packed field; a cut entry at the end is dropped. */
        private fun unpackLines(first: Int, packed: String): List<SeasonLine> {
            val out = ArrayList<SeasonLine>()
            var season = first
            var i = 0
            fun take(width: Int): Int = packed.substring(i, i + width).toInt(36).also { i += width }
            while (i < packed.length) {
                if (packed[i] == '.') {
                    i++
                    season++
                    continue
                }
                if (i + WIDTH > packed.length) break
                val d = take(1)
                val pos = take(2)
                val w = take(2)
                val dr = take(2)
                val l = take(2)
                val r = take(2)
                val a = take(2)
                out += SeasonLine(season, d, pos, w, dr, l, ratingOf(r), avgOf(a))
                season++
            }
            return out
        }

        /** Reads archive.txt; throws when it is not an archive at all (the caller starts fresh). */
        fun decode(text: String): Archive {
            val all = text.lines()
            require(all.firstOrNull()?.trim() == "archive 1") { "not an archive file" }
            // Every line ends in a newline: a last one without it was cut (a torn write).
            val lines = if (text.endsWith('\n')) all else all.dropLast(1)
            val a = Archive()
            val review = ArrayList<String>()
            for (line in lines) {
                runCatching {
                    val f = line.split(' ')
                    when (f[0]) {
                        "sum" -> {
                            val season = f[1].toInt()
                            val honours = if (f[6] == "-") emptyList() else f[6].split(',').map { h ->
                                val p = h.split(':')
                                Honour(season, p[0], Field.unesc(p[1]))
                            }
                            val records = if (f[7] == "-") emptyList() else f[7].split(',').mapNotNull { RecordBreak.fromShort(it, season) }
                            a.summaryMap[season] = SeasonSummary(season, honours, Field.opt(f[4]), Field.dbl(f[5]), records, f[2].toInt(), Field.dbl(f[3]))
                        }
                        "sl" -> {
                            val list = unpackLines(f[2].toInt(), f[3])
                            if (list.isNotEmpty()) a.lineMap[Field.unesc(f[1])] = ArrayList(list.takeLast(LINE_SEASONS))
                        }
                        "dyn" -> a.dynastyList += Dynasty(Field.unesc(f[1]), f[2], f[3], f[4].toInt(), f[5].toInt(), f[6].toInt())
                        "hof" -> a.hof += HallOfFameEntry(Field.unesc(f[1]), Field.unesc(f[2]), f[3].toInt(), f[4], f[5].toInt(), f[6].toInt(), Field.dbl(f[7]))
                        "hl" -> {
                            val season = f[1].toInt()
                            val l = line.substringAfter(' ').substringAfter(' ')
                            val have = a.highlightMap[season].orEmpty()
                            if (l.isNotBlank() && have.size < MAX_HIGHLIGHTS) a.highlightMap[season] = have + l
                        }
                        "rv" -> review += line
                    }
                }
            }
            SeasonReview.decode(review)?.let { (r, seen) ->
                a.review = r
                a.reviewSeen = seen
            }
            while (a.summaryMap.size > MAX_SEASONS) a.summaryMap.pollFirstEntry()
            while (a.highlightMap.size > MAX_SEASONS) a.highlightMap.pollFirstEntry()
            while (a.hof.size > MAX_HALL_OF_FAME) a.hof.removeAt(0)
            while (a.dynastyList.size > MAX_DYNASTIES) a.dynastyList.removeAt(0)
            return a
        }
    }
}

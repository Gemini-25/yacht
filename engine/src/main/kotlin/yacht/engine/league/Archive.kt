package yacht.engine.league

import kotlin.math.roundToInt

/**
 * One AI's league season (성장 곡선): its [division] (its tier in its league, 0 = 1부; a pyramid's
 * tier, the group not kept) and place, record, final rating and average match rating, the
 * [league] it played in ([LeagueIds]; [LeagueIds.LEGACY] for the old single league) and its
 * [club] then (null without one).
 */
data class SeasonLine(
    val season: Int,
    val division: Int,
    val position: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val rating: Double,
    val avgRating: Double,
    val league: String = LeagueIds.LEGACY,
    val club: String? = null,
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
 * dynasties, the hall of fame, and the season reviews of the newest [REVIEWS] seasons (the
 * newest one marked until it has been shown). The lines' leagues and clubs are kept on their own
 * (`sx`, only where they change), and the reviews before the newest in `rw` blocks, so a version
 * from before national leagues reads the file as it always did, every season line and the
 * newest review included. A season's best games are no longer kept here (`hl` lines: those of
 * generated players never fit): the review names them ([SeasonReview.best]) and the match log
 * archive keeps their games. Not thread-safe: callers synchronise.
 */
class Archive {
    private val summaryMap = java.util.TreeMap<Int, SeasonSummary>()
    private val lineMap = HashMap<String, ArrayList<SeasonLine>>()
    private val dynastyList = ArrayList<Dynasty>()
    private val hof = ArrayList<HallOfFameEntry>()
    private val reviews = java.util.TreeMap<Int, SeasonReview>()

    /** The newest season review (시즌 결산). */
    val review: SeasonReview? get() = reviews.lastEntry()?.value
    private var reviewSeen = false

    /** The review while it has not been shown yet ([reviewShown] once it has). */
    val pendingReview: SeasonReview? get() = review?.takeIf { !reviewSeen }

    fun reviewShown() {
        reviewSeen = true
    }

    /** The review of [season], while it is kept. */
    fun review(season: Int): SeasonReview? = reviews[season]

    /** The seasons whose reviews are kept, newest first. */
    val reviewSeasons: List<Int> get() = reviews.descendingKeySet().toList()

    /**
     * Adds to [season]'s review what is known only after the close: the league ranking's moves
     * ([ranking]), the 요트협회's decisions ([federation]) and the transfers abroad ([transfers]);
     * a null leaves that part as it is. Each part replaces what the review had, so amending again
     * with the same values (a replay after a kill) changes nothing. False without that review.
     */
    fun amendReview(season: Int, ranking: List<Pair<String, Int>>? = null, federation: List<String>? = null, transfers: Int? = null): Boolean {
        val r = reviews[season] ?: return false
        reviews[season] = r.copy(ranking = ranking ?: r.ranking, federation = federation ?: r.federation, transfers = transfers ?: r.transfers)
        return true
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
     * Kept for its callers; keeps nothing. A season's best games are named by its review
     * ([SeasonReview.best]) and kept by the match log archive.
     */
    @Suppress("UNUSED_PARAMETER")
    fun putHighlights(season: Int, lines: List<String>) {}

    /** Nothing: the archive no longer keeps highlight lines (see [putHighlights]). */
    @Suppress("UNUSED_PARAMETER")
    fun highlights(season: Int): List<String> = emptyList()

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

    /**
     * Adds [line] in the precision the file keeps (2 rating points, 0.01 of match rating; a
     * league this version knows, a club id `c<n>` up to [MAX_CLUB]), so a reload changes nothing.
     */
    internal fun addLine(key: String, line: SeasonLine) {
        val list = lineMap.getOrPut(key) { ArrayList() }
        list.removeAll { it.season == line.season }
        list += line.copy(
            rating = ratingOf(ratingCode(line.rating)), avgRating = avgOf(avgCode(line.avgRating)),
            league = LeagueIds.ofCode(leagueCode(line.league)), club = clubOf(clubCode(line.club)),
        )
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

    /**
     * Keeps [r] (replacing its season's), the newest [REVIEWS] (fewer, [SMALL_REVIEWS], in a
     * league of fewer than [SMALL_LEAGUE] [members]); the newest is not shown yet.
     */
    internal fun putReview(r: SeasonReview, members: Int = SMALL_LEAGUE) {
        reviews[r.season] = r
        if (r.season == reviews.lastKey()) reviewSeen = false
        val keep = if (members < SMALL_LEAGUE) SMALL_REVIEWS else REVIEWS
        while (reviews.size > keep) reviews.pollFirstEntry()
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
            // League and club apart from the 13-digit lines, so an older version reads the file as before.
            if (list.any { it.league != LeagueIds.LEGACY || it.club != null }) {
                append("sx ${Field.esc(k)}")
                packPlaces(list, this)
                append('\n')
            }
        }
        for (d in dynastyList) append("dyn ${Field.esc(d.key)} ${d.kind} ${d.title} ${d.count} ${d.from} ${d.to}\n")
        for (e in hof) append("hof ${Field.esc(e.key)} ${Field.esc(e.name)} ${e.season} ${e.reason} ${e.titles} ${e.games} ${Field.num(e.winRate, 3)}\n")
        // The newest review as "rv" (what older versions read), the others as "rw" blocks.
        val newest = reviews.lastEntry()?.value
        for (r in reviews.values) if (r !== newest) r.encode(this, true, "rw")
        newest?.encode(this, reviewSeen)
    }

    companion object {
        const val MAX_SEASONS = 100

        /** Season lines kept per AI: the newest this many league seasons. */
        const val LINE_SEASONS = 30
        const val MAX_DYNASTIES = 200
        const val MAX_HALL_OF_FAME = 200

        /** Season reviews kept (about 1.5 KB each at 1000 players), and in a league of fewer than [SMALL_LEAGUE] members. */
        const val REVIEWS = 6
        const val SMALL_REVIEWS = 3
        const val SMALL_LEAGUE = 500

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

        // A season line's league and club go into the key's one `sx` line: an entry of PLACE_WIDTH
        // base-36 digits (season 2, league 1 [LeagueIds.code], club number 2, 00 = none) wherever
        // they differ from the line before (before the first: the old single league, no club). A
        // line takes the newest entry of its season or before. About 1.5 entries a career. From
        // season 1296 (a league left running for days) an entry's season takes 3 digits.
        private const val PLACE_WIDTH = 5
        private const val SHORT_SEASONS = 36 * 36

        /** The highest club number an `sx` entry holds (c1 ... c1295). */
        const val MAX_CLUB = 36 * 36 - 1

        /** [LeagueIds.code] of [id]; 0 (the old single league) for one this version does not know. */
        private fun leagueCode(id: String) = runCatching { LeagueIds.code(id) }.getOrDefault(0)

        /** The number of a club id `c<n>` ([ClubBook.idOf]); 0 for none or an id the archive cannot hold. */
        private fun clubCode(id: String?): Int {
            val digits = id?.takeIf { it.length > 1 && it[0] == 'c' }?.substring(1)?.takeIf { d -> d.all { it in '0'..'9' } } ?: return 0
            return digits.toIntOrNull()?.takeIf { it in 1..MAX_CLUB } ?: 0
        }

        private fun clubOf(code: Int): String? = if (code == 0) null else ClubBook.idOf(code)

        private fun packPlaces(list: List<SeasonLine>, out: StringBuilder) {
            var league = 0
            var club = 0
            for (l in list) {
                val lc = leagueCode(l.league)
                val cc = clubCode(l.club)
                if (lc == league && cc == club) continue
                out.append(' ')
                put(out, l.season, if (l.season < SHORT_SEASONS) 2 else 3)
                put(out, lc, 1)
                put(out, cc, 2)
                league = lc
                club = cc
            }
        }

        /** [list]'s lines (oldest first) with the league and club of [entries] (an `sx` line's fields); bad entries are left out. */
        private fun placeLines(list: MutableList<SeasonLine>, entries: List<String>) {
            val places = entries.mapNotNull { e ->
                if (e.length != PLACE_WIDTH && e.length != PLACE_WIDTH + 1) return@mapNotNull null
                val w = e.length - 3
                runCatching { Triple(e.substring(0, w).toInt(36), LeagueIds.ofCode(e.substring(w, w + 1).toInt(36)), clubOf(e.substring(w + 1).toInt(36))) }.getOrNull()
            }.sortedBy { it.first }
            if (places.isEmpty()) return
            var at = -1
            for ((i, l) in list.withIndex()) {
                while (at + 1 < places.size && places[at + 1].first <= l.season) at++
                if (at >= 0) list[i] = l.copy(league = places[at].second, club = places[at].third)
            }
        }

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
            // The older reviews: each "rw head" line starts one.
            val older = ArrayList<ArrayList<String>>()
            val places = HashMap<String, List<String>>()
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
                        "sx" -> places[Field.unesc(f[1])] = f.drop(2)
                        "dyn" -> a.dynastyList += Dynasty(Field.unesc(f[1]), f[2], f[3], f[4].toInt(), f[5].toInt(), f[6].toInt())
                        "hof" -> a.hof += HallOfFameEntry(Field.unesc(f[1]), Field.unesc(f[2]), f[3].toInt(), f[4], f[5].toInt(), f[6].toInt(), Field.dbl(f[7]))
                        "rv" -> review += line
                        "rw" -> {
                            if (f[1] == "head") older += ArrayList<String>()
                            older.lastOrNull()?.add(line)
                        }
                    }
                }
            }
            for ((k, entries) in places) a.lineMap[k]?.let { placeLines(it, entries) }
            for (lines in older) SeasonReview.decode(lines)?.let { (r, _) -> a.reviews[r.season] = r }
            // The "rv" block is the newest (an older season's "rw" block of the same season gives way to it).
            SeasonReview.decode(review)?.let { (r, seen) ->
                a.reviews.keys.removeAll { it >= r.season }
                a.reviews[r.season] = r
                a.reviewSeen = seen
            }
            while (a.reviews.size > REVIEWS) a.reviews.pollFirstEntry()
            while (a.summaryMap.size > MAX_SEASONS) a.summaryMap.pollFirstEntry()
            while (a.hof.size > MAX_HALL_OF_FAME) a.hof.removeAt(0)
            while (a.dynastyList.size > MAX_DYNASTIES) a.dynastyList.removeAt(0)
            return a
        }
    }
}

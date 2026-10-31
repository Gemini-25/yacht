package yacht.engine.league

/**
 * The world's own story (league/world.txt, `world 1`, design ENG 8.1): the meta and its eras
 * ([meta], Meta.kt), the media ([media], Media.kt), the world events ([events], WorldEvents.kt) and
 * the encyclopedia's history ([enc], Encyclopedia.kt), each saved by its own [LinePart]s, and the
 * guards of the steps that write them ([guards]: one season each, so a step asked again for a
 * season it did returns its stored result). Written 5th, between roster.txt and clubs.txt, so a
 * kill that leaves it unwritten leaves the club book and the federation pre-close too. A book
 * without a world.txt (a save from before, or a fresh league) is empty: every weight 1.0, no arcs.
 * Not thread-safe: callers synchronise.
 */
class WorldBook {
    /** The last close whose world steps ran (scorecard, World Cup vote, arcs' close stages, finals, the `pr1` fold). */
    var closeSeason = 0
        internal set

    /** The last close whose meta weights and eras were updated. */
    var metaSeason = 0
        internal set

    /** The last close and winter checkpoint whose arc stages ran. */
    var arcClose = 0
        internal set
    var arcWinter = 0
        internal set

    val meta = MetaBook()
    val media = MediaBook()
    val events = ArcBook()
    val enc = EncBook()

    /**
     * Whether this book is behind season [season] in play by more than one close (an older build
     * played on without it, then an upgrade came back): its arcs and votes no longer fit the world.
     * A book that never closed a season is never stale.
     */
    fun stale(season: Int): Boolean = closeSeason > 0 && season - closeSeason > 1

    /**
     * Whether a guard of this book is past season [season] in play: a close or checkpoint of a
     * season this league has not reached, so the book is another league's (an older build's
     * 리그 초기화 leaves world.txt behind, as it did the stats). Its guards would skip this
     * league's own steps, so [LeagueWorld.decode] starts an empty book instead. Equal is not
     * ahead: the season's own close, read again after a kill.
     */
    fun ahead(season: Int): Boolean = maxOf(closeSeason, metaSeason, arcClose, arcWinter) > season

    /**
     * The stale rule: when [stale] for [season], drops the arcs under way, the ranking's bottom
     * quarters (the merger's trigger) and the World Cup votes after the last close it saw, and keeps
     * the history (eras, style shares, finals, league history, career weeks at #1, scorecards,
     * ceremonies). True when it dropped anything.
     */
    fun dropStale(season: Int): Boolean {
        if (!stale(season)) return false
        val before = events.active.size + events.bottom.size + media.votes.size
        events.active.clear()
        events.bottom.clear()
        media.votes.keys.removeAll { it > closeSeason }
        return events.active.size + events.bottom.size + media.votes.size != before
    }

    /** world.txt: the header, the guards, then every part's lines ([PARTS] order). */
    fun encode(): String = buildString {
        append("$HEADER\n")
        LineParts.encode(PARTS, this@WorldBook, this)
    }

    companion object {
        const val HEADER = "world 1"
        const val FILE = "world.txt"

        private val GUARDS = LineParts.of<WorldBook>("guards", { w, out ->
            out.append("guards ${w.closeSeason} ${w.metaSeason} ${w.arcClose} ${w.arcWinter}\n")
        }) { w, f ->
            w.closeSeason = f[1].toInt()
            w.metaSeason = f[2].toInt()
            w.arcClose = f[3].toInt()
            w.arcWinter = f[4].toInt()
            true
        }

        /** Every line kind of world.txt, in the order they are written (the guards first). */
        val PARTS: List<LinePart<WorldBook>> = listOf(GUARDS) + MetaBook.PARTS + MediaBook.PARTS + ArcBook.PARTS + EncBook.PARTS

        /**
         * Reads world.txt; lines that cannot be read are skipped (a cut file keeps what came
         * before the cut). Throws when it is not a world file (the caller starts an empty book).
         */
        fun decode(text: String): WorldBook {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == HEADER) { "not a world file" }
            return WorldBook().also { LineParts.decode(PARTS, it, lines.drop(1)) }
        }
    }
}

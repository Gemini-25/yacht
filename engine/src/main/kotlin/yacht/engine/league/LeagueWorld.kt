package yacht.engine.league

/**
 * The league between matches: the ladder, the season with its competitions, the honours, the
 * generated and evolved players and the news. The app's LeagueController holds one under its
 * lock, runs the workers and builds the AIs; everything here is pure and deterministic given the
 * results (docs/LEAGUE_SPEC.md sections 2 and 5). Not thread-safe: callers synchronise.
 *
 * A season ends in two steps so the app can rebuild its AIs in between: [closeSeason] (honours,
 * evolution, new generated players), then [openSeason] once [members] holds next season's AIs.
 */
class LeagueWorld(var ladder: Ladder, var config: LeagueConfig = LeagueConfig()) {
    var plan: SeasonPlan? = null
    var honours = HonourBook()
    var players = PlayerBook()

    /** The clubs (club league, transfers); null until the first season with clubs makes them. */
    var clubs: ClubBook? = null

    /** Season and career statistics, records, milestones and the league meta. */
    var stats = StatsBook()

    /** Past seasons: summaries, season lines, dynasties, hall of fame, highlights, the review. */
    var archive = Archive()

    /** Display names (the awards, the season review and the news name AIs), from the app's roster. */
    var name: (String) -> String = { it }

    /** A short string that changes when an AI's play changes (a highlight can then not be replayed). */
    var version: (String) -> String = { "" }

    /** The season's most dramatic games and the all-time best (league/highlights.txt). */
    var highlights = HighlightBook()

    /** News articles made from the events, newest first (league/news.txt). */
    var news = NewsBook()

    /** The newest finished fixtures, replayed on the live scoreboard, and the feed's win curves (memory only). */
    val live = LiveBoard()

    private val titleRace = TitleRaceWatch()

    /** AI 연구소: the current two-AI experiment and the newest results (league/lab.txt). */
    var lab = LabBook()

    /** News events, oldest first (the newest [EVENTS] are kept). */
    val events = ArrayDeque<LeagueEvent>()

    /** The AIs in the league next season (set by the app: me excluded, the config's filters applied). */
    var members: List<String> = emptyList()

    /** Each AI's family (the family cup), from the app's roster. */
    var family: (String) -> Family? = { null }

    /** Whether an AI is still in the app's roster (one deleted during a season places nowhere at its end). */
    var present: (String) -> Boolean = { true }

    /** The closing season's AIs that left during it, from [closeSeason] to [openSeason]. */
    private var closingGone: Set<String> = emptySet()

    /** Each AI's hometown: generated and evolved players have their own, the rest by key. */
    fun hometown(key: String): String = players[key]?.hometown ?: Hometowns.of(key)

    /** Rating for seeding: the ladder's, or [START_RATING] before an AI's first game. */
    fun rating(key: String): Double = ladder.rating(key).takeIf { !it.isNaN() } ?: START_RATING

    /** True once the season and its post-season are over: [closeSeason] is due. */
    val seasonOver: Boolean get() = plan?.finished == true

    private fun context(historyBefore: Int): Offseason.Context = Offseason.Context(
        members, ::rating, family, ::hometown, config, factories(),
        honours.all.filter { it.season < historyBefore }, closingGone,
    )

    /** Every competition of a season: the cups and playoffs, and the club league (making the clubs the first time). */
    private fun factories(): List<(SeasonStart) -> Competition?> = Competitions.factories() + ClubLeague.factory(
        { clubs },
        { s -> ClubBook.create(s.ranking, s.config.clubs, s.config.squad, s.seed, Hometowns.ALL).also { clubs = it } },
    )

    /**
     * The first season: divisions by rating; returns the draw news. It comes after the newest
     * season the league already knows (an imported history, a season file that could not be
     * read), so no kept season is written over and the books' per-season guards still run.
     */
    fun openFirstSeason(leagueSeed: Long): List<LeagueEvent> {
        val known = listOf(
            honours.seasons.maxOrNull() ?: 0, stats.ended, archive.summaries.maxOfOrNull { it.season } ?: 0,
            players.evolvedSeason, clubs?.season ?: 0,
        ).max()
        val p = Offseason.first(leagueSeed, context(Int.MAX_VALUE), known + 1)
        plan = p
        return opened(p)
    }

    /** Traits of a generated or evolved player in Korean (메타 리포트), null for the others. */
    private fun traits(key: String): Map<String, String>? =
        players[key]?.spec?.let { s -> Traits.of(s).entries.associate { (t, v) -> (Traits.NAMES[t] ?: t) to Traits.label(t, v) } }

    private fun opened(p: SeasonPlan): List<LeagueEvent> {
        // Start ratings (기량발전상) and rookies of the new season; the retired leave the stats.
        stats.retain(members + ladder.keys)
        stats.startSeason(p.number, members.associateWith { ladder.rating(it) })
        highlights.startSeason(p.number)
        val out = p.comps.flatMap { c -> runCatching { c.opening() }.getOrDefault(emptyList()) }
        note(out)
        return out
    }

    /**
     * The next fixture of the season (the post-season is set up once the league stage is over);
     * null when none is free now. See [SeasonPlan.next].
     */
    fun next(busy: Set<String>, cupsInFlight: Int, cupCap: Int, cost: ((LeagueFixture) -> Double)? = null): Fixture? {
        val p = plan ?: return null
        if (p.league.finished && !p.postStarted) p.startPost(Competitions.postFactories())
        return p.next(busy, cupsInFlight, cupCap, cost, teamsInFlight)
    }

    /** Team fixtures (club league, family cup) being played; set by the app around [next]. */
    var teamsInFlight = 0

    /** What [recordJob] did: the ladder's view of each rated board, and the news. */
    class Recorded(val matches: List<MatchRecord>, val events: List<LeagueEvent>)

    /**
     * Records a finished job: statistics first (they want the ratings from before the match),
     * then every rated board on the ladder, then the season (friendlies are only on the ladder).
     */
    fun recordJob(r: JobResult, nowMs: Long = 0L): Recorded {
        // Lab games count for the experiment only.
        if (r.fixture.comp == Comp.LAB) {
            lab.record(r)
            return Recorded(emptyList(), emptyList())
        }
        val out = ArrayList<LeagueEvent>()
        val season = plan?.number ?: 0
        val counted = runCatching { stats.record(r, StatsContext(season, { ladder.rating(it) }, ::hometown)) }.getOrDefault(emptyList())
        note(counted)
        out += counted
        val matches = ArrayList<MatchRecord>()
        if (r.fixture.rated) {
            for (b in r.boards) {
                if (b.games.isEmpty() || ladder.entry(b.a) == null || ladder.entry(b.b) == null) continue
                matches += ladder.record(b.a, b.b, b.games)
            }
        }
        if (r.fixture.comp != Comp.FRIENDLY) {
            out += record(r)
            plan?.let { p ->
                runCatching {
                    highlights.offer(r, p.number, ::rating, ::hometown, version, Drama.titleRace(p.league, r.fixture))
                }
                if (r.fixture.comp == Comp.LEAGUE) titleRace.check(p.league)?.let { e ->
                    note(listOf(e))
                    out += e
                }
            }
        }
        live.add(r, nowMs)
        return Recorded(matches, out)
    }

    /** Records a finished season fixture in the season alone; returns its news. */
    fun record(r: JobResult): List<LeagueEvent> {
        val out = plan?.record(r).orEmpty()
        note(out)
        return out
    }

    fun release(f: Fixture) {
        if (f.comp == Comp.LAB) lab.release(f) else plan?.release(f)
    }

    /** What [closeSeason] did. */
    class Closing(val season: Int, val honours: List<Honour>, val events: List<LeagueEvent>, val rosterChanged: Boolean)

    /**
     * End of season, first step: the season's honours (division champions after the playoffs,
     * moves, awards, every competition's titles), then evolution (the lowest rated generated and
     * evolved players retire, children are born) and generated players up to the config.
     * Idempotent by season.
     */
    fun closeSeason(): Closing {
        val p = plan ?: error("no season")
        // AIs deleted during the season (no title, award or move), decided once for both steps.
        // Evolution's retirees are no deletions (a replay after a kill finds them retired).
        val gone = p.league.standings.keys.filter { !present(it) && players.retiredRecord(it) == null }.toSet()
        closingGone = gone
        val moves = Offseason.moves(p, gone)
        val h = Offseason.honours(p, moves, gone)
        // Awards, records, hall of fame, dynasties and the season's archive, from the book as it
        // was before this season; then the season's honours go in once, awards included.
        val end = runCatching {
            // The ladder's own ratings: an AI off the ladder (retired by this close, read again
            // after a kill) has none, rather than a made-up starting one that would win awards.
            stats.endSeason(p, h, honours, archive, { k -> ladder.rating(k) }, family, name, null, ::traits, gone)
        }.getOrNull()
        honours.add(h + end?.awards.orEmpty())
        val out = ArrayList<LeagueEvent>()
        end?.let { out += it.events }
        // The season's three best games go to the archive.
        runCatching { archive.putHighlights(p.number, highlights.archiveLines(p.number)) }
        val champion = h.firstOrNull { it.kind == "L0" }?.key ?: "-"
        val cup = h.firstOrNull { it.kind == "CUP" }?.key ?: "-"
        out += LeagueEvent("season_review", p.number, listOf(champion, cup))
        // Titles and moves the competitions did not announce themselves: the title playoff
        // announces its champion and the promotion playoff its moves (read from the season, as
        // the events are not saved).
        val playoffChampion = p.competition(Comp.TITLE_PLAYOFF)?.honours()?.firstOrNull { it.kind == "L0" }?.key
        val promo = p.competition(Comp.PROMOTION_PLAYOFF) as? PromotionPlayoff
        val promoUp = promo?.honours()?.filter { it.kind.startsWith("UP") }?.map { it.key }.orEmpty()
        fun announced(kind: String, key: String) = when (kind) {
            "title" -> key == playoffChampion
            "promoted" -> key in promoUp
            else -> promo?.spots?.any { it.upper == key && it.lower in promoUp } == true
        }
        for (x in h) {
            val e = when {
                DIVISION.matches(x.kind) -> LeagueEvent("title", p.number, listOf(x.key), listOf(x.kind.removePrefix("L")), Comp.LEAGUE)
                x.kind.startsWith("UP") -> LeagueEvent("promoted", p.number, listOf(x.key), listOf(x.kind.removePrefix("UP")))
                x.kind.startsWith("DOWN") -> LeagueEvent("relegated", p.number, listOf(x.key), listOf(x.kind.removePrefix("DOWN")))
                else -> null
            } ?: continue
            if (!announced(e.kind, x.key)) out += e
        }
        var changed = false
        if (config.evolution > 0) {
            // Players who sat the season out (a preset left them out) neither retire nor breed;
            // children keep the book at the configured size, and the chaos preset breeds only
            // flawed ones (others would never play).
            val played = p.league.standings.keys
            val r = Evolution.run(
                players, p.number, { k -> if (k in played) ladder.rating(k) else Double.NaN }, honours::titles, config.evolution, p.seed,
                { honours.of(it) }, population = config.generated, accept = { s -> !config.onlyFlawed || s.flawed },
            )
            out += r.events
            changed = r.born.isNotEmpty() || r.retired.isNotEmpty()
            // A book below the configured size (generated players switched on again, nobody to
            // breed from): the next generated players come in.
            if (players.size < config.generated && players.ensureGenerated(config.generated, p.number + 1).isNotEmpty()) changed = true
        } else if (players.ensureGenerated(config.generated, p.number + 1).isNotEmpty()) {
            // Without evolution, a raised setting brings in the next generated players.
            changed = true
        }
        note(out)
        return Closing(p.number, h + end?.awards.orEmpty(), out, changed)
    }

    /**
     * End of season, second step (after the app set [members] from its rebuilt roster): the
     * clubs' off-season (prize money, retired members out, transfer window, draft), then the
     * next season. Returns the news of both.
     */
    fun openSeason(): List<LeagueEvent> {
        val p = plan ?: error("no season")
        val market = clubs?.let { c -> runCatching { c.offseason(p, ::rating, members.toSet(), config) }.getOrDefault(emptyList()) }.orEmpty()
        note(market)
        val (next, _) = Offseason.next(p, context(p.number))
        closingGone = emptySet()
        plan = next
        return market + opened(next)
    }

    /** Keeps the events and writes them up as news articles. */
    private fun note(e: List<LeagueEvent>) {
        if (e.isEmpty()) return
        for (x in e) events.addLast(x)
        while (events.size > EVENTS) events.removeFirst()
        runCatching {
            news.add(News.articles(e, name, { id -> clubs?.club(id)?.name ?: id }, Competitions::teamLabel, { k -> ladder.rating(k) }))
        }
    }

    // ---- saving --------------------------------------------------------------------------------

    /**
     * Every file of the league, by name, in the order to write them (atomically, one by one).
     * The season goes last and the ladder just before it: killed halfway through a rollover's
     * save, the league reads the closed season again and closes it again (every book's season
     * guard makes that a replay). The honours, clubs and archive go before the stats, and the
     * stats before the roster: once the roster has moved on (this season's retirees gone from
     * the ladder), the stats say the season ended and its awards, lines and review are kept as
     * they were. No season or clubs: empty files, so an older one is not read.
     */
    fun encodeFiles(): Map<String, String> = buildMap {
        put(HONOURS, honours.encode())
        put(CLUBS, clubs?.encode() ?: "")
        put(ARCHIVE, archive.encode())
        put(STATS, stats.encode())
        put(ROSTER, players.encode())
        put(HIGHLIGHTS, highlights.encode())
        put(NEWS, news.encode())
        put(LAB, lab.encode())
        put(LADDER, ladder.encode())
        put(SEASON, plan?.encode() ?: "")
    }

    companion object {
        const val LADDER = "ladder.txt"
        const val SEASON = "season.txt"
        const val HONOURS = "honours.txt"
        const val ROSTER = "roster.txt"
        const val CLUBS = "clubs.txt"
        const val STATS = "stats.txt"
        const val ARCHIVE = "archive.txt"
        const val HIGHLIGHTS = HighlightBook.FILE
        const val NEWS = NewsBook.FILE
        const val LAB = LabBook.FILE

        /** Files [encodeFiles] writes. */
        val FILES = listOf(LADDER, SEASON, HONOURS, ROSTER, CLUBS, STATS, ARCHIVE, HIGHLIGHTS, NEWS, LAB)

        /** Readers of every competition in season.txt. */
        val CODECS: Map<Comp, CompetitionCodec> = Competitions.codecs + (Comp.CLUB to ClubLeague.codec)

        const val EVENTS = 300

        /** Seeding rating before an AI's first game (a solid amateur). */
        const val START_RATING = 1400.0

        /**
         * Reads the saved league. [keys] are the ladder's keys (every AI and me); an unusable
         * file starts that part fresh. A season file of the first version moves its history into
         * the honours (the next start begins a season of the new kind).
         */
        fun decode(texts: Map<String, String?>, keys: List<String>, reserved: Collection<String>): LeagueWorld {
            // An empty file stands for a part not made yet (see encodeFiles).
            val files = texts.mapValues { (_, t) -> t?.takeIf { it.isNotBlank() } }
            val ladder = files[LADDER]?.let { t -> runCatching { Ladder.decode(keys, t) }.getOrNull() } ?: Ladder(keys)
            val w = LeagueWorld(ladder)
            val seasonText = files[SEASON]
            w.plan = seasonText?.let { t -> runCatching { SeasonPlan.decode(t, keys, CODECS) }.getOrNull() }
            w.honours = files[HONOURS]?.let { t -> runCatching { HonourBook.decode(t) }.getOrNull() } ?: HonourBook()
            if (w.plan == null && seasonText != null) w.honours.add(HonourBook.legacy(seasonText))
            w.players = files[ROSTER]?.let { t -> runCatching { PlayerBook.decode(t, reserved) }.getOrNull() } ?: PlayerBook(reserved)
            w.clubs = files[CLUBS]?.let { t -> runCatching { ClubBook.decode(t) }.getOrNull() }
            w.stats = files[STATS]?.let { t -> runCatching { StatsBook.decode(t) }.getOrNull() } ?: StatsBook()
            w.archive = files[ARCHIVE]?.let { t -> runCatching { Archive.decode(t) }.getOrNull() } ?: Archive()
            w.highlights = files[HIGHLIGHTS]?.let { t -> runCatching { HighlightBook.decode(t) }.getOrNull() } ?: HighlightBook()
            w.news = files[NEWS]?.let { t -> runCatching { NewsBook.decode(t) }.getOrNull() } ?: NewsBook()
            w.lab = files[LAB]?.let { t -> runCatching { LabBook.decode(t) }.getOrNull() } ?: LabBook()
            // A season in progress that the stats do not know (stats.txt is newer than the
            // season, or was lost): start ratings and rookies from now, so its awards are made.
            w.plan?.let { p ->
                if (w.stats.season != p.number && w.stats.ended < p.number) {
                    val keys = p.league.standings.keys
                    w.stats.startSeason(p.number, keys.associateWith { ladder.rating(it) }, keys.filter { w.players[it]?.born == p.number })
                }
            }
            return w
        }

        /**
         * Families whose AIs play with mistakes or misbeliefs (the 대혼돈 preset keeps only those,
         * and generated or custom AIs with a mistake or a temper).
         */
        private val DIVISION = Regex("L\\d")

        val FLAWED = setOf(Family.HUMAN, Family.CHARACTER, Family.RULE, Family.BELIEF, Family.SITUATION)
    }
}

/** An AI's career statistics as the preview and the scouting report read them (null with none). */
fun AiStats.boxProfile(): BoxProfile? = BoxProfile.of(
    carded, LongArray(boxSums.size) { boxSums[it].toLong() }, boxZeros, yachts, bonuses, comebacks, blownLeads,
)

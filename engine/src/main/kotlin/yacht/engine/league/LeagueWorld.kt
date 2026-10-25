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

    /** News events, oldest first (the newest [EVENTS] are kept). */
    val events = ArrayDeque<LeagueEvent>()

    /** The AIs in the league next season (set by the app: me excluded, the config's filters applied). */
    var members: List<String> = emptyList()

    /** Each AI's family (the family cup), from the app's roster. */
    var family: (String) -> Family? = { null }

    /** Each AI's hometown: generated and evolved players have their own, the rest by key. */
    fun hometown(key: String): String = players[key]?.hometown ?: Hometowns.of(key)

    /** Rating for seeding: the ladder's, or [START_RATING] before an AI's first game. */
    fun rating(key: String): Double = ladder.rating(key).takeIf { !it.isNaN() } ?: START_RATING

    /** True once the season and its post-season are over: [closeSeason] is due. */
    val seasonOver: Boolean get() = plan?.finished == true

    private fun context(historyBefore: Int): Offseason.Context = Offseason.Context(
        members, ::rating, family, ::hometown, config, factories(),
        honours.all.filter { it.season < historyBefore },
    )

    /** Every competition of a season: the cups and playoffs, and the club league (making the clubs the first time). */
    private fun factories(): List<(SeasonStart) -> Competition?> = Competitions.factories() + ClubLeague.factory(
        { clubs },
        { s -> ClubBook.create(s.ranking, s.config.clubs, s.config.squad, s.seed, Hometowns.ALL).also { clubs = it } },
    )

    /** The first season: divisions by rating; returns the draw news. */
    fun openFirstSeason(leagueSeed: Long): List<LeagueEvent> {
        val p = Offseason.first(leagueSeed, context(Int.MAX_VALUE))
        plan = p
        return opened(p)
    }

    private fun opened(p: SeasonPlan): List<LeagueEvent> {
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

    /** Records a finished season fixture (not friendlies: they are only on the ladder); returns its news. */
    fun record(r: JobResult): List<LeagueEvent> {
        val out = plan?.record(r).orEmpty()
        note(out)
        return out
    }

    fun release(f: Fixture) {
        plan?.release(f)
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
        val moves = Offseason.moves(p)
        val h = Offseason.honours(p, moves)
        honours.add(h)
        val out = ArrayList<LeagueEvent>()
        // Titles and moves the competitions did not announce themselves (the title playoff and
        // the promotion playoff do).
        fun announced(kind: String, key: String) = events.any { it.season == p.number && it.kind == kind && it.keys.firstOrNull() == key }
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
            val r = Evolution.run(players, p.number, { ladder.rating(it) }, honours::titles, config.evolution, p.seed, { honours.of(it) })
            out += r.events
            changed = r.born.isNotEmpty() || r.retired.isNotEmpty()
        }
        if (players.ensureGenerated(config.generated, p.number + 1).isNotEmpty()) changed = true
        note(out)
        return Closing(p.number, h, out, changed)
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
        plan = next
        return market + opened(next)
    }

    private fun note(e: List<LeagueEvent>) {
        for (x in e) events.addLast(x)
        while (events.size > EVENTS) events.removeFirst()
    }

    // ---- saving --------------------------------------------------------------------------------

    /** Every file of the league, by name, for the app to write (atomically). */
    fun encodeFiles(): Map<String, String> = buildMap {
        put(LADDER, ladder.encode())
        plan?.let { put(SEASON, it.encode()) }
        put(HONOURS, honours.encode())
        put(ROSTER, players.encode())
        clubs?.let { put(CLUBS, it.encode()) }
    }

    companion object {
        const val LADDER = "ladder.txt"
        const val SEASON = "season.txt"
        const val HONOURS = "honours.txt"
        const val ROSTER = "roster.txt"
        const val CLUBS = "clubs.txt"

        /** Files [encodeFiles] writes. */
        val FILES = listOf(LADDER, SEASON, HONOURS, ROSTER, CLUBS)

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
        fun decode(files: Map<String, String?>, keys: List<String>, reserved: Collection<String>): LeagueWorld {
            val ladder = files[LADDER]?.let { t -> runCatching { Ladder.decode(keys, t) }.getOrNull() } ?: Ladder(keys)
            val w = LeagueWorld(ladder)
            val seasonText = files[SEASON]
            w.plan = seasonText?.let { t -> runCatching { SeasonPlan.decode(t, keys, CODECS) }.getOrNull() }
            w.honours = files[HONOURS]?.let { t -> runCatching { HonourBook.decode(t) }.getOrNull() } ?: HonourBook()
            if (w.plan == null && seasonText != null) w.honours.add(HonourBook.legacy(seasonText))
            w.players = files[ROSTER]?.let { t -> runCatching { PlayerBook.decode(t, reserved) }.getOrNull() } ?: PlayerBook(reserved)
            w.clubs = files[CLUBS]?.let { t -> runCatching { ClubBook.decode(t) }.getOrNull() }
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

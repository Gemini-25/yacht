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

    /** The national teams' history (league/nations.txt). */
    var nations = NationBook()

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

    /** A town told apart by its nation, for derbies (코르도바 is in Spain and in Argentina). */
    fun derbyTown(key: String): String = players[key]?.let { p -> (p.identity?.nation ?: Nations.KR.code) + "/" + p.hometown } ?: ("KR/" + Hometowns.of(key))

    /** Rating for seeding: the ladder's, or [START_RATING] before an AI's first game. */
    fun rating(key: String): Double = ladder.rating(key).takeIf { !it.isNaN() } ?: prior(key)

    /**
     * A player off the ladder (a new league's, a newcomer's): [START_RATING] moved by its
     * abilities, so a new league's first tiers and seeds go by them rather than by key.
     */
    private fun prior(key: String): Double =
        players[key]?.identity?.abilities?.overall?.let { START_RATING + PRIOR_PER_POINT * (it - PRIOR_MID) } ?: START_RATING

    /** True once the season and its post-season are over: [closeSeason] is due. */
    val seasonOver: Boolean get() = plan?.finished == true

    private fun context(historyBefore: Int): Offseason.Context = Offseason.Context(
        members, ::rating, family, ::hometown, config, factories(),
        honours.all.filter { it.season < historyBefore }, closingGone, { k -> players[k]?.identity?.nation },
    )

    /** Every competition of a season: the cups and playoffs, and the club league (making the clubs the first time). */
    private fun factories(): List<(SeasonStart) -> Competition?> = Competitions.factories() + ClubLeague.factory(
        { clubs },
        { s -> ClubBook.create(s.ranking, s.config.clubs, s.config.squad, s.seed, Hometowns.ALL).also { clubs = it } },
    ) + LegendMatch.factory { players.legends }

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
        // Start ratings (기량발전상) and rookies of the new season; the retired leave the stats,
        // the closing season's a season later: a stats file saved before the roster must not
        // wipe a career that a replayed close (after a kill) keeps in the league.
        stats.retain(members + ladder.keys + players.retired.filter { it.retired >= p.number - 1 }.map { it.key })
        stats.startSeason(p.number, members.associateWith { ladder.rating(it) })
        highlights.startSeason(p.number)
        val out = p.comps.flatMap { c -> runCatching { c.opening() }.getOrDefault(emptyList()) }.map { it.at(SeasonCalendar.PRESEASON) }
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
        val f = r.fixture
        // Friendlies and exhibitions have no week of their own: their news goes in the week in play.
        val week = if (f.week != SeasonCalendar.NONE) f.week else plan?.let { p -> runCatching { p.week }.getOrNull() } ?: SeasonCalendar.NONE
        val counted = runCatching { stats.record(r, StatsContext(season, { ladder.rating(it) }, ::derbyTown)) }.getOrDefault(emptyList()).map { it.at(week) }
        note(counted)
        out += counted
        val matches = ArrayList<MatchRecord>()
        if (f.rated) {
            for ((i, b) in r.boards.withIndex()) {
                if (b.games.isEmpty() || ladder.entry(b.a) == null || ladder.entry(b.b) == null) continue
                val label = if (f.kind == JobKind.TEAM) "${f.label} · ${i + 1}번 보드".removePrefix(" · ") else f.label
                matches += ladder.record(b.a, b.b, b.games, comp = f.comp, label = label, week = f.week, season = season)
            }
        }
        if (r.fixture.comp != Comp.FRIENDLY) {
            out += record(r)
            plan?.let { p ->
                runCatching {
                    highlights.offer(r, p.number, ::rating, ::derbyTown, version, Drama.titleRace(p.league, r.fixture)) { k -> players[k]?.spec }
                }
                if (r.fixture.comp == Comp.LEAGUE) titleRace.check(p.league)?.at(week)?.let { e ->
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
        val out = ArrayList(plan?.record(r).orEmpty().map { it.at(r.fixture.week) })
        weekPick(r)?.let { out += it }
        note(out)
        return out
    }

    /**
     * The last matchday of season [pickedSeason] whose 이 주의 선수 was told: a matchday is told
     * when its last fixture is recorded, once (a stale result recorded again tells nothing).
     */
    private var pickedDay = -1
    private var pickedSeason = 0

    /** 이 주의 선수 when [r] finished its matchday: `player_of_week` [player, opponent] (week, average, best, tier). */
    private fun weekPick(r: JobResult): LeagueEvent? {
        val p = plan ?: return null
        if (r.fixture.comp != Comp.LEAGUE) return null
        val league = p.league
        if (pickedSeason != p.number) {
            pickedSeason = p.number
            pickedDay = -1
        }
        val day = runCatching { league.fixture(r.fixture.id).matchday }.getOrNull() ?: return null
        if (day <= pickedDay || !league.dayDone(day)) return null
        pickedDay = day
        val pick = league.playersOfWeek(day..day, closingGone + league.standings.keys.filter { !present(it) })[day] ?: return null
        return LeagueEvent(
            "player_of_week", p.number, listOf(pick.key, pick.opponent),
            listOf((day + 1).toString(), String.format(java.util.Locale.ROOT, "%.1f", pick.average), pick.best.toString(), league.tiers.tier(pick.division).toString()),
            Comp.LEAGUE, day + 1,
        )
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
        // 이 주의 선수 of every week, kept with the season's honours.
        val picks = runCatching { p.league.playersOfWeek(0 until p.league.matchdays, gone).values.sortedBy { it.matchday }.map { Honour(p.number, "POTW", it.key) } }.getOrDefault(emptyList())
        honours.add(h + end?.awards.orEmpty() + picks)
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
        // A pyramid's lower tiers (4부 down, and the moves below 3부) in one article: dozens of
        // group titles and hundreds of moves would flood the news.
        val grouped = !p.league.tiers.linear
        val lowerChampions = ArrayList<String>()
        var lowerUp = 0
        for (x in h) {
            val e = when {
                DIVISION.matches(x.kind) -> LeagueEvent("title", p.number, listOf(x.key), listOf(x.kind.removePrefix("L")), Comp.LEAGUE)
                x.kind.startsWith("UP") -> LeagueEvent("promoted", p.number, listOf(x.key), listOf(x.kind.removePrefix("UP")))
                x.kind.startsWith("DOWN") -> LeagueEvent("relegated", p.number, listOf(x.key), listOf(x.kind.removePrefix("DOWN")))
                else -> null
            } ?: continue
            if (grouped) {
                val t = e.args.single().toIntOrNull() ?: 0
                when {
                    e.kind == "title" && t > NEWS_TIERS -> { lowerChampions += x.key; continue }
                    e.kind == "promoted" && t > PromotionPlayoff.NEWS_BOUNDARIES -> { lowerUp++; continue }
                    e.kind == "relegated" && t - 1 > PromotionPlayoff.NEWS_BOUNDARIES -> continue
                }
            }
            if (!announced(e.kind, x.key)) out += e
        }
        if (lowerChampions.isNotEmpty()) {
            val named = lowerChampions.sortedWith(compareByDescending<String> { ladder.rating(it).takeIf { r -> !r.isNaN() } ?: 0.0 }.thenBy { it }).take(3)
            out += LeagueEvent(
                "lower_tiers", p.number, named,
                listOf(lowerChampions.size.toString(), lowerUp.toString(), (NEWS_TIERS + 1).toString(), (p.league.tiers.count - 1).toString()), Comp.LEAGUE,
            )
        }
        // The season's national cups go into the records, and a world champion's favourite style
        // comes into fashion (newcomers take it up more often for a few seasons).
        val cups = p.comps.filterIsInstance<NationCup>().filter { it.finished }.mapNotNull { runCatching { it.summary() }.getOrNull() }
        runCatching { nations.close(p.number, cups) { k -> players[k] != null || players.legend(k) != null } }
        val fashion = cups.firstOrNull { it.comp == Comp.WORLD_CUP }?.let { wc ->
            val champion = wc.podiums.firstOrNull()?.winner ?: return@let null
            val styles = wc.squads[champion].orEmpty().mapNotNull { k -> players[k]?.spec?.play?.primary }
            val style = styles.groupingBy { it }.eachCount().maxByOrNull { (k, n) -> n * 10 - styles.indexOf(k) }?.key ?: return@let null
            out += LeagueEvent("style_trend", p.number, listOf(champion), listOf(style), Comp.WORLD_CUP)
            style
        }
        // The hand-made AIs' last season when the league is for generated players only: they
        // leave it as its founders (their records stay; the app leaves them out of the members).
        if (!config.baseAis) {
            val founders = p.league.standings.keys.filter { Origin.of(it) == Origin.BASE && present(it) }
            if (founders.isNotEmpty()) {
                val named = founders.sortedWith(compareByDescending<String> { honours.titles(it) }.thenByDescending { ladder.rating(it) }.thenBy { it }).take(3)
                out += LeagueEvent("founders_farewell", p.number, named, listOf(founders.size.toString()))
            }
        }
        // Players age, retire and grow (with aging on), and new ones bring the book back to the
        // configured size. Players who sat the season out (a preset left them out) sit in the
        // middle of the ratings; the chaos preset brings in only flawed ones (others would never
        // play). Played: the share of the most league matches anyone played.
        val standings = p.league.standings
        val most = standings.values.maxOfOrNull { it.played }?.coerceAtLeast(1) ?: 1
        val r = Evolution.run(
            players, p.number, { k -> if (k in standings) ladder.rating(k) else Double.NaN }, honours::titles, p.seed, config.generated,
            { honours.of(it) }, childShare = config.childShare / 100.0, played = { k -> (standings[k]?.played ?: 0).toDouble() / most },
            accept = { s -> !config.onlyFlawed || s.flawed }, fashion = fashion, aging = config.aging,
        )
        out += r.events
        // Everyone a year older plays a little differently: their old results count half from
        // now on, so ratings follow who they are (careers and head-to-heads stay).
        if (config.aging) runCatching {
            ladder.decay(players.alive.filter { it.born <= p.number }.map { it.key }.toSet(), RATING_FADE, p.number)
            ladder.fit()
        }
        val changed = r.born.isNotEmpty() || r.retired.isNotEmpty() || players.reformed == p.number || config.aging
        val closed = out.map { it.at(SeasonCalendar.CLOSING) }
        note(closed)
        return Closing(p.number, h + end?.awards.orEmpty() + picks, closed, changed)
    }

    /**
     * End of season, second step (after the app set [members] from its rebuilt roster): the
     * clubs' off-season (prize money, retired members out, transfer window, draft), then the
     * next season. Returns the news of both.
     */
    fun openSeason(): List<LeagueEvent> {
        val p = plan ?: error("no season")
        val market = clubs?.let { c -> runCatching { c.offseason(p, ::rating, members.toSet(), config) }.getOrDefault(emptyList()) }.orEmpty().map { it.at(SeasonCalendar.CLOSING) }
        note(market)
        val hadClubs = clubs != null
        val (next, _) = Offseason.next(p, context(p.number))
        // Clubs drafted just now (switched on during the season) had this off-season already.
        if (!hadClubs) clubs?.founded(p.number, p.seed)
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
            news.add(News.articles(e, { k -> Nations.flagged(k) ?: name(k) }, { id -> clubs?.club(id)?.name ?: id }, Competitions::teamLabel, { k -> ladder.rating(k) }))
        }
    }

    // ---- saving --------------------------------------------------------------------------------

    /**
     * Every file of the league, by name, in the order to write them (atomically, one by one).
     * The season goes last and the ladder just before it: killed halfway through a rollover's
     * save, the league reads the closed season again and closes it again (every book's season
     * guard makes that a replay). The honours and archive go before the stats, the stats before
     * the roster: once the roster has moved on (this season's retirees gone from the ladder), the
     * stats say the season ended and its awards, lines and review are kept as they were. The
     * clubs come after the roster they were drafted from. No season or clubs: empty files, so an
     * older one is not read.
     */
    fun encodeFiles(): Map<String, String> = buildMap {
        put(HONOURS, honours.encode())
        put(ARCHIVE, archive.encode())
        put(STATS, stats.encode())
        put(ROSTER, players.encode())
        put(CLUBS, clubs?.encode() ?: "")
        put(HIGHLIGHTS, highlights.encode())
        put(NEWS, news.encode())
        put(LAB, lab.encode())
        put(NATIONS, nations.encode())
        put(LADDER, ladder.encode())
        put(SEASON, plan?.encode() ?: "")
    }

    companion object {
        /** A pyramid's tiers whose group titles make news of their own (0-based: 1부 to 3부). */
        const val NEWS_TIERS = 2

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
        const val NATIONS = NationBook.FILE

        /** What the results of a player who aged still weigh in the ratings after a season. */
        const val RATING_FADE = 0.5

        /** Files [encodeFiles] writes. */
        val FILES = listOf(LADDER, SEASON, HONOURS, ROSTER, CLUBS, STATS, ARCHIVE, HIGHLIGHTS, NEWS, LAB, NATIONS)

        /** Readers of every competition in season.txt. */
        val CODECS: Map<Comp, CompetitionCodec> = Competitions.codecs + (Comp.CLUB to ClubLeague.codec) + (Comp.LEGEND to LegendMatch.codec)

        const val EVENTS = 300

        /** Seeding rating before an AI's first game (a solid amateur). */
        const val START_RATING = 1400.0

        /** [prior]: rating points per point of overall ability from [PRIOR_MID]. */
        const val PRIOR_PER_POINT = 5.0
        const val PRIOR_MID = 50

        /**
         * Reads the saved league. [keys] are the ladder's keys (every AI and me); an unusable
         * file starts that part fresh. A season file of the first version moves its history into
         * the honours (the next start begins a season of the new kind).
         */
        /**
         * [block], or null when it throws an exception (a file that cannot be read starts that
         * part afresh). An Error is thrown on: running out of memory must not read as an empty
         * file, which the next save would write over the league.
         */
        private inline fun <T> readOrNull(block: () -> T): T? = try {
            block()
        } catch (e: Exception) {
            null
        }

        fun decode(texts: Map<String, String?>, keys: List<String>, reserved: Collection<String>): LeagueWorld {
            // An empty file stands for a part not made yet (see encodeFiles).
            val files = texts.mapValues { (_, t) -> t?.takeIf { it.isNotBlank() } }
            val ladder = files[LADDER]?.let { t -> readOrNull { Ladder.decode(keys, t) } } ?: Ladder(keys)
            val w = LeagueWorld(ladder)
            val seasonText = files[SEASON]
            w.plan = seasonText?.let { t -> readOrNull { SeasonPlan.decode(t, keys, CODECS) } }
            w.honours = files[HONOURS]?.let { t -> readOrNull { HonourBook.decode(t) } } ?: HonourBook()
            if (w.plan == null && seasonText != null) w.honours.add(HonourBook.legacy(seasonText))
            w.players = files[ROSTER]?.let { t -> readOrNull { PlayerBook.decode(t, reserved) } } ?: PlayerBook(reserved)
            w.clubs = files[CLUBS]?.let { t -> readOrNull { ClubBook.decode(t) } }
            w.stats = files[STATS]?.let { t -> readOrNull { StatsBook.decode(t) } } ?: StatsBook()
            w.archive = files[ARCHIVE]?.let { t -> readOrNull { Archive.decode(t) } } ?: Archive()
            w.highlights = files[HIGHLIGHTS]?.let { t -> readOrNull { HighlightBook.decode(t) } } ?: HighlightBook()
            w.news = files[NEWS]?.let { t -> readOrNull { NewsBook.decode(t) } } ?: NewsBook()
            w.lab = files[LAB]?.let { t -> readOrNull { LabBook.decode(t) } } ?: LabBook()
            w.nations = files[NATIONS]?.let { t -> readOrNull { NationBook.decode(t) } } ?: NationBook()
            // A season in progress that the stats do not know (stats.txt is newer than the
            // season, or was lost): start ratings and rookies from now, so its awards are made.
            w.plan?.let { p ->
                // Records ahead of this league's seasons are another league's (older versions'
                // 리그 초기화 kept them): they would hand this one that league's awards. Closing
                // this very season again after a kill leaves them equal, not ahead.
                if (w.stats.ended > p.number || (w.archive.summaries.maxOfOrNull { it.season } ?: 0) > p.number) {
                    w.stats = StatsBook()
                    w.archive = Archive()
                }
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

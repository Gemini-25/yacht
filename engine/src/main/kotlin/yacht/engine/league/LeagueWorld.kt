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

    /**
     * The clubs: a legacy league's club league (`clubs 1`), or in national leagues every member's
     * club (`clubs 2`, [ClubBook.AFFILIATION]); null until the first season with clubs makes them.
     */
    var clubs: ClubBook? = null

    /**
     * 요트협회: the league coefficients, each national season's best-ranked leagues and the
     * proposals of its assemblies (league/federation.txt).
     */
    var federation = Federation()

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

    /**
     * 뉴스 데스크: the season's match news and competition rounds, written as each week's best and
     * as digests ([NewsDesk]); what it holds is saved with the news.
     */
    var desk = NewsDesk()

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

    /** Each AI's nationality (generated and evolved players; null for the others). */
    private fun nationOf(key: String): String? = players[key]?.identity?.nation

    /** The national clubs (null without them, or with a club league's book). */
    private val affiliation: ClubBook? get() = clubs?.takeIf { it.format == ClubBook.AFFILIATION }

    /**
     * What the off-season knows. A rollover after season [moved] also tells who moved abroad or
     * came home in its club off-season, and [ranking], the league ranking after it.
     */
    private fun context(historyBefore: Int, ranking: LeagueRanking? = null, moved: Int? = null): Offseason.Context {
        val book = affiliation
        return Offseason.Context(
            members, ::rating, family, ::hometown, config, factories(),
            honours.all.filter { it.season < historyBefore }, closingGone, ::nationOf,
            // A member plays in its club's nation (a member without one in its own).
            playNation = { k -> book?.playNation(k) ?: nationOf(k) },
            transfersIn = moved?.let { book?.moved(it, ClubMoveType.ABROAD) }.orEmpty(),
            returned = moved?.let { book?.moved(it, ClubMoveType.HOME) }.orEmpty(),
            ranking = ranking,
        )
    }

    /**
     * Every competition of a season: the cups and playoffs, a legacy season's club league (making
     * the clubs the first time) and a national season's 클럽 대항전.
     */
    private fun factories(): List<(SeasonStart) -> Competition?> = Competitions.factories() + ClubLeague.factory(
        { clubs },
        { s -> ClubBook.create(s.ranking, s.config.clubs, s.config.squad, s.seed, Hometowns.ALL).also { clubs = it } },
    ) + ClubCup.factory { affiliation } + LegendMatch.factory { players.legends }

    /**
     * The first season: divisions by rating; returns the draw news. It comes after the newest
     * season the league already knows (an imported history, a season file that could not be
     * read), so no kept season is written over and the books' per-season guards still run.
     * National leagues found their clubs first (every member in one club of its nation).
     */
    fun openFirstSeason(leagueSeed: Long): List<LeagueEvent> {
        val known = listOf(
            honours.seasons.maxOrNull() ?: 0, stats.ended, archive.summaries.maxOfOrNull { it.season } ?: 0,
            players.evolvedSeason, clubs?.season ?: 0,
        ).max()
        if (config.national) clubs = ClubBook.found(members, ::nationOf, ::rating, known, leagueSeed, config)
        val p = Offseason.first(leagueSeed, context(Int.MAX_VALUE), known + 1)
        plan = p
        desk.begin(p)
        val out = opened(p)
        rank(p)
        return out
    }

    /**
     * Sets national season [p]'s league ranking, the one it is played under ([SeasonPlan.ranking]:
     * the coefficients of the seasons before it, by strength in the first national season), and
     * records its best-ranked leagues ([Federation.putTop]) once. The strength reads the ratings
     * the season was drawn with ([drawn]), so a league read back after a restart, or a finished
     * season read back after a kill in its rollover's save, is ranked as it was.
     */
    private fun rank(p: SeasonPlan) {
        if (!p.league.leagues.national) return
        runCatching {
            p.ranking = Coefficients.ranking(federation, p.league.leagues, p.number - 1, strengths(p, drawn(p)), p.config)
            if (federation.top(p.number) == null) federation.putTop(p.number, NationalLeagues.bestRanked(p, Federation.TOP))
        }
    }

    /**
     * The ratings season [p] was drawn with: the 요트컵's seeds (every member, whole points in
     * season.txt), else the start ratings stats.txt keeps, else the ladder's (whole points). They
     * do not move during the season or its close, so the rankings [p]'s leagues get from them
     * read the same at the close, at the open and after a restart.
     */
    private fun drawn(p: SeasonPlan): (String) -> Double {
        val seeded = (p.competition(Comp.CUP) as? Cup)?.entries?.associate { it.key to if (it.rating.isNaN()) it.rating else Math.round(it.rating).toDouble() }
        val started = stats.season == p.number
        return { k -> seeded?.get(k) ?: if (started) stats.startRating(k) else ladder.rating(k).let { if (it.isNaN()) it else Math.rint(it) } }
    }

    /** Each league of [p]'s strength ([Coefficients.strength]: the average [rating] of its best 5). */
    private fun strengths(p: SeasonPlan, rating: (String) -> Double): (String) -> Double {
        val s = p.league
        val byId = s.leagues.list.associate { lg -> lg.id to Coefficients.strength(lg.divisions.flatMap { s.divisions[it] }, rating) }
        return { id -> byId[id] ?: Double.NaN }
    }

    /**
     * The league ranking after national season [p] (its coefficients stored) under [rules]; null
     * after a legacy season. Leagues level on coefficients go by their strength in the ratings
     * [p] was drawn with, as in [p]'s own ranking: so the ranking the close publishes is the one
     * the next season's places are drawn by (evolution moves the ratings in between), a league's
     * climb is counted from the ranking [p] was played under, and a replayed close ranks the same.
     */
    private fun rankingAfter(p: SeasonPlan, rules: LeagueConfig): LeagueRanking? =
        if (!p.league.leagues.national) null else runCatching { Coefficients.ranking(federation, p.league.leagues, p.number, strengths(p, drawn(p)), rules) }.getOrNull()

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
     * null when none is free now; none of a week after [lastWeek]. See [SeasonPlan.next].
     */
    fun next(busy: Set<String>, cupsInFlight: Int, cupCap: Int, cost: ((LeagueFixture) -> Double)? = null, lastWeek: Int = Int.MAX_VALUE): Fixture? {
        val p = plan ?: return null
        if (p.league.finished && !p.postStarted) p.startPost(Competitions.postFactories())
        return p.next(busy, cupsInFlight, cupCap, cost, teamsInFlight, lastWeek)
    }

    /** Team fixtures (club league, family cup) being played; set by the app around [next]. */
    var teamsInFlight = 0

    /** What [recordJob] did: the ladder's view of each rated board, and the news. */
    class Recorded(val matches: List<MatchRecord>, val events: List<LeagueEvent>)

    /**
     * Records a finished job: statistics first (they want the ratings from before the match),
     * the news desk's look at it (the same ratings), then every rated board on the ladder, then
     * the season (friendlies are only on the ladder). A job that moves the season into a new
     * week closes the desk's week before it ([NewsDesk.close]).
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
        out += counted + note(counted)
        plan?.let { p -> runCatching { desk.offer(r, p) { k -> ladder.rating(k) } } }
        val matches = ArrayList<MatchRecord>()
        if (f.rated) {
            for ((i, b) in r.boards.withIndex()) {
                if (b.games.isEmpty() || ladder.entry(b.a) == null || ladder.entry(b.b) == null) continue
                val label = if (f.kind == JobKind.TEAM) "${f.label} · ${i + 1}번 보드".removePrefix(" · ") else f.label
                // The fixture and board, so the feed line leads to its match (board 0 of a one-on-one job).
                matches += ladder.record(b.a, b.b, b.games, comp = f.comp, label = label, week = f.week, season = season, id = f.id, board = i)
            }
        }
        if (r.fixture.comp != Comp.FRIENDLY) {
            out += record(r)
            plan?.let { p ->
                runCatching {
                    highlights.offer(r, p.number, ::rating, ::derbyTown, version, Drama.titleRace(p.league, r.fixture)) { k -> players[k]?.spec }
                }
                if (r.fixture.comp == Comp.LEAGUE) {
                    // National leagues: the title races of the best-ranked leagues' 1부.
                    val races = if (p.league.leagues.national) {
                        runCatching { titleRace.check(p.league, NationalLeagues.bestRanked(p, RACE_LEAGUES)) }.getOrDefault(emptyList())
                    } else {
                        listOfNotNull(titleRace.check(p.league))
                    }
                    val e = races.map { it.at(week) }
                    out += e + note(e)
                }
            }
        }
        live.add(r, nowMs, season)
        plan?.let { p -> out += closeWeek(p) }
        return Recorded(matches, out)
    }

    /** Records a finished season fixture in the season alone; returns its news (the news desk's digests it finished included). */
    fun record(r: JobResult): List<LeagueEvent> {
        val out = ArrayList(plan?.record(r).orEmpty().map { it.at(r.fixture.week) })
        weekPick(r)?.let { out += it }
        return out + note(out, r.fixture)
    }

    /** The news desk's weeks before the one [p] is in, closed once: their news. */
    private fun closeWeek(p: SeasonPlan): List<LeagueEvent> {
        if (desk.season != p.number) return emptyList()
        val now = runCatching { p.week }.getOrNull() ?: return emptyList()
        if (now <= desk.week) return emptyList()
        val e = runCatching { desk.close(deskContext(p), now) }.getOrDefault(emptyList())
        told(e)
        return e
    }

    /** What the news desk reads at a week's close: an article's match is not written up again. */
    private fun deskContext(p: SeasonPlan): NewsDesk.Context {
        val linked by lazy(LazyThreadSafetyMode.NONE) { news.all.mapNotNullTo(HashSet()) { it.ref } }
        return NewsDesk.Context(p, stats, affiliation) { ref -> ref in linked }
    }

    /**
     * The last matchday of season [pickedSeason] whose 이 주의 선수 was told: a matchday is told
     * when its last fixture is recorded, once (a stale result recorded again tells nothing).
     */
    private var pickedDay = -1
    private var pickedSeason = 0

    /**
     * 이 주의 선수 when [r] finished its matchday: `player_of_week` [player, opponent] (week,
     * average, best, tier within its league, and in national leagues the league's id).
     */
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
        val leagues = league.leagues
        return LeagueEvent(
            "player_of_week", p.number, listOf(pick.key, pick.opponent),
            listOf((day + 1).toString(), String.format(java.util.Locale.ROOT, "%.1f", pick.average), pick.best.toString(), leagues.tier(pick.division).toString()) +
                listOfNotNull(leagues.of(pick.division).id.takeIf { leagues.national }),
            Comp.LEAGUE, day + 1,
        )
    }

    fun release(f: Fixture) {
        if (f.comp == Comp.LAB) lab.release(f) else plan?.release(f)
    }

    /**
     * What [key]'s record says of it to a 상대 맞춤 AI ([OppModel]; null: too few games, or it
     * plays like max expectation): this season's official games once there are enough (it may
     * have changed with its age), else every rated game of its career.
     */
    fun oppModel(tables: yacht.engine.Tables, key: String): yacht.engine.OppModel? {
        val s = stats.season(key)?.takeIf { it.games >= yacht.engine.OppModel.MIN_GAMES } ?: stats.career(key) ?: return null
        return yacht.engine.OppModel.of(tables, s.games, s.avgScore, s.scoreSd)
    }

    /** [key]'s age in [season] (alive, retired or a legend); null when it has none (a hand-made AI). */
    internal fun ageAt(key: String, season: Int): Int? {
        players[key]?.let { r -> return r.identity?.age(season, r.born) }
        val gone = players.retired.lastOrNull { it.key == key } ?: players.legend(key)?.record ?: return null
        return if (gone.debutAge > 0) gone.debutAge + season - gone.born else null
    }

    /**
     * What [closeSeason] did. [ruleChanges] are the 요트협회's proposals adopted at this close (the
     * app applies them to its rules, [Federation.applyTo], before the next season opens: they shape
     * the season after next); [ranking] is the league ranking after a national season
     * ([LeagueRanking.EMPTY] after a legacy one).
     */
    class Closing(
        val season: Int,
        val honours: List<Honour>,
        val events: List<LeagueEvent>,
        val rosterChanged: Boolean,
        val ruleChanges: List<Proposal> = emptyList(),
        val ranking: LeagueRanking = LeagueRanking.EMPTY,
    )

    /**
     * End of season, first step: the season's honours (division champions after the playoffs,
     * moves, awards, every competition's titles), then evolution (the lowest rated generated and
     * evolved players retire, children are born) and generated players up to the config. A
     * national season also stores its league coefficients, publishes the league ranking, and the
     * 요트협회 decides the proposals voted on during it. Idempotent by season.
     */
    fun closeSeason(): Closing {
        val p = plan ?: error("no season")
        // Every national step follows the closing season itself, never the rules (whose closing
        // copy is the old rules at a migration).
        val national = p.league.leagues.national
        // AIs deleted during the season (no title, award or move), decided once for both steps.
        // Evolution's retirees are no deletions (a replay after a kill finds them retired).
        val gone = p.league.standings.keys.filter { !present(it) && players.retiredRecord(it) == null }.toSet()
        closingGone = gone
        val moves = Offseason.moves(p, gone)
        val h = Offseason.honours(p, moves, gone)
        // The league coefficients of the season, and the ranking after it.
        if (national) runCatching { federation.putCoefficients(p.number, Coefficients.season(p)) }
        val ranking = rankingAfter(p, config) ?: LeagueRanking.EMPTY
        // The news desk's last week and whatever it still holds.
        val flushed = runCatching { desk.flush(deskContext(p)) }.getOrDefault(emptyList())
        told(flushed)
        // Awards, records, hall of fame, dynasties and the season's archive, from the book as it
        // was before this season; then the season's honours go in once, awards included. A
        // national season's lines carry each player's club, and its hall of fame counts the
        // titles of the leagues that were among the best-ranked.
        val book = affiliation.takeIf { national }
        val end = runCatching {
            // The ladder's own ratings: an AI off the ladder (retired by this close, read again
            // after a kill) has none, rather than a made-up starting one that would win awards.
            stats.endSeason(
                p, h, honours, archive, { k -> ladder.rating(k) }, family, name, null, ::traits, gone,
                club = { k -> book?.clubOf(k) }, major = federation::isMajor, highlights = highlights,
            )
        }.getOrNull()
        // 이 주의 선수 of every week, kept with the season's honours.
        val picks = runCatching { p.league.playersOfWeek(0 until p.league.matchdays, gone).values.sortedBy { it.matchday }.map { Honour(p.number, "POTW", it.key) } }.getOrDefault(emptyList())
        honours.add(h + end?.awards.orEmpty() + picks)
        val out = ArrayList<LeagueEvent>()
        // A national season's awards are the 요트협회's (its MVP is 올해의 선수).
        end?.let { e -> out += if (national) e.events.map { if (it.kind == "award") it.copy(args = it.args + News.NATIONAL) else it } else e.events }
        out += runCatching { stats.honourRecords(honours, p.number, ::ageAt) }.getOrDefault(emptyList())
        out += if (national) runCatching { nationalTitles(p, h, moves) }.getOrDefault(emptyList()) else legacyTitles(p, h)
        // The 요트협회: the ranking it publishes, and the proposals voted on during the season
        // (P(n - 1)) decided: the user's choice, else the 선수 위원회. Nothing is pending at a
        // migration's close; the call keeps the season guard.
        val adopted = runCatching {
            val committee = if (national) Federation.committee(p, ranking) { k -> ladder.rating(k) } else emptyList()
            federation.resolve(p.number, p.seed, committee, config.federation)
        }.getOrDefault(emptyList())
        if (national) {
            leagueRankingEvent(p.number, ranking)?.let { out += it }
            // Void ones only when the federation was off: the others' voids are told at the open.
            for (x in federation.of(p.number - 1)) if (!x.pending && (x.result != Proposal.VOID || x.by == Proposal.NONE)) out += decision(p.number, x)
            // The season's review learns the ranking's moves and the 요트협회's decisions.
            runCatching { archive.amendReview(p.number, ranking = reviewRanking(ranking), federation = reviewDecisions(p.number)) }
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
        // Titles that count for a career: in national leagues the major ones (the cups and the
        // best-ranked leagues' titles), counted in one pass over the book.
        val major = if (national) honours.all.filter { HonourBook.isMajor(it, federation::isMajor) }.groupingBy { it.key }.eachCount() else null
        val titles: (String) -> Int = if (major != null) { k -> major[k] ?: 0 } else honours::titles
        // The hand-made AIs' last season when the league is for generated players only: they
        // leave it as its founders (their records stay; the app leaves them out of the members).
        if (!config.baseAis) {
            val founders = p.league.standings.keys.filter { Origin.of(it) == Origin.BASE && present(it) }
            if (founders.isNotEmpty()) {
                val named = founders.sortedWith(compareByDescending<String> { titles(it) }.thenByDescending { ladder.rating(it) }.thenBy { it }).take(3)
                out += LeagueEvent("founders_farewell", p.number, named, listOf(founders.size.toString()))
            }
        }
        // Players age, retire and grow (with aging on), and new ones bring the book back to the
        // configured size. Players who sat the season out (a preset left them out) sit in the
        // middle of the ratings; the chaos preset brings in only flawed ones (others would never
        // play). Played: the share of the most league matches anyone played; in national leagues
        // the share of the player's own league fixtures (leagues play 30 to 42 rounds).
        val standings = p.league.standings
        val most = standings.values.maxOfOrNull { it.played }?.coerceAtLeast(1) ?: 1
        val played: (String) -> Double = if (national) {
            { k -> (standings[k]?.played ?: 0).toDouble() / p.league.fixturesOf(k).coerceAtLeast(1) }
        } else {
            { k -> (standings[k]?.played ?: 0).toDouble() / most }
        }
        val r = Evolution.run(
            players, p.number, { k -> if (k in standings) ladder.rating(k) else Double.NaN }, titles, p.seed, config.generated,
            { honours.of(it) }, childShare = config.childShare / 100.0, played = played,
            accept = { s -> !config.onlyFlawed || s.flawed }, fashion = fashion, aging = config.aging,
        )
        // A national retiree's article counts every title it won (the major ones only decide who
        // is kept as a legend): a champion of a league outside the best-ranked did win one.
        out += if (national) r.events.map { e -> if (e.kind == "retired" && e.args.size > 1) e.copy(args = e.args.toMutableList().also { it[1] = honours.titles(e.keys.first()).toString() }) else e } else r.events
        // Everyone a year older plays a little differently: their old results count half from
        // now on, so ratings follow who they are (careers and head-to-heads stay).
        if (config.aging) runCatching {
            // Not those forever in their prime: they play as they did.
            ladder.decay(players.alive.filter { it.born <= p.number && !it.keep }.map { it.key }.toSet(), RATING_FADE, p.number)
            ladder.fit()
        }
        val changed = r.born.isNotEmpty() || r.retired.isNotEmpty() || players.reformed == p.number || config.aging
        val closed = (if (national) digested(p.number, out) else out).map { it.at(SeasonCalendar.CLOSING) }
        note(closed)
        return Closing(p.number, h + end?.awards.orEmpty() + picks, flushed + closed, changed, adopted, ranking)
    }

    /** The league ranking's biggest moves for the season's review: up to [REVIEW_MOVES] climbers, then as many fallers (league id, places). */
    private fun reviewRanking(ranking: LeagueRanking): List<Pair<String, Int>> {
        val rows = ranking.rows.filter { it.change != 0 }
        val up = rows.filter { it.change > 0 }.sortedWith(compareByDescending<LeagueRank> { it.change }.thenBy { it.rank }).take(REVIEW_MOVES)
        val down = rows.filter { it.change < 0 }.sortedWith(compareBy<LeagueRank> { it.change }.thenBy { it.rank }).take(REVIEW_MOVES)
        return (up + down).map { it.id to it.change }
    }

    /** The 요트협회's decisions on season [season]'s vote for its review: "‘자동 승강 인원 2명 → 1명’ 가결". */
    private fun reviewDecisions(season: Int): List<String> = federation.of(season - 1).filter { !it.pending }.map { x ->
        "‘${x.title}’ " + when (x.result) {
            Proposal.YES -> "가결"
            Proposal.NO -> "부결"
            else -> "무효"
        }
    }

    /**
     * A national close's articles that many leagues multiply, told at the source as digests: of
     * the title milestones (most players win their first title in one of dozens of lower tiers),
     * the runs and unbeaten seasons in titles that are not major (lower tiers, leagues outside
     * the best-ranked), the pupils' debuts and the rules learned, the [SINGLES] best-rated stay
     * as they are and the rest make one article each: `milestones` [≤ 6] (count, first titles),
     * `dynasties` [≤ 6] (count), `pupils` [≤ 6] (count), `learned_rules` [≤ 6] (count).
     */
    private fun digested(season: Int, events: List<LeagueEvent>): List<LeagueEvent> {
        fun titleMilestone(e: LeagueEvent) = e.kind == "milestone" && e.args.firstOrNull().let { it == "title" || it == "titles" }
        fun minorRun(e: LeagueEvent): Boolean {
            if (e.kind != "dynasty" || e.args.firstOrNull() == Awards.DOUBLE || e.args.firstOrNull() == Awards.TREBLE) return false
            val title = e.args.getOrNull(2) ?: return false
            val league = HonourKind.leagueOf(title) ?: return false
            return !HonourKind.isTopTitle(title) || !federation.isMajor(season, league)
        }
        val groups = listOf(
            "milestones" to ::titleMilestone,
            "dynasties" to ::minorRun,
            "pupils" to { e: LeagueEvent -> e.kind == "born" },
            "learned_rules" to { e: LeagueEvent -> e.kind == "learned_rule" },
        )
        val best = compareByDescending<LeagueEvent> { e -> e.keys.firstOrNull()?.let { ladder.rating(it) }?.takeIf { !it.isNaN() } ?: 0.0 }.thenBy { it.keys.firstOrNull() }
        val dropped = HashSet<LeagueEvent>()
        val digests = ArrayList<LeagueEvent>()
        for ((kind, test) in groups) {
            val all = events.filter(test)
            if (all.size <= SINGLES + 1) continue
            val rest = all.sortedWith(best).drop(SINGLES)
            dropped += rest
            val args = listOf(rest.size.toString()) + if (kind == "milestones") listOf(rest.count { it.args.getOrNull(1) == "1" }.toString()) else emptyList()
            digests += LeagueEvent(kind, season, rest.mapNotNull { it.keys.firstOrNull() }.distinct().take(DIGEST_NAMES), args)
        }
        if (dropped.isEmpty()) return events
        return events.filter { it !in dropped } + digests
    }

    /**
     * A legacy season's titles and moves that the competitions did not announce themselves, and
     * the season review. The title playoff announces its champion and the promotion playoff its
     * moves (read from the season, as the events are not saved); a pyramid's lower tiers (4부
     * down, and the moves below 3부) make one article: dozens of group titles and hundreds of
     * moves would flood the news.
     */
    private fun legacyTitles(p: SeasonPlan, h: List<Honour>): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        val champion = h.firstOrNull { it.kind == "L0" }?.key ?: "-"
        val cup = h.firstOrNull { it.kind == "CUP" }?.key ?: "-"
        out += LeagueEvent("season_review", p.number, listOf(champion, cup))
        val playoffChampion = p.competition(Comp.TITLE_PLAYOFF)?.honours()?.firstOrNull { it.kind == "L0" }?.key
        val promo = p.competition(Comp.PROMOTION_PLAYOFF) as? PromotionPlayoff
        val promoUp = promo?.honours()?.filter { it.kind.startsWith("UP") }?.map { it.key }.orEmpty()
        fun announced(kind: String, key: String) = when (kind) {
            "title" -> key == playoffChampion
            "promoted" -> key in promoUp
            else -> promo?.spots?.any { it.upper == key && it.lower in promoUp } == true
        }
        // The one league of a legacy season.
        val tiers = p.league.leagues.list.first().tiers
        val grouped = !tiers.linear
        val lowerChampions = ArrayList<String>()
        var lowerUp = 0
        for (x in h) {
            val e = when {
                // A pyramid's group title names its group ("2부 A조").
                DIVISION.matches(x.kind) -> LeagueEvent(
                    "title", p.number, listOf(x.key),
                    listOf(x.kind.removePrefix("L")) + listOfNotNull(p.league.divisionOf(x.key)?.let { tiers.groupLabel(it) }?.takeIf { it.isNotEmpty() }),
                    Comp.LEAGUE,
                )
                x.kind.startsWith("UP") -> LeagueEvent("promoted", p.number, listOf(x.key), listOf(x.kind.removePrefix("UP")))
                x.kind.startsWith("DOWN") -> LeagueEvent("relegated", p.number, listOf(x.key), listOf(x.kind.removePrefix("DOWN")))
                else -> null
            } ?: continue
            if (grouped) {
                val t = e.args.first().toIntOrNull() ?: 0
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
                listOf(lowerChampions.size.toString(), lowerUp.toString(), (NEWS_TIERS + 1).toString(), (tiers.count - 1).toString()), Comp.LEAGUE,
            )
        }
        return out
    }

    /**
     * A national season's league news at the close (design ENG 1.12), digests at the source:
     * `league_title` [champion] (league, "0", "po" when its title playoff decided it else "",
     * rank) for the [LEAGUE_TITLES] best-ranked leagues and one `league_titles_digest` [≤
     * [DIGEST_CHAMPIONS] champions] (count, then "league:key" each) for the others, best-ranked
     * first; one `lower_tiers` [3 best-rated lower-tier champions] (count, promotions, first
     * tier, last tier, "national"); `league_moves` [promoted…, relegated…] (league, ups, downs) at
     * the 1부 of the [MOVE_LEAGUES] best-ranked; the season review (with the best-ranked league's
     * champion).
     */
    private fun nationalTitles(p: SeasonPlan, h: List<Honour>, moves: List<Offseason.Move>): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        val n = p.number
        val leagues = p.league.leagues
        val champions = h.filter { HonourKind.isTopTitle(it.kind) }.associate { (HonourKind.leagueOf(it.kind) ?: "") to it.key }
        val order = Coefficients.order(leagues.list.map { it.id }, p.ranking, strengths(p, ::rating))
        val best = NationalLeagues.bestRanked(p, LEAGUE_TITLES)
        for ((i, id) in best.withIndex()) {
            val k = champions[id] ?: continue
            val po = if (h.any { it.kind == HonourKind.of("L0R", id) }) "po" else ""
            out += LeagueEvent("league_title", n, listOf(k), listOf(id, "0", po, i.toString()), Comp.LEAGUE)
        }
        val rest = order.filter { it !in best }.mapNotNull { id -> champions[id]?.let { id to it } }
        if (rest.isNotEmpty()) {
            val named = rest.take(DIGEST_CHAMPIONS)
            out += LeagueEvent("league_titles_digest", n, named.map { it.second }, listOf(rest.size.toString()) + named.map { "${it.first}:${it.second}" }, Comp.LEAGUE)
        }
        val lower = h.filter { (HonourKind.tier(it.kind) ?: 0) >= 1 && HonourKind.leagueOf(it.kind) != null }.map { it.key }.distinct()
        if (lower.isNotEmpty()) {
            val named = lower.sortedWith(compareByDescending<String> { ladder.rating(it).takeIf { r -> !r.isNaN() } ?: 0.0 }.thenBy { it }).take(3)
            val deepest = leagues.list.maxOf { it.tiers.count } - 1
            out += LeagueEvent(
                "lower_tiers", n, named, listOf(lower.size.toString(), moves.count { it.to < it.from }.toString(), "1", deepest.toString(), News.NATIONAL), Comp.LEAGUE,
            )
        }
        for (id in NationalLeagues.bestRanked(p, MOVE_LEAGUES)) {
            val ups = moves.filter { it.league == id && it.from == 1 && it.to == 0 }.map { it.key }.distinct()
            val downs = moves.filter { it.league == id && it.from == 0 && it.to == 1 }.map { it.key }.distinct()
            if (ups.isEmpty() && downs.isEmpty()) continue
            out += LeagueEvent("league_moves", n, ups + downs, listOf(id, ups.size.toString(), downs.size.toString()), Comp.LEAGUE)
        }
        val top = order.firstOrNull()
        val cup = h.firstOrNull { it.kind == "CUP" }?.key ?: "-"
        out += LeagueEvent("season_review", n, listOf(top?.let { champions[it] } ?: "-", cup), listOfNotNull(top))
        return out
    }

    /** `league_ranking` [] (the 3 best leagues, "-" for missing ones, the biggest climber or "-", its climb) after a national season; null without rows. */
    private fun leagueRankingEvent(season: Int, ranking: LeagueRanking): LeagueEvent? {
        val rows = ranking.rows.sortedBy { it.rank }
        if (rows.isEmpty()) return null
        val climber = rows.filter { it.change > 0 }.maxWithOrNull(compareBy<LeagueRank> { it.change }.thenByDescending { it.rank })
        val top = rows.take(3).map { it.id }
        return LeagueEvent("league_ranking", season, emptyList(), top + List(3 - top.size) { "-" } + listOf(climber?.id ?: "-", (climber?.change ?: 0).toString()))
    }

    /**
     * `federation_decision` [] (title, result, by, yes, no, the season it applies from) of
     * proposal [x], told in season [season]: result 'A' 가결, 'R' 부결, 'V' 무효; by 'U' the
     * user, 'C' the 선수 위원회, '-' none (the federation was off).
     */
    private fun decision(season: Int, x: Proposal): LeagueEvent = LeagueEvent(
        "federation_decision", season, emptyList(),
        listOf(x.title, x.result.toString(), x.by.toString(), x.yes.toString(), x.no.toString(), x.appliesFrom.toString()),
    )

    /**
     * End of season, second step (after the app set [members] from its rebuilt roster): the
     * clubs' off-season, then the next season. Returns the news of both. Before national leagues
     * (the closing season and the rules to come both legacy): the club league's off-season (prize
     * money, retired members out, transfer window, draft). Into national leagues: the national
     * one (club tables, returns and transfers abroad, the domestic window, the draft; a club
     * league's book is converted at the first, a missing book founded). Then the next season
     * under the ranking after this one, the next season's own ranking and best-ranked leagues,
     * and the 요트협회's assembly on this season's statistics against the rules the next season
     * really uses.
     */
    fun openSeason(): List<LeagueEvent> {
        val p = plan ?: error("no season")
        // Offseason.next makes national leagues after a national season or when the rules ask.
        val national = p.league.leagues.national || config.national
        val ranking = rankingAfter(p, config)
        val market = if (national) {
            val book = clubs
            if (book == null) {
                // No book (a league without the club league, a lost file): founded now, with this off-season done.
                clubs = ClubBook.found(members, ::nationOf, ::rating, p.number, p.seed, config)
                emptyList()
            } else {
                runCatching { book.offseason(p, clubInput(p, ranking ?: LeagueRanking.EMPTY)) }.getOrDefault(emptyList())
            }
        } else {
            clubs?.let { c -> runCatching { c.offseason(p, ::rating, members.toSet(), config) }.getOrDefault(emptyList()) }.orEmpty()
        }.let { e -> if (national) starred(p, e) else e }.map { it.at(SeasonCalendar.CLOSING) }
        note(market)
        val hadClubs = clubs != null
        val (next, _) = Offseason.next(p, context(p.number, ranking, p.number))
        // Clubs drafted just now (switched on during the season) had this off-season already.
        if (!hadClubs) clubs?.founded(p.number, p.seed)
        val news = ArrayList<LeagueEvent>()
        if (next.league.leagues.national) news += formation(p, next)
        news += runCatching { assembly(p) }.getOrDefault(emptyList())
        // The closing season's review learns its transfers abroad, and the decisions as they stand after the voids.
        if (p.league.leagues.national) runCatching {
            archive.amendReview(p.number, federation = reviewDecisions(p.number), transfers = affiliation?.moved(p.number, ClubMoveType.ABROAD)?.size ?: 0)
        }
        val preseason = news.map { it.at(SeasonCalendar.PRESEASON) }
        note(preseason)
        closingGone = emptySet()
        plan = next
        desk.begin(next)
        val opening = opened(next)
        rank(next)
        return market + preseason + opening
    }

    /**
     * [events] of the club off-season after [closing], with the transfers abroad of a league
     * champion or award winner of [closing] marked "star" (bigger news).
     */
    private fun starred(closing: SeasonPlan, events: List<LeagueEvent>): List<LeagueEvent> {
        if (events.none { it.kind == "transfer_abroad" }) return events
        val stars = honours.of(closing.number).filter { h ->
            HonourKind.isTopTitle(h.kind) && HonourKind.leagueOf(h.kind) != null || HonourKind.base(h.kind) == Awards.LMVP || h.kind == "MVP"
        }.mapTo(HashSet()) { it.key }
        return events.map { if (it.kind == "transfer_abroad" && it.keys.firstOrNull() in stars) it.copy(args = it.args + News.STAR) else it }
    }

    /** What the national club off-season after [closing] knows: the members, their nations, ages next season and mentors, the honours. */
    private fun clubInput(closing: SeasonPlan, ranking: LeagueRanking): ClubInput = ClubInput(
        members, ::rating, ::nationOf, config, honours.all,
        age = { k -> players[k]?.let { r -> r.identity?.age(closing.number + 1, r.born) } },
        origin = { k -> players[k]?.origin ?: Origin.of(k) },
        mentor = { k -> players[k]?.parentA },
        ranking = ranking, major = federation::isMajor,
    )

    /**
     * The leagues of national season [next] in the news: `national_reform` [] (national leagues,
     * regional leagues, then every id) for the first after a legacy season; later
     * `league_formed` [] (league, tiers, members) for each league that is new.
     */
    private fun formation(closing: SeasonPlan, next: SeasonPlan): List<LeagueEvent> {
        val n = next.number
        val list = next.league.leagues.list
        if (!closing.league.leagues.national) {
            val own = list.count { Nations.of(it.id) != null }
            return listOf(LeagueEvent("national_reform", n, emptyList(), listOf(own.toString(), (list.size - own).toString()) + list.map { it.id }))
        }
        val before = closing.league.leagues.list.map { it.id }.toSet()
        return list.filter { it.id !in before }.map { lg ->
            LeagueEvent("league_formed", n, emptyList(), listOf(lg.id, lg.tiers.count.toString(), lg.divisions.sumOf { next.league.divisions[it].size }.toString()))
        }
    }

    /**
     * The 요트협회 at the open after season [closing]: an adopted proposal whose rule the season to
     * come does not have was not applied (its rule had been changed by hand) and is void; then
     * the assembly on [closing]'s statistics against the rules to come (the first after a legacy
     * season has only the structure-free kinds). Held at every open: a legacy league's keeps the
     * statistics and tables nothing. News: `federation_decision` for those void ones and, with
     * national leagues to come and the federation on, `federation_assembly` [] (count, the season
     * voting, the season they apply from, then each proposal's title).
     */
    private fun assembly(closing: SeasonPlan): List<LeagueEvent> {
        val n = closing.number
        val out = ArrayList<LeagueEvent>()
        federation.markVoid(federation.of(n - 1).filter { it.adopted && config.valueOf(it.lever.key) != it.to })
        for (x in federation.of(n - 1)) if (x.result == Proposal.VOID && x.by != Proposal.NONE) out += decision(n + 1, x)
        val stats = FederationStats.of(closing, honours.all, federation, config, closingGone)
        val tabled = federation.assemble(n, stats, config, structureFreeOnly = !closing.league.leagues.national)
        if (config.federation && config.national) {
            out += LeagueEvent("federation_assembly", n + 1, emptyList(), listOf(tabled.size.toString(), (n + 1).toString(), (n + 2).toString()) + tabled.map { it.title })
        }
        return out
    }

    /**
     * Keeps the events and writes them up as news articles, but those the news desk holds for its
     * digests ([NewsDesk.sift]; [f] is the fixture they come from). Returns the desk's own news it
     * wrote with them (the digest of a big knockout stage that [f] finished).
     */
    private fun note(e: List<LeagueEvent>, f: Fixture? = null): List<LeagueEvent> {
        if (e.isEmpty() && f == null) return emptyList()
        keep(e)
        val shown = runCatching { desk.sift(e, plan, f) }.getOrDefault(e)
        val made = shown.filter { it.kind in NewsDesk.KINDS && it !in e }
        keep(made)
        write(shown)
        return made
    }

    /**
     * Writes up events the news desk made or let go ([note] without the desk), keeping its own:
     * those it held were kept when they came.
     */
    private fun told(e: List<LeagueEvent>) {
        keep(e.filter { it.kind in NewsDesk.KINDS })
        write(e)
    }

    private fun keep(e: List<LeagueEvent>) {
        for (x in e) events.addLast(x)
        while (events.size > EVENTS) events.removeFirst()
    }

    /** The articles of [e] (a club's id by its name, dissolved clubs too), each with its match and scope. */
    private fun write(e: List<LeagueEvent>) {
        if (e.isEmpty()) return
        runCatching {
            news.add(News.articles(e, { k -> Nations.flagged(k) ?: name(k) }, { id -> clubs?.nameOf(id) ?: id }, Competitions::teamLabel, { k -> ladder.rating(k) }, ::scopeOf))
        }
    }

    /**
     * Where [e] happened ([Article.scope]): a national league's id for its league news (its
     * titles, moves, races, upsets, a player's move into it; a 1부 or playoff match by its first
     * player's league), a confederation's name for its 대륙 챔피언스, "" for the rest and for a
     * legacy season.
     */
    private fun scopeOf(e: LeagueEvent): String {
        val p = plan ?: return ""
        val leagues = p.league.leagues
        fun id(i: Int): String = e.args.getOrNull(i)?.takeIf { it != LeagueIds.LEGACY && it != "-" && LeagueIds.known(it) }.orEmpty()
        val byArg = when (e.kind) {
            "league_title", "league_moves", "league_formed", "club_title", "club_race" -> id(0)
            "league_upset", "transfer_abroad", "return_home" -> id(1)
            "title_race" -> id(2)
            // A knockout's by its competition (a 대륙 챔피언스 tie by the winner's confederation).
            "comeback" -> when (e.comp) {
                Comp.LEAGUE -> id(3)
                Comp.CONT_CHAMPIONS -> e.keys.firstOrNull()?.let { p.league.leagueOf(it)?.id }?.let { leagues.confedOf(it)?.name }.orEmpty()
                else -> null
            }
            "player_of_week" -> id(4)
            else -> null
        }
        if (byArg != null) return byArg
        if (!leagues.national) return ""
        return when (e.comp) {
            Comp.CONT_CHAMPIONS -> e.args.lastOrNull()?.takeIf { it.startsWith("@") }?.drop(1)
                ?.let { t -> Confed.entries.firstOrNull { ContinentalChampions.title(it) == t }?.name }.orEmpty()
            Comp.LEAGUE, Comp.TITLE_PLAYOFF, Comp.PROMOTION_PLAYOFF -> e.keys.firstOrNull()?.let { p.league.leagueOf(it)?.id }.orEmpty()
            else -> ""
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
     * clubs come after the roster they were drafted from, the 요트협회 after the clubs. No season
     * or clubs: empty files, so an older one is not read.
     */
    fun encodeFiles(): Map<String, String> = buildMap {
        put(HONOURS, honours.encode())
        put(ARCHIVE, archive.encode())
        put(STATS, stats.encode())
        put(ROSTER, players.encode())
        put(CLUBS, clubs?.encode() ?: "")
        put(FEDERATION, federation.encode())
        put(HIGHLIGHTS, highlights.encode())
        // The news desk's pending lines after the articles (older versions skip them).
        put(NEWS, news.encode() + desk.encode())
        put(LAB, lab.encode())
        put(NATIONS, nations.encode())
        put(LADDER, ladder.encode())
        put(SEASON, plan?.encode() ?: "")
    }

    companion object {
        /** A pyramid's tiers whose group titles make news of their own (0-based: 1부 to 3부). */
        const val NEWS_TIERS = 2

        /**
         * National leagues: the best-ranked leagues whose titles are told one by one, the champions
         * the digest names, the best-ranked leagues whose 1부 moves are told and whose title races
         * are watched.
         */
        const val LEAGUE_TITLES = 8
        const val DIGEST_CHAMPIONS = 20
        const val MOVE_LEAGUES = 4
        const val RACE_LEAGUES = 4

        /** A national close's digested kinds: articles kept as they are, and players a digest names. */
        const val SINGLES = 2
        const val DIGEST_NAMES = 6

        /** The league ranking's climbers (and fallers) a season's review keeps. */
        const val REVIEW_MOVES = 5

        const val LADDER = "ladder.txt"
        const val SEASON = "season.txt"
        const val HONOURS = "honours.txt"
        const val ROSTER = "roster.txt"
        const val CLUBS = "clubs.txt"
        const val FEDERATION = "federation.txt"
        const val STATS = "stats.txt"
        const val ARCHIVE = "archive.txt"
        const val HIGHLIGHTS = HighlightBook.FILE
        const val NEWS = NewsBook.FILE
        const val LAB = LabBook.FILE
        const val NATIONS = NationBook.FILE

        /** What the results of a player who aged still weigh in the ratings after a season. */
        const val RATING_FADE = 0.5

        /** Files [encodeFiles] writes. */
        val FILES = listOf(LADDER, SEASON, HONOURS, ROSTER, CLUBS, FEDERATION, STATS, ARCHIVE, HIGHLIGHTS, NEWS, LAB, NATIONS)

        /** Readers of every competition in season.txt. */
        val CODECS: Map<Comp, CompetitionCodec> = Competitions.codecs + (Comp.CLUB to ClubLeague.codec) + (Comp.LEGEND to LegendMatch.codec)

        const val EVENTS = 300

        /** Seeding rating before an AI's first game (a solid amateur). */
        const val START_RATING = 1400.0

        /** [prior]: rating points per point of overall ability from [PRIOR_MID]. */
        const val PRIOR_PER_POINT = 5.0
        const val PRIOR_MID = 50

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

        /**
         * Reads the saved league under [config] (the app's rules). [keys] are the ladder's keys
         * (every AI and me); an unusable file starts that part fresh. A season file of the first
         * version moves its history into the honours (the next start begins a season of the new
         * kind). A national season gets back the league ranking it is played under.
         */
        fun decode(texts: Map<String, String?>, keys: List<String>, reserved: Collection<String>, config: LeagueConfig = LeagueConfig()): LeagueWorld {
            // An empty file stands for a part not made yet (see encodeFiles).
            val files = texts.mapValues { (_, t) -> t?.takeIf { it.isNotBlank() } }
            val ladder = files[LADDER]?.let { t -> readOrNull { Ladder.decode(keys, t) } } ?: Ladder(keys)
            val w = LeagueWorld(ladder, config)
            val seasonText = files[SEASON]
            w.plan = seasonText?.let { t -> readOrNull { SeasonPlan.decode(t, keys, CODECS) } }
            w.honours = files[HONOURS]?.let { t -> readOrNull { HonourBook.decode(t) } } ?: HonourBook()
            if (w.plan == null && seasonText != null) w.honours.add(HonourBook.legacy(seasonText))
            w.players = files[ROSTER]?.let { t -> readOrNull { PlayerBook.decode(t, reserved) } } ?: PlayerBook(reserved)
            w.clubs = files[CLUBS]?.let { t -> readOrNull { ClubBook.decode(t) } }
            w.federation = files[FEDERATION]?.let { t -> readOrNull { Federation.decode(t) } } ?: Federation()
            w.stats = files[STATS]?.let { t -> readOrNull { StatsBook.decode(t) } } ?: StatsBook()
            w.archive = files[ARCHIVE]?.let { t -> readOrNull { Archive.decode(t) } } ?: Archive()
            w.highlights = files[HIGHLIGHTS]?.let { t -> readOrNull { HighlightBook.decode(t) } } ?: HighlightBook()
            w.news = files[NEWS]?.let { t -> readOrNull { NewsBook.decode(t) } } ?: NewsBook()
            w.desk = files[NEWS]?.let { t -> readOrNull { NewsDesk.decode(t) } } ?: NewsDesk()
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
                w.rank(p)
                // A desk of another season (or none): it starts this season's weeks afresh.
                if (w.desk.season != p.number) w.desk.begin(p)
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

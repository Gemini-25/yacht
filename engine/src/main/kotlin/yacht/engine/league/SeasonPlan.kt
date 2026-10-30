package yacht.engine.league

/**
 * A stage of the season on its week, for the screen: [played] of [total] fixtures (0 of 0 for a
 * playoff not [set] up yet); [final] for the last stage of a cup, the champions league, an event
 * or the title playoff.
 */
data class WeekStage(
    val comp: Comp,
    val stage: Int,
    val label: String,
    val week: Int,
    val played: Int,
    val total: Int,
    val final: Boolean,
    val set: Boolean = true,
) {
    val done: Boolean get() = set && played >= total
}

/**
 * Everything a season plays: the league stage ([league]) and the other competitions ([comps]),
 * then the post-season ones ([post], e.g. playoffs) once the league is over. Hands the workers
 * their next fixture and takes the results. See docs/LEAGUE_SPEC.md section 2.4.
 * Not thread-safe: callers synchronise.
 */
class SeasonPlan(
    val league: Season,
    val config: LeagueConfig,
    val comps: MutableList<Competition> = ArrayList(),
    val post: MutableList<Competition> = ArrayList(),
) {
    val number: Int get() = league.number
    val seed: Long get() = league.seed

    init {
        // The league's matches are decided by the rules the season began with.
        league.format = config.matchFormat
        league.bonus = config.sweepPoints
    }

    /**
     * The league ranking this season is played under (not saved: the world sets it when the season
     * opens or is read). With [LeagueRanking.EMPTY] no league counts as one of the best-ranked.
     */
    var ranking: LeagueRanking = LeagueRanking.EMPTY

    /** League progress, 0..1: competitions open their rounds by it. */
    val progress: Double get() = league.progress

    /** True once the league and every competition are over (post-season included). */
    val finished: Boolean
        get() = league.finished && postStarted && comps.all { it.finished } && post.all { it.finished }

    /** True once the post-season has been set up (it may be empty). */
    var postStarted = false

    /**
     * The rules this season is closed with, set by the app before it saves the finished season:
     * a close replayed after a kill uses them again, whatever the rules are by then.
     */
    var closing: LeagueConfig? = null

    /** Sets up the post-season (playoffs) from the final tables, once the league is over. */
    fun startPost(factories: List<(SeasonPlan) -> Competition?>) {
        if (postStarted || !league.finished) return
        postStarted = true
        post += factories.mapNotNull { f -> runCatching { f(this) }.getOrNull() }
    }

    val fixturesTotal: Int get() = league.fixturesTotal + comps.sumOf { it.fixturesTotal } + post.sumOf { it.fixturesTotal }
    val fixturesLeft: Int get() = league.fixturesLeft + comps.sumOf { it.fixturesLeft } + post.sumOf { it.fixturesLeft }

    fun competition(comp: Comp): Competition? = comps.firstOrNull { it.comp == comp } ?: post.firstOrNull { it.comp == comp }

    // ---- the calendar ---------------------------------------------------------------------------

    /** Weeks of the regular season: the league's rounds. */
    val leagueWeeks: Int by lazy(LazyThreadSafetyMode.NONE) { maxOf(1, league.matchdays) }

    private var built: SeasonCalendar? = null
    private var builtFor = -1

    /**
     * The season's weeks ([SeasonCalendar]) for the competitions set up so far; the post-season's
     * from the rules until it is set up. Rebuilt when a competition is added.
     */
    val calendar: SeasonCalendar
        get() {
            val key = comps.size * 10_000 + post.size * 10 + (if (postStarted) 1 else 0)
            built?.let { if (builtFor == key) return it }
            val post = if (postStarted) post.map { it.comp to runCatching { it.stages() }.getOrDefault(emptyList()) } else expectedPost()
            return SeasonCalendar.build(leagueWeeks, comps, post).also {
                built = it
                builtFor = key
                openWeek = Int.MIN_VALUE
            }
        }

    /** The playoffs the rules call for, before the league is over (the calendar shows them ahead). */
    private fun expectedPost(): List<Pair<Comp, List<Stage>>> {
        val out = ArrayList<Pair<Comp, List<Stage>>>()
        if (config.titlePlayoff && league.divisions.firstOrNull().orEmpty().size >= 4) {
            out += Comp.TITLE_PLAYOFF to Knockout.stageLabels(2, config.doubleElimination) { if (it == 0) "4강" else "결승" }.map { Stage(it, 0.0) }
        }
        if (config.promotionPlayoff && league.divisions.size >= 2) out += Comp.PROMOTION_PLAYOFF to listOf(Stage(PromotionPlayoff.STAGE, 0.0))
        return out
    }

    /**
     * The week the season is in: the league round in play (the lowest with a fixture left), then
     * the first post-season week with a playoff tie left; the last week once all is played.
     */
    val week: Int
        get() {
            if (!league.finished) return league.currentMatchday + 1
            val cal = calendar
            if (!postStarted) return minOf(cal.lastWeek, leagueWeeks + 1)
            var w = Int.MAX_VALUE
            for (c in post) for ((s, n) in c.stageCounts().withIndex()) if (!n.done) w = minOf(w, cal.week(c.comp, s))
            return if (w == Int.MAX_VALUE || w == SeasonCalendar.NONE) cal.lastWeek else w
        }

    private var openWeek = Int.MIN_VALUE
    private var opens = DoubleArray(0)

    /** The league progress each competition of [comps] opens its stages by in week [w]. */
    private fun opens(w: Int): DoubleArray {
        val cal = calendar
        if (w == openWeek && opens.size == comps.size) return opens
        opens = DoubleArray(comps.size) { i -> cal.progress(comps[i].comp, w) }
        openWeek = w
        return opens
    }

    /** What competition [i] of [comps] is open to from [o] (a competition off the calendar goes by league progress). */
    private fun open(o: DoubleArray, i: Int): Double = if (league.finished) 1.0 else o[i].let { if (it.isNaN()) progress else it }

    /**
     * The next team fixture, in the competitions' order; a competition whose open match waits
     * for busy players keeps them from the ones after it ([Competition.waiting]).
     */
    private fun teams(o: DoubleArray, busy: Set<String>, lastWeek: Int): Fixture? {
        var blocked: MutableSet<String>? = null
        for ((i, c) in comps.withIndex()) {
            if (!isTeam(c.comp)) continue
            c.next(open(o, i), blocked ?: busy)?.let { f -> within(c, f, lastWeek)?.let { return it } }
            val w = c.waiting(open(o, i))
            if (w.isNotEmpty()) blocked = (blocked ?: busy.toMutableSet()).apply { addAll(w) }
        }
        return null
    }

    /** [f] with the week of its stage, or null (and [f] given back) when that week is after [lastWeek]. */
    private fun within(c: Competition, f: Fixture, lastWeek: Int): Fixture? {
        val s = stamp(c, f)
        if (s.week == SeasonCalendar.NONE || s.week <= lastWeek) return s
        c.release(f)
        return null
    }

    /** [f] with the week of its stage. */
    private fun stamp(c: Competition, f: Fixture): Fixture {
        val w = calendar.week(c.comp, runCatching { c.stageOf(f) }.getOrDefault(-1))
        return if (w == SeasonCalendar.NONE || w == f.week) f else f.copy(week = w)
    }

    /**
     * The next fixture: knockout and group fixtures first while fewer than [cupCap] such jobs
     * are in flight ([cupsInFlight]), then the league, then team competitions, then the
     * post-season. A competition's stage opens in its calendar week (the week of the league
     * round in play, [week]); when nothing else is free the next week's stages may start, so no
     * worker waits. [cost] estimates a league fixture's ms per game (heaviest first late in the
     * season). Nothing of a week after [lastWeek] is given (1주씩 plays one week): a fixture
     * that turns out to be later is given back and the next one tried, so it holds up nothing.
     */
    fun next(
        busy: Set<String>, cupsInFlight: Int, cupCap: Int, cost: ((LeagueFixture) -> Double)? = null, teamsInFlight: Int = Int.MAX_VALUE,
        lastWeek: Int = Int.MAX_VALUE,
    ): Fixture? {
        val o = opens(if (league.finished) leagueWeeks else league.currentMatchday + 1)
        if (cupsInFlight < cupCap) {
            for ((i, c) in comps.withIndex()) if (!isTeam(c.comp)) c.next(open(o, i), busy)?.let { f -> within(c, f, lastWeek)?.let { return it } }
        }
        // One team fixture at a time ahead of the league, so the club league and the family cup
        // play their rounds as they open instead of all after the league.
        if (teamsInFlight < 1) teams(o, busy, lastWeek)?.let { return it }
        league.next(busy, cost, lastMatchday = if (lastWeek == Int.MAX_VALUE) Int.MAX_VALUE else lastWeek - 1)?.let { return league.job(it) }
        teams(o, busy, lastWeek)?.let { return it }
        // Cup fixtures are not capped when nothing else is left to do.
        for ((i, c) in comps.withIndex()) if (!isTeam(c.comp)) c.next(open(o, i), busy)?.let { f -> within(c, f, lastWeek)?.let { return it } }
        if (!league.finished) {
            // Nothing else is free: next week's stages start early rather than a worker waiting.
            val cal = calendar
            val w = league.currentMatchday + 2
            if (w <= lastWeek) for (c in comps) c.next(cal.progress(c.comp, w).let { if (it.isNaN()) progress else it }, busy)?.let { f -> within(c, f, lastWeek)?.let { return it } }
        }
        if (league.finished) for (c in post) c.next(1.0, busy)?.let { f -> within(c, f, lastWeek)?.let { return it } }
        return null
    }

    /**
     * Every stage of the season on its week, league rounds first in each week (comp LEAGUE, stage
     * = matchday), with what has been played; playoffs not set up yet have no fixtures counted.
     * Scans the season: for the screen, not per job.
     */
    fun schedule(): List<WeekStage> {
        val cal = calendar
        val out = ArrayList<WeekStage>()
        for ((k, n) in league.matchdayCounts().withIndex()) out += WeekStage(Comp.LEAGUE, k, "${k + 1}라운드", k + 1, n.played, n.total, false)
        for (e in cal.entries) {
            val c = competition(e.comp)
            val counts = c?.let { runCatching { it.stageCounts() }.getOrNull() }.orEmpty()
            val big = e.comp != Comp.CLUB && e.comp != Comp.PROMOTION_PLAYOFF
            for ((s, st) in e.stages.withIndex()) {
                val n = counts.getOrNull(s)
                out += WeekStage(e.comp, s, st.label, e.week(s), n?.played ?: 0, n?.total ?: 0, big && s == e.stages.lastIndex, set = c != null)
            }
        }
        out.sortWith(compareBy<WeekStage> { it.week }.thenBy { if (it.comp == Comp.LEAGUE) 0 else 1 })
        return out
    }

    fun release(f: Fixture) {
        if (f.comp == Comp.LEAGUE) league.release(f.id) else owner(f)?.release(f)
    }

    /** Records a finished job; returns the news it made. */
    fun record(r: JobResult): List<LeagueEvent> {
        val f = r.fixture
        if (f.comp == Comp.LEAGUE) {
            league.record(f.id, r.boards.single().games)
            return emptyList()
        }
        return owner(f)?.record(r).orEmpty()
    }

    private fun owner(f: Fixture): Competition? = comps.firstOrNull { it.comp == f.comp } ?: post.firstOrNull { it.comp == f.comp }

    /** True for team competitions: they run with the league; cups and groups take the cup slots. */
    fun isTeam(c: Comp) = c == Comp.CLUB || c == Comp.ALL_STAR || c == Comp.FAMILY || c == Comp.WORLD_CUP || c == Comp.CONTINENTAL

    // ---- saving --------------------------------------------------------------------------------

    fun encode(): String = buildString {
        append("plan 1\n")
        append("post ${if (postStarted) 1 else 0}\n")
        append(config.encode().lines().filter { it.isNotBlank() }.joinToString("") { "cfg $it\n" })
        closing?.let { c -> append(c.encode().lines().filter { it.isNotBlank() }.joinToString("") { "closecfg $it\n" }) }
        for ((list, tag) in listOf(comps to "comp", post to "postcomp")) {
            for (c in list) {
                append("$tag ${c.comp.code}\n")
                val body = StringBuilder()
                c.encode(body)
                append(body)
                if (body.isNotEmpty() && body.last() != '\n') append('\n')
                append("end\n")
            }
        }
        append("league\n")
        append(league.encode())
    }

    companion object {
        /**
         * Reads a saved plan; competitions are rebuilt by [codecs] (a competition without a codec
         * is dropped). Throws on unusable files (the caller starts a new season).
         */
        fun decode(text: String, keys: Collection<String>, codecs: Map<Comp, CompetitionCodec>): SeasonPlan {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == "plan 1") { "not a plan file" }
            val leagueAt = lines.indexOf("league")
            require(leagueAt > 0) { "no league in the plan" }
            val league = Season.decode(lines.subList(leagueAt + 1, lines.size).joinToString("\n"), keys)
            val config = LeagueConfig.decode(lines.subList(0, leagueAt).filter { it.startsWith("cfg ") }.joinToString("\n") { it.removePrefix("cfg ") })
            val plan = SeasonPlan(league, config)
            plan.postStarted = lines.any { it.trim() == "post 1" }
            val closing = lines.subList(0, leagueAt).filter { it.startsWith("closecfg ") }
            if (closing.isNotEmpty()) plan.closing = runCatching { LeagueConfig.decode(closing.joinToString("\n") { it.removePrefix("closecfg ") }) }.getOrNull()
            var i = 0
            while (i < leagueAt) {
                val l = lines[i]
                val tag = l.substringBefore(' ')
                if (tag == "comp" || tag == "postcomp") {
                    val code = l.substringAfter(' ').singleOrNull()
                    val end = (i + 1 until leagueAt).firstOrNull { lines[it] == "end" } ?: leagueAt
                    val comp = code?.let { Comp.of(it) }
                    val codec = comp?.let { codecs[it] }
                    if (codec != null) {
                        runCatching { codec.decode(lines.subList(i + 1, end), league.number, league.seed) }.getOrNull()?.let {
                            if (tag == "comp") plan.comps += it else plan.post += it
                        }
                    }
                    i = end + 1
                } else {
                    i++
                }
            }
            return plan
        }
    }
}

package yacht.engine.league

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

    /** League progress, 0..1: competitions open their rounds by it. */
    val progress: Double get() = league.progress

    /** True once the league and every competition are over (post-season included). */
    val finished: Boolean
        get() = league.finished && postStarted && comps.all { it.finished } && post.all { it.finished }

    /** True once the post-season has been set up (it may be empty). */
    var postStarted = false

    /** Sets up the post-season (playoffs) from the final tables, once the league is over. */
    fun startPost(factories: List<(SeasonPlan) -> Competition?>) {
        if (postStarted || !league.finished) return
        postStarted = true
        post += factories.mapNotNull { f -> runCatching { f(this) }.getOrNull() }
    }

    val fixturesTotal: Int get() = league.fixturesTotal + comps.sumOf { it.fixturesTotal } + post.sumOf { it.fixturesTotal }
    val fixturesLeft: Int get() = league.fixturesLeft + comps.sumOf { it.fixturesLeft } + post.sumOf { it.fixturesLeft }

    fun competition(comp: Comp): Competition? = comps.firstOrNull { it.comp == comp } ?: post.firstOrNull { it.comp == comp }

    /**
     * The next fixture: knockout and group fixtures first while fewer than [cupCap] such jobs
     * are in flight ([cupsInFlight]), then the league, then team competitions, then the
     * post-season. [cost] estimates a league fixture's ms per game (heaviest first late in the
     * season).
     */
    fun next(busy: Set<String>, cupsInFlight: Int, cupCap: Int, cost: ((LeagueFixture) -> Double)? = null, teamsInFlight: Int = Int.MAX_VALUE): Fixture? {
        val p = progress
        if (cupsInFlight < cupCap) {
            for (c in comps) if (!isTeam(c.comp)) c.next(p, busy)?.let { return it }
        }
        // One team fixture at a time ahead of the league, so the club league and the family cup
        // play their rounds as they open instead of all after the league.
        if (teamsInFlight < 1) for (c in comps) if (isTeam(c.comp)) c.next(p, busy)?.let { return it }
        league.next(busy, cost)?.let { return league.job(it) }
        for (c in comps) if (isTeam(c.comp)) c.next(p, busy)?.let { return it }
        // Cup fixtures are not capped when nothing else is left to do.
        for (c in comps) if (!isTeam(c.comp)) c.next(p, busy)?.let { return it }
        if (league.finished) for (c in post) c.next(1.0, busy)?.let { return it }
        return null
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
    fun isTeam(c: Comp) = c == Comp.CLUB || c == Comp.ALL_STAR || c == Comp.FAMILY

    // ---- saving --------------------------------------------------------------------------------

    fun encode(): String = buildString {
        append("plan 1\n")
        append("post ${if (postStarted) 1 else 0}\n")
        append(config.encode().lines().filter { it.isNotBlank() }.joinToString("") { "cfg $it\n" })
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

package yacht.engine.league

/**
 * One identity for every match of a season (UX design 3.1): season, competition and fixture id;
 * [board] picks one pairing of a team match (-1: the whole fixture; a one-on-one fixture's only
 * board is 0), [game] a game of it (-1: none). Text "season:code:id[:board[:game]]", as the
 * failure key and [Highlight.id] (which has all five) already write it. Friendlies have none.
 */
data class MatchRef(val season: Int, val comp: Comp, val id: Int, val board: Int = -1, val game: Int = -1) {
    fun encode(): String = "$season:${comp.code}:$id" + (if (board >= 0 || game >= 0) ":$board" else "") + (if (game >= 0) ":$game" else "")

    /** "season:code:id": the whole fixture. */
    val fixtureKey: String get() = "$season:${comp.code}:$id"

    /** The whole fixture (no board, no game). */
    val whole: MatchRef get() = if (board < 0 && game < 0) this else copy(board = -1, game = -1)

    override fun toString(): String = encode()

    companion object {
        /** Reads [encode]'s text (3, 4 or 5 fields); null when it is not one. */
        fun decode(s: String): MatchRef? {
            val f = s.trim().split(':')
            if (f.size !in 3..5) return null
            val season = f[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val comp = f[1].singleOrNull()?.let { Comp.of(it) } ?: return null
            val id = f[2].toIntOrNull() ?: return null
            val board = if (f.size > 3) f[3].toIntOrNull()?.takeIf { it >= -1 } ?: return null else -1
            val game = if (f.size > 4) f[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null else -1
            return MatchRef(season, comp, id, board, game)
        }
    }
}

/**
 * A fixture of the season as the world can play it again: its [fixture] exactly as it was (or
 * will be) handed out (same deal, label and week), whether it is [played], the stored [score]
 * (games or boards won and totals, [TieScore]) and, for a league fixture, its [games]. Knockout
 * ties are listed once both sides are known (byes never). [stage] is the competition's (a league
 * fixture's: its matchday), [week] the calendar's ([SeasonCalendar.NONE] when it has none).
 */
data class SeasonMatch(
    val ref: MatchRef,
    val fixture: Fixture,
    val played: Boolean,
    val score: TieScore?,
    val games: List<GameScore>?,
    val week: Int,
    val stage: Int,
)

/**
 * A competition whose fixtures can be listed again ([SeasonMatches]): every match that is ready or
 * played, with the fixture its `next` hands (or handed) out. The week is left to the season's
 * calendar ([SeasonMatches] stamps it as [SeasonPlan] does).
 */
interface MatchSource {
    fun seasonMatches(): List<SeasonMatch>

    /** Fixture [id]; null when it is not this competition's, a bye, or its sides are not known yet. */
    fun seasonMatch(id: Int): SeasonMatch?

    /** The matches [key] plays in (a side or in a lineup). */
    fun seasonMatchesOf(key: String): List<SeasonMatch> = seasonMatches().filter { key in it.fixture.players }
}

/** A match of this competition from the fixture it hands out for it and its stored [score] (null: not played). */
internal fun Competition.matchOf(season: Int, f: Fixture, score: TieScore?): SeasonMatch =
    SeasonMatch(MatchRef(season, comp, f.id), f, score != null, score, null, f.week, runCatching { stageOf(f) }.getOrDefault(-1))

/**
 * Every match of a season from its plan (UX design 3.5): the league's fixtures through [Season]'s
 * own API, the legacy [ClubLeague]'s through its matches, every other competition as a
 * [MatchSource]. For the match report, replays (re-simulating a stored fixture with
 * [LeagueMatch.playJob] and [verify]ing it), the broadcast picks and the favourite feed. Reads
 * the plan only: call it under the league's lock.
 */
object SeasonMatches {
    /** Fixture [id] of [comp] this season; null when there is none (or not yet: a tie whose sides are not known). */
    fun of(plan: SeasonPlan, comp: Comp, id: Int): SeasonMatch? {
        if (comp == Comp.LEAGUE) return league(plan, id)
        val c = plan.competition(comp) ?: return null
        val m = when (c) {
            is MatchSource -> c.seasonMatch(id)
            is ClubLeague -> c.matches().firstOrNull { it.id == id }?.let { club(plan, c, it) }
            else -> null
        } ?: return null
        return stamp(plan, m)
    }

    /** [ref]'s fixture (its board and game are the caller's). */
    fun of(plan: SeasonPlan, ref: MatchRef): SeasonMatch? = if (ref.season != plan.number) null else of(plan, ref.comp, ref.id)

    /** Every match of the season: the league's fixtures in id order, then each competition's (post-season last). */
    fun all(plan: SeasonPlan): Sequence<SeasonMatch> = sequence {
        for (id in 0 until plan.league.fixturesTotal) league(plan, id)?.let { yield(it) }
        for (c in plan.comps + plan.post) yieldAll(ofComp(plan, c))
    }

    /** Every match of competition [comp] (the league's: all its fixtures). */
    fun ofComp(plan: SeasonPlan, comp: Comp): List<SeasonMatch> =
        if (comp == Comp.LEAGUE) (0 until plan.league.fixturesTotal).mapNotNull { league(plan, it) } else plan.competition(comp)?.let { ofComp(plan, it) }.orEmpty()

    /**
     * [key]'s matches this season in week order (league fixtures first in a week): its league
     * fixtures (from [index], the ids [fixtureIndex] gave, when known; else a scan of the league)
     * and its competition matches. A team match is [key]'s own board ([MatchRef.board]).
     */
    fun ofPlayer(plan: SeasonPlan, key: String, index: IntArray? = null): List<SeasonMatch> {
        val out = ArrayList<SeasonMatch>()
        val ids = index ?: leagueIds(plan, setOf(key)).getValue(key)
        for (id in ids) league(plan, id)?.let { out += it }
        for (c in plan.comps + plan.post) {
            val own = when (c) {
                is MatchSource -> c.seasonMatchesOf(key)
                is ClubLeague -> c.matches().filter { m -> key in c.lineups[m.a].orEmpty() || key in c.lineups[m.b].orEmpty() }.map { club(plan, c, it) }
                else -> emptyList()
            }
            for (m in own) {
                val f = m.fixture
                val board = if (f.kind == JobKind.TEAM) f.lineupA.indexOf(key).takeIf { it >= 0 } ?: f.lineupB.indexOf(key) else -1
                if (f.kind == JobKind.TEAM && board < 0) continue
                out += stamp(plan, if (board >= 0) m.copy(ref = m.ref.copy(board = board)) else m)
            }
        }
        // Stable: league fixtures stay ahead of the week's other matches.
        out.sortBy { if (it.week == SeasonCalendar.NONE) Int.MAX_VALUE else it.week }
        return out
    }

    /** The league fixture ids of each of [keys] (id order), in one scan of the league: for [ofPlayer]. */
    fun fixtureIndex(plan: SeasonPlan, keys: Set<String>): Map<String, IntArray> = leagueIds(plan, keys)

    /**
     * The matches of week [week]: the league's fixtures of that matchday, then the ready or played
     * matches of every competition's stages in that week.
     */
    fun ofWeek(plan: SeasonPlan, week: Int): List<SeasonMatch> {
        val out = ArrayList<SeasonMatch>()
        if (week >= 1) for (f in plan.league.matchday(week - 1)) league(plan, f.id)?.let { out += it }
        val stages = plan.calendar.at(week).groupBy({ it.first }, { it.second })
        for (c in plan.comps + plan.post) {
            val due = stages[c.comp] ?: continue
            out += ofComp(plan, c).filter { it.stage in due }
        }
        return out
    }

    /**
     * The match of [comp] in week [week] (any week: [SeasonCalendar.NONE]) between [a] and [b] (a
     * board's sides, either way round), for feed items saved without an id: the last played one,
     * else the last ready one; a team match's board as [MatchRef.board]. Null when none.
     */
    fun find(plan: SeasonPlan, comp: Comp, week: Int, a: String, b: String): SeasonMatch? {
        val candidates = when {
            comp == Comp.LEAGUE && week >= 1 -> plan.league.matchday(week - 1).mapNotNull { league(plan, it.id) }
            else -> ofComp(plan, comp).filter { week == SeasonCalendar.NONE || it.week == week }
        }
        fun pair(x: String, y: String) = (x == a && y == b) || (x == b && y == a)
        val found = candidates.mapNotNull { m ->
            val f = m.fixture
            if (f.kind != JobKind.TEAM) {
                m.takeIf { pair(f.a, f.b) }
            } else {
                f.lineupA.indices.firstOrNull { i -> i < f.lineupB.size && pair(f.lineupA[i], f.lineupB[i]) }?.let { m.copy(ref = m.ref.copy(board = it)) }
            }
        }
        return found.lastOrNull { it.played } ?: found.lastOrNull()
    }

    /**
     * True when [r] (the match played again, in the season's format) ends as [m] did: a league
     * fixture game for game, any other its [TieScore] (a team match all its boards at once).
     */
    fun verify(m: SeasonMatch, r: JobResult): Boolean {
        if (!m.played || r.fixture.comp != m.ref.comp || r.fixture.id != m.ref.id) return false
        m.games?.let { g -> return r.boards.size == 1 && r.boards[0].games == g }
        val s = m.score ?: return false
        return TieScore.of(r) == s
    }

    // ---- sources -------------------------------------------------------------------------------

    private fun league(plan: SeasonPlan, id: Int): SeasonMatch? {
        val s = plan.league
        if (id !in 0 until s.fixturesTotal) return null
        val lf = s.fixture(id)
        val games = s.result(id)
        val score = games?.let { g ->
            var won = 0.0
            for (x in g) won += if (x.a > x.b) 1.0 else if (x.a == x.b) 0.5 else 0.0
            TieScore(won, g.size - won, g.sumOf { it.a }, g.sumOf { it.b }, TieRule.match(g, s.format))
        }
        return SeasonMatch(MatchRef(plan.number, Comp.LEAGUE, id), s.job(lf), games != null, score, games, lf.matchday + 1, lf.matchday)
    }

    private fun club(plan: SeasonPlan, c: ClubLeague, m: ClubMatch): SeasonMatch {
        val f = c.job(m)
        val score = m.result?.let { r -> TieScore(r.won + r.level / 2.0, r.lost + r.level / 2.0, r.scoreA, r.scoreB, r.winner) }
        return stamp(plan, SeasonMatch(MatchRef(plan.number, Comp.CLUB, m.id), f, score != null, score, null, f.week, m.round))
    }

    private fun ofComp(plan: SeasonPlan, c: Competition): List<SeasonMatch> = when (c) {
        is MatchSource -> c.seasonMatches().map { stamp(plan, it) }
        is ClubLeague -> c.matches().map { club(plan, c, it) }
        else -> emptyList()
    }

    /** [m] with the week of its stage, as [SeasonPlan] stamps the fixtures it hands out. */
    private fun stamp(plan: SeasonPlan, m: SeasonMatch): SeasonMatch {
        val w = plan.calendar.week(m.ref.comp, m.stage)
        if (w == SeasonCalendar.NONE || w == m.week) return m
        return m.copy(fixture = m.fixture.copy(week = w), week = w)
    }

    private fun leagueIds(plan: SeasonPlan, keys: Set<String>): Map<String, IntArray> {
        val lists = keys.associateWith { ArrayList<Int>() }
        val s = plan.league
        for (id in 0 until s.fixturesTotal) {
            val f = s.fixture(id)
            lists[f.a]?.add(id)
            lists[f.b]?.add(id)
        }
        return lists.mapValues { (_, v) -> v.toIntArray() }
    }
}

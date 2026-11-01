package yacht.engine.league

import java.util.TreeMap

/**
 * A row of the weekly power ranking (파워 랭킹, design ENG 5.1), built on request for the screens:
 * member [key] at [rank] (last week's [prev], null when new), its [score] `R0 + 6 (P − E) + 12
 * (form5 − e5) + S` from the rating it was [drawn] with, league [points] and [expected] points
 * through the week, its last 5 matches' mean points and expected points, the stage bonus [stage]
 * (20 / 10 / 0), league matches [played], weeks at #1 this season ([weeksTop]) and the wins of
 * its last 5 matches ([wins5]: "최근 5경기 {n}승").
 */
data class PowerRow(
    val key: String, val rank: Int, val prev: Int?, val score: Double, val drawn: Double, val points: Int,
    val expected: Double, val form5: Double, val expected5: Double, val stage: Int, val played: Int, val weeksTop: Int,
    val wins5: Int = 0,
)

/** A candidate's votes in a press award: [points] (5-3-1 a ballot) and first-place votes. */
data class PressVoteRow(val key: String, val points: Int, val firsts: Int)

/**
 * A press award's tally of [season] (`pv` in stats.txt, design ENG 5.4): award [award] (a
 * [PressAwards.AWARDS] code) voted by [jury] journalists, every candidate's votes, winner first.
 */
data class PressTally(val season: Int, val award: String, val jury: Int, val rows: List<PressVoteRow>) {
    /** The `pv` line (with its '\n'). */
    fun encode(): String = "pv $season $award $jury ${LineParts.list(rows) { "${Field.esc(it.key)}:${it.points}:${it.firsts}" }}\n"

    companion object {
        /** Reads a `pv` line split on spaces; null when it cannot. */
        fun decode(f: List<String>): PressTally? = runCatching {
            val rows = LineParts.items(f[4]).map { e ->
                val p = e.split(':')
                PressVoteRow(Field.unesc(p[0]), p[1].toInt(), p[2].toInt())
            }
            PressTally(f[1].toInt(), f[2], f[3].toInt(), rows)
        }.getOrNull()
    }
}

/**
 * The press-vote awards (기자단 투표, design ENG 5.4). [KINDS] are the honours they give (골든 다이스,
 * 올해의 영입), apart from [Awards.KINDS] (which drives the season's own awards and their news): 올해의
 * 감독 is a tally only (the coach's count is raised in the next club off-season).
 */
object PressAwards {
    const val GOLDEN_DICE = "GD"
    const val COACH = "COY"
    const val SIGNING = "SIGN"

    /** Honour kinds given by the press votes. */
    val KINDS: Set<String> = setOf(GOLDEN_DICE, SIGNING)

    /** Every award, with its label, in the order the screens show them. */
    val AWARDS: List<Pair<String, String>> = listOf(GOLDEN_DICE to "골든 다이스", COACH to "올해의 감독", SIGNING to "올해의 영입")
}

/** A member's predicted and final place (예측 성적표's over- and underachievers). */
data class ScoreSwing(val key: String, val predicted: Int, val final: Int)

/** One league's line of a scorecard: the favourite won, the champion was among the 3 favourites, relegation picks relegated, Spearman ρ × 100. */
data class LeagueScorecard(val league: String, val hit: Boolean, val top3: Boolean, val relHits: Int, val rho: Int)

/**
 * The preseason predictions' scorecard of [season] (`psc`, design ENG 5.2): over [leagues] leagues
 * the favourites who won, champions among the 3 favourites, relegation picks relegated of
 * [relPicks], the mean Spearman ρ × 100, the biggest overachiever and flop, and (for the newest
 * season) each league's line.
 */
data class PredictionScorecard(
    val season: Int, val hits: Int, val leagues: Int, val top3: Int, val relHits: Int, val relPicks: Int, val rho: Int,
    val over: ScoreSwing?, val flop: ScoreSwing?, val perLeague: List<LeagueScorecard> = emptyList(),
)

/** The World Cup host vote for the WC of [season] (`wcv`, design ENG 5.5): the [host] and each round's votes per nation. */
data class WcVote(val season: Int, val host: String, val rounds: List<List<Pair<String, Int>>>)

/**
 * A World Cup's draw ceremony (`wcc`): its presenter (a legend's key, null for none), the group of
 * death ([death], a group's letter), the groups as drawn (letter → nations) and the pots.
 */
data class WcCeremony(val season: Int, val presenter: String?, val death: String, val groups: List<Pair<String, List<String>>>, val pots: List<List<String>>)

/**
 * The media's part of world.txt (design ENG 5): career weeks at #1 of the power ranking (`pr1`),
 * the predictions' scorecards (`psc`, 30 seasons), the World Cup host votes (`wcv`) and draw
 * ceremonies (`wcc`). The logic is P10's, in this file.
 */
class MediaBook {
    internal val weeksTop = TreeMap<String, Int>()
    internal val scorecards = TreeMap<Int, PredictionScorecard>()
    internal val votes = TreeMap<Int, WcVote>()
    internal val ceremonies = TreeMap<Int, WcCeremony>()

    /** Career weeks at #1 of the power ranking, by key. */
    val careerTop: Map<String, Int> get() = weeksTop

    fun scorecard(season: Int): PredictionScorecard? = scorecards[season]

    val allScorecards: List<PredictionScorecard> get() = scorecards.values.toList()

    /** The host vote for the World Cup of [season]; null when none was held (today's rotation). */
    fun vote(season: Int): WcVote? = votes[season]

    fun ceremony(season: Int): WcCeremony? = ceremonies[season]

    /**
     * Adds season [season]'s weeks at #1 of the power ranking ([NewsDesk.weeksTop] of [desk]) to
     * the career counts (`pr1`). The close's step (design ENG 5.1), run once a season under the
     * world book's `closeSeason` guard and before the open's [NewsDesk.begin] clears the desk; a
     * desk of another season adds nothing.
     */
    internal fun foldTop(desk: NewsDesk, season: Int) {
        if (desk.season != season) return
        for ((k, n) in desk.weeksTop) if (n > 0) weeksTop.merge(k, n, Int::plus)
    }

    /** Keeps [sc] in place of its season's: only the newest season keeps its per-league lines, and the newest [SCORECARDS] seasons. */
    internal fun putScorecard(sc: PredictionScorecard) {
        scorecards[sc.season] = sc
        for (s in scorecards.headMap(scorecards.lastKey(), false).keys.toList()) {
            val old = scorecards.getValue(s)
            if (old.perLeague.isNotEmpty()) scorecards[s] = old.copy(perLeague = emptyList())
        }
        while (scorecards.size > SCORECARDS) scorecards.pollFirstEntry()
    }

    /** Keeps the host vote [v] (for the World Cup of its season) in place of any before, and the newest [VOTES]. */
    internal fun putVote(v: WcVote) {
        votes[v.season] = v
        while (votes.size > VOTES) votes.pollFirstEntry()
    }

    /** Keeps the draw ceremony [c] in place of its season's, and the newest [VOTES]. */
    internal fun putCeremony(c: WcCeremony) {
        ceremonies[c.season] = c
        while (ceremonies.size > VOTES) ceremonies.pollFirstEntry()
    }

    companion object {
        /** Seasons of scorecards kept (`psc`, design ENG 5.2). */
        const val SCORECARDS = 30

        /** World Cups whose host vote and draw ceremony are kept (`wcv`, `wcc`): about 120 seasons. */
        const val VOTES = 30

        private fun swing(s: ScoreSwing?) = s?.let { "${Field.esc(it.key)}:${it.predicted}:${it.final}" } ?: "-"

        private fun swingOf(t: String): ScoreSwing? = t.takeIf { it != "-" }?.split(':')?.let { ScoreSwing(Field.unesc(it[0]), it[1].toInt(), it[2].toInt()) }

        private fun ratio(t: String): Pair<Int, Int> = t.split('/').let { it[0].toInt() to it[1].toInt() }

        private val PR1 = LineParts.of<WorldBook>("pr1", { w, out ->
            if (w.media.weeksTop.isNotEmpty()) out.append("pr1 ").append(w.media.weeksTop.entries.joinToString(",") { "${Field.esc(it.key)}:${it.value}" }).append('\n')
        }) { w, f ->
            for (e in LineParts.items(f[1])) w.media.weeksTop[Field.unesc(e.substringBefore(':'))] = e.substringAfter(':').toInt()
            true
        }

        private val PSC = LineParts.of<WorldBook>("psc", { w, out ->
            for (s in w.media.scorecards.values) {
                out.append("psc ${s.season} ${s.hits}/${s.leagues} ${s.top3}/${s.leagues} ${s.relHits}/${s.relPicks} ${s.rho} ${swing(s.over)} ${swing(s.flop)} ")
                out.append(LineParts.list(s.perLeague, ';') { "${Field.esc(it.league)}:${LineParts.bit(it.hit)}:${LineParts.bit(it.top3)}:${it.relHits}:${it.rho}" }).append('\n')
            }
        }) { w, f ->
            val (hits, leagues) = ratio(f[2])
            val (top3, _) = ratio(f[3])
            val (relHits, relPicks) = ratio(f[4])
            val per = f.getOrNull(8)?.let { t ->
                LineParts.items(t, ';').map { e ->
                    val p = e.split(':')
                    LeagueScorecard(Field.unesc(p[0]), p[1] == "1", p[2] == "1", p[3].toInt(), p[4].toInt())
                }
            }.orEmpty()
            val season = f[1].toInt()
            w.media.scorecards[season] = PredictionScorecard(season, hits, leagues, top3, relHits, relPicks, f[5].toInt(), swingOf(f[6]), swingOf(f[7]), per)
            true
        }

        private val WCV = LineParts.of<WorldBook>("wcv", { w, out ->
            for (v in w.media.votes.values) {
                out.append("wcv ${v.season} ${v.host} ")
                out.append(LineParts.list(v.rounds.withIndex().toList(), ';') { (i, r) -> r.joinToString(",") { "${i + 1}:${it.first}:${it.second}" } }).append('\n')
            }
        }) { w, f ->
            val rounds = LineParts.items(f[3], ';').map { r -> r.split(',').filter { it.isNotEmpty() }.map { e -> e.split(':').let { it[1] to it[2].toInt() } } }
            val season = f[1].toInt()
            w.media.votes[season] = WcVote(season, f[2], rounds)
            true
        }

        private val WCC = LineParts.of<WorldBook>("wcc", { w, out ->
            for (c in w.media.ceremonies.values) {
                out.append("wcc ${c.season} ${LineParts.opt(c.presenter)} ${c.death} ")
                out.append(LineParts.list(c.groups, ';') { (g, ns) -> "$g=${ns.joinToString(",")}" }).append(' ')
                out.append(LineParts.list(c.pots, ';') { it.joinToString(",") }).append('\n')
            }
        }) { w, f ->
            val groups = LineParts.items(f[4], ';').map { e -> e.substringBefore('=') to LineParts.items(e.substringAfter('=')) }
            val pots = LineParts.items(f[5], ';').map { LineParts.items(it) }
            val season = f[1].toInt()
            w.media.ceremonies[season] = WcCeremony(season, Field.opt(f[2]), f[3], groups, pots)
            true
        }

        /** `pr1 psc wcv wcc`. */
        val PARTS: List<LinePart<WorldBook>> = listOf(PR1, PSC, WCV, WCC)
    }
}

// ---- the power ranking, the predictions and their scorecard (design ENG 5.1-5.2) ---------------

/**
 * A league's preseason predictions (시즌 전망, design ENG 5.2) from [Odds.preseason]: its 1부's rows
 * in predicted order ([order], by expected place), the title favourites (우승 후보, by title chance),
 * the dark horse (다크호스: of those predicted [Media.DARK_FROM]th or lower, the best chance of a
 * top-4 finish, a promoted player a little ahead) and the relegation picks (강등 후보). Nothing of
 * it is saved: the same plan and drawn ratings give the same predictions whenever asked.
 */
data class Prediction(
    val league: String,
    val season: Int,
    val odds: OddsTable,
    val order: List<AiOdds>,
    val favourites: List<AiOdds>,
    val dark: AiOdds?,
    val relegation: List<AiOdds>,
) {
    /** [key]'s predicted place in the 1부 (1 = first); null when it is not in it. */
    fun predicted(key: String): Int? = order.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.plus(1)
}

/**
 * 미디어 연출 (design ENG 5): the weekly power ranking (파워 랭킹, 5.1), the preseason predictions
 * and their scorecard (시즌 전망, 예측 성적표, 5.2). Pure functions of a season's plan and the
 * ratings it was drawn with ([drawn]: the ratings the season started from, never the live ones, so
 * a week or a close asked again after a kill gives the same answer); the desk keeps the ranking's
 * weeks ([NewsDesk]) and the world book the scorecards ([MediaBook]).
 */
object Media {
    /** League matches a 1부 member must have played to be ranked. */
    const val MIN_PLAYED = 4

    /** The world's column (세계 톱 10) and a league's list on its page. */
    const val WORLD_TOP = 10
    const val LEAGUE_TOP = 5

    /** The recent form: the last league matches counted, and the weights of points and form above expectation. */
    const val FORM = 5
    const val POINTS_WEIGHT = 6.0
    const val FORM_WEIGHT = 12.0

    /** The stage bonus: a 1부 of the [TOP_LEAGUES] best-ranked leagues, another 1부. */
    const val TOP_STAGE = 20
    const val STAGE = 10

    /** The best-ranked leagues: the stage bonus, the predictions' news. */
    const val TOP_LEAGUES = 8

    /** A climb into or within the top 10 by this many places or more is the column's news. */
    const val CLIMB = 3

    /** Title favourites and relegation picks a league; the dark horse comes from this predicted place down. */
    const val FAVOURITES = 3
    const val RELEGATION = 3
    const val DARK_FROM = 6

    /** What a promoted player's top-4 chance counts for more in the dark horse's pick. */
    const val PROMOTED_BONUS = 0.05

    /** [plan]'s leagues, best-ranked first (its ranking, then the strength by [drawn]): the order every media list uses. */
    fun leagues(plan: SeasonPlan, drawn: (String) -> Double): List<String> =
        if (plan.league.leagues.national) Derbies.topLeagues(plan, drawn) else emptyList()

    /** A drawn rating, an unrated one at [LeagueWorld.START_RATING]. */
    private fun r0(drawn: (String) -> Double, k: String): Double = drawn(k).takeIf { it.isFinite() } ?: LeagueWorld.START_RATING

    // ---- the power ranking ---------------------------------------------------------------------

    /** One member's league matches through the week: points, expected points, and its matches (matchday, id, points, expected, 1 for a win). */
    private class Form {
        var points = 0
        var expected = 0.0
        val matches = ArrayList<DoubleArray>()
    }

    /**
     * The power ranking of week [week] (design ENG 5.1), best first: every 1부 member of a national
     * league (of [league] only, when given) with [MIN_PLAYED] league matches or more through the
     * week, by `PR = R0 + 6 (P − E) + 12 (form5 − e5) + S`: its drawn rating, its league points
     * through the week above those expected from the drawn ratings ([Expectation.points] at
     * [Odds]' priors), its last [FORM] matches' mean points above their mean expected points, and
     * the stage ([TOP_STAGE] in a 1부 of the [TOP_LEAGUES] best-ranked leagues, else [STAGE]).
     * "Through week w" is through matchday w − 1 (fixtures of later matchdays played early never
     * count, as [Season.pointsThrough]). Ranks from 1; no `prev` and no weeks at #1 ([power] adds
     * them). Empty for a legacy season. O(league fixtures).
     */
    fun ranking(plan: SeasonPlan, drawn: (String) -> Double, week: Int, league: String? = null): List<PowerRow> {
        val season = plan.league
        if (!season.leagues.national || week < 1) return emptyList()
        val top = leagues(plan, drawn).take(TOP_LEAGUES).toSet()
        val stage = HashMap<Int, Int>()
        for (lg in season.leagues.list) if (lg.size > 0 && (league == null || lg.id == league)) stage[lg.top] = if (lg.id in top) TOP_STAGE else STAGE
        if (stage.isEmpty()) return emptyList()
        val bonus = season.bonus
        val sweep = if (bonus) Odds.SWEEP_PRIOR else 0.0
        val b = if (bonus) 1 else 0
        val forms = HashMap<String, Form>()
        for (id in 0 until season.fixturesTotal) {
            val f = season.fixture(id)
            if (f.matchday > week - 1 || f.division !in stage) continue
            val games = season.result(id) ?: continue
            val r = TieRule.match(games, season.format)
            val pa = when {
                r > 0 -> if (bonus && TieRule.sweptByA(games)) 4 else 3
                r < 0 -> 0
                else -> 1
            }
            val pb = when {
                r < 0 -> if (bonus && TieRule.sweptByB(games)) 4 else 3
                r > 0 -> 0
                else -> 1
            }
            val ra = r0(drawn, f.a)
            val rb = r0(drawn, f.b)
            for ((k, p, e) in listOf(Triple(f.a, pa, Expectation.points(ra, rb, Odds.DRAW_PRIOR, sweep, b)), Triple(f.b, pb, Expectation.points(rb, ra, Odds.DRAW_PRIOR, sweep, b)))) {
                val x = forms.getOrPut(k) { Form() }
                x.points += p
                x.expected += e
                x.matches += doubleArrayOf(f.matchday.toDouble(), f.id.toDouble(), p.toDouble(), e, if (p >= 3) 1.0 else 0.0)
            }
        }
        val rows = ArrayList<PowerRow>()
        for ((k, x) in forms) {
            if (x.matches.size < MIN_PLAYED) continue
            val d = season.divisionOf(k) ?: continue
            val s = stage[d] ?: continue
            val last = x.matches.sortedWith(compareBy<DoubleArray> { it[0] }.thenBy { it[1] }).takeLast(FORM)
            val form = last.sumOf { it[2] } / last.size
            val e5 = last.sumOf { it[3] } / last.size
            val drawnK = r0(drawn, k)
            val score = drawnK + POINTS_WEIGHT * (x.points - x.expected) + FORM_WEIGHT * (form - e5) + s
            rows += PowerRow(k, 0, null, score, drawnK, x.points, x.expected, form, e5, s, x.matches.size, 0, last.count { it[4] > 0.0 })
        }
        return rows.sortedWith(compareByDescending<PowerRow> { it.score }.thenBy { it.key }).mapIndexed { i, r -> r.copy(rank = i + 1) }
    }

    /**
     * The first week a national 1부 member can be ranked ([MIN_PLAYED] league fixtures through it:
     * "파워 랭킹은 … {first}주차부터 나와요"); 0 for a legacy season or one too short.
     */
    fun firstWeek(plan: SeasonPlan): Int {
        val season = plan.league
        if (!season.leagues.national) return 0
        val tops = season.leagues.tops().toSet()
        val days = HashMap<String, MutableList<Int>>()
        for (id in 0 until season.fixturesTotal) {
            val f = season.fixture(id)
            if (f.division !in tops) continue
            days.getOrPut(f.a) { ArrayList() } += f.matchday
            days.getOrPut(f.b) { ArrayList() } += f.matchday
        }
        return days.values.filter { it.size >= MIN_PLAYED }.minOfOrNull { it.sorted()[MIN_PLAYED - 1] + 1 } ?: 0
    }

    /**
     * The power ranking as the desk last ranked it ([NewsDesk.powerWeek]), for the screens: the
     * world's ([league] null; the desk's top 10 in its order, then the rest) or a league's, its
     * first [n] with last week's place ([PowerRow.prev]: the desk's last list for the world's, the
     * week before's for a league's; null for a newcomer) and this season's weeks at #1. Empty
     * before the first ranked week or for another season's desk.
     */
    fun power(plan: SeasonPlan, desk: NewsDesk, drawn: (String) -> Double, league: String? = null, n: Int = if (league == null) WORLD_TOP else LEAGUE_TOP): List<PowerRow> {
        if (desk.season != plan.number || desk.powerWeek <= 0) return emptyList()
        val rows = ranking(plan, drawn, desk.powerWeek, league)
        val ordered = if (league == null) {
            val byKey = rows.associateBy { it.key }
            val told = desk.power.toSet()
            desk.power.mapNotNull { byKey[it] } + rows.filter { it.key !in told }
        } else {
            rows
        }
        val before = when {
            league == null -> desk.powerPrev
            desk.powerPrevWeek > 0 -> ranking(plan, drawn, desk.powerPrevWeek, league).map { it.key }
            else -> emptyList()
        }
        return ordered.take(n).mapIndexed { i, r ->
            r.copy(rank = i + 1, prev = before.indexOf(r.key).takeIf { it >= 0 }?.plus(1), weeksTop = desk.weeksTop[r.key] ?: 0)
        }
    }

    /**
     * The week's column (`power_ranking`, design ENG 11.2) for the world top 10 [now] after
     * [before] (last week's, empty the first week): keys the top 10 in order; args the week, the
     * biggest climber's key (of those in both lists, [CLIMB] places or more; "-"), its places up
     * ("-"), the best-placed newcomer's key ("-"), "1" when #1 changed (else "0").
     */
    fun powerEvent(season: Int, week: Int, now: List<String>, before: List<String>): LeagueEvent {
        var climber: String? = null
        var up = 0
        for ((i, k) in now.withIndex()) {
            val was = before.indexOf(k).takeIf { it >= 0 } ?: continue
            if (was - i >= CLIMB && was - i > up) {
                climber = k
                up = was - i
            }
        }
        val newcomer = if (before.isEmpty()) null else now.firstOrNull { it !in before }
        val changed = before.isNotEmpty() && now.firstOrNull() != before.firstOrNull()
        return LeagueEvent(
            "power_ranking", season, now,
            listOf(week.toString(), climber ?: "-", if (climber == null) "-" else up.toString(), newcomer ?: "-", if (changed) "1" else "0"), null, week,
        )
    }

    // ---- the predictions -----------------------------------------------------------------------

    /**
     * League [league]'s predictions ([Prediction]) of [plan] from [Odds.preseason] at its seed,
     * [drawn] the season's drawn ratings; [promoted] tells a member that came up to the 1부 this
     * season (its dark-horse pick counts [PROMOTED_BONUS] more). Null for a league the plan does not
     * have or a 1부 of fewer than 2. O(the league's fixtures × [Odds.PRESEASON_RUNS]), about 3 ms.
     */
    fun prediction(plan: SeasonPlan, drawn: (String) -> Double, league: String, promoted: (String) -> Boolean = { false }): Prediction? {
        val leagues = plan.league.leagues
        val lg = if (leagues.national) leagues.byId(league) ?: return null else leagues.list.firstOrNull() ?: return null
        val odds = runCatching { Odds.preseason(plan, drawn, league) }.getOrNull() ?: return null
        val rows = odds.division(lg.top)
        if (rows.size < 2) return null
        val order = rows.sortedWith(compareBy<AiOdds> { it.expectedPosition }.thenBy { it.key })
        val favourites = rows.sortedWith(compareByDescending<AiOdds> { it.title }.thenBy { it.expectedPosition }.thenBy { it.key }).take(FAVOURITES)
        val dark = order.drop(DARK_FROM - 1).filter { it.top4 > 0.0 }
            .maxWithOrNull(compareBy<AiOdds> { it.top4 + if (promoted(it.key)) PROMOTED_BONUS else 0.0 }.thenByDescending { it.expectedPosition }.thenByDescending { it.key })
        val relegation = rows.filter { it.relegation > 0.0 }
            .sortedWith(compareByDescending<AiOdds> { it.relegation }.thenByDescending { it.expectedPosition }.thenBy { it.key }).take(RELEGATION)
        return Prediction(lg.id, plan.number, odds, order, favourites, dark, relegation)
    }

    /** A chance as a whole percent. */
    private fun pct(x: Double): Int = Math.round(x * 100).toInt()

    /**
     * The open's predictions news (design ENG 5.2, 11.2): `predictions` of the best-ranked league
     * [favourite, dark horse or "-", relegation picks...] (league, the favourite's title chance in
     * %), comp [Comp.LEAGUE] (so its scope is the favourite's league), and one digest `predictions`
     * [the other best-ranked leagues' favourites] ("*", then "league:%" for each, in the same order;
     * no comp: the world's) when there are others. Week [SeasonCalendar.PRESEASON]. Empty for a
     * legacy season.
     */
    fun predictionEvents(plan: SeasonPlan, drawn: (String) -> Double, promoted: (String) -> Boolean = { false }): List<LeagueEvent> {
        val ids = leagues(plan, drawn).take(TOP_LEAGUES)
        val ps = ids.mapNotNull { prediction(plan, drawn, it, promoted) }
        val first = ps.firstOrNull() ?: return emptyList()
        val fav = first.favourites.first()
        val out = ArrayList<LeagueEvent>()
        out += LeagueEvent(
            "predictions", plan.number, listOf(fav.key, first.dark?.key ?: "-") + first.relegation.map { it.key },
            listOf(first.league, pct(fav.title).toString()), Comp.LEAGUE, SeasonCalendar.PRESEASON,
        )
        val rest = ps.drop(1)
        if (rest.isNotEmpty()) {
            out += LeagueEvent(
                "predictions", plan.number, rest.map { it.favourites.first().key },
                listOf("*") + rest.map { "${it.league}:${pct(it.favourites.first().title)}" }, null, SeasonCalendar.PRESEASON,
            )
        }
        return out
    }

    // ---- the scorecard -------------------------------------------------------------------------

    /**
     * The predictions' scorecard of closing season [plan] (예측 성적표, design ENG 5.2; `psc`): every
     * national league's predictions computed again from the same plan and [drawn] ratings (so they
     * are the open's, [promoted] as the open had it) against how it ended: the favourite won the
     * league title ("L0.<id>" of [honours] of this season, else of the title playoff, else the regular season's
     * leader), the champion among the favourites, the relegation picks that went down
     * ([Offseason.moves]; AIs in [gone] do not move), Spearman's ρ of the predicted against the
     * final regular-season order of the 1부; over every league, the biggest overachiever and flop
     * (predicted − final place, then the better-ranked league, then key). Leagues best-ranked
     * first. Null for a legacy season or with no league predicted.
     */
    fun scorecard(
        plan: SeasonPlan,
        drawn: (String) -> Double,
        honours: List<Honour> = emptyList(),
        promoted: (String) -> Boolean = { false },
        gone: Set<String> = emptySet(),
    ): PredictionScorecard? {
        val season = plan.league
        if (!season.leagues.national) return null
        val moves = runCatching { Offseason.moves(plan, gone) }.getOrDefault(emptyList())
        // The season's own titles (a caller may pass every season's honours).
        val titles = honours.filter { it.season == plan.number }
        val playoff = runCatching { plan.competition(Comp.TITLE_PLAYOFF)?.honours() }.getOrNull().orEmpty()
        val per = ArrayList<LeagueScorecard>()
        val rhos = ArrayList<Double>()
        var hits = 0
        var top3 = 0
        var relHits = 0
        var relPicks = 0
        // Over- and underachievers: (swing, league rank, key, predicted, final).
        var over: Pair<ScoreSwing, Pair<Int, Int>>? = null
        var flop: Pair<ScoreSwing, Pair<Int, Int>>? = null
        fun better(x: Pair<ScoreSwing, Pair<Int, Int>>?, s: ScoreSwing, gain: Int, rank: Int): Boolean {
            val cur = x ?: return true
            val (g, r) = cur.second
            return gain > g || gain == g && (rank < r || rank == r && s.key < cur.first.key)
        }
        for ((rank, id) in leagues(plan, drawn).withIndex()) {
            val p = prediction(plan, drawn, id, promoted) ?: continue
            val lg = season.leagues.byId(id) ?: continue
            val final = season.table(lg.top).map { it.key }
            val kind = HonourKind.of("L0", id)
            val champion = (titles.firstOrNull { it.kind == kind } ?: playoff.firstOrNull { it.kind == kind })?.key ?: final.first()
            val hit = p.favourites.first().key == champion
            val inTop = p.favourites.any { it.key == champion }
            val down = moves.filter { it.league == id && it.from == 0 && it.to > 0 }.map { it.key }.toSet()
            val rel = p.relegation.count { it.key in down }
            val n = final.size
            var d2 = 0.0
            for ((i, k) in final.withIndex()) {
                val pr = p.predicted(k) ?: continue
                val dd = (pr - (i + 1)).toDouble()
                d2 += dd * dd
                val gain = pr - (i + 1)
                if (gain > 0 && better(over, ScoreSwing(k, pr, i + 1), gain, rank)) over = ScoreSwing(k, pr, i + 1) to (gain to rank)
                if (gain < 0 && better(flop, ScoreSwing(k, pr, i + 1), -gain, rank)) flop = ScoreSwing(k, pr, i + 1) to (-gain to rank)
            }
            val rho = if (n < 2) 0.0 else 1.0 - 6.0 * d2 / (n.toDouble() * (n.toDouble() * n - 1))
            rhos += rho
            if (hit) hits++
            if (inTop) top3++
            relHits += rel
            relPicks += p.relegation.size
            per += LeagueScorecard(id, hit, inTop, rel, Math.round(rho * 100).toInt())
        }
        if (per.isEmpty()) return null
        return PredictionScorecard(
            plan.number, hits, per.size, top3, relHits, relPicks, Math.round(rhos.average() * 100).toInt(), over?.first, flop?.first, per,
        )
    }

    /**
     * The close's `prediction_scorecard` (design ENG 11.2) of [sc]: keys [overachiever, flop] ("-"
     * for none); args the favourites who won, the leagues, the overachiever's predicted and final
     * places, the flop's ("-" for none), the champions among the favourites, the relegation hits
     * and picks, ρ × 100. Week [SeasonCalendar.CLOSING].
     */
    fun scorecardEvent(sc: PredictionScorecard): LeagueEvent = LeagueEvent(
        "prediction_scorecard", sc.season, listOf(sc.over?.key ?: "-", sc.flop?.key ?: "-"),
        listOf(
            sc.hits.toString(), sc.leagues.toString(), sc.over?.predicted?.toString() ?: "-", sc.over?.final?.toString() ?: "-",
            sc.flop?.predicted?.toString() ?: "-", sc.flop?.final?.toString() ?: "-", sc.top3.toString(), sc.relHits.toString(), sc.relPicks.toString(), sc.rho.toString(),
        ),
        null, SeasonCalendar.CLOSING,
    )
}

// ---- the press votes (기자단 투표, design ENG 5.4) -----------------------------------------------

/**
 * A journalist of the press jury (기자단): number [index] of the jury, from league [league] and its
 * nation [nation] (a 연합 리그's largest; null when none is known), with its tastes: the weight of
 * a candidate's rating, titles and statistics, and of a candidate of its own nation.
 */
data class Journalist(
    val index: Int, val league: String, val nation: String?,
    val aRating: Double, val aTitles: Double, val aStats: Double, val aHome: Double,
    /** Its stream's seed `mix(seed, 0xB0A7, season, index)`: its tastes are drawn first, each award's noise from `mix(this, award)`. */
    val seed: Long = 0L,
)

/**
 * The press jury of a season (design ENG 5.4): [SEATS] journalists for each of the [TOP] best-ranked
 * leagues and one for each of the [NEXT] after them (40 from 24 leagues; fewer with fewer), each
 * with its tastes drawn from its own stream `mix(seed, 0xB0A7, season, j)`.
 */
object PressJury {
    const val TOP = 8
    const val SEATS = 3
    const val NEXT = 16

    /** The jury of [leagues] (best-ranked first) for [season], seeded by [seed]; [nationOf] gives a league's nation. */
    fun of(leagues: List<String>, season: Int, seed: Long, nationOf: (String) -> String?): List<Journalist> {
        val out = ArrayList<Journalist>()
        for ((i, id) in leagues.take(TOP + NEXT).withIndex()) {
            val nation = nationOf(id)
            repeat(if (i < TOP) SEATS else 1) {
                val j = out.size
                val own = yacht.engine.DiceStream.mix(seed, 0xB0A7L, season.toLong(), j.toLong())
                val rng = java.util.Random(own)
                out += Journalist(
                    j, id, nation, 0.6 + 0.8 * rng.nextDouble(), 0.6 + 0.8 * rng.nextDouble(), 0.4 + 0.6 * rng.nextDouble(), 0.2 + 0.4 * rng.nextDouble(), own,
                )
            }
        }
        return out
    }

    /**
     * The jury of [plan]'s season ([Media.leagues] order by [drawn], seed [seed]): a national league's
     * journalists are of its nation, a 연합 리그's of the nation most of its members are from
     * ([nation]; then [Nations.ALL] order).
     */
    fun of(plan: SeasonPlan, drawn: (String) -> Double, nation: (String) -> String?, seed: Long = plan.seed): List<Journalist> {
        val season = plan.league
        fun nationOf(id: String): String? {
            if (Nations.of(id) != null) return id
            val lg = season.leagues.byId(id) ?: return null
            val counts = HashMap<String, Int>()
            for (d in lg.divisions) for (k in season.divisions[d]) nation(k)?.takeIf { Nations.of(it) != null }?.let { counts.merge(it, 1, Int::plus) }
            return counts.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { LeagueIds.order(it.key) })?.key
        }
        return of(Media.leagues(plan, drawn), plan.number, seed, ::nationOf)
    }
}

/**
 * What the press votes read at the close (design ENG 5.4), right after [StatsBook.endSeason] (its
 * awards, league MVPs and 평점 are known) and before the season's honours are added: the closing
 * [plan], the season's [honours] (titles, cups and awards, the "LMVP.<league>" among them), the
 * ratings at the close ([rating]) and those the season was drawn with ([drawn]), each member's mean
 * 평점 this season ([avgRating], NaN for none), the power ranking's weeks at #1 ([NewsDesk.weeksTop]),
 * each member's nation, and the national clubs before their off-season ([clubs]; without a club
 * world there is no 올해의 감독 and no 올해의 영입). Members deleted during the season ([gone]) stand
 * for nothing, as for the season's awards.
 */
class PressInput(
    val plan: SeasonPlan,
    val honours: List<Honour>,
    val rating: (String) -> Double,
    val drawn: (String) -> Double,
    val avgRating: (String) -> Double = { Double.NaN },
    val weeksTop: Map<String, Int> = emptyMap(),
    val nation: (String) -> String? = { null },
    val clubs: ClubBook? = null,
    val seed: Long = plan.seed,
    val gone: Set<String> = emptySet(),
)

/**
 * The three press awards of a season (design ENG 5.4): 골든 다이스 ([PressAwards.GOLDEN_DICE]),
 * 올해의 감독 ([PressAwards.COACH]) and 올해의 영입 ([PressAwards.SIGNING]). Each journalist ranks the
 * candidates by `u = a_rating × z(rating) + a_titles × titles + a_stats × z(stat) + (golden dice:
 * 0.5 × [LMVP] + 0.3 × z(weeks at #1)) + a_home × [same nation] + 0.2 × [same league] + ε`, ε
 * Gumbel(0, 0.5) drawn in candidate key order from the journalist's stream for the award; its top 3
 * get 5, 3 and 1 points. A tally lists every candidate, the winner first: most points, then
 * first-place votes, then rating (a coach's reputation), then key. Pure: the same input gives the
 * same tallies (the close stores them in stats.txt, `pv`).
 */
object PressVotes {
    /** Candidates an award at most, and the best-rated added to the golden dice's. */
    const val CANDIDATES = 12
    const val BEST_RATED = 5

    /** League MVPs of this many best-ranked leagues stand for the golden dice. */
    const val MVP_LEAGUES = 12

    /** Coaches of the club-table champions of the [Media.TOP_LEAGUES] best-ranked leagues, and this many above their club's expected place. */
    const val OVERACHIEVERS = 5

    /** The last off-season's biggest fees standing for 올해의 영입. */
    const val SIGNINGS = 10

    /** A ballot's points, first place first. */
    val POINTS = intArrayOf(5, 3, 1)

    /** The honour kinds whose holders stand for the golden dice (a 대륙 챔피언스 "CCL.<confed>" by its base). */
    private val HOLDERS = setOf("CL", "CCL", "CUP", "WCW")

    private const val LMVP_WEIGHT = 0.5
    private const val WEEKS_WEIGHT = 0.3
    private const val LEAGUE_WEIGHT = 0.2
    private const val NOISE = 0.5

    /** A candidate: its three numbers (rating, titles, statistic), the golden dice's extra, its nation and league, and its tie-break. */
    private class Candidate(val key: String, val rating: Double, val titles: Double, val stat: Double, val extra: Double, val nation: String?, val league: String?, val tie: Double)

    /** Every award's tally of [input]'s season, in [PressAwards.AWARDS] order; an award without candidates or without a jury has none. */
    fun vote(input: PressInput): List<PressTally> {
        val plan = input.plan
        val jury = PressJury.of(plan, input.drawn, input.nation, input.seed)
        if (jury.isEmpty()) return emptyList()
        val order = Media.leagues(plan, input.drawn)
        val top = order.take(Media.TOP_LEAGUES).toSet()
        val season = plan.number
        val mine = input.honours.filter { it.season == season }
        // A season's major titles of a player (a national league's title counts in the best-ranked leagues).
        val majors = HashMap<String, Int>()
        for (h in mine) if (HonourBook.isMajor(h) { _, lg -> lg in top }) majors.merge(h.key, 1, Int::plus)
        val out = ArrayList<PressTally>()
        tally(season, PressAwards.GOLDEN_DICE, 1, jury, golden(input, order, mine, majors))?.let { out += it }
        input.clubs?.takeIf { it.world != null }?.let { book ->
            val tables = ClubTable.all(plan.league, book, mine)
            tally(season, PressAwards.COACH, 2, jury, coaches(input, book, order, tables, mine))?.let { out += it }
            tally(season, PressAwards.SIGNING, 3, jury, signings(input, book, tables, majors))?.let { out += it }
        }
        return out
    }

    /** The golden dice's candidates: league MVPs of the [MVP_LEAGUES] best-ranked leagues, the CL / CCL / CUP / WCW holders, the [BEST_RATED] best-rated; at most [CANDIDATES], the best-rated of them. */
    private fun golden(input: PressInput, order: List<String>, mine: List<Honour>, majors: Map<String, Int>): List<Candidate> {
        val season = input.plan.league
        val members = season.standings.keys - input.gone
        val mvpLeagues = order.take(MVP_LEAGUES).toSet()
        val mvps = mine.filter { HonourKind.base(it.kind) == Awards.LMVP && it.key in members }.map { it.key }.toSet()
        val keys = LinkedHashSet<String>()
        keys += mine.filter { HonourKind.base(it.kind) == Awards.LMVP && HonourKind.leagueOf(it.kind) in mvpLeagues && it.key in members }.map { it.key }
        keys += mine.filter { HonourKind.base(it.kind) in HOLDERS && it.key in members }.map { it.key }
        keys += members.sortedWith(compareByDescending<String> { input.rating(it).takeIf { r -> r.isFinite() } ?: Double.NEGATIVE_INFINITY }.thenBy { it }).take(BEST_RATED)
        val picked = keys.sortedWith(compareByDescending<String> { input.rating(it).takeIf { r -> r.isFinite() } ?: Double.NEGATIVE_INFINITY }.thenBy { it }).take(CANDIDATES)
        val ratings = z(picked.map { input.rating(it) })
        val stats = z(picked.map { input.avgRating(it) })
        val weeks = z(picked.map { (input.weeksTop[it] ?: 0).toDouble() })
        return picked.mapIndexed { i, k ->
            Candidate(
                k, ratings[i], (majors[k] ?: 0).toDouble(), stats[i], LMVP_WEIGHT * (if (k in mvps) 1 else 0) + WEEKS_WEIGHT * weeks[i],
                input.nation(k), season.leagueOf(k)?.id, input.rating(k),
            )
        }
    }

    /**
     * 올해의 감독's candidates: the coaches of the [Media.TOP_LEAGUES] best-ranked leagues' club-table
     * champions, the [OVERACHIEVERS] with their club furthest above its expected place
     * ([Expectation.clubs] from the drawn ratings), the 챔피언스 리그 winner's coach. Titles: its club
     * won its club table, the CL winner played for it; statistic: expected − final place.
     */
    private fun coaches(input: PressInput, book: ClubBook, order: List<String>, tables: Map<String, List<ClubStanding>>, mine: List<Honour>): List<Candidate> {
        val plan = input.plan
        val home = homes(book, tables)
        val expected = tables.keys.associateWith { id -> runCatching { Expectation.clubs(plan, book, id, input.drawn) }.getOrDefault(emptyList()).map { it.first } }
        val gap = HashMap<String, Int>()
        for ((club, at) in home) {
            val e = expected[at.first]?.indexOf(club) ?: -1
            if (e >= 0) gap[club] = (e + 1) - at.second
        }
        val champions = order.take(Media.TOP_LEAGUES).mapNotNull { id -> tables[id]?.firstOrNull()?.club?.takeIf { home[it]?.first == id } }
        val cl = mine.firstOrNull { it.kind == "CL" }?.key?.let { book.clubOf(it) }
        val clubs = LinkedHashSet<String>()
        clubs += champions
        clubs += gap.entries.filter { it.value > 0 }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenComparing({ it.key }, ClubBook.ID_ORDER)).take(OVERACHIEVERS).map { it.key }
        cl?.let { clubs += it }
        val titles = HashMap<String, Int>()
        for ((club, at) in home) if (at.second == 1) titles.merge(club, 1, Int::plus)
        cl?.let { titles.merge(it, 1, Int::plus) }
        val coached = clubs.mapNotNull { c -> book.coachOf(c)?.let { it to c } }.distinctBy { it.first.id }
            .sortedWith(compareByDescending<Pair<Coach, String>> { titles[it.second] ?: 0 }.thenByDescending { gap[it.second] ?: 0 }.thenBy { it.first.id }).take(CANDIDATES)
        val reps = z(coached.map { it.first.reputation.toDouble() })
        val gaps = z(coached.map { (gap[it.second] ?: 0).toDouble() })
        return coached.mapIndexed { i, (coach, club) ->
            Candidate(coach.id, reps[i], (titles[club] ?: 0).toDouble(), gaps[i], 0.0, coach.nation, home[club]?.first, coach.reputation.toDouble())
        }
    }

    /**
     * Each club's own club table of [tables] (its nation's league's when that lists it, else the
     * first that does): the league and its place there (from 1).
     */
    private fun homes(book: ClubBook, tables: Map<String, List<ClubStanding>>): Map<String, Pair<String, Int>> {
        val out = HashMap<String, Pair<String, Int>>()
        for ((id, table) in tables) for ((i, row) in table.withIndex()) {
            if (row.club !in out || book.nationOf(row.club) == id) out[row.club] = id to i + 1
        }
        return out
    }

    /**
     * 올해의 영입's candidates: the [SIGNINGS] biggest fees of the last off-season's notable transfers
     * (`tx` of the season before, no winter move) of members still in the league. Rating: its change
     * since the season was drawn; statistic: its new club's places gained in its own club table on
     * last season ([placesGained]).
     */
    private fun signings(input: PressInput, book: ClubBook, tables: Map<String, List<ClubStanding>>, majors: Map<String, Int>): List<Candidate> {
        val plan = input.plan
        val season = plan.league
        val members = season.standings.keys - input.gone
        val moves = book.notable.filter { it.season == plan.number - 1 && it.type != 'W' && it.fee > 0 && it.to != null && it.key in members }
            .sortedWith(compareByDescending<NotableMove> { it.fee }.thenBy { it.key }).distinctBy { it.key }.take(SIGNINGS)
        if (moves.isEmpty()) return emptyList()
        val home = homes(book, tables)
        val last = book.places(plan.number - 1).associate { it.league to it.clubs }
        val rise = moves.map { m -> input.rating(m.key) - input.drawn(m.key) }
        val gains = moves.map { m -> gained(m.to!!, home[m.to], last).toDouble() }
        val rz = z(rise)
        val gz = z(gains)
        return moves.mapIndexed { i, m ->
            Candidate(m.key, rz[i], (majors[m.key] ?: 0).toDouble(), gz[i], 0.0, input.nation(m.key), season.leagueOf(m.key)?.id, input.rating(m.key))
        }
    }

    /**
     * Club [club]'s places gained on last season in its own club table of [tables] ([homes]), from
     * season [previous]'s best clubs of that table (the `cs` line, which keeps a table's best 3): its
     * place there, or, not among them, from below them (a club now among the best 3 rose at least
     * to its place from the 4th); 0 when not known (no such table last season, or neither season
     * among the best).
     */
    internal fun placesGained(book: ClubBook, tables: Map<String, List<ClubStanding>>, previous: Int, club: String): Int =
        gained(club, homes(book, tables)[club], book.places(previous).associate { it.league to it.clubs })

    /** [placesGained] with [home] (the club's table and place now) and [last] (each table's best clubs last season). */
    private fun gained(club: String, home: Pair<String, Int>?, last: Map<String, List<String>>): Int {
        val (league, now) = home ?: return 0
        val best = last[league] ?: return 0
        val was = best.indexOf(club)
        return when {
            was >= 0 -> was + 1 - now
            now <= best.size -> best.size + 1 - now
            else -> 0
        }
    }

    /** z-scores among [xs] (a value that is not a number, or every value alike, scores 0). */
    private fun z(xs: List<Double>): DoubleArray {
        val ok = xs.filter { it.isFinite() }
        if (ok.size < 2) return DoubleArray(xs.size)
        val mean = ok.average()
        val sd = kotlin.math.sqrt(ok.sumOf { (it - mean) * (it - mean) } / ok.size)
        return DoubleArray(xs.size) { i -> if (!xs[i].isFinite() || sd < 1e-12) 0.0 else (xs[i] - mean) / sd }
    }

    /** Gumbel(0, [scale]) from [rng]. */
    internal fun gumbel(rng: java.util.Random, scale: Double): Double {
        val u = rng.nextDouble().coerceIn(1e-12, 1 - 1e-12)
        return -scale * kotlin.math.ln(-kotlin.math.ln(u))
    }

    /** [award]'s tally by [jury] over [cands] (a journalist's noise from `mix(its seed, salt)`, the award's number); null without candidates. */
    private fun tally(season: Int, award: String, salt: Int, jury: List<Journalist>, cands: List<Candidate>): PressTally? {
        if (cands.isEmpty()) return null
        val byKey = cands.sortedBy { it.key }
        val points = HashMap<String, Int>()
        val firsts = HashMap<String, Int>()
        for (j in jury) {
            val rng = java.util.Random(yacht.engine.DiceStream.mix(j.seed, salt.toLong()))
            val u = byKey.map { c ->
                var x = j.aRating * c.rating + j.aTitles * c.titles + j.aStats * c.stat + c.extra
                if (j.nation != null && c.nation == j.nation) x += j.aHome
                if (c.league != null && c.league == j.league) x += LEAGUE_WEIGHT
                c.key to x + gumbel(rng, NOISE)
            }
            val ballot = u.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(POINTS.size)
            for ((i, b) in ballot.withIndex()) points.merge(b.first, POINTS[i], Int::plus)
            ballot.firstOrNull()?.let { firsts.merge(it.first, 1, Int::plus) }
        }
        val tie = cands.associate { it.key to it.tie }
        val rows = cands.map { PressVoteRow(it.key, points[it.key] ?: 0, firsts[it.key] ?: 0) }.sortedWith(
            compareByDescending<PressVoteRow> { it.points }.thenByDescending { it.firsts }
                .thenByDescending { tie[it.key]?.takeIf { r -> r.isFinite() } ?: Double.NEGATIVE_INFINITY }.thenBy { it.key },
        )
        return PressTally(season, award, jury.size, rows)
    }

    /** The honours the press votes give (design ENG 5.4): GD and SIGN to their winners (올해의 감독 gives none). */
    fun honours(tallies: List<PressTally>): List<Honour> =
        tallies.filter { it.award in PressAwards.KINDS }.mapNotNull { t -> t.rows.firstOrNull()?.let { Honour(t.season, t.award, it.key) } }

    /**
     * The close's news (design ENG 11.2): `press_award` [the golden dice's top 3] (points, first-place
     * votes, jurors, the 2nd's points, the 3rd's) and one `press_awards` [올해의 감독's club or "-", 올해의
     * 영입's winner or "-"] (the coach's id) when either was voted. [clubOf] gives a coach's club. Week
     * [SeasonCalendar.CLOSING].
     */
    fun events(tallies: List<PressTally>, clubOf: (String) -> String? = { null }): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        tallies.firstOrNull { it.award == PressAwards.GOLDEN_DICE }?.takeIf { it.rows.isNotEmpty() }?.let { t ->
            val top = t.rows.take(3)
            out += LeagueEvent(
                "press_award", t.season, top.map { it.key },
                listOf(top[0].points.toString(), top[0].firsts.toString(), t.jury.toString(), top.getOrNull(1)?.points?.toString() ?: "-", top.getOrNull(2)?.points?.toString() ?: "-"),
                null, SeasonCalendar.CLOSING,
            )
        }
        val coach = tallies.firstOrNull { it.award == PressAwards.COACH }?.rows?.firstOrNull()?.key
        val signing = tallies.firstOrNull { it.award == PressAwards.SIGNING }?.rows?.firstOrNull()?.key
        if (coach != null || signing != null) {
            val season = tallies.first().season
            out += LeagueEvent("press_awards", season, listOf(coach?.let(clubOf) ?: "-", signing ?: "-"), listOf(coach ?: "-"), null, SeasonCalendar.CLOSING)
        }
        return out
    }
}

// ---- the World Cup host vote and the draw ceremony (design ENG 5.5) ----------------------------

/** A nation bidding to host a World Cup (월드컵 유치 신청), with its bid's strength [score] (b). */
data class WcBid(val nation: String, val score: Double)

/**
 * What the World Cup host vote reads (design ENG 5.5): the season before a World Cup ([plan]; its
 * winter announces the bids, its close holds the vote), every honour so far ([history]: the hosts,
 * "WCH"), the ratings the season was drawn with, each member's nation, and the national clubs before
 * their off-season ([clubs]: the stadiums; none, every capacity 0).
 */
class WcInput(
    val plan: SeasonPlan,
    val history: List<Honour>,
    val drawn: (String) -> Double,
    val nation: (String) -> String?,
    val clubs: ClubBook? = null,
    val seed: Long = plan.seed,
)

/**
 * 월드컵 개최지 투표 and 조 추첨식 (design ENG 5.5). The bids: today's rotation picks the
 * confederation (`(hosts so far + c) % 4`, the last [RECENT] hosts out; a confederation with no
 * nation left passes to the next), and of its nations with a team the [BIDS] best by `b = 0.4 z(the
 * league's coefficient) + 0.3 z(its [STADIUMS] biggest stadiums) + 0.2 z(team strength) + 0.1 ×
 * min(1, seasons since it hosted / 16) + ε` (Gumbel 0.3) bid. Every nation with a team votes, by
 * `b + 0.6 × [same confederation] − 0.8 × [a rivalry] + ε` (Gumbel 0.5), a candidate for itself;
 * rounds of first preferences until a majority, the last (fewest votes, then lower b) dropping, at
 * most [MAX_ROUNDS]. Pure: the winter and the close (or the close replayed) find the same bids.
 */
object WcHosts {
    const val BIDS = 4
    const val RECENT = 3
    const val STADIUMS = 3
    const val MAX_ROUNDS = 4

    /** Seasons since a nation hosted that count in full. */
    const val WAIT_SEASONS = 16

    private const val W_COEFFICIENT = 0.4
    private const val W_STADIUMS = 0.3
    private const val W_STRENGTH = 0.2
    private const val W_WAIT = 0.1
    private const val BID_NOISE = 0.3
    private const val SAME_CONFED = 0.6
    private const val RIVAL = 0.8
    private const val VOTE_NOISE = 0.5

    /** The World Cup season [plan]'s close votes for: the next, when a World Cup is due then; null otherwise. */
    fun voteSeason(plan: SeasonPlan): Int? = (plan.number + 1).takeIf { plan.config.nationCup && plan.league.leagues.national && NationCup.worldDue(it) }

    /** The nations with a team (5 members or more), their best 5 by the drawn ratings, strongest first (as [NationCup.teams]). */
    fun teams(input: WcInput): List<NationTeam> {
        val byNation = HashMap<String, MutableList<String>>()
        for (k in input.plan.league.standings.keys) {
            val n = input.nation(k)?.takeIf { Nations.of(it) != null } ?: continue
            byNation.getOrPut(n) { ArrayList() } += k
        }
        fun r(k: String) = input.drawn(k).takeIf { it.isFinite() } ?: LeagueWorld.START_RATING
        return byNation.filterValues { it.size >= NationCup.SQUAD }.map { (n, keys) ->
            val squad = keys.sortedWith(compareByDescending<String> { r(it) }.thenBy { it }).take(NationCup.SQUAD)
            NationTeam(n, squad, squad.map(::r).average())
        }.sortedWith(compareByDescending<NationTeam> { it.strength }.thenBy { it.id })
    }

    /** The bids for the World Cup after [input]'s season, strongest first; empty when none is due or no nation can bid. */
    fun bids(input: WcInput): List<WcBid> {
        val plan = input.plan
        val wc = voteSeason(plan) ?: return emptyList()
        val teams = teams(input)
        if (teams.size < NationCup.MIN_TEAMS) return emptyList()
        val hosts = input.history.filter { it.kind == "WCH" && it.season < wc }.sortedBy { it.season }
        val recent = hosts.takeLast(RECENT).map { it.key }.toSet()
        for (c in 0 until Confed.entries.size) {
            val confed = Confed.entries[(hosts.size + c) % Confed.entries.size]
            val pool = teams.filter { Nations.of(it.id)?.confed == confed && it.id !in recent }.sortedBy { it.id }
            if (pool.isEmpty()) continue
            val k = zs(pool.map { coefficient(plan, input.nation, it.id) })
            val cap = zs(pool.map { capacity(input.clubs, it.id).toDouble() })
            val str = zs(pool.map { it.strength })
            val rng = java.util.Random(yacht.engine.DiceStream.mix(input.seed, 0x3C40L, wc.toLong()))
            return pool.mapIndexed { i, t ->
                val last = hosts.lastOrNull { it.key == t.id }?.season
                val wait = if (last == null) 1.0 else minOf(1.0, (wc - last).toDouble() / WAIT_SEASONS)
                WcBid(t.id, W_COEFFICIENT * k[i] + W_STADIUMS * cap[i] + W_STRENGTH * str[i] + W_WAIT * wait + PressVotes.gumbel(rng, BID_NOISE))
            }.sortedWith(compareByDescending<WcBid> { it.score }.thenBy { it.nation }).take(BIDS)
        }
        return emptyList()
    }

    /** League coefficient of nation [n]'s league (its own, else the one most of its members play in) in [plan]'s ranking; 0 unranked. */
    private fun coefficient(plan: SeasonPlan, nation: (String) -> String?, n: String): Double {
        val season = plan.league
        val id = season.leagues.byId(n)?.id ?: run {
            val counts = HashMap<String, Int>()
            for (k in season.standings.keys) if (nation(k) == n) season.leagueOf(k)?.id?.let { counts.merge(it, 1, Int::plus) }
            counts.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { LeagueIds.order(it.key) })?.key
        } ?: return 0.0
        return plan.ranking.of(id)?.coefficient ?: 0.0
    }

    /** Seats of nation [n]'s [STADIUMS] biggest stadiums (0 without a club world). */
    private fun capacity(clubs: ClubBook?, n: String): Int =
        clubs?.clubsOf(n)?.map { c -> clubs.life(c.id)?.stadium?.capacity ?: 0 }?.sortedDescending()?.take(STADIUMS)?.sum() ?: 0

    private fun zs(xs: List<Double>): DoubleArray {
        if (xs.size < 2) return DoubleArray(xs.size)
        val mean = xs.average()
        val sd = kotlin.math.sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
        return DoubleArray(xs.size) { i -> if (sd < 1e-12) 0.0 else (xs[i] - mean) / sd }
    }

    /**
     * The host vote for the World Cup after [input]'s season ([WcVote]: each round's votes, most
     * first; no round when a single nation bid, elected unopposed); null when no nation bids (the
     * cup's host then comes by today's rule, [NationCup.world]).
     */
    fun vote(input: WcInput): WcVote? {
        val bids = bids(input)
        if (bids.isEmpty()) return null
        val wc = voteSeason(input.plan) ?: return null
        if (bids.size == 1) return WcVote(wc, bids[0].nation, emptyList())
        val b = bids.associate { it.nation to it.score }
        val candidates = bids.map { it.nation }.sorted()
        val voters = teams(input).map { it.id }.sorted()
        val prefs = voters.mapIndexed { i, v ->
            val rng = java.util.Random(yacht.engine.DiceStream.mix(input.seed, 0x3C41L, wc.toLong(), i.toLong()))
            val conf = Nations.of(v)?.confed
            val ranked = candidates.map { c ->
                var u = b.getValue(c)
                if (conf != null && Nations.of(c)?.confed == conf) u += SAME_CONFED
                if (c != v && Nations.rivalry(v, c) != null) u -= RIVAL
                c to u + PressVotes.gumbel(rng, VOTE_NOISE)
            }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).map { it.first }
            if (v in b) listOf(v) + ranked.filter { it != v } else ranked
        }
        val left = bids.map { it.nation }.toMutableList()
        val rounds = ArrayList<List<Pair<String, Int>>>()
        while (rounds.size < MAX_ROUNDS) {
            val counts = HashMap<String, Int>()
            for (p in prefs) p.firstOrNull { it in left }?.let { counts.merge(it, 1, Int::plus) }
            val round = left.map { it to (counts[it] ?: 0) }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { b.getValue(it.first) }.thenBy { it.first })
            rounds += round
            if (2 * round[0].second > round.sumOf { it.second } || left.size == 1) break
            left.remove(round.last().first)
        }
        return WcVote(wc, rounds.last()[0].first, rounds)
    }

    /**
     * The winter's `wc_bids` [bidding nations] (the World Cup's season) of closing season [season]
     * for the World Cup after it, in week [week]; null without bids.
     */
    fun bidsEvent(season: Int, bids: List<WcBid>, week: Int = SeasonCalendar.NONE): LeagueEvent? {
        if (bids.isEmpty()) return null
        return LeagueEvent("wc_bids", season, bids.map { it.nation }, listOf((season + 1).toString()), Comp.WORLD_CUP, week)
    }

    /**
     * The close's `wc_host_vote` [host, runner-up or "-"] (rounds, the host's votes in the last
     * round, the runner-up's; "-" for an unopposed host) of closing season [season]. Week
     * [SeasonCalendar.CLOSING].
     */
    fun voteEvent(season: Int, v: WcVote): LeagueEvent {
        val last = v.rounds.lastOrNull().orEmpty()
        val runner = last.getOrNull(1)
        return LeagueEvent(
            "wc_host_vote", season, listOf(v.host, runner?.first ?: "-"),
            listOf(v.rounds.size.toString(), last.firstOrNull()?.second?.toString() ?: "-", runner?.second?.toString() ?: "-"), Comp.WORLD_CUP, SeasonCalendar.CLOSING,
        )
    }

    /**
     * The draw ceremony's presenter (추첨자) for host [host]: its legend with the most titles (then
     * honours, best rating, key), else its best-rated retired player; null when it has neither.
     */
    fun presenter(host: String?, legends: List<Legend>, retired: List<RetiredRecord>): String? {
        host ?: return null
        fun best(r: Double) = r.takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY
        legends.filter { it.record.nation == host }.maxWithOrNull(
            compareBy<Legend> { it.record.titles }.thenBy { Evolution.counts(it.honours).values.sum() }.thenBy { best(it.record.bestRating) }.thenByDescending { it.key },
        )?.let { return it.key }
        return retired.filter { it.nation == host && !it.bestRating.isNaN() }.maxWithOrNull(compareBy<RetiredRecord> { it.bestRating }.thenByDescending { it.key })?.key
    }

    /**
     * World Cup [cup]'s draw ceremony (`wcc`): its groups as drawn (A first, each in pot order), the
     * pots ([NationCup.drawGroups]' pots: the p-th of every group, by seed), the group of death (죽음의
     * 조: the highest mean team rating, as the save rounds it; ties: the lower group) and [presenter].
     * Null for a continental cup. The same from a cup read back from season.txt.
     */
    fun ceremony(cup: NationCup, presenter: String?): WcCeremony? {
        if (cup.comp != Comp.WORLD_CUP || cup.groups.isEmpty()) return null
        val letters = cup.groups.indices.map { ('A' + it).toString() }
        val groups = cup.groups.mapIndexed { g, es -> letters[g] to es.map { it.key } }
        val pots = (0 until cup.groups.maxOf { it.size }).map { p -> cup.groups.mapNotNull { it.getOrNull(p) }.sortedBy { it.seed }.map { it.key } }
        fun mean(es: List<DrawEntry>): Double =
            es.filter { !it.rating.isNaN() }.map { Math.round(it.rating).toDouble() }.takeIf { it.isNotEmpty() }?.average() ?: Double.NEGATIVE_INFINITY
        val death = cup.groups.indices.maxWithOrNull(compareBy<Int> { mean(cup.groups[it]) }.thenByDescending { it }) ?: 0
        return WcCeremony(cup.season, presenter, letters[death], groups, pots)
    }

    /** The open's `wc_draw_ceremony` [presenter or "-", the group of death's nations] (its letter). Week [SeasonCalendar.PRESEASON]. */
    fun ceremonyEvent(c: WcCeremony): LeagueEvent = LeagueEvent(
        "wc_draw_ceremony", c.season, listOf(c.presenter ?: "-") + c.groups.firstOrNull { it.first == c.death }?.second.orEmpty().take(4),
        listOf(c.death), Comp.WORLD_CUP, SeasonCalendar.PRESEASON,
    )
}

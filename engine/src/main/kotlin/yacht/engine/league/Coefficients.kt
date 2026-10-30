package yacht.engine.league

import java.util.TreeMap

/**
 * Where the league coefficients (리그 계수) are kept: each closed national season's raw sums per
 * league, the points its 챔피언스 리그 and 대륙 챔피언스 entrants earned and their entries
 * ([Coefficients.season]). The 요트협회 keeps them in federation.txt; [MemoryCoefficients] keeps
 * them in memory.
 */
interface CoefficientStore {
    /** Season [season]'s sums, league id to [points, entries]; replaces what that season had. */
    fun putCoefficients(season: Int, values: Map<String, IntArray>)

    /**
     * Season [season]'s coefficient of league [league]: its points per entry, 0.0 with no
     * entries; null when that season has no line for it (the league did not exist then, the
     * season was not a national one, or it is older than the store keeps).
     */
    fun coefficient(league: String, season: Int): Double?
}

/** A [CoefficientStore] in memory: the newest [Coefficients.KEEP] seasons. */
class MemoryCoefficients : CoefficientStore {
    private val seasons = TreeMap<Int, Map<String, IntArray>>()

    override fun putCoefficients(season: Int, values: Map<String, IntArray>) {
        seasons[season] = values.mapValues { it.value.copyOf() }
        while (seasons.size > Coefficients.KEEP) seasons.remove(seasons.firstKey())
    }

    override fun coefficient(league: String, season: Int): Double? = sums(league, season)?.let(Coefficients::value)

    /** Season [season]'s [points, entries] of league [league]; null without a line. */
    fun sums(league: String, season: Int): IntArray? = seasons[season]?.get(league)?.copyOf()

    /** The seasons kept, oldest first. */
    val stored: List<Int> get() = seasons.keys.toList()
}

/**
 * League coefficients (리그 계수, docs/LEAGUE_SPEC.md): each league is credited with the points
 * its entrants of the season's [International] competitions earn ([International.coefficientPoints]:
 * group matches, knockout series, how far they got, a champions league place), the entrant's
 * league being the one it plays in that season. A season's value is the points per entry (a
 * player in both counts twice); the ranking adds the values of the newest [SEASONS] closed
 * seasons and sets next season's places: [cclSlots] in each confederation's 대륙 챔피언스, a
 * place straight into the 챔피언스 리그 for the [LeagueConfig.clDirect] best.
 */
object Coefficients {
    /** Seasons the ranking adds up. */
    const val SEASONS = 5

    /** Seasons a store keeps. */
    const val KEEP = 10

    /** Points of an entrant: a group match won or drawn. */
    const val GROUP_WIN = 2
    const val GROUP_DRAW = 1

    /** A knockout series won (on either side of a double-elimination bracket). */
    const val SERIES_WIN = 2

    /** Reaching the knockout, the last four, the final; the title. */
    const val KNOCKOUT = 1
    const val LAST_FOUR = 1
    const val FINAL = 1
    const val TITLE = 1

    /** A place in the 챔피언스 리그. */
    const val CL_ENTRY = 2

    /** Points per entry of [points, entries]; 0.0 with no entries. */
    fun value(sums: IntArray): Double = if (sums[1] <= 0) 0.0 else sums[0].toDouble() / sums[1]

    /**
     * Points for how far a knockout side got: [place] as [Knockout.places] gives it (0 the
     * champion, 1 the runner-up, 2 the last four, ...); 0 for null (still in it or never there).
     */
    fun placePoints(place: Int?): Int {
        if (place == null) return 0
        var p = 0
        if (place <= 2) p += LAST_FOUR
        if (place <= 1) p += FINAL
        if (place == 0) p += TITLE
        return p
    }

    /**
     * Season [plan]'s sums, league id to [points, entries], for every league of the season (a
     * league without entrants has [0, 0]); empty for a legacy season. Read at the close (and on
     * request mid-season: points so far).
     */
    fun season(plan: SeasonPlan): Map<String, IntArray> {
        val leagues = plan.league.leagues
        if (!leagues.national) return emptyMap()
        val out = LinkedHashMap<String, IntArray>()
        for (lg in leagues.list) out[lg.id] = IntArray(2)
        for (c in plan.comps) {
            val i = c as? International ?: continue
            val points = i.coefficientPoints()
            for (k in i.entrantKeys()) {
                val sums = plan.league.leagueOf(k)?.let { out[it.id] } ?: continue
                sums[0] += points[k] ?: 0
                sums[1] += 1
            }
        }
        return out
    }

    /**
     * League [id]'s value in season [season], inherited when the season has no line for it: a
     * nation's league takes its 연합 리그's (it played in it), a 연합 리그 the mean of its
     * confederation's nation leagues (they had leagues of their own); else 0.
     */
    fun inherited(store: CoefficientStore, id: String, season: Int): Double {
        store.coefficient(id, season)?.let { return it }
        val confed = LeagueIds.confedOf(id) ?: return 0.0
        return when {
            LeagueIds.isRegion(id) -> Nations.ALL.filter { it.confed == confed }.mapNotNull { store.coefficient(it.code, season) }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
            else -> store.coefficient(LeagueIds.region(confed), season) ?: 0.0
        }
    }

    /** League [id]'s values of the [SEASONS] seasons up to [closed], newest first (seasons from 1). */
    fun values(store: CoefficientStore, id: String, closed: Int): List<Double> =
        (closed downTo maxOf(1, closed - SEASONS + 1)).map { inherited(store, id, it) }

    /** The average rating of the best 5 of [keys]; NaN without any. */
    fun strength(keys: Collection<String>, rating: (String) -> Double): Double =
        keys.map(rating).filter { !it.isNaN() }.sortedDescending().take(STRENGTH).takeIf { it.isNotEmpty() }?.average() ?: Double.NaN

    private const val STRENGTH = 5

    /**
     * The places of the league ranked [rank] (0-based) of a confederation's [n] in its section of
     * [size]: an even share, one more for the first `size % n` (16 among 6: 3, 3, 3, 3, 2, 2).
     */
    fun cclSlots(n: Int, rank: Int, size: Int): Int = if (n <= 0 || size <= 0) 0 else size / n + if (rank < size % n) 1 else 0

    /**
     * [ids] best first by [ranking] (its rows' order), the leagues it has no row for after them by
     * [strength] (strongest first), then by id.
     */
    fun order(ids: Collection<String>, ranking: LeagueRanking, strength: (String) -> Double): List<String> {
        val s = ids.associateWith { strength(it).let { x -> if (x.isNaN()) Double.NEGATIVE_INFINITY else x } }
        return ids.sortedWith(compareBy<String> { ranking.rank(it) }.thenByDescending { s.getValue(it) }.thenBy { it })
    }

    /**
     * The league ranking after season [closed] of [leagues]' national leagues: by the sum of the
     * newest [SEASONS] values (with inheritance), then the newest value, then [strength] (the
     * average rating of a league's best 5, [Coefficients.strength]), then id. With no values yet
     * (the first national season) the ranking is by strength. [LeagueRank.change] compares with
     * the ranking the same leagues had after season [closed] - 1.
     */
    fun ranking(store: CoefficientStore, leagues: Leagues, closed: Int, strength: (String) -> Double, config: LeagueConfig): LeagueRanking {
        val ids = leagues.list.filter { it.national }.map { it.id }
        if (ids.isEmpty()) return LeagueRanking(closed, emptyList())
        val strengths = ids.associateWith { strength(it).let { x -> if (x.isNaN()) Double.NEGATIVE_INFINITY else x } }
        fun ranked(at: Int): List<Pair<String, List<Double>>> = ids.map { it to values(store, it, at) }
            .sortedWith(
                compareByDescending<Pair<String, List<Double>>> { it.second.sum() }
                    .thenByDescending { it.second.firstOrNull() ?: 0.0 }
                    .thenByDescending { strengths.getValue(it.first) }
                    .thenBy { it.first },
            )
        val now = ranked(closed)
        val before = ranked(closed - 1).withIndex().associate { (i, r) -> r.first to i }
        val confedOf = ids.associateWith { leagues.confedOf(it) }
        val inConfed = HashMap<Confed?, Int>()
        val counts = confedOf.values.groupingBy { it }.eachCount()
        val rows = now.mapIndexed { rank, (id, values) ->
            val c = confedOf[id]
            val confedRank = inConfed.getOrDefault(c, 0).also { inConfed[c] = it + 1 }
            val n = counts.getValue(c)
            val slots = if (!config.continentalCl || c == null) 0 else cclSlots(n, confedRank, ContinentalChampions.sectionSize(n))
            LeagueRank(
                id, rank, confedRank, values.sum(), values, strength(id), slots, rank < config.clDirect, before.getValue(id) - rank, c,
            )
        }
        return LeagueRanking(closed, rows)
    }
}

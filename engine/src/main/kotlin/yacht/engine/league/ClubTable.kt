package yacht.engine.league

/**
 * Club [club]'s line in the club table of league [league]: [points] = [leaguePart] + [bonus] of
 * its [counted] members (its best [ClubTable.COUNTED] by member score), rounded to 0.1;
 * [members] in all, and the league points its members won in the 1부 ([topTierPoints], the
 * first tie-break).
 */
data class ClubStanding(
    val club: String,
    val league: String,
    val points: Double,
    /** The counted members' league points, each weighted by its tier ([ClubTable.TIER_WEIGHT]). */
    val leaguePart: Double,
    /** The counted members' honour points ([ClubTable.bonusOf]). */
    val bonus: Double,
    /** Its best members by score, best first. */
    val counted: List<String>,
    val members: Int,
    val topTierPoints: Int,
)

/**
 * The club table of a league (F1 constructors' style, design ENG 3.4): a member scores its league
 * points times its tier's weight, plus points for its honours; a club the sum of its best 6, so a
 * club with more members gains nothing by size (clubs are within ±25% of each other). A league's
 * clubs are those of the nations its members' clubs belong to; a member abroad scores for its
 * (foreign) club. Ties: more [ClubStanding.topTierPoints], then the best member score, then id.
 * Built on request (O(members of the league)), never per frame.
 */
object ClubTable {
    /** League points' weight by tier: 1부 1.0, 2부 0.6, ... 6부 and below 0.2. */
    val TIER_WEIGHT = doubleArrayOf(1.0, 0.6, 0.4, 0.3, 0.25, 0.2)

    /** Members counted per club. */
    const val COUNTED = 6

    /** A lower tier's title ("L1.KR", ... and the old single league's "L1" and below). */
    const val LOWER_TITLE = 8

    /** Honour points by [HonourKind.base]; a kind not listed scores none. */
    val BONUS: Map<String, Int> = mapOf(
        "L0" to 20, "REG" to 5, "L0R" to 10, "CUP" to 15, "CUP2" to 8, "CL" to 30, "CL2" to 15,
        "CCL" to 20, "CCL2" to 10, "SUPER" to 5, "LMVP" to 10, "MVP" to 15, "SCORER" to 5, "YACHT" to 5, "WCW" to 5,
    )

    /** Points of an honour of [kind] for its holder's club. */
    fun bonusOf(kind: String): Int =
        if (HonourKind.isDivision(kind) && (HonourKind.tier(kind) ?: 0) >= 1) LOWER_TITLE else BONUS[HonourKind.base(kind)] ?: 0

    /** A tier's weight ([TIER_WEIGHT], the last for every tier below). */
    fun weight(tier: Int): Double = TIER_WEIGHT[tier.coerceIn(0, TIER_WEIGHT.size - 1)]

    /**
     * The club table of league [leagueId] in [season], best first, with [clubs]' members as they
     * are and [honours] (during the season those of competitions already finished; at the close
     * all of them). Empty for a league the season does not have.
     *
     * With [through] (a week, design ENG 3.4) the members' league points are those of the
     * fixtures through that week only ([Season.pointsThrough]); null: the live table. [splits] are
     * the season's winter moves (`ws`): a member who moved scores its points up to the move for its
     * old club and the rest (and its honours) for its new one, and both may count it among their
     * best [COUNTED]. [deductions] take a percentage off a club's points (법정관리, 재정 규정; never the
     * members' league points). With the defaults the table is today's.
     */
    fun of(
        season: Season,
        clubs: ClubBook,
        leagueId: String,
        honours: List<Honour>,
        through: Int? = null,
        splits: List<WinterMove> = emptyList(),
        deductions: Map<String, Int> = emptyMap(),
    ): List<ClubStanding> {
        val lg = season.leagues.byId(leagueId) ?: return emptyList()
        val bonus = HashMap<String, Int>()
        for (h in honours) bonusOf(h.kind).takeIf { it != 0 }?.let { bonus.merge(h.key, it, Int::plus) }
        val cut = through?.let { season.pointsThrough(it - 1) }
        // A member's league points: the live standing's, or those through the week; null when it has none.
        fun pointsOf(k: String): Int? = if (cut == null) season.standings[k]?.points else cut[k]?.get(Season.THROUGH_POINTS)
        val moved = HashMap<String, WinterMove>()
        val left = HashMap<String, MutableList<WinterMove>>()
        for (m in splits) if (m.season == season.number && moved.putIfAbsent(m.key, m) == null) left.getOrPut(m.from) { ArrayList() } += m
        val nations = LinkedHashSet<String>()
        for (d in lg.divisions) for (k in season.divisions[d]) clubs.clubOf(k)?.let { clubs.nationOf(it) }?.let { nations += it }
        val out = ArrayList<Pair<ClubStanding, Double>>()
        for (nation in nations) for (c in clubs.clubsOf(nation)) {
            var topTier = 0
            val all = ArrayList<Score>(c.squad.size)
            fun add(k: String, p: Int?, honour: Int) {
                val d = season.divisionOf(k)?.takeIf { it in lg.divisions }
                val tier = d?.let { season.leagues.tier(it) }
                if (tier == 0) topTier += p ?: 0
                all += Score(k, if (p == null || tier == null) 0.0 else p * weight(tier), honour)
            }
            // The members now (one who came in the winter: its points since the move), then those
            // who left it in the winter (their points up to the move, no honours).
            for (k in c.squad) {
                val split = moved[k]?.takeIf { it.to == c.id }
                add(k, pointsOf(k)?.let { if (split == null) it else it - minOf(it, split.points) }, bonus[k] ?: 0)
            }
            for (m in left[c.id].orEmpty()) if (m.key !in c.squad) add(m.key, pointsOf(m.key)?.let { minOf(it, m.points) }, 0)
            val scores = all.sortedWith(compareByDescending<Score> { it.total }.thenBy { it.key })
            val counted = scores.take(COUNTED)
            val league = counted.sumOf { it.league }
            val extra = counted.sumOf { it.bonus }.toDouble()
            val cutBy = deductions[c.id] ?: 0
            val total = if (cutBy > 0) (league + extra) * (100 - cutBy) / 100.0 else league + extra
            out += ClubStanding(c.id, leagueId, round(total), round(league), extra, counted.map { it.key }, c.squad.size, topTier) to (counted.firstOrNull()?.total ?: 0.0)
        }
        return out.sortedWith(
            compareByDescending<Pair<ClubStanding, Double>> { it.first.points }.thenByDescending { it.first.topTierPoints }
                .thenByDescending { it.second }.thenComparing({ it.first.club }, ClubBook.ID_ORDER),
        ).map { it.first }
    }

    /** Every league's club table of [season], in league order ([of]'s cut, splits and deductions for each). */
    fun all(
        season: Season,
        clubs: ClubBook,
        honours: List<Honour>,
        through: Int? = null,
        splits: List<WinterMove> = emptyList(),
        deductions: Map<String, Int> = emptyMap(),
    ): Map<String, List<ClubStanding>> =
        season.leagues.list.associate { it.id to of(season, clubs, it.id, honours, through, splits, deductions) }

    /** A member's tier-weighted league points and honour points. */
    private class Score(val key: String, val league: Double, val bonus: Int) {
        val total: Double get() = league + bonus
    }

    /** Rounded to 0.1. */
    private fun round(x: Double): Double = Math.round(x * 10) / 10.0
}

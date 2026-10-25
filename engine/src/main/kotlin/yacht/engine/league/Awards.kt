package yacht.engine.league

/**
 * A member of the hall of fame (명예의 전당), as it was when inducted (it may retire later).
 * [reason]: "TITLES" (3 titles), "MVP" (MVP twice) or "GAMES" (5000 games at 55% or better).
 */
data class HallOfFameEntry(
    val key: String,
    val name: String,
    val season: Int,
    val reason: String,
    val titles: Int,
    val games: Int,
    val winRate: Double,
)

/**
 * A dynasty for the records room (기록실). [kind] "RUN": [count] titles of [title] (an honour
 * kind, e.g. "L0" or "CUP") in a row, seasons [from]..[to]; "DOUBLE" (1부 and cup), "TREBLE"
 * (1부, cup and champions league) and "UNBEATEN" (a league season without a lost match,
 * [title] its division) happen in one season, [count] being the holder's how-many-th.
 */
data class Dynasty(val key: String, val kind: String, val title: String, val count: Int, val from: Int, val to: Int)

/** What [StatsBook.endSeason] made of a season. */
class SeasonEnd(
    val season: Int,
    /** MVP, ROOKIE and MIP: add them together with the season's other honours. */
    val awards: List<Honour>,
    /** "award", "record", "milestone", "hall_of_fame" and "dynasty" news. */
    val events: List<LeagueEvent>,
    val inducted: List<HallOfFameEntry>,
    /** Dynasties started or extended this season. */
    val dynasties: List<Dynasty>,
    /** The season review (시즌 결산), also kept in the archive until shown. */
    val review: SeasonReview?,
)

/** The season awards, the hall of fame and dynasties (docs/LEAGUE_SPEC.md section 3). */
object Awards {
    /** Share of its league fixtures an MVP must have played. */
    const val MVP_SHARE = 0.6

    /** Matches for the season rating record. */
    const val RATING_MATCHES = 20
    const val HOF_TITLES = 3
    const val HOF_GAMES = 5000
    const val HOF_WIN_RATE = 0.55
    const val HOF_MVPS = 2

    /** League matches an unbeaten season needs. */
    const val UNBEATEN_MIN = 5

    /** Awards made here (득점왕 and 최다 요트상 come from [Offseason.honours]). */
    val KINDS = setOf("MVP", "ROOKIE", "MIP")

    /** Awards announced as news. */
    val ANNOUNCED = KINDS + setOf("SCORER", "YACHT")
    private val DIVISION = Regex("L\\d")
    private val AI_TITLES = setOf("CUP", "CL", "SUPER")
    private val TEAM_TITLES = setOf("ALLSTAR", "FAMILY", "CLUB")

    /** A title an AI wins: a division, the cup, the champions league, the super cup. */
    fun isAiTitle(kind: String): Boolean = kind in AI_TITLES || DIVISION.matches(kind)

    /** Any title, team competitions included (their honours are keyed by the team). */
    fun isTitle(kind: String): Boolean = isAiTitle(kind) || kind in TEAM_TITLES

    /** What a season summary keeps: titles, runners-up, the regular season and the awards (not moves or squads). */
    fun inSummary(kind: String): Boolean = isTitle(kind) || kind == "REG" || kind == "CUP2" || kind == "CL2" || kind in ANNOUNCED

    /**
     * MVP: the best average match rating in division 1 among AIs that played at least
     * [MVP_SHARE] of their league fixtures (the rating over every official game of the season).
     */
    fun mvp(plan: SeasonPlan, stats: (String) -> AiStats?): String? {
        val league = plan.league
        val top = league.divisions.firstOrNull() ?: return null
        val fixtures = (top.size - 1) * if (league.double) 2 else 1
        return top.mapNotNull { k ->
            val s = league.standings[k] ?: return@mapNotNull null
            val r = stats(k)?.avgRating ?: Double.NaN
            if (r.isNaN() || s.played < MVP_SHARE * fixtures || s.played == 0) null else Triple(k, r, s.points)
        }.maxWithOrNull(compareBy<Triple<String, Double, Int>> { it.second }.thenBy { it.third })?.first
    }

    /**
     * 신인왕: the best rated AI in its first season. None when most of the league is new (the
     * first season), where it would only name the strongest AI.
     */
    fun rookie(keys: Collection<String>, isRookie: (String) -> Boolean, rating: (String) -> Double): String? {
        val rookies = keys.filter(isRookie)
        if (rookies.isEmpty() || 2 * rookies.size > keys.size) return null
        return rookies.filter { !rating(it).isNaN() }.maxByOrNull { rating(it) }
    }

    /** 기량발전상: the largest rating gain from the season's start to its end (a gain, not a smaller loss). */
    fun mip(keys: Collection<String>, start: (String) -> Double, rating: (String) -> Double): String? =
        keys.mapNotNull { k ->
            val d = rating(k) - start(k)
            if (d.isNaN() || d <= 0) null else k to d
        }.maxByOrNull { it.second }?.first

    /** Why an AI belongs in the hall of fame, or null: 3 titles, MVP twice, or 5000 games at 55%. */
    fun hallOfFame(titles: Int, mvps: Int, career: AiStats?): String? = when {
        titles >= HOF_TITLES -> "TITLES"
        mvps >= HOF_MVPS -> "MVP"
        career != null && career.games >= HOF_GAMES && career.winRate >= HOF_WIN_RATE -> "GAMES"
        else -> null
    }

    /**
     * Dynasties of season [n] from its honours ([now]) and the earlier seasons' ([history]):
     * titles kept from the title's previous edition (a run starts at two in a row; the family
     * cup is held every third season, a competition may be off for a while), doubles and trebles
     * of the 1부 champion, unbeaten league seasons. [existing] are the dynasties so far (runs to
     * extend, counts). The all-star game is an exhibition between two picked teams: no runs.
     */
    fun dynasties(n: Int, now: List<Honour>, history: List<Honour>, league: Season, existing: List<Dynasty>): List<Dynasty> {
        val out = ArrayList<Dynasty>()
        for (h in now.distinctBy { it.kind to it.key }) {
            if (!isTitle(h.kind) || h.kind == "ALLSTAR") continue
            val last = history.filter { it.kind == h.kind && it.season < n }.maxOfOrNull { it.season } ?: continue
            if (history.none { it.season == last && it.kind == h.kind && it.key == h.key }) continue
            // A run already taken to this season (the season closed again) stays as it is.
            val run = existing.firstOrNull { it.kind == RUN && it.title == h.kind && it.key == h.key && (it.to == last || it.to == n) }
            out += when {
                run == null -> Dynasty(h.key, RUN, h.kind, 2, last, n)
                run.to == n -> run
                else -> run.copy(count = run.count + 1, to = n)
            }
        }
        fun nth(key: String, kind: String) = existing.count { it.key == key && it.kind == kind && it.to < n } + 1
        val champion = now.firstOrNull { it.kind == "L0" }?.key
        if (champion != null && now.any { it.kind == "CUP" && it.key == champion }) {
            val kind = if (now.any { it.kind == "CL" && it.key == champion }) TREBLE else DOUBLE
            out += Dynasty(champion, kind, "L0", nth(champion, kind), n, n)
        }
        for (d in league.divisions.indices) {
            for (s in league.table(d)) {
                if (s.played >= UNBEATEN_MIN && s.losses == 0) out += Dynasty(s.key, UNBEATEN, "L$d", nth(s.key, UNBEATEN), n, n)
            }
        }
        return out
    }

    const val RUN = "RUN"
    const val DOUBLE = "DOUBLE"
    const val TREBLE = "TREBLE"
    const val UNBEATEN = "UNBEATEN"
}

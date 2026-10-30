package yacht.engine.league

/**
 * A member of the hall of fame (명예의 전당), as it was when inducted (it may retire later).
 * [reason]: "TITLES" (3 titles; major titles once the leagues are national), "MVP" (MVP twice)
 * or "GAMES" (5000 games at 55% or better).
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
 * kind, e.g. "L0", "L0.KR" or "CUP") in a row, seasons [from]..[to]; "DOUBLE" (1부 and cup,
 * [title] the league's title kind), "TREBLE" (1부, cup and the champions league or a 대륙
 * 챔피언스) and "UNBEATEN" (a league season without a lost match, [title] its division: "L2",
 * "L1.KR") happen in one season, [count] being the holder's how-many-th.
 */
data class Dynasty(val key: String, val kind: String, val title: String, val count: Int, val from: Int, val to: Int)

/** What [StatsBook.endSeason] made of a season. */
class SeasonEnd(
    val season: Int,
    /** MVP, ROOKIE and MIP (and each national league's LMVP): add them together with the season's other honours. */
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

    /** Awards made here (득점왕 and 최다 요트상 come from [Offseason.honours]); a national season adds [LMVP]s. */
    val KINDS = setOf("MVP", "ROOKIE", "MIP")

    /** A national league's MVP, "LMVP.<league>" ([leagueMvps]); not news (one a league). */
    const val LMVP = "LMVP"

    /** Awards announced as news. */
    val ANNOUNCED = KINDS + setOf("SCORER", "YACHT")

    // By HonourKind.base: "CCL.ASIA" is a CCL.
    private val AI_TITLES = setOf("CUP", "CL", "SUPER", "WCW", "CCL")
    private val TEAM_TITLES = setOf("ALLSTAR", "FAMILY", "CLUB", "WC", "CC", "CLUBCUP")

    /** An award made here: [KINDS] and a national league's [LMVP]. */
    fun isAward(kind: String): Boolean = kind in KINDS || HonourKind.base(kind) == LMVP

    /** A title an AI wins: a division of any tier and league, the cup, the champions league, a 대륙 챔피언스, the super cup. */
    fun isAiTitle(kind: String): Boolean = HonourKind.base(kind) in AI_TITLES || HonourKind.isDivision(kind)

    /** Any title, team competitions included (their honours are keyed by the team, the 클럽 대항전's by the club). */
    fun isTitle(kind: String): Boolean = isAiTitle(kind) || HonourKind.base(kind) in TEAM_TITLES

    /**
     * What a season summary keeps: titles, runners-up, the regular season and the awards (not
     * moves or squads). Of a national league only its title ("L0.KR"): its regular season, its
     * title playoff's runner-up, its lower tiers and its MVP stay in honours.txt.
     */
    fun inSummary(kind: String): Boolean {
        if (HonourKind.leagueOf(kind) != null) return HonourKind.isTopTitle(kind)
        return (isTitle(kind) && kind != "WCW") || kind == "REG" || kind == "CUP2" || kind == "CL2" || HonourKind.base(kind) == "CCL2" ||
            kind == "WC2" || kind == "WCMVP" || kind == "WCTOP" || kind in ANNOUNCED
    }

    /**
     * MVP: the best average match rating in division 1 among AIs that played at least
     * [MVP_SHARE] of their league fixtures (the rating over every official game of the season).
     * A national season's (올해의 선수): of the league MVPs ([leagueMvps]) the one with the highest
     * [rating] at the season's end (the ladder is world-wide, so ratings compare across leagues
     * where match ratings do not), then the better match rating, then the first league.
     */
    fun mvp(plan: SeasonPlan, stats: (String) -> AiStats?): String? = mvp(plan, { Double.NaN }, stats)

    /** [mvp] with each player's ladder [rating] at the season's end (a national season's MVP is chosen by it). */
    fun mvp(plan: SeasonPlan, rating: (String) -> Double, stats: (String) -> AiStats?): String? {
        val league = plan.league
        if (league.leagues.national) {
            fun r(k: String) = rating(k).takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY
            fun m(k: String) = stats(k)?.avgRating?.takeIf { !it.isNaN() } ?: Double.NEGATIVE_INFINITY
            return leagueMvps(plan, stats).values.maxWithOrNull(compareBy<String> { r(it) }.thenBy { m(it) })
        }
        val top = league.divisions.firstOrNull() ?: return null
        val fixtures = (top.size - 1) * if (league.double) 2 else 1
        return best(league, top, stats) { fixtures }
    }

    /**
     * Each national league's MVP (리그 MVP, honour "LMVP.<league>"), in league order: the best
     * average match rating in its 1부 among the players who played at least [MVP_SHARE] of their
     * own scheduled league fixtures ([Season.fixturesOf]: in a division of an odd size a player
     * sits out a round of each round robin), level on rating the more points. Empty for a legacy
     * season (its MVP is [mvp]'s).
     */
    fun leagueMvps(plan: SeasonPlan, stats: (String) -> AiStats?): Map<String, String> {
        val league = plan.league
        val out = LinkedHashMap<String, String>()
        if (!league.leagues.national) return out
        for (lg in league.leagues.list) {
            val top = league.divisions.getOrNull(lg.top) ?: continue
            best(league, top, stats) { league.fixturesOf(it) }?.let { out[lg.id] = it }
        }
        return out
    }

    /** The best average match rating of [keys] that played [MVP_SHARE] of [fixtures] or more (level: more points, then the first). */
    private fun best(league: Season, keys: List<String>, stats: (String) -> AiStats?, fixtures: (String) -> Int): String? = keys.mapNotNull { k ->
        val s = league.standings[k] ?: return@mapNotNull null
        val r = stats(k)?.avgRating ?: Double.NaN
        if (r.isNaN() || s.played < MVP_SHARE * fixtures(k) || s.played == 0) null else Triple(k, r, s.points)
    }.maxWithOrNull(compareBy<Triple<String, Double, Int>> { it.second }.thenBy { it.third })?.first

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

    /**
     * Why an AI belongs in the hall of fame, or null: 3 titles, MVP twice, or 5000 games at 55%.
     * [titles] are a legacy league's every title, a national league's major titles ([HonourBook.majorTitles]).
     */
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
     * of a league's champion, unbeaten league seasons. [existing] are the dynasties so far (runs to
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
        // A league champion ("L0", or "L0.KR" of a national league) who also won the cup; with
        // the champions league or a 대륙 챔피언스 too, a treble.
        for (top in now.filter { HonourKind.isTopTitle(it.kind) }.distinctBy { it.key }) {
            val champion = top.key
            if (now.none { it.kind == "CUP" && it.key == champion }) continue
            val kind = if (now.any { it.key == champion && (it.kind == "CL" || HonourKind.base(it.kind) == "CCL") }) TREBLE else DOUBLE
            out += Dynasty(champion, kind, top.kind, nth(champion, kind), n, n)
        }
        val leagues = league.leagues
        for (d in league.divisions.indices) {
            for (s in league.table(d)) {
                if (s.played >= UNBEATEN_MIN && s.losses == 0) {
                    out += Dynasty(s.key, UNBEATEN, HonourKind.of("L${leagues.tier(d)}", leagues.of(d).id), nth(s.key, UNBEATEN), n, n)
                }
            }
        }
        return out
    }

    const val RUN = "RUN"
    const val DOUBLE = "DOUBLE"
    const val TREBLE = "TREBLE"
    const val UNBEATEN = "UNBEATEN"
}

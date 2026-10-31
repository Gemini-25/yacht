package yacht.engine.league

/**
 * The club rankings (세계 클럽 랭킹, design ENG 1b.9), built on request from the club book: by club
 * value, by results (a 5-season club coefficient), the richest (last season's income), the fans and
 * the financial health, and each close's top 10 kept in `rk` lines for the news, the rank moves and
 * the encyclopedia.
 */
object ClubRanks {
    /** Place points of a season's club table: 10, 7, 5, 4, 3, 2, then 1. */
    val PLACE_POINTS = intArrayOf(10, 7, 5, 4, 3, 2)
    const val PLACE_REST = 1

    /** Each list's length in an `rk` line, and the seasons of lines kept. */
    const val TOP = 10
    const val KEEP = 30

    /** Points of club-table place [place] (1 = champion); 0 for none. */
    fun placePoints(place: Int): Int = if (place <= 0) 0 else PLACE_POINTS.getOrNull(place - 1) ?: PLACE_REST

    /**
     * A season's result points of a club: `placePoints(place) × λ + intl / 2`, [intl] the coefficient
     * points its members earned for it in the 챔피언스 리그 and the 대륙 챔피언스 (as
     * [Coefficients.season] credits them).
     */
    fun resultPoints(place: Int, lambda: Double, intl: Int): Double = placePoints(place) * lambda + maxOf(0, intl) / 2.0

    /** Club [id]'s results score: its result points over the seasons its money history keeps (5; 3 below 500 members). */
    fun results(book: ClubBook, id: String): Double = book.world?.economy?.results?.get(id)?.values?.sum()?.div(10.0) ?: 0.0

    /**
     * The rankings of every club of [book] with a club world state (gone clubs left out), best first
     * and ties by id: value (members valued by [value]), results, rich (operating income of the last
     * accounts, or the money history's), fans and health (grade A first, then the higher balance), with
     * the clubs of each grade A..E. Empty lists without a club world.
     */
    fun of(book: ClubBook, value: (String) -> Int): ClubRankings {
        val w = book.world ?: return ClubRankings(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), List(5) { 0 })
        val ids = w.clubs.keys.filter { book.club(it) != null }
        fun <T : Comparable<T>> ranked(f: (String) -> T): List<Pair<String, T>> =
            ids.map { it to f(it) }.sortedWith(compareByDescending<Pair<String, T>> { it.second }.thenComparing({ it.first }, ClubBook.ID_ORDER))
        val health = ids.map { it to (ClubEconomy.health(book, it) ?: 'C') }
            .sortedWith(compareBy<Pair<String, Char>> { it.second }.thenByDescending { w.clubs.getValue(it.first).balance }.thenComparing({ it.first }, ClubBook.ID_ORDER))
        val grades = "ABCDE".map { g -> health.count { it.second == g } }
        return ClubRankings(
            ranked { ClubEconomy.clubValue(book, it, value) ?: 0 },
            ranked { results(book, it) },
            ranked { ClubEconomy.income(book, it) },
            ranked { w.clubs.getValue(it).fans },
            health,
            grades,
        )
    }

    /**
     * The close's `rk` line of [season] (the club off-season's step 13): the top [TOP] by value and by
     * results now, and the record fee of each of the best-ranked leagues ([leagueRecords], the market's);
     * replaces the season's line, keeps [KEEP] seasons. Nothing without a club world.
     */
    fun record(book: ClubBook, season: Int, value: (String) -> Int, leagueRecords: Map<String, Int> = emptyMap()): RankLine? {
        val w = book.world ?: return null
        val r = of(book, value)
        val line = RankLine(season, r.value.take(TOP).map { it.first }, r.results.take(TOP).map { it.first }, LinkedHashMap(leagueRecords))
        val ranks = w.economy.ranks
        ranks[season] = line
        while (ranks.size > KEEP) ranks.pollFirstEntry()
        book.touch()
        return line
    }

    /** The newest `rk` line before season [season] (the one a rank move compares with). */
    fun previous(book: ClubBook, season: Int): RankLine? = book.world?.economy?.ranks?.lowerEntry(season)?.value

    /**
     * The move (▲ positive, ▼ negative) of club [club] at index [now] of a list against the same list
     * of an `rk` line ([before]: its top 10): null when it was not in it (new in the top 10) or is not in
     * the top 10 now.
     */
    fun move(before: List<String>, club: String, now: Int): Int? {
        if (now !in 0 until TOP) return null
        val was = before.indexOf(club).takeIf { it >= 0 } ?: return null
        return was - now
    }

    /** `club_ranking` [top by value, top by results] (its value) for the `rk` line of a close; none without one. */
    fun events(line: RankLine?, value: (String) -> Int?): List<LeagueEvent> {
        val top = line?.value?.firstOrNull() ?: return emptyList()
        return listOf(LeagueEvent("club_ranking", line.season, listOfNotNull(top, line.results.firstOrNull()), listOf((value(top) ?: 0).toString()), Comp.CLUB))
    }
}

/** The club rankings of this book with members valued by [value] ([ClubRanks.of]; design ENG 1b.9). */
fun ClubBook.rankings(value: (String) -> Int): ClubRankings = ClubRanks.of(this, value)

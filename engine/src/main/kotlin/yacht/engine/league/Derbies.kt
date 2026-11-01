package yacht.engine.league

/**
 * Two clubs the rules make rivals (design ENG 1b.6), before each club's two are chosen: [kind] 'N'
 * national classic, 'R' regional derby, 'H' history, 'T' transfer rivalry, from season [season]'s
 * events; [why] what made it: C the nation's two biggest cities, R a region's, P the top two of a
 * club table twice, F a 클럽 대항전 final, X a record fee between them, K a captain's move.
 */
data class RivalLink(val a: String, val b: String, val kind: Char, val season: Int, val why: Char)

/**
 * Who plays a rival match this season (라이벌전, design ENG 1b.6): each club's counted members (its
 * [ClubTable.COUNTED] best by the season's drawn ratings, so a fixture's flag stays the same all
 * season) and the book's rival pairs. Built by [Derbies.matches] once a season (or on a new
 * [ClubBook.version]); asked per fixture.
 */
class RivalMatches internal constructor(private val book: ClubBook, private val counted: Set<String>) {
    private val names = HashMap<String, String?>()

    /** The rivalry's name when [a] and [b] are counted members of two rival clubs, in any competition; else null. */
    fun of(a: String, b: String): String? {
        if (a !in counted || b !in counted) return null
        val ca = book.clubOf(a) ?: return null
        val cb = book.clubOf(b) ?: return null
        if (ca == cb) return null
        return names.getOrPut(ClubHistoryBook.pairKey(ca, cb)) { if (book.isRival(ca, cb)) Derbies.name(book, ca, cb) else null }
    }

    /** [of] for a 1부 league fixture of [league] (both players in one division of the top tier); else null. */
    fun name(league: Season, a: String, b: String): String? {
        val d = league.divisionOf(a) ?: return null
        if (league.divisionOf(b) != d || league.leagues.tier(d) != 0) return null
        return of(a, b)
    }
}

/**
 * Rivals and derbies (design ENG 1b.6): the rivalries of each club (≤ 2, recomputed at each close),
 * the all-time season series of every rival pair (`dr`, folded at the close from the season's own
 * results), this season's series on request, the rival matches (a 1부 league match between counted
 * members of rival clubs, [rivalName]) and the weekly `derby_result` pick. Every function does
 * nothing without a club world.
 */
object Derbies {
    const val CLASSIC = 'N'
    const val REGIONAL = 'R'
    const val HISTORY = 'H'
    const val TRANSFER = 'T'

    /** Rivals a club keeps; a history or transfer rivalry fades after [FADE] seasons without a meeting of members. */
    const val MAX = 2
    const val FADE = 10

    /** A history rivalry: the top two of a club table [HISTORY_TIMES] times in the last [HISTORY_SEASONS] (or a 클럽 대항전 final then). */
    const val HISTORY_SEASONS = 5
    const val HISTORY_TIMES = 2

    /** A transfer rivalry: a world or league record fee, or a captain's move, between the clubs in the last [TRANSFER_SEASONS]. */
    const val TRANSFER_SEASONS = 3

    /** The leagues whose rival matches make news (best-ranked first), and the most `derby_result` articles a season. */
    const val NEWS_LEAGUES = 4
    const val SEASON_RESULTS = 6

    /** The kinds, strongest first: a club with more candidates keeps the strongest. */
    private const val PRIORITY = "NRHT"

    /** The competitions whose matches count in a series: the league, its playoffs and the cups between players (not the team cups, friendlies, legends or the lab). */
    private val OFFICIAL = setOf(Comp.LEAGUE, Comp.CUP, Comp.CHAMPIONS, Comp.CONT_CHAMPIONS, Comp.SUPER, Comp.TITLE_PLAYOFF, Comp.PROMOTION_PLAYOFF)

    private fun priority(kind: Char): Int = PRIORITY.indexOf(kind).let { if (it < 0) PRIORITY.length else it }

    // ---- rivalries ----------------------------------------------------------------------------------

    /**
     * Every rivalry the rules give [book]'s clubs at the close of [season] (unchosen: a club may have
     * more than [MAX]): per nation the classic of its two biggest cities' clubs (by their cities'
     * order, [ClubEconomy.cityIndex]); per region of [ClubIdentity.REGIONS] its two biggest clubs
     * (unless they are the classic's); a history rivalry for the top two of a league's club table in
     * [HISTORY_TIMES] of the last [HISTORY_SEASONS] seasons (the `cs` lines) or the two finalists of a
     * 클럽 대항전 then ([honours]); a transfer rivalry for each of [links] and of [transferLinks] within
     * the last [TRANSFER_SEASONS]. Only clubs of the book, best kind first.
     */
    fun candidates(book: ClubBook, season: Int, honours: List<Honour> = emptyList(), links: List<RivalLink> = emptyList()): List<RivalLink> {
        val clubs = book.clubs
        val ids = clubs.mapTo(HashSet()) { it.id }
        val out = ArrayList<RivalLink>()
        val cityOrder = compareBy<Club> { ClubEconomy.cityIndex(it) }.thenComparing({ it.id }, ClubBook.ID_ORDER)
        for ((nation, cs) in clubs.groupBy { it.nation }) {
            val big = cs.sortedWith(cityOrder)
            val classic = if (big.size >= 2) ClubHistoryBook.pairKey(big[0].id, big[1].id) else null
            if (classic != null) out += link(big[0].id, big[1].id, CLASSIC, season, 'C')
            for (r in ClubIdentity.REGIONS[nation].orEmpty()) {
                val two = cs.filter { it.city in r.cities }.sortedWith(cityOrder).take(2)
                if (two.size == 2 && ClubHistoryBook.pairKey(two[0].id, two[1].id) != classic) out += link(two[0].id, two[1].id, REGIONAL, season, 'R')
            }
        }
        val from = season - HISTORY_SEASONS + 1
        val tops = HashMap<String, MutableList<Int>>()
        for (x in from..season) for (p in book.places(x)) {
            if (p.clubs.size < 2 || p.clubs[0] !in ids || p.clubs[1] !in ids) continue
            tops.getOrPut(ClubHistoryBook.pairKey(p.clubs[0], p.clubs[1])) { ArrayList() } += x
        }
        for ((k, seasons) in tops.entries.sortedBy { it.key }) if (seasons.size >= HISTORY_TIMES) {
            val (a, b) = k.split(' ')
            out += link(a, b, HISTORY, seasons.max(), 'P')
        }
        val finals = honours.filter { it.season in from..season && HonourKind.base(it.kind) in FINALISTS }.groupBy { it.season }
        for ((x, hs) in finals.toSortedMap()) {
            val w = hs.firstOrNull { HonourKind.base(it.kind) == "CLUBCUP" }?.key
            val r = hs.firstOrNull { HonourKind.base(it.kind) == "CLUBCUP2" }?.key
            if (w != null && r != null && w != r && w in ids && r in ids) out += link(w, r, HISTORY, x, 'F')
        }
        for (l in links + transferLinks(book, season)) {
            if (l.season !in season - TRANSFER_SEASONS + 1..season || l.a == l.b || l.a !in ids || l.b !in ids) continue
            out += link(l.a, l.b, TRANSFER, l.season, l.why)
        }
        return out.sortedWith(compareBy<RivalLink> { priority(it.kind) }.thenByDescending { it.season }.thenBy { ClubHistoryBook.pairKey(it.a, it.b) })
    }

    private val FINALISTS = setOf("CLUBCUP", "CLUBCUP2")

    private fun link(a: String, b: String, kind: Char, season: Int, why: Char): RivalLink {
        val (x, y) = ClubHistoryBook.pair(a, b)
        return RivalLink(x, y, kind, season, why)
    }

    /**
     * The transfer links of [book]'s notable moves (`tx`) in the last [TRANSFER_SEASONS] up to
     * [season]: a world or league record fee (flags W, L) between two clubs of the book.
     */
    fun transferLinks(book: ClubBook, season: Int): List<RivalLink> =
        book.notable.filter { m -> m.season in season - TRANSFER_SEASONS + 1..season && m.from != null && m.to != null && m.from != m.to && ('W' in m.flags || 'L' in m.flags) }
            .map { link(it.from!!, it.to!!, TRANSFER, it.season, 'X') }

    /**
     * The rivals of [book]'s clubs recomputed at the close of [season] (design ENG 1b.6, the club
     * off-season's step 13; at the start of a club world with [start] the season in play): every
     * [candidates] pair and every history or transfer rivalry kept that has not faded ([FADE]
     * seasons since it began or its members last met), each pair its strongest kind, chosen
     * strongest first (then the older, then the pair's ids) while both clubs have fewer than [MAX];
     * a pair kept keeps its `since`, a new one starts at [start] with an empty all-time series (an
     * old series of the pair is kept). Clubs that are gone lose their rivals. The pairs that became
     * rivals now.
     */
    fun recompute(book: ClubBook, season: Int, honours: List<Honour> = emptyList(), links: List<RivalLink> = emptyList(), start: Int = season + 1): List<RivalLink> {
        val w = book.world ?: return emptyList()
        val h = w.history
        val ids = book.clubs.mapTo(HashSet()) { it.id }
        val old = HashMap<String, Rival>()
        for ((id, rs) in h.rivals) for (r in rs) old.putIfAbsent(ClubHistoryBook.pairKey(id, r.club), r)
        // The strongest claim of each pair: this close's candidates, then the history and transfer rivalries that have not faded.
        val best = LinkedHashMap<String, RivalLink>()
        for (c in candidates(book, season, honours, links)) best.putIfAbsent(ClubHistoryBook.pairKey(c.a, c.b), c)
        for ((k, r) in old.entries.sortedBy { it.key }) {
            val (a, b) = ClubHistoryBook.pair(k.substringBefore(' '), k.substringAfter(' '))
            if (a !in ids || b !in ids || (r.kind != HISTORY && r.kind != TRANSFER)) continue
            val met = maxOf(r.since, h.derbies[k]?.last ?: 0)
            if (season - met >= FADE) continue
            val now = best[k]
            if (now == null || priority(r.kind) < priority(now.kind)) best[k] = RivalLink(a, b, r.kind, r.since, r.kind)
        }
        fun since(k: String): Int = old[k]?.since ?: start
        val chosen = ArrayList<RivalLink>()
        val count = HashMap<String, Int>()
        for ((k, l) in best.entries.sortedWith(compareBy<Map.Entry<String, RivalLink>> { priority(it.value.kind) }.thenBy { since(it.key) }.thenBy { it.key })) {
            if ((count[l.a] ?: 0) >= MAX || (count[l.b] ?: 0) >= MAX) continue
            count.merge(l.a, 1, Int::plus)
            count.merge(l.b, 1, Int::plus)
            chosen += l.copy(season = since(k))
        }
        val next = HashMap<String, MutableList<Rival>>()
        for (l in chosen) {
            next.getOrPut(l.a) { ArrayList() } += Rival(l.b, l.kind, l.season)
            next.getOrPut(l.b) { ArrayList() } += Rival(l.a, l.kind, l.season)
            h.derbies.putIfAbsent(ClubHistoryBook.pairKey(l.a, l.b), DerbyRecord(l.a, l.b, 0, 0, 0, 0, l.season, 0))
        }
        val before = h.rivals.mapValues { it.value.toList() }
        h.rivals.clear()
        for ((id, rs) in next) h.rivals[id] = rs.sortedWith(compareBy<Rival> { priority(it.kind) }.thenBy { it.since }.thenComparing({ it.club }, ClubBook.ID_ORDER))
        if (before != h.rivals.mapValues { it.value.toList() }) book.touch()
        return chosen.filter { ClubHistoryBook.pairKey(it.a, it.b) !in old }
    }

    /**
     * The history and transfer rivalries born at the close of [season] (those whose `since` is the
     * next season), rebuilt from the book with what made them ([candidates]' reasons; [honours] and
     * [links] as they were given to [recompute]).
     */
    fun born(book: ClubBook, season: Int, honours: List<Honour> = emptyList(), links: List<RivalLink> = emptyList()): List<RivalLink> {
        val why = HashMap<String, Char>()
        for (c in candidates(book, season, honours, links)) why.putIfAbsent(ClubHistoryBook.pairKey(c.a, c.b), c.why)
        val out = ArrayList<RivalLink>()
        for ((id, rs) in book.world?.history?.rivals.orEmpty()) for (r in rs) {
            if (r.since != season + 1 || (r.kind != HISTORY && r.kind != TRANSFER) || ClubBook.ID_ORDER.compare(id, r.club) > 0) continue
            out += RivalLink(id, r.club, r.kind, r.since, why[ClubHistoryBook.pairKey(id, r.club)] ?: r.kind)
        }
        return out.sortedWith(compareBy<RivalLink> { priority(it.kind) }.thenBy { ClubHistoryBook.pairKey(it.a, it.b) })
    }

    /** `rivalry_born` [club, club] (why: P, F, X or K, [RivalLink.why]) for the first history or transfer rivalry of [born] (comp [Comp.CLUB]), ≤ 1. */
    fun events(season: Int, born: List<RivalLink>): List<LeagueEvent> =
        born.filter { it.kind == HISTORY || it.kind == TRANSFER }.take(1).map { LeagueEvent("rivalry_born", season, listOf(it.a, it.b), listOf(it.why.toString()), Comp.CLUB) }

    /**
     * The name of the rivalry of clubs [a] and [b] (null when they are not rivals): the classic
     * "{city}-{city} 클래식", the regional "{region} 더비", else "{city}-{city} 라이벌전", the bigger
     * city first (club names where the cities are the same or unknown).
     */
    fun name(book: ClubBook, a: String, b: String): String? {
        val kind = book.rivals(a).firstOrNull { it.club == b }?.kind ?: return null
        val ca = book.club(a) ?: return null
        val cb = book.club(b) ?: return null
        val (x, y) = if (compareBy<Club> { ClubEconomy.cityIndex(it) }.thenComparing({ it.id }, ClubBook.ID_ORDER).compare(ca, cb) <= 0) ca to cb else cb to ca
        val sameCity = x.city.isEmpty() || y.city.isEmpty() || x.city == y.city
        val pair = if (sameCity) "${x.name}-${y.name}" else "${x.city}-${y.city}"
        return when (kind) {
            CLASSIC -> "$pair 클래식"
            REGIONAL -> ClubIdentity.region(ca.nation, ca.city)?.let { "${it.name} 더비" } ?: "$pair 라이벌전"
            else -> "$pair 라이벌전"
        }
    }

    // ---- series -------------------------------------------------------------------------------------

    /**
     * The season series of [closing] folded into every rival pair's all-time record (design ENG
     * 1b.6, at the club off-season after the close): each played official match ([OFFICIAL], one-on-
     * one) between members of the two clubs counts once by its result, a member counting for the
     * club it played for that week (a winter mover for its old club up to the move's week), and so
     * does a 클럽 대항전 match between the two clubs (by its team result). Once per
     * pair and season: a pair whose record already holds [closing]'s season ([DerbyRecord.last]) or
     * that is rival only from a later season is left alone, so a replayed close counts nothing twice.
     * The matches counted.
     */
    fun fold(book: ClubBook, closing: SeasonPlan): Int {
        val w = book.world ?: return 0
        val h = w.history
        val s = closing.number
        val due = HashMap<String, DerbyRecord>()
        for ((id, rs) in h.rivals) for (r in rs) {
            val k = ClubHistoryBook.pairKey(id, r.club)
            if (k in due) continue
            val (a, b) = ClubHistoryBook.pair(id, r.club)
            val d = h.derbies[k] ?: DerbyRecord(a, b, 0, 0, 0, 0, r.since, 0)
            if (d.last < s && d.since <= s) due[k] = d
        }
        if (due.isEmpty()) return 0
        val counts = tally(book, closing, due.keys, null)
        var n = 0
        for ((k, c) in counts) {
            val d = due.getValue(k)
            h.derbies[k] = d.copy(winsA = d.winsA + c[0], draws = d.draws + c[1], winsB = d.winsB + c[2], games = d.games + c.sum(), last = s)
            n += c.sum()
        }
        if (n > 0) book.touch()
        return n
    }

    /**
     * This season's series of every rival pair of [book] in [plan] (design ENG 1b.6: computed on
     * request, never stored), through week [through] (null: every played match), by
     * [ClubHistoryBook.pairKey]: pairs that met only, `since` and `last` the season.
     */
    fun series(book: ClubBook, plan: SeasonPlan, through: Int? = null): Map<String, DerbyRecord> {
        val h = book.world?.history ?: return emptyMap()
        val pairs = HashSet<String>()
        for ((id, rs) in h.rivals) for (r in rs) pairs += ClubHistoryBook.pairKey(id, r.club)
        val s = plan.number
        return tally(book, plan, pairs, through).mapValues { (k, c) ->
            val (a, b) = k.split(' ')
            DerbyRecord(a, b, c[0], c[1], c[2], c.sum(), s, s)
        }
    }

    /**
     * The all-time series of clubs [a] and [b] with this season's matches of [plan] through week
     * [through] added (unless the record holds that season already); null when they have no record.
     */
    fun allTime(book: ClubBook, plan: SeasonPlan, a: String, b: String, through: Int? = null): DerbyRecord? {
        val stored = book.derby(a, b) ?: return null
        if (stored.last >= plan.number) return stored
        val now = series(book, plan, through)[ClubHistoryBook.pairKey(a, b)] ?: return stored
        return stored.copy(winsA = stored.winsA + now.winsA, draws = stored.draws + now.draws, winsB = stored.winsB + now.winsB, games = stored.games + now.games, last = plan.number)
    }

    /** Wins of the pair's first club, draws, wins of the second, by pair key, over [plan]'s played official matches through [through]. */
    private fun tally(book: ClubBook, plan: SeasonPlan, pairs: Set<String>, through: Int?): Map<String, IntArray> {
        if (pairs.isEmpty()) return emptyMap()
        val moved = HashMap<String, WinterMove>()
        for (m in book.winterMoves(plan.number)) moved.putIfAbsent(m.key, m)
        fun clubAt(key: String, week: Int): String? =
            moved[key]?.takeIf { week != SeasonCalendar.NONE && week <= it.week }?.from ?: book.clubOf(key)
        val out = HashMap<String, IntArray>()
        for (m in SeasonMatches.all(plan)) {
            // The 클럽 대항전's sides are the clubs themselves: their team match is one match of the series.
            val clubCup = m.ref.comp == Comp.CLUB_CUP
            if (!m.played || (m.ref.comp !in OFFICIAL && !clubCup) || (m.fixture.kind == JobKind.TEAM) != clubCup) continue
            if (through != null && (m.week == SeasonCalendar.NONE || m.week > through)) continue
            val result = m.score?.winner ?: continue
            val ca = (if (clubCup) m.fixture.a else clubAt(m.fixture.a, m.week)) ?: continue
            val cb = (if (clubCup) m.fixture.b else clubAt(m.fixture.b, m.week)) ?: continue
            if (ca == cb) continue
            val k = ClubHistoryBook.pairKey(ca, cb)
            if (k !in pairs) continue
            val c = out.getOrPut(k) { IntArray(3) }
            val aFirst = ClubHistoryBook.pair(ca, cb).first == ca
            when {
                result == 0 -> c[1]++
                (result > 0) == aFirst -> c[0]++
                else -> c[2]++
            }
        }
        return out
    }

    // ---- rival matches ------------------------------------------------------------------------------

    /** The rival matches of [book] this season, with each club's counted members its best [ClubTable.COUNTED] by [rating] (the season's drawn ratings). */
    fun matches(book: ClubBook, rating: (String) -> Double): RivalMatches {
        val order = ClubBook.ratingOrder(rating)
        val counted = HashSet<String>()
        if (book.world != null) for (c in book.clubs) counted += c.squad.sortedWith(order).take(ClubTable.COUNTED)
        return RivalMatches(book, counted)
    }

    /**
     * The name of the rivalry when [a] and [b] meet in a 1부 league fixture of [plan] as counted
     * members of rival clubs (design ENG 1b.6, `LeagueWorld.rivalName`); null otherwise. For many
     * fixtures build [matches] once.
     */
    fun rivalName(book: ClubBook, plan: SeasonPlan, a: String, b: String, rating: (String) -> Double): String? =
        matches(book, rating).name(plan.league, a, b)

    /** [plan]'s leagues, best-ranked first (its ranking, then the strength of their best players by [rating], as the club tables order them). */
    fun topLeagues(plan: SeasonPlan, rating: (String) -> Double): List<String> {
        val season = plan.league
        val ids = season.leagues.list.map { it.id }
        val strength = ids.associateWith { id -> Coefficients.strength(season.leagues.byId(id)?.divisions?.flatMap { season.divisions[it] }.orEmpty(), rating) }
        return Coefficients.order(ids, plan.ranking) { strength.getValue(it) }
    }

    /**
     * The week's `derby_result` (design ENG 11.2): of week [week]'s played rival matches with a
     * winner in the [NEWS_LEAGUES] best-ranked leagues of [plan], the one of the best-ranked league
     * (then the strongest rivalry kind, then the fixture id), as [winner, loser, winner's club,
     * loser's club] (score winner first "2-0", the clubs' all-time wins, draws and losses from the
     * winner's club through the week, the rivalry's name), comp [Comp.LEAGUE], the week and the
     * match's ref. Null when there is none, or when [told] (this season's so far) reached
     * [SEASON_RESULTS]. [rating]: the season's drawn ratings ([matches]).
     */
    fun weekResult(book: ClubBook, plan: SeasonPlan, week: Int, rating: (String) -> Double, told: Int = 0, rivals: RivalMatches? = null): LeagueEvent? {
        if (book.world == null || week < 1 || told >= SEASON_RESULTS) return null
        val league = plan.league
        val rank = topLeagues(plan, rating).take(NEWS_LEAGUES).withIndex().associate { it.value to it.index }
        val rm = rivals ?: matches(book, rating)
        var best: Triple<Int, Int, LeagueFixture>? = null
        var bestName = ""
        for (f in league.matchday(week - 1)) {
            val games = league.result(f.id) ?: continue
            val r = league.leagueOf(f.a)?.id?.let { rank[it] } ?: continue
            val name = rm.name(league, f.a, f.b) ?: continue
            if (TieRule.match(games, league.format) == 0) continue
            val ca = book.clubOf(f.a) ?: continue
            val kind = priority(book.rivals(ca).firstOrNull { it.club == book.clubOf(f.b) }?.kind ?: TRANSFER)
            val b = best
            if (b == null || r < b.first || (r == b.first && (kind < b.second || (kind == b.second && f.id < b.third.id)))) {
                best = Triple(r, kind, f)
                bestName = name
            }
        }
        val f = best?.third ?: return null
        val games = league.result(f.id)!!
        val aWon = TieRule.match(games, league.format) > 0
        val (winner, loser) = if (aWon) f.a to f.b else f.b to f.a
        val wc = book.clubOf(winner)!!
        val lc = book.clubOf(loser)!!
        var won = 0.0
        for (g in games) won += if (g.a > g.b) 1.0 else if (g.a == g.b) 0.5 else 0.0
        val mine = if (aWon) won else games.size - won
        val score = "${num(mine)}-${num(games.size - mine)}"
        val (wins, draws, losses) = allTime(book, plan, wc, lc, week)?.of(wc) ?: Triple(0, 0, 0)
        return LeagueEvent(
            "derby_result", plan.number, listOf(winner, loser, wc, lc),
            listOf(score, wins.toString(), draws.toString(), losses.toString(), bestName), Comp.LEAGUE, week,
            MatchRef(plan.number, Comp.LEAGUE, f.id).encode(),
        )
    }

    private fun num(x: Double): String = if (x == Math.floor(x)) x.toInt().toString() else x.toString()
}

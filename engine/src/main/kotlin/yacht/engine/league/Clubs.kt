package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A club's badge: a [shape] (0 until [SHAPES]; the screen picks the outlines, e.g. shield,
 * circle, diamond, hexagon, star, square), a main and an accent colour (ARGB) and the
 * [initial] drawn in the middle (the mascot's first letter).
 */
data class Emblem(val shape: Int, val colour1: Int, val colour2: Int, val initial: Char) {
    companion object {
        const val SHAPES = 6
    }
}

/**
 * A club, as an immutable snapshot. In a book of the club league (`clubs 1`, docs/LEAGUE_SPEC.md
 * section 2.2) [squad] holds the club's few AIs and [honours] its club league titles; in a book of
 * national leagues (`clubs 2`, [ClubBook.AFFILIATION]) every member of the league is in one club
 * of its [nation], [squad] holds them all, and [honours] counts its league's club-table titles
 * ([ClubTable]). No AI is in two clubs; [budget] is its money for the transfer window.
 */
data class Club(
    val id: String,
    /** "<city> <mascot>", e.g. "부산 돌고래". */
    val name: String,
    val city: String,
    val mascot: String,
    val emblem: Emblem,
    val squad: List<String>,
    val budget: Int,
    val honours: Int,
    /** The nation it belongs to (its members play in that nation's league); "" in the club league. */
    val nation: String = "",
    /** The first season it played; 0 when unknown (a club from the club league's book). */
    val founded: Int = 0,
)

/**
 * A club dissolved after season [dissolved] (해체): kept so its id stays resolvable (honours,
 * season lines and moves name it), until the id is given to a new club ([ClubBook.MAX_ID]).
 */
data class GoneClub(
    val id: String,
    val nation: String,
    val name: String,
    val emblem: Emblem,
    val honours: Int,
    /** The first season it played (0: unknown). */
    val founded: Int,
    val dissolved: Int,
)

/** What a [ClubMove] was, as the club card groups it ([ClubMoveType.kind]); [label] is shown on the card. */
enum class ClubMoveKind(val code: Char, val label: String) {
    /** Bought in the transfer window (from another club or as a free agent). */
    TRANSFER('T', "이적"),

    /** Picked as a free agent in the draft. */
    DRAFT('D', "드래프트"),

    /** Let go: replaced by a buy, cut to a smaller squad, or the club was dissolved. */
    RELEASED('R', "방출"),

    /** Left the league (retired or removed), freeing a place. */
    RETIRED('X', "은퇴"),
    ;

    companion object {
        fun of(code: Char): ClubMoveKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What a [ClubMove] was, in full: the saved [code]. The moves between leagues and the youth
 * placements of national leagues ([ABROAD], [HOME], [YOUTH]) belong to one of the four
 * [ClubMoveKind]s the club card has always shown.
 */
enum class ClubMoveType(val code: Char, val label: String, val kind: ClubMoveKind) {
    TRANSFER('T', "이적", ClubMoveKind.TRANSFER),
    DRAFT('D', "드래프트", ClubMoveKind.DRAFT),
    RELEASED('R', "방출", ClubMoveKind.RELEASED),
    RETIRED('X', "은퇴", ClubMoveKind.RETIRED),

    /** To a club of a stronger league abroad (해외 이적, [Transfers]); the amount is the fee. */
    ABROAD('F', "해외 이적", ClubMoveKind.TRANSFER),

    /** Back from abroad to a club of its own nation (귀국); no fee. */
    HOME('H', "귀국", ClubMoveKind.TRANSFER),

    /** A pupil placed at its mentor's club (유스 입단). */
    YOUTH('Y', "유스 입단", ClubMoveKind.DRAFT),
    ;

    companion object {
        fun of(code: Char): ClubMoveType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One line of the clubs' history, for the club card: AI [ai] moved from club [from] (null: a
 * free agent) to club [to] (null: nowhere) after season [season]. [amount] is the fee of a
 * transfer and the pick number of a draft pick (0 otherwise).
 */
data class ClubMove(val season: Int, val type: ClubMoveType, val ai: String, val from: String?, val to: String?, val amount: Int = 0) {
    /** The card's group of [type]. */
    val kind: ClubMoveKind get() = type.kind
}

/** The best clubs of league [league] in a season's club table (`cs` line): [clubs] the top 3, [points] the champion's. */
data class ClubPlaces(val league: String, val clubs: List<String>, val points: Double)

/**
 * The last club off-season of a national league in numbers (`od` line), with the players its
 * news named, so a replayed rollover tells the same news ([ClubBook.offseason]).
 */
data class ClubDigest(
    val season: Int,
    /** Players new to the league placed in a club (draft and youth). */
    val rookies: Int,
    /** Transfers between clubs of one nation. */
    val domestic: Int,
    val abroad: Int,
    val returns: Int,
    /** The biggest domestic fee: the AI, from, to (null without a transfer). */
    val biggest: ClubMove?,
    /** The best rated rookies (named in `draft` and `youth` news). */
    val drafted: List<String>,
    /** The biggest domestic fees (named in `transfer` news). */
    val bought: List<String>,
    /** The biggest fees abroad (named in `transfer_abroad` news). */
    val abroadNamed: List<Named>,
    /** Holders of a major title back home (named in `return_home` news). */
    val homeNamed: List<Named>,
    /** The off-season that made the book a national one ([ClubBook.migrate]). */
    val reform: Boolean = false,
) {
    /** A player of a move between leagues, with the leagues of the closing season ("" when none). */
    data class Named(val key: String, val fromLeague: String, val toLeague: String)
}

/**
 * What a national league's club off-season ([ClubBook.offseason], [ClubBook.migrate]) knows about
 * the league next season.
 */
class ClubInput(
    /** Every AI in the league next season (me excluded). */
    val members: Collection<String>,
    val rating: (String) -> Double,
    /** Each AI's nation (generated, evolved and made players; null for the guest, base AIs and mine). */
    val nation: (String) -> String?,
    /** The next season's config: [LeagueConfig.clubSize], [LeagueConfig.transfers]. */
    val config: LeagueConfig,
    /** Every honour so far, the closing season's included: prize money, the club table, notable players. */
    val honours: List<Honour> = emptyList(),
    /** Age in the coming season (null: unknown; such a player is never transferred nor sent home by age). */
    val age: (String) -> Int? = { null },
    val origin: (String) -> Origin = Origin::of,
    /** An evolved player's first parent, whose club takes it in first when it is new to the league (유스 입단). */
    val mentor: (String) -> String? = { null },
    /** The league ranking after the closing season (transfers abroad: none with [LeagueRanking.EMPTY]). */
    val ranking: LeagueRanking = LeagueRanking.EMPTY,
    /** Whether a league's title counted as major in a season ([HonourBook.majorTitles]). */
    val major: (season: Int, league: String) -> Boolean = { _, _ -> true },
)

/**
 * Club names and badges: deterministic from the club id and the book's seed, so a club keeps its
 * name and a club added later (a bigger league) never renames the others.
 */
object ClubNames {
    /** Mascots, one per club of a nation (the club league dealt from the first 32). */
    val MASCOTS = listOf(
        "호랑이", "독수리", "돌고래", "반달곰", "늑대", "여우", "송골매", "사자",
        "표범", "상어", "고래", "불사조", "황소", "백마", "까치", "두루미",
        "부엉이", "해달", "펭귄", "코뿔소", "치타", "수달", "갈매기", "노루",
        "거북", "물개", "전사", "번개", "폭풍", "파도", "혜성", "유니콘",
        "흑룡", "청룡", "백호", "불곰", "흰수리", "바다사자", "범고래", "가오리",
        "제비", "참매", "들소", "순록", "하마", "코끼리", "기린", "판다",
    )

    /** The club league's mascots (its names stay as they were dealt). */
    private val LEAGUE_MASCOTS = MASCOTS.take(32)

    /**
     * Cities for clubs only (players' hometowns stay as they are drawn), after a nation's own,
     * so every nation has at least 10.
     */
    val EXTRA_CITIES: Map<String, List<String>> = mapOf(
        "SN" to listOf("루가", "콜다", "탐바쿤다"),
        "ZA" to listOf("폴로콰네", "음봄벨라", "킴벌리"),
        "NG" to listOf("조스", "아베오쿠타"),
        "CO" to listOf("산타마르타", "쿠쿠타"),
        "EG" to listOf("기자", "이스마일리아"),
        "MA" to listOf("케니트라", "테투안"),
        "SA" to listOf("하일", "알코바르"),
        "AU" to listOf("뉴캐슬", "울런공"),
        "AR" to listOf("네우켄", "바이아블랑카"),
        "SE" to listOf("우메오", "칼스타드"),
    )

    /** Main colours: deep, saturated tones that carry a light accent and a white initial. */
    private val PRIMARY = intArrayOf(
        0xFFC62828.toInt(), 0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt(),
        0xFFBF360C.toInt(), 0xFF00838F.toInt(), 0xFFAD1457.toInt(), 0xFF4E342E.toInt(),
        0xFF283593.toInt(), 0xFF37474F.toInt(), 0xFF212121.toInt(), 0xFF827717.toInt(),
    )

    /** Accent colours: white, cream, gold, silver. */
    private val ACCENT = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFF8E1.toInt(), 0xFFFFC107.toInt(), 0xFFCFD8DC.toInt())

    private const val SALT = 0xC1B5L

    /** A club's city, mascot, name and badge. */
    data class Identity(val city: String, val mascot: String, val name: String, val emblem: Emblem)

    private val citiesOf = HashMap<String, List<String>>()

    /** A nation's club cities: its own by weight (then name), then its [EXTRA_CITIES]. */
    fun cities(nation: Nation): List<String> = synchronized(citiesOf) {
        citiesOf.getOrPut(nation.code) {
            nation.cities.names.sortedWith(compareByDescending<String> { nation.cities.weight(it) }.thenBy { it }) + EXTRA_CITIES[nation.code].orEmpty()
        }
    }

    /**
     * The identity of club [id] ("c01", "c02", ...) of the club league in a book seeded with
     * [seed]. Mascots and the first 12 main colours are dealt without repeats, and [cities] (the
     * hometowns) go round once before any city gets a second club, so names stay unique (up to 32
     * clubs).
     */
    fun identity(id: String, seed: Long, cities: List<String>): Identity {
        val i = indexOf(id)
        val mascot = LEAGUE_MASCOTS[deal(i, LEAGUE_MASCOTS.size, seed, 1)]
        val city = if (cities.isEmpty()) "" else cities[deal(i, cities.size, seed, 2)]
        val name = if (city.isEmpty()) mascot else "$city $mascot"
        return Identity(city, mascot, name, emblem(i, seed, mascot.first()))
    }

    /**
     * The identity of club [id] of [nation], its [k]-th club founded there (0-based), in a book
     * seeded with [seed]: city `k` of [cities] (round again after the last), the first mascot of
     * the nation's seeded order that is not in [taken] (the nation's clubs' mascots) and makes no
     * name of [names] (a club of another nation in a city of the same name), the badge from the id.
     */
    fun identity(id: String, nation: Nation, k: Int, seed: Long, taken: Set<String>, names: Set<String> = emptySet()): Identity {
        val pool = cities(nation)
        val city = pool[Math.floorMod(k, pool.size)]
        val order = MASCOTS.shuffled(Random(DiceStream.mix(seed, SALT, 5, Nations.ALL.indexOf(nation).toLong())))
        val mascot = order.firstOrNull { it !in taken && "$city $it" !in names } ?: order.firstOrNull { "$city $it" !in names } ?: order.first()
        return Identity(city, mascot, "$city $mascot", emblem(indexOf(id), seed, mascot.first()))
    }

    private fun deal(i: Int, size: Int, seed: Long, salt: Long): Int = (0 until size).shuffled(Random(DiceStream.mix(seed, SALT, salt)))[i % size]

    private fun emblem(i: Int, seed: Long, initial: Char): Emblem {
        val rng = Random(DiceStream.mix(seed, SALT, 3, i.toLong()))
        val shape = rng.nextInt(Emblem.SHAPES)
        val primary = PRIMARY[deal(i, PRIMARY.size, seed, 4)]
        return Emblem(shape, primary, ACCENT[rng.nextInt(ACCENT.size)], initial)
    }

    /** "c07" is club 6 (0-based); an id of another shape gets a place from its hash. */
    private fun indexOf(id: String): Int =
        id.drop(1).toIntOrNull()?.minus(1)?.takeIf { it >= 0 } ?: Math.floorMod(id.hashCode(), 1000)
}

/**
 * Every club, kept between seasons (league/clubs.txt). Not thread-safe: callers synchronise.
 *
 * A book of the club league ([format] [CLUB_LEAGUE], `clubs 1`): squads, budgets, titles and the
 * newest [HISTORY] moves. Built once by [create] (a snake draft), then changed only by the
 * off-season of [offseason] (season, table, ...) (prize money, retirements, the transfer window and
 * the draft; spec section 5 step 4).
 *
 * A book of national leagues ([format] [AFFILIATION], `clubs 2`): every member of the league is in
 * exactly one club of the nation it plays for ([playNation]), and every club's nation has members
 * in the league. `C(n) = clamp(round(n / clubSize), 1, 16)` clubs per nation of n members. Built by
 * [found] (a new league) or converted from a club league's book by [migrate], then changed by the
 * national off-season ([offseason] with a [ClubInput]): the season's club tables, departures,
 * returns home and transfers abroad ([Transfers]), the domestic window, new and dissolved clubs,
 * the draft. Its accessors ([clubOf], [club], [clubsOf], [members], [playNation]) cost no copies of
 * the book, and [version] changes with every change of a club or its members.
 */
class ClubBook private constructor(
    /** Seed of the club names and badges (clubs added later are named from it too). */
    val seed: Long,
    /** Hometowns the club league's clubs are named after (empty in a national book). */
    cities: List<String>,
    format: Int,
) : ClubSource {
    /** A club as the book changes it. */
    private class Entry(
        val id: String,
        var nation: String,
        val identity: ClubNames.Identity,
        val squad: MutableList<String>,
        var budget: Int,
        var honours: Int,
        val founded: Int = 0,
    ) {
        fun snapshot() = Club(id, identity.name, identity.city, identity.mascot, identity.emblem, squad.toList(), budget, honours, nation, founded)
    }

    var cities: List<String> = cities
        private set

    /** [CLUB_LEAGUE] (`clubs 1`) or [AFFILIATION] (`clubs 2`). */
    var format: Int = format
        private set

    private val entries = ArrayList<Entry>()
    private val byId = HashMap<String, Entry>()
    private val memberOf = HashMap<String, String>()
    private val moves = ArrayDeque<ClubMove>()
    private val goneList = ArrayList<GoneClub>()
    private val placeLines = TreeMap<Int, List<ClubPlaces>>()

    /** The number of the next new club's id (a national book). */
    private var next = 1

    /** The last national off-season in numbers, null before one (or in the club league's book). */
    var digest: ClubDigest? = null
        private set

    /**
     * Changes with every change of a club or its members (different from every other book's
     * number too), so the app rebuilds what it shows from the book only when this moves.
     */
    var version: Int = VERSIONS.incrementAndGet()
        private set

    private fun changed() {
        version = VERSIONS.incrementAndGet()
    }

    /** The last season whose off-season was applied (0 before any): a repeat changes nothing. */
    var season: Int = 0
        private set

    /**
     * The seed of the plan whose off-season was applied last (null before any, or from a file
     * without it): with [season] it tells a repeated rollover from another league's season of
     * the same number.
     */
    private var lastSeed: Long? = null

    /**
     * Clubs founded at the end of season [season] (plan seed [seed]), drafted from that season's
     * table: that off-season is theirs already, so a repeated rollover leaves them as they are.
     */
    fun founded(season: Int, seed: Long) {
        this.season = season
        lastSeed = seed
    }

    /** Every club, by id. */
    val clubs: List<Club> get() = entries.map { it.snapshot() }

    /** Dissolved clubs whose ids are still theirs, by id. */
    val gone: List<GoneClub> get() = goneList.toList()

    /** Members in all. */
    val size: Int get() = memberOf.size

    /**
     * The newest moves, oldest first: [HISTORY] lines ([historyCap] in a national book), or more
     * when the last off-season alone made more (it is kept whole, so a repeated rollover tells
     * the same news).
     */
    val history: List<ClubMove> get() = moves.toList()

    fun club(id: String): Club? = entry(id)?.snapshot()

    /** A dissolved club, by id. */
    fun gone(id: String): GoneClub? = goneList.firstOrNull { it.id == id }

    /** The club AI [key] plays for, or null for a free agent. */
    fun clubOf(key: String): String? = memberOf[key]

    /** The nation of club [id], also of a dissolved one; null for an unknown id or a club league's club. */
    fun nationOf(id: String): String? = (entry(id)?.nation ?: gone(id)?.nation)?.ifEmpty { null }

    /** The name of club [id], also of a dissolved one. */
    fun nameOf(id: String): String? = entry(id)?.identity?.name ?: gone(id)?.name

    /** The nation AI [key] plays for next: its club's; null without a club (then its own nation's, [NationalLeagues.home]). */
    fun playNation(key: String): String? = memberOf[key]?.let { nationOf(it) }

    /** The clubs of [nation], by id. */
    fun clubsOf(nation: String): List<Club> = entries.filter { it.nation == nation }.map { it.snapshot() }

    /** Club [club]'s members now (empty for a club that is gone). */
    override fun members(club: String): List<String> = entry(club)?.squad?.toList().orEmpty()

    /** The club-table champion of every league of season [season] (the `cs` line), best-ranked league first. */
    override fun champions(season: Int): List<String> = placeLines[season].orEmpty().mapNotNull { it.clubs.firstOrNull() }

    /** Season [season]'s best clubs per league (the newest [PLACE_SEASONS] seasons are kept), best-ranked league first. */
    fun places(season: Int): List<ClubPlaces> = placeLines[season].orEmpty()

    /** The AIs that made a move of [type] in the off-season after [season] ([ClubMoveType.ABROAD]: the transfers in for [Offseason.Context]). */
    fun moved(season: Int, type: ClubMoveType): Set<String> = moves.filter { it.season == season && it.type == type }.mapTo(LinkedHashSet()) { it.ai }

    /** Moves into or out of club [id], newest last. */
    fun history(id: String): List<ClubMove> = moves.filter { it.from == id || it.to == id }

    private fun entry(id: String): Entry? = byId[id]

    private fun add(e: Entry) {
        entries += e
        byId[e.id] = e
    }

    private fun drop(e: Entry) {
        entries.remove(e)
        byId.remove(e.id)
    }

    private fun newEntry(id: String) = Entry(id, "", ClubNames.identity(id, seed, cities), ArrayList(), START_BUDGET, 0)

    private fun reindex() {
        memberOf.clear()
        for (e in entries) for (k in e.squad) memberOf[k] = e.id
    }

    private fun log(m: ClubMove) {
        moves.addLast(m)
    }

    /** Drops the oldest moves beyond [HISTORY] ([historyCap]), never those of the last off-season. */
    private fun trim() {
        val cap = if (format == AFFILIATION) historyCap(memberOf.size) else HISTORY
        while (moves.size > cap && moves.first().season < season) moves.removeFirst()
        while (placeLines.size > PLACE_SEASONS) placeLines.pollFirstEntry()
    }

    // ---- the club league's off-season ----------------------------------------------------------

    /**
     * The clubs' off-season after season [season] (spec section 5 step 4), once: a repeated call
     * for the same season and [seed] (a rollover replayed after a kill) changes nothing and
     * returns the same news from the history. An older season, or the same season with another
     * seed, means the league started over (a new league, or a season file that could not be
     * read): the off-season is applied to the clubs as they are, and the moves of that season
     * and later ones (an abandoned timeline) leave the history.
     *
     * In order: prize money by [table] (the club league's final order, best first; empty when it
     * did not run) of [prize] per place, and money for the cup and CL places of [honours] to the
     * clubs of those AIs ([honourMoney]); a "CLUB" honour adds a title. Members not in [alive]
     * (the AIs in the league next season) leave. Then, with [clubs] > 0, the club count and
     * squad size follow the config:
     * - more clubs: new clubs (the smallest free ids, named as usual) start with
     *   [START_BUDGET] and an empty squad; they buy in the window and pick first in the draft;
     * - fewer clubs: the clubs that did not play, then the lowest placed, are dissolved and
     *   their members become free agents;
     * - a smaller squad: each club releases its lowest rated members; a bigger one is filled
     *   by the draft.
     *
     * Transfer window: clubs in order of budget (smallest first, ties in a draw from [seed]),
     * [BUYS] rounds of one buy each. A club buys the best rated AI it can afford that is better
     * than its weakest member: a free agent at [value], or another club's player at [fee] when
     * that club keeps at least `squad - 1` (the money goes to the seller). A full club releases
     * its weakest member to make room. An AI bought in this window is not bought again in it.
     *
     * Draft: clubs with open places pick the best rated free agents, one pick per round, clubs
     * that did not play first, then in reverse final order.
     *
     * With [clubs] = 0 (club league off) the clubs stay as they are after the money and the
     * departures, so turning the league back on resumes them. Events: `transfer`
     * [ai, fromClub or "-", toClub] (fee) and `draft` [ai, club] (pick number).
     *
     * A national book ([AFFILIATION]) is changed only by its own off-season ([offseason] with a
     * [ClubInput]): here its members not in [alive] leave, and nothing else changes.
     */
    fun offseason(
        season: Int,
        table: List<String>,
        honours: List<Honour>,
        rating: (String) -> Double,
        alive: Set<String>,
        clubs: Int,
        squad: Int,
        seed: Long,
    ): List<LeagueEvent> {
        if (format == AFFILIATION) {
            if (entries.any { e -> e.squad.any { it !in alive } }) {
                for (e in entries) e.squad.retainAll(alive)
                reindex()
                changed()
            }
            return emptyList()
        }
        if (season == this.season && (lastSeed == null || lastSeed == seed)) return again(season, table, rating, alive, clubs, squad)
        if (season <= this.season) moves.removeAll { it.season >= season }
        leagueMoney(season, table, honours, alive)
        val events = ArrayList<LeagueEvent>()
        if (clubs > 0) {
            val order = ratingOrder(rating)
            val place = table.distinct().withIndex().associate { it.value to it.index }
            resize(season, place, clubs.coerceAtMost(MAX_CLUBS), squad, order)
            window(season, rating, order, alive, squad, seed, events)
            draft(season, place, order, alive, squad, events)
        }
        this.season = season
        lastSeed = seed
        trim()
        changed()
        return events
    }

    /**
     * [offseason] for a finished [plan]: its club league's final table and every competition's
     * honours, the club count and squad size of the next season's [config], and the plan's seed.
     */
    fun offseason(plan: SeasonPlan, rating: (String) -> Double, alive: Set<String>, config: LeagueConfig): List<LeagueEvent> {
        val table = (plan.competition(Comp.CLUB) as? ClubLeague)?.order().orEmpty()
        return offseason(plan.number, table, leagueHonours(plan), rating, alive, config.clubs, config.squad, plan.seed)
    }

    /** Every competition's honours of [plan] (the club league's off-season pays by them). */
    private fun leagueHonours(plan: SeasonPlan): List<Honour> = (plan.comps + plan.post).flatMap { c -> runCatching { c.honours() }.getOrDefault(emptyList()) }

    /**
     * The club league's money and departures after [season]: prize money by [table], honour
     * money and the "CLUB" title, members not in [alive] leave (each a [ClubMoveType.RETIRED]).
     */
    private fun leagueMoney(season: Int, table: List<String>, honours: List<Honour>, alive: Set<String>) {
        val final = table.distinct()
        for ((i, id) in final.withIndex()) entry(id)?.let { it.budget += prize(i + 1) }
        for (h in honours) {
            if (h.season != season) continue
            if (h.kind == "CLUB") entry(h.key)?.let { it.honours++ }
            val money = honourMoney(h.kind)
            if (money > 0) memberOf[h.key]?.let { entry(it) }?.let { it.budget += money }
        }
        for (e in entries) {
            val gone = e.squad.filter { it !in alive }
            e.squad.removeAll(gone.toSet())
            for (k in gone) log(ClubMove(season, ClubMoveType.RETIRED, k, e.id, null))
        }
        reindex()
    }

    /**
     * An off-season already applied, asked for again (a close replayed after the app was
     * killed): its news from the history. Members no longer in the league ([alive]: the rules
     * changed before the replay) leave, and the draft fills their places, so squads hold only
     * AIs of the league; with the same members nothing changes.
     */
    private fun again(season: Int, table: List<String>, rating: (String) -> Double, alive: Set<String>, clubs: Int, squad: Int): List<LeagueEvent> {
        val events = replay(season).toMutableList()
        var left = false
        for (e in entries) {
            val gone = e.squad.filter { it !in alive }
            if (gone.isEmpty()) continue
            left = true
            e.squad.removeAll(gone.toSet())
            for (k in gone) log(ClubMove(season, ClubMoveType.RETIRED, k, e.id, null))
        }
        if (!left) return events
        reindex()
        if (clubs > 0) {
            val place = table.distinct().withIndex().associate { it.value to it.index }
            val picked = moves.filter { it.season == season && it.type == ClubMoveType.DRAFT }.maxOfOrNull { it.amount } ?: 0
            draft(season, place, ratingOrder(rating), alive, squad, events, picked)
        }
        trim()
        changed()
        return events
    }

    /** The news of an off-season already applied, from the history (as far as it reaches back). */
    private fun replay(season: Int): List<LeagueEvent> = moves.filter { it.season == season }.mapNotNull { m ->
        when (m.type) {
            ClubMoveType.TRANSFER -> LeagueEvent("transfer", season, listOf(m.ai, m.from ?: "-", m.to ?: "-"), listOf(m.amount.toString()), Comp.CLUB)
            ClubMoveType.DRAFT -> LeagueEvent("draft", season, listOf(m.ai, m.to ?: "-"), listOf(m.amount.toString()), Comp.CLUB)
            else -> null
        }
    }

    private fun release(season: Int, e: Entry, keys: Collection<String>) {
        e.squad.removeAll(keys.toSet())
        for (k in keys) {
            memberOf.remove(k)
            log(ClubMove(season, ClubMoveType.RELEASED, k, e.id, null))
        }
    }

    private fun resize(season: Int, place: Map<String, Int>, clubs: Int, squad: Int, order: Comparator<String>) {
        if (entries.size > clubs) {
            val doomed = entries.sortedWith(compareByDescending<Entry> { place[it.id] ?: Int.MAX_VALUE }.thenByDescending { it.id })
                .take(entries.size - clubs)
            for (e in doomed) {
                release(season, e, e.squad.toList())
                drop(e)
            }
        }
        var n = 1
        while (entries.size < clubs) {
            val id = idOf(n++)
            if (entries.none { it.id == id }) add(newEntry(id))
        }
        entries.sortBy { it.id }
        for (e in entries) if (e.squad.size > squad) release(season, e, e.squad.sortedWith(order).drop(squad))
    }

    /** A possible buy: AI [key] from [seller] (null: a free agent) for [fee]. */
    private class Offer(val key: String, val seller: Entry?, val fee: Int, val rating: Double)

    private fun window(
        season: Int,
        rating: (String) -> Double,
        order: Comparator<String>,
        alive: Set<String>,
        squad: Int,
        seed: Long,
        events: MutableList<LeagueEvent>,
    ) {
        // Stable sort: equal budgets keep the seeded draw's order.
        val buyers = entries.shuffled(Random(DiceStream.mix(seed, WINDOW_SALT, season.toLong()))).sortedBy { it.budget }
        val sold = HashSet<String>()
        repeat(BUYS) {
            for (buyer in buyers) {
                val weakest = buyer.squad.sortedWith(order).lastOrNull()
                val floor = weakest?.let { score(rating, it) } ?: Double.NEGATIVE_INFINITY
                var best: Offer? = null
                fun consider(key: String, seller: Entry?) {
                    val r = score(rating, key)
                    if (r <= floor) return
                    val v = value(rating(key))
                    val price = if (seller == null) v else fee(v)
                    if (price > buyer.budget) return
                    val b = best
                    // Best rated first; level, the cheaper, then the key (a total order).
                    if (b == null || r > b.rating || (r == b.rating && (price < b.fee || (price == b.fee && key < b.key)))) best = Offer(key, seller, price, r)
                }
                for (k in alive) if (k !in memberOf && k !in sold) consider(k, null)
                for (seller in entries) {
                    if (seller === buyer || seller.squad.size < squad) continue
                    for (k in seller.squad) if (k !in sold) consider(k, seller)
                }
                val o = best ?: continue
                o.seller?.let { s ->
                    s.squad.remove(o.key)
                    s.budget += o.fee
                }
                buyer.budget -= o.fee
                if (buyer.squad.size >= squad && weakest != null) release(season, buyer, listOf(weakest))
                buyer.squad += o.key
                memberOf[o.key] = buyer.id
                sold += o.key
                log(ClubMove(season, ClubMoveType.TRANSFER, o.key, o.seller?.id, buyer.id, o.fee))
                events += LeagueEvent("transfer", season, listOf(o.key, o.seller?.id ?: "-", buyer.id), listOf(o.fee.toString()), Comp.CLUB)
            }
        }
    }

    /** The draft of [season]; its picks are numbered after the [start] made already. */
    private fun draft(season: Int, place: Map<String, Int>, order: Comparator<String>, alive: Set<String>, squad: Int, events: MutableList<LeagueEvent>, start: Int = 0) {
        val turns = entries.sortedWith(compareByDescending<Entry> { place[it.id] ?: Int.MAX_VALUE }.thenBy { it.id })
        val pool = ArrayDeque(alive.filter { it !in memberOf }.sortedWith(order))
        var pick = start
        do {
            var any = false
            for (e in turns) {
                if (e.squad.size >= squad || pool.isEmpty()) continue
                val k = pool.removeFirst()
                e.squad += k
                memberOf[k] = e.id
                pick++
                any = true
                log(ClubMove(season, ClubMoveType.DRAFT, k, null, e.id, pick))
                events += LeagueEvent("draft", season, listOf(k, e.id), listOf(pick.toString()), Comp.CLUB)
            }
        } while (any)
    }

    // ---- national leagues: the off-season ---------------------------------------------------------

    /**
     * The clubs' off-season of a national league after [closing] (design ENG 3.5), once per
     * season and plan seed: a repeat (a rollover replayed after a kill) changes nothing but
     * removing members who are no longer in the league and drafting members without a club, and
     * returns the same news, rebuilt from the named moves and the [digest]. A club league's book
     * is converted first ([migrate]). An older season, or the same with another seed, is a league
     * that started over: applied to the clubs as they are (the abandoned timeline's moves go).
     *
     * In order:
     * 1. the season's club tables ([ClubTable], every league of [closing]): the `cs` line ([places]),
     *    [prize] money by place, a title for each champion, and money for the cup, CL, 대륙 챔피언스
     *    and 클럽 대항전 places ([honourMoney]);
     * 2. members not in [ClubInput.members] leave (a [ClubMoveType.RETIRED] move only for a notable
     *    one: a major title, or the 1부 at the close);
     * 3. and 4. returns home and transfers abroad ([Transfers], with [ClubInput.ranking]);
     * 5. the domestic window of each nation, worst club first: at most `C / 2` deals, each buyer one
     *    player from the top 3 of a club placed above it, better than its weakest counted member,
     *    at [fee] (the seller keeps at least the size floor, a buyer at the ceiling releases its
     *    weakest member);
     * 6. clubs founded (창단) and dissolved (해체) where the club count no longer fits the members;
     *    where the members without a club cannot bring every club up to the size floor, the
     *    biggest clubs release their weakest;
     * 7. the draft of each nation: pupils (evolved players new to the league) first at their
     *    mentor's club when it is of their nation and below the ceiling, then every member without
     *    a club, best rated first, each to the club with the fewest members (then the lower
     *    club-table place, then id).
     *
     * News (comp [Comp.CLUB], keys are AIs and club ids): `club_title` [club] (league, points) for
     * the 4 best-ranked leagues and one `club_champions` [≤ 3 clubs] (other leagues); `return_home`
     * [ai, from, to] (fromLeague, toLeague) for up to 3 holders of a major title, `transfer_abroad`
     * [ai, from, to] (fromLeague, toLeague, fee) for the 3 biggest fees and one `transfer_window`
     * [≤ 6 of them] (abroad, returns); `transfer` [ai, from, to] (fee) for the 3 biggest domestic
     * fees; `club_dissolved` [club] (members released) and `club_founded` [club] (nation), or, with
     * more than 3 of them, one `club_digest` [≤ 6 clubs, founded first] (founded, dissolved); `draft`
     * [ai, club] (pick within its nation) or `youth` [ai, club] for the 3 best rated rookies; one
     * `market_digest` [≤ 6 of them] (domestic, rookies, biggest-fee ai, from, to, fee).
     */
    fun offseason(closing: SeasonPlan, input: ClubInput): List<LeagueEvent> {
        if (format == CLUB_LEAGUE) return migrate(closing, input)
        val s = closing.number
        if (s == season && (lastSeed == null || lastSeed == closing.seed)) return againNational(closing, input)
        if (s <= season) restart(s)
        val alive = input.members.toHashSet()
        val majors = majorHolders(input)
        val inSeason = closing.league.standings.keys
        val place = record(closing, input)
        // 2. Departures.
        depart(s, alive) { k -> k in majors || tierOf(closing.league, k) == 0 }
        // 3 and 4. Returns home and transfers abroad.
        val moved = HashSet<String>()
        val sizes = nationSizes(alive, input)
        val plan = Transfers.plan(
            TransferInput(
                closing.league, input.ranking, alive, input.rating, input.age, input.nation, input.origin, clubs,
                { id -> entry(id)?.let { maxSize(sizes[it.nation] ?: 0, clubCountOf(it.nation)) } ?: 0 }, input.config,
            ),
        )
        for (t in plan) {
            val from = entry(t.fromClub) ?: continue
            val to = entry(t.toClub)
            from.squad.remove(t.key)
            from.budget += t.fee
            memberOf.remove(t.key)
            to?.let {
                it.budget -= t.fee
                it.squad += t.key
                memberOf[t.key] = it.id
            }
            moved += t.key
            log(ClubMove(s, if (t.home) ClubMoveType.HOME else ClubMoveType.ABROAD, t.key, from.id, to?.id, t.fee))
        }
        // 5. The domestic window.
        window(s, input, place, moved)
        // 6. Clubs founded and dissolved, and room for the draft to bring every club up to the floor.
        resize(s, alive, input, place)
        balance(s, alive, input, place)
        // 7. The draft.
        val placed = draftNational(s, alive, input, place, inSeason)
        // The digest: what the news names, so a replay names the same.
        val newcomers = placed.filter { it.key !in inSeason }
        val drafted = newcomers.sortedWith(compareByDescending<Placement> { score(input.rating, it.key) }.thenBy { it.key }).take(NAMED)
        logPlacements(s, placed, drafted.mapTo(HashSet()) { it.key })
        val ms = moves.filter { it.season == s }
        val domestic = ms.filter { it.type == ClubMoveType.TRANSFER }.sortedWith(compareByDescending<ClubMove> { it.amount }.thenBy { it.ai })
        fun named(t: Transfer) = ClubDigest.Named(t.key, t.fromLeague, t.toLeague)
        val homes = plan.filter { it.home && it.key in majors }.sortedWith(compareByDescending<Transfer> { score(input.rating, it.key) }.thenBy { it.key }).take(NAMED)
        digest = ClubDigest(
            s, newcomers.size, domestic.size, plan.count { !it.home }, plan.count { it.home }, domestic.firstOrNull(),
            drafted.map { it.key }, domestic.take(NAMED).map { it.ai },
            plan.filter { !it.home }.sortedWith(compareByDescending<Transfer> { it.fee }.thenBy { it.key }).take(NAMED).map(::named), homes.map(::named),
        )
        season = s
        lastSeed = closing.seed
        trim()
        changed()
        return news(s)
    }

    /**
     * A club league's book becomes a national one at the open of the first national season
     * (design ENG 3.7), once per season and plan seed (a repeat goes to [offseason]): the club
     * league's money and departures of [closing] as before (unless that off-season was applied
     * already); Korea keeps the best `C(KR)` clubs by (titles, budget, id) with their ids, names,
     * badges, money and titles and their members who play for Korea, the other clubs are dissolved
     * ([gone]); every nation gets its clubs (ids after the old ones), and everyone without a club
     * goes through the drafts. News: one `club_reform` [] (clubs, nations).
     */
    fun migrate(closing: SeasonPlan, input: ClubInput): List<LeagueEvent> {
        if (format == AFFILIATION) return offseason(closing, input)
        val s = closing.number
        val alive = input.members.toHashSet()
        if (!(s == season && (lastSeed == null || lastSeed == closing.seed))) {
            if (s <= season) moves.removeAll { it.season >= s }
            val table = (closing.competition(Comp.CLUB) as? ClubLeague)?.order().orEmpty()
            leagueMoney(s, table, leagueHonours(closing), alive)
        }
        for (e in entries) e.squad.retainAll(alive)
        val place = (closing.competition(Comp.CLUB) as? ClubLeague)?.order().orEmpty().withIndex().associate { it.value to it.index + 1 }
        val counts = alive.groupingBy { homeOf(input.nation(it)) }.eachCount()
        val korea = clubCount(counts[Nations.KR.code] ?: 0, input.config.clubSize)
        val kept = entries.sortedWith(compareByDescending<Entry> { it.honours }.thenByDescending { it.budget }.thenBy { it.id }).take(korea).mapTo(HashSet()) { it.id }
        next = (entries.mapNotNull { number(it.id) } + goneList.mapNotNull { number(it.id) }).maxOrNull()?.plus(1) ?: 1
        for (e in entries.toList()) {
            if (e.id in kept) {
                e.nation = Nations.KR.code
                e.squad.retainAll { homeOf(input.nation(it)) == Nations.KR.code }
            } else {
                goneList += GoneClub(e.id, Nations.KR.code, e.identity.name, e.identity.emblem, e.honours, e.founded, s)
                drop(e)
            }
        }
        format = AFFILIATION
        cities = emptyList()
        reindex()
        for (nation in Nations.ALL) {
            val want = clubCount(counts[nation.code] ?: 0, input.config.clubSize)
            while (clubCountOf(nation.code) < want) newClub(nation.code, s) ?: break
        }
        sortEntries()
        val inSeason = closing.league.standings.keys
        val placed = draftNational(s, alive, input, place, inSeason)
        logPlacements(s, placed, emptySet())
        digest = ClubDigest(s, placed.count { it.key !in inSeason }, 0, 0, 0, null, emptyList(), emptyList(), emptyList(), emptyList(), reform = true)
        season = s
        lastSeed = closing.seed
        trim()
        changed()
        return news(s)
    }

    /** A national off-season already applied, asked for again: its news; members who left go, members without a club are drafted. */
    private fun againNational(closing: SeasonPlan, input: ClubInput): List<LeagueEvent> {
        val s = closing.number
        val events = news(s)
        val alive = input.members.toHashSet()
        val majors = majorHolders(input)
        val left = entries.any { e -> e.squad.any { it !in alive } }
        if (left) depart(s, alive) { k -> k in majors || tierOf(closing.league, k) == 0 }
        val free = alive.any { it !in memberOf }
        if (free) {
            val counts = nationSizes(alive, input)
            for (nation in Nations.ALL) if (clubCountOf(nation.code) == 0 && (counts[nation.code] ?: 0) > 0) {
                repeat(clubCount(counts.getValue(nation.code), input.config.clubSize)) { newClub(nation.code, s) }
            }
            sortEntries()
            logPlacements(s, draftNational(s, alive, input, emptyMap(), closing.league.standings.keys), null)
        }
        if (left || free) {
            trim()
            changed()
        }
        return events
    }

    /** A league that started over at season [s]: the abandoned timeline's moves, tables and digest go. */
    private fun restart(s: Int) {
        moves.removeAll { it.season >= s }
        placeLines.tailMap(s, true).clear()
        if ((digest?.season ?: Int.MIN_VALUE) >= s) digest = null
    }

    /** The keys holding a major title ([HonourBook.isMajor]) among [ClubInput.honours]. */
    private fun majorHolders(input: ClubInput): Set<String> =
        input.honours.filter { HonourBook.isMajor(it, input.major) }.mapTo(HashSet()) { it.key }

    /** Step 1: the club tables of [closing]'s leagues, the `cs` line, prize and honour money, titles; each club's place in its table. */
    private fun record(closing: SeasonPlan, input: ClubInput): Map<String, Int> {
        val s = closing.number
        val season = closing.league
        val honours = input.honours.filter { it.season == s }
        val ids = season.leagues.list.map { it.id }
        val strength = ids.associateWith { id ->
            Coefficients.strength(season.leagues.byId(id)?.divisions?.flatMap { season.divisions[it] }.orEmpty(), input.rating)
        }
        val place = HashMap<String, Int>()
        val lines = ArrayList<ClubPlaces>()
        for (id in Coefficients.order(ids, closing.ranking) { strength.getValue(it) }) {
            val table = ClubTable.of(season, this, id, honours)
            for ((i, row) in table.withIndex()) {
                place[row.club] = i + 1
                entry(row.club)?.let { it.budget += prize(i + 1) }
            }
            val top = table.firstOrNull() ?: continue
            entry(top.club)?.let { it.honours++ }
            lines += ClubPlaces(id, table.take(3).map { it.club }, top.points)
        }
        placeLines[s] = lines
        for (h in honours) {
            val money = honourMoney(h.kind)
            if (money <= 0) continue
            val club = if (HonourKind.base(h.kind) in CLUB_HONOURS) entry(h.key) else memberOf[h.key]?.let { entry(it) }
            club?.let { it.budget += money }
        }
        return place
    }

    /** Members not in [alive] leave; a [ClubMoveType.RETIRED] move for the [notable] ones. */
    private fun depart(s: Int, alive: Set<String>, notable: (String) -> Boolean) {
        for (e in entries) {
            val gone = e.squad.filter { it !in alive }
            if (gone.isEmpty()) continue
            e.squad.removeAll(gone.toSet())
            for (k in gone) {
                memberOf.remove(k)
                if (notable(k)) log(ClubMove(s, ClubMoveType.RETIRED, k, e.id, null))
            }
        }
    }

    /** Members next season per nation: those of its clubs and those without a club who play for it. */
    private fun nationSizes(alive: Set<String>, input: ClubInput): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (e in entries) out.merge(e.nation, e.squad.size, Int::plus)
        for (k in alive) if (k !in memberOf) out.merge(homeOf(input.nation(k)), 1, Int::plus)
        return out
    }

    private fun clubCountOf(nation: String): Int = entries.count { it.nation == nation }

    /** Step 5: each nation's window, worst club first. */
    private fun window(s: Int, input: ClubInput, place: Map<String, Int>, moved: Set<String>) {
        val order = ratingOrder(input.rating)
        val sizes = nationSizes(input.members.toHashSet(), input)
        for (nation in Nations.ALL) {
            val clubs = entries.filter { it.nation == nation.code }
            if (clubs.size < 2) continue
            val n = sizes[nation.code] ?: 0
            val min = minSize(n, clubs.size)
            val max = maxSize(n, clubs.size)
            var deals = 0
            val sold = HashSet<String>()
            val buyers = clubs.sortedWith(compareByDescending<Entry> { place[it.id] ?: Int.MAX_VALUE }.thenBy { it.budget }.thenComparing({ it.id }, ID_ORDER))
            for (buyer in buyers) {
                if (deals >= clubs.size / 2) break
                val at = place[buyer.id] ?: Int.MAX_VALUE
                val counted = buyer.squad.sortedWith(order).take(ClubTable.COUNTED)
                val floor = if (counted.size < ClubTable.COUNTED) Double.NEGATIVE_INFINITY else score(input.rating, counted.last())
                var best: Offer? = null
                for (seller in clubs) {
                    if (seller === buyer || (place[seller.id] ?: Int.MAX_VALUE) >= at || seller.squad.size <= min) continue
                    for (k in seller.squad.sortedWith(order).take(Transfers.TOP)) {
                        if (k in moved || k in sold) continue
                        val r = score(input.rating, k)
                        val price = fee(value(input.rating(k)))
                        if (r <= floor || price > buyer.budget) continue
                        val b = best
                        if (b == null || r > b.rating || (r == b.rating && (price < b.fee || (price == b.fee && k < b.key)))) best = Offer(k, seller, price, r)
                    }
                }
                val o = best ?: continue
                val seller = o.seller!!
                seller.squad.remove(o.key)
                seller.budget += o.fee
                buyer.budget -= o.fee
                if (buyer.squad.size >= max) buyer.squad.sortedWith(order).lastOrNull()?.let { release(s, buyer, listOf(it)) }
                buyer.squad += o.key
                memberOf[o.key] = buyer.id
                sold += o.key
                deals++
                log(ClubMove(s, ClubMoveType.TRANSFER, o.key, seller.id, buyer.id, o.fee))
            }
        }
    }

    /**
     * Step 6: per nation, clubs founded (more members) or dissolved (fewer, or none left: the
     * fewest titles, then the lowest place); only when `round(n / clubSize)` moved, the clubs are
     * more than 2 members off the size, and the new count brings them closer to it, so the count
     * does not churn (at size 8 a nation of 11 keeps its second club; one of 10 folds it into one).
     */
    private fun resize(s: Int, alive: Set<String>, input: ClubInput, place: Map<String, Int>) {
        val sizes = nationSizes(alive, input)
        val size = input.config.clubSize
        for (nation in Nations.ALL) {
            val code = nation.code
            val n = sizes[code] ?: 0
            val c = clubCountOf(code)
            val want = clubCount(n, size)
            fun off(clubs: Int) = Math.abs(n.toDouble() / clubs - size)
            if (want == c || (c > 0 && n > 0 && (off(c) <= 2 || off(want) >= off(c)))) continue
            if (want > c) {
                repeat(want - c) { newClub(code, s) }
                continue
            }
            val doomed = entries.filter { it.nation == code }
                .sortedWith(compareBy<Entry> { it.honours }.thenByDescending { place[it.id] ?: Int.MAX_VALUE }.thenComparing({ it.id }, ID_ORDER.reversed()))
                .take(c - want)
            for (e in doomed) {
                release(s, e, e.squad.toList())
                goneList += GoneClub(e.id, e.nation, e.identity.name, e.identity.emblem, e.honours, e.founded, s)
                drop(e)
            }
        }
        sortEntries()
    }

    /**
     * Step 6, after the resize: a nation whose members without a club cannot bring every club up
     * to the size floor ([minSize]: a club just founded, or one thinned by retirements) has its
     * biggest clubs (then the better placed, then id) let their weakest member of the nation go
     * ([ClubMoveType.RELEASED]), one at a time, until they can; the draft then gives each pick to
     * the smallest club.
     */
    private fun balance(s: Int, alive: Set<String>, input: ClubInput, place: Map<String, Int>) {
        val sizes = nationSizes(alive, input)
        val free = HashMap<String, Int>()
        for (k in alive) if (k !in memberOf) free.merge(homeOf(input.nation(k)), 1, Int::plus)
        val order = ratingOrder(input.rating)
        val biggest = compareByDescending<Entry> { it.squad.size }.thenBy { place[it.id] ?: Int.MAX_VALUE }.thenComparing({ it.id }, ID_ORDER)
        for (nation in Nations.ALL) {
            val code = nation.code
            val clubs = entries.filter { it.nation == code }
            if (clubs.size < 2) continue
            val min = minSize(sizes[code] ?: 0, clubs.size)
            var short = clubs.sumOf { maxOf(0, min - it.squad.size) } - (free[code] ?: 0)
            while (short > 0) {
                val big = clubs.minWith(biggest)
                if (big.squad.size <= min) break
                val weakest = big.squad.filter { homeOf(input.nation(it)) == code }.sortedWith(order).lastOrNull() ?: break
                release(s, big, listOf(weakest))
                short--
            }
        }
    }

    /** A new club of nation [code] after season [s] (null when no id is free, [MAX_ID]). */
    private fun newClub(code: String, s: Int): Entry? {
        val nation = Nations.of(code) ?: return null
        val id = nextId(s) ?: return null
        val own = entries.filter { it.nation == code }
        val k = own.size + goneList.count { it.nation == code }
        // No name of a club now, nor of a dissolved one whose id still stands (a new club is no refounded one).
        val names = entries.mapTo(HashSet()) { it.identity.name }.apply { goneList.mapTo(this) { it.name } }
        val identity = ClubNames.identity(id, nation, k, seed, own.mapTo(HashSet()) { it.identity.mascot }, names)
        return Entry(id, code, identity, ArrayList(), START_BUDGET, 0, s + 1).also { add(it) }
    }

    /**
     * The next id: `c<next>` up to [MAX_ID], then the lowest id of a club dissolved more than
     * [Archive.LINE_SEASONS] seasons before [s] (no season line can point at it any more), whose
     * [gone] line goes; null when there is none.
     */
    private fun nextId(s: Int): String? {
        while (next <= MAX_ID) {
            val id = idOf(next++)
            if (entry(id) == null && gone(id) == null) return id
        }
        val old = goneList.filter { s - it.dissolved > Archive.LINE_SEASONS }.minWithOrNull(compareBy(ID_ORDER) { it.id }) ?: return null
        goneList.remove(old)
        return old.id
    }

    private fun sortEntries() {
        entries.sortWith(compareBy(ID_ORDER) { it.id })
    }

    /** A member placed by the draft ([pick] within its nation's draft) or at its mentor's club ([youth]). */
    private class Placement(val key: String, val club: String, val pick: Int, val youth: Boolean)

    /**
     * Step 7: every member without a club, nation by nation: pupils (evolved players new to the
     * league: not in [played], the closing season's members) at their mentor's club first, then
     * the draft. An evolved player released or set free by a dissolved club is drafted like anyone.
     */
    private fun draftNational(s: Int, alive: Set<String>, input: ClubInput, place: Map<String, Int>, played: Set<String>): List<Placement> {
        val order = ratingOrder(input.rating)
        val sizes = nationSizes(alive, input)
        val free = alive.filter { it !in memberOf }.groupBy { homeOf(input.nation(it)) }
        val out = ArrayList<Placement>()
        for (nation in Nations.ALL) {
            val pool = free[nation.code]?.sortedWith(order) ?: continue
            val clubs = entries.filter { it.nation == nation.code }
            if (clubs.isEmpty()) continue
            val max = maxSize(sizes[nation.code] ?: 0, clubs.size)
            val left = ArrayList<String>()
            for (k in pool) {
                val mentor = if (input.origin(k) == Origin.EVOLVED && k !in played) input.mentor(k) else null
                val club = mentor?.let { memberOf[it] }?.let { entry(it) }?.takeIf { it.nation == nation.code && it.squad.size < max }
                if (club == null) {
                    left += k
                    continue
                }
                club.squad += k
                memberOf[k] = club.id
                out += Placement(k, club.id, 0, true)
            }
            val turn = compareBy<Entry> { it.squad.size }.thenByDescending { place[it.id] ?: Int.MAX_VALUE }.thenComparing({ it.id }, ID_ORDER)
            for ((i, k) in left.withIndex()) {
                val club = clubs.minWith(turn)
                club.squad += k
                memberOf[k] = club.id
                out += Placement(k, club.id, i + 1, false)
            }
        }
        return out
    }

    /**
     * The moves of [placed]: every youth placement; a draft pick when it is its club's first of
     * the window or one of [named] (every pick when [named] is null).
     */
    private fun logPlacements(s: Int, placed: List<Placement>, named: Set<String>?) {
        val picked = moves.filter { it.season == s && it.type == ClubMoveType.DRAFT }.mapNotNullTo(HashSet()) { it.to }
        for (p in placed) {
            when {
                p.youth -> log(ClubMove(s, ClubMoveType.YOUTH, p.key, null, p.club))
                named == null || picked.add(p.club) || p.key in named -> log(ClubMove(s, ClubMoveType.DRAFT, p.key, null, p.club, p.pick))
            }
        }
    }

    /** The news of the national off-season after [s], from what the book keeps: its places, moves, clubs and [digest]. */
    private fun news(s: Int): List<LeagueEvent> {
        val d = digest?.takeIf { it.season == s } ?: return emptyList()
        val out = ArrayList<LeagueEvent>()
        fun event(kind: String, keys: List<String>, args: List<String> = emptyList()) {
            out += LeagueEvent(kind, s, keys, args, Comp.CLUB)
        }
        val lines = places(s).filter { it.clubs.isNotEmpty() }
        for (p in lines.take(NEWS_LEAGUES)) event("club_title", listOf(p.clubs.first()), listOf(p.league.ifEmpty { "-" }, points(p.points)))
        if (lines.size > NEWS_LEAGUES) event("club_champions", lines.drop(NEWS_LEAGUES).take(NAMED).map { it.clubs.first() }, listOf((lines.size - NEWS_LEAGUES).toString()))
        if (d.reform) {
            event("club_reform", emptyList(), listOf(entries.size.toString(), entries.map { it.nation }.distinct().size.toString()))
            return out
        }
        val ms = moves.filter { it.season == s }
        fun move(type: ClubMoveType, key: String) = ms.lastOrNull { it.type == type && it.ai == key }
        for (t in d.homeNamed) move(ClubMoveType.HOME, t.key)?.let { m ->
            event("return_home", listOf(t.key, m.from ?: "-", m.to ?: "-"), listOf(t.fromLeague.ifEmpty { "-" }, t.toLeague.ifEmpty { "-" }))
        }
        for (t in d.abroadNamed) move(ClubMoveType.ABROAD, t.key)?.let { m ->
            event("transfer_abroad", listOf(t.key, m.from ?: "-", m.to ?: "-"), listOf(t.fromLeague.ifEmpty { "-" }, t.toLeague.ifEmpty { "-" }, m.amount.toString()))
        }
        if (d.abroad + d.returns > 0) event("transfer_window", (d.abroadNamed + d.homeNamed).map { it.key }.distinct(), listOf(d.abroad.toString(), d.returns.toString()))
        for (k in d.bought) move(ClubMoveType.TRANSFER, k)?.let { m -> event("transfer", listOf(k, m.from ?: "-", m.to ?: "-"), listOf(m.amount.toString())) }
        val dissolved = goneList.filter { it.dissolved == s }.sortedWith(compareBy(ID_ORDER) { it.id })
        val founded = entries.filter { it.founded == s + 1 }
        if (dissolved.size + founded.size <= NAMED) {
            for (g in dissolved) event("club_dissolved", listOf(g.id), listOf(ms.count { it.type == ClubMoveType.RELEASED && it.from == g.id }.toString()))
            for (e in founded) event("club_founded", listOf(e.id), listOf(e.nation))
        } else {
            // A new club size (or a big change of the members): one article, not dozens.
            event("club_digest", (founded.map { it.id } + dissolved.map { it.id }).take(2 * NAMED), listOf(founded.size.toString(), dissolved.size.toString()))
        }
        for (k in d.drafted) {
            val youth = move(ClubMoveType.YOUTH, k)
            if (youth != null) event("youth", listOf(k, youth.to ?: "-")) else move(ClubMoveType.DRAFT, k)?.let { m -> event("draft", listOf(k, m.to ?: "-"), listOf(m.amount.toString())) }
        }
        if (d.domestic + d.rookies > 0) {
            val b = d.biggest
            event(
                "market_digest", (d.drafted + d.bought).distinct(),
                listOf(d.domestic.toString(), d.rookies.toString(), b?.ai ?: "-", b?.from ?: "-", b?.to ?: "-", (b?.amount ?: 0).toString()),
            )
        }
        return out
    }

    // ---- saving ----------------------------------------------------------------------------------

    /**
     * league/clubs.txt. The club league's (`clubs 1`): a header, the meta line (seed, last season,
     * cities, the last season's plan seed), one line per club and per history move. A national
     * book's (`clubs 2`): the meta line (seed, last season, plan seed, next id number), one line
     * per club (with its nation, founding season and members) and per dissolved club, the newest
     * seasons' club-table places (`cs`), the last off-season's digest (`od`) and the moves.
     */
    fun encode(): String = if (format == AFFILIATION) encodeNational() else buildString {
        append("$HEADER\n")
        append("meta $seed $season ${if (cities.isEmpty()) "-" else cities.joinToString(",") { esc(it) }} ${lastSeed ?: "-"}\n")
        for (e in entries) {
            val id = e.identity
            val em = id.emblem
            append("club ${e.id} ${esc(id.city)} ${esc(id.mascot)} ${esc(id.name)} ${em.shape} ${hex(em.colour1)} ${hex(em.colour2)} ")
            append("${esc(em.initial.toString())} ${e.budget} ${e.honours} ${e.squad.joinToString(",").ifEmpty { "-" }}\n")
        }
        for (m in moves) append("move ${m.season} ${m.type.code} ${m.ai} ${m.from ?: "-"} ${m.to ?: "-"} ${m.amount}\n")
    }

    private fun encodeNational(): String = buildString {
        append("$HEADER_NATIONAL\n")
        append("meta $seed $season ${lastSeed ?: "-"} $next\n")
        for (e in entries) {
            val id = e.identity
            val em = id.emblem
            append("club ${e.id} ${e.nation} ${esc(id.city)} ${esc(id.mascot)} ${esc(id.name)} ${em.shape} ${hex(em.colour1)} ${hex(em.colour2)} ")
            append("${esc(em.initial.toString())} ${e.budget} ${e.honours} ${e.founded} ${e.squad.joinToString(",").ifEmpty { "-" }}\n")
        }
        for (g in goneList) {
            val em = g.emblem
            append("gone ${g.id} ${g.nation} ${esc(g.name)} ${em.shape} ${hex(em.colour1)} ${hex(em.colour2)} ${esc(em.initial.toString())} ${g.honours} ${g.founded} ${g.dissolved}\n")
        }
        for ((n, lines) in placeLines) {
            if (lines.isEmpty()) continue
            append("cs $n ").append(lines.joinToString(";") { p -> "${p.league.ifEmpty { "-" }}:${p.clubs.joinToString(",")}:${Math.round(p.points * 10)}" }).append('\n')
        }
        digest?.let { d ->
            fun keys(l: List<String>) = l.joinToString(",").ifEmpty { "-" }
            fun named(l: List<ClubDigest.Named>) = l.joinToString(",") { "${it.key}:${it.fromLeague.ifEmpty { "-" }}:${it.toLeague.ifEmpty { "-" }}" }.ifEmpty { "-" }
            val b = d.biggest
            append("od ${d.season} ${d.rookies} ${d.domestic} ${d.abroad} ${d.returns} ${b?.ai ?: "-"} ${b?.from ?: "-"} ${b?.to ?: "-"} ${b?.amount ?: 0} ")
            append("${keys(d.drafted)} ${keys(d.bought)} ${named(d.abroadNamed)} ${named(d.homeNamed)} ${if (d.reform) 1 else 0}\n")
        }
        for (m in moves) append("move ${m.season} ${m.type.code} ${m.ai} ${m.from ?: "-"} ${m.to ?: "-"} ${m.amount}\n")
    }

    companion object {
        const val HEADER = "clubs 1"

        /** The header of a national book. */
        const val HEADER_NATIONAL = "clubs 2"

        /** [format] of the club league's book (`clubs 1`). */
        const val CLUB_LEAGUE = 1

        /** [format] of national leagues' book (`clubs 2`): clubs as every member's affiliation. */
        const val AFFILIATION = 2

        const val START_BUDGET = 100

        /** Lineup size: boards in a club fixture. */
        const val BOARDS = 5

        /** Buys per club in a transfer window. */
        const val BUYS = 2

        /** History moves the club league's book keeps for the screen ([historyCap] in a national book). */
        const val HISTORY = 100

        /** The club league's ids go up to "c99". */
        const val MAX_CLUBS = 99

        /** A national book's ids go up to "c1295" (two base-36 digits in the archive's `sx` entries). */
        const val MAX_ID = Archive.MAX_CLUB

        /** Clubs per nation at most. */
        const val MAX_PER_NATION = 16

        /** Seasons of `cs` lines kept. */
        const val PLACE_SEASONS = 20

        /** Leagues whose club-table champion gets an article (`club_title`). */
        const val NEWS_LEAGUES = 4

        /** Players named in each kind of off-season news. */
        const val NAMED = 3

        private const val DRAFT_SALT = 0xD2AF7L
        private const val WINDOW_SALT = 0x7A45FL

        /** Honours keyed by a club id (their money goes to that club). */
        private val CLUB_HONOURS = setOf("CLUB", "CLUB2", "CLUBCUP", "CLUBCUP2")

        private val VERSIONS = AtomicInteger()

        /** An AI's transfer value: `max(1, (rating - 1100) / 10)`, whole units. */
        fun value(rating: Double): Int = if (rating.isNaN()) 1 else maxOf(1, ((rating - 1100) / 10).toInt())

        /** Price of a player bought from another club: 1.3 times its [value], rounded up (no overflow). */
        fun fee(value: Int): Int = ((13L * value + 9) / 10).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        /** Prize money for final place [position] (1 = champion): `40 - 2 * position`, never below 0. */
        fun prize(position: Int): Int = maxOf(0, 40 - 2 * position)

        /** Money a club gets for a member's (or its own) cup, CL, 대륙 챔피언스 or 클럽 대항전 place (honour [kind]); 0 for other honours. */
        fun honourMoney(kind: String): Int = when (HonourKind.base(kind)) {
            "CUP" -> 20
            "CUP2" -> 10
            "CL" -> 30
            "CL2" -> 15
            "CCL" -> 25
            "CCL2" -> 12
            "CLUBCUP" -> 30
            "CLUBCUP2" -> 15
            else -> 0
        }

        /** "c01" for club 1, "c100" for club 100. */
        fun idOf(n: Int): String = "c" + n.toString().padStart(2, '0')

        /** The number of a "c<n>" id; null for another shape. */
        fun number(id: String): Int? = id.takeIf { it.startsWith("c") }?.drop(1)?.toIntOrNull()?.takeIf { it > 0 }

        /** Club ids by number ("c99" before "c100"), other ids after them by text. */
        val ID_ORDER: Comparator<String> = compareBy<String> { number(it) ?: Int.MAX_VALUE }.thenBy { it }

        /** Clubs for a nation of [members]: `clamp(round(members / size), 1, 16)`, none without members. */
        fun clubCount(members: Int, size: Int): Int = if (members <= 0) 0 else Math.round(members.toDouble() / size).toInt().coerceIn(1, MAX_PER_NATION)

        /** The fewest members a club of a nation of [members] in [clubs] clubs keeps in the window: `floor(0.75 n / C)`. */
        fun minSize(members: Int, clubs: Int): Int = if (clubs <= 0) 0 else Math.floorDiv(3 * members, 4 * clubs)

        /** The most members a club of a nation of [members] in [clubs] clubs takes: `ceil(1.25 n / C) + 1`. */
        fun maxSize(members: Int, clubs: Int): Int = if (clubs <= 0) 0 else -Math.floorDiv(-5 * members, 4 * clubs) + 1

        /** History moves a national book of [members] keeps: `clamp(members / 4, 60, 300)`. */
        fun historyCap(members: Int): Int = (members / 4).coerceIn(60, 300)

        /** The nation a member plays for without a club: its own when this version knows it, else Korea's. */
        fun homeOf(nation: String?): String = nation?.takeIf { Nations.of(it) != null } ?: Nations.KR.code

        /**
         * A new club league: [clubs] clubs named after [cities] (the app's hometowns), each with
         * [START_BUDGET], and a snake draft of [ranking] (best first, me excluded): the pick
         * order is drawn from [seed] and reverses every round, so squads are balanced. AIs left
         * over are free agents; with too few AIs the last squads are short.
         */
        fun create(ranking: List<String>, clubs: Int, squad: Int, seed: Long, cities: List<String>): ClubBook {
            val book = ClubBook(seed, cities.toList(), CLUB_LEAGUE)
            for (n in 1..clubs.coerceIn(0, MAX_CLUBS)) book.add(book.newEntry(idOf(n)))
            val turns = book.entries.shuffled(Random(DiceStream.mix(seed, DRAFT_SALT)))
            val pool = ranking.distinct()
            var next = 0
            for (round in 0 until squad) {
                for (e in if (round % 2 == 0) turns else turns.asReversed()) {
                    if (next < pool.size) e.squad += pool[next++]
                }
            }
            book.reindex()
            return book
        }

        /**
         * The clubs of a new national league (a new league, a renewed one, a league whose book is
         * missing): per nation (each member's own, [homeOf]) `C(n)` clubs ([clubCount] with
         * [LeagueConfig.clubSize]) named after its cities, each with [START_BUDGET], and a snake
         * draft of its members by [rating] whose pick order is drawn from [seed]. Its off-season
         * after [season] counts as applied (a repeat changes nothing); its clubs first play in
         * season `season + 1`.
         */
        fun found(members: Collection<String>, nation: (String) -> String?, rating: (String) -> Double, season: Int, seed: Long, config: LeagueConfig): ClubBook {
            val book = ClubBook(seed, emptyList(), AFFILIATION)
            val byNation = members.distinct().groupBy { homeOf(nation(it)) }
            val order = ratingOrder(rating)
            for ((index, n) in Nations.ALL.withIndex()) {
                val pool = byNation[n.code]?.sortedWith(order) ?: continue
                val clubs = (0 until clubCount(pool.size, config.clubSize)).mapNotNull { book.newClub(n.code, season) }
                if (clubs.isEmpty()) continue
                val turns = clubs.shuffled(Random(DiceStream.mix(seed, DRAFT_SALT, index.toLong())))
                for ((i, k) in pool.withIndex()) {
                    val round = i / turns.size
                    val e = if (round % 2 == 0) turns[i % turns.size] else turns[turns.size - 1 - i % turns.size]
                    e.squad += k
                }
            }
            book.sortEntries()
            book.reindex()
            book.season = season
            book.lastSeed = seed
            book.changed()
            return book
        }

        /**
         * Reads league/clubs.txt (`clubs 1` or `clubs 2`); lines that cannot be read are skipped,
         * so a cut file keeps the clubs before the cut. Throws when the header or the meta line is
         * missing (the caller then makes a new book). An AI listed in two clubs stays in the
         * first; a club of a nation this version does not know is left out (its members are
         * drafted at the next off-season).
         */
        fun decode(text: String): ClubBook {
            val lines = text.lines()
            val header = lines.firstOrNull()?.trim()
            if (header == HEADER_NATIONAL) return decodeNational(lines)
            require(header == HEADER) { "not a clubs file" }
            val book = lines.firstNotNullOfOrNull { l ->
                runCatching {
                    val f = l.trim().split(' ')
                    if (f[0] != "meta") return@runCatching null
                    val cities = if (f.getOrNull(3).let { it == null || it == "-" }) emptyList() else f[3].split(',').map { unesc(it) }
                    ClubBook(f[1].toLong(), cities, CLUB_LEAGUE).apply {
                        season = f[2].toInt()
                        lastSeed = f.getOrNull(4)?.toLongOrNull()
                    }
                }.getOrNull()
            } ?: error("no meta line in the clubs file")
            val seen = HashSet<String>()
            for (l in lines) {
                runCatching {
                    val f = l.trim().split(' ')
                    when (f[0]) {
                        "club" -> {
                            require(f.size >= 12)
                            val id = f[1]
                            if (book.entry(id) != null) return@runCatching
                            val emblem = emblemOf(f, 5)
                            val identity = ClubNames.Identity(unesc(f[2]), unesc(f[3]), unesc(f[4]), emblem)
                            val squad = f[11].split(',').filter { it.isNotEmpty() && it != "-" && seen.add(it) }
                            book.add(Entry(id, "", identity, squad.toMutableList(), maxOf(0, f[9].toInt()), maxOf(0, f[10].toInt())))
                        }
                        "move" -> book.moves += moveOf(f) ?: return@runCatching
                    }
                }
            }
            book.entries.sortBy { it.id }
            book.trim()
            book.reindex()
            return book
        }

        private fun decodeNational(lines: List<String>): ClubBook {
            val book = lines.firstNotNullOfOrNull { l ->
                runCatching {
                    val f = l.trim().split(' ')
                    if (f[0] != "meta") return@runCatching null
                    ClubBook(f[1].toLong(), emptyList(), AFFILIATION).apply {
                        season = f[2].toInt()
                        lastSeed = f.getOrNull(3)?.toLongOrNull()
                        next = f.getOrNull(4)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    }
                }.getOrNull()
            } ?: error("no meta line in the clubs file")
            val seen = HashSet<String>()
            for (l in lines) {
                runCatching {
                    val f = l.trim().split(' ')
                    when (f[0]) {
                        "club" -> {
                            require(f.size >= 14 && Nations.of(f[2]) != null)
                            val id = f[1]
                            if (book.entry(id) != null) return@runCatching
                            val identity = ClubNames.Identity(unesc(f[3]), unesc(f[4]), unesc(f[5]), emblemOf(f, 6))
                            val squad = f[13].split(',').filter { it.isNotEmpty() && it != "-" && seen.add(it) }
                            book.add(Entry(id, f[2], identity, squad.toMutableList(), maxOf(0, f[10].toInt()), maxOf(0, f[11].toInt()), f[12].toInt()))
                        }
                        "gone" -> {
                            require(f.size >= 11)
                            if (book.gone(f[1]) != null) return@runCatching
                            book.goneList += GoneClub(f[1], f[2], unesc(f[3]), emblemOf(f, 4), maxOf(0, f[8].toInt()), f[9].toInt(), f[10].toInt())
                        }
                        "cs" -> book.placeLines[f[1].toInt()] = f[2].split(';').map { part ->
                            val p = part.split(':')
                            ClubPlaces(if (p[0] == "-") "" else p[0], p[1].split(',').filter { it.isNotEmpty() }, p[2].toLong() / 10.0)
                        }
                        "od" -> {
                            fun keys(s: String) = if (s == "-") emptyList() else s.split(',').filter { it.isNotEmpty() }
                            fun named(s: String) = keys(s).map { e ->
                                val p = e.split(':')
                                ClubDigest.Named(p[0], if (p[1] == "-") "" else p[1], if (p[2] == "-") "" else p[2])
                            }
                            val biggest = f[6].takeIf { it != "-" }?.let { ClubMove(f[1].toInt(), ClubMoveType.TRANSFER, it, f[7].takeIf { x -> x != "-" }, f[8].takeIf { x -> x != "-" }, f[9].toInt()) }
                            book.digest = ClubDigest(
                                f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), biggest,
                                keys(f[10]), keys(f[11]), named(f[12]), named(f[13]), f[14] == "1",
                            )
                        }
                        "move" -> book.moves += moveOf(f) ?: return@runCatching
                    }
                }
            }
            // A next id number at or below an id in use (a hand-edited or cut file) moves past it.
            val used = (book.entries.map { it.id } + book.goneList.map { it.id }).mapNotNull { number(it) }.maxOrNull() ?: 0
            if (book.next <= MAX_ID && book.next <= used) book.next = used + 1
            book.sortEntries()
            book.reindex()
            book.trim()
            return book
        }

        private fun emblemOf(f: List<String>, at: Int): Emblem {
            val initial = unesc(f[at + 3]).firstOrNull() ?: '?'
            return Emblem(Math.floorMod(f[at].toInt(), Emblem.SHAPES), f[at + 1].toLong(16).toInt(), f[at + 2].toLong(16).toInt(), initial)
        }

        private fun moveOf(f: List<String>): ClubMove? {
            val type = ClubMoveType.of(f[2].single()) ?: return null
            return ClubMove(f[1].toInt(), type, f[3], f[4].takeIf { it != "-" }, f[5].takeIf { it != "-" }, f[6].toInt())
        }

        /** Best rated first (an unrated AI last), then by key. */
        internal fun ratingOrder(rating: (String) -> Double): Comparator<String> =
            compareByDescending<String> { score(rating, it) }.thenBy { it }

        private fun score(rating: (String) -> Double, key: String): Double = rating(key).let { if (it.isNaN()) Double.NEGATIVE_INFINITY else it }

        /** Club points as written in the news: one decimal. */
        private fun points(p: Double): String = String.format(java.util.Locale.ROOT, "%.1f", p)

        /** The tier of [key]'s division in [season]; null when it did not play in it. */
        private fun tierOf(season: Season, key: String): Int? = season.divisionOf(key)?.let { season.leagues.tier(it) }

        private fun hex(c: Int): String = (c.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')

        /** Characters that would break a space-separated line or its inner lists. */
        private const val SPECIAL = " %,;:-|\t\r\n"

        /** Percent-escapes [SPECIAL] characters; "-" stands for an empty text. */
        private fun esc(s: String): String = if (s.isEmpty()) "-" else buildString {
            for (c in s) if (c in SPECIAL) append('%').append(c.code.toString(16).padStart(2, '0')) else append(c)
        }

        private fun unesc(s: String): String {
            if (s == "-") return ""
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val code = if (s[i] == '%' && i + 3 <= s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                } else {
                    out.append(s[i])
                    i++
                }
            }
            return out.toString()
        }
    }
}

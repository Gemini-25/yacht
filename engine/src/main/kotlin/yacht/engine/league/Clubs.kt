package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random

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
 * A club of the club league (docs/LEAGUE_SPEC.md section 2.2), as an immutable snapshot.
 * [squad] holds its AIs (no AI is in two clubs), [budget] its money for the transfer window and
 * [honours] its club league titles.
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
)

/** What a [ClubMove] was; [label] is shown on the club card. */
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
 * One line of the clubs' history, for the club card: AI [ai] moved from club [from] (null: a
 * free agent) to club [to] (null: nowhere) after season [season]. [amount] is the fee of a
 * transfer and the pick number of a draft pick (0 otherwise).
 */
data class ClubMove(val season: Int, val kind: ClubMoveKind, val ai: String, val from: String?, val to: String?, val amount: Int = 0)

/**
 * Club names and badges: deterministic from the club id and the book's seed, so a club keeps its
 * name and a club added later (a bigger league) never renames the others.
 */
object ClubNames {
    /** Mascots, one per club (at least as many as the largest club league). */
    val MASCOTS = listOf(
        "호랑이", "독수리", "돌고래", "반달곰", "늑대", "여우", "송골매", "사자",
        "표범", "상어", "고래", "불사조", "황소", "백마", "까치", "두루미",
        "부엉이", "해달", "펭귄", "코뿔소", "치타", "수달", "갈매기", "노루",
        "거북", "물개", "전사", "번개", "폭풍", "파도", "혜성", "유니콘",
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

    /**
     * The identity of club [id] ("c01", "c02", ...) in a book seeded with [seed]. Mascots and
     * the first 12 main colours are dealt without repeats, and [cities] (the hometowns) go round
     * once before any city gets a second club, so names stay unique (up to 32 clubs).
     */
    fun identity(id: String, seed: Long, cities: List<String>): Identity {
        val i = indexOf(id)
        fun deal(size: Int, salt: Long) = (0 until size).shuffled(Random(DiceStream.mix(seed, SALT, salt)))[i % size]
        val mascot = MASCOTS[deal(MASCOTS.size, 1)]
        val city = if (cities.isEmpty()) "" else cities[deal(cities.size, 2)]
        val rng = Random(DiceStream.mix(seed, SALT, 3, i.toLong()))
        val emblem = Emblem(rng.nextInt(Emblem.SHAPES), PRIMARY[deal(PRIMARY.size, 4)], ACCENT[rng.nextInt(ACCENT.size)], mascot.first())
        val name = if (city.isEmpty()) mascot else "$city $mascot"
        return Identity(city, mascot, name, emblem)
    }

    /** "c07" is club 6 (0-based); an id of another shape gets a place from its hash. */
    private fun indexOf(id: String): Int =
        id.drop(1).toIntOrNull()?.minus(1)?.takeIf { it >= 0 } ?: Math.floorMod(id.hashCode(), 1000)
}

/**
 * Every club of the club league, kept between seasons (league/clubs.txt): squads, budgets,
 * titles and the newest [HISTORY] moves. Built once by [create] (a snake draft), then changed
 * only by [offseason] (prize money, retirements, the transfer window and the draft; spec
 * section 5 step 4). Invariant: no AI is in two clubs. Not thread-safe: callers synchronise.
 */
class ClubBook private constructor(
    /** Seed of the club names and badges (clubs added later are named from it too). */
    val seed: Long,
    /** Hometowns the clubs are named after. */
    val cities: List<String>,
) {
    /** A club as the book changes it. */
    private class Entry(val id: String, val identity: ClubNames.Identity, val squad: MutableList<String>, var budget: Int, var honours: Int) {
        fun snapshot() = Club(id, identity.name, identity.city, identity.mascot, identity.emblem, squad.toList(), budget, honours)
    }

    private val entries = ArrayList<Entry>()
    private val memberOf = HashMap<String, String>()
    private val moves = ArrayDeque<ClubMove>()

    /** The last season whose off-season was applied (0 before any): a repeat changes nothing. */
    var season: Int = 0
        private set

    /**
     * The seed of the plan whose off-season was applied last (null before any, or from a file
     * without it): with [season] it tells a repeated rollover from another league's season of
     * the same number.
     */
    private var lastSeed: Long? = null

    /** Every club, by id. */
    val clubs: List<Club> get() = entries.map { it.snapshot() }

    /**
     * The newest moves, oldest first: [HISTORY] lines, or more when the last off-season alone
     * made more (it is kept whole, so a repeated rollover tells the same news).
     */
    val history: List<ClubMove> get() = moves.toList()

    fun club(id: String): Club? = entry(id)?.snapshot()

    /** The club AI [key] plays for, or null for a free agent. */
    fun clubOf(key: String): String? = memberOf[key]

    /** Moves into or out of club [id], newest last. */
    fun history(id: String): List<ClubMove> = moves.filter { it.from == id || it.to == id }

    private fun entry(id: String): Entry? = entries.firstOrNull { it.id == id }

    private fun newEntry(id: String) = Entry(id, ClubNames.identity(id, seed, cities), ArrayList(), START_BUDGET, 0)

    private fun reindex() {
        memberOf.clear()
        for (e in entries) for (k in e.squad) memberOf[k] = e.id
    }

    private fun log(m: ClubMove) {
        moves.addLast(m)
    }

    /** Drops the oldest moves beyond [HISTORY], never those of the last off-season. */
    private fun trim() {
        while (moves.size > HISTORY && moves.first().season < season) moves.removeFirst()
    }

    // ---- the off-season --------------------------------------------------------------------------

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
        if (season == this.season && (lastSeed == null || lastSeed == seed)) return replay(season)
        if (season <= this.season) moves.removeAll { it.season >= season }
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
            for (k in gone) log(ClubMove(season, ClubMoveKind.RETIRED, k, e.id, null))
        }
        reindex()
        val events = ArrayList<LeagueEvent>()
        if (clubs > 0) {
            val order = ratingOrder(rating)
            val place = final.withIndex().associate { it.value to it.index }
            resize(season, place, clubs.coerceAtMost(MAX_CLUBS), squad, order)
            window(season, rating, order, alive, squad, seed, events)
            draft(season, place, order, alive, squad, events)
        }
        this.season = season
        lastSeed = seed
        trim()
        return events
    }

    /**
     * [offseason] for a finished [plan]: its club league's final table and every competition's
     * honours, the club count and squad size of the next season's [config], and the plan's seed.
     */
    fun offseason(plan: SeasonPlan, rating: (String) -> Double, alive: Set<String>, config: LeagueConfig): List<LeagueEvent> {
        val table = (plan.competition(Comp.CLUB) as? ClubLeague)?.order().orEmpty()
        val honours = (plan.comps + plan.post).flatMap { c -> runCatching { c.honours() }.getOrDefault(emptyList()) }
        return offseason(plan.number, table, honours, rating, alive, config.clubs, config.squad, plan.seed)
    }

    /** The news of an off-season already applied, from the history (as far as it reaches back). */
    private fun replay(season: Int): List<LeagueEvent> = moves.filter { it.season == season }.mapNotNull { m ->
        when (m.kind) {
            ClubMoveKind.TRANSFER -> LeagueEvent("transfer", season, listOf(m.ai, m.from ?: "-", m.to ?: "-"), listOf(m.amount.toString()), Comp.CLUB)
            ClubMoveKind.DRAFT -> LeagueEvent("draft", season, listOf(m.ai, m.to ?: "-"), listOf(m.amount.toString()), Comp.CLUB)
            else -> null
        }
    }

    private fun release(season: Int, e: Entry, keys: Collection<String>) {
        e.squad.removeAll(keys.toSet())
        for (k in keys) {
            memberOf.remove(k)
            log(ClubMove(season, ClubMoveKind.RELEASED, k, e.id, null))
        }
    }

    private fun resize(season: Int, place: Map<String, Int>, clubs: Int, squad: Int, order: Comparator<String>) {
        if (entries.size > clubs) {
            val doomed = entries.sortedWith(compareByDescending<Entry> { place[it.id] ?: Int.MAX_VALUE }.thenByDescending { it.id })
                .take(entries.size - clubs)
            for (e in doomed) {
                release(season, e, e.squad.toList())
                entries.remove(e)
            }
        }
        var n = 1
        while (entries.size < clubs) {
            val id = idOf(n++)
            if (entries.none { it.id == id }) entries += newEntry(id)
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
                log(ClubMove(season, ClubMoveKind.TRANSFER, o.key, o.seller?.id, buyer.id, o.fee))
                events += LeagueEvent("transfer", season, listOf(o.key, o.seller?.id ?: "-", buyer.id), listOf(o.fee.toString()), Comp.CLUB)
            }
        }
    }

    private fun draft(season: Int, place: Map<String, Int>, order: Comparator<String>, alive: Set<String>, squad: Int, events: MutableList<LeagueEvent>) {
        val turns = entries.sortedWith(compareByDescending<Entry> { place[it.id] ?: Int.MAX_VALUE }.thenBy { it.id })
        val pool = ArrayDeque(alive.filter { it !in memberOf }.sortedWith(order))
        var pick = 0
        do {
            var any = false
            for (e in turns) {
                if (e.squad.size >= squad || pool.isEmpty()) continue
                val k = pool.removeFirst()
                e.squad += k
                memberOf[k] = e.id
                pick++
                any = true
                log(ClubMove(season, ClubMoveKind.DRAFT, k, null, e.id, pick))
                events += LeagueEvent("draft", season, listOf(k, e.id), listOf(pick.toString()), Comp.CLUB)
            }
        } while (any)
    }

    // ---- saving ----------------------------------------------------------------------------------

    /**
     * league/clubs.txt: a header, the meta line (seed, last season, cities, the last season's
     * plan seed), one line per club and per history move.
     */
    fun encode(): String = buildString {
        append("$HEADER\n")
        append("meta $seed $season ${if (cities.isEmpty()) "-" else cities.joinToString(",") { esc(it) }} ${lastSeed ?: "-"}\n")
        for (e in entries) {
            val id = e.identity
            val em = id.emblem
            append("club ${e.id} ${esc(id.city)} ${esc(id.mascot)} ${esc(id.name)} ${em.shape} ${hex(em.colour1)} ${hex(em.colour2)} ")
            append("${esc(em.initial.toString())} ${e.budget} ${e.honours} ${e.squad.joinToString(",").ifEmpty { "-" }}\n")
        }
        for (m in moves) append("move ${m.season} ${m.kind.code} ${m.ai} ${m.from ?: "-"} ${m.to ?: "-"} ${m.amount}\n")
    }

    companion object {
        const val HEADER = "clubs 1"
        const val START_BUDGET = 100

        /** Lineup size: boards in a club fixture. */
        const val BOARDS = 5

        /** Buys per club in a transfer window. */
        const val BUYS = 2

        /** History moves kept for the screen. */
        const val HISTORY = 100

        /** Ids go up to "c99". */
        const val MAX_CLUBS = 99

        private const val DRAFT_SALT = 0xD2AF7L
        private const val WINDOW_SALT = 0x7A45FL

        /** An AI's transfer value: `max(1, (rating - 1100) / 10)`, whole units. */
        fun value(rating: Double): Int = if (rating.isNaN()) 1 else maxOf(1, ((rating - 1100) / 10).toInt())

        /** Price of a player bought from another club: 1.3 times its [value], rounded up (no overflow). */
        fun fee(value: Int): Int = ((13L * value + 9) / 10).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        /** Prize money for final place [position] (1 = champion): `40 - 2 * position`, never below 0. */
        fun prize(position: Int): Int = maxOf(0, 40 - 2 * position)

        /** Money a club gets for a member's cup or CL place (honour [kind]); 0 for other honours. */
        fun honourMoney(kind: String): Int = when (kind) {
            "CUP" -> 20
            "CUP2" -> 10
            "CL" -> 30
            "CL2" -> 15
            else -> 0
        }

        /** "c01" for club 1. */
        fun idOf(n: Int): String = "c" + n.toString().padStart(2, '0')

        /**
         * A new club league: [clubs] clubs named after [cities] (the app's hometowns), each with
         * [START_BUDGET], and a snake draft of [ranking] (best first, me excluded): the pick
         * order is drawn from [seed] and reverses every round, so squads are balanced. AIs left
         * over are free agents; with too few AIs the last squads are short.
         */
        fun create(ranking: List<String>, clubs: Int, squad: Int, seed: Long, cities: List<String>): ClubBook {
            val book = ClubBook(seed, cities.toList())
            for (n in 1..clubs.coerceIn(0, MAX_CLUBS)) book.entries += book.newEntry(idOf(n))
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
         * Reads league/clubs.txt; lines that cannot be read are skipped, so a cut file keeps
         * the clubs before the cut. Throws when the header or the meta line is missing (the
         * caller then makes a new book with [create]). An AI listed in two clubs stays in the
         * first.
         */
        fun decode(text: String): ClubBook {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == HEADER) { "not a clubs file" }
            val book = lines.firstNotNullOfOrNull { l ->
                runCatching {
                    val f = l.trim().split(' ')
                    if (f[0] != "meta") return@runCatching null
                    val cities = if (f.getOrNull(3).let { it == null || it == "-" }) emptyList() else f[3].split(',').map { unesc(it) }
                    ClubBook(f[1].toLong(), cities).apply {
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
                            val initial = unesc(f[8]).firstOrNull() ?: '?'
                            val emblem = Emblem(Math.floorMod(f[5].toInt(), Emblem.SHAPES), f[6].toLong(16).toInt(), f[7].toLong(16).toInt(), initial)
                            val identity = ClubNames.Identity(unesc(f[2]), unesc(f[3]), unesc(f[4]), emblem)
                            val squad = f[11].split(',').filter { it.isNotEmpty() && it != "-" && seen.add(it) }
                            book.entries += Entry(id, identity, squad.toMutableList(), maxOf(0, f[9].toInt()), maxOf(0, f[10].toInt()))
                        }
                        "move" -> {
                            val kind = ClubMoveKind.of(f[2].single()) ?: return@runCatching
                            book.moves += ClubMove(f[1].toInt(), kind, f[3], f[4].takeIf { it != "-" }, f[5].takeIf { it != "-" }, f[6].toInt())
                        }
                    }
                }
            }
            book.entries.sortBy { it.id }
            book.trim()
            book.reindex()
            return book
        }

        /** Best rated first (an unrated AI last), then by key. */
        internal fun ratingOrder(rating: (String) -> Double): Comparator<String> =
            compareByDescending<String> { score(rating, it) }.thenBy { it }

        private fun score(rating: (String) -> Double, key: String): Double = rating(key).let { if (it.isNaN()) Double.NEGATIVE_INFINITY else it }

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

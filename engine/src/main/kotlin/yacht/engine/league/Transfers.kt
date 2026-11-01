package yacht.engine.league

/**
 * A move between leagues after a season: AI [key] from club [fromClub] (of league [fromLeague] in
 * the closing season) to club [toClub] (of [toLeague]) for [fee], what the buyer paid; or, with
 * [home], a return home (귀국, no fee). [toClub] is "" when its nation has no club yet (it is
 * drafted there), a league "" when the nation played in none.
 */
data class Transfer(val key: String, val fromClub: String, val toClub: String, val fromLeague: String, val toLeague: String, val fee: Int, val home: Boolean)

/** What [Transfers.plan] knows: the closing season, the ranking after it and the clubs as they are. */
class TransferInput(
    /** The closing season: its leagues, tiers and members. */
    val closing: Season,
    /** The league ranking after the closing season: a destination ranks at least [Transfers.GAP] places higher. */
    val ranking: LeagueRanking,
    /** Every AI in the league next season. */
    val members: Collection<String>,
    val rating: (String) -> Double,
    /** Age in the coming season (null: unknown; such a player never moves abroad nor goes home by age). */
    val age: (String) -> Int?,
    /** Each AI's nation (null: none, so never abroad). */
    val nation: (String) -> String?,
    val origin: (String) -> Origin,
    /** Every club now: its nation, members and budget. */
    val clubs: List<Club>,
    /** The most members a club takes (by id). */
    val maxSize: (String) -> Int,
    val config: LeagueConfig,
    /** The club world's money (design ENG 1.5, 3.2); null: planned as before the club world, from the clubs' budgets. */
    val money: TransferMoney? = null,
)

/**
 * What a club world's market gives [Transfers.plan] (design ENG 1.5, 3.2): the plan keeps today's
 * count, candidates, destination leagues, caps and destination club (the most room, then the richer),
 * and reads the money here instead: a buyer's transfer [budget] only filters (a club with less than half
 * the [asked] fee is passed over for the next with room; the fee is the asked one capped at the budget),
 * [allows] refuses a buyer (an embargo or its buys cap, the concentration guard, the wage rules: a free
 * agent costs no fee, so the budget alone does not stop it), [talks] for an abroad move are
 * tried first and count toward the caps, [free] agents are candidates from the club they left (no fee),
 * and every planned move is handed to [moved] at once, so the next budgets and the guard see it.
 */
interface TransferMoney {
    /** Club [club]'s transfer budget now (0 under an embargo or at its buys cap). */
    fun budget(club: String): Int

    /** The fee [buyer] is asked for [key] (0 for a free agent). */
    fun asked(key: String, buyer: String): Int

    /** Whether [buyer] may sign [key] (also for no fee). */
    fun allows(key: String, buyer: String): Boolean

    /** Talks for a move abroad (key, buyer), tried first in this order. */
    val talks: List<Pair<String, String>> get() = emptyList()

    /** Free agents (key → the club it left): candidates as if still there. */
    val free: Map<String, String> get() = emptyMap()

    /**
     * Talks of [key] with [buyer] end: [open] is false when the player has moved or the caps or the
     * buyer's ceiling leave no room. The fee paid when they come true (the plan then moves the player),
     * null when they do not.
     */
    fun talk(key: String, buyer: String, open: Boolean): Int? = null

    /** The planned [transfer] is made now. */
    fun moved(transfer: Transfer)
}

/**
 * Cross-league transfers (해외 이적, design ENG 2), planned once per off-season without any
 * randomness (the order is fixed by rating and key):
 * - **Returns first (귀국).** A member abroad (its nation is not its club's) returns when it is
 *   [RETURN_AGE] or older, or finished the closing season outside its league's 1부: to the club of
 *   its nation with the fewest members (then id), for no fee.
 * - **Transfers.** [count] a season, none with fewer than 2 leagues or no ranking. Candidates are
 *   among the [TOP] best rated of their league's 1부, aged [MIN_AGE] to [MAX_AGE], generated or
 *   evolved, not abroad and not moved already; best rated first. Each goes to the best-ranked
 *   league at least [GAP] places above its own whose 1부 median rating it reaches, to the club of
 *   that league with the most room below its ceiling (then the richer, then id), for
 *   [ClubBook.fee] of its [ClubBook.value]: the buyer pays what it can, and one with less than
 *   half the fee is passed over. A league loses and takes at most `1 + n / 28` players.
 *
 * A player's nation never changes, so the national teams stay as they are. With a club world's
 * [TransferInput.money] the plan is the same but for the money ([TransferMoney]): budgets, fees and
 * the guard come from the market, talks go first, free agents are candidates, and each move is made
 * as it is planned.
 */
object Transfers {
    /** A destination ranks at least this many places above the player's league. */
    const val GAP = 3

    /** Transfers a season at most. */
    const val MAX_TOTAL = 30

    /** A player abroad goes home at this age. */
    const val RETURN_AGE = 33

    const val MIN_AGE = 20
    const val MAX_AGE = 31

    /** Candidates come from this many best rated players of each league's 1부. */
    const val TOP = 3

    /** `1 + n / LEAGUE_SHARE` players a league loses and takes at most. */
    private const val LEAGUE_SHARE = 28

    private val ORIGINS = setOf(Origin.GENERATED, Origin.EVOLVED)

    /** Transfers a season for a league of [members]: `round(transfers × members / 1000)`, at most [MAX_TOTAL]. */
    fun count(config: LeagueConfig, members: Int): Int = Math.round(config.transfers * members / 1000.0).toInt().coerceIn(0, MAX_TOTAL)

    /** Abroad: a member of a known [nation] in a club of another one; one without a nation never is. */
    fun abroad(nation: String?, clubNation: String?): Boolean = nation != null && Nations.of(nation) != null && clubNation != null && clubNation != nation

    /** The league each club nation played in during [closing] (where most of its clubs' members played; ties by league order). */
    fun leagues(closing: Season, clubOf: (String) -> String?, clubNation: (String) -> String?): Map<String, String> {
        val votes = HashMap<String, HashMap<String, Int>>()
        for ((d, keys) in closing.divisions.withIndex()) {
            val lg = closing.leagues.of(d).id
            for (k in keys) {
                val n = clubOf(k)?.let(clubNation) ?: continue
                votes.getOrPut(n) { HashMap() }.merge(lg, 1, Int::plus)
            }
        }
        return votes.mapValues { (_, v) -> v.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { LeagueIds.order(it.key) }).first().key }
    }

    /** The returns, then the transfers, in the order to apply them. */
    fun plan(input: TransferInput): List<Transfer> {
        val cl = input.closing
        val money = input.money
        val byId = input.clubs.associateBy { it.id }
        val clubOf = HashMap<String, String>()
        for (c in input.clubs) for (k in c.squad) clubOf.putIfAbsent(k, c.id)
        // Free agents count as their old club's for who and where, but not for its size.
        val unattached = HashSet<String>()
        if (money != null) for ((k, c) in money.free) if (k !in clubOf && c in byId) {
            clubOf[k] = c
            unattached += k
        }
        val size = HashMap<String, Int>()
        val budget = HashMap<String, Int>()
        for (c in input.clubs) {
            size[c.id] = c.squad.size
            budget[c.id] = c.budget
        }
        val leagueOf = leagues(cl, clubOf::get) { byId[it]?.nation }
        fun nation(k: String) = input.nation(k)?.takeIf { Nations.of(it) != null }
        fun clubNation(k: String) = clubOf[k]?.let { byId[it]?.nation }
        fun isAbroad(k: String) = abroad(nation(k), clubNation(k))
        fun league(k: String): String? = cl.divisionOf(k)?.let { cl.leagues.of(it).id }
        fun moveTo(k: String, from: String, to: String?) {
            if (!unattached.remove(k)) size.merge(from, -1, Int::plus)
            if (to == null) clubOf.remove(k) else {
                size.merge(to, 1, Int::plus)
                clubOf[k] = to
            }
        }
        val members = input.members.toHashSet()
        val out = ArrayList<Transfer>()
        val moved = HashSet<String>()

        for (k in input.members.filter { it in clubOf && isAbroad(it) }.distinct().sorted()) {
            val age = input.age(k)
            val top = cl.divisionOf(k)?.let { cl.leagues.tier(it) == 0 } == true
            if (top && (age == null || age < RETURN_AGE)) continue
            val home = nation(k)!!
            val from = clubOf.getValue(k)
            val to = input.clubs.filter { it.nation == home }.minWithOrNull(compareBy<Club> { size.getValue(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
            val fromLeague = league(k) ?: byId[from]?.nation?.let { leagueOf[it] }
            val t = Transfer(k, from, to?.id.orEmpty(), fromLeague.orEmpty(), leagueOf[home].orEmpty(), 0, true)
            out += t
            moveTo(k, from, to?.id)
            moved += k
            money?.moved(t)
        }

        val n = count(input.config, members.size)
        val cap = 1 + n / LEAGUE_SHARE
        val lost = HashMap<String, Int>()
        val taken = HashMap<String, Int>()
        var made = 0
        val ranked = n > 0 && cl.leagues.list.size >= 2 && input.ranking.rows.isNotEmpty()
        // Talks for a move abroad go first, to the club they were with, and count toward the caps.
        if (money != null) for ((k, buyer) in money.talks) {
            val c = byId[buyer]
            val source = league(k)
            val d = c?.let { leagueOf[it.nation] }
            val open = ranked && c != null && source != null && d != null && k in clubOf && k !in moved && made < n &&
                (lost[source] ?: 0) < cap && (taken[d] ?: 0) < cap && size.getValue(c.id) < input.maxSize(c.id)
            val fee = money.talk(k, buyer, open) ?: continue
            if (!open) continue
            val from = clubOf.getValue(k)
            val t = Transfer(k, from, c!!.id, source!!, d!!, fee, false)
            out += t
            budget[c.id] = budget.getValue(c.id) - fee
            budget.merge(from, fee, Int::plus)
            moveTo(k, from, c.id)
            moved += k
            lost.merge(source, 1, Int::plus)
            taken.merge(d, 1, Int::plus)
            made++
            money.moved(t)
        }
        if (!ranked) return out
        val order = ClubBook.ratingOrder(input.rating)
        val median = HashMap<String, Double>()
        val candidates = ArrayList<String>()
        for (lg in cl.leagues.list) {
            val top = cl.divisions[lg.top].filter { it in members }
            val ratings = top.map(input.rating).filter { !it.isNaN() }.sorted()
            if (ratings.isNotEmpty()) median[lg.id] = (ratings[(ratings.size - 1) / 2] + ratings[ratings.size / 2]) / 2
            candidates += top.sortedWith(order).take(TOP).filter { k ->
                val age = input.age(k)
                age != null && age in MIN_AGE..MAX_AGE && input.origin(k) in ORIGINS && k in clubOf && !isAbroad(k) && k !in moved
            }
        }
        candidates.sortWith(order)
        for (k in candidates) {
            if (made >= n) break
            val source = league(k) ?: continue
            val rank = input.ranking.rank(source)
            if (rank == Int.MAX_VALUE || (lost[source] ?: 0) >= cap) continue
            val rating = input.rating(k)
            val today = if (money == null) ClubBook.fee(ClubBook.value(rating)) else 0
            val destinations = cl.leagues.list.map { it.id }
                .filter { d -> input.ranking.rank(d) <= rank - GAP && (taken[d] ?: 0) < cap && (median[d]?.let { it <= rating } == true) }
                .sortedBy { input.ranking.rank(it) }
            search@ for (d in destinations) {
                val clubs = input.clubs.filter { leagueOf[it.nation] == d && size.getValue(it.id) < input.maxSize(it.id) }
                    .sortedWith(
                        compareByDescending<Club> { input.maxSize(it.id) - size.getValue(it.id) }.thenByDescending { budget.getValue(it.id) }
                            .thenComparing({ it.id }, ClubBook.ID_ORDER),
                    )
                for (c in clubs) {
                    val local = budget.getValue(c.id)
                    val fee = money?.asked(k, c.id) ?: today
                    val have = money?.budget(c.id) ?: local
                    if (2L * have < fee) continue
                    if (money != null && !money.allows(k, c.id)) continue
                    val paid = minOf(fee, have)
                    val from = clubOf.getValue(k)
                    val t = Transfer(k, from, c.id, source, d, paid, false)
                    out += t
                    budget[c.id] = local - paid
                    budget.merge(from, paid, Int::plus)
                    moveTo(k, from, c.id)
                    moved += k
                    lost.merge(source, 1, Int::plus)
                    taken.merge(d, 1, Int::plus)
                    made++
                    money?.moved(t)
                    break@search
                }
            }
        }
        return out
    }
}

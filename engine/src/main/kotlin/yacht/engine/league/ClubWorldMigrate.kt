package yacht.engine.league

import yacht.engine.DiceStream
import java.util.TreeMap

/**
 * How a club world starts (design ENG 8.2): the migration of a national book that has none
 * ([ClubWorld.migrate]), the founding of a new league's ([ClubWorld.found]), and the defaults the
 * off-season gives a club founded later or dissolved ([founded], [dissolved]). Every step is a start
 * function of the feature files (P3, P5, P6) called in ENG 8.2's order; deterministic from the book's
 * seed and the input.
 */
internal object ClubWorldMigrate {
    /** Kept moves' fees at the migration: `amount ← round(amount × 0.6)` (an old 52, a player near 1500, becomes 3.1억; ENG 3.2). */
    const val FEE_FACTOR = 0.6

    private const val CONTRACT_SALT = 0xC0A7L

    fun migrate(book: ClubBook, input: ClubInput, start: ClubStart): Boolean = whole(book) {
        val s = start.season
        book.convertFees { Math.round(it * FEE_FACTOR).toInt() }
        val w = book.startWorld()
        setAnchor(w, input)
        val value = book.valuer(input.rating, input.age, input.potential, input.overall)
        // Last season's places where the book knows them (its `cs` lines: the top three of each league).
        val order = book.places(s - 1).associate { p -> p.league to p.clubs.filter { book.club(it) != null } }.filterValues { it.isNotEmpty() }
        val money = ClubEconomy.migrate(book, s - 1, input.economy, order, value, start.plan)
        begin(book, input, start, money, value)
    }

    fun found(book: ClubBook, input: ClubInput, start: ClubStart): Boolean = whole(book) {
        val w = book.startWorld()
        setAnchor(w, input)
        val value = book.valuer(input.rating, input.age, input.potential, input.overall)
        val money = ClubEconomy.found(book, start.season - 1, value, LeagueEconomy.NEUTRAL, start.plan)
        begin(book, input, start, money, value)
    }

    /**
     * [steps] start [book]'s club world, all or nothing (as the off-season, ENG 1b.10): nothing for a
     * book that has one or a club league's book; on an exception the book goes back to what it was
     * (the fees in their old units, no `cw` line) and false, so a later load starts it whole. True
     * when it made one.
     */
    private fun whole(book: ClubBook, steps: () -> Unit): Boolean {
        if (book.world != null || book.format != ClubBook.AFFILIATION) return false
        val saved = book.encode()
        return try {
            steps()
            book.fault?.invoke(ClubBook.MIGRATION)
            true
        } catch (e: Exception) {
            book.restore(saved)
            false
        }
    }

    /** The steps after the money, shared by [migrate] and [found]. */
    private fun begin(book: ClubBook, input: ClubInput, start: ClubStart, money: StartMoney, value: (String) -> Int) {
        val w = book.world ?: return
        val s = start.season
        val plan = start.plan
        ClubIdentity.start(book)
        ClubHistory.start(
            book, s, start.keys, input.lines, input.honours, input.rating,
            until = { k -> contractEnd(book.seed, k, s) }, wage = { k -> money.wages[k] ?: 1 }, major = input.major,
        )
        val leagueOf = book.clubs.associate { c -> c.id to (c.squad.firstNotNullOfOrNull { plan?.league?.leagueOf(it)?.id } ?: c.nation) }
        CoachMarket.start(book, s, input, start.legends, book.seed) { leagueOf[it] ?: book.nationOf(it).orEmpty() }
        // Rivals from every season the `cs` lines keep (nothing fades meanwhile: each starts in [s]).
        for (x in maxOf(0, s - ClubBook.PLACE_SEASONS) until s) Derbies.recompute(book, x, input.honours, start = s)
        records(book, input, s, value) { leagueOf[it] }
        // The season in play gets the in-season features from now on: a winter week closed already skips its checkpoint.
        if (plan != null && start.week >= ClubWorld.winterWeek(plan)) w.checkpoint = s
        w.marketSeason = s
        w.marketWeek = maxOf(0, start.week)
        book.touch()
    }

    /** A migrated member's contract: to [s] + 1 + `hash % 3` (ENG 8.2), staggered by key. */
    fun contractEnd(seed: Long, key: String, s: Int): Int = s + 1 + Math.floorMod(DiceStream.mix(seed, CONTRACT_SALT, key.hashCode().toLong()), 3L).toInt()

    /**
     * The records the kept moves hold (P7's request: the first window's record fees are real records):
     * the world record fee, each of the 8 best-ranked leagues' (the buyer's league, [leagueOf]) on an
     * `rk` line of the season before [s] (its top 10 by value now, no results yet), and each club's
     * record signing and sale.
     */
    private fun records(book: ClubBook, input: ClubInput, s: Int, value: (String) -> Int, leagueOf: (String) -> String?) {
        val w = book.world ?: return
        val fees = book.history.filter { it.amount > 0 && (it.type == ClubMoveType.TRANSFER || it.type == ClubMoveType.ABROAD) }
        if (fees.isEmpty()) return
        // The biggest fee; level, the newer, then the later in the log.
        val top = fees.withIndex().maxWith(compareBy<IndexedValue<ClubMove>> { it.value.amount }.thenBy { it.value.season }.thenBy { it.index }).value
        w.worldRecord = FeeRecord(top.ai, top.from, top.to, top.amount, top.season)
        val leagues = TreeMap<String, Int>()
        for (m in fees) {
            val to = m.to?.takeIf { book.club(it) != null }
            val from = m.from?.takeIf { book.club(it) != null }
            ClubHistory.fee(book, m.ai, to, from, m.amount, m.season)
            val lg = to?.let(leagueOf)?.takeIf { input.ranking.rank(it) < Market.RECORD_LEAGUES } ?: continue
            if (m.amount > (leagues[lg] ?: 0)) leagues[lg] = m.amount
        }
        if (leagues.isNotEmpty()) {
            val byValue = ClubRanks.of(book, value).value.take(ClubRanks.TOP).map { it.first }
            w.economy.ranks[s - 1] = RankLine(s - 1, byValue, emptyList(), LinkedHashMap(leagues))
        }
    }

    /**
     * The anchor of members' values (design ENG 1.1): the median rating of the league's players
     * (generated, evolved and made ones) among [input]'s members that [count] (at an off-season those
     * who played the closing season: rookies have no rating of their own yet); kept as it is when
     * there are none.
     */
    fun setAnchor(w: ClubWorld, input: ClubInput, count: (String) -> Boolean = { true }) {
        val rs = input.members.filter { count(it) && input.origin(it).let { o -> o == Origin.GENERATED || o == Origin.EVOLVED || o == Origin.CREATED } }
            .map(input.rating).filter { it.isFinite() }.sorted()
        if (rs.isEmpty()) return
        w.anchor = if (rs.size % 2 == 1) rs[rs.size / 2] else (rs[rs.size / 2 - 1] + rs[rs.size / 2]) / 2
    }

    /**
     * Club [id] founded in the off-season after [closing] (ENG 8.2): an id reused from a club dissolved
     * long ago loses every per-id line first (its history and its money); then the defaults of a club
     * founded later (half the nation's median income, an owner and a sponsor, levels 1, 0.6 × the fan
     * base, 3,000 seats), its founding year and nickname, and a coach for the next season.
     */
    fun founded(book: ClubBook, id: String, closing: SeasonPlan, input: ClubInput) {
        forget(book, id)
        ClubEconomy.newClub(book, id, closing.number, input.economy, closing)
        ClubIdentity.newClub(book, id)
        CoachMarket.newClub(book, id, closing.number + 1, input, closing.seed)
    }

    /**
     * Club [id] is dissolved after season [season]: its coach goes to the free pool and its money lines
     * (`co cp cd ac ah`, the result points) go; its state (identity) and history lines stay with its
     * `gone` line (ENG 1b.7).
     */
    fun dissolved(book: ClubBook, id: String, season: Int) {
        CoachMarket.release(book, id, season)
        forgetMoney(book, id)
    }

    /** Every line of club id [id] (a reused id: the old club's history, state and money go, ENG 1b.7). */
    fun forget(book: ClubBook, id: String) {
        val w = book.world ?: return
        ClubHistory.purge(book, id)
        w.clubs.remove(id)
        forgetMoney(book, id)
    }

    /** Club [id]'s money lines (`co cp cd ac ah`, the result points). */
    fun forgetMoney(book: ClubBook, id: String) {
        val e = book.world?.economy ?: return
        val had = id in e.owners || id in e.sponsors || id in e.stadiums || id in e.accounts || id in e.years || id in e.results || id in e.lived
        e.owners.remove(id)
        e.sponsors.remove(id)
        e.stadiums.remove(id)
        e.accounts.remove(id)
        e.years.remove(id)
        e.results.remove(id)
        e.lived -= id
        if (had) book.touch()
    }
}

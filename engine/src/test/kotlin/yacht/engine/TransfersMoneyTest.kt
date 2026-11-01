package yacht.engine

import yacht.engine.league.Club
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubMoveType
import yacht.engine.league.Emblem
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Leagues
import yacht.engine.league.Market
import yacht.engine.league.MarketPhase
import yacht.engine.league.MarketReport
import yacht.engine.league.Nations
import yacht.engine.league.Origin
import yacht.engine.league.Season
import yacht.engine.league.StoryStage
import yacht.engine.league.Transfer
import yacht.engine.league.TransferInput
import yacht.engine.league.TransferMoney
import yacht.engine.league.Transfers
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transfers abroad with the club world's money (design ENG 1.5, 3.2): without money the plan is
 * 3ce4b93's byte for byte; with it the count, candidates, destination leagues, caps and destination
 * club stay, the budget only filters, the fee is the asked fee capped at the budget, the buyer sets
 * the contract.
 */
class TransfersMoneyTest {
    private val s = Mk.SEASON

    /** 3ce4b93's `Transfers.plan`, copied as it was (the golden). */
    private object Before {
        private const val LEAGUE_SHARE = 28
        private val ORIGINS = setOf(Origin.GENERATED, Origin.EVOLVED)

        fun plan(input: TransferInput): List<Transfer> {
            val cl = input.closing
            val byId = input.clubs.associateBy { it.id }
            val clubOf = HashMap<String, String>()
            for (c in input.clubs) for (k in c.squad) clubOf.putIfAbsent(k, c.id)
            val size = HashMap<String, Int>()
            val budget = HashMap<String, Int>()
            for (c in input.clubs) {
                size[c.id] = c.squad.size
                budget[c.id] = c.budget
            }
            val leagueOf = Transfers.leagues(cl, clubOf::get) { byId[it]?.nation }
            fun nation(k: String) = input.nation(k)?.takeIf { Nations.of(it) != null }
            fun clubNation(k: String) = clubOf[k]?.let { byId[it]?.nation }
            fun isAbroad(k: String) = Transfers.abroad(nation(k), clubNation(k))
            fun league(k: String): String? = cl.divisionOf(k)?.let { cl.leagues.of(it).id }
            fun moveTo(k: String, from: String, to: String?) {
                size.merge(from, -1, Int::plus)
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
                if (top && (age == null || age < Transfers.RETURN_AGE)) continue
                val home = nation(k)!!
                val from = clubOf.getValue(k)
                val to = input.clubs.filter { it.nation == home }.minWithOrNull(compareBy<Club> { size.getValue(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
                val fromLeague = league(k) ?: byId[from]?.nation?.let { leagueOf[it] }
                out += Transfer(k, from, to?.id.orEmpty(), fromLeague.orEmpty(), leagueOf[home].orEmpty(), 0, true)
                moveTo(k, from, to?.id)
                moved += k
            }

            val n = Transfers.count(input.config, members.size)
            if (n == 0 || cl.leagues.list.size < 2 || input.ranking.rows.isEmpty()) return out
            val cap = 1 + n / LEAGUE_SHARE
            val order = ClubBook.ratingOrder(input.rating)
            val median = HashMap<String, Double>()
            val candidates = ArrayList<String>()
            for (lg in cl.leagues.list) {
                val top = cl.divisions[lg.top].filter { it in members }
                val ratings = top.map(input.rating).filter { !it.isNaN() }.sorted()
                if (ratings.isNotEmpty()) median[lg.id] = (ratings[(ratings.size - 1) / 2] + ratings[ratings.size / 2]) / 2
                candidates += top.sortedWith(order).take(Transfers.TOP).filter { k ->
                    val age = input.age(k)
                    age != null && age in Transfers.MIN_AGE..Transfers.MAX_AGE && input.origin(k) in ORIGINS && k in clubOf && !isAbroad(k) && k !in moved
                }
            }
            candidates.sortWith(order)
            val lost = HashMap<String, Int>()
            val taken = HashMap<String, Int>()
            var made = 0
            for (k in candidates) {
                if (made >= n) break
                val source = league(k) ?: continue
                val rank = input.ranking.rank(source)
                if (rank == Int.MAX_VALUE || (lost[source] ?: 0) >= cap) continue
                val rating = input.rating(k)
                val fee = ClubBook.fee(ClubBook.value(rating))
                val destinations = cl.leagues.list.map { it.id }
                    .filter { d -> input.ranking.rank(d) <= rank - Transfers.GAP && (taken[d] ?: 0) < cap && (median[d]?.let { it <= rating } == true) }
                    .sortedBy { input.ranking.rank(it) }
                search@ for (d in destinations) {
                    val clubs = input.clubs.filter { leagueOf[it.nation] == d && size.getValue(it.id) < input.maxSize(it.id) }
                        .sortedWith(
                            compareByDescending<Club> { input.maxSize(it.id) - size.getValue(it.id) }.thenByDescending { budget.getValue(it.id) }
                                .thenComparing({ it.id }, ClubBook.ID_ORDER),
                        )
                    for (c in clubs) {
                        val money = budget.getValue(c.id)
                        if (2L * money < fee) continue
                        val paid = minOf(fee, money)
                        val from = clubOf.getValue(k)
                        out += Transfer(k, from, c.id, source, d, paid, false)
                        budget[c.id] = money - paid
                        budget.merge(from, paid, Int::plus)
                        moveTo(k, from, c.id)
                        moved += k
                        lost.merge(source, 1, Int::plus)
                        taken.merge(d, 1, Int::plus)
                        made++
                        break@search
                    }
                }
            }
            return out
        }
    }

    /**
     * A random world for seed [seed]: 2-6 nations with a league of 1-2 divisions each, 1-4 clubs a
     * nation, some foreigners and some players without a club, ratings (some unrated), ages (some
     * unknown), budgets, ceilings, a ranking (sometimes none) and the count's lever.
     */
    private fun world(seed: Long): TransferInput {
        val rng = Random(seed)
        val nations = Nations.ALL.map { it.code }.shuffled(rng).take(2 + rng.nextInt(5))
        val divisions = ArrayList<List<String>>()
        val tiers = ArrayList<Int>()
        val players = LinkedHashMap<String, String>()
        var n = 1
        for (nation in nations) {
            val t = 1 + rng.nextInt(2)
            tiers += t
            repeat(t) { divisions += (0 until 6 + rng.nextInt(20)).map { "g${n++}".also { k -> players[k] = nation } } }
        }
        val season = Season.start(1, seed, divisions, false, Leagues.national(nations, tiers))
        val ratings = players.keys.associateWith { if (rng.nextInt(20) == 0) Double.NaN else 1300.0 + rng.nextInt(400) }
        val ages = players.keys.associateWith { if (rng.nextInt(25) == 0) null else 17 + rng.nextInt(20) }
        val nationOf = players.mapValues { (_, nation) -> if (rng.nextInt(8) == 0) nations[rng.nextInt(nations.size)] else nation }
        val clubs = ArrayList<Club>()
        var id = 1
        val clubsOf = nations.associateWith { (0 until 1 + rng.nextInt(4)).map { ClubBook.idOf(id++) } }
        val squads = HashMap<String, MutableList<String>>()
        for ((k, nation) in players) {
            if (rng.nextInt(20) == 0) continue
            // Most play for a club of their league's nation; a few for another nation's club (abroad).
            val home = if (rng.nextInt(10) == 0) nations[rng.nextInt(nations.size)] else nation
            val pick = clubsOf.getValue(home).let { it[rng.nextInt(it.size)] }
            squads.getOrPut(pick) { ArrayList() } += k
        }
        for ((nation, ids) in clubsOf) for (c in ids) clubs += Club(c, c, "", "", Emblem(0, 0, 0, '?'), squads[c].orEmpty(), rng.nextInt(300), 0, nation)
        val ranking = if (rng.nextInt(6) == 0) LeagueRanking.EMPTY else LeagueRanking(1, nations.shuffled(rng).mapIndexed { i, lg -> LeagueRank(lg, i, i, 0.0, emptyList(), 0.0, 0, false, 0) })
        val ceilings = clubs.associate { it.id to it.squad.size + rng.nextInt(4) }
        val extra = (1..rng.nextInt(1200)).map { "x$it" }
        return TransferInput(
            season, ranking, players.keys + extra, { ratings[it] ?: Double.NaN }, { ages[it] }, { nationOf[it] }, Origin::of, clubs,
            { ceilings[it] ?: 0 }, LeagueConfig.DEFAULT.copy(transfers = rng.nextInt(11)),
        )
    }

    /** Money that is today's: the clubs' budgets kept as the plan pays, today's fee, every buyer allowed. */
    private class Mirror(clubs: List<Club>, private val rating: (String) -> Double) : TransferMoney {
        val budgets = clubs.associate { it.id to it.budget }.toMutableMap()
        override fun budget(club: String): Int = budgets.getValue(club)
        override fun asked(key: String, buyer: String): Int = ClubBook.fee(ClubBook.value(rating(key)))
        override fun allows(key: String, buyer: String): Boolean = true
        override fun moved(transfer: Transfer) {
            if (transfer.home) return
            budgets.merge(transfer.toClub, -transfer.fee, Int::plus)
            budgets.merge(transfer.fromClub, transfer.fee, Int::plus)
        }
    }

    private fun TransferInput.with(money: TransferMoney?) =
        TransferInput(closing, ranking, members, rating, age, nation, origin, clubs, maxSize, config, money)

    @Test
    fun withoutMoneyThePlanIsTheGoldenOne() {
        var abroad = 0
        var home = 0
        for (seed in 1L..400L) {
            val input = world(seed)
            val plan = Transfers.plan(input)
            assertEquals(Before.plan(input), plan, "seed $seed")
            abroad += plan.count { !it.home }
            home += plan.count { it.home }
        }
        assertTrue(abroad > 100 && home > 100, "$abroad abroad, $home home")
    }

    @Test
    fun moneyThatIsTodaysPlansTodaysMoves() {
        for (seed in 1L..400L) {
            val input = world(seed)
            val mirror = Mirror(input.clubs, input.rating)
            assertEquals(Before.plan(input), Transfers.plan(input.with(mirror)), "seed $seed")
        }
    }

    @Test
    fun theBudgetOnlyFiltersAndCapsTheFee() {
        // The first player abroad of each world: its buyer's budget is put below half the asked fee, the
        // others' at three quarters of it. It goes to the next club with room and pays its whole budget.
        val asked = 100
        var moves = 0
        for (seed in 1L..200L) {
            val input = world(seed)
            val base = Transfers.plan(input).firstOrNull { !it.home } ?: continue
            val money = object : TransferMoney {
                override fun budget(club: String): Int = if (club == base.toClub) asked / 2 - 1 else asked * 3 / 4
                override fun asked(key: String, buyer: String): Int = asked
                override fun allows(key: String, buyer: String): Boolean = true
                override fun moved(transfer: Transfer) {}
            }
            val now = Transfers.plan(input.with(money)).firstOrNull { it.key == base.key && !it.home } ?: continue
            assertTrue(now.toClub != base.toClub, "seed $seed: passed over below half the fee")
            assertEquals(asked * 3 / 4, now.fee, "seed $seed: the asked fee capped at the budget")
            // A buyer the market refuses (the guard) is passed over the same way.
            val refusing = object : TransferMoney {
                override fun budget(club: String): Int = 10_000
                override fun asked(key: String, buyer: String): Int = asked
                override fun allows(key: String, buyer: String): Boolean = buyer != base.toClub
                override fun moved(transfer: Transfer) {}
            }
            assertTrue(Transfers.plan(input.with(refusing)).none { it.key == base.key && it.toClub == base.toClub }, "seed $seed")
            moves++
        }
        assertTrue(moves >= 10, "$moves worlds")
    }

    private fun fourLeagues(): Mk.Market = Mk.market(nations = listOf("GB", "ES", "DE", "KR"))

    @Test
    fun aMoveAbroadIsPaidBookedAndSignedAtTheBuyer() {
        val m = fourLeagues()
        val star = Mk.key(13, 1)
        val value = m.value(star)
        val r = m.run()
        val d = r.of(MarketPhase.ABROAD).single()
        assertEquals(Triple(star, "c13", "c02"), Triple(d.key, d.from, d.to))
        // The asked fee (two seasons left, a 재벌 seller) within the buyer's budget.
        assertEquals(Market.price(fourLeagues().book, star, "c13", "c02", s + 1, value).fee, d.fee)
        // Every fee of the window is booked: a club's balance moved by what it received less what it paid.
        val w = m.book.world!!
        for (c in listOf("c02", "c13")) {
            val net = r.deals.filter { it.from == c }.sumOf { it.fee } - r.deals.filter { it.to == c }.sumOf { it.fee }
            assertEquals(300 + net, w.clubs.getValue(c).balance, c)
        }
        assertTrue(w.economy.accounts.getValue("c02").buys >= d.fee)
        val t = m.terms(star)
        assertEquals(s + 1, t.since)
        assertEquals(d.wage, t.wage)
        assertEquals(s + Market.length(25, m.book.seed, star), t.until)
        assertEquals("c02", m.book.clubOf(star))
        // A transfer abroad is notable (a fee) and logged as one.
        assertEquals(ClubMoveType.ABROAD, m.book.history.single { it.ai == star }.type)
        assertTrue(m.book.notable.any { it.key == star && it.type == ClubMoveType.ABROAD.code })
    }

    @Test
    fun talksForAMoveAbroadComeFirstAndCount() {
        val m = fourLeagues()
        val second = Mk.key(13, 2)
        m.story(1, second, "c03")
        val r = m.run()
        val abroad = r.of(MarketPhase.ABROAD)
        assertEquals(listOf(second to "c03"), abroad.map { it.key to it.to }, "the one transfer of the count goes to the talks")
        assertEquals(StoryStage.DONE, m.story(1).stage)
        assertEquals(abroad.single().fee, m.story(1).fee)
        // Talks the count leaves no room for collapse (규정).
        val full = fourLeagues()
        full.story(1, Mk.key(13, 2), "c03")
        full.story(2, Mk.key(13, 3), "c04")
        full.run()
        assertEquals(StoryStage.COLLAPSED to 'R', full.story(2).stage to full.story(2).reason)
    }

    @Test
    fun aFreeAgentStarMovesAbroadForNoFee() {
        val m = fourLeagues()
        val star = Mk.key(13, 1)
        // Underpaid and courted by a club of a better league: it refuses KR's renewal and goes abroad free.
        m.setTerms(star) { it.copy(until = s, wage = 5) }
        m.story(1, star, "c01", stage = StoryStage.RUMOUR)
        val r = m.run()
        val d = r.deals.single { it.key == star }
        assertEquals(MarketPhase.ABROAD to MarketReport.FREE, d.phase to d.type)
        assertEquals("c13", d.from)
        assertEquals(0, d.fee)
        assertEquals("GB", m.book.nationOf(d.to!!))
        assertEquals(d.wage, m.terms(star).wage)
    }

    @Test
    fun aPlayerAbroadComesHomeAt33WithAContractThere() {
        val m = fourLeagues()
        val back = Mk.key(13, 8)
        m.book.place(back, "c04")
        m.nationOf[back] = "KR"
        m.ages[back] = 34
        val r = m.run()
        val d = r.deals.single { it.key == back }
        assertEquals(Triple(ClubMoveType.HOME.code, "c04", "c13"), Triple(d.type, d.from, d.to))
        assertEquals(0, d.fee)
        assertEquals(s + 1, m.terms(back).since)
        assertNull(m.terms(back).loanFrom)
        assertEquals(s + Market.length(34, m.book.seed, back), m.terms(back).until)
    }
}

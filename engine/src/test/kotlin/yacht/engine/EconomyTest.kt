package yacht.engine

import yacht.engine.league.Accounts
import yacht.engine.league.ArcEffects
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubNames
import yacht.engine.league.ClubStanding
import yacht.engine.league.ClubYear
import yacht.engine.league.Coach
import yacht.engine.league.Comp
import yacht.engine.league.Competition
import yacht.engine.league.Fixture
import yacht.engine.league.Gender
import yacht.engine.league.Honour
import yacht.engine.league.International
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.LeagueEvent
import yacht.engine.league.Leagues
import yacht.engine.league.Money
import yacht.engine.league.MoneyItem
import yacht.engine.league.Nations
import yacht.engine.league.Owner
import yacht.engine.league.OwnerType
import yacht.engine.league.Philosophy
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.Sponsor
import yacht.engine.league.Sponsors
import yacht.engine.league.Stadium
import yacht.engine.league.Terms
import yacht.engine.league.Valuation
import yacht.engine.league.WinterMove
import yacht.engine.league.health
import yacht.engine.league.transferBudget
import yacht.engine.league.valuer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-made club worlds for the economy's tests (EconomyTest, TroubleTest, FansStadiumTest,
 * SponsorTest, ClubRankingsTest, FinanceCalibration): a national book of clubs with members, their
 * club world states, a closing season of one division per league and the club tables of the close.
 */
internal object Eco {
    /** A club of the book: its id, nation, members, old budget and club-table titles; its city is the nation's [city]-th club city. */
    data class Spec(val id: String, val nation: String, val members: List<String>, val budget: Int = 100, val honours: Int = 0, val city: Int = 0)

    /** A national book read from club lines (no club world yet). */
    fun book(specs: List<Spec>, seed: Long = 1L): ClubBook = ClubBook.decode(
        buildString {
            append("clubs 2\nmeta $seed 0 - 100\n")
            for (c in specs) {
                val city = ClubNames.cities(Nations.of(c.nation)!!)[c.city]
                append("club ${c.id} ${c.nation} $city ${c.id} $city%20${c.id} 0 ffc62828 ffffffff 가 ${c.budget} ${c.honours} 1 ${c.members.joinToString(",").ifEmpty { "-" }}\n")
            }
        },
    )

    /** [book] with a club world whose clubs all have a state, balance [balance], [fans], mood 50, levels 2 and a stadium of [seats]. */
    fun world(book: ClubBook, balance: Int = 100, fans: Int = 20_000, seats: Int = 10_000): ClubBook {
        val w = book.startWorld()
        for (c in book.clubs) {
            w.state(c.id).apply {
                this.balance = balance; this.fans = fans; mood = 50; training = 2; academy = 2
            }
            w.economy.stadiums[c.id] = Stadium("${c.city} 스타디움", seats, null, null)
            w.economy.owners[c.id] = Owner(OwnerType.FANS, "${c.city} 팬 협동조합", 2, 4, 1, 0)
        }
        return book
    }

    /** Gives member [key] terms with [wage]. */
    fun terms(book: ClubBook, key: String, wage: Int, until: Int = 5) {
        book.world!!.terms[key] = Terms(until, wage, 0, 0, 1, 0, null, null, 0)
    }

    /**
     * Club [id]'s accounts of season [season] as if they were just made: an operating income of
     * [income] (all TV), [wages], the balance as it is now (and the money history's season); the
     * club-life step not yet run.
     */
    fun closed(book: ClubBook, id: String, season: Int, income: Int, wages: Int = 0) {
        val e = book.world!!.economy
        val st = book.world!!.state(id)
        e.accounts[id] = Accounts(season, income, 0, 0, 0, 0, 0, 0, 0, wages, 0, 0, 0, 0, st.balance)
        e.years[id] = listOf(ClubYear(season, st.balance, income, st.fans, 0)) + e.years[id].orEmpty().filter { it.season < season }
        e.lived -= id
    }

    /** The closing season [number] of the leagues (id → its members, one division each), every fixture unplayed. */
    fun plan(number: Int, leagues: List<Pair<String, List<String>>>, seed: Long = 7L, config: LeagueConfig = LeagueConfig()): SeasonPlan {
        val s = Season.start(number, seed, leagues.map { it.second }, true, Leagues.national(leagues.map { it.first }, leagues.map { 1 }))
        return SeasonPlan(s, config)
    }

    /** A club table of [league], best first (points only). */
    fun table(league: String, clubs: List<String>): List<ClubStanding> =
        clubs.mapIndexed { i, c -> ClubStanding(c, league, 100.0 - i, 100.0 - i, 0.0, emptyList(), 0, 0) }

    /** The off-season's input: members of the book, ratings, the config, honours, the economy and the arcs. */
    fun input(
        book: ClubBook,
        economy: LeagueEconomy = LeagueEconomy.NEUTRAL,
        honours: List<Honour> = emptyList(),
        arcs: ArcEffects = ArcEffects.NONE,
        config: LeagueConfig = LeagueConfig(),
        rating: (String) -> Double = { 1500.0 },
    ): ClubInput = ClubInput(book.clubs.flatMap { it.squad }, rating, { k -> book.clubOf(k)?.let { book.nationOf(it) } }, config, honours, economy = economy, arcs = arcs)

    /** A 챔피언스 리그 (or a 대륙 챔피언스) whose entrants earned [points]. */
    class Intl(override val comp: Comp, private val points: Map<String, Int>) : Competition, International {
        override fun next(p: Double, busy: Set<String>): Fixture? = null
        override fun release(f: Fixture) {}
        override fun record(r: JobResult): List<LeagueEvent> = emptyList()
        override val finished: Boolean get() = true
        override val fixturesLeft: Int get() = 0
        override val fixturesTotal: Int get() = 0
        override fun honours(): List<Honour> = emptyList()
        override fun encode(out: StringBuilder) {}
        override fun coefficientPoints(): Map<String, Int> = points
        override fun entrantKeys(): List<String> = points.keys.toList()
    }

    /** The 28-league economy of the worked example: [league] ranked [rank] of 28, every other league filling the other ranks. */
    fun economy(ranks: Map<String, Int>): LeagueEconomy {
        val free = (0 until 28).filter { it !in ranks.values }.iterator()
        val lambda = LinkedHashMap<String, Double>()
        for ((id, r) in ranks) lambda[id] = LeagueEconomy.lambda(r, 28)
        var n = 0
        while (free.hasNext()) lambda["Z${n++}"] = LeagueEconomy.lambda(free.next(), 28)
        return LeagueEconomy(lambda, emptyMap())
    }
}

/** The club world's money (design ENG 1.1-1.3, 1.5, 8.2): the formulas, the season's accounts and the money a club world starts with. */
class EconomyTest {
    /**
     * ENG 1.2's worked example: a 한국 리그 club, λ of the third of 28 leagues, club-table champion of
     * 5 clubs, 25,000 fans, mood 70, 12,000 seats, a 지역 기업 owner, training 3, academy 2, a coach of
     * reputation 60, ω = 1, a member in the CL last four (14 points), a wage bill of 14.0억.
     */
    private fun workedExample(): Pair<ClubBook, Accounts> {
        val kr = (1..22).map { "k$it" }
        val specs = listOf(
            Eco.Spec("c01", "KR", kr.take(6)), Eco.Spec("c02", "KR", kr.subList(6, 10), city = 1), Eco.Spec("c03", "KR", kr.subList(10, 14), city = 2),
            Eco.Spec("c04", "KR", kr.subList(14, 18), city = 3), Eco.Spec("c05", "KR", kr.subList(18, 22), city = 4), Eco.Spec("c06", "VN", listOf("v1", "v2", "v3", "v4")),
        )
        val book = Eco.world(Eco.book(specs))
        val w = book.world!!
        w.state("c01").apply { fans = 25_000; mood = 70; training = 3; academy = 2; balance = 0; coach = "k1" }
        w.economy.stadiums["c01"] = Stadium("서울 스타디움", 12_000, null, null)
        w.economy.owners["c01"] = Owner(OwnerType.LOCAL, "휘담건설", 3, 3, 1, 0)
        val economy = Eco.economy(mapOf("KR" to 2, "VN" to 20))
        val lambda = economy.lambdaOf("KR")
        w.economy.sponsors["c01"] = Sponsor("휘담전자", 0, Sponsors.perSeason(lambda, 25_000, 3, 0), 1, 2, false)
        w.coaches.active["k1"] = Coach("k1", "이 감독", "KR", Gender.MALE, -50, Philosophy.STABLE, 3, 60, "c01", 1, 3, ClubEconomy.coachWage(60, 1.0), null, 1, 0, 0, 0, emptyList(), 0)
        for ((k, wage) in kr.take(6).zip(listOf(30, 30, 20, 20, 20, 20))) Eco.terms(book, k, wage)
        val plan = Eco.plan(1, listOf("KR" to kr, "VN" to listOf("v1", "v2", "v3", "v4")))
        plan.comps += Eco.Intl(Comp.CHAMPIONS, mapOf("k1" to 14))
        val tables = mapOf("KR" to Eco.table("KR", listOf("c01", "c02", "c03", "c04", "c05")), "VN" to Eco.table("VN", listOf("c06")))
        ClubEconomy.accounts(book, plan, Eco.input(book, economy), tables)
        return book to w.economy.accounts.getValue("c01")
    }

    @Test
    fun theWorkedExampleLandsOnItsFigures() {
        val (book, a) = workedExample()
        assertEquals(1.6 - 2.0 / 27, LeagueEconomy.lambda(2, 28))
        // TV 1.5259 × 4.5, prize × 3, intl 14 × 0.6, sponsor 3.745 × 1.24, match-day 21 days × 11,281 × 0.309만, owner 0.05 × 14.0, solidarity −5% of TV.
        assertEquals(listOf(69, 46, 84, 46, 73, 7, -3, 0), listOf(a.tv, a.prize, a.intl, a.sponsor, a.matchday, a.owner, a.solidarity, a.sales))
        assertEquals(listOf(140, 27, 21, 0, 0), listOf(a.wages, a.coach, a.upkeep, a.building, a.buys))
        assertEquals(322, a.income)
        assertEquals(188, a.expenses)
        assertEquals(134, a.net)
        // Within ±0.3억 of the design's decimals: 32.2, 18.85, +13.4.
        assertTrue(abs(Money.eok(a.income) - 32.2) <= 0.3 + 1e-9)
        assertTrue(abs(Money.eok(a.expenses) - 18.85) <= 0.3 + 1e-9)
        assertTrue(abs(Money.eok(a.net) - 13.4) <= 0.3 + 1e-9)
        // The balance moved by the net; the history holds the season with the recurring income (no owner money) and the place.
        assertEquals(134, a.balance)
        assertEquals(134, book.life("c01")!!.balance)
        assertEquals(ClubYear(1, 134, 322 - 7, 25_000, 1), book.life("c01")!!.history.single())
        assertEquals(21, ClubEconomy.matchDays(42))
        assertEquals(11_281, ClubEconomy.demand(25_000, 70, LeagueEconomy.lambda(2, 28)))
    }

    @Test
    fun solidarityGoesFromTheBestLeaguesToTheOthersAndSumsToZero() {
        val (book, _) = workedExample()
        val all = book.world!!.economy.accounts
        assertEquals(0, all.values.sumOf { it.solidarity })
        assertTrue(all.filterKeys { it != "c06" }.values.all { it.solidarity < 0 })
        assertEquals(-all.filterKeys { it != "c06" }.values.sumOf { it.solidarity }, all.getValue("c06").solidarity)
        // The fund itself: shares of the pool one unit apart, the remainder to the first by id; nobody pays without receivers.
        val tv = linkedMapOf("c01" to 69, "c02" to 60, "c03" to 30, "c04" to 30, "c05" to 30)
        val s = ClubEconomy.solidarity(tv, setOf("c01", "c02"), 5)
        assertEquals(mapOf("c01" to -3, "c02" to -3, "c03" to 2, "c04" to 2, "c05" to 2), s)
        assertEquals(0, ClubEconomy.solidarity(tv, setOf("c01", "c02"), 10).values.sum())
        assertTrue(ClubEconomy.solidarity(tv, tv.keys, 5).values.all { it == 0 })
        assertTrue(ClubEconomy.solidarity(tv, setOf("c01"), 0).values.all { it == 0 })
    }

    @Test
    fun placeMoneyIsFlatAndInternationalMoneyIsByPoints() {
        // TV: λ × (3 + 1.5 × (C − p) / (C − 1)); the champion gets 1.5 × the last; one club alone λ × 4.5.
        assertEquals(listOf(45, 41, 38, 34, 30), (1..5).map { ClubEconomy.tv(1.0, it, 5) })
        assertEquals(45, ClubEconomy.tv(1.0, 1, 1))
        assertEquals(38, ClubEconomy.tv(1.0, 1, 5, 0.85))
        assertEquals(72, ClubEconomy.tv(1.6, 1, 16))
        assertEquals(1.5, ClubEconomy.tv(1.0, 1, 16).toDouble() / ClubEconomy.tv(1.0, 16, 16))
        // Prize money: 3, 2, 1.5, 1, 0.6, then 0.4.
        assertEquals(listOf(30, 20, 15, 10, 6, 4, 4, 4), (1..8).map { ClubEconomy.prize(1.0, it) })
        // International: 0.6억 a CL point (× the reform's factor), 0.25억 a CCL point; honours.
        assertEquals(84, ClubEconomy.intl(14, true))
        assertEquals(105, ClubEconomy.intl(14, true, 1.25))
        assertEquals(50, ClubEconomy.intl(20, false))
        assertEquals(0, ClubEconomy.intl(-3, true))
        assertEquals(listOf(40, 20, 32, 5, 10, 80, 40, 0, 0), listOf("CUP", "CUP2", "L0.KR", "LMVP.KR", "SUPER", "CLUBCUP", "CLUBCUP2", "CL", "MVP").map { ClubEconomy.honour(it, 1.6) })
    }

    @Test
    fun honoursAndInternationalPointsArePaidToTheClubs() {
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a", "b")), Eco.Spec("c02", "KR", listOf("c", "d"), city = 1))))
        val plan = Eco.plan(2, listOf("KR" to listOf("a", "b", "c", "d")))
        plan.comps += Eco.Intl(Comp.CHAMPIONS, mapOf("a" to 10, "c" to 3))
        plan.comps += Eco.Intl(Comp.CONT_CHAMPIONS, mapOf("b" to 8))
        val honours = listOf(Honour(2, "CUP", "b"), Honour(2, "CLUBCUP", "c02"), Honour(1, "CUP", "a"), Honour(2, "L0.KR", "c"))
        ClubEconomy.accounts(book, plan, Eco.input(book, honours = honours), mapOf("KR" to Eco.table("KR", listOf("c01", "c02"))))
        val e = book.world!!.economy
        // c01: CL 10 × 0.6 = 6억, CCL 8 × 0.25 = 2억, the cup 4억; c02: CL 1.8억, the 클럽 대항전 8억, a league title 2억 (λ 1). Last season's cup is not this one's.
        assertEquals(60 + 20 + 40, e.accounts.getValue("c01").intl)
        assertEquals(18 + 80 + 20, e.accounts.getValue("c02").intl)
    }

    @Test
    fun ownersPayByType() {
        fun owner(type: OwnerType, ambition: Int = 3) = Owner(type, "", ambition, 3, 1, 0)
        assertEquals(35, ClubEconomy.ownerMoney(owner(OwnerType.TYCOON, 5), 140, 300, 0))
        assertEquals(7, ClubEconomy.ownerMoney(owner(OwnerType.LOCAL), 140, 300, 0))
        assertEquals(56, ClubEconomy.ownerMoney(owner(OwnerType.STATE, 5), 140, 300, 0))
        assertEquals(0, ClubEconomy.ownerMoney(owner(OwnerType.FANS), 140, 300, 0))
        // A fund takes 5% of the income as a dividend while the balance is above half the income.
        assertEquals(-15, ClubEconomy.ownerMoney(owner(OwnerType.FUND), 140, 300, 151))
        assertEquals(0, ClubEconomy.ownerMoney(owner(OwnerType.FUND), 140, 300, 150))
        // A crisis halves it.
        assertEquals(18, ClubEconomy.ownerMoney(owner(OwnerType.TYCOON, 5), 140, 300, 0, 0.5))
    }

    @Test
    fun wagesFollowTheValueTheLeagueAndTheClub() {
        val local = Owner(OwnerType.LOCAL, "", 3, 3, 1, 0)
        // The club factor: 1.0 up to half the income in the bank, 2.0 from twice; a rich 재벌 of ambition ≥ 4 × 1.15.
        assertEquals(1.0, ClubEconomy.clubFactor(0, 100, local))
        assertEquals(1.0, ClubEconomy.clubFactor(50, 100, local))
        assertEquals(1.5, ClubEconomy.clubFactor(125, 100, local), 1e-12)
        assertEquals(2.0, ClubEconomy.clubFactor(200, 100, local), 1e-12)
        assertEquals(2.0, ClubEconomy.clubFactor(900, 100, local), 1e-12)
        assertEquals(1.0, ClubEconomy.clubFactor(900, 0, local))
        assertEquals(2.3, ClubEconomy.clubFactor(200, 100, Owner(OwnerType.TYCOON, "", 4, 1, 1, 0)), 1e-12)
        assertEquals(2.0, ClubEconomy.clubFactor(200, 100, Owner(OwnerType.TYCOON, "", 3, 1, 1, 0)), 1e-12)
        // A 2.5억 player asks 0.35 + 0.325 = 0.675억 → 7 at ω 1 and factor 1; × ω 1.4 = 10; capped at 30% of a small club's income; never below 0.3억 × √λ.
        assertEquals(7, ClubEconomy.wage(25, 1.0, 1.0, 0, 200, local))
        assertEquals(10, ClubEconomy.wage(25, 1.4, 1.0, 0, 200, local))
        assertEquals(15, ClubEconomy.wage(190, 1.0, 1.0, 0, 50, local), "28억's 2.8억 wage capped at 30% of 5억")
        assertEquals(4, ClubEconomy.minWage(1.6))
        assertEquals(3, ClubEconomy.wage(2, 0.5, 1.0, 0, 200, local), "the least wage")
        assertEquals(4, ClubEconomy.wage(190, 1.0, 1.6, 0, 10, local), "the least wage wins over the cap")
        assertEquals(28, ClubEconomy.wage(190, 1.0, 1.0, 0, 0, local), "no income: no cap")
        // Underpaid below 75% of what the value asks now; a renewal in trouble at 80%.
        assertTrue(ClubEconomy.underpaid(7, 80, 1.0))
        assertFalse(ClubEconomy.underpaid(11, 80, 1.0), "0.75 × 1.4억 = 1.05억")
        assertEquals(8, ClubEconomy.renewal(10, true))
        assertEquals(10, ClubEconomy.renewal(10, false))
        assertEquals(27, ClubEconomy.coachWage(60, 1.0))
        assertEquals(3, ClubEconomy.coachWage(0, 1.0))
        assertEquals(13, ClubEconomy.coachWage(25, 1.0))
        assertEquals(43, ClubEconomy.coachWage(100, 1.0))
    }

    @Test
    fun theSpendingCapAndTheTransferBudget() {
        // The mean of the newest 3 recurring incomes, 1.5 × with an investor's injection; none without history.
        assertEquals(200, ClubEconomy.spendCap(listOf(240, 180, 180, 1_000)))
        assertEquals(300, ClubEconomy.spendCap(listOf(240, 180, 180), true))
        assertEquals(Int.MAX_VALUE, ClubEconomy.spendCap(emptyList()))
        // TB = max(0, balance − 0.25 × wages) × (0.4 + 0.1 × ambition) + 0.8 × sales + injection, within cap − paid.
        assertEquals(119, ClubEconomy.budget(200, 120, 3), "170 × 0.7")
        assertEquals(159, ClubEconomy.budget(200, 120, 3, sales = 50))
        assertEquals(60, ClubEconomy.budget(200, 120, 3, sales = 50, cap = 100, paid = 40))
        assertEquals(0, ClubEconomy.budget(-50, 120, 5))
        assertEquals(0, ClubEconomy.budget(200, 120, 3, cap = 100, paid = 150))
        // The book: the wage bill from the terms, the cap from the history; 0 under an embargo.
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a", "b")))), balance = 200)
        Eco.terms(book, "a", 60)
        Eco.terms(book, "b", 60)
        val e = book.world!!.economy
        e.owners["c01"] = Owner(OwnerType.TYCOON, "", 4, 1, 1, 0)
        e.years["c01"] = listOf(ClubYear(3, 200, 120, 20_000, 1), ClubYear(2, 150, 90, 20_000, 2))
        e.accounts["c01"] = Accounts(3, 50, 30, 0, 20, 20, 10, 0, 0, 120, 10, 10, 0, 0, 200)
        assertEquals(120, ClubEconomy.wageBill(book, "c01"))
        assertEquals(130, ClubEconomy.income(book, "c01"))
        assertEquals(105, ClubEconomy.spendCap(book, "c01"))
        assertEquals(105, book.transferBudget("c01"), "170 × 0.8 = 136, capped at the mean of 120 and 90")
        assertEquals(95, book.transferBudget("c01", ClubEconomy.FFP_STRICT), "strict FFP: 136 × 0.7")
        assertEquals(105, book.transferBudget("c01", ClubEconomy.FFP_OFF), "borrowing 65 more, still within the cap")
        e.years["c01"] = listOf(ClubYear(3, 200, 1_000, 20_000, 1))
        assertEquals(136 + 65, book.transferBudget("c01", ClubEconomy.FFP_OFF))
        assertEquals(136, book.transferBudget("c01"))
        book.world!!.state("c01").trouble = 1
        assertEquals(0, book.transferBudget("c01"))
        assertEquals(0, ClubEconomy.transferBudget(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a")))), "c01"), "no club world")
        // FFP's wage share: off none, soft 0.85, strict 0.70.
        assertNull(ClubEconomy.wageShare(0))
        assertEquals(0.85, ClubEconomy.wageShare(1))
        assertEquals(0.70, ClubEconomy.wageShare(2))
        assertEquals(Math.round(0.85 * 130).toInt() - 120, ClubEconomy.wageRoom(book, "c01"))
    }

    @Test
    fun aSaleAddsEightyPercentOfItsFeeToTheBudget() {
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a", "b")))), balance = 200)
        Eco.terms(book, "a", 60)
        Eco.terms(book, "b", 60)
        val e = book.world!!.economy
        e.owners["c01"] = Owner(OwnerType.TYCOON, "", 4, 1, 1, 0)
        Eco.closed(book, "c01", 3, 1_000, 120)
        assertEquals(136, book.transferBudget("c01"), "(200 − 30) × 0.8")
        // The fee is in the balance once it is booked; the budget counts it at 0.8, not at 0.8 + the share.
        ClubEconomy.transfer(book, null, "c01", 50, 3)
        assertEquals(250, book.life("c01")!!.balance)
        assertEquals(136 + 40, ClubEconomy.transferBudget(book, "c01", sales = 50))
        // A fee paid comes out of the balance (and of the spending cap).
        ClubEconomy.transfer(book, "c01", null, 30, 3)
        assertEquals(Math.round((220 - 50 - 30) * 0.8).toInt() + 40, ClubEconomy.transferBudget(book, "c01", sales = 50, paid = 30))
        // An investor's injection, booked in the club-life step, counts once and in full.
        ClubEconomy.charge(book, "c01", MoneyItem.OWNER, 100, 3)
        assertEquals(Math.round((320 - 150 - 30) * 0.8).toInt() + 40 + 100, ClubEconomy.transferBudget(book, "c01", sales = 50, paid = 30, injection = 100))
    }

    @Test
    fun clubValueAndHealthGrades() {
        // Squad + 1.5억 per 1,000 seats + (training + academy) × 2.5억 + 5억 per 10,000 fans + balance.
        assertEquals(500 + 180 + 125 + 125 - 40, ClubEconomy.clubValue(500, 12_000, 3, 2, 25_000, -40))
        // A ≥ income in the bank and wages ≤ 60%; B ≥ 0.3 × income and wages ≤ 70%; C ≥ 0; D ≥ −0.5 × income; E below or 법정관리.
        assertEquals('A', ClubEconomy.health(100, 100, 60, 0))
        assertEquals('B', ClubEconomy.health(100, 100, 61, 0))
        assertEquals('B', ClubEconomy.health(30, 100, 70, 0))
        assertEquals('C', ClubEconomy.health(29, 100, 70, 0))
        assertEquals('C', ClubEconomy.health(100, 100, 71, 0))
        assertEquals('C', ClubEconomy.health(0, 100, 90, 0))
        assertEquals('D', ClubEconomy.health(-1, 100, 50, 0))
        assertEquals('D', ClubEconomy.health(-50, 100, 50, 0))
        assertEquals('E', ClubEconomy.health(-51, 100, 50, 0))
        assertEquals('E', ClubEconomy.health(500, 100, 10, 1))
        assertEquals('C', ClubEconomy.health(10, 0, 10, 0))
        assertEquals('D', ClubEconomy.health(-10, 0, 10, 0))
        assertEquals(listOf("아주 튼튼해요", "튼튼해요", "보통이에요", "불안해요", "위기예요"), ClubEconomy.HEALTH.values.toList())
        // The book's: the last accounts' operating income, the wage bill of the terms.
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a")))), balance = 100)
        Eco.terms(book, "a", 50)
        book.world!!.economy.accounts["c01"] = Accounts(1, 50, 30, 0, 20, 0, 0, 0, 0, 50, 0, 0, 0, 0, 100)
        assertEquals('A', book.health("c01"))
        assertNull(book.health("c09"))
    }

    @Test
    fun theAccountsAddUpAndAreMadeOnce() {
        val (book, a) = workedExample()
        val all = book.world!!.economy.accounts
        for ((id, x) in all) {
            assertEquals(x.tv + x.prize + x.intl + x.sponsor + x.matchday + maxOf(0, x.owner) + x.solidarity + x.sales, x.income, id)
            assertEquals(x.wages + x.coach + x.upkeep + x.building + x.buys + maxOf(0, -x.owner), x.expenses, id)
        }
        // Every other KR club started at 100 and moved by its net.
        for (id in listOf("c02", "c03", "c04", "c05")) assertEquals(100 + all.getValue(id).net, all.getValue(id).balance, id)
        // Asked again: the same report, the book unchanged.
        val text = book.encode()
        val plan = Eco.plan(1, listOf("KR" to (1..22).map { "k$it" }, "VN" to listOf("v1", "v2", "v3", "v4")))
        val again = ClubEconomy.accounts(book, plan, Eco.input(book, Eco.economy(mapOf("KR" to 2, "VN" to 20))), emptyMap())
        assertEquals(text, book.encode())
        assertEquals(ClubEconomy.report(book, 1), again)
        assertEquals("c01" to 322 - 0, again.richest)
        assertEquals(0, again.red)
        val ev = again.events().single()
        assertEquals("club_accounts", ev.kind)
        assertEquals(Comp.CLUB, ev.comp)
        assertEquals(a.income.toString(), ev.args[0])
        // The book reads back with every number.
        assertEquals(text, ClubBook.decode(text).encode())
        // A club dissolved since keeps its state and accounts, but the rebuilt news leaves it out.
        book.world!!.state("c50").balance = -999
        book.world!!.economy.accounts["c50"] = Accounts(1, 900, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -999)
        assertEquals(again, ClubEconomy.report(book, 1))
    }

    @Test
    fun theAccountsAreMadeOnceAlsoAfterAClubIsFounded() {
        val (book, _) = workedExample()
        val grown = ClubBook.decode(book.encode().replace("club c02 ", "club c07 KR 대구 c07 대구%20c07 0 ffc62828 ffffffff 가 100 0 2 -\nclub c02 "))
        assertTrue(ClubEconomy.newClub(grown, "c07", 1, LeagueEconomy.NEUTRAL))
        val text = grown.encode()
        val plan = Eco.plan(1, listOf("KR" to (1..22).map { "k$it" }, "VN" to listOf("v1", "v2", "v3", "v4")))
        val tables = mapOf("KR" to Eco.table("KR", listOf("c01", "c02", "c03", "c04", "c05", "c07")), "VN" to Eco.table("VN", listOf("c06")))
        val again = ClubEconomy.accounts(grown, plan, Eco.input(grown, Eco.economy(mapOf("KR" to 2, "VN" to 20))), tables)
        assertEquals(text, grown.encode(), "no accounts of a season the new club did not play, and ω moves once")
        assertNull(grown.life("c07")!!.last)
        assertEquals(ClubEconomy.report(grown, 1), again)
    }

    @Test
    fun winterFeesShowInTheItemsAndWagesSplit() {
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a", "x")), Eco.Spec("c02", "KR", listOf("b"), city = 1))), balance = 0)
        Eco.terms(book, "a", 20)
        Eco.terms(book, "b", 10)
        Eco.terms(book, "x", 11)
        // x moved c02 → c01 in the winter for 30, paid then (balances moved already).
        book.world!!.winter.moves += WinterMove(1, "x", "c02", "c01", 20, 12, 0)
        book.world!!.state("c01").balance = -30
        book.world!!.state("c02").balance = 30
        val plan = Eco.plan(1, listOf("KR" to listOf("a", "b", "x", "y")))
        ClubEconomy.accounts(book, plan, Eco.input(book), mapOf("KR" to Eco.table("KR", listOf("c01", "c02"))), mapOf("c02" to 30), mapOf("c01" to 30))
        val e = book.world!!.economy
        val c1 = e.accounts.getValue("c01")
        val c2 = e.accounts.getValue("c02")
        assertEquals(30, c1.buys)
        assertEquals(30, c2.sales)
        // Half of x's 11 at each club (6 at the new one, 5 at the old).
        assertEquals(20 + 6, c1.wages)
        assertEquals(10 + 5, c2.wages)
        // The balance moved once: what was there, and the season's other items.
        assertEquals(-30 + c1.net + 30, c1.balance)
        assertEquals(30 + c2.net - 30, c2.balance)
    }

    @Test
    fun theLedgerBooksTheOffSeasonsFeesIntoItsAccounts() {
        val (book, before) = workedExample()
        val e = book.world!!.economy
        ClubEconomy.transfer(book, "c01", "c02", 50, 1)
        val a = e.accounts.getValue("c01")
        assertEquals(50, a.buys)
        assertEquals(before.balance - 50, a.balance)
        assertEquals(a.balance, book.life("c01")!!.balance)
        assertEquals(a.balance, e.years.getValue("c01").first().balance)
        assertEquals(50, e.accounts.getValue("c02").sales)
        // A winter of season 2: the balance only, the last accounts stay season 1's.
        ClubEconomy.charge(book, "c01", MoneyItem.BUILDING, 10, 2)
        assertEquals(a, e.accounts.getValue("c01"))
        assertEquals(a.balance - 10, book.life("c01")!!.balance)
        assertFalse(ClubEconomy.charge(book, "c99", MoneyItem.SALES, 10, 1))
        // Items by label and amount.
        assertEquals(listOf(69, 46, 84, 46, 73, 7, -3, 0, 140, 27, 21, 0, 50), MoneyItem.entries.map { it.of(a) })
        assertEquals("배당", MoneyItem.DIVIDEND)
    }

    @Test
    fun aLeagueThatStartedOverDropsTheAbandonedSeasons() {
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a", "b")))))
        val tables = mapOf("KR" to Eco.table("KR", listOf("c01")))
        for (s in 4..6) ClubEconomy.accounts(book, Eco.plan(s, listOf("KR" to listOf("a", "b"))), Eco.input(book), tables)
        assertEquals(listOf(6, 5, 4), book.life("c01")!!.history.map { it.season })
        ClubEconomy.accounts(book, Eco.plan(5, listOf("KR" to listOf("a", "b"))), Eco.input(book), tables)
        assertEquals(listOf(5, 4), book.life("c01")!!.history.map { it.season })
        assertEquals(5, book.life("c01")!!.last!!.season)
    }

    @Test
    fun omegaPullsEachLeaguesWageBillToTwoThirdsOfItsRevenue() {
        // A league paying far more than 66% of its revenue: ω falls, and the clubs' new contracts with it.
        val members = (1..8).map { "m$it" }
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", members.take(4)), Eco.Spec("c02", "KR", members.drop(4), city = 1))))
        for (k in members) Eco.terms(book, k, 20)
        val w = book.world!!
        val plan = Eco.plan(1, listOf("KR" to members))
        val tables = mapOf("KR" to Eco.table("KR", listOf("c01", "c02")))
        ClubEconomy.accounts(book, plan, Eco.input(book), tables)
        val revenue = w.economy.accounts.values.sumOf { ClubEconomy.recurring(it) }
        val wages = w.economy.accounts.values.sumOf { it.wages }
        assertTrue(wages > 0.66 * revenue)
        val expected = LeagueEconomy.nextOmega(1.0, revenue, wages)
        assertEquals(Math.round(expected * 100).toInt() / 100.0, w.omega("KR"))
        assertTrue(w.omega("KR") < 1.0)
        assertTrue(ClubEconomy.wageFor(book, "c01", 25, "KR", LeagueEconomy.NEUTRAL) <= ClubEconomy.wage(25, 1.0, 1.0, 0, 0, Owner.NONE))
        // Iterated with wages renewed at ω, the share approaches 0.66.
        var omega = 1.0
        var bill = wages
        repeat(12) {
            omega = LeagueEconomy.nextOmega(omega, revenue, bill)
            bill = Math.round(wages * omega).toInt()
        }
        assertEquals(0.66, bill.toDouble() / revenue, 0.02)
    }

    @Test
    fun aMigrationStartsTheMoneyFromRanksNotOldBudgets() {
        val members = (1..24).map { "m$it" }
        val specs = (0 until 6).map { i -> Eco.Spec("c0${i + 1}", "KR", members.subList(4 * i, 4 * i + 4), budget = listOf(900, 300, 250, 120, 80, 40)[i], honours = listOf(5, 2, 0, 0, 1, 0)[i], city = i) } +
            listOf(Eco.Spec("c07", "JP", listOf("j1", "j2"), budget = 50))
        val book = Eco.book(specs)
        val plan = Eco.plan(5, listOf("KR" to members, "JP" to listOf("j1", "j2")))
        val economy = LeagueEconomy(mapOf("KR" to 1.6, "JP" to 1.0), emptyMap())
        val value = { k: String -> if (k.startsWith("m")) 20 + 5 * k.drop(1).toInt() else 20 }
        val order = mapOf("KR" to listOf("c02", "c01", "c03", "c04", "c05", "c06"))
        val money = ClubEconomy.migrate(book, 4, economy, order, value, plan)
        val w = book.world!!
        val e = w.economy
        assertEquals(7, w.clubs.size)
        // Balances by the old budget's rank within the nation: income × (0.2 + 1.3 × pct), never above 1.5 × income.
        for (c in book.clubs) {
            val st = w.clubs.getValue(c.id)
            val inc = money.income.getValue(c.id)
            assertTrue(st.balance <= 1.5 * inc + 1, c.id)
            assertTrue(st.balance >= 0.2 * inc - 1, c.id)
        }
        assertEquals(Math.round(money.income.getValue("c01") * 1.5).toInt(), w.clubs.getValue("c01").balance, "the richest old budget")
        assertEquals(Math.round(money.income.getValue("c06") * 0.2).toInt(), w.clubs.getValue("c06").balance, "the poorest")
        assertEquals(Math.round(money.income.getValue("c07") * 0.85).toInt(), w.clubs.getValue("c07").balance, "a nation's only club: the middle")
        // The estimated income uses last season's places: c02 was the champion.
        assertTrue(money.income.getValue("c02") > money.income.getValue("c03"))
        // Fans from the titles; facilities 1 + the old-budget tercile; the stadium from the fans.
        val base = ClubEconomy.fanBase(1.6, 0)
        assertEquals(ClubEconomy.initialFans(base, 5), w.clubs.getValue("c01").fans)
        assertEquals(listOf(3, 3, 2, 2, 1, 1), (1..6).map { w.clubs.getValue("c0$it").training })
        assertEquals(listOf(3, 3, 2, 2, 1, 1), (1..6).map { w.clubs.getValue("c0$it").academy })
        assertEquals(ClubEconomy.startSeats(w.clubs.getValue("c01").fans), e.stadiums.getValue("c01").capacity)
        assertTrue(e.stadiums.values.all { it.capacity in 3_000..40_000 && it.project == null })
        // Owners and sponsors for every club; deals end in seasons 5..7, from 5.
        assertEquals(7, e.owners.size)
        assertTrue(e.owners.values.all { it.since == 5 && it.ambition in 1..5 && it.patience in 1..5 && it.name.isNotEmpty() })
        assertTrue(e.sponsors.values.all { it.from == 5 && it.until in 5..7 })
        assertEquals(7, e.sponsors.values.map { it.name }.toSet().size)
        assertTrue(e.sponsors.getValue("c01").name.none { it == ' ' }, "a Korean form")
        assertTrue(' ' in e.sponsors.getValue("c07").name, "a foreign form")
        // Wages: ω for each league's bill to be 0.62 × its estimated revenue (within 0.5..2.0); each club at most 0.6 × its income.
        for ((league, ids) in mapOf("KR" to (1..6).map { "c0$it" })) {
            val revenue = ids.sumOf { money.income.getValue(it) }
            val bill = ids.sumOf { id -> book.members(id).sumOf { money.wages.getValue(it) } }
            assertTrue(bill <= 0.62 * revenue * 1.05 + ids.size, "$league $bill of $revenue")
            assertEquals(money.omega.getValue(league), w.omega(league))
        }
        for (c in book.clubs) assertTrue(c.squad.sumOf { money.wages.getValue(it) } <= 0.6 * money.income.getValue(c.id) + 1, c.id)
        assertEquals(members.size + 2, money.wages.size)
        // The history's first season: the balance at the migration and the estimate.
        assertEquals(ClubYear(4, w.clubs.getValue("c02").balance, money.income.getValue("c02"), w.clubs.getValue("c02").fans, 1), e.years.getValue("c02").single())
        assertEquals(0, e.years.getValue("c07").single().place)
        // Deterministic: the same book migrates the same; the encoding reads back.
        val again = Eco.book(specs)
        ClubEconomy.migrate(again, 4, economy, order, value, plan)
        assertEquals(book.encode(), again.encode())
        assertEquals(book.encode(), ClubBook.decode(book.encode()).encode())
        // Terms that exist take the wages.
        val third = Eco.book(specs)
        third.startWorld().terms["m1"] = Terms(6, 0, 0, 7, 1, 0, null, null, 0)
        ClubEconomy.migrate(third, 4, economy, order, value, plan)
        assertEquals(money.wages.getValue("m1"), third.terms("m1")!!.wage)
    }

    @Test
    fun aNewLeagueAndANewClubGetTheirDefaults() {
        val book = Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a", "b"), honours = 3), Eco.Spec("c02", "KR", listOf("c"), city = 1), Eco.Spec("c03", "JP", listOf("d"))))
        ClubEconomy.found(book, 0, { 20 })
        val w = book.world!!
        assertTrue(w.clubs.values.all { it.balance == ClubEconomy.FOUND_BALANCE && it.training == 2 && it.academy == 2 && it.mood == 50 })
        assertEquals(Math.round(ClubEconomy.fanBase(1.0, 0)).toInt(), w.clubs.getValue("c01").fans, "the fan base, no titles at a founding")
        assertTrue(w.economy.sponsors.values.all { it.from == 1 && it.until in 1..3 })
        assertTrue(w.economy.owners.values.all { it.since == 1 })
        // A club founded later: half the nation's median income, levels 1, 0.6 × the fan base, 3,000 seats, a local or fan owner, a sponsor at 0.85.
        w.economy.accounts["c01"] = Accounts(3, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100)
        w.economy.accounts["c02"] = Accounts(3, 60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100)
        val grown = ClubBook.decode(book.encode().replace("club c02 ", "club c04 KR 대구 c04 대구%20c04 0 ffc62828 ffffffff 가 100 0 4 -\nclub c02 "))
        // The id was a club's dissolved long ago, whose state is still there: the new club starts clean of it.
        grown.world!!.state("c04").apply { trouble = 3; admin = 2; deduction = 10; balance = -500 }
        grown.world!!.economy.accounts["c04"] = Accounts(3, 10, 0, 0, 0, 0, 0, 0, 0, 90, 0, 0, 0, 0, -500)
        assertTrue(ClubEconomy.newClub(grown, "c04", 3, LeagueEconomy.NEUTRAL))
        val st = grown.world!!.clubs.getValue("c04")
        assertEquals(listOf(0, 0, 0), listOf(st.trouble, st.admin, st.deduction))
        assertNull(grown.life("c04")!!.last)
        assertEquals(50, st.balance, "half of the median of 100 and 60 (the upper middle)")
        assertEquals(listOf(1, 1, 50), listOf(st.training, st.academy, st.mood))
        assertEquals(Math.round(0.6 * ClubEconomy.fanBase(1.0, ClubEconomy.cityIndex(grown.club("c04")!!))).toInt(), st.fans)
        assertEquals(3_000, grown.life("c04")!!.stadium.capacity)
        assertTrue(grown.life("c04")!!.owner.type in setOf(OwnerType.LOCAL, OwnerType.FANS))
        val deal = grown.life("c04")!!.sponsor!!
        assertEquals(Sponsors.perSeason(1.0, st.fans, 0, deal.sector, 0.85), deal.perSeason)
        assertEquals(4, deal.from)
        assertEquals(ClubYear(3, 50, 100, st.fans, 0), grown.life("c04")!!.history.single(), "the income its health and spending cap read")
        assertEquals('B', ClubEconomy.health(grown, "c04"), "half its income in the bank, no wages yet")
        assertFalse(ClubEconomy.newClub(grown, "c99", 3, LeagueEconomy.NEUTRAL))
        assertNotEquals(grown.life("c01")!!.sponsor!!.name, deal.name)
    }

    @Test
    fun valuesPriceAtTheBooksAnchor() {
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a")))))
        book.world!!.anchor = 1600.0
        val value = book.valuer({ 1600.0 }, { 28 })
        assertEquals(Valuation.value(1600.0, 1600.0, 28, null, null), value("a"))
        assertEquals(25, value("a"))
    }
}

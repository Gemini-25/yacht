package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubNames
import yacht.engine.league.ClubRecord
import yacht.engine.league.ClubRecordKind
import yacht.engine.league.ClubStanding
import yacht.engine.league.FeeRecord
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Market
import yacht.engine.league.MarketDeal
import yacht.engine.league.MarketInput
import yacht.engine.league.MarketPhase
import yacht.engine.league.MarketReport
import yacht.engine.league.MarketStory
import yacht.engine.league.Nations
import yacht.engine.league.NotableMove
import yacht.engine.league.Owner
import yacht.engine.league.OwnerType
import yacht.engine.league.Philosophy
import yacht.engine.league.PlayerTraits
import yacht.engine.league.RankLine
import yacht.engine.league.Rival
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StoryStage
import yacht.engine.league.StyleFamily
import yacht.engine.league.Tenure
import yacht.engine.league.Terms
import yacht.engine.league.Valuation
import yacht.engine.league.valuer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-made markets for the market's tests (MarketTest, ConcentrationTest, ContractsTest,
 * TransfersMoneyTest): national books of clubs `c01`.. with generated members `g<club><nn>`, a club
 * world from [Eco.world] whose owners are 재벌 of ambition 3 (no release clauses in a league of λ 1.5, no
 * fund sales), terms for every member, last season's accounts, the closing season [SEASON] of one
 * division per nation and its club tables.
 */
internal object Mk {
    const val SEASON = 3

    /** Club [club]'s [n]-th member (a generated player's key). */
    fun key(club: Int, n: Int) = "g$club%02d".format(n)

    class Market(
        val book: ClubBook,
        /** Nation → its clubs, best placed first. */
        val nations: Map<String, List<String>>,
        val ratings: HashMap<String, Double>,
    ) {
        val ages = HashMap<String, Int>()
        val traits = HashMap<String, PlayerTraits>()

        /** Players without a club who play on, and their nations. */
        val extra = ArrayList<String>()
        val nationOf = HashMap<String, String>()

        fun members(): List<String> = book.clubs.flatMap { it.squad } + extra

        /** The closing season: one division per nation of the players of its clubs. */
        fun plan(season: Int = SEASON, seed: Long = 7L): SeasonPlan =
            Eco.plan(season, nations.keys.map { n -> n to nations.getValue(n).flatMap { book.members(it) } }, seed)

        fun tables(order: Map<String, List<String>> = nations): Map<String, List<ClubStanding>> = order.mapValues { (lg, cs) -> Eco.table(lg, cs) }

        /** The ranking after the close: the nations' leagues in their order. */
        fun ranking(): LeagueRanking = LeagueRanking(SEASON, nations.keys.mapIndexed { i, id -> LeagueRank(id, i, i, 0.0, emptyList(), 0.0, 0, false, 0) })

        fun input(
            order: Map<String, List<String>> = nations,
            config: LeagueConfig = LeagueConfig(),
            honours: List<Honour> = emptyList(),
            market: Boolean = true,
            lambda: Double = 1.5,
            ranking: LeagueRanking = ranking(),
        ): MarketInput {
            val ci = ClubInput(
                members(), { ratings[it] ?: Double.NaN }, { k -> nationOf[k] ?: book.clubOf(k)?.let { book.nationOf(it) } }, config, honours,
                age = { ages[it] ?: 25 }, ranking = ranking, economy = LeagueEconomy(nations.keys.associateWith { lambda }, emptyMap()), market = market,
            )
            return MarketInput(ci, tables(order), traits = { traits[it] })
        }

        fun run(input: MarketInput = input(), plan: SeasonPlan = plan()): MarketReport = yacht.engine.league.Market.offseason(book, plan, input)

        fun terms(key: String): Terms = book.world!!.terms.getValue(key)

        fun setTerms(key: String, f: (Terms) -> Terms) {
            val w = book.world!!
            w.terms[key] = f(w.terms.getValue(key))
        }

        /** A value at the book's anchor (age 25 unless set). */
        fun value(key: String): Int = book.valuer({ ratings[it] ?: Double.NaN }, { ages[it] ?: 25 })(key)

        /** A story of the closing season: [buyer] in talks for [key] since week 30 (or a rumour). */
        fun story(id: Int, key: String, buyer: String, stage: StoryStage = StoryStage.TALKS, fee: Int = 50, winter: Boolean = false, season: Int = SEASON): MarketStory {
            val st = MarketStory(id, season, stage, key, buyer, book.clubOf(key)!!, fee, 28, if (stage == StoryStage.TALKS) 30 else 0, '-', winter, null)
            book.world!!.market.stories += st
            return st
        }

        fun story(id: Int): MarketStory = book.world!!.market.stories.first { it.id == id }
    }

    /**
     * A market of [clubs] clubs of KR (or of each of [nations], ranked in that order), [size] members
     * each rated [rating] (club, n) (by default `1600 − 30 × (club − 1) − 6 × (n − 1)`: club 1 the best),
     * aged 25, on [wage] to [until]; every club with [balance], last season's [income] and a 재벌 owner
     * of ambition 3.
     */
    fun market(
        clubs: Int = 4, size: Int = 8, balance: Int = 300, income: Int = 200, wage: Int = 10, until: Int = 5,
        nations: List<String> = listOf("KR"), rating: (Int, Int) -> Double = { c, n -> 1600.0 - 30 * (c - 1) - 6 * (n - 1) },
    ): Market {
        val specs = ArrayList<Eco.Spec>()
        val byNation = LinkedHashMap<String, List<String>>()
        val ratings = HashMap<String, Double>()
        var number = 1
        for (nation in nations) {
            val ids = ArrayList<String>()
            val cities = ClubNames.cities(Nations.of(nation)!!).size
            for (i in 0 until clubs) {
                val id = ClubBook.idOf(number)
                val ms = (1..size).map { key(number, it) }
                for ((n, k) in ms.withIndex()) ratings[k] = rating(i + 1, n + 1)
                specs += Eco.Spec(id, nation, ms, city = i % cities)
                ids += id
                number++
            }
            byNation[nation] = ids
        }
        val book = Eco.world(Eco.book(specs), balance = balance)
        val w = book.world!!
        for (c in book.clubs) {
            w.economy.owners[c.id] = Owner(OwnerType.TYCOON, "${c.city} 회장", 3, 3, 1, 0)
            for (k in c.squad) Eco.terms(book, k, wage, until)
            Eco.closed(book, c.id, SEASON, income, c.squad.size * wage)
        }
        return Market(book, byNation, ratings)
    }

    /** Each deal's club-table place check: a round-1 buyer buys from a club placed above it, a free agent, a club in trouble or by a clause. */
    fun incoming(r: MarketReport, club: String): List<MarketDeal> = r.deals.filter { it.to == club && it.type != MarketReport.RELEASE }
}

/** The off-season's transfer window (design ENG 3.2, 1.5, 1b.8): talks, rounds, caps, money, records. */
class MarketTest {
    private val s = Mk.SEASON

    @Test
    fun theWindowRunsOnceAndIsRebuiltFromTheBook() {
        val m = Mk.market()
        m.story(1, Mk.key(2, 3), "c04")
        val r = m.run()
        assertTrue(r.deals.size >= 3, r.deals.toString())
        // Every deal is a move of the season's log, in order; the report rebuilt equals the one made.
        val logged = m.book.history.filter { it.season == s }
        assertEquals(r.deals.map { Triple(it.key, it.from, it.to) }, logged.map { Triple(it.ai, it.from, it.to) })
        assertEquals(r.deals.map { it.fee }, logged.map { it.amount })
        val text = m.book.encode()
        val version = m.book.version
        assertEquals(r, m.run(), "a repeat returns the stored window")
        assertEquals(text, m.book.encode())
        assertEquals(version, m.book.version)
        val back = ClubBook.decode(text)
        assertEquals(text, back.encode())
        assertEquals(r, Market.report(back, s))
        assertEquals(r.events(), Market.report(back, s).events())
        // Its `ms W` line is the guard; an older reader of `ms` lines skips it (its id is no number).
        assertTrue(text.lines().any { it.startsWith("ms W $s 0 ") }, text)
        assertEquals(MarketReport(s + 1), Market.report(m.book, s + 1))
    }

    @Test
    fun nothingHappensWithoutAClubWorldOrWithItOff() {
        val plain = Eco.book(listOf(Eco.Spec("c01", "KR", listOf("g101", "g102")), Eco.Spec("c02", "KR", listOf("g201", "g202"))))
        val before = plain.encode()
        val ci = ClubInput(plain.clubs.flatMap { it.squad }, { 1500.0 }, { "KR" }, LeagueConfig())
        assertEquals(MarketReport(s), Market.offseason(plain, Eco.plan(s, listOf("KR" to plain.clubs.flatMap { it.squad })), MarketInput(ci)))
        assertEquals(before, plain.encode())
        val m = Mk.market()
        val text = m.book.encode()
        assertEquals(MarketReport(s), m.run(m.input(config = LeagueConfig(clubWorld = false))))
        assertEquals(text, m.book.encode())
    }

    @Test
    fun talksComeTrueAndHijackersTakeThePlayerFirst() {
        val m = Mk.market()
        val star = Mk.key(2, 3)
        m.story(1, star, "c04")
        m.story(2, star, "c03")
        m.story(3, Mk.key(2, 4), "c03", stage = StoryStage.RUMOUR)
        m.story(4, Mk.key(1, 5), "c02", stage = StoryStage.DONE, season = s - 1)
        val r = m.run()
        val done = r.of(MarketPhase.TALKS).single()
        assertEquals(Triple(star, "c02", "c04"), Triple(done.key, done.from, done.to))
        assertEquals(Market.price(Mk.market().book, star, "c02", "c04", s + 1, m.value(star)).fee, done.fee)
        assertEquals("c04", m.book.clubOf(star))
        assertEquals(StoryStage.DONE, m.story(1).stage)
        assertEquals(done.fee, m.story(1).fee, "a story done carries the fee paid")
        assertEquals(StoryStage.HIJACKED to "c04", m.story(2).stage to m.story(2).by)
        // Rumours never escalated and older outcomes leave; the window's outcomes stay.
        assertEquals(listOf(1, 2), m.book.world!!.market.stories.map { it.id })
        assertEquals(listOf(star), r.talked)
        val collapsed = r.events().single { it.kind == "deal_collapsed" }
        assertEquals(listOf(star, "c03", "c02", "c04"), collapsed.keys)
        assertEquals(listOf("H"), collapsed.args)
    }

    @Test
    fun talksFailForTheirReasons() {
        fun reason(setup: (Mk.Market) -> Unit): Char {
            val m = Mk.market()
            m.story(1, Mk.key(2, 3), "c04")
            setup(m)
            m.run()
            val st = m.story(1)
            assertEquals(StoryStage.COLLAPSED, st.stage)
            return st.reason
        }
        // 가격: the buyer's budget is short of the asked fee.
        assertEquals('P', reason { it.book.world!!.clubs.getValue("c04").balance = 40 })
        // 재정: an embargo (재정 위기), or a wage bill FFP does not allow.
        assertEquals('F', reason { it.book.world!!.clubs.getValue("c04").trouble = 1 })
        assertEquals('F', reason { m -> for (k in m.book.members("c04")) m.setTerms(k) { t -> t.copy(wage = 21) } })
        // 잔류: the player earns more than the buyer offers.
        assertEquals('S', reason { m -> m.setTerms(Mk.key(2, 3)) { t -> t.copy(wage = 60) } })
        // 감독 교체: the buyer's new coach likes the player less than the one who started the talks.
        assertEquals('M', reason { m ->
            m.traits[Mk.key(2, 3)] = PlayerTraits(StyleFamily.YACHT, 40, 0, 50, false, null, null)
            Coaching.coach(m.book, "k1", "c04", from = s + 1, philosophy = Philosophy.BONUS)
            Coaching.coach(m.book, "k2", null, philosophy = Philosophy.ATTACK)
            val w = m.book.world!!
            w.coaches.active["k2"] = w.coaches.active.getValue("k2").copy(tenures = listOf(Tenure("c04", 1, s, 2, 0, 'S')))
        })
        // 규정: the guard (c01 already holds 8 of KR's 16 best; another one would make 9 of a cap of 6).
        val m = Mk.market()
        m.story(1, Mk.key(2, 1), "c01")
        m.run()
        assertEquals(StoryStage.COLLAPSED to 'R', m.story(1).stage to m.story(1).reason)
        // The market's stories are a test knob: off, talks are not decided and simply go.
        val off = Mk.market()
        off.story(1, Mk.key(2, 3), "c04")
        val r = off.run(off.input(market = false))
        assertTrue(r.of(MarketPhase.TALKS).isEmpty() && off.book.world!!.market.stories.isEmpty())
    }

    @Test
    fun roundOneGoesWorstPlacedFirstFromClubsAbove() {
        val m = Mk.market()
        val r = m.run()
        val parity = r.of(MarketPhase.PARITY)
        assertEquals(listOf("c04", "c03"), parity.map { it.to })
        assertEquals(listOf(Mk.key(1, 1), Mk.key(1, 2)), parity.map { it.key })
        val place = m.nations.getValue("KR").withIndex().associate { it.value to it.index + 1 }
        for (d in parity) assertTrue(place.getValue(d.from!!) < place.getValue(d.to!!), d.toString())
        // c02 found nobody: its only club above (c01) is down to its size floor after two sales.
        assertTrue(parity.none { it.to == "c02" || it.to == "c01" })
        // The fee is the asked fee; the wage the buyer's offer, the new contract from next season.
        for (d in parity) {
            assertEquals(Market.price(Mk.market().book, d.key, d.from!!, d.to!!, s + 1, m.value(d.key)).fee, d.fee)
            assertEquals(ClubEconomy.wageFor(Mk.market().book, d.to!!, m.value(d.key), "KR", LeagueEconomy(mapOf("KR" to 1.5), emptyMap())), d.wage)
            val t = m.terms(d.key)
            assertEquals(s + 1, t.since)
            assertEquals(d.wage, t.wage)
            assertEquals(s + Market.length(25, m.book.seed, d.key), t.until)
        }
        // Each fee moved the money: the sellers got it, the buyers paid it, both in the season's accounts.
        val w = m.book.world!!
        assertEquals(300 - parity[0].fee, w.clubs.getValue("c04").balance)
        assertEquals(parity.sumOf { it.fee }, w.economy.accounts.getValue("c01").sales)
    }

    @Test
    fun roundTwoGoesRichestFirstFromAnyClub() {
        // Club 4 has the best players; c01 tops the table with the most money and buys from below.
        val m = Mk.market(rating = { c, n -> 1500.0 + 30 * (c - 1) - 6 * (n - 1) })
        m.book.world!!.clubs.getValue("c01").balance = 800
        val r = m.run()
        assertTrue(r.of(MarketPhase.PARITY).isEmpty(), r.deals.toString())
        val money = r.of(MarketPhase.MONEY)
        assertEquals(Triple(Mk.key(4, 1), "c04", "c01"), money.first().let { Triple(it.key, it.from, it.to) })
    }

    @Test
    fun capsHoldPerNationAndPerClub() {
        for (n in listOf(2, 3, 4, 5, 8, 16)) assertEquals(n / 2 + (n + 3) / 4, Market.dealCap(n))
        assertEquals(listOf(2, 2, 3, 6, 12), listOf(2, 3, 4, 8, 16).map(Market::dealCap))
        val m = Mk.market(clubs = 8, balance = 2000, income = 1000)
        val r = m.run()
        val domestic = r.deals.filter { it.type != MarketReport.RELEASE && it.type != MarketReport.LOAN && it.type != MarketReport.LOAN_BACK && it.type != MarketReport.FREE }
        assertEquals(Market.dealCap(8), domestic.size, r.deals.toString())
        for (c in m.nations.getValue("KR")) assertTrue(Mk.incoming(r, c).size <= Market.MAX_BUYS, c)
    }

    @Test
    fun theSpendingCapBoundsAWindowsFees() {
        val m = Mk.market()
        // c04 is rich but its recurring income is 4억: no more than that in fees this window (FFP off: no wage share).
        m.book.world!!.clubs.getValue("c04").balance = 3000
        Eco.closed(m.book, "c04", s, 40, 80)
        val r = m.run(m.input(config = LeagueConfig(ffp = 0)))
        val paid = Mk.incoming(r, "c04").sumOf { it.fee }
        assertTrue(paid in 1..40, r.deals.toString())
        // Its wage offers are capped at 30% of that income.
        assertTrue(Mk.incoming(r, "c04").all { it.wage <= 12 })
    }

    @Test
    fun ffpLevelsChangeWhatAClubCanAfford() {
        fun buys(ffp: Int, setup: (Mk.Market) -> Unit = {}): List<MarketDeal> {
            val m = Mk.market()
            setup(m)
            return Mk.incoming(m.run(m.input(config = LeagueConfig(ffp = ffp))), "c04")
        }
        // c04 pays its own members 1.6억 each (a bill of 12.8억 on an income of 20억). Soft: the bill after a
        // signing ≤ 0.85 × income (128 + 20 ≤ 170); strict: ≤ 0.70 × income (140) refuses.
        val dear = { m: Mk.Market -> for (k in m.book.members("c04")) m.setTerms(k) { it.copy(wage = 16) } }
        assertTrue(buys(1, dear).isNotEmpty())
        assertTrue(buys(2, dear).isEmpty())
        // In the red there is no budget at all; strict FFP bars the club (an embargo) even for a free signing.
        assertTrue(buys(1) { it.book.world!!.clubs.getValue("c04").balance = -1 }.isEmpty())
        assertTrue(ClubEconomy.embargo(Mk.market().also { it.book.world!!.clubs.getValue("c04").balance = -1 }.book, "c04", 2))
        // Off, a 재벌 of ambition 4 borrows half its income beyond a budget of 1.6억.
        val rich = { m: Mk.Market ->
            m.book.world!!.clubs.getValue("c04").balance = 40
            m.book.world!!.economy.owners["c04"] = Owner(OwnerType.TYCOON, "회장", 4, 2, 1, 0)
        }
        assertTrue(buys(1, rich).isEmpty())
        assertTrue(buys(0, rich).sumOf { it.fee } > 16, "borrowing buys a player")
    }

    @Test
    fun aPlayerSaysYesForAGoodWageOrAClubNotFarBelow() {
        assertTrue(Market.accepts(20, 20, 3, 2))
        assertTrue(Market.accepts(20, 20, 3, 2))
        assertFalse(Market.accepts(19, 20, 1, 4), "never below its wage")
        assertFalse(Market.accepts(25, 20, 4, 2), "two places below needs 1.3 ×")
        assertTrue(Market.accepts(26, 20, 4, 2))
        assertTrue(Market.accepts(22, 20, 4, 1, clause = true))
        assertFalse(Market.accepts(21, 20, 1, 4, clause = true), "a clause needs 1.1 ×")
        assertTrue(Market.accepts(20, 20, Market.UNPLACED, Market.UNPLACED))
        // In a window: c04's best target earns more than c04 offers and refuses; c04 buys the next best.
        val m = Mk.market()
        m.setTerms(Mk.key(1, 1)) { it.copy(wage = 25) }
        val first = Mk.incoming(m.run(), "c04").first()
        assertEquals(Mk.key(1, 2), first.key)
    }

    @Test
    fun aRivalPaysThePremiumAndTheMoveIsFlagged() {
        val m = Mk.market()
        val h = m.book.world!!.history
        h.rivals["c01"] = listOf(Rival("c04", 'T', 1))
        h.rivals["c04"] = listOf(Rival("c01", 'T', 1))
        val r = m.run()
        val d = Mk.incoming(r, "c04").first()
        assertEquals("c01", d.from)
        assertEquals(Math.round(m.value(d.key) * Valuation.RIVAL).toInt(), d.fee)
        assertTrue('V' in d.flags)
        assertEquals(listOf(d.key, "c01", "c04"), r.events().single { it.kind == "rival_move" }.keys)
        assertEquals(-15, r.moodEvents("c01") { false } - ClubEconomy.moodEvents())
        assertTrue(m.book.notable.single { it.key == d.key }.flags.contains('V'))
    }

    @Test
    fun aReleaseClauseCannotBeRefusedAndCapsTheFee() {
        // Two clubs: c01 (placed first) can only buy from below by paying a clause.
        val m = Mk.market(clubs = 2)
        val target = Mk.key(2, 1)
        m.setTerms(target) { it.copy(clause = 70) }
        val r = m.run()
        val k = r.deals.single { it.type == MarketReport.CLAUSE }
        assertEquals(Triple(target, "c02", "c01"), Triple(k.key, k.from, k.to))
        assertEquals(70, k.fee)
        assertTrue('K' in k.flags)
        assertEquals(listOf(target, "c02", "c01"), r.events().single { it.kind == "release_clause" }.keys)
        // A clause below the asked fee is the price.
        val c = Mk.market()
        c.setTerms(Mk.key(1, 1)) { it.copy(clause = 30) }
        assertEquals(Market.Price(30, true), Market.price(c.book, Mk.key(1, 1), "c01", "c04", s + 1, c.value(Mk.key(1, 1))))
        assertEquals(Market.Price(c.value(Mk.key(1, 2)), false), Market.price(c.book, Mk.key(1, 2), "c01", "c04", s + 1, c.value(Mk.key(1, 2))))
    }

    @Test
    fun clausesAreDrawnAtSmallOrFanOwnedClubs() {
        val m = Mk.market()
        val w = m.book.world!!
        // A 재벌 club of a λ 1.5 league gives none; λ below 1.2 or a 팬 소유 / 지역 기업 owner: 30% (60% from 20억).
        assertEquals(0, (1..200).sumOf { Market.clause(m.book, "c01", "g$it", 100, 1.5, s) })
        val drawn = (1..2000).map { Market.clause(m.book, "c01", "g$it", 100, 1.0, s) }
        assertEquals(0.3, drawn.count { it > 0 } / 2000.0, 0.04)
        assertTrue(drawn.filter { it > 0 }.all { it in 200..300 })
        w.economy.owners["c01"] = Owner(OwnerType.FANS, "팬", 2, 4, 1, 0)
        assertEquals(0.6, (1..2000).count { Market.clause(m.book, "c01", "g$it", 250, 1.5, s) > 0 } / 2000.0, 0.04)
        assertEquals(Market.clause(m.book, "c01", "g7", 250, 1.5, s), Market.clause(m.book, "c01", "g7", 250, 1.5, s))
    }

    @Test
    fun aClubInTroubleSellsItsMostValuableToTheRichest() {
        // c02 is in 재정 위기; only c03 has money (the others' budgets are 0).
        val m = Mk.market()
        val w = m.book.world!!
        for (c in m.book.clubs) w.clubs.getValue(c.id).balance = 0
        w.clubs.getValue("c02").apply { trouble = 1; balance = -150 }
        w.clubs.getValue("c03").balance = 900
        Eco.closed(m.book, "c03", s, 600, 80)
        val r = m.run()
        val forced = r.of(MarketPhase.FORCED)
        assertTrue(forced.isNotEmpty() && forced.size <= ClubEconomy.FORCED_MAX, r.deals.toString())
        for (d in forced) {
            assertEquals(MarketReport.FORCED, d.type)
            assertEquals("F", d.flags)
            assertEquals("c02", d.from)
            assertEquals(Math.round(ClubEconomy.FORCED_PRICE * m.value(d.key)).toInt(), d.fee)
            // The most valuable go: none of those it kept is worth more.
            assertTrue(m.book.members("c02").all { m.value(it) <= m.value(d.key) })
        }
        // The richest club that can pay and take the player buys it.
        assertEquals("c03", forced.first().to)
        assertEquals(listOf(forced[0].key, "c02", "c03"), r.events().single { it.kind == "forced_sale" }.keys)
        // c02 itself bought nothing (an embargo); in round 1 its members were open to every club.
        assertTrue(Mk.incoming(r, "c02").isEmpty())
    }

    @Test
    fun freeAgentsSignWhereTheWageIsBestOrGoToTheDraft() {
        val m = Mk.market()
        // c03's two weakest are out of contract and outside its counted 6: no renewal.
        val a = Mk.key(3, 7)
        val b = Mk.key(3, 8)
        for (k in listOf(a, b)) m.setTerms(k) { it.copy(until = s) }
        val r = m.run()
        for (k in listOf(a, b)) {
            val d = r.deals.filter { it.key == k }
            assertEquals(1, d.size, d.toString())
            assertEquals("c03", d.single().from)
            assertTrue(d.single().to != "c03")
            if (d.single().type == MarketReport.FREE) {
                assertEquals(0, d.single().fee)
                assertEquals(m.terms(k).wage, d.single().wage)
                assertEquals(s + 1, m.terms(k).since)
            } else {
                assertEquals(MarketReport.RELEASE to MarketPhase.RELEASED, d.single().type to d.single().phase)
                assertNull(m.book.clubOf(k))
            }
        }
        val signed = r.deals.filter { it.type == MarketReport.FREE }
        assertTrue(signed.isNotEmpty())
        val told = r.events().single { it.kind == "free_agent" }
        assertEquals(signed.maxBy { it.wage }.key, told.keys.first())
    }

    @Test
    fun loansGoOutAndComeBackOrTheLoanClubKeepsThePlayer() {
        // Nobody has money (no buys); c01 and c02, the most valuable, have two members aged 21 or younger outside their counted 6.
        val m = Mk.market(clubs = 8, balance = 0)
        for ((k, r) in listOf(Mk.key(1, 7) to 1400.0, Mk.key(1, 8) to 1395.0, Mk.key(2, 7) to 1390.0, Mk.key(2, 8) to 1385.0)) {
            m.ages[k] = 20
            m.ratings[k] = r
        }
        val r = m.run()
        val loans = r.deals.filter { it.type == MarketReport.LOAN }
        assertEquals(Market.loanCap(8), loans.size)
        assertEquals(listOf(Mk.key(1, 7) to "c01", Mk.key(2, 7) to "c02"), loans.map { it.key to it.from })
        // To the lowest-placed clubs with room, one each.
        assertEquals(listOf("c08", "c07"), loans.map { it.to })
        for (d in loans) {
            assertEquals(d.from, m.terms(d.key).loanFrom)
            assertEquals(d.to, m.book.clubOf(d.key))
            assertEquals(MarketPhase.LOANS, d.phase)
        }
        assertEquals(loans.map { it.key }, r.events().single { it.kind == "loan_digest" }.keys)
        // The next off-season: the loan club keeps a member now in its counted 6 when it can pay; else it goes home.
        val kept = loans[0]
        val home = loans[1]
        m.setTerms(kept.key) { it.copy(wage = 5) }
        m.book.world!!.clubs.getValue(kept.to!!).balance = 300
        for (c in m.book.clubs) Eco.closed(m.book, c.id, s + 1, 200, c.squad.size * 10)
        val next = m.run(plan = m.plan(s + 1))
        val back = next.of(MarketPhase.BACK)
        val buy = back.single { it.key == kept.key }
        assertEquals(Triple('T', kept.from, kept.to), Triple(buy.type, buy.from, buy.to))
        // The asked fee of a contract in its last season (to season 5, the first of the new one).
        assertEquals(Math.round(m.value(kept.key) * Valuation.yearsFactor(1)).toInt(), buy.fee)
        assertNull(m.terms(kept.key).loanFrom)
        assertEquals(s + 2, m.terms(kept.key).since)
        val ret = back.single { it.key == home.key }
        assertEquals(Triple(MarketReport.LOAN_BACK, home.to, home.from), Triple(ret.type, ret.from, ret.to))
        assertEquals(home.from, m.book.clubOf(home.key))
        assertNull(m.terms(home.key).loanFrom)
    }

    @Test
    fun deadlineDayBuysOnceMoreForAClubThatLostTwoCountedMembers() {
        // A fair nation of 8 clubs (each holds 2 of the 16 best); only c01-c03 have money. Talks take
        // c01's two best (to c02 and c03) and round 1 more; c01 buys in round 2 and once more on deadline day.
        fun fair(c: Int, n: Int) = 1600.0 - 3 * (8 * (n - 1) + (c - 1))
        val setup = {
            val m = Mk.market(clubs = 8, balance = 0, income = 400, rating = ::fair)
            for (c in listOf("c01", "c02", "c03")) m.book.world!!.clubs.getValue(c).balance = 600
            m.story(1, Mk.key(1, 1), "c02")
            m.story(2, Mk.key(1, 2), "c03")
            m
        }
        val m = setup()
        val before = setup()
        val r = m.run()
        val late = r.of(MarketPhase.DEADLINE)
        assertEquals(listOf("c01"), late.map { it.to }, r.deals.toString())
        for (d in late) {
            assertTrue('D' in d.flags)
            val counted = before.book.members(d.to!!).sortedByDescending { m.ratings.getValue(it) }.take(6)
            assertTrue(counted.count { m.book.clubOf(it) != d.to } >= Market.DEADLINE_LOST, d.toString())
            assertEquals(Math.round(Market.price(before.book, d.key, d.from!!, d.to!!, s + 1, m.value(d.key)).fee * Market.DEADLINE_PREMIUM).toInt(), d.fee)
        }
        assertEquals(Market.MAX_BUYS, Mk.incoming(r, "c01").size)
        val event = r.events().single { it.kind == "deadline_day" }
        assertEquals(late.size.toString(), event.args.single())
        assertTrue(m.book.notable.any { it.key == late.single().key && 'D' in it.flags })
    }

    @Test
    fun recordFeesAreFlaggedToldAndKept() {
        val m = Mk.market()
        val w = m.book.world!!
        w.worldRecord = FeeRecord("old", null, null, 30, 1)
        w.economy.ranks[s - 1] = RankLine(s - 1, emptyList(), emptyList(), mapOf("KR" to 55))
        w.history.records["c04"] = listOf(ClubRecord(ClubRecordKind.SIGNING, "x", 40, 1))
        val r = m.run()
        val first = r.deals.first { it.fee > 0 }
        assertEquals("WLC", first.flags.filter { it in "WLC" })
        assertEquals(first.fee, w.worldRecord!!.fee)
        assertEquals(first.key, w.worldRecord!!.key)
        assertEquals(mapOf("KR" to r.deals.maxOf { it.fee }), r.leagueRecords)
        val told = r.events().single { it.kind == "record_fee" }
        assertEquals(listOf(first.key, first.from, first.to), told.keys)
        assertEquals(listOf(first.fee.toString(), "30", "W"), told.args)
        // The record is the buyer's 최고 영입액 now, and a record signing lifts its fans' mood.
        assertEquals(first.fee, w.history.records.getValue("c04").first { it.kind == ClubRecordKind.SIGNING }.value)
        assertEquals(6, r.moodEvents("c04") { false })
        // Without any record yet, the first fee sets one silently.
        val fresh = Mk.market()
        val q = fresh.run()
        assertTrue(q.deals.none { it.flags.any { f -> f in "WL" } })
        assertNotNull(fresh.book.worldRecord)
        assertNull(q.events().firstOrNull { it.kind == "record_fee" })
    }

    @Test
    fun notableTransfersKeepTheFlaggedThenTheBiggestFees() {
        // Two nations of 8 clubs: up to 6 domestic deals each.
        val setup = {
            val m = Mk.market(clubs = 8, balance = 2000, income = 1000, nations = listOf("KR", "JP"))
            val h = m.book.world!!.history
            h.rivals["c01"] = listOf(Rival("c08", 'T', 1))
            h.rivals["c08"] = listOf(Rival("c01", 'T', 1))
            m
        }
        val m = setup()
        val w = m.book.world!!
        w.market.notable += NotableMove(s - Market.TX_SEASONS, 'T', "old", "c01", "c02", 10, "")
        w.market.notable += NotableMove(s - Market.TX_SEASONS + 1, 'T', "kept", "c01", "c02", 10, "")
        val r = m.run()
        val tx = m.book.notable.filter { it.season == s }
        val fees = r.deals.filter { it.to != null && it.fee > 0 }
        assertTrue(fees.size > Market.TX_SMALL, fees.toString())
        assertEquals(Market.TX_SMALL, tx.size, "below 500 members: 6 a season")
        // Every flagged move is kept, then the biggest fees, in the window's order.
        val flagged = r.deals.filter { it.flags.any { f -> f in Market.TX_FLAGS } }
        assertTrue(flagged.isNotEmpty())
        for (d in flagged) assertTrue(tx.any { it.key == d.key }, d.toString())
        val chosen = r.deals.filter { d -> tx.any { it.key == d.key } }
        assertEquals(chosen.map { it.key }, tx.map { it.key })
        val left = fees.filter { it !in chosen }
        assertTrue(left.all { it.flags.none { f -> f in Market.TX_FLAGS } && it.fee <= chosen.filter { c -> c.flags.isEmpty() }.minOf { c -> c.fee } })
        assertEquals(listOf("kept"), m.book.notable.filter { it.season < s }.map { it.key })
        // From 500 members: 20 a season (the extra players play for a nation without clubs).
        val big = setup()
        for (i in 1..500) {
            big.extra += "x$i"
            big.nationOf["x$i"] = "CN"
        }
        val q = big.run()
        assertEquals(minOf(Market.TX_MAX, q.deals.count { it.to != null && (it.fee > 0 || it.flags.any { f -> f in Market.TX_FLAGS }) }), big.book.notable.count { it.season == s })
        assertTrue(big.book.notable.count { it.season == s } > Market.TX_SMALL)
    }

    @Test
    fun theCoachsTasteChoosesAmongTargets() {
        val yacht = PlayerTraits(StyleFamily.YACHT, 40, 0, 60, false, 70, 60)
        assertEquals(0.25 * 40 / 50 + 0.15, Market.fit(Philosophy.ATTACK, yacht, 25), 1e-9)
        assertEquals(0.0, Market.fit(Philosophy.BONUS, yacht, 25), 1e-9)
        assertEquals(0.30 + 0.02 * 10, Market.fit(Philosophy.YOUTH, yacht, 22), 1e-9)
        assertEquals(0.02 * 10, Market.fit(Philosophy.YOUTH, yacht, 23), 1e-9)
        assertEquals(0.20, Market.fit(Philosophy.STABLE, yacht, 25), 1e-9)
        assertEquals(-0.10, Market.fit(Philosophy.STABLE, yacht.copy(mental = 40, volatile = true), 25), 1e-9)
        assertEquals(0.15 + 0.10, Market.fit(Philosophy.COMBO, yacht.copy(family = StyleFamily.COMBO, side = -10), 25), 1e-9)
        assertEquals(0.15 + 0.20 * 1.5, Market.fit(Philosophy.PRAGMATIC, yacht.copy(family = StyleFamily.EV), 25, efficiency = 1.5), 1e-9)
        assertEquals(0.30 * 2.0, Market.fit(Philosophy.STAR, null, null, zRating = 2.0), 1e-9)
        assertEquals(0.0, Market.fit(null, yacht, 25))
        assertEquals(0.15, Market.fit(Philosophy.ATTACK, yacht.copy(family = StyleFamily.BOLD, bold = 0), 25), 1e-9)
        // c04's 공격형 coach takes the 요트파 over a slightly better-rated 족보파.
        val m = Mk.market()
        m.traits[Mk.key(1, 1)] = PlayerTraits(StyleFamily.COMBO, 0, 0, 50, false, null, null)
        m.traits[Mk.key(1, 2)] = yacht
        Coaching.coach(m.book, "k1", "c04", philosophy = Philosophy.ATTACK)
        assertEquals(Mk.key(1, 2), Mk.incoming(m.run(), "c04").first().key)
        // A 스타 영입형 coach pays over the asked fee, as far as its budget goes (at most 1.2 ×).
        val star = Mk.market()
        Coaching.coach(star.book, "k1", "c04", philosophy = Philosophy.STAR)
        val d = Mk.incoming(star.run(), "c04").first()
        assertEquals(Math.round(star.value(d.key) * Market.STAR_PREMIUM).toInt(), d.fee)
    }

    @Test
    fun aFundSellsAMemberWorthFarMoreThanAtSigning() {
        // Club 4 has the best players; nobody buys in round 1. c03 is a 투자 펀드's whose members signed cheap.
        val m = Mk.market(rating = { c, n -> 1500.0 + 30 * (c - 1) - 6 * (n - 1) })
        val w = m.book.world!!
        w.economy.owners["c03"] = Owner(OwnerType.FUND, "파트너스", 3, 3, 1, 0)
        w.clubs.getValue("c01").balance = 800
        for (k in m.book.members("c03")) m.setTerms(k) { it.copy(wage = 5) }
        // Signed at about 1.2억 by their wage, worth 4-5억 now: each is up for sale by its own draw.
        assertTrue(m.book.members("c03").all { m.value(it) >= Market.FUND_RISE * Market.signedValue(5, 1.0, 1.67) })
        val drawn = m.book.members("c03").filter {
            java.util.Random(DiceStream.mix(m.book.seed, Market.FUND_SALT, s.toLong(), it.hashCode().toLong())).nextDouble() < Market.FUND_SALE
        }
        assertTrue(drawn.isNotEmpty())
        val r = m.run()
        assertTrue(r.of(MarketPhase.PARITY).isEmpty())
        val sale = r.of(MarketPhase.MONEY).first()
        assertEquals("c03", sale.from)
        assertEquals(drawn.maxWith(compareBy<String> { m.value(it) }.thenByDescending { it }), sale.key)
        // The richest budget buys it (c01).
        assertEquals("c01", sale.to)
        // The value read back from a wage: 1.5억 at ω 1.5 is what a 5억 player asks (0.35억 + 0.13 × 5억).
        assertEquals(50, Market.signedValue(Math.round((3.5 + 0.13 * 50) * 1.5).toInt(), 1.5, 1.0))
        assertEquals(Valuation.MIN_VALUE, Market.signedValue(0, 1.0, 1.0))
    }

    @Test
    fun theWindowIsDeterministic() {
        fun one(): String {
            val m = Mk.market(clubs = 8, balance = 900, income = 500)
            for (k in listOf(Mk.key(3, 7), Mk.key(5, 8), Mk.key(1, 4))) m.setTerms(k) { it.copy(until = s) }
            for (k in listOf(Mk.key(1, 7), Mk.key(1, 8))) m.ages[k] = 19
            m.story(1, Mk.key(2, 2), "c07")
            m.book.world!!.clubs.getValue("c05").apply { trouble = 1; balance = -400 }
            m.run()
            return m.book.encode()
        }
        assertEquals(one(), one())
    }

    @Test
    fun theWinterWindowIsDomesticOneDealAClub() {
        val m = Mk.market(clubs = 16, size = 6)
        val plan = m.plan(s)
        // Every club chases a target above its place but c02, which is frozen (its squad was drawn).
        val r = Market.winter(m.book, plan, m.input(), { c -> if (c == "c01") 1 else 1 }, { it == "c02" })
        val deals = r.deals
        assertEquals(Market.winterCap(16), deals.size, deals.toString())
        assertTrue(deals.all { it.type == MarketReport.WINTER && it.phase == MarketPhase.WINTER })
        assertTrue(deals.none { it.from == "c02" || it.to == "c02" })
        assertEquals(deals.size, deals.map { it.to }.distinct().size)
        // After the first ⌈C/8⌉ of a nation, deadline deals.
        assertEquals(listOf(false, false, true, true), deals.map { 'D' in it.flags })
        for (d in deals) assertEquals(s, m.terms(d.key).since, "a winter contract starts this season")
        assertTrue(m.book.notable.filter { it.season == s }.all { it.type == MarketReport.WINTER })
        // The off-season after it still knows the winter: its moves come before the window's in the season's log.
        val after = m.run(plan = plan)
        assertTrue(after.deals.none { it.type == MarketReport.WINTER })
    }

    @Test
    fun theWintersRecordFeeIsTold() {
        val m = Mk.market(clubs = 16, size = 6)
        m.book.world!!.worldRecord = FeeRecord("old", null, null, 1, 1)
        val r = Market.winter(m.book, m.plan(s), m.input(), { 1 })
        val record = assertNotNull(r.record, r.deals.toString())
        assertEquals("W", record.scope)
        val d = r.deals[record.deal]
        assertTrue('W' in d.flags)
        assertEquals(listOf(d.key, d.from, d.to), r.events().single { it.kind == "record_fee" }.keys)
    }

    @Test
    fun aLoanClubDoesNotSellItsLoanee() {
        // c02's member plays the season on loan at c08; winter talks with c03 name c08 as the seller.
        val m = Mk.market(clubs = 8)
        val k = Mk.key(2, 8)
        m.book.place(k, "c08")
        m.setTerms(k) { it.copy(loanFrom = "c02") }
        m.story(1, k, "c03", winter = true)
        Market.winter(m.book, m.plan(s), m.input(), { 1 })
        assertEquals(StoryStage.COLLAPSED to 'R', m.story(1).stage to m.story(1).reason)
        assertEquals("c08", m.book.clubOf(k))
        assertEquals("c02", m.terms(k).loanFrom)
    }

    @Test
    fun aPlayersTraitsAreReadOncePerWindow() {
        // The off-season's traits come from the genes: the window asks for each player's once, however
        // many buyers score it.
        val m = Mk.market(clubs = 8)
        for (c in m.book.clubs) Coaching.coach(m.book, "k${c.id.drop(1).toInt()}", c.id, philosophy = Philosophy.YOUTH)
        val asked = HashMap<String, Int>()
        val base = m.input()
        val r = m.run(MarketInput(base.club, base.tables, traits = { k -> asked.merge(k, 1, Int::plus); m.traits[k] }))
        assertTrue(r.deals.isNotEmpty())
        assertTrue(asked.isNotEmpty() && asked.values.all { it == 1 }, asked.toString())
    }

    @Test
    fun randomWindowsKeepTheirRules() {
        val all = listOf("GB", "ES", "DE", "KR", "JP")
        for (seed in 1L..40L) {
            val rnd = java.util.Random(seed)
            val nations = all.take(1 + rnd.nextInt(all.size))
            val m = Mk.market(clubs = 2 + rnd.nextInt(9), size = 4 + rnd.nextInt(6), nations = nations, rating = { _, _ -> 1300.0 + rnd.nextInt(500) })
            val w = m.book.world!!
            val keys = m.book.clubs.flatMap { it.squad }
            val types = OwnerType.entries
            for (c in m.book.clubs) {
                w.clubs.getValue(c.id).balance = rnd.nextInt(1200) - 300
                if (w.clubs.getValue(c.id).balance < 0 && rnd.nextInt(3) == 0) w.clubs.getValue(c.id).trouble = 1
                w.economy.owners[c.id] = Owner(types[rnd.nextInt(types.size)], "주인", 1 + rnd.nextInt(5), 3, 1, 0)
                Eco.closed(m.book, c.id, s, 100 + rnd.nextInt(600), c.squad.size * 10)
                if (rnd.nextInt(3) == 0) Coaching.coach(m.book, "k${c.id.drop(1).toInt()}", c.id, philosophy = Philosophy.entries[rnd.nextInt(Philosophy.entries.size)])
            }
            for (k in keys) {
                m.ages[k] = 18 + rnd.nextInt(18)
                m.setTerms(k) { it.copy(until = s - 1 + rnd.nextInt(5), wage = 3 + rnd.nextInt(30), clause = if (rnd.nextInt(5) == 0) 50 + rnd.nextInt(400) else 0) }
                // Some play abroad: their nation is another one.
                m.nationOf[k] = if (nations.size > 1 && rnd.nextInt(8) == 0) nations[rnd.nextInt(nations.size)] else m.book.nationOf(m.book.clubOf(k)!!)!!
            }
            // Loans of the season before: a young member of one club plays for another of its nation.
            for (n in nations) {
                val cs = m.nations.getValue(n)
                val k = m.book.members(cs.first()).lastOrNull() ?: continue
                m.ages[k] = 19
                m.book.place(k, cs.last())
                m.setTerms(k) { it.copy(loanFrom = cs.first()) }
            }
            // Stories of the season: talks and rumours, at home and abroad.
            var id = 1
            for (k in keys.shuffled(rnd).take(6)) {
                val seller = m.book.clubOf(k) ?: continue
                val buyer = m.book.clubs.map { it.id }.filter { it != seller }.shuffled(rnd).firstOrNull() ?: continue
                val st = MarketStory(id++, s, if (rnd.nextBoolean()) StoryStage.TALKS else StoryStage.RUMOUR, k, buyer, seller, 50, 28, 30, '-', false, null)
                w.market.stories += st
            }
            val input = m.input(config = LeagueConfig(ffp = rnd.nextInt(3)))
            val plan = m.plan()
            val copy = ClubBook.decode(m.book.encode())
            val before = m.book.clubs.associate { it.id to it.squad.toSet() }
            val balances = m.book.clubs.associate { it.id to w.clubs.getValue(it.id).balance }
            val offset = m.book.history.count { it.season == s }
            val r = m.run(input, plan)
            val why = "seed $seed: ${r.deals}"
            // Every deal is one logged move, in order.
            val logged = m.book.history.filter { it.season == s }.drop(offset)
            assertEquals(r.deals.size, logged.size, why)
            assertEquals(r.deals.map { Triple(it.key, it.from, it.to) }, logged.map { Triple(it.ai, it.from, it.to) }, why)
            // Rebuilt after a reload, asked again: the same; the same book gives the same window.
            assertEquals(r, Market.report(ClubBook.decode(m.book.encode()), s), why)
            assertEquals(r, m.run(input, plan), why)
            Market.offseason(copy, plan, input)
            assertEquals(m.book.encode(), copy.encode(), why)
            // Each fee leaves the buyer and reaches the seller.
            for (c in m.book.clubs) {
                val fees = r.deals.filter { it.from == c.id }.sumOf { it.fee } - r.deals.filter { it.to == c.id }.sumOf { it.fee }
                assertEquals(balances.getValue(c.id) + fees, w.clubs.getValue(c.id).balance, "${c.id} $why")
            }
            // At most 2 buys a club, ⌊C/2⌋ + ⌈C/4⌉ domestic deals a nation.
            val buying = setOf(MarketPhase.ABROAD, MarketPhase.TALKS, MarketPhase.PARITY, MarketPhase.FORCED, MarketPhase.MONEY, MarketPhase.DEADLINE, MarketPhase.BACK)
            val buys = r.deals.filter { it.phase in buying && it.to != null && it.type !in "${MarketReport.RELEASE}${MarketReport.LOAN_BACK}" && it.type != 'H' }
            for ((club, ds) in buys.groupBy { it.to }) assertTrue(ds.size <= Market.MAX_BUYS, "$club $why")
            for (n in nations) {
                val home = buys.filter { m.book.nationOf(it.to!!) == n && m.book.nationOf(it.from ?: "") == n && it.phase != MarketPhase.ABROAD }
                assertTrue(home.size <= Market.dealCap(m.nations.getValue(n).size), "$n $why")
            }
            // Every member has terms, a newcomer a contract from next season; no club grew past its ceiling.
            for (c in m.book.clubs) {
                val members = m.book.members(c.id).size
                val all = m.nations.getValue(c.nation).sumOf { before.getValue(it).size }
                assertTrue(members <= maxOf(before.getValue(c.id).size, ClubBook.maxSize(all, m.nations.getValue(c.nation).size)), "${c.id} $why")
                for (k in m.book.members(c.id)) {
                    val t = w.terms[k]
                    assertNotNull(t, "$k $why")
                    val back = r.deals.any { it.key == k && it.type == MarketReport.LOAN_BACK }
                    if (k !in before.getValue(c.id) && t.loanFrom == null && !back) assertTrue(t.since == s + 1 && t.until > s, "$k $t $why")
                }
            }
            // No talks are left open; a story that came true has its player at the buyer.
            assertTrue(m.book.stories(s).none { it.stage == StoryStage.TALKS || it.stage == StoryStage.RUMOUR }, why)
            for (st in m.book.stories(s).filter { it.stage == StoryStage.DONE }) assertEquals(st.buyer, m.book.clubOf(st.key), "$st $why")
        }
    }
}

package yacht.engine

import yacht.engine.league.Market
import yacht.engine.league.MarketPhase
import yacht.engine.league.MarketReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The relative concentration guard (design ENG 1.5): after any incoming move no club holds more than
 * `cap(C) = max(2, ⌈24 / C⌉)` of the 16 best-rated members playing for its nation's clubs.
 */
class ConcentrationTest {
    private val s = Mk.SEASON

    @Test
    fun theCapIsRelativeToTheClubs() {
        assertEquals(listOf(12, 8, 6, 5, 3, 2), listOf(2, 3, 4, 5, 8, 16).map(Market::cap))
        assertEquals(Int.MAX_VALUE, Market.cap(1))
        assertEquals(Int.MAX_VALUE, Market.cap(0))
        assertEquals(2, Market.cap(13))
        assertEquals(4, Market.cap(6))
    }

    @Test
    fun aFairBookOfTwoClubsNeverRefusesAMove() {
        // Two clubs of 10 sharing the nation's best alike (8 of the 16 best each).
        val m = Mk.market(clubs = 2, size = 10, rating = { c, n -> 1600.0 - 3 * (2 * (n - 1) + (c - 1)) })
        val r = { k: String -> m.ratings.getValue(k) }
        for (k in m.book.members("c01")) assertTrue(Market.allowed(m.book, k, "c02", r), k)
        for (k in m.book.members("c02")) assertTrue(Market.allowed(m.book, k, "c01", r), k)
        // A whole window: every buy the rules allow goes through (the guard refuses none).
        val report = m.run()
        assertTrue(report.deals.any { it.fee > 0 }, report.deals.toString())
    }

    @Test
    fun aClubAtTheCapTakesNoneOfTheBestButMayTakeOthers() {
        // c01 holds 8 of KR's 16 best (cap 6 with 4 clubs): one more of them is refused, a weaker one not.
        val m = Mk.market()
        val r = { k: String -> m.ratings.getValue(k) }
        assertFalse(Market.allowed(m.book, Mk.key(2, 1), "c01", r))
        assertTrue(Market.allowed(m.book, Mk.key(4, 8), "c01", r), "outside the 16 best")
        assertTrue(Market.allowed(m.book, Mk.key(1, 1), "c04", r))
        // A member the club already has moves nothing.
        assertTrue(Market.allowed(m.book, Mk.key(1, 1), "c01", r))
        // Foreigners count like anyone: a JP player at c04 among KR's best fills c04's share.
        for (n in 1..6) m.ratings[Mk.key(4, n)] = 1700.0 - n
        m.nationOf[Mk.key(4, 1)] = "JP"
        assertFalse(Market.allowed(m.book, Mk.key(2, 1), "c04", r))
        m.book.place(Mk.key(4, 1), null)
        assertTrue(Market.allowed(m.book, Mk.key(2, 1), "c04", r))
    }

    @Test
    fun theGuardHoldsForTalksClausesAndFreeAgents() {
        // Talks: c01's talks for one more of the 16 best end on 규정 (MarketTest checks the reason).
        val clause = Mk.market()
        clause.setTerms(Mk.key(2, 1)) { it.copy(clause = 70) }
        val r = clause.run()
        assertTrue(r.deals.none { it.type == MarketReport.CLAUSE && it.to == "c01" }, "a clause does not beat the guard")
        // A free agent among the 16 best whom only c01 could sign (the others are under an embargo) goes to the draft.
        val free = Mk.market()
        val fa = Mk.key(2, 1)
        free.setTerms(fa) { it.copy(until = s, wage = 5) }
        free.story(1, fa, "c01", stage = yacht.engine.league.StoryStage.RUMOUR)
        for (c in listOf("c02", "c03", "c04")) free.book.world!!.clubs.getValue(c).trouble = 1
        val q = free.run()
        assertEquals(MarketReport.RELEASE, q.deals.single { it.key == fa }.type, q.deals.toString())
        assertNull(free.book.clubOf(fa))
    }

    @Test
    fun theGuardHoldsForLoansAndTheWinter() {
        // c01 (8 of the 16 best) lends its 7th; c04, the lowest placed, already holds 6 of them, so c03 takes the loan.
        val m = Mk.market(balance = 0, rating = { c, n ->
            when (c) {
                1 -> 1600.0 - 6 * (n - 1)
                4 -> if (n <= 6) 1590.0 - 6 * (n - 1) else 1400.0 - n
                else -> 1450.0 - 10 * c - n
            }
        })
        m.ages[Mk.key(1, 7)] = 20
        m.ages[Mk.key(1, 8)] = 20
        val loan = m.run().deals.single { it.type == MarketReport.LOAN }
        assertEquals(Triple(Mk.key(1, 7), "c01", "c03"), Triple(loan.key, loan.from, loan.to))
        // The winter: c01, last in the table and chasing, holds too many of the best to buy any of them.
        val w = Mk.market()
        val last = mapOf("KR" to listOf("c04", "c03", "c02", "c01"))
        val chasing = { c: String -> if (c == "c01") 1 else 4 - w.nations.getValue("KR").indexOf(c) }
        assertTrue(Market.winter(w.book, w.plan(), w.input(order = last), chasing).deals.isEmpty())
        // A fair nation's last club buys.
        val fair = Mk.market(rating = { c, n -> 1600.0 - 3 * (4 * (n - 1) + (c - 1)) })
        val deals = Market.winter(fair.book, fair.plan(), fair.input(order = last), { c -> if (c == "c01") 1 else 4 - fair.nations.getValue("KR").indexOf(c) }).deals
        assertEquals(listOf("c01"), deals.map { it.to })
        assertEquals(MarketPhase.WINTER, deals.single().phase)
    }

    @Test
    fun anAbroadBuyerAtTheCapIsPassedOverForTheNextWithRoom() {
        // Four leagues ranked GB, ES, DE, KR; KR's best goes to GB (three places higher). GB's clubs have
        // equal room and money, so the first by id buys, but c01 holds 8 of GB's 16 best (cap 6) and is
        // passed over. c02 holds 7 and may buy: the star pushes its own 16th out, so it holds no more than before.
        val m = Mk.market(nations = listOf("GB", "ES", "DE", "KR"))
        val star = Mk.key(13, 1)
        val r = { k: String -> m.ratings[k] ?: Double.NaN }
        assertFalse(Market.allowed(m.book, star, "c01", r))
        assertTrue(Market.allowed(m.book, star, "c02", r))
        val out = m.run().of(MarketPhase.ABROAD).single()
        assertEquals(Triple(star, "c13", "c02"), Triple(out.key, out.from, out.to))
        assertEquals(yacht.engine.league.ClubMoveType.ABROAD.code, out.type)
        // Without the club world's money the plan sends it to c01 as before.
        assertEquals("c01", Mk.market(nations = listOf("GB", "ES", "DE", "KR")).let { b ->
            yacht.engine.league.Transfers.plan(
                yacht.engine.league.TransferInput(
                    b.plan().league, b.ranking(), b.members(), { b.ratings[it] ?: Double.NaN }, { 25 }, { k -> b.book.clubOf(k)?.let { b.book.nationOf(it) } },
                    yacht.engine.league.Origin::of, b.book.clubs, { 11 }, yacht.engine.league.LeagueConfig(),
                ),
            ).single { !it.home }.toClub
        })
    }
}

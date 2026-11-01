package yacht.engine

import yacht.engine.league.ClubEconomy
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.Leagues
import yacht.engine.league.Market
import yacht.engine.league.MarketPhase
import yacht.engine.league.MarketReport
import yacht.engine.league.Owner
import yacht.engine.league.OwnerType
import yacht.engine.league.PlayerTraits
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StoryStage
import yacht.engine.league.Valuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contracts (design ENG 1b.8, 1.1): lengths, expiries and renewals, the underpaid, the wages offered. */
class ContractsTest {
    private val s = Mk.SEASON

    @Test
    fun aContractsLengthFollowsTheAge() {
        assertEquals(4, Market.length(18, 1L, "g1"))
        assertEquals(4, Market.length(21, 1L, "g1"))
        val mid = (1..200).map { Market.length(24, 1L, "g$it") }
        assertEquals(setOf(3, 4), mid.toSet(), "22-25: 3 or 4 by the key")
        assertEquals(mid, (1..200).map { Market.length(24, 1L, "g$it") })
        assertTrue((1..200).all { Market.length(22, 1L, "g$it") == Market.length(25, 1L, "g$it") })
        assertEquals(3, Market.length(26, 1L, "g1"))
        assertEquals(3, Market.length(29, 1L, "g1"))
        assertEquals(2, Market.length(30, 1L, "g1"))
        assertEquals(2, Market.length(32, 1L, "g1"))
        assertEquals(1, Market.length(33, 1L, "g1"))
        assertEquals(1, Market.length(40, 1L, "g1"))
        assertEquals(3, Market.length(18, 1L, "g1", draft = true))
        assertEquals(3, Market.length(null, 1L, "g1"))
    }

    @Test
    fun theUnderpaidAreBelowThreeQuartersOfWhatTheirValueAsks() {
        // A value of 6억 asks 0.35억 + 0.13 × 6억 ≈ 1.1억; at ω 1.2 three quarters of that is 0.99억.
        val value = 60
        assertEquals(11, Valuation.baseWage(value))
        assertTrue(ClubEconomy.underpaid(9, value, 1.2))
        assertFalse(ClubEconomy.underpaid(10, value, 1.2))
        assertFalse(ClubEconomy.underpaid(9, value, 1.0), "9 ≥ 0.75 × 11 at ω 1")
        assertTrue(ClubEconomy.underpaid(8, value, 1.0))
    }

    @Test
    fun theWageOfferedFollowsTheValueTheLeagueAndTheClub() {
        val m = Mk.market()
        val w = m.book.world!!
        val economy = LeagueEconomy(mapOf("KR" to 1.5), emptyMap())
        val v = 62
        // A balance of 1.5 × income: club factor 1 + (2/3) × 1.0; ω from the book's wage index.
        assertEquals(Math.round(Valuation.baseWage(v) * 1.0 * (1 + 2.0 / 3)).toInt(), ClubEconomy.wageFor(m.book, "c04", v, "KR", economy))
        w.wageIndex["KR"] = 150
        assertEquals(Math.round(Valuation.baseWage(v) * 1.5 * (1 + 2.0 / 3)).toInt(), ClubEconomy.wageFor(m.book, "c04", v, "KR", economy))
        // Capped at 30% of the income; never below 0.3억 × √λ.
        Eco.closed(m.book, "c04", s, 40, 80)
        assertEquals(12, ClubEconomy.wageFor(m.book, "c04", v, "KR", economy))
        assertEquals(4, ClubEconomy.minWage(1.5))
        assertEquals(6, ClubEconomy.wage(Valuation.MIN_VALUE, 0.5, 4.0, 0, 100, Owner.NONE), "0.3억 × √4 wins over 0.4억 × 0.5")
        // A window's signing is on that wage: the buyer's offer when it decided.
        val n = Mk.market()
        n.book.world!!.wageIndex["KR"] = 120
        val d = Mk.incoming(n.run(), "c04").first()
        assertEquals(Math.round(Valuation.baseWage(n.value(d.key)) * 1.2 * (1 + 2.0 / 3)).toInt(), d.wage)
        assertEquals(d.wage, n.terms(d.key).wage)
    }

    @Test
    fun expiringContractsAreRenewedForTheCountedAndTheYoung() {
        val m = Mk.market()
        val c = "c02"
        val members = m.book.members(c)
        for (k in members) m.setTerms(k) { it.copy(until = s, number = 10 + members.indexOf(k), since = 1, apps = 40) }
        // The 7th is 23 with 8 points of growth room; the 8th has none.
        m.ages[members[6]] = 23
        m.traits[members[6]] = PlayerTraits(null, 0, 0, 50, false, 70, 62)
        m.traits[members[7]] = PlayerTraits(null, 0, 0, 50, false, 70, 70)
        val r = m.run()
        for (k in members.take(7)) {
            val t = m.terms(k)
            if (m.book.clubOf(k) != c) continue // sold in the window afterwards: its new club's contract
            assertEquals(s + Market.length(m.ages[k] ?: 25, m.book.seed, k), t.until, k)
            assertEquals(1, t.since, "a renewal keeps the stint")
            assertEquals(40, t.apps)
            assertEquals(10 + members.indexOf(k), t.number)
        }
        assertEquals(7, r.renewals)
        val renewed = r.renewal!!
        assertEquals(members.first(), renewed.key, "the news names the best rated")
        assertEquals(c, renewed.club)
        val event = r.events().single { it.kind == "contract_renewal" }
        assertEquals(listOf(renewed.key, c), event.keys)
        assertEquals(listOf(renewed.seasons.toString(), renewed.wage.toString()), event.args)
        // The 8th was not offered one: a free agent, signed elsewhere or off to the draft.
        val gone = r.deals.single { it.key == members[7] }
        assertEquals(c, gone.from)
        assertTrue(gone.type == MarketReport.FREE || gone.type == MarketReport.RELEASE)
    }

    @Test
    fun aClubInTroubleRenewsOnlyItsBestThreeAtEightyPercent() {
        val m = Mk.market()
        val c = "c03"
        val w = m.book.world!!
        w.clubs.getValue(c).apply { trouble = 1; balance = -20 }
        val members = m.book.members(c)
        for (k in members) m.setTerms(k) { it.copy(until = s) }
        val economy = LeagueEconomy(mapOf("KR" to 1.5), emptyMap())
        val offers = members.associateWith { ClubEconomy.wageFor(m.book, c, m.value(it), "KR", economy) }
        val r = m.run()
        assertEquals(3, r.renewals)
        for (k in members.take(3)) {
            if (m.book.clubOf(k) != c) continue
            assertEquals(Math.round(offers.getValue(k) * ClubEconomy.RENEWAL_IN_TROUBLE).toInt(), m.terms(k).wage)
        }
        for (k in members.drop(3)) assertTrue(r.deals.any { it.key == k && it.from == c }, k)
    }

    @Test
    fun theUnderpaidWithABetterSuitorAndTheOldOutsideTheTopLeaveForNothing() {
        val m = Mk.market()
        val star = Mk.key(2, 1)
        val other = Mk.key(2, 2)
        // Both underpaid (0.5억 against a value of 5억); only the star is courted, by c01, placed above c02.
        for (k in listOf(star, other)) m.setTerms(k) { it.copy(until = s, wage = 5) }
        m.story(1, star, "c01", stage = StoryStage.RUMOUR)
        val r = m.run()
        assertTrue(r.deals.any { it.key == star && it.from == "c02" && (it.type == MarketReport.FREE || it.type == MarketReport.RELEASE) }, r.deals.toString())
        assertTrue(r.deals.none { it.key == other && it.from == "c02" && (it.type == MarketReport.FREE || it.type == MarketReport.RELEASE) })
        assertEquals(1, r.renewals)
        // With the market's stories off, nobody is courted: the star renews.
        val off = Mk.market()
        for (k in listOf(star, other)) off.setTerms(k) { it.copy(until = s, wage = 5) }
        off.story(1, star, "c01", stage = StoryStage.RUMOUR)
        val q = off.run(off.input(market = false))
        assertEquals(2, q.renewals)
        // A member of 33 or older outside the 1부 does not renew: two divisions, c02's best in the 2부.
        val old = Mk.market()
        val veteran = Mk.key(2, 1)
        old.ages[veteran] = 34
        old.setTerms(veteran) { it.copy(until = s) }
        val keys = old.book.clubs.flatMap { it.squad }
        val season = Season.start(s, 7L, listOf(keys.filter { it != veteran }, listOf(veteran)), true, Leagues.national(listOf("KR"), listOf(2)))
        val plan = SeasonPlan(season, LeagueConfig())
        val p = old.run(plan = plan)
        assertEquals(0, p.renewals)
        assertTrue(p.deals.any { it.key == veteran && it.from == "c02" })
        // In the 1부 it renews (one season, at 33+).
        val top = Mk.market()
        top.ages[veteran] = 34
        top.setTerms(veteran) { it.copy(until = s) }
        assertEquals(1, top.run().renewals)
        if (top.book.clubOf(veteran) == "c02") assertEquals(s + 1, top.terms(veteran).until)
    }

    @Test
    fun aFreeAgentKeepsItsAcademyAndJoinsWithNewTerms() {
        val m = Mk.market()
        val fa = Mk.key(3, 8)
        m.setTerms(fa) { it.copy(until = s, academy = "c03", gradSeason = 1, apps = 90, number = 23) }
        val r = m.run()
        val d = r.deals.single { it.key == fa }
        if (d.to != null) {
            val t = m.terms(fa)
            assertEquals("c03", t.academy)
            assertEquals(1, t.gradSeason)
            assertEquals(0, t.apps)
            assertEquals(s + 1, t.since)
            assertNull(t.loanFrom)
            assertEquals(d.wage, t.wage)
        } else {
            assertNull(m.book.clubOf(fa))
            assertEquals("c03", m.book.world!!.terms.getValue(fa).academy, "kept for the draft")
        }
        // A free agent costs no fee.
        assertTrue(r.deals.filter { it.type == MarketReport.FREE }.all { it.fee == 0 })
    }
}

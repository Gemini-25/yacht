package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubTable
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LifeReport
import yacht.engine.league.OwnerType
import yacht.engine.league.troubleLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Money trouble (design ENG 1.4): 적자, 재정 위기, 법정관리, the rescue and the dissolution; the embargo, forced sales and the club-table deduction. */
class TroubleTest {
    /** A Korean league of [n] clubs of two members each, every one with an income of 10억 and the given balances. */
    private fun book(vararg balances: Int): ClubBook {
        val specs = balances.indices.map { i -> Eco.Spec("c0${i + 1}", "KR", listOf("a$i", "b$i"), city = i) }
        val book = Eco.world(Eco.book(specs))
        for ((i, b) in balances.withIndex()) book.world!!.state("c0${i + 1}").balance = b
        return book
    }

    /** The club-life step after season [season] (accounts of an income of 10억 made first), with plan seed [seed]. */
    private fun close(book: ClubBook, season: Int, seed: Long = 7L, config: LeagueConfig = LeagueConfig()): LifeReport {
        val ids = book.clubs.map { it.id }
        for (id in ids) Eco.closed(book, id, season, 100)
        val plan = Eco.plan(season, listOf("KR" to book.clubs.flatMap { it.squad }), seed, config)
        return ClubEconomy.life(book, plan, Eco.input(book, config = config), mapOf("KR" to Eco.table("KR", ids)))
    }

    @Test
    fun theThresholdsOfRedTroubleAndAdministration() {
        val book = book(-1, -51, -101, 0)
        val r = close(book, 3)
        val w = book.world!!
        assertEquals(1, book.troubleLevel("c01"), "in the red")
        assertEquals(listOf(0, 0, 0), w.clubs.getValue("c01").let { listOf(it.trouble, it.admin, it.deduction) })
        assertEquals(2, book.troubleLevel("c02"), "재정 위기 below −0.5 × income")
        assertEquals(1, w.clubs.getValue("c02").trouble)
        assertEquals(0, book.troubleLevel("c04"))
        // 법정관리 below −1.0 × income: the next season's club table takes 10% off, rescued or not.
        assertEquals(10, w.clubs.getValue("c03").deduction)
        assertEquals(listOf("c03"), r.administration)
        assertTrue("c02" in r.trouble && "c03" in r.trouble)
        assertEquals(setOf("c01", "c02", "c03"), r.red.toSet())
        val rescued = r.owners.any { it.club == "c03" && it.reason == 'R' }
        assertEquals(if (rescued) 0 else 1, w.clubs.getValue("c03").admin)
        // The news: one 재정 위기 (not the club in administration), one administration.
        val ev = r.events({ w.clubs.getValue(it).balance }, { w.clubs.getValue(it).mood }, { null })
        assertEquals(listOf("c02"), ev.filter { it.kind == "club_trouble" }.map { it.keys.single() })
        assertEquals(listOf("c03"), ev.filter { it.kind == "administration" }.map { it.keys.single() })
        // Asked again: nothing changes.
        val text = book.encode()
        val again = ClubEconomy.life(book, Eco.plan(3, listOf("KR" to book.clubs.flatMap { it.squad })), Eco.input(book), mapOf("KR" to Eco.table("KR", book.clubs.map { it.id })))
        assertEquals(LifeReport(3), again)
        assertEquals(text, book.encode())
        assertEquals(text, ClubBook.decode(text).encode())
    }

    @Test
    fun aRescueHalvesTheDebtAboutSevenTimesInTen() {
        var rescued = 0
        for (seed in 1L..400L) {
            val book = book(-150)
            val r = close(book, 3, seed)
            val again = book(-150)
            assertEquals(r, close(again, 3, seed), "seeded")
            val o = r.owners.singleOrNull { it.reason == 'R' } ?: continue
            rescued++
            val st = book.world!!.clubs.getValue("c01")
            // The debt halved, at most to −0.2 × income: here −20; out of administration and of trouble.
            assertEquals(-20, st.balance)
            assertEquals(-20, book.life("c01")!!.last!!.balance)
            assertEquals(130, book.life("c01")!!.last!!.owner)
            assertEquals(listOf(0, 0, 10), listOf(st.admin, st.trouble, st.deduction))
            assertTrue(o.owner.type == OwnerType.LOCAL || o.owner.type == OwnerType.TYCOON)
            assertEquals(4, o.owner.since)
            assertEquals(o.owner, book.life("c01")!!.owner)
        }
        assertTrue(rescued in 250..310, "$rescued of 400")
    }

    /** A book whose clubs are all a season into administration, closing season [season] with plan seed [seed]. */
    private fun second(season: Int, seed: Long, n: Int = 1): Pair<ClubBook, LifeReport> {
        val book = book(*IntArray(n) { -150 })
        for (st in book.world!!.clubs.values) {
            st.admin = 1
            st.trouble = 1
        }
        return book to close(book, season, seed)
    }

    @Test
    fun twoOffSeasonsInAdministrationWithoutARescueDissolveTheClub() {
        // The first off-season in administration never dissolves.
        for (seed in 1L..50L) assertTrue(close(book(-150), 3, seed).dissolve.isEmpty())
        val fails = (1L..400L).first { s -> second(5, s).second.owners.isEmpty() }
        val (book, r) = second(5, fails)
        assertEquals(listOf("c01"), r.dissolve)
        assertEquals(5, book.world!!.dissolvedBy)
        assertEquals(2, book.world!!.clubs.getValue("c01").admin)
        val saves = (1L..400L).first { s -> second(5, s).second.owners.isNotEmpty() }
        assertTrue(second(5, saves).second.dissolve.isEmpty())
    }

    @Test
    fun atMostOneDissolutionInFiveSeasonsWorldWide() {
        // Both clubs' rescues fail: the first by id is dissolved, the second rescued instead.
        val both = (1L..2000L).first { s ->
            val (_, r) = second(5, s, 2)
            r.dissolve.size == 1 && r.owners.none { it.club == r.dissolve.single() } && r.owners.any { it.club != r.dissolve.single() && it.reason == 'R' }
        }
        val (book, r) = second(5, both, 2)
        assertEquals(listOf("c01"), r.dissolve)
        assertEquals("c02", r.owners.single { it.reason == 'R' }.club)
        assertEquals(0, book.world!!.clubs.getValue("c02").admin)
        // A failing club three seasons after the last dissolution is rescued instead; five seasons after it, it goes.
        fun spaced(season: Int, seed: Long): LifeReport {
            val b = book(-150)
            b.world!!.dissolvedBy = 5
            for (st in b.world!!.clubs.values) {
                st.admin = 1
                st.trouble = 1
            }
            return close(b, season, seed)
        }
        val fails = (1L..400L).first { s -> spaced(10, s).dissolve.isNotEmpty() }
        val r8 = spaced(8, fails)
        assertTrue(r8.dissolve.isEmpty(), "within 5 seasons of the last")
        assertEquals('R', r8.owners.single().reason)
        assertEquals(listOf("c01"), spaced(10, fails).dissolve)
    }

    @Test
    fun anEmbargoForcedSalesAndRenewalsInTrouble() {
        val book = book(-80, -10, 50)
        for (id in listOf("c01", "c02", "c03")) Eco.closed(book, id, 3, 100)
        val w = book.world!!
        w.clubs.getValue("c01").trouble = 1
        assertTrue(ClubEconomy.embargo(book, "c01"))
        assertEquals(0, ClubEconomy.transferBudget(book, "c01"))
        assertFalse(ClubEconomy.embargo(book, "c02"), "in the red under soft FFP: no embargo")
        assertTrue(ClubEconomy.embargo(book, "c02", ClubEconomy.FFP_STRICT), "in the red under strict FFP")
        assertFalse(ClubEconomy.embargo(book, "c03", ClubEconomy.FFP_STRICT))
        // Forced sales: the most valuable first, until −0.25 × income at 0.8 × value, at most 2.
        val values = mapOf("a0" to 60, "b0" to 40)
        assertEquals(listOf("a0", "b0"), ClubEconomy.forcedSales(book, "c01", values::getValue), "−80 + 48 = −32, + 32 = 0")
        assertEquals(-25, ClubEconomy.forcedTarget(book, "c01"))
        w.clubs.getValue("c01").balance = -60
        assertEquals(listOf("a0"), ClubEconomy.forcedSales(book, "c01", values::getValue), "−60 + 48 = −12 ≥ −25")
        w.clubs.getValue("c01").balance = -900
        assertEquals(2, ClubEconomy.forcedSales(book, "c01", values::getValue).size)
        assertEquals(listOf("a0", "b0"), ClubEconomy.forcedSales(book, "c01") { 10 }, "ties by key")
        assertTrue(ClubEconomy.forcedSales(book, "c02", values::getValue).isEmpty(), "not in trouble")
        // Renewals at 80% while in trouble.
        assertEquals(16, ClubEconomy.renewal(20, (book.troubleLevel("c01") ?: 0) >= 2))
        assertEquals(20, ClubEconomy.renewal(20, (book.troubleLevel("c02") ?: 0) >= 2))
    }

    @Test
    fun theDeductionTakesClubTablePointsNeverTheMembersLeaguePoints() {
        val book = book(-150, 50)
        val members = book.clubs.flatMap { it.squad }
        val plan = Eco.plan(4, listOf("KR" to members))
        for ((i, k) in members.withIndex()) plan.league.standings.getValue(k).points = 10 * (i + 1)
        val points = members.associateWith { plan.league.standings.getValue(it).points }
        for (id in listOf("c01", "c02")) Eco.closed(book, id, 3, 100)
        ClubEconomy.life(book, Eco.plan(3, listOf("KR" to members)), Eco.input(book), mapOf("KR" to Eco.table("KR", listOf("c01", "c02"))))
        assertEquals(mapOf("c01" to 10), ClubEconomy.deductions(book))
        val before = ClubTable.of(plan.league, book, "KR", emptyList())
        val after = ClubTable.of(plan.league, book, "KR", emptyList(), deductions = ClubEconomy.deductions(book))
        val c01 = before.single { it.club == "c01" }
        assertEquals(Math.round(c01.points * 0.9 * 10) / 10.0, after.single { it.club == "c01" }.points)
        assertEquals(before.single { it.club == "c02" }, after.single { it.club == "c02" })
        assertEquals(points, members.associateWith { plan.league.standings.getValue(it).points })
        // Strict FFP: a club in the red at a close loses 5% more.
        val strict = LeagueConfig().copy(ffp = 2)
        val red = book(-10, 50)
        close(red, 3, config = strict)
        assertEquals(mapOf("c01" to 5), ClubEconomy.deductions(red))
        val deep = book(-150)
        close(deep, 3, config = strict)
        assertEquals(mapOf("c01" to 15), ClubEconomy.deductions(deep), "법정관리 and in the red, rescued or not")
    }
}

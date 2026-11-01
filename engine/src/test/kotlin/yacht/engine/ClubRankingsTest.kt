package yacht.engine

import yacht.engine.league.Accounts
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubRanks
import yacht.engine.league.Comp
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.RankLine
import yacht.engine.league.rankings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The club rankings (design ENG 1b.9): value, results, rich, fans and health, the `rk` lines and the rank moves. */
class ClubRankingsTest {
    /** Twelve Korean clubs of two members each; club i has 20,000 − 1,000 i fans and a balance of 10 × (i − 3). */
    private fun book(): ClubBook {
        val book = Eco.world(Eco.book((0 until 12).map { i -> Eco.Spec(ClubBook.idOf(i + 1), "KR", listOf("a$i", "b$i"), city = i) }))
        val w = book.world!!
        for (i in 0 until 12) w.state(ClubBook.idOf(i + 1)).apply {
            fans = 20_000 - 1_000 * i
            balance = 10 * (i - 3)
        }
        return book
    }

    private val members = (0 until 12).flatMap { listOf("a$it", "b$it") }

    /** Every member's value: club i's members are worth 2 × (12 − i) 억 together. */
    private fun value(k: String): Int = 10 * (12 - k.drop(1).toInt())

    @Test
    fun theFiveListsAreSortedAndTheGradesAddUp() {
        val book = book()
        val w = book.world!!
        for (i in 0 until 12) w.economy.accounts[ClubBook.idOf(i + 1)] = Accounts(1, 100 + 7 * (i % 5), 0, 0, 0, 0, 0, 0, 30, 0, 0, 0, 0, 0, 10 * (i - 3))
        // A state kept for a club that is gone is left out.
        w.state("c50").fans = 999_999
        val r = book.rankings(::value)
        for (list in listOf(r.value.map { it.second.toDouble() }, r.results.map { it.second }, r.rich.map { it.second.toDouble() }, r.fans.map { it.second.toDouble() })) {
            assertEquals(12, list.size)
            assertEquals(list.sortedDescending(), list)
        }
        assertTrue(r.fans.none { it.first == "c50" })
        assertEquals("c01", r.fans.first().first)
        assertEquals(ClubEconomy.clubValue(book, "c01", ::value), r.value.first().second)
        // Rich: last season's operating income (no sales); ties by id.
        assertEquals(listOf("c05", "c10", "c04", "c09"), r.rich.take(4).map { it.first })
        assertEquals(128, r.rich.first().second)
        // Health: A first, then the higher balance; the grades add up to the clubs.
        assertEquals(12, r.grades.sum())
        assertEquals(5, r.grades.size)
        val order = "ABCDE"
        assertEquals(r.health.map { order.indexOf(it.second) }.sorted(), r.health.map { order.indexOf(it.second) })
        assertEquals(listOf(0, 5, 4, 3, 0), r.grades, "B from 0.3 × income in the bank, C from 0, D in the red")
        assertEquals(listOf("c12", "c11", "c10"), r.health.take(3).map { it.first })
        // No club world: empty lists.
        val bare = Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a"))))
        assertTrue(bare.rankings(::value).value.isEmpty())
        assertEquals(listOf(0, 0, 0, 0, 0), bare.rankings(::value).grades)
    }

    @Test
    fun resultsAddEachSeasonsPlacesByLambdaAndInternationalPoints() {
        assertEquals(listOf(10, 7, 5, 4, 3, 2, 1, 1, 0), listOf(1, 2, 3, 4, 5, 6, 7, 16, 0).map { ClubRanks.placePoints(it) })
        assertEquals(23.0, ClubRanks.resultPoints(1, 1.6, 14))
        assertEquals(7.0, ClubRanks.resultPoints(2, 1.0, 0))
        val book = book()
        val economy = LeagueEconomy(mapOf("KR" to 1.6), emptyMap())
        val ids = (1..12).map { ClubBook.idOf(it) }
        // Season 1: c01 champion with 6 CL points; season 2: c02 first, c01 second.
        val p1 = Eco.plan(1, listOf("KR" to members))
        p1.comps += Eco.Intl(Comp.CHAMPIONS, mapOf("a0" to 6))
        ClubEconomy.accounts(book, p1, Eco.input(book, economy), mapOf("KR" to Eco.table("KR", ids)))
        ClubEconomy.accounts(book, Eco.plan(2, listOf("KR" to members)), Eco.input(book, economy), mapOf("KR" to Eco.table("KR", listOf("c02", "c01") + ids.drop(2))))
        assertEquals(10 * 1.6 + 3 + 7 * 1.6, ClubRanks.results(book, "c01"), 1e-9)
        assertEquals(7 * 1.6 + 10 * 1.6, ClubRanks.results(book, "c02"), 1e-9)
        val r = book.rankings(::value)
        assertEquals(listOf("c01", "c02", "c03"), r.results.take(3).map { it.first })
        // Five seasons count (최근 5시즌), also where the money history keeps 3 (below 500 members): season 1 leaves after season 6.
        for (s in 3..5) ClubEconomy.accounts(book, Eco.plan(s, listOf("KR" to members)), Eco.input(book, economy), mapOf("KR" to Eco.table("KR", ids.drop(2) + listOf("c01", "c02"))))
        assertEquals(listOf(5, 4, 3), book.life("c01")!!.history.map { it.season })
        assertEquals(10 * 1.6 + 3 + 7 * 1.6 + 3 * 1.6, ClubRanks.results(book, "c01"), 1e-9)
        val ah = book.encode().lines().first { it.startsWith("ah c01 ") }.split(' ')
        assertTrue(ah[2].split(';').all { it.split(':').size == 6 })
        assertEquals("2:112,1:190", ah[3], "the seasons older than the money history")
        val back = ClubBook.decode(book.encode())
        assertEquals(ClubRanks.results(book, "c01"), ClubRanks.results(back, "c01"))
        assertEquals(book.encode(), back.encode())
        ClubEconomy.accounts(book, Eco.plan(6, listOf("KR" to members)), Eco.input(book, economy), mapOf("KR" to Eco.table("KR", ids.drop(2) + listOf("c01", "c02"))))
        assertEquals(7 * 1.6 + 4 * 1.6, ClubRanks.results(book, "c01"), 1e-9)
        assertEquals(4, book.encode().lines().first { it.startsWith("ah c01 ") }.split(' ').size, "season 2 still counts, season 1 is gone")
        // A league that started over drops the seasons of the abandoned timeline.
        ClubEconomy.accounts(book, Eco.plan(4, listOf("KR" to members)), Eco.input(book, economy), mapOf("KR" to Eco.table("KR", ids)))
        assertEquals(10 * 1.6 + 7 * 1.6 + 1.6, ClubRanks.results(book, "c01"), 1e-9)
    }

    @Test
    fun theRkLineKeepsTheTopTenAndTheMoves() {
        val book = book()
        val line = ClubRanks.record(book, 2, ::value, linkedMapOf("KR" to 450))!!
        assertEquals(10, line.value.size)
        assertEquals(book.rankings(::value).value.take(10).map { it.first }, line.value)
        assertEquals(mapOf("KR" to 450), line.leagueRecords)
        assertEquals(listOf(line), book.rankLines)
        // A move against the last line: up is positive; new in the top 10, or out of it now: none.
        val before = listOf("c03", "c01", "c02")
        assertEquals(1, ClubRanks.move(before, "c01", 0))
        assertEquals(-1, ClubRanks.move(before, "c03", 1))
        assertEquals(0, ClubRanks.move(before, "c02", 2))
        assertNull(ClubRanks.move(before, "c09", 3))
        assertNull(ClubRanks.move(before, "c01", 10))
        assertEquals(line, ClubRanks.previous(book, 3))
        assertNull(ClubRanks.previous(book, 2))
        // A season's line is replaced when recorded again; 30 seasons are kept.
        ClubRanks.record(book, 2, ::value)
        assertEquals(1, book.rankLines.size)
        for (s in 3..40) ClubRanks.record(book, s, ::value)
        assertEquals((11..40).toList(), book.rankLines.map { it.season })
        // It round-trips, and the news names the top clubs.
        assertEquals(book.rankLines, ClubBook.decode(book.encode()).rankLines)
        val ev = ClubRanks.events(book.rankLines.last()) { ClubEconomy.clubValue(book, it, ::value) }.single()
        assertEquals("club_ranking", ev.kind)
        assertEquals(listOf(book.rankLines.last().value.first(), book.rankLines.last().results.first()), ev.keys)
        assertEquals(ClubEconomy.clubValue(book, ev.keys.first(), ::value).toString(), ev.args.single())
        assertTrue(ClubRanks.events(null) { 0 }.isEmpty())
        assertTrue(ClubRanks.events(RankLine(1, emptyList(), emptyList(), emptyMap())) { 0 }.isEmpty())
    }
}

package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.LeaderStat
import yacht.engine.league.Leaders
import yacht.engine.league.Terms
import yacht.engine.league.Valuation
import yacht.engine.league.valuer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The club world's leaderboards (design ENG 1b.3, 1b.9; UX 7.5): 💰 몸값 ranks players by their
 * market value ([Valuation.value] through [ClubBook.valuer]), 🌱 유스 출신 the academy graduates
 * playing for a club by rating; neither comes from the stats, and neither has a career board.
 */
class LeadersClubTest {
    private val ratings = mapOf("a1" to 1610.0, "a2" to 1480.0, "a3" to 1555.0, "b1" to 1700.0, "b2" to 1480.0, "b3" to Double.NaN)

    private fun book(): ClubBook {
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a1", "a2", "a3")), Eco.Spec("c02", "KR", listOf("b1", "b2", "b3"), city = 1))))
        val w = book.world!!
        fun terms(key: String, academy: String?, grad: Int) {
            w.terms[key] = Terms(5, 10, 0, 0, 1, 0, null, academy, grad)
        }
        terms("a1", "c01", 2)
        terms("a2", null, 0)
        terms("a3", "c02", 3)
        terms("b1", null, 0)
        terms("b2", "c02", 4)
        terms("b3", "c02", 4)
        // Terms of a player who left every club: the book does not keep them.
        terms("gone", "c01", 1)
        return book
    }

    private val rating: (String) -> Double = { ratings[it] ?: Double.NaN }

    @Test
    fun theValueBoardRanksByMarketValue() {
        val book = book()
        val ages = mapOf("a1" to 24, "a2" to 19, "a3" to 31, "b1" to 28, "b2" to 23)
        val value = book.valuer(rating, { ages[it] }, { 80 }, { 70 })
        val keys = book.clubs.flatMap { it.squad }
        val board = Leaders.club(LeaderStat.VALUE, keys + keys.take(2), book, rating, value)
        // Every rated player once, the most valuable first; the extra is the rating.
        assertEquals(5, board.size)
        assertEquals(board.sortedWith(compareByDescending<yacht.engine.league.LeaderEntry> { it.value }.thenBy { it.key }), board)
        for (e in board) {
            assertEquals(Valuation.value(rating(e.key), book.world!!.anchor, ages[e.key], 80, 70).toDouble(), e.value, e.key)
            assertEquals(rating(e.key), e.extra)
        }
        assertEquals("b1", board.first().key)
        // a2 and b2 share a rating: the younger is worth more; equal values go by key.
        assertTrue(value("a2") > value("b2"))
        assertTrue(board.indexOfFirst { it.key == "a2" } < board.indexOfFirst { it.key == "b2" })
        val level = Leaders.club(LeaderStat.VALUE, listOf("b2", "a2"), book, rating) { 50 }
        assertEquals(listOf("a2", "b2"), level.map { it.key })
    }

    @Test
    fun theGraduateBoardListsAcademyGraduatesByRating() {
        val book = book()
        val keys = book.clubs.flatMap { it.squad } + "gone"
        val board = Leaders.club(LeaderStat.GRADUATE, keys, book, rating) { 0 }
        // Graduates of any academy playing for a club, the best rated first; the extra is the season they came out.
        assertEquals(listOf("a1", "a3", "b2"), board.map { it.key })
        assertEquals(listOf(1610.0, 1555.0, 1480.0), board.map { it.value })
        assertEquals(listOf(2.0, 3.0, 4.0), board.map { it.extra })
        // Without a club world there are no graduates.
        val plain = Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a1", "a2"))))
        assertTrue(Leaders.club(LeaderStat.GRADUATE, listOf("a1", "a2"), plain, rating) { 0 }.isEmpty())
    }

    @Test
    fun theClubBoardsAreNoStatsBoards() {
        assertEquals(listOf(LeaderStat.VALUE, LeaderStat.GRADUATE), LeaderStat.entries.filter { it.club })
        assertEquals("몸값", LeaderStat.VALUE.label)
        assertEquals("유스 출신", LeaderStat.GRADUATE.label)
        for (stat in listOf(LeaderStat.VALUE, LeaderStat.GRADUATE)) {
            assertTrue(!stat.career && stat.rule.endsWith("요."))
            assertTrue(Leaders.career(stat, yacht.engine.league.StatsBook(), listOf("a1")).isEmpty())
        }
        // A stats board asked of the club boards' function is empty.
        val book = book()
        assertTrue(Leaders.club(LeaderStat.SCORER, listOf("a1"), book, rating) { 1 }.isEmpty())
    }
}

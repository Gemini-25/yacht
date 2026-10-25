package yacht.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelCoverageTest {
    private val tables get() = SolverTest.tables
    private val rules get() = tables.rules

    private fun board(vararg boxes: Pair<Category, Int>) = boxes.fold(Board(rules)) { b, (c, p) -> b.with(c, p) }

    /**
     * A table rooted at the opponent's turn, then a card with the same boxes but another upper
     * sum (a box rewritten after an undo): the table must say it does not cover it, instead of
     * answering from rows it never built (this closed the app mid-game).
     */
    @Test
    fun aRewrittenUpperBoxIsNotInTheTable() {
        // Upper boxes 1-5 written for 45, Sixes open: the bonus still depends on the upper sum.
        val filled: List<Pair<Category, Int>> = listOf(
            Category.ACES to 3, Category.DEUCES to 6, Category.THREES to 9, Category.FOURS to 12, Category.FIVES to 15,
            Category.FOUR_OF_A_KIND to 20, Category.FULL_HOUSE to 25, Category.SMALL_STRAIGHT to 15,
            Category.LARGE_STRAIGHT to 30,
        )
        val me = board(*filled.toTypedArray())
        val opp = board(*filled.dropLast(1).toTypedArray())
        // Root: the opponent to move (4 boxes left against my 3).
        val table = Advisor(tables).buildDuelTable(me, opp, meToMove = false)
        val opp1 = opp.with(Category.LARGE_STRAIGHT, 30)
        // My turn as the table played it.
        assertNotNull(table.after(me, opp1))
        assertTrue(table.contains(me, opp1))
        // Same boxes, Fives written as 20 instead of 15.
        val rewritten = board(*filled.map { if (it.first == Category.FIVES) Category.FIVES to 20 else it }.toTypedArray())
        assertEquals(me.mask, rewritten.mask)
        assertNull(table.after(rewritten, opp1))
        assertFalse(table.contains(rewritten, opp1))
        // The planner falls back to its own search for it.
        val plan = Advisor(tables).planTurn(rewritten, opp1, Mode.WIN, table = table)
        assertFalse(plan.duel)
    }

    @Test
    fun tableMemoryIsEstimatedBeforeBuilding() {
        val me = board(Category.ACES to 3, Category.DEUCES to 6, Category.THREES to 9, Category.FOURS to 12, Category.FIVES to 15, Category.CHOICE to 22)
        val opp = board(Category.ACES to 2, Category.DEUCES to 8, Category.THREES to 6, Category.FOURS to 16, Category.FIVES to 10, Category.CHOICE to 20)
        val estimate = DuelSearch.estimateBytes(tables, me, opp, ties = true)
        val table = Advisor(tables).buildDuelTable(me, opp, meToMove = true, ties = true)
        assertEquals(table.bytes, estimate)
        val cap = DuelSearch.maxBytes
        try {
            DuelSearch.maxBytes = estimate - 1
            assertFalse(DuelSearch.fitsMemory(tables, me, opp, ties = true))
        } finally {
            DuelSearch.maxBytes = cap
        }
    }
}

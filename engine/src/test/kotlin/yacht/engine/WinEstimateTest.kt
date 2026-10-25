package yacht.engine

import yacht.engine.league.WinEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinEstimateTest {
    private val tables get() = SolverTest.tables
    private val rules get() = tables.rules

    private fun board(vararg boxes: Pair<Category, Int>) = boxes.fold(Board(rules)) { b, (c, p) -> b.with(c, p) }

    @Test
    fun equalPositionsAreEven() {
        val w = WinEstimate(tables)
        assertEquals(0.5, w.pWin(Board(rules), Board(rules)), 1e-9)
        val b = board(Category.CHOICE to 22, Category.SIXES to 18)
        assertEquals(0.5, w.pWin(b, b), 1e-9)
    }

    @Test
    fun aLeadHelpsAndProbabilitiesAddUp() {
        val w = WinEstimate(tables)
        val ahead = board(Category.CHOICE to 28, Category.YACHT to 50)
        val behind = board(Category.CHOICE to 15, Category.YACHT to 0)
        val p = w.pWin(ahead, behind)
        assertTrue(p > 0.8, "a Yacht ahead: $p")
        assertEquals(1.0, p + w.pWin(behind, ahead), 1e-9)
    }

    @Test
    fun finishedGamesAreCertain() {
        val w = WinEstimate(tables)
        val full = Category.ALL.fold(Board(rules)) { b, c -> b.with(c, 10) }
        val less = Category.ALL.fold(Board(rules)) { b, c -> b.with(c, if (c == Category.YACHT) 0 else 10) }
        assertEquals(1.0, w.pWin(full, less), 1e-12)
        assertEquals(0.5, w.pWin(full, full), 1e-12)
        // One side finished: the other's rest decides (only a Yacht beats 120 from 110).
        val open = Category.ALL.dropLast(1).fold(Board(rules)) { b, c -> b.with(c, 10) }
        val q = w.pWin(open, full)
        assertTrue(q in 0.02..0.1, "one turn for a Yacht: $q")
    }
}

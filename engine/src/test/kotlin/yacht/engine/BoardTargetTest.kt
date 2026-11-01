package yacht.engine

import yacht.engine.league.CoachMarket
import yacht.engine.league.Owner
import yacht.engine.league.OwnerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The board's target (design ENG 2.2-2.3): clamped to the table, a title resets the pressure, a bigger miss weighs more. */
class BoardTargetTest {
    @Test
    fun theTargetIsClampedToTheTable() {
        // T = clamp(E − 0.5 × (ambition − 3), 1, C).
        assertEquals(1.0, CoachMarket.target(1, 5, 8))
        assertEquals(1.0, CoachMarket.target(2, 5, 8))
        assertEquals(8.0, CoachMarket.target(8, 1, 8))
        assertEquals(2.5, CoachMarket.target(3, 4, 8))
        assertEquals(3.5, CoachMarket.target(3, 2, 8))
        assertEquals(3.0, CoachMarket.target(3, 3, 8))
        assertEquals(1.0, CoachMarket.target(1, 3, 0))
        // Shown with a half place to the better one, so the shown target is met exactly when the gap is not negative.
        assertEquals(2, CoachMarket.shown(2.5))
        assertEquals(3, CoachMarket.shown(3.0))
        for (t in listOf(1.0, 1.5, 2.0, 2.5, 3.5, 7.5)) for (place in 1..8) {
            assertEquals(place <= CoachMarket.shown(t), CoachMarket.gap(t, place, 8) >= 0, "target $t place $place")
        }
        // On a season: the favourite's expected place is 1, so an ambitious owner asks for 1 and never for less.
        val lg = Coaching.league(6)
        val plan = lg.plan(1)
        assertEquals(lg.ids, CoachMarket.expected(plan, lg.book, "KR", lg::rating).entries.sortedBy { it.value }.map { it.key })
        lg.book.world!!.economy.owners["c01"] = Owner(OwnerType.TYCOON, "회장", 5, 1, 0, 0)
        lg.book.world!!.economy.owners["c06"] = Owner(OwnerType.FANS, "팬", 1, 5, 0, 0)
        val targets = CoachMarket.targets(plan, lg.book, lg::rating)
        assertEquals(1, targets["c01"])
        assertEquals(6, targets["c06"])
        assertEquals(3, targets["c03"])
    }

    @Test
    fun aFavouriteChampionWithAnAmbitiousOwnerEndsEverySeasonAtZero() {
        val lg = Coaching.league(4)
        lg.book.world!!.economy.owners["c01"] = Owner(OwnerType.TYCOON, "회장", 5, 1, 0, 0)
        for (c in lg.ids.withIndex()) Coaching.coach(lg.book, "k${c.index + 1}", c.value, from = 0, until = 99)
        val st = lg.book.world!!.state("c01")
        for (s in 1..10) {
            val plan = lg.plan(s)
            // Second at the winter checkpoint: a little pressure (the target is 1), not enough to go.
            val winter = CoachMarket.review(lg.book, plan, lg.table("c02", "c01", "c03", "c04"), lg::rating, winter = true)
            assertTrue(winter.none { it.club == "c01" })
            assertEquals(0.5, st.pressure)
            // Champion at the close: back to 0.
            val close = CoachMarket.review(lg.book, plan, lg.table("c01", "c02", "c03", "c04"), lg::rating, winter = false)
            assertTrue(close.none { it.club == "c01" })
            assertEquals(0.0, st.pressure, "season $s")
            assertEquals("k1", lg.book.coachOf("c01")!!.id)
        }
    }

    @Test
    fun aThreePlaceMissWeighsThreeTimesAOnePlaceMiss() {
        val lg = Coaching.league(8)
        for (c in lg.ids.withIndex()) {
            lg.book.world!!.economy.owners[c.value] = Owner(OwnerType.LOCAL, "기업", 3, 2, 0, 0)
            Coaching.coach(lg.book, "k${c.index + 1}", c.value, from = 0, until = 99)
        }
        val w = lg.book.world!!
        // Expected places 1..8 by the ratings; c02 ends third (one short), c03 sixth (three short).
        val order = listOf("c01", "c04", "c02", "c05", "c07", "c03", "c06", "c08")
        val first = CoachMarket.review(lg.book, lg.plan(1), lg.table(*order.toTypedArray()), lg::rating, winter = true)
        assertTrue(first.isEmpty())
        // 2 × 1/8 and 2 × 3/8, held in tenths; better than asked: none.
        assertEquals(0.3, w.state("c02").pressure)
        assertEquals(0.8, w.state("c03").pressure)
        assertEquals(0.0, w.state("c04").pressure)
        assertEquals(0.0, w.state("c08").pressure)
        // Threshold 2.0 (patience 2): the three-place miss goes at its third checkpoint, the one-place miss holds on longer.
        var sackedC03 = 0
        var sackedC02 = 0
        for (i in 2..12) {
            val out = CoachMarket.review(lg.book, lg.plan(i), lg.table(*order.toTypedArray()), lg::rating, winter = i % 2 == 1)
            if (out.any { it.club == "c03" } && sackedC03 == 0) sackedC03 = i
            if (out.any { it.club == "c02" } && sackedC02 == 0) sackedC02 = i
            for (m in out) assertEquals(order.indexOf(m.club) + 1, m.place)
        }
        assertEquals(3, sackedC03)
        assertTrue(sackedC02 > sackedC03 + 2, "c02 at $sackedC02")
        assertEquals(2.0, CoachMarket.threshold(2))
    }
}

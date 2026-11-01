package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The club world's off-season and checkpoint are transactional (design ENG 1b.10): an exception at
 * any step leaves the book as it was and tells no news, and the next attempt runs whole.
 */
class OffseasonRollbackTest {
    @Test
    fun anExceptionInTheMarketLeavesTheBookAsItWasAndTheNextAttemptRunsWhole() {
        val (l, plan) = Cw.migrated()
        val book = l.book
        Cw.play(plan)
        l.close(plan)
        val next = l.next(plan.number)
        val input = l.input(next, plan)
        val before = book.encode()
        // Step 8 (the domestic market) has moved members and money when it fails.
        book.fault = { n -> if (n == 8) error("injected in step 8") }
        assertEquals(emptyList(), book.offseason(plan, input))
        assertEquals(before, book.encode())
        assertEquals(before, ClubBook.decode(book.encode()).encode())
        book.fault = null
        val events = book.offseason(plan, input)
        val clean = ClubBook.decode(before)
        assertEquals(clean.offseason(plan, input), events)
        assertEquals(clean.encode(), book.encode())
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun aFailureAtAnyStepRollsBack() {
        val (l, plan) = Cw.migrated(size = 300)
        Cw.play(plan)
        l.close(plan)
        val input = l.input(l.next(plan.number), plan)
        val before = l.book.encode()
        for (step in 0..14) {
            val book = ClubBook.decode(before)
            book.fault = { n -> if (n == step) error("injected in step $n") }
            assertEquals(emptyList(), book.offseason(plan, input), "step $step")
            assertEquals(before, book.encode(), "step $step")
        }
    }

    @Test
    fun theFrozenOffseasonRollsBackToo() {
        val (l, plan) = Cw.migrated(size = 300)
        Cw.play(plan)
        l.close(plan)
        val next = l.next(plan.number)
        val off = Cw.config.copy(clubWorld = false)
        val before = l.book.encode()
        // Today's off-season reads the ages: one that throws fails it in the middle.
        val good = l.input(next, plan, off)
        val bad = yacht.engine.league.ClubInput(next, Cw::rating, Cw::nation, off, l.honours.toList(), age = { error("no age") }, ranking = good.ranking)
        assertEquals(emptyList(), l.book.offseason(plan, bad))
        assertEquals(before, l.book.encode())
        assertTrue(l.book.offseason(plan, good).isNotEmpty())
    }

    @Test
    fun aFailingCheckpointLeavesTheBookAsItWas() {
        val (l, plan) = Cw.migrated(size = 300)
        val book = l.book
        val before = book.encode()
        val club = book.clubs.first().id
        book.fault = { n ->
            if (n == ClubBook.CHECKPOINT) {
                book.world!!.terms.clear()
                error("injected after the checkpoint")
            }
        }
        assertEquals(emptyList(), book.checkpoint(plan, l.input(l.members, plan), ClubWorld.winterWeek(plan)))
        book.fault = null
        assertEquals(before, book.encode())
        assertTrue(book.terms(book.members(club).first()) != null)
    }
}

package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubLeague
import yacht.engine.league.ClubLegend
import yacht.engine.league.ClubMoveType
import yacht.engine.league.ClubRecord
import yacht.engine.league.ClubRecordKind
import yacht.engine.league.ClubStart
import yacht.engine.league.ClubWorld
import yacht.engine.league.ClubWorldMigrate
import yacht.engine.league.CoachMarket
import yacht.engine.league.Comp
import yacht.engine.league.Derbies
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.Hometowns
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Market
import yacht.engine.league.Offseason
import yacht.engine.league.OwnerType
import yacht.engine.league.Rival
import yacht.engine.league.TieRule
import yacht.engine.league.wageBill
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a club world starts (design ENG 8.2): the migration of today's books, the founding of a new league's, clubs founded later. */
class ClubWorldMigrationTest {
    /** A `clubs 2` book without a club world, migrated in the season after [v2] seasons: every rule of ENG 8.2. */
    private fun migrates(size: Int) {
        val l = Cw.league(size, v2 = 2)
        val book = l.book
        val before = book.encode()
        val kept = book.history
        val digest = book.digest!!
        val old = book.clubs.associate { it.id to it.budget }
        val plan = Cw.fresh(l.members, 3)
        val input = l.input(l.members, plan)
        assertNull(book.world)
        assertTrue(ClubWorld.migrate(book, input, ClubStart(3, plan)))
        val w = book.world!!
        val clubs = book.clubs
        // Kept moves' fees × 0.6 (old units into money); a draft's pick number is no fee.
        assertEquals(kept.size, book.history.size)
        for ((a, b) in kept.zip(book.history)) {
            val fee = a.amount > 0 && (a.type == ClubMoveType.TRANSFER || a.type == ClubMoveType.ABROAD)
            assertEquals(if (fee) Math.round(a.amount * 0.6).toInt() else a.amount, b.amount, a.toString())
        }
        assertEquals(digest.biggest?.let { Math.round(it.amount * 0.6).toInt() }, book.digest!!.biggest?.amount)
        // Every club's money: rank-based balances (by the old budget within its nation), at most 1.5 × its estimated income.
        for ((nation, cs) in clubs.groupBy { it.nation }) {
            val ratio = cs.sortedWith(compareByDescending<yacht.engine.league.Club> { old.getValue(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER)).map { c ->
                val life = book.life(c.id)!!
                val income = life.history.single().income
                assertTrue(income > 0, c.id)
                assertTrue(life.balance <= Math.round(1.5 * income) + 1, "${c.id}: ${life.balance} of $income")
                assertTrue(life.balance >= Math.round(0.2 * income) - 1, "${c.id}: ${life.balance} of $income")
                life.balance.toDouble() / income
            }
            for (i in 1 until ratio.size) assertTrue(ratio[i] <= ratio[i - 1] + 0.02, "$nation: $ratio")
        }
        // Wages: each club's at most 0.6 × its income (a least wage of 0.1억 a member), each league's at most 0.62 × its revenue.
        val leagueOf = clubs.associate { c -> c.id to (c.squad.firstNotNullOfOrNull { plan.league.leagueOf(it)?.id } ?: c.nation) }
        for (c in clubs) {
            val income = book.life(c.id)!!.history.single().income
            assertTrue(book.wageBill(c.id) <= Math.ceil(0.6 * income).toInt() + c.squad.size, "${c.id}: ${book.wageBill(c.id)} of $income")
        }
        for ((lg, cs) in clubs.groupBy { leagueOf.getValue(it.id) }) {
            val revenue = cs.sumOf { book.life(it.id)!!.history.single().income }
            val bill = cs.sumOf { book.wageBill(it.id) }
            assertTrue(bill <= Math.ceil(0.62 * revenue) + cs.sumOf { it.squad.size }, "$lg: $bill of $revenue")
        }
        // One terms entry per member, contracts to seasons 4..6 (staggered), unique shirt numbers per club.
        val members = clubs.flatMap { it.squad }
        assertTrue(members.all { book.terms(it) != null })
        assertEquals(setOf(4, 5, 6), members.map { book.terms(it)!!.until }.toSet())
        for (c in clubs) assertEquals(c.squad.size, c.squad.map { book.terms(it)!!.number }.toSet().size, c.id)
        // Sponsor deals staggered, owners by hash, identities, a captain per club.
        assertTrue(clubs.mapNotNull { book.life(it.id)!!.sponsor?.until }.toSet().size >= 2)
        assertTrue(clubs.map { book.life(it.id)!!.owner.type }.toSet().size >= 3)
        assertTrue(clubs.none { it.id in w.clubs.keys && book.life(it.id)!!.owner.type == OwnerType.STATE })
        assertTrue(clubs.all { book.life(it.id)!!.foundedYear in 1880..2010 && book.life(it.id)!!.nickname.isNotEmpty() })
        assertTrue(clubs.all { book.life(it.id)!!.captain in it.squad })
        // One coach per club, the free pool at its floor or above.
        assertTrue(clubs.all { book.coachOf(it.id)?.club == it.id })
        assertEquals(clubs.size, clubs.mapNotNull { book.coachOf(it.id)?.id }.toSet().size)
        assertTrue(book.freeCoaches.size >= CoachMarket.poolSize(clubs.size), "${book.freeCoaches.size} free of ${clubs.size}")
        // Rivals: every nation with two clubs has its classic; the series start at zero in the season in play.
        for ((nation, cs) in clubs.groupBy { it.nation }) if (cs.size >= 2) {
            assertTrue(cs.any { c -> book.rivals(c.id).any { it.kind == Derbies.CLASSIC } }, nation)
        }
        for (c in clubs) for (r in book.rivals(c.id)) {
            val d = book.derby(c.id, r.club)!!
            assertEquals(0, d.games)
            assertEquals(3, d.since)
        }
        // Records from the kept moves: the world record and the best-ranked leagues' records (an `rk` line before the season).
        val fees = book.history.filter { it.amount > 0 && (it.type == ClubMoveType.TRANSFER || it.type == ClubMoveType.ABROAD) }
        if (fees.isNotEmpty()) {
            assertEquals(fees.maxOf { it.amount }, book.worldRecord!!.fee)
            val rk = book.rankLines.single()
            assertEquals(2, rk.season)
            assertTrue(rk.leagueRecords.isNotEmpty() && rk.leagueRecords.values.all { it <= book.worldRecord!!.fee })
            assertTrue(rk.leagueRecords.keys.all { input.ranking.rank(it) < Market.RECORD_LEAGUES })
        }
        // The season in play: its checkpoint to come (week 0), the market's tick from now on.
        assertEquals(0, w.checkpoint)
        assertEquals(3 to 0, w.marketSeason to w.marketWeek)
        // Members' values are anchored at the league's median rating.
        val rs = l.members.map(Cw::rating).sorted()
        assertEquals(LineAnchor.of(rs), w.anchor)
        // The book with its club world reads back as it is; migrating twice (a second load before the first save) gives the same.
        val text = book.encode()
        assertEquals(text, ClubBook.decode(text).encode())
        assertFalse(ClubWorld.migrate(book, input, ClubStart(3, plan)))
        assertEquals(text, book.encode())
        val again = ClubBook.decode(before)
        assertTrue(ClubWorld.migrate(again, input, ClubStart(3, plan)))
        assertEquals(text, again.encode())
    }

    /** The anchor as the `cw` line holds it: the median, to the tenth. */
    private object LineAnchor {
        fun of(sorted: List<Double>): Double {
            val m = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            return Math.round(m * 10) / 10.0
        }
    }

    @Test
    fun aNationalBookMigratesAsTheDesignSays() = migrates(400)

    @Test
    fun aProbeShapedBookMigratesToo() = migrates(1000)

    @Test
    fun aSeasonLoadedAfterItsWinterWeekSkipsItsCheckpoint() {
        val l = Cw.league(300, v2 = 1)
        val plan = Cw.fresh(l.members, 2)
        val week = ClubWorld.winterWeek(plan)
        assertEquals(plan.leagueWeeks / 2 + 1, week)
        val before = ClubBook.decode(l.book.encode())
        assertTrue(ClubWorld.migrate(l.book, l.input(l.members, plan), ClubStart(2, plan, week)))
        assertEquals(2, l.book.world!!.checkpoint)
        assertEquals(2 to week, l.book.world!!.marketSeason to l.book.world!!.marketWeek)
        assertTrue(ClubWorld.migrate(before, l.input(l.members, plan), ClubStart(2, plan, week - 1)))
        assertEquals(0, before.world!!.checkpoint)
    }

    @Test
    fun aNewLeagueFoundsItsClubWorldAtLambdaOne() {
        val members = Cw.keys.take(300)
        val book = ClubBook.found(members, Cw::nation, Cw::rating, 0, 9L, Cw.config)
        val plan = Cw.fresh(members, 1)
        val l = Cw.League(book, members, 300)
        assertTrue(ClubWorld.found(book, l.input(members, plan), ClubStart(1, plan)))
        for (c in book.clubs) {
            val life = book.life(c.id)!!
            assertEquals(ClubEconomy.FOUND_BALANCE, life.balance, c.id)
            assertEquals(2 to 2, life.training to life.academy)
            assertEquals(Math.round(ClubEconomy.fanBase(1.0, ClubEconomy.cityIndex(c))).toInt(), life.fans, c.id)
            assertNotNull(book.coachOf(c.id))
            assertTrue(c.squad.all { book.terms(it)?.until in 2..4 })
        }
        assertTrue(book.history.isEmpty() && book.worldRecord == null && book.rankLines.isEmpty())
        assertFalse(ClubWorld.found(book, l.input(members, plan), ClubStart(1, plan)))
        // Its first off-season runs the club world's.
        Cw.play(plan)
        l.close(plan)
        val next = l.next(1)
        val events = book.offseason(plan, l.input(next, plan))
        assertTrue(events.any { it.kind == "club_accounts" })
        Cw.assertWhole(book, next)
    }

    @Test
    fun aClubLeaguesBookConvertsThenMigrates() {
        val keys = Cw.keys.take(300)
        val v1 = ClubBook.create(keys.sortedByDescending(Cw::rating), 12, 6, 12L, Hometowns.ALL)
        val ids = v1.clubs.map { it.id }
        v1.offseason(1, ids.reversed(), listOf(Honour(1, "CLUB", ids[3])), Cw::rating, keys.toSet(), 12, 6, 5L)
        val legacy = LeagueConfig()
        val plan = Offseason.first(8L, Offseason.Context(keys, Cw::rating, { null }, { "서울" }, legacy, listOf(ClubLeague.factory({ v1 })), nation = Cw::nation), 2)
        assertNotNull(plan.competition(Comp.CLUB))
        val rng = Random(1)
        while (true) {
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(
                when (f.kind) {
                    JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, List(2) { if (rng.nextBoolean()) GameScore(200, 150) else GameScore(150, 200) }, emptyList()) }
                        .let { JobResult(f, it, TieRule.team(it), 0) }
                    else -> List(2) { if (Cw.rating(f.a) >= Cw.rating(f.b)) GameScore(200, 150) else GameScore(150, 200) }
                        .let { JobResult(f, listOf(BoardResult(f.a, f.b, it, emptyList())), TieRule.match(it), 0) }
                },
            )
        }
        plan.startPost(emptyList())
        val next = keys.drop(4)
        val input = ClubInput(next, Cw::rating, Cw::nation, Cw.config)
        val converted = v1.offseason(plan, input)
        assertTrue(converted.any { it.kind == "club_reform" }, converted.toString())
        assertEquals(ClubBook.AFFILIATION, v1.format)
        assertNull(v1.world)
        val kept = v1.history.filter { it.amount > 0 && it.type == ClubMoveType.TRANSFER }
        assertTrue(kept.isNotEmpty())
        // Right after the conversion, inside the same off-season: the club world starts in the first national season.
        assertTrue(ClubWorld.migrate(v1, input, ClubStart(3)))
        Cw.assertWhole(v1, next)
        assertTrue(next.all { v1.terms(it) != null })
        assertTrue(v1.clubs.all { v1.coachOf(it.id) != null && v1.life(it.id) != null })
        assertTrue(v1.clubs.all { v1.life(it.id)!!.history.single().season == 2 })
        // The club league's fees became money too.
        val after = v1.history.filter { it.amount > 0 && it.type == ClubMoveType.TRANSFER }
        assertEquals(kept.map { Math.round(it.amount * 0.6).toInt() }, after.map { it.amount })
        val text = v1.encode()
        assertEquals(text, ClubBook.decode(text).encode())
        // That off-season asked again (a rollover replayed after a kill) tells the conversion's news as it did and changes nothing.
        assertEquals(converted, v1.offseason(plan, input))
        assertEquals(text, v1.encode())
        assertEquals(converted, ClubBook.decode(text).offseason(plan, input))
    }

    /**
     * The start is all or nothing (ENG 1b.10's rollback, for the load that migrates): a failure leaves
     * the book without a club world and its fees in their old units, and the next attempt migrates it
     * as a clean one does.
     */
    @Test
    fun aFailedMigrationLeavesTheBookAsItWas() {
        val l = Cw.league(300, v2 = 2)
        val book = l.book
        val before = book.encode()
        val plan = Cw.fresh(l.members, 3)
        val input = l.input(l.members, plan)
        book.fault = { n -> if (n == ClubBook.MIGRATION) error("injected after the migration") }
        assertFalse(ClubWorld.migrate(book, input, ClubStart(3, plan)))
        assertNull(book.world)
        assertEquals(before, book.encode())
        book.fault = null
        assertTrue(ClubWorld.migrate(book, input, ClubStart(3, plan)))
        val clean = ClubBook.decode(before)
        assertTrue(ClubWorld.migrate(clean, input, ClubStart(3, plan)))
        assertEquals(clean.encode(), book.encode())
        // A new league's founding too.
        val fresh = ClubBook.found(Cw.keys.take(200), Cw::nation, Cw::rating, 0, 9L, Cw.config)
        val founded = fresh.encode()
        val first = Cw.fresh(Cw.keys.take(200), 1)
        fresh.fault = { n -> if (n == ClubBook.MIGRATION) error("injected after the founding") }
        assertFalse(ClubWorld.found(fresh, Cw.League(fresh, Cw.keys.take(200), 200).input(Cw.keys.take(200), first), ClubStart(1, first)))
        assertEquals(founded, fresh.encode())
    }

    @Test
    fun aClubFoundedLaterGetsTheDefaultsOfTheDesign() {
        val (l, plan) = Cw.migrated()
        val book = l.book
        Cw.play(plan)
        l.close(plan)
        // A nation of one club grows to 16 members: the count rule founds a second club.
        val nation = book.clubs.groupBy { it.nation }.entries.first { it.value.size == 1 && it.value.single().squad.size <= 10 }.key
        val stay = l.next(3)
        val more = Cw.keys.drop(l.nextRookie).filter { Cw.nation(it) == nation }.take(16 - stay.count { Cw.nation(it) == nation })
        book.offseason(plan, l.input(stay + more, plan))
        val clubs = book.clubsOf(nation)
        val other = clubs.single { it.founded != 4 }
        val founded = clubs.single { it.founded == 4 }
        val life = book.life(founded.id)!!
        assertEquals(1 to 1, life.training to life.academy)
        assertEquals(ClubEconomy.MIN_SEATS, life.stadium.capacity)
        assertTrue(life.owner.type == OwnerType.LOCAL || life.owner.type == OwnerType.FANS)
        assertNotNull(life.sponsor)
        assertEquals(2025 + 4, life.foundedYear)
        assertEquals(Math.round(0.5 * ClubEconomy.income(book, other.id)).toInt(), life.balance)
        assertNotNull(book.coachOf(founded.id))
        assertTrue(founded.squad.isNotEmpty() && founded.squad.all { book.terms(it) != null })
        // Its hire is one of the coach market's moves of that off-season.
        assertTrue(CoachMarket.report(book, 3).moves.any { it.club == founded.id && it.kind == 'H' })
    }

    @Test
    fun aReusedIdStartsWithoutTheOldClubsLines() {
        val (l, plan) = Cw.migrated()
        val book = l.book
        val w = book.world!!
        val c = book.clubs.first { book.rivals(it.id).isNotEmpty() }
        val h = w.history
        h.legends[c.id] = listOf(ClubLegend("old", 150, 12, 3, true, 1, 7))
        h.records[c.id] = listOf(ClubRecord(ClubRecordKind.APPS, "old", 400, 1))
        val rival = book.rivals(c.id).first().club
        w.clubs.getValue(c.id).balance = -999
        // The id given to a new club (ENG 1b.7): its history, its rivals' links to it and its money go first.
        ClubWorldMigrate.founded(book, c.id, plan, l.input(l.members, plan))
        assertTrue(h.legends[c.id].isNullOrEmpty() && h.records[c.id].isNullOrEmpty())
        assertTrue(book.rivals(c.id).isEmpty() && book.rivals(rival).none { it.club == c.id } && book.derby(c.id, rival) == null)
        val life = book.life(c.id)!!
        assertTrue(life.balance >= 0)
        assertEquals(1 to 1, life.training to life.academy)
        assertEquals(emptyList<Rival>(), life.rivals)
    }
}

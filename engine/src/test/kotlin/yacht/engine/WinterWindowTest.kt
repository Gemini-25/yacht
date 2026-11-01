package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubCheckpoint
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubTable
import yacht.engine.league.ClubWorld
import yacht.engine.league.CoachMarket
import yacht.engine.league.LeagueEvent
import yacht.engine.league.Market
import yacht.engine.league.MarketPhase
import yacht.engine.league.MarketReport
import yacht.engine.league.MarketStory
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.Stories
import yacht.engine.league.StoryStage
import yacht.engine.league.WinterReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The winter checkpoint (design ENG 3.3, [ClubCheckpoint]): once a season after the tick of week W,
 * on the results through W and the drawn ratings only; the fans' mood, the board's sackings with an
 * immediate hire, the domestic window under its caps with a `ws` line per move (its points split in
 * the club table, its wages shared), the news within the winter week's budget, a repeat and a
 * read-back that tell the same, a season begun after its week W that skips it, the rollback.
 */
class WinterWindowTest {
    /** [l]'s season [plan] played and closed through its winter week; the checkpoint's news. */
    private fun throughWinter(l: Cw.League, plan: SeasonPlan, input: yacht.engine.league.ClubInput = Ms.input(l, plan)): List<LeagueEvent> {
        val w = ClubWorld.winterWeek(plan)
        Ms.season(l, plan, input, last = w - 1)
        Ms.playThrough(plan, w)
        return Stories.close(l.book, plan, input, w, w + 1)
    }

    @Test
    fun itRunsOnceAfterTheTickOfItsWeek() {
        val (l, plan) = Ms.league()
        val book = l.book
        val s = plan.number
        val w = ClubWorld.winterWeek(plan)
        val input = Ms.input(l, plan)
        Ms.season(l, plan, input, last = w - 1)
        assertTrue(book.world!!.checkpoint < s)
        assertTrue(book.winterMoves(s).isEmpty())
        assertTrue(book.stories(s).any { it.winter && it.stage == StoryStage.RUMOUR }, "the winter's rumours")
        Ms.playThrough(plan, w)
        // Only the winter week's close brings it.
        val before = book.encode()
        assertEquals(emptyList(), book.checkpoint(plan, input, w - 1))
        assertEquals(emptyList(), book.checkpoint(plan, input, w + 1))
        assertEquals(before, book.encode())
        var hooked = 0
        val events = Stories.close(book, plan, input, w, w + 1) { hooked++; listOf(LeagueEvent("arc_winter", s)) }
        assertEquals(1, hooked)
        assertEquals("arc_winter", events.last().kind, "the world events' winter stages come after the checkpoint")
        assertEquals(s, book.world!!.checkpoint)
        val news = events.dropLast(1)
        assertTrue(news.isNotEmpty() && news.size <= WinterReport.MAX_NEWS, news.toString())
        assertTrue(news.all { it.season == s && it.week == w })
        assertEquals("winter_window", news.first().kind)
        val after = book.encode()
        // Asked again, or read back, it tells the same and changes nothing.
        assertEquals(news, book.checkpoint(plan, input, w))
        assertEquals(after, book.encode())
        val copy = Ms.copy(book)
        assertEquals(after, copy.encode(), "the book is as its file reads")
        assertEquals(ClubCheckpoint.report(book, s), ClubCheckpoint.report(copy, s))
        assertEquals(news, copy.checkpoint(plan, input, w))
        assertEquals(after, copy.encode())
        // Later weeks do not run it again.
        Ms.playThrough(plan, w + 3)
        Stories.close(book, plan, input, w + 1, w + 3)
        assertEquals(1, book.winterMoves(s).map { it.week }.toSet().size)
    }

    @Test
    fun onlyTheResultsThroughItsWeekCount() {
        // One league plays a few of the next week's fixtures before the winter week closes, with the ladder moved on.
        val (a, pa) = Ms.league()
        val (b, pb) = Ms.league()
        val w = ClubWorld.winterWeek(pa)
        val ia = Ms.input(a, pa, live = Ms::moved)
        val ib = Ms.input(b, pb)
        Ms.season(a, pa, ia, last = w - 1)
        Ms.season(b, pb, ib, last = w - 1)
        Ms.playThrough(pa, w)
        Ms.playEarly(pa, w + 1, n = 60)
        Ms.playThrough(pb, w)
        assertNotEquals(pa.league.standings.values.sumOf { it.played }, pb.league.standings.values.sumOf { it.played })
        assertEquals(Stories.close(b.book, pb, ib, w, w + 1), Stories.close(a.book, pa, ia, w, w + 1))
        assertEquals(b.book.encode(), a.book.encode())
        assertTrue(b.book.winterMoves(pb.number).isNotEmpty())
    }

    @Test
    fun theWindowIsDomesticWithinItsCaps() {
        val (l, plan) = Ms.league()
        val book = l.book
        val s = plan.number
        val w = ClubWorld.winterWeek(plan)
        val leagues = book.clubs.flatMap { it.squad }.associateWith { plan.league.leagueOf(it)?.id }
        throughWinter(l, plan)
        val report = ClubCheckpoint.report(book, s)
        val moves = report.moves
        assertTrue(moves.size >= 5, report.toString())
        val through = plan.league.pointsThrough(w - 1)
        val ws = book.winterMoves(s)
        assertEquals(report.deals.size, ws.size)
        for ((d, m) in report.deals.zip(ws)) {
            assertEquals(MarketPhase.WINTER, d.phase)
            assertEquals(d.key, m.key)
            assertEquals(d.from, m.from)
            assertEquals(d.to ?: ClubCheckpoint.NONE, m.to)
            assertEquals(w, m.week)
            assertEquals(through.getValue(d.key)[Season.THROUGH_POINTS], m.points)
            assertEquals(plan.league.tierOf(d.key), m.tier)
        }
        for (d in moves) {
            assertEquals(MarketReport.WINTER, d.type)
            assertEquals(book.nationOf(d.from!!), book.nationOf(d.to!!), "domestic only: $d")
            assertEquals(leagues[d.key], plan.league.leagueOf(d.key)?.id, "the division stays")
            assertEquals(d.to, book.clubOf(d.key))
            val t = book.terms(d.key)!!
            assertEquals(s, t.since)
            assertTrue(t.until >= s)
            assertEquals(d.wage, t.wage)
        }
        // One deal a club, ⌈C/4⌉ a nation, deadline deals after the first ⌈C/8⌉.
        assertEquals(moves.size, moves.map { it.to }.toSet().size)
        for ((nation, ds) in moves.groupBy { book.nationOf(it.to!!)!! }) {
            val c = book.clubsOf(nation).size
            assertTrue(ds.size <= Market.winterCap(c), "$nation: $ds")
            for ((i, d) in ds.withIndex()) assertEquals(i >= Market.winterEarly(c), 'D' in d.flags, "$nation #$i: $d")
        }
        // Fees moved the balances and are logged as winter moves of the season.
        val logged = book.history.filter { it.season == s }
        assertEquals(report.deals.map { it.key to it.fee }, logged.map { it.ai to it.amount })
        assertTrue(logged.all { it.type == yacht.engine.league.ClubMoveType.WINTER || it.type == yacht.engine.league.ClubMoveType.RELEASED })
    }

    @Test
    fun aSackedCoachIsReplacedAtOnce() {
        val (l, plan) = Ms.league()
        val book = l.book
        val s = plan.number
        val w = ClubWorld.winterWeek(plan)
        val input = Ms.input(l, plan)
        Ms.season(l, plan, input, last = w - 1)
        Ms.playThrough(plan, w)
        // Three boards out of patience.
        val before = book.world!!
        val clubs = book.clubs.filter { book.coachOf(it.id) != null }.sortedByDescending { before.clubs.getValue(it.id).fans }.take(3).map { it.id }
        val old = clubs.associateWith { book.coachOf(it)!!.id }
        for (c in clubs) before.clubs.getValue(c).pressure = 50.0
        val news = Stories.close(book, plan, input, w, w + 1)
        val world = book.world!!
        val report = ClubCheckpoint.report(book, s)
        for (c in clubs) {
            val m = report.sacked.single { it.club == c }
            assertEquals(old.getValue(c), m.coach)
            val now = book.coachOf(c)
            assertNotNull(now)
            assertNotEquals(m.coach, now.id)
            assertEquals(now.id, m.hired)
            assertEquals(s, now.current!!.from)
            assertEquals(0.0, world.clubs.getValue(c).pressure)
            // The coach let go joins the free pool (another club that sacked its own may take it at once).
            val gone = book.coach(m.coach)!!
            assertNotEquals(c, gone.club)
            assertEquals('S', gone.tenures.first { it.club == c }.end)
            assertEquals(s, gone.tenures.first { it.club == c }.to)
            assertTrue(CoachMarket.sackedIn(book, c, s))
        }
        assertEquals(report.sacked.size, report.sacked.map { it.club }.toSet().size, "one sacking a club")
        val sacked = news.filter { it.kind == "coach_sacked" }
        assertEquals(2, sacked.size, news.toString())
        for (e in sacked) assertEquals(report.sacked.single { it.club == e.keys[0] }.let { listOf(it.club, it.coach, it.hired) }, e.keys)
        assertEquals(clubs.take(2), sacked.map { it.keys[0] }, "the biggest fan bases told")
        val digest = news.single { it.kind == "coach_digest" }
        assertEquals(report.sacked.size.toString(), digest.args[0])
        assertTrue(news.size <= WinterReport.MAX_NEWS)
    }

    @Test
    fun theFansMoodFollowsTheGapAndTheLastWindow() {
        // A season after a club world's off-season: its window's sales, record signings and moves to rivals count now.
        val (l, p3) = Ms.league()
        val s3 = p3.number
        Ms.season(l, p3, Ms.input(l, p3))
        Cw.play(p3)
        l.close(p3)
        val next = l.next(s3)
        l.book.offseason(p3, l.input(next, p3))
        l.members = next
        val plan = Cw.fresh(l.members, s3 + 1).also { it.ranking = Cw.ranking(it) }
        val book = l.book
        val s = plan.number
        val w = ClubWorld.winterWeek(plan)
        val input = Ms.input(l, plan)
        Ms.season(l, plan, input, last = w - 1)
        Ms.playThrough(plan, w)
        Stories.week(book, plan, input, w)
        val window = Market.report(book, s3)
        assertTrue(window.deals.isNotEmpty())
        val moods = book.world!!.clubs.mapValues { it.value.mood }
        val tables = ClubTable.all(plan.league, book, emptyList(), w, emptyList(), ClubEconomy.deductions(book))
        val seats = ClubEconomy.seats(tables)
        val targets = CoachMarket.targets(plan, book, Cw::rating)
        val members = book.clubs.flatMap { it.squad }
        val stars = members.sortedByDescending { Cw.rating(it) }.take(Math.ceil(members.size / 100.0).toInt()).toSet()
        val relieved = CoachMarket.report(book, s3).of('S').mapNotNull { it.club }.toSet()
        book.checkpoint(plan, input, w)
        val world = book.world!!
        var moved = 0
        for ((id, seat) in seats) {
            val points = window.moodEvents(id) { it in stars } + ClubEconomy.moodEvents(sacked = id in relieved)
            if (points != 0) moved++
            val gap = ClubEconomy.gap(targets[id] ?: seat.place, seat.place, seat.clubs)
            assertEquals(ClubEconomy.moodAfter(moods.getValue(id), gap, points), world.clubs.getValue(id).mood, id)
        }
        assertTrue(moved > 0, "some clubs' fans felt the window")
        assertEquals(s, world.checkpoint, "the close will not count the window again")
    }

    @Test
    fun wagesArePaidHalfByEachClub() {
        val (l, plan) = Ms.league()
        val book = l.book
        val s = plan.number
        val input = Ms.input(l, plan)
        throughWinter(l, plan, input)
        val moved = ClubCheckpoint.report(book, s).moves
        assertTrue(moved.isNotEmpty())
        Ms.season(l, plan, input)
        val wages = book.clubs.associate { c -> c.id to book.members(c.id).sumOf { book.terms(it)?.wage ?: 0 } }
        val tables = ClubTable.all(plan.league, book, emptyList(), null, book.winterMoves(s), ClubEconomy.deductions(book))
        ClubEconomy.accounts(book, plan, input, tables)
        // Each mover costs half at the club it left and the rest at the one it joined; every wage is paid once.
        val half = HashMap<String, Int>()
        for (d in moved) {
            val wg = book.terms(d.key)!!.wage
            half.merge(d.to!!, -(wg / 2), Int::plus)
            half.merge(d.from!!, wg / 2, Int::plus)
        }
        for (c in book.clubs) {
            val a = book.life(c.id)!!.last!!
            assertEquals(s, a.season)
            assertEquals(wages.getValue(c.id) + (half[c.id] ?: 0), a.wages, c.id)
        }
        assertEquals(wages.values.sum(), book.clubs.sumOf { book.life(it.id)!!.last!!.wages })
    }

    @Test
    fun theClubTableSplitsAMoversPoints() {
        val (l, plan) = Ms.league()
        val book = l.book
        val s = plan.number
        val input = Ms.input(l, plan)
        throughWinter(l, plan, input)
        Ms.season(l, plan, input)
        val m = book.winterMoves(s).first { it.to != ClubCheckpoint.NONE }
        val league = plan.league.leagueOf(m.key)!!.id
        val total = plan.league.standings.getValue(m.key).points
        val table = ClubTable.of(plan.league, book, league, emptyList(), null, book.winterMoves(s))
        val plain = ClubTable.of(plan.league, book, league, emptyList())
        // Without the split the old club loses the mover's points and the new one has them all.
        val from = table.first { it.club == m.from }
        val to = table.first { it.club == m.to }
        assertTrue(m.points <= total)
        assertTrue(from.leaguePart >= plain.first { it.club == m.from }.leaguePart)
        assertTrue(to.leaguePart <= plain.first { it.club == m.to }.leaguePart)
        assertTrue(table.zip(plain).any { (x, y) -> x != y }, "the split changes the table")
    }

    @Test
    fun theWintersTalksAreDecidedAndItsRumoursEnd() {
        val (l, plan) = Ms.league()
        val book = l.book
        val world = book.world!!
        val s = plan.number
        val w = ClubWorld.winterWeek(plan)
        val input = Ms.input(l, plan)
        Ms.season(l, plan, input, last = w - 1)
        Ms.playThrough(plan, w)
        // Talks for a squad player of the richest club's rival at home, and a rumour.
        val buyer = book.clubs.filter { book.clubsOf(it.nation).size >= 3 }.maxBy { ClubEconomy.transferBudget(book, it.id) }
        val seller = book.clubsOf(buyer.nation).first { it.id != buyer.id }
        val key = seller.squad.minBy { Cw.rating(it) }
        world.market.stories.removeIf { it.season == s && (it.key == key || it.buyer == buyer.id) }
        world.market.stories += MarketStory(800, s, StoryStage.TALKS, key, buyer.id, seller.id, 10, w - 2, w, '-', true, null)
        world.market.stories += MarketStory(801, s, StoryStage.RUMOUR, seller.squad.first { it != key }, buyer.id, seller.id, 10, w - 1, 0, '-', true, null)
        Stories.close(book, plan, input, w, w + 1)
        val talks = book.stories(s).single { it.id == 800 }
        assertTrue(talks.stage in setOf(StoryStage.DONE, StoryStage.COLLAPSED, StoryStage.HIJACKED), talks.toString())
        if (talks.stage == StoryStage.DONE) {
            assertEquals(buyer.id, book.clubOf(key))
            assertTrue(book.winterMoves(s).any { it.key == key && it.from == seller.id && it.to == buyer.id })
        }
        assertTrue(book.stories(s).none { it.winter && (it.stage == StoryStage.RUMOUR || it.stage == StoryStage.TALKS) })
        assertTrue(ClubCheckpoint.report(book, s).stories.any { it.id == 800 })
    }

    @Test
    fun theNewsKeepsTheWinterWeeksBudget() {
        val (l, plan) = Ms.league()
        val book = l.book
        val s = plan.number
        val news = throughWinter(l, plan)
        val report = ClubCheckpoint.report(book, s)
        assertEquals(report.events(book), news)
        assertTrue(news.size <= WinterReport.MAX_NEWS)
        val kinds = setOf("winter_window", "coach_sacked", "coach_digest", "record_fee", "rival_move", "release_clause", "transfer", "deal_collapsed", "fan_protest")
        assertTrue(news.all { it.kind in kinds }, news.toString())
        val window = news.single { it.kind == "winter_window" }
        val moves = report.moves
        val big = moves.withIndex().maxWith(compareBy<IndexedValue<yacht.engine.league.MarketDeal>> { it.value.fee }.thenByDescending { it.index }).value
        assertEquals(listOf(moves.size.toString(), big.key, big.from, big.to, big.fee.toString()), window.args)
        assertTrue(window.keys.size <= MarketReport.NAMED && window.keys.first() == big.key)
        val singles = news.filter { it.kind in setOf("record_fee", "rival_move", "release_clause", "transfer", "deal_collapsed") }
        assertTrue(singles.size <= WinterReport.SINGLES)
        for (e in news.filter { it.kind == "transfer" }) assertEquals(WinterReport.WINTER_FLAG, e.args[2])
        report.record?.let { r -> assertEquals("record_fee", singles.first().kind, "the record goes first: $r") }
    }

    @Test
    fun aSeasonBegunAfterItsWinterWeekSkipsIt() {
        val (l0, p0) = Ms.league()
        val w = ClubWorld.winterWeek(p0)
        val (l, plan) = Ms.league(week = w)
        val book = l.book
        assertEquals(plan.number, book.world!!.checkpoint)
        Ms.playThrough(plan, w)
        val before = book.encode()
        assertEquals(emptyList(), Stories.close(book, plan, Ms.input(l, plan), w, w + 1))
        assertEquals(before, book.encode())
        assertTrue(book.winterMoves(plan.number).isEmpty())
        // A season begun just before it still has it.
        assertTrue(l0.book.world!!.checkpoint < p0.number)
    }

    @Test
    fun aFailingCheckpointLeavesTheBookAsItWasAndRunsWholeNextTime() {
        val (a, pa) = Ms.league()
        val (b, pb) = Ms.league()
        val w = ClubWorld.winterWeek(pa)
        val ia = Ms.input(a, pa)
        val ib = Ms.input(b, pb)
        Ms.season(a, pa, ia, last = w - 1)
        Ms.season(b, pb, ib, last = w - 1)
        Ms.playThrough(pa, w)
        Ms.playThrough(pb, w)
        Stories.week(a.book, pa, ia, w)
        val before = a.book.encode()
        a.book.fault = { if (it == ClubBook.CHECKPOINT) error("injected after the checkpoint") }
        assertEquals(emptyList(), a.book.checkpoint(pa, ia, w))
        a.book.fault = null
        assertEquals(before, a.book.encode())
        assertTrue(a.book.world!!.checkpoint < pa.number)
        val again = a.book.checkpoint(pa, ia, w)
        assertEquals(Stories.close(b.book, pb, ib, w, w + 1), again)
        assertEquals(b.book.encode(), a.book.encode())
    }

    @Test
    fun nothingWithTheClubWorldOffOrItsKnobOff() {
        val (l, plan) = Ms.league()
        val w = ClubWorld.winterWeek(plan)
        Ms.season(l, plan, Ms.input(l, plan), last = w - 1)
        Ms.playThrough(plan, w)
        val before = l.book.encode()
        assertEquals(emptyList(), l.book.checkpoint(plan, Ms.input(l, plan, config = Cw.config.copy(clubWorld = false)), w))
        assertEquals(emptyList(), l.book.checkpoint(plan, Ms.input(l, plan, winter = false), w))
        assertEquals(before, l.book.encode())
        assertFalse(l.book.world!!.checkpoint >= plan.number)
    }
}

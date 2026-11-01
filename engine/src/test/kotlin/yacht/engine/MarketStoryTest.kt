package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubTable
import yacht.engine.league.ClubWorld
import yacht.engine.league.CoachInput
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.LeagueEvent
import yacht.engine.league.MarketStory
import yacht.engine.league.Origin
import yacht.engine.league.PlayerTraits
import yacht.engine.league.SeasonPlan
import yacht.engine.league.Stories
import yacht.engine.league.StoryStage
import yacht.engine.league.StyleFamily
import yacht.engine.league.Transfers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A national season in play for the in-season tests (MarketStoryTest, WinterWindowTest): a league of
 * generated players migrated into a club world at the start of its third season, that season's plan
 * (ranked, so moves abroad are possible), matches played week by week, and the in-season input with
 * the drawn ratings ([Cw.rating]) and live ratings that may differ from them.
 */
internal object Ms {
    /** A migrated league of [size] players in its season in play (none of it played, the last closed week [week]), and that season's plan. */
    fun league(size: Int = 600, week: Int = 0): Pair<Cw.League, SeasonPlan> {
        val (l, plan) = Cw.migrated(size, week = week)
        plan.ranking = Cw.ranking(plan)
        return l to plan
    }

    /** The in-season input of [l] in [plan]: the drawn ratings are [Cw.rating]; [live] is the ladder's now; ages are the season in play's. */
    fun input(
        l: Cw.League, plan: SeasonPlan, live: (String) -> Double = Cw::rating, config: LeagueConfig = Cw.config,
        market: Boolean = true, winter: Boolean = true, coaches: Boolean = true,
        age: (String) -> Int? = { k -> Cw.players[k]?.age(plan.number) ?: 22 },
    ): ClubInput = ClubInput(
        l.members, live, Cw::nation, config, l.honours.toList(), age = age, origin = Origin::of,
        ranking = plan.ranking, economy = LeagueEconomy.of(plan, Cw::rating), drawn = Cw::rating,
        coachInput = CoachInput(names = Cw.players.alive.mapTo(HashSet()) { it.name }), families = { k -> StyleFamily.of(Cw.players[k]?.spec?.play) },
        coaches = coaches, market = market, winter = winter,
        hometown = { k -> Cw.players[k]?.hometown }, potential = { k -> Cw.players[k]?.identity?.potential }, overall = { k -> Cw.players[k]?.identity?.abilities?.overall },
        traits = { k -> Cw.players[k]?.let { PlayerTraits.of(it.spec.play, it.identity) } },
    )

    /** Live ratings that moved away from the drawn ones (the ladder after more matches). */
    fun moved(k: String): Double = Cw.rating(k) + Math.floorMod(k.hashCode() * 7, 61) - 30

    /** Every league fixture of [plan] through week [week] (matchdays before it) played, as [Cw.play] plays them. */
    fun playThrough(plan: SeasonPlan, week: Int) {
        val s = plan.league
        while (true) play(plan, s.next(emptySet(), lastMatchday = week - 1) ?: break)
    }

    /** [n] fixtures of week [week] played early (with the results they get anyway), before the week before it is closed. */
    fun playEarly(plan: SeasonPlan, week: Int, n: Int = 30) {
        val s = plan.league
        repeat(n) { play(plan, s.next(emptySet(), lastMatchday = week - 1) ?: return) }
    }

    private fun play(plan: SeasonPlan, f: yacht.engine.league.LeagueFixture) {
        val aWins = (Cw.rating(f.a) >= Cw.rating(f.b)) != (f.id % 5 == 0)
        plan.league.record(f.id, List(2) { if (aWins) GameScore(220, 180) else GameScore(180, 220) })
    }

    /** A copy of [book] as its file keeps it. */
    fun copy(book: ClubBook): ClubBook = ClubBook.decode(book.encode())

    /** The season of [l]'s plan [plan] closed week by week to [last] (each week played, then closed): each week's news. */
    fun season(l: Cw.League, plan: SeasonPlan, input: ClubInput, last: Int = plan.leagueWeeks): Map<Int, List<LeagueEvent>> {
        val out = LinkedHashMap<Int, List<LeagueEvent>>()
        for (k in 1..last) {
            playThrough(plan, k)
            out[k] = Stories.close(l.book, plan, input, k, k + 1)
        }
        return out
    }
}

/**
 * The transfer market's weekly tick (design ENG 3.1, [Stories]): rumours from week ⌈0.6 R⌉ and in the
 * winter weeks, at most 2 a week (1 below 500 members), one open story a player and a buyer that can
 * pay, talks 2-4 weeks after a rumour with chance 0.45, the drawn ratings only, every closed week in
 * order, the stored news on a repeat, nothing without a club world.
 */
class MarketStoryTest {
    @Test
    fun rumoursComeInTheirWeeksTwoAWeekAtMost() {
        val (l, plan) = Ms.league()
        val book = l.book
        assertTrue(book.size >= Stories.SMALL)
        val r = plan.leagueWeeks
        val w = ClubWorld.winterWeek(plan)
        assertEquals(Math.ceil(0.6 * r).toInt(), Stories.firstWeek(plan))
        assertEquals((w - 2) until w, Stories.winterWeeks(plan))
        Ms.season(l, plan, Ms.input(l, plan))
        val stories = book.stories(plan.number)
        val byWeek = stories.groupBy { it.rumourWeek }
        for ((k, st) in byWeek) {
            assertTrue(Stories.rumourWeek(plan, k), "week $k")
            assertTrue(st.size <= Stories.RUMOURS, "week $k: $st")
            for (s in st) assertEquals(k in Stories.winterWeeks(plan), s.winter, s.toString())
        }
        // Nearly every rumour week starts some (the winter's rumours that came to nothing end at the checkpoint).
        val ordinary = (maxOf(Stories.firstWeek(plan), w + 1)..r).toList()
        assertTrue(ordinary.count { it in byWeek } >= ordinary.size * 8 / 10, byWeek.keys.toString())
        assertTrue(stories.none { it.winter && it.stage == StoryStage.RUMOUR })
        // Below 500 members one a week.
        val (small, p2) = Ms.league(size = 400)
        assertTrue(small.book.size < Stories.SMALL)
        Ms.season(small, p2, Ms.input(small, p2), last = Stories.firstWeek(p2) + 6)
        val smallWeeks = small.book.stories(p2.number).groupBy { it.rumourWeek }
        assertTrue(smallWeeks.isNotEmpty())
        for ((k, st) in smallWeeks) assertTrue(st.size <= Stories.RUMOURS_SMALL, "week $k: $st")
    }

    @Test
    fun aPlayerHasOneOpenStoryWithABuyerThatCanPay() {
        val (l, plan) = Ms.league()
        val book = l.book
        val input = Ms.input(l, plan)
        val s = plan.number
        val season = plan.league
        val leagueOf = Transfers.leagues(season, book::clubOf, book::nationOf)
        var abroad = 0
        for (k in 1..plan.leagueWeeks) {
            Ms.playThrough(plan, k)
            val before = book.stories(s).map { it.id }.toSet()
            Stories.close(book, plan, input, k, k + 1)
            val open = Stories.open(book, s)
            assertEquals(open.size, open.map { it.key }.toSet().size, "one open story a player: $open")
            for (st in book.stories(s).filter { it.id !in before }) {
                assertEquals(k, st.rumourWeek)
                assertEquals(st.seller, book.clubOf(st.key))
                assertNotEquals(st.seller, st.buyer)
                assertNull(book.terms(st.key)!!.loanFrom)
                assertTrue(ClubEconomy.transferBudget(book, st.buyer, input.config.ffp) >= Stories.BUDGET_SHARE * st.fee, st.toString())
                if (book.nationOf(st.buyer) == book.nationOf(st.seller)) {
                    // The buyer's counted 6 would take it.
                    val six = book.members(st.buyer).sortedByDescending { Cw.rating(it) }.take(ClubTable.COUNTED)
                    assertTrue(six.size < ClubTable.COUNTED || Cw.rating(st.key) > Cw.rating(six.last()), st.toString())
                } else {
                    abroad++
                    assertTrue(!st.winter)
                    // Transfers' rule as the off-season applies it: the age in the coming season.
                    assertTrue(Cw.players[st.key]!!.age(s + 1) in Transfers.MIN_AGE..Transfers.MAX_AGE, st.toString())
                    val from = plan.ranking.rank(season.leagueOf(st.key)!!.id)
                    val to = plan.ranking.rank(leagueOf.getValue(book.nationOf(st.buyer)!!))
                    assertTrue(to <= from - Transfers.GAP, st.toString())
                }
            }
        }
        assertTrue(abroad <= Transfers.count(input.config, input.members.size), "abroad stories $abroad")
        assertTrue(book.stories(s).size >= 20)
    }

    @Test
    fun aMoveAbroadIsTalkedOfForPlayersTheOffseasonWouldSend() {
        // Everyone 30 in the season in play: 31 at the off-season, still young enough to go abroad; 31 now: too old then.
        fun abroad(age: Int): Int {
            val (l, plan) = Ms.league(size = 1000)
            val book = l.book
            Ms.season(l, plan, Ms.input(l, plan, age = { age }))
            return book.stories(plan.number).count { book.nationOf(it.buyer) != book.nationOf(it.seller) }
        }
        assertTrue(abroad(Transfers.MAX_AGE - 1) > 0)
        assertEquals(0, abroad(Transfers.MAX_AGE))
    }

    @Test
    fun rumoursTurnIntoTalksTwoToFourWeeksLaterAboutHalfTheTime() {
        val (l, plan) = Ms.league()
        val book = l.book
        val w = book.world!!
        val s = plan.number
        val first = Stories.firstWeek(plan)
        // 200 rumours started in the first rumour week, each by a club of the member's nation.
        val made = ArrayList<MarketStory>()
        for (c in book.clubs) {
            val other = book.clubsOf(c.nation).firstOrNull { it.id != c.id } ?: continue
            for (k in c.squad.take(4)) if (made.size < 200) made += MarketStory(1000 + made.size, s, StoryStage.RUMOUR, k, other.id, c.id, 50, first, 0, '-', false, null)
        }
        assertEquals(200, made.size)
        w.market.stories += made
        val input = Ms.input(l, plan)
        for (k in first..first + Stories.ESCALATE_AFTER + Stories.ESCALATE_SPREAD) Stories.week(book, plan, input, k)
        val ours = book.stories(s).filter { it.id >= 1000 }
        assertEquals(200, ours.size)
        for (st in ours) {
            val due = Stories.escalation(book, plan, st)
            assertTrue(due - first in Stories.ESCALATE_AFTER until Stories.ESCALATE_AFTER + Stories.ESCALATE_SPREAD, st.toString())
            if (st.stage == StoryStage.TALKS) assertEquals(due, st.talksWeek) else assertEquals(StoryStage.RUMOUR, st.stage)
        }
        val share = ours.count { it.stage == StoryStage.TALKS } / 200.0
        assertTrue(share in 0.36..0.54, "talks share $share")
        assertTrue(ours.groupBy { Stories.escalation(book, plan, it) }.size == Stories.ESCALATE_SPREAD)
        // A winter rumour comes to talks in the checkpoint's week, or not at all.
        val winter = MarketStory(2000, s, StoryStage.RUMOUR, "x", "c01", "c02", 1, Stories.winterWeeks(plan).first, 0, '-', true, null)
        assertEquals(ClubWorld.winterWeek(plan), Stories.escalation(book, plan, winter))
    }

    @Test
    fun theDrawnRatingsAloneDecide() {
        // One league closes its weeks with next week's fixtures already played and the ladder moved on; the other
        // closes them as they come: the stories, the checkpoint and the news are the same.
        val (a, pa) = Ms.league()
        val (b, pb) = Ms.league()
        assertEquals(a.book.encode(), b.book.encode())
        val ia = Ms.input(a, pa, live = Ms::moved)
        val ib = Ms.input(b, pb)
        val last = ClubWorld.winterWeek(pa) + 14
        for (k in 1..last) {
            Ms.playThrough(pa, k)
            Ms.playEarly(pa, k + 1)
            Ms.playThrough(pb, k)
            assertEquals(Stories.close(b.book, pb, ib, k, k + 1), Stories.close(a.book, pa, ia, k, k + 1), "week $k")
        }
        assertEquals(b.book.encode(), a.book.encode())
        assertTrue(a.book.stories(pa.number).isNotEmpty())
    }

    @Test
    fun weeksClosedTogetherAreTickedInOrder() {
        val (a, pa) = Ms.league()
        val (b, pb) = Ms.league()
        val last = ClubWorld.winterWeek(pa) + 12
        val one = Ms.season(a, pa, Ms.input(a, pa), last).values.flatten()
        // The other closes three weeks at a time, then the rest at once.
        val ib = Ms.input(b, pb)
        val all = ArrayList<LeagueEvent>()
        var from = 1
        while (from <= last) {
            val to = if (from > last - 8) last + 1 else minOf(last + 1, from + 3)
            Ms.playThrough(pb, to - 1)
            all += Stories.close(b.book, pb, ib, from, to)
            from = to
        }
        assertEquals(one, all)
        assertEquals(a.book.encode(), b.book.encode())
        assertTrue(one.any { it.kind == "market_week" } && one.any { it.kind == "winter_window" }, one.toString())
    }

    @Test
    fun aWeekTickedAgainTellsTheSameAndChangesNothing() {
        val (l, plan) = Ms.league()
        val book = l.book
        val input = Ms.input(l, plan)
        val news = Ms.season(l, plan, input, last = plan.leagueWeeks - 4)
        val after = book.encode()
        val copy = Ms.copy(book)
        for ((k, e) in news) {
            val tick = e.filter { it.kind == "market_week" }
            assertEquals(tick, Stories.week(book, plan, input, k), "week $k")
            assertEquals(tick, Stories.week(copy, plan, input, k), "week $k read back")
            assertEquals(e, Stories.close(book, plan, input, k, k + 1), "week $k closed again")
        }
        assertEquals(after, book.encode())
        assertEquals(after, copy.encode())
        assertTrue(news.values.flatten().count { it.kind == "market_week" } >= 4)
        // An earlier season's weeks change nothing either.
        val earlier = Cw.fresh(l.members, plan.number - 1)
        assertEquals(emptyList(), Stories.week(book, earlier, input, 30))
        assertEquals(after, book.encode())
    }

    @Test
    fun aBookReadBackEveryWeekPlaysOnAsOneKeptInMemory() {
        // A league killed after each week's close and read back from its file: the same stories, checkpoint and news.
        val (l, plan) = Ms.league()
        val input = Ms.input(l, plan)
        var read = Ms.copy(l.book)
        for (k in 1..plan.leagueWeeks + 2) {
            Ms.playThrough(plan, k)
            val kept = Stories.close(l.book, plan, input, k, k + 1)
            read = Ms.copy(read)
            assertEquals(kept, Stories.close(read, plan, input, k, k + 1), "week $k")
            assertEquals(l.book.encode(), read.encode(), "week $k")
        }
        assertTrue(l.book.stories(plan.number).isNotEmpty() && l.book.winterMoves(plan.number).isNotEmpty())
    }

    @Test
    fun theMarketWeekIsToldOnceAWeekForTwelveWeeks() {
        val (l, plan) = Ms.league()
        val book = l.book
        val w = book.world!!
        val s = plan.number
        val c = book.clubs.first { book.clubsOf(it.nation).size >= 2 }
        val other = book.clubsOf(c.nation).first { it.id != c.id }
        var id = 500
        for (week in 26..40) for (n in 1..2) {
            val key = c.squad[(week * 2 + n) % c.squad.size]
            w.market.stories += MarketStory(id++, s, StoryStage.TALKS, key, other.id, c.id, week * 10 + n, week - 3, week, '-', false, null)
        }
        w.market.stories += MarketStory(id, s, StoryStage.TALKS, c.squad[0], other.id, c.id, 999, 20, ClubWorld.winterWeek(plan), '-', true, null)
        for (week in 26..40) {
            val e = Stories.events(book, plan, week)
            if (week - 26 < Stories.WEEKS_TOLD) {
                val best = book.stories(s).filter { it.talksWeek == week && !it.winter }.maxBy { it.fee }
                assertEquals(
                    listOf(LeagueEvent("market_week", s, listOf(best.key, other.id, c.id), listOf(week.toString(), best.fee.toString(), "2"), yacht.engine.league.Comp.CLUB, week)), e,
                )
            } else {
                assertEquals(emptyList(), e, "week $week: past the season's twelve")
            }
        }
        assertEquals(emptyList(), Stories.events(book, plan, ClubWorld.winterWeek(plan)), "the winter's talks are the checkpoint's news")
        assertEquals(emptyList(), Stories.events(book, plan, 25))
    }

    @Test
    fun aRumourEndsWhenItsPlayerMovesButTalksWait() {
        val (l, plan) = Ms.league()
        val book = l.book
        val w = book.world!!
        val s = plan.number
        val c = book.clubs.first { book.clubsOf(it.nation).size >= 3 }
        val (b1, b2) = book.clubsOf(c.nation).filter { it.id != c.id }.take(2)
        val (k1, k2) = c.squad.take(2)
        w.market.stories += MarketStory(900, s, StoryStage.RUMOUR, k1, b1.id, c.id, 40, 25, 0, '-', false, null)
        w.market.stories += MarketStory(901, s, StoryStage.TALKS, k2, b1.id, c.id, 40, 24, 26, '-', false, null)
        book.place(k1, b2.id)
        book.place(k2, b2.id)
        Stories.week(book, plan, Ms.input(l, plan), 27)
        assertTrue(book.stories(s).none { it.id == 900 })
        assertEquals(StoryStage.TALKS, book.stories(s).single { it.id == 901 }.stage)
    }

    @Test
    fun nothingWithoutAClubWorldOrWithItOff() {
        // A book without a club world.
        val l0 = Cw.league(size = 400)
        val p0 = Cw.fresh(l0.members, 3)
        val before0 = l0.book.encode()
        for (k in 1..p0.leagueWeeks) assertEquals(emptyList(), Stories.close(l0.book, p0, Ms.input(l0, p0), k, k + 1))
        assertEquals(before0, l0.book.encode())
        // clubWorld off, and the test knob for the stories off.
        val (l, plan) = Ms.league()
        val before = l.book.encode()
        val off = Ms.input(l, plan, config = Cw.config.copy(clubWorld = false))
        val noStories = Ms.input(l, plan, market = false)
        for (k in Stories.firstWeek(plan)..plan.leagueWeeks) {
            assertEquals(emptyList(), Stories.week(l.book, plan, off, k))
            assertEquals(emptyList(), Stories.week(l.book, plan, noStories, k))
        }
        assertEquals(before, l.book.encode())
    }

    @Test
    fun theOffseasonDecidesTheTalksAndDropsTheRumours() {
        val (l, plan) = Ms.league()
        val book = l.book
        val s = plan.number
        Ms.season(l, plan, Ms.input(l, plan))
        val talks = book.stories(s).filter { it.stage == StoryStage.TALKS }.map { it.id }.toSet()
        assertTrue(talks.size >= 5, book.stories(s).toString())
        Cw.play(plan)
        l.close(plan)
        val next = l.next(s)
        book.offseason(plan, l.input(next, plan))
        val after = book.stories(s)
        assertTrue(after.none { it.stage == StoryStage.RUMOUR || it.stage == StoryStage.TALKS }, after.toString())
        val decided = after.filter { it.id in talks }
        assertTrue(decided.isNotEmpty())
        val deals = yacht.engine.league.Market.report(book, s).deals
        for (st in decided.filter { it.stage == StoryStage.DONE }) assertTrue(deals.any { it.key == st.key && it.to == st.buyer }, st.toString())
    }
}

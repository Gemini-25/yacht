package yacht.engine

import yacht.engine.league.Article
import yacht.engine.league.Comp
import yacht.engine.league.LeagueEvent
import yacht.engine.league.News
import yacht.engine.league.NewsBudget
import yacht.engine.league.SeasonCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The club world's news budgets (design ENG 11.2, 3.1) on synthetic event streams: the rollover's
 * 10 + members / 500 articles with the rest folded into the digests, and the in-season caps.
 */
class NewsBudgetTest {
    private fun e(kind: String, keys: List<String>, args: List<String> = emptyList(), week: Int = SeasonCalendar.NONE, season: Int = 4, comp: Comp? = Comp.CLUB) =
        LeagueEvent(kind, season, keys, args, comp, week)

    /** A busy club off-season: every kind its stages tell, more singles than any budget, with today's news around them. */
    private fun busy(): List<LeagueEvent> = listOf(
        e("club_title", listOf("c1"), listOf("KR", "461.0")),
        e("transfer", listOf("a11", "c2", "c1"), listOf("85", "1")),
        // The deal a release clause tells as well.
        e("transfer", listOf("a2", "c3", "c4"), listOf("300")),
        e("market_digest", listOf("a1", "a2"), listOf("12", "34", "a1", "c2", "c1", "85")),
        e("club_accounts", listOf("c1", "c9"), listOf("322", "45", "7")),
        e("club_trouble", listOf("c9"), listOf("-45")),
        e("administration", listOf("c8")),
        e("takeover", listOf("c7"), listOf("한빛건설", "L")),
        e("sponsor_deal", listOf("c6"), listOf("휘담전자", "46", "3", "0")),
        e("sponsor_exit", listOf("c5"), listOf("벨로렌 텔레콤", "0")),
        e("stadium_project", listOf("c4"), listOf("18000", "24000", "1", "160", "E")),
        e("stadium_open", listOf("c3"), listOf("부산 아레나", "24000", "E")),
        e("fan_protest", listOf("c9", "k9"), listOf("18")),
        e("fan_protest", listOf("c8", "k8"), listOf("20")),
        e("fan_party", listOf("c1")),
        e("club_world_digest", listOf("c6", "c4"), listOf("5", "2", "3")),
        e("coach_sacked", listOf("c9", "k9"), listOf("7", "3", "1")),
        e("coach_sacked", listOf("c8", "k8"), listOf("6", "4", "1")),
        e("coach_poached", listOf("c9", "k7", "c7"), listOf("25")),
        e("coach_hired", listOf("c8", "k6"), listOf("A")),
        e("coach_hired", listOf("c7", "k5"), listOf("Y")),
        e("coach_retired", listOf("k4"), listOf("14", "3")),
        e("player_to_coach", listOf("a9", "k3"), listOf("-", "S")),
        e("record_fee", listOf("a1", "c2", "c1"), listOf("520", "480", "W")),
        e("rival_move", listOf("a3", "c5", "c6"), listOf("140")),
        e("release_clause", listOf("a2", "c3", "c4"), listOf("300")),
        e("forced_sale", listOf("a4", "c9", "c2"), listOf("85")),
        e("free_agent", listOf("a5", "c3", "c4"), listOf("18")),
        e("contract_renewal", listOf("a6", "c1"), listOf("3", "25")),
        e("deal_collapsed", listOf("a7", "c1", "c2"), listOf("P")),
        e("deadline_day", listOf("a1", "a2"), listOf("2")),
        e("loan_digest", listOf("a3"), listOf("5")),
        e("wonderkid", listOf("a8", "c1"), listOf("92", "17")),
        e("academy_graduates", listOf("a8", "c1"), listOf("14", "1")),
        e("one_club_man", listOf("a9", "c3"), listOf("14", "7")),
        e("club_legend", listOf("a5", "c4")),
        e("captain", listOf("a6", "c1")),
        e("rivalry_born", listOf("c1", "c2"), listOf("P")),
        e("club_ranking", listOf("c1", "c2"), listOf("1240")),
        e("draft", listOf("a10", "c1"), listOf("3")),
    )

    private val budgeted = NewsBudget.ROLLOVER.toSet()

    /** The market's singles that tell a deal (a `transfer` of it is told once). */
    private val deals = setOf("record_fee", "rival_move", "release_clause", "forced_sale")

    @Test
    fun theRolloverTellsTenAndOneMoreEveryFiveHundredMembers() {
        assertEquals(10, NewsBudget.rolloverCap(200))
        assertEquals(12, NewsBudget.rolloverCap(1000))
        assertEquals(16, NewsBudget.rolloverCap(3000))
        val events = busy()
        for (members in listOf(200, 1000, 3000)) {
            val out = NewsBudget.rollover(events, members)
            val told = out.filter { it.kind in budgeted }
            assertEquals(NewsBudget.rolloverCap(members), told.size, "$members: ${told.map { it.kind }}")
            // Today's news stays whole and in its place, but a `transfer` of a deal a kept market single tells.
            val toldDeals = out.filter { it.kind in deals }.map { it.keys.take(3) }.toSet()
            val todays = events.filter { it.kind !in budgeted && !(it.kind == "transfer" && it.keys.take(3) in toldDeals) }
            assertEquals(todays, out.filter { it.kind !in budgeted })
            // The clause's deal is told once: by the clause when it is kept, else by the plain transfer.
            assertEquals(1, out.count { it.keys.take(3) == listOf("a2", "c3", "c4") }, "$members")
            // The digests are always told; the singles kept are the most important ones, ties by the table's order.
            assertTrue(out.any { it.kind == NewsBudget.CLUB_DIGEST } && out.any { it.kind == NewsBudget.COACH_DIGEST })
            val keptSingles = told.filter { it.kind != NewsBudget.CLUB_DIGEST && it.kind != NewsBudget.COACH_DIGEST }
            val droppedSingles = events.filter { it.kind in budgeted && it.kind != NewsBudget.CLUB_DIGEST && it !in out }
            val worstKept = keptSingles.minOf { News.importance(it) }
            assertTrue(droppedSingles.all { News.importance(it) <= worstKept }, "$members: ${droppedSingles.map { it.kind to News.importance(it) }}")
            val order = NewsBudget.ROLLOVER.withIndex().associate { it.value to it.index }
            for (d in droppedSingles.filter { News.importance(it) == worstKept }) {
                assertTrue(keptSingles.filter { News.importance(it) == worstKept }.all { order.getValue(it.kind) <= order.getValue(d.kind) }, "$members: ${d.kind}")
            }
            // The kept ones keep their order; the result is the budget's fixed point.
            assertEquals(out.filter { it in events }, events.filter { it in out })
            assertEquals(out, NewsBudget.rollover(out, members))
            assertEquals(out, NewsBudget.rollover(events, members), "deterministic")
        }
        // The biggest stories always make it: the world record, administration, the takeover.
        val small = NewsBudget.rollover(events, 200)
        for (kind in listOf("record_fee", "administration")) assertTrue(small.any { it.kind == kind }, kind)
    }

    @Test
    fun theRestIsFoldedIntoTheDigests() {
        val events = busy()
        val out = NewsBudget.rollover(events, 200)
        // Coach moves left out with no coach digest: one is made, at the first coach move's place, counting them all.
        val digest = out.single { it.kind == NewsBudget.COACH_DIGEST }
        assertEquals(listOf("2", "3", "1", "3"), digest.args)
        assertEquals(listOf("c9", "c8", "c7"), digest.keys)
        assertEquals(out.indexOfFirst { it.kind == NewsBudget.COACH_DIGEST }, out.indexOfFirst { it.kind == NewsBudget.COACH_DIGEST || it.kind.startsWith("coach_") })
        assertTrue(out.indexOf(digest) < out.indexOfFirst { it.kind == "record_fee" })
        // Club life singles left out name their clubs in the club world's digest (6 at most, its own first).
        val clubDigest = out.single { it.kind == NewsBudget.CLUB_DIGEST }
        assertEquals(listOf("c6", "c4"), clubDigest.keys.take(2))
        assertTrue(clubDigest.keys.size <= NewsBudget.DIGEST_CLUBS && clubDigest.keys.size > 2, clubDigest.keys.toString())
        assertEquals(clubDigest.keys.distinct(), clubDigest.keys)
        assertEquals(listOf("5", "2", "3"), clubDigest.args)
        // An off-season with its own coach digest keeps it as it is (its counts hold every move).
        val own = events + e("coach_digest", listOf("c9", "c8"), listOf("2", "3", "1", "3"))
        val withOwn = NewsBudget.rollover(own, 200)
        assertEquals(listOf(own.last()), withOwn.filter { it.kind == NewsBudget.COACH_DIGEST })
        assertEquals(NewsBudget.rolloverCap(200), withOwn.count { it.kind in budgeted })
    }

    @Test
    fun aQuietRolloverAndTodaysNewsPassUntouched() {
        val quiet = listOf(
            e("club_title", listOf("c1"), listOf("KR", "461.0")), e("transfer", listOf("a1", "c2", "c1"), listOf("85")),
            e("club_accounts", listOf("c1"), listOf("322", "0", "0")), e("sponsor_deal", listOf("c6"), listOf("휘담전자", "46", "3", "0")),
            e("club_world_digest", listOf("c6"), listOf("1", "0", "0")), e("coach_sacked", listOf("c9", "k9"), listOf("7", "3", "1")),
        )
        assertEquals(quiet, NewsBudget.rollover(quiet, 1000))
        // A book without a club world tells only today's kinds: nothing changes, however many.
        val todays = (1..40).map { e("transfer", listOf("a$it", "c1", "c2"), listOf("$it")) } + e("market_digest", emptyList(), listOf("0", "5", "-", "-", "-", "0"))
        assertEquals(todays, NewsBudget.rollover(todays, 200))
        assertEquals(emptyList(), NewsBudget.rollover(emptyList(), 200))
    }

    @Test
    fun oneStoryIsToldOnce() {
        // The arc's investor takeover (at the close, or in the same stream) makes the life step's takeover of that club go.
        val open = listOf(e("takeover", listOf("c3"), listOf("오르비스 인베스트먼트", "S")), e("takeover", listOf("c4"), listOf("한빛건설", "L")))
        val close = listOf(LeagueEvent("investor_takeover", 4, listOf("c3"), listOf("오르비스 인베스트먼트", "S")))
        assertEquals(listOf(open[1]), NewsBudget.rollover(open, 200, close))
        assertEquals(close + open[1], NewsBudget.rollover(close + open, 200))
        // A deal the market's singles tell is not told again as a plain transfer (a winter transfer is the checkpoint's).
        val deals = listOf(
            e("transfer", listOf("a1", "c1", "c2"), listOf("300", "1")), e("transfer", listOf("a2", "c1", "c2"), listOf("40")),
            e("release_clause", listOf("a1", "c1", "c2"), listOf("300")),
        )
        assertEquals(deals.drop(1), NewsBudget.rollover(deals, 200))
        val winter = e("transfer", listOf("a1", "c1", "c2"), listOf("300", "0", "W"), week = 22)
        assertEquals(listOf(winter, deals[2]), NewsBudget.rollover(listOf(winter, deals[2]), 200))
    }

    /** A transfer whose market single the budget leaves out is still told; one deal two singles tell is told by the better ranked. */
    @Test
    fun aDealIsToldOnceNeverTwiceNorNever() {
        // Twelve singles more important than the clause: at 200 members (10) it is left out, so its transfer stays.
        val ahead = (1..12).map { e("administration", listOf("c$it")) }
        val transfer = e("transfer", listOf("a2", "c3", "c4"), listOf("300"))
        val clause = e("release_clause", listOf("a2", "c3", "c4"), listOf("300"))
        val small = NewsBudget.rollover(listOf(transfer) + ahead + clause, 200)
        assertTrue(transfer in small && clause !in small, small.map { it.kind }.toString())
        assertEquals(small, NewsBudget.rollover(small, 200))
        // With room for it, the clause tells the deal and the transfer goes.
        val big = NewsBudget.rollover(listOf(transfer) + ahead + clause, 3000)
        assertTrue(transfer !in big && clause in big, big.map { it.kind }.toString())
        // A clause paid by a rival: the rival move (the table's earlier kind) tells it, the clause goes; a record fee to a rival is the record's.
        val rival = e("rival_move", listOf("a2", "c3", "c4"), listOf("300"))
        assertEquals(listOf(rival), NewsBudget.rollover(listOf(rival, clause), 1000))
        assertEquals(listOf(rival), NewsBudget.rollover(listOf(clause, rival), 1000))
        val record = e("record_fee", listOf("a2", "c3", "c4"), listOf("300", "200", "KR"))
        val once = NewsBudget.rollover(listOf(transfer, rival, record, clause), 1000)
        assertEquals(listOf(record), once)
        assertEquals(once, NewsBudget.rollover(once, 1000))
        // Other deals of the same player (another club) are other stories.
        val other = e("rival_move", listOf("a2", "c5", "c6"), listOf("90"))
        assertEquals(listOf(record, other), NewsBudget.rollover(listOf(record, other), 1000))
    }

    /** A club world's digest names six clubs of its own most off-seasons: the clubs left out still get named, after its first three. */
    @Test
    fun theClubsLeftOutAreNamedInAFullDigest() {
        val digest = e("club_world_digest", listOf("c1", "c2", "c3", "c4", "c5", "c6"), listOf("34", "2", "3"))
        val ahead = (1..12).map { e("one_club_man", listOf("p$it", "c$it"), listOf("14", "-")) }
        val life = listOf(e("sponsor_exit", listOf("c8"), listOf("휘담전자", "0")), e("fan_protest", listOf("c9", "k9"), listOf("18")))
        val out = NewsBudget.rollover(listOf(digest) + ahead + life, 200)
        val named = out.single { it.kind == NewsBudget.CLUB_DIGEST }
        assertEquals(listOf("c1", "c2", "c3", "c8", "c9", "c4"), named.keys)
        assertEquals(digest.args, named.args)
        assertTrue(life.none { it in out })
        assertEquals(out, NewsBudget.rollover(out, 200))
        // A quiet off-season (everything told) leaves its digest as it was.
        assertEquals(listOf(digest) + life, NewsBudget.rollover(listOf(digest) + life, 200))
    }

    @Test
    fun theSeasonsMarketNewsIsOneAWeekAndTwelveASeason() {
        // Two market_week a week for 20 weeks: one a week, 12 in all, the first of each week.
        val weeks = (26..45).flatMap { w -> listOf(e("market_week", listOf("a$w", "c1", "c2"), listOf("$w", "65", "2"), w), e("market_week", listOf("b$w", "c2", "c1"), listOf("$w", "40", "2"), w)) }
        val out = NewsBudget.inSeason(weeks, winterWeek = 22)
        assertEquals((26..37).toList(), out.map { it.week })
        assertTrue(out.all { it.keys[0].startsWith("a") })
        // Told in pieces (a week at a time, the season's earlier ones told before) gives the same.
        val told = ArrayList<LeagueEvent>()
        for (w in 26..45) told += NewsBudget.inSeason(weeks.filter { it.week == w }, 22, told)
        assertEquals(out, told)
        // The season's articles count as well (a restart reads them from the news book); another season's do not.
        val articles = out.take(11).map { News.article(it, { k -> k }) }
        assertEquals(1, NewsBudget.inSeason(weeks.filter { it.week > 37 }, 22, NewsBudget.toldOf(articles)).size)
        val lastSeason = out.map { News.article(it.copy(season = 3), { k -> k }) }
        assertEquals(12, NewsBudget.inSeason(weeks, 22, NewsBudget.toldOf(lastSeason)).size)
    }

    @Test
    fun derbiesAreOneAWeekSixASeasonInTheBestLeaguesOnly() {
        fun derby(w: Int, league: String) = e("derby_result", listOf("a$w$league", "b$w", "c1", "c2"), listOf("2-0", "1", "0", "0", league), w, comp = Comp.LEAGUE)
        val stream = (1..12).flatMap { w -> listOf(derby(w, "X"), derby(w, "KR")) }
        // Only the best-ranked leagues' (here: not "X"), one a week, six a season.
        val out = NewsBudget.inSeason(stream, 22) { it.args[4] != "X" }
        assertEquals((1..6).toList(), out.map { it.week })
        assertTrue(out.all { it.args[4] == "KR" })
        assertEquals((1..6).toList(), NewsBudget.inSeason(stream, 22).map { it.week })
        assertTrue(NewsBudget.inSeason(stream, 22).all { it.args[4] == "X" })
    }

    @Test
    fun theWinterWeekTellsFiveOfItsMarketsNews() {
        val w = 22
        val checkpoint = listOf(
            e("winter_window", listOf("a1", "a2"), listOf("9", "a1", "c3", "c4", "120"), w),
            e("coach_sacked", listOf("c1", "k1", "k2"), listOf("7", "3", "1", "0"), w), e("coach_sacked", listOf("c2", "k3"), listOf("6", "4", "1", "0"), w),
            e("coach_digest", listOf("c1", "c2", "c3"), listOf("3", "2", "0", "3"), w),
            e("record_fee", listOf("a1", "c3", "c4"), listOf("120", "100", "KR"), w), e("transfer", listOf("a2", "c4", "c3"), listOf("40", "0", "W"), w),
            e("fan_protest", listOf("c5", "k5"), listOf("18"), w),
        )
        // The world's and the media's winter news are no market news: they pass.
        val world = listOf(LeagueEvent("superleague_announce", 4, listOf("c1", "c2"), listOf("2"), null, w), LeagueEvent("wc_bids", 4, listOf("KR", "JP"), listOf("5"), Comp.WORLD_CUP, w))
        val out = NewsBudget.inSeason(checkpoint + world, w)
        assertEquals(checkpoint.take(NewsBudget.WINTER) + world, out)
        // The same kinds in another week are not the winter's.
        val later = checkpoint.map { it.copy(week = w + 1) }
        assertEquals(later, NewsBudget.inSeason(later, w))
        // Told before (a week closed again after a kill): nothing more.
        assertEquals(world, NewsBudget.inSeason(checkpoint + world, w, out))
    }

    @Test
    fun articlesToldAreReadBackAsWhatTheyCount() {
        val a = Article(4, "market_week", "h", "b", listOf("a1", "c1", "c2"), Comp.CLUB, 2, 30)
        assertEquals(listOf(LeagueEvent("market_week", 4, listOf("a1", "c1", "c2"), emptyList(), Comp.CLUB, 30)), NewsBudget.toldOf(listOf(a)))
    }
}

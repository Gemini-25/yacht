package yacht.engine

import yacht.engine.league.Awards
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubTable
import yacht.engine.league.Evolution
import yacht.engine.league.Expectation
import yacht.engine.league.Honour
import yacht.engine.league.HonourBook
import yacht.engine.league.HonourKind
import yacht.engine.league.Journalist
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Leagues
import yacht.engine.league.Media
import yacht.engine.league.NotableMove
import yacht.engine.league.PressAwards
import yacht.engine.league.PressInput
import yacht.engine.league.PressJury
import yacht.engine.league.PressTally
import yacht.engine.league.PressVoteRow
import yacht.engine.league.PressVotes
import yacht.engine.league.Season
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StatsBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The press votes (기자단 투표, design ENG 5.4): the jury by the league ranking, each journalist's
 * tastes from its own stream, ballots of 5-3-1 points, the three awards' candidates, the tie-breaks,
 * the honours they give through the close's one `honours.add`, the kinds apart from the season's
 * awards, and the tallies' `pv` lines in stats.txt.
 */
class PressVoteTest {
    @Test
    fun theJuryHasThreeSeatsForEachOfTheBestEightLeaguesAndOneForTheNextSixteen() {
        val ids = (1..30).map { "L%02d".format(it) }
        val jury = PressJury.of(ids, 5, 11L) { "N" + it.drop(1) }
        assertEquals(40, jury.size)
        assertEquals((0 until 40).toList(), jury.map { it.index })
        assertEquals(ids.take(8).flatMap { listOf(it, it, it) } + ids.subList(8, 24), jury.map { it.league })
        assertEquals(jury.map { "N" + it.league.drop(1) }, jury.map { it.nation })
        assertEquals(26, PressJury.of(ids.take(10), 5, 11L) { it }.size)
        assertEquals(15, PressJury.of(ids.take(5), 5, 11L) { it }.size)
        assertTrue(PressJury.of(emptyList(), 5, 11L) { it }.isEmpty())
        for (j in jury) {
            assertTrue(j.aRating in 0.6..1.4 && j.aTitles in 0.6..1.4 && j.aStats in 0.4..1.0 && j.aHome in 0.2..0.6, j.toString())
        }
        // Each journalist's own stream: the same in a smaller jury, another season another taste.
        assertEquals(jury.take(26), PressJury.of(ids.take(10), 5, 11L) { "N" + it.drop(1) })
        assertNotEquals(jury.map { it.aRating }, PressJury.of(ids, 6, 11L) { "N" + it.drop(1) }.map { it.aRating })
        assertEquals(40, jury.map { it.aRating }.distinct().size)
    }

    @Test
    fun aRegionalLeaguesJournalistsAreOfItsLargestNation() {
        val kr = (1..6).map { "kr$it" }
        val xas = (1..6).map { "xa$it" }
        val nation = mapOf("xa1" to "VN", "xa2" to "VN", "xa3" to "VN", "xa4" to "TH", "xa5" to "TH", "xa6" to "ID") + kr.associateWith { "KR" }
        val plan = SeasonPlan(Season.start(1, 3L, listOf(kr, xas), true, Leagues.national(listOf("KR", "XAS"), listOf(1, 1))), LeagueConfig(national = true))
        val jury = PressJury.of(plan, { k -> if (k.startsWith("kr")) 1600.0 else 1500.0 }, { nation[it] })
        assertEquals(listOf("KR", "KR", "KR", "XAS", "XAS", "XAS"), jury.map { it.league })
        assertEquals(listOf("KR", "KR", "KR", "VN", "VN", "VN"), jury.map { it.nation })
    }

    /** Club c<i>'s member p<i>x<j> is rated 1700 − 40 i − 5 j; JP's members (no club) 1650 − 30 j. */
    private fun rating(k: String): Double =
        if (k.startsWith("jp")) 1650.0 - 30 * k.drop(2).toInt() else 1700.0 - 40 * k.removePrefix("p").substringBefore("x").toInt() - 5 * k.substringAfter("x").toInt()

    /** The season's start was 30 lower for club c02's, 30 higher for the rest. */
    private fun drawn(k: String): Double = rating(k) + if (k.startsWith("p2x")) -30.0 else 30.0

    private class Close(val book: ClubBook, val plan: SeasonPlan, val honours: List<Honour>)

    /** Five KR clubs of 3 with their coaches, a JP league of 6 without clubs, played through; last off-season's notable transfers. */
    private fun close(): Close {
        val lg = Coaching.league(5, per = 3)
        val book = lg.book
        for ((i, c) in lg.ids.withIndex()) Coaching.coach(book, "k${i + 1}", c, reputation = 40 + 10 * i)
        val jp = (1..6).map { "jp$it" }
        val plan = Eco.plan(1, listOf("KR" to lg.members.values.flatten(), "JP" to jp))
        Hist.play(plan.league)
        val w = book.world!!
        w.market.notable += NotableMove(0, 'T', "p2x1", "c05", "c02", 80, "")
        w.market.notable += NotableMove(0, 'T', "p4x2", "c01", "c04", 40, "V")
        w.market.notable += NotableMove(0, 'W', "p3x1", "c01", "c03", 90, "")
        w.market.notable += NotableMove(0, 'A', "p5x3", null, "c05", 0, "")
        w.market.notable += NotableMove(-1, 'T', "p1x2", "c02", "c01", 99, "")
        val honours = listOf(
            Honour(1, HonourKind.of("L0", "KR"), "p1x1"), Honour(1, HonourKind.of(Awards.LMVP, "KR"), "p1x1"),
            Honour(1, HonourKind.of(Awards.LMVP, "JP"), "jp3"), Honour(1, "CL", "p2x2"), Honour(1, "CUP", "p3x3"), Honour(1, "MVP", "p1x1"),
        )
        return Close(book, plan, honours)
    }

    private fun input(c: Close, clubs: Boolean = true, seed: Long = c.plan.seed, gone: Set<String> = emptySet()) = PressInput(
        c.plan, c.honours, ::rating, ::drawn, { k -> 7.0 - (rating(k) - 1500) / 100 }, mapOf("p1x1" to 4, "p2x2" to 2),
        { k -> if (k.startsWith("jp")) "JP" else "KR" }, if (clubs) c.book else null, seed, gone,
    )

    @Test
    fun ballotsGiveFiveThreeAndOnePointsAndEveryTallyIsKept() {
        val c = close()
        val tallies = PressVotes.vote(input(c))
        assertEquals(listOf(PressAwards.GOLDEN_DICE, PressAwards.COACH, PressAwards.SIGNING), tallies.map { it.award })
        val jury = PressJury.of(c.plan, ::drawn, { k -> if (k.startsWith("jp")) "JP" else "KR" }).size
        assertEquals(6, jury)
        for (t in tallies) {
            assertEquals(1, t.season)
            assertEquals(jury, t.jury)
            if (t.rows.size >= 3) assertEquals(9 * jury, t.rows.sumOf { it.points }, t.award)
            assertEquals(jury, t.rows.sumOf { it.firsts })
            assertTrue(t.rows.size <= PressVotes.CANDIDATES)
            // The winner first: points, then first-place votes, then rating (reputation), then key.
            val tie: (String) -> Double = if (t.award == PressAwards.COACH) { k -> c.book.coach(k)!!.reputation.toDouble() } else ::rating
            assertEquals(t.rows.sortedWith(compareByDescending<PressVoteRow> { it.points }.thenByDescending { it.firsts }.thenByDescending { tie(it.key) }.thenBy { it.key }), t.rows)
        }
        // Pure: the same input, the same tallies.
        assertEquals(tallies, PressVotes.vote(input(c)))
        assertEquals(listOf(PressAwards.GOLDEN_DICE), PressVotes.vote(input(c, clubs = false)).map { it.award })
    }

    @Test
    fun eachAwardHasItsCandidates() {
        val c = close()
        val tallies = PressVotes.vote(input(c)).associateBy { it.award }
        // 골든 다이스: league MVPs, the CL / CCL / CUP / WCW holders, the 5 best-rated.
        val best = c.plan.league.standings.keys.sortedWith(compareByDescending<String> { rating(it) }.thenBy { it }).take(5)
        assertEquals((setOf("p1x1", "jp3", "p2x2", "p3x3") + best), tallies.getValue(PressAwards.GOLDEN_DICE).rows.map { it.key }.toSet())
        // 올해의 감독: the club-table champion's coach, the overachievers', the CL winner's (c02's).
        val book = c.book
        val table = ClubTable.of(c.plan.league, book, "KR", c.honours)
        val expected = Expectation.clubs(c.plan, book, "KR", ::drawn).map { it.first }
        val over = table.withIndex().filter { (i, r) -> expected.indexOf(r.club) > i }.sortedByDescending { (i, r) -> expected.indexOf(r.club) - i }.take(5).map { it.value.club }
        val clubs = setOf(table[0].club, "c02") + over
        assertEquals(clubs.map { book.coachOf(it)!!.id }.toSet(), tallies.getValue(PressAwards.COACH).rows.map { it.key }.toSet())
        // 올해의 영입: the last off-season's paid transfers (no winter move, no free agent, no older season).
        assertEquals(setOf("p2x1", "p4x2"), tallies.getValue(PressAwards.SIGNING).rows.map { it.key }.toSet())
        // A member deleted during the season stands for nothing.
        val without = PressVotes.vote(input(c, gone = setOf("p1x1", "p2x1"))).associateBy { it.award }
        assertTrue(without.getValue(PressAwards.GOLDEN_DICE).rows.none { it.key == "p1x1" })
        assertEquals(listOf("p4x2"), without.getValue(PressAwards.SIGNING).rows.map { it.key })
    }

    @Test
    fun aSigningsClubGainsPlacesInItsOwnTableOnLastSeasonsBestThree() {
        val c = close()
        val tables = ClubTable.all(c.plan.league, c.book, c.honours)
        val kr = tables.getValue("KR").map { it.club }
        val (first, second, third) = kr
        val fifth = kr[4]
        // Last season's best 3 of KR (the `cs` line) and of JP, which lists the 5th first.
        val back = ClubBook.decode(c.book.encode() + "cs 0 KR:$fifth,$second,$first:900;JP:$third,$fifth:800\n")
        assertEquals(listOf(fifth, second, first), back.places(0).first { it.league == "KR" }.clubs)
        val t = ClubTable.all(c.plan.league, back, c.honours)
        // Listed last season in its own table: the places from there (up and down).
        assertEquals(3 - 1, PressVotes.placesGained(back, t, 0, first))
        assertEquals(0, PressVotes.placesGained(back, t, 0, second))
        assertEquals(1 - 5, PressVotes.placesGained(back, t, 0, fifth), "its own table's place, not JP's")
        // Not among last season's best 3 of its own table (JP's list does not count): now 3rd, it came up from the 4th at least.
        assertEquals(1, PressVotes.placesGained(back, t, 0, third))
        // Neither season among the best, or no table last season: not known.
        assertEquals(0, PressVotes.placesGained(back, t, 0, kr[3]))
        assertEquals(0, PressVotes.placesGained(c.book, tables, 0, first))
    }

    @Test
    fun theHonoursComeFromTheTalliesAndGoThroughTheClosesOneAdd() {
        val c = close()
        val tallies = PressVotes.vote(input(c))
        val honours = PressVotes.honours(tallies)
        val gd = tallies.first { it.award == PressAwards.GOLDEN_DICE }.rows[0].key
        val sign = tallies.first { it.award == PressAwards.SIGNING }.rows[0].key
        assertEquals(listOf(Honour(1, "GD", gd), Honour(1, "SIGN", sign)), honours)
        // The close's one add, replayed: nothing twice.
        val book = HonourBook()
        book.add(c.honours + honours)
        val once = book.all.toList()
        book.add(c.honours + PressVotes.honours(PressVotes.vote(input(c))))
        assertEquals(once, book.all)
        assertEquals(1, book.all.count { it.kind == "GD" })
        assertEquals(1, book.all.count { it.kind == "SIGN" })
        // Apart from the season's awards; 올해의 영입 counts for no legend.
        assertTrue(PressAwards.KINDS.none { it in Awards.KINDS || Awards.isAward(it) })
        assertTrue(Evolution.isHonour("GD"))
        assertTrue(!Evolution.isHonour("SIGN"))
        assertTrue(!HonourBook.isTitle("GD") && !HonourBook.isTitle("SIGN"))
        assertTrue(!HonourBook.isMajor(Honour(1, "GD", "x")) && !HonourBook.isMajor(Honour(1, "SIGN", "x")))
    }

    @Test
    fun theTalliesAreSavedWithTheStats() {
        val c = close()
        val tallies = PressVotes.vote(input(c))
        val stats = StatsBook()
        stats.putPressVotes(1, tallies, 5)
        val back = StatsBook.decode(stats.encode())
        assertEquals(tallies, back.pressVotes(1))
        assertTrue(stats.encode().lines().count { it.startsWith("pv 1 ") } == 3)
        // A line by hand reads back as its tally.
        val t = PressTally(4, PressAwards.SIGNING, 40, listOf(PressVoteRow("g00 12", 120, 20), PressVoteRow("g7", 0, 0)))
        assertEquals(t, PressTally.decode(t.encode().trimEnd().split(' ')))
    }

    @Test
    fun theClosesNewsNamesTheGoldenDicesTopThreeAndTheOtherWinners() {
        val c = close()
        val tallies = PressVotes.vote(input(c))
        val e = PressVotes.events(tallies) { k -> c.book.coach(k)?.club }
        assertEquals(listOf("press_award", "press_awards"), e.map { it.kind })
        val gd = tallies[0]
        assertEquals(gd.rows.take(3).map { it.key }, e[0].keys)
        assertEquals(listOf(gd.rows[0].points.toString(), gd.rows[0].firsts.toString(), "6", gd.rows[1].points.toString(), gd.rows[2].points.toString()), e[0].args)
        val coach = tallies[1].rows[0].key
        assertEquals(listOf(c.book.coach(coach)!!.club!!, tallies[2].rows[0].key), e[1].keys)
        assertEquals(listOf(coach), e[1].args)
        assertTrue(e.all { it.week == SeasonCalendar.CLOSING })
        // Without a club world: the golden dice only.
        assertEquals(listOf("press_award"), PressVotes.events(PressVotes.vote(input(c, clubs = false))).map { it.kind })
        assertTrue(PressVotes.events(emptyList()).isEmpty())
    }

    @Test
    fun anotherSeedVotesAnew() {
        val c = close()
        val a = PressVotes.vote(input(c, seed = 1L))
        val b = PressVotes.vote(input(c, seed = 2L))
        assertEquals(a.map { it.rows.map { r -> r.key }.toSet() }, b.map { it.rows.map { r -> r.key }.toSet() })
        assertNotEquals(a.map { t -> t.rows.map { it.points } }, b.map { t -> t.rows.map { it.points } })
        assertTrue(Media.leagues(c.plan, ::drawn).first() == "KR")
        val j: Journalist = PressJury.of(c.plan, ::drawn, { "KR" }).first()
        assertEquals("KR", j.nation)
    }
}

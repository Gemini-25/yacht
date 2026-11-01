package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubHistory
import yacht.engine.league.ClubLegend
import yacht.engine.league.ClubRecord
import yacht.engine.league.ClubRecordKind
import yacht.engine.league.DerbyRecord
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.LeagueFixture
import yacht.engine.league.RetiredNumber
import yacht.engine.league.Rival
import yacht.engine.league.Season
import yacht.engine.league.SeasonLine
import yacht.engine.league.Terms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Played seasons and archive lines for the club history's tests (ClubHistoryTest, DerbyTest, AcademyTest). */
internal object Hist {
    /** A result by the fixture's id: a 2-0 sweep by a, a win by a on one game and a draw, or a level match. */
    fun games(id: Int): List<GameScore> = when (id % 3) {
        0 -> listOf(GameScore(230, 190), GameScore(210, 200))
        1 -> listOf(GameScore(230, 190), GameScore(200, 200))
        else -> listOf(GameScore(230, 190), GameScore(190, 230))
    }

    /** 1 when a wins fixture [id] (as [games] plays it), 0 for a draw. */
    fun aWins(id: Int): Int = if (id % 3 == 2) 0 else 1

    /** Plays [s]'s league fixtures of matchdays up to [last] (every one by default); the fixtures played. */
    fun play(s: Season, last: Int = Int.MAX_VALUE): List<LeagueFixture> {
        val out = ArrayList<LeagueFixture>()
        while (true) {
            val f = s.next(emptySet()) ?: break
            if (f.matchday > last) {
                s.release(f.id)
                break
            }
            s.record(f.id, games(f.id))
            out += f
        }
        return out
    }

    /** A season line of [season] at [club] with [matches] league matches. */
    fun line(season: Int, club: String?, matches: Int = 30): SeasonLine =
        SeasonLine(season, 0, 1, matches / 2, 0, matches - matches / 2, 1500.0, 7.0, "KR", club)

    /** Lines of seasons [from]..[to] at [club]. */
    fun lines(from: Int, to: Int, club: String?, matches: Int = 30): List<SeasonLine> = (from..to).map { line(it, club, matches) }

    /** Terms with [apps], [number] and [since]. */
    fun terms(apps: Int = 0, number: Int = 0, since: Int = 1, academy: String? = null, grad: Int = 0): Terms = Terms(9, 10, 0, number, since, apps, null, academy, grad)
}

/**
 * Club history (design ENG 1b.7, 1b.8): legends by score and eligibility (≤ 5), retired numbers
 * (thresholds, ≤ 3, never handed out again), shirt numbers unique per club, the four club records,
 * the captain's choice, the history step once a season with its news rebuilt, a gone club keeps
 * its legends and records, a reused id purges its lines, loans' bookkeeping and the start.
 */
class ClubHistoryTest {
    private fun book(vararg squads: Pair<String, List<String>>): ClubBook =
        Eco.world(Eco.book(squads.mapIndexed { i, (id, ms) -> Eco.Spec(id, "KR", ms, city = i) }))

    @Test
    fun theLegendScoreAndItsThresholds() {
        // 10 × seasons + 30 × majors + 15 × others + 20 × one-club + apps / 20.
        assertEquals(10 * 11 + 30 * 2 + 15 * 1 + 20 + 450 / 20, ClubHistory.score(11, 2, 1, true, 450))
        assertEquals(60, ClubHistory.score(6, 0, 0, false, 19))
        // At least 6 seasons and a title, 400 apps or 3 seasons as captain.
        assertTrue(ClubHistory.eligible(6, 1, 0, 0))
        assertTrue(ClubHistory.eligible(6, 0, 400, 0))
        assertTrue(ClubHistory.eligible(6, 0, 0, 3))
        assertFalse(ClubHistory.eligible(5, 9, 900, 9))
        assertFalse(ClubHistory.eligible(9, 0, 399, 2))
        // A number is retired at 10 seasons and 120 (one-club: 100).
        assertTrue(ClubHistory.retires(ClubLegend("x", 120, 10, 0, false)))
        assertFalse(ClubHistory.retires(ClubLegend("x", 119, 10, 0, false)))
        assertTrue(ClubHistory.retires(ClubLegend("x", 100, 10, 0, true)))
        assertFalse(ClubHistory.retires(ClubLegend("x", 200, 9, 3, true)))
        // 원클럽맨: 10 seasons or more, every one with a club at the same club (seasons before clubs do not count).
        assertTrue(ClubHistory.oneClub(Hist.lines(1, 2, null) + Hist.lines(3, 12, "c01"), "c01"))
        assertFalse(ClubHistory.oneClub(Hist.lines(3, 11, "c01"), "c01"))
        assertFalse(ClubHistory.oneClub(Hist.lines(1, 1, "c02") + Hist.lines(2, 12, "c01"), "c01"))
        // The stint the lines end with.
        assertEquals(4 to 90, ClubHistory.stint(Hist.lines(1, 3, "c02") + Hist.lines(4, 6, "c01"), "c01", 6))
        assertNull(ClubHistory.stint(Hist.lines(1, 3, "c01") + Hist.lines(4, 6, "c02"), "c01", 6))
    }

    @Test
    fun aMemberLeavingBecomesALegendAndTheBestFiveStay() {
        val book = book("c01" to (1..8).map { "p$it" })
        val w = book.world!!
        val major = listOf(Honour(5, "L0.KR", "p1"), Honour(6, "CUP", "p1"), Honour(7, "L1.KR", "p1"), Honour(9, "SCORER", "p1"), Honour(3, "CUP", "p1"))
        // p1: 11 seasons at c01, all its career (a one-club man), two major titles and a 2부 title there (season 3's cup was elsewhere).
        w.terms["p1"] = Hist.terms(apps = 450, number = 10)
        val lines1 = Hist.lines(1, 2, "c02") + Hist.lines(4, 14, "c01")
        val d = assertNotNull(ClubHistory.leave(book, "p1", "c01", 14, lines1, major, retiring = true))
        val legend = assertNotNull(d.legend)
        assertEquals(11, legend.seasons)
        assertEquals(3, legend.titles)
        assertFalse(legend.oneClub, "it played for c02 before")
        assertEquals(ClubHistory.score(11, 2, 1, false, 450), legend.score)
        assertEquals(14 to 10, legend.season to legend.number)
        assertEquals(RetiredNumber(10, "p1", 14), d.retired, "11 seasons and ${legend.score} points")
        // Asked again (a replayed step) it stays one legend and one number.
        ClubHistory.leave(book, "p1", "c01", 14, lines1, major, retiring = true)
        assertEquals(1, book.life("c01")!!.legends.size)
        assertEquals(1, book.life("c01")!!.retired.size)
        // A member without a title, apps or the armband is no legend; one moving away is never a one-club man.
        w.terms["p2"] = Hist.terms(apps = 100, number = 7)
        assertNull(ClubHistory.leave(book, "p2", "c01", 14, Hist.lines(5, 14, "c01"), emptyList(), retiring = true)!!.legend)
        w.terms["p3"] = Hist.terms(apps = 420, number = 8)
        val moved = ClubHistory.leave(book, "p3", "c01", 14, Hist.lines(3, 14, "c01"), emptyList(), retiring = false)!!.legend!!
        assertFalse(moved.oneClub)
        // A one-club legend retires its number at 100 points; others need 120.
        w.terms["p4"] = Hist.terms(apps = 0, number = 9)
        w.state("c01").captain = "p4"
        w.history.captainSince["c01"] = 4
        assertEquals(10, ClubHistory.captainSeasons(book, "p4", 13))
        val one = ClubHistory.leave(book, "p4", "c01", 13, Hist.lines(4, 13, "c01"), emptyList(), retiring = true)!!
        assertTrue(one.captain && one.legend!!.oneClub)
        assertEquals(10 * 10 + 20, one.legend!!.score)
        assertEquals(9, one.retired!!.number)
        assertNull(w.clubs["c01"]!!.captain, "the armband is free")
        // The best five by score stay; a sixth below them is no legend.
        for ((i, k) in listOf("p5", "p6", "p7").withIndex()) {
            w.terms[k] = Hist.terms(apps = 500 + i, number = 20 + i)
            assertNotNull(ClubHistory.leave(book, k, "c01", 15, Hist.lines(1, 15, "c01"), listOf(Honour(10, "L0.KR", k)), retiring = true)!!.legend)
        }
        val five = book.life("c01")!!.legends
        assertEquals(5, five.size)
        assertEquals(five.sortedByDescending { it.score }, five)
        w.terms["p8"] = Hist.terms(apps = 400, number = 30)
        assertNull(ClubHistory.leave(book, "p8", "c01", 15, Hist.lines(9, 15, "c01"), emptyList(), retiring = true)!!.legend)
        assertFalse(book.life("c01")!!.legends.any { it.key == "p8" })
        // At most three numbers are retired: p5..p7 all qualify, only the third number goes up.
        val numbers = book.life("c01")!!.retired
        assertEquals(3, numbers.size)
        assertEquals(listOf(10, 9, 20), numbers.map { it.number })
        // A retired number is never handed out again there.
        for (k in (1..40).map { "new$it" }) {
            assertTrue(ClubHistory.number(book, "c01", k, star = true) !in setOf(10, 9, 20))
            assertTrue(ClubHistory.number(book, "c01", k, star = false) !in setOf(10, 9, 20))
        }
    }

    @Test
    fun shirtNumbersAreUniqueWithinAClub() {
        val keys = (1..45).map { "s$it" }
        val book = book("c01" to keys, "c02" to listOf("t1"))
        val stars = setOf("s1", "s2", "s3")
        for (k in keys) assertNotNull(ClubHistory.join(book, k, "c01", 3, 5, 10, k in stars))
        val numbers = keys.map { book.terms(it)!!.number }
        assertEquals(numbers.size, numbers.toSet().size, "unique")
        assertTrue(numbers.all { it in 2..99 })
        // Stars ask for 7, 10, 9, 11, 8, 23 first; everyone else for 2..30 before 31..99.
        for (k in stars) assertTrue(book.terms(k)!!.number in ClubHistory.STAR_NUMBERS, k)
        val firstOthers = keys.drop(3).take(20).map { book.terms(it)!!.number }
        assertTrue(firstOthers.all { it in 2..30 }, "$firstOthers")
        // Another club hands out its own numbers; the same key asks the same order everywhere.
        assertEquals(ClubHistory.preferences(book.seed, "s9", false).first(), ClubHistory.number(book, "c02", "s9", star = false))
        // Joining keeps the academy a member came out of.
        book.world!!.terms["t1"] = Hist.terms(academy = "c02", grad = 2)
        val t = ClubHistory.join(book, "t1", "c02", 4, 6, 12, star = false)!!
        assertEquals("c02" to 2, t.academy to t.gradSeason)
        assertEquals(Terms(6, 12, 0, t.number, 4, 0, null, "c02", 2), t)
        // Duplicated numbers (terms from elsewhere) are fixed, the longer-serving keeping theirs.
        val w = book.world!!
        w.terms["s44"] = w.terms["s44"]!!.copy(number = w.terms["s1"]!!.number, since = 9)
        w.terms.remove("s45")
        assertEquals(2, ClubHistory.newcomers(book, 5, emptySet(), { 7 }, { _, _ -> 3 }))
        val after = keys.map { book.terms(it)!!.number }
        assertEquals(after.size, after.toSet().size)
        assertEquals(numbers[0], after[0], "s1 keeps its number")
        assertEquals(Terms(7, 3, 0, book.terms("s45")!!.number, 5, 0, null, null, 0), book.terms("s45"))
    }

    @Test
    fun theHistoryStepFoldsAppsRecordsDeparturesAndCaptainsOnce() {
        val a = listOf("a1", "a2", "a3", "a4")
        val b = listOf("b1", "b2", "b3", "b4")
        val book = book("c01" to a, "c02" to b)
        val w = book.world!!
        for ((i, k) in (a + b).withIndex()) w.terms[k] = Hist.terms(apps = if (k == "a1") 400 else 10 * i, number = 2 + i, since = if (k == "a2") 1 else 3)
        val plan = Eco.plan(12, listOf("KR" to a + b))
        Hist.play(plan.league)
        val played = (a + b).associateWith { plan.league.standings.getValue(it).played }
        // a1 retires after 10 seasons at c01, all its career, as its captain since season 4.
        w.state("c01").captain = "a1"
        w.history.captainSince["c01"] = 4
        val honours = listOf(Honour(12, "L0.KR", "b1"), Honour(11, "CUP", "b1"), Honour(10, "CUP", "a3"))
        val lines = mapOf("a1" to Hist.lines(3, 12, "c01"), "b1" to Hist.lines(9, 12, "c02"), "a3" to Hist.lines(10, 12, "c01"))
        val input = Eco.input(book, honours = honours).let { yacht.engine.league.ClubInput((a + b) - "a1", it.rating, it.nation, it.config, honours) }
        val v = book.version
        val report = ClubHistory.close(book, plan, input) { lines[it].orEmpty() }
        assertNotEquals(v, book.version)
        // Apps: each member's league matches this season added.
        for (k in a + b) assertEquals((if (k == "a1") 400 else 10 * (a + b).indexOf(k)) + played.getValue(k), w.terms[k]!!.apps, k)
        // Records: the most apps and the most titles there.
        val rc = book.life("c01")!!.records
        assertEquals(ClubRecord(ClubRecordKind.APPS, "a1", 400 + played.getValue("a1"), 12), rc.first { it.kind == ClubRecordKind.APPS })
        assertEquals(ClubRecord(ClubRecordKind.TITLES, "a3", 1, 12), rc.first { it.kind == ClubRecordKind.TITLES })
        assertEquals(2, book.life("c02")!!.records.first { it.kind == ClubRecordKind.TITLES }.value)
        // a1: a one-club legend with its number retired; the armband goes to the longest-serving member who stays (a2, since 1).
        assertEquals(listOf("c01" to "a1"), report.legends.map { it.first to it.second.key })
        assertTrue(report.legends.single().second.oneClub)
        assertEquals(listOf("c01" to RetiredNumber(2, "a1", 12)), report.retired)
        assertEquals("a2", w.clubs["c01"]!!.captain)
        assertEquals(13, w.history.captainSince["c01"])
        // c02 had none: the longest-serving (all since 3), then the most apps: b4.
        assertEquals("b4", w.clubs["c02"]!!.captain)
        assertEquals(listOf("c01" to "a2", "c02" to "b4"), report.captains)
        // The news: one-club man (with the number), the captains of the news clubs.
        val events = report.events(newsClubs = listOf("c02", "c01"))
        assertEquals(listOf("one_club_man", "captain", "captain"), events.map { it.kind })
        assertEquals(listOf("a1", "c01"), events[0].keys)
        assertEquals(listOf("10", "2"), events[0].args)
        assertEquals(listOf("b4", "c02"), events[1].keys)
        assertTrue(report.events().none { it.kind == "captain" }, "no news clubs, no captain news")
        // Asked again: nothing folds twice, the same report.
        val again = ClubHistory.close(book, plan, input) { lines[it].orEmpty() }
        assertEquals(report, again)
        for (k in a + b) assertEquals((if (k == "a1") 400 else 10 * (a + b).indexOf(k)) + played.getValue(k), w.terms[k]!!.apps, k)
        assertEquals(report, ClubHistory.report(book, 12))
        // The guard and the captaincy are saved.
        val back = ClubBook.decode(book.encode())
        assertEquals(book.encode(), back.encode())
        assertEquals(report, ClubHistory.report(back, 12))
        assertEquals(1, ClubHistory.captainSeasons(back, "a2", 13))
    }

    @Test
    fun feesSetTheSigningAndSaleRecords() {
        val book = book("c01" to listOf("x"), "c02" to listOf("y"))
        ClubHistory.fee(book, "x", "c01", "c02", 120, 3)
        ClubHistory.fee(book, "y", "c01", "c02", 90, 4)
        ClubHistory.fee(book, "z", "c01", null, 120, 5)
        assertEquals(ClubRecord(ClubRecordKind.SIGNING, "x", 120, 3), book.life("c01")!!.records.single())
        assertEquals(ClubRecord(ClubRecordKind.SALE, "x", 120, 3), book.life("c02")!!.records.single())
        ClubHistory.fee(book, "y", "c02", "c01", 121, 6)
        assertEquals(ClubRecord(ClubRecordKind.SALE, "y", 121, 6), book.life("c01")!!.records.first { it.kind == ClubRecordKind.SALE })
    }

    @Test
    fun captainsAreTheLongestServing() {
        val book = book("c01" to listOf("m1", "m2", "m3", "m4"))
        val w = book.world!!
        w.terms["m1"] = Hist.terms(apps = 50, since = 4)
        w.terms["m2"] = Hist.terms(apps = 80, since = 4)
        w.terms["m3"] = Hist.terms(apps = 80, since = 4)
        // m4 has no terms (a newcomer): it counts from the captaincy's first season.
        val ratings = mapOf("m2" to 1500.0, "m3" to 1600.0)
        assertEquals(listOf("c01" to "m3"), ClubHistory.captains(book, 7, { ratings[it] ?: Double.NaN }))
        assertEquals(7, w.history.captainSince["c01"])
        // Kept while a member; nothing to do again.
        assertTrue(ClubHistory.captains(book, 8, { 0.0 }).isEmpty())
        assertEquals(2, ClubHistory.captainSeasons(book, "m3", 8))
        assertEquals(0, ClubHistory.captainSeasons(book, "m2", 8))
        // Re-chosen when the captain leaves.
        book.place("m3", null)
        assertEquals(listOf("c01" to "m2"), ClubHistory.captains(book, 9, { ratings[it] ?: Double.NaN }))
        assertEquals("m2", book.life("c01")!!.captain)
    }

    @Test
    fun aGoneClubKeepsItsHistoryAndAReusedIdStartsClean() {
        val book = book("c01" to listOf("a"), "c02" to listOf("b"), "c03" to listOf("c"))
        val h = book.world!!.history
        h.legends["c02"] = listOf(ClubLegend("old", 150, 12, 2, true, 8, 10))
        h.retired["c02"] = listOf(RetiredNumber(10, "old", 8))
        h.records["c02"] = listOf(ClubRecord(ClubRecordKind.APPS, "old", 500, 8))
        h.captainSince["c02"] = 5
        h.folded["c02"] = 8
        h.rivals["c02"] = listOf(Rival("c01", 'N', 1))
        h.rivals["c01"] = listOf(Rival("c02", 'N', 1), Rival("c03", 'R', 2))
        h.rivals["c03"] = listOf(Rival("c01", 'R', 2))
        h.derbies["c01 c02"] = DerbyRecord("c01", "c02", 3, 1, 2, 6, 1, 8)
        // The club's line gone (dissolved): its legends and records stay under its id.
        val text = book.encode().lines().filter { !it.startsWith("club c02 ") }.joinToString("\n")
        val gone = ClubBook.decode(text)
        assertNull(gone.club("c02"))
        assertEquals(listOf("old"), gone.world!!.history.legends["c02"]!!.map { it.key })
        assertEquals(500, gone.world!!.history.records["c02"]!!.single().value)
        // A reused id: every per-id line goes, and the others' rivalries with it.
        ClubHistory.purge(gone, "c02")
        val g = gone.world!!.history
        assertTrue(listOf(g.legends, g.retired, g.records, g.captainSince, g.folded, g.rivals).none { "c02" in it.keys })
        assertTrue(g.derbies.isEmpty())
        assertEquals(listOf(Rival("c03", 'R', 2)), gone.rivals("c01"))
        assertTrue(ClubBook.decode(gone.encode()).encode().lines().none { it.split(' ').getOrNull(1) == "c02" && it.split(' ')[0] in setOf("lg", "rn", "cr", "rv", "dr") })
    }

    @Test
    fun theNewTokensReadBackAndOlderLinesStillRead() {
        val book = book("c01" to listOf("a"), "c02" to listOf("b"))
        val h = book.world!!.history
        h.legends["c01"] = listOf(ClubLegend("a9", 140, 11, 3, true, 9, 7))
        h.captainSince["c02"] = 4
        h.folded["c01"] = 9
        val text = book.encode()
        assertTrue(text.contains("\nlg c01 a9:140:11:3:1:9:7\n"), text)
        assertTrue(text.contains("\ncr c01 - 0 9\n"), text)
        assertTrue(text.contains("\ncr c02 - 4 0\n"), text)
        assertEquals(text, ClubBook.decode(text).encode())
        // A line written before the tokens: no season, number, captaincy or guard.
        val older = text.replace("lg c01 a9:140:11:3:1:9:7", "lg c01 a9:140:11:3:1").replace("cr c02 - 4 0", "cr c02 A:a:5:2")
        val back = ClubBook.decode(older)
        assertEquals(ClubLegend("a9", 140, 11, 3, true), back.life("c01")!!.legends.single())
        assertEquals(ClubRecord(ClubRecordKind.APPS, "a", 5, 2), back.life("c02")!!.records.single())
        assertNull(back.world!!.history.captainSince["c02"])
    }

    @Test
    fun loansKeepTheBooks() {
        val book = book("c01" to listOf("y1", "y2"), "c02" to listOf("z1"))
        val w = book.world!!
        w.terms["y1"] = Terms(6, 8, 50, 10, 2, 40, null, "c01", 2)
        w.terms["z1"] = Hist.terms(number = 10)
        assertTrue(ClubHistory.loanOut(book, "y1", "c02", star = false))
        assertEquals("c02", book.clubOf("y1"))
        val t = book.terms("y1")!!
        assertEquals("c01", t.loanFrom)
        assertEquals(listOf(6, 8, 50, 40, 2), listOf(t.until, t.wage, t.clause, t.apps, t.since))
        assertNotEquals(10, t.number, "c02's 10 is taken")
        assertEquals(listOf("y1"), ClubHistory.loanedOut(book, "c01"))
        assertFalse(ClubHistory.loanOut(book, "y1", "c01", star = false), "on loan already")
        // Back home: its own number again.
        assertEquals("c01", ClubHistory.loanBack(book, "y1", star = false))
        assertEquals("c01", book.clubOf("y1"))
        assertNull(book.terms("y1")!!.loanFrom)
        assertEquals(ClubHistory.number(book, "c01", "y1", false), book.terms("y1")!!.number)
        assertNull(ClubHistory.loanBack(book, "y1", star = false))
        // Bought for good by the loan club.
        assertTrue(ClubHistory.loanOut(book, "y2".also { w.terms[it] = Hist.terms() }, "c02", star = false))
        assertTrue(ClubHistory.loanBuy(book, "y2"))
        assertNull(book.terms("y2")!!.loanFrom)
        assertTrue(ClubHistory.loanedOut(book, "c01").isEmpty())
        // The club that loaned it out is gone: it stays at its loan club, on loan from nobody.
        val gone = book("c01" to listOf("g1"), "c02" to listOf("h1"))
        gone.world!!.terms["g1"] = Hist.terms()
        assertTrue(ClubHistory.loanOut(gone, "g1", "c02", star = false))
        val left = ClubBook.decode(gone.encode().lines().filter { !it.startsWith("club c01 ") }.joinToString("\n"))
        assertNull(ClubHistory.loanBack(left, "g1", star = false))
        assertEquals("c02", left.clubOf("g1"))
        assertNull(left.terms("g1")!!.loanFrom)
        // Without a club world nothing happens.
        val plain = Eco.book(listOf(Eco.Spec("c01", "KR", listOf("q"))))
        assertFalse(ClubHistory.loanOut(plain, "q", "c01", false))
        assertNull(ClubHistory.leave(plain, "q", "c01", 3, emptyList(), emptyList(), true))
    }

    @Test
    fun aMemberOnLoanIsNoCaptainThereAndItsLoanMatchesCountForNoClub() {
        val a = listOf("a1", "a2", "a3")
        val b = listOf("b1", "b2")
        val book = book("c01" to a, "c02" to b)
        val w = book.world!!
        // a1: at c01 since season 1 with 300 apps; c02's members only since 6.
        w.terms["a1"] = Hist.terms(apps = 300, number = 7, since = 1)
        w.terms["a2"] = Hist.terms(apps = 10, number = 8, since = 5)
        w.terms["a3"] = Hist.terms(apps = 5, number = 9, since = 6)
        w.terms["b1"] = Hist.terms(apps = 40, number = 7, since = 6)
        w.terms["b2"] = Hist.terms(apps = 30, number = 8, since = 6)
        assertTrue(ClubHistory.loanOut(book, "a1", "c02", star = false))
        // c02's armband goes to one of its own (a1's `since` is c01's), c01's to a2.
        assertEquals(listOf("c01" to "a2", "c02" to "b1"), ClubHistory.captains(book, 7, { 1500.0 }))
        val plan = Eco.plan(7, listOf("KR" to a + b))
        Hist.play(plan.league)
        val played = plan.league.standings.getValue("a1").played
        assertTrue(played > 0)
        ClubHistory.close(book, plan, yacht.engine.league.ClubInput(a + b, { 1500.0 }, { "KR" }, yacht.engine.league.LeagueConfig())) { emptyList() }
        // Its terms are c01's: the loan matches add nothing to them, and c02's 최다 출전 is one of its own members'.
        assertEquals(300, book.terms("a1")!!.apps)
        assertEquals("b1", book.life("c02")!!.records.first { it.kind == ClubRecordKind.APPS }.key)
        assertEquals(40 + plan.league.standings.getValue("b1").played, book.terms("b1")!!.apps)
        // Only when nobody else can is a member on loan its loan club's captain.
        val solo = book("c01" to listOf("x1"), "c02" to listOf("y1"))
        solo.world!!.terms["x1"] = Hist.terms(since = 2)
        ClubHistory.loanOut(solo, "x1", "c02", star = false)
        solo.place("y1", "c01")
        assertEquals(listOf("c01" to "y1", "c02" to "x1"), ClubHistory.captains(solo, 4, { 1500.0 }))
    }

    @Test
    fun newcomersDropTheTermsOfPlayersWithoutAClubAndChangeNothingTwice() {
        val book = book("c01" to listOf("a", "b"), "c02" to listOf("c"))
        val w = book.world!!
        for (k in listOf("a", "b", "c")) ClubHistory.join(book, k, book.clubOf(k)!!, 2, 4, 10, star = false, academy = "c01")
        // b is released: until the off-season's terms step it keeps its terms (the draft reads its academy).
        book.place("b", null)
        assertNotNull(w.terms["b"])
        assertEquals(0, ClubHistory.newcomers(book, 3, emptySet(), { 5 }, { _, _ -> 1 }))
        assertNull(w.terms["b"], "the file keeps no terms of a player without a club")
        assertEquals(ClubBook.decode(book.encode()).world!!.terms, w.terms)
        // A club with no number left keeps what its members wear, and asking again changes nothing.
        val full = (1..99).map { "f$it" }
        val big = book("c01" to full)
        for (k in full.dropLast(1)) ClubHistory.join(big, k, "c01", 2, 4, 10, star = false)
        big.world!!.terms["f99"] = Hist.terms(number = 0)
        assertEquals((2..99).toSet(), full.dropLast(1).map { big.terms(it)!!.number }.toSet())
        assertEquals(0, ClubHistory.newcomers(big, 3, emptySet(), { 5 }, { _, _ -> 1 }))
        val v = big.version
        assertEquals(0, ClubHistory.newcomers(big, 3, emptySet(), { 5 }, { _, _ -> 1 }))
        assertEquals(v, big.version)
    }

    @Test
    fun aClubWorldStartsWithTermsRecordsLegendsAndCaptains() {
        val book = book("c01" to listOf("a1", "a2", "a3"), "c02" to listOf("b1", "b2"))
        val lines = mapOf(
            "a1" to Hist.lines(1, 3, "c02") + Hist.lines(4, 9, "c01"),
            "a2" to Hist.lines(7, 9, "c01", matches = 20),
            "b1" to Hist.lines(9, 9, "c02"),
            // Retired after 8 seasons at c01 with a title there: a legend; 12 seasons at c02 before it.
            "old" to Hist.lines(1, 8, "c01", matches = 40),
            "old2" to Hist.lines(1, 12, "c02", matches = 40),
        )
        val honours = listOf(Honour(5, "L0.KR", "old"), Honour(6, "CUP", "a1"), Honour(2, "CUP", "a1"))
        val ratings = mapOf("a1" to 1600.0, "a2" to 1650.0, "a3" to 1500.0, "b1" to 1550.0, "b2" to 1560.0)
        val n = ClubHistory.start(book, 10, lines.keys + listOf("a1", "a2", "a3", "b1", "b2"), { lines[it].orEmpty() }, honours, { ratings[it] ?: Double.NaN }, { 12 }, { 15 })
        assertEquals(5, n)
        // Terms: the stint from the archive, numbers unique, no clause.
        assertEquals(Terms(12, 15, 0, book.terms("a1")!!.number, 4, 180, null, null, 0), book.terms("a1"))
        assertEquals(7 to 60, book.terms("a2")!!.let { it.since to it.apps })
        assertEquals(10 to 0, book.terms("a3")!!.let { it.since to it.apps }, "no lines: from now")
        assertEquals(3, book.clubs.first { it.id == "c01" }.squad.map { book.terms(it)!!.number }.toSet().size)
        // Records the archive reaches: apps (a1's stint 180 against old's 320) and titles at the club.
        val rc = book.life("c01")!!.records
        assertEquals(ClubRecord(ClubRecordKind.APPS, "old", 320, 8), rc.first { it.kind == ClubRecordKind.APPS })
        assertEquals(ClubRecord(ClubRecordKind.TITLES, "a1", 1, 9), rc.first { it.kind == ClubRecordKind.TITLES })
        // Legends: those who left; a1 left c02 after 3 seasons (too few).
        assertEquals(listOf("old"), book.life("c01")!!.legends.map { it.key })
        assertTrue(book.life("c01")!!.legends.single().let { !it.oneClub && it.season == 8 && it.number == 0 })
        val old2 = book.life("c02")!!.legends.single()
        assertEquals("old2", old2.key)
        assertTrue(old2.oneClub, "12 seasons, all at c02, and no club now")
        assertTrue(book.life("c02")!!.retired.isEmpty(), "numbers are not known")
        // Captains: the longest-serving (a1 since 4; c02: b1 since 9 over b2 from now).
        assertEquals("a1" to "b1", book.life("c01")!!.captain to book.life("c02")!!.captain)
        assertEquals(10, book.world!!.history.captainSince["c01"])
        // Terms that exist are kept.
        assertEquals(0, ClubHistory.start(book, 10, emptyList(), { emptyList() }, emptyList(), { 0.0 }, { 1 }, { 1 }))
        assertEquals(15, book.terms("a1")!!.wage)
    }

    @Test
    fun theNewsClubsAreThoseOfTheBestLeagues() {
        val book = book("c01" to listOf("a"), "c02" to listOf("b"))
        val plan = Eco.plan(3, listOf("KR" to listOf("a", "b")))
        assertEquals(listOf("c01", "c02"), ClubHistory.newsClubs(book, plan) { 1500.0 })
        assertTrue(ClubHistory.newsClubs(book, Eco.plan(3, listOf("JP" to listOf("x", "y")))) { 1500.0 }.isEmpty())
    }
}

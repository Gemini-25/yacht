package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubIdentity
import yacht.engine.league.ClubNames
import yacht.engine.league.Comp
import yacht.engine.league.Competition
import yacht.engine.league.DerbyRecord
import yacht.engine.league.Derbies
import yacht.engine.league.Fixture
import yacht.engine.league.Honour
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.Leagues
import yacht.engine.league.MatchRef
import yacht.engine.league.MatchSource
import yacht.engine.league.Nations
import yacht.engine.league.Rival
import yacht.engine.league.RivalLink
import yacht.engine.league.Season
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonMatch
import yacht.engine.league.SeasonPlan
import yacht.engine.league.TieScore
import yacht.engine.league.WinterMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Identity and derbies (design ENG 1b.6): rivals N (a nation's two biggest cities), R (the KR and
 * JP region tables), H (the top two of a club table twice in five seasons, a 클럽 대항전 final) and T
 * (record fees, a captain's move); at most two a club by priority; only H and T fade; the season
 * series folded equals a hand count and a replayed close counts nothing twice; `rivalName` only
 * for 1부 league fixtures of counted members; the week's `derby_result`; founding years, nicknames
 * and colours deterministic.
 */
class DerbyTest {
    /** KR clubs in the cities 서울 부산 인천 대구 광주 대전 (c01..c06) and JP's 도쿄 오사카 요코하마 (c07..c09), with `cs` lines. */
    private fun book(cs: String = ""): ClubBook {
        val specs = (0..5).map { Eco.Spec(ClubBook.idOf(it + 1), "KR", listOf("k$it"), city = it) } +
            (0..2).map { Eco.Spec(ClubBook.idOf(it + 7), "JP", listOf("j$it"), city = it) }
        return Eco.world(ClubBook.decode(Eco.book(specs).encode() + cs))
    }

    /** c05-c06 top two of KR in seasons 4 and 5; c01-c05 in 2 and 3 (c01's two places are taken by then). */
    private val places = "cs 2 KR:c01,c05,c02:900\ncs 3 KR:c01,c05,c02:900\ncs 4 KR:c05,c06,c01:900\ncs 5 KR:c05,c06,c01:900\n"

    private val final = listOf(Honour(4, "CLUBCUP", "c08"), Honour(4, "CLUBCUP2", "c09"))

    /** Captains' moves and a record fee: c05-c06 is a history rivalry already, c02 has two rivals, c01-c04's move is too old. */
    private val links = listOf(
        RivalLink("c03", "c06", 'T', 5, 'K'), RivalLink("c04", "c05", 'T', 4, 'K'), RivalLink("c05", "c06", 'T', 5, 'X'),
        RivalLink("c02", "c06", 'T', 5, 'K'), RivalLink("c01", "c04", 'T', 1, 'K'),
    )

    @Test
    fun theCitiesAndRegionsAreAsTheTablesSay() {
        assertEquals(listOf("서울", "부산", "인천", "대구", "광주", "대전"), ClubNames.cities(Nations.KR).take(6))
        assertEquals(listOf("도쿄", "오사카", "요코하마"), ClubNames.cities(Nations.JP).take(3))
        // Every region's city is a club city of its nation, and in one region only.
        for ((code, regions) in ClubIdentity.REGIONS) {
            val cities = ClubNames.cities(Nations.of(code)!!)
            val all = regions.flatMap { it.cities }
            assertEquals(all.size, all.toSet().size, code)
            assertTrue(cities.containsAll(all), "$code ${all - cities.toSet()}")
        }
        assertEquals("수도권", ClubIdentity.region("KR", "인천")?.name)
        assertNull(ClubIdentity.region("KR", "제주"))
        assertNull(ClubIdentity.region("CN", "베이징"))
    }

    @Test
    fun rivalsFollowTheirKindsAndTwoAClub() {
        val book = book(places)
        val born = Derbies.recompute(book, 5, final, links)
        fun of(id: String) = book.rivals(id).map { it.club to it.kind }
        assertEquals(listOf("c02" to 'N', "c03" to 'R'), of("c01"))
        assertEquals(listOf("c01" to 'N', "c04" to 'R'), of("c02"))
        assertEquals(listOf("c01" to 'R', "c06" to 'T'), of("c03"))
        assertEquals(listOf("c02" to 'R', "c05" to 'T'), of("c04"))
        assertEquals(listOf("c06" to 'H', "c04" to 'T'), of("c05"))
        assertEquals(listOf("c05" to 'H', "c03" to 'T'), of("c06"))
        assertEquals(listOf("c08" to 'N', "c09" to 'R'), of("c07"))
        assertEquals(listOf("c07" to 'N', "c09" to 'H'), of("c08"))
        assertEquals(listOf("c07" to 'R', "c08" to 'H'), of("c09"))
        // Symmetric, at most two, every pair with a series from the next season.
        for (c in book.clubs) {
            assertTrue(book.rivals(c.id).size <= Derbies.MAX)
            for (r in book.rivals(c.id)) {
                assertTrue(book.isRival(r.club, c.id))
                assertEquals(6, r.since)
                assertEquals(DerbyRecord(minOf(c.id, r.club), maxOf(c.id, r.club), 0, 0, 0, 0, 6, 0), book.derby(c.id, r.club))
            }
        }
        // c01-c05 (top two twice) and c02-c06 lost to stronger rivalries; c01-c04's move is too old.
        assertTrue(!book.isRival("c01", "c05") && !book.isRival("c02", "c06") && !book.isRival("c01", "c04"))
        assertEquals(9, born.size)
        // The names.
        assertEquals("서울-부산 클래식", Derbies.name(book, "c02", "c01"))
        assertEquals("수도권 더비", Derbies.name(book, "c01", "c03"))
        assertEquals("영남 더비", Derbies.name(book, "c02", "c04"))
        assertEquals("광주-대전 라이벌전", Derbies.name(book, "c06", "c05"))
        assertEquals("인천-대전 라이벌전", Derbies.name(book, "c03", "c06"))
        assertEquals("도쿄-오사카 클래식", Derbies.name(book, "c07", "c08"))
        assertEquals("간토 더비", Derbies.name(book, "c07", "c09"))
        assertNull(Derbies.name(book, "c01", "c06"))
        // The news: the first history rivalry, with what made it; rebuilt the same from the book.
        val events = Derbies.events(5, born)
        assertEquals(listOf("c05", "c06"), events.single().keys)
        assertEquals(listOf("P"), events.single().args)
        val rebuilt = Derbies.born(book, 5, final, links)
        assertEquals(listOf("c05 c06 P", "c08 c09 F", "c03 c06 K", "c04 c05 K"), rebuilt.map { "${it.a} ${it.b} ${it.why}" })
        assertEquals(events, Derbies.events(5, rebuilt))
        // Again: the same rivals, nothing born, no new version.
        val v = book.version
        assertTrue(Derbies.recompute(book, 5, final, links).isEmpty())
        assertEquals(v, book.version)
        // Saved and read back.
        assertEquals(book.encode(), ClubBook.decode(book.encode()).encode())
    }

    @Test
    fun historyAndTransferRivalriesFadeTheOthersStay() {
        val book = book(places)
        Derbies.recompute(book, 5, final, links)
        // Nine seasons on, nothing new: everything kept, `since` too.
        Derbies.recompute(book, 15)
        assertEquals(listOf("c06" to 'H', "c04" to 'T'), book.rivals("c05").map { it.club to it.kind })
        assertTrue(book.rivals("c05").all { it.since == 6 })
        // A meeting of members keeps c05-c06 alive; c04-c05 (never met) and the other H and T rivalries fade after 10 seasons.
        val w = book.world!!
        w.history.derbies["c05 c06"] = w.history.derbies["c05 c06"]!!.copy(games = 2, winsA = 2, last = 14)
        Derbies.recompute(book, 16)
        assertEquals(listOf(Rival("c06", 'H', 6)), book.rivals("c05"))
        assertEquals(listOf("c01" to 'R'), book.rivals("c03").map { it.club to it.kind })
        assertTrue(book.rivals("c08").none { it.kind == 'H' })
        // National classics and regional derbies never fade.
        assertEquals(listOf(Rival("c02", 'N', 6), Rival("c03", 'R', 6)), book.rivals("c01"))
        assertEquals(listOf(Rival("c08", 'N', 6), Rival("c09", 'R', 6)), book.rivals("c07"))
        // The faded pairs keep their all-time series.
        assertNotNull(book.derby("c04", "c05"))
        // A gone club loses its rivals; its rivals lose it.
        val gone = ClubBook.decode(book.encode().lines().filter { !it.startsWith("club c06 ") }.joinToString("\n"))
        Derbies.recompute(gone, 16)
        assertTrue(gone.rivals("c06").isEmpty())
        assertTrue(gone.rivals("c05").none { it.club == "c06" })
    }

    /** Members a1..a3 of c01 (서울), b1..b3 of c02 (부산), d1..d2 of c03 (인천) in one division of 8, a whole season played. */
    private fun played(): Pair<ClubBook, SeasonPlan> {
        val a = listOf("a1", "a2", "a3")
        val b = listOf("b1", "b2", "b3")
        val d = listOf("d1", "d2")
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", a), Eco.Spec("c02", "KR", b, city = 1), Eco.Spec("c03", "KR", d, city = 2))))
        Derbies.recompute(book, 0, start = 1)
        val plan = Eco.plan(1, listOf("KR" to a + b + d))
        Hist.play(plan.league)
        return book to plan
    }

    /** The series of rival pairs counted by hand over [plan]'s league fixtures through [through], a member's club as [clubAt] says. */
    private fun byHand(book: ClubBook, plan: SeasonPlan, through: Int = Int.MAX_VALUE, clubAt: (String, Int) -> String?): Map<String, List<Int>> {
        val out = HashMap<String, IntArray>()
        val s = plan.league
        for (id in 0 until s.fixturesTotal) {
            val f = s.fixture(id)
            val week = f.matchday + 1
            if (week > through) continue
            val ca = clubAt(f.a, week) ?: continue
            val cb = clubAt(f.b, week) ?: continue
            if (ca == cb || !book.isRival(ca, cb)) continue
            val first = minOf(ca, cb)
            val c = out.getOrPut("$first ${maxOf(ca, cb)}") { IntArray(3) }
            when {
                Hist.aWins(id) == 0 -> c[1]++
                (ca == first) -> c[0]++
                else -> c[2]++
            }
        }
        return out.mapValues { it.value.toList() }
    }

    @Test
    fun theSeasonSeriesFoldEqualsAHandCountAndCountsOnce() {
        val (book, plan) = played()
        // a2 moved from c01 to c02 in the winter after week 6: its matches up to then count for c01.
        book.world!!.winter.moves += WinterMove(1, "a2", "c01", "c02", 6, 0, 0)
        book.place("a2", "c02")
        val clubAt = { k: String, week: Int -> if (k == "a2" && week <= 6) "c01" else book.clubOf(k) }
        val hand = byHand(book, plan, clubAt = clubAt)
        assertTrue(hand.keys.containsAll(listOf("c01 c02", "c01 c03")), "${hand.keys}")
        // This season on request, before and after the fold, through a week too.
        val series = Derbies.series(book, plan)
        assertEquals(hand, series.mapValues { listOf(it.value.winsA, it.value.draws, it.value.winsB) })
        assertEquals(byHand(book, plan, 5, clubAt), Derbies.series(book, plan, through = 5).mapValues { listOf(it.value.winsA, it.value.draws, it.value.winsB) })
        val n = Derbies.fold(book, plan)
        assertEquals(hand.values.sumOf { it.sum() }, n)
        for ((k, c) in hand) {
            val (x, y) = k.split(' ')
            assertEquals(DerbyRecord(x, y, c[0], c[1], c[2], c.sum(), 1, 1), book.derby(x, y))
        }
        // c02-c03 are no rivals: nothing kept for them.
        assertNull(book.derby("c02", "c03"))
        // A replayed close counts nothing twice; the all-time record does not add this season again.
        val text = book.encode()
        assertEquals(0, Derbies.fold(book, plan))
        assertEquals(text, book.encode())
        assertEquals(book.derby("c01", "c02"), Derbies.allTime(book, plan, "c02", "c01"))
        val reread = ClubBook.decode(text)
        assertEquals(0, Derbies.fold(reread, plan))
        assertEquals(text, reread.encode())
    }

    /** A finished competition whose matches are [matches] (for the series: which competitions count). */
    private class Played(override val comp: Comp, private val matches: List<SeasonMatch>) : Competition, MatchSource {
        override fun next(p: Double, busy: Set<String>): Fixture? = null
        override fun release(f: Fixture) {}
        override fun record(r: JobResult): List<LeagueEvent> = emptyList()
        override val finished: Boolean get() = true
        override val fixturesLeft: Int get() = 0
        override val fixturesTotal: Int get() = matches.size
        override fun honours(): List<Honour> = emptyList()
        override fun encode(out: StringBuilder) {}
        override fun seasonMatches(): List<SeasonMatch> = matches
        override fun seasonMatch(id: Int): SeasonMatch? = matches.firstOrNull { it.ref.id == id }

        companion object {
            /** Fixture [id] of [comp] between [a] and [b] won by [winner] (+1 a, −1 b, 0 level), as a team match when [team]. */
            fun match(comp: Comp, id: Int, a: String, b: String, winner: Int, team: Boolean = false): SeasonMatch {
                val f = Fixture(comp, id, a, b, 1L, if (team) JobKind.TEAM else JobKind.MATCH)
                val score = TieScore(if (winner > 0) 2.0 else 0.0, if (winner < 0) 2.0 else 0.0, 400, 380, winner)
                return SeasonMatch(MatchRef(1, comp, id), f, true, score, null, SeasonCalendar.NONE, 0)
            }
        }
    }

    @Test
    fun cupsAndTheClubCupCountInTheSeriesTeamCupsAndFriendliesDoNot() {
        val (book, plan) = played()
        val hand = byHand(book, plan) { k, _ -> book.clubOf(k) }.getValue("c01 c02")
        plan.comps += Played(Comp.CUP, listOf(Played.match(Comp.CUP, 0, "a1", "b1", -1), Played.match(Comp.CUP, 1, "b2", "a3", 0)))
        plan.comps += Played(Comp.CLUB_CUP, listOf(Played.match(Comp.CLUB_CUP, 0, "c01", "c02", 1, team = true)))
        plan.comps += Played(Comp.WORLD_CUP, listOf(Played.match(Comp.WORLD_CUP, 0, "KR", "JP", 1, team = true)))
        plan.comps += Played(Comp.FRIENDLY, listOf(Played.match(Comp.FRIENDLY, 0, "a1", "b2", 1)))
        // The cup: a c02 win and a draw; the 클럽 대항전: a c01 win; the world cup and the friendly nothing.
        assertEquals(listOf(hand[0] + 1, hand[1] + 1, hand[2] + 1), Derbies.series(book, plan).getValue("c01 c02").let { listOf(it.winsA, it.draws, it.winsB) })
        Derbies.fold(book, plan)
        assertEquals(hand.sum() + 3, book.derby("c01", "c02")!!.games)
    }

    @Test
    fun rivalMatchesAreTopTierLeagueFixturesOfCountedMembers() {
        val a = (1..8).map { "a$it" }
        val b = (1..8).map { "b$it" }
        val book = Eco.world(Eco.book(listOf(Eco.Spec("c01", "KR", a), Eco.Spec("c02", "KR", b, city = 1), Eco.Spec("c04", "KR", listOf("d1"), city = 3))))
        Derbies.recompute(book, 0, start = 1)
        assertTrue(book.isRival("c01", "c02") && !book.isRival("c01", "c04"))
        val rating = { k: String -> if (k == "d1") 1700.0 else 1700.0 - 10 * k.drop(1).toInt() }
        val top = listOf("a1", "a2", "a7", "b1", "b2", "b7", "d1")
        val second = listOf("a3", "a8", "b3", "b8")
        val s = Season.start(1, 9L, listOf(top, second), true, Leagues.national(listOf("KR"), listOf(2)))
        val plan = SeasonPlan(s, LeagueConfig())
        val rm = Derbies.matches(book, rating)
        assertEquals("서울-부산 클래식", rm.name(s, "a1", "b2"))
        assertEquals("서울-부산 클래식", Derbies.rivalName(book, plan, "b1", "a2", rating))
        // b7 and a7 are no counted members (their clubs' 7th best).
        assertNull(rm.name(s, "a1", "b7"))
        assertNull(rm.name(s, "a7", "b1"))
        // The same club, a club that is no rival, the 2부, two divisions.
        assertNull(rm.name(s, "a1", "a2"))
        assertNull(rm.name(s, "a1", "d1"))
        assertNull(rm.name(s, "a3", "b3"))
        assertNull(rm.name(s, "a1", "b3"))
        // Any competition: counted members of rival clubs.
        assertEquals("서울-부산 클래식", rm.of("a3", "b3"))
        // The week's derby_result: the first rival match with a winner, the all-time series from the winner's club.
        Hist.play(s, last = 0)
        val week1 = s.matchday(0).filter { s.result(it.id) != null }
        val rival = week1.filter { rm.name(s, it.a, it.b) != null && Hist.aWins(it.id) == 1 }.minByOrNull { it.id }
        val e = Derbies.weekResult(book, plan, 1, rating)
        if (rival == null) {
            assertNull(e)
        } else {
            val ev = assertNotNull(e)
            assertEquals("derby_result", ev.kind)
            val wc = book.clubOf(rival.a)!!
            val lc = book.clubOf(rival.b)!!
            assertEquals(listOf(rival.a, rival.b, wc, lc), ev.keys)
            val series = Derbies.series(book, plan, through = 1).getValue("c01 c02")
            val (w, d, l) = series.of(wc)
            assertEquals(listOf(if (rival.id % 3 == 0) "2-0" else "1.5-0.5", w.toString(), d.toString(), l.toString(), "서울-부산 클래식"), ev.args)
            assertEquals(1, ev.week)
            assertEquals("1:L:${rival.id}", ev.ref)
        }
        assertNull(Derbies.weekResult(book, plan, 1, rating, told = Derbies.SEASON_RESULTS))
        assertNull(Derbies.weekResult(book, plan, 2, rating), "nothing played in week 2")
    }

    @Test
    fun foundingYearsNicknamesAndColoursAreDeterministic() {
        val one = book()
        val two = book()
        assertEquals(9, ClubIdentity.start(one))
        ClubIdentity.start(two)
        assertEquals(0, ClubIdentity.start(one), "set once")
        for (c in one.clubs) {
            val l1 = one.life(c.id)!!
            val l2 = two.life(c.id)!!
            assertEquals(l1.foundedYear to l1.nickname, l2.foundedYear to l2.nickname)
            assertTrue(l1.foundedYear in ClubIdentity.FIRST_YEAR until ClubIdentity.FIRST_YEAR + ClubIdentity.YEAR_SPAN)
            assertEquals("빨강·흰색", l1.colours)
            assertTrue(l1.nickname.isNotBlank())
        }
        // The biggest city's club is founded in the first 60% of the span.
        assertTrue(one.life("c01")!!.foundedYear <= ClubIdentity.FIRST_YEAR + Math.round(0.6 * (ClubIdentity.YEAR_SPAN - 1)))
        // Another seed, other draws.
        val other = Eco.world(ClubBook.decode(Eco.book((0..5).map { Eco.Spec(ClubBook.idOf(it + 1), "KR", listOf("k$it"), city = it) }, seed = 99L).encode()))
        ClubIdentity.start(other)
        assertNotEquals((1..6).map { one.life(ClubBook.idOf(it))!!.foundedYear }, (1..6).map { other.life(ClubBook.idOf(it))!!.foundedYear })
        // "{city} 명가" only with three club-table titles; a club founded later carries its first season's year.
        val titled = (1L..200L).map { seed -> ClubIdentity.nickname(seed, one.club("c01")!!.copy(honours = 3)) }
        val untitled = (1L..200L).map { seed -> ClubIdentity.nickname(seed, one.club("c01")!!) }
        assertTrue(titled.any { it == "서울 명가" })
        assertTrue(untitled.none { it.endsWith("명가") })
        val forms = Regex("(빨강 군단|서울의 자존심|c01 군단|서울 명가|빨강 c01|서울의 c01)")
        assertTrue((titled + untitled).all { forms.matches(it) }, (titled + untitled).toSet().toString())
        assertEquals(ClubIdentity.SEASON_YEAR + 12, ClubIdentity.foundedYear(1L, one.club("c01")!!.copy(founded = 12), later = true))
        assertTrue(ClubIdentity.newClub(one, "c02"))
        assertEquals(ClubIdentity.SEASON_YEAR + 1, one.life("c02")!!.foundedYear)
    }
}

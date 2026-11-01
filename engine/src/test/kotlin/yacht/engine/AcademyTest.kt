package yacht.engine

import yacht.engine.league.Academy
import yacht.engine.league.AcademyInput
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubInput
import yacht.engine.league.Intake
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Origin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Youth academies (design ENG 1b.3): the hometown club only with an academy at least the nation's
 * median and an intake below q; the lottery by academy level, seeded; no club above q + 1; pupils
 * at their mentor's club; everyone else through the draft; graduates' terms and their growth term;
 * wonderkids and the news, rebuilt from the book.
 */
class AcademyTest {
    /** KR clubs c01..c04 (서울 부산 인천 대구) of [members] members each, academies [levels]. */
    private fun book(levels: List<Int>, members: Int = 4): ClubBook {
        val book = Eco.world(Eco.book(levels.indices.map { i -> Eco.Spec(ClubBook.idOf(i + 1), "KR", (1..members).map { "m${i + 1}_$it" }, city = i) }))
        for ((i, l) in levels.withIndex()) book.world!!.state(ClubBook.idOf(i + 1)).academy = l
        return book
    }

    /** The off-season's input: the book's members and [newcomers], all Korean, rated 1500 unless [rating] says. */
    private fun input(
        book: ClubBook, newcomers: List<String>, rating: (String) -> Double = { 1500.0 },
        origin: (String) -> Origin = { Origin.GENERATED }, mentor: (String) -> String? = { null }, age: (String) -> Int? = { 18 },
    ): ClubInput = ClubInput(book.clubs.flatMap { it.squad } + newcomers, rating, { "KR" }, LeagueConfig(), age = age, origin = origin, mentor = mentor)

    private fun intake(placed: List<yacht.engine.league.AcademyPick>): Map<String, Int> = placed.groupingBy { it.club }.eachCount()

    @Test
    fun theHometownClubNeedsAGoodAcademyAndRoomInItsIntake() {
        val book = book(listOf(5, 1, 3, 3))
        val played = book.clubs.flatMap { it.squad }.toSet()
        val towns = mapOf("r1" to "서울", "r2" to "부산", "r3" to "서울", "r4" to "제주")
        val pot = mapOf("r1" to 80, "r2" to 85, "r3" to 70, "r4" to 60)
        val academy = AcademyInput({ towns[it] }, { pot[it] })
        val report = Academy.draft(book, 6, input(book, towns.keys.toList()), academy, played)
        val by = report.placed.associateBy { it.key }
        // Median academy 3: 서울's (5) takes r1, its best home-grown rookie, and only one (q = 1); 부산's (1) none.
        assertEquals("c01" to Intake.HOMETOWN, by.getValue("r1").let { it.club to it.intake })
        assertEquals(Intake.LOTTERY, by.getValue("r2").intake)
        assertEquals(Intake.LOTTERY, by.getValue("r3").intake)
        assertEquals(Intake.LOTTERY, by.getValue("r4").intake)
        assertTrue(intake(report.placed).values.all { it <= Academy.fair(4, 4) + 1 })
        // Every graduate's terms: its club's academy, from the next season, for three seasons, numbered.
        for (p in report.placed) {
            val t = book.terms(p.key)!!
            assertEquals(listOf(p.club, "7", "7", "9"), listOf(t.academy, t.gradSeason.toString(), t.since.toString(), t.until.toString()))
            assertEquals(p.club, book.clubOf(p.key))
        }
        for (c in book.clubs) assertEquals(c.squad.size, c.squad.map { book.terms(it)?.number ?: -it.hashCode() }.toSet().size, c.id)
        // The same book and seed draw the same.
        val again = book(listOf(5, 1, 3, 3))
        assertEquals(report.placed, Academy.draft(again, 6, input(again, towns.keys.toList()), academy, played).placed)
        // Asked again: nobody is left to place, the report is rebuilt the same.
        val repeat = Academy.draft(book, 6, input(book, towns.keys.toList()), academy, played)
        assertTrue(repeat.placed.isEmpty())
        assertEquals(report.graduates, repeat.graduates)
        assertEquals(report.copy(placed = emptyList()), Academy.report(book, 6, input(book, towns.keys.toList()), academy))
    }

    @Test
    fun theLotteryFollowsTheAcademiesAndSpreadsEachRound() {
        var best5 = 0
        var best1 = 0
        for (season in 1..300) {
            val book = book(listOf(5, 1, 3, 3))
            val played = book.clubs.flatMap { it.squad }.toSet()
            val rookies = (1..4).map { "r$it" }
            val report = Academy.draft(book, season, input(book, rookies), AcademyInput(potential = { 90 - it.drop(1).toInt() }), played)
            // One round: every club one rookie.
            assertEquals(List(4) { 1 }, intake(report.placed).values.toList(), "season $season")
            when (book.clubOf("r1")) {
                "c01" -> best5++
                "c02" -> best1++
            }
        }
        // The best rookie goes to the academy drawn first: level 5 about 5 times as often as level 1.
        assertTrue(best5 > 3 * best1, "$best5 vs $best1")
        assertTrue(best1 > 0)
    }

    @Test
    fun noClubTakesMoreThanQPlusOneAndTheRestIsDrafted() {
        val book = book(listOf(5, 2, 2, 1), members = 6)
        // c04 is at the size ceiling already (16 of 46 members in 4 clubs).
        for (k in (7..16).map { "m4_$it" }) book.place(k, "c04")
        val played = book.clubs.flatMap { it.squad }.toSet() + listOf("free1", "free2")
        val rookies = (1..10).map { "r$it" }
        val all = rookies + listOf("free1", "free2")
        val report = Academy.draft(book, 3, input(book, all), AcademyInput(hometown = { "서울" }, potential = { 99 - it.filter(Char::isDigit).toInt() }), played)
        val q = Academy.fair(10, 4)
        assertEquals(3, q)
        val rookieIntake = intake(report.placed.filter { it.key in rookies })
        assertTrue(rookieIntake.values.all { it <= q + 1 }, "$rookieIntake")
        // Hometown 서울 for everyone: c01 takes q of them that way, then the lottery may give it one more.
        assertEquals(q, report.placed.count { it.intake == Intake.HOMETOWN })
        assertTrue(report.placed.filter { it.intake == Intake.HOMETOWN }.all { it.club == "c01" })
        // The full club takes nobody; players who played before go through the draft, smallest club first.
        assertEquals(0, rookieIntake["c04"] ?: 0)
        val drafted = report.placed.filter { it.intake == Intake.DRAFT }
        assertTrue(drafted.map { it.key }.containsAll(listOf("free1", "free2")))
        assertEquals(drafted.indices.map { it + 1 }, drafted.map { it.pick })
        for (d in drafted.filter { it.key.startsWith("free") }) assertEquals(null, book.terms(d.key)!!.academy)
        assertTrue(all.all { book.clubOf(it) != null })
    }

    @Test
    fun pupilsGoToTheirMentorsClubFirst() {
        val book = book(listOf(1, 1, 1, 1))
        val played = book.clubs.flatMap { it.squad }.toSet()
        val pupils = listOf("e1_1", "e1_2", "e1_3")
        val report = Academy.draft(
            book, 4, input(book, pupils + "r1", origin = { if (it.startsWith("e")) Origin.EVOLVED else Origin.GENERATED }, mentor = { if (it.startsWith("e")) "m3_1" else null }),
            AcademyInput(), played,
        )
        val by = report.placed.associateBy { it.key }
        // q = 1: the mentor's club takes its pupils up to q + 1; the third goes to the lottery.
        assertEquals(listOf("c03" to Intake.MENTOR, "c03" to Intake.MENTOR), pupils.take(2).map { k -> by.getValue(k).let { it.club to it.intake } })
        assertEquals(Intake.LOTTERY, by.getValue("e1_3").intake)
        assertTrue(by.getValue("e1_3").club != "c03")
        assertEquals("c03", book.terms("e1_1")!!.academy)
        assertTrue(intake(report.placed).values.all { it <= Academy.fair(4, 4) + 1 })
    }

    @Test
    fun graduatesGetTheirAcademysGrowthForThreeSeasons() {
        val book = book(listOf(5, 1, 3, 3))
        val played = book.clubs.flatMap { it.squad }.toSet()
        Academy.draft(book, 6, input(book, listOf("r1")), AcademyInput(hometown = { "서울" }), played)
        assertEquals("c01", Academy.academyOf(book, "r1"))
        assertEquals(listOf("r1"), Academy.graduates(book, "c01"))
        // Seasons 7, 8 and 9 (its first three): 0.02 × (5 − 3); then nothing.
        for (s in 7..9) assertEquals(0.04, Academy.growth(book, "r1", s), 1e-9)
        assertEquals(0.0, Academy.growth(book, "r1", 10))
        assertEquals(0.0, Academy.growth(book, "r1", 6))
        // Playing for another club, the academy no longer helps; a member who came through the draft never had it.
        book.place("r1", "c02")
        assertEquals(0.0, Academy.growth(book, "r1", 8))
        assertEquals(listOf("r1"), Academy.graduates(book, "c01"), "a graduate playing elsewhere")
        assertEquals(0.0, Academy.growth(book, "m1_1", 7))
        // A weak academy slows its own graduates a little.
        val weak = book(listOf(1, 1, 1, 1))
        Academy.draft(weak, 6, input(weak, listOf("r1")), AcademyInput(), weak.clubs.flatMap { it.squad }.toSet())
        assertEquals(-0.04, Academy.growth(weak, "r1", 7), 1e-9)
    }

    @Test
    fun wonderkidsAndTheNews() {
        val book = book(listOf(3, 3, 3, 3))
        val played = book.clubs.flatMap { it.squad }.toSet()
        val pot = mapOf("w1" to 92, "w2" to 88, "w3" to 95, "r1" to 70, "old" to 93)
        val ages = mapOf("w1" to 18, "w2" to 19, "w3" to 17, "r1" to 18, "old" to 22)
        val keys = pot.keys.toList()
        val report = Academy.draft(book, 2, input(book, keys, age = { ages[it] }), AcademyInput(potential = { pot[it] }), played)
        assertEquals(listOf("w3", "old", "w1", "w2", "r1"), report.graduates.map { it.first })
        assertEquals(listOf("w3", "w1", "w2"), report.wonderkids)
        assertTrue(Academy.isWonderkid(88, 19) && !Academy.isWonderkid(87, 19) && !Academy.isWonderkid(93, 20) && !Academy.isWonderkid(null, 18))
        val events = report.events()
        assertEquals(listOf("academy_graduates", "wonderkid", "wonderkid"), events.map { it.kind })
        val g = events[0]
        assertEquals(listOf("w3", "old", "w1") + report.graduates.take(3).map { it.second }.distinct(), g.keys)
        assertEquals(listOf("5", "3"), g.args)
        assertEquals(listOf("w3", book.clubOf("w3")), events[1].keys)
        assertEquals(listOf("95", "17"), events[1].args)
        // Rebuilt from the book after the step: the same news.
        assertEquals(events, Academy.report(book, 2, input(book, keys, age = { ages[it] }), AcademyInput(potential = { pot[it] })).events())
        // No club world, no academy.
        val plain = Eco.book(listOf(Eco.Spec("c01", "KR", listOf("a"))))
        assertTrue(Academy.draft(plain, 2, input(plain, listOf("x")), AcademyInput(), setOf("a")).placed.isEmpty())
    }
}

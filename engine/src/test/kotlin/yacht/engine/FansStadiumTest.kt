package yacht.engine

import yacht.engine.league.ArcEffects
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.Coach
import yacht.engine.league.FacilityChange
import yacht.engine.league.Gender
import yacht.engine.league.LifeInput
import yacht.engine.league.LifeReport
import yacht.engine.league.Owner
import yacht.engine.league.OwnerType
import yacht.engine.league.Philosophy
import yacht.engine.league.Sponsors
import yacht.engine.league.Stadium
import yacht.engine.league.StadiumProject
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fans, their mood, the stadium and the facilities (design ENG 1b.1, 1b.2). */
class FansStadiumTest {
    /** A Korean league of [n] clubs (two members each); club i's city is the nation's i-th. */
    private fun book(n: Int = 1, balance: Int = 0, fans: Int = 20_000, seats: Int = 10_000): ClubBook =
        Eco.world(Eco.book((0 until n).map { i -> Eco.Spec("c0${i + 1}", "KR", listOf("a$i", "b$i"), city = i) }), balance, fans, seats)

    /** The club-life step after [season] (accounts of an income of [income] made first); club tables in id order unless [order]. */
    private fun close(book: ClubBook, season: Int, income: Int = 100, order: List<String>? = null, extra: LifeInput = LifeInput(), arcs: ArcEffects = ArcEffects.NONE): LifeReport {
        val ids = book.clubs.map { it.id }
        for (id in ids) Eco.closed(book, id, season, income)
        val plan = Eco.plan(season, listOf("KR" to book.clubs.flatMap { it.squad }))
        return ClubEconomy.life(book, plan, Eco.input(book, arcs = arcs), mapOf("KR" to Eco.table("KR", order ?: ids)), extra)
    }

    @Test
    fun fansGrowWithSuccessAndRevertToTheirBase() {
        val base = ClubEconomy.fanBase(1.0, 0)
        assertEquals(12_800.0, base)
        assertEquals(listOf(1.6, 1.5, 1.0, 0.7, 0.7), listOf(0, 1, 6, 9, 20).map { Math.round(ClubEconomy.cityWeight(it) * 10) / 10.0 })
        assertEquals(Math.round(12_800 * 1.75).toInt(), ClubEconomy.initialFans(base, 5))
        assertEquals(ClubEconomy.initialFans(base, 5), ClubEconomy.initialFans(base, 9), "at most 5 titles count")
        // Ordinary clubs (any place of 8, a title when first, now and then a major or a star, a crisis one season in ten) stay within 0.5-3 × base for 40 seasons.
        for (club in 0 until 20) {
            val rng = Random(club.toLong())
            var f = ClubEconomy.initialFans(base, club % 4)
            repeat(40) {
                val place = 1 + rng.nextInt(8)
                f = ClubEconomy.grow(f, base, ((8 + 1) / 2.0 - place) / 8, place == 1, rng.nextInt(2), rng.nextInt(2), place == 8, rng.nextInt(10) == 0)
                assertTrue(f in (0.5 * base).toInt()..(3 * base).toInt(), "club $club: $f")
            }
        }
        // A big club with neutral seasons falls back toward its base.
        var big = (5 * base).toInt()
        repeat(40) { big = ClubEconomy.grow(big, base, 0.0, false, 0, 0, false, false) }
        assertTrue(big < 1.5 * base, "$big")
        // Extremes stay inside the bounds.
        var dynasty = ClubEconomy.initialFans(ClubEconomy.fanBase(1.6, 0), 5)
        repeat(200) { dynasty = ClubEconomy.grow(dynasty, ClubEconomy.fanBase(1.6, 0), 0.4375, true, 3, 3, false, false) }
        assertTrue(dynasty in 300..500_000)
        var doormat = 1_000
        repeat(200) { doormat = ClubEconomy.grow(doormat, 2_000.0, -0.4375, false, 0, 0, true, true) }
        assertTrue(doormat in 300..500_000)
        assertEquals(500_000, ClubEconomy.grow(500_000, 12_800.0, 2.0, true, 3, 3, false, false))
        assertEquals(300, ClubEconomy.grow(300, 12_800.0, -2.0, false, 0, 0, true, true))
        // Through the step: the champion gains, the last loses, the money history keeps the fans after the close.
        val book = book(4, fans = 12_800)
        close(book, 3)
        val w = book.world!!
        assertTrue(w.clubs.getValue("c01").fans > w.clubs.getValue("c04").fans)
        assertEquals(w.clubs.getValue("c01").fans, book.life("c01")!!.history.first().fans)
    }

    @Test
    fun moodFollowsTheBoardsTargetAndTheSeasonsEvents() {
        // M ← 0.5 M + 0.5 clamp(50 + 60 × gap + events, 0, 100).
        assertEquals(70, ClubEconomy.moodAfter(50, 0.25, 25))
        assertEquals(25, ClubEconomy.moodAfter(50, 0.0, -50))
        assertEquals(50, ClubEconomy.moodAfter(100, -1.0, -100))
        // The target is clamped to 1..C.
        assertEquals(0.0, ClubEconomy.gap(0, 1, 8))
        assertEquals(-0.875, ClubEconomy.gap(-3, 8, 8))
        assertEquals(0.0, ClubEconomy.gap(20, 8, 8))
        // Events: title +25, stars sold −8 each (at most −16), record signing +6, a member to a rival −15, derby ±8, a fund −5 (−15 at a fans' club), a sacking +5.
        assertEquals(25, ClubEconomy.moodEvents(title = true))
        assertEquals(-16, ClubEconomy.moodEvents(starsSold = 3))
        assertEquals(6 - 15 + 8, ClubEconomy.moodEvents(recordSigning = true, toRival = 1, derby = 7))
        assertEquals(-8, ClubEconomy.moodEvents(derby = -4))
        assertEquals(-5, ClubEconomy.moodEvents(fundTakeover = true))
        assertEquals(-15, ClubEconomy.moodEvents(fundTakeover = true, fansOwned = true))
        assertEquals(5, ClubEconomy.moodEvents(sacked = true))
        // Through the step: the champion against a target of 3 of 4; the last against a target of 2; the arcs' shocks and other events.
        val book = book(4)
        val w = book.world!!
        w.clubs.getValue("c01").mood = 100
        w.clubs.getValue("c04").mood = 10
        val targets = mapOf("c01" to 3, "c02" to 2, "c03" to 3, "c04" to 2)
        val r = close(book, 3, extra = LifeInput(target = targets::get, moodEvents = { if (it == "c03") -20 else 0 }), arcs = ArcEffects(moodShocks = mapOf("c02" to 10)))
        assertEquals(ClubEconomy.moodAfter(100, 0.5, 25), w.clubs.getValue("c01").mood)
        assertEquals(ClubEconomy.moodAfter(50, 0.0, 10), w.clubs.getValue("c02").mood)
        assertEquals(ClubEconomy.moodAfter(50, 0.0, -20), w.clubs.getValue("c03").mood)
        assertEquals(ClubEconomy.moodAfter(10, -0.5, 0), w.clubs.getValue("c04").mood)
        assertEquals(listOf("c01"), r.parties, "≥ 90 with a title")
        assertEquals(listOf("c04"), r.protests, "below 25")
        assertEquals(listOf("c04"), ClubEconomy.protests(book, w.clubs.keys))
        val ev = r.events({ 0 }, { w.clubs.getValue(it).mood }, { if (it == "c04") "k7" else null })
        assertEquals(listOf("c04", "k7"), ev.single { it.kind == "fan_protest" }.keys)
        assertEquals(listOf("c01"), ev.single { it.kind == "fan_party" }.keys)
        // Without the board's targets the middle of the table is the target: 2.5 of 4, so the second is a little above it.
        val plain = book(4)
        close(plain, 3)
        assertEquals(ClubEconomy.moodAfter(50, 0.375, 25), plain.world!!.clubs.getValue("c01").mood)
        assertEquals(ClubEconomy.moodAfter(50, 0.125, 0), plain.world!!.clubs.getValue("c02").mood)
        assertEquals(ClubEconomy.moodAfter(50, -0.125, 0), plain.world!!.clubs.getValue("c03").mood)
        // The checkpoint's update.
        assertTrue(ClubEconomy.mood(book, "c02", 0.25, 0))
        assertEquals(ClubEconomy.moodAfter(ClubEconomy.moodAfter(50, 0.0, 10), 0.25, 0), w.clubs.getValue("c02").mood)
    }

    @Test
    fun attendanceNeverExceedsTheSeats() {
        val stadium = Stadium("서울 스타디움", 10_000, null, null)
        assertEquals(10_000, ClubEconomy.attendance(500_000, 100, 1.6, stadium))
        assertEquals(ClubEconomy.demand(20_000, 50, 1.0), ClubEconomy.attendance(20_000, 50, 1.0, stadium))
        assertEquals(7_000, ClubEconomy.demand(20_000, 50, 1.0))
        val building = stadium.copy(project = StadiumProject(ClubEconomy.EXPAND, 12_500, 6, 50, 25))
        assertEquals(9_000, ClubEconomy.seats(building), "10% fewer seats while building")
        assertEquals(9_000, ClubEconomy.attendance(500_000, 100, 1.6, building))
        assertEquals(10_000, ClubEconomy.seats(stadium.copy(project = StadiumProject(ClubEconomy.NEW_STADIUM, 30_000, 8, 900, 360))), "a new stadium is built elsewhere")
        // Prices by owner and the match-day money.
        assertEquals(0.25, ClubEconomy.price(1.0, OwnerType.LOCAL))
        assertEquals(0.2, ClubEconomy.price(1.0, OwnerType.FANS))
        assertEquals(0.3125, ClubEconomy.price(1.0, OwnerType.FUND))
        assertEquals(53, ClubEconomy.matchday(10_000, 0.25, 21))
        // Capacities at a migration: round(F × 0.45, 500) within 3,000..40,000.
        assertEquals(listOf(3_000, 9_000, 11_500, 40_000), listOf(2_000, 20_000, 25_555, 200_000).map { ClubEconomy.startSeats(it) })
    }

    @Test
    fun anExpansionTakesTwoSeasonsAndCostsHalfNowHalfAtTheOpening() {
        // 40,000 fans at mood 50 fill a 10,000 stadium (14,000 would come): +2,500 seats for 5억.
        val book = book(1, balance = 300, fans = 40_000)
        val st = book.world!!.clubs.getValue("c01")
        st.training = 5
        st.academy = 5
        val name = book.life("c01")!!.stadium.name
        val r = close(book, 3)
        val project = book.life("c01")!!.stadium.project!!
        assertEquals(StadiumProject(ClubEconomy.EXPAND, 12_500, 6, 50, 25), project)
        assertEquals(25, book.life("c01")!!.last!!.building)
        assertEquals(275, st.balance)
        assertEquals('E', r.stadiums.single().kind)
        val ev = r.events({ 0 }, { 0 }, { null }).single { it.kind == "stadium_project" }
        assertEquals(listOf("10000", "12500", "2", "50", "E"), ev.args)
        // Built through seasons 4 and 5: nothing more to pay after 4; it opens after 5 with the rest paid.
        close(book, 4)
        assertEquals(project, book.life("c01")!!.stadium.project)
        assertEquals(0, book.life("c01")!!.last!!.building)
        val opened = close(book, 5)
        val stadium = book.life("c01")!!.stadium
        assertEquals(12_500, stadium.capacity)
        assertNull(stadium.project)
        assertEquals(name, stadium.name)
        assertEquals(25, book.life("c01")!!.last!!.building)
        assertEquals('O', opened.stadiums.single().kind)
        assertEquals(listOf(name, "12500", "E"), opened.events({ 0 }, { 0 }, { null }).single { it.kind == "stadium_open" }.args)
        // No expansion without the money: cost + half the income.
        val poor = book(1, balance = 99, fans = 40_000)
        poor.world!!.clubs.getValue("c01").apply { training = 5; academy = 5 }
        close(poor, 3)
        assertNull(poor.life("c01")!!.stadium.project)
    }

    @Test
    fun aNewStadiumOpensAfterFourSeasonsWithNamingRights() {
        // Demand 14,000 against 5,000 seats: a new stadium of 15,000 seats for 45억, 40% now and 15% a season.
        val book = book(1, balance = 500, fans = 40_000, seats = 5_000)
        book.world!!.clubs.getValue("c01").apply { training = 5; academy = 5 }
        val r = close(book, 3)
        assertEquals(StadiumProject(ClubEconomy.NEW_STADIUM, 15_000, 8, 450, 180), book.life("c01")!!.stadium.project)
        assertEquals('N', r.stadiums.single().kind)
        assertEquals(listOf("5000", "15000", "4", "450", "N"), r.events({ 0 }, { 0 }, { null }).single { it.kind == "stadium_project" }.args, "a new stadium, not an expansion")
        for (s in 4..6) {
            close(book, s)
            assertEquals(68, book.life("c01")!!.last!!.building, "season $s")
        }
        assertEquals(180 + 3 * 68, book.life("c01")!!.stadium.project!!.paid)
        val opened = close(book, 7)
        val stadium = book.life("c01")!!.stadium
        assertEquals(450 - 180 - 3 * 68, book.life("c01")!!.last!!.building)
        assertEquals(15_000, stadium.capacity)
        assertNull(stadium.project)
        val naming = stadium.naming!!
        assertEquals("$naming 아레나", stadium.name)
        assertTrue(naming != book.life("c01")!!.sponsor?.name)
        assertEquals('O', opened.stadiums.single().kind)
        assertEquals(listOf(stadium.name, "15000", "N"), opened.events({ 0 }, { 0 }, { null }).single { it.kind == "stadium_open" }.args)
        // The naming rights pay 0.5 × λ × capacity / 20,000 억 a season on top of the main deal.
        assertEquals(4, Sponsors.naming(1.0, 15_000))
        val plan = Eco.plan(8, listOf("KR" to listOf("a0", "b0")))
        ClubEconomy.accounts(book, plan, Eco.input(book), mapOf("KR" to Eco.table("KR", listOf("c01"))))
        val deal = book.life("c01")!!.sponsor!!
        assertEquals((if (8 in deal.from..deal.until) deal.perSeason else 0) + 4, book.life("c01")!!.last!!.sponsor)
    }

    @Test
    fun oneFacilityUpgradeASeasonByTheCoachAndTheOwner() {
        assertEquals(listOf(40, 80, 160, 300, null), (1..5).map { ClubEconomy.upgradeCost('T', it) })
        assertEquals(listOf(30, 60, 120, 240, null), (1..5).map { ClubEconomy.upgradeCost('A', it) })
        assertEquals(listOf("기초", "보통", "좋음", "최신", "세계 최고"), ClubEconomy.LEVELS)
        fun upgraded(owner: OwnerType, philosophy: Philosophy?, training: Int, academy: Int, balance: Int): List<FacilityChange> {
            // 5,000 fans: no stadium project.
            val book = book(1, balance = balance, fans = 5_000)
            val w = book.world!!
            w.clubs.getValue("c01").apply { this.training = training; this.academy = academy }
            w.economy.owners["c01"] = Owner(owner, "", 3, 3, 1, 0)
            if (philosophy != null) {
                w.coaches.active["k1"] = Coach("k1", "감독", "KR", Gender.MALE, -50, philosophy, 3, 50, "c01", 1, 3, 20, null, 1, 0, 0, 0, emptyList(), 0)
                w.clubs.getValue("c01").coach = "k1"
            }
            return close(book, 3).facilities
        }
        // The academy first for a fans' club or a 유망주 육성형 coach, training first for a 스타 영입형 coach, else the lower (ties: academy).
        assertEquals(listOf(FacilityChange("c01", 'A', 3)), upgraded(OwnerType.FANS, null, 2, 2, 500))
        assertEquals(listOf(FacilityChange("c01", 'A', 3)), upgraded(OwnerType.LOCAL, Philosophy.YOUTH, 2, 2, 500))
        assertEquals(listOf(FacilityChange("c01", 'T', 3)), upgraded(OwnerType.LOCAL, Philosophy.STAR, 2, 2, 500))
        assertEquals(listOf(FacilityChange("c01", 'T', 3)), upgraded(OwnerType.LOCAL, null, 2, 3, 500))
        assertEquals(listOf(FacilityChange("c01", 'A', 3)), upgraded(OwnerType.LOCAL, null, 2, 2, 500))
        assertEquals(listOf(FacilityChange("c01", 'T', 5)), upgraded(OwnerType.FANS, null, 4, 5, 2_000), "the academy is at the top")
        // Only with the balance after a reserve of half the income at twice the cost: 50 + 2 × 60.
        assertTrue(upgraded(OwnerType.FANS, null, 2, 2, 169).isEmpty())
        assertEquals(1, upgraded(OwnerType.FANS, null, 2, 2, 170).size)
        // Paid in the season's accounts.
        val book = book(1, balance = 170, fans = 5_000)
        close(book, 3)
        assertEquals(60, book.life("c01")!!.last!!.building)
        assertEquals(110, book.life("c01")!!.balance)
        assertEquals(3, book.life("c01")!!.academy)
    }

    @Test
    fun twoSeasonsInTroubleCostTheHigherFacilityALevel() {
        val book = book(1, balance = -60, fans = 5_000)
        val st = book.world!!.clubs.getValue("c01")
        st.training = 3
        st.academy = 2
        st.trouble = 1
        val r = close(book, 3)
        assertEquals(2, st.trouble)
        assertEquals(2, st.training)
        assertEquals(listOf(FacilityChange("c01", 'T', 2, up = false)), r.facilities)
        // A third season in trouble keeps them; a fourth drops the higher again (ties: training).
        close(book, 4)
        assertEquals(2 to 2, st.training to st.academy)
        close(book, 5)
        assertEquals(1 to 2, st.training to st.academy)
        assertEquals(0, r.events({ 0 }, { 0 }, { null }).count { it.kind == "club_world_digest" && it.args[2] != "0" }, "a level lost is no investment")
    }
}

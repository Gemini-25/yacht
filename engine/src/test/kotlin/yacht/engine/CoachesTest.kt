package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubEconomy
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubNames
import yacht.engine.league.ClubStanding
import yacht.engine.league.Coach
import yacht.engine.league.CoachBook
import yacht.engine.league.CoachInput
import yacht.engine.league.CoachMaker
import yacht.engine.league.CoachMarket
import yacht.engine.league.CoachMove
import yacht.engine.league.CoachRecordKind
import yacht.engine.league.Gender
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Nations
import yacht.engine.league.Owner
import yacht.engine.league.OwnerType
import yacht.engine.league.Philosophy
import yacht.engine.league.PlayerBook
import yacht.engine.league.RetiredCoach
import yacht.engine.league.Retiree
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StyleFamily
import yacht.engine.league.Tenure
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-made leagues for the coach market's tests (CoachesTest, BoardTargetTest): one league "KR" of
 * clubs c01.. with members `p<club>x<n>`, rated so the clubs' expected places follow their numbers,
 * a club world from [Eco.world] (팬 소유 owners of ambition 2 and patience 4, mood 50, levels 2).
 */
internal object Coaching {
    class League(val book: ClubBook, val ids: List<String>, val members: Map<String, List<String>>) {
        /** Club i's members are rated 1700 − 40 × i. */
        fun rating(key: String): Double = 1700.0 - 40 * key.removePrefix("p").substringBefore("x").toInt()

        fun plan(season: Int): SeasonPlan = Eco.plan(season, listOf("KR" to members.values.flatten()))

        fun table(vararg order: String): Map<String, List<ClubStanding>> = mapOf("KR" to Eco.table("KR", order.toList()))

        fun input(honours: List<Honour> = emptyList(), coach: CoachInput = CoachInput(), config: LeagueConfig = LeagueConfig(), coaches: Boolean = true): ClubInput =
            ClubInput(book.clubs.flatMap { it.squad }, ::rating, { k -> book.clubOf(k)?.let { book.nationOf(it) } }, config, honours, coachInput = coach, coaches = coaches)
    }

    fun league(n: Int, per: Int = 3): League {
        val members = (1..n).associate { i -> ClubBook.idOf(i) to (1..per).map { j -> "p${i}x$j" } }
        val cities = ClubNames.cities(Nations.KR).size
        val book = Eco.world(Eco.book(members.entries.mapIndexed { i, (id, ms) -> Eco.Spec(id, "KR", ms, city = i % cities) }))
        return League(book, members.keys.toList(), members)
    }

    /** Coach [id] of [club] (null: free), its spell from [from]; the club's `cl` line names it. */
    fun coach(
        book: ClubBook, id: String, club: String?, reputation: Int = 50, from: Int = 1, until: Int = 3, wage: Int = 20,
        philosophy: Philosophy = Philosophy.STABLE, born: Int = -50, dev: Int = 3, nation: String = "KR",
    ): Coach {
        val w = book.world!!
        val c = Coach(
            id, "감독 $id", nation, Gender.MALE, born, philosophy, dev, reputation, club, if (club == null) 0 else from,
            if (club == null) 0 else until, if (club == null) 0 else wage, null, 0, 0, 0, 0, listOfNotNull(club?.let { Tenure(it, from, 0, 0, 0, '-') }), 0,
        )
        w.coaches.active[id] = c
        if (club != null) w.state(club).coach = id
        w.nextCoach = maxOf(w.nextCoach, id.removePrefix("k").toInt() + 1)
        return c
    }
}

/** The coach market (design ENG 2.1-2.6). */
class CoachesTest {
    @Test
    fun generatedCoachesFollowTheirRules() {
        val players = PlayerBook().apply { ensureGenerated(300) }
        val taken = players.names().toHashSet()
        val counts = HashMap<Philosophy, Int>()
        val genders = HashMap<Gender, Int>()
        val n = 4000
        repeat(n) { i ->
            val c = CoachMaker.make("k$i", if (i % 2 == 0) "KR" else "JP", 10, Random(DiceStream.mix(77L, i.toLong())), taken)
            assertTrue(c.name !in taken, c.name)
            taken += c.name
            val age = c.age(10)
            assertTrue(age in 38..58)
            if (c.philosophy == Philosophy.YOUTH) assertTrue(c.dev in 4..5) else assertTrue(c.dev in 1..4)
            assertTrue(c.reputation in 20 + (age - 38)..45 + (age - 38), "${c.reputation} at $age")
            assertNull(c.club)
            assertEquals(if (i % 2 == 0) "KR" else "JP", c.nation)
            counts.merge(c.philosophy, 1, Int::plus)
            genders.merge(c.gender, 1, Int::plus)
        }
        for ((p, weight) in CoachMaker.WEIGHTS) assertEquals(weight / 100, counts.getValue(p).toDouble() / n, 0.025, p.name)
        assertEquals(0.5, genders.getValue(Gender.FEMALE).toDouble() / n, 0.03)
        // The same draw gives the same coach.
        assertEquals(CoachMaker.make("k1", "KR", 3, Random(5), emptySet()), CoachMaker.make("k1", "KR", 3, Random(5), emptySet()))
        // A coach's fit: 공격형 takes 요트파 and 과감파.
        assertEquals(setOf(StyleFamily.YACHT, StyleFamily.BOLD), Philosophy.ATTACK.families)
        assertTrue(Philosophy.YOUTH.families.isEmpty() && Philosophy.STAR.families.isEmpty())
    }

    @Test
    fun aStarSignerIsHiredOnlyByARichOwner() {
        val lg = Coaching.league(3)
        val w = lg.book.world!!
        Coaching.coach(lg.book, "k1", null, reputation = 95, philosophy = Philosophy.STAR)
        Coaching.coach(lg.book, "k2", null, reputation = 90, philosophy = Philosophy.STAR)
        Coaching.coach(lg.book, "k3", null, reputation = 30, philosophy = Philosophy.PRAGMATIC)
        w.economy.owners["c02"] = Owner(OwnerType.TYCOON, "회장", 4, 1, 0, 0)
        assertEquals("k3", CoachMarket.hire(lg.book, "c01", 1, lg.input())!!.id)
        assertEquals("k1", CoachMarket.hire(lg.book, "c02", 1, lg.input())!!.id)
        // The cheapest free one when nothing it may hire is free: a generated coach, never the 스타 영입형.
        val g = assertNotNull(CoachMarket.hire(lg.book, "c03", 1, lg.input()))
        assertNotEquals("k2", g.id)
        // Founding a world: no 스타 영입형 at a club without a rich owner.
        val big = Coaching.league(40)
        for ((i, id) in big.ids.withIndex()) if (i % 5 == 0) big.book.world!!.economy.owners[id] = Owner(OwnerType.STATE, "국부", 5, 2, 0, 0)
        CoachMarket.start(big.book, 1, big.input())
        for (c in big.book.clubs) {
            val coach = big.book.coachOf(c.id)!!
            if (coach.philosophy == Philosophy.STAR) assertEquals(OwnerType.STATE, big.book.life(c.id)!!.owner.type)
        }
    }

    @Test
    fun retiredPlayersWithACareerBecomeCoaches() {
        val r = Retiree("g1", "김선수", "KR", Gender.FEMALE, 34, 12, 14, 2, 0.2, 0, StyleFamily.BONUS)
        assertTrue(CoachMaker.eligible(r))
        assertFalse(CoachMaker.eligible(r.copy(seasons = 7)))
        assertFalse(CoachMaker.eligible(r.copy(age = 31)))
        assertFalse(CoachMaker.eligible(r.copy(majors = 0, captain = 2)))
        assertTrue(CoachMaker.eligible(r.copy(majors = 0, peak = 0.05)))
        assertTrue(CoachMaker.eligible(r.copy(majors = 0, captain = 3)))
        // About 35% of the eligible, each decided by its own draw.
        val share = (1..2000).count { CoachMaker.becomes(r.copy(key = "g$it"), 9L) } / 2000.0
        assertEquals(0.35, share, 0.04)
        assertEquals(CoachMaker.becomes(r, 9L), CoachMaker.becomes(r, 9L))
        // Its identity, a philosophy from its style family, development 2-5, reputation 30 + 10 a major (≤ 3) + 10 for a top-5% peak.
        val c = CoachMaker.fromPlayer("k9", r, Random(1))
        assertEquals(listOf("김선수", "KR", "g1"), listOf(c.name, c.nation, c.player))
        assertEquals(Gender.FEMALE, c.gender)
        assertEquals(34, c.age(12))
        assertEquals(Philosophy.BONUS, c.philosophy)
        assertEquals(50, c.reputation)
        assertTrue(c.dev in 2..5)
        assertEquals(70, CoachMaker.fromPlayer("k9", r.copy(majors = 5, peak = 0.01), Random(1)).reputation)
        assertEquals(Philosophy.ATTACK, CoachMaker.philosophyOf(StyleFamily.BOLD))
        assertEquals(Philosophy.PRAGMATIC, CoachMaker.philosophyOf(StyleFamily.EV))
        assertNull(CoachMaker.philosophyOf(StyleFamily.ODD))
        // At the off-season: a retiree who becomes a coach joins the free pool with its story.
        val lg = market()
        val key = (1..500).map { "g$it" }.first { CoachMaker.becomes(r.copy(key = it, retired = 1), lg.plan(1).seed) }
        val report = CoachMarket.offseason(lg.book, lg.plan(1), lg.input(coach = CoachInput(listOf(r.copy(key = key, retired = 1)))), lg.table(*lg.ids.toTypedArray()))
        val ex = lg.book.coaches.single { it.player == key }
        assertTrue(report.moves.any { it.kind == 'C' && it.coach == ex.id && it.from == key })
        val story = report.events(lg.book).single { it.kind == "player_to_coach" }
        assertEquals(listOf(key, ex.id), story.keys)
        assertEquals(listOf("-", "B"), story.args)
        // The roster's retirees: those of the season's close, with their peak's share.
        val players = PlayerBook().apply { ensureGenerated(50) }
        assertTrue(CoachInput.retirees(players, 1, { 0 }).isEmpty())
    }

    @Test
    fun theBoardReadsTheSeasonsExpectations() {
        val lg = Coaching.league(5)
        val plan = lg.plan(1)
        val expected = CoachMarket.expected(plan, lg.book, "KR", lg::rating)
        val clubs = yacht.engine.league.Expectation.clubs(plan, lg.book, "KR", lg::rating)
        assertEquals(clubs.map { it.first }, expected.entries.sortedBy { it.value }.map { it.key })
        assertEquals(lg.ids, clubs.map { it.first })
        for (c in lg.ids.withIndex()) Coaching.coach(lg.book, "k${c.index + 1}", c.value, from = 0, until = 9)
        val w = lg.book.world!!
        w.state("c03").pressure = 1.0
        // Before any match the table is level (by id), so c03 is third: expected 3, the 팬 소유 owner (ambition 2) asks 3.5.
        val o = assertNotNull(CoachMarket.outlook(plan, lg.book, "c03", 1, lg::rating))
        assertEquals(3, o.expected)
        assertEquals(3, o.target)
        assertEquals(3, o.place)
        assertEquals(5, o.clubs)
        assertEquals(0.1, o.gap, 1e-12)
        assertEquals(1.0, o.pressure)
        assertEquals(CoachMarket.threshold(4), o.threshold)
        assertEquals(0.9, o.projected)
        assertEquals(5, CoachMarket.outlooks(plan, lg.book, 1, lg::rating).size)
        // A club without a coach has no outlook; nor does a book without a club world.
        w.state("c05").coach = null
        assertNull(CoachMarket.outlook(plan, lg.book, "c05", 1, lg::rating))
        assertTrue(CoachMarket.outlooks(plan, ClubBook.decode("clubs 2\nmeta 1 0 - 100\n"), 1, lg::rating).isEmpty())
        // A coach in its first season shows the first checkpoint's doubled threshold before the winter.
        Coaching.coach(lg.book, "k9", "c04", from = 1)
        assertEquals(2 * CoachMarket.threshold(4), CoachMarket.outlook(plan, lg.book, "c04", 1, lg::rating)!!.threshold)
    }

    @Test
    fun pressureAndThresholdsFollowTheFormulas() {
        assertEquals(listOf(1.2, 2.0, 2.8, 3.6, 4.4), (1..5).map { CoachMarket.threshold(it) }.map { Math.round(it * 10) / 10.0 })
        assertEquals(1.5, CoachMarket.pressureAfter(1.0, -0.25, 50))
        assertEquals(0.75, CoachMarket.pressureAfter(1.0, 0.25, 50))
        assertEquals(0.1, CoachMarket.pressureAfter(0.1, 0.5, 30), 1e-12)
        assertEquals(0.0, CoachMarket.pressureAfter(0.0, 0.5, 50))
        assertEquals(2.0, CoachMarket.pressureAfter(1.0, -0.25, 34))
        // Reputation: toward 40, the gap, titles, a sacking.
        assertEquals(59, CoachMarket.reputationAfter(60, 0.0, false, false, false, false))
        assertEquals(72, CoachMarket.reputationAfter(60, 0.25, true, false, false, false))
        assertEquals(48, CoachMarket.reputationAfter(60, -0.25, false, false, false, true))
        assertEquals(100, CoachMarket.reputationAfter(100, 1.0, true, true, true, false))
        assertEquals(0, CoachMarket.reputationAfter(0, -1.0, false, false, false, true))
        // Retirements from 62, certain at 72.
        assertEquals(listOf(0.0, 0.15, 0.45, 0.95, 1.0, 1.0, 1.0), listOf(61, 62, 65, 70, 71, 72, 80).map { Math.round(CoachMarket.retireChance(it) * 100) / 100.0 })
        assertEquals(8, CoachMarket.poolSize(10))
        assertEquals(32, CoachMarket.poolSize(127))
    }

    @Test
    fun aCoachAtItsFirstCheckpointSurvivesBelowTwiceTheThreshold() {
        val lg = Coaching.league(4)
        val w = lg.book.world!!
        for (id in listOf("c03", "c04")) w.economy.owners[id] = Owner(OwnerType.TYCOON, "회장", 5, 1, 0, 0)
        Coaching.coach(lg.book, "k1", "c01", from = 0, until = 9)
        Coaching.coach(lg.book, "k2", "c02", from = 0, until = 9)
        Coaching.coach(lg.book, "k3", "c03", from = 0, until = 9)
        Coaching.coach(lg.book, "k4", "c04", from = 1, until = 9)
        // Both are asked for one place better than they end (gap −1/4, +0.5): 1.0 → 1.5 ≥ 1.2.
        w.state("c03").pressure = 1.0
        w.state("c04").pressure = 1.0
        val plan = lg.plan(1)
        assertTrue(CoachMarket.firstCheckpoint(lg.book, "c04", lg.book.coachOf("c04")!!, 1, winter = true))
        val out = CoachMarket.review(lg.book, plan, lg.table("c01", "c02", "c03", "c04"), lg::rating, winter = true)
        assertEquals(listOf("c03"), out.map { it.club })
        assertEquals(CoachMove('S', "k3", "c03", null, 3, 2), out.single())
        assertEquals("k4", lg.book.coachOf("c04")!!.id)
        assertEquals(1.5, w.state("c04").pressure)
        // Twice the threshold (2.4) sacks it even at its first checkpoint.
        val lg2 = Coaching.league(4)
        lg2.book.world!!.economy.owners["c04"] = Owner(OwnerType.TYCOON, "회장", 5, 1, 0, 0)
        Coaching.coach(lg2.book, "k4", "c04", from = 1, until = 9)
        lg2.book.world!!.state("c04").pressure = 2.0
        assertEquals(listOf("c04"), CoachMarket.review(lg2.book, lg2.plan(1), lg2.table("c01", "c02", "c03", "c04"), lg2::rating, winter = true).map { it.club })
        // The sacked coach is free with its spell ended 'S'; the club's pressure starts again.
        val k3 = lg.book.coach("k3")!!
        assertNull(k3.club)
        assertEquals(Tenure("c03", 0, 1, 0, 0, 'S'), k3.tenures.first())
        assertEquals(1, k3.sacked)
        assertEquals(0.0, w.state("c03").pressure)
        assertNull(w.state("c03").coach)
    }

    @Test
    fun aClubSacksAtMostOneCoachASeason() {
        val lg = Coaching.league(4)
        val w = lg.book.world!!
        w.economy.owners["c04"] = Owner(OwnerType.TYCOON, "회장", 5, 1, 0, 0)
        for (c in lg.ids.withIndex()) Coaching.coach(lg.book, "k${c.index + 1}", c.value, from = 0, until = 9)
        w.state("c04").pressure = 5.0
        val plan = lg.plan(1)
        assertEquals(listOf("c04"), CoachMarket.review(lg.book, plan, lg.table("c01", "c02", "c03", "c04"), lg::rating, winter = true).map { it.club })
        // The winter hire: from the free pool (none: a generated one), its spell from this season.
        val hired = assertNotNull(CoachMarket.hire(lg.book, "c04", 1, lg.input(), plan.seed))
        assertEquals(Tenure("c04", 1, 0, 0, 0, '-'), hired.tenures.first())
        assertEquals(hired.id, w.state("c04").coach)
        assertTrue(hired.until in 2..3)
        assertNull(CoachMarket.hire(lg.book, "c04", 1, lg.input(), plan.seed))
        // At the close the new coach is far over any threshold, and stays: one sacking a season.
        w.state("c04").pressure = 20.0
        assertTrue(CoachMarket.sackedIn(lg.book, "c04", 1))
        assertTrue(CoachMarket.firstCheckpoint(lg.book, "c04", hired, 1, winter = false))
        assertTrue(CoachMarket.review(lg.book, plan, lg.table("c01", "c02", "c03", "c04"), lg::rating, winter = false).isEmpty())
        assertEquals(hired.id, lg.book.coachOf("c04")!!.id)
        // The next season it may go.
        assertEquals(listOf("c04"), CoachMarket.review(lg.book, lg.plan(2), lg.table("c01", "c02", "c03", "c04"), lg::rating, winter = true).map { it.club })
    }

    /**
     * The off-season after season 1 of six clubs: c01 (rich: balance 1000) without a coach; c02's coach
     * (k2, reputation 40) and c03's (k3, pressure 2.0, more than half its threshold) at the end of their
     * contracts; c05's coach k5 (reputation 95, wage 3억, two seasons left) worth poaching; every club an
     * income of 50억 for the season.
     */
    private fun market(): Coaching.League {
        val lg = Coaching.league(6)
        val w = lg.book.world!!
        w.state("c01").balance = 1000
        for (id in lg.ids) Eco.closed(lg.book, id, 1, 500)
        Coaching.coach(lg.book, "k2", "c02", reputation = 40, from = 0, until = 1)
        Coaching.coach(lg.book, "k3", "c03", reputation = 40, from = 0, until = 1)
        Coaching.coach(lg.book, "k4", "c04", reputation = 40, from = 0, until = 9)
        Coaching.coach(lg.book, "k5", "c05", reputation = 95, from = 0, until = 3, wage = 30)
        Coaching.coach(lg.book, "k6", "c06", reputation = 40, from = 0, until = 9)
        w.state("c03").pressure = 2.0
        return lg
    }

    @Test
    fun theRichestVacancyHiresFirstAndPoachesWithCompensation() {
        val lg = market()
        val w = lg.book.world!!
        val report = CoachMarket.offseason(lg.book, lg.plan(1), lg.input(), lg.table(*lg.ids.toTypedArray()))
        // c01 poached k5 and paid c05 its wage × seasons left (3억 × 2).
        val k5 = lg.book.coach("k5")!!
        assertEquals("c01", k5.club)
        assertEquals(Tenure("c01", 2, 0, 0, 0, '-'), k5.tenures[0])
        assertEquals('P', k5.tenures[1].end)
        assertEquals(1, k5.tenures[1].to)
        assertEquals(CoachMove('P', "k5", "c01", "c05", fee = 60), report.of('P').single())
        assertEquals(1000 - 60, w.state("c01").balance)
        assertEquals(100 + 60, w.state("c05").balance)
        assertEquals(60, w.economy.accounts.getValue("c01").coach)
        assertEquals(-60, w.economy.accounts.getValue("c05").coach)
        assertEquals(ClubEconomy.coachWage(k5.reputation, 1.0), k5.wage)
        assertTrue(k5.until in 1 + CoachMarket.CONTRACT_MIN..1 + CoachMarket.CONTRACT_MAX)
        // The chain: c05 and c03 hired (each once), every club has a coach, the pool is full.
        assertEquals(listOf("c03", "c05"), report.of('H').map { it.club!! }.sorted())
        for (id in lg.ids) assertEquals(id, lg.book.coachOf(id)!!.club)
        assertTrue(lg.book.freeCoaches.size >= CoachMarket.poolSize(6))
        assertEquals(lg.book.coaches.size, lg.book.coaches.map { it.id }.distinct().size)
        // k3 did not get its contract again (pressure ≥ half the threshold), nor its old club back.
        val k3 = lg.book.coach("k3")!!
        assertNotEquals("c03", k3.club)
        assertEquals('L', k3.tenures.first { it.club == "c03" }.end)
        assertTrue(report.moves.contains(CoachMove('L', "k3", "c03", null)))
        // k2 (pressure 0) was renewed, 2-3 more seasons at its new reputation's wage.
        val k2 = lg.book.coach("k2")!!
        assertEquals("c02", k2.club)
        assertTrue(k2.until in 3..4)
        assertEquals(ClubEconomy.coachWage(k2.reputation, 1.0), k2.wage)
        // The news: the poaching and the two hires; no digest (nothing left over).
        val events = report.events(lg.book)
        assertEquals(listOf("coach_poached", "coach_hired", "coach_hired"), events.map { it.kind })
        assertEquals(listOf("c01", "k5", "c05"), events[0].keys)
        assertEquals(listOf("60"), events[0].args)
        assertEquals("c03", events[1].keys[0])
        assertTrue(events.all { it.season == 1 })
    }

    @Test
    fun aRepeatedOffseasonChangesNothingAndTellsTheSame() {
        val lg = market()
        val first = CoachMarket.offseason(lg.book, lg.plan(1), lg.input(), lg.table(*lg.ids.toTypedArray()))
        val text = lg.book.encode()
        val version = lg.book.version
        val again = CoachMarket.offseason(lg.book, lg.plan(1), lg.input(), lg.table(*lg.ids.toTypedArray()))
        assertEquals(first, again)
        assertEquals(text, lg.book.encode())
        assertEquals(version, lg.book.version)
        // Read back from the file, it rebuilds the same report and news.
        val back = ClubBook.decode(text)
        assertEquals(first, CoachMarket.report(back, 1))
        assertEquals(first.events(lg.book), CoachMarket.report(back, 1).events(back))
        assertEquals(1, back.world!!.coaches.season)
        // The same book and inputs decide the same in a fresh run.
        val other = market()
        CoachMarket.offseason(other.book, other.plan(1), other.input(), other.table(*other.ids.toTypedArray()))
        assertEquals(text, other.book.encode())
        // Off: the knob, the switch, a book without a club world.
        val off = market()
        val before = off.book.encode()
        assertTrue(CoachMarket.offseason(off.book, off.plan(1), off.input(coaches = false), off.table(*off.ids.toTypedArray())).moves.isEmpty())
        assertTrue(CoachMarket.offseason(off.book, off.plan(1), off.input(config = LeagueConfig().copy(clubWorld = false)), off.table(*off.ids.toTypedArray())).moves.isEmpty())
        assertEquals(before, off.book.encode())
    }

    @Test
    fun theCloseCountsHonoursAndReputation() {
        val lg = market()
        val w = lg.book.world!!
        // c02 is the club-table champion; a member of c04 wins its league, one of c06 the 챔피언스 리그; k4 is 올해의 감독.
        w.state("c04").pressure = 3.0
        val honours = listOf(Honour(1, "L0.KR", "p4x1"), Honour(1, "CL", "p6x2"), Honour(0, "L0.KR", "p6x1"))
        val order = arrayOf("c02", "c01", "c03", "c04", "c05", "c06")
        CoachMarket.offseason(lg.book, lg.plan(1), lg.input(honours, CoachInput(coachOfYear = "k4")), lg.table(*order))
        val k2 = lg.book.coach("k2")!!
        assertEquals(listOf(1, 1, 0), listOf(k2.seasons, k2.clubTitles, k2.leagueTitles))
        assertEquals(Tenure("c02", 0, 0, 1, 1, '-'), k2.tenures.first())
        // 40 → 0.95 × 40 + 2 + 12 × (2.5 − 1) / 6 + 10.
        assertEquals(53, k2.reputation)
        val k4 = lg.book.coach("k4")!!
        assertEquals(listOf(1, 0, 1, 1), listOf(k4.seasons, k4.clubTitles, k4.leagueTitles, k4.awards))
        assertEquals(0.0, w.state("c04").pressure)
        assertEquals(4, k4.tenures.first().best)
        val k6 = lg.book.coach("k6")!!
        assertEquals(listOf(1, 0), listOf(k6.cups, k6.leagueTitles))
        // A free coach's reputation drifts toward 40.
        assertTrue(lg.book.freeCoaches.all { it.reputation in 0..100 })
    }

    @Test
    fun retiringCoachesAreKeptWithTheirHonours() {
        val lg = market()
        val w = lg.book.world!!
        // k4 is 72 at the close (it retires), k6 72 too with ten seasons behind it (kept), k2 61 (never).
        w.coaches.active["k4"] = lg.book.coach("k4")!!.copy(born = 1 - 72)
        w.coaches.active["k6"] = lg.book.coach("k6")!!.copy(born = 1 - 72, seasons = 9)
        w.coaches.active["k2"] = lg.book.coach("k2")!!.copy(born = 1 - 61)
        val report = CoachMarket.offseason(lg.book, lg.plan(1), lg.input(), lg.table(*lg.ids.toTypedArray()))
        assertNull(lg.book.coach("k4"))
        assertNull(lg.book.coach("k6"))
        assertNotNull(lg.book.coach("k2"))
        val kept = lg.book.retiredCoaches.single()
        assertEquals("k6", kept.id)
        assertEquals(1, kept.retired)
        assertEquals(10, kept.count("seasons"))
        assertEquals(0, kept.count("CT"))
        assertEquals(listOf("k6"), report.retired.map { it.id })
        val news = report.events(lg.book).single { it.kind == "coach_retired" }
        assertEquals(listOf("k6"), news.keys)
        assertEquals(listOf("10", "0"), news.args)
        // Both clubs found a new coach.
        assertNotNull(lg.book.coachOf("c04"))
        assertNotNull(lg.book.coachOf("c06"))
        // Below 500 members at most 40 are kept, those without a title dropped first (oldest first).
        val lg2 = market()
        val w2 = lg2.book.world!!
        for (i in 1..45) w2.coaches.retiredList += RetiredCoach("k${100 + i}", "은퇴 $i", "KR", Gender.MALE, -80, i % 3 - 5, Philosophy.STABLE, 40, null, if (i <= 5) "CT:1" else "seasons:12")
        w2.coaches.active["k4"] = lg2.book.coach("k4")!!.copy(born = 1 - 72, clubTitles = 2)
        CoachMarket.offseason(lg2.book, lg2.plan(1), lg2.input(), lg2.table(*lg2.ids.toTypedArray()))
        assertEquals(40, lg2.book.retiredCoaches.size)
        assertTrue(lg2.book.retiredCoaches.any { it.id == "k4" })
        assertEquals(5, lg2.book.retiredCoaches.count { it.count("CT") > 0 && it.id != "k4" })
    }

    @Test
    fun coachLinesRoundTripWithFiveTenuresAndTheMarketsTokens() {
        val lg = market()
        CoachMarket.offseason(lg.book, lg.plan(1), lg.input(), lg.table(*lg.ids.toTypedArray()))
        val w = lg.book.world!!
        val tenures = listOf(
            Tenure("c01", 6, 0, 2, 1, '-'), Tenure("c02", 5, 5, 3, 0, 'S'), Tenure("c03", 3, 4, 1, 1, 'P'),
            Tenure("c04", 2, 2, 4, 0, 'L'), Tenure("c05", 1, 1, 5, 0, 'S'),
        )
        w.coaches.active["k90"] = Coach(
            "k90", "명장 %:,; 이", "KR", Gender.FEMALE, -40, Philosophy.YOUTH, 5, 77, null, 0, 0, 0, "g7", 12, 3, 2, 2, tenures, 1,
            cups = 2, spells = 7, longest = 4, done = 1, moves = listOf(CoachMove('S', "k90", "c02", null, 5, 2), CoachMove('H', "k90", "c06", "c02")),
        )
        val text = lg.book.encode()
        val back = ClubBook.decode(text)
        assertEquals(text, back.encode())
        assertEquals(w.coaches.active.values.toList(), back.coaches)
        assertEquals(lg.book.retiredCoaches, back.retiredCoaches)
        assertEquals(5, back.coach("k90")!!.tenures.size)
        // An older line (20 fields) reads with the defaults: no cups, spells = its tenures, no moves.
        val old = ClubBook.decode(
            "clubs 2\nmeta 77 3 991 6\nclub c01 KR 서울 호랑이 서울%20호랑이 0 ffc62828 ffffffff 호 120 2 1 g1,g2\n" +
                "cw 1 - - 0 0:0 4 0 1482 1\nk k1 이%20감독 KR F -45 Y 5 63 c01 3 5 31 - 9 2 1 2 1 c01:3:0:1:1:-;c02:1:2:3:0:S\n",
        ).coach("k1")!!
        assertEquals(listOf(0, 2, 0, 0), listOf(old.cups, old.spells, old.longest, old.done))
        assertTrue(old.moves.isEmpty())
        assertEquals(3, old.longestBy(5))
    }

    @Test
    fun everyCoachChangeBumpsTheBooksVersion() {
        val lg = market()
        fun bumps(what: String, f: () -> Unit) {
            val v = lg.book.version
            f()
            assertNotEquals(v, lg.book.version, what)
        }
        val w = lg.book.world!!
        bumps("pressure") { CoachMarket.review(lg.book, lg.plan(1), lg.table("c06", "c05", "c04", "c03", "c02", "c01"), lg::rating, winter = true) }
        bumps("a hire") { CoachMarket.hire(lg.book, "c01", 1, lg.input()) }
        bumps("a release") { CoachMarket.release(lg.book, "c06", 1) }
        bumps("a new club's coach") { CoachMarket.newClub(lg.book, "c06", 2, lg.input()) }
        w.state("c04").pressure = 50.0
        bumps("a sacking") { CoachMarket.review(lg.book, lg.plan(1), lg.table(*lg.ids.toTypedArray()), lg::rating, winter = false) }
        bumps("the off-season") { CoachMarket.offseason(lg.book, lg.plan(1), lg.input(), lg.table(*lg.ids.toTypedArray())) }
        val fresh = Coaching.league(3)
        val v = fresh.book.version
        CoachMarket.start(fresh.book, 1, fresh.input())
        assertNotEquals(v, fresh.book.version)
    }

    @Test
    fun aClubWorldStartsWithACoachForEveryClub() {
        val lg = Coaching.league(10)
        val w = lg.book.world!!
        // A legend of Korea, retired a season ago at 36: it coaches the most valuable Korean club.
        val legend = Retiree("g5", "전설", "KR", Gender.MALE, 36, 2, 12, 3, 0.01, 0, StyleFamily.COMBO)
        val made = CoachMarket.start(lg.book, 3, lg.input(), listOf(legend))
        assertEquals(10 + CoachMarket.poolSize(10), made)
        for (id in lg.ids) {
            val c = assertNotNull(lg.book.coachOf(id), id)
            assertEquals(Tenure(id, 3, 0, 0, 0, '-'), c.tenures.single())
            assertTrue(c.until in 3..5)
            assertEquals(ClubEconomy.coachWage(c.reputation, 1.0), c.wage)
        }
        val ex = lg.book.coachOf("c01")!!
        assertEquals("g5", ex.player)
        assertEquals(Philosophy.COMBO, ex.philosophy)
        assertEquals(CoachMarket.poolSize(10), lg.book.freeCoaches.size)
        assertEquals(lg.book.coaches.size, lg.book.coaches.map { it.name }.toSet().size)
        // A second start changes nothing.
        val text = lg.book.encode()
        assertEquals(0, CoachMarket.start(lg.book, 3, lg.input(), listOf(legend)))
        assertEquals(text, lg.book.encode())
        // A club founded later hires from the pool; a dissolved club's coach goes back to it.
        val pool = lg.book.freeCoaches.size
        val old = assertNotNull(CoachMarket.release(lg.book, "c10", 3))
        assertNull(CoachMarket.release(lg.book, "c10", 3))
        val hired = assertNotNull(CoachMarket.newClub(lg.book, "c10", 4, lg.input()))
        assertNotEquals(old.id, hired.id)
        assertEquals("c10", hired.club)
        assertEquals(4, hired.tenures.first().from)
        assertEquals(pool, lg.book.freeCoaches.size)
        assertNull(CoachMarket.newClub(lg.book, "c10", 4, lg.input()))
        val gone = CoachMarket.release(lg.book, "c09", 4)!!
        assertNull(gone.club)
        assertEquals('L', gone.tenures.first().end)
        assertNull(lg.book.coachOf("c09"))
        assertTrue(w.coaches.active.values.none { it.club == "c09" })
    }

    /**
     * [nations] leagues of 8 clubs of 8 members run [seasons] seasons of the coach market (random
     * tables, a winter checkpoint with its hires, three retired stars a season), from [CoachMarket.start];
     * returns the book and the sackings each season.
     */
    private fun longRun(nations: Int, seasons: Int): Pair<ClubBook, List<Int>> {
        val codes = Nations.ALL.take(nations).map { it.code }
        val members = LinkedHashMap<String, List<String>>()
        val specs = ArrayList<Eco.Spec>()
        var n = 1
        for ((ni, code) in codes.withIndex()) {
            val cities = ClubNames.cities(Nations.of(code)!!).size
            for (k in 0 until 8) {
                val id = ClubBook.idOf(n++)
                members[id] = (1..8).map { "p${ni}y${k}x$it" }
                specs += Eco.Spec(id, code, members.getValue(id), city = k % cities)
            }
        }
        val book = Eco.world(Eco.book(specs))
        val rate = { key: String -> 1400.0 + 37 * Math.floorMod(key.hashCode(), 11) }
        val leagues = codes.withIndex().map { (ni, code) -> code to members.filterKeys { book.nationOf(it) == code }.values.flatten().also { assertTrue(it.size == 64, "$ni") } }
        fun input(s: Int, retirees: List<Retiree> = emptyList()) = ClubInput(
            book.clubs.flatMap { it.squad }, rate, { k -> book.clubOf(k)?.let { book.nationOf(it) } }, LeagueConfig(),
            coachInput = CoachInput(retirees, names = emptySet(), coachOfYear = book.coaches.firstOrNull { it.club != null }?.id),
        )
        assertTrue(CoachMarket.start(book, 1, input(1)) > 8 * nations)
        val sackings = ArrayList<Int>()
        for (s in 1..seasons) {
            val plan = Eco.plan(s, leagues, seed = 100L + s)
            val rng = Random(s * 31L)
            fun tables() = codes.associateWith { code -> Eco.table(code, book.clubsOf(code).map { it.id }.shuffled(rng)) }
            for (c in book.clubs) Eco.closed(book, c.id, s, 400 + 20 * (ClubBook.number(c.id)!! % 7))
            val winter = CoachMarket.review(book, plan, tables(), rate, winter = true)
            for (m in winter) assertNotNull(CoachMarket.hire(book, m.club!!, s, input(s), plan.seed))
            book.world!!.checkpoint = s
            val retirees = (1..3).map { Retiree("r${s}_$it", "은퇴 $s-$it", codes[it % codes.size], null, 34, s, 12, 1, 0.02) }
            val report = CoachMarket.offseason(book, plan, input(s, retirees), tables())
            sackings += winter.size + report.of('S').size
            // Every club has one coach, no coach two clubs, the pool its floor, at most 100 retired kept.
            for (c in book.clubs) assertEquals(c.id, book.coachOf(c.id)?.club, "season $s ${c.id}")
            assertEquals(book.clubs.size, book.coaches.filter { it.club != null }.map { it.club }.distinct().size)
            assertTrue(book.freeCoaches.size >= CoachMarket.poolSize(book.clubs.size))
            assertTrue(book.retiredCoaches.size <= if (book.size < 500) 40 else 100)
            assertTrue(book.coaches.all { it.tenures.size <= CoachBook.TENURES && it.reputation in 0..100 && it.done == s })
        }
        return book to sackings
    }

    @Test
    fun theMarketRunsForSeasonsAndKeepsItsInvariants() {
        val (book, sackings) = longRun(16, 12)
        val text = book.encode()
        assertEquals(text, ClubBook.decode(text).encode())
        // Random tables against the ratings' expectations: coaches do get sacked, not all of them.
        assertTrue(sackings.sum() in 1 until 128 * 12, "$sackings")
        // Ex-player coaches came in and some were hired; some coaches have retired and a few are kept.
        assertTrue(book.coaches.any { it.player != null })
        // The same run decides the same.
        assertEquals(text, longRun(16, 12).first.encode())
        // The coach lines' bytes at 1000 (127 clubs) and 200 players (24 clubs), for the hand-off.
        fun bytes(b: ClubBook, p: String) = b.encode().lines().filter { it.startsWith("$p ") }.sumOf { it.toByteArray().size + 1 }
        val (small, _) = longRun(3, 12)
        println("COACH_BYTES 1024 members: k=${bytes(book, "k")} kr=${bytes(book, "kr")} coaches=${book.coaches.size} retired=${book.retiredCoaches.size}; " +
            "192 members: k=${bytes(small, "k")} kr=${bytes(small, "kr")} coaches=${small.coaches.size} retired=${small.retiredCoaches.size}; sackings=$sackings")
    }

    @Test
    fun theRecordsHoldTheMostOfEachKind() {
        val lg = Coaching.league(3)
        val w = lg.book.world!!
        Coaching.coach(lg.book, "k1", "c01", from = 1)
        w.coaches.active["k1"] = lg.book.coach("k1")!!.copy(clubTitles = 4, sacked = 1, spells = 2)
        Coaching.coach(lg.book, "k2", "c02", from = 3)
        w.coaches.active["k2"] = lg.book.coach("k2")!!.copy(awards = 2, sacked = 3, longest = 9)
        w.coaches.retiredList += RetiredCoach("k3", "은퇴", "KR", Gender.MALE, -60, 4, Philosophy.ATTACK, 70, null, "CT:4,L0:1,IC:0,COY:1,clubs:6,seasons:20,sacked:0,longest:11")
        val records = CoachMarket.records(lg.book, 10).associateBy { it.kind }
        assertEquals("k3" to 11, records.getValue(CoachRecordKind.TENURE).let { it.coach to it.value })
        // Ties go to the lower id.
        assertEquals("k1" to 4, records.getValue(CoachRecordKind.CLUB_TITLES).let { it.coach to it.value })
        assertEquals("k3" to 6, records.getValue(CoachRecordKind.CLUBS).let { it.coach to it.value })
        assertEquals("k2" to 3, records.getValue(CoachRecordKind.SACKED).let { it.coach to it.value })
        assertEquals("k2" to 2, records.getValue(CoachRecordKind.AWARDS).let { it.coach to it.value })
        assertTrue(records.getValue(CoachRecordKind.TENURE).retired)
        assertEquals(10, lg.book.coach("k1")!!.longestBy(10))
        assertTrue(CoachBook.ID_ORDER.compare("k9", "k10") < 0)
    }
}

package yacht.engine.league

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PyramidTest {
    private fun key(i: Int) = "p%04d".format(i)
    private fun index(k: String) = k.removePrefix("p").toInt()
    private fun rating(k: String) = 2000.0 - index(k)
    private fun nation(k: String) = Nations.ALL[(index(k) * 7) % Nations.ALL.size].code

    private fun context(n: Int, config: LeagueConfig = LeagueConfig()) = Offseason.Context(
        (1..n).map(::key), ::rating, { null }, { k -> "도시${index(k) % 9}" }, config,
        factories = emptyList(), nation = ::nation,
    )

    /** The better rated wins 70% of its matches (seeded), mirrored games. */
    private fun play(f: Fixture, rng: Random): JobResult {
        val aBetter = index(f.a) < index(f.b)
        val aWins = (rng.nextDouble() < 0.7) == aBetter
        val n = if (f.kind == JobKind.TIE) 2 * f.legs else 2
        val g = List(n) { if (aWins) GameScore(220, 180) else GameScore(180, 220) }
        val res = TieRule.match(g).let { if (f.kind == JobKind.TIE && it == 0) 1 else it }
        return JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), res, 0)
    }

    private fun drive(plan: SeasonPlan, rng: Random, until: Double = 2.0) {
        while (!plan.finished && plan.league.progress < until) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(play(f, rng))
        }
    }

    @Test
    fun shapesDoubleTheGroupsTierByTier() {
        val cfg = LeagueConfig()
        assertNull(Pyramid.shape(203, cfg), "a league of 4 groups stays linear")
        assertNull(Pyramid.shape(300, cfg))
        assertEquals(listOf(1, 2, 4), Pyramid.shape(350, cfg))
        assertEquals(listOf(1, 2, 4, 9), Pyramid.shape(800, cfg))
        assertEquals(listOf(1, 2, 4, 8, 5), Pyramid.shape(1000, cfg))
        assertEquals(listOf(1, 2, 4, 8, 16, 9), Pyramid.shape(2000, cfg))
        assertEquals(listOf(1, 2, 4, 8, 16, 29), Pyramid.shape(3000, cfg))
        assertNull(Pyramid.shape(3000, cfg.copy(pyramid = false)))
        assertEquals(listOf(1, 2, 4, 8, 16, 9), Pyramid.shape(3000, cfg.copy(groupSize = 75)))
        val sizes = Pyramid.sizes(1013, listOf(1, 2, 4, 8, 5))
        assertEquals(1013, sizes.sum())
        assertTrue(sizes.max() - sizes.min() <= 1 && sizes.first() >= sizes.last())
    }

    @Test
    fun aLinearBoundaryKeepsItsOldPlaces() {
        val upper = (0 until 10).map { "u$it" }
        val lower = (0 until 10).map { "l$it" }
        val c = Pyramid.crossing(listOf(upper), listOf(lower), 2, true, compareBy { it })
        assertEquals(listOf("u9", "u8"), c.down)
        assertEquals(listOf("l0", "l1"), c.up)
        assertEquals(listOf("u7" to "l2"), c.ties)
        // A third of the smaller side at most.
        assertEquals(1, Pyramid.crossing(listOf(upper.take(4)), listOf(lower), 3, false, compareBy { it }).swap)
    }

    @Test
    fun groupsDropTheirBottomPlacesAndTheRestByPointsAcrossGroups() {
        // Two upper groups, three lower ones: 3 x 2 = 6 up, so each upper group drops 3.
        val upper = listOf((0 until 12).map { "a$it" }, (0 until 12).map { "b$it" })
        val lower = List(3) { g -> (0 until 12).map { "c$g-$it" } }
        val worse = compareBy<String> { it.last().digitToIntOrNull() ?: 0 }.thenBy { it }
        val c = Pyramid.crossing(upper, lower, 2, true, worse)
        assertEquals(2, c.swap)
        assertEquals(6, c.up.size)
        assertEquals(6, c.down.size)
        assertEquals(setOf("a11", "b11", "a10", "b10", "a9", "b9"), c.down.toSet())
        assertEquals(3, c.ties.size)
        assertTrue(c.ties.all { (u, l) -> u in upper.flatten() && l.endsWith("-2") })
    }

    @Test
    fun aPyramidSeasonSendsEveryoneToTheRightTierAndKeepsTheSizes() {
        val ctx = context(1000)
        val plan = Offseason.first(42L, ctx)
        val tiers = plan.league.tiers
        assertFalse(tiers.linear)
        assertEquals(20, plan.league.divisions.size)
        assertEquals(listOf(1, 2, 4, 8, 5), (0 until tiers.count).map { tiers.groupCount(it) })
        assertTrue(plan.league.divisions.all { it.size == 50 })
        assertEquals((1..50).map(::key).toSet(), plan.league.divisions[0].toSet(), "1부 is the best rated")
        assertEquals("", tiers.names[0])
        assertTrue((1 until 20).all { tiers.names[it].isNotEmpty() }, tiers.names.toString())
        assertEquals("3부 B조", tiers.label(4))
        // 49 matchdays whatever the size.
        assertEquals(49, plan.league.matchdays)

        // The saved season keeps its tiers.
        val back = Season.decode(plan.league.encode(), ctx.keys)
        assertEquals(tiers, back.tiers)

        val rng = Random(7)
        drive(plan, rng, 0.5)
        val odds = Odds.compute(plan, ::rating, runs = 300, seed = 3L)
        for (d in plan.league.divisions.indices) {
            assertEquals(1.0, odds.division(d).sumOf { it.leader }, 1e-9, "a leader per group")
        }
        // Into 1부: 3 from each of 2부's two groups, plus up to two playoff winners.
        val up = (1..2).sumOf { d -> odds.division(d).sumOf { it.promotion } }
        val down = odds.division(0).sumOf { it.relegation }
        assertTrue(up in 6.0..8.0 && down in 6.0..8.0, "$up $down")
        // From 5부 (five groups) into 4부 (eight): 15 up and 15 down, the last 7 by points.
        val lastTier = tiers.groups(4)
        val fourth = tiers.groups(3)
        val lastUp = lastTier.sumOf { d -> odds.division(d).sumOf { it.promotion } }
        val fourthDown = fourth.sumOf { d -> odds.division(d).sumOf { it.relegation } }
        assertTrue(lastUp in 15.0 - 1e-9..20.0 && fourthDown in 15.0 - 1e-9..20.0, "$lastUp $fourthDown")

        drive(plan, rng)
        assertTrue(plan.finished)
        val promo = assertNotNull(plan.competition(Comp.PROMOTION_PLAYOFF) as? PromotionPlayoff)
        assertTrue(promo.grouped)
        // One tie per lower group: 2 + 4 + 8 + 5.
        assertEquals(19, promo.spots.size)
        assertEquals(promo.spots.size, promo.spots.map { it.slot }.toSet().size)

        val moves = Offseason.moves(plan)
        val honours = Offseason.honours(plan, moves)
        assertEquals(2, honours.count { it.kind == "L1" })
        assertEquals(4, honours.count { it.kind == "L2" })
        assertEquals(5, honours.count { it.kind == "L4" })
        for (t in 0 until 4) {
            val ups = moves.count { it.from == t + 1 && it.to == t }
            val downs = moves.count { it.from == t && it.to == t + 1 }
            assertEquals(ups, downs, "tier $t keeps its size")
            assertTrue(ups >= 3 * tiers.groupCount(t + 1), "tier $t: $ups up")
        }

        val (next, _) = Offseason.next(plan, ctx)
        val nt = next.league.tiers
        assertEquals(ctx.keys.toSet(), next.league.divisions.flatten().toSet())
        assertEquals(1000, next.league.divisions.sumOf { it.size })
        assertTrue(next.league.divisions.all { it.size == 50 })
        val tierOf = HashMap<String, Int>()
        for (d in next.league.divisions.indices) for (k in next.league.divisions[d]) tierOf[k] = nt.tier(d)
        for (m in moves) assertEquals(m.to, tierOf[m.key], "${m.key} moved ${m.from} -> ${m.to}")
        // The rest stay in their tier.
        val moved = moves.map { it.key }.toSet()
        for (d in plan.league.divisions.indices) for (k in plan.league.divisions[d]) {
            if (k !in moved) assertEquals(tiers.tier(d), tierOf[k], k)
        }
    }

    @Test
    fun retirementsAreFilledFromBelowAndNewcomersStartAtTheBottom() {
        val ctx = context(1000)
        val plan = Offseason.first(5L, ctx)
        drive(plan, Random(11))
        // Ten of 1부 retire; forty newcomers arrive.
        val leaving = plan.league.divisions[0].take(10).toSet()
        val keys = ctx.keys.filter { it !in leaving } + (1001..1040).map(::key)
        val next = Offseason.next(plan, Offseason.Context(keys, ::rating, { null }, { "도시" }, LeagueConfig(), nation = ::nation)).first
        val nt = next.league.tiers
        assertEquals(keys.toSet(), next.league.divisions.flatten().toSet())
        // 1030 players: 21 groups.
        val shape = assertNotNull(Pyramid.shape(keys.size, LeagueConfig()))
        assertEquals(listOf(1, 2, 4, 8, 6), shape)
        val sizes = Pyramid.sizes(keys.size, shape)
        assertEquals(sizes, next.league.divisions.map { it.size })
        val bottom = nt.groups(nt.count - 1).flatMap { next.league.divisions[it] }.toSet()
        assertTrue((1001..1040).map(::key).all { it in bottom })
    }

    @Test
    fun aSmallLeagueStaysAsItWasAndCanGrowIntoAPyramid() {
        val small = context(203)
        val plan = Offseason.first(1L, small)
        assertTrue(plan.league.tiers.linear)
        assertEquals(4, plan.league.divisions.size)
        assertFalse(plan.league.encode().contains("tiers"), "a linear league's file is as before")
        drive(plan, Random(3))
        // The league grows to 600: next season is a pyramid (12 groups: 1, 2, 4, 5).
        val big = Offseason.Context((1..600).map(::key), ::rating, { null }, { "도시" }, LeagueConfig(), nation = ::nation)
        val next = Offseason.next(plan, big).first
        assertEquals(listOf(1, 2, 4, 5), (0 until next.league.tiers.count).map { next.league.tiers.groupCount(it) })
        assertEquals(big.keys.toSet(), next.league.divisions.flatten().toSet())
        // And back down to divisions.
        drive(next, Random(4))
        val back = Offseason.next(next, Offseason.Context((1..250).map(::key), ::rating, { null }, { "도시" }, LeagueConfig(), nation = ::nation)).first
        assertTrue(back.league.tiers.linear)
        assertEquals(4, back.league.divisions.size)
        assertEquals((1..250).map(::key).toSet(), back.league.divisions.flatten().toSet())
    }

    @Test
    fun groupsAreCutByRegion() {
        val ctx = context(1000)
        val plan = Offseason.first(9L, ctx)
        val tiers = plan.league.tiers
        // Within a tier the groups follow the region order: no nation is split over many groups.
        for (t in 1 until tiers.count) {
            val groups = tiers.groups(t).map { d -> plan.league.divisions[d].map(::nation).toSet() }
            val spread = Nations.ALL.map { n -> groups.count { n.code in it } }.max()
            assertTrue(spread <= 2 + groups.size / 4, "tier $t: a nation in $spread groups")
        }
    }
}

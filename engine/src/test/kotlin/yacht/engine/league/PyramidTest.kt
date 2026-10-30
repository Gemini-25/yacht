package yacht.engine.league

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PyramidTest {
    /** The single (legacy) league's tiers. */
    private val Season.legacy: Tiers get() = leagues.list.single().tiers

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
        val tiers = plan.league.legacy
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
        assertEquals(tiers, back.legacy)

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
        val nt = next.league.legacy
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
        val nt = next.league.legacy
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
        assertTrue(plan.league.legacy.linear)
        assertEquals(4, plan.league.divisions.size)
        assertFalse(plan.league.encode().contains("tiers"), "a linear league's file is as before")
        drive(plan, Random(3))
        // The league grows to 600: next season is a pyramid (12 groups: 1, 2, 4, 5).
        val big = Offseason.Context((1..600).map(::key), ::rating, { null }, { "도시" }, LeagueConfig(), nation = ::nation)
        val next = Offseason.next(plan, big).first
        assertEquals(listOf(1, 2, 4, 5), (0 until next.league.legacy.count).map { next.league.legacy.groupCount(it) })
        assertEquals(big.keys.toSet(), next.league.divisions.flatten().toSet())
        // And back down to divisions.
        drive(next, Random(4))
        val back = Offseason.next(next, Offseason.Context((1..250).map(::key), ::rating, { null }, { "도시" }, LeagueConfig(), nation = ::nation)).first
        assertTrue(back.league.legacy.linear)
        assertEquals(4, back.league.divisions.size)
        assertEquals((1..250).map(::key).toSet(), back.league.divisions.flatten().toSet())
    }

    @Test
    fun aSavedSeasonBuildsItsFixturesAgainAndOldFilesStillRead() {
        val ctx = context(400)
        val plan = Offseason.first(3L, ctx)
        drive(plan, Random(1), 0.4)
        val s = plan.league
        // One fixture taken and not played: it stays to play.
        val taken = assertNotNull(s.next(emptySet()))
        val text = s.encode()
        assertTrue(text.startsWith("season 4\n") && !text.contains("\nfx "))
        fun same(b: Season) {
            assertEquals(s.fixturesTotal, b.fixturesTotal)
            assertEquals(s.fixturesPlayed, b.fixturesPlayed + 0)
            for (id in 0 until s.fixturesTotal) {
                assertEquals(s.fixture(id), b.fixture(id))
                assertEquals(s.result(id), b.result(id))
                assertEquals(s.isPlayed(id) && id != taken.id, b.isPlayed(id), "fixture $id")
            }
            for ((k, st) in s.standings) assertEquals(st.points to st.games, b.standings.getValue(k).let { it.points to it.games })
        }
        same(Season.decode(text, ctx.keys))
        // The file of the version before, every fixture in it.
        val old = buildString {
            append("season 3\n")
            for (l in text.lines()) if (l.startsWith("number ") || l.startsWith("division ") || l.startsWith("tiers ")) append(l).append('\n')
            for (id in 0 until s.fixturesTotal) {
                val f = s.fixture(id)
                val played = s.isPlayed(id) && id != taken.id
                append("fx ${f.id} ${f.division} ${f.matchday} ${f.a} ${f.b} ${if (played) 1 else 0}")
                s.result(id)?.let { gs -> append(' ').append(gs.joinToString(";") { Season.encodeGame(it) }) }
                append('\n')
            }
            for (l in text.lines()) if (l.startsWith("standing ") || l.startsWith("pos ")) append(l).append('\n')
        }
        same(Season.decode(old, ctx.keys))
        // Half the size or less.
        assertTrue(text.length * 2 < old.length, "${text.length} vs ${old.length}")
    }

    /** Plays [plan] to the end and checks next season's divisions against [keys]: sizes as the shape has them, movers in their tier. */
    private fun grow(plan: SeasonPlan, keys: List<String>, rng: Random): SeasonPlan {
        drive(plan, rng)
        val ctx = Offseason.Context(keys, ::rating, { null }, { "도시" }, LeagueConfig(), nation = ::nation)
        val moves = Offseason.moves(plan)
        val next = Offseason.next(plan, ctx).first
        val shape = assertNotNull(Pyramid.shape(keys.size, LeagueConfig()))
        assertEquals(Pyramid.sizes(keys.size, shape), next.league.divisions.map { it.size }, "group sizes")
        assertEquals(keys.toSet(), next.league.divisions.flatten().toSet())
        val tierOf = HashMap<String, Int>()
        for (d in next.league.divisions.indices) for (k in next.league.divisions[d]) tierOf[k] = next.league.legacy.tier(d)
        // A player announced as promoted plays in the tier it was promoted to.
        // (Never lower; a league that grows a lot can lift it further.)
        for (m in moves) if (m.to < m.from && m.key in tierOf) assertTrue(tierOf.getValue(m.key) <= m.to, "${m.key} promoted to ${m.to}, plays in ${tierOf[m.key]}")
        return next
    }

    @Test
    fun aLinearLeagueOfManyDivisionsGrowsIntoAWellShapedPyramid() {
        for ((n, divisions) in listOf(200 to 6, 200 to 5, 230 to 4, 300 to 6)) {
            val cfg = LeagueConfig(divisions = divisions)
            val plan = Offseason.first(n.toLong(), context(n, cfg))
            assertTrue(plan.league.legacy.linear)
            grow(plan, (1..1000).map(::key), Random(n.toLong()))
        }
    }

    @Test
    fun aPyramidThatShrinksKeepsItsPromotedPlayersUp() {
        val plan = Offseason.first(8L, context(1013))
        grow(plan, (1..990).map(::key), Random(2))
    }

    @Test
    fun oddsAtTheEndAgreeWithTheMovesWhenGroupsDifferInSize() {
        // 1013 players: groups of 51 and 50 in a tier.
        val plan = Offseason.first(13L, context(1013))
        val rng = Random(5)
        while (!plan.league.finished) {
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(play(f, rng))
        }
        val odds = Odds.compute(plan, ::rating, runs = 1, seed = 1L)
        val cfg = plan.config
        for (t in 0 until plan.league.legacy.count - 1) {
            val c = Pyramid.crossing(plan.league, cfg, plan.league.leagues.list.single(), t)
            val down = plan.league.legacy.groups(t).flatMap { d -> odds.division(d) }.filter { it.relegation >= 1.0 - 1e-9 && it.relegationPlayoff == 0.0 }.map { it.key }.toSet()
            assertEquals(c.down.toSet(), down, "boundary $t: automatic drops")
            val upperTies = plan.league.legacy.groups(t).flatMap { d -> odds.division(d) }.filter { it.relegationPlayoff > 0.0 }.map { it.key }.toSet()
            assertEquals(c.ties.map { it.first }.toSet(), upperTies, "boundary $t: playoff upper sides")
        }
    }

    @Test
    fun groupsAreCutByRegion() {
        val ctx = context(1000)
        val plan = Offseason.first(9L, ctx)
        val tiers = plan.league.legacy
        // Within a tier the groups follow the region order: no nation is split over many groups.
        for (t in 1 until tiers.count) {
            val groups = tiers.groups(t).map { d -> plan.league.divisions[d].map(::nation).toSet() }
            val spread = Nations.ALL.map { n -> groups.count { n.code in it } }.max()
            assertTrue(spread <= 2 + groups.size / 4, "tier $t: a nation in $spread groups")
        }
    }

    @Test
    fun interleaveGoesPlaceByPlace() {
        val better = compareBy<String> { it.last() }.thenBy { it }
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), Pyramid.interleave(listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2")), better))
        // Within a place the better first; never across places.
        assertEquals(listOf("y0", "x9", "x1", "y2"), Pyramid.interleave(listOf(listOf("x9", "x1"), listOf("y0", "y2")), compareBy { it.last() }))
        assertEquals(emptyList(), Pyramid.interleave(emptyList(), better))
    }

    @Test
    fun aNationalSeasonsTablesAndBoundariesArePerLeague() {
        // Korea three divisions of 12, Japan two.
        val keys = (1..60).map(::key)
        val season = Season.start(1, 4L, keys.chunked(12), true, Leagues.national(listOf("KR", "JP"), listOf(3, 2)))
        val plan = SeasonPlan(season, LeagueConfig(national = true))
        drive(plan, Random(6), until = 1.0)
        assertTrue(season.finished)
        val jp = season.leagues.byId("JP")!!
        val worse = Pyramid.worse(season)
        for (t in 0 until 2) {
            assertEquals(listOf(season.table(3 + t).map { it.key }), Pyramid.tables(season, jp, t))
            assertEquals(season.table(3 + t).map { it.key }, Pyramid.ranking(season, jp, t))
        }
        val c = Pyramid.crossing(season, plan.config, jp, 0)
        val own = Pyramid.crossing(listOf(season.table(3).map { it.key }), listOf(season.table(4).map { it.key }), plan.config.autoSwap, true, worse)
        assertEquals(listOf(own.up, own.down, own.ties), listOf(c.up, c.down, c.ties))
        assertEquals(2, c.swap)
        // The deprecated overloads see one ladder of all five divisions (as Season.tiers does): safe, not per league.
        @Suppress("DEPRECATION")
        val flat = (0 until 4).map { Pyramid.crossing(season, plan.config, it) }
        assertEquals(c.down, flat[3].down)
        @Suppress("DEPRECATION")
        val last = Pyramid.ranking(season, 4)
        assertEquals(season.table(4).map { it.key }, last)
    }
}

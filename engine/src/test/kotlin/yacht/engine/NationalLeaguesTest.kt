package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.Competitions
import yacht.engine.league.Confed
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueRoster
import yacht.engine.league.Leagues
import yacht.engine.league.NationalLeagues
import yacht.engine.league.Nations
import yacht.engine.league.Offseason
import yacht.engine.league.PlayerBook
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.TieRule
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** League formation, next season's ladders and the migration from the regional pyramid (ENG 1.1-1.3, 1.7). */
class NationalLeaguesTest {
    private val national = LeagueConfig(national = true, doubleRoundRobin = true)

    /** [n] generated players and the guest, rated by their abilities (ties by key). */
    private class World(n: Int) {
        val book = PlayerBook().also { it.ensureGenerated(n) }
        val keys = book.alive.map { it.key } + LeagueRoster.ADAPTIVE
        val ratings = HashMap<String, Double>()
        fun nation(k: String): String? = book[k]?.identity?.nation
        fun rating(k: String): Double = ratings[k] ?: (1400.0 + 5.0 * (book[k]?.identity?.abilities?.overall ?: 50) - (k.hashCode() and 7) * 0.1)
    }

    private fun ctx(
        w: World,
        config: LeagueConfig,
        keys: List<String> = w.keys,
        playNation: (String) -> String? = w::nation,
        transfersIn: Set<String> = emptySet(),
        returned: Set<String> = emptySet(),
    ) = Offseason.Context(
        keys, w::rating, { null }, { "" }, config, nation = w::nation, playNation = playNation, transfersIn = transfersIn, returned = returned,
    )

    /** The better rated wins 70% of its matches (seeded). */
    private fun play(f: Fixture, w: World, rng: Random): JobResult {
        val aWins = (rng.nextDouble() < 0.7) == (w.rating(f.a) >= w.rating(f.b))
        val n = if (f.kind == JobKind.TIE) 2 * f.legs else 2
        val g = List(n) { if (aWins) GameScore(220, 180) else GameScore(180, 220) }
        val res = TieRule.match(g).let { if (f.kind == JobKind.TIE && it == 0) 1 else it }
        return JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), res, 0)
    }

    private fun drive(plan: SeasonPlan, w: World, rng: Random) {
        while (!plan.finished) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            plan.record(play(plan.next(emptySet(), 0, 1) ?: break, w, rng))
        }
        assertTrue(plan.finished)
    }

    /** Rounds of a double round robin of [s] players (an odd division sits one out each round). */
    private fun rounds(s: Int) = if (s % 2 == 0) 2 * (s - 1) else 2 * s

    @Test
    fun leaguesFormAtEverySize() {
        for ((n, leagues) in listOf(200 to 4, 500 to 4, 1000 to 28, 2000 to 28, 3000 to 28)) {
            val w = World(n)
            val c = ctx(w, national)
            val (divisions, lg) = NationalLeagues.first(w.keys.sortedByDescending(w::rating), c)
            assertEquals(leagues, lg.list.size, "$n players")
            assertTrue(lg.national && lg.list.all { it.tiers.linear })
            if (n < 1000) assertTrue(lg.list.all { LeagueIds.isRegion(it.id) }, lg.toString()) else assertTrue(lg.list.all { Nations.of(it.id) != null }, lg.toString())
            assertEquals(lg.list.map { it.id }, lg.list.map { it.id }.sortedBy { LeagueIds.order(it) }, "league order")
            assertEquals(w.keys.sorted(), divisions.flatten().sorted(), "everyone once")
            for (def in lg.list) {
                val sizes = def.divisions.map { divisions[it].size }
                assertTrue(sizes.all { it in NationalLeagues.MIN_DIVISION..national.divisionSize + NationalLeagues.OVER }, "$n ${def.id} $sizes")
                if (sizes.sum() >= 20) assertTrue(sizes.size >= 2, "$n ${def.id} $sizes")
                assertTrue(sizes.zipWithNext().all { (a, b) -> a >= b && a - b <= 1 }, "$n ${def.id} $sizes")
                // Each league its members, the better rated higher.
                for (d in def.divisions) assertTrue(divisions[d].all { NationalLeagues.home(it, c).let { h -> h == def.id || LeagueIds.confedOf(def.id) == Nations.confedOf(h) } })
            }
            assertTrue(divisions.maxOf { rounds(it.size) } <= 42, "$n players")
            // The guest has no nation: Korea's league.
            val guest = lg.of(divisions.indexOfFirst { LeagueRoster.ADAPTIVE in it }).id
            assertEquals(if (n < 1000) LeagueIds.region(Confed.ASIA) else "KR", guest)
            // An aligned season of these leagues fits the 42 weeks.
            val season = Season.start(1, 9L, divisions, true, lg)
            assertTrue(season.matchdays <= 42, "$n: ${season.matchdays}")
        }
    }

    @Test
    fun aMadePlayerPlaysInTheNationItChose() {
        val w = World(1000)
        val keys = w.keys + "u1"
        val c = Offseason.Context(keys, { 1500.0 }, { null }, { "" }, national, nation = { k -> if (k == "u1") "JP" else w.nation(k) })
        assertEquals("JP", NationalLeagues.home("u1", c))
        val (divisions, lg) = NationalLeagues.first(keys, c)
        assertEquals("JP", lg.of(divisions.indexOfFirst { "u1" in it }).id)
    }

    @Test
    fun aNationOfItsOwnARegionAndTheHysteresis() {
        val counts = mapOf("KR" to 30, "JP" to 21, "CN" to 10, "GB" to 40, "FR" to 12, "DE" to 13, "US" to 25)
        assertEquals(
            mapOf("KR" to "KR", "JP" to "XAS", "CN" to "XAS", "US" to "US", "GB" to "GB", "FR" to "XEU", "DE" to "XEU"),
            NationalLeagues.plan(counts, national, emptySet()),
        )
        // Japan had a league last season: 21 keeps it (down to 20); China alone is too few for
        // 아시아 연합 and joins the smallest league of Asia.
        val kept = NationalLeagues.plan(counts, national, setOf("JP"))
        assertEquals("JP", kept["JP"])
        assertEquals("JP", kept["CN"])
        assertEquals("XAS", NationalLeagues.plan(counts + ("JP" to 19), national, setOf("JP"))["JP"])
        // And the other way: 24 makes a league without one last season.
        assertEquals("JP", NationalLeagues.plan(counts + ("JP" to 24), national, emptySet())["JP"])
        // A 연합 리그 keeps going down to 20 as well.
        val europe = counts + ("FR" to 10) + ("DE" to 11)
        assertEquals("XEU", NationalLeagues.plan(europe, national, setOf("XEU"))["FR"])
        assertEquals("GB", NationalLeagues.plan(europe, national, emptySet())["FR"])
        // A larger leagueMin.
        assertEquals("XAS", NationalLeagues.plan(counts, national.copy(leagueMin = 31), emptySet())["KR"])
    }

    @Test
    fun theRestFormTheWorldLeagueOrJoinTheSmallest() {
        // 25 left over: 국제 연합 리그, of its largest nation's confederation.
        val world = NationalLeagues.plan(mapOf("KR" to 30, "GB" to 30, "BR" to 10, "NG" to 8, "ZA" to 7), national, emptySet())
        assertEquals(listOf("XWO", "XWO", "XWO"), listOf(world["BR"], world["NG"], world["ZA"]))
        assertEquals(Confed.AMERICAS, NationalLeagues.worldConfed(world, mapOf("BR" to 10, "NG" to 8, "ZA" to 7)))
        // Level: the first in Nations.ALL order (US before NG).
        val level = mapOf("KR" to 30, "NG" to 12, "US" to 12)
        assertEquals(Confed.AMERICAS, NationalLeagues.worldConfed(NationalLeagues.plan(level, national, emptySet()), level))
        // 23 left over: each (in Nations.ALL order) joins the smallest league of its
        // confederation, or of all.
        val small = NationalLeagues.plan(mapOf("KR" to 30, "JP" to 26, "GB" to 40, "BR" to 10, "NG" to 8, "CN" to 5), national, emptySet())
        assertEquals("JP", small["CN"], "Asia's smallest")
        assertEquals("KR", small["BR"], "no league in the Americas: the smallest of all (Korea 30, Japan 31 now)")
        assertEquals("JP", small["NG"], "then Japan (31) is the smallest")
        // Level sizes: the league order.
        assertEquals("KR", NationalLeagues.plan(mapOf("KR" to 30, "GB" to 30, "BR" to 5), national, emptySet())["BR"])
        // No league yet: whatever there is forms the 국제 연합 리그.
        val few = NationalLeagues.plan(mapOf("BR" to 5, "NG" to 3), national, emptySet())
        assertEquals(mapOf("BR" to "XWO", "NG" to "XWO"), few)
        // A 국제 연합 리그 of last season keeps going down to 20.
        val shrunk = mapOf("KR" to 30, "GB" to 30, "BR" to 10, "NG" to 6, "ZA" to 5)
        assertEquals("XWO", NationalLeagues.plan(shrunk, national, setOf("XWO"))["BR"])
        assertEquals("KR", NationalLeagues.plan(shrunk, national, emptySet())["BR"])
    }

    @Test
    fun theTierCountKeepsDivisionsBetweenTenAndTheSizePlusTwo() {
        assertEquals(1, NationalLeagues.tierCount(19, 20))
        assertEquals(listOf(10, 10), NationalLeagues.sizes(20, NationalLeagues.tierCount(20, 20)))
        assertEquals(listOf(12, 12), NationalLeagues.sizes(24, NationalLeagues.tierCount(24, 20)))
        assertEquals(listOf(21, 21), NationalLeagues.sizes(42, NationalLeagues.tierCount(42, 20)))
        assertEquals(3, NationalLeagues.tierCount(45, 20))
        assertEquals(8, NationalLeagues.tierCount(159, 20))
        for (d in 16..24) for (n in 20..12 * (d + NationalLeagues.OVER)) {
            val sizes = NationalLeagues.sizes(n, NationalLeagues.tierCount(n, d))
            assertTrue(sizes.size >= 2 && sizes.all { it in NationalLeagues.MIN_DIVISION..d + NationalLeagues.OVER }, "$n at $d: $sizes")
        }
    }

    @Test
    fun nextSeasonPlacesEveryoneOnceWithTheMovesTransfersReturnsAndRookies() {
        val w = World(1000)
        val plan = Offseason.first(4L, ctx(w, national))
        drive(plan, w, Random(9))
        val season = plan.league
        val moves = Offseason.moves(plan)
        assertTrue(moves.isNotEmpty() && moves.all { it.league.isNotEmpty() })
        fun leagueOf(k: String) = season.leagueOf(k)!!.id
        val top = { id: String -> season.table(season.leagues.byId(id)!!.top).map { it.key } }
        // Japan's two best leave; three rookies a nation for Korea, Brazil and Egypt.
        val leaving = top("JP").take(2).toSet()
        val rookies = listOf("KR", "BR", "EG").flatMap { n -> (1..3).map { "r$n$it" } }
        val rookieNation = rookies.associateWith { it.substring(1, 3) }
        // Five players move to Germany's league (its 1부 overflows); one Korean in 1부 comes home
        // from England, one in 2부 from Spain, one rated low into the 2부.
        val movers = (top("GB").take(3) + top("FR").take(2)).filter { it !in moves.map { m -> m.key } }
        val homeHigh = top("GB")[5]
        val homeLow = season.divisions[season.leagues.byId("ES")!!.top + 1].first()
        w.ratings[homeHigh] = 3000.0
        w.ratings[homeLow] = 1000.0
        val keys = w.keys.filter { it !in leaving } + rookies
        val plays = { k: String ->
            when (k) {
                in movers -> "DE"
                homeHigh, homeLow -> "KR"
                else -> rookieNation[k] ?: w.nation(k)
            }
        }
        val c = Offseason.Context(keys, w::rating, { null }, { "" }, national, nation = { rookieNation[it] ?: w.nation(it) }, playNation = plays,
            transfersIn = movers.toSet(), returned = setOf(homeHigh, homeLow))
        val (divisions, lg) = NationalLeagues.next(plan, moves, c)
        assertEquals(keys.sorted(), divisions.flatten().sorted(), "everyone once")
        val tierOf = HashMap<String, Pair<String, Int>>()
        for ((d, ks) in divisions.withIndex()) for (k in ks) tierOf[k] = lg.of(d).id to lg.tier(d)
        // The moves, in every league whose ladder kept its length.
        for (m in moves) {
            if (m.key !in tierOf || m.key in movers || m.key == homeHigh || m.key == homeLow) continue
            if (lg.byId(m.league)?.size != season.leagues.byId(m.league)!!.size) continue
            assertEquals(m.league to m.to, tierOf[m.key], "${m.key} ${m.from} -> ${m.to}")
        }
        // The rest stay where they were, in the leagues nobody left or joined.
        val moved = moves.map { it.key }.toSet()
        val touched = setOf("JP", "DE", "KR", "GB", "FR", "ES", "BR", "EG")
        for (k in keys) {
            if (k in moved || k in rookies || leagueOf(k) in touched) continue
            assertEquals(leagueOf(k) to season.tierOf(k)!!, tierOf[k], k)
        }
        // Transfers in: Germany's 1부, though it overflows by five.
        for (k in movers) assertEquals("DE" to 0, tierOf[k], k)
        val de = lg.byId("DE")!!
        assertEquals(NationalLeagues.sizes(keys.count { plays(it) == "DE" }, de.size)[0], divisions[de.top].size)
        // Promoted into Germany's 1부: still there; the 1부's last places made the room.
        for (m in moves.filter { it.league == "DE" && it.to == 0 && it.from == 1 }) assertEquals("DE" to 0, tierOf[m.key])
        // Home again: by the 1부's median.
        assertEquals("KR" to 0, tierOf[homeHigh])
        assertEquals("KR" to 1, tierOf[homeLow])
        // Rookies: the last tier of their nation's league.
        for (k in rookies) {
            val id = tierOf.getValue(k).first
            assertEquals(lg.byId(id)!!.size - 1, tierOf.getValue(k).second, k)
        }
        // Japan's 1부 lost two: the best of its 2부 who did not just come down went up.
        val jp = lg.byId("JP")!!
        val jpSizes = NationalLeagues.sizes(keys.count { plays(it) == "JP" }, jp.size)
        assertEquals(jpSizes, jp.divisions.map { divisions[it].size })
        val relegated = moves.filter { it.to > it.from }.map { it.key }.toSet()
        val lifted = divisions[jp.top].filter { season.tierOf(it) == 1 && moves.none { m -> m.key == it } }
        val oldTop = season.divisions[season.leagues.byId("JP")!!.top].size
        assertTrue(jpSizes[0] - (oldTop - 2) >= 1)
        assertEquals(jpSizes[0] - (oldTop - 2), lifted.size, divisions[jp.top].toString())
        assertTrue(lifted.none { it in relegated })
    }

    @Test
    fun aLegacyPyramidMigratesByOldTierThenPlace() {
        val w = World(1200)
        // Three of Korea's players are among the best (so in the old 1부).
        for (k in w.keys.filter { w.nation(it) == "KR" }.take(3)) w.ratings[k] = 2500.0
        // Groups of 30: a pyramid six tiers deep.
        val legacy = LeagueConfig(groupSize = 30)
        val plan = Offseason.first(6L, ctx(w, legacy))
        assertEquals(6, plan.league.leagues.list.single().tiers.count)
        drive(plan, w, Random(2))
        val moves = Offseason.moves(plan)
        val honours = Offseason.honours(plan, moves)
        // Each player's tier after the moves, in the old pyramid.
        val oldTier = HashMap<String, Int>()
        for ((d, ks) in plan.league.divisions.withIndex()) for (k in ks) oldTier[k] = plan.league.leagues.tier(d)
        for (m in moves) oldTier[m.key] = m.to
        val relegated = moves.filter { it.to > it.from }.map { it.key }.toSet()
        val (next, _) = Offseason.next(plan, ctx(w, national))
        val s = next.league
        assertTrue(s.leagues.national)
        assertTrue(s.encode().startsWith(Season.NATIONAL_HEADER))
        assertEquals(w.keys.sorted(), s.divisions.flatten().sorted())
        // Korea's players of the old 1부 are in 한국 1부.
        val kr = s.leagues.byId("KR")!!
        val oldTop = w.keys.filter { oldTier[it] == 0 && NationalLeagues.home(it, ctx(w, national)) == "KR" }
        assertTrue(oldTop.isNotEmpty())
        assertTrue(oldTop.all { it in s.divisions[kr.top] }, oldTop.toString())
        for (lg in s.leagues.list) {
            // Down each ladder the old tiers never go back up (those just relegated aside: they
            // are not lifted straight back).
            val ladder = lg.divisions.flatMap { s.divisions[it] }.filter { it !in relegated }
            val tiers = ladder.map { oldTier.getValue(it) }
            assertEquals(tiers.sorted(), tiers, "${lg.id}: $tiers")
            // No 6부 champion above any of the league's old 2부 players.
            val sixth = honours.filter { it.kind == "L5" }.map { it.key }.filter { s.leagueOf(it) == lg }
            val second = lg.divisions.flatMap { s.divisions[it] }.filter { oldTier[it] == 1 }
            for (a in sixth) for (b in second) assertTrue(s.tierOf(a)!! >= s.tierOf(b)!!, "${lg.id}: $a above $b")
        }
        assertTrue(Leagues.decode(s.encode().lines().filter { it.startsWith("league ") }, s.divisions.size) == s.leagues)
    }
}

package yacht.engine

import yacht.engine.league.ClubCup
import yacht.engine.league.ClubSource
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.Confed
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.Honour
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LegendMatch
import yacht.engine.league.MatchRef
import yacht.engine.league.NationCup
import yacht.engine.league.Offseason
import yacht.engine.league.PlayerBook
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonMatch
import yacht.engine.league.SeasonMatches
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.Tiers
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every match of a national season found again ([SeasonMatches], UX design 3.5): a whole season
 * of real games (cheap AIs) with every competition, each fixture rebuilt exactly as it was handed
 * out, played again to the same result, and the refs of the news, the brackets and the feed.
 */
class SeasonMatchesTest {
    private val rules = RuleSet.NINTENDO
    private val config = LeagueConfig.DEFAULT.copy(generated = 1000, leagueMin = 20, divisionSize = 16)

    private val book = PlayerBook().also { it.ensureGenerated(config.generated) }
    private val keys = book.alive.map { it.key }
    private fun nation(k: String): String? = book[k]?.identity?.nation
    private fun rating(k: String): Double = 1400.0 + 5.0 * (book[k]?.identity?.abilities?.overall ?: 50) - (k.hashCode() and 7) * 0.1

    /** Cheap AIs of every kind of keeping, by key: at random, chasing a face, never throwing again, by rules of thumb. */
    private val ais = HashMap<String, LeagueAi>()
    private fun ai(k: String): LeagueAi = ais.getOrPut(k) {
        val make: (Random) -> Player = when (k.hashCode() and 3) {
            0 -> { r -> RandomPlayer(r) }
            1 -> { _ -> KeeperPlayer(rules, upperFirst = false) }
            2 -> { r -> RandomPlayer(r) }
            else -> { _ -> RulePlayer(rules) }
        }
        LeagueAi(k, k, Family.EV, "", make = make)
    }

    /**
     * National season [number] (after a national season of the same leagues, its honours made up
     * from the ratings) with the competitions of [factories], a 클럽 대항전 of each league's best
     * eight as its champion club when due, and a 레전드 매치 when [legend].
     */
    private fun plan(number: Int, factories: List<(SeasonStart) -> yacht.engine.league.Competition?>, legend: Boolean): SeasonPlan {
        val ctx = Offseason.Context(keys, ::rating, { null }, { "" }, config, nation = ::nation)
        val league = Offseason.first(43L, ctx, number).league
        val leagues = league.leagues
        val last = number - 1
        val ranking = keys.sortedByDescending(::rating)
        val honours = ArrayList<Honour>()
        for ((i, kind) in listOf("CL", "CL2", "CUP", "CUP2").withIndex()) honours += Honour(last, kind, ranking[i])
        for ((i, c) in Confed.entries.withIndex()) {
            honours += Honour(last, "CCL.${c.name}", ranking[4 + 2 * i])
            honours += Honour(last, "CCL2.${c.name}", ranking[5 + 2 * i])
        }
        for (lg in leagues.list) league.divisions[lg.top].firstOrNull()?.let { honours += Honour(last, "L0.${lg.id}", it) }
        val flat = Tiers.linear(league.divisions.size)
        val start = SeasonStart(
            number, league.seed, config, ranking, ::rating, league.divisions, league.divisions, honours, { null }, { "" }, honours, ::nation, flat, flat, leagues, leagues,
        )
        val clubs = object : ClubSource {
            override fun champions(season: Int) = leagues.list.map { "c${it.id}" }
            override fun members(club: String) = league.divisions[leagues.byId(club.drop(1))!!.top].take(8)
        }
        val comps = (factories + ClubCup.factory { clubs }).mapNotNull { it(start) }.toMutableList()
        if (legend) comps += LegendMatch.codec.decode(listOf(LegendMatch.HEADER, "e legend 1 1900", "e ${ranking[0]} 2 1800"), number, league.seed)
        return SeasonPlan(league, config, comps)
    }

    /** What a played season leaves: each fixture as handed out with its result, and the news. */
    private class Played(val plan: SeasonPlan) {
        val fixtures = LinkedHashMap<Pair<Comp, Int>, Pair<Fixture, JobResult>>()
        val events = ArrayList<LeagueEvent>()

        /** Fixtures not played yet at the season's middle, as [SeasonMatches] listed them then. */
        val foreseen = HashMap<Pair<Comp, Int>, Fixture>()
    }

    private fun play(plan: SeasonPlan): Played {
        val out = Played(plan)
        var guard = 0
        while (!plan.finished) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            if (out.foreseen.isEmpty() && plan.league.progress >= 0.5) {
                for (m in SeasonMatches.all(plan)) if (!m.played) out.foreseen[m.ref.comp to m.ref.id] = m.fixture
            }
            val f = plan.next(emptySet(), 0, 2) ?: error("stuck at ${plan.fixturesLeft} fixtures left")
            val r = assertNotNull(LeagueMatch.playJob(rules, f, ::ai, plan.config.matchFormat))
            out.fixtures[f.comp to f.id] = f to r
            out.events += plan.record(r)
            check(++guard < 100_000)
        }
        return out
    }

    /** The checks every played season passes; its news links [linkedComps] competitions or more, its brackets [minTies] ties. */
    private fun check(p: Played, linkedComps: Int, minTies: Int) {
        val plan = p.plan
        val all = SeasonMatches.all(plan).toList()
        val byKey = all.associateBy { it.ref.comp to it.ref.id }
        assertEquals(all.size, byKey.size, "every match once")
        assertTrue(all.all { it.played }, "a finished season has played every match it lists")
        assertEquals(p.fixtures.keys, byKey.keys)
        for ((key, fr) in p.fixtures) {
            val (f, r) = fr
            val m = assertNotNull(SeasonMatches.of(plan, f.comp, f.id), "$key")
            assertEquals(MatchRef(plan.number, f.comp, f.id), m.ref)
            assertEquals(f, m.fixture, "$key rebuilt as it was handed out")
            assertEquals(byKey.getValue(key), m)
            assertTrue(SeasonMatches.verify(m, r), "$key: the stored result")
            if (f.comp == Comp.LEAGUE) assertEquals(r.boards.single().games, m.games) else assertNull(m.games)
            assertEquals(f.week, m.week)
            p.foreseen[key]?.let { assertEquals(it, f, "$key foreseen at mid-season") }
        }
        assertTrue(p.foreseen.size > 100, "${p.foreseen.size} foreseen")
        // Played again from the rebuilt fixture: the same result (every competition match, a share of the league's).
        var again = 0
        for ((i, m) in all.withIndex()) {
            if (m.ref.comp == Comp.LEAGUE && i % 9 != 0) continue
            val r = assertNotNull(LeagueMatch.playJob(rules, m.fixture, ::ai, plan.config.matchFormat))
            assertTrue(SeasonMatches.verify(m, r), "${m.ref} again")
            again++
        }
        assertTrue(again > 200, "$again played again")
        // A changed AI does not play its matches the same way.
        val league = all.first { it.ref.comp == Comp.LEAGUE }
        val changed = LeagueAi(league.fixture.a, "", Family.EV, "") { GreedyPlayer() }
        val other = assertNotNull(LeagueMatch.playJob(rules, league.fixture, { k -> if (k == league.fixture.a) changed else ai(k) }, plan.config.matchFormat))
        assertFalse(SeasonMatches.verify(league, other))
        assertFalse(SeasonMatches.verify(league, p.fixtures.values.first { it.first.comp != Comp.LEAGUE }.second), "another fixture")
        // A week's matches are the ones on its week.
        val weeks = all.groupBy { it.week }
        for (w in 1..plan.calendar.lastWeek) {
            val listed = SeasonMatches.ofWeek(plan, w)
            assertTrue(listed.all { it.week == w }, "week $w")
            assertEquals(weeks[w].orEmpty().map { it.ref }.toSet(), listed.map { it.ref }.toSet(), "week $w")
        }
        // Found again from the feed's sides and week (older feed lines have no id).
        for (m in all.filterIndexed { i, m -> m.ref.comp != Comp.LEAGUE || i % 50 == 0 }) {
            val f = m.fixture
            val (a, b) = if (f.kind == JobKind.TEAM) f.lineupA.last() to f.lineupB.last() else f.b to f.a
            val found = assertNotNull(SeasonMatches.find(plan, f.comp, m.week, a, b), "${m.ref}")
            assertEquals(f.comp to f.week, found.fixture.comp to found.fixture.week)
            if (f.kind == JobKind.TEAM) assertEquals(f.lineupA.lastIndex, found.ref.board)
            assertTrue(a in found.fixture.players && b in found.fixture.players)
        }
        // A player's matches, with and without the fixture index.
        val busiest = all.flatMap { it.fixture.players }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }
        val index = SeasonMatches.fixtureIndex(plan, busiest.toSet())
        for (k in busiest) {
            val own = SeasonMatches.ofPlayer(plan, k)
            assertEquals(own, SeasonMatches.ofPlayer(plan, k, index.getValue(k)))
            assertEquals(plan.league.fixturesOf(k), own.count { it.ref.comp == Comp.LEAGUE })
            assertEquals(all.count { k in it.fixture.players }, own.size, k)
            assertTrue(own.all { k in it.fixture.players })
            assertEquals(own.map { if (it.week == SeasonCalendar.NONE) Int.MAX_VALUE else it.week }.sorted(), own.map { if (it.week == SeasonCalendar.NONE) Int.MAX_VALUE else it.week })
            for (m in own.filter { it.fixture.kind == JobKind.TEAM }) assertTrue(m.fixture.lineupA.getOrNull(m.ref.board) == k || m.fixture.lineupB.getOrNull(m.ref.board) == k)
        }
        // News of a tie links to it: a played match with the news' first side.
        val linked = p.events.filter { it.ref != null }
        for (e in linked) {
            val ref = assertNotNull(MatchRef.decode(e.ref!!), e.ref)
            assertEquals(e.comp, ref.comp)
            val m = assertNotNull(SeasonMatches.of(plan, ref), "$e")
            assertTrue(m.played && e.keys.first() in listOf(m.fixture.a, m.fixture.b), "$e of ${m.fixture}")
        }
        for (kind in listOf("cup_round", "final")) assertTrue(p.events.filter { it.kind == kind }.all { it.ref != null }, "every $kind links")
        assertTrue(linked.map { it.comp }.toSet().size >= linkedComps, linked.map { it.comp }.toSet().toString())
        // Bracket ties show their fixture's id once both sides are known; byes never.
        var ties = 0
        for ((comp, view) in Competitions.views(plan)) {
            val brackets = when (view) {
                is CompetitionView.Bracket -> listOf(view)
                is CompetitionView.Groups -> listOfNotNull(view.knockout)
                is CompetitionView.Sections -> view.parts.mapNotNull { it.knockout }
            }
            for (t in brackets.flatMap { it.rounds }.flatMap { it.ties }) {
                if (t.bye) assertEquals(-1, t.id)
                if (t.id < 0) continue
                val m = assertNotNull(SeasonMatches.of(plan, comp, t.id), "$comp ${t.id}")
                assertEquals(t.a to t.b, m.fixture.a to m.fixture.b)
                ties++
            }
        }
        assertTrue(ties >= minTies, "$ties ties")
        // Read back from season.txt: the same matches.
        val codecs = Competitions.codecs + (Comp.LEGEND to LegendMatch.codec)
        val back = SeasonPlan.decode(plan.encode(), keys + "legend", codecs)
        assertEquals(all, SeasonMatches.all(back).toList())
    }

    @Test
    fun everyMatchOfANationalSeasonIsFoundAndPlayedAgain() {
        val plan = plan(6, Competitions.factories(), legend = true)
        val comps = plan.comps.map { it.comp }.toSet()
        for (c in listOf(Comp.CHAMPIONS, Comp.CONT_CHAMPIONS, Comp.CUP, Comp.SUPER, Comp.ALL_STAR, Comp.CLUB_CUP, Comp.LEGEND)) assertTrue(c in comps, "$c in $comps")
        val p = play(plan)
        assertTrue(plan.post.map { it.comp }.containsAll(listOf(Comp.TITLE_PLAYOFF, Comp.PROMOTION_PLAYOFF)))
        check(p, linkedComps = 6, minTies = 300)
    }

    @Test
    fun theWorldCupsMatchesTooTheirBoardsByPlayer() {
        val plan = plan(7, listOf(NationCup::world), legend = false)
        assertEquals(listOf(Comp.WORLD_CUP), plan.comps.map { it.comp })
        val p = play(plan)
        check(p, linkedComps = 1, minTies = 10)
        // Knockout ids from 1000: found by id, and by a board's players.
        val ko = SeasonMatches.ofComp(plan, Comp.WORLD_CUP).filter { it.ref.id >= 1000 }
        assertTrue(ko.isNotEmpty())
        val m = ko.last()
        val found = assertNotNull(SeasonMatches.find(plan, Comp.WORLD_CUP, SeasonCalendar.NONE, m.fixture.lineupB[1], m.fixture.lineupA[1]))
        assertEquals(MatchRef(plan.number, Comp.WORLD_CUP, m.ref.id, 1), found.ref)
        assertNull(SeasonMatches.of(plan, Comp.WORLD_CUP, 999_999))
        assertNull(SeasonMatches.of(plan, Comp.CUP, 0), "no cup this season")
        assertNull(SeasonMatches.of(plan, MatchRef(plan.number + 1, Comp.LEAGUE, 0)), "another season's")
    }

    @Test
    fun refsReadBackInEveryForm() {
        for (r in listOf(MatchRef(3, Comp.LEAGUE, 17), MatchRef(3, Comp.WORLD_CUP, 1203, 4), MatchRef(12, Comp.CUP, 9, 0, 3), MatchRef(1, Comp.CUP, 9, -1, 2))) {
            assertEquals(r, MatchRef.decode(r.encode()))
        }
        assertEquals("3:L:17", MatchRef(3, Comp.LEAGUE, 17).encode())
        assertEquals("3:N:1203:4", MatchRef(3, Comp.WORLD_CUP, 1203, 4).encode())
        assertEquals("3:L:17", MatchRef(3, Comp.LEAGUE, 17, 2, 1).fixtureKey)
        assertEquals(MatchRef(3, Comp.LEAGUE, 17), MatchRef(3, Comp.LEAGUE, 17, 2, 1).whole)
        // A highlight's id has all five fields.
        assertEquals(MatchRef(5, Comp.CHAMPIONS, 104, 0, 2), MatchRef.decode("5:E:104:0:2"))
        for (bad in listOf("", "3:L", "3:?:1", "x:L:1", "3:L:1:2:3:4", "3:L:one", "3:LL:1", "-1:L:1", "3:L:1:-2")) assertNull(MatchRef.decode(bad), bad)
    }
}

package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.ChampionsLeague
import yacht.engine.league.Comp
import yacht.engine.league.Competition
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.ContinentalChampions
import yacht.engine.league.Drama
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.GroupTable
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.MatchFormat
import yacht.engine.league.Odds
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.ViewableCompetition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One switch for the 2-0 sweep bonus ([LeagueConfig.sweepPoints]): with `sweepBonus` off no table
 * gives the bonus point. These parts: the league season, the groups of the champions league and
 * the 대륙 챔피언스, the odds' runs and the highlights' title race.
 */
class SweepLeverTest {
    private val keys = (1..6).map { "ai%02d".format(it) }

    /** Plays the whole league stage, side A sweeping every match. */
    private fun sweptSeason(config: LeagueConfig): Season {
        val plan = SeasonPlan(Season.start(1, 21L, listOf(keys)), config)
        val s = plan.league
        while (true) {
            val f = s.next(emptySet()) ?: break
            s.record(f.id, List(2) { GameScore(230, 170) })
        }
        assertTrue(s.finished)
        return s
    }

    @Test
    fun theLeagueGivesTheBonusOnlyWhenTheRulesKeepIt() {
        val on = sweptSeason(LeagueConfig())
        assertTrue(on.bonus)
        assertEquals(on.fixturesTotal, on.standings.values.sumOf { it.sweeps })
        assertEquals(4 * on.fixturesTotal, on.standings.values.sumOf { it.points })

        val off = sweptSeason(LeagueConfig(sweepBonus = false))
        assertFalse(off.bonus)
        assertEquals(0, off.standings.values.sumOf { it.sweeps })
        assertEquals(3 * off.fixturesTotal, off.standings.values.sumOf { it.points })
        // The same results and the same tables otherwise.
        assertEquals(on.standings.values.map { it.wins }.sorted(), off.standings.values.map { it.wins }.sorted())

        // A format without the bonus has none whatever the rule says.
        assertFalse(sweptSeason(LeagueConfig(matchFormat = MatchFormat.MIRRORED)).bonus)
    }

    @Test
    fun aSavedSeasonKeepsItsBonusRule() {
        val config = LeagueConfig(sweepBonus = false)
        val plan = SeasonPlan(Season.start(1, 22L, listOf(keys)), config)
        val back = SeasonPlan.decode(plan.encode(), keys, emptyMap())
        assertFalse(back.config.sweepBonus)
        assertFalse(back.league.bonus)
        assertTrue(SeasonPlan.decode(SeasonPlan(Season.start(1, 22L, listOf(keys)), LeagueConfig()).encode(), keys, emptyMap()).league.bonus)
    }

    // ---- the groups of the champions league and the 대륙 챔피언스 ---------------------------------

    /** Plays every group match of [c] (it hands them out first), side A sweeping 2-0; returns the group tables. */
    private fun sweptGroups(c: Competition): List<GroupTable> {
        while (true) {
            val f = c.next(1.0, emptySet())?.takeIf { it.kind == JobKind.MATCH } ?: break
            val g = List(2) { GameScore(230, 170) }
            c.record(JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), 1, 0, MatchFormat.GAMES))
        }
        return when (val v = (c as ViewableCompetition).view()) {
            is CompetitionView.Groups -> v.groups
            is CompetitionView.Sections -> v.parts.flatMap { it.groups }
            else -> error("no groups")
        }
    }

    @Test
    fun internationalGroupsGiveTheBonusOnlyWhenTheRulesKeepIt() {
        val ten = listOf(10, 10)
        val w = NationalStart(listOf("KR" to ten, "JP" to ten, "CN" to ten, "VN" to ten))
        for ((config, bonus) in listOf(LeagueConfig(national = true) to true, LeagueConfig(national = true, sweepBonus = false) to false)) {
            val start = w.start(config = config)
            for (c in listOf(assertNotNull(ChampionsLeague.create(start)), assertNotNull(ContinentalChampions.create(start)))) {
                val tables = sweptGroups(c)
                assertTrue(tables.all { it.bonus == bonus }, "${c.comp} $bonus")
                val rows = tables.flatMap { it.rows }
                // 12 matches a group, every one a sweep: 3 points each, and the bonus point when it is on.
                assertEquals(if (bonus) tables.size * 12 else 0, rows.sumOf { it.sweeps }, "${c.comp} $bonus")
                assertEquals(tables.size * 12 * (if (bonus) 4 else 3), rows.sumOf { it.points }, "${c.comp} $bonus")
                // The rule is saved with the competition.
                val again = Competitions.codecs.getValue(c.comp).decode(StringBuilder().also { c.encode(it) }.lines().filter { it.isNotEmpty() }, 2, start.seed)
                assertEquals(bonus, ((again as ViewableCompetition).view().let { v -> (v as? CompetitionView.Groups)?.groups ?: (v as CompetitionView.Sections).parts.flatMap { it.groups } }).all { it.bonus })
            }
        }
    }

    // ---- the odds and the highlights' title race --------------------------------------------------

    @Test
    fun theOddsGiveTheBonusOnlyWhenTheRulesKeepIt() {
        // Half a season of 2-0 sweeps: the runs give a win 4 points as often as the season did, or never.
        fun half(config: LeagueConfig): SeasonPlan {
            val plan = SeasonPlan(Season.start(1, 24L, listOf(keys, keys.map { "$it-2" })), config)
            repeat(plan.league.fixturesTotal / 2) {
                val f = plan.league.next(emptySet())!!
                plan.league.record(f.id, List(2) { GameScore(230, 170) })
            }
            return plan
        }
        val rating = { k: String -> 1500.0 + k.hashCode() % 50 }
        val on = half(LeagueConfig())
        val off = half(LeagueConfig(sweepBonus = false))
        assertTrue(Odds.sweepRate(on.league) > 0.5)
        assertEquals(0.0, Odds.sweepRate(off.league))
        assertTrue(Odds.input(on, rating).sweep > 0.5)
        assertEquals(0.0, Odds.input(off, rating).sweep)
        // The same games, so only the points the runs add differ.
        val a = Odds.compute(on, rating, runs = 400, seed = 1L)
        val b = Odds.compute(off, rating, runs = 400, seed = 1L)
        assertEquals(a.rows.map { it.key }, b.rows.map { it.key })
        assertTrue(a.rows.zip(b.rows).any { (x, y) -> x.expectedPosition != y.expectedPosition })
    }

    @Test
    fun theTitleRaceMarginIsOneWinWithTheBonusPoint() {
        // Four players, no sweep at all (a win has a level game), so both rule sets give the same
        // table: a 14 points, b 10, c and d 3. A win is 4 points with the bonus: b is in the race.
        val four = listOf("a", "b", "c", "d")
        val win = listOf(GameScore(200, 200), GameScore(220, 180))
        val draw = listOf(GameScore(200, 200), GameScore(200, 200))
        // Each pair's two matches, from the first named's side.
        val results = mapOf(
            "ab" to listOf(draw, draw), "ac" to listOf(win, win), "ad" to listOf(win, win),
            "bc" to listOf(win, draw), "bd" to listOf(win, draw), "cd" to listOf(draw, draw),
        )
        fun played(config: LeagueConfig): Season {
            val s = SeasonPlan(Season.start(1, 25L, listOf(four), true), config).league
            val met = HashMap<String, Int>()
            while (true) {
                val f = s.next(emptySet()) ?: break
                val pair = listOf(f.a, f.b).sorted().joinToString("")
                val i = met.merge(pair, 1, Int::plus)!! - 1
                val g = results.getValue(pair)[i]
                // From A's side: flipped when the fixture's A is the pair's second.
                s.record(f.id, if (f.a == pair.substring(0, 1)) g else g.map { GameScore(it.b, it.a) })
            }
            assertTrue(s.finished)
            return s
        }
        val on = played(LeagueConfig())
        val off = played(LeagueConfig(sweepBonus = false))
        for (s in listOf(on, off)) assertEquals(listOf(14, 10, 3, 3), s.table(0).map { it.points })
        val bc = Fixture(Comp.LEAGUE, 0, "b", "c", 0L)
        assertTrue(Drama.titleRace(on, bc))
        assertFalse(Drama.titleRace(off, bc))
    }

    @Test
    fun aSeasonSavedBeforeTheRuleExistedKeepsItsBonus() {
        // The season in play as 945de5e saved it: its rules without the keys added since.
        val added = setOf("national", "divisionSize", "leagueMin", "sweepBonus", "transfers", "continentalCl", "clDirect", "clubSize", "clubCup", "federation")
        val text = SeasonPlan(Season.start(1, 23L, listOf(keys)), LeagueConfig()).encode().lines()
            .filter { l -> !l.startsWith("cfg ") || l.removePrefix("cfg ").substringBefore(' ') !in added }.joinToString("\n")
        assertFalse("cfg sweepBonus" in text)
        val s = SeasonPlan.decode(text, keys, emptyMap()).league
        assertTrue(s.bonus)
        val f = s.next(emptySet())!!
        s.record(f.id, List(2) { GameScore(230, 170) })
        assertEquals(4, s.standings.getValue(f.a).points)
        assertEquals(1, s.standings.getValue(f.a).sweeps)
    }
}

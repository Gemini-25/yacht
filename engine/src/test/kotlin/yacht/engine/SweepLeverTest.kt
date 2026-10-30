package yacht.engine

import yacht.engine.league.GameScore
import yacht.engine.league.LeagueConfig
import yacht.engine.league.MatchFormat
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One switch for the 2-0 sweep bonus ([LeagueConfig.sweepPoints]): with `sweepBonus` off no table
 * gives the bonus point. This part: the league season.
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

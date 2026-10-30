package yacht.engine

import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRoster
import yacht.engine.league.Offseason
import yacht.engine.league.PlayerBook
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * National leagues cost no more than the regional pyramid: at every size and division size a
 * season has no more league fixtures than today's, and its league stage and post-season (4 weeks)
 * fit today's 55 weeks (ENG 1.3).
 */
class FormationBudgetTest {
    @Test
    fun nationalSeasonsAreNoDearerOrLongerThanThePyramid() {
        for (n in listOf(200, 500, 1000, 2000, 3000)) {
            val book = PlayerBook().also { it.ensureGenerated(n) }
            val keys = book.alive.map { it.key } + LeagueRoster.ADAPTIVE
            val rating = { k: String -> 1400.0 + 5.0 * (book[k]?.identity?.abilities?.overall ?: 50) }
            fun ctx(config: LeagueConfig) = Offseason.Context(keys, rating, { null }, { "" }, config, nation = { book[it]?.identity?.nation })
            val legacy = Offseason.first(1L, ctx(LeagueConfig())).league
            for (d in 16..24) {
                val season = Offseason.first(1L, ctx(LeagueConfig(national = true, doubleRoundRobin = true, divisionSize = d))).league
                assertTrue(season.leagues.national)
                assertTrue(season.fixturesTotal <= legacy.fixturesTotal, "$n players at $d: ${season.fixturesTotal} fixtures, the pyramid ${legacy.fixturesTotal}")
                assertTrue(season.matchdays + POST_WEEKS <= MAX_WEEKS, "$n players at $d: ${season.matchdays} weeks")
            }
        }
    }

    private companion object {
        /** The post-season's weeks (title playoffs, double elimination). */
        const val POST_WEEKS = 4

        /** Today's longest season: 51 league weeks and the post-season. */
        const val MAX_WEEKS = 55
    }
}

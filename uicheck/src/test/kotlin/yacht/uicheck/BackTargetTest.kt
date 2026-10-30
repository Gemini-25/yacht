package yacht.uicheck

import yacht.app.core.BackAction
import yacht.app.core.LeagueNav
import yacht.app.core.LeagueTab
import yacht.app.core.Page
import yacht.app.core.Screen
import yacht.app.core.Sheet
import yacht.app.core.backTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackTargetTest {
    private fun back(screen: Screen, nav: LeagueNav = LeagueNav(), rankedFrom: Screen? = null, stopped: () -> Unit = {}) =
        backTarget(screen, nav, rankedFrom, stopped)

    @Test
    fun everyScreen() {
        val expected = mapOf(
            Screen.HOME to BackAction.ToBackground,
            Screen.GAME to BackAction.Go(Screen.HOME),
            Screen.HISTORY to BackAction.Go(Screen.HOME),
            Screen.REVIEW to BackAction.Go(Screen.HISTORY),
            Screen.VERIFY to BackAction.Go(Screen.HOME),
            Screen.QUIZ to BackAction.Go(Screen.HOME),
            Screen.LEAGUE to BackAction.Go(Screen.HOME),
            Screen.WATCH to BackAction.Go(Screen.LEAGUE),
            Screen.BUILDER to BackAction.Go(Screen.LEAGUE),
            Screen.MAKER to BackAction.Go(Screen.LEAGUE),
        )
        // A new screen must be given its back here.
        assertEquals(Screen.entries.toSet(), expected.keys)
        for ((screen, action) in expected) assertEquals(action, back(screen), "$screen")
    }

    @Test
    fun theLeagueGoesBackInsideFirst() {
        val nav = LeagueNav()
        nav.go(LeagueTab.MATCHES)
        nav.open(Page.Match("3:L:12"))
        nav.sheet = Sheet.WEEKS
        repeat(3) { assertEquals(BackAction.Handled, back(Screen.LEAGUE, nav)) }
        assertEquals(LeagueTab.HOME, nav.tab)
        assertTrue(nav.pages.isEmpty())
        assertEquals(BackAction.Go(Screen.HOME), back(Screen.LEAGUE, nav))
    }

    @Test
    fun watchingStopsAndKeepsThePages() {
        val nav = LeagueNav()
        nav.open(Page.Match("3:L:12"))
        var stopped = 0
        assertEquals(BackAction.Go(Screen.LEAGUE), back(Screen.WATCH, nav) { stopped++ })
        assertEquals(1, stopped)
        // Back from watching lands on the report it was started from.
        assertEquals(Page.Match("3:L:12"), nav.page)
    }

    @Test
    fun aRankedGameFromTheLeagueGoesBackToIt() {
        assertEquals(BackAction.Go(Screen.LEAGUE), back(Screen.GAME, rankedFrom = Screen.LEAGUE))
        assertEquals(BackAction.Go(Screen.HOME), back(Screen.GAME, rankedFrom = null))
    }

    @Test
    fun otherScreensLeaveTheLeaguesNavigationAlone() {
        for (screen in Screen.entries - Screen.LEAGUE) {
            val nav = LeagueNav()
            nav.go(LeagueTab.RECORDS)
            nav.open(Page.Lab)
            nav.sheet = Sheet.SETTINGS
            back(screen, nav)
            assertEquals(LeagueTab.RECORDS, nav.tab, "$screen")
            assertEquals(listOf<Page>(Page.Lab), nav.pages.toList(), "$screen")
            assertEquals(Sheet.SETTINGS, nav.sheet, "$screen")
        }
    }
}

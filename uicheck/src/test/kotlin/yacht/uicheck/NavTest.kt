package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import yacht.app.core.LeagueController
import yacht.app.core.LeagueNav
import yacht.app.core.LeagueTab
import yacht.app.core.Page
import yacht.app.core.PlayerTab
import yacht.app.core.Sheet
import yacht.engine.league.Comp
import yacht.engine.league.LeagueConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NavTest {
    @Test
    fun backClosesTheSheetThenPagesThenGoesHomeThenLeaves() {
        val nav = LeagueNav()
        nav.go(LeagueTab.TABLES)
        nav.open(Page.Player("a"))
        nav.open(Page.Club("c1"))
        nav.sheet = Sheet.PLAYER_ACTIONS
        assertTrue(nav.back())
        assertEquals(Sheet.NONE, nav.sheet)
        assertEquals(Page.Club("c1"), nav.page)
        assertTrue(nav.back())
        assertEquals(Page.Player("a"), nav.page)
        assertTrue(nav.back())
        assertNull(nav.page)
        assertEquals(LeagueTab.TABLES, nav.tab)
        assertTrue(nav.back())
        assertEquals(LeagueTab.HOME, nav.tab)
        assertFalse(nav.back())
        assertEquals(LeagueTab.HOME, nav.tab)
    }

    @Test
    fun atMostEightPagesTheOldestDropped() {
        val nav = LeagueNav()
        for (i in 1..9) nav.open(Page.Player("p$i"))
        assertEquals(LeagueNav.MAX_PAGES, nav.pages.size)
        assertEquals(Page.Player("p2"), nav.pages.first())
        assertEquals(Page.Player("p9"), nav.page)
    }

    @Test
    fun theTopPageIsNotOpenedTwice() {
        val nav = LeagueNav()
        nav.open(Page.Player("a"))
        nav.open(Page.Player("a"))
        assertEquals(1, nav.pages.size)
        nav.open(Page.Nations)
        nav.open(Page.Nations)
        assertEquals(listOf(Page.Player("a"), Page.Nations), nav.pages.toList())
        // Not the top one: opened again on top (A → B → A).
        nav.open(Page.Player("a"))
        assertEquals(3, nav.pages.size)
    }

    @Test
    fun replaceTopChangesOnlyTheTopPage() {
        val nav = LeagueNav()
        nav.replaceTop(Page.Player("a"))
        assertEquals(listOf<Page>(Page.Player("a")), nav.pages.toList())
        nav.open(Page.Player("b"))
        nav.replaceTop(Page.Player("b", PlayerTab.STATS))
        assertEquals(listOf(Page.Player("a"), Page.Player("b", PlayerTab.STATS)), nav.pages.toList())
        // The tab is the page's own state: it does not change what loads.
        assertEquals(Page.Player("b").loadKey, nav.page!!.loadKey)
    }

    @Test
    fun closeRemovesThatPageAndKeepsTheSheet() {
        val nav = LeagueNav()
        val a = Page.Player("a")
        nav.open(a)
        nav.open(Page.Competition(Comp.CUP))
        nav.sheet = Sheet.STAGE
        nav.close(a)
        assertEquals(listOf<Page>(Page.Competition(Comp.CUP)), nav.pages.toList())
        assertEquals(Sheet.STAGE, nav.sheet)
        nav.close(a)
        assertEquals(1, nav.pages.size)
    }

    @Test
    fun goingToATabClosesPagesAndTheSheet() {
        val nav = LeagueNav()
        nav.open(Page.Lab)
        nav.sheet = Sheet.SETTINGS
        nav.go(LeagueTab.RECORDS)
        assertTrue(nav.pages.isEmpty())
        assertEquals(Sheet.NONE, nav.sheet)
        assertEquals(LeagueTab.RECORDS, nav.tab)
    }

    @Test
    fun selectLeagueResetsTierGroupAndRound() {
        var told = 0
        val nav = LeagueNav { told++ }
        nav.tier = 2
        nav.group = 3
        nav.round = 7
        nav.selectLeague("JP", tier = 1)
        assertEquals("JP", nav.league)
        assertEquals(1, nav.tier)
        assertEquals(0, nav.group)
        assertNull(nav.round)
        assertEquals(1, told)
        nav.selectLeague(null)
        assertNull(nav.league)
        assertEquals(0, nav.tier)
        assertEquals(2, told)
    }

    @Test
    fun aNewLeagueIsShownFromItsStart() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("nav-reset").toFile()
        try {
            val lg = LeagueController(scope, dir)
            lg.changeConfig(LeagueConfig(generated = 0))
            val nav = lg.nav
            nav.go(LeagueTab.TABLES)
            nav.tier = 2
            nav.group = 1
            nav.round = 7
            nav.chartKeys = listOf("gone")
            nav.week = 12
            nav.open(Page.Compare("gone"))
            // The old league's division, round, chart picks and week mean nothing in the new one
            // (picks of players who left would leave the chart empty).
            lg.reset()
            val end = System.currentTimeMillis() + 10_000
            while (nav.round != null || nav.pages.isNotEmpty()) {
                check(System.currentTimeMillis() < end) { "timed out" }
                Thread.sleep(10)
            }
            assertEquals(0, nav.tier)
            assertEquals(0, nav.group)
            assertEquals(emptyList(), nav.chartKeys)
            assertNull(nav.week)
            // The tab shown stays.
            assertEquals(LeagueTab.TABLES, nav.tab)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun detailForwardsToThePlayerPages() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("nav-detail").toFile()
        try {
            val lg = LeagueController(scope, dir)
            val nav = lg.nav
            // The news still opens a player this way (until its file moves to the pages).
            lg.detail = "a"
            assertEquals(Page.Player("a"), nav.page)
            assertEquals("a", lg.detail)
            nav.open(Page.Lab)
            assertNull(lg.detail)
            // Closing closes only a player page on top.
            lg.detail = null
            assertEquals(listOf(Page.Player("a"), Page.Lab), nav.pages.toList())
            nav.back()
            lg.detail = null
            assertTrue(nav.pages.isEmpty())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aTabKeepsItsChipRowState() {
        val nav = LeagueNav()
        assertSame(nav.chipState(LeagueTab.MATCHES), nav.chipState(LeagueTab.MATCHES))
        assertTrue(nav.chipState(LeagueTab.MATCHES) !== nav.chipState(LeagueTab.RECORDS))
    }
}

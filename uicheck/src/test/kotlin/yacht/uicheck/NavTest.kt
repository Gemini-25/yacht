package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import yacht.app.core.ClubRankKind
import yacht.app.core.ClubTab
import yacht.app.core.InfoTopic
import yacht.app.core.LeagueController
import yacht.app.core.LeagueNav
import yacht.app.core.LeagueTab
import yacht.app.core.MarketTab
import yacht.app.core.MetaTab
import yacht.app.core.Page
import yacht.app.core.PageChips
import yacht.app.core.PlayerTab
import yacht.app.core.ShareScope
import yacht.app.core.Sheet
import yacht.app.core.pageOf
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
    fun aTabKeepsItsChipRowState() {
        val nav = LeagueNav()
        assertSame(nav.chipState(LeagueTab.MATCHES), nav.chipState(LeagueTab.MATCHES))
        assertTrue(nav.chipState(LeagueTab.MATCHES) !== nav.chipState(LeagueTab.RECORDS))
        // A page's chip row keeps its own, apart from the tabs'.
        assertSame(nav.chipState(PageChips.MARKET), nav.chipState(PageChips.MARKET))
        assertTrue(nav.chipState(PageChips.MARKET) !== nav.chipState(PageChips.META))
    }

    // ---- league phase 2: the new pages, the same-key rule, the encyclopedia trail, links ---------------

    /** One page of every kind the phase adds or changes, with its state at the defaults. */
    private val newPages = listOf(
        Page.Club("c05"), Page.Market(), Page.Meta(), Page.Coach("k12"), Page.ClubRanking(), Page.Press(), Page.Events, Page.Event(3),
        Page.WcStory(3), Page.Encyclopedia("p:g001"), Page.Search,
    )

    @Test
    fun theNewPagesLoadByKeysOfTheirOwn() {
        val keys = newPages.map { it.loadKey }
        assertEquals(keys.size, keys.toSet().size, keys.toString())
        // A coach is "co:", not the competitions' "k:"; a club and its encyclopedia page differ.
        assertTrue(Page.Coach("k12").loadKey.startsWith("co:"))
        assertTrue(Page.Competition(Comp.CUP).loadKey.startsWith("k:"))
        assertTrue(Page.Club("c05").loadKey != Page.Encyclopedia("c:c05").loadKey)
        // Rankings of the world and of a league are two lists; one arc, one World Cup, one entry each.
        assertTrue(Page.ClubRanking(scope = "KR").loadKey != Page.ClubRanking().loadKey)
        assertTrue(Page.Event(3).loadKey != Page.Event(4).loadKey)
        assertTrue(Page.WcStory(3).loadKey != Page.WcStory(7).loadKey)
    }

    @Test
    fun tabsSteppersAndScopesShareTheirPagesKey() {
        assertEquals(Page.Club("c05").loadKey, Page.Club("c05", ClubTab.HISTORY).loadKey)
        assertEquals(Page.Market().loadKey, Page.Market(MarketTab.LIVE, replay = 4).loadKey)
        assertEquals(Page.Meta().loadKey, Page.Meta(MetaTab.BOXES, ShareScope.ALL).loadKey)
        assertEquals(Page.Press().loadKey, Page.Press("JP", reveal = 2).loadKey)
        assertEquals(Page.ClubRanking().loadKey, Page.ClubRanking(ClubRankKind.HEALTH).loadKey)
        assertEquals(Page.WcStory(3).loadKey, Page.WcStory(3, revealed = 13, round = 2, playing = true).loadKey)
    }

    @Test
    fun theRewrittenCallSitesOpenWhatTheObjectsDid() {
        // Market and Meta became data classes: `Page.Market()` and `Page.Meta()` are the pages the 9
        // call sites opened as objects, loaded and cached under the same keys.
        assertEquals("market", Page.Market().loadKey)
        assertEquals("meta", Page.Meta().loadKey)
        assertEquals(Page.Market(), Page.Market(null, -1))
        assertEquals(Page.Meta(), Page.Meta(MetaTab.ERAS, ShareScope.ELITE))
        val nav = LeagueNav()
        nav.open(Page.Market())
        nav.open(Page.Market())
        assertEquals(listOf<Page>(Page.Market()), nav.pages.toList())
    }

    @Test
    fun aLinkToThePageOnTopReplacesIt() {
        val nav = LeagueNav()
        nav.open(Page.Player("a"))
        nav.open(Page.Club("c05", ClubTab.HISTORY))
        // 역사's link to the club's own page: the same page, not a copy on top.
        nav.open(Page.Club("c05"))
        assertEquals(listOf(Page.Player("a"), Page.Club("c05")), nav.pages.toList())
        nav.open(Page.Club("c06"))
        assertEquals(3, nav.pages.size)
        nav.open(Page.Market())
        nav.open(Page.Market(MarketTab.RECORDS))
        nav.open(Page.Press())
        nav.open(Page.Press("JP"))
        nav.open(Page.Meta())
        nav.open(Page.Meta(MetaTab.BOXES))
        assertEquals(listOf(Page.Player("a"), Page.Club("c05"), Page.Club("c06"), Page.Market(MarketTab.RECORDS), Page.Press("JP"), Page.Meta(MetaTab.BOXES)), nav.pages.toList())
        // Only the top page: a page with the key further down is opened on top again.
        nav.open(Page.Club("c05"))
        assertEquals(7, nav.pages.size)
    }

    @Test
    fun aReadingChainTakesOneStackSlotAndBacksOutToWhereItStarted() {
        val nav = LeagueNav()
        nav.open(Page.Player("g001"))
        // From an entity page the encyclopedia opens on top; links inside it replace it.
        nav.read("p:g001")
        nav.read("c:c05")
        nav.read("k:k12")
        assertEquals(2, nav.pages.size)
        assertEquals(Page.Encyclopedia("k:k12", listOf("p:g001", "c:c05")), nav.page)
        // The page shown again: nothing changes.
        nav.read("k:k12")
        assertEquals(Page.Encyclopedia("k:k12", listOf("p:g001", "c:c05")), nav.page)
        // Back (the system's, then the bar's ←) walks the trail, then leaves the encyclopedia.
        assertTrue(nav.back())
        assertEquals(Page.Encyclopedia("c:c05", listOf("p:g001")), nav.page)
        nav.leave(nav.page!!)
        assertEquals(Page.Encyclopedia("p:g001"), nav.page)
        nav.leave(nav.page!!)
        assertEquals(listOf<Page>(Page.Player("g001")), nav.pages.toList())
        // The trail keeps the last 20 refs: still one slot.
        nav.read("p:a0")
        for (i in 1..30) nav.read("p:a$i")
        assertEquals(2, nav.pages.size)
        val top = nav.page as Page.Encyclopedia
        assertEquals("p:a30", top.ref)
        assertEquals(LeagueNav.TRAIL, top.trail.size)
        assertEquals((10..29).map { "p:a$it" }, top.trail)
        // Eight pages under a long chain: the page the reader came from is never dropped.
        nav.go(LeagueTab.HOME)
        for (i in 1..7) nav.open(Page.Player("p$i"))
        nav.read("p:x0")
        for (i in 1..12) nav.read("p:x$i")
        assertEquals(LeagueNav.MAX_PAGES, nav.pages.size)
        assertEquals(Page.Player("p1"), nav.pages.first())
    }

    @Test
    fun theInfoSheetClosesBeforeTheTrail() {
        val nav = LeagueNav()
        nav.read("l:KR")
        nav.read("n:KR")
        nav.info = InfoTopic.ERAS
        nav.sheet = Sheet.INFO
        assertTrue(nav.back())
        assertEquals(Sheet.NONE, nav.sheet)
        assertEquals(Page.Encyclopedia("n:KR", listOf("l:KR")), nav.page)
        assertTrue(nav.back())
        assertEquals(Page.Encyclopedia("l:KR"), nav.page)
        assertTrue(nav.back())
        assertNull(nav.page)
    }

    @Test
    fun thePressPageNeverChangesTheShownLeague() {
        var told = 0
        val nav = LeagueNav { told++ }
        nav.selectLeague("KR")
        nav.open(Page.Press("JP"))
        nav.replaceTop(Page.Press("ES"))
        nav.open(Page.ClubRanking(scope = "ES"))
        nav.replaceTop(Page.ClubRanking(ClubRankKind.FANS, scope = "JP"))
        nav.searchQuery = "김"
        assertEquals("KR", nav.league)
        assertEquals(1, told)
    }

    @Test
    fun aLinkNamesItsPage() {
        assertEquals(Page.Player("g001"), pageOf("p:g001"))
        assertEquals(Page.Club("c05"), pageOf("c:c05"))
        // A gone or reused club's id with its founding year: its encyclopedia page.
        assertEquals(Page.Encyclopedia("c:c05@2031"), pageOf("c:c05@2031"))
        assertEquals(Page.Coach("k12"), pageOf("k:k12"))
        assertEquals(Page.League("KR"), pageOf("l:KR"))
        assertEquals(Page.Nation("KR"), pageOf("n:KR"))
        // A competition by its code, its name or an honour's base; one the season lacks: its encyclopedia page.
        assertEquals(Page.Competition(Comp.CHAMPIONS), pageOf("x:${Comp.CHAMPIONS.code}"))
        assertEquals(Page.Competition(Comp.CHAMPIONS), pageOf("x:CL"))
        assertEquals(Page.Competition(Comp.CONT_CHAMPIONS), pageOf("x:CCL.ASIA"))
        assertEquals(Page.Competition(Comp.WORLD_CUP), pageOf("x:WORLD_CUP"))
        assertEquals(Page.Encyclopedia("x:CL"), pageOf("x:CL") { it != Comp.CHAMPIONS })
        assertEquals(Page.Event(4), pageOf("ev:4"))
        assertEquals(Page.WcStory(3), pageOf("wc:3"))
        assertEquals(Page.Market(), pageOf("mk"))
        assertEquals(Page.Market(MarketTab.COACHES), pageOf("mk:COACHES"))
        assertEquals(Page.Press(), pageOf("pr"))
        assertEquals(Page.Press("JP"), pageOf("pr:JP"))
        assertEquals(Page.Meta(), pageOf("meta"))
        assertEquals(Page.Meta(MetaTab.BOXES), pageOf("meta:boxes"))
        assertEquals(Page.ClubRanking(), pageOf("cr"))
        assertEquals(Page.ClubRanking(ClubRankKind.HEALTH), pageOf("cr:HEALTH"))
        // A match ref keeps its own colons.
        assertEquals(Page.Match("7:L:120:1"), pageOf("m:7:L:120:1"))
        assertEquals(Page.Encyclopedia("k:k12"), pageOf("enc:k:k12"))
        // Junk: nothing.
        for (junk in listOf("", "p", "p:", "zz:1", "ev:x", "wc:", "mk:NOPE", "meta:x", "cr:BEST", "x:", "x:ZZ", "m:", "enc:", "pr:", "hello")) {
            assertNull(pageOf(junk), junk)
        }
    }
}

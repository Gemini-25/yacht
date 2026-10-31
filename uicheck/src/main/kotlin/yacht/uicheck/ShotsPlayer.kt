package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.Page
import yacht.app.core.PlayerTab
import yacht.app.core.Sheet
import yacht.app.core.StatScope
import yacht.engine.league.Ladder
import yacht.engine.league.Origin
import java.io.File

/**
 * The player page's shots (lg50-lg54): the best ranked generated player's page on each tab (개요 also
 * dark and at 360 dp), its ⋯ sheet and two players compared, with the league stopped meanwhile; my
 * own page after a ranked game (lg51b). On the way it checks that a page opened again (back from a
 * rival's page) draws its data and tab at once.
 */
internal object ShotsPlayer : LeagueShots {
    override fun running(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        // Stopped while shooting, so every tab shows the same week (and running again after, if it
        // was). A season that closed just before can still put up its review (it is set after the
        // close): closed before each shot.
        val wasRunning = lg.running
        lg.stop()
        waitFor("league stopped", 60_000) { !lg.running }
        fun shoot(name: String, height: Int, dark: Boolean = false, width: Int? = null) {
            if (lg.review != null) lg.dismissReview()
            if (width == null) shot(c, out, name, dark = dark, heightDp = height) else shot(c, out, name, dark = dark, heightDp = height, widthDp = width)
        }
        val top = lg.view?.rows?.filter { it.rank != null && !it.isMe && it.division != null && it.origin != Origin.BASE }?.sortedBy { it.rank }.orEmpty()
        val page = Page.Player(top.firstOrNull()?.key ?: "win")
        nav.open(page)
        // No screen is composed between shots: the harness loads what the page's effect would.
        lg.analytics.loadPlayer(page.key, page.scope)
        waitFor("player page") { lg.analytics.player(page) != null }
        shoot("lg50_player", 1800)
        shoot("lg50_player_dark", 1800, dark = true)
        shoot("lg50_player_360", 1800, width = 360)
        // Just after a season closed its numbers are empty: then the stats tab is shot over the career.
        val stats = if (lg.analytics.player(page)?.cells.isNullOrEmpty()) StatScope.CAREER else StatScope.SEASON
        if (stats == StatScope.CAREER) {
            lg.analytics.loadPlayer(page.key, stats)
            waitFor("career page") { lg.analytics.player(page.copy(scope = stats)) != null }
        }
        for ((tab, name, height) in listOf(
            Triple(PlayerTab.STATS, "lg51_player_stats", 1800),
            Triple(PlayerTab.MATCHES, "lg52_player_matches", 1500),
            Triple(PlayerTab.HONOURS, "lg53_player_honours", 1300),
        )) {
            nav.replaceTop(page.copy(tab = tab, scope = if (tab == PlayerTab.STATS) stats else page.scope))
            shoot(name, height)
        }
        nav.replaceTop(page)
        nav.sheet = Sheet.PLAYER_ACTIONS
        shoot("lg53b_player_actions", 900)
        nav.sheet = Sheet.NONE

        // A on 기록 → its rival B → back: A's page and tab are there before anything loads again.
        val a = page.copy(tab = PlayerTab.STATS)
        nav.replaceTop(a)
        val rival = lg.analytics.player(a)?.rival?.other ?: top.getOrNull(1)?.key
        if (rival != null) {
            val b = Page.Player(rival)
            nav.open(b)
            lg.analytics.loadPlayer(b.key, b.scope)
            waitFor("rival's page") { lg.analytics.player(b) != null }
            nav.back()
            val back = nav.page
            check(back == a && lg.analytics.player(a)?.profile?.key == page.key) { "back from the rival's page: $back, ${lg.analytics.player(a)?.key}" }
            println("player page: back from the rival's page draws ${page.key} on ${a.tab} at once")
        }
        nav.close(a)

        if (top.size >= 2) {
            val compare = Page.Compare(top[0].key, top[1].key)
            nav.open(compare)
            lg.loadCompare(top[0].key, top[1].key)
            waitFor("compare") { lg.compare != null }
            shoot("lg54_compare", 1500)
            lg.closeCompare()
            nav.close(compare)
        }
        if (wasRunning) {
            lg.start()
            waitFor("league running again") { lg.running }
        }
    }

    /** After a ranked game: my own page's 기록 (my ladder line's numbers: I play no official games). */
    override fun stopped(c: AppController, out: File) {
        val lg = c.league
        val me = Page.Player(Ladder.ME, PlayerTab.STATS)
        lg.nav.open(me)
        lg.analytics.loadPlayer(me.key, me.scope)
        waitFor("my page") { lg.analytics.player(me) != null }
        if (lg.review != null) lg.dismissReview()
        shot(c, out, "lg51b_player_me", heightDp = 900)
        lg.nav.close(me)
    }
}

package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.Page
import java.io.File

/** The player page's shots: a player's page and two players compared. */
internal object ShotsPlayer : LeagueShots {
    override fun running(c: AppController, out: File) {
        val nav = c.league.nav
        // A generated player's page (the best ranked), then two players side by side.
        val top = c.league.view?.rows?.filter { it.rank != null && !it.isMe && it.origin != yacht.engine.league.Origin.BASE }?.sortedBy { it.rank } ?: emptyList()
        val player = Page.Player(top.firstOrNull()?.key ?: "win")
        nav.open(player)
        c.league.loadProfile(player.key)
        waitFor("profile") { c.league.profile?.key == player.key }
        shot(c, out, "19_league_detail", heightDp = 1800)
        nav.close(player)
        if (top.size >= 2) {
            val compare = Page.Compare(top[0].key, top[1].key)
            nav.open(compare)
            c.league.loadCompare(top[0].key, top[1].key)
            waitFor("compare") { c.league.compare != null }
            shot(c, out, "19b_league_compare", heightDp = 1500)
            c.league.closeCompare()
            nav.close(compare)
        }
    }
}

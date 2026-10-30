package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.Screen
import java.io.File
import java.util.Random

/** Watching: the watch screen (and later the match reports and the broadcast channel). */
internal object ShotsWatch : LeagueShots {
    override fun stopped(c: AppController, out: File) {
        // A league game throw by throw.
        val rival = c.league.rivalOf("win", Random(3)) ?: "ev"
        c.league.startWatch("win", rival) { 300L }
        c.screen = Screen.WATCH
        waitFor("watch", 180_000) { (c.league.watch?.shown ?: 0) >= 8 }
        c.league.pauseWatch()
        shot(c, out, "23_watch", heightDp = 1300)
        waitFor("watch complete", 180_000) { c.league.watch?.complete == true }
        repeat(30) { c.league.skipWatchTurn() }
        shot(c, out, "24_watch_end", heightDp = 1300)
        c.league.stopWatch()
    }
}

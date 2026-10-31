package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.BoardReport
import yacht.app.core.BroadcastQueue
import yacht.app.core.LeagueTab
import yacht.app.core.MatchCenter
import yacht.app.core.MatchesView
import yacht.app.core.Page
import yacht.app.core.ReportState
import yacht.app.core.Screen
import yacht.app.core.WatchKind
import yacht.app.core.WatchPace
import yacht.app.ui.MatchReportBody
import yacht.app.ui.WatchActions
import yacht.app.ui.WatchBody
import yacht.engine.RuleSet
import yacht.engine.league.Comp
import yacht.engine.league.Origin
import yacht.engine.league.SeasonMatch
import java.io.File
import java.util.Random

/**
 * Watching (lg13, lg60-lg67): the broadcast channel's list, match reports (played, in play, a cup
 * series, a team match, a match to come), the watch screen replaying a real match (and its end),
 * the channel playing its picks (a countdown, a team match and a pick still to be played drawn
 * from it), and a friendly.
 * The league is stopped while shooting (a report in play is shot first, while it runs).
 */
internal object ShotsWatch : LeagueShots {
    override fun running(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        val center = lg.center
        fun clean() {
            if (lg.review != null) lg.dismissReview()
        }
        // A report of a match in play (the league was just started again by the files before: one is waited for).
        val season = lg.view?.clock?.season ?: 1
        fun inPlay() = lg.view?.live?.firstOrNull { it.id >= 0 && it.comp != Comp.FRIENDLY && it.comp != Comp.LAB && it.comp != Comp.LEGEND }
        runCatching { waitFor("a match in play", 30_000) { inPlay() != null } }
        inPlay()?.let { m ->
            val page = Page.Match("$season:${m.comp.code}:${m.id}")
            nav.open(page)
            center.loadReport(page)
            waitFor("live report") { center.report(page) != null }
            clean()
            val r = center.report(page)!!
            println("watch: live report ${page.key}: ${r.state}")
            if (r.state == ReportState.LIVE) {
                shot(c, out, "lg60b_report_live", heightDp = 1300)
            } else {
                // A match is played in milliseconds: when it ended before its report was read, the report as it was in play.
                val live = r.copy(
                    state = ReportState.LIVE, result = "", winner = 0, boards = r.boards.map { it.copy(games = null) }, loading = false,
                    replayable = false, note = yacht.app.core.MatchCenter.LIVE_TEXT, source = yacht.app.core.ReplaySource.NONE,
                )
                shotOf(out, "lg60b_report_live", heightDp = 1300) { MatchReportBody(live, 0, 0, lg.view?.clock) }
            }
            nav.close(page)
        }
        val wasRunning = lg.running
        lg.stop()
        waitFor("league stopped", 60_000) { !lg.running }

        // '📺 이번 주 중계' with its picks.
        nav.go(LeagueTab.MATCHES)
        nav.matches = MatchesView.BROADCAST
        center.refreshPicks()
        waitFor("broadcast picks") { center.broadcast?.season == lg.view?.clock?.season }
        clean()
        shot(c, out, "lg13_broadcast", heightDp = 1300)
        println("watch: picks of week ${center.broadcast?.week}: " + center.broadcast?.picks?.joinToString { "${it.title} ${it.score}${if (it.played) " ✓" else ""}" })
        nav.go(LeagueTab.HOME)

        // The best ranked player's first played league match, its report.
        val top = lg.view?.rows?.filter { it.rank != null && !it.isMe && it.division != null && it.origin != Origin.BASE }?.sortedBy { it.rank }.orEmpty()
        val key = top.firstOrNull()?.key ?: "win"
        val matches = center.matchesNow()
        fun first(cond: (SeasonMatch) -> Boolean) = matches.firstOrNull(cond)
        val played = first { it.ref.comp == Comp.LEAGUE && it.played && key in it.fixture.players && it.fixture.players.none { k -> center.adaptive(k) } }
            ?: first { it.ref.comp == Comp.LEAGUE && it.played }
        var reportShot: yacht.app.core.MatchReport? = null
        if (played != null) {
            val page = Page.Match(played.ref.encode())
            nav.open(page)
            center.loadReport(page)
            waitFor("report", 120_000) { center.report(page)?.loading == false }
            clean()
            shot(c, out, "lg60_report", heightDp = 1700)
            shot(c, out, "lg60_report_dark", heightDp = 1700, dark = true)
            shot(c, out, "lg60_report_360", heightDp = 1700, widthDp = 360)
            reportShot = center.report(page)
            println("watch: report ${page.key}: ${reportShot?.source} ${reportShot?.note}")
            nav.close(page)
        }
        // A 요트컵 series of three games or more.
        first { it.ref.comp == Comp.CUP && it.played && (it.score?.let { s -> s.scoreA + s.scoreB } ?: 0.0) >= 3 }?.let { m ->
            val page = Page.Match(m.ref.encode())
            nav.open(page)
            center.loadReport(page)
            waitFor("cup report", 120_000) { center.report(page)?.loading == false }
            clean()
            shot(c, out, "lg61_report_tie", heightDp = 1700)
            nav.close(page)
        } ?: println("watch: no cup series of three games yet (lg61 not shot)")
        // A team match of five boards (hand-made from the league report: no team match is played this early).
        reportShot?.takeIf { it.boards.isNotEmpty() }?.let { r ->
            val names = top.take(10).map { it.shown }.ifEmpty { listOf(r.nameA, r.nameB) }
            val boards = List(5) { i ->
                val b = r.boards.first()
                val nameA = names.getOrElse(2 * i) { b.nameA }
                val nameB = names.getOrElse(2 * i + 1) { b.nameB }
                b.copy(
                    board = i, nameA = nameA, nameB = nameB, result = if (i == 4) -1 else if (i % 2 == 1) -b.result else b.result,
                    games = b.games?.map { g -> g.copy(label = "${g.index + 1}판 · ${if (g.aFirst) nameA else nameB} 선공") },
                )
            }
            val team = r.copy(
                key = "team", title = "요트 월드컵 8강 · 23주차", week = 23, comp = Comp.WORLD_CUP, team = true, nameA = "🇰🇷 대한민국", nameB = "🇯🇵 일본",
                ratingA = null, ratingB = null, placeA = null, placeB = null, deltaA = null, deltaB = null, result = "보드 3 : 2", winner = 1,
                boards = boards, stakes = listOf("이기는 나라가 4강에 올라요."), news = emptyList(),
            )
            shotOf(out, "lg62_report_team", heightDp = 1700) { MatchReportBody(team, 0, 0, lg.view?.clock) }
        }
        // The best ranked player's next match: a preview.
        first { it.ref.comp == Comp.LEAGUE && !it.played && key in it.fixture.players }?.let { m ->
            val page = Page.Match(m.ref.encode())
            nav.open(page)
            center.loadReport(page)
            waitFor("preview") { center.report(page)?.state == ReportState.SCHEDULED }
            clean()
            shot(c, out, "lg63_preview", heightDp = 1300)
            nav.close(page)
        }

        // The watch screen playing the report's match again, then at its end.
        val rules = c.tables?.rules ?: RuleSet.NINTENDO
        if (played != null) {
            center.setPace(WatchPace.FAST)
            center.watch(played.ref.encode())
            c.screen = Screen.WATCH
            waitFor("replay", 180_000) { (lg.watch?.shown ?: 0) >= 8 || lg.watch?.message != null }
            center.pause()
            shot(c, out, "lg64_watch", heightDp = 1300)
            shot(c, out, "lg64_watch_360", heightDp = 1300, widthDp = 360)
            println("watch: replay ${lg.watch?.ref}: ${lg.watch?.games?.size} games, ${lg.watch?.message}")
            lg.watch?.games?.lastOrNull()?.let { center.pickGame(it.index) }
            waitFor("replay's last game", 180_000) { lg.watch?.complete == true || lg.watch?.message != null }
            repeat(40) { center.skipTurn() }
            shot(c, out, "lg65_watch_end", heightDp = 1300)
            center.stop()
        }

        // The channel: its first played pick, then a countdown and a team match drawn from it.
        center.startBroadcast()
        // Played, ended, refused, or a pick still to be played (not the card of a pick being worked out).
        waitFor("broadcast", 180_000) {
            lg.watch?.let { w -> w.shown >= 8 || w.atEnd || w.message != null || w.waiting != null && w.waiting != MatchCenter.COMPUTING && w.steps.isEmpty() && w.queue != null } == true
        }
        center.pause()
        shot(c, out, "lg66_broadcast", heightDp = 1300)
        lg.watch?.takeIf { it.message == null && it.steps.isNotEmpty() }?.let { w ->
            val names = top.take(10).map { it.name }
            shotOf(out, "lg66b_countdown", heightDp = 1300) {
                WatchBody(w.copy(shown = w.steps.size, complete = true, playing = true, queue = BroadcastQueue(1, 6, "요트컵 8강, ${names.getOrElse(2) { "김민준" }} 대 ${names.getOrElse(3) { "이서연" }}이에요", 3)), rules, WatchActions())
            }
            shotOf(out, "lg66c_watch_team", heightDp = 1300) {
                WatchBody(
                    w.copy(
                        kind = WatchKind.BROADCAST, title = "요트 월드컵 8강 · 23주차", board = 2, boards = 5, matchScore = "보드 2 : 0",
                        boardLines = List(5) { i -> "${i + 1}번 보드 ${names.getOrElse(2 * i) { "A" }} ${if (i < 2) "2:0" else "0:0"} ${names.getOrElse(2 * i + 1) { "B" }}" },
                        queue = BroadcastQueue(2, 6, null, null),
                    ),
                    rules, WatchActions(),
                )
            }
            // A pick still to be played with the league stopped: ▶ 이번 주 진행.
            shotOf(out, "lg66d_waiting", heightDp = 900) {
                WatchBody(
                    w.copy(steps = emptyList(), shown = 0, complete = false, playing = false, waiting = MatchCenter.UNPLAYED_PICK, queue = BroadcastQueue(3, 6, null, null)),
                    rules, WatchActions(),
                )
            }
        }
        center.stop()
        center.setPace(WatchPace.NORMAL)
        c.screen = Screen.LEAGUE
        if (wasRunning) {
            lg.start()
            waitFor("league running again") { lg.running }
        }
    }

    override fun stopped(c: AppController, out: File) {
        // A friendly throw by throw (rated, as the old 관전 was).
        val rival = c.league.rivalOf("win", Random(3)) ?: "ev"
        c.league.center.watchFriendly("win", rival)
        c.screen = Screen.WATCH
        waitFor("friendly", 180_000) { (c.league.watch?.shown ?: 0) >= 8 }
        c.league.center.pause()
        shot(c, out, "lg67_friendly", heightDp = 1300)
        c.league.center.stop()
    }
}

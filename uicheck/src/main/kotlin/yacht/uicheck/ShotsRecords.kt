package yacht.uicheck

import yacht.app.core.AppController
import yacht.app.core.ChronicleLine
import yacht.app.core.ChronicleSeason
import yacht.app.core.ChronicleView
import yacht.app.core.FollowedSeason
import yacht.app.core.HallChampion
import yacht.app.core.LeaderFilter
import yacht.app.core.LeaderKind
import yacht.app.core.LeagueTab
import yacht.app.core.Page
import yacht.app.core.RecordsView
import yacht.app.core.ReviewGameView
import yacht.app.core.ReviewPage
import yacht.app.core.Sheet
import yacht.app.core.StatScope
import yacht.app.ui.ChronicleBody
import yacht.app.ui.ReviewBody
import yacht.app.ui.WrappedCardView
import yacht.app.ui.wrappedCards
import yacht.engine.league.Honour
import yacht.engine.league.LeaderStat
import yacht.engine.league.LeagueRecord
import yacht.engine.league.MetaChange
import yacht.engine.league.Origin
import yacht.engine.league.RatingMove
import yacht.engine.league.RecordBreak
import yacht.engine.league.RecordKind
import yacht.engine.league.ReviewGame
import yacht.engine.league.ReviewLeader
import yacht.engine.league.SeasonReview
import java.io.File

/**
 * The records' shots (lg40-lg47): 기록 › 리더 (the hub, also at 360 dp and over the last 10 matches,
 * and its filter sheet), a whole board (득점왕, 평점왕), 기록실, 전당 and 결산 from the league in play,
 * 더 보기, 탐구 and the league comparison; a season review with Season Wrapped's cards and a
 * chronicle from hand-made data (the run closes no season before these shots). The league is
 * stopped meanwhile, as the player page's shots do.
 */
internal object ShotsRecords : LeagueShots {
    override fun running(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        val an = lg.analytics
        val wasRunning = lg.running
        lg.stop()
        waitFor("league stopped", 60_000) { !lg.running }
        fun shoot(name: String, height: Int, width: Int? = null) {
            if (lg.review != null) lg.dismissReview()
            if (width == null) shot(c, out, name, heightDp = height) else shot(c, out, name, heightDp = height, widthDp = width)
        }

        // 기록 › 리더: this season over everyone, then over the last 10 league matches; its filter sheet.
        nav.go(LeagueTab.RECORDS)
        nav.records = RecordsView.LEADERS
        var season = LeaderFilter()
        nav.leaders = season
        an.loadLeaders(season)
        waitFor("leaders") { an.leaders(season) != null }
        // A season that has just opened has no league match yet: the boards are shot over careers then.
        if (an.leaders(season)!!.boards.all { it.second.isEmpty() }) {
            season = LeaderFilter(scope = StatScope.CAREER)
            nav.leaders = season
            an.loadLeaders(season)
            waitFor("career leaders") { an.leaders(season) != null }
        }
        val hub = an.leaders(season)!!
        println("leaders (${season.scope}): " + hub.boards.joinToString { (k, l) -> "$k ${l.size}" })
        check(hub.boards.first { it.first == LeaderKind.YACHTS }.second.isNotEmpty()) { "the Yacht board is empty after 300 fixtures" }
        shoot("lg40_leaders", 1500)
        checkChips(c)
        shoot("lg40_leaders_360", 1500, width = 360)
        val recent = LeaderFilter(recent = true, area = yacht.app.core.LeaderArea.LEAGUE)
        nav.leaders = recent
        an.loadLeaders(recent)
        waitFor("recent leaders") { an.leaders(recent) != null }
        shoot("lg40b_leaders_recent", 1500)
        nav.sheet = Sheet.LEADER_FILTER
        shoot("lg40c_leader_filter", 1000)
        nav.sheet = Sheet.NONE
        nav.leaders = season

        // Two whole boards: 득점왕 and 평점왕 (평점 2.0's grades).
        for ((kind, name) in listOf(LeaderKind.SCORER to "lg41_board", LeaderKind.MVP to "lg41b_board_rating")) {
            val page = Page.Leaderboard(kind)
            nav.open(page)
            an.loadBoard(kind, season)
            waitFor("board $kind") { an.board(kind, season) != null }
            shoot(name, 1300)
            nav.close(page)
        }
        // A career board.
        val career = LeaderFilter(scope = StatScope.CAREER)
        nav.leaders = career
        nav.open(Page.Leaderboard(LeaderKind.YACHTS))
        an.loadBoard(LeaderKind.YACHTS, career)
        waitFor("career board") { an.board(LeaderKind.YACHTS, career) != null }
        shoot("lg41c_board_career", 1300)
        nav.go(LeagueTab.RECORDS)
        nav.leaders = season

        // 기록실, 전당, 결산 and 더 보기 of the league in play.
        nav.records = RecordsView.ROOM
        an.loadRoom()
        waitFor("records room") { an.room() != null }
        shoot("lg42_room", 1500)
        nav.records = RecordsView.HALL
        an.loadHall()
        waitFor("hall") { an.hall() != null }
        shoot("lg43_hall", 1300)
        nav.records = RecordsView.SEASONS
        an.loadReviews()
        waitFor("reviews") { an.reviewList() != null }
        shoot("lg44_seasons", 1100)
        // A season closed during the run: its real review (the Wrapped cards on top).
        an.reviewList()?.items?.firstOrNull { it.kept }?.let { kept ->
            val page = Page.Review(kept.season)
            nav.open(page)
            an.loadReview(kept.season, lg.center)
            waitFor("review of season ${kept.season}") { an.review(page) != null }
            shoot("lg46c_review_live", 2600)
            nav.close(page)
        }
        nav.records = RecordsView.MORE
        shoot("lg45_more", 1000)

        // 탐구 and the league comparison.
        nav.open(Page.Facts())
        an.loadFacts()
        waitFor("facts") { an.facts() != null }
        shoot("lg47_facts", 1800)
        nav.replaceTop(Page.Facts(leagues = true))
        shoot("lg47b_league_compare", 1800)
        nav.go(LeagueTab.RECORDS)
        nav.open(Page.Chronicle)
        an.loadChronicle()
        waitFor("chronicle") { an.chronicle() != null }
        shoot("lg43c_chronicle_live", 1100)
        nav.go(LeagueTab.HOME)

        // A season review with every part, and the chronicle of a longer league, from hand-made data with the league's players.
        val top = lg.view?.rows?.filter { it.rank != null && !it.isMe && it.origin != Origin.BASE }?.sortedBy { it.rank }?.map { it.key }.orEmpty()
        if (top.size >= 8) {
            val review = sampleReview(c, top)
            shotOf(out, "lg46_review", 2600) { ReviewBody(review) }
            for (card in wrappedCards(review)) shotOf(out, "lg46b_wrapped_${card.name.lowercase()}", 300) { WrappedCardView(review, card) }
            shotOf(out, "lg43b_chronicle", 1300) { ChronicleBody(sampleChronicle(c, top)) {} }
        }
        if (wasRunning) {
            lg.start()
            waitFor("league running again") { lg.running }
        }
    }

    /** Fails the run when 기록's chip row scrolls at 392 dp. */
    private fun checkChips(c: AppController) {
        val state = c.league.nav.chipState(LeagueTab.RECORDS)
        check(state.layoutInfo.totalItemsCount > 0) { "the RECORDS chip row was not drawn" }
        check(!state.canScrollForward && !state.canScrollBackward) { "the RECORDS chip row scrolls at 392 dp" }
        println("chip row RECORDS: fits")
    }

    /** Season 4's review of a national league: real names, made-up numbers. */
    private fun sampleReview(c: AppController, top: List<String>): ReviewPage {
        val lg = c.league
        val a = top[0]
        val b = top[1]
        val d = top[2]
        val e = top[3]
        val f = top[4]
        val g = top[5]
        val h = top[6]
        val i = top[7]
        val review = SeasonReview(
            4,
            champions = listOf(Honour(4, "L0.KR", a), Honour(4, "L0.JP", b), Honour(4, "CL", d), Honour(4, "CUP", e)),
            awards = listOf(Honour(4, "MVP", a), Honour(4, "ROOKIE", f), Honour(4, "MIP", g), Honour(4, "SCORER", b), Honour(4, "YACHT", h)),
            risers = listOf(RatingMove(f, 1488.0, 1561.0), RatingMove(g, 1502.0, 1559.0)),
            fallers = listOf(RatingMove(i, 1571.0, 1512.0)),
            records = listOf(
                RecordBreak(LeagueRecord(RecordKind.BEST_GAME, a, 341.0, 4, b), LeagueRecord(RecordKind.BEST_GAME, d, 332.0, 2)),
                RecordBreak(LeagueRecord(RecordKind.SEASON_RATING2, a, 7.91, 4), null),
            ),
            meta = listOf(MetaChange("요트 칸 평균", 18.2, 19.6), MetaChange("윗칸 보너스 비율", 0.41, 0.44, percent = true)),
            sentences = listOf(
                "${lg.name(a)}가 한국 리그를 우승하고 MVP까지 받았어요.",
                "챔피언스 리그는 ${lg.name(d)}의 차지였어요. 결승에서 2:1로 이겼어요.",
            ),
            leaders = listOf(
                ReviewLeader(LeaderStat.SCORER, b, 248.3), ReviewLeader(LeaderStat.SCORER, a, 246.1), ReviewLeader(LeaderStat.SCORER, e, 241.9),
                ReviewLeader(LeaderStat.MVP, a, 8.12), ReviewLeader(LeaderStat.MVP, b, 7.43), ReviewLeader(LeaderStat.MVP, d, 6.97),
                ReviewLeader(LeaderStat.YACHTS, h, 31.0), ReviewLeader(LeaderStat.YACHTS, a, 29.0), ReviewLeader(LeaderStat.YACHTS, g, 27.0),
                ReviewLeader(LeaderStat.CLUTCH, e, 0.74), ReviewLeader(LeaderStat.CLUTCH, i, 0.69), ReviewLeader(LeaderStat.CLUTCH, b, 0.66),
            ),
            numbers = listOf("경기 수" to "48,210판", "평균 점수" to "231.4점", "요트율" to "38%", "보너스율" to "41%", "300점 경기" to "212판", "이변" to "1,034번", "역전승" to "2,871번", "2:0 완승 비율" to "44%"),
            best = listOf("4:C:1203:0:1", "4:L:5120:0:0"),
            topRated = ReviewGame(a, b, 9.4, "4:L:5120:0:0"),
            upset = ReviewGame(i, d, 214.0, "4:K:77:0"),
            ranking = listOf("KR" to 2, "JP" to -1, "BR" to 3),
            federation = listOf("‘자동 승강 인원 2명 → 1명’ 가결 (찬성 6, 반대 3)", "‘2:0 완승 보너스 없애기’ 부결"),
            transfers = 18,
        )
        val keys = listOf(a, b, d, e, f, g, h, i)
        return ReviewPage(
            "r:4", 4, review, null, keys.associateWith { lg.name(it) },
            listOf(HallChampion("🇰🇷 한국 리그", a, 0), HallChampion("🇯🇵 일본 리그", b, 0), HallChampion("🇧🇷 브라질 리그", e, 0)),
            listOf(Honour(4, "CL", d), Honour(4, "CUP", e)),
            listOf(
                ReviewGameView("4:C:1203:0:1", lg.name(d), lg.name(i), "287 : 279", "역전", true),
                ReviewGameView("4:L:5120:0:0", lg.name(a), lg.name(b), "341 : 198", "평점 9.4", false),
            ),
            ReviewGameView("4:L:5120:0:0", lg.name(a), lg.name(b), "341 : 198", "평점 9.4", true),
            ReviewGameView("4:K:77:0", lg.name(i), lg.name(d), "2 : 1", "레이팅 214점 차 이변", false),
            listOf(FollowedSeason(a, "한국 1부 1위 · 21승 5무 4패", "평점 8.12 · 레이팅 1,642"), FollowedSeason(f, "한국 2부 3위 · 17승 4무 9패", "평점 6.71 · 레이팅 1,561")),
            mapOf("KR" to "🇰🇷 한국 리그", "JP" to "🇯🇵 일본 리그", "BR" to "🇧🇷 브라질 리그"),
        )
    }

    /** A chronicle of 4 seasons with the league's players: real names, made-up seasons. */
    private fun sampleChronicle(c: AppController, top: List<String>): ChronicleView {
        val lg = c.league
        fun n(i: Int) = lg.name(top[i])
        val seasons = (4 downTo 1).map { s ->
            ChronicleSeason(
                s,
                listOfNotNull(
                    ChronicleLine("👑", "🇰🇷 한국 리그 우승 ${n(if (s >= 3) 0 else 1)}", top[0]),
                    ChronicleLine("👑", "🇯🇵 일본 리그 우승 ${n(2 + s % 2)}", top[2]),
                    ChronicleLine("🏆", "챔피언스 리그 우승 ${n(s % 4)}", top[s % 4]),
                    ChronicleLine("⭐", "MVP ${n(s % 3)}", top[s % 3]),
                    ChronicleLine("🌍", "리그 랭킹 1위 🇰🇷 한국 리그").takeIf { s >= 2 },
                    ChronicleLine("📈", "시즌 최고 레이팅 ${n(0)} (1,6${40 + s})", top[0]),
                    ChronicleLine("🔥", "${n(0)} 한국 리그 우승 2연패", top[0]).takeIf { s == 4 },
                ),
            )
        }
        val eras = listOf(
            ChronicleLine("👑", "${n(0)} · 한국 리그 우승 2연패 (시즌 3–4)", top[0]),
            ChronicleLine("🏅", "리그 우승 최다 ${n(0)} (2회)", top[0]),
            ChronicleLine("🌍", "🇰🇷 한국 리그 · 리그 랭킹 1위 3시즌 연속"),
        )
        return ChronicleView(seasons, eras)
    }

}

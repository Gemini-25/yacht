package yacht.uicheck

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import yacht.app.core.AppController
import yacht.app.core.FollowCard
import yacht.app.core.FollowKind
import yacht.app.core.FollowMatch
import yacht.app.core.HomeBanner
import yacht.app.core.HomeView
import yacht.app.core.LeagueSelector
import yacht.app.core.LeagueTab
import yacht.app.core.MatchesView
import yacht.app.core.NewsFilter
import yacht.app.core.Page
import yacht.app.core.RankRow
import yacht.app.core.ResultsFilter
import yacht.app.core.WorldSnippet
import yacht.app.ui.HomeBannerCard
import yacht.app.ui.HomeBody
import yacht.engine.league.ChaseLine
import yacht.engine.league.Comp
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueRank
import yacht.engine.league.RecordKind
import java.io.File

/**
 * The home dashboard's and the news page's shots (lg01 with its dark and 360 dp variants, the
 * 응원 page lg06, the followed results lg12, the news lg70), and the home's height check: the
 * home as the league shows it and a hand-made one with every section full stay within
 * [HOME_MAX_DP] at 392 dp. [last] takes the legacy league's home with the coming-reform banner and
 * the other banners alone.
 */
internal object ShotsHome : LeagueShots {
    /** The home's height budget at 392 dp (UX 2). */
    private const val HOME_MAX_DP = 1600f

    override fun running(c: AppController, out: File) {
        val lg = c.league
        val nav = lg.nav
        val v = lg.view ?: error("no league view")
        val before = lg.favourites
        // Four followed entities (a player, a club, a nation and a league) for ★ 응원 and the 응원 page.
        val top = v.rows.filter { it.rank != null && !it.isMe && it.division != null }.sortedBy { it.rank }
        val wanted = listOfNotNull(
            top.firstOrNull()?.key,
            top.firstOrNull { it.club != null && it.key != top.first().key }?.club?.let { LeagueSelector.CLUB + it },
            top.firstOrNull { it.nation.isNotEmpty() }?.nation?.let { LeagueSelector.NATION + it },
            v.season?.league?.takeIf { it.isNotEmpty() }?.let { LeagueSelector.LEAGUE + it },
        )
        for (k in wanted) if (k !in lg.favourites) lg.toggleFavourite(k)
        lg.center.refreshPicks()
        lg.home.refresh()
        waitFor("home", 120_000) { lg.home.view?.let { h -> h.followed.size >= wanted.size && h.season == lg.view?.clock?.season } == true }

        shot(c, out, "lg01_home", heightDp = 1600, settleMs = 500)
        shot(c, out, "lg01_home_dark", dark = true, heightDp = 1600)
        shot(c, out, "lg01_home_360", heightDp = 1600, widthDp = 360)
        checkHomeHeight(c, out, "lg01_home_body")
        fullHomeCheck(c, out)

        // The 응원 page with every followed entity.
        nav.open(Page.Follows)
        shot(c, out, "lg06_follows", heightDp = 1500, settleMs = 500)
        nav.go(LeagueTab.HOME)

        // 경기 › 결과 of the followed ones.
        nav.go(LeagueTab.MATCHES)
        nav.matches = MatchesView.RESULTS
        nav.results = ResultsFilter.FOLLOWED
        shot(c, out, "lg12_results_followed", heightDp = 1100)
        nav.results = ResultsFilter.ALL
        nav.go(LeagueTab.HOME)

        // 뉴스: everything the league keeps, then the big ones, the international stage and only the followed.
        nav.open(Page.News)
        lg.loadNews()
        waitFor("news") { lg.newsList != null }
        shot(c, out, "lg70_news", heightDp = 1500, settleMs = 300)
        shot(c, out, "lg70_news_360", heightDp = 1500, widthDp = 360)
        nav.newsFilter = NewsFilter.TOP
        shot(c, out, "lg70b_news_top", heightDp = 1300)
        nav.newsFilter = NewsFilter.WORLD
        shot(c, out, "lg70c_news_world", heightDp = 1300)
        nav.newsFilter = NewsFilter.ALL
        nav.newsFollowed = true
        shot(c, out, "lg70d_news_followed", heightDp = 1300)
        nav.newsFollowed = false
        nav.go(LeagueTab.HOME)

        // The other files' shots see the league as it was.
        for (k in wanted) if (k !in before && k in lg.favourites) lg.toggleFavourite(k)
        lg.home.refresh()
    }

    /** The legacy league (ShotsWorld's pass) with national rules next: its home with the coming-reform banner; then the banners alone. */
    override fun last(c: AppController, out: File) {
        val lg = c.league
        val v = lg.view ?: return
        // The 1부's first two (a new league's players are still in placement: no rank to pick them by).
        val top = v.season?.divisions?.firstOrNull()?.take(2)?.map { it.key }.orEmpty()
        for (k in top) if (k !in lg.favourites) lg.toggleFavourite(k)
        lg.home.refresh()
        // The home built with these favourites (a banner built before them names no one).
        val coming = runCatching {
            waitFor("coming reform banner", 60_000) {
                lg.home.view?.let { h -> h.banner is HomeBanner.Coming && h.followed.size >= top.size && (h.banner as HomeBanner.Coming).moves.isNotEmpty() } == true
            }
        }.isSuccess
        if (coming) {
            shot(c, out, "lg01_home_legacy", heightDp = 1600, settleMs = 300)
            checkHomeHeight(c, out, "lg01_home_legacy_body")
        } else {
            println("no coming-reform banner on the legacy home: ${lg.home.view?.banner} (season ${v.season?.number}, national next ${v.season?.nextNational})")
        }
        // 0c and 0b alone (a migrated league's first season and a season's end do not come in this run).
        shotOf(out, "lg01c_banner_reform", 260) { HomeBannerCard(c, v, HomeBanner.Reform(2)) }
        shotOf(out, "lg01d_banner_season_end", 260) {
            HomeBannerCard(c, v, HomeBanner.SeasonEnd(1, listOf("한국 리그 김민준 · 챔스 루카스 시우바 · 요트컵 이서연 · MVP 김민준", "득점왕 사토 하루토 · 요트왕 이서연")))
        }
    }

    /**
     * Fails the run when the home's content is taller than [HOME_MAX_DP] at 392 dp (rendered alone,
     * as [name]: [shotOf]'s 12 dp margins on each side added to the width, so the home is as wide as on the screen).
     */
    private fun checkHomeHeight(c: AppController, out: File, name: String) {
        val v = c.league.view ?: return
        var height by mutableStateOf(0f)
        shotOf(out, name, (HOME_MAX_DP + 300).toInt(), widthDp = 392 + 2 * 12) {
            val density = LocalDensity.current
            Box(Modifier.onSizeChanged { height = with(density) { it.height.toDp().value } }) { HomeBody(c, v) {} }
        }
        check(height in 1f..HOME_MAX_DP) { "the home ($name) is ${height.toInt()} dp high (at most ${HOME_MAX_DP.toInt()})" }
        println("home ($name): ${height.toInt()} dp high")
    }

    /**
     * The home with every section full (a season's end banner, three followed rows with matches,
     * the international stage, the 요트협회 and three record chases) over the league's own data:
     * still within the budget. The home built from the league comes back after.
     */
    private fun fullHomeCheck(c: AppController, out: File) {
        val lg = c.league
        val real = lg.home.view ?: return
        val v = lg.view ?: return
        val keys = v.rows.filter { it.rank != null && !it.isMe }.sortedBy { it.rank }.map { it.key }.take(6)
        if (keys.size < 6) return
        val season = v.clock?.season ?: return
        fun match(k: String, played: Boolean) = FollowMatch("$season:L:0", 12, "한국 1부", k, lg.name(k), if (played) 1 else null, if (played) "2:0" else null, played, if (played) null else 61)
        val cards = (0 until 3).map { i ->
            FollowCard(FollowKind.PLAYER, keys[i], keys[i], lg.name(keys[i]), null, "한국 1부 ${i + 2}위", "레이팅 1,640 (+8)", match(keys[i + 3], true), match(keys[5 - i], false), "WWDLW", null)
        }
        fun rank(id: String, i: Int) = LeagueRank(id, i, i, 30.0 - 4 * i, listOf(8.0, 7.0), 1600.0, 2, i == 0, 1 - i)
        val world = WorldSnippet(
            listOf(Comp.CHAMPIONS to "조별 리그", Comp.CONT_CHAMPIONS to "8강"),
            listOf("KR", "JP", "ES").mapIndexed { i, id -> RankRow(rank(id, i), LeagueIds.label(id), LeagueIds.short(id), LeagueIds.icon(id), 4.5 - i) },
            listOf("KR" to 4.5, "JP" to 3.5, "ES" to 2.5),
        )
        val chase = listOf(
            ChaseLine(RecordKind.SEASON_POINTS, keys[0], 41.0, 88.0, 85.0, keys[1]),
            ChaseLine(RecordKind.STREAK, keys[1], 9.0, Double.NaN, 11.0, keys[2]),
            ChaseLine(RecordKind.CAREER_YACHTS, keys[2], 410.0, Double.NaN, 413.0, keys[3]),
        )
        val full = HomeView(
            season, real.week, HomeBanner.SeasonEnd(season - 1, listOf("한국 리그 ${lg.name(keys[0])} · 챔스 ${lg.name(keys[1])} · 요트컵 ${lg.name(keys[2])} · MVP ${lg.name(keys[0])}", "득점왕 ${lg.name(keys[3])} · 요트왕 ${lg.name(keys[4])}")),
            cards, 2, world, 3, 1, chase,
        )
        lg.home.show(full)
        try {
            checkHomeHeight(c, out, "lg01b_home_full")
        } finally {
            lg.home.show(null)
        }
        waitFor("home again", 120_000) { lg.home.view?.let { it !== full } == true }
    }
}

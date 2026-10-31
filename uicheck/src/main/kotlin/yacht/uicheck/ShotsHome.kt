package yacht.uicheck

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import yacht.app.core.AppController
import yacht.app.core.BroadcastPick
import yacht.app.core.BroadcastView
import yacht.app.core.FeedItem
import yacht.app.core.FollowCard
import yacht.app.core.FollowKind
import yacht.app.core.FollowMatch
import yacht.app.core.HomeBanner
import yacht.app.core.HomeView
import yacht.app.core.LeagueSelector
import yacht.app.core.LeagueTab
import yacht.app.core.LeagueView
import yacht.app.core.MatchCenter
import yacht.app.core.MatchesView
import yacht.app.core.NewsFilter
import yacht.app.core.Page
import yacht.app.core.RankRow
import yacht.app.core.ResultsFilter
import yacht.app.core.WorldSnippet
import yacht.app.ui.HomeBannerCard
import yacht.app.ui.HomeBody
import yacht.engine.league.Article
import yacht.engine.league.ChaseLine
import yacht.engine.league.Comp
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueRank
import yacht.engine.league.RecordKind
import java.io.File

/**
 * The home dashboard's and the news page's shots (lg01 with its dark and 360 dp variants, the
 * 응원 page lg06, the followed results lg12, the news lg70), and the home's height check: the
 * home as the league shows it and a hand-made one at its worst (lg01b, every section at its
 * fullest, each section's height logged) stay within [HOME_MAX_DP] at 392 dp. [last] takes the
 * legacy league's home with the coming-reform banner and the other banners alone.
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

    /**
     * The hand-made worst-case home (lg01b), with the league stopped: while it runs at full speed a
     * new week every second or two brings the league's own broadcast picks over the hand-made ones.
     */
    override fun stopped(c: AppController, out: File) {
        val lg = c.league
        lg.home.refresh()
        waitFor("home", 120_000) { lg.home.view?.season == lg.view?.clock?.season }
        fullHomeCheck(c, out)
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
     * Fails the run when the home's content ([v]'s) is taller than [HOME_MAX_DP] at 392 dp (rendered
     * alone, as [name]: [shotOf]'s 12 dp margins on each side added to the width, so the home is as
     * wide as on the screen). Returns its height.
     */
    private fun checkHomeHeight(c: AppController, out: File, name: String, v: LeagueView? = c.league.view): Float {
        v ?: return 0f
        var height by mutableStateOf(0f)
        shotOf(out, name, (HOME_MAX_DP + 300).toInt(), widthDp = HOME_SHOT_DP) {
            val density = LocalDensity.current
            Box(Modifier.onSizeChanged { height = with(density) { it.height.toDp().value } }) { HomeBody(c, v) {} }
        }
        check(height in 1f..HOME_MAX_DP) { "the home ($name) is ${height.toInt()} dp high (at most ${HOME_MAX_DP.toInt()})" }
        println("home ($name): ${height.toInt()} dp high")
        return height
    }

    /** A home shot's width: the 392 dp screen and [shotOf]'s 12 dp margins on each side. */
    private const val HOME_SHOT_DP = 392 + 2 * 12

    /**
     * The home at its worst (UX 15.2), every section hand-made at its fullest over the league's
     * players: a season's end whose two lines wrap to two lines each, three followed rows with
     * matches, this week's broadcast with three played picks and two to come (their names on two
     * lines), three official results, 내 리그 with two followed players below its top three, the
     * international stage, the 요트협회's three proposals, three record chases, two front-page
     * articles and next week. It must stay within the budget; every section's height is logged
     * (the home measured without it: its 6 dp gap left out). The league's own home and broadcast
     * come back after.
     */
    private fun fullHomeCheck(c: AppController, out: File) {
        val lg = c.league
        val real = lg.home.view ?: return
        val v = lg.view ?: return
        val clock = v.clock ?: return
        val shownSeason = v.season ?: return
        val season = clock.season
        val keys = v.rows.filter { it.rank != null && !it.isMe }.sortedBy { it.rank }.map { it.key }.take(6)
        if (keys.size < 6) return
        // The longest names, wherever a line can wrap.
        val long = v.rows.filter { !it.isMe && it.origin != yacht.engine.league.Origin.BASE }.map { it.key }.sortedByDescending { lg.name(it).length }.take(10)
        if (long.size < 10) return
        fun name(k: String) = lg.name(k)
        fun match(k: String, played: Boolean) = FollowMatch("$season:L:0", 12, "한국 1부", k, name(k), if (played) 1 else null, if (played) "2:0" else null, played, if (played) null else 61)
        val cards = (0 until 3).map { i ->
            FollowCard(FollowKind.PLAYER, keys[i], keys[i], name(keys[i]), null, "한국 1부 ${i + 2}위", "레이팅 1,640 (+8)", match(long[i], true), match(long[i + 3], false), "WWDLW", null)
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
        val end = HomeBanner.SeasonEnd(
            season - 1,
            listOf(
                "한국 리그 ${name(long[0])} · 챔스 ${name(long[1])} · 요트컵 ${name(long[2])} · MVP ${name(long[3])}",
                "득점왕 ${name(long[4])} · 요트왕 ${name(long[5])} · 승격 4명",
            ),
        )
        val full = HomeView(season, real.week, end, cards, 2, world, 3, 1, chase)
        // This week's picks, all played, and two of next week's.
        fun pick(i: Int, week: Int, played: Boolean) = BroadcastPick(
            "$season:L:${9000 + i}", week, Comp.LEAGUE, "한국 리그 1부 ${week}R", long[i % long.size], long[(i + 5) % long.size], "우승 경쟁 · 상위권 맞대결",
            emptyList(), 90 - i, played, if (played) "2:0" else null, false,
        )
        val broadcast = BroadcastView(season, clock.week, (0 until 3).map { pick(it, clock.week, true) }, 0, (3 until 5).map { pick(it, clock.week + 1, false) })
        // Three official results, and two front-page articles of this week.
        val feed = (0 until 3).map { i ->
            FeedItem(
                long[i], name(long[i]), long[i + 5], name(long[i + 5]), listOf(GameScore(251, 208), GameScore(240, 233)), 0, 0, System.currentTimeMillis() - i, emptyList(),
                comp = Comp.CHAMPIONS, label = "8강", week = clock.week, season = season, ref = "$season:${Comp.CHAMPIONS.code}:${9000 + i}", replay = true,
            )
        }
        val articles = (0 until 2).map { i ->
            Article(
                season, "upset", "${name(long[i + 6])}, ${name(long[i + 7])} 꺾고 ${i + 4}연승을 달렸어요 — 우승 경쟁이 더 뜨거워졌어요",
                "레이팅이 ${120 + 40 * i}점 낮은 쪽이 이겼어요. 마지막 판 마지막 칸에서 요트를 굴려 뒤집었고, 이번 시즌 가장 큰 이변으로 남을 것 같아요. 두 선수는 다음 달 다시 만나요.",
                listOf(long[i + 6], long[i + 7]), Comp.LEAGUE, 5, clock.week, "$season:L:${9100 + i}",
            )
        }
        val view = v.copy(feed = feed, news = articles + v.news)
        // 내 리그 with two followed players below its top three (followed for the check only).
        val below = shownSeason.divisions.withIndex().flatMap { (d, rows) -> rows.withIndex().filter { (i, _) -> d > 0 || i >= 3 }.map { it.value.key } }
            .filter { it !in lg.favourites }.take(2)
        for (k in below) lg.toggleFavourite(k)
        val before = showBroadcast(lg, broadcast)
        lg.home.show(full)
        try {
            // Each section's height: the home without it (its 6 dp gap with it), the footer's what is
            // left (named in ASCII: the log is read on consoles without Hangul).
            val without = linkedMapOf(
                "banner" to Triple(full.copy(banner = null), view, broadcast),
                "followed" to Triple(full, view.copy(season = null), broadcast),
                "big matches" to Triple(full, view, broadcast.copy(picks = emptyList())),
                "latest results" to Triple(full, view.copy(feed = emptyList()), broadcast),
                "my league" to Triple(full, view.copy(season = shownSeason.copy(divisions = emptyList())), broadcast),
                "world stage" to Triple(full.copy(world = null), view, broadcast),
                "federation" to Triple(full.copy(pendingProposals = 0), view, broadcast),
                "record chase" to Triple(full.copy(chase = emptyList()), view, broadcast),
                "top news" to Triple(full, view.copy(news = emptyList()), broadcast),
                "next week" to Triple(full, view.copy(clock = clock.copy(over = true)), broadcast),
            )
            // A refresh of the picks asked for before this check may land late (and a league left
            // running brings its own with each week): a measure they cut into is taken again.
            fun <T> shown(hv: HomeView, bv: BroadcastView, what: String, measure: () -> T): T {
                for (attempt in 1..MEASURE_TRIES) {
                    lg.home.show(hv)
                    showBroadcast(lg, bv)
                    val r = measure()
                    if (lg.center.broadcast === bv && lg.home.view === hv) return r
                    println(
                        "home (lg01b_home_full): replaced while $what was measured (try $attempt of $MEASURE_TRIES): " +
                            "broadcast ${lg.center.broadcast === bv} (${lg.center.broadcast?.let { "${it.season}/${it.week} ${it.picks.size}" }}), home ${lg.home.view === hv} (${lg.home.view?.week})",
                    )
                    Thread.sleep(300)
                }
                error("the hand-made home was replaced while $what was measured, $MEASURE_TRIES times")
            }
            val total = shown(full, broadcast, "the full home") { checkHomeHeight(c, out, "lg01b_home_full", view) }
            val heights = LinkedHashMap<String, Float>()
            for ((section, state) in without) {
                val (hv, sv, bv) = state
                heights[section] = total - shown(hv, bv, section) { heightOf(HOME_SHOT_DP, (HOME_MAX_DP + 300).toInt()) { HomeBody(c, sv) {} } } - GAP_DP
            }
            // Without a season both ★ 응원 and 내 리그 go: ★ 응원 is what goes beyond 내 리그.
            heights["followed"] = heights.getValue("followed") - heights.getValue("my league") - GAP_DP
            heights["footer"] = total - PADDING_DP - heights.values.sumOf { (it + GAP_DP).toDouble() }.toFloat()
            val lines = end.lines.map { lineCount(it, type.bodySmall, BANNER_TEXT_DP) }
            println(
                "home (lg01b_home_full) sections: " + heights.entries.joinToString(", ") { "${it.key} ${it.value.toInt()}" } +
                    "; ${heights.size - 1} gaps of ${GAP_DP.toInt()} dp, ${PADDING_DP.toInt()} dp padding",
            )
            println("home (lg01b_home_full): the SeasonEnd lines take ${lines.joinToString(" and ")} lines; followed below my league's top three: ${below.size}")
        } finally {
            lg.home.show(null)
            showBroadcast(lg, before)
            lg.center.refreshPicks()
            for (k in below) if (k in lg.favourites) lg.toggleFavourite(k)
        }
        waitFor("home again", 120_000) { lg.home.view?.let { it !== full } == true }
    }

    /** Measures of the hand-made home taken again when the league's own picks cut into them. */
    private const val MEASURE_TRIES = 5

    /** The space between two of the home's sections, and above and below them. */
    private const val GAP_DP = 6f
    private const val PADDING_DP = 16f

    /** A SeasonEnd banner line's width at 392 dp: the home's 12 dp sides and the card's 14 and 6 dp. */
    private const val BANNER_TEXT_DP = 392f - 2 * 12 - 14 - 6

    /** The default type scale the app's theme uses. */
    private val type = androidx.compose.material3.Typography()

    /**
     * Shows [b] as the broadcast channel's view on the home (the harness's hand-made one; the
     * match centre keeps its view's setter to itself, so the harness sets its state directly) and
     * returns the view it replaced.
     */
    private fun showBroadcast(lg: yacht.app.core.LeagueController, b: BroadcastView?): BroadcastView? {
        val field = MatchCenter::class.java.getDeclaredField("broadcast\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(lg.center) as MutableState<BroadcastView?>
        return state.value.also { state.value = b }
    }
}

package yacht.uicheck

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import yacht.app.core.ClubBadge
import yacht.app.core.LeaderArea
import yacht.app.core.LeaderFilter
import yacht.app.core.LeagueAnalytics
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.PressPhase
import yacht.app.core.StatScope
import yacht.app.core.StoryPages
import yacht.app.core.drawnRatings
import yacht.app.core.leagueStyle
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Media
import yacht.engine.league.Meta
import yacht.engine.league.PressAwards
import yacht.engine.league.PressInput
import yacht.engine.league.PressVotes
import yacht.engine.league.StyleFamily
import yacht.engine.league.WinEstimate

/**
 * 기자단 and 시대와 메타's loaders (`lg.storyPages`, UX 7-8, 13.4) and the analytics' story parts (the
 * review's big stories, the chronicle's and the 결산 list's lines, the leaders hub's club boards)
 * on [PressStory]'s variant of the story world (the press and the meta written as their lines) and
 * on the plain story world (nothing of them yet): the phase's order, the power ranking as the desk
 * ranked it with its moves, the predictions as the open's own numbers, the press vote's rows as
 * the engine tallied them, the style shares and eras, and what links to the pages.
 */
class StoryPagesTest {
    private val main = LinkedBlockingQueue<Runnable>()

    @Test
    fun thePressOrdersItsSectionsByThePhase() {
        // The review weeks (the SeasonEnd banner's) put the last close's vote first, the rest of the
        // first five weeks the predictions, the season after them the power ranking.
        assertEquals(PressPhase.REVIEW, StoryPages.phaseOf(1, fresh = true))
        assertEquals(PressPhase.REVIEW, StoryPages.phaseOf(StoryPages.REVIEW_WEEKS, fresh = true))
        assertEquals(PressPhase.PRESEASON, StoryPages.phaseOf(StoryPages.REVIEW_WEEKS + 1, fresh = true))
        assertEquals(PressPhase.PRESEASON, StoryPages.phaseOf(1, fresh = false))
        assertEquals(PressPhase.PRESEASON, StoryPages.phaseOf(StoryPages.PRESEASON_WEEKS, fresh = true))
        assertEquals(PressPhase.SEASON, StoryPages.phaseOf(StoryPages.PRESEASON_WEEKS + 1, fresh = true))
        val v = press()
        assertTrue(v.national)
        assertEquals(PressPhase.SEASON, v.phase)
        assertEquals(scene.world.plan!!.week, v.week)
        assertEquals(scene.last, v.voteSeason)
    }

    @Test
    fun thePowerRankingIsTheDesksInItsOrderWithItsMoves() {
        val w = scene.world
        val desk = w.desk
        val v = press()
        assertEquals(desk.powerWeek, v.powerWeek)
        assertEquals(v.season, v.powerSeason)
        assertEquals(desk.power.take(Media.WORLD_TOP), v.power.map { it.key }, "the world's top 10 is the desk's, in its order")
        assertEquals((1..v.power.size).toList(), v.power.map { it.rank })
        for (r in v.power) {
            val before = desk.powerPrev.indexOf(r.key)
            assertEquals(if (before < 0) null else before + 1 - r.rank, r.move, "${r.key}'s move against last week")
            assertTrue(r.where.endsWith("1부") && r.sub.startsWith("기대보다 승점 ") && r.score != null, r.toString())
        }
        assertFalse(v.firstRanking)
        assertEquals(v.column?.keys, desk.power, "the column names this week's top 10")
        // A league's top 5: its own ranking of the same week.
        val p = w.plan!!
        assertEquals(Media.power(p, desk, drawnRatings(w, p), scene.league, Media.LEAGUE_TOP).map { it.key }, v.leagueTop.map { it.key })
        // Weeks at no. 1: this season's, and in all with the closes' (`pr1`) added.
        assertEquals(desk.weeksTop.values.sortedDescending().take(StoryPages.TOP_WEEKS), v.weeksTop.map { it.second })
        val career = HashMap(w.world.media.careerTop).also { m -> desk.weeksTop.forEach { (k, n) -> m.merge(k, n, Int::plus) } }
        assertEquals(career.values.sortedDescending().take(StoryPages.TOP_WEEKS), v.careerTop.map { it.second })
        assertTrue((v.weeksTop + v.careerTop).all { (k, _) -> v.names.containsKey(k) })
    }

    @Test
    fun theLeaguesPredictionIsTheOpensOwnNumbers() {
        val w = scene.world
        val p = w.plan!!
        val v = press()
        val pr = assertNotNull(v.prediction)
        val expected = assertNotNull(Media.prediction(p, drawnRatings(w, p), scene.league))
        assertEquals(scene.league, pr.league)
        assertEquals(expected.favourites.map { it.key }, pr.favourites.map { it.key })
        assertEquals(expected.favourites.map { (it.title * 100).roundToInt() }, pr.favourites.map { it.pct })
        assertEquals(expected.dark?.key, pr.dark?.key)
        assertEquals(expected.dark?.let { (it.top4 * 100).roundToInt() }, pr.dark?.pct)
        assertEquals(expected.relegation.map { it.key }, pr.relegation.map { it.key })
        assertEquals(expected.order.map { it.key }, pr.order.map { it.key })
        assertEquals((1..pr.order.size).toList(), pr.order.map { it.predicted })
        // The places now, as the 1부's table has them.
        val table = p.league.table(p.league.leagues.byId(scene.league)!!.top).map { it.key }
        for (pick in pr.order) assertEquals(table.indexOf(pick.key) + 1, pick.now, pick.key)
        // Another league's: the page's own (the tabs' league is left as it is).
        val other = Media.leagues(p, drawnRatings(w, p)).first { it != scene.league }
        val pages = StoryPages(Host(w))
        pages.loadPress(other)
        assertEquals(other, wait { pages.press }.prediction?.league)
    }

    @Test
    fun theVoteRowsAreTheEnginesTallies() {
        // The engine's own tallies of the season (points 5-3-1 a ballot) written as stats.txt keeps them.
        val w = ClubStory.decode(scene.files)
        val p = w.plan!!
        val tallies = PressVotes.vote(PressInput(p, w.honours.all, { w.ladder.rating(it) }, drawnRatings(w, p), nation = { w.players[it]?.identity?.nation }, clubs = w.clubs))
            .map { it.copy(season = scene.last) }
        assertTrue(tallies.map { it.award }.containsAll(listOf(PressAwards.GOLDEN_DICE, PressAwards.COACH)), tallies.map { it.award }.toString())
        // Each ballot gives 5, 3 and 1 point (fewer with fewer candidates: a small world's 올해의 영입).
        for (t in tallies) assertEquals(listOf(5, 3, 1).take(t.rows.size).sum() * t.jury, t.rows.sumOf { it.points }, "${t.award}: 9 points a juror")
        val files = StoryWorld.variant(scene.files) {
            val kept = this[LeagueWorld.STATS]!!.lines().filter { !it.startsWith("pv ") }.joinToString("\n")
            this[LeagueWorld.STATS] = kept + "\n" + tallies.joinToString("") { it.encode() }
        }
        val v = press(ClubStory.decode(files))
        assertEquals(tallies.first().jury, v.jury)
        assertEquals(PressAwards.AWARDS.map { it.first }.filter { a -> tallies.any { it.award == a } }, v.votes.map { it.award }, "the awards in their order")
        for (a in v.votes) {
            val t = tallies.first { it.award == a.award }
            val n = if (a.award == PressAwards.GOLDEN_DICE) StoryPages.VOTE_ROWS else StoryPages.AWARD_ROWS
            assertEquals(t.rows.take(n).map { Triple(it.key, it.points, it.firsts) }, a.rows.map { Triple(it.link.substringAfter(':'), it.points, it.firsts) })
            val prefix = if (a.award == PressAwards.COACH) "k:" else "p:"
            assertTrue(a.rows.all { it.link.startsWith(prefix) }, a.rows.toString())
        }
        // Every 골든 다이스 the honours keep, newest first.
        val gd = w.honours.all.filter { it.kind == PressAwards.GOLDEN_DICE }.sortedByDescending { it.season }.map { it.season }
        assertEquals(gd, v.goldenDice.map { it.first })
    }

    @Test
    fun theScorecardIsTheLastCloses() {
        val v = press()
        val sc = assertNotNull(v.scorecard)
        val kept = assertNotNull(scene.world.world.media.scorecard(scene.last))
        assertEquals(scene.last, sc.season)
        assertEquals(listOf(kept.hits, kept.leagues, kept.top3, kept.relHits, kept.relPicks, kept.rho), listOf(sc.hits, sc.leagues, sc.top3, sc.relHits, sc.relPicks, sc.rho))
        assertEquals(kept.over?.key, sc.over?.key)
        assertEquals(kept.perLeague.map { it.league }, sc.perLeague.map { it.league })
        assertEquals((scene.last downTo 2).toList().take(StoryPages.HISTORY), v.history.map { it.first })
    }

    @Test
    fun theMetaReadsTheErasTheSharesAndTheMatchups() {
        val w = scene.world
        val m = wait(StoryPages(Host(w)).also { it.loadMeta() }::meta)
        assertTrue(m.any)
        // Shares: per mille, each season's adding up to 1000 within rounding; now the newest's, biggest first.
        for (s in m.shares) for (xs in listOf(s.elite, s.all)) assertTrue(kotlin.math.abs(xs.sum() - 1000) <= 8, "season ${s.season}: ${xs.sum()}")
        assertEquals(m.shares.map { it.season }, m.shares.map { it.season }.sorted())
        assertEquals(m.now.map { it.elite }, m.now.map { it.elite }.sortedDescending())
        assertEquals(StyleFamily.entries.size, m.now.size)
        // Eras newest first, one after another without overlapping; the one going on is the era now.
        assertEquals(m.eras.map { it.n }, m.eras.map { it.n }.sortedDescending())
        for ((newer, older) in m.eras.zipWithNext()) assertTrue(older.to != null && older.to!! < newer.from, "$older then $newer")
        val now = assertNotNull(m.era)
        assertNull(now.to)
        assertEquals("족보파의 시대", now.name)
        assertEquals(StyleFamily.COMBO, now.family)
        assertFalse(m.chaos)
        // k of T big titles over the last three closes measured.
        val score = Meta.scores(w.stats.families, m.measured).first { it.family == StyleFamily.COMBO }
        assertEquals(score.titles to score.total, m.titles)
        // The candidate names the era it would open.
        val c = assertNotNull(m.candidate)
        assertEquals(StyleFamily.YACHT to "요트파의 시대", c.family to c.era)
        // Matchups: points a match above expectation, none below the counter rule's matches.
        val grid = assertNotNull(m.matchups)
        val fs = w.stats.families(m.measured)!!
        for (x in fs.matchups) {
            val cell = grid.score[x.f.ordinal][x.g.ordinal]
            if (x.matches < StoryPages.MATCHUP_MIN) assertTrue(cell.isNaN()) else assertEquals(x.excess / x.matches, cell, 1e-9)
            assertEquals(x.matches, grid.games[x.f.ordinal][x.g.ordinal])
        }
        assertEquals("🛡 카운터: 족보파가 요트파를 상대로 기대보다 경기당 승점을 0.31점 더 땄어요.", m.counter)
        assertEquals(w.config.styleCap, m.styleCap)
        assertEquals(StyleFamily.entries, m.weights.map { it.first })
        // The best-ranked leagues first, each with its family at the last close.
        val p = w.plan!!
        assertEquals(Media.leagues(p, drawnRatings(w, p)).filter { it in fs.leagues }.take(StoryPages.TOP_LEAGUES), m.leagues.map { it.first })
        assertTrue(m.leagues.all { (id, f) -> fs.leagues[id] == f })
    }

    @Test
    fun whatLinksToThePagesFollowsTheirData() {
        // The plain story world (before P17 writes any of it): no press, no meta, no era; the meta page keeps its report.
        val plain = ClubStory.decode(StoryWorld.files())
        val none = wait(StoryPages(Host(plain)).also { it.loadStatus() }::status)
        assertFalse(none.press || none.meta)
        assertNull(none.era)
        assertFalse(wait(StoryPages(Host(plain)).also { it.loadMeta() }::meta).any)
        val all = wait(StoryPages(Host(scene.world)).also { it.loadStatus() }::status)
        assertTrue(all.press && all.meta)
        assertEquals("🧭 족보파의 시대 ${scene.world.plan!!.number - scene.last + 1}시즌째", all.era)
        // The league page's 🧭: the family measured at the last close, its share of the 1부 now.
        val keys = listOf("a", "b", "c", "d", "e")
        val families = mapOf("a" to StyleFamily.BONUS, "b" to StyleFamily.BONUS, "c" to StyleFamily.COMBO, "d" to null, "e" to StyleFamily.EV)
        assertEquals(StyleFamily.COMBO to 25, leagueStyle(keys, StyleFamily.COMBO, families::get))
        assertEquals(StyleFamily.BONUS to 50, leagueStyle(keys, null, families::get))
        assertNull(leagueStyle(listOf("d"), null, families::get))
    }

    @Test
    fun theReviewTheChronicleAndTheSeasonListTellTheStories() {
        val w = scene.world
        val an = LeagueAnalytics(Host(w))
        an.loadReview(scene.last)
        val s = assertNotNull(wait { an.review(Page.Review(scene.last)) }.stories)
        // The big stories in the close's order, 골든 다이스 left to its own block.
        assertEquals(listOf("meta", "wc:${scene.last + 1}", "p:"), s.big.map { (_, link) -> if (link.startsWith("p:")) "p:" else link })
        assertEquals("🧭 족보파의 시대가 열렸어요", s.big[0].first)
        assertTrue(s.big[1].first.startsWith("🗳 시즌 ${scene.last + 1} 요트 월드컵 개최지가 "), s.big[1].first)
        assertTrue(s.big[2].first.startsWith("💰 세계 최고 이적료 "), s.big[2].first)
        assertEquals(3, s.goldenDice.size)
        assertEquals(listOf(142, 96, 61), s.goldenDice.map { it.points })
        assertTrue(s.predict!!.startsWith("3/"), s.predict)
        assertEquals("족보파의 시대가 열렸어요", s.era)
        assertEquals(23 to 2, s.deals to s.sackings)
        assertNotNull(s.coachOfYear)
        // The chronicle: 골든 다이스 every season, the eras' starts, the world event, the record fee.
        an.loadChronicle()
        val ch = wait { an.chronicle() }
        fun lines(season: Int) = ch.seasons.first { it.season == season }.lines.map { "${it.icon} ${it.text}" }
        for (x in 1..scene.last) assertTrue(lines(x).any { it.startsWith("🎙 골든 다이스 ") }, "season $x")
        assertTrue("🧭 보너스파의 시대 시작" in lines(scene.last - 3))
        assertTrue("🧭 족보파의 시대 시작" in lines(scene.last))
        assertTrue("🆕 요트 붐 시작" in lines(scene.last - 2) && "🆕 요트 붐 결말" in lines(scene.last - 1))
        assertTrue(lines(scene.last).any { it.startsWith("💰 세계 최고 이적료 ") })
        // 기록 › 결산: the seasons inside an era carry its name.
        an.loadReviews()
        val list = wait { an.reviewList() }
        for (item in list.items) {
            val era = when (item.season) {
                in scene.last - 3..scene.last - 1 -> "🧭 보너스파의 시대"
                scene.last -> "🧭 족보파의 시대"
                else -> null
            }
            assertEquals(era, item.lines.firstOrNull { it.startsWith("🧭") }, "season ${item.season}")
        }
    }

    @Test
    fun theLeadersHubsClubBoardsNeedAClubWorld() {
        val w = scene.world
        val an = LeagueAnalytics(Host(w))
        val filter = LeaderFilter(scope = StatScope.SEASON, area = LeaderArea.ALL)
        an.loadClubBoards(filter)
        val b = wait { an.clubBoards(filter) }
        // (The scene's club world started this season: its academies may have no graduates playing yet.)
        assertTrue(b.value.isNotEmpty())
        assertEquals(b.value.map { it.value }, b.value.map { it.value }.sortedDescending())
        assertEquals(b.graduates.map { it.value }, b.graduates.map { it.value }.sortedDescending())
        val book = w.clubs!!
        assertTrue(b.graduates.all { book.terms(it.key)?.academy != null }, "academy graduates only")
        assertTrue(b.value.size <= LeagueAnalytics.CLUB_BOARD)
        // Over careers or the last matches, and without a club world: none.
        val career = filter.copy(scope = StatScope.CAREER)
        an.loadClubBoards(career)
        assertTrue(wait { an.clubBoards(career) }.value.isEmpty())
        val plain = LeagueAnalytics(Host(ClubStory.decode(StoryWorld.files())))
        plain.loadClubBoards(filter)
        val nothing = wait { plain.clubBoards(filter) }
        assertTrue(nothing.value.isEmpty() && nothing.graduates.isEmpty())
    }

    /** 기자단 of the variant's best-ranked league, loaded on [w] (the scene's by default). */
    private fun press(w: LeagueWorld = scene.world): yacht.app.core.PressView {
        val pages = StoryPages(Host(w))
        pages.loadPress(scene.league)
        return wait { pages.press }
    }

    /** [get] once it gives a value (the loaders read off the main thread; their results are handed over here). */
    private fun <T : Any> wait(get: () -> T?): T {
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            get()?.let { return it }
            main.poll(10, TimeUnit.MILLISECONDS)?.run()
        }
        error("nothing loaded in time")
    }

    /** A host of world [w] alone (no snapshot, names as keys), its main thread the test's ([main]). */
    private inner class Host(var w: LeagueWorld) : LeagueHost {
        override val scope = CoroutineScope(Executor { main.put(it) }.asCoroutineDispatcher())
        override val lock = Any()
        override val folder = File(".")
        override fun world(): LeagueWorld? = w
        override fun tables(): Tables? = null
        override fun extra(): LeagueTables? = null
        override fun estimate(): WinEstimate? = null
        override fun ai(key: String): LeagueAi? = null
        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? = null
        override fun adaptive(key: String) = false
        override fun name(key: String) = key
        override fun shown(key: String) = key
        override fun badge(key: String): ClubBadge? = null
        override fun leagueLabel(key: String): String? = null
        override fun favourites() = emptySet<String>()
        override fun view(): LeagueView? = null
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = w.members.size
    }

    companion object {
        /** The press-and-meta variant of the story world in the season (the shots' own), built once. */
        private val scene: PressStory.Scene by lazy { PressStory.of(ClubScene.default.files, ranked = true) }
    }
}

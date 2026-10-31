package yacht.engine

import yacht.engine.league.Article
import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Cup
import yacht.engine.league.GameScore
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.News
import yacht.engine.league.NewsDesk
import yacht.engine.league.Season
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.StatsBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The news diet (design_ux 5.2) at scale: a 3000-player 요트컵 week makes at most one digest and
 * six single ties a stage instead of a hundred articles; a week of 30 milestones makes one
 * digest and at most two singles; and a national season's flood of cup weeks never pushes the
 * close's major news (champions, awards, the review) out of the news book.
 */
class NewsDietTest {
    @Test
    fun aBigCupWeekAt3000PlayersMakesOneDigestAndAtMostSixSinglesAStage() {
        val keys = (0 until 3000).map { "g%04d".format(it) }
        val config = LeagueConfig(generated = 0, clubs = 0, familyCup = false)
        val league = keys.take(24)
        val p = SeasonPlan(Season.start(1, 31L, league.chunked(8)), config)
        val rating = { k: String -> 2600.0 - 0.3 * k.drop(1).toInt() }
        p.comps += Cup.create(SeasonStart(1, 31L, config, keys, rating, league.chunked(8), emptyList(), emptyList(), { null }, { "" }))!!
        val cup = p.competition(Comp.CUP)!!
        val desk = NewsDesk()
        desk.begin(p)
        val raw = HashMap<Int, Int>()
        val written = HashMap<Int, MutableList<LeagueEvent>>()
        var guard = 0
        while (!cup.finished) {
            val f = cup.next(1.0, emptySet()) ?: break
            val s = cup.stageOf(f)
            // The lower seed takes a third of the ties.
            val rng = java.util.Random(f.seed)
            val under = rng.nextDouble() < 0.3
            val g = List(2 * f.legs) { if (under) GameScore(190, 230) else GameScore(230, 190) }
            val e = p.record(JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), if (under) -1 else 1, 0))
            raw.merge(s, e.size, Int::plus)
            written.getOrPut(s) { ArrayList() } += desk.sift(e, p, f)
            check(++guard < 10_000)
        }
        assertTrue(cup.finished)
        val counts = cup.stageCounts()
        val name = { k: String -> k }
        var flooded = 0
        for ((s, events) in written) {
            val articles = News.articles(events, name)
            val ties = counts[s].total
            if (ties > NewsDesk.SMALL_STAGE) {
                assertEquals(1, articles.count { it.kind == "cup_digest" }, "stage $s: ${articles.map { it.kind }}")
                assertTrue(articles.count { it.kind != "cup_digest" } <= NewsDesk.SINGLES, "stage $s ($ties ties): ${articles.map { it.headline }}")
                if ((raw[s] ?: 0) > 50) flooded++
            } else {
                assertEquals(0, articles.count { it.kind == "cup_digest" }, "stage $s")
            }
        }
        // Today's cup weeks: a hundred and more events in a first round (giant killings and their upsets).
        assertTrue(flooded >= 1, raw.toString())
        assertEquals(0, desk.held)
    }

    @Test
    fun thirtyMilestonesInAWeekMakeOneDigestAndAtMostTwoSingles() {
        val keys = (0 until 24).map { "p%02d".format(it) }
        val p = SeasonPlan(Season.start(1, 5L, keys.chunked(8)), LeagueConfig(generated = 0, clubs = 0, familyCup = false))
        val desk = NewsDesk()
        desk.begin(p)
        fun m(i: Int, kind: String, value: Int) = LeagueEvent("milestone", 1, listOf("k$i"), listOf(kind, value.toString()), null, 1)
        val week = (0 until 10).map { m(it, "wins", 100) } + (10 until 18).map { m(it, "yachts", 50) } + (18 until 25).map { m(it, "games", 1000) } +
            listOf(m(25, "wins", 1000), m(26, "games", 10_000), m(27, "yachts", 1000), m(28, "300", 1), m(29, "300", 1))
        assertEquals(30, week.size)
        assertTrue(desk.sift(week, p).isEmpty())
        val closed = desk.close(NewsDesk.Context(p, StatsBook()), 2)
        val articles = News.articles(closed, { it })
        assertEquals(1, articles.count { it.kind == "milestones_week" })
        assertTrue(articles.count { it.kind == "milestone" } <= NewsDesk.MILESTONE_SINGLES, articles.map { it.headline }.toString())
        assertEquals(3, articles.size)
        // The digest counts every milestone it holds.
        val digest = closed.single { it.kind == "milestones_week" }
        assertEquals(28, digest.args.sumOf { it.substringAfterLast(':').toInt() })
        assertTrue("이정표 28개" in articles.single { it.kind == "milestones_week" }.body)
    }

    @Test
    fun eightFloodWeeksKeepEveryMajorArticleOfTheClose() {
        val config = LeagueConfig.DEFAULT.copy(generated = 1000)
        val run = WorldRun(config)
        run.w.openFirstSeason(5L)
        run.play()
        run.beforeClose()
        run.rollover(config)
        // The close's and the open's major news: champions, awards, records, the review, the 요트협회.
        val close = run.w.news.all.filter { it.major && it.season == 1 && it.week == SeasonCalendar.CLOSING }
        assertTrue(close.size >= 20, close.size.toString())
        val seen = run.w.news.all.toHashSet()
        var raw = 0
        val weeks = HashMap<Int, MutableList<Article>>()
        for (w in 1..8) {
            raw += run.play { it.plan!!.week > w }.size
            for (a in run.w.news.all) if (seen.add(a)) weeks.getOrPut(a.week) { ArrayList() } += a
        }
        // Flood weeks: cup rounds, CL and CCL groups, milestones by the dozen.
        assertTrue(raw >= 150, "$raw events")
        val kept = run.w.news.all.toSet()
        val lost = close.filter { it !in kept }
        assertTrue(lost.isEmpty(), "${lost.size} of ${close.size} lost: ${lost.take(3).map { it.headline }}")
        // Each week stays within the desk's caps.
        for ((w, list) in weeks) {
            if (w == SeasonCalendar.PRESEASON) continue
            val kinds = list.groupingBy { it.kind }.eachCount()
            assertTrue(list.size <= WEEK_MAX, "week $w: ${list.size} articles $kinds")
            assertTrue((kinds["league_upset"] ?: 0) <= NewsDesk.UPSETS && (kinds["comeback"] ?: 0) <= NewsDesk.COMEBACKS, "week $w: $kinds")
            for (k in listOf("club_race", "milestones_week", "record_chase")) assertTrue((kinds[k] ?: 0) <= 1, "week $w: $kinds")
            assertTrue(list.filter { it.kind == "intl_week" }.groupingBy { it.comp }.eachCount().values.all { it <= 1 }, "week $w: $kinds")
        }
    }

    private companion object {
        /** Articles a week at 1000 players (design: about 10-15 at 3000; competition stories make the rest). */
        const val WEEK_MAX = 20
    }
}

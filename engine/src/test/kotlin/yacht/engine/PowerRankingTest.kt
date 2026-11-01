package yacht.engine

import yacht.engine.league.Article
import yacht.engine.league.Expectation
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.Leagues
import yacht.engine.league.Media
import yacht.engine.league.NewsBook
import yacht.engine.league.NewsDesk
import yacht.engine.league.Odds
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StatsBook
import yacht.engine.league.TieRule
import yacht.engine.league.WorldBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The weekly power ranking (파워 랭킹, design ENG 5.1): the formula on a hand-made national season,
 * who is ranked (1부 members with 4 league matches or more), results through the closed week only,
 * the desk's weeks (one by one or all at once alike), last week's places and newcomers, the weeks
 * at #1 and their fold into the career count, and the column rule of the news book.
 */
class PowerRankingTest {
    /** KR in two tiers of 6, then eight one-division leagues of 4, each weaker than the one before (BR the 9th, outside the best 8). */
    private val ids = listOf("KR", "JP", "CN", "US", "GB", "FR", "DE", "ES", "BR")
    private fun members(id: String, tier: Int, n: Int) = List(n) { "${id.lowercase()}$tier-$it" }
    private val divisions = listOf(members("KR", 0, 6), members("KR", 1, 6)) + ids.drop(1).map { members(it, 0, 4) }
    private val leagues = Leagues.national(ids, listOf(2) + List(ids.size - 1) { 1 })

    /** The ratings the season was drawn with: by league, tier and place. */
    private fun drawn(k: String): Double {
        val l = ids.indexOf(k.take(2).uppercase())
        val t = k[2].digitToInt()
        val i = k.substringAfter('-').toInt()
        return 1700.0 - 25 * l - 150 * t - 10 * i
    }

    private fun plan(): SeasonPlan = SeasonPlan(Season.start(1, 21L, divisions, true, leagues), LeagueConfig(national = true, doubleRoundRobin = true))

    private fun ctx(p: SeasonPlan) = NewsDesk.Context(p, StatsBook(), null, drawn = ::drawn)

    /** The 1부s' division indices. */
    private val tops = leagues.tops().toSet()

    @Test
    fun theScoreIsTheDrawnRatingPlusPointsAndFormAboveExpectationAndTheStage() {
        val p = plan()
        val s = p.league
        Hist.play(s, last = 5)
        val week = 6
        val rows = Media.ranking(p, ::drawn, week)
        assertTrue(rows.size > 10, "${rows.size} ranked")
        val cut = s.pointsThrough(week - 1)
        val bonus = if (s.bonus) 1 else 0
        val sweep = if (s.bonus) Odds.SWEEP_PRIOR else 0.0
        for (r in rows) {
            val d = s.divisionOf(r.key)!!
            assertTrue(d in tops, "${r.key} is no 1부 member")
            // By hand: its played fixtures through matchday 5, in order.
            val mine = (0 until s.fixturesTotal).map { s.fixture(it) }.filter { (it.a == r.key || it.b == r.key) && it.matchday <= week - 1 && s.result(it.id) != null }
                .sortedWith(compareBy({ it.matchday }, { it.id }))
            val pts = mine.map { f ->
                val g = s.result(f.id)!!
                val m = TieRule.match(g, s.format) * (if (f.a == r.key) 1 else -1)
                val swept = if (f.a == r.key) TieRule.sweptByA(g) else TieRule.sweptByB(g)
                if (m > 0) 3 + (if (s.bonus && swept) 1 else 0) else if (m == 0) 1 else 0
            }
            val exp = mine.map { f -> val o = if (f.a == r.key) f.b else f.a; Expectation.points(drawn(r.key), drawn(o), Odds.DRAW_PRIOR, sweep, bonus) }
            assertEquals(cut.getValue(r.key)[Season.THROUGH_POINTS], pts.sum())
            assertEquals(pts.sum(), r.points)
            assertEquals(mine.size, r.played)
            assertTrue(r.played >= Media.MIN_PLAYED)
            assertEquals(exp.sum(), r.expected, 1e-9)
            assertEquals(pts.takeLast(5).average(), r.form5, 1e-9)
            // "최근 5경기 {n}승": the wins of the same 5.
            assertEquals(pts.takeLast(5).count { it >= 3 }, r.wins5)
            assertEquals(exp.takeLast(5).average(), r.expected5, 1e-9)
            val stage = if (r.key.startsWith("br")) Media.STAGE else Media.TOP_STAGE
            assertEquals(stage, r.stage, r.key)
            assertEquals(drawn(r.key) + 6 * (r.points - r.expected) + 12 * (r.form5 - r.expected5) + stage, r.score, 1e-9)
            assertEquals(drawn(r.key), r.drawn)
            assertEquals(null, r.prev)
        }
        // Best first, ranks from 1; every 1부 member with 4 matches through the week is in, nobody else.
        assertEquals(rows.sortedByDescending { it.score }.map { it.key }, rows.map { it.key })
        assertEquals((1..rows.size).toList(), rows.map { it.rank })
        val eligible = tops.flatMap { s.divisions[it] }.filter { (cut.getValue(it)[Season.THROUGH_PLAYED]) >= Media.MIN_PLAYED }.toSet()
        assertEquals(eligible, rows.map { it.key }.toSet())
        assertTrue(rows.any { it.key.startsWith("br") }, "the 9th league is ranked too")
        // Earlier, the shorter divisions' members have fewer than 4 matches and are not ranked yet.
        val early = plan()
        Hist.play(early.league, last = 3)
        val cut4 = early.league.pointsThrough(3)
        val few = tops.flatMap { early.league.divisions[it] }.filter { cut4.getValue(it)[Season.THROUGH_PLAYED] < Media.MIN_PLAYED }
        assertTrue(few.isNotEmpty(), "some 1부 members have fewer than 4 matches")
        assertTrue(Media.ranking(early, ::drawn, 4).isNotEmpty())
        assertTrue(Media.ranking(early, ::drawn, 4).none { it.key in few })
        // A league's own list: the same rows of its members.
        val kr = Media.ranking(p, ::drawn, week, "KR")
        assertEquals(rows.filter { it.key.startsWith("kr") }.map { it.key }, kr.map { it.key })
        assertEquals((1..kr.size).toList(), kr.map { it.rank })
    }

    @Test
    fun onlyResultsThroughTheClosedWeekCount() {
        val p = plan()
        Hist.play(p.league, last = 5)
        val before = Media.ranking(p, ::drawn, 6)
        // Week 7's fixtures played after week 6 closed (or early) never change week 6's ranking.
        Hist.play(p.league, last = 6)
        assertEquals(before, Media.ranking(p, ::drawn, 6))
        assertNotEquals(before, Media.ranking(p, ::drawn, 7))
    }

    @Test
    fun aMemberDeletedFromTheRosterIsLeftOut() {
        val p = plan()
        Hist.play(p.league, last = 7)
        val all = Media.ranking(p, ::drawn, 8)
        val out = all.first().key
        val without = Media.ranking(p, ::drawn, 8, gone = { it == out })
        // The others' numbers as they were (its results still count for its opponents), ranked from 1.
        assertEquals(all.drop(1).map { it.copy(rank = it.rank - 1) }, without)
        // The desk's list and the screens' leave it out too.
        val desk = NewsDesk(media = true).also { it.begin(p) }
        desk.close(NewsDesk.Context(p, StatsBook(), null, drawn = ::drawn, gone = { it == out }), 9)
        assertTrue(out !in desk.power && out !in desk.weeksTop)
        assertTrue(Media.power(p, desk, ::drawn, gone = { it == out }).none { it.key == out })
    }

    @Test
    fun theFirstRankedWeekIsWhenAMemberCanHaveFourMatches() {
        val p = plan()
        Hist.play(p.league)
        val first = Media.firstWeek(p)
        assertEquals(4, first)
        assertTrue(Media.ranking(p, ::drawn, first - 1).isEmpty())
        assertTrue(Media.ranking(p, ::drawn, first).isNotEmpty())
        // A legacy season has no power ranking.
        val legacy = SeasonPlan(Season.start(1, 3L, listOf(members("KR", 0, 6))), LeagueConfig())
        Hist.play(legacy.league)
        assertEquals(0, Media.firstWeek(legacy))
        assertTrue(Media.ranking(legacy, ::drawn, 8).isEmpty())
    }

    @Test
    fun theDeskRanksEachClosedWeekOnceWhetherTheyCloseOneByOneOrTogether() {
        val p1 = plan()
        val one = NewsDesk(media = true).also { it.begin(p1) }
        assertEquals(1, one.week)
        val days = p1.league.matchdays
        val columns = ArrayList<LeagueEvent>()
        for (md in 0 until days) {
            Hist.play(p1.league, last = md)
            columns += one.close(ctx(p1), md + 2).filter { it.kind == "power_ranking" }
            // Asked again for the same week: nothing more.
            assertTrue(one.close(ctx(p1), md + 2).isEmpty())
        }
        val p2 = plan()
        val all = NewsDesk(media = true).also { it.begin(p2) }
        Hist.play(p2.league)
        val once = all.close(ctx(p2), days + 1).filter { it.kind == "power_ranking" }
        assertEquals(one.encode(), all.encode())
        // One column a ranked week one by one, the last week's only when they close together.
        val ranked = (1..days).filter { Media.ranking(p1, ::drawn, it).isNotEmpty() }
        assertEquals(ranked, columns.map { it.args[0].toInt() })
        assertEquals(listOf(days.toString()), once.map { it.args[0] })
        assertEquals(columns.last(), once.single())
        for (c in columns) assertEquals(Media.ranking(p1, ::drawn, c.week).take(10).map { it.key }, c.keys)
        // The desk keeps this and last week's top 10 and this season's weeks at #1.
        assertEquals(days, one.powerWeek)
        assertEquals(days - 1, one.powerPrevWeek)
        assertEquals(Media.ranking(p1, ::drawn, days).take(10).map { it.key }, one.power)
        assertEquals(Media.ranking(p1, ::drawn, days - 1).take(10).map { it.key }, one.powerPrev)
        assertEquals(ranked.size, one.weeksTop.values.sum())
        val tops = ranked.map { Media.ranking(p1, ::drawn, it).first().key }.groupingBy { it }.eachCount()
        assertEquals(tops, one.weeksTop)
        // Read back after a kill: the same desk.
        assertEquals(one.encode(), NewsDesk.decode(one.encode(), media = true).encode())
        assertTrue(one.encode().lines().count { it.startsWith("pw ") } == 2 && one.encode().contains("\np1 "))
        // The season's end ranks nothing more (every league week closed).
        assertTrue(one.flush(ctx(p1)).none { it.kind == "power_ranking" })
    }

    @Test
    fun theSeasonsEndRanksTheWeeksStillOpen() {
        val p = plan()
        val desk = NewsDesk(media = true).also { it.begin(p) }
        Hist.play(p.league)
        val e = desk.flush(ctx(p))
        assertEquals(p.league.matchdays, desk.powerWeek)
        assertEquals(listOf(p.league.matchdays.toString()), e.filter { it.kind == "power_ranking" }.map { it.args[0] })
    }

    @Test
    fun theColumnTellsTheClimberTheNewcomerAndANewTop() {
        val before = "abcdefghij".map { it.toString() }
        val now = listOf("b", "a", "h", "c", "d", "e", "f", "g", "x", "i")
        val e = Media.powerEvent(4, 12, now, before)
        assertEquals("power_ranking", e.kind)
        assertEquals(now, e.keys)
        assertEquals(12, e.week)
        // h from 8th to 3rd; x is new; b took a's place at the top.
        assertEquals(listOf("12", "h", "5", "x", "1"), e.args)
        // The first week: nobody climbs, nobody is new, the top did not change.
        assertEquals(listOf("5", "-", "-", "-", "0"), Media.powerEvent(4, 5, now, emptyList()).args)
        // Rises of fewer than 3 places are no news.
        assertEquals(listOf("6", "-", "-", "-", "0"), Media.powerEvent(4, 6, listOf("a", "c", "b"), listOf("a", "b", "c")).args)
    }

    @Test
    fun theScreensListShowsLastWeeksPlacesAndWeeksAtTheTop() {
        val p = plan()
        val desk = NewsDesk(media = true).also { it.begin(p) }
        assertTrue(Media.power(p, desk, ::drawn).isEmpty(), "before the first ranked week")
        for (md in 0 until 8) {
            Hist.play(p.league, last = md)
            desk.close(ctx(p), md + 2)
        }
        val world = Media.power(p, desk, ::drawn)
        assertEquals(desk.power, world.map { it.key })
        assertEquals((1..10).toList(), world.map { it.rank })
        for (r in world) {
            assertEquals(desk.powerPrev.indexOf(r.key).takeIf { it >= 0 }?.plus(1), r.prev)
            assertEquals(desk.weeksTop[r.key] ?: 0, r.weeksTop)
        }
        assertEquals(desk.weeksTop.values.sum(), 8 - Media.firstWeek(p) + 1)
        val kr = Media.power(p, desk, ::drawn, "KR")
        assertEquals(Media.LEAGUE_TOP, kr.size)
        assertTrue(kr.all { it.key.startsWith("kr0") })
        val last = Media.ranking(p, ::drawn, desk.powerPrevWeek, "KR").map { it.key }
        for (r in kr) assertEquals(last.indexOf(r.key).takeIf { it >= 0 }?.plus(1), r.prev)
        // Another season's desk shows nothing.
        val next = SeasonPlan(Season.start(2, 21L, divisions, true, leagues), LeagueConfig(national = true, doubleRoundRobin = true))
        assertTrue(Media.power(next, desk, ::drawn).isEmpty())
    }

    @Test
    fun theSeasonsWeeksAtTheTopFoldIntoTheCareerCount() {
        val p = plan()
        val desk = NewsDesk(media = true).also { it.begin(p) }
        Hist.play(p.league)
        desk.close(ctx(p), p.league.matchdays + 1)
        val w = WorldBook()
        w.media.foldTop(desk, 2)
        assertTrue(w.media.careerTop.isEmpty(), "another season's desk adds nothing")
        w.media.foldTop(desk, 1)
        assertEquals(desk.weeksTop, w.media.careerTop)
        val back = WorldBook.decode(w.encode())
        assertEquals(w.media.careerTop, back.media.careerTop)
        assertTrue(w.encode().contains("\npr1 "))
    }

    private fun article(kind: String, week: Int, importance: Int = 2) =
        Article(1, kind, "$kind $week", "본문 $week", listOf("k$week"), null, importance, week)

    @Test
    fun theNewsBookKeepsOnlyTheNewestArticleOfAColumn() {
        val book = NewsBook()
        book.add(article("power_ranking", 5))
        book.add(article("title", 5, 4))
        book.add(article("power_ranking", 6, 3))
        book.add(article("upset", 6))
        book.add(article("power_ranking", 7))
        assertEquals(listOf("power_ranking 7", "upset 6", "title 5"), book.all.map { it.headline })
        // Read back, a file of an older build with every column article keeps the newest only.
        fun line(a: Article) = NewsBook().also { it.add(a) }.encode().lines()[1]
        val old = buildString {
            append(NewsBook.HEADER).append('\n')
            for (w in 1..4) append(line(article("power_ranking", w))).append('\n')
            append(line(article("title", 9, 4))).append('\n')
        }
        val read = NewsBook.decode(old)
        assertEquals(listOf("title", "power_ranking"), read.all.map { it.kind })
        assertEquals(4, read.all.last().week)
        // The lanes still count right: a column article of the major lane leaves room for others.
        val big = NewsBook()
        repeat(NewsBook.MAJOR + 5) { big.add(article("power_ranking", it, 3)) }
        assertEquals(1, big.size)
        repeat(NewsBook.MAJOR) { big.add(article("title", it, 4)) }
        assertEquals(NewsBook.MAJOR, big.size)
        assertTrue(big.all.none { it.kind == "power_ranking" }, "the major lane dropped its oldest")
    }
}

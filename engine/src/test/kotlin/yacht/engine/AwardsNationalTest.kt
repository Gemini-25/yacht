package yacht.engine

import yacht.engine.league.AiStats
import yacht.engine.league.Archive
import yacht.engine.league.Awards
import yacht.engine.league.Dynasty
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.HonourBook
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Leagues
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StatsBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The awards of a national season (ENG 1.10): each league's MVP by its players' own fixtures, the
 * season's MVP among them by ladder rating, major titles for the hall of fame, doubles, trebles
 * and unbeaten seasons per league, and what the season summary keeps.
 */
class AwardsNationalTest {
    private val config = LeagueConfig(national = true, doubleRoundRobin = true)

    /** A match the better player (the lower number) wins 2-0. */
    private fun games(a: String, b: String): List<GameScore> =
        if (a.drop(1).toInt() < b.drop(1).toInt()) listOf(GameScore(220, 180), GameScore(210, 190)) else listOf(GameScore(180, 220), GameScore(190, 210))

    /** Season stats with average match rating [r] (NaN: none). */
    private fun rated(r: Double): AiStats = AiStats().apply {
        if (!r.isNaN()) {
            ratingSum = r * 10
            ratingCount = 10
        }
    }

    /** Plays [s] in week order; stops (leaving the rest unplayed) before the first fixture [stop] says no to. */
    private fun play(s: Season, stop: (yacht.engine.league.LeagueFixture) -> Boolean = { false }) {
        while (true) {
            val f = s.next(emptySet()) ?: break
            if (stop(f)) break
            s.record(f.id, games(f.a, f.b))
        }
    }

    @Test
    fun aLeagueMvpNeedsItsShareOfItsOwnFixtures() {
        // KR's 1부 of 5 plays 10 rounds, each player 8 of them (a bye a round robin); JP's 1부 of 6, 10.
        val kr = (1..5).map { "k$it" }
        val jp = (1..6).map { "j$it" }
        val s = Season.start(1, 9L, listOf(kr, jp), true, Leagues.national(listOf("KR", "JP"), listOf(1, 1)))
        assertEquals(10, s.roundsOf(0))
        assertTrue(kr.all { s.fixturesOf(it) == 8 } && jp.all { s.fixturesOf(it) == 10 })
        // Six of KR's rounds: one player has sat out twice (4 played), the others once (5).
        play(s) { it.division == 0 && it.round >= 6 }
        val played = kr.associateWith { s.standings.getValue(it).played }
        val four = kr.single { played[it] == 4 }
        val five = kr.filter { played[it] == 5 }
        assertEquals(4, five.size)
        val plan = SeasonPlan(s, config)
        // The best match rating has played 4 of 8 (under 60%); 5 of 8 is enough although it is under 60% of the 10 rounds.
        val ratings = HashMap<String, Double>()
        ratings[four] = 9.0
        ratings[five[1]] = 8.0
        for (k in five - five[1]) ratings[k] = 6.0
        for ((i, k) in jp.withIndex()) ratings[k] = 7.0 - i * 0.1
        val stats = { k: String -> rated(ratings[k] ?: Double.NaN) }
        val mvps = Awards.leagueMvps(plan, stats)
        assertEquals(mapOf("KR" to five[1], "JP" to "j1"), mvps)
        // The MVP: the league MVP rated highest on the ladder, whatever their match ratings.
        assertEquals("j1", Awards.mvp(plan, { k -> if (k == "j1") 1800.0 else 1700.0 }, stats))
        assertEquals(five[1], Awards.mvp(plan, { k -> if (k == five[1]) 1800.0 else 1700.0 }, stats))
        // Without ratings, the better match rating.
        assertEquals(five[1], Awards.mvp(plan, stats))
        // A legacy season has no league MVPs.
        assertTrue(Awards.leagueMvps(SeasonPlan(Season.start(1, 9L, listOf(kr)), LeagueConfig()), stats).isEmpty())
    }

    /** KR of two tiers (1부 k1-k6, 2부 m1-m6) and JP (j1-j6), played out: the lower number always wins. */
    private fun playedPlan(number: Int): SeasonPlan {
        val s = Season.start(
            number, 5L, listOf((1..6).map { "k$it" }, (1..6).map { "m$it" }, (1..6).map { "j$it" }), true,
            Leagues.national(listOf("KR", "JP"), listOf(2, 1)),
        )
        play(s)
        assertTrue(s.finished)
        return SeasonPlan(s, config)
    }

    @Test
    fun theHallOfFameCountsMajorTitlesOnly() {
        // k1: KR's title three times and the cup; j1: JP's title three times, JP not one of the best-ranked.
        val earlier = listOf(Honour(1, "L0.KR", "k1"), Honour(1, "L0.JP", "j1"), Honour(2, "L0.JP", "j1"), Honour(2, "CUP", "k1"), Honour(2, "L1.KR", "m1"), Honour(1, "L1.KR", "m1"))
        val now = listOf(Honour(3, "L0.KR", "k1"), Honour(3, "L0.JP", "j1"), Honour(3, "L1.KR", "m1"), Honour(3, "REG.KR", "k1"))
        fun close(major: (Int, String) -> Boolean): List<Pair<String, Int>> {
            val book = StatsBook()
            val honours = HonourBook().apply { add(earlier) }
            return book.endSeason(playedPlan(3), now, honours, Archive(), { 1500.0 }, major = major).inducted.map { it.key to it.titles }
        }
        // m1's three 2부 titles are no major titles at all.
        assertEquals(listOf("k1" to 3), close { _, league -> league == "KR" })
        assertEquals(listOf("j1" to 3, "k1" to 3), close { _, _ -> true })
        // The major count itself.
        val all = HonourBook().apply { add(earlier + now) }
        assertEquals(3, all.majorTitles("k1"))
        assertEquals(0, all.majorTitles("j1") { _, league -> league != "JP" })
        assertEquals(2, all.majorTitles("j1") { season, _ -> season >= 2 })
        assertEquals(0, all.majorTitles("m1"))
        // Every title still counts for the records and the screens.
        assertEquals(3, all.titles("m1"))
    }

    @Test
    fun majorTitlesAreTheCupsAndTheBestLeaguesTitles() {
        val h = listOf(
            "L0", "L0.KR", "L0.JP", "L1.KR", "L3", "CUP", "CUP2", "CL", "CL2", "CCL.ASIA", "CCL2.ASIA", "SUPER", "WCW", "WC",
            "CLUBCUPW", "REG.KR", "L0R.KR", "LMVP.KR", "MVP", "POTW",
        ).mapIndexed { i, kind -> Honour(i + 1, kind, "a") }
        val book = HonourBook().apply { add(h) }
        // L0, L0.KR, L0.JP, CUP, CL, CCL.ASIA, SUPER, WCW.
        assertEquals(8, book.majorTitles("a"))
        assertEquals(7, book.majorTitles("a") { _, league -> league == "KR" })
        assertEquals(listOf("L0", "L0.KR", "L0.JP", "CUP", "CL", "CCL.ASIA", "SUPER", "WCW"), h.filter { HonourBook.isMajor(it) }.map { it.kind })
        // Titles: every division of every league, cups and the team titles.
        assertEquals(
            listOf("L0", "L0.KR", "L0.JP", "L1.KR", "L3", "CUP", "CL", "CCL.ASIA", "SUPER", "WCW", "WC", "CLUBCUPW"),
            h.filter { HonourBook.isTitle(it.kind) }.map { it.kind },
        )
        assertEquals(12, book.titles("a"))
        assertTrue(Awards.isAiTitle("CCL.ASIA") && Awards.isAiTitle("L2.JP") && !Awards.isAiTitle("CCL2.ASIA") && !Awards.isAiTitle("LMVP.KR"))
        assertTrue(Awards.isTitle("CLUBCUP") && !Awards.isTitle("CLUBCUP2"))
        assertTrue(Awards.isAward("LMVP.KR") && Awards.isAward("MVP") && !Awards.isAward("SCORER"))
    }

    @Test
    fun theSummaryKeepsEachLeaguesTitleAndTheCups() {
        val kept = listOf("L0.KR", "L0.XWO", "CCL.ASIA", "CCL2.EUROPE", "CUP", "CUP2", "CL", "CL2", "SUPER", "CLUBCUP", "MVP", "ROOKIE", "MIP", "SCORER", "YACHT", "WC", "WC2", "WCMVP")
        val dropped = listOf("REG.KR", "L0R.KR", "L1.KR", "L4.XAS", "LMVP.KR", "CLUBCUPW", "CLUBCUP2", "WCW", "POTW", "UP1", "DOWN2", "ALLSTAR2")
        for (k in kept) assertTrue(Awards.inSummary(k), k)
        for (k in dropped) assertFalse(Awards.inSummary(k), k)
        // A legacy league's as before: its lower tiers, the regular season.
        for (k in listOf("L0", "L1", "L5", "REG")) assertTrue(Awards.inSummary(k), k)
    }

    @Test
    fun doublesTreblesAndUnbeatenSeasonsArePerLeague() {
        val plan = playedPlan(4)
        // k1 and j1 won every match of their 1부, m1 of KR's 2부.
        val now = listOf(Honour(4, "L0.KR", "k1"), Honour(4, "CUP", "k1"), Honour(4, "CCL.ASIA", "k1"), Honour(4, "L0.JP", "j1"), Honour(4, "L1.KR", "m1"))
        val ds = Awards.dynasties(4, now, emptyList(), plan.league, emptyList())
        assertTrue(Dynasty("k1", Awards.TREBLE, "L0.KR", 1, 4, 4) in ds, ds.toString())
        assertEquals(setOf("L0.KR", "L1.KR", "L0.JP"), ds.filter { it.kind == Awards.UNBEATEN }.map { it.title }.toSet())
        assertEquals(listOf("k1", "m1", "j1"), ds.filter { it.kind == Awards.UNBEATEN }.map { it.key })
        // The cup with JP's title is a double; a runs of league titles compare the whole kind.
        val double = Awards.dynasties(4, listOf(Honour(4, "L0.JP", "j1"), Honour(4, "CUP", "j1")), listOf(Honour(3, "L0.JP", "j1"), Honour(3, "L0.KR", "j1")), plan.league, emptyList())
        assertTrue(Dynasty("j1", Awards.DOUBLE, "L0.JP", 1, 4, 4) in double, double.toString())
        assertTrue(Dynasty("j1", Awards.RUN, "L0.JP", 2, 3, 4) in double, double.toString())
    }

    // ---- a national league played and closed ---------------------------------------------------

    /** 300 players of five nations (KR, JP, CN, GB, US: 60 each, their own leagues), newcomers too. */
    private fun nation(k: String): String = listOf("KR", "JP", "CN", "GB", "US")[k.removePrefix("p").toInt() % 5]

    @Test
    fun aNationalSeasonClosesWithLeagueMvpsLinesAndMajorTitles() {
        val cfg = LeagueConfig(national = true, doubleRoundRobin = true, divisionSize = 16, leagueMin = 20)
        val lg = PlayedLeague(300, cfg, 31L, ::nation)
        lg.club = { k -> "c" + (k.removePrefix("p").toInt() % 40 + 1) }
        // Only KR's and JP's titles are major.
        lg.major = { _, league -> league == "KR" || league == "JP" }
        lg.play(2)
        for ((s, plan) in lg.plans.withIndex()) {
            val leagues = plan.league.leagues
            assertTrue(leagues.national)
            assertEquals(listOf("KR", "JP", "CN", "GB", "US").sortedBy { yacht.engine.league.LeagueIds.order(it) }, leagues.list.map { it.id })
            val end = lg.ends[s]
            val n = plan.number
            // One league MVP per league, from its 1부; the MVP is one of them, rated highest.
            val lmvps = end.awards.filter { HonourKind.base(it.kind) == Awards.LMVP }
            assertEquals(leagues.list.map { HonourKind.of(Awards.LMVP, it.id) }, lmvps.map { it.kind })
            for (h in lmvps) assertEquals(leagues.byId(HonourKind.leagueOf(h.kind)!!)!!.top, plan.league.divisionOf(h.key))
            val mvp = assertNotNull(end.awards.singleOrNull { it.kind == "MVP" }).key
            assertTrue(mvp in lmvps.map { it.key })
            // The ratings are as they were at the last close only.
            if (s == lg.plans.lastIndex) assertEquals(lmvps.maxBy { lg.rating(it.key) }.key, mvp)
            // No award news for the league MVPs (one a league), the MVP's as before.
            assertTrue(end.events.none { it.kind == "award" && it.args.first().startsWith(Awards.LMVP) })
            assertTrue(end.events.any { it.kind == "award" && it.keys == listOf(mvp) && it.args == listOf("MVP") })
            // Season lines: tier in its league, the league and the club.
            for (lgDef in leagues.list) for (d in lgDef.divisions) for (k in plan.league.divisions[d]) {
                val line = lg.archive.lines(k).firstOrNull { it.season == n } ?: continue
                assertEquals(lgDef.id, line.league)
                assertEquals(d - lgDef.first, line.division)
                assertEquals(yacht.engine.league.ClubBook.idOf(k.removePrefix("p").toInt() % 40 + 1), line.club)
            }
            // The summary: each league's champion and the cups, not the regular season, lower tiers or league MVPs.
            val sum = assertNotNull(lg.archive.summary(n))
            assertEquals(lg.seasonHonours[s].filter { HonourKind.isTopTitle(it.kind) }.map { it.kind }.toSet(), sum.honours.filter { HonourKind.isTopTitle(it.kind) }.map { it.kind }.toSet())
            assertTrue(sum.honours.none { HonourKind.leagueOf(it.kind) != null && !HonourKind.isTopTitle(it.kind) }, sum.honours.toString())
            // The hall of fame counted major titles only.
            for (e in end.inducted.filter { it.reason == "TITLES" }) assertTrue(lg.honours.majorTitles(e.key, lg.major) >= Awards.HOF_TITLES)
        }
        // The archive keeps leagues and clubs through a reload, in sx lines older versions skip.
        val text = lg.archive.encode()
        assertTrue(text.lines().any { it.startsWith("sx ") })
        val back = Archive.decode(text)
        for (k in lg.archive.lineKeys) assertEquals(lg.archive.lines(k), back.lines(k), k)
        assertEquals(text, back.encode())
        // The review names the leagues' champions.
        val review = assertNotNull(lg.ends.last().review)
        assertTrue(review.sentences.any { "리그 우승은 " in it }, review.sentences.toString())
        println(review.sentences.joinToString("\n"))
    }

    @Test
    fun closingANationalSeasonAgainKeepsItsLeagueMvps() {
        val book = StatsBook()
        val plan = playedPlan(1)
        val honours = HonourBook()
        val archive = Archive()
        for (k in plan.league.standings.keys) book.putSeason(k, rated(if (k.endsWith("1")) 8.0 else 6.0))
        val h = listOf(Honour(1, "L0.KR", "k1"), Honour(1, "L0.JP", "j1"))
        val first = book.endSeason(plan, h, honours, archive, { 1500.0 })
        assertEquals(listOf("LMVP.KR" to "k1", "LMVP.JP" to "j1"), first.awards.filter { HonourKind.base(it.kind) == Awards.LMVP }.map { it.kind to it.key })
        honours.add(h + first.awards)
        // Killed after saving: the same awards, from the summary and the book.
        val again = book.endSeason(plan, h, honours, archive, { 1500.0 })
        assertEquals(first.awards.toSet(), again.awards.toSet())
        assertTrue(again.events.isEmpty())
    }
}

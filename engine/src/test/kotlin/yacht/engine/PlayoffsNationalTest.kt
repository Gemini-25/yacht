package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Competition
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.LeagueTitlePlayoffs
import yacht.engine.league.Leagues
import yacht.engine.league.Nations
import yacht.engine.league.Offseason
import yacht.engine.league.PromotionPlayoff
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.TieRule
import yacht.engine.league.TitlePlayoff
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The title playoffs (one bracket per league) and the promotion playoff of a national season, their saves and news. */
class PlayoffsNationalTest {
    private fun key(i: Int) = "p%04d".format(i)
    private fun index(k: String) = k.removePrefix("p").toInt()
    private fun rating(k: String) = 2000.0 - index(k)

    /** 300 Koreans, 26 Japanese and 400 of the other 26 nations: KR, JP and the four 연합 리그. */
    private fun nationOf(k: String): String {
        val i = index(k)
        val rest = Nations.ALL.drop(2)
        return when {
            i <= 300 -> "KR"
            i <= 326 -> "JP"
            else -> rest[i % rest.size].code
        }
    }

    private val national = LeagueConfig(national = true, doubleRoundRobin = true, divisionSize = 16, leagueMin = 20)

    /** The better rated wins 70% of its matches (seeded). */
    private fun play(f: Fixture, rng: Random): JobResult {
        val aWins = (rng.nextDouble() < 0.7) == (index(f.a) < index(f.b))
        val n = if (f.kind == JobKind.TIE) 2 * f.legs else 2
        val g = List(n) { if (aWins) GameScore(220, 180) else GameScore(180, 220) }
        val res = TieRule.match(g).let { if (f.kind == JobKind.TIE && it == 0) 1 else it }
        return JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), res, 0)
    }

    /** A season's league stage, played out. */
    private fun leagueStage(config: LeagueConfig, n: Int = 726, rng: Random = Random(3)): SeasonPlan {
        val ctx = Offseason.Context((1..n).map(::key), ::rating, { null }, { "" }, config, nation = ::nationOf)
        val plan = Offseason.first(5L, ctx)
        while (!plan.league.finished) plan.record(play(assertNotNull(plan.next(emptySet(), 0, 1)), rng))
        return plan
    }

    private fun text(c: Competition): String = StringBuilder().also { c.encode(it) }.toString()

    /** Plays [c] alone, one fixture at a time, until nothing is open; [each] sees every fixture first. */
    private fun run(c: Competition, rng: Random, limit: Int = Int.MAX_VALUE, each: (Fixture) -> Unit = {}): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        var n = 0
        while (n++ < limit) {
            val f = c.next(1.0, emptySet()) ?: break
            each(f)
            out += c.record(play(f, rng))
        }
        return out
    }

    private fun rank(id: String, r: Int) = LeagueRank(id, r, 0, 0.0, emptyList(), 0.0, 0, false, 0)

    @Test
    fun theTitlePlayoffIsOneBracketPerLeaguePlayedStageByStage() {
        val plan = leagueStage(national)
        plan.startPost(Competitions.postFactories())
        val titles = assertIs<LeagueTitlePlayoffs>(plan.competition(Comp.TITLE_PLAYOFF))
        val leagues = plan.league.leagues
        assertEquals(leagues.list.map { it.id }, titles.leagues)
        assertTrue(text(titles).startsWith(LeagueTitlePlayoffs.HEADER + "\n"))
        // Four stages (double elimination), each every league's at once.
        assertEquals(4, titles.stages().size)
        assertEquals(List(4) { s -> listOf(2, 2, 1, 1)[s] * leagues.list.size }, titles.stageCounts().map { it.total })
        // Seeds by the 1부's table: 1 against 4, 2 against 3.
        val firsts = HashMap<String, MutableSet<Pair<String, String>>>()
        val stages = ArrayList<Int>()
        val ids = ArrayList<Int>()
        val news = run(titles, Random(8), limit = 15) { f ->
            val slot = f.id / LeagueTitlePlayoffs.SLOT_IDS
            val id = titles.leagues[slot]
            assertTrue(f.label.startsWith(LeagueIds.short(id) + " "), f.label)
            assertTrue(f.a in plan.league.divisions[leagues.byId(id)!!.top])
            stages += titles.stageOf(f)
            ids += f.id
            if (titles.stageOf(f) == 0) firsts.getOrPut(id) { HashSet() } += f.a to f.b
        }
        for (lg in leagues.list) {
            val top = plan.league.table(lg.top).map { it.key }
            assertEquals<Set<Pair<String, String>>?>(setOf(top[0] to top[3], top[1] to top[2]), firsts[lg.id], lg.id)
        }
        // Saved halfway: read back, the same text and the same next tie.
        val half = text(titles)
        val back = TitlePlayoff.codec.decode(half.lines().dropLast(1), plan.number, plan.seed)
        assertEquals(half, text(back))
        val again = TitlePlayoff.codec.decode(half.lines().dropLast(1), plan.number, plan.seed)
        assertEquals(again.next(1.0, emptySet())?.id, titles.next(1.0, emptySet())?.also { titles.release(it) }?.id)
        val rest = run(titles, Random(8)) { f ->
            stages += titles.stageOf(f)
            ids += f.id
        }
        assertTrue(news.isEmpty() && rest.isEmpty(), "no news of its own: ${news + rest}")
        assertTrue(titles.finished)
        // No league ran ahead into a later stage; every tie its own id, inside its slot.
        assertEquals(stages.sorted(), stages)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(6 * leagues.list.size, ids.size)
        // Honours per league: the champion and the runner-up.
        val honours = titles.honours()
        for (id in titles.leagues) {
            assertEquals(1, honours.count { it.kind == "L0.$id" })
            assertEquals(1, honours.count { it.kind == "L0R.$id" })
        }
        assertEquals(2 * leagues.list.size, honours.size)
        // The view: a part per league, its bracket under its name.
        val view = assertIs<CompetitionView.Sections>(titles.view())
        assertEquals(Comp.TITLE_PLAYOFF.label, view.title)
        assertEquals(titles.leagues.map { LeagueIds.label(it) }, view.parts.map { it.title })
        for ((i, part) in view.parts.withIndex()) {
            assertTrue(part.groups.isEmpty())
            val final = assertNotNull(part.knockout).rounds.last().ties.single()
            assertEquals(honours.single { it.kind == "L0.${titles.leagues[i]}" }.key, final.winner)
        }
        // The finished playoffs read back the same.
        val done = TitlePlayoff.codec.decode(text(titles).lines().dropLast(1), plan.number, plan.seed)
        assertEquals(text(titles), text(done))
        assertEquals(honours, done.honours())
        assertTrue(done.finished)
    }

    @Test
    fun aWeekAtATimeRunsThroughTheTitlePlayoffsStages() {
        // 1주씩: nothing of a later week is handed out, and every week ends by itself.
        val ctx = Offseason.Context((1..726).map(::key), ::rating, { null }, { "" }, national, nation = ::nationOf)
        val plan = Offseason.first(5L, ctx)
        val rng = Random(12)
        val postWeeks = HashSet<Int>()
        var guard = 0
        while (!plan.finished) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            val week = plan.week
            var played = 0
            while (true) {
                val f = plan.next(emptySet(), 0, 1, lastWeek = week) ?: break
                assertTrue(f.week <= week, "week ${f.week} handed out in week $week")
                if (f.comp == Comp.TITLE_PLAYOFF) postWeeks += f.week
                plan.record(play(f, rng))
                played++
            }
            assertTrue(plan.finished || played > 0 || plan.week > week, "week $week stalls")
            check(++guard < 1_000)
        }
        assertEquals(4, postWeeks.size, "the four stages in four weeks: $postWeeks")
    }

    @Test
    fun aLegacySeasonKeepsItsTitlePlayoffAndOldSavesRead() {
        val legacy = leagueStage(LeagueConfig(divisions = 2), n = 24)
        val po = assertIs<TitlePlayoff>(TitlePlayoff.create(legacy))
        val saved = text(po)
        assertTrue(saved.startsWith(TitlePlayoff.HEADER + "\n"))
        run(po, Random(1), limit = 3)
        val back = assertIs<TitlePlayoff>(TitlePlayoff.codec.decode(text(po).lines().dropLast(1), 1, legacy.seed))
        assertEquals(text(po), text(back))
        // A title playoff saved before national leagues.
        val old = TitlePlayoff.codec.decode(listOf("titlepo 1", "long 1", "e ai01 1 -", "e ai02 2 -", "e ai03 3 -", "e ai04 4 -"), 1, 7L)
        assertIs<TitlePlayoff>(old)
        assertEquals(3, old.fixturesTotal)
        assertNull(TitlePlayoff.create(leagueStage(national.copy(titlePlayoff = false), n = 60)))
    }

    @Test
    fun aLeagueWhose1부HasFewerThanFourHasNoBracket() {
        val kr = (1..16).map(::key)
        val jp = (17..19).map(::key)
        val season = Season.start(1, 5L, listOf(kr.take(8), kr.drop(8), jp), true, Leagues.national(listOf("KR", "JP"), listOf(2, 1)))
        val plan = SeasonPlan(season, national)
        val rng = Random(4)
        while (!plan.league.finished) plan.record(play(assertNotNull(plan.next(emptySet(), 0, 1)), rng))
        val titles = assertIs<LeagueTitlePlayoffs>(TitlePlayoff.create(plan))
        assertEquals(listOf("KR"), titles.leagues)
        assertEquals(6, titles.fixturesTotal)
        val only = SeasonPlan(Season.start(1, 5L, listOf(jp), true, Leagues.national(listOf("JP"), listOf(1))), national)
        assertNull(TitlePlayoff.create(only))
    }

    @Test
    fun thePromotionPlayoffHasEveryLeaguesTiesAndTellsOnlyTheBestRanked() {
        val plan = leagueStage(national)
        val leagues = plan.league.leagues
        // GB is ranked but plays in no league of its own this season.
        plan.ranking = LeagueRanking(1, listOf(rank("XEU", 0), rank("KR", 1), rank("GB", 2), rank("JP", 3), rank("XAS", 4), rank("XAM", 5)))
        plan.startPost(Competitions.postFactories())
        val promo = assertIs<PromotionPlayoff>(plan.competition(Comp.PROMOTION_PLAYOFF))
        assertEquals(setOf("XEU", "KR", "JP", "XAS"), promo.newsLeagues)
        assertEquals(leagues.list.sumOf { it.size - 1 }, promo.spots.size, "a tie per boundary")
        assertEquals(promo.spots.indices.toList(), promo.spots.map { it.slot })
        for (lg in leagues.list) assertEquals((0 until lg.size - 1).toList(), promo.spots.filter { it.league == lg.id }.map { it.division })
        val saved = text(promo)
        assertTrue(saved.startsWith(PromotionPlayoff.NATIONAL_HEADER + "\n") && "\nnews KR JP XAS XEU\n" in saved, saved.take(200))
        val labels = ArrayList<String>()
        val news = run(promo, Random(6), limit = 5) { labels += it.label }
        val mid = text(promo)
        val back = assertIs<PromotionPlayoff>(PromotionPlayoff.codec.decode(mid.lines().dropLast(1), plan.number, plan.seed))
        assertEquals(mid, text(back))
        assertEquals(promo.spots, back.spots)
        assertEquals(promo.newsLeagues, back.newsLeagues)
        val all = news + run(promo, Random(6)) { labels += it.label }
        assertTrue(promo.finished)
        assertEquals("한국 1·2부 승강전", labels.first())
        assertEquals(promo.spots.map { PromotionPlayoff.label(it.division, it.league) }, labels)
        // Only the 1·2부 tie of each best-ranked league makes news, and only the tie itself.
        assertEquals(List(4) { "cup_round" }, all.map { it.kind })
        val told = all.map { e -> promo.spots.single { it.upper in e.keys && it.lower in e.keys } }
        assertEquals(setOf("KR", "JP", "XAS", "XEU"), told.map { it.league }.toSet())
        assertTrue(told.all { it.division == 0 })
        assertTrue(all.any { "한국 1·2부 승강전" in it.args })
        val done = assertIs<PromotionPlayoff>(PromotionPlayoff.codec.decode(text(promo).lines().dropLast(1), plan.number, plan.seed))
        assertEquals(promo.honours(), done.honours())
        // No ranking yet (the first national season): no news at all.
        val quiet = leagueStage(national)
        quiet.startPost(Competitions.postFactories())
        val q = assertIs<PromotionPlayoff>(quiet.competition(Comp.PROMOTION_PLAYOFF))
        assertTrue(q.newsLeagues.isEmpty() && "\nnews" !in text(q))
        assertTrue(run(q, Random(2)).isEmpty())
    }

    @Test
    fun aLegacyPromotionPlayoffIsSavedAndReadAsBefore() {
        val legacy = leagueStage(LeagueConfig(divisions = 3), n = 36)
        val po = assertNotNull(PromotionPlayoff.create(legacy))
        assertTrue(po.spots.all { it.league == LeagueIds.LEGACY })
        val saved = text(po)
        assertTrue(saved.startsWith(PromotionPlayoff.HEADER + "\n") && "news" !in saved)
        val back = assertIs<PromotionPlayoff>(PromotionPlayoff.codec.decode(saved.lines().dropLast(1), 1, legacy.seed))
        assertEquals(saved, text(back))
        // A promotion playoff saved before national leagues.
        val old = assertIs<PromotionPlayoff>(PromotionPlayoff.codec.decode(listOf("promopo 1", "series 1", "t 0 ai10 ai15", "t 1 ai22 ai27"), 1, 7L))
        assertEquals(listOf(PromotionPlayoff.Spot(0, "ai10", "ai15"), PromotionPlayoff.Spot(1, "ai22", "ai27")), old.spots)
        assertEquals("1·2부 승강전", old.next(1.0, emptySet())?.label)
    }
}

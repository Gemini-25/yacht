package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubLeague
import yacht.engine.league.Comp
import yacht.engine.league.Competition
import yacht.engine.league.Competitions
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.Hometowns
import yacht.engine.league.Honour
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueWorld
import yacht.engine.league.MatchFormat
import yacht.engine.league.Nations
import yacht.engine.league.NewsBook
import yacht.engine.league.Offseason
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.Stage
import yacht.engine.league.TieRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The season's weeks: where stages fall, when they open, and the weeks stamped on fixtures, news and the feed. */
class SeasonCalendarTest {
    /** A competition that only has stages (for placing). */
    private class Staged(override val comp: Comp, private val list: List<Stage>) : Competition {
        override fun next(p: Double, busy: Set<String>): Fixture? = null
        override fun release(f: Fixture) {}
        override fun record(r: JobResult): List<LeagueEvent> = emptyList()
        override val finished: Boolean get() = false
        override val fixturesLeft: Int get() = 0
        override val fixturesTotal: Int get() = 0
        override fun honours(): List<Honour> = emptyList()
        override fun encode(out: StringBuilder) {}
        override fun stages(): List<Stage> = list
    }

    private fun stages(vararg t: Double) = t.mapIndexed { i, x -> Stage("s$i", i * 0.1, x) }

    @Test
    fun stagesGetTheirOwnWeeksInOrder() {
        val cup = Staged(Comp.CUP, stages(0.04, 0.16, 0.28, 0.40, 0.52, 0.64, 0.76, 0.88))
        val cl = Staged(Comp.CHAMPIONS, stages(0.08, 0.16, 0.24, 0.32, 0.40, 0.48, 0.60, 0.72, 0.94))
        val club = Staged(Comp.CLUB, stages(*DoubleArray(15) { 0.02 + 0.9 * it / 14 }))
        val star = Staged(Comp.ALL_STAR, stages(0.5))
        val cal = SeasonCalendar.build(51, listOf(cup, cl, star, club), listOf(Comp.TITLE_PLAYOFF to stages(0.0, 0.0)))
        val cupWeeks = cup.stages().indices.map { cal.week(Comp.CUP, it) }
        val clWeeks = cl.stages().indices.map { cal.week(Comp.CHAMPIONS, it) }
        assertEquals(listOf(3, 10, 15, 22, 27, 33, 39, 45), cupWeeks)
        assertEquals(listOf(5, 9, 13, 17, 21, 25, 31, 37, 48), clWeeks)
        assertEquals(26, cal.week(Comp.ALL_STAR, 0))
        // One cup, champions league or event stage a week; the club league has its own lane.
        val lane = (cupWeeks + clWeeks + cal.week(Comp.ALL_STAR, 0))
        assertEquals(lane.size, lane.toSet().size, lane.toString())
        val clubWeeks = club.stages().indices.map { cal.week(Comp.CLUB, it) }
        assertEquals(clubWeeks.sorted(), clubWeeks)
        assertEquals(clubWeeks.size, clubWeeks.toSet().size)
        assertEquals(2, clubWeeks.first())
        // The playoffs come after the league: semis in week 52, the final in 53.
        assertEquals(52, cal.week(Comp.TITLE_PLAYOFF, 0))
        assertEquals(53, cal.week(Comp.TITLE_PLAYOFF, 1))
        assertEquals(53, cal.lastWeek)
        assertEquals(listOf(Comp.CUP to 0), cal.at(3))
        // The progress handed to a competition opens just the stages due by that week.
        assertEquals(SeasonCalendar.CLOSED, cal.progress(Comp.CUP, 2))
        assertEquals(0.0, cal.progress(Comp.CUP, 3))
        assertEquals(0.1, cal.progress(Comp.CUP, 14))
        assertEquals(0.2, cal.progress(Comp.CUP, 15))
        assertTrue(cal.progress(Comp.LEAGUE, 3).isNaN())
    }

    @Test
    fun aShortSeasonStacksStagesButKeepsTheirOrder() {
        val cup = Staged(Comp.CUP, stages(0.04, 0.16, 0.28, 0.40, 0.52, 0.64, 0.76, 0.88))
        val cl = Staged(Comp.CHAMPIONS, stages(0.08, 0.16, 0.24, 0.32, 0.40, 0.48, 0.60, 0.72, 0.94))
        for (r in listOf(1, 3, 7, 12)) {
            val cal = SeasonCalendar.build(r, listOf(cup, cl), emptyList())
            for (c in listOf(cup, cl)) {
                val weeks = c.stages().indices.map { cal.week(c.comp, it) }
                assertEquals(weeks.sorted(), weeks, "$r ${c.comp}: $weeks")
                assertTrue(weeks.all { it in 1..r }, "$r ${c.comp}: $weeks")
            }
            // As few per week as fit: 17 stages in r weeks.
            val perWeek = (1..r).map { cal.at(it).size }
            assertTrue(perWeek.max() <= (17 + r - 1) / r + (if (r < 9) 8 else 0), "$r $perWeek")
        }
        // The same input places the same way.
        val a = SeasonCalendar.build(51, listOf(cup, cl), emptyList())
        val b = SeasonCalendar.build(51, listOf(cup, cl), emptyList())
        assertEquals((1..51).map { a.at(it) }, (1..51).map { b.at(it) })
    }

    // ---- a whole league ---------------------------------------------------------------------------

    private val base = (1..100).map { "b%03d".format(it) }
    private val families = Family.entries.filter { it != Family.MINE }

    private fun strength(k: String): Int = if (k.startsWith("b")) 1000 - k.drop(1).toInt() else 500 - (k.hashCode() and 255)

    private fun games(a: String, b: String, n: Int, salt: Int): List<GameScore> =
        List(n) { i -> if ((strength(a) + salt + i) % 7 < 4 == strength(a) >= strength(b)) GameScore(230, 190, aYacht = true) else GameScore(190, 230) }

    private fun play(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f.a, f.b, 2, f.id).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f.a, f.b, 2 * f.legs, f.id).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games(x, y, 2, f.id), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    private fun world(config: LeagueConfig): LeagueWorld {
        val w = LeagueWorld(Ladder(base + Ladder.ME), config)
        w.players.ensureGenerated(config.generated)
        w.members = base + w.players.alive.map { it.key }
        w.ladder = Ladder(w.members + Ladder.ME)
        w.family = { k -> if (k.startsWith("b")) families[k.drop(1).toInt() % families.size] else Family.EV }
        return w
    }

    /** One fixture handed out, with the plan's week at that moment. */
    private class Handed(val f: Fixture, val week: Int)

    /**
     * Plays a season with [workers] jobs in flight (results come back oldest first, or a random
     * one with [rng]); returns every fixture handed out and the news.
     */
    private fun season(w: LeagueWorld, workers: Int, rng: java.util.Random? = null): Pair<List<Handed>, List<LeagueEvent>> {
        val handed = ArrayList<Handed>()
        val news = ArrayList<LeagueEvent>()
        val flight = ArrayList<Fixture>()
        var guard = 0
        while (!w.seasonOver) {
            while (flight.size < workers) {
                val busy = flight.flatMap { it.players }.toSet()
                val p = w.plan!!
                val wk = if (p.league.finished) p.week else p.league.currentMatchday + 1
                val f = w.next(busy, flight.count { !p.isTeam(it.comp) && it.comp != Comp.LEAGUE }, maxOf(1, workers - 1)) ?: break
                handed += Handed(f, wk)
                flight += f
            }
            if (flight.isEmpty()) error("stuck at ${w.plan?.fixturesLeft} left")
            val f = flight.removeAt(rng?.nextInt(flight.size) ?: 0)
            news += w.recordJob(play(f)).events
            check(++guard < 200_000)
        }
        return handed to news
    }

    private fun rollover(w: LeagueWorld) {
        w.ladder.fit()
        val c = w.closeSeason()
        if (c.rosterChanged) {
            w.members = base + w.players.alive.map { it.key }
            w.ladder = Ladder.decode(w.members + Ladder.ME, w.ladder.encode())
        }
        w.openSeason()
    }

    private fun check(p: SeasonPlan, handed: List<Handed>, lookAhead: Boolean) {
        val cal = p.calendar
        for (h in handed) {
            val f = h.f
            if (f.comp == Comp.LEAGUE) {
                assertEquals(f.label, "${f.week}라운드")
                continue
            }
            val c = p.competition(f.comp)!!
            val s = c.stageOf(f)
            assertTrue(s >= 0, "$f")
            assertEquals(cal.week(f.comp, s), f.week, "$f")
            if (f.week <= p.leagueWeeks) {
                // Never before its week (a week early only when nothing else was free).
                assertTrue(f.week <= h.week + (if (lookAhead) 1 else 0), "${f.comp} ${f.label} week ${f.week} handed out in week ${h.week}")
            } else {
                assertTrue(h.week > p.leagueWeeks, "a playoff before the league is over: $f")
            }
        }
    }

    @Test
    fun weekByWeekGivesNothingOfALaterWeekAndNeverStalls() {
        val w = world(LeagueConfig(generated = 100))
        w.openFirstSeason(11L)
        val p = w.plan!!
        val rng = java.util.Random(4)
        var weeks = 0
        var guard = 0
        while (!w.seasonOver) {
            // 1주씩: the week in play, then nothing of a later week until it is played.
            val goal = p.week
            val flight = ArrayList<Fixture>()
            while (true) {
                while (flight.size < 4) {
                    val busy = flight.flatMap { it.players }.toSet()
                    val f = w.next(busy, flight.count { !p.isTeam(it.comp) && it.comp != Comp.LEAGUE }, 3, lastWeek = goal) ?: break
                    assertTrue(f.week == SeasonCalendar.NONE || f.week <= goal, "${f.comp} ${f.label} of week ${f.week} in week $goal")
                    flight += f
                }
                if (flight.isEmpty()) break
                w.recordJob(play(flight.removeAt(rng.nextInt(flight.size))))
                check(++guard < 200_000)
            }
            // Its league round is played (a week that cannot be played would stall here).
            assertTrue(w.seasonOver || p.week > goal, "week $goal still in play")
            weeks++
        }
        assertEquals(p.calendar.lastWeek, weeks)
        assertTrue(p.comps.all { it.finished } && p.post.all { it.finished })
        // A league round never runs past the last matchday it is allowed.
        val w2 = world(LeagueConfig(generated = 100))
        w2.openFirstSeason(12L)
        val l = w2.plan!!.league
        val taken = generateSequence { l.next(emptySet(), lastMatchday = 0) }.toList()
        assertTrue(taken.isNotEmpty() && taken.all { it.matchday == 0 })
        assertEquals(null, l.next(emptySet(), lastMatchday = 0))
    }

    @Test
    fun aDefaultSeasonPlaysEveryStageInItsWeek() {
        val w = world(LeagueConfig(generated = 100))
        w.openFirstSeason(5L)
        val (h1, n1) = season(w, 1)
        check(w.plan!!, h1, lookAhead = false)
        assertTrue(n1.filter { it.comp != null && it.comp != Comp.FRIENDLY }.all { it.week != SeasonCalendar.NONE }, n1.filter { it.week == SeasonCalendar.NONE }.map { it.kind }.distinct().toString())
        rollover(w)
        val p = w.plan!!
        val cal = p.calendar
        println((0..cal.lastWeek).joinToString("\n") { wk -> "$wk: " + cal.at(wk).joinToString(" · ") { (c, s) -> "${c.label} ${cal.entry(c)!!.stages[s].label}" } })
        assertTrue(p.comps.map { it.comp }.containsAll(listOf(Comp.CUP, Comp.CHAMPIONS, Comp.SUPER, Comp.ALL_STAR, Comp.FAMILY, Comp.CLUB)), p.comps.map { it.comp }.toString())
        assertEquals(1, cal.week(Comp.SUPER, 0))
        // One cup, champions league, family cup or event stage a week.
        val lane = cal.entries.filter { !it.post && it.comp != Comp.CLUB }.flatMap { e -> e.stages.indices.map { e.week(it) } }
        assertEquals(lane.size, lane.toSet().size, lane.toString())
        val (h2, _) = season(w, 6, java.util.Random(3))
        check(p, h2, lookAhead = true)
        assertTrue(p.comps.all { it.finished } && p.post.all { it.finished })
        // The schedule is cheap enough for every screen snapshot.
        repeat(50) { p.schedule() }
        val t0 = System.nanoTime()
        repeat(200) { p.schedule() }
        println("schedule: ${"%.3f".format((System.nanoTime() - t0) / 200 / 1e6)} ms")
        // The schedule adds up to the season.
        val sched = p.schedule()
        assertEquals(p.fixturesTotal, sched.sumOf { it.total })
        assertTrue(sched.all { it.done })
        assertEquals(sched.sortedBy { it.week }.map { it.week }, sched.map { it.week })
    }

    /**
     * Plays a season on [workers] simulated workers where a job takes as long as its games (team
     * matches five boards, heavy AIs [heavy] times longer); returns, per stage, how many weeks
     * after its own week its last fixture finished.
     */
    private fun timedSeason(w: LeagueWorld, workers: Int, heavy: Set<String>, slow: Double): Map<Pair<Comp, Int>, Int> {
        class Job(val f: Fixture, val end: Double)
        val flight = java.util.PriorityQueue<Job>(compareBy { it.end })
        var now = 0.0
        val lateness = HashMap<Pair<Comp, Int>, Int>()
        var guard = 0
        while (!w.seasonOver) {
            while (flight.size < workers) {
                val p = w.plan!!
                val busy = flight.flatMap { it.f.players }.toSet()
                val f = w.next(busy, flight.count { !p.isTeam(it.f.comp) && it.f.comp != Comp.LEAGUE }, maxOf(1, workers - 1)) ?: break
                val cost = f.players.sumOf { if (it in heavy) slow else 1.0 } / 2.0
                val games = when (f.kind) {
                    JobKind.MATCH -> 2
                    JobKind.TIE -> 2 * f.legs
                    JobKind.TEAM -> 2 * f.lineupA.size
                }
                flight += Job(f, now + games * cost)
            }
            val j = flight.poll() ?: error("stuck")
            now = j.end
            val p = w.plan!!
            w.recordJob(play(j.f))
            if (j.f.comp != Comp.LEAGUE && j.f.comp != Comp.FRIENDLY) {
                val c = p.competition(j.f.comp)!!
                val s = c.stageOf(j.f)
                if (c.stageCounts()[s].done) lateness[j.f.comp to s] = p.week - j.f.week
            }
            if (++guard % 500 == 0) {
                // The quick count (from the fixtures left) agrees with a scan of every division.
                val l = p.league
                val scan = l.divisions.indices.map { l.matchdayCounts(it) }
                assertEquals(List(l.matchdays) { k -> scan.sumOf { it[k].played } to scan.sumOf { it[k].total } }, l.matchdayCounts().map { it.played to it.total })
            }
            check(guard < 200_000)
        }
        return lateness
    }

    @Test
    fun stagesFinishCloseToTheirWeekWithSlowAis() {
        val w = world(LeagueConfig(generated = 100))
        w.openFirstSeason(8L)
        val heavy = base.take(6).toSet()
        for (workers in listOf(1, 4, 8)) {
            val late = timedSeason(w, workers, heavy, slow = 20.0)
            val p = w.plan!!
            println("workers $workers: " + late.entries.sortedBy { p.calendar.week(it.key.first, it.key.second) }.joinToString(" ") { (k, v) -> "${k.first.code}${k.second}+$v" })
            // A stage of the regular season ends within a couple of weeks of its own.
            val regular = late.filterKeys { (c, s) -> p.calendar.week(c, s) <= p.leagueWeeks }
            assertTrue(regular.values.all { it <= 3 }, "$workers workers: $regular")
            rollover(w)
        }
    }

    // ---- legacy plans keep their weeks -----------------------------------------------------------

    /**
     * A legacy plan of [n] players for season [number] with every competition the factories make
     * and the club league, as if last season's tables were this season's divisions.
     */
    private fun legacyPlan(n: Int, number: Int, config: LeagueConfig): SeasonPlan {
        val keys = (1..n).map { "p%04d".format(it) }
        fun rating(k: String) = 2000.0 - k.drop(1).toInt()
        val nations = Nations.ALL.map { it.code }
        val nation = { k: String -> nations[k.drop(1).toInt() % nations.size] }
        val family = { k: String -> families[k.drop(1).toInt() % families.size] }
        val ctx = Offseason.Context(keys, ::rating, family, { "서울" }, config, nation = nation)
        val league = Offseason.first(31L, ctx, number).league
        val last = number - 1
        val honours = listOf(Honour(last, "L0", keys[2]), Honour(last, "CUP", keys[5]), Honour(last, "CUP2", keys[7]), Honour(last, "MVP", keys[1]))
        val tiers = league.leagues.list.single().tiers
        val start = SeasonStart(number, league.seed, config, keys, ::rating, league.divisions, league.divisions, honours, family, { "서울" }, honours, nation, tiers, tiers)
        val factories = Competitions.factories() + ClubLeague.factory({ null }, { s -> ClubBook.create(s.ranking, s.config.clubs, s.config.squad, s.seed, Hometowns.ALL) })
        return SeasonPlan(league, config, factories.mapNotNull { it(start) }.toMutableList())
    }

    /** Every stage of [p] on its week, and the season's length. */
    private fun weeks(p: SeasonPlan): String = buildString {
        val cal = p.calendar
        append("${cal.leagueWeeks} ${cal.postWeeks} ${cal.lastWeek}\n")
        for (s in p.schedule()) append("${s.comp.code} ${s.stage} ${s.label} ${s.week} ${s.total} ${s.final} ${s.set}\n")
    }

    @Test
    fun aLegacyPlanKeepsItsWeeks() {
        val rules = listOf("default" to LeagueConfig(), "single" to LeagueConfig(matchFormat = MatchFormat.MIRRORED, seriesKnockouts = false, doubleElimination = false))
        val got = LinkedHashMap<String, String>()
        for ((name, config) in rules) for (n in listOf(60, 200, 1000)) for (number in listOf(2, 3, 5, 6)) {
            val p = legacyPlan(n, number, config)
            if (n == 1000 && number == 5) {
                assertEquals(listOf(Comp.CHAMPIONS, Comp.CUP, Comp.SUPER, Comp.ALL_STAR, Comp.FAMILY, Comp.CONTINENTAL, Comp.CLUB), p.comps.map { it.comp })
            }
            got["$name $n $number"] = print.sha(weeks(p))
        }
        assertEquals(LEGACY_WEEKS, got)
    }

    private val print = CompetitionPrint { 0.0 }

    @Test
    fun weeksAreSavedWithTheNewsAndTheFeedAndOldLinesStillRead() {
        val w = world(LeagueConfig(divisions = 2, generated = 0, clubs = 0, familyCup = false))
        w.openFirstSeason(11L)
        season(w, 2)
        val articles = w.news.all
        assertTrue(articles.any { it.week in 1..w.plan!!.leagueWeeks }, articles.map { it.week }.toString())
        assertTrue(articles.filter { it.kind.startsWith("draw") }.all { it.week == SeasonCalendar.PRESEASON })
        val again = NewsBook.decode(w.news.encode())
        assertEquals(articles, again.all)
        // A line without the week (older versions) reads as an article of no known week.
        val old = w.news.encode().lines().joinToString("\n") { l -> if (l.startsWith("a ") && l.split(' ').size == 9) l.substringBeforeLast(' ') else l }
        assertTrue(NewsBook.decode(old).all.all { it.week == SeasonCalendar.NONE })
        assertEquals(articles.map { it.headline }, NewsBook.decode(old).all.map { it.headline })
        // The feed says where each match was played.
        val feed = w.ladder.feed.toList()
        assertTrue(feed.all { it.comp != null }, feed.take(3).toString())
        assertTrue(feed.all { it.label.isNotEmpty() && it.week != SeasonCalendar.NONE }, feed.take(3).toString())
        val read = Ladder.decode(w.members + Ladder.ME, w.ladder.encode()).feed.toList()
        assertEquals(feed.map { Triple(it.comp, it.label, it.week) }, read.map { Triple(it.comp, it.label, it.week) })
        // Each result knows its season (week names depend on the season's length).
        assertTrue(feed.all { it.season == w.plan!!.number })
        assertEquals(feed.map { it.season }, read.map { it.season })
        // A line saved before the season was kept (9 fields) reads as season unknown.
        val noSeason = w.ladder.encode().lines().joinToString("\n") { l -> if (l.startsWith("m ") && l.split(' ').size == 10) l.substringBeforeLast(' ') else l }
        val older = Ladder.decode(w.members + Ladder.ME, noSeason).feed.toList()
        assertTrue(older.all { it.season == 0 && it.comp != null })
        val stripped = w.ladder.encode().lines().joinToString("\n") { l -> if (l.startsWith("m ") && l.split(' ').size == 10) l.split(' ').take(8).joinToString(" ") else l }
        val oldFeed = Ladder.decode(w.members + Ladder.ME, stripped).feed.toList()
        assertEquals(feed.size, oldFeed.size)
        assertTrue(oldFeed.all { it.comp == null && it.week == SeasonCalendar.NONE && it.label == "" })
        // Events carry the week as a sixth field that older lines do not have.
        val e = LeagueEvent("upset", 3, listOf("a", "b"), listOf("120"), Comp.CUP, 15)
        assertEquals(e, LeagueEvent.decode(e.encode()))
        assertEquals(e.copy(week = SeasonCalendar.NONE), LeagueEvent.decode(e.encode().substringBeforeLast('|')))
        assertNotEquals(e.encode(), e.copy(week = SeasonCalendar.NONE).encode())
    }

    companion object {
        /** [aLegacyPlanKeepsItsWeeks]' hashes, taken from the code before the national lanes. */
        private val LEGACY_WEEKS = mapOf(
            "default 60 2" to "0c9383e539d13d59b1a8d261aa13256c39432ca7a52736608e66676ef11361de",
            "default 60 3" to "2c2fc2ab298583a0ff0baf986c43b215a2cfb51509e3e681f0a531aae2011ac5",
            "default 60 5" to "0c9383e539d13d59b1a8d261aa13256c39432ca7a52736608e66676ef11361de",
            "default 60 6" to "2c2fc2ab298583a0ff0baf986c43b215a2cfb51509e3e681f0a531aae2011ac5",
            "default 200 2" to "d6fae3a5bb931b4eb1c5dc40c680401b5fa6acdace85baccba0bfa98f4aa183f",
            "default 200 3" to "461da8fe3d7a80b71ff739ddf80a06db1b9fb81494d3cee6f9062637821d8b52",
            "default 200 5" to "85fe85b8268a8b0f04fa8695e291013276a32cc806cd851185357772ec027594",
            "default 200 6" to "c509ccb2146d569c0d6c56ffeb612af8fab787da3be6ff51cbb631bb931b86f3",
            "default 1000 2" to "908d0e5dc9e26d706398d05b6f41f7cb941a01a57f65829c19196981db95badc",
            "default 1000 3" to "d827c1cd0afc55a499896faf4e84af7c644e7de724998a30488cfe7c7d90a1a8",
            "default 1000 5" to "9e10ea23df252064da6aa2e7300595ecdb4ecd82524f42652c1ff58b4b05d0fa",
            "default 1000 6" to "c7a2983a90f3ede080322677b5d5fc403210bdfb39d909737b5ceadb4867ba30",
            "single 60 2" to "c7d3657bd44eb211e3c35fb67725b871503373bd3df81140ff54215cd74e5a24",
            "single 60 3" to "1ca4667a009e495f44ab36811ddb1f7bb775819c46ddea436e366432c51e2770",
            "single 60 5" to "c7d3657bd44eb211e3c35fb67725b871503373bd3df81140ff54215cd74e5a24",
            "single 60 6" to "1ca4667a009e495f44ab36811ddb1f7bb775819c46ddea436e366432c51e2770",
            "single 200 2" to "b51c30790b798dfa0f24d1631e4f85199afd98f7f4133309eed1554f07eb8f13",
            "single 200 3" to "5c9003b3de02915aecda3f4319df5fcf8dcdd815a98b1e7c923a8e3d35461f18",
            "single 200 5" to "01f7eb7f54fa85fbfbfccb52b2e47acd5e6f5f0a4d4ff830127992b4759ea682",
            "single 200 6" to "8f0692b21468f64bc17e5f490694ba97be7d386682088df593cf7b17e30207e2",
            "single 1000 2" to "0c5028e2a8b0e2041afda5416a67b0343d89d540ea3a4fdd16e880c41bacf4c2",
            "single 1000 3" to "bb7a0280d88e2a6784692ba1b1458ef934bbb24ef9db93dd6b3d0c3fd4d06566",
            "single 1000 5" to "fa0b05e66db43c7ab5326f589b9d52549e4e57bb09a5c3e18928a181b926c8dd",
            "single 1000 6" to "ab5a5399a49127b78e44c2c6d9a94147dc3dad41fbe291e0d7670a9c081e7c3d",
        )
    }
}

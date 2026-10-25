package yacht.engine

import yacht.engine.league.AllStar
import yacht.engine.league.BoardResult
import yacht.engine.league.ChampionsLeague
import yacht.engine.league.Comp
import yacht.engine.league.Competition
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.Cup
import yacht.engine.league.DrawEntry
import yacht.engine.league.Family
import yacht.engine.league.FamilyCup
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Knockout
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.Offseason
import yacht.engine.league.PromotionPlayoff
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.SuperCup
import yacht.engine.league.TieRule
import yacht.engine.league.TitlePlayoff
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompetitionsTest {
    // Synthetic league: ai01 is the strongest, 20 rating points apart, families in turn.
    private fun key(i: Int) = "ai%02d".format(i)
    private fun index(k: String) = k.removePrefix("ai").toInt()
    private fun rating(k: String) = 1700.0 - 20 * index(k)
    private val families = Family.entries.filter { it != Family.MINE }
    private fun family(k: String): Family? = families[(index(k) - 1) % families.size]

    private fun start(
        n: Int,
        number: Int = 1,
        divisions: Int = 2,
        config: LeagueConfig = LeagueConfig(),
        lastTables: List<List<String>> = emptyList(),
        lastHonours: List<Honour> = emptyList(),
        history: List<Honour> = emptyList(),
        family: (String) -> Family? = ::family,
    ): SeasonStart {
        val keys = (1..n).map(::key)
        return SeasonStart(number, 99L + number, config, keys, ::rating, Season.split(keys, divisions), lastTables, lastHonours, family, { "서울" }, history)
    }

    /** The stronger AI wins every game ([flip]: the weaker); team matches board by board. */
    private fun play(f: Fixture, flip: Boolean = false, level: Boolean = false): JobResult {
        fun games(a: String, b: String, n: Int): List<GameScore> {
            val aWins = (index(a) < index(b)) != flip
            return List(n) { if (level) GameScore(200, 200) else if (aWins) GameScore(220, 180) else GameScore(180, 220) }
        }
        return when (f.kind) {
            JobKind.MATCH -> {
                val g = games(f.a, f.b, 2)
                JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0)
            }
            JobKind.TIE -> {
                val g = games(f.a, f.b, 2 * f.legs)
                // As LeagueMatch.playJob: still level, side A goes through.
                JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0)
            }
            JobKind.TEAM -> {
                val boards = f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games(x, y, 2), emptyList()) }
                JobResult(f, boards, TieRule.team(boards), 0)
            }
        }
    }

    /** Plays [c] alone at progress [p] until nothing is open; returns the news. */
    private fun run(c: Competition, p: Double, result: (Fixture) -> JobResult = { play(it) }): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        while (true) out += c.record(result(c.next(p, emptySet()) ?: break))
        return out
    }

    /** Plays a whole season plan (post-season included), recording every fixture handed out. */
    private fun drive(plan: SeasonPlan, seen: MutableList<Fixture>? = null, result: (Fixture) -> JobResult = { play(it) }): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        while (!plan.finished) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            val f = plan.next(emptySet(), 0, 1) ?: break
            seen?.add(f)
            out += plan.record(result(f))
        }
        assertTrue(plan.finished, "the season ends")
        return out
    }

    private fun text(c: Competition) = StringBuilder().also { c.encode(it) }.toString()

    private fun lines(c: Competition) = text(c).lines().filter { it.isNotEmpty() }

    @Test
    fun bracketSeedsMeetLateAndTheBestGetTheByes() {
        assertEquals(listOf(1, 8, 4, 5, 2, 7, 3, 6), Knockout.seedOrder(8).toList())
        assertEquals(16, Knockout.size(13))
        val entries = (1..5).map { DrawEntry(key(it), it, rating(key(it))) }
        val pairs = Knockout.pairings(entries)
        // 8 places, 5 AIs: seeds 1-3 rest, 4 meets 5.
        assertEquals(listOf("ai01" to null, "ai04" to "ai05", "ai02" to null, "ai03" to null), pairs.map { it.first?.key to it.second?.key })
    }

    @Test
    fun theCupOpensByProgressGivesByesAndCrownsTheFavourite() {
        val cup = assertNotNull(Cup.create(start(13)))
        assertEquals(12, cup.fixturesTotal)
        assertNull(cup.next(0.0, emptySet()), "the first round opens at 5%")
        val view = cup.view() as CompetitionView.Bracket
        assertEquals(listOf("16강", "8강", "4강", "결승"), view.rounds.map { it.label })
        val byes = view.rounds[0].ties.filter { it.b == null }
        assertEquals(listOf("ai01", "ai02", "ai03"), byes.map { it.a }.sortedBy { it })
        assertTrue(byes.all { it.winner == it.a && !it.played })
        assertEquals(listOf("draw_cup"), cup.opening().map { it.kind })
        // A busy AI waits; a released tie comes back.
        val f = assertNotNull(cup.next(0.05, emptySet()))
        assertEquals(JobKind.TIE, f.kind)
        assertEquals(1, f.legs)
        assertEquals("16강", f.label)
        assertTrue(index(f.a) < index(f.b), "side A is the better seed")
        assertEquals(DiceStream.mix(100L, Comp.CUP.code.code.toLong(), f.id.toLong()), f.seed, "a deal of the season, the cup and the tie")
        cup.release(f)
        assertEquals(f, cup.next(0.05, emptySet()))
        cup.release(f)
        val g = assertNotNull(cup.next(0.05, setOf(f.a)))
        assertTrue(f.a !in g.players)
        cup.release(g)
        // Seed 4 loses to seed 13 (180 rating points below): an upset and a giant killing.
        val events = run(cup, 0.05) { x -> play(x, flip = x.a == "ai04") }
        assertEquals(7, cup.fixturesLeft, "5 first-round ties played")
        assertEquals(listOf(LeagueEvent("upset", 1, listOf("ai13", "ai04"), listOf("180"), Comp.CUP)), events.filter { it.kind == "upset" })
        assertTrue(events.any { it.kind == "cup_round" && it.keys == listOf("ai13", "ai04") })
        assertTrue(events.any { it.kind == "eliminated" && it.keys == listOf("ai04") })
        assertNull(cup.next(0.1, emptySet()), "the second round opens at 15%")
        val quarter = assertNotNull(cup.next(0.15, emptySet()))
        assertEquals("8강", quarter.label)
        cup.release(quarter)
        val rest = run(cup, 1.0)
        assertTrue(cup.finished)
        assertEquals(0, cup.fixturesLeft)
        assertEquals(listOf(Honour(1, "CUP", "ai01"), Honour(1, "CUP2", "ai02")), cup.honours())
        assertTrue(rest.any { it.kind == "final" && it.keys == listOf("ai01", "ai02") && it.args == listOf("2-0") })
        assertTrue(rest.any { it.kind == "title" && it.keys == listOf("ai01") })
    }

    @Test
    fun aLongCupFinishesBeforeTheEndAndAtFullProgress() {
        val cup = assertNotNull(Cup.create(start(205)))
        assertEquals(204, cup.fixturesTotal)
        val view = cup.view() as CompetitionView.Bracket
        assertEquals(8, view.rounds.size)
        assertEquals("256강", view.rounds[0].label)
        assertEquals(51, view.rounds[0].ties.count { it.b == null })
        // The final opens at 0.75: everything is playable by then.
        run(cup, 0.75)
        assertTrue(cup.finished)
        // And when the league jumps to its end, every round opens.
        val other = assertNotNull(Cup.create(start(205)))
        run(other, 1.0)
        assertEquals(cup.honours(), other.honours())
    }

    @Test
    fun theChampionsLeaguePlaysGroupsThenAFixedBracket() {
        val cl = assertNotNull(ChampionsLeague.create(start(20)))
        assertEquals((1..16).map(::key), cl.entries.map { it.key })
        for (g in cl.groups) assertEquals(listOf(0, 1, 2, 3), g.map { (it.seed - 1) / 4 }, "one AI per pot")
        assertEquals(55, cl.fixturesTotal)
        assertEquals(4, cl.opening().count { it.kind == "draw_cl" && it.keys.size == 4 })
        assertNull(cl.next(0.0, emptySet()))
        val day1 = generateSequence { cl.next(0.05, emptySet()) }.toList()
        assertEquals(8, day1.size)
        assertEquals(16, day1.flatMap { it.players }.toSet().size)
        assertTrue(day1.all { it.kind == JobKind.MATCH && it.label.endsWith("1차전") })
        for (f in day1) cl.record(play(f))
        val events = run(cl, 0.6)
        assertEquals(7, cl.fixturesLeft, "groups over, the knockout waits for 70%")
        assertEquals(8, events.count { it.kind == "qualified" })
        val view = cl.view() as CompetitionView.Groups
        val winners = view.groups.map { it.rows[0].key }
        val seconds = view.groups.map { it.rows[1].key }
        assertEquals(cl.groups.map { it[0].key }, winners)
        assertTrue(view.groups.all { g -> g.rows.all { it.played == 6 } && g.rows[0].points == 18 })
        val qf = view.knockout!!.rounds[0].ties
        assertEquals(listOf(winners[0] to seconds[1], winners[1] to seconds[0], winners[2] to seconds[3], winners[3] to seconds[2]), qf.map { it.a to it.b })
        val f = assertNotNull(cl.next(0.7, emptySet()))
        assertEquals(JobKind.TIE, f.kind)
        assertEquals(2, f.legs)
        assertEquals("8강", f.label)
        cl.release(f)
        val seen = ArrayList<Fixture>()
        val ko = run(cl, 1.0) { seen += it; play(it) }
        assertEquals(listOf(2, 2, 2, 2, 2, 2, 1), seen.map { it.legs })
        assertTrue(cl.finished)
        assertEquals(Honour(1, "CL", "ai01"), cl.honours()[0])
        val half = if (winners.indexOf("ai01") < 2) winners.drop(2) else winners.take(2)
        assertEquals(half.minByOrNull { index(it) }, cl.honours()[1].key)
        assertTrue(ko.any { it.kind == "final" } && ko.any { it.kind == "title" && it.keys == listOf("ai01") })
    }

    @Test
    fun laterChampionsLeaguesComeFromTheTablesAndTheCup() {
        val d1 = (1..20).map(::key)
        val d2 = (21..40).map(::key)
        // The cup winner is in already: its place passes down the 1st division (13th).
        val honours = listOf(Honour(1, "CUP", "ai05"), Honour(1, "CUP2", "ai30"))
        val s = start(40, number = 2, lastTables = listOf(d1, d2), lastHonours = honours)
        assertEquals((1..12).map(::key) + listOf("ai21", "ai22", "ai30", "ai13"), ChampionsLeague.entrants(s))
        // An AI that left the league leaves its place too.
        val keys = (1..40).map(::key) - "ai03"
        val gone = SeasonStart(2, 1L, LeagueConfig(), keys, ::rating, listOf(keys), listOf(d1, d2), honours, ::family, { "서울" })
        assertEquals((1..13).map(::key) - "ai03" + listOf("ai21", "ai22", "ai30", "ai14"), ChampionsLeague.entrants(gone))
    }

    @Test
    fun theSuperCupNeedsLastSeasonsChampionAndCupWinner() {
        assertNull(SuperCup.create(start(10)), "not in the first season")
        val double = listOf(Honour(1, "L0", "ai03"), Honour(1, "CUP", "ai03"), Honour(1, "CUP2", "ai07"))
        val sc = assertNotNull(SuperCup.create(start(10, number = 2, lastHonours = double)))
        val f = assertNotNull(sc.next(0.0, emptySet()), "played at once")
        assertEquals("ai03" to "ai07", f.a to f.b)
        assertEquals(JobKind.TIE, f.kind)
        // Still level after the deciders: the champion (side A) wins it.
        sc.record(play(f, level = true))
        assertEquals(listOf(Honour(2, "SUPER", "ai03")), sc.honours())
        val other = assertNotNull(SuperCup.create(start(10, number = 2, lastHonours = listOf(Honour(1, "L0", "ai03"), Honour(1, "CUP", "ai09")))))
        assertEquals(listOf("ai03", "ai09"), other.entries.map { it.key })
        assertNull(SuperCup.create(start(10, number = 2, lastHonours = listOf(Honour(1, "L0", "ai03")))))
        assertNull(SuperCup.create(start(10, number = 2, lastHonours = double, config = LeagueConfig(superCup = false))))
    }

    /** A league stage of 24 AIs in 2 divisions played out by strength. */
    private fun playedLeague(config: LeagueConfig = LeagueConfig(divisions = 2)): SeasonPlan {
        val ctx = Offseason.Context((1..24).map(::key), ::rating, ::family, { "서울" }, config)
        val plan = Offseason.first(3L, ctx)
        while (true) plan.record(play(plan.next(emptySet(), 0, 1) ?: break))
        assertTrue(plan.league.finished)
        return plan
    }

    @Test
    fun theTitlePlayoffCrownsTheChampion() {
        val plan = playedLeague()
        plan.startPost(listOf(TitlePlayoff::create))
        val po = plan.post.single()
        assertEquals(3, po.fixturesTotal)
        val semis = listOf(po.next(1.0, emptySet())!!, po.next(1.0, emptySet())!!)
        assertEquals(listOf("ai01" to "ai04", "ai02" to "ai03"), semis.map { it.a to it.b })
        assertTrue(semis.all { it.legs == 2 && it.kind == JobKind.TIE })
        assertNull(po.next(1.0, emptySet()), "the final waits for both semis")
        // The 4th place knocks out the leader; the final is one match.
        for (f in semis) po.record(play(f, flip = f.a == "ai01"))
        val final = po.next(1.0, emptySet())!!
        assertEquals("ai02" to "ai04", final.a to final.b)
        assertEquals(1, final.legs)
        po.record(play(final))
        assertTrue(plan.finished)
        assertEquals(listOf(Honour(1, "L0", "ai02"), Honour(1, "L0R", "ai04")), po.honours())
        val honours = Offseason.honours(plan, Offseason.moves(plan))
        assertEquals("ai02", honours.single { it.kind == "L0" }.key)
        assertEquals("ai01", honours.single { it.kind == "REG" }.key)
        assertNull(TitlePlayoff.create(playedLeague(LeagueConfig(divisions = 2, titlePlayoff = false))))
    }

    @Test
    fun thePromotionPlayoffFeedsTheMoves() {
        for (lowerWins in listOf(true, false)) {
            val plan = playedLeague()
            plan.startPost(listOf(PromotionPlayoff::create))
            val po = plan.post.single() as PromotionPlayoff
            // 12 a side, 2 swap automatically: the 10th of the top plays the 3rd of the second.
            assertEquals(listOf(PromotionPlayoff.Spot(0, "ai10", "ai15")), po.spots)
            val f = po.next(1.0, emptySet())!!
            assertEquals("ai10" to "ai15", f.a to f.b)
            assertEquals(2, f.legs)
            assertEquals("1·2부 승강전", f.label)
            val news = po.record(play(f, flip = lowerWins))
            assertTrue(plan.finished)
            val moves = Offseason.moves(plan)
            if (lowerWins) {
                assertEquals(listOf(Honour(1, "UP0", "ai15")), po.honours())
                assertTrue(Offseason.Move("ai15", 1, 0) in moves && Offseason.Move("ai10", 0, 1) in moves)
                assertTrue(news.any { it.kind == "promoted" && it.keys == listOf("ai15") })
                val (next, _) = Offseason.next(plan, Offseason.Context((1..24).map(::key), ::rating, ::family, { "서울" }, LeagueConfig(divisions = 2)))
                assertTrue("ai15" in next.league.divisions[0] && "ai10" in next.league.divisions[1])
            } else {
                assertEquals(listOf(Honour(1, "STAY0", "ai10")), po.honours())
                assertEquals(4, moves.size, "only the automatic moves")
            }
        }
        assertNull(PromotionPlayoff.create(playedLeague(LeagueConfig(divisions = 2, promotionPlayoff = false))))
    }

    @Test
    fun theAllStarGameIsAFriendlyTeamMatchAtHalfway() {
        val s = start(12)
        val game = assertNotNull(AllStar.create(s))
        assertEquals((1..5).map(::key), game.top)
        assertEquals((7..11).map(::key), game.challenger)
        assertNull(game.next(0.49, emptySet()))
        val f = assertNotNull(game.next(0.5, emptySet()))
        assertEquals(JobKind.TEAM, f.kind)
        assertEquals(false, f.rated)
        assertEquals(AllStar.TOP to AllStar.CHALLENGER, f.a to f.b)
        assertEquals(10, f.players.size)
        val news = game.record(play(f))
        assertEquals(listOf(Honour(1, "ALLSTAR", AllStar.TOP)), game.honours())
        assertEquals(listOf(AllStar.TOP, AllStar.CHALLENGER), news.single { it.kind == "final" }.keys)
        val view = game.view() as CompetitionView.Bracket
        assertEquals(5.0, view.rounds[0].ties[0].scoreA)
        assertEquals(game.top, view.squads[AllStar.TOP])
        assertEquals("도전자 올스타", Competitions.teamLabel(AllStar.CHALLENGER))
        // Not without 5 AIs on each side.
        assertNull(AllStar.create(start(9)))
        assertNull(AllStar.create(start(12, divisions = 1)))
    }

    @Test
    fun theFamilyCupPlaysGroupsSemisAndAFinal() {
        val s = start(45, number = 2)
        val cup = assertNotNull(FamilyCup.create(s))
        assertEquals(9, cup.teams.size)
        assertTrue(cup.teams.all { it.squad.size == 5 })
        assertEquals(listOf(3, 3, 3), cup.groups.map { it.size })
        for (g in cup.groups) assertEquals(listOf(0, 1, 2), g.map { (it.seed - 1) / 3 }, "one team per pot")
        assertEquals(9 + 3, cup.fixturesTotal)
        assertEquals(3, cup.opening().count { it.kind == "draw_family" })
        assertTrue(cup.opening().none { it.kind == "callup" }, "a first edition has nobody to compare with")
        assertNull(cup.next(0.09, emptySet()))
        val f = assertNotNull(cup.next(0.1, emptySet()))
        assertEquals(JobKind.TEAM, f.kind)
        assertEquals(5, f.lineupA.size)
        assertEquals(cup.teams.first { it.id == f.a }.squad, f.lineupA)
        cup.release(f)
        run(cup, 0.6)
        assertEquals(3, cup.fixturesLeft)
        assertNull(cup.next(0.7, emptySet()), "semis at 75%")
        val semis = listOf(cup.next(0.75, emptySet())!!, cup.next(0.75, emptySet())!!)
        // A level semi goes to the better seed (side A).
        for (x in semis) cup.record(play(x, level = true))
        val final = assertNotNull(cup.next(0.9, emptySet()))
        assertEquals(setOf(semis[0].a, semis[1].a), setOf(final.a, final.b))
        val news = cup.record(play(final))
        assertTrue(cup.finished)
        val honours = cup.honours()
        val winner = honours.single { it.kind == "FAMILY" }.key
        assertTrue(news.any { it.kind == "title" && it.keys == listOf(winner) })
        // The strongest team won its group and every match it played.
        assertEquals(cup.teams[0].id, winner)
        assertEquals(45, honours.count { it.kind == "FSQ" })
        val view = cup.view() as CompetitionView.Groups
        assertEquals(3, view.groups.size)
        assertEquals(listOf("4강", "결승"), view.knockout!!.rounds.map { it.label })
        assertEquals(9, view.squads.size)
    }

    @Test
    fun theFamilyCupAnnouncesCallupsAndDropsAgainstTheLastSquads() {
        // Last edition (season 2): the EV family had ai48 where it now has ai03, and RULE had no team.
        val now = start(54, number = 5)
        val teams = FamilyCup.teams(now)
        val ev = teams.single { it.id == Family.EV.name }
        assertEquals(listOf("ai03", "ai12", "ai21", "ai30", "ai39"), ev.squad)
        val lastEv = listOf("ai12", "ai21", "ai30", "ai39", "ai48")
        val history = lastEv.map { Honour(2, "FSQ", it) } +
            teams.filter { it.id != Family.EV.name && it.id != Family.RULE.name }.flatMap { t -> t.squad.map { Honour(2, "FSQ", it) } } +
            Honour(1, "FSQ", "ai01")
        val cup = assertNotNull(FamilyCup.create(start(54, number = 5, history = history)))
        val news = cup.opening().filter { it.kind == "callup" || it.kind == "dropped" }
        assertEquals(
            listOf(
                LeagueEvent("callup", 5, listOf("ai03"), listOf(Family.EV.label), Comp.FAMILY),
                LeagueEvent("dropped", 5, listOf("ai48"), listOf(Family.EV.label), Comp.FAMILY),
            ),
            news,
        )
    }

    @Test
    fun familyCupsOfFourToTwelveTeamsAlwaysMakeFourSemiFinalists() {
        assertEquals(listOf(1, 1, 2, 2, 2, 3, 3, 3, 3), (4..12).map { FamilyCup.groupCount(it) })
        for (n in 4..12) {
            val groups = FamilyCup.draw((1..n).map { DrawEntry("t$it", it, 1500.0 - it) }, Random(n.toLong()))
            assertEquals(n, groups.sumOf { it.size })
            assertTrue(groups.all { it.size in 3..5 }, "$n: ${groups.map { it.size }}")
        }
        // Four families of 3 AIs: one group of 4, everyone in the semis.
        val four = Family.entries.filter { it != Family.MINE }.take(4)
        val s = start(12, number = 2, family = { k -> four[(index(k) - 1) % 4] })
        val cup = assertNotNull(FamilyCup.create(s))
        assertEquals(listOf(4), cup.groups.map { it.size })
        assertEquals(6 + 3, cup.fixturesTotal)
        val f = cup.next(0.1, emptySet())!!
        assertEquals(3, f.lineupA.size)
        cup.release(f)
        run(cup, 1.0)
        assertTrue(cup.finished)
        // Three families: no cup.
        assertNull(FamilyCup.create(start(12, number = 2, family = { k -> four[(index(k) - 1) % 3] })))
    }

    @Test
    fun factoriesReturnNullWhenOffOrNotDue() {
        val off = LeagueConfig(cup = false, champions = false, superCup = false, allStar = false, familyCup = false)
        val last = listOf(Honour(1, "L0", "ai01"), Honour(1, "CUP", "ai02"))
        val s = start(45, number = 2, config = off, lastHonours = last)
        assertTrue(Competitions.factories().all { it(s) == null })
        val on = start(45, number = 2, lastHonours = last)
        assertEquals(listOf(Comp.CHAMPIONS, Comp.CUP, Comp.SUPER, Comp.ALL_STAR, Comp.FAMILY), Competitions.factories().mapNotNull { it(on)?.comp })
        assertNull(Cup.create(start(3)))
        assertNull(ChampionsLeague.create(start(15)))
        assertEquals(listOf(2, 5, 8), (1..9).filter { FamilyCup.due(it) })
        assertNull(FamilyCup.create(start(45, number = 3)))
        assertNotNull(FamilyCup.create(start(45, number = 5)))
        assertTrue(Competitions.codecs.keys.containsAll(listOf(Comp.CUP, Comp.CHAMPIONS, Comp.SUPER, Comp.ALL_STAR, Comp.FAMILY, Comp.TITLE_PLAYOFF, Comp.PROMOTION_PLAYOFF)))
    }

    @Test
    fun twoSeasonsWithEveryCompetitionSurviveASaveMidway() {
        val keys = (1..40).map(::key)
        val ctx = Offseason.Context(keys, ::rating, ::family, { "서울" }, LeagueConfig(divisions = 2), Competitions.factories())
        val first = Offseason.first(7L, ctx)
        assertEquals(listOf(Comp.CHAMPIONS, Comp.CUP, Comp.ALL_STAR), first.comps.map { it.comp })
        drive(first)
        assertEquals(listOf(Comp.TITLE_PLAYOFF, Comp.PROMOTION_PLAYOFF), first.post.map { it.comp })
        val (plan, last) = Offseason.next(first, ctx)
        for ((kind, k) in listOf("L0" to "ai01", "CUP" to "ai01", "CUP2" to "ai02", "CL" to "ai01", "ALLSTAR" to AllStar.TOP)) {
            assertEquals(k, last.single { it.kind == kind }.key, kind)
        }
        assertEquals(listOf(Comp.CHAMPIONS, Comp.CUP, Comp.SUPER, Comp.ALL_STAR, Comp.FAMILY), plan.comps.map { it.comp })
        assertEquals(listOf("ai01", "ai02"), (plan.competition(Comp.SUPER) as SuperCup).entries.map { it.key })
        // Season 2's CL: the 1st division's top 12, the 2nd's top 2, the cup's places passed down.
        assertEquals((1..14).map(::key) + listOf("ai21", "ai22"), (plan.competition(Comp.CHAMPIONS) as ChampionsLeague).entries.map { it.key }.sortedBy { index(it) })

        // Half the league, then a save with a fixture in progress.
        while (plan.progress < 0.5) plan.record(play(plan.next(emptySet(), 0, 1)!!))
        val inFlight = assertNotNull(plan.next(emptySet(), 0, 1))
        val back = SeasonPlan.decode(plan.encode(), keys, Competitions.codecs)
        assertEquals(plan.comps.map { it.comp }, back.comps.map { it.comp })
        for ((a, b) in plan.comps.zip(back.comps)) {
            assertEquals(a.fixturesLeft, b.fixturesLeft, a.comp.label)
            assertEquals(a.fixturesTotal, b.fixturesTotal, a.comp.label)
            assertEquals(text(a), text(b), a.comp.label)
        }
        // Both go on the same way once the stopped fixture is released.
        plan.release(inFlight)
        val seenA = ArrayList<Fixture>()
        val seenB = ArrayList<Fixture>()
        drive(plan, seenA)
        drive(back, seenB)
        assertEquals(inFlight, seenB.first { it.comp == inFlight.comp && it.id == inFlight.id })
        assertEquals(seenA, seenB)
        assertEquals(Offseason.honours(plan, Offseason.moves(plan)), Offseason.honours(back, Offseason.moves(back)))
        val honours = Offseason.honours(plan, Offseason.moves(plan))
        assertEquals("ai01", honours.single { it.kind == "SUPER" }.key)
        assertEquals(Family.WIN.name, honours.single { it.kind == "FAMILY" }.key)
        // Every competition has a view for the screen.
        val views = Competitions.views(plan)
        assertEquals(plan.comps.size + plan.post.size, views.size)
        assertTrue(views.single { it.first == Comp.CHAMPIONS }.second is CompetitionView.Groups)
    }

    @Test
    fun savedSectionsSkipBadLinesAndStaleResults() {
        val cup = assertNotNull(Cup.create(start(13)))
        val f = cup.next(1.0, emptySet())!!
        cup.record(play(f))
        // A stale result (other sides) or a second one for the same tie changes nothing.
        assertTrue(cup.record(play(f.copy(b = "ai99"))).isEmpty())
        val left = cup.fixturesLeft
        cup.record(play(f, flip = true))
        assertEquals(left, cup.fixturesLeft)
        // A removed AI's tie is forfeited by the app: the side still here goes through.
        val g = cup.next(1.0, emptySet())!!
        cup.record(JobResult(g.copy(rated = false), listOf(BoardResult(g.a, g.b, listOf(GameScore(0, 1)), emptyList())), -1, 0))
        assertEquals(g.b, (cup.view() as CompetitionView.Bracket).rounds.flatMap { it.ties }.single { it.a == g.a && it.b == g.b }.winner)
        // Junk and cut lines are skipped; the lost result is simply played again.
        val saved = lines(cup)
        val damaged = saved.dropLast(1) + "r 3 x" + "zz top" + ""
        val back = Competitions.codecs.getValue(Comp.CUP).decode(damaged, 1, 99L + 1)
        assertEquals(cup.fixturesLeft + 1, back.fixturesLeft)
        assertFailsWith<IllegalArgumentException> { Competitions.codecs.getValue(Comp.CUP).decode(listOf("cup 1"), 1, 0L) }
        assertFailsWith<IllegalArgumentException> { Competitions.codecs.getValue(Comp.CHAMPIONS).decode(listOf("nonsense"), 1, 0L) }
        // Every codec reads back what its competition wrote.
        val s = start(45, number = 2, lastHonours = listOf(Honour(1, "L0", "ai01"), Honour(1, "CUP", "ai02")))
        for (c in Competitions.factories().mapNotNull { it(s) }) {
            run(c, 0.3)
            val again = Competitions.codecs.getValue(c.comp).decode(lines(c), 2, s.seed)
            assertEquals(text(c), text(again), c.comp.label)
            assertEquals(c.next(1.0, emptySet()), again.next(1.0, emptySet()), c.comp.label)
        }
    }
}

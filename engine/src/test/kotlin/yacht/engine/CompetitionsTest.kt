package yacht.engine

import yacht.engine.league.AllStar
import yacht.engine.league.BoardResult
import yacht.engine.league.ChampionsLeague
import yacht.engine.league.Comp
import yacht.engine.league.Competition
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.ContinentalChampions
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
import yacht.engine.league.KnockoutLength
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.MatchFormat
import yacht.engine.league.Offseason
import yacht.engine.league.PromotionPlayoff
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.SuperCup
import yacht.engine.league.TieRule
import yacht.engine.league.TieView
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

    /** The rules before series and double elimination (the bracket tests below count their ties). */
    private val legacy = LeagueConfig(matchFormat = MatchFormat.MIRRORED, seriesKnockouts = false, doubleElimination = false)

    private fun start(
        n: Int,
        number: Int = 1,
        divisions: Int = 2,
        config: LeagueConfig = legacy,
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
        // One match a tie (긴 토너먼트 off); the long rounds have their own test.
        val cup = assertNotNull(Cup.create(start(13, config = legacy.copy(longKnockouts = false))))
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
    fun knockoutsGrowLongerTowardTheFinal() {
        fun legs(c: Competition) = ((c as yacht.engine.league.ViewableCompetition).view() as CompetitionView.Bracket).rounds.map { r -> r.label to r.ties.first().legs }
        // 40 players: 64강 and 32강 one match, 16강 and 8강 two, 4강 three, 결승 four.
        val cup = assertNotNull(Cup.create(start(40)))
        assertEquals(listOf("64강" to 1, "32강" to 1, "16강" to 2, "8강" to 2, "4강" to 3, "결승" to 4), legs(cup))
        val events = run(cup, 1.0)
        assertTrue(events.any { it.kind == "final" && it.args == listOf("8-0") }, events.filter { it.kind == "final" }.toString())
        // Saved long, read back long; a cup saved before (no line) keeps one match a round.
        assertTrue(KnockoutLength.LINE in lines(cup))
        assertEquals(legs(cup), legs(Cup.codec.decode(lines(cup), 1, 100L)))
        assertEquals(List(6) { 1 }, legs(Cup.codec.decode(lines(cup).filter { it != KnockoutLength.LINE }, 1, 100L)).map { it.second })
        // Off: one match a tie everywhere.
        val short = assertNotNull(Cup.create(start(40, config = legacy.copy(longKnockouts = false))))
        assertEquals(List(6) { 1 }, legs(short).map { it.second })
        assertTrue(KnockoutLength.LINE !in lines(short))
        // The super cup: two matches; the champions league's bracket before the draw: 2, 3, 4.
        val double = listOf(Honour(1, "L0", "ai01"), Honour(1, "CUP", "ai02"))
        assertEquals(listOf(2), legs(assertNotNull(SuperCup.create(start(10, number = 2, lastHonours = double)))).map { it.second })
        val cl = assertNotNull(ChampionsLeague.create(start(20)))
        val ko = (cl.view() as CompetitionView.Groups).knockout!!.rounds.map { it.ties.first().legs }
        assertEquals(listOf(2, 3, 4), ko)
        val clShort = assertNotNull(ChampionsLeague.create(start(20, config = legacy.copy(longKnockouts = false))))
        assertEquals(listOf(2, 2, 1), (clShort.view() as CompetitionView.Groups).knockout!!.rounds.map { it.ties.first().legs })
        // The rule itself: early rounds keep what they had.
        assertEquals(listOf(1, 1, 2, 2, 3, 4), listOf(128, 32, 16, 8, 4, 2).map { KnockoutLength.legs(it, 1, true) })
        assertEquals(listOf(2, 2, 2, 3, 4), listOf(64, 32, 16, 4, 2).map { KnockoutLength.legs(it, 2, true) })
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
        // 긴 토너먼트: quarter-finals 2 matches, semis 3, the final 4.
        assertEquals(listOf(2, 2, 2, 2, 3, 3, 4), seen.map { it.legs })
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

    /** A league stage of [n] AIs (24 in 2 divisions) played out by strength. */
    private fun playedLeague(config: LeagueConfig = legacy.copy(divisions = 2), n: Int = 24): SeasonPlan {
        val ctx = Offseason.Context((1..n).map(::key), ::rating, ::family, { "서울" }, config)
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
        assertTrue(semis.all { it.legs == 3 && it.kind == JobKind.TIE })
        assertNull(po.next(1.0, emptySet()), "the final waits for both semis")
        // The 4th place knocks out the leader; the final is four matches.
        for (f in semis) po.record(play(f, flip = f.a == "ai01"))
        val final = po.next(1.0, emptySet())!!
        assertEquals("ai02" to "ai04", final.a to final.b)
        assertEquals(4, final.legs)
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
                val (next, _) = Offseason.next(plan, Offseason.Context((1..24).map(::key), ::rating, ::family, { "서울" }, legacy.copy(divisions = 2)))
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
        // The international stage reads back too; the 대륙 챔피언스 never runs in a legacy season.
        assertTrue(Competitions.codecs.keys.containsAll(listOf(Comp.CONT_CHAMPIONS, Comp.CLUB_CUP)))
        assertNull(ContinentalChampions.create(on))
        assertEquals(listOf('T', 'U'), listOf(Comp.CONT_CHAMPIONS.code, Comp.CLUB_CUP.code))
        assertEquals(Comp.entries.size, Comp.entries.map { it.code }.toSet().size)
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

    @Test
    fun aMiddleDivisionOfThreeNeverSendsAnAiUpAndDownAtOnce() {
        // 3 divisions of 3, one automatic move per boundary: the middle division's 2nd is the
        // lower side above and the upper side below. It plays only for promotion.
        val config = LeagueConfig(divisions = 3)
        val plan = playedLeague(config, n = 9)
        plan.startPost(listOf(PromotionPlayoff::create))
        val po = plan.post.single() as PromotionPlayoff
        assertEquals(listOf(PromotionPlayoff.Spot(0, "ai02", "ai05")), po.spots)
        // Every lower side wins: ai05 goes up, and no second tie could send it down as well.
        run(po, 1.0) { play(it, flip = true) }
        assertTrue(plan.finished)
        assertEquals(listOf(Honour(1, "UP0", "ai05")), po.honours())
        val (next, _) = Offseason.next(plan, Offseason.Context((1..9).map(::key), ::rating, ::family, { "서울" }, config))
        val all = next.league.divisions.flatten()
        assertEquals(all.size, all.toSet().size, "an AI in two divisions: ${next.league.divisions}")
        assertTrue("ai05" in next.league.divisions[0] && "ai02" in next.league.divisions[1])
    }

    @Test
    fun aResultOfAnotherSeasonIsIgnored() {
        // The same ratings make the same cup draw every season; only the deal tells them apart.
        val one = assertNotNull(Cup.create(start(13, number = 1)))
        val two = assertNotNull(Cup.create(start(13, number = 2)))
        val old = assertNotNull(one.next(0.05, emptySet()))
        val now = assertNotNull(two.next(0.05, emptySet()))
        assertEquals(old.copy(seed = now.seed), now)
        assertTrue(two.record(play(old, flip = true)).isEmpty())
        assertEquals(12, two.fixturesLeft)
        two.record(play(now))
        assertEquals(11, two.fixturesLeft)
        assertEquals(now.a, (two.view() as CompetitionView.Bracket).rounds[0].ties.single { it.a == now.a }.winner)
        // A team competition too: last season's all-star game does not count this season.
        val stars = assertNotNull(AllStar.create(start(12, number = 2)))
        val lastYear = assertNotNull(AllStar.create(start(12, number = 1))?.next(0.5, emptySet()))
        assertTrue(stars.record(play(lastYear)).isEmpty())
        assertTrue(!stars.finished && stars.honours().isEmpty())
    }

    @Test
    fun aByeShowsOnlyItsSide() {
        val e = (1..3).map { DrawEntry(key(it), it, rating(key(it))) }
        // A draw read back with a seed missing: the top side of a tie is empty.
        val k = Knockout(listOf(null to e[1], e[0] to e[2]), { 1 }, { "r$it" }, { 0.0 })
        assertEquals(TieView("ai02", null, 0.0, 0.0, "ai02", false, 1, bye = true), k.view()[0].ties[0])
        // The final waits for the other tie: its known side shows, the other is not known yet.
        assertEquals(TieView("ai02", null, 0.0, 0.0, null, false, 1), k.view()[1].ties[0])
    }

    /** A result from the fixture's deal: the same fixture always ends the same way, whenever it is played. */
    private fun dealt(f: Fixture, gone: Set<String>): JobResult {
        if (f.players.any { it in gone }) {
            // As LeagueController.forfeit: the side still here wins, off the ladder.
            val aHere = f.players.take(maxOf(1, f.lineupA.size)).none { it in gone }
            val games = listOf(if (aHere) GameScore(1, 0) else GameScore(0, 1))
            val boards = if (f.kind == JobKind.TEAM) f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games, emptyList()) }
            else listOf(BoardResult(f.a, f.b, games, emptyList()))
            return JobResult(f.copy(rated = false), boards, if (aHere) 1 else -1, 0)
        }
        val rng = Random(f.seed)
        fun games(n: Int) = List(n) { GameScore(150 + rng.nextInt(60), 150 + rng.nextInt(60)) }
        return when (f.kind) {
            JobKind.MATCH -> games(2).let { JobResult(f, listOf(BoardResult(f.a, f.b, it, emptyList())), TieRule.match(it), 0) }
            JobKind.TIE -> {
                val g = games(2 * f.legs).toMutableList()
                while (g.size < 2 * f.legs + 3 && TieRule.match(g) == 0) g += games(1)
                JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0)
            }
            JobKind.TEAM -> {
                val boards = f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games(2), emptyList()) }
                JobResult(f, boards, TieRule.team(boards), 0)
            }
        }
    }

    /**
     * [count] seasons of [n] AIs with up to [threads] jobs in flight that finish in a random
     * order ([order] seeds it), the rules changed for season 3, and with [reload] the plan saved
     * and read back now and then (the jobs in flight lost, as after a kill); with [leave] ai05
     * leaves in the middle of season 2 (its fixtures forfeited). Checks that the season never
     * waits for nothing, that no AI is handed out twice and none plays in two divisions.
     * Returns every season's honours.
     */
    private fun seasons(n: Int, config: LeagueConfig, count: Int, threads: Int, order: Long, reload: Boolean, leave: Boolean = false): List<Honour> {
        val rng = Random(order)
        var keys = (1..n).map(::key)
        val gone = HashSet<String>()
        val history = ArrayList<Honour>()
        var rules = config
        fun ctx() = Offseason.Context(keys, ::rating, ::family, { "서울" }, rules, Competitions.factories(), history.toList())
        var plan = Offseason.first(11L, ctx())
        var season = 1
        val flight = ArrayList<Fixture>()
        while (true) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            if (plan.finished) {
                if (season == count) return history + Offseason.honours(plan, Offseason.moves(plan))
                keys = keys - gone
                rules = if (season == 2) config.copy(divisions = maxOf(1, config.divisions - 1), champions = !config.champions, titlePlayoff = !config.titlePlayoff, autoSwap = 0) else config
                val (next, h) = Offseason.next(plan, ctx())
                history += h
                val all = next.league.divisions.flatten()
                assertEquals(all.size, all.toSet().size, "an AI in two divisions after season $season")
                plan = next
                season++
                continue
            }
            if (leave && season == 2 && plan.progress > 0.3) gone += key(5)
            val busy = flight.flatMap { it.players }.toSet()
            val cups = flight.count { it.comp != Comp.LEAGUE && it.comp != Comp.ALL_STAR && it.comp != Comp.FAMILY }
            val f = if (flight.size < threads) plan.next(busy, cups, maxOf(1, threads - 1)) else null
            if (f != null) {
                assertTrue(f.players.none { it in busy }, "a busy AI handed out: $f")
                flight += f
                continue
            }
            assertTrue(flight.isNotEmpty(), "season $season waits for nothing at ${plan.progress}: ${(plan.comps + plan.post).map { it.comp to it.fixturesLeft }}")
            plan.record(dealt(flight.removeAt(rng.nextInt(flight.size)), gone))
            if (rng.nextInt(40) == 0) Competitions.views(plan)
            if (reload && rng.nextInt(60) == 0) {
                flight.clear()
                plan = SeasonPlan.decode(plan.encode(), keys, Competitions.codecs)
            }
        }
    }

    @Test
    fun leaguesOfEverySizeEndTheSameWayHoweverTheirFixturesAreScheduled() {
        for ((n, config) in listOf(
            9 to LeagueConfig(divisions = 3),
            16 to LeagueConfig(divisions = 4),
            18 to LeagueConfig(divisions = 6, autoSwap = 1),
            45 to LeagueConfig(divisions = 2),
            5 to LeagueConfig(divisions = 1),
        )) {
            val calm = seasons(n, config, 5, threads = 1, order = 1L, reload = false)
            assertEquals(calm, seasons(n, config, 5, threads = 6, order = 2L, reload = true), "$n AIs")
            if (n == 45) {
                // Seasons 2 and 5 hold a family cup; season 3 runs without the champions league.
                assertEquals(listOf(2, 5), calm.filter { it.kind == "FAMILY" }.map { it.season })
                assertEquals(listOf(1, 2, 4, 5), calm.filter { it.kind == "CL" }.map { it.season })
            }
            seasons(n, config, 3, threads = 4, order = 3L, reload = true, leave = true)
        }
    }

    // ---- after a national season ----------------------------------------------------------------

    private val ten = listOf(10, 10)

    /** Ten leagues (Asia 4, Europe 2, the Americas 2, Africa 2) that played last season as they play now. */
    private val nations = NationalStart(listOf("KR", "GB", "US", "JP", "FR", "CN", "BR", "VN", "NG", "ZA").map { it to ten })

    /** Last season's honours: the CL, the 대륙 챔피언스, each league's champion (KR's after its title playoff). */
    private val worldHonours = listOf(
        Honour(1, "CL", "us0x05"),
        Honour(1, "CCL.ASIA", "kr0x00"), Honour(1, "CCL.EUROPE", "gb0x00"), Honour(1, "CCL.AMERICAS", "us0x00"), Honour(1, "CCL.AFRICA", "ng0x00"),
        Honour(1, "CCL2.ASIA", "jp0x00"), Honour(1, "CCL2.EUROPE", "fr0x00"), Honour(1, "CCL2.AMERICAS", "br0x00"), Honour(1, "CCL2.AFRICA", "za0x02"),
        Honour(1, "L0.KR", "kr0x03"), Honour(1, "L0R.KR", "kr0x00"),
    ) + listOf("GB", "US", "JP", "FR", "CN", "BR", "VN", "NG", "ZA").map { Honour(1, "L0.$it", "${it.lowercase()}0x00") }

    private fun worldStart(clDirect: Int = 7, honours: List<Honour> = worldHonours, world: List<String> = nations.keys) = nations.start(
        config = LeagueConfig(national = true, clDirect = clDirect), lastTables = nations.divisions, lastLeagues = nations.leagues, lastHonours = honours,
        ranking = nations.ranking(listOf("KR", "GB", "US", "JP", "FR", "CN", "BR", "VN", "NG", "ZA")), world = world,
    )

    @Test
    fun theChampionsLeagueIsTheWorldStageAfterANationalSeason() {
        // The holder, the continental champions and runners-up, the champions of the best-ranked
        // leagues (a champion already in passes its place down), then the 1부 runners-up.
        val holder = listOf("us0x05")
        val continental = listOf("kr0x00", "gb0x00", "us0x00", "ng0x00", "jp0x00", "fr0x00", "br0x00", "za0x02")
        val champions = listOf("kr0x03", "cn0x00", "vn0x00", "za0x00")
        assertEquals(holder + continental + champions + listOf("gb0x01", "us0x01", "jp0x01"), ChampionsLeague.entrants(worldStart()))
        // One more direct place: the last continental runner-up makes room for a league's runner-up later on.
        assertEquals(holder + continental.dropLast(1) + champions + listOf("gb0x01", "us0x01", "jp0x01", "fr0x01"), ChampionsLeague.entrants(worldStart(clDirect = 8)))
        // No direct places: the runners-up of the best-ranked leagues take them (KR's is in already).
        assertEquals(holder + continental + listOf("gb0x01", "us0x01", "jp0x01", "fr0x01", "cn0x01", "br0x01", "vn0x01"), ChampionsLeague.entrants(worldStart(clDirect = 0)))
        // A holder who left the world leaves its place too.
        assertEquals(continental + champions + listOf("gb0x01", "us0x01", "jp0x01", "fr0x01"), ChampionsLeague.entrants(worldStart(world = nations.keys - "us0x05")))
        val cl = assertNotNull(ChampionsLeague.create(worldStart()))
        assertEquals(ChampionsLeague.entrants(worldStart()), cl.entries.map { it.key })
    }

    @Test
    fun withoutContinentalChampionsEachLeagueAddsItsNextPlayerInTurn() {
        val regions = NationalStart(listOf("XEU", "XAS", "XAM", "XAF").map { it to ten })
        val honours = listOf(Honour(1, "CL", "xeu0x04"), Honour(1, "L0.XAM", "xam0x02")) +
            listOf("XEU", "XAS", "XAF").map { Honour(1, "L0.$it", "${it.lowercase()}0x00") }
        val start = regions.start(lastTables = regions.divisions, lastLeagues = regions.leagues, lastHonours = honours, ranking = regions.ranking(listOf("XEU", "XAS", "XAM", "XAF")))
        // The holder, 4 champions, 4 seconds, 4 thirds and 3 fourths.
        assertEquals(
            listOf("xeu0x04") + listOf("xeu0x00", "xas0x00", "xam0x02", "xaf0x00") + listOf("xeu0x01", "xas0x01", "xam0x00", "xaf0x01") +
                listOf("xeu0x02", "xas0x02", "xam0x01", "xaf0x02") + listOf("xeu0x03", "xas0x03", "xam0x03"),
            ChampionsLeague.entrants(start),
        )
        // The legacy rule is unchanged after a legacy season, and the first season goes by rating.
        assertEquals(regions.keys.sortedByDescending(regions::rating).take(16), ChampionsLeague.entrants(regions.start()))
    }

    @Test
    fun theSuperCupAndTheAllStarsOfANationalWorld() {
        val honours = worldHonours + listOf(Honour(1, "CUP", "jp0x03"), Honour(1, "CUP2", "cn0x00"), Honour(1, "CL2", "gb0x02"))
        // The champions league's winner against the cup's.
        assertEquals(listOf("us0x05", "jp0x03"), assertNotNull(SuperCup.create(worldStart(honours = honours))).entries.map { it.key })
        // The cup's winner is the holder: the cup's runner-up; a holder who left: the champions league's runner-up.
        val double = honours.map { if (it.kind == "CUP") it.copy(key = "us0x05") else it }
        assertEquals(listOf("us0x05", "cn0x00"), assertNotNull(SuperCup.create(worldStart(honours = double))).entries.map { it.key })
        assertEquals(listOf("gb0x02", "jp0x03"), assertNotNull(SuperCup.create(worldStart(honours = honours, world = nations.keys - "us0x05"))).entries.map { it.key })
        // Without a champions league winner there is none (a league's L0.<id> is no champion of the world).
        assertNull(SuperCup.create(worldStart(honours = honours.filter { it.kind != "CL" && it.kind != "CL2" })))

        // 1부 올스타: the best of every league's 1부; 도전자: the best of all the other divisions.
        val start = worldStart()
        val star = assertNotNull(AllStar.create(start))
        val tops = nations.leagues.tops().toSet()
        val first = nations.divisions.filterIndexed { d, _ -> d in tops }.flatten().sortedByDescending(nations::rating).take(AllStar.SIDE)
        val rest = nations.divisions.filterIndexed { d, _ -> d !in tops }.flatten().sortedByDescending(nations::rating).take(AllStar.SIDE)
        assertEquals(first, star.top)
        assertEquals(rest, star.challenger)
        assertTrue(first.any { it.startsWith("gb") } && rest.all { it.contains("1x") })

        // A national world's factories: the champions league first, the 대륙 챔피언스 next.
        assertEquals(listOf(Comp.CHAMPIONS, Comp.CONT_CHAMPIONS, Comp.CUP, Comp.ALL_STAR), Competitions.factories().mapNotNull { it(start)?.comp })
    }
}

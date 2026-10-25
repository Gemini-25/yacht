package yacht.engine

import yacht.engine.explain.Commentary
import yacht.engine.explain.Tone
import yacht.engine.game.DecisionKind
import yacht.engine.game.DecisionRecord
import yacht.engine.game.DiceSource
import yacht.engine.game.GameCodec
import yacht.engine.game.GameState
import yacht.engine.game.PlayStyle
import yacht.engine.game.Side
import yacht.engine.league.CloneProfile
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomAis
import yacht.engine.league.CustomBase
import yacht.engine.league.CustomMistake
import yacht.engine.league.GameScore
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueTables
import yacht.engine.league.Matchmaker
import yacht.engine.league.Season
import yacht.engine.league.WatchMatch
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeagueFeaturesTest {
    private val tables get() = SolverTest.tables

    @Test
    fun aSeasonPlaysOutAndSwapsDivisions() {
        val keys = (1..70).map { "ai$it" }
        val season = Season.start(keys)
        assertEquals(2, season.divisions.size)
        assertEquals(35, season.divisions[0].size)
        val total = season.fixturesTotal
        assertEquals(2 * 35 * 34 / 2, total)
        val rng = Random(1)
        // The stronger (lower number) team wins more often.
        fun strength(k: String) = 100 - k.removePrefix("ai").toInt()
        var played = 0
        var saved: String? = null
        while (true) {
            val f = season.next(rng, emptySet()) ?: break
            val (a, b) = f
            val aWins = rng.nextInt(200) < 100 + strength(a) - strength(b)
            season.record(f, listOf(if (aWins) GameScore(200, 150, aYacht = true) else GameScore(150, 200), GameScore(180, 180)))
            played++
            if (played == 300) saved = season.encode()
        }
        assertTrue(season.finished)
        assertEquals(total, played)
        // Everyone played everyone in its division once.
        assertTrue(season.divisions.flatten().all { season.standings.getValue(it).played == 34 })
        val top = season.table(0)
        assertTrue(top.zipWithNext().all { (x, y) -> x.points >= y.points })
        season.sync(keys + "newbie")
        val summary = season.finish()
        assertEquals(1, summary.number)
        assertEquals(2, summary.champions.size)
        assertEquals(6, summary.moves.size)
        assertEquals(2, season.number)
        assertTrue("newbie" in season.divisions.last())
        assertTrue(summary.moves.filter { it.second == 0 }.all { it.first in season.divisions[1] })
        assertNotNull(summary.topScorer)
        // Saved mid-season: the same table and the fixtures still to play.
        val back = Season.decode(saved!!, keys)
        assertEquals(total - 300, back.fixturesLeft)
        assertEquals(300, back.standings.values.sumOf { it.played } / 2)
        val round = Season.decode(season.encode(), keys + "newbie")
        assertEquals(season.history, round.history)
        assertEquals(season.divisions, round.divisions)
    }

    @Test
    fun customAisSaveAndPlay() {
        val specs = listOf(
            CustomAiSpec("a1", "요트 사랑 감잡이", CustomBase.EV, mistake = CustomMistake.FEEL, amount = 0.5, yacht = 20.0, zero = 5.0, saveChoice = true, future = 0.9),
            CustomAiSpec("a2", "모험 실수", CustomBase.RISK, theta = 0.03, mistake = CustomMistake.SLIP, amount = 0.05),
            CustomAiSpec("a3", "계산 + 감", CustomBase.WIN, mistake = CustomMistake.FEEL, amount = 1.0),
        )
        for (s in specs) assertEquals(s, CustomAiSpec.decode(s.encode()))
        assertEquals(CustomMistake.SLIP, specs[2].effectiveMistake)
        println(specs.joinToString("\n") { it.describe() })
        val extra = LeagueTables(tables.rules, threads = Runtime.getRuntime().availableProcessors())
        val ev = yacht.engine.league.LeagueAi("ev", "ev", yacht.engine.league.Family.EV, "") { AdvisorPlayer(Advisor(tables), Mode.EXPECTED_SCORE) }
        for (s in specs) {
            val ai = CustomAis.build(s, tables, extra)
            assertEquals(s.key, ai.key)
            assertEquals(2, LeagueMatch.play(tables.rules, ai, ev, 5).size)
        }
    }

    private fun decision(kind: DecisionKind, mistake: Boolean) = DecisionRecord(
        1, 1, listOf(1, 2, 3, 4, 5), kind, "x", "y", Mode.WIN, if (mistake) 0.4 else 0.5, 0.5, 190.0, 190.0,
    )

    @Test
    fun theCloneTakesMyMistakeRates() {
        fun game(i: Long, style: PlayStyle, keepMistakes: Int, boxMistakes: Int): GameState {
            val ds = List(10) { decision(DecisionKind.KEEP, it < keepMistakes) } + List(10) { decision(DecisionKind.SCORE, it < boxMistakes) }
            val full = Category.ALL.fold(Board(tables.rules)) { b, c -> b.with(c, 1) }
            return GameState(DiceSource.RANDOM, Side.ME, full, full, Side.ME, decisions = ds, createdAt = i, style = style)
        }
        assertNull(CloneProfile.of(listOf(game(1, PlayStyle.SOLO, 1, 1))))
        // Alone: 2 in 10 keeps and 1 in 10 boxes go wrong; the assisted game is left out.
        val history = (1L..3L).map { game(it, PlayStyle.SOLO, 2, 1) } + game(9, PlayStyle.ASSISTED, 0, 0)
        val p = assertNotNull(CloneProfile.of(history))
        assertEquals(3, p.games)
        assertEquals(0.2, p.keepSlip, 1e-12)
        assertEquals(0.1, p.boxSlip, 1e-12)
        val clone = CustomAis.clone(p, tables)
        val ev = yacht.engine.league.LeagueAi("ev", "ev", yacht.engine.league.Family.EV, "") { AdvisorPlayer(Advisor(tables), Mode.EXPECTED_SCORE) }
        assertEquals(2, LeagueMatch.play(tables.rules, clone, ev, 3).size)
    }

    @Test
    fun rankedGamesRememberTheirOpponentAndRating() {
        val g = GameState.new(DiceSource.RANDOM, Side.ME, tables.rules, PlayStyle.SOLO, opponent = "감으로 두기 (중수)", rankedAi = "soft1")
        assertTrue(g.ranked)
        val back = GameCodec.decode(GameCodec.encode(g.copy(rated = true)))
        assertEquals("soft1", back.rankedAi)
        assertTrue(back.rated)
        // A hint ends the ranked game.
        assertTrue(!g.copy(style = PlayStyle.PEEKED).ranked)
    }

    @Test
    fun theLadderRatesMeButNeverSchedulesMe() {
        val ladder = Ladder(listOf("ev", "a", "b", Ladder.ME))
        val rng = Random(2)
        repeat(30) { ladder.record("ev", if (it % 2 == 0) "a" else "b", listOf(GameScore(200, 180), GameScore(170, 190))) }
        repeat(200) { assertTrue(Matchmaker.pick(ladder, rng, emptySet())!!.toList().none { it == Ladder.ME }) }
        repeat(Ladder.ME_PLACEMENT - 1) { ladder.record(Ladder.ME, "a", listOf(GameScore(210, 200))) }
        assertNull(ladder.rankOf(Ladder.ME))
        ladder.record(Ladder.ME, "b", listOf(GameScore(150, 200)))
        assertNotNull(ladder.rankOf(Ladder.ME))
        assertNotNull(Matchmaker.opponentFor(ladder, ladder.rating(Ladder.ME), rng))
    }

    @Test
    fun aWatchedGameIsWholeAndMistakesAreCalledOut() {
        val rules = tables.rules
        val turns = WatchMatch.play(rules, GreedyPlayer(), AdvisorPlayer(Advisor(tables), Mode.EXPECTED_SCORE), Random(4))
        assertEquals(24, turns.size)
        assertTrue(turns.last().first.isComplete && turns.last().second.isComplete)
        // Holding nothing on 6 6 6 6 1 is a clear mistake for the plan.
        val me = Board(rules)
        val opp = Board(rules)
        val plan = Advisor(tables).planTurn(me, opp, Mode.WIN, depth = 1)
        val steps = Commentary(tables).aiSteps(
            plan, me, listOf(listOf(6, 6, 6, 6, 1), listOf(1, 2, 3, 4, 5)), listOf(emptyList()), Category.LARGE_STRAIGHT,
            null, null, judge = true, who = "상대",
        )
        val call = steps[0].lines.firstOrNull { it.tag.contains("실수") }
        assertNotNull(call, steps[0].lines.toString())
        assertEquals(Tone.GOOD, call.tone)
        assertTrue(call.text.contains("6 6 6 6 남기기"), call.text)
        println(call.text)
    }
}

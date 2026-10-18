package yacht.engine

import yacht.engine.explain.LuckAnalysis
import yacht.engine.explain.OutlookAnalysis
import yacht.engine.explain.WhatIfAnalysis
import yacht.engine.game.DiceSource
import yacht.engine.game.Game
import yacht.engine.game.GameCodec
import yacht.engine.game.GameState
import yacht.engine.game.MoveRecord
import yacht.engine.game.PlayStyle
import yacht.engine.game.Side
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Plays whole games through [Game] the way the app does, then checks the review's luck analysis. */
class LuckTest {
    private val tables get() = SolverTest.tables

    /**
     * Both sides play for [mode] (the opponent as the AI, with its throws recorded). With
     * [blunder] I write my first turn into the worst box instead of the recommended one.
     */
    private fun play(mode: Mode, seed: Long, first: Side, blunder: Boolean = false): GameState {
        val rng = java.util.Random(seed)
        val advisor = Advisor(tables)
        var s = GameState.new(DiceSource.RANDOM, first, style = PlayStyle.SOLO, opponent = " 친구 A ")
        var lastWin: Double? = null
        while (!s.isOver) {
            if (s.toMove == Side.OPP) {
                val turn = advisor.planTurn(s.opp, s.me, mode, depth = 1)
                val throws = ArrayList<List<Int>>()
                val held = ArrayList<List<Int>>()
                var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
                throws += Dice.rollFaces(r)
                for (rolls in 1..2) {
                    val k = turn.bestKeep(rolls, r)
                    if (k == Dice.rollAsKeep[r]) break
                    held += Dice.keepFaces(k)
                    r = Match.throwDice(rng, k)
                    throws += Dice.rollFaces(r)
                }
                val c = turn.bestCategory(r)
                val before = if (mode == Mode.WIN) lastWin ?: advisor.planTurn(s.me, s.opp, depth = 1).startValue else null
                s = Game.opponentScore(
                    s, c, tables.scores[r, c.ordinal], winBefore = before,
                    turn = MoveRecord(Side.OPP, c, 0, throws = throws, held = held, why = listOf("이유 하나", "둘")),
                )
                continue
            }
            val plan = advisor.planTurn(s.me, s.opp, mode, depth = 1)
            if (mode == Mode.WIN) s = Game.noteWinAfterOpponent(s, plan.startValue)
            s = Game.firstThrow(s, rng)
            var before = plan.startValue
            while (true) {
                val a = plan.advise(s.draft.rollsDone, s.draft.faces)
                if (a.shouldStop) {
                    val c = if (blunder && s.me.filledCount == 0) a.categories.last().category else a.bestCategory.category
                    lastWin = a.categories.first { it.category == c }.value.takeIf { mode == Mode.WIN }
                    s = Game.score(s, c, a, before = before)
                    break
                }
                s = Game.holdFaces(s, a.bestKeep!!.held)
                s = Game.reroll(s, a, rng, before = before)
                before = a.bestKeep!!.value
            }
        }
        return s
    }

    @Test
    fun maxExpectationLuckAddsUpToScoreMinusAverage() {
        val luck = LuckAnalysis(tables)
        val e0 = tables.ev[0, 0]
        for (seed in 1L..3L) {
            val g = play(Mode.EXPECTED_SCORE, seed, if (seed % 2 == 0L) Side.ME else Side.OPP)
            val r = luck.of(g)
            // Every decision follows the max-expectation plan, so dice luck is the whole difference.
            assertEquals(g.me.total - e0, r.me!!, 1e-6, "me, seed $seed")
            assertEquals(g.opp.total - e0, r.opp!!, 1e-6, "opp, seed $seed")
            assertTrue(!r.oppWithSkill)
            assertEquals(12, r.rounds.size)
            assertEquals(r.me!!, r.rounds.last().me!!, 1e-9)
            assertTrue(r.split == null)
            assertTrue(r.summary.endsWith("."))
        }
    }

    @Test
    fun winSplitAddsUp() {
        val luck = LuckAnalysis(tables)
        for ((seed, first) in listOf(4L to Side.ME, 5L to Side.OPP)) {
            val g = play(Mode.WIN, seed, first, blunder = true)
            val r = luck.of(g)
            val split = assertNotNull(r.split, "seed $seed")
            assertTrue(abs(split.other) < 2e-3, "unexplained ${split.other}")
            assertTrue(split.myChoices < -1e-4, "the blunder costs P(win): ${split.myChoices}")
            assertTrue(r.moments.isNotEmpty() && r.moments.size <= 3)
            assertTrue(r.moments.zipWithNext().all { (a, b) -> abs(a.delta) >= abs(b.delta) })
            println(r.summary)
            r.details.forEach { println("  $it") }
            r.moments.forEach { println("  * ${it.text} ${it.delta}") }
        }
    }

    @Test
    fun percentileIsMonotone() {
        val luck = LuckAnalysis(tables)
        val ps = listOf(-60.0, -20.0, 0.0, 20.0, 60.0).map { luck.percentile(it)!! }
        assertTrue(ps.zipWithNext().all { (a, b) -> a < b }, "$ps")
        assertTrue(ps[2] in 0.4..0.6, "${ps[2]}")
    }

    @Test
    fun newFieldsSurviveSaving() {
        val g = play(Mode.WIN, 6, Side.OPP)
        assertEquals("친구 A", g.opponent)
        assertEquals(PlayStyle.SOLO, g.style)
        assertEquals(PlayStyle.PEEKED, Game.peek(g).style)
        val back = GameCodec.decode(GameCodec.encode(g))
        assertEquals(g, back)
        assertEquals(listOf("이유 하나", "둘"), back.moves.first { it.side == Side.OPP }.why)
    }

    @Test
    fun whatIfComparesBothMoves() {
        val g = play(Mode.WIN, 7, Side.ME, blunder = true)
        val d = g.decisions.first { it.isMistake }
        val turn = Advisor(tables).planTurn(d.me!!, d.opp!!, d.mode, depth = 1)
        val w = assertNotNull(WhatIfAnalysis(tables).of(turn, d))
        for (side in listOf(w.best, w.chosen)) assertEquals(1.0, side.outcomes.sumOf { it.prob }, 1e-9)
        assertTrue(w.best.value > w.chosen.value)
        println(w.summary)
        w.details.forEach { println("  $it") }
        // A keep decision: the outcomes of rolling on.
        val keep = g.decisions.first { it.chosenHeld != null }
        val t2 = Advisor(tables).planTurn(keep.me!!, keep.opp!!, keep.mode, depth = 1)
        val w2 = assertNotNull(WhatIfAnalysis(tables).of(t2, keep))
        assertTrue(w2.best.outcomes.size > 1)
        assertEquals(1.0, w2.best.outcomes.sumOf { it.prob }, 1e-9)
    }

    @Test
    fun opponentOutlookOrdersScenarios() {
        val me = Category.ALL.filter { it != Category.CHOICE && it != Category.YACHT }.fold(Board()) { b, c -> b.with(c, if (c.isUpper) 3 * c.face else 20) }
        val opp = Category.ALL.filter { it != Category.YACHT && it != Category.SIXES && it != Category.CHOICE }
            .fold(Board()) { b, c -> b.with(c, if (c.isUpper) 3 * c.face else 22) }
        val advisor = Advisor(tables)
        val now = advisor.planTurn(me, opp, depth = Category.COUNT).startValue
        val outlook = OutlookAnalysis(tables)
        val quick = assertNotNull(outlook.opponentTurn(me, opp, now))
        assertTrue(quick.low <= quick.typical && quick.typical <= quick.high, "${quick.low} ${quick.typical} ${quick.high}")
        assertTrue(quick.yacht != null && quick.yacht!!.second < quick.typical)
        // With the end-game table the values are exact.
        val table = advisor.buildDuelTable(me, opp, meToMove = false)
        val exact = assertNotNull(outlook.opponentTurn(me, opp, now) { o -> table.valueAt(me, o)?.first })
        assertTrue(exact.exact)
        println("quick ${quick.low} ${quick.typical} ${quick.high} / exact ${exact.low} ${exact.typical} ${exact.high}")
        assertTrue(abs(quick.typical - exact.typical) < 0.05)
        println(exact.summary)
        exact.details.forEach { println("  $it") }
    }
}

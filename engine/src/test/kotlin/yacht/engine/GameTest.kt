package yacht.engine

import yacht.engine.game.DecisionKind
import yacht.engine.game.DiceSource
import yacht.engine.game.Game
import yacht.engine.game.GameCodec
import yacht.engine.game.GameState
import yacht.engine.game.Side
import yacht.engine.game.possibleScores
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameTest {
    private val tables get() = SolverTest.tables

    private fun enter(s: GameState, vararg faces: Int) = faces.fold(s) { acc, f -> Game.enterFace(acc, f) }

    @Test
    fun manualTurnFlowRecordsDecisions() {
        val advisor = Advisor(tables)
        var s = GameState.new(DiceSource.MANUAL, Side.ME)
        val turn = advisor.planTurn(s.me, s.opp)
        s = enter(s, 6, 6, 6, 2, 1)
        assertEquals(1, s.draft.rollsDone)
        assertTrue(s.draft.deciding)

        val a1 = turn.advise(1, s.draft.faces)
        s = Game.holdFaces(s, a1.bestKeep!!.held)
        s = Game.reroll(s, a1)
        assertFalse(s.draft.complete)
        assertEquals(a1.bestKeep!!.held, s.draft.dice.filterNotNull().sorted())
        assertEquals(1, s.decisions.size)
        assertEquals(DecisionKind.KEEP, s.decisions[0].kind)
        assertEquals(0.0, s.decisions[0].loss, 1e-12)

        val missing = 5 - s.draft.dice.count { it != null }
        s = (0 until missing).fold(s) { acc, _ -> Game.enterFace(acc, 6) }
        assertEquals(2, s.draft.rollsDone)

        // Backspace re-opens the throw, re-entering completes it again.
        s = Game.backspace(s)
        assertEquals(1, s.draft.rollsDone)
        s = Game.enterFace(s, 6)
        assertEquals(2, s.draft.rollsDone)

        val a2 = turn.advise(2, s.draft.faces)
        s = Game.score(s, Category.CHOICE, a2)
        assertEquals(Side.OPP, s.toMove)
        assertEquals(s.decisions.last().kind, DecisionKind.SCORE)
        assertTrue(s.me.isFilled(Category.CHOICE))

        s = Game.opponentScore(s, Category.YACHT, 50)
        assertEquals(Side.ME, s.toMove)
        assertEquals(50, s.opp.total)

        val back = GameCodec.decode(GameCodec.encode(s))
        assertEquals(s, back)
    }

    @Test
    fun randomGameAgainstAiFinishes() {
        val rng = java.util.Random(11)
        val advisor = Advisor(tables)
        val ai = AdvisorPlayer(advisor, Mode.WIN)
        var s = GameState.new(DiceSource.RANDOM, Side.OPP)
        while (!s.isOver) {
            if (s.toMove == Side.OPP) {
                ai.startTurn(s.opp, s.me)
                var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
                for (rolls in 1..2) {
                    val k = ai.chooseKeep(rolls, r)
                    if (k == Dice.rollAsKeep[r]) break
                    r = Match.throwDice(rng, k)
                }
                val c = ai.chooseCategory(r)
                s = Game.opponentScore(s, c, tables.scores[r, c.ordinal], "ai $c")
            } else {
                val turn = advisor.planTurn(s.me, s.opp)
                s = Game.firstThrow(s, rng)
                while (true) {
                    val a = turn.advise(s.draft.rollsDone, s.draft.faces)
                    if (a.shouldStop) {
                        s = Game.score(s, a.bestCategory.category, a)
                        break
                    }
                    s = Game.holdFaces(s, a.bestKeep!!.held)
                    s = Game.reroll(s, a, rng)
                }
            }
        }
        assertEquals(12, s.oppLog.size)
        assertTrue(s.decisions.all { it.loss < 1e-9 })
        assertEquals(s, GameCodec.decode(GameCodec.encode(s)))
    }

    @Test
    fun possibleScoresCoverRules() {
        assertEquals(listOf(0, 15), possibleScores(RuleSet.NINTENDO, Category.SMALL_STRAIGHT))
        assertEquals(listOf(0, 5, 10, 15, 20, 25), possibleScores(RuleSet.NINTENDO, Category.FIVES))
        assertTrue(25 in possibleScores(RuleSet.NINTENDO, Category.FULL_HOUSE))
        assertFalse(4 in possibleScores(RuleSet.NINTENDO, Category.FOUR_OF_A_KIND))
    }
}

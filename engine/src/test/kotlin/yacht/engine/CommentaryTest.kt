package yacht.engine

import yacht.engine.explain.Commentary
import yacht.engine.explain.OutlookAnalysis
import yacht.engine.explain.Tone
import yacht.engine.game.MoveRecord
import yacht.engine.game.Side
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommentaryTest {
    private val tables get() = SolverTest.tables
    private val rules get() = tables.rules

    @Test
    fun firstThrowYachtIsCelebratedWithItsExactOdds() {
        val turn = Advisor(tables).planTurn(Board(rules), Board(rules), Mode.WIN, depth = 1)
        val lines = Commentary(tables).afterThrow(turn, 1, listOf(6, 6, 6, 6, 6), emptyList())
        val yacht = lines.first { it.tag.contains("요트") }
        assertTrue(yacht.text.contains("1,296번에 한 번꼴"), yacht.text)
        val luck = lines.first { it.tag.contains("굴림 운") }
        assertEquals(Tone.GOOD, luck.tone)
        assertTrue(luck.text.startsWith("상위"), luck.text)
        // Playing alone: the dice fact stays, the AI's evaluation goes.
        val alone = Commentary(tables).afterThrow(turn, 1, listOf(6, 6, 6, 6, 6), emptyList(), factsOnly = true)
        assertTrue(alone.isNotEmpty() && alone.none { it.text.contains("승률") })
    }

    @Test
    fun aRerolledYachtQuotesTheChanceFromTheHeldDice() {
        val turn = Advisor(tables).planTurn(Board(rules), Board(rules), Mode.WIN, depth = 1)
        val lines = Commentary(tables).afterThrow(turn, 3, listOf(4, 4, 4, 4, 4), listOf(4, 4, 4))
        assertTrue(lines.any { it.text.contains("4 4 4를 남기고") && it.text.contains("2.8%") }, lines.toString())
    }

    @Test
    fun aiStepsEndAtTheValueOfItsFinalCard() {
        val me = Board(rules).with(Category.CHOICE, 22).with(Category.SIXES, 18)
        val ai = Board(rules).with(Category.CHOICE, 20).with(Category.FIVES, 15)
        val advisor = Advisor(tables)
        val aiTurn = advisor.planTurn(ai, me, Mode.WIN, depth = 1)
        val outlook = OutlookAnalysis(tables)
        val now = advisor.planTurn(me, ai, Mode.WIN, depth = 1).startValue
        val after = assertNotNull(outlook.winAfter(me, ai, now))
        val rng = Random(3)
        repeat(5) {
            val throws = ArrayList<List<Int>>()
            val held = ArrayList<List<Int>>()
            var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
            throws += Dice.rollFaces(r)
            for (rollsDone in 1..2) {
                val k = aiTurn.bestKeep(rollsDone, r)
                if (k == Dice.rollAsKeep[r]) break
                held += Dice.keepFaces(k)
                r = Match.throwDice(rng, k)
                throws += Dice.rollFaces(r)
            }
            val c = aiTurn.bestCategory(r)
            val steps = Commentary(tables).aiSteps(aiTurn, ai, throws, held, c, { after(it) }, now)
            assertEquals(throws.size, steps.size)
            val last = steps.last()
            assertEquals(c, last.category)
            assertEquals(after(ai.with(c, tables.scores[r, c.ordinal])), last.myWin!!, 1e-12)
            assertTrue(steps.dropLast(1).all { it.held != null && it.myWin!! in 0.0..1.0 })
        }
        // Before any throw the steps average to my P(win) now: the first step's expectation over its throw.
        val first = ArrayList<Double>()
        for (r in 0 until Dice.ROLLS) {
            val k = aiTurn.bestKeep(1, r)
            val steps = Commentary(tables).aiSteps(
                aiTurn, ai, listOf(Dice.rollFaces(r)), if (k == Dice.rollAsKeep[r]) emptyList() else listOf(Dice.keepFaces(k)),
                aiTurn.bestCategory(r), { after(it) }, now,
            )
            first += Dice.firstRoll[r] * steps[0].myWin!!
        }
        val viaAiPolicy = first.sum()
        // The AI plays for its own P(win), not max expectation as in the outlook's anchoring: close, not equal.
        assertTrue(abs(viaAiPolicy - now) < 0.03, "$viaAiPolicy vs $now")
    }

    @Test
    fun opponentLuckIsRankedAgainstItsOwnTurns() {
        val before = Board(rules).with(Category.CHOICE, 20)
        val talk = Commentary(tables)
        val yacht = talk.opponentResult(before.with(Category.YACHT, 50), MoveRecord(Side.OPP, Category.YACHT, 50), "AI")
        assertTrue(yacht.any { it.tone == Tone.BAD && it.text.startsWith("상위") && it.text.contains("번에 한 번꼴로") }, yacht.toString())
        val zero = talk.opponentResult(before.with(Category.LARGE_STRAIGHT, 0), MoveRecord(Side.OPP, Category.LARGE_STRAIGHT, 0), "AI")
        assertTrue(zero.any { it.tone == Tone.GOOD && it.text.contains("하위") }, zero.toString())
    }

    @Test
    fun previewNamesTheStakesOfALateTurn() {
        // Last box each, the opponent already done: this turn decides the game.
        val me = Category.ALL.filter { it != Category.YACHT }.fold(Board(rules)) { b, c -> b.with(c, if (c.isUpper) 3 * c.face else 20) }
        val opp = Category.ALL.fold(Board(rules)) { b, c -> b.with(c, if (c.isUpper) 3 * c.face else if (c == Category.YACHT) 0 else 20) }
            .let { it.without(Category.CHOICE).with(Category.CHOICE, 45) }
        val turn = Advisor(tables).planTurn(me, opp, Mode.WIN, depth = 1)
        val lines = Commentary(tables).turnPreview(turn, opp)
        assertTrue(lines.any { it.tone == Tone.HOT }, lines.toString())
        lines.forEach { println("${it.tag} ${it.text}") }
    }
}

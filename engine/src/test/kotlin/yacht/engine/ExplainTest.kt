package yacht.engine

import yacht.engine.explain.Explainer
import yacht.engine.explain.Explanation
import yacht.engine.explain.Ko
import yacht.engine.game.DecisionNote
import yacht.engine.game.DiceSource
import yacht.engine.game.Game
import yacht.engine.game.GameCodec
import yacht.engine.game.GameState
import yacht.engine.game.Side
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoreanTest {
    @Test
    fun particlesFollowTheReadSound() {
        assertEquals("6 6 6을", Ko.eul("6 6 6"))
        assertEquals("5 5를", Ko.eul("5 5"))
        assertEquals("요트가", Ko.i("요트"))
        assertEquals("L.스트레이트가", Ko.i("L.스트레이트"))
        assertEquals("초이스는", Ko.eun("초이스"))
        assertEquals("포카드와", Ko.wa("포카드"))
        assertEquals("9.3%로", Ko.ro("9.3%"))
        assertEquals("26점으로", Ko.ro("26점"))
        assertEquals("1로", Ko.ro("1")) // 일: ㄹ takes 로
        assertEquals("10으로", Ko.ro("10"))
        assertEquals("71%예요", Ko.ieyo("71%"))
        assertEquals("‘3’이에요", Ko.ieyo("‘3’"))
        assertEquals("‘6 6 고정’이었어요", Ko.ieosseoyo("‘6 6 고정’"))
        assertEquals("24점(네 개)을", Ko.eul("24점(네 개)"))
        assertEquals("2.1%p를", Ko.eul("2.1%p"))
    }

    @Test
    fun probabilitiesReadNaturally() {
        assertEquals("52%", Ko.pct(0.523))
        assertEquals("4.6%", Ko.pct(0.046))
        assertEquals("5%", Ko.pct(0.0501))
        assertEquals("0%", Ko.pct(0.0003))
        assertEquals("100%", Ko.pct(0.9996))
        assertEquals("99.6%", Ko.pct(0.996))
        assertEquals("2.1%p", Ko.pp(0.021))
        assertEquals("12%p", Ko.pp(0.12))
        assertEquals("54.4%" to "53.6%", Ko.pctPair(0.544, 0.536))
        assertEquals("47.0%" to "46.9%", Ko.pctPair(0.4703, 0.469))
    }
}

class ExplainTest {
    private val tables get() = SolverTest.tables

    @Test
    fun bonusTableMatchesClosedForm() {
        // Only the sixes box left with 45 in the upper section: the bonus needs three or more sixes,
        // and every die not yet a six is thrown again (each becomes a six with q = 1 - (5/6)^3).
        val board = Board.of(RuleSet.NINTENDO, listOf(5, 10, 15, 15, 0, null, 0, 0, 0, 0, 0, 0))
        val q = 1 - (5.0 / 6).pow(3)
        val exact = (3..5).sumOf { k -> binom(5, k) * q.pow(k) * (1 - q).pow(5 - k) }
        assertEquals(exact, tables.bonus!![board.mask, board.u], 1e-6)
        assertEquals(1.0, tables.bonus!![0b111000, 63], 0.0)
        assertEquals(0.0, tables.bonus!![Category.UPPER_MASK, 62], 0.0)
    }

    private fun binom(n: Int, k: Int): Double = (1..k).fold(1.0) { acc, i -> acc * (n - k + i) / i }

    @Test
    fun forwardPassReproducesKeepValues() {
        val board = Board.of(RuleSet.NINTENDO, listOf(3, null, 9, null, 15, null, 22, null, 0, 15, null, null))
        val plan = TurnPlan(board, ExpectedAfter(tables.ev, board.mask))
        val r = Dice.rollIndex(listOf(2, 4, 4, 5, 6))
        for (rollsDone in 1..2) {
            for (j in Dice.subStart[r] until Dice.subStart[r + 1]) {
                val k = Dice.subKeep[j]
                val final = plan.finalRolls(rollsDone, k)
                val v = (0 until Dice.ROLLS).sumOf { final[it] * plan.rollValue(3, it) }
                // Holding all five dice ends the turn.
                val expected = if (Dice.keepSize[k] == 5) plan.rollValue(3, r) else plan.keepValue(rollsDone, k)
                assertEquals(expected, v, 1e-9)
            }
        }
    }

    @Test
    fun yachtChanceInOneTurn() {
        assertEquals(0.0460, yacht.engine.explain.TurnAnalysis.chance(tables, Category.YACHT), 5e-4)
    }

    /** Plays games with stray moves and checks every explanation is well-formed text. */
    @Test
    fun explanationsAreCleanSentences() {
        val rng = java.util.Random(4)
        val advisor = Advisor(tables)
        val ex = Explainer(tables)
        val texts = ArrayList<String>()
        fun add(e: Explanation) {
            texts += e.summary
            texts += e.details
        }
        val finished = ArrayList<GameState>()
        repeat(2) { g ->
            var s = GameState.new(DiceSource.RANDOM, if (g == 0) Side.ME else Side.OPP)
            var lastWin: Double? = null
            while (!s.isOver) {
                if (s.toMove == Side.OPP) {
                    yacht.engine.explain.OutlookAnalysis(tables).opponentTurn(s.me, s.opp, lastWin ?: 0.5)?.let { add(Explanation(it.summary, it.details)) }
                    val turn = advisor.planTurn(s.opp, s.me, Mode.WIN, depth = 1)
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
                    texts += ex.aiTurn(turn, s.me, throws, held, c)
                    s = Game.opponentScore(s, c, tables.scores[r, c.ordinal], winBefore = lastWin)
                    continue
                }
                val turn = advisor.planTurn(s.me, s.opp, Mode.WIN, depth = 1)
                s = Game.noteWinAfterOpponent(s, turn.startValue)
                s.moves.lastOrNull()?.takeIf { it.side == Side.OPP }?.let {
                    texts += ex.opponentMove(s.opp, it, lastWin)
                    texts += ex.opponentMove(s.opp, it, lastWin, "AI")
                }
                add(ex.situation(s.me, s.opp, turn.startValue))
                add(ex.situation(s.me, s.opp, null))
                s = Game.firstThrow(s, rng)
                while (true) {
                    val a = turn.advise(s.draft.rollsDone, s.draft.faces)
                    add(ex.advice(turn, s.opp, a))
                    for (k in a.keeps.take(4)) add(ex.keepOption(turn, s.opp, a, k))
                    for (c in a.categories.take(4)) add(ex.categoryOption(turn, s.opp, a, c))
                    a.keeps.getOrNull(1)?.let { k -> ex.keepDeviation(turn, s.opp, a, k)?.let { texts += it.full } }
                    a.categories.getOrNull(1)?.let { c -> ex.categoryDeviation(turn, s.opp, a, c)?.let { texts += it.full } }
                    val stray = rng.nextInt(6) == 0
                    if (a.shouldStop) {
                        val pick = if (stray && a.categories.size > 1) a.categories[1] else a.bestCategory
                        val note = if (pick != a.bestCategory) ex.categoryDeviation(turn, s.opp, a, pick)?.let { DecisionNote(it.reason, it.chosenSd, it.bestSd) } else null
                        lastWin = pick.value
                        s = Game.score(s, pick.category, a, note)
                        break
                    }
                    val pick = if (stray) a.keeps[1] else a.bestKeep!!
                    if (pick.held.size == 5) {
                        lastWin = a.bestCategory.value
                        val note = ex.categoryDeviation(turn, s.opp, a, a.bestCategory)?.let { DecisionNote(it.reason, it.chosenSd, it.bestSd) }
                        s = Game.score(s, a.bestCategory.category, a, note)
                        break
                    }
                    val note = if (pick != a.bestKeep) ex.keepDeviation(turn, s.opp, a, pick)?.let { DecisionNote(it.reason, it.chosenSd, it.bestSd) } else null
                    s = Game.holdFaces(s, pick.held)
                    s = Game.reroll(s, a, rng, note)
                }
            }
            add(ex.gameSummary(s))
            val luck = yacht.engine.explain.LuckAnalysis(tables).of(s)
            add(Explanation(luck.summary, luck.details))
            for (d in s.decisions.filter { it.isMistake }) {
                val w = yacht.engine.explain.WhatIfAnalysis(tables).of(advisor.planTurn(d.me!!, d.opp!!, d.mode, depth = 1), d) ?: continue
                add(Explanation(w.summary, w.details))
            }
            assertEquals(s, GameCodec.decode(GameCodec.encode(s)))
            assertEquals(24, s.moves.size)
            finished += s
        }
        val report = yacht.engine.explain.Habits.analyze(finished)
        texts += report.summary
        texts += report.details
        for (h in report.habits) {
            assertTrue(h.title.isNotBlank())
            texts += h.detail
        }
        assertTrue(report.mistakes > 0)
        assertTrue(finished.flatMap { it.decisions }.filter { it.isMistake }.all { it.me != null && it.chosenSd != null })
        assertTrue(texts.size > 500)
        val bad = Regex("""null|NaN|Infinity|을/를|이/가|\$\{|\s{2,}|\.\.|-\d""")
        for (t in texts) {
            assertTrue(t.isNotBlank(), "empty explanation")
            assertTrue(!bad.containsMatchIn(t), "malformed: $t")
            assertTrue(t.last() in ".!)", "unfinished sentence: $t")
        }
    }
}

class HabitsAndQuizTest {
    private val tables get() = SolverTest.tables

    private fun record(
        kind: yacht.engine.game.DecisionKind,
        rollsDone: Int,
        dice: List<Int>,
        chosenHeld: List<Int>? = null,
        bestHeld: List<Int>? = null,
        chosenCategory: Category? = null,
        bestCategory: Category? = null,
        win: Double = 0.5,
        chosenSd: Double? = null,
        bestSd: Double? = null,
    ) = yacht.engine.game.DecisionRecord(
        5, rollsDone, dice, kind, "x", "y", Mode.WIN, win - 0.02, win, 190.0, 190.0,
        chosenHeld = chosenHeld, bestHeld = bestHeld, chosenCategory = chosenCategory, bestCategory = bestCategory,
        chosenSd = chosenSd, bestSd = bestSd,
    )

    @Test
    fun mistakesAreTagged() {
        val k = yacht.engine.game.DecisionKind.KEEP
        val sc = yacht.engine.game.DecisionKind.SCORE
        val h = yacht.engine.explain.Habits
        assertEquals(listOf("dropped_dice"), h.tags(record(k, 1, listOf(2, 6, 6, 6, 5), listOf(6, 6), listOf(6, 6, 6))))
        assertEquals(listOf("extra_dice"), h.tags(record(k, 1, listOf(2, 6, 6, 6, 5), listOf(5, 6, 6, 6), listOf(6, 6, 6))))
        assertEquals(listOf("greedy_reroll"), h.tags(record(k, 2, listOf(2, 3, 4, 5, 6), listOf(2, 3, 4, 5), bestCategory = Category.LARGE_STRAIGHT)))
        assertEquals(listOf("early_stop"), h.tags(record(sc, 1, listOf(1, 3, 3, 4, 6), chosenCategory = Category.CHOICE, bestHeld = listOf(3, 3))))
        assertEquals(listOf("choice_early"), h.tags(record(sc, 3, listOf(2, 2, 3, 3, 3), chosenCategory = Category.CHOICE, bestCategory = Category.FULL_HOUSE)))
        assertEquals(listOf("avoid_sacrifice"), h.tags(record(sc, 3, listOf(1, 2, 4, 4, 6), chosenCategory = Category.ACES, bestCategory = Category.YACHT)))
        assertEquals(
            listOf("other_target", "too_safe"),
            h.tags(record(k, 1, listOf(1, 2, 3, 6, 6), listOf(6, 6), listOf(1, 2, 3), win = 0.3, chosenSd = 20.0, bestSd = 24.0)),
        )
    }

    @Test
    fun quizFindsAndGradesQuestions() {
        val quiz = yacht.engine.explain.Quiz(Advisor(tables), Explainer(tables))
        val (q, plan) = quiz.generate(java.util.Random(1), tries = 60) ?: error("no question")
        val a = quiz.advice(plan, q)
        val right = if (a.shouldStop) quiz.gradeCategory(plan, q, a.bestCategory.category) else quiz.gradeKeep(plan, q, a.bestKeep!!.held)
        assertTrue(right.correct)
        val wrong = a.categories.first { !(a.shouldStop && it.category == a.bestCategory.category) }
        val graded = quiz.gradeCategory(plan, q, wrong.category)
        assertEquals(a.value - wrong.value, graded.loss, 1e-12)
        assertTrue(graded.explanation.summary.isNotBlank())
    }
}

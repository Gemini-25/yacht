package yacht.engine.explain

import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.Mode
import yacht.engine.PlannedTurn
import yacht.engine.Tables
import yacht.engine.game.DecisionRecord
import yacht.engine.game.Game
import yacht.engine.game.label

/** One way the rest of a turn can end: a group of outcomes with its probability and average points. */
data class OutcomeLine(val label: String, val prob: Double, val points: Double)

/** One side of a "what if": the move, its recorded value, and how the turn ends after it. */
data class WhatIfSide(
    val move: String,
    /** P(win) in WIN mode, expected final score otherwise (as recorded in the game). */
    val value: Double,
    val outcomes: List<OutcomeLine>,
    /** Average points written this turn. */
    val turnPoints: Double,
    /** P(upper bonus) at the end of the game afterwards; NaN when unknown. */
    val bonusProb: Double,
    val expectedFinal: Double,
)

/** "추천대로 뒀다면?": the recommended move next to the one played, outcome by outcome. */
class WhatIf(val mode: Mode, val best: WhatIfSide, val chosen: WhatIfSide, val summary: String, val details: List<String>)

class WhatIfAnalysis(private val tables: Tables) {
    /** Outcome groups for display; upper boxes are numbers, so they are quoted. */
    internal fun label(o: Int): String {
        val c = Outcome.category(o)
        val b = if (c.isUpper) "‘${c.face}’" else c.label
        return when {
            Outcome.kind(o) == Outcome.ZERO -> "$b 0점"
            c.isUpper && Outcome.kind(o) == Outcome.FEW -> "$b 한두 개"
            c.isUpper -> "$b 세 개 이상"
            else -> b
        }
    }

    /** [label] with the points where they help: fixed boxes "S.스트레이트 15점", sums "포카드 약 24점". */
    private fun labelWithPoints(o: Int, points: Double): String {
        val c = Outcome.category(o)
        if (c.isUpper || Outcome.kind(o) == Outcome.ZERO) return label(o)
        return when (c) {
            Category.CHOICE, Category.FOUR_OF_A_KIND, Category.FULL_HOUSE -> "${c.label} 약 ${Ko.int(points)}점"
            else -> "${c.label} ${Ko.int(points)}점"
        }
    }

    private fun lines(p: Profile): List<OutcomeLine> {
        val all = (0 until Outcome.COUNT).filter { p.p(it) > 0.0 }
            .map { OutcomeLine(labelWithPoints(it, p.points(it)), p.p(it), p.points(it)) }
            .sortedByDescending { it.prob }
        val shown = all.filter { it.prob >= 0.01 }.take(5)
        val rest = 1.0 - shown.sumOf { it.prob }
        if (rest < 0.005) return shown
        val restPoints = all.filter { it !in shown }.let { r -> r.sumOf { it.prob * it.points } / r.sumOf { it.prob }.coerceAtLeast(1e-12) }
        return shown + OutcomeLine("그 밖", rest, restPoints)
    }

    private fun turnPoints(p: Profile) = (0 until Outcome.COUNT).sumOf { p.pointsMass[it] }

    private fun keepSide(an: TurnAnalysis, rollsDone: Int, held: List<Int>, value: Double): WhatIfSide {
        val p = an.keepProfile(rollsDone, Dice.keepIndex(Dice.countsOf(held)))
        return WhatIfSide(Game.keepText(held), value, lines(p), turnPoints(p), p.bonusProb, p.expectedFinal)
    }

    private fun boxSide(an: TurnAnalysis, r: Int, c: Category, value: Double): WhatIfSide {
        val f = an.category(r, c)
        val line = OutcomeLine(if (c.isUpper) "‘${c.face}’ ${f.points}점" else "${c.label} ${f.points}점", 1.0, f.points.toDouble())
        return WhatIfSide(Game.scoreText(c, f.points), value, listOf(line), f.points.toDouble(), f.bonusProb, f.expectedFinal)
    }

    /**
     * The decision [d] replayed with [turn] (the plan of that turn, recomputed for the review);
     * null for records without the moves themselves (older saves).
     */
    fun of(turn: PlannedTurn, d: DecisionRecord): WhatIf? {
        val an = TurnAnalysis(tables, turn)
        val r = Dice.rollIndex(d.dice)
        fun side(held: List<Int>?, c: Category?, value: Double): WhatIfSide? = when {
            held != null && d.rollsDone < 3 && held.size < 5 -> keepSide(an, d.rollsDone, held, value)
            c != null -> boxSide(an, r, c, value)
            else -> null
        }
        val best = side(d.bestHeld, d.bestCategory, d.bestValue) ?: return null
        val chosen = side(d.chosenHeld, d.chosenCategory, d.chosenValue) ?: return null
        val win = d.mode == Mode.WIN
        val (vb, vc) = if (win) Ko.pctPair(best.value, chosen.value) else Ko.num(best.value) + "점" to Ko.num(chosen.value) + "점"
        val what = if (win) "승률" else "기대 점수"
        val doing = when {
            d.bestHeld != null && best.outcomes.size > 1 && best.move == Game.keepText(emptyList()) -> "모두 다시 굴렸다면"
            d.bestHeld != null && best.outcomes.size > 1 -> "${best.move}하고 다시 굴렸다면"
            else -> "${Ko.ro(best.move)} 기록했다면"
        }
        val summary = "추천대로 $doing $what ${Ko.ieosseoyo(vb)}(실제 선택은 $vc)."
        val details = ArrayList<String>()
        val bothRolls = best.outcomes.size > 1 || chosen.outcomes.size > 1
        if (bothRolls) {
            details += "이번 턴 평균 점수는 추천 ${Ko.num(best.turnPoints)}점, 내 선택 ${Ko.num(chosen.turnPoints)}점이에요."
        }
        if (!best.bonusProb.isNaN() && !chosen.bonusProb.isNaN() && kotlin.math.abs(best.bonusProb - chosen.bonusProb) >= 0.03) {
            val (a, b) = Ko.pctPair(best.bonusProb, chosen.bonusProb)
            details += "보너스를 받을 확률은 추천 $a, 내 선택 ${Ko.ieyo(b)}."
        }
        if (win && kotlin.math.abs(best.expectedFinal - chosen.expectedFinal) >= 0.5) {
            details += "예상 최종 점수는 추천 ${Ko.int(best.expectedFinal)}점, 내 선택 ${Ko.int(chosen.expectedFinal)}점이에요."
        }
        return WhatIf(d.mode, best, chosen, summary, details)
    }
}

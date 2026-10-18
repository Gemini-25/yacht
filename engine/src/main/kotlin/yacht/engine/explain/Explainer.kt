package yacht.engine.explain

import yacht.engine.Advice
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.CategoryOption
import yacht.engine.Dice
import yacht.engine.KeepOption
import yacht.engine.Mode
import yacht.engine.PlannedTurn
import yacht.engine.Tables
import yacht.engine.game.GameState
import yacht.engine.game.MoveRecord
import yacht.engine.game.Side
import yacht.engine.game.label
import kotlin.math.abs

/** One explanation: a line that is always shown and a few sentences behind "왜?". */
data class Explanation(val summary: String, val details: List<String> = emptyList())

/**
 * A move that differed from the advice: what was recommended (with the loss) and why, and the
 * spread of my final score after the chosen and the recommended move (for habit analysis).
 */
data class Deviation(val head: String, val reason: String?, val chosenSd: Double? = null, val bestSd: Double? = null) {
    val full: String get() = listOfNotNull(head, reason).joinToString(" ")
}

/**
 * Turns the engine's numbers into plain Korean (해요체). Every number in a sentence is computed
 * by the engine; a reason is only stated when the numbers show it carries the difference.
 */
class Explainer(private val tables: Tables) {
    private val rules = tables.rules
    private val cap = rules.bonusThreshold
    private var lastAnalysis: TurnAnalysis? = null

    @Synchronized
    private fun analysis(turn: PlannedTurn): TurnAnalysis =
        lastAnalysis?.takeIf { it.turn === turn } ?: TurnAnalysis(tables, turn).also { lastAnalysis = it }

    // ---- recommendations ------------------------------------------------------------------------

    /** Why the recommended move of [a] is the best one. */
    fun advice(turn: PlannedTurn, opp: Board, a: Advice): Explanation {
        val an = analysis(turn)
        if (a.keeps.isEmpty() || a.shouldStop && a.rollsDone == 3) return categoryExplanation(an, a, a.bestCategory, null, opp)
        val best = a.bestKeep!!
        if (best.held.size == 5) return stopExplanation(an, a, opp)
        val (other, strategic) = keepRival(a, best)
            ?: return Explanation("선택할 수 있는 방법이 이것뿐이에요.")
        val c = keepCompare(an, a, best, other)
        val summary = keepSummary(c, a.mode)
        val details = ArrayList<String>()
        if (decided(a.mode, best.value, other.value)) {
            details += evFraming(best.expectedFinal, other.expectedFinal, keepNoun(other))
            details += mainSentences(c, keepIf(best), keepIf(other)).take(1)
            return Explanation(summary, details)
        }
        details += framing(a.mode, best.value, other.value, keepNoun(other))
        if (tiny(a.mode, c.diff)) {
            balanceSentence(c, keepIf(best))?.let { details += it }
            return Explanation(summary, details)
        }
        val mains = mainSentences(c, keepIf(best), keepIf(other))
        details += mains.take(1)
        if (mains.isEmpty()) spreadSentence(c, best, other)?.let { details += it }
        if (strategic) {
            details += strategy(a.mode, a.value, keepNoun(other), other.expectedFinal - best.expectedFinal, c.pa.finalSd, c.pb.finalSd, opp)
        } else {
            (mains.getOrNull(1) ?: tradeSentence(c))?.let { details += it }
        }
        return Explanation(summary, details.take(3))
    }

    /** Why the recommendation beats keeping [x] (the player is looking at another option). */
    fun keepOption(turn: PlannedTurn, opp: Board, a: Advice, x: KeepOption): Explanation {
        val best = a.bestKeep ?: return advice(turn, opp, a)
        if (x.held == best.held) return advice(turn, opp, a)
        val an = analysis(turn)
        val r = Dice.rollIndex(a.dice)
        val summary = optionSummary(a.mode, best.value, x.value, keepNoun(best))
        val details = ArrayList<String>()
        if (best.held.size == 5) {
            details += worseSentence(an, a, r, x, best.value)
        } else {
            val c = keepCompare(an, a, best, x)
            val doing = "추천대로 " + keepIf(best)
            if (tiny(a.mode, c.diff)) {
                balanceSentence(c, doing)?.let { details += it }
            } else {
                val mains = mainSentences(c, doing, "이렇게 하면")
                details += mains.take(1)
                if (mains.isEmpty()) spreadSentence(c, best, x, "추천대로 ")?.let { details += it }
                if (a.mode == Mode.WIN && x.expectedFinal - best.expectedFinal >= 0.3) {
                    details += strategy(a.mode, a.value, "이 선택", x.expectedFinal - best.expectedFinal, c.pa.finalSd, c.pb.finalSd, opp)
                } else {
                    (mains.getOrNull(1) ?: rivalMerit(c))?.let { details += it }
                }
            }
        }
        return Explanation(summary, details.take(3))
    }

    /** Why the recommendation beats writing into [x] now. */
    fun categoryOption(turn: PlannedTurn, opp: Board, a: Advice, x: CategoryOption): Explanation {
        val an = analysis(turn)
        if (!a.shouldStop) {
            // Rolling again is better than writing anything now.
            val best = a.bestKeep!!
            val summary = optionSummary(a.mode, best.value, x.value, keepNoun(best))
            val stop = a.keeps.firstOrNull { it.held.size == 5 }
            val details = ArrayList<String>()
            if (stop != null) {
                details += mainSentences(keepCompare(an, a, best, stop), "추천대로 " + keepIf(best), "여기서 멈추면").take(1)
            }
            return Explanation(summary, details)
        }
        val best = a.bestCategory
        if (x.category == best.category) return advice(turn, opp, a)
        val e = categoryExplanation(an, a, best, x, opp, asOption = true)
        return Explanation(optionSummary(a.mode, best.value, x.value, catNoun(best)), e.details.drop(1))
    }

    /** A short note for the record when the player chose [x] over the recommendation; null if it was fine. */
    fun keepDeviation(turn: PlannedTurn, opp: Board, a: Advice, x: KeepOption): Deviation? {
        val best = a.bestKeep ?: return null
        if (x.held == best.held || tiny(a.mode, best.value - x.value)) return null
        val head = "추천은 ${Ko.ieosseoyo(keepNoun(best))}(${lossText(a.mode, best.value - x.value)})."
        val an = analysis(turn)
        val r = Dice.rollIndex(a.dice)
        return Deviation(
            head, keepOption(turn, opp, a, x).details.firstOrNull(),
            keepSd(an, a, r, x), keepSd(an, a, r, best),
        )
    }

    private fun keepSd(an: TurnAnalysis, a: Advice, r: Int, k: KeepOption): Double? =
        (if (k.held.size == 5) an.categorySd(r, a.bestCategory.category)
        else an.keepProfile(a.rollsDone, keepIndex(k)).finalSd).takeIf { !it.isNaN() }

    fun categoryDeviation(turn: PlannedTurn, opp: Board, a: Advice, x: CategoryOption): Deviation? {
        val bestValue = a.value
        if (tiny(a.mode, bestValue - x.value)) return null
        val bestNoun = if (a.shouldStop) catNoun(a.bestCategory) else keepNoun(a.bestKeep!!)
        val head = "추천은 ${Ko.ieosseoyo(bestNoun)}(${lossText(a.mode, bestValue - x.value)})."
        val an = analysis(turn)
        val r = Dice.rollIndex(a.dice)
        val bestSd = if (a.shouldStop) an.categorySd(r, a.bestCategory.category) else an.keepProfile(a.rollsDone, keepIndex(a.bestKeep!!)).finalSd
        return Deviation(
            head, categoryOption(turn, opp, a, x).details.firstOrNull(),
            an.categorySd(r, x.category).takeIf { !it.isNaN() }, bestSd.takeIf { !it.isNaN() },
        )
    }

    // ---- keep comparison -------------------------------------------------------------------------

    private class Reason(
        /** Outcome index, or -1 for "some box gets 0 points". */
        val o: Int,
        val quality: Boolean,
        val weight: Double,
        val pA: Double,
        val pB: Double,
        val ptsA: Double,
        val ptsB: Double,
        val good: Boolean,
    )

    private class Compare(val pa: Profile, val pb: Profile, val diff: Double, val reasons: List<Reason>)

    /** The option to compare the best keep with, and whether it is the max-expectation choice. */
    private fun keepRival(a: Advice, best: KeepOption): Pair<KeepOption, Boolean>? {
        val ev = a.keeps.maxBy { it.expectedFinal }
        if (a.mode == Mode.WIN && ev.held != best.held && ev.expectedFinal - best.expectedFinal >= 0.3 &&
            best.value - ev.value >= 0.0005
        ) return ev to true
        return a.keeps.firstOrNull { it.held != best.held }?.let { it to false }
    }

    private fun keepIndex(k: KeepOption) = Dice.keepIndex(Dice.countsOf(k.held))

    private fun keepCompare(an: TurnAnalysis, a: Advice, x: KeepOption, y: KeepOption): Compare {
        val pa = an.keepProfile(a.rollsDone, keepIndex(x))
        val pb = an.keepProfile(a.rollsDone, keepIndex(y))
        return Compare(pa, pb, pa.value - pb.value, reasons(pa, pb))
    }

    /**
     * Splits the value difference over outcomes. Against the midpoint c of both values, each
     * outcome adds P_A (V_A - c) - P_B (V_B - c): more often reaching a good outcome or less often
     * a bad one, plus a better result within the same outcome. The parts add up exactly.
     */
    private fun reasons(a: Profile, b: Profile): List<Reason> {
        val c = (a.value + b.value) / 2
        val out = ArrayList<Reason>()
        var zA = 0.0
        var zB = 0.0
        var zW = 0.0
        var zV = 0.0
        for (o in 0 until Outcome.COUNT) {
            val pa = a.p(o)
            val pb = b.p(o)
            if (pa < 1e-9 && pb < 1e-9) continue
            val va = a.cond(o)
            val vb = b.cond(o)
            val vBar = when {
                va.isNaN() -> vb
                vb.isNaN() -> va
                else -> (va + vb) / 2
            }
            val probW = (pa - pb) * (vBar - c)
            out += Reason(o, false, probW, pa, pb, a.points(o), b.points(o), vBar > c)
            if (!va.isNaN() && !vb.isNaN()) {
                out += Reason(o, true, (pa + pb) / 2 * (va - vb), pa, pb, a.points(o), b.points(o), vBar > c)
            }
            if (Outcome.kind(o) == Outcome.ZERO) {
                zA += pa
                zB += pb
                zW += probW
                zV += (pa + pb) * vBar
            }
        }
        if (zA + zB > 0) out += Reason(-1, false, zW, zA, zB, 0.0, 0.0, zV / (zA + zB) > c)
        return out
    }

    private fun sayable(r: Reason): Boolean =
        if (r.quality) {
            !r.ptsA.isNaN() && !r.ptsB.isNaN() && abs(r.ptsA - r.ptsB) >= 0.5 && minOf(r.pA, r.pB) >= 0.02 &&
                (r.weight > 0) == (r.ptsA > r.ptsB)
        } else {
            abs(r.pA - r.pB) >= 0.01 && (r.weight > 0) == ((r.pA > r.pB) == r.good)
        }

    /** The reasons that carry the difference, strongest first (at most two). */
    private fun mainReasons(c: Compare): List<Reason> {
        if (c.diff <= 0) return emptyList()
        // Reaching something good reads more naturally than avoiding something bad: prefer it when close.
        val sorted = c.reasons.filter { it.weight > 0 && sayable(it) }
            .sortedByDescending { if (!it.quality && it.good && it.pA > it.pB) it.weight * 1.25 else it.weight }
        val first = sorted.firstOrNull()?.takeIf { it.weight >= 0.2 * c.diff } ?: return emptyList()
        val second = sorted.drop(1).firstOrNull { r ->
            r.weight >= 0.15 * c.diff && r.o != first.o && !overlaps(first, r)
        }
        return listOfNotNull(first, second)
    }

    /** "Some box gets 0" contains every single 0-point outcome. */
    private fun overlaps(a: Reason, b: Reason): Boolean {
        fun zero(r: Reason) = r.o >= 0 && Outcome.kind(r.o) == Outcome.ZERO
        return (a.o < 0 && zero(b)) || (b.o < 0 && zero(a))
    }

    private fun tradeReason(c: Compare, mains: List<Reason>): Reason? {
        val m = mains.firstOrNull() ?: return null
        val r = c.reasons.filter { t -> t.weight < 0 && sayable(t) && mains.none { it.o == t.o || overlaps(it, t) } }
            .minByOrNull { it.weight } ?: return null
        return r.takeIf { -it.weight >= 0.3 * m.weight }
    }

    /** Adnominal clause for an outcome: "요트가 나올", "‘3’에 한두 개만 넣게 될". */
    private fun clause(o: Int, good: Boolean): String {
        if (o < 0) return "0점 칸이 생길"
        val c = Outcome.category(o)
        val kind = Outcome.kind(o)
        return when {
            kind == Outcome.ZERO -> "${box(c)}에 0점을 넣게 될"
            c == Category.CHOICE -> if (good) "초이스에 넣게 될" else "초이스로 때우게 될"
            c.isUpper && kind == Outcome.MADE && good -> "${Ko.eul("${c.face}")} 세 개 이상 모을"
            c.isUpper && kind == Outcome.FEW -> "${box(c)}에 한두 개만 넣게 될"
            c.isUpper -> "${box(c)}에 넣게 될"
            !good -> "${c.label} 정도로 끝날"
            c == Category.YACHT -> "요트가 나올"
            c == Category.SMALL_STRAIGHT || c == Category.LARGE_STRAIGHT -> "${Ko.i(c.label)} 완성될"
            else -> "${Ko.ro(c.label)} 마무리할"
        }
    }

    /** What an outcome aims at, for "…를 노리는 수예요"; null for outcomes that are not targets. */
    private fun target(o: Int): String? {
        if (o < 0 || Outcome.kind(o) != Outcome.MADE) return null
        val c = Outcome.category(o)
        return when {
            c == Category.CHOICE -> null
            c.isUpper -> box(c)
            else -> c.label
        }
    }

    private fun keepSummary(c: Compare, mode: Mode): String {
        if (decided(mode, c.pa.value, c.pb.value)) return "승패는 사실상 정해져서, 최종 점수를 조금이라도 높이는 쪽을 골랐어요."
        if (tiny(mode, c.diff)) return "다른 선택과 거의 차이가 없어요."
        val mains = mainReasons(c)
        val r = mains.firstOrNull() ?: run {
            val boxes = spreadBoxes(c)
            return when (boxes.size) {
                2 -> "${Ko.wa(box(boxes[0]))} ${box(boxes[1])} 쪽 결과가 조금씩 함께 좋아지는 수예요."
                1 -> "${box(boxes[0])} 쪽 결과가 조금씩 좋아지는 수예요."
                else -> "한 가지 결과 때문이라기보다, 나올 수 있는 경우를 모두 합쳐 보면 조금씩 더 나은 수예요."
            }
        }
        if (r.quality) {
            return "같은 ${Ko.irado(box(Outcome.category(r.o)))} 점수를 평균 ${Ko.num(r.ptsA - r.ptsB)}점 더 받는 수예요."
        }
        if (r.pA > r.pB) {
            val t1 = target(r.o)
            val second = mains.getOrNull(1)
            val t2 = second?.takeIf { !it.quality && it.pA > it.pB }?.let { target(it.o) }
            if (t1 != null && t2 != null && r.weight < 0.6 * c.diff) return "${Ko.wa(t1)} $t2 쪽을 함께 노리는 수예요."
            val cat = Outcome.category(r.o)
            val chance = "(이번 턴 확률 ${Ko.pct(r.pA)})"
            if (t1 != null && r.pA < 0.08) {
                val goal = if (cat.isUpper) "${Ko.eul("${cat.face}")} 세 개 이상 모을" else t1
                return "확률은 낮지만 $goal 기회를 살리는 수예요$chance."
            }
            if (t1 != null && cat.isUpper) return "${Ko.eul("${cat.face}")} 세 개 이상 모으는 수예요$chance."
            if (t1 != null) return "${Ko.eul(t1)} 노리는 수예요$chance."
            return "${clause(r.o, true)} 확률을 ${Ko.ro(Ko.pct(r.pA))} 높이는 수예요."
        }
        if (r.pA < 0.005) return "${clause(r.o, false)} 위험을 피하는 수예요."
        return "${clause(r.o, false)} 확률을 ${Ko.ro(Ko.pct(r.pA))} 줄이는 수예요."
    }

    /** Boxes whose results improve the most, when no single outcome carries the difference. */
    private fun spreadBoxes(c: Compare): List<Category> {
        val w = DoubleArray(Category.COUNT)
        for (r in c.reasons) if (r.o >= 0) w[Outcome.category(r.o).ordinal] += r.weight
        return (0 until Category.COUNT).filter { w[it] > 0.15 * c.diff }.sortedByDescending { w[it] }.take(2).map { Category.of(it) }
    }

    /** "5를 버리고 다시 굴리면 ‘2’, 포카드 쪽 결과가 조금씩 함께 좋아져요." */
    private fun spreadSentence(c: Compare, a: KeepOption, b: KeepOption, prefix: String = ""): String? {
        val boxes = spreadBoxes(c)
        if (boxes.isEmpty()) return null
        fun minus(x: List<Int>, y: List<Int>): List<Int>? {
            val left = x.toMutableList()
            for (f in y) if (!left.remove(f)) return null
            return left
        }
        val dropped = minus(b.held, a.held)
        val added = minus(a.held, b.held)
        val lead = when {
            dropped != null && dropped.isNotEmpty() -> "${Ko.eul(dice(dropped))} 버리고 다시 굴리면"
            added != null && added.isNotEmpty() -> "${dice(added)}까지 남기면"
            else -> keepIf(a)
        }
        val names = boxes.joinToString(", ") { box(it) }
        val tail = if (boxes.size > 1) "결과가 조금씩 함께 좋아져요." else "결과가 조금씩 좋아져요."
        return "$prefix$lead $names 쪽 $tail"
    }

    /** One reason as a sentence. [doingA]/[doingB] say what each option does ("6 6 6을 남기면"). */
    private fun reasonSentence(r: Reason, doingA: String, doingB: String, also: Boolean): String {
        val too = if (also) "도" else ""
        if (r.quality) {
            val b = box(Outcome.category(r.o))
            return if (also) "${b}에 넣을 때 점수도 평균 ${Ko.num(r.ptsA - r.ptsB)}점 높아요."
            else "$doingA ${b}에 넣게 되더라도 평균 ${Ko.num(r.ptsA - r.ptsB)}점을 더 받아요."
        }
        val cl = clause(r.o, r.good)
        if (!r.good && r.pA < 0.005) {
            return if (also) "$doingB $cl 확률도 ${Ko.pct(r.pB)}나 돼요." else "$doingB $cl 확률이 ${Ko.pct(r.pB)}나 되거든요."
        }
        val verb = if (r.pA > r.pB) "올라가요" else "줄어요"
        val subject = if (also) "$cl 확률$too" else "$doingA $cl 확률이"
        return "$subject ${Ko.pct(r.pB)}에서 ${Ko.ro(Ko.pct(r.pA))} $verb."
    }

    /** The main reasons as sentences (one or two). */
    private fun mainSentences(c: Compare, doingA: String, doingB: String): List<String> {
        val mains = mainReasons(c)
        return mains.mapIndexed { i, r -> reasonSentence(r, doingA, doingB, also = i > 0) }
    }

    /** What the better option gives up, if it matters. */
    private fun tradeSentence(c: Compare): String? {
        val r = tradeReason(c, mainReasons(c)) ?: return null
        if (r.quality) {
            return "대신 ${box(Outcome.category(r.o))}에 넣을 때 점수는 평균 ${Ko.num(r.ptsB - r.ptsA)}점 적어요."
        }
        val verb = if (r.pA > r.pB) "늘어요" else "줄어요"
        return "대신 ${clause(r.o, r.good)} 확률은 ${Ko.pct(r.pB)}에서 ${Ko.ro(Ko.pct(r.pA))} $verb."
    }

    /** The same trade-off, said from the side of the option the player is looking at. */
    private fun rivalMerit(c: Compare): String? {
        val r = tradeReason(c, mainReasons(c)) ?: return null
        if (r.quality) {
            return "물론 이렇게 하면 ${box(Outcome.category(r.o))}에 넣을 때 점수가 평균 ${Ko.num(r.ptsB - r.ptsA)}점 높긴 해요."
        }
        val cmp = if (r.pB > r.pA) "높긴" else "낮긴"
        return "물론 이렇게 하면 ${clause(r.o, r.good)} 확률이 ${Ko.ro(Ko.pct(r.pB))} 추천(${Ko.pct(r.pA)})보다 $cmp 해요."
    }

    /** Both sides of a near tie: what the recommendation gains and what it gives up. */
    private fun balanceSentence(c: Compare, doingA: String): String? {
        val sorted = c.reasons.filter { sayable(it) && !it.quality }.sortedByDescending { abs(it.weight) }
        val plus = sorted.firstOrNull { it.weight > 0 } ?: return null
        val minus = sorted.firstOrNull { it.weight < 0 && it.o != plus.o && !overlaps(plus, it) } ?: return null
        val v1 = if (plus.pA > plus.pB) "올라가는" else "줄어드는"
        val v2 = if (minus.pA > minus.pB) "늘어나서" else "줄어들어서"
        return "$doingA ${clause(plus.o, plus.good)} 확률이 ${Ko.pct(plus.pB)}에서 ${Ko.ro(Ko.pct(plus.pA))} $v1 대신 " +
            "${clause(minus.o, minus.good)} 확률은 ${Ko.pct(minus.pB)}에서 ${Ko.ro(Ko.pct(minus.pA))} $v2, 장단점이 거의 맞먹어요."
    }

    // ---- stopping ---------------------------------------------------------------------------------

    private fun stopExplanation(an: TurnAnalysis, a: Advice, opp: Board): Explanation {
        val stop = a.bestKeep!!
        val r = Dice.rollIndex(a.dice)
        val reroll = a.keeps.firstOrNull { it.held.size < 5 }
        val cat = categoryExplanation(an, a, a.bestCategory, null, opp)
        if (reroll == null) return cat
        val details = ArrayList<String>()
        val diff = stop.value - reroll.value
        val summary = if (tiny(a.mode, diff)) "더 굴려도 거의 차이가 없어서, 여기서 기록해도 돼요."
        else "더 굴리지 말고 지금 점수를 챙기는 게 나아요."
        details += framing(a.mode, stop.value, reroll.value, rerollNoun(reroll))
        if (!tiny(a.mode, diff)) details += worseSentence(an, a, r, reroll, stop.value)
        cat.details.drop(1).firstOrNull()?.let { details += it }
        return Explanation(summary, details.take(3))
    }

    /** "다시 굴려서 요트를 노려도 성공 확률은 9%지만, 지금보다 나빠질 확률은 62%예요." */
    private fun worseSentence(an: TurnAnalysis, a: Advice, r: Int, reroll: KeepOption, stopValue: Double): String {
        val k = keepIndex(reroll)
        val worse = an.probWorse(a.rollsDone, k, stopValue)
        val prof = an.keepProfile(a.rollsDone, k)
        val stopProf = an.stopProfile(r)
        // The best thing rerolling could bring: the made outcome it adds the most value with.
        var bestO = -1
        var bestW = 0.0
        for (o in 0 until Outcome.COUNT) {
            if (Outcome.kind(o) != Outcome.MADE) continue
            val gain = prof.p(o) - stopProf.p(o)
            val v = prof.cond(o)
            if (gain < 0.01 || v.isNaN() || v <= stopValue) continue
            val w = gain * (v - stopValue)
            if (w > bestW) {
                bestW = w
                bestO = o
            }
        }
        val tail = "지금보다 나빠질 확률은 ${Ko.ieyo(Ko.pct(worse))}"
        if (bestO < 0) return "다시 굴리면 지금보다 나빠질 확률이 ${Ko.ieyo(Ko.pct(worse))}."
        val c = Outcome.category(bestO)
        val goal = if (c.isUpper) "${Ko.eul("${c.face}")} 더 모아도" else if (c == Category.CHOICE) "초이스 점수를 노려도" else "${Ko.eul(c.label)} 노려도"
        return "다시 굴려서 $goal 성공 확률은 ${Ko.ijiman(Ko.pct(prof.p(bestO)))}, $tail."
    }

    // ---- categories -------------------------------------------------------------------------------

    private fun categoryExplanation(
        an: TurnAnalysis,
        a: Advice,
        best: CategoryOption,
        rival: CategoryOption?,
        opp: Board,
        asOption: Boolean = false,
    ): Explanation {
        val me = an.me
        val r = Dice.rollIndex(a.dice)
        val evBest = a.categories.maxBy { it.expectedFinal }
        val strategic = rival == null && a.mode == Mode.WIN && evBest.category != best.category &&
            evBest.expectedFinal - best.expectedFinal >= 0.3 && best.value - evBest.value >= 0.0005
        val other = rival ?: if (strategic) evBest else a.categories.firstOrNull { it.category != best.category }
        if (other == null) return Explanation("남은 칸이 ${box(best.category)}뿐이에요.")
        val fa = an.category(r, best.category)
        val fb = an.category(r, other.category)
        // Box points now vs everything after (an upper bonus completed now counts with the rest:
        // leaving the box open usually still gets it later).
        val dNow = (fa.points - fb.points).toDouble()
        val dEf = (fa.points + fa.bonusNow + fa.future) - (fb.points + fb.bonusNow + fb.future)
        val dFut = dEf - dNow
        val diff = best.value - other.value
        val bonusOpen = me.u < cap && Category.ALL.any { it.isUpper && !me.isFilled(it) }
        val dPb = fa.bonusProb - fb.bonusProb
        val bonusMatters = bonusOpen && !dPb.isNaN() && abs(dPb) >= 0.05

        val isDecided = decided(a.mode, best.value, other.value)
        val bOther = box(other.category)
        val bBest = box(best.category)
        val summary = when {
            isDecided -> "승패는 사실상 정해져서, 최종 점수를 조금이라도 높이는 칸을 골랐어요."
            tiny(a.mode, diff) -> "${Ko.wa(catNoun(other))} 거의 차이가 없어요."
            best.points == 0 -> "${bBest}에 0점을 넣는 게 손해가 가장 적어요."
            fa.bonusNow > 0 -> "${bBest}에 넣으면 보너스 ${rules.bonusPoints}점이 바로 확정돼요."
            bonusMatters && dPb >= 0.08 -> "보너스 받을 확률을 ${Ko.ro(Ko.pct(fa.bonusProb))} 지키는 선택이에요."
            dNow < -0.5 && dFut > 0.5 -> "지금 ${Ko.num(-dNow)}점을 덜 받더라도 ${Ko.eul(bOther)} 아껴 두는 선택이에요."
            dNow > 0.5 && dFut < -0.5 -> "남은 칸이 조금 아쉬워지더라도 지금 점수를 챙기는 선택이에요."
            else -> "${bBest}에 ${best.points}점 넣는 게 가장 좋아요."
        }

        val details = ArrayList<String>()
        details += if (isDecided) evFraming(best.expectedFinal, other.expectedFinal, catNoun(other))
        else framing(a.mode, best.value, other.value, catNoun(other))
        val reasons = ArrayList<String>()
        when {
            dNow < -0.5 && dFut > 0.5 && dEf > 0 -> {
                reasons += "${bOther}에 넣으면 지금 ${Ko.num(-dNow)}점을 더 받지만, ${Ko.eul(bOther)} 비워 두면 남은 게임에서 평균 ${Ko.num(dFut)}점을 더 기대할 수 있어요."
                if (other.category == Category.CHOICE) reasons += "초이스는 나중에 안 풀린 턴을 받아 주는 칸이거든요."
            }
            dNow > 0.5 && dFut < -0.5 && dEf > 0 ->
                reasons += "${bBest}에 넣으면 지금 ${Ko.num(dNow)}점을 더 받아요. 남은 칸의 기대 점수는 ${Ko.num(-dFut)}점 줄지만, 그보다 이득이 커요."
            dNow >= 1 && dFut >= 0.5 ->
                reasons += "${bBest}에 넣으면 지금 점수도 ${Ko.num(dNow)}점 더 받고, 남은 칸도 더 좋게 남아요."
            abs(dNow) < 1 && dFut >= 0.5 ->
                reasons += "받는 점수는 비슷하지만, 남은 칸이 더 좋게 남아요(남은 게임 기대 점수 ${Ko.num(dFut)}점 차이)."
            dNow >= 1 && dEf > 0 ->
                reasons += "${bBest}에 넣으면 지금 점수를 ${Ko.num(dNow)}점 더 받아요."
            dNow > 0.5 && dFut < -0.5 ->
                reasons += "${bBest}에 넣으면 지금 ${Ko.num(dNow)}점을 더 받는 대신 남은 칸의 기대 점수가 ${Ko.num(-dFut)}점 줄어요." +
                    if (strategic) "" else " 평균으로 따지면 거의 비슷해요."
            dNow < -0.5 && dFut > 0.5 ->
                reasons += "${bOther}에 넣으면 지금 ${Ko.num(-dNow)}점을 더 받지만, ${Ko.eul(bOther)} 비워 두면 남은 칸의 기대 점수가 ${Ko.num(dFut)}점 높아져요." +
                    if (strategic) "" else " 평균으로 따지면 거의 비슷해요."
        }
        if (best.points == 0) sacrificeNote(best.category)?.let { reasons += it }
        if (fb.bonusNow > 0 && fa.bonusNow == 0 && !fa.bonusProb.isNaN()) {
            reasons.add(
                0,
                "${bOther}에 넣으면 보너스가 바로 확정되지만, ${Ko.eul(bOther)} 비워 둬도 나중에 보너스를 받을 확률이 ${Ko.ieyo(Ko.pct(fa.bonusProb))}.",
            )
        }
        val extras = ArrayList<String>()
        if (strategic || (asOption && a.mode == Mode.WIN && other.expectedFinal - best.expectedFinal >= 0.3)) {
            val who = if (asOption) "이 선택" else catNoun(other)
            extras += strategy(a.mode, a.value, who, other.expectedFinal - best.expectedFinal, Double.NaN, Double.NaN, opp)
        }
        if (bonusMatters) {
            extras += if (dPb > 0) "${bBest}에 넣으면 보너스 받을 확률이 ${Ko.pct(fa.bonusProb)}, ${bOther}에 넣으면 ${Ko.ieyo(Ko.pct(fb.bonusProb))}."
            else "보너스만 보면 ${bOther}에 넣는 쪽이 낫지만(${Ko.pct(fb.bonusProb)} 대 ${Ko.pct(fa.bonusProb)}), 전체로는 이쪽이 이득이에요."
        }
        parNote(me, best.category, a.dice)?.let { extras += it }
        if (reasons.isEmpty() && extras.isEmpty() && !tiny(a.mode, diff)) {
            reasons += "지금 점수와 남은 칸을 함께 따져 보면 이쪽이 조금 더 나아요."
        }
        details += reasons.take(2)
        details += extras
        return Explanation(summary, details.take(3))
    }

    /** Why giving up [c] costs little. */
    private fun sacrificeNote(c: Category): String? {
        if (c.isUpper) {
            return if (c.face <= 2) "${Ko.eun(box(c))} 잘 나와도 최대 ${5 * c.face}점이라 지워도 손해가 작아요." else null
        }
        if (c == Category.CHOICE) return null
        val p = TurnAnalysis.chance(tables, c)
        if (p >= 0.35) return null
        return "${Ko.eun(c.label)} 한 턴에 작정하고 노려도 성공 확률이 ${Ko.pct(p)} 정도라, 지우는 부담이 비교적 작아요."
    }

    /** "6 네 개면 보너스 기준(눈마다 세 개)보다 6점 앞서요." */
    private fun parNote(me: Board, c: Category, dice: List<Int>): String? {
        if (!c.isUpper || me.u >= cap) return null
        val n = dice.count { it == c.face }
        if (n < 3) return null
        val tail = if (n == 3) "에 딱 맞아요" else "보다 ${(n - 3) * c.face}점 앞서요"
        return "${c.face} ${Ko.count(n)} 개면 보너스 기준(눈마다 세 개)$tail."
    }

    // ---- shared sentences -------------------------------------------------------------------------

    /** "기대 최종 점수로는 ‘5’에 10점 넣기보다 3점 높아요." */
    private fun evFraming(a: Double, b: Double, than: String): String =
        if (a - b < 0.05) "${Ko.wa(than)} 기대 점수도 거의 같아요."
        else "기대 최종 점수로는 ${than}보다 ${Ko.num(a - b)}점 높아요."

    private fun tiny(mode: Mode, d: Double) = if (mode == Mode.WIN) d < 0.0005 else d < 0.05

    private fun decided(mode: Mode, a: Double, b: Double) =
        mode == Mode.WIN && ((a > 0.9995 && b > 0.9995) || (a < 0.0005 && b < 0.0005))

    /** "6 6 5 5 남기기보다 승률이 2.1%p 높아요(54% 대 52%)." */
    private fun framing(mode: Mode, a: Double, b: Double, than: String): String {
        val d = a - b
        if (mode == Mode.WIN) {
            if (d < 0.0005) return "${Ko.wa(than)} 승률 차이가 0.1%p도 안 돼서 사실상 같아요."
            val (pa, pb) = Ko.pctPair(a, b)
            return "${than}보다 승률이 ${Ko.pp(d)} 높아요($pa 대 $pb)."
        }
        if (d < 0.05) return "${Ko.wa(than)} 기대 점수 차이가 거의 없어요."
        return "${than}보다 기대 최종 점수가 ${Ko.num(d)}점 높아요."
    }

    private fun optionSummary(mode: Mode, best: Double, x: Double, bestNoun: String): String {
        val d = best - x
        if (tiny(mode, d)) return "추천과 거의 차이가 없어요. 이렇게 해도 괜찮아요."
        if (mode == Mode.WIN) {
            val (pb, px) = Ko.pctPair(best, x)
            return "추천(${bestNoun})보다 승률이 ${Ko.pp(d)} 낮아요($px 대 $pb)."
        }
        return "추천(${bestNoun})보다 기대 최종 점수가 ${Ko.num(d)}점 낮아요."
    }

    private fun lossText(mode: Mode, d: Double) =
        if (mode == Mode.WIN) "승률 ${Ko.pp(d)} 손해" else "기대 점수 ${Ko.num(d)}점 손해"

    /** Why the win-maximising move differs from the max-expectation one ([rival] scores [dEf] more on average). */
    private fun strategy(mode: Mode, win: Double, rival: String, dEf: Double, sdA: Double, sdB: Double, opp: Board): String {
        val lead = "기대 점수만 보면 ${Ko.i(rival)} ${Ko.num(dEf)}점 높지만"
        if (mode != Mode.WIN) return "$lead, 이쪽이 더 나아요."
        return when {
            opp.isComplete -> "$lead, 상대가 ${opp.total}점으로 끝난 지금은 그 점수를 넘을 확률이 더 중요해요."
            !sdA.isNaN() && sdA > sdB + 0.7 && win < 0.5 -> "$lead, 지금은 불리한 상황이라 평균보다 한 방이 더 필요해요."
            !sdA.isNaN() && sdA < sdB - 0.7 && win > 0.5 -> "$lead, 지금은 유리한 상황이라 크게 무너지지 않는 쪽이 이길 확률이 더 높아요."
            win < 0.45 -> "$lead, 지금은 불리한 상황이라 평균 점수보다 이길 수 있는 점수를 노리는 게 중요해요."
            win > 0.55 -> "$lead, 지금은 앞서 있어서 평균 점수보다 지지 않는 게 중요해요."
            else -> "$lead, 이기고 지는 것만 따지면 이쪽이 더 나아요."
        }
    }

    // ---- names ------------------------------------------------------------------------------------

    /** A box as named in a sentence: ‘6’ for upper boxes, the label otherwise. */
    private fun box(c: Category) = if (c.isUpper) "‘${c.face}’" else c.label

    private fun dice(held: List<Int>) = held.joinToString(" ")

    private fun keepNoun(k: KeepOption) = when {
        k.held.isEmpty() -> "전부 다시 굴리기"
        k.held.size == 5 -> "여기서 멈추기"
        else -> "${dice(k.held)} 남기기"
    }

    private fun rerollNoun(k: KeepOption) =
        if (k.held.isEmpty()) "전부 다시 굴리기" else "${dice(k.held)}만 남기고 다시 굴리기"

    private fun keepIf(k: KeepOption) = when {
        k.held.isEmpty() -> "전부 다시 굴리면"
        k.held.size == 5 -> "여기서 멈추면"
        else -> "${Ko.eul(dice(k.held))} 남기면"
    }

    private fun catNoun(o: CategoryOption) = "${box(o.category)}에 ${o.points}점 넣기"

    // ---- situation --------------------------------------------------------------------------------

    /**
     * The state of the game from my side: [win] is my current P(win) (null in max-expectation
     * mode or when unknown).
     */
    fun situation(me: Board, opp: Board, win: Double?): Explanation {
        val ev = tables.ev
        val evMe = if (me.isComplete) 0.0 else ev[me.mask, me.u]
        val evOpp = if (opp.isComplete) 0.0 else ev[opp.mask, opp.u]
        val eMe = me.total + evMe
        val eOpp = opp.total + evOpp
        val d = me.total - opp.total
        val left = Category.COUNT - me.filledCount

        // The header already shows the probability: say what it means and why.
        val outlook = if (!opp.isComplete && !me.isComplete) {
            val e = eMe - eOpp
            when {
                abs(e) < 1.5 -> "점수판만 보면 예상 최종 점수는 거의 같아요."
                e > 0 -> "점수판만 보면 예상 최종 점수가 ${Ko.int(e)}점쯤 앞서요."
                else -> "점수판만 보면 예상 최종 점수가 ${Ko.int(-e)}점쯤 밀려요."
            }
        } else null
        var summary = when {
            opp.isComplete && d > 0 -> "이미 상대 점수를 넘었어요. 이긴 판이에요!"
            opp.isComplete && win != null && win < 0.0005 -> "아쉽지만 남은 칸으로는 상대 점수를 넘기 어려워요."
            opp.isComplete -> "남은 칸에서 ${opp.total - me.total + 1}점 이상 내면 이겨요."
            win != null -> scale(win) + (outlook?.let { " $it" } ?: "")
            else -> "예상 최종 점수는 나 ${Ko.int(eMe)}점, 상대 ${Ko.int(eOpp)}점이에요."
        }

        val details = ArrayList<String>()
        if (left == 1 && !me.isComplete) details += "이번이 마지막 턴이에요."
        if (me.filledCount > 0 || opp.filledCount > 0) {
            val now = when {
                d > 0 -> "지금 점수는 ${d}점 앞서 있어요"
                d < 0 -> "지금 점수는 ${-d}점 뒤져 있어요"
                else -> "지금 점수는 같아요"
            }
            val nowAnd = when {
                d > 0 -> "지금 점수는 ${d}점 앞서 있고"
                d < 0 -> "지금 점수는 ${-d}점 뒤져 있고"
                else -> "지금 점수는 같고"
            }
            if (opp.isComplete) {
                details += "$now. 상대는 ${opp.total}점으로 끝났어요."
            } else if (!me.isComplete) {
                val e = eMe - eOpp
                val flips = (d > 0 && e < -1.5) || (d < 0 && e > 1.5)
                val finals = "예상 최종 점수는 나 ${Ko.int(eMe)}점, 상대 ${Ko.int(eOpp)}점"
                details += when {
                    flips -> {
                        val nowBut = if (d > 0) "지금 점수는 ${d}점 앞서 있지만" else "지금 점수는 ${-d}점 뒤져 있지만"
                        "$nowBut, ${Ko.ieyo(finals)}."
                    }
                    else -> "$nowAnd, ${Ko.ieyo(finals)}."
                }
                val gap = evMe - evOpp
                if (abs(gap) >= 8) {
                    val who = if (gap > 0) "내가" else "상대가"
                    var s = "남은 칸의 기대 점수는 $who ${Ko.int(abs(gap))}점쯤 더 높아요."
                    val yMe = !me.isFilled(Category.YACHT)
                    val yOpp = !opp.isFilled(Category.YACHT)
                    if (yMe != yOpp && (gap > 0) == yMe) s += " ${if (yMe) "나는" else "상대는"} 아직 요트 칸이 남아 있거든요."
                    details += s
                }
            }
        }
        // Without a win probability the score comparison is the headline itself.
        if (win == null && !opp.isComplete && details.isNotEmpty() && details[0].startsWith("지금 점수")) {
            summary = details.removeAt(0)
        }
        bonusLine(me)?.let { details += it }
        if (!opp.isComplete && Category.COUNT - opp.filledCount <= 6) {
            oppRange(opp)?.let { (lo, hi) -> details += "상대 최종 점수는 대략 ${lo}~${hi}점 사이로 예상돼요." }
        }
        return Explanation(summary, details.take(3))
    }

    /** What a win probability means, in words (the number itself is shown next to it). */
    private fun scale(p: Double): String = when {
        p >= 0.995 -> "사실상 이긴 판이에요."
        p >= 0.9 -> "아주 유리해요."
        p >= 0.72 -> "꽤 유리해요."
        p >= 0.57 -> "조금 유리해요."
        p > 0.43 -> "팽팽한 승부예요."
        p > 0.28 -> "조금 불리해요."
        p > 0.1 -> "꽤 불리해요."
        p > 0.005 -> "역전하려면 운이 꽤 따라야 해요."
        else -> "뒤집기는 사실상 어려워요."
    }

    private fun bonusLine(me: Board): String? {
        if (me.bonus > 0) return null
        val upper = Category.ALL.filter { it.isUpper }
        val filled = upper.filter { me.isFilled(it) }
        if (filled.isEmpty() || filled.size == 6) return null
        val pb = tables.bonus?.get(me.mask, me.u) ?: return null
        if (pb < 0.005) return "보너스는 이제 받기 어려워요."
        val need = cap - me.upperSum
        val par = 3 * filled.sumOf { it.face }
        val vsPar = me.upperSum - par
        val pace = when {
            vsPar > 0 -> "기준(눈마다 세 개)보다 ${vsPar}점 앞서 있어서"
            vsPar < 0 -> "기준(눈마다 세 개)보다 ${-vsPar}점 모자라서"
            else -> "딱 기준(눈마다 세 개)대로라서"
        }
        val chance = if (pb >= 0.995) "거의 확실해요" else "받을 확률은 ${Ko.ieyo(Ko.pct(pb))}"
        return "보너스까지 ${need}점 남았어요. $pace $chance."
    }

    /** 10th and 90th percentile of the opponent's final score. */
    private fun oppRange(opp: Board): Pair<Int, Int>? {
        val dist = tables.dist ?: return null
        val p = dist.pmf(opp.mask, opp.u)
        val lo = dist.lo(opp.mask, opp.u)
        var acc = 0.0
        var a = -1
        var b = -1
        for (i in p.indices) {
            acc += p[i]
            if (a < 0 && acc >= 0.1) a = i
            if (b < 0 && acc >= 0.9) b = i
        }
        if (a < 0 || b < 0) return null
        return opp.total + lo + a to opp.total + lo + b
    }

    // ---- opponent ---------------------------------------------------------------------------------

    /**
     * Comment on the opponent's last box. [winBefore] is my P(win) after my previous move and
     * [move].winAfter my P(win) now, when known. [who] names the opponent ("상대", "AI").
     */
    fun opponentMove(oppAfter: Board, move: MoveRecord, winBefore: Double?, who: String = "상대"): String {
        val c = move.category
        val pts = move.points
        val before = oppAfter.without(c)
        val bonusGain = if (before.bonus == 0 && oppAfter.bonus > 0) rules.bonusPoints else 0
        val parts = ArrayList<String>()
        parts += when {
            c == Category.YACHT && pts > 0 -> "${Ko.i(who)} 요트에 성공했어요(${pts}점)!"
            pts == 0 -> "${Ko.i(who)} ${Ko.eul(box(c))} 0점으로 지웠어요."
            c.isUpper -> "${Ko.i(who)} ${box(c)}에 ${pts}점(${Ko.count(pts / c.face)} 개)을 넣었어요."
            else -> "${Ko.i(who)} ${box(c)}에 ${pts}점을 넣었어요."
        }
        if (bonusGain > 0) parts += "보너스 ${bonusGain}점도 확정됐어요."
        val evBefore = tables.ev[before.mask, before.u]
        val evAfter = if (oppAfter.isComplete) 0.0 else tables.ev[oppAfter.mask, oppAfter.u]
        val luck = pts + bonusGain + evAfter - evBefore
        if (luck >= 4) parts += "$who 입장에선 평소보다 ${Ko.int(luck)}점쯤 잘 풀린 턴이에요."
        else if (luck <= -4) parts += "$who 입장에선 평소보다 ${Ko.int(-luck)}점쯤 아쉬운 턴이에요."
        val after = move.winAfter
        if (after != null && winBefore != null) {
            val dw = after - winBefore
            if (abs(dw) >= 0.005) {
                val (b, a) = Ko.pctPair(winBefore, after)
                val so = if ((luck >= 4 && dw < 0) || (luck <= -4 && dw > 0)) "그래서 " else ""
                parts += "${so}내 승률은 ${b}에서 ${Ko.ro(a)} ${if (dw > 0) "올랐어요" else "내려갔어요"}."
            } else {
                parts += "내 승률은 ${Ko.ro(Ko.pct(after))} 거의 그대로예요."
            }
        } else if (after != null) {
            parts += "지금 내 승률은 ${Ko.ieyo(Ko.pct(after))}."
        }
        return parts.joinToString(" ")
    }

    /**
     * The AI's reasons for its turn (vs AI), one line per decision: the dice it saw, what it did,
     * and why, as a recommendation would put it. [opp] is the AI's opponent (me).
     */
    fun aiTurn(turn: PlannedTurn, opp: Board, throws: List<List<Int>>, held: List<List<Int>>, category: Category): List<String> {
        val out = ArrayList<String>()
        for ((k, dice) in throws.withIndex()) {
            val action = if (k < held.size) yacht.engine.game.Game.keepText(held[k])
            else yacht.engine.game.Game.scoreText(category, rules.score(dice, category))
            val why = runCatching { advice(turn, opp, turn.advise(k + 1, dice)).summary }.getOrNull()
            out += "${k + 1}번째 ${dice.sorted().joinToString(" ")} → $action" + (why?.let { ": $it" } ?: "")
        }
        return out
    }

    // ---- whole game -------------------------------------------------------------------------------

    fun gameSummary(s: GameState): Explanation {
        val me = s.me.total
        val opp = s.opp.total
        val summary = when {
            me > opp -> "$me 대 $opp, ${me - opp}점 차로 이겼어요!"
            me == opp -> "$me 대 $opp, 비겼어요."
            else -> "$me 대 $opp, ${opp - me}점 차로 졌어요."
        }
        val details = ArrayList<String>()
        val decisions = s.decisions
        if (decisions.isNotEmpty()) {
            val mistakes = decisions.filter { it.isMistake }
            if (mistakes.isEmpty()) {
                details += "모든 판단을 추천대로 뒀어요."
            } else {
                val mode = mistakes.first().mode
                val sum = mistakes.filter { it.mode == mode }.sumOf { it.loss }
                val total = if (mode == Mode.WIN) "승률 ${Ko.eul(Ko.pp(sum))}" else "기대 점수 ${Ko.num(sum)}점을"
                val all = if (mistakes.size > 1) "모두 합쳐 " else ""
                var line = "추천과 다르게 둔 수가 ${mistakes.size}번 있었고, $all$total 손해 봤어요."
                val worst = mistakes.maxBy { it.loss }
                if (mistakes.size > 1 && (mode != Mode.WIN || worst.loss >= 0.005)) {
                    line += " 가장 아까운 수는 ${worst.turn}턴의 ${Ko.ieosseoyo(Ko.quote(worst.chosen))}(추천: ${worst.best})."
                }
                details += line
            }
        }
        turningPoint(s)?.let { details += it }
        val wins = s.moves.mapNotNull { it.winAfter }
        if (wins.size >= 4) {
            val lo = wins.min()
            val hi = wins.max()
            if (me > opp && lo < 0.3) details += "한때 승률이 ${Ko.pct(lo)}까지 떨어졌는데 역전했어요!"
            else if (me < opp && hi > 0.7) details += "한때 승률이 ${Ko.pct(hi)}까지 올랐는데 아쉽게 졌어요."
        }
        return Explanation(summary, details.take(3))
    }

    /** The move after which my P(win) changed the most. */
    private fun turningPoint(s: GameState): String? {
        var best: Triple<Int, Double, Double>? = null
        var prev: Double? = null
        for ((i, m) in s.moves.withIndex()) {
            val w = m.winAfter
            if (w != null && prev != null && (best == null || abs(w - prev) > abs(best.third - best.second))) {
                best = Triple(i, prev, w)
            }
            prev = w
        }
        val (i, from, to) = best ?: return null
        if (abs(to - from) < 0.1) return null
        val m = s.moves[i]
        val turn = s.moves.take(i + 1).count { it.side == m.side }
        val what = when {
            m.points == 0 -> "${Ko.eul(box(m.category))} 0점으로 지웠을 때"
            else -> "${box(m.category)}에 ${m.points}점을 넣었을 때"
        }
        val who = if (m.side == Side.ME) "${turn}턴에 내가" else "${turn}턴에 상대가"
        val (a, b) = Ko.pctPair(from, to)
        return "가장 큰 전환점은 $who $what${if (what.endsWith("때")) "예요" else ""}(내 승률 $a → $b)."
    }
}

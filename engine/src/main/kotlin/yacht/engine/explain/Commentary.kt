package yacht.engine.explain

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.Mode
import yacht.engine.PlannedTurn
import yacht.engine.Tables
import yacht.engine.TurnOutcomes
import yacht.engine.TurnPlan
import yacht.engine.game.MoveRecord
import yacht.engine.game.label
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a comment feels: colours the line in the app. */
enum class Tone { GOOD, BAD, HOT, CALM, INFO }

/** One line of live commentary: a short tag ("🎲 굴림") and a sentence. */
data class Comment(val tag: String, val text: String, val tone: Tone = Tone.INFO)

/**
 * One throw of the AI's turn as it is played back: the dice, what the AI did with them, my
 * P(win) once that is known, and the commentary for it.
 */
data class AiStep(
    /** 1..3 */
    val throwNo: Int,
    val dice: List<Int>,
    /** Dice held for the next throw; null when the AI wrote its box after this throw. */
    val held: List<Int>?,
    val category: Category?,
    val points: Int,
    /** My P(win) after this step (WIN mode). */
    val myWin: Double?,
    val lines: List<Comment>,
)

/**
 * Live commentary for the game screen: how lucky a throw was, what the turn is heading for, how
 * much rides on it, and the AI's turn throw by throw. Every number is computed here from the
 * plans and tables; the words follow the numbers.
 */
class Commentary(private val tables: Tables) {
    private companion object {
        /** A hand is celebrated only when this throw had at most this chance of making it. */
        const val RARE = 0.12

        /** A turn is a decider when its 10%-90% outcomes are this far apart in P(win). */
        const val HOT_SPREAD = 0.25
    }

    private val rules = tables.rules
    private val scores = tables.scores
    private val cap = rules.bonusThreshold

    // ---- my turn ---------------------------------------------------------------------------------

    /**
     * Before my first throw: what this turn may bring and how much rides on it. [history] is my
     * P(win) after each earlier box (both sides, oldest first) for the momentum line.
     */
    fun turnPreview(turn: PlannedTurn, opp: Board, history: List<Double> = emptyList()): List<Comment> {
        val plan = turn.active
        val out = ArrayList<Comment>()
        val final = startFinals(plan)
        stakes(turn, final, "이번 턴", quiet = true)?.let { out += it }
        yachtChance(turn, final)?.let { out += it }
        goals(turn, final, minTop = 0.15)?.let { out += it }
        if (turn.mode == Mode.WIN) momentum(history, turn.startValue)?.let { out += it }
        bonusRace(turn.me, opp)?.let { out += it }
        return out.take(3)
    }

    /**
     * Right after one of my throws ([rollsDone] = 1..3, [dice] on the table): rarity, luck
     * against everything this throw could have shown, and where the rest of the turn is going.
     * [heldBefore]: the dice held for this throw (empty for the first throw). [factsOnly] keeps
     * to dice facts without the AI's evaluation (playing alone).
     */
    fun afterThrow(
        turn: PlannedTurn,
        rollsDone: Int,
        dice: List<Int>,
        heldBefore: List<Int>,
        factsOnly: Boolean = false,
    ): List<Comment> {
        require(rollsDone in 1..3 && dice.size == 5)
        val plan = turn.active
        val r = Dice.rollIndex(dice)
        val keep = Dice.keepIndex(Dice.countsOf(heldBefore))
        val dist = throwDist(rollsDone, keep)
        val out = ArrayList<Comment>()
        rareHand(turn.me, dist, r, rollsDone, heldBefore, "")?.let { out += it }
        if (factsOnly) return out
        throwLuck(turn, plan, rollsDone, dist, r, "")?.let { out += it }
        if (rollsDone < 3) {
            val best = plan.bestKeep(rollsDone, r)
            if (best != Dice.rollAsKeep[r]) {
                val final = plan.finalRolls(rollsDone, best)
                stakes(turn, final, "남은 굴림", quiet = true)?.let { out += it }
                goals(turn, final, minTop = 0.0)?.let { out += it }
            }
        }
        return out.take(4)
    }

    // ---- the AI's turn ---------------------------------------------------------------------------

    /**
     * The AI's turn, throw by throw, from my side. [aiTurn] is the AI's own plan (it plays
     * against me); [winOf] gives my P(win) once the AI's card is [Board] (WIN mode), and
     * [winBefore] my P(win) before its turn. [reasons] holds the AI's reason per throw.
     */
    fun aiSteps(
        aiTurn: PlannedTurn,
        ai: Board,
        throws: List<List<Int>>,
        held: List<List<Int>>,
        category: Category,
        winOf: ((Board) -> Double)?,
        winBefore: Double?,
        reasons: List<String?>? = null,
        /** The AI may play worse than [aiTurn] (a league AI): say when it departs from it. */
        judge: Boolean = false,
        /** How the AI is named in the lines ("AI", or the league AI's name). */
        who: String = "AI",
        /** Whose P(win) [winOf] gives: "내" for me, or a name when watching two AIs. */
        viewer: String = "내",
    ): List<AiStep> {
        val plan = aiTurn.active
        val steps = ArrayList<AiStep>()
        val memo = HashMap<Board, Double>()
        fun myWin(card: Board): Double? = winOf?.let { f -> memo.getOrPut(card) { f(card) } }
        var prevWin = winBefore
        var keep = Dice.EMPTY_KEEP
        var prevHeld: List<Int> = emptyList()
        for ((k, faces) in throws.withIndex()) {
            val rollsDone = k + 1
            val r = Dice.rollIndex(faces)
            val dist = throwDist(rollsDone, keep)
            val lines = ArrayList<Comment>()
            reasons?.getOrNull(k)?.let { lines += Comment("🤖 AI의 생각", it) }
            rareHand(ai, dist, r, rollsDone, prevHeld, "$who ")?.let { lines += it }
            throwLuck(aiTurn, plan, rollsDone, dist, r, who)?.let { lines += it }
            val holds = k < held.size
            if (judge) judgeLine(aiTurn, plan, rollsDone, r, if (holds) held[k] else null, if (holds) null else category, who)?.let { lines += it }
            val win: Double?
            if (holds) {
                val nextKeep = Dice.keepIndex(Dice.countsOf(held[k]))
                val final = plan.finalRolls(rollsDone, nextKeep)
                win = expectWin(ai, plan, final, ::myWin)
                aiChase(ai, plan, final, win, ::myWin, who, viewer)?.let { lines += it }
                keep = nextKeep
                prevHeld = held[k]
            } else {
                win = myWin(ai.with(category, scores[r, category.ordinal]))
            }
            winLine(prevWin, win, viewer)?.let { lines += it }
            steps += AiStep(
                rollsDone, faces.sorted(), if (holds) held[k] else null, if (holds) null else category,
                if (holds) 0 else scores[r, category.ordinal], win, lines,
            )
            prevWin = win ?: prevWin
        }
        return steps
    }

    /**
     * "😮 실수! 정석은 ‘6 6 6 남기기’였어요": the AI held [held] (or wrote [category]) after throw
     * [rollsDone] where the reference plan does clearly better.
     */
    private fun judgeLine(turn: PlannedTurn, plan: TurnPlan, rollsDone: Int, r: Int, held: List<Int>?, category: Category?, who: String): Comment? {
        val win = turn.mode == Mode.WIN
        val bestValue = turn.shown(plan.rollValue(rollsDone, r))
        val chosen: Double
        val text: String
        if (held != null) {
            val k = Dice.keepIndex(Dice.countsOf(held))
            chosen = turn.shown(plan.keepValue(rollsDone, k))
            val best = plan.bestKeep(rollsDone, r)
            if (best == k) return null
            text = if (best == Dice.rollAsKeep[r]) "여기서 멈추고 ${plan.bestCategory(r).label.let { box(it) }}에 넣는 게" else "‘${keepName(Dice.keepFaces(best))}’가"
        } else {
            val c = category ?: return null
            chosen = turn.shown(plan.categoryValue(r, c.ordinal))
            val keepGoing = rollsDone < 3 && plan.bestKeep(rollsDone, r) != Dice.rollAsKeep[r]
            val bestCat = plan.bestCategory(r)
            text = when {
                keepGoing -> "멈추지 말고 ‘${keepName(Dice.keepFaces(plan.bestKeep(rollsDone, r)))}’로 더 굴리는 게"
                bestCat != c -> "${box(bestCat.label)}에 ${scores[r, bestCat.ordinal]}점을 넣는 게"
                else -> return null
            }
        }
        val loss = bestValue - chosen
        if (loss < (if (win) 0.01 else 1.0)) return null
        val cost = if (win) "$who 승률 ${Ko.pp(loss)} 손해" else "평균 ${Ko.num(loss)}점 손해"
        return Comment("😮 $who 실수", "$text 정석이었어요($cost).", Tone.GOOD)
    }

    private fun keepName(faces: List<Int>) = if (faces.isEmpty()) "모두 다시" else faces.joinToString(" ") + " 남기기"

    private fun box(label: String) = if (label.length == 1) "‘$label’" else label

    /** My expected P(win) when the AI's turn ends with a roll from [final] written by its plan. */
    private fun expectWin(ai: Board, plan: TurnPlan, final: DoubleArray, myWin: (Board) -> Double?): Double? {
        var w = 0.0
        for (rf in 0 until Dice.ROLLS) {
            val p = final[rf]
            if (p == 0.0) continue
            val c = plan.bestCategory(rf)
            w += p * (myWin(ai.with(c, scores[rf, c.ordinal])) ?: return null)
        }
        return w
    }

    /** "AI가 요트를 노려요(성공 확률 17%). 성공하면 내 승률 21%." */
    private fun aiChase(ai: Board, plan: TurnPlan, final: DoubleArray, win: Double?, myWin: (Board) -> Double?, who: String, viewer: String): Comment? {
        val made = DoubleArray(Category.COUNT)
        val winMass = DoubleArray(Category.COUNT)
        for (rf in 0 until Dice.ROLLS) {
            val p = final[rf]
            if (p == 0.0) continue
            val c = plan.bestCategory(rf)
            val pts = scores[rf, c.ordinal]
            if (!madeHand(c, rf, pts)) continue
            made[c.ordinal] += p
            winMass[c.ordinal] += p * (myWin(ai.with(c, pts)) ?: 0.0)
        }
        if (win == null) return null
        // The target that would cost me the most, weighted by its chance.
        val c = (0 until Category.COUNT).filter { it != Category.CHOICE.ordinal && made[it] in 0.03..0.95 }
            .maxByOrNull { made[it] * (win - winMass[it] / made[it]) } ?: return null
        val p = made[c]
        val ifMade = winMass[c] / p
        if (win - ifMade < 0.03) return null
        val goal = goalName(Category.of(c))
        val tone = if (win - ifMade >= 0.1) Tone.HOT else Tone.BAD
        return Comment("🎯 노림수", "${Ko.i(who)} ${goal}에 성공하면(확률 ${Ko.pct(p)}) $viewer 승률은 ${Ko.ro(Ko.pct(ifMade))} 떨어져요.", tone)
    }

    private fun winLine(before: Double?, after: Double?, viewer: String = "내"): Comment? {
        if (before == null || after == null) return null
        if (decided(before) && decided(after)) return null
        val d = after - before
        val (b, a) = Ko.pctPair(before, after)
        return when {
            abs(d) < 0.005 -> Comment("📊 승률", "$viewer 승률은 ${Ko.ro(a)} 거의 그대로예요.", Tone.CALM)
            d > 0 -> Comment("📊 승률", "$viewer 승률 $b → $a (+${Ko.pp(d)})", Tone.GOOD)
            else -> Comment("📊 승률", "$viewer 승률 $b → $a (−${Ko.pp(d)})", Tone.BAD)
        }
    }

    // ---- the opponent's result ---------------------------------------------------------------------

    /**
     * After the opponent wrote [move] (its card is now [oppAfter]): how its turn ranks among all
     * the turns it could have had, and the big boxes it still has.
     */
    fun opponentResult(oppAfter: Board, move: MoveRecord, who: String = "상대"): List<Comment> {
        val out = ArrayList<Comment>()
        val before = oppAfter.without(move.category)
        val ev = tables.ev
        val evBefore = ev[before.mask, before.u]
        val bonusGain = if (before.bonus == 0 && oppAfter.bonus > 0) rules.bonusPoints else 0
        val evAfter = if (oppAfter.isComplete) 0.0 else ev[oppAfter.mask, oppAfter.u]
        val luck = move.points + bonusGain + evAfter - evBefore
        val oc = TurnOutcomes.of(tables, before.mask, before.u)
        var better = 0.0
        var same = 0.0
        for (k in 0 until oc.size) {
            val c = oc.category[k]
            val l = oc.gain[k] + (if (before.mask or (1 shl c) == Category.FULL_MASK) 0.0 else ev[before.mask or (1 shl c), oc.u2[k]]) - evBefore
            when {
                l > luck + 1e-6 -> better += oc.prob[k]
                l > luck - 1e-6 -> same += oc.prob[k]
            }
        }
        val top = better + same / 2
        when {
            top <= 0.1 -> out += Comment("🎲 상대 운", "상위 ${Ko.pct(top.coerceAtLeast(0.001))}: 이만큼 잘 풀리는 턴은 ${oneIn(top.coerceAtLeast(0.001))} 나와요.", Tone.BAD)
            top >= 0.9 -> out += Comment("🎲 상대 운", "하위 ${Ko.pct((1 - top).coerceAtLeast(0.001))}: 이만큼 안 풀리는 턴은 ${oneIn((1 - top).coerceAtLeast(0.001))} 나와요.", Tone.GOOD)
        }
        threat(oppAfter, who)?.let { out += it }
        return out
    }

    /** The opponent's open boxes that can still swing the game. */
    private fun threat(opp: Board, who: String): Comment? {
        val left = Category.COUNT - opp.filledCount
        if (left < 2 || left > 6) return null
        val big = listOf(Category.YACHT, Category.LARGE_STRAIGHT, Category.FOUR_OF_A_KIND, Category.FULL_HOUSE).filter { !opp.isFilled(it) }
        if (big.isEmpty()) return Comment("🛡️ 남은 칸", "${who}에게는 이제 큰 점수 칸이 없어요. 남은 ${left}칸에서 평균 ${Ko.int(tables.ev[opp.mask, opp.u])}점쯤이에요.", Tone.CALM)
        val names = big.joinToString(", ") { it.label }
        return Comment("🧨 남은 칸", "${who}에게 아직 $names 칸이 남아 있어요(남은 ${left}칸 평균 ${Ko.int(tables.ev[opp.mask, opp.u])}점).")
    }

    // ---- pieces ------------------------------------------------------------------------------------

    /** A P(win) that leaves nothing to talk about. */
    private fun decided(p: Double) = p < 0.005 || p > 0.995

    /** Distribution of the roll after throw [rollsDone] when [keep] was held for it. */
    private fun throwDist(rollsDone: Int, keep: Int): DoubleArray {
        if (rollsDone == 1) return Dice.firstRoll
        val d = DoubleArray(Dice.ROLLS)
        for (j in Dice.tStart[keep] until Dice.tStart[keep + 1]) d[Dice.tRoll[j]] += Dice.tProb[j]
        return d
    }

    /** Distribution of the final roll of a turn from its start, following [plan]. */
    private fun startFinals(plan: TurnPlan): DoubleArray {
        val out = DoubleArray(Dice.ROLLS)
        for (r in 0 until Dice.ROLLS) {
            val p = Dice.firstRoll[r]
            if (p == 0.0) continue
            val f = plan.finalRolls(1, plan.bestKeep(1, r))
            for (x in 0 until Dice.ROLLS) out[x] += p * f[x]
        }
        return out
    }

    private enum class Hand(val label: String) {
        YACHT("요트"), FOUR("포카드"), LARGE("L.스트레이트"), FULL("풀하우스"), SMALL("S.스트레이트"), OTHER("")
    }

    private fun hand(r: Int): Hand {
        val c = Dice.rollCounts[r]
        val max = c.max()
        val run = longestRun(c)
        return when {
            max == 5 -> Hand.YACHT
            max == 4 -> Hand.FOUR
            run == 5 -> Hand.LARGE
            max == 3 && c.count { it == 2 } == 1 -> Hand.FULL
            run == 4 -> Hand.SMALL
            else -> Hand.OTHER
        }
    }

    private fun longestRun(c: IntArray): Int {
        var best = 0
        var cur = 0
        for (f in 0 until 6) {
            cur = if (c[f] > 0) cur + 1 else 0
            best = maxOf(best, cur)
        }
        return best
    }

    /** Rolls that count as having made [h] (a Yacht also makes four of a kind and a full house). */
    private fun makes(r: Int, h: Hand): Boolean {
        val x = hand(r)
        return x == h || when (h) {
            Hand.FOUR, Hand.FULL -> x == Hand.YACHT
            Hand.SMALL -> x == Hand.LARGE
            else -> false
        }
    }

    /** Is the box for hand [h] still open (for four of a kind: that box or the face's upper box)? */
    private fun open(me: Board, h: Hand, r: Int): Boolean = when (h) {
        Hand.YACHT -> !me.isFilled(Category.YACHT)
        Hand.FOUR -> !me.isFilled(Category.FOUR_OF_A_KIND) ||
            Dice.rollCounts[r].indices.any { Dice.rollCounts[r][it] >= 4 && !me.isFilled(Category.of(it)) }
        Hand.LARGE -> !me.isFilled(Category.LARGE_STRAIGHT) || !me.isFilled(Category.SMALL_STRAIGHT)
        Hand.FULL -> !me.isFilled(Category.FULL_HOUSE)
        Hand.SMALL -> !me.isFilled(Category.SMALL_STRAIGHT)
        Hand.OTHER -> false
    }

    /** "첫 굴림부터 풀하우스! 약 26번에 한 번꼴이에요." [who] is "" for me or "AI ". */
    private fun rareHand(me: Board, dist: DoubleArray, r: Int, rollsDone: Int, heldBefore: List<Int>, who: String): Comment? {
        val h = hand(r)
        if (h == Hand.OTHER) return null
        var p = 0.0
        for (x in 0 until Dice.ROLLS) if (dist[x] > 0 && makes(x, h)) p += dist[x]
        if (p > RARE) return null
        val isOpen = open(me, h, r)
        val subject = if (who.isEmpty()) "" else who
        if (!isOpen) {
            if (h != Hand.YACHT && h != Hand.LARGE) return null
            val box = if (h == Hand.YACHT) "요트" else "스트레이트"
            return Comment("😅 아쉽다", "${subject}${Ko.i(h.label)} 나왔지만 $box 칸은 이미 찼어요.", Tone.INFO)
        }
        val tone = if (who.isEmpty()) Tone.GOOD else Tone.BAD
        val bang = if (h == Hand.YACHT) "!!!" else "!"
        val text = if (rollsDone == 1) {
            "${subject}첫 굴림부터 ${h.label}$bang ${oneIn(p)} 나오는 굴림이에요."
        } else {
            val kept = if (heldBefore.isEmpty()) "모두 다시 굴려서" else "${Ko.eul(heldBefore.joinToString(" "))} 남기고"
            "${subject}${h.label}$bang $kept ${Ko.i(h.label)} 나올 확률은 ${Ko.ieosseoyo(Ko.pct(p))}."
        }
        return Comment(if (h == Hand.YACHT) "🎉 요트" else "✨ 족보", text, tone)
    }

    /** "1,296번에 한 번꼴로", "약 25번에 한 번꼴로" */
    private fun oneIn(p: Double): String {
        val n = 1 / p
        if (abs(n - n.roundToInt()) < 0.01) return String.format(Locale.ROOT, "%,d", n.roundToInt()) + "번에 한 번꼴로"
        val step = when {
            n < 20 -> 1
            n < 100 -> 5
            n < 1000 -> 10
            else -> 100
        }
        return "약 " + String.format(Locale.ROOT, "%,d", (n / step).roundToInt() * step) + "번에 한 번꼴로"
    }

    /**
     * How this throw ranks among everything it could have shown, by the plan's value of the
     * position after it, and how it moved that value. [who] is "" for me or "AI".
     */
    private fun throwLuck(turn: PlannedTurn, plan: TurnPlan, rollsDone: Int, dist: DoubleArray, r: Int, who: String): Comment? {
        fun v(x: Int) = turn.shown(plan.rollValue(rollsDone, x))
        val actual = v(r)
        var before = 0.0
        var better = 0.0
        var same = 0.0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        val eps = 1e-7 * maxOf(1.0, abs(actual))
        for (x in 0 until Dice.ROLLS) {
            val p = dist[x]
            if (p == 0.0) continue
            val vx = v(x)
            before += p * vx
            lo = minOf(lo, vx)
            hi = maxOf(hi, vx)
            when {
                vx > actual + eps -> better += p
                vx > actual - eps -> same += p
            }
        }
        val win = turn.mode == Mode.WIN
        if (hi - lo < (if (win) 0.002 else 0.2)) return null
        if (win && decided(before) && decided(actual)) return null
        val top = (better + same / 2).coerceIn(0.0, 1.0)
        val change = when {
            win && abs(actual - before) < 0.0005 -> "승률은 ${Ko.pct(actual)} 그대로"
            win -> {
                val (b, a) = Ko.pctPair(before, actual)
                "승률 $b → $a"
            }
            abs(actual - before) < 0.05 -> "예상 최종 점수는 ${Ko.num(actual)}점 그대로"
            else -> "예상 최종 ${Ko.num(before)} → ${Ko.num(actual)}점"
        }
        val me = who.isEmpty()
        val topText = Ko.pct(top.coerceAtLeast(0.001))
        val bottomText = Ko.pct((1 - top).coerceAtLeast(0.001))
        return when {
            top <= 0.1 -> Comment(
                "🎲 굴림 운",
                if (me) "상위 $topText 굴림이에요! $change." else "${who}에게 상위 $topText 굴림이 나왔어요.",
                if (me) Tone.GOOD else Tone.BAD,
            )
            top >= 0.9 -> Comment(
                "🎲 굴림 운",
                if (me) "하위 $bottomText 굴림이에요… $change." else "$who 굴림이 하위 ${Ko.ieyo(bottomText)}. 기회예요!",
                if (me) Tone.BAD else Tone.GOOD,
            )
            !me -> null
            // Rank and change can disagree: a rare big result (a Yacht) lifts the average above most outcomes.
            top <= 0.35 && actual >= before -> Comment("🎲 굴림 운", "괜찮은 굴림이에요(상위 ${Ko.pct(top)}). $change.", Tone.GOOD)
            top >= 0.65 && actual <= before -> Comment("🎲 굴림 운", "조금 아쉬운 굴림이에요(상위 ${Ko.pct(top)}). $change.", Tone.INFO)
            top in 0.35..0.65 -> Comment("🎲 굴림 운", "평범한 굴림이에요(상위 ${Ko.pct(top)}). $change.", Tone.CALM)
            else -> Comment("🎲 굴림 운", "상위 ${Ko.pct(top)} 굴림이에요. $change.", Tone.CALM)
        }
    }

    /**
     * How much rides on the rest of the turn when it ends with a roll from [final]: the spread
     * of the plan's value over the outcomes, and the main target's best and worst case.
     */
    private fun stakes(turn: PlannedTurn, final: DoubleArray, what: String, quiet: Boolean): Comment? {
        val plan = turn.active
        val vals = ArrayList<Pair<Double, Double>>()
        for (rf in 0 until Dice.ROLLS) {
            val p = final[rf]
            if (p > 0) vals += turn.shown(plan.rollValue(3, rf)) to p
        }
        vals.sortBy { it.first }
        fun q(x: Double): Double {
            var acc = 0.0
            for ((v, p) in vals) {
                acc += p
                if (acc >= x) return v
            }
            return vals.last().first
        }
        val lo = q(0.1)
        val hi = q(0.9)
        val win = turn.mode == Mode.WIN
        if (win) {
            if (decided(lo) && decided(hi)) return null
            val (a, b) = Ko.pctPair(lo, hi)
            return when {
                hi - lo >= HOT_SPREAD -> Comment("⚡ 승부처", "$what 결과에 따라 승률이 $a~${Ko.ro(b)} 크게 갈려요.", Tone.HOT)
                quiet -> null
                hi - lo <= 0.03 -> Comment("🧊 차분하게", "$what 결과가 어떻게 나와도 승률은 $a~$b 사이예요.", Tone.CALM)
                else -> Comment("⚖️ 이번 턴", "$what 결과에 따라 승률이 $a~$b 사이에서 움직여요.")
            }
        }
        val spread = hi - lo
        return when {
            spread >= 25 -> Comment("⚡ 승부처", "$what 결과에 따라 예상 최종 점수가 ${Ko.int(lo)}~${Ko.int(hi)}점으로 크게 갈려요.", Tone.HOT)
            quiet -> null
            else -> Comment("⚖️ 이번 턴", "$what 결과에 따라 예상 최종 점수는 ${Ko.int(lo)}~${Ko.int(hi)}점이에요.")
        }
    }

    /** "요트 찬스: 이번 턴 요트 확률 4.6%, 나오면 승률 71%" when a Yacht would swing the game. */
    private fun yachtChance(turn: PlannedTurn, final: DoubleArray): Comment? {
        if (turn.me.isFilled(Category.YACHT)) return null
        val plan = turn.active
        var p = 0.0
        var mass = 0.0
        for (rf in 0 until Dice.ROLLS) {
            val q = final[rf]
            if (q == 0.0 || plan.bestCategory(rf) != Category.YACHT || scores[rf, Category.YACHT.ordinal] == 0) continue
            p += q
            mass += q * turn.shown(plan.rollValue(3, rf))
        }
        if (p < 0.01) return null
        val ifYacht = mass / p
        val now = turn.startValue
        // Every turn has a Yacht chance while the box is open: only worth saying when it swings a lot.
        return if (turn.mode == Mode.WIN) {
            if (ifYacht - now < 0.25) return null
            Comment("🎉 요트 찬스", "이번 턴에 요트가 나올 확률은 ${Ko.ieyo(Ko.pct(p))}. 나오면 승률이 ${Ko.pct(now)} → ${Ko.ro(Ko.pct(ifYacht))} 뛰어요.", Tone.HOT)
        } else {
            if (turn.me.filledCount < 6) return null
            Comment("🎉 요트 찬스", "이번 턴에 요트가 나올 확률은 ${Ko.ieyo(Ko.pct(p))}. 나오면 예상 최종 점수가 ${Ko.int(ifYacht)}점이 돼요.")
        }
    }

    /** Where the turn is heading: the boxes it most likely ends in, with the 0-point risk. */
    private fun goals(turn: PlannedTurn, final: DoubleArray, minTop: Double): Comment? {
        val plan = turn.active
        val made = DoubleArray(Category.COUNT)
        var zero = 0.0
        var choice = 0.0
        for (rf in 0 until Dice.ROLLS) {
            val p = final[rf]
            if (p == 0.0) continue
            val c = plan.bestCategory(rf)
            val pts = scores[rf, c.ordinal]
            when {
                pts == 0 -> zero += p
                c == Category.CHOICE -> choice += p
                madeHand(c, rf, pts) -> made[c.ordinal] += p
            }
        }
        val top = (0 until Category.COUNT).filter { made[it] >= 0.05 }.sortedByDescending { made[it] }.take(3)
        if (top.isEmpty() || made[top[0]] < minTop || made[top[0]] >= 0.9) return null
        val parts = top.map { "${goalName(Category.of(it))} ${Ko.pct(made[it])}" }.toMutableList()
        if (choice >= 0.15) parts += "초이스 ${Ko.pct(choice)}"
        var text = "이번 턴 예상: " + parts.joinToString(" · ")
        if (zero >= 0.1) text += " (0점 위험 ${Ko.pct(zero)})"
        return Comment("🎯 노림수", "$text.")
    }

    /** A made hand worth naming: a lower box with points, or three or more of a face in its upper box. */
    private fun madeHand(c: Category, r: Int, pts: Int): Boolean =
        pts > 0 && (!c.isUpper || Dice.rollCounts[r][c.ordinal] >= 3)

    private fun goalName(c: Category) = if (c.isUpper) "${c.face} 모으기" else c.label

    /** Over the last three rounds my P(win) climbed or fell a lot. */
    private fun momentum(history: List<Double>, now: Double): Comment? {
        if (history.size < 6) return null
        val then = history[history.size - 6]
        val d = now - then
        val (a, b) = Ko.pctPair(then, now)
        return when {
            d >= 0.12 -> Comment("📈 흐름", "최근 3턴 동안 승률이 $a → ${Ko.ro(b)} 올랐어요. 흐름을 탔어요!", Tone.GOOD)
            d <= -0.12 -> Comment("📉 흐름", "최근 3턴 동안 승률이 $a → ${Ko.ro(b)} 떨어졌어요. 반전이 필요해요.", Tone.BAD)
            else -> null
        }
    }

    /** Both sides' pace towards the upper bonus, while it is still open for someone. */
    private fun bonusRace(me: Board, opp: Board): Comment? {
        val bonus = tables.bonus ?: return null
        fun side(b: Board): Pair<Double, Int>? {
            if (b.bonus > 0) return 1.0 to 0
            val filled = Category.ALL.filter { it.isUpper && b.isFilled(it) }
            if (filled.size == 6) return 0.0 to 0
            val par = 3 * filled.sumOf { it.face }
            return bonus[b.mask, b.u] to (b.upperSum - par)
        }
        val m = side(me) ?: return null
        val o = side(opp) ?: return null
        val live = { p: Double -> p in 0.03..0.97 }
        if (!live(m.first) && !live(o.first)) return null
        if (me.filledCount == 0) return null
        fun upperLeft(b: Board) = Category.ALL.count { it.isUpper && !b.isFilled(it) }
        val tense = (live(m.first) && upperLeft(me) <= 2) || (live(o.first) && upperLeft(opp) <= 2)
        if (abs(m.first - o.first) < 0.15 && !tense) return null
        fun text(p: Pair<Double, Int>, b: Board): String = when {
            b.bonus > 0 -> "확정"
            p.first < 0.005 -> "어려움"
            else -> Ko.pct(p.first) + if (p.second != 0) " (기준 ${if (p.second > 0) "+" else "−"}${abs(p.second)})" else " (기준대로)"
        }
        return Comment("🏆 보너스 레이스", "보너스 받을 확률: 나 ${text(m, me)} · 상대 ${text(o, opp)}")
    }
}

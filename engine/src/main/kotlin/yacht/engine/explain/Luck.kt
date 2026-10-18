package yacht.engine.explain

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.ExpectedAfter
import yacht.engine.Mode
import yacht.engine.Tables
import yacht.engine.TurnPlan
import yacht.engine.game.DecisionRecord
import yacht.engine.game.DiceSource
import yacht.engine.game.Game
import yacht.engine.game.GameState
import yacht.engine.game.MoveRecord
import yacht.engine.game.Side
import kotlin.math.abs
import kotlin.math.roundToInt

/** One event that moved my P(win): a throw, a decision of mine, or an opponent's turn. */
data class Moment(val turn: Int, val side: Side, val text: String, val delta: Double)

/** Luck added up to the end of round [round] (points), null where unknown. */
data class RoundLuck(val round: Int, val me: Double?, val opp: Double?)

/**
 * How my P(win) got from the start of the game to the result, in parts: my throws, my decisions
 * (never positive: the loss against the recommendation) and the opponent's turns. [other] is
 * what the plans' re-estimates between turns added (small).
 */
data class WinSplit(val start: Double, val end: Double, val myDice: Double, val myChoices: Double, val opponent: Double) {
    val other: Double get() = end - start - myDice - myChoices - opponent
}

/**
 * Luck of one game. Dice luck is measured in points with the max-expectation values: a throw's
 * luck is the expected final score right after it minus right before it, so over a game played
 * that way it adds up to final score minus the average. [percentile] places it among games.
 */
class LuckReport(
    /** My dice luck in points; null when my throws were not recorded. */
    val me: Double?,
    val mePercentile: Double?,
    /** The opponent's luck in points. */
    val opp: Double?,
    val oppPercentile: Double?,
    /** Real games: the opponent's dice are unknown, so its number is luck and decisions together. */
    val oppWithSkill: Boolean,
    val rounds: List<RoundLuck>,
    val split: WinSplit?,
    /** The biggest swings of my P(win), largest first (at most three). */
    val moments: List<Moment>,
    val summary: String,
    val details: List<String>,
)

class LuckAnalysis(private val tables: Tables) {
    private val rules = tables.rules
    private val cap = rules.bonusThreshold

    private fun evPlan(b: Board) = TurnPlan(b, ExpectedAfter(tables.ev, b.mask))

    private fun evLeft(b: Board) = if (b.isComplete) 0.0 else tables.ev[b.mask, b.u]

    private fun keepOf(held: List<Int>) = Dice.keepIndex(Dice.countsOf(held))

    /** Dice luck of one of my turns from its records (one per throw); null if a throw is missing. */
    internal fun myTurnLuck(recs: List<DecisionRecord>): Double? {
        val board = recs.firstOrNull()?.me ?: return null
        if (recs.map { it.rollsDone } != (1..recs.size).toList()) return null
        val plan = evPlan(board)
        var luck = 0.0
        var prev = -1
        for (rec in recs) {
            val r = Dice.rollIndex(rec.dice)
            val before = if (rec.rollsDone == 1) plan.startValue else {
                if (prev < 0) return null
                plan.keepValue(rec.rollsDone - 1, prev)
            }
            luck += plan.rollValue(rec.rollsDone, r) - before
            prev = rec.chosenHeld?.let { keepOf(it) } ?: -1
        }
        return luck
    }

    /** The opponent's turn: dice luck from its throws when known (vs AI), else the whole turn's result. */
    internal fun oppTurnLuck(before: Board, m: MoveRecord): Pair<Double, Boolean> {
        val throws = m.throws
        val held = m.held
        if (throws != null && held != null && throws.isNotEmpty() && held.size == throws.size - 1 && !before.isComplete) {
            val plan = evPlan(before)
            var luck = 0.0
            for ((k, t) in throws.withIndex()) {
                val b = if (k == 0) plan.startValue else plan.keepValue(k, keepOf(held[k - 1]))
                luck += plan.rollValue(k + 1, Dice.rollIndex(t)) - b
            }
            return luck to true
        }
        val after = before.with(m.category, m.points)
        val bonus = if (before.bonus == 0 && after.bonus > 0) rules.bonusPoints else 0
        return (m.points + bonus + evLeft(after) - evLeft(before)) to false
    }

    /** P(a whole game's dice luck is below [luck]), from the distribution of max-expectation play. */
    fun percentile(luck: Double): Double? {
        val dist = tables.dist ?: return null
        val p = runCatching { dist.pmf(0, 0) }.getOrNull() ?: return null
        val lo = dist.lo(0, 0)
        val x = (tables.ev[0, 0] + luck).roundToInt()
        var below = 0.0
        var at = 0.0
        for (i in p.indices) {
            val f = lo + i
            if (f < x) below += p[i] else if (f == x) at += p[i]
        }
        return below + 0.5 * at
    }

    fun of(g: GameState): LuckReport {
        val byTurn = g.decisions.groupBy { it.turn }
        val myByTurn = HashMap<Int, Double?>()
        for ((t, recs) in byTurn) myByTurn[t] = myTurnLuck(recs.sortedBy { it.rollsDone })
        val myTurns = g.moves.count { it.side == Side.ME }
        val me = if (myTurns > 0 && (1..myTurns).all { myByTurn[it] != null }) (1..myTurns).sumOf { myByTurn[it]!! } else null

        var oppBoard = Board(rules)
        var oppSum = 0.0
        var oppKnownDice = true
        val oppByTurn = ArrayList<Double>()
        for (m in g.moves) {
            if (m.side != Side.OPP) continue
            val (l, dice) = oppTurnLuck(oppBoard, m)
            if (!dice) oppKnownDice = false
            oppByTurn += l
            oppSum += l
            oppBoard = oppBoard.with(m.category, m.points)
        }
        val opp = if (oppByTurn.isNotEmpty()) oppSum else null
        val withSkill = !oppKnownDice || g.source == DiceSource.MANUAL

        val rounds = ArrayList<RoundLuck>()
        var accMe: Double? = 0.0
        var accOpp = 0.0
        for (round in 1..maxOf(myTurns, oppByTurn.size)) {
            val m = myByTurn[round]
            accMe = if (accMe != null && m != null) accMe + m else if (round <= myTurns) null else accMe
            val o = oppByTurn.getOrNull(round - 1)
            if (o != null) accOpp += o
            rounds += RoundLuck(round, accMe, if (round <= oppByTurn.size) accOpp else null)
        }

        val split = winSplit(g)
        val moments = moments(g)
        val (summary, details) = words(g, me, opp, withSkill, split)
        return LuckReport(
            me, me?.let { percentile(it) }, opp, opp?.let { percentile(it) }, withSkill,
            rounds, split, moments, summary, details,
        )
    }

    private fun end(g: GameState) = if (g.me.total > g.opp.total) 1.0 else 0.0

    /** The opponent's moves with my P(win) before and after each (after = result for the last move). */
    private fun oppSwings(g: GameState): List<Triple<MoveRecord, Double, Double>> {
        val out = ArrayList<Triple<MoveRecord, Double, Double>>()
        var lastMine: Double? = null
        for ((i, m) in g.moves.withIndex()) {
            if (m.side == Side.ME) {
                lastMine = m.winAfter
                continue
            }
            val before = m.winBefore ?: lastMine
            val after = m.winAfter ?: if (i == g.moves.lastIndex && g.isOver) end(g) else null
            if (before != null && after != null) out += Triple(m, before, after)
        }
        return out
    }

    private fun winSplit(g: GameState): WinSplit? {
        if (!g.isOver) return null
        val win = g.decisions.filter { it.mode == Mode.WIN }
        if (win.isEmpty() || win.size != g.decisions.size) return null
        val first = win.minWith(compareBy<DecisionRecord> { it.turn }.thenBy { it.rollsDone })
        if (first.turn != 1 || first.rollsDone != 1) return null
        val swings = oppSwings(g)
        val start = if (g.first == Side.ME) first.before ?: return null
        else g.moves.firstOrNull { it.side == Side.OPP }?.winBefore ?: return null
        val myDice = win.sumOf { d -> d.before?.let { d.bestValue - it } ?: 0.0 }
        val choices = win.sumOf { it.chosenValue - it.bestValue }
        val opponent = swings.sumOf { it.third - it.second }
        // My last box ends the game: its value is the result itself (up to the points tie-break).
        return WinSplit(start, end(g), myDice, choices, opponent)
    }

    private fun moments(g: GameState): List<Moment> {
        val out = ArrayList<Moment>()
        for (d in g.decisions) {
            if (d.mode != Mode.WIN) continue
            d.before?.let { b ->
                out += Moment(d.turn, Side.ME, "${d.turn}턴 ${d.rollsDone}번째 굴림(${d.dice.joinToString(" ")})", d.bestValue - b)
            }
            if (d.isMistake) out += Moment(d.turn, Side.ME, "${d.turn}턴 내 선택 ${Ko.quote(d.chosen)}(추천: ${d.best})", d.chosenValue - d.bestValue)
        }
        var oppTurn = 0
        val swings = oppSwings(g).associateBy { it.first }
        for (m in g.moves) {
            if (m.side != Side.OPP) continue
            oppTurn++
            val (_, before, after) = swings[m] ?: continue
            val what = if (m.points == 0) "${Game.scoreText(m.category, 0).removeSuffix(" 0점")} 0점 처리" else Game.scoreText(m.category, m.points)
            out += Moment(oppTurn, Side.OPP, "${oppTurn}턴 상대 $what", after - before)
        }
        return out.filter { abs(it.delta) >= 0.02 }.sortedByDescending { abs(it.delta) }.take(3)
    }

    // ---- words ----------------------------------------------------------------------------------

    private fun signedPoints(x: Double): String {
        val n = x.roundToInt()
        return if (n > 0) "+${n}점" else if (n < 0) "−${-n}점" else "±0점"
    }

    private fun signedPp(d: Double): String = (if (d >= 0) "+" else "−") + Ko.pp(d)

    /** "100판으로 치면 운 나쁜 쪽에서 20번째쯤이에요." */
    private fun rank(p: Double, good: String, bad: String): String {
        val fromBad = (100 * p).roundToInt().coerceIn(1, 100)
        val fromGood = (100 * (1 - p)).roundToInt().coerceIn(1, 100)
        return when {
            p in 0.35..0.65 -> "100판 중에서도 흔한 수준이에요."
            p < 0.5 -> "100판으로 치면 $bad 쪽에서 ${fromBad}번째쯤이에요."
            else -> "100판으로 치면 $good 쪽에서 ${fromGood}번째쯤이에요."
        }
    }

    private fun words(g: GameState, me: Double?, opp: Double?, withSkill: Boolean, split: WinSplit?): Pair<String, List<String>> {
        val details = ArrayList<String>()
        val oppName = if (g.source == DiceSource.RANDOM) "AI" else "상대"
        val summary = when {
            me != null && opp != null -> {
                val d = me - opp
                val verdict = when {
                    d <= -12 -> if (withSkill) "운은 상대 쪽으로 기울었어요" else "주사위 운은 상대 편이었어요"
                    d >= 12 -> if (withSkill) "운은 내 쪽으로 기울었어요" else "주사위 운이 내 편이었어요"
                    else -> "운은 비슷했어요"
                }
                "$verdict(나 ${signedPoints(me)}, $oppName ${signedPoints(opp)})."
            }
            me != null -> "내 주사위 운은 기대보다 ${signedPoints(me)}였어요."
            else -> "주사위 기록이 부족해서 운을 다 따지지는 못했어요."
        }
        if (me != null) {
            val n = abs(me).roundToInt()
            val head = when {
                n == 0 -> "내 주사위 운은 딱 평균이었어요."
                me > 0 -> "내 주사위는 기대보다 ${n}점만큼 잘 나왔어요."
                else -> "내 주사위는 기대보다 ${n}점만큼 안 나왔어요."
            }
            details += head + (percentile(me)?.let { " " + rank(it, "운 좋은", "운 나쁜") } ?: "")
        }
        if (opp != null) {
            val n = abs(opp).roundToInt()
            val p = percentile(opp)
            details += if (withSkill) {
                val head = when {
                    n == 0 -> "상대는 딱 기대만큼 점수를 냈어요"
                    opp > 0 -> "상대는 기대보다 ${n}점 더 냈어요"
                    else -> "상대는 기대보다 ${n}점 덜 냈어요"
                }
                "$head(상대 주사위는 모르니 운과 실력을 합친 값이에요)." + (p?.let { " " + rank(it, "잘 풀린", "안 풀린") } ?: "")
            } else {
                val head = when {
                    n == 0 -> "AI의 주사위 운은 딱 평균이었어요."
                    opp > 0 -> "AI의 주사위는 기대보다 ${n}점만큼 잘 나왔어요."
                    else -> "AI의 주사위는 기대보다 ${n}점만큼 안 나왔어요."
                }
                head + (p?.let { " " + rank(it, "운 좋은", "운 나쁜") } ?: "")
            }
        }
        if (split != null) {
            val result = if (split.end > 0.5) "승리" else if (g.me.total == g.opp.total) "무승부" else "패배"
            val parts = listOf(
                "내 주사위 운 ${signedPp(split.myDice)}",
                "내 선택 ${signedPp(split.myChoices)}",
                "상대 결과 ${signedPp(split.opponent)}",
            )
            details += "승률로 보면 시작 ${Ko.pct(split.start)}에서 결과(${result})까지 ${parts.joinToString(" · ")}만큼 움직였어요."
        }
        return summary to details
    }
}

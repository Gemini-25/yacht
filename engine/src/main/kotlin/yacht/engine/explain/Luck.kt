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

/**
 * The game with average dice for both sides: each final score minus its dice luck, which leaves
 * the average final plus what the decisions gained or lost. [opp] is null when the opponent's
 * luck cannot be taken out (real games without enough earlier games against the same tag).
 */
class LuckFree(
    val me: Double,
    val opp: Double?,
    /** Earlier games the opponent's usual result comes from (real games), 0 when its dice were known. */
    val oppGames: Int,
    val summary: String,
    val details: List<String>,
)

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
    /** Who would have won with average luck; null when my throws were not recorded. */
    val luckFree: LuckFree? = null,
)

class LuckAnalysis(private val tables: Tables) {
    private companion object {
        /** Earlier games against a tag needed to estimate that opponent's usual result. */
        const val MIN_TAG_GAMES = 5

        /** Luck-free scores closer than this (points) are called even. */
        const val LUCK_FREE_MARGIN = 3
    }

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

    /** The opponent's luck and decisions together in points (its turns' results against the average). */
    private fun oppTotal(g: GameState): Double? {
        var b = Board(rules)
        var sum = 0.0
        var any = false
        for (m in g.moves) {
            if (m.side != Side.OPP) continue
            sum += oppTurnLuck(b, m).first
            b = b.with(m.category, m.points)
            any = true
        }
        return if (any) sum else null
    }

    /**
     * [history]: finished games, used for a tagged real opponent's usual result: its luck
     * averages out over games, leaving how well it plays.
     */
    fun of(g: GameState, history: List<GameState> = emptyList()): LuckReport {
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
        val free = me?.let { m -> luckFree(g, m, if (withSkill) null else opp, history) }
        return LuckReport(
            me, me?.let { percentile(it) }, opp, opp?.let { percentile(it) }, withSkill,
            rounds, split, moments, summary, details, free,
        )
    }

    private fun luckFree(g: GameState, myLuck: Double, oppDiceLuck: Double?, history: List<GameState>): LuckFree {
        val e0 = tables.ev[0, 0]
        val me = g.me.total - myLuck
        val oppName = if (g.source == DiceSource.RANDOM) "AI" else "상대"
        var games = 0
        val opp = when {
            oppDiceLuck != null -> g.opp.total - oppDiceLuck
            g.opponent != null -> {
                val past = history.filter {
                    it.isOver && it.createdAt != g.createdAt && it.source == DiceSource.MANUAL && it.opponent == g.opponent
                }.mapNotNull { oppTotal(it) }
                games = past.size
                if (past.size >= MIN_TAG_GAMES) e0 + past.average() else null
            }
            else -> null
        }
        val details = ArrayList<String>()
        if (opp == null) {
            val summary = "내 주사위 운을 평균으로 바꾸면 나는 ${Ko.int(me)}점이에요."
            details += "상대 주사위는 알 수 없어서 상대 쪽 운은 뺄 수 없어요."
            details += if (g.opponent == null) "상대 이름을 붙여 ${MIN_TAG_GAMES}판 이상 두면, 상대의 평소 실력으로 비교해 드려요."
            else "${Ko.wa("‘${g.opponent}’")} ${MIN_TAG_GAMES}판 이상 두면 상대의 평소 실력으로 비교해 드려요(지금 ${games}판)."
            return LuckFree(me, null, games, summary, details)
        }
        // Said with the rounded scores, so the margin matches the numbers shown.
        val d = (me.roundToInt() - opp.roundToInt()).toDouble()
        val n = kotlin.math.abs(d).roundToInt()
        val scores = "나 ${Ko.int(me)}점, $oppName ${Ko.int(opp)}점"
        val iWon = g.me.total > g.opp.total
        val summary = when {
            n < LUCK_FREE_MARGIN -> "운을 빼면 거의 비긴 승부예요($scores)."
            d > 0 -> "운을 빼면 내가 ${n}점 차로 이겼을 거예요($scores)."
            else -> "운을 빼면 ${Ko.i(oppName)} ${n}점 차로 이겼을 거예요($scores)."
        }
        details += when {
            n < LUCK_FREE_MARGIN -> "실력은 비슷했고, 결과는 운이 갈랐어요."
            d > 0 && iWon -> "실제로도 이겼으니, 실력대로 끝난 판이에요."
            d > 0 -> "실제로는 졌으니, 운 때문에 놓친 판이에요."
            iWon -> "실제로는 이겼으니, 운 덕분에 가져온 판이에요."
            else -> "실제로도 졌으니, 실력 차이가 그대로 나온 판이에요."
        }
        details += if (oppDiceLuck != null) "양쪽 주사위 운을 모두 평균으로 바꿔서 계산했어요."
        else "상대 주사위는 알 수 없어서, 상대는 지난 ${games}판의 평소 실력으로 계산했어요."
        return LuckFree(me, opp, games, summary, details)
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
                out += Moment(d.turn, Side.ME, "${d.turn}턴 ${Ko.nth(d.rollsDone)} 굴림에서 ${d.dice.joinToString(" ")}", d.bestValue - b)
            }
            if (d.isMistake) out += Moment(d.turn, Side.ME, "${d.turn}턴에 내가 둔 ${Ko.quote(d.chosen)}(추천: ${d.best})", d.chosenValue - d.bestValue)
        }
        var oppTurn = 0
        val swings = oppSwings(g).associateBy { it.first }
        for (m in g.moves) {
            if (m.side != Side.OPP) continue
            oppTurn++
            val (_, before, after) = swings[m] ?: continue
            val box = Game.scoreText(m.category, 0).removeSuffix(" 0점")
            val what = if (m.points == 0) "${Ko.eul(box)} 0점으로 지움" else "${box}에 ${m.points}점"
            out += Moment(oppTurn, Side.OPP, "${oppTurn}턴 상대가 $what", after - before)
        }
        return out.filter { abs(it.delta) >= 0.02 }.sortedByDescending { abs(it.delta) }.take(3)
    }

    // ---- words ----------------------------------------------------------------------------------

    private fun signedPoints(x: Double): String {
        val n = x.roundToInt()
        return if (n > 0) "+${n}점" else if (n < 0) "−${-n}점" else "±0점"
    }

    private fun signedPp(d: Double): String = if (abs(d) < 0.0005) "±0%p" else (if (d > 0) "+" else "−") + Ko.pp(d)

    /** "100판 중 20번째로 운이 나쁜 수준이에요." */
    private fun rank(p: Double, good: String, bad: String): String {
        val fromBad = (100 * p).roundToInt().coerceIn(1, 100)
        val fromGood = (100 * (1 - p)).roundToInt().coerceIn(1, 100)
        return when {
            p in 0.35..0.65 -> "흔히 있는 정도예요."
            p < 0.5 -> "100판 중 ${fromBad}번째로 $bad 수준이에요."
            else -> "100판 중 ${fromGood}번째로 $good 수준이에요."
        }
    }

    private fun words(g: GameState, me: Double?, opp: Double?, withSkill: Boolean, split: WinSplit?): Pair<String, List<String>> {
        val details = ArrayList<String>()
        val oppName = if (g.source == DiceSource.RANDOM) "AI" else "상대"
        val summary = when {
            me != null && opp != null -> {
                val d = me - opp
                val verdict = when {
                    d <= -12 -> if (withSkill) "이번 판은 흐름이 상대 쪽이었어요" else "이번 판은 주사위 운이 상대 편이었어요"
                    d >= 12 -> if (withSkill) "이번 판은 흐름이 내 쪽이었어요" else "이번 판은 주사위 운이 내 편이었어요"
                    else -> "이번 판은 운이 비슷했어요"
                }
                "$verdict(나 ${signedPoints(me)}, $oppName ${signedPoints(opp)})."
            }
            me != null -> "내 주사위 운은 평균보다 ${signedPoints(me)}였어요."
            else -> "주사위 기록이 부족해서 운을 다 따지지는 못했어요."
        }
        if (me != null) {
            val n = abs(me).roundToInt()
            val head = when {
                n == 0 -> "내 주사위 운은 딱 평균이었어요."
                me > 0 -> "내 주사위 운은 평균보다 ${n}점 좋았어요."
                else -> "내 주사위 운은 평균보다 ${n}점 나빴어요."
            }
            details += head + (percentile(me)?.let { " " + rank(it, "운이 좋은", "운이 나쁜") } ?: "")
        }
        if (opp != null) {
            val n = abs(opp).roundToInt()
            val p = percentile(opp)
            details += if (withSkill) {
                val head = when {
                    n == 0 -> "상대는 딱 평균만큼 점수를 냈어요."
                    opp > 0 -> "상대는 평균보다 ${n}점 더 냈어요."
                    else -> "상대는 평균보다 ${n}점 덜 냈어요."
                }
                "$head 상대 주사위는 알 수 없어서, 운과 실력이 섞인 값이에요." + (p?.let { " " + rank(it, "잘 풀린", "안 풀린") } ?: "")
            } else {
                val head = when {
                    n == 0 -> "AI의 주사위 운은 딱 평균이었어요."
                    opp > 0 -> "AI의 주사위 운은 평균보다 ${n}점 좋았어요."
                    else -> "AI의 주사위 운은 평균보다 ${n}점 나빴어요."
                }
                head + (p?.let { " " + rank(it, "운이 좋은", "운이 나쁜") } ?: "")
            }
        }
        if (split != null) {
            val result = if (split.end > 0.5) "승리" else if (g.me.total == g.opp.total) "무승부" else "패배"
            details += "승률은 ${Ko.pct(split.start)}에서 시작했어요. 내 주사위 운이 ${signedPp(split.myDice)}, 내 선택이 ${signedPp(split.myChoices)}, " +
                "상대 결과가 ${signedPp(split.opponent)}만큼 움직여서 ${Ko.ro(result)} 끝났어요."
        }
        return summary to details
    }
}

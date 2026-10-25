package yacht.engine.league

import yacht.engine.Advisor
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.PlanPlayer
import yacht.engine.Player
import yacht.engine.SlipPlayer
import yacht.engine.SoftmaxPlayer
import yacht.engine.Tables
import yacht.engine.TurnPlan
import yacht.engine.game.DecisionKind
import yacht.engine.game.GameState
import yacht.engine.game.PlayStyle
import yacht.engine.riskPlanner
import yacht.engine.shapedPlanner
import yacht.engine.winPlanner
import java.util.Locale

enum class CustomBase(val label: String) {
    EV("기대점수 정석"),
    WIN("승률 계산"),
    RISK("위험 성향"),
}

enum class CustomMistake(val label: String) {
    NONE("실수 없음"),
    SLIP("가끔 차선"),
    FEEL("감으로 두기"),
}

/**
 * An AI the player builds: a base plan, a way of making mistakes, and (with the max-expectation
 * base) tastes that shift how it values the end of a turn.
 */
data class CustomAiSpec(
    val id: String,
    val name: String,
    val base: CustomBase = CustomBase.EV,
    /** Risk attitude θ for the RISK base (one of [LeagueRoster.THETAS]). */
    val theta: Double = 0.03,
    val mistake: CustomMistake = CustomMistake.NONE,
    /** Chance of the second-best move (SLIP) or the feel τ in points (FEEL). */
    val amount: Double = 0.0,
    /** Extra points for a Yacht, a straight, per upper point, a 0 written (negative = dislike). */
    val yacht: Double = 0.0,
    val straight: Double = 0.0,
    val bonus: Double = 0.0,
    val zero: Double = 0.0,
    /** Treats Choice before the last three turns as 10 points lost. */
    val saveChoice: Boolean = false,
    /** Share of the expected rest it counts (1 = as it is). */
    val future: Double = 1.0,
) {
    val key: String get() = "custom-$id"

    /**
     * "감으로" needs values in points (the max-expectation base); with another base it becomes
     * slipping, at [amount] / 10 (τ = 1 point ≈ 10%).
     */
    val effectiveMistake: CustomMistake get() = if (mistake == CustomMistake.FEEL && base != CustomBase.EV) CustomMistake.SLIP else mistake
    val effectiveAmount: Double get() = if (mistake == CustomMistake.FEEL && base != CustomBase.EV) minOf(0.5, amount / 10) else amount

    fun describe(): String {
        val parts = ArrayList<String>()
        parts += when (base) {
            CustomBase.EV -> "기대점수 정석"
            CustomBase.WIN -> "승률 계산"
            CustomBase.RISK -> "위험 성향 θ=" + (if (theta > 0) "+" else "") + fmt(theta)
        }
        if (base == CustomBase.EV) {
            if (future != 1.0) parts += "미래 ${(future * 100).toInt()}%"
            if (yacht != 0.0) parts += "요트 ${signed(yacht)}"
            if (straight != 0.0) parts += "스트레이트 ${signed(straight)}"
            if (bonus != 0.0) parts += "상단 1점당 ${signed(bonus)}"
            if (zero != 0.0) parts += "0점 기록 ${signed(-zero)}"
            if (saveChoice) parts += "초이스 아끼기"
        }
        parts += when (effectiveMistake) {
            CustomMistake.NONE -> "실수 없음"
            CustomMistake.SLIP -> "실수 ${fmt(effectiveAmount * 100)}%"
            CustomMistake.FEEL -> "감(τ=${fmt(amount)}점)"
        }
        return parts.joinToString(" · ")
    }

    fun encode(): String = listOf(
        id, name.replace('|', ' ').replace('\n', ' '), base.name, theta, mistake.name, amount, yacht, straight, bonus, zero, saveChoice, future,
    ).joinToString("|")

    companion object {
        private fun fmt(x: Double) = String.format(Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.')
        private fun signed(x: Double) = (if (x > 0) "+" else "") + fmt(x)

        fun decode(line: String): CustomAiSpec? = runCatching {
            val f = line.split('|')
            CustomAiSpec(
                f[0], f[1], CustomBase.valueOf(f[2]), f[3].toDouble(), CustomMistake.valueOf(f[4]), f[5].toDouble(),
                f[6].toDouble(), f[7].toDouble(), f[8].toDouble(), f[9].toDouble(), f[10].toBoolean(), f[11].toDouble(),
            )
        }.getOrNull()
    }
}

/** Builds league entrants for the player's own AIs and clone. */
object CustomAis {
    fun build(spec: CustomAiSpec, tables: Tables, extra: LeagueTables, budget: Long = LeagueRoster.BUDGET): LeagueAi {
        val t = tables.withoutRisk()
        val ev = t.ev
        val planner: (Board, Board) -> TurnPlan = when (spec.base) {
            CustomBase.WIN -> winPlanner(Advisor(t), budget)
            CustomBase.RISK -> { me, opp -> riskPlanner(extra.risk(spec.theta), spec.theta)(me, opp) }
            CustomBase.EV -> shapedPlanner(ev, spec.future) { me, c, p, u2 ->
                var x = 0.0
                if (c == Category.YACHT && p > 0) x += spec.yacht
                if ((c == Category.SMALL_STRAIGHT || c == Category.LARGE_STRAIGHT) && p > 0) x += spec.straight
                if (c.isUpper) x += spec.bonus * (u2 - me.u)
                if (p == 0) x -= spec.zero
                if (spec.saveChoice && c == Category.CHOICE && me.filledCount < 9) x -= 10.0
                x
            }
        }
        val key = spec.key
        return LeagueAi(key, spec.name, Family.MINE, "내가 만든 AI · " + spec.describe(), heavy = spec.base == CustomBase.WIN) { r ->
            when (spec.effectiveMistake) {
                CustomMistake.NONE -> PlanPlayer(key, planner)
                CustomMistake.SLIP -> SlipPlayer(planner, spec.effectiveAmount, r, key)
                CustomMistake.FEEL -> SoftmaxPlayer(planner, spec.amount, r, key)
            }
        }
    }

    const val CLONE = "clone"

    /** The player's clone: the win search's moves, with the player's own rates of keep and box mistakes. */
    fun clone(profile: CloneProfile, tables: Tables, budget: Long = LeagueRoster.BUDGET): LeagueAi {
        val plain = Advisor(tables.withoutRisk())
        return LeagueAi(
            CLONE, "내 분신", Family.MINE,
            "내 기록 ${profile.games}판에서 뽑은 실수율: 주사위 ${pct(profile.keepSlip)}, 칸 ${pct(profile.boxSlip)}", heavy = true,
        ) { r -> SlipPlayer(winPlanner(plain, budget), profile.keepSlip, r, CLONE, boxSlip = profile.boxSlip) }
    }

    private fun pct(p: Double) = String.format(Locale.ROOT, "%.1f%%", 100 * p)
}

/**
 * How often I depart from the recommendation, from my finished games: separately for holding
 * dice and for choosing a box. Games played alone count first (with hints I mostly follow the AI).
 */
data class CloneProfile(
    val games: Int,
    val soloGames: Int,
    val keepDecisions: Int,
    val keepSlip: Double,
    val boxDecisions: Int,
    val boxSlip: Double,
) {
    companion object {
        /** Games needed before a clone means something. */
        const val MIN_GAMES = 3

        fun of(history: List<GameState>, maxGames: Int = 50): CloneProfile? {
            val done = history.filter { it.isOver && it.decisions.isNotEmpty() }.sortedByDescending { it.createdAt }
            val solo = done.filter { it.style == PlayStyle.SOLO }
            val use = (if (solo.size >= MIN_GAMES) solo else done).take(maxGames)
            if (use.size < MIN_GAMES) return null
            val ds = use.flatMap { it.decisions }
            val keeps = ds.filter { it.kind == DecisionKind.KEEP }
            val boxes = ds.filter { it.kind == DecisionKind.SCORE }
            fun rate(xs: List<yacht.engine.game.DecisionRecord>) = if (xs.isEmpty()) 0.0 else xs.count { it.isMistake }.toDouble() / xs.size
            return CloneProfile(use.size, use.count { it.style == PlayStyle.SOLO }, keeps.size, rate(keeps), boxes.size, rate(boxes))
        }
    }
}

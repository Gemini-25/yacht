package yacht.engine.league

import yacht.engine.Advisor
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.EvTable
import yacht.engine.OppModel
import yacht.engine.PlanPlayer
import yacht.engine.Player
import yacht.engine.RuleSet
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
import java.util.Random

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
 * base) tastes that shift how it values the end of a turn. Generated and evolved players
 * (Players.kt) are specs too: their genes are these fields.
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
    /** How its mistakes change within a game (see [Tempers]). */
    val temper: Temper = Temper.NONE,
    /**
     * A misremembered rule, a key of [BELIEFS] (= [LeagueRoster.beliefs]): with the
     * max-expectation base it plays for that game instead of the real one. Null = the real rules.
     */
    val belief: String? = null,
    /** A style of play (generated players with a style, PlayStyles.kt): it then plays a [StylePlayer]; null = the plans above. */
    val play: PlayGene? = null,
    /** How strongly the temper bites (1 = the tempers as they always were; 멘탈). */
    val temperStrength: Double = 1.0,
    /**
     * 상대 맞춤 (the WIN base): the opponent read from its record once it has one ([OppModel]),
     * else a max-expectation opponent as the plain win search assumes.
     */
    val adapt: Boolean = false,
) {
    /** True when it plays its opponent by its record (the WIN base with [adapt]). */
    val adapts: Boolean get() = adapt && base == CustomBase.WIN && play == null

    val key: String get() = "custom-$id"

    /**
     * "감으로" needs values in points (the max-expectation base); with another base it becomes
     * slipping, at [amount] / 10 (τ = 1 point ≈ 10%).
     */
    val effectiveMistake: CustomMistake get() = if (mistake == CustomMistake.FEEL && base != CustomBase.EV) CustomMistake.SLIP else mistake
    val effectiveAmount: Double get() = if (mistake == CustomMistake.FEEL && base != CustomBase.EV) minOf(0.5, amount / 10) else amount

    /** Makes mistakes at some point of a game (the "only flawed AIs" preset keeps these); slips at 0% are none. */
    val flawed: Boolean get() = (effectiveMistake != CustomMistake.NONE && effectiveAmount > 0) || temper != Temper.NONE

    fun describe(): String {
        val parts = ArrayList<String>()
        play?.let { g ->
            parts += g.label
            parts += when (effectiveMistake) {
                CustomMistake.NONE -> "실수 없음"
                CustomMistake.SLIP -> "실수 ${fmt(amount * 100)}%"
                CustomMistake.FEEL -> "감(τ=${fmt(amount)}점)"
            }
            if (temper != Temper.NONE) parts += temper.label
            return parts.joinToString(" · ")
        }
        parts += when (base) {
            CustomBase.EV -> "기대점수 정석"
            CustomBase.WIN -> if (adapts) "승률 계산 · 상대 맞춤" else "승률 계산"
            CustomBase.RISK -> "위험 성향 θ=" + (if (theta > 0) "+" else "") + fmt(theta)
        }
        if (base == CustomBase.EV) {
            belief?.let { parts += BELIEFS[it] ?: "규칙 착각" }
            if (future != 1.0) parts += "미래 ${Math.round(future * 100)}%"
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
        if (temper != Temper.NONE) parts += temper.label
        return parts.joinToString(" · ")
    }

    /**
     * One line, fields separated by '|'. Old versions read the first twelve fields and ignore the
     * rest, so the temper and belief are appended, and only when set (a plain AI keeps its line).
     */
    fun encode(): String {
        val fields = mutableListOf<Any>(
            id, name.replace('|', ' ').replace('\n', ' '), base.name, theta, mistake.name, amount, yacht, straight, bonus, zero, saveChoice, future,
        )
        val styled = play != null || temperStrength != 1.0 || adapt
        if (temper != Temper.NONE || belief != null || styled) fields.addAll(listOf(temper.name, belief ?: "-"))
        if (styled) fields.addAll(listOf(play?.encode() ?: "-", temperStrength))
        if (adapt) fields.add(1)
        return fields.joinToString("|")
    }

    /**
     * The play fields (everything but [id] and [name]) as [GENES] space-free tokens, for
     * space-separated save files such as roster.txt; [ofGenes] reads them back.
     */
    fun genes(): List<String> = listOf(
        base.name, theta.toString(), mistake.name, amount.toString(), yacht.toString(), straight.toString(), bonus.toString(),
        zero.toString(), saveChoice.toString(), future.toString(), temper.name, belief ?: "-",
    )

    companion object {
        private fun fmt(x: Double) = String.format(Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.')
        private fun signed(x: Double) = (if (x > 0) "+" else "") + fmt(x)

        /**
         * Misremembered rules an AI can believe in (the keys of [LeagueRoster.beliefs]) and their
         * labels. The order is fixed: generated players draw from it.
         */
        val BELIEFS: Map<String, String> = linkedMapOf(
            "no-bonus" to "보너스를 모름",
            "big-bonus" to "보너스 70점 착각",
            "fh-no-yacht" to "요트는 풀하우스 아님 착각",
            "four-only" to "포카드 네 개만 착각",
            "yahtzee-straights" to "야찌 점수 습관",
            "cheap-yacht" to "요트 25점 착각",
        )

        /** Play fields of [genes]. */
        const val GENES = 12

        fun decode(line: String): CustomAiSpec? = runCatching {
            val f = line.split('|')
            CustomAiSpec(
                f[0], f[1], CustomBase.valueOf(f[2]), f[3].toDouble(), CustomMistake.valueOf(f[4]), f[5].toDouble(),
                f[6].toDouble(), f[7].toDouble(), f[8].toDouble(), f[9].toDouble(), f[10].toBoolean(), f[11].toDouble(),
                temperOf(f.getOrNull(12)), beliefOf(f.getOrNull(13)),
                f.getOrNull(14)?.takeIf { it != "-" }?.let { PlayGene.decode(it) }, f.getOrNull(15)?.toDoubleOrNull() ?: 1.0,
                f.getOrNull(16) == "1",
            )
        }.getOrNull()

        /**
         * A spec from the tokens of [genes]; the first ten are needed (the temper and belief may
         * be missing). Throws on a bad field.
         */
        fun ofGenes(id: String, name: String, f: List<String>): CustomAiSpec = CustomAiSpec(
            id, name, CustomBase.valueOf(f[0]), f[1].toDouble(), CustomMistake.valueOf(f[2]), f[3].toDouble(),
            f[4].toDouble(), f[5].toDouble(), f[6].toDouble(), f[7].toDouble(), f[8].toBooleanStrict(), f[9].toDouble(),
            temperOf(f.getOrNull(10)), beliefOf(f.getOrNull(11)),
        )

        // A temper or belief from a newer version is read as none rather than losing the AI.
        private fun temperOf(s: String?) = Temper.entries.firstOrNull { it.name == s } ?: Temper.NONE
        private fun beliefOf(s: String?) = s?.takeIf { it.isNotEmpty() && it != "-" }
    }
}

/** Builds league entrants for the player's own AIs and clone (and, through [build]'s key and family, generated players). */
object CustomAis {
    /**
     * The league AI of [spec]: by default one of mine ([Family.MINE], key [CustomAiSpec.key]);
     * generated and evolved players pass their own [key], [name], [family] and [desc]. Extra
     * tables (risk attitude, believed rules) are fetched on first play, not here.
     */
    fun build(
        spec: CustomAiSpec,
        tables: Tables,
        extra: LeagueTables,
        budget: Long = LeagueRoster.BUDGET,
        key: String = spec.key,
        name: String = spec.name,
        family: Family = Family.MINE,
        desc: String = "내가 만든 AI · " + spec.describe(),
    ): LeagueAi {
        val t = tables.withoutRisk()
        val ev = t.ev
        spec.play?.let { gene -> return styled(spec, gene, t, key, name, family, desc) }
        val advisor = Advisor(t)
        val planner: (Board, Board) -> TurnPlan = when (spec.base) {
            CustomBase.WIN -> winPlanner(advisor, budget, LeagueRoster.EXACT_AT)
            CustomBase.RISK -> { me, opp -> riskPlanner(extra.risk(spec.theta), spec.theta)(me, opp) }
            CustomBase.EV -> {
                val taste: (Board, Category, Int, Int) -> Double = { me, c, p, u2 ->
                    var x = 0.0
                    if (c == Category.YACHT && p > 0) x += spec.yacht
                    if ((c == Category.SMALL_STRAIGHT || c == Category.LARGE_STRAIGHT) && p > 0) x += spec.straight
                    if (c.isUpper) x += spec.bonus * (u2 - me.u)
                    if (p == 0) x -= spec.zero
                    if (spec.saveChoice && c == Category.CHOICE && me.filledCount < 9) x -= 10.0
                    x
                }
                // An unknown belief (a newer version's) plays the real rules.
                val believed = spec.belief?.let { b -> LeagueRoster.beliefs(t.rules)[b]?.let { b to it } }
                if (believed == null) {
                    shapedPlanner(ev, spec.future, taste)
                } else {
                    val (b, rules) = believed
                    believedPlanner(rules, { extra.believed(b, rules) }, spec.future, taste)
                }
            }
        }
        val mistake = spec.effectiveMistake
        val amount = spec.effectiveAmount
        // The player at a mistake level: level 1 is the spec as it is (the only level without a temper).
        fun player(r: Random, level: Double, plan: (Board, Board) -> TurnPlan): Player = when (mistake) {
            CustomMistake.NONE -> Tempers.slip(0.0, level).let { p -> if (p <= 0.0) PlanPlayer(key, plan) else SlipPlayer(plan, p, r, key) }
            CustomMistake.SLIP -> SlipPlayer(plan, Tempers.slip(amount, level), r, key)
            CustomMistake.FEEL -> SoftmaxPlayer(plan, Tempers.feel(amount, level), r, key)
        }
        val temper = spec.temper
        fun make(r: Random, plan: (Board, Board) -> TurnPlan): Player =
            if (temper == Temper.NONE) player(r, 1.0, plan) else TemperPlayer(key, Tempers.mood(temper, ev, r)) { player(r, it, plan) }
        // 상대 맞춤: the same AI, its win search reading the opponent by its record when there is one.
        val versus: ((Random, OppModel?) -> Player)? = if (!spec.adapts) null else { r, model ->
            make(r, if (model == null) planner else winPlanner(advisor, budget, LeagueRoster.EXACT_AT, model))
        }
        return LeagueAi(key, name, family, desc, heavy = spec.base == CustomBase.WIN, versus = versus) { r -> make(r, planner) }
    }

    /**
     * A player with a style ([PlayGene]): each turn solved for its style ([StylePlan], in points,
     * no table of its own), the overlay's style for the situation, mistakes (slips, or feel τ in
     * points on any base) at the temper's level.
     */
    private fun styled(spec: CustomAiSpec, gene: PlayGene, t: Tables, key: String, name: String, family: Family, desc: String): LeagueAi {
        val ev = t.ev
        val win = if (gene.sense > 0.0 && t.dist != null) WinEstimate(t) else null
        val slip = if (spec.mistake == CustomMistake.SLIP) spec.amount else 0.0
        val tau = if (spec.mistake == CustomMistake.FEEL) spec.amount else 0.0
        return LeagueAi(key, name, family, desc) { r ->
            StylePlayer(key, ev, r, gene.picker(ev, r, spec.temper, spec.temperStrength, slip, tau), t.bonus, gene.sense, win)
        }
    }

    /**
     * Max expectation (with [taste], as [shapedPlanner]) for the game the AI believes in: it
     * reads its card under [belief] (same bonus threshold) and plans with that game's [table],
     * as the 규칙 착각 family's [yacht.engine.BeliefPlayer] does.
     */
    private fun believedPlanner(
        belief: RuleSet,
        table: () -> EvTable,
        future: Double,
        taste: (Board, Category, Int, Int) -> Double,
    ): (Board, Board) -> TurnPlan = { me, opp -> shapedPlanner(table(), future, taste)(Board.of(belief, me.toList()), opp) }

    const val CLONE = "clone"

    /** The player's clone: the win search's moves, with the player's own rates of keep and box mistakes. */
    fun clone(profile: CloneProfile, tables: Tables, budget: Long = LeagueRoster.BUDGET): LeagueAi {
        val plain = Advisor(tables.withoutRisk())
        return LeagueAi(
            CLONE, "내 분신", Family.MINE,
            "내 기록 ${profile.games}판에서 뽑은 실수율: 주사위 ${pct(profile.keepSlip)}, 칸 ${pct(profile.boxSlip)}", heavy = true,
        ) { r -> SlipPlayer(winPlanner(plain, budget, LeagueRoster.EXACT_AT), profile.keepSlip, r, CLONE, boxSlip = profile.boxSlip) }
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

package yacht.engine.league

import yacht.engine.explain.Ko
import java.util.Locale
import kotlin.math.abs

/** 스카우팅 리포트 of an opponent: Korean sentences, and one sentence of advice. */
data class ScoutingReport(val lines: List<String>, val advice: String)

/**
 * 스카우팅 리포트 before a ranked game (docs/LEAGUE_SPEC.md section 6): the opponent's strongest
 * and weakest boxes against the league, its Yacht and bonus rates, comebacks and blown leads,
 * its attitude to risk and its mistakes (from its genes when it has them, else its family), and
 * the advice that follows from the most telling of these.
 */
object Scouting {
    /** Boxes named as strong or weak. */
    private const val BOXES = 2

    /** A rate this much above or below the league's counts as a trait (30%). */
    private const val NOTABLE = 1.3

    /** A slip chance from which an AI counts as error-prone. */
    private const val SLIPPY = 0.05

    /** θ written in an AI's description ("θ=+0.03", "θ=−0.1"). */
    private val THETA = Regex("θ=([+\\-−]?)(\\d+(?:\\.\\d+)?)")

    /**
     * The report on [name]. Any of [family], [spec] (generated, evolved and my own AIs),
     * [profile] (its career boxes) and [league] (the league's) may be missing; [desc] is the
     * AI's description, read for a risk attitude θ when there is no [spec].
     */
    fun report(
        name: String,
        family: Family?,
        spec: CustomAiSpec?,
        profile: BoxProfile?,
        league: BoxProfile?,
        desc: String? = null,
    ): ScoutingReport {
        val lines = ArrayList<String>()
        // Advice candidates, the weightiest wins.
        val advice = ArrayList<Pair<Double, String>>()
        val p = profile?.takeIf { it.games > 0 }
        if (p == null) {
            lines += "${Ko.eun(name)} 아직 기록된 경기가 없어서 칸별 분석은 할 수 없어요."
        } else if (!p.reliable) {
            lines += "${Ko.eun(name)} 아직 ${p.games}판만 둬서 칸별 분석은 조금 더 지켜봐야 해요."
        } else if (league != null) {
            boxes(p, league, lines, advice)
        } else {
            lines += "리그 평균이 아직 없어서 칸별 비교는 할 수 없어요."
        }
        // Rates from a handful of games are mostly luck.
        p?.takeIf { it.reliable }?.let { rates(it, league, lines, advice) }
        val theta = theta(spec, desc)
        attitude(family, spec, theta)?.let { lines += it }
        if (theta != null && theta > 0) advice += 3.0 to "한 방을 노리는 상대라 앞서 있어도 방심하지 말고 꾸준히 점수를 쌓으세요."
        if (theta != null && theta < 0) advice += 3.0 to "안전하게 두는 상대라 뒤지고 있다면 과감하게 큰 칸을 노려 보세요."
        if (spec != null) habits(spec, lines, advice)
        when (family) {
            Family.WIN -> advice += 2.5 to "승률 계산이 정확한 강적이라 큰 실수 없이 버티는 게 가장 중요해요."
            Family.HUMAN, Family.RULE, Family.BELIEF -> if (spec == null) advice += 2.0 to "실수가 잦은 상대라 정석대로만 둬도 승산이 커요."
            else -> {}
        }
        if (lines.isEmpty()) lines += "${Ko.eun(name)} 처음 보는 상대예요."
        val best = advice.maxByOrNull { it.first }?.second
            ?: if (p == null || !p.reliable) "처음 보는 상대라 정석대로 차분하게 두면서 상대의 수를 지켜보세요."
            else "뚜렷한 약점이 없는 상대라 정석대로 차분하게 두세요."
        return ScoutingReport(lines, best)
    }

    private fun pct(x: Double) = Ko.pct(x)

    /** The strongest and weakest boxes against the league. */
    private fun boxes(p: BoxProfile, league: BoxProfile, lines: MutableList<String>, advice: MutableList<Pair<Double, String>>) {
        val edges = p.edges(league)
        val strong = edges.filter { it.diff >= BoxProfile.MIN_EDGE }.take(BOXES)
        val weak = edges.reversed().filter { it.diff <= -BoxProfile.MIN_EDGE }.take(BOXES)
        // "풀하우스와 포카드": 와/과 follows the first box's sound.
        fun names(es: List<BoxEdge>) =
            if (es.size == 2) Ko.wa(BoxProfile.boxName(es[0].category)) + " " + BoxProfile.boxName(es[1].category) else BoxProfile.boxName(es[0].category)
        fun amounts(es: List<BoxEdge>) =
            if (es.size == 1) "${Ko.num(abs(es[0].diff))}점" else "각각 " + es.joinToString(", ") { Ko.num(abs(it.diff)) + "점" }
        lines += if (strong.isNotEmpty()) "가장 강한 칸은 ${Ko.ieyo(names(strong))}. 리그 평균보다 판당 ${Ko.eul(amounts(strong))} 더 따요."
        else "리그 평균보다 눈에 띄게 강한 칸은 없어요."
        if (weak.isEmpty()) {
            lines += "리그 평균보다 눈에 띄게 약한 칸도 없어요."
            return
        }
        val worst = weak.first()
        val box = BoxProfile.boxName(worst.category)
        val zero = if (worst.zeroDiff >= 0.05) " ${Ko.eul(box)} 0점으로 지우는 판도 평균보다 ${Ko.pp(worst.zeroDiff)} 많아요." else ""
        lines += "가장 약한 칸은 ${Ko.ieyo(names(weak))}. 리그 평균보다 판당 ${Ko.eul(amounts(weak))} 덜 받아요.$zero"
        advice += 1.0 + minOf(1.0, -worst.score) to "상대가 ${box}에서 점수를 잘 못 내니, 내가 그 칸을 잘 챙기면 차이를 벌릴 수 있어요."
    }

    /** Yacht and bonus rates, comebacks and blown leads, against the league when known. */
    private fun rates(p: BoxProfile, league: BoxProfile?, lines: MutableList<String>, advice: MutableList<Pair<Double, String>>) {
        val head = "요트는 ${pct(p.yachtRate)}의 판에서 만들고, 상단 보너스는 ${pct(p.bonusRate)}의 판에서 챙겨요."
        lines += if (league != null) "$head ${averages(league.yachtRate, league.bonusRate)}" else head
        val back = "역전승은 ${pct(p.comebackRate)}, 역전패는 ${pct(p.blownLeadRate)}의 판에서 나왔어요."
        if (league == null) {
            lines += back
            return
        }
        val traits = ArrayList<String>()
        if (p.comebackRate > NOTABLE * league.comebackRate && p.comebackRate >= 0.02) {
            traits += "뒤지고 있어도 잘 뒤집는 편이에요."
            advice += 2.0 + p.comebackRate / maxOf(0.01, league.comebackRate) / 10 to "뒤집기에 강한 상대라 앞서 있어도 마지막 턴까지 방심하지 마세요."
        }
        if (p.blownLeadRate > NOTABLE * league.blownLeadRate && p.blownLeadRate >= 0.02) {
            traits += "앞서다가 무너지는 일이 잦아요."
            advice += 2.0 + p.blownLeadRate / maxOf(0.01, league.blownLeadRate) / 10 to "앞서다 무너지는 일이 잦은 상대라 초반에 밀려도 끝까지 따라붙으면 기회가 와요."
        }
        lines += (listOf(back, averages(league.comebackRate, league.blownLeadRate)) + traits).joinToString(" ")
        if (p.yachtRate > NOTABLE * league.yachtRate && p.yachtRate >= 0.1) {
            advice += 1.8 to "요트를 자주 만드는 상대라 점수 차가 크지 않으면 안심하지 마세요."
        }
        if (p.bonusRate * NOTABLE < league.bonusRate) {
            advice += 1.6 to "상단 보너스를 자주 놓치는 상대라 내가 보너스를 챙기면 쉽게 앞설 수 있어요."
        }
    }

    /** "리그 평균은 각각 30%와 50%예요." (or "둘 다 3%예요." when they read the same). */
    private fun averages(x: Double, y: Double): String {
        val (px, py) = pct(x) to pct(y)
        return if (px == py) "리그 평균은 둘 다 ${Ko.ieyo(px)}." else "리그 평균은 각각 ${px}와 ${Ko.ieyo(py)}."
    }

    /** θ of [spec] (a risk base), else read from [desc]: null when none, 0 when it says both ways. */
    private fun theta(spec: CustomAiSpec?, desc: String?): Double? {
        if (spec != null) return if (spec.base == CustomBase.RISK) spec.theta else null
        val found = desc?.let { d -> THETA.findAll(d).map { m -> (if (m.groupValues[1] in setOf("-", "−")) -1 else 1) * m.groupValues[2].toDouble() }.toList() }.orEmpty()
        if (found.isEmpty()) return null
        return when {
            found.all { it > 0 } -> found.max()
            found.all { it < 0 } -> found.min()
            else -> 0.0
        }
    }

    private fun signed(theta: Double): String =
        (if (theta > 0) "+" else "−") + String.format(Locale.ROOT, "%.2f", abs(theta)).trimEnd('0').trimEnd('.')

    /** How it plays and how it takes risks. */
    private fun attitude(family: Family?, spec: CustomAiSpec?, theta: Double?): String? {
        if (theta != null && theta != 0.0 && family != Family.SITUATION) {
            return if (theta > 0) "큰 점수를 노리는 모험형이에요(θ=${signed(theta)}). 망할 위험을 감수하고 한 방을 노려요."
            else "크게 망하는 걸 싫어하는 신중형이에요(θ=${signed(theta)}). 무리하지 않고 점수를 지켜요."
        }
        if (spec != null) return when (spec.base) {
            CustomBase.WIN -> "매 턴 승률을 직접 계산해서, 점수 차에 맞춰 안전하게도 과감하게도 둬요." +
                if (spec.adapts) " 상대 기록이 쌓이면 그 상대가 실제로 내는 점수에 맞춰 계산해요." else ""
            CustomBase.EV, CustomBase.RISK -> "평균 점수를 최대화하는 정석을 바탕으로 둬요."
        }
        return when (family) {
            Family.WIN -> "매 턴 승률을 직접 계산해서, 점수 차에 맞춰 안전하게도 과감하게도 둬요."
            Family.TARGET -> "상대는 보지 않고 목표 점수를 넘기는 데만 집중해요."
            Family.EV -> "평균 점수를 최대화하는 정석파예요."
            Family.RISK -> "위험을 대하는 태도가 뚜렷한 AI예요."
            Family.SITUATION -> "점수 차를 보고 신중하게 두다가도, 상황에 따라 과감해지거나 흔들려요."
            Family.CHARACTER -> "취향과 기질이 뚜렷한 개성파라 수를 읽기 어려워요."
            Family.HUMAN -> "기본은 정석이지만 사람처럼 가끔 실수해요."
            Family.BELIEF -> "규칙을 잘못 알고 있어서 엉뚱한 칸을 고를 때가 있어요."
            Family.RULE -> "단순한 규칙대로 둬서 수가 뻔한 편이에요."
            Family.MINE -> "내 기록에서 뽑은 실수율로 두는 AI예요."
            null -> null
        }
    }

    /** Mistakes, temper, misremembered rules and tastes from the genes. */
    private fun habits(spec: CustomAiSpec, lines: MutableList<String>, advice: MutableList<Pair<Double, String>>) {
        when (spec.effectiveMistake) {
            CustomMistake.NONE -> if (spec.temper == Temper.NONE) lines += "실수 없이 계산대로 둬요."
            CustomMistake.SLIP -> {
                lines += "수를 고를 때 ${pct(spec.effectiveAmount)}의 확률로 최선 대신 차선을 둬요."
                if (spec.effectiveAmount >= SLIPPY) advice += 2.0 + spec.effectiveAmount to "실수가 잦은 상대라 정석대로만 둬도 승산이 커요."
            }
            CustomMistake.FEEL -> {
                lines += "감으로 둬서 ${Ko.num(spec.amount)}점 안쪽 차이는 자주 헷갈려요."
                if (spec.amount >= 1.0) advice += 2.0 + spec.amount / 10 to "감으로 두는 상대라 정석대로만 둬도 승산이 커요."
            }
        }
        if (spec.temper != Temper.NONE) lines += "${Ko.quote(spec.temper.label)} 기질이 있어요. ${spec.temper.desc}."
        if (spec.temper == Temper.TILT) advice += 2.2 to "멘탈이 약한 상대라 초반에 앞서 나가면 상대가 스스로 무너질 수 있어요."
        if (spec.base != CustomBase.EV) return
        spec.belief?.let { b ->
            lines += "규칙을 잘못 알고 있어서(${Ko.quote(CustomAiSpec.BELIEFS[b] ?: "규칙 착각")}) 실제와 다른 규칙대로 계산해요."
        }
        val tastes = ArrayList<String>()
        fun pts(x: Double) = Ko.num(abs(x))
        if (spec.yacht > 0) tastes += "요트를 실제보다 ${pts(spec.yacht)}점 더 값지게 봐요."
        if (spec.yacht < 0) tastes += "요트를 ${pts(spec.yacht)}점 낮춰 봐요."
        if (spec.straight > 0) tastes += "스트레이트를 ${pts(spec.straight)}점 더 값지게 봐요."
        if (spec.straight < 0) tastes += "스트레이트를 ${pts(spec.straight)}점 낮춰 봐요."
        if (spec.bonus > 0) tastes += "상단 점수 1점마다 ${pts(spec.bonus)}점 덤을 붙여 봐요."
        if (spec.zero > 0) tastes += "0점 기록을 ${pts(spec.zero)}점 손해로 쳐요."
        if (spec.saveChoice) tastes += "초이스를 끝까지 아껴요."
        if (spec.future < 1.0) tastes += "앞으로 받을 점수를 ${pct(spec.future)}만 쳐 주는 근시안이에요."
        if (spec.future > 1.0) tastes += "앞으로 받을 점수를 ${Ko.ro(pct(spec.future))} 부풀려 봐요."
        if (tastes.isNotEmpty()) lines += tastes.joinToString(" ")
        if (spec.yacht >= 20) advice += 1.7 to "요트를 쫓는 상대라 요트가 안 나오면 다른 칸이 무너지기 쉬워요. 흔들리지 말고 정석대로 두세요."
    }
}

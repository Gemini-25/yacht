package yacht.engine.explain

import yacht.engine.Category
import yacht.engine.Mode
import yacht.engine.game.DecisionKind
import yacht.engine.game.DecisionRecord
import yacht.engine.game.GameState

/** A kind of mistake seen in my games, with the P(win) it cost. */
data class Habit(
    val key: String,
    val title: String,
    val detail: String,
    val count: Int,
    /** P(win) lost in total by these moves. */
    val loss: Double,
    /** The moves themselves, worst first (with the position when recorded, for quizzes). */
    val examples: List<DecisionRecord>,
)

data class HabitReport(
    val games: Int,
    val mistakes: Int,
    val summary: String,
    val details: List<String>,
    val habits: List<Habit>,
)

/**
 * Finds patterns in the moves that differed from the advice (WIN mode). Each such move is tagged
 * by what it did (stopped too early, kept extra dice, used Choice early, played safe while
 * behind, ...) and the tags are ranked by the P(win) they cost.
 */
object Habits {
    /** Recent games say more about current habits than old ones. */
    const val RECENT_GAMES = 30

    private class Tag(val key: String, val title: String, val detail: (Int) -> String)

    private val TAGS = listOf(
        Tag("early_stop", "너무 일찍 멈춰요") { n -> "더 굴리는 게 나은데 멈추고 기록한 수가 ${n}번이에요. 지금 결과가 아까워도 더 굴리면 나아질 가능성이 더 큰 경우였어요." },
        Tag("greedy_reroll", "욕심내서 더 굴려요") { n -> "멈추고 기록하는 게 나은데 더 굴린 수가 ${n}번이에요. 지금 결과를 지키는 쪽이 이길 확률이 높은 경우였어요." },
        Tag("extra_dice", "필요 없는 주사위까지 남겨요") { n -> "추천보다 주사위를 더 남긴 수가 ${n}번이에요. 쓸모가 적은 주사위는 다시 굴려야 좋은 결과가 나올 기회가 많아요." },
        Tag("dropped_dice", "남길 주사위를 다시 굴려요") { n -> "추천이 남기라는 주사위까지 다시 굴린 수가 ${n}번이에요." },
        Tag("other_target", "다른 족보를 노려요") { n -> "추천과 다른 조합을 남긴 수가 ${n}번이에요. 이길 확률로는 추천 쪽 족보가 더 나은 경우였어요." },
        Tag("choice_early", "초이스를 너무 일찍 써요") { n -> "추천은 다른 칸인데 초이스에 넣은 수가 ${n}번이에요. 초이스는 안 풀린 턴을 받아 주는 칸이라 아껴 두는 게 좋아요." },
        Tag("wrong_sacrifice", "0점으로 지울 칸을 잘못 골라요") { n -> "0점으로 지울 칸을 추천과 다르게 고른 수가 ${n}번이에요. 남겨 둬도 기대할 게 가장 적은 칸을 지워야 해요." },
        Tag("avoid_sacrifice", "지워야 할 때 아까운 칸을 써요") { n -> "추천은 0점으로 지우기였는데 다른 칸에 점수를 넣은 수가 ${n}번이에요. 적은 점수로 좋은 칸을 써 버리면 나중에 더 손해예요." },
        Tag("other_box", "칸 선택이 추천과 달라요") { n -> "기록할 칸을 추천과 다르게 고른 수가 ${n}번이에요." },
        Tag("too_safe", "불리할 때 너무 안전하게 둬요") { n -> "불리한 상황에서 결과가 덜 흔들리는 쪽을 고른 수가 ${n}번이에요. 뒤질 땐 큰 점수가 나올 기회를 살려야 역전할 수 있어요." },
        Tag("too_risky", "유리할 때 무리해요") { n -> "유리한 상황에서 더 모험적인 쪽을 고른 수가 ${n}번이에요. 앞설 땐 크게 무너지지 않는 게 먼저예요." },
        Tag("points_over_win", "승률보다 점수를 따라가요") { n -> "평균 점수는 더 높지만 이길 확률은 낮은 쪽을 고른 수가 ${n}번이에요. 이 앱은 점수보다 이기는 확률을 기준으로 추천해요." },
    )

    /** Tags of one move that differed from the advice; empty for records without the details. */
    fun tags(d: DecisionRecord): List<String> {
        val out = ArrayList<String>()
        val dice = d.dice
        when (d.kind) {
            DecisionKind.KEEP -> {
                val chosen = d.chosenHeld ?: return out
                val best = d.bestHeld
                when {
                    best == null && d.bestCategory != null -> out += "greedy_reroll"
                    best != null && contains(chosen, best) -> out += "extra_dice"
                    best != null && contains(best, chosen) -> out += "dropped_dice"
                    best != null -> out += "other_target"
                }
            }
            DecisionKind.SCORE -> {
                val chosen = d.chosenCategory ?: return out
                val best = d.bestCategory
                if (best == null) {
                    if (d.rollsDone < 3) out += "early_stop"
                } else {
                    val rules = d.me?.rules ?: yacht.engine.RuleSet.NINTENDO
                    val chosenPts = rules.score(dice, chosen)
                    val bestPts = rules.score(dice, best)
                    out += when {
                        chosen == Category.CHOICE -> "choice_early"
                        chosenPts == 0 && bestPts == 0 -> "wrong_sacrifice"
                        chosenPts > 0 && bestPts == 0 -> "avoid_sacrifice"
                        else -> "other_box"
                    }
                }
            }
        }
        val cs = d.chosenSd
        val bs = d.bestSd
        if (cs != null && bs != null) {
            if (d.bestValue < 0.45 && cs < bs - 1.0) out += "too_safe"
            if (d.bestValue > 0.55 && cs > bs + 1.0) out += "too_risky"
        }
        if (d.chosenExpected > d.bestExpected + 0.3) out += "points_over_win"
        return out
    }

    /** Is [small] a sub-multiset of [big] with fewer dice? */
    private fun contains(big: List<Int>, small: List<Int>): Boolean {
        if (small.size >= big.size) return false
        val left = big.toMutableList()
        return small.all { left.remove(it) }
    }

    fun analyze(allGames: List<GameState>): HabitReport {
        val games = allGames.filter { it.isOver }.sortedByDescending { it.createdAt }.take(RECENT_GAMES)
        val decisions = games.flatMap { g -> g.decisions.filter { it.mode == Mode.WIN } }
        val mistakes = decisions.filter { it.isMistake }
        val n = games.size
        if (n == 0) return HabitReport(0, 0, "끝난 게임이 아직 없어요. 몇 판 두고 나면 습관을 분석해 드릴게요.", emptyList(), emptyList())
        if (decisions.isEmpty()) return HabitReport(n, 0, "승률 기준으로 둔 판이 아직 없어요.", emptyList(), emptyList())
        val recent = if (n == 1) "최근 1판" else "최근 ${n}판"
        if (mistakes.isEmpty()) {
            return HabitReport(n, 0, "$recent 동안 추천과 다르게 둔 수가 하나도 없어요. 아주 좋아요!", emptyList(), emptyList())
        }
        val totalLoss = mistakes.sumOf { it.loss }
        val perGame = mistakes.size.toDouble() / n
        val summary = "$recent 동안 추천과 다르게 둔 수는 판당 ${Ko.num(perGame)}번, 이길 확률로는 판당 ${Ko.pp(totalLoss / n)} 손해예요."

        val byTag = LinkedHashMap<String, MutableList<DecisionRecord>>()
        for (m in mistakes) for (t in tags(m)) byTag.getOrPut(t) { ArrayList() } += m
        val habits = TAGS.mapNotNull { tag ->
            val list = byTag[tag.key] ?: return@mapNotNull null
            Habit(tag.key, tag.title, tag.detail(list.size), list.size, list.sumOf { it.loss }, list.sortedByDescending { it.loss })
        }.sortedByDescending { it.loss }

        val details = ArrayList<String>()
        habits.firstOrNull()?.let { top ->
            val share = top.loss / totalLoss
            if (share >= 0.25) details += "전체 손해의 ${Ko.pct(share)}가 ‘${top.title}’ 유형에서 나왔어요."
        }
        val keepLoss = mistakes.filter { it.kind == DecisionKind.KEEP }.sumOf { it.loss }
        val scoreLoss = totalLoss - keepLoss
        if (keepLoss > 0 && scoreLoss > 0) {
            details += "주사위 고르기에서 ${Ko.pp(keepLoss)}, 칸 고르기에서 ${Ko.pp(scoreLoss)}를 잃었어요."
        }
        val phases = listOf("초반(1~4턴)" to 1..4, "중반(5~8턴)" to 5..8, "막판(9~12턴)" to 9..12)
        val worst = phases.map { (name, range) -> name to mistakes.filter { it.turn in range }.sumOf { it.loss } }.maxBy { it.second }
        if (worst.second / totalLoss >= 0.5 && mistakes.size >= 3) {
            details += "손해의 ${Ko.pct(worst.second / totalLoss)}가 ${worst.first}에 나왔어요."
        }
        return HabitReport(n, mistakes.size, summary, details, habits)
    }
}

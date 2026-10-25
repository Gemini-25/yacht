package yacht.engine.league

import yacht.engine.Advisor
import yacht.engine.AdvisorPlayer
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.EvTable
import yacht.engine.ExpectedAfter
import yacht.engine.GreedyPlayer
import yacht.engine.HalfEvPlayer
import yacht.engine.KeeperEvBoxPlayer
import yacht.engine.KeeperPlayer
import yacht.engine.LazyPlayer
import yacht.engine.LimitedThrowsPlayer
import yacht.engine.Mode
import yacht.engine.PhasePlayer
import yacht.engine.PlanPlayer
import yacht.engine.Player
import yacht.engine.RandomPlayer
import yacht.engine.RulePlayer
import yacht.engine.RuleSet
import yacht.engine.SituationalPlayer
import yacht.engine.SlipPlayer
import yacht.engine.SoftmaxPlayer
import yacht.engine.TableBuilder
import yacht.engine.Tables
import yacht.engine.TurnPlan
import yacht.engine.riskPlanner
import yacht.engine.shapedPlanner
import yacht.engine.winPlanner
import java.util.Random

enum class Family(val label: String) {
    WIN("승률형"),
    EV("기대점수형"),
    RISK("위험 성향"),
    SITUATION("상황 판단"),
    HUMAN("사람 같은 실수"),
    RULE("단순 규칙"),
}

/**
 * One AI of the league. [make] builds a fresh player for one game from that game's random
 * source; [heavy] players run the win search (about a second per game on a phone).
 */
class LeagueAi(
    val key: String,
    val name: String,
    val family: Family,
    val desc: String,
    val heavy: Boolean = false,
    val make: (Random) -> Player,
)

/**
 * Exponential-utility tables for the risk-attitude AIs, built on first use (a few seconds each
 * on a phone). [load]/[save] can keep them between runs.
 */
class RiskTables(
    private val rules: RuleSet,
    private val threads: Int = 1,
    private val load: (Double) -> EvTable? = { null },
    private val save: (Double, EvTable) -> Unit = { _, _ -> },
) {
    private val tables = HashMap<Double, EvTable>()
    private val locks = HashMap<Double, Any>()

    fun has(theta: Double): Boolean = synchronized(tables) { theta in tables }

    operator fun get(theta: Double): EvTable {
        synchronized(tables) { tables[theta]?.let { return it } }
        val lock = synchronized(locks) { locks.getOrPut(theta) { Any() } }
        synchronized(lock) {
            synchronized(tables) { tables[theta]?.let { return it } }
            val t = runCatching { load(theta) }.getOrNull()
                ?: EvTable(rules, TableBuilder(rules, threads = threads, theta = theta).build(withDist = false).ev).also { save(theta, it) }
            synchronized(tables) { tables[theta] = t }
            return t
        }
    }
}

/** Everyone in the league: from the exact win search down to random play. */
object LeagueRoster {
    /** Risk attitudes used by the roster (exponential utility θ). */
    val THETAS = listOf(-0.1, -0.06, -0.03, -0.01, 0.01, 0.03, 0.06, 0.1)

    /** Ops budget of the win-search AIs: the same as the 36-AI study (a few hundred ms per game on a server). */
    const val BUDGET = 100_000_000L

    /** End-game duel tables are built for the duel AIs once they fit this many operations. */
    const val DUEL_OPS = 200_000_000L

    fun build(tables: Tables, risk: RiskTables, budget: Long = BUDGET, duelOps: Long = DUEL_OPS): List<LeagueAi> {
        val t = tables.withoutRisk()
        val rules = t.rules
        val ev = t.ev
        val plain = Advisor(t)
        val minimax = Advisor(t, adversarial = true)
        fun evPlan(me: Board): TurnPlan = TurnPlan(me, ExpectedAfter(ev, me.mask))
        val evPlanner: (Board, Board) -> TurnPlan = { me, _ -> evPlan(me) }
        fun riskOf(theta: Double): (Board, Board) -> TurnPlan = { me, opp -> riskPlanner(risk[theta], theta)(me, opp) }
        fun winAi(key: String, name: String, desc: String, make: (Random) -> Player) = LeagueAi(key, name, Family.WIN, desc, heavy = true, make = make)
        fun winSearch(key: String, mode: Mode = Mode.WIN, depth: Int? = null, duel: Long = 0, tie: Double = 0.0, shift: Int = 0, adv: Advisor = plain) =
            AdvisorPlayer(adv, mode, budget, fixedDepth = depth, name = key, duelOps = duel, tieValue = tie, oppShift = shift)
        fun shaped(key: String, name: String, desc: String, future: Double = 1.0, extra: (Board, Category, Int, Int) -> Double = { _, _, _, _ -> 0.0 }) =
            LeagueAi(key, name, Family.EV, desc) { PlanPlayer(key, shapedPlanner(ev, future, extra)) }
        fun riskAi(key: String, name: String, theta: Double, desc: String) =
            LeagueAi(key, name, Family.RISK, desc) { PlanPlayer(key, riskOf(theta)) }
        fun slip(key: String, name: String, p: Double, desc: String, keeps: Boolean = true, boxes: Boolean = true) =
            LeagueAi(key, name, Family.HUMAN, desc) { r -> SlipPlayer(evPlanner, p, r, key, keeps, boxes) }
        fun soft(key: String, name: String, tau: Double, desc: String) =
            LeagueAi(key, name, Family.HUMAN, desc) { r -> SoftmaxPlayer(ev, tau, r, key) }
        fun lower(c: Category) = c == Category.FOUR_OF_A_KIND || c == Category.FULL_HOUSE || c == Category.SMALL_STRAIGHT ||
            c == Category.LARGE_STRAIGHT || c == Category.YACHT

        return listOf(
            // ---- win search -------------------------------------------------------------------
            winAi("win", "승률 AI (앱 기본)", "승률을 최대화. 앞 몇 턴은 정확히, 막판 8~9칸부터는 끝까지 정확히 계산") { winSearch("win") },
            winAi("win-d1", "승률 AI · 이번 턴만", "이번 턴만 정확히 풀고 이후는 기대점수 방식으로 근사") { winSearch("win-d1", depth = 1) },
            winAi("win-d2", "승률 AI · 2턴", "앞 2턴을 정확히 풀고 이후는 근사") { winSearch("win-d2", depth = 2) },
            winAi("duel", "승률 AI · 막판 대결표", "막판(약 5칸)부터 상대의 실제 결과에 대응하는 표로 끝까지 정확") { winSearch("duel", duel = duelOps) },
            winAi("minimax", "최소최대 AI", "막판에 상대가 늘 나에게 가장 불리한 수를 둔다고 가정") { winSearch("minimax", duel = duelOps, adv = minimax) },
            winAi("win-tie", "승률 AI · 무승부도 반승", "무승부를 절반의 승리로 쳐서 계산") { winSearch("win-tie", tie = 0.5) },
            winAi("win-under15", "상대를 얕보는 승률 AI", "상대가 실제보다 15점 덜 낼 거라고 믿고 둠 (지나치게 안전)") { winSearch("win-under15", shift = -15) },
            winAi("win-over15", "상대를 높게 보는 승률 AI", "상대가 실제보다 15점 더 낼 거라고 믿고 둠 (지나치게 모험)") { winSearch("win-over15", shift = 15) },
            winAi("win-under30", "상대를 무시하는 승률 AI", "상대가 30점 덜 낼 거라고 믿음 (아주 소극적)") { winSearch("win-under30", shift = -30) },
            winAi("win-over30", "상대를 두려워하는 승률 AI", "상대가 30점 더 낼 거라고 믿음 (늘 무리수)") { winSearch("win-over30", shift = 30) },
            winAi("win-slip5", "가끔 실수하는 승률 AI", "승률 AI지만 판단마다 5% 확률로 차선") { r -> SlipPlayer(winPlanner(plain, budget), 0.05, r, "win-slip5") },
            winAi("win-slip10", "실수가 잦은 승률 AI", "승률 AI지만 판단마다 10% 확률로 차선") { r -> SlipPlayer(winPlanner(plain, budget), 0.10, r, "win-slip10") },
            winAi("win-slip20", "덜렁이 승률 AI", "승률 AI지만 판단마다 20% 확률로 차선") { r -> SlipPlayer(winPlanner(plain, budget), 0.20, r, "win-slip20") },
            winAi("late-win", "막판 승부사", "기대점수로 두다가 남은 4칸부터 승률을 계산") {
                PhasePlayer("late-win", PlanPlayer("ev", evPlanner), winSearch("late-win"), lateFrom = 4)
            },
            winAi("early-win", "초반만 계산파", "남은 칸이 9개 이상일 때만 승률을 계산하고 이후는 기대점수") {
                PhasePlayer("early-win", winSearch("early-win"), PlanPlayer("ev", evPlanner), lateFrom = 8)
            },

            // ---- max expectation, plain and with a taste ---------------------------------------
            LeagueAi("ev", "기대점수 AI", Family.EV, "평균 점수를 최대화하는 정석 (레이팅 기준 1500)") { winSearch("ev", mode = Mode.EXPECTED_SCORE) },
            shaped("myopic-0.3", "심한 근시안 (미래 30%)", "앞으로 받을 점수를 30%만 쳐 줌", future = 0.3),
            shaped("myopic-0.5", "근시안 (미래 50%)", "앞으로 받을 점수를 절반만 쳐 줌", future = 0.5),
            shaped("myopic-0.8", "약한 근시안 (미래 80%)", "앞으로 받을 점수를 80%만 쳐 줌", future = 0.8),
            shaped("farsight-1.2", "미래 과신 (120%)", "앞으로 받을 점수를 20% 부풀려 봄", future = 1.2),
            shaped("farsight-1.5", "미래 맹신 (150%)", "앞으로 받을 점수를 50% 부풀려 봄", future = 1.5),
            shaped("yacht-lover", "요트 집착", "요트 성공을 25점 더 값지게 봄") { _, c, p, _ -> if (c == Category.YACHT && p > 0) 25.0 else 0.0 },
            shaped("yacht-maniac", "요트 광신도", "요트 성공을 60점 더 값지게 봄") { _, c, p, _ -> if (c == Category.YACHT && p > 0) 60.0 else 0.0 },
            shaped("yacht-hater", "요트 무시", "요트는 운이라며 성공해도 20점 깎아서 봄") { _, c, p, _ -> if (c == Category.YACHT && p > 0) -20.0 else 0.0 },
            shaped("straight-lover", "스트레이트 집착", "스트레이트 성공을 10점 더 값지게 봄") { _, c, p, _ ->
                if ((c == Category.SMALL_STRAIGHT || c == Category.LARGE_STRAIGHT) && p > 0) 10.0 else 0.0
            },
            shaped("fullhouse-lover", "풀하우스 집착", "풀하우스 성공을 8점 더 값지게 봄") { _, c, p, _ -> if (c == Category.FULL_HOUSE && p > 0) 8.0 else 0.0 },
            shaped("fourkind-lover", "포카드 집착", "포카드 성공을 8점 더 값지게 봄") { _, c, p, _ -> if (c == Category.FOUR_OF_A_KIND && p > 0) 8.0 else 0.0 },
            shaped("bonus-lover", "보너스 집착", "상단 점수 1점마다 0.5점 덤을 붙여 봄") { me, c, _, u2 -> if (c.isUpper) 0.5 * (u2 - me.u) else 0.0 },
            shaped("bonus-maniac", "보너스 광신도", "상단 점수 1점마다 1.5점 덤을 붙여 봄") { me, c, _, u2 -> if (c.isUpper) 1.5 * (u2 - me.u) else 0.0 },
            shaped("zero-hater", "0점 싫어", "칸을 0점으로 지우면 8점 손해로 봄") { _, _, p, _ -> if (p == 0) -8.0 else 0.0 },
            shaped("zero-phobia", "0점 공포증", "칸을 0점으로 지우면 20점 손해로 봄") { _, _, p, _ -> if (p == 0) -20.0 else 0.0 },
            shaped("choice-saver", "초이스 아끼기", "마지막 3턴 전에는 초이스를 쓰면 10점 손해로 봄") { me, c, _, _ ->
                if (c == Category.CHOICE && me.filledCount < 9) -10.0 else 0.0
            },
            shaped("choice-spender", "초이스 막 쓰기", "초이스에 넣으면 늘 6점 덤으로 봄") { _, c, _, _ -> if (c == Category.CHOICE) 6.0 else 0.0 },
            shaped("upper-snob", "상단 깔끔이", "상단 칸에 세 개 미만을 넣으면 6점 손해로 봄") { me, c, p, _ ->
                if (c.isUpper && p < 3 * c.face) -6.0 else 0.0
            },
            shaped("big-lover", "큰 족보 사랑", "포카드·풀하우스·스트레이트·요트 성공마다 5점 덤") { _, c, p, _ -> if (lower(c) && p > 0) 5.0 else 0.0 },

            // ---- risk attitude ------------------------------------------------------------------
            riskAi("risk-0.1", "철벽", -0.1, "크게 망하는 걸 극도로 싫어함(θ=−0.1)"),
            riskAi("risk-0.06", "겁쟁이", -0.06, "크게 망하는 걸 아주 싫어함(θ=−0.06)"),
            riskAi("risk-0.03", "신중파", -0.03, "크게 망하는 걸 싫어함(θ=−0.03)"),
            riskAi("risk-0.01", "살짝 신중", -0.01, "아주 약간 안전 쪽(θ=−0.01)"),
            riskAi("risk+0.01", "살짝 모험", 0.01, "아주 약간 큰 점수 쪽(θ=+0.01)"),
            riskAi("risk+0.03", "모험가", 0.03, "큰 점수를 좋아함(θ=+0.03)"),
            riskAi("risk+0.06", "도박꾼", 0.06, "큰 점수를 아주 좋아함(θ=+0.06)"),
            riskAi("risk+0.1", "올인러", 0.1, "한 방만 노림(θ=+0.1)"),

            // ---- reading the game ---------------------------------------------------------------
            LeagueAi("mood", "눈치파", Family.SITUATION, "예상 점수로 15점 앞서면 신중(θ=−0.03), 뒤지면 모험(θ=+0.03)") {
                SituationalPlayer("mood", ev, 15.0, riskOf(-0.03), riskOf(0.03))
            },
            LeagueAi("mood-strong", "극단 눈치파", Family.SITUATION, "예상 점수로 10점 앞서면 철벽(θ=−0.06), 뒤지면 도박(θ=+0.06)") {
                SituationalPlayer("mood-strong", ev, 10.0, riskOf(-0.06), riskOf(0.06))
            },
            LeagueAi("comeback", "역전 노리기", Family.SITUATION, "예상 점수로 15점 이상 뒤질 때만 한 방(θ=+0.06)") {
                SituationalPlayer("comeback", ev, 15.0, null, riskOf(0.06))
            },
            LeagueAi("closer", "굳히기", Family.SITUATION, "예상 점수로 15점 이상 앞설 때만 철벽(θ=−0.06)") {
                SituationalPlayer("closer", ev, 15.0, riskOf(-0.06), null)
            },
            LeagueAi("tilt", "멘탈 약함", Family.SITUATION, "평소 실수 3%, 예상 점수로 15점 이상 뒤지면 25%") { r ->
                SlipPlayer(evPlanner, 0.03, r, "tilt", slipOf = { me, opp ->
                    fun e(b: Board) = b.total + if (b.isComplete) 0.0 else ev[b.mask, b.u]
                    if (e(me) - e(opp) <= -15) 0.25 else 0.03
                })
            },
            LeagueAi("tired", "지구력 부족", Family.SITUATION, "처음엔 완벽하다가 갈수록 실수가 늘어 마지막 턴엔 25%") { r ->
                SlipPlayer(evPlanner, 0.0, r, "tired", slipOf = { me, _ -> 0.25 * me.filledCount / (Category.COUNT - 1) })
            },
            LeagueAi("moody", "컨디션 기복", Family.SITUATION, "판마다 실수 확률이 0~30% 사이에서 정해짐") { r ->
                val mood = r.nextDouble() * 0.3
                SlipPlayer(evPlanner, mood, r, "moody")
            },
            LeagueAi("warmup", "슬로 스타터", Family.SITUATION, "초반 4턴은 실수 20%, 이후엔 정석") { r ->
                SlipPlayer(evPlanner, 0.0, r, "warmup", slipOf = { me, _ -> if (me.filledCount < 4) 0.2 else 0.0 })
            },

            // ---- human-like mistakes ------------------------------------------------------------
            slip("slip1", "거의 완벽 (실수 1%)", 0.01, "기대점수 정석, 판단마다 1% 확률로 차선"),
            slip("slip3", "실수 3%", 0.03, "판단마다 3% 확률로 차선"),
            slip("slip5", "가끔 실수 5%", 0.05, "판단마다 5% 확률로 차선"),
            slip("slip10", "가끔 실수 10%", 0.10, "판단마다 10% 확률로 차선"),
            slip("slip15", "실수 15%", 0.15, "판단마다 15% 확률로 차선"),
            slip("slip20", "자주 실수 20%", 0.20, "판단마다 20% 확률로 차선"),
            slip("slip35", "실수 많음 35%", 0.35, "판단마다 35% 확률로 차선"),
            slip("slip50", "반반 (실수 50%)", 0.50, "판단마다 절반은 차선"),
            slip("keep-slip10", "굴림 실수 10%", 0.10, "남길 주사위만 10% 확률로 잘못 고름, 칸은 정확", boxes = false),
            slip("box-slip10", "칸 실수 10%", 0.10, "주사위는 정확, 기록할 칸만 10% 확률로 잘못 고름", keeps = false),
            soft("soft0.1", "감으로 두기 (달인)", 0.1, "0.1점 안쪽 차이만 헷갈림(τ=0.1점)"),
            soft("soft0.3", "감으로 두기 (고수)", 0.3, "1점 안쪽 차이만 헷갈리는 감(τ=0.3점)"),
            soft("soft0.5", "감으로 두기 (상급)", 0.5, "1~2점 차이를 가끔 헷갈림(τ=0.5점)"),
            soft("soft1", "감으로 두기 (중수)", 1.0, "몇 점 차이는 자주 헷갈림(τ=1점)"),
            soft("soft2", "감으로 두기 (초보)", 2.0, "웬만한 차이는 감으로(τ=2점)"),
            soft("soft4", "감으로 두기 (입문)", 4.0, "큰 차이가 아니면 거의 아무거나(τ=4점)"),

            // ---- simple rules -------------------------------------------------------------------
            LeagueAi("half-ev", "굴리기만 정석", Family.RULE, "주사위는 정석대로 남기고, 칸은 지금 점수가 가장 큰 곳") { HalfEvPlayer(ev, rules) },
            LeagueAi("keeper-ev", "칸만 정석", Family.RULE, "늘 가장 많은 눈만 남기지만, 칸은 정석대로") { KeeperEvBoxPlayer(ev) },
            LeagueAi("two-throws", "두 번만 굴림", Family.RULE, "정석대로 두지만 한 턴에 두 번까지만 굴림") { LimitedThrowsPlayer(ev, 2, "two-throws") },
            LeagueAi("rule", "생활 규칙", Family.RULE, "족보가 되면 기록, 아니면 같은 눈 모으기, 큰 칸부터") { RulePlayer(rules) },
            LeagueAi("greedy", "한 턴 욕심", Family.RULE, "이번 턴 점수만 최대화") { GreedyPlayer() },
            LeagueAi("upper-first", "상단 먼저", Family.RULE, "같은 눈만 모으고, 세 개 이상이면 상단 칸부터") { KeeperPlayer(rules, upperFirst = true, name = "upper-first") },
            LeagueAi("keeper", "같은 눈 모으기", Family.RULE, "늘 가장 많은 눈만 남기고, 지금 점수가 큰 칸") { KeeperPlayer(rules, upperFirst = false) },
            LeagueAi("lazy", "안 굴리기", Family.RULE, "첫 굴림 그대로 가장 점수가 큰 칸") { LazyPlayer() },
            LeagueAi("random", "무작위", Family.RULE, "주사위도 칸도 무작위") { r -> RandomPlayer(r) },
        )
    }
}

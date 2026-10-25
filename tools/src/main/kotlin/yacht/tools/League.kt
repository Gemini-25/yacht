package yacht.tools

import yacht.engine.Advisor
import yacht.engine.AdvisorPlayer
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.DiceStream
import yacht.engine.EvTable
import yacht.engine.GreedyPlayer
import yacht.engine.HalfEvPlayer
import yacht.engine.KeeperPlayer
import yacht.engine.LazyPlayer
import yacht.engine.Mode
import yacht.engine.PlanPlayer
import yacht.engine.Player
import yacht.engine.RandomPlayer
import yacht.engine.RulePlayer
import yacht.engine.RuleSet
import yacht.engine.SlipPlayer
import yacht.engine.SoftmaxPlayer
import yacht.engine.TableBuilder
import yacht.engine.Tables
import yacht.engine.riskPlanner
import yacht.engine.shapedPlanner
import yacht.engine.winPlanner
import java.io.File
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln
import kotlin.math.log10

/** One AI of the league: a fresh player per game ([make] gets that game's random source). */
class Entrant(val key: String, val name: String, val family: String, val desc: String, val make: (Random) -> Player)

/** Thirty-odd AIs from the exact win search down to random play. Win-search players use [budget]. */
fun leagueRoster(tables: Tables, budget: Long): List<Entrant> {
    val rules = tables.rules
    val ev = tables.ev
    val plain = Advisor(tables)
    val minimax = Advisor(tables, adversarial = true)
    val risk = listOf(0.03, 0.06, -0.03, -0.06).associateWith { EvTable(rules, TableBuilder(rules, theta = it).build(withDist = false).ev) }
    fun win(key: String, name: String, desc: String, make: (Random) -> Player) = Entrant(key, name, "승률형", desc, make)
    fun exp(key: String, name: String, desc: String, make: (Random) -> Player) = Entrant(key, name, "기대점수형", desc, make)
    fun shaped(key: String, name: String, desc: String, future: Double = 1.0, extra: (Board, Category, Int, Int) -> Double = { _, _, _, _ -> 0.0 }) =
        exp(key, name, desc) { PlanPlayer(key, shapedPlanner(ev, future, extra)) }
    return listOf(
        win("win", "승률 AI (앱 기본)", "승률을 최대화. 앞 몇 턴은 정확, 막판 8~9칸부터 끝까지 정확") { AdvisorPlayer(plain, Mode.WIN, budget, name = "win") },
        win("win-d1", "승률 AI · 이번 턴만", "이번 턴만 정확히 풀고 이후는 기대점수 방식으로 근사") { AdvisorPlayer(plain, Mode.WIN, budget, fixedDepth = 1, name = "win-d1") },
        win("win-d2", "승률 AI · 2턴", "앞 2턴을 정확히 풀고 이후는 근사") { AdvisorPlayer(plain, Mode.WIN, budget, fixedDepth = 2, name = "win-d2") },
        win("duel", "승률 AI · 막판 대결표", "막판(약 5칸)부터 상대의 실제 결과에 대응하는 표로 끝까지 정확") {
            AdvisorPlayer(plain, Mode.WIN, budget, duelOps = 500_000_000L, name = "duel")
        },
        win("minimax", "최소최대 AI", "막판에 상대가 항상 나에게 가장 불리한 수를 둔다고 가정") {
            AdvisorPlayer(minimax, Mode.WIN, budget, duelOps = 500_000_000L, name = "minimax")
        },
        win("win-tie", "승률 AI · 무승부도 반승", "무승부를 절반의 승리로 쳐서 계산") { AdvisorPlayer(plain, Mode.WIN, budget, tieValue = 0.5, name = "win-tie") },
        win("win-under", "상대를 얕보는 승률 AI", "상대가 실제보다 15점 덜 낼 거라고 믿고 둠 (지나치게 안전)") {
            AdvisorPlayer(plain, Mode.WIN, budget, oppShift = -15, name = "win-under")
        },
        win("win-over", "상대를 과대평가하는 승률 AI", "상대가 실제보다 15점 더 낼 거라고 믿고 둠 (지나치게 모험)") {
            AdvisorPlayer(plain, Mode.WIN, budget, oppShift = 15, name = "win-over")
        },
        win("win-slip10", "가끔 실수하는 승률 AI", "승률 AI지만 판단마다 10% 확률로 차선") { r -> SlipPlayer(winPlanner(plain, budget), 0.10, r, "win-slip10") },

        exp("ev", "기대점수 AI", "평균 점수를 최대화하는 정석 (기준 1500)") { AdvisorPlayer(plain, Mode.EXPECTED_SCORE, name = "ev") },
        shaped("myopic-0.5", "근시안 (미래 50%)", "앞으로 받을 점수를 절반만 쳐 줌", future = 0.5),
        shaped("myopic-0.8", "약한 근시안 (미래 80%)", "앞으로 받을 점수를 80%만 쳐 줌", future = 0.8),
        shaped("farsight-1.2", "미래 과신 (120%)", "앞으로 받을 점수를 20% 부풀려 봄", future = 1.2),
        shaped("bonus-lover", "보너스 집착", "상단 점수 1점마다 0.5점 덤을 붙여 봄") { me, c, _, u2 -> if (c.isUpper) 0.5 * (u2 - me.u) else 0.0 },
        shaped("yacht-lover", "요트 집착", "요트 성공을 25점 더 값지게 봄") { _, c, p, _ -> if (c == Category.YACHT && p > 0) 25.0 else 0.0 },
        shaped("straight-lover", "스트레이트 집착", "스트레이트 성공을 10점 더 값지게 봄") { _, c, p, _ ->
            if ((c == Category.SMALL_STRAIGHT || c == Category.LARGE_STRAIGHT) && p > 0) 10.0 else 0.0
        },
        shaped("choice-saver", "초이스 아끼기", "마지막 3턴 전에는 초이스를 쓰면 10점 손해로 봄") { me, c, _, _ -> if (c == Category.CHOICE && me.filledCount < 9) -10.0 else 0.0 },
        shaped("zero-hater", "0점 싫어", "칸을 0점으로 지우면 8점 손해로 봄") { _, _, p, _ -> if (p == 0) -8.0 else 0.0 },

        Entrant("risk+0.03", "모험가", "위험 성향", "큰 점수를 좋아하는 효용(θ=+0.03)으로 둠") { PlanPlayer("risk+0.03", riskPlanner(risk.getValue(0.03), 0.03)) },
        Entrant("risk+0.06", "도박꾼", "위험 성향", "큰 점수를 아주 좋아함(θ=+0.06)") { PlanPlayer("risk+0.06", riskPlanner(risk.getValue(0.06), 0.06)) },
        Entrant("risk-0.03", "신중파", "위험 성향", "크게 망하는 걸 싫어함(θ=−0.03)") { PlanPlayer("risk-0.03", riskPlanner(risk.getValue(-0.03), -0.03)) },
        Entrant("risk-0.06", "겁쟁이", "위험 성향", "크게 망하는 걸 아주 싫어함(θ=−0.06)") { PlanPlayer("risk-0.06", riskPlanner(risk.getValue(-0.06), -0.06)) },

        Entrant("slip5", "가끔 실수 5%", "사람 같은 실수", "기대점수 정석, 판단마다 5% 확률로 차선") { r -> SlipPlayer(ev, 0.05, r, "slip5") },
        Entrant("slip10", "가끔 실수 10%", "사람 같은 실수", "판단마다 10% 확률로 차선") { r -> SlipPlayer(ev, 0.10, r, "slip10") },
        Entrant("slip20", "자주 실수 20%", "사람 같은 실수", "판단마다 20% 확률로 차선") { r -> SlipPlayer(ev, 0.20, r, "slip20") },
        Entrant("slip35", "실수 많음 35%", "사람 같은 실수", "판단마다 35% 확률로 차선") { r -> SlipPlayer(ev, 0.35, r, "slip35") },
        Entrant("soft0.3", "감으로 두기 (고수)", "사람 같은 실수", "1점 안쪽 차이만 헷갈리는 감(τ=0.3점)") { r -> SoftmaxPlayer(ev, 0.3, r, "soft0.3") },
        Entrant("soft1", "감으로 두기 (중수)", "사람 같은 실수", "몇 점 차이는 자주 헷갈림(τ=1점)") { r -> SoftmaxPlayer(ev, 1.0, r, "soft1") },
        Entrant("soft2", "감으로 두기 (초보)", "사람 같은 실수", "웬만한 차이는 감으로(τ=2점)") { r -> SoftmaxPlayer(ev, 2.0, r, "soft2") },

        Entrant("half-ev", "굴리기만 정석", "단순 규칙", "주사위는 기대점수 정석대로 남기고, 칸은 지금 점수가 가장 큰 곳") { HalfEvPlayer(ev, rules) },
        Entrant("rule", "생활 규칙", "단순 규칙", "족보가 되면 기록, 아니면 같은 눈 모으기, 큰 칸부터") { RulePlayer(rules) },
        Entrant("greedy", "한 턴 욕심", "단순 규칙", "이번 턴 점수만 최대화") { GreedyPlayer() },
        Entrant("upper-first", "상단 먼저", "단순 규칙", "같은 눈만 모으고, 세 개 이상이면 상단 칸부터") { KeeperPlayer(rules, upperFirst = true, name = "upper-first") },
        Entrant("keeper", "같은 눈 모으기", "단순 규칙", "늘 가장 많은 눈만 남기고, 지금 점수가 큰 칸") { KeeperPlayer(rules, upperFirst = false) },
        Entrant("lazy", "안 굴리기", "단순 규칙", "첫 굴림 그대로 가장 점수가 큰 칸") { LazyPlayer() },
        Entrant("random", "무작위", "단순 규칙", "주사위도 칸도 무작위") { r -> RandomPlayer(r) },
    )
}

private fun playTurn(rules: RuleSet, p: Player, me: Board, opp: Board, stream: DiceStream, turn: Int): Board {
    p.startTurn(me, opp)
    var r = stream.throwDice(Dice.EMPTY_KEEP, turn, 0)
    for (rollsDone in 1..2) {
        val keep = p.chooseKeep(rollsDone, r)
        if (keep == Dice.rollAsKeep[r]) break
        r = stream.throwDice(keep, turn, rollsDone)
    }
    val c = p.chooseCategory(r)
    return me.with(c, rules.score(Dice.rollCounts[r], c))
}

private fun playGame(rules: RuleSet, first: Player, second: Player, s0: DiceStream, s1: DiceStream): IntArray {
    var a = Board(rules)
    var b = Board(rules)
    for (turn in 0 until Category.COUNT) {
        a = playTurn(rules, first, a, b, s0, turn)
        b = playTurn(rules, second, b, a, s1, turn)
    }
    return intArrayOf(a.total, b.total)
}

/**
 * Round robin: every pair plays every deal twice with the seats swapped on the same dice (the
 * first seat's and the second seat's dice are fixed per deal for the whole league, so all pairs
 * face the same luck). Results go to --csv as "i,j,deal,i first,j second,i second,j first" and a
 * rerun resumes. --only a,b,... limits the roster; --deals, --seed, --budget, --out report.json.
 */
fun league(opts: Map<String, String>, tables: Tables) {
    val deals = opts["deals"]?.toInt() ?: 40
    val seed = opts["seed"]?.toLong() ?: 2026L
    val budget = opts["budget"]?.toDouble()?.toLong() ?: 100_000_000L
    val csv = File(opts["csv"] ?: "league.csv")
    val only = opts["only"]?.split(',')?.toSet()
    val roster = leagueRoster(tables, budget).filter { only == null || it.key in only }
    val rules = tables.rules
    val done = HashSet<String>()
    if (csv.exists()) csv.forEachLine { l -> l.split(',').takeIf { it.size >= 7 }?.let { done += "${it[0]},${it[1]},${it[2]}" } }
    val tasks = ArrayList<Triple<Int, Int, Int>>()
    for (d in 0 until deals) for (i in roster.indices) for (j in i + 1 until roster.size) {
        if ("${roster[i].key},${roster[j].key},$d" !in done) tasks += Triple(i, j, d)
    }
    println("league: ${roster.size} AIs, $deals deals, ${tasks.size} pair-deals to play (${done.size} done)")
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threads)
    val count = AtomicInteger()
    val t0 = System.nanoTime()
    csv.parentFile?.mkdirs()
    val out = java.io.FileWriter(csv, true)
    try {
        pool.invokeAll(tasks.map { (i, j, d) ->
            Callable {
                val s0 = DiceStream(DiceStream.mix(seed, d.toLong(), 0))
                val s1 = DiceStream(DiceStream.mix(seed, d.toLong(), 1))
                fun rng(game: Int, seat: Int) = Random(DiceStream.mix(seed, d.toLong(), i.toLong(), j.toLong(), game.toLong(), seat.toLong()))
                val x = playGame(rules, roster[i].make(rng(0, 0)), roster[j].make(rng(0, 1)), s0, s1)
                val y = playGame(rules, roster[j].make(rng(1, 0)), roster[i].make(rng(1, 1)), s0, s1)
                val line = "${roster[i].key},${roster[j].key},$d,${x[0]},${x[1]},${y[1]},${y[0]}\n"
                synchronized(out) {
                    out.write(line)
                    out.flush()
                }
                val n = count.incrementAndGet()
                if (n % 200 == 0 || n == tasks.size) {
                    val secs = (System.nanoTime() - t0) / 1e9
                    System.err.println("  $n/${tasks.size} pair-deals, %.0fs, about %.0f min left".format(secs, secs / n * (tasks.size - n) / 60))
                }
            }
        }).forEach { it.get() }
    } finally {
        out.close()
        pool.shutdown()
    }
    opts["out"]?.let { leagueReport(csv, File(it), roster, deals, seed, budget) }
}

/**
 * Bradley-Terry strengths (ties count half) fitted by minorisation-maximisation, as Elo points
 * with the max-expectation AI at 1500; 95% intervals from resampling the deals.
 */
fun leagueReport(csv: File, out: File, roster: List<Entrant>, deals: Int, seed: Long, budget: Long) {
    val idx = roster.withIndex().associate { it.value.key to it.index }
    val n = roster.size
    // per deal: pair results
    class Res(val i: Int, val j: Int, val d: Int, val iFirst: Int, val jSecond: Int, val iSecond: Int, val jFirst: Int)
    val results = csv.readLines().mapNotNull { l ->
        val f = l.split(',')
        if (f.size < 7) return@mapNotNull null
        val i = idx[f[0]] ?: return@mapNotNull null
        val j = idx[f[1]] ?: return@mapNotNull null
        Res(i, j, f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt())
    }.distinctBy { Triple(it.i, it.j, it.d) }
    val dealIds = results.map { it.d }.distinct().sorted()
    fun pts(a: Int, b: Int) = if (a > b) 1.0 else if (a == b) 0.5 else 0.0
    val byDeal = results.groupBy { it.d }

    fun fit(sample: List<Int>): DoubleArray {
        val w = Array(n) { DoubleArray(n) }
        val g = Array(n) { DoubleArray(n) }
        for (d in sample) for (r in byDeal[d].orEmpty()) {
            val s = pts(r.iFirst, r.jSecond) + pts(r.iSecond, r.jFirst)
            w[r.i][r.j] += s
            w[r.j][r.i] += 2 - s
            g[r.i][r.j] += 2.0
            g[r.j][r.i] += 2.0
        }
        // A light prior (0.1 win and 0.1 loss against every opponent) keeps hopeless players finite.
        for (i in 0 until n) for (j in 0 until n) if (i != j && g[i][j] > 0) {
            w[i][j] += 0.1
            g[i][j] += 0.2
        }
        val pi = DoubleArray(n) { 1.0 }
        repeat(2000) {
            for (i in 0 until n) {
                var wins = 0.0
                var den = 0.0
                for (j in 0 until n) if (j != i && g[i][j] > 0) {
                    wins += w[i][j]
                    den += g[i][j] / (pi[i] + pi[j])
                }
                if (den > 0) pi[i] = wins / den
            }
            val mean = pi.map { ln(it) }.average()
            for (i in 0 until n) pi[i] = Math.exp(ln(pi[i]) - mean)
        }
        val anchor = idx["ev"]?.let { 400 * log10(pi[it]) } ?: 0.0
        return DoubleArray(n) { 1500 + 400 * log10(pi[it]) - anchor }
    }

    val elo = fit(dealIds)
    val rng = Random(7)
    val boots = List(200) { fit(List(dealIds.size) { dealIds[rng.nextInt(dealIds.size)] }) }
    val lo = DoubleArray(n) { i -> boots.map { it[i] }.sorted()[4] }
    val hi = DoubleArray(n) { i -> boots.map { it[i] }.sorted()[194] }

    val wins = IntArray(n)
    val ties = IntArray(n)
    val games = IntArray(n)
    val score = LongArray(n)
    val h2hW = Array(n) { DoubleArray(n) }
    val h2hG = Array(n) { IntArray(n) }
    for (r in results) {
        for ((me, op, a, b) in listOf(listOf(r.i, r.j, r.iFirst, r.jSecond), listOf(r.i, r.j, r.iSecond, r.jFirst))) {
            games[me]++
            games[op]++
            score[me] += a.toLong()
            score[op] += b.toLong()
            when {
                a > b -> wins[me]++
                a < b -> wins[op]++
                else -> {
                    ties[me]++
                    ties[op]++
                }
            }
            h2hW[me][op] += pts(a, b)
            h2hW[op][me] += pts(b, a)
            h2hG[me][op]++
            h2hG[op][me]++
        }
    }
    val order = (0 until n).sortedByDescending { elo[it] }
    println("rank  rating (95%)        win%   avg   AI")
    for ((rank, i) in order.withIndex()) {
        val e = roster[i]
        val wp = (wins[i] + 0.5 * ties[i]) / games[i].coerceAtLeast(1)
        println("%3d  %5.0f (%4.0f~%4.0f)  %5.1f%%  %5.1f  %s [%s]".format(rank + 1, elo[i], lo[i], hi[i], 100 * wp, score[i].toDouble() / games[i].coerceAtLeast(1), e.name, e.key))
    }
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val json = buildString {
        append("{\"meta\":{\"deals\":${dealIds.size},\"plannedDeals\":$deals,\"seed\":$seed,\"budget\":$budget,\"games\":${results.size * 2}},")
        append("\"players\":[")
        append(order.joinToString(",") { i ->
            val e = roster[i]
            "{\"key\":${q(e.key)},\"name\":${q(e.name)},\"family\":${q(e.family)},\"desc\":${q(e.desc)}," +
                "\"elo\":%.1f,\"lo\":%.1f,\"hi\":%.1f,\"games\":${games[i]},\"wins\":${wins[i]},\"ties\":${ties[i]},\"avg\":%.2f}".format(
                    elo[i], lo[i], hi[i], score[i].toDouble() / games[i].coerceAtLeast(1),
                )
        })
        append("],\"h2h\":[")
        append(order.joinToString(",") { i -> "[" + order.joinToString(",") { j -> if (i == j || h2hG[i][j] == 0) "null" else "%.4f".format(h2hW[i][j] / h2hG[i][j]) } + "]" })
        append("]}")
    }
    out.parentFile?.mkdirs()
    out.writeText(json)
    println("wrote ${out.path}")
}

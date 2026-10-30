package yacht.tools

import yacht.engine.Advisor
import yacht.engine.AdvisorPlayer
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.DiceStream
import yacht.engine.Duplicate
import yacht.engine.DuelSearch
import yacht.engine.Match
import yacht.engine.Mode
import yacht.engine.RuleSet
import yacht.engine.TableBuilder
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.WinSearch
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

private val rules = RuleSet.NINTENDO

fun main(args: Array<String>) {
    val opts = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    when (args.firstOrNull()) {
        "gen" -> gen(
            File(opts["out"] ?: "tables"),
            if (opts["precision"] == "float") 4 else 2,
            opts["risk"]?.split(',')?.filter { it.isNotBlank() }?.map { it.toDouble() } ?: DEFAULT_RISK,
        )
        "sim" -> sim(opts)
        "duel" -> duel(opts)
        "explain" -> explain(opts)
        "headroom" -> headroom(opts)
        "arena" -> arena(opts)
        "calib" -> calib(opts)
        "versus" -> versus(opts)
        "quiz" -> quizSamples(opts)
        "review" -> reviewSamples(opts)
        "commentary" -> commentarySamples(opts)
        "ladder" -> ladderRun(opts)
        "cost" -> cost(opts)
        "adapt" -> adapt(opts)
        "league" -> league(opts, loadTables(File(opts["tables"] ?: "tables")).withoutRisk())
        "league-report" -> {
            val t = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
            val budget = opts["budget"]?.toDouble()?.toLong() ?: 100_000_000L
            leagueReport(
                File(opts["csv"] ?: "league.csv"), File(opts["out"] ?: "league.json"), leagueRoster(t, budget),
                opts["deals"]?.toInt() ?: 0, opts["seed"]?.toLong() ?: 2026L, budget,
            )
        }
        "bench" -> bench(File(opts["tables"] ?: "tables"), opts["budget"]?.toLong() ?: Advisor.DEFAULT_BUDGET, opts["only"], opts["ties"] != null)
        else -> println(
            """
            usage:
              gen   --out DIR [--precision half|float] [--risk T1,T2,..]
                    build ev.bin, dist.bin and risk_N.bin (risk-attitude policies for search leaves)
              sim   --tables DIR --games N --a win|ev --b win|ev [--budget OPS] [--depthA D] [--depthB D] [--seed S]
              duel  --tables DIR --games N --players ev,win,win-d1,win-b1e9,duel-1e9 [--seed S] [--from F --csv FILE]
                    paired comparison against a max-expectation opponent with shared dice
              bench --tables DIR [--budget OPS]       time the win search at several game stages
              headroom --tables DIR --games N [--base 3e8] [--strong 3e9,1e10] [--seed S] [--from F --csv FILE]
                    how much P(win) per game deeper searches would add: plays the base policy and sums,
                    at every decision, the loss of its choice as valued by each stronger search
              arena --tables DIR --games N [--opp rule,greedy,slip10,ev,self] [--budget OPS] [--seed S]
                    head-to-head win rate of the win AI against other AIs, both seats, with the
                    max-expectation AI on the same games as a paired reference
              versus --tables DIR --a SPEC --b SPEC --games N [--budget OPS] [--seed S] [--from F --csv FILE]
                    two AIs against each other, each deal played twice with the seats swapped;
                    SPEC: ev | win | duel-X (end-game table vs a max-expectation opponent, <= X ops)
                    | aduel-X (end-game table vs an opponent playing to beat me)
              adapt --tables DIR [--opps greedy,keeper,slip20,soft3,rule,ev] [--record N] [--games N] [--threads T] [--seed S]
                    상대 맞춤 승률 AI vs the app's win AI on the same deals against each opponent,
                    the opponent's model taken from its finals in N games against the max-expectation AI
              explain --tables DIR [--games N] [--seed S] [--stray P]
                    print the Korean explanations of whole games (P = chance of not following advice)
            """.trimIndent(),
        )
    }
}

/**
 * Risk attitudes stored for search leaves (see TableBuilder.theta). Off by default: with
 * thetas -0.03, 0.03, 0.06 the paired duel measured -0.25%p ±0.28 against plain leaves.
 */
val DEFAULT_RISK = emptyList<Double>()

private fun gen(out: File, valueBytes: Int, risk: List<Double>) {
    out.mkdirs()
    out.listFiles()?.filter { it.name.startsWith("risk_") }?.forEach { it.delete() }
    var t0 = System.nanoTime()
    val b = TableBuilder(rules).build(withDist = true)
    println("built in %.1fs; optimal expected score %.4f".format((System.nanoTime() - t0) / 1e9, b.ev[0]))
    File(out, "ev.bin").outputStream().use { TableIO.writeEv(it, rules, b.ev) }
    File(out, "dist.bin").outputStream().use { TableIO.writeDist(it, rules, b.distLo, b.dist, valueBytes) }
    File(out, "bonus.bin").outputStream().use { TableIO.writeBonus(it, rules, b.bonus) }
    println("  P(bonus) at the start %.4f".format(b.bonus[0]))
    for (f in listOf("ev.bin", "dist.bin", "bonus.bin")) println("  $f ${File(out, f).length() / 1024} KiB")
    risk.forEachIndexed { i, theta ->
        t0 = System.nanoTime()
        val rb = TableBuilder(rules, theta = theta).build(withDist = true)
        val f = File(out, "risk_$i.bin")
        f.outputStream().use { TableIO.writeDist(it, rules, rb.distLo, rb.dist, valueBytes, theta) }
        val mean = rb.distTable().mean(0, 0)
        println("  ${f.name} theta=$theta mean %.2f, %.1fs, ${f.length() / 1024} KiB".format(mean, (System.nanoTime() - t0) / 1e9))
    }
}

fun loadTables(dir: File): Tables {
    fun map(name: String) = RandomAccessFile(File(dir, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    val risk = dir.listFiles()?.filter { it.name.startsWith("risk_") && it.name.endsWith(".bin") }
        ?.sortedBy { it.name }?.map { TableIO.readDist(map(it.name), rules) } ?: emptyList()
    val bonus = File(dir, "bonus.bin").takeIf { it.exists() }?.let { TableIO.readBonus(map(it.name), rules) }
    return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules), risk, bonus)
}

private fun sim(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables"))
    val games = opts["games"]?.toInt() ?: 1000
    val budget = opts["budget"]?.toLong() ?: Advisor.DEFAULT_BUDGET
    val seed = opts["seed"]?.toLong() ?: 1L
    val advisor = Advisor(tables.withoutRisk())
    fun player(kind: String, depth: String?): AdvisorPlayer = when (kind) {
        "ev" -> AdvisorPlayer(advisor, Mode.EXPECTED_SCORE, name = "ev")
        "win" -> AdvisorPlayer(advisor, Mode.WIN, budget, depth?.toInt(), name = "win" + (depth?.let { "-d$it" } ?: ""))
        else -> error("unknown player $kind")
    }
    val a = opts["a"] ?: "win"
    val b = opts["b"] ?: "ev"
    val threads = Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threads)
    // Game g uses the same dice seed for g and its mirror (seats swapped) to cut variance.
    val stats = Array(2) { IntArray(3) } // [seat of A][A win, tie, B win]
    val scoreSum = LongArray(2)
    val done = AtomicInteger()
    val t0 = System.nanoTime()
    val tasks = (0 until games).map { g ->
        Callable {
            val pa = player(a, opts["depthA"])
            val pb = player(b, opts["depthB"])
            val seat = g % 2
            val rng = Random(seed * 1_000_003 + g / 2)
            val res = if (seat == 0) Match.play(rules, pa, pb, rng) else Match.play(rules, pb, pa, rng)
            val sa = res[seat]
            val sb = res[1 - seat]
            synchronized(stats) {
                stats[seat][if (sa > sb) 0 else if (sa == sb) 1 else 2]++
                scoreSum[0] += sa.toLong()
                scoreSum[1] += sb.toLong()
            }
            val d = done.incrementAndGet()
            if (d % 2000 == 0) System.err.println("  $d/$games")
        }
    }
    pool.invokeAll(tasks).forEach { it.get() }
    pool.shutdown()
    val secs = (System.nanoTime() - t0) / 1e9
    val w = stats[0][0] + stats[1][0]
    val t = stats[0][1] + stats[1][1]
    val p = w.toDouble() / games
    val ci = 1.96 * sqrt(p * (1 - p) / games)
    println("$a vs $b: $games games in %.0fs".format(secs))
    println("  $a win %.2f%% ±%.2f, tie %.2f%%, $b win %.2f%%".format(100 * p, 100 * ci, 100.0 * t / games, 100.0 * (games - w - t) / games))
    for (seat in 0..1) {
        val n = stats[seat].sum().coerceAtLeast(1)
        println("  $a as ${if (seat == 0) "first" else "second"}: win %.2f%% tie %.2f%%".format(100.0 * stats[seat][0] / n, 100.0 * stats[seat][1] / n))
    }
    println("  mean score $a %.2f, $b %.2f".format(scoreSum[0].toDouble() / games, scoreSum[1].toDouble() / games))
}

/** --ties: time the searches with P(tie) tracking as well (and their table sizes). */
private fun bench(dir: File, budget: Long, only: String?, ties: Boolean) {
    val tables = loadTables(dir)
    val rng = Random(3)
    val advisor = Advisor(tables)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val parallel = Advisor(tables, pool)
    for (filled in if (only == "duel") emptyList() else listOf(0, 3, 6, 7, 8, 9, 10, 11)) {
        var worst = 0.0
        var sum = 0.0
        var worstPar = 0.0
        var worstTies = 0.0
        val depths = HashMap<Int, Int>()
        val samples = 6
        repeat(samples) {
            val me = randomBoard(tables, rng, filled)
            val opp = randomBoard(tables, rng, filled)
            val d = WinSearch.chooseDepth(tables, me, budget, opp)
            depths.merge(d, 1, Int::plus)
            var t = System.nanoTime()
            advisor.planTurn(me, opp, depth = d)
            val ms = (System.nanoTime() - t) / 1e6
            t = System.nanoTime()
            parallel.planTurn(me, opp, depth = d)
            val msPar = (System.nanoTime() - t) / 1e6
            if (ties) {
                t = System.nanoTime()
                parallel.planTurn(me, opp, depth = d, ties = true)
                worstTies = maxOf(worstTies, (System.nanoTime() - t) / 1e6)
            }
            worst = maxOf(worst, ms)
            worstPar = maxOf(worstPar, msPar)
            sum += ms
        }
        println("filled=%2d depth=%s  avg %.0f ms, worst %.0f ms (1 thread), worst %.0f ms (pool)".format(filled, depths, sum / samples, worst, worstPar) +
            if (ties) ", worst %.0f ms (pool, ties)".format(worstTies) else "")
    }
    for (filled in if (only == "search") emptyList() else (11 downTo 6).toList()) {
        var worst = 0.0
        var sum = 0.0
        var est = 0L
        var mem = 0L
        var worstTies = 0.0
        var memTies = 0L
        val samples = 4
        repeat(samples) {
            val me = randomBoard(tables, rng, filled)
            val opp = randomBoard(tables, rng, filled)
            est = maxOf(est, DuelSearch.estimateOps(tables, me, opp))
            val t = System.nanoTime()
            val table = parallel.buildDuelTable(me, opp, meToMove = true)
            val ms = (System.nanoTime() - t) / 1e6
            mem = maxOf(mem, table.bytes)
            if (ties) {
                val t2 = System.nanoTime()
                val traced = parallel.buildDuelTable(me, opp, meToMove = true, ties = true)
                worstTies = maxOf(worstTies, (System.nanoTime() - t2) / 1e6)
                memTies = maxOf(memTies, traced.bytes)
            }
            worst = maxOf(worst, ms)
            sum += ms
        }
        println("duel filled=%2d avg %.0f ms, worst %.0f ms (pool), max estimate %.2e ops, max table %.1f MB".format(filled, sum / samples, worst, est.toDouble(), mem / 1e6) +
            if (ties) ", ties: worst %.0f ms, %.1f MB".format(worstTies, memTies / 1e6) else "")
    }
    pool.shutdown()
}

private fun randomBoard(tables: Tables, rng: Random, filled: Int): Board {
    var b = Board(rules)
    for (c in Category.ALL.shuffled(rng).take(filled)) {
        b = b.with(c, tables.scores[Match.throwDice(rng, Dice.EMPTY_KEEP), c.ordinal])
    }
    return b
}

/**
 * "ev", "win" (default budget), "win-dN" (fixed depth), "win-bX" (ops budget, e.g. 1e9).
 * A leading "r" ("rwin", "rwin-d1") also uses the risk-attitude tables at search leaves.
 */
private fun parsePlayer(plain: Advisor, risky: Advisor, spec: String): AdvisorPlayer {
    val advisor = if (spec.startsWith("r")) risky else plain
    val s = spec.removePrefix("r")
    return when {
        s == "ev" -> AdvisorPlayer(advisor, Mode.EXPECTED_SCORE, name = spec)
        s == "win" -> AdvisorPlayer(advisor, Mode.WIN, name = spec)
        s.startsWith("win-d") -> AdvisorPlayer(advisor, Mode.WIN, fixedDepth = s.removePrefix("win-d").toInt(), name = spec)
        s.startsWith("win-b") -> AdvisorPlayer(advisor, Mode.WIN, budgetOps = s.removePrefix("win-b").toDouble().toLong(), name = spec)
        // "win-xK": this turn only, and exact to the end with K boxes left or fewer.
        s.startsWith("win-x") -> AdvisorPlayer(advisor, Mode.WIN, fixedDepth = 1, exactAt = s.removePrefix("win-x").toInt(), name = spec)
        // "duel-X": default budget, plus the exact duel search when it costs at most X ops.
        s.startsWith("duel-") -> AdvisorPlayer(advisor, Mode.WIN, duelOps = s.removePrefix("duel-").toDouble().toLong(), name = spec)
        else -> error("unknown player $spec")
    }
}

private fun duel(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables"))
    val games = opts["games"]?.toInt() ?: 2000
    val seed = opts["seed"]?.toLong() ?: 7L
    val specs = (opts["players"] ?: "ev,win").split(',')
    // --from F: play games F until F+games of this seed (runs can be split and merged via --csv).
    val from = opts["from"]?.toInt() ?: 0
    val csv = opts["csv"]?.let { File(it) }
    val advisor = Advisor(tables.withoutRisk())
    val risky = Advisor(tables)
    val n = specs.size
    // wins[player][seat] and per-game outcomes for paired differences
    val outcome = Array(n) { Array(2) { ByteArray(games) } }
    val scoreSum = LongArray(n)
    val oppSum = LongArray(1)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val done = AtomicInteger()
    val t0 = System.nanoTime()
    val tasks = (0 until games).map { g ->
        Callable {
            val gid = (from + g).toLong()
            val oppStream = DiceStream(DiceStream.mix(seed, gid, 1))
            val myStream = DiceStream(DiceStream.mix(seed, gid, 0))
            val opp = Duplicate.independentGame(rules, AdvisorPlayer(advisor, Mode.EXPECTED_SCORE), oppStream)
            val oppFinal = opp.last().total
            synchronized(oppSum) { oppSum[0] += oppFinal.toLong() }
            val line = StringBuilder("${from + g},$oppFinal")
            for (i in 0 until n) for (seat in 0..1) {
                val player = parsePlayer(advisor, risky, specs[i])
                // seat 0: I move first and see the opponent before its turn; seat 1: after it.
                val me = Duplicate.playTurns(rules, player, myStream) { turn -> opp[turn + seat] }
                outcome[i][seat][g] = if (me.total > oppFinal) 1 else if (me.total == oppFinal) 2 else 0
                synchronized(scoreSum) { scoreSum[i] += me.total.toLong() }
                line.append(",${me.total}")
            }
            if (csv != null) synchronized(csv) { csv.appendText(line.append('\n').toString()) }
            val d = done.incrementAndGet()
            if (d % 500 == 0) System.err.println("  $d/$games")
        }
    }
    pool.invokeAll(tasks).forEach { it.get() }
    pool.shutdown()
    println("duel vs max-expectation opponent: $games games x 2 seats in %.0fs (opp mean %.2f)".format((System.nanoTime() - t0) / 1e9, oppSum[0].toDouble() / games))
    fun winRate(i: Int, seat: Int?) = (if (seat == null) listOf(0, 1) else listOf(seat))
        .sumOf { s -> outcome[i][s].count { it == 1.toByte() } }.toDouble() / (games * (if (seat == null) 2 else 1))
    for (i in 0 until n) {
        val p = winRate(i, null)
        val line = StringBuilder("  %-10s win %.2f%% (first %.2f%%, second %.2f%%) mean %.2f".format(
            specs[i], 100 * p, 100 * winRate(i, 0), 100 * winRate(i, 1), scoreSum[i].toDouble() / (2 * games)))
        if (i > 0) {
            // Paired difference against the first player over the same games and seats.
            val diffs = DoubleArray(2 * games) { k ->
                val seat = k % 2
                val g = k / 2
                (if (outcome[i][seat][g] == 1.toByte()) 1.0 else 0.0) - (if (outcome[0][seat][g] == 1.toByte()) 1.0 else 0.0)
            }
            val mean = diffs.average()
            val sd = sqrt(diffs.sumOf { (it - mean) * (it - mean) } / (diffs.size - 1))
            line.append("  vs %s: %+.2f%%p ±%.2f".format(specs[0], 100 * mean, 100 * 1.96 * sd / sqrt(diffs.size.toDouble())))
        }
        println(line)
    }
}

/** Plays games (win AI vs max-expectation AI) and prints every explanation the app would show. */
private fun explain(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val games = opts["games"]?.toInt() ?: 2
    val stray = opts["stray"]?.toDouble() ?: 0.15
    val mode = if (opts["mode"] == "ev") Mode.EXPECTED_SCORE else Mode.WIN
    val rng = Random(opts["seed"]?.toLong() ?: 3L)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val advisor = Advisor(tables, pool)
    val ex = yacht.engine.explain.Explainer(tables)
    fun show(tag: String, e: yacht.engine.explain.Explanation) {
        println("  [$tag] ${e.summary}")
        for (d in e.details) println("        - $d")
    }
    try {
        repeat(games) { g ->
            println("==================== game ${g + 1} ====================")
            var s = yacht.engine.game.GameState.new(
                yacht.engine.game.DiceSource.RANDOM,
                if (g % 2 == 0) yacht.engine.game.Side.ME else yacht.engine.game.Side.OPP,
                rules,
            )
            var lastMyWin: Double? = null
            while (!s.isOver) {
                if (s.toMove == yacht.engine.game.Side.OPP) {
                    val turn = advisor.planTurn(s.opp, s.me, Mode.EXPECTED_SCORE)
                    var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
                    for (rollsDone in 1..2) {
                        val k = turn.bestKeep(rollsDone, r)
                        if (k == Dice.rollAsKeep[r]) break
                        r = Match.throwDice(rng, k)
                    }
                    val c = turn.bestCategory(r)
                    s = yacht.engine.game.Game.opponentScore(s, c, tables.scores[r, c.ordinal])
                    continue
                }
                val turn = advisor.planTurn(s.me, s.opp, mode)
                val win = turn.startValue.takeIf { mode == Mode.WIN }
                if (win != null) s = yacht.engine.game.Game.noteWinAfterOpponent(s, win)
                println("--- my turn ${s.myTurnNumber}: me ${s.me.total} vs opp ${s.opp.total} (depth ${turn.depth})")
                val last = s.moves.lastOrNull()
                if (last != null && last.side == yacht.engine.game.Side.OPP) {
                    println("  [상대] " + ex.opponentMove(s.opp, last, lastMyWin))
                }
                show("상황", ex.situation(s.me, s.opp, win))
                s = yacht.engine.game.Game.firstThrow(s, rng)
                while (true) {
                    val a = turn.advise(s.draft.rollsDone, s.draft.faces)
                    println("  roll ${a.rollsDone}: ${a.dice.joinToString("")}")
                    show("추천", ex.advice(turn, s.opp, a))
                    val strayNow = rng.nextDouble() < stray
                    if (a.shouldStop) {
                        val pick = if (strayNow && a.categories.size > 1) a.categories[1] else a.bestCategory
                        if (pick != a.bestCategory) {
                            show("비교 ${pick.category.name} ${pick.points}", ex.categoryOption(turn, s.opp, a, pick))
                            println("  [기록] " + ex.categoryDeviation(turn, s.opp, a, pick)?.full)
                        }
                        lastMyWin = pick.value
                        s = yacht.engine.game.Game.score(s, pick.category, a)
                        break
                    }
                    val pick = if (strayNow) a.keeps[1 + rng.nextInt(minOf(3, a.keeps.size - 1))] else a.bestKeep!!
                    if (pick != a.bestKeep) {
                        show("비교 ${pick.held}", ex.keepOption(turn, s.opp, a, pick))
                        println("  [기록] " + ex.keepDeviation(turn, s.opp, a, pick)?.full)
                    }
                    if (pick.held.size == 5) {
                        val c = a.bestCategory
                        lastMyWin = c.value
                        s = yacht.engine.game.Game.score(s, c.category, a)
                        break
                    }
                    s = yacht.engine.game.Game.holdFaces(s, pick.held)
                    s = yacht.engine.game.Game.reroll(s, a, rng)
                }
            }
            show("결과", ex.gameSummary(s))
        }
    } finally {
        pool.shutdown()
    }
}

/**
 * Estimates the gain of a deeper search without playing it out: the base policy plays, and at
 * each of its decisions every stronger search values the base choice against its own best one.
 * By the performance difference lemma the per-game sum estimates P(win)(strong) - P(win)(base),
 * with far less noise than comparing game results.
 */
private fun headroom(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val games = opts["games"]?.toInt() ?: 200
    val seed = opts["seed"]?.toLong() ?: 41L
    val from = opts["from"]?.toInt() ?: 0
    val csv = opts["csv"]?.let { File(it) }
    // --base d1: fixed one-turn search (as the win-d1 duel player); otherwise an ops budget.
    val baseFixed = opts["base"]?.takeIf { it.startsWith("d") }?.removePrefix("d")?.toInt()
    val baseEv = opts["base"] == "ev"
    val base = if (baseFixed != null || baseEv) 0L else opts["base"]?.toDouble()?.toLong() ?: Advisor.DEFAULT_BUDGET
    val strong = (opts["strong"] ?: "3e9,1e10").split(',').map { it.toDouble().toLong() }
    val advisor = Advisor(tables)
    val k = strong.size
    val lossSum = DoubleArray(k)
    val differ = IntArray(k)
    val wins = AtomicInteger()
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val t0 = System.nanoTime()
    val tasks = (0 until games).map { g ->
        Callable {
            val gid = (from + g).toLong()
            val opp = Duplicate.independentGame(rules, AdvisorPlayer(advisor, Mode.EXPECTED_SCORE), DiceStream(DiceStream.mix(seed, gid, 1)))
            val stream = DiceStream(DiceStream.mix(seed, gid, 0))
            val line = StringBuilder()
            for (seat in 0..1) {
                val loss = DoubleArray(k)
                val diffs = IntArray(k)
                var me = Board(rules)
                for (turn in 0 until Category.COUNT) {
                    val o = opp[turn + seat]
                    val baseDepth = if (baseEv) 0 else baseFixed ?: WinSearch.chooseDepth(tables, me, base, o)
                    val plan = if (baseEv) advisor.planTurn(me, o, Mode.EXPECTED_SCORE) else advisor.planTurn(me, o, Mode.WIN, base, depth = baseFixed)
                    val strongPlans = strong.map { b ->
                        if (WinSearch.chooseDepth(tables, me, b, o) == baseDepth) null else advisor.planTurn(me, o, Mode.WIN, b)
                    }
                    if (System.getenv("HEADROOM_DEBUG") != null) {
                        System.err.println("game $gid seat $seat turn $turn depth $baseDepth -> " + strong.map { WinSearch.chooseDepth(tables, me, it, o) })
                    }
                    fun judge(rollsDone: Int, r: Int, chosenKeep: Int?, chosenCat: Category?) {
                        val faces = Dice.rollFaces(r)
                        for ((i, sp) in strongPlans.withIndex()) {
                            if (sp == null) continue
                            val a = sp.advise(rollsDone, faces)
                            val best = if (a.keeps.isNotEmpty()) a.keeps.first().value else a.bestCategory.value
                            val mine = if (chosenKeep != null) {
                                a.keeps.first { it.held == Dice.keepFaces(chosenKeep) }.value
                            } else {
                                a.categories.first { it.category == chosenCat }.value
                            }
                            val l = best - mine
                            if (l > 1e-9) {
                                loss[i] += l
                                diffs[i]++
                            }
                        }
                    }
                    var r = stream.throwDice(Dice.EMPTY_KEEP, turn, 0)
                    for (rollsDone in 1..2) {
                        val keep = plan.bestKeep(rollsDone, r)
                        judge(rollsDone, r, keep, null)
                        if (keep == Dice.rollAsKeep[r]) break
                        r = stream.throwDice(keep, turn, rollsDone)
                    }
                    val c = plan.bestCategory(r)
                    judge(3, r, null, c)
                    me = me.with(c, tables.scores[r, c.ordinal])
                }
                val oppFinal = opp.last().total
                if (me.total > oppFinal) wins.incrementAndGet()
                synchronized(lossSum) {
                    for (i in 0 until k) {
                        lossSum[i] += loss[i]
                        differ[i] += diffs[i]
                    }
                }
                line.append("${from + g},$seat,${me.total},$oppFinal")
                for (i in 0 until k) line.append(",%.7f,%d".format(loss[i], diffs[i]))
                line.append('\n')
            }
            if (csv != null) synchronized(csv) { csv.appendText(line.toString()) }
        }
    }
    pool.invokeAll(tasks).forEach { it.get() }
    pool.shutdown()
    val n = 2 * games
    println("headroom: $games games x 2 seats in %.0fs; base budget %.0e wins %.2f%%".format((System.nanoTime() - t0) / 1e9, base.toDouble(), 100.0 * wins.get() / n))
    for (i in 0 until k) {
        println("  strong %.0e: estimated gain %+.3f%%p per game, differing decisions %.2f per game".format(
            strong[i].toDouble(), 100 * lossSum[i] / n, differ[i].toDouble() / n))
    }
}

/** Prints generated practice questions with their answers, to check what the quiz asks. */
private fun quizSamples(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val n = opts["n"]?.toInt() ?: 6
    val rng = Random(opts["seed"]?.toLong() ?: 9L)
    val advisor = Advisor(tables)
    val ex = yacht.engine.explain.Explainer(tables)
    val quiz = yacht.engine.explain.Quiz(advisor, ex)
    var tries = 0
    var found = 0
    val kinds = HashMap<String, Int>()
    val tStart = System.nanoTime()
    while (found < 60 && tries < 600) {
        tries++
        val got = quiz.generate(rng, tries = 1, wantKeep = if (tries % 2 == 0) true else null) ?: continue
        found++
        val a = quiz.advice(got.second, got.first)
        val k = "roll ${got.first.rollsDone} " + if (a.shouldStop) "box" else "keep"
        kinds[k] = (kinds[k] ?: 0) + 1
    }
    println("acceptance: $found / $tries, %.2fs per try; ".format((System.nanoTime() - tStart) / 1e9 / tries) + kinds.toSortedMap())
    repeat(n) {
        val t0 = System.nanoTime()
        val (q, plan) = quiz.generate(rng) ?: run { println("no question"); return@repeat }
        val a = quiz.advice(plan, q)
        println("--- me ${q.me.total} (${q.me.filledCount} filled) vs opp ${q.opp.total} (${q.opp.filledCount}); roll ${q.rollsDone}: ${q.dice}  [%.1fs]".format((System.nanoTime() - t0) / 1e9))
        println("    me:  ${q.me}")
        println("    opp: ${q.opp}")
        val e = ex.advice(plan, q.opp, a)
        val best = if (a.shouldStop) "${a.bestCategory.category} ${a.bestCategory.points}" else "keep ${a.bestKeep!!.held}"
        println("  best: $best  P(win) %.3f".format(a.value))
        println("  why: ${e.summary} / ${e.details.joinToString(" / ")}")
        val wrong = a.categories.maxBy { it.points }
        if (!(a.shouldStop && a.bestCategory.category == wrong.category)) {
            val g = quiz.gradeCategory(plan, q, wrong.category)
            println("  if ${g.chosen}: correct=${g.correct} loss " + "%.4f".format(g.loss) + " / ${g.explanation.summary} / ${g.explanation.details.joinToString(" / ")}")
        }
    }
}

/** One player's turn on [stream] (turn index [turn]); returns the board after it. */
private fun playOneTurn(rules: RuleSet, p: yacht.engine.Player, me: Board, opp: Board, stream: DiceStream, turn: Int): Board {
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

/**
 * Head-to-head: the win AI against other AIs. Opponents that ignore my card (all but "self") play
 * their game once per deal; I play it from both seats, and so does the max-expectation AI, which
 * gives a paired reference on the same dice. "self" plays the win AI against itself.
 */
private fun arena(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val games = opts["games"]?.toInt() ?: 1000
    val seed = opts["seed"]?.toLong() ?: 2026L
    val budget = opts["budget"]?.toDouble()?.toLong() ?: 100_000_000L
    val opps = (opts["opp"] ?: "rule,greedy,slip10,ev,self").split(',')
    // --from F --csv FILE: deals F until F+games, one line per deal (merge chunks with a script).
    val from = opts["from"]?.toInt() ?: 0
    val csv = opts["csv"]?.let { File(it) }
    // --me app:S plays my side exactly as the app's AI opponent does: anytime planning with an
    // S-second deadline per turn (instead of a fixed ops budget).
    val appSeconds = opts["me"]?.takeIf { it.startsWith("app:") }?.removePrefix("app:")?.toDouble()
    val advisor = Advisor(tables)
    fun mine(): yacht.engine.Player = if (appSeconds == null) AdvisorPlayer(advisor, Mode.WIN, budget) else object : yacht.engine.Player {
        override val name = "app"
        private lateinit var turn: yacht.engine.PlannedTurn
        override fun startTurn(me: Board, opp: Board) {
            turn = advisor.planTurnAnytime(me, opp, System.nanoTime() + (appSeconds * 1e9).toLong())
        }
        override fun chooseKeep(rollsDone: Int, r: Int) = turn.bestKeep(rollsDone, r)
        override fun chooseCategory(r: Int) = turn.bestCategory(r)
    }
    fun opponent(spec: String, gid: Long): yacht.engine.Player = when {
        spec == "ev" -> AdvisorPlayer(advisor, Mode.EXPECTED_SCORE, name = "ev")
        spec == "greedy" -> yacht.engine.GreedyPlayer()
        spec == "rule" -> yacht.engine.RulePlayer(rules)
        spec.startsWith("slip") -> yacht.engine.SlipPlayer(
            tables.ev, spec.removePrefix("slip").toInt() / 100.0, Random(DiceStream.mix(seed, gid, 7)), spec,
        )
        else -> error("unknown opponent $spec")
    }
    for (spec in opps) {
        val ours = Array(2) { IntArray(games) } // 1 win, 2 tie, 0 loss, per seat
        val evs = Array(2) { IntArray(games) }
        val myScore = LongArray(games)
        val oppScore = LongArray(games)
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val t0 = System.nanoTime()
        val tasks = (0 until games).map { g ->
            Callable {
                val gid = (from + g).toLong()
                fun outcome(a: Int, b: Int) = if (a > b) 1 else if (a == b) 2 else 0
                if (spec == "self") {
                    // Seat 0 of "ours" = the first player; both are the win AI.
                    val sa = DiceStream(DiceStream.mix(seed, gid, 0))
                    val sb = DiceStream(DiceStream.mix(seed, gid, 1))
                    val pa = AdvisorPlayer(advisor, Mode.WIN, budget)
                    val pb = AdvisorPlayer(advisor, Mode.WIN, budget)
                    var a = Board(rules)
                    var b = Board(rules)
                    for (turn in 0 until Category.COUNT) {
                        a = playOneTurn(rules, pa, a, b, sa, turn)
                        b = playOneTurn(rules, pb, b, a, sb, turn)
                    }
                    ours[0][g] = outcome(a.total, b.total)
                    ours[1][g] = outcome(b.total, a.total)
                    myScore[g] = (a.total + b.total).toLong()
                    oppScore[g] = (a.total + b.total).toLong()
                } else {
                    val oppGame = Duplicate.independentGame(rules, opponent(spec, gid), DiceStream(DiceStream.mix(seed, gid, 1)))
                    val oppFinal = oppGame.last().total
                    val stream = DiceStream(DiceStream.mix(seed, gid, 0))
                    for (seat in 0..1) {
                        val me = Duplicate.playTurns(rules, mine(), stream) { t -> oppGame[t + seat] }
                        val ev = Duplicate.playTurns(rules, AdvisorPlayer(advisor, Mode.EXPECTED_SCORE), stream) { t -> oppGame[t + seat] }
                        ours[seat][g] = outcome(me.total, oppFinal)
                        evs[seat][g] = outcome(ev.total, oppFinal)
                        myScore[g] += me.total.toLong()
                    }
                    oppScore[g] = 2L * oppFinal
                }
                if (csv != null) synchronized(csv) {
                    csv.appendText("$spec,$gid,${ours[0][g]},${ours[1][g]},${evs[0][g]},${evs[1][g]},${myScore[g]},${oppScore[g]}\n")
                }
            }
        }
        pool.invokeAll(tasks).forEach { it.get() }
        pool.shutdown()
        fun rate(o: Array<IntArray>, seat: Int?, v: Int) =
            (if (seat == null) listOf(0, 1) else listOf(seat)).sumOf { s -> o[s].count { it == v } }.toDouble() / (games * (if (seat == null) 2 else 1))
        // 95% interval with the two seats of a deal as one cluster.
        fun ci(perGame: DoubleArray): Double {
            val m = perGame.average()
            return 1.96 * sqrt(perGame.sumOf { (it - m) * (it - m) } / (perGame.size - 1) / perGame.size)
        }
        val winPerGame = DoubleArray(games) { g -> ((if (ours[0][g] == 1) 1 else 0) + (if (ours[1][g] == 1) 1 else 0)) / 2.0 }
        println("=== vs $spec: $games deals x 2 seats in %.0fs".format((System.nanoTime() - t0) / 1e9))
        if (spec == "self") {
            println("  first player wins %.2f%%, second %.2f%%, ties %.2f%% (±%.2f)".format(
                100 * rate(ours, 0, 1), 100 * rate(ours, 1, 1), 100 * rate(ours, 0, 2),
                100 * 1.96 * sqrt(rate(ours, 0, 1) * (1 - rate(ours, 0, 1)) / games)))
            continue
        }
        println("  win AI: win %.2f%% ±%.2f, tie %.2f%%, loss %.2f%% (first %.2f%%, second %.2f%%); mean %.1f vs %.1f".format(
            100 * rate(ours, null, 1), 100 * ci(winPerGame), 100 * rate(ours, null, 2), 100 * rate(ours, null, 0),
            100 * rate(ours, 0, 1), 100 * rate(ours, 1, 1), myScore.sum() / (2.0 * games), oppScore.sum() / (2.0 * games)))
        val diff = DoubleArray(games) { g ->
            (0..1).sumOf { s -> (if (ours[s][g] == 1) 1.0 else 0.0) - (if (evs[s][g] == 1) 1.0 else 0.0) } / 2
        }
        println("  max-expectation AI on the same deals: win %.2f%%, tie %.2f%%; win AI - it: %+.2f%%p ±%.2f".format(
            100 * rate(evs, null, 1), 100 * rate(evs, null, 2), 100 * diff.average(), 100 * ci(diff)))
    }
}

/**
 * Calibration of the shown P(win): the win AI plays against [--opp] ev (max expectation, the
 * opponent it assumes) or self (another win AI, which fights back when behind). At each of its
 * turns the predicted P(win) is recorded; one CSV line per game: game,seat,outcome,p1..p12.
 */
private fun calib(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val games = opts["games"]?.toInt() ?: 1000
    val seed = opts["seed"]?.toLong() ?: 77L
    val budget = opts["budget"]?.toDouble()?.toLong() ?: 100_000_000L
    val opp = opts["opp"] ?: "ev"
    val csv = File(opts["csv"] ?: "calib.csv")
    val from = opts["from"]?.toInt() ?: 0
    val advisor = Advisor(tables)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val tasks = (0 until games).map { g ->
        Callable {
            val gid = (from + g).toLong()
            val meFirst = gid % 2 == 0L
            val myStream = DiceStream(DiceStream.mix(seed, gid, 0))
            val oppStream = DiceStream(DiceStream.mix(seed, gid, 1))
            val oppPlayer = if (opp == "self") AdvisorPlayer(advisor, Mode.WIN, budget) else AdvisorPlayer(advisor, Mode.EXPECTED_SCORE)
            var me = Board(rules)
            var them = Board(rules)
            val preds = DoubleArray(Category.COUNT)
            for (turn in 0 until Category.COUNT) {
                if (!meFirst) them = playOneTurn(rules, oppPlayer, them, me, oppStream, turn)
                val plan = advisor.planTurn(me, them, Mode.WIN, budget)
                preds[turn] = plan.startValue.coerceIn(0.0, 1.0)
                val mine = object : yacht.engine.Player {
                    override val name = "win"
                    override fun startTurn(me: Board, opp: Board) {}
                    override fun chooseKeep(rollsDone: Int, r: Int) = plan.bestKeep(rollsDone, r)
                    override fun chooseCategory(r: Int) = plan.bestCategory(r)
                }
                me = playOneTurn(rules, mine, me, them, myStream, turn)
                if (meFirst) them = playOneTurn(rules, oppPlayer, them, me, oppStream, turn)
            }
            val outcome = if (me.total > them.total) 1 else if (me.total == them.total) 2 else 0
            synchronized(csv) {
                csv.appendText("$gid,${if (meFirst) 0 else 1},$outcome," + preds.joinToString(",") { "%.5f".format(it) } + "\n")
            }
        }
    }
    pool.invokeAll(tasks).forEach { it.get() }
    pool.shutdown()
    println("calib: $games games vs $opp written to $csv")
}

private fun versus(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val games = opts["games"]?.toInt() ?: 500
    val seed = opts["seed"]?.toLong() ?: 99L
    val budget = opts["budget"]?.toDouble()?.toLong() ?: 100_000_000L
    val from = opts["from"]?.toInt() ?: 0
    val csv = opts["csv"]?.let { File(it) }
    val specA = opts["a"] ?: "aduel-5e8"
    val specB = opts["b"] ?: "duel-5e8"
    val plain = Advisor(tables)
    val adv = Advisor(tables, adversarial = true)
    fun player(spec: String): yacht.engine.Player = when {
        spec == "ev" -> AdvisorPlayer(plain, Mode.EXPECTED_SCORE)
        spec == "win" -> AdvisorPlayer(plain, Mode.WIN, budget)
        spec.startsWith("duel-") -> AdvisorPlayer(plain, Mode.WIN, budget, duelOps = spec.removePrefix("duel-").toDouble().toLong())
        spec.startsWith("aduel-") -> AdvisorPlayer(adv, Mode.WIN, budget, duelOps = spec.removePrefix("aduel-").toDouble().toLong())
        else -> error("unknown player $spec")
    }
    fun play(first: yacht.engine.Player, second: yacht.engine.Player, s0: DiceStream, s1: DiceStream): IntArray {
        var a = Board(rules)
        var b = Board(rules)
        for (turn in 0 until Category.COUNT) {
            a = playOneTurn(rules, first, a, b, s0, turn)
            b = playOneTurn(rules, second, b, a, s1, turn)
        }
        return intArrayOf(a.total, b.total)
    }
    val aWins = IntArray(games)
    val bWins = IntArray(games)
    val ties = IntArray(games)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val t0 = System.nanoTime()
    pool.invokeAll((0 until games).map { g ->
        Callable {
            val gid = (from + g).toLong()
            val s0 = DiceStream(DiceStream.mix(seed, gid, 0))
            val s1 = DiceStream(DiceStream.mix(seed, gid, 1))
            // Same dice for the first and the second seat in both games: only the seats swap.
            val x = play(player(specA), player(specB), s0, s1)
            val y = play(player(specB), player(specA), s0, s1)
            aWins[g] = (if (x[0] > x[1]) 1 else 0) + (if (y[1] > y[0]) 1 else 0)
            bWins[g] = (if (x[1] > x[0]) 1 else 0) + (if (y[0] > y[1]) 1 else 0)
            ties[g] = (if (x[0] == x[1]) 1 else 0) + (if (y[0] == y[1]) 1 else 0)
            if (csv != null) synchronized(csv) { csv.appendText("$gid,${x[0]},${x[1]},${y[0]},${y[1]}\n") }
        }
    }).forEach { it.get() }
    pool.shutdown()
    val n = 2.0 * games
    val diff = DoubleArray(games) { (aWins[it] - bWins[it]) / 2.0 }
    val m = diff.average()
    val ci = 1.96 * sqrt(diff.sumOf { (it - m) * (it - m) } / (games - 1) / games)
    println("versus: $games deals x 2 games in %.0fs".format((System.nanoTime() - t0) / 1e9))
    println("  $specA wins %.2f%%, $specB wins %.2f%%, ties %.2f%%; difference %+.2f%%p ±%.2f".format(
        100 * aWins.sum() / n, 100 * bWins.sum() / n, 100 * ties.sum() / n, 100 * m, 100 * ci))
}

/**
 * Plays games the way the app does against its AI (outlook before each AI turn, the AI's reasons,
 * my P(win) before every throw) and prints the opponent-turn texts, the luck analysis and the
 * "추천대로 뒀다면?" comparisons, for reading. I deviate from the advice with probability --stray.
 */
private fun reviewSamples(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val games = opts["games"]?.toInt() ?: 2
    val stray = opts["stray"]?.toDouble() ?: 0.1
    val rng = Random(opts["seed"]?.toLong() ?: 11L)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val advisor = Advisor(tables, pool)
    val ex = yacht.engine.explain.Explainer(tables)
    val outlook = yacht.engine.explain.OutlookAnalysis(tables)
    val luck = yacht.engine.explain.LuckAnalysis(tables)
    val whatIf = yacht.engine.explain.WhatIfAnalysis(tables)
    try {
        repeat(games) { g ->
            println("==================== game ${g + 1} ====================")
            var s = yacht.engine.game.GameState.new(
                yacht.engine.game.DiceSource.RANDOM,
                if (g % 2 == 0) yacht.engine.game.Side.ME else yacht.engine.game.Side.OPP,
                rules,
            )
            var lastWin: Double? = null
            while (!s.isOver) {
                if (s.toMove == yacht.engine.game.Side.OPP) {
                    val now = lastWin ?: if (s.me.isComplete) outlook.quick(s.me, s.opp) else advisor.planTurn(s.me, s.opp, Mode.WIN, depth = 1).startValue
                    outlook.opponentTurn(s.me, s.opp, now)?.let { o ->
                        println("  [상대 턴 전망] ${o.summary}")
                        o.details.forEach { println("        - $it") }
                    }
                    val turn = advisor.planTurn(s.opp, s.me, Mode.WIN)
                    val throws = ArrayList<List<Int>>()
                    val held = ArrayList<List<Int>>()
                    var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
                    throws += Dice.rollFaces(r)
                    for (rollsDone in 1..2) {
                        val k = turn.bestKeep(rollsDone, r)
                        if (k == Dice.rollAsKeep[r]) break
                        held += Dice.keepFaces(k)
                        r = Match.throwDice(rng, k)
                        throws += Dice.rollFaces(r)
                    }
                    val c = turn.bestCategory(r)
                    val why = ex.aiTurn(turn, s.me, throws, held, c)
                    why.forEach { println("  [AI] $it") }
                    s = yacht.engine.game.Game.opponentScore(
                        s, c, tables.scores[r, c.ordinal], winBefore = now,
                        turn = yacht.engine.game.MoveRecord(yacht.engine.game.Side.OPP, c, 0, throws = throws, held = held, why = why),
                    )
                    continue
                }
                val turn = advisor.planTurn(s.me, s.opp, Mode.WIN, ties = true)
                s = yacht.engine.game.Game.noteWinAfterOpponent(s, turn.startValue)
                val last = s.moves.lastOrNull()
                if (last != null && last.side == yacht.engine.game.Side.OPP) {
                    println("  [직전 상대] " + ex.opponentMove(s.opp, last, last.winBefore, "AI"))
                }
                turn.startOutlook?.let { println("--- my turn ${s.myTurnNumber}: ${s.me.total}:${s.opp.total} 승 ${"%.1f".format(100 * it.win)} 무 ${"%.2f".format(100 * it.tie)} 패 ${"%.1f".format(100 * it.loss)}") }
                s = yacht.engine.game.Game.firstThrow(s, rng)
                var before = turn.startValue
                while (true) {
                    val a = turn.advise(s.draft.rollsDone, s.draft.faces)
                    val strayNow = rng.nextDouble() < stray
                    if (a.shouldStop) {
                        val pick = if (strayNow && a.categories.size > 1) a.categories[1] else a.bestCategory
                        lastWin = pick.value
                        s = yacht.engine.game.Game.score(s, pick.category, a, before = before)
                        break
                    }
                    val pick = if (strayNow) a.keeps[1 + rng.nextInt(minOf(3, a.keeps.size - 1))] else a.bestKeep!!
                    if (pick.held.size == 5) {
                        lastWin = a.bestCategory.value
                        s = yacht.engine.game.Game.score(s, a.bestCategory.category, a, before = before)
                        break
                    }
                    s = yacht.engine.game.Game.holdFaces(s, pick.held)
                    s = yacht.engine.game.Game.reroll(s, a, rng, before = before)
                    before = pick.value
                }
            }
            println("=== result ${s.me.total}:${s.opp.total}")
            val r = luck.of(s)
            r.luckFree?.let { f ->
                println("  [운을 빼면] ${f.summary}")
                f.details.forEach { println("        - $it") }
            }
            println("  [운] ${r.summary}")
            r.details.forEach { println("        - $it") }
            r.moments.forEach { println("        * ${it.text} ${"%+.1f".format(100 * it.delta)}%p") }
            for (d in s.decisions.filter { it.isMistake }.sortedByDescending { it.loss }.take(3)) {
                val w = whatIf.of(advisor.planTurn(d.me!!, d.opp!!, d.mode, budgetOps = 100_000_000L), d) ?: continue
                println("  [추천대로?] ${w.summary}")
                for ((t, side) in listOf("추천" to w.best, "내 선택" to w.chosen)) {
                    println("        $t ${side.move}: " + side.outcomes.joinToString(" · ") { "${it.label} ${"%.0f".format(100 * it.prob)}%" })
                }
                w.details.forEach { println("        - $it") }
            }
        }
    } finally {
        pool.shutdown()
    }
}

/**
 * How much more exact play would cost: for random positions with N boxes left, the work and
 * memory of (1) the fixed-opponent search solved to the end, (2) the duel table (the opponent's
 * results inside the search), and (3) the whole-game duel table from the empty board.
 * Work is in the searches' operation units; --time also times the feasible searches.
 */
private fun cost(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val rng = Random(opts["seed"]?.toLong() ?: 1L)
    val samples = opts["samples"]?.toInt() ?: 5
    val timeLimit = opts["time"]?.toDouble() ?: 0.0
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val advisor = Advisor(tables, pool)
    try {
        println("== fixed-opponent search solved to the end (max over $samples positions)")
        for (left in 12 downTo 4) {
            var ops = 0L
            var bytes = 0L
            var secs = Double.NaN
            repeat(samples) {
                val me = randomBoard(tables, rng, 12 - left)
                val opp = randomBoard(tables, rng, 12 - left)
                ops = maxOf(ops, WinSearch.estimateOps(tables, me, left, opp))
                bytes = maxOf(bytes, winSearchBytes(tables, me, left))
                if (timeLimit > 0 && WinSearch.estimateOps(tables, me, left, opp) < timeLimit * 1e9) {
                    val t = System.nanoTime()
                    advisor.planTurn(me, opp, depth = left)
                    secs = if (secs.isNaN()) (System.nanoTime() - t) / 1e9 else maxOf(secs, (System.nanoTime() - t) / 1e9)
                }
            }
            println("left=%2d ops %.2e  memory %8.1f MB  measured %s".format(left, ops.toDouble(), bytes / 1e6, if (secs.isNaN()) "-" else "%.2f s".format(secs)))
        }
        println("== duel table rooted at my turn (max over $samples positions)")
        for (left in 12 downTo 5) {
            var ops = 0.0
            var cells = 0.0
            var secs = Double.NaN
            repeat(samples) {
                val me = randomBoard(tables, rng, 12 - left)
                val opp = randomBoard(tables, rng, 12 - left)
                val (c, o) = duelCost(tables, me, opp, true)
                ops = maxOf(ops, o)
                cells = maxOf(cells, c)
                if (timeLimit > 0 && o < timeLimit * 1e9) {
                    val t = System.nanoTime()
                    advisor.buildDuelTable(me, opp, true)
                    secs = if (secs.isNaN()) (System.nanoTime() - t) / 1e9 else maxOf(secs, (System.nanoTime() - t) / 1e9)
                }
            }
            println("left=%2d ops %.2e  cells %.2e (%.1f MB as floats)  measured %s".format(left, ops, cells, cells * 4 / 1e6, if (secs.isNaN()) "-" else "%.2f s".format(secs)))
        }
        val empty = Board(rules)
        for (meFirst in listOf(true, false)) {
            val (c, o) = duelCost(tables, empty, empty, meFirst)
            val (_, oa) = duelCost(tables, empty, empty, meFirst, adversarial = true)
            println("whole game, ${if (meFirst) "me first" else "opponent first"}: cells %.3e (%.1f GB float, %.1f GB half), ops %.2e (minimax %.2e)".format(c, c * 4 / 1e9, c * 2 / 1e9, o, oa))
        }
    } finally {
        pool.shutdown()
    }
}

/** Memory of a fixed-opponent search's grids (doubles) to depth [depth]. */
private fun winSearchBytes(tables: Tables, me: Board, depth: Int): Long {
    val decided = WinSearch.bonusDecided(me)
    val levels = WinSearch.masksByLevel(me.mask)
    var bytes = 0L
    for (j in 1..minOf(depth, levels.size - 1)) for (m in levels[j]) {
        if (m == Category.FULL_MASK) continue
        bytes += 8L * WinSearch.upperRows(me, decided, m).values.size * (WinSearch.gainMax(tables, me, decided, m) + 1)
    }
    return bytes
}

/**
 * Stored cells and operations of a duel table from ([me], [opp]) with no size limit: the same
 * count as DuelSearch.estimateOps, plus every stored score difference.
 */
private fun duelCost(tables: Tables, me: Board, opp: Board, meToMove: Boolean, adversarial: Boolean = false): Pair<Double, Double> {
    val kMe = Category.COUNT - me.filledCount
    val kOpp = Category.COUNT - opp.filledCount
    val myDecided = WinSearch.bonusDecided(me)
    val oppDecided = WinSearch.bonusDecided(opp)
    val myLevels = WinSearch.masksByLevel(me.mask)
    val oppLevels = WinSearch.masksByLevel(opp.mask)
    val d0 = me.total - opp.total
    class Info(val rows: Int, val gain: Int, val rest: Int)
    fun rest(m: Int, decided: Boolean): Int {
        var g = 0
        for (c in 0 until Category.COUNT) if (m and (1 shl c) == 0) g += tables.scores.maxPoints[c]
        if (!decided && (m and Category.UPPER_MASK) != Category.UPPER_MASK) g += tables.rules.bonusPoints
        return g
    }
    val myInfo = HashMap<Int, Info>()
    val oppInfo = HashMap<Int, Info>()
    fun info(map: HashMap<Int, Info>, b: Board, decided: Boolean, m: Int) = map.getOrPut(m) {
        Info(WinSearch.upperRows(b, decided, m).values.size, WinSearch.gainMax(tables, b, decided, m), rest(m, decided))
    }
    val kernel = 7_500.0
    var ops = 0.0
    var cells = 0.0
    for (h in kMe + kOpp - 1 downTo 1) {
        val myTurn = (h % 2 == 0) == meToMove
        val jm = if (meToMove) (h + 1) / 2 else h / 2
        val jo = if (meToMove) h / 2 else (h + 1) / 2
        if (jm >= myLevels.size || jo >= oppLevels.size) continue
        for (mm in myLevels[jm]) {
            val mi = info(myInfo, me, myDecided, mm)
            for (om in oppLevels[jo]) {
                if (mm == Category.FULL_MASK && om == Category.FULL_MASK) continue
                val oi = info(oppInfo, opp, oppDecided, om)
                val lo = d0 - oi.gain
                val hi = d0 + mi.gain
                val slices = mi.rows.toDouble() * oi.rows
                cells += slices * (hi - lo + 1)
                val w = (minOf(hi, oi.rest) - maxOf(lo, -mi.rest) + 1).coerceAtLeast(0)
                ops += slices * w * when {
                    myTurn -> kernel + 252.0 * (Category.COUNT - Integer.bitCount(mm))
                    adversarial -> kernel + 252.0 * (Category.COUNT - Integer.bitCount(om))
                    else -> 120.0
                }
            }
        }
    }
    return cells to ops
}

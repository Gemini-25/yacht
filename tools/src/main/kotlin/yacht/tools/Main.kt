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
        "quiz" -> quizSamples(opts)
        "bench" -> bench(File(opts["tables"] ?: "tables"), opts["budget"]?.toLong() ?: Advisor.DEFAULT_BUDGET, opts["only"])
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

private fun bench(dir: File, budget: Long, only: String?) {
    val tables = loadTables(dir)
    val rng = Random(3)
    val advisor = Advisor(tables)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val parallel = Advisor(tables, pool)
    for (filled in if (only == "duel") emptyList() else listOf(0, 3, 6, 7, 8, 9, 10, 11)) {
        var worst = 0.0
        var sum = 0.0
        var worstPar = 0.0
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
            worst = maxOf(worst, ms)
            worstPar = maxOf(worstPar, msPar)
            sum += ms
        }
        println("filled=%2d depth=%s  avg %.0f ms, worst %.0f ms (1 thread), worst %.0f ms (pool)".format(filled, depths, sum / samples, worst, worstPar))
    }
    for (filled in if (only == "search") emptyList() else (11 downTo 6).toList()) {
        var worst = 0.0
        var sum = 0.0
        var est = 0L
        var mem = 0L
        val samples = 4
        repeat(samples) {
            val me = randomBoard(tables, rng, filled)
            val opp = randomBoard(tables, rng, filled)
            est = maxOf(est, DuelSearch.estimateOps(tables, me, opp))
            val t = System.nanoTime()
            val table = parallel.buildDuelTable(me, opp, meToMove = true)
            val ms = (System.nanoTime() - t) / 1e6
            mem = maxOf(mem, table.bytes)
            worst = maxOf(worst, ms)
            sum += ms
        }
        println("duel filled=%2d avg %.0f ms, worst %.0f ms (pool), max estimate %.2e ops, max table %.1f MB".format(filled, sum / samples, worst, est.toDouble(), mem / 1e6))
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

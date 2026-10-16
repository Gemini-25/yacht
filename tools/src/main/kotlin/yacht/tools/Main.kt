package yacht.tools

import yacht.engine.Advisor
import yacht.engine.AdvisorPlayer
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
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
        "gen" -> gen(File(opts["out"] ?: "tables"), if (opts["precision"] == "float") 4 else 2)
        "sim" -> sim(opts)
        "bench" -> bench(File(opts["tables"] ?: "tables"), opts["budget"]?.toLong() ?: Advisor.DEFAULT_BUDGET)
        else -> println(
            """
            usage:
              gen   --out DIR [--precision half|float] build ev.bin and dist.bin
              sim   --tables DIR --games N --a win|ev --b win|ev [--budget OPS] [--depthA D] [--depthB D] [--seed S]
              bench --tables DIR [--budget OPS]       time the win search at several game stages
            """.trimIndent(),
        )
    }
}

private fun gen(out: File, valueBytes: Int) {
    out.mkdirs()
    val t0 = System.nanoTime()
    val b = TableBuilder(rules).build(withDist = true)
    val secs = (System.nanoTime() - t0) / 1e9
    File(out, "ev.bin").outputStream().use { TableIO.writeEv(it, rules, b.ev) }
    File(out, "dist.bin").outputStream().use { TableIO.writeDist(it, rules, b.distLo, b.dist, valueBytes) }
    println("built in %.1fs; optimal expected score %.4f".format(secs, b.ev[0]))
    for (f in listOf("ev.bin", "dist.bin")) println("  $f ${File(out, f).length() / 1024} KiB")
}

fun loadTables(dir: File): Tables {
    fun map(name: String) = RandomAccessFile(File(dir, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules))
}

private fun sim(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables"))
    val games = opts["games"]?.toInt() ?: 1000
    val budget = opts["budget"]?.toLong() ?: Advisor.DEFAULT_BUDGET
    val seed = opts["seed"]?.toLong() ?: 1L
    val advisor = Advisor(tables)
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

private fun bench(dir: File, budget: Long) {
    val tables = loadTables(dir)
    val rng = Random(3)
    val advisor = Advisor(tables)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val parallel = Advisor(tables, pool)
    for (filled in listOf(0, 3, 6, 7, 8, 9, 10, 11)) {
        var worst = 0.0
        var sum = 0.0
        var worstPar = 0.0
        val depths = HashMap<Int, Int>()
        val samples = 6
        repeat(samples) {
            val me = randomBoard(tables, rng, filled)
            val opp = randomBoard(tables, rng, filled)
            val d = WinSearch.chooseDepth(tables, me, budget)
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
    pool.shutdown()
}

private fun randomBoard(tables: Tables, rng: Random, filled: Int): Board {
    var b = Board(rules)
    for (c in Category.ALL.shuffled(rng).take(filled)) {
        b = b.with(c, tables.scores[Match.throwDice(rng, Dice.EMPTY_KEEP), c.ordinal])
    }
    return b
}

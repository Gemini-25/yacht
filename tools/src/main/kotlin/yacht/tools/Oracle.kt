package yacht.tools

import yacht.engine.DistTable
import yacht.engine.ExactOpp
import yacht.engine.OppModel
import yacht.engine.PolicyDist
import yacht.engine.TableBuilder
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import java.io.File
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * What knowing an opponent completely would add (an AI league can know it): for opponents
 * that play their own card only, their exact remaining-points distribution from every state
 * ([PolicyDist]; the risk AIs' from [TableBuilder] at their θ), handed to the app's win AI.
 * Against each, on the same deals: the win AI, the 상대 맞춤 model from the opponent's record,
 * and the exact one. Prints each one's share of points and the gains with their 95% ranges.
 */
fun oracle(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val roster = LeagueRoster.build(tables, LeagueTables(tables.rules)).associateBy { it.key }
    val opps = (opts["opps"] ?: "ev,greedy,keeper,rule,upper-first,risk+0.1,risk-0.1,risk+0.03,risk-0.03").split(',').filter { it.isNotBlank() }
    val games = opts["games"]?.toInt() ?: 2000
    val record = opts["record"]?.toInt() ?: 500
    val seed = opts["seed"]?.toLong() ?: 2027L
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threads)
    val win = roster.getValue("win")
    val adaptive = roster.getValue(LeagueRoster.ADAPTIVE)
    val ev = roster.getValue("ev")
    println("opponent | exact mean (record) | win AI | record model | exact | record gain | exact gain (95%)")
    for (key in opps) {
        val opp = roster[key] ?: run { println("$key: no such AI"); null } ?: continue
        val t0 = System.nanoTime()
        val exact: ExactOpp
        val exactMean: Double
        val theta = key.removePrefix("risk").toDoubleOrNull()?.takeIf { key.startsWith("risk") }
        if (theta != null) {
            val d: DistTable = TableBuilder(tables.rules, threads, theta).build(true).distTable()
            exact = ExactOpp(d)
            exactMean = d.mean(0, 0)
        } else {
            val d = PolicyDist.build(tables.rules, threads) { opp.make(Random(0)) }
            exact = ExactOpp(d)
            exactMean = DistTable.moments(d.pmf(0, 0), d.lo(0, 0)).first
        }
        val built = (System.nanoTime() - t0) / 1e9
        // Its record, as the league would have it: its finals against the max-expectation AI.
        val finals = (0 until record).map { i ->
            pool.submit(Callable { LeagueMatch.playMatch(tables.rules, opp, ev, seed * 7919 + i).games.map { it.a } })
        }.flatMap { it.get() }
        val mean = finals.average()
        val sd = sqrt(finals.sumOf { (it - mean) * (it - mean) } / (finals.size - 1))
        val model = OppModel.of(tables, finals.size, mean, sd)
        fun points(gs: List<GameScore>) = gs.sumOf { if (it.a > it.b) 1.0 else if (it.a == it.b) 0.5 else 0.0 } / gs.size
        val rows = (0 until games).map { i ->
            pool.submit(Callable {
                val s = seed * 104729 + 1_000_003L * (key.hashCode() and 0xffff) + i
                val w = points(LeagueMatch.playMatch(tables.rules, win, opp, s).games)
                val m = points(LeagueMatch.playMatch(tables.rules, adaptive, opp, s, scout = { if (it == key) model else null }).games)
                val e = points(LeagueMatch.playMatch(tables.rules, adaptive, opp, s, scout = { if (it == key) exact else null }).games)
                Triple(w, m, e)
            })
        }.map { it.get() }
        fun gain(f: (Triple<Double, Double, Double>) -> Double): String {
            val d = rows.map { f(it) - it.first }
            val dm = d.average()
            val se = sqrt(d.sumOf { (it - dm) * (it - dm) } / (d.size - 1) / d.size)
            return "%+.2f%%p ±%.2f".format(100 * dm, 196 * se)
        }
        println(
            "%s | %.1f (%.1f) | %.1f%% | %.1f%% | %.1f%% | %s | %s   [table %.0fs]".format(
                key, exactMean, mean, 100 * rows.map { it.first }.average(), 100 * rows.map { it.second }.average(),
                100 * rows.map { it.third }.average(), gain { it.second }, gain { it.third }, built,
            ),
        )
    }
    pool.shutdown()
}

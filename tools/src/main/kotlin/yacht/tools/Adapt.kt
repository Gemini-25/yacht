package yacht.tools

import yacht.engine.OppModel
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * 상대 맞춤 승률 AI against the app's win AI, opponent by opponent: each opponent's record (its
 * finals in [record] games against the max-expectation AI) gives its model, then both win AIs
 * play the same [games] mirrored matches against it (paired: the same deals). Prints each
 * one's share of points (a draw is half) and the difference with its 95% range.
 */
fun adapt(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val roster = LeagueRoster.build(tables, LeagueTables(tables.rules)).associateBy { it.key }
    val opps = (opts["opps"] ?: "greedy,keeper,slip20,soft3,rule,ev").split(',').filter { it.isNotBlank() }
    val record = opts["record"]?.toInt() ?: 200
    val games = opts["games"]?.toInt() ?: 300
    val seed = opts["seed"]?.toLong() ?: 2026L
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threads)
    val win = roster.getValue("win")
    val adaptive = roster.getValue(LeagueRoster.ADAPTIVE)
    val ev = roster.getValue("ev")
    println("opponent | record mean/sd | model | win AI | 상대 맞춤 | difference (95%)")
    for (key in opps) {
        val opp = roster[key] ?: run { println("$key: no such AI"); null } ?: continue
        // Its record: its own finals, both seats, against the max-expectation AI.
        val finals = (0 until record).map { i ->
            pool.submit(Callable { LeagueMatch.playMatch(tables.rules, opp, ev, seed * 7919 + i).games.map { it.a } })
        }.flatMap { it.get() }
        val mean = finals.average()
        val sd = sqrt(finals.sumOf { (it - mean) * (it - mean) } / (finals.size - 1))
        val model = OppModel.of(tables, finals.size, mean, sd)
        // The same deals for both: points per match (a game won 1, drawn 1/2), mirrored seats.
        val pairs = (0 until games).map { i ->
            pool.submit(Callable {
                val s = seed * 104729 + 1_000_003L * (key.hashCode() and 0xffff) + i
                fun points(gs: List<yacht.engine.league.GameScore>) = gs.sumOf { if (it.a > it.b) 1.0 else if (it.a == it.b) 0.5 else 0.0 } / gs.size
                val w = points(LeagueMatch.playMatch(tables.rules, win, opp, s).games)
                val a = points(LeagueMatch.playMatch(tables.rules, adaptive, opp, s, scout = { if (it == key) model else null }).games)
                w to a
            })
        }.map { it.get() }
        val w = pairs.map { it.first }
        val a = pairs.map { it.second }
        val d = pairs.map { it.second - it.first }
        val dm = d.average()
        val se = sqrt(d.sumOf { (it - dm) * (it - dm) } / (d.size - 1) / d.size)
        println(
            "%s | %.1f/%.1f | %s | %.1f%% | %.1f%% | %+.2f%%p ±%.2f".format(
                key, mean, sd, model?.let { "shift %+.1f, spread %.2f".format(it.shift, it.spread) } ?: "none",
                100 * w.average(), 100 * a.average(), 100 * dm, 196 * se,
            ),
        )
    }
    pool.shutdown()
}

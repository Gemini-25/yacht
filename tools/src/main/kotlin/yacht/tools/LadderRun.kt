package yacht.tools

import yacht.engine.EvTable
import yacht.engine.TableIO
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.Matchmaker
import yacht.engine.league.LeagueTables
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The app's ranked ladder on the desktop: matchmaking, mirrored matches and live ratings, for
 * --minutes on --threads. --state FILE resumes and saves; risk tables are cached in --cache.
 */
fun ladderRun(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val minutes = opts["minutes"]?.toDouble() ?: 5.0
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val stateFile = File(opts["state"] ?: "ladder.txt")
    val cache = File(opts["cache"] ?: "risk-cache").apply { mkdirs() }
    val rules = tables.rules
    val extra = LeagueTables(
        rules, threads = threads,
        load = { key, believed -> File(cache, "$key.bin").takeIf { it.exists() }?.let { TableIO.readEv(ByteBuffer.wrap(it.readBytes()), believed) } },
        save = { key, t: EvTable -> File(cache, "$key.bin").outputStream().use { TableIO.writeEv(it, t.rules, t.values) } },
    )
    val t0 = System.nanoTime()
    LeagueRoster.tableSpecs(rules).forEach { extra[it] }
    println("extra tables ready in %.1fs".format((System.nanoTime() - t0) / 1e9))
    val roster = LeagueRoster.build(tables, extra)
    val byKey = roster.associateBy { it.key }
    val ladder = if (stateFile.exists()) Ladder.decode(roster.map { it.key }, stateFile.readText()) else Ladder(roster.map { it.key })
    println("${roster.size} AIs (${roster.count { it.heavy }} heavy), ${ladder.matches} matches so far")
    val stop = AtomicBoolean(false)
    val busy = HashSet<String>()
    val rng = Random()
    val nanos = HashMap<String, Long>()
    val pool = Executors.newFixedThreadPool(threads)
    val end = System.nanoTime() + (minutes * 60e9).toLong()
    repeat(threads) {
        pool.submit {
            while (!stop.get() && System.nanoTime() < end) {
                val pair = synchronized(ladder) { Matchmaker.pick(ladder, rng, busy)?.also { busy += it.first; busy += it.second } } ?: continue
                try {
                    val s = System.nanoTime()
                    val res = LeagueMatch.play(rules, byKey.getValue(pair.first), byKey.getValue(pair.second), rng.nextLong()) { stop.get() }
                    val took = System.nanoTime() - s
                    synchronized(ladder) {
                        ladder.record(pair.first, pair.second, res)
                        for (k in listOf(pair.first, pair.second)) nanos[k] = (nanos[k] ?: 0L) + took / 2
                        if (ladder.matches % 500 == 0L) {
                            println("  ${ladder.matches} matches, %.0fs".format((System.nanoTime() - t0) / 1e9))
                            stateFile.writeText(ladder.encode())
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("match ${pair.first} vs ${pair.second} failed: $e")
                    e.printStackTrace()
                } finally {
                    synchronized(ladder) { busy -= pair.first; busy -= pair.second }
                }
            }
        }
    }
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.DAYS)
    stateFile.writeText(ladder.encode())
    println("rank  tier            rating   ±     games  win%   avg   streak  ms/game  AI")
    for ((i, k) in ladder.ranking().withIndex()) {
        val e = ladder.entry(k)!!
        val tier = ladder.tier(k)
        val ms = (nanos[k] ?: 0L) / 1e6 / maxOf(1, e.games)
        println(
            "%3s  %-14s %6s  %4s  %6d  %5.1f  %5.1f  %+4d  %7.1f  %s [%s]".format(
                ladder.rankOf(k)?.toString() ?: "-", tier?.let { "${it.label} ${it.lp}" } ?: "배치 중",
                ladder.rating(k).takeIf { !it.isNaN() }?.roundToInt() ?: "-", ladder.error(k).takeIf { !it.isNaN() }?.let { (1.96 * it).roundToInt() } ?: "-",
                e.games, 100 * e.winRate, e.avgScore, e.streak, ms, byKey.getValue(k).name, k,
            ),
        )
    }
    println("feed:")
    ladder.feed.take(8).forEach { println("  ${it.a} vs ${it.b} ${it.games.joinToString(" ") { g -> "${g.a}:${g.b}" }} ${it.deltaA}/${it.deltaB} ${it.notes}") }
}

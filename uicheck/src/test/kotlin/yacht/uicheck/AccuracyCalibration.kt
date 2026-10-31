package yacht.uicheck

import yacht.app.core.Accuracies
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import yacht.engine.league.PlayerGenerator
import yacht.engine.league.WatchMatch
import yacht.engine.league.leagueAi
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.Random
import kotlin.test.Test

/**
 * Measures 정확도 ([Accuracies]) on games of the league's hand-made AIs against the expected-score
 * AI, so [Accuracies.REFERENCE] can be set where the best read 95-98% and weak ones 80-90%.
 * Opt-in (`ACCURACY_CALIBRATION=1`, the precomputed tables in `../tables`): it prints, per AI, the
 * games, the expected points given away per game and the accuracy they read as.
 */
class AccuracyCalibration {
    @Test
    fun calibrate() {
        if (System.getenv("ACCURACY_CALIBRATION") != "1") return
        val dir = listOf(File("../tables"), File("tables")).first { File(it, "ev.bin").exists() }
        val tables = load(dir)
        val roster = LeagueRoster.build(tables, LeagueTables(tables.rules, threads = 2))
        val opp = roster.first { it.key == "ev" }
        val only = System.getenv("ACCURACY_AIS")?.split(',')?.toSet()
        val games = System.getenv("ACCURACY_GAMES")?.toIntOrNull() ?: 12
        val rows = ArrayList<Triple<String, Int, Double>>()
        for (ai in roster) {
            if (only != null && ai.key !in only) continue
            val n = if (ai.heavy) maxOf(2, games / 4) else games
            var lost = 0.0
            var decisions = 0
            var mistakes = 0
            val started = System.nanoTime()
            for (g in 0 until n) {
                val seed = 1000L * g + ai.key.hashCode()
                val first = g % 2 == 0
                val me = ai.player(Random(seed), null)
                val other = opp.player(Random(seed + 1), null)
                val turns = WatchMatch.play(tables.rules, if (first) me else other, if (first) other else me, Random(seed + 2))
                val (a, b) = Accuracies.of(tables, turns)
                val mine = if (first) a else b
                lost += mine.lost
                decisions += mine.decisions
                mistakes += mine.mistakes
            }
            val perGame = lost / n
            rows += Triple(ai.key, n, perGame)
            println(
                String.format(
                    Locale.ROOT, "ACC %-14s %-8s %3d games  lost %6.2f/game  %5.1f%%  mistakes %.1f/game  decisions %.1f/game  (%d ms)",
                    ai.key, ai.family, n, perGame, Accuracies.percent(perGame), mistakes.toDouble() / n, decisions.toDouble() / n, (System.nanoTime() - started) / 1_000_000,
                ),
            )
        }
        val sorted = rows.map { it.third }.sorted()
        if (sorted.isNotEmpty()) println(String.format(Locale.ROOT, "ACC median lost %.2f/game, p10 %.2f, p90 %.2f", sorted[sorted.size / 2], sorted[sorted.size / 10], sorted[sorted.size * 9 / 10]))

        // Generated players (the league's own), by potential: what a national league's players read.
        val generated = System.getenv("ACCURACY_GENERATED")?.toIntOrNull() ?: 0
        val extra = LeagueTables(tables.rules, threads = 2)
        val byPotential = java.util.TreeMap<Int, MutableList<Double>>()
        for (i in 1..generated) {
            val record = PlayerGenerator.recruit(i, 1, founding = true, taken = emptySet())
            val ai = leagueAi(record, tables, extra)
            var lost = 0.0
            val n = 4
            for (g in 0 until n) {
                val seed = 7919L * i + g
                val first = g % 2 == 0
                val me = ai.player(Random(seed), null)
                val other = opp.player(Random(seed + 1), null)
                val turns = WatchMatch.play(tables.rules, if (first) me else other, if (first) other else me, Random(seed + 2))
                val (a, b) = Accuracies.of(tables, turns)
                lost += (if (first) a else b).lost
            }
            byPotential.getOrPut((record.identity?.potential ?: 0) / 10 * 10) { ArrayList() } += lost / n
        }
        for ((p, list) in byPotential) {
            val s = list.sorted()
            println(String.format(Locale.ROOT, "GEN potential %2d-%2d: %3d players  lost median %5.1f (p10 %5.1f, p90 %5.1f)  %5.1f%%", p, p + 9, s.size, s[s.size / 2], s[s.size / 10], s[s.size * 9 / 10], Accuracies.percent(s[s.size / 2])))
        }
        val all = byPotential.values.flatten().sorted()
        if (all.isNotEmpty()) println(String.format(Locale.ROOT, "GEN all %d: lost median %.1f, p10 %.1f, p90 %.1f", all.size, all[all.size / 2], all[all.size / 10], all[all.size * 9 / 10]))
    }

    private fun load(dir: File): Tables {
        val rules = RuleSet.NINTENDO
        fun map(name: String) = RandomAccessFile(File(dir, name), "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
        val bonus = File(dir, "bonus.bin").takeIf { it.exists() }?.let { TableIO.readBonus(map(it.name), rules) }
        return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules), bonus = bonus)
    }
}

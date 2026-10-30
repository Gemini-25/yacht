package yacht.tools

import yacht.engine.Advisor
import yacht.engine.AdvisorPlayer
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.DiceStream
import yacht.engine.Mode
import yacht.engine.OppModel
import yacht.engine.Player
import yacht.engine.RuleSet
import yacht.engine.Stakes
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import yacht.engine.league.TieRule
import java.io.File
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * 경기 승리: the league's win AI playing a match (two games on the same dice with the seats
 * swapped, the higher total over both winning: see [Stakes]) for the match instead of game by
 * game. Against each opponent, on the same deals: the plain win AI, the one that plays the
 * second game for the match ("last"), and the one that plays both ("both": the first with the
 * second game's margin read as two max-expectation players'). Prints each one's match points
 * (a win 3, a draw 1, as the league table; shown as a share of 3), match share (a draw half) and
 * game share (as the rating counts), and the gains over the plain win AI with their 95% ranges.
 */
fun stakes(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val rules = tables.rules
    val roster = LeagueRoster.build(tables, LeagueTables(rules)).associateBy { it.key }
    val opps = (opts["opps"] ?: "win,ev,greedy").split(',').filter { it.isNotBlank() }
    val matches = opts["games"]?.toInt() ?: 1000
    val seed = opts["seed"]?.toLong() ?: 2028L
    val tie = opts["tie"]?.toDouble() ?: (1.0 / 3)
    // The dice come back: in the second game the opponent plays my first game's dice and I its,
    // so a first-game margin partly returns. [rho]: the share that does, for "back".
    val rho = opts["rho"]?.toDouble() ?: 0.7
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threads)
    val advisor = Advisor(tables)
    val first = Stakes.firstOfTwo(tables, tie)
    // As the league's "win" (LeagueRoster.winSearch with exactAt 5), with the match's stakes.
    fun win(stakes: Stakes?, back: OppModel? = null) = AdvisorPlayer(
        advisor, Mode.WIN, LeagueRoster.BUDGET, fixedDepth = 1, name = "win", exactAt = 5,
        tieValue = if (stakes == null) 0.0 else tie, oppModel = back, stakes = stakes,
    )
    // A player keeps its turn's plan: one per game.
    fun ev() = AdvisorPlayer(advisor, Mode.EXPECTED_SCORE, name = "ev")
    val all = listOf<Pair<String, (Int, Int?) -> Player>>(
        "plain" to { _, _ -> win(null) },
        "last" to { game, carry -> if (game == 0) win(null) else win(Stakes.last(carry!!, tie)) },
        "both" to { game, carry -> if (game == 0) win(first) else win(Stakes.last(carry!!, tie)) },
        // The first game for points (max expectation: its board is mostly luck that comes back).
        "ev1" to { game, carry -> if (game == 0) ev() else win(Stakes.last(carry!!, tie)) },
        // And the second reading the opponent [rho] of my first-game margin stronger, by the share
        // of the game left (the luck that comes back has not come yet).
        "back" to { game, carry -> if (game == 0) ev() else win(Stakes.last(carry!!, tie), OppModel(rho * carry!!, 1.0)) },
        "ev" to { _, _ -> ev() },
    )
    val chosen = (opts["variants"] ?: "plain,last,both,ev1,back,ev").split(',').toSet()
    val variants = all.filter { it.first == "plain" || it.first in chosen }
    println("tie value of a level match: %.3f; worth of a first-game margin over %d values from %d".format(tie, first.value.size, first.lo))
    println("opponent | variant | match points | match share | game share | gain in points, match, games (95%)")
    for (key in opps) {
        val opp = roster[key] ?: run { println("$key: no such AI"); null } ?: continue
        val t0 = System.nanoTime()
        val rows = (0 until matches).map { i ->
            pool.submit(Callable {
                val s = seed * 104729 + 1_000_003L * (key.hashCode() and 0xffff) + i
                variants.map { (_, make) -> match(rules, make, opp, s) }
            })
        }.map { it.get() }
        val secs = (System.nanoTime() - t0) / 1e9
        fun mean(xs: List<Double>) = xs.average()
        fun ci(xs: List<Double>): Double {
            val m = xs.average()
            return 1.96 * sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1) / xs.size)
        }
        for ((v, name) in variants.map { it.first }.withIndex()) {
            val pts = rows.map { it[v].points / 3.0 }
            val share = rows.map { it[v].share }
            val games = rows.map { it[v].games }
            val gain = if (v == 0) "" else {
                val dp = rows.map { (it[v].points - it[0].points) / 3.0 }
                val ds = rows.map { it[v].share - it[0].share }
                val dg = rows.map { it[v].games - it[0].games }
                "%+.2f ±%.2f, %+.2f ±%.2f, %+.2f ±%.2f %%p".format(100 * mean(dp), 100 * ci(dp), 100 * mean(ds), 100 * ci(ds), 100 * mean(dg), 100 * ci(dg))
            }
            println("%s | %s | %.1f%% | %.1f%% | %.1f%% | %s".format(key, name, 100 * mean(pts), 100 * mean(share), 100 * mean(games), gain))
        }
        // How much of the first game's margin the second gives back (plain win AI on both sides of it).
        val d1 = rows.map { it[0].d1.toDouble() }
        val d2 = rows.map { it[0].d2.toDouble() }
        val m1 = d1.average()
        val m2 = d2.average()
        val cov = d1.indices.sumOf { (d1[it] - m1) * (d2[it] - m2) }
        val v1 = d1.sumOf { (it - m1) * (it - m1) }
        val v2 = d2.sumOf { (it - m2) * (it - m2) }
        println("   [$matches matches each, %.0fs; plain: corr(d1, d2) %.2f, slope %.2f, sd of d1+d2 %.1f]".format(
            secs, cov / sqrt(v1 * v2), cov / v1, sqrt(d1.indices.sumOf { val t = d1[it] + d2[it] - m1 - m2; t * t } / (d1.size - 1)),
        ))
    }
    pool.shutdown()
}

/** One match from my side: league points (3/1/0), match share (a draw half), share of games. */
private class MatchOutcome(val points: Int, val share: Double, val games: Double, val d1: Int, val d2: Int)

/**
 * A mirrored league match on deal [seed], dealt as [yacht.engine.league.LeagueMatch.playMatch]
 * deals it: I move first in game 0 on the first stream, second in game 1 on the second.
 * [make] gives my player for a game and my margin so far (null in game 0).
 */
private fun match(rules: RuleSet, make: (Int, Int?) -> Player, opp: LeagueAi, seed: Long): MatchOutcome {
    val s0 = DiceStream.mix(seed, 0)
    val s1 = DiceStream.mix(seed, 1)
    fun rng(game: Int, seat: Int) = Random(DiceStream.mix(seed, 7, game.toLong(), seat.toLong()))
    val (a1, b1) = game(rules, make(0, null), opp.player(rng(0, 1), null), DiceStream(s0), DiceStream(s1))
    val g1 = GameScore(a1.total, b1.total, false, false, false, false)
    val (b2, a2) = game(rules, opp.player(rng(1, 0), null), make(1, a1.total - b1.total), DiceStream(s0), DiceStream(s1))
    val g2 = GameScore(a2.total, b2.total, false, false, false, false)
    val games = listOf(g1, g2)
    val result = TieRule.match(games)
    fun s(g: GameScore) = if (g.a > g.b) 1.0 else if (g.a == g.b) 0.5 else 0.0
    return MatchOutcome(TieRule.points(result), (result + 1) / 2.0, (s(g1) + s(g2)) / 2, g1.a - g1.b, g2.a - g2.b)
}

/** One game: [first] on [d0], [second] on [d1]; the two final cards. */
private fun game(rules: RuleSet, first: Player, second: Player, d0: DiceStream, d1: DiceStream): Pair<Board, Board> {
    var x = Board(rules)
    var y = Board(rules)
    for (turn in 0 until Category.COUNT) {
        x = turn(rules, first, x, y, d0, turn)
        y = turn(rules, second, y, x, d1, turn)
    }
    return x to y
}

private fun turn(rules: RuleSet, p: Player, me: Board, opp: Board, stream: DiceStream, turn: Int): Board {
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

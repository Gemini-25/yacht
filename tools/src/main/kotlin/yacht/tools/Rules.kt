package yacht.tools

import yacht.engine.DiceStream
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTables
import yacht.engine.league.MatchFormat
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.sign

/**
 * Match rules compared on the same games: for each pair of AIs, series of [PAIRS] mirrored pairs
 * (two games on the same dice, seats swapped, as the league deals a match's legs) and [SINGLES]
 * single games on fresh deals (seats alternating, as deciders); every rule reads its result off
 * the same series. The AIs play each game alone, as the league's AIs do. Prints, per rule and
 * pair, A's wins, draws and losses, the games a match takes, and how often the stronger side
 * (the pair's first) comes out ahead.
 */
fun rules(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val roster = LeagueRoster.build(tables, LeagueTables(tables.rules)).associateBy { it.key }
    val pairs = (opts["pairs"] ?: "slip10:slip10,slip5:slip10,ev:slip10,ev:greedy,win:ev").split(',').map { it.split(':').let { (a, b) -> a to b } }
    val series = opts["games"]?.toInt() ?: 3000
    val seed = opts["seed"]?.toLong() ?: 2029L
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threads)
    val results = LinkedHashMap<String, List<Series>>()
    for ((ka, kb) in pairs) {
        val a = roster.getValue(ka)
        val b = roster.getValue(kb)
        val t0 = System.nanoTime()
        results["$ka:$kb"] = (0 until series).map { i ->
            pool.submit(Callable { play(tables.rules, a, b, DiceStream.mix(seed, ka.hashCode().toLong(), kb.hashCode().toLong(), i.toLong())) })
        }.map { it.get() }
        System.err.println("$ka vs $kb: %.0fs".format((System.nanoTime() - t0) / 1e9))
    }
    pool.shutdown()
    for ((group, list) in listOf("league" to LEAGUE, "knockout" to KNOCKOUT)) {
        println("== $group: A wins / draws / B wins (%), games per match")
        println("rule | " + results.keys.joinToString(" | "))
        for (rule in list) {
            val cells = results.values.map { ss ->
                var w = 0
                var d = 0
                var l = 0
                var g = 0L
                for (s in ss) {
                    val (r, n) = rule.decide(s)
                    when (r) {
                        1 -> w++
                        0 -> d++
                        else -> l++
                    }
                    g += n
                }
                val n = ss.size.toDouble()
                "%.1f / %.1f / %.1f · %.2f".format(100 * w / n, 100 * d / n, 100 * l / n, g / n)
            }
            println("${rule.name} | " + cells.joinToString(" | "))
        }
    }
}

/** The pairs' games (A's score first; pair j's first game has A first) and the single games (A first on even k). */
private class Series(val pairs: List<List<GameScore>>, val singles: List<GameScore>)

private const val PAIRS = 4
private const val SINGLES = 5

private fun play(rules: yacht.engine.RuleSet, a: LeagueAi, b: LeagueAi, seed: Long): Series {
    val pairs = (0 until PAIRS).map { j -> LeagueMatch.playMatch(rules, a, b, DiceStream.mix(seed, 100L + j), MatchFormat.MIRRORED).games }
    val singles = (0 until SINGLES).map { k ->
        val s = DiceStream.mix(seed, 300L + k)
        if (k % 2 == 0) LeagueMatch.playMatch(rules, a, b, s, MatchFormat.SINGLE).games[0]
        else LeagueMatch.playMatch(rules, b, a, s, MatchFormat.SINGLE).games[0].let { GameScore(it.b, it.a) }
    }
    return Series(pairs, singles)
}

/** A rule: the result from A's side (+1, 0, -1) and the games it took. */
private class Rule(val name: String, val decide: (Series) -> Pair<Int, Int>)

private fun won(g: GameScore) = if (g.a > g.b) 1.0 else if (g.a == g.b) 0.5 else 0.0
private fun games(gs: List<GameScore>) = gs.sumOf { won(it) } * 2 - gs.size // won minus lost, in games
private fun points(gs: List<GameScore>) = gs.sumOf { it.a - it.b }

/** More games won, else the higher total, else level ([yacht.engine.league.TieRule.match]). */
private fun gamesThenTotal(gs: List<GameScore>): Int {
    val g = games(gs)
    return if (g != 0.0) g.sign.toInt() else points(gs).sign
}

/** Same dice compared: on each dice stream, who scored more with it (first game's A against second game's B, and the other way). */
private fun sameDice(pair: List<GameScore>): Int {
    val s0 = (pair[0].a - pair[1].b).sign
    val s1 = (pair[1].a - pair[0].b).sign
    return (s0 + s1).sign
}

/** Single games one after another until one is won (at most [most]): +1/-1, or 0 still level; and the games played. */
private fun sudden(s: Series, most: Int = SINGLES): Pair<Int, Int> {
    for (k in 0 until most) {
        val r = (s.singles[k].a - s.singles[k].b).sign
        if (r != 0) return r to k + 1
    }
    return 0 to most
}

private val LEAGUE = listOf(
    Rule("L1 합계 (지금: 판 수, 같으면 합계)") { s -> gamesThenTotal(s.pairs[0]) to 2 },
    Rule("L2 판 승점 (1승1패 무)") { s -> games(s.pairs[0]).sign.toInt() to 2 },
    Rule("L3 판 승점, 1승1패는 합계 20점 차 이상만 승") { s ->
        val g = games(s.pairs[0])
        val p = points(s.pairs[0])
        (if (g != 0.0) g.sign.toInt() else if (Math.abs(p) >= 20) p.sign else 0) to 2
    },
    Rule("L4 같은 주사위 비교 (같은 주사위로 더 낸 쪽)") { s -> sameDice(s.pairs[0]) to 2 },
    Rule("L5 3판 2선승 (1승1패면 새 주사위 3판째)") { s ->
        val p = s.pairs[0]
        if (games(p) != 0.0) games(p).sign.toInt() to 2 else gamesThenTotal(p + s.singles[0]) to 3
    },
    Rule("L6 1승1패면 같은 주사위 두 판 더 (판 수, 그다음 합계)") { s ->
        val p = s.pairs[0]
        if (games(p) != 0.0) games(p).sign.toInt() to 2 else gamesThenTotal(p + s.pairs[1]) to 4
    },
    Rule("L7 같은 주사위 4판 (판 수, 같으면 합계)") { s -> gamesThenTotal(s.pairs[0] + s.pairs[1]) to 4 },
    Rule("L8 같은 주사위 4판 판 승점 (2:2 무)") { s -> games(s.pairs[0] + s.pairs[1]).sign.toInt() to 4 },
    Rule("L9 단판") { s -> (s.singles[0].a - s.singles[0].b).sign to 1 },
)

private fun legs(s: Series, n: Int) = s.pairs.take(n).flatten()

private val KNOCKOUT = listOf(
    Rule("K1 지금: 1레그, 판 수→합계→승부판 3→시드") { s ->
        val r = gamesThenTotal(s.pairs[0])
        if (r != 0) r to 2 else sudden(s, 3).let { (x, n) -> (if (x != 0) x else 1) to 2 + n }
    },
    Rule("K2 지금: 2레그") { s ->
        val r = gamesThenTotal(legs(s, 2))
        if (r != 0) r to 4 else sudden(s, 3).let { (x, n) -> (if (x != 0) x else 1) to 4 + n }
    },
    Rule("K3 1레그 판 수, 같으면 서든데스 단판 (합계 없음)") { s ->
        val g = games(s.pairs[0])
        if (g != 0.0) g.sign.toInt() to 2 else sudden(s).let { (x, n) -> (if (x != 0) x else 1) to 2 + n }
    },
    Rule("K4 2레그 판 수, 같으면 서든데스") { s ->
        val g = games(legs(s, 2))
        if (g != 0.0) g.sign.toInt() to 4 else sudden(s).let { (x, n) -> (if (x != 0) x else 1) to 4 + n }
    },
    Rule("K5 1레그 판 수, 같으면 같은 주사위 연장 (판 수→합계)") { s ->
        val g = games(s.pairs[0])
        if (g != 0.0) g.sign.toInt() to 2 else gamesThenTotal(legs(s, 2)).let { r -> (if (r != 0) r else 1) to 4 }
    },
    Rule("K6 레그 승수 (2레그, 레그는 판 승점; 같으면 합계→서든데스)") { s ->
        val legsWon = (0 until 2).sumOf { j -> games(s.pairs[j]).sign.toInt() }
        if (legsWon != 0) legsWon.sign to 4 else points(legs(s, 2)).sign.let { p ->
            if (p != 0) p to 4 else sudden(s).let { (x, n) -> (if (x != 0) x else 1) to 4 + n }
        }
    },
    Rule("K7 먼저 3판 (같은 주사위 짝으로 최대 8판, 남으면 합계)") { s ->
        var aw = 0.0
        var bw = 0.0
        var n = 0
        val all = legs(s, PAIRS)
        for (g in all) {
            n++
            aw += won(g)
            bw += 1 - won(g)
            if (aw >= 3 || bw >= 3) break
        }
        (if (aw != bw) (aw - bw).sign.toInt() else points(all.take(n)).sign.let { if (it != 0) it else 1 }) to n
    },
    Rule("K8 같은 주사위 비교 2레그 (같으면 서든데스)") { s ->
        val r = sameDice(s.pairs[0]) + sameDice(s.pairs[1])
        if (r != 0) r.sign to 4 else sudden(s).let { (x, n) -> (if (x != 0) x else 1) to 4 + n }
    },
    Rule("K9 합계만 (2레그 4판 합계, 같으면 서든데스)") { s ->
        val p = points(legs(s, 2)).sign
        if (p != 0) p to 4 else sudden(s).let { (x, n) -> (if (x != 0) x else 1) to 4 + n }
    },
)

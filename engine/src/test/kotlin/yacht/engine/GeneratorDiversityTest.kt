package yacht.engine

import yacht.engine.league.CustomAis
import yacht.engine.league.CustomMistake
import yacht.engine.league.LeagueTables
import yacht.engine.league.PlayGene
import yacht.engine.league.PlayerBook
import yacht.engine.league.PlayerGenerator
import yacht.engine.league.PlayerRecord
import yacht.engine.league.Temper
import java.util.Random
import java.util.stream.IntStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Generated players play apart (생성 다양화 v2): the noise-free choices of 200 new players on the
 * same 100 boards, grouped by complete linkage (a group's members all within 2% of each other's
 * choices). Measured on 400 boards: 1000 players make 1000 styles at 2% and no twins (533 and 91
 * twins before v2), 3000 make 2991.
 */
class GeneratorDiversityTest {
    private val tables get() = SolverTest.tables
    private val extra by lazy { LeagueTables(tables.rules) }

    /** Every draw at its top: a chameleon takes its last mood, nothing else draws. */
    private class TopRandom : Random() {
        override fun next(bits: Int): Int = if (bits >= 32) -1 else (1 shl bits) - 1
        override fun nextInt(bound: Int): Int = bound - 1
        override fun nextBoolean(): Boolean = true
    }

    /** A board and the throws of one turn on it: (throws done, roll) for each decision; 3 = the box. */
    private class Turn(val me: Board, val opp: Board, val decisions: List<Pair<Int, Int>>)

    /** One random turn of each of [games] games between max-expectation players, with and without mistakes. */
    private fun turns(games: Int, seed: Long): List<Turn> {
        val scores = ScoreTable.of(tables.rules)
        return (0 until games).map { g ->
            val rng = Random(seed * 1_000_003 + g)
            fun policy(): Player {
                val r = Random(rng.nextLong())
                return when (rng.nextInt(3)) {
                    0 -> PlanPlayer("ev") { me, _ -> TurnPlan(me, ExpectedAfter(tables.ev, me.mask)) }
                    1 -> SlipPlayer(tables.ev, 0.1, r)
                    else -> SoftmaxPlayer(tables.ev, 1.0, r)
                }
            }
            val players = arrayOf(policy(), policy())
            val boards = arrayOf(Board(tables.rules), Board(tables.rules))
            val side = rng.nextInt(2)
            val pick = rng.nextInt(Category.COUNT)
            val dice = Random(rng.nextLong())
            var out: Turn? = null
            for (t in 0 until Category.COUNT) for (p in 0..1) {
                val me = boards[p]
                players[p].startTurn(me, boards[1 - p])
                val ds = ArrayList<Pair<Int, Int>>()
                var r = Match.throwDice(dice, Dice.EMPTY_KEEP)
                for (done in 1..2) {
                    ds += done to r
                    val keep = players[p].chooseKeep(done, r)
                    if (keep == Dice.rollAsKeep[r]) break
                    r = Match.throwDice(dice, keep)
                }
                ds += 3 to r
                val c = players[p].chooseCategory(r)
                if (p == side && t == pick) out = Turn(me, boards[1 - p], ds)
                boards[p] = me.with(c, scores[r, c.ordinal])
            }
            out!!
        }
    }

    /** The noise-free choices of each player on [turns] (keeps and boxes in one row). */
    private fun prints(players: List<PlayerRecord>, turns: List<Turn>): List<IntArray> =
        IntStream.range(0, players.size).parallel().mapToObj { i ->
            val r = players[i]
            val spec = r.spec.copy(mistake = CustomMistake.NONE, amount = 0.0, temper = Temper.NONE, temperStrength = 1.0)
            val ai = CustomAis.build(spec, tables, extra, key = r.key, name = r.name)
            val row = ArrayList<Int>()
            for (turn in turns) {
                val p = ai.make(TopRandom())
                p.startTurn(turn.me, turn.opp)
                for ((done, roll) in turn.decisions) row += if (done < 3) p.chooseKeep(done, roll) else p.chooseCategory(roll).ordinal
            }
            row.toIntArray()
        }.toList()

    private fun distance(a: IntArray, b: IntArray): Double = a.indices.count { a[it] != b[it] }.toDouble() / a.size

    /** Groups of complete linkage: merged while every pair of the two groups is under [t] apart. */
    private fun styles(d: Array<DoubleArray>, t: Double): Int {
        val groups = d.indices.map { mutableListOf(it) }.toMutableList()
        while (true) {
            var best = Double.MAX_VALUE
            var bi = -1
            var bj = -1
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val far = groups[i].maxOf { a -> groups[j].maxOf { b -> d[a][b] } }
                if (far < best) { best = far; bi = i; bj = j }
            }
            if (bi < 0 || best >= t) return groups.size
            groups[bi].addAll(groups.removeAt(bj))
        }
    }

    private fun measure(players: List<PlayerRecord>, turns: List<Turn>): Triple<Int, Int, Int> {
        val p = prints(players, turns)
        val d = Array(p.size) { i -> DoubleArray(p.size) { j -> distance(p[i], p[j]) } }
        val twins = p.indices.count { i -> p.indices.any { j -> j != i && d[i][j] == 0.0 } }
        return Triple(twins, styles(d, 0.02), styles(d, 0.05))
    }

    @Test
    fun generatedPlayersPlayApart() {
        // Measured: (0 twins, 200 styles at 2%, 195 at 5%); the same players without their v2
        // genes (older intensities and foresight, no tastes or situations) make (10, 173, 131).
        val turns = turns(100, 7)
        val players = PlayerBook().apply { ensureGenerated(200) }.alive
        val (twins, at2, at5) = measure(players, turns)
        println("${turns.sumOf { it.decisions.size }} decisions: $twins twins, $at2 styles at 2%, $at5 at 5%")
        assertEquals(0, twins, "players making every choice the same")
        assertTrue(at2 >= 195, "$at2 styles at 2%")
        assertTrue(at5 >= 180, "$at5 styles at 5%")
        // Nobody is born with an alive player's genes, and plain max expectation always has a taste of its own.
        assertEquals(players.size, players.map { it.spec.play!!.encode() }.toSet().size)
        for (p in players) {
            val g = p.spec.play!!
            assertEquals(PlayGene.V2, g.v)
            if (g.plain) assertTrue(PlayerGenerator.strong(g.taste), g.encode())
        }
    }
}

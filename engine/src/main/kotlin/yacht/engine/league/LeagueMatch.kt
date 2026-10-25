package yacht.engine.league

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.DiceStream
import yacht.engine.Player
import yacht.engine.RuleSet
import java.util.Random
import java.util.concurrent.CancellationException

/**
 * A league match: two games on the same dice with the seats swapped, so the luck of the deal is
 * shared and only the decisions differ. Also knockout ties (legs and decider games) and team
 * matches (one match per board), with optional per-game facts for the statistics.
 */
object LeagueMatch {
    /**
     * Plays [a] against [b] on the deal [seed]; [a] moves first in the first game. Throws
     * [CancellationException] between turns once [cancelled] says so.
     */
    fun play(rules: RuleSet, a: LeagueAi, b: LeagueAi, seed: Long, cancelled: () -> Boolean = { false }): List<GameScore> =
        playMatch(rules, a, b, seed, MatchFormat.MIRRORED, null, cancelled).games

    /** One match in the given [format], with each game's facts when [estimate] is given. */
    fun playMatch(
        rules: RuleSet,
        a: LeagueAi,
        b: LeagueAi,
        seed: Long,
        format: MatchFormat = MatchFormat.MIRRORED,
        estimate: WinEstimate? = null,
        cancelled: () -> Boolean = { false },
    ): BoardResult {
        val games = ArrayList<GameScore>()
        val facts = ArrayList<GameFacts>()
        val first = DiceStream(DiceStream.mix(seed, 0))
        val second = DiceStream(DiceStream.mix(seed, 1))
        fun rng(game: Int, seat: Int) = Random(DiceStream.mix(seed, 7, game.toLong(), seat.toLong()))
        val g1 = game(rules, a.make(rng(0, 0)), b.make(rng(0, 1)), first, second, estimate, cancelled)
        games += score(g1.first, g1.second)
        g1.facts?.let { facts += it }
        if (format != MatchFormat.SINGLE) {
            val g2 = game(rules, b.make(rng(1, 0)), a.make(rng(1, 1)), first, second, estimate, cancelled)
            games += score(g2.second, g2.first)
            g2.facts?.let { facts += it }
        }
        var deciders = 0
        if (format == MatchFormat.BEST_OF_3 && games.sumOf { if (it.a > it.b) 1.0 else if (it.a == it.b) 0.5 else 0.0 } == 1.0) {
            decider(rules, a, b, seed, 0, estimate, cancelled, games, facts)
            deciders = 1
        }
        return BoardResult(a.key, b.key, games, facts, deciders)
    }

    /**
     * A knockout tie: [legs] matches on their own deals, then single decider games (seats
     * alternating, at most [maxDeciders]) while level. Still level after them: the caller's seed
     * order decides (see [TieRule]).
     */
    fun playTie(
        rules: RuleSet,
        a: LeagueAi,
        b: LeagueAi,
        seed: Long,
        legs: Int = 1,
        format: MatchFormat = MatchFormat.MIRRORED,
        estimate: WinEstimate? = null,
        maxDeciders: Int = 3,
        cancelled: () -> Boolean = { false },
    ): BoardResult {
        val games = ArrayList<GameScore>()
        val facts = ArrayList<GameFacts>()
        for (leg in 0 until legs) {
            val m = playMatch(rules, a, b, DiceStream.mix(seed, 100L + leg), if (format == MatchFormat.BEST_OF_3) MatchFormat.MIRRORED else format, estimate, cancelled)
            games += m.games
            facts += m.facts
        }
        var deciders = 0
        while (deciders < maxDeciders && TieRule.match(games) == 0) {
            decider(rules, a, b, seed, deciders, estimate, cancelled, games, facts)
            deciders++
        }
        return BoardResult(a.key, b.key, games, facts, deciders)
    }

    /** A team match: board i is a match between the lineups' i-th AIs, on its own deal. */
    fun playTeam(
        rules: RuleSet,
        lineupA: List<LeagueAi>,
        lineupB: List<LeagueAi>,
        seed: Long,
        format: MatchFormat = MatchFormat.MIRRORED,
        estimate: WinEstimate? = null,
        cancelled: () -> Boolean = { false },
    ): List<BoardResult> = lineupA.zip(lineupB).mapIndexed { i, (x, y) ->
        playMatch(rules, x, y, DiceStream.mix(seed, 200L + i), format, estimate, cancelled)
    }

    /** Plays a whole job; [ai] finds each AI (null: the job cannot be played). */
    fun playJob(
        rules: RuleSet,
        job: Fixture,
        ai: (String) -> LeagueAi?,
        format: MatchFormat = MatchFormat.MIRRORED,
        estimate: WinEstimate? = null,
        cancelled: () -> Boolean = { false },
    ): JobResult? {
        val t0 = System.nanoTime()
        val boards: List<BoardResult>
        val winner: Int
        when (job.kind) {
            JobKind.MATCH -> {
                val x = ai(job.a) ?: return null
                val y = ai(job.b) ?: return null
                val r = playMatch(rules, x, y, job.seed, format, estimate, cancelled)
                boards = listOf(r)
                winner = TieRule.match(r.games)
            }
            JobKind.TIE -> {
                val x = ai(job.a) ?: return null
                val y = ai(job.b) ?: return null
                val r = playTie(rules, x, y, job.seed, job.legs, format, estimate, cancelled = cancelled)
                boards = listOf(r)
                // Level after every decider: side A (the higher seed by convention) goes through.
                winner = TieRule.match(r.games).let { if (it == 0) 1 else it }
            }
            JobKind.TEAM -> {
                val xs = job.lineupA.map { ai(it) ?: return null }
                val ys = job.lineupB.map { ai(it) ?: return null }
                boards = playTeam(rules, xs, ys, job.seed, format, estimate, cancelled)
                winner = TieRule.team(boards)
            }
        }
        return JobResult(job, boards, winner, System.nanoTime() - t0)
    }

    /** Decider game [k]: a fresh deal, A first on even k, B first on odd k. */
    private fun decider(
        rules: RuleSet,
        a: LeagueAi,
        b: LeagueAi,
        seed: Long,
        k: Int,
        estimate: WinEstimate?,
        cancelled: () -> Boolean,
        games: MutableList<GameScore>,
        facts: MutableList<GameFacts>,
    ) {
        val s0 = DiceStream(DiceStream.mix(seed, 300L + k, 0))
        val s1 = DiceStream(DiceStream.mix(seed, 300L + k, 1))
        fun rng(seat: Int) = Random(DiceStream.mix(seed, 301L + k, seat.toLong()))
        if (k % 2 == 0) {
            val g = game(rules, a.make(rng(0)), b.make(rng(1)), s0, s1, estimate, cancelled)
            games += score(g.first, g.second)
            g.facts?.let { facts += it }
        } else {
            val g = game(rules, b.make(rng(0)), a.make(rng(1)), s0, s1, estimate, cancelled)
            games += score(g.second, g.first)
            g.facts?.let { facts += it }
        }
    }

    private fun score(a: Board, b: Board) = GameScore(
        a.total, b.total, (a[Category.YACHT] ?: 0) > 0, (b[Category.YACHT] ?: 0) > 0, a.bonus > 0, b.bonus > 0,
    )

    /** A finished game: the first player's and the second's card, and its facts when collected. */
    private class Played(val first: Board, val second: Board, val facts: GameFacts?)

    private fun game(
        rules: RuleSet,
        first: Player,
        second: Player,
        s0: DiceStream,
        s1: DiceStream,
        estimate: WinEstimate?,
        cancelled: () -> Boolean,
    ): Played {
        var x = Board(rules)
        var y = Board(rules)
        val trace = if (estimate != null) ArrayList<Pair<Board, Board>>(2 * Category.COUNT) else null
        for (turn in 0 until Category.COUNT) {
            if (cancelled()) throw CancellationException("league stopped")
            x = turn(rules, first, x, y, s0, turn)
            trace?.add(x to y)
            y = turn(rules, second, y, x, s1, turn)
            trace?.add(x to y)
        }
        return Played(x, y, trace?.let { GameFacts.of(it, estimate!!) })
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
}

/** One turn of a watched game: who moved (0 = first player), its throws, held dice and box. */
data class WatchTurn(
    val side: Int,
    val throws: List<List<Int>>,
    val held: List<List<Int>>,
    val category: yacht.engine.Category,
    val points: Int,
    /** Both cards after this turn (first player, second player). */
    val first: Board,
    val second: Board,
)

/** A single game kept turn by turn, for watching it back. */
object WatchMatch {
    fun play(rules: RuleSet, first: Player, second: Player, rng: Random): List<WatchTurn> {
        var a = Board(rules)
        var b = Board(rules)
        val out = ArrayList<WatchTurn>()
        for (turn in 0 until Category.COUNT) {
            for (side in 0..1) {
                val p = if (side == 0) first else second
                val me = if (side == 0) a else b
                val opp = if (side == 0) b else a
                p.startTurn(me, opp)
                val throws = ArrayList<List<Int>>()
                val held = ArrayList<List<Int>>()
                var r = yacht.engine.Match.throwDice(rng, Dice.EMPTY_KEEP)
                throws += Dice.rollFaces(r)
                for (rollsDone in 1..2) {
                    val keep = p.chooseKeep(rollsDone, r)
                    if (keep == Dice.rollAsKeep[r]) break
                    held += Dice.keepFaces(keep)
                    r = yacht.engine.Match.throwDice(rng, keep)
                    throws += Dice.rollFaces(r)
                }
                val c = p.chooseCategory(r)
                val pts = rules.score(Dice.rollCounts[r], c)
                if (side == 0) a = a.with(c, pts) else b = b.with(c, pts)
                out += WatchTurn(side, throws, held, c, pts, a, b)
            }
        }
        return out
    }
}

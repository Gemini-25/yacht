package yacht.engine.league

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.DiceStream
import yacht.engine.OppModel
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
    /** No opponent records known: every AI plays as it always does. */
    val NO_SCOUT: (String) -> OppModel? = { null }

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
        /** The opponent model of each AI by its record ([OppModel], for the 상대 맞춤 AIs). */
        scout: (String) -> OppModel? = NO_SCOUT,
    ): BoardResult {
        val games = ArrayList<GameScore>()
        val facts = ArrayList<GameFacts>()
        val first = DiceStream(DiceStream.mix(seed, 0))
        val second = DiceStream(DiceStream.mix(seed, 1))
        fun rng(game: Int, seat: Int) = Random(DiceStream.mix(seed, 7, game.toLong(), seat.toLong()))
        val g1 = game(rules, a.player(rng(0, 0), scout(b.key)), b.player(rng(0, 1), scout(a.key)), first, second, estimate, cancelled)
        games += score(g1.first, g1.second)
        g1.facts?.let { facts += it }
        if (format != MatchFormat.SINGLE) {
            val g2 = game(rules, b.player(rng(1, 0), scout(a.key)), a.player(rng(1, 1), scout(b.key)), first, second, estimate, cancelled)
            games += score(g2.second, g2.first)
            g2.facts?.let { facts += it }
        }
        var deciders = 0
        if (format == MatchFormat.BEST_OF_3 && games.sumOf { if (it.a > it.b) 1.0 else if (it.a == it.b) 0.5 else 0.0 } == 1.0) {
            decider(rules, a, b, seed, 0, estimate, cancelled, games, facts, scout)
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
        /** The opponent model of each AI by its record ([OppModel], for the 상대 맞춤 AIs). */
        scout: (String) -> OppModel? = NO_SCOUT,
    ): BoardResult {
        val games = ArrayList<GameScore>()
        val facts = ArrayList<GameFacts>()
        for (leg in 0 until legs) {
            val m = playMatch(rules, a, b, DiceStream.mix(seed, 100L + leg), if (format == MatchFormat.BEST_OF_3) MatchFormat.MIRRORED else format, estimate, cancelled, scout)
            games += m.games
            facts += m.facts
        }
        var deciders = 0
        while (deciders < maxDeciders && TieRule.match(games) == 0) {
            decider(rules, a, b, seed, deciders, estimate, cancelled, games, facts, scout)
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
        /** The opponent model of each AI by its record ([OppModel], for the 상대 맞춤 AIs). */
        scout: (String) -> OppModel? = NO_SCOUT,
    ): List<BoardResult> = lineupA.zip(lineupB).mapIndexed { i, (x, y) ->
        playMatch(rules, x, y, DiceStream.mix(seed, 200L + i), format, estimate, cancelled, scout)
    }

    /** Plays a whole job; [ai] finds each AI (null: the job cannot be played). */
    fun playJob(
        rules: RuleSet,
        job: Fixture,
        ai: (String) -> LeagueAi?,
        format: MatchFormat = MatchFormat.MIRRORED,
        estimate: WinEstimate? = null,
        cancelled: () -> Boolean = { false },
        /** The opponent model of each AI by its record ([OppModel], for the 상대 맞춤 AIs). */
        scout: (String) -> OppModel? = NO_SCOUT,
    ): JobResult? {
        val t0 = System.nanoTime()
        val boards: List<BoardResult>
        val winner: Int
        when (job.kind) {
            JobKind.MATCH -> {
                val x = ai(job.a) ?: return null
                val y = ai(job.b) ?: return null
                val r = playMatch(rules, x, y, job.seed, format, estimate, cancelled, scout)
                boards = listOf(r)
                winner = TieRule.match(r.games)
            }
            JobKind.TIE -> {
                val x = ai(job.a) ?: return null
                val y = ai(job.b) ?: return null
                val r = playTie(rules, x, y, job.seed, job.legs, format, estimate, cancelled = cancelled, scout = scout)
                boards = listOf(r)
                // Level after every decider: side A (the higher seed by convention) goes through.
                winner = TieRule.match(r.games).let { if (it == 0) 1 else it }
            }
            JobKind.TEAM -> {
                val xs = job.lineupA.map { ai(it) ?: return null }
                val ys = job.lineupB.map { ai(it) ?: return null }
                boards = playTeam(rules, xs, ys, job.seed, format, estimate, cancelled, scout)
                winner = TieRule.team(boards)
            }
        }
        return JobResult(job, boards, winner, System.nanoTime() - t0)
    }

    /**
     * The deal of game [game] (its index in the board's games, deciders last) of board [board] of
     * [job], exactly as [playJob] dealt it, so that one game can be played again alone with
     * [playGame]. [perMatch] is the number of games per match (1 for SINGLE, else 2); the games
     * after the matches (all legs of a tie) are the decider games.
     */
    fun deal(job: Fixture, board: Int, game: Int, perMatch: Int): GameDeal {
        require(perMatch in 1..2 && game >= 0) { "game $game with $perMatch per match" }
        val legs = if (job.kind == JobKind.TIE) maxOf(1, job.legs) else 1
        // Deciders hang off the tie's seed or the match's (a team board is a match of its own).
        val unit = if (job.kind == JobKind.TEAM) DiceStream.mix(job.seed, 200L + board) else job.seed
        val matchGames = legs * perMatch
        if (game < matchGames) {
            val seed = if (job.kind == JobKind.TIE) DiceStream.mix(unit, 100L + game / perMatch) else unit
            val g = (game % perMatch).toLong()
            return GameDeal(DiceStream.mix(seed, 0), DiceStream.mix(seed, 1), DiceStream.mix(seed, 7, g, 0), DiceStream.mix(seed, 7, g, 1), g == 0L)
        }
        val k = (game - matchGames).toLong()
        return GameDeal(
            DiceStream.mix(unit, 300L + k, 0), DiceStream.mix(unit, 300L + k, 1),
            DiceStream.mix(unit, 301L + k, 0), DiceStream.mix(unit, 301L + k, 1), k % 2 == 0L,
        )
    }

    /**
     * Plays one game again alone on its [deal] (see [deal]) through the same code as a job, and
     * records nothing. [a] and [b] are the board's sides; the score is from A's side. With
     * [watch], the game is also kept turn by turn for the watch screen.
     */
    fun playGame(
        rules: RuleSet,
        deal: GameDeal,
        a: LeagueAi,
        b: LeagueAi,
        estimate: WinEstimate? = null,
        watch: Boolean = false,
        cancelled: () -> Boolean = { false },
        /** The opponent model of each AI by its record ([OppModel], for the 상대 맞춤 AIs). */
        scout: (String) -> OppModel? = NO_SCOUT,
    ): SingleGame {
        val (x, y) = if (deal.aFirst) a to b else b to a
        val turns = if (watch) ArrayList<WatchTurn>(2 * Category.COUNT) else null
        val g = game(rules, x.player(Random(deal.rng0), scout(y.key)), y.player(Random(deal.rng1), scout(x.key)), DiceStream(deal.dice0), DiceStream(deal.dice1), estimate, cancelled, turns)
        val s = if (deal.aFirst) score(g.first, g.second) else score(g.second, g.first)
        return SingleGame(s, g.facts, turns.orEmpty())
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
        scout: (String) -> OppModel?,
    ) {
        val s0 = DiceStream(DiceStream.mix(seed, 300L + k, 0))
        val s1 = DiceStream(DiceStream.mix(seed, 300L + k, 1))
        fun rng(seat: Int) = Random(DiceStream.mix(seed, 301L + k, seat.toLong()))
        if (k % 2 == 0) {
            val g = game(rules, a.player(rng(0), scout(b.key)), b.player(rng(1), scout(a.key)), s0, s1, estimate, cancelled)
            games += score(g.first, g.second)
            g.facts?.let { facts += it }
        } else {
            val g = game(rules, b.player(rng(0), scout(a.key)), a.player(rng(1), scout(b.key)), s0, s1, estimate, cancelled)
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
        watch: MutableList<WatchTurn>? = null,
    ): Played {
        var x = Board(rules)
        var y = Board(rules)
        val trace = if (estimate != null) ArrayList<Pair<Board, Board>>(2 * Category.COUNT) else null
        for (turn in 0 until Category.COUNT) {
            if (cancelled()) throw CancellationException("league stopped")
            val l0 = watch?.let { TurnLog() }
            x = turn(rules, first, x, y, s0, turn, l0)
            trace?.add(x to y)
            l0?.let { watch!! += WatchTurn(0, it.throws, it.held, it.category, it.points, x, y) }
            val l1 = watch?.let { TurnLog() }
            y = turn(rules, second, y, x, s1, turn, l1)
            trace?.add(x to y)
            l1?.let { watch!! += WatchTurn(1, it.throws, it.held, it.category, it.points, x, y) }
        }
        return Played(x, y, trace?.let { GameFacts.of(it, estimate!!) })
    }

    /** What one turn did, kept only while watching. */
    private class TurnLog {
        val throws = ArrayList<List<Int>>(3)
        val held = ArrayList<List<Int>>(2)
        var category = Category.CHOICE
        var points = 0
    }

    private fun turn(rules: RuleSet, p: Player, me: Board, opp: Board, stream: DiceStream, turn: Int, log: TurnLog? = null): Board {
        p.startTurn(me, opp)
        var r = stream.throwDice(Dice.EMPTY_KEEP, turn, 0)
        log?.throws?.add(Dice.rollFaces(r))
        for (rollsDone in 1..2) {
            val keep = p.chooseKeep(rollsDone, r)
            if (keep == Dice.rollAsKeep[r]) break
            log?.held?.add(Dice.keepFaces(keep))
            r = stream.throwDice(keep, turn, rollsDone)
            log?.throws?.add(Dice.rollFaces(r))
        }
        val c = p.chooseCategory(r)
        val points = rules.score(Dice.rollCounts[r], c)
        log?.let {
            it.category = c
            it.points = points
        }
        return me.with(c, points)
    }
}

/**
 * Where one game of a job was dealt (see [LeagueMatch.deal]): the dice stream and the player's
 * random source of the seat that moves first ([dice0], [rng0]) and of the other ([dice1],
 * [rng1]), and whether the board's side A has the first seat.
 */
data class GameDeal(val dice0: Long, val dice1: Long, val rng0: Long, val rng1: Long, val aFirst: Boolean)

/** One game played again alone ([LeagueMatch.playGame]): its score from side A, its facts and turns (when watched). */
class SingleGame(val score: GameScore, val facts: GameFacts?, val turns: List<WatchTurn>)

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

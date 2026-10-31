package yacht.engine.league

import yacht.engine.Category

/** One finished game kept for the live board, seen from its board's side A. */
class LiveGame internal constructor(
    /** Side A moved first. */
    val aFirst: Boolean,
    val scoreA: Int,
    val scoreB: Int,
    // GameFacts' arrays, first player first: never changed after the game.
    private val totals: ShortArray,
    private val curve: ByteArray,
) {
    val halfTurns: Int get() = curve.size

    /** A's total after [h] half-turns (0: before the first move). */
    fun totalA(h: Int): Int = total(h, if (aFirst) 0 else 1)

    fun totalB(h: Int): Int = total(h, if (aFirst) 1 else 0)

    /** A's P(win) in percent after [h] half-turns (50 before the first move). */
    fun pA(h: Int): Int {
        if (h <= 0 || curve.isEmpty()) return 50
        val p = curve[minOf(h, curve.size) - 1].toInt()
        return if (aFirst) p else 100 - p
    }

    /** A's P(win) after every half-turn, in percent. */
    fun curveA(): IntArray = IntArray(curve.size) { pA(it + 1) }

    private fun total(h: Int, seat: Int): Int {
        if (h <= 0 || totals.isEmpty()) return 0
        val i = 2 * (minOf(h, totals.size / 2) - 1) + seat
        return totals[i].toInt()
    }
}

/** A board of a live fixture: its two AIs and their games in order. */
class LiveBoardGames(val a: String, val b: String, val games: List<LiveGame>)

/** A finished fixture kept for the live board ([seq] grows with every fixture added). */
class LiveFixture(
    val seq: Long,
    val fixture: Fixture,
    /** When it was added (the app's clock). */
    val finishedMs: Long,
    /** +1 side A, -1 side B, 0 level (as [JobResult.winner]). */
    val winner: Int,
    val boards: List<LiveBoardGames>,
    /** How its boards were decided (as [JobResult.format]). */
    val format: MatchFormat = MatchFormat.MIRRORED,
) {
    /** Games of its longest board: they are replayed one after the other, boards side by side. */
    val length: Int get() = boards.maxOf { it.games.size }

    /** Steps of its replay: every game shows 0..24 half-turns. */
    val steps: Int get() = length * LiveBoard.GAME_STEPS
}

/** The P(win) curve of one game of the feed. */
class GameCurve(
    val comp: Comp,
    val fixtureId: Int,
    /** Short fixture label ("3라운드", "결승"). */
    val label: String,
    val a: String,
    val b: String,
    val board: Int,
    val game: Int,
    val aFirst: Boolean,
    val scoreA: Int,
    val scoreB: Int,
    /** The fixture it came from (see [LiveFixture.seq]). */
    val fixtureSeq: Long,
    private val curve: ByteArray,
    /** The season it was played in (0 when not told: [LiveBoard.add]). */
    val season: Int = 0,
) {
    /** A's P(win) after every half-turn, in percent. */
    fun curveA(): IntArray = IntArray(curve.size) { if (aFirst) curve[it].toInt() else 100 - curve[it] }
}

/** One board on the screen now: the game being replayed and its totals at the half-turn reached. */
class LiveBoardFrame(
    val a: String,
    val b: String,
    /** The game shown (0-based) of [games]. */
    val game: Int,
    val games: Int,
    /** Half-turns of that game shown so far, 0..24. */
    val halfTurn: Int,
    val totalA: Int,
    val totalB: Int,
    /** A's P(win) in percent at that point. */
    val pA: Int,
    /** Games won before the one shown (all of them once [done]), a level game counting half. */
    val wonA: Double,
    val wonB: Double,
    /** Every game of this board is shown to the end. */
    val done: Boolean,
)

/** One slot of the live board: a fixture replayed as if in progress. */
class LiveFrame(
    val slot: Int,
    val fixture: Fixture,
    val boards: List<LiveBoardFrame>,
    /** Games won (a match or tie) or boards won (a team match) so far, level ones counting half. */
    val scoreA: Double,
    val scoreB: Double,
    /** The replay has reached the end (the final result stays on screen for a moment). */
    val finished: Boolean,
    /** Once [finished]: +1 side A, -1 side B, 0 level; null before. */
    val winner: Int?,
    /** 0..1, for a progress bar. */
    val progress: Double,
)

/**
 * The live scoreboard (라이브 스코어보드, docs/LEAGUE_SPEC.md section 4): the per-half-turn totals
 * of the newest [FIXTURES] finished fixtures, replayed turn by turn [SLOTS] at a time so the
 * board shows matches "in progress", and the P(win) curves of the newest [CURVES] games for the
 * feed. Memory only, by choice: it is a view of the last minute or so, refilled within seconds
 * once the league runs again, and not worth the save file (a team match alone is ~500 numbers).
 * Not thread-safe: callers synchronise.
 */
class LiveBoard {
    private val kept = ArrayDeque<LiveFixture>()
    private val feed = ArrayDeque<GameCurve>()
    private var seq = 0L

    // The replay: a slot takes the newest fixture not shown yet once its last one is over.
    private class Show(val fixture: LiveFixture, val begin: Long)

    private val slots = arrayOfNulls<Show>(SLOTS)
    private val shown = HashSet<Long>()
    private var session = Long.MIN_VALUE

    /** The kept fixtures, newest first. */
    val fixtures: List<LiveFixture> get() = kept.toList()

    /** The feed's game curves, newest first. */
    val curves: List<GameCurve> get() = feed.toList()

    /**
     * Keeps a finished job (any competition, friendlies too when the feed shows them) of [season]
     * (0: not told) at the app's time [nowMs]; boards without facts are left out, and a job with
     * none is ignored.
     */
    fun add(r: JobResult, nowMs: Long, season: Int = 0) {
        val f = r.fixture
        val n = seq
        val boards = ArrayList<LiveBoardGames>(r.boards.size)
        val games = ArrayList<GameCurve>()
        for ((bi, bd) in r.boards.withIndex()) {
            if (bd.games.isEmpty() || bd.facts.size != bd.games.size) continue
            val per = gamesPerMatch(f, bd)
            val list = bd.facts.mapIndexed { gi, facts ->
                val aFirst = LeagueMatch.deal(f, bi, gi, per).aFirst
                val g = bd.games[gi]
                games += GameCurve(f.comp, f.id, f.label, bd.a, bd.b, bi, gi, aFirst, g.a, g.b, n, facts.curve, season)
                LiveGame(aFirst, g.a, g.b, facts.totals, facts.curve)
            }
            boards += LiveBoardGames(bd.a, bd.b, list)
        }
        if (boards.isEmpty()) return
        seq++
        kept.addFirst(LiveFixture(n, f, nowMs, r.winner, boards, r.format))
        while (kept.size > FIXTURES) kept.removeLast()
        for (g in games) feed.addFirst(g)
        while (feed.size > CURVES) feed.removeLast()
    }

    /** The curves of the newest board between [a] and [b] (either side), in game order; empty when none is kept. */
    fun curves(a: String, b: String): List<GameCurve> {
        val last = feed.firstOrNull { (it.a == a && it.b == b) || (it.a == b && it.b == a) } ?: return emptyList()
        return feed.filter { it.fixtureSeq == last.fixtureSeq && it.board == last.board }.sortedBy { it.game }
    }

    /**
     * The curves of [ref]'s board (board -1: the first) in game order, from the newest time it was
     * played (a fixture played again after a kill); empty when none is kept. A curve kept without
     * its season (0) matches any season.
     */
    fun curveOf(ref: MatchRef): List<GameCurve> {
        val board = maxOf(0, ref.board)
        fun mine(c: GameCurve) = c.comp == ref.comp && c.fixtureId == ref.id && c.board == board && (c.season == 0 || c.season == ref.season)
        val last = feed.firstOrNull(::mine) ?: return emptyList()
        return feed.filter { it.fixtureSeq == last.fixtureSeq && mine(it) }.sortedBy { it.game }
    }

    /**
     * What the board shows at [nowMs] for a viewing that started at [startMs] (a new start
     * empties the slots): up to [SLOTS] fixtures, each at the half-turn its elapsed time implies,
     * [stepMs] per half-turn, games one after the other and a team match's boards side by side.
     * A slot whose replay is over (and held for [HOLD_MS]) takes the newest fixture not shown
     * yet; with none, it keeps its final result. Call it on every frame of the screen.
     */
    fun window(nowMs: Long, startMs: Long, stepMs: Long = STEP_MS): List<LiveFrame> {
        if (startMs != session) {
            session = startMs
            slots.fill(null)
            shown.clear()
        }
        val step = maxOf(1L, stepMs)
        for (s in slots.indices) {
            val cur = slots[s]
            if (cur != null && nowMs < cur.begin + cur.fixture.steps * step + HOLD_MS) continue
            val next = kept.firstOrNull { it.seq !in shown } ?: continue
            slots[s] = Show(next, nowMs)
            shown += next.seq
        }
        // Fixtures that dropped out can never be picked again: forget them (bounded).
        if (shown.size > 2 * FIXTURES) shown.retainAll(kept.mapTo(HashSet()) { it.seq })
        return slots.withIndex().mapNotNull { (s, show) -> show?.let { frame(s, it, nowMs, step) } }
    }

    private fun frame(slot: Int, show: Show, nowMs: Long, step: Long): LiveFrame {
        val fx = show.fixture
        val t = ((nowMs - show.begin) / step).coerceIn(0L, fx.steps.toLong()).toInt()
        val boards = fx.boards.map { bd ->
            val n = bd.games.size
            val done = t >= n * GAME_STEPS
            val g = if (done) n - 1 else t / GAME_STEPS
            val h = if (done) bd.games[g].halfTurns else t % GAME_STEPS
            val before = if (done) bd.games else bd.games.subList(0, g)
            val game = bd.games[g]
            LiveBoardFrame(
                bd.a, bd.b, g, n, h, game.totalA(h), game.totalB(h), game.pA(h),
                before.sumOf { won(it.scoreA, it.scoreB) }, before.sumOf { won(it.scoreB, it.scoreA) }, done,
            )
        }
        val finished = t >= fx.steps
        val (sa, sb) = if (fx.fixture.kind == JobKind.TEAM) {
            // Boards count once they are over: a won board 1, a level one half.
            var x = 0.0
            var y = 0.0
            for ((i, bf) in boards.withIndex()) {
                if (!bf.done) continue
                when (TieRule.match(fx.boards[i].games.map { GameScore(it.scoreA, it.scoreB) }, fx.format)) {
                    1 -> x += 1.0
                    -1 -> y += 1.0
                    else -> {
                        x += 0.5
                        y += 0.5
                    }
                }
            }
            x to y
        } else {
            boards.sumOf { it.wonA } to boards.sumOf { it.wonB }
        }
        val progress = if (fx.steps == 0) 1.0 else t.toDouble() / fx.steps
        return LiveFrame(slot, fx.fixture, boards, sa, sb, finished, if (finished) fx.winner else null, progress)
    }

    private fun won(x: Int, y: Int) = if (x > y) 1.0 else if (x == y) 0.5 else 0.0

    companion object {
        const val FIXTURES = 40
        const val CURVES = 60
        const val SLOTS = 6

        /** Replay speed: one half-turn per this many ms (a mirrored match takes about 12 s). */
        const val STEP_MS = 250L

        /** How long a finished replay keeps its final result before the slot moves on. */
        const val HOLD_MS = 4000L

        /** Steps per game: the empty board, then each of the 24 half-turns. */
        const val GAME_STEPS = 2 * Category.COUNT + 1
    }
}

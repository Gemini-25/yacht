package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random

/**
 * A played club fixture seen from side A: [winner] +1 / 0 / -1 (by [TieRule.team]), boards
 * won, lost and level, and both sides' points over all boards.
 */
data class ClubResult(val winner: Int, val won: Int, val lost: Int, val level: Int, val scoreA: Int, val scoreB: Int)

/** A club league fixture: club [a] (side A) against club [b] in round [round] (0-based); [result] once played. */
data class ClubMatch(val id: Int, val round: Int, val a: String, val b: String, val result: ClubResult? = null)

/** A club's line in the club league table. A level board is half a board for each side, so the difference stays whole. */
data class ClubRow(
    val club: String,
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    /** 3 for a win, 1 for a draw. */
    val points: Int,
    val boardsWon: Int,
    val boardsLost: Int,
    val boardsLevel: Int,
    /** Game points over all boards, and the opponents'. */
    val scored: Int,
    val conceded: Int,
) {
    val boardDiff: Int get() = boardsWon - boardsLost
}

/**
 * The club league of a season (docs/LEAGUE_SPEC.md section 2.2): a single round robin of the
 * clubs, one [JobKind.TEAM] fixture per pairing with each club's [lineups] (its best rated
 * members at the season start, board 1 strongest). Round k opens at league progress
 * `k / rounds * 0.95`. Table: 3/1/0, then board difference. The last result recorded makes the
 * news `title` [champion's club id] ("K"); honours "CLUB" and "CLUB2" are keyed by club id.
 * Not thread-safe: callers synchronise.
 */
class ClubLeague private constructor(
    val season: Int,
    val seed: Long,
    /** Each club's lineup for the season, board 1 (strongest) first, by club id. */
    val lineups: Map<String, List<String>>,
    private val fixtures: List<ClubMatch>,
    /** Rounds of the round robin. */
    val rounds: Int,
) : Competition {
    override val comp: Comp get() = Comp.CLUB

    private val byId = fixtures.associateBy { it.id }
    private val results = HashMap<Int, ClubResult>()
    private val taken = HashSet<Int>()

    override val finished: Boolean get() = results.size >= fixtures.size
    override val fixturesTotal: Int get() = fixtures.size
    override val fixturesLeft: Int get() = fixtures.size - results.size

    /** Rounds open at league progress [p]. */
    fun openRounds(p: Double): Int = (0 until rounds).count { k -> k.toDouble() / rounds * OPEN_SPAN <= p + 1e-9 }

    override fun next(p: Double, busy: Set<String>): Fixture? {
        val open = openRounds(p)
        for (f in fixtures) {
            if (f.round >= open) break
            if (f.id in results || f.id in taken || blocked(f, busy)) continue
            taken += f.id
            return job(f)
        }
        return null
    }

    /** Boards of a fixture between lineups [la] and [lb]: the shorter one's size, at most [ClubBook.BOARDS]. */
    private fun boards(la: List<String>, lb: List<String>) = minOf(ClubBook.BOARDS, la.size, lb.size)

    /** True when an AI of [f]'s boards is in [busy] (checked without building the job: [next] runs per job). */
    private fun blocked(f: ClubMatch, busy: Set<String>): Boolean {
        if (busy.isEmpty()) return false
        val la = lineups[f.a].orEmpty()
        val lb = lineups[f.b].orEmpty()
        for (i in 0 until boards(la, lb)) if (la[i] in busy || lb[i] in busy) return true
        return false
    }

    /** The fixture as a job: boards for the shorter lineup (at most [ClubBook.BOARDS]). */
    fun job(f: ClubMatch): Fixture {
        val la = lineups[f.a].orEmpty()
        val lb = lineups[f.b].orEmpty()
        val n = boards(la, lb)
        return Fixture(
            Comp.CLUB, f.id, f.a, f.b, DiceStream.mix(seed, Comp.CLUB.code.code.toLong(), f.id.toLong()), JobKind.TEAM,
            lineupA = la.take(n), lineupB = lb.take(n), rated = true, label = "${f.round + 1}라운드",
        )
    }

    override fun release(f: Fixture) {
        taken.remove(f.id)
    }

    override fun record(r: JobResult): List<LeagueEvent> {
        val f = byId[r.fixture.id] ?: return emptyList()
        if (f.id in results || r.fixture.a != f.a || r.fixture.b != f.b) return emptyList()
        taken.remove(f.id)
        var won = 0
        var lost = 0
        var level = 0
        for (bd in r.boards) {
            when (TieRule.match(bd.games)) {
                1 -> won++
                -1 -> lost++
                else -> level++
            }
        }
        results[f.id] = ClubResult(r.winner.coerceIn(-1, 1), won, lost, level, r.boards.sumOf { it.totalA }, r.boards.sumOf { it.totalB })
        if (!finished) return emptyList()
        val champion = order().firstOrNull() ?: return emptyList()
        return listOf(LeagueEvent("title", season, listOf(champion), listOf(comp.code.toString()), comp))
    }

    /** Every fixture in round order, with its result once played. */
    fun matches(): List<ClubMatch> = fixtures.map { f -> results[f.id]?.let { f.copy(result = it) } ?: f }

    private class Line(val club: String) {
        var played = 0
        var wins = 0
        var draws = 0
        var losses = 0
        var won = 0
        var lost = 0
        var level = 0
        var scored = 0
        var conceded = 0

        fun add(winner: Int, won: Int, lost: Int, level: Int, scored: Int, conceded: Int) {
            played++
            when (winner) {
                1 -> wins++
                -1 -> losses++
                else -> draws++
            }
            this.won += won
            this.lost += lost
            this.level += level
            this.scored += scored
            this.conceded += conceded
        }

        fun row() = ClubRow(club, played, wins, draws, losses, 3 * wins + draws, won, lost, level, scored, conceded)
    }

    /** The table, best first: points, board difference, boards won, point difference, then club id. */
    fun table(): List<ClubRow> {
        val lines = LinkedHashMap<String, Line>()
        for (c in lineups.keys) lines[c] = Line(c)
        for (f in fixtures) {
            val r = results[f.id] ?: continue
            lines.getOrPut(f.a) { Line(f.a) }.add(r.winner, r.won, r.lost, r.level, r.scoreA, r.scoreB)
            lines.getOrPut(f.b) { Line(f.b) }.add(-r.winner, r.lost, r.won, r.level, r.scoreB, r.scoreA)
        }
        return lines.values.map { it.row() }.sortedWith(
            compareByDescending<ClubRow> { it.points }.thenByDescending { it.boardDiff }.thenByDescending { it.boardsWon }
                .thenByDescending { it.scored - it.conceded }.thenBy { it.club },
        )
    }

    /** Club ids in table order (the final order once [finished]), for [ClubBook.offseason]. */
    fun order(): List<String> = table().map { it.club }

    /** "CLUB" (champion) and "CLUB2" (runner-up), keyed by club id, once finished. */
    override fun honours(): List<Honour> {
        if (!finished) return emptyList()
        val o = order()
        return listOfNotNull(o.getOrNull(0)?.let { Honour(season, "CLUB", it) }, o.getOrNull(1)?.let { Honour(season, "CLUB2", it) })
    }

    /** Lineups and fixtures; fixtures in progress are written as unplayed (played again after a restart). */
    override fun encode(out: StringBuilder) {
        out.append("$HEADER\n")
        out.append("season $season $seed $rounds\n")
        for ((c, l) in lineups) out.append("lineup $c ${l.joinToString(",").ifEmpty { "-" }}\n")
        for (f in fixtures) {
            out.append("fx ${f.id} ${f.round} ${f.a} ${f.b}")
            val r = results[f.id]
            if (r == null) out.append(" -") else out.append(" ${r.winner} ${r.won} ${r.lost} ${r.level} ${r.scoreA} ${r.scoreB}")
            out.append('\n')
        }
    }

    companion object {
        const val HEADER = "clubleague 1"

        /** The last round opens at this share of the league (spread over the season). */
        const val OPEN_SPAN = 0.95

        /** The best rated of [squad], strongest first, at most [ClubBook.BOARDS]. */
        fun lineup(squad: List<String>, rating: (String) -> Double): List<String> =
            squad.distinct().sortedWith(ClubBook.ratingOrder(rating)).take(ClubBook.BOARDS)

        /**
         * A new club league for season [number] with deal seed [seed]: a single round robin of
         * the clubs of [lineups] (club id to lineup, board 1 first), drawn from [seed].
         */
        fun start(number: Int, seed: Long, lineups: Map<String, List<String>>): ClubLeague {
            val ids = lineups.keys.toList()
            val rounds = Calendar.roundRobin(ids, Random(DiceStream.mix(seed, Comp.CLUB.code.code.toLong(), -1L)))
            val fixtures = ArrayList<ClubMatch>()
            for ((k, round) in rounds.withIndex()) for ((a, b) in round) fixtures += ClubMatch(fixtures.size, k, a, b)
            return ClubLeague(number, seed, LinkedHashMap(lineups), fixtures, rounds.size)
        }

        /**
         * The season factory: the clubs of [book] with at least one member in the league this
         * season, each fielding its [lineup] by the season-start ratings. Null when
         * [LeagueConfig.clubs] is 0 or fewer than 2 clubs can play. When [book] gives null and
         * [create] is set, [create] makes the book first (the first season with clubs; the app
         * keeps what it returns).
         */
        fun factory(book: () -> ClubBook?, create: ((SeasonStart) -> ClubBook?)? = null): (SeasonStart) -> Competition? = f@{ s ->
            if (s.config.clubs == 0) return@f null
            val b = book() ?: create?.invoke(s) ?: return@f null
            val alive = s.ranking.toHashSet()
            val lineups = LinkedHashMap<String, List<String>>()
            for (c in b.clubs) {
                val l = lineup(c.squad.filter { it in alive }, s.rating)
                if (l.isNotEmpty()) lineups[c.id] = l
            }
            if (lineups.size < 2) null else start(s.number, s.seed, lineups)
        }

        /**
         * Reads a club league back from its season.txt lines (skipping lines it cannot read).
         * Fixtures still to play whose club lost its lineup are dropped, so the competition can
         * always finish. Throws when nothing usable is left.
         */
        fun decode(lines: List<String>, season: Int, seed: Long): ClubLeague {
            require(lines.firstOrNull()?.trim() == HEADER) { "not a club league" }
            var number = season
            var s = seed
            var rounds = 0
            val lineups = LinkedHashMap<String, List<String>>()
            val fixtures = ArrayList<ClubMatch>()
            val results = HashMap<Int, ClubResult>()
            for (l in lines.drop(1)) {
                runCatching {
                    val f = l.trim().split(' ')
                    when (f[0]) {
                        "season" -> {
                            // All three or none: a cut line keeps the values from the plan.
                            val r = f[3].toInt()
                            number = f[1].toInt()
                            s = f[2].toLong()
                            rounds = r
                        }
                        "lineup" -> lineups[f[1]] = f[2].split(',').filter { it.isNotEmpty() && it != "-" }.distinct()
                        "fx" -> {
                            val m = ClubMatch(f[1].toInt(), f[2].toInt(), f[3], f[4])
                            if (f[5] != "-") results[m.id] = ClubResult(f[5].toInt().coerceIn(-1, 1), f[6].toInt(), f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt())
                            fixtures += m
                        }
                    }
                }
            }
            val kept = fixtures.distinctBy { it.id }.filter { it.id in results || (lineups[it.a].orEmpty().isNotEmpty() && lineups[it.b].orEmpty().isNotEmpty()) }
                .sortedWith(compareBy<ClubMatch> { it.round }.thenBy { it.id })
            require(kept.isNotEmpty()) { "no club fixtures" }
            val c = ClubLeague(number, s, lineups, kept, maxOf(rounds, kept.maxOf { it.round } + 1))
            for (f in kept) results[f.id]?.let { c.results[f.id] = it }
            return c
        }

        /** The codec for [SeasonPlan.decode]. */
        val codec = CompetitionCodec { lines, season, seed -> decode(lines, season, seed) }
    }
}

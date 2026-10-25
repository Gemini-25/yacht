package yacht.engine.league

import java.util.Random

/** One team's line in a division table. */
class Standing(val key: String) {
    var played = 0
    var wins = 0
    var draws = 0
    var losses = 0

    /** League points: 3 for a won match, 1 for a drawn one. */
    var points = 0

    /** Total of its game scores, and of its opponents'. */
    var scored = 0
    var conceded = 0
    var yachts = 0
    val diff: Int get() = scored - conceded
    val avgScore: Double get() = if (played == 0) 0.0 else scored / (2.0 * played)
}

/** How a season ended: champions, awards and who moved between divisions. */
data class SeasonSummary(
    val number: Int,
    /** Champion of each division, top division first. */
    val champions: List<String>,
    /** Highest average score per game (at least half the fixtures played). */
    val topScorer: String?,
    val topScore: Double,
    /** Most Yachts in the season. */
    val yachtKing: String?,
    val yachts: Int,
    /** (key, from division, to division), 0 = top division. */
    val moves: List<Triple<String, Int, Int>>,
)

/**
 * The season structure on top of the rating ladder: divisions that each play a single round
 * robin of matches (two games on the same dice, seats swapped). A match won 2-0 (or 1.5-0.5)
 * gives 3 points; at one game each the higher two-game total wins it (most close matches split
 * the games, since each side gets the same dice once); only equal totals are a draw, 1 each. When every fixture is played the bottom [SWAP] of each
 * division swap places with the top [SWAP] of the one below, and newcomers start at the bottom.
 * Not thread-safe: callers synchronise.
 */
class Season private constructor(
    var number: Int,
    val divisions: MutableList<MutableList<String>>,
) {
    val standings = HashMap<String, Standing>()
    private val remaining = ArrayList<Pair<String, String>>()
    private val inProgress = HashSet<Pair<String, String>>()
    val history = ArrayList<SeasonSummary>()

    /** AIs waiting for the next season (they start in the lowest division). */
    val newcomers = LinkedHashSet<String>()

    val fixturesLeft: Int get() = remaining.size + inProgress.size
    var fixturesTotal = 0
        private set

    fun divisionOf(key: String): Int? = divisions.indexOfFirst { key in it }.takeIf { it >= 0 }

    private fun startFixtures() {
        standings.clear()
        remaining.clear()
        inProgress.clear()
        for (d in divisions) {
            for (k in d) standings[k] = Standing(k)
            for (i in d.indices) for (j in i + 1 until d.size) remaining += d[i] to d[j]
        }
        fixturesTotal = remaining.size
    }

    /** Division table, best first: points, then score difference, then score. */
    fun table(division: Int): List<Standing> =
        divisions[division].map { standings.getValue(it) }
            .sortedWith(compareByDescending<Standing> { it.points }.thenByDescending { it.diff }.thenByDescending { it.scored })

    /**
     * The next fixture whose teams are both free, preferring teams that have played the least;
     * null when none is available right now.
     */
    fun next(rng: Random, busy: Set<String>): Pair<String, String>? {
        var best: Pair<String, String>? = null
        var bestPlayed = Int.MAX_VALUE
        val start = if (remaining.isEmpty()) 0 else rng.nextInt(remaining.size)
        for (k in remaining.indices) {
            val f = remaining[(start + k) % remaining.size]
            if (f.first in busy || f.second in busy) continue
            val p = standings.getValue(f.first).played + standings.getValue(f.second).played
            if (p < bestPlayed) {
                bestPlayed = p
                best = f
            }
        }
        best?.let {
            remaining.remove(it)
            inProgress += it
        }
        return best
    }

    /** A fixture taken by [next] that could not be played (stopped): back in the list. */
    fun release(f: Pair<String, String>) {
        if (inProgress.remove(f)) remaining += f
    }

    /** Records the fixture [f] (its first team is "a" in [games]). */
    fun record(f: Pair<String, String>, games: List<GameScore>) {
        if (!inProgress.remove(f)) return
        val a = standings[f.first] ?: return
        val b = standings[f.second] ?: return
        var aw = 0.0
        for (g in games) {
            aw += if (g.a > g.b) 1.0 else if (g.a == g.b) 0.5 else 0.0
            a.scored += g.a
            a.conceded += g.b
            b.scored += g.b
            b.conceded += g.a
            if (g.aYacht) a.yachts++
            if (g.bYacht) b.yachts++
        }
        val bw = games.size - aw
        val aTotal = games.sumOf { it.a }
        val bTotal = games.sumOf { it.b }
        a.played++
        b.played++
        when {
            aw > bw || (aw == bw && aTotal > bTotal) -> {
                a.wins++
                b.losses++
                a.points += 3
            }
            bw > aw || (aw == bw && bTotal > aTotal) -> {
                b.wins++
                a.losses++
                b.points += 3
            }
            else -> {
                a.draws++
                b.draws++
                a.points++
                b.points++
            }
        }
    }

    val finished: Boolean get() = remaining.isEmpty() && inProgress.isEmpty()

    /**
     * Ends the season (all fixtures played): awards, promotion and relegation, newcomers into the
     * lowest division, and the next season's fixtures. Returns the summary.
     */
    fun finish(): SeasonSummary {
        val tables = divisions.indices.map { table(it) }
        val all = standings.values.filter { it.played > 0 }
        val minPlayed = (divisions.minOfOrNull { it.size } ?: 2) / 2
        val scorer = all.filter { it.played >= minPlayed }.maxByOrNull { it.avgScore }
        val yachtKing = all.maxByOrNull { it.yachts }?.takeIf { it.yachts > 0 }
        val moves = ArrayList<Triple<String, Int, Int>>()
        val next = divisions.map { it.toMutableList() }.toMutableList()
        for (d in 0 until divisions.size - 1) {
            val swap = minOf(SWAP, tables[d].size / 3, tables[d + 1].size / 3)
            val down = tables[d].takeLast(swap).map { it.key }
            val up = tables[d + 1].take(swap).map { it.key }
            for (k in down) {
                next[d].remove(k)
                next[d + 1].add(k)
                moves += Triple(k, d, d + 1)
            }
            for (k in up) {
                next[d + 1].remove(k)
                next[d].add(k)
                moves += Triple(k, d + 1, d)
            }
        }
        for (k in newcomers) if (next.none { k in it }) next.last().add(k)
        newcomers.clear()
        val summary = SeasonSummary(
            number, tables.map { it.first().key }, scorer?.key, scorer?.avgScore ?: 0.0, yachtKing?.key, yachtKing?.yachts ?: 0, moves,
        )
        history += summary
        divisions.clear()
        divisions.addAll(next)
        number++
        startFixtures()
        return summary
    }

    /** Keeps the season in step with the roster: removed AIs leave, new ones wait for next season. */
    fun sync(keys: Collection<String>) {
        val known = keys.toSet()
        for (d in divisions) {
            val gone = d.filter { it !in known }
            if (gone.isEmpty()) continue
            d.removeAll(gone.toSet())
            for (g in gone) standings.remove(g)
            remaining.removeAll { it.first in gone || it.second in gone }
            inProgress.removeAll { it.first in gone || it.second in gone }
        }
        newcomers.retainAll(known)
        for (k in keys) if (k != Ladder.ME && divisionOf(k) == null) newcomers += k
    }

    // ---- saving --------------------------------------------------------------------------------

    fun encode(): String = buildString {
        append("season 1\n")
        append("number $number $fixturesTotal\n")
        for ((i, d) in divisions.withIndex()) append("division $i ${d.joinToString(",")}\n")
        for (s in standings.values) {
            append("standing ${s.key} ${s.played} ${s.wins} ${s.draws} ${s.losses} ${s.points} ${s.scored} ${s.conceded} ${s.yachts}\n")
        }
        // Fixtures in progress were not played: they are saved as still to play.
        for (f in remaining + inProgress) append("fixture ${f.first} ${f.second}\n")
        if (newcomers.isNotEmpty()) append("newcomers ${newcomers.joinToString(",")}\n")
        for (h in history) {
            val moves = h.moves.joinToString(";") { "${it.first}:${it.second}:${it.third}" }.ifEmpty { "-" }
            append(
                "summary ${h.number} ${h.champions.joinToString(",")} ${h.topScorer ?: "-"} ${h.topScore} " +
                    "${h.yachtKing ?: "-"} ${h.yachts} $moves\n",
            )
        }
    }

    companion object {
        /** Teams moving up and down between neighbouring divisions each season. */
        const val SWAP = 3

        /** Divisions of a league of this many AIs (about 30-35 per division). */
        fun divisionCount(n: Int) = maxOf(1, minOf(4, (n + 17) / 34))

        /** The first season: divisions by the current ranking (best first). */
        fun start(ranking: List<String>): Season {
            val keys = ranking.filter { it != Ladder.ME }
            val count = divisionCount(keys.size)
            val size = (keys.size + count - 1) / count
            val divisions = keys.chunked(maxOf(1, size)).map { it.toMutableList() }.toMutableList()
            return Season(1, divisions).also { it.startFixtures() }
        }

        fun decode(text: String, keys: Collection<String>): Season {
            val lines = text.lines()
            var number = 1
            var total = 0
            val divisions = ArrayList<MutableList<String>>()
            for (l in lines) {
                val f = l.split(' ')
                if (f[0] == "number") {
                    number = f[1].toInt()
                    total = f.getOrNull(2)?.toInt() ?: 0
                }
                if (f[0] == "division") divisions += f.getOrElse(2) { "" }.split(',').filter { it.isNotEmpty() }.toMutableList()
            }
            // A file without divisions (cut off, or not a season) is not a season: start a new one.
            require(divisions.any { it.isNotEmpty() }) { "no divisions in the saved season" }
            divisions.removeAll { it.isEmpty() }
            val s = Season(number, divisions)
            s.fixturesTotal = total
            for (d in divisions) for (k in d) s.standings[k] = Standing(k)
            for (l in lines) {
                val f = l.split(' ')
                when (f[0]) {
                    "standing" -> s.standings[f[1]]?.apply {
                        played = f[2].toInt()
                        wins = f[3].toInt()
                        draws = f[4].toInt()
                        losses = f[5].toInt()
                        points = f[6].toInt()
                        scored = f[7].toInt()
                        conceded = f[8].toInt()
                        yachts = f[9].toInt()
                    }
                    "fixture" -> if (f[1] in s.standings && f[2] in s.standings) s.remaining += f[1] to f[2]
                    "newcomers" -> f[1].split(',').filter { it.isNotEmpty() }.forEach { s.newcomers += it }
                    "summary" -> {
                        val moves = if (f[7] == "-") emptyList() else f[7].split(';').map { m ->
                            val p = m.split(':')
                            Triple(p[0], p[1].toInt(), p[2].toInt())
                        }
                        s.history += SeasonSummary(
                            f[1].toInt(), f[2].split(','), f[3].takeIf { it != "-" }, f[4].toDouble(),
                            f[5].takeIf { it != "-" }, f[6].toInt(), moves,
                        )
                    }
                }
            }
            s.sync(keys)
            return s
        }
    }
}

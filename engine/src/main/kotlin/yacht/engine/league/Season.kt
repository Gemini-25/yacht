package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random

/** One AI's line in a division table. */
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

    /**
     * 운 보정 승점: 3 when its two-game total beat the opponent's on the same dice, 1 when
     * equal, whatever the games won.
     */
    var aggPoints = 0

    /** Games played (a match is one, two or three of them, by the match format). */
    var games = 0
    val diff: Int get() = scored - conceded
    val avgScore: Double get() = if (games == 0) 0.0 else scored.toDouble() / games
}

/** A league fixture: division [division], matchday [matchday], [a] first in the first game. */
data class LeagueFixture(val id: Int, val division: Int, val matchday: Int, val a: String, val b: String)

/**
 * The league stage of a season: divisions that each play a round robin of matches, by matchdays
 * (every AI plays at most once per matchday; matchday k of every division opens together). See
 * docs/LEAGUE_SPEC.md section 2.1. Promotion, relegation and playoffs are decided by the
 * [SeasonPlan]; this class keeps the tables. Not thread-safe: callers synchronise.
 */
class Season private constructor(
    val number: Int,
    /** Season seed: every fixture's deal comes from it. */
    val seed: Long,
    val divisions: List<List<String>>,
    val double: Boolean,
    /** Which tier each division is in: a regional pyramid's groups, else one division a tier. */
    val tiers: Tiers = Tiers.linear(divisions.size),
) {
    val standings = HashMap<String, Standing>()
    private val fixtures = ArrayList<LeagueFixture>()

    /** Each fixture's two standings (by id), so [next] reads no map. */
    private val standingA = ArrayList<Standing>()
    private val standingB = ArrayList<Standing>()
    private val remaining = java.util.TreeSet<Int>()
    private val inProgress = HashSet<Int>()

    /** The games of every played fixture (for the calendar and the odds), by fixture id. */
    private val results = HashMap<Int, List<GameScore>>()

    /** Positions after each completed matchday: positions[d][k][i] = place of divisions[d][i] (a division can hold hundreds). */
    val positions: List<MutableList<ShortArray>> = divisions.map { ArrayList() }
    private val matchdayLeft = HashMap<Pair<Int, Int>, Int>()

    val fixturesTotal: Int get() = fixtures.size
    val fixturesLeft: Int get() = remaining.size + inProgress.size
    val fixturesPlayed: Int get() = fixturesTotal - fixturesLeft
    val finished: Boolean get() = remaining.isEmpty() && inProgress.isEmpty()
    val progress: Double get() = if (fixturesTotal == 0) 1.0 else fixturesPlayed.toDouble() / fixturesTotal

    /** Matchdays of the longest division. */
    val matchdays: Int
        get() {
            // Fixtures are only ever added (when the season is built or read): counted once per size.
            if (matchdaysFor != fixtures.size) {
                matchdayCount = (fixtures.maxOfOrNull { it.matchday } ?: -1) + 1
                matchdaysFor = fixtures.size
            }
            return matchdayCount
        }
    private var matchdayCount = 0
    private var matchdaysFor = -1
    private var matchdayTotals = IntArray(0)
    private var totalsFor = -1

    /** The lowest matchday with a fixture still to play. */
    val currentMatchday: Int get() = (remaining.firstOrNull()?.let { fixtures[it].matchday } ?: matchdays).let { md ->
        minOf(md, inProgress.minOfOrNull { fixtures[it].matchday } ?: md)
    }

    private val divisionIndex = HashMap<String, Int>().apply { divisions.forEachIndexed { d, ks -> for (k in ks) put(k, d) } }

    fun divisionOf(key: String): Int? = divisionIndex[key]

    fun fixture(id: Int): LeagueFixture = fixtures[id]

    /** The fixture as a job ([comp] LEAGUE, its own deal). */
    fun job(f: LeagueFixture): Fixture =
        Fixture(Comp.LEAGUE, f.id, f.a, f.b, DiceStream.mix(seed, Comp.LEAGUE.code.code.toLong(), f.id.toLong()), label = "${f.matchday + 1}라운드", week = f.matchday + 1)

    /** Played and total fixtures of each matchday, of division [division] or of all (for the season calendar). */
    fun matchdayCounts(division: Int? = null): List<StageCount> {
        val n = matchdays
        if (division == null) {
            // From the per-division counts of fixtures left (no scan: the screen asks often).
            if (totalsFor != fixtures.size) {
                matchdayTotals = IntArray(n).also { t -> for (f in fixtures) t[f.matchday]++ }
                totalsFor = fixtures.size
            }
            return List(n) { k ->
                var left = 0
                for (d in divisions.indices) left += matchdayLeft[d to k] ?: 0
                StageCount((matchdayTotals[k] - left).coerceAtLeast(0), matchdayTotals[k])
            }
        }
        val played = IntArray(n)
        val total = IntArray(n)
        for (f in fixtures) {
            if (division != null && f.division != division) continue
            total[f.matchday]++
            if (isPlayed(f.id)) played[f.matchday]++
        }
        return List(n) { StageCount(played[it], total[it]) }
    }

    /** Fixture ids by matchday (built on first use; fixtures are only added while the season is built or read). */
    private var byDay: Array<IntArray> = emptyArray()
    private var byDayFor = -1

    /** Upcoming and played fixtures of a matchday (for the calendar). */
    fun matchday(k: Int): List<LeagueFixture> {
        if (byDayFor != fixtures.size) {
            val lists = List(matchdays) { ArrayList<Int>() }
            for (f in fixtures) lists[f.matchday] += f.id
            byDay = Array(lists.size) { lists[it].toIntArray() }
            byDayFor = fixtures.size
        }
        return byDay.getOrNull(k)?.map { fixtures[it] }.orEmpty()
    }

    private fun add(f: LeagueFixture) {
        fixtures += f
        standingA += standings.getValue(f.a)
        standingB += standings.getValue(f.b)
    }

    /** True when every fixture of matchday [k] is played (in every division). */
    fun dayDone(k: Int): Boolean = k in 0 until matchdays && divisions.indices.all { (matchdayLeft[it to k] ?: 0) == 0 }

    /** The 이 주의 선수 of a matchday: [key] beat [opponent] with the best average score of the week. */
    data class WeekPick(val matchday: Int, val key: String, val opponent: String, val average: Double, val best: Int, val division: Int)

    private val pickOrder: Comparator<WeekPick> = compareByDescending<WeekPick> { it.average }.thenByDescending { it.best }.thenBy { it.division }.thenBy { it.key }

    /**
     * 이 주의 선수 of each matchday in [days] (null for a day with no winner): of the players
     * who won their match that day (none of [excluded]), the best average score; level, the best
     * single game, then the higher division, then by key (all fixed by the results: the same
     * pick whenever it is asked). One pass over the fixtures.
     */
    fun playersOfWeek(days: IntRange, excluded: Set<String> = emptySet()): Map<Int, WeekPick> {
        val best = HashMap<Int, WeekPick>()
        for (f in fixtures) {
            if (f.matchday !in days) continue
            val games = results[f.id] ?: continue
            if (games.isEmpty()) continue
            val res = TieRule.match(games)
            if (res == 0) continue
            val (w, l) = if (res > 0) f.a to f.b else f.b to f.a
            if (w in excluded) continue
            val own = games.map { if (res > 0) it.a else it.b }
            val pick = WeekPick(f.matchday, w, l, own.average(), own.max(), f.division)
            val cur = best[f.matchday]
            if (cur == null || pickOrder.compare(pick, cur) < 0) best[f.matchday] = pick
        }
        return best
    }

    fun isPlayed(id: Int): Boolean = id !in remaining && id !in inProgress

    /** The games of fixture [id] once played (its [LeagueFixture.a] is "a"); null before. */
    fun result(id: Int): List<GameScore>? = results[id]

    /** Every fixture not played yet (in progress included), in id order. */
    fun unplayed(): List<LeagueFixture> = fixtures.filter { !isPlayed(it.id) }

    private fun build(rng: Random) {
        // Ids in matchday order (then division), so the open matchdays come first in [remaining].
        val rounds = divisions.map { keys -> if (double) Calendar.doubleRoundRobin(keys, rng) else Calendar.roundRobin(keys, rng) }
        var id = 0
        for (d in divisions.indices) for (k in divisions[d]) standings[k] = Standing(k)
        for (md in 0 until (rounds.maxOfOrNull { it.size } ?: 0)) {
            for ((d, rs) in rounds.withIndex()) {
                for ((a, b) in rs.getOrNull(md).orEmpty()) {
                    add(LeagueFixture(id, d, md, a, b))
                    remaining += id
                    id++
                }
            }
        }
        countMatchdays()
    }

    private fun countMatchdays() {
        matchdayLeft.clear()
        for (f in fixtures) if (!isPlayed(f.id)) matchdayLeft.merge(f.division to f.matchday, 1, Int::plus)
    }

    /** Division table, best first: points, then score difference, then score. */
    fun table(division: Int): List<Standing> =
        divisions[division].map { standings.getValue(it) }
            .sortedWith(compareByDescending<Standing> { it.points }.thenByDescending { it.diff }.thenByDescending { it.scored })

    /** The same division ranked by 운 보정 승점 (two-game totals on the same dice). */
    fun aggregateTable(division: Int): List<Standing> =
        divisions[division].map { standings.getValue(it) }
            .sortedWith(compareByDescending<Standing> { it.aggPoints }.thenByDescending { it.diff })

    /**
     * The next fixture whose two AIs are free, from the current matchday or the next one,
     * preferring the earlier matchday and AIs that have played less; [cost] (estimated ms per
     * game of a fixture) puts the heaviest first once 80% of the league is played, so the season
     * does not end waiting for one slow match. Null when none is available now; none after
     * matchday [lastMatchday].
     */
    fun next(busy: Set<String>, cost: ((LeagueFixture) -> Double)? = null, lastMatchday: Int = Int.MAX_VALUE): LeagueFixture? {
        if (remaining.isEmpty()) return null
        // The current matchday and the next (not past [lastMatchday]).
        val last = minOf(currentMatchday + 1, lastMatchday)
        val late = progress >= 0.8 && cost != null
        var best: LeagueFixture? = null
        var bestKey = Double.MAX_VALUE
        for (id in remaining) {
            val f = fixtures[id]
            if (f.matchday > last) break
            // The key first (no map read), the busy sides only for a better one.
            val key = if (late) -cost!!(f) else f.matchday * 10_000.0 + standingA[id].played + standingB[id].played
            if (key < bestKey && f.a !in busy && f.b !in busy) {
                bestKey = key
                best = f
            }
        }
        best?.let {
            remaining.remove(it.id)
            inProgress += it.id
        }
        return best
    }

    /** A fixture taken by [next] that was not played (stopped): back in the list. */
    fun release(id: Int) {
        if (inProgress.remove(id)) remaining += id
    }

    /**
     * Fixtures taken by [next] that nobody plays any more (only [playing] are in flight): back in
     * the list. Returns how many came back (none unless a taken fixture was lost).
     */
    fun reclaim(playing: Set<Int>): Int {
        val lost = inProgress.filter { it !in playing }
        for (id in lost) release(id)
        return lost.size
    }

    /** Records fixture [id] (its [LeagueFixture.a] is "a" in [games]); false if it was not taken. */
    fun record(id: Int, games: List<GameScore>): Boolean {
        if (!inProgress.remove(id)) return false
        val f = fixtures[id]
        val a = standings.getValue(f.a)
        val b = standings.getValue(f.b)
        results[id] = games
        for (g in games) {
            a.scored += g.a
            a.conceded += g.b
            b.scored += g.b
            b.conceded += g.a
            if (g.aYacht) a.yachts++
            if (g.bYacht) b.yachts++
        }
        a.played++
        b.played++
        a.games += games.size
        b.games += games.size
        when (TieRule.match(games)) {
            1 -> {
                a.wins++
                b.losses++
                a.points += 3
            }
            -1 -> {
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
        val agg = games.sumOf { it.a - it.b }
        when {
            agg > 0 -> a.aggPoints += 3
            agg < 0 -> b.aggPoints += 3
            else -> {
                a.aggPoints++
                b.aggPoints++
            }
        }
        val k = f.division to f.matchday
        val left = (matchdayLeft[k] ?: 1) - 1
        matchdayLeft[k] = left
        if (left == 0) {
            val rank = HashMap<String, Int>()
            for ((i, s) in table(f.division).withIndex()) rank[s.key] = i + 1
            val place = ShortArray(divisions[f.division].size)
            for ((i, key) in divisions[f.division].withIndex()) place[i] = (rank[key] ?: 0).toShort()
            positions[f.division] += place
        }
        return true
    }

    // ---- saving --------------------------------------------------------------------------------

    /**
     * The saved season (`season 4`): the divisions and the seed, from which [decode] builds the
     * fixtures again, and the results of the played ones only (a league of thousands has tens of
     * thousands of fixtures).
     */
    fun encode(): String = buildString {
        append(HEADER).append('\n')
        append("number $number $seed ${if (double) 1 else 0}\n")
        for ((i, d) in divisions.withIndex()) append("division $i ${d.joinToString(",")}\n")
        if (!tiers.linear) append(tiers.encode()).append('\n')
        append("fixtures ${fixtures.size}\n")
        for (f in fixtures) {
            if (!isPlayed(f.id)) continue
            append("r ").append(f.id)
            results[f.id]?.let { gs -> append(' ').append(gs.joinToString(";") { encodeGame(it) }) }
            append('\n')
        }
        for (s in standings.values) {
            append("standing ${s.key} ${s.played} ${s.wins} ${s.draws} ${s.losses} ${s.points} ${s.scored} ${s.conceded} ${s.yachts} ${s.aggPoints} ${s.games}\n")
        }
        for ((d, list) in positions.withIndex()) for ((k, p) in list.withIndex()) append("pos $d $k ${p.joinToString(",")}\n")
    }

    companion object {
        const val HEADER = "season 4"

        /** The version before: every fixture saved. */
        private const val OLD_HEADER = "season 3"

        /** "a:b:flags" with flags bits 1 aYacht, 2 bYacht, 4 aBonus, 8 bBonus. */
        fun encodeGame(g: GameScore): String {
            val flags = (if (g.aYacht) 1 else 0) or (if (g.bYacht) 2 else 0) or (if (g.aBonus) 4 else 0) or (if (g.bBonus) 8 else 0)
            return "${g.a}:${g.b}:$flags"
        }

        fun decodeGame(t: String): GameScore {
            val p = t.split(':')
            val flags = p.getOrNull(2)?.toInt() ?: 0
            return GameScore(p[0].toInt(), p[1].toInt(), flags and 1 != 0, flags and 2 != 0, flags and 4 != 0, flags and 8 != 0)
        }

        /**
         * A new season of the league stage with these [divisions] (best division first), in
         * [tiers] (null: one division a tier). Empty divisions are left out (and their tiers'
         * groups with them).
         */
        fun start(number: Int, seed: Long, divisions: List<List<String>>, double: Boolean = false, tiers: Tiers? = null): Season {
            val kept = divisions.indices.filter { divisions[it].isNotEmpty() }
            val t = tiers?.takeIf { it.size == divisions.size && kept.size == divisions.size }
                ?: tiers?.takeIf { it.size == divisions.size }?.let { all -> keptTiers(all, kept) }
            val s = Season(number, seed, kept.map { divisions[it] }, double, t ?: Tiers.linear(kept.size))
            s.build(Random(DiceStream.mix(seed, 0x5EA5)))
            return s
        }

        /** [all] without the divisions not in [kept]; null when a tier would be left empty (then linear). */
        private fun keptTiers(all: Tiers, kept: List<Int>): Tiers? = runCatching {
            val of = kept.map { all.tier(it) }
            // Tiers renumbered without gaps.
            val renumber = of.distinct().withIndex().associate { (i, t) -> t to i }
            Tiers(of.map { renumber.getValue(it) }.toIntArray(), kept.map { all.names[it] })
        }.getOrNull()

        /** Balanced divisions of [ranking] (best first). */
        fun split(ranking: List<String>, count: Int): List<List<String>> {
            val n = ranking.size
            val c = count.coerceIn(1, maxOf(1, n / 2))
            val out = ArrayList<List<String>>()
            var start = 0
            for (d in 0 until c) {
                val size = n / c + if (d < n % c) 1 else 0
                out += ranking.subList(start, start + size)
                start += size
            }
            return out
        }

        /**
         * Reads a saved season of this version; throws on older versions or unusable files (the
         * caller starts a new season). AIs no longer in [keys] keep their played results; their
         * fixtures still to play are dropped.
         */
        fun decode(text: String, keys: Collection<String>): Season {
            val lines = text.lines()
            val header = lines.firstOrNull()?.trim()
            require(header == HEADER || header == OLD_HEADER) { "not a season file" }
            val rebuilt = header == HEADER
            var number = 1
            var seed = 0L
            var double = false
            val divisions = ArrayList<MutableList<String>>()
            var tierLine: List<String>? = null
            for (l in lines) {
                val f = l.split(' ')
                when (f[0]) {
                    "number" -> {
                        number = f[1].toInt()
                        seed = f[2].toLong()
                        double = f.getOrNull(3) == "1"
                    }
                    "division" -> divisions += f.getOrElse(2) { "" }.split(',').filter { it.isNotEmpty() }.toMutableList()
                    "tiers" -> tierLine = f.drop(1)
                }
            }
            require(divisions.any { it.isNotEmpty() }) { "no divisions in the saved season" }
            val known = keys.toSet()
            val tiers = tierLine?.let { Tiers.decode(it, divisions.size) } ?: Tiers.linear(divisions.size)
            val s = Season(number, seed, divisions, double, tiers)
            if (rebuilt) {
                // The fixtures as the season built them; the played ones come off the list below.
                s.build(Random(DiceStream.mix(seed, 0x5EA5)))
                val saved = lines.firstOrNull { it.startsWith("fixtures ") }?.substringAfter(' ')?.trim()?.toIntOrNull()
                require(saved == null || saved == s.fixtures.size) { "the fixtures do not rebuild" }
                for (f in s.fixtures) if (f.a !in known || f.b !in known) s.remaining.remove(f.id)
            } else {
                for (d in divisions) for (k in d) s.standings[k] = Standing(k)
            }
            // Standings from before games were counted: counted again from the saved results.
            val noGames = HashSet<String>()
            for (l in lines) {
                val f = l.split(' ')
                when (f[0]) {
                    "r" -> if (rebuilt) {
                        val id = f[1].toInt()
                        require(id in s.fixtures.indices) { "a result of no fixture" }
                        s.remaining.remove(id)
                        f.getOrNull(2)?.let { r -> runCatching { r.split(';').map { decodeGame(it) } }.getOrNull()?.let { s.results[id] = it } }
                    }
                    "fx" -> if (!rebuilt) {
                        val fx = LeagueFixture(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4], f[5])
                        require(fx.id == s.fixtures.size) { "fixtures out of order" }
                        s.add(fx)
                        val gone = fx.a !in known || fx.b !in known
                        if (f[6] == "0" && !gone) s.remaining += fx.id
                        f.getOrNull(7)?.let { r -> runCatching { r.split(';').map { decodeGame(it) } }.getOrNull()?.let { s.results[fx.id] = it } }
                    }
                    "standing" -> s.standings[f[1]]?.apply {
                        played = f[2].toInt()
                        wins = f[3].toInt()
                        draws = f[4].toInt()
                        losses = f[5].toInt()
                        points = f[6].toInt()
                        scored = f[7].toInt()
                        conceded = f[8].toInt()
                        yachts = f[9].toInt()
                        aggPoints = f.getOrNull(10)?.toInt() ?: 0
                        games = f.getOrNull(11)?.toInt() ?: 0.also { noGames += key }
                    }
                    // Older files kept places as signed bytes: 128..255 came out negative.
                    "pos" -> {
                        val d = f[1].toInt()
                        if (d in s.positions.indices) s.positions[d] += f[3].split(',').map { v -> v.toInt().let { if (it < 0) it + 256 else it }.toShort() }.toShortArray()
                    }
                }
            }
            if (noGames.isNotEmpty()) {
                val counted = HashMap<String, Int>()
                val matches = HashMap<String, Int>()
                for ((id, games) in s.results) {
                    val fx = s.fixtures.getOrNull(id) ?: continue
                    for (k in listOf(fx.a, fx.b)) {
                        counted.merge(k, games.size, Int::plus)
                        matches.merge(k, 1, Int::plus)
                    }
                }
                // A match without saved games counts two (the format before others existed).
                for (k in noGames) s.standings[k]?.let { st -> st.games = (counted[k] ?: 0) + 2 * maxOf(0, st.played - (matches[k] ?: 0)) }
            }
            s.countMatchdays()
            return s
        }
    }
}

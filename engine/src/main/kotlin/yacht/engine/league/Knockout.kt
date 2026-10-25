package yacht.engine.league

import yacht.engine.DiceStream
import kotlin.math.roundToInt

/**
 * One entry of a draw: an AI key or a team id, its [seed] (1 = best) and its rating when the
 * draw was made (NaN when unknown). The seed decides who goes through a tie still level after
 * the deciders; the rating gap tells an upset.
 */
data class DrawEntry(val key: String, val seed: Int, val rating: Double) {
    /** "key seed rating" for the save files, '-' for an unknown rating. */
    fun encode(): String = "$key $seed ${if (rating.isNaN()) "-" else rating.roundToInt().toString()}"

    companion object {
        /** Reads [encode]'s three fields from [f], starting at [at]. */
        fun decode(f: List<String>, at: Int = 0): DrawEntry = DrawEntry(f[at], f[at + 1].toInt(), f[at + 2].toDoubleOrNull() ?: Double.NaN)
    }
}

/**
 * How a knockout tie, a group match or a team match ended, seen from side A. [scoreA] and
 * [scoreB] are games won (a level game counts half) for one-on-one jobs, and boards won (a level
 * board counts half) for team matches; [totalA]/[totalB] are the points over every game;
 * [winner] is +1 for A, -1 for B and 0 when level.
 */
data class TieScore(val scoreA: Double, val scoreB: Double, val totalA: Int, val totalB: Int, val winner: Int) {
    /** The score with the winner's side first, as "3-1" or "2.5-1.5". */
    fun text(): String = if (winner < 0) "${num(scoreB)}-${num(scoreA)}" else "${num(scoreA)}-${num(scoreB)}"

    companion object {
        /** The score of a finished job, from its boards; its [JobResult.winner] decides. */
        fun of(r: JobResult): TieScore {
            var a = 0.0
            var b = 0.0
            if (r.fixture.kind == JobKind.TEAM) {
                for (bd in r.boards) {
                    when (TieRule.match(bd.games)) {
                        1 -> a += 1.0
                        -1 -> b += 1.0
                        else -> {
                            a += 0.5
                            b += 0.5
                        }
                    }
                }
            } else {
                for (bd in r.boards) {
                    a += bd.gamesA
                    b += bd.games.size - bd.gamesA
                }
            }
            return TieScore(a, b, r.boards.sumOf { it.totalA }, r.boards.sumOf { it.totalB }, r.winner.coerceIn(-1, 1))
        }

        private fun num(x: Double): String = if (x == Math.floor(x)) x.toInt().toString() else x.toString()
    }

    /**
     * A result line of a competition's save: fixture [id] between [a] and [b] (as the fixture had
     * them). Scores are kept in halves so no decimals are written.
     */
    internal class Saved(val id: Int, val a: String, val b: String, val score: TieScore) {
        companion object {
            fun line(id: Int, a: String, b: String, s: TieScore): String =
                "r $id $a $b ${s.winner} ${(s.scoreA * 2).roundToInt()} ${(s.scoreB * 2).roundToInt()} ${s.totalA} ${s.totalB}\n"

            /** Every readable result line of [lines], in fixture id order (bad lines are skipped). */
            fun parse(lines: List<String>): List<Saved> = lines.mapNotNull { l ->
                runCatching {
                    val f = l.trim().split(' ')
                    if (f[0] != "r") return@runCatching null
                    Saved(f[1].toInt(), f[2], f[3], TieScore(f[5].toInt() / 2.0, f[6].toInt() / 2.0, f[7].toInt(), f[8].toInt(), f[4].toInt().coerceIn(-1, 1)))
                }.getOrNull()
            }.sortedBy { it.id }
        }
    }
}

/**
 * One tie of a [Knockout]. Once both sides are known, [a] is the better seed (it goes through a
 * tie still level after the deciders). A side missing from the draw makes a bye: the other side
 * goes on without a fixture.
 */
class KnockoutTie internal constructor(val id: Int, val round: Int, val slot: Int, val legs: Int) {
    var a: DrawEntry? = null
        internal set
    var b: DrawEntry? = null
        internal set

    /** Decided without a fixture (one side or both missing). */
    var bye = false
        internal set
    var result: TieScore? = null
        internal set
    var winner: DrawEntry? = null
        internal set

    /** Played or a bye: the winner has gone on to the next round. */
    var decided = false
        internal set

    val loser: DrawEntry? get() = if (result == null) null else if (winner == a) b else a

    /** Both sides known and not played yet. */
    val ready: Boolean get() = !decided && a != null && b != null

    // The winners of the two ties feeding this one, until both are known.
    internal var top: DrawEntry? = null
    internal var bottom: DrawEntry? = null
    internal var fed = 0
}

/**
 * A single-elimination bracket. Round 1 holds [first]'s pairings (a power of two of them, a null
 * side being a bye); the winner of tie s goes to tie s/2 of the next round. Round r opens at
 * league progress [opensAt] (everything is open at 1.0, so a bracket always finishes); a round's
 * ties are handed out as soon as both sides are known. Ids run from [idBase] round by round.
 */
class Knockout(
    first: List<Pair<DrawEntry?, DrawEntry?>>,
    legsOf: (round: Int) -> Int,
    private val labelOf: (round: Int) -> String,
    private val opensAt: (round: Int) -> Double,
    idBase: Int = 0,
) {
    val rounds: List<List<KnockoutTie>>
    private val byId = HashMap<Int, KnockoutTie>()
    private val taken = HashSet<Int>()

    init {
        require(first.isNotEmpty() && first.size and (first.size - 1) == 0) { "a bracket needs a power of two ties" }
        val list = ArrayList<List<KnockoutTie>>()
        var id = idBase
        var n = first.size
        while (true) {
            val r = list.size
            list += List(n) { s -> KnockoutTie(id++, r, s, legsOf(r)) }
            if (n == 1) break
            n /= 2
        }
        rounds = list
        for (round in list) for (t in round) byId[t.id] = t
        for ((s, p) in first.withIndex()) {
            feed(list[0][s], p.first, top = true)
            feed(list[0][s], p.second, top = false)
        }
    }

    val final: KnockoutTie get() = rounds.last().single()
    val finished: Boolean get() = final.decided
    val champion: DrawEntry? get() = if (finished) final.winner else null
    val runnerUp: DrawEntry? get() = final.loser

    /** Ties that are or will be played (byes are not fixtures). */
    val fixturesTotal: Int get() = rounds.sumOf { r -> r.count { !it.bye } }
    val fixturesLeft: Int get() = rounds.sumOf { r -> r.count { !it.bye && !it.decided } }

    fun label(round: Int): String = labelOf(round)

    fun tie(id: Int): KnockoutTie? = byId[id]

    /** True when [id] is one of this bracket's ties. */
    operator fun contains(id: Int): Boolean = id in byId

    /**
     * The first open tie (earliest round first) whose fixture, built by [make], keeps no AI of
     * [busy] waiting; it is then taken until [record] or [release].
     */
    fun next(p: Double, busy: Set<String>, make: (KnockoutTie) -> Fixture): Fixture? {
        for ((r, round) in rounds.withIndex()) {
            if (!opened(p, opensAt(r))) continue
            for (t in round) {
                if (!t.ready || t.id in taken) continue
                val f = make(t)
                if (f.players.any { it in busy }) continue
                taken += t.id
                return f
            }
        }
        return null
    }

    fun release(id: Int) {
        taken -= id
    }

    /**
     * Records tie [id] played between [a] and [b] (side A as the fixture had it). A level score
     * sends the better seed (side A) through. Null when the tie is not open or its sides differ
     * (a stale result): nothing changes then.
     */
    fun record(id: Int, a: String, b: String, score: TieScore): KnockoutTie? {
        val t = byId[id] ?: return null
        if (!t.ready || t.a?.key != a || t.b?.key != b) return null
        taken -= id
        t.result = score
        decide(t, if (score.winner < 0) t.b else t.a)
        return t
    }

    private fun feed(t: KnockoutTie, e: DrawEntry?, top: Boolean) {
        if (top) t.top = e else t.bottom = e
        t.fed++
        if (t.fed < 2) return
        val x = t.top
        val y = t.bottom
        if (x != null && y != null) {
            val xFirst = x.seed <= y.seed
            t.a = if (xFirst) x else y
            t.b = if (xFirst) y else x
        } else {
            t.a = x ?: y
            t.bye = true
            decide(t, t.a)
        }
    }

    private fun decide(t: KnockoutTie, w: DrawEntry?) {
        t.winner = w
        t.decided = true
        val next = t.round + 1
        if (next < rounds.size) feed(rounds[next][t.slot / 2], w, top = t.slot % 2 == 0)
    }

    /** Result lines of the played ties. */
    fun encode(out: StringBuilder) {
        for (round in rounds) for (t in round) t.result?.let { out.append(TieScore.Saved.line(t.id, t.a!!.key, t.b!!.key, it)) }
    }

    /** Puts saved results back, earliest round first; results that do not fit are skipped (played again). */
    internal fun restore(results: List<TieScore.Saved>) {
        for (r in results) if (r.id in byId) record(r.id, r.a, r.b, r.score)
    }

    /** The bracket for the screen; a side waiting for its opponent shows on its own. */
    fun view(): List<BracketRound> = rounds.mapIndexed { r, round ->
        BracketRound(
            labelOf(r),
            round.map { t ->
                val s = t.result
                TieView((t.a ?: t.top)?.key, (t.b ?: t.bottom)?.key, s?.scoreA ?: 0.0, s?.scoreB ?: 0.0, t.winner?.key, s != null, t.legs)
            },
        )
    }

    companion object {
        /** The deal of a competition's fixture: a pure function of the season, the competition and the id. */
        internal fun fixtureSeed(seasonSeed: Long, comp: Comp, id: Int): Long = DiceStream.mix(seasonSeed, comp.code.code.toLong(), id.toLong())

        /** Seed of a competition's draw (negative salt: never a fixture id). */
        internal fun drawSeed(seasonSeed: Long, comp: Comp): Long = DiceStream.mix(seasonSeed, comp.code.code.toLong(), -1L)

        /**
         * True once league progress [p] has reached [at], or the league is over. The slack keeps a
         * threshold such as 0.05 + 0.1 * 7 (a hair above 0.75 in floating point) open at 0.75.
         */
        internal fun opened(p: Double, at: Double): Boolean = p >= 1.0 || p + 1e-9 >= at

        /** The smallest power of two of at least [n]. */
        fun size(n: Int): Int {
            var s = 1
            while (s < n) s *= 2
            return s
        }

        /**
         * The seed at each position of a bracket of [size]: seeds 1 and 2 can meet only in the
         * final, 1-4 only from the semi-finals on, and so on ([1, 8, 4, 5, 2, 7, 3, 6] for 8).
         */
        fun seedOrder(size: Int): IntArray {
            var order = intArrayOf(1)
            while (order.size < size) {
                val n = order.size * 2
                order = order.flatMap { listOf(it, n + 1 - it) }.toIntArray()
            }
            return order
        }

        /**
         * First-round pairings of [entries] by their seeds in a bracket of the next power of two:
         * the seeds past the last entry are byes, so the best seeds get them.
         */
        fun pairings(entries: List<DrawEntry>): List<Pair<DrawEntry?, DrawEntry?>> {
            val bySeed = entries.associateBy { it.seed }
            val size = size(maxOf(2, entries.maxOfOrNull { it.seed } ?: 2))
            val order = seedOrder(size)
            return (0 until size / 2).map { s -> bySeed[order[2 * s]] to bySeed[order[2 * s + 1]] }
        }

        /** Empty rounds for a bracket not drawn yet ([ties] in the first round, halving). */
        fun placeholder(ties: Int, label: (Int) -> String, legs: (Int) -> Int): List<BracketRound> {
            val out = ArrayList<BracketRound>()
            var n = ties
            while (n >= 1) {
                val r = out.size
                out += BracketRound(label(r), List(n) { TieView(null, null, 0.0, 0.0, null, false, legs(r)) })
                n /= 2
            }
            return out
        }
    }
}

/**
 * A group stage: a round robin inside each group (double for the champions league), round k of
 * every group opening together at league progress [opensAt]. Tables: points 3/1/0, then the
 * difference (score points for one-on-one matches, boards for team matches, [teams]), then the
 * other difference, then the seed. Match ids run round by round from 0.
 */
class GroupStage(
    /** Members of each group, best seed first (the fixture list follows this order). */
    val groups: List<List<DrawEntry>>,
    double: Boolean,
    private val teams: Boolean,
    private val opensAt: (round: Int) -> Double,
) {
    /** A group match; [a] moves first in its first game (or is the home team). */
    class Match internal constructor(val id: Int, val group: Int, val round: Int, val a: String, val b: String) {
        var result: TieScore? = null
            internal set
    }

    /** A group table line. */
    class Row internal constructor(val entry: DrawEntry) {
        var played = 0
            internal set
        var wins = 0
            internal set
        var draws = 0
            internal set
        var losses = 0
            internal set
        var points = 0
            internal set

        /** Game points scored minus conceded. */
        var scoreDiff = 0
            internal set

        /** Games (or boards) won and lost, in halves. */
        var halvesFor = 0
            internal set
        var halvesAgainst = 0
            internal set

        /** Boards (or games) won minus lost; whole for team matches (a level board is half each). */
        val boardDiff: Int get() = (halvesFor - halvesAgainst) / 2
    }

    val matches: List<Match>
    private val byId: Map<Int, Match>
    private val taken = HashSet<Int>()

    /** Rounds of the largest group. */
    val rounds: Int

    init {
        val schedules = groups.map { g -> g.map { it.key }.let { if (double) Calendar.doubleRoundRobin(it) else Calendar.roundRobin(it) } }
        rounds = schedules.maxOfOrNull { it.size } ?: 0
        val list = ArrayList<Match>()
        for (k in 0 until rounds) {
            for ((g, s) in schedules.withIndex()) for ((a, b) in s.getOrNull(k).orEmpty()) list += Match(list.size, g, k, a, b)
        }
        matches = list
        byId = list.associateBy { it.id }
    }

    val finished: Boolean get() = matches.all { it.result != null }
    val fixturesTotal: Int get() = matches.size
    val fixturesLeft: Int get() = matches.count { it.result == null }

    operator fun contains(id: Int): Boolean = id in byId

    fun groupLabel(g: Int): String = "${'A' + g}조"

    fun matchLabel(m: Match): String = "${groupLabel(m.group)} ${m.round + 1}차전"

    fun entry(key: String): DrawEntry? = groups.firstNotNullOfOrNull { g -> g.firstOrNull { it.key == key } }

    /** The first open match (earliest round first) whose fixture keeps no AI of [busy] waiting. */
    fun next(p: Double, busy: Set<String>, make: (Match) -> Fixture): Fixture? {
        for (m in matches) {
            if (m.result != null || m.id in taken) continue
            if (!Knockout.opened(p, opensAt(m.round))) break
            val f = make(m)
            if (f.players.any { it in busy }) continue
            taken += m.id
            return f
        }
        return null
    }

    fun release(id: Int) {
        taken -= id
    }

    /** Records match [id] between [a] and [b]; null when it is not this stage's or already played. */
    fun record(id: Int, a: String, b: String, score: TieScore): Match? {
        val m = byId[id] ?: return null
        if (m.result != null || m.a != a || m.b != b) return null
        taken -= id
        m.result = score
        return m
    }

    /** Group [g]'s table, best first. */
    fun table(g: Int): List<Row> {
        val rows = groups[g].associate { it.key to Row(it) }
        for (m in matches) {
            if (m.group != g) continue
            val s = m.result ?: continue
            val x = rows[m.a] ?: continue
            val y = rows[m.b] ?: continue
            x.played++
            y.played++
            x.scoreDiff += s.totalA - s.totalB
            y.scoreDiff += s.totalB - s.totalA
            val ha = (s.scoreA * 2).roundToInt()
            val hb = (s.scoreB * 2).roundToInt()
            x.halvesFor += ha
            x.halvesAgainst += hb
            y.halvesFor += hb
            y.halvesAgainst += ha
            when (s.winner) {
                1 -> {
                    x.wins++
                    y.losses++
                }
                -1 -> {
                    y.wins++
                    x.losses++
                }
                else -> {
                    x.draws++
                    y.draws++
                }
            }
        }
        for (r in rows.values) r.points = TieRule.points(1) * r.wins + TieRule.points(0) * r.draws
        return rows.values.sortedWith(order)
    }

    /** The difference shown in the table: boards for team matches, score points otherwise. */
    fun diff(r: Row): Int = if (teams) r.boardDiff else r.scoreDiff

    private val order: Comparator<Row> = compareByDescending<Row> { it.points }
        .thenByDescending { diff(it) }
        .thenByDescending { if (teams) it.scoreDiff else it.halvesFor }
        .thenBy { it.entry.seed }

    fun view(g: Int): GroupTable =
        GroupTable(groupLabel(g), table(g).map { GroupRow(it.entry.key, it.played, it.wins, it.draws, it.losses, it.points, diff(it)) })

    /** Result lines of the played matches. */
    fun encode(out: StringBuilder) {
        for (m in matches) m.result?.let { out.append(TieScore.Saved.line(m.id, m.a, m.b, it)) }
    }
}

/** The news of finished ties (docs/LEAGUE_SPEC.md section 9). */
internal object KnockoutNews {
    /** A winner rated this much lower at the draw than its opponent makes an upset. */
    const val UPSET_GAP = 100.0

    /** `upset` [winner, loser] (rating gap at the draw), or null when it was no upset. */
    fun upset(season: Int, comp: Comp, w: DrawEntry, l: DrawEntry): LeagueEvent? {
        val gap = l.rating - w.rating
        return if (!gap.isNaN() && gap >= UPSET_GAP) LeagueEvent("upset", season, listOf(w.key, l.key), listOf(gap.roundToInt().toString()), comp) else null
    }

    /** True when the winner was rated [UPSET_GAP] or more below the loser (a giant killing). */
    fun giantKilling(w: DrawEntry, l: DrawEntry): Boolean = l.rating - w.rating >= UPSET_GAP

    /** `cup_round` [winner, loser] (round label, score). */
    fun round(season: Int, comp: Comp, t: KnockoutTie, label: String): LeagueEvent =
        LeagueEvent("cup_round", season, listOf(t.winner!!.key, t.loser!!.key), listOf(label, t.result!!.text()), comp)

    /** `final` [winner, runner-up] (score), and `title` [winner] ([title]) when given. */
    fun final(season: Int, comp: Comp, t: KnockoutTie, title: String?): List<LeagueEvent> {
        val w = t.winner!!.key
        val out = arrayListOf(LeagueEvent("final", season, listOf(w, t.loser!!.key), listOf(t.result!!.text()), comp))
        if (title != null) out += LeagueEvent("title", season, listOf(w), listOf(title), comp)
        return out
    }
}

/**
 * A competition that is one knockout bracket of AIs (the cup, the super cup, the title playoff):
 * fixtures, results, saving and the view are shared; subclasses build the bracket from
 * [entries] and say what is news. Saved as a header line, one "e" line per entry and the result
 * lines; the bracket is drawn again from the entries on decode.
 */
abstract class BracketCompetition(
    final override val comp: Comp,
    val season: Int,
    protected val seed: Long,
    /** The draw, best seed first. */
    val entries: List<DrawEntry>,
) : ViewableCompetition {
    /** The bracket, built by the subclass from [entries]. */
    protected abstract val bracket: Knockout

    /** First line of the saved section, e.g. "cup 1". */
    protected abstract val header: String

    /** News of a finished tie. */
    protected abstract fun news(t: KnockoutTie): List<LeagueEvent>

    protected open fun fixture(t: KnockoutTie): Fixture =
        Fixture(comp, t.id, t.a!!.key, t.b!!.key, Knockout.fixtureSeed(seed, comp, t.id), JobKind.TIE, legs = t.legs, label = bracket.label(t.round))

    override fun next(p: Double, busy: Set<String>): Fixture? = bracket.next(p, busy, ::fixture)

    override fun release(f: Fixture) = bracket.release(f.id)

    override fun record(r: JobResult): List<LeagueEvent> {
        val f = r.fixture
        val t = bracket.record(f.id, f.a, f.b, TieScore.of(r)) ?: return emptyList()
        return news(t)
    }

    override val finished: Boolean get() = bracket.finished
    override val fixturesLeft: Int get() = bracket.fixturesLeft
    override val fixturesTotal: Int get() = bracket.fixturesTotal

    /** The winner once the final is played. */
    val champion: String? get() = bracket.champion?.key
    val runnerUp: String? get() = bracket.runnerUp?.key

    override fun encode(out: StringBuilder) {
        out.append(header).append('\n')
        for (e in entries) out.append("e ").append(e.encode()).append('\n')
        bracket.encode(out)
    }

    override fun view(): CompetitionView = CompetitionView.Bracket(comp.label, bracket.view())

    internal fun restore(results: List<TieScore.Saved>) = bracket.restore(results)

    companion object {
        /** The entries (by seed) and results of a saved section; throws when it has no usable draw. */
        internal fun read(lines: List<String>, header: String): Pair<List<DrawEntry>, List<TieScore.Saved>> {
            require(lines.firstOrNull()?.trim() == header) { "not a $header section" }
            val entries = lines.mapNotNull { l ->
                runCatching { l.trim().split(' ').takeIf { it[0] == "e" }?.let { DrawEntry.decode(it, 1) } }.getOrNull()
            }.distinctBy { it.seed }.sortedBy { it.seed }
            require(entries.size >= 2) { "no draw in the $header section" }
            return entries to TieScore.Saved.parse(lines)
        }
    }
}

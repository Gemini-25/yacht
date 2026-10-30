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
                    when (TieRule.match(bd.games, r.format)) {
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

/** Where a tie stands in a bracket: the winners' side, the losers' side (패자부활) or the grand final. */
enum class BracketSide { WINNERS, LOSERS, GRAND }

/**
 * One tie of a [Knockout]. Once both sides are known, [a] is the better seed (it goes through a
 * tie still level after the deciders). A side missing from the draw makes a bye: the other side
 * goes on without a fixture. [round] counts within its [side]; [stage] is its place in the play
 * order (the calendar's stage). A series tie ([firstTo] > 0) is first to that many games won,
 * in at most [legs] pairs of games.
 */
class KnockoutTie internal constructor(
    val id: Int,
    val round: Int,
    val slot: Int,
    val legs: Int,
    val side: BracketSide = BracketSide.WINNERS,
    val stage: Int = round,
    val firstTo: Int = 0,
) {
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

    // The sides feeding this one, until both are known.
    internal var top: DrawEntry? = null
    internal var bottom: DrawEntry? = null
    internal var fed = 0
}

/**
 * A knockout bracket. Round 1 holds [first]'s pairings (a power of two of them, a null side being
 * a bye); the winner of tie s goes to tie s/2 of the next round.
 *
 * With [double] (패자부활, from 4 entries) a loser of the winners' side drops to the losers' side,
 * where a loss ends it: its round 1 pairs the first round's losers, then each round alternates
 * between taking the next winners' round's losers (in reverse order every other time, so recent
 * opponents do not meet again at once) and halving; its final takes the winners' final's loser,
 * and the two sides' winners meet in one grand final, no advantage. The stages (the play order,
 * which the calendar and [opensAt] count) are then: W1; W2 + L1; L2; W3 + L3; L4; ...; the
 * winners' final with the losers' round before its final; the losers' final; the grand final.
 *
 * A stage opens at league progress [opensAt] (everything is open at 1.0, so a bracket always
 * finishes); its ties are handed out as soon as both sides are known. Ids run from [idBase] in
 * the play order, so results read back in id order always find their tie ready.
 *
 * [legsOf] gives the matches of a winners' round (legs of two-game matches, as before series);
 * with [winsOf] a tie is a series instead, first to that many games won ([KnockoutLength]).
 */
class Knockout(
    first: List<Pair<DrawEntry?, DrawEntry?>>,
    legsOf: (round: Int) -> Int,
    private val labelOf: (round: Int) -> String,
    /** League progress at which each stage opens. */
    val opensAt: (stage: Int) -> Double,
    idBase: Int = 0,
    /** Series: games to win a tie of a winners' round (null: legs of [legsOf]). */
    winsOf: ((round: Int) -> Int)? = null,
    /** Double elimination (needs 4 entries or more; ignored below). */
    double: Boolean = false,
    /** Games to win a losers' series (its final: true) and the grand final's (with [winsOf]). */
    losersWins: (final: Boolean) -> Int = { 2 },
    grandWins: Int = 2,
) {
    /** The winners' rounds (the only rounds without [double]). */
    val rounds: List<List<KnockoutTie>>

    /** The losers' rounds; empty without double elimination. */
    val losers: List<List<KnockoutTie>>

    /** The grand final of a double elimination. */
    val grand: KnockoutTie?

    /** True when this bracket plays double elimination. */
    val double: Boolean

    /** Stages of the play order. */
    val stageCount: Int

    private val byId = HashMap<Int, KnockoutTie>()
    private val taken = HashSet<Int>()

    /** Every tie in play order (stage, then winners before losers, then slot). */
    private val ordered: List<KnockoutTie>

    /** Every tie, in play order. */
    val all: List<KnockoutTie> get() = ordered

    /** The ties of each stage (a big cup asks for its counts at every snapshot). */
    private val byStage: List<List<KnockoutTie>>

    init {
        require(first.isNotEmpty() && first.size and (first.size - 1) == 0) { "a bracket needs a power of two ties" }
        val k = Integer.numberOfTrailingZeros(first.size) + 1
        this.double = double && first.size >= 2
        stageCount = stageCount(k, this.double)
        fun legs(r: Int, wins: Int) = if (winsOf != null) KnockoutLength.pairs(wins) else legsOf(r)
        // Ties first without ids, in stage order; ids follow that order.
        class Plan(val round: Int, val slot: Int, val side: BracketSide, val stage: Int, val wins: Int, val legs: Int)
        val plans = ArrayList<Plan>()
        var n = first.size
        for (r in 0 until k) {
            val wins = winsOf?.invoke(r) ?: 0
            for (sl in 0 until n) plans += Plan(r, sl, BracketSide.WINNERS, winnersStage(r, this.double), wins, legs(r, wins))
            n /= 2
        }
        val loserCounts = if (this.double) losersSizes(first.size) else emptyList()
        for ((j, c) in loserCounts.withIndex()) {
            val final = j == loserCounts.lastIndex
            val wins = if (winsOf != null) losersWins(final) else 0
            for (sl in 0 until c) plans += Plan(j, sl, BracketSide.LOSERS, j + 1, wins, if (winsOf != null) KnockoutLength.pairs(wins) else 1)
        }
        if (this.double) plans += Plan(0, 0, BracketSide.GRAND, stageCount - 1, if (winsOf != null) grandWins else 0, if (winsOf != null) KnockoutLength.pairs(grandWins) else legsOf(k - 1))
        // Play order: stage, then the winners' side, then slot.
        plans.sortWith(compareBy<Plan> { it.stage }.thenBy { it.side.ordinal }.thenBy { it.round }.thenBy { it.slot })
        var id = idBase
        val made = plans.map { pl -> KnockoutTie(id++, pl.round, pl.slot, pl.legs, pl.side, pl.stage, pl.wins) }
        ordered = made
        byStage = (0 until stageCount).map { st -> made.filter { it.stage == st } }
        for (t in made) byId[t.id] = t
        rounds = (0 until k).map { r -> made.filter { it.side == BracketSide.WINNERS && it.round == r }.sortedBy { it.slot } }
        losers = loserCounts.indices.map { j -> made.filter { it.side == BracketSide.LOSERS && it.round == j }.sortedBy { it.slot } }
        grand = made.firstOrNull { it.side == BracketSide.GRAND }
        for ((sl, p) in first.withIndex()) {
            feed(rounds[0][sl], p.first, top = true)
            feed(rounds[0][sl], p.second, top = false)
        }
    }

    /** The deciding tie: the grand final, or the last winners' round. */
    val final: KnockoutTie get() = grand ?: rounds.last().single()
    val finished: Boolean get() = final.decided
    val champion: DrawEntry? get() = if (finished) final.winner else null
    val runnerUp: DrawEntry? get() = final.loser

    /** The losers' final's loser (third place) once played; null without double elimination. */
    val third: DrawEntry? get() = losers.lastOrNull()?.singleOrNull()?.loser

    /**
     * How far each side that went out got: 1 the runner-up, 2 the last four, 3 the last eight,
     * and so on (0 the champion); sides still in it are absent. Without double elimination a
     * loss in round r of k is place k - r; with it only losses on the losers' side (and the final)
     * end a run: its final and the round before it are the last four, the two before those the
     * last eight, ...
     */
    fun places(): Map<String, Int> {
        val out = HashMap<String, Int>()
        if (!double) {
            for ((r, round) in rounds.withIndex()) for (t in round) t.loser?.let { out[it.key] = rounds.size - r }
        } else {
            for ((j, round) in losers.withIndex()) for (t in round) t.loser?.let { out[it.key] = 2 + (losers.lastIndex - j) / 2 }
            final.loser?.let { out[it.key] = 1 }
        }
        champion?.let { out[it.key] = 0 }
        return out
    }

    /** The last four's two sides that did not reach the final (the semi-finals' losers), once out. */
    fun lastFour(): List<DrawEntry> =
        if (!double) rounds.getOrNull(rounds.size - 2)?.mapNotNull { it.loser }.orEmpty()
        else losers.takeLast(2).flatMap { round -> round.mapNotNull { it.loser } }

    /** True when [t] is the deciding tie (the final). */
    fun isFinal(t: KnockoutTie): Boolean = t === final

    /** True when losing [t] ends a side's run (any tie without double elimination; the winners' side drops to the losers'). */
    fun eliminates(t: KnockoutTie): Boolean = !double || t.side != BracketSide.WINNERS

    /** Ties that are or will be played (byes are not fixtures). */
    val fixturesTotal: Int get() = ordered.count { !it.bye }
    val fixturesLeft: Int get() = ordered.count { !it.bye && !it.decided }

    /** The label of winners' round [round] (as the competition names it; "승자조 ..." with double elimination). */
    fun label(round: Int): String = if (double) "승자조 " + labelOf(round) else labelOf(round)

    /** The label of [t]: its round's, its losers' round's, or the grand final's. */
    fun label(t: KnockoutTie): String = when (t.side) {
        BracketSide.WINNERS -> label(t.round)
        BracketSide.LOSERS -> if (t.round == losers.lastIndex) "패자조 결승" else "패자조 ${t.round + 1}R"
        BracketSide.GRAND -> GRAND_LABEL
    }

    /** The label of stage [stage]: its rounds' labels, winners first. */
    fun stageLabel(stage: Int): String = byStage.getOrNull(stage).orEmpty().map { label(it) }.distinct().joinToString(" · ")

    fun tie(id: Int): KnockoutTie? = byId[id]

    /** The stage of tie [id] in the play order (its round without double elimination); -1 when it is not this bracket's. */
    fun roundOf(id: Int): Int = byId[id]?.stage ?: -1

    /** Played and total ties (byes left out) of each stage. */
    fun counts(): List<StageCount> = byStage.map { ts ->
        var played = 0
        var total = 0
        for (t in ts) {
            if (t.bye) continue
            total++
            if (t.result != null) played++
        }
        StageCount(played, total)
    }

    /** Stages for the calendar: each stage with its label, opening threshold and [target]. */
    fun stages(target: (stage: Int) -> Double = opensAt): List<Stage> = (0 until stageCount).map { st -> Stage(stageLabel(st), opensAt(st), target(st)) }

    /** True when [id] is one of this bracket's ties. */
    operator fun contains(id: Int): Boolean = id in byId

    /**
     * The first open tie (earliest stage first) whose fixture, built by [make], keeps no AI of
     * [busy] waiting; it is then taken until [record] or [release].
     */
    fun next(p: Double, busy: Set<String>, make: (KnockoutTie) -> Fixture): Fixture? {
        for (t in ordered) {
            if (!t.ready || t.id in taken) continue
            if (!opened(p, opensAt(t.stage))) continue
            // Busy sides wait without building their fixture (team ids are never busy: their
            // lineups are checked below).
            if (t.a!!.key in busy || t.b!!.key in busy) continue
            val f = make(t)
            if (f.players.any { it in busy }) continue
            taken += t.id
            return f
        }
        return null
    }

    fun release(id: Int) {
        taken -= id
    }

    /** Players of the open ties at progress [p] not in play yet ([make] builds their fixtures). */
    fun waiting(p: Double, make: (KnockoutTie) -> Fixture): Set<String> {
        val out = HashSet<String>()
        for (t in ordered) {
            if (t.ready && t.id !in taken && opened(p, opensAt(t.stage))) out += make(t).players
        }
        return out
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
        // Level (a team match level on boards under 판 승점제): the totals, then the better seed.
        val w = if (score.winner != 0) score.winner else (score.totalA - score.totalB).coerceIn(-1, 1)
        decide(t, if (w < 0) t.b else t.a)
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
        // The side that did not go on (none for a bye).
        val l = if (t.bye) null else if (w == t.a) t.b else t.a
        when (t.side) {
            BracketSide.WINNERS -> {
                val r = t.round
                if (r + 1 < rounds.size) feed(rounds[r + 1][t.slot / 2], w, top = t.slot % 2 == 0)
                else grand?.let { feed(it, w, top = true) }
                if (double) {
                    if (r == 0) {
                        feed(losers[0][t.slot / 2], l, top = t.slot % 2 == 0)
                    } else {
                        // Into the losers' round 2r - 1, against that round's survivors; reversed on odd rounds.
                        val into = losers[2 * r - 1]
                        val at = if (r % 2 == 1) into.size - 1 - t.slot else t.slot
                        feed(into[at], l, top = false)
                    }
                }
            }
            BracketSide.LOSERS -> {
                val j = t.round
                when {
                    j == losers.lastIndex -> feed(grand!!, w, top = false)
                    // An even round (or the first) meets the dropping losers slot for slot.
                    j % 2 == 0 -> feed(losers[j + 1][t.slot], w, top = true)
                    else -> feed(losers[j + 1][t.slot / 2], w, top = t.slot % 2 == 0)
                }
            }
            BracketSide.GRAND -> {}
        }
    }

    /** Result lines of the played ties. */
    fun encode(out: StringBuilder) {
        for (t in ordered) t.result?.let { out.append(TieScore.Saved.line(t.id, t.a!!.key, t.b!!.key, it)) }
    }

    /** Puts saved results back, earliest first; results that do not fit are skipped (played again). */
    internal fun restore(results: List<TieScore.Saved>) {
        for (r in results) if (r.id in byId) record(r.id, r.a, r.b, r.score)
    }

    private fun tieView(t: KnockoutTie): TieView {
        val s = t.result
        val a = if (t.bye) t.a else t.a ?: t.top
        val b = if (t.bye) null else t.b ?: t.bottom
        // Fed from the winners' bracket: a losers' odd round's bottom (the dropped loser), the grand final's top.
        val fromWinners = when (t.side) {
            BracketSide.GRAND -> t.top?.key
            BracketSide.LOSERS -> if (t.round % 2 == 1) t.bottom?.key else null
            BracketSide.WINNERS -> null
        }
        return TieView(a?.key, b?.key, s?.scoreA ?: 0.0, s?.scoreB ?: 0.0, t.winner?.key, s != null, t.legs, t.firstTo, fromWinners, t.bye)
    }

    /**
     * The bracket for the screen: the winners' rounds, then the losers' and the grand final; a
     * side waiting for its opponent shows on its own, and a bye shows its side as [TieView.a]
     * with no [TieView.b].
     */
    fun view(): List<BracketRound> =
        rounds.mapIndexed { r, round -> BracketRound(label(r), round.map(::tieView), BracketSide.WINNERS, round.first().stage) } +
            losers.map { round -> BracketRound(label(round.first()), round.map(::tieView), BracketSide.LOSERS, round.first().stage) } +
            listOfNotNull(grand?.let { BracketRound(GRAND_LABEL, listOf(tieView(it)), BracketSide.GRAND, it.stage) })

    companion object {
        /** The grand final of a double elimination. */
        const val GRAND_LABEL = "최종 결승"

        /** The stage of winners' round [r] in the play order. */
        fun winnersStage(r: Int, double: Boolean): Int = if (!double || r == 0) r else 2 * r - 1

        /** Stages of a bracket of [k] winners' rounds: k, or 2k with double elimination (from 4 entries). */
        fun stageCount(k: Int, double: Boolean): Int = if (double && k >= 2) 2 * k else k

        /**
         * The stage labels of a bracket of [ties] first-round ties named [labelOf] by winners'
         * round, before its draw (as [Knockout.stageLabel] would give them).
         */
        fun stageLabels(ties: Int, double: Boolean, labelOf: (Int) -> String): List<String> {
            val k = Knockout(List(maxOf(1, ties)) { null to null }, { 1 }, labelOf, { 0.0 }, double = double)
            return (0 until k.stageCount).map { k.stageLabel(it) }
        }

        /** [count] values from [first] to [last], evenly spaced (one value: [last]). */
        fun spread(count: Int, first: Double, last: Double): List<Double> =
            if (count <= 1) listOf(last) else List(count) { first + (last - first) * it / (count - 1) }

        /** Ties of each stage of a bracket of [ties] first-round ties (byes counted), for counts before the draw. */
        fun stageSizes(ties: Int, double: Boolean): List<Int> {
            val k = Integer.numberOfTrailingZeros(maxOf(1, ties)) + 1
            val dbl = double && ties >= 2
            val out = IntArray(stageCount(k, dbl))
            var n = ties
            for (r in 0 until k) {
                out[winnersStage(r, dbl)] += n
                n /= 2
            }
            if (dbl) {
                for ((j, c) in losersSizes(ties).withIndex()) out[j + 1] += c
                out[out.size - 1] += 1
            }
            return out.toList()
        }

        /** Stages of a bracket of [size] entries (a power of two). */
        fun stagesFor(size: Int, double: Boolean): Int = stageCount(Integer.numberOfTrailingZeros(maxOf(2, size)), double && size >= 4)

        /**
         * Ties of each losers' round of a bracket of [ties] first-round ties: n/4, n/4, n/8, n/8,
         * ..., 1, 1 for n entries (2k - 2 rounds).
         */
        fun losersSizes(ties: Int): List<Int> {
            val out = ArrayList<Int>()
            var c = ties / 2
            while (c >= 1) {
                out += c
                out += c
                c /= 2
            }
            return out
        }

        /** The deal of a competition's fixture: a pure function of the season, the competition and the id. */
        internal fun fixtureSeed(seasonSeed: Long, comp: Comp, id: Int): Long = DiceStream.mix(seasonSeed, comp.code.code.toLong(), id.toLong())

        /**
         * True when [r] is a result of this season's fixture: a job of another season or league
         * with the same id and sides (a stale result) has another deal and is ignored.
         */
        internal fun current(seasonSeed: Long, comp: Comp, r: JobResult): Boolean = r.fixture.seed == fixtureSeed(seasonSeed, comp, r.fixture.id)

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

        /**
         * Empty rounds for a bracket not drawn yet ([ties] in the first round, halving; with
         * [double] the losers' rounds and the grand final after them).
         */
        fun placeholder(
            ties: Int,
            label: (Int) -> String,
            legs: (Int) -> Int,
            double: Boolean = false,
            firstTo: (Int) -> Int = { 0 },
            /** Series lengths on the losers' side (its final: true) and of the grand final; 0: no series. */
            losersFirstTo: (Boolean) -> Int = { 0 },
            grandFirstTo: Int = 0,
        ): List<BracketRound> {
            val out = ArrayList<BracketRound>()
            var n = ties
            val dbl = double && ties >= 2
            while (n >= 1) {
                val r = out.size
                val wins = firstTo(r)
                out += BracketRound(
                    (if (dbl) "승자조 " else "") + label(r), List(n) { TieView(null, null, 0.0, 0.0, null, false, if (wins > 0) KnockoutLength.pairs(wins) else legs(r), wins) },
                    BracketSide.WINNERS, winnersStage(r, dbl),
                )
                n /= 2
            }
            if (dbl) {
                val sizes = losersSizes(ties)
                for ((j, c) in sizes.withIndex()) {
                    val wins = losersFirstTo(j == sizes.lastIndex)
                    out += BracketRound(
                        if (j == sizes.lastIndex) "패자조 결승" else "패자조 ${j + 1}R", List(c) { TieView(null, null, 0.0, 0.0, null, false, if (wins > 0) KnockoutLength.pairs(wins) else 1, wins) },
                        BracketSide.LOSERS, j + 1,
                    )
                }
                val k = Integer.numberOfTrailingZeros(ties) + 1
                out += BracketRound(
                    GRAND_LABEL, listOf(TieView(null, null, 0.0, 0.0, null, false, if (grandFirstTo > 0) KnockoutLength.pairs(grandFirstTo) else legs(k - 1), grandFirstTo)),
                    BracketSide.GRAND, stageCount(k, true) - 1,
                )
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
    /** A 2-0 sweep earns a bonus point (판 승점제's 완승 보너스; one-on-one matches only). */
    private val bonus: Boolean = false,
    private val opensAt: (round: Int) -> Double,
) {
    /** Group names ("A조" by default). */
    var label: ((group: Int) -> String)? = null

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

        /** 2-0 sweeps that earned the bonus point. */
        var sweeps = 0
            internal set
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

    fun groupLabel(g: Int): String = label?.invoke(g) ?: "${'A' + g}조"

    fun matchLabel(m: Match): String = "${groupLabel(m.group)} ${m.round + 1}차전"

    /** The round of match [id]; -1 when it is not this stage's. */
    fun roundOf(id: Int): Int = byId[id]?.round ?: -1

    /** Played and total matches of each round. */
    fun counts(): List<StageCount> {
        val played = IntArray(rounds)
        val total = IntArray(rounds)
        for (m in matches) {
            total[m.round]++
            if (m.result != null) played[m.round]++
        }
        return List(rounds) { StageCount(played[it], total[it]) }
    }

    fun entry(key: String): DrawEntry? = groups.firstNotNullOfOrNull { g -> g.firstOrNull { it.key == key } }

    /** The first open match (earliest round first) whose fixture keeps no AI of [busy] waiting. */
    fun next(p: Double, busy: Set<String>, make: (Match) -> Fixture): Fixture? {
        for (m in matches) {
            if (m.result != null || m.id in taken) continue
            if (!Knockout.opened(p, opensAt(m.round))) break
            if (m.a in busy || m.b in busy) continue
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
                    if (bonus && !teams && s.scoreB == 0.0 && s.scoreA >= 2.0) x.sweeps++
                }
                -1 -> {
                    y.wins++
                    x.losses++
                    if (bonus && !teams && s.scoreA == 0.0 && s.scoreB >= 2.0) y.sweeps++
                }
                else -> {
                    x.draws++
                    y.draws++
                }
            }
        }
        for (r in rows.values) r.points = TieRule.points(1) * r.wins + TieRule.points(0) * r.draws + r.sweeps
        return rows.values.sortedWith(order)
    }

    /** The difference shown in the table: boards for team matches, score points otherwise. */
    fun diff(r: Row): Int = if (teams) r.boardDiff else r.scoreDiff

    private val order: Comparator<Row> = compareByDescending<Row> { it.points }
        .thenByDescending { diff(it) }
        .thenByDescending { if (teams) it.scoreDiff else it.halvesFor }
        .thenBy { it.entry.seed }

    fun view(g: Int): GroupTable =
        GroupTable(groupLabel(g), table(g).map { GroupRow(it.entry.key, it.played, it.wins, it.draws, it.losses, it.points, diff(it), it.sweeps) }, bonus && !teams)

    /** Result lines of the played matches. */
    fun encode(out: StringBuilder) {
        for (m in matches) m.result?.let { out.append(TieScore.Saved.line(m.id, m.a, m.b, it)) }
    }

    companion object {
        /** Saved by a competition whose group tables give the 2-0 bonus point. */
        const val BONUS_LINE = "bonus 1"

        fun bonus(lines: List<String>): Boolean = lines.any { it.trim() == BONUS_LINE }
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

    /** Drawn with longer late rounds ([KnockoutLength]); saved. */
    protected open val long: Boolean get() = false

    /** Ties are series (선승제, [KnockoutLength.wins]); saved. */
    protected open val series: Boolean get() = false

    /** Double elimination (패자부활); saved. */
    protected open val double: Boolean get() = false

    /** News of a finished tie. */
    protected abstract fun news(t: KnockoutTie): List<LeagueEvent>

    protected open fun fixture(t: KnockoutTie): Fixture =
        Fixture(comp, t.id, t.a!!.key, t.b!!.key, Knockout.fixtureSeed(seed, comp, t.id), JobKind.TIE, legs = t.legs, label = bracket.label(t), firstTo = t.firstTo)

    override fun next(p: Double, busy: Set<String>): Fixture? = bracket.next(p, busy, ::fixture)

    /** The share of the regular season round [round] would like to be played at (its threshold by default). */
    protected open fun target(round: Int): Double = bracket.opensAt(round)

    override fun stages(): List<Stage> = bracket.stages(::target)

    override fun stageOf(f: Fixture): Int = bracket.roundOf(f.id)

    override fun stageCounts(): List<StageCount> = bracket.counts()

    override fun release(f: Fixture) = bracket.release(f.id)

    override fun record(r: JobResult): List<LeagueEvent> {
        val f = r.fixture
        if (!Knockout.current(seed, comp, r)) return emptyList()
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
        if (long) out.append(KnockoutLength.LINE).append('\n')
        if (series) out.append(KnockoutLength.SERIES_LINE).append('\n')
        if (double) out.append(KnockoutLength.DOUBLE_LINE).append('\n')
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

/**
 * Matches in a one-on-one knockout tie (긴 토너먼트): a round of [left] players plays [short]
 * matches, or with [long] more from the last 16 on so a final is not one coin toss: 16강 and
 * 8강 2, 4강 3, 결승 4 (each match two games on the same dice, deciders when level). A
 * competition saves [LINE] when it was drawn long, so a season keeps the lengths it began with.
 */
object KnockoutLength {
    const val LINE = "long 1"

    fun legs(left: Int, short: Int, long: Boolean): Int = if (!long) short else when {
        left <= 2 -> maxOf(short, FINAL)
        left <= 4 -> maxOf(short, SEMI)
        left <= 16 -> maxOf(short, LATE)
        else -> short
    }

    /** True when a saved section was drawn long. */
    fun saved(lines: List<String>): Boolean = lines.any { it.trim() == LINE }

    private const val LATE = 2
    private const val SEMI = 3
    private const val FINAL = 4

    /**
     * Games to win a series (선승제) in a winners-bracket round of [left] players: 2 before the
     * last 8, 3 in the 8강 and 4강 (and a winners-bracket final, with a grand final still to
     * come: [grandAfter]), 4 in a final; every tie 2 when not [long].
     */
    fun wins(left: Int, long: Boolean, grandAfter: Boolean = false): Int = if (!long) SHORT else when {
        left <= 2 -> if (grandAfter) SEMI else FINAL
        left <= 8 -> SEMI
        else -> SHORT
    }

    /** Games to win a losers-bracket series: 2, its final 3 (2 when not [long]). */
    fun losersWins(final: Boolean, long: Boolean): Int = if (long && final) SEMI else SHORT

    /** Games to win the grand final (the winners' champion against the losers'): as a final. */
    fun grandWins(long: Boolean): Int = if (long) FINAL else SHORT

    /** Pairs of games a series to [wins] may take before deciders: enough for draws on the way. */
    fun pairs(wins: Int): Int = wins + 1

    private const val SHORT = 2

    /** Saved when a competition plays series ([LeagueConfig.seriesKnockouts]). */
    const val SERIES_LINE = "series 1"

    /** Saved when a competition plays double elimination ([LeagueConfig.doubleElimination]). */
    const val DOUBLE_LINE = "double 1"

    fun series(lines: List<String>): Boolean = lines.any { it.trim() == SERIES_LINE }
    fun double(lines: List<String>): Boolean = lines.any { it.trim() == DOUBLE_LINE }
}

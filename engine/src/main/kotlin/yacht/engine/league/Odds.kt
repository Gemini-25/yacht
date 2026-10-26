package yacht.engine.league

import yacht.engine.DiceStream
import kotlin.math.pow

/**
 * One AI's chances over the rest of the season (우승·강등 확률), from [Odds.run]. The places
 * are the ones the off-season uses: [Offseason.moves] and [PromotionPlayoff] for the moves,
 * [ChampionsLeague.entrants] for the champions league.
 */
data class AiOdds(
    val key: String,
    /** Its division (0 = top). */
    val division: Int,
    /** Its place in the table now (1 = top). */
    val position: Int,
    /** Mean final place over the runs (1 = top). */
    val expectedPosition: Double,
    /** 1st of its division after the league stage (division 1: the regular-season leader, 정규리그 1위). */
    val leader: Double,
    /**
     * Its division's title: [leader], except in division 1 with the title playoff, where it is
     * the chance of winning the playoff from the place it finishes in.
     */
    val title: Double,
    /** A place in the title playoff (division 1's top 4); 0 when there is none. */
    val playoff: Double,
    /**
     * A place in next season's champions league: by [ChampionsLeague.entrants]' rule once the
     * cup's finalists are known (or there is no cup), else by league position alone (see [Odds]).
     */
    val champions: Double,
    /** Up a division next season: the automatic places, plus winning the promotion playoff. */
    val promotion: Double,
    /** Down a division: the automatic places, plus losing the promotion playoff. */
    val relegation: Double,
    /** The lower side's place in a promotion playoff (it goes up by winning the tie). */
    val promotionPlayoff: Double,
    /** The upper side's place in a promotion playoff (it goes down by losing the tie). */
    val relegationPlayoff: Double,
)

/** Every AI's [AiOdds] at one point of a season: an immutable snapshot for the screens. */
class OddsTable(
    val season: Int,
    /** League fixtures played when it was computed, of [total]. */
    val played: Int,
    val total: Int,
    val runs: Int,
    /** Share of drawn matches the runs assumed. */
    val drawRate: Double,
    /** Every AI, division by division, each in its table's order. */
    val rows: List<AiOdds>,
) {
    private val byKey = rows.associateBy { it.key }

    val progress: Double get() = if (total == 0) 1.0 else played.toDouble() / total

    operator fun get(key: String): AiOdds? = byKey[key]

    /** Division [d]'s rows in its table's order. */
    fun division(d: Int): List<AiOdds> = rows.filter { it.division == d }

    /**
     * True when [season] has moved on enough to compute again: another season, [step] more of
     * the league played, or the last fixture played since (the final certainties).
     */
    fun stale(season: Season, step: Double = Odds.STEP): Boolean =
        season.number != this.season || season.progress - progress >= step || (season.finished && played != season.fixturesPlayed)
}

/**
 * What [Odds.run] needs, copied from a season by [Odds.input] (under the caller's lock, O(AIs +
 * fixtures)) so the runs can go on off the lock.
 */
class OddsInput internal constructor(
    internal val season: Int,
    internal val played: Int,
    internal val total: Int,
    internal val drawRate: Double,
    /** Global index: division by division, each in its static tie-break order. */
    internal val keys: Array<String>,
    /** Division d is indices start[d] until start[d + 1]. */
    internal val start: IntArray,
    internal val basePoints: IntArray,
    internal val baseDiff: IntArray,
    /** Today's total score (not simulated): a tie-break across groups after points and difference. */
    internal val baseScored: IntArray,
    /** Bradley-Terry strength, 10^((rating - 1500) / 400). */
    internal val strength: DoubleArray,
    /** Fixtures to play: the sides, and the chances of A winning and of A not losing. */
    internal val fa: IntArray,
    internal val fb: IntArray,
    internal val win: DoubleArray,
    internal val notLose: DoubleArray,
    /** Each division's tier, and per boundary below tier t ([Pyramid.crossing]): s up from each lower group, places down from the upper groups, and whether it has playoff ties. */
    internal val tierOf: IntArray,
    /** Each pair of a division meets this many times (2: double round robin). */
    internal val rounds: Int,
    internal val swapBelow: IntArray,
    internal val downBelow: IntArray,
    internal val playoffBelow: BooleanArray,
    internal val clPlaces: IntArray,
    internal val titlePlayoff: Boolean,
    /** Each division's AIs in today's table order (global indices). */
    internal val table: List<IntArray>,
    /** Winners already known: the title playoff's champion, the promotion ties (spot, lower side went up). */
    internal val champion: String?,
    internal val decided: List<Pair<PromotionPlayoff.Spot, Boolean>>,
    /**
     * Champions league places by [ChampionsLeague.entrants]' rule: the cup finalists (global
     * indices, -1 when not in the league; empty without a cup), or null while the cup is on.
     */
    internal val finalists: IntArray?,
    /** Every AI by rating, best first: the entrants' rule fills its last places from it. */
    internal val byRating: IntArray,
) {
    /** Fixtures the runs play. */
    val fixtures: Int get() = fa.size
}

/**
 * 우승·강등 확률 (docs/LEAGUE_SPEC.md section 6): Monte Carlo over the league fixtures still to
 * play, from the current tables. Each match is won by A with the Bradley-Terry share
 * `1 / (1 + 10^((rb - ra) / 400))` of the current ratings, less half the draw probability (the
 * observed share of drawn matches, [drawRate]); so the expected share is the Bradley-Terry one.
 *
 * Approximations, all deterministic:
 * - The score difference moves by [WIN_DIFF] per decided match (draws: none); the score itself
 *   is not simulated, so a tie on points and difference goes to today's score, then to the
 *   division's order.
 * - A pyramid's boundaries ([Pyramid.crossing]) compare places across groups by points and
 *   score difference (the off-season: per match).
 * - Knockout ties (the title playoff, the promotion playoff) are won with the Bradley-Terry
 *   share of the two AIs, legs and deciders ignored. Rather than half the playoff place, the
 *   promotion and relegation odds add that share of it; and a tie's outcome is added as an
 *   expectation in each run instead of being drawn, which costs nothing and lowers the noise.
 *   A playoff counts as decided only once the whole competition is over ([input] of a plan).
 * - Champions league places: while the cup is still on, division 1's top 12 and division 2's
 *   top 2 (one division: its top 14; a pyramid's 2부 groups: their winners), the cup finalists'
 *   places not counted. Once the cup's
 *   finalists are known, or without a cup, each run applies [ChampionsLeague.entrants]' rule:
 *   those places, the finalists, then the 1st division's next places until 16, then by rating.
 *
 * Under 15 ns per fixture and run on a desktop: 5100 fixtures and 2000 runs take about 120 ms.
 * The app copies with [input] under its lock and calls [run] on a background thread.
 */
object Odds {
    const val RUNS = 2000

    /** Progress between two recomputations (see [OddsTable.stale]). */
    const val STEP = 0.1

    /** Score difference of a decided match in the runs. */
    const val WIN_DIFF = 30

    /** Draw share assumed before any match is played, and its weight in matches. */
    const val DRAW_PRIOR = 0.02
    const val PRIOR_WEIGHT = 20.0

    /** Champions league places of division 1 and 2 (one division: both from it). */
    private const val CL_FIRST = 12
    private const val CL_SECOND = 2
    private const val TITLE_PLAYOFF = 4

    // Bits of the packed sort key: points, then the score difference, then the static order.
    private const val DIFF_BITS = 26
    private const val DIFF_OFFSET = 1 shl (DIFF_BITS - 1)
    private const val DIFF_MAX = (1 shl DIFF_BITS) - 1

    /**
     * The share of drawn matches among the played ones, pulled towards [prior] by [weight]
     * matches (no draw in the first few matches does not mean there are none).
     */
    fun drawRate(season: Season, prior: Double = DRAW_PRIOR, weight: Double = PRIOR_WEIGHT): Double {
        var draws = 0
        var played = 0
        for (id in 0 until season.fixturesTotal) {
            val g = season.result(id) ?: continue
            played++
            if (TieRule.match(g) == 0) draws++
        }
        return ((draws + prior * weight) / (played + weight)).coerceIn(0.0, 1.0)
    }

    /** [input] and [run] at once (the season must not change meanwhile). */
    fun compute(plan: SeasonPlan, rating: (String) -> Double, runs: Int = RUNS, seed: Long = plan.seed): OddsTable =
        run(input(plan, rating), runs, seed)

    /** [input] and [run] at once (the season must not change meanwhile). */
    fun compute(season: Season, config: LeagueConfig, rating: (String) -> Double, drawRate: Double, runs: Int = RUNS, seed: Long): OddsTable =
        run(input(season, config, rating, drawRate), runs, seed)

    /**
     * The input for [plan]'s league: its config, the draw share from its results, and the
     * post-season playoffs once each is over (the league is then over too), whose winners are
     * then certain.
     */
    fun input(plan: SeasonPlan, rating: (String) -> Double): OddsInput {
        val champion = plan.competition(Comp.TITLE_PLAYOFF)?.takeIf { it.finished }?.honours()?.firstOrNull { it.kind == "L0" }?.key
        val promo = (plan.competition(Comp.PROMOTION_PLAYOFF) as? PromotionPlayoff)?.takeIf { it.finished }
        val decided = promo?.let { p -> p.spots.map { s -> s to p.honours().any { it.kind == "UP${s.division}" && it.key == s.lower } } }.orEmpty()
        // The cup's finalists take champions league places (no cup this season: none).
        val cup = plan.competition(Comp.CUP)
        val finalists = when {
            cup == null -> emptyList()
            cup.finished -> cup.honours().filter { it.kind == "CUP" || it.kind == "CUP2" }.map { it.key }
            else -> null
        }
        return input(plan.league, plan.config, rating, drawRate(plan.league), champion, decided, finalists)
    }

    /**
     * Copies what the runs need from [season] under [config]'s places: the tables, and the
     * fixtures still to play (in progress included) with their chances from [rating] (NaN:
     * [LeagueWorld.START_RATING]) and [drawRate].
     */
    fun input(season: Season, config: LeagueConfig, rating: (String) -> Double, drawRate: Double): OddsInput =
        input(season, config, rating, drawRate, null, emptyList(), if (config.cup) null else emptyList())

    private fun input(
        season: Season,
        config: LeagueConfig,
        rating: (String) -> Double,
        drawRate: Double,
        champion: String?,
        decided: List<Pair<PromotionPlayoff.Spot, Boolean>>,
        finalists: List<String>?,
    ): OddsInput {
        val divs = season.divisions
        val nd = divs.size
        val start = IntArray(nd + 1)
        for (d in 0 until nd) start[d + 1] = start[d] + divs[d].size
        val n = start[nd]
        // Each division in its static tie-break order (today's score, then the division's
        // order), which the low bits of the sort key carry.
        val keys = Array(n) { "" }
        val basePoints = IntArray(n)
        val baseDiff = IntArray(n)
        val baseScored = IntArray(n)
        val index = HashMap<String, Int>(n * 2)
        for (d in 0 until nd) {
            val order = divs[d].map { season.standings.getValue(it) }.sortedByDescending { it.scored }
            for ((j, s) in order.withIndex()) {
                val g = start[d] + j
                keys[g] = s.key
                basePoints[g] = s.points
                baseDiff[g] = s.diff
                baseScored[g] = s.scored
                index[s.key] = g
            }
        }
        // Relative to 1500 so the strengths stay small.
        val strength = DoubleArray(n) { g ->
            val r = rating(keys[g]).takeIf { it.isFinite() } ?: LeagueWorld.START_RATING
            10.0.pow((r - 1500.0) / 400.0)
        }
        val draw = drawRate.coerceIn(0.0, 1.0)
        val left = season.unplayed()
        val fa = IntArray(left.size)
        val fb = IntArray(left.size)
        val win = DoubleArray(left.size)
        val notLose = DoubleArray(left.size)
        var m = 0
        for (f in left) {
            val a = index[f.a] ?: continue
            val b = index[f.b] ?: continue
            val p = strength[a] / (strength[a] + strength[b])
            // Draws take half their share from each side; capped so no side's win goes negative.
            val d = minOf(draw, 2 * minOf(p, 1 - p))
            fa[m] = a
            fb[m] = b
            win[m] = p - d / 2
            notLose[m] = p + d / 2
            m++
        }
        // Places of each boundary, as Pyramid.crossing (Offseason.moves, PromotionPlayoff) and
        // ChampionsLeague have them.
        val size = IntArray(nd) { divs[it].size }
        val tiers = season.tiers
        val nt = tiers.count
        val tierOf = IntArray(nd) { tiers.tier(it) }
        val swapBelow = IntArray(maxOf(0, nt))
        val downBelow = IntArray(maxOf(0, nt))
        val playoffBelow = BooleanArray(maxOf(0, nt))
        for (t in 0 until nt - 1) {
            val upper = tiers.groups(t)
            val lower = tiers.groups(t + 1)
            val u = upper.count()
            val l = lower.count()
            val swap = minOf(if (tiers.linear) config.autoSwap else config.groupSwap, lower.minOf { size[it] } / 3, upper.minOf { size[it] } * u / (3 * l))
            swapBelow[t] = swap
            downBelow[t] = swap * l
            playoffBelow[t] = config.promotionPlayoff && upper.sumOf { size[it] } > swap * l && lower.any { size[it] > swap }
        }
        val clOn = config.champions && n >= ChampionsLeague.SIZE
        val exact = finalists?.takeIf { clOn }?.map { index[it] ?: -1 }?.toIntArray()
        val clPlaces = IntArray(nd) { d ->
            if (!clOn || exact != null) 0
            else when (tierOf[d]) {
                0 -> if (nt == 1) CL_FIRST + CL_SECOND else CL_FIRST
                1 -> ChampionsLeague.secondPlaces(tiers.groupCount(1))
                else -> 0
            }.coerceAtMost(size[d])
        }
        val table = (0 until nd).map { d -> season.table(d).map { index.getValue(it.key) }.toIntArray() }
        val byRating = (0 until n).sortedByDescending { strength[it] }.toIntArray()
        return OddsInput(
            season.number, season.fixturesPlayed, season.fixturesTotal, draw, keys, start, basePoints, baseDiff, baseScored, strength,
            fa.copyOf(m), fb.copyOf(m), win.copyOf(m), notLose.copyOf(m), tierOf, if (season.double) 2 else 1, swapBelow, downBelow, playoffBelow, clPlaces,
            config.titlePlayoff && nd > 0 && size[0] >= TITLE_PLAYOFF, table, champion, decided, exact, byRating,
        )
    }

    /**
     * [runs] simulations of the rest of the season from [input], seeded by [seed]; with nothing
     * left to play, one exact run. Pure: may run on any thread.
     */
    /**
     * Simulations for [fixturesLeft] fixtures: [RUNS], fewer in a big league (about 1e8
     * simulated fixtures at most per refresh, at least 200 runs).
     */
    fun runsFor(fixturesLeft: Int): Int = (1e8 / maxOf(1, fixturesLeft)).toInt().coerceIn(200, RUNS)

    /** [fixturesLeft] of [input]: its league fixtures still to play. */
    fun left(input: OddsInput): Int = input.total - input.played

    fun run(input: OddsInput, runs: Int = RUNS, seed: Long): OddsTable {
        val start = input.start
        val nd = start.size - 1
        val n = start[nd]
        val fa = input.fa
        val fb = input.fb
        val win = input.win
        val notLose = input.notLose
        val strength = input.strength
        val tierOf = input.tierOf
        val nt = if (nd == 0) 0 else tierOf[nd - 1] + 1
        val swapBelow = input.swapBelow
        val downBelow = input.downBelow
        // Divisions of each tier, and the automatic drops every upper group gives for sure
        // (the rest of a boundary's drops go to the worst of the next places across its groups).
        val tierStart = IntArray(nt + 1).also { ts -> for (t in 0..nt) ts[t] = (0 until nd).firstOrNull { tierOf[it] >= t } ?: nd }
        val groupsIn = IntArray(nt) { tierStart[it + 1] - tierStart[it] }
        val baseDown = IntArray(nt) { t -> if (t < nt - 1) downBelow[t] / groupsIn[t] else 0 }
        // Places compared across groups go by points (then difference) per match, as
        // Pyramid.worse has them: a tier's groups can differ in size by one.
        val perMatch = DoubleArray(nd) { d -> 1.0 / maxOf(1, (start[d + 1] - start[d] - 1) * input.rounds) }
        val clPlaces = input.clPlaces
        val titlePlayoff = input.titlePlayoff
        val m = fa.size
        val r = if (m == 0) 1 else runs.coerceAtLeast(1)
        val size = IntArray(nd) { start[it + 1] - start[it] }
        val rankBits = maxOf(1, 32 - Integer.numberOfLeadingZeros(size.maxOrNull() ?: 1))
        val rankMask = (1L shl rankBits) - 1
        val pointBits = 63 - DIFF_BITS - rankBits
        val pointMax = (1L shl pointBits) - 1

        val points = IntArray(n)
        val diff = IntArray(n)
        // Worse first across groups: points, difference and score per match, then by key (as Pyramid.worse).
        val worseFirst = compareBy<Pair<Int, Int>> { (g, d) -> points[g] * perMatch[d] }.thenBy { (g, d) -> diff[g] * perMatch[d] }
            .thenBy { (g, d) -> input.baseScored[g] * perMatch[d] }.thenByDescending { (g, _) -> input.keys[g] }
        val sorted = LongArray(n)
        val positionSum = LongArray(n)
        val leader = IntArray(n)
        val top = IntArray(n)
        val cl = IntArray(n)
        val up = IntArray(n)
        val down = IntArray(n)
        val upSpot = IntArray(n)
        val downSpot = IntArray(n)
        val upShare = DoubleArray(n)
        val downShare = DoubleArray(n)
        val title = DoubleArray(n)
        val finalists = input.finalists
        val entered = if (finalists != null) IntArray(n) else null
        fun e(x: Int, y: Int) = strength[x] / (strength[x] + strength[y])

        var state = DiceStream.mix(seed, 0x0DD5)
        repeat(r) { runNo ->
            System.arraycopy(input.basePoints, 0, points, 0, n)
            System.arraycopy(input.baseDiff, 0, diff, 0, n)
            for (i in 0 until m) {
                // SplitMix64, inline: no allocation and no synchronisation in the hot loop.
                state += -0x61c8864680b583ebL
                var z = state
                z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
                z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
                z = z xor (z ushr 31)
                val u = (z ushr 11) * 1.1102230246251565E-16
                val a = fa[i]
                val b = fb[i]
                if (u < win[i]) {
                    points[a] += 3
                    diff[a] += WIN_DIFF
                    diff[b] -= WIN_DIFF
                } else if (u < notLose[i]) {
                    points[a]++
                    points[b]++
                } else {
                    points[b] += 3
                    diff[b] += WIN_DIFF
                    diff[a] -= WIN_DIFF
                }
            }
            for (d in 0 until nd) {
                val s = start[d]
                val k = size[d]
                for (j in 0 until k) {
                    val g = s + j
                    val pv = pointMax - points[g].toLong().coerceIn(0L, pointMax)
                    val dv = DIFF_MAX - (diff[g] + DIFF_OFFSET).coerceIn(0, DIFF_MAX).toLong()
                    sorted[g] = (pv shl (DIFF_BITS + rankBits)) or (dv shl rankBits) or j.toLong()
                }
                // Ascending keys: the table's order, best first.
                java.util.Arrays.sort(sorted, s, s + k)
                val t = tierOf[d]
                val upTo = if (t > 0) swapBelow[t - 1] else 0
                val downFrom = if (t < nt - 1) k - baseDown[t] else k
                for (pos in 0 until k) {
                    val g = s + (sorted[s + pos] and rankMask).toInt()
                    positionSum[g] += pos.toLong()
                    if (pos == 0) leader[g]++
                    if (pos < clPlaces[d]) cl[g]++
                    if (pos < upTo) up[g]++
                    if (pos >= downFrom) down[g]++
                    if (d == 0 && titlePlayoff && pos < TITLE_PLAYOFF) top[g]++
                }
            }
            // Pyramid boundaries whose drops do not share out evenly: the rest from the worst of
            // the next places up across the upper groups, and the playoff ties after them.
            for (t in 0 until nt - 1) {
                val rest = downBelow[t] - baseDown[t] * groupsIn[t]
                val ties = if (input.playoffBelow[t] && groupsIn[t] * groupsIn[t + 1] > 1) groupsIn[t + 1] else 0
                if (rest == 0 && ties == 0) continue
                val order = nextUp(sorted, start, size, tierStart[t], tierStart[t + 1], baseDown[t], rest + ties, rankMask, worseFirst)
                for (i in 0 until minOf(rest, order.size)) down[order[i]]++
                if (ties > 0) {
                    // Each lower group's next place, best first (per match).
                    val lowers = (tierStart[t + 1] until tierStart[t + 2]).filter { size[it] > swapBelow[t] }
                        .map { d -> start[d] + (sorted[start[d] + swapBelow[t]] and rankMask).toInt() to d }
                        .sortedWith(worseFirst.reversed())
                        .map { it.first }
                    for ((i, lo) in lowers.withIndex()) {
                        val hi = order.getOrNull(rest + i) ?: break
                        val s = e(lo, hi)
                        upSpot[lo]++
                        downSpot[hi]++
                        upShare[lo] += s
                        downShare[hi] += s
                    }
                }
            }
            if (finalists != null && nd > 0) {
                val second = if (nt > 1) tierStart[1] until tierStart[2] else IntRange.EMPTY
                entrants(sorted, rankMask, start, size, second, finalists, input.byRating, entered!!, runNo + 1, cl)
            }
            if (titlePlayoff) {
                // Semi-finals 1v4 and 2v3, then the final (division 1 starts at index 0).
                val t1 = (sorted[0] and rankMask).toInt()
                val t2 = (sorted[1] and rankMask).toInt()
                val t3 = (sorted[2] and rankMask).toInt()
                val t4 = (sorted[3] and rankMask).toInt()
                val s1 = e(t1, t4)
                val s2 = e(t2, t3)
                title[t1] += s1 * (s2 * e(t1, t2) + (1 - s2) * e(t1, t3))
                title[t4] += (1 - s1) * (s2 * e(t4, t2) + (1 - s2) * e(t4, t3))
                title[t2] += s2 * (s1 * e(t2, t1) + (1 - s1) * e(t2, t4))
                title[t3] += (1 - s2) * (s1 * e(t3, t1) + (1 - s1) * e(t3, t4))
            }
            // A linear league's ties: the next place on each side.
            for (t in 0 until nt - 1) {
                if (!input.playoffBelow[t] || groupsIn[t] * groupsIn[t + 1] > 1) continue
                val d = tierStart[t]
                if (size[d] - swapBelow[t] - 1 < 0 || swapBelow[t] >= size[d + 1]) continue
                val hi = start[d] + (sorted[start[d] + size[d] - swapBelow[t] - 1] and rankMask).toInt()
                val lo = start[d + 1] + (sorted[start[d + 1] + swapBelow[t]] and rankMask).toInt()
                val s = e(lo, hi)
                upSpot[lo]++
                downSpot[hi]++
                upShare[lo] += s
                downShare[hi] += s
            }
        }

        val runsD = r.toDouble()
        val rows = ArrayList<AiOdds>(n)
        for (d in 0 until nd) {
            for ((place, g) in input.table[d].withIndex()) {
                val key = input.keys[g]
                val lead = leader[g] / runsD
                var x = AiOdds(
                    key, d, place + 1, positionSum[g] / runsD + 1, lead,
                    if (d == 0 && titlePlayoff) title[g] / runsD else lead,
                    top[g] / runsD, cl[g] / runsD,
                    (up[g] + upShare[g]) / runsD, (down[g] + downShare[g]) / runsD,
                    upSpot[g] / runsD, downSpot[g] / runsD,
                )
                // Playoffs already over: their winners are no longer a chance.
                if (d == 0 && input.champion != null) x = x.copy(title = if (key == input.champion) 1.0 else 0.0)
                for ((spot, wentUp) in input.decided) {
                    if (key == spot.lower) x = x.copy(promotion = if (wentUp) 1.0 else 0.0)
                    if (key == spot.upper) x = x.copy(relegation = if (wentUp) 1.0 else 0.0)
                }
                rows += x
            }
        }
        return OddsTable(input.season, input.played, input.total, r, input.drawRate, rows)
    }

    /**
     * The upper groups' players from place [from] up (from the bottom: 0 is each group's last),
     * worst first within a place by [worseFirst], [count] of them (global indices); one run's
     * sorted tables.
     */
    private fun nextUp(
        sorted: LongArray,
        start: IntArray,
        size: IntArray,
        firstDiv: Int,
        endDiv: Int,
        from: Int,
        count: Int,
        rankMask: Long,
        worseFirst: Comparator<Pair<Int, Int>>,
    ): IntArray {
        val out = IntArray(count)
        var got = 0
        var p = from
        val most = (firstDiv until endDiv).maxOf { size[it] }
        while (got < count && p < most) {
            // The p-th from the bottom of each group, worst first.
            val level = (firstDiv until endDiv).filter { size[it] > p }.map { d -> start[d] + (sorted[start[d] + size[d] - 1 - p] and rankMask).toInt() to d }
                .sortedWith(worseFirst)
            for ((g, _) in level) {
                if (got >= count) break
                out[got++] = g
            }
            p++
        }
        return out.copyOf(got)
    }

    /**
     * One run's champions league entrants by [ChampionsLeague.entrants]' rule, counted in [cl]:
     * the 1st division's top 12 and the 2nd division's top 2, the cup [finalists], then the 1st
     * division's next places (a place taken twice passes down), then by rating, until 16.
     * [entered] marks this run's entrants with [stamp].
     */
    private fun entrants(
        sorted: LongArray,
        rankMask: Long,
        start: IntArray,
        size: IntArray,
        second: IntRange,
        finalists: IntArray,
        byRating: IntArray,
        entered: IntArray,
        stamp: Int,
        cl: IntArray,
    ) {
        fun enter(g: Int): Int {
            if (g < 0 || entered[g] == stamp) return 0
            entered[g] = stamp
            cl[g]++
            return 1
        }
        val first = size[0]
        var count = 0
        // Division 1 starts at index 0, so its sorted entries hold global indices.
        for (pos in 0 until minOf(CL_FIRST, first)) count += enter((sorted[pos] and rankMask).toInt())
        val each = ChampionsLeague.secondPlaces(second.count())
        for (d in second) for (pos in 0 until minOf(each, size[d])) count += enter(start[d] + (sorted[start[d] + pos] and rankMask).toInt())
        for (g in finalists) count += enter(g)
        var pos = CL_FIRST
        while (count < ChampionsLeague.SIZE && pos < first) count += enter((sorted[pos++] and rankMask).toInt())
        var i = 0
        while (count < ChampionsLeague.SIZE && i < byRating.size) count += enter(byRating[i++])
    }
}

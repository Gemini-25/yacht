package yacht.engine.league

import java.util.Locale
import kotlin.math.pow

/**
 * Money of the club world (design ENG 1.1) in whole tenths of 억 (1 = 1천만 원), so every sum
 * is exact and a file holds plain integers. Signed: a club's balance can be negative.
 */
object Money {
    /** Units in one 억. */
    const val PER_EOK = 10

    /** [eok] 억 in units, rounded half up. */
    fun of(eok: Double): Int = Math.round(eok * PER_EOK).toInt()

    /** Units [m] in 억. */
    fun eok(m: Int): Double = m / PER_EOK.toDouble()

    /**
     * "12.3억", "-4.5억", "0.8억"; from 1000억 on whole 억 with thousands separators ("1,240억").
     * Every amount ends in 억, so a particle after it is always 을 / 이에요.
     */
    fun text(m: Int): String {
        val sign = if (m < 0) "-" else ""
        val a = Math.abs(m.toLong())
        if (a >= 1000L * PER_EOK) return sign + String.format(Locale.ROOT, "%,d", (a + PER_EOK / 2) / PER_EOK) + "억"
        return "$sign${a / PER_EOK}.${a % PER_EOK}억"
    }
}

/**
 * What players are worth and what they ask (design ENG 1.1, 3.2). All amounts in [Money] units.
 * The value doubles every [DOUBLING] rating points of a rating re-anchored on the league's median
 * ([ANCHOR]), so prices neither drift with the ladder's anchor nor with the meta.
 */
object Valuation {
    /** The rating a value is anchored at: the probe league's median of alive generated players. */
    const val ANCHOR = 1482.0

    /** The rating worth [BASE] at full age factor. */
    const val PIVOT = 1450.0
    const val DOUBLING = 100.0

    /** 2억 at [PIVOT]. */
    const val BASE = 2.0

    /** Values are clamped to 0.2억..300억. */
    const val MIN_VALUE = 2
    const val MAX_VALUE = 3000

    /** The least a seller asks: 0.5억. */
    const val MIN_ASKED = 5

    /** Growth room's lift for the young (age ≤ [YOUNG]): 4% a point, at most [ROOM_CAP]. */
    const val YOUNG = 24
    const val ROOM_STEP = 0.04
    const val ROOM_CAP = 1.6

    /** Seller factors: a rival buyer, a seller in 재정 위기, a 팬 소유 seller, a 투자 펀드 seller. */
    const val RIVAL = 1.5
    const val TROUBLE = 0.8
    const val FANS_SELLER = 1.15
    const val FUND_SELLER = 0.9

    /** The age factor A(age) (1.0 for an unknown age). */
    fun ageFactor(age: Int?): Double = when {
        age == null -> 1.0
        age <= 20 -> 1.25
        age <= 23 -> 1.20
        age <= 27 -> 1.10
        age <= 29 -> 1.00
        age <= 31 -> 0.85
        age <= 33 -> 0.65
        age <= 35 -> 0.45
        else -> 0.30
    }

    /** The growth room's factor Y: `min(1.6, 1 + 0.04 × max(0, potential − overall))` up to age 24, else 1. */
    fun roomFactor(age: Int?, potential: Int?, overall: Int?): Double {
        if (age == null || age > YOUNG || potential == null || overall == null) return 1.0
        return minOf(ROOM_CAP, 1 + ROOM_STEP * maxOf(0, potential - overall))
    }

    /**
     * Market value (시장 가치) of a member rated [rating] (the ladder's; a prior when unrated, NaN
     * read as the anchor) when the league's median is [anchor]: `2억 × 2^((r' − 1450) / 100) × A(age) × Y`
     * with `r' = rating − anchor + 1482`, clamped to 0.2억..300억. Base AIs, the guest and made
     * players without an identity pass nulls (A = Y = 1).
     */
    fun value(rating: Double, anchor: Double, age: Int?, potential: Int?, overall: Int?): Int {
        val r = if (rating.isNaN()) ANCHOR else rating - (if (anchor.isNaN()) ANCHOR else anchor) + ANCHOR
        val eok = BASE * 2.0.pow((r - PIVOT) / DOUBLING) * ageFactor(age) * roomFactor(age, potential, overall)
        return Money.of(eok).coerceIn(MIN_VALUE, MAX_VALUE)
    }

    /** The wage a value asks for before the league's and the club's factors: 0.35억 + 0.13 × value. */
    fun baseWage(value: Int): Int = Math.round(3.5 + 0.13 * value).toInt()

    /** The years-left factor T: 0.85 in the last year (or none), 1.00 with 2, 1.15 with 3, 1.30 with 4 or more. */
    fun yearsFactor(yearsLeft: Int): Double = when {
        yearsLeft <= 1 -> 0.85
        yearsLeft == 2 -> 1.00
        yearsLeft == 3 -> 1.15
        else -> 1.30
    }

    /**
     * What a seller asks for a member of [value] with [yearsLeft] seasons of contract: value × T ×
     * S, S = 1.5 for a [rival] buyer, 0.8 for a seller in 재정 위기, 1.15 for a 팬 소유 seller and 0.9
     * for a 투자 펀드 one; never below 0.5억. A release clause caps it (the caller's rule, ENG 1b.8).
     */
    fun asked(value: Int, yearsLeft: Int, rival: Boolean, sellerInTrouble: Boolean, owner: OwnerType): Int {
        var x = value * yearsFactor(yearsLeft)
        if (rival) x *= RIVAL
        if (sellerInTrouble) x *= TROUBLE
        when (owner) {
            OwnerType.FANS -> x *= FANS_SELLER
            OwnerType.FUND -> x *= FUND_SELLER
            else -> {}
        }
        return maxOf(MIN_ASKED, Math.round(x).toInt())
    }

    /** A release clause at signing: 2.0..3.0 × [value], drawn from [rng] (one draw). */
    fun clause(value: Int, rng: java.util.Random): Int = Math.round(value * (2.0 + rng.nextDouble())).toInt()
}

/**
 * The money multipliers of each league for a season (design ENG 1.1): the revenue multiplier λ
 * from the league ranking the season was played under, and the wage index ω the club book keeps
 * (its `cw` line). A league it does not know has λ = ω = 1.
 */
class LeagueEconomy(
    /** League id → λ (1.6 for the best-ranked down to 0.6 for the last). */
    val lambda: Map<String, Double>,
    /** League id → ω (0.5..2.0). */
    val omega: Map<String, Double>,
) {
    fun lambdaOf(league: String): Double = lambda[league] ?: 1.0

    fun omegaOf(league: String): Double = omega[league] ?: 1.0

    companion object {
        /** Every league at λ = ω = 1 (a new league's founding, ENG 8.2). */
        val NEUTRAL = LeagueEconomy(emptyMap(), emptyMap())

        /** λ's range: the best-ranked league and the last. */
        const val LAMBDA_TOP = 1.6
        const val LAMBDA_SPAN = 1.0

        /** The share of its revenue a league's wage bill is pulled toward, and ω's range. */
        const val WAGE_SHARE = 0.66
        const val OMEGA_MIN = 0.5
        const val OMEGA_MAX = 2.0

        /** λ of the league ranked [rank] (0 = best) of [n]: `1.6 − 1.0 × rank / (n − 1)`; 1.0 with one league. */
        fun lambda(rank: Int, n: Int): Double = if (n <= 1) 1.0 else LAMBDA_TOP - LAMBDA_SPAN * rank.coerceIn(0, n - 1) / (n - 1)

        /**
         * ω after a close: `clamp(ω × (0.66 × revenue / wages)^0.5, 0.5, 2.0)` over the league's
         * clubs; unchanged without wages (nothing to pull).
         */
        fun nextOmega(omega: Double, revenue: Int, wages: Int): Double {
            if (wages <= 0) return omega.coerceIn(OMEGA_MIN, OMEGA_MAX)
            return (omega * Math.sqrt(WAGE_SHARE * maxOf(0, revenue) / wages)).coerceIn(OMEGA_MIN, OMEGA_MAX)
        }

        /**
         * The economy of [plan]'s season: λ by the leagues' order in the ranking it was played
         * under ([SeasonPlan.ranking]; the leagues it has no row for by strength, as the club tables
         * order them), and ω from [wageIndex] (league → ω × 100, the `cw` line).
         */
        fun of(plan: SeasonPlan, rating: (String) -> Double, wageIndex: Map<String, Int> = emptyMap()): LeagueEconomy {
            val season = plan.league
            val ids = season.leagues.list.map { it.id }
            val order = Coefficients.order(ids, plan.ranking) { id ->
                Coefficients.strength(season.leagues.byId(id)?.divisions?.flatMap { season.divisions[it] }.orEmpty(), rating)
            }
            val lambda = order.withIndex().associate { (i, id) -> id to lambda(i, order.size) }
            return LeagueEconomy(lambda, wageIndex.mapValues { it.value / 100.0 })
        }
    }
}

/**
 * Expected points from ratings (design ENG 2.2), shared by the board (2.2), the power ranking
 * (5.1), the market's tick (3.1) and the press votes (5.4): the Bradley-Terry share of [Odds]
 * with its draw and sweep rates.
 */
object Expectation {
    /**
     * Expected league points of a against b rated [ra] and [rb]: `3 pW + pD + bonus × pW × sweepRate`
     * where p = `1 / (1 + 10^((rb − ra) / 400))`, the draws `pD = min(drawRate, 2 min(p, 1 − p))` take
     * half their share from each side (`pW = p − pD / 2`), as in [Odds]' runs; [bonus] is 1 when a
     * 2-0 sweep earns a point (판 승점제's 완승 보너스), else 0. An unrated side is read at
     * [LeagueWorld.START_RATING].
     */
    fun points(ra: Double, rb: Double, drawRate: Double, sweepRate: Double, bonus: Int): Double {
        val a = ra.takeIf { it.isFinite() } ?: LeagueWorld.START_RATING
        val b = rb.takeIf { it.isFinite() } ?: LeagueWorld.START_RATING
        val p = 1.0 / (1.0 + 10.0.pow((b - a) / 400.0))
        val d = minOf(drawRate.coerceIn(0.0, 1.0), 2 * minOf(p, 1 - p))
        val win = p - d / 2
        return 3 * win + d + bonus * win * sweepRate
    }

    /**
     * Each club of league [league]'s club table with its expected club-table points, best first
     * (ties by id): a member's expected points are [points] summed over its own league fixtures of
     * the whole season from the ratings the season was drawn with ([drawn]), weighted by its tier
     * ([ClubTable.weight]); a club scores its best [ClubTable.COUNTED]. The club's expected place is
     * its index + 1. The rates default to [Odds]' priors (not the season's results so far), so the
     * numbers are the same whenever they are asked, a week closed again after a kill included.
     * Clubs are those [ClubTable.of] lists for the league, members as the book has them now.
     */
    fun clubs(
        plan: SeasonPlan,
        book: ClubBook,
        league: String,
        drawn: (String) -> Double,
        drawRate: Double = Odds.DRAW_PRIOR,
        sweepRate: Double = if (plan.league.bonus) Odds.SWEEP_PRIOR else 0.0,
    ): List<Pair<String, Double>> {
        val season = plan.league
        val lg = season.leagues.byId(league) ?: return emptyList()
        val bonus = if (season.bonus) 1 else 0
        val expected = HashMap<String, Double>()
        for (id in 0 until season.fixturesTotal) {
            val f = season.fixture(id)
            if (f.division !in lg.divisions) continue
            val ra = drawn(f.a)
            val rb = drawn(f.b)
            expected.merge(f.a, points(ra, rb, drawRate, sweepRate, bonus), Double::plus)
            expected.merge(f.b, points(rb, ra, drawRate, sweepRate, bonus), Double::plus)
        }
        val nations = LinkedHashSet<String>()
        for (d in lg.divisions) for (k in season.divisions[d]) book.clubOf(k)?.let { book.nationOf(it) }?.let { nations += it }
        val out = ArrayList<Pair<String, Double>>()
        for (nation in nations) for (c in book.clubsOf(nation)) {
            val scores = c.squad.map { k ->
                val d = season.divisionOf(k)?.takeIf { it in lg.divisions }
                if (d == null) 0.0 else (expected[k] ?: 0.0) * ClubTable.weight(season.leagues.tier(d))
            }.sortedDescending()
            out += c.id to scores.take(ClubTable.COUNTED).sum()
        }
        return out.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenComparing({ it.first }, ClubBook.ID_ORDER))
    }
}

package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.Expectation
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEconomy
import yacht.engine.league.Leagues
import yacht.engine.league.Money
import yacht.engine.league.OwnerType
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.Valuation
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Money, values, wages' base, asked fees, the league multipliers and expected points (design ENG 1.1, 2.2). */
class MoneyTest {
    @Test
    fun moneyIsTenthsOfEokAndReadsAsEok() {
        assertEquals("12.3억", Money.text(123))
        assertEquals("-4.5억", Money.text(-45))
        assertEquals("0.8억", Money.text(8))
        assertEquals("0.0억", Money.text(0))
        assertEquals("999.9억", Money.text(9999))
        assertEquals("1,000억", Money.text(10000))
        assertEquals("1,240억", Money.text(12400))
        assertEquals("1,241억", Money.text(12405))
        assertEquals("-1,240억", Money.text(-12400))
        assertEquals(123, Money.of(12.34))
        assertEquals(-45, Money.of(-4.5))
        assertEquals(12.3, Money.eok(123))
    }

    @Test
    fun aValueDoublesEvery100RatingPointsOnTheAnchoredRating() {
        // At the probe's anchor r' = r; a 28-year-old (A = 1, no growth room): 2억 × 2^((r − 1450) / 100).
        fun v(r: Double) = Valuation.value(r, Valuation.ANCHOR, 28, null, null)
        assertEquals(10, v(1350.0))
        assertEquals(20, v(1450.0))
        assertEquals(28, v(1500.0))
        assertEquals(80, v(1650.0))
        assertEquals(190, v(1775.0))
        // The age bands at 1450.
        val bands = mapOf(19 to 25, 20 to 25, 21 to 24, 23 to 24, 24 to 22, 27 to 22, 28 to 20, 29 to 20, 30 to 17, 31 to 17, 32 to 13, 33 to 13, 34 to 9, 35 to 9, 36 to 6, 40 to 6)
        for ((age, value) in bands) assertEquals(value, Valuation.value(1450.0, Valuation.ANCHOR, age, null, null), "age $age")
        assertEquals(20, Valuation.value(1450.0, Valuation.ANCHOR, null, 80, 50), "an unknown age: A = Y = 1")
        // Growth room up to 24: 4% a point, at most 1.6.
        assertEquals(29, Valuation.value(1450.0, Valuation.ANCHOR, 22, 65, 60), "2 × 1.2 × 1.2 = 2.88")
        assertEquals(38, Valuation.value(1450.0, Valuation.ANCHOR, 22, 90, 60), "Y capped: 2 × 1.2 × 1.6 = 3.84")
        assertEquals(24, Valuation.value(1450.0, Valuation.ANCHOR, 22, 50, 60), "no room below the overall")
        assertEquals(22, Valuation.value(1450.0, Valuation.ANCHOR, 25, 90, 60), "no room past 24")
        // The rating and the anchor moved together give the same value; NaN reads as the anchor.
        assertEquals(Valuation.value(1482.0, 1482.0, 28, null, null), Valuation.value(1600.0, 1600.0, 28, null, null))
        assertEquals(25, Valuation.value(1600.0, 1600.0, 28, null, null))
        assertEquals(25, Valuation.value(Double.NaN, 1530.0, 28, null, null))
        assertEquals(Valuation.value(1550.0, 1532.0, 28, null, null), v(1500.0))
        // Clamped to 0.2억..300억.
        assertEquals(Valuation.MIN_VALUE, v(700.0))
        assertEquals(Valuation.MAX_VALUE, v(2600.0))
    }

    @Test
    fun wagesFeesAndClauses() {
        assertEquals(6, Valuation.baseWage(20), "0.35 + 0.13 × 2.0 = 0.61억")
        assertEquals(17, Valuation.baseWage(100), "0.35 + 1.3 = 1.65억, rounded up")
        assertEquals(4, Valuation.baseWage(0))
        val asked = { years: Int -> Valuation.asked(100, years, false, false, OwnerType.LOCAL) }
        assertEquals(listOf(85, 85, 100, 115, 130, 130), listOf(0, 1, 2, 3, 4, 6).map(asked))
        assertEquals(150, Valuation.asked(100, 2, true, false, OwnerType.LOCAL))
        assertEquals(80, Valuation.asked(100, 2, false, true, OwnerType.LOCAL))
        assertEquals(115, Valuation.asked(100, 2, false, false, OwnerType.FANS))
        assertEquals(90, Valuation.asked(100, 2, false, false, OwnerType.FUND))
        for (o in listOf(OwnerType.TYCOON, OwnerType.STATE)) assertEquals(100, Valuation.asked(100, 2, false, false, o), o.name)
        assertEquals(120, Valuation.asked(100, 2, true, true, OwnerType.LOCAL))
        assertEquals(Valuation.MIN_ASKED, Valuation.asked(2, 1, false, false, OwnerType.LOCAL), "never below 0.5억")
        val rng = java.util.Random(7)
        val clauses = List(500) { Valuation.clause(100, rng) }
        assertTrue(clauses.all { it in 200..300 }, clauses.toString())
        assertTrue(clauses.min() < 210 && clauses.max() > 290)
        assertEquals(1, List(5) { Valuation.clause(80, java.util.Random(3)) }.distinct().size, "deterministic for the stream")
    }

    @Test
    fun theLeagueMultipliers() {
        assertEquals(1.6, LeagueEconomy.lambda(0, 28))
        assertEquals(0.6, LeagueEconomy.lambda(27, 28), 1e-12)
        assertEquals(1.6 - 2.0 / 27, LeagueEconomy.lambda(2, 28), 1e-12)
        assertEquals(1.1, LeagueEconomy.lambda(1, 3), 1e-12)
        assertEquals(1.0, LeagueEconomy.lambda(0, 1))
        // ω pulls a league's wage bill toward 66% of its revenue, within 0.5..2.0.
        assertEquals(1.0, LeagueEconomy.nextOmega(1.0, 1000, 660), 1e-12)
        assertEquals(Math.sqrt(2.0), LeagueEconomy.nextOmega(1.0, 1000, 330), 1e-12)
        assertEquals(0.8 * Math.sqrt(0.66 * 100 / 70), LeagueEconomy.nextOmega(0.8, 100, 70), 1e-12)
        assertEquals(2.0, LeagueEconomy.nextOmega(1.9, 10_000, 100))
        assertEquals(0.5, LeagueEconomy.nextOmega(0.55, 100, 10_000))
        assertEquals(1.3, LeagueEconomy.nextOmega(1.3, 500, 0), "no wages: unchanged")
        assertEquals(0.5, LeagueEconomy.nextOmega(0.8, -50, 400), "no revenue: the floor")
        // A season's economy: λ by the leagues' order (no ranking: by strength), ω from the wage index.
        val keys = listOf("a1", "a2", "b1", "b2", "c1", "c2")
        val s = Season.start(1, 4L, keys.chunked(2), false, Leagues.national(listOf("KR", "JP", "CN"), listOf(1, 1, 1)))
        val rating = mapOf("a1" to 1500.0, "a2" to 1500.0, "b1" to 1700.0, "b2" to 1650.0, "c1" to 1300.0, "c2" to 1310.0)
        val e = LeagueEconomy.of(SeasonPlan(s, LeagueConfig()), { rating.getValue(it) }, mapOf("KR" to 120))
        assertEquals(1.6, e.lambdaOf("JP"))
        assertEquals(1.1, e.lambdaOf("KR"), 1e-12)
        assertEquals(0.6, e.lambdaOf("CN"), 1e-12)
        assertEquals(1.0, e.lambdaOf("US"))
        assertEquals(1.2, e.omegaOf("KR"))
        assertEquals(1.0, e.omegaOf("JP"))
        assertEquals(1.0, LeagueEconomy.NEUTRAL.lambdaOf("KR"))
    }

    @Test
    fun expectedPointsAreOddsBradleyTerryShare() {
        // Odds' runs: A wins with p − d/2, draws d = min(drawRate, 2 min(p, 1 − p)); a win is a sweep the sweep share of the time.
        fun odds(ra: Double, rb: Double, draw: Double, sweep: Double, bonus: Int): Double {
            val sa = 10.0.pow((ra - 1500) / 400)
            val sb = 10.0.pow((rb - 1500) / 400)
            val p = sa / (sa + sb)
            val d = minOf(draw, 2 * minOf(p, 1 - p))
            val win = p - d / 2
            return 3 * win + d + bonus * win * sweep
        }
        for ((ra, rb, draw) in listOf(Triple(1500.0, 1500.0, 0.02), Triple(1620.0, 1410.0, 0.05), Triple(1300.0, 1900.0, 0.3))) {
            for (bonus in 0..1) {
                val e = Expectation.points(ra, rb, draw, 0.5, bonus)
                assertEquals(odds(ra, rb, draw, 0.5, bonus), e, 1e-12, "$ra $rb $draw $bonus")
            }
        }
        assertEquals(1.735, Expectation.points(1500.0, 1500.0, 0.02, 0.5, 1), 1e-12)
        // The two sides share 2 + p_win points of a decided match's 3.
        val ab = Expectation.points(1600.0, 1450.0, 0.04, 0.0, 0)
        val ba = Expectation.points(1450.0, 1600.0, 0.04, 0.0, 0)
        assertEquals(3 * (1 - 0.04) + 2 * 0.04, ab + ba, 1e-12)
        // An unrated side is read at the start rating.
        assertEquals(Expectation.points(1400.0, 1500.0, 0.02, 0.5, 1), Expectation.points(Double.NaN, 1500.0, 0.02, 0.5, 1))
    }

    @Test
    fun eachClubsExpectedPointsAreItsBestSixWeightedByTier() {
        // One league of two divisions; c01 holds three 1부 players and two 2부 ones, c02 the rest.
        val d1 = listOf("a", "b", "c", "x", "y", "z")
        val d2 = listOf("d", "e", "u", "v", "w", "q")
        val s = Season.start(1, 9L, listOf(d1, d2), true, Leagues.national(listOf("KR"), listOf(2)))
        val plan = SeasonPlan(s, LeagueConfig())
        val book = ClubBook.decode(
            "clubs 2\nmeta 1 0 - 100\n" +
                "club c01 KR 도시 c01 도시%20c01 0 ffc62828 ffffffff 가 100 0 1 a,b,c,d,e\n" +
                "club c02 KR 도시 c02 도시%20c02 0 ffc62828 ffffffff 가 100 0 1 x,y,z,u,v,w,q\n",
        )
        val drawn = mapOf("a" to 1700.0, "b" to 1600.0, "c" to 1550.0, "x" to 1500.0, "y" to 1450.0, "z" to 1400.0, "d" to 1500.0, "e" to 1480.0, "u" to 1400.0, "v" to 1380.0, "w" to 1350.0, "q" to 1300.0)
        val table = Expectation.clubs(plan, book, "KR", { drawn.getValue(it) })
        assertEquals(listOf("c01", "c02"), table.map { it.first })
        // By hand: every member's expected points over its own fixtures, times its tier's weight.
        val sweep = if (s.bonus) yacht.engine.league.Odds.SWEEP_PRIOR else 0.0
        val bonus = if (s.bonus) 1 else 0
        val each = HashMap<String, Double>()
        for (id in 0 until s.fixturesTotal) {
            val f = s.fixture(id)
            each.merge(f.a, Expectation.points(drawn.getValue(f.a), drawn.getValue(f.b), yacht.engine.league.Odds.DRAW_PRIOR, sweep, bonus), Double::plus)
            each.merge(f.b, Expectation.points(drawn.getValue(f.b), drawn.getValue(f.a), yacht.engine.league.Odds.DRAW_PRIOR, sweep, bonus), Double::plus)
        }
        fun score(k: String) = each.getValue(k) * (if (k in d1) 1.0 else 0.6)
        assertEquals(listOf("a", "b", "c", "d", "e").sumOf(::score), table[0].second, 1e-9)
        assertEquals(listOf("x", "y", "z", "u", "v", "w", "q").map(::score).sortedDescending().take(6).sum(), table[1].second, 1e-9)
        assertTrue(abs(table[0].second - table[1].second) > 1)
        // Asked again: the same numbers (the priors, not the results so far).
        assertEquals(table, Expectation.clubs(plan, book, "KR", { drawn.getValue(it) }))
        assertEquals(emptyList(), Expectation.clubs(plan, book, "JP", { drawn.getValue(it) }))
    }
}

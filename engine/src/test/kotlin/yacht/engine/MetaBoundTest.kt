package yacht.engine

import yacht.engine.league.NewcomerMeta
import yacht.engine.league.PlayRecipe
import yacht.engine.league.PlayStyles
import yacht.engine.league.PlayerGenerator
import yacht.engine.league.PlayerGenerator.Tier
import yacht.engine.league.Schools
import yacht.engine.league.StyleFamily
import java.util.Locale
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The meta weights only reshuffle the styles a newcomer's potential could take anyway (design ENG
 * 1b.11): for every weight vector at the 256 corners of [0.6, 1.8]^8, the newcomers' mean style
 * average of each tier (a primary's [PlayerGenerator.avgAt] at its intensity, k log-uniform on
 * 0.7..1.7 integrated on a 60-point grid as the generator rounds it; a second style's at half
 * strength, drawn [PlayerGenerator.BLEND] of the time among those [PlayerGenerator.secondaryFor]
 * allows) stays within the bounds computed here and pinned, and a newcomer's average by the tier
 * shares moves at most −1.7 / +1.3 points (about −10 / +8 rating points). The integration is
 * checked against the generator's own draws.
 */
class MetaBoundTest {
    private val ks: List<Double> = List(60) { i -> PlayerGenerator.round(exp(ln(0.7) + (ln(1.7) - ln(0.7)) * (i + 0.5) / 60), 2) }

    private fun favour(r: PlayRecipe, w: Map<StyleFamily, Double>) = 1 + ((w[StyleFamily.of(r.key)] ?: 1.0) - 1).coerceAtLeast(-0.9)

    /** Each tier's recipes it may take up at each k of the grid, and the second styles open to each primary (they do not depend on the weights). */
    private val eligible: Map<Tier, List<List<PlayRecipe>>> = Tier.entries.associateWith { tier ->
        ks.map { k0 -> PlayStyles.RECIPES.filter { PlayerGenerator.avgAt(it, k0) >= tier.minAvg && PlayerGenerator.weight(it.key, tier) > 0 }.ifEmpty { PlayStyles.RECIPES.take(1) } }
    }
    private val secondsOf: Map<Tier, Map<String, List<PlayRecipe>>> = Tier.entries.associateWith { tier ->
        PlayStyles.RECIPES.associate { r -> r.key to PlayStyles.RECIPES.filter { it.key != r.key && PlayerGenerator.secondaryFor(it, tier) } }
    }

    /** A tier's mean primary average and mean second-style average (at half strength) under weights [w]. */
    private fun means(tier: Tier, w: Map<StyleFamily, Double>): Pair<Double, Double> {
        var primary = 0.0
        var second = 0.0
        for ((i, k0) in ks.withIndex()) {
            val open = eligible.getValue(tier)[i]
            val weights = open.map { PlayerGenerator.weight(it.key, tier) * favour(it, w) }
            val total = weights.sum()
            for ((r, x) in open.zip(weights)) {
                val p = x / total
                primary += p * PlayerGenerator.avgAt(r, if (r.scales) k0 else 1.0)
                val seconds = secondsOf.getValue(tier).getValue(r.key)
                val sw = seconds.map { favour(it, w) }
                val st = sw.sum()
                second += p * seconds.zip(sw).sumOf { (s, y) -> y / st * PlayerGenerator.avgAt(s, 0.5) }
            }
        }
        return primary / ks.size to second / ks.size
    }

    /** Each tier's share of newcomers: potential N(64, 13) rounded and clamped to 25..97. */
    private fun tierShares(): Map<Tier, Double> {
        // The normal distribution function by a midpoint sum of its density.
        fun phi(z: Double): Double {
            var s = 0.0
            val n = 4000
            val lo = -10.0
            val h = (z - lo) / n
            for (i in 0 until n) {
                val x = lo + (i + 0.5) * h
                s += exp(-x * x / 2)
            }
            return s * h / Math.sqrt(2 * Math.PI)
        }
        fun below(p: Int) = phi((p - 0.5 - PlayerGenerator.POTENTIAL_MEAN) / PlayerGenerator.POTENTIAL_SD)
        return mapOf(
            Tier.TOP to 1 - below(75),
            Tier.MID to below(75) - below(58),
            Tier.LOW to below(58) - below(42),
            Tier.NOVICE to below(42),
        )
    }

    private val corners: List<Map<StyleFamily, Double>> = (0 until 256).map { m -> StyleFamily.entries.associateWith { f -> if ((m shr f.ordinal) and 1 == 1) 1.8 else 0.6 } }

    /**
     * Pinned: each tier's mean primary average at every weight 1.0, its least and most over the
     * corners, then the same of the second style (design ENG 1b.11 computed the primaries as TOP
     * 185.08 → 184.09..186.06, MID 180.39 → 179.13..181.29, LOW 178.69 → 176.44..180.28, NOVICE
     * 165.24 → 159.30..169.90).
     */
    private val pinned: Map<Tier, List<Double>> = mapOf(
        Tier.TOP to listOf(185.070, 184.069, 186.050, 186.542, 185.986, 187.100),
        Tier.MID to listOf(180.404, 179.160, 181.300, 184.381, 183.523, 184.937),
        Tier.LOW to listOf(178.687, 176.437, 180.281, 184.407, 183.614, 184.946),
        Tier.NOVICE to listOf(165.242, 159.300, 169.895, 184.405, 183.593, 184.952),
    )

    @Test
    fun theNewcomersStyleAverageStaysWithinItsBoundsAtEveryCorner() {
        val ones = StyleFamily.entries.associateWith { 1.0 }
        val shares = tierShares()
        assertEquals(listOf(0.21, 0.48, 0.27, 0.04), Tier.entries.map { Math.round(shares.getValue(it) * 100) / 100.0 })
        var lowShift = 0.0
        var highShift = 0.0
        for (t in Tier.entries) {
            val (b1, b2) = means(t, ones)
            val all = corners.map { means(t, it) }
            val p = all.map { it.first }
            val s = all.map { it.second }
            println(String.format(Locale.ROOT, "%s share %.4f primary %.3f (%.3f..%.3f) second %.3f (%.3f..%.3f)", t, shares.getValue(t), b1, p.min(), p.max(), b2, s.min(), s.max()))
            lowShift += shares.getValue(t) * (p.min() - b1)
            highShift += shares.getValue(t) * (p.max() - b1)
            val pin = pinned.getValue(t)
            assertEquals(pin[0], b1, 0.005, "$t base")
            assertEquals(pin[1], p.min(), 0.005, "$t primary min")
            assertEquals(pin[2], p.max(), 0.005, "$t primary max")
            assertEquals(pin[3], b2, 0.005, "$t second base")
            assertEquals(pin[4], s.min(), 0.005, "$t second min")
            assertEquals(pin[5], s.max(), 0.005, "$t second max")
        }
        println(String.format(Locale.ROOT, "newcomer shift %.3f .. %.3f", lowShift, highShift))
        assertTrue(lowShift >= -1.7 && highShift <= 1.3, "a newcomer moves at most −1.7 / +1.3 points: $lowShift .. $highShift")
    }

    @Test
    fun theIntegrationMatchesTheGeneratorsDraws() {
        // Every weight at a corner: the generator's own draws (a school without favourites) against the integral.
        val w = corners[0b1010_0110]
        val meta = NewcomerMeta(w).forNation("??", emptyMap())
        val school = Schools.of("??")
        for ((t, potential) in listOf(Tier.TOP to 80, Tier.MID to 65, Tier.LOW to 50, Tier.NOVICE to 35)) {
            val rng = Random(17L + potential)
            val n = 20_000
            var primary = 0.0
            var second = 0.0
            var seconds = 0
            repeat(n) {
                val g = PlayerGenerator.style(rng, potential, school, meta)
                val r = PlayStyles.recipe(g.primary)!!
                primary += PlayerGenerator.avgAt(r, if (r.scales) g.k else 1.0)
                g.secondary?.let { s ->
                    second += PlayerGenerator.avgAt(PlayStyles.recipe(s)!!, 0.5)
                    seconds++
                }
            }
            val (p, s) = means(t, w)
            assertEquals(p, primary / n, 0.25, "$t primary")
            assertEquals(s, second / seconds, 0.25, "$t second")
            assertEquals(PlayerGenerator.BLEND, seconds.toDouble() / n, 0.015, "$t blend")
        }
    }
}

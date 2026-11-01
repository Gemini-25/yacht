package yacht.engine

import yacht.engine.league.Archive
import yacht.engine.league.CareerClubs
import yacht.engine.league.CounterStyle
import yacht.engine.league.Evolution
import yacht.engine.league.EvolutionResult
import yacht.engine.league.Expectation
import yacht.engine.league.FamilyInput
import yacht.engine.league.FamilyMatchup
import yacht.engine.league.FamilyStats
import yacht.engine.league.GameScore
import yacht.engine.league.Honour
import yacht.engine.league.HonourBook
import yacht.engine.league.Imitation
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Leagues
import yacht.engine.league.Meta
import yacht.engine.league.NewcomerMeta
import yacht.engine.league.Odds
import yacht.engine.league.PlayStyles
import yacht.engine.league.PlayerBook
import yacht.engine.league.PlayerGenerator
import yacht.engine.league.PlayerRecord
import yacht.engine.league.Season
import yacht.engine.league.SeasonLine
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StatsBook
import yacht.engine.league.StyleCap
import yacht.engine.league.StyleFamily
import yacht.engine.league.StyleFamily.BOLD
import yacht.engine.league.StyleFamily.BONUS
import yacht.engine.league.StyleFamily.CAUTION
import yacht.engine.league.StyleFamily.COMBO
import yacht.engine.league.StyleFamily.EV
import yacht.engine.league.StyleFamily.MIND
import yacht.engine.league.StyleFamily.ODD
import yacht.engine.league.StyleFamily.YACHT
import yacht.engine.league.WorldBook
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The meta's evolution (design ENG 4.1-4.4): the families of the 25 recipes and their neutral
 * shares, what a close measures (`fm`), the newcomer weights' update with its decay, rescaling,
 * relative saturation, counter rule and clamp, the close's guard and its news, the newcomers'
 * meta map, the relative style cap, imitation and the roster's `im=`/`lc=` tokens, and the two
 * goldens: every weight 1.0 with the cap off is today's evolution byte for byte; with the default
 * cap it binds rarely and changes only the newcomers of the seasons it binds.
 */
class MetaTest {
    // ---- families -----------------------------------------------------------------------------

    @Test
    fun everyRecipeHasOneFamilyAndTheNeutralSharesArePinned() {
        assertEquals(PlayStyles.RECIPES.map { it.key }.toSet(), StyleFamily.RECIPES.keys)
        assertEquals(25, StyleFamily.RECIPES.size)
        for (r in PlayStyles.RECIPES) assertEquals(StyleFamily.RECIPES.getValue(r.key), StyleFamily.of(r.key), r.key)
        assertEquals(mapOf(EV to 1, YACHT to 3, COMBO to 5, BONUS to 3, CAUTION to 4, BOLD to 3, MIND to 2, ODD to 4), StyleFamily.RECIPES.values.groupingBy { it }.eachCount())
        assertEquals(ODD, StyleFamily.of("someday"), "a recipe of a newer version")
        assertNull(StyleFamily.of(null as yacht.engine.league.PlayGene?))
        val pinned = listOf(0.058839, 0.197799, 0.284906, 0.107184, 0.172113, 0.080504, 0.041829, 0.056826)
        assertEquals(pinned, StyleFamily.entries.map { StyleFamily.NEUTRAL.getValue(it) })
        assertEquals(1.0, StyleFamily.NEUTRAL.values.sum(), 1e-9)
        assertEquals("보너스파의 시대", BONUS.era)
    }

    // ---- the goldens (ENG 4.3) ------------------------------------------------------------------

    /** 1000 generated players and [seasons] seasons of evolution (seed 5, ratings from the abilities), world-champion fashions in seasons 3, 8 and 14. */
    private fun evolve(seasons: Int = 20, each: (PlayerBook, Int) -> Unit = { _, _ -> }, run: (PlayerBook, Int, (String) -> Double, String?) -> EvolutionResult): PlayerBook {
        val book = PlayerBook().apply { ensureGenerated(1000) }
        val fashions = mapOf(3 to "hunter", 8 to "purist", 14 to "timid")
        for (s in 1..seasons) {
            val r = book.alive.associate { p ->
                val id = p.identity
                p.key to if (id == null) Double.NaN else 1100.0 + 6.0 * id.abilities.overall + (Math.floorMod(DiceStream.mix(p.key.hashCode().toLong(), s.toLong()), 200L) - 100)
            }
            run(book, s, { r[it] ?: Double.NaN }, fashions[s])
            each(book, s)
        }
        return book
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private val ones = NewcomerMeta(StyleFamily.entries.associateWith { 1.0 })

    @Test
    fun everyWeightAtOneWithTheCapOffIsTodaysEvolution() {
        // Measured on 3ce4b93's engine sources (the same run, before the meta existed).
        val golden = "56d4421f79084d129f6c74f6a78629494f36810d69f8cb2a735255f783287e02"
        assertEquals(golden, sha(evolve { b, s, r, f -> Evolution.run(b, s, r, { 0 }, 5L, 1000, fashion = f) }.encode()))
        assertEquals(golden, sha(evolve { b, s, r, f -> Evolution.run(b, s, r, { 0 }, 5L, 1000, fashion = f, metaWeights = ones, styleCap = null) }.encode()))
        // The newcomers' map is the fashion itself.
        val fashion = mapOf("hunter" to 0.6, "timid" to 0.22)
        assertEquals(fashion, ones.forNation("KR", fashion))
    }

    @Test
    fun theDefaultCapBindsRarelyAndChangesOnlyTheNewcomersOfTheSeasonItBinds() {
        val plain = ArrayList<String>()
        evolve(each = { b, _ -> plain += b.encode() }) { b, s, r, f -> Evolution.run(b, s, r, { 0 }, 5L, 1000, fashion = f) }
        val bound = ArrayList<Pair<Int, Set<StyleFamily>>>()
        val capped = ArrayList<String>()
        evolve(each = { b, _ -> capped += b.encode() }) { b, s, r, f ->
            Evolution.run(b, s, r, { 0 }, 5L, 1000, fashion = f, metaWeights = ones, styleCap = 1.5).also { if (it.capped.isNotEmpty()) bound += s to it.capped }
        }
        val families = bound.sumOf { it.second.size }
        println("style cap 1.5: bound in ${bound.size} of 20 seasons, $families of 160 season-families: $bound")
        assertTrue(families <= 2, "the default cap binds in ≤ 2 of 160 season-families: $bound")
        val first = bound.firstOrNull()?.first
        if (first == null) {
            assertEquals(plain, capped)
        } else {
            // Identical before; in the season it binds, the same players up to the newcomers debuting after it.
            assertEquals(plain.take(first - 1), capped.take(first - 1))
            fun older(text: String) = text.lines().filter { it.startsWith("ai ") }.map { it.split(' ') }.filter { it[5].toInt() <= first }.map { it.joinToString(" ") }
            assertEquals(older(plain[first - 1]), older(capped[first - 1]))
        }
    }

    // ---- the close's measure (ENG 4.2) --------------------------------------------------------

    private val kr = (1..6).map { "k$it" }
    private val jp = (1..6).map { "j$it" }
    private val families = mapOf(
        "k1" to YACHT, "k2" to YACHT, "k3" to YACHT, "k4" to COMBO, "k5" to COMBO, "k6" to COMBO,
        "j1" to BONUS, "j2" to BONUS, "j3" to BONUS, "j4" to BONUS, "j5" to YACHT, "j6" to YACHT,
    )

    /** KR and JP of 6 each, a single round robin where the lower key sweeps 2-0 every time. */
    private fun season(): SeasonPlan {
        val s = Season.start(4, 9L, listOf(kr, jp), false, Leagues.national(listOf("KR", "JP"), listOf(1, 1)))
        val plan = SeasonPlan(s, LeagueConfig(national = true))
        while (true) {
            val f = s.next(emptySet()) ?: break
            s.record(f.id, if (f.a < f.b) listOf(GameScore(230, 190), GameScore(220, 200)) else listOf(GameScore(190, 230), GameScore(200, 220)))
        }
        assertTrue(s.finished)
        return plan
    }

    private fun overall(k: String) = 40 + 3 * (k.drop(1).toInt() + if (k[0] == 'j') 6 else 0)
    private fun age(k: String) = listOf(20, 26, 33)[k.drop(1).toInt() % 3]
    private fun rating(k: String) = 1100.0 + 7 * overall(k) + (k.hashCode() % 37) + if (families[k] == YACHT) 25.0 else 0.0

    private fun input() = FamilyInput({ families[it] }, { overall(it) }, { age(it) }, { 1500.0 }, listOf("JP", "KR"))

    /** Least squares by the normal equations of `rating = β x + α_band` (an independent count of what [Meta.measure] fits). */
    private fun expectedResiduals(keys: List<String>): List<Double> {
        // Unknowns: β, α0, α1, α2.
        val a = Array(4) { DoubleArray(4) }
        val b = DoubleArray(4)
        fun band(k: String) = when (age(k)) {
            in 0..23 -> 0
            in 24..29 -> 1
            else -> 2
        }
        for (k in keys) {
            val v = doubleArrayOf(overall(k).toDouble(), 0.0, 0.0, 0.0).also { it[1 + band(k)] = 1.0 }
            for (i in 0 until 4) {
                b[i] += v[i] * rating(k)
                for (j in 0 until 4) a[i][j] += v[i] * v[j]
            }
        }
        // Gauss-Jordan.
        for (c in 0 until 4) {
            val p = (c until 4).maxBy { abs(a[it][c]) }
            a[c] = a[p].also { a[p] = a[c] }
            b[c] = b[p].also { b[p] = b[c] }
            for (r in 0 until 4) if (r != c) {
                val m = a[r][c] / a[c][c]
                for (j in 0 until 4) a[r][j] -= m * a[c][j]
                b[r] -= m * b[c]
            }
        }
        val x = DoubleArray(4) { b[it] / a[it][it] }
        return StyleFamily.entries.map { f ->
            val ks = keys.filter { families[it] == f }
            if (ks.isEmpty()) 0.0 else ks.sumOf { k -> rating(k) - (x[0] * overall(k) + x[1 + band(k)]) } / ks.size * ks.size / (ks.size + 40.0)
        }
    }

    @Test
    fun theFamilyLineOfAHandMadeSeason() {
        val plan = season()
        val titles = listOf(Honour(4, "L0.KR", "k1"), Honour(4, "L0.JP", "j1"), Honour(4, "CL", "k4"), Honour(4, "L1.KR", "x"))
        val fs = Meta.measure(plan, titles, ::rating, input())
        assertEquals(4, fs.season)
        // Elite: both 1부s (the best-ranked leagues); all: the same 12.
        assertEquals(listOf(0, 5, 3, 4, 0, 0, 0, 0), fs.elite)
        assertEquals(fs.elite, fs.all)
        // Big titles in the order of the best-ranked leagues, then the 챔피언스 리그.
        assertEquals(listOf(BONUS, YACHT, COMBO), fs.bigTitles)
        // Residuals: an OLS fit with an intercept per age band, shrunk by n / (n + 40).
        val expected = expectedResiduals(kr + jp)
        for (f in StyleFamily.entries) assertEquals(expected[f.ordinal], fs.residuals[f.ordinal], 1e-9, f.name)
        assertTrue(fs.residuals[YACHT.ordinal] > 0.3, "YACHT's +25 shows, shrunk: ${fs.residuals}")
        // Matchups: YACHT (k1-k3) swept COMBO (k4-k6) 9 times, BONUS (j1-j4) YACHT (j5, j6) 8 times.
        val win = 3 + if (plan.league.bonus) 1 else 0
        val e = Expectation.points(1500.0, 1500.0, Odds.DRAW_PRIOR, if (plan.league.bonus) Odds.SWEEP_PRIOR else 0.0, if (plan.league.bonus) 1 else 0)
        val m = fs.matchups.associateBy { it.f to it.g }
        assertEquals(listOf(YACHT to COMBO, YACHT to BONUS, COMBO to YACHT, BONUS to YACHT), fs.matchups.map { it.f to it.g })
        assertEquals(FamilyMatchup(YACHT, COMBO, 9 * win, 9, 0.0), m.getValue(YACHT to COMBO).copy(excess = 0.0))
        assertEquals(9 * win - 9 * e, m.getValue(YACHT to COMBO).excess, 1e-9)
        assertEquals(-9 * e, m.getValue(COMBO to YACHT).excess, 1e-9)
        assertEquals(8 * win - 8 * e, m.getValue(BONUS to YACHT).excess, 1e-9)
        assertEquals(0, m.getValue(YACHT to BONUS).points)
        // Each league's most common family in its 1부 (a tie to the family first in order).
        assertEquals(mapOf("KR" to YACHT, "JP" to BONUS), fs.leagues)

        // The close keeps it under its guard, as the line saves it; without familyStats nothing.
        val stats = StatsBook()
        stats.startSeason(4, (kr + jp).associateWith { rating(it) })
        stats.endSeason(plan, titles, HonourBook(), Archive(), ::rating, familyStats = true, families = input())
        assertEquals(FamilyStats.decode(fs.encode().trimEnd().split(' ')), stats.families(4))
        assertEquals(stats.families(4), StatsBook.decode(stats.encode()).families(4))
        val off = StatsBook()
        off.startSeason(4, (kr + jp).associateWith { rating(it) })
        off.endSeason(plan, titles, HonourBook(), Archive(), ::rating)
        assertTrue(off.families.isEmpty())
        assertTrue(off.encode().lines().none { it.startsWith("fm ") })
        // Kept for the newest 10 seasons (5 below 500 members), the matchups only on the newest.
        assertEquals(5, Meta.kept(499))
        assertEquals(10, Meta.kept(500))
        val book = StatsBook()
        for (s in 1..12) book.putFamilies(fs.copy(season = s), Meta.kept(1000))
        assertEquals((3..12).toList(), book.families.keys.toList())
        assertTrue(book.families.values.filter { it.season < 12 }.all { it.matchups.isEmpty() })
        assertEquals(4, book.families(12)!!.matchups.size)
        assertEquals(book.families, StatsBook.decode(book.encode()).families)
    }

    // ---- the weights (ENG 4.3) ----------------------------------------------------------------

    private fun stats(season: Int = 5, elite: List<Int> = List(8) { 10 }, all: List<Int>? = null, residuals: List<Double> = List(8) { 0.0 }, matchups: List<FamilyMatchup> = emptyList()): FamilyStats {
        // All by the neutral shares unless given: nobody saturates.
        val people = all ?: StyleFamily.entries.map { Math.round(1000 * StyleFamily.NEUTRAL.getValue(it)).toInt() }
        return FamilyStats(season, elite, people, emptyList(), residuals, matchups, emptyMap())
    }

    private fun neutralSum(w: Map<StyleFamily, Double>) = StyleFamily.entries.sumOf { StyleFamily.NEUTRAL.getValue(it) * w.getValue(it) }

    @Test
    fun theWeightsFollowSuccessDecayAndStayRescaledAndClamped() {
        // Nothing wins: weights stay at 1.
        val flat = Meta.update(null, stats(), null)
        for (f in StyleFamily.entries) assertEquals(1.0, flat.getValue(f), 1e-12)
        // A residual of +5 for BONUS: exp(0.15 × 5 / 25) before the rescale, which keeps Σ b w = 1.
        val res = List(8) { if (it == BONUS.ordinal) 5.0 else 0.0 }
        val w1 = Meta.update(null, stats(residuals = res), null)
        assertEquals(1.0, neutralSum(w1), 1e-9)
        assertEquals(Math.exp(0.03), w1.getValue(BONUS) / w1.getValue(EV), 1e-9)
        assertTrue(w1.getValue(BONUS) > 1 && w1.getValue(EV) < 1)
        // A steady residual gives a steady weight near exp(1.5 × res / 25) (≈ 1.35 for res 5): the decay pulls it back.
        var w: Map<StyleFamily, Double>? = null
        repeat(80) { w = Meta.update(w, stats(residuals = res), null) }
        val steady = w!!.getValue(BONUS) / w!!.getValue(EV)
        assertEquals(Math.exp(0.3), steady, 0.01)
        // When nothing wins any more the weights decay back toward 1.
        repeat(60) { w = Meta.update(w, stats(), null) }
        for (f in StyleFamily.entries) assertEquals(1.0, w!!.getValue(f), 0.002, f.name)
        // A big residual meets the clamp [0.6, 1.8] and never passes it.
        var big: Map<StyleFamily, Double>? = null
        val huge = List(8) { if (it == ODD.ordinal) 40.0 else if (it == COMBO.ordinal) -40.0 else 0.0 }
        repeat(40) { big = Meta.update(big, stats(residuals = huge), null) }
        // (The second rescale after the first clamp may leave the top one a little under 1.8.)
        assertTrue(big!!.getValue(ODD) in 1.75..1.8, big.toString())
        assertTrue(big!!.getValue(COMBO) in 0.6..0.65, big.toString())
        for (v in big!!.values) assertTrue(v in 0.6..1.8)
        // Saturation is relative: COMBO above 1.4 × its neutral share of all cools by 0.85, ODD above 1.4 × its own too.
        val crowded = StyleFamily.entries.map { f -> Math.round(1000 * StyleFamily.NEUTRAL.getValue(f) * if (f == ODD) 1.5 else 1.0).toInt() }
        val sat = Meta.update(null, stats(all = crowded), null)
        assertEquals(0.85, sat.getValue(ODD) / sat.getValue(EV), 1e-9)
        val justBelow = StyleFamily.entries.map { f -> Math.round(10000 * StyleFamily.NEUTRAL.getValue(f) * if (f == COMBO) 1.35 else 1.0).toInt() }
        assertEquals(1.0, Meta.update(null, stats(all = justBelow), null).let { it.getValue(COMBO) / it.getValue(EV) }, 1e-9)
        // The counter boost: × 1.25 for this update.
        val boosted = Meta.update(null, stats(), MIND)
        assertEquals(1.25, boosted.getValue(MIND) / boosted.getValue(EV), 1e-9)
        assertEquals(1.0, neutralSum(boosted), 1e-9)
    }

    @Test
    fun theCounterRuleReadsPointsAboveExpectationAgainstTheDominant() {
        // COMBO holds 50% of the elite (≥ 1.4 × 0.285): dominant.
        val elite = listOf(5, 15, 50, 10, 10, 5, 3, 2)
        val ups = listOf(
            FamilyMatchup(YACHT, COMBO, 300, 100, 20.0), // +0.20 a match
            FamilyMatchup(MIND, COMBO, 150, 50, 15.0), // +0.30 a match
            FamilyMatchup(ODD, COMBO, 100, 39, 30.0), // too few matches
            FamilyMatchup(COMBO, YACHT, 100, 100, -20.0),
        )
        assertEquals(CounterStyle(5, MIND, COMBO, 0.3), Meta.counter(stats(elite = elite, matchups = ups)))
        // Below +0.15 a match: none.
        assertNull(Meta.counter(stats(elite = elite, matchups = listOf(FamilyMatchup(YACHT, COMBO, 300, 100, 14.0)))))
        // The largest family not 1.4 × its neutral share: no dominant, no counter (COMBO 35% < 39.9%).
        assertNull(Meta.counter(stats(elite = listOf(10, 20, 35, 10, 10, 8, 4, 3), matchups = ups)))
        // A small family dominates relative to its own share (MIND, the largest at 30%, ≥ 1.4 × 4.2%).
        val mindElite = listOf(10, 15, 14, 10, 10, 5, 30, 6)
        val c = Meta.counter(stats(elite = mindElite, matchups = listOf(FamilyMatchup(EV, MIND, 140, 40, 8.0))))
        assertEquals(CounterStyle(5, EV, MIND, 0.2), c)
    }

    @Test
    fun theCloseRunsOnceKeepsItsLinesAndRebuildsItsNews() {
        val world = WorldBook()
        val elite = listOf(5, 15, 50, 10, 10, 5, 3, 2)
        val ups = listOf(FamilyMatchup(MIND, COMBO, 150, 50, 15.0))
        val res = List(8) { if (it == MIND.ordinal) 4.0 else 0.0 }
        val fms = HashMap<Int, FamilyStats>()
        for (s in 1..4) fms[s] = stats(season = s, elite = elite, residuals = res, matchups = ups)
        val e1 = Meta.close(world, fms, 1)
        assertEquals(listOf("counter_style"), e1.map { it.kind })
        assertEquals(listOf("M", "C", "0.30"), e1.single().args)
        val w1 = world.meta.allWeights
        assertEquals(1, world.metaSeason)
        // Asked again: the same news, nothing moves.
        assertEquals(e1, Meta.close(world, fms, 1))
        assertEquals(w1, world.meta.allWeights)
        // At most once in 3 closes: not at 2 or 3, again at 4.
        assertTrue(Meta.close(world, fms, 2).none { it.kind == "counter_style" })
        assertTrue(Meta.close(world, fms, 3).none { it.kind == "counter_style" })
        assertEquals(listOf("counter_style"), Meta.close(world, fms, 4).map { it.kind })
        assertEquals(listOf(1, 4), world.meta.counters.map { it.season })
        // The weights moved: MIND's residual and two counter boosts.
        assertTrue(world.meta.weight(MIND) > 1.2 && world.meta.weight(EV) < 1.0, world.meta.allWeights.toString())
        // The shares of each close and the counters round-trip in world.txt; weights as the line saves them.
        assertEquals(listOf(1, 2, 3, 4), world.meta.shares.map { it.season })
        assertEquals(Meta.shares(fms.getValue(4)), world.meta.shares.last())
        val text = world.encode()
        assertTrue(text.lines().any { it.startsWith("meta ") && it.endsWith(" 1:M:C:30,4:M:C:30") }, text)
        val back = WorldBook.decode(text)
        assertEquals(text, back.encode())
        assertEquals(world.meta.counters, back.meta.counters)
        assertEquals(world.meta.allWeights, back.meta.allWeights)
        // A replayed close read back gives the same news.
        assertEquals(Meta.events(world.meta, fms, 4), Meta.close(back, fms, 4))
        // Without the season's family line nothing happens (no guard either).
        assertTrue(Meta.close(back, fms, 5).isEmpty())
        assertEquals(4, back.metaSeason)
        // An older `meta` line without counters still reads.
        val old = WorldBook.decode("world 1\nguards 0 3 0 0\nmeta 1000,1100,900,1000,1000,1000,1000,1000\n")
        assertEquals(1.1, old.meta.weight(YACHT))
        assertTrue(old.meta.counters.isEmpty())
        // The weights the next newcomers take.
        val nm = Meta.newcomers(world.meta, mapOf("KR" to setOf(BONUS)))
        assertEquals(world.meta.allWeights, nm.weights)
    }

    @Test
    fun theNewcomersMapAddsTheWeightsAndALocalFashionToTheFashion() {
        val nm = NewcomerMeta(StyleFamily.entries.associateWith { if (it == BONUS) 1.3 else if (it == YACHT) 0.8 else 1.0 }, mapOf("KR" to setOf(YACHT)))
        val fashion = mapOf("hunter" to 0.6)
        val kr = nm.forNation("KR", fashion)
        val jp = nm.forNation("JP", fashion)
        assertEquals(0.6 - 0.2 + 0.25, kr.getValue("hunter"), 1e-12)
        assertEquals(-0.2 + 0.25, kr.getValue("collector"), 1e-12)
        assertEquals(0.6 - 0.2, jp.getValue("hunter"), 1e-12)
        for (k in listOf("purist", "small", "high")) assertEquals(0.3, jp.getValue(k), 1e-12)
        assertNull(jp["ev"], "a family at 1.0 adds nothing")
        // The local fashion of a close: each nation's league champion's family (not a 연합 리그's).
        val local = Meta.localFashion(listOf(Honour(3, "L0.KR", "a"), Honour(3, "L0.XAS", "b"), Honour(3, "L1.JP", "c"), Honour(3, "L0.JP", "d")), mapOf("a" to BONUS, "b" to EV, "d" to ODD)::get)
        assertEquals(mapOf("JP" to setOf(ODD), "KR" to setOf(BONUS)), local)
        // The generator takes it: more 보너스파 among rookies with BONUS weighted up, as a share of 4000 recruits.
        fun share(meta: Map<String, Double>) = (1..4000).count { StyleFamily.of(PlayerGenerator.recruit(it, 2, false, emptySet(), meta = meta).spec.play) == BONUS } / 4000.0
        val up = NewcomerMeta(StyleFamily.entries.associateWith { if (it == BONUS) 1.8 else 1.0 })
        assertTrue(share(up.forNation("KR", emptyMap())) > share(emptyMap()) + 0.03)
    }

    // ---- the style cap (ENG 4.3) --------------------------------------------------------------

    @Test
    fun theStyleCapIsRelativeToTheNeutralShares() {
        val cap = StyleCap(1.5, 100)
        assertEquals(49, cap.limit(COMBO)) // ⌈0.427 × 100 + √28.5⌉
        assertEquals(11, cap.limit(ODD)) // ⌈0.085 × 100 + √5.7⌉
        assertEquals(9, cap.limit(MIND))
        assertEquals(43, StyleCap(1.3, 100).limit(COMBO), "the strict rule")
        assertEquals(4, StyleCap(1.3, 20).limit(MIND), "at least 4")
        assertEquals(66, StyleCap(2.5, 100).limit(COMBO), "the share part never above 60%")
        repeat(9) { assertFalse(cap.refuse(MIND)); cap.take(MIND) }
        assertTrue(cap.refuse(MIND))
        assertEquals(setOf(MIND), cap.bound)
        assertEquals(9, cap.count(MIND))
        // The default cap trims a family drawn at the top weight (족보파 at 1.8, the rest rescaled), never at 1.0.
        fun season(weights: NewcomerMeta?): Pair<Set<StyleFamily>, Int> {
            val book = PlayerBook().apply { ensureGenerated(1000) }
            for (p in book.alive.take(300)) book.retire(p.key, 1)
            val before = book.alive.map { it.key }.toSet()
            val r = Evolution.run(book, 1, { 1500.0 }, { 0 }, 5L, 1000, childShare = 0.0, aging = false, metaWeights = weights, styleCap = 1.5)
            return r.capped to book.alive.count { it.key !in before && StyleFamily.of(it.spec.play) == COMBO }
        }
        val b = StyleFamily.NEUTRAL.getValue(COMBO)
        val (top, combos) = season(NewcomerMeta(StyleFamily.entries.associateWith { if (it == COMBO) 1.8 else (1 - 1.8 * b) / (1 - b) }))
        assertEquals(setOf(COMBO), top)
        assertEquals(StyleCap(1.5, 300).limit(COMBO), combos)
        assertTrue(season(ones).first.isEmpty())
        // In a season of rookies only, no family passes its limit; a tight cap binds and the book still fills.
        val book = PlayerBook().apply { ensureGenerated(400) }
        for (p in book.alive.take(120)) book.retire(p.key, 1)
        val before = book.alive.map { it.key }.toSet()
        val r = Evolution.run(book, 1, { 1500.0 }, { 0 }, 5L, 400, childShare = 0.0, aging = false, styleCap = 0.8)
        val fresh = book.alive.filter { it.key !in before }
        assertEquals(400, book.size)
        val tight = StyleCap(0.8, fresh.size)
        for (f in StyleFamily.entries) assertTrue(fresh.count { StyleFamily.of(it.spec.play) == f } <= tight.limit(f), f.name)
        assertTrue(COMBO in r.capped, "0.8 × the neutral share binds on 족보파: ${r.capped}")
    }

    // ---- imitation (ENG 4.4) ------------------------------------------------------------------

    @Test
    fun theSecondStyleComesFromTheModelsFamilyWhenTheTierMayTakeIt() {
        val top = PlayerGenerator.Tier.TOP
        val low = PlayerGenerator.Tier.LOW
        // The model's own primary when allowed.
        assertEquals("hunter", Imitation.secondFor("hunter", "ev", low))
        // A rule-bound model: the family's first allowed by weight (요트파 for "collector" at the top? collector is too weak at half strength there).
        val ok = { key: String, tier: PlayerGenerator.Tier -> PlayerGenerator.secondaryFor(PlayStyles.recipe(key)!!, tier) }
        val combo = Imitation.secondFor("runonly", "ev", low)!!
        assertEquals(COMBO, StyleFamily.of(combo))
        assertTrue(ok(combo, low))
        // Never its own primary.
        assertTrue(Imitation.secondFor("hunter", "hunter", low) != "hunter")
        // 정석파 has no second style to give (ev is never a second style).
        assertNull(Imitation.secondFor("ev", "hunter", top))
        for (r in PlayStyles.RECIPES) for (t in PlayerGenerator.Tier.entries) {
            Imitation.secondFor(r.key, "ev", t)?.let { s ->
                assertEquals(StyleFamily.of(r.key), StyleFamily.of(s))
                assertTrue(ok(s, t), "$s in $t")
            }
        }
    }

    @Test
    fun youngPlayersInTheBottomHalfImitateAtMostTwiceAndTheNewsComesBackFromTheBook() {
        val book = PlayerBook().apply { ensureGenerated(600) }
        val imitation = Imitation({ true }, { "purist" }, { "hunter" })
        val genes = book.alive.associate { it.key to it.spec.play!! }
        val events = ArrayList<List<String>>()
        val taken = HashMap<StyleFamily, Int>()
        for (s in 1..12) {
            val before = book.alive.associateBy { it.key }
            val r = Evolution.run(book, s, { 1500.0 }, { 0 }, 7L, 600, imitation = imitation, aging = false)
            for (p in book.alive) {
                val was = before[p.key] ?: continue
                if (p.imitations == was.imitations) continue
                assertEquals(was.imitations + 1, p.imitations)
                assertEquals(s, p.imitatedIn)
                assertTrue(p.identity!!.age(s, p.born) <= Imitation.AGE)
                val g = p.spec.play!!
                val g0 = was.spec.play!!
                // Only the second style changes, to a recipe of the model's family its tier may take.
                assertEquals(g0.copy(secondary = g.secondary), g)
                assertTrue(StyleFamily.of(g.secondary!!) == BONUS || StyleFamily.of(g.secondary!!) == YACHT, g.secondary)
                taken.merge(StyleFamily.of(g.secondary!!), 1, Int::plus)
                assertTrue(PlayerGenerator.secondaryFor(PlayStyles.recipe(g.secondary!!)!!, PlayerGenerator.Tier.of(p.identity!!.potential)))
            }
            val digest = r.events.filter { it.kind == "imitation" }
            assertTrue(digest.size <= 1)
            digest.firstOrNull()?.let { e ->
                events += e.args
                assertTrue(e.keys.size in 1..Evolution.NEWS_IMITATIONS)
                assertTrue(e.args[0].toInt() <= e.args[2].toInt())
                // A replay gives it again.
                assertEquals(r.events, Evolution.run(book, s, { 1500.0 }, { 0 }, 7L, 600, imitation = imitation, aging = false).events)
            }
        }
        assertTrue(events.isNotEmpty(), "about 6% a season of the young imitate")
        // The club's best member is the model 55% of the time, the league champion 45% (both
        // models' families give every tier a second style here).
        val clubShare = (taken[BONUS] ?: 0).toDouble() / taken.values.sum()
        assertTrue(taken.values.sum() >= 100 && clubShare in 0.45..0.65, "club model share $clubShare of $taken")
        assertTrue(book.alive.all { it.imitations <= Imitation.MAX })
        // The roster keeps the count and the close; an old line reads none.
        val text = book.encode()
        assertTrue(text.lines().any { " im=" in it })
        val back = PlayerBook.decode(text)
        assertEquals(book.alive.map { Triple(it.key, it.imitations, it.imitatedIn) }, back.alive.map { Triple(it.key, it.imitations, it.imitatedIn) })
        assertEquals(text, back.encode())
        assertEquals(0, PlayerBook.decode(text.replace(Regex(" im=\\d+:\\d+"), "")).alive.sumOf { it.imitations })
        // Remade in 선수 고치기, an imitator keeps its count (two a career, whatever is edited).
        val imitator = book.alive.first { it.imitations > 0 }
        val edited = book.edit(imitator.key, yacht.engine.league.PlayerMaker.of(imitator, 12), 12)!!
        assertEquals(imitator.imitations to imitator.imitatedIn, edited.imitations to edited.imitatedIn)
        // Only the second style ever changed.
        for (p in book.alive) genes[p.key]?.let { g0 -> assertEquals(g0.copy(secondary = p.spec.play!!.secondary), p.spec.play) }
        // Nobody outside the bottom half, nobody older than 25.
        val none = PlayerBook().apply { ensureGenerated(300) }
        Evolution.run(none, 1, { 1500.0 }, { 0 }, 7L, 300, imitation = Imitation({ false }, { "purist" }, { "hunter" }))
        assertTrue(none.alive.all { it.imitations == 0 })
    }

    @Test
    fun theImitationInputsComeFromTheClosingSeason() {
        val plan = season()
        val titles = listOf(Honour(4, "L0.KR", "k1"), Honour(4, "L0.JP", "j2"))
        val clubs = mapOf("k1" to "c01", "k2" to "c01", "k5" to "c01", "j1" to "c02", "j6" to "c02")
        val given = mapOf("k1" to "hunter", "k2" to "purist", "j1" to "timid", "j2" to "fourkind", "j6" to "small")
        val recipes = HashMap(given)
        val im = Imitation.of(plan, titles, ::rating, clubs::get) { recipes[it] }
        // Read when it is built: the KR champion retiring at the close (gone from the roster before
        // the imitations) and a model's rule-learning during growth change none of the models.
        recipes.remove("k1")
        recipes["j6"] = "ev"
        // The lower half of each table of 6 by points: the three last (the higher keys lost every time).
        assertEquals(setOf("k4", "k5", "k6", "j4", "j5", "j6"), (kr + jp).filter(im.bottomHalf).toSet())
        // The club's best-rated other member; the league champion (its L0 title, not the table's top when they differ).
        fun bestOther(k: String) = clubs.keys.filter { clubs[it] == clubs[k] && it != k }.maxBy { rating(it) }
        for (k in listOf("k1", "k2", "k5", "j1", "j6")) assertEquals(given[bestOther(k)], im.clubModel(k), k)
        assertEquals("small", im.clubModel("j1"), "j6's main style as the season ended")
        assertEquals("hunter", im.championModel("k6"))
        assertNull(im.championModel("k1"), "the champion is no model of itself")
        assertEquals("fourkind", im.championModel("j5"))
        assertNull(im.clubModel("k3"), "no club")
    }

    // ---- the roster's tokens and the rookie boost ---------------------------------------------

    @Test
    fun aLegendKeepsItsCareerClubs() {
        val lines = listOf(
            SeasonLine(5, 0, 1, 1, 0, 0, 1500.0, 7.0, "KR", "c03"),
            SeasonLine(3, 0, 1, 1, 0, 0, 1500.0, 7.0, "KR", "c03"),
            SeasonLine(4, 0, 1, 1, 0, 0, 1500.0, 7.0, "KR", "c03"),
            SeasonLine(6, 0, 1, 1, 0, 0, 1500.0, 7.0, "KR", null),
            SeasonLine(7, 0, 1, 1, 0, 0, 1500.0, 7.0, "JP", "c 11"),
            SeasonLine(8, 0, 1, 1, 0, 0, 1500.0, 7.0, "KR", "c03"),
        )
        val token = CareerClubs.of(lines)!!
        assertEquals("c03:3-5,c%2011:7-7,c03:8-8", token)
        assertEquals(listOf(CareerClubs.Spell("c03", 3, 5), CareerClubs.Spell("c 11", 7, 7), CareerClubs.Spell("c03", 8, 8)), CareerClubs.decode(token))
        assertNull(CareerClubs.of(lines.map { it.copy(club = null) }))
        // At retirement (two players let go): a legend's line carries lc=, read back; the other none.
        val book = PlayerBook().apply { ensureGenerated(50) }
        val (a, b) = book.alive.take(2)
        for (p in listOf(a, b)) book.update(p.copy(leaving = true))
        val r = Evolution.run(book, 1, { 1500.0 }, { k -> if (k == a.key) 1 else 0 }, 5L, 50, careerClubs = { token }, aging = false)
        assertEquals(listOf(a.key, b.key), r.retired)
        assertEquals(token, book.legend(a.key)!!.clubs)
        assertNull(book.legend(b.key))
        assertNull(book.legends.firstOrNull { it.key != a.key }?.clubs)
        val text = book.encode()
        assertTrue(text.lines().single { it.startsWith("legend ${a.key} ") }.endsWith(" lc=$token"))
        assertEquals(token, PlayerBook.decode(text).legend(a.key)!!.clubs)
        assertEquals(text, PlayerBook.decode(text).encode())
    }

    @Test
    fun aRookieBoostTiltsTheNationDrawAndItsAbsenceKeepsTodays() {
        fun rookies(boost: Map<String, Double>): List<PlayerRecord> {
            val book = PlayerBook().apply { ensureGenerated(1000) }
            for (p in book.alive.take(300)) book.retire(p.key, 1)
            val before = book.alive.map { it.key }.toSet()
            Evolution.run(book, 1, { 1500.0 }, { 0 }, 5L, 1000, childShare = 0.0, aging = false, rookieBoost = boost)
            return book.alive.filter { it.key !in before }
        }
        val plain = rookies(emptyMap())
        assertEquals(plain, rookies(mapOf("KR" to 1.0)), "a factor of 1 changes nothing")
        val kr = plain.count { it.identity!!.nation == "KR" }
        val boosted = rookies(mapOf("KR" to 3.0)).count { it.identity!!.nation == "KR" }
        assertTrue(boosted >= kr * 3 / 2 && boosted >= kr + 5, "KR rookies $kr → $boosted")
    }
}

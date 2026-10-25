package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random
import kotlin.math.abs

/** What one season's evolution did: who retired (lowest rated first), who was born, and the news. */
class EvolutionResult(val retired: List<String>, val born: List<String>, val events: List<LeagueEvent>)

/**
 * Evolution at the end of a season (docs/LEAGUE_SPEC.md section 5 step 3): the lowest rated
 * generated and evolved AIs retire, and children of the best take their places. Base AIs and
 * mine are never in the [PlayerBook], so they never retire or breed.
 */
object Evolution {
    /** Entrants of a parent-selection tournament. */
    const val TOURNAMENT = 4

    /** Chance of each gene of a child changing by a small step. */
    const val MUTATION = 0.15
    private const val SALT = 0xE7011L

    /** Plain expectation (θ = 0) between the risk attitudes: a style mutation moves one place along it. */
    private val THETA_LINE = LeagueRoster.THETAS.filter { it < 0 } + 0.0 + LeagueRoster.THETAS.filter { it > 0 }

    /**
     * Runs the evolution of the season [season] that just ended, once: a repeated call for the
     * same season (a rollover replayed after a kill) changes nothing and returns the same
     * result, rebuilt from the book.
     *
     * Every rated player that played the season ([rating] not NaN, debut ≤ [season]) is ranked
     * by [rating]. The [count] lowest retire, from the bottom half only (the top half is kept as
     * the parents); those with [titles] or [hasHonour] become legends with a summary of
     * [honours]. Then [children] children (default: one per retired AI, so the population stays
     * the same; pass fewer to shrink it towards a lowered `generated` setting) are made: each
     * parent is the best of [TOURNAMENT] random players of the top half, genes by uniform
     * crossover, then [mutate]. A child is `e<season>_<n>`, takes parent A's hometown and
     * debuts in season + 1. Deterministic from [seed] and the inputs.
     */
    fun run(
        book: PlayerBook,
        season: Int,
        rating: (String) -> Double,
        titles: (String) -> Int,
        count: Int,
        seed: Long,
        honours: (String) -> List<Honour> = { emptyList() },
        hasHonour: (String) -> Boolean = { k -> honours(k).any { isHonour(it.kind) } },
        children: Int? = null,
    ): EvolutionResult {
        if (season <= book.evolvedSeason) return replay(book, season)
        val rated = book.alive.filter { it.born <= season && !rating(it.key).isNaN() }
        for (p in rated) book.notePeak(p.key, rating(p.key))
        val ranked = rated.sortedWith(compareByDescending<PlayerRecord> { rating(it.key) }.thenBy { it.key })
        val pool = ranked.take((ranked.size + 1) / 2)
        val leaving = ranked.drop(pool.size).asReversed().take(count.coerceAtLeast(0))
        val events = ArrayList<LeagueEvent>()
        val retired = ArrayList<String>()
        for (p in leaving) {
            val t = titles(p.key)
            val legend = t > 0 || hasHonour(p.key)
            val r = book.retire(p.key, season, t, rating(p.key), if (legend) summary(honours(p.key)) else null) ?: continue
            retired += p.key
            events += retiredEvent(r)
        }
        val rng = Random(DiceStream.mix(seed, SALT, season.toLong()))
        val born = ArrayList<String>()
        if (pool.isNotEmpty()) {
            val taken = book.names().toMutableSet()
            for (n in 1..(children ?: retired.size).coerceAtLeast(0)) {
                val a = tournament(pool, rng)
                val b = if (pool.size > 1) tournament(pool.filter { it.key != a.key }, rng) else a
                val key = "e${season}_$n"
                val name = KoreanNames.unique(rng, taken)
                taken += name
                val spec = mutate(cross(a.spec, b.spec, rng), rng).copy(id = key, name = name)
                val child = PlayerRecord(key, name, a.hometown, Origin.EVOLVED, season + 1, a.key, b.key, spec)
                if (!book.add(child)) continue
                born += key
                events += LeagueEvent("born", season, listOf(key, a.key, b.key))
            }
        }
        book.evolvedSeason = season
        return EvolutionResult(retired, born, events)
    }

    /** A season already evolved: who retired then and who was born, from the book. */
    private fun replay(book: PlayerBook, season: Int): EvolutionResult {
        val gone = (book.retired + book.legends.map { it.record }).filter { it.retired == season }.distinctBy { it.key }
        val prefix = "e${season}_"
        val kids = (book.alive.map { Triple(it.key, it.parentA, it.parentB) } +
            (book.retired + book.legends.map { it.record }).map { Triple(it.key, it.parentA, it.parentB) })
            .filter { it.first.startsWith(prefix) }.distinctBy { it.first }
            .sortedBy { it.first.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE }
        val events = gone.map { retiredEvent(it) } + kids.map { (k, a, b) -> LeagueEvent("born", season, listOfNotNull(k, a, b)) }
        return EvolutionResult(gone.map { it.key }, kids.map { it.first }, events)
    }

    private fun retiredEvent(r: RetiredRecord) = LeagueEvent("retired", r.retired, listOf(r.key), listOf(r.seasons.toString(), r.titles.toString()))

    /** The best of up to [TOURNAMENT] distinct random entrants of [pool] (sorted best first). */
    private fun tournament(pool: List<PlayerRecord>, rng: Random): PlayerRecord {
        val idx = IntArray(pool.size) { it }
        var best = Int.MAX_VALUE
        for (i in 0 until minOf(TOURNAMENT, pool.size)) {
            val j = i + rng.nextInt(pool.size - i)
            val t = idx[i]
            idx[i] = idx[j]
            idx[j] = t
            best = minOf(best, idx[i])
        }
        return pool[best]
    }

    /**
     * Uniform crossover: each gene from either parent. The style (base, θ and belief) and the
     * mistake (kind and amount) travel as one gene each, so a child is always coherent.
     */
    fun cross(a: CustomAiSpec, b: CustomAiSpec, rng: Random): CustomAiSpec {
        val style = if (rng.nextBoolean()) a else b
        val habit = if (rng.nextBoolean()) a else b
        fun pick(x: Double, y: Double) = if (rng.nextBoolean()) x else y
        val yacht = pick(a.yacht, b.yacht)
        val straight = pick(a.straight, b.straight)
        val bonus = pick(a.bonus, b.bonus)
        val zero = pick(a.zero, b.zero)
        val saveChoice = if (rng.nextBoolean()) a.saveChoice else b.saveChoice
        val future = pick(a.future, b.future)
        val temper = if (rng.nextBoolean()) a.temper else b.temper
        return CustomAiSpec(
            "", "", style.base, style.theta, habit.mistake, habit.amount, yacht, straight, bonus, zero, saveChoice, future, temper, style.belief,
        )
    }

    /**
     * Each gene changes with probability [p], by a small step: θ moves to the next risk attitude
     * (plain expectation sits between the cautious and the bold), a believer learns the real
     * rule, mistakes scale by up to ×1.6 either way (slips that fade away stop), tastes move a
     * few points, Choice saving flips, a temper comes or goes.
     */
    fun mutate(s: CustomAiSpec, rng: Random, p: Double = MUTATION): CustomAiSpec {
        fun u(lo: Double, hi: Double) = PlayerGenerator.uniform(rng, lo, hi)
        fun points(x: Double, step: Double, lo: Double, hi: Double) = PlayerGenerator.round((x + u(-step, step)).coerceIn(lo, hi), 0)
        var x = s
        if (rng.nextDouble() < p) x = stepStyle(x, rng)
        if (rng.nextDouble() < p) x = stepMistake(x, rng)
        if (rng.nextDouble() < p) x = x.copy(yacht = points(x.yacht, 10.0, -30.0, 70.0))
        if (rng.nextDouble() < p) x = x.copy(straight = points(x.straight, 4.0, -10.0, 20.0))
        if (rng.nextDouble() < p) x = x.copy(bonus = PlayerGenerator.round((x.bonus + u(-0.3, 0.3)).coerceIn(-0.5, 2.0), 1))
        if (rng.nextDouble() < p) x = x.copy(zero = points(x.zero, 5.0, 0.0, 35.0))
        if (rng.nextDouble() < p) x = x.copy(saveChoice = !x.saveChoice)
        if (rng.nextDouble() < p) x = x.copy(future = PlayerGenerator.twentieths((x.future + u(-0.1, 0.1)).coerceIn(0.3, 1.5)))
        if (rng.nextDouble() < p) x = x.copy(temper = stepTemper(x.temper, rng))
        return x
    }

    private fun stepStyle(s: CustomAiSpec, rng: Random): CustomAiSpec {
        if (s.belief != null) return s.copy(belief = null)
        if (s.base == CustomBase.WIN) return s
        val here = if (s.base == CustomBase.RISK) s.theta else 0.0
        val i = THETA_LINE.indices.minBy { abs(THETA_LINE[it] - here) }
        val t = THETA_LINE[(i + if (rng.nextBoolean()) 1 else -1).coerceIn(THETA_LINE.indices)]
        return if (t == 0.0) s.copy(base = CustomBase.EV, theta = PlayerGenerator.PLAIN_THETA) else s.copy(base = CustomBase.RISK, theta = t)
    }

    private fun stepMistake(s: CustomAiSpec, rng: Random): CustomAiSpec {
        val scale = Math.exp(PlayerGenerator.uniform(rng, -0.5, 0.5))
        return when (s.mistake) {
            CustomMistake.NONE -> s.copy(mistake = CustomMistake.SLIP, amount = 0.005)
            CustomMistake.SLIP -> {
                val a = s.amount * scale
                if (a < PlayerGenerator.SLIP_MIN) s.copy(mistake = CustomMistake.NONE, amount = 0.0)
                else s.copy(amount = PlayerGenerator.round(minOf(a, PlayerGenerator.SLIP_MAX), 3))
            }
            CustomMistake.FEEL -> s.copy(amount = PlayerGenerator.round((s.amount * scale).coerceIn(PlayerGenerator.FEEL_MIN, PlayerGenerator.FEEL_MAX), 2))
        }
    }

    private fun stepTemper(t: Temper, rng: Random): Temper {
        val tempers = PlayerGenerator.TEMPERS
        return if (t != Temper.NONE && rng.nextBoolean()) Temper.NONE else tempers[rng.nextInt(tempers.size)]
    }

    /** Promotions, relegations and playoff stays are moves, not honours. */
    fun isHonour(kind: String): Boolean = !kind.startsWith("UP") && !kind.startsWith("DOWN") && !kind.startsWith("STAY")

    /** A space-free summary of [honours]: kind:count in the order first won, moves left out ("L0:2,CUP:1"; "-" for none). */
    fun summary(honours: List<Honour>): String {
        val counts = LinkedHashMap<String, Int>()
        for (h in honours) if (isHonour(h.kind)) counts.merge(h.kind, 1, Int::plus)
        return counts.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "-" }
    }

    /** The counts of a [summary] (bad parts skipped). */
    fun counts(summary: String): Map<String, Int> {
        if (summary == "-" || summary.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (part in summary.split(',')) {
            val k = part.substringBefore(':')
            val n = part.substringAfter(':', "").toIntOrNull() ?: continue
            if (k.isNotEmpty()) out[k] = n
        }
        return out
    }
}

/**
 * Traits of a spec for the meta report (메타 리포트), as stable codes (trait → value); [Traits.label]
 * gives the Korean words. Keys: mistake (none, low, mid, high), risk (- for no risk base,
 * very-cautious, cautious, bold, very-bold), yacht (yes when it loves Yachts, else no), belief
 * (its key or -), temper (a [Temper] name).
 */
fun traits(spec: CustomAiSpec): Map<String, String> = Traits.of(spec)

/** The traits the meta report compares between the top fifth and the rest. */
object Traits {
    /** Trait keys and their Korean names. */
    val NAMES: Map<String, String> = linkedMapOf(
        "mistake" to "실수",
        "risk" to "위험 성향",
        "yacht" to "요트 선호",
        "belief" to "규칙 착각",
        "temper" to "기복",
    )

    fun of(spec: CustomAiSpec): Map<String, String> = linkedMapOf(
        "mistake" to mistake(spec),
        "risk" to risk(spec),
        "yacht" to if (spec.base == CustomBase.EV && spec.yacht > 0) "yes" else "no",
        "belief" to (spec.belief?.takeIf { spec.base == CustomBase.EV } ?: "-"),
        "temper" to spec.temper.name,
    )

    private fun mistake(spec: CustomAiSpec): String {
        val a = spec.effectiveAmount
        return when (spec.effectiveMistake) {
            CustomMistake.NONE -> "none"
            CustomMistake.SLIP -> if (a < 0.02) "low" else if (a < 0.08) "mid" else "high"
            CustomMistake.FEEL -> if (a < 0.5) "low" else if (a < 1.5) "mid" else "high"
        }
    }

    private fun risk(spec: CustomAiSpec): String {
        if (spec.base != CustomBase.RISK) return "-"
        val t = spec.theta
        return when {
            t <= -0.06 -> "very-cautious"
            t < 0 -> "cautious"
            t < 0.06 -> "bold"
            else -> "very-bold"
        }
    }

    /** Korean words for a trait's value (the code itself when unknown). */
    fun label(trait: String, value: String): String = when (trait) {
        "mistake" -> when (value) {
            "none" -> "실수 없음"
            "low" -> "실수 적음"
            "mid" -> "실수 보통"
            "high" -> "실수 많음"
            else -> value
        }
        "risk" -> when (value) {
            "-" -> "위험 중립"
            "very-cautious" -> "아주 신중"
            "cautious" -> "신중"
            "bold" -> "모험"
            "very-bold" -> "아주 모험"
            else -> value
        }
        "yacht" -> if (value == "yes") "요트 좋아함" else "요트 보통"
        "belief" -> if (value == "-") "규칙 정확" else CustomAiSpec.BELIEFS[value] ?: value
        "temper" -> Temper.entries.firstOrNull { it.name == value }?.label ?: value
        else -> value
    }
}

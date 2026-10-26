package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random

/** What one season's evolution did: who retired, who debuts next season (pupils and rookies), and the news. */
class EvolutionResult(val retired: List<String>, val born: List<String>, val events: List<LeagueEvent>)

/**
 * The generations of the league at the end of a season (docs/LEAGUE_SPEC.md section 5 step 3):
 * players age a year, grow or decline ([Profiles.grow]), retire by age and form
 * ([Profiles.retireChance]), and new players fill the league back up: a share as pupils of the
 * best (제자: a mentor's style, a second mentor's touch), the rest as rookies from everywhere.
 * Base AIs and mine are never in the [PlayerBook], so they never age or retire.
 */
object Evolution {
    /** Entrants of a mentor-selection tournament. */
    const val TOURNAMENT = 4

    /** Chance of each part of a pupil's style changing a little. */
    const val MUTATION = 0.15

    /** Draws of a pupil before [run] gives up on one its `accept` refuses. */
    const val TRIES = 20

    /** Share of the free places that go to pupils (the rest to rookies) by default. */
    const val CHILD_SHARE = 0.4

    /** Chance a misread-rule player of 30 or younger learns the real rule after a season. */
    const val LEARN = 0.2

    /** Articles a season about rookies and about big changes in ability (the rest are summed up). */
    const val NEWS_ROOKIES = 3
    const val NEWS_CHANGES = 3

    private const val SALT = 0xE7011L
    private const val RETIRE_SALT = 0x2E71L
    private const val GROW_SALT = 0x6202L

    private val MISREAD = setOf("ss3", "fh2pair")

    /**
     * Runs the end of season [season], once: a repeated call for the same season (a rollover
     * replayed after a kill) changes nothing and returns the same result, rebuilt from the book
     * (all but the rule-learning articles).
     *
     * Players saved before styles take one first ([Profiles.reform], the league's reform). Then,
     * with [aging]: every player that debuted by [season] may retire, by its age and its place in
     * the season's ratings ([rating]; NaN for one that sat the season out, read as the middle);
     * those with [titles] or [hasHonour] become legends with a summary of [honours]. The rest grow
     * a year older ([played]: its share of a full season's games). Then new players bring the book
     * back to [population]: [childShare] of the free places go to pupils of mentors drawn by
     * tournament from the top half of the ratings (a pupil [accept] refuses is drawn again, up to
     * [TRIES] times), the rest to rookies ([meta] tilts their styles). A pupil is
     * `e<season>_<n>`, a rookie the next g-key; all debut in season + 1. Deterministic from [seed]
     * and the inputs.
     */
    fun run(
        book: PlayerBook,
        season: Int,
        rating: (String) -> Double,
        titles: (String) -> Int,
        seed: Long,
        population: Int,
        honours: (String) -> List<Honour> = { emptyList() },
        hasHonour: (String) -> Boolean = { k -> honours(k).any { isHonour(it.kind) } },
        childShare: Double = CHILD_SHARE,
        played: (String) -> Double = { 1.0 },
        accept: (CustomAiSpec) -> Boolean = { true },
        meta: Map<String, Double> = emptyMap(),
        aging: Boolean = true,
    ): EvolutionResult {
        if (season <= book.evolvedSeason) return replay(book, season)
        var reformed = 0
        for (p in book.alive) if (p.spec.play == null) {
            book.update(Profiles.reform(p, season))
            reformed++
        }
        if (reformed > 0) book.reformed = season
        val playing = book.alive.filter { it.born <= season }
        val rated = playing.filter { !rating(it.key).isNaN() }
        for (p in rated) book.notePeak(p.key, rating(p.key))
        val ranked = rated.sortedWith(compareByDescending<PlayerRecord> { rating(it.key) }.thenBy { it.key })
        val percentile = HashMap<String, Double>()
        for ((i, p) in ranked.withIndex()) percentile[p.key] = if (ranked.size <= 1) 0.5 else 1.0 - i.toDouble() / (ranked.size - 1)
        val pool = ranked.take((ranked.size + 1) / 2).mapNotNull { book[it.key] }
        val retired = ArrayList<String>()
        val learned = ArrayList<LeagueEvent>()
        if (aging) {
            for (p in playing) {
                val id = p.identity ?: continue
                val age = id.age(season, p.born)
                val r = Random(DiceStream.mix(seed, RETIRE_SALT, season.toLong(), p.key.hashCode().toLong()))
                if (r.nextDouble() >= Profiles.retireChance(age, percentile[p.key] ?: 0.5, id.nearCeiling(p.key))) continue
                val t = titles(p.key)
                val legend = t > 0 || hasHonour(p.key)
                book.retire(p.key, season, t, rating(p.key), if (legend) summary(honours(p.key)) else null) ?: continue
                retired += p.key
            }
            for (p0 in playing) {
                val p = book[p0.key] ?: continue
                val id = p.identity ?: continue
                val age = id.age(season, p.born)
                val r = Random(DiceStream.mix(seed, GROW_SALT, season.toLong(), p.key.hashCode().toLong()))
                val abl = Profiles.grow(id.abilities, id.potential, p.key, age, played(p.key), r)
                var gene = p.spec.play
                if (gene != null && gene.primary in MISREAD && age <= 30 && r.nextDouble() < LEARN) {
                    learned += LeagueEvent("learned_rule", season, listOf(p.key), listOf(gene.primary))
                    gene = gene.copy(primary = gene.secondary ?: "ev", k = if (gene.secondary != null) 0.6 else 1.0, secondary = null)
                }
                val next = id.copy(abilities = abl, lastAbl = id.abilities)
                book.update(p.copy(identity = next, spec = Profiles.apply(p.spec.copy(play = gene), next)))
            }
        }
        val rng = Random(DiceStream.mix(seed, SALT, season.toLong()))
        val wanted = population - book.size
        if (wanted > 0) {
            val pupils = if (pool.isEmpty()) 0 else Math.round(wanted * childShare.coerceIn(0.0, 1.0)).toInt()
            val taken = book.names().toMutableSet()
            val counts = book.nationCounts().toMutableMap()
            var n = 0
            repeat(pupils) {
                var child: PlayerRecord? = null
                for (attempt in 1..TRIES) {
                    val a = tournament(pool, rng)
                    val compatriots = pool.filter { it.key != a.key && it.identity?.nation == a.identity?.nation }
                    val others = pool.filter { it.key != a.key }
                    val b = when {
                        others.isEmpty() -> a
                        compatriots.size >= 2 && rng.nextBoolean() -> tournament(compatriots, rng)
                        else -> tournament(others, rng)
                    }
                    val c = pupil("e${season}_${n + 1}", season, a, b, rng, taken, counts, book.size + n, meta)
                    if (accept(c.spec)) {
                        child = c
                        break
                    }
                }
                val c = child ?: return@repeat
                if (book.add(c)) {
                    n++
                    taken += c.name
                    counts.merge(c.identity!!.nation, 1, Int::plus)
                }
            }
            book.ensureGenerated(population, season + 1, meta = meta, founding = false, accept = accept)
        }
        book.evolvedSeason = season
        return EvolutionResult(retired, debuts(book, season), events(book, season, learned))
    }

    /** A season already evolved: who retired then and who debuts after it, from the book. */
    private fun replay(book: PlayerBook, season: Int): EvolutionResult {
        val gone = (book.retired + book.legends.map { it.record }).filter { it.retired == season }.distinctBy { it.key }
        return EvolutionResult(gone.map { it.key }, debuts(book, season), events(book, season, emptyList()))
    }

    /** Players debuting after [season] (pupils first, by number, then rookies by key). */
    private fun debuts(book: PlayerBook, season: Int): List<String> {
        val prefix = "e${season}_"
        val all = book.alive.map { it.key to it.born } +
            (book.retired + book.legends.map { it.record }).map { it.key to it.born }
        val keys = all.filter { it.second == season + 1 }.map { it.first }.distinct()
        val pupils = keys.filter { it.startsWith(prefix) }.sortedBy { it.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE }
        val rookies = keys.filter { Origin.of(it) == Origin.GENERATED }.sortedBy { PlayerGenerator.index(it) ?: Int.MAX_VALUE }
        return pupils + rookies
    }

    /**
     * The news of season [season]'s end, from the book (the same on a replay): the reform, each
     * legend's retirement and one article for the rest, the biggest growths and declines, the
     * pupils' debuts, and the rookies (the [NEWS_ROOKIES] most promising by name). [learned] are
     * the rule-learning articles (only known when it ran).
     */
    private fun events(book: PlayerBook, season: Int, learned: List<LeagueEvent>): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        if (book.reformed == season) out += LeagueEvent("league_reform", season)
        val legends = book.legends.filter { it.record.retired == season }
        for (l in legends) out += retiredEvent(l.record)
        val others = book.retired.filter { it.retired == season && legends.none { l -> l.key == it.key } }.distinctBy { it.key }
        if (others.isNotEmpty()) {
            val named = others.sortedWith(compareByDescending<RetiredRecord> { if (it.bestRating.isNaN()) 0.0 else it.bestRating }.thenBy { it.key }).take(3)
            out += LeagueEvent("retirements", season, named.map { it.key }, listOf((others.size + legends.size).toString()))
        }
        out += changes(book, season)
        out += learned
        for (k in debuts(book, season)) {
            val p = book[k] ?: continue
            if (p.origin == Origin.EVOLVED) out += LeagueEvent("born", season, listOfNotNull(k, p.parentA, p.parentB))
        }
        val rookies = debuts(book, season).mapNotNull { book[it] }.filter { it.origin == Origin.GENERATED }
        if (rookies.isNotEmpty()) {
            val best = rookies.sortedWith(compareByDescending<PlayerRecord> { it.identity?.potential ?: 0 }.thenBy { it.key }).take(NEWS_ROOKIES)
            out += LeagueEvent("rookies", season, best.map { it.key }, listOf(rookies.size.toString()))
            for (r in best) {
                val id = r.identity ?: continue
                out += LeagueEvent("debut", season, listOf(r.key), listOf(id.nation, id.debutAge.toString(), r.hometown, id.potential.toString()))
            }
        }
        return out
    }

    /** The biggest growths (an ability +6, or +12 in all) and declines (−5, or −8 in all) of the season. */
    private fun changes(book: PlayerBook, season: Int): List<LeagueEvent> {
        class Change(val p: PlayerRecord, val i: Int, val from: Int, val to: Int, val total: Int)
        val all = book.alive.filter { it.born <= season }.mapNotNull { p ->
            val id = p.identity ?: return@mapNotNull null
            val before = id.lastAbl ?: return@mapNotNull null
            val d = id.abilities.diff(before)
            val i = d.indices.maxBy { Math.abs(d[it]) }
            Change(p, i, before.toList()[i], id.abilities.toList()[i], d.sum())
        }
        val up = all.filter { it.to - it.from >= 6 || it.total >= 12 }
            .sortedWith(compareByDescending<Change> { it.total }.thenBy { it.p.key }).take(NEWS_CHANGES)
        val down = all.filter { it.to - it.from <= -5 || it.total <= -8 }
            .sortedWith(compareBy<Change> { it.total }.thenBy { it.p.key }).take(NEWS_CHANGES)
        fun ev(kind: String, c: Change) = LeagueEvent(
            kind, season, listOf(c.p.key),
            listOf((c.p.identity!!.age(season, c.p.born)).toString(), c.i.toString(), c.from.toString(), c.to.toString()),
        )
        return up.map { ev("breakout", it) } + down.map { ev("decline", it) }
    }

    private fun retiredEvent(r: RetiredRecord) =
        LeagueEvent("retired", r.retired, listOf(r.key), listOf(r.seasons.toString(), r.titles.toString(), r.retiredAge.toString()))

    /**
     * A pupil of [a] (its main mentor) and [b]: a nation from A (55%), B (35%) or the league's
     * least represented ones, a city, a name of that nation, a potential halfway from the
     * league's usual toward its mentors', and
     * a style from A (50%), B (30%) or of its own ([crossPlay]).
     */
    fun pupil(
        key: String,
        season: Int,
        a: PlayerRecord,
        b: PlayerRecord,
        rng: Random,
        taken: Set<String>,
        counts: Map<String, Int>,
        total: Int,
        meta: Map<String, Double> = emptyMap(),
    ): PlayerRecord {
        val ia = a.identity ?: Profiles.legacy(a, season)
        val ib = b.identity ?: Profiles.legacy(b, season)
        val u = rng.nextDouble()
        val nation = when {
            u < 0.55 -> Nations.of(ia.nation)
            u < 0.9 -> Nations.of(ib.nation)
            else -> Nations.draw(rng, Nations.rebalance(counts, total))
        } ?: Nations.KR
        val gender = if (rng.nextBoolean()) Gender.MALE else Gender.FEMALE
        val age = Profiles.ROOKIE_AGES.draw(rng).toInt()
        val town = if (nation.code == ia.nation && rng.nextBoolean()) a.hometown else PlayerNames.hometown(nation, rng)
        val name = PlayerNames.unique(nation, gender, age, rng, taken)
        // Halfway between the mentors' and the league's usual: great mentors do not make great pupils every time.
        val mentors = (ia.potential + ib.potential) / 2.0
        val potential = Math.round(PlayerGenerator.POTENTIAL_MEAN + 0.5 * (mentors - PlayerGenerator.POTENTIAL_MEAN) + 9 * rng.nextGaussian()).toInt().coerceIn(25, 97)
        val school = Schools.of(nation.code)
        val id = Identity(
            nation.code, gender, age, Profiles.drawAbilities(rng, key, age, potential, school.bias), potential,
            if (rng.nextBoolean()) ia.temperKind else ib.temperKind, if (rng.nextBoolean()) ia.feel else ib.feel,
        )
        val gene = crossPlay(a.spec.play, b.spec.play, rng, potential, school, meta)
        val spec = Profiles.apply(CustomAiSpec(key, name, play = gene), id)
        return PlayerRecord(key, name, town, Origin.EVOLVED, season + 1, a.key, b.key, spec, identity = id)
    }

    /**
     * A pupil's style: [a]'s (50%), [b]'s (30%) or one of its own (20%, as a rookie of
     * [potential] from [school] would draw it); 30% of the time the other mentor's main style
     * comes along as its second one, half the time the other's overlay; then each of intensity,
     * second style and overlay changes with chance [MUTATION].
     */
    fun crossPlay(a: PlayGene?, b: PlayGene?, rng: Random, potential: Int, school: School, meta: Map<String, Double> = emptyMap()): PlayGene {
        val ga = a ?: PlayGene("ev")
        val gb = b ?: ga
        val own = PlayerGenerator.style(rng, potential, school, meta)
        val u = rng.nextDouble()
        val base = when {
            u < 0.5 -> ga
            u < 0.8 -> gb
            else -> own
        }
        val other = if (base === ga) gb else ga
        var g = base.copy(future = 1.0, sense = 0.0)
        if (rng.nextDouble() < 0.3 && other.primary != g.primary && PlayStyles.recipe(other.primary)?.scales == true && other.primary != "ev") {
            g = g.copy(secondary = other.primary)
        }
        if (rng.nextDouble() < 0.5) g = g.copy(overlay = other.overlay, moods = other.moods)
        if (rng.nextDouble() < MUTATION && PlayStyles.recipe(g.primary)?.scales == true) {
            g = g.copy(k = PlayerGenerator.round((g.k * Math.exp(PlayerGenerator.uniform(rng, -0.25, 0.25))).coerceIn(0.5, 2.0), 2))
        }
        if (rng.nextDouble() < MUTATION) g = g.copy(secondary = if (g.secondary == null) own.secondary else null)
        if (rng.nextDouble() < MUTATION) g = if (g.overlay == null) g.copy(overlay = own.overlay, moods = own.moods) else g.copy(overlay = null, moods = emptyList())
        return g
    }

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

    fun of(spec: CustomAiSpec): Map<String, String> = spec.play?.let { styled(spec, it) } ?: linkedMapOf(
        "mistake" to mistake(spec),
        "risk" to risk(spec),
        "yacht" to if (spec.base == CustomBase.EV && spec.yacht > 0) "yes" else "no",
        "belief" to (spec.belief?.takeIf { spec.base == CustomBase.EV } ?: "-"),
        "temper" to spec.temper.name,
    )

    // A player with a style: its risk and Yacht love from its tendencies, a misread rule as its belief.
    private fun styled(spec: CustomAiSpec, gene: PlayGene): Map<String, String> {
        val t = Tendencies.of(gene.style())
        return linkedMapOf(
            "mistake" to mistake(spec),
            "risk" to when {
                gene.overlay == "gambler" || t.bold >= 25 -> "very-bold"
                t.bold > 8 -> "bold"
                t.bold <= -25 -> "very-cautious"
                t.bold < -8 || gene.overlay == "locker" -> "cautious"
                else -> "-"
            },
            "yacht" to if (t.yacht > 10) "yes" else "no",
            "belief" to (gene.primary.takeIf { it == "ss3" || it == "fh2pair" } ?: "-"),
            "temper" to spec.temper.name,
        )
    }

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
        "belief" -> if (value == "-") "규칙 정확" else CustomAiSpec.BELIEFS[value] ?: PlayStyles.recipe(value)?.name ?: value
        "temper" -> Temper.entries.firstOrNull { it.name == value }?.label ?: value
        else -> value
    }
}

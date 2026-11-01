package yacht.engine.league

import java.util.TreeMap

/**
 * Style families (design ENG 4.1), called ○○파 everywhere so they never collide with the radar's
 * result labels (보너스형, ...) or the coaches' philosophies (공격형, ...). Every recipe of
 * [PlayStyles.RECIPES] belongs to one; a player's family is its primary recipe's, and players
 * without genes (base AIs, the guest, mine) have none. [code] is saved.
 */
enum class StyleFamily(val code: Char, val label: String) {
    EV('E', "정석파"),
    YACHT('Y', "요트파"),
    COMBO('C', "족보파"),
    BONUS('B', "보너스파"),
    CAUTION('S', "신중파"),
    BOLD('A', "과감파"),
    MIND('M', "심리전파"),
    ODD('O', "괴짜파"),
    ;

    /** "보너스파의 시대" (○○파 ends in a vowel: 가 / 예요 after it). */
    val era: String get() = "${label}의 시대"

    companion object {
        /** Recipe key → family: the 25 recipes of [PlayStyles.RECIPES]. */
        val RECIPES: Map<String, StyleFamily> = mapOf(
            "ev" to EV,
            "hunter" to YACHT, "collector" to YACHT, "reroller" to YACHT,
            "fourkind" to COMBO, "fullhouse" to COMBO, "lower" to COMBO, "straight" to COMBO, "runonly" to COMBO,
            "purist" to BONUS, "small" to BONUS, "high" to BONUS,
            "timid" to CAUTION, "noscratch" to CAUTION, "hoarder" to CAUTION, "bigchoice" to CAUTION,
            "scratcher" to BOLD, "dumper" to BOLD, "allornothing" to BOLD,
            "copycat" to MIND, "contrarian" to MIND,
            "ss3" to ODD, "fh2pair" to ODD, "topdown" to ODD, "bottomup" to ODD,
        )

        /** The family of recipe [recipe] (괴짜파 for a recipe this version does not know). */
        fun of(recipe: String): StyleFamily = RECIPES[recipe] ?: ODD

        /** The family of a player's genes (its primary recipe's); null without genes. */
        fun of(gene: PlayGene?): StyleFamily? = gene?.let { of(it.primary) }

        fun ofCode(code: Char): StyleFamily? = entries.firstOrNull { it.code == code }

        /**
         * Each family's neutral share `b_f` of newcomers: the share of rookies drawn by
         * [PlayerGenerator.recruit] with every weight at 1.0 (no fashion, no nation boost), measured
         * on the generator as it was at 3ce4b93 (recruits 1..1,000,000; a share's standard error is
         * below 0.0005) and pinned here; caps, saturation, dominance and the federation's style
         * statistic are relative to it (ENG 4.1). Sums to 1. (The probe league's newcomers, pupils
         * included, had fewer 괴짜파: pupils take after strong parents.)
         */
        val NEUTRAL: Map<StyleFamily, Double> = linkedMapOf(
            EV to 0.058839, YACHT to 0.197799, COMBO to 0.284906, BONUS to 0.107184,
            CAUTION to 0.172113, BOLD to 0.080504, MIND to 0.041829, ODD to 0.056826,
        )
    }
}

/** An era (시대, design ENG 4.5): the [n]-th era, of [family] (null: 춘추전국 시대), seasons [from]..[to] (0 = going on), its peak X × 100. */
data class Era(val n: Int, val family: StyleFamily?, val from: Int, val to: Int, val peak: Int)

/** The candidate for the next era (`cand`): [family] (null: none holds) and the closes it has held. */
data class EraCandidate(val family: StyleFamily?, val held: Int)

/**
 * A close where the counter rule fired (design ENG 4.3): against the dominant family [dominant]
 * the [counter] family took [excess] points a match above expectation, and its newcomers' weight
 * got the counter boost. Kept on the `meta` line (to the hundredth, as saved).
 */
data class CounterStyle(val season: Int, val counter: StyleFamily, val dominant: StyleFamily, val excess: Double)

/** Style shares of a season (`ss`), per family × 1000 in [StyleFamily] order: among the elite and among all. */
data class StyleShares(val season: Int, val elite: List<Int>, val all: List<Int>)

/** Points of family [f]'s members against [g]'s in a season's league matches, and their points above expectation. */
data class FamilyMatchup(val f: StyleFamily, val g: StyleFamily, val points: Int, val matches: Int, val excess: Double)

/**
 * What a close measures of the style families (`fm` in stats.txt, design ENG 4.2): members per
 * family among the elite and all ([StyleFamily] order), the families of the season's big titles
 * (null for a holder without genes), each family's shrunk rating residual, the matchups and each
 * league's top family among its 1부.
 */
data class FamilyStats(
    val season: Int,
    val elite: List<Int>,
    val all: List<Int>,
    val bigTitles: List<StyleFamily?>,
    val residuals: List<Double>,
    val matchups: List<FamilyMatchup>,
    val leagues: Map<String, StyleFamily>,
) {
    /** The `fm` line (with its '\n'). */
    fun encode(): String = buildString {
        append("fm $season ${elite.joinToString(",")} ${all.joinToString(",")} ")
        append(if (bigTitles.isEmpty()) "-" else bigTitles.joinToString("") { (it?.code ?: NONE).toString() }).append(' ')
        append(residuals.joinToString(",") { Math.round(it * 10).toString() }).append(' ')
        append(LineParts.list(matchups) { "${it.f.code}>${it.g.code}:${it.points}/${it.matches}/${Math.round(it.excess * 100)}" }).append(' ')
        append(LineParts.list(leagues.entries) { "${Field.esc(it.key)}:${it.value.code}" }).append('\n')
    }

    companion object {
        /** A big title whose holder has no family. */
        private const val NONE = '.'

        /** Reads an `fm` line split on spaces; null when it cannot. */
        fun decode(f: List<String>): FamilyStats? = runCatching {
            val n = StyleFamily.entries.size
            fun counts(t: String) = t.split(',').map { it.toInt() }.also { require(it.size == n) }
            val big = if (f[4] == "-") emptyList() else f[4].map { c -> if (c == NONE) null else StyleFamily.ofCode(c) ?: error("family $c") }
            val residuals = f[5].split(',').map { it.toInt() / 10.0 }.also { require(it.size == n) }
            val matchups = LineParts.items(f[6]).map { e ->
                val (pair, rest) = e.split(':')
                val p = rest.split('/')
                FamilyMatchup(StyleFamily.ofCode(pair[0])!!, StyleFamily.ofCode(pair[2])!!, p[0].toInt(), p[1].toInt(), p[2].toInt() / 100.0)
            }
            val leagues = LinkedHashMap<String, StyleFamily>()
            for (e in LineParts.items(f[7])) leagues[Field.unesc(e.substringBefore(':'))] = StyleFamily.ofCode(e.substringAfter(':').single())!!
            FamilyStats(f[1].toInt(), counts(f[2]), counts(f[3]), big, residuals, matchups, leagues)
        }.getOrNull()
    }
}

/**
 * The meta's part of world.txt (design ENG 4.3-4.5): the newcomer weights per family (`meta`; none
 * before the first update: every weight 1.0), the style shares of each season (`ss`), every era
 * (`era`) and the candidate for the next (`cand`). The logic is P9's, in this file.
 */
class MetaBook {
    /**
     * Newcomer weights per family; null before the first update (every weight 1.0). Held as the
     * `meta` line saves them (every family, to the thousandth; a missing or non-number weight is
     * 1.0), so the next update and the rookie draw read the same weights whether or not the league
     * was read back in between (a restart, a close replayed after a kill).
     */
    var weights: Map<StyleFamily, Double>? = null
        internal set(v) {
            field = v?.let { ws -> StyleFamily.entries.associateWith { f -> ws[f]?.takeIf { it.isFinite() }?.let { LineParts.fixed(it, 1000) } ?: 1.0 } }
        }

    internal val shareLines = TreeMap<Int, StyleShares>()
    internal val eraList = ArrayList<Era>()

    /** The closes where the counter rule fired, oldest first (the newest [Meta.COUNTERS_KEPT]). */
    internal val counterList = ArrayList<CounterStyle>()

    var candidate: EraCandidate? = null
        internal set

    /** Family [f]'s newcomer weight (1.0 before any update). */
    fun weight(f: StyleFamily): Double = weights?.get(f) ?: 1.0

    /** Every weight (1.0 before any update), in [StyleFamily] order. */
    val allWeights: Map<StyleFamily, Double> get() = StyleFamily.entries.associateWith { weight(it) }

    /** The counter rule's closes kept, oldest first. */
    val counters: List<CounterStyle> get() = counterList.toList()

    /** The counter rule of close [season], null when it did not fire then. */
    fun counter(season: Int): CounterStyle? = counterList.lastOrNull { it.season == season }

    /** Every era, oldest first. */
    val eras: List<Era> get() = eraList.toList()

    /** The era going on, null for none. */
    val era: Era? get() = eraList.lastOrNull()?.takeIf { it.to == 0 }

    /** The style shares kept, oldest season first. */
    val shares: List<StyleShares> get() = shareLines.values.toList()

    /** Era [e]'s name: "보너스파의 시대", a family's second "두 번째 보너스파의 시대", ...; "춘추전국 시대" without a family. */
    fun name(e: Era): String {
        val f = e.family ?: return CHAOS
        val nth = eraList.count { it.family == f && it.n < e.n } + 1
        return if (nth == 1) f.era else "${Ordinals.of(nth)} ${f.era}"
    }

    companion object {
        /** The era of no family. */
        const val CHAOS = "춘추전국 시대"

        // `meta <w×1000 ×8> [<season:counter:dominant:excess×100,...>]`: the counter closes after
        // the weights (they fire only in a weight update), left out while there are none.
        private val META = LineParts.of<WorldBook>("meta", { w, out ->
            w.meta.weights?.let { ws ->
                out.append("meta ").append(StyleFamily.entries.joinToString(",") { Math.round(ws.getValue(it) * 1000).toString() })
                if (w.meta.counterList.isNotEmpty()) {
                    out.append(' ').append(w.meta.counterList.joinToString(",") { "${it.season}:${it.counter.code}:${it.dominant.code}:${Math.round(it.excess * 100)}" })
                }
                out.append('\n')
            }
        }) { w, f ->
            val xs = f[1].split(',').map { it.toInt() / 1000.0 }
            require(xs.size == StyleFamily.entries.size)
            val counters = LineParts.items(f.getOrElse(2) { "-" }).mapNotNull { e ->
                val p = e.split(':').takeIf { it.size == 4 } ?: return@mapNotNull null
                val counter = p[1].singleOrNull()?.let { StyleFamily.ofCode(it) } ?: return@mapNotNull null
                val dominant = p[2].singleOrNull()?.let { StyleFamily.ofCode(it) } ?: return@mapNotNull null
                CounterStyle(p[0].toIntOrNull() ?: return@mapNotNull null, counter, dominant, (p[3].toIntOrNull() ?: return@mapNotNull null) / 100.0)
            }
            w.meta.weights = StyleFamily.entries.zip(xs).toMap()
            w.meta.counterList.clear()
            w.meta.counterList += counters.sortedBy { it.season }
            true
        }

        private val SS = LineParts.of<WorldBook>("ss", { w, out ->
            for (s in w.meta.shareLines.values) out.append("ss ${s.season} ${s.elite.joinToString(",")} ${s.all.joinToString(",")}\n")
        }) { w, f ->
            val n = StyleFamily.entries.size
            val elite = f[2].split(',').map { it.toInt() }.also { require(it.size == n) }
            val all = f[3].split(',').map { it.toInt() }.also { require(it.size == n) }
            w.meta.shareLines[f[1].toInt()] = StyleShares(f[1].toInt(), elite, all)
            true
        }

        private val ERA = LineParts.of<WorldBook>("era", { w, out ->
            for (e in w.meta.eraList) out.append("era ${e.n} ${e.family?.code ?: '-'} ${e.from} ${e.to} ${e.peak}\n")
        }) { w, f ->
            val family = f[2].single().takeIf { it != '-' }?.let { StyleFamily.ofCode(it) ?: return@of false }
            w.meta.eraList += Era(f[1].toInt(), family, f[3].toInt(), f[4].toInt(), f[5].toInt())
            w.meta.eraList.sortBy { it.n }
            true
        }

        private val CAND = LineParts.of<WorldBook>("cand", { w, out ->
            w.meta.candidate?.let { c -> out.append("cand ${c.family?.code ?: '-'} ${c.held}\n") }
        }) { w, f ->
            val family = f[1].single().takeIf { it != '-' }?.let { StyleFamily.ofCode(it) ?: return@of false }
            w.meta.candidate = EraCandidate(family, f[2].toInt())
            true
        }

        /** `meta ss era cand`. */
        val PARTS: List<LinePart<WorldBook>> = listOf(META, SS, ERA, CAND)
    }
}

/**
 * What the close's family measure reads besides the closing plan, its titles and the ladder's
 * ratings (design ENG 4.2): each member's [family] (its genes' primary; null without genes), its
 * [overall] ability and [age] in the season (null: none), the ratings the season was drawn with
 * ([drawn], the matchups' expectation) and the season's best-ranked leagues ([top], the
 * federation's `top` line; empty: the plan's ranking, else every league).
 */
class FamilyInput(
    val family: (String) -> StyleFamily?,
    val overall: (String) -> Int?,
    val age: (String) -> Int?,
    val drawn: (String) -> Double,
    val top: List<String> = emptyList(),
) {
    companion object {
        /** The inputs from the roster as it stands at the close (before evolution). */
        fun of(players: PlayerBook, season: Int, drawn: (String) -> Double, top: List<String>): FamilyInput = FamilyInput(
            family = { k -> StyleFamily.of(players[k]?.spec?.play) },
            overall = { k -> players[k]?.identity?.abilities?.overall },
            age = { k -> players[k]?.let { r -> r.identity?.age(season, r.born) } },
            drawn = drawn,
            top = top,
        )
    }
}

/**
 * The newcomer weights as the generator takes them (design ENG 4.3, "Use"): each family's
 * [weights] and, per nation code, the families of [local] fashion (its league champion's: +0.25
 * for one season's newcomers of that nation).
 */
class NewcomerMeta(val weights: Map<StyleFamily, Double>, val local: Map<String, Set<StyleFamily>> = emptyMap()) {
    /**
     * The `meta` map [PlayerGenerator.style] takes for a newcomer of [nation]: [fashion] (today's
     * world-champion styles) plus `w_f − 1` and the local fashion for every recipe of family f. With
     * every weight 1.0 and no local fashion it is [fashion] itself.
     */
    fun forNation(nation: String, fashion: Map<String, Double>): Map<String, Double> {
        val lf = local[nation].orEmpty()
        val out = HashMap(fashion)
        for (r in PlayStyles.RECIPES) {
            val f = StyleFamily.of(r.key)
            val add = (weights[f] ?: 1.0) - 1.0 + (if (f in lf) Meta.LOCAL else 0.0)
            if (add != 0.0) out[r.key] = (out[r.key] ?: 0.0) + add
        }
        return out
    }
}

/**
 * The style cap of one season's newcomers (design ENG 4.3, lever `styleCap`): while [newcomers]
 * are drawn, a family that already holds `max(4, ⌈min(0.6, k × b_f) × N + √(b_f N)⌉)` of them is
 * refused for the rest of the season. The slack of one standard deviation of a count at the
 * neutral share keeps chance from binding it: 1000 players draw only about 25-85 newcomers a
 * season (MetaTest's golden run), so a family expecting 2-6 of them passes 1.5 × its share by
 * luck alone (without the slack the cap bound 6 of 160 season-families there at every weight 1.0,
 * mostly 정석파 and 과감파, whose pupils take after the best; with it none). It still trims a
 * family drawn at the top weight.
 * [bound] are the families it refused at least once.
 */
class StyleCap(val k: Double, val newcomers: Int) {
    private val counts = IntArray(StyleFamily.entries.size)
    private val refused = java.util.EnumSet.noneOf(StyleFamily::class.java)

    /** The most newcomers of family [f] this season. */
    fun limit(f: StyleFamily): Int {
        val b = StyleFamily.NEUTRAL.getValue(f)
        val at = minOf(Meta.CAP_SHARE, k * b) * newcomers + Meta.CAP_SLACK * Math.sqrt(b * newcomers)
        return maxOf(Meta.CAP_MIN, Math.ceil(at - 1e-9).toInt())
    }

    /** True (and [f] noted as bound) when [f] is full. */
    fun refuse(f: StyleFamily): Boolean {
        if (counts[f.ordinal] < limit(f)) return false
        refused += f
        return true
    }

    /** A newcomer of [f] came in. */
    fun take(f: StyleFamily) {
        counts[f.ordinal]++
    }

    /** Newcomers of [f] so far. */
    fun count(f: StyleFamily): Int = counts[f.ordinal]

    val bound: Set<StyleFamily> get() = refused.toSet()
}

/**
 * Young players taking up a second style from a successful one (design ENG 4.4): who finished in
 * the [bottomHalf] of its division by points, the primary recipe of its club's best-rated other
 * member ([clubModel]) and of its league's champion ([championModel]); null where there is none.
 */
class Imitation(
    val bottomHalf: (String) -> Boolean,
    val clubModel: (String) -> String?,
    val championModel: (String) -> String?,
) {
    companion object {
        /** Players of this age or younger may imitate. */
        const val AGE = 25

        /** Imitations a career. */
        const val MAX = 2

        /** A season's chance for one who may. */
        const val CHANCE = 0.06

        /** The club's best member is the model this often, its league's champion otherwise. */
        const val CLUB = 0.55

        /**
         * The second style a player of primary [primary] and [tier] takes from a model whose
         * primary is [model]: the model's own when [PlayerGenerator.secondaryFor] allows it, else
         * the first allowed recipe of the model's family by [PlayerGenerator.weight]; never its own
         * primary; null when the family has none it may take.
         */
        fun secondFor(model: String, primary: String, tier: PlayerGenerator.Tier): String? {
            val family = StyleFamily.of(model)
            fun ok(r: PlayRecipe) = r.key != primary && PlayerGenerator.secondaryFor(r, tier)
            PlayStyles.recipe(model)?.takeIf(::ok)?.let { return it.key }
            return PlayStyles.RECIPES.filter { StyleFamily.of(it.key) == family && ok(it) }
                .sortedByDescending { PlayerGenerator.weight(it.key, tier) }.firstOrNull()?.key
        }

        /**
         * The imitation's inputs from the closing season: the division tables (the bottom half:
         * the lower ⌊n/2⌋ places), each club's best-rated member by [rating] among the season's
         * members ([clubOf]), and each league's champion (its `L0` title in [titles], else the top
         * of its 1부); [recipe] is a player's primary recipe (null without genes). Everything is
         * read here, once: a model who retires at this close, or whose rule-learning changes its
         * main style while the season evolves, is still the model it was when the season ended.
         */
        fun of(plan: SeasonPlan, titles: List<Honour>, rating: (String) -> Double, clubOf: (String) -> String?, recipe: (String) -> String?): Imitation {
            val season = plan.league
            val bottom = HashSet<String>()
            for (d in season.divisions.indices) {
                val table = season.table(d)
                for (s in table.drop(table.size - table.size / 2)) bottom += s.key
            }
            val club = HashMap<String, String>()
            for (k in season.standings.keys) clubOf(k)?.let { club[k] = it }
            // Each club's two best-rated members, so every member's model is another one.
            val best = HashMap<String, MutableList<String>>()
            val ranked = season.standings.keys.filter { !rating(it).isNaN() }
                .sortedWith(compareByDescending<String> { rating(it) }.thenBy { it })
            for (k in ranked) {
                val c = club[k] ?: continue
                val top = best.getOrPut(c) { ArrayList(2) }
                if (top.size < 2) top += k
            }
            val champion = HashMap<String, String>()
            for (lg in season.leagues.list) {
                val h = titles.firstOrNull { HonourKind.isTopTitle(it.kind) && (HonourKind.leagueOf(it.kind) ?: LeagueIds.LEGACY) == lg.id }
                (h?.key ?: season.table(lg.top).firstOrNull()?.key)?.let { champion[lg.id] = it }
            }
            val recipes = HashMap<String, String?>()
            for (k in best.values.flatten() + champion.values) if (k !in recipes) recipes[k] = recipe(k)
            return Imitation(
                bottomHalf = { it in bottom },
                clubModel = { k -> club[k]?.let { best[it] }?.firstOrNull { it != k }?.let { recipes[it] } },
                championModel = { k -> season.leagueOf(k)?.id?.let { champion[it] }?.takeIf { it != k }?.let { recipes[it] } },
            )
        }
    }
}

/**
 * The meta's evolution and its eras (design ENG 4.2-4.5): what a close measures of the style
 * families ([measure], the `fm` line), the newcomer weights' update with its decay, relative
 * saturation, counter rule and clamp, the style shares (`ss`), and the eras by big-title success
 * against population share with their hysteresis ([close], once a season under
 * [WorldBook.metaSeason]). Pure functions of the books they are given; the dice scoring rules and
 * the tables are never touched.
 */
object Meta {
    /** A weight's power at each update: it decays toward 1 when nothing wins. */
    const val DECAY = 0.9

    /** `exp(RESIDUAL × res / RES_SCALE)`: a family's residual moves its weight. */
    const val RESIDUAL = 0.15
    const val RES_SCALE = 25.0

    /** Weights stay in [W_MIN, W_MAX]. */
    const val W_MIN = 0.6
    const val W_MAX = 1.8

    /** A family above SAT_SHARE × its neutral share of all generated players cools by SATURATION ("유행이 지나치면 식어요"). */
    const val SAT_SHARE = 1.4
    const val SATURATION = 0.85

    /** The elite's largest family is dominant from DOMINANT × its neutral share of the elite. */
    const val DOMINANT = 1.4

    /** The counter: COUNTER_MIN points a match above expectation against the dominant over COUNTER_MATCHES matches or more; its weight × COUNTER_BOOST. */
    const val COUNTER_MIN = 0.15
    const val COUNTER_MATCHES = 40
    const val COUNTER_BOOST = 1.25

    /** The counter rule fires at most once in this many closes. */
    const val COUNTER_EVERY = 3

    /** Counter closes kept on the `meta` line. */
    const val COUNTERS_KEPT = 10

    /** A residual's shrinkage `n / (n + SHRINK)` (a family of 5-25 players stays near 0). */
    const val SHRINK = 40.0

    /** A league champion's family: this much more for its nation's next newcomers. */
    const val LOCAL = 0.25

    /** The style cap: at least CAP_MIN of a family, its share never above CAP_SHARE, and CAP_SLACK standard deviations of chance on top ([StyleCap]). */
    const val CAP_MIN = 4
    const val CAP_SHARE = 0.6
    const val CAP_SLACK = 1.0

    /** `fm` lines kept: the newest FM_KEPT seasons, FM_KEPT_SMALL below SMALL members. */
    const val FM_KEPT = 10
    const val FM_KEPT_SMALL = 5
    const val SMALL = 500

    /** Style-share seasons kept (`ss`). */
    const val SHARES_KEPT = 30

    /** The era rule (ENG 4.5): over ERA_SEASONS seasons, X ≥ ERA_X, titles ≥ ERA_TITLES, ERA_LEAD × the second; families from ERA_SHARE of all. */
    const val ERA_SEASONS = 3
    const val ERA_X = 1.8
    const val ERA_TITLES = 5
    const val ERA_LEAD = 1.25
    const val ERA_SHARE = 0.03

    /** A candidate begins its era after ERA_BEGIN closes; none for ERA_NONE closes ends an era (춘추전국 begins). */
    const val ERA_BEGIN = 2
    const val ERA_NONE = 3

    /** The age bands of the residuals' fit: up to 23, 24-29, 30 and older. */
    private fun band(age: Int): Int = when {
        age <= 23 -> 0
        age <= 29 -> 1
        else -> 2
    }

    /** `fm` lines a league of [members] keeps. */
    fun kept(members: Int): Int = if (members < SMALL) FM_KEPT_SMALL else FM_KEPT

    // ---- the close's measure (ENG 4.2) ---------------------------------------------------------

    /**
     * What closing season [plan] measures of the families: members per family among the elite (the
     * 1부 of the best-ranked leagues and the 챔피언스 리그's entrants) and among all members with
     * genes; the families of the big titles (each best-ranked league's `L0` in [titles], in their
     * order, then the 챔피언스 리그's `CL`); each family's mean rating residual ([rating] against an
     * OLS fit on overall with an intercept per age band, over every member with genes and an
     * identity, shrunk by `n / (n + 40)`); the matchups of the season's league matches (points,
     * matches, points above [Expectation.points] on the drawn ratings with Odds' priors), every
     * ordered pair of two different families; and each league's most common family among its 1부.
     */
    fun measure(plan: SeasonPlan, titles: List<Honour>, rating: (String) -> Double, input: FamilyInput): FamilyStats {
        val season = plan.league
        val leagues = season.leagues
        val nf = StyleFamily.entries.size
        val keys = season.standings.keys.sorted()
        val family = HashMap<String, StyleFamily>()
        for (k in keys) input.family(k)?.let { family[k] = it }
        val all = IntArray(nf)
        for (f in family.values) all[f.ordinal]++

        val top = input.top.filter { leagues.byId(it) != null }.distinct().ifEmpty { NationalLeagues.bestRanked(plan, Federation.TOP) }
            .ifEmpty { leagues.list.map { it.id } }
        val elite = java.util.TreeSet<String>()
        for (id in top) leagues.byId(id)?.let { lg -> if (lg.size > 0) elite += season.divisions[lg.top] }
        for (cl in plan.comps.filterIsInstance<ChampionsLeague>()) elite += cl.entrantKeys()
        val eliteCounts = IntArray(nf)
        for (k in elite) family[k]?.let { eliteCounts[it.ordinal]++ }

        val big = ArrayList<StyleFamily?>()
        for (id in top) titles.firstOrNull { HonourKind.isTopTitle(it.kind) && (HonourKind.leagueOf(it.kind) ?: LeagueIds.LEGACY) == id }?.let { big += family[it.key] }
        titles.firstOrNull { it.kind == "CL" }?.let { big += family[it.key] }

        val residuals = residuals(keys, family, rating, input)

        val bonus = if (season.bonus) 1 else 0
        val sweepRate = if (season.bonus) Odds.SWEEP_PRIOR else 0.0
        val points = Array(nf) { IntArray(nf) }
        val matches = Array(nf) { IntArray(nf) }
        val excess = Array(nf) { DoubleArray(nf) }
        for (id in 0 until season.fixturesTotal) {
            val fx = season.fixture(id)
            val games = season.result(id) ?: continue
            val fa = family[fx.a] ?: continue
            val fb = family[fx.b] ?: continue
            if (fa == fb) continue
            val res = TieRule.match(games, season.format)
            val pa = when (res) {
                1 -> 3 + if (season.bonus && TieRule.sweptByA(games)) 1 else 0
                -1 -> 0
                else -> 1
            }
            val pb = when (res) {
                -1 -> 3 + if (season.bonus && TieRule.sweptByB(games)) 1 else 0
                1 -> 0
                else -> 1
            }
            val ra = input.drawn(fx.a)
            val rb = input.drawn(fx.b)
            val a = fa.ordinal
            val b = fb.ordinal
            points[a][b] += pa
            points[b][a] += pb
            matches[a][b]++
            matches[b][a]++
            excess[a][b] += pa - Expectation.points(ra, rb, Odds.DRAW_PRIOR, sweepRate, bonus)
            excess[b][a] += pb - Expectation.points(rb, ra, Odds.DRAW_PRIOR, sweepRate, bonus)
        }
        val matchups = ArrayList<FamilyMatchup>()
        for (f in StyleFamily.entries) for (g in StyleFamily.entries) {
            if (f != g && matches[f.ordinal][g.ordinal] > 0) {
                matchups += FamilyMatchup(f, g, points[f.ordinal][g.ordinal], matches[f.ordinal][g.ordinal], excess[f.ordinal][g.ordinal])
            }
        }

        val byLeague = LinkedHashMap<String, StyleFamily>()
        for (lg in leagues.list) {
            if (lg.size == 0) continue
            val counts = IntArray(nf)
            for (k in season.divisions[lg.top]) family[k]?.let { counts[it.ordinal]++ }
            val most = counts.indices.maxWithOrNull(compareBy<Int> { counts[it] }.thenByDescending { it }) ?: continue
            if (counts[most] > 0) byLeague[lg.id] = StyleFamily.entries[most]
        }
        return FamilyStats(plan.number, eliteCounts.toList(), all.toList(), big, residuals, matchups, byLeague)
    }

    /**
     * Each family's shrunk mean residual: `rating − (β × overall + α_band)` with β and α by least
     * squares over every member of [keys] with a family, an identity and a rating (one slope, an
     * intercept per age band), the family's mean × `n / (n + SHRINK)`; 0 for a family without one.
     */
    private fun residuals(keys: List<String>, family: Map<String, StyleFamily>, rating: (String) -> Double, input: FamilyInput): List<Double> {
        class Row(val f: StyleFamily, val x: Double, val y: Double, val band: Int)
        val rows = keys.mapNotNull { k ->
            val f = family[k] ?: return@mapNotNull null
            val y = rating(k).takeIf { it.isFinite() } ?: return@mapNotNull null
            val x = input.overall(k) ?: return@mapNotNull null
            val age = input.age(k) ?: return@mapNotNull null
            Row(f, x.toDouble(), y, band(age))
        }
        val bands = 3
        val n = IntArray(bands)
        val sx = DoubleArray(bands)
        val sy = DoubleArray(bands)
        for (r in rows) {
            n[r.band]++
            sx[r.band] += r.x
            sy[r.band] += r.y
        }
        val mx = DoubleArray(bands) { if (n[it] == 0) 0.0 else sx[it] / n[it] }
        val my = DoubleArray(bands) { if (n[it] == 0) 0.0 else sy[it] / n[it] }
        var sxy = 0.0
        var sxx = 0.0
        for (r in rows) {
            val dx = r.x - mx[r.band]
            sxy += dx * (r.y - my[r.band])
            sxx += dx * dx
        }
        val beta = if (sxx > 0) sxy / sxx else 0.0
        val sum = DoubleArray(StyleFamily.entries.size)
        val count = IntArray(StyleFamily.entries.size)
        for (r in rows) {
            sum[r.f.ordinal] += r.y - (my[r.band] + beta * (r.x - mx[r.band]))
            count[r.f.ordinal]++
        }
        return StyleFamily.entries.map { f ->
            val c = count[f.ordinal]
            if (c == 0) 0.0 else sum[f.ordinal] / c * (c / (c + SHRINK))
        }
    }

    // ---- the newcomer weights (ENG 4.3) --------------------------------------------------------

    /**
     * The weights after close [fs] from [weights] (null: every weight 1.0): `w^0.9 ×
     * exp(0.15 × res / 25) × saturation × counter` per family ([counter]: the family the counter
     * rule boosts this close, null for none), rescaled so `Σ b_f w_f = 1` and clamped to [0.6, 1.8],
     * twice. Saturation: 0.85 for a family above 1.4 × its neutral share of all generated players.
     */
    fun update(weights: Map<StyleFamily, Double>?, fs: FamilyStats, counter: StyleFamily?): Map<StyleFamily, Double> {
        val total = fs.all.sum()
        val w = DoubleArray(StyleFamily.entries.size) { i ->
            val f = StyleFamily.entries[i]
            val share = if (total == 0) 0.0 else fs.all[i].toDouble() / total
            val sat = if (share > SAT_SHARE * StyleFamily.NEUTRAL.getValue(f)) SATURATION else 1.0
            val boost = if (f == counter) COUNTER_BOOST else 1.0
            Math.pow(weights?.get(f) ?: 1.0, DECAY) * Math.exp(RESIDUAL * fs.residuals[i] / RES_SCALE) * sat * boost
        }
        repeat(2) {
            val scale = StyleFamily.entries.sumOf { f -> StyleFamily.NEUTRAL.getValue(f) * w[f.ordinal] }
            for (i in w.indices) w[i] = (w[i] / scale).coerceIn(W_MIN, W_MAX)
        }
        return StyleFamily.entries.associateWith { w[it.ordinal] }
    }

    /**
     * The counter rule on close [fs]: the elite's largest family D (holding at least 1.4 × its
     * neutral share of the elite; ties by family order) and, among the other families with at least
     * 40 matches against D, the one with the most points above expectation a match against it,
     * when that is at least +0.15 a match. Null when the rule does not hold.
     */
    fun counter(fs: FamilyStats): CounterStyle? {
        val eliteTotal = fs.elite.sum()
        if (eliteTotal == 0) return null
        val d = StyleFamily.entries.maxWithOrNull(compareBy<StyleFamily> { fs.elite[it.ordinal] }.thenByDescending { it.ordinal }) ?: return null
        if (fs.elite[d.ordinal].toDouble() / eliteTotal < DOMINANT * StyleFamily.NEUTRAL.getValue(d)) return null
        val best = fs.matchups.filter { it.g == d && it.f != d && it.matches >= COUNTER_MATCHES }
            .maxWithOrNull(compareBy<FamilyMatchup> { it.excess / it.matches }.thenByDescending { it.f.ordinal }) ?: return null
        val perMatch = best.excess / best.matches
        if (perMatch < COUNTER_MIN) return null
        return CounterStyle(fs.season, best.f, d, LineParts.fixed(perMatch, 100))
    }

    // ---- eras (ENG 4.5) ------------------------------------------------------------------------

    /** A family's big-title success over the seasons up to a close: its [titles] of [total], its all-share [share] and `X = (T_f + 1) / (T × a_f + 1)`. */
    data class EraScore(val family: StyleFamily, val titles: Int, val total: Int, val share: Double, val x: Double)

    /**
     * Every family's [EraScore] over seasons `season − 2 .. season` of [families] (the seasons kept
     * of them), in [StyleFamily] order; empty without any.
     */
    fun scores(families: Map<Int, FamilyStats>, season: Int): List<EraScore> {
        val window = (season - ERA_SEASONS + 1..season).mapNotNull { families[it] }
        if (window.isEmpty()) return emptyList()
        val total = window.sumOf { it.bigTitles.size }
        val people = window.sumOf { it.all.sum() }
        return StyleFamily.entries.map { f ->
            val t = window.sumOf { fs -> fs.bigTitles.count { it == f } }
            val a = if (people == 0) 0.0 else window.sumOf { it.all[f.ordinal] }.toDouble() / people
            EraScore(f, t, total, a, (t + 1.0) / (total * a + 1.0))
        }
    }

    /**
     * The era candidate at close [season]: among the families with an all-share of at least 3%,
     * the highest X when it is at least 1.8, its titles at least 5 and its X at least 1.25 × the
     * second's; null when none holds.
     */
    fun candidate(families: Map<Int, FamilyStats>, season: Int): EraScore? {
        val ranked = scores(families, season).filter { it.share >= ERA_SHARE }
            .sortedWith(compareByDescending<EraScore> { it.x }.thenBy { it.family.ordinal })
        val first = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.x ?: 0.0
        return first.takeIf { it.x >= ERA_X && it.titles >= ERA_TITLES && it.x >= ERA_LEAD * second }
    }

    /**
     * The eras after close [season] with candidate [c] (null: none holds; [scores] give the going
     * era's X for its peak): the candidate's closes held count on (or start at 1); a family that
     * has held [ERA_BEGIN] closes begins its era with season [season] (the era going on ends with
     * the season before); [ERA_NONE] closes of none end a family's era and 춘추전국 begins. No era
     * begins from nothing but a family's. Closes are held in a row only: when the meta's last
     * close [last] was not the season before (a season it could not measure), the count starts again.
     */
    internal fun stepEras(book: MetaBook, season: Int, c: EraScore?, scores: List<EraScore>, last: Int = season - 1) {
        val prev = book.candidate?.takeIf { last == season - 1 }
        val held = if (prev != null && prev.family == c?.family) prev.held + 1 else 1
        book.candidate = EraCandidate(c?.family, held)
        val i = book.eraList.indexOfLast { it.to == 0 }
        val cur = if (i >= 0) book.eraList[i] else null
        fun begin(f: StyleFamily?, peak: Int) {
            if (cur != null) book.eraList[i] = cur.copy(to = season - 1)
            book.eraList += Era((book.eraList.maxOfOrNull { it.n } ?: 0) + 1, f, season, 0, peak)
        }
        when {
            c != null && held >= ERA_BEGIN && cur?.family != c.family -> begin(c.family, Math.round(c.x * 100).toInt())
            c == null && held >= ERA_NONE && cur?.family != null -> begin(null, 0)
            cur?.family != null -> scores.firstOrNull { it.family == cur.family }?.let { s ->
                val x = Math.round(s.x * 100).toInt()
                if (x > cur.peak) book.eraList[i] = cur.copy(peak = x)
            }
        }
    }

    // ---- the close (ENG 4.3-4.5, 8.3) ----------------------------------------------------------

    /**
     * The meta's close of season [season], once (guard [WorldBook.metaSeason]): from the season's
     * `fm` in [families] (as stats.txt keeps it), the counter rule (at most once in 3 closes), the
     * newcomer weights, the style shares (`ss`, the newest 30), the era candidate and the eras.
     * Returns the news (`era_ends`, `era_begins`, `counter_style`), rebuilt from the book when the
     * close is asked again (a rollover replayed after a kill). Nothing without the season's `fm`.
     */
    fun close(world: WorldBook, families: Map<Int, FamilyStats>, season: Int): List<LeagueEvent> {
        val book = world.meta
        if (season <= world.metaSeason) return events(book, families, season)
        val fs = families[season] ?: return emptyList()
        val counter = counter(fs)?.takeIf { c -> book.counterList.none { it.season > c.season - COUNTER_EVERY && it.season < c.season } }
        book.weights = update(book.weights, fs, counter?.counter)
        if (counter != null) {
            book.counterList.removeAll { it.season == season }
            book.counterList += counter
            while (book.counterList.size > COUNTERS_KEPT) book.counterList.removeAt(0)
        }
        book.shareLines[season] = shares(fs)
        while (book.shareLines.size > SHARES_KEPT) book.shareLines.pollFirstEntry()
        val scores = scores(families, season)
        stepEras(book, season, candidate(families, season), scores, world.metaSeason)
        world.metaSeason = season
        return events(book, families, season)
    }

    /** The style shares of close [fs] (`ss`): per family × 1000 among the elite and among all (zeros without any). */
    fun shares(fs: FamilyStats): StyleShares {
        fun per(xs: List<Int>): List<Int> {
            val total = xs.sum()
            return xs.map { if (total == 0) 0 else Math.round(1000.0 * it / total).toInt() }
        }
        return StyleShares(fs.season, per(fs.elite), per(fs.all))
    }

    /**
     * The news of close [season] from the book: `era_ends` [] (family code or "-", era name,
     * seasons) for the era that ended with the season before, `era_begins` [] (family code or "-",
     * its big titles and all big titles of the last three seasons, era name) for the one that
     * began, `counter_style` [] (counter code, dominant code, points a match above expectation
     * "0.18") when the counter rule fired.
     */
    fun events(book: MetaBook, families: Map<Int, FamilyStats>, season: Int): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        val begun = book.eraList.filter { it.from == season }
        if (begun.isNotEmpty()) {
            for (e in book.eraList.filter { it.to == season - 1 && it.to > 0 }) {
                out += LeagueEvent("era_ends", season, emptyList(), listOf(code(e.family), book.name(e), (e.to - e.from + 1).toString()))
            }
        }
        val scores = scores(families, season)
        for (e in begun) {
            val s = e.family?.let { f -> scores.firstOrNull { it.family == f } }
            val total = scores.firstOrNull()?.total ?: 0
            out += LeagueEvent("era_begins", season, emptyList(), listOf(code(e.family), (s?.titles ?: 0).toString(), total.toString(), book.name(e)))
        }
        book.counter(season)?.let { c ->
            out += LeagueEvent("counter_style", season, emptyList(), listOf(c.counter.code.toString(), c.dominant.code.toString(), String.format(java.util.Locale.ROOT, "%.2f", c.excess)))
        }
        return out
    }

    private fun code(f: StyleFamily?): String = f?.code?.toString() ?: "-"

    // ---- what the next newcomers take ----------------------------------------------------------

    /** The newcomer weights of [book] with the local fashion [local] (nation code → families), for [Evolution.run]. */
    fun newcomers(book: MetaBook, local: Map<String, Set<StyleFamily>> = emptyMap()): NewcomerMeta = NewcomerMeta(book.allWeights, local)

    /**
     * The local fashion of a close: for each national league whose id is a nation's (not a 연합
     * 리그), the family of its champion (its `L0` title in [titles]), for that nation's newcomers.
     */
    fun localFashion(titles: List<Honour>, family: (String) -> StyleFamily?): Map<String, Set<StyleFamily>> {
        val out = java.util.TreeMap<String, MutableSet<StyleFamily>>()
        for (h in titles) {
            if (!HonourKind.isTopTitle(h.kind)) continue
            val nation = HonourKind.leagueOf(h.kind)?.takeIf { Nations.of(it) != null } ?: continue
            val f = family(h.key) ?: continue
            out.getOrPut(nation) { java.util.EnumSet.noneOf(StyleFamily::class.java) } += f
        }
        return out
    }
}

/** Korean ordinals for names ("두 번째", ...). */
internal object Ordinals {
    private val WORDS = listOf("첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")

    /** "두 번째" for 2; "11번째" past ten. */
    fun of(n: Int): String = if (n in 1..WORDS.size) "${WORDS[n - 1]} 번째" else "${n}번째"
}

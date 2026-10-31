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

/** Style shares of a season (`ss`), per family × 1000 in [StyleFamily] order: among the elite and among all. */
data class StyleShares(val season: Int, val elite: List<Int>, val all: List<Int>)

/** Points of family [f]'s members against [g]'s in a season's league matches, and their points above expectation. */
data class Matchup(val f: StyleFamily, val g: StyleFamily, val points: Int, val matches: Int, val excess: Double)

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
    val matchups: List<Matchup>,
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
                Matchup(StyleFamily.ofCode(pair[0])!!, StyleFamily.ofCode(pair[2])!!, p[0].toInt(), p[1].toInt(), p[2].toInt() / 100.0)
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
    /** Newcomer weights per family; null before the first update (every weight 1.0). */
    var weights: Map<StyleFamily, Double>? = null
        internal set

    internal val shareLines = TreeMap<Int, StyleShares>()
    internal val eraList = ArrayList<Era>()

    var candidate: EraCandidate? = null
        internal set

    /** Family [f]'s newcomer weight (1.0 before any update). */
    fun weight(f: StyleFamily): Double = weights?.get(f) ?: 1.0

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

        private val META = LineParts.of<WorldBook>("meta", { w, out ->
            w.meta.weights?.let { ws -> out.append("meta ").append(StyleFamily.entries.joinToString(",") { Math.round((ws[it] ?: 1.0) * 1000).toString() }).append('\n') }
        }) { w, f ->
            val xs = f[1].split(',').map { it.toInt() / 1000.0 }
            require(xs.size == StyleFamily.entries.size)
            w.meta.weights = StyleFamily.entries.zip(xs).toMap()
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

/** Korean ordinals for names ("두 번째", ...). */
internal object Ordinals {
    private val WORDS = listOf("첫", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")

    /** "두 번째" for 2; "11번째" past ten. */
    fun of(n: Int): String = if (n in 1..WORDS.size) "${WORDS[n - 1]} 번째" else "${n}번째"
}

package yacht.engine.league

import yacht.engine.Category
import yacht.engine.DiceStream
import yacht.engine.Tables
import java.util.Locale
import java.util.Random
import java.util.TreeSet

/** The 16 home cities (연고지). Base AIs get one from their key; evolved players inherit parent A's. */
object Hometowns {
    val ALL: List<String> = listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "수원", "춘천", "청주", "전주", "포항", "창원", "제주", "강릉")

    /** The town of [key] from its hash: the formula the app has always used, so base AIs keep theirs. */
    fun of(key: String): String = ALL[Math.floorMod(key.hashCode(), ALL.size)]
}

/** Where an AI comes from (the ladder's origin chip). */
enum class Origin(val label: String) {
    BASE("기본"),
    GENERATED("생성"),
    EVOLVED("진화"),
    MINE("내 AI"),

    /** A player the player made (선수 만들기): a league player like the generated ones. */
    CREATED("직접 만든"),
    ;

    companion object {
        private val GENERATED_KEY = Regex("g\\d+")
        private val EVOLVED_KEY = Regex("e\\d+_\\d+")
        private val CREATED_KEY = Regex("u\\d+")

        /** The origin read from a key: g001 generated, e3_1 evolved, u1 made by the player, me, the clone and custom AIs mine, the rest base. */
        fun of(key: String): Origin = when {
            key == Ladder.ME || key == CustomAis.CLONE || key.startsWith("custom-") -> MINE
            GENERATED_KEY.matches(key) -> GENERATED
            EVOLVED_KEY.matches(key) -> EVOLVED
            CREATED_KEY.matches(key) -> CREATED
            else -> BASE
        }
    }
}

/**
 * A generated or evolved AI in the league (an `ai` line of roster.txt): name, town, origin,
 * debut season, parents (evolved players only) and genes ([spec], with id [key] and [name]).
 */
data class PlayerRecord(
    val key: String,
    val name: String,
    val hometown: String,
    val origin: Origin,
    /** Its first season in the league. */
    val born: Int,
    val parentA: String?,
    val parentB: String?,
    val spec: CustomAiSpec,
    /** Best season-end rating so far (NaN before its first season ends). */
    val peak: Double = Double.NaN,
    /** Nation, gender, age and abilities (null only for a record made before identities, until the book fills it in). */
    val identity: Identity? = null,
    /** Forever in its prime (영원히 현역): it never ages, grows, declines nor retires (a player made so). */
    val keep: Boolean = false,
) {
    /** Age in [season] (its debut age when it has no identity yet). */
    fun age(season: Int): Int = identity?.age(season, born) ?: 0
}

/** An AI that left the league at the end of season [retired] (a `retired` line). */
data class RetiredRecord(
    val key: String,
    val name: String,
    val born: Int,
    val retired: Int,
    val parentA: String?,
    val parentB: String?,
    /** Best season-end rating (NaN when it never finished a season). */
    val bestRating: Double,
    val titles: Int,
    val hometown: String,
    val origin: Origin,
    /** Its nation code, gender and debut age (age at retirement = debutAge + seasons - 1). */
    val nation: String = Nations.KR.code,
    val gender: Gender? = null,
    val debutAge: Int = 0,
) {
    /** Seasons it played. */
    val seasons: Int get() = retired - born + 1

    /** Its age in its last season (0 when unknown). */
    val retiredAge: Int get() = if (debutAge > 0) debutAge + seasons - 1 else 0
}

/**
 * A retired AI with honours, kept with its genes (a `legend` line) so it can be rebuilt for the
 * legend match and the 레전드 보관소. [honours] is a summary such as "L0:2,CUP:1" ([Evolution.summary]).
 */
data class Legend(val record: RetiredRecord, val spec: CustomAiSpec, val honours: String, val identity: Identity? = null) {
    val key: String get() = record.key
    val name: String get() = record.name

    /** As a player record, for [leagueAi]. */
    fun asPlayer(): PlayerRecord =
        PlayerRecord(record.key, record.name, record.hometown, record.origin, record.born, record.parentA, record.parentB, spec, record.bestRating, identity)

    /** The legend at its best: its genes with the abilities of its peak season (as it retired when none were kept). */
    fun atPeak(): CustomAiSpec {
        val id = identity ?: return spec
        val peak = id.peakAbl ?: return spec
        return Profiles.apply(spec, id.copy(abilities = peak))
    }
}

/**
 * New players (docs/LEAGUE_SPEC.md section 1). Player i is drawn from its own random streams
 * only (identity, name, profile), so the same index always gives the same player.
 *
 * Identity: a nation by the weights (or [Nations.rebalance]d for rookies), gender 50/50, the
 * debut age ([Profiles.FOUNDING_AGES] for a league's founders, [Profiles.ROOKIE_AGES] after),
 * a city of its nation and a name of its nation.
 *
 * Profile: a potential ~ N(64, 13) (25..97), abilities for its age with its nation's [School]
 * bias, mistakes by feel 60% (else slips), a temper kind for a low 멘탈. Style ([PlayGene.V2]):
 * a recipe by [Tier] (the potential decides which styles it can take up: the best never the
 * misread rules or the rule-bound beginners' styles), its nation's favourite styles
 * [Schools.FAVOUR] times as often, at an intensity k (log-uniform 0.7..1.7); a second style at
 * half strength 40% (one the tier could take up at that strength); a situation overlay 25%
 * (gambler 7, locker 7, panic 4, streaky 4, chameleon 3); its own [taste]s, a score reading and a
 * late phase ([situations]); and 예측력 erring on the far side [FAR_SHARE] of the time.
 *
 * A player is never born a copy: genes equal to an alive player's (as saved, so after rounding)
 * draw their tastes again ([recruit]'s `alive`), and a plain max-expectation player always has a
 * taste that changes at least 3% of its decisions ([Taste] ranges measured in the 생성 다양화 study).
 */
object PlayerGenerator {
    const val GEN_SEED = 0x67656E5EED01L
    private const val NAME_SALT = 0x4E414D45L
    private const val IDENTITY_SALT = 0x1DL
    private const val PROFILE_SALT = 0x9FL

    const val SLIP_MIN = 0.003
    const val SLIP_MAX = 0.30
    const val FEEL_MIN = 0.1
    const val FEEL_MAX = 3.0

    /** Share of players whose mistakes are by feel (the rest slip). */
    const val FEEL_SHARE = 0.6

    /** Share with a second style mixed in. */
    const val BLEND = 0.4

    /** Share whose 예측력 errs on the far side ([PlayGene.far]). */
    const val FAR_SHARE = 0.3

    /** Draws of the tastes before a player born equal to an alive one is kept as it is. */
    const val REDRAWS = 10

    /** Situation overlays and their shares (25% in all). */
    val OVERLAY_SHARES: List<Pair<String, Double>> = listOf("gambler" to 0.07, "locker" to 0.07, "panic" to 0.04, "streaky" to 0.04, "chameleon" to 0.03)

    /** Mean and spread of the potential. */
    const val POTENTIAL_MEAN = 64.0
    const val POTENTIAL_SD = 13.0

    /** θ of a non-risk spec (unused by its plan; the [CustomAiSpec] default). */
    const val PLAIN_THETA = 0.03

    val TEMPERS: List<Temper> = Temper.entries.filter { it != Temper.NONE }

    fun key(i: Int): String = "g" + i.toString().padStart(3, '0')

    /** The index of a generated key ("g007" → 7), null for any other key. */
    fun index(key: String): Int? = key.removePrefix("g").takeIf { key.startsWith("g") }?.toIntOrNull()?.takeIf { it > 0 && key(it) == key }

    /** The stream naming player [i], apart from its genes: a name clash never changes them. */
    internal fun nameRng(i: Int) = Random(DiceStream.mix(GEN_SEED, NAME_SALT, i.toLong()))

    /** Which styles a potential can take up. */
    enum class Tier(val minAvg: Double) {
        TOP(179.0),
        MID(165.0),
        LOW(150.0),
        NOVICE(0.0),
        ;

        companion object {
            fun of(potential: Int): Tier = when {
                potential >= 75 -> TOP
                potential >= 58 -> MID
                potential >= 42 -> LOW
                else -> NOVICE
            }
        }
    }

    /**
     * How often a recipe is drawn in a tier: the clearly visible styles more than the subtle
     * ones, rule-bound beginners' styles mostly among low potentials, misread rules never at the top.
     */
    fun weight(key: String, tier: Tier): Double = when (key) {
        "ev" -> if (tier == Tier.TOP) 3.0 else 0.3
        "dumper", "bigchoice", "contrarian" -> 0.3
        "ss3", "fh2pair" -> when (tier) {
            Tier.TOP -> 0.0
            Tier.MID -> 0.5
            else -> 1.5
        }
        "high", "topdown", "bottomup", "allornothing", "runonly" -> when (tier) {
            Tier.TOP, Tier.MID -> 0.0
            Tier.LOW -> 1.0
            Tier.NOVICE -> 2.5
        }
        "hunter", "collector", "purist", "lower", "timid", "straight", "fourkind", "scratcher", "noscratch", "copycat", "fullhouse" -> 1.5
        else -> 1.0
    }

    /**
     * Whether [r] may be a second style in [tier]: a style with an intensity whose half strength
     * the tier could take up; the most promising never take one that brings its rule along
     * (두 가지 눈, 0점 거부) or scores below the tier's floor at full strength.
     */
    fun secondaryFor(r: PlayRecipe, tier: Tier): Boolean {
        if (!r.scales || r.key == "ev" || avgAt(r, 0.5) < tier.minAvg) return false
        if (tier != Tier.TOP) return true
        val half = r.style(0.5)
        return r.avg > tier.minAvg && half.keep == KeepRule.ANY && half.box == BoxRule.ANY
    }

    /** A recipe's average score at intensity [k] (the more of a style, the further from max expectation). */
    fun avgAt(r: PlayRecipe, k: Double): Double = if (r.scales) PlayStyles.EV_AVG - (PlayStyles.EV_AVG - r.avg) * k else r.avg

    /**
     * New player [i] debuting in [season] ([founding]: one of a new league's first players, of
     * any age), named apart from [taken]; [boost] tilts the nation draw, [meta] adds to recipes'
     * weights (a style in fashion after a cup).
     */
    fun recruit(
        i: Int,
        season: Int,
        founding: Boolean,
        taken: Set<String>,
        boost: (Nation) -> Double = { 1.0 },
        meta: Map<String, Double> = emptyMap(),
        alive: Set<String> = emptySet(),
    ): PlayerRecord {
        val key = key(i)
        val idRng = Random(DiceStream.mix(GEN_SEED, IDENTITY_SALT, i.toLong()))
        val nation = Nations.draw(idRng, boost)
        val gender = if (idRng.nextBoolean()) Gender.MALE else Gender.FEMALE
        val age = (if (founding) Profiles.FOUNDING_AGES else Profiles.ROOKIE_AGES).draw(idRng).toInt()
        val town = PlayerNames.hometown(nation, idRng)
        val name = PlayerNames.unique(nation, gender, age, nameRng(i), taken)
        val rng = Random(DiceStream.mix(GEN_SEED, PROFILE_SALT, i.toLong()))
        val potential = potential(rng)
        val school = Schools.of(nation.code)
        val id = Identity(
            nation.code, gender, age, Profiles.drawAbilities(rng, key, age, potential, school.bias), potential,
            TEMPERS[rng.nextInt(TEMPERS.size)], rng.nextDouble() < FEEL_SHARE,
        )
        var spec = Profiles.apply(CustomAiSpec(key, name, play = style(rng, potential, school, meta)), id)
        var redraws = 0
        while (spec.play!!.encode() in alive && redraws++ < REDRAWS) {
            val g = spec.play!!
            spec = spec.copy(play = g.copy(taste = taste(rng, Tier.of(potential), plain = g.plain)))
        }
        return PlayerRecord(key, name, town, Origin.GENERATED, season, null, null, spec, identity = id)
    }

    fun potential(rng: Random): Int = Math.round(POTENTIAL_MEAN + POTENTIAL_SD * rng.nextGaussian()).toInt().coerceIn(25, 97)

    /** A style for a player of [potential] from [school], by the shares above. */
    fun style(rng: Random, potential: Int, school: School, meta: Map<String, Double> = emptyMap()): PlayGene {
        val tier = Tier.of(potential)
        val k0 = round(Math.exp(uniform(rng, Math.log(0.7), Math.log(1.7))), 2)
        val eligible = PlayStyles.RECIPES.filter { avgAt(it, k0) >= tier.minAvg && weight(it.key, tier) > 0 }
        fun favour(key: String) = (if (key in school.favoured) Schools.FAVOUR else 1.0) * (1 + (meta[key] ?: 0.0).coerceAtLeast(-0.9))
        val primary = pick(rng, eligible.ifEmpty { PlayStyles.RECIPES.take(1) }.map { it to weight(it.key, tier) * favour(it.key) })
        val k = if (primary.scales) k0 else 1.0
        val secondary = if (rng.nextDouble() < BLEND) {
            pick(rng, PlayStyles.RECIPES.filter { it.key != primary.key && secondaryFor(it, tier) }.map { it to favour(it.key) }).key
        } else null
        val overlay = overlay(rng, school)
        val moods = if (overlay == "chameleon") List(3) { pick(rng, PlayStyles.RECIPES.filter { it.scales }.map { it to favour(it.key) }).key } else emptyList()
        val far = rng.nextDouble() < FAR_SHARE
        val g = situations(rng, PlayGene(primary.key, k, secondary, overlay, moods, v = PlayGene.V2, far = far))
        return g.copy(taste = taste(rng, tier, plain = g.plain))
    }

    /**
     * Its own tastes ([Taste]), each present about a third of the time, in ranges that change a
     * few percent of the decisions for a point or two each (measured on [StylePlan]); the most
     * promising ([Tier.TOP]) take the cheaper ones a quarter of the time each, so the best stay as
     * strong as before. A [plain] max-expectation player gets at least one that changes 3% or more ([strong]).
     */
    fun taste(rng: Random, tier: Tier, plain: Boolean = false): Taste {
        val top = tier == Tier.TOP
        val p = if (top) 0.25 else 0.35
        fun has() = rng.nextDouble() < p
        fun u(lo: Double, hi: Double, step: Double) = round(Math.round(uniform(rng, lo, hi) / step) * step, 2)
        fun signed(pos: Double, lo: Double, hi: Double, negLo: Double, negHi: Double, step: Double = 1.0) =
            if (rng.nextDouble() < pos) u(lo, hi, step) else -u(negLo, negHi, step)
        var t = if (top) Taste(
            fullHouse = if (has()) signed(0.75, 3.0, 7.0, 3.0, 4.0) else 0.0,
            fourKind = if (has()) signed(0.5, 3.0, 7.0, 3.0, 7.0) else 0.0,
            choice = if (has()) u(3.0, 5.0, 1.0) else 0.0,
            snob = if (has()) u(1.0, 2.0, 0.5) else 0.0,
            yacht = if (has()) signed(0.4, 10.0, 15.0, 10.0, 18.0) else 0.0,
            straight = if (has()) signed(0.5, 3.0, 8.0, 2.0, 5.0) else 0.0,
            bonus = if (has()) u(0.1, 0.15, 0.05) else 0.0,
            zero = if (has()) u(3.0, 5.0, 1.0) else 0.0,
            cost = if (has()) -u(1.0, 3.0, 0.5) else 0.0,
        ) else Taste(
            fullHouse = if (has()) signed(0.5, 3.0, 7.0, 3.0, 7.0) else 0.0,
            fourKind = if (has()) signed(0.5, 3.0, 7.0, 3.0, 7.0) else 0.0,
            choice = if (has()) signed(0.6, 2.0, 6.0, 2.0, 4.0) else 0.0,
            snob = if (has()) u(1.0, 3.0, 0.5) else 0.0,
            yacht = if (has()) signed(0.6, 10.0, 40.0, 10.0, 25.0) else 0.0,
            straight = if (has()) signed(0.5, 3.0, 8.0, 2.0, 5.0) else 0.0,
            bonus = if (has()) u(0.1, 0.3, 0.05) else 0.0,
            zero = if (has()) u(3.0, 8.0, 1.0) else 0.0,
            cost = if (has()) signed(0.4, 0.5, 1.5, 1.0, 3.0, 0.5) else 0.0,
        )
        if (has()) t = t.copy(face = 1 + rng.nextInt(6), faceBias = if (top) u(3.0, 4.0, 1.0) else u(3.0, 6.0, 1.0))
        if (has()) {
            t = if (top) t.copy(fill = if (rng.nextBoolean()) 1 else 3, fillBias = u(1.5, 2.0, 0.5))
            else t.copy(fill = rng.nextInt(Style.FILL_ORDERS.size), fillBias = u(1.5, 3.0, 0.5))
        }
        if (has()) {
            val boxes = if (top) SCRAP_BOXES_TOP else SCRAP_BOXES
            t = t.copy(scrap = boxes[rng.nextInt(boxes.size)].ordinal, scrapBias = if (top) u(3.0, 4.0, 1.0) else u(3.0, 6.0, 1.0))
        }
        return if (plain) strengthen(rng, t, tier) else t
    }

    /** [t] with one taste that changes 3% or more of the decisions when it has none ([strong]): a plain player's. */
    fun strengthen(rng: Random, t: Taste, tier: Tier): Taste {
        if (strong(t)) return t
        fun u(lo: Double, hi: Double, step: Double) = round(Math.round(uniform(rng, lo, hi) / step) * step, 2)
        return if (tier == Tier.TOP) when (rng.nextInt(3)) {
            0 -> t.copy(choice = u(3.0, 5.0, 1.0))
            1 -> t.copy(straight = -u(4.0, 5.0, 1.0))
            else -> t.copy(zero = 5.0)
        } else when (rng.nextInt(6)) {
            0 -> t.copy(choice = u(3.0, 6.0, 1.0))
            1 -> t.copy(bonus = u(0.2, 0.3, 0.05))
            2 -> t.copy(snob = 3.0)
            3 -> t.copy(face = 1 + rng.nextInt(6), faceBias = u(5.0, 6.0, 1.0))
            4 -> t.copy(zero = u(5.0, 8.0, 1.0))
            else -> t.copy(fill = rng.nextInt(Style.FILL_ORDERS.size), fillBias = u(2.0, 3.0, 0.5))
        }
    }

    /** Boxes a taste may make its sacrifice box (the most promising: the cheaper ones). */
    private val SCRAP_BOXES = listOf(Category.ACES, Category.DEUCES, Category.FOUR_OF_A_KIND, Category.LARGE_STRAIGHT, Category.YACHT)
    private val SCRAP_BOXES_TOP = listOf(Category.ACES, Category.FOUR_OF_A_KIND, Category.LARGE_STRAIGHT)

    /** Whether [t] has a taste that changes 3% or more of a plain player's decisions on its own. */
    fun strong(t: Taste): Boolean =
        t.choice >= 3 || t.choice <= -4 || t.snob >= 3 || t.faceBias >= 5 || t.yacht <= -20 || t.bonus >= 0.2 || t.zero >= 5 ||
            t.fillBias >= 2 || t.straight <= -4 || (t.scrapBias >= 4 && (t.scrap == Category.FOUR_OF_A_KIND.ordinal || t.scrap == Category.YACHT.ordinal))

    /**
     * The light situational genes of [g]: a score reading 35% of the time (from 8..20 points ahead
     * and/or behind it leans: [Lean]), a late phase 30% (from 3..6 boxes left).
     */
    fun situations(rng: Random, g: PlayGene): PlayGene {
        var out = g
        if (rng.nextDouble() < 0.35) {
            val margin = 8 + rng.nextInt(13)
            var ahead = if (rng.nextDouble() < 0.6) AHEAD[rng.nextInt(AHEAD.size)] else null
            val behind = if (rng.nextDouble() < 0.6) BEHIND[rng.nextInt(BEHIND.size)] else null
            if (ahead == null && behind == null) ahead = AHEAD[rng.nextInt(AHEAD.size)]
            out = out.copy(margin = margin, ahead = ahead, behind = behind)
        }
        if (rng.nextDouble() < 0.3) out = out.copy(lateFrom = 3 + rng.nextInt(4), late = Lean.entries[rng.nextInt(Lean.entries.size)])
        return out
    }

    private val AHEAD = listOf(Lean.SAFE, Lean.NEAR, Lean.FAR, Lean.BOLD)
    private val BEHIND = listOf(Lean.SAFE, Lean.NEAR, Lean.BOLD)

    private fun overlay(rng: Random, school: School): String? {
        val shares = OVERLAY_SHARES.map { (o, p) -> o to if (o == school.overlay) p * Schools.FAVOUR else p }
        var u = rng.nextDouble()
        for ((o, p) in shares) {
            u -= p
            if (u < 0) return o
        }
        return null
    }

    internal fun <T> pick(rng: Random, xs: List<Pair<T, Double>>): T {
        var x = rng.nextDouble() * xs.sumOf { it.second }
        for ((v, w) in xs) {
            x -= w
            if (x < 0) return v
        }
        return xs.last().first
    }

    internal fun uniform(rng: Random, lo: Double, hi: Double) = lo + (hi - lo) * rng.nextDouble()
    internal fun logUniform(rng: Random, lo: Double, hi: Double) = lo * Math.pow(hi / lo, rng.nextDouble())

    /** [x] rounded to [digits] decimals (kept short in the save file and the description). */
    internal fun round(x: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(x * f) / f
    }

    /** [x] rounded to a multiple of 0.05. */
    internal fun twentieths(x: Double): Double = Math.round(x * 20) / 20.0
}

/** The family of a generated or evolved player, by its base gene: BELIEF, then SITUATION (a temper), RISK, EV. */
fun familyOf(spec: CustomAiSpec): Family = spec.play?.let { familyOf(it) } ?: when {
    spec.base == CustomBase.EV && spec.belief != null -> Family.BELIEF
    spec.temper != Temper.NONE -> Family.SITUATION
    spec.base == CustomBase.RISK -> Family.RISK
    spec.base == CustomBase.WIN -> Family.WIN
    else -> Family.EV
}

/**
 * The family of a player with a style: a misread rule is 규칙 착각, a rule-bound style 단순 규칙,
 * the gambler overlay 위험 성향, another overlay 상황 판단, plain max expectation 기대점수형,
 * every other style 개성파.
 */
fun familyOf(gene: PlayGene): Family = when {
    gene.primary in MISREAD -> Family.BELIEF
    PlayStyles.recipe(gene.primary)?.scales == false && gene.primary != "ev" -> Family.RULE
    gene.overlay == "gambler" -> Family.RISK
    gene.overlay != null -> Family.SITUATION
    gene.primary == "ev" -> Family.EV
    else -> Family.CHARACTER
}

private val MISREAD = setOf("ss3", "fh2pair")

/** The league AI of a generated or evolved player (light: never the win search). */
fun leagueAi(record: PlayerRecord, tables: Tables, extra: LeagueTables): LeagueAi = CustomAis.build(
    record.spec, tables, extra, key = record.key, name = record.name, family = familyOf(record.spec),
    desc = "${record.origin.label} 선수 · ${record.spec.describe()}",
)

/** A legend rebuilt from its archived genes at its best (the legend match, the lab). */
fun leagueAi(legend: Legend, tables: Tables, extra: LeagueTables): LeagueAi = leagueAi(legend.asPlayer().copy(spec = legend.atPeak()), tables, extra)

/**
 * The generated and evolved players (league/roster.txt, `roster 1`): the alive ones, the newest
 * [MAX_RETIRED] retired and up to [MAX_LEGENDS] legends (retired AIs with honours, with genes).
 * Base AIs and mine are never in it. [reserved] names (the base AIs') are never given out.
 * Not thread-safe: callers synchronise.
 */
class PlayerBook(reserved: Collection<String> = emptyList()) {
    /** Names no generated or evolved AI may take (the base AIs' names: they keep theirs). */
    var reserved: Set<String> = reserved.toSet()

    private val byKey = LinkedHashMap<String, PlayerRecord>()
    private val retiredList = ArrayList<RetiredRecord>()
    private val legendList = ArrayList<Legend>()

    /** Generated indices that have left: never generated again, even after their retired record is dropped. */
    private val gone = TreeSet<Int>()

    /**
     * The last season whose evolution ran (the rollover is idempotent by season): [Evolution.run]
     * replays any season up to it, so a league reset that starts again from season 1 must start
     * a new book too (delete roster.txt with the ladder and seasons).
     */
    var evolvedSeason = 0
        internal set

    /** Alive players, oldest first. */
    val alive: List<PlayerRecord> get() = byKey.values.toList()

    /** Retired players, oldest first (the newest [MAX_RETIRED]). */
    val retired: List<RetiredRecord> get() = retiredList

    /** Legends, oldest first. */
    val legends: List<Legend> get() = legendList

    val size: Int get() = byKey.size

    operator fun get(key: String): PlayerRecord? = byKey[key]

    operator fun contains(key: String): Boolean = key in byKey

    fun retiredRecord(key: String): RetiredRecord? = retiredList.lastOrNull { it.key == key } ?: legend(key)?.record

    fun legend(key: String): Legend? = legendList.firstOrNull { it.key == key }

    /** The name of any AI this book knows (alive, retired or legend). */
    fun name(key: String): String? = byKey[key]?.name ?: retiredRecord(key)?.name

    /**
     * Archived genes of every legend (id = its key). To play one, use [leagueAi] with the
     * [Legend]: [CustomAis.build]'s defaults would make it one of mine ("custom-" key, MINE).
     */
    fun legendSpecs(): List<CustomAiSpec> = legendList.map { it.spec }

    /** Every name in use: alive, retired, legends and [reserved]. */
    fun names(): Set<String> = HashSet<String>().apply {
        addAll(reserved)
        byKey.values.forEach { add(it.name) }
        retiredList.forEach { add(it.name) }
        legendList.forEach { add(it.name) }
    }

    /** The next made player's number (u1, u2, ...): never given out twice, as honours and records are by key. */
    var madeNext: Int = 1
        private set

    private fun madeIndex(key: String): Int? = if (Origin.of(key) == Origin.CREATED) key.drop(1).toIntOrNull() else null

    /** Adds the player [draft] describes, debuting in [season] (선수 만들기); its key is the next made one. */
    fun make(draft: PlayerDraft, season: Int): PlayerRecord {
        val key = "u${madeNext++}"
        val r = PlayerMaker.build(draft, key, season, null)
        byKey[key] = r
        return r
    }

    /** [key] remade from [draft] (its records and debut stay); null when it is not alive here. */
    fun edit(key: String, draft: PlayerDraft, season: Int): PlayerRecord? {
        val old = byKey[key] ?: return null
        val r = PlayerMaker.build(draft, key, season, old)
        byKey[key] = r
        return r
    }

    /** Takes a player the player made out of the book (지우기): it leaves as if deleted, no retirement. */
    fun remove(key: String): Boolean = Origin.of(key) == Origin.CREATED && byKey.remove(key) != null

    /** The next generated index: every new generated player takes the next one, so a key is never given out twice. */
    var next: Int = 1
        internal set

    /**
     * Brings the book up to [n] alive players with new generated ones debuting in [season]
     * (at most [max]); returns them. Lowering [n] removes nobody: the population only shrinks
     * through retirements. [founding] players are of every age (a new league's first players,
     * or a big top-up: 50 or more at once, or a fifth of [n]); others are rookies. Rookies'
     * nations are rebalanced toward the weights ([Nations.rebalance]); [meta] tilts styles.
     */
    fun ensureGenerated(
        n: Int,
        season: Int = 1,
        max: Int = Int.MAX_VALUE,
        meta: Map<String, Double> = emptyMap(),
        founding: Boolean? = null,
        accept: (CustomAiSpec) -> Boolean = { true },
    ): List<PlayerRecord> {
        val count = minOf(n - byKey.size, max)
        if (count <= 0) return emptyList()
        val old = founding ?: (season <= 1 || count >= 50 || count * 5 >= n)
        val added = ArrayList<PlayerRecord>()
        val taken = names().toMutableSet()
        val nations = nationCounts().toMutableMap()
        val genes = geneSet()
        var total = byKey.size
        var refused = 0
        while (added.size < count && refused < count * Evolution.TRIES) {
            val i = next++
            val boost = if (old) { _: Nation -> 1.0 } else Nations.rebalance(nations, total)
            val r = PlayerGenerator.recruit(i, season, old, taken, boost, meta, genes)
            // A player the league's filter refuses (the chaos preset's only flawed players) is never generated.
            if (!accept(r.spec)) {
                refused++
                continue
            }
            taken += r.name
            nations.merge(r.identity!!.nation, 1, Int::plus)
            r.spec.play?.let { genes += it.encode() }
            total++
            byKey[r.key] = r
            added += r
        }
        return added
    }

    /** The style genes of the alive players as saved (a newcomer is never born equal to one). */
    fun geneSet(): MutableSet<String> = byKey.values.mapNotNullTo(HashSet()) { it.spec.play?.encode() }

    /**
     * Styles in fashion (a world champion's): extra weight for rookies and pupils taking them
     * up, fading each season ([fashion]).
     */
    var meta: Map<String, Double> = emptyMap()
        private set

    /** A season passes: fashions fade to 60%, and [style] (a world champion's) comes into fashion. */
    fun fashion(style: String?) {
        val next = HashMap<String, Double>()
        for ((k, v) in meta) if (v * 0.6 >= 0.05) next[k] = PlayerGenerator.round(v * 0.6, 2)
        style?.let { next[it] = minOf(1.5, (next[it] ?: 0.0) + 0.6) }
        meta = next
    }

    /** The season its players saved before styles took one (the league's reform), 0 when it never had any. */
    var reformed: Int = 0
        internal set

    /** Alive players by nation code. */
    fun nationCounts(): Map<String, Int> = byKey.values.groupingBy { it.identity?.nation ?: Nations.KR.code }.eachCount()

    /** Replaces an alive player's record (same key): its genes, identity or peak after a season. */
    fun update(record: PlayerRecord) {
        if (record.key in byKey) byKey[record.key] = record
    }

    /** Adds a new player (an evolved child); a key already known is refused. */
    fun add(record: PlayerRecord): Boolean {
        if (record.key in byKey || retiredRecord(record.key) != null) return false
        val r = record.copy(spec = record.spec.copy(id = record.key, name = record.name))
        byKey[record.key] = if (r.identity == null) r.copy(identity = Profiles.legacy(r, r.born)) else r
        PlayerGenerator.index(record.key)?.let { if (it >= next) next = it + 1 }
        return true
    }

    /** Remembers [rating] as [key]'s best season-end rating if it is higher. */
    fun notePeak(key: String, rating: Double) {
        val r = byKey[key] ?: return
        if (rating.isNaN()) return
        if (r.peak.isNaN() || rating > r.peak) byKey[key] = r.copy(peak = rating, identity = r.identity?.let { it.copy(peakAbl = it.abilities) })
    }

    /**
     * Retires [key] at the end of [season]: its retired record (best rating [bestRating] or its
     * peak, [titles]) and, with an [honours] summary, a legend that keeps its genes. Null when
     * [key] is not alive here.
     */
    fun retire(key: String, season: Int, titles: Int = 0, bestRating: Double = Double.NaN, honours: String? = null): RetiredRecord? {
        val p = byKey.remove(key) ?: return null
        // Only the keys older versions could generate again (they made g001..g300).
        PlayerGenerator.index(key)?.takeIf { it <= OLD_GENERATED }?.let { gone += it }
        val best = listOf(bestRating, p.peak).filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
        val id = p.identity
        val r = RetiredRecord(
            key, p.name, p.born, season, p.parentA, p.parentB, best, titles, p.hometown, p.origin,
            id?.nation ?: Nations.KR.code, id?.gender, id?.debutAge ?: 0,
        )
        retiredList += r
        while (retiredList.size > MAX_RETIRED) retiredList.removeAt(0)
        if (honours != null) {
            legendList.removeAll { it.key == key }
            legendList += Legend(r, p.spec, honours.ifBlank { "-" }, id)
            trimLegends()
        }
        return r
    }

    // Over the cap, the least decorated (fewest titles, then honours, then the oldest) goes.
    private fun trimLegends() {
        while (legendList.size > MAX_LEGENDS) {
            val least = legendList.minWith(
                compareBy<Legend> { it.record.titles }.thenBy { Evolution.counts(it.honours).values.sum() }.thenBy { it.record.retired },
            )
            legendList.remove(least)
        }
    }

    /** The parents of any AI this book knows (null each when unknown or generated). */
    fun parents(key: String): Pair<String?, String?> {
        byKey[key]?.let { return it.parentA to it.parentB }
        val r = retiredRecord(key) ?: return null to null
        return r.parentA to r.parentB
    }

    /**
     * The family tree upwards: generation 1 is [parentA, parentB], generation g has 2^g slots
     * (slot i's parents are slots 2i and 2i+1 of the next), null where unknown. Generations past
     * the last known ancestor are left out (a generated player has none).
     */
    fun ancestors(key: String, generations: Int = 4): List<List<String?>> {
        val out = ArrayList<List<String?>>()
        var row: List<String?> = listOf(key)
        repeat(generations) {
            val next = row.flatMap { k -> if (k == null) listOf(null, null) else parents(k).toList() }
            if (next.all { it == null }) return out
            out += next
            row = next
        }
        return out
    }

    /** Children of [key] (alive, retired or legends), by debut season. */
    fun children(key: String): List<String> = childIndex()[key].orEmpty()

    /** Descendants by generation: children first, then grandchildren, ... (each AI once). */
    fun descendants(key: String, generations: Int = 4): List<List<String>> {
        val index = childIndex()
        val out = ArrayList<List<String>>()
        val seen = hashSetOf(key)
        var row = listOf(key)
        repeat(generations) {
            val next = row.flatMap { index[it].orEmpty() }.filter { seen.add(it) }
            if (next.isEmpty()) return out
            out += next
            row = next
        }
        return out
    }

    private fun childIndex(): Map<String, List<String>> {
        val all = LinkedHashMap<String, Triple<Int, String?, String?>>()
        for (l in legendList) all[l.key] = Triple(l.record.born, l.record.parentA, l.record.parentB)
        for (r in retiredList) all[r.key] = Triple(r.born, r.parentA, r.parentB)
        for (p in byKey.values) all[p.key] = Triple(p.born, p.parentA, p.parentB)
        val index = HashMap<String, MutableList<Pair<Int, String>>>()
        for ((k, v) in all) {
            val (born, a, b) = v
            a?.let { index.getOrPut(it) { ArrayList() } += born to k }
            if (b != null && b != a) index.getOrPut(b) { ArrayList() } += born to k
        }
        // By debut, then by key as a number would sort ("e3_2" before "e3_10").
        return index.mapValues { (_, xs) -> xs.sortedWith(compareBy({ it.first }, { it.second.length }, { it.second })).map { it.second } }
    }

    // ---- saving --------------------------------------------------------------------------------

    fun encode(): String = buildString {
        append("$HEADER\n")
        append("evolved $evolvedSeason\n")
        append("gone ${gone.joinToString(",").ifEmpty { "-" }}\n")
        append("next $next\n")
        if (madeNext > 1) append("made $madeNext\n")
        if (reformed > 0) append("reformed $reformed\n")
        if (meta.isNotEmpty()) append("meta ${meta.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }}\n")
        // Identity and style are name=value tokens after the fields older versions read.
        for (p in byKey.values) {
            append("ai ${p.key} ${esc(p.name)} ${esc(p.hometown)} ${p.origin.name} ${p.born} ${p.parentA ?: "-"} ${p.parentB ?: "-"} ")
            append("${p.spec.genes().joinToString(" ")} ${num(p.peak)}")
            p.identity?.let { append(' ').append(it.tokens()) }
            if (p.keep) append(" keep=1")
            append(styleTokens(p.spec)).append('\n')
        }
        for (r in retiredList) {
            append("retired ${r.key} ${esc(r.name)} ${r.born} ${r.retired} ${r.parentA ?: "-"} ${r.parentB ?: "-"} ${num(r.bestRating)} ${r.titles} ")
            append("${esc(r.hometown)} ${r.origin.name} nat=${r.nation}")
            r.gender?.let { append(" sex=").append(it.code) }
            if (r.debutAge > 0) append(" da=").append(r.debutAge)
            append('\n')
        }
        for (l in legendList) {
            val r = l.record
            append("legend ${r.key} ${esc(r.name)} ${r.born} ${r.retired} ${l.spec.genes().joinToString(" ")} ${esc(l.honours)} ")
            append("${r.parentA ?: "-"} ${r.parentB ?: "-"} ${num(r.bestRating)} ${r.titles} ${esc(r.hometown)} ${r.origin.name}")
            l.identity?.let { append(' ').append(it.tokens()) }
            append(styleTokens(l.spec)).append('\n')
        }
    }

    private fun styleTokens(spec: CustomAiSpec): String = buildString {
        spec.play?.let { append(" play=").append(it.encode()) }
        if (spec.temperStrength != 1.0) append(" ts=").append(spec.temperStrength)
    }

    companion object {
        const val HEADER = "roster 1"
        const val MAX_RETIRED = 3000
        const val MAX_LEGENDS = 300

        /** The most generated players older versions made (g001..g300): their keys are kept in `gone`. */
        const val OLD_GENERATED = 300

        private const val G = CustomAiSpec.GENES

        /**
         * Reads roster.txt. Each line on its own: a line cut off or from a newer version is
         * skipped. [encode] ends every line with a newline, so a last line without one was cut
         * off mid-write and is dropped (its last number could otherwise read as a shorter one).
         * Throws only when the file is not a roster at all (the caller starts afresh).
         */
        fun decode(text: String, reserved: Collection<String> = emptyList()): PlayerBook {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == HEADER) { "not a roster file" }
            val b = PlayerBook(reserved)
            val body = lines.drop(1).let { if (text.endsWith("\n")) it else it.dropLast(1) }
            for (line in body) {
                // A bad line is skipped; running out of memory is not (it would lose the players).
                try {
                    b.decodeLine(line.trim().split(' '))
                } catch (e: Exception) {
                    continue
                }
            }
            return b
        }

        private fun esc(s: String): String =
            if (s.isEmpty()) "-" else if (s == "-") "%2D" else s.replace("%", "%25").replace(" ", "%20").replace("\n", "%0A").replace("\r", "%0D")

        private fun unesc(s: String): String =
            if (s == "-") "" else s.replace("%20", " ").replace("%0A", "\n").replace("%0D", "\r").replace("%2D", "-").replace("%25", "%")

        private fun num(x: Double) = if (x.isNaN()) "-" else String.format(Locale.ROOT, "%.1f", x)
        private fun numOf(s: String) = if (s == "-") Double.NaN else s.toDouble()
        private fun keyOf(s: String) = s.takeIf { it != "-" }
    }

    // A style from a newer version this one cannot read plays the genes before it.
    private fun withStyle(spec: CustomAiSpec, t: Map<String, String>): CustomAiSpec =
        spec.copy(play = t["play"]?.let { PlayGene.decode(it) }, temperStrength = t["ts"]?.toDoubleOrNull() ?: 1.0)

    /** One saved line; every field is read before anything is added (a cut line changes nothing). */
    private fun decodeLine(f: List<String>) {
        when (f[0]) {
            "evolved" -> evolvedSeason = f[1].toInt()
            "gone" -> if (f[1] != "-") gone += f[1].split(',').map { it.toInt() }.onEach { if (it >= next) next = it + 1 }
            "next" -> next = maxOf(next, f[1].toInt())
            "made" -> madeNext = maxOf(madeNext, f[1].toInt())
            "reformed" -> reformed = f[1].toInt()
            "meta" -> meta = f[1].split(',').associate { it.substringBefore(':') to it.substringAfter(':').toDouble() }
            "ai" -> {
                // key name town origin born parentA parentB, the genes, the peak, then tokens.
                require(f.size >= 8 + G + 1)
                val key = f[1]
                val name = unesc(f[2])
                val t = Identity.fields(f, 9 + G)
                val spec = withStyle(CustomAiSpec.ofGenes(key, name, f.subList(8, 8 + G)), t)
                val r = PlayerRecord(key, name, unesc(f[3]), Origin.valueOf(f[4]), f[5].toInt(), keyOf(f[6]), keyOf(f[7]), spec, numOf(f[8 + G]))
                // A line from before identities: read as the Korean player it always was.
                byKey[key] = r.copy(identity = Identity.read(t) ?: Profiles.legacy(r, evolvedSeason + 1), keep = t["keep"] == "1")
                PlayerGenerator.index(key)?.let { if (it >= next) next = it + 1 }
                madeIndex(key)?.let { if (it >= madeNext) madeNext = it + 1 }
            }
            "retired" -> {
                require(f.size >= 11)
                val t = Identity.fields(f, 11)
                retiredList += RetiredRecord(
                    f[1], unesc(f[2]), f[3].toInt(), f[4].toInt(), keyOf(f[5]), keyOf(f[6]), numOf(f[7]), f[8].toInt(), unesc(f[9]), Origin.valueOf(f[10]),
                    Nations.of(t["nat"])?.code ?: Nations.KR.code, Gender.of(t["sex"]?.singleOrNull()), t["da"]?.toIntOrNull() ?: 0,
                )
                PlayerGenerator.index(f[1])?.let { if (it >= next) next = it + 1 }
                madeIndex(f[1])?.let { if (it >= madeNext) madeNext = it + 1 }
                while (retiredList.size > MAX_RETIRED) retiredList.removeAt(0)
            }
            "legend" -> {
                // key name born retired, the genes, honours, parentA parentB best titles town origin.
                val at = 5 + G
                require(f.size >= at + 7)
                val key = f[1]
                val name = unesc(f[2])
                val t = Identity.fields(f, at + 7)
                val spec = withStyle(CustomAiSpec.ofGenes(key, name, f.subList(5, at)), t)
                val id = Identity.read(t)
                val r = RetiredRecord(
                    key, name, f[3].toInt(), f[4].toInt(), keyOf(f[at + 1]), keyOf(f[at + 2]), numOf(f[at + 3]), f[at + 4].toInt(),
                    unesc(f[at + 5]), Origin.valueOf(f[at + 6]), id?.nation ?: Nations.KR.code, id?.gender, id?.debutAge ?: 0,
                )
                PlayerGenerator.index(key)?.let { if (it >= next) next = it + 1 }
                madeIndex(key)?.let { if (it >= madeNext) madeNext = it + 1 }
                legendList.removeAll { it.key == key }
                legendList += Legend(r, spec, unesc(f[at]).ifEmpty { "-" }, id)
                trimLegends()
            }
        }
    }
}

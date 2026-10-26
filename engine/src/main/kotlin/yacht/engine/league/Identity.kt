package yacht.engine.league

import yacht.engine.Category
import yacht.engine.DiceStream
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The four abilities (능력치), 1..99, higher is better (docs/LEAGUE_SPEC.md 1.3):
 * 판단력 [judgement] (how few mistakes: the slip or feel level), 예측력 [foresight] (how much of
 * the rest of the game it counts), 승부 감각 [sense] (choosing the box by the chance of winning
 * late in a game), 멘탈 [mental] (steadiness: no temper from [Profiles.CALM] up, a stronger
 * temper the lower it is).
 */
data class Abilities(val judgement: Int, val foresight: Int, val sense: Int, val mental: Int) {
    fun toList() = listOf(judgement, foresight, sense, mental)

    /** One number for the four, weighted by what they are worth in games. */
    val overall: Int get() = (0.55 * judgement + 0.2 * foresight + 0.1 * sense + 0.15 * mental).roundToInt()

    fun encode() = "$judgement,$foresight,$sense,$mental"

    companion object {
        val LABELS = listOf("판단력", "예측력", "승부 감각", "멘탈")
        val HELP = listOf(
            "수를 얼마나 정확히 고르는지예요. 높을수록 실수가 적어요.",
            "남은 판을 얼마나 멀리 내다보는지예요. 낮으면 눈앞의 점수에 끌려요.",
            "막판에 점수 차를 보고 이길 확률이 높은 칸을 고르는 감각이에요.",
            "흔들리지 않는 힘이에요. 낮으면 0점을 쓴 뒤 무너지거나, 막판에 지치거나, 판마다 기복이 커요.",
        )

        fun of(xs: List<Int>) = Abilities(xs[0], xs[1], xs[2], xs[3])

        fun decode(s: String): Abilities? = runCatching {
            val f = s.split(',').map { it.toInt().coerceIn(1, 99) }
            require(f.size >= 4)
            of(f)
        }.getOrNull()
    }
}

/**
 * The five tendencies (성향), -50..50, two-ended bars where neither end is better, read off a
 * player's style ([of]) for the screen: 대담성 안정↔도박, 칸 운영 아랫칸↔윗칸, 요트 사랑
 * 무관심↔집착, 0점 처리 과감히 버림↔끝까지 피함, 끈기 빨리 멈춤↔끝까지 굴림.
 */
data class Tendencies(val bold: Int, val side: Int, val yacht: Int, val zero: Int, val persist: Int) {
    fun toList() = listOf(bold, side, yacht, zero, persist)

    companion object {
        /** Bar names and their two ends (left = -50, right = +50). */
        val BARS = listOf(
            Triple("대담성", "안정", "도박"),
            Triple("칸 운영", "아랫칸", "윗칸"),
            Triple("요트 사랑", "무관심", "집착"),
            Triple("0점 처리", "과감히 버림", "끝까지 피함"),
            Triple("끈기", "빨리 멈춤", "끝까지 굴림"),
        )

        private fun bar(x: Double) = x.roundToInt().coerceIn(-50, 50)

        /** The bars of [s] (a plain max-expectation style is all zero). */
        fun of(s: Style): Tendencies {
            val y = s.like[Category.YACHT] ?: 0.0
            val ls = s.like[Category.LARGE_STRAIGHT] ?: 0.0
            val keepBold = when (s.keep) {
                KeepRule.ONE_FACE -> 15.0
                KeepRule.NONE -> 25.0
                KeepRule.HIGH -> 8.0
                else -> 0.0
            }
            val noScratch = s.box == BoxRule.NO_SCRATCH
            val bold = -4 * s.rerollCost + 0.35 * y + 0.15 * ls - 2 * s.zero + keepBold - (if (noScratch) 15.0 else 0.0)
            val small = s.hold.take(3).sum()
            val side = s.bonusLove + 5 * s.par + 20 * s.bonus + 5 * small
            val yacht = 0.6 * y - 3 * (s.zeroLike[Category.YACHT] ?: 0.0)
            val zero = 4 * s.zero + (if (noScratch) 25.0 else 0.0) - 1.5 * s.zeroLike.values.sum()
            val persist = -5 * s.rerollCost
            return Tendencies(bar(bold), bar(side), bar(yacht), bar(zero), bar(persist))
        }
    }
}

/**
 * Who a generated or evolved player is besides its genes: [nation] (a [Nations] code), [gender],
 * the age at its debut ([debutAge]: age = debutAge + season - born), its [abilities] and
 * [potential] (the ceiling young players grow toward), the temper a low 멘탈 brings
 * ([temperKind], never NONE) and whether its mistakes are by feel ([feel]); [peakAbl] are the
 * abilities of its best season, [lastAbl] those at the start of this season (the ▲▼ arrows).
 * Saved as `name=value` tokens on its roster line, which older versions ignore.
 */
data class Identity(
    val nation: String,
    val gender: Gender,
    val debutAge: Int,
    val abilities: Abilities,
    val potential: Int,
    val temperKind: Temper,
    val feel: Boolean,
    val peakAbl: Abilities? = null,
    val lastAbl: Abilities? = null,
) {
    fun age(season: Int, born: Int): Int = debutAge + (season - born)

    fun tokens(): String = buildString {
        append("nat=").append(nation).append(" sex=").append(gender.code).append(" da=").append(debutAge)
        append(" abl=").append(abilities.encode()).append(" pot=").append(potential)
        append(" tk=").append(temperKind.name).append(" mk=").append(if (feel) 'F' else 'S')
        peakAbl?.let { append(" pka=").append(it.encode()) }
        lastAbl?.let { append(" pab=").append(it.encode()) }
    }

    companion object {
        /** `name=value` tokens of a line (from [from]); others are skipped. */
        fun fields(f: List<String>, from: Int): Map<String, String> =
            f.drop(from).mapNotNull { t -> t.indexOf('=').takeIf { it > 0 }?.let { t.substring(0, it) to t.substring(it + 1) } }.toMap()

        /** The identity in [t] (from [fields]); null without one (a line of an older version). */
        fun read(t: Map<String, String>): Identity? = runCatching {
            val nation = t["nat"] ?: return null
            Identity(
                Nations.of(nation)?.code ?: Nations.KR.code,
                Gender.of(t["sex"]?.singleOrNull()) ?: Gender.MALE,
                t.getValue("da").toInt(),
                Abilities.decode(t.getValue("abl"))!!,
                t.getValue("pot").toInt().coerceIn(1, 99),
                Temper.entries.firstOrNull { it.name == t["tk"] && it != Temper.NONE } ?: Temper.TILT,
                t["mk"] == "F",
                t["pka"]?.let { Abilities.decode(it) },
                t["pab"]?.let { Abilities.decode(it) },
            )
        }.getOrNull()
    }
}

/**
 * Abilities to play and back, ages, growth and retirement (docs/LEAGUE_SPEC.md 1.3). Every number
 * here is part of the league's rules: changing one changes how saved players play.
 */
object Profiles {
    /** 판단력 at or above this makes no mistakes. */
    const val PERFECT = 92

    /** 멘탈 at or above this has no temper; below it the temper's strength is (CALM - mental) / 30. */
    const val CALM = 50

    /** 승부 감각 below this does not play for the win at all. */
    const val SENSE_FROM = 25

    /**
     * Points a game the mistakes of 판단력 cost against perfect play: 0 from [PERFECT], 3 at 80,
     * 8 at 65, 15 at 50, 25 at 35, 40 at 20, 65 at 1 (straight lines between).
     */
    private val DROP = listOf(PERFECT to 0.0, 80 to 3.0, 65 to 8.0, 50 to 15.0, 35 to 25.0, 20 to 40.0, 1 to 65.0)

    fun drop(j: Int): Double {
        if (j >= PERFECT) return 0.0
        for (i in 0 until DROP.size - 1) {
            val (hi, dh) = DROP[i]
            val (lo, dl) = DROP[i + 1]
            if (j >= lo) return dl + (dh - dl) * (j - lo).toDouble() / (hi - lo)
        }
        return DROP.last().second
    }

    /** 판단력 that costs [points] a game (the inverse of [drop]). */
    fun judgementOf(points: Double): Int {
        if (points <= 0.0) return 99
        for (i in 0 until DROP.size - 1) {
            val (hi, dh) = DROP[i]
            val (lo, dl) = DROP[i + 1]
            if (points <= dl) return (hi - (points - dh) / (dl - dh) * (hi - lo)).roundToInt().coerceIn(1, PERFECT - 1)
        }
        return 1
    }

    /** Feel τ (points) that costs [drop] a game (measured: 16.05 τ + 3.15 τ²). */
    fun tauFor(drop: Double): Double = PlayerGenerator.round((-16.05 + Math.sqrt(16.05 * 16.05 + 4 * 3.15 * drop)) / (2 * 3.15), 2)

    fun dropOfTau(tau: Double): Double = 16.05 * tau + 3.15 * tau * tau

    /** Slip chance that costs [drop] a game (measured: about 130 points a game at slip 1). */
    fun slipFor(drop: Double): Double = PlayerGenerator.round(minOf(0.5, drop / 130.0), 3)

    fun dropOfSlip(slip: Double): Double = slip * 130.0

    /** How far from 1 the `future` gene is at 예측력 [f]: 0 at 99, 0.6 at 1 (quadratic: average players are only a little short-sighted). */
    fun future(f: Int): Double = PlayerGenerator.twentieths((1 - 0.6 * ((99 - f) / 98.0).pow(2)).coerceIn(0.4, 1.0))

    /** 예측력 of a `future` gene (1 and above read as 99..). */
    fun foresightOf(future: Double): Int =
        if (future >= 1.0) 99 else (99 - 98 * Math.sqrt(((1 - future) / 0.6).coerceIn(0.0, 1.0))).roundToInt().coerceIn(1, 98)

    fun sense(s: Int): Double = PlayerGenerator.round(((s - SENSE_FROM) / (99.0 - SENSE_FROM)).coerceIn(0.0, 1.0), 2)

    fun temperStrength(m: Int): Double = if (m >= CALM) 0.0 else PlayerGenerator.round(((CALM - m) / 30.0).coerceIn(0.2, 1.5), 2)

    /**
     * [spec] playing with [id]'s abilities: its mistakes (slips or feel, by [Identity.feel]), its
     * style's foresight and 승부 감각, and its temper. The style itself ([CustomAiSpec.play]) stays.
     */
    fun apply(spec: CustomAiSpec, id: Identity): CustomAiSpec {
        val a = id.abilities
        val d = drop(a.judgement)
        val (mistake, amount) = when {
            d <= 0.0 -> CustomMistake.NONE to 0.0
            id.feel -> CustomMistake.FEEL to tauFor(d)
            else -> CustomMistake.SLIP to slipFor(d)
        }
        val strength = temperStrength(a.mental)
        val play = (spec.play ?: PlayGene("ev")).copy(future = future(a.foresight), sense = sense(a.sense))
        return spec.copy(
            mistake = mistake, amount = amount, play = play,
            temper = if (strength == 0.0) Temper.NONE else id.temperKind, temperStrength = if (strength == 0.0) 1.0 else strength,
        )
    }

    // ---- reading a player saved before identities ------------------------------------------------

    /** A stable number 0 until [n] from a key and a salt (the same every run). */
    fun hash(key: String, salt: Int, n: Int): Int = Math.floorMod(DiceStream.mix(key.hashCode().toLong(), salt.toLong(), 0x1D3L), n.toLong()).toInt()

    /**
     * Abilities read from [spec] (a player saved before abilities): 판단력 from its mistakes,
     * 예측력 from its foresight, 멘탈 from its temper, 승부 감각 low (it never played for the win);
     * values a gene does not pin down are placed by a hash of [key].
     */
    fun infer(spec: CustomAiSpec, key: String): Abilities {
        fun inRange(lo: Int, hi: Int, salt: Int) = if (hi <= lo) lo else lo + hash(key, salt, hi - lo + 1)
        val j = when (spec.effectiveMistake) {
            CustomMistake.NONE -> inRange(PERFECT, 99, 1)
            CustomMistake.SLIP -> judgementOf(dropOfSlip(spec.effectiveAmount))
            CustomMistake.FEEL -> judgementOf(dropOfTau(spec.amount))
        }
        val f = if (spec.base == CustomBase.EV && spec.future < 1.0) foresightOf(spec.future) else inRange(80, 99, 2)
        val s = inRange(5, SENSE_FROM, 3)
        val m = if (spec.temper == Temper.NONE) inRange(CALM, 95, 4) else inRange(CALM - 30, CALM - 15, 4)
        return Abilities(j, f, s, m)
    }

    /**
     * Debut age of a player saved before ages, read in [season]: drawn by a hash of the key (the
     * founding spread for season-1 generated players, the rookie spread for the rest), at least
     * 17 plus the seasons it has played and at most [MAX_AGE] - 2 now (an old league keeps its
     * veterans for a farewell of a few seasons), less the seasons played.
     */
    fun legacyDebutAge(key: String, born: Int, origin: Origin, season: Int): Int {
        val played = (season - born).coerceAtLeast(0)
        val pool = if (born <= 1 && origin == Origin.GENERATED) FOUNDING_AGES else ROOKIE_AGES
        val drawn = pool.draw(Random(DiceStream.mix(key.hashCode().toLong(), 0xA9EL))).toInt() + if (pool === ROOKIE_AGES) played else 0
        val now = drawn.coerceIn(minOf(17 + played, MAX_AGE - 2), MAX_AGE - 2)
        return now - played
    }

    /** The identity of a player saved before identities, read in [season] (Korean, as all were). */
    fun legacy(r: PlayerRecord, season: Int): Identity {
        val abl = infer(r.spec, r.key)
        val da = legacyDebutAge(r.key, r.born, r.origin, season)
        val age = da + (season - r.born)
        return Identity(
            Nations.KR.code, LegacyGender.guess(r.name, r.key), da, abl, potentialAt(abl, age, r.key),
            if (r.spec.temper != Temper.NONE) r.spec.temper else PlayerGenerator.TEMPERS[hash(r.key, 10, PlayerGenerator.TEMPERS.size)],
            r.spec.mistake == CustomMistake.FEEL,
        )
    }

    /**
     * A player saved before styles, with the style closest to its old genes (its strongest
     * taste, its risk attitude, its misremembered rule) and its own abilities: the league's
     * reform. Its mistakes come back about as they were (the abilities were read from them).
     */
    fun reform(r: PlayerRecord, season: Int): PlayerRecord {
        val id = r.identity ?: legacy(r, season)
        val s = r.spec
        fun k(x: Double) = PlayerGenerator.round(x.coerceIn(0.6, 1.8), 2)
        val gene = when {
            s.base == CustomBase.EV && s.belief != null -> when (s.belief) {
                "no-bonus" -> PlayGene("lower")
                "big-bonus" -> PlayGene("purist", 1.5)
                "fh-no-yacht" -> PlayGene("fullhouse")
                "four-only" -> PlayGene("fourkind")
                "yahtzee-straights", "cheap-yacht" -> PlayGene("straight")
                else -> PlayGene("ev")
            }
            s.base == CustomBase.RISK -> if (s.theta > 0) PlayGene("hunter", k(s.theta / 0.03), overlay = "gambler") else PlayGene("timid", k(-s.theta / 0.03))
            s.base == CustomBase.EV -> {
                val tastes = listOf(
                    "hunter" to s.yacht / 60, "straight" to s.straight / 12 - (if (s.yacht < 0) s.yacht / 20 else 0.0),
                    "purist" to s.bonus / 0.8, "noscratch" to s.zero / 15, "hoarder" to if (s.saveChoice) 1.0 else 0.0,
                )
                val (key, x) = tastes.maxBy { it.second }
                if (x <= 0.2) PlayGene("ev") else PlayGene(key, if (key == "hoarder") 1.0 else k(x))
            }
            else -> PlayGene("ev")
        }
        val temper = if (s.temper != Temper.NONE) s.temper else id.temperKind
        val next = id.copy(temperKind = temper, feel = s.mistake == CustomMistake.FEEL)
        val spec = apply(s.copy(base = CustomBase.EV, theta = PlayerGenerator.PLAIN_THETA, belief = null, play = gene), next)
        return r.copy(identity = next, spec = spec)
    }

    /** A potential for [abl] at [age]: room to grow when young. */
    fun potentialAt(abl: Abilities, age: Int, key: String): Int {
        val room = when {
            age < 22 -> 12
            age < 26 -> 7
            age < 29 -> 3
            else -> 0
        }
        return (abl.overall + room + hash(key, 11, 5)).coerceIn(1, 99)
    }

    // ---- aging ---------------------------------------------------------------------------------

    /** Peak and decline ages of each ability (before a player's own offset of -2..+2). */
    val PEAK = intArrayOf(28, 30, 29, 30)
    val DECLINE_FROM = intArrayOf(31, 34, 33, 35)

    /** Points lost per season at the start of the decline (then 35% more each season). */
    val DECLINE = doubleArrayOf(1.2, 0.6, 0.8, 0.5)

    /** A player's own offset of its peak (-2..+2: early peakers and late bloomers). */
    fun peakOffset(key: String): Int = hash(key, 20, 5) - 2

    /** Each ability's ceiling: the potential, -10..+10 by a hash of the key. */
    fun ceiling(potential: Int, key: String, i: Int): Int = (potential + hash(key, 30 + i, 21) - 10).coerceIn(1, 99)

    fun growthRate(age: Int): Double = when {
        age <= 20 -> 0.22
        age <= 23 -> 0.17
        age <= 26 -> 0.12
        else -> 0.08
    }

    /**
     * One season older: [age] is the age in the season that just ended, [played] the share of a
     * full season's games it played (0..1: experience for 예측력 and 승부 감각). Young players
     * grow toward their ceilings, peak players drift, veterans decline (faster each season).
     */
    fun grow(abl: Abilities, potential: Int, key: String, age: Int, played: Double, rng: Random): Abilities {
        val xs = abl.toList().toMutableList()
        val off = peakOffset(key)
        for (i in 0 until 4) {
            val cap = ceiling(potential, key, i)
            val x = xs[i]
            val peak = PEAK[i] + off
            val decline = DECLINE_FROM[i] + off
            var d = when {
                age < peak -> (cap - x).coerceAtLeast(0) * growthRate(age) + rng.nextGaussian() * 1.5
                age < decline -> 0.05 * (cap - x).coerceAtLeast(0) + rng.nextGaussian() * 1.0
                else -> -DECLINE[i] * (1 + 0.35 * (age - decline)) * (0.6 + 0.8 * rng.nextDouble())
            }
            if ((i == 1 || i == 2) && age < 34) d += (if (i == 1) 0.4 else 0.8) * played.coerceIn(0.0, 1.0)
            // Stochastic rounding: small steps add up over seasons.
            val whole = Math.floor(d)
            val step = whole.toInt() + if (rng.nextDouble() < d - whole) 1 else 0
            val hi = if (step > 0) maxOf(x, cap + 4) else 99
            xs[i] = (x + step).coerceIn(1, minOf(99, hi))
        }
        return Abilities.of(xs)
    }

    // ---- retirement ------------------------------------------------------------------------------

    /** Everyone retires at this age. */
    const val MAX_AGE = 40

    /** Chance of retiring after a season at [age] by age alone. */
    fun ageChance(age: Int): Double = when {
        age >= MAX_AGE -> 1.0
        age < 22 -> 0.0
        age <= 28 -> 0.01
        else -> doubleArrayOf(0.03, 0.04, 0.06, 0.08, 0.11, 0.15, 0.20, 0.27, 0.35, 0.47, 0.62)[age - 29]
    }

    /**
     * Chance of retiring after a season: by age, times 1.6 for the worst rated down to 0.4 for
     * the best ([percentile] 0..1 of the season's ratings, best = 1); a player of 23 or more in
     * the bottom 5% that is near its ceiling leaves at 25% (기량의 한계).
     */
    fun retireChance(age: Int, percentile: Double, nearCeiling: Boolean): Double {
        if (age >= MAX_AGE) return 1.0
        val byAge = ageChance(age) * (1.6 - 1.2 * percentile.coerceIn(0.0, 1.0))
        val washout = if (age >= 23 && percentile < 0.05 && nearCeiling) 0.25 else 0.0
        return maxOf(byAge, washout).coerceIn(0.0, 1.0)
    }

    // ---- new players ---------------------------------------------------------------------------

    /** Debut ages of the youth intake (17..27 by weight; a few late starters). */
    val ROOKIE_AGES = NamePool("17:10 18:22 19:24 20:18 21:10 22:6 23:3 24:2 25:2 26:2 27:1")

    /** Ages of a league's founding players (a whole career spread, fewer at the ends). */
    val FOUNDING_AGES = NamePool(
        "17:3 18:5 19:6 20:7 21:7 22:7 23:7 24:7 25:7 26:7 27:6 28:6 29:5 30:5 31:4 32:4 33:3 34:2 35:2 36:1 37:1",
    )

    /** How much of its ceiling an ability has reached at [age] on average (growth, plateau, decline). */
    fun shareAt(age: Int, i: Int, off: Int): Double {
        val peak = PEAK[i] + off
        val decline = DECLINE_FROM[i] + off
        return when {
            age < peak -> 0.62 + 0.38 * ((age - 17).coerceAtLeast(0).toDouble() / (peak - 17)).pow(0.8)
            age < decline -> 1.0
            else -> 1.0 - (age - decline + 1) * DECLINE[i] * (1 + 0.175 * (age - decline)) / 70
        }
    }

    /**
     * Abilities of a new player of [age] with [potential] (founding or rookie), [bias] added to
     * each (a nation's school), drawn from [rng]: each ability its ceiling times its share at that
     * age, 멘탈 a little higher (calm is the norm), 승부 감각 lower (it comes with games).
     */
    fun drawAbilities(rng: Random, key: String, age: Int, potential: Int, bias: IntArray = IntArray(4)): Abilities {
        val off = peakOffset(key)
        return Abilities.of(
            (0 until 4).map { i ->
                val shift = when (i) {
                    3 -> 6.0
                    2 -> -6.0
                    else -> 0.0
                }
                (ceiling(potential, key, i) * shareAt(age, i, off) + shift + bias[i] + rng.nextGaussian() * 4).roundToInt().coerceIn(1, 99)
            },
        )
    }

    /** Stage of a career by age, for the profile. */
    fun stage(age: Int): String = when {
        age <= 21 -> "유망주"
        age <= 25 -> "성장기"
        age <= 31 -> "전성기"
        age <= 35 -> "베테랑"
        else -> "노장"
    }

    /** "성장 가능성 높음/보통/낮음" for a player of 25 or younger; null when older. */
    fun outlook(id: Identity, age: Int): String? {
        if (age > 25) return null
        val gap = id.potential - id.abilities.overall
        return when {
            gap >= 15 -> "성장 가능성 높음"
            gap >= 7 -> "성장 가능성 보통"
            else -> "성장 가능성 낮음"
        }
    }

    internal fun fmt(x: Double) = String.format(Locale.ROOT, "%.2f", x)
}

/** Gender of a player saved before genders existed: from its Korean given name, else a hash of the key. */
object LegacyGender {
    private val MALE_END = "준우호진혁훈성재석환범기철규원현".toSet()
    private val FEMALE_END = "아은연희린하나미혜비별결솔".toSet()
    private val MALE_START = "준도시태승건동상성정진재경규형민".toSet()
    private val FEMALE_START = "서예수채다혜소나보아미가은유하".toSet()

    fun guess(name: String, key: String): Gender {
        // The Korean names before nations always had a one-syllable surname.
        val given = name.drop(1)
        val male = Nations.KR.male.names + Nations.KR.maleClassic.names
        val female = Nations.KR.female.names + Nations.KR.femaleClassic.names
        if (given in male && given !in female) return Gender.MALE
        if (given in female && given !in male) return Gender.FEMALE
        var score = 0
        given.lastOrNull()?.let { if (it in MALE_END) score += 2 else if (it in FEMALE_END) score -= 2 }
        given.firstOrNull()?.let { if (it in MALE_START) score += 1 else if (it in FEMALE_START) score -= 1 }
        return when {
            score > 0 -> Gender.MALE
            score < 0 -> Gender.FEMALE
            else -> if (Profiles.hash(key, 40, 2) == 0) Gender.MALE else Gender.FEMALE
        }
    }
}

/** Abilities' total change, for the profile ("판단력 ▲3"). */
fun Abilities.diff(before: Abilities?): List<Int> = if (before == null) List(4) { 0 } else toList().zip(before.toList()) { a, b -> a - b }

/** True when a player's abilities are within a few points of its ceilings (it has stopped growing). */
fun Identity.nearCeiling(key: String): Boolean = (0 until 4).all { i -> abs(abilities.toList()[i] - Profiles.ceiling(potential, key, i)) <= 5 }

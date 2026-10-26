package yacht.engine.league

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
    ;

    companion object {
        private val GENERATED_KEY = Regex("g\\d+")
        private val EVOLVED_KEY = Regex("e\\d+_\\d+")

        /** The origin read from a key: g001 generated, e3_1 evolved, me, the clone and custom AIs mine, the rest base. */
        fun of(key: String): Origin = when {
            key == Ladder.ME || key == CustomAis.CLONE || key.startsWith("custom-") -> MINE
            GENERATED_KEY.matches(key) -> GENERATED
            EVOLVED_KEY.matches(key) -> EVOLVED
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
)

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
) {
    /** Seasons it played. */
    val seasons: Int get() = retired - born + 1
}

/**
 * A retired AI with honours, kept with its genes (a `legend` line) so it can be rebuilt for the
 * legend match and the 레전드 보관소. [honours] is a summary such as "L0:2,CUP:1" ([Evolution.summary]).
 */
data class Legend(val record: RetiredRecord, val spec: CustomAiSpec, val honours: String) {
    val key: String get() = record.key
    val name: String get() = record.name

    /** As a player record, for [leagueAi]. */
    fun asPlayer(): PlayerRecord =
        PlayerRecord(record.key, record.name, record.hometown, record.origin, record.born, record.parentA, record.parentB, spec, record.bestRating)
}

/** Korean names: a surname (weighted by how common it is) and a two-syllable given name. */
internal object KoreanNames {
    private val SURNAMES: List<Pair<String, Int>> = listOf(
        "김" to 20, "이" to 14, "박" to 8, "최" to 5, "정" to 5, "강" to 2, "조" to 2, "윤" to 2, "장" to 2, "임" to 2,
    ) + "한오서신권황안송류전홍고문양손배백허남심노하곽성차주우구민진나지엄채원천방공현".map { it.toString() to 1 }
    private val TOTAL = SURNAMES.sumOf { it.second }
    private const val FIRST = "민서지현준도하예수시우주연유윤은채재승태정다성진동영혜소상경건선나보아세원규형미가"
    private const val SECOND = "준우호진민서윤아은원현연빈율희영수혁훈성재경린하람솔결온별비나지주석환범기철미혜"

    fun draw(rng: Random): String {
        var x = rng.nextInt(TOTAL)
        var surname = SURNAMES.last().first
        for ((s, w) in SURNAMES) {
            if (x < w) {
                surname = s
                break
            }
            x -= w
        }
        var a = FIRST[rng.nextInt(FIRST.length)]
        var b = SECOND[rng.nextInt(SECOND.length)]
        // No doubled syllable ("정정우", "민민").
        if (a.toString() == surname) a = FIRST[(FIRST.indexOf(a) + 1) % FIRST.length]
        if (b == a) b = SECOND[(SECOND.indexOf(b) + 1) % SECOND.length]
        return "$surname$a$b"
    }

    /** A name not in [taken]: draws until one is free (a number is added in the unlikely case none is). */
    fun unique(rng: Random, taken: Set<String>): String {
        repeat(200) {
            val n = draw(rng)
            if (n !in taken) return n
        }
        val base = draw(rng)
        var i = 2
        while ("$base$i" in taken) i++
        return "$base$i"
    }
}

/**
 * The genes of generated players (docs/LEAGUE_SPEC.md section 1). Player i's genes come from its
 * own random stream only, so the first N of N+10 players are the same players.
 *
 * Base genes: EV-shaped 55%, RISK 20% (θ from [LeagueRoster.THETAS]), BELIEF 10% (max
 * expectation under one of the six misremembered rules) and SITUATION 15%. CustomAiSpec has no
 * plan that reads the score, so the SITUATION overlay is a [Temper] (the 상황 판단 family's
 * tilting, tiring, warming-up and moody players) on an EV (70%) or RISK base, always with
 * mistakes for it to scale. Mistakes otherwise: none 30%, slips 35% (0.3-30%, log-uniform), feel
 * 35% (τ 0.1-3 points). Tastes (EV base only) with probability 0.3 each: Yacht, straight,
 * upper points, zeros, saving Choice, short or long sight. Never the win search: all are light.
 */
object PlayerGenerator {
    const val GEN_SEED = 0x67656E5EED01L
    private const val NAME_SALT = 0x4E414D45L

    const val EV_SHARE = 0.55
    const val RISK_SHARE = 0.20
    const val BELIEF_SHARE = 0.10
    const val TASTE = 0.3
    const val SLIP_MIN = 0.003
    const val SLIP_MAX = 0.30
    const val FEEL_MIN = 0.1
    const val FEEL_MAX = 3.0

    /** θ of a non-risk spec (unused by its plan; the [CustomAiSpec] default). */
    const val PLAIN_THETA = 0.03

    val BELIEF_KEYS: List<String> = CustomAiSpec.BELIEFS.keys.toList()
    val TEMPERS: List<Temper> = Temper.entries.filter { it != Temper.NONE }

    fun key(i: Int): String = "g" + i.toString().padStart(3, '0')

    /** The index of a generated key ("g007" → 7), null for any other key. */
    fun index(key: String): Int? = key.removePrefix("g").takeIf { key.startsWith("g") }?.toIntOrNull()?.takeIf { it > 0 && key(it) == key }

    /** Genes of generated player [i] (id = its key, no name). */
    fun spec(i: Int): CustomAiSpec = draw(Random(DiceStream.mix(GEN_SEED, i.toLong())), key(i))

    /** The stream naming player [i], apart from its genes: a name clash never changes them. */
    internal fun nameRng(i: Int) = Random(DiceStream.mix(GEN_SEED, NAME_SALT, i.toLong()))

    /** A random spec by the shares above. */
    fun draw(rng: Random, id: String = "", name: String = ""): CustomAiSpec {
        val u = rng.nextDouble()
        val situation = u >= EV_SHARE + RISK_SHARE + BELIEF_SHARE
        val believer = !situation && u >= EV_SHARE + RISK_SHARE
        val base = when {
            u < EV_SHARE -> CustomBase.EV
            u < EV_SHARE + RISK_SHARE -> CustomBase.RISK
            believer -> CustomBase.EV
            else -> if (rng.nextDouble() < 0.3) CustomBase.RISK else CustomBase.EV
        }
        val theta = if (base == CustomBase.RISK) LeagueRoster.THETAS[rng.nextInt(LeagueRoster.THETAS.size)] else PLAIN_THETA
        val belief = if (believer) BELIEF_KEYS[rng.nextInt(BELIEF_KEYS.size)] else null
        val temper = if (situation) TEMPERS[rng.nextInt(TEMPERS.size)] else Temper.NONE
        val m = rng.nextDouble()
        val mistake = when {
            !situation && m < 0.3 -> CustomMistake.NONE
            m < 0.65 -> CustomMistake.SLIP
            else -> CustomMistake.FEEL
        }
        val amount = when (mistake) {
            CustomMistake.NONE -> 0.0
            CustomMistake.SLIP -> round(logUniform(rng, SLIP_MIN, SLIP_MAX), 3)
            CustomMistake.FEEL -> round(logUniform(rng, FEEL_MIN, FEEL_MAX), 2)
        }
        fun taste() = base == CustomBase.EV && rng.nextDouble() < TASTE
        val yacht = if (taste()) round(if (rng.nextDouble() < 0.75) uniform(rng, 5.0, 60.0) else -uniform(rng, 5.0, 25.0), 0) else 0.0
        val straight = if (taste()) round(uniform(rng, 3.0, 15.0), 0) else 0.0
        val bonus = if (taste()) round(uniform(rng, 0.2, 1.5), 1) else 0.0
        val zero = if (taste()) round(uniform(rng, 3.0, 30.0), 0) else 0.0
        val saveChoice = taste()
        val future = if (taste()) twentieths(if (rng.nextBoolean()) uniform(rng, 0.3, 0.9) else uniform(rng, 1.1, 1.5)) else 1.0
        return CustomAiSpec(id, name, base, theta, mistake, amount, yacht, straight, bonus, zero, saveChoice, future, temper, belief)
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
fun familyOf(spec: CustomAiSpec): Family = when {
    spec.base == CustomBase.EV && spec.belief != null -> Family.BELIEF
    spec.temper != Temper.NONE -> Family.SITUATION
    spec.base == CustomBase.RISK -> Family.RISK
    spec.base == CustomBase.WIN -> Family.WIN
    else -> Family.EV
}

/** The league AI of a generated or evolved player (light: never the win search). */
fun leagueAi(record: PlayerRecord, tables: Tables, extra: LeagueTables): LeagueAi = CustomAis.build(
    record.spec, tables, extra, key = record.key, name = record.name, family = familyOf(record.spec),
    desc = "${record.origin.label} 선수 · ${record.spec.describe()}",
)

/** A legend rebuilt from its archived genes (the legend match, the lab). */
fun leagueAi(legend: Legend, tables: Tables, extra: LeagueTables): LeagueAi = leagueAi(legend.asPlayer(), tables, extra)

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

    /**
     * Adds the generated players g001..g<[n]> that are missing, debuting in [season]; a key that
     * ever retired is never added again. Returns the new ones. Lowering [n] removes nobody: the
     * extra players stay until evolution retires them, and the population only shrinks (or,
     * once [n] is raised again, grows back past the g-keys already used) through
     * [Evolution.run]'s `population` (pass the same [n]).
     * At most [max] are added (a book refilled by evolution's children only tops up what it lacks).
     */
    fun ensureGenerated(n: Int, season: Int = 1, max: Int = Int.MAX_VALUE): List<PlayerRecord> {
        val added = ArrayList<PlayerRecord>()
        val taken = names().toMutableSet()
        for (i in 1..n) {
            if (added.size >= max) break
            val key = PlayerGenerator.key(i)
            if (key in byKey || i in gone || retiredRecord(key) != null) continue
            val name = KoreanNames.unique(PlayerGenerator.nameRng(i), taken)
            taken += name
            val r = PlayerRecord(key, name, Hometowns.of(key), Origin.GENERATED, season, null, null, PlayerGenerator.spec(i).copy(name = name))
            byKey[key] = r
            added += r
        }
        return added
    }

    /** Adds a new player (an evolved child); a key already known is refused. */
    fun add(record: PlayerRecord): Boolean {
        if (record.key in byKey || retiredRecord(record.key) != null) return false
        byKey[record.key] = record.copy(spec = record.spec.copy(id = record.key, name = record.name))
        return true
    }

    /** Remembers [rating] as [key]'s best season-end rating if it is higher. */
    fun notePeak(key: String, rating: Double) {
        val r = byKey[key] ?: return
        if (rating.isNaN()) return
        if (r.peak.isNaN() || rating > r.peak) byKey[key] = r.copy(peak = rating)
    }

    /**
     * Retires [key] at the end of [season]: its retired record (best rating [bestRating] or its
     * peak, [titles]) and, with an [honours] summary, a legend that keeps its genes. Null when
     * [key] is not alive here.
     */
    fun retire(key: String, season: Int, titles: Int = 0, bestRating: Double = Double.NaN, honours: String? = null): RetiredRecord? {
        val p = byKey.remove(key) ?: return null
        PlayerGenerator.index(key)?.let { gone += it }
        val best = listOf(bestRating, p.peak).filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
        val r = RetiredRecord(key, p.name, p.born, season, p.parentA, p.parentB, best, titles, p.hometown, p.origin)
        retiredList += r
        while (retiredList.size > MAX_RETIRED) retiredList.removeAt(0)
        if (honours != null) {
            legendList.removeAll { it.key == key }
            legendList += Legend(r, p.spec, honours.ifBlank { "-" })
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
        for (p in byKey.values) {
            append("ai ${p.key} ${esc(p.name)} ${esc(p.hometown)} ${p.origin.name} ${p.born} ${p.parentA ?: "-"} ${p.parentB ?: "-"} ")
            append("${p.spec.genes().joinToString(" ")} ${num(p.peak)}\n")
        }
        for (r in retiredList) {
            append("retired ${r.key} ${esc(r.name)} ${r.born} ${r.retired} ${r.parentA ?: "-"} ${r.parentB ?: "-"} ${num(r.bestRating)} ${r.titles} ")
            append("${esc(r.hometown)} ${r.origin.name}\n")
        }
        for (l in legendList) {
            val r = l.record
            append("legend ${r.key} ${esc(r.name)} ${r.born} ${r.retired} ${l.spec.genes().joinToString(" ")} ${esc(l.honours)} ")
            append("${r.parentA ?: "-"} ${r.parentB ?: "-"} ${num(r.bestRating)} ${r.titles} ${esc(r.hometown)} ${r.origin.name}\n")
        }
    }

    companion object {
        const val HEADER = "roster 1"
        const val MAX_RETIRED = 500
        const val MAX_LEGENDS = 200

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
                runCatching { b.decodeLine(line.trim().split(' ')) }
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

    /** One saved line; every field is read before anything is added (a cut line changes nothing). */
    private fun decodeLine(f: List<String>) {
        when (f[0]) {
            "evolved" -> evolvedSeason = f[1].toInt()
            "gone" -> if (f[1] != "-") gone += f[1].split(',').map { it.toInt() }
            "ai" -> {
                // key name town origin born parentA parentB, the genes, the peak.
                require(f.size >= 8 + G + 1)
                val key = f[1]
                val name = unesc(f[2])
                val spec = CustomAiSpec.ofGenes(key, name, f.subList(8, 8 + G))
                val r = PlayerRecord(key, name, unesc(f[3]), Origin.valueOf(f[4]), f[5].toInt(), keyOf(f[6]), keyOf(f[7]), spec, numOf(f[8 + G]))
                byKey[key] = r
            }
            "retired" -> {
                require(f.size >= 11)
                retiredList += RetiredRecord(
                    f[1], unesc(f[2]), f[3].toInt(), f[4].toInt(), keyOf(f[5]), keyOf(f[6]), numOf(f[7]), f[8].toInt(), unesc(f[9]), Origin.valueOf(f[10]),
                )
                while (retiredList.size > MAX_RETIRED) retiredList.removeAt(0)
            }
            "legend" -> {
                // key name born retired, the genes, honours, parentA parentB best titles town origin.
                val at = 5 + G
                require(f.size >= at + 7)
                val key = f[1]
                val name = unesc(f[2])
                val spec = CustomAiSpec.ofGenes(key, name, f.subList(5, at))
                val r = RetiredRecord(
                    key, name, f[3].toInt(), f[4].toInt(), keyOf(f[at + 1]), keyOf(f[at + 2]), numOf(f[at + 3]), f[at + 4].toInt(),
                    unesc(f[at + 5]), Origin.valueOf(f[at + 6]),
                )
                legendList.removeAll { it.key == key }
                legendList += Legend(r, spec, unesc(f[at]).ifEmpty { "-" })
                trimLegends()
            }
        }
    }
}

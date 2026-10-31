package yacht.engine.league

import java.util.TreeMap

/**
 * One part of a file's codec (design ENG 1.6): the line kinds it owns ([prefixes], each line's
 * first field), how it writes them from state [S] and reads one back. Every feature file owns the
 * parts of its lines (club world: [ClubWorld.PARTS]; world.txt: [WorldBook.PARTS]); the stage that
 * owns a file later may add tokens at the end of its own lines (read with defaults when missing),
 * and no stage renames a prefix. Older decoders skip the lines: their first fields are unknown there.
 */
interface LinePart<S> {
    val prefixes: List<String>

    /** Appends this part's lines (each ending in '\n'), in a deterministic order. */
    fun encode(state: S, out: StringBuilder)

    /** Reads one line, split on spaces, whose first field is one of [prefixes]; false (or a throw) skips it. */
    fun decode(state: S, f: List<String>): Boolean
}

/** Runs [LinePart]s over a file. */
object LineParts {
    fun <S> encode(parts: List<LinePart<S>>, state: S, out: StringBuilder) {
        for (p in parts) p.encode(state, out)
    }

    /** Reads every line one of [parts] owns into [state]; a line that cannot be read is skipped. */
    fun <S> decode(parts: List<LinePart<S>>, state: S, lines: List<String>) {
        val byPrefix = HashMap<String, LinePart<S>>()
        for (p in parts) for (x in p.prefixes) byPrefix[x] = p
        for (l in lines) {
            val f = l.trim().split(' ')
            val part = byPrefix[f[0]] ?: continue
            runCatching { part.decode(state, f) }
        }
    }

    /** A part of one line kind, from two functions. */
    fun <S> of(prefix: String, write: (S, StringBuilder) -> Unit, read: (S, List<String>) -> Boolean): LinePart<S> = object : LinePart<S> {
        override val prefixes = listOf(prefix)
        override fun encode(state: S, out: StringBuilder) = write(state, out)
        override fun decode(state: S, f: List<String>): Boolean = read(state, f)
    }

    /** Items of a list token ("-" for none). */
    internal fun items(t: String, sep: Char = ','): List<String> = if (t == "-" || t.isEmpty()) emptyList() else t.split(sep).filter { it.isNotEmpty() }

    /** A list token: the items joined, "-" for none. */
    internal fun <T> list(xs: Collection<T>, sep: Char = ',', f: (T) -> String): String = if (xs.isEmpty()) "-" else xs.joinToString(sep.toString(), transform = f)

    /** An optional text ("-" for null), escaped. */
    internal fun opt(s: String?): String = if (s.isNullOrEmpty()) "-" else Field.esc(s)

    internal fun bit(b: Boolean): String = if (b) "1" else "0"
}

/** Owner types (design ENG 1b.5); [code] is saved. 국부 펀드 comes only by a takeover arc. */
enum class OwnerType(val code: Char, val label: String, val desc: String) {
    TYCOON('T', "재벌 구단주", "돈을 아끼지 않지만 참을성이 적어요."),
    FANS('F', "팬 소유", "팬들이 주인이라 티켓이 싸고, 감독을 오래 믿어 줘요."),
    LOCAL('L', "지역 기업", "무리하지 않고 꾸준히 투자해요."),
    FUND('P', "투자 펀드", "수익을 중시해요. 몸값이 오른 선수는 팔기도 해요."),
    STATE('S', "국부 펀드", "막대한 돈을 쏟아붓고 빠른 성과를 원해요."),
    ;

    companion object {
        fun of(code: Char): OwnerType? = entries.firstOrNull { it.code == code }
    }
}

/** A club's owner: [ambition] and [patience] 1..5, owner [since] the season, [paid] the club value at the takeover. */
data class Owner(val type: OwnerType, val name: String, val ambition: Int, val patience: Int, val since: Int, val paid: Int) {
    companion object {
        /** A club whose `co` line is missing: a modest local owner. */
        val NONE = Owner(OwnerType.LOCAL, "", 3, 3, 0, 0)
    }
}

/** The main sponsor (메인 스폰서): [perSeason] money a season of seasons [from]..[until]; [record] a world record deal. [sector] indexes the sponsor sectors (P3). */
data class Sponsor(val name: String, val sector: Int, val perSeason: Int, val from: Int, val until: Int, val record: Boolean)

/** A stadium project: [kind] 'E' an expansion, 'N' a new stadium, to [target] seats, opening at [opens], costing [cost] of which [paid] is paid. */
data class StadiumProject(val kind: Char, val target: Int, val opens: Int, val cost: Int, val paid: Int)

/** The home ground: [capacity] seats, a [project] under way, the naming-rights sponsor's [naming] name. */
data class Stadium(val name: String, val capacity: Int, val project: StadiumProject?, val naming: String?) {
    companion object {
        val NONE = Stadium("", 0, null, null)
    }
}

/** A club's accounts of [season] (design ENG 1.3), money units; [balance] after the season. */
data class Accounts(
    val season: Int,
    val tv: Int, val prize: Int, val intl: Int, val sponsor: Int, val matchday: Int, val owner: Int, val solidarity: Int, val sales: Int,
    val wages: Int, val coach: Int, val upkeep: Int, val building: Int, val buys: Int,
    val balance: Int,
) {
    /** Owner money counts as income when paid in and as an expense when taken out (a fund's dividend). */
    val income: Int get() = tv + prize + intl + sponsor + matchday + maxOf(0, owner) + solidarity + sales
    val expenses: Int get() = wages + coach + upkeep + building + buys + maxOf(0, -owner)
    val net: Int get() = income - expenses
}

/** One season of a club's money history (`ah`): balance and income after it, fans, its club-table place. */
data class ClubYear(val season: Int, val balance: Int, val income: Int, val fans: Int, val place: Int)

/**
 * A member's contract at its club (design ENG 1b.8): the last season it covers ([until]), its
 * wage a season, a release clause (0 = none), the shirt [number], at the club [since] the season,
 * official [apps] there, the club it is on loan from, the academy it came out of and that season.
 */
data class Terms(
    val until: Int, val wage: Int, val clause: Int, val number: Int, val since: Int, val apps: Int,
    val loanFrom: String?, val academy: String?, val gradSeason: Int,
)

/** A record fee: [key] moved [from] → [to] for [fee] after [season] (the `cw` line's world record). */
data class FeeRecord(val key: String, val from: String?, val to: String?, val fee: Int, val season: Int)

/** The all-time season series of a rival pair (`dr`), [a] < [b] by [ClubBook.ID_ORDER]; [since] the first season counted, [last] the last met. */
data class DerbyRecord(val a: String, val b: String, val winsA: Int, val draws: Int, val winsB: Int, val games: Int, val since: Int, val last: Int) {
    /** The record from club [club]'s side: its wins, draws, losses. */
    fun of(club: String): Triple<Int, Int, Int> = if (club == b) Triple(winsB, draws, winsA) else Triple(winsA, draws, winsB)
}

/**
 * The world's club rankings (design ENG 1b.9), best first: by club value, by results (the 5-season
 * club coefficient), the richest (last season's income), the fans, and by financial health with
 * the clubs per grade A..E ([grades], 5 counts).
 */
data class ClubRankings(
    val value: List<Pair<String, Int>>,
    val results: List<Pair<String, Double>>,
    val rich: List<Pair<String, Int>>,
    val fans: List<Pair<String, Int>>,
    val health: List<Pair<String, Char>>,
    val grades: List<Int>,
)

/**
 * A club's life in the club world (design ENG 1b), as a snapshot ([ClubBook.life]): identity, money,
 * owner, sponsor, stadium, facilities (levels 1..5), fans and their mood (0..100), the coach's
 * pressure, coach and captain, seasons in trouble and in administration, the club-table points
 * deduction (%), its last accounts and money history, rivals, legends, retired numbers and records.
 * Its financial health and club value are P3's (ENG 1.3).
 */
data class ClubLife(
    val id: String, val foundedYear: Int, val nickname: String, val colours: String,
    val balance: Int, val owner: Owner, val sponsor: Sponsor?, val stadium: Stadium, val training: Int, val academy: Int,
    val fans: Int, val mood: Int, val pressure: Double, val coach: String?, val captain: String?,
    val trouble: Int, val admin: Int, val deduction: Int,
    val last: Accounts?, val history: List<ClubYear>, val rivals: List<Rival>,
    val legends: List<ClubLegend>, val retired: List<RetiredNumber>, val records: List<ClubRecord>,
)

/** A club's own state in the club world (the `cl` line), as the steps change it. */
internal class ClubState(val id: String) {
    var foundedYear = 0
    var nickname = ""
    var balance = 0
    var fans = 0
    var mood = 50
    var pressure = 0.0
    var coach: String? = null
    var captain: String? = null
    var trouble = 0
    var admin = 0
    var deduction = 0
    var training = 1
    var academy = 1
}

/**
 * The club world of a national club book (design ENG 1b), saved in clubs.txt after today's lines
 * from a `cw 1` marker on: present only once the book is migrated (no `cw` line = not yet, and the
 * app behaves as before). Each feature keeps its part: the world's own (`cw cl sq`: wage indexes,
 * records, guards, each club's state and every member's terms), [economy] (ClubEconomy.kt),
 * [history] (ClubHistory.kt), [coaches] (Coaches.kt), [market] (Market.kt) and [winter]
 * (ClubCheckpoint.kt). Changed only by the club off-season and the winter checkpoint, through
 * the book that holds it ([book]).
 */
class ClubWorld internal constructor(book: ClubBook) {
    internal var book: ClubBook = book

    /** League id → wage index ω × 100 (design ENG 1.1). */
    internal val wageIndex = TreeMap<String, Int>()

    /** The world record fee. */
    var worldRecord: FeeRecord? = null
        internal set

    /** The last season whose winter checkpoint ran (its guard). */
    var checkpoint = 0
        internal set

    /** The last week the market's tick closed ([marketSeason]'s; the guard of ENG 3.1). */
    var marketSeason = 0
        internal set
    var marketWeek = 0
        internal set

    /** The number of the next coach id ("k<n>", never reused) and of the next story. */
    internal var nextCoach = 1
    internal var nextStory = 1

    /** The last season a club was dissolved for money (at most one per 5 seasons, ENG 1.4); 0 for none. */
    var dissolvedBy = 0
        internal set

    /** The median ladder rating of the alive generated players at the last close (values are anchored at it, [Valuation]). */
    var anchor = Valuation.ANCHOR
        internal set

    internal val clubs = TreeMap<String, ClubState>(ClubBook.ID_ORDER)
    internal val terms = HashMap<String, Terms>()
    internal val economy = ClubEconomyBook()
    internal val history = ClubHistoryBook()
    internal val coaches = CoachBook()
    internal val market = MarketBook()
    internal val winter = WinterBook()

    /** League [league]'s wage index ω (1.0 when it has none). */
    fun omega(league: String): Double = (wageIndex[league] ?: 100) / 100.0

    /** The state of club [id], made when it has none. */
    internal fun state(id: String): ClubState = clubs.getOrPut(id) { ClubState(id) }

    companion object {
        const val VERSION = 1

        private val CW = LineParts.of<ClubWorld>("cw", { w, out ->
            out.append("cw $VERSION ")
            out.append(LineParts.list(w.wageIndex.entries) { "${Field.esc(it.key)}:${it.value}" }).append(' ')
            out.append(w.worldRecord?.let { r -> "${Field.esc(r.key)}:${LineParts.opt(r.from)}:${LineParts.opt(r.to)}:${r.fee}:${r.season}" } ?: "-").append(' ')
            out.append("${w.checkpoint} ${w.marketSeason}:${w.marketWeek} ${w.nextCoach} ${w.dissolvedBy} ${Field.num(w.anchor, 1)} ${w.nextStory}\n")
        }) { w, f ->
            for (e in LineParts.items(f[2])) w.wageIndex[Field.unesc(e.substringBefore(':'))] = e.substringAfter(':').toInt()
            w.worldRecord = f[3].takeIf { it != "-" }?.split(':')?.let { p -> FeeRecord(Field.unesc(p[0]), Field.opt(p[1]), Field.opt(p[2]), p[3].toInt(), p[4].toInt()) }
            w.checkpoint = f[4].toInt()
            val market = f[5].split(':')
            if (market.size == 2) {
                w.marketSeason = market[0].toInt()
                w.marketWeek = market[1].toInt()
            } else {
                w.marketWeek = market[0].toInt()
            }
            w.nextCoach = f[6].toInt().coerceAtLeast(1)
            w.dissolvedBy = f[7].toInt()
            w.anchor = f[8].toDouble()
            w.nextStory = f.getOrNull(9)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            true
        }

        private val CL = LineParts.of<ClubWorld>("cl", { w, out ->
            for (c in w.clubs.values) {
                out.append("cl ${c.id} ${c.foundedYear} ${Field.esc(c.nickname)} ${c.balance} ${c.fans} ${c.mood} ${Math.round(c.pressure * 10)} ")
                out.append("${LineParts.opt(c.coach)} ${LineParts.opt(c.captain)} ${c.trouble} ${c.admin} ${c.deduction} ${c.training} ${c.academy}\n")
            }
        }) { w, f ->
            require(f.size >= 15)
            w.state(f[1]).apply {
                foundedYear = f[2].toInt()
                nickname = Field.unesc(f[3])
                balance = f[4].toInt()
                fans = f[5].toInt()
                mood = f[6].toInt()
                pressure = f[7].toInt() / 10.0
                coach = Field.opt(f[8])
                captain = Field.opt(f[9])
                trouble = f[10].toInt()
                admin = f[11].toInt()
                deduction = f[12].toInt()
                training = f[13].toInt()
                academy = f[14].toInt()
            }
            true
        }

        /** Terms in squad order, one line per club (members without terms left out). */
        private val SQ = LineParts.of<ClubWorld>("sq", { w, out ->
            for (c in w.book.clubs) {
                val termed = c.squad.mapNotNull { k -> w.terms[k]?.let { k to it } }
                if (termed.isEmpty()) continue
                out.append("sq ${c.id} ")
                out.append(termed.joinToString(",") { (k, t) ->
                    "${Field.esc(k)}:${t.until}:${t.wage}:${t.clause}:${t.number}:${t.since}:${t.apps}:${LineParts.opt(t.loanFrom)}:${LineParts.opt(t.academy)}:${t.gradSeason}"
                }).append('\n')
            }
        }) { w, f ->
            for (e in LineParts.items(f[2])) {
                val p = e.split(':')
                w.terms[Field.unesc(p[0])] = Terms(p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6].toInt(), Field.opt(p[7]), Field.opt(p[8]), p[9].toInt())
            }
            true
        }

        /** Every line kind of the club world, in the order they are written. */
        val PARTS: List<LinePart<ClubWorld>> =
            listOf(CW, CL) + ClubEconomyBook.PARTS + listOf(SQ) + ClubHistoryBook.PARTS + CoachBook.PARTS + MarketBook.PARTS + WinterBook.PARTS

        /** Every prefix the club world writes. */
        val PREFIXES: Set<String> = PARTS.flatMap { it.prefixes }.toSet()
    }
}

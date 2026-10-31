package yacht.engine.league

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Sections of the 기록실, in order. */
enum class RecordGroup(val label: String) {
    RATING("레이팅"),
    GAME("한 판·한 경기"),
    SEASON("한 시즌"),
    CAREER("통산"),
    TITLE("우승·수상"),
}

/**
 * The league's all-time records (기록실). Game and match records come from official games
 * ([StatsBook.countsForSeason]), the winning run and the career counts from every rated game as
 * on the ladder, the peak rating and upsets from official matches (ratings before the match),
 * the season records, titles and awards from finished seasons. A record is broken only by a
 * better value: a higher one, or a lower one for the kinds marked [lowerIsBetter] (the lowest
 * winning score, the youngest champion). [running]: a count one holder keeps raising game by
 * game (one break a season, not one a game). [retired]: no longer offered (a record that could
 * never be broken again, or one on an old scale); its holder is still kept and read back, for
 * the 기록실's "예전 기록". The youngest and oldest champion are of any league's title
 * ([HonourKind.isTopTitle]: the old single league's or a national league's).
 */
enum class RecordKind(
    val label: String,
    val group: RecordGroup,
    val lowerIsBetter: Boolean = false,
    val running: Boolean = false,
    val retired: Boolean = false,
) {
    BEST_GAME("한 판 최고 점수", RecordGroup.GAME),
    MARGIN("최다 점수 차 승리", RecordGroup.GAME),

    /** The lowest P(win) a winner had, in percent: 0 within a league's first season, so never broken again ([COMEBACK_POINTS]). */
    COMEBACK("최대 역전승 (승리 확률)", RecordGroup.GAME, lowerIsBetter = true, retired = true),

    /** The most points a winner trailed by after a full turn ([GameFacts.deficit]). */
    COMEBACK_POINTS("최대 점수 차 역전승", RecordGroup.GAME),

    /** One AI's Yachts in one match (경기): 2 of 2 games within a season, so never broken again ([YACHT_RUN]). */
    YACHTS_MATCH("한 경기 최다 요트", RecordGroup.GAME, retired = true),
    STREAK("최다 연승", RecordGroup.CAREER, running = true),

    /** Official games in a row with a Yacht. */
    YACHT_RUN("연속 요트 경기", RecordGroup.CAREER, running = true),
    SEASON_POINTS("한 시즌 최다 승점", RecordGroup.SEASON),

    /** League points per league match, of a player that played [Awards.MVP_SHARE] of its own fixtures. */
    SEASON_PPM("한 시즌 경기당 최고 승점", RecordGroup.SEASON),

    /** 2-0 sweeps that earned the bonus point (a season with 완승 보너스). */
    SEASON_SWEEPS("한 시즌 최다 완승", RecordGroup.SEASON),

    /** The best season average of the first match rating (평점 1.0), kept as it was. */
    SEASON_RATING("한 시즌 최고 평균 평점 (구 평점)", RecordGroup.SEASON, retired = true),

    /** The best season average match rating (평점 2.0, [GameFacts.rating]). */
    SEASON_RATING2("한 시즌 최고 평균 평점", RecordGroup.SEASON),
    TITLES("최다 우승", RecordGroup.TITLE),
    PEAK_RATING("역대 최고 레이팅", RecordGroup.RATING, running = true),
    RATING_GAIN("한 시즌 최대 레이팅 상승", RecordGroup.RATING),
    UPSET("최대 이변", RecordGroup.RATING),
    LOW_WIN("가장 낮은 점수로 이긴 판", RecordGroup.GAME, lowerIsBetter = true),
    BEST_PAIR("한 판 두 선수 합계 최고", RecordGroup.GAME),
    SEASON_SCORE("한 시즌 최고 평균 점수", RecordGroup.SEASON),
    SEASON_WINRATE("한 시즌 최고 승률", RecordGroup.SEASON),
    SEASON_YACHTS("한 시즌 최다 요트", RecordGroup.SEASON),
    CAREER_GAMES("통산 최다 판", RecordGroup.CAREER, running = true),
    CAREER_WINS("통산 최다 승리", RecordGroup.CAREER, running = true),
    CAREER_YACHTS("통산 최다 요트", RecordGroup.CAREER, running = true),
    CAREER_300("통산 최다 300점 경기", RecordGroup.CAREER, running = true),
    MVPS("최다 MVP", RecordGroup.TITLE),
    POTW("최다 이 주의 선수", RecordGroup.TITLE),
    YOUNGEST_CHAMPION("최연소 1부 우승", RecordGroup.TITLE, lowerIsBetter = true),
    OLDEST_CHAMPION("최고령 1부 우승", RecordGroup.TITLE),
    ;

    /** The value as events carry it: a whole number, a match rating or points per match with two decimals, an average or a rate with one. */
    fun plain(v: Double): String = when (this) {
        SEASON_RATING, SEASON_RATING2, SEASON_PPM -> Field.num(v, 2)
        SEASON_SCORE, SEASON_WINRATE -> Field.num(v, 1)
        else -> v.roundToInt().toString()
    }

    /** The value for the screen, in Korean. */
    fun text(v: Double): String = when (this) {
        BEST_GAME -> "${plain(v)}점"
        MARGIN -> "${plain(v)}점 차"
        COMEBACK -> if (v < 1) "승리 확률 1% 미만에서 역전" else "승리 확률 ${plain(v)}%에서 역전"
        COMEBACK_POINTS -> "${plain(v)}점 뒤지다 역전"
        YACHTS_MATCH -> "요트 ${plain(v)}번"
        STREAK -> "${plain(v)}연승"
        YACHT_RUN -> "${plain(v)}경기 연속 요트"
        SEASON_POINTS -> "승점 ${plain(v)}"
        SEASON_PPM -> "경기당 승점 ${plain(v)}"
        SEASON_SWEEPS -> "2:0 완승 ${plain(v)}번"
        SEASON_RATING, SEASON_RATING2 -> "평점 ${plain(v)}"
        TITLES -> "우승 ${plain(v)}회"
        PEAK_RATING -> "레이팅 ${plain(v)}"
        RATING_GAIN -> "레이팅 +${plain(v)}"
        UPSET -> "레이팅 ${plain(v)} 높은 상대를 꺾음"
        LOW_WIN -> "${plain(v)}점으로 승리"
        BEST_PAIR -> "두 선수 합계 ${plain(v)}점"
        SEASON_SCORE -> "평균 ${plain(v)}점"
        SEASON_WINRATE -> "경기 승률 ${plain(v)}%"
        SEASON_YACHTS -> "요트 ${plain(v)}번"
        CAREER_GAMES -> "%,d판".format(v.roundToInt())
        CAREER_WINS -> "%,d승".format(v.roundToInt())
        CAREER_YACHTS -> "요트 %,d번".format(v.roundToInt())
        CAREER_300 -> "300점 이상 ${plain(v)}판"
        MVPS -> "MVP ${plain(v)}회"
        POTW -> "이 주의 선수 ${plain(v)}회"
        YOUNGEST_CHAMPION, OLDEST_CHAMPION -> "${plain(v)}세에 우승"
    }

    /**
     * The value with its unit as a sentence names it ("245점", "87점 차", "17연승", "레이팅 차 514",
     * "평점 7.85"): what a record article writes for "기록이 …으로 바뀌었어요" and "종전 기록은 …이었어요".
     */
    fun withUnit(v: Double): String = when (this) {
        BEST_GAME, LOW_WIN, BEST_PAIR -> "${grouped(v)}점"
        MARGIN, COMEBACK_POINTS -> "${grouped(v)}점 차"
        COMEBACK -> "승리 확률 ${plain(v)}%"
        YACHTS_MATCH, SEASON_YACHTS -> "요트 ${plain(v)}개"
        STREAK -> "${plain(v)}연승"
        YACHT_RUN -> "${plain(v)}경기 연속"
        SEASON_POINTS -> "승점 ${plain(v)}"
        SEASON_PPM -> "경기당 승점 ${plain(v)}"
        SEASON_SWEEPS -> "완승 ${plain(v)}번"
        SEASON_RATING, SEASON_RATING2 -> "평점 ${plain(v)}"
        TITLES -> "우승 ${plain(v)}회"
        PEAK_RATING -> "레이팅 ${grouped(v)}"
        RATING_GAIN -> "레이팅 +${grouped(v)}"
        UPSET -> "레이팅 차 ${grouped(v)}"
        SEASON_SCORE -> "평균 ${plain(v)}점"
        SEASON_WINRATE -> "승률 ${plain(v)}%"
        CAREER_GAMES -> "${grouped(v)}판"
        CAREER_WINS -> "${grouped(v)}승"
        CAREER_YACHTS -> "요트 ${grouped(v)}개"
        CAREER_300 -> "300점 이상 ${grouped(v)}판"
        MVPS -> "MVP ${plain(v)}회"
        POTW -> "이 주의 선수 ${plain(v)}회"
        YOUNGEST_CHAMPION, OLDEST_CHAMPION -> "${plain(v)}세"
    }

    private fun grouped(v: Double): String = String.format(Locale.ROOT, "%,d", v.roundToInt())

    /** A count a holder raises itself ([running], titles, MVPs, 이 주의 선수): its own earlier values are no "이전 기록". */
    val cumulative: Boolean get() = running || this == TITLES || this == MVPS || this == POTW

    companion object {
        /** The kinds still offered (not [retired]), in order: what the 기록실 lists. */
        val current: List<RecordKind> = entries.filter { !it.retired }
    }
}

/** A record and who holds it: [other] is the opponent when there was one, [comp] where it was set. */
data class LeagueRecord(
    val kind: RecordKind,
    val holder: String,
    val value: Double,
    val season: Int,
    val other: String? = null,
    val comp: Comp? = null,
) {
    /** "kind holder value season other comp". */
    fun encode(): String = "${kind.name} ${Field.esc(holder)} ${Field.num(value, 2)} $season ${Field.esc(other)} ${comp?.code ?: '-'}"

    companion object {
        fun decode(f: List<String>, from: Int): LeagueRecord? = runCatching {
            LeagueRecord(
                RecordKind.valueOf(f[from]), Field.unesc(f[from + 1]), f[from + 2].toDouble(), f[from + 3].toInt(),
                Field.opt(f[from + 4]), f[from + 5].singleOrNull()?.let { Comp.of(it) },
            )
        }.getOrNull()
    }
}

/** A record broken: the new one and the one it replaced (null when it was the first). */
data class RecordBreak(val record: LeagueRecord, val previous: LeagueRecord?) {
    fun encode(): String = record.encode() + " " + (previous?.encode() ?: "-")

    companion object {
        fun decode(f: List<String>, from: Int): RecordBreak? {
            val r = LeagueRecord.decode(f, from) ?: return null
            return RecordBreak(r, if (f.getOrNull(from + 6) == "-") null else LeagueRecord.decode(f, from + 6))
        }

        /** Short form for the season summaries, the new record only: "kind:holder:value". */
        internal fun short(b: RecordBreak): String = "${b.record.kind.name}:${Field.esc(b.record.holder)}:${Field.num(b.record.value, 2)}"

        internal fun fromShort(s: String, season: Int): RecordBreak? = runCatching {
            val p = s.split(':')
            RecordBreak(LeagueRecord(RecordKind.valueOf(p[0]), Field.unesc(p[1]), p[2].toDouble(), season), null)
        }.getOrNull()
    }
}

/**
 * The holder of each [RecordKind]: an offer replaces it only when strictly better (the first to
 * reach a value keeps it), and only for a kind not [RecordKind.retired]. The [HISTORY] holders
 * before each one are kept too ([history], 이전 기록): a holder that only raises its own
 * [RecordKind.cumulative] count stays one entry.
 */
class Records {
    private val held = java.util.EnumMap<RecordKind, LeagueRecord>(RecordKind::class.java)
    private val past = java.util.EnumMap<RecordKind, ArrayDeque<LeagueRecord>>(RecordKind::class.java)

    operator fun get(kind: RecordKind): LeagueRecord? = held[kind]

    /** Every record held, retired kinds included, in kind order. */
    val all: List<LeagueRecord> get() = RecordKind.entries.mapNotNull { held[it] }

    /** The records still offered ([RecordKind.current]), in kind order. */
    val current: List<LeagueRecord> get() = RecordKind.current.mapNotNull { held[it] }

    /** The records [kind] had before its holder's, newest first (at most [HISTORY]). */
    fun history(kind: RecordKind): List<LeagueRecord> = past[kind]?.toList().orEmpty()

    /** True when [value] would break the [kind] record (allocation free: checked for every game). */
    fun beats(kind: RecordKind, value: Double): Boolean {
        if (kind.retired || value.isNaN()) return false
        val h = held[kind] ?: return true
        return if (kind.lowerIsBetter) value < h.value else value > h.value
    }

    /** Takes [r] when it breaks its record; returns the break, or null when it does not. */
    fun offer(r: LeagueRecord): RecordBreak? {
        if (!beats(r.kind, r.value)) return null
        val prev = held[r.kind]
        held[r.kind] = r
        // A count raised by its own holder is the same record going on.
        if (prev != null && (prev.holder != r.holder || !r.kind.cumulative)) {
            val list = past.getOrPut(r.kind) { ArrayDeque() }
            list.addFirst(prev)
            while (list.size > HISTORY) list.removeLast()
        }
        return RecordBreak(r, prev)
    }

    internal fun put(r: LeagueRecord) {
        held[r.kind] = r
    }

    /** Adds [r] as the oldest of its kind's [history] so far (reading a file, newest first). */
    internal fun putHistory(r: LeagueRecord) {
        val list = past.getOrPut(r.kind) { ArrayDeque() }
        if (list.size < HISTORY) list.addLast(r)
    }

    /** "rec" and "hist" lines of stats.txt. */
    internal fun encode(out: StringBuilder) {
        for (r in all) out.append("rec ").append(r.encode()).append('\n')
        for (k in RecordKind.entries) for (r in past[k].orEmpty()) out.append("hist ").append(r.encode()).append('\n')
    }

    companion object {
        /** Previous holders kept per kind. */
        const val HISTORY = 3

        /**
         * The AI with the most titles in [honours] (division champions of every tier and league,
         * cup, champions league, 대륙 챔피언스, super cup: [Awards.isAiTitle]), computed on demand;
         * the earliest to reach the count when level (then the first key). Null before the first title.
         */
        fun mostTitles(honours: HonourBook): LeagueRecord? {
            val count = HashMap<String, Int>()
            val reached = HashMap<String, Int>()
            for (h in honours.all.sortedBy { it.season }) {
                if (!Awards.isAiTitle(h.kind)) continue
                count.merge(h.key, 1, Int::plus)
                reached[h.key] = h.season
            }
            val best = count.entries.maxWithOrNull(
                compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { reached.getValue(it.key) }.thenByDescending { it.key },
            ) ?: return null
            return LeagueRecord(RecordKind.TITLES, best.key, best.value.toDouble(), reached.getValue(best.key))
        }
    }
}

/**
 * A player near a record ([RecordChase]): where it stands ([value]: a count, a run, an average so
 * far), its [pace] (a season count projected over its own league fixtures; NaN for the others)
 * and the record it chases ([record], held by [holder]).
 */
data class ChaseLine(
    val kind: RecordKind,
    val key: String,
    val value: Double,
    val pace: Double,
    val record: Double,
    val holder: String,
) {
    /** A season count chased by its pace (승점, 완승, 요트). */
    val paced: Boolean get() = !pace.isNaN()

    /** A season average (평점, 평균 점수, 경기당 승점): compared as it stands. */
    val average: Boolean get() = kind in RecordChase.AVERAGES

    /**
     * How far it is: a count or a run still needs this many to break the record (record − value + 1);
     * a pace or an average stands this far ahead of the record (negative: behind).
     */
    val gap: Double
        get() = when {
            paced -> pace - record
            average -> value - record
            else -> record - value + 1
        }

    /** In Korean: "기록 페이스 +3점", "통산 최다 요트까지 4개", "연승 기록까지 2연승", "기록 대비 평점 -0.12". */
    fun text(): String {
        val g = gap
        return when (kind) {
            RecordKind.SEASON_POINTS -> "기록 페이스 ${signed(g, 0)}점"
            RecordKind.SEASON_SWEEPS -> "기록 페이스 ${signed(g, 0)}번"
            RecordKind.SEASON_YACHTS -> "기록 페이스 ${signed(g, 0)}개"
            RecordKind.SEASON_RATING2 -> "기록 대비 평점 ${signed(g, 2)}"
            RecordKind.SEASON_SCORE -> "기록 대비 평균 ${signed(g, 1)}점"
            RecordKind.SEASON_PPM -> "기록 대비 경기당 ${signed(g, 2)}점"
            RecordKind.STREAK -> "연승 기록까지 ${g.roundToInt()}연승"
            RecordKind.YACHT_RUN -> "연속 요트 기록까지 ${g.roundToInt()}경기"
            RecordKind.CAREER_GAMES -> "통산 최다 판까지 ${count(g)}판"
            RecordKind.CAREER_WINS -> "통산 최다 승리까지 ${count(g)}승"
            RecordKind.CAREER_YACHTS -> "통산 최다 요트까지 ${count(g)}개"
            RecordKind.CAREER_300 -> "통산 최다 300점 경기까지 ${count(g)}판"
            RecordKind.TITLES -> "최다 우승까지 ${count(g)}회"
            RecordKind.MVPS -> "최다 MVP까지 ${count(g)}회"
            else -> "${kind.label}까지 ${count(g)}"
        }
    }

    private fun signed(x: Double, digits: Int): String {
        val s = String.format(Locale.ROOT, "%.${digits}f", abs(x))
        val zero = s.all { it == '0' || it == '.' }
        return if (zero) s else (if (x > 0) "+" else "-") + s
    }

    private fun count(x: Double): String = String.format(Locale.ROOT, "%,d", x.roundToInt())
}

/**
 * Who is chasing which record (기록 추격): for every record that can be chased in the season or
 * a career, the nearest [TOP] challengers, cheap to build on request (one pass over the players
 * per kind). Season counts go by their pace over the player's own league fixtures, averages as
 * they stand (over enough matches), career counts and runs by what they still need; the holder of
 * a career count or a run is not its own challenger (it extends the record). [near] says which
 * lines are close enough for news ("기록에 다가선다").
 */
object RecordChase {
    const val TOP = 3

    /** Season kinds chased by pace, by average, and the career kinds and runs. */
    val PACED = listOf(RecordKind.SEASON_POINTS, RecordKind.SEASON_SWEEPS, RecordKind.SEASON_YACHTS)
    val AVERAGES = setOf(RecordKind.SEASON_RATING2, RecordKind.SEASON_SCORE, RecordKind.SEASON_PPM)
    val CAREER = listOf(
        RecordKind.STREAK, RecordKind.YACHT_RUN, RecordKind.CAREER_WINS, RecordKind.CAREER_YACHTS, RecordKind.CAREER_300,
        RecordKind.CAREER_GAMES, RecordKind.TITLES, RecordKind.MVPS,
    )

    /** A pace counts once a player has played this share of its own league fixtures (and [MIN_PLAYED]). */
    const val PACE_FROM = 0.25
    const val MIN_PLAYED = 5

    /** An average counts over this many official matches. */
    const val AVERAGE_MATCHES = 10

    /**
     * The chases of [stats]'s league, kinds in [RecordKind] order, each with at most [top] lines
     * (kinds without a record or a challenger are left out). [plan] is the season in play (none:
     * career kinds only); [keys] the players that may chase (default: everyone with a career).
     */
    fun of(stats: StatsBook, plan: SeasonPlan?, keys: Collection<String>? = null, top: Int = TOP): List<Pair<RecordKind, List<ChaseLine>>> {
        val who = keys?.toList() ?: stats.keys.sorted()
        val out = ArrayList<Pair<RecordKind, List<ChaseLine>>>()
        val season = plan?.league?.takeIf { plan.number == stats.season }
        for (kind in RecordKind.current) {
            val rec = stats.records[kind] ?: continue
            val lines = when {
                kind in PACED -> if (season == null) emptyList() else paced(kind, rec, stats, season, who)
                kind in AVERAGES -> if (season == null) emptyList() else averages(kind, rec, stats, season, who)
                kind in CAREER -> career(kind, rec, stats, who)
                else -> emptyList()
            }
            val order = if (kind in CAREER) compareBy<ChaseLine> { it.gap } else compareByDescending { it.gap }
            val best = lines.sortedWith(order.thenBy { it.key }).take(top)
            if (best.isNotEmpty()) out += kind to best
        }
        return out
    }

    /** True when [l] is close: a count within [NEAR_SHARE] of the record (at least [NEAR_MIN]), a run within 2, a pace or an average on record terms. */
    fun near(l: ChaseLine): Boolean = when {
        l.paced -> l.gap >= 0
        l.average -> l.gap >= -NEAR_AVERAGE.getValue(l.kind)
        l.kind == RecordKind.STREAK || l.kind == RecordKind.YACHT_RUN -> l.gap <= NEAR_RUN
        else -> l.gap <= maxOf(NEAR_MIN.toDouble(), l.record * NEAR_SHARE)
    }

    private const val NEAR_SHARE = 0.01
    private const val NEAR_MIN = 3
    private const val NEAR_RUN = 2
    private val NEAR_AVERAGE = mapOf(RecordKind.SEASON_RATING2 to 0.05, RecordKind.SEASON_SCORE to 1.0, RecordKind.SEASON_PPM to 0.05)

    private fun paced(kind: RecordKind, rec: LeagueRecord, stats: StatsBook, season: Season, keys: List<String>): List<ChaseLine> = keys.mapNotNull { k ->
        val s = season.standings[k] ?: return@mapNotNull null
        val own = season.fixturesOf(k)
        if (own == 0 || s.played < MIN_PLAYED || s.played < PACE_FROM * own) return@mapNotNull null
        val value = when (kind) {
            RecordKind.SEASON_POINTS -> s.points
            RecordKind.SEASON_SWEEPS -> if (season.bonus) s.sweeps else return@mapNotNull null
            else -> stats.season(k)?.yachts ?: return@mapNotNull null
        }.toDouble()
        ChaseLine(kind, k, value, value * own / s.played, rec.value, rec.holder)
    }

    private fun averages(kind: RecordKind, rec: LeagueRecord, stats: StatsBook, season: Season, keys: List<String>): List<ChaseLine> = keys.mapNotNull { k ->
        val st = stats.season(k) ?: return@mapNotNull null
        val value = when (kind) {
            RecordKind.SEASON_RATING2 -> if (stats.ratingScale.version < 2 || st.matches < AVERAGE_MATCHES) return@mapNotNull null else st.avgRating
            RecordKind.SEASON_SCORE -> if (st.matches < AVERAGE_MATCHES) return@mapNotNull null else st.avgScore
            else -> {
                val s = season.standings[k] ?: return@mapNotNull null
                if (s.played < MIN_PLAYED || s.played < PACE_FROM * season.fixturesOf(k)) return@mapNotNull null
                s.points.toDouble() / s.played
            }
        }
        if (value.isNaN()) null else ChaseLine(kind, k, value, Double.NaN, rec.value, rec.holder)
    }

    private fun career(kind: RecordKind, rec: LeagueRecord, stats: StatsBook, keys: List<String>): List<ChaseLine> = keys.mapNotNull { k ->
        if (k == rec.holder) return@mapNotNull null
        val c = stats.career(k)
        val value = when (kind) {
            RecordKind.STREAK -> c?.streak ?: 0
            RecordKind.YACHT_RUN -> stats.yachtRun(k)
            RecordKind.CAREER_WINS -> c?.gameWins ?: 0
            RecordKind.CAREER_YACHTS -> c?.yachts ?: 0
            RecordKind.CAREER_300 -> c?.over300 ?: 0
            RecordKind.CAREER_GAMES -> c?.games ?: 0
            RecordKind.TITLES -> stats.titles(k)
            else -> stats.mvps(k)
        }
        // A run must be going; a count must have started.
        if (value <= 0 || value > rec.value) null else ChaseLine(kind, k, value.toDouble(), Double.NaN, rec.value, rec.holder)
    }
}

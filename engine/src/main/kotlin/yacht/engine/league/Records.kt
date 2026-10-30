package yacht.engine.league

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
 * the season records, titles and awards from finished seasons. [lowerIsBetter]: the comeback (the
 * lowest P(win) a winner had, in percent), the lowest winning score, the youngest champion.
 * [running]: a count one holder keeps raising game by game (one break a season, not one a game).
 * YACHTS_MATCH counts one AI's Yachts in one match (경기). The youngest and oldest champion are of
 * any league's title ([HonourKind.isTopTitle]: the old single league's or a national league's).
 */
enum class RecordKind(val label: String, val group: RecordGroup, val lowerIsBetter: Boolean = false, val running: Boolean = false) {
    BEST_GAME("한 판 최고 점수", RecordGroup.GAME),
    MARGIN("최다 점수 차 승리", RecordGroup.GAME),
    COMEBACK("최대 역전승", RecordGroup.GAME, lowerIsBetter = true),
    YACHTS_MATCH("한 경기 최다 요트", RecordGroup.GAME),
    STREAK("최다 연승", RecordGroup.CAREER, running = true),
    SEASON_POINTS("한 시즌 최다 승점", RecordGroup.SEASON),
    SEASON_RATING("한 시즌 최고 평균 평점", RecordGroup.SEASON),
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

    /** The value as events carry it: a whole number, the rating with two decimals, an average or a rate with one. */
    fun plain(v: Double): String = when (this) {
        SEASON_RATING -> Field.num(v, 2)
        SEASON_SCORE, SEASON_WINRATE -> Field.num(v, 1)
        else -> v.roundToInt().toString()
    }

    /** The value for the screen, in Korean. */
    fun text(v: Double): String = when (this) {
        BEST_GAME -> "${plain(v)}점"
        MARGIN -> "${plain(v)}점 차"
        COMEBACK -> if (v < 1) "승리 확률 1% 미만에서 역전" else "승리 확률 ${plain(v)}%에서 역전"
        YACHTS_MATCH -> "요트 ${plain(v)}번"
        STREAK -> "${plain(v)}연승"
        SEASON_POINTS -> "승점 ${plain(v)}"
        SEASON_RATING -> "평점 ${plain(v)}"
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

/** The holder of each [RecordKind]: an offer replaces it only when strictly better (the first to reach a value keeps it). */
class Records {
    private val held = java.util.EnumMap<RecordKind, LeagueRecord>(RecordKind::class.java)

    operator fun get(kind: RecordKind): LeagueRecord? = held[kind]

    val all: List<LeagueRecord> get() = RecordKind.entries.mapNotNull { held[it] }

    /** True when [value] would break the [kind] record (allocation free: checked for every game). */
    fun beats(kind: RecordKind, value: Double): Boolean {
        val h = held[kind] ?: return true
        return if (kind.lowerIsBetter) value < h.value else value > h.value
    }

    /** Takes [r] when it breaks its record; returns the break, or null when it does not. */
    fun offer(r: LeagueRecord): RecordBreak? {
        if (!beats(r.kind, r.value)) return null
        val prev = held[r.kind]
        held[r.kind] = r
        return RecordBreak(r, prev)
    }

    internal fun put(r: LeagueRecord) {
        held[r.kind] = r
    }

    companion object {
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

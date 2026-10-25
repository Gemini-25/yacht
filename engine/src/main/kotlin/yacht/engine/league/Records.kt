package yacht.engine.league

import kotlin.math.roundToInt

/**
 * The league's all-time records (기록실). Game and match records come from official games
 * ([StatsBook.countsForSeason]), the winning run from every rated game as on the ladder, the
 * season records and titles from finished seasons. [lowerIsBetter]: the comeback, the lowest
 * P(win) a winner had (in percent). YACHTS_MATCH counts one AI's Yachts in one match (경기).
 */
enum class RecordKind(val label: String, val lowerIsBetter: Boolean = false) {
    BEST_GAME("한 판 최고 점수"),
    MARGIN("최다 점수 차 승리"),
    COMEBACK("최대 역전승", lowerIsBetter = true),
    YACHTS_MATCH("한 경기 최다 요트"),
    STREAK("최다 연승"),
    SEASON_POINTS("한 시즌 최다 승점"),
    SEASON_RATING("한 시즌 최고 평균 평점"),
    TITLES("최다 우승"),
    ;

    /** The value as events carry it: a whole number, or the rating with two decimals. */
    fun plain(v: Double): String = if (this == SEASON_RATING) Field.num(v, 2) else v.roundToInt().toString()

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
         * The AI with the most titles in [honours] (division champions, cup, champions league,
         * super cup), computed on demand; the earliest to reach the count when level (then the
         * first key). Null before the first title.
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

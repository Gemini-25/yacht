package yacht.engine.league

import java.util.TreeMap

/** A coach's philosophy (지도 철학, design ENG 2.1): who its club signs; [family] the style family it favours (none for 유망주 육성형 and 스타 영입형). [code] is saved. */
enum class Philosophy(val code: Char, val label: String, val desc: String, val family: StyleFamily?) {
    ATTACK('A', "공격형", "요트와 큰 족보를 노리는 과감한 선수를 좋아해요.", StyleFamily.YACHT),
    BONUS('B', "보너스형", "위쪽 칸 보너스를 꼬박꼬박 챙기는 선수를 모아요.", StyleFamily.BONUS),
    YOUTH('Y', "유망주 육성형", "어린 선수를 키워 쓰는 걸 좋아해요. 선수들이 조금 더 빨리 성장해요.", null),
    STABLE('S', "안정형", "흔들리지 않는 침착한 선수와 실수 없는 운영을 좋아해요.", StyleFamily.CAUTION),
    COMBO('C', "족보형", "포카드·풀하우스·스트레이트에 강한 선수를 찾아요.", StyleFamily.COMBO),
    PRAGMATIC('P', "실리형", "기대 점수를 끝까지 계산하는 정석파를 몸값 대비 효율로 골라요.", StyleFamily.EV),
    STAR('X', "스타 영입형", "이름값 있는 최고의 선수를 데려오는 걸 좋아해요. 돈이 많이 들어요.", null),
    ;

    companion object {
        fun of(code: Char): Philosophy? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A coach's spell at [club] from season [from] to [to] (0 = now): its [best] club-table place,
 * [titles] club-table titles, and how it ended ([end]: S sacked, P poached, L left at the end of
 * its contract, R retired, '-' going on).
 */
data class Tenure(val club: String, val from: Int, val to: Int, val best: Int, val titles: Int, val end: Char)

/**
 * A coach (감독, design ENG 2.1): id "k<n>" (never reused), its identity (age = season − [born]),
 * philosophy, development skill [dev] 1..5 and [reputation] 0..100; its club (null: free) since
 * [since] on a contract to [until] at [wage]; the player it was ([player], an ex-player coach);
 * seasons in charge, club-table titles, members' league titles, sackings, its newest 5 [tenures]
 * (older ones are summed into the counts) and its 올해의 감독 [awards].
 */
data class Coach(
    val id: String,
    val name: String, val nation: String, val gender: Gender, val born: Int,
    val philosophy: Philosophy, val dev: Int, val reputation: Int,
    val club: String?, val since: Int, val until: Int, val wage: Int,
    val player: String?,
    val seasons: Int, val clubTitles: Int, val leagueTitles: Int, val sacked: Int,
    val tenures: List<Tenure>,
    val awards: Int,
) {
    fun age(season: Int): Int = season - born
}

/** A retired coach kept for its honours (≤ 100): [summary] the space-free counts ("CT:3,L0:5,COY:1,clubs:4"). */
data class RetiredCoach(
    val id: String, val name: String, val nation: String, val gender: Gender, val born: Int, val retired: Int,
    val philosophy: Philosophy, val reputation: Int, val player: String?, val summary: String,
)

/**
 * What the board expects of a club through a closed week (design ENG 2.2), as the screens show it:
 * the [expected] club-table place from the season's drawn ratings, the owner's [target] (clamped to
 * 1..[clubs]), the [place] now, `gap = (target − place) / clubs` (positive = better than asked), the
 * coach's [pressure] and sacking [threshold], and the pressure the next checkpoint would leave at
 * this gap ([projected]). Recomputed on request, never stored (P5's `CoachMarket.outlook`).
 */
data class BoardOutlook(
    val expected: Int, val target: Int, val place: Int, val clubs: Int,
    val gap: Double, val pressure: Double, val threshold: Double, val projected: Double,
)

/** What the coach market reads about the players of the league (P5 adds its fields, with defaults). */
class CoachInput

/**
 * The coaches of a club world (design ENG 2.6): every coach with a club or in the free pool (`k`)
 * and the retired coaches kept for their honours (`kr`). The market's logic (hiring, sackings,
 * poaching, retirements, reputation) is P5's, in this file.
 */
class CoachBook {
    /** Every active coach (employed or free), by id number. */
    internal val active = TreeMap<String, Coach>(ID_ORDER)
    internal val retiredList = ArrayList<RetiredCoach>()

    fun coach(id: String): Coach? = active[id]

    /** Club [club]'s coach, null for none. */
    fun coachOf(club: String): Coach? = active.values.firstOrNull { it.club == club }

    /** Every active coach, by id. */
    val all: List<Coach> get() = active.values.toList()

    /** The free pool: coaches without a club, by id. */
    val free: List<Coach> get() = active.values.filter { it.club == null }

    /** Retired coaches kept, oldest retirement first. */
    val retired: List<RetiredCoach> get() = retiredList.toList()

    /** Every kept spell at club [club] with its coach's id, newest first (then by coach id). */
    fun tenuresAt(club: String): List<Pair<String, Tenure>> =
        active.values.flatMap { c -> c.tenures.filter { it.club == club }.map { c.id to it } }
            .sortedWith(compareByDescending<Pair<String, Tenure>> { it.second.from }.thenComparing({ it.first }, ID_ORDER))

    companion object {
        /** "k1", "k2", ... by number; other ids after them by text. */
        val ID_ORDER: Comparator<String> = compareBy<String> { it.removePrefix("k").toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }

        /** Tenures a coach keeps (older ones live on in its counts). */
        const val TENURES = 5

        private fun tenure(t: Tenure) = "${t.club}:${t.from}:${t.to}:${t.best}:${t.titles}:${t.end}"

        private val K = LineParts.of<ClubWorld>("k", { w, out ->
            for (c in w.coaches.active.values) {
                out.append("k ${c.id} ${Field.esc(c.name)} ${c.nation} ${c.gender.code} ${c.born} ${c.philosophy.code} ${c.dev} ${c.reputation} ")
                out.append("${c.club ?: "-"} ${c.since} ${c.until} ${c.wage} ${LineParts.opt(c.player)} ${c.seasons} ${c.clubTitles} ${c.leagueTitles} ${c.sacked} ${c.awards} ")
                out.append(LineParts.list(c.tenures, ';', ::tenure)).append('\n')
            }
        }) { w, f ->
            require(f.size >= 20)
            val tenures = LineParts.items(f[19], ';').map { e ->
                val p = e.split(':')
                Tenure(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].single())
            }
            val c = Coach(
                f[1], Field.unesc(f[2]), f[3], Gender.of(f[4].single()) ?: Gender.MALE, f[5].toInt(),
                Philosophy.of(f[6].single()) ?: return@of false, f[7].toInt(), f[8].toInt(),
                f[9].takeIf { it != "-" }, f[10].toInt(), f[11].toInt(), f[12].toInt(), Field.opt(f[13]),
                f[14].toInt(), f[15].toInt(), f[16].toInt(), f[17].toInt(), tenures, f[18].toInt(),
            )
            w.coaches.active[c.id] = c
            true
        }

        private val KR = LineParts.of<ClubWorld>("kr", { w, out ->
            for (c in w.coaches.retiredList) {
                out.append("kr ${c.id} ${Field.esc(c.name)} ${c.nation} ${c.gender.code} ${c.born} ${c.retired} ${c.philosophy.code} ${c.reputation} ")
                out.append("${LineParts.opt(c.player)} ${LineParts.opt(c.summary)}\n")
            }
        }) { w, f ->
            require(f.size >= 11)
            w.coaches.retiredList += RetiredCoach(
                f[1], Field.unesc(f[2]), f[3], Gender.of(f[4].single()) ?: Gender.MALE, f[5].toInt(), f[6].toInt(),
                Philosophy.of(f[7].single()) ?: return@of false, f[8].toInt(), Field.opt(f[9]), Field.opt(f[10]).orEmpty(),
            )
            true
        }

        /** `k kr`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(K, KR)
    }
}

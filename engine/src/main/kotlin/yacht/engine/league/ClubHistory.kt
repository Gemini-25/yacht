package yacht.engine.league

import java.util.TreeMap

/** A rival club (design ENG 1b.6): [kind] 'N' national classic, 'R' regional derby, 'H' history, 'T' transfer rivalry, since season [since]. */
data class Rival(val club: String, val kind: Char, val since: Int)

/** A club legend (클럽 레전드, ENG 1b.7): its [score], seasons and titles at the club, whether it played only there. */
data class ClubLegend(val key: String, val score: Int, val seasons: Int, val titles: Int, val oneClub: Boolean)

/** A retired shirt number (영구결번): [number] retired for [key] after [season]. */
data class RetiredNumber(val number: Int, val key: String, val season: Int)

/** A club record's kind; [code] is saved. */
enum class ClubRecordKind(val code: Char, val label: String) {
    APPS('A', "최다 출전"),
    TITLES('T', "최다 우승"),
    SIGNING('S', "최고 영입액"),
    SALE('L', "최고 판매액"),
    ;

    companion object {
        fun of(code: Char): ClubRecordKind? = entries.firstOrNull { it.code == code }
    }
}

/** A club record: [value] (apps, titles, a fee) held by [key] (null when nobody), set in [season]. */
data class ClubRecord(val kind: ClubRecordKind, val key: String?, val value: Int, val season: Int)

/**
 * The club world's history part (design ENG 1b.6-1b.7): each club's rivals (`rv`), the rival
 * pairs' all-time series (`dr`), legends (`lg`), retired numbers (`rn`) and records (`cr`). Kept
 * by club id, also for a dissolved club (its legends and records stay beside its `gone` line).
 * The logic (rivals, the season-series fold, legends, numbers, records) is P6's.
 */
class ClubHistoryBook {
    internal val rivals = TreeMap<String, List<Rival>>(ClubBook.ID_ORDER)

    /** By [pairKey]. */
    internal val derbies = TreeMap<String, DerbyRecord>()
    internal val legends = TreeMap<String, List<ClubLegend>>(ClubBook.ID_ORDER)
    internal val retired = TreeMap<String, List<RetiredNumber>>(ClubBook.ID_ORDER)
    internal val records = TreeMap<String, List<ClubRecord>>(ClubBook.ID_ORDER)

    fun rivalsOf(club: String): List<Rival> = rivals[club].orEmpty()

    fun derby(a: String, b: String): DerbyRecord? = derbies[pairKey(a, b)]

    companion object {
        /** The two ids of a pair in [ClubBook.ID_ORDER], as a [DerbyRecord] holds them. */
        fun pair(a: String, b: String): Pair<String, String> = if (ClubBook.ID_ORDER.compare(a, b) <= 0) a to b else b to a

        internal fun pairKey(a: String, b: String): String = pair(a, b).let { "${it.first} ${it.second}" }

        private val RV = LineParts.of<ClubWorld>("rv", { w, out ->
            for ((id, rs) in w.history.rivals) if (rs.isNotEmpty()) out.append("rv $id ").append(rs.joinToString(",") { "${it.club}:${it.kind}:${it.since}" }).append('\n')
        }) { w, f ->
            w.history.rivals[f[1]] = LineParts.items(f[2]).map { e ->
                val p = e.split(':')
                Rival(p[0], p[1].single(), p[2].toInt())
            }
            true
        }

        private val DR = LineParts.of<ClubWorld>("dr", { w, out ->
            for (d in w.history.derbies.values) out.append("dr ${d.a} ${d.b} ${d.winsA} ${d.draws} ${d.winsB} ${d.games} ${d.since} ${d.last}\n")
        }) { w, f ->
            val d = DerbyRecord(f[1], f[2], f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toInt(), f[8].toInt())
            w.history.derbies[pairKey(d.a, d.b)] = d
            true
        }

        private val LG = LineParts.of<ClubWorld>("lg", { w, out ->
            for ((id, ls) in w.history.legends) {
                if (ls.isEmpty()) continue
                out.append("lg $id ").append(ls.joinToString(",") { "${Field.esc(it.key)}:${it.score}:${it.seasons}:${it.titles}:${LineParts.bit(it.oneClub)}" }).append('\n')
            }
        }) { w, f ->
            w.history.legends[f[1]] = LineParts.items(f[2]).map { e ->
                val p = e.split(':')
                ClubLegend(Field.unesc(p[0]), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4] == "1")
            }
            true
        }

        private val RN = LineParts.of<ClubWorld>("rn", { w, out ->
            for ((id, ns) in w.history.retired) if (ns.isNotEmpty()) out.append("rn $id ").append(ns.joinToString(",") { "${it.number}:${Field.esc(it.key)}:${it.season}" }).append('\n')
        }) { w, f ->
            w.history.retired[f[1]] = LineParts.items(f[2]).map { e ->
                val p = e.split(':')
                RetiredNumber(p[0].toInt(), Field.unesc(p[1]), p[2].toInt())
            }
            true
        }

        private val CR = LineParts.of<ClubWorld>("cr", { w, out ->
            for ((id, rs) in w.history.records) {
                if (rs.isEmpty()) continue
                out.append("cr $id ").append(rs.joinToString(",") { "${it.kind.code}:${LineParts.opt(it.key)}:${it.value}:${it.season}" }).append('\n')
            }
        }) { w, f ->
            w.history.records[f[1]] = LineParts.items(f[2]).mapNotNull { e ->
                val p = e.split(':')
                ClubRecordKind.of(p[0].single())?.let { ClubRecord(it, Field.opt(p[1]), p[2].toInt(), p[3].toInt()) }
            }
            true
        }

        /** `rv dr lg rn cr`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(RV, DR, LG, RN, CR)
    }
}

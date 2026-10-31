package yacht.engine.league

import java.util.TreeMap

/** An encyclopedia entity's kind (자동 백과사전, design ENG 7.1); [prefix] starts its ref. */
enum class EncKind(val prefix: Char) {
    PLAYER('p'), CLUB('c'), COACH('k'), LEAGUE('l'), NATION('n'), COMPETITION('x');

    companion object {
        fun of(prefix: Char): EncKind? = entries.firstOrNull { it.prefix == prefix }
    }
}

/**
 * A page's address: "p:g001", "c:c05", "c:c05@2031" (a gone or reused club id with its founding
 * year), "k:k12", "l:KR", "n:KR", "x:CL", "x:CCL.ASIA".
 */
data class EncRef(val kind: EncKind, val id: String) {
    fun encode(): String = "${kind.prefix}:$id"

    companion object {
        fun decode(s: String): EncRef? {
            if (s.length < 3 || s[1] != ':') return null
            return EncKind.of(s[0])?.let { EncRef(it, s.substring(2)) }
        }
    }
}

/** An infobox fact: [label], [value] and the page it links to. */
data class EncFact(val label: String, val value: String, val link: EncRef? = null)

/** A name inside a paragraph, characters [start] until [end], linking to [ref]. */
data class EncLink(val start: Int, val end: Int, val ref: EncRef)

data class EncPara(val text: String, val links: List<EncLink> = emptyList())

data class EncSection(val heading: String, val paras: List<EncPara>)

/** A timeline entry of [season], linking to a page or a match ([match], a [MatchRef] encoded). */
data class EncEvent(val season: Int, val text: String, val link: EncRef? = null, val match: String? = null)

/** A page (design ENG 7.2-7.3): infobox (≤ 8 facts), lead, timeline, chapters, related pages. */
data class EncPage(
    val ref: EncRef, val title: String, val subtitle: String,
    val infobox: List<EncFact>, val lead: EncPara, val sections: List<EncSection>,
    val timeline: List<EncEvent>, val related: List<EncRef>,
)

/** A search hit. */
data class EncHit(val ref: EncRef, val title: String, val subtitle: String, val score: Int)

/** The search page's empty state: 이번 주의 문서 (a stable pick by season and week), the 3 newest retired legends, last season's champions of the 3 best-ranked leagues and their clubs. */
data class EncBrowse(val featured: EncHit?, val legends: List<EncHit>, val champions: List<EncHit>)

/** A final (`fx`) of [season]: competition [comp] won by [winner] over [runnerUp], [score] as shown ("3:1"). */
data class EncFinal(val season: Int, val comp: String, val winner: String, val runnerUp: String, val score: String)

/** A league's history (`lh`): its first season and its events (formed, merged, split, boom) with their seasons. */
data class LeagueHistory(val league: String, val first: Int, val events: List<Pair<String, Int>>)

/**
 * The encyclopedia's part of world.txt (design ENG 7.4): the finals of the seasons kept (`fx`) and
 * each league's history (`lh`). The pages, search and index are P19's (`Encyclopedia`, in this file).
 */
class EncBook {
    internal val finals = ArrayList<EncFinal>()
    internal val leagues = TreeMap<String, LeagueHistory>()

    /** The finals of [season], in the order they were written. */
    fun finals(season: Int): List<EncFinal> = finals.filter { it.season == season }

    val allFinals: List<EncFinal> get() = finals.toList()

    fun league(id: String): LeagueHistory? = leagues[id]

    companion object {
        private val FX = LineParts.of<WorldBook>("fx", { w, out ->
            for (x in w.enc.finals) out.append("fx ${x.season} ${Field.esc(x.comp)} ${Field.esc(x.winner)} ${Field.esc(x.runnerUp)} ${Field.esc(x.score)}\n")
        }) { w, f ->
            require(f.size >= 6)
            w.enc.finals += EncFinal(f[1].toInt(), Field.unesc(f[2]), Field.unesc(f[3]), Field.unesc(f[4]), Field.unesc(f[5]))
            true
        }

        private val LH = LineParts.of<WorldBook>("lh", { w, out ->
            for (h in w.enc.leagues.values) out.append("lh ${Field.esc(h.league)} ${h.first} ${LineParts.list(h.events) { "${Field.esc(it.first)}:${it.second}" }}\n")
        }) { w, f ->
            val events = LineParts.items(f[3]).map { e -> Field.unesc(e.substringBefore(':')) to e.substringAfter(':').toInt() }
            val id = Field.unesc(f[1])
            w.enc.leagues[id] = LeagueHistory(id, f[2].toInt(), events)
            true
        }

        /** `fx lh`. */
        val PARTS: List<LinePart<WorldBook>> = listOf(FX, LH)
    }
}

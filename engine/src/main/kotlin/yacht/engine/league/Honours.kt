package yacht.engine.league

/** Every season's titles and awards, newest season last (league/honours.txt). */
class HonourBook {
    private val list = ArrayList<Honour>()

    val all: List<Honour> get() = list

    val seasons: List<Int> get() = list.map { it.season }.distinct().sorted()

    fun of(season: Int): List<Honour> = list.filter { it.season == season }

    fun of(key: String): List<Honour> = list.filter { it.key == key }

    fun add(h: Collection<Honour>) {
        val seasons = h.map { it.season }.toSet()
        // A season is written once: a repeated rollover replaces it.
        list.removeAll { it.season in seasons }
        list += h
        trim()
    }

    /** Titles of [key] (division champions of every tier and league, and cups; not moves or awards). */
    fun titles(key: String): Int = list.count { it.key == key && isTitle(it.kind) }

    /**
     * Major titles of [key] (the hall of fame, "리그의 제왕", the evolution mentor): the cup, the
     * champions league, a 대륙 챔피언스, the super cup, a world cup won with the squad, the old
     * single league's title, and a national league's title ("L0.KR") when [major] says that
     * league counted in that season (one of the best-ranked it was played under).
     */
    fun majorTitles(key: String, major: (season: Int, league: String) -> Boolean = { _, _ -> true }): Int =
        list.count { it.key == key && isMajor(it, major) }

    private fun trim() {
        val keep = seasons.takeLast(MAX_SEASONS).toSet()
        list.removeAll { it.season !in keep }
    }

    fun encode(): String = buildString {
        append("honours 1\n")
        for (h in list) append("h ${h.encode()}\n")
    }

    companion object {
        const val MAX_SEASONS = 100

        /** Title kinds besides the division titles, by [HonourKind.base] ("CCL.ASIA" is a "CCL"). */
        val TITLES = setOf("CUP", "CL", "SUPER", "FAMILY", "CLUB", "WC", "CC", "WCW", "CCL", "CLUBCUP", "CLUBCUPW")

        /** Major title kinds besides the league titles, by [HonourKind.base] ([majorTitles]). */
        val MAJOR = setOf("CUP", "CL", "CCL", "SUPER", "WCW")

        /** A title: a division of any tier and league ("L3", "L1.KR") or a [TITLES] kind. */
        fun isTitle(kind: String): Boolean = HonourKind.isDivision(kind) || HonourKind.base(kind) in TITLES

        /**
         * A major title ([majorTitles]): a [MAJOR] kind, the old single league's "L0", or a national
         * league's "L0.<id>" when [major] (season, league id) says so.
         */
        fun isMajor(h: Honour, major: (season: Int, league: String) -> Boolean = { _, _ -> true }): Boolean {
            if (!HonourKind.isTopTitle(h.kind)) return HonourKind.base(h.kind) in MAJOR
            val league = HonourKind.leagueOf(h.kind) ?: return true
            return major(h.season, league)
        }

        fun decode(text: String): HonourBook {
            val b = HonourBook()
            for (line in text.lines()) {
                val f = line.split(' ')
                if (f[0] == "h" && f.size >= 4) Honour.decode(f.drop(1))?.let { b.list += it }
            }
            return b
        }

        /**
         * Honours from a season file of the first version ("season 1": summary lines with the
         * division champions, top scorer, yacht king and moves), so its history is kept.
         */
        fun legacy(text: String): List<Honour> {
            if (text.lines().firstOrNull()?.trim() != "season 1") return emptyList()
            val out = ArrayList<Honour>()
            for (line in text.lines()) {
                val f = line.split(' ')
                if (f[0] != "summary" || f.size < 8) continue
                runCatching {
                    val n = f[1].toInt()
                    f[2].split(',').filter { it.isNotEmpty() }.forEachIndexed { d, k -> out += Honour(n, "L$d", k) }
                    if (f[3] != "-") out += Honour(n, "SCORER", f[3])
                    if (f[5] != "-") out += Honour(n, "YACHT", f[5])
                    if (f[7] != "-") for (m in f[7].split(';')) {
                        val p = m.split(':')
                        val from = p[1].toInt()
                        val to = p[2].toInt()
                        out += Honour(n, if (to < from) "UP$to" else "DOWN$to", p[0])
                    }
                }
            }
            return out
        }
    }
}

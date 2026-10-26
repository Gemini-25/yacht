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

    /** Titles of [key] (division champions and cups, not moves or awards). */
    fun titles(key: String): Int = list.count { it.key == key && (it.kind in TITLES || DIVISION.matches(it.kind)) }

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
        val TITLES = setOf("CUP", "CL", "SUPER", "FAMILY", "CLUB", "WC", "CC", "WCW")
        private val DIVISION = Regex("L\\d")

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

package yacht.engine.league

import java.util.Locale

/**
 * The national teams' history (league/nations.txt, `nations 1`): each nation's editions played,
 * team matches won, drawn and lost, titles and best finishes; each player's national career
 * (caps: national cup editions, boards won, drawn and lost, its games); and every edition's
 * podium. Filled once per season from the season's finished national cups ([close]); a repeated
 * close of the same season changes nothing. Not thread-safe: callers synchronise.
 */
class NationBook {
    /** A nation's record. Best finishes: 0 champion, 1 runner-up, 2 semi-finals, ... ([NONE] never played). */
    class NationRecord(val code: String) {
        var editions = 0
        var won = 0
        var drawn = 0
        var lost = 0

        /** Boards won and lost, in halves (a level board is one each). */
        var boardsFor = 0
        var boardsAgainst = 0
        var worldTitles = 0
        var continentalTitles = 0
        var worldBest = NONE
        var continentalBest = NONE

        val matches: Int get() = won + drawn + lost

        fun encode() = "nat $code $editions $won $drawn $lost $boardsFor $boardsAgainst $worldTitles $continentalTitles $worldBest $continentalBest"
    }

    /** A player's national career: [caps] editions called up, boards and games across them. */
    class CapRecord(val key: String, var code: String) {
        var caps = 0
        var won = 0
        var drawn = 0
        var lost = 0
        var games = 0
        var points = 0
        var best = 0
        var first = 0
        var last = 0

        /** Times in a winning national team (world cup or continental cup). */
        var titles = 0

        val boards: Int get() = won + drawn + lost
        val average: Double get() = if (games == 0) Double.NaN else points.toDouble() / games

        fun encode() = "cap $key $code $caps $won $drawn $lost $games $points $best $first $last $titles"
    }

    /** One edition: the world cup (one podium) or the continental cups (one per confederation). */
    class Edition(
        val season: Int,
        val world: Boolean,
        val host: String?,
        val teams: Int,
        val podiums: List<CupSummary.Podium>,
        val mvp: String?,
        val scorer: String?,
    ) {
        fun encode(): String = buildString {
            append("ed ").append(season).append(' ').append(if (world) 'N' else 'R').append(' ').append(host ?: "-").append(' ').append(teams)
            append(' ').append(mvp ?: "-").append(' ').append(scorer ?: "-")
            for (p in podiums) {
                append(' ').append(p.confed?.name ?: "-").append(':').append(p.winner ?: "-").append(':').append(p.runnerUp ?: "-")
                append(':').append(p.semis.joinToString(",").ifEmpty { "-" })
            }
        }
    }

    /** The last season whose cups are in the book. */
    var closed = 0
        private set

    private val nationMap = LinkedHashMap<String, NationRecord>()
    private val capMap = LinkedHashMap<String, CapRecord>()
    private val editionList = ArrayList<Edition>()

    val nations: List<NationRecord> get() = nationMap.values.toList()
    val caps: List<CapRecord> get() = capMap.values.toList()
    val editions: List<Edition> get() = editionList

    operator fun get(code: String): NationRecord? = nationMap[code]

    fun cap(key: String): CapRecord? = capMap[key]

    /**
     * Season [season]'s finished national cups go into the book, once; [keep] says which
     * players' careers to keep besides those with [KEEP_CAPS] caps or more (the alive and the
     * legends), so the book stays small in a league of thousands.
     */
    fun close(season: Int, cups: List<CupSummary>, keep: (String) -> Boolean = { true }) {
        if (season <= closed) return
        for (c in cups) add(c)
        closed = season
        capMap.entries.removeAll { (k, v) -> v.caps < KEEP_CAPS && !keep(k) }
    }

    private fun add(c: CupSummary) {
        val world = c.comp == Comp.WORLD_CUP
        for (t in c.teams) {
            val n = nationMap.getOrPut(t) { NationRecord(t) }
            n.editions++
            c.records[t]?.let { r ->
                n.won += r[0]; n.drawn += r[1]; n.lost += r[2]; n.boardsFor += r[3]; n.boardsAgainst += r[4]
            }
            val place = c.place[t] ?: NONE
            if (world) n.worldBest = minOf(n.worldBest, place) else n.continentalBest = minOf(n.continentalBest, place)
        }
        for (p in c.podiums) p.winner?.let { w ->
            val n = nationMap.getOrPut(w) { NationRecord(w) }
            if (world) n.worldTitles++ else n.continentalTitles++
        }
        val winners = c.podiums.mapNotNull { it.winner }.toSet()
        val lines = c.lines.associateBy { it.key }
        for ((team, squad) in c.squads) for (k in squad) {
            val cap = capMap.getOrPut(k) { CapRecord(k, team).also { it.first = c.season } }
            cap.code = team
            cap.caps++
            cap.last = c.season
            if (team in winners) cap.titles++
            lines[k]?.let { l ->
                cap.won += l.won; cap.drawn += l.drawn; cap.lost += l.lost
                cap.games += l.games; cap.points += l.points; cap.best = maxOf(cap.best, l.best)
            }
        }
        editionList += Edition(c.season, world, c.host, c.teams.size, c.podiums, c.mvp, c.scorer)
        while (editionList.size > MAX_EDITIONS) editionList.removeAt(0)
    }

    /** Nations by titles (world cups first), then best finishes and match record. */
    fun ranking(): List<NationRecord> = nationMap.values.sortedWith(
        compareByDescending<NationRecord> { it.worldTitles }.thenByDescending { it.continentalTitles }
            .thenBy { it.worldBest }.thenByDescending { it.won }.thenBy { it.code },
    )

    /** The most capped players (national careers), most caps first. */
    fun mostCapped(n: Int = 10, code: String? = null): List<CapRecord> = capMap.values.filter { code == null || it.code == code }
        .sortedWith(compareByDescending<CapRecord> { it.caps }.thenByDescending { it.won }.thenBy { it.key }).take(n)

    fun encode(): String = buildString {
        append(HEADER).append('\n')
        append("closed ").append(closed).append('\n')
        for (n in nationMap.values) append(n.encode()).append('\n')
        for (c in capMap.values) append(c.encode()).append('\n')
        for (e in editionList) append(e.encode()).append('\n')
    }

    companion object {
        const val FILE = "nations.txt"
        const val HEADER = "nations 1"
        const val NONE = 9

        /** Careers kept whoever they are: the rest go once their player has left the league. */
        const val KEEP_CAPS = 3
        const val MAX_EDITIONS = 100

        /** Reads nations.txt; throws when it is not one (the caller starts afresh); bad lines are skipped. */
        fun decode(text: String): NationBook {
            val lines = text.lines()
            require(lines.firstOrNull()?.trim() == HEADER) { "not a nations file" }
            val b = NationBook()
            for (line in lines.drop(1)) runCatching {
                val f = line.trim().split(' ')
                when (f[0]) {
                    "closed" -> b.closed = f[1].toInt()
                    "nat" -> {
                        require(f.size >= 12)
                        val n = NationRecord(f[1])
                        n.editions = f[2].toInt(); n.won = f[3].toInt(); n.drawn = f[4].toInt(); n.lost = f[5].toInt()
                        n.boardsFor = f[6].toInt(); n.boardsAgainst = f[7].toInt(); n.worldTitles = f[8].toInt(); n.continentalTitles = f[9].toInt()
                        n.worldBest = f[10].toInt(); n.continentalBest = f[11].toInt()
                        b.nationMap[n.code] = n
                    }
                    "cap" -> {
                        require(f.size >= 13)
                        val c = CapRecord(f[1], f[2])
                        c.caps = f[3].toInt(); c.won = f[4].toInt(); c.drawn = f[5].toInt(); c.lost = f[6].toInt()
                        c.games = f[7].toInt(); c.points = f[8].toInt(); c.best = f[9].toInt(); c.first = f[10].toInt(); c.last = f[11].toInt()
                        c.titles = f[12].toInt()
                        b.capMap[c.key] = c
                    }
                    "ed" -> {
                        require(f.size >= 7)
                        fun key(s: String) = s.takeIf { it != "-" }
                        val podiums = f.drop(7).map { p ->
                            val x = p.split(':')
                            require(x.size == 4)
                            CupSummary.Podium(key(x[0])?.let { Confed.valueOf(it) }, key(x[1]), key(x[2]), key(x[3])?.split(',').orEmpty())
                        }
                        b.editionList += Edition(f[1].toInt(), f[2] == "N", key(f[3]), f[4].toInt(), podiums, key(f[5]), key(f[6]))
                    }
                }
            }
            return b
        }

        /** "3승 1무 2패". */
        fun record(won: Int, drawn: Int, lost: Int): String = String.format(Locale.ROOT, "%d승 %d무 %d패", won, drawn, lost)
    }
}

package yacht.engine.league

import java.util.TreeMap

/**
 * A close's top 10 of the club rankings (`rk`, design ENG 1b.9), kept for the news, the rank
 * moves and the encyclopedia: by [value] and by [results] (club ids), and the record fee of each
 * of the best-ranked leagues ([leagueRecords]: league id → fee).
 */
data class RankLine(val season: Int, val value: List<String>, val results: List<String>, val leagueRecords: Map<String, Int>)

/**
 * The club world's money part (design ENG 1.2-1.5, 1b.1-1b.5): each club's owner (`co`), main
 * sponsor (`cp`), stadium (`cd`), last accounts (`ac`), money history (`ah`, newest first) and the
 * rankings' top 10 of each close (`rk`). The season logic (income, expenses, trouble, fans, sponsors,
 * owners, rankings) is P3's, in this file and Sponsors.kt / ClubRankings.kt.
 */
class ClubEconomyBook {
    internal val owners = TreeMap<String, Owner>(ClubBook.ID_ORDER)
    internal val sponsors = TreeMap<String, Sponsor>(ClubBook.ID_ORDER)
    internal val stadiums = TreeMap<String, Stadium>(ClubBook.ID_ORDER)
    internal val accounts = TreeMap<String, Accounts>(ClubBook.ID_ORDER)
    internal val years = TreeMap<String, List<ClubYear>>(ClubBook.ID_ORDER)
    internal val ranks = TreeMap<Int, RankLine>()

    companion object {
        private val CO = LineParts.of<ClubWorld>("co", { w, out ->
            for ((id, o) in w.economy.owners) out.append("co $id ${o.type.code} ${o.ambition} ${o.patience} ${o.since} ${o.paid} ${Field.esc(o.name)}\n")
        }) { w, f ->
            val type = OwnerType.of(f[2].single()) ?: return@of false
            w.economy.owners[f[1]] = Owner(type, Field.unesc(f[7]), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt())
            true
        }

        private val CP = LineParts.of<ClubWorld>("cp", { w, out ->
            for ((id, s) in w.economy.sponsors) out.append("cp $id ${Field.esc(s.name)} ${s.sector} ${s.perSeason} ${s.from} ${s.until} ${LineParts.bit(s.record)}\n")
        }) { w, f ->
            w.economy.sponsors[f[1]] = Sponsor(Field.unesc(f[2]), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7] == "1")
            true
        }

        private val CD = LineParts.of<ClubWorld>("cd", { w, out ->
            for ((id, s) in w.economy.stadiums) {
                val p = s.project
                out.append("cd $id ${Field.esc(s.name)} ${s.capacity} ${p?.kind ?: '-'} ${p?.target ?: 0} ${p?.opens ?: 0} ${p?.cost ?: 0} ${p?.paid ?: 0} ${LineParts.opt(s.naming)}\n")
            }
        }) { w, f ->
            val project = f[4].single().takeIf { it != '-' }?.let { StadiumProject(it, f[5].toInt(), f[6].toInt(), f[7].toInt(), f[8].toInt()) }
            w.economy.stadiums[f[1]] = Stadium(Field.unesc(f[2]), f[3].toInt(), project, Field.opt(f[9]))
            true
        }

        private val AC = LineParts.of<ClubWorld>("ac", { w, out ->
            for ((id, a) in w.economy.accounts) {
                out.append("ac $id ${a.season} ${a.tv} ${a.prize} ${a.intl} ${a.sponsor} ${a.matchday} ${a.owner} ${a.solidarity} ${a.sales} ")
                out.append("${a.wages} ${a.coach} ${a.upkeep} ${a.building} ${a.buys} ${a.balance}\n")
            }
        }) { w, f ->
            val x = f.subList(2, 17).map { it.toInt() }
            w.economy.accounts[f[1]] = Accounts(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8], x[9], x[10], x[11], x[12], x[13], x[14])
            true
        }

        private val AH = LineParts.of<ClubWorld>("ah", { w, out ->
            for ((id, ys) in w.economy.years) {
                if (ys.isEmpty()) continue
                out.append("ah $id ").append(ys.joinToString(";") { "${it.season}:${it.balance}:${it.income}:${it.fans}:${it.place}" }).append('\n')
            }
        }) { w, f ->
            w.economy.years[f[1]] = LineParts.items(f[2], ';').map { e ->
                val p = e.split(':')
                ClubYear(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt())
            }
            true
        }

        private val RK = LineParts.of<ClubWorld>("rk", { w, out ->
            for (r in w.economy.ranks.values) {
                out.append("rk ${r.season} ${LineParts.list(r.value) { it }} ${LineParts.list(r.results) { it }} ")
                out.append(LineParts.list(r.leagueRecords.entries) { "${Field.esc(it.key)}:${it.value}" }).append('\n')
            }
        }) { w, f ->
            val records = LinkedHashMap<String, Int>()
            for (e in LineParts.items(f[4])) records[Field.unesc(e.substringBefore(':'))] = e.substringAfter(':').toInt()
            val season = f[1].toInt()
            w.economy.ranks[season] = RankLine(season, LineParts.items(f[2]), LineParts.items(f[3]), records)
            true
        }

        /** `co cp cd ac ah rk`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(CO, CP, CD, AC, AH, RK)
    }
}

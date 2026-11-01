package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random
import java.util.TreeMap
import java.util.TreeSet

/** A rival club (design ENG 1b.6): [kind] 'N' national classic, 'R' regional derby, 'H' history, 'T' transfer rivalry, since season [since]. */
data class Rival(val club: String, val kind: Char, val since: Int)

/**
 * A club legend (클럽 레전드, ENG 1b.7): its [score], seasons and titles at the club, whether it played
 * only there; made one when it left the club after [season] (0: not known), wearing [number] there
 * (0: not known).
 */
data class ClubLegend(val key: String, val score: Int, val seasons: Int, val titles: Int, val oneClub: Boolean, val season: Int = 0, val number: Int = 0)

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
 * pairs' all-time series (`dr`), legends (`lg`), retired numbers (`rn`) and records (`cr`, with the
 * season its captain took the armband and the last close folded into its history). Kept by club
 * id, also for a dissolved club (its legends and records stay beside its `gone` line) until its
 * id is given to a new club ([ClubHistory.purge]). The logic is [ClubHistory] (legends, numbers,
 * records, captains, terms, loans), [Derbies] (rivals, the season-series fold) and [Academy].
 */
class ClubHistoryBook {
    internal val rivals = TreeMap<String, List<Rival>>(ClubBook.ID_ORDER)

    /** By [pairKey]. */
    internal val derbies = TreeMap<String, DerbyRecord>()
    internal val legends = TreeMap<String, List<ClubLegend>>(ClubBook.ID_ORDER)
    internal val retired = TreeMap<String, List<RetiredNumber>>(ClubBook.ID_ORDER)
    internal val records = TreeMap<String, List<ClubRecord>>(ClubBook.ID_ORDER)

    /** Club → the first season its captain (the `cl` line's) wears the armband. */
    internal val captainSince = TreeMap<String, Int>(ClubBook.ID_ORDER)

    /** Club → the last season whose close was folded into its history (apps, records, departures): the history step's guard. */
    internal val folded = TreeMap<String, Int>(ClubBook.ID_ORDER)

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

        /** `lg <club> <key:score:seasons:titles:oneClub:season:number,...>` (an older line has no season and number: read as 0). */
        private val LG = LineParts.of<ClubWorld>("lg", { w, out ->
            for ((id, ls) in w.history.legends) {
                if (ls.isEmpty()) continue
                out.append("lg $id ").append(ls.joinToString(",") { "${Field.esc(it.key)}:${it.score}:${it.seasons}:${it.titles}:${LineParts.bit(it.oneClub)}:${it.season}:${it.number}" }).append('\n')
            }
        }) { w, f ->
            w.history.legends[f[1]] = LineParts.items(f[2]).map { e ->
                val p = e.split(':')
                ClubLegend(Field.unesc(p[0]), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4] == "1", p.getOrNull(5)?.toIntOrNull() ?: 0, p.getOrNull(6)?.toIntOrNull() ?: 0)
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

        /** `cr <club> <records|-> [<captainSince> <folded>]`: the last two only when either is set (an older line has neither). */
        private val CR = LineParts.of<ClubWorld>("cr", { w, out ->
            val h = w.history
            val ids = TreeSet(ClubBook.ID_ORDER).apply {
                for ((id, rs) in h.records) if (rs.isNotEmpty()) add(id)
                addAll(h.captainSince.keys)
                addAll(h.folded.keys)
            }
            for (id in ids) {
                out.append("cr $id ").append(LineParts.list(h.records[id].orEmpty()) { "${it.kind.code}:${LineParts.opt(it.key)}:${it.value}:${it.season}" })
                val since = h.captainSince[id]
                val folded = h.folded[id]
                if (since != null || folded != null) out.append(' ').append(since ?: 0).append(' ').append(folded ?: 0)
                out.append('\n')
            }
        }) { w, f ->
            val h = w.history
            val rs = LineParts.items(f[2]).mapNotNull { e ->
                val p = e.split(':')
                ClubRecordKind.of(p[0].single())?.let { ClubRecord(it, Field.opt(p[1]), p[2].toInt(), p[3].toInt()) }
            }
            if (rs.isNotEmpty()) h.records[f[1]] = rs
            f.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }?.let { h.captainSince[f[1]] = it }
            f.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 }?.let { h.folded[f[1]] = it }
            true
        }

        /** `rv dr lg rn cr`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(RV, DR, LG, RN, CR)
    }
}

/**
 * What the history step of a club off-season did after [season] (design ENG 1b.7-1b.8,
 * [ClubHistory.close]), rebuilt from the book whenever asked ([ClubHistory.report]): the legends made
 * then (club, legend; best score first), the numbers retired (club, number) and the new captains of
 * the next season (club, member; club id order).
 */
data class HistoryReport(
    val season: Int,
    val legends: List<Pair<String, ClubLegend>> = emptyList(),
    val retired: List<Pair<String, RetiredNumber>> = emptyList(),
    val captains: List<Pair<String, String>> = emptyList(),
) {
    /**
     * The news of ENG 11.2 (comp [Comp.CLUB]): `one_club_man` [ai, club] (seasons, retired number or
     * "-") ≤ 1, the best one-club legend who played [ClubHistory.ONE_CLUB_SEASONS] seasons or more;
     * `club_legend` [ai, club] ≤ 1, the best other new legend; `captain` [ai, club] ≤ 2, new captains
     * of the clubs in [newsClubs] (the clubs of the 4 best-ranked leagues, best first: the order
     * picks them; [ClubHistory.newsClubs]).
     */
    fun events(newsClubs: List<String> = emptyList()): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        fun event(kind: String, keys: List<String>, vararg args: String) {
            out += LeagueEvent(kind, season, keys, args.toList(), Comp.CLUB)
        }
        val one = legends.firstOrNull { it.second.oneClub && it.second.seasons >= ClubHistory.ONE_CLUB_SEASONS }
        one?.let { (club, l) ->
            val no = retired.firstOrNull { it.first == club && it.second.key == l.key }?.second?.number
            event("one_club_man", listOf(l.key, club), l.seasons.toString(), no?.toString() ?: "-")
        }
        legends.firstOrNull { it != one }?.let { (club, l) -> event("club_legend", listOf(l.key, club)) }
        val rank = newsClubs.withIndex().associate { it.value to it.index }
        for ((club, key) in captains.filter { it.first in rank }.sortedBy { rank.getValue(it.first) }.take(ClubHistory.NEWS_CAPTAINS)) event("captain", listOf(key, club))
        return out
    }
}

/**
 * What became of a member leaving its club ([ClubHistory.leave]): the [legend] it was made (null:
 * none), the number retired for it, and whether it was the club's [captain] (a captain moving to
 * another club makes a transfer rivalry, [Derbies.recompute]'s links).
 */
data class Departure(val key: String, val club: String, val legend: ClubLegend?, val retired: RetiredNumber?, val captain: Boolean)

/**
 * Club history and squad stories (design ENG 1b.7, 1b.8) as functions of a book with a club world:
 * legends and retired numbers when members leave, the four club records, apps folded at each close,
 * captains, shirt numbers and terms for members joining, loans' bookkeeping, and the history a club
 * world starts with. Every function does nothing (empty, null, false, 0) without a club world,
 * bumps [ClubBook.version] when it changes what the screens show, and asked again for the same
 * season changes nothing more.
 */
object ClubHistory {
    /** Legends kept per club (best score first), numbers retired per club. */
    const val LEGENDS = 5
    const val RETIRED_NUMBERS = 3

    /** A legend: at least [LEGEND_SEASONS] seasons at the club and a title there, [LEGEND_APPS] apps or [LEGEND_CAPTAIN] seasons as captain. */
    const val LEGEND_SEASONS = 6
    const val LEGEND_APPS = 400
    const val LEGEND_CAPTAIN = 3

    /** A number is retired for a legend of [RETIRE_SEASONS] seasons at the club scoring [RETIRE_SCORE] ([RETIRE_ONE_CLUB_SCORE] for a one-club legend). */
    const val RETIRE_SEASONS = 10
    const val RETIRE_SCORE = 120
    const val RETIRE_ONE_CLUB_SCORE = 100

    /** 원클럽맨: retiring after this many seasons, all at one club. */
    const val ONE_CLUB_SEASONS = 10

    /** Captain news a season ([HistoryReport.events]). */
    const val NEWS_CAPTAINS = 2

    /** The numbers a member rated in its nation's best [STAR_RANK] asks for first, in a hashed order. */
    val STAR_NUMBERS = listOf(7, 10, 9, 11, 8, 23)
    const val STAR_RANK = 10

    /** Everyone else's numbers: [FIRST_NUMBER]..[USUAL_LAST] in a hashed order, then up to [LAST_NUMBER] in order. */
    const val FIRST_NUMBER = 2
    const val USUAL_LAST = 30
    const val LAST_NUMBER = 99

    private const val NUMBER_SALT = 0x5E7AL

    // ---- legends ------------------------------------------------------------------------------------

    /** The legend score (ENG 1b.7): `10 × seasons + 30 × major titles + 15 × other titles + 20 × one-club + apps / 20`, all at the club. */
    fun score(seasons: Int, majors: Int, others: Int, oneClub: Boolean, apps: Int): Int =
        10 * seasons + 30 * majors + 15 * others + (if (oneClub) 20 else 0) + maxOf(0, apps) / 20

    /** Whether a member with these numbers at a club becomes its legend (ENG 1b.7). */
    fun eligible(seasons: Int, titles: Int, apps: Int, captainSeasons: Int): Boolean =
        seasons >= LEGEND_SEASONS && (titles > 0 || apps >= LEGEND_APPS || captainSeasons >= LEGEND_CAPTAIN)

    /** Whether [legend]'s number is retired (ENG 1b.7). */
    fun retires(legend: ClubLegend): Boolean =
        legend.seasons >= RETIRE_SEASONS && legend.score >= (if (legend.oneClub) RETIRE_ONE_CLUB_SCORE else RETIRE_SCORE)

    /** Seasons at club [club] in [lines] (the archive's season lines, [Archive.LINE_SEASONS] kept). */
    fun seasonsAt(lines: List<SeasonLine>, club: String): Int = lines.count { it.club == club }

    /** Whether every season of [lines] with a club was at [club], at least [min] of them (원클럽맨; seasons before clubs existed do not count). */
    fun oneClub(lines: List<SeasonLine>, club: String, min: Int = ONE_CLUB_SEASONS): Boolean {
        val clubbed = lines.filter { it.club != null }
        return clubbed.size >= min && clubbed.all { it.club == club }
    }

    /**
     * The stint at club [club] that [lines] end with, up to [season]: its first season and its
     * league matches (the newest lines back while they are at the club); null when the newest line
     * up to [season] is not at the club.
     */
    fun stint(lines: List<SeasonLine>, club: String, season: Int): Pair<Int, Int>? {
        var since: Int? = null
        var apps = 0
        for (l in lines.filter { it.season <= season }.sortedByDescending { it.season }) {
            if (l.club != club) break
            since = l.season
            apps += l.wins + l.draws + l.losses
        }
        return since?.let { it to apps }
    }

    /**
     * [key]'s titles (major, other) won while at club [club]: its title honours ([HonourBook.isTitle])
     * of the seasons whose line in [lines] has the club; [major] says which league titles counted as
     * major (`ClubInput.major`).
     */
    fun titlesAt(key: String, club: String, lines: List<SeasonLine>, honours: List<Honour>, major: (Int, String) -> Boolean = { _, _ -> true }): Pair<Int, Int> {
        val there = lines.filter { it.club == club }.mapTo(HashSet()) { it.season }
        var majors = 0
        var others = 0
        for (h in honours) {
            if (h.key != key || h.season !in there || !HonourBook.isTitle(h.kind)) continue
            if (HonourBook.isMajor(h, major)) majors++ else others++
        }
        return majors to others
    }

    /** Seasons [key] has worn club [club]'s armband by the end of [season] (0 when it is not that club's captain). */
    fun captainSeasons(book: ClubBook, key: String, season: Int, club: String? = book.clubOf(key)): Int {
        val w = book.world ?: return 0
        if (club == null || w.clubs[club]?.captain != key) return 0
        val since = w.history.captainSince[club] ?: return 0
        return maxOf(0, season - since + 1)
    }

    /**
     * Member [key] leaves club [club] after [season] (it retires when [retiring], else it moves):
     * made a legend when [eligible] and among the club's [LEGENDS] best scores (one entry per
     * member, the newer replacing the older), its number retired when it retires as a legend that
     * [retires] and the club has fewer than [RETIRED_NUMBERS] (never twice; a legend moving on
     * keeps its number), the armband freed when it was the
     * captain. Its seasons and titles at the club come from [lines] (its archive lines) and
     * [honours], its apps and number from its terms: call it while the member still has them (the
     * off-season's [close] does for those who retire; the windows for every member they move). Null
     * without a club world.
     */
    fun leave(
        book: ClubBook, key: String, club: String, season: Int, lines: List<SeasonLine>, honours: List<Honour>, retiring: Boolean,
        major: (Int, String) -> Boolean = { _, _ -> true },
    ): Departure? {
        val w = book.world ?: return null
        val h = w.history
        val terms = w.terms[key]
        val seasons = seasonsAt(lines, club)
        val (majors, others) = titlesAt(key, club, lines, honours, major)
        val apps = terms?.apps ?: 0
        val captain = w.clubs[club]?.captain == key
        val captained = if (captain) captainSeasons(book, key, season, club) else 0
        val one = retiring && oneClub(lines, club)
        var legend: ClubLegend? = null
        var retired: RetiredNumber? = null
        val made = ClubLegend(key, score(seasons, majors, others, one, apps), seasons, majors + others, one, season, terms?.number ?: 0)
        // A member back for a second spell keeps the better of its two legend entries.
        val before = h.legends[club].orEmpty().firstOrNull { it.key == key }
        if (eligible(seasons, majors + others, apps, captained) && (before == null || before.score <= made.score)) {
            val kept = (h.legends[club].orEmpty().filter { it.key != key } + made)
                .sortedWith(compareByDescending<ClubLegend> { it.score }.thenBy { it.season }.thenBy { it.key })
                .take(LEGENDS)
            if (made in kept) {
                legend = made
                h.legends[club] = kept
                val numbers = h.retired[club].orEmpty()
                if (retiring && retires(made) && made.number > 0 && numbers.size < RETIRED_NUMBERS && numbers.none { it.number == made.number || it.key == key }) {
                    retired = RetiredNumber(made.number, key, season)
                    h.retired[club] = numbers + retired
                }
            }
        }
        if (captain) {
            w.clubs[club]?.captain = null
            h.captainSince.remove(club)
        }
        if (legend != null || captain) book.touch()
        return Departure(key, club, legend, retired, captain)
    }

    // ---- the off-season's history step --------------------------------------------------------------

    /**
     * The history step of the off-season after [closing] (design ENG 1b.10 step 5), once per club and
     * season ([ClubHistoryBook.folded]): the season's league matches folded into every member's apps
     * at its club (`played`, members of the club now), the rival pairs' season series
     * ([Derbies.fold]), the club records of apps and titles, every member not in the next season's
     * [ClubInput.members] retiring ([leave]), then the captains of clubs without one ([captains], from
     * the next season). Call it **before** the departures take the retired members off the squads.
     * [lines] are the archive's season lines (`Archive.lines`). Its news: [HistoryReport.events].
     */
    fun close(book: ClubBook, closing: SeasonPlan, input: ClubInput, lines: (String) -> List<SeasonLine>): HistoryReport {
        val w = book.world ?: return HistoryReport(closing.number)
        val s = closing.number
        val h = w.history
        Derbies.fold(book, closing)
        val alive = input.members.toHashSet()
        val byKey = input.honours.groupBy { it.key }
        var changed = false
        for (c in book.clubs) {
            if ((h.folded[c.id] ?: 0) >= s) continue
            for (k in c.squad) {
                val played = closing.league.standings[k]?.played ?: 0
                val t = w.terms[k] ?: continue
                if (played > 0) w.terms[k] = t.copy(apps = t.apps + played)
            }
            records(book, c.id, s, lines, byKey)
            for (k in c.squad) if (k !in alive) leave(book, k, c.id, s, lines(k), byKey[k].orEmpty(), retiring = true, major = input.major)
            h.folded[c.id] = s
            changed = true
        }
        captains(book, s + 1, input.rating) { it in alive }
        if (changed) book.touch()
        return report(book, s)
    }

    /** The history step's report of the off-season after [season], rebuilt from the book (its legends and numbers of [season], the captains from the next). */
    fun report(book: ClubBook, season: Int): HistoryReport {
        val w = book.world ?: return HistoryReport(season)
        val h = w.history
        val legends = h.legends.flatMap { (club, ls) -> ls.filter { it.season == season }.map { club to it } }
            .sortedWith(compareByDescending<Pair<String, ClubLegend>> { it.second.score }.thenBy { it.second.key })
        val retired = h.retired.flatMap { (club, ns) -> ns.filter { it.season == season }.map { club to it } }
        val captains = book.clubs.mapNotNull { c ->
            val key = w.clubs[c.id]?.captain ?: return@mapNotNull null
            if (h.captainSince[c.id] == season + 1) c.id to key else null
        }
        return HistoryReport(season, legends, retired, captains)
    }

    /**
     * The club records of apps and titles of club [club] after [season] (ENG 1b.7): the most apps at
     * the club among its members' terms, the most titles won there by a member (its [lines] and
     * honours [byKey]); a record is beaten only by more (the older holder keeps a tie).
     */
    private fun records(book: ClubBook, club: String, season: Int, lines: (String) -> List<SeasonLine>, byKey: Map<String, List<Honour>>) {
        val w = book.world ?: return
        for (k in book.members(club)) {
            w.terms[k]?.let { record(book, club, ClubRecordKind.APPS, k, it.apps, season) }
            val (majors, others) = titlesAt(k, club, lines(k), byKey[k].orEmpty())
            record(book, club, ClubRecordKind.TITLES, k, majors + others, season)
        }
    }

    /** Club [club]'s record of [kind] becomes [value] by [key] in [season] when it beats the one kept; true when it did. */
    internal fun record(book: ClubBook, club: String, kind: ClubRecordKind, key: String?, value: Int, season: Int): Boolean {
        val h = book.world?.history ?: return false
        if (value <= 0) return false
        val rs = h.records[club].orEmpty()
        val old = rs.firstOrNull { it.kind == kind }
        if (old != null && old.value >= value) return false
        h.records[club] = (rs.filter { it.kind != kind } + ClubRecord(kind, key, value, season)).sortedBy { it.kind.ordinal }
        book.touch()
        return true
    }

    /**
     * A fee paid for [key] by club [buyer] to club [seller] after [season] (the windows call it
     * with every fee): the buyer's 최고 영입액 and the seller's 최고 판매액 when beaten (ENG 1b.7).
     */
    fun fee(book: ClubBook, key: String, buyer: String?, seller: String?, fee: Int, season: Int) {
        buyer?.let { record(book, it, ClubRecordKind.SIGNING, key, fee, season) }
        seller?.let { record(book, it, ClubRecordKind.SALE, key, fee, season) }
    }

    // ---- captains -----------------------------------------------------------------------------------

    /**
     * Captains (주장, ENG 1b.8): every club whose captain is no member of it any more (or that has
     * none) gives the armband, from season [from], to the member with the most seasons there (the
     * earliest `since` of its terms), then the most apps, the higher [rating], the key; only members
     * that [stay] (the off-season's history step passes those of the next season). Its clubs and new
     * captains, by club id.
     */
    fun captains(book: ClubBook, from: Int, rating: (String) -> Double, stay: (String) -> Boolean = { true }): List<Pair<String, String>> {
        val w = book.world ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (c in book.clubs) {
            val st = w.clubs[c.id] ?: continue
            if (st.captain != null && st.captain in c.squad && stay(st.captain!!)) continue
            val best = c.squad.filter(stay).minWithOrNull(
                compareBy<String> { w.terms[it]?.since ?: from }.thenByDescending { w.terms[it]?.apps ?: 0 }
                    .thenByDescending { rating(it).let { r -> if (r.isNaN()) Double.NEGATIVE_INFINITY else r } }.thenBy { it },
            )
            if (best == st.captain) continue
            st.captain = best
            if (best == null) w.history.captainSince.remove(c.id) else {
                w.history.captainSince[c.id] = from
                out += c.id to best
            }
            book.touch()
        }
        return out
    }

    // ---- shirt numbers and terms ----------------------------------------------------------------------

    /** Each nation's [STAR_RANK] best club members by [rating] (the shirt numbers' stars). */
    fun stars(book: ClubBook, rating: (String) -> Double): Set<String> {
        val out = HashSet<String>()
        val order = ClubBook.ratingOrder(rating)
        for ((_, cs) in book.clubs.groupBy { it.nation }) out += cs.flatMap { it.squad }.sortedWith(order).take(STAR_RANK)
        return out
    }

    /**
     * The shirt number club [club] gives [key] (ENG 1b.7): the first free one of its preference
     * list, a hashed order of [STAR_NUMBERS] for a [star], then [FIRST_NUMBER]..[USUAL_LAST] in a
     * hashed order, then the rest up to [LAST_NUMBER]; taken numbers (the other members' terms) and
     * the club's retired numbers are skipped. 0 when none is free.
     */
    fun number(book: ClubBook, club: String, key: String, star: Boolean): Int {
        val w = book.world ?: return 0
        val taken = HashSet<Int>()
        for (k in book.members(club)) if (k != key) w.terms[k]?.number?.let { taken += it }
        for (r in w.history.retired[club].orEmpty()) taken += r.number
        return preferences(book.seed, key, star).firstOrNull { it !in taken } ?: 0
    }

    /** [key]'s shirt numbers in the order it asks for them. */
    internal fun preferences(seed: Long, key: String, star: Boolean): Sequence<Int> {
        val h = key.hashCode().toLong()
        val stars = if (star) STAR_NUMBERS.shuffled(Random(DiceStream.mix(seed, NUMBER_SALT, 1, h))) else emptyList()
        val usual = (FIRST_NUMBER..USUAL_LAST).shuffled(Random(DiceStream.mix(seed, NUMBER_SALT, 2, h)))
        return (stars.asSequence() + usual.asSequence() + (USUAL_LAST + 1..LAST_NUMBER).asSequence()).distinct()
    }

    /**
     * Member [key] joins club [club] (or rejoins it) at season [since]: new terms to season [until]
     * at [wage] with a release [clause] (0: none), on loan from [loanFrom], a shirt number there
     * ([number]), no apps yet; the academy it came out of and that season are kept from its old
     * terms unless [academy] names the club whose academy it graduates from now (then the
     * graduation season is [since]). The windows, the draft and the academy call it for every
     * member they place; the book must already have it in the club. Null without a club world.
     */
    fun join(
        book: ClubBook, key: String, club: String, since: Int, until: Int, wage: Int, star: Boolean,
        clause: Int = 0, loanFrom: String? = null, academy: String? = null,
    ): Terms? {
        val w = book.world ?: return null
        val old = w.terms[key]
        val t = Terms(
            until, wage, clause, number(book, club, key, star), since, 0, loanFrom,
            academy ?: old?.academy, if (academy != null) since else old?.gradSeason ?: 0,
        )
        w.terms[key] = t
        book.touch()
        return t
    }

    /**
     * Terms for every club member without any (design ENG 1b.10 step 13: the newcomers of moves that
     * did not make terms themselves), from season [since] to [until] at [wage], numbered at its
     * club; and a member whose number another member of its club wears too (an older terms' number
     * from elsewhere) gets a free one, the longer-serving keeping it. Members renumbered or termed.
     */
    fun newcomers(book: ClubBook, since: Int, stars: Set<String>, until: (String) -> Int, wage: (String, String) -> Int): Int {
        val w = book.world ?: return 0
        var n = 0
        for (c in book.clubs) {
            val worn = HashMap<Int, String>()
            val order = c.squad.sortedWith(compareBy<String> { w.terms[it]?.since ?: Int.MAX_VALUE }.thenBy { it })
            for (k in order) {
                val t = w.terms[k]
                when {
                    t == null -> {
                        join(book, k, c.id, since, until(k), wage(k, c.id), k in stars)
                        n++
                    }
                    t.number <= 0 || worn.containsKey(t.number) -> {
                        w.terms[k] = t.copy(number = number(book, c.id, k, k in stars).takeIf { it !in worn } ?: free(book, c.id, worn.keys))
                        n++
                    }
                }
                w.terms[k]?.number?.let { worn[it] = k }
            }
        }
        if (n > 0) book.touch()
        return n
    }

    /** The lowest number of [FIRST_NUMBER]..[LAST_NUMBER] not in [taken] nor retired at [club]. */
    private fun free(book: ClubBook, club: String, taken: Set<Int>): Int {
        val retired = book.world?.history?.retired?.get(club).orEmpty().mapTo(HashSet()) { it.number }
        return (FIRST_NUMBER..LAST_NUMBER).firstOrNull { it !in taken && it !in retired } ?: 0
    }

    // ---- loans (임대, ENG 1b.8: the market decides who, this keeps the books) -------------------------

    /**
     * Member [key] goes on loan from its club to club [to] for the next season ([season] the closing
     * one): it plays for [to] (its membership), its terms keep their contract, wage and clause with
     * `loanFrom` set, and it wears a free number there. False (nothing changed) without a club world,
     * a club or terms, or when it is on loan already.
     */
    fun loanOut(book: ClubBook, key: String, to: String, star: Boolean): Boolean {
        val w = book.world ?: return false
        val from = book.clubOf(key) ?: return false
        val t = w.terms[key] ?: return false
        if (from == to || t.loanFrom != null || !book.place(key, to)) return false
        w.terms[key] = t.copy(loanFrom = from, number = number(book, to, key, star))
        book.touch()
        return true
    }

    /**
     * Member [key], on loan, goes back to the club that loaned it out (임대 복귀): its terms lose
     * `loanFrom` and it gets a number there again (most often its own, the first of its list). The
     * club it went back to; null when it is not on loan or that club is gone (it stays where it is).
     */
    fun loanBack(book: ClubBook, key: String, star: Boolean): String? {
        val w = book.world ?: return null
        val t = w.terms[key] ?: return null
        val home = t.loanFrom ?: return null
        if (book.club(home) == null || !book.place(key, home)) return null
        w.terms[key] = t.copy(loanFrom = null, number = number(book, home, key, star))
        book.touch()
        return home
    }

    /** The loan club keeps member [key] for good (완전 이적): its terms lose `loanFrom` (the fee and the new contract are the market's). */
    fun loanBuy(book: ClubBook, key: String): Boolean {
        val w = book.world ?: return false
        val t = w.terms[key]?.takeIf { it.loanFrom != null } ?: return false
        w.terms[key] = t.copy(loanFrom = null)
        book.touch()
        return true
    }

    /** The members club [club] has out on loan (at other clubs with `loanFrom` = [club]), by key. */
    fun loanedOut(book: ClubBook, club: String): List<String> {
        val w = book.world ?: return emptyList()
        return book.clubs.filter { it.id != club }.flatMap { c -> c.squad.filter { w.terms[it]?.loanFrom == club } }.sorted()
    }

    // ---- reused ids and the start ------------------------------------------------------------------

    /**
     * Club id [id] is given to a new club (ENG 1b.7): every per-id history line of the old club goes
     * (`rv`, `dr`, `lg`, `rn`, `cr`, the captaincy and the fold guard), and other clubs' rivalries
     * with it.
     */
    fun purge(book: ClubBook, id: String) {
        val w = book.world ?: return
        val h = w.history
        h.rivals.remove(id)
        for ((club, rs) in h.rivals.entries.toList()) if (rs.any { it.club == id }) h.rivals[club] = rs.filter { it.club != id }
        h.derbies.entries.removeIf { it.value.a == id || it.value.b == id }
        h.legends.remove(id)
        h.retired.remove(id)
        h.records.remove(id)
        h.captainSince.remove(id)
        h.folded.remove(id)
        book.touch()
    }

    /**
     * The history a club world starts with (design ENG 8.2, its migration and founding) in season
     * [season] (the one in play): every member without terms gets them, its stint at its club from
     * its archive [lines] (`since` its first season there, `apps` its league matches there; [season]
     * and 0 without any), a contract to [until], [wage], a shirt number (the longest-serving members
     * choose first) and no clause; the club records of apps and titles the archive lines reach (every
     * player in [keys], those who left included); legends among the players of [keys] who left a club
     * after at least [LEGEND_SEASONS] seasons there (by the same rule; numbers are not known, so none
     * is retired); a captain for every club (from [season]). Members given terms.
     */
    fun start(
        book: ClubBook, season: Int, keys: Collection<String>, lines: (String) -> List<SeasonLine>, honours: List<Honour>,
        rating: (String) -> Double, until: (String) -> Int, wage: (String) -> Int, major: (Int, String) -> Boolean = { _, _ -> true },
    ): Int {
        val w = book.world ?: return 0
        val h = w.history
        val stars = stars(book, rating)
        val byKey = honours.groupBy { it.key }
        val order = ClubBook.ratingOrder(rating)
        var n = 0
        for (c in book.clubs) {
            val stints = c.squad.associateWith { stint(lines(it), c.id, season) }
            val by = compareBy<String> { stints[it]?.first ?: season }.then(order)
            for (k in c.squad.sortedWith(by)) {
                if (w.terms[k] != null) continue
                val (since, apps) = stints[k] ?: (season to 0)
                w.terms[k] = Terms(until(k), wage(k), 0, number(book, c.id, k, k in stars), since, apps, null, null, 0)
                n++
            }
        }
        // Records and legends from every player the archive keeps lines of.
        val clubs = book.clubs.mapTo(HashSet()) { it.id }
        for (k in keys.distinct().sorted()) {
            val ls = lines(k)
            val own = book.clubOf(k)
            for (club in ls.mapNotNullTo(TreeSet(ClubBook.ID_ORDER)) { it.club }) {
                if (club !in clubs) continue
                val at = ls.filter { it.club == club }
                val apps = if (club == own) w.terms[k]?.apps ?: 0 else at.sumOf { it.wins + it.draws + it.losses }
                record(book, club, ClubRecordKind.APPS, k, apps, at.last().season)
                val (majors, others) = titlesAt(k, club, ls, byKey[k].orEmpty(), major)
                record(book, club, ClubRecordKind.TITLES, k, majors + others, at.last().season)
                if (club == own || !eligible(at.size, majors + others, apps, 0)) continue
                val one = book.clubOf(k) == null && oneClub(ls, club)
                val made = ClubLegend(k, score(at.size, majors, others, one, apps), at.size, majors + others, one, at.last().season, 0)
                h.legends[club] = (h.legends[club].orEmpty().filter { it.key != k } + made)
                    .sortedWith(compareByDescending<ClubLegend> { it.score }.thenBy { it.season }.thenBy { it.key }).take(LEGENDS)
            }
        }
        captains(book, season, rating)
        book.touch()
        return n
    }

    /** The clubs whose news the history step tells ([HistoryReport.events]): those of [plan]'s 4 best-ranked leagues ([Derbies.topLeagues]), league by league, by id. */
    fun newsClubs(book: ClubBook, plan: SeasonPlan, rating: (String) -> Double): List<String> {
        val season = plan.league
        val top = Derbies.topLeagues(plan, rating).take(Derbies.NEWS_LEAGUES)
        val leagueOf = HashMap<String, String>()
        for (c in book.clubs) c.squad.firstNotNullOfOrNull { season.leagueOf(it)?.id }?.let { leagueOf[c.id] = it }
        return top.flatMap { lg -> book.clubs.filter { leagueOf[it.id] == lg }.map { it.id } }
    }
}

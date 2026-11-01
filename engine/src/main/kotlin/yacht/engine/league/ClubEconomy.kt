package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random
import java.util.TreeMap
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A close's top 10 of the club rankings (`rk`, design ENG 1b.9), kept for the news, the rank
 * moves and the encyclopedia: by [value] and by [results] (club ids), and the record fee of each
 * of the best-ranked leagues ([leagueRecords]: league id → fee).
 */
data class RankLine(val season: Int, val value: List<String>, val results: List<String>, val leagueRecords: Map<String, Int>)

/**
 * The club world's money part (design ENG 1.2-1.5, 1b.1-1b.5): each club's owner (`co`), main
 * sponsor (`cp`), stadium (`cd`), last accounts (`ac`), money history (`ah`, newest first) and the
 * rankings' top 10 of each close (`rk`). The season logic is [ClubEconomy]'s (and [Sponsors]',
 * ClubRankings.kt's).
 *
 * Two facts ride on these lines beyond P1's types: each season's result points ([results];
 * [ClubRanks]) as a sixth field `:points×10` of its `ah` season, and for the seasons older than the
 * money history keeps (3 below 500 members, the ranking counts 5) as a last token `season:points,…`
 * of the `ah` line; and whether the club-life step of the last accounts' season ran ([lived], an
 * 18th field `1` on the `ac` line), the step's guard. Older readers stop before them.
 */
class ClubEconomyBook {
    internal val owners = TreeMap<String, Owner>(ClubBook.ID_ORDER)
    internal val sponsors = TreeMap<String, Sponsor>(ClubBook.ID_ORDER)
    internal val stadiums = TreeMap<String, Stadium>(ClubBook.ID_ORDER)
    internal val accounts = TreeMap<String, Accounts>(ClubBook.ID_ORDER)
    internal val years = TreeMap<String, List<ClubYear>>(ClubBook.ID_ORDER)
    internal val ranks = TreeMap<Int, RankLine>()

    /** Club → season → result points × 10 ([ClubRanks.resultPoints]), the newest [ClubRanks.SEASONS] seasons. */
    internal val results = TreeMap<String, TreeMap<Int, Int>>(ClubBook.ID_ORDER)

    /** Clubs whose club-life step ([ClubEconomy.life]) ran for the season of their last [accounts]. */
    internal val lived = HashSet<String>()

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
                out.append("${a.wages} ${a.coach} ${a.upkeep} ${a.building} ${a.buys} ${a.balance}")
                if (id in w.economy.lived) out.append(" 1")
                out.append('\n')
            }
        }) { w, f ->
            val x = f.subList(2, 17).map { it.toInt() }
            w.economy.accounts[f[1]] = Accounts(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8], x[9], x[10], x[11], x[12], x[13], x[14])
            if (f.getOrNull(17) == "1") w.economy.lived += f[1] else w.economy.lived -= f[1]
            true
        }

        private val AH = LineParts.of<ClubWorld>("ah", { w, out ->
            for ((id, ys) in w.economy.years) {
                if (ys.isEmpty()) continue
                val points = w.economy.results[id]
                out.append("ah $id ").append(
                    ys.joinToString(";") { y ->
                        "${y.season}:${y.balance}:${y.income}:${y.fans}:${y.place}" + (points?.get(y.season)?.let { ":$it" } ?: "")
                    },
                )
                val seasons = ys.mapTo(HashSet()) { it.season }
                val older = points?.entries?.filter { it.key !in seasons }.orEmpty()
                if (older.isNotEmpty()) out.append(' ').append(older.reversed().joinToString(",") { "${it.key}:${it.value}" })
                out.append('\n')
            }
        }) { w, f ->
            val points = TreeMap<Int, Int>()
            w.economy.years[f[1]] = LineParts.items(f[2], ';').map { e ->
                val p = e.split(':')
                p.getOrNull(5)?.toIntOrNull()?.let { points[p[0].toInt()] = it }
                ClubYear(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt())
            }
            for (e in LineParts.items(f.getOrNull(3) ?: "-")) points[e.substringBefore(':').toInt()] = e.substringAfter(':').toInt()
            if (points.isEmpty()) w.economy.results.remove(f[1]) else w.economy.results[f[1]] = points
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

/**
 * An item of a club's accounts (design ENG 1.2, the 구단 tab's labels): [income] items add to the
 * balance, the others take from it. [OWNER] is signed (a 투자 펀드's dividend is negative: "배당").
 */
enum class MoneyItem(val income: Boolean, val label: String) {
    TV(true, "중계권"),
    PRIZE(true, "상금"),
    INTL(true, "대회 상금"),
    SPONSOR(true, "스폰서"),
    MATCHDAY(true, "입장 수입"),
    OWNER(true, "구단주 지원"),
    SOLIDARITY(true, "연대 기금"),
    SALES(true, "선수 판매"),
    WAGES(false, "선수 연봉"),
    COACH(false, "감독 연봉"),
    UPKEEP(false, "운영비"),
    BUILDING(false, "공사비"),
    BUYS(false, "선수 영입"),
    ;

    /** This item's amount in [a] (signed for [OWNER] and [SOLIDARITY]). */
    fun of(a: Accounts): Int = when (this) {
        TV -> a.tv
        PRIZE -> a.prize
        INTL -> a.intl
        SPONSOR -> a.sponsor
        MATCHDAY -> a.matchday
        OWNER -> a.owner
        SOLIDARITY -> a.solidarity
        SALES -> a.sales
        WAGES -> a.wages
        COACH -> a.coach
        UPKEEP -> a.upkeep
        BUILDING -> a.building
        BUYS -> a.buys
    }

    companion object {
        /** The label of a negative [OWNER] amount (a fund's dividend). */
        const val DIVIDEND = "배당"
    }
}

/** Where a club played a season: its club table's [league], its [place] there (1 = champion) and the table's size. */
data class ClubSeat(val league: String, val place: Int, val clubs: Int) {
    /** The place score of ENG 1b.1: `((C + 1) / 2 − place) / C`, from just under +0.5 for the champion to just above −0.5 for the last. */
    val perf: Double get() = if (clubs <= 0) 0.0 else ((clubs + 1) / 2.0 - place) / clubs
}

/**
 * The accounts of [season] (the `club_accounts` news, design ENG 11.2): every club of the book whose
 * accounts of that season it holds, by id, with each one's operating income ([incomes],
 * [ClubEconomy.operating] with its owner; the recurring income when not given). Rebuilt from the book
 * whenever asked ([ClubEconomy.report]).
 */
data class AccountsReport(
    val season: Int,
    val clubs: List<Pair<String, Accounts>>,
    val incomes: Map<String, Int> = clubs.associate { it.first to ClubEconomy.recurring(it.second) },
) {
    /** The club with the largest operating income and that income. */
    val richest: Pair<String, Int>? get() = clubs.map { it.first to (incomes[it.first] ?: ClubEconomy.recurring(it.second)) }
        .maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenComparing({ it.first }, ClubBook.ID_ORDER.reversed()))

    /** The club with the largest loss of the season (a negative net) and the loss, positive; null when no club lost money. */
    val loss: Pair<String, Int>? get() = clubs.filter { it.second.net < 0 }.map { it.first to -it.second.net }
        .maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenComparing({ it.first }, ClubBook.ID_ORDER.reversed()))

    /** Clubs that ended the season in the red. */
    val red: Int get() = clubs.count { it.second.balance < 0 }

    /** `club_accounts` [richest, biggest loss] (income, loss, red count); none without accounts. */
    fun events(): List<LeagueEvent> {
        val top = richest ?: return emptyList()
        val worst = loss
        return listOf(
            LeagueEvent("club_accounts", season, listOfNotNull(top.first, worst?.first), listOf(top.second.toString(), (worst?.second ?: 0).toString(), red.toString()), Comp.CLUB),
        )
    }
}

/** A main sponsor signed or lost: [sponsor] the new deal (signed) or the one that left; [renewed] a renewal; [mid] a walk-out before the deal's end. */
data class SponsorChange(val club: String, val sponsor: Sponsor, val renewed: Boolean = false, val mid: Boolean = false)

/** A new owner of [club]: [owner] after [previous]; [reason] W the old 재벌 walked out, Q a quiet takeover, A an arc's takeover, R a rescue from administration. */
data class OwnerChange(val club: String, val owner: Owner, val previous: Owner, val reason: Char)

/**
 * A stadium step of [club]: [kind] E an expansion started, N a new stadium started, O a project
 * opened ([opened]: the kind of that project, E or N); [from] the capacity before; [stadium] after.
 */
data class StadiumChange(val club: String, val kind: Char, val from: Int, val stadium: Stadium, val opened: Char? = null)

/** A facility level change of [club]: [kind] 'T' training or 'A' academy, to [level]; [up] an upgrade, else a level lost after two seasons in trouble. */
data class FacilityChange(val club: String, val kind: Char, val level: Int, val up: Boolean = true)

/**
 * What the club-life step of an off-season did ([ClubEconomy.life], design ENG 1b.10 step 3), for
 * the news of ENG 11.2 and the off-season's digest (the caller keeps what it tells). Club lists are
 * biggest fan base first.
 */
data class LifeReport(
    val season: Int,
    /** Mood below 25 after the close (`fan_protest`), and at least 90 with a club-table title (`fan_party`). */
    val protests: List<String> = emptyList(),
    val parties: List<String> = emptyList(),
    val signed: List<SponsorChange> = emptyList(),
    val left: List<SponsorChange> = emptyList(),
    val owners: List<OwnerChange> = emptyList(),
    val stadiums: List<StadiumChange> = emptyList(),
    val facilities: List<FacilityChange> = emptyList(),
    /** Clubs in the red, newly in 재정 위기 (the first season of it), newly in 법정관리. */
    val red: List<String> = emptyList(),
    val trouble: List<String> = emptyList(),
    val administration: List<String> = emptyList(),
    /** Clubs to dissolve for money (two off-seasons in administration without a rescue): the off-season dissolves them ('F'). */
    val dissolve: List<String> = emptyList(),
) {
    /**
     * The news of ENG 11.2 with their caps (comp [Comp.CLUB], biggest clubs first): `club_trouble`
     * [club] (balance) ≤ 2, `administration` [club] ≤ 1, `rescue` [club] (owner), `takeover` [club]
     * (owner, type code), `owner_exit` [club] (owner), `sponsor_deal` [club] (sponsor, value, seasons,
     * record 0/1) ≤ 1, `sponsor_exit` [club] (sponsor, mid 0/1) ≤ 1, `stadium_project` [club] (from, to,
     * seasons, cost, kind E 증축 / N 신구장) ≤ 1, `stadium_open` [club] (name, capacity, kind E an
     * expansion finished / N a new stadium) ≤ 1, `fan_protest` [club, coach] (mood)
     * ≤ 2, `fan_party` [club] ≤ 1, and one `club_world_digest` [≤ 6 clubs] (sponsor deals, building
     * projects, facility investments). [balance], [mood] and [coach] read the book as it is now.
     * `club_folded` is the dissolving step's.
     */
    fun events(balance: (String) -> Int, mood: (String) -> Int, coach: (String) -> String?): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        fun event(kind: String, keys: List<String>, vararg args: String) {
            out += LeagueEvent(kind, season, keys, args.toList(), Comp.CLUB)
        }
        for (c in trouble.filter { it !in administration }.take(2)) event("club_trouble", listOf(c), balance(c).toString())
        for (c in administration.take(1)) event("administration", listOf(c))
        for (o in owners) when (o.reason) {
            'R' -> event("rescue", listOf(o.club), o.owner.name)
            'W' -> {
                event("owner_exit", listOf(o.club), o.previous.name)
            }
            else -> event("takeover", listOf(o.club), o.owner.name, o.owner.type.code.toString())
        }
        signed.sortedWith(compareByDescending<SponsorChange> { it.sponsor.record }.thenByDescending { it.sponsor.perSeason }).firstOrNull()?.let { d ->
            event("sponsor_deal", listOf(d.club), d.sponsor.name, d.sponsor.perSeason.toString(), (d.sponsor.until - d.sponsor.from + 1).toString(), LineParts.bit(d.sponsor.record))
        }
        left.firstOrNull()?.let { x -> event("sponsor_exit", listOf(x.club), x.sponsor.name, LineParts.bit(x.mid)) }
        stadiums.firstOrNull { it.kind != 'O' }?.let { p ->
            val project = p.stadium.project
            if (project != null) {
                event("stadium_project", listOf(p.club), p.from.toString(), project.target.toString(), (project.opens - season - 1).toString(), project.cost.toString(), project.kind.toString())
            }
        }
        stadiums.firstOrNull { it.kind == 'O' }?.let { o ->
            event("stadium_open", listOf(o.club), o.stadium.name, o.stadium.capacity.toString(), (o.opened ?: ClubEconomy.EXPAND).toString())
        }
        for (c in protests.take(2)) event("fan_protest", listOfNotNull(c, coach(c)), mood(c).toString())
        for (c in parties.take(1)) event("fan_party", listOf(c))
        val ns = signed.size
        val nb = stadiums.count { it.kind != 'O' }
        val nf = facilities.count { it.up }
        if (ns + nb + nf > 0) {
            val clubs = (signed.map { it.club } + stadiums.filter { it.kind != 'O' }.map { it.club } + facilities.filter { it.up }.map { it.club }).distinct().take(6)
            event("club_world_digest", clubs, ns.toString(), nb.toString(), nf.toString())
        }
        return out
    }
}

/**
 * What the club-life step reads besides the book and the season (design ENG 1b.1, 1b.5): the board's
 * [target] place of each club (ENG 2.2: the coaches' board, P5; null: the middle of its table, a
 * neutral season), the mood points of the season's other events ([ClubEconomy.moodEvents] without the
 * title, which the step adds itself: stars sold, a record signing, a member gone to a rival, the derby
 * balance, a sacking) and the members' market values (club values at takeovers and rescues).
 */
class LifeInput(
    val target: (String) -> Int? = { null },
    val moodEvents: (String) -> Int = { 0 },
    val value: (String) -> Int = { 0 },
)

/** What the club world's money started from ([ClubEconomy.migrate], [ClubEconomy.found]): each member's wage, each club's estimated recurring income, each league's ω. */
data class StartMoney(val wages: Map<String, Int>, val income: Map<String, Int>, val omega: Map<String, Double>)

/**
 * The club world's money and club life (design ENG 1.1-1.5, 1b.1, 1b.2, 1b.5, 8.2), as pure functions
 * of the book: the formulas, the steps of the club off-season that the pipeline calls (the season's
 * accounts, [accounts]; the club's life, [life]; the ledger of the windows, [charge] and [transfer]),
 * and the money a club world starts with ([migrate], [found], [newClub]). All amounts are [Money]
 * units. Every step changes only a book that has a club world, bumps [ClubBook.version] when it
 * changes something the screens show, and asked again for the same season changes nothing.
 */
object ClubEconomy {
    // ---- wages (ENG 1.1) ----------------------------------------------------------------------------

    /** A wage is capped at 30% of the club's income. */
    const val WAGE_CAP = 0.30

    /** The least wage: `0.3억 × √λ`. */
    const val MIN_WAGE = 0.3

    /** A member is underpaid below 75% of what its value asks now. */
    const val UNDERPAID = 0.75

    /** A club in 재정 위기 renews at 80% of the new wage. */
    const val RENEWAL_IN_TROUBLE = 0.8

    /** A 재벌 or 국부 펀드 owner of ambition ≥ 4 pays 15% more. */
    const val RICH_OWNER = 1.15

    /**
     * The club factor on the wages it offers: `1 + (2/3) × clamp(balance / income − 0.5, 0, 1.5)`
     * (1.0 up to a balance of half the income, 2.0 from twice the income), × [RICH_OWNER] for a 재벌 or
     * 국부 펀드 owner of ambition ≥ 4; 1.0 without an income.
     */
    fun clubFactor(balance: Int, income: Int, owner: Owner): Double {
        val ratio = if (income > 0) balance.toDouble() / income else 0.0
        val f = 1 + 2.0 / 3 * (ratio - 0.5).coerceIn(0.0, 1.5)
        return if (owner.ambition >= 4 && (owner.type == OwnerType.TYCOON || owner.type == OwnerType.STATE)) f * RICH_OWNER else f
    }

    /** The least wage in a league of [lambda]: `0.3억 × √λ`. */
    fun minWage(lambda: Double): Int = Money.of(MIN_WAGE * sqrt(maxOf(0.0, lambda)))

    /**
     * The wage of a contract signed or renewed for a member of [value] at a club of [balance] and
     * last season's [income] (the league's average for a new club) owned by [owner], in a league of
     * [omega] and [lambda]: `clamp(baseWage × ω × clubFactor, minWage, 0.30 × income)` (no cap
     * without an income; the least wage wins over the cap).
     */
    fun wage(value: Int, omega: Double, lambda: Double, balance: Int, income: Int, owner: Owner): Int {
        val x = Math.round(Valuation.baseWage(value) * omega * clubFactor(balance, income, owner)).toInt()
        val capped = if (income > 0) minOf(x, Math.round(WAGE_CAP * income).toInt()) else x
        return maxOf(minWage(lambda), capped)
    }

    /** A renewal's wage: [wage] (the new contract's), at [RENEWAL_IN_TROUBLE] for a club in 재정 위기. */
    fun renewal(wage: Int, inTrouble: Boolean): Int = if (inTrouble) Math.round(wage * RENEWAL_IN_TROUBLE).toInt() else wage

    /** Whether a member on [wage] is underpaid for its [value] now in a league of [omega]: `wage < 0.75 × baseWage × ω`. */
    fun underpaid(wage: Int, value: Int, omega: Double): Boolean = wage < UNDERPAID * Valuation.baseWage(value) * omega

    /** A coach's contract wage (ENG 1.2, 2.1): `(0.3 + 0.04 × reputation) × ω` 억. */
    fun coachWage(reputation: Int, omega: Double): Int = Money.of((0.3 + 0.04 * reputation.coerceIn(0, 100)) * omega)

    // ---- income and expenses (ENG 1.2) --------------------------------------------------------------

    /** Prize money by place in 억 per λ: 3, 2, 1.5, 1, 0.6, then [PRIZE_REST]. */
    val PRIZES = doubleArrayOf(3.0, 2.0, 1.5, 1.0, 0.6)
    const val PRIZE_REST = 0.4

    /** International prize money per coefficient point: 0.6억 in the 챔피언스 리그, 0.25억 in a 대륙 챔피언스. */
    const val CL_POINT = 0.6
    const val CCL_POINT = 0.25

    /** Honours keyed by a club id: their money goes to that club. */
    val CLUB_HONOURS = setOf("CLUB", "CLUB2", "CLUBCUP", "CLUBCUP2")

    /** The 8 best-ranked leagues' clubs pay the solidarity share of their TV money. */
    const val SOLIDARITY_PAYERS = 8

    /**
     * TV money (중계권) of place [place] (1 = champion) in a club table of [clubs]: `λ × (3 + 1.5 ×
     * (C − p) / (C − 1))` 억 (`λ × 4.5` alone), × [factor] (a crisis). Flat on purpose: the champion
     * gets 1.5 × the last.
     */
    fun tv(lambda: Double, place: Int, clubs: Int, factor: Double = 1.0): Int {
        val share = if (clubs <= 1) 4.5 else 3 + 1.5 * (clubs - place.coerceIn(1, clubs)) / (clubs - 1).toDouble()
        return Money.of(lambda * share * factor)
    }

    /** Prize money (상금) of place [place]: `λ × [3, 2, 1.5, 1, 0.6, 0.4…][p − 1]` 억. */
    fun prize(lambda: Double, place: Int): Int = Money.of(lambda * (PRIZES.getOrNull(place - 1) ?: PRIZE_REST))

    /** International prize money of [points] coefficient points: [CL_POINT] 억 a point in the 챔피언스 리그 (× [clPrizeFactor] after a CL reform), [CCL_POINT] in a 대륙 챔피언스. */
    fun intl(points: Int, champions: Boolean, clPrizeFactor: Double = 1.0): Int =
        Money.of(maxOf(0, points) * if (champions) CL_POINT * clPrizeFactor else CCL_POINT)

    /** The prize money of an honour of [kind] (λ: its league's): CUP 4 / CUP2 2, a league title 2 × λ, LMVP 0.5, SUPER 1, CLUBCUP 8 / CLUBCUP2 4 억; 0 for others. */
    fun honour(kind: String, lambda: Double): Int = when (HonourKind.base(kind)) {
        "CUP" -> 40
        "CUP2" -> 20
        "L0" -> Money.of(2 * lambda)
        "LMVP" -> 5
        "SUPER" -> 10
        "CLUBCUP" -> 80
        "CLUBCUP2" -> 40
        else -> 0
    }

    /** Home match days of a league of [rounds] rounds: `round(R / 2)` (21 at 42). */
    fun matchDays(rounds: Int): Int = Math.round(maxOf(0, rounds) / 2.0).toInt()

    /** The fans who would come (ENG 1b.2): `F × 0.35 × (0.6 + 0.8 × M / 100) × λ^0.25`. */
    fun demand(fans: Int, mood: Int, lambda: Double): Int =
        Math.round(maxOf(0, fans) * 0.35 * (0.6 + 0.8 * mood.coerceIn(0, 100) / 100.0) * maxOf(0.0, lambda).pow(0.25)).toInt()

    /** The seats in use: the capacity, 10% fewer while an expansion is being built (공사 중). */
    fun seats(stadium: Stadium): Int = if (stadium.project?.kind == EXPAND) stadium.capacity * 9 / 10 else stadium.capacity

    /** The average attendance (평균 관중): `min(seats, demand)`. */
    fun attendance(fans: Int, mood: Int, lambda: Double, stadium: Stadium): Int = minOf(seats(stadium), demand(fans, mood, lambda))

    /** The ticket price in 만 원: `0.25 × √λ × owner factor` (팬 소유 0.8, 재벌 1.1, 투자 펀드 1.25, else 1.0). */
    fun price(lambda: Double, owner: OwnerType): Double = 0.25 * sqrt(maxOf(0.0, lambda)) * when (owner) {
        OwnerType.FANS -> 0.8
        OwnerType.TYCOON -> 1.1
        OwnerType.FUND -> 1.25
        else -> 1.0
    }

    /** Match-day income (입장 수입): `days × attendance × price / 10,000` 억. */
    fun matchday(attendance: Int, price: Double, days: Int): Int = Money.of(maxOf(0, days) * maxOf(0, attendance) * price / 10_000.0)

    /**
     * The owner's money of a season (signed): 재벌 `+0.05 × ambition × wages`, 지역 기업 `+0.05 ×
     * wages`, 국부 펀드 `+0.40 × wages`, 투자 펀드 `−0.05 × income` (a dividend) when [balance] is above
     * half the [income], 팬 소유 nothing; × [factor] (a crisis halves it).
     */
    fun ownerMoney(owner: Owner, wages: Int, income: Int, balance: Int, factor: Double = 1.0): Int = Math.round(
        factor * when (owner.type) {
            OwnerType.TYCOON -> 0.05 * owner.ambition * wages
            OwnerType.LOCAL -> 0.05 * wages
            OwnerType.STATE -> 0.40 * wages
            OwnerType.FUND -> if (balance > 0.5 * income) -0.05 * income else 0.0
            OwnerType.FANS -> 0.0
        },
    ).toInt()

    /** Upkeep (운영비): `0.25 × (training + academy) × √λ` 억 for the facilities and `0.5 × capacity / 10,000` 억 for the stadium. */
    fun upkeep(training: Int, academy: Int, capacity: Int, lambda: Double): Int =
        Money.of(0.25 * (training + academy) * sqrt(maxOf(0.0, lambda)) + 0.5 * maxOf(0, capacity) / 10_000.0)

    /**
     * The solidarity fund (연대 기금): the [payers] (clubs of the 8 best-ranked leagues) pay
     * [percent]% of their TV money ([tv]: club → TV money), and the pool is shared equally by the other
     * clubs of [tv] (the remainder one unit each to the first by id), so it sums to zero; nobody pays
     * when nobody would receive. Club → signed amount (every club of [tv]).
     */
    fun solidarity(tv: Map<String, Int>, payers: Set<String>, percent: Int): Map<String, Int> {
        val out = TreeMap<String, Int>(ClubBook.ID_ORDER)
        for (id in tv.keys) out[id] = 0
        val receivers = out.keys.filter { it !in payers }
        if (percent <= 0 || receivers.isEmpty()) return out
        var pool = 0
        for ((id, x) in tv) if (id in payers) {
            val paid = Math.round(percent / 100.0 * x).toInt()
            out[id] = -paid
            pool += paid
        }
        val share = pool / receivers.size
        val rest = pool % receivers.size
        for ((i, id) in receivers.withIndex()) out[id] = share + if (i < rest) 1 else 0
        return out
    }

    /** Recurring income (the spending cap's, ENG 1.5): TV, prize, international, sponsor, match-day and solidarity money; no sales, no owner money. */
    fun recurring(a: Accounts): Int = a.tv + a.prize + a.intl + a.sponsor + a.matchday + a.solidarity

    /**
     * Operating income: [recurring] and the owner's money paid in, at most what [owner]'s type pays a
     * season ([ownerMoney] on the accounts' wages), and no sales: what the health, the trouble rules,
     * the wage caps and the richest clubs measure against. A rescue's write-off and an investor's
     * injection are booked as owner money too ([MoneyItem.OWNER]) but are one-offs, so they do not
     * count: a club just rescued from 법정관리 is no richer for it.
     */
    fun operating(a: Accounts, owner: Owner): Int =
        recurring(a) + minOf(maxOf(0, a.owner), maxOf(0, ownerMoney(owner, a.wages, recurring(a), 0)))

    /** Seasons of money history (`ah`) a book of [members] keeps: 5, 3 below 500 members. */
    fun yearsKept(members: Int): Int = if (members < 500) 3 else 5

    // ---- club value and health (ENG 1.3) ------------------------------------------------------------

    /**
     * Club value (클럽 가치): the squad's value + 1.5억 per 1,000 seats + (training + academy) × 2.5억 +
     * 5억 per 10,000 fans + the balance (a debt counts against it).
     */
    fun clubValue(squad: Int, capacity: Int, training: Int, academy: Int, fans: Int, balance: Int): Int {
        val x = squad.toLong() + Math.round(maxOf(0, capacity) * 0.015) + 25L * (training + academy) + Math.round(maxOf(0, fans) * 0.005) + balance
        return x.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** The health grades' words. */
    val HEALTH: Map<Char, String> = linkedMapOf('A' to "아주 튼튼해요", 'B' to "튼튼해요", 'C' to "보통이에요", 'D' to "불안해요", 'E' to "위기예요")

    /**
     * Financial health (재정 건전성) A..E: A a balance of at least the [income] and [wages] at most 60%
     * of it, B at least 0.3 × income and wages at most 70%, C not in the red, D down to −0.5 × income,
     * E below that or in administration ([admin]). Without an income: C, or D in the red.
     */
    fun health(balance: Int, income: Int, wages: Int, admin: Int): Char = when {
        admin > 0 -> 'E'
        income <= 0 -> if (balance >= 0) 'C' else 'D'
        balance >= income && wages <= 0.60 * income -> 'A'
        balance >= 0.3 * income && wages <= 0.70 * income -> 'B'
        balance >= 0 -> 'C'
        balance >= -0.5 * income -> 'D'
        else -> 'E'
    }

    // ---- the windows' money (ENG 1.5) ---------------------------------------------------------------

    /** FFP's levels (`ffp` lever): 0 off, 1 soft (the default), 2 strict. */
    const val FFP_OFF = 0
    const val FFP_STRICT = 2

    /** Strict FFP: the transfer budget × 0.7, and a club in the red at a close loses 5% of its club-table points. */
    const val FFP_STRICT_BUDGET = 0.7
    const val FFP_DEDUCTION = 5

    /** The spending cap with an investor's injection this season: 1.5 × the recurring income. */
    const val INJECTED_CAP = 1.5

    /**
     * The transfer budget (이적 예산): `min(max(0, balance − 0.25 × wages) × (0.4 + 0.1 × ambition) +
     * 0.8 × [sales] this window + [injection], [cap] − [paid] this window)`, never below 0. [balance]
     * leaves out this window's [sales] and the [injection], which count as written (the fees already
     * paid are out of it).
     */
    fun budget(balance: Int, wages: Int, ambition: Int, sales: Int = 0, injection: Int = 0, cap: Int = Int.MAX_VALUE, paid: Int = 0): Int {
        val own = maxOf(0.0, balance - 0.25 * wages) * (0.4 + 0.1 * ambition)
        val tb = Math.round(own + 0.8 * maxOf(0, sales) + maxOf(0, injection))
        return minOf(tb, cap.toLong() - paid).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * The spending cap (a window's fees): the mean of the newest 3 recurring incomes ([incomes], newest
     * first; `ah`), × [INJECTED_CAP] with an investor's injection this season; no cap without history.
     */
    fun spendCap(incomes: List<Int>, injected: Boolean = false): Int {
        val xs = incomes.take(3)
        if (xs.isEmpty()) return Int.MAX_VALUE
        return Math.round(maxOf(0.0, xs.average()) * if (injected) INJECTED_CAP else 1.0).toInt()
    }

    /** What FFP off lets a 재벌 or 투자 펀드 owner of ambition ≥ 4 borrow beyond the budget: 0.5 × income; 0 otherwise. */
    fun borrowing(owner: Owner, income: Int, ffp: Int): Int =
        if (ffp == FFP_OFF && owner.ambition >= 4 && (owner.type == OwnerType.TYCOON || owner.type == OwnerType.FUND)) Math.round(0.5 * maxOf(0, income)).toInt() else 0

    /** The share of its income a club's wage bill may reach after a signing under FFP level [ffp]: none off, 0.85 soft, 0.70 strict. */
    fun wageShare(ffp: Int): Double? = when {
        ffp <= FFP_OFF -> null
        ffp >= FFP_STRICT -> 0.70
        else -> 0.85
    }

    // ---- trouble (ENG 1.4) --------------------------------------------------------------------------

    /** 재정 위기 below −0.5 × income, 법정관리 below −1.0 × income (at a close). */
    const val TROUBLE = -0.5
    const val ADMINISTRATION = -1.0

    /** Forced sales stop at −0.25 × income; at most 2; the buyer pays at least 0.8 × value. */
    const val FORCED_TARGET = -0.25
    const val FORCED_MAX = 2
    const val FORCED_PRICE = 0.8

    /** 법정관리's club-table deduction (%), the rescue's chance an off-season, a dissolution's spacing (seasons, world-wide). */
    const val ADMIN_DEDUCTION = 10
    const val RESCUE = 0.7
    const val DISSOLVE_EVERY = 5

    // ---- fans and mood (ENG 1b.1) -------------------------------------------------------------------

    /** Fans' bounds. */
    const val MIN_FANS = 300
    const val MAX_FANS = 500_000

    /** A club-table title's mood points; protests below 25, parties from 90 with a title. */
    const val TITLE_MOOD = 25
    const val PROTEST = 25
    const val PARTY = 90

    /** A city's weight on the fan base: 1.6 for a nation's first club city down to 0.7 for its tenth and later. */
    fun cityWeight(index: Int): Double = 1.6 - 0.1 * index.coerceIn(0, 9)

    /** The fan base a club's fans revert to: `8,000 × λ × cityWeight`. */
    fun fanBase(lambda: Double, cityIndex: Int): Double = 8_000.0 * lambda * cityWeight(cityIndex)

    /** The fans at a migration or a founding: `base × (1 + 0.15 × min(5, titles))`. */
    fun initialFans(base: Double, titles: Int): Int = Math.round(base * (1 + 0.15 * titles.coerceIn(0, 5))).toInt().coerceIn(MIN_FANS, MAX_FANS)

    /**
     * The fans after a close: `F × (1 + g)` clamped to [MIN_FANS]..[MAX_FANS], `g = 0.10 × perf + 0.06 ×
     * [title] + 0.02 × min(3, members' major titles) + 0.01 × min(3, stars) − 0.04 × [last] − 0.05 ×
     * ln(F / base) − 0.02 × [crisis]` ([perf]: [ClubSeat.perf], 0 when the club had no table).
     */
    fun grow(fans: Int, base: Double, perf: Double, title: Boolean, majors: Int, stars: Int, last: Boolean, crisis: Boolean): Int {
        val f = maxOf(MIN_FANS, fans).toDouble()
        val g = 0.10 * perf + (if (title) 0.06 else 0.0) + 0.02 * majors.coerceIn(0, 3) + 0.01 * stars.coerceIn(0, 3) -
            (if (last) 0.04 else 0.0) - 0.05 * ln(f / maxOf(1.0, base)) - (if (crisis) 0.02 else 0.0)
        return Math.round(f * (1 + g)).toInt().coerceIn(MIN_FANS, MAX_FANS)
    }

    /** The fans' mood (팬 만족도) after a checkpoint or a close: `0.5 × M + 0.5 × clamp(50 + 60 × gap + events, 0, 100)`. */
    fun moodAfter(mood: Int, gap: Double, events: Int): Int =
        Math.round(0.5 * mood.coerceIn(0, 100) + 0.5 * (50 + 60 * gap + events).coerceIn(0.0, 100.0)).toInt().coerceIn(0, 100)

    /** The mood gap: `(target − place) / C`, the target clamped to 1..C (positive: better than asked). */
    fun gap(target: Int, place: Int, clubs: Int): Double = if (clubs <= 0) 0.0 else (target.coerceIn(1, clubs) - place).toDouble() / clubs

    /**
     * The mood points of a season's events (ENG 1b.1): a club-table title +25, each star sold −8 (at
     * most −16), a record signing +6, each member gone to a rival −15, the season's derby balance
     * (wins − losses) × 2 within ±8, a fund's takeover −5 (−15 at a 팬 소유 club), a sacked coach +5.
     */
    fun moodEvents(
        title: Boolean = false, starsSold: Int = 0, recordSigning: Boolean = false, toRival: Int = 0,
        derby: Int = 0, fundTakeover: Boolean = false, fansOwned: Boolean = false, sacked: Boolean = false,
    ): Int = (if (title) TITLE_MOOD else 0) - minOf(16, 8 * maxOf(0, starsSold)) + (if (recordSigning) 6 else 0) - 15 * maxOf(0, toRival) +
        (2 * derby).coerceIn(-8, 8) - (if (fundTakeover) (if (fansOwned) 15 else 5) else 0) + (if (sacked) 5 else 0)

    /** Whether the arcs make this a crisis season (the fans' −0.02, a sponsor's walk-out mid-deal): their TV or sponsor factor is down. */
    fun inCrisis(arcs: ArcEffects): Boolean = arcs.tvFactor < 1.0 || arcs.sponsorFactor < 1.0

    // ---- stadium and facilities (ENG 1b.2) ----------------------------------------------------------

    /** Project kinds. */
    const val EXPAND = 'E'
    const val NEW_STADIUM = 'N'

    /** Capacities: at a migration `clamp(round(F × 0.45, 500), 3,000, 40,000)`, a new club 3,000, a new stadium at most 80,000. */
    const val MIN_SEATS = 3_000
    const val START_SEATS_MAX = 40_000
    const val MAX_SEATS = 80_000

    /** An expansion: from 92% full, +max(2,000, 25%) seats at 0.002억 a seat, half now and half at the opening 2 seasons later. */
    const val EXPAND_FILL = 0.92
    const val EXPAND_SEATS = 2_000
    const val EXPAND_SEAT_COST = 0.02
    const val EXPAND_SEASONS = 2

    /** A new stadium: from a demand of 1.6 × capacity, `min(80,000, round(1.1 × demand, 1,000))` seats at 0.003억 a seat, 40% now and 15% a season, open after 4 seasons. */
    const val NEW_DEMAND = 1.6
    const val NEW_SEAT_COST = 0.03
    const val NEW_SEASONS = 4
    const val NEW_FIRST = 0.40
    const val NEW_INSTALMENT = 0.15

    /** Stadium types of the default names ("{city} {type}"). */
    val STADIUM_TYPES = listOf("스타디움", "아레나", "파크", "그라운드")

    /** Facility levels' words (훈련 시설, 유스 아카데미). */
    val LEVELS = listOf("기초", "보통", "좋음", "최신", "세계 최고")

    /** Upgrade costs to levels 2..5: training 4 / 8 / 16 / 30억, academy 3 / 6 / 12 / 24억. */
    val TRAINING_COST = intArrayOf(40, 80, 160, 300)
    val ACADEMY_COST = intArrayOf(30, 60, 120, 240)

    /** The capacity at a migration for [fans]: `clamp(round(F × 0.45, 500), 3,000, 40,000)`. */
    fun startSeats(fans: Int): Int = (Math.round(fans * 0.45 / 500.0).toInt() * 500).coerceIn(MIN_SEATS, START_SEATS_MAX)

    /** The upgrade cost of [kind] ('T' training, 'A' academy) from [level] to the next; null at the top. */
    fun upgradeCost(kind: Char, level: Int): Int? = (if (kind == 'T') TRAINING_COST else ACADEMY_COST).getOrNull(level - 1)

    // ---- owners (ENG 1b.5) --------------------------------------------------------------------------

    /** A 재벌's walk-out a season, the quiet takeover's chance (a 지역 기업 club in the top 25% of fans; ≤ 1 a season). */
    const val WALKOUT = 0.04
    const val QUIET_TAKEOVER = 0.01

    /** Ambition and patience ranges by owner type. */
    fun ambitions(type: OwnerType): IntRange = when (type) {
        OwnerType.TYCOON -> 3..5
        OwnerType.FANS -> 1..3
        OwnerType.LOCAL -> 2..4
        OwnerType.FUND -> 2..5
        OwnerType.STATE -> 5..5
    }

    fun patiences(type: OwnerType): IntRange = when (type) {
        OwnerType.TYCOON -> 1..2
        OwnerType.FANS -> 4..5
        OwnerType.LOCAL -> 2..4
        OwnerType.FUND -> 2..3
        OwnerType.STATE -> 2..2
    }

    /** The owner type a club starts with, by the hash of its id: 지역 기업 45%, 팬 소유 25%, 재벌 15%, 투자 펀드 15%. */
    fun startOwner(seed: Long, club: Int): OwnerType = when (Math.floorMod(DiceStream.mix(seed, OWNER_SALT, club.toLong()), 100L)) {
        in 0L until 45L -> OwnerType.LOCAL
        in 45L until 70L -> OwnerType.FANS
        in 70L until 85L -> OwnerType.TYCOON
        else -> OwnerType.FUND
    }

    /**
     * A new owner of [type] for club [id] from season [since]: ambition and patience drawn in the
     * type's ranges (or given), named by type (재벌 "{name} 회장" of the club's nation, 팬 소유 "{city}
     * 팬 협동조합", 지역 기업 a company of the nation, 투자 펀드 "{prefix} 파트너스", 국부 펀드 "{prefix}
     * 인베스트먼트") unless [name] is given; [paid] the club value at the takeover.
     */
    fun owner(book: ClubBook, id: String, type: OwnerType, since: Int, rng: Random, paid: Int = 0, ambition: Int? = null, patience: Int? = null, name: String? = null): Owner {
        val a = ambition ?: ambitions(type).let { it.first + rng.nextInt(it.last - it.first + 1) }
        val p = patience ?: patiences(type).let { it.first + rng.nextInt(it.last - it.first + 1) }
        val club = book.club(id)
        val n = name ?: when (type) {
            OwnerType.TYCOON -> {
                val nation = Nations.of(club?.nation) ?: Nations.KR
                val taken = book.world?.economy?.owners?.values?.mapTo(HashSet()) { it.name.removeSuffix(" 회장") }.orEmpty()
                PlayerNames.unique(nation, if (rng.nextBoolean()) Gender.MALE else Gender.FEMALE, 60, rng, taken) + " 회장"
            }
            OwnerType.FANS -> "${club?.city?.ifEmpty { null } ?: club?.name ?: id} 팬 협동조합"
            OwnerType.LOCAL -> Sponsors.localName(club?.nation, book.seed, number(id), since)
            OwnerType.FUND -> Sponsors.fund(rng.nextInt(Sponsors.FOREIGN_PREFIXES.size))
            OwnerType.STATE -> Sponsors.state(rng.nextInt(Sponsors.FOREIGN_PREFIXES.size))
        }
        return Owner(type, n, a.coerceIn(1, 5), p.coerceIn(1, 5), since, paid)
    }

    // ---- reading the book ---------------------------------------------------------------------------

    /** Club [id]'s wage bill now: its members' contract wages (the terms; 0 without a club world). */
    fun wageBill(book: ClubBook, id: String): Int = book.members(id).sumOf { book.terms(it)?.wage ?: 0 }

    /**
     * Club [id]'s income for its health, the trouble rules and the wage cap: the operating income of its
     * last accounts with its owner now, else the newest money history's (a migrated club before its
     * first accounts), else 0.
     */
    fun income(book: ClubBook, id: String): Int {
        val e = book.world?.economy ?: return 0
        e.accounts[id]?.let { return operating(it, e.owners[id] ?: Owner.NONE) }
        return e.years[id]?.firstOrNull()?.income ?: 0
    }

    /** Club [id]'s squad value: [value] summed over its members. */
    fun squadValue(book: ClubBook, id: String, value: (String) -> Int): Int = book.members(id).sumOf { value(it).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Club [id]'s value ([clubValue]) with its members valued by [value]; null without a club world state. */
    fun clubValue(book: ClubBook, id: String, value: (String) -> Int): Int? {
        val w = book.world ?: return null
        val st = w.clubs[id] ?: return null
        val stadium = w.economy.stadiums[id] ?: Stadium.NONE
        return clubValue(squadValue(book, id, value), stadium.capacity, st.training, st.academy, st.fans, st.balance)
    }

    /** Club [id]'s health grade; null without a club world state. */
    fun health(book: ClubBook, id: String): Char? {
        val st = book.world?.clubs?.get(id) ?: return null
        return health(st.balance, income(book, id), wageBill(book, id), st.admin)
    }

    /** Club [id]'s money trouble: 0 none, 1 적자 (in the red), 2 재정 위기, 3 법정관리; null without a state. */
    fun troubleLevel(book: ClubBook, id: String): Int? {
        val st = book.world?.clubs?.get(id) ?: return null
        return when {
            st.admin > 0 -> 3
            st.trouble > 0 -> 2
            st.balance < 0 -> 1
            else -> 0
        }
    }

    /** Whether club [id] may not buy or loan in (a transfer embargo): in 재정 위기 or administration, or in the red under strict FFP. */
    fun embargo(book: ClubBook, id: String, ffp: Int = 1): Boolean {
        val st = book.world?.clubs?.get(id) ?: return false
        return st.trouble > 0 || st.admin > 0 || (ffp >= FFP_STRICT && st.balance < 0)
    }

    /** The club-table deductions of the season in play (법정관리 10%, strict FFP 5%): club → %, for [ClubTable.of]. */
    fun deductions(book: ClubBook): Map<String, Int> =
        book.world?.clubs?.values?.filter { it.deduction > 0 }?.associate { it.id to it.deduction }.orEmpty()

    /** Club [id]'s spending cap of a window ([spendCap] over its money history). */
    fun spendCap(book: ClubBook, id: String, injected: Boolean = false): Int =
        spendCap(book.world?.economy?.years?.get(id)?.map { it.income }.orEmpty(), injected)

    /**
     * Club [id]'s transfer budget (이적 예산, ENG 1.5) under FFP level [ffp], having had [sales] and
     * paid [paid] in fees this window, with an investor's [injection] this off-season: [budget] (× 0.7
     * under strict FFP, plus [borrowing] with FFP off) within the spending cap; 0 under an embargo or
     * without a club world state. The sales ([transfer]) and the injection ([life]) are in the balance
     * already, so they are taken out of it before the share: a sale of x adds 0.8 × x, not more.
     */
    fun transferBudget(book: ClubBook, id: String, ffp: Int = 1, sales: Int = 0, paid: Int = 0, injection: Int = 0): Int {
        val w = book.world ?: return 0
        val st = w.clubs[id] ?: return 0
        if (embargo(book, id, ffp)) return 0
        val owner = w.economy.owners[id] ?: Owner.NONE
        val income = income(book, id)
        val cap = spendCap(book, id, injection > 0)
        val own = budget(st.balance - maxOf(0, sales) - maxOf(0, injection), wageBill(book, id), owner.ambition, sales, injection)
        val scaled = if (ffp >= FFP_STRICT) Math.round(own * FFP_STRICT_BUDGET) else own.toLong() + borrowing(owner, income, ffp)
        return minOf(scaled, cap.toLong() - paid).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** The wage bill club [id] may still add under FFP level [ffp] (the bill after a signing within [wageShare] × income); null when FFP sets no cap. */
    fun wageRoom(book: ClubBook, id: String, ffp: Int = 1): Int? {
        val share = wageShare(ffp) ?: return null
        return Math.round(share * income(book, id)).toInt() - wageBill(book, id)
    }

    /**
     * The wage club [id] offers a member of [value] (ENG 1.1) in its league [league] of the season's
     * [economy] (λ) and the book's ω; the least wage for an unknown club.
     */
    fun wageFor(book: ClubBook, id: String, value: Int, league: String, economy: LeagueEconomy): Int {
        val w = book.world
        val st = w?.clubs?.get(id)
        val lambda = economy.lambdaOf(league)
        if (w == null || st == null) return minWage(lambda)
        return wage(value, w.omega(league), lambda, st.balance, income(book, id), w.economy.owners[id] ?: Owner.NONE)
    }

    /** The most valuable members of club [id] (by [value], then key) that a club in 재정 위기 must sell (at most 2, until its balance would reach −0.25 × income at 0.8 × value); empty when not in trouble. */
    fun forcedSales(book: ClubBook, id: String, value: (String) -> Int): List<String> {
        val st = book.world?.clubs?.get(id) ?: return emptyList()
        if (st.trouble == 0 && st.admin == 0) return emptyList()
        val target = FORCED_TARGET * income(book, id)
        var balance = st.balance.toDouble()
        val out = ArrayList<String>()
        for (k in book.members(id).sortedWith(compareByDescending<String> { value(it) }.thenBy { it })) {
            if (out.size >= FORCED_MAX || balance >= target) break
            out += k
            balance += FORCED_PRICE * value(k)
        }
        return out
    }

    /** The balance a forced sale stops at: −0.25 × the club's income. */
    fun forcedTarget(book: ClubBook, id: String): Int = Math.round(FORCED_TARGET * income(book, id)).toInt()

    /** Clubs among [clubs] whose fans protest (mood below [PROTEST]), biggest fan base first, at most 2 (the checkpoint's and the close's `fan_protest`). */
    fun protests(book: ClubBook, clubs: Collection<String>): List<String> {
        val w = book.world ?: return emptyList()
        return clubs.mapNotNull { w.clubs[it] }.filter { it.mood < PROTEST }.sortedWith(byFans()).take(2).map { it.id }
    }

    /** Club [id]'s average attendance in a league of [lambda] at its fans and mood now; 0 without a state. */
    fun attendance(book: ClubBook, id: String, lambda: Double): Int {
        val w = book.world ?: return 0
        val st = w.clubs[id] ?: return 0
        return attendance(st.fans, st.mood, lambda, w.economy.stadiums[id] ?: Stadium.NONE)
    }

    // ---- the ledger ---------------------------------------------------------------------------------

    /**
     * Books [amount] of [item] for club [id]: the balance moves (up for an income item, down for an
     * expense; [MoneyItem.OWNER] signed), and when the club's last accounts are those of [season] (the
     * off-season after [season], whose accounts are open until it ends) the item and the accounts'
     * balance with it, and the money history's balance of that season. In the winter of a season the
     * last accounts are the season before's, so only the balance moves (the winter's fees join the
     * next accounts through [accounts]' winter maps). False (nothing changed) without a state.
     */
    fun charge(book: ClubBook, id: String, item: MoneyItem, amount: Int, season: Int): Boolean {
        val w = book.world ?: return false
        val st = w.clubs[id] ?: return false
        if (amount == 0) return true
        st.balance += if (item.income) amount else -amount
        val e = w.economy
        e.accounts[id]?.takeIf { it.season == season }?.let { a ->
            e.accounts[id] = a.plus(item, amount).copy(balance = st.balance)
            e.years[id]?.let { ys -> if (ys.firstOrNull()?.season == season) e.years[id] = listOf(ys.first().copy(balance = st.balance)) + ys.drop(1) }
        }
        book.touch()
        return true
    }

    /** A fee of [fee] paid by [buyer] to [seller] (either null: abroad or none), booked as [charge]s ([MoneyItem.BUYS], [MoneyItem.SALES]). */
    fun transfer(book: ClubBook, buyer: String?, seller: String?, fee: Int, season: Int) {
        if (fee <= 0) return
        buyer?.let { charge(book, it, MoneyItem.BUYS, fee, season) }
        seller?.let { charge(book, it, MoneyItem.SALES, fee, season) }
    }

    /** The fans' mood of club [id] after a checkpoint ([moodAfter] with [gap] and [events]); false without a state. */
    fun mood(book: ClubBook, id: String, gap: Double, events: Int): Boolean {
        val st = book.world?.clubs?.get(id) ?: return false
        val m = moodAfter(st.mood, gap, events)
        if (m != st.mood) {
            st.mood = m
            book.touch()
        }
        return true
    }

    // ---- step 2: the season's accounts --------------------------------------------------------------

    /**
     * The accounts of [season] the book holds (rebuilt news): its clubs' (a club dissolved since keeps
     * its state but is left out, so the news rebuilt after the off-season's dissolutions names only
     * clubs that exist), with their operating incomes.
     */
    fun report(book: ClubBook, season: Int): AccountsReport {
        val e = book.world?.economy ?: return AccountsReport(season, emptyList())
        val clubs = e.accounts.entries.filter { it.value.season == season && book.club(it.key) != null }.map { it.key to it.value }
        return AccountsReport(season, clubs, clubs.associate { (id, a) -> id to operating(a, e.owners[id] ?: Owner.NONE) })
    }

    /**
     * The accounts of the season [closing] closed (design ENG 1.2-1.3, the club off-season's step 2),
     * once per club and season: income and expenses of every club with a club world state, from the
     * club [tables] of the close (league → table, best first, as [ClubTable.all] gives them, with the
     * season's splits and deductions), the season's honours and international points, the book as it
     * was at the close (fans, mood, stadium, owner, sponsor, facilities, members' wages: a member that
     * moved in the winter costs half at each club), the season's λ ([ClubInput.economy]), the arcs'
     * factors and the solidarity lever; the balance after it, `ac` and `ah` (with the result points of
     * the club rankings), and each league's ω pulled toward [LeagueEconomy.WAGE_SHARE] of its revenue.
     * [winterSales] and [winterBuys] are the winter window's fees per club, which moved the balances
     * then: they show in the items but not again in the balance. The window steps of the off-season
     * add their fees through [charge]. The step's guard is the season's accounts: once a club has
     * them, a repeat returns the same report and changes nothing (also after the off-season founded a
     * club, [newClub], which has none: it played no part in the season, and ω moves once).
     */
    fun accounts(
        book: ClubBook,
        closing: SeasonPlan,
        input: ClubInput,
        tables: Map<String, List<ClubStanding>>,
        winterSales: Map<String, Int> = emptyMap(),
        winterBuys: Map<String, Int> = emptyMap(),
    ): AccountsReport {
        val s = closing.number
        val w = book.world ?: return AccountsReport(s, emptyList())
        val e = w.economy
        val economy = input.economy
        val arcs = input.arcs
        val season = closing.league
        val seats = seats(tables)
        if (w.clubs.keys.any { e.accounts[it]?.season == s }) return report(book, s)
        val todo = w.clubs.keys.filter { book.club(it) != null }
        if (todo.isEmpty()) return report(book, s)
        val doing = todo.toHashSet()
        // International money and points (the club rankings' results), and the honours' prize money.
        val intl = HashMap<String, Int>()
        val points = HashMap<String, Int>()
        for (c in closing.comps) {
            val champions = c.comp == Comp.CHAMPIONS
            if (!champions && c.comp != Comp.CONT_CHAMPIONS) continue
            val i = c as? International ?: continue
            for ((k, p) in i.coefficientPoints()) {
                val club = book.clubOf(k)?.takeIf { it in doing } ?: continue
                intl.merge(club, intl(p, champions, arcs.clPrizeFactor), Int::plus)
                points.merge(club, p, Int::plus)
            }
        }
        for (h in input.honours) {
            if (h.season != s) continue
            val club = (if (HonourKind.base(h.kind) in CLUB_HONOURS) h.key else book.clubOf(h.key))?.takeIf { it in doing } ?: continue
            val lambda = economy.lambdaOf(HonourKind.leagueOf(h.kind) ?: seats[club]?.league ?: "")
            honour(h.kind, lambda).takeIf { it > 0 }?.let { intl.merge(club, it, Int::plus) }
        }
        // Wages: the members' contracts; a winter move costs half at each club.
        val moved = book.winterMoves(s).associateBy { it.key }
        val wages = HashMap<String, Int>()
        for (id in todo) {
            var sum = 0
            for (k in book.members(id)) {
                val wg = book.terms(k)?.wage ?: 0
                sum += if (moved[k]?.to == id) wg - wg / 2 else wg
            }
            wages[id] = sum
        }
        for (m in moved.values) {
            if (m.from !in doing || book.clubOf(m.key) != m.to) continue
            wages.merge(m.from, (book.terms(m.key)?.wage ?: 0) / 2, Int::plus)
        }
        val leagueOf = todo.associateWith { seats[it]?.league ?: playedIn(book, season, it) }
        val tv = todo.associateWith { id -> seats[id]?.let { tv(economy.lambdaOf(it.league), it.place, it.clubs, arcs.tvFactor) } ?: 0 }
        // The 8 best-ranked leagues of the season's ranking (λ's order), not only those with clubs here.
        val top = (economy.lambda.keys + leagueOf.values).distinct().sortedWith(compareByDescending<String> { economy.lambdaOf(it) }.thenBy { it }).take(SOLIDARITY_PAYERS).toSet()
        val solidarity = solidarity(tv, todo.filterTo(HashSet()) { leagueOf.getValue(it) in top }, input.config.solidarity)
        val keep = yearsKept(book.size)
        val revenue = HashMap<String, Long>()
        val bills = HashMap<String, Long>()
        for (id in todo) {
            val st = w.clubs.getValue(id)
            val seat = seats[id]
            val league = leagueOf.getValue(id)
            val lambda = economy.lambdaOf(league)
            val stadium = e.stadiums[id] ?: Stadium.NONE
            val owner = e.owners[id] ?: Owner.NONE
            val prize = seat?.let { prize(lambda, it.place) } ?: 0
            val sponsor = (e.sponsors[id]?.takeIf { s in it.from..it.until }?.perSeason ?: 0) + (if (stadium.naming != null) Sponsors.naming(lambda, stadium.capacity) else 0)
            val days = matchDays(season.leagues.byId(league)?.let { season.roundsOf(it.top) } ?: 0)
            val matchday = matchday(attendance(st.fans, st.mood, lambda, stadium), price(lambda, owner.type), days)
            val wg = wages[id] ?: 0
            val coach = book.coachOf(id)?.wage ?: 0
            val upkeep = upkeep(st.training, st.academy, stadium.capacity, lambda)
            val tvMoney = tv.getValue(id)
            val solid = solidarity[id] ?: 0
            val before = tvMoney + prize + (intl[id] ?: 0) + sponsor + matchday + solid
            val ownerMoney = ownerMoney(owner, wg, before, st.balance, arcs.injectionFactor)
            st.balance += before + ownerMoney - wg - coach - upkeep
            val a = Accounts(
                s, tvMoney, prize, intl[id] ?: 0, sponsor, matchday, ownerMoney, solid, winterSales[id] ?: 0,
                wg, coach, upkeep, 0, winterBuys[id] ?: 0, st.balance,
            )
            e.accounts[id] = a
            e.lived -= id
            val rec = recurring(a)
            // Seasons from s on belong to an abandoned timeline (a league that started over): they go.
            e.years[id] = (listOf(ClubYear(s, st.balance, rec, st.fans, seat?.place ?: 0)) + e.years[id].orEmpty().filter { it.season < s }).take(keep)
            // The 결과 순위's 5 seasons, also where the money history keeps 3.
            val pts = e.results.getOrPut(id) { TreeMap() }
            pts.keys.removeIf { it > s }
            pts[s] = Math.round(10 * ClubRanks.resultPoints(seat?.place ?: 0, lambda, points[id] ?: 0)).toInt()
            while (pts.size > ClubRanks.SEASONS) pts.pollFirstEntry()
            revenue.merge(league, rec.toLong(), Long::plus)
            bills.merge(league, wg.toLong(), Long::plus)
        }
        // Each league's wage index moves its wage bill toward 66% of its revenue.
        for ((league, bill) in bills) {
            val next = LeagueEconomy.nextOmega(w.omega(league), revenue[league]?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0, bill.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            w.wageIndex[league] = Math.round(next * 100).toInt()
        }
        book.touch()
        return report(book, s)
    }

    // ---- step 3: the club's life --------------------------------------------------------------------

    /**
     * The club's life after the season [closing] closed (design ENG 1b.10 step 3), once per club and
     * season, for every club whose accounts of the season were made ([accounts]) and whose life step
     * has not run: in order, the fans and their mood (the board's target from [extra]; the arcs' mood
     * shocks), sponsor renewals, walk-outs and new deals (1b.4), the owners' walk-outs, a quiet takeover
     * and the arcs' takeovers, injections and fines (1b.5, 6.7), stadium projects (progress, openings,
     * new decisions) and facility upgrades (1b.2), and the trouble rules (1.4): 재정 위기, 법정관리 and
     * its rescue, and the clubs to dissolve (returned; the off-season dissolves them). Randomness from
     * the closing plan's seed, by club. Asked again, it changes nothing and returns an empty report.
     */
    fun life(book: ClubBook, closing: SeasonPlan, input: ClubInput, tables: Map<String, List<ClubStanding>>, extra: LifeInput = LifeInput()): LifeReport {
        val s = closing.number
        val w = book.world ?: return LifeReport(s)
        val e = w.economy
        val todo = w.clubs.keys.filter { id -> e.accounts[id]?.season == s && id !in e.lived && book.club(id) != null }
        if (todo.isEmpty()) return LifeReport(s)
        val economy = input.economy
        val arcs = input.arcs
        val crisis = inCrisis(arcs)
        val seats = seats(tables)
        val season = closing.league
        val honours = input.honours.filter { it.season == s }
        val majors = HashMap<String, Int>()
        for (h in honours) if (HonourBook.isMajor(h, input.major)) book.clubOf(h.key)?.let { majors.merge(it, 1, Int::plus) }
        // Stars: the members in the world's top 1% by rating.
        val members = w.clubs.keys.flatMap { book.members(it) }
        val starCount = Math.ceil(members.size / 100.0).toInt()
        val stars = HashMap<String, Int>()
        members.sortedWith(ClubBook.ratingOrder(input.rating)).take(starCount).forEach { k -> book.clubOf(k)?.let { stars.merge(it, 1, Int::plus) } }
        val before = HashMap<String, IntArray>()
        val protests = ArrayList<String>()
        val parties = ArrayList<String>()
        val signed = ArrayList<SponsorChange>()
        val left = ArrayList<SponsorChange>()
        val owners = ArrayList<OwnerChange>()
        val stadiums = ArrayList<StadiumChange>()
        val facilities = ArrayList<FacilityChange>()
        val red = ArrayList<String>()
        val trouble = ArrayList<String>()
        val administration = ArrayList<String>()
        val dissolve = ArrayList<String>()
        fun leagueOf(id: String) = seats[id]?.league ?: playedIn(book, season, id)
        // 1. Fans and their mood.
        for (id in todo) {
            val st = w.clubs.getValue(id)
            val club = book.club(id)!!
            val seat = seats[id]
            before[id] = intArrayOf(st.fans, st.mood)
            val title = seat?.place == 1
            val base = fanBase(economy.lambdaOf(leagueOf(id)), cityIndex(club))
            st.fans = grow(st.fans, base, seat?.perf ?: 0.0, title, majors[id] ?: 0, stars[id] ?: 0, seat != null && seat.clubs > 1 && seat.place == seat.clubs, crisis)
            // Without a board's target, the middle of the table ((C + 1) / 2, a half place for an even C): the place score.
            val gap = if (seat == null) 0.0 else extra.target(id)?.let { gap(it, seat.place, seat.clubs) } ?: seat.perf
            st.mood = moodAfter(st.mood, gap, (if (title) TITLE_MOOD else 0) + extra.moodEvents(id) + (arcs.moodShocks[id] ?: 0))
            e.years[id]?.let { ys -> if (ys.firstOrNull()?.season == s) e.years[id] = listOf(ys.first().copy(fans = st.fans)) + ys.drop(1) }
            if (st.mood < PROTEST) protests += id
            if (title && st.mood >= PARTY) parties += id
        }
        // 2. Sponsors: renewals, walk-outs, new deals.
        val taken = HashSet<String>()
        for ((_, sp) in e.sponsors) taken += sp.name
        for ((_, sd) in e.stadiums) sd.naming?.let { taken += it }
        var best = e.sponsors.values.maxOfOrNull { it.perSeason } ?: 0
        for (id in todo) {
            val st = w.clubs.getValue(id)
            val club = book.club(id)!!
            val n = number(id)
            val rng = rng(closing.seed, SPONSOR_SALT, n)
            val lambda = economy.lambdaOf(leagueOf(id))
            val titles = recentTitles(book, id, s, seats[id])
            fun sign(factor: Double, renewal: Sponsor? = null): Sponsor {
                val (name, sector) = renewal?.let { it.name to it.sector } ?: Sponsors.draw(club.nation, book.seed, n, s + 1, taken)
                val value = Sponsors.perSeason(lambda, st.fans, titles, sector, arcs.sponsorFactor * factor)
                val deal = Sponsor(name, sector, value, s + 1, s + Sponsors.length(book.seed, n, s + 1), value > best)
                best = maxOf(best, value)
                taken += name
                e.sponsors[id] = deal
                return deal
            }
            val deal = e.sponsors[id]
            when {
                deal == null -> signed += SponsorChange(id, sign(Sponsors.REPLACEMENT))
                deal.until <= s -> {
                    if (Sponsors.renews(dealPerf(e.years[id].orEmpty(), deal, seats[id]), arcs.walkoutFactor, rng)) {
                        signed += SponsorChange(id, sign(1.0, deal), renewed = true)
                    } else {
                        left += SponsorChange(id, deal)
                        // The new sponsor is another company: the old name is free only after the draw.
                        signed += SponsorChange(id, sign(Sponsors.REPLACEMENT))
                        taken -= deal.name
                    }
                }
                (crisis || st.mood < Sponsors.MOOD_FLOOR) && rng.nextDouble() < Sponsors.WALK_MID * arcs.walkoutFactor -> {
                    e.sponsors.remove(id)
                    taken -= deal.name
                    left += SponsorChange(id, deal, mid = true)
                }
            }
        }
        // 3. Owners: walk-outs, one quiet takeover, the arcs' takeovers, injections and fines.
        for (id in todo) {
            val old = e.owners[id] ?: continue
            if (old.type != OwnerType.TYCOON) continue
            val rng = rng(closing.seed, OWNER_SALT, number(id))
            if (rng.nextDouble() < WALKOUT) {
                val o = owner(book, id, OwnerType.LOCAL, s + 1, rng, clubValue(book, id, extra.value) ?: 0)
                e.owners[id] = o
                owners += OwnerChange(id, o, old, 'W')
            }
        }
        val byFans = todo.mapNotNull { w.clubs[it] }.sortedWith(byFans())
        val topFans = byFans.take(Math.ceil(byFans.size / 4.0).toInt()).mapTo(HashSet()) { it.id }
        for (st in byFans) {
            val old = e.owners[st.id] ?: continue
            if (old.type != OwnerType.LOCAL || st.id !in topFans || owners.any { it.club == st.id }) continue
            val rng = rng(closing.seed, QUIET_SALT, number(st.id))
            if (rng.nextDouble() < QUIET_TAKEOVER) {
                val o = owner(book, st.id, OwnerType.TYCOON, s + 1, rng, clubValue(book, st.id, extra.value) ?: 0)
                e.owners[st.id] = o
                owners += OwnerChange(st.id, o, old, 'Q')
                break
            }
        }
        for (t in arcs.takeovers) {
            if (t.club !in todo) continue
            val st = w.clubs.getValue(t.club)
            val old = e.owners[t.club] ?: Owner.NONE
            val o = owner(book, t.club, t.type, s + 1, rng(closing.seed, ARC_SALT, number(t.club)), clubValue(book, t.club, extra.value) ?: 0, t.ambition, t.patience, t.name)
            e.owners[t.club] = o
            owners += OwnerChange(t.club, o, old, 'A')
            if (t.type == OwnerType.FUND || t.type == OwnerType.STATE) st.mood = (st.mood + moodEvents(fundTakeover = true, fansOwned = old.type == OwnerType.FANS)).coerceIn(0, 100)
        }
        for ((club, share) in arcs.injections) if (club in todo) charge(book, club, MoneyItem.OWNER, Math.round(share * income(book, club)).toInt(), s)
        for ((club, share) in arcs.fines) if (club in todo) charge(book, club, MoneyItem.UPKEEP, Math.round(share * maxOf(0, w.clubs.getValue(club).balance)).toInt(), s)
        // 4. Stadiums: progress, openings, new projects; then one facility upgrade.
        for (id in todo) {
            val st = w.clubs.getValue(id)
            val club = book.club(id)!!
            val stadium = e.stadiums[id] ?: Stadium.NONE
            val project = stadium.project
            val lambda = economy.lambdaOf(leagueOf(id))
            val income = income(book, id)
            if (project != null) {
                if (project.opens <= s + 1) {
                    charge(book, id, MoneyItem.BUILDING, maxOf(0, project.cost - project.paid), s)
                    val naming = if (project.kind == NEW_STADIUM) Sponsors.namingName(club.nation, book.seed, number(id), s, taken).also { taken += it } else stadium.naming
                    val opened = Stadium(if (project.kind == NEW_STADIUM) "$naming 아레나" else stadium.name, project.target, null, naming)
                    e.stadiums[id] = opened
                    stadiums += StadiumChange(id, 'O', stadium.capacity, opened, project.kind)
                } else if (project.kind == NEW_STADIUM) {
                    val due = minOf(project.cost - project.paid, Math.round(NEW_INSTALMENT * project.cost).toInt())
                    charge(book, id, MoneyItem.BUILDING, due, s)
                    e.stadiums[id] = stadium.copy(project = project.copy(paid = project.paid + due))
                }
            } else if (stadium.capacity > 0) {
                val (fans, mood) = before.getValue(id).let { it[0] to it[1] }
                val demand = demand(fans, mood, lambda)
                if (demand >= NEW_DEMAND * stadium.capacity) {
                    val target = minOf(MAX_SEATS, (Math.round(1.1 * demand / 1_000.0) * 1_000).toInt())
                    val cost = Math.round(target * NEW_SEAT_COST).toInt()
                    if (target > stadium.capacity && st.balance >= NEW_FIRST * cost) {
                        val first = Math.round(NEW_FIRST * cost).toInt()
                        charge(book, id, MoneyItem.BUILDING, first, s)
                        val started = stadium.copy(project = StadiumProject(NEW_STADIUM, target, s + 1 + NEW_SEASONS, cost, first))
                        e.stadiums[id] = started
                        stadiums += StadiumChange(id, NEW_STADIUM, stadium.capacity, started)
                    }
                } else if (attendance(fans, mood, lambda, stadium) >= EXPAND_FILL * stadium.capacity) {
                    val target = minOf(MAX_SEATS, stadium.capacity + maxOf(EXPAND_SEATS, stadium.capacity / 4))
                    val cost = Math.round((target - stadium.capacity) * EXPAND_SEAT_COST).toInt()
                    if (target > stadium.capacity && st.balance >= cost + 0.5 * income) {
                        val first = cost / 2
                        charge(book, id, MoneyItem.BUILDING, first, s)
                        val started = stadium.copy(project = StadiumProject(EXPAND, target, s + 1 + EXPAND_SEASONS, cost, first))
                        e.stadiums[id] = started
                        stadiums += StadiumChange(id, EXPAND, stadium.capacity, started)
                    }
                }
            }
            upgrade(book, id, income, s)?.let { facilities += it }
        }
        // 5. Trouble: 적자, 재정 위기, 법정관리, rescue, dissolution.
        for (id in todo) {
            val st = w.clubs.getValue(id)
            val income = income(book, id)
            val balance = st.balance
            st.deduction = 0
            if (balance < 0) red += id
            val troubled = income > 0 && balance < TROUBLE * income
            st.trouble = if (troubled) st.trouble + 1 else 0
            if (st.trouble == 1) trouble += id
            if (income > 0 && balance < ADMINISTRATION * income) {
                st.admin++
                st.deduction = ADMIN_DEDUCTION
                if (st.admin == 1) administration += id
                val rng = rng(closing.seed, RESCUE_SALT, number(id))
                val mayFold = st.admin >= 2 && w.dissolvedBy != s && (w.dissolvedBy == 0 || s - w.dissolvedBy >= DISSOLVE_EVERY)
                if (rng.nextDouble() < RESCUE || (st.admin >= 2 && !mayFold)) {
                    val old = e.owners[id] ?: Owner.NONE
                    val type = if (rng.nextDouble() < 0.6) OwnerType.LOCAL else OwnerType.TYCOON
                    val o = owner(book, id, type, s + 1, rng, clubValue(book, id, extra.value) ?: 0)
                    e.owners[id] = o
                    owners += OwnerChange(id, o, old, 'R')
                    val after = maxOf(balance / 2, Math.round(-0.2 * income).toInt())
                    charge(book, id, MoneyItem.OWNER, after - balance, s)
                    st.admin = 0
                    st.trouble = if (st.balance < TROUBLE * income) maxOf(1, st.trouble) else 0
                } else if (st.admin >= 2) {
                    dissolve += id
                    w.dissolvedBy = s
                }
            } else {
                st.admin = 0
            }
            if (input.config.ffp >= FFP_STRICT && st.balance < 0) st.deduction += FFP_DEDUCTION
            if (st.trouble >= 2 && st.trouble % 2 == 0) {
                val kind = if (st.training >= st.academy) 'T' else 'A'
                if (kind == 'T' && st.training > 1) {
                    st.training--
                    facilities += FacilityChange(id, 'T', st.training, up = false)
                } else if (kind == 'A' && st.academy > 1) {
                    st.academy--
                    facilities += FacilityChange(id, 'A', st.academy, up = false)
                }
            }
            e.lived += id
        }
        book.touch()
        val order = byFans().let { cmp -> Comparator<String> { a, b -> cmp.compare(w.clubs.getValue(a), w.clubs.getValue(b)) } }
        return LifeReport(
            s, protests.sortedWith(order), parties.sortedWith(order),
            signed.sortedWith(compareBy(order) { it.club }), left.sortedWith(compareBy(order) { it.club }), owners,
            stadiums.sortedWith(compareBy(order) { it.club }), facilities,
            red.sortedWith(order), trouble.sortedWith(order), administration.sortedWith(order), dissolve,
        )
    }

    /** One facility upgrade of club [id] a season (ENG 1b.2), when its balance after a reserve of half its [income] is at least twice the cost; null when none. */
    private fun upgrade(book: ClubBook, id: String, income: Int, s: Int): FacilityChange? {
        val w = book.world ?: return null
        val st = w.clubs[id] ?: return null
        val philosophy = book.coachOf(id)?.philosophy
        val owner = w.economy.owners[id]?.type
        val order = when {
            philosophy == Philosophy.YOUTH || owner == OwnerType.FANS -> "AT"
            philosophy == Philosophy.STAR -> "TA"
            st.training < st.academy -> "TA"
            else -> "AT"
        }
        for (kind in order) {
            val level = if (kind == 'T') st.training else st.academy
            val cost = upgradeCost(kind, level) ?: continue
            if (st.balance - 0.5 * income < 2.0 * cost) continue
            charge(book, id, MoneyItem.BUILDING, cost, s)
            if (kind == 'T') st.training++ else st.academy++
            return FacilityChange(id, kind, level + 1)
        }
        return null
    }

    // ---- the money a club world starts with (ENG 8.2) -----------------------------------------------

    /** The balance of every club of a new league (λ = 1): 10억. */
    const val FOUND_BALANCE = 100

    /** At a migration ω is set for a league's wage bill to be 0.62 × its estimated revenue, and a club's wages at most 0.6 × its estimated income. */
    const val START_WAGE_SHARE = 0.62
    const val START_WAGE_CAP = 0.6

    /** A migrated club's balance: `income × (0.2 + 1.3 × pct)`, pct its old budget's percentile within its nation. */
    const val START_FLOOR = 0.2
    const val START_SPAN = 1.3

    /** A club founded later: half the nation's median income, 3,000 seats, levels 1, 0.6 × the fan base. */
    const val NEW_CLUB_BALANCE = 0.5
    const val NEW_CLUB_FANS = 0.6

    /**
     * The money of a club world at its migration from a `clubs 2` book (design ENG 8.2), for every club
     * of the book (states made where missing), after the season [season] closed (the money history's
     * first season; deals and owners from [season] + 1): fans `base × (1 + 0.15 × min(5, titles))`, the
     * stadium from the fans, training and academy `1 + ` the club's old-budget tercile within its nation,
     * an owner by the id's hash, a sponsor deal (names drawn in id order, ending in seasons [season] + 1
     * .. + 3), a balance by the old budget's rank, `income × (0.2 + 1.3 × pct)`, from the estimated
     * recurring income of ENG 1.2 (λ of [economy], the place in [order] — league → club ids best first:
     * last season's places — else by old budget among the league's clubs, mood 50, the rounds of
     * [plan]'s leagues), each member's wage from its [value] with ω set for each league's wage bill to be
     * 0.62 × its revenue (within 0.5..2.0) and each club's wages at most 0.6 × its income. Terms that
     * exist take the wages; the result has them all. Mood 50; the coaches, identity and terms are the
     * other steps' (P5, P6, P11). Called once, on a book without a club world: it reads the clubs' old
     * budgets, which a club world replaces by `max(0, balance)`.
     */
    fun migrate(book: ClubBook, season: Int, economy: LeagueEconomy, order: Map<String, List<String>>, value: (String) -> Int, plan: SeasonPlan? = null): StartMoney =
        start(book, season, economy, order, value, plan, found = false)

    /**
     * The money of a new league's club world (design ENG 8.2: `ClubBook.found`): as [migrate] with every
     * league at λ = [economy] (neutral: 1) and no places, but a balance of 10억 for every club, fans at
     * the fan base and both facilities at level 2.
     */
    fun found(book: ClubBook, season: Int, value: (String) -> Int, economy: LeagueEconomy = LeagueEconomy.NEUTRAL, plan: SeasonPlan? = null): StartMoney =
        start(book, season, economy, emptyMap(), value, plan, found = true)

    /**
     * The money of club [id] founded after season [season] (design ENG 8.2): half the median income of
     * its nation's other clubs (its own estimate without any) and that income as its money history's
     * first season, an owner by hash (지역 기업 or 팬 소유 only), a sponsor at 0.85 × the formula, training
     * and academy 1, fans 0.6 × the fan base, a stadium of 3,000 seats, mood 50. False (nothing done)
     * for a book without a club world or a club it does not have.
     */
    fun newClub(book: ClubBook, id: String, season: Int, economy: LeagueEconomy, plan: SeasonPlan? = null): Boolean {
        val w = book.world ?: return false
        val club = book.club(id) ?: return false
        val e = w.economy
        val n = number(id)
        val league = club.squad.firstNotNullOfOrNull { plan?.league?.leagueOf(it)?.id } ?: club.nation
        val lambda = economy.lambdaOf(league)
        val st = w.state(id)
        st.fans = Math.round(NEW_CLUB_FANS * fanBase(lambda, cityIndex(club))).toInt().coerceIn(MIN_FANS, MAX_FANS)
        st.mood = 50
        st.training = 1
        st.academy = 1
        // An id reused from a club dissolved long ago (ClubBook's next id) starts clean of its money.
        st.trouble = 0
        st.admin = 0
        st.deduction = 0
        e.accounts.remove(id)
        e.results.remove(id)
        e.lived -= id
        e.stadiums[id] = Stadium(stadiumName(book.seed, club), MIN_SEATS, null, null)
        val rng = rng(book.seed, OWNER_SALT, n.toLong() + season)
        val type = if (Math.floorMod(DiceStream.mix(book.seed, OWNER_SALT, 2L, n.toLong()), 100L) < 64L) OwnerType.LOCAL else OwnerType.FANS
        e.owners[id] = owner(book, id, type, season + 1, rng)
        val taken = e.sponsors.values.mapTo(HashSet()) { it.name }.apply { e.stadiums.values.mapNotNullTo(this) { it.naming } }
        val (name, sector) = Sponsors.draw(club.nation, book.seed, n, season + 1, taken)
        val per = Sponsors.perSeason(lambda, st.fans, 0, sector, Sponsors.REPLACEMENT)
        e.sponsors[id] = Sponsor(name, sector, per, season + 1, season + Sponsors.length(book.seed, n, season + 1), false)
        val others = book.clubsOf(club.nation).map { it.id }.filter { it != id }.map { income(book, it) }.filter { it > 0 }.sorted()
        val clubs = book.clubsOf(club.nation).size.coerceAtLeast(1)
        val median = if (others.isEmpty()) estimate(book, id, lambda, (clubs + 1) / 2, clubs, plan, league) else others[others.size / 2]
        st.balance = Math.round(NEW_CLUB_BALANCE * median).toInt()
        // Its money history starts with that income, as a migrated club's does (its health and spending cap read it).
        e.years[id] = listOf(ClubYear(season, st.balance, median, st.fans, 0))
        book.touch()
        return true
    }

    private fun start(book: ClubBook, season: Int, economy: LeagueEconomy, order: Map<String, List<String>>, value: (String) -> Int, plan: SeasonPlan?, found: Boolean): StartMoney {
        val clubs = book.clubs
        val w = book.startWorld()
        val e = w.economy
        // Where each club plays and its place: the given order, else by old budget among the league's clubs.
        val leagueOf = HashMap<String, String>()
        val place = HashMap<String, Int>()
        val size = HashMap<String, Int>()
        for ((league, ids) in order) for ((i, id) in ids.withIndex()) if (leagueOf.putIfAbsent(id, league) == null) {
            place[id] = i + 1
            size[id] = ids.size
        }
        for (c in clubs) if (c.id !in leagueOf) leagueOf[c.id] = c.squad.firstNotNullOfOrNull { plan?.league?.leagueOf(it)?.id } ?: c.nation
        val byLeague = clubs.groupBy { leagueOf.getValue(it.id) }
        for ((_, cs) in byLeague) {
            val missing = cs.filter { it.id !in place }.sortedWith(compareByDescending<Club> { it.budget }.thenComparing({ it.id }, ClubBook.ID_ORDER))
            val listed = cs.size - missing.size
            for ((i, c) in missing.withIndex()) {
                place[c.id] = listed + i + 1
                size[c.id] = cs.size
            }
        }
        // The old budget's percentile within the nation (1 the richest).
        val pct = HashMap<String, Double>()
        for ((_, cs) in clubs.groupBy { it.nation }) {
            val sorted = cs.sortedWith(compareByDescending<Club> { it.budget }.thenComparing({ it.id }, ClubBook.ID_ORDER))
            for ((i, c) in sorted.withIndex()) pct[c.id] = if (sorted.size <= 1) 0.5 else (sorted.size - 1 - i).toDouble() / (sorted.size - 1)
        }
        val taken = HashSet<String>()
        val income = TreeMap<String, Int>(ClubBook.ID_ORDER)
        val listed = order.values.flatten().toHashSet()
        for (c in clubs) {
            val id = c.id
            val n = number(id)
            val st = w.state(id)
            val league = leagueOf.getValue(id)
            val lambda = economy.lambdaOf(league)
            val p = pct.getValue(id)
            val base = fanBase(lambda, cityIndex(c))
            st.fans = if (found) Math.round(base).toInt().coerceIn(MIN_FANS, MAX_FANS) else initialFans(base, c.honours)
            st.mood = 50
            val level = if (found) 2 else 1 + when {
                p >= 2.0 / 3 -> 2
                p >= 1.0 / 3 -> 1
                else -> 0
            }
            st.training = level
            st.academy = level
            e.stadiums[id] = Stadium(stadiumName(book.seed, c), startSeats(st.fans), null, null)
            val rng = rng(book.seed, OWNER_SALT, n.toLong())
            e.owners[id] = owner(book, id, startOwner(book.seed, n), season + 1, rng)
            val (name, sector) = Sponsors.draw(c.nation, book.seed, n, season + 1, taken)
            taken += name
            val titles = (season - 4..season).count { x -> book.places(x).any { it.clubs.firstOrNull() == id } }
            val per = Sponsors.perSeason(lambda, st.fans, titles, sector)
            e.sponsors[id] = Sponsor(name, sector, per, season + 1, season + 1 + Math.floorMod(DiceStream.mix(book.seed, SPONSOR_SALT, n.toLong()), 3L).toInt(), false)
            val est = estimate(book, id, lambda, place.getValue(id), size.getValue(id), plan, league)
            income[id] = est
            st.balance = if (found) FOUND_BALANCE else Math.round(est * (START_FLOOR + START_SPAN * p)).toInt()
            e.years[id] = listOf(ClubYear(season, st.balance, est, st.fans, if (id in listed) place.getValue(id) else 0))
        }
        // Wages: today's values, ω for each league's bill to be 0.62 × its revenue, each club at most 0.6 × its income.
        val base = HashMap<String, Int>()
        for (c in clubs) for (k in c.squad) base[k] = Valuation.baseWage(value(k))
        val omega = TreeMap<String, Double>()
        for ((league, cs) in byLeague) {
            val revenue = cs.sumOf { income.getValue(it.id).toLong() }
            val bill = cs.sumOf { c -> c.squad.sumOf { (base[it] ?: 0).toLong() } }
            val o = if (bill <= 0) 1.0 else (START_WAGE_SHARE * revenue / bill).coerceIn(LeagueEconomy.OMEGA_MIN, LeagueEconomy.OMEGA_MAX)
            omega[league] = LineParts.fixed(o, 100)
            w.wageIndex[league] = Math.round(o * 100).toInt()
        }
        val wages = HashMap<String, Int>()
        for (c in clubs) {
            val league = leagueOf.getValue(c.id)
            val o = omega.getValue(league)
            val floor = minWage(economy.lambdaOf(league))
            val raw = c.squad.associateWith { k -> maxOf(floor, Math.round((base[k] ?: 0) * o).toInt()) }
            val bill = raw.values.sum()
            val cap = START_WAGE_CAP * income.getValue(c.id)
            val scale = if (bill > cap && bill > 0) cap / bill else 1.0
            for ((k, x) in raw) {
                val wage = maxOf(1, Math.floor(x * scale).toInt())
                wages[k] = wage
                w.terms[k]?.let { w.terms[k] = it.copy(wage = wage) }
            }
        }
        book.touch()
        return StartMoney(wages, income, omega)
    }

    /**
     * Club [id]'s estimated recurring income (ENG 1.2, 8.2) in a league of [lambda] at [place] of
     * [clubs]: TV, prize money, its sponsor deal now and match-day money at its fans with mood 50 over
     * the rounds of [league]'s 1부 in [plan] (30 without a plan).
     */
    private fun estimate(book: ClubBook, id: String, lambda: Double, place: Int, clubs: Int, plan: SeasonPlan?, league: String): Int {
        val w = book.world ?: return 0
        val st = w.clubs[id] ?: return 0
        val stadium = w.economy.stadiums[id] ?: Stadium.NONE
        val owner = w.economy.owners[id] ?: Owner.NONE
        val rounds = plan?.league?.let { season -> season.leagues.byId(league)?.let { season.roundsOf(it.top) } } ?: DEFAULT_ROUNDS
        val sponsor = w.economy.sponsors[id]?.perSeason ?: 0
        return tv(lambda, place, clubs) + prize(lambda, place) + sponsor + matchday(attendance(st.fans, 50, lambda, stadium), price(lambda, owner.type), matchDays(rounds))
    }

    /** The rounds a migration estimates with when it has no plan: a 16-player double round robin. */
    const val DEFAULT_ROUNDS = 30

    // ---- helpers ------------------------------------------------------------------------------------

    private const val SPONSOR_SALT = 0x5B0DL
    private const val OWNER_SALT = 0x0A3EL
    private const val QUIET_SALT = 0x0A3FL
    private const val ARC_SALT = 0x0A40L
    private const val RESCUE_SALT = 0x0A41L
    private const val STADIUM_SALT = 0x57ADL

    /** Each club's seat in [tables] (the first table it is in). */
    fun seats(tables: Map<String, List<ClubStanding>>): Map<String, ClubSeat> {
        val out = HashMap<String, ClubSeat>()
        for ((league, table) in tables) for ((i, row) in table.withIndex()) out.putIfAbsent(row.club, ClubSeat(league, i + 1, table.size))
        return out
    }

    /** A club's default stadium name: "{city} {type}", the type by the id's hash. */
    fun stadiumName(seed: Long, club: Club): String =
        "${club.city.ifEmpty { club.name }} ${STADIUM_TYPES[Math.floorMod(DiceStream.mix(seed, STADIUM_SALT, number(club.id).toLong()), STADIUM_TYPES.size.toLong()).toInt()]}"

    /** The index of a club's city among its nation's club cities (its fan base's weight); 9 when unknown. */
    fun cityIndex(club: Club): Int {
        val nation = Nations.of(club.nation) ?: return 9
        return ClubNames.cities(nation).indexOf(club.city).takeIf { it >= 0 } ?: 9
    }

    /** Club-table titles of club [id] in the 5 seasons up to [season]: the `cs` lines before it and its [seat] of [season]. */
    private fun recentTitles(book: ClubBook, id: String, season: Int, seat: ClubSeat?): Int =
        (season - 4 until season).count { x -> book.places(x).any { it.clubs.firstOrNull() == id } } + if (seat?.place == 1) 1 else 0

    /** The mean place score of a club over a sponsor [deal]'s seasons ([years]' places, scored in a table of the [seat]'s size); 0 without any. */
    private fun dealPerf(years: List<ClubYear>, deal: Sponsor, seat: ClubSeat?): Double {
        val clubs = seat?.clubs ?: return 0.0
        val scores = years.filter { it.season in deal.from..deal.until && it.place > 0 }.map { ClubSeat("", it.place, clubs).perf }
        return if (scores.isEmpty()) 0.0 else scores.average()
    }

    /** The league club [id] played in in [season]: its first member's, else its nation's id. */
    private fun playedIn(book: ClubBook, season: Season, id: String): String =
        book.members(id).firstNotNullOfOrNull { season.leagueOf(it)?.id } ?: book.nationOf(id).orEmpty()

    private fun byFans(): Comparator<ClubState> = compareByDescending<ClubState> { it.fans }.thenComparing({ it.id }, ClubBook.ID_ORDER)

    private fun rng(seed: Long, salt: Long, club: Int): Random = Random(DiceStream.mix(seed, salt, club.toLong()))

    private fun rng(seed: Long, salt: Long, x: Long): Random = Random(DiceStream.mix(seed, salt, x))

    /** A club id's number (its hash for another shape). */
    internal fun number(id: String): Int = ClubBook.number(id) ?: Math.floorMod(id.hashCode(), 1_000_000)

    /** [this] with [amount] more of [item]. */
    private fun Accounts.plus(item: MoneyItem, amount: Int): Accounts = when (item) {
        MoneyItem.TV -> copy(tv = tv + amount)
        MoneyItem.PRIZE -> copy(prize = prize + amount)
        MoneyItem.INTL -> copy(intl = intl + amount)
        MoneyItem.SPONSOR -> copy(sponsor = sponsor + amount)
        MoneyItem.MATCHDAY -> copy(matchday = matchday + amount)
        MoneyItem.OWNER -> copy(owner = owner + amount)
        MoneyItem.SOLIDARITY -> copy(solidarity = solidarity + amount)
        MoneyItem.SALES -> copy(sales = sales + amount)
        MoneyItem.WAGES -> copy(wages = wages + amount)
        MoneyItem.COACH -> copy(coach = coach + amount)
        MoneyItem.UPKEEP -> copy(upkeep = upkeep + amount)
        MoneyItem.BUILDING -> copy(building = building + amount)
        MoneyItem.BUYS -> copy(buys = buys + amount)
    }
}

/** Club [id]'s health grade A..E ([ClubEconomy.health]); null without a club world state. */
fun ClubBook.health(id: String): Char? = ClubEconomy.health(this, id)

/** Club [id]'s transfer budget now ([ClubEconomy.transferBudget]) under FFP level [ffp]. */
fun ClubBook.transferBudget(id: String, ffp: Int = 1): Int = ClubEconomy.transferBudget(this, id, ffp)

/** Club [id]'s wage bill ([ClubEconomy.wageBill]). */
fun ClubBook.wageBill(id: String): Int = ClubEconomy.wageBill(this, id)

/** Club [id]'s value with members valued by [value] ([ClubEconomy.clubValue]); null without a state. */
fun ClubBook.clubValue(id: String, value: (String) -> Int): Int? = ClubEconomy.clubValue(this, id, value)

/** Club [id]'s money trouble 0..3 ([ClubEconomy.troubleLevel]); null without a state. */
fun ClubBook.troubleLevel(id: String): Int? = ClubEconomy.troubleLevel(this, id)

/**
 * A member's market value in this book ([Valuation.value] at the club world's anchor, or the probe's
 * without one), from its [rating] and identity ([age], [potential], [overall]; nulls for base AIs).
 */
fun ClubBook.valuer(
    rating: (String) -> Double,
    age: (String) -> Int? = { null },
    potential: (String) -> Int? = { null },
    overall: (String) -> Int? = { null },
): (String) -> Int {
    val anchor = world?.anchor ?: Valuation.ANCHOR
    return { k -> Valuation.value(rating(k), anchor, age(k), potential(k), overall(k)) }
}

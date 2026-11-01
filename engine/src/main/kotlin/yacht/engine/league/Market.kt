package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random
import java.util.TreeMap

/** A transfer story's stage (design ENG 3.1); [code] is saved. */
enum class StoryStage(val code: Char, val label: String) {
    RUMOUR('R', "소문"),
    TALKS('T', "협상 중"),
    DONE('D', "성사"),
    COLLAPSED('C', "무산"),
    HIJACKED('H', "가로채기"),
    ;

    companion object {
        fun of(code: Char): StoryStage? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A transfer story (이적설, design ENG 3.1): [buyer] wants member [key] of [seller] for [fee] (the
 * asked fee when the rumour started in week [rumourWeek]); talks from [talksWeek] (0: none). How
 * it ended: [reason] '-' (none yet), 'P' 가격, 'S' 잔류, 'H' 가로채기 (by club [by]), 'M' 감독 교체,
 * 'F' 재정, 'R' 규정; [winter] when it belongs to the winter window. [id] is unique among the
 * stories kept. A story the off-season's market made happen ([StoryStage.DONE]) carries the fee paid.
 */
data class MarketStory(
    val id: Int, val season: Int, val stage: StoryStage, val key: String, val buyer: String, val seller: String,
    val fee: Int, val rumourWeek: Int, val talksWeek: Int,
    val reason: Char, val winter: Boolean, val by: String?,
)

/**
 * A notable transfer (`tx`, design ENG 3.2) after [season]: member [key] from club [from] to [to]
 * (null: none, a free agent or abroad) for [fee], move type [type] (a [ClubMoveType] code), with
 * [flags]: W world record, L league record, C club record, V rival, K release clause, D deadline
 * day, F forced sale ("" for none).
 */
data class NotableMove(val season: Int, val type: Char, val key: String, val from: String?, val to: String?, val fee: Int, val flags: String)

/**
 * The phases of an off-season's transfer window (design ENG 3.2, the club off-season's steps 6-9), in
 * the order they run; [code] is saved with each move of the window, [label] heads the window's replay
 * (☀ 이적시장 다시 보기).
 */
enum class MarketPhase(val code: Char, val label: String) {
    /** Loans of the season before end: the member goes back, or its loan club keeps it (완전 이적). */
    BACK('B', "임대 복귀"),

    /** Returns home and transfers abroad ([Transfers.plan] with the club world's money), talks for an abroad move first. */
    ABROAD('A', "해외 이적"),

    /** Round 0: the talks of the season decided. */
    TALKS('0', "협상 타결"),

    /** Round 1, parity: the worst-placed clubs buy first. */
    PARITY('1', "1라운드"),
    FORCED('F', "강제 매각"),

    /** Members whose contract ran out sign where the wage is best. */
    FREE('G', "자유계약"),

    /** Round 2, money: the richest clubs buy first, from any club of their nation. */
    MONEY('2', "2라운드"),
    DEADLINE('D', "마감일"),
    LOANS('L', "임대"),

    /** Free agents nobody signed: to the draft. */
    RELEASED('R', "방출"),

    /** The winter window at the checkpoint ([Market.winter]). */
    WINTER('W', "겨울 이적시장"),
    ;

    companion object {
        fun of(code: Char): MarketPhase? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One move of a transfer window: member [key] from club [from] (a free agent's: the club it left) to
 * [to] (null: none, to the draft) for [fee] in [phase]. [type] is a [ClubMoveType] code, also the
 * market's own codes before Clubs.kt has them (A 자유계약 이적, K 바이아웃, S 강제 매각, L 임대, B 임대
 * 복귀; the move log writes those as [ClubMoveType.TRANSFER] until then). [flags] are the `tx` flags
 * ([NotableMove.flags]) and P when the member was its old club's captain. [wage] is the new
 * contract's wage a season (0 for a release, a loan and a loan's return).
 */
data class MarketDeal(
    val phase: MarketPhase, val type: Char, val key: String, val from: String?, val to: String?, val fee: Int, val flags: String, val wage: Int,
)

/** The renewal the news tells (`contract_renewal`): member [key] stays at [club] for [seasons] more seasons at [wage]. */
data class Renewal(val key: String, val club: String, val seasons: Int, val wage: Int)

/** The record fee the news tells (`record_fee`): the window's deal number [deal], its [scope] ("W" the world, else a league id) and the [previous] record. */
data class RecordFee(val deal: Int, val scope: String, val previous: Int)

/**
 * What an off-season's transfer window did ([Market.offseason]), rebuilt from the book when asked
 * again ([Market.report]): its [deals] in order, the contracts renewed and the one the news tells, the
 * season's talks with their outcome ([stories]), the told record fee and the 8 best-ranked leagues'
 * record fees after the window ([leagueRecords], for the club rankings' `rk` line).
 */
data class MarketReport(
    val season: Int,
    val deals: List<MarketDeal> = emptyList(),
    val renewals: Int = 0,
    val renewal: Renewal? = null,
    val stories: List<MarketStory> = emptyList(),
    val record: RecordFee? = null,
    val leagueRecords: Map<String, Int> = emptyMap(),
) {
    /** The deals of [phase]. */
    fun of(phase: MarketPhase): List<MarketDeal> = deals.filter { it.phase == phase }

    /** Keys of the talks that came true ("소문이 사실이 됐어요" in the `transfer` news). */
    val talked: List<String> get() = stories.filter { it.stage == StoryStage.DONE }.map { it.key }

    /** Captains who moved to another club: transfer rivalries' links for [Derbies.recompute] (why K). */
    val captainLinks: List<RivalLink>
        get() = deals.filter { CAPTAIN in it.flags && it.from != null && it.to != null && it.from != it.to }
            .map { d -> ClubHistoryBook.pair(d.from!!, d.to!!).let { (a, b) -> RivalLink(a, b, 'T', season, 'K') } }

    /**
     * The fans' mood points of club [club]'s window ([ClubEconomy.moodEvents]): each [star] it sold, a
     * record signing (a world, league or club record fee paid by it) and each member gone to a rival.
     */
    fun moodEvents(club: String, star: (String) -> Boolean): Int = ClubEconomy.moodEvents(
        starsSold = deals.count { it.from == club && it.type in SALES && it.fee > 0 && star(it.key) },
        recordSigning = deals.any { it.to == club && it.flags.any { f -> f in "WLC" } },
        toRival = deals.count { it.from == club && 'V' in it.flags },
    )

    /**
     * The window's news (design ENG 11.2; templates are News.kt's), one of each at most: `record_fee`
     * [ai, from, to] (fee, previous, scope: W or the league), `rival_move` [ai, from, to] (fee),
     * `release_clause` [ai, from, to] (fee), `forced_sale` [ai, from, to] (fee), `free_agent` [ai, from,
     * to] (wage), `contract_renewal` [ai, club] (seasons, wage), `deal_collapsed` [ai, buyer, seller,
     * hijacker?] (reason code) for the biggest collapsed talks and the biggest hijacked ones (one each),
     * `deadline_day` [≤ 6 movers] (count) and
     * `loan_digest` [≤ 6 loaned] (count). Each pick is the biggest fee (the free agent: the best wage),
     * then the earlier deal. The `transfer`, `transfer_abroad` and `market_digest` news stay the
     * off-season's digest's ([talked] marks the talks that came true).
     */
    fun events(): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        fun event(kind: String, keys: List<String>, vararg args: String) {
            out += LeagueEvent(kind, season, keys, args.toList(), Comp.CLUB)
        }
        fun keys(d: MarketDeal) = listOf(d.key, d.from ?: "-", d.to ?: "-")
        fun best(xs: List<MarketDeal>, by: (MarketDeal) -> Int = { it.fee }) = xs.withIndex().maxWithOrNull(compareBy<IndexedValue<MarketDeal>> { by(it.value) }.thenByDescending { it.index })?.value
        record?.let { r -> deals.getOrNull(r.deal)?.let { d -> event("record_fee", keys(d), d.fee.toString(), r.previous.toString(), r.scope) } }
        best(deals.filter { 'V' in it.flags && it.fee > 0 })?.let { event("rival_move", keys(it), it.fee.toString()) }
        best(deals.filter { it.type == CLAUSE })?.let { event("release_clause", keys(it), it.fee.toString()) }
        best(deals.filter { it.type == FORCED })?.let { event("forced_sale", keys(it), it.fee.toString()) }
        best(deals.filter { it.type == FREE && it.to != null }) { it.wage }?.let { event("free_agent", keys(it), it.wage.toString()) }
        renewal?.let { event("contract_renewal", listOf(it.key, it.club), it.seasons.toString(), it.wage.toString()) }
        for (stage in listOf(StoryStage.COLLAPSED, StoryStage.HIJACKED)) {
            stories.filter { it.stage == stage }.maxWithOrNull(compareBy<MarketStory> { it.fee }.thenByDescending { it.id })
                ?.let { s -> event("deal_collapsed", listOfNotNull(s.key, s.buyer, s.seller, s.by), s.reason.toString()) }
        }
        val late = of(MarketPhase.DEADLINE).filter { it.to != null && it.type != RELEASE }
        if (late.isNotEmpty()) event("deadline_day", late.sortedByDescending { it.fee }.take(NAMED).map { it.key }, late.size.toString())
        val loans = deals.filter { it.type == LOAN }
        if (loans.isNotEmpty()) event("loan_digest", loans.take(NAMED).map { it.key }, loans.size.toString())
        return out
    }

    companion object {
        /** A captain's move (the window's own flag, not written to `tx`). */
        const val CAPTAIN = 'P'

        /** The market's own move codes (ENG 1b.8): 자유계약 이적, 바이아웃, 강제 매각, 임대, 임대 복귀, 겨울 이적; and a release. */
        const val FREE = 'A'
        const val CLAUSE = 'K'
        const val FORCED = 'S'
        const val LOAN = 'L'
        const val LOAN_BACK = 'B'
        const val WINTER = 'W'
        const val RELEASE = 'R'

        /** Moves that sell a member for a fee (a star sold upsets the fans). */
        const val SALES = "TFKSW"

        /** Movers named in a digest. */
        const val NAMED = 6
    }
}

/**
 * What the club world's market reads about a player besides its rating and age (design ENG 2.5's fit,
 * 1b.8's renewals): its style family, the 대담성 and 칸 운영 bars of its style ([Tendencies]), its 멘탈,
 * whether its overlay is a gambler's or a streaky one (뒤지면 승부수, 기분파), its potential and overall.
 */
data class PlayerTraits(
    val family: StyleFamily?, val bold: Int, val side: Int, val mental: Int, val volatile: Boolean,
    val potential: Int?, val overall: Int?,
) {
    companion object {
        /** The traits of a player with [gene] and [identity]; null without both (base AIs, the guest). */
        fun of(gene: PlayGene?, identity: Identity?): PlayerTraits? {
            if (gene == null && identity == null) return null
            val t = gene?.let { Tendencies.of(it.style()) }
            return PlayerTraits(
                StyleFamily.of(gene), t?.bold ?: 0, t?.side ?: 0, identity?.abilities?.mental ?: Market.CALM,
                gene?.overlay == "gambler" || gene?.overlay == "streaky", identity?.potential, identity?.abilities?.overall,
            )
        }
    }
}

/**
 * What the off-season's market reads besides the book (design ENG 3.2): the off-season's [club] input
 * (members, ratings, ages, nations, the config's `ffp`, the honours, the ranking, the economy), the
 * club [tables] of the close (league → table, best first, as [ClubTable.all] gives them with the
 * season's splits and deductions: places and counted members), each player's [traits], each member's
 * market [value] (null: the book's [valuer] from the ratings, ages and the traits' potential and
 * overall), the archive's season [lines] of a member (legends of members who move) and an investor's
 * [injection] this off-season per club (as the club-life step booked it).
 */
class MarketInput(
    val club: ClubInput,
    val tables: Map<String, List<ClubStanding>> = emptyMap(),
    val traits: (String) -> PlayerTraits? = { null },
    val value: ((String) -> Int)? = null,
    val lines: (String) -> List<SeasonLine> = { emptyList() },
    val injection: (String) -> Int = { 0 },
)

/**
 * The saved summary of the last off-season's window (an `ms W` line, design ENG 3.2): the market's
 * guard (the season) and what its report is rebuilt from: where its moves start among the season's
 * logged moves ([offset]), each move's phase, type, flags and wage, the renewals, the told record and
 * the leagues' record fees.
 */
internal class WindowLine(
    val season: Int, val offset: Int, val deals: List<Entry>, val renewals: Int, val renewal: Renewal?, val record: RecordFee?,
    val records: Map<String, Int>,
) {
    data class Entry(val phase: MarketPhase, val type: Char, val flags: String, val wage: Int)
}

/**
 * The club world's market part: the stories (`ms`: the current season's open ones and the last
 * off-season's outcomes), the notable transfers (`tx`, newest seasons) and the last off-season's window
 * (an `ms W` line). The off-season's market is [Market], the weekly tick P13's.
 */
class MarketBook {
    internal val stories = ArrayList<MarketStory>()
    internal val notable = ArrayList<NotableMove>()
    internal var window: WindowLine? = null

    /** The stories of [season] (any stage), by id. */
    fun stories(season: Int): List<MarketStory> = stories.filter { it.season == season }.sortedBy { it.id }

    /** The notable transfers kept, oldest first. */
    val moves: List<NotableMove> get() = notable.toList()

    /** The season of the last off-season's window (0 before any). */
    val windowSeason: Int get() = window?.season ?: 0

    companion object {
        /** The window line's second field (a story's is its id, a number). */
        private const val WINDOW = "W"

        private val MS = LineParts.of<ClubWorld>("ms", { w, out ->
            for (s in w.market.stories) {
                out.append("ms ${s.id} ${s.season} ${s.stage.code} ${Field.esc(s.key)} ${s.buyer} ${s.seller} ${s.fee} ${s.rumourWeek} ${s.talksWeek} ")
                out.append("${s.reason} ${LineParts.bit(s.winter)} ${s.by ?: "-"}\n")
            }
            w.market.window?.let { x ->
                out.append("ms $WINDOW ${x.season} ${x.offset} ")
                out.append(LineParts.list(x.deals) { "${it.phase.code}${it.type}${it.flags}:${it.wage}" }).append(' ')
                out.append(x.renewals).append(' ')
                out.append(x.renewal?.let { "${Field.esc(it.key)}:${it.club}:${it.seasons}:${it.wage}" } ?: "-").append(' ')
                out.append(x.record?.let { "${it.deal}:${Field.esc(it.scope)}:${it.previous}" } ?: "-").append(' ')
                out.append(LineParts.list(x.records.entries) { "${Field.esc(it.key)}:${it.value}" }).append('\n')
            }
        }) { w, f ->
            if (f[1] == WINDOW) {
                require(f.size >= 9)
                val deals = LineParts.items(f[4]).map { e ->
                    val (head, wage) = e.split(':')
                    WindowLine.Entry(MarketPhase.of(head[0])!!, head[1], head.substring(2), wage.toInt())
                }
                val renewal = f[6].takeIf { it != "-" }?.split(':')?.let { p -> Renewal(Field.unesc(p[0]), p[1], p[2].toInt(), p[3].toInt()) }
                val record = f[7].takeIf { it != "-" }?.split(':')?.let { p -> RecordFee(p[0].toInt(), Field.unesc(p[1]), p[2].toInt()) }
                val records = TreeMap<String, Int>()
                for (e in LineParts.items(f[8])) records[Field.unesc(e.substringBefore(':'))] = e.substringAfter(':').toInt()
                w.market.window = WindowLine(f[2].toInt(), f[3].toInt(), deals, f[5].toInt(), renewal, record, records)
                return@of true
            }
            require(f.size >= 13)
            val stage = StoryStage.of(f[3].single()) ?: return@of false
            w.market.stories += MarketStory(
                f[1].toInt(), f[2].toInt(), stage, Field.unesc(f[4]), f[5], f[6], f[7].toInt(), f[8].toInt(), f[9].toInt(),
                f[10].single(), f[11] == "1", f[12].takeIf { it != "-" },
            )
            true
        }

        private val TX = LineParts.of<ClubWorld>("tx", { w, out ->
            for (m in w.market.notable) out.append("tx ${m.season} ${m.type} ${Field.esc(m.key)} ${m.from ?: "-"} ${m.to ?: "-"} ${m.fee} ${m.flags.ifEmpty { "-" }}\n")
        }) { w, f ->
            require(f.size >= 8)
            w.market.notable += NotableMove(f[1].toInt(), f[2].single(), Field.unesc(f[3]), f[4].takeIf { it != "-" }, f[5].takeIf { it != "-" }, f[6].toInt(), if (f[7] == "-") "" else f[7])
            true
        }

        /** `ms tx`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(MS, TX)
    }
}

/**
 * The club world's transfer market (design ENG 1.5, 3.2, 1b.8, 2.5) as functions of the club book: the
 * off-season's window ([offseason], the club off-season's steps 6-9: loans back, contracts, returns and
 * transfers abroad, the talks, the parity round, forced sales, free agents, the money round, deadline
 * day, loans out), the rules every window shares (the concentration guard [cap]/[allowed], the asked fee
 * [price], the player's yes [accepts], the coach's fit [fit], contract [length]s and release [clause]s)
 * and the window's record fees and notable transfers (`tx`). A book without a club world (or of the club
 * league, or with `clubWorld` off) is left as it is.
 */
object Market {
    // ---- the concentration guard (ENG 1.5) ----------------------------------------------------------

    /** The guard counts a nation's this many best-rated members. */
    const val GUARD_TOP = 16

    /** `cap(C) = max(2, ⌈1.5 × 16 / C⌉)`: 1.5 × the fair share of the 16 best. */
    const val GUARD_SHARE = 24
    const val GUARD_MIN = 2

    /**
     * The most of its nation's [GUARD_TOP] best-rated members one club may hold after an incoming move
     * when the nation has [clubs] clubs: `max(2, ⌈24 / C⌉)` (12, 8, 6, 5, 3, 2 for 2, 3, 4, 5, 8, 16
     * clubs); no limit with fewer than 2.
     */
    fun cap(clubs: Int): Int = if (clubs < 2) Int.MAX_VALUE else maxOf(GUARD_MIN, (GUARD_SHARE + clubs - 1) / clubs)

    /**
     * Whether member [key] may join club [buyer] (design ENG 1.5, every incoming move: domestic, abroad,
     * free agents, clauses, loans, the winter): after the move the buyer holds at most [cap] of the
     * [GUARD_TOP] best-rated members ([rating]) playing for its nation's clubs (foreigners included), or
     * no more than before the move (a club already above the cap may still sign a player outside the 16).
     */
    fun allowed(book: ClubBook, key: String, buyer: String, rating: (String) -> Double): Boolean {
        val nation = book.nationOf(buyer) ?: return true
        val clubs = book.clubsOf(nation)
        val cap = cap(clubs.size)
        if (cap >= GUARD_TOP) return true
        val r = { k: String -> rating(k).let { if (it.isNaN()) Double.NEGATIVE_INFINITY else it } }
        val now = ArrayList<Pair<String, String>>()
        for (c in clubs) for (k in c.squad) now += k to c.id
        val before = top(now, r).count { it.second == buyer }
        // Under the cap before, one member more is at most the cap.
        if (before < cap) return true
        val then = now.filter { it.first != key } + (key to buyer)
        val after = top(then, r).count { it.second == buyer }
        return after <= cap || after <= before
    }

    /**
     * The [GUARD_TOP] best of [members] (member to club) by [r], best first, then by key: what sorting
     * them all would give, in one pass (the guard asks for every buyer and every target).
     */
    internal fun top(members: List<Pair<String, String>>, r: (String) -> Double): List<Pair<String, String>> {
        val best = ArrayList<Pair<String, String>>(GUARD_TOP + 1)
        val ratings = ArrayList<Double>(GUARD_TOP + 1)
        for (m in members) {
            val x = r(m.first)
            // The place among the best so far: after every better one (a higher rating, or the same and a smaller key).
            var i = best.size
            while (i > 0) {
                val c = java.lang.Double.compare(ratings[i - 1], x)
                if (c > 0 || (c == 0 && best[i - 1].first < m.first)) break
                i--
            }
            if (i >= GUARD_TOP) continue
            best.add(i, m)
            ratings.add(i, x)
            if (best.size > GUARD_TOP) {
                best.removeAt(GUARD_TOP)
                ratings.removeAt(GUARD_TOP)
            }
        }
        return best
    }

    // ---- prices, wages and contracts (ENG 1.1, 1b.8) ------------------------------------------------

    /** What a seller asks: [fee], and whether the member's release clause set it ([clause]: paying it, no seller can refuse). */
    data class Price(val fee: Int, val clause: Boolean)

    /**
     * What club [seller] asks club [buyer] for member [key] of [value] when the new contract starts in
     * season [now] (the off-season after season s: s + 1; a winter: s): [Valuation.asked] with the
     * years left of its contract (`until − now + 1`), the rival premium, the seller's trouble and owner;
     * a release clause caps it (the clause is then the fee).
     */
    fun price(book: ClubBook, key: String, seller: String, buyer: String, now: Int, value: Int): Price {
        val w = book.world
        val trouble = (ClubEconomy.troubleLevel(book, seller) ?: 0) >= 2
        val owner = w?.economy?.owners?.get(seller)?.type ?: OwnerType.LOCAL
        return price(w?.terms?.get(key), now, value, book.isRival(seller, buyer), trouble, owner)
    }

    /** [price] of a member on [terms] from a seller whose [rival], trouble and [owner] are known. */
    internal fun price(terms: Terms?, now: Int, value: Int, rival: Boolean, sellerInTrouble: Boolean, owner: OwnerType): Price {
        val years = (terms?.until ?: now) - now + 1
        val asked = Valuation.asked(value, years, rival, sellerInTrouble, owner)
        val clause = terms?.clause ?: 0
        return if (clause in 1..asked) Price(clause, true) else Price(asked, false)
    }

    /** A wage offer must beat the current one by this much from a club placed more than one place below the seller. */
    const val RAISE = 1.3

    /** A release clause paid in full needs a raise of this much. */
    const val CLAUSE_RAISE = 1.1

    /**
     * Whether a member on [current] wage takes an [offer] (design ENG 3.2): at least its wage, and from a
     * club placed at most one place below its club ([buyerPlace], [sellerPlace]: club-table places) or
     * for [RAISE] × the wage; for a release clause paid in full, [CLAUSE_RAISE] × the wage.
     */
    fun accepts(offer: Int, current: Int, buyerPlace: Int, sellerPlace: Int, clause: Boolean = false): Boolean =
        if (clause) offer >= CLAUSE_RAISE * current
        else offer >= current && (buyerPlace.toLong() <= sellerPlace.toLong() + 1 || offer >= RAISE * current)

    /**
     * A contract's length in seasons (design ENG 1b.8) for a member aged [age] in its first season: 4
     * up to 21, 3 or 4 (by [key]'s hash) from 22 to 25, 3 from 26 to 29, 2 from 30 to 32, 1 from 33; 3
     * for a draft or academy signing ([draft]) and for an unknown age.
     */
    fun length(age: Int?, seed: Long, key: String, draft: Boolean = false): Int = when {
        draft || age == null -> 3
        age <= 21 -> 4
        age <= 25 -> 3 + Math.floorMod(DiceStream.mix(seed, LENGTH_SALT, key.hashCode().toLong()), 2L).toInt()
        age <= 29 -> 3
        age <= 32 -> 2
        else -> 1
    }

    /** Clubs of a league with λ below this give release clauses (and every 팬 소유 or 지역 기업 club). */
    const val CLAUSE_LAMBDA = 1.2
    const val CLAUSE_SHARE = 0.3

    /** Members valued 20억 or more there get one twice as often. */
    const val CLAUSE_STAR_VALUE = 200
    const val CLAUSE_STAR_SHARE = 0.6

    /**
     * The release clause (바이아웃, design ENG 1b.8) of member [key]'s contract signed at club [club] in
     * season [now] for a [value]: at a club of a league with λ below [CLAUSE_LAMBDA] or with a 팬 소유 or
     * 지역 기업 owner, 30% of the contracts (60% for a value of 20억 or more) get [Valuation.clause]; 0
     * otherwise (none). Drawn from the book's seed, the season and the key, so asked again it is the same.
     */
    fun clause(book: ClubBook, club: String, key: String, value: Int, lambda: Double, now: Int): Int {
        val owner = book.world?.economy?.owners?.get(club)?.type ?: OwnerType.LOCAL
        if (lambda >= CLAUSE_LAMBDA && owner != OwnerType.FANS && owner != OwnerType.LOCAL) return 0
        val rng = Random(DiceStream.mix(book.seed, CLAUSE_SALT, now.toLong(), key.hashCode().toLong()))
        val share = if (value >= CLAUSE_STAR_VALUE) CLAUSE_STAR_SHARE else CLAUSE_SHARE
        return if (rng.nextDouble() < share) Valuation.clause(value, rng) else 0
    }

    // ---- the coach's taste (ENG 2.5) ----------------------------------------------------------------

    /** A 멘탈 of this much counts as steady (안정형), and is a player's without an identity. */
    const val CALM = 50

    /**
     * What a coach of [philosophy] adds to a target's score (design ENG 2.5): 공격형 `0.25 × bold / 50 +
     * 0.15 × [요트파 or 과감파]`, 보너스형 `0.25 × side / 50 + 0.15 × [보너스파]`, 유망주 육성형 `0.30 × [age ≤ 22]
     * + 0.02 × (potential − overall)`, 안정형 `0.20 × [mental ≥ 50] + 0.15 × [신중파] − 0.10 × [a gambler's or
     * streaky overlay]`, 족보형 `0.15 × [족보파] + 0.10 × [side ≤ −10]`, 실리형 `0.15 × [정석파] + 0.20 ×
     * [efficiency]` (the z-score of its rating per 억 of the asked fee among the targets), 스타 영입형 `0.30 ×
     * [zRating]`; 0 without a coach.
     */
    fun fit(philosophy: Philosophy?, traits: PlayerTraits?, age: Int?, zRating: Double = 0.0, efficiency: Double = 0.0): Double {
        val f = traits?.family
        fun b(x: Boolean) = if (x) 1.0 else 0.0
        return when (philosophy) {
            null -> 0.0
            Philosophy.ATTACK -> 0.25 * (traits?.bold ?: 0) / 50.0 + 0.15 * b(f == StyleFamily.YACHT || f == StyleFamily.BOLD)
            Philosophy.BONUS -> 0.25 * (traits?.side ?: 0) / 50.0 + 0.15 * b(f == StyleFamily.BONUS)
            Philosophy.YOUTH -> 0.30 * b(age != null && age <= YOUTH_AGE) + 0.02 * room(traits)
            Philosophy.STABLE -> 0.20 * b((traits?.mental ?: CALM) >= CALM) + 0.15 * b(f == StyleFamily.CAUTION) - 0.10 * b(traits?.volatile == true)
            Philosophy.COMBO -> 0.15 * b(f == StyleFamily.COMBO) + 0.10 * b((traits?.side ?: 0) <= -10)
            Philosophy.PRAGMATIC -> 0.15 * b(f == StyleFamily.EV) + 0.20 * efficiency
            Philosophy.STAR -> 0.30 * zRating
        }
    }

    /** 유망주 육성형's young: 22 or younger. */
    const val YOUTH_AGE = 22

    /** A player's growth room: potential − overall (0 when unknown or none). */
    fun room(traits: PlayerTraits?): Int = maxOf(0, (traits?.potential ?: 0) - (traits?.overall ?: 0))

    // ---- the window's rules (ENG 3.2) ---------------------------------------------------------------

    /** A target's cost (asked / budget) weighs this much against its rating in the score. */
    const val COST_WEIGHT = 0.3

    /** Buys a club a window at most. */
    const val MAX_BUYS = 2

    /** A 스타 영입형 coach pays up to this much over the asked fee. */
    const val STAR_PREMIUM = 1.2

    /** Deadline day: clubs that lost this many counted members buy once more, at this much over the asked fee. */
    const val DEADLINE_LOST = 2
    const val DEADLINE_PREMIUM = 1.15

    /** Renewals: the young with this much growth room are kept, the old leave a club outside the 1부, a club in trouble keeps its best 3. */
    const val RENEW_AGE = 23
    const val RENEW_ROOM = 8
    const val OLD_AGE = 33
    const val TROUBLE_KEEP = 3

    /** A 투자 펀드 sells a member worth this much more than at signing, half the time. */
    const val FUND_RISE = 1.5
    const val FUND_SALE = 0.5

    /** Loans: the 2 most valuable clubs of a nation lend one member aged 21 or younger from outside their counted 6, ⌈C/4⌉ a nation. */
    const val LOAN_CLUBS = 2
    const val LOAN_AGE = 21

    /** Notable transfers a season (`tx`): 20 from 500 members, 6 below; kept 20 seasons. */
    const val TX_MAX = 20
    const val TX_SMALL = 6
    const val TX_MEMBERS = 500
    const val TX_SEASONS = 20

    /** The `tx` flags. */
    const val TX_FLAGS = "WLCVKDF"

    /** The last off-season's talks kept with their outcome. */
    const val OUTCOMES = 30

    /** The 8 best-ranked leagues keep a record fee. */
    const val RECORD_LEAGUES = 8

    /** A club with no place in a table (a new one) counts as placed here. */
    const val UNPLACED = 99

    /** Domestic deals a nation of [clubs] clubs makes in a window at most: `⌊C/2⌋ + ⌈C/4⌉`. */
    fun dealCap(clubs: Int): Int = clubs / 2 + (clubs + 3) / 4

    /** Loans out a nation of [clubs] clubs makes at most: `⌈C/4⌉`. */
    fun loanCap(clubs: Int): Int = (clubs + 3) / 4

    /**
     * What a member signed for: the value its wage asked at signing, read back from the wage and the
     * league's ω and the club's [factor] (`(wage / (ω × factor) − 0.35억) / 0.13`): the terms keep no
     * fee, and a 투자 펀드 sells a member worth [FUND_RISE] × that.
     */
    fun signedValue(wage: Int, omega: Double, factor: Double): Int =
        maxOf(Valuation.MIN_VALUE, Math.round((wage / maxOf(1e-9, omega * factor) - 3.5) / 0.13).toInt())

    /**
     * The off-season's transfer window after [closing] (design ENG 3.2, the club off-season's steps
     * 6-9), once per season (its `ms W` line is the guard: asked again, [report]), in order:
     * 1. **Loans back** (임대 복귀): last season's loans end; the loan club keeps a member now in its
     *    counted 6 when its budget pays the asked fee (완전 이적), else it goes home.
     * 2. **Contracts** (ENG 1b.8): members whose contract ends with [closing] are offered a renewal when
     *    in their club's counted 6 (the close's table) or 23 or younger with [RENEW_ROOM] of growth room
     *    (a club in 재정 위기: only its best 3), at the new wage (80% in trouble); a member refuses when
     *    underpaid with a story from a better club, or when 33 or older outside the 1부. The others
     *    become free agents (they leave their club now).
     * 3. **Returns and transfers abroad** ([Transfers.plan] with the money: talks for an abroad move
     *    first, the budget only as a filter, the asked fee capped at the budget, free agents as
     *    candidates by the abroad rule, the guard on every buyer).
     * 4. **Round 0, the talks** of the season, by id: done when the player is still at the seller (or a
     *    free agent from it), the buyer can pay and the player says yes; else collapsed with the reason
     *    (가격, 잔류, 감독 교체, 재정, 규정) or hijacked by the club it went to.
     * 5. **Round 1, parity**: in each nation the worst-placed club first (then the smaller budget, id)
     *    buys one member who would enter its counted 6: a free agent, a member of a club placed above it
     *    or in trouble, or anyone whose release clause it pays.
     * 6. **Forced sales** of clubs in 재정 위기 (at most 2, the most valuable, at 0.8 × value) to the
     *    domestic club with the biggest budget that can pay, else abroad (counting toward the abroad count).
     * 7. **Free agents** sign where the wage offered is best among clubs with room and the need.
     * 8. **Round 2, money**: 투자 펀드 sales first, then the biggest budget first, one more buy each from
     *    any club of its nation.
     * 9. **Deadline day**: a club that lost [DEADLINE_LOST] of its counted members buys once more at
     *    [DEADLINE_PREMIUM] × the asked fee.
     * 10. Free agents nobody signed go to the draft; **loans out**.
     * Every buy keeps the caps ([MAX_BUYS] a club, [dealCap] a nation), the spending cap and FFP (in the
     * budget, [ClubEconomy.transferBudget]), the wage rules ([ClubEconomy.wageFor], [ClubEconomy.wageRoom]),
     * the player's yes ([accepts]) and the guard ([allowed]); a buyer at its size ceiling lets its weakest
     * member go. Every fee is booked ([ClubEconomy.transfer]), every mover leaves its club through
     * [ClubHistory.leave] and joins through [ClubHistory.join] (contract, wage, clause, number); record
     * fees (the world's, the 8 best-ranked leagues', each club's) and the notable transfers (`tx`) are
     * kept, and the season's open stories make way for their outcomes. Moves are logged in order.
     */
    fun offseason(book: ClubBook, closing: SeasonPlan, input: MarketInput): MarketReport {
        val w = book.world ?: return MarketReport(closing.number)
        val s = closing.number
        if (book.format != ClubBook.AFFILIATION || !input.club.config.clubWorld) return MarketReport(s)
        if (w.market.windowSeason == s) return report(book, s)
        return MarketWindow(book, closing, input).run()
    }

    /**
     * The winter window (겨울 이적시장, design ENG 3.3) of [plan]'s season at its checkpoint, for P13's
     * checkpoint to call once (its guard and its `ws` lines are the checkpoint's): domestic only (a member
     * keeps its division and league, only its club changes), [input]'s tables are those through the
     * checkpoint's week. First the season's winter talks (done, collapsed or hijacked as in the
     * off-season); then in each nation the buyers chasing their board's [target] (`target − place ≤ −1`)
     * with a budget, or with fewer than 6 members, the furthest behind first (then the bigger budget, id),
     * buy one member who would enter their counted 6 from any other club of the nation, at most one deal
     * a club and [winterCap] a nation; the deals after the first [winterEarly] of a nation are deadline
     * deals (flag D). Clubs [frozen] (still in the 클럽 대항전, their squads drawn) neither buy nor sell.
     * Contracts start this season; fees, records, `tx` (type W) and terms as in the off-season; moves
     * are logged as [MarketReport.WINTER] moves of the season. Its report is not kept (the checkpoint keeps
     * its `ws` lines); nothing happens without a club world, in a club league's book or with `clubWorld` off.
     */
    fun winter(book: ClubBook, plan: SeasonPlan, input: MarketInput, target: (String) -> Int?, frozen: (String) -> Boolean = { false }): MarketReport {
        if (book.world == null || book.format != ClubBook.AFFILIATION || !input.club.config.clubWorld) return MarketReport(plan.number)
        return MarketWindow(book, plan, input, winter = true).runWinter(target, frozen)
    }

    /** Winter deals a nation of [clubs] clubs makes at most: `⌈C/4⌉`. */
    fun winterCap(clubs: Int): Int = (clubs + 3) / 4

    /** A nation's first `⌈C/8⌉` winter deals are made early; the rest are deadline deals. */
    fun winterEarly(clubs: Int): Int = (clubs + 7) / 8

    /** The window of the off-season after [season] as the book keeps it (its moves, the `ms W` line, the stories); empty when it did not run. */
    fun report(book: ClubBook, season: Int): MarketReport {
        val w = book.world ?: return MarketReport(season)
        val x = w.market.window?.takeIf { it.season == season } ?: return MarketReport(season)
        val moves = book.history.filter { it.season == season }.drop(x.offset)
        val deals = moves.zip(x.deals) { m, e -> MarketDeal(e.phase, e.type, m.ai, m.from, m.to, m.amount, e.flags, e.wage) }
        val stories = w.market.stories(season).filter { !it.winter && it.stage != StoryStage.RUMOUR && it.stage != StoryStage.TALKS }
        return MarketReport(season, deals, x.renewals, x.renewal, stories, x.record, x.records)
    }

    /** Salts of the market's draws. */
    internal const val LENGTH_SALT = 0x7E12L
    internal const val CLAUSE_SALT = 0x7E13L
    internal const val FUND_SALT = 0x7E14L
}

/**
 * One transfer window's working state (design ENG 3.2, 3.3): the fees received and paid per club (the
 * budgets read them), the buys per club and the deals per nation (the caps), the free agents, the
 * window's deals in order and its record fees. The off-season's market runs it after [closing] ([run]),
 * the winter checkpoint in the season [closing] is ([winter], [runWinter]); its rules are [Market]'s.
 */
internal class MarketWindow(val book: ClubBook, val closing: SeasonPlan, val input: MarketInput, private val winter: Boolean = false) {
    private val w = book.world!!
    private val s = closing.number

    /** The first season of a contract signed now: the next one, or this one in the winter. */
    private val now = if (winter) s else s + 1

    /** Clubs that neither buy nor sell in this window (the winter's 클럽 대항전 clubs). */
    private var frozen: (String) -> Boolean = { false }

    /** What a seller's price reads, fixed for the window (trouble flags, owners and rivals change only at the close's steps). */
    private val troubled = HashSet<String>()
    private val owners = HashMap<String, OwnerType>()
    private val rivals = HashSet<String>()
    private val ci = input.club
    private val ffp = ci.config.ffp
    private val rating = ci.rating
    private val order = ClubBook.ratingOrder(rating)
    private val alive = ci.members.toHashSet()

    /**
     * Each player's traits and market value, read once a window: both are fixed while it runs, and a
     * buyer's scoring asks for every member of its nation (the off-season's traits come from the genes).
     */
    private val traitsOf = HashMap<String, PlayerTraits?>()
    private val values = HashMap<String, Int>()
    private val valuer: (String) -> Int = input.value ?: book.valuer(rating, ci.age, { traits(it)?.potential }, { traits(it)?.overall })
    private val value: (String) -> Int = { k -> values.getOrPut(k) { valuer(k) } }
    private val honours = ci.honours.groupBy { it.key }

    private val deals = ArrayList<MarketDeal>()
    private val sales = HashMap<String, Int>()
    private val paid = HashMap<String, Int>()
    private val buys = HashMap<String, Int>()
    private val nationDeals = HashMap<String, Int>()

    /** Free agents → the club they left, their old wage. */
    private val free = LinkedHashMap<String, String>()
    private val freeWage = HashMap<String, Int>()

    /** Free agents who were their club's captain (their move carries the captain flag). */
    private val freeCaptains = HashSet<String>()
    private val moved = HashSet<String>()
    private var renewals = 0
    private var renewal: Renewal? = null
    private var abroadMade = 0
    private val abroadCount = Transfers.count(ci.config, ci.members.size)

    /** Record fees: the told candidates (deal index, scope, the record before it) and the leagues' records. */
    private val recordNews = ArrayList<RecordFee>()
    private val leagueRecords = TreeMap<String, Int>()
    private val topLeagues: Set<String>

    private val offset = book.history.count { it.season == s }
    private val leagueOf: Map<String, String> = Transfers.leagues(closing.league, book::clubOf, book::nationOf)
    private val place = HashMap<String, Int>()
    private val counted = HashMap<String, List<String>>()
    private val sizes = HashMap<String, Int>()

    /** Each nation's clubs (none is founded or dissolved during a window). */
    private val clubCount = HashMap<String, Int>()
    private val stats = HashMap<String, Pair<Double, Double>>()
    private val stars = ClubHistory.stars(book, rating)

    /** Each club's counted members by rating when the window opened (deadline day counts who left). */
    private val startCounted: Map<String, Set<String>>

    init {
        for ((_, table) in input.tables) for ((i, row) in table.withIndex()) {
            place[row.club] = i + 1
            counted[row.club] = row.counted
        }
        for (c in book.clubs) {
            sizes.merge(c.nation, c.squad.size, Int::plus)
            clubCount.merge(c.nation, 1, Int::plus)
            if ((ClubEconomy.troubleLevel(book, c.id) ?: 0) >= 2) troubled += c.id
            owners[c.id] = w.economy.owners[c.id]?.type ?: OwnerType.LOCAL
            for (r in book.rivals(c.id)) rivals += "${c.id} ${r.club}"
        }
        for (k in alive) if (book.clubOf(k) == null) sizes.merge(ClubBook.homeOf(ci.nation(k)), 1, Int::plus)
        for ((nation, cs) in book.clubs.groupBy { it.nation }) {
            val rs = cs.flatMap { it.squad }.map(rating).filter { it.isFinite() }
            val mean = if (rs.isEmpty()) 0.0 else rs.average()
            val sd = if (rs.size < 2) 0.0 else Math.sqrt(rs.sumOf { (it - mean) * (it - mean) } / rs.size)
            stats[nation] = mean to sd
        }
        startCounted = book.clubs.associate { c -> c.id to c.squad.sortedWith(order).take(ClubTable.COUNTED).toSet() }
        val before = book.rankLines.lastOrNull { it.season < s }?.leagueRecords.orEmpty()
        leagueRecords.putAll(before)
        topLeagues = closing.league.leagues.list.map { it.id }.filter { ci.ranking.rank(it) < Market.RECORD_LEAGUES }.toSet()
        // The season's winter fees kept (`tx` W lines) count toward the leagues' records.
        for (m in w.market.notable) if (m.season == s && m.type == MarketReport.WINTER && m.to != null) {
            val lg = leagueOfClub(m.to).takeIf { it in topLeagues } ?: continue
            if (m.fee > (leagueRecords[lg] ?: 0)) leagueRecords[lg] = m.fee
        }
    }

    /** The winter window ([Market.winter]): the winter's talks, then each nation's chasing buyers. */
    fun runWinter(target: (String) -> Int?, frozen: (String) -> Boolean): MarketReport {
        this.frozen = frozen
        talks()
        for (nation in nations()) {
            val clubs = book.clubsOf(nation)
            val early = Market.winterEarly(clubs.size)
            fun behind(c: Club): Int = target(c.id)?.let { placeOf(c.id) - it } ?: 0
            val chasing = clubs.filter { c -> !frozen(c.id) && ((behind(c) >= 1 && tb(c.id) > 0) || c.squad.size < ClubTable.COUNTED) }
                .sortedWith(compareByDescending<Club> { behind(it) }.thenByDescending { tb(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
            for (buyer in chasing) buy(buyer.id, MarketPhase.WINTER, { c -> !frozen(c) }, 1.0, if ((nationDeals[nation] ?: 0) >= early) "D" else "")
        }
        notable()
        book.touch()
        val stories = w.market.stories(s).filter { it.winter && it.stage != StoryStage.RUMOUR && it.stage != StoryStage.TALKS }
        return MarketReport(s, deals.toList(), stories = stories, record = told(), leagueRecords = TreeMap(leagueRecords))
    }

    fun run(): MarketReport {
        // A league that started over: the abandoned timeline's notable moves go (this season's winter ones stay).
        w.market.notable.removeIf { it.season > s || (it.season == s && it.type != MarketReport.WINTER) }
        loansBack()
        contracts()
        abroad()
        talks()
        for (nation in nations()) round(nation, MarketPhase.PARITY)
        for (nation in nations()) forced(nation)
        freeAgents()
        for (nation in nations()) round(nation, MarketPhase.MONEY)
        for (nation in nations()) deadline(nation)
        for ((k, from) in free.entries.toList()) if (k in alive) record(k, from, null, 0, MarketReport.RELEASE, MarketPhase.RELEASED, "", 0)
        free.clear()
        for (nation in nations()) loansOut(nation)
        notable()
        stories()
        w.market.window = WindowLine(s, offset, deals.map { WindowLine.Entry(it.phase, it.type, it.flags, it.wage) }, renewals, renewal, told(), TreeMap(leagueRecords))
        book.touch()
        return Market.report(book, s)
    }

    /** The record fee the news tells: the world record's biggest beating, else the biggest league record's (then the earlier deal). */
    private fun told(): RecordFee? = recordNews.filter { it.scope == WORLD }.maxByOrNull { deals[it.deal].fee }
        ?: recordNews.maxWithOrNull(compareBy<RecordFee> { deals[it.deal].fee }.thenBy { it.deal })

    // ---- reading the book ---------------------------------------------------------------------------

    /** Nations with 2 clubs or more, in [Nations.ALL]'s order. */
    private fun nations(): List<String> = Nations.ALL.map { it.code }.filter { (clubCount[it] ?: 0) >= 2 }

    private fun r(k: String): Double = rating(k).let { if (it.isNaN()) Double.NEGATIVE_INFINITY else it }

    private fun traits(k: String): PlayerTraits? = if (k in traitsOf) traitsOf[k] else input.traits(k).also { traitsOf[k] = it }

    private fun leagueOfClub(club: String): String = book.nationOf(club)?.let { leagueOf[it] }.orEmpty()

    private fun placeOf(club: String?): Int = club?.let { place[it] } ?: Market.UNPLACED

    private fun tb(club: String): Int = ClubEconomy.transferBudget(book, club, ffp, sales[club] ?: 0, paid[club] ?: 0, input.injection(club))

    private fun offer(key: String, club: String): Int = ClubEconomy.wageFor(book, club, value(key), leagueOfClub(club), ci.economy)

    private fun current(key: String): Int = w.terms[key]?.wage ?: freeWage[key] ?: 0

    private fun wageOk(club: String, wage: Int): Boolean = ClubEconomy.wageRoom(book, club, ffp)?.let { wage <= it } ?: true

    private fun embargo(club: String): Boolean = ClubEconomy.embargo(book, club, ffp)

    private fun trouble(club: String): Boolean = club in troubled

    /** [Market.price] with the window's fixed seller factors. */
    private fun price(key: String, seller: String, buyer: String): Market.Price =
        Market.price(w.terms[key], now, value(key), "$seller $buyer" in rivals, seller in troubled, owners[seller] ?: OwnerType.LOCAL)

    private fun maxSize(club: String): Int {
        val nation = book.nationOf(club) ?: return 0
        return ClubBook.maxSize(sizes[nation] ?: 0, clubCount[nation] ?: 0)
    }

    private fun minSize(club: String): Int {
        val nation = book.nationOf(club) ?: return 0
        return ClubBook.minSize(sizes[nation] ?: 0, clubCount[nation] ?: 0)
    }

    /** Whether club [club] may still buy: not under an embargo nor [frozen], and fewer than [Market.MAX_BUYS] buys (from abroad too; one in the winter). */
    private fun canBuy(club: String): Boolean = !embargo(club) && underCaps(club)

    /** Whether club [club] is under its buys cap and not [frozen] (an embargo aside). */
    private fun underCaps(club: String): Boolean = !frozen(club) && (buys[club] ?: 0) < if (winter) 1 else Market.MAX_BUYS

    /** Whether club [club] may still buy at home: [canBuy], and its nation under its deal cap ([Market.dealCap]; [Market.winterCap] in the winter). */
    private fun open(club: String): Boolean = !embargo(club) && homeCaps(club)

    /** [underCaps], and its nation under its deal cap (an embargo aside: talks tell it apart). */
    private fun homeCaps(club: String): Boolean {
        val nation = book.nationOf(club) ?: return false
        val clubs = clubCount[nation] ?: 0
        return underCaps(club) && (nationDeals[nation] ?: 0) < if (winter) Market.winterCap(clubs) else Market.dealCap(clubs)
    }

    /** The move type of a deal of [type]: a winter deal is a [MarketReport.WINTER] move whatever it is. */
    private fun typed(type: Char): Char = if (winter) MarketReport.WINTER else type

    // ---- making a move ------------------------------------------------------------------------------

    /**
     * Member [key] moves from [from] to [to] (null: out of every club) for [fee] as a move of [type]
     * in [phase] with [extra] flags, on a contract at [wage] (none when 0 or without a club): it leaves
     * its old club ([ClubHistory.leave], the captain flag), a full buyer lets its weakest member go, the
     * fee is booked with the record fees, it joins with new terms, and the move is logged.
     */
    private fun record(key: String, from: String?, to: String?, fee: Int, type: Char, phase: MarketPhase, extra: String, wage: Int): MarketDeal {
        val flags = HashSet<Char>()
        for (c in extra) flags += c
        if (from != null && book.clubOf(key) == from) {
            val dep = ClubHistory.leave(book, key, from, s, input.lines(key), honours[key].orEmpty(), retiring = false, major = ci.major)
            if (dep?.captain == true) flags += MarketReport.CAPTAIN
        }
        if (freeCaptains.remove(key) && to != null) flags += MarketReport.CAPTAIN
        if (to != null && book.clubOf(key) != to) {
            if (book.members(to).size >= maxSize(to)) makeRoom(to, phase)
            book.place(key, to)
        } else if (to == null) {
            book.place(key, null)
        }
        val index = deals.size
        if (fee > 0) {
            fees(key, from, to, fee, flags, index)
            ClubEconomy.transfer(book, to, from, fee, s)
            ClubHistory.fee(book, key, to, from, fee, s)
            to?.let { paid.merge(it, fee, Int::plus) }
            from?.let { sales.merge(it, fee, Int::plus) }
        }
        if (from != null && to != null && from != to && book.isRival(from, to)) flags += 'V'
        val signed = if (to != null && wage > 0) {
            val age = ci.age(key)
            val clause = Market.clause(book, to, key, value(key), ci.economy.lambdaOf(leagueOfClub(to)), now)
            ClubHistory.join(book, key, to, now, now + Market.length(age, book.seed, key) - 1, wage, key in stars, clause)
            wage
        } else 0
        val text = FLAG_ORDER.filter { it in flags }
        book.logMove(ClubMove(s, ClubMoveType.of(type) ?: ClubMoveType.TRANSFER, key, from, to, fee))
        val d = MarketDeal(phase, type, key, from, to, fee, text, signed)
        deals += d
        moved += key
        free.remove(key)
        return d
    }

    /** Club [club] is at its size ceiling: its weakest member (none who joined in this window, none on loan) goes to the draft. */
    private fun makeRoom(club: String, phase: MarketPhase) {
        val weakest = book.members(club).filter { it !in moved && w.terms[it]?.loanFrom == null }.sortedWith(order).lastOrNull() ?: return
        record(weakest, club, null, 0, MarketReport.RELEASE, phase, "", 0)
    }

    /** The record fees of a fee of [fee] for [key] into [to] (flags W, L, C; the first fee a scope ever sees sets its record silently). */
    private fun fees(key: String, from: String?, to: String?, fee: Int, flags: MutableSet<Char>, index: Int) {
        val world = w.worldRecord
        if (world == null || fee > world.fee) {
            if (world != null) {
                flags += 'W'
                recordNews += RecordFee(index, WORLD, world.fee)
            }
            w.worldRecord = FeeRecord(key, from, to, fee, s)
        }
        val lg = to?.let { leagueOfClub(it) }?.takeIf { it in topLeagues }
        if (lg != null) {
            val old = leagueRecords[lg]
            if (old == null || fee > old) {
                if (old != null) {
                    flags += 'L'
                    recordNews += RecordFee(index, lg, old)
                }
                leagueRecords[lg] = fee
            }
        }
        val signing = to?.let { w.history.records[it] }?.firstOrNull { it.kind == ClubRecordKind.SIGNING }?.value
        if (signing != null && fee > signing) flags += 'C'
    }

    /** Counts a buy for [club] and a deal for its nation. */
    private fun counted(club: String) {
        buys.merge(club, 1, Int::plus)
        book.nationOf(club)?.let { nationDeals.merge(it, 1, Int::plus) }
    }

    // ---- 1. loans back ------------------------------------------------------------------------------

    private fun loansBack() {
        for (c in book.clubs) for (k in c.squad.sorted()) {
            val t = w.terms[k] ?: continue
            val home = t.loanFrom ?: continue
            if (k !in alive) continue
            val keep = c.squad.sortedWith(order).take(ClubTable.COUNTED).contains(k) && book.club(home) != null
            val price = if (keep) price(k, home, c.id) else null
            val wage = offer(k, c.id)
            if (price != null && open(c.id) && price.fee <= tb(c.id) && wageOk(c.id, wage) && Market.accepts(wage, t.wage, placeOf(c.id), placeOf(home), price.clause)) {
                ClubHistory.leave(book, k, home, s, input.lines(k), honours[k].orEmpty(), retiring = false, major = ci.major)
                ClubHistory.loanBuy(book, k)
                record(k, home, c.id, price.fee, if (price.clause) MarketReport.CLAUSE else ClubMoveType.TRANSFER.code, MarketPhase.BACK, if (price.clause) "K" else "", wage)
                counted(c.id)
                continue
            }
            val back = ClubHistory.loanBack(book, k, k in stars) ?: continue
            book.logMove(ClubMove(s, ClubMoveType.TRANSFER, k, c.id, back, 0))
            deals += MarketDeal(MarketPhase.BACK, MarketReport.LOAN_BACK, k, c.id, back, 0, "", 0)
            moved += k
        }
    }

    // ---- 2. contracts -------------------------------------------------------------------------------

    private fun contracts() {
        val open = book.stories(s).filter { it.stage == StoryStage.RUMOUR || it.stage == StoryStage.TALKS }
        for (c in book.clubs) {
            val best = c.squad.sortedWith(order)
            val six = counted[c.id]?.takeIf { it.isNotEmpty() } ?: best.take(ClubTable.COUNTED)
            val inTrouble = trouble(c.id)
            for (k in c.squad.sorted()) {
                val t = w.terms[k] ?: continue
                if (t.until > s || t.loanFrom != null || k !in alive) continue
                val age = ci.age(k)
                val young = age != null && age <= Market.RENEW_AGE && Market.room(traits(k)) >= Market.RENEW_ROOM
                val offered = (k in six || young) && !(inTrouble && k !in best.take(Market.TROUBLE_KEEP))
                val wage = ClubEconomy.renewal(offer(k, c.id), inTrouble)
                if (offered && accepts(k, c.id, t, age, if (ci.market) open else emptyList())) {
                    val until = s + Market.length(age, book.seed, k)
                    w.terms[k] = t.copy(until = until, wage = wage, clause = Market.clause(book, c.id, k, value(k), ci.economy.lambdaOf(leagueOfClub(c.id)), now))
                    book.touch()
                    renewals++
                    val told = renewal
                    if (told == null || r(k) > r(told.key) || (r(k) == r(told.key) && k < told.key)) renewal = Renewal(k, c.id, until - s, wage)
                    continue
                }
                if (ClubHistory.leave(book, k, c.id, s, input.lines(k), honours[k].orEmpty(), retiring = false, major = ci.major)?.captain == true) freeCaptains += k
                book.place(k, null)
                free[k] = c.id
                freeWage[k] = t.wage
            }
        }
    }

    /** Whether member [key] of [club] on [t] takes the renewal: not when underpaid with a story from a better club, nor 33 or older outside the 1부. */
    private fun accepts(key: String, club: String, t: Terms, age: Int?, stories: List<MarketStory>): Boolean {
        if (age != null && age >= Market.OLD_AGE && closing.league.tierOf(key) != 0) return false
        val lg = leagueOfClub(club)
        if (!ClubEconomy.underpaid(t.wage, value(key), w.omega(lg))) return true
        return stories.none { st ->
            st.key == key && st.buyer != club && book.club(st.buyer) != null && run {
                val blg = leagueOfClub(st.buyer)
                if (book.nationOf(st.buyer) == book.nationOf(club)) placeOf(st.buyer) < placeOf(club)
                else ci.ranking.rank(blg) < ci.ranking.rank(lg)
            }
        }
    }

    // ---- 3. returns and transfers abroad ------------------------------------------------------------

    private fun abroad() {
        val pending = HashMap<String, MarketStory>()
        val abroadTalks = if (!ci.market) emptyList() else book.stories(s).filter { st ->
            st.stage == StoryStage.TALKS && !st.winter && book.nationOf(st.buyer) != null && book.nationOf(st.buyer) != book.nationOf(st.seller)
        }
        val money = object : TransferMoney {
            override fun budget(club: String): Int = if (canBuy(club)) tb(club) else 0
            override fun asked(key: String, buyer: String): Int {
                if (key in free) return 0
                val seller = book.clubOf(key) ?: return 0
                val p = price(key, seller, buyer)
                return if (p.clause) p.fee else premium(buyer, p.fee)
            }
            // The caps too: a free agent costs no fee, so the budget alone does not stop a club at its buys cap.
            override fun allows(key: String, buyer: String): Boolean =
                canBuy(buyer) && wageOk(buyer, offer(key, buyer)) && Market.allowed(book, key, buyer, rating)
            override val talks: List<Pair<String, String>> = abroadTalks.map { it.key to it.buyer }
            override val free: Map<String, String> = LinkedHashMap(this@MarketWindow.free)
            override fun talk(key: String, buyer: String, open: Boolean): Int? {
                val st = abroadTalks.first { it.key == key && it.buyer == buyer }
                if (!open && book.clubOf(key) != st.seller && this@MarketWindow.free[key] != st.seller) return null
                val (fee, reason) = decide(st, open && underCaps(buyer) && Market.allowed(book, key, buyer, rating))
                if (reason != null) {
                    end(st, reason)
                    return null
                }
                pending[key] = st
                return fee
            }
            override fun moved(t: Transfer) {
                val to = t.toClub.ifEmpty { null }
                val from = t.fromClub.ifEmpty { null }
                val wage = to?.let { offer(t.key, it) } ?: 0
                val freeAgent = t.key in this@MarketWindow.free
                val type = when {
                    t.home -> ClubMoveType.HOME.code
                    freeAgent -> MarketReport.FREE
                    else -> ClubMoveType.ABROAD.code
                }
                record(t.key, from, to, t.fee, type, MarketPhase.ABROAD, "", wage)
                if (!t.home) {
                    abroadMade++
                    to?.let { buys.merge(it, 1, Int::plus) }
                }
                pending.remove(t.key)?.let { st -> w.market.stories[w.market.stories.indexOf(st)] = st.copy(stage = StoryStage.DONE, fee = t.fee, reason = '-') }
            }
        }
        Transfers.plan(
            TransferInput(closing.league, ci.ranking, ci.members, rating, ci.age, ci.nation, ci.origin, book.clubs, ::maxSize, ci.config, money),
        )
    }

    /** [fee] as a buyer with a 스타 영입형 coach pays it: up to [Market.STAR_PREMIUM] × it, as far as its budget goes. */
    private fun premium(buyer: String, fee: Int): Int =
        if (book.coachOf(buyer)?.philosophy != Philosophy.STAR) fee else starFee(fee, tb(buyer))

    /** [premium] of a 스타 영입형 buyer whose budget is [budget]. */
    private fun starFee(fee: Int, budget: Int): Int = maxOf(fee, minOf(Math.round(fee * Market.STAR_PREMIUM).toInt(), budget))

    // ---- 4. the talks -------------------------------------------------------------------------------

    private fun talks() {
        if (!ci.market) return
        for (st in book.stories(s).filter { it.stage == StoryStage.TALKS && it.winter == winter }) {
            if (st.key !in alive || book.club(st.buyer) == null) {
                w.market.stories.remove(st)
                continue
            }
            val at = book.clubOf(st.key)
            val fromSeller = at == st.seller || free[st.key] == st.seller
            if (!fromSeller) {
                // Bought first by another club (the abroad step, a clause), or let go (잔류 is the nearest reason).
                when (at) {
                    st.buyer -> end(st, '-', StoryStage.DONE)
                    null -> end(st, 'S')
                    else -> end(st, 'H', StoryStage.HIJACKED, at)
                }
                continue
            }
            if (book.nationOf(st.buyer) != book.nationOf(st.seller) || frozen(st.seller)) {
                // An abroad talk the abroad step could not take (its caps; the winter is domestic), or a seller whose squad is drawn: 규정.
                end(st, 'R')
                continue
            }
            val (fee, reason) = decide(st, homeCaps(st.buyer) && Market.allowed(book, st.key, st.buyer, rating))
            if (reason != null) {
                end(st, reason)
                continue
            }
            val freeAgent = st.key in free
            val price = if (freeAgent) null else price(st.key, st.seller, st.buyer)
            val type = when {
                freeAgent -> MarketReport.FREE
                price?.clause == true -> MarketReport.CLAUSE
                else -> ClubMoveType.TRANSFER.code
            }
            record(st.key, st.seller, st.buyer, fee, typed(type), if (winter) MarketPhase.WINTER else MarketPhase.TALKS, if (price?.clause == true) "K" else "", offer(st.key, st.buyer))
            counted(st.buyer)
            w.market.stories[w.market.stories.indexOf(st)] = st.copy(stage = StoryStage.DONE, fee = fee, reason = '-')
        }
    }

    /**
     * Talks [st]: the fee the buyer pays and no reason, or the reason they fail, in this order: F 재정 (an
     * embargo), M 감독 교체 when the buyer's coach changed since the talks and fits the player less, R 규정
     * when not [open] (the caps, the buyer's ceiling or the guard), P 가격 (the budget), F 재정 (the wage
     * rules), S 잔류 (the player says no).
     */
    private fun decide(st: MarketStory, open: Boolean): Pair<Int, Char?> {
        val k = st.key
        val buyer = st.buyer
        if (embargo(buyer)) return 0 to 'F'
        if (coachChanged(st)) return 0 to 'M'
        if (!open) return 0 to 'R'
        val freeAgent = k in free
        val price = if (freeAgent) Market.Price(0, false) else price(k, st.seller, buyer)
        val fee = if (price.clause || freeAgent) price.fee else premium(buyer, price.fee)
        if (fee > tb(buyer)) return 0 to 'P'
        val wage = offer(k, buyer)
        if (!wageOk(buyer, wage)) return 0 to 'F'
        // Places of two nations' tables do not compare: abroad, the wage alone decides.
        val home = book.nationOf(buyer) == book.nationOf(st.seller)
        if (!Market.accepts(wage, current(k), if (home) placeOf(buyer) else 0, if (home) placeOf(st.seller) else 0, price.clause)) return 0 to 'S'
        return fee to null
    }

    /** Whether the buyer of [st] has a coach hired since the talks began who fits the player less than the one who wanted it. */
    private fun coachChanged(st: MarketStory): Boolean {
        val coach = book.coachOf(st.buyer) ?: return false
        val from = coach.current?.from ?: return false
        if (from <= st.season) return false
        val before = book.tenuresAt(st.buyer).firstOrNull { (id, t) -> id != coach.id && t.to >= st.season }?.let { (id, _) -> book.coach(id)?.philosophy }
        val traits = traits(st.key)
        val age = ci.age(st.key)
        return Market.fit(coach.philosophy, traits, age) < Market.fit(before, traits, age)
    }

    /** Story [st] ends at [stage] with [reason] (and the club that took the player, [by]). */
    private fun end(st: MarketStory, reason: Char, stage: StoryStage = StoryStage.COLLAPSED, by: String? = null) {
        val i = w.market.stories.indexOf(st)
        if (i >= 0) w.market.stories[i] = st.copy(stage = stage, reason = reason, by = by ?: st.by)
    }

    // ---- 5, 8, 9. the rounds ------------------------------------------------------------------------

    /** A target for a buyer: its seller (null: a free agent), the fee, whether a release clause sets it, its score. */
    private class Target(val key: String, val seller: String?, val fee: Int, val clause: Boolean, val rating: Double) {
        var score = 0.0
    }

    /**
     * One round in [nation]: round 1 the worst-placed club first (then the smaller budget, id), from
     * clubs placed above it or in trouble; round 2 the 투자 펀드 sales, then the biggest budget first,
     * from any club. One buy a club; the nation stops at its deal cap.
     */
    private fun round(nation: String, phase: MarketPhase) {
        val clubs = book.clubsOf(nation)
        val budgets = clubs.associate { it.id to tb(it.id) }
        val done = HashSet<String>()
        val buyers = if (phase == MarketPhase.PARITY) {
            clubs.sortedWith(compareByDescending<Club> { placeOf(it.id) }.thenBy { budgets.getValue(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
        } else {
            for (seller in clubs) fundSale(seller, clubs, budgets)?.let { done += it }
            clubs.sortedWith(compareByDescending<Club> { budgets.getValue(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
        }
        for (buyer in buyers) {
            if (buyer.id in done) continue
            val sellers: (String) -> Boolean = if (phase == MarketPhase.PARITY) {
                { c -> placeOf(c) < placeOf(buyer.id) || trouble(c) }
            } else {
                { true }
            }
            buy(buyer.id, phase, sellers, 1.0, "")
        }
    }

    /** Round 2's 투자 펀드 sale of club [seller] (ENG 1b.5): a member worth [Market.FUND_RISE] × its value at signing, half the time, to the biggest budget that buys it. The buyer, or null. */
    private fun fundSale(seller: Club, clubs: List<Club>, budgets: Map<String, Int>): String? {
        if (w.economy.owners[seller.id]?.type != OwnerType.FUND) return null
        val omega = w.omega(leagueOfClub(seller.id))
        val st = w.clubs[seller.id] ?: return null
        val factor = ClubEconomy.clubFactor(st.balance, ClubEconomy.income(book, seller.id), w.economy.owners[seller.id] ?: Owner.NONE)
        val key = book.members(seller.id).filter { k ->
            val t = w.terms[k] ?: return@filter false
            k !in moved && t.loanFrom == null && value(k) >= Market.FUND_RISE * Market.signedValue(t.wage, omega, factor) &&
                Random(DiceStream.mix(book.seed, Market.FUND_SALT, s.toLong(), k.hashCode().toLong())).nextDouble() < Market.FUND_SALE
        }.sortedWith(compareByDescending<String> { value(it) }.thenBy { it }).firstOrNull() ?: return null
        if (book.members(seller.id).size <= minSize(seller.id)) return null
        for (buyer in clubs.filter { it.id != seller.id }.sortedWith(compareByDescending<Club> { budgets.getValue(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))) {
            if (!open(buyer.id)) continue
            val floor = floor(buyer.id)
            if (r(key) <= floor) continue
            val price = price(key, seller.id, buyer.id)
            val fee = if (price.clause) price.fee else premium(buyer.id, price.fee)
            if (fee > tb(buyer.id)) continue
            val wage = offer(key, buyer.id)
            if (!wageOk(buyer.id, wage) || !Market.accepts(wage, current(key), placeOf(buyer.id), placeOf(seller.id), price.clause)) continue
            if (!Market.allowed(book, key, buyer.id, rating)) continue
            record(key, seller.id, buyer.id, fee, if (price.clause) MarketReport.CLAUSE else ClubMoveType.TRANSFER.code, MarketPhase.MONEY, if (price.clause) "K" else "", wage)
            counted(buyer.id)
            return buyer.id
        }
        return null
    }

    /** The rating a target must beat to enter club [club]'s counted 6 (−∞ with fewer than 6). */
    private fun floor(club: String): Double {
        val six = book.members(club).sortedWith(order).take(ClubTable.COUNTED)
        return if (six.size < ClubTable.COUNTED) Double.NEGATIVE_INFINITY else r(six.last())
    }

    /**
     * Club [buyer]'s best buy of [phase] (design ENG 3.2): among the members of its nation's other clubs
     * that [sellers] allows (the seller above its size floor), anyone whose release clause it pays, and
     * the free agents of its nation, each rated above its counted 6's last; the fee × [premium] (release
     * clauses and free agents at their price) within its budget; scored `z(rating within the nation) +
     * fit − 0.3 × z(fee / budget)`; the best one whose wage rules, yes and guard hold is bought.
     */
    private fun buy(buyer: String, phase: MarketPhase, sellers: (String) -> Boolean, premium: Double, extra: String): MarketDeal? {
        if (!open(buyer)) return null
        val nation = book.nationOf(buyer) ?: return null
        val budget = tb(buyer)
        val floor = floor(buyer)
        val philosophy = book.coachOf(buyer)?.philosophy
        // What it pays over the asked fee: the deadline's premium, else a 스타 영입형 coach's (the budget is the same for every target).
        fun pays(fee: Int): Int = when {
            premium > 1.0 -> Math.round(fee * premium).toInt()
            philosophy == Philosophy.STAR -> starFee(fee, budget)
            else -> fee
        }
        val targets = ArrayList<Target>()
        for (c in book.clubsOf(nation)) {
            if (c.id == buyer || frozen(c.id)) continue
            val allowed = sellers(c.id) && c.squad.size > minSize(c.id)
            for (k in c.squad) {
                if (k in moved || w.terms[k]?.loanFrom != null || !rating(k).isFinite() || r(k) <= floor) continue
                val p = price(k, c.id, buyer)
                val clause = w.terms[k]?.clause ?: 0
                val target = when {
                    allowed && p.clause -> Target(k, c.id, p.fee, true, r(k))
                    allowed -> Target(k, c.id, pays(p.fee), false, r(k))
                    clause > 0 -> Target(k, c.id, clause, true, r(k))
                    else -> null
                } ?: continue
                if (target.fee <= budget) targets += target
            }
        }
        for ((k, from) in free) {
            if (book.nationOf(from) != nation || from == buyer || k !in alive || !rating(k).isFinite() || r(k) <= floor) continue
            targets += Target(k, null, 0, false, r(k))
        }
        if (targets.isEmpty()) return null
        val (mean, sd) = stats[nation] ?: (0.0 to 0.0)
        val costs = Spread(targets.map { it.fee.toDouble() / maxOf(1, budget) })
        val efficiency = Spread(targets.map { it.rating / maxOf(it.fee, Valuation.MIN_ASKED) })
        for ((i, t) in targets.withIndex()) {
            val zr = z(t.rating, mean, sd)
            val fit = if (philosophy == null) 0.0 else Market.fit(philosophy, traits(t.key), ci.age(t.key), zr, efficiency.z(i))
            t.score = zr + fit - Market.COST_WEIGHT * costs.z(i)
        }
        targets.sortWith(compareByDescending<Target> { it.score }.thenByDescending { it.rating }.thenBy { it.key })
        val guard = Guard(buyer)
        for (t in targets) {
            val wage = offer(t.key, buyer)
            if (!wageOk(buyer, wage)) continue
            val from = t.seller ?: free.getValue(t.key)
            if (!Market.accepts(wage, current(t.key), placeOf(buyer), placeOf(from), t.clause)) continue
            if (!guard.ok(t.key)) continue
            val type = when {
                t.seller == null -> MarketReport.FREE
                t.clause -> MarketReport.CLAUSE
                else -> ClubMoveType.TRANSFER.code
            }
            val d = record(t.key, from, buyer, t.fee, typed(type), phase, (if (t.clause) "K" else "") + extra, wage)
            counted(buyer)
            return d
        }
        return null
    }

    private fun z(x: Double, mean: Double, sd: Double): Double = if (sd > 0) (x - mean) / sd else 0.0

    /**
     * [Market.allowed] for one buyer while its nation's clubs stay as they are now, without sorting
     * the nation for every member it looks at: a buyer under the cap may take anyone, and a member
     * rated below the nation's 16th best never enters the 16 (the buyer holds no more than before).
     */
    private inner class Guard(private val buyer: String) {
        private val open: Boolean
        private val sixteenth: Double

        init {
            val nation = book.nationOf(buyer)
            val members = nation?.let { n -> book.clubsOf(n).flatMap { c -> c.squad.map { it to c.id } } }.orEmpty()
            val top = Market.top(members) { r(it) }
            open = nation == null || top.count { it.second == buyer } < Market.cap(clubCount[nation] ?: 0)
            sixteenth = if (top.size < Market.GUARD_TOP) Double.NEGATIVE_INFINITY else r(top.last().first)
        }

        fun ok(key: String): Boolean = open || r(key) < sixteenth || Market.allowed(book, key, buyer, rating)
    }

    /** Values and their mean and spread: [z] of the [i]-th among them (0 for fewer than 2). */
    private class Spread(private val xs: List<Double>) {
        private val mean = if (xs.isEmpty()) 0.0 else xs.average()
        private val sd = if (xs.size < 2) 0.0 else Math.sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)

        fun z(i: Int): Double = if (sd > 0) (xs[i] - mean) / sd else 0.0
    }

    // ---- 6. forced sales ----------------------------------------------------------------------------

    /**
     * Clubs of [nation] in 재정 위기 sell their most valuable members (ENG 1.4, [ClubEconomy.forcedSales])
     * at 0.8 × value to the domestic club with the biggest budget that can pay, takes the player and
     * keeps the guard; else to a club of a better-ranked league abroad with room, while the window's
     * transfers abroad stay under their count.
     */
    private fun forced(nation: String) {
        for (c in book.clubsOf(nation)) {
            if (!trouble(c.id)) continue
            for (k in ClubEconomy.forcedSales(book, c.id, value)) {
                if (k in moved || book.clubOf(k) != c.id) continue
                val fee = Math.round(ClubEconomy.FORCED_PRICE * value(k)).toInt()
                val buyer = buyers(nation, c.id, k, fee) ?: abroadBuyer(c.id, k, fee)
                if (buyer == null) continue
                val abroad = book.nationOf(buyer) != nation
                record(k, c.id, buyer, fee, MarketReport.FORCED, MarketPhase.FORCED, "F", offer(k, buyer))
                if (abroad) {
                    abroadMade++
                    buys.merge(buyer, 1, Int::plus)
                } else {
                    counted(buyer)
                }
            }
        }
    }

    /** The domestic buyer of a forced sale: the biggest budget that pays [fee] and takes [key] (then id). */
    private fun buyers(nation: String, seller: String, key: String, fee: Int): String? =
        book.clubsOf(nation).filter { it.id != seller }.sortedWith(compareByDescending<Club> { tb(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
            .firstOrNull { b -> open(b.id) && fee <= tb(b.id) && signs(key, b.id) }?.id

    /** Whether club [buyer] can pay [key]'s wage under its rules, [key] takes it and the guard lets it in. */
    private fun signs(key: String, buyer: String): Boolean {
        val wage = offer(key, buyer)
        return wageOk(buyer, wage) && wage >= current(key) && Market.allowed(book, key, buyer, rating)
    }

    /** A forced sale abroad: the club with room and the biggest budget of the best-ranked league above the seller's that pays [fee]. */
    private fun abroadBuyer(seller: String, key: String, fee: Int): String? {
        if (abroadMade >= abroadCount) return null
        val own = ci.ranking.rank(leagueOfClub(seller))
        val nations = leagueOf.entries.filter { (_, lg) -> ci.ranking.rank(lg) < own }.sortedWith(compareBy<Map.Entry<String, String>> { ci.ranking.rank(it.value) }.thenBy { it.key })
        for ((nation, _) in nations) {
            val pick = book.clubsOf(nation).filter { it.squad.size < maxSize(it.id) && canBuy(it.id) && fee <= tb(it.id) && signs(key, it.id) }
                .sortedWith(compareByDescending<Club> { tb(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER)).firstOrNull()
            if (pick != null) return pick.id
        }
        return null
    }

    // ---- 7. free agents -----------------------------------------------------------------------------

    /**
     * Each free agent left (best rated first) signs with the club of its old club's nation that offers it
     * the most (then the better placed, id) among those with room, the need (it would enter their
     * counted 6, or they are below their nation's mean size), no embargo, the wage rules and the guard;
     * one a club. A free agent nobody wants waits for the rounds, then the draft.
     */
    private fun freeAgents() {
        val took = HashSet<String>()
        for (k in free.keys.sortedWith(order).toList()) {
            val from = free[k] ?: continue
            if (k !in alive) continue
            val nation = book.nationOf(from) ?: continue
            val clubs = book.clubsOf(nation)
            val mean = clubs.sumOf { it.squad.size }.toDouble() / maxOf(1, clubs.size)
            val best = clubs.filter { c ->
                c.id != from && c.id !in took && book.members(c.id).size < maxSize(c.id) && !embargo(c.id) &&
                    (r(k) > floor(c.id) || book.members(c.id).size < mean)
            }.map { it.id to offer(k, it.id) }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { placeOf(it.first) }.thenComparing({ it.first }, ClubBook.ID_ORDER))
                .firstOrNull { (c, wage) -> wageOk(c, wage) && Guard(c).ok(k) } ?: continue
            record(k, from, best.first, 0, MarketReport.FREE, MarketPhase.FREE, "", best.second)
            took += best.first
        }
    }

    // ---- 9. deadline day ----------------------------------------------------------------------------

    /** Clubs of [nation] that lost [Market.DEADLINE_LOST] of their counted members in the window buy once more at [Market.DEADLINE_PREMIUM] (flag D). */
    private fun deadline(nation: String) {
        val clubs = book.clubsOf(nation).sortedWith(compareByDescending<Club> { tb(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
        for (c in clubs) {
            val lost = startCounted[c.id].orEmpty().count { book.clubOf(it) != c.id }
            if (lost < Market.DEADLINE_LOST) continue
            buy(c.id, MarketPhase.DEADLINE, { true }, Market.DEADLINE_PREMIUM, "D")
        }
    }

    // ---- 10. loans out ------------------------------------------------------------------------------

    /**
     * The [Market.LOAN_CLUBS] most valuable clubs of [nation] with two members aged [Market.LOAN_AGE] or
     * younger outside their counted 6 lend the best rated of them to the lowest-placed club with room
     * (no embargo, the guard; one loan in a club), [Market.loanCap] a nation.
     */
    private fun loansOut(nation: String) {
        val clubs = book.clubsOf(nation)
        val cap = Market.loanCap(clubs.size)
        val lenders = clubs.sortedWith(compareByDescending<Club> { ClubEconomy.clubValue(book, it.id, value) ?: 0 }.thenComparing({ it.id }, ClubBook.ID_ORDER)).take(Market.LOAN_CLUBS)
        val took = HashSet<String>()
        var made = 0
        for (lender in lenders) {
            if (made >= cap) break
            val squad = book.members(lender.id).sortedWith(order)
            val young = squad.drop(ClubTable.COUNTED).filter { k -> (ci.age(k) ?: Int.MAX_VALUE) <= Market.LOAN_AGE && w.terms[k]?.loanFrom == null && k !in moved }
            if (young.size < 2) continue
            val k = young.first()
            val to = clubs.filter { it.id != lender.id && it.id !in took && book.members(it.id).size < maxSize(it.id) && !embargo(it.id) }
                .sortedWith(compareByDescending<Club> { placeOf(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
                .firstOrNull { Market.allowed(book, k, it.id, rating) } ?: continue
            if (!ClubHistory.loanOut(book, k, to.id, k in stars)) continue
            book.logMove(ClubMove(s, ClubMoveType.TRANSFER, k, lender.id, to.id, 0))
            deals += MarketDeal(MarketPhase.LOANS, MarketReport.LOAN, k, lender.id, to.id, 0, "", 0)
            moved += k
            took += to.id
            made++
        }
    }

    // ---- the window's records -----------------------------------------------------------------------

    /**
     * The season's notable transfers (`tx`, design ENG 3.2): every flagged move (a record, a rival, a
     * clause, a forced sale, deadline day), then the biggest fees, [Market.TX_MAX] a season ([Market.TX_SMALL]
     * below [Market.TX_MEMBERS] members) with the winter's already kept; in the window's order, the newest
     * [Market.TX_SEASONS] seasons kept.
     */
    private fun notable() {
        val market = w.market
        market.notable.removeIf { it.season <= s - Market.TX_SEASONS }
        val room = (if (ci.members.size >= Market.TX_MEMBERS) Market.TX_MAX else Market.TX_SMALL) - market.notable.count { it.season == s }
        if (room <= 0) return
        fun flagged(d: MarketDeal) = d.flags.any { it in Market.TX_FLAGS }
        val chosen = deals.withIndex().filter { (_, d) -> d.type in NOTABLE && d.to != null && (d.fee > 0 || flagged(d)) }
            .sortedWith(compareByDescending<IndexedValue<MarketDeal>> { flagged(it.value) }.thenByDescending { it.value.fee }.thenBy { it.index })
            .take(room).sortedBy { it.index }
        for ((_, d) in chosen) market.notable += NotableMove(s, d.type, d.key, d.from, d.to, d.fee, d.flags.filter { it in Market.TX_FLAGS })
    }

    /**
     * The season's stories after the window: rumours never escalated go without news, talks are decided,
     * the stories of earlier seasons go, and at most [Market.OUTCOMES] outcomes of the season stay.
     */
    private fun stories() {
        val st = w.market.stories
        st.removeIf { it.season < s || it.season > s || it.stage == StoryStage.RUMOUR || it.stage == StoryStage.TALKS }
        val kept = st.sortedByDescending { it.id }.take(Market.OUTCOMES).toSet()
        st.removeIf { it !in kept }
    }

    private companion object {
        const val WORLD = "W"

        /** Flags in the order they are written. */
        const val FLAG_ORDER = "WLCVKDFP"

        /** Moves a `tx` line may keep: transfers, abroad, free agents, clauses, forced sales, winter moves. */
        const val NOTABLE = "TFAKSW"
    }
}

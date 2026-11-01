package yacht.engine.league

/**
 * A winter move (겨울 이적, `ws`, design ENG 3.3): member [key] moved from club [from] to club [to]
 * at the checkpoint of week [week] of [season], holding [points] league points then, in a division
 * of [tier]. The club table credits the points before the move to [from] and the rest to [to]
 * ([ClubTable.of] with splits). [to] is "-" for a member its club let go to make room for a signing
 * (it plays the season on without a club).
 */
data class WinterMove(val season: Int, val key: String, val from: String, val to: String, val week: Int, val points: Int, val tier: Int)

/**
 * A sacking at a winter checkpoint (design ENG 2.3, 3.3): club [club] let coach [coach] go at
 * club-table [place] against the board's shown [target], and hired [hired] at once (null: nobody).
 */
data class WinterSacking(val club: String, val coach: String, val place: Int, val target: Int, val hired: String?)

/**
 * What the winter checkpoint of [season] did ([ClubCheckpoint.report]), rebuilt from the book: the
 * window's [deals] in order (all [MarketPhase.WINTER], move type [MarketReport.WINTER] or
 * [MarketReport.RELEASE] for a member let go to make room; flags as [MarketDeal.flags]), the record
 * fee its news tells, the [sacked] coaches and who came in, the clubs whose fans protested (club,
 * mood, coach then) and the winter's talks with their outcome ([stories]). Empty when it did not run.
 */
data class WinterReport(
    val season: Int,
    val week: Int = 0,
    val deals: List<MarketDeal> = emptyList(),
    val record: RecordFee? = null,
    val sacked: List<WinterSacking> = emptyList(),
    val protests: List<Protest> = emptyList(),
    val stories: List<MarketStory> = emptyList(),
) {
    /** Fans of [club] protesting at the checkpoint, at [mood], against coach [coach] (null: none). */
    data class Protest(val club: String, val mood: Int, val coach: String?)

    /** The deals that moved a member to another club (the releases left out). */
    val moves: List<MarketDeal> get() = deals.filter { it.to != null && it.type != MarketReport.RELEASE }

    /**
     * The checkpoint's news (design ENG 3.3, 11.2; templates are News.kt's), at most [MAX_NEWS], in
     * this order: `winter_window` [≤ 6 movers, biggest fee first] (deals, biggest ai, its from club, its
     * to club, fee) when a member moved; `coach_sacked` [club, coach, hired?] (place, target, far 0/1,
     * protest 0/1: the club's fans protested too) for the 2 biggest fan bases; `coach_digest` [≤ 6 clubs]
     * (sacked, hired, poached = 0, clubs) past 2 sackings; then 2 singles at most: the told record fee
     * (`record_fee` [ai, from, to] (fee, previous, scope W or a league id)), the other deals by fee as
     * `rival_move` [ai, from, to] (fee) for a move to a rival, `release_clause` [ai, from, to] (fee) for
     * a clause paid, else `transfer` [ai, from, to] (fee, talked 0/1: the talks came true, "W": a winter
     * move), and the biggest collapsed or hijacked winter talks (`deal_collapsed` [ai, buyer, seller,
     * hijacker?] (reason)); last `fan_protest` [club, coach?] (mood) for protests at clubs that kept
     * their coach (a sacked club's protest is folded into its `coach_sacked`). Clubs rank by fans in
     * [book] now, then id; every event carries the checkpoint's week.
     */
    fun events(book: ClubBook): List<LeagueEvent> {
        val w = book.world ?: return emptyList()
        val out = ArrayList<LeagueEvent>()
        fun event(kind: String, keys: List<String>, vararg args: String) {
            out += LeagueEvent(kind, season, keys, args.toList(), Comp.CLUB, week)
        }
        fun keys(d: MarketDeal) = listOf(d.key, d.from ?: "-", d.to ?: "-")
        val moved = moves
        val byFee = moved.withIndex().sortedWith(compareByDescending<IndexedValue<MarketDeal>> { it.value.fee }.thenBy { it.index }).map { it.value }
        byFee.firstOrNull()?.let { big ->
            event("winter_window", byFee.take(MarketReport.NAMED).map { it.key }, moved.size.toString(), big.key, big.from ?: "-", big.to ?: "-", big.fee.toString())
        }
        val fans = { club: String -> w.clubs[club]?.fans ?: 0 }
        val byFans = compareByDescending<WinterSacking> { fans(it.club) }.thenComparing({ it.club }, ClubBook.ID_ORDER)
        val protesting = protests.mapTo(HashSet()) { it.club }
        val sackings = sacked.sortedWith(byFans)
        for (m in sackings.take(CoachReport.SACKED)) {
            event(
                "coach_sacked", listOfNotNull(m.club, m.coach, m.hired),
                m.place.toString(), m.target.toString(), LineParts.bit(m.place - m.target >= CoachReport.FAR), LineParts.bit(m.club in protesting),
            )
        }
        if (sackings.size > CoachReport.SACKED) {
            val clubs = sackings.map { it.club }
            event("coach_digest", clubs.take(CoachReport.DIGEST_CLUBS), sackings.size.toString(), sackings.count { it.hired != null }.toString(), "0", clubs.size.toString())
        }
        val singles = ArrayList<LeagueEvent>()
        val recordDeal = record?.let { deals.getOrNull(it.deal) }
        if (record != null && recordDeal != null) {
            singles += LeagueEvent("record_fee", season, keys(recordDeal), listOf(recordDeal.fee.toString(), record.previous.toString(), record.scope), Comp.CLUB, week)
        }
        val talked = stories.filter { it.stage == StoryStage.DONE }.mapTo(HashSet()) { it.key }
        for (d in byFee) {
            if (singles.size >= SINGLES) break
            if (d === recordDeal) continue
            singles += when {
                'V' in d.flags -> LeagueEvent("rival_move", season, keys(d), listOf(d.fee.toString()), Comp.CLUB, week)
                'K' in d.flags -> LeagueEvent("release_clause", season, keys(d), listOf(d.fee.toString()), Comp.CLUB, week)
                else -> LeagueEvent("transfer", season, keys(d), listOf(d.fee.toString(), LineParts.bit(d.key in talked), WINTER_FLAG), Comp.CLUB, week)
            }
        }
        if (singles.size < SINGLES) {
            stories.filter { it.stage == StoryStage.COLLAPSED || it.stage == StoryStage.HIJACKED }
                .maxWithOrNull(compareBy<MarketStory> { it.fee }.thenByDescending { it.id })
                ?.let { s -> singles += LeagueEvent("deal_collapsed", season, listOfNotNull(s.key, s.buyer, s.seller, s.by), listOf(s.reason.toString()), Comp.CLUB, week) }
        }
        out += singles.take(SINGLES)
        val sackedClubs = sacked.mapTo(HashSet()) { it.club }
        for (p in protests.filter { it.club !in sackedClubs }) event("fan_protest", listOfNotNull(p.club, p.coach), p.mood.toString())
        return out.take(MAX_NEWS)
    }

    companion object {
        /** Articles a winter checkpoint tells at most (the winter week's budget, ENG 3.1). */
        const val MAX_NEWS = 5

        /** Single deals told (record fees, rival moves, clauses, transfers, a collapsed deal). */
        const val SINGLES = 2

        /** The last arg of a winter `transfer`: a move of the winter window. */
        const val WINTER_FLAG = "W"
    }
}

/**
 * The saved summary of the last winter checkpoint (a `ws C` line): what its report is rebuilt from:
 * where its moves start among the season's logged moves ([offset]), each move's phase, type, flags
 * and wage, the told record, the sackings and the protests.
 */
internal class WinterLine(
    val season: Int, val week: Int, val offset: Int, val deals: List<WindowLine.Entry>, val record: RecordFee?,
    val sacked: List<WinterSacking>, val protests: List<WinterReport.Protest>,
)

/**
 * The club world's winter part: the winter moves of the seasons kept (`ws`, the season in play's and
 * the one before) and the last checkpoint's summary (a `ws C` line).
 */
class WinterBook {
    internal val moves = ArrayList<WinterMove>()
    internal var line: WinterLine? = null

    /** The winter moves of [season], in the order they were made. */
    fun of(season: Int): List<WinterMove> = moves.filter { it.season == season }

    companion object {
        /** The summary line's second field (a winter move's is its season, a number). */
        private const val SUMMARY = "C"

        private val WS = LineParts.of<ClubWorld>("ws", { w, out ->
            for (m in w.winter.moves) out.append("ws ${m.season} ${Field.esc(m.key)} ${m.from} ${m.to} ${m.week} ${m.points} ${m.tier}\n")
            w.winter.line?.let { x ->
                out.append("ws $SUMMARY ${x.season} ${x.week} ${x.offset} ")
                out.append(LineParts.list(x.deals) { "${it.phase.code}${it.type}${it.flags}:${it.wage}" }).append(' ')
                out.append(x.record?.let { "${it.deal}:${Field.esc(it.scope)}:${it.previous}" } ?: "-").append(' ')
                out.append(LineParts.list(x.sacked) { "${it.club}:${it.coach}:${it.place}:${it.target}:${it.hired ?: "-"}" }).append(' ')
                out.append(LineParts.list(x.protests) { "${it.club}:${it.mood}:${it.coach ?: "-"}" }).append('\n')
            }
        }) { w, f ->
            if (f[1] == SUMMARY) {
                require(f.size >= 9)
                val deals = LineParts.items(f[5]).map { e ->
                    val (head, wage) = e.split(':')
                    WindowLine.Entry(MarketPhase.of(head[0])!!, head[1], head.substring(2), wage.toInt())
                }
                val record = f[6].takeIf { it != "-" }?.split(':')?.let { p -> RecordFee(p[0].toInt(), Field.unesc(p[1]), p[2].toInt()) }
                val sacked = LineParts.items(f[7]).map { e ->
                    val p = e.split(':')
                    WinterSacking(p[0], p[1], p[2].toInt(), p[3].toInt(), p[4].takeIf { it != "-" })
                }
                val protests = LineParts.items(f[8]).map { e ->
                    val p = e.split(':')
                    WinterReport.Protest(p[0], p[1].toInt(), p[2].takeIf { it != "-" })
                }
                w.winter.line = WinterLine(f[2].toInt(), f[3].toInt(), f[4].toInt(), deals, record, sacked, protests)
                return@of true
            }
            require(f.size >= 8)
            w.winter.moves += WinterMove(f[1].toInt(), Field.unesc(f[2]), f[3], f[4], f[5].toInt(), f[6].toInt(), f[7].toInt())
            true
        }

        /** `ws`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(WS)
    }
}

/**
 * The winter checkpoint (겨울 이적시장, design ENG 3.3): after the market's tick of week `W = ⌊R / 2⌋ +
 * 1` ([ClubWorld.winterWeek]), once a season, on the club tables through week W and the ratings the
 * season was drawn with, so a checkpoint replayed after a kill (or after next week's fixtures were
 * played early) decides the same. [ClubBook.checkpoint] calls it with the rollback around it.
 */
object ClubCheckpoint {
    /** Seasons whose winter moves (`ws`) are kept: the season in play's and the one before. */
    const val KEEP_SEASONS = 2

    /**
     * The winter checkpoint of [plan]'s season after its week [week] closed, its news ([WinterReport.events]);
     * nothing in a legacy season or for another week than [ClubWorld.winterWeek]. Once a season (the
     * guard [ClubWorld.checkpoint], set last; a season whose week W closed before the club world began
     * has it set already and skips): asked again it changes nothing and returns the news rebuilt. In
     * order, on the club tables through week W (the season's league points only, with the season's
     * deductions):
     * 1. **Fans' mood** of every club with a seat: the gap to the board's shown target and the points of
     *    the last off-season's window (stars sold, a record signing, members gone to a rival:
     *    [MarketReport.moodEvents], stars = the top 1% by the drawn ratings) and the relief of a coach
     *    sacked at the close before (+5); the close then counts the window no more.
     * 2. **The board's review** ([CoachMarket.review], winter) and an immediate hire for each sacking
     *    ([CoachMarket.hire]).
     * 3. **The winter window** ([Market.winter]: its talks first, then each nation's chasing clubs), on the
     *    drawn ratings, the board's targets, the 클럽 대항전's clubs frozen; a `ws` line per move with the
     *    member's league points through W and its tier; winter rumours never turned into talks end
     *    without news.
     * 4. The protests (fans' mood below 25, the 2 biggest fan bases), the summary line, the guard; when
     *    the winter's moves took the move log past its cap, the book as its file reads back (the oldest
     *    moves trimmed; [ClubBook.world] is then a new object: read it again after a checkpoint).
     * The world events' winter stages run after it, on the world book ([Stories.close]'s hook).
     */
    fun run(book: ClubBook, plan: SeasonPlan, input: ClubInput, week: Int): List<LeagueEvent> {
        val w = book.world ?: return emptyList()
        val season = plan.league
        if (!season.leagues.national || week != ClubWorld.winterWeek(plan)) return emptyList()
        val s = plan.number
        if (w.checkpoint >= s) return report(book, s).events(book)
        val drawn = input.drawn
        val steady = drawnInput(input)
        val tables = ClubTable.all(season, book, emptyList(), week, book.winterMoves(s), ClubEconomy.deductions(book))
        val seats = ClubEconomy.seats(tables)
        val targets = CoachMarket.targets(plan, book, drawn)
        // 1. The fans' mood.
        val window = Market.report(book, s - 1)
        val members = book.clubs.flatMap { it.squad }
        val stars = members.sortedWith(ClubBook.ratingOrder(drawn)).take(Math.ceil(members.size / 100.0).toInt()).toHashSet()
        val relieved = CoachMarket.report(book, s - 1).of('S').mapNotNullTo(HashSet()) { it.club }
        for ((id, seat) in seats.entries.sortedWith(compareBy(ClubBook.ID_ORDER) { it.key })) {
            val target = targets[id] ?: seat.place
            val points = window.moodEvents(id) { it in stars } + ClubEconomy.moodEvents(sacked = id in relieved)
            ClubEconomy.mood(book, id, ClubEconomy.gap(target, seat.place, seat.clubs), points)
        }
        // 2. The board's review and the hires.
        val sacked = CoachMarket.review(book, plan, tables, drawn, winter = true).map { m ->
            val club = m.club!!
            WinterSacking(club, m.coach, m.place, m.target, CoachMarket.hire(book, club, s, steady, plan.seed)?.id)
        }
        // 3. The winter window, on the drawn ratings throughout.
        val cup = (plan.competition(Comp.CLUB_CUP) as? TeamCup)?.teams?.mapTo(HashSet()) { it.id }.orEmpty()
        val offset = book.history.count { it.season == s }
        val market = Market.winter(book, plan, MarketInput(steady, tables, input.traits, lines = input.lines), { targets[it] }, { it in cup })
        val through = season.pointsThrough(week - 1)
        for (d in market.deals) {
            val from = d.from ?: continue
            val points = through[d.key]?.get(Season.THROUGH_POINTS) ?: 0
            w.winter.moves += WinterMove(s, d.key, from, d.to ?: NONE, week, points, season.tierOf(d.key) ?: 0)
        }
        w.winter.moves.removeIf { it.season <= s - KEEP_SEASONS || it.season > s }
        w.market.stories.removeIf { it.season == s && it.winter && it.stage == StoryStage.RUMOUR }
        // 4. The protests, the summary and the guard.
        val protests = ClubEconomy.protests(book, seats.keys).map { WinterReport.Protest(it, w.clubs[it]?.mood ?: 0, w.clubs[it]?.coach) }
        val entries = market.deals.map { WindowLine.Entry(it.phase, it.type, it.flags, it.wage) }
        w.winter.line = WinterLine(s, week, offset, entries, market.record, sacked, protests)
        w.checkpoint = s
        book.touch()
        // The winter's moves can take the move log past its cap, which a read-back trims (its oldest seasons go):
        // the book is then left as its file reads, so one kept in memory and one read back after a kill write the same.
        val log = book.history
        if (log.size > ClubBook.historyCap(book.size) && log.first().season < book.season) book.restore(book.encode())
        return report(book, s).events(book)
    }

    /** The winter checkpoint of [season] as the book keeps it (the `ws C` line, the season's moves and its winter stories); empty when it did not run. */
    fun report(book: ClubBook, season: Int): WinterReport {
        val w = book.world ?: return WinterReport(season)
        val x = w.winter.line?.takeIf { it.season == season } ?: return WinterReport(season)
        val moves = book.history.filter { it.season == season }.drop(x.offset)
        val deals = moves.zip(x.deals) { m, e -> MarketDeal(e.phase, e.type, m.ai, m.from, m.to, m.amount, e.flags, e.wage) }
        val stories = w.market.stories(season).filter { it.winter && it.stage != StoryStage.RUMOUR && it.stage != StoryStage.TALKS }
        return WinterReport(season, x.week, deals, x.record, x.sacked, x.protests, stories)
    }

    /** A winter move's [WinterMove.to] for a member let go (no club). */
    const val NONE = "-"

    /** [input] with the ratings the season was drawn with as its ratings: the window reads nothing that moves with the matches. */
    private fun drawnInput(input: ClubInput): ClubInput = ClubInput(
        input.members, input.drawn, input.nation, input.config, input.honours, age = input.age, origin = input.origin, mentor = input.mentor,
        ranking = input.ranking, major = input.major, economy = input.economy, arcs = input.arcs, drawn = input.drawn, coachInput = input.coachInput,
        families = input.families, coaches = input.coaches, market = input.market, winter = input.winter, lines = input.lines,
        hometown = input.hometown, potential = input.potential, overall = input.overall, traits = input.traits,
    )
}

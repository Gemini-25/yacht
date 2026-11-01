package yacht.engine.league

import yacht.engine.DiceStream
import java.util.Random

/**
 * The transfer market's weekly tick (이적설, design ENG 3.1): once per closed week of a national season
 * of a book with a club world, from week `⌈0.6 × R⌉` to the league's end and in the winter window's
 * two weeks before the checkpoint (`W − 2 .. W − 1`, [ClubWorld.winterWeek]), new rumours (up to 2 a
 * week world-wide, 1 below [SMALL] members) and rumours turned into talks; [close] runs every week a
 * close of the season covers, in order, with the winter checkpoint ([ClubBook.checkpoint]) after the
 * tick of week W. Its inputs are the book and the ratings the season was drawn with
 * ([ClubInput.drawn]) only, so a week closed again after a kill, or after next week's fixtures were
 * played early, decides the same. The guard is the `cw` line's `marketSeason:marketWeek`: a week
 * ticked already returns its news rebuilt from the stories ([events]). The talks are decided by the
 * market: the winter's at the checkpoint ([Market.winter]), the others at the off-season (round 0 of
 * [Market.offseason]); a rumour never turned into talks goes without news.
 */
object Stories {
    /** Rumours start in the week `⌈0.6 × R⌉` of a season of R league weeks. */
    const val START = 0.6

    /** The winter window's rumours start this many weeks before the checkpoint's week. */
    const val WINTER_WEEKS = 2

    /** New rumours a week world-wide ([RUMOURS_SMALL] below [SMALL] members). */
    const val RUMOURS = 2
    const val RUMOURS_SMALL = 1
    const val SMALL = 500

    /** A rumour turns into talks with this chance, [ESCALATE_AFTER] + 0..[ESCALATE_SPREAD] − 1 weeks after it began (a winter one in the checkpoint's week). */
    const val ESCALATE = 0.45
    const val ESCALATE_AFTER = 2
    const val ESCALATE_SPREAD = 3

    /** A buyer's transfer budget covers this much of the asked fee. */
    const val BUDGET_SHARE = 0.8

    /** Candidates: the best rated of their league's 1부 whose club is not among its league's most valuable. */
    const val TOP = 3
    const val VALUE_TOP = 2

    /** `market_week` articles a season at most (one a week, in the weeks with new talks). */
    const val WEEKS_TOLD = 12

    /** The salts of the week's rumours and of each story's escalation. */
    const val SALT = 0x7A1CL
    const val ESCALATE_SALT = 0x7A1DL
    const val ESCALATE_WEEK_SALT = 0x7A1EL

    /** The first week of the season's rumours: `⌈0.6 × R⌉`. */
    fun firstWeek(plan: SeasonPlan): Int = Math.ceil(START * plan.leagueWeeks - 1e-9).toInt().coerceAtLeast(1)

    /** The winter window's rumour weeks: `W − 2 .. W − 1` (from week 1). */
    fun winterWeeks(plan: SeasonPlan): IntRange = maxOf(1, ClubWorld.winterWeek(plan) - WINTER_WEEKS) until ClubWorld.winterWeek(plan)

    /** Whether the close of week [week] brings new rumours: a winter week, or from [firstWeek] (after the checkpoint's week) to the league's last. */
    fun rumourWeek(plan: SeasonPlan, week: Int): Boolean =
        week in winterWeeks(plan) || week in maxOf(firstWeek(plan), ClubWorld.winterWeek(plan) + 1)..plan.leagueWeeks

    /**
     * Every week from [from] until [now] (the weeks a close of [plan]'s season covers, `desk.week until
     * now`) in order: its tick ([week]) and, after the tick of the winter week ([ClubWorld.winterWeek]),
     * the winter checkpoint ([ClubBook.checkpoint]) and then [winter] (the world events' winter stages
     * and the World Cup bids, whose guard and state are the world book's). Their news, in that order. A
     * tick that fails changes nothing and tells nothing.
     */
    fun close(
        book: ClubBook,
        plan: SeasonPlan,
        input: ClubInput,
        from: Int,
        now: Int,
        winter: () -> List<LeagueEvent> = { emptyList() },
    ): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        val w = ClubWorld.winterWeek(plan)
        for (k in maxOf(1, from) until now) {
            out += runCatching { week(book, plan, input, k) }.getOrDefault(emptyList())
            if (k == w) {
                out += book.checkpoint(plan, input, k)
                out += runCatching(winter).getOrDefault(emptyList())
            }
        }
        return out
    }

    /**
     * The tick of week [week] of [plan]'s season (design ENG 3.1), its news (`market_week`, [events]).
     * Once per week in order (the guard: a week at or before the last ticked returns its news rebuilt
     * and changes nothing); nothing without a club world, in a club league's book or a legacy season,
     * with [LeagueConfig.clubWorld] off or the test knob [ClubInput.market] off. In order:
     * 1. Rumours whose player left the seller (the winter window) or the league, or whose buyer is gone,
     *    are dropped (talks are left to their decision).
     * 2. Rumours due this week ([escalation]) turn into talks with chance [ESCALATE] (the rest fade: they
     *    stay rumours and end without news).
     * 3. In a [rumourWeek], new rumours, picked by `Random(mix(seed, 0x7A1C, season, week))` weighted by
     *    the player's value from the candidates without an open story ([candidates]) that have a buyer
     *    ([buyer]); a winter week's are the winter window's (domestic only, decided at the checkpoint).
     */
    fun week(book: ClubBook, plan: SeasonPlan, input: ClubInput, week: Int): List<LeagueEvent> {
        val w = book.world ?: return emptyList()
        if (book.format != ClubBook.AFFILIATION || !plan.league.leagues.national || !input.config.clubWorld || !input.market) return emptyList()
        val s = plan.number
        if (w.marketSeason > s || (w.marketSeason == s && week <= w.marketWeek)) return events(book, plan, week)
        // Everything is decided first and written at the end, so a failure changes nothing.
        val stories = w.market.stories
        val season = plan.league
        val stale = stories.filter { st ->
            st.season == s && st.stage == StoryStage.RUMOUR &&
                (book.clubOf(st.key) != st.seller || season.divisionOf(st.key) == null || book.club(st.buyer) == null)
        }.toHashSet()
        val talks = HashMap<Int, MarketStory>()
        for (st in stories) {
            if (st.season != s || st.stage != StoryStage.RUMOUR || st in stale || escalation(book, plan, st) != week) continue
            if (Random(DiceStream.mix(book.seed, ESCALATE_SALT, s.toLong(), st.id.toLong())).nextDouble() < ESCALATE) talks[st.id] = st.copy(stage = StoryStage.TALKS, talksWeek = week)
        }
        val made = if (rumourWeek(plan, week)) Tick(book, plan, input, week, stale).rumours() else emptyList()
        if (stale.isEmpty() && talks.isEmpty() && made.isEmpty()) {
            w.marketSeason = s
            w.marketWeek = week
            return events(book, plan, week)
        }
        stories.removeAll(stale)
        for (i in stories.indices) talks[stories[i].id]?.takeIf { stories[i].season == s }?.let { stories[i] = it }
        for (st in made) {
            stories += st.copy(id = w.nextStory)
            w.nextStory++
        }
        w.marketSeason = s
        w.marketWeek = week
        book.touch()
        return events(book, plan, week)
    }

    /**
     * The week story [st] may turn into talks: a winter story in the checkpoint's week (the window
     * closes then), any other [ESCALATE_AFTER] + 0..2 weeks after its rumour began (by its id); a week
     * past the league's last never comes.
     */
    fun escalation(book: ClubBook, plan: SeasonPlan, st: MarketStory): Int =
        if (st.winter) ClubWorld.winterWeek(plan)
        else st.rumourWeek + ESCALATE_AFTER + Math.floorMod(DiceStream.mix(book.seed, ESCALATE_WEEK_SALT, st.season.toLong(), st.id.toLong()), ESCALATE_SPREAD.toLong()).toInt()

    /** The open stories (rumours and talks) of [season], by id. */
    fun open(book: ClubBook, season: Int): List<MarketStory> =
        book.stories(season).filter { it.stage == StoryStage.RUMOUR || it.stage == StoryStage.TALKS }

    /**
     * The news of week [week]'s tick of [plan]'s season, rebuilt from the stories (design ENG 3.1, 11.2):
     * one `market_week` [ai, buyer, seller] (week, fee, talks) naming the week's biggest new talks (then
     * the first story) with the week's number of new talks, in a week that had some, for the season's
     * first [WEEKS_TOLD] such weeks. The winter's talks are the checkpoint's news.
     */
    fun events(book: ClubBook, plan: SeasonPlan, week: Int): List<LeagueEvent> {
        val s = plan.number
        val talked = book.stories(s).filter { !it.winter && it.talksWeek > 0 }
        val now = talked.filter { it.talksWeek == week }
        if (now.isEmpty()) return emptyList()
        if (talked.filter { it.talksWeek < week }.map { it.talksWeek }.distinct().size >= WEEKS_TOLD) return emptyList()
        val best = now.maxWith(compareBy<MarketStory> { it.fee }.thenByDescending { it.id })
        return listOf(
            LeagueEvent("market_week", s, listOf(best.key, best.buyer, best.seller), listOf(week.toString(), best.fee.toString(), now.size.toString()), Comp.CLUB, week),
        )
    }

    /** One week's new rumours (design ENG 3.1): who may be talked about, by whom, and the draw. */
    private class Tick(val book: ClubBook, val plan: SeasonPlan, val input: ClubInput, val week: Int, stale: Set<MarketStory>) {
        val w = book.world!!
        val s = plan.number
        val season = plan.league
        val winter = week in winterWeeks(plan)
        val drawn = input.drawn
        val order = ClubBook.ratingOrder(drawn)
        val values = HashMap<String, Int>()
        val valuer = book.valuer(drawn, input.age, input.potential, input.overall)
        val leagueOf: Map<String, String> = Transfers.leagues(season, book::clubOf, book::nationOf)
        val open: Set<String> = w.market.stories.filter { it.season == s && (it.stage == StoryStage.RUMOUR || it.stage == StoryStage.TALKS) && it !in stale }.mapTo(HashSet()) { it.key }

        /** The 클럽 대항전's clubs: their squads were drawn, so the winter window neither buys from nor sells to them. */
        val cup: Set<String> = if (!winter) emptySet() else (plan.competition(Comp.CLUB_CUP) as? TeamCup)?.teams?.mapTo(HashSet()) { it.id }.orEmpty()

        /** Stories with a buyer abroad the season may still start: the off-season's transfers abroad ([Transfers.count]) less those it has; none in the winter. */
        var abroadRoom = if (winter) 0 else Transfers.count(input.config, input.members.size) -
            w.market.stories.count { it.season == s && book.nationOf(it.buyer) != book.nationOf(it.seller) }

        /** A contract the move would start: next season's, or this one's in the winter. */
        val now = if (winter) s else s + 1

        /** Each club's rating to beat to enter its counted 6 (−∞ with fewer than 6), and each nation's size and clubs. */
        val floors = HashMap<String, Double>()
        val sizes = HashMap<String, Int>()
        val clubCount = HashMap<String, Int>()

        init {
            for (c in book.clubs) {
                val six = c.squad.sortedWith(order).take(ClubTable.COUNTED)
                floors[c.id] = if (six.size < ClubTable.COUNTED) Double.NEGATIVE_INFINITY else r(six.last())
                sizes.merge(c.nation, c.squad.size, Int::plus)
                clubCount.merge(c.nation, 1, Int::plus)
            }
        }

        fun r(k: String): Double = drawn(k).let { if (it.isNaN()) Double.NEGATIVE_INFINITY else it }

        fun value(k: String): Int = values.getOrPut(k) { valuer(k) }

        fun tb(club: String): Int = ClubEconomy.transferBudget(book, club, input.config.ffp)

        /** The new rumours of the week, ids still to be given (0). */
        fun rumours(): List<MarketStory> {
            val pool = candidates().toMutableList()
            val rng = Random(DiceStream.mix(book.seed, SALT, s.toLong(), week.toLong()))
            val n = if (book.size < SMALL) RUMOURS_SMALL else RUMOURS
            val out = ArrayList<MarketStory>()
            val buyers = HashSet<String>()
            while (out.size < n && pool.isNotEmpty()) {
                val total = pool.sumOf { maxOf(1, value(it.key)).toLong() }
                var x = (rng.nextDouble() * total).toLong()
                var i = 0
                while (i < pool.size - 1) {
                    x -= maxOf(1, value(pool[i].key))
                    if (x < 0) break
                    i++
                }
                val c = pool.removeAt(i)
                if (c.abroad && abroadRoom <= 0) continue
                // A club starts one story a week at most.
                val (buyer, fee) = buyer(c, buyers) ?: continue
                buyers += buyer
                if (c.abroad) abroadRoom--
                out += MarketStory(0, s, StoryStage.RUMOUR, c.key, buyer, c.club, fee, week, 0, '-', winter, null)
            }
            return out
        }

        /**
         * The week's candidates, by key (design ENG 3.1): members of a club who play the season, are not on
         * loan and have no open story, who are underpaid ([ClubEconomy.underpaid]), or among the [TOP] best
         * rated of their league's 1부 at a club outside its league's [VALUE_TOP] most valuable; and outside
         * the winter the abroad candidates of [Transfers] (among the [TOP] of a 1부, aged
         * [Transfers.MIN_AGE]..[Transfers.MAX_AGE] in the coming season, generated or evolved, at home, with a
         * league ranked [Transfers.GAP] places above whose 1부 median it reaches) while the season's stories
         * with a buyer abroad are fewer than the off-season's transfers abroad ([Transfers.count]). A winter
         * week leaves out the 클럽 대항전's clubs.
         */
        fun candidates(): List<Candidate> {
            val tops = HashMap<String, List<String>>()
            for (lg in season.leagues.list) tops[lg.id] = season.divisions[lg.top].filter { book.clubOf(it) != null }.sortedWith(order).take(TOP)
            // Each league's clubs (those of the nations playing in it) by club value.
            val clubValue = HashMap<String, Int>()
            val leagueClubs = HashMap<String, MutableList<String>>()
            for ((nation, lg) in leagueOf) for (c in book.clubsOf(nation)) {
                clubValue[c.id] = ClubEconomy.clubValue(book, c.id, ::value) ?: 0
                leagueClubs.getOrPut(lg) { ArrayList() } += c.id
            }
            val mostValuable = leagueClubs.mapValues { (_, cs) ->
                cs.sortedWith(compareByDescending<String> { clubValue[it] ?: 0 }.thenComparing(ClubBook.ID_ORDER)).take(VALUE_TOP).toSet()
            }
            val out = ArrayList<Candidate>()
            for (c in book.clubs.sortedWith(compareBy(ClubBook.ID_ORDER) { it.id })) {
                if (c.id in cup) continue
                val lg = leagueOf[c.nation] ?: continue
                for (k in c.squad.sorted()) {
                    if (k in open || season.divisionOf(k) == null || !drawn(k).isFinite()) continue
                    val t = w.terms[k] ?: continue
                    if (t.loanFrom != null) continue
                    val own = season.leagueOf(k)?.id
                    val top = own != null && k in tops[own].orEmpty()
                    val underpaid = ClubEconomy.underpaid(t.wage, value(k), w.omega(lg))
                    val home = !Transfers.abroad(input.nation(k), c.nation)
                    when {
                        abroadRoom > 0 && top && home && own != null && abroadLeague(k, own) != null -> out += Candidate(k, c.id, c.nation, own, true)
                        underpaid || (top && c.id !in mostValuable[lg].orEmpty()) -> out += Candidate(k, c.id, c.nation, lg, false)
                    }
                }
            }
            return out.sortedBy { it.key }
        }

        /** The 1부 medians of the leagues by the drawn ratings. */
        val medians: Map<String, Double> by lazy {
            season.leagues.list.mapNotNull { lg ->
                val rs = season.divisions[lg.top].map(drawn).filter { !it.isNaN() }.sorted()
                if (rs.isEmpty()) null else lg.id to (rs[(rs.size - 1) / 2] + rs[rs.size / 2]) / 2
            }.toMap()
        }

        /**
         * The best-ranked league [key] of league [own] would go to abroad ([Transfers]' rule, which the
         * off-season applies to the ages of the coming season: the input's are the season in play's), null
         * for none.
         */
        fun abroadLeague(key: String, own: String): String? {
            val age = input.age(key)?.plus(1) ?: return null
            if (age !in Transfers.MIN_AGE..Transfers.MAX_AGE || input.origin(key) !in ORIGINS) return null
            val ranking = plan.ranking
            val rank = ranking.rank(own)
            if (rank == Int.MAX_VALUE || season.leagues.list.size < 2) return null
            return destinations(key, rank).firstOrNull()
        }

        /** The leagues ranked [Transfers.GAP] places or more above [rank] whose 1부 median [key] reaches, best-ranked first. */
        fun destinations(key: String, rank: Int): List<String> {
            val ranking = plan.ranking
            val rating = r(key)
            return season.leagues.list.map { it.id }.filter { d -> ranking.rank(d) <= rank - Transfers.GAP && (medians[d]?.let { it <= rating } == true) }
                .sortedBy { ranking.rank(it) }
        }

        /**
         * Candidate [c]'s buyer (design ENG 3.1) and the asked fee, none of [taken]: at home, the club of
         * its nation it would enter the counted 6 of whose coach's taste ([Market.fit]) less the cost
         * (0.3 × asked / budget) is best (then the bigger budget, id), with a budget of [BUDGET_SHARE] × the
         * asked fee and the concentration guard's yes; abroad, among the destination leagues' clubs best
         * ranked first, the one with the most room below its ceiling (then the bigger budget, id), as
         * [Transfers] would choose, with that budget and the guard's yes. Null when nobody can.
         */
        fun buyer(c: Candidate, taken: Set<String>): Pair<String, Int>? {
            val k = c.key
            val v = value(k)
            fun asked(buyer: String): Int = Market.price(book, k, c.club, buyer, now, v).fee
            fun affordable(buyer: String, fee: Int): Boolean = !ClubEconomy.embargo(book, buyer, input.config.ffp) && tb(buyer) >= BUDGET_SHARE * fee
            if (c.abroad) {
                val rank = plan.ranking.rank(c.league)
                for (d in destinations(k, rank)) {
                    val clubs = book.clubs.filter { leagueOf[it.nation] == d && it.id !in taken && it.squad.size < maxSize(it) }
                        .sortedWith(compareByDescending<Club> { maxSize(it) - it.squad.size }.thenByDescending { tb(it.id) }.thenComparing({ it.id }, ClubBook.ID_ORDER))
                    for (b in clubs) {
                        val fee = asked(b.id)
                        if (affordable(b.id, fee) && Market.allowed(book, k, b.id, drawn)) return b.id to fee
                    }
                }
                return null
            }
            val traits = input.traits(k)
            val age = input.age(k)
            var best: Triple<String, Int, Double>? = null
            for (b in book.clubsOf(c.nation).sortedWith(compareBy(ClubBook.ID_ORDER) { it.id })) {
                if (b.id == c.club || b.id in taken || b.id in cup || r(k) <= (floors[b.id] ?: Double.NEGATIVE_INFINITY)) continue
                val fee = asked(b.id)
                if (!affordable(b.id, fee)) continue
                val score = Market.fit(book.coachOf(b.id)?.philosophy, traits, age) - Market.COST_WEIGHT * fee / maxOf(1, tb(b.id))
                val so = best
                if ((so == null || score > so.third || (score == so.third && tb(b.id) > tb(so.first))) && Market.allowed(book, k, b.id, drawn)) {
                    best = Triple(b.id, fee, score)
                }
            }
            return best?.let { it.first to it.second }
        }

        fun maxSize(c: Club): Int = ClubBook.maxSize(sizes[c.nation] ?: 0, clubCount[c.nation] ?: 0)
    }

    /** A rumour's candidate: member [key] of [club] (of [nation], in [league]), [abroad] when a better-ranked league's club would buy it. */
    private class Candidate(val key: String, val club: String, val nation: String, val league: String, val abroad: Boolean)

    /** The players [Transfers] sends abroad: generated and evolved ones. */
    private val ORIGINS = setOf(Origin.GENERATED, Origin.EVOLVED)
}

package yacht.engine.league

/**
 * 우승 플레이오프 (docs/LEAGUE_SPEC.md 2.1), after the league stage: the 1st division's top 4 play
 * semi-finals 1v4 and 2v3 over two legs and a one-match final (3 and 4 matches with
 * [LeagueConfig.longKnockouts]); the better league place is side A. With series (선승제) the
 * semis are first to 3 and the final first to 4; with double elimination (패자부활) the semis'
 * losers play on, the winners' final's loser meets the losers' survivor, and the grand final ends it.
 * Honours "L0" (the champion, which [Offseason.honours] takes over the table's leader) and "L0R"
 * (the runner-up). A season of national leagues has one per league instead ([LeagueTitlePlayoffs]).
 *
 * News: `cup_round` [winner, loser] (4강, score) for the semis, `final` and `title` [winner] ("0",
 * the division) for the final.
 */
class TitlePlayoff private constructor(
    season: Int,
    seed: Long,
    entries: List<DrawEntry>,
    override val long: Boolean,
    override val series: Boolean = false,
    override val double: Boolean = false,
) : BracketCompetition(Comp.TITLE_PLAYOFF, season, seed, entries) {
    override val header: String get() = HEADER
    override val bracket = bracket(entries, long, series, double)

    override fun news(t: KnockoutTie): List<LeagueEvent> =
        if (bracket.isFinal(t)) KnockoutNews.final(season, comp, t, "0") else listOf(KnockoutNews.round(season, comp, t, bracket.label(t)))

    override fun honours(): List<Honour> {
        val w = champion ?: return emptyList()
        return listOfNotNull(Honour(season, "L0", w), runnerUp?.let { Honour(season, "L0R", it) })
    }

    companion object {
        const val HEADER = "titlepo 1"

        /**
         * The title playoff of [plan]'s finished league stage: null when the playoff is off or the
         * 1st division has fewer than 4 AIs; a national season's [LeagueTitlePlayoffs].
         */
        fun create(plan: SeasonPlan): ViewableCompetition? {
            if (plan.league.leagues.national) return LeagueTitlePlayoffs.create(plan)
            if (!plan.config.titlePlayoff || plan.league.divisions.isEmpty()) return null
            val top = plan.league.table(0).take(4).map { it.key }
            if (top.size < 4) return null
            // Seeds are the league places; ratings are not known here, so no upsets are called.
            val c = plan.config
            return TitlePlayoff(plan.number, plan.seed, top.mapIndexed { i, k -> DrawEntry(k, i + 1, Double.NaN) }, c.longKnockouts, c.seriesKnockouts, c.doubleElimination)
        }

        /** Reads a saved title playoff: `titlepo 1`, or a national season's `titlepo 2` ([LeagueTitlePlayoffs]). */
        val codec = CompetitionCodec { lines, season, seed ->
            if (lines.firstOrNull()?.trim() == LeagueTitlePlayoffs.HEADER) return@CompetitionCodec LeagueTitlePlayoffs.decode(lines, season, seed)
            val (entries, results) = read(lines, HEADER)
            require(entries.size == 4) { "a title playoff has 4 entries" }
            TitlePlayoff(season, seed, entries, KnockoutLength.saved(lines), KnockoutLength.series(lines), KnockoutLength.double(lines)).also { it.restore(results) }
        }

        /** The bracket of a title playoff's 4 [entries] (seeds 1-4), its ties numbered from [idBase]. */
        internal fun bracket(entries: List<DrawEntry>, long: Boolean, series: Boolean, double: Boolean, idBase: Int = 0) = Knockout(
            Knockout.pairings(entries), { KnockoutLength.legs(4 shr it, if (it == 0) 2 else 1, long) }, { if (it == 0) "4강" else "결승" }, { 0.0 }, idBase,
            winsOf = if (series) { r -> KnockoutLength.wins(4 shr r, long, grandAfter = double) } else null,
            double = double, losersWins = { KnockoutLength.losersWins(it, long) }, grandWins = KnockoutLength.grandWins(long),
        )
    }
}

/**
 * 우승 플레이오프 of a season of national leagues: the [TitlePlayoff] of every league whose 1부
 * has 4 AIs or more (its top 4, seeds by place), in league order, each its own bracket with ids
 * from its slot × [SLOT_IDS]. Their stages are played together, stage by stage (no league runs
 * ahead into a later week), and the calendar counts them as one. Honours "L0.<league>" (the
 * champion, which [Offseason.honours] takes over the 1부's leader) and "L0R.<league>".
 *
 * No news of its own: dozens of brackets would flood the news; the close tells the champions.
 */
class LeagueTitlePlayoffs private constructor(
    val season: Int,
    private val seed: Long,
    /** The league of each bracket, in league order. */
    val leagues: List<String>,
    /** Each bracket's 4 entries, seeds 1-4. */
    private val entries: List<List<DrawEntry>>,
    private val long: Boolean,
    private val series: Boolean,
    private val double: Boolean,
) : ViewableCompetition {
    override val comp: Comp get() = Comp.TITLE_PLAYOFF

    private val brackets = entries.mapIndexed { i, e -> TitlePlayoff.bracket(e, long, series, double, i * SLOT_IDS) }

    /** The stage every bracket is still playing (the first one not done); -1 once all are over. */
    private fun stage(): Int = stageCounts().indexOfFirst { !it.done }

    override fun next(p: Double, busy: Set<String>): Fixture? {
        val stage = stage().takeIf { it >= 0 } ?: return null
        for ((i, k) in brackets.withIndex()) {
            // A bracket done with this stage waits for the others: its next ties are next week's.
            if (k.counts().getOrNull(stage)?.done != false) continue
            k.next(p, busy) { t -> fixture(i, k, t) }?.let { return it }
        }
        return null
    }

    private fun fixture(i: Int, k: Knockout, t: KnockoutTie): Fixture =
        Fixture(comp, t.id, t.a!!.key, t.b!!.key, Knockout.fixtureSeed(seed, comp, t.id), JobKind.TIE, legs = t.legs, label = "${LeagueIds.short(leagues[i])} ${k.label(t)}", firstTo = t.firstTo)

    override fun release(f: Fixture) {
        brackets.firstOrNull { f.id in it }?.release(f.id)
    }

    override fun record(r: JobResult): List<LeagueEvent> {
        val f = r.fixture
        val k = brackets.firstOrNull { f.id in it } ?: return emptyList()
        if (!Knockout.current(seed, comp, r)) return emptyList()
        k.record(f.id, f.a, f.b, TieScore.of(r))
        return emptyList()
    }

    /** Every bracket's stages (the same rules: the same stages). */
    override fun stages(): List<Stage> = brackets.firstOrNull()?.stages().orEmpty()

    override fun stageOf(f: Fixture): Int = brackets.firstOrNull { f.id in it }?.roundOf(f.id) ?: -1

    override fun stageCounts(): List<StageCount> {
        val all = brackets.map { it.counts() }
        return List(all.maxOfOrNull { it.size } ?: 0) { s ->
            StageCount(all.sumOf { it.getOrNull(s)?.played ?: 0 }, all.sumOf { it.getOrNull(s)?.total ?: 0 })
        }
    }

    override val finished: Boolean get() = brackets.all { it.finished }
    override val fixturesLeft: Int get() = brackets.sumOf { it.fixturesLeft }
    override val fixturesTotal: Int get() = brackets.sumOf { it.fixturesTotal }

    override fun honours(): List<Honour> = brackets.withIndex().flatMap { (i, k) ->
        val w = k.champion ?: return@flatMap emptyList()
        listOfNotNull(Honour(season, HonourKind.of("L0", leagues[i]), w.key), k.runnerUp?.let { Honour(season, HonourKind.of("L0R", leagues[i]), it.key) })
    }

    override fun encode(out: StringBuilder) {
        out.append(HEADER).append('\n')
        if (long) out.append(KnockoutLength.LINE).append('\n')
        if (series) out.append(KnockoutLength.SERIES_LINE).append('\n')
        if (double) out.append(KnockoutLength.DOUBLE_LINE).append('\n')
        for ((i, id) in leagues.withIndex()) {
            out.append("lg ").append(i).append(' ').append(id).append('\n')
            for (e in entries[i]) out.append("e ").append(i).append(' ').append(e.encode()).append('\n')
        }
        for (k in brackets) k.encode(out)
    }

    /** One part per league: its bracket under the league's name. */
    override fun view(): CompetitionView = CompetitionView.Sections(
        comp.label,
        brackets.mapIndexed { i, k ->
            val name = LeagueIds.label(leagues[i])
            CompetitionView.Groups(name, emptyList(), knockout = CompetitionView.Bracket(name, k.view()))
        },
    )

    companion object {
        const val HEADER = "titlepo 2"

        /** Ids a league's bracket takes (a 4-entry bracket has at most 6 ties). */
        const val SLOT_IDS = 16

        /** Null when the playoff is off or no league's 1부 has 4 AIs. */
        fun create(plan: SeasonPlan): LeagueTitlePlayoffs? {
            val c = plan.config
            if (!c.titlePlayoff) return null
            val leagues = ArrayList<String>()
            val entries = ArrayList<List<DrawEntry>>()
            for (lg in plan.league.leagues.list) {
                val top = plan.league.table(lg.top).take(4).map { it.key }
                if (top.size < 4) continue
                leagues += lg.id
                // Seeds are the league places; ratings are not known here.
                entries += top.mapIndexed { i, k -> DrawEntry(k, i + 1, Double.NaN) }
            }
            if (leagues.isEmpty()) return null
            return LeagueTitlePlayoffs(plan.number, plan.seed, leagues, entries, c.longKnockouts, c.seriesKnockouts, c.doubleElimination)
        }

        /** Reads [encode]'s lines: `lg <slot> <league>`, `e <slot> <entry>` (4 a slot), then the results. */
        fun decode(lines: List<String>, season: Int, seed: Long): LeagueTitlePlayoffs {
            require(lines.firstOrNull()?.trim() == HEADER) { "not a $HEADER section" }
            val leagues = sortedMapOf<Int, String>()
            val entries = HashMap<Int, MutableList<DrawEntry>>()
            for (l in lines) {
                val f = l.trim().split(' ')
                when (f[0]) {
                    "lg" -> leagues[f[1].toInt()] = f[2]
                    "e" -> entries.getOrPut(f[1].toInt()) { ArrayList() } += DrawEntry.decode(f, 2)
                }
            }
            require(leagues.isNotEmpty() && leagues.keys.toList() == leagues.keys.indices.toList()) { "the title playoffs' slots" }
            val draws = leagues.keys.map { i -> entries[i].orEmpty().sortedBy { it.seed }.also { e -> require(e.map { it.seed } == listOf(1, 2, 3, 4)) { "a title playoff has 4 entries" } } }
            return LeagueTitlePlayoffs(season, seed, leagues.values.toList(), draws, KnockoutLength.saved(lines), KnockoutLength.series(lines), KnockoutLength.double(lines)).also { p ->
                val results = TieScore.Saved.parse(lines)
                for (k in p.brackets) k.restore(results)
            }
        }
    }
}

/**
 * 승강 플레이오프 (docs/LEAGUE_SPEC.md 2.1), after the league stage: per boundary t/t+1 the upper
 * tier's next place above the automatic drop plays the lower tier's next place below the
 * automatic rise, one tie over two legs ([Pyramid.crossing]: one tie per lower group in a
 * pyramid); the upper side is side A (it stays up when still level). The places are exactly the
 * ones [Offseason.moves] reads: honour "UP<t>" keyed by the lower side when it wins (it goes up,
 * the other down) and "STAY<t>" keyed by the upper side when it holds. A season of national
 * leagues has every league's boundaries ([Spot.league]).
 *
 * News: `cup_round` [winner, loser] (boundary label, score) per tie, and `promoted` [ai] (from, to)
 * and `relegated` [ai] (from, to) with 0-based tiers when the lower side wins; in a pyramid
 * ([grouped]) only for the boundaries of 1부 to 3부 (the rest would flood the news). National
 * leagues: only the `cup_round` of the 1·2부 tie of the [NEWS_LEAGUES] best-ranked leagues
 * ([newsLeagues]); their moves are the close's news.
 */
class PromotionPlayoff private constructor(
    val season: Int,
    private val seed: Long,
    /** The ties: per boundary t the upper side's AI and the lower side's. */
    val spots: List<Spot>,
    /** The league is a pyramid (several ties a boundary). */
    val grouped: Boolean = false,
    /** Each tie is a series (선승제: first to 3, or 2 when not long); saved. */
    private val series: Boolean = false,
    private val long: Boolean = true,
    /** National leagues whose 1·2부 tie makes news (the best-ranked when the playoff was drawn); saved. */
    val newsLeagues: Set<String> = emptySet(),
) : ViewableCompetition {
    /**
     * A tie of boundary [division]/[division]+1 (tiers of its [league]; "" the legacy league),
     * its bracket ids from [slot] (a linear legacy league's: the boundary).
     */
    data class Spot(val division: Int, val upper: String, val lower: String, val slot: Int = division, val league: String = LeagueIds.LEGACY)

    override val comp: Comp get() = Comp.PROMOTION_PLAYOFF

    /** Ties of national leagues (`promopo 2`). */
    private val national: Boolean = spots.any { it.league != LeagueIds.LEGACY }

    // One single-tie bracket per tie, its ids from the tie's slot.
    private val brackets = spots.map { s ->
        Knockout(
            listOf(DrawEntry(s.upper, 1, Double.NaN) to DrawEntry(s.lower, 2, Double.NaN)), { 2 }, { label(s.division, s.league) }, { 0.0 }, idBase = s.slot,
            winsOf = if (series) { _ -> if (long) SERIES_WINS else 2 } else null,
        )
    }

    override fun next(p: Double, busy: Set<String>): Fixture? = brackets.firstNotNullOfOrNull { k ->
        k.next(p, busy) { t -> Fixture(comp, t.id, t.a!!.key, t.b!!.key, Knockout.fixtureSeed(seed, comp, t.id), JobKind.TIE, legs = t.legs, label = k.label(0), firstTo = t.firstTo) }
    }

    override fun release(f: Fixture) {
        brackets.firstOrNull { f.id in it }?.release(f.id)
    }

    /** One stage: every boundary's tie at once. */
    override fun stages(): List<Stage> = listOf(Stage(STAGE, 0.0))

    override fun stageOf(f: Fixture): Int = if (brackets.any { f.id in it }) 0 else -1

    override fun stageCounts(): List<StageCount> =
        listOf(brackets.map { it.counts().single() }.fold(StageCount(0, 0)) { x, y -> StageCount(x.played + y.played, x.total + y.total) })

    override fun record(r: JobResult): List<LeagueEvent> {
        val f = r.fixture
        val i = brackets.indexOfFirst { f.id in it }
        if (i < 0 || !Knockout.current(seed, comp, r)) return emptyList()
        val t = brackets[i].record(f.id, f.a, f.b, TieScore.of(r)) ?: return emptyList()
        val s = spots[i]
        if (national) {
            return if (s.division == 0 && s.league in newsLeagues) listOf(KnockoutNews.round(season, comp, t, label(s.division, s.league))) else emptyList()
        }
        if (grouped && s.division > NEWS_BOUNDARIES) return emptyList()
        val out = arrayListOf(KnockoutNews.round(season, comp, t, label(s.division)))
        if (t.winner?.key == s.lower) {
            out += LeagueEvent("promoted", season, listOf(s.lower), listOf("${s.division + 1}", "${s.division}"), comp)
            out += LeagueEvent("relegated", season, listOf(s.upper), listOf("${s.division}", "${s.division + 1}"), comp)
        }
        return out
    }

    override val finished: Boolean get() = brackets.all { it.finished }
    override val fixturesLeft: Int get() = brackets.sumOf { it.fixturesLeft }
    override val fixturesTotal: Int get() = brackets.sumOf { it.fixturesTotal }

    override fun honours(): List<Honour> {
        if (!finished) return emptyList()
        return spots.zip(brackets).mapNotNull { (s, k) ->
            when (k.champion?.key) {
                s.lower -> Honour(season, "UP${s.division}", s.lower)
                s.upper -> Honour(season, "STAY${s.division}", s.upper)
                else -> null
            }
        }
    }

    override fun encode(out: StringBuilder) {
        out.append(if (national) NATIONAL_HEADER else HEADER).append('\n')
        if (grouped) out.append("grouped\n")
        if (series) out.append(KnockoutLength.SERIES_LINE).append('\n')
        if (series && !long) out.append(SHORT_LINE).append('\n')
        if (national) {
            if (newsLeagues.isNotEmpty()) out.append(NEWS_LINE).append(' ').append(newsLeagues.sortedBy { LeagueIds.order(it) }.joinToString(" ")).append('\n')
            for (s in spots) out.append("t ${s.division} ${s.upper} ${s.lower} ${s.slot} ${s.league}").append('\n')
        } else {
            for (s in spots) out.append("t ${s.division} ${s.upper} ${s.lower}").append(if (s.slot != s.division) " ${s.slot}" else "").append('\n')
        }
        for (k in brackets) k.encode(out)
    }

    override fun view(): CompetitionView = CompetitionView.Bracket(comp.label, brackets.map { it.view().single() })

    companion object {
        const val HEADER = "promopo 1"

        /** National leagues' ties: each `t` line ends with its league, and a `news` line names the leagues whose tie makes news. */
        const val NATIONAL_HEADER = "promopo 2"

        const val STAGE = "승강전"

        /** Games to win a promotion series (2 when knockouts are short). */
        private const val SERIES_WINS = 3

        /** Saved when a series promotion playoff was drawn short. */
        private const val SHORT_LINE = "short 1"

        private const val NEWS_LINE = "news"

        /** In a pyramid, the boundaries below 1부 to 3부 make news (0-based). */
        const val NEWS_BOUNDARIES = 1

        /** National leagues: the best-ranked whose 1·2부 tie makes news ([NationalLeagues.bestRanked]). */
        const val NEWS_LEAGUES = 4

        /** Bracket ids a tie takes (a single tie: one). */
        private const val SLOT_IDS = 1

        /** "1·2부 승강전" for the boundary below tier [d] (0 = top). */
        fun label(d: Int): String = "${d + 1}·${d + 2}부 승강전"

        /** "한국 1·2부 승강전" for the boundary below tier [d] of national league [league]; the legacy league's [label]. */
        fun label(d: Int, league: String): String = if (league == LeagueIds.LEGACY) label(d) else "${LeagueIds.short(league)} ${label(d)}"

        /**
         * The ties of a finished league stage, the places computed as [Offseason.moves] does (a
         * tie whose upper side already plays at the boundary above has none), league by league;
         * null when the playoff is off or no boundary has both places.
         */
        fun create(plan: SeasonPlan): PromotionPlayoff? {
            val cfg = plan.config
            if (!cfg.promotionPlayoff) return null
            val league = plan.league
            val national = league.leagues.national
            val grouped = !national && league.leagues.list.any { !it.tiers.linear }
            val spots = ArrayList<Spot>()
            for (lg in league.leagues.list) {
                for (t in 0 until lg.tiers.count - 1) {
                    for ((upperSpot, lowerSpot) in Pyramid.crossing(league, cfg, lg, t).ties) {
                        // In a middle division of 3 the same AI is the lower side above and the upper
                        // side here: it plays only for promotion, or Offseason.moves could send it up
                        // and down at once (into two divisions). Without this tie both places stay.
                        if (spots.any { it.lower == upperSpot }) continue
                        spots += when {
                            national -> Spot(t, upperSpot, lowerSpot, spots.size * SLOT_IDS, lg.id)
                            grouped -> Spot(t, upperSpot, lowerSpot, spots.size * SLOT_IDS)
                            else -> Spot(t, upperSpot, lowerSpot)
                        }
                    }
                }
            }
            if (spots.isEmpty()) return null
            val news = if (national) NationalLeagues.bestRanked(plan, NEWS_LEAGUES).toSet() else emptySet()
            return PromotionPlayoff(plan.number, plan.seed, spots, grouped, cfg.seriesKnockouts, cfg.longKnockouts, news)
        }

        /** Reads `promopo 1` (one legacy league) and `promopo 2` (national leagues). */
        val codec = CompetitionCodec { lines, season, seed ->
            val header = lines.firstOrNull()?.trim()
            require(header == HEADER || header == NATIONAL_HEADER) { "not a promotion playoff section" }
            val national = header == NATIONAL_HEADER
            val spots = lines.mapNotNull { l ->
                runCatching {
                    l.trim().split(' ').takeIf { it[0] == "t" }?.let { f ->
                        if (national) Spot(f[1].toInt(), f[2], f[3], f[4].toInt(), f[5].also { require(it != LeagueIds.LEGACY) })
                        else Spot(f[1].toInt(), f[2], f[3], f.getOrNull(4)?.toInt() ?: f[1].toInt())
                    }
                }.getOrNull()
            }.distinctBy { it.slot }.sortedBy { it.slot }
            require(spots.isNotEmpty()) { "no ties in the promotion playoff section" }
            val news = if (!national) emptySet() else lines.firstOrNull { it.trim().startsWith("$NEWS_LINE ") }?.trim()?.split(' ')?.drop(1)?.toSet().orEmpty()
            PromotionPlayoff(season, seed, spots, lines.any { it.trim() == "grouped" }, KnockoutLength.series(lines), lines.none { it.trim() == SHORT_LINE }, news).also { p ->
                for (r in TieScore.Saved.parse(lines)) p.brackets.firstOrNull { r.id in it }?.record(r.id, r.a, r.b, r.score)
            }
        }
    }
}

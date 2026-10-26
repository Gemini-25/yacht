package yacht.engine.league

/**
 * 우승 플레이오프 (docs/LEAGUE_SPEC.md 2.1), after the league stage: the 1st division's top 4 play
 * semi-finals 1v4 and 2v3 over two legs and a one-match final; the better league place is side A.
 * Honours "L0" (the champion, which [Offseason.honours] takes over the table's leader) and "L0R"
 * (the runner-up).
 *
 * News: `cup_round` [winner, loser] (4강, score) for the semis, `final` and `title` [winner] ("0",
 * the division) for the final.
 */
class TitlePlayoff private constructor(season: Int, seed: Long, entries: List<DrawEntry>) :
    BracketCompetition(Comp.TITLE_PLAYOFF, season, seed, entries) {
    override val header: String get() = HEADER
    override val bracket = Knockout(Knockout.pairings(entries), { if (it == 0) 2 else 1 }, { if (it == 0) "4강" else "결승" }, { 0.0 })

    override fun news(t: KnockoutTie): List<LeagueEvent> =
        if (t.round == 0) listOf(KnockoutNews.round(season, comp, t, bracket.label(0))) else KnockoutNews.final(season, comp, t, "0")

    override fun honours(): List<Honour> {
        val w = champion ?: return emptyList()
        return listOfNotNull(Honour(season, "L0", w), runnerUp?.let { Honour(season, "L0R", it) })
    }

    companion object {
        const val HEADER = "titlepo 1"

        /** Null when the playoff is off or the 1st division has fewer than 4 AIs. */
        fun create(plan: SeasonPlan): TitlePlayoff? {
            if (!plan.config.titlePlayoff || plan.league.divisions.isEmpty()) return null
            val top = plan.league.table(0).take(4).map { it.key }
            if (top.size < 4) return null
            // Seeds are the league places; ratings are not known here, so no upsets are called.
            return TitlePlayoff(plan.number, plan.seed, top.mapIndexed { i, k -> DrawEntry(k, i + 1, Double.NaN) })
        }

        val codec = CompetitionCodec { lines, season, seed ->
            val (entries, results) = read(lines, HEADER)
            require(entries.size == 4) { "a title playoff has 4 entries" }
            TitlePlayoff(season, seed, entries).also { it.restore(results) }
        }
    }
}

/**
 * 승강 플레이오프 (docs/LEAGUE_SPEC.md 2.1), after the league stage: per boundary d/d+1 the upper
 * division's next place above the automatic drop plays the lower division's next place below the
 * automatic rise, one tie over two legs; the upper side is side A (it stays up when still level).
 * The places are exactly the ones [Offseason.moves] reads: honour "UP<d>" keyed by the lower
 * side when it wins (it goes up, the other down) and "STAY<d>" keyed by the upper side when it
 * holds.
 *
 * News: `cup_round` [winner, loser] (boundary label, score) per tie, and `promoted` [ai] (from, to)
 * and `relegated` [ai] (from, to) with 0-based division indices when the lower side wins.
 */
class PromotionPlayoff private constructor(
    val season: Int,
    private val seed: Long,
    /** Per boundary d: the upper side's AI and the lower side's. */
    val spots: List<Spot>,
) : ViewableCompetition {
    /** The tie of boundary [division]/[division]+1. */
    data class Spot(val division: Int, val upper: String, val lower: String)

    override val comp: Comp get() = Comp.PROMOTION_PLAYOFF

    // One single-tie bracket per boundary, its id the upper division's index.
    private val brackets = spots.map { s ->
        Knockout(listOf(DrawEntry(s.upper, 1, Double.NaN) to DrawEntry(s.lower, 2, Double.NaN)), { 2 }, { label(s.division) }, { 0.0 }, idBase = s.division)
    }

    override fun next(p: Double, busy: Set<String>): Fixture? = brackets.firstNotNullOfOrNull { k ->
        k.next(p, busy) { t -> Fixture(comp, t.id, t.a!!.key, t.b!!.key, Knockout.fixtureSeed(seed, comp, t.id), JobKind.TIE, legs = t.legs, label = k.label(0)) }
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
        out.append(HEADER).append('\n')
        for (s in spots) out.append("t ${s.division} ${s.upper} ${s.lower}\n")
        for (k in brackets) k.encode(out)
    }

    override fun view(): CompetitionView = CompetitionView.Bracket(comp.label, brackets.map { it.view().single() })

    companion object {
        const val HEADER = "promopo 1"
        const val STAGE = "승강전"

        /** "1·2부 승강전" for the boundary below division [d] (0 = top). */
        fun label(d: Int): String = "${d + 1}·${d + 2}부 승강전"

        /**
         * The ties of a finished league stage, the places computed as [Offseason.moves] does (a
         * boundary whose upper place already plays the boundary above has no tie); null when the
         * playoff is off or no boundary has both places.
         */
        fun create(plan: SeasonPlan): PromotionPlayoff? {
            val cfg = plan.config
            if (!cfg.promotionPlayoff) return null
            val league = plan.league
            val spots = ArrayList<Spot>()
            for (d in 0 until league.divisions.size - 1) {
                val upper = league.table(d).map { it.key }
                val lower = league.table(d + 1).map { it.key }
                val swap = minOf(cfg.autoSwap, upper.size / 3, lower.size / 3)
                val upperSpot = upper.getOrNull(upper.size - swap - 1) ?: continue
                val lowerSpot = lower.getOrNull(swap) ?: continue
                // In a middle division of 3 the same AI is the lower side above and the upper side
                // here: it plays only for promotion, or Offseason.moves could send it up and down
                // at once (into two divisions). Without this tie both places stay.
                if (spots.any { it.lower == upperSpot }) continue
                spots += Spot(d, upperSpot, lowerSpot)
            }
            if (spots.isEmpty()) return null
            return PromotionPlayoff(plan.number, plan.seed, spots)
        }

        val codec = CompetitionCodec { lines, season, seed ->
            require(lines.firstOrNull()?.trim() == HEADER) { "not a promotion playoff section" }
            val spots = lines.mapNotNull { l ->
                runCatching { l.trim().split(' ').takeIf { it[0] == "t" }?.let { Spot(it[1].toInt(), it[2], it[3]) } }.getOrNull()
            }.distinctBy { it.division }.sortedBy { it.division }
            require(spots.isNotEmpty()) { "no ties in the promotion playoff section" }
            PromotionPlayoff(season, seed, spots).also { p ->
                for (r in TieScore.Saved.parse(lines)) p.brackets.firstOrNull { r.id in it }?.record(r.id, r.a, r.b, r.score)
            }
        }
    }
}

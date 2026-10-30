package yacht.engine.league

/**
 * 요트컵 (docs/LEAGUE_SPEC.md 2.3): every AI of the league in one knockout bracket seeded by
 * rating, the next power of two with byes for the best seeds. Stage s of the play order opens at
 * league progress 0.05 + 0.1 s (spread to end by 0.95 when there are more than ten stages), so
 * the final comes last. Each tie is a series (선승제, [KnockoutLength.wins]: first to 2, from the
 * 8강 3, the final 4; with [LeagueConfig.seriesKnockouts]) or, as before, one match with deciders;
 * with [LeagueConfig.doubleElimination] (패자부활) a loss drops a side to the losers' bracket and
 * the two brackets' winners meet in the final. Honours CUP and CUP2.
 *
 * News: `cup_round` [winner, loser] (round, score) from the last 16 on and for every giant
 * killing before, `upset`, `eliminated` [ai] (round) when a top-4 seed goes out, `final` and
 * `title` [winner] (요트컵) at the end.
 */
class Cup private constructor(
    season: Int,
    seed: Long,
    entries: List<DrawEntry>,
    override val long: Boolean,
    override val series: Boolean = false,
    override val double: Boolean = false,
) : BracketCompetition(Comp.CUP, season, seed, entries) {
    override val header: String get() = HEADER
    private val size = Knockout.size(maxOf(2, entries.maxOf { it.seed }))
    private val stageCount = Knockout.stagesFor(size, double)
    override val bracket = Knockout(
        Knockout.pairings(entries), { KnockoutLength.legs(size shr it, 1, long) }, { label(size, it) }, ::opensAt,
        winsOf = if (series) { r -> KnockoutLength.wins(size shr r, long, grandAfter = double && size >= 4) } else null,
        double = double, losersWins = { KnockoutLength.losersWins(it, long) }, grandWins = KnockoutLength.grandWins(long),
    )

    private fun opensAt(st: Int): Double =
        if (stageCount <= 10) 0.05 + 0.1 * st else 0.05 + 0.9 * st / (stageCount - 1)

    /** On the calendar the stages spread from 4% to 88% of the season, the final some weeks before the end. */
    override fun target(round: Int): Double = if (stageCount <= 1) FIRST_TARGET else FIRST_TARGET + (LAST_TARGET - FIRST_TARGET) * round / (stageCount - 1)

    override fun news(t: KnockoutTie): List<LeagueEvent> {
        val w = t.winner!!
        val l = t.loser!!
        val label = bracket.label(t)
        val out = ArrayList<LeagueEvent>()
        if (bracket.isFinal(t)) {
            out += KnockoutNews.final(season, comp, t, comp.label)
        } else {
            // Early rounds are many: only the giant killings make the news there.
            val late = when (t.side) {
                BracketSide.WINNERS -> bracket.rounds[t.round].size <= 8
                else -> bracket.losers[t.round].size <= 2
            }
            if (late || KnockoutNews.giantKilling(w, l)) out += KnockoutNews.round(season, comp, t, label)
            // Out of the cup: a loss on the losers' side (or any loss without one).
            val out1 = !bracket.double || t.side != BracketSide.WINNERS
            if (out1 && l.seed <= 4) out += LeagueEvent("eliminated", season, listOf(l.key), listOf(label), comp)
        }
        KnockoutNews.upset(season, comp, w, l)?.let { out += it }
        return out
    }

    override fun honours(): List<Honour> {
        val w = champion ?: return emptyList()
        return listOfNotNull(Honour(season, "CUP", w), runnerUp?.let { Honour(season, "CUP2", it) })
    }

    /** `draw_cup` [the top 4 seeds] (entries, first round, byes). */
    override fun opening(): List<LeagueEvent> = listOf(
        LeagueEvent(
            "draw_cup", season, entries.take(4).map { it.key },
            listOf(entries.size.toString(), bracket.label(0), bracket.rounds[0].count { it.bye }.toString()), comp,
        ),
    )

    companion object {
        const val HEADER = "cup 1"

        /** The cup needs at least this many AIs. */
        const val MIN_AIS = 4
        private const val FIRST_TARGET = 0.04
        private const val LAST_TARGET = 0.88

        /** "결승", "4강", "8강", ... for round [r] of a bracket of [size]. */
        fun label(size: Int, r: Int): String {
            val left = size shr r
            return if (left <= 2) "결승" else "${left}강"
        }

        /** This season's cup: every AI, seeded by rating; null when off or too few AIs. */
        fun create(start: SeasonStart): Cup? {
            if (!start.config.cup || start.ranking.size < MIN_AIS) return null
            val entries = start.ranking.mapIndexed { i, k -> DrawEntry(k, i + 1, start.rating(k)) }
            val c = start.config
            return Cup(start.number, start.seed, entries, c.longKnockouts, c.seriesKnockouts, c.doubleElimination)
        }

        val codec = CompetitionCodec { lines, season, seed ->
            val (entries, results) = read(lines, HEADER)
            Cup(season, seed, entries, KnockoutLength.saved(lines), KnockoutLength.series(lines), KnockoutLength.double(lines)).also { it.restore(results) }
        }
    }
}

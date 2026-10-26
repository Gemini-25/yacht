package yacht.engine.league

/**
 * 레전드 매치 (docs/LEAGUE_SPEC.md 2.3): every third season from season 3, the most decorated
 * legend of the last three seasons comes back at its best (its peak abilities, [Legend.atPeak])
 * against last season's MVP (or the best rated player), one mirrored match at mid-season. An
 * exhibition: off the ladder and the season's statistics, no honour.
 *
 * News: `draw_legend` [legend, current] at the start, `legend_match` [legend, current] (score
 * from the legend's side) when it is played.
 */
class LegendMatch private constructor(season: Int, seed: Long, entries: List<DrawEntry>) :
    BracketCompetition(Comp.LEGEND, season, seed, entries) {
    override val header: String get() = HEADER
    override val bracket = Knockout(listOf(entries[0] to entries[1]), { 1 }, { Comp.LEGEND.label }, { OPENS })

    override fun fixture(t: KnockoutTie): Fixture = super.fixture(t).copy(rated = false, label = Comp.LEGEND.label)

    override fun news(t: KnockoutTie): List<LeagueEvent> {
        val legend = entries[0].key
        val r = t.result ?: return emptyList()
        // The score from the legend's side (side A).
        val score = "${num(r.scoreA)}-${num(r.scoreB)}"
        return listOf(LeagueEvent("legend_match", season, listOf(legend, entries[1].key), listOf(score), comp))
    }

    override fun honours(): List<Honour> = emptyList()

    override fun opening(): List<LeagueEvent> = listOf(LeagueEvent("draw_legend", season, entries.map { it.key }, emptyList(), comp))

    companion object {
        const val HEADER = "legend 1"
        const val OPENS = 0.55

        /** Seasons 3, 6, 9, ... */
        fun due(season: Int): Boolean = season >= 3 && season % 3 == 0

        private fun num(x: Double) = if (x == Math.floor(x)) x.toInt().toString() else x.toString()

        /**
         * This season's legend match from [legends] (the book's); null when off (with the
         * all-star game), not due, or without a legend of the last three seasons.
         */
        fun factory(legends: () -> List<Legend>): (SeasonStart) -> Competition? = { start -> create(start, legends()) }

        fun create(start: SeasonStart, legends: List<Legend>): LegendMatch? {
            if (!start.config.allStar || !due(start.number)) return null
            val recent = legends.filter { it.record.retired >= start.number - 3 && it.record.retired < start.number }
            val legend = recent.maxWithOrNull(
                compareBy<Legend> { it.record.titles }.thenBy { Evolution.counts(it.honours).values.sum() }
                    .thenBy { if (it.record.bestRating.isNaN()) 0.0 else it.record.bestRating }.thenByDescending { it.key },
            ) ?: return null
            val inLeague = start.ranking.toSet()
            val current = start.lastHonours.firstOrNull { it.kind == "MVP" && it.key in inLeague }?.key ?: start.ranking.firstOrNull() ?: return null
            val best = if (legend.record.bestRating.isNaN()) start.rating(current) else legend.record.bestRating
            val entries = listOf(DrawEntry(legend.key, 1, best), DrawEntry(current, 2, start.rating(current)))
            return LegendMatch(start.number, start.seed, entries)
        }

        val codec = CompetitionCodec { lines, season, seed ->
            val (entries, results) = read(lines, HEADER)
            LegendMatch(season, seed, entries.take(2)).also { it.restore(results) }
        }
    }
}

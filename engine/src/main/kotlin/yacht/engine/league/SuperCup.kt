package yacht.engine.league

/**
 * 슈퍼컵 (docs/LEAGUE_SPEC.md 2.3, from season 2): last season's champion against last season's
 * cup winner (the runner-up when the champion won the cup too), one knockout tie at the season
 * start; the champion is side A (it goes through when still level). Honour SUPER.
 *
 * News: `draw_super` [champion, opponent] (their honours) at the start, then `final`, `title`
 * [winner] (슈퍼컵) and `upset`.
 */
class SuperCup private constructor(season: Int, seed: Long, entries: List<DrawEntry>) :
    BracketCompetition(Comp.SUPER, season, seed, entries) {
    override val header: String get() = HEADER
    override val bracket = Knockout(listOf(entries[0] to entries[1]), { 1 }, { Comp.SUPER.label }, { 0.0 })

    override fun news(t: KnockoutTie): List<LeagueEvent> =
        KnockoutNews.final(season, comp, t, comp.label) + listOfNotNull(KnockoutNews.upset(season, comp, t.winner!!, t.loser!!))

    override fun honours(): List<Honour> = listOfNotNull(champion?.let { Honour(season, "SUPER", it) })

    override fun opening(): List<LeagueEvent> = listOf(LeagueEvent("draw_super", season, entries.map { it.key }, emptyList(), comp))

    companion object {
        const val HEADER = "super 1"

        /**
         * Last season's champion ("L0") against its cup winner ("CUP", or "CUP2" when that is the
         * champion). A champion that has left (retired) is replaced by the best of last season's
         * 1st division still in the league, a cup winner that has left by the cup's runner-up.
         * Null in the first season, when off, or when no pair is left.
         */
        fun create(start: SeasonStart): SuperCup? {
            if (!start.config.superCup || start.number < 2) return null
            val inLeague = start.ranking.toSet()
            val l0 = start.lastHonours.firstOrNull { it.kind == "L0" }?.key ?: return null
            val champion = if (l0 in inLeague) l0 else start.lastTables.firstOrNull()?.firstOrNull { it in inLeague } ?: return null
            val cups = listOfNotNull(start.lastHonours.firstOrNull { it.kind == "CUP" }?.key, start.lastHonours.firstOrNull { it.kind == "CUP2" }?.key)
            val opponent = cups.firstOrNull { it != champion && it in inLeague } ?: return null
            val entries = listOf(DrawEntry(champion, 1, start.rating(champion)), DrawEntry(opponent, 2, start.rating(opponent)))
            return SuperCup(start.number, start.seed, entries)
        }

        val codec = CompetitionCodec { lines, season, seed ->
            val (entries, results) = read(lines, HEADER)
            SuperCup(season, seed, entries.take(2)).also { it.restore(results) }
        }
    }
}

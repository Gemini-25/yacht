package yacht.engine.league

/**
 * A winter move (겨울 이적, `ws`, design ENG 3.3): member [key] moved from club [from] to club [to]
 * at the checkpoint of week [week] of [season], holding [points] league points then, in a division
 * of [tier]. The club table credits the points before the move to [from] and the rest to [to]
 * ([ClubTable.of] with splits).
 */
data class WinterMove(val season: Int, val key: String, val from: String, val to: String, val week: Int, val points: Int, val tier: Int)

/** The club world's winter part: the winter moves of the seasons kept (`ws`). */
class WinterBook {
    internal val moves = ArrayList<WinterMove>()

    /** The winter moves of [season], in the order they were made. */
    fun of(season: Int): List<WinterMove> = moves.filter { it.season == season }

    companion object {
        private val WS = LineParts.of<ClubWorld>("ws", { w, out ->
            for (m in w.winter.moves) out.append("ws ${m.season} ${Field.esc(m.key)} ${m.from} ${m.to} ${m.week} ${m.points} ${m.tier}\n")
        }) { w, f ->
            require(f.size >= 8)
            w.winter.moves += WinterMove(f[1].toInt(), Field.unesc(f[2]), f[3], f[4], f[5].toInt(), f[6].toInt(), f[7].toInt())
            true
        }

        /** `ws`. */
        val PARTS: List<LinePart<ClubWorld>> = listOf(WS)
    }
}

/**
 * The winter checkpoint (겨울 이적시장, design ENG 3.3): after the market tick of week `⌊R / 2⌋ + 1`,
 * once a season, mood, the board's review and sackings with immediate hires, the winter window,
 * the arcs' winter hook; P13 writes it. [ClubBook.checkpoint] calls it with the rollback around it.
 */
object ClubCheckpoint {
    /** The checkpoint of [plan]'s season at [week] for [book]; its news. Does nothing yet. */
    fun run(book: ClubBook, plan: SeasonPlan, input: ClubInput, week: Int): List<LeagueEvent> = emptyList()
}

package yacht.engine

import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.SeasonCalendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The news of a national rollover at 1000 players stays readable (design ENG 1.12): the close
 * and the off-season of the national world (league titles and moves, awards, records and
 * milestones, the 요트협회, the clubs and their market) make at most [MAX] events, told as
 * digests at the source instead of one article per player. The players' own news of the close
 * (retirements, debuts, growth: the same as in a legacy league) stays within its few articles a
 * kind; the next season's draws (its competitions' opening news) are not the rollover's.
 */
class NewsVolumeTest {
    private val config = LeagueConfig.DEFAULT.copy(generated = 1000)

    /** A rollover's news: the close's and the open's but the draws of the season that opens. */
    private fun rolloverNews(close: List<LeagueEvent>, open: List<LeagueEvent>): List<LeagueEvent> =
        close + open.filter { it.week == SeasonCalendar.CLOSING || it.kind in OPEN_KINDS }

    @Test
    fun aNationalRolloverAt1000PlayersMakesAtMost60Events() {
        val run = WorldRun(config)
        var retiredTitles = 0
        run.w.openFirstSeason(5L)
        assertTrue(run.w.plan!!.league.leagues.list.size >= 20, run.w.plan!!.league.leagues.toString())
        for (season in 1..2) {
            run.play()
            run.beforeClose()
            val (close, open) = run.rollover(config)
            val news = rolloverNews(close.events, open)
            val (players, world) = news.partition { it.kind in PLAYER_KINDS }
            val kinds = news.groupingBy { it.kind }.eachCount().toSortedMap()
            assertTrue(world.size <= MAX, "season $season: ${world.size} events of the world (${news.size} in all) $kinds")
            // With the players' own news the whole rollover stays near it (the news desk, S17, picks from these).
            assertTrue(news.size <= TOTAL, "season $season: ${news.size} events in all $kinds")
            // No kind floods: one article per player is gone (drafts, transfers, titles, moves, milestones).
            assertTrue(kinds.values.all { it <= 8 }, kinds.toString())
            assertTrue(players.groupingBy { it.kind }.eachCount().values.all { it <= 3 }, kinds.toString())
            // The digests at the source.
            for (k in listOf("league_titles_digest", "lower_tiers", "market_digest", "transfer_window", "milestones")) assertTrue(k in kinds, "season $season: no $k in $kinds")
            // A retiree's article counts every title it won, not only the major ones (a champion of
            // a league outside the best-ranked did win).
            val retired = close.events.filter { it.kind == "retired" }
            for (e in retired) assertEquals(run.w.honours.titles(e.keys.first()), e.args[1].toInt(), e.toString())
            retiredTitles += retired.count { e -> e.args[1] != "0" && run.w.honours.majorTitles(e.keys.first(), run.w.federation::isMajor) == 0 }
        }
        assertTrue(retiredTitles > 0, "no retiree with titles outside the major ones")
    }

    private companion object {
        const val MAX = 60

        /** The whole rollover, the players' own news included (measured 59 and 73). */
        const val TOTAL = 80

        /** The rollover's own news that the open writes at PRESEASON. */
        val OPEN_KINDS = setOf("national_reform", "league_formed", "federation_assembly", "federation_decision")

        /** The players' own news of a close (evolution: retirements, debuts, growth), as in a legacy league. */
        val PLAYER_KINDS = setOf(
            "retirements", "retired", "rookies", "debut", "born", "pupils", "breakout", "decline", "learned_rule", "learned_rules", "league_reform",
        )
    }
}

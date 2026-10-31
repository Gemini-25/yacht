package yacht.engine

import yacht.engine.league.AiStats
import yacht.engine.league.Awards
import yacht.engine.league.LeaderEntry
import yacht.engine.league.LeaderStat
import yacht.engine.league.Leaders
import yacht.engine.league.LeagueConfig
import yacht.engine.league.MatchFormat
import yacht.engine.league.SeasonPlan
import yacht.engine.league.StatsBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The leaderboards (design UX 4.1): every season board ranks by its rule over the players asked
 * for, with its eligibility; career boards need their samples; 완승왕 counts 2-0 wins when the
 * season has no bonus; level lines and the order of the keys asked for never change a board.
 */
class LeadersTest {
    /**
     * Boards of a played 60-player league once its league stage is over, and its stats then;
     * [shuffled] the same boards asked with the keys in other orders and some twice.
     */
    private class Seen(
        val plan: SeasonPlan,
        val boards: Map<LeaderStat, List<LeaderEntry>>,
        val lucky: List<LeaderEntry>,
        val stats: Map<String, AiStats>,
        val shuffled: List<Map<LeaderStat, List<LeaderEntry>>>,
    )

    private fun play(config: LeagueConfig, seasons: Int = 1): Pair<PlayedLeague, List<Seen>> {
        val lg = PlayedLeague(60, config, 51L)
        val seen = ArrayList<Seen>()
        lg.onEnd = { plan ->
            val keys = plan.league.standings.keys.sorted()
            val orders = (1..3).map { keys.shuffled(java.util.Random(it.toLong())) } + listOf(keys + keys.take(5))
            seen += Seen(
                plan, LeaderStat.entries.associateWith { Leaders.season(it, plan, lg.book, keys) }, Leaders.season(LeaderStat.LUCK, plan, lg.book, keys, ascending = true),
                keys.mapNotNull { k -> lg.book.season(k)?.let { k to it.copy() } }.toMap(),
                orders.map { ks -> LeaderStat.entries.associateWith { Leaders.season(it, plan, lg.book, ks) } },
            )
        }
        lg.play(seasons)
        return lg to seen
    }

    @Test
    fun everySeasonBoardRanksByItsRule() {
        val (_, seen) = play(LeagueConfig(divisions = 3))
        val s = seen.single()
        val league = s.plan.league
        val table = league.standings.values.filter { it.played > 0 }
        val most = table.maxOf { it.played }
        fun board(stat: LeaderStat) = s.boards.getValue(stat)
        for ((stat, b) in s.boards) {
            assertTrue(b.isNotEmpty() || stat == LeaderStat.BONUS || stat == LeaderStat.GIANT || stat == LeaderStat.OVER300, "$stat empty")
            // Best first.
            val values = b.map { it.value }
            assertEquals(if (stat.lowFirst) values.sorted() else values.sortedDescending(), values, stat.name)
        }
        // 득점왕: league average score, at least half the most matches played (the SCORER award's rule).
        val scorers = table.filter { it.played >= most / 2 }
        assertEquals(scorers.maxOf { it.avgScore }, board(LeaderStat.SCORER).first().value)
        assertTrue(board(LeaderStat.SCORER).all { e -> league.standings.getValue(e.key).played >= most / 2 })
        // 요트왕: league Yachts, level by fewer games.
        assertEquals(table.maxOf { it.yachts }.toDouble(), board(LeaderStat.YACHTS).first().value)
        // 완승왕 with the bonus: the standings' sweeps, the same as the 2-0 wins of the results.
        assertTrue(league.bonus)
        val twoNil = Leaders.sweepsOf(league)
        for (e in board(LeaderStat.SWEEPS)) assertEquals(twoNil[e.key] ?: 0, e.value.toInt(), e.key)
        // 평점왕: official games' average rating of those with 60% of their own league fixtures.
        for (e in board(LeaderStat.MVP)) {
            val st = s.stats.getValue(e.key)
            assertEquals(st.avgRating, e.value)
            assertTrue(league.standings.getValue(e.key).played >= Awards.MVP_SHARE * league.fixturesOf(e.key))
        }
        // In 1부 alone it is the MVP award's pick.
        val top = league.divisions[0]
        assertEquals(Awards.mvp(s.plan) { s.stats[it] }, Leaders.season(LeaderStat.MVP, league, { s.stats[it] }, top).first().key)
        // 행운아 / 불운: points without the bonus against 운 보정 승점, both ways.
        for (e in board(LeaderStat.LUCK)) {
            val st = league.standings.getValue(e.key)
            assertEquals((st.points - st.sweeps - st.aggPoints).toDouble(), e.value)
        }
        assertEquals(board(LeaderStat.LUCK).sortedWith(compareBy<LeaderEntry> { it.value }.thenByDescending { it.games }.thenBy { it.key }), s.lucky)
        // 클러치: deciders and close games, with one of each added (a short run is not 100%).
        for (e in board(LeaderStat.CLUTCH)) {
            val st = s.stats.getValue(e.key)
            val n = st.deciderWins + st.deciderLosses + st.closeWins + st.closeLosses
            assertTrue(n >= Leaders.CLUTCH_GAMES)
            assertEquals((st.deciderWins + st.closeWins + 1.0) / (n + 2), e.value)
        }
        // 역전의 명수, 거인 사냥꾼, 300점 클럽 list only those who did it; 꾸준함 the steadiest over 40 games.
        assertTrue(board(LeaderStat.COMEBACK).all { it.value >= 1 && it.value == s.stats.getValue(it.key).comebacks.toDouble() })
        assertTrue(board(LeaderStat.STEADY).all { s.stats.getValue(it.key).games >= Leaders.STEADY_GAMES })
        // The boards' rules read as the page opens with them.
        assertTrue(LeaderStat.entries.all { it.label.isNotBlank() && it.rule.endsWith("요.") })
    }

    @Test
    fun withoutTheBonusASweepIsATwoNilWin() {
        val (_, seen) = play(LeagueConfig(divisions = 3, matchFormat = MatchFormat.MIRRORED))
        val s = seen.single()
        val league = s.plan.league
        assertTrue(!league.bonus)
        assertTrue(league.standings.values.all { it.sweeps == 0 })
        val board = s.boards.getValue(LeaderStat.SWEEPS)
        val twoNil = Leaders.sweepsOf(league)
        assertTrue(board.first().value > 0)
        for (e in board) assertEquals(twoNil[e.key] ?: 0, e.value.toInt())
        // Counted from the results: both games won.
        val k = board.first().key
        val counted = (0 until league.fixturesTotal).count { id ->
            val f = league.fixture(id)
            val g = league.result(id) ?: return@count false
            g.size == 2 && ((f.a == k && g.all { it.a > it.b }) || (f.b == k && g.all { it.b > it.a }))
        }
        assertEquals(counted, board.first().value.toInt())
    }

    @Test
    fun careerBoardsNeedTheirSamples() {
        // Double round robins: about 50 matches and 100 games a season.
        val (lg, _) = play(LeagueConfig(divisions = 3, doubleRoundRobin = true), seasons = 3)
        val keys = lg.book.keys
        val scorers = Leaders.career(LeaderStat.SCORER, lg.book, keys)
        assertTrue(scorers.isNotEmpty())
        assertTrue(scorers.all { lg.book.career(it.key)!!.games >= Leaders.CAREER_SCORER_GAMES })
        val rated = Leaders.career(LeaderStat.MVP, lg.book, keys)
        assertTrue(rated.isNotEmpty() && rated.all { lg.book.career(it.key)!!.matches >= Leaders.CAREER_RATING_MATCHES })
        assertTrue(Leaders.career(LeaderStat.STEADY, lg.book, keys).all { lg.book.career(it.key)!!.games >= Leaders.CAREER_STEADY_GAMES })
        assertTrue(Leaders.career(LeaderStat.CLUTCH, lg.book, keys).all { it.games >= Leaders.CAREER_CLUTCH_GAMES })
        // 완승왕 is a season's board only.
        assertTrue(Leaders.career(LeaderStat.SWEEPS, lg.book, keys).isEmpty())
        // Career luck: match points against 운 보정 승점.
        for (e in Leaders.career(LeaderStat.LUCK, lg.book, keys).take(5)) {
            val c = lg.book.career(e.key)!!
            assertEquals((3 * c.matchWins + c.matchDraws - c.aggPoints).toDouble(), e.value)
        }
    }

    @Test
    fun aBoardDoesNotDependOnTheOrderOfItsKeys() {
        val (lg, seen) = play(LeagueConfig(divisions = 3))
        val s = seen.single()
        for (other in s.shuffled) for (stat in LeaderStat.entries) assertEquals(s.boards.getValue(stat), other.getValue(stat), stat.name)
        // A scope: one division's players only; a book without the season's stats has no 평점 board.
        val plan = s.plan
        val d0 = plan.league.divisions[0].toSet()
        assertTrue(Leaders.season(LeaderStat.YACHTS, plan, lg.book, d0).all { it.key in d0 })
        assertTrue(Leaders.season(LeaderStat.MVP, plan, StatsBook(), plan.league.standings.keys).isEmpty())
    }
}

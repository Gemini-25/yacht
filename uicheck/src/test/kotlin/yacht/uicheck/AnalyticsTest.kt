package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import yacht.app.core.Accuracies
import yacht.app.core.AggregateStats
import yacht.app.core.BoardReport
import yacht.app.core.BoxRow
import yacht.app.core.ClubBadge
import yacht.app.core.GameReport
import yacht.app.core.LeaderArea
import yacht.app.core.LeaderFilter
import yacht.app.core.LeaderKind
import yacht.app.core.LeagueAnalytics
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.MatchReport
import yacht.app.core.ReplaySource
import yacht.app.core.ReportState
import yacht.app.core.leaderText
import yacht.app.core.mvpRacers
import yacht.app.core.radarOf
import yacht.app.core.recentBoard
import yacht.app.core.recentStats
import yacht.app.core.seatSplit
import yacht.app.core.styleOf
import yacht.app.core.styleSentence
import yacht.app.ui.matchMvp
import yacht.engine.Advisor
import yacht.engine.AdvisorPlayer
import yacht.engine.Category
import yacht.engine.GreedyPlayer
import yacht.engine.Mode
import yacht.engine.OppModel
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.GameScore
import yacht.engine.league.Ladder
import yacht.engine.league.LeaderEntry
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueFixture
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.WatchMatch
import yacht.engine.league.WinEstimate
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The analytics' loaders (leaderboards, 탐구, the records room) and their pure parts: 최근 10경기, seats, style, texts, 정확도, 경기 MVP. */
class AnalyticsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun stop() = scope.cancel()

    /** What the loaders read: a world and a snapshot; names are keys, [favs] followed. */
    private inner class Host(@Volatile var w: LeagueWorld?, private val favs: Set<String> = emptySet()) : LeagueHost {
        override val scope: CoroutineScope get() = this@AnalyticsTest.scope
        override val lock = Any()
        override val folder = File(".")
        override fun world(): LeagueWorld? = w
        override fun tables(): Tables? = null
        override fun extra(): LeagueTables? = null
        override fun estimate(): WinEstimate? = null
        override fun ai(key: String): LeagueAi? = null
        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? = null
        override fun adaptive(key: String) = false
        override fun name(key: String) = key
        override fun shown(key: String) = key
        override fun badge(key: String): ClubBadge? = null
        override fun leagueLabel(key: String): String? = null
        override fun favourites(): Set<String> = favs
        override fun view(): LeagueView? = null
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = 0
    }

    /**
     * A season of [keys] in one division (single round robin; [divisions] when given) with every
     * fixture played by [score] (the fixture and its index among those played), the first [upTo]
     * fixtures handed out only: returns the world and the fixtures in play order.
     */
    private fun played(
        keys: List<String>,
        divisions: List<List<String>> = listOf(keys),
        upTo: Int = Int.MAX_VALUE,
        score: (LeagueFixture, Int) -> List<GameScore>,
    ): Pair<LeagueWorld, List<LeagueFixture>> {
        val w = LeagueWorld(Ladder(keys))
        val season = Season.start(1, 11L, divisions)
        w.plan = SeasonPlan(season, LeagueConfig())
        w.members = keys
        val out = ArrayList<LeagueFixture>()
        while (out.size < upTo) {
            val f = season.next(emptySet()) ?: break
            assertTrue(season.record(f.id, score(f, out.size)))
            out += f
        }
        return w to out
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun recentBoardsCountEachPlayersLastMatchesOnly() {
        // a beats everyone 2-0 with high scores; the rest split their games.
        val (w, fixtures) = played(listOf("a", "b", "c", "d", "e", "f")) { f, i ->
            when {
                f.a == "a" -> listOf(GameScore(300, 200, aYacht = true, aBonus = true), GameScore(250, 248))
                f.b == "a" -> listOf(GameScore(200, 310, bYacht = true), GameScore(247, 250, bBonus = true))
                else -> listOf(GameScore(220 + i, 210), GameScore(200, 230))
            }
        }
        val season = w.plan!!.league
        val stats = recentStats(season, listOf("a", "b"), n = 3)
        val a = assertNotNull(stats["a"])
        assertEquals(3, a.matches)
        assertEquals(3, a.wins)
        assertEquals(6, a.games)
        assertEquals(3, a.sweeps)
        // Yachts and bonuses from its side only: one of each a match.
        assertEquals(3, a.yachts)
        assertEquals(3, a.bonuses)
        // A total ahead on the same dice: 3 points a match; one 2-point game a match is close.
        assertEquals(9, a.aggPoints)
        assertEquals(3, a.closeWins)
        // Its last three by matchday: the scores of those matches only.
        val last = fixtures.filter { it.a == "a" || it.b == "a" }.sortedByDescending { it.matchday }.take(3)
        assertEquals(last.size * 2, a.games)
        // Boards: a first on 득점왕 (all five of its matches); none for 평점 (not kept per match) or below the matches needed.
        val five = recentStats(season, listOf("a", "b"))
        assertEquals("a", recentBoard(LeaderKind.SCORER, five).first().key)
        assertTrue(recentBoard(LeaderKind.MVP, five).isEmpty())
        // Three matches are fewer than a board needs.
        assertTrue(recentBoard(LeaderKind.SCORER, recentStats(season, listOf("a"), n = 3)).isEmpty())
        assertEquals(setOf("a", "b"), recentBoard(LeaderKind.SCORER, recentStats(season, listOf("a", "b"))).map { it.key }.toSet())
    }

    @Test
    fun seatsFollowWhoMovesFirstInEachGame() {
        // Side A moves first in game 1: it scores 300 there and 100 in game 2 (B first).
        val (w, _) = played(listOf("a", "b", "c", "d")) { _, _ -> listOf(GameScore(300, 100), GameScore(100, 300)) }
        val s = assertNotNull(seatSplit(w.plan!!.league, "a"))
        // a's games: 3 matches, 6 games; first seat always scored 300 and won.
        assertEquals(3, s.first.games)
        assertEquals(3, s.second.games)
        assertEquals(300.0, s.first.avg)
        assertEquals(100.0, s.second.avg)
        assertEquals(1.0, s.first.rate)
        assertEquals(0.0, s.second.rate)
        assertEquals(3, s.game1.games)
        assertEquals(3, s.game2.games)
        assertNull(seatSplit(w.plan!!.league, "nobody"))
    }

    @Test
    fun styleIsTheTraitFurthestPastItsMark() {
        fun box(yacht: Double, straights: Double = 1.0) = Category.ALL.map { c ->
            val league = if (c.isUpper) 10.0 else 20.0
            val ratio = when (c) {
                Category.YACHT -> yacht
                Category.SMALL_STRAIGHT, Category.LARGE_STRAIGHT -> straights
                else -> 1.0
            }
            BoxRow(c, league * ratio, league, 0.1, 0.1)
        }
        val hunter = radarOf(box(1.4), 0.4 to 0.4)
        assertEquals(listOf("윗칸", "보너스", "초이스", "포카·풀하우스", "스트레이트", "요트"), hunter.map { it.label })
        assertEquals("×1.40", hunter.last().text)
        // The league sits at the middle ring, a 40% better Yacht box near the rim.
        assertTrue(hunter.all { it.league == 0.5 } && hunter.last().value > 0.85)
        assertEquals("요트 사냥꾼", styleOf(hunter, 40.0, 40.0).first)
        assertEquals("균형형", styleOf(radarOf(box(1.05), 0.4 to 0.4), 40.0, 40.0).first)
        assertEquals("안정형", styleOf(radarOf(box(1.0), 0.4 to 0.4), 30.0, 40.0).first)
        assertEquals("스트레이트 장인", styleOf(radarOf(box(1.0, straights = 1.2), 0.4 to 0.4), 40.0, 40.0).first)
    }

    private fun agg(avg: Double, yacht: Double, bonus: Double) = AggregateStats(10, 100, 0.5, avg, yacht, bonus, 6.0, 1500, 0, 0, 0, emptyList())

    @Test
    fun aGroupsStyleReadsInOneSentence() {
        val base = agg(230.0, 0.4, 0.4)
        assertEquals("평균 241.5점으로 전체보다 11.5점 높아요. 요트를 자주 만드는 리그예요.", styleSentence(agg(241.5, 0.5, 0.4), base, "리그"))
        assertEquals("평균 229.6점으로 전체와 비슷해요. 윗칸 보너스를 잘 챙기는 클럽이에요.", styleSentence(agg(229.6, 0.4, 0.5), base, "클럽"))
        assertEquals("평균 220.0점으로 전체보다 10.0점 낮아요. 요트도 보너스도 드문 선수들이에요.", styleSentence(agg(220.0, 0.3, 0.3), base, "선수들"))
        assertEquals("평균 230.0점으로 전체와 비슷해요.", styleSentence(base, base, "리그"))
    }

    @Test
    fun boardLinesSayValueAndSample() {
        assertEquals("248.3점" to "38경기", leaderText(LeaderKind.SCORER, LeaderEntry("a", 248.31, 38), career = false))
        assertEquals("248.3점" to "1,234판", leaderText(LeaderKind.SCORER, LeaderEntry("a", 248.31, 1234), career = true))
        assertEquals("31개" to "요트율 41%", leaderText(LeaderKind.YACHTS, LeaderEntry("a", 31.0, 38, 0.41), career = false))
        assertEquals("63%" to "48/76판", leaderText(LeaderKind.BONUS, LeaderEntry("a", 0.63, 76, 48.0), career = false))
        assertEquals("+7점" to "승점 64 · 운 보정 57", leaderText(LeaderKind.LUCKY, LeaderEntry("a", 7.0, 38, 57.0), career = false))
        assertEquals("-5점" to "승점 52 · 운 보정 57", leaderText(LeaderKind.UNLUCKY, LeaderEntry("a", -5.0, 38, 57.0), career = false))
        assertEquals("71%" to "12승 5패", leaderText(LeaderKind.CLUTCH, LeaderEntry("a", 0.71, 17, 12.0), career = false))
        assertEquals("7.42" to "36경기", leaderText(LeaderKind.MVP, LeaderEntry("a", 7.4249, 36), career = false))
        assertEquals("±31.2점" to "50판", leaderText(LeaderKind.STEADY, LeaderEntry("a", 31.2, 50), career = false))
        // Every board has a title and a rule, 불운 read the other way.
        for (k in LeaderKind.entries) assertTrue(LeagueAnalytics.title(k).isNotEmpty() && LeagueAnalytics.ruleOf(k, LeaderFilter()).isNotEmpty())
        assertEquals("불운", LeagueAnalytics.title(LeaderKind.UNLUCKY))
        assertTrue(LeagueAnalytics.ruleOf(LeaderKind.SCORER, LeaderFilter(recent = true)).endsWith("마지막 리그 10경기만 세요."))
    }

    @Test
    fun leaderboardsAndFactsLoadFromThePlayedSeason() {
        val keys = listOf("a", "b", "c", "d", "e", "f")
        val (w, _) = played(keys) { f, i ->
            when {
                f.a == "a" -> listOf(GameScore(280, 120 + i), GameScore(260, 180))
                f.b == "a" -> listOf(GameScore(120, 290), GameScore(170, 250))
                else -> listOf(GameScore(205 + i % 7, 200), GameScore(190, 230 - i % 5))
            }
        }
        val host = Host(w, favs = setOf("f"))
        val an = LeagueAnalytics(host)
        val all = LeaderFilter()
        an.loadLeaders(all)
        waitFor { an.leaders(all) != null }
        val hub = an.leaders(all)!!
        assertEquals("전체", hub.area)
        assertEquals((LeagueAnalytics.HUB + LeagueAnalytics.OTHERS).toSet(), hub.boards.map { it.first }.toSet())
        assertEquals("a", hub.boards.toMap().getValue(LeaderKind.SCORER).first().key)
        assertTrue(hub.boards.all { it.second.size <= LeagueAnalytics.HUB_TOP })
        // The last 10 matches: no 평점 (it is not kept per match).
        val recent = LeaderFilter(recent = true, area = LeaderArea.LEAGUE)
        an.loadLeaders(recent)
        waitFor { an.leaders(recent) != null }
        assertTrue(LeaderKind.MVP in an.leaders(recent)!!.missing)
        assertEquals("a", an.leaders(recent)!!.boards.toMap().getValue(LeaderKind.SCORER).first().key)
        // A whole board: everyone eligible, the order of the hub.
        an.loadBoard(LeaderKind.SCORER, all)
        waitFor { an.board(LeaderKind.SCORER, all) != null }
        val board = an.board(LeaderKind.SCORER, all)!!
        assertEquals(keys.size, board.eligible)
        assertEquals(hub.boards.toMap().getValue(LeaderKind.SCORER).map { it.key }, board.lines.take(LeagueAnalytics.HUB_TOP).map { it.key })
        // 탐구: the lowest winning score of the season is the lowest winning game's.
        an.loadFacts()
        waitFor { an.facts() != null }
        val low = an.facts()!!.facts.first { it.title == "가장 낮은 점수로 이긴 판" }
        val season = w.plan!!.league
        val lowest = (0 until season.fixturesTotal).flatMap { season.result(it).orEmpty() }.filter { it.a != it.b }.minOf { maxOf(it.a, it.b) }
        assertTrue(low.lines.first().text.contains("${lowest}점으로"), low.lines.first().text)
        assertNotNull(low.lines.first().ref)
        // The records room, the hall and the reviews of a season with no records yet: empty, not missing.
        an.loadRoom()
        an.loadHall()
        an.loadReviews()
        waitFor { an.room() != null && an.hall() != null && an.reviewList() != null }
        assertTrue(an.hall()!!.seasons.isEmpty() && an.reviewList()!!.items.isEmpty())
        // A league replaced (a reset): nothing of the old one is shown.
        host.w = LeagueWorld(Ladder(listOf("x")))
        assertNull(an.leaders(all))
        assertNull(an.facts())
    }

    @Test
    fun theMvpRaceMeasuresEachPlayerAgainstItsOwnDivision() {
        // A division of eight plays seven rounds, one of four three: at the end the four have played
        // three matches each, under 60% of the busiest of all (seven) but all of their own division's.
        val big = (1..8).map { "a$it" }
        val small = (1..4).map { "b$it" }
        val (w, _) = played(big + small, listOf(big, small)) { _, _ -> listOf(GameScore(220, 200), GameScore(200, 210)) }
        val season = w.plan!!.league
        assertEquals(7, season.standings.getValue("a1").played)
        assertEquals(3, season.standings.getValue("b1").played)
        assertEquals((big + small).toSet(), mvpRacers(season, big + small))
        // Half way through the big division's matches: who has played 60% of its busiest is in, a player of no season is not.
        val (w2, _) = played(big, upTo = 10) { _, _ -> listOf(GameScore(220, 200), GameScore(200, 210)) }
        val s2 = w2.plan!!.league
        val most = big.maxOf { s2.standings.getValue(it).played }
        val racers = mvpRacers(s2, big + "nobody")
        assertEquals(big.filter { s2.standings.getValue(it).played.let { p -> p > 0 && p >= 0.6 * most } }.toSet(), racers)
        assertTrue(racers.isNotEmpty() && "nobody" !in racers)
    }

    @Test
    fun accuracyFindsExpectedScorePlayPerfectAndGreedyPlayNot() {
        // The precomputed tables (only the expected scores are needed): not in every checkout.
        val dir = listOf(File("../tables"), File("tables")).firstOrNull { File(it, "ev.bin").exists() } ?: return
        val rules = RuleSet.NINTENDO
        val ev = RandomAccessFile(File(dir, "ev.bin"), "r").use { f -> TableIO.readEv(f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()), rules) }
        val tables = Tables(rules, ev, null)
        for (seed in 1L..3L) for (bestFirst in listOf(true, false)) {
            val best = AdvisorPlayer(Advisor(tables), Mode.EXPECTED_SCORE)
            val greedy = GreedyPlayer()
            val turns = WatchMatch.play(rules, if (bestFirst) best else greedy, if (bestFirst) greedy else best, Random(seed))
            val (first, second) = Accuracies.of(tables, turns)
            // Each side read from its own seat: the expected-score player gives nothing away wherever it sits.
            val side = if (bestFirst) 0 else 1
            val (mine, other) = if (bestFirst) first to second else second to first
            assertEquals(0.0, mine.lost, 1e-6, "seed $seed, first $bestFirst")
            assertEquals(100.0, mine.percent, 1e-6)
            assertEquals(0, mine.mistakes)
            // Every decision found among the advisor's options: a box a turn, a keep after each of the first two throws (the last one kept whole: stopped).
            assertEquals(turns.filter { it.side == side }.sumOf { 1 + minOf(it.throws.size, 2) }, mine.decisions)
            assertTrue(other.lost > 0 && other.percent < 100 && other.mistakes > 0, "greedy: $other")
            assertEquals(other.percent, Accuracies.percent(other.lost), 1e-9)
        }
    }

    @Test
    fun accuracyReadsTheLossAgainstTheReference() {
        assertEquals(100.0, Accuracies.percent(0.0))
        assertEquals(0.0, Accuracies.percent(Accuracies.REFERENCE))
        assertEquals(0.0, Accuracies.percent(Accuracies.REFERENCE * 2))
        assertEquals(90.0, Accuracies.percent(Accuracies.REFERENCE / 10), 1e-9)
    }

    @Test
    fun theMatchMvpIsTheHigherAverage() {
        fun game(i: Int, a: Double?, b: Double?) = GameReport(i, "${i + 1}판", 200, 190, i == 0, false, false, false, false, null, null, null, null, a, b, emptyList())
        fun board(n: Int, a: String, b: String, games: List<GameReport>) = BoardReport(n, a, b, a, b, games, 0, 0, 0)
        fun report(boards: List<BoardReport>) = MatchReport(
            "m", "1:L:0", "리그", ReportState.PLAYED, "a", "b", "a", "b", null, null, null, null, "2 : 0", 1, boards,
            ReplaySource.LOG, true, false, null, emptyList(), null, emptyList(),
        )
        assertEquals("b" to 7.5, matchMvp(report(listOf(board(0, "a", "b", listOf(game(0, 6.0, 8.0), game(1, 7.0, 7.0)))))))
        // Across the boards of a team match; games without ratings are left out.
        val team = report(listOf(board(0, "a", "b", listOf(game(0, 6.0, 6.5))), board(1, "c", "d", listOf(game(0, 8.4, 5.0), game(1, null, null)))))
        assertEquals("c" to 8.4, matchMvp(team))
        assertNull(matchMvp(report(listOf(board(0, "a", "b", listOf(game(0, null, null)))))))
    }
}

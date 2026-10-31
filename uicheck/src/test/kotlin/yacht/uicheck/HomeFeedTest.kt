package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import yacht.app.core.ClubBadge
import yacht.app.core.FollowCard
import yacht.app.core.FollowKind
import yacht.app.core.FollowMatch
import yacht.app.core.HomeBanner
import yacht.app.core.HomeFeed
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueSelector
import yacht.app.core.LeagueView
import yacht.app.core.UiPrefs
import yacht.app.core.rulesAfterClose
import yacht.app.ui.followLine
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.MatchRef
import yacht.engine.league.Preview
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.SeasonMatch
import yacht.engine.league.SeasonMatches
import yacht.engine.league.TieRule
import yacht.engine.league.WinEstimate
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The home's feed (`lg.home`, UX design 2): a followed player's row shows its last and next
 * match as the season's matches have them, every followed kind gets its card in order, the one
 * banner goes by its priority (a season's end > the first national season > a reform to come >
 * an empty league) and each can be closed for its season, the followed fixture index is
 * built once a season, a team match's row is the teams' (named by the other club), the home's
 * line names weeks as the schedule does, and a burst of followed results builds the home once.
 */
class HomeFeedTest {
    @Test
    fun aFollowedPlayersRowShowsItsLastAndNextMatch() {
        val w = national()
        play(w) { it.plan!!.league.progress >= 0.3 }
        val p = w.plan!!
        val s = p.league
        val lg = s.leagues.list.first { it.national }
        val key = s.table(lg.top)[2].key
        val club = w.clubs!!.clubOf(s.table(lg.top)[0].key)!!
        val host = Host(w, setOf(key, LeagueSelector.CLUB + club, LeagueSelector.NATION + "KR", LeagueSelector.LEAGUE + lg.id))
        val v = HomeFeed(host).build(w)
        assertEquals(listOf(FollowKind.PLAYER, FollowKind.CLUB, FollowKind.NATION, FollowKind.LEAGUE), v.followed.map { it.kind })
        val card = v.followed.first()
        assertEquals(key, card.target)
        val at = s.table(lg.top).indexOfFirst { it.key == key }
        assertEquals("${s.leagues.label(lg.top)} ${at + 1}위", card.line)

        // Its matches as the season has them: the last played and the first to play, from its side.
        val all = SeasonMatches.ofPlayer(p, key)
        val lastPlayed = all.last { it.played }
        val last = assertNotNull(card.last)
        assertEquals(lastPlayed.ref.encode(), last.ref)
        val first = lastPlayed.fixture.a == key
        val opp = if (first) lastPlayed.fixture.b else lastPlayed.fixture.a
        assertEquals(opp, last.opponent)
        // A league match or a knockout tie: its stored score, from the followed side.
        val score = assertNotNull(lastPlayed.score)
        assertEquals(if (first) score.winner else -score.winner, last.result)
        val won = (if (first) score.scoreA else score.scoreB).roundToInt()
        val lost = (if (first) score.scoreB else score.scoreA).roundToInt()
        assertEquals("$won:$lost", last.score)
        assertEquals(lastPlayed.ref.comp == Comp.LEAGUE, last.replay, "▶ as the host says it can play again")
        val toPlay = all.first { !it.played }
        val next = assertNotNull(card.next)
        assertEquals(toPlay.ref.encode(), next.ref)
        val nopp = if (toPlay.fixture.a == key) toPlay.fixture.b else toPlay.fixture.a
        assertEquals((100 * Preview.expected(w.ladder.rating(key), w.ladder.rating(nopp))).roundToInt(), next.winChance)
        assertNull(next.result)
        assertNull(card.note)

        // The club's place in its league's club table, the league's leader.
        val clubCard = v.followed[1]
        assertTrue(clubCard.line.contains("클럽 순위"), clubCard.line)
        val leagueCard = v.followed[3]
        assertTrue(leagueCard.line.startsWith("1부 선두 "), leagueCard.line)
        assertEquals(0, v.followedMore)
    }

    @Test
    fun theBannerGoesByPriorityAndEachClosesForItsSeason() {
        // An empty league: the welcome.
        val fresh = LeagueWorld(Ladder(listOf(Ladder.ME)), LeagueConfig.DEFAULT)
        assertEquals(HomeBanner.Empty, HomeFeed(Host(fresh, emptySet())).build(fresh).banner)

        // A legacy season with national rules next: where the followed player goes.
        val legacy = LeagueConfig().copy(generated = 300)
        val w = world(legacy)
        assertTrue(!w.plan!!.league.leagues.national)
        play(w) { it.plan!!.league.progress >= 0.2 }
        assertNull(HomeFeed(Host(w, emptySet())).build(w).banner, "no banner while the next season keeps the old rules")
        val next = LeagueConfig.DEFAULT.copy(generated = 300)
        w.config = next
        val key = w.members.maxBy { w.rating(it) }
        val host = Host(w, setOf(key))
        val feed = HomeFeed(host)
        val coming = assertIs<HomeBanner.Coming>(feed.build(w).banner)
        assertEquals(1, coming.season)
        assertEquals(1, coming.moves.size)
        assertTrue(coming.moves[0].second.endsWith("리그"), coming.moves.toString())
        host.prefs = host.prefs.dismiss(UiPrefs.COMING, 1)
        assertNull(feed.build(w).banner)

        // The migration: the season's end first, then the first national season's.
        play(w)
        rollover(w, next)
        val p = w.plan!!
        assertTrue(p.league.leagues.national)
        val end = assertIs<HomeBanner.SeasonEnd>(feed.build(w).banner)
        assertEquals(1, end.season)
        assertTrue(end.lines.first().startsWith("1부 "), end.lines.toString())
        host.prefs = host.prefs.dismiss(UiPrefs.SEASON_END, 1)
        assertEquals(HomeBanner.Reform(2), feed.build(w).banner)
        host.prefs = host.prefs.dismiss(UiPrefs.REFORM, 2)
        assertNull(feed.build(w).banner)
        // A new league (a reset) forgets them all.
        host.prefs = host.prefs.undismissed()
        assertIs<HomeBanner.SeasonEnd>(feed.build(w).banner)
    }

    @Test
    fun theFollowedFixtureIndexIsBuiltOnceASeason() {
        val w = national()
        play(w) { it.plan!!.league.progress >= 0.1 }
        val s = w.plan!!.league
        val lg = s.leagues.list.first { it.national }
        val a = s.table(lg.top)[0].key
        val b = s.table(lg.top)[1].key
        val host = Host(w, setOf(a))
        val feed = HomeFeed(host)
        feed.build(w)
        assertEquals(1, feed.indexScans)
        play(w) { it.plan!!.league.progress >= 0.2 }
        feed.build(w)
        feed.build(w)
        assertEquals(1, feed.indexScans, "results after the index was built need no scan")
        // A player followed later: one scan for it alone; then none again.
        host.favs = setOf(a, b)
        feed.build(w)
        assertEquals(2, feed.indexScans)
        val v = feed.build(w)
        assertEquals(2, feed.indexScans)
        assertEquals(SeasonMatches.ofPlayer(w.plan!!, b).last { it.played }.ref.encode(), v.followed.first { it.target == b }.last?.ref)
        // The next season: built anew, once.
        play(w)
        rollover(w, w.config)
        val after = feed.build(w)
        feed.build(w)
        assertEquals(3, feed.indexScans)
        val refs = after.followed.flatMap { listOfNotNull(it.last, it.next) }.map { it.ref }.filter { it.isNotEmpty() }
        assertTrue(refs.isNotEmpty() && refs.all { MatchRef.decode(it)?.season == w.plan!!.number }, "a new season's rows are its own: $refs")
    }

    @Test
    fun aTeamMatchIsTheTeamsAndNamedByTheOtherClub() {
        // Season 2 of national leagues holds the club cup: a followed member's board in it.
        val w = national()
        play(w)
        rollover(w, w.config)
        var board: Pair<String, SeasonMatch>? = null
        play(w) { x ->
            val p = x.plan!!
            board = SeasonMatches.ofComp(p, Comp.CLUB_CUP).filter { it.played }.firstNotNullOfOrNull { m ->
                // A board player whose latest match is this one (its own board of it).
                m.fixture.players.firstOrNull { k -> SeasonMatches.ofPlayer(p, k).lastOrNull { it.played }?.ref?.whole == m.ref.whole }?.let { it to m }
            }
            board != null
        }
        val (key, m) = assertNotNull(board, "no club cup board played in season 2")
        val f = m.fixture
        val first = key in f.lineupA
        val other = if (first) f.b else f.a
        val host = Host(w, setOf(key))
        val last = assertNotNull(HomeFeed(host).build(w).followed.single().last)
        val i = (if (first) f.lineupA else f.lineupB).indexOf(key)
        assertEquals(m.ref.copy(board = i).encode(), last.ref, "the followed player's own board")
        assertEquals((if (first) f.lineupB else f.lineupA)[i], last.opponent, "its board's opponent")
        // The teams' result and score, named by the other club (by its name, not its id).
        val clubName = assertNotNull(w.clubs!!.nameOf(other))
        assertEquals(clubName, last.opponentName)
        assertTrue(last.opponentName != other, "a club cup side by its name: ${last.opponentName}")
        val s = assertNotNull(m.score)
        assertEquals(if (first) s.winner else -s.winner, last.result)
        fun num(x: Double) = if (x == Math.floor(x)) x.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", x)
        assertEquals(if (first) "${num(s.scoreA)}:${num(s.scoreB)}" else "${num(s.scoreB)}:${num(s.scoreA)}", last.score)
        // The home's line says so: "최근 {club}에게 3:1 승".
        val card = HomeFeed(host).build(w).followed.single()
        assertTrue(followLine(card, w.plan!!.leagueWeeks) { it }.startsWith("최근 ${clubName}에게 "), followLine(card, w.plan!!.leagueWeeks) { it })
    }

    @Test
    fun theHomeLineNamesWeeksAsTheScheduleDoes() {
        fun next(week: Int) = FollowMatch("2:L:5", week, "한국 1부", "b", "B", null, null, false, 55)
        fun card(m: FollowMatch) = FollowCard(FollowKind.PLAYER, "a", "a", "A", null, "", null, null, m, "", null)
        assertEquals("다음 12주차 b 55%", followLine(card(next(12)), 30) { it })
        assertEquals("다음 포스트시즌 1주차 b 55%", followLine(card(next(31)), 30) { it })
        assertEquals("다음 개막 전 b 55%", followLine(card(next(SeasonCalendar.PRESEASON)), 30) { it })
        assertEquals("다음 b 55%", followLine(card(next(SeasonCalendar.NONE)), 30) { it })
    }

    @Test
    fun resultsInARowBuildTheHomeOnce() {
        val w = national()
        play(w) { it.plan!!.league.progress >= 0.05 }
        val feed = HomeFeed(Host(w, setOf(w.members.first())))
        // A burst of followed results (a club's round): one build, a little later.
        repeat(50) { feed.refreshSoon() }
        assertEquals(0L, feed.buildCount, "not at once")
        waitUntil { feed.buildCount >= 1 }
        Thread.sleep(HomeFeed.RESULT_REFRESH_MS + 500)
        assertEquals(1L, feed.buildCount)
        assertNotNull(feed.view)
        // A later result: one more.
        feed.refreshSoon()
        waitUntil { feed.buildCount >= 2 }
        Thread.sleep(300)
        assertEquals(2L, feed.buildCount)
    }

    private fun waitUntil(ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }

    /** A national league of 300 generated players, its first season open. */
    private fun national(): LeagueWorld = world(LeagueConfig.DEFAULT.copy(generated = 300)).also { assertTrue(it.plan!!.league.leagues.national) }

    private fun world(cfg: LeagueConfig): LeagueWorld {
        val w = LeagueWorld(Ladder(listOf(Ladder.ME)), cfg)
        w.players.ensureGenerated(cfg.generated)
        w.members = w.players.alive.map { it.key }
        w.ladder = Ladder(w.members + Ladder.ME)
        w.present = { true }
        w.openFirstSeason(20261031L)
        return w
    }

    /** The rollover as the app does it (LeagueController.rollover): the close under the season's rules, the next under [config]. */
    private fun rollover(w: LeagueWorld, config: LeagueConfig) {
        val p = w.plan!!
        val rules = p.closing ?: w.config
        p.closing = rules
        w.config = rules
        w.ladder.fit()
        val closing = w.closeSeason()
        if (closing.rosterChanged) w.ladder = Ladder.decode(w.players.alive.map { it.key } + Ladder.ME, w.ladder.encode())
        w.members = w.players.alive.map { it.key }
        w.config = rulesAfterClose(config, rules, closing).second
        w.openSeason()
    }

    /** Plays [w]'s season with made-up results (the stronger side by the key's hash wins more often) until [stop] or its end. */
    private fun play(w: LeagueWorld, stop: (LeagueWorld) -> Boolean = { false }) {
        var guard = 0
        while (!w.seasonOver && !stop(w)) {
            val f = w.next(emptySet(), 0, 2) ?: if (w.seasonOver) break else error("season ${w.plan?.number} stuck at ${w.plan?.fixturesLeft} left")
            w.recordJob(result(f))
            check(++guard < 400_000)
        }
    }

    private fun games(f: Fixture, a: String, b: String, n: Int, salt: Int): List<GameScore> {
        val rng = java.util.Random(f.seed * 31 + salt)
        val p = 1 / (1 + Math.pow(10.0, ((b.hashCode() and 63) - (a.hashCode() and 63)) / 100.0))
        return List(n) { if (rng.nextDouble() < p) GameScore(230, 190, aYacht = it == 0) else GameScore(190, 230) }
    }

    private fun result(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f, f.a, f.b, 2, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f, f.a, f.b, 2 * f.legs, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).mapIndexed { i, (x, y) -> BoardResult(x, y, games(f, x, y, 2, i + 1), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    /** A host of world [w] alone (no snapshot, names as keys, a club by its name) with favourites [favs] and preferences [prefs]; a league match of this season plays again. */
    private class Host(var w: LeagueWorld, var favs: Set<String>, var prefs: UiPrefs = UiPrefs()) : LeagueHost {
        override val scope = CoroutineScope(Dispatchers.Unconfined)
        override val lock = Any()
        override val folder = File(".")
        override fun world(): LeagueWorld? = w
        override fun tables(): Tables? = null
        override fun extra(): LeagueTables? = null
        override fun estimate(): WinEstimate? = null
        override fun ai(key: String): LeagueAi? = null
        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? = null
        override fun adaptive(key: String) = false
        override fun name(key: String) = w.clubs?.nameOf(key) ?: key
        override fun shown(key: String) = key
        override fun badge(key: String): ClubBadge? = null
        override fun leagueLabel(key: String): String? = null
        override fun favourites() = favs
        override fun view(): LeagueView? = null
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = w.members.size
        override fun uiPrefs(): UiPrefs = prefs
        override fun canReplay(ref: MatchRef, a: String, b: String): Boolean = ref.comp == Comp.LEAGUE && ref.season == w.plan?.number
    }
}

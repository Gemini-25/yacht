package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import yacht.app.core.ClubBadge
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueSelector
import yacht.app.core.LeagueView
import yacht.app.core.MarketPages
import yacht.app.core.MarketTab
import yacht.app.core.MarketView
import yacht.app.core.MatchCenter
import yacht.app.core.MoveBlock
import yacht.app.core.Page
import yacht.app.core.drawnRatings
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.league.LeagueConfig
import yacht.engine.league.ClubCheckpoint
import yacht.engine.league.ClubMoveType
import yacht.engine.league.Comp
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.Derbies
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.Market
import yacht.engine.league.MatchRef
import yacht.engine.league.StoryStage
import yacht.engine.league.Stories
import yacht.engine.league.WinEstimate
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 이적 센터 (UX 6, 13.4) on the story world's market variant ([MarketScene]: the engine's own window,
 * weekly ticks and winter checkpoint run on the club world): 이적설 is the book's open stories, the followed
 * first; the last windows' outcomes carry their reasons; winter moves are flagged; the page lands on its
 * tab by the week; ☀ 다시 보기's rounds follow the window; the records are sorted and the world record
 * rises; the hot seat is sorted by how close each coach is and starts at half the threshold; the coach
 * moves tell the winter from the off-season as the tenures do; the outlooks are worked out once per
 * closed week; without a club world the page is today's. Rival matches: marked, picked and reported.
 */
class MarketPagesTest {
    private val scene = MarketScene.default

    @Test
    fun theSceneHasEveryPartOfTheCentre() {
        val m = load(scene.world)
        val c = assertNotNull(m.coaches)
        val r = assertNotNull(m.records)
        println(
            "market scene: ${m.live.size} open stories (${m.live.count { it.stage == StoryStage.TALKS }} talks), ${m.ended.size} ended " +
                "(${m.ended.groupingBy { it.stage }.eachCount()}), ${m.moves.size} moves (${m.winterMoves.size} winter, ${m.flags.size} flagged), " +
                "window ${m.window.size} moves in ${m.rounds.size} rounds, ${r.top.size} top fees, ${r.leagues.size} league records, " +
                "${c.hotSeat.size} on the hot seat, ${c.moves.size} coach moves, ${c.free.size} free coaches, ${c.records.size} records; built in ${scene.millis} ms",
        )
        assertTrue(m.centre)
        // What the shots show: open stories, outcomes, a window to replay, winter moves, records, the hot seat, coach moves.
        assertTrue(m.live.size >= 6, "${m.live.size} open stories")
        assertTrue(m.ended.isNotEmpty() && m.ended.any { it.winter } && m.ended.any { !it.winter }, "ended ${m.ended}")
        assertTrue(m.window.size >= 3 && m.rounds.size >= 2, "window ${m.window.size}, rounds ${m.rounds}")
        assertTrue(m.winterMoves.isNotEmpty(), "no winter move")
        assertNotNull(r.world)
        assertTrue(r.top.isNotEmpty() && r.lastSummer.isNotEmpty(), "records $r")
        assertTrue(c.hotSeat.size >= 3, "hot seat ${c.hotSeat}")
        assertTrue(c.moves.any { it.winter && it.kind == 'S' }, "coach moves ${c.moves}")
        assertEquals(scene.world.plan!!.week, m.week)
        // What a read costs under the lock (warmed up), and the season's rival matches (each a new centre's first).
        val pages = MarketPages(Host(scene.world))
        repeat(3) { pages.marketOf(scene.world) }
        val t0 = System.nanoTime()
        repeat(10) { pages.marketOf(scene.world) }
        val t1 = System.nanoTime()
        repeat(3) { MatchCenter(Host(scene.world)).rivalsOf(scene.world, scene.world.plan!!) }
        val t2 = System.nanoTime()
        repeat(10) { MatchCenter(Host(scene.world)).rivalsOf(scene.world, scene.world.plan!!) }
        val t3 = System.nanoTime()
        println("market read ${(t1 - t0) / 10 / 1_000_000.0} ms, rival marks ${(t3 - t2) / 10 / 1_000_000.0} ms (${scene.world.members.size} players)")
    }

    @Test
    fun liveIsTheBooksOpenStoriesFollowedFirst() {
        val w = scene.world
        val b = scene.book
        val s = w.plan!!.number
        val open = Stories.open(b, s)
        val plain = load(w)
        assertEquals(open.map { it.id }.toSet(), plain.live.map { it.id }.toSet())
        assertTrue(plain.live.none { it.followed })
        // Each as the book has it: the stage, the clubs, the fee and weeks, a move between rivals.
        for (v in plain.live) {
            val st = open.first { it.id == v.id }
            assertEquals(st.stage, v.stage)
            assertEquals(st.key, v.key)
            assertEquals(st.buyer, v.buyer?.id)
            assertEquals(st.seller, v.seller?.id)
            assertEquals(st.fee, v.fee)
            assertEquals(st.rumourWeek to st.talksWeek, v.rumourWeek to v.talksWeek)
            assertEquals(b.isRival(st.buyer, st.seller), v.rival)
            assertNull(v.reason)
        }
        assertTrue(plain.live.zipWithNext().all { (x, y) -> x.fee >= y.fee }, "by fee")
        // A followed club's stories come first (the buyer's or the seller's), then the rest by fee.
        val club = plain.live.last().seller!!.id
        val followed = load(w, setOf(LeagueSelector.CLUB + club))
        val n = followed.live.count { it.followed }
        assertTrue(n >= 1)
        assertTrue(followed.live.take(n).all { it.followed } && followed.live.drop(n).none { it.followed })
        assertTrue(followed.live.take(n).all { it.buyer?.id == club || it.seller?.id == club })
    }

    @Test
    fun endedStoriesCarryTheirReasons() {
        val b = scene.book
        val s = scene.world.plan!!.number
        val m = load(scene.world)
        val summer = Market.report(b, s - 1).stories
        val winter = ClubCheckpoint.report(b, s).stories
        assertEquals((winter + summer).map { it.id }.toSet(), m.ended.map { it.id }.toSet())
        // The winter's (newer) first; in each window done, hijacked, collapsed.
        val n = m.ended.count { it.winter }
        assertTrue(m.ended.take(n).all { it.winter } && m.ended.drop(n).none { it.winter })
        for (v in m.ended) {
            assertTrue(v.stage in setOf(StoryStage.DONE, StoryStage.COLLAPSED, StoryStage.HIJACKED), "$v")
            when (v.stage) {
                StoryStage.DONE -> assertNull(v.reason, "$v")
                StoryStage.HIJACKED -> assertTrue(v.reason != null && v.by != null, "$v")
                else -> assertNotNull(v.reason, "$v")
            }
        }
    }

    @Test
    fun winterMovesAreFlagged() {
        val b = scene.book
        val s = scene.world.plan!!.number
        val m = load(scene.world)
        // The winter's moves: its transfers (W) and the members let go to make room for them, all of season s.
        val winter = b.winterMoves(s)
        val moved = m.winterMoves.map { m.moves[it] }
        assertTrue(moved.all { it.season == s && (it.type == ClubMoveType.WINTER || it.type == ClubMoveType.RELEASED) }, "$moved")
        assertEquals(winter.filter { it.to != ClubCheckpoint.NONE }.map { it.key }.toSet(), moved.filter { it.type == ClubMoveType.WINTER }.map { it.ai }.toSet())
        assertEquals(winter.filter { it.to == ClubCheckpoint.NONE }.map { it.key }.toSet(), moved.filter { it.type == ClubMoveType.RELEASED }.map { it.ai }.toSet())
        // Every other move is no winter one.
        for ((i, x) in m.moves.withIndex()) if (i !in m.winterMoves) assertTrue(x.type != ClubMoveType.WINTER, "$i $x")
        // The winter's deals carry the checkpoint's flags, the summer window's its own.
        for (d in ClubCheckpoint.report(b, s).deals.filter { it.flags.isNotEmpty() }) {
            val i = m.moves.indexOfFirst { it.season == s && it.ai == d.key && it.from == d.from && it.to == d.to }
            assertEquals(d.flags, m.flags[i], "${d.key}")
        }
    }

    @Test
    fun aWinterStaysOneRunOnceItsWsLinesAreGone() {
        // A winter that let a member go to make room (the release logged right before the transfer it made room for, its
        // `ws` line naming it): one run of winter moves, and still one once the season's `ws` move lines are dropped
        // (after 2 seasons, while the move log may keep the season's moves).
        val s = scene.world.plan!!.number
        val week = ClubCheckpoint.report(scene.book, s).week
        fun withRelease(ws: Boolean) = load(
            ClubStory.decode(
                StoryWorld.variant(scene.files) {
                    val lines = clubs!!.lines().toMutableList()
                    // Before the winter's last transfer, so that it sits between two of them.
                    val at = lines.indexOfLast { it.startsWith("move $s W ") }
                    val buyer = lines[at].split(' ')[5]
                    lines.add(at, "move $s R letgo1 $buyer - 0")
                    val wsAt = lines.indexOfFirst { it.startsWith("ws ") }
                    if (ws) lines.add(wsAt, "ws $s letgo1 $buyer ${ClubCheckpoint.NONE} $week 0 0") else lines.removeAll { it.matches(Regex("ws \\d+ .*")) }
                    clubs = lines.joinToString("\n")
                },
            ),
        )
        val kept = withRelease(ws = true)
        val gone = withRelease(ws = false)
        val release = kept.moves.indexOfFirst { it.ai == "letgo1" }
        assertTrue(release >= 0 && release in kept.winterMoves, "the release is no winter move: ${kept.winterMoves}")
        assertTrue(kept.moves.getOrNull(release + 1)?.type == ClubMoveType.WINTER, "the scene's winter made one transfer")
        assertEquals(kept.moves, gone.moves)
        assertEquals(kept.winterMoves, gone.winterMoves)
        // One heading a window either way (a release left out of its winter's run split it in two "겨울" headings with
        // one key, which the LazyColumn throws on).
        for (v in listOf(kept, gone)) {
            val blocks = MarketPages.blocks(v)
            assertEquals(blocks.size, blocks.map { it.season to it.winter }.toSet().size, "$blocks")
        }
    }

    @Test
    fun everyWindowHeadingHasItsOwnKey() {
        // A log whose windows do not stay together (a winter's release told apart no more, a league started over) gets
        // a numbered second heading, never a key twice (a LazyColumn throws on one).
        val mv = { s: Int, t: ClubMoveType -> yacht.engine.league.ClubMove(s, t, "k$s$t", "c1", "c2") }
        val moves = listOf(mv(7, ClubMoveType.TRANSFER), mv(7, ClubMoveType.WINTER), mv(7, ClubMoveType.RELEASED), mv(7, ClubMoveType.WINTER), mv(6, ClubMoveType.TRANSFER), mv(7, ClubMoveType.DRAFT))
        val v = MarketView(7, moves, null, winterMoves = setOf(1, 3))
        val blocks = MarketPages.blocks(v)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), blocks.map { it.start })
        assertEquals(listOf("mh-7-false", "mh-7-true", "mh-7-false-2", "mh-7-true-2", "mh-6-false", "mh-7-false-3"), blocks.map { it.key })
        assertEquals(listOf(MoveBlock(0, 7, false, "mh-7-false")), MarketPages.blocks(v.copy(moves = moves.take(1))))
        assertEquals(emptyList(), MarketPages.blocks(v.copy(moves = emptyList(), winterMoves = emptySet())))
    }

    @Test
    fun theDefaultTabFollowsTheWeek() {
        val m = load(scene.world)
        // The rumour weeks: 이적설; a tab asked for wins.
        assertTrue(m.week >= m.rumoursFrom)
        assertEquals(MarketTab.LIVE, MarketPages.tabOf(Page.Market(), m))
        assertEquals(MarketTab.RECORDS, MarketPages.tabOf(Page.Market(MarketTab.RECORDS), m))
        // Before them 이적 내역, unless stories are open (the winter's).
        val quiet = m.copy(week = m.rumoursFrom - 1, live = emptyList())
        assertEquals(MarketTab.MOVES, MarketPages.tabOf(Page.Market(), quiet))
        assertEquals(MarketTab.LIVE, MarketPages.tabOf(Page.Market(), quiet.copy(live = m.live.take(1))))
        assertEquals(MarketTab.LIVE, MarketPages.tabOf(Page.Market(), quiet.copy(week = m.rumoursFrom)))
        // The rumours start after the winter checkpoint's week, ⌈0.6 R⌉ at the earliest.
        val p = scene.world.plan!!
        assertEquals(maxOf(Stories.firstWeek(p), m.winterWeek + 1), m.rumoursFrom)
        // The quiet scene, before the rumour weeks: 이적 내역.
        val q = load(ClubStory.decode(MarketScene.quiet))
        assertTrue(q.centre && q.week < q.rumoursFrom && q.live.isEmpty(), "week ${q.week}, from ${q.rumoursFrom}, ${q.live.size} open")
        assertEquals(MarketTab.MOVES, MarketPages.tabOf(Page.Market(), q))
    }

    @Test
    fun theReplaysRoundsFollowTheWindow() {
        val b = scene.book
        val s = scene.world.plan!!.number
        val m = load(scene.world)
        val deals = Market.report(b, s - 1).deals
        assertEquals(s - 1, m.windowSeason)
        assertEquals(deals.map { Triple(it.key, it.from, it.to) }, m.window.map { Triple(it.move.ai, it.move.from, it.move.to) })
        assertEquals(deals.map { it.flags }, m.window.map { it.flags })
        assertEquals(deals.map { it.phase.label }, m.window.map { it.round })
        // A round starts where the phase changes; the rounds are in the window's order.
        assertEquals(0, m.rounds.first().second)
        assertTrue(m.rounds.zipWithNext().all { (x, y) -> x.second < y.second && x.first != y.first }, "${m.rounds}")
        for ((name, i) in m.rounds) {
            assertEquals(name, m.window[i].round)
            if (i > 0) assertTrue(m.window[i - 1].round != name)
        }
        assertEquals(m.window.size, m.rounds.indices.sumOf { k -> (m.rounds.getOrNull(k + 1)?.second ?: m.window.size) - m.rounds[k].second })
        // The window's moves are among the kept moves with their flags.
        for (x in m.window.filter { it.flags.isNotEmpty() }) {
            val i = m.moves.indexOfFirst { it.season == s - 1 && it.ai == x.move.ai && it.from == x.move.from && it.to == x.move.to }
            assertTrue(i >= 0, "${x.move}")
            assertEquals(x.flags, m.flags[i])
        }
    }

    @Test
    fun recordsAreSortedAndTheProgressionRises() {
        val b = scene.book
        val m = load(scene.world)
        val r = assertNotNull(m.records)
        val world = assertNotNull(r.world)
        assertEquals(b.worldRecord?.fee, world.fee)
        assertEquals(b.worldRecord?.key, world.key)
        // The top 20 by fee; the progression newest first, each record above the one before it (in time).
        assertTrue(r.top.size <= MarketPages.TOP_FEES && r.top.zipWithNext().all { (x, y) -> x.fee >= y.fee }, "${r.top}")
        assertEquals(b.notable.maxOf { it.fee }, r.top.first().fee)
        assertTrue(r.progression.zipWithNext().all { (x, y) -> x.season >= y.season && x.fee > y.fee }, "${r.progression}")
        assertEquals(world.fee, r.progression.first().fee)
        assertTrue(r.lastSummer.size <= MarketPages.SUMMER_TOP && r.lastSummer.zipWithNext().all { (x, y) -> x.fee >= y.fee })
        assertTrue(r.leagues.size <= MarketPages.RECORD_LEAGUES && r.leagues.map { it.first }.toSet().size == r.leagues.size)
    }

    @Test
    fun theHotSeatIsSortedAndAtHalfTheThresholdOrMore() {
        val w = scene.world
        val b = scene.book
        val m = load(w)
        val hot = assertNotNull(m.coaches).hotSeat
        assertTrue(hot.size in 1..MarketPages.HOT_SEAT)
        assertTrue(hot.zipWithNext().all { (x, y) -> x.ratio >= y.ratio }, "${hot.map { it.ratio }}")
        assertTrue(hot.all { it.ratio >= MarketPages.HOT_RATIO })
        for (h in hot) {
            assertEquals(b.coachOf(h.club.id)?.id, h.coach.id)
            assertEquals(yacht.app.core.PressureLevel.of(h.ratio), h.coach.pressure)
        }
        // Every coach at half its threshold or more among the watched clubs is on it (up to its size).
        val outlooks = MarketPages(Host(w)).outlooks(w)
        val due = outlooks.count { (_, o) -> o.threshold > 0 && o.projected / o.threshold >= MarketPages.HOT_RATIO }
        assertEquals(minOf(due, MarketPages.HOT_SEAT), hot.size)
    }

    @Test
    fun coachMovesSplitWinterFromTheOffseason() {
        val b = scene.book
        val s = scene.world.plan!!.number
        val moves = assertNotNull(load(scene.world).coaches).moves
        // The winter's sackings: a spell that ended in season s ('S') with another coach's spell from s at that club.
        for (x in moves.filter { it.winter }) {
            assertEquals(s, x.season)
            val club = assertNotNull(x.club).id
            val tenures = b.tenuresAt(club)
            when (x.kind) {
                'S' -> {
                    assertTrue(tenures.any { (coach, t) -> coach == x.coach && t.to == s && t.end == 'S' }, "$x")
                    assertTrue(tenures.any { (coach, t) -> coach != x.coach && t.from == s }, "$x: nobody came in")
                }
                'H' -> assertTrue(tenures.any { (coach, t) -> coach == x.coach && t.from == s && t.to == 0 }, "$x")
                else -> error("a winter move of kind ${x.kind}")
            }
        }
        // The off-season's: those of the coach market's last off-season, season s − 1.
        assertTrue(moves.filter { !it.winter }.all { it.season == s - 1 })
        assertEquals(yacht.engine.league.CoachMarket.report(b, s - 1).let { it.moves.size + it.retired.size }, moves.count { !it.winter })
        // The winter's come first.
        val n = moves.count { it.winter }
        assertTrue(moves.take(n).all { it.winter })
    }

    @Test
    fun outlooksAreWorkedOutOncePerClosedWeek() {
        val w = scene.world
        val pages = MarketPages(Host(w))
        val first = pages.outlooks(w)
        assertEquals(1, pages.outlookRuns)
        // Asked again in the same week (the page, the home): the same, not worked out again.
        assertTrue(pages.outlooks(w) === first)
        pages.load()
        val m = wait { pages.view }
        assertTrue(m.centre)
        assertEquals(1, pages.outlookRuns)
        // Another league read (a restore: a new season object and club book) works them out again.
        val again = ClubStory.decode(scene.files)
        assertEquals(first, pages.outlooks(again))
        assertEquals(2, pages.outlookRuns)
        // The watched clubs: the best-ranked leagues' (and the followed ones), each with its coach.
        val p = w.plan!!
        val top = Derbies.topLeagues(p, drawnRatings(w, p)).take(MarketPages.HOT_LEAGUES).toSet()
        assertTrue(first.isNotEmpty() && first.keys.all { c -> scene.book.members(c).any { p.league.leagueOf(it)?.id in top } })
    }

    @Test
    fun withoutAClubWorldThePageIsTodays() {
        // The story world as P8's scene was built from (no club world lines): today's 이적·드래프트 view.
        val plain = ClubStory.decode(StoryWorld.files())
        assertNull(plain.clubs?.world)
        val m = load(plain)
        val b = plain.clubs!!
        assertEquals(MarketView(plain.plan!!.number, b.history.asReversed(), b.digest), m)
        assertFalse(m.centre)
        // `clubWorld` off: the world stands still and the page is today's too.
        val off = ClubStory.decode(StoryWorld.variant(scene.files) { this["config.txt"] = LeagueConfig.decode(this["config.txt"]!!).copy(clubWorld = false).encode() })
        assertNotNull(off.clubs?.world)
        assertFalse(off.config.clubWorld)
        val stood = load(off)
        assertFalse(stood.centre)
        assertEquals(MarketView(off.plan!!.number, off.clubs!!.history.asReversed(), off.clubs!!.digest), stood)
        assertEquals(emptyMap(), MarketPages(Host(plain)).outlooks(plain))
    }

    @Test
    fun rivalMatchesAreMarkedPickedAndReported() {
        val w = scene.world
        val p = w.plan!!
        val s = p.league
        val center = MatchCenter(Host(w))
        val marks = assertNotNull(center.rivalsOf(w, p))
        // The marks: every 1부 league fixture between counted members of rival clubs (the engine's rivalName).
        var n = 0
        for (id in 0 until s.fixturesTotal) {
            val f = s.fixture(id)
            val name = Derbies.rivalName(scene.book, p, f.a, f.b, drawnRatings(w, p))
            assertEquals(name, marks.of(MatchRef(p.number, Comp.LEAGUE, id).encode()), "fixture $id")
            if (name != null) n++
        }
        assertEquals(n, marks.size)
        assertTrue(n > 0)
        println("rival matches: $n of ${s.fixturesTotal} league fixtures")
        // Asked again with nothing changed: the same marks.
        assertTrue(center.rivalsOf(w, p) === marks)
        // The broadcast's candidates name the rivalry of a rival match, and only of one.
        for (week in listOf(p.week, p.week + 1)) {
            for (c in center.candidatesNow(week)) {
                assertEquals(marks.of(c.ref.encode()).takeIf { c.comp == Comp.LEAGUE }, c.rival, "${c.ref}")
                if (c.rival == null) assertFalse(c.rivalMove)
            }
        }
        // The report of a played rival match has its line: side A's club's record before the match.
        val page = Page.Match(scene.rivalMatch)
        center.loadReport(page)
        val r = wait { center.report(page) }
        val line = assertNotNull(r.rival)
        val ref = MatchRef.decode(scene.rivalMatch)!!
        val f = s.fixture(ref.id)
        val (ca, cb) = scene.book.clubOf(f.a)!! to scene.book.clubOf(f.b)!!
        assertEquals(marks.of(scene.rivalMatch), line.name)
        assertEquals(ca to cb, line.a.id to line.b.id)
        val record = Derbies.allTime(scene.book, p, ca, cb, f.matchday) ?: Derbies.series(scene.book, p, f.matchday).values.firstOrNull { setOf(it.a, it.b) == setOf(ca, cb) }
        assertEquals(record?.of(ca) ?: Triple(0, 0, 0), Triple(line.w, line.d, line.l))
        // Without a club world: no marks, no rival candidates, no rival line.
        val plain = ClubStory.decode(StoryWorld.files())
        val none = MatchCenter(Host(plain))
        assertNull(none.rivalsOf(plain, plain.plan!!))
        assertTrue(none.candidatesNow(plain.plan!!.week).none { it.rival != null || it.rivalMove })
    }

    // ---- the host ------------------------------------------------------------------------------------

    /** The view [MarketPages] loads from [w] (with [favs] followed). */
    private fun load(w: LeagueWorld, favs: Set<String> = emptySet()): MarketView {
        val pages = MarketPages(Host(w, favs))
        pages.load()
        return wait { pages.view }
    }

    /**
     * The main thread's work: the loaders hand their results over on [Host.scope], and [wait] runs
     * them on the test's thread between its reads, as the app's main thread runs them (WorldPagesTest's pattern).
     */
    private val main = LinkedBlockingQueue<Runnable>()

    /** [get] once it gives a value (the loaders read off the main thread; their results are handed over here). */
    private fun <T : Any> wait(get: () -> T?): T {
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until) {
            get()?.let { return it }
            main.poll(10, TimeUnit.MILLISECONDS)?.run()
        }
        error("nothing loaded in time")
    }

    /** A host of world [w] alone (no snapshot, names as keys), its main thread the test's ([main]). */
    private inner class Host(val w: LeagueWorld, private val favs: Set<String> = emptySet()) : LeagueHost {
        override val scope = CoroutineScope(Executor { main.put(it) }.asCoroutineDispatcher())
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
        override fun favourites() = favs
        override fun view(): LeagueView? = null
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = w.members.size
    }

}

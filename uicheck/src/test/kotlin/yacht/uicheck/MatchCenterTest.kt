package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import yacht.app.core.ClubBadge
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.MatchCenter
import yacht.engine.GreedyPlayer
import yacht.engine.KeeperPlayer
import yacht.engine.LazyPlayer
import yacht.engine.OppModel
import yacht.engine.RandomPlayer
import yacht.engine.RulePlayer
import yacht.engine.RuleSet
import yacht.engine.Tables
import yacht.engine.league.Comp
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.JobKind
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.LogClass
import yacht.engine.league.LogSink
import yacht.engine.league.MatchLogBook
import yacht.engine.league.MatchRef
import yacht.engine.league.Season
import yacht.engine.league.SeasonPlan
import yacht.engine.league.WinEstimate
import java.io.File
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The match log book in the app (UX 3.4): which boards are kept, the budget, memory-only records, other leagues' books, a reset. */
class MatchCenterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rules = RuleSet.NINTENDO

    @AfterTest
    fun stop() = scope.cancel()

    /** Cheap AIs; "p0" counts as adaptive (as 상대 맞춤 would). */
    private val ais = listOf<(Random) -> yacht.engine.Player>(
        { r -> RandomPlayer(r) }, { _ -> GreedyPlayer() }, { _ -> LazyPlayer() }, { _ -> KeeperPlayer(rules, upperFirst = true) },
        { r -> RandomPlayer(r) }, { _ -> RulePlayer(rules) },
    ).mapIndexed { i, make -> LeagueAi("p$i", "선수 $i", Family.EV, "", make = make) }.associateBy { it.key }
    private val keys = ais.keys.toList()

    private inner class Host(@Volatile var w: LeagueWorld?, private val favs: Set<String> = emptySet()) : LeagueHost {
        override val scope: CoroutineScope get() = this@MatchCenterTest.scope
        override val lock = Any()
        override val folder = File(".")
        override fun world(): LeagueWorld? = w
        override fun tables(): Tables? = null
        override fun extra(): LeagueTables? = null
        override fun estimate(): WinEstimate? = null
        override fun ai(key: String): LeagueAi? = ais[key]
        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? = null
        override fun adaptive(key: String) = key == "p0"
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
        override fun members() = w?.members?.size ?: 0
    }

    /** A legacy season of the six AIs (seeded [seed], number [number]); [members] padded to that many for the budget. */
    private fun world(seed: Long = 7L, number: Int = 1, members: Int = keys.size): LeagueWorld {
        val w = LeagueWorld(Ladder(keys))
        w.plan = SeasonPlan(Season.start(number, seed, listOf(keys)), LeagueConfig())
        w.members = keys + List(members - keys.size) { "x$it" }
        return w
    }

    /** Plays league fixture [id] with its decisions logged, records it and offers it to [c]. */
    private fun play(c: MatchCenter, h: Host, id: Int): MatchRef {
        val w = h.w!!
        val p = w.plan!!
        val f = p.league.job(p.league.fixture(id))
        val sink = LogSink()
        val r = assertNotNull(LeagueMatch.playJob(rules, f, { ais[it] }, p.config.matchFormat, log = sink))
        synchronized(h.lock) {
            w.recordJob(r)
            c.offer(r, sink)
        }
        return MatchRef(p.number, Comp.LEAGUE, id)
    }

    /** The league fixtures of [w]'s first matchday, by sides. */
    private fun fixtures(w: LeagueWorld) = (0 until w.plan!!.league.fixturesTotal).map { w.plan!!.league.fixture(it) }

    private fun picked(c: MatchCenter, ref: MatchRef) = c.picks().any { (_, refs) -> refs.any { it.comp == ref.comp && it.id == ref.id } }

    @Test
    fun theClassesOfAMatch() {
        val h = Host(world())
        val c = MatchCenter(h)
        val p = h.w!!.plan!!
        fun cls(f: Fixture, picks: List<Pair<Int, List<MatchRef>>> = emptyList()) = LogClass.of(c.classesOf(p, f, picks))
        fun tie(comp: Comp, label: String, kind: JobKind = JobKind.TIE) = Fixture(comp, 3, "p1", "p2", 1L, kind, label = label)
        assertEquals(setOf(LogClass.FINAL, LogClass.KO), cls(tie(Comp.CUP, "결승")))
        assertEquals(setOf(LogClass.FINAL), cls(tie(Comp.SUPER, "슈퍼컵", JobKind.MATCH)))
        assertEquals(setOf(LogClass.KO), cls(tie(Comp.CUP, "8강")))
        assertEquals(setOf(LogClass.KO), cls(tie(Comp.CUP, "승자조 4강")))
        assertEquals(emptySet(), cls(tie(Comp.CUP, "16강")))
        // CL and CCL knockouts from their first round, not their groups; a legacy title playoff.
        assertEquals(setOf(LogClass.KO), cls(tie(Comp.CHAMPIONS, "16강")))
        assertEquals(emptySet(), cls(tie(Comp.CHAMPIONS, "A조 1차전", JobKind.MATCH)))
        assertEquals(setOf(LogClass.KO), cls(tie(Comp.TITLE_PLAYOFF, "4강")))
        // One of the week's picks.
        assertEquals(setOf(LogClass.BROADCAST), cls(tie(Comp.CUP, "64강"), listOf(2 to listOf(MatchRef(1, Comp.CUP, 3)))))
        assertEquals(emptySet(), cls(tie(Comp.CUP, "64강"), listOf(2 to listOf(MatchRef(1, Comp.CUP, 4)))))
    }

    @Test
    fun adaptiveSidesAreSavedFollowedOnesKeptInMemoryOnly() {
        val h = Host(world(), favs = setOf("p1"))
        val c = MatchCenter(h)
        val all = fixtures(h.w!!)
        val adaptive = all.first { "p0" in listOf(it.a, it.b) && "p1" !in listOf(it.a, it.b) }
        val followed = all.first { "p1" in listOf(it.a, it.b) && "p0" !in listOf(it.a, it.b) }
        val plain = all.filter { f -> listOf(f.a, f.b).none { it == "p0" || it == "p1" } }
        val a = play(c, h, adaptive.id)
        val fl = play(c, h, followed.id)
        val refs = plain.map { play(c, h, it.id) }
        synchronized(h.lock) {
            assertTrue(assertNotNull(c.logOf(a)).has(LogClass.ADAPTIVE))
            val log = assertNotNull(c.logOf(fl))
            assertTrue(log.has(LogClass.FOLLOWED))
            // A match of no class is not kept (unless it was one of the week's picks).
            for (r in refs) assertEquals(picked(c, r), c.logOf(r) != null, "$r")
        }
        // Saved and read again: the adaptive match is there, the followed one only when picked too.
        val bytes = synchronized(h.lock) { c.files().getValue(MatchCenter.LOGS) }
        val again = MatchCenter(h)
        synchronized(h.lock) {
            again.load(bytes)
            assertNotNull(again.logOf(a))
            val back = again.logOf(fl)
            if (picked(c, fl)) assertTrue(assertNotNull(back).has(LogClass.BROADCAST) && !back.has(LogClass.FOLLOWED)) else assertNull(back)
        }
    }

    @Test
    fun theBudgetFollowsTheMembers() {
        for (members in listOf(6, 200, 1000, 3000)) {
            val h = Host(world(members = members))
            val c = MatchCenter(h)
            play(c, h, 0)
            synchronized(h.lock) { assertEquals(MatchLogBook.caps(members), c.bookCaps(), "$members members") }
        }
        // Members joining mid-season move the budget at the next match.
        val h = Host(world(members = 200))
        val c = MatchCenter(h)
        play(c, h, 0)
        h.w!!.members = h.w!!.members + List(800) { "y$it" }
        play(c, h, 1)
        synchronized(h.lock) { assertEquals(MatchLogBook.caps(1000), c.bookCaps()) }
    }

    @Test
    fun aBookOfAnotherLeagueIsNotRead() {
        val h = Host(world(seed = 7L))
        val c = MatchCenter(h)
        val a = fixtures(h.w!!).first { "p0" in listOf(it.a, it.b) }
        val ref = play(c, h, a.id)
        val bytes = synchronized(h.lock) { c.files().getValue(MatchCenter.LOGS) }
        // The same league reads it; a league of another seed (a reset, a seed league) does not.
        val same = MatchCenter(h)
        synchronized(h.lock) {
            same.load(bytes)
            assertNotNull(same.logOf(ref))
        }
        val other = Host(world(seed = 8L))
        val foreign = MatchCenter(other)
        synchronized(other.lock) {
            foreign.load(bytes)
            assertNull(foreign.logOf(ref))
            foreign.load(byteArrayOf(1, 2, 3))
            assertNull(foreign.logOf(ref))
        }
        // A book saved before its season's close, read by the league after it: rolled over, last
        // season's picks kept (1000 members keep a previous block).
        val big = Host(world(seed = 7L, members = 1000))
        val before = MatchCenter(big)
        val pick = fixtures(big.w!!).firstNotNullOf { f -> play(before, big, f.id).takeIf { picked(before, it) } }
        val saved = synchronized(big.lock) { before.files().getValue(MatchCenter.LOGS) }
        val next = Host(world(seed = MatchLogBook.nextSeed(7L, 2), number = 2, members = 1000))
        val after = MatchCenter(next)
        synchronized(next.lock) {
            after.load(saved)
            assertTrue(assertNotNull(after.logOf(pick)).has(LogClass.BROADCAST))
        }
    }

    @Test
    fun clearingEmptiesTheBook() {
        val h = Host(world(), favs = setOf("p1"))
        val c = MatchCenter(h)
        val refs = fixtures(h.w!!).map { play(c, h, it.id) }
        synchronized(h.lock) {
            assertTrue(c.loggedBoards().isNotEmpty())
            c.clear()
            assertTrue(c.loggedBoards().isEmpty())
            for (r in refs) assertNull(c.logOf(r))
            assertTrue(c.picks().isEmpty())
            // Nothing is saved for an empty book (the league's file went with it).
            assertTrue(c.files().isEmpty())
        }
        // A book read from a file goes on being written (over the old file, even when it empties).
        val other = MatchCenter(h)
        play(other, h, 0)
        val bytes = synchronized(h.lock) { other.files().getValue(MatchCenter.LOGS) }
        val read = MatchCenter(h)
        synchronized(h.lock) {
            read.load(bytes)
            assertTrue(MatchCenter.LOGS in read.files())
        }
    }
}

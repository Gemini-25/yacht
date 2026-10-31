package yacht.engine

import yacht.engine.league.Comp
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.GameLogs
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LogClass
import yacht.engine.league.LogSink
import yacht.engine.league.LoggedGame
import yacht.engine.league.MatchFormat
import yacht.engine.league.MatchLog
import yacht.engine.league.MatchLogBook
import yacht.engine.league.MatchRef
import yacht.engine.league.Offseason
import yacht.engine.league.gamesPerMatch
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Decision logs (UX design 3.4): recording, decoding, the book's budget, rollover and file. */
class MatchLogTest {
    private val rules = RuleSet.NINTENDO

    /** Cheap AIs that keep in every way: at random, greedily, never, and chasing a face. */
    private val ais = listOf<(Random) -> Player>(
        { r -> RandomPlayer(r) }, { _ -> GreedyPlayer() }, { _ -> LazyPlayer() }, { _ -> KeeperPlayer(rules, upperFirst = true) },
        { r -> RandomPlayer(r) }, { _ -> RulePlayer(rules) },
    ).mapIndexed { i, make -> LeagueAi("p$i", "선수 $i", Family.EV, "", make = make) }.associateBy { it.key }

    private val keys = ais.keys.toList()

    /** Every kind of job: matches in the three formats, ties of legs and series, team matches. */
    private fun jobs(): List<Pair<Fixture, MatchFormat>> {
        val rng = Random(3)
        fun pair(): Pair<String, String> {
            val a = keys[rng.nextInt(keys.size)]
            return a to keys.filter { it != a }[rng.nextInt(keys.size - 1)]
        }
        val out = ArrayList<Pair<Fixture, MatchFormat>>()
        for (i in 0 until 240) {
            val (a, b) = pair()
            out += Fixture(Comp.LEAGUE, i, a, b, rng.nextLong()) to listOf(MatchFormat.MIRRORED, MatchFormat.SINGLE, MatchFormat.BEST_OF_3)[i % 3]
        }
        for (i in 0 until 40) {
            val (a, b) = pair()
            out += Fixture(Comp.CUP, i, a, b, rng.nextLong(), JobKind.TIE, legs = if (i % 2 == 0) 2 else 4, firstTo = if (i % 2 == 0) 0 else 3) to MatchFormat.MIRRORED
        }
        for (i in 0 until 20) {
            out += Fixture(Comp.CLUB_CUP, i, "T1", "T2", rng.nextLong(), JobKind.TEAM, lineupA = keys.take(3), lineupB = keys.drop(3)) to MatchFormat.MIRRORED
        }
        return out
    }

    private fun play(job: Fixture, format: MatchFormat, sink: LogSink?): JobResult = assertNotNull(LeagueMatch.playJob(rules, job, { ais[it] }, format, log = sink))

    @Test
    fun loggedGamesDecodeToTheSameTurnsAndScores() {
        var games = 0
        var bytes = 0
        for ((job, format) in jobs()) {
            val sink = LogSink()
            val r = play(job, format, sink)
            assertEquals(r.boards.size, sink.boardCount)
            for ((bi, bd) in r.boards.withIndex()) {
                val logs = sink.games(bi)
                assertEquals(bd.games.size, logs.size, "${job.comp} ${job.id} board $bi")
                val per = gamesPerMatch(job, bd)
                for ((gi, g) in bd.games.withIndex()) {
                    val deal = LeagueMatch.deal(job, bi, gi, per)
                    val bits = logs[gi]
                    assertTrue(bits.size <= GameLogs.MAX_BYTES, "${bits.size} bytes")
                    val turns = assertNotNull(GameLogs.turns(rules, deal, bits), "${job.comp} ${job.id} board $bi game $gi")
                    val played = LeagueMatch.playGame(rules, deal, ais.getValue(bd.a), ais.getValue(bd.b), watch = true)
                    assertEquals(played.turns, turns)
                    assertEquals(g, played.score)
                    val last = turns.last()
                    val (sa, sb) = if (deal.aFirst) last.first.total to last.second.total else last.second.total to last.first.total
                    assertEquals(g.a to g.b, sa to sb)
                    // Written again from the turns: the same bits.
                    assertTrue(GameLogs.of(turns, deal).contentEquals(bits))
                    games++
                    bytes += bits.size
                }
                // As a record of the book: every game turn by turn, checked against its score.
                val log = assertNotNull(MatchLog.of(4, r, sink, bi, LogClass.KO.bit))
                assertEquals(MatchRef(4, job.comp, job.id, bi), log.ref)
                for (lg in log.games) assertNotNull(log.turns(rules, lg.index))
            }
        }
        assertTrue(games >= 600, "$games games")
        assertTrue(bytes.toDouble() / games in 20.0..42.0, "${bytes.toDouble() / games} B a game")
    }

    @Test
    fun recordingLeavesEveryResultAsItWas() {
        for ((job, format) in jobs()) {
            val with = play(job, format, LogSink())
            val without = play(job, format, null)
            assertEquals(without.winner, with.winner)
            assertEquals(without.boards.map { it.games }, with.boards.map { it.games }, "${job.comp} ${job.id}")
            assertEquals(without.boards.map { it.deciders }, with.boards.map { it.deciders })
        }
    }

    @Test
    fun badBitsAreRefused() {
        val job = Fixture(Comp.LEAGUE, 0, "p0", "p1", 5L)
        val sink = LogSink()
        play(job, MatchFormat.MIRRORED, sink)
        val deal = LeagueMatch.deal(job, 0, 0, 2)
        val bits = sink.games(0)[0]
        assertNotNull(GameLogs.turns(rules, deal, bits))
        assertNull(GameLogs.turns(rules, deal, bits.copyOf(bits.size - 1)), "cut")
        assertNull(GameLogs.turns(rules, deal, bits + byteArrayOf(0)), "a byte too many")
        assertNull(GameLogs.turns(rules, deal, ByteArray(bits.size) { -1 }), "boxes and keeps that cannot be")
        // Another deal: other dice, so the same decisions do not fit them (or end elsewhere).
        val other = LeagueMatch.deal(job.copy(seed = 6L), 0, 0, 2)
        val log = MatchLog(
            MatchRef(1, Comp.LEAGUE, 0, 0), JobKind.MATCH, 2, 1, 0, 6L, "p0", "p1", null, null, "", 1, LogClass.KO.bit, 0,
            listOf(LoggedGame(0, 1, 1, bits)),
        )
        assertNull(log.turns(rules, 0))
        assertTrue(GameLogs.turns(rules, other, bits) == null || GameLogs.turns(rules, other, bits) != GameLogs.turns(rules, deal, bits))
        // Turns that are not the deal's cannot be written.
        val turns = GameLogs.turns(rules, deal, bits)!!
        assertFailsWith<IllegalArgumentException> { GameLogs.of(turns, other) }
    }

    // ---- the book ------------------------------------------------------------------------------

    private val bits = ByteArray(40) { (it * 7).toByte() }

    /** A record of season [season]'s board: [games] games, all of the same size. */
    private fun rec(season: Int, id: Int, classes: Int, week: Int = 1, games: Int = 2, comp: Comp = Comp.CUP, tag: Int = 0, seed: Long = 0L) = MatchLog(
        MatchRef(season, comp, id, 0), JobKind.TIE, 2, 3, 3, seed, "aa", "bb", null, null, "8강", week, classes, tag,
        List(games) { LoggedGame(it, 200 + it, 190, bits) },
    )

    private fun book(season: Int = 1, seed: Long = 11L): MatchLogBook = MatchLogBook().also { it.begin(season, seed) }

    @Test
    fun capsFollowTheMembers() {
        assertEquals(MatchLogBook.Caps(9_600, 0, 120, 1), MatchLogBook.caps(200))
        assertEquals(MatchLogBook.Caps(48_000, 12_000, 600, Int.MAX_VALUE), MatchLogBook.caps(1000))
        assertEquals(MatchLogBook.Caps(96_000, 24_000, 830, Int.MAX_VALUE), MatchLogBook.caps(3000))
        assertEquals(4_000, MatchLogBook.caps(10).current)
        assertEquals(92, MatchLogBook.caps(10).archiveSeason)
    }

    @Test
    fun overBudgetTheCurrentBlockEvictsByClass() {
        val b = book()
        val k = LogClass.KO.bit
        val f = LogClass.FINAL.bit
        val list = listOf(
            rec(1, 1, k), rec(1, 2, LogClass.BROADCAST.bit, week = 5), rec(1, 3, LogClass.BROADCAST.bit, week = 2), rec(1, 4, LogClass.NEWS.bit),
            rec(1, 5, LogClass.ADAPTIVE.bit), rec(1, 6, LogClass.HIGHLIGHT.bit), rec(1, 7, f), rec(1, 8, k or f),
        )
        for (r in list) assertTrue(b.put(r))
        val one = b.bytes(MatchLogBook.CURRENT) / list.size
        assertEquals(one * list.size, b.bytes(MatchLogBook.CURRENT), "records of one size")
        fun kept() = (1..8).filter { b[MatchRef(1, Comp.CUP, it)] != null }
        fun cap(n: Double) {
            b.caps = MatchLogBook.Caps((one * n).toInt(), 0, 0, 0)
        }
        // KO first (the oldest), and only as much as needed: the KO final keeps its KO.
        cap(7.5)
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8), kept())
        assertTrue(b[MatchRef(1, Comp.CUP, 8)]!!.has(LogClass.KO))
        // Then broadcast picks, the oldest week first; the final loses its KO but stays.
        cap(5.5)
        assertEquals(listOf(4, 5, 6, 7, 8), kept())
        assertEquals(f, b[MatchRef(1, Comp.CUP, 8)]!!.classes)
        // Then news, adaptive, highlights; finals last.
        cap(2.5)
        assertEquals(listOf(7, 8), kept())
        cap(0.5)
        assertEquals(emptyList(), kept())
        assertEquals(0, b.bytes(MatchLogBook.CURRENT))
        // A put over budget evicts at once.
        b.caps = MatchLogBook.Caps(one * 2, 0, 0, 0)
        for (i in 10..12) b.put(rec(1, i, LogClass.NEWS.bit))
        assertEquals(listOf(11, 12), (10..12).filter { b[MatchRef(1, Comp.CUP, it)] != null })
    }

    @Test
    fun putReplacesTheBoardAndOrsItsClasses() {
        val b = book()
        val first = rec(1, 3, LogClass.KO.bit)
        val again = rec(1, 3, LogClass.NEWS.bit).copy(games = listOf(LoggedGame(0, 1, 2, byteArrayOf(9))))
        assertTrue(b.put(first))
        val size = b.bytes(MatchLogBook.CURRENT)
        assertTrue(b.put(again))
        assertEquals(1, b.count(MatchLogBook.CURRENT))
        val got = assertNotNull(b[MatchRef(1, Comp.CUP, 3)])
        assertEquals(LogClass.KO.bit or LogClass.NEWS.bit, got.classes)
        assertEquals(again.games, got.games)
        assertTrue(b.bytes(MatchLogBook.CURRENT) < size)
        // The whole fixture's ref (board -1) and a game's ref read the board 0 record.
        assertEquals(got, b[MatchRef(1, Comp.CUP, 3, -1)])
        assertEquals(got, b[MatchRef(1, Comp.CUP, 3, 0, 1)])
        // Another season's, or a record of no class: not kept.
        assertFalse(b.put(rec(2, 3, LogClass.KO.bit)))
        assertFalse(b.put(rec(1, 4, 0)))
        assertNull(b[MatchRef(1, Comp.CUP, 4)])
        assertTrue(b.addClass(MatchRef(1, Comp.CUP, 3), LogClass.FINAL))
        assertTrue(b[MatchRef(1, Comp.CUP, 3)]!!.has(LogClass.FINAL))
        assertFalse(b.addClass(MatchRef(1, Comp.CUP, 99), LogClass.FINAL))
        // A team match's boards are records of their own.
        val team = rec(1, 7, LogClass.FINAL.bit, comp = Comp.ALL_STAR).copy(kind = JobKind.TEAM, teamA = "TOP", teamB = "CHALLENGER")
        for (i in 0 until 3) b.put(team.copy(ref = MatchRef(1, Comp.ALL_STAR, 7, i), a = "a$i", b = "b$i"))
        assertEquals(listOf("a0", "a1", "a2"), b.boards(MatchRef(1, Comp.ALL_STAR, 7)).map { it.a })
        assertEquals("a1", b[MatchRef(1, Comp.ALL_STAR, 7, 1)]?.a)
    }

    @Test
    fun memoryRecordsAreNeverSaved() {
        val b = book()
        // A followed player's KO tie: evicted from the file's budget, it stays in memory.
        b.put(rec(1, 1, LogClass.KO.bit or LogClass.FOLLOWED.bit))
        b.put(rec(1, 2, LogClass.FOLLOWED.bit))
        repeat(7) { b.put(rec(1, -1 - it, LogClass.FRIENDLY.bit, comp = Comp.FRIENDLY)) }
        assertEquals(1, b.count(MatchLogBook.CURRENT))
        b.caps = MatchLogBook.Caps(10, 0, 0, 0)
        assertEquals(0, b.count(MatchLogBook.CURRENT))
        assertEquals(LogClass.FOLLOWED.bit, b[MatchRef(1, Comp.CUP, 1)]?.classes)
        assertNotNull(b[MatchRef(1, Comp.CUP, 2)])
        // The newest 5 friendlies.
        assertEquals((3..7).map { -it }, (1..7).map { -it }.filter { b[MatchRef(1, Comp.FRIENDLY, it)] != null })
        val back = MatchLogBook.decode(b.encode())
        assertNull(back[MatchRef(1, Comp.CUP, 1)])
        assertNull(back[MatchRef(1, Comp.FRIENDLY, -7)])
        // Gaining a saved class saves it.
        b.caps = MatchLogBook.Caps.NONE
        assertTrue(b.addClass(MatchRef(1, Comp.CUP, 2), LogClass.NEWS))
        assertNotNull(MatchLogBook.decode(b.encode())[MatchRef(1, Comp.CUP, 2)])
    }

    @Test
    fun rolloverKeepsLastSeasonInOrderAndArchivesSingleGames() {
        val b = book(1, 11L)
        val final = rec(1, 1, LogClass.FINAL.bit, games = 5, tag = MatchLogBook.TAG_CUP_FINAL)
        val highlight = rec(1, 2, LogClass.HIGHLIGHT.bit)
        val broadcast = rec(1, 3, LogClass.BROADCAST.bit, week = 3)
        val news = rec(1, 4, LogClass.NEWS.bit)
        val ko = rec(1, 5, LogClass.KO.bit)
        for (r in listOf(ko, news, broadcast, highlight, final)) b.put(r)
        b.picks = listOf(3 to listOf(MatchRef(1, Comp.CUP, 3)))
        fun keep(l: MatchLog) = when {
            l.has(LogClass.FINAL) -> 0
            l.has(LogClass.HIGHLIGHT) -> 1
            l.has(LogClass.BROADCAST) -> 2
            l.has(LogClass.NEWS) -> 3
            else -> -1
        }
        fun archive(l: MatchLog) = when (l.ref.id) {
            1 -> listOf(MatchLogBook.ArchiveGame(MatchLogBook.TAG_CUP_FINAL))
            2 -> listOf(MatchLogBook.ArchiveGame(1, game = 1))
            else -> emptyList()
        }
        // Room for the final's deciding game, the highlight's and the broadcast pick's two each, not the news.
        val one = MatchLogBook.size(final.copy(games = final.games.takeLast(1)), 11L)
        val two = MatchLogBook.size(highlight, 11L)
        val caps = MatchLogBook.Caps(Int.MAX_VALUE, one + 2 * two + 10, 10_000, 1)
        b.rollover(1, 11L, 2, 22L, caps, ::keep, ::archive)
        assertEquals(2, b.season)
        assertEquals(22L, b.seed)
        assertEquals(0, b.count(MatchLogBook.CURRENT))
        assertEquals(emptyList(), b.picks)
        assertEquals(listOf(4), b[MatchRef(1, Comp.CUP, 1)]!!.games.map { it.index }, "a series keeps its deciding game")
        assertEquals(listOf(0, 1), b[MatchRef(1, Comp.CUP, 2)]!!.games.map { it.index })
        assertNotNull(b[MatchRef(1, Comp.CUP, 3)])
        assertNull(b[MatchRef(1, Comp.CUP, 4)], "full before the news")
        assertNull(b[MatchRef(1, Comp.CUP, 5)], "KO is not kept")
        assertEquals(3, b.count(MatchLogBook.PREVIOUS))
        // The archive: one game a season below 500 members, highlights first.
        assertEquals(listOf(1), b.archivedSeasons)
        assertEquals(1, b.count(MatchLogBook.ARCHIVE))
        val before = b.encode()
        // Done once: called again, nothing changes.
        b.rollover(1, 11L, 2, 22L, caps, ::keep, ::archive)
        assertTrue(before.contentEquals(b.encode()))
        // The next close drops last season's block; its archive entry stays.
        b.put(rec(2, 9, LogClass.FINAL.bit, tag = MatchLogBook.TAG_SUPER))
        b.rollover(2, 22L, 3, 33L, caps, ::keep) { l -> listOf(MatchLogBook.ArchiveGame(l.tag)) }
        assertEquals(listOf(1), b[MatchRef(1, Comp.CUP, 2)]?.games?.map { it.index }, "season 1: its archived game only")
        assertNull(b[MatchRef(1, Comp.CUP, 3)])
        assertEquals(listOf(1, 2), b.archivedSeasons)
        assertEquals(listOf(0, 1), b[MatchRef(2, Comp.CUP, 9)]!!.games.map { it.index })
        // Archived games from the same record, in tag order, until the season's bytes are used.
        val c = book(1, 11L)
        c.put(final)
        c.put(highlight)
        c.rollover(1, 11L, 2, 22L, MatchLogBook.Caps(Int.MAX_VALUE, 0, 2 * one + 5, Int.MAX_VALUE), { -1 }) { l ->
            if (l.ref.id == 2) listOf(MatchLogBook.ArchiveGame(1, 0), MatchLogBook.ArchiveGame(3, 1)) else listOf(MatchLogBook.ArchiveGame(MatchLogBook.TAG_CL_FINAL))
        }
        assertEquals(listOf(0, 1), c[MatchRef(1, Comp.CUP, 2)]!!.games.map { it.index })
        assertNull(c[MatchRef(1, Comp.CUP, 1)], "no room left for the final")
        assertEquals(0, c.count(MatchLogBook.PREVIOUS))
    }

    @Test
    fun aPastSeasonIsReadFromItsBlockAndItsArchiveTogether() {
        val b = book(1, 11L)
        // A 5-game final whose second game is the season's best; a KO tie whose game is the third best.
        val final = rec(1, 1, LogClass.FINAL.bit or LogClass.HIGHLIGHT.bit, games = 5, tag = MatchLogBook.TAG_CUP_FINAL)
        val ko = rec(1, 2, LogClass.KO.bit or LogClass.HIGHLIGHT.bit)
        b.put(final)
        b.put(ko)
        val keep = { l: MatchLog -> if (l.has(LogClass.FINAL)) 0 else -1 }
        val archive = { l: MatchLog ->
            if (l.ref.id == 1) listOf(MatchLogBook.ArchiveGame(1, game = 1), MatchLogBook.ArchiveGame(MatchLogBook.TAG_CUP_FINAL)) else listOf(MatchLogBook.ArchiveGame(3, game = 0))
        }
        b.rollover(1, 11L, 2, 22L, MatchLogBook.caps(1000), keep, archive)
        // Last season's block keeps the final's deciding game, the archive its best game too.
        assertEquals(1, b.count(MatchLogBook.PREVIOUS))
        assertEquals(listOf(1), b.archivedSeasons)
        val got = assertNotNull(b[MatchRef(1, Comp.CUP, 1, 0, 1)])
        assertEquals(final.games.filter { it.index == 1 || it.index == 4 }, got.games)
        assertEquals(MatchLogBook.TAG_CUP_FINAL, got.tag)
        // The KO tie is only in the archive: found, and its boards listed.
        assertEquals(listOf(0), b[MatchRef(1, Comp.CUP, 2)]?.games?.map { it.index })
        assertEquals(listOf(MatchRef(1, Comp.CUP, 2, 0)), b.boards(MatchRef(1, Comp.CUP, 2)).map { it.ref })
        assertEquals(listOf(1, 4), b.boards(MatchRef(1, Comp.CUP, 1)).single().games.map { it.index })
        assertTrue(b.addClass(MatchRef(1, Comp.CUP, 2), LogClass.NEWS))
        assertTrue(b[MatchRef(1, Comp.CUP, 2)]!!.has(LogClass.NEWS))
        // The same from the file.
        val back = MatchLogBook.decode(b.encode())
        assertEquals(b[MatchRef(1, Comp.CUP, 1)], back[MatchRef(1, Comp.CUP, 1)])
        assertEquals(b[MatchRef(1, Comp.CUP, 2)], back[MatchRef(1, Comp.CUP, 2)])
        // Once last season's block is gone, the archive alone.
        b.rollover(2, 22L, 3, 33L, MatchLogBook.caps(1000), keep, archive)
        assertEquals(listOf(1, 4), b[MatchRef(1, Comp.CUP, 1)]?.games?.map { it.index })
    }

    @Test
    fun theArchiveKeepsThirtySeasons() {
        val b = book(1, 1L)
        for (s in 1..35) {
            b.put(rec(s, 1, LogClass.FINAL.bit))
            b.rollover(s, s.toLong(), s + 1, s + 1L, MatchLogBook.caps(1000), { 0 }) { listOf(MatchLogBook.ArchiveGame(MatchLogBook.TAG_CUP_FINAL)) }
        }
        assertEquals((6..35).toList(), b.archivedSeasons)
        assertEquals(MatchLogBook.ARCHIVE_SEASONS, b.count(MatchLogBook.ARCHIVE))
        val back = MatchLogBook.decode(b.encode())
        assertEquals(b.archivedSeasons, back.archivedSeasons)
        assertEquals(b[MatchRef(35, Comp.CUP, 1)], back[MatchRef(35, Comp.CUP, 1)])
    }

    @Test
    fun theFileReadsBackAndAnythingElseIsAnEmptyBook() {
        val b = book(4, -77L)
        b.put(rec(4, 1, LogClass.FINAL.bit, games = 3, tag = MatchLogBook.TAG_CL_FINAL, seed = 1234L))
        // A team board, a label in Korean, a week, a derived seed (not written).
        val derived = yacht.engine.DiceStream.mix(-77L, Comp.WORLD_CUP.code.code.toLong(), 300L)
        b.put(rec(4, 300, LogClass.KO.bit or LogClass.ADAPTIVE.bit, week = 41, comp = Comp.WORLD_CUP, seed = derived).copy(kind = JobKind.TEAM, teamA = "KR", teamB = "JP", label = "아시아컵 결승", ref = MatchRef(4, Comp.WORLD_CUP, 300, 4)))
        b.picks = listOf(40 to listOf(MatchRef(4, Comp.CUP, 1), MatchRef(4, Comp.LEAGUE, 2048)), 41 to emptyList())
        val bytes = b.encode()
        val back = MatchLogBook.decode(bytes)
        assertEquals(4, back.season)
        assertEquals(-77L, back.seed)
        assertEquals(b[MatchRef(4, Comp.CUP, 1)], back[MatchRef(4, Comp.CUP, 1)])
        val team = assertNotNull(back[MatchRef(4, Comp.WORLD_CUP, 300, 4)])
        assertEquals(b[MatchRef(4, Comp.WORLD_CUP, 300, 4)], team)
        assertEquals(derived, team.seed)
        assertEquals(b.picks, back.picks)
        assertEquals(b.bytes(MatchLogBook.CURRENT), back.bytes(MatchLogBook.CURRENT))
        assertTrue(bytes.contentEquals(back.encode()))
        // The record bytes are what the budget counts (the header, blocks and trailer aside).
        assertTrue(bytes.size > b.bytes(MatchLogBook.CURRENT) && bytes.size < b.bytes(MatchLogBook.CURRENT) + 60, "${bytes.size}")
        fun empty(x: MatchLogBook) = x.season == 0 && x.count(MatchLogBook.CURRENT) == 0 && x.picks.isEmpty()
        assertTrue(empty(MatchLogBook.decode(null)))
        assertTrue(empty(MatchLogBook.decode(ByteArray(0))))
        assertTrue(empty(MatchLogBook.decode(bytes.copyOf(bytes.size - 1))), "cut")
        for (at in listOf(3, 10, bytes.size / 2, bytes.size - 1)) {
            val bad = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x20).toByte() }
            assertTrue(empty(MatchLogBook.decode(bad)), "CRC error at $at")
        }
        // Another format version is not read.
        val other = bytes.copyOf().also { it[3] = (MatchLogBook.FORMAT + 1).toByte() }
        assertTrue(empty(MatchLogBook.decode(other)))
    }

    @Test
    fun blocksOfAnotherLeagueAreDropped() {
        // Season seeds as the off-season derives them.
        val players = (1..12).map { "q$it" }
        val ctx = Offseason.Context(players, { 1500.0 - players.indexOf(it) }, { null }, { "" }, LeagueConfig(divisions = 1, generated = 0))
        val first = Offseason.first(5L, ctx)
        val second = Offseason.next(first, ctx).first
        assertEquals(MatchLogBook.nextSeed(first.seed, first.number + 1), second.seed)
        val third = Offseason.next(second, ctx).first
        assertEquals(MatchLogBook.nextSeed(second.seed, 3), third.seed)
        fun ours(): MatchLogBook {
            val b = book(1, first.seed)
            for ((s, p) in listOf(first, second).withIndex()) {
                b.put(rec(s + 1, 1, LogClass.FINAL.bit))
                val next = if (s == 0) second else third
                b.rollover(p.number, p.seed, next.number, next.seed, MatchLogBook.caps(1000), { 0 }) { listOf(MatchLogBook.ArchiveGame(1)) }
            }
            return b
        }
        val b = ours()
        b.dropForeign(3, third.seed)
        assertEquals(listOf(1, 2), b.archivedSeasons)
        assertNotNull(b[MatchRef(2, Comp.CUP, 1)])
        assertEquals(3, b.season)
        // A book of another league (or after 리그 초기화): its seeds lead elsewhere.
        val other = ours()
        other.dropForeign(3, third.seed + 1)
        assertEquals(emptyList(), other.archivedSeasons)
        assertNull(other[MatchRef(2, Comp.CUP, 1)])
        assertEquals(0, other.season)
        // A book saved before season 2's close, the league after it: its season-2 block is kept
        // for the caller to roll over; one two closes behind, or of another seed, is not.
        val behind = book(1, first.seed).also { x ->
            x.put(rec(1, 1, LogClass.FINAL.bit))
            x.rollover(1, first.seed, 2, second.seed, MatchLogBook.caps(1000), { 0 }) { listOf(MatchLogBook.ArchiveGame(1)) }
            x.put(rec(2, 1, LogClass.FINAL.bit))
        }
        val saved = behind.encode()
        val late = MatchLogBook.decode(saved).also { it.dropForeign(3, third.seed) }
        assertEquals(2 to second.seed, late.season to late.seed)
        assertNotNull(late[MatchRef(2, Comp.CUP, 1)])
        late.rollover(2, second.seed, 3, third.seed, MatchLogBook.caps(1000), { 0 }) { listOf(MatchLogBook.ArchiveGame(1)) }
        assertEquals(listOf(1, 2), late.archivedSeasons)
        assertEquals(3, late.season)
        assertNotNull(late[MatchRef(2, Comp.CUP, 1)])
        val older = MatchLogBook.decode(saved).also { it.dropForeign(4, MatchLogBook.nextSeed(third.seed, 4)) }
        assertEquals(0, older.season)
        assertEquals(listOf(1), older.archivedSeasons)
        val wrong = MatchLogBook.decode(saved).also { it.dropForeign(3, third.seed + 1) }
        assertEquals(0, wrong.season)
        assertEquals(emptyList(), wrong.archivedSeasons)
        // One block of another seed among ours goes alone.
        val mixed = ours()
        mixed.dropForeign { s -> if (s == 1) first.seed + 1 else listOf(first, second, third)[s - 1].seed }
        assertEquals(listOf(2), mixed.archivedSeasons)
        assertEquals(3, mixed.season)
    }
}

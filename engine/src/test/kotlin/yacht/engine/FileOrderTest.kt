package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueWorld
import yacht.engine.league.TieRule
import yacht.engine.league.WorldBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The order the league's files are written in (design ENG 8.1, 8.3): honours, archive, stats,
 * roster, world, clubs, federation, highlights, news, lab, nations, ladder, season. world.txt sits
 * between the roster and the clubs, so a kill that leaves it unwritten leaves the club book and the
 * 요트협회 pre-close too. A folder of before (no world.txt) reads and writes back as it was, plus an
 * empty world book.
 */
class FileOrderTest {
    private val order = listOf(
        LeagueWorld.HONOURS, LeagueWorld.ARCHIVE, LeagueWorld.STATS, LeagueWorld.ROSTER, LeagueWorld.WORLD, LeagueWorld.CLUBS,
        LeagueWorld.FEDERATION, LeagueWorld.HIGHLIGHTS, LeagueWorld.NEWS, LeagueWorld.LAB, LeagueWorld.NATIONS, LeagueWorld.LADDER,
        LeagueWorld.SEASON,
    )

    @Test
    fun theFilesAreWrittenInTheirOrder() {
        val w = LeagueWorld(Ladder(listOf("a", "b", Ladder.ME)), LeagueConfig())
        val files = w.encodeFiles()
        assertEquals(order, files.keys.toList())
        assertEquals(LeagueWorld.FILES.toSet(), files.keys)
        assertEquals(LeagueWorld.FILES.size, files.size)
        assertEquals("world.txt", LeagueWorld.WORLD)
        assertEquals("${WorldBook.HEADER}\nguards 0 0 0 0\n", files.getValue(LeagueWorld.WORLD))
    }

    /** Made-up strength: the key's hash (no dice). */
    private fun strength(k: String): Int = k.hashCode() and 63

    private fun games(a: String, b: String, n: Int): List<GameScore> =
        List(n) { if (strength(a) >= strength(b)) GameScore(230, 190, aYacht = it == 0) else GameScore(190, 230) }

    private fun play(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f.a, f.b, 2).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f.a, f.b, 2 * f.legs).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games(x, y, 2), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    @Test
    fun aFolderOfBeforeReadsBackAsItWasPlusAnEmptyWorldBook() {
        val config = LeagueConfig.DEFAULT.copy(generated = 200, leagueMin = 20, divisionSize = 16)
        val w = LeagueWorld(Ladder(listOf(Ladder.ME)), config)
        w.players.ensureGenerated(config.generated)
        fun members() = w.players.alive.map { it.key } + LeagueRoster.ADAPTIVE
        w.members = members()
        w.ladder = Ladder(w.members + Ladder.ME)
        w.family = { Family.EV }
        w.openFirstSeason(5L)
        var guard = 0
        while (!w.seasonOver) {
            val f = w.next(emptySet(), 0, 2) ?: error("stuck")
            w.recordJob(play(f))
            check(++guard < 200_000)
        }
        w.ladder.fit()
        val c = w.closeSeason()
        if (c.rosterChanged) {
            w.members = members()
            w.ladder = Ladder.decode(w.members + Ladder.ME, w.ladder.encode())
        }
        w.openSeason()
        // A few weeks into the second season: the files as an older build would have them.
        repeat(400) { w.next(emptySet(), 0, 2)?.let { f -> w.recordJob(play(f)) } }
        val files = w.encodeFiles()
        assertTrue(files.getValue(LeagueWorld.CLUBS).startsWith("clubs 2\n"))
        assertTrue(files.getValue(LeagueWorld.CLUBS).lines().none { it.startsWith("cw ") }, "no club world before the migration")
        val before = files - LeagueWorld.WORLD
        val back = LeagueWorld.decode(before, w.ladder.keys, emptySet())
        assertNull(back.clubs!!.world)
        val again = back.encodeFiles()
        assertEquals(order, again.keys.toList())
        // The ladder refits its ratings when read (as it always has): its fitted fields may move.
        fun unfitted(ladder: String) = ladder.lines().map { l -> if (l.startsWith("p ")) l.split(' ').let { it.take(13) + it[14] }.joinToString(" ") else l }
        for ((name, text) in before) {
            if (name == LeagueWorld.LADDER) assertEquals(unfitted(text), unfitted(again.getValue(name)), name)
            else assertEquals(text, again.getValue(name), name)
        }
        assertEquals("world 1\nguards 0 0 0 0\n", again.getValue(LeagueWorld.WORLD))
        // A world.txt that cannot be read starts an empty book; so does one of another kind.
        for (bad in listOf("garbage", "world 9\nguards 1 1 1 1\n")) {
            assertEquals("world 1\nguards 0 0 0 0\n", LeagueWorld.decode(before + (LeagueWorld.WORLD to bad), w.ladder.keys, emptySet()).encodeFiles().getValue(LeagueWorld.WORLD))
        }
        // In season 2: a book that closed season 1 is this league's and kept whole; one that closed a
        // season not played yet is another league's (its guards would skip this league's closes): empty.
        assertEquals(2, back.plan!!.number)
        val ours = "world 1\nguards 1 1 1 2\nera 1 B 1 0 190\nev 1 BOOM X 1 2 0 NZ - -\n"
        assertEquals(ours, LeagueWorld.decode(before + (LeagueWorld.WORLD to ours), w.ladder.keys, emptySet()).encodeFiles().getValue(LeagueWorld.WORLD))
        for (theirs in listOf("world 1\nguards 7 7 7 7\nera 1 B 3 0 190\n", "world 1\nguards 1 1 1 3\nera 1 B 1 0 190\n", "world 1\nguards 0 3 0 0\n")) {
            assertEquals("world 1\nguards 0 0 0 0\n", LeagueWorld.decode(before + (LeagueWorld.WORLD to theirs), w.ladder.keys, emptySet()).encodeFiles().getValue(LeagueWorld.WORLD), theirs)
        }
        // Stats of a season ahead (another league's, as before): the world book goes with them.
        val ahead = before + (LeagueWorld.STATS to before.getValue(LeagueWorld.STATS).replaceFirst(Regex("(?m)^book (\\d+) \\d+ "), "book $1 9 ")) + (LeagueWorld.WORLD to ours)
        assertTrue(ahead.getValue(LeagueWorld.STATS) != before.getValue(LeagueWorld.STATS), "the stats name a later close")
        assertEquals("world 1\nguards 0 0 0 0\n", LeagueWorld.decode(ahead, w.ladder.keys, emptySet()).encodeFiles().getValue(LeagueWorld.WORLD))
    }
}

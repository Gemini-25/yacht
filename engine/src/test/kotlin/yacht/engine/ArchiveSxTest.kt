package yacht.engine

import yacht.engine.league.Archive
import yacht.engine.league.ClubBook
import yacht.engine.league.Field
import yacht.engine.league.Honour
import yacht.engine.league.LeagueIds
import yacht.engine.league.SeasonLine
import yacht.engine.league.SeasonSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The archive's league and club per season line (ENG 1.9): one `sx` line per player with an
 * entry only where they change, read back onto the season lines, and a file an older version
 * (the 945de5e decoder, copied below) reads as before, every season line included.
 */
class ArchiveSxTest {
    private fun line(season: Int, league: String = "", club: String? = null, tier: Int = 0) =
        SeasonLine(season, tier, 3, 20, 4, 14, 1500.0 + season, 7.25, league, club)

    @Test
    fun anEntryIsWrittenOnlyWhereTheLeagueOrClubChanges() {
        val a = Archive()
        // Legacy, legacy with a club, then 한국 리그 (two seasons, then another club), abroad in
        // Japan (a season missed in between), home again without a club.
        val lines = listOf(
            line(1), line(2, club = "c03"), line(3, "KR", "c03", 1), line(4, "KR", "c03"), line(5, "KR", "c07"),
            line(6, "JP", "c07"), line(8, "JP", "c07"), line(9, "KR", null, 2),
        )
        for (l in lines) a.addLine("p1", l)
        a.addLine("p2", line(1))
        val text = a.encode()
        // Season 2 digits, league code 1 (KR 1, JP 2), club 2.
        val sx = text.lines().filter { it.startsWith("sx ") }
        assertEquals(listOf("sx p1 02003 03103 05107 06207 09100"), sx)
        val back = Archive.decode(text)
        assertEquals(a.lines("p1"), back.lines("p1"))
        assertEquals(lines.map { Triple(it.season, it.league, it.club) }, back.lines("p1").map { Triple(it.season, it.league, it.club) })
        assertEquals(lines.map { it.division }, back.lines("p1").map { it.division })
        assertEquals(a.lines("p2"), back.lines("p2"))
        assertEquals(text, back.encode())
    }

    @Test
    fun aLineTakesTheNewestEntryOfItsSeasonOrBefore() {
        val a = Archive()
        a.addLine("x", line(4))
        a.addLine("x", line(5))
        a.addLine("x", line(7))
        a.addLine("x", line(8))
        val sl = a.encode().lines().single { it.startsWith("sl ") }
        // An entry in the season missed (6), one for a season before the first line, bad entries, and a player without lines.
        val text = "archive 1\n$sl\nsx x 03101 06204 0 zz 07700 x!z01\nsx gone 01100\n"
        assertEquals("JP", LeagueIds.ofCode(2))
        val seventh = LeagueIds.ofCode(7)
        assertTrue(seventh.length == 2)
        val back = Archive.decode(text)
        // Seasons 4 and 5 take season 3's entry; 7 and 8 season 7's (it replaced season 6's).
        assertEquals(
            listOf(Triple(4, "KR", "c01"), Triple(5, "KR", "c01"), Triple(7, seventh, null), Triple(8, seventh, null)),
            back.lines("x").map { Triple(it.season, it.league, it.club) },
        )
        assertTrue(back.lines("gone").isEmpty())
        // Written again: one entry where the league starts, one where it changes.
        assertEquals(listOf("sx x 04101 07700"), back.encode().lines().filter { it.startsWith("sx ") })
    }

    @Test
    fun whatTheArchiveCannotHoldIsLeftOutTheSameWayInMemoryAndOnFile() {
        val a = Archive()
        // A league this version does not know, club ids it cannot hold, a club number written short.
        a.addLine("p", line(1, "ZZ", "x9"))
        a.addLine("p", line(2, "KR", "c1296"))
        a.addLine("p", line(3, "KR", "c5"))
        a.addLine("p", line(4, "XWO", ClubBook.idOf(1295)))
        val mem = a.lines("p")
        assertEquals(listOf("" to null, "KR" to null, "KR" to ClubBook.idOf(5), "XWO" to ClubBook.idOf(1295)), mem.map { it.league to it.club })
        assertEquals(mem, Archive.decode(a.encode()).lines("p"))
    }

    @Test
    fun theEntriesAgeOutWithTheLines() {
        val a = Archive()
        // 40 seasons: KR from season 5, a club from season 20; a retired player's lines end at season 9.
        for (n in 1..40) {
            a.addLine("p", line(n, if (n >= 5) "KR" else "", if (n >= 20) "c02" else null))
            if (n < 10) a.addLine("old", line(n, "JP", "c09"))
            a.putSummary(SeasonSummary(n, listOf(Honour(n, "L0.KR", "p")), null, Double.NaN, emptyList(), 0, Double.NaN))
        }
        val text = a.encode()
        // The newest 30 seasons (11-40): the first kept line carries KR again.
        assertEquals(listOf("sx p 0b100 0k102"), text.lines().filter { it.startsWith("sx ") })
        assertEquals((11..40).toList(), Archive.decode(text).lines("p").map { it.season })
        assertTrue(Archive.decode(text).lines("p").all { it.league == "KR" && (it.club == "c02") == (it.season >= 20) })
        assertTrue(a.lines("old").isEmpty())
        assertTrue("sx old" !in text)
    }

    @Test
    fun anOlderVersionReadsTheNewFileWithEverySeasonLine() {
        val a = Archive()
        val ids = listOf("", "KR", "JP", "XAS", "XWO", "US")
        for (k in 0 until 200) {
            for (n in 1..12) {
                val league = ids[(k + n / 5) % ids.size]
                a.addLine("p$k", SeasonLine(n, (k + n) % 4, 1 + k % 20, k % 30, n % 3, 29 - k % 30, 1200.0 + 3 * k + n, 5.0 + (k % 50) / 10.0, league, if (k % 3 == 0) null else "c${k % 97 + 1}"))
            }
        }
        a.putSummary(SeasonSummary(12, listOf(Honour(12, "L0.KR", "p1"), Honour(12, "CUP", "p2")), "p3", 231.5, emptyList(), 4000, 215.25))
        val text = a.encode()
        assertTrue(text.lines().count { it.startsWith("sx ") } > 100)
        val old = OldArchive.decode(text)
        val now = Archive.decode(text)
        assertEquals(now.lineKeys, old.lines.keys)
        for (k in now.lineKeys) {
            assertEquals(now.lines(k).map { OldArchive.Line(it.season, it.division, it.position, it.wins, it.draws, it.losses, it.rating, it.avgRating) }, old.lines.getValue(k), k)
        }
        assertEquals(listOf("L0.KR:p1", "CUP:p2"), old.summaries.getValue(12))
        // About 6 bytes an entry: the league and club changes of 200 careers in a few KB.
        val sxBytes = text.lines().filter { it.startsWith("sx ") }.sumOf { it.length + 1 }
        assertTrue(sxBytes < 200 * 40, "$sxBytes")
    }

    @Test
    fun aLegacyArchiveHasNoSxLines() {
        val a = Archive()
        a.addLine("p", line(1))
        a.addLine("p", line(2, tier = 3))
        assertTrue(a.encode().lines().none { it.startsWith("sx") })
        assertNull(a.lines("p").first().club)
    }
}

/**
 * Archive.decode as it was at 945de5e (the season lines and the summaries; the other tags are
 * read as then and left aside here): its `when` has no else, so it skips tags it does not know.
 */
private object OldArchive {
    data class Line(val season: Int, val division: Int, val position: Int, val wins: Int, val draws: Int, val losses: Int, val rating: Double, val avgRating: Double)

    class Read(val lines: Map<String, List<Line>>, val summaries: Map<Int, List<String>>)

    private const val WIDTH = 13
    private const val RATING_OFFSET = 800
    private const val NONE = 36 * 36 - 1
    private const val LINE_SEASONS = 30

    private fun ratingOf(code: Int) = if (code == 0) Double.NaN else (2 * code - RATING_OFFSET).toDouble()
    private fun avgOf(code: Int) = if (code == NONE) Double.NaN else code / 100.0

    private fun unpackLines(first: Int, packed: String): List<Line> {
        val out = ArrayList<Line>()
        var season = first
        var i = 0
        fun take(width: Int): Int = packed.substring(i, i + width).toInt(36).also { i += width }
        while (i < packed.length) {
            if (packed[i] == '.') {
                i++
                season++
                continue
            }
            if (i + WIDTH > packed.length) break
            val d = take(1)
            val pos = take(2)
            val w = take(2)
            val dr = take(2)
            val l = take(2)
            val r = take(2)
            val a = take(2)
            out += Line(season, d, pos, w, dr, l, ratingOf(r), avgOf(a))
            season++
        }
        return out
    }

    fun decode(text: String): Read {
        val all = text.lines()
        require(all.firstOrNull()?.trim() == "archive 1") { "not an archive file" }
        val lines = if (text.endsWith('\n')) all else all.dropLast(1)
        val lineMap = HashMap<String, List<Line>>()
        val summaries = HashMap<Int, List<String>>()
        for (line in lines) {
            runCatching {
                val f = line.split(' ')
                when (f[0]) {
                    "sum" -> {
                        val season = f[1].toInt()
                        summaries[season] = if (f[6] == "-") emptyList() else f[6].split(',').map { h ->
                            val p = h.split(':')
                            "${p[0]}:${Field.unesc(p[1])}"
                        }
                    }
                    "sl" -> {
                        val list = unpackLines(f[2].toInt(), f[3])
                        if (list.isNotEmpty()) lineMap[Field.unesc(f[1])] = ArrayList(list.takeLast(LINE_SEASONS))
                    }
                    "dyn", "hof", "hl", "rv" -> Unit
                }
            }
        }
        return Read(lineMap, summaries)
    }
}

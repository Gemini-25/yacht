package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubTable
import yacht.engine.league.Honour
import yacht.engine.league.Leagues
import yacht.engine.league.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The club table of a league (F1 constructors' style, design ENG 3.4). */
class ClubTableTest {
    /** A national book read from hand-written club lines: id, nation and members (no escapes needed). */
    private fun book(vararg clubs: Triple<String, String, List<String>>): ClubBook = ClubBook.decode(
        buildString {
            append("clubs 2\nmeta 1 0 - 100\n")
            for ((id, nation, members) in clubs) append("club $id $nation 도시 $id 도시%20$id 0 ffc62828 ffffffff 가 100 0 1 ${members.joinToString(",").ifEmpty { "-" }}\n")
        },
    )

    /** A season of [leagues] (id to its divisions, best first) whose standings get [points]. */
    private fun season(leagues: List<Pair<String, List<List<String>>>>, points: Map<String, Int>): Season {
        val s = Season.start(1, 9L, leagues.flatMap { it.second }, false, Leagues.national(leagues.map { it.first }, leagues.map { it.second.size }))
        for ((k, p) in points) s.standings.getValue(k).points = p
        return s
    }

    @Test
    fun theWorkedExampleScores247() {
        // 1부: A 60, B 45; 2부: C 70 → 42, D 50 → 30, E 40 → 24, F 30 → 18, G 20 → 12.
        // Best 6: 60 + 45 + 42 + 30 + 24 + 18 = 219; A won L0.KR (+20) and C L1.KR (+8): 247.0.
        val s = season(
            listOf("KR" to listOf(listOf("A", "B", "x1", "x2"), listOf("C", "D", "E", "F", "G", "x3"))),
            mapOf("A" to 60, "B" to 45, "C" to 70, "D" to 50, "E" to 40, "F" to 30, "G" to 20, "x1" to 90, "x2" to 10, "x3" to 5),
        )
        val clubs = book(Triple("c01", "KR", listOf("A", "B", "C", "D", "E", "F", "G")), Triple("c02", "KR", listOf("x1", "x2", "x3")))
        val honours = listOf(Honour(1, "L0.KR", "A"), Honour(1, "L1.KR", "C"), Honour(1, "REG.KR", "x1"))
        val table = ClubTable.of(s, clubs, "KR", honours)
        val c01 = table.first { it.club == "c01" }
        assertEquals(247.0, c01.points)
        assertEquals(219.0, c01.leaguePart)
        assertEquals(28.0, c01.bonus)
        // G (12) is the seventh and does not count; A counts first with its title.
        assertEquals(listOf("A", "C", "B", "D", "E", "F"), c01.counted)
        assertEquals(7, c01.members)
        assertEquals(105, c01.topTierPoints)
        // c02: 90 + 5 (REG) + 10 + 5 × 0.6 = 108.0, second.
        assertEquals(listOf("c01", "c02"), table.map { it.club })
        assertEquals(108.0, table[1].points)
    }

    @Test
    fun tierWeightsAndHonourPoints() {
        assertEquals(listOf(1.0, 0.6, 0.4, 0.3, 0.25, 0.2, 0.2, 0.2), (0..7).map { ClubTable.weight(it) })
        val kinds = mapOf(
            "L0.KR" to 20, "L0" to 20, "REG.KR" to 5, "L0R.KR" to 10, "L1.KR" to 8, "L4.JP" to 8, "L3" to 8, "LMVP.KR" to 10,
            "CUP" to 15, "CUP2" to 8, "CL" to 30, "CL2" to 15, "CCL.ASIA" to 20, "CCL2.EUROPE" to 10, "SUPER" to 5, "MVP" to 15,
            "SCORER" to 5, "YACHT" to 5, "WCW" to 5, "POTW" to 0, "CLUBCUP" to 0, "CLUBCUPW" to 0, "ROOKIE" to 0, "UP2" to 0,
        )
        for ((kind, points) in kinds) assertEquals(points, ClubTable.bonusOf(kind), kind)
        // A tier's weight in a table: one 2부 player of 50 is 30.0.
        val s = season(listOf("JP" to listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f"), listOf("g", "h"), listOf("i", "j"), listOf("k", "l"), listOf("m", "n"))), mapOf("c" to 50, "m" to 50))
        val clubs = book(Triple("c01", "JP", listOf("c")), Triple("c02", "JP", listOf("m")))
        assertEquals(listOf(30.0, 10.0), ClubTable.of(s, clubs, "JP", emptyList()).map { it.points })
    }

    @Test
    fun aRegionalLeagueHasTheClubsOfItsNationsAndAMemberAbroadScoresForItsClub() {
        // 아시아 연합 리그 of Korea and Japan; China plays its own league. "kj" is Japanese in a Korean club.
        val s = season(
            listOf("CN" to listOf(listOf("c1", "c2")), "XAS" to listOf(listOf("k1", "k2", "j1", "kj"))),
            mapOf("k1" to 10, "k2" to 20, "j1" to 25, "kj" to 9, "c1" to 99),
        )
        val clubs = book(
            Triple("c01", "KR", listOf("k1", "k2", "kj")), Triple("c02", "JP", listOf("j1")),
            Triple("c03", "CN", listOf("c1", "c2")), Triple("c04", "JP", emptyList()),
        )
        val table = ClubTable.of(s, clubs, "XAS", emptyList())
        // The empty Japanese club is in the table too (its nation plays here); China's is not.
        assertEquals(listOf("c01", "c02", "c04"), table.map { it.club })
        assertEquals(39.0, table[0].points)
        assertTrue(table.all { it.league == "XAS" })
        assertEquals(listOf("c03"), ClubTable.of(s, clubs, "CN", emptyList()).map { it.club })
        assertEquals(emptyList(), ClubTable.of(s, clubs, "KR", emptyList()))
        assertEquals(setOf("CN", "XAS"), ClubTable.all(s, clubs, emptyList()).keys)
    }

    @Test
    fun tiesGoToTheTopTierThenTheBestMemberThenTheId() {
        // c01 and c02 both 60: c02 has 60 in the 1부, c01 100 × 0.6 in the 2부.
        val s = season(
            listOf("KR" to listOf(listOf("a", "b", "c", "d"), listOf("e", "f", "g", "h"))),
            mapOf("b" to 60, "e" to 100, "c" to 30, "d" to 30, "f" to 50, "g" to 10),
        )
        val tied = book(Triple("c01", "KR", listOf("e")), Triple("c02", "KR", listOf("b")))
        assertEquals(listOf("c02", "c01"), ClubTable.of(s, tied, "KR", emptyList()).map { it.club })
        // c03 = 30 + 30 and c04 = 0 + 60, all in the 1부: the best member, b's 60 against c's 30.
        val best = book(Triple("c03", "KR", listOf("c", "d")), Triple("c04", "KR", listOf("a", "b")))
        assertEquals(listOf("c04", "c03"), ClubTable.of(s, best, "KR", emptyList()).map { it.club })
        // All level: by id, c9 before c10.
        val level = book(Triple("c10", "KR", listOf("c")), Triple("c9", "KR", listOf("d")))
        assertEquals(listOf("c9", "c10"), ClubTable.of(s, level, "KR", emptyList()).map { it.club })
    }
}

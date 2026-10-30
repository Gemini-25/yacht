package yacht.engine

import yacht.engine.league.ContinentalChampions
import yacht.engine.league.Honour
import yacht.engine.league.Leagues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Who plays the 대륙 챔피언스 for a league (ENG 4.2): last season's champion and 1부 table, found
 * at the league's 1부 of last season ([yacht.engine.league.SeasonStart.lastLeagues]); a league new
 * this season, and every league after a legacy season, by its 1부's rating now.
 */
class CclEntrantsTest {
    private val ten = listOf(10, 10)

    /** This season: KR, JP, CN, VN with two divisions each (KR first in league order). */
    private val now = NationalStart(listOf("KR" to ten, "JP" to ten, "CN" to ten, "VN" to ten))

    @Test
    fun aLeagueThatPlayedLastSeasonSendsItsChampionThenItsTable() {
        // Last season VN had three divisions, so KR's 1부 was division 3 then (division 0 now).
        val lastLeagues = Leagues.national(listOf("VN", "KR", "JP", "CN"), listOf(3, 2, 2, 2))
        val last = lastLeagues.list.flatMap { lg -> lg.divisions.map { d -> lg.id to d } }
        // Last season's tables: each league's divisions in a shuffled order of this season's players.
        val tables = last.map { (id, d) ->
            val tier = d - lastLeagues.byId(id)!!.first
            now.divisions[now.leagues.byId(id)!!.first + minOf(tier, 1)].reversed()
        }
        assertNotEquals(lastLeagues.byId("KR")!!.top, now.leagues.byId("KR")!!.top)
        // The title playoff's champion was 3rd in the table; one player of the table has left.
        val krTable = tables[lastLeagues.byId("KR")!!.top]
        val champion = krTable[2]
        val gone = krTable[0]
        val start = now.start(
            lastTables = tables, lastLeagues = lastLeagues, lastHonours = listOf(Honour(1, "L0.KR", champion), Honour(1, "L0.JP", "jp0x05")),
            world = now.keys - gone,
        )
        val kr = ContinentalChampions.candidates(start, "KR")
        assertEquals(listOf(champion) + (krTable - champion - gone), kr)
        // Never the table of the division KR's 1부 is now (that was VN's 1부 last season).
        assertNotEquals(tables[now.leagues.byId("KR")!!.top].take(3), kr.take(3))
        // A league without a champion's honour sends its table as it ended.
        assertEquals(tables[lastLeagues.byId("CN")!!.top], ContinentalChampions.candidates(start, "CN"))
        // In the competition KR (the strongest) has seeds 1-4: its champion and the table's next three.
        val sec = assertNotNull(ContinentalChampions.create(start)).sections.single()
        assertEquals(kr.take(4), sec.groups.flatten().sortedBy { it.seed }.take(4).map { it.key })
        assertEquals(kr.take(4).toSet(), sec.groups.flatten().filter { it.key.startsWith("kr") }.map { it.key }.toSet())
    }

    @Test
    fun aQualifiedPlayerWhoMovedAbroadStillPlaysForItsLeague() {
        val lastLeagues = Leagues.national(listOf("KR", "JP", "CN", "VN"), listOf(2, 2, 2, 2))
        val tables = now.divisions.map { it }.toMutableList()
        // jp0x01 finished 2nd in KR's 1부 last season and plays in Japan now.
        tables[0] = listOf(tables[0][0], "jp0x01") + tables[0].drop(1)
        tables[2] = tables[2] - "jp0x01"
        val start = now.start(lastTables = tables, lastLeagues = lastLeagues, lastHonours = listOf(Honour(1, "L0.KR", "kr0x00")))
        assertEquals(listOf("kr0x00", "jp0x01"), ContinentalChampions.candidates(start, "KR").take(2))
        assertTrue("jp0x01" !in ContinentalChampions.candidates(start, "JP"))
        val sec = assertNotNull(ContinentalChampions.create(start)).sections.single()
        assertTrue(sec.groups.flatten().any { it.key == "jp0x01" })
        // It counts as one of KR's in the draw (at most two of a league in a group).
        val group = sec.groups.single { g -> g.any { it.key == "jp0x01" } }
        assertTrue(group.count { it.key.startsWith("kr") || it.key == "jp0x01" } <= 2, group.map { it.key }.toString())
    }

    @Test
    fun aLeagueNewThisSeasonGoesByRating() {
        // Last season VN played in the 아시아 연합 리그 (XAS); this season it has its own league.
        val lastLeagues = Leagues.national(listOf("KR", "JP", "CN", "XAS"), listOf(2, 2, 2, 2))
        val tables = now.divisions.map { it.reversed() }
        val start = now.start(lastTables = tables, lastLeagues = lastLeagues, lastHonours = listOf(Honour(1, "L0.XAS", "vn0x09")))
        assertEquals(now.top("VN"), ContinentalChampions.candidates(start, "VN"))
        assertEquals(now.top("VN").sortedByDescending(now::rating), now.top("VN"))
        // KR played last season: its table, champion first (none here: the table as it ended).
        assertEquals(tables[0], ContinentalChampions.candidates(start, "KR"))
    }

    @Test
    fun theTransitionSeasonGoesByRating() {
        // The season before was legacy: one league of four divisions.
        val tables = now.divisions.take(4).map { it.reversed() }
        val start = now.start(lastTables = tables, lastHonours = listOf(Honour(1, "L0", "kr0x09")))
        for (id in listOf("KR", "JP", "CN", "VN")) assertEquals(now.top(id), ContinentalChampions.candidates(start, id), id)
        val sec = assertNotNull(ContinentalChampions.create(start)).sections.single()
        // The four best of every 1부.
        assertEquals(listOf("KR", "JP", "CN", "VN").flatMap { now.top(it).take(4) }.toSet(), sec.groups.flatten().map { it.key }.toSet())
    }
}

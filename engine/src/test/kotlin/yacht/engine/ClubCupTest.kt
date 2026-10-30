package yacht.engine

import yacht.engine.league.ClubCup
import yacht.engine.league.ClubSource
import yacht.engine.league.Comp
import yacht.engine.league.Competitions
import yacht.engine.league.Family
import yacht.engine.league.FamilyCup
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Leagues
import yacht.engine.league.MatchFormat
import yacht.engine.league.Season
import yacht.engine.league.SeasonStart
import yacht.engine.league.byTeams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 클럽 대항전 ([yacht.engine.league.ClubCup]) and the family cup it shares its format with
 * ([yacht.engine.league.TeamCup]): the family cup plays and saves as before the format was
 * shared (the hashes were taken from the code before that).
 */
class ClubCupTest {
    private fun key(i: Int) = "ai%02d".format(i)
    private fun index(k: String) = k.removePrefix("ai").toInt()
    private fun rating(k: String) = 1700.0 - 20 * index(k)
    private val families = Family.entries.filter { it != Family.MINE }
    private fun family(k: String): Family? = families[(index(k) - 1) % families.size]
    private val print = CompetitionPrint(::rating)

    private fun familyStart(n: Int, number: Int, config: LeagueConfig, history: List<Honour>): SeasonStart {
        val keys = (1..n).map(::key)
        return SeasonStart(number, 777L + number, config, keys, ::rating, Season.split(keys, 3), emptyList(), emptyList(), ::family, { "서울" }, history)
    }

    @Test
    fun theFamilyCupPlaysAsBeforeItsFormatWasShared() {
        // Last edition's squads: some called up again, some dropped, one family new.
        val history = (1..60).filter { it % 3 != 0 }.map { Honour(2, "FSQ", key(it)) }
        val runs = listOf(
            "single" to LeagueConfig(matchFormat = MatchFormat.MIRRORED, seriesKnockouts = false, doubleElimination = false),
            "default" to LeagueConfig(),
        )
        val got = runs.flatMap { (name, config) ->
            listOf(45, 70).map { n ->
                val cup = assertNotNull(FamilyCup.create(familyStart(n, 5, config, history)), name)
                "$name $n" to print.sha(print.fingerprint(cup, setOf(5, 13), season = 5, seed = 782L))
            }
        }
        for ((name, hash) in got) println("$name $hash")
        assertEquals(FAMILY_GOLDEN, got.toMap())
    }

    // ---- the club cup ----------------------------------------------------------------------------

    /** 15 leagues of 2 divisions of 10, the first the strongest; club "c<n>" of league n has 8 members of its two divisions. */
    private val ids = listOf("KR", "JP", "CN", "VN", "IN", "TH", "AU", "GB", "FR", "DE", "ES", "IT", "US", "BR", "AR")
    private val world = NationalStart(ids.map { it to listOf(10, 10) })
    private val clubPrint = CompetitionPrint(world::rating)

    private fun division(n: Int, tier: Int) = world.divisions[world.leagues.byId(ids[n])!!.first + tier]

    private inner class Clubs(val champions: List<String>) : ClubSource {
        val members = ids.indices.associate { n -> "c$n" to (division(n, 0).take(5) + division(n, 1).take(3)).shuffled(java.util.Random(n.toLong())) }
        val asked = ArrayList<Int>()
        override fun champions(season: Int): List<String> = champions.also { asked += season }
        override fun members(club: String): List<String> = members[club].orEmpty()
    }

    private fun start(number: Int, config: LeagueConfig = LeagueConfig(national = true), last: Leagues? = world.leagues, keys: List<String> = world.keys) =
        if (last == null) world.start(number, config, world = keys) else world.start(number, config, lastTables = world.divisions, lastLeagues = last, world = keys)

    @Test
    fun theClubCupComesInEvenSeasonsAfterANationalOne() {
        assertEquals(listOf(2, 4, 6, 8), (1..8).filter { ClubCup.due(it) })
        val clubs = Clubs(ids.indices.map { "c$it" })
        assertNotNull(ClubCup.create(start(4), clubs))
        assertEquals(listOf(3), clubs.asked, "last season's champions")
        assertNull(ClubCup.create(start(5), clubs), "odd season")
        assertNull(ClubCup.create(start(4, last = null), clubs), "the first national season")
        assertNull(ClubCup.create(start(4, config = LeagueConfig(national = true, clubCup = false)), clubs), "off")
        val keys = world.keys
        val legacy = SeasonStart(4, 1L, LeagueConfig(), keys, world::rating, Season.split(keys, 4), Season.split(keys, 4), emptyList(), { null }, { "" })
        assertNull(ClubCup.create(legacy, clubs), "a legacy season")
        // Fewer than 4 clubs with a squad: none. No club book: none.
        assertNull(ClubCup.create(start(4), Clubs(listOf("c0", "c1", "c2"))))
        assertNull(ClubCup.factory { null }(start(4)))
        assertEquals(Comp.CLUB_CUP, ClubCup.factory { clubs }(start(4))?.comp)
        assertTrue(Comp.CLUB_CUP.byTeams)
    }

    @Test
    fun theTwelveStrongestChampionsPlayWithTheirBestFive() {
        // All but two of c1's members left the world: too few for a squad. A club named twice plays once.
        val clubs = Clubs(ids.indices.map { "c$it" } + "c0" + "gone")
        val left = clubs.members.getValue("c1").drop(2).toSet()
        val cup = assertNotNull(ClubCup.create(start(4, keys = world.keys - left), clubs))
        // Leagues earlier in the list are stronger: c0 and c2..c12 make it, c13 and c14 do not.
        assertEquals((listOf(0) + (2..12)).map { "c$it" }, cup.teams.map { it.id })
        assertEquals(listOf(4, 4, 4), cup.groups.map { it.size })
        for (t in cup.teams) {
            val n = t.id.drop(1).toInt()
            assertEquals(division(n, 0).take(5), t.squad, "the best five by rating, board 1 first")
            assertEquals(t.squad.map(world::rating).average(), t.strength)
        }
        assertEquals(listOf("draw_clubcup"), cup.opening().map { it.kind }.distinct())
        assertEquals(cup.groups.size, cup.opening().size)
    }

    @Test
    fun theClubCupPlaysSavesAndHonoursItsWinners() {
        val clubs = Clubs(ids.indices.map { "c$it" })
        for (config in listOf(LeagueConfig(national = true), LeagueConfig(national = true, doubleElimination = false))) {
            val cup = assertNotNull(ClubCup.create(start(6, config), clubs))
            val text = clubPrint.fingerprint(cup, setOf(4, 12, 20), season = 6, seed = 186L)
            assertTrue(text.contains("clubcup 1"), "its own header")
            assertTrue(text.lines().filter { it.startsWith("f ") }.all { it.split(' ')[6] == "TEAM" })
            // Team matches of 5 boards with the clubs' squads.
            assertTrue(text.lines().filter { it.startsWith("f ") }.all { it.substringAfter("[").substringBefore("]").split(", ").size == 5 })
            // Played out, saved and read back: the same honours.
            val played = assertNotNull(ClubCup.create(start(6, config), clubs))
            var p = 0.0
            while (!played.finished) {
                val f = played.next(p, emptySet())
                if (f == null) {
                    p += 0.05
                    check(p < 2.0)
                    continue
                }
                played.record(clubPrint.play(f))
            }
            val back = ClubCup.codec.decode(clubPrint.text(played).lines().filter { it.isNotEmpty() }, 6, 186L)
            assertEquals(played.honours(), back.honours())
            val h = back.honours()
            assertEquals(listOf("CLUBCUP", "CLUBCUP2"), h.take(2).map { it.kind })
            val winner = cup.teams.single { it.id == h[0].key }
            assertEquals(winner.squad.map { Honour(6, "CLUBCUPW", it) }, h.drop(2))
        }
        assertTrue(Competitions.codecs.getValue(Comp.CLUB_CUP) === ClubCup.codec)
        assertTrue(Competitions.codecs.getValue(Comp.FAMILY) === FamilyCup.codec)
    }

    companion object {
        private val FAMILY_GOLDEN = mapOf(
            "single 45" to "b1b6299c20e1c80f86812eb11b87a9ed09a41fbe09dff79b654e915cdf5ebd01",
            "single 70" to "0b6b11b327e2e1cf06fa4ef6ef669badf0cb5a69fe86c08fc14f0e72a4bb19b0",
            "default 45" to "952350a20f55626efe0510ce8983c725aa45a4014e5af390ddc4584d3440d088",
            "default 70" to "d1b81cd4f57135b8220162ad97aa86850813fe64713881a6c843e8d0e80bab9e",
        )
    }
}

package yacht.engine

import yacht.engine.league.Family
import yacht.engine.league.FamilyCup
import yacht.engine.league.Honour
import yacht.engine.league.LeagueConfig
import yacht.engine.league.MatchFormat
import yacht.engine.league.Season
import yacht.engine.league.SeasonStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

    companion object {
        private val FAMILY_GOLDEN = mapOf(
            "single 45" to "b1b6299c20e1c80f86812eb11b87a9ed09a41fbe09dff79b654e915cdf5ebd01",
            "single 70" to "0b6b11b327e2e1cf06fa4ef6ef669badf0cb5a69fe86c08fc14f0e72a4bb19b0",
            "default 45" to "952350a20f55626efe0510ce8983c725aa45a4014e5af390ddc4584d3440d088",
            "default 70" to "d1b81cd4f57135b8220162ad97aa86850813fe64713881a6c843e8d0e80bab9e",
        )
    }
}

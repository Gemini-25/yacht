package yacht.engine

import yacht.engine.league.Academy
import yacht.engine.league.AcademyInput
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubInput
import yacht.engine.league.LeagueConfig
import yacht.engine.league.Nations
import yacht.engine.league.PlayerNames
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The academies do not concentrate talent (design ENG 1b.3): over 20 synthetic off-seasons of a
 * KR-shaped nation (eight clubs in its eight biggest cities, rookies' hometowns drawn by the city
 * weights, so a quarter come from 서울; academies of every level, re-drawn every five seasons), no
 * club gets more than twice its fair share of each season's ten best rookies; not even 서울's club
 * with the best academy all along.
 */
class AcademyFairnessTest {
    /** Each club's count of the seasons' [TOP] best rookies over [SEASONS] off-seasons of seed [seed]; [seoul] fixes c01's academy at 5. */
    private fun shares(seed: Long, seoul: Boolean): Map<String, Int> {
        val book = Eco.world(Eco.book((0 until CLUBS).map { i -> Eco.Spec(ClubBook.idOf(i + 1), "KR", (1..6).map { "m${i}_$it" }, city = i) }, seed = seed))
        val base = book.clubs.flatMap { it.squad }.toSet()
        val rng = Random(seed)
        val got = HashMap<String, Int>()
        for (s in 1..SEASONS) {
            if (s % 5 == 1) for (c in book.clubs) book.world!!.state(c.id).academy = if (seoul && c.id == "c01") 5 else 1 + rng.nextInt(5)
            val rookies = (1..ROOKIES).map { "r${s}_$it" }
            val town = rookies.associateWith { PlayerNames.hometown(Nations.KR, rng) }
            val pot = rookies.associateWith { 40 + rng.nextInt(56) }
            val input = ClubInput(base + rookies, { 1500.0 }, { "KR" }, LeagueConfig(), age = { 18 })
            Academy.draft(book, s, input, AcademyInput({ town[it] }, { pot[it] }), base)
            for (k in rookies.sortedWith(compareByDescending<String> { pot.getValue(it) }.thenBy { it }).take(TOP)) got.merge(book.clubOf(k)!!, 1, Int::plus)
            // The next season starts from the same squads.
            for (k in rookies) book.place(k, null)
        }
        return got
    }

    @Test
    fun noClubGetsMoreThanTwiceItsShareOfTheBestRookies() {
        val fair = SEASONS * TOP / CLUBS.toDouble()
        for (seoul in listOf(false, true)) for (seed in 1L..8L) {
            val got = shares(seed, seoul)
            val max = got.values.max()
            println("AcademyFairnessTest ${if (seoul) "서울 academy 5" else "random academies"}, seed $seed: ${got.toSortedMap(ClubBook.ID_ORDER)} (fair ${"%.1f".format(fair)}, max $max)")
            assertTrue(max <= 2 * fair, "seed $seed: $max > 2 × $fair ($got)")
        }
    }

    private companion object {
        const val CLUBS = 8
        const val ROOKIES = 16
        const val TOP = 10
        const val SEASONS = 20
    }
}

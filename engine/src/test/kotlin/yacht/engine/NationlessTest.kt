package yacht.engine

import yacht.engine.league.CustomAis
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRoster
import yacht.engine.league.NationalLeagues
import yacht.engine.league.Offseason
import yacht.engine.league.PlayerBook
import kotlin.test.Test
import kotlin.test.assertEquals

/** Members without a nation (the guest, the hand-made AIs, mine, a key of no identity) play in Korea's league. */
class NationlessTest {
    @Test
    fun theNationlessFormInKorea() {
        val book = PlayerBook().also { it.ensureGenerated(1000) }
        val nationless = listOf(LeagueRoster.ADAPTIVE, "ev", "b01", "b02", CustomAis.CLONE, "custom-1", "ghost")
        val keys = book.alive.map { it.key } + nationless
        // A nation code this version does not know (a newer version's) counts as none.
        val nation = { k: String -> if (k == "ghost") "ZZ" else book[k]?.identity?.nation }
        val ctx = Offseason.Context(keys, { 1500.0 }, { null }, { "" }, LeagueConfig(national = true, doubleRoundRobin = true), nation = nation)
        for (k in nationless) assertEquals("KR", NationalLeagues.home(k, ctx), k)
        val (divisions, leagues) = NationalLeagues.first(keys, ctx)
        for (k in nationless) assertEquals("KR", leagues.of(divisions.indexOfFirst { k in it }).id, k)
        // A smaller league: Korea's players are in 아시아 연합 리그, and so are they.
        val few = PlayerBook().also { it.ensureGenerated(300) }
        val small = Offseason.Context(few.alive.map { it.key } + nationless, { 1500.0 }, { null }, { "" }, LeagueConfig(national = true), nation = { few[it]?.identity?.nation })
        val (d2, l2) = NationalLeagues.first(small.keys, small)
        for (k in nationless) assertEquals("XAS", l2.of(d2.indexOfFirst { k in it }).id, k)
    }
}

package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.ClubInput
import yacht.engine.league.ClubMoveType
import yacht.engine.league.CustomAis
import yacht.engine.league.GameScore
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.LeagueRoster
import yacht.engine.league.NationalLeagues
import yacht.engine.league.Offseason
import yacht.engine.league.PlayerBook
import yacht.engine.league.Transfers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun theNationlessSitInKoreanClubsAndNeverGoAbroad() {
        val book = PlayerBook().also { it.ensureGenerated(1000) }
        val nationless = listOf(LeagueRoster.ADAPTIVE, "ev", "b01", "b02", CustomAis.CLONE, "custom-1", "ghost")
        val keys = book.alive.map { it.key } + nationless
        val nation = { k: String -> if (k == "ghost") "ZZ" else book[k]?.identity?.nation }
        // The nationless are the best of Korea's league, and Korea the lowest-ranked league: transfer material, were they eligible.
        val rating = { k: String -> if (k in nationless) 2000.0 + nationless.indexOf(k) else 1000.0 + 10 * (book[k]?.identity?.abilities?.overall ?: 50) }
        val config = LeagueConfig.DEFAULT
        val clubs = ClubBook.found(keys, nation, rating, 0, 3L, config)
        for (k in nationless) assertEquals("KR", clubs.playNation(k), k)
        for (n in 1..2) {
            val plan = Offseason.first(9L, Offseason.Context(keys, rating, { null }, { "" }, config, nation = nation, playNation = { clubs.playNation(it) ?: nation(it) }), n)
            val s = plan.league
            while (true) {
                val f = s.next(emptySet()) ?: break
                s.record(f.id, List(2) { if (rating(f.a) >= rating(f.b)) GameScore(220, 180) else GameScore(180, 220) })
            }
            val ids = s.leagues.list.map { it.id }.sortedBy { if (it == "KR") 1 else 0 }
            val ranking = LeagueRanking(n, ids.mapIndexed { i, id -> LeagueRank(id, i, 0, 0.0, emptyList(), 0.0, 0, false, 0) })
            clubs.offseason(plan, ClubInput(keys, rating, nation, config, age = { 25 }, ranking = ranking))
            val moves = clubs.history.filter { it.season == n }
            assertTrue(moves.any { it.type == ClubMoveType.ABROAD }, "some players go abroad")
            for (k in nationless) {
                assertEquals("KR", clubs.playNation(k), k)
                assertFalse(Transfers.abroad(nation(k), clubs.playNation(k)), k)
                assertTrue(moves.none { it.ai == k && (it.type == ClubMoveType.ABROAD || it.type == ClubMoveType.HOME) }, k)
            }
        }
    }
}

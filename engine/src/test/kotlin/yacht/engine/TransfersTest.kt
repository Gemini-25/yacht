package yacht.engine

import yacht.engine.league.Club
import yacht.engine.league.ClubBook
import yacht.engine.league.Emblem
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Leagues
import yacht.engine.league.Origin
import yacht.engine.league.Season
import yacht.engine.league.Transfer
import yacht.engine.league.TransferInput
import yacht.engine.league.Transfers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 해외 이적 and 귀국 (design ENG 2), planned on hand-made leagues, clubs and a ranking. */
class TransfersTest {
    /** Five leagues of two tiers of four, best-ranked first: GB, ES, DE, FR, KR; one club each. */
    private val ids = listOf("GB", "ES", "DE", "FR", "KR")

    private fun key(league: Int, i: Int) = "g%d%02d".format(league + 1, i)

    private val divisions = ids.indices.flatMap { l -> listOf((0 until 4).map { key(l, it) }, (4 until 8).map { key(l, it) }) }
    private val closing = Season.start(1, 5L, divisions, false, Leagues.national(ids, List(ids.size) { 2 }))
    private val ranking = LeagueRanking(1, ids.mapIndexed { i, id -> LeagueRank(id, i, i, 0.0, emptyList(), 0.0, 0, false, 0) })

    private val ratings = HashMap<String, Double>().apply {
        // Each 1부 is rated 1500 to 1530 (median 1515) and each 2부 1300 to 1330.
        for (l in ids.indices) for (i in 0 until 8) put(key(l, i), (if (i < 4) 1530.0 else 1330.0) - 10 * (i % 4))
    }
    private val nations = HashMap<String, String?>().apply { for (l in ids.indices) for (i in 0 until 8) put(key(l, i), ids[l]) }
    private val ages = HashMap<String, Int>()

    private fun club(id: String, nation: String, squad: List<String>, budget: Int = 100) =
        Club(id, id, "", "", Emblem(0, 0, 0, '?'), squad, budget, 0, nation)

    private fun clubs(): MutableList<Club> = ids.mapIndexed { l, n -> club("c%02d".format(l + 1), n, (0 until 8).map { key(l, it) }) }.toMutableList()

    /** 400 members more (none in a club or a division), so the count rule gives [n] transfers with [LeagueConfig.transfers] 10. */
    private fun members(n: Int = 4): List<String> = divisions.flatten() + (1..n * 100 - 40).map { "x$it" }

    private fun input(
        clubs: List<Club> = clubs(),
        members: List<String> = members(),
        ranking: LeagueRanking = this.ranking,
        config: LeagueConfig = LeagueConfig.DEFAULT.copy(transfers = 10),
        maxSize: (String) -> Int = { 12 },
    ) = TransferInput(
        closing, ranking, members, { ratings[it] ?: Double.NaN }, { ages[it] ?: 25 }, { nations[it] }, Origin::of, clubs, maxSize, config,
    )

    @Test
    fun theCountFollowsTheLeagueSize() {
        val config = LeagueConfig.DEFAULT
        assertEquals(4, Transfers.count(config, 1001))
        assertEquals(12, Transfers.count(config, 3001))
        assertEquals(2, Transfers.count(config, 501))
        assertEquals(1, Transfers.count(config, 200))
        assertEquals(0, Transfers.count(config.copy(transfers = 0), 3000))
        assertEquals(Transfers.MAX_TOTAL, Transfers.count(config.copy(transfers = 10), 9000))
    }

    @Test
    fun abroadNeedsANationOfItsOwn() {
        assertFalse(Transfers.abroad(null, "KR"), "the nationless are never abroad")
        assertFalse(Transfers.abroad("JP", "JP"))
        assertTrue(Transfers.abroad("JP", "KR"))
        assertFalse(Transfers.abroad("ZZ", "KR"), "a nation this version does not know counts as none")
        assertFalse(Transfers.abroad("JP", null), "without a club")
    }

    @Test
    fun theBestPlayersGoToALeagueThreePlacesHigherWhoseTopTheyReach() {
        // The best rated first (then by key): GB's, ES's and DE's have no league three places
        // higher; FR's best (1530) reaches GB's 1부 median (1515).
        val fee = ClubBook.fee(ClubBook.value(1530.0))
        assertEquals(listOf(Transfer(key(3, 0), "c04", "c01", "FR", "GB", fee, false)), Transfers.plan(input(members = members(1))))
        // Four allowed: one out and one in per league, so KR's best takes ES (GB has had its one),
        // and nobody else can go.
        val four = Transfers.plan(input())
        assertEquals(listOf(Transfer(key(3, 0), "c04", "c01", "FR", "GB", fee, false), Transfer(key(4, 0), "c05", "c02", "KR", "ES", fee, false)), four)
        for (t in four) assertTrue(ranking.rank(t.toLeague) <= ranking.rank(t.fromLeague) - Transfers.GAP, t.toString())
        // GB's 1부 rated above them: FR's best has nowhere to go, KR's goes to ES.
        for (i in 0 until 4) ratings[key(0, i)] = 1600.0 - i
        assertEquals(listOf(key(4, 0) to "ES"), Transfers.plan(input(members = members(1))).map { it.key to it.toLeague })
    }

    @Test
    fun ageOriginAndTheTopThreeDecideWhoMayGo() {
        // KR's 1부 is the best rated, so its players are the first candidates.
        for (i in 0 until 4) ratings[key(4, i)] = 1600.0 - 10 * i
        val star = key(4, 0)
        for (age in listOf(19, 32)) {
            ages[star] = age
            // Too young or too old: KR's next best goes instead.
            assertEquals(key(4, 1), Transfers.plan(input(members = members(1))).single().key, "age $age")
        }
        ages[star] = 31
        assertEquals(star, Transfers.plan(input(members = members(1))).single().key)
        // Only generated and evolved players: a made player (u…), a base AI or the guest never moves.
        val made = "u7"
        val c = clubs().also { it[4] = it[4].copy(squad = listOf(made) + it[4].squad.drop(1)) }
        val altered = divisions.map { d -> d.map { if (it == star) made else it } }
        val s2 = Season.start(1, 5L, altered, false, Leagues.national(ids, List(ids.size) { 2 }))
        ratings[made] = 1600.0
        nations[made] = "KR"
        val plan = Transfers.plan(TransferInput(s2, ranking, altered.flatten() + (1..60).map { "x$it" }, { ratings[it] ?: Double.NaN }, { 25 }, { nations[it] }, Origin::of, c, { 12 }, LeagueConfig.DEFAULT.copy(transfers = 10)))
        assertEquals(key(4, 1), plan.single().key)
        // Only the top 3 of the 1부: KR's 4th never goes even when the others cannot (FR's best goes instead).
        for (i in 0 until 3) ages[key(4, i)] = 40
        assertEquals(listOf(key(3, 0)), Transfers.plan(input(members = members(1))).map { it.key })
        // A 2부 player rated above every 1부 player is no candidate either.
        ratings[key(4, 5)] = 1700.0
        assertEquals(listOf(key(3, 0)), Transfers.plan(input(members = members(1))).map { it.key })
    }

    @Test
    fun theClubWithTheMostRoomBuysAndPaysWhatItCan() {
        // FR's best (1530) goes first, and only GB is high enough for it.
        val fee = ClubBook.fee(ClubBook.value(1530.0))
        // GB has two clubs: the one with more room below its ceiling buys.
        val gb = (0 until 8).map { key(0, it) }
        val two = clubs().also {
            it[0] = club("c01", "GB", gb.take(5))
            it += club("c06", "GB", gb.drop(5))
        }
        assertEquals("c06", Transfers.plan(input(clubs = two, members = members(1))).single().toClub)
        // Level room: the richer, then the lower id.
        val level = clubs().also {
            it[0] = club("c01", "GB", gb.take(4), budget = 80)
            it += club("c06", "GB", gb.drop(4), budget = 90)
        }
        assertEquals("c06", Transfers.plan(input(clubs = level, members = members(1))).single().toClub)
        // A club at its ceiling is passed over.
        assertEquals("c01", Transfers.plan(input(clubs = level, members = members(1), maxSize = { if (it == "c06") 4 else 12 })).single().toClub)
        // A buyer with at least half the fee pays all it has; with less it is passed over (then the next league).
        val poor = clubs().also { it[0] = it[0].copy(budget = (fee + 1) / 2) }
        val t = Transfers.plan(input(clubs = poor, members = members(1))).single()
        assertEquals("c01", t.toClub)
        assertEquals((fee + 1) / 2, t.fee)
        // Too poor: FR's best stays, and KR's best goes to ES instead.
        val poorer = clubs().also { it[0] = it[0].copy(budget = (fee + 1) / 2 - 1) }
        val u = Transfers.plan(input(clubs = poorer, members = members(1))).single()
        assertEquals(key(4, 0) to "ES", u.key to u.toLeague)
        assertEquals(fee, u.fee)
    }

    @Test
    fun playersAbroadComeHomeAt33OrOutsideTheTop() {
        // Three Koreans in GB's club: one in the 1부 aged 25 stays, one aged 33 and one in the 2부 return.
        val stays = key(0, 1)
        val old = key(0, 2)
        val low = key(0, 6)
        for (k in listOf(stays, old, low)) nations[k] = "KR"
        ages[old] = 33
        // A nationless member of a foreign club (a base AI, the guest) is never abroad.
        nations[key(0, 7)] = null
        val c = clubs().also { it += club("c06", "KR", emptyList()) }
        val plan = Transfers.plan(input(clubs = c, ranking = LeagueRanking.EMPTY))
        assertEquals(listOf(old, low), plan.map { it.key })
        assertTrue(plan.all { it.home && it.fee == 0 && it.fromClub == "c01" && it.fromLeague == "GB" && it.toLeague == "KR" })
        // To the club of their nation with the fewest members: the empty c06 (1 then, against c05's 8).
        assertEquals(listOf("c06", "c06"), plan.map { it.toClub })
        // Level sizes: the lower id.
        val level = clubs().also { it += club("c06", "KR", (1..8).map { "k$it" }) }
        assertEquals(listOf("c05", "c06"), Transfers.plan(input(clubs = level, ranking = LeagueRanking.EMPTY)).map { it.toClub })
        // No ranking, so no transfers abroad: only the returns.
        assertTrue(plan.none { !it.home })
    }

    @Test
    fun aPlanIsTheSameWhateverTheOrderOfTheClubs() {
        ratings[key(3, 0)] = 1560.0
        nations[key(0, 6)] = "KR"
        val a = Transfers.plan(input())
        val b = Transfers.plan(input(clubs = clubs().reversed()))
        assertEquals(a, b)
        assertEquals(a, Transfers.plan(input()))
        assertTrue(a.isNotEmpty())
        // One league only, or no ranking: no transfers abroad.
        val one = Season.start(1, 5L, divisions.take(2), false, Leagues.national(listOf("GB"), listOf(2)))
        assertEquals(emptyList(), Transfers.plan(TransferInput(one, ranking, members(), { ratings[it] ?: Double.NaN }, { 25 }, { nations[it] }, Origin::of, clubs(), { 12 }, LeagueConfig.DEFAULT)).filter { !it.home })
    }
}

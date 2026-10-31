package yacht.uicheck

import yacht.app.core.LeagueSelector
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Leagues
import yacht.engine.league.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The league selector (UX 1.7): the league shown by default, the followed leagues first, a legacy season's one entry. */
class SelectorTest {
    private fun keys(prefix: String, r: IntRange) = r.map { "$prefix$it" }

    // 한국 (two tiers), 일본, 아시아 연합 (Vietnam and Thailand) and 유럽 연합 (Britain).
    private val divisions = listOf(keys("k", 0..9), keys("k", 10..19), keys("j", 0..9), keys("v", 0..4) + keys("t", 0..4), keys("g", 0..9))
    private val season = Season.start(1, 7L, divisions, false, Leagues.national(listOf("KR", "JP", "XAS", "XEU"), listOf(2, 1, 1, 1)))
    private val nations = mapOf('k' to "KR", 'j' to "JP", 'v' to "VN", 't' to "TH", 'g' to "GB")

    // Japan the strongest by its best five, then Korea, the Europeans, the Asian union.
    private val base = mapOf('j' to 1600.0, 'k' to 1550.0, 'g' to 1500.0, 'v' to 1450.0, 't' to 1440.0)
    private fun rating(k: String): Double = base.getValue(k[0]) - k.drop(1).toInt()

    private fun build(favourites: Set<String>, ranking: LeagueRanking = LeagueRanking.EMPTY, of: Season = season) =
        LeagueSelector.build(of, ranking, LeagueConfig.DEFAULT, favourites, { nations[it[0]] }, ::rating)

    @Test
    fun theDefaultIsTheBestFollowedPlayersLeagueThenANationsThenKoreasThenTheBestRanked() {
        // Nothing followed: Korea's league.
        assertEquals("KR", build(emptySet()).default)
        // A followed player's league; of two, the better-rated one's.
        assertEquals("JP", build(setOf("j5")).default)
        assertEquals("XEU", build(setOf("g0", "v4")).default)
        assertEquals("JP", build(setOf("g0", "j9")).default)
        // A followed player before a followed nation; a nation's league is where its players play.
        assertEquals("XAS", build(setOf("nation:VN")).default)
        assertEquals("XEU", build(setOf("nation:GB")).default)
        assertEquals("JP", build(setOf("nation:GB", "j0")).default)
        // A nation with no player this season: its confederation's 연합 리그.
        assertEquals("XEU", build(setOf("nation:FR")).default)
        // A followed league is marked but does not move the default; a player out of the season counts for nothing.
        val league = build(setOf("league:XAS", "zz9"))
        assertEquals("KR", league.default)
        assertTrue(league.info("XAS")!!.followed)
        // A season without Koreans: its best-ranked league.
        val noKorea = Season.start(1, 7L, divisions.drop(2), false, Leagues.national(listOf("JP", "XAS", "XEU"), listOf(1, 1, 1)))
        assertEquals("JP", build(emptySet(), of = noKorea).default)
    }

    @Test
    fun theRowsCarryRankSizesAndPlacesAndTheFollowedLeaguesComeFirst() {
        val b = build(setOf("v1", "league:XEU"))
        // In league order, ranked by strength before the first ranking (the draws' order).
        assertEquals(listOf("KR", "JP", "XAS", "XEU"), b.infos.map { it.id })
        assertEquals(listOf(2, 1, 4, 3), b.infos.map { it.rank })
        val kr = b.info("KR")!!
        assertEquals(listOf("1부", "2부"), kr.tierLabels)
        assertEquals(listOf(10, 10), kr.sizes)
        assertEquals(20, kr.members)
        assertEquals(0, kr.first)
        assertEquals("한국 리그", kr.label)
        assertEquals(2, b.info("XAS")!!.nations)
        assertEquals(3, b.info("XAS")!!.first)
        // Asia's three leagues share a section of 8 (3, 3, 2); Europe's one has none.
        assertEquals(listOf(3, 3, 2, 0), b.infos.map { it.cclSlots })
        assertNull(kr.coefficient)
        assertFalse(kr.followed)

        // ★ 응원 by rank (a followed player's league and a followed league), then the nations', then the unions.
        val sections = LeagueSelector.sections(b.infos)
        assertEquals(listOf("★ 응원", "국가 리그"), sections.map { it.first })
        assertEquals(listOf("XEU", "XAS"), sections[0].second.map { it.id })
        assertEquals(listOf("JP", "KR"), sections[1].second.map { it.id })
        assertEquals(listOf("국가 리그", "연합 리그"), LeagueSelector.sections(build(emptySet()).infos).map { it.first })
        assertEquals(listOf("XAS", "XEU"), LeagueSelector.sections(build(emptySet()).infos)[1].second.map { it.id })

        // A published ranking orders them and gives their coefficients and places.
        val ranking = LeagueRanking(
            0,
            listOf("XAS", "KR", "JP", "XEU").mapIndexed { i, id -> LeagueRank(id, i, 0, 10.0 - i, listOf(10.0 - i), 1500.0, 4 - i, i == 0, 0) },
        )
        val ranked = build(emptySet(), ranking)
        assertEquals(listOf(2, 3, 1, 4), ranked.infos.map { it.rank })
        assertEquals(10.0, ranked.info("XAS")!!.coefficient)
        assertEquals(listOf(3, 2, 4, 1), ranked.infos.map { it.cclSlots })
        assertTrue(ranked.info("XAS")!!.clDirect)
    }

    @Test
    fun aLegacySeasonHasOneEntryAndNothingToPick() {
        val legacy = Season.start(1, 7L, divisions, false)
        val b = build(setOf("j5", "nation:GB"), of = legacy)
        assertEquals(1, b.infos.size)
        val only = b.infos.single()
        assertEquals("", only.id)
        assertEquals("리그", only.label)
        assertNull(only.rank)
        assertEquals(listOf("1부", "2부", "3부", "4부", "5부"), only.tierLabels)
        assertEquals(50, only.members)
        assertEquals("", b.default)
        assertTrue(LeagueSelector.sections(b.infos).isEmpty())
    }
}

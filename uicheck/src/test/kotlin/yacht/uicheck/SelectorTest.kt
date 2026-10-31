package yacht.uicheck

import yacht.app.core.LeagueNav
import yacht.app.core.LeagueSelector
import yacht.app.core.SeasonView
import yacht.app.ui.showDivision
import yacht.app.ui.showSeasonDivision
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Leagues
import yacht.engine.league.Season
import yacht.engine.league.Tiers
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

    // Club c01 is British, c02 Vietnamese.
    private val clubs = mapOf("c01" to "GB", "c02" to "VN")

    private fun build(favourites: Set<String>, ranking: LeagueRanking = LeagueRanking.EMPTY, of: Season = season, config: LeagueConfig = LeagueConfig.DEFAULT) =
        LeagueSelector.build(of, ranking, config, favourites, { nations[it[0]] }, ::rating, { clubs[it] })

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
        // A followed club's league (its nation's) after a followed player's, before a followed nation's.
        assertEquals("XEU", build(setOf("club:c01")).default)
        assertEquals("XEU", build(setOf("club:c01", "nation:JP")).default)
        assertEquals("JP", build(setOf("club:c01", "j0")).default)
        assertTrue(build(setOf("club:c02")).info("XAS")!!.followed)
        // A club of no known nation counts for nothing.
        assertEquals("KR", build(setOf("club:c99")).default)
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
    fun noPlacesInCompetitionsTheRulesDoNotPlay() {
        val on = build(emptySet())
        assertTrue(on.infos.all { it.clDirect })
        val off = build(emptySet(), config = LeagueConfig.DEFAULT.copy(continentalCl = false, champions = false))
        assertEquals(listOf(0, 0, 0, 0), off.infos.map { it.cclSlots })
        assertTrue(off.infos.none { it.clDirect })
        // Also over a published ranking's places.
        val ranking = LeagueRanking(0, listOf("XAS", "KR", "JP", "XEU").mapIndexed { i, id -> LeagueRank(id, i, 0, 1.0, listOf(1.0), 1500.0, 3, true, 0) })
        val offRanked = build(emptySet(), ranking, config = LeagueConfig.DEFAULT.copy(continentalCl = false, champions = false))
        assertTrue(offRanked.infos.all { it.cclSlots == 0 && !it.clDirect })
    }

    @Test
    fun aSeasonsDivisionOpensItsLeagueAtItsTierAndAnyDivisionIsSafe() {
        val b = build(emptySet())
        val national = SeasonView(1, 0, 0, emptyList(), emptyList(), emptyList(), leagues = b.infos, league = "KR", national = true)
        val nav = LeagueNav()
        // Division 1 is 한국 2부, 4 유럽 연합 1부.
        nav.showSeasonDivision(national, 1)
        assertEquals("KR", nav.league)
        assertEquals(1, nav.tier)
        nav.showSeasonDivision(national, 4)
        assertEquals("XEU", nav.league)
        assertEquals(0, nav.tier)
        // A division the season does not have changes nothing.
        nav.showSeasonDivision(national, 9)
        assertEquals("XEU", nav.league)

        // A legacy pyramid (1부, 2부 two groups, 3부 four): its tier and group, the league untouched.
        val pyramid = Tiers.of(listOf(1, 2, 4))
        val legacy = SeasonView(1, 0, 0, List(7) { emptyList() }, emptyList(), emptyList(), tiers = pyramid)
        val other = LeagueNav()
        other.showSeasonDivision(legacy, 4)
        assertNull(other.league)
        assertEquals(2, other.tier)
        assertEquals(1, other.group)
        // A division the tiers do not have shows the 1부 (no throw).
        other.showDivision(pyramid, 12)
        assertEquals(0, other.tier)
        assertEquals(0, other.group)
        other.showDivision(Tiers.linear(0), 0)
        assertEquals(0, other.tier)
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

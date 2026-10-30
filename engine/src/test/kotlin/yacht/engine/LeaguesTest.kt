package yacht.engine

import yacht.engine.league.Confed
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueDef
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueRank
import yacht.engine.league.LeagueRanking
import yacht.engine.league.Leagues
import yacht.engine.league.Nations
import yacht.engine.league.Tiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The leagues of a season, league ids and their names and codes, honour kinds with a league, and the league ranking's rows. */
class LeaguesTest {
    private val national = Leagues.national(listOf("KR", "JP", "XAS", "XWO"), listOf(3, 2, 2, 1), Confed.EUROPE)

    @Test
    fun nationalLeaguesCoverTheDivisionsInOrder() {
        val lg = national
        assertTrue(lg.national)
        assertEquals(8, lg.size)
        assertEquals(listOf(0, 3, 5, 7), lg.list.map { it.first })
        assertEquals(listOf(0, 3, 5, 7), lg.tops())
        assertEquals(listOf("KR", "KR", "KR", "JP", "JP", "XAS", "XAS", "XWO"), (0 until 8).map { lg.of(it).id })
        assertEquals(listOf(0, 1, 2, 0, 1, 0, 1, 0), (0 until 8).map { lg.local(it) })
        assertEquals(listOf(0, 1, 2, 0, 1, 0, 1, 0), (0 until 8).map { lg.tier(it) })
        assertEquals(3..4, lg.byId("JP")?.divisions)
        assertNull(lg.byId("CN"))
        assertTrue(lg.list.all { it.national && it.tiers.linear })
        assertEquals(Confed.EUROPE, lg.confedOf("XWO"))
        assertEquals(Confed.ASIA, lg.confedOf("KR"))
        assertEquals(Confed.ASIA, lg.confedOf("XAS"))
        // Labels.
        assertEquals("한국 1부", lg.label(0))
        assertEquals("한국 3부", lg.label(2))
        assertEquals("일본 2부", lg.label(4))
        assertEquals("아시아 연합 2부", lg.label(6))
        assertEquals("국제 연합 1부", lg.label(7))
        assertEquals("한국 리그 1부", lg.fullLabel(0))
        assertEquals("국제 연합 리그 1부", lg.fullLabel(7))
    }

    @Test
    fun aLegacyLeagueIsOneLeagueWithItsTiers() {
        val tiers = Tiers.of(listOf(1, 2, 4), listOf("", "한국", "유럽", "한국", "일본", "유럽", "아메리카"))
        val lg = Leagues.legacy(tiers)
        assertFalse(lg.national)
        assertEquals(7, lg.size)
        assertEquals(tiers, lg.list.single().tiers)
        assertEquals("", lg.of(5).id)
        assertEquals(2, lg.tier(5))
        assertEquals("3부 B조", lg.label(4))
        assertEquals("3부 B조 · 일본", lg.fullLabel(4))
        assertEquals("1부", lg.label(0))
        assertEquals(listOf(0), lg.tops())
        assertEquals("2부", Leagues.legacy(Tiers.linear(4)).label(1))
    }

    @Test
    fun badLeaguesAreRefused() {
        val t = Tiers.linear(2)
        assertFailsWith<IllegalArgumentException>("a gap") { Leagues(listOf(LeagueDef("KR", 0, t), LeagueDef("JP", 3, t))) }
        assertFailsWith<IllegalArgumentException>("not from 0") { Leagues(listOf(LeagueDef("KR", 1, t))) }
        assertFailsWith<IllegalArgumentException>("an id twice") { Leagues(listOf(LeagueDef("KR", 0, t), LeagueDef("KR", 2, t))) }
        assertFailsWith<IllegalArgumentException>("legacy with others") { Leagues(listOf(LeagueDef("", 0, t), LeagueDef("KR", 2, t))) }
        assertFailsWith<IllegalArgumentException>("a national pyramid") { Leagues(listOf(LeagueDef("KR", 0, Tiers.of(listOf(1, 2))))) }
        assertFailsWith<IllegalArgumentException>("an empty league") { Leagues.national(listOf("KR"), listOf(0)) }
        assertFailsWith<IllegalArgumentException>("a legacy id") { Leagues.national(listOf("KR", ""), listOf(1, 1)) }
        // The 국제 연합 리그's confederation only with one.
        assertNull(Leagues.national(listOf("KR"), listOf(2), Confed.AFRICA).worldConfed)
    }

    @Test
    fun theSeasonLinesComeBack() {
        val out = StringBuilder().also { national.encode(it) }.toString()
        assertEquals("league KR 0 3\nleague JP 3 2\nleague XAS 5 2\nleague XWO 7 1 EUROPE\n", out)
        assertEquals(national, Leagues.decode(out.lines(), 8))
        assertNull(Leagues.decode(out.lines(), 9), "fewer divisions than the season's")
        assertNull(Leagues.decode(listOf("league KR 0 3", "league JP 4 2"), 6), "a gap")
        assertNull(Leagues.decode(listOf("league KR 0 x"), 1))
        assertNull(Leagues.decode(listOf("league XWO 0 1 MARS"), 1))
        // Legacy: the tiers line of a pyramid, nothing for linear divisions.
        val pyramid = Leagues.legacy(Tiers.of(listOf(1, 2), listOf("", "한국", "유럽")))
        val text = StringBuilder().also { pyramid.encode(it) }.toString()
        assertEquals("tiers 0,1,1 -|한국|유럽\n", text)
        assertEquals(pyramid, Leagues.decode(text.lines(), 3))
        assertEquals("", StringBuilder().also { Leagues.legacy(Tiers.linear(4)).encode(it) }.toString())
        assertEquals(Leagues.legacy(Tiers.linear(4)), Leagues.decode(emptyList(), 4))
        assertEquals(Leagues.legacy(Tiers.linear(4)), Leagues.decode(listOf("tiers 0,1"), 4), "a tiers line that does not fit")
    }

    @Test
    fun leagueIdsHaveNamesIconsAndOneDigitCodes() {
        assertTrue(Nations.ALL.size <= 30, "the archive's league digit has room for 30 nations")
        val ids = listOf(LeagueIds.LEGACY) + Nations.ALL.map { it.code } + Confed.entries.map(LeagueIds::region) + LeagueIds.WORLD
        val codes = ids.map(LeagueIds::code)
        assertEquals(ids.size, codes.toSet().size)
        assertTrue(codes.all { it in 0 until 36 }, "one base-36 digit")
        for (id in ids) assertEquals(id, LeagueIds.ofCode(LeagueIds.code(id)))
        assertEquals(0, LeagueIds.code(""))
        assertEquals(1, LeagueIds.code("KR"))
        assertEquals(31, LeagueIds.code("XWO"))
        assertEquals(listOf(32, 33, 34, 35), listOf("XAS", "XEU", "XAM", "XAF").map(LeagueIds::code))
        assertEquals("", LeagueIds.ofCode(30), "a spare code")
        assertFailsWith<IllegalArgumentException> { LeagueIds.code("ZZ") }
        // Names.
        assertEquals("리그", LeagueIds.label(""))
        assertEquals("한국 리그", LeagueIds.label("KR"))
        assertEquals("사우디 리그", LeagueIds.label("SA"))
        assertEquals("아시아 연합 리그", LeagueIds.label("XAS"))
        assertEquals("아프리카·중동 연합 리그", LeagueIds.label("XAF"))
        assertEquals("국제 연합 리그", LeagueIds.label("XWO"))
        assertEquals(listOf("", "한국", "유럽 연합", "국제 연합"), listOf("", "KR", "XEU", "XWO").map(LeagueIds::short))
        assertEquals(listOf("", "🇰🇷", "🌏", "🌐"), listOf("", "KR", "XAS", "XWO").map(LeagueIds::icon))
        assertEquals("한국 1부", LeagueIds.division("KR", 0))
        assertEquals("국제 연합 2부", LeagueIds.division("XWO", 1))
        assertEquals("3부", LeagueIds.division("", 2))
        // Confederations: the 국제 연합 리그's is the season's.
        assertEquals(Confed.ASIA, LeagueIds.confedOf("KR"))
        assertEquals(Confed.EUROPE, LeagueIds.confedOf("GB"))
        assertEquals(Confed.AFRICA, LeagueIds.confedOf("XAF"))
        assertNull(LeagueIds.confedOf("XWO"))
        assertNull(LeagueIds.confedOf(""))
        assertNull(LeagueIds.confedOf("ZZ"))
        assertEquals(listOf("JP", "VN"), LeagueIds.nations("XAS", mapOf("VN" to "XAS", "KR" to "KR", "JP" to "XAS", "GB" to "XWO")))
        // Season order: nations as listed, then the 연합 리그, then the 국제 연합 리그.
        assertEquals(listOf("KR", "JP", "ZA", "XAS", "XEU", "XAF", "XWO"), listOf("XWO", "XAF", "ZA", "XAS", "JP", "KR", "XEU").sortedBy(LeagueIds::order))
    }

    @Test
    fun honourKindsCarryTheirLeagueOrConfederation() {
        assertEquals("L0", HonourKind.base("L0.KR"))
        assertEquals("CUP", HonourKind.base("CUP"))
        assertEquals("KR", HonourKind.qualifier("L0.KR"))
        assertEquals("ASIA", HonourKind.qualifier("CCL.ASIA"))
        assertNull(HonourKind.qualifier("L0"))
        assertEquals("XWO", HonourKind.leagueOf("LMVP.XWO"))
        assertEquals("KR", HonourKind.leagueOf("L0R.KR"))
        assertNull(HonourKind.leagueOf("CCL.ASIA"), "a confederation is not a league")
        assertNull(HonourKind.leagueOf("CCL2.EUROPE"))
        assertNull(HonourKind.leagueOf("L0"), "a legacy kind: the old single league")
        assertEquals("L1.JP", HonourKind.of("L1", "JP"))
        assertEquals("REG", HonourKind.of("REG", LeagueIds.LEGACY))
        // Division titles, legacy and national; not the runner-up of the title playoff or the league MVP.
        assertTrue(listOf("L0", "L3", "L12", "L0.KR", "L5.XEU").all(HonourKind::isDivision))
        assertFalse(listOf("L0R.KR", "LMVP.KR", "REG.KR", "UP2", "CL").any(HonourKind::isDivision))
        assertEquals(listOf(0, 3, 12, 0, null), listOf("L0", "L3", "L12", "L0.KR", "L0R.KR").map(HonourKind::tier))
        assertTrue(HonourKind.isTopTitle("L0") && HonourKind.isTopTitle("L0.KR"))
        assertFalse(HonourKind.isTopTitle("L0R.KR") || HonourKind.isTopTitle("L1.KR"))
    }

    @Test
    fun theRankingFindsLeaguesAndConfederations() {
        fun row(id: String, rank: Int, confed: Confed? = LeagueIds.confedOf(id)) =
            LeagueRank(id, rank, 0, 10.0 - rank, listOf(2.0), 1800.0, 2, rank < 7, 0, confed)
        val r = LeagueRanking(4, listOf(row("GB", 0), row("KR", 1), row("XWO", 2, Confed.ASIA), row("BR", 3)))
        assertEquals(1, r.rank("KR"))
        assertEquals(Int.MAX_VALUE, r.rank("JP"))
        assertEquals("XWO", r.of("XWO")?.id)
        assertNull(r.of("JP"))
        assertEquals(listOf("KR", "XWO"), r.confed(Confed.ASIA).map { it.id })
        assertEquals(Confed.EUROPE, LeagueRank("GB", 0, 0, 1.0, emptyList(), 0.0, 0, false, 0).confed)
        assertTrue(LeagueRanking.EMPTY.rows.isEmpty())
        assertEquals(Int.MAX_VALUE, LeagueRanking.EMPTY.rank("KR"))
    }
}

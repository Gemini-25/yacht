package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import yacht.app.core.ClockView
import yacht.app.core.ClubBadge
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.ResultsFilter
import yacht.app.core.WeekRow
import yacht.app.core.WorldPages
import yacht.app.core.competitionStages
import yacht.app.core.roundInPlay
import yacht.app.ui.barTitle
import yacht.app.ui.matchKey
import yacht.app.ui.nationFavourite
import yacht.app.ui.resultShown
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.league.BracketRound
import yacht.engine.league.BracketSide
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionView
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.GroupRow
import yacht.engine.league.GroupTable
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.TieView
import yacht.engine.league.WinEstimate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The league shell's logic that needs no screen: competition stages and pages, match keys, the results filter, the bar's title. */
class ShellTest {
    /** A one-match tie; a played one won 2-0 by [winner]. */
    private fun tie(a: String?, b: String?, played: Boolean = false, winner: String? = null) =
        TieView(a, b, if (played && winner == a) 2.0 else 0.0, if (played && winner == b) 2.0 else 0.0, winner, played, 1)

    private fun group(label: String, vararg keys: String) = GroupTable(label, keys.map { GroupRow(it, 0, 0, 0, 0, 0, 0) })

    /** A knockout not drawn yet: every side unknown. */
    private fun placeholder(title: String, vararg labels: String) =
        CompetitionView.Bracket(title, labels.mapIndexed { i, l -> BracketRound(l, listOf(tie(null, null)), stage = i) })

    @Test
    fun stagesFoldSectionsGroupsAndRoundsIntoOneList() {
        val asia = CompetitionView.Groups("아시아컵", listOf(group("A조", "a", "b")), placeholder("아시아컵", "4강", "결승"))
        val europe = CompetitionView.Groups("유럽컵", emptyList(), CompetitionView.Bracket("유럽컵", listOf(BracketRound("결승", listOf(tie("x", "y")), stage = 0))))
        val stages = competitionStages(CompetitionView.Sections("대륙컵", listOf(asia, europe)))
        assertEquals(listOf("아시아컵 · 조별 리그", "아시아컵 · 4강", "아시아컵 · 결승", "유럽컵 · 결승"), stages.map { it.label })
        assertEquals(listOf(0 to -1, 0 to 0, 0 to 1, 1 to 0), stages.map { it.part to it.round })
        // A bracket alone: its rounds, in order.
        val cup = CompetitionView.Bracket("요트컵", listOf(BracketRound("승자조 8강", emptyList()), BracketRound("패자조 1R", emptyList(), BracketSide.LOSERS)))
        assertEquals(listOf("승자조 8강", "패자조 1R"), competitionStages(cup).map { it.label })
    }

    @Test
    fun theRoundInPlayIsTheEarliestStageWithATieReady() {
        // A double elimination: the losers' round of stage 1 is ready before the winners' round of stage 2.
        val rounds = listOf(
            BracketRound("승자조 8강", listOf(tie("a", "b", played = true, winner = "a")), stage = 0),
            BracketRound("승자조 4강", listOf(tie("a", "c")), stage = 2),
            BracketRound("패자조 1R", listOf(tie("b", "d")), BracketSide.LOSERS, stage = 1),
        )
        assertEquals(2, roundInPlay(rounds))
        // Nothing ready: the latest played, else the first; no rounds, none.
        assertEquals(0, roundInPlay(listOf(rounds[0], BracketRound("결승", listOf(tie("a", null)), stage = 1))))
        assertEquals(0, roundInPlay(listOf(BracketRound("결승", listOf(tie(null, null))))))
        assertNull(roundInPlay(emptyList()))
    }

    @Test
    fun aCompetitionPageOpensAtTheStageInPlay() {
        val cup = CompetitionView.Bracket(
            "요트컵",
            listOf(
                BracketRound("8강", listOf(tie("a", "b", played = true, winner = "a")), stage = 0),
                BracketRound("4강", listOf(tie("a", "c")), stage = 1),
                BracketRound("결승", listOf(tie(null, null)), stage = 2),
            ),
        )
        // Groups in play: the knockout is a placeholder.
        val cl = CompetitionView.Groups("챔피언스 리그", listOf(group("A조", "a", "b"), group("B조", "c", "d")), placeholder("챔피언스 리그", "4강", "결승"))
        val host = FakeHost(view(Comp.CUP to cup, Comp.CHAMPIONS to cl))
        val pages = WorldPages(host)
        pages.loadCompetition(Comp.CUP)
        assertEquals(1, pages.competitions[Page.Competition(Comp.CUP).loadKey]!!.stage)
        pages.loadCompetition(Comp.CHAMPIONS)
        val groups = pages.competitions[Page.Competition(Comp.CHAMPIONS).loadKey]!!
        assertEquals(0, groups.stage)
        assertEquals(listOf("조별 리그", "4강", "결승"), groups.stages)
        // A stage picked stays; one out of range is the stage in play.
        pages.loadCompetition(Comp.CUP, 2)
        assertEquals(2, pages.competitions[Page.Competition(Comp.CUP).loadKey]!!.stage)
        pages.loadCompetition(Comp.CUP, 9)
        assertEquals(1, pages.competitions[Page.Competition(Comp.CUP).loadKey]!!.stage)

        // The knockout drawn: its round in play.
        val drawn = cl.copy(knockout = CompetitionView.Bracket("챔피언스 리그", listOf(BracketRound("4강", listOf(tie("a", "d"), tie("c", "b")), stage = 0), BracketRound("결승", listOf(tie(null, null)), stage = 1))))
        host.v = view(Comp.CHAMPIONS to drawn)
        pages.loadCompetition(Comp.CHAMPIONS)
        assertEquals(1, pages.competitions[Page.Competition(Comp.CHAMPIONS).loadKey]!!.stage)

        // Not in this season: nothing loaded.
        pages.loadCompetition(Comp.WORLD_CUP)
        assertNull(pages.competitions[Page.Competition(Comp.WORLD_CUP).loadKey])
    }

    @Test
    fun sectionsOpenWhereAFollowedPlayerPlays() {
        val asia = CompetitionView.Groups("아시아컵", listOf(group("A조", "a", "b")), placeholder("아시아컵", "결승"))
        val europe = CompetitionView.Groups("유럽컵", listOf(group("A조", "c", "d")), placeholder("유럽컵", "결승"))
        val continental = CompetitionView.Sections("대륙컵", listOf(asia, europe))
        val host = FakeHost(view(Comp.CONTINENTAL to continental))
        val pages = WorldPages(host)
        pages.loadCompetition(Comp.CONTINENTAL)
        assertEquals(0, pages.competitions.values.single().stage)
        host.favs = setOf("d")
        pages.loadCompetition(Comp.CONTINENTAL)
        val page = pages.competitions.values.single()
        assertEquals("유럽컵 · 조별 리그", page.stages[page.stage])
    }

    @Test
    fun competitionPagesKeepTheLastFour() {
        val comps = listOf(Comp.CUP, Comp.CHAMPIONS, Comp.SUPER, Comp.ALL_STAR, Comp.FAMILY)
        val host = FakeHost(view(*comps.map { it to CompetitionView.Bracket(it.label, listOf(BracketRound("결승", listOf(tie("a", "b"))))) }.toTypedArray()))
        val pages = WorldPages(host)
        for (c in comps) pages.loadCompetition(c)
        assertEquals(comps.drop(1).map { Page.Competition(it).loadKey }, pages.competitions.keys.toList())
        // Loaded again: the newest, so the oldest after it goes first.
        pages.loadCompetition(Comp.CHAMPIONS)
        pages.loadCompetition(Comp.CUP)
        assertEquals(listOf(Comp.ALL_STAR, Comp.FAMILY, Comp.CHAMPIONS, Comp.CUP).map { Page.Competition(it).loadKey }, pages.competitions.keys.toList())
    }

    @Test
    fun onlyOfficialMatchesHaveAReportKey() {
        assertEquals("3:L:120", matchKey(3, Comp.LEAGUE, 120))
        assertEquals("3:C:7", matchKey(3, Comp.CUP, 7))
        for (c in listOf(Comp.FRIENDLY, Comp.LEGEND, Comp.LAB)) assertNull(matchKey(3, c, 7))
        assertNull(matchKey(3, Comp.CUP, -1))
    }

    @Test
    fun theResultsFilterAppliesToResultsAndMatchesInPlay() {
        val favs = setOf("a", nationFavourite("BR"))
        assertTrue(resultShown(ResultsFilter.FOLLOWED, Comp.LEAGUE, "x", "a", favs))
        // A followed nation's team.
        assertTrue(resultShown(ResultsFilter.FOLLOWED, Comp.WORLD_CUP, "BR", "AR", favs))
        assertFalse(resultShown(ResultsFilter.FOLLOWED, Comp.LEAGUE, "x", "y", favs))
        assertTrue(resultShown(ResultsFilter.COMPS, Comp.CUP, "x", "y", favs))
        for (c in listOf(Comp.LEAGUE, Comp.FRIENDLY, Comp.LEGEND, Comp.LAB, null)) assertFalse(resultShown(ResultsFilter.COMPS, c, "x", "y", favs))
        assertTrue(resultShown(ResultsFilter.FRIENDLY, Comp.LEGEND, "x", "y", favs))
        assertFalse(resultShown(ResultsFilter.LEAGUE, null, "x", "y", favs))
        assertTrue(resultShown(ResultsFilter.ALL, null, "x", "y", favs))
    }

    @Test
    fun theBarTitleSaysTheSeasonAndItsWeek() {
        assertEquals("AI 리그", barTitle(null))
        val clock = ClockView(3, 12, 42, 46, over = false, weeks = (1..46).map { WeekRow(it, emptyList()) })
        assertEquals("시즌 3 · 12주차 / 42주", barTitle(clock))
        assertEquals("시즌 3 · 포스트시즌 2주차", barTitle(clock.copy(week = 44)))
        assertEquals("시즌 3 마무리 중", barTitle(clock.copy(over = true)))
    }

    private fun view(vararg comps: Pair<Comp, CompetitionView>) =
        LeagueView(rows = emptyList(), feed = emptyList(), matches = 0, games = 0, live = emptyList(), comps = comps.toList())

    /** A host whose only data is a snapshot ([v]) and the favourites ([favs]). */
    private class FakeHost(var v: LeagueView?, var favs: Set<String> = emptySet()) : LeagueHost {
        override val scope = CoroutineScope(Dispatchers.Unconfined)
        override val lock = Any()
        override val folder = File(".")
        override fun world(): LeagueWorld? = null
        override fun tables(): Tables? = null
        override fun extra(): LeagueTables? = null
        override fun estimate(): WinEstimate? = null
        override fun ai(key: String): LeagueAi? = null
        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? = null
        override fun adaptive(key: String) = false
        override fun name(key: String) = key
        override fun shown(key: String) = key
        override fun badge(key: String): ClubBadge? = null
        override fun leagueLabel(key: String): String? = null
        override fun favourites() = favs
        override fun view() = v
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = 0
    }
}

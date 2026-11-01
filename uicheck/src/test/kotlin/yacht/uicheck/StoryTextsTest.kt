package yacht.uicheck

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import yacht.app.core.ClubBadge
import yacht.app.core.ClubRankKind
import yacht.app.core.EraView
import yacht.app.core.HomeBanner
import yacht.app.core.HomeFeed
import yacht.app.core.InfoTopic
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.NewsFilter
import yacht.app.core.Page
import yacht.app.core.PressureLevel
import yacht.app.core.StoryLines
import yacht.app.core.UiPrefs
import yacht.app.core.pageOf
import yacht.app.core.storyReason
import yacht.app.ui.ClubRankTexts
import yacht.app.ui.ClubTexts
import yacht.app.ui.CoachTexts
import yacht.app.ui.MetaTexts
import yacht.app.ui.PlayerClubTexts
import yacht.app.ui.PressTexts
import yacht.app.ui.ReviewTexts
import yacht.app.ui.VOTE_POINTS
import yacht.app.ui.WorldPageTexts
import yacht.app.ui.arcIcon
import yacht.app.ui.articleLink
import yacht.app.ui.familyColor
import yacht.app.ui.filterLabel
import yacht.app.ui.honourLabel
import yacht.app.ui.infoText
import yacht.app.ui.infoTitle
import yacht.app.ui.kindIcon
import yacht.app.ui.linkable
import yacht.app.ui.moodWord
import yacht.app.ui.newsShown
import yacht.app.ui.ownerIcon
import yacht.app.ui.rankMove
import yacht.app.ui.storyPill
import yacht.app.ui.storyStage
import yacht.app.ui.tenureEnd
import yacht.app.ui.trophy
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.explain.Ko
import yacht.engine.league.ArcKind
import yacht.engine.league.Article
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.MetaBook
import yacht.engine.league.OwnerType
import yacht.engine.league.StoryStage
import yacht.engine.league.StyleFamily
import yacht.engine.league.WinEstimate

/**
 * The app's texts with names in them (UX 14.3, 16): every [Case] read with every [Names] — club,
 * nation and player names ending with and without a final consonant, seasons 2 and 3 — takes the
 * right particle after each name (이/가, 은/는, 을/를, 과/와, (으)로, 이에요/예요, …), keeps no "{" or
 * "|" of a template, and stays within its lines at a 360 dp phone's width, measured with the
 * harness's text measurer ([lineCount]). Each app stage adds the cases of its texts to [CASES].
 */
class StoryTextsTest {
    /** The names a text is read with: a club's, a nation's, a player's, and a season. */
    data class Names(val club: String, val nation: String, val player: String, val season: Int) {
        /** The words a particle may follow, each as the text says it. */
        val words: List<String> get() = listOf(club, nation, player, "시즌 $season")
    }

    /**
     * One text of the app: [text] built from the [Names] (as the screen builds it), shown in [style]
     * across [widthDp] of a 360 dp phone in at most [maxLines] lines.
     */
    class Case(val name: String, val style: TextStyle, val widthDp: Float, val maxLines: Int, val text: (Names) -> String)

    @Test
    fun everyTextTakesTheRightParticlesAndFitsItsLines() {
        val problems = ArrayList<String>()
        var read = 0
        for (case in CASES) {
            for (names in NAMES) {
                val text = case.text(names)
                read++
                if ('{' in text || '|' in text) problems += "${case.name} ($names): a template's slot is left: $text"
                for (word in names.words) wrongParticle(text, word)?.let { problems += "${case.name} ($names): \"$it\" after \"$word\": $text" }
                val lines = lineCount(text, case.style, case.widthDp)
                if (lines > case.maxLines) problems += "${case.name} ($names): $lines lines (at most ${case.maxLines}) at ${case.widthDp} dp: $text"
            }
        }
        println("story texts: ${CASES.size} texts read $read times; with ${NAMES.last()}:")
        for (case in CASES) println("  ${case.name}: ${case.text(NAMES.last())}")
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun theCheckerFindsAWrongParticle() {
        // The runner's own check: a text that ignores the name's last sound is caught, either way.
        assertTrue(wrongParticle("일본가 이겼어요", "일본") == "가")
        assertTrue(wrongParticle("캐나다이 이겼어요", "캐나다") == "이")
        assertTrue(wrongParticle("광주 불곰로 갔어요", "광주 불곰") == "로")
        assertTrue(wrongParticle("부산 갈매기은 3위예요", "부산 갈매기") == "은")
        assertTrue(wrongParticle("시즌 3가 끝났어요", "시즌 3") == "가")
        assertTrue(wrongParticle("일본이 이기고 캐나다는 졌어요", "일본") == null)
        assertTrue(wrongParticle("일본이 이기고 캐나다는 졌어요", "캐나다") == null)
        assertTrue(wrongParticle("서울로 가요", "서울") == null)
    }

    @Test
    fun thePhasesNewsHasItsFilterIconAndStoryPill() {
        fun a(kind: String, season: Int = 3, scope: String = "") = Article(season, kind, "제목", "본문", emptyList(), null, 2, scope = scope)
        // UX 11: 이적·클럽 holds the transfers, the coaches and the clubs' own news; 국제 the 요트협회,
        // the world events, the World Cup, the eras and the media.
        val market = listOf(
            "transfer", "transfer_abroad", "market_digest", "draft", "youth", "market_week", "record_fee", "rival_move", "free_agent",
            "winter_window", "loan_digest", "coach_sacked", "coach_digest", "player_to_coach", "club_accounts", "club_trouble",
            "club_ranking", "forced_sale", "administration", "rescue", "takeover", "owner_exit", "sponsor_deal", "stadium_open",
            "fan_protest", "wonderkid", "academy_graduates", "one_club_man", "club_legend", "captain", "derby_result", "rivalry_born",
        )
        val world = listOf(
            "federation_assembly", "federation_decision", "superleague_announce", "investor_rumour", "crisis_begins", "merger_proposed",
            "league_merged", "league_split", "boom_begins", "boom_ends", "wc_bids", "wc_host_vote", "wc_draw_ceremony", "era_begins",
            "era_ends", "counter_style", "imitation", "press_award", "press_awards", "predictions", "prediction_scorecard", "power_ranking",
        )
        for (k in market) assertTrue(newsShown(a(k), NewsFilter.MARKET) && !newsShown(a(k), NewsFilter.WORLD), "$k: 이적·클럽 only")
        for (k in world) assertTrue(newsShown(a(k), NewsFilter.WORLD) && !newsShown(a(k), NewsFilter.MARKET), "$k: 국제 only")
        assertEquals("이적·클럽", filterLabel(NewsFilter.MARKET))
        assertEquals(NewsFilter.WORLD.label, filterLabel(NewsFilter.WORLD))
        // Today's kinds keep their icons; every new kind has its own.
        val today = mapOf(
            "title" to "🏆", "club_title" to "🏆", "upset" to "⚡", "transfer" to "✈️", "market_digest" to "✈️", "federation_assembly" to "🏛",
            "club_race" to "📈", "record" to "📝", "club_founded" to "📰", "wc_open" to "📰", "rivalry" to "📰", "youth" to "📰",
        )
        for ((k, icon) in today) assertEquals(icon, kindIcon(k), k)
        for (k in market + world - today.keys) assertNotEquals("📰", kindIcon(k), "$k has no icon of its own")
        // The story pills: each a link pageOf reads; a world event's names its arc, once its id is known.
        val noArc = { _: ArcKind, _: Int -> null }
        assertEquals("📈 파워 랭킹 ›" to "pr", articleLink(a("power_ranking"), noArc))
        assertEquals(Page.Press("KR"), pageOf(articleLink(a("predictions", scope = "KR"), noArc)!!.second))
        assertEquals(Page.Press(), pageOf(articleLink(a("press_award"), noArc)!!.second))
        assertEquals(Page.Meta(), pageOf(articleLink(a("era_begins"), noArc)!!.second))
        // The host vote is held the season before its World Cup; the draw opens the World Cup's season.
        assertEquals(Page.WcStory(3), pageOf(articleLink(a("wc_host_vote", season = 2), noArc)!!.second))
        assertEquals(Page.WcStory(3), pageOf(articleLink(a("wc_draw_ceremony", season = 3), noArc)!!.second))
        assertEquals(Page.Market(), pageOf(articleLink(a("record_fee"), noArc)!!.second))
        assertEquals(Page.ClubRanking(), pageOf(articleLink(a("club_ranking"), noArc)!!.second))
        assertNull(articleLink(a("superleague_announce", season = 7), noArc))
        val arcs = { k: ArcKind, season: Int -> if (k == ArcKind.SUPER_LEAGUE && season == 7) 4 else null }
        assertEquals("🌐 자세히 ›" to "ev:4", articleLink(a("superleague_announce", season = 7), arcs))
        for (k in today.keys) assertNull(articleLink(a(k), arcs), "$k has no story pill")
    }

    @Test
    fun aPageWaitingForItsContentHasNoLinkYet() {
        // Rule 4: a page's entry points ship with its content. The stage that ships one of these
        // takes it off [linkable] (and off this list); until then no link built from data opens it.
        val waiting = listOf(
            Page.Events, Page.Event(4), Page.WcStory(3), Page.Encyclopedia("p:g001"), Page.Search,
        )
        for (p in waiting) assertFalse(linkable(p), "$p is linkable before its content")
        // P8 shipped the coach page and 클럽 랭킹, P12 기자단.
        val shipped = listOf(
            Page.Player("g001"), Page.Club("c01"), Page.League("KR"), Page.Nation("KR"), Page.Market(), Page.Meta(), Page.Coach("k1"), Page.ClubRanking(),
            Page.Press(), Page.Press("KR", reveal = 2),
        )
        for (p in shipped) assertTrue(linkable(p), "$p")
        // So an article's story pill shows only where it can lead: 이적 센터, 시대와 메타, 클럽 랭킹 and 기자단 today.
        fun a(kind: String) = Article(3, kind, "제목", "본문", emptyList(), null, 2, scope = "KR")
        val arcs = { _: ArcKind, _: Int -> 4 }
        assertEquals("💸 이적 센터 ›" to Page.Market(), storyPill(a("record_fee"), arcs))
        assertEquals("🧭 시대 ›" to Page.Meta(), storyPill(a("era_begins"), arcs))
        assertEquals("🏅 클럽 랭킹 ›" to Page.ClubRanking(), storyPill(a("club_ranking"), arcs))
        assertEquals("📈 파워 랭킹 ›" to Page.Press(), storyPill(a("power_ranking"), arcs))
        assertEquals(Page.Press("KR"), storyPill(a("predictions"), arcs)?.second)
        assertEquals(Page.Press(), storyPill(a("press_award"), arcs)?.second)
        for (k in listOf("wc_bids", "wc_host_vote", "wc_draw_ceremony", "superleague_announce")) {
            assertNotNull(articleLink(a(k), arcs), k)
            assertNull(storyPill(a(k), arcs), "$k leads to a page still waiting")
        }
    }

    @Test
    fun thePhasesLabelsAndIconsAreTheirOwn() {
        // UX 14.1's honours, owner and arc icons; UX 8.3's family colours, lighter in the dark theme.
        assertEquals(listOf("골든 다이스", "올해의 영입"), listOf("GD", "SIGN").map(::honourLabel))
        assertEquals(listOf("🎲", "✍"), listOf("GD", "SIGN").map(::trophy))
        assertEquals(OwnerType.entries.size, OwnerType.entries.map(::ownerIcon).toSet().size)
        assertEquals(ArcKind.entries.size, ArcKind.entries.map(::arcIcon).toSet().size)
        val families: List<StyleFamily?> = StyleFamily.entries + null
        assertEquals(families.size, families.map { familyColor(it, dark = false) }.toSet().size)
        for (f in families) {
            val light = familyColor(f, dark = false)
            val dark = familyColor(f, dark = true)
            assertTrue(dark != light && dark.red >= light.red && dark.green >= light.green && dark.blue >= light.blue, "$f")
        }
    }

    @Test
    fun theStoriesWorldEventsAreTheScreensOwn() {
        // The review's and the chronicle's arc lines (core) take the screens' icons (ui), and read each arc kind's news kinds.
        for (k in ArcKind.entries) assertEquals(arcIcon(k), StoryLines.arcIcon(k), k.name)
        assertEquals(ArcKind.SUPER_LEAGUE, StoryLines.arcOf("superleague_outcome"))
        assertEquals(ArcKind.MERGER, StoryLines.arcOf("league_merged"))
        assertEquals(ArcKind.BOOM, StoryLines.arcOf("boom_ends"))
        assertEquals(ArcKind.INVESTOR, StoryLines.arcOf("investor_verdict"))
        assertEquals(ArcKind.CRISIS, StoryLines.arcOf("crisis_begins"))
        assertNull(StoryLines.arcOf("era_begins"))
        assertEquals("📉 금융 위기가 시작됐어요", StoryLines.arcOutcome(ArcKind.CRISIS, "crisis_begins"))
        assertEquals("🔱 슈퍼리그 분리 위협이 결말을 맞았어요", StoryLines.arcOutcome(ArcKind.SUPER_LEAGUE, "superleague_outcome"))
        // Points with their sign; a matchup cell's value.
        assertEquals(listOf("+3.2", "-1.0", "0"), listOf(3.24, -1.0, 0.02).map(StoryLines::signed))
        assertEquals(listOf("+0.2", "-0.1", "0"), listOf(0.21, -0.14, -0.04).map(MetaTexts::excess))
        assertEquals(listOf("0.42", "-0.10"), listOf(42, -10).map(PressTexts::rho))
        // A tapped matchup cell in a sentence: more or fewer points than expected, or as many.
        assertEquals("족보파가 요트파를 만나 기대보다 경기당 승점을 0.31점 더 땄어요 (52경기).", MetaTexts.cell("족보파", "요트파", 0.31, 52))
        assertEquals("요트파가 족보파를 만나 기대보다 경기당 승점을 0.25점 덜 땄어요 (52경기).", MetaTexts.cell("요트파", "족보파", -0.25, 52))
        assertEquals("정석파가 신중파를 만나 기대만큼 승점을 땄어요 (40경기).", MetaTexts.cell("정석파", "신중파", 0.001, 40))
    }

    @Test
    fun theClubWorldsWordsFollowTheirThresholds() {
        // UX 5's pressure levels and 14.1's mood words and rank moves.
        assertEquals(listOf(PressureLevel.SAFE, PressureLevel.OK, PressureLevel.SHAKY, PressureLevel.DANGER, PressureLevel.EDGE, PressureLevel.EDGE), listOf(0.24, 0.25, 0.5, 0.75, 1.0, 3.0).map(PressureLevel::of))
        assertEquals(PressureLevel.SAFE, PressureLevel.of(Double.NaN))
        assertEquals(listOf("🤩", "😊", "😐", "😠", "😡"), listOf(80, 60, 40, 25, 24).map { moodWord(it).second })
        assertEquals(listOf("▲2", "▼1", "–", "NEW"), listOf(2, -1, 0, null).map(::rankMove))
    }

    companion object {
        /** Every pairing of the names that end with a final consonant and those that do not, and seasons 2 (이: 가) and 3 (삼: 이). */
        val NAMES: List<Names> = buildList {
            for (club in listOf("부산 갈매기", "광주 불곰")) for (nation in listOf("캐나다", "일본")) for (player in listOf("알렉산드르 페트로프", "응우옌 반 꽝")) {
                for (season in listOf(2, 3)) add(Names(club, nation, player, season))
            }
        }

        private val type = Typography()

        /** The SeasonEnd banner's text width at 360 dp: the home's 12 dp sides and the card's 14 and 6 dp. */
        private const val BANNER_DP = 360f - 2 * 12 - 14 - 6

        /** A sheet's text width at 360 dp (its 16 dp sides), and its title's beside the 닫기 button. */
        private const val SHEET_DP = 360f - 2 * 16
        private const val SHEET_TITLE_DP = SHEET_DP - 64

        /** A small national world played one season, whose close the SeasonEnd banner reads (built once). */
        private val closed: LeagueWorld by lazy { StoryWorld.build(players = 120, seasons = 1, untilWeek = 1).world }

        /** The SeasonEnd banner HomeFeed builds for [closed], every player named [n]'s player. */
        private fun seasonEnd(n: Names): HomeBanner.SeasonEnd {
            val w = closed
            return assertIs(HomeFeed(NamedHost(w, n.player)).banner(w, w.plan, UiPrefs(), emptySet()))
        }

        /**
         * The texts: today's SeasonEnd banner first (its title as ui/LeagueHome.kt's SeasonEndBanner
         * writes it, in its bold, its two lines as HomeFeed builds them); each app stage appends its own.
         */
        val CASES: List<Case> = listOf(
            Case("SeasonEnd title", type.titleSmall.copy(fontWeight = FontWeight.Bold), BANNER_DP, 1) { n -> "🏆 ${Ko.i("시즌 ${n.season}")} 끝났어요" },
            Case("SeasonEnd line 1", type.bodySmall, BANNER_DP, 2) { n -> seasonEnd(n).lines[0] },
            Case("SeasonEnd line 2", type.bodySmall, BANNER_DP, 2) { n -> seasonEnd(n).lines.getOrElse(1) { "" } },
        ) +
            // The ⓘ sheet (UX 14.4): each title on one line beside 닫기, each explanation within half a phone's sheet.
            InfoTopic.entries.flatMap { t ->
                listOf(
                    Case("info $t title", type.titleMedium.copy(fontWeight = FontWeight.Bold), SHEET_TITLE_DP, 1) { infoTitle(t) },
                    Case("info $t", type.bodySmall, SHEET_DP, INFO_LINES) { infoText(t) },
                )
            } +
            CLUB_CASES + STORY_CASES

        /** 기자단's and 시대·스타일's card text width at 360 dp (the page's 12 dp sides and the card's 12), and a title's beside its ⓘ. */
        private const val CARD_DP = 360f - 2 * 12 - 2 * 12
        private const val CARD_TITLE_DP = CARD_DP - 24

        /** An era of the longest family name (심리전파, its second era). */
        private fun era(n: Names) = EraView(2, StyleFamily.MIND, "두 번째 심리전파의 시대", n.season, null, n.player to n.player)

        /** 기자단's, 시대와 메타's, the review's and the league page's texts of P12 (UX 7-9.4). */
        private val STORY_CASES: List<Case> get() = listOf(
            Case("press power title", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 1) { PressTexts.powerTitle(38) },
            Case("press last power title", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 1) { n -> PressTexts.lastPowerTitle(n.season, 38) },
            Case("press not ranked", type.bodySmall, CARD_DP, 2) { PressTexts.notRanked(6) },
            Case("press power line 2", type.labelSmall, CARD_DP - 22 - 36 - 18 - 44, 2) { "아프리카·중동 연합 1부 · " + StoryLines.powerSub(-12.34, 3) },
            Case("press weeks at no. 1", type.bodySmall, CARD_DP, 3) { n -> "이번 시즌 · " + listOf(n.player, n.player, n.player).joinToString(" · ") { PressTexts.weeks("🇨🇦 $it", 12) } },
            Case("press prediction title", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 2) { n -> PressTexts.predictionTitle("🌍 아프리카·중동 연합 리그", n.season) },
            Case("press versus title", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 2) { PressTexts.versusTitle("🌍 아프리카·중동 연합 리그") },
            Case("press favourite", type.bodySmall, CARD_DP - 120, 1) { n -> PressTexts.favourite(1, "🇨🇦 ${n.player}") },
            Case("press dark horse", type.bodySmall, CARD_DP, 2) { n -> PressTexts.dark("🇨🇦 ${n.player}", 12, 21, 11) },
            Case("press relegation", type.bodySmall, CARD_DP, 2) { n -> PressTexts.relegation("🇨🇦 ${n.player}", 34, 13) },
            Case("press order", type.bodySmall, CARD_DP, 2) { n -> PressTexts.order(3, "🇨🇦 ${n.player}", 15) },
            Case("press scorecard title", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 1) { n -> PressTexts.scorecardTitle(n.season) },
            Case("press no scorecard", type.bodySmall, CARD_DP, 2) { n -> PressTexts.noScorecard(n.season) },
            Case("press scorecard lead", type.bodyMedium, CARD_DP, 2) { PressTexts.scorecardLead(5, 24) },
            Case("press scorecard line", type.bodySmall, CARD_DP, 2) { PressTexts.scorecardLine(13, 24, 31, 72, -8) },
            Case("press over", type.bodySmall, CARD_DP, 2) { n -> PressTexts.over("🇨🇦 ${n.player}", 14, 2) },
            Case("press flop", type.bodySmall, CARD_DP, 2) { n -> PressTexts.flop("🇨🇦 ${n.player}", 1, 16) },
            Case("press league score", type.bodySmall, CARD_DP, 2) { PressTexts.leagueScore("🌍 아프리카·중동 연합 리그", false, true, 2, -12) },
            Case("press vote title", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 1) { n -> PressTexts.voteTitle(n.season, 40) },
            Case("press ballot", type.bodySmall, CARD_DP, 2) { PressTexts.BALLOT },
            Case("press no vote (first)", type.bodySmall, CARD_DP, 2) { n -> PressTexts.noVote(n.season, first = true) },
            Case("press no vote", type.bodySmall, CARD_DP, 2) { n -> PressTexts.noVote(n.season, first = false) },
            Case("press points", type.labelMedium.copy(fontWeight = FontWeight.Bold), VOTE_POINTS.value, 1) { PressTexts.points(150, 30) },
            Case("press legacy", type.bodyMedium, PAGE_DP, 2) { PressTexts.LEGACY },
            Case("meta now", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 2) { n -> MetaTexts.now(era(n), chaos = false) },
            Case("meta chaos", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 2) { MetaTexts.CHAOS },
            Case("meta none", type.titleSmall.copy(fontWeight = FontWeight.Bold), CARD_TITLE_DP, 1) { MetaTexts.NONE },
            Case("meta since", type.bodySmall, CARD_DP, 3) { n -> MetaTexts.since(era(n), n.season + 11, 9 to 18, chaos = false) },
            Case("meta candidate", type.labelMedium, CARD_DP, 2) { MetaTexts.candidate(StyleFamily.MIND, "두 번째 심리전파의 시대") },
            Case("meta share", type.bodySmall, CARD_DP - 16, 1) { MetaTexts.share(StyleFamily.MIND, 33, 30, -12) },
            Case("meta timeline", type.bodySmall, CARD_DP - 12, 1) { n -> MetaTexts.timeline(era(n).copy(from = n.season + 10)) },
            Case("meta cap", type.labelSmall, CARD_DP, 2) { MetaTexts.cap(17) },
            Case("meta fashion", type.bodySmall, CARD_DP, 2) { MetaTexts.FASHION },
            Case("meta grid", type.labelSmall, CARD_DP, 2) { MetaTexts.GRID },
            Case("meta cell", type.labelSmall, CARD_DP, 2) { MetaTexts.cell("심리전파", "정석파", -0.214, 52) },
            Case("meta cell (few)", type.labelSmall, CARD_DP, 2) { MetaTexts.cell("심리전파", "정석파", Double.NaN, 12) },
            Case("meta cell (even)", type.labelSmall, CARD_DP, 2) { MetaTexts.cell("심리전파", "정석파", 0.0, 152) },
            Case("meta empty", type.bodyMedium, PAGE_DP, 2) { MetaTexts.EMPTY },
            Case("meta counter", type.bodySmall, CARD_DP, 2) { StoryLines.counter(StyleFamily.MIND, StyleFamily.CAUTION, 0.31) },
            Case("counts line with the era", type.bodySmall, SHEET_DP, 2) { n -> "선수 3,000명 · 총 123,456경기 · " + StoryLines.eraLine("두 번째 심리전파의 시대", n.season) },
            Case("league page style", type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), PAGE_DP - 24, 1) { WorldPageTexts.style(StyleFamily.MIND, 31) },
            Case("review stories title", type.titleSmall.copy(fontWeight = FontWeight.Bold), PAGE_DP, 1) { ReviewTexts.STORIES },
            Case("review era begins", type.bodySmall, PAGE_DP - 16, 2) { StoryLines.eraBegins("두 번째 심리전파의 시대") },
            Case("review era ends", type.bodySmall, PAGE_DP - 16, 2) { n -> StoryLines.eraEnds("두 번째 심리전파의 시대", n.season) },
            Case("review wc host", type.bodySmall, PAGE_DP - 16, 2) { n -> StoryLines.wcHost(n.season, "🇨🇦 ${n.nation}") },
            Case("review record fee", type.bodySmall, PAGE_DP - 16, 2) { n -> StoryLines.recordFee("1,240.0억", "🇨🇦 ${n.player}") },
            Case("review league record fee", type.bodySmall, PAGE_DP - 16, 2) { n -> StoryLines.recordFee("1,240.0억", "🇨🇦 ${n.player}", "🌍 아프리카·중동 연합 리그") },
            Case("review golden dice", type.bodySmall, PAGE_DP - 16, 2) { n -> StoryLines.goldenDice("🇨🇦 ${n.player}", 142) },
            Case("review golden row", type.bodySmall, PAGE_DP - 16, 1) { n -> ReviewTexts.golden(1, "🇨🇦 ${n.player}", 142) },
            Case("review season", type.labelSmall, PAGE_DP, 2) { n -> ReviewTexts.season(23, 2, "🇨🇦 ${n.player}")!! },
            Case("review era (began)", type.bodySmall, PAGE_DP - 16, 2) { StoryLines.era("두 번째 심리전파의 시대", 'B') },
            Case("review era", type.bodySmall, PAGE_DP - 16, 2) { StoryLines.era(MetaBook.CHAOS, '-') },
            Case("wrapped predict", type.bodyMedium, PAGE_DP - 40 - 32, 3) { n -> StoryLines.predict(5, 24, "🇨🇦 ${n.player}") },
        ) + ArcKind.entries.flatMap { k ->
            listOf("${k.name.lowercase()}_begins", "${k.name.lowercase()}_outcome").map { kind ->
                Case("review arc $kind", type.bodySmall, PAGE_DP - 16, 2) { StoryLines.arcOutcome(k, kind) }
            }
        }

        /** A club or coach page's text width at 360 dp (its 16 dp sides), a line's beside its "›", the club header's beside its 48 dp emblem. */
        private const val PAGE_DP = 360f - 2 * 16
        private const val LINE_DP = PAGE_DP - 16
        private const val HEADER_DP = PAGE_DP - 60

        /** A rivalry's name as the engine names it, from the sample's club ("부산-서울 클래식"). */
        private fun rivalry(n: Names) = "${n.club.substringBefore(' ')}-서울 클래식"

        /** The club pages', the coach page's, the 💼 line's and 클럽 랭킹's texts (UX 4-5). */
        private val CLUB_CASES: List<Case> get() = listOf(
            Case("club identity", type.labelSmall, HEADER_DP, 2) { n -> ClubTexts.identity("${n.club}의 자존심", n.club.substringBefore(' '), "🇨🇦 ${n.nation}", 1923) },
            Case("board warning (winter)", type.labelSmall, PAGE_DP, 1) { ClubTexts.warning(true) },
            Case("board warning (close)", type.labelSmall, PAGE_DP, 1) { ClubTexts.warning(false) },
            Case("captain", type.bodySmall, LINE_DP, 1) { n -> ClubTexts.captain("🇨🇦 ${n.player}", n.season) },
            Case("rival", type.bodySmall, LINE_DP, 2) { n -> ClubTexts.rival(rivalry(n), n.club, 12, 3, 9) },
            Case("rival history", type.bodySmall, LINE_DP, 2) { n -> ClubTexts.rivalHistory(rivalry(n), n.club, 12, 3, 9, listOf(1, 0, 1), n.season) },
            Case("story", type.bodySmall, PAGE_DP - 48, 2) { n -> ClubTexts.story(storyStage(StoryStage.TALKS), n.player, n.club, "서울 하마") },
            Case("squad lead", type.bodyMedium, PAGE_DP, 2) { ClubTexts.squadLead(12, 27.4, 64) },
            Case("member", type.bodySmall, PAGE_DP - 26 - 60, 1) { n -> ClubTexts.member("🇨🇦 ${n.player}", true, true, "🌱⏳") },
            Case("graduates", type.labelSmall, PAGE_DP, 1) { ClubTexts.graduates(4) },
            Case("trouble 1", type.bodySmall, PAGE_DP - 24, 3) { ClubTexts.trouble(1)!! },
            Case("trouble 2", type.bodySmall, PAGE_DP - 24, 3) { ClubTexts.trouble(2)!! },
            Case("trouble 3", type.bodySmall, PAGE_DP - 24, 3) { ClubTexts.trouble(3)!! },
            Case("accounts (net)", type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), PAGE_DP, 2) { ClubTexts.accounts("32.2억", "18.8억", 134) },
            Case("accounts (loss)", type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), PAGE_DP, 2) { ClubTexts.accounts("1,240.0억", "1,302.5억", -625) },
            Case("first accounts", type.bodyMedium, PAGE_DP, 1) { n -> ClubTexts.firstAccounts(n.season) },
            Case("sponsor", type.bodySmall, PAGE_DP - 80, 2) { n -> ClubTexts.sponsor("${n.club.substringBefore(' ')} 텔레콤", "4.6억", 9) },
            Case("stadium", type.bodySmall, PAGE_DP, 2) { n -> ClubTexts.stadium("${n.club.substringBefore(' ')} 스타디움", 13500, 12840, 95) },
            Case("project E", type.labelSmall, PAGE_DP, 2) { ClubTexts.project('E', 13125, 8, "2.6억", "5.3억") },
            Case("project N", type.labelSmall, PAGE_DP, 2) { ClubTexts.project('N', 30000, 9, "12.5억", "40.0억") },
            Case("fans", type.bodySmall, PAGE_DP, 2) { n -> ClubTexts.fans(23320, 3, n.nation, 1, 68, "😊") },
            Case("titles", type.labelSmall, PAGE_DP, 2) { ClubTexts.titles(4, listOf(2, 3, 5, 6)) },
            Case("legend", type.bodySmall, PAGE_DP - 130, 2) { n -> ClubTexts.legend(7, "🇨🇦 ${n.player}", 11, 4) },
            Case("record", type.bodySmall, LINE_DP, 2) { n -> ClubTexts.record("최다 출전", "🇨🇦 ${n.player}", "312경기", n.season) },
            Case("tenure", type.bodySmall, LINE_DP, 2) { n -> ClubTexts.tenure(n.season, 0, "🇨🇦 ${n.player}", 2, 1, tenureEnd('-')) },
            Case("fee", type.bodySmall, LINE_DP, 2) { n -> ClubTexts.fee(n.season, "🇨🇦 ${n.player}", true, n.club, "18.5억") },
            Case("story reason", type.labelSmall, PAGE_DP, 1) { n -> storyReason('H', n.club)!! },
            Case("coach header", type.labelMedium, PAGE_DP - 70, 1) { CoachTexts.header(47, "유망주 육성형") },
            Case("coach club", type.labelMedium, PAGE_DP - 18, 1) { n -> CoachTexts.club(n.club, n.season) },
            Case("coach free", type.labelMedium, PAGE_DP, 1) { CoachTexts.free(12) },
            Case("coach retired", type.labelMedium, PAGE_DP, 1) { n -> CoachTexts.retired(n.season) },
            Case("coach board", type.bodySmall, PAGE_DP, 3) { n -> CoachTexts.board("${n.nation} 리그", 12, 3, 7) },
            Case("coach older", type.labelSmall, PAGE_DP, 1) { CoachTexts.older(3, 11) },
            Case("coach career", type.labelSmall, PAGE_DP, 1) { CoachTexts.career(4, 17) },
            Case("coach honours", type.labelSmall, PAGE_DP, 2) { CoachTexts.honours(2, 5, 1, 1) },
            Case("coach fits", type.labelSmall, PAGE_DP, 1) { CoachTexts.fits(setOf(StyleFamily.YACHT, StyleFamily.BOLD)) },
            Case("coach player days", type.bodySmall, PAGE_DP, 2) { CoachTexts.playerDays(14, 6) },
            Case("coach player days (no title)", type.bodySmall, PAGE_DP, 1) { CoachTexts.playerDays(3, 0) },
            Case("contract line", type.labelMedium, PAGE_DP - 78 - 12 - 18, 1) { n -> PlayerClubTexts.line(n.club, 10, true) },
            Case("coach line", type.labelMedium, PAGE_DP - 78 - 12, 1) { n -> PlayerClubTexts.coach(n.club) },
            Case("coach line (retired)", type.labelMedium, PAGE_DP - 78 - 12, 1) { n -> PlayerClubTexts.coach(null, n.season) },
            Case("contract", type.bodySmall, SHEET_DP, 2) { PlayerClubTexts.contract(9, "1.2억", "18.5억", "40.0억") },
            Case("academy", type.bodySmall, SHEET_DP, 1) { n -> PlayerClubTexts.academy(n.club, n.season) },
            Case("loan", type.bodySmall, SHEET_DP, 1) { n -> PlayerClubTexts.loan(n.club) },
            Case("club coach", type.bodySmall, SHEET_DP, 1) { n -> PlayerClubTexts.clubCoach("🇨🇦 ${n.player}") },
            Case("story line", type.bodySmall, SHEET_DP, 1) { n -> PlayerClubTexts.story(n.club, StoryStage.TALKS.label, 24) },
            Case("style", type.labelMedium, PAGE_DP - 16, 1) { PlayerClubTexts.style("요트 사냥꾼", StyleFamily.YACHT)!! },
            Case("ranking grades", type.labelMedium, PAGE_DP, 1) { ClubRankTexts.grades(listOf(3, 12, 14, 8, 3)) },
        ) + ClubRankKind.entries.map { k -> Case("ranking $k", type.labelSmall, 360f - 2 * 12, 2) { ClubRankTexts.sentence(k) } }

        /** Lines an ⓘ explanation may take (bodySmall, 16 dp a line: under 200 dp; the longest, FINANCE, takes 9 here). */
        private const val INFO_LINES = 12

        /** The particle pairs a name slot takes: (after a final consonant, after none). */
        private val PAIRS = listOf(
            "이" to "가", "은" to "는", "을" to "를", "과" to "와", "이에요" to "예요", "이었어요" to "였어요",
            "이지만" to "지만", "이나" to "나", "이라도" to "라도",
        )

        /** The wrong particle right after [word] somewhere in [text] (the form for the other kind of ending); null when every one is right. */
        fun wrongParticle(text: String, word: String): String? {
            val final = Ko.hasFinal(word)
            // (으)로 goes by ㄹ as well: what Ko.ro adds is right, the other form wrong.
            val ro = Ko.ro(word).removePrefix(word)
            val wrong = PAIRS.map { (f, v) -> if (final) v else f } + (if (ro == "로") "으로" else "로")
            var i = text.indexOf(word)
            while (i >= 0) {
                val rest = text.substring(i + word.length)
                wrong.firstOrNull { rest.startsWith(it) }?.let { return it }
                i = text.indexOf(word, i + 1)
            }
            return null
        }
    }

    /** A host of world [w] alone that names every player [player] (the banner's names are the sample's). */
    private class NamedHost(val w: LeagueWorld, val player: String) : LeagueHost {
        override val scope = CoroutineScope(Dispatchers.Unconfined)
        override val lock = Any()
        override val folder = File(".")
        override fun world(): LeagueWorld? = w
        override fun tables(): Tables? = null
        override fun extra(): LeagueTables? = null
        override fun estimate(): WinEstimate? = null
        override fun ai(key: String): LeagueAi? = null
        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? = null
        override fun adaptive(key: String) = false
        override fun name(key: String) = player
        override fun shown(key: String) = player
        override fun badge(key: String): ClubBadge? = null
        override fun leagueLabel(key: String): String? = null
        override fun favourites() = emptySet<String>()
        override fun view(): LeagueView? = null
        override fun running() = false
        override fun startOneWeek() {}
        override fun requestSave() {}
        override fun oppModel(key: String): OppModel? = null
        override fun members() = w.members.size
    }
}

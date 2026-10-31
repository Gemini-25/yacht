package yacht.uicheck

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import yacht.app.core.ClubBadge
import yacht.app.core.HomeBanner
import yacht.app.core.HomeFeed
import yacht.app.core.InfoTopic
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.NewsFilter
import yacht.app.core.Page
import yacht.app.core.PressureLevel
import yacht.app.core.UiPrefs
import yacht.app.core.pageOf
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
import yacht.engine.league.OwnerType
import yacht.engine.league.StyleFamily
import yacht.engine.league.WinEstimate
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
            Page.Coach("k1"), Page.ClubRanking(), Page.Press(), Page.Events, Page.Event(4), Page.WcStory(3), Page.Encyclopedia("p:g001"), Page.Search,
        )
        for (p in waiting) assertFalse(linkable(p), "$p is linkable before its content")
        for (p in listOf(Page.Player("g001"), Page.Club("c01"), Page.League("KR"), Page.Nation("KR"), Page.Market(), Page.Meta())) assertTrue(linkable(p), "$p")
        // So an article's story pill shows only where it can lead: 이적 센터 and the meta report today.
        fun a(kind: String) = Article(3, kind, "제목", "본문", emptyList(), null, 2, scope = "KR")
        val arcs = { _: ArcKind, _: Int -> 4 }
        assertEquals("💸 이적 센터 ›" to Page.Market(), storyPill(a("record_fee"), arcs))
        assertEquals("🧭 시대 ›" to Page.Meta(), storyPill(a("era_begins"), arcs))
        for (k in listOf("power_ranking", "predictions", "press_award", "wc_bids", "wc_host_vote", "wc_draw_ceremony", "club_ranking", "superleague_announce")) {
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
            }

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

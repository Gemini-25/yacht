package yacht.uicheck

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import yacht.app.core.ClubBadge
import yacht.app.core.HomeBanner
import yacht.app.core.HomeFeed
import yacht.app.core.LeagueHost
import yacht.app.core.LeagueView
import yacht.app.core.UiPrefs
import yacht.engine.OppModel
import yacht.engine.Tables
import yacht.engine.explain.Ko
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueTables
import yacht.engine.league.LeagueWorld
import yacht.engine.league.WinEstimate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
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
        )

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

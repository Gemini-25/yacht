package yacht.uicheck

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.runBlocking
import yacht.app.core.ClubRankKind
import yacht.app.core.ClubTab
import yacht.app.core.MarketTab
import yacht.app.core.MetaTab
import yacht.app.ui.ChipRow
import yacht.app.ui.YachtTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phase's page chip rows (UX 2.1: the club page's tabs, 이적 센터's, 시대와 메타's, 클럽 랭킹's lists) drawn as the
 * screens draw them ([ChipRow]) on a 360 dp phone at font scale 1.0 and 1.15: where the last chip
 * ends, and a row wider than the screen scrolls until its last chip shows whole (none is cut off).
 */
class ChipWidthTest {
    /** Where a row's last chip ends unscrolled (dp from the row's start, its end padding in), whether it fits, and whether it scrolls to it whole. */
    private class Row(val lastEnd: Float, val fits: Boolean, val scrollsToLast: Boolean)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun <T> measure(entries: List<T>, label: (T) -> String, fontScale: Float): Row {
        val state = LazyListState()
        val scene = ImageComposeScene(width = (WIDTH_DP * DENSITY).toInt(), height = (60 * DENSITY).toInt(), density = Density(DENSITY, fontScale)) {
            YachtTheme(dark = false) { ChipRow(state, entries, entries.first(), label) {} }
        }
        try {
            scene.render(0)
            scene.render(1_000_000_000L)
            // Each chip's width, from the row as it starts and as it ends (a wide row shows only some at a time).
            val sizes = HashMap<Int, Int>()
            val info = state.layoutInfo
            for (item in info.visibleItemsInfo) sizes[item.index] = item.size
            val fits = info.visibleItemsInfo.size == entries.size && !state.canScrollForward
            // A row wider than the screen: scrolled to its end, the last chip is in view whole.
            runBlocking { state.scrollToItem(entries.lastIndex) }
            scene.render(2_000_000_000L)
            val after = state.layoutInfo
            for (item in after.visibleItemsInfo) sizes[item.index] = item.size
            val shown = after.visibleItemsInfo.lastOrNull()
            val whole = shown != null && shown.index == entries.lastIndex && shown.offset + shown.size <= after.viewportEndOffset
            check(sizes.size == entries.size) { "chips seen: ${sizes.keys}" }
            val end = info.beforeContentPadding + sizes.values.sum() + info.mainAxisItemSpacing * (entries.size - 1) + info.afterContentPadding
            return Row(end / DENSITY, fits, whole)
        } finally {
            scene.close()
        }
    }

    /** [entries]' row at both font scales: it scrolls to its last chip whole; at 1.0 it fits when [fitsAtOne] says so (null: not asked). */
    private fun checkRow(name: String, entries: List<String>, fitsAtOne: Boolean?) {
        for (scale in listOf(1.0f, 1.15f)) {
            val r = measure(entries, { it }, scale)
            println("$name at ${WIDTH_DP.toInt()} dp, font scale $scale: the last chip ends at ${"%.1f".format(r.lastEnd)} dp${if (r.fits) "" else " (scrolls)"}")
            assertTrue(r.scrollsToLast, "$name at font scale $scale: its last chip does not scroll into view whole")
            if (scale == 1.0f && fitsAtOne != null) assertEquals(fitsAtOne, r.fits, "$name at font scale 1.0 (last chip at ${r.lastEnd} dp)")
        }
    }

    /** 이적 센터's four tabs end a few dp inside 360 at font scale 1.0 (354 dp with the harness's fonts here): whether they fit is the fonts' call, so only the scroll is pinned. */
    @Test
    fun theMarketTabsScrollToTheirLastTab() = checkRow("MarketTab", MarketTab.entries.map { it.label }, fitsAtOne = null)

    @Test
    fun theMetaTabsFit() {
        checkRow("MetaTab", MetaTab.entries.map { it.label }, fitsAtOne = true)
        assertTrue(measure(MetaTab.entries.map { it.label }, { it }, 1.15f).fits, "MetaTab at font scale 1.15")
    }

    /** The club page's four tabs ([yacht.app.core.PageChips.CLUB]): short words, whole on the screen at either font scale. */
    @Test
    fun theClubTabsFit() {
        checkRow("ClubTab", ClubTab.entries.map { it.label }, fitsAtOne = true)
        assertTrue(measure(ClubTab.entries.map { it.label }, { it }, 1.15f).fits, "ClubTab at font scale 1.15")
    }

    @Test
    fun theClubRankingListsFitAndScrollWhenTheFontIsLarger() = checkRow("ClubRankKind", ClubRankKind.entries.map { it.label }, fitsAtOne = true)

    private companion object {
        /** A narrow phone's width, and the harness's pixels per dp. */
        const val WIDTH_DP = 360f
        const val DENSITY = 2f
    }
}

package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import yacht.app.core.LeagueController
import yacht.app.core.LeagueNav
import yacht.app.core.LeagueView
import yacht.app.core.Page

// 백과사전 (UX 10): an entity's encyclopedia page, the search, and the 📖 pill on every entity page.
// Their views ([yacht.app.core.EncPages]) and bodies come with the encyclopedia stage; until then
// no link a user can tap opens them ([linkable]): the pill shows nothing.

/**
 * The 📖 pill (UX 10.3): "📖 백과" (labelSmall on surfaceVariant, 28 dp high), opening [ref]'s
 * encyclopedia page ([ref] an encyclopedia reference, "c:c05"); nothing while that page cannot
 * be opened yet.
 */
@Composable
internal fun EncPill(nav: LeagueNav, ref: String, modifier: Modifier = Modifier) {
    val page = Page.Encyclopedia(ref)
    if (!linkable(page)) return
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant).clickable { nav.open(page) }.padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("📖 백과", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

/** [Page.Encyclopedia]: loads the page and waits for it. */
@Composable
internal fun EncyclopediaBody(lg: LeagueController, v: LeagueView, page: Page.Encyclopedia) {
    LaunchedEffect(page.loadKey, v.clock?.season, v.clock?.week) { lg.encPages.loadPage(page.ref) }
    CenterText("백과사전 문서를 불러오는 중…")
}

/** [Page.Search]: searches for [LeagueNav.searchQuery] (an empty one: 둘러보기) and waits for the results. */
@Composable
internal fun SearchBody(lg: LeagueController, v: LeagueView) {
    val query = lg.nav.searchQuery
    LaunchedEffect(query, v.clock?.season, v.clock?.week) { lg.encPages.search(query) }
    CenterText("백과사전을 불러오는 중…")
}

package yacht.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView

// 시대와 메타 (UX 8): 시대·스타일 and 칸별 메타. Today the page is the meta report (메타 리포트), which
// becomes its 칸별 메타 tab unchanged; the eras tab over [yacht.app.core.StoryPages.meta] and the
// tab row come with the meta stage.

/** [yacht.app.core.Page.Meta]: the meta report of the league's records. */
@Composable
internal fun MetaPageBody(lg: LeagueController, v: LeagueView) {
    LaunchedEffect(v.season?.number) { lg.loadRecords() }
    MetaReportView(lg.records)
}

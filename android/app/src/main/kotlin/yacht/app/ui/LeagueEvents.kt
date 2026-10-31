package yacht.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page

// 세계 정세 (UX 9): the world events going on and ended, one event's arc, and a World Cup's host vote
// and draw ceremony. Their views ([yacht.app.core.EventPages]) and bodies come with the world
// events stage; until then no link a user can tap opens them ([linkable]).

/** [Page.Events]: loads 세계 정세 and waits for the view. */
@Composable
internal fun EventsPageBody(lg: LeagueController, v: LeagueView) {
    LaunchedEffect(v.clock?.season, v.clock?.week) { lg.eventPages.loadEvents() }
    CenterText("세계 정세를 불러오는 중…")
}

/** [Page.Event]: loads the arc and waits for the view. */
@Composable
internal fun EventPageBody(lg: LeagueController, v: LeagueView, page: Page.Event) {
    LaunchedEffect(page.loadKey, v.clock?.season, v.clock?.week) { lg.eventPages.loadEvent(page.id) }
    CenterText("세계 정세를 불러오는 중…")
}

/** [Page.WcStory]: loads the World Cup's story and waits for the view. */
@Composable
internal fun WcStoryBody(lg: LeagueController, v: LeagueView, page: Page.WcStory) {
    LaunchedEffect(page.loadKey, v.clock?.season, v.clock?.week) { lg.eventPages.loadWc(page.season) }
    CenterText("월드컵 기록을 불러오는 중…")
}

package yacht.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page

// 기자단 (UX 7): the power ranking, the season's predictions and the press vote on one page,
// ordered by the season's phase. Its view ([yacht.app.core.StoryPages.press]) and body come with
// the media stage; until then no link a user can tap opens it ([linkable]).

/** [Page.Press]: loads 기자단 for its league (the shown league when it names none) and waits for the view. */
@Composable
internal fun PressPageBody(lg: LeagueController, v: LeagueView, page: Page.Press) {
    val league = page.league ?: v.season?.league.orEmpty()
    LaunchedEffect(page.loadKey, league, v.clock?.season, v.clock?.week) { lg.storyPages.loadPress(league) }
    CenterText("기자단 기록을 불러오는 중…")
}

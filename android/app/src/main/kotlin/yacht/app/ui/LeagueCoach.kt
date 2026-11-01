package yacht.app.ui

import androidx.compose.runtime.Composable

// 감독 (UX 5): a coach's philosophy, board, career and honours. Its loader (`WorldPages.loadCoach`)
// and its body come with the club world's pages; until then no link a user can tap opens it
// ([linkable]).

/** [yacht.app.core.Page.Coach]: waits for the coach's view, which nothing loads yet. */
@Composable
internal fun CoachPageBody() {
    CenterText("감독 기록을 불러오는 중…")
}

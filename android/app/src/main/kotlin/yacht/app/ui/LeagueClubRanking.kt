package yacht.app.ui

import androidx.compose.runtime.Composable

// 클럽 랭킹 (UX 4.8): the clubs by value, results, income, fans and health, the scope in the bar.
// Its loader (`WorldPages.loadClubRanking`) and its body come with the club world's pages; until
// then no link a user can tap opens it ([linkable]).

/** [yacht.app.core.Page.ClubRanking]: waits for the ranking's view, which nothing loads yet. */
@Composable
internal fun ClubRankingBody() {
    CenterText("클럽 랭킹을 불러오는 중…")
}

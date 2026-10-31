package yacht.app.ui

import androidx.compose.runtime.Composable

// The phase's new pages (감독, 클럽 랭킹, 기자단, 세계 정세, the World Cup story, the encyclopedia and its
// search) before their stages fill them in: each says so. No link a user can tap opens one yet
// (only the harness and NavTest do); the page's own stage ships its entry points with its body.

/** A page still to come: "준비 중이에요". */
@Composable
internal fun StubPage() {
    CenterText("이 페이지는 준비 중이에요.")
}

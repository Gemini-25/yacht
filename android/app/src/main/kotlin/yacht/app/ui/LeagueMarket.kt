package yacht.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.engine.league.ClubMove
import yacht.engine.league.ClubMoveKind
import yacht.engine.league.ClubMoveType

// 이적·드래프트, the 이적 센터 to be (UX 6): the page's body (from `lg.marketPages`) and a move's row,
// which the club pages list too.

/** [Page.Market]: the national clubs' moves ([NationalMarket]), or a legacy club league's window and draft. */
@Composable
internal fun MarketBody(lg: LeagueController, v: LeagueView) {
    if (v.clubs != null || v.badges.isEmpty()) LegacyMarket(lg, v.clubs) else NationalMarket(lg, v)
}

/**
 * [Page.Market] of national clubs: the last window in numbers, then every kept move, newest first
 * (해외 이적, 귀국, 유스 입단, 국내 이적, 드래프트, 방출, 은퇴).
 */
@Composable
private fun NationalMarket(lg: LeagueController, v: LeagueView) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(v.season?.number) { lg.marketPages.load() }
    val m = lg.marketPages.view
    if (m == null) {
        CenterText("이적 기록을 불러오는 중…")
        return
    }
    if (m.moves.isEmpty()) {
        CenterText("시즌이 끝나면 클럽마다 이적 시장이 열리고, 강한 선수는 더 강한 리그의 클럽으로 해외 이적을 가요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        m.digest?.let { d ->
            item {
                Text(
                    "시즌 ${d.season} 이적 시장: 신인 ${d.rookies}명 · 국내 이적 ${d.domestic}건 · 해외 이적 ${d.abroad}건 · 귀국 ${d.returns}건",
                    style = MaterialTheme.typography.labelMedium, color = cs.secondary,
                )
            }
        }
        items(m.moves, key = { x -> "nm-${x.season}-${x.type}-${x.ai}-${x.from}-${x.to}" }) { x -> MoveText(lg, x) { id -> v.badges[id]?.name ?: id } }
    }
}

/** One move of a club's history: its type ([ClubMoveType.label]), the player, the clubs by [clubName]. */
@Composable
internal fun MoveText(lg: LeagueController, m: ClubMove, clubName: (String) -> String) {
    val cs = MaterialTheme.colorScheme
    val from = m.from?.let(clubName)
    val to = m.to?.let(clubName)
    val who = lg.name(m.ai)
    val text = when (m.type) {
        ClubMoveType.ABROAD -> "✈️ $who: $from → $to (해외 이적 · 이적료 ${m.amount})"
        ClubMoveType.HOME -> "🏠 $who: $from → $to (귀국)"
        ClubMoveType.YOUTH -> "🌱 $who: $to 유스 입단"
        else -> when (m.kind) {
            ClubMoveKind.TRANSFER -> "🔁 $who: ${from ?: "자유 계약"} → $to (이적료 ${m.amount})"
            ClubMoveKind.DRAFT -> "📝 $who: $to 드래프트 ${m.amount}순위"
            ClubMoveKind.RELEASED -> "👋 $who: ${from}에서 방출"
            ClubMoveKind.RETIRED -> "🌅 $who: $from 떠남 (은퇴)"
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("S${m.season}", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

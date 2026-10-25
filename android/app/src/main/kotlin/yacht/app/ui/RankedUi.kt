package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.LadderRow
import yacht.app.core.RankResult
import yacht.engine.league.Ladder
import yacht.engine.league.TierName

/** My line on the ladder (ranked games), if I have played one. */
fun myRow(c: AppController): LadderRow? = c.league.view?.rows?.firstOrNull { it.isMe }

/** "다이아몬드 III · 1461" or "배치 2/5판" for my ranked standing. */
fun myStanding(r: LadderRow?): String = when {
    r == null -> "아직 랭크전 기록이 없어요"
    r.tier == null -> "배치 중 ${r.games}/${Ladder.ME_PLACEMENT}판"
    else -> "${r.tier.label} · ${r.rating} · ${r.rank}위"
}

/**
 * Starting a ranked game: an opponent near my rating, or one from a chosen tier. Ranked games are
 * played alone (no hints) and move my rating on the same scale as the league AIs.
 */
@Composable
fun RankedStartDialog(c: AppController, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var tier by remember { mutableStateOf<TierName?>(null) }
    var failed by remember { mutableStateOf(false) }
    val rows = c.league.view?.rows.orEmpty()
    val tiers = rows.filter { !it.isMe }.mapNotNull { it.tier?.name }.toSortedSet(compareByDescending { it.ordinal })
    val me = myRow(c)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (c.newRankedGame(tier = tier)) onDismiss() else failed = true }, enabled = tiers.isNotEmpty()) { Text("시작") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        title = { Text("🎮 랭크전") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("내 랭크: ${myStanding(me)}", fontWeight = FontWeight.Bold)
                Text(
                    "리그 AI와 혼자 둬서 레이팅을 매겨요. 힌트를 보면 연습 경기가 되어 레이팅에 들어가지 않아요. " +
                        "${Ladder.ME_PLACEMENT}판을 두면 배치가 끝나고 티어가 붙어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                if (tiers.isEmpty()) {
                    Text(
                        "아직 레이팅이 있는 AI가 없어요. AI 리그를 먼저 돌리거나 ‘미리 돌린 리그로 시작’을 눌러 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                    )
                } else {
                    Text("상대", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = tier == null, onClick = { tier = null }, label = { Text("내 실력 근처", fontSize = 12.sp) })
                        for (t in tiers) FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(t.label, fontSize = 12.sp) })
                    }
                }
                if (failed) Text("그 티어에는 상대가 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        },
    )
}

/** The rating change of a finished ranked game. */
@Composable
fun RankResultCard(r: RankResult) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface.copy(alpha = 0.8f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("🎮 랭크전 결과 · vs ${r.opponent}", fontWeight = FontWeight.Bold)
        val before = r.before
        val after = r.after
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (r.placementLeft > 0) "임시 " else "") + when {
                    before != null && after != null -> "레이팅 $before → $after"
                    after != null -> "레이팅 $after"
                    else -> "레이팅 계산 중"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            if (before != null && after != null && after != before) {
                Text(
                    "  ${if (after > before) "+" else ""}${after - before}",
                    color = if (after > before) gameColors.good else cs.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        val tier = r.tierAfter
        Text(
            when {
                r.placementLeft > 0 -> "배치 경기 ${Ladder.ME_PLACEMENT - r.placementLeft}/${Ladder.ME_PLACEMENT}판 · ${r.placementLeft}판 더 두면 티어가 나와요"
                r.tierBefore == null && tier != null -> "🎓 배치 완료! ${tier.label}에서 시작해요 (${r.rank}위)"
                tier != null && r.tierBefore != null && tier.order > r.tierBefore.order -> "🎉 ${tier.label} 승급! (${r.rank}위)"
                tier != null && r.tierBefore != null && tier.order < r.tierBefore.order -> "📉 ${tier.label}로 내려갔어요 (${r.rank}위)"
                tier != null -> "${tier.label} · ${tier.lp} LP · ${r.rank}위"
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

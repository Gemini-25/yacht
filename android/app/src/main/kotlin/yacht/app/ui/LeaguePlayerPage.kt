package yacht.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yacht.app.core.AppController
import yacht.app.core.LadderRow
import yacht.app.core.Screen
import yacht.engine.league.Ladder

/** A player's dialog: its ladder line, profile, actions, numbers and matchups ([onOpen]: another player's page). */
@Composable
internal fun AiDetail(c: AppController, r: LadderRow, onOpen: (String) -> Unit, onCompare: () -> Unit, onDismiss: () -> Unit) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    // Read with the profile, off the main thread (the ladder's lock is busy while the league runs).
    androidx.compose.runtime.LaunchedEffect(r.key) { lg.loadProfile(r.key) }
    val profile = lg.profile?.takeIf { it.key == r.key }
    val best = profile?.best.orEmpty()
    val worst = profile?.worst.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierBadge(r.tier, r.games, Modifier.width(78.dp), if (r.isMe) Ladder.ME_PLACEMENT else Ladder.PLACEMENT)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(r.shown, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (r.rank?.let { "${it}위 · " } ?: "") + "레이팅 ${r.rating ?: "–"}" + (r.margin?.let { " ±$it" } ?: "") + (r.peak?.let { " · 최고 $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${r.family.label} · ${r.desc}" + if (r.heavy) " (승률 탐색을 써서 계산이 무거워요)" else "", style = MaterialTheme.typography.bodySmall)
                if (!r.isMe) ProfileSection(lg, r.key, onOpen)
                if (r.key == yacht.engine.league.CustomAis.CLONE) {
                    lg.clone?.let { p ->
                        Text(
                            "혼자 둔 최근 ${p.games}판에서 남기기 ${p.keepDecisions}번 중 ${"%.0f".format(100 * p.keepSlip)}%, " +
                                "칸 고르기 ${p.boxDecisions}번 중 ${"%.0f".format(100 * p.boxSlip)}%를 추천과 다르게 뒀어요. 분신도 그만큼 차선을 둬요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.secondary,
                        )
                    }
                }
                if (!r.isMe) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val fav = r.key in lg.favourites
                        AssistChip(onClick = { lg.toggleFavourite(r.key) }, label = { Text(if (fav) "★ 응원 중" else "☆ 응원하기") })
                        AssistChip(onClick = onCompare, label = { Text("⚖ 비교") })
                        AssistChip(onClick = { onDismiss(); c.newRankedGame(r.key) }, label = { Text("🎮 이 AI와 랭크전") })
                        AssistChip(
                            onClick = {
                                val rival = lg.rivalOf(r.key, java.util.Random())
                                if (rival != null) {
                                    onDismiss()
                                    lg.startWatch(r.key, rival) { c.aiPace.stepMs.coerceAtLeast(400L) }
                                    c.screen = Screen.WATCH
                                }
                            },
                            label = { Text("👀 관전") },
                        )
                        // A league player (generated, evolved or made): remade in 선수 만들기.
                        if (r.origin == yacht.engine.league.Origin.GENERATED || r.origin == yacht.engine.league.Origin.EVOLVED || r.origin == yacht.engine.league.Origin.CREATED) {
                            AssistChip(onClick = { onDismiss(); c.editingPlayer = r.key; c.screen = Screen.MAKER }, label = { Text("✏️ 선수 고치기") })
                        }
                        val mine = lg.customs.firstOrNull { it.key == r.key }
                        if (mine != null) {
                            AssistChip(onClick = { onDismiss(); c.editingAi = mine; c.screen = Screen.BUILDER }, label = { Text("🛠 고치기") })
                        }
                    }
                }
                StatGrid(
                    listOf(
                        "경기" to "${r.games}판",
                        "전적" to "${r.wins}승 ${r.ties}무 ${r.losses}패",
                        "승률" to "%.1f%%".format(100 * r.winRate),
                        "평균 점수" to "%.1f".format(r.avgScore),
                        "최고 점수" to "${r.best}",
                        "요트" to if (r.games > 0) "%.0f%%".format(100.0 * r.yachts / r.games) else "–",
                        "보너스" to if (r.games > 0) "%.0f%%".format(100.0 * r.bonuses / r.games) else "–",
                        "최다 연승" to "${r.bestStreak}",
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 ${r.recent.length}판  ", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    FormDots(r.recent)
                    StreakText(r.streak)
                }
                val history = profile?.ratingHistory.orEmpty()
                if (history.size >= 2) {
                    Text("레이팅 흐름 (최근 ${history.size}경기)", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    RatingLine(history)
                }
                if (best.isNotEmpty()) {
                    Text("😊 잘 잡는 상대", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    for (m in best) MatchupLine(m.name, m.score, m.games)
                }
                if (worst.isNotEmpty()) {
                    Text("😣 천적", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    for (m in worst) MatchupLine(m.name, m.score, m.games)
                }
            }
        },
    )
}

@Composable
private fun StatGrid(cells: List<Pair<String, String>>) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in cells.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, v) in row) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MatchupLine(name: String, score: Double, games: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("%.0f%%".format(100 * score), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text("  ${games}판", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RatingLine(points: List<Int>) {
    val line = gameColors.me
    val grid = MaterialTheme.colorScheme.outlineVariant
    // At least 20 points tall, so a 1-point wobble does not look like a swing.
    val mid = (points.min() + points.max()) / 2
    val lo = minOf(points.min(), mid - 10)
    val hi = maxOf(points.max(), mid + 10)
    Column {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
            val span = maxOf(1, hi - lo).toFloat()
            val step = size.width / (points.size - 1)
            fun y(v: Int) = size.height * (1f - (v - lo) / span) * 0.9f + size.height * 0.05f
            for (i in 1 until points.size) {
                drawLine(line, Offset(step * (i - 1), y(points[i - 1])), Offset(step * i, y(points[i])), strokeWidth = 4f)
            }
        }
        Row {
            Text("$lo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("$hi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

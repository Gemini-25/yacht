package yacht.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.LeagueBackground
import yacht.app.core.LeagueRunMode
import yacht.app.core.LeagueSpeed
import yacht.app.core.LeagueView
import yacht.app.core.SeasonHistoryItem

/** Status, ▶/■, how far ▶ goes, speed, what happens when the app is hidden, and the week ([onSchedule]: the week line tapped). */
@Composable
internal fun ControlCard(c: AppController, v: LeagueView?, onSchedule: () -> Unit) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (lg.running) cs.primaryContainer else cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val prep = lg.preparing
                    Text(
                        when {
                            prep != null -> prep
                            lg.running -> "🔴 리그 진행 중"
                            (v?.matches ?: 0) > 0 -> "⏸ 멈춤"
                            else -> "아직 경기가 없어요"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            lg.running && v != null && lg.runMode == LeagueRunMode.WEEK -> "이번 주만 치르고 멈춰요 · 분당 ${"%.0f".format(lg.pace)}경기 · 지금 ${v.live.size}경기"
                            lg.running && v != null -> "분당 ${"%.0f".format(lg.pace)}경기 · 지금 ${v.live.size}경기 진행 중"
                            lg.background == LeagueBackground.KEEP -> "앱을 나가도 계속 돌아요. 결과는 저장돼요"
                            else -> "앱을 나가면 멈추고, 돌아오면 이어서 해요"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                if (lg.preparing != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                if (lg.running) {
                    Button(onClick = { lg.stop() }, colors = ButtonDefaults.buttonColors(containerColor = cs.error)) { Text("■ 정지") }
                } else {
                    val label = when {
                        lg.runMode == LeagueRunMode.WEEK -> "▶ 1주 진행"
                        (v?.matches ?: 0) > 0 -> "▶ 이어서"
                        else -> "▶ 시작"
                    }
                    Button(onClick = { lg.start() }, enabled = v != null) { Text(label) }
                }
            }
            lg.stopReason?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
            if (!lg.running) lg.weekDone?.let { note ->
                Text("✓ $note", style = MaterialTheme.typography.bodySmall, color = cs.primary)
            }
            // How far ▶ goes: the week in play, or on until ■.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("진행", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                for (m in LeagueRunMode.entries) {
                    FilterChip(selected = lg.runMode == m, onClick = { lg.changeRunMode(m) }, label = { Text(m.label, fontSize = 12.sp) })
                }
                Text(
                    if (lg.runMode == LeagueRunMode.WEEK) "한 주를 치르면 멈춰요" else "■를 누를 때까지 계속 돌아요",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
            v?.clock?.let { ClockLine(it, onSchedule) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in LeagueSpeed.entries) {
                    FilterChip(selected = lg.speed == s, onClick = { lg.changeSpeed(s) }, label = { Text(s.label, fontSize = 12.sp) })
                }
            }
            // What happens when the app is hidden: stop (battery) or keep playing.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("앱을 나가면", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                for (b in LeagueBackground.entries) {
                    FilterChip(selected = lg.background == b, onClick = { c.chooseLeagueBackground(b) }, label = { Text(b.label, fontSize = 12.sp) })
                }
            }
            if (lg.background == LeagueBackground.KEEP) {
                Text(
                    "진행 중에는 알림이 떠 있고, 화면을 꺼도 계속 돌아요(배터리를 많이 써요). 알림의 ‘정지’로 멈출 수 있어요. " +
                        "최근 앱 목록에서 앱을 지우면 멈추고, 다시 열면 이어서 해요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
            if (v != null && v.matches == 0L && lg.hasSeed && !lg.running) {
                OutlinedButton(onClick = { lg.loadSeed() }, modifier = Modifier.fillMaxWidth()) { Text("⚡ 미리 돌린 리그로 시작 (서버에서 둔 12만 경기)") }
            }
        }
    }
}

/** Banner of a season that just ended. */
@Composable
internal fun SeasonNews(h: SeasonHistoryItem, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🏆 시즌 ${h.number} 종료! 우승 ${h.champions.firstOrNull() ?: "–"}", fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer)
                Text(
                    listOfNotNull(
                        h.topScorer?.let { "득점왕 $it" },
                        h.yachtKing?.let { "요트왕 $it" },
                        h.promotedCount.takeIf { it > 0 }?.let { "승격 ${it}명" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onTertiaryContainer,
                )
            }
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    }
}

package yacht.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.LeagueController
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeaguePreset
import yacht.engine.league.MatchFormat

/** 규칙·프리셋: the league's rules, applied from the next season. */
@Composable
fun LeagueRulesDialog(lg: LeagueController, onDismiss: () -> Unit) {
    var cfg by remember { mutableStateOf(lg.config) }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { lg.changeConfig(cfg); onDismiss() }, enabled = cfg != lg.config) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        title = { Text("리그 규칙", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("바꾼 규칙은 다음 시즌부터 적용돼요. 지금 시즌은 그대로 끝까지 가요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text("프리셋", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                for (p in LeaguePreset.entries) {
                    val selected = cfg == p.config
                    Card(
                        Modifier.fillMaxWidth().clickable { cfg = p.config },
                        colors = CardDefaults.cardColors(containerColor = if (selected) cs.primaryContainer else cs.surfaceVariant),
                        border = if (selected) BorderStroke(2.dp, cs.primary) else null,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(p.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(p.desc, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Text("리그", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Stepper("부 개수", cfg.divisions, 1..6, { "${it}부" }) { cfg = cfg.copy(divisions = it) }
                Toggle("홈·원정 두 번씩 (더블 라운드 로빈)", cfg.doubleRoundRobin) { cfg = cfg.copy(doubleRoundRobin = it) }
                Text("경기 방식", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in MatchFormat.entries) {
                        FilterChip(selected = cfg.matchFormat == f, onClick = { cfg = cfg.copy(matchFormat = f) }, label = { Text(f.label, fontSize = 12.sp) })
                    }
                }
                Stepper("자동 승강", cfg.autoSwap, 0..5, { "${it}팀" }) { cfg = cfg.copy(autoSwap = it) }
                Toggle("승강 플레이오프", cfg.promotionPlayoff) { cfg = cfg.copy(promotionPlayoff = it) }
                Toggle("우승 플레이오프 (1부 상위 4팀)", cfg.titlePlayoff) { cfg = cfg.copy(titlePlayoff = it) }
                HorizontalDivider()
                Text("선수", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Stepper("생성 선수", cfg.generated, 0..300, { "${it}명" }, step = 10) { cfg = cfg.copy(generated = it) }
                Stepper("시즌마다 진화 (은퇴·탄생)", cfg.evolution, 0..30, { if (it == 0) "끄기" else "${it}명" }) { cfg = cfg.copy(evolution = it) }
                Toggle("실수하는 AI만", cfg.onlyFlawed) { cfg = cfg.copy(onlyFlawed = it) }
                Stepper("레이팅 상위만", cfg.onlyTop, 0..200, { if (it == 0) "모두" else "${it}명" }, step = 8) { cfg = cfg.copy(onlyTop = it) }
                HorizontalDivider()
                Text("대회", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Toggle("요트컵", cfg.cup) { cfg = cfg.copy(cup = it) }
                Toggle("챔피언스 리그", cfg.champions) { cfg = cfg.copy(champions = it) }
                Toggle("슈퍼컵", cfg.superCup) { cfg = cfg.copy(superCup = it) }
                Toggle("올스타전", cfg.allStar) { cfg = cfg.copy(allStar = it) }
                Toggle("계열 대항전 (3시즌마다)", cfg.familyCup) { cfg = cfg.copy(familyCup = it) }
                Toggle("클럽 리그", cfg.clubs > 0) { cfg = cfg.copy(clubs = if (it) 16 else 0) }
                if (cfg.clubs > 0) {
                    Stepper("클럽 수", cfg.clubs, 4..24, { "${it}개" }, step = 2) { cfg = cfg.copy(clubs = it) }
                    Stepper("클럽당 선수", cfg.squad, 5..8, { "${it}명" }) { cfg = cfg.copy(squad = it) }
                }
                OutlinedButton(onClick = { cfg = LeagueConfig() }, modifier = Modifier.fillMaxWidth()) { Text("기본값으로") }
            }
        },
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, show: (Int) -> String, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
        Text(show(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
        TextButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
    }
}

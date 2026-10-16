@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import yacht.app.core.AppController
import yacht.app.core.Screen
import yacht.app.core.ThinkTime
import yacht.engine.Mode
import yacht.engine.game.DiceSource
import yacht.engine.game.Side

@Composable
fun AppRoot(c: AppController) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val error = c.loadError
        when {
            error != null -> CenterText("계산 표를 불러오지 못했습니다\n$error")
            c.tables == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("계산 표 불러오는 중…")
                }
            }
            else -> when (c.screen) {
                Screen.HOME -> HomeScreen(c)
                Screen.GAME -> if (c.state != null) GameScreen(c) else HomeScreen(c)
                Screen.HISTORY -> HistoryScreen(c)
                Screen.REVIEW -> ReviewScreen(c)
                Screen.VERIFY -> VerifyScreen(c)
            }
        }
    }
}

@Composable
fun CenterText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center)
    }
}

@Composable
fun HomeScreen(c: AppController) {
    var source by remember { mutableStateOf(DiceSource.MANUAL) }
    var seat by remember { mutableStateOf(Side.OPP) }
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp)) { DieFace(5) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("요트 다이스 AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text("1:1 승률 최대화 · 닌텐도 규칙", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }

        val current = c.state
        if (current != null && !current.isOver) {
            Card(
                onClick = { c.screen = Screen.GAME },
                colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("이어하기", fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                        Text(
                            "${if (current.source == DiceSource.MANUAL) "실전" else "AI 대전"} · 내 턴 ${current.myTurnNumber}/12 · " +
                                "${current.me.total} : ${current.opp.total}",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onPrimaryContainer,
                        )
                    }
                    Text("▶", color = cs.onPrimaryContainer, style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        SectionTitle("새 게임")
        ModeCard(
            selected = source == DiceSource.MANUAL,
            title = "실전 도우미",
            desc = "스위치 화면의 주사위와 상대 점수를 입력하면 승률이 가장 높은 수를 알려줍니다.",
            onClick = { source = DiceSource.MANUAL },
        )
        ModeCard(
            selected = source == DiceSource.RANDOM,
            title = "AI와 대전",
            desc = "앱이 주사위를 굴립니다. 승률 최대화 AI와 1:1 연습, 힌트 켜고 끄기 가능.",
            onClick = { source = DiceSource.RANDOM },
        )
        Text("순서", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = seat == Side.ME,
                onClick = { seat = Side.ME },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("내가 선공") }
            SegmentedButton(
                selected = seat == Side.OPP,
                onClick = { seat = Side.OPP },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("내가 후공 (추천)") }
        }
        Text(
            "고를 수 있으면 후공: 마지막 턴에 상대 최종 점수를 보고 둘 수 있어 정보상 유리합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Button(onClick = { c.newGame(source, seat) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("게임 시작", style = MaterialTheme.typography.titleMedium)
        }

        SectionTitle("AI 설정")
        Text("목표", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = c.objective == Mode.WIN, onClick = { c.changeObjective(Mode.WIN) }, label = { Text("승률 최대") })
            FilterChip(
                selected = c.objective == Mode.EXPECTED_SCORE,
                onClick = { c.changeObjective(Mode.EXPECTED_SCORE) },
                label = { Text("기대점수 최대") },
            )
        }
        Text("한 턴 계산 시간 (첫 추천은 즉시, 이후 더 깊게)", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in ThinkTime.entries) {
                FilterChip(selected = c.thinkTime == t, onClick = { c.changeThinkTime(t) }, label = { Text(t.label) })
            }
        }

        SectionTitle("기록과 검증")
        MenuCard("기록 · 통계 · 복기", "실전 승률(신뢰구간), 판별 실수와 승률 그래프") { c.openHistory() }
        MenuCard("승률 검증", "같은 주사위로 승률 AI와 기대점수 AI를 겨뤄 차이를 직접 측정") { c.screen = Screen.VERIFY }

        Text(
            "규칙: 상단 63점 이상 보너스 35 · 초이스/포카드/풀하우스 = 주사위 5개 합 (요트도 풀하우스 인정) · " +
                "S.스트레이트 15 · L.스트레이트 30 · 요트 50",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeCard(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) cs.primaryContainer.copy(alpha = 0.35f) else cs.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text((if (selected) "● " else "○ ") + title, fontWeight = FontWeight.Bold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun MenuCard(title: String, desc: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

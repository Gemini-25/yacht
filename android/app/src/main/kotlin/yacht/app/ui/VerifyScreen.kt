@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import yacht.app.core.AppController
import yacht.app.core.Screen

@Composable
fun VerifyScreen(c: AppController) {
    val cs = MaterialTheme.colorScheme
    val v = c.verify
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("승률 검증", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { c.screen = Screen.HOME }) { Text("홈") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "같은 주사위 흐름으로 \"승률 AI\"와 \"기대점수 AI\"를 각각 같은 상대(기대점수 최적 AI)와 붙입니다. " +
                    "선공·후공 둘 다 둡니다. 운이 같으니 두 AI의 승률 차이가 곧 전략의 차이입니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(100, 300, 1000)) {
                    Button(onClick = { c.startVerify(n) }, enabled = !c.verifying, modifier = Modifier.weight(1f)) { Text("${n}판") }
                }
            }
            if (c.verifying) {
                OutlinedButton(onClick = { c.stopVerify() }, modifier = Modifier.fillMaxWidth()) { Text("중지") }
            }
            if (v.target > 0) {
                LinearProgressIndicator(progress = { v.games.toFloat() / v.target }, modifier = Modifier.fillMaxWidth())
                Text("${v.games} / ${v.target}판 (판마다 선공·후공 2게임)", style = MaterialTheme.typography.bodySmall)
            }
            if (v.games > 0) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ResultRow("승률 AI", v.winRateAi, v.scoreAi.toDouble() / (2 * v.games))
                        ResultRow("기대점수 AI", v.winRateEv, v.scoreEv.toDouble() / (2 * v.games))
                        Text(
                            "차이 ${fmtDelta(yacht.engine.Mode.WIN, v.diff)} (95% 범위 ±${"%.1f".format(v.diffCi * 100)}%p)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (v.diff - v.diffCi > 0) gameColors.me else cs.onSurface,
                        )
                        Text(
                            when {
                                v.games < 50 -> "판 수가 더 쌓여야 판단할 수 있습니다."
                                v.diff - v.diffCi > 0 -> "승률 AI가 통계적으로 유의하게 더 많이 이깁니다."
                                v.diff + v.diffCi < 0 -> "기대점수 AI가 더 많이 이겼습니다 (예상 밖 결과)."
                                else -> "아직 차이가 오차 범위 안입니다. 판 수를 늘려 보세요."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "이 기기에서 승률 AI의 한 턴 계산: 평균 %.0f ms (검증용 계산량)".format(v.avgPlanMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "참고: 상대가 사람이면 기대점수 최적보다 약하므로 실제 승률은 이보다 높게 나옵니다. " +
                    "실전 기록 화면의 승률과 'AI 예측 검증' 표로 실제 결과도 확인할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultRow(name: String, rate: Double, mean: Double) {
    Row {
        Text(name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text("승률 ${pct(rate)}", fontWeight = FontWeight.Bold)
        Text("  평균 %.1f점".format(mean), style = MaterialTheme.typography.bodySmall)
    }
}

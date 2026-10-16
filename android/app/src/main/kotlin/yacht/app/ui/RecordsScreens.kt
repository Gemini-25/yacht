@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import yacht.app.core.AppController
import yacht.app.core.Screen
import yacht.engine.Mode
import yacht.engine.game.DecisionRecord
import yacht.engine.game.DiceSource
import yacht.engine.game.GameState
import yacht.engine.game.Side
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

private val DATE = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)

private fun oppName(g: GameState) = if (g.source == DiceSource.RANDOM) "AI" else "상대"

/** 95% Wilson interval for k successes out of n. */
fun wilson(k: Int, n: Int): Pair<Double, Double> {
    if (n == 0) return 0.0 to 1.0
    val z = 1.96
    val p = k.toDouble() / n
    val d = 1 + z * z / n
    val center = (p + z * z / (2 * n)) / d
    val half = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / d
    return (center - half).coerceAtLeast(0.0) to (center + half).coerceAtMost(1.0)
}

/** My P(win) at the first decision of each turn (WIN-mode decisions only). */
fun winTrace(g: GameState): List<Double> =
    g.decisions.filter { it.mode == Mode.WIN }.groupBy { it.turn }.toSortedMap().values.map { it.first().bestValue }

@Composable
fun HistoryScreen(c: AppController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("기록 · 통계", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { c.screen = Screen.HOME }) { Text("홈") } },
            )
        },
    ) { pad ->
        val games = c.history.filter { it.isOver }
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { StatsCard("실전 (스위치)", games.filter { it.source == DiceSource.MANUAL }) }
            item { StatsCard("앱에서 AI와 대전", games.filter { it.source == DiceSource.RANDOM }) }
            item { CalibrationCard(games) }
            if (games.isEmpty()) {
                item { Text("끝난 게임이 아직 없습니다.", modifier = Modifier.padding(12.dp)) }
            }
            items(games) { g -> GameRow(c, g) }
        }
    }
}

@Composable
private fun StatsCard(title: String, games: List<GameState>) {
    val cs = MaterialTheme.colorScheme
    val n = games.size
    val wins = games.count { it.me.total > it.opp.total }
    val ties = games.count { it.me.total == it.opp.total }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (n == 0) {
                Text("아직 기록 없음", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                return@Column
            }
            val (lo, hi) = wilson(wins, n)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(pct(wins.toDouble() / n), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = gameColors.me)
                Text(
                    "  ${n}판 ${wins}승 ${ties}무 ${n - wins - ties}패",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Text(
                "95% 신뢰구간 ${pct(lo)} ~ ${pct(hi)} · 평균 ${"%.1f".format(games.map { it.me.total }.average())} : " +
                    "%.1f".format(games.map { it.opp.total }.average()),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (n < 30) {
                Text(
                    "판 수가 적으면 운의 영향이 큽니다. 30판 이상부터 비교해 보세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** Does the AI's predicted win probability match reality? Groups games by the mid-game prediction. */
@Composable
private fun CalibrationCard(games: List<GameState>) {
    val cs = MaterialTheme.colorScheme
    val points = games.mapNotNull { g ->
        val trace = winTrace(g)
        if (trace.size < 7) null else trace[6] to (g.me.total > g.opp.total)
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AI 예측 검증 (7턴째 예측 승률 vs 실제)", fontWeight = FontWeight.Bold)
            if (points.size < 5) {
                Text(
                    "게임이 5판 이상 쌓이면 표시됩니다. 예측 승률과 실제 승률이 비슷하면 AI의 확률 계산이 맞다는 뜻입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                return@Column
            }
            Row {
                for (h in listOf("예측 구간", "판 수", "평균 예측", "실제")) {
                    Text(h, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
            for (b in 0 until 5) {
                val lo = b * 0.2
                val bucket = points.filter { it.first >= lo && (it.first < lo + 0.2 || b == 4) }
                if (bucket.isEmpty()) continue
                Row {
                    Text("${(lo * 100).toInt()}~${((lo + 0.2) * 100).toInt()}%", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Text("${bucket.size}", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Text(pct(bucket.map { it.first }.average()), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Text(pct(bucket.count { it.second }.toDouble() / bucket.size), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GameRow(c: AppController, g: GameState) {
    val cs = MaterialTheme.colorScheme
    val result = when {
        g.me.total > g.opp.total -> "승"
        g.me.total == g.opp.total -> "무"
        else -> "패"
    }
    Card(
        onClick = { c.openReview(g) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                result,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = if (result == "승") gameColors.me else if (result == "패") cs.error else cs.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("나 ${g.me.total} : ${g.opp.total} ${oppName(g)}", fontWeight = FontWeight.Bold)
                Text(
                    "${DATE.format(Date(g.createdAt))} · ${if (g.source == DiceSource.MANUAL) "실전" else "AI 대전"} · " +
                        (if (g.first == Side.ME) "선공" else "후공") + " · 추천과 다른 수 ${g.decisions.count { it.isMistake }}번",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
fun ReviewScreen(c: AppController) {
    val g = c.reviewing
    if (g == null) {
        HistoryScreen(c)
        return
    }
    val cs = MaterialTheme.colorScheme
    val mistakes = g.decisions.filter { it.isMistake }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("복기", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { c.openHistory() }) { Text("목록") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("나 ${g.me.total} : ${g.opp.total} ${oppName(g)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("판단 ${g.decisions.size}번 중 추천과 다른 수 ${mistakes.size}번", style = MaterialTheme.typography.bodyMedium)
                        val winLoss = mistakes.filter { it.mode == Mode.WIN }.sumOf { it.loss }
                        val evLoss = g.decisions.sumOf { it.expectedLoss }
                        Text(
                            "승률 손실 합계 %.1f%%p · 기대점수 손실 합계 %.1f점".format(winLoss * 100, evLoss),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                        val trace = winTrace(g)
                        if (trace.size >= 2) {
                            SectionTitle("턴별 내 승률 (첫 굴림 직후)")
                            ProbabilityChart(trace)
                            Text("가운데 선 = 50%", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            if (mistakes.isNotEmpty()) {
                item { SectionTitle("가장 큰 실수") }
                items(mistakes.sortedByDescending { it.loss }.take(3)) { DecisionCard(it) }
            }
            item { SectionTitle("전체 판단") }
            items(g.decisions) { DecisionCard(it) }
            if (g.oppLog.isNotEmpty()) {
                item { SectionTitle("AI 기록") }
                items(g.oppLog) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun DecisionCard(d: DecisionRecord) {
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (d.isMistake) cs.errorContainer else cs.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("턴 ${d.turn} · ${d.rollsDone}번째  ", style = MaterialTheme.typography.labelMedium)
                MiniDice(d.dice, 16.dp)
                Text("  ${fmtValue(d.mode, d.bestValue)}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            Text("선택: ${d.chosen}", fontWeight = FontWeight.SemiBold)
            if (d.isMistake) {
                HorizontalDivider(color = cs.error.copy(alpha = 0.3f))
                Text("추천: ${d.best}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "손실 ${fmtDelta(d.mode, -d.loss)} · 기대점수 ${"%+.1f".format(-d.expectedLoss)}점",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                )
            }
        }
    }
}

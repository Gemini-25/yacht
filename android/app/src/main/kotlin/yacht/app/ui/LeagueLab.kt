package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.LeagueController
import yacht.engine.league.LabResult
import yacht.engine.league.LabSession
import yacht.engine.league.LabStats
import yacht.engine.league.MatchPreview

/**
 * AI 연구소: any two AIs (legends too) play hundreds or thousands of mirrored matches alongside
 * the league; the result is a win share and rating difference with 95% ranges.
 */
@Composable
fun LabDialog(lg: LeagueController, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var a by remember { mutableStateOf<String?>(null) }
    var b by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf(1000) }
    val (current, history) = lg.lab
    val rows = lg.view?.rows.orEmpty().filter { !it.isMe }
    val legends = remember { lg.legends() }
    val choices = rows.map { it.key to it.name } + legends.map { (k, n) -> k to "🏛 $n" }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text("🧪 AI 연구소", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "두 AI를 같은 주사위 두 판 경기로 수백~수천 번 붙여서 누가 얼마나 강한지 95% 범위로 알려줘요. 리그와 함께 돌고, 리그 기록에는 들어가지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                if (current != null && !current.done) {
                    LabCard(current, running = true)
                    Button(onClick = { lg.stopLab() }, colors = ButtonDefaults.buttonColors(containerColor = cs.error), modifier = Modifier.fillMaxWidth()) { Text("■ 실험 멈추기") }
                } else {
                    Picker("A", choices, a) { a = it }
                    Picker("B", choices, b) { b = it }
                    Text("경기 수", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in LabSession.TARGETS) {
                            FilterChip(selected = target == t, onClick = { target = t }, label = { Text(if (t == LabSession.UNTIL_PRECISE) "±1%p까지" else "${t}경기", fontSize = 12.sp) })
                        }
                    }
                    val x = a
                    val y = b
                    Button(onClick = { if (x != null && y != null) lg.startLab(x, y, target) }, enabled = x != null && y != null && x != y, modifier = Modifier.fillMaxWidth()) { Text("▶ 실험 시작") }
                }
                if (history.isNotEmpty()) {
                    HorizontalDivider()
                    Text("지난 실험", fontWeight = FontWeight.Bold)
                    for (r in history) LabCard(r, running = false)
                }
            }
        },
    )
}

@Composable
private fun Picker(label: String, choices: List<Pair<String, String>>, picked: String?, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val name = choices.firstOrNull { it.first == picked }?.second
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: ${name ?: "고르세요"}", fontWeight = FontWeight.Bold, color = if (name == null) cs.onSurfaceVariant else cs.primary)
        OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("이름 검색", fontSize = 13.sp) }, singleLine = true, textStyle = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        val shown = choices.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }.take(if (query.isBlank()) 8 else 20)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((k, n) in shown) FilterChip(selected = k == picked, onClick = { onPick(k) }, label = { Text(n, fontSize = 11.sp, maxLines = 1) })
        }
    }
}

@Composable
private fun LabCard(r: LabResult, running: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (running) cs.secondaryContainer else cs.surfaceVariant).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("${r.nameA} 대 ${r.nameB}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val goal = if (r.target == LabSession.UNTIL_PRECISE) "±1%p까지" else "${r.target}경기"
        Text(
            "${"%,d".format(r.matches)}경기 (${"%,d".format(r.games)}판) / $goal" + when {
                r.stopped -> " · 멈춤"
                r.done -> " · 끝"
                else -> " · 진행 중"
            },
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
        if (running && r.target != LabSession.UNTIL_PRECISE) LinearProgressIndicator(progress = { (r.matches.toFloat() / r.target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        if (r.matches == 0) return@Column
        Text(
            "${r.nameA}의 승점 비율 ${"%.1f".format(100 * r.share)}% (95% 범위 ${"%.1f".format(100 * r.shareLow)}~${"%.1f".format(100 * r.shareHigh)}%)",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
        )
        Text("판 전적 ${r.gameWins}승 ${r.gameDraws}무 ${r.gameLosses}패 · 경기 ${r.matchWins}승 ${r.matchDraws}무 ${r.matchLosses}패", style = MaterialTheme.typography.labelSmall)
        Text(
            "레이팅 차이 ${"%+.0f".format(r.ratingDiff)} (${"%+.0f".format(r.ratingLow)} ~ ${"%+.0f".format(r.ratingHigh)})" +
                " · ${yacht.engine.explain.Ko.i(r.nameA)} 더 강할 확률 ${"%.0f".format(100 * r.pStronger)}%",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "평균 점수 ${"%.1f".format(r.avgA)} 대 ${"%.1f".format(r.avgB)} (판당 ${"%+.1f".format(r.scoreDiff)}" + (if (r.scoreDiffCi.isNaN()) "" else " ±${"%.1f".format(r.scoreDiffCi)}") + ")",
            style = MaterialTheme.typography.labelSmall,
        )
        DiffHistogram(r.diffHistogram)
    }
}

/** Per-game score differences (A − B), bins of 20 points from −200 to +200. */
@Composable
private fun DiffHistogram(bins: List<Int>) {
    val max = bins.maxOrNull()?.takeIf { it > 0 } ?: return
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            for ((i, n) in bins.withIndex()) {
                val center = LabStats.diffBinCenter(i)
                Box(
                    Modifier.weight(1f).fillMaxHeight(n.toFloat() / max).heightIn(min = 1.dp)
                        .background(if (center > 0) gameColors.me else if (center < 0) gameColors.opp else cs.outline),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("B가 크게 이김", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("A가 크게 이김", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 경기 프리뷰: prediction, head-to-head, form, boxes and what is at stake. */
@Composable
fun PreviewDialog(lg: LeagueController, p: MatchPreview) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { lg.closePreview() },
        confirmButton = { TextButton(onClick = { lg.closePreview() }) { Text("닫기") } },
        title = { Text("🔮 ${lg.name(p.a)} 대 ${lg.name(p.b)}", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.winA?.let { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${"%.0f".format(100 * w)}%", fontWeight = FontWeight.ExtraBold, color = gameColors.me)
                        Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(gameColors.opp)) {
                            Box(Modifier.fillMaxWidth(w.toFloat()).height(10.dp).background(gameColors.me))
                        }
                        Text("${"%.0f".format(100 * (1 - w))}%", fontWeight = FontWeight.ExtraBold, color = gameColors.opp)
                    }
                }
                Row {
                    FormDots(p.formA)
                    Box(Modifier.weight(1f))
                    FormDots(p.formB)
                }
                for (line in p.lines) Text(line, style = MaterialTheme.typography.bodySmall)
                if (p.stakes.isNotEmpty()) {
                    Text("🔥 걸린 것", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    for (line in p.stakes) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.secondary)
                }
            }
        },
    )
}

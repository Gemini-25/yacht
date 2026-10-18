@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
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
        val all = remember(games) { groups(games) }
        var picked by remember { mutableStateOf<String?>(null) }
        val group = all.firstOrNull { it.label == picked }
        val shown = group?.games ?: games
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { GroupsCard(all, picked) { picked = if (picked == it) null else it } }
            item { StatsCard(group?.label ?: "전체", shown) }
            item { HabitsCard(c) }
            item { CalibrationCard(games) }
            if (games.isEmpty()) {
                item { Text("끝난 게임이 아직 없습니다.", modifier = Modifier.padding(12.dp)) }
            }
            items(shown) { g -> GameRow(c, g) }
        }
    }
}

/** Finished games of one kind: real or vs AI, with recommendations or alone; or against one opponent. */
private class Group(val label: String, val games: List<GameState>, val tag: Boolean = false)

private fun groups(games: List<GameState>): List<Group> {
    fun of(source: DiceSource, assisted: Boolean) = games.filter { it.source == source && it.style.assisted == assisted }
    val kinds = listOf(
        Group("실전 · AI 추천", of(DiceSource.MANUAL, true)),
        Group("실전 · 혼자", of(DiceSource.MANUAL, false)),
        Group("AI 대전 · AI 추천", of(DiceSource.RANDOM, true)),
        Group("AI 대전 · 혼자", of(DiceSource.RANDOM, false)),
    )
    val tags = games.mapNotNull { it.opponent }.distinct().map { t -> Group("vs $t", games.filter { it.opponent == t }, tag = true) }
    return kinds + tags.sortedByDescending { it.games.size }
}

private fun result(g: GameState) = when {
    g.me.total > g.opp.total -> "승"
    g.me.total == g.opp.total -> "무"
    else -> "패"
}

/** Win rate of each kind of game and of each tagged opponent; a row filters the list below. */
@Composable
private fun GroupsCard(groups: List<Group>, picked: String?, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("전적 (줄을 누르면 그 판만 보여요)", fontWeight = FontWeight.Bold)
            Row(Modifier.padding(vertical = 2.dp)) {
                for ((h, w) in listOf("구분" to 2.2f, "판" to 0.8f, "승률" to 1.1f, "승-무-패" to 1.4f)) {
                    Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            for ((i, g) in groups.withIndex()) {
                if (g.tag && (i == 0 || !groups[i - 1].tag)) {
                    HorizontalDivider(Modifier.padding(vertical = 3.dp), color = cs.outlineVariant)
                    Text("상대별", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                val n = g.games.size
                val w = g.games.count { result(it) == "승" }
                val t = g.games.count { result(it) == "무" }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (picked == g.label) cs.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = n > 0) { onPick(g.label) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.label, Modifier.weight(2.2f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("$n", Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                    Text(if (n == 0) "-" else pct(w.toDouble() / n), Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = gameColors.me)
                    Text(if (n == 0) "-" else "$w-$t-${n - w - t}", Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
                }
            }
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

/** Patterns in my recent mistakes, with a way to practise them again. */
@Composable
private fun HabitsCard(c: AppController) {
    val r = c.habits ?: return
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("내 습관 분석", fontWeight = FontWeight.Bold)
            Text(r.summary, style = MaterialTheme.typography.bodyMedium)
            if (r.details.isNotEmpty()) Bullets(r.details, cs.onSurfaceVariant)
            for (h in r.habits.take(4)) HabitRow(c, h)
            val replayable = r.habits.flatMap { it.examples }.distinct().filter { it.me != null && it.opp != null }
            if (replayable.isNotEmpty()) {
                Button(onClick = { c.openQuiz(replayable) }, modifier = Modifier.fillMaxWidth()) {
                    Text("내 실수 다시 풀기 (${replayable.size}문제)")
                }
            }
        }
    }
}

@Composable
private fun HabitRow(c: AppController, h: yacht.engine.explain.Habit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant.copy(alpha = 0.6f))
            .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${h.count}번 · -${"%.1f".format(100 * h.loss)}%p", style = MaterialTheme.typography.labelMedium, color = cs.error)
        }
        if (open) {
            Text(h.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            val replayable = h.examples.filter { it.me != null && it.opp != null }
            if (replayable.isNotEmpty()) {
                TextButton(onClick = { c.openQuiz(replayable) }) { Text("이 유형만 다시 풀기 (${replayable.size}문제)") }
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
    val result = result(g)
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
                        (if (g.style.assisted) "AI 추천" else "혼자") + (g.opponent?.let { " · vs $it" } ?: "") + " · " +
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
                        Text(
                            (if (g.source == DiceSource.MANUAL) "실전" else "AI 대전") + " · " +
                                (if (g.style.assisted) "AI 추천 받으며 둔 판" else "혼자 둔 판") + (g.opponent?.let { " · vs $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Text("판단 ${g.decisions.size}번 중 추천과 다른 수 ${mistakes.size}번", style = MaterialTheme.typography.bodyMedium)
                        val winLoss = mistakes.filter { it.mode == Mode.WIN }.sumOf { it.loss }
                        val evLoss = g.decisions.sumOf { it.expectedLoss }
                        Text(
                            "승률 손실 합계 %.1f%%p · 기대점수 손실 합계 %.1f점".format(winLoss * 100, evLoss),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                        c.gameSummary(g)?.details?.takeIf { it.isNotEmpty() }?.let { Bullets(it, cs.onSurface) }
                        val trace = winTrace(g)
                        if (trace.size >= 2) {
                            SectionTitle("턴별 내 승률 (첫 굴림 직후)")
                            ProbabilityChart(trace)
                            Text("가운데 선 = 50%", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            c.luck(g)?.let { r -> item { LuckCard(g, r) } }
            if (mistakes.isNotEmpty()) {
                item { SectionTitle("가장 큰 실수 (누르면 ‘추천대로 뒀다면?’)") }
                items(mistakes.sortedByDescending { it.loss }.take(3)) { DecisionCard(c, it) }
            }
            item { SectionTitle("전체 판단") }
            items(g.decisions) { DecisionCard(c, it) }
            if (g.oppLog.isNotEmpty()) {
                item { SectionTitle("AI 기록") }
                items(g.oppLog) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Luck of both sides: in points, as a share of my P(win), turn by turn, and the biggest swings. */
@Composable
private fun LuckCard(g: GameState, r: yacht.engine.explain.LuckReport) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("운 분석", fontWeight = FontWeight.Bold)
            Text(r.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Bullets(r.details, cs.onSurfaceVariant)
            if (r.rounds.count { it.me != null || it.opp != null } >= 2) {
                SectionTitle("턴별 누적 운 (기대 점수 대비)")
                LuckChart(r.rounds)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("● 나", style = MaterialTheme.typography.labelSmall, color = gameColors.me)
                    Text("● ${oppName(g)}" + if (r.oppWithSkill) " (운+실력)" else "", style = MaterialTheme.typography.labelSmall, color = gameColors.opp)
                    Text("가운데 선 = 기대만큼", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            if (r.moments.isNotEmpty()) {
                SectionTitle("결정적인 순간 (내 승률 변화)")
                for (m in r.moments) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            fmtDelta(Mode.WIN, m.delta),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (m.delta >= 0) gameColors.good else cs.error,
                        )
                    }
                }
            }
        }
    }
}

/** Cumulative luck (points) after each round for both sides, around a zero line. */
@Composable
private fun LuckChart(rounds: List<yacht.engine.explain.RoundLuck>) {
    val me = gameColors.me
    val opp = gameColors.opp
    val grid = MaterialTheme.colorScheme.outlineVariant
    val top = maxOf(10.0, rounds.maxOf { maxOf(kotlin.math.abs(it.me ?: 0.0), kotlin.math.abs(it.opp ?: 0.0)) })
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val mid = size.height / 2
        drawLine(grid, androidx.compose.ui.geometry.Offset(0f, mid), androidx.compose.ui.geometry.Offset(size.width, mid), strokeWidth = 2f)
        val step = size.width / maxOf(1, rounds.size)
        fun y(v: Double) = (mid - (v / top) * (size.height / 2 - 6)).toFloat()
        val lines: List<Pair<(yacht.engine.explain.RoundLuck) -> Double?, androidx.compose.ui.graphics.Color>> =
            listOf(Pair({ r: yacht.engine.explain.RoundLuck -> r.me }, me), Pair({ r: yacht.engine.explain.RoundLuck -> r.opp }, opp))
        for ((pick, color) in lines) {
            var prev = androidx.compose.ui.geometry.Offset(0f, mid)
            for ((i, r) in rounds.withIndex()) {
                val v = pick(r) ?: break
                val p = androidx.compose.ui.geometry.Offset(step * (i + 1), y(v))
                drawLine(color, prev, p, strokeWidth = 4f)
                drawCircle(color, 5f, p)
                prev = p
            }
        }
    }
}

@Composable
private fun DecisionCard(c: AppController, d: DecisionRecord) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(d in c.whatIfs) }
    val canAsk = d.isMistake && d.me != null && d.opp != null
    Card(
        Modifier.fillMaxWidth().then(if (canAsk) Modifier.clickable { open = !open; if (open) c.loadWhatIf(d) } else Modifier),
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
                d.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer) }
                if (canAsk) {
                    Text(
                        if (open) "▴ 접기" else "▾ 추천대로 뒀다면?",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.onErrorContainer,
                    )
                }
                if (open) {
                    val w = c.whatIfs[d]
                    when {
                        w != null -> WhatIfView(w)
                        d in c.whatIfBusy -> Text("계산 중…", style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                        else -> Text("이 판단은 다시 계산할 수 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                    }
                }
            }
        }
    }
}

/** The recommended move and the one played, each with how the turn would end. */
@Composable
private fun WhatIfView(w: yacht.engine.explain.WhatIf) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surface.copy(alpha = 0.8f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(w.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        for ((title, side, color) in listOf(Triple("추천", w.best, gameColors.me), Triple("내 선택", w.chosen, cs.error))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$title · ${side.move}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
                Text(fmtValue(w.mode, side.value), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            if (side.outcomes.size > 1) {
                for (o in side.outcomes) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.foundation.Canvas(Modifier.width(70.dp).height(8.dp).clip(RoundedCornerShape(50))) {
                            drawRect(color.copy(alpha = 0.15f))
                            drawRect(color, size = size.copy(width = size.width * o.prob.toFloat()))
                        }
                        Text(pct(o.prob), Modifier.width(52.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (w.details.isNotEmpty()) Bullets(w.details, cs.onSurfaceVariant)
    }
}

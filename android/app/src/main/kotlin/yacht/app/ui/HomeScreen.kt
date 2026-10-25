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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import yacht.engine.game.PlayStyle
import yacht.engine.game.Side

@Composable
fun AppRoot(c: AppController) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val error = c.loadError
        when {
            error != null -> CenterText("계산 표를 불러오지 못했어요\n$error")
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
                Screen.QUIZ -> QuizScreen(c)
                Screen.LEAGUE -> LeagueScreen(c)
                Screen.WATCH -> WatchScreen(c)
                Screen.BUILDER -> BuilderScreen(c)
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
    var opponent by remember { mutableStateOf("") }
    var rankedAsk by remember { mutableStateOf(false) }
    var backupNote by remember { mutableStateOf<String?>(null) }
    val files = LocalFilePort.current
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

        c.lastCrash?.let { CrashCard(it, onDismiss = { c.dismissCrash() }) }

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
                            (if (current.ranked) "🎮 랭크전 vs ${current.opponent} · " else "") +
                                "${if (current.source == DiceSource.MANUAL) "실전" else "AI 대전"} · ${if (current.style.assisted) "AI 추천" else "혼자"} · " +
                                "내 턴 ${current.myTurnNumber}/12 · ${current.me.total} : ${current.opp.total}",
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
            desc = "스위치 화면의 주사위와 상대 점수를 입력하면, 승률이 가장 높은 수를 알려 줘요.",
            onClick = { source = DiceSource.MANUAL },
        )
        ModeCard(
            selected = source == DiceSource.RANDOM,
            title = "AI와 대전",
            desc = "앱이 주사위를 굴려요. 승률을 최대로 두는 AI와 1:1로 연습해요.",
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
            "고를 수 있다면 후공이 좋아요. 마지막 턴에 상대 최종 점수를 보고 둘 수 있거든요.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Text("진행 방식", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = c.nextStyle == PlayStyle.ASSISTED,
                onClick = { c.changeStyle(PlayStyle.ASSISTED) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("AI 추천 받기") }
            SegmentedButton(
                selected = c.nextStyle == PlayStyle.SOLO,
                onClick = { c.changeStyle(PlayStyle.SOLO) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("혼자 두고 복기") }
        }
        Text(
            if (c.nextStyle == PlayStyle.SOLO) "게임 중에는 AI가 아무것도 알려 주지 않고, 끝나면 바로 복기할 수 있어요. 통계도 따로 모아요."
            else "매 판단마다 승률이 가장 높은 수와 이유를 알려 줘요.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        if (source == DiceSource.MANUAL) {
            Text("상대 (선택)", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            OutlinedTextField(
                value = opponent,
                onValueChange = { opponent = it.take(20) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("예: 친구 A, 스위치 CPU, 온라인") },
            )
            if (c.tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in c.tags) {
                        FilterChip(selected = opponent.trim() == t, onClick = { opponent = if (opponent.trim() == t) "" else t }, label = { Text(t) })
                    }
                }
            }
        }
        Button(onClick = { c.newGame(source, seat, opponent) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
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

        SectionTitle("AI 리그")
        MenuCard(
            "🎮 랭크전 · ${myStanding(myRow(c))}",
            "리그 AI와 혼자 두고 내 레이팅과 티어 매기기 · AI들과 같은 눈금이라 몇 위쯤인지 바로 보여요",
        ) { rankedAsk = true }
        MenuCard(
            "🏆 AI 리그 · ${c.league.size}개 AI 솔랭",
            "승률 AI부터 실수하는 AI, 단순 규칙까지 계속 붙여서 실시간 순위표로 보기 · 티어, 레이팅, 승률, 연승",
        ) { c.screen = Screen.LEAGUE }

        SectionTitle("기록과 검증")
        MenuCard("기록 · 통계 · 복기", "추천/혼자·상대별 승률, 운 분석, 내 습관, 판별 실수와 승률 그래프") { c.openHistory() }
        val qs = c.quizStats
        MenuCard(
            "연습 퀴즈",
            "헷갈리기 쉬운 국면을 풀고 해설 보기" + if (qs.answered > 0) " · 정답 ${qs.correct}/${qs.answered}" else "",
        ) { c.openQuiz() }
        MenuCard("승률 검증", "같은 주사위로 승률 AI와 기대점수 AI를 겨뤄 차이를 직접 측정") { c.screen = Screen.VERIFY }

        SectionTitle("기록 백업")
        Text(
            "게임 기록, 설정, 리그(순위·시즌·내가 만든 AI)를 파일 하나로 저장하고, 다른 폰이나 다른 앱(예전 ‘요트 AI’ ↔ ‘요트 AI 2’)에서 불러올 수 있어요.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val f = files ?: return@OutlinedButton
                backupNote = "백업 파일을 만드는 중…"
                c.backup { name, bytes ->
                    backupNote = null
                    f.save(name, bytes) { ok -> backupNote = if (ok) "백업 파일을 저장했어요." else "저장하지 못했어요." }
                }
            }, enabled = files != null, modifier = Modifier.weight(1f)) { Text("💾 백업 저장") }
            OutlinedButton(onClick = {
                val f = files ?: return@OutlinedButton
                f.open { bytes, problem ->
                    when {
                        problem != null -> backupNote = problem
                        bytes != null -> {
                            backupNote = "불러오는 중…"
                            c.restore(bytes) { backupNote = it }
                        }
                    }
                }
            }, enabled = files != null, modifier = Modifier.weight(1f)) { Text("📂 불러오기") }
        }
        backupNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.secondary) }

        Text(
            "규칙: 상단 63점 이상 보너스 35 · 초이스/포카드/풀하우스 = 주사위 5개 합 (요트도 풀하우스 인정) · " +
                "S.스트레이트 15 · L.스트레이트 30 · 요트 50",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
    if (rankedAsk) RankedStartDialog(c) { rankedAsk = false }
}

/** Why the app closed last time, to copy and send. */
@Composable
private fun CrashCard(text: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.errorContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚠️ 지난번에 앱이 갑자기 꺼졌어요", fontWeight = FontWeight.Bold, color = cs.onErrorContainer)
            Text(
                "원인 기록을 복사해서 보내 주면 고칠 수 있어요. 진행 중이던 게임과 리그 기록은 저장돼 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onErrorContainer,
            )
            Text(
                if (open) text else text.lines().take(4).joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = cs.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    copied = true
                }) { Text(if (copied) "복사됨" else "복사") }
                OutlinedButton(onClick = { open = !open }) { Text(if (open) "접기" else "자세히") }
                OutlinedButton(onClick = onDismiss) { Text("닫기") }
            }
        }
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

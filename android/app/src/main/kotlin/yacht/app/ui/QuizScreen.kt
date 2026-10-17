@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import yacht.app.core.AppController
import yacht.engine.explain.QuizQuestion
import yacht.engine.game.DiceSource
import yacht.engine.game.GameState
import yacht.engine.game.Side
import yacht.engine.game.TurnDraft

/** Practice: decide a position, then see how the choice compares with the best move and why. */
@Composable
fun QuizScreen(c: AppController) {
    val cs = MaterialTheme.colorScheme
    val replay = c.quizReplayLeft != null
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                title = {
                    Column {
                        Text(if (replay) "내 실수 다시 풀기" else "연습 퀴즈", fontWeight = FontWeight.Bold)
                        val st = c.quizStats
                        Text(
                            if (st.answered == 0) "헷갈리기 쉬운 국면만 골라 내요"
                            else "정답 ${st.correct}/${st.answered} · 한 문제 평균 손해 ${"%.1f".format(100 * st.lossSum / st.answered)}%p",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = { c.closeQuiz() }) { Text("홈") } },
            )
        },
    ) { pad ->
        val q = c.quiz
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                q == null && c.quizLoading -> CenterText("문제를 고르는 중…", Modifier.weight(1f))
                q == null -> QuizFinished(c, Modifier.weight(1f))
                else -> QuizBody(c, q)
            }
        }
    }
}

@Composable
private fun QuizFinished(c: AppController, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (c.quizReplayLeft != null) "다시 풀 문제를 모두 풀었어요." else "문제를 만들지 못했어요. 다시 시도해 주세요.",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = { c.openQuiz() }) { Text("새 문제 풀기") }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.QuizBody(c: AppController, q: QuizQuestion) {
    val cs = MaterialTheme.colorScheme
    val result = c.quizResult
    val s = GameState(
        DiceSource.MANUAL, Side.ME, q.me, q.opp, Side.ME,
        draft = TurnDraft(rollsDone = q.rollsDone, dice = q.dice, held = c.quizHeld),
    )
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "내 턴 ${q.me.filledCount + 1}/12 · ${q.rollsDone}번째 굴림 후",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (q.replay != null) "지난 게임에서 내가 둔 국면이에요" else "상대는 ${q.opp.filledCount}칸을 채웠어요",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Text("나 ${q.me.total} : ${q.opp.total} 상대", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        ScoreSheet(
            s,
            oppLabel = "상대",
            deciding = result == null,
            recommended = result?.bestCategory,
            oppInput = false,
            onMyCell = { c.quizScore(it) },
            onOppCell = {},
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (result == null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                Text(
                    if (q.rollsDone < 3) "남길 주사위를 누르고 ‘다시 굴리기’를 누르거나, 지금 기록할 칸을 점수표에서 누르세요."
                    else "점수표에서 기록할 칸을 누르세요.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSecondaryContainer,
                )
            }
        } else {
            ResultCard(c, q)
        }
        val suggested = result?.bestHeld?.let { keep ->
            val left = keep.toMutableList()
            q.dice.map { left.remove(it) }
        }
        DiceTray(q.dice, c.quizHeld, suggested) { i -> c.quizToggle(i) }
        when {
            result != null -> Button(onClick = { c.nextQuiz() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (c.quizReplayLeft == 0) "끝내기" else "다음 문제")
            }
            q.rollsDone < 3 -> Button(
                onClick = { c.quizKeep() },
                enabled = !c.quizHeld.all { it },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("이대로 다시 굴리기") }
        }
    }
}

@Composable
private fun ResultCard(c: AppController, q: QuizQuestion) {
    val r = c.quizResult ?: return
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (r.correct) cs.primaryContainer else cs.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val on = if (r.correct) cs.onPrimaryContainer else cs.onErrorContainer
            Text(
                if (r.correct) "정답이에요!" else "아쉬워요 · 승률 ${"%.1f".format(100 * r.loss)}%p 손해",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = on,
            )
            if (!r.correct) {
                Text("추천: ${r.best} · 내 답: ${r.chosen}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = on)
            }
            q.replay?.let { Text("그때 고른 수: ${it.chosen}", style = MaterialTheme.typography.bodySmall, color = on) }
            Text(r.explanation.summary, style = MaterialTheme.typography.bodyMedium, color = on)
            Bullets(r.explanation.details, on)
        }
    }
}

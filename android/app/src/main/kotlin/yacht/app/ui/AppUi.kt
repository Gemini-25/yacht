@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.Screen
import yacht.engine.Advice
import yacht.engine.Category
import yacht.engine.Mode
import yacht.engine.game.DecisionRecord
import yacht.engine.game.DiceSource
import yacht.engine.game.GameState
import yacht.engine.game.Side
import yacht.engine.game.label
import yacht.engine.game.possibleScores
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- formatting ------------------------------------------------------------------------------

private fun fmt(mode: Mode, v: Double): String =
    if (mode == Mode.WIN) "%.1f%%".format(v.coerceIn(0.0, 1.0) * 100) else "%.1f점".format(v)

private fun fmtDelta(mode: Mode, d: Double): String =
    if (mode == Mode.WIN) "%+.1f%%p".format(d * 100) else "%+.1f점".format(d)

private fun valueLabel(mode: Mode) = if (mode == Mode.WIN) "승률" else "기대 최종"

private fun oppName(s: GameState) = if (s.source == DiceSource.RANDOM) "AI" else "상대"

// ---- root ------------------------------------------------------------------------------------

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
            }
        }
    }
}

@Composable
private fun CenterText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center)
    }
}

// ---- home ------------------------------------------------------------------------------------

@Composable
fun HomeScreen(c: AppController) {
    var source by remember { mutableStateOf(DiceSource.MANUAL) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("요트 다이스 AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "닌텐도 『세계의 아소비 대전 51』 규칙 · 1:1 승률 최대화",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val current = c.state
        if (current != null && !current.isOver) {
            Button(onClick = { c.screen = Screen.GAME }, modifier = Modifier.fillMaxWidth()) {
                Text("진행 중인 게임 계속하기")
            }
        }
        Text("모드", style = MaterialTheme.typography.titleMedium)
        ModeOption(
            selected = source == DiceSource.MANUAL,
            title = "실전 도우미",
            desc = "스위치에서 나온 주사위와 상대 점수를 입력하면 최선의 수를 알려줍니다",
            onClick = { source = DiceSource.MANUAL },
        )
        ModeOption(
            selected = source == DiceSource.RANDOM,
            title = "AI와 대전",
            desc = "앱이 주사위를 굴리고, 승률 최대화 AI와 1:1로 겨룹니다 (연습용)",
            onClick = { source = DiceSource.RANDOM },
        )
        Text("누가 먼저 하나요?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { c.newGame(source, Side.ME) }, modifier = Modifier.weight(1f)) { Text("내가 선공") }
            Button(onClick = { c.newGame(source, Side.OPP) }, modifier = Modifier.weight(1f)) {
                Text(if (source == DiceSource.RANDOM) "AI가 선공" else "상대가 선공")
            }
        }
        Text(
            "선후공을 고를 수 있으면 후공을 추천합니다. 마지막 턴에 상대의 최종 점수를 보고 둘 수 있어 승률이 약 2%p 높습니다 (시뮬레이션 기준).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("AI 목표", style = MaterialTheme.typography.titleMedium)
        ObjectiveChips(c)
        OutlinedButton(onClick = { c.openHistory() }, modifier = Modifier.fillMaxWidth()) { Text("복기 기록") }
        Text(
            "규칙: 상단 합 63점 이상이면 보너스 35점 · 초이스/포카드/풀하우스는 주사위 5개 합 (요트도 풀하우스 인정) · S.스트레이트 15 · L.스트레이트 30 · 요트 50",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ObjectiveChips(c: AppController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = c.objective == Mode.WIN,
            onClick = { c.changeObjective(Mode.WIN) },
            label = { Text("승률 최대 (추천)") },
        )
        FilterChip(
            selected = c.objective == Mode.EXPECTED_SCORE,
            onClick = { c.changeObjective(Mode.EXPECTED_SCORE) },
            label = { Text("기대점수 최대") },
        )
    }
}

// ---- game ------------------------------------------------------------------------------------

@Composable
fun GameScreen(c: AppController) {
    val s = c.state ?: return
    var oppPick by remember { mutableStateOf<Category?>(null) }
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.source == DiceSource.MANUAL) "실전 도우미" else "AI와 대전") },
                navigationIcon = { TextButton(onClick = { c.screen = Screen.HOME }) { Text("홈") } },
                actions = {
                    if (s.source == DiceSource.MANUAL) {
                        TextButton(onClick = { c.undo() }, enabled = c.canUndo) { Text("되돌리기") }
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text((if (c.objective == Mode.WIN) "✓ " else "") + "목표: 승률 최대") },
                                onClick = { c.changeObjective(Mode.WIN); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text((if (c.objective == Mode.EXPECTED_SCORE) "✓ " else "") + "목표: 기대점수 최대") },
                                onClick = { c.changeObjective(Mode.EXPECTED_SCORE); menu = false },
                            )
                            if (s.source == DiceSource.RANDOM) {
                                DropdownMenuItem(
                                    text = { Text(if (c.showHints) "힌트 숨기기" else "힌트 보기") },
                                    onClick = { c.showHints = !c.showHints; menu = false },
                                )
                            }
                            DropdownMenuItem(text = { Text("복기 기록") }, onClick = { menu = false; c.openHistory() })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScoreTable(c, s, onOppCell = { oppPick = it }, modifier = Modifier.weight(1f))
            StatusBar(c, s)
            if (s.isOver) GameOverPanel(c, s) else BottomPanel(c, s)
        }
    }
    val pick = oppPick
    if (pick != null) {
        OppScoreDialog(
            s, pick,
            onPick = { points -> c.opponentScore(pick, points); oppPick = null },
            onDismiss = { oppPick = null },
        )
    }
}

@Composable
private fun ScoreTable(c: AppController, s: GameState, onOppCell: (Category) -> Unit, modifier: Modifier) {
    val advice = if (s.source == DiceSource.RANDOM && !c.showHints) null else c.advice
    val deciding = s.toMove == Side.ME && s.draft.deciding && !s.isOver
    val recommended = advice?.takeIf { it.shouldStop }?.bestCategory?.category
    val oppInput = s.toMove == Side.OPP && s.source == DiceSource.MANUAL && !s.isOver
    val cs = MaterialTheme.colorScheme
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row {
            Cell("족보", 1.4f, bold = true)
            Cell("나", 1f, bold = true, color = if (s.toMove == Side.ME) cs.primary else Color.Unspecified)
            Cell(oppName(s), 1f, bold = true, color = if (s.toMove == Side.OPP) cs.primary else Color.Unspecified)
        }
        for (cat in Category.ALL) {
            Row {
                Cell(cat.label, 1.4f)
                val mine = s.me[cat]
                when {
                    mine != null -> Cell("$mine", 1f, bold = true)
                    deciding -> {
                        val pts = s.rules.score(s.draft.faces, cat)
                        Cell(
                            "$pts", 1f,
                            bg = if (cat == recommended) cs.primaryContainer else cs.surfaceVariant,
                            color = if (cat == recommended) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            bold = cat == recommended,
                            onClick = { c.score(cat) },
                        )
                    }
                    else -> Cell("", 1f)
                }
                val theirs = s.opp[cat]
                when {
                    theirs != null -> Cell("$theirs", 1f, bold = true)
                    oppInput -> Cell("+", 1f, bg = cs.tertiaryContainer, color = cs.onTertiaryContainer, onClick = { onOppCell(cat) })
                    else -> Cell("", 1f)
                }
            }
            if (cat == Category.SIXES) {
                Row {
                    Cell("소계 /63", 1.4f, color = cs.onSurfaceVariant)
                    Cell("${s.me.upperSum}", 1f, color = cs.onSurfaceVariant)
                    Cell("${s.opp.upperSum}", 1f, color = cs.onSurfaceVariant)
                }
                Row {
                    Cell("보너스", 1.4f, color = cs.onSurfaceVariant)
                    Cell(if (s.me.bonus > 0) "+${s.me.bonus}" else "", 1f, color = cs.onSurfaceVariant)
                    Cell(if (s.opp.bonus > 0) "+${s.opp.bonus}" else "", 1f, color = cs.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider()
        Row {
            Cell("합계", 1.4f, bold = true)
            Cell("${s.me.total}", 1f, bold = true)
            Cell("${s.opp.total}", 1f, bold = true)
        }
    }
}

@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
    bg: Color = Color.Transparent,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier.weight(weight).height(32.dp).padding(2.dp).clip(RoundedCornerShape(6.dp)).background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
    }
}

@Composable
private fun StatusBar(c: AppController, s: GameState) {
    val cs = MaterialTheme.colorScheme
    val d = s.draft
    val text = when {
        s.isOver -> "게임 종료"
        s.toMove == Side.OPP && s.source == DiceSource.RANDOM -> if (c.aiThinking) "AI가 두는 중…" else "AI 차례"
        s.toMove == Side.OPP -> "상대 차례 · 상대가 기록한 칸의 + 를 눌러 점수 입력"
        d.rollsDone == 0 -> "내 차례 ${s.myTurnNumber}/12 · 첫 굴림"
        else -> "내 차례 ${s.myTurnNumber}/12 · ${d.rollsDone}번째 굴림"
    }
    Column(Modifier.fillMaxWidth().background(cs.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val v = c.turnValue
            if (v != null && s.toMove == Side.ME && !s.isOver && (s.source == DiceSource.MANUAL || c.showHints)) {
                Text("${valueLabel(c.objective)} ${fmt(c.objective, v)}", fontWeight = FontWeight.Bold)
            }
            if (c.refining) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        val last = s.oppLog.lastOrNull()
        if (last != null && s.source == DiceSource.RANDOM) {
            Text("AI $last", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun BottomPanel(c: AppController, s: GameState) {
    if (s.toMove != Side.ME) return
    val d = s.draft
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.source == DiceSource.MANUAL || c.showHints) AdviceCard(c, s)
        DiceRow(s.draft.dice, s.draft.held) { i -> if (d.deciding) c.toggleHold(i) else c.clearSlot(i) }
        if (s.source == DiceSource.MANUAL) ManualControls(c, s) else RandomControls(c, s)
    }
}

@Composable
private fun AdviceCard(c: AppController, s: GameState) {
    val a = c.advice
    val d = s.draft
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                c.plan == null -> Text("AI 계산 중…")
                !d.deciding -> Text(
                    if (s.source == DiceSource.MANUAL) "굴린 주사위를 입력하면 추천이 나옵니다" else "주사위를 굴리세요",
                    style = MaterialTheme.typography.bodyMedium,
                )
                a == null -> Text("AI 계산 중…")
                a.shouldStop -> {
                    val best = a.bestCategory
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "추천: ${best.category.label}에 ${best.points}점 기록",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { c.score(best.category) }) { Text("기록") }
                    }
                    ValueLine(a.mode, best.value, best.expectedFinal)
                }
                else -> {
                    val k = a.bestKeep!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("추천: ", fontWeight = FontWeight.Bold)
                        if (k.held.isEmpty()) Text("전부 다시 굴리기", fontWeight = FontWeight.Bold)
                        else {
                            DiceChips(k.held)
                            Text(" 고정 → 다시 굴리기", fontWeight = FontWeight.Bold)
                        }
                    }
                    ValueLine(a.mode, k.value, k.expectedFinal)
                    val mine = a.keeps.firstOrNull { it.held == d.heldFaces }
                    if (mine != null && mine.held != k.held) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "현재 고정: ${fmt(a.mode, mine.value)} (${fmtDelta(a.mode, mine.value - k.value)})",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { c.holdRecommended() }) { Text("추천대로 고정") }
                        }
                    }
                }
            }
            if (a != null) OptionsToggle(a)
        }
    }
}

@Composable
private fun ValueLine(mode: Mode, value: Double, expectedFinal: Double) {
    val text = if (mode == Mode.WIN) "승률 ${fmt(mode, value)} · 기대 최종 %.1f점".format(expectedFinal)
    else "기대 최종 %.1f점".format(value)
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun OptionsToggle(a: Advice) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) "선택지 접기" else "다른 선택지 보기")
    }
    if (!open) return
    if (a.keeps.isNotEmpty()) {
        Text("고정 선택지", style = MaterialTheme.typography.labelMedium)
        for (k in a.keeps.take(6)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (k.held.isEmpty()) Text("전부 다시", style = MaterialTheme.typography.bodySmall)
                else if (k.held.size == 5) Text("그대로 멈춤", style = MaterialTheme.typography.bodySmall)
                else DiceChips(k.held, small = true)
                Spacer(Modifier.weight(1f))
                Text(fmt(a.mode, k.value), style = MaterialTheme.typography.bodySmall)
                Text(
                    "  " + fmtDelta(a.mode, k.value - a.value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text("지금 기록한다면", style = MaterialTheme.typography.labelMedium)
    for (o in a.categories.take(6)) {
        Row(Modifier.fillMaxWidth()) {
            Text("${o.category.label} ${o.points}점", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(fmt(a.mode, o.value), style = MaterialTheme.typography.bodySmall)
            Text(
                "  " + fmtDelta(a.mode, o.value - a.value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiceChips(faces: List<Int>, small: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (f in faces) {
            Box(Modifier.size(if (small) 18.dp else 24.dp)) {
                DieFace(f, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun DiceRow(dice: List<Int?>, held: List<Boolean>, onDie: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (i in 0 until 5) {
            val face = dice[i]
            val isHeld = held[i]
            Box(
                Modifier.weight(1f).aspectRatio(1f)
                    .border(2.dp, if (isHeld) cs.primary else cs.outline, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = face != null) { onDie(i) },
                contentAlignment = Alignment.Center,
            ) {
                if (face == null) Text("?", color = cs.outline, fontSize = 22.sp)
                else DieFace(face, if (isHeld) cs.onPrimary else cs.onSurface, if (isHeld) cs.primary else cs.surface)
            }
        }
    }
}

private val PIPS = mapOf(
    1 to listOf(0.5f to 0.5f),
    2 to listOf(0.25f to 0.25f, 0.75f to 0.75f),
    3 to listOf(0.25f to 0.25f, 0.5f to 0.5f, 0.75f to 0.75f),
    4 to listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.25f to 0.75f, 0.75f to 0.75f),
    5 to listOf(0.25f to 0.25f, 0.75f to 0.25f, 0.5f to 0.5f, 0.25f to 0.75f, 0.75f to 0.75f),
    6 to listOf(0.25f to 0.22f, 0.75f to 0.22f, 0.25f to 0.5f, 0.75f to 0.5f, 0.25f to 0.78f, 0.75f to 0.78f),
)

@Composable
private fun DieFace(face: Int, pip: Color, bg: Color) {
    Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(bg)) {
        val r = size.minDimension * 0.09f
        for ((x, y) in PIPS.getValue(face)) {
            drawCircle(if (face == 1) Color(0xFFD32F2F) else pip, r, Offset(size.width * x, size.height * y))
        }
    }
}

@Composable
private fun ManualControls(c: AppController, s: GameState) {
    val d = s.draft
    val canEnter = !d.complete && d.rollsDone < 3
    if (canEnter) {
        val n = d.dice.count { it == null }
        Text(
            "${d.rollsDone + 1}번째 굴림 결과 입력 (${n}개 남음) · 잘못 넣은 주사위는 눌러서 지우기",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in 1..6) {
            FilledTonalButton(
                onClick = { c.enterFace(f) },
                enabled = canEnter,
                modifier = Modifier.weight(1f).height(48.dp),
                contentPadding = PaddingValues(0.dp),
            ) { Text("$f", fontSize = 20.sp) }
        }
        OutlinedButton(
            onClick = { c.backspace() },
            enabled = d.entryOrder.isNotEmpty(),
            modifier = Modifier.weight(1f).height(48.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("⌫", fontSize = 18.sp) }
    }
    if (d.deciding) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { c.reroll() },
                enabled = d.rollsDone < 3 && !d.held.all { it },
                modifier = Modifier.weight(1f),
            ) { Text(if (d.rollsDone < 3) "고정 안 한 주사위 다시 굴림 (${d.rollsDone}/3)" else "굴림 끝") }
        }
        Text(
            "주사위를 눌러 고정/해제 · 점수표의 회색 칸을 누르면 그 칸에 기록",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RandomControls(c: AppController, s: GameState) {
    val d = s.draft
    if (d.rollsDone == 0) {
        Button(onClick = { c.firstThrow() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("주사위 굴리기", fontSize = 18.sp)
        }
        return
    }
    Button(
        onClick = { c.reroll() },
        enabled = d.rollsDone < 3 && !d.held.all { it },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(if (d.rollsDone < 3) "다시 굴리기 (${d.rollsDone}/3)" else "점수표에서 기록할 칸을 고르세요") }
    Text(
        "주사위를 눌러 고정/해제 · 점수표의 회색 칸을 누르면 기록",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OppScoreDialog(s: GameState, cat: Category, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val scores = remember(cat) { possibleScores(s.rules, cat) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        title = { Text("${oppName(s)} ${cat.label} 점수") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in scores.chunked(5)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in row) {
                            OutlinedButton(
                                onClick = { onPick(v) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("$v") }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
    )
}

@Composable
private fun GameOverPanel(c: AppController, s: GameState) {
    val me = s.me.total
    val opp = s.opp.total
    val mistakes = s.decisions.filter { it.isMistake }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                me > opp -> "승리!"
                me == opp -> "무승부"
                else -> "패배"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text("나 $me : $opp ${oppName(s)}")
        if (s.decisions.isNotEmpty()) {
            Text("AI와 다르게 둔 수 ${mistakes.size}회", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.openReview(s) }, modifier = Modifier.weight(1f)) { Text("복기 보기") }
            OutlinedButton(onClick = { c.screen = Screen.HOME }, modifier = Modifier.weight(1f)) { Text("새 게임") }
        }
    }
}

// ---- history & review ------------------------------------------------------------------------

private val DATE = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)

@Composable
fun HistoryScreen(c: AppController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("복기 기록") },
                navigationIcon = { TextButton(onClick = { c.screen = Screen.HOME }) { Text("홈") } },
            )
        },
    ) { pad ->
        if (c.history.isEmpty()) {
            CenterText("끝난 게임이 아직 없습니다", Modifier.padding(pad))
        } else {
            LazyColumn(
                Modifier.padding(pad),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(c.history) { g ->
                    val result = when {
                        g.me.total > g.opp.total -> "승"
                        g.me.total == g.opp.total -> "무"
                        else -> "패"
                    }
                    Card(onClick = { c.openReview(g) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${DATE.format(Date(g.createdAt))} · ${if (g.source == DiceSource.MANUAL) "실전" else "AI 대전"} · " +
                                    if (g.first == Side.ME) "선공" else "후공",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("$result  나 ${g.me.total} : ${g.opp.total} ${oppName(g)}", fontWeight = FontWeight.Bold)
                            Text("AI와 다르게 둔 수 ${g.decisions.count { it.isMistake }}회", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
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
    val mistakes = g.decisions.filter { it.isMistake }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("복기") },
                navigationIcon = { TextButton(onClick = { c.openHistory() }) { Text("목록") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("나 ${g.me.total} : ${g.opp.total} ${oppName(g)}", style = MaterialTheme.typography.titleLarge)
                    Text("판단 ${g.decisions.size}회 중 AI와 다른 수 ${mistakes.size}회")
                    val winLoss = mistakes.filter { it.mode == Mode.WIN }.sumOf { it.loss }
                    val evLoss = g.decisions.sumOf { it.expectedLoss }
                    Text(
                        "승률 손실 합계 %.1f%%p · 기대점수 손실 합계 %.1f점".format(winLoss * 100, evLoss),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (mistakes.isNotEmpty()) {
                        Text("가장 큰 실수", style = MaterialTheme.typography.titleSmall)
                        for (m in mistakes.sortedByDescending { it.loss }.take(3)) DecisionCard(m)
                        HorizontalDivider()
                        Text("전체 판단", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            items(g.decisions) { d -> DecisionCard(d) }
            if (g.oppLog.isNotEmpty()) {
                item { Text("AI 기록", style = MaterialTheme.typography.titleSmall) }
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
        colors = CardDefaults.cardColors(containerColor = if (d.isMistake) cs.errorContainer else cs.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("턴 ${d.turn} · ${d.rollsDone}번째 굴림 ", style = MaterialTheme.typography.bodySmall)
                DiceChips(d.dice, small = true)
            }
            Text("선택: ${d.chosen} (${fmt(d.mode, d.chosenValue)})", fontWeight = FontWeight.SemiBold)
            if (d.isMistake) {
                Text(
                    "최선: ${d.best} (${fmt(d.mode, d.bestValue)}) · 손실 ${fmtDelta(d.mode, -d.loss)}" +
                        " / 기대점수 %+.1f점".format(-d.expectedLoss),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

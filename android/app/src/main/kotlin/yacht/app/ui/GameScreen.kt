@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import yacht.engine.explain.Explanation
import yacht.engine.game.DiceSource
import yacht.engine.game.GameState
import yacht.engine.game.Side
import yacht.engine.game.label
import yacht.engine.game.possibleScores

private fun oppName(s: GameState) = if (s.source == DiceSource.RANDOM) "AI" else "상대"

private val Category.short: String
    get() = when (this) {
        Category.SMALL_STRAIGHT -> "S.스트"
        Category.LARGE_STRAIGHT -> "L.스트"
        else -> label
    }

/** Advice shown to the player (hidden when hints are off in vs-AI games). */
private fun shownAdvice(c: AppController, s: GameState): Advice? =
    if (s.source == DiceSource.RANDOM && !c.showHints) null else c.advice

@Composable
fun GameScreen(c: AppController) {
    val s = c.state ?: return
    var oppPick by remember { mutableStateOf<Category?>(null) }
    var menu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                title = {
                    Column {
                        Text(if (s.source == DiceSource.MANUAL) "실전 도우미" else "AI와 대전", fontWeight = FontWeight.Bold)
                        Text(
                            if (s.isOver) "게임 종료" else "내 턴 ${minOf(s.myTurnNumber, 12)}/12 · ${if (s.first == Side.ME) "선공" else "후공"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = { c.screen = Screen.HOME }) { Text("홈") } },
                actions = {
                    if (s.source == DiceSource.MANUAL) {
                        TextButton(onClick = { c.undo() }, enabled = c.canUndo) { Text("↶ 되돌리기") }
                    }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text((if (c.objective == Mode.WIN) "✓ " else "   ") + "목표: 승률 최대") },
                                onClick = { c.changeObjective(Mode.WIN); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text((if (c.objective == Mode.EXPECTED_SCORE) "✓ " else "   ") + "목표: 기대점수 최대") },
                                onClick = { c.changeObjective(Mode.EXPECTED_SCORE); menu = false },
                            )
                            if (s.source == DiceSource.RANDOM) {
                                DropdownMenuItem(
                                    text = { Text(if (c.showHints) "힌트 숨기기" else "힌트 보기") },
                                    onClick = { c.toggleHints(); menu = false },
                                )
                            }
                            DropdownMenuItem(text = { Text("기록 · 복기") }, onClick = { menu = false; c.openHistory() })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            MatchHeader(c, s)
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                ScoreSheet(c, s, onOppCell = { oppPick = it })
            }
            if (s.isOver) GameOverPanel(c, s) else ActionArea(c, s)
        }
    }
    val pick = oppPick
    if (pick != null) {
        OppScoreDialog(s, pick, onPick = { points -> c.opponentScore(pick, points); oppPick = null }, onDismiss = { oppPick = null })
    }
}

// ---- header ----------------------------------------------------------------------------------

@Composable
private fun MatchHeader(c: AppController, s: GameState) {
    val cs = MaterialTheme.colorScheme
    val advice = shownAdvice(c, s)
    val hidden = s.source == DiceSource.RANDOM && !c.showHints
    val value = if (s.toMove == Side.ME && !hidden) advice?.value ?: c.turnValue else null
    val mode = c.plan?.mode ?: c.objective
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge("나", s.me.total, gameColors.me, active = s.toMove == Side.ME && !s.isOver)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        s.isOver -> Text("최종", style = MaterialTheme.typography.labelLarge)
                        value != null -> {
                            Text(
                                (if (mode == Mode.WIN) "승률 " else "기대 최종 ") + fmtValue(mode, value),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (c.refining) {
                                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(depthLabel(c), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                        s.toMove == Side.OPP -> Text("${oppName(s)} 차례", style = MaterialTheme.typography.labelLarge)
                        hidden -> Text("힌트 꺼짐", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        else -> Text("계산 중…", style = MaterialTheme.typography.labelLarge)
                    }
                }
                ScoreBadge(oppName(s), s.opp.total, gameColors.opp, active = s.toMove == Side.OPP && !s.isOver)
            }
            if (value != null && mode == Mode.WIN) WinBar(value)
            val situation = if (!hidden && s.toMove == Side.ME) c.situation else null
            if (situation != null) SituationLine(situation)
        }
    }
}

/** One line about the game; tap for the details. */
@Composable
private fun SituationLine(e: Explanation) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(enabled = e.details.isNotEmpty()) { open = !open }
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(e.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (e.details.isNotEmpty()) {
                Text(
                    if (open) "  접기" else "  자세히",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (open) Bullets(e.details, cs.onSurfaceVariant)
    }
}

/** Explanation sentences as a list. */
@Composable
fun Bullets(lines: List<String>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (l in lines) {
            Row {
                Text("•  ", style = MaterialTheme.typography.bodySmall, color = color)
                Text(l, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

private fun depthLabel(c: AppController): String = when {
    c.plan?.mode == Mode.EXPECTED_SCORE -> "기대점수 최적"
    c.searchDuel -> "끝까지 정확 · 상대 결과에 대응"
    c.searchExact -> "끝까지 정확 계산"
    c.searchDepth > 1 -> "앞 ${c.searchDepth}턴 정확 + 근사"
    else -> "이번 턴 정확 + 근사"
} + if (c.duelBuilding && !c.searchDuel) " · 끝까지 계산 준비 중" else ""

@Composable
private fun ScoreBadge(name: String, total: Int, color: Color, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
        Text("$total", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = color)
        Box(
            Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(50))
                .background(if (active) color else Color.Transparent),
        )
    }
}

// ---- score sheet -----------------------------------------------------------------------------

@Composable
private fun ScoreSheet(c: AppController, s: GameState, onOppCell: (Category) -> Unit) {
    val advice = shownAdvice(c, s)
    val deciding = s.toMove == Side.ME && s.draft.deciding && !s.isOver
    val recommended = advice?.takeIf { it.shouldStop }?.bestCategory?.category
    val oppInput = s.toMove == Side.OPP && s.source == DiceSource.MANUAL && !s.isOver
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (half in listOf(Category.ALL.take(6), Category.ALL.drop(6))) {
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.padding(6.dp)) {
                    Row {
                        HeadCell("", 1.25f)
                        HeadCell("나", 1f, gameColors.me)
                        HeadCell(oppName(s), 1f, gameColors.opp)
                    }
                    for (cat in half) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1.25f).height(34.dp), contentAlignment = Alignment.CenterStart) {
                                if (cat.isUpper) Box(Modifier.padding(start = 4.dp)) { MiniDie(cat.face, 20.dp) }
                                else Text(cat.short, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                            MyCell(c, s, cat, deciding, cat == recommended)
                            OppCell(s, cat, oppInput) { onOppCell(cat) }
                        }
                    }
                    if (half.first().isUpper) BonusRow(s) else TotalRow(s)
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeadCell(text: String, weight: Float, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier = Modifier.weight(weight).padding(bottom = 2.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

@Composable
private fun RowScope.Cell(
    text: String,
    bg: Color = Color.Transparent,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
    border: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier.weight(1f).height(34.dp).padding(2.dp).clip(RoundedCornerShape(8.dp)).background(bg)
            .then(if (border != null) Modifier.border(1.5.dp, border, RoundedCornerShape(8.dp)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Medium, fontSize = 15.sp)
    }
}

@Composable
private fun RowScope.MyCell(c: AppController, s: GameState, cat: Category, deciding: Boolean, recommended: Boolean) {
    val cs = MaterialTheme.colorScheme
    val mine = s.me[cat]
    when {
        mine != null -> Cell("$mine", bold = true)
        deciding -> {
            val pts = s.rules.score(s.draft.faces, cat)
            if (recommended) {
                Cell("$pts", bg = gameColors.recommend, color = gameColors.onRecommend, bold = true, onClick = { c.score(cat) })
            } else {
                Cell(
                    "$pts",
                    bg = cs.surfaceVariant,
                    color = if (pts == 0) cs.outline else cs.onSurfaceVariant,
                    border = cs.outlineVariant,
                    onClick = { c.score(cat) },
                )
            }
        }
        else -> Cell("")
    }
}

@Composable
private fun RowScope.OppCell(s: GameState, cat: Category, input: Boolean, onClick: () -> Unit) {
    val theirs = s.opp[cat]
    when {
        theirs != null -> Cell("$theirs", bold = true, color = gameColors.opp)
        input -> Cell("+", bg = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer, bold = true, onClick = onClick)
        else -> Cell("")
    }
}

@Composable
private fun BonusRow(s: GameState) {
    val cs = MaterialTheme.colorScheme
    val cap = s.rules.bonusThreshold
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1.25f).height(34.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Text("보너스", style = MaterialTheme.typography.labelMedium)
                Text("$cap↑ +${s.rules.bonusPoints}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        for ((b, color) in listOf(s.me to gameColors.me, s.opp to gameColors.opp)) {
            if (b.bonus > 0) Cell("+${b.bonus}", color = gameColors.good, bold = true)
            else Cell("${b.upperSum}", color = color.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun TotalRow(s: GameState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1.25f).height(34.dp), contentAlignment = Alignment.CenterStart) {
            Text("합계", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Cell("${s.me.total}", bold = true, color = gameColors.me)
        Cell("${s.opp.total}", bold = true, color = gameColors.opp)
    }
}

// ---- action area -----------------------------------------------------------------------------

@Composable
private fun ActionArea(c: AppController, s: GameState) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(cs.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (s.toMove == Side.OPP) {
            OpponentTurnCard(c, s)
            return@Column
        }
        val advice = shownAdvice(c, s)
        if (!(s.source == DiceSource.RANDOM && !c.showHints)) RecommendationCard(c, s, advice)
        val d = s.draft
        val suggested = advice?.bestKeep?.takeIf { !advice.shouldStop && it.held != d.heldFaces }?.let { keep ->
            val left = keep.held.toMutableList()
            d.dice.map { f -> f != null && left.remove(f) }
        }
        DiceTray(d.dice, d.held, suggested) { i -> if (d.deciding) c.toggleHold(i) else c.clearSlot(i) }
        if (s.source == DiceSource.MANUAL) ManualPad(c, s) else RandomPad(c, s)
    }
}

@Composable
private fun OpponentTurnCard(c: AppController, s: GameState) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (s.source == DiceSource.RANDOM) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.aiThinking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onTertiaryContainer)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("AI가 두는 중…", fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer)
                }
            } else {
                Text("상대 차례", fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer)
                Text(
                    "상대가 기록한 칸의 + 를 눌러 점수를 입력하세요. 입력하는 즉시 AI가 내 턴을 계산합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onTertiaryContainer,
                )
            }
            s.oppLog.lastOrNull()?.let {
                Text("최근 AI: $it", style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
            }
            val note = c.lastNote
            if (note != null && !c.hintsHidden) NoteLine(note) { c.dismissNote() }
        }
    }
}

@Composable
private fun RecommendationCard(c: AppController, s: GameState, a: Advice?) {
    val cs = MaterialTheme.colorScheme
    val d = s.draft
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            c.lastNote?.let { NoteLine(it) { c.dismissNote() } }
            when {
                !d.deciding -> {
                    Text(
                        if (s.source == DiceSource.MANUAL) "${d.rollsDone + 1}번째 굴림 결과를 아래 버튼으로 입력하세요"
                        else if (d.rollsDone == 0) "주사위를 굴리세요" else "주사위를 굴리세요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSecondaryContainer,
                    )
                    if (c.plan == null) Text("AI가 이번 턴을 계산하는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                    if (d.rollsDone == 0) {
                        c.opponentComment?.let {
                            Text("직전 상대 턴 · $it", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                        }
                    }
                }
                a == null -> Text("AI 계산 중…", color = cs.onSecondaryContainer)
                a.shouldStop -> {
                    val best = a.bestCategory
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("추천", style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer)
                            Text(
                                "${best.category.label}에 ${best.points}점 기록",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = cs.onSecondaryContainer,
                            )
                            ValueLine(a.mode, best.value, best.expectedFinal)
                        }
                        Button(onClick = { c.score(best.category) }) { Text("기록") }
                    }
                }
                else -> {
                    val k = a.bestKeep!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("추천", style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (k.held.isEmpty()) {
                                    Text("전부 다시 굴리기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = cs.onSecondaryContainer)
                                } else {
                                    MiniDice(k.held, 22.dp)
                                    Text(" 고정 후 다시", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = cs.onSecondaryContainer)
                                }
                            }
                            ValueLine(a.mode, k.value, k.expectedFinal)
                        }
                        if (d.heldFaces != k.held) FilledTonalButton(onClick = { c.holdRecommended() }) { Text("추천대로") }
                    }
                    val mine = a.keeps.firstOrNull { it.held == d.heldFaces }
                    if (mine != null && mine.held != k.held) {
                        Text(
                            "지금 고정한 대로면 ${fmtValue(a.mode, mine.value)} (${fmtDelta(a.mode, mine.value - k.value)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            if (a != null) {
                val why = c.adviceExplanation
                if (why != null) {
                    Text(
                        why.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSecondaryContainer,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (why != null && why.details.isNotEmpty()) {
                        Toggle(if (c.whyOpen) "▴ 왜? 접기" else "▾ 왜?") { c.toggleWhy() }
                    }
                    Toggle(if (open) "▴ 선택지 접기" else "▾ 다른 선택지와 비교") { open = !open }
                }
                if (why != null && c.whyOpen) Bullets(why.details, cs.onSecondaryContainer)
                if (open) OptionsList(c, a)
            }
        }
    }
}

@Composable
private fun Toggle(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        fontWeight = FontWeight.Bold,
    )
}

/** Why my last move differed from the advice; tap to dismiss. */
@Composable
private fun NoteLine(note: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.errorContainer)
            .clickable(onClick = onDismiss).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("방금 선택 · $note", style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer, modifier = Modifier.weight(1f))
        Text("  ✕", style = MaterialTheme.typography.labelSmall, color = cs.onErrorContainer)
    }
}

@Composable
private fun ValueLine(mode: Mode, value: Double, expectedFinal: Double) {
    val text = if (mode == Mode.WIN) "승률 ${pct(value)} · 기대 최종 ${"%.1f".format(expectedFinal)}점"
    else "기대 최종 %.1f점".format(value)
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
}

@Composable
private fun OptionsList(c: AppController, a: Advice) {
    val cs = MaterialTheme.colorScheme
    var pickedKeep by remember(a.rollsDone, a.dice) { mutableStateOf<List<Int>?>(null) }
    var pickedCat by remember(a.rollsDone, a.dice) { mutableStateOf<Category?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("선택지를 누르면 추천과 비교해 설명해 드려요", style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer.copy(alpha = 0.75f))
        if (a.keeps.isNotEmpty()) {
            Text("고정 선택", style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer)
            for (k in a.keeps.take(5)) {
                OptionRow(
                    label = {
                        when {
                            k.held.isEmpty() -> Text("전부 다시", style = MaterialTheme.typography.bodySmall)
                            k.held.size == 5 -> Text("멈추고 기록", style = MaterialTheme.typography.bodySmall)
                            else -> MiniDice(k.held, 16.dp)
                        }
                    },
                    value = fmtValue(a.mode, k.value),
                    delta = fmtDelta(a.mode, k.value - a.value),
                    selected = pickedKeep == k.held,
                ) {
                    pickedCat = null
                    pickedKeep = if (pickedKeep == k.held) null else k.held
                }
            }
        }
        Text("지금 기록한다면", style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer)
        for (o in a.categories.take(5)) {
            OptionRow(
                label = { Text("${o.category.label} ${o.points}점", style = MaterialTheme.typography.bodySmall) },
                value = fmtValue(a.mode, o.value),
                delta = fmtDelta(a.mode, o.value - a.value),
                selected = pickedCat == o.category,
            ) {
                pickedKeep = null
                pickedCat = if (pickedCat == o.category) null else o.category
            }
        }
        val e = pickedKeep?.let { h -> a.keeps.firstOrNull { it.held == h }?.let { c.keepExplanation(it) } }
            ?: pickedCat?.let { p -> a.categories.firstOrNull { it.category == p }?.let { c.categoryExplanation(it) } }
        if (e != null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp))
                    .background(cs.surface.copy(alpha = 0.7f)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(e.summary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Bullets(e.details, cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: @Composable () -> Unit,
    value: String,
    delta: String,
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (selected) cs.surface.copy(alpha = 0.7f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { label() }
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(
            delta,
            modifier = Modifier.width(64.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ManualPad(c: AppController, s: GameState) {
    val d = s.draft
    val canEnter = !d.complete && d.rollsDone < 3
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (f in 1..6) {
            Box(
                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(18))
                    .background(if (canEnter) gameColors.dieBody else MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18))
                    .clickable(enabled = canEnter) { c.enterFace(f) }
                    .padding(5.dp),
            ) {
                DieFace(
                    f,
                    body = if (canEnter) gameColors.dieBody else MaterialTheme.colorScheme.surfaceVariant,
                    pip = if (canEnter) gameColors.diePip else MaterialTheme.colorScheme.outline,
                )
            }
        }
        OutlinedButton(
            onClick = { c.backspace() },
            enabled = d.entryOrder.isNotEmpty(),
            modifier = Modifier.weight(1f).aspectRatio(1f),
            contentPadding = PaddingValues(0.dp),
        ) { Text("⌫", fontSize = 18.sp) }
    }
    if (d.deciding && d.rollsDone < 3) {
        Button(
            onClick = { c.reroll() },
            enabled = !d.held.all { it },
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) { Text("고정 안 한 주사위 다시 굴림  (${d.rollsDone + 1}/3번째)") }
    } else {
        Text(
            when {
                d.deciding -> "3번 다 굴렸습니다. 점수표에서 기록할 칸을 누르세요 (노란 칸 = 추천)"
                d.dice.any { it != null } -> "주사위를 잘못 넣었으면 그 주사위를 눌러 지우세요"
                else -> "주사위 눈 버튼을 왼쪽 주사위부터 차례로 누르세요"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RandomPad(c: AppController, s: GameState) {
    val d = s.draft
    when {
        d.rollsDone == 0 -> Button(onClick = { c.firstThrow() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text("주사위 굴리기", style = MaterialTheme.typography.titleMedium)
        }
        d.rollsDone < 3 -> Button(
            onClick = { c.reroll() },
            enabled = !d.held.all { it },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("다시 굴리기  (${d.rollsDone + 1}/3번째)", style = MaterialTheme.typography.titleMedium) }
        else -> Text(
            "점수표에서 기록할 칸을 누르세요",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OppScoreDialog(s: GameState, cat: Category, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val scores = remember(cat) { possibleScores(s.rules, cat) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cat.isUpper) {
                    MiniDie(cat.face, 24.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("${oppName(s)} · ${cat.label} 점수")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in scores.chunked(5)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in row) {
                            OutlinedButton(
                                onClick = { onPick(v) },
                                modifier = Modifier.weight(1f).height(44.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) { Text("$v", fontWeight = FontWeight.Bold) }
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
    val cs = MaterialTheme.colorScheme
    val mistakes = s.decisions.count { it.isMistake }
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (me > opp) cs.primaryContainer else cs.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    me > opp -> "승리!"
                    me == opp -> "무승부"
                    else -> "패배"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text("나 $me : $opp ${oppName(s)}", style = MaterialTheme.typography.titleMedium)
            val summary = c.gameSummary(s)
            if (summary != null && summary.details.isNotEmpty()) {
                Bullets(summary.details, cs.onSurface)
            } else if (s.decisions.isNotEmpty()) {
                Text("AI 추천과 다르게 둔 수 ${mistakes}번", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.openReview(s) }, modifier = Modifier.weight(1f)) { Text("복기 보기") }
                OutlinedButton(onClick = { c.screen = Screen.HOME }, modifier = Modifier.weight(1f)) { Text("새 게임") }
            }
        }
    }
}

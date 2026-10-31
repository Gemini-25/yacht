package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.ClockView
import yacht.app.core.LeagueController
import yacht.app.core.LeagueRunMode
import yacht.app.core.LeagueView
import yacht.engine.league.Comp

/** How the control bar's status line is coloured. */
internal enum class BarTone { QUIET, LIVE, DONE, ERROR }

/** The control bar's status line. */
internal data class BarStatus(val text: String, val tone: BarTone)

/** The bar's title: "시즌 3 · 12주차 / 42주", "시즌 3 · 포스트시즌 2주차", "시즌 3 마무리 중"; "AI 리그" before the first season. */
internal fun barTitle(clock: ClockView?): String = when {
    clock == null -> "AI 리그"
    clock.over -> "시즌 ${clock.season} 마무리 중"
    else -> "시즌 ${clock.season} · ${clockTitle(clock)}"
}

/** The bar's status line: the first of an error, the tables being built, the run, a week played, an empty league, the pause. */
internal fun barStatus(lg: LeagueController, v: LeagueView?): BarStatus {
    val clock = v?.clock
    lg.stopReason?.let { return BarStatus("⚠ $it", BarTone.ERROR) }
    lg.preparing?.let { return BarStatus(it, BarTone.QUIET) }
    if (lg.running) {
        if (lg.runMode == LeagueRunMode.WEEK) {
            val stages = clock?.row(clock.week)?.stages.orEmpty().filter { it.set }
            val counts = if (stages.isEmpty()) "" else " · ${stages.sumOf { it.played }}/${stages.sumOf { it.total }}경기"
            return BarStatus("🔴 이번 주만 진행해요$counts", BarTone.LIVE)
        }
        return BarStatus("🔴 " + (clock?.let { weekStages(it) } ?: "리그 진행 중"), BarTone.LIVE)
    }
    lg.weekDone?.let { return BarStatus("✓ " + firstSentence(it), BarTone.DONE) }
    // Short enough for the ~200 dp the line has at 360 dp.
    if ((v?.matches ?: 0L) == 0L) return BarStatus("아직 경기가 없어요 · ▶로 시작해요", BarTone.QUIET)
    return BarStatus(if (lg.runMode == LeagueRunMode.WEEK) "⏸ 멈춤 · ▶로 한 주를 치러요" else "⏸ 멈춤 · ▶로 계속 진행해요", BarTone.QUIET)
}

/**
 * The first sentence of a note ("46주차를 다 치렀어요. ▶를 누르면 47주차를 해요." → "46주차를 다
 * 치렀어요"): what the bar's one line can hold; the title already names the next week.
 */
internal fun firstSentence(note: String): String = note.substringBefore(". ").removeSuffix(".")

/** What the week in play holds: "요트컵 64강 · 챔스 A조 4차전", "리그 12라운드" when only the league plays. */
private fun weekStages(c: ClockView): String {
    if (c.over) return "시상식과 승강, 이적 시장이 이어지고 다음 시즌이 열려요"
    val stages = c.row(c.week)?.stages.orEmpty()
    val cups = stages.filter { it.comp != Comp.LEAGUE }
    return (cups.ifEmpty { stages }).joinToString(" · ") { stageName(it, short = true, icon = false) }.ifEmpty { clockTitle(c) }
}

/**
 * The league's control bar (56 dp): ← out of the league ([onExit]), ▶/■, the season's clock and
 * a status line (a tap: the week in play, [onWeek], or why it stopped), and ⚙ ([onSettings]).
 */
@Composable
internal fun ControlBar(c: AppController, onExit: () -> Unit, onWeek: () -> Unit, onSettings: () -> Unit) {
    val lg = c.league
    val v = lg.view
    var why by remember { mutableStateOf(false) }
    ControlBarRow(
        barTitle(v?.clock), barStatus(lg, v), lg.running, lg.preparing != null, enabled = v != null,
        onExit = onExit,
        onToggle = { if (lg.running) lg.stop() else lg.start() },
        onTap = { if (lg.stopReason != null) why = true else onWeek() },
        onSettings = onSettings,
    )
    val reason = lg.stopReason
    if (why && reason != null) {
        AlertDialog(
            onDismissRequest = { why = false },
            confirmButton = { TextButton(onClick = { why = false }) { Text("닫기") } },
            title = { Text("리그가 멈췄어요", fontWeight = FontWeight.Bold) },
            text = { Text(reason, style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

/** The bar's row from plain values (the screenshot harness draws it with a status of its own). */
@Composable
internal fun ControlBarRow(
    title: String,
    status: BarStatus,
    running: Boolean,
    preparing: Boolean,
    enabled: Boolean,
    onExit: () -> Unit,
    onToggle: () -> Unit,
    onTap: () -> Unit,
    onSettings: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp).background(cs.background).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onExit) { Text("←", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics { contentDescription = "리그 나가기" }) }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (preparing) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                FilledTonalIconButton(
                    onClick = onToggle,
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                    colors = if (running) IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.errorContainer, contentColor = cs.error) else IconButtonDefaults.filledTonalIconButtonColors(),
                ) {
                    Text(if (running) "■" else "▶", fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics { contentDescription = if (running) "정지" else "시작" })
                }
            }
        }
        Column(Modifier.weight(1f).clickable(onClick = onTap).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                status.text,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when (status.tone) {
                    BarTone.QUIET -> cs.onSurfaceVariant
                    BarTone.LIVE -> cs.onSurface
                    BarTone.DONE -> cs.tertiary
                    BarTone.ERROR -> cs.error
                },
            )
        }
        IconButton(onClick = onSettings) { Text("⚙", fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics { contentDescription = "리그 설정" }) }
    }
}

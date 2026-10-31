@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package yacht.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.LeagueTab
import yacht.app.core.MatchCenter
import yacht.app.core.Page
import yacht.app.core.Screen
import yacht.app.core.WatchKind
import yacht.app.core.WatchPace
import yacht.app.core.WatchView
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.RuleSet
import yacht.engine.game.Game
import yacht.engine.game.label

/** What the watch screen's buttons do ([WatchBody] is drawn with them, by the app and by the harness). */
internal class WatchActions(
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val skipTurn: () -> Unit = {},
    val nextGame: () -> Unit = {},
    val nextMatch: () -> Unit = {},
    val pickGame: (Int) -> Unit = {},
    val pickBoard: (Int) -> Unit = {},
    val pace: (WatchPace) -> Unit = {},
    val summary: (Boolean) -> Unit = {},
    val report: () -> Unit = {},
    val again: () -> Unit = {},
    val playWeek: () -> Unit = {},
    val stop: () -> Unit = {},
    /** The league is running (a pick still to be played: "곧 시작해요" rather than ▶ 이번 주 진행). */
    val running: Boolean = false,
    /** The channel plays its matches in 요약 (the deciding game in full). */
    val summaryOn: Boolean = true,
)

/** The watch screen: a real match played again, a highlight, the broadcast channel or a friendly, throw by throw. */
@Composable
fun WatchScreen(c: AppController) {
    val lg = c.league
    val center = lg.center
    val w = center.watch
    val cs = MaterialTheme.colorScheme
    val leave = {
        center.stop()
        c.screen = Screen.LEAGUE
    }
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                title = {
                    Column {
                        Text(w?.let { watchTitle(it) } ?: "경기 관전", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        w?.title?.takeIf { w.kind != WatchKind.FRIENDLY }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2) }
                    }
                },
                navigationIcon = { TextButton(onClick = leave, modifier = Modifier.semantics { contentDescription = "뒤로" }) { Text("←", fontSize = 20.sp) } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (w == null) {
                CenterText("볼 경기가 없어요")
            } else {
                val actions = WatchActions(
                    pause = center::pause, resume = center::resume, skipTurn = center::skipTurn, nextGame = center::nextGame,
                    nextMatch = center::nextMatch, pickGame = center::pickGame, pickBoard = center::pickBoard, pace = center::setPace,
                    summary = center::setSummary,
                    report = {
                        val ref = w.ref
                        center.stop()
                        if (ref != null) {
                            lg.nav.go(LeagueTab.MATCHES)
                            lg.nav.open(Page.Match(ref, w.board, w.game))
                        }
                        c.screen = Screen.LEAGUE
                    },
                    again = { if (w.first.isNotEmpty()) center.watchFriendly(w.first, w.second) },
                    playWeek = { lg.startOneWeek() },
                    stop = leave,
                    running = lg.running,
                    summaryOn = center.summaryOn,
                )
                WatchBody(w, c.tables?.rules ?: RuleSet.NINTENDO, actions, Modifier.fillMaxSize().padding(horizontal = 12.dp))
            }
        }
    }
}

/** The bar's title by what is watched: "▶ 다시 보기", "🎬 하이라이트", "📺 이번 주 중계 2/6", "🤝 친선 경기". */
private fun watchTitle(w: WatchView): String = when (w.kind) {
    WatchKind.REPLAY -> "▶ 다시 보기"
    WatchKind.HIGHLIGHT -> "🎬 하이라이트"
    WatchKind.BROADCAST -> "📺 이번 주 중계" + (w.queue?.let { " ${it.index + 1}/${it.total}" } ?: "")
    WatchKind.FRIENDLY -> "🤝 친선 경기"
}

/**
 * The watch screen's body: the score card (with the match score and a strip of the board's
 * games), the boards of a team match, both cards and the turn shown with its commentary, the
 * broadcaster's lines, the countdown to the channel's next match, and the two rows of controls.
 */
@Composable
internal fun WatchBody(w: WatchView, rules: RuleSet, actions: WatchActions, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            w.message != null -> Box(Modifier.weight(1f).fillMaxWidth()) {
                Text(w.message, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
            w.waiting != null && w.steps.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth()) { WaitingCard(w, actions, Modifier.align(Alignment.Center)) }
            else -> Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { WatchGame(w, rules, actions) }
            }
        }
        if (w.message == null) Controls(w, actions)
    }
}

/** A pick not played yet (the league stopped: ▶ 이번 주 진행), or a match being worked out. */
@Composable
private fun WaitingCard(w: WatchView, actions: WatchActions, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val unplayed = w.kind == WatchKind.BROADCAST && w.waiting == MatchCenter.UNPLAYED_PICK && !actions.running
        if (!unplayed) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(w.waiting.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        if (unplayed) Button(onClick = actions.playWeek) { Text("▶ 이번 주 진행") }
    }
}

@Composable
private fun WatchGame(w: WatchView, rules: RuleSet, actions: WatchActions) {
    val cs = MaterialTheme.colorScheme
    val shown = w.steps.take(w.shown)
    val cur = shown.lastOrNull()
    val first = cur?.first ?: startBoard(w, rules, 0)
    val second = cur?.second ?: startBoard(w, rules, 1)
    ScoreCard(w, first, second, actions)
    if (w.boards > 1 && w.boardLines.isNotEmpty()) {
        // The board picker ("1번" … "5번"), then the board shown with both sides and its games so far.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in w.boardLines.indices) {
                FilterChip(selected = i == w.board, onClick = { actions.pickBoard(i) }, label = { Text("${i + 1}번 보드".takeIf { w.boardLines.size <= 3 } ?: "${i + 1}번", fontSize = 12.sp, maxLines = 1) })
            }
        }
        w.boardLines.getOrNull(w.board)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    w.queue?.countdown?.let { s -> Countdown(w, s, actions) }
    if (w.shown == 0 && w.intro.isNotEmpty()) Lines(w.intro, cs.secondaryContainer, cs.onSecondaryContainer)
    WatchCards(first, second, w)
    if (cur == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!w.complete) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
            Text("  " + (if (w.summary) "요약 중계: 이 판은 마지막 ${yacht.app.core.MatchCenter.SUMMARY_HALF_TURNS / 2}턴만 보여 드려요." else if (w.complete) "" else "경기를 준비하는 중…"), style = MaterialTheme.typography.bodySmall)
        }
    } else {
        // This turn so far, latest throw with its commentary.
        val turnSteps = shown.takeLastWhile { it.side == cur.side && it.turn == cur.turn }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${cur.turn}턴 · ${if (cur.side == 0) w.firstName else w.secondName}", fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer)
                for (st in turnSteps) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${st.throwNo}번째  ", style = MaterialTheme.typography.labelSmall, color = cs.onTertiaryContainer)
                        MiniDice(st.dice, 18.dp)
                        val act = st.held?.let { Game.keepText(it) } ?: st.category?.let { Game.scoreText(it, st.points) }.orEmpty()
                        Text("  → $act", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer)
                    }
                }
                if (cur.lines.isNotEmpty()) CommentLines(cur.lines, cs.onTertiaryContainer)
            }
        }
    }
    if (w.atEnd && w.outro.isNotEmpty()) Lines(w.outro, cs.primaryContainer, cs.onPrimaryContainer, bold = w.ended)
}

/** The cards before the first step shown: empty, or (요약 중계) as they stood before the shown half-turns. */
private fun startBoard(w: WatchView, rules: RuleSet, side: Int): Board {
    val st = w.steps.firstOrNull() ?: return Board(rules)
    // A step's cards are the ones before its turn until the turn ends.
    return if (side == 0) st.first else st.second
}

/** The two sides (names wrap), the totals of the game, the first player's P(win), the match so far and the game strip. */
@Composable
private fun ScoreCard(w: WatchView, first: Board, second: Board, actions: WatchActions) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.firstName, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = gameColors.me, maxLines = 2)
                Text("${first.total} : ${second.total}", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 8.dp))
                Text(w.secondName, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = gameColors.opp, textAlign = TextAlign.End, maxLines = 2)
            }
            val win = w.steps.take(w.shown).lastOrNull { it.winFirst != null }?.winFirst
            if (win != null) {
                WinBar(win)
                Text("${w.firstName} 승률 ${pct(win)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            w.matchScore?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            if (w.games.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (g in w.games) {
                        val done = g.index < w.game || (g.index == w.game && w.atEnd)
                        val score = if (done && g.scoreA != null && g.scoreB != null) " ${g.scoreA}:${g.scoreB} ✓" else ""
                        FilterChip(
                            selected = g.index == w.game, onClick = { actions.pickGame(g.index) },
                            label = { Text(g.label.substringBefore(" ·") + score, fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            }
        }
    }
}

/** The broadcaster's lines (before the game, or after it). */
@Composable
private fun Lines(lines: List<String>, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((i, l) in lines.withIndex()) {
                Text(l, style = MaterialTheme.typography.bodySmall, color = fg, fontWeight = if (i == 0 || (bold && l.startsWith("🏆"))) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** The channel's next match in [seconds] ("지금 보기 ⏭⏭"; "멈추기" holds it, ▶ 재생 counts down again). */
@Composable
private fun Countdown(w: WatchView, seconds: Int, actions: WatchActions) {
    val cs = MaterialTheme.colorScheme
    val next = w.queue?.next
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (next != null) "다음 경기는 $next. " else "곧 다음 경기예요. ") + "${seconds}초 뒤에 시작해요.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.nextMatch) { Text("지금 보기 ⏭⏭") }
                OutlinedButton(onClick = actions.pause) { Text("멈추기") }
            }
        }
    }
}

/** Two rows: play/pause and the skips; then the report (or one more friendly), the pace and 요약 / 전체. */
@Composable
private fun Controls(w: WatchView, actions: WatchActions) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (w.playing) {
                OutlinedButton(onClick = actions.pause, modifier = Modifier.weight(1f)) { Text("❚❚ 멈춤") }
            } else {
                Button(onClick = actions.resume, enabled = !w.ended && w.waiting == null, modifier = Modifier.weight(1f)) { Text("▶ 재생") }
            }
            IconSkip("▶▶", "다음 턴", actions.skipTurn)
            IconSkip("⏭", "다음 판", actions.nextGame)
            if (w.kind == WatchKind.BROADCAST) IconSkip("⏭⏭", "다음 경기", actions.nextMatch)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (w.kind == WatchKind.FRIENDLY) {
                Button(onClick = actions.again) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("↻ 한 판 더")
                        Text("레이팅에 반영돼요", fontSize = 9.sp)
                    }
                }
            } else if (w.ref != null) {
                OutlinedButton(onClick = actions.report) { Text("📋 리포트") }
            }
            for (p in WatchPace.entries) FilterChip(selected = w.pace == p, onClick = { actions.pace(p) }, label = { Text(p.label, fontSize = 12.sp) })
            if (w.kind == WatchKind.BROADCAST) {
                FilterChip(selected = actions.summaryOn, onClick = { actions.summary(!actions.summaryOn) }, label = { Text(if (actions.summaryOn) "요약" else "전체", fontSize = 12.sp) })
            }
        }
    }
}

/** A 48 dp skip button with its name for accessibility. */
@Composable
private fun IconSkip(icon: String, name: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = name }) { Text(icon, fontSize = 14.sp) }
}

/** Both cards side by side, compact. */
@Composable
private fun WatchCards(first: Board, second: Board, w: WatchView) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text("", Modifier.weight(1.4f))
                Text(w.firstName, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = gameColors.me, textAlign = TextAlign.Center, maxLines = 2)
                Text(w.secondName, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = gameColors.opp, textAlign = TextAlign.Center, maxLines = 2)
            }
            for (cat in Category.ALL) {
                Row {
                    Text(if (cat.isUpper) "‘${cat.face}’" else cat.label, Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                    Text(first[cat]?.toString() ?: "", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(second[cat]?.toString() ?: "", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            Row {
                Text("보너스", Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                Text(if (first.bonus > 0) "+${first.bonus}" else "${first.upperSum}/63", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                Text(if (second.bonus > 0) "+${second.bonus}" else "${second.upperSum}/63", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}

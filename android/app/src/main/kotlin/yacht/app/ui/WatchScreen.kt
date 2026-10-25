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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AiPace
import yacht.app.core.AppController
import yacht.app.core.Screen
import yacht.app.core.WatchView
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.game.Game
import yacht.engine.game.label

/** Watching a league game throw by throw, with commentary and the first AI's P(win). */
@Composable
fun WatchScreen(c: AppController) {
    val lg = c.league
    val w = lg.watch
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                title = { Text("👀 경기 관전", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = {
                        lg.stopWatch()
                        c.screen = Screen.LEAGUE
                    }) { Text("리그") }
                },
            )
        },
    ) { pad ->
        if (w == null) {
            CenterText("관전할 경기가 없어요")
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shown = w.steps.take(w.shown)
            val cur = shown.lastOrNull()
            val first = cur?.first ?: Board(c.tables!!.rules)
            val second = cur?.second ?: Board(c.tables!!.rules)
            // Score line and the first AI's P(win).
            Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(w.firstName, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = gameColors.me, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${first.total} : ${second.total}", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                        Text(w.secondName, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = gameColors.opp, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val win = shown.lastOrNull { it.winFirst != null }?.winFirst
                    if (win != null) {
                        WinBar(win)
                        Text("${w.firstName} 승률 ${pct(win)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WatchCards(first, second, w)
                    if (cur == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                            Text("  경기를 준비하는 중…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        // This turn so far, latest throw with its commentary.
                        val turnSteps = shown.takeLastWhile { it.side == cur.side && it.turn == cur.turn }
                        Card(colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${cur.turn}턴 · ${if (cur.side == 0) w.firstName else w.secondName}",
                                    fontWeight = FontWeight.Bold,
                                    color = cs.onTertiaryContainer,
                                )
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
                    if (w.complete && w.shown >= w.steps.size && cur != null) {
                        val a = cur.first.total
                        val b = cur.second.total
                        Text(
                            when {
                                a > b -> "🏆 ${w.firstName} 승리! $a : $b"
                                a < b -> "🏆 ${w.secondName} 승리! $a : $b"
                                else -> "무승부 $a : $b"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text("이 경기는 리그 레이팅에도 들어갔어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (w.playing) {
                    OutlinedButton(onClick = { lg.pauseWatch() }) { Text("❚❚ 멈춤") }
                } else {
                    Button(onClick = { lg.resumeWatch { c.aiPace.stepMs.coerceAtLeast(400L) } }, enabled = !(w.complete && w.shown >= w.steps.size)) { Text("▶ 재생") }
                }
                OutlinedButton(onClick = { lg.skipWatchTurn() }) { Text("다음 턴 ▶▶") }
                Button(onClick = { lg.startWatch(w.first, w.second) { c.aiPace.stepMs.coerceAtLeast(400L) } }) { Text("↻ 새 경기") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                for (p in AiPace.entries.filter { it != AiPace.INSTANT }) {
                    FilterChip(selected = c.aiPace == p, onClick = { c.changeAiPace(p) }, label = { Text("속도: ${p.label}", fontSize = 12.sp) })
                }
            }
        }
    }
}

/** Both cards side by side, compact. */
@Composable
private fun WatchCards(first: Board, second: Board, w: WatchView) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text("", Modifier.weight(1.4f))
                Text(w.firstName, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = gameColors.me, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(w.secondName, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = gameColors.opp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

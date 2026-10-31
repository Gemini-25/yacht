@file:OptIn(ExperimentalLayoutApi::class)

package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.BoardReport
import yacht.app.core.ClockView
import yacht.app.core.GameReport
import yacht.app.core.LeagueView
import yacht.app.core.MatchReport
import yacht.app.core.Page
import yacht.app.core.ReplaySource
import yacht.app.core.ReportState
import yacht.app.core.Screen
import yacht.engine.Category
import yacht.engine.game.label
import yacht.engine.league.MatchRef

/**
 * [Page.Match]: the match report (UX 3.2), loaded on [MatchCenter.loadReport] and loaded again when
 * the match starts or ends while the page is open; ▶ plays it again on the watch screen.
 */
@Composable
internal fun MatchReportPage(c: AppController, v: LeagueView, page: Page.Match) {
    val lg = c.league
    val center = lg.center
    // A ref with a board or a game (a highlight's) opens the whole match at that board and game.
    LaunchedEffect(page.key) {
        val r = MatchRef.decode(page.key)
        if (r != null && (r.board >= 0 || r.game >= 0)) lg.nav.replaceTop(Page.Match(r.whole.encode(), maxOf(0, r.board), maxOf(0, r.game)))
    }
    val ref = MatchRef.decode(page.key)
    val live = ref != null && ref.season == v.clock?.season && v.live.any { it.comp == ref.comp && it.id == ref.id }
    LaunchedEffect(page.loadKey, live, v.clock?.week) { center.loadReport(page) }
    DisposableEffect(page.loadKey) { onDispose { center.closeReport(page) } }
    val report = center.report(page)
    if (report == null) {
        CenterText("경기 리포트를 불러오는 중…")
        return
    }
    MatchReportBody(
        report, page.board, page.game, v.clock,
        onBoard = { lg.nav.replaceTop(page.copy(board = it, game = 0)) },
        onGame = { lg.nav.replaceTop(page.copy(game = it)) },
        onWatch = { game -> watchReport(c, report, page.board, game) },
        onPlayer = { k -> if (!report.team || report.boards.any { it.a == k || it.b == k }) lg.nav.open(Page.Player(k)) },
        onNews = { lg.nav.open(Page.News) },
    )
}

/** Plays [report]'s match again from game [game] of board [board] on the watch screen. */
internal fun watchReport(c: AppController, report: MatchReport, board: Int, game: Int) {
    c.league.center.watch(report.ref, board, game)
    c.screen = Screen.WATCH
}

/** The report page's bar action: ▶ (plays the match again) when it can be. */
@Composable
internal fun MatchBarAction(c: AppController, page: Page.Match) {
    val r = c.league.center.report(page) ?: return
    if (r.state == ReportState.PLAYED && r.replayable) TextButton(onClick = { watchReport(c, r, page.board, page.game) }) { Text("▶", fontSize = 18.sp) }
}

/**
 * The report itself (the page draws it; the harness too, with hand-made reports): the header,
 * the replay line, the boards of a team match, 판별 점수, the selected game's 칸별 점수, 승리 확률 흐름
 * and 평점, 걸린 것, the preview of a match still to come, and 관련 뉴스.
 */
@Composable
internal fun MatchReportBody(
    r: MatchReport,
    board: Int,
    game: Int,
    clock: ClockView?,
    onBoard: (Int) -> Unit = {},
    onGame: (Int) -> Unit = {},
    onWatch: (Int) -> Unit = {},
    onPlayer: (String) -> Unit = {},
    onNews: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(r, clock, onPlayer)
        ReplayLine(r, onWatch)
        val bd = r.boards.getOrNull(board) ?: r.boards.firstOrNull()
        if (r.boards.size > 1) {
            ScrollableTabRow(selectedTabIndex = r.boards.indexOf(bd).coerceAtLeast(0), edgePadding = 0.dp, containerColor = cs.background) {
                for ((i, b) in r.boards.withIndex()) {
                    Tab(selected = b === bd, onClick = { onBoard(i) }, text = {
                        Text("${b.board + 1}번 보드 ${b.nameA} ${if (b.games == null) "" else "${b.totalA}:${b.totalB}"} ${b.nameB}", fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center)
                    })
                }
            }
        }
        if (bd != null && r.state == ReportState.PLAYED) {
            Games(r, bd, game, onGame, onWatch)
            val g = bd.games?.firstOrNull { it.index == game } ?: bd.games?.firstOrNull()
            if (g != null) GameDetail(r, bd, g)
        }
        // A match to come: the preview (with its own 걸린 것); the stakes it does not tell follow.
        val preview = r.preview.takeIf { r.state == ReportState.SCHEDULED }
        preview?.let { PreviewBody(it) }
        val stakes = r.stakes.filter { s -> preview == null || s !in preview.stakes }
        if (stakes.isNotEmpty()) {
            if (preview == null) Section("🔥 걸린 것")
            for (s in stakes) Text("· $s", style = MaterialTheme.typography.bodySmall)
        }
        if (r.news.isNotEmpty()) {
            Section("📰 관련 뉴스")
            for (a in r.news) ArticleCard(a, clock) { onNews() }
        }
    }
}

/** "{icon} {competition} {round} · {week}", both sides (flag, name, rating, place), the result large, the state. */
@Composable
private fun Header(r: MatchReport, clock: ClockView?, onPlayer: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((r.comp?.let { compIcon(it) + " " } ?: "") + r.title, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                val (chip, color) = when (r.state) {
                    ReportState.SCHEDULED -> "경기 전" to cs.secondaryContainer
                    ReportState.LIVE -> "경기 중" to cs.errorContainer
                    ReportState.PLAYED -> "끝" to cs.surfaceVariant
                }
                Text(chip, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Side(r.nameA, r.ratingA, r.placeA, r.deltaA, r.winner > 0, TextAlign.Start, Modifier.weight(1f).clickable(enabled = !r.team) { onPlayer(r.sideA) })
                Text(
                    r.result.ifEmpty { if (r.state == ReportState.PLAYED) "–" else "vs" },
                    fontSize = if (r.result.length > 7) 18.sp else 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Side(r.nameB, r.ratingB, r.placeB, r.deltaB, r.winner < 0, TextAlign.End, Modifier.weight(1f).clickable(enabled = !r.team) { onPlayer(r.sideB) })
            }
            if (r.week > 0 && clock != null) {
                val wk = weekLabel(r.week, r.season, clock, r.comp)
                if (wk.isNotEmpty() && wk !in r.title) Text("시즌 ${r.season} · $wk", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Side(name: String, rating: Int?, place: String?, delta: Int?, won: Boolean, align: TextAlign, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
        Text(name, fontWeight = if (won) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (won) cs.primary else cs.onSurface, maxLines = 2, textAlign = align)
        val sub = listOfNotNull(rating?.let { "레이팅 $it" }, place).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = align)
        if (delta != null && delta != 0) Text("%+d".format(delta), style = MaterialTheme.typography.labelSmall, color = if (delta > 0) gameColors.good else cs.error)
    }
}

/** "▶ 다시 보기", the replay being worked out, or why it cannot be played again (UX 3.3). */
@Composable
private fun ReplayLine(r: MatchReport, onWatch: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    when {
        r.state == ReportState.PLAYED && r.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                "  " + if (r.source == ReplaySource.LOG) "경기 기록을 읽는 중이에요…" else "경기를 다시 계산하는 중이에요…",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
        r.state == ReportState.PLAYED && r.replayable -> LinkLine(
            "▶ 다시 보기",
            when (r.source) {
                ReplaySource.LOG -> "저장된 경기 기록으로 1판부터 그대로 다시 둬요."
                ReplaySource.RESIM -> "같은 주사위와 같은 선수로 다시 계산해서 1판부터 보여 줘요."
                ReplaySource.SNAPSHOT -> "하이라이트로 남긴 판을 그때의 선수로 다시 둬요."
                ReplaySource.NONE -> null
            },
        ) { onWatch(0) }
        r.note != null -> Text(r.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

/** 판별 점수: a row per game (who moved first, the score, 🎲 yacht and ⭐ bonus marks, drama tags, ▶ from that game). */
@Composable
private fun Games(r: MatchReport, bd: BoardReport, selected: Int, onGame: (Int) -> Unit, onWatch: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Section("🎲 판별 점수")
    val games = bd.games
    if (games == null) {
        Text(if (r.loading) "판별 점수는 경기를 다시 계산하면 보여요." else "판별 점수가 남아 있지 않아요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        return
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            for (g in games) {
                val on = g.index == selected
                Column(
                    Modifier.fillMaxWidth().background(if (on) cs.secondaryContainer.copy(alpha = 0.5f) else cs.surface).clickable { onGame(g.index) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.label, style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        Text(marks(g.yachtA, g.bonusA), style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${g.scoreA ?: "–"} : ${g.scoreB ?: "–"}", fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                        Text(marks(g.yachtB, g.bonusB), style = MaterialTheme.typography.labelSmall)
                        if (r.replayable && !r.loading) {
                            Text("▶", color = cs.primary, modifier = Modifier.padding(start = 8.dp).clickable { onWatch(g.index) })
                        }
                    }
                    if (g.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (t in g.tags.take(4)) {
                                Text(
                                    t.label, style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(cs.secondaryContainer).padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (r.replayable && !r.loading && games.size > 1) {
        Text("판을 누르면 그 판의 칸별 점수와 흐름을 보여 줘요. ▶는 그 판부터 다시 봐요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

private fun marks(yacht: Boolean, bonus: Boolean): String = (if (yacht) "🎲" else "") + (if (bonus) "⭐" else "")

/** The selected game: 칸별 점수 (the better cell bold), 승리 확률 흐름 with its decisive turn, and 평점. */
@Composable
private fun GameDetail(r: MatchReport, bd: BoardReport, g: GameReport) {
    val cs = MaterialTheme.colorScheme
    val boxA = g.boxA
    val boxB = g.boxB
    if (boxA != null && boxB != null) {
        Section("📋 칸별 점수 · ${g.label.substringBefore(" ·")}")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(10.dp)) {
                Row {
                    Text("", Modifier.weight(1.4f))
                    Text(bd.nameA, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = gameColors.me, textAlign = TextAlign.Center, maxLines = 2)
                    Text(bd.nameB, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = gameColors.opp, textAlign = TextAlign.Center, maxLines = 2)
                }
                for (i in 0..Category.ALL.size) {
                    val label = if (i < Category.ALL.size) Category.ALL[i].let { c -> if (c.isUpper) "‘${c.face}’" else c.label } else "보너스"
                    val a = boxA.getOrElse(i) { 0 }
                    val b = boxB.getOrElse(i) { 0 }
                    Row {
                        Text(label, Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall)
                        Text("$a", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = if (a > b) FontWeight.ExtraBold else FontWeight.Normal, textAlign = TextAlign.Center)
                        Text("$b", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = if (b > a) FontWeight.ExtraBold else FontWeight.Normal, textAlign = TextAlign.Center)
                    }
                }
                Row {
                    Text("합계", Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("${g.scoreA ?: boxA.sum()}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("${g.scoreB ?: boxB.sum()}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
    }
    g.curve?.let { curve ->
        Section("📈 승리 확률 흐름")
        val (first, second) = if (g.aFirst) bd.nameA to bd.nameB else bd.nameB to bd.nameA
        WinCurve(curve, first, second, height = 120.dp, mark = g.decisive)
        g.decisive?.let { d -> Text("점선: 승부가 갈린 ${d / 2 + 1}턴 (${if (d % 2 == 0) first else second})", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
    }
    val games = bd.games.orEmpty().filter { it.ratingA != null && it.ratingB != null }
    if (games.isNotEmpty()) {
        Section("⭐ 평점")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text("", Modifier.weight(1.4f))
                    Text(bd.nameA, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
                    Text(bd.nameB, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
                }
                for (x in games) Rating(x.label.substringBefore(" ·"), x.ratingA!!, x.ratingB!!, x.index == g.index)
                if (games.size > 1) Rating("경기 평균", games.map { it.ratingA!! }.average(), games.map { it.ratingB!! }.average(), false, bold = true)
            }
        }
    }
}

@Composable
private fun Rating(label: String, a: Double, b: Double, selected: Boolean, bold: Boolean = false) {
    Row {
        Text(label, Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall, fontWeight = if (bold || selected) FontWeight.Bold else FontWeight.Normal)
        Text("%.1f".format(a), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = if (a > b) FontWeight.ExtraBold else FontWeight.Normal, textAlign = TextAlign.Center)
        Text("%.1f".format(b), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = if (b > a) FontWeight.ExtraBold else FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp).fillMaxWidth())
}

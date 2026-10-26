package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.ClockView
import yacht.app.core.LeagueView
import yacht.app.core.Screen
import yacht.engine.league.Article
import yacht.engine.league.Competitions
import yacht.engine.league.Highlight
import yacht.engine.league.JobKind
import yacht.engine.league.LiveFrame
import yacht.engine.league.SeasonCalendar

private enum class NewsMode(val label: String) {
    NEWS("뉴스"),
    LIVE("라이브"),
    RESULTS("경기 결과"),
    HIGHLIGHTS("하이라이트"),
}

/** 소식: news articles, the live scoreboard, the results feed and the season's highlights. */
@Composable
fun NewsTab(c: AppController, v: LeagueView, results: @Composable () -> Unit) {
    val lg = c.league
    var mode by remember { mutableStateOf(NewsMode.NEWS) }
    var onlyFavourites by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in NewsMode.entries) FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 12.sp) })
            if (mode == NewsMode.NEWS) {
                FilterChip(selected = onlyFavourites, onClick = { onlyFavourites = !onlyFavourites }, label = { Text("★ 응원 AI만", fontSize = 12.sp) })
            }
        }
        when (mode) {
            NewsMode.NEWS -> {
                val favs = lg.favourites
                val shown = if (onlyFavourites) v.news.filter { a -> a.keys.any { it in favs } } else v.news
                Articles(shown, onlyFavourites, v.clock) { if (lg.isAi(it)) lg.detail = it }
            }
            NewsMode.LIVE -> LiveBoardView(c)
            NewsMode.RESULTS -> results()
            NewsMode.HIGHLIGHTS -> Highlights(c, v)
        }
    }
}

@Composable
private fun Articles(all: List<Article>, favourites: Boolean, clock: ClockView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (all.isEmpty()) {
        CenterText(if (favourites) "응원하는 AI의 소식이 아직 없어요. 순위표에서 AI를 눌러 ‘☆ 응원하기’를 골라 보세요." else "리그가 진행되면 우승 경쟁, 이변, 기록, 이적 같은 소식이 기사로 올라와요.")
        return
    }
    // By the week they happened in (newest season and week first, newest first within a week),
    // under a heading per week; news from before weeks were kept ends each season.
    val news = remember(all) {
        all.withIndex().sortedWith(compareByDescending<IndexedValue<Article>> { it.value.season }.thenByDescending { weekOrder(it.value.week) }.thenBy { it.index }).map { it.value }
    }
    // Two articles can read the same (the same lead change twice): number the repeats.
    val keys = remember(news) {
        val seen = HashMap<String, Int>()
        news.map { a ->
            val base = "${a.season}-${a.kind}-${a.headline}-${a.keys}"
            val n = seen.merge(base, 1, Int::plus)!!
            if (n == 1) base else "$base-$n"
        }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(news, key = { i, _ -> keys[i] }) { i, a ->
            val prev = news.getOrNull(i - 1)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (prev == null || prev.season != a.season || prev.week != a.week) WeekHeading(a, clock)
                ArticleCard(a, clock, onOpen)
            }
        }
    }
}

/** Week order inside a season: the close last, news without a week first. */
private fun weekOrder(w: Int): Int = if (w == SeasonCalendar.NONE) -1 else w

@Composable
private fun WeekHeading(a: Article, clock: ClockView?) {
    val cs = MaterialTheme.colorScheme
    val current = clock != null && a.season == clock.season
    val week = weekName(a.week, if (current) clock?.leagueWeeks else null)
    val text = when {
        a.week == SeasonCalendar.NONE -> "시즌 ${a.season} · 주차 기록 없음"
        current && a.week == clock!!.week && !clock.over -> "$week · 이번 주"
        current -> week
        a.week >= SeasonCalendar.CLOSING -> "시즌 ${a.season} 마감"
        else -> "시즌 ${a.season} · $week"
    }
    Column(Modifier.padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.secondary)
        if (a.week == SeasonCalendar.NONE) Text("업데이트 전에 나온 소식이라 몇 주차인지 몰라요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ArticleCard(a: Article, clock: ClockView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().clickable { a.keys.firstOrNull()?.let(onOpen) },
        colors = CardDefaults.cardColors(containerColor = if (a.importance >= 5) cs.tertiaryContainer else cs.surface),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The heading above says the week (and the season when it is not this one).
                Text(
                    listOfNotNull(a.comp?.let { compIcon(it) + " " + it.label }, "시즌 ${a.season}".takeIf { clock == null || a.season != clock.season })
                        .joinToString(" · ").ifEmpty { "📰 리그 소식" },
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                if (a.importance >= 4) Text("🔥".repeat(a.importance - 3), fontSize = 11.sp)
            }
            Text(a.headline, fontWeight = FontWeight.Bold, style = if (a.importance >= 4) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
            Text(a.body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** 하이라이트: the season's most dramatic games and the all-time best; a tap replays one. */
@Composable
private fun Highlights(c: AppController, v: LeagueView) {
    val lg = c.league
    var allTime by remember { mutableStateOf(false) }
    val list = if (allTime) v.allTime else v.highlights
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !allTime, onClick = { allTime = false }, label = { Text("이번 시즌", fontSize = 12.sp) })
                FilterChip(selected = allTime, onClick = { allTime = true }, label = { Text("역대 최고", fontSize = 12.sp) })
            }
        }
        if (list.isEmpty()) item { Text("역전승, 막판 결정, 1점 차 승부처럼 극적인 경기가 쌓여요. 눌러서 다시 볼 수 있어요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(list, key = { "hl-${it.id}" }) { h -> HighlightCard(lg.name(h.a), lg.name(h.b), h) {
            lg.replayHighlight(h) { c.aiPace.stepMs.coerceAtLeast(400L) }
            c.screen = Screen.WATCH
        } }
    }
}

@Composable
private fun HighlightCard(aName: String, bName: String, h: Highlight, onReplay: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable(onClick = onReplay), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시즌 ${h.season} · ${h.caption}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("▶ 다시 보기", style = MaterialTheme.typography.labelMedium, color = cs.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(aName, Modifier.weight(1f), fontWeight = if (h.scoreA > h.scoreB) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${h.scoreA} : ${h.scoreB}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                Text(bName, Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = if (h.scoreB > h.scoreA) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (h.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in h.tags.take(4)) {
                        Text(
                            t.label, style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(cs.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            val curve = h.curveA()
            WinCurve(ByteArray(curve.size) { curve[it].toByte() }, aName, bName, height = 56.dp)
        }
    }
}

/** 라이브 스코어보드: the newest finished fixtures replayed turn by turn, six at a time. */
@Composable
private fun LiveBoardView(c: AppController) {
    val lg = c.league
    DisposableEffect(Unit) {
        lg.watchLive(true)
        onDispose { lg.watchLive(false) }
    }
    val frames = lg.liveFrames
    if (frames.isEmpty()) {
        CenterText(if (lg.running) "경기가 끝나는 대로 여기서 턴마다 다시 보여 줘요…" else "리그를 시작하면 막 끝난 경기들이 여기서 턴마다 다시 펼쳐져요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(frames, key = { "live-${it.slot}" }) { f -> LiveCard(c, f) }
    }
}

@Composable
private fun LiveCard(c: AppController, f: LiveFrame) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    val fx = f.fixture
    val team = fx.kind == JobKind.TEAM
    fun side(k: String) = if (team && (fx.comp == yacht.engine.league.Comp.ALL_STAR || fx.comp == yacht.engine.league.Comp.FAMILY)) Competitions.teamLabel(k) else lg.name(k)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (f.finished) cs.surface else cs.secondaryContainer.copy(alpha = 0.35f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    compIcon(fx.comp) + " " + listOf(fx.comp.label, fx.label).filter { it.isNotBlank() }.distinct().joinToString(" ") +
                        (if (fx.week > 0 && fx.comp != yacht.engine.league.Comp.LEAGUE) " · " + weekName(fx.week, lg.view?.clock?.leagueWeeks) else ""),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                Text(if (f.finished) "종료" else "🔴 LIVE", style = MaterialTheme.typography.labelSmall, color = if (f.finished) cs.onSurfaceVariant else cs.error, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(side(fx.a), Modifier.weight(1f), fontWeight = if (f.winner == 1) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${score(f.scoreA)} : ${score(f.scoreB)}", fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp))
                Text(side(fx.b), Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = if (f.winner == -1) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            for (b in f.boards.take(5)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (team) Text(lg.name(b.a), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${b.game + 1}/${b.games}판 ${b.halfTurn / 2}턴  ${b.totalA}:${b.totalB}",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                        modifier = if (team) Modifier.padding(horizontal = 6.dp) else Modifier.weight(1f),
                    )
                    if (team) Text(lg.name(b.b), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!team) {
                        Box(Modifier.width(90.dp).height(8.dp).clip(CircleShape).background(gameColors.opp.copy(alpha = 0.5f))) {
                            Box(Modifier.fillMaxWidth(b.pA / 100f).height(8.dp).clip(CircleShape).background(gameColors.me))
                        }
                        Text(" ${b.pA}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            LinearProgressIndicator(progress = { f.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun score(x: Double): String = if (x == Math.floor(x)) "%.0f".format(x) else "%.1f".format(x)

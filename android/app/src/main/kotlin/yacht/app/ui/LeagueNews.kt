package yacht.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.ClockView
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.engine.league.Article
import yacht.engine.league.Comp
import yacht.engine.league.SeasonCalendar

private enum class NewsMode(val label: String) {
    NEWS("뉴스"),
    LIVE("라이브"),
    RESULTS("경기 결과"),
    HIGHLIGHTS("하이라이트"),
}

/** 소식: news articles, the live scoreboard, the results feed and the season's highlights. */
@Composable
fun NewsTab(c: AppController, v: LeagueView) {
    val lg = c.league
    val nav = lg.nav
    var mode by remember { mutableStateOf(NewsMode.NEWS) }
    val onlyFavourites = nav.newsFollowed
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in NewsMode.entries) FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 12.sp) })
            if (mode == NewsMode.NEWS) {
                FilterChip(selected = onlyFavourites, onClick = { nav.newsFollowed = !onlyFavourites }, label = { Text("★ 응원만", fontSize = 12.sp) })
            }
        }
        when (mode) {
            NewsMode.NEWS -> {
                val favs = lg.favourites
                // A followed nation's news: its national team's (its code among the keys).
                val shown = if (onlyFavourites) v.news.filter { a -> a.keys.any { it in favs || nationFavourite(it) in favs } } else v.news
                Articles(shown, onlyFavourites, v.clock) { if (lg.isAi(it)) nav.open(Page.Player(it)) }
            }
            NewsMode.LIVE -> LiveBoardView(c)
            NewsMode.RESULTS -> FeedList(lg, v)
            NewsMode.HIGHLIGHTS -> Highlights(c, v)
        }
    }
}

@Composable
private fun Articles(all: List<Article>, favourites: Boolean, clock: ClockView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (all.isEmpty()) {
        CenterText(if (favourites) "응원하는 AI나 국가의 소식이 아직 없어요. 순위표에서 AI를 눌러 ‘☆ 응원하기’를, 기록 › 국가 랭킹에서 나라를 눌러 ‘☆ 응원 국가로’를 골라 보세요." else "리그가 진행되면 우승 경쟁, 이변, 기록, 이적 같은 소식이 기사로 올라와요.")
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
    // Weeks of another season with playoff news are its post-season (that season's length is not kept).
    val playoffWeeks = remember(news) {
        news.filter { it.comp == Comp.TITLE_PLAYOFF || it.comp == Comp.PROMOTION_PLAYOFF }.map { it.season to it.week }.toSet()
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(news, key = { i, _ -> keys[i] }) { i, a ->
            val prev = news.getOrNull(i - 1)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (prev == null || prev.season != a.season || prev.week != a.week) WeekHeading(a, clock, (a.season to a.week) in playoffWeeks)
                ArticleCard(a, clock, onOpen)
            }
        }
    }
}

/** Week order inside a season: the close last, news without a week first. */
private fun weekOrder(w: Int): Int = if (w == SeasonCalendar.NONE) -1 else w

@Composable
private fun WeekHeading(a: Article, clock: ClockView?, playoffs: Boolean) {
    val cs = MaterialTheme.colorScheme
    val current = clock != null && a.season == clock.season
    val week = weekLabel(a.week, a.season, clock, if (playoffs) Comp.TITLE_PLAYOFF else null)
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

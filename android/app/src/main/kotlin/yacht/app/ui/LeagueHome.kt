package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yacht.app.core.AppController
import yacht.app.core.HomeBanner
import yacht.app.core.LeagueController
import yacht.app.core.LeagueTab
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.RecordsView
import yacht.app.core.Screen
import yacht.app.core.SeasonHistoryItem
import yacht.app.core.SeasonRow
import yacht.app.core.TableMode
import yacht.app.core.TablesView
import yacht.engine.explain.Ko
import yacht.engine.league.Article
import yacht.engine.league.CloneProfile
import yacht.engine.league.Ladder
import yacht.engine.league.MatchFormat
import yacht.engine.league.Origin

// 홈: the league's dashboard tab. For now one banner (the empty league, the season just over), the
// league card, the top news and the footer (ranked game, player and AI makers, the clone, the
// counts); the followed players, the big matches and the international stage come later.

/** 홈: the dashboard, scrolled ([onRanked]: the ranked game's picker). */
@Composable
internal fun LeagueHome(c: AppController, v: LeagueView, onRanked: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { HomeBody(c, v, onRanked) }
}

/** The home's content in one column (the screenshot harness measures its height). */
@Composable
internal fun HomeBody(c: AppController, v: LeagueView, onRanked: () -> Unit) {
    val lg = c.league
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val b = homeBanner(lg, v)) {
            HomeBanner.Empty -> EmptyBanner(lg, v)
            is HomeBanner.SeasonEnd -> SeasonEndBanner(b, onDismiss = { lg.seasonNews = null }) {
                lg.nav.go(LeagueTab.RECORDS)
                lg.nav.records = RecordsView.SEASONS
            }
            // The reform banners come with the national leagues.
            else -> {}
        }
        MyLeague(lg, v)
        TopNews(lg, v)
        Footer(c, v, onRanked)
    }
}

/** The one banner the home shows: a season just over (until dismissed), else an empty league's welcome. */
internal fun homeBanner(lg: LeagueController, v: LeagueView): HomeBanner? {
    lg.seasonNews?.let { return HomeBanner.SeasonEnd(it.number, seasonLines(it)) }
    return if (v.matches == 0L) HomeBanner.Empty else null
}

/** A finished season's banner lines: the titles, then the scorers and the promotions. */
private fun seasonLines(h: SeasonHistoryItem): List<String> {
    val titles = h.titles.toMap()
    val first = listOfNotNull(
        h.champions.firstOrNull()?.let { "1부 $it" },
        titles["CL"]?.let { "챔스 $it" },
        titles["CUP"]?.let { "요트컵 $it" },
        titles["MVP"]?.let { "MVP $it" },
    )
    val second = listOfNotNull(
        h.topScorer?.let { "득점왕 $it" },
        h.yachtKing?.let { "요트왕 $it" },
        h.promotedCount.takeIf { it > 0 }?.let { "승격 ${it}명" },
    )
    return listOf(first, second).filter { it.isNotEmpty() }.map { it.joinToString(" · ") }
}

@Composable
private fun SeasonEndBanner(b: HomeBanner.SeasonEnd, onDismiss: () -> Unit, onReview: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer)) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("🏆 ${Ko.i("시즌 ${b.season}")} 끝났어요", fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer, style = MaterialTheme.typography.titleSmall)
            for (line in b.lines) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("닫기") }
                TextButton(onClick = onReview) { Text("결산 보기") }
            }
        }
    }
}

/** An empty league: what it is, how the first season is split, and how to start (from the seed too). */
@Composable
private fun EmptyBanner(lg: LeagueController, v: LeagueView) {
    val cs = MaterialTheme.colorScheme
    val players = v.rows.count { !it.isMe && (lg.config.baseAis || it.origin != Origin.BASE) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AI 리그를 시작해요", fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer, style = MaterialTheme.typography.titleSmall)
            Text(
                "선수 ${"%,d".format(players)}명이 리그와 컵 대회에서 겨뤄요. 첫 시즌에는 레이팅으로 부를 나누고, 경기마다 레이팅이 ±로 바뀌어요.",
                style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer,
            )
            // The first season's match rule (the rules saved now).
            val match = when (lg.config.matchFormat) {
                MatchFormat.SINGLE -> "한 경기는 한 판이에요."
                MatchFormat.BEST_OF_3 -> "한 경기는 같은 주사위로 선공·후공을 바꿔 두 판이고, 승부가 안 나면 세 번째 판을 둬요."
                else -> "한 경기는 같은 주사위로 선공·후공을 바꿔 두 판이에요."
            }
            Text(
                "$match ${Ladder.PLACEMENT}판을 두면 배치가 끝나 티어가 붙어요.",
                style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer,
            )
            if (lg.hasSeed && !lg.running) {
                OutlinedButton(onClick = { lg.loadSeed() }, modifier = Modifier.fillMaxWidth()) { Text("⚡ 미리 돌린 리그로 시작해요") }
            }
            Button(onClick = { lg.start() }, enabled = !lg.running, modifier = Modifier.fillMaxWidth()) { Text("▶ 시작") }
        }
    }
}

/** 내 리그: the first division's top three and up to two followed players elsewhere, with "순위표 전체 ›". */
@Composable
private fun MyLeague(lg: LeagueController, v: LeagueView) {
    val season = v.season ?: return
    val top = season.divisions.firstOrNull() ?: return
    val cs = MaterialTheme.colorScheme
    val favs = lg.favourites
    val tiers = season.tiers.takeIf { it.size == season.divisions.size } ?: yacht.engine.league.Tiers.linear(season.divisions.size)
    // Followed players outside the top three: (division, place, row).
    val followed = season.divisions.withIndex().flatMap { (d, rows) -> rows.withIndex().filter { (i, r) -> r.key in favs && (d > 0 || i >= 3) }.map { (i, r) -> Triple(d, i, r) } }.take(2)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📋 내 리그", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "시즌 ${season.number} · ${tiers.label(0)}" + if (season.matchdays > 0) " · ${minOf(season.currentMatchday + 1, season.matchdays)}/${season.matchdays}라운드" else "",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
            val open: (String) -> Unit = { lg.nav.open(Page.Player(it)) }
            for ((i, r) in top.take(3).withIndex()) TableLine("${i + 1}", r, r.key in favs, open)
            for ((d, i, r) in followed) TableLine("${tiers.label(d)} ${i + 1}위", r, true, open)
            Text(
                "순위표 전체 ›",
                style = MaterialTheme.typography.labelMedium, color = cs.primary,
                modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(6.dp)).clickable {
                    // The card's table: 1부, in full.
                    val nav = lg.nav
                    nav.go(LeagueTab.TABLES)
                    nav.tables = TablesView.LEAGUE
                    nav.tableMode = TableMode.TABLE
                    nav.showDivision(tiers, 0)
                }.padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

/** A row of the league card: its place, the zone stripe, ★ when followed, the name and the points; a tap opens the player ([onOpen]). */
@Composable
private fun TableLine(place: String, r: SeasonRow, followed: Boolean, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(6.dp)).clickable { onOpen(r.key) }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(22.dp).background(if (r.zone > 0) gameColors.good else if (r.zone < 0) cs.error else cs.surface))
        // A top-three place is one digit; a followed player's line says its division ("2부 A조 12위").
        Text(place, Modifier.widthIn(min = 28.dp).padding(start = 6.dp, end = 8.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text((if (followed) "★ " else "") + r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${r.wins}-${r.draws}-${r.losses}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        Text("${r.points}점", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
    }
}

/** 📰 주요 뉴스: two articles, the big ones of the last two weeks first, with "뉴스 전체 ›". */
@Composable
private fun TopNews(lg: LeagueController, v: LeagueView) {
    if (v.news.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val shown = topNews(v)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { lg.nav.open(Page.News) }, verticalAlignment = Alignment.CenterVertically) {
            Text("📰 주요 뉴스", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("뉴스 전체 ›", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.padding(6.dp))
        }
        for (a in shown) ArticleCard(a, v.clock) { if (lg.isAi(it)) lg.nav.open(Page.Player(it)) }
    }
}

/** The home's two articles: importance 4 or more of this week and the last, newest first, then the most important. */
private fun topNews(v: LeagueView): List<Article> {
    val clock = v.clock
    val recent = v.news.filter { a -> clock != null && a.season == clock.season && a.week >= clock.week - 1 && a.importance >= 4 }.take(2)
    return (recent + v.news.filter { it !in recent }.sortedByDescending { it.importance }).take(2)
}

/** The footer: the ranked game and the makers, the clone's invitation, and the league's counts. */
@Composable
private fun Footer(c: AppController, v: LeagueView, onRanked: () -> Unit) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = onRanked, label = { Text("🎮 랭크전") })
            AssistChip(onClick = { c.editingPlayer = null; c.screen = Screen.MAKER }, label = { Text("🧑 선수 만들기") })
            AssistChip(onClick = { c.editingAi = null; c.screen = Screen.BUILDER }, label = { Text("🛠 AI 만들기") })
        }
        if (lg.clone == null) {
            Text(
                "🧬 ‘혼자 두기’로 ${CloneProfile.MIN_GAMES}판 이상 끝내면 내 플레이를 닮은 AI가 리그에 들어와요.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        Text(countsLine(c, v), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

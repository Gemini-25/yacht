@file:OptIn(ExperimentalLayoutApi::class)

package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yacht.app.core.AppController
import yacht.app.core.FollowedSeason
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.ReviewGameView
import yacht.app.core.ReviewPage
import yacht.app.core.Screen
import yacht.engine.league.LeaderStat
import yacht.engine.league.MatchRef
import yacht.engine.league.SeasonReview

// The season review page (Page.Review, re-openable for every kept season; a summary for an older
// one): Season Wrapped's swipeable cards first, then the whole review (UX 4.7): sentences, 우승,
// 수상, 리더, 숫자로 본 시즌, 최고의 경기, 레이팅 상승·하락, 기록 경신, 메타 변화, 리그 랭킹 변화 and 요트협회 결정.

/** [Page.Review]: loads season [Page.Review.season]'s review (again when the season moves on: the amendments) and draws it. */
@Composable
internal fun ReviewPageBody(c: AppController, v: LeagueView, page: Page.Review) {
    val lg = c.league
    val nav = lg.nav
    LaunchedEffect(page.loadKey, v.season?.number) { lg.analytics.loadReview(page.season, lg.center) }
    val r = lg.analytics.review(page)
    if (r == null) {
        CenterText("시즌 ${page.season} 결산을 불러오는 중…")
        return
    }
    ReviewBody(
        r,
        onPlayer = { k -> if (lg.isAi(k)) nav.open(Page.Player(k)) },
        onMatch = { ref -> nav.open(Page.Match(ref)) },
        onWatch = { ref ->
            MatchRef.decode(ref)?.let { m ->
                lg.center.watch(m.whole.encode(), maxOf(0, m.board), maxOf(0, m.game))
                c.screen = Screen.WATCH
            }
        },
        onLeague = { id -> nav.open(Page.League(id)) },
    )
}

/**
 * The review itself (the page draws it; the harness too, with a hand-made review): the Wrapped
 * cards, then every part the review has. A season without its full review (older than the kept
 * ones) shows its summary only.
 */
@Composable
internal fun ReviewBody(
    r: ReviewPage,
    onPlayer: (String) -> Unit = {},
    onMatch: (String) -> Unit = {},
    onWatch: (String) -> Unit = {},
    onLeague: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    fun name(k: String) = r.names[k] ?: k
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val cards = wrappedCards(r)
        if (cards.isNotEmpty()) WrappedPager(r, cards, onPlayer, onMatch, onWatch)
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val review = r.review
            if (review == null) {
                SummaryOnly(r, ::name, onPlayer)
                return@Column
            }
            for (s in review.sentences) Text(s, style = MaterialTheme.typography.bodyMedium)
            if (r.champions.isNotEmpty() || r.world.isNotEmpty()) {
                Part("🏆 우승")
                for (ch in r.champions) Line("${if (ch.tier == 0) "👑" else "🥇"} ${ch.label} ${name(ch.key)}") { onPlayer(ch.key) }
                for (h in r.world) Line("${trophy(h.kind) ?: "🏆"} ${honourLabel(h.kind)} ${name(h.key)}") { onPlayer(h.key) }
            }
            if (review.awards.isNotEmpty()) {
                Part("🏅 수상")
                Text("요트협회가 발표했어요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                for (h in review.awards) Line("${trophy(h.kind) ?: "🏅"} ${honourLabel(h.kind)} ${name(h.key)}") { onPlayer(h.key) }
            }
            if (review.leaders.isNotEmpty()) {
                Part("📊 리더")
                for (stat in SeasonReview.REVIEW_STATS) {
                    val top = review.leadersOf(stat)
                    if (top.isEmpty()) continue
                    Column {
                        Text(stat.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        for ((i, l) in top.withIndex()) {
                            Row(Modifier.fillMaxWidth().clickable { onPlayer(l.key) }.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(16.dp))
                                Text(name(l.key), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (stat == LeaderStat.MVP) RatingText(l.value, digits = 2) else Text(reviewValue(stat, l.value), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            val numbers = review.numbers + listOfNotNull(review.transfers?.let { "해외 이적" to "${it}명" })
            if (numbers.isNotEmpty()) {
                Part("🔢 숫자로 본 시즌")
                for (row in numbers.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, value) in row) {
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (r.best.isNotEmpty()) {
                Part("✨ 최고의 경기")
                for (g in r.best) GameCard(g, onMatch, onWatch)
            }
            if (review.risers.isNotEmpty()) {
                Part("📈 레이팅 상승")
                for (m in review.risers) Line("${name(m.key)} ${"%.0f".format(m.from)} → ${"%.0f".format(m.to)} (${"%+.0f".format(m.delta)})", gameColors.good) { onPlayer(m.key) }
            }
            if (review.fallers.isNotEmpty()) {
                Part("📉 레이팅 하락")
                for (m in review.fallers) Line("${name(m.key)} ${"%.0f".format(m.from)} → ${"%.0f".format(m.to)} (${"%+.0f".format(m.delta)})", cs.error) { onPlayer(m.key) }
            }
            if (review.records.isNotEmpty()) {
                Part("📜 기록 경신")
                for (b in review.records) Line("${b.record.kind.label} · ${b.record.kind.withUnit(b.record.value)} ${name(b.record.holder)}") { onPlayer(b.record.holder) }
            }
            if (review.meta.isNotEmpty()) {
                Part("🧪 메타 변화")
                for (m in review.meta) {
                    Text(m.label + ": " + if (m.percent) "%.0f%% → %.0f%%".format(100 * m.before, 100 * m.after) else "%.1f → %.1f".format(m.before, m.after), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (review.ranking.isNotEmpty()) {
                Part("🌍 리그 랭킹 변화")
                for ((id, change) in review.ranking) {
                    Line("${r.leagueLabels[id] ?: id} ${if (change > 0) "▲$change" else "▼${-change}"}", if (change > 0) gameColors.good else cs.error) { onLeague(id) }
                }
            }
            if (review.federation.isNotEmpty()) {
                Part("🏛 요트협회 결정")
                for (s in review.federation) Text("· $s", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A season with its summary only (older than the reviews kept): its honours, top scorer, records and numbers. */
@Composable
private fun ColumnScope.SummaryOnly(r: ReviewPage, name: (String) -> String, onPlayer: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = r.summary
    Text("이 시즌은 요약만 남아 있어요. 결산은 최근 시즌 몇 개만 보관해요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    if (r.champions.isNotEmpty() || r.world.isNotEmpty()) {
        Part("🏆 우승")
        for (ch in r.champions) Line("${if (ch.tier == 0) "👑" else "🥇"} ${ch.label} ${name(ch.key)}") { onPlayer(ch.key) }
        for (h in r.world) Line("${trophy(h.kind) ?: "🏆"} ${honourLabel(h.kind)} ${name(h.key)}") { onPlayer(h.key) }
    }
    if (s == null) return
    s.topScorer?.let { k -> Line("⚽ 득점왕 ${name(k)} (평균 ${"%.1f".format(s.topScore)}점)") { onPlayer(k) } }
    for (b in s.records) Line("📜 ${b.record.kind.label} · ${b.record.kind.withUnit(b.record.value)} ${name(b.record.holder)}") { onPlayer(b.record.holder) }
    Text("${"%,d".format(s.games)}판 · 평균 ${"%.1f".format(s.avgScore)}점", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
}

@Composable
private fun Part(title: String) = Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified, onClick: () -> Unit) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 1.dp))

/** A review leader's value in words, as its board writes it. */
internal fun reviewValue(stat: LeaderStat, v: Double): String = when (stat) {
    LeaderStat.SCORER -> "%.1f점".format(v)
    LeaderStat.YACHTS -> "%.0f개".format(v)
    LeaderStat.BONUS, LeaderStat.CLUTCH -> "%.0f%%".format(100 * v)
    LeaderStat.MVP -> "%.2f".format(v)
    LeaderStat.LUCK -> "%+.0f점".format(v)
    LeaderStat.STEADY -> "±%.1f점".format(v)
    else -> "%.0f번".format(v)
}

/** A game the review points at: both sides, the score, what it was, and ▶ (or why it cannot be played again). */
@Composable
private fun GameCard(g: ReviewGameView, onMatch: (String) -> Unit, onWatch: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable { onMatch(g.ref) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (g.replay) Text("▶ 다시 보기", style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onWatch(g.ref) })
            }
            Text(listOf(g.a, g.score ?: "vs", g.b).filter { it.isNotEmpty() }.joinToString("  "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (!g.replay) Text("이 경기는 다시 볼 수 없어요. 리포트는 볼 수 있어요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

// ---- Season Wrapped -----------------------------------------------------------------------------------

/** Season Wrapped's cards, in order; only those the review has data for. */
internal enum class WrappedCard { HEROES, TOP_GAME, UPSET, RECORDS, RANKING, FOLLOWED }

/** The cards [r] can fill. */
internal fun wrappedCards(r: ReviewPage): List<WrappedCard> = buildList {
    if (r.champions.isNotEmpty() || r.world.isNotEmpty() || r.review?.awards?.isNotEmpty() == true) add(WrappedCard.HEROES)
    if (r.topRated != null) add(WrappedCard.TOP_GAME)
    if (r.upset != null) add(WrappedCard.UPSET)
    if (r.review?.records?.isNotEmpty() == true) add(WrappedCard.RECORDS)
    if (r.review?.ranking?.isNotEmpty() == true) add(WrappedCard.RANKING)
    if (r.followed.isNotEmpty()) add(WrappedCard.FOLLOWED)
}

/** The swipeable cards with their dots. */
@Composable
private fun WrappedPager(r: ReviewPage, cards: List<WrappedCard>, onPlayer: (String) -> Unit, onMatch: (String) -> Unit, onWatch: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberPagerState { cards.size }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("🎁 시즌 ${r.season} 돌아보기 · 옆으로 넘겨 보세요", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        HorizontalPager(state, contentPadding = PaddingValues(horizontal = 20.dp), pageSpacing = 10.dp, modifier = Modifier.fillMaxWidth()) { i ->
            WrappedCardView(r, cards[i], onPlayer, onMatch, onWatch)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in cards.indices) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == state.currentPage) cs.primary else cs.outlineVariant))
            }
        }
    }
}

/** One Wrapped card ([kind] of [r]), 240 dp tall (the harness draws each alone too). */
@Composable
internal fun WrappedCardView(r: ReviewPage, kind: WrappedCard, onPlayer: (String) -> Unit = {}, onMatch: (String) -> Unit = {}, onWatch: (String) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    fun name(k: String) = r.names[k] ?: k
    val (bg, fg) = when (kind) {
        WrappedCard.HEROES -> cs.primaryContainer to cs.onPrimaryContainer
        WrappedCard.TOP_GAME, WrappedCard.UPSET -> cs.tertiaryContainer to cs.onTertiaryContainer
        else -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    Card(Modifier.fillMaxWidth().height(WRAPPED_HEIGHT), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            @Composable
            fun head(icon: String, title: String) {
                Text(icon, style = MaterialTheme.typography.headlineMedium)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            }
            @Composable
            fun item(text: String, key: String?) = Text(
                text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(enabled = key != null) { key?.let(onPlayer) },
            )
            when (kind) {
                WrappedCard.HEROES -> {
                    head("🏆", "시즌 ${r.season}의 주인공")
                    r.review?.awards?.firstOrNull { it.kind == "MVP" }?.let { item("⭐ MVP ${name(it.key)}", it.key) }
                    for (ch in r.champions.take(3)) item("👑 ${ch.label} ${name(ch.key)}", ch.key)
                    for (h in r.world.take(2)) item("${trophy(h.kind) ?: "🏆"} ${honourLabel(h.kind)} ${name(h.key)}", h.key)
                }
                WrappedCard.TOP_GAME -> r.topRated?.let { g ->
                    head("⭐", "최고 평점 경기")
                    WrappedGame(g, r.review?.topRated?.key?.let(::name), onMatch, onWatch)
                }
                WrappedCard.UPSET -> r.upset?.let { g ->
                    head("😱", "최대 이변")
                    WrappedGame(g, r.review?.upset?.key?.let { "${name(it)}의 승리" }, onMatch, onWatch)
                }
                WrappedCard.RECORDS -> {
                    head("📜", "깨진 기록 ${r.review?.records?.size ?: 0}개")
                    for (b in r.review?.records.orEmpty().take(4)) item("${b.record.kind.label} ${b.record.kind.withUnit(b.record.value)} · ${name(b.record.holder)}", b.record.holder)
                }
                WrappedCard.RANKING -> {
                    head("🌍", "리그 랭킹이 움직였어요")
                    for ((id, change) in r.review?.ranking.orEmpty().take(5)) Text("${r.leagueLabels[id] ?: id} ${if (change > 0) "▲$change" else "▼${-change}"}", style = MaterialTheme.typography.bodyMedium)
                }
                WrappedCard.FOLLOWED -> {
                    head("★", "내가 응원한 선수들")
                    for (f in r.followed.take(4)) FollowedLine(f, name(f.key), onPlayer)
                }
            }
        }
    }
}

@Composable
private fun WrappedGame(g: ReviewGameView, who: String?, onMatch: (String) -> Unit, onWatch: (String) -> Unit) {
    who?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
    Text(listOf(g.a, g.score ?: "vs", g.b).filter { it.isNotEmpty() }.joinToString("  "), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    Text(g.note, style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (g.replay) Text("▶ 다시 보기", fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onWatch(g.ref) }.padding(vertical = 4.dp))
        Text("📋 리포트 ›", fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onMatch(g.ref) }.padding(vertical = 4.dp))
    }
}

@Composable
private fun FollowedLine(f: FollowedSeason, shown: String, onPlayer: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onPlayer(f.key) }) {
        Text(shown, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOf(f.text, f.sub).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A Wrapped card's height. */
private val WRAPPED_HEIGHT = 240.dp

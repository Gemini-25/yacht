package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import yacht.app.core.ClubPage
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.MatchQuery
import yacht.app.core.Page
import yacht.engine.league.Comp
import yacht.engine.league.Nations

// The club page (UX 4): a national club's as it is today, the club world's tabs to come; a legacy
// club league's club stays in LeagueClubs.kt.

/** [Page.Club]: a national club's page ([NationalClubPage]), or a club of a legacy season's club league. */
@Composable
internal fun ClubPageBody(lg: LeagueController, v: LeagueView, page: Page.Club, onOpen: (String) -> Unit) {
    if (v.clubs != null || v.badges.isEmpty()) LegacyClubPage(lg, v.clubs, page.id, onOpen) else NationalClubPage(lg, v, page, onOpen)
}

/**
 * A national club's page (UX 4.3): its standing with the 6 counted members and what each brings,
 * this week, the squad (tier, place, rating, season record, 해외), the members' latest league
 * matches, the club's record (places, titles, money, founded), its moves and its numbers.
 */
@Composable
private fun NationalClubPage(lg: LeagueController, v: LeagueView, page: Page.Club, onOpen: (String) -> Unit) {
    val season = v.season
    LaunchedEffect(page.loadKey, season?.number, (season?.played ?: 0) / CLUB_REFRESH) { lg.worldPages.loadClub(page.id) }
    val p = lg.worldPages.club(page)
    if (p == null) {
        CenterText(if (page.id in v.badges) "클럽 기록을 불러오는 중…" else "이 클럽은 이제 리그에 없어요.")
        return
    }
    ClubPageContent(lg, v, p, onOpen)
}

@Composable
private fun ClubPageContent(lg: LeagueController, v: LeagueView, p: ClubPage, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val nation = Nations.of(p.badge.nation)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClubEmblem(p.badge.emblem, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(p.badge.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        p.badge.city.ifEmpty { null }?.let { c -> nation?.let { "$c · ${it.flag} ${it.short}" } ?: c } ?: nation?.let { "${it.flag} ${it.short}" },
                        p.leagueLabel.ifEmpty { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                p.gone?.let { Text("시즌 $it 종료 후 해체됐어요.", style = MaterialTheme.typography.labelSmall, color = cs.error) }
            }
        }
        p.standing?.let { st ->
            Heading("📊 클럽 순위")
            Text("${p.leagueLabel} ${st.position}위 · ${clubPoints(st.points)}점", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            // 리그 점수 and 수상 보너스 as one stacked bar.
            val total = (st.leaguePart + st.bonus).coerceAtLeast(0.001)
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
                if (st.leaguePart > 0) Box(Modifier.weight((st.leaguePart / total).toFloat()).fillMaxHeight().background(cs.primary))
                if (st.bonus > 0) Box(Modifier.weight((st.bonus / total).toFloat()).fillMaxHeight().background(cs.tertiary))
            }
            Small("리그 점수 ${clubPoints(st.leaguePart)} · 수상 보너스 ${clubPoints(st.bonus)} · 상위 ${st.counted.size}명 합산 (선수 ${st.members}명)")
            val byKey = p.members.associateBy { it.key }
            for (k in st.counted) {
                val m = byKey[k] ?: continue
                Line("${m.shown} ${m.tierLabel.orEmpty()}", m.contribution?.let { "→ ${clubPoints(it)}" }.orEmpty()) { onOpen(k) }
            }
        }
        if (p.week.isNotEmpty()) {
            Heading("📅 이번 주")
            Small("선수단 ${p.week}")
        }
        if (p.members.isNotEmpty()) {
            Heading("👥 선수단 (${p.members.size}명)")
            for (m in p.members) {
                val place = listOfNotNull(m.tierLabel, m.position?.let { "${it}위" }).joinToString(" ")
                Line(
                    (if (m.abroad) "✈️ " else "") + m.shown,
                    listOfNotNull(place.ifEmpty { null }, m.rating?.toString(), m.record.ifEmpty { null }).joinToString(" · "),
                ) { onOpen(m.key) }
            }
            if (p.members.any { it.abroad }) Small("✈️ 해외: 다른 나라 클럽에서 뛰는 선수예요.")
        }
        if (p.recent.isNotEmpty()) {
            Heading("🎲 최근 결과")
            for (m in p.recent) {
                val res = when (m.result) {
                    null -> ""
                    1 -> "승"
                    0 -> "무"
                    else -> "패"
                }
                Row(Modifier.fillMaxWidth().clickable { m.ref?.let { lg.nav.open(Page.Match(it)) } }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(res, Modifier.width(22.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f)) {
                        Text(m.where, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("상대 ", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            ClubMark(m.badge, 12.dp)
                            Text(m.opponentName, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(m.score, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Every match of the squad this season, cup ties and the 클럽 대항전 included.
        LinkLine("선수단 경기 전체", "리그와 대회 경기를 한눈에 봐요.") {
            lg.nav.open(Page.Matches(MatchQuery.OfClub(p.key), "${p.badge.name} 선수단 경기"))
        }
        Heading("📜 클럽 기록")
        Small(
            listOfNotNull(
                "클럽 순위 우승 ${p.titles}회",
                p.cupTitles.takeIf { it > 0 }?.let { "${Comp.CLUB_CUP.label} 우승 ${it}회" },
                "자금 ${p.budget}",
                p.founded.takeIf { it > 0 }?.let { "시즌 $it 창단" },
            ).joinToString(" · "),
        )
        if (p.places.isNotEmpty()) Small(p.places.joinToString(" · ") { (s, place) -> "시즌 $s ${place}위" })
        if (p.moves.isNotEmpty()) {
            Heading("🔁 이적")
            for (m in p.moves) MoveText(lg, m) { id -> v.badges[id]?.name ?: id }
        }
        p.aggregate?.let { a ->
            Heading("📈 클럽 통계 (이번 시즌)")
            Small(
                listOfNotNull(
                    "${a.games}판", "승률 ${"%.1f".format(100 * a.winRate)}%", "평균 ${"%.1f".format(a.avgScore)}점",
                    "요트 ${"%.2f".format(a.yachtRate)}개/판", "보너스 ${"%.0f".format(100 * a.bonusRate)}%",
                    a.avgRating.takeIf { !it.isNaN() }?.let { "평점 ${"%.2f".format(it)}" }, a.avgElo?.let { "평균 레이팅 $it" },
                ).joinToString(" · "),
            )
            if (a.over300 > 0 || a.comebacks > 0 || a.sweeps > 0) Small("300점 이상 ${a.over300}판 · 역전승 ${a.comebacks}판 · 완승 ${a.sweeps}번")
            GroupStyleLine(lg, "c:${p.badge.id}")
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Line(name: String, note: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

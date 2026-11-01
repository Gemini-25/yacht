package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yacht.app.core.ClubRankKind
import yacht.app.core.ClubRankLine
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.PageChips
import yacht.engine.league.ClubRanks
import yacht.engine.league.LeagueIds

// 클럽 랭킹 (UX 4.8): the clubs by value, results, income, fans and health, each list under one
// sentence; the scope (the world or one league's nations) is the page bar's action, which opens
// the league sheet (Sheet.LEAGUE_PICK).

/** [Page.ClubRanking]: loads the scope's lists (again each closed week) and shows the page's list. */
@Composable
internal fun ClubRankingBody(lg: LeagueController, v: LeagueView, page: Page.ClubRanking) {
    val nav = lg.nav
    LaunchedEffect(page.loadKey, v.clock?.season, v.clock?.week) { lg.worldPages.loadClubRanking(page.scope) }
    val r = lg.worldPages.clubRanking(page)
    Column {
        ChipRow(nav.chipState(PageChips.CLUB_RANK), ClubRankKind.entries, page.kind, { it.label }) { nav.replaceTop(page.copy(kind = it)) }
        if (r == null) {
            CenterText("클럽 랭킹을 불러오는 중…")
            return@Column
        }
        val rows = r.lists[page.kind].orEmpty()
        if (rows.isEmpty()) {
            CenterText(
                when {
                    v.season?.national != true -> "클럽 재정은 국가 리그 시즌부터 기록돼요."
                    !lg.config.clubWorld -> "클럽 살림이 꺼져 있어요. 규칙 설정에서 켜면 다음 시즌부터 멈춘 곳에서 이어져요."
                    else -> "클럽 랭킹은 클럽 살림이 시작되면 나와요."
                },
            )
            return@Column
        }
        val cs = MaterialTheme.colorScheme
        val moves = page.kind in r.moved
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Text(ClubRankTexts.sentence(page.kind), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                if (page.kind == ClubRankKind.HEALTH) Text(ClubRankTexts.grades(r.grades), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            }
            items(rows.filter { it.rank <= ROWS }, key = { "crk-${page.kind}-${it.badge.id}" }) { row -> RankRow(row, moves) { nav.open(Page.Club(row.badge.id)) } }
            val below = rows.filter { it.rank > ROWS }
            if (below.isNotEmpty()) {
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                items(below, key = { "crf-${page.kind}-${it.badge.id}" }) { row -> RankRow(row, false) { nav.open(Page.Club(row.badge.id)) } }
            }
        }
    }
}

/** A club's row: its rank, its move (top 10 of a list that keeps them), badge and name (★ when followed), the list's value. */
@Composable
private fun RankRow(r: ClubRankLine, moves: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${r.rank}", Modifier.width(26.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        // Moves only for the top 10 (a row below it has none, which rankMove would read as NEW).
        Text(if (moves && r.rank <= ClubRanks.TOP) rankMove(r.move) else "", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = moveColor(r.move))
        ClubEmblem(r.badge.emblem, 22.dp)
        Text(" " + (if (r.followed) "★ " else "") + r.badge.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun moveColor(move: Int?) = when {
    move == null -> MaterialTheme.colorScheme.tertiary
    move > 0 -> gameColors.good
    move < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The bar action's label (UX 4.8): "🌍 세계 ▾" or "{icon} {league short} ▾". */
internal fun clubRankScope(scope: String?): String = scope?.let { "${LeagueIds.icon(it)} ${LeagueIds.short(it)} ▾" } ?: "🌍 세계 ▾"

/** Rows a list shows before the followed clubs further down. */
private const val ROWS = 50

/** 클럽 랭킹's sentences (UX 4.8). */
internal object ClubRankTexts {
    fun sentence(kind: ClubRankKind): String = when (kind) {
        ClubRankKind.VALUE -> "선수단 가치, 경기장, 시설, 팬, 자금을 더한 클럽 가치예요."
        ClubRankKind.RESULTS -> "최근 5시즌 클럽 순위와 국제 대회 성적을 리그 수준에 맞춰 더했어요."
        ClubRankKind.RICH -> "지난 시즌 수입 순이에요."
        ClubRankKind.FANS -> "팬 수 순이에요."
        ClubRankKind.HEALTH -> "자금과 연봉 비중으로 매긴 재정 건전성이에요."
    }

    /** "A {n} · B {n} · C {n} · D {n} · E {n}". */
    fun grades(counts: List<Int>): String = "ABCDE".mapIndexed { i, g -> "$g ${counts.getOrElse(i) { 0 }}" }.joinToString(" · ")
}

package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.EraView
import yacht.app.core.H2hMatrix
import yacht.app.core.InfoTopic
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.MetaTab
import yacht.app.core.MetaView
import yacht.app.core.Page
import yacht.app.core.PageChips
import yacht.app.core.ShareScope
import yacht.engine.explain.Ko
import yacht.engine.league.LeagueIds
import yacht.engine.league.StyleFamily
import java.util.Locale

// 시대와 메타 (UX 8): 시대·스타일 (the era now, the next candidate, the style shares through the
// seasons with the era strip, the shares now, the eras' timeline, the style matchups, the
// newcomers' fashion and each league's strongest style) and 칸별 메타 (today's meta report,
// unchanged). Until the meta keeps anything (before its first measured close, or a legacy
// league) the page is the meta report alone, as before.

/** [Page.Meta]: the tab row and its tab, or the meta report alone while there are no eras to show. */
@Composable
internal fun MetaPageBody(lg: LeagueController, v: LeagueView, page: Page.Meta) {
    val nav = lg.nav
    val national = v.season?.national == true
    LaunchedEffect(v.season?.number) { lg.loadRecords() }
    LaunchedEffect(national, v.clock?.season, v.clock?.week) { if (national) lg.storyPages.loadMeta() }
    if (!national) {
        MetaReportView(lg.records)
        return
    }
    val meta = lg.storyPages.meta
    if (meta == null) {
        CenterText("시대 기록을 불러오는 중…")
        return
    }
    if (!meta.any) {
        MetaReportView(lg.records)
        return
    }
    Column(Modifier.fillMaxSize()) {
        ChipRow(nav.chipState(PageChips.META), MetaTab.entries, page.tab, { it.label }) { nav.replaceTop(page.copy(tab = it)) }
        Box(Modifier.weight(1f)) {
            when (page.tab) {
                MetaTab.ERAS -> ErasTab(lg, meta, page)
                MetaTab.BOXES -> MetaReportView(lg.records)
            }
        }
    }
}

/** Whether 시대·스타일's folded parts are open (kept while the app runs, as the chip rows' scroll; the harness opens them). */
internal object MetaFolds {
    var matchups by mutableStateOf(false)
    var fashion by mutableStateOf(false)
}

/** The name of 시대·스타일's first block for the first-screen check. */
internal const val ERA_SECTION = "meta_era"

/** 시대·스타일 (UX 8.1). */
@Composable
private fun ErasTab(lg: LeagueController, m: MetaView, page: Page.Meta) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val open = { k: String -> nav.open(Page.Player(k)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1-2. The era now and the next one's candidate.
        Card(Modifier.fillMaxWidth().shotSection(ERA_SECTION), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val era = m.era
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(MetaTexts.now(era, m.chaos), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f, fill = false))
                    InfoMark(nav, InfoTopic.ERAS)
                }
                if (era != null) {
                    Text(MetaTexts.since(era, m.season, m.titles, m.chaos), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                    era.star?.let { (k, name) -> Text("대표: $name ›", style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer, modifier = Modifier.clickable { open(k) }) }
                }
                m.candidate?.let { c -> Text(MetaTexts.candidate(c.family, c.era), style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer, fontWeight = FontWeight.SemiBold) }
            }
        }
        // 3. The shares through the seasons, the era strip above them.
        if (m.shares.isNotEmpty()) {
            Part("스타일 점유율 추이")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in ShareScope.entries) FilterChip(selected = s == page.scope, onClick = { nav.replaceTop(page.copy(scope = s)) }, label = { Text(s.label, fontSize = 12.sp, maxLines = 1) })
            }
            val seasons = m.shares.map { it.season }.sorted()
            EraStrip(m.eras, seasons, dark)
            ShareChart(m.shares, page.scope == ShareScope.ELITE, dark, Modifier.fillMaxWidth())
        }
        // 4. The shares now.
        if (m.now.isNotEmpty()) {
            Part("지금 점유율")
            for (f in m.now) ShareRow(f.family, f.elite, f.all, f.change, dark)
        }
        // 5. The eras' timeline, newest first.
        if (m.eras.isNotEmpty()) {
            Part("시대 연표")
            for (e in m.eras) EraRow(e, dark, open)
        }
        // 6. The style matchups of the last close, folded.
        m.matchups?.let { grid ->
            Fold("스타일 상성 (시즌 ${m.measured})", MetaFolds.matchups) { MetaFolds.matchups = !MetaFolds.matchups }
            if (MetaFolds.matchups) {
                MatchupGrid(grid)
                m.counter?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (m.matchups == null) m.counter?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        // 7. The newcomers' fashion, folded.
        Fold("신인 유행 지수", MetaFolds.fashion) { MetaFolds.fashion = !MetaFolds.fashion }
        if (MetaFolds.fashion) {
            Text(MetaTexts.FASHION, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            val top = (m.weights.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1.0)
            for ((f, w) in m.weights) BarRow(f.label, w, top, "×${String.format(Locale.ROOT, "%.2f", w)}", familyColor(f, dark))
            Text(MetaTexts.cap(m.styleCap), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        // 8. Each best-ranked league's strongest style.
        if (m.leagues.isNotEmpty()) {
            Part("리그별 강세 스타일")
            for ((id, f) in m.leagues) {
                Row(Modifier.fillMaxWidth().clickable { nav.open(Page.League(id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(f, dark)
                    Text("${LeagueIds.icon(id)} ${LeagueIds.label(id)} · ${f.label}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("›", color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Part(title: String) = Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Fold(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(if (open) "접기 ▴" else "펼치기 ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** A family's dot (its colour, always beside its name). */
@Composable
private fun Dot(f: StyleFamily?, dark: Boolean) {
    Box(Modifier.padding(end = 6.dp).size(10.dp).clip(CircleShape).background(familyColor(f, dark)))
}

/** "{dot} {family} · 상위 {e}% · 전체 {a}% · {±change}%p" with two thin bars (the best leagues' 1부, everyone). */
@Composable
private fun ShareRow(f: StyleFamily, elite: Int, all: Int, change: Int?, dark: Boolean) {
    val color = familyColor(f, dark)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(f, dark)
            Text(MetaTexts.share(f, elite, all, change), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PointsBar(elite / 100.0, color, Modifier.weight(1f), height = 4.dp)
            PointsBar(all / 100.0, color.copy(alpha = 0.5f), Modifier.weight(1f), height = 4.dp)
        }
    }
}

/** A row of the eras' timeline: its colour, "시즌 {from}–{to|지금} · {name}", its star under it. */
@Composable
private fun EraRow(e: EraView, dark: Boolean, open: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(familyColor(e.family, dark)))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(MetaTexts.timeline(e), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.star?.let { (k, name) ->
                Text("대표: $name", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { open(k) })
            }
        }
    }
}

/**
 * 스타일 상성: each row family's points a match above expectation against each column family in
 * the last measured close ([MetaView.matchups]; "–" for fewer matches than the counter rule
 * reads), green above and red below; a tap names the pair and its matches. The columns go by
 * each family's first letter (정 요 족 보 신 과 심 괴, all different).
 */
@Composable
private fun ColumnScope.MatchupGrid(m: H2hMatrix) {
    val cs = MaterialTheme.colorScheme
    val n = m.labels.size
    var picked by remember(m) { mutableStateOf<Pair<Int, Int>?>(null) }
    Text(
        picked?.let { (i, j) -> MetaTexts.cell(m.labels[i], m.labels[j], m.score[i][j], m.games[i][j]) } ?: MetaTexts.GRID,
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
    )
    Row {
        Spacer(Modifier.width(GRID_LABEL))
        for (j in 0 until n) Text(m.labels[j].take(1), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
    }
    for (i in 0 until n) {
        Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.labels[i], style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.width(GRID_LABEL))
            for (j in 0 until n) {
                val x = m.score[i][j]
                val bg = when {
                    i == j -> cs.surfaceVariant.copy(alpha = 0.4f)
                    x.isNaN() -> cs.surfaceVariant
                    else -> shareColor(0.5 + x, cs.surfaceVariant)
                }
                Box(
                    Modifier.weight(1f).height(26.dp).padding(1.dp).clip(RoundedCornerShape(3.dp)).background(bg).clickable(enabled = i != j) { picked = i to j },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i != j) Text(if (x.isNaN()) "–" else MetaTexts.excess(x), fontSize = 9.sp, maxLines = 1, color = if (x.isNaN()) cs.onSurfaceVariant else androidx.compose.ui.graphics.Color.Black)
                }
            }
        }
    }
}

/** The matchup grid's row labels' column. */
private val GRID_LABEL = 52.dp

/** 시대와 메타's texts (UX 8, 해요체; ○○파 and "…의 시대" end in a vowel). */
internal object MetaTexts {
    const val NONE = "🧭 아직 뚜렷한 시대가 없어요."
    const val CHAOS = "⚔ 춘추전국 시대예요. 어느 유형도 앞서지 못하고 있어요."
    const val FASHION = "잘나가는 유형일수록 신인 사이에서 조금씩 유행해요. 너무 많아지면 식어요."
    const val GRID = "가로줄 유형이 세로줄 유형을 만났을 때 기대보다 경기당 더 딴 승점이에요. 칸을 누르면 경기 수가 나와요."
    const val EMPTY = "시대 기록은 시즌이 한 번 끝나면 쌓이기 시작해요."

    /** The era card's title: "🧭 지금은 보너스파의 시대예요", 춘추전국's, or none yet. */
    fun now(era: EraView?, chaos: Boolean): String = when {
        era == null -> NONE
        chaos || era.family == null -> CHAOS
        else -> "🧭 지금은 ${Ko.ieyo(era.name)}"
    }

    /** "시즌 4부터 3시즌째예요. 최근 세 시즌 큰 대회 우승 12번 가운데 7번이 보너스파였어요." */
    fun since(era: EraView, season: Int, titles: Pair<Int, Int>?, chaos: Boolean): String {
        val n = (season - era.from + 1).coerceAtLeast(1)
        val head = "시즌 ${era.from}부터 ${n}시즌째예요."
        val f = era.family
        if (chaos || f == null || titles == null || titles.second == 0) return head
        return "$head 최근 세 시즌 큰 대회 우승 ${titles.second}번 가운데 ${titles.first}번이 ${f.label}였어요."
    }

    /** "족보파 · 한 시즌 더 앞서면 족보파의 시대가 열려요". */
    fun candidate(f: StyleFamily, era: String): String = "다음 시대 후보: ${f.label} · 한 시즌 더 앞서면 ${Ko.i(era)} 열려요"

    /** "보너스파 · 상위 24% · 전체 18% · +3%p". */
    fun share(f: StyleFamily, elite: Int, all: Int, change: Int?): String =
        "${f.label} · 상위 $elite% · 전체 $all%" + (change?.let { " · ${if (it > 0) "+$it" else "$it"}%p" } ?: "")

    /** "시즌 4–9 · 보너스파의 시대", "시즌 10–지금 · 두 번째 요트파의 시대". */
    fun timeline(e: EraView): String = "시즌 ${e.from}–${e.to ?: "지금"} · ${e.name}"

    /** "요트협회 규정: 한 시즌 신인 가운데 한 유형은 보통 비율의 1.5배까지예요." ([k]: tenths). */
    fun cap(k: Int): String = "요트협회 규정: 한 시즌 신인 가운데 한 유형은 보통 비율의 ${String.format(Locale.ROOT, "%.1f", k / 10.0)}배까지예요."

    /** A matchup cell's value: "+0.2", "-0.1". */
    fun excess(x: Double): String = String.format(Locale.ROOT, "%+.1f", x).let { if (it == "+0.0" || it == "-0.0") "0" else it }

    /** A tapped cell: "정석파가 요트파를 만나 기대보다 경기당 승점을 0.21점 더 땄어요 (52경기)." ("덜" below expectation). */
    fun cell(row: String, col: String, x: Double, games: Int): String =
        if (x.isNaN()) "$row 대 $col: ${games}경기라 아직 셀 수 없어요."
        else if (kotlin.math.abs(x) < 0.005) "${row}가 ${col}를 만나 기대만큼 승점을 땄어요 (${games}경기)."
        else "${row}가 ${col}를 만나 기대보다 경기당 승점을 ${String.format(Locale.ROOT, "%.2f", kotlin.math.abs(x))}점 ${if (x < 0) "덜" else "더"} 땄어요 (${games}경기)."
}

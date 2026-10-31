package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import yacht.app.core.AggregateStats
import yacht.app.core.AppController
import yacht.app.core.FederationView
import yacht.app.core.LeagueController
import yacht.app.core.LeagueTab
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.ProposalView
import yacht.app.core.RankRow
import yacht.app.core.RankingView
import yacht.app.core.RulesPart
import yacht.app.core.Sheet
import yacht.app.core.TableMode
import yacht.app.core.TablesView
import yacht.engine.league.Confed
import yacht.engine.league.LeagueRank
import yacht.engine.league.Proposal
import java.util.Locale

// The world's pages: a league (`Page.League`), the league ranking (순위 › 랭킹) and the 요트협회
// (`Page.Federation`: its agenda, decisions and awards), and the confederation sheet.

/** "1.234" (a coefficient). */
private fun coef(x: Double): String = String.format(Locale.ROOT, "%.3f", x)

/** "대륙 챔피언스 3장 · 챔스 직행" ("" without places). */
private fun places(r: LeagueRank): String = listOfNotNull(
    "대륙 챔피언스 ${r.cclSlots}장".takeIf { r.cclSlots > 0 },
    "챔스 직행".takeIf { r.clDirect },
).joinToString(" · ")

// ---- 순위 › 랭킹 --------------------------------------------------------------------------------

/** League fixtures between two loads of the ranking while the league plays (its live points move with the cups). */
private const val RANKING_REFRESH = 200

/** 순위 › 랭킹 (UX 4.6): the league ranking loaded on request ([RankingBody]); the national teams' ranking is a page. */
@Composable
internal fun RankingTab(lg: LeagueController) {
    val nav = lg.nav
    val season = lg.view?.season
    LaunchedEffect(season?.number, (season?.played ?: 0) / RANKING_REFRESH) { lg.worldPages.loadRanking() }
    RankingBody(
        lg.worldPages.ranking, nav.rankingConfed,
        onConfed = { nav.sheet = Sheet.CONFED },
        onLeague = { nav.open(Page.League(it)) },
        onNations = { nav.open(Page.Nations) },
        onFederation = { nav.open(Page.Federation) },
    )
}

/**
 * The league ranking (리그 랭킹): its rule, "대륙: 전체 ▾" ([onConfed]), a row a league (rank and
 * change, icon and name, 국제 성적 점수, the last 5 seasons as bars, this season's points so far,
 * next season's places; [onLeague]), the cut-off of a confederation's 대륙 챔피언스 places, then
 * 국가대표 랭킹 and the 요트협회. A legacy season has no ranking.
 */
@Composable
internal fun RankingBody(
    view: RankingView?,
    confed: Confed?,
    onConfed: () -> Unit,
    onLeague: (String) -> Unit,
    onNations: () -> Unit,
    onFederation: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text("리그 랭킹", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    view == null -> "리그 랭킹을 불러오는 중…"
                    view.rows.isEmpty() -> "나라마다 리그가 열리면 국제 대회 성적으로 리그 랭킹을 매겨요. 지금은 모든 선수가 한 리그에서 겨뤄서 리그 랭킹이 없어요."
                    view.byStrength -> "첫 국가 리그 시즌이 끝나면 랭킹이 생겨요. 지금은 상위 5명 평균 레이팅 순이에요. " + RULE
                    else -> RULE
                },
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        if (view != null && view.rows.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "시즌 ${view.season} 랭킹" + if (view.published > 0) " · 시즌 ${view.published}까지의 성적" else "",
                        style = MaterialTheme.typography.labelMedium, color = cs.secondary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onConfed) { Text("대륙: ${confed?.label ?: "전체"} ▾", fontSize = 12.sp) }
                }
            }
            val rows = if (confed == null) view.rows else view.rows.filter { it.rank.confed == confed }
            val top = view.rows.maxOfOrNull { r -> r.rank.seasons.maxOrNull() ?: 0.0 }?.coerceAtLeast(0.001) ?: 1.0
            // In a confederation, the line under its last league with a 대륙 챔피언스 place.
            val cut = if (confed == null) -1 else rows.indexOfLast { it.rank.cclSlots > 0 }
            for ((i, r) in rows.withIndex()) {
                item(key = "rank-${r.rank.id}") { RankLine(r, confed != null, top, view.byStrength) { onLeague(r.rank.id) } }
                if (i == cut && i < rows.lastIndex) {
                    item(key = "cut") {
                        Text("— 여기까지 대륙 챔피언스 출전권 —", style = MaterialTheme.typography.labelSmall, color = cs.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                    }
                }
            }
            if (rows.isEmpty()) item { Text("이 대륙에는 리그가 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        item {
            Column(Modifier.padding(top = 6.dp)) {
                LinkLine("🏳 국가대표 랭킹", "나라별 대표팀의 대회 성적과 전력", onClick = onNations)
                LinkLine("🏛 요트협회", "시즌마다 총회가 대회 규칙을 바꾸자는 안건을 올려요", onClick = onFederation)
            }
        }
    }
}

private const val RULE = "최근 5시즌 챔피언스 리그와 대륙 챔피언스 성적으로 매긴 리그 순위예요. 리그마다 국제 대회에 나간 선수들이 딴 점수를 출전 수로 나눈 값(리그 계수)을 5시즌 동안 더해요. 시즌이 끝날 때마다 새로 매기고, 그 순위로 다음 시즌 대륙 챔피언스 출전권과 챔피언스 리그 직행이 정해져요. 아래 출전권은 이번 시즌 것이에요."

/** One league of the ranking ([inConfed]: its place in its confederation); [top] scales the season bars. */
@Composable
private fun RankLine(r: RankRow, inConfed: Boolean, top: Double, byStrength: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val rank = r.rank
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(if (inConfed) rank.confedRank else rank.rank) + 1}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            RankChange(rank.change.takeIf { !byStrength && !inConfed })
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("${r.icon} ${r.label}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (byStrength) "전력 ${rank.strength.takeIf { !it.isNaN() }?.let { "%.0f".format(it) } ?: "–"}" else "국제 성적 점수 ${coef(rank.coefficient)}",
                    r.live?.let { "이번 시즌 +${coef(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1,
            )
            places(rank).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.secondary, maxLines = 1) }
        }
        SeasonBars(rank.seasons, top)
    }
}

/** The last 5 seasons' values as small bars, the newest on the right ([values] newest first). */
@Composable
private fun SeasonBars(values: List<Double>, top: Double) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.height(24.dp).padding(start = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 4 downTo 0) {
            val x = values.getOrNull(i)
            val h = if (x == null || x <= 0) 2f else (2 + 22 * (x / top)).toFloat().coerceAtMost(24f)
            Box(Modifier.width(6.dp).height(h.dp).clip(RoundedCornerShape(1.dp)).background(if (x == null) cs.surfaceVariant else cs.primary))
        }
    }
}

// ---- the confederation sheet -------------------------------------------------------------------

/** The world pages' sheets over the league screen: the ranking's confederation ([Sheet.CONFED]). */
@Composable
internal fun WorldSheets(c: AppController) {
    val nav = c.league.nav
    if (nav.sheet != Sheet.CONFED) return
    val close = { nav.sheet = Sheet.NONE }
    LeagueSheet("대륙", close) {
        SheetChoice("전체", "모든 리그의 세계 순위", nav.rankingConfed == null) {
            nav.rankingConfed = null
            close()
        }
        for (cf in Confed.entries) {
            SheetChoice("${cf.icon} ${cf.label}", "${cf.label} 리그끼리의 순위와 대륙 챔피언스 출전권", nav.rankingConfed == cf) {
                nav.rankingConfed = cf
                close()
            }
        }
    }
}

// ---- Page.League --------------------------------------------------------------------------------

/**
 * [Page.League] (UX 4.4): its ranking (rank, 국제 성적 점수, the last 5 seasons, places, change),
 * its 1부's top 5 ("순위표 보기 ›"), its club table's top 5, its stars, its champions, the last
 * window's moves abroad and home, each division's numbers and sizes.
 */
@Composable
internal fun LeaguePageBody(lg: LeagueController, v: LeagueView, page: Page.League, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val season = v.season
    LaunchedEffect(page.loadKey, season?.number, (season?.played ?: 0) / CLUB_REFRESH) { lg.worldPages.loadLeague(page.id) }
    val p = lg.worldPages.league(page)
    if (p == null) {
        CenterText(if (season?.leagues?.any { it.id == page.id } == true) "리그 기록을 불러오는 중…" else "이번 시즌에는 이 리그가 없어요.")
        return
    }
    val info = p.info
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${info.icon} ${info.label}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull("${info.nations}개국".takeIf { info.union }, "선수 ${"%,d".format(info.members)}명", "${info.tiers}개 부").joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
        p.rank?.let { r ->
            Heading("🌍 리그 랭킹")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${r.rank + 1}위" + if (r.change != 0) " (${if (r.change > 0) "▲" else "▼"}${kotlin.math.abs(r.change)})" else "", fontWeight = FontWeight.Bold)
                    Small("국제 성적 점수 ${coef(r.coefficient)}")
                    places(r).takeIf { it.isNotEmpty() }?.let { Small("이번 시즌 $it") }
                }
                SeasonBars(r.seasons, (r.seasons.maxOrNull() ?: 0.0).coerceAtLeast(0.001))
            }
        }
        if (p.top.isNotEmpty()) {
            Heading("🏆 1부 순위")
            for ((i, r) in p.top.withIndex()) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(r.key) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(22.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    ClubMark(r.club?.let { v.badges[it] }, 14.dp)
                    Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.wins}-${r.draws}-${r.losses} · ${r.points}점", style = MaterialTheme.typography.labelSmall)
                }
            }
            LinkLine("순위표 보기") {
                lg.nav.go(LeagueTab.TABLES)
                lg.nav.tables = TablesView.LEAGUE
                lg.nav.tableMode = TableMode.TABLE
                lg.nav.selectLeague(info.id)
            }
        }
        if (p.clubs.isNotEmpty()) {
            Heading("🛡 클럽 순위")
            for (c in p.clubs) {
                Row(Modifier.fillMaxWidth().clickable { lg.nav.open(Page.Club(c.badge.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.position}", Modifier.width(22.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    ClubMark(c.badge, 16.dp)
                    Text(c.badge.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${clubPoints(c.points)}점", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (p.stars.isNotEmpty()) {
            Heading("⭐ 스타 선수")
            for (r in p.stars) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(r.key) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClubMark(r.club?.let { v.badges[it] }, 14.dp)
                    Text(r.shown, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(r.divisionLabel, r.rating?.toString()).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        if (p.champions.isNotEmpty()) {
            Heading("👑 역대 우승")
            for ((s, name) in p.champions) Small("시즌 $s · $name")
        }
        if (p.moves.isNotEmpty()) {
            Heading("✈️ 지난 이적 시장 (해외 이적·귀국)")
            for (m in p.moves.take(MOVES)) {
                val from = m.from?.let { v.badges[it]?.name ?: it }
                val to = m.to?.let { v.badges[it]?.name ?: it }
                Small("${lg.name(m.ai)}: $from → $to" + if (m.type == yacht.engine.league.ClubMoveType.HOME) " (귀국)" else "")
            }
        }
        if (p.tiers.isNotEmpty()) {
            Heading("📈 리그 통계 (이번 시즌)")
            for ((label, a) in p.tiers) Small("$label · ${aggregateLine(a)}")
        }
        Heading("🧩 부 구성")
        Small(info.tierLabels.zip(info.sizes).joinToString(" · ") { (t, n) -> "$t ${n}명" })
    }
}

/** The first moves of a league page's window. */
private const val MOVES = 12

/** A group's numbers in one line: players, average rating and score, yachts, bonus rate, sweeps. */
private fun aggregateLine(a: AggregateStats): String = listOfNotNull(
    "${a.members}명",
    a.avgElo?.let { "평균 레이팅 $it" },
    "평균 ${"%.1f".format(a.avgScore)}점",
    "요트 ${"%.2f".format(a.yachtRate)}개/판",
    "보너스 ${"%.0f".format(100 * a.bonusRate)}%",
    "완승 ${a.sweeps}번".takeIf { a.sweeps > 0 },
).joinToString(" · ")

// ---- Page.Federation ----------------------------------------------------------------------------

/** [Page.Federation]: the 요트협회's agenda to vote on, its decisions and the season's awards ([FederationSection]). */
@Composable
internal fun FederationPage(lg: LeagueController, v: LeagueView) {
    LaunchedEffect(v.season?.number) { lg.worldPages.loadFederation() }
    val f = lg.worldPages.federation
    if (f == null) {
        CenterText("요트협회 기록을 불러오는 중…")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
        FederationSection(f, onChoose = { p, yes -> lg.worldPages.chooseProposal(p.season, p.number, yes) }, onReview = { lg.nav.open(Page.Review(it)) }, onRules = { lg.nav.open(Page.Rules(RulesPart.FEDERATION)) })
    }
}

/**
 * The 요트협회 (UX 2, federation part): this season's 총회 안건 with their evidence and 찬성/반대
 * ([onChoose]: true, false, or null to take a vote back; a tap on the chosen one clears it), how
 * the rest are decided, the decisions of the last assembly and every one before, and the awards
 * of the last closed season ([onReview]: its review). Drawn alone by the harness.
 */
@Composable
internal fun FederationSection(f: FederationView, onChoose: (ProposalView, Boolean?) -> Unit, onReview: (Int) -> Unit = {}, onRules: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("🏛 요트협회", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
        Small("요트협회는 시즌마다 총회를 열어 지난 시즌 통계를 보고 대회 규칙을 바꾸자는 안건을 올려요. 주사위 점수 규칙은 절대 바꾸지 않아요.")
        if (!f.enabled) {
            Text("요트협회가 꺼져 있어요. 규칙 설정에서 켜면 다음 시즌부터 총회가 열려요.", style = MaterialTheme.typography.bodySmall, color = cs.error)
            LinkLine("규칙 설정", onClick = onRules)
        }
        Heading("시즌 ${f.nextClose} 총회 안건" + if (f.pending.isNotEmpty()) " · 투표 중" else "")
        if (f.pending.isEmpty()) {
            Small("지금 투표 중인 안건이 없어요. 시즌이 끝나면 총회가 통계를 보고 안건을 2~3개 올려요.")
        }
        for (p in f.pending) ProposalCard(p, onChoose)
        if (f.pending.isNotEmpty()) {
            Small("고르지 않은 안건은 시즌이 끝날 때 선수 위원회 ${COMMITTEE}명이 투표해서 정해요. 가결된 규칙은 그다음 시즌부터 적용돼요.")
        }
        val last = f.history.firstOrNull()?.season
        val recent = f.history.filter { it.season == last }
        if (recent.isNotEmpty()) {
            Heading("지난 총회 결정")
            for (p in recent) Decision(p)
        }
        if (f.awards.isNotEmpty()) {
            Heading("🏅 시상 · 시즌 ${f.awardsSeason}")
            for ((kind, name) in f.awards) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(if (kind == "MVP") "올해의 선수 (MVP)" else honourLabel(kind), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (f.awardsSeason > 0) LinkLine("결산 보기", "시즌 ${f.awardsSeason} 결산을 열어요") { onReview(f.awardsSeason) }
        }
        val older = f.history.filter { it.season != last }
        if (older.isNotEmpty()) {
            Heading("결정 기록")
            for (p in older) Decision(p)
        }
    }
}

/** The 선수 위원회's seats ([Proposal]'s committee vote). */
private const val COMMITTEE = yacht.engine.league.Federation.COMMITTEE

/** A pending proposal: its title and lever, its evidence (one line, "근거 ›" opens it), 찬성/반대. */
@Composable
private fun ProposalCard(p: ProposalView, onChoose: (ProposalView, Boolean?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember(p.season, p.number) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(p.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Small("${p.lever} · 시즌 ${p.appliesFrom}부터 적용")
        Text(
            p.evidence, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { open = !open },
        )
        if (!open) Text("근거 ›", style = MaterialTheme.typography.labelSmall, color = cs.primary, modifier = Modifier.clickable { open = true })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = p.choice == true, onClick = { onChoose(p, if (p.choice == true) null else true) }, label = { Text("찬성", fontSize = 12.sp) })
            FilterChip(selected = p.choice == false, onClick = { onChoose(p, if (p.choice == false) null else false) }, label = { Text("반대", fontSize = 12.sp) })
        }
        if (p.choice != null) Small("투표했어요 · 시즌이 끝날 때까지 바꿀 수 있어요.")
    }
}

/** A decided proposal in one line: "‘안건’ 가결 (내가 찬성)", "부결 (선수 위원회 9명 중 3명 찬성)", "무효 (…)". */
@Composable
private fun Decision(p: ProposalView) {
    val seats = p.yes + p.no
    val committee = if (seats > 0 && p.yes == seats) "선수 위원회 ${seats}명 전원 찬성" else "선수 위원회 ${seats}명 중 ${p.yes}명 찬성"
    val text = when (p.result) {
        Proposal.YES -> "가결 (" + (if (p.by == Proposal.USER) "내가 찬성" else committee) + ") · 시즌 ${p.appliesFrom}부터 적용"
        Proposal.NO -> "부결 (" + (if (p.by == Proposal.USER) "내가 반대" else committee) + ")"
        Proposal.VOID -> "무효 (" + (if (p.by == Proposal.NONE) "투표 없이 끝났어요" else "규칙이 이미 바뀌었어요") + ")"
        else -> "투표 중"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("S${p.season + 1}", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("‘${p.title}’ $text", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

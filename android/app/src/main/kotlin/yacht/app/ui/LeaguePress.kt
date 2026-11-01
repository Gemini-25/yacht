@file:OptIn(ExperimentalLayoutApi::class)

package yacht.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import yacht.app.core.AwardVote
import yacht.app.core.InfoTopic
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.Pick
import yacht.app.core.PowerLine
import yacht.app.core.PredictionView
import yacht.app.core.PressPhase
import yacht.app.core.PressView
import yacht.app.core.ScorecardView
import yacht.app.core.VoteLine
import yacht.app.core.pageOf
import yacht.engine.explain.Ko
import yacht.engine.league.LeagueIds
import yacht.engine.league.Media
import yacht.engine.league.PressAwards
import java.util.Locale

// 기자단 (UX 7): the power ranking, the season's predictions and the press vote on one page,
// ordered by the season's phase (the review weeks put the last vote first, the first weeks the
// predictions, the season after them the power ranking). The league is the page's (the bar's
// "{flag} {league} ▾" changes only this page's league, never the tabs').

/** [Page.Press]: loads 기자단 for its league (the shown league when it names none) and draws its sections in the phase's order. */
@Composable
internal fun PressPageBody(lg: LeagueController, v: LeagueView, page: Page.Press) {
    val league = page.league ?: v.season?.league.orEmpty()
    LaunchedEffect(page.loadKey, league, v.clock?.season, v.clock?.week) { lg.storyPages.loadPress(league) }
    // A league the season has no national league of falls to the best-ranked one.
    val known = v.season?.leagues?.any { it.id == league } == true
    val view = lg.storyPages.press?.takeIf { it.league == league || !known }
    if (view == null) {
        CenterText("기자단 기록을 불러오는 중…")
        return
    }
    if (!view.national) {
        CenterText(PressTexts.LEGACY)
        return
    }
    val open: (String) -> Unit = { link -> pageOf(link)?.takeIf(::linkable)?.let { lg.nav.open(it) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((i, part) in pressSections(view.phase).withIndex()) {
            // The first section is the one the first screen must show (UX 15.2).
            val m = if (i == 0) Modifier.shotSection(PRESS_SECTION) else Modifier
            when (part) {
                PressPart.POWER -> PowerCard(lg, v, view, m, open)
                PressPart.PREDICTION -> PredictionCard(lg, view, view.prediction, view.phase == PressPhase.SEASON, m, open)
                PressPart.SCORECARD -> ScorecardCard(lg, view, view.scorecard, m, open)
                PressPart.VOTES -> VotesCard(lg, view, page, m, open)
            }
        }
    }
}

/** 기자단's sections. */
internal enum class PressPart { POWER, PREDICTION, SCORECARD, VOTES }

/**
 * 기자단's sections in [phase]'s order (UX 7.1): the review weeks the last vote first, the first
 * weeks the predictions first, the season after them the power ranking first. Every phase ends
 * with the sections it does not lead with that still have something to show: the review weeks the
 * new season's predictions and the last power ranking, the first weeks the power ranking (its
 * first ranked week can come before week 6, and the league page's "📈 파워 랭킹" opens this page).
 */
internal fun pressSections(phase: PressPhase): List<PressPart> = when (phase) {
    PressPhase.REVIEW -> listOf(PressPart.VOTES, PressPart.SCORECARD, PressPart.PREDICTION, PressPart.POWER)
    PressPhase.PRESEASON -> listOf(PressPart.PREDICTION, PressPart.SCORECARD, PressPart.VOTES, PressPart.POWER)
    PressPhase.SEASON -> listOf(PressPart.POWER, PressPart.PREDICTION, PressPart.VOTES)
}

/** The name of 기자단's first section for the first-screen check (UX 15.2). */
internal const val PRESS_SECTION = "press_first"

/** The bar's league action on 기자단 ("🇰🇷 한국 ▾"). */
internal fun pressScope(league: String): String = "${LeagueIds.icon(league)} ${LeagueIds.short(league)} ▾"

/** One section: its title (with ⓘ for [topic]), then [content]; "전체 보기 ▾" unfolds what [content] keeps folded. */
@Composable
private fun Section(lg: LeagueController, title: String, topic: InfoTopic?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                if (topic != null) InfoMark(lg.nav, topic)
            }
            content()
        }
    }
}

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** "전체 보기 ▾" / "접기 ▴". */
@Composable
private fun Fold(open: Boolean, label: String = "전체 보기", onToggle: () -> Unit) = Text(
    if (open) "접기 ▴" else "$label ▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.clickable(onClick = onToggle).padding(vertical = 4.dp),
)

// ---- 📈 파워 랭킹 (UX 7.2) --------------------------------------------------------------------------

@Composable
private fun PowerCard(lg: LeagueController, v: LeagueView, view: PressView, modifier: Modifier, open: (String) -> Unit) {
    val last = view.powerSeason != view.season
    val title = when {
        view.powerWeek <= 0 -> "📈 파워 랭킹"
        last -> PressTexts.lastPowerTitle(view.powerSeason, view.powerWeek)
        else -> PressTexts.powerTitle(view.powerWeek)
    }
    Section(lg, title, InfoTopic.POWER, modifier) {
        if (view.power.isEmpty()) {
            Note(PressTexts.notRanked(view.firstWeek))
            return@Section
        }
        view.column?.let { a -> ArticleCard(a, v.clock) { k -> open("p:$k") } }
        var all by remember { mutableStateOf(false) }
        Sub("세계 톱 10")
        for (r in view.power.take(if (all) Media.WORLD_TOP else FOLDED)) PowerRow(r, view.firstRanking || last, where = true) { open("p:${r.key}") }
        if (view.power.size > FOLDED) Fold(all) { all = !all }
        if (view.leagueTop.isNotEmpty()) {
            // One league's: its league goes without saying.
            Sub("${LeagueIds.icon(view.league)} ${LeagueIds.label(view.league)} 톱 5")
            for (r in view.leagueTop) PowerRow(r, view.firstRanking, where = false) { open("p:${r.key}") }
        }
        if (view.weeksTop.isNotEmpty() || view.careerTop.isNotEmpty()) {
            Sub("👑 1위 주수")
            if (view.weeksTop.isNotEmpty()) Note("이번 시즌 · " + view.weeksTop.joinToString(" · ") { (k, n) -> PressTexts.weeks(view.names[k] ?: k, n) })
            if (view.careerTop.isNotEmpty()) Note("통산 파워 랭킹 1위 최다 주 · " + view.careerTop.joinToString(" · ") { (k, n) -> PressTexts.weeks(view.names[k] ?: k, n) })
        }
    }
}

/**
 * A power ranking row (44 dp, more when its second line wraps): rank, move (none in the season's
 * first ranked week), club mark and name, score; its league ([where]) and form under it.
 */
@Composable
private fun PowerRow(r: PowerLine, noMoves: Boolean, where: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${r.rank}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (r.rank == 1) cs.primary else cs.onSurface, textAlign = TextAlign.End, modifier = Modifier.width(22.dp))
        val move = if (noMoves) "" else rankMove(r.move)
        Text(
            move, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp),
            color = when {
                r.move == null -> cs.tertiary
                r.move > 0 -> gameColors.good
                r.move < 0 -> cs.error
                else -> cs.onSurfaceVariant
            },
        )
        ClubMark(r.badge, 14.dp)
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(r.shown + if (r.weeksTop > 0) "  👑${r.weeksTop}" else "", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Line 2's items wrap between them, never inside one ("최근 5경기 3승" stays whole).
            val parts = listOf(if (where) r.where else "", r.sub).filter { it.isNotEmpty() }.flatMap { it.split(" · ") }
            if (parts.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for ((i, p) in parts.withIndex()) {
                        Text((if (i > 0) "· " else "") + p, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        r.score?.let { Text("$it", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
    }
}

// ---- 🔮 시즌 전망 (UX 7.3) --------------------------------------------------------------------------

/** The season's predictions of [view]'s league; [now] (the season after week 5): "전망 vs 지금", the places now first. */
@Composable
private fun PredictionCard(lg: LeagueController, view: PressView, p: PredictionView?, now: Boolean, modifier: Modifier, open: (String) -> Unit) {
    val league = "${LeagueIds.icon(view.league)} ${LeagueIds.label(view.league)}"
    Section(lg, if (now) PressTexts.versusTitle(league) else PressTexts.predictionTitle(league, view.season), InfoTopic.PREDICT, modifier) {
        if (p == null) {
            Note("이 리그는 예측할 선수가 모자라요.")
            return@Section
        }
        val cs = MaterialTheme.colorScheme
        Sub("우승 후보")
        for ((i, f) in p.favourites.withIndex()) {
            Column(Modifier.fillMaxWidth().clickable { open("p:${f.key}") }.padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(PressTexts.favourite(i + 1, f.shown), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(listOfNotNull(f.pct?.let { "$it%" }, f.now?.let { "지금 ${it}위" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                PointsBar((f.pct ?: 0) / 100.0, cs.primary, Modifier.fillMaxWidth().padding(top = 2.dp))
            }
        }
        p.dark?.let { d ->
            Sub("다크호스")
            Line(PressTexts.dark(d.shown, d.predicted, d.pct, d.now)) { open("p:${d.key}") }
        }
        if (p.relegation.isNotEmpty()) {
            Sub("강등 후보")
            for (r in p.relegation) Line(PressTexts.relegation(r.shown, r.pct, r.now)) { open("p:${r.key}") }
        }
        var all by remember { mutableStateOf(false) }
        Fold(all, "예상 순위 vs 지금") { all = !all }
        if (all) for ((i, o) in p.order.withIndex()) Line(PressTexts.order(i + 1, o.shown, o.now)) { open("p:${o.key}") }
    }
}

@Composable
private fun Line(text: String, onClick: () -> Unit) =
    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp))

// ---- 📋 예측 성적표 (UX 7.3) ------------------------------------------------------------------------

@Composable
private fun ScorecardCard(lg: LeagueController, view: PressView, sc: ScorecardView?, modifier: Modifier, open: (String) -> Unit) {
    Section(lg, sc?.let { PressTexts.scorecardTitle(it.season) } ?: "📋 예측 성적표", InfoTopic.PREDICT, modifier) {
        if (sc == null) {
            Note(PressTexts.noScorecard(view.season))
            return@Section
        }
        Text(PressTexts.scorecardLead(sc.hits, sc.leagues), style = MaterialTheme.typography.bodyMedium)
        Note(PressTexts.scorecardLine(sc.top3, sc.leagues, sc.relHits, sc.relPicks, sc.rho))
        sc.over?.let { s -> Line(PressTexts.over(s.shown, s.predicted, s.final)) { open("p:${s.key}") } }
        sc.flop?.let { s -> Line(PressTexts.flop(s.shown, s.predicted, s.final)) { open("p:${s.key}") } }
        var all by remember { mutableStateOf(false) }
        if (sc.perLeague.isNotEmpty() || view.history.size > 1) Fold(all, "리그별 보기") { all = !all }
        if (all) {
            for (l in sc.perLeague) Note(PressTexts.leagueScore("${LeagueIds.icon(l.league)} ${LeagueIds.label(l.league)}", l.hit, l.top3, l.relHits, l.rho))
            if (view.history.size > 1) {
                Sub("시즌별 적중률")
                for ((s, hits, rho) in view.history) Note("시즌 $s · 우승 적중 $hits · 순위 상관 ${PressTexts.rho(rho)}")
            }
        }
    }
}

// ---- 🎙 기자단 투표 (UX 7.4) ------------------------------------------------------------------------

@Composable
private fun VotesCard(lg: LeagueController, view: PressView, page: Page.Press, modifier: Modifier, open: (String) -> Unit) {
    val gd = view.votes.firstOrNull { it.award == PressAwards.GOLDEN_DICE }
    Section(lg, if (view.voteSeason > 0) PressTexts.voteTitle(view.voteSeason, view.jury) else "🎙 기자단 투표", InfoTopic.VOTES, modifier) {
        if (view.votes.isEmpty()) {
            Note(PressTexts.noVote(view.season, first = view.goldenDice.isEmpty()))
            return@Section
        }
        Note(PressTexts.BALLOT)
        gd?.let { GoldenDice(lg, view, it, page, open) }
        for (a in view.votes.filter { it.award != PressAwards.GOLDEN_DICE }) {
            Sub(a.label)
            for ((i, r) in a.rows.withIndex()) VoteRow(i + 1, r, view.jury, shown = true) { open(r.link) }
        }
        if (view.goldenDice.isNotEmpty()) {
            var all by remember { mutableStateOf(false) }
            Fold(all, "역대 골든 다이스") { all = !all }
            if (all) for ((s, name) in view.goldenDice) Note("시즌 $s · $name")
        }
    }
}

/**
 * 골든 다이스's top 5 with "▶ 5위부터 발표" ([Page.Press.reveal]: rows shown from 5th up, a row each
 * [REVEAL_MS], the winner last; -1 shows all). The steps stop when the page is no longer shown.
 */
@Composable
private fun ColumnScope.GoldenDice(lg: LeagueController, view: PressView, a: AwardVote, page: Page.Press, open: (String) -> Unit) {
    val rows = a.rows
    val shown = if (page.reveal < 0) rows.size else page.reveal.coerceAtMost(rows.size)
    LaunchedEffect(page.reveal, rows.size) {
        if (page.reveal in 0 until rows.size) {
            delay(REVEAL_MS)
            lg.nav.replaceTop(page.copy(reveal = page.reveal + 1))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(a.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (rows.size > 1) {
            Text(
                "▶ ${rows.size}위부터 발표", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { lg.nav.replaceTop(page.copy(reveal = 0)) }.padding(vertical = 2.dp),
            )
        }
    }
    for ((i, r) in rows.withIndex()) VoteRow(i + 1, r, view.jury, shown = i >= rows.size - shown) { open(r.link) }
}

/** A vote's row: its place, the name (hidden until [shown]), a bar of its points out of five a ballot, the points and first-place votes. */
@Composable
private fun VoteRow(rank: Int, r: VoteLine, jury: Int, shown: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(40.dp).clickable(enabled = shown, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (rank == 1) cs.primary else cs.onSurfaceVariant, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (shown) r.shown else "· · ·", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (shown) cs.onSurface else cs.onSurfaceVariant)
            PointsBar(if (shown && jury > 0) r.points / (5.0 * jury) else 0.0, if (rank == 1) cs.primary else cs.secondary, Modifier.fillMaxWidth())
        }
        // A column of its own width, so every row's bar runs the same length (the bars compare the points).
        Text(
            if (shown) PressTexts.points(r.points, r.firsts) else "?점", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
            color = if (shown) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.width(VOTE_POINTS),
        )
    }
}

/** A vote row's points column ("142점 · 1위 표 18표" in one line; StoryTextsTest measures it). */
internal val VOTE_POINTS = 130.dp

/** Rows of a folded list (the world's top 10 shows its first five). */
private const val FOLDED = 5

/** A row of "▶ 5위부터 발표" every this long. */
private const val REVEAL_MS = 1_200L

/** 기자단's texts (UX 7, 해요체). */
internal object PressTexts {
    const val LEGACY = "기자단 기록은 국가 리그 시즌부터 쌓여요."
    const val BALLOT = "기자마다 1위에 5점, 2위에 3점, 3위에 1점을 줘요."

    fun powerTitle(week: Int) = "📈 ${week}주차 파워 랭킹"

    fun lastPowerTitle(season: Int, week: Int) = "📈 시즌 $season 마지막 파워 랭킹" + if (week > 0) " (${week}주차)" else ""

    fun notRanked(first: Int) = "파워 랭킹은 리그 경기를 ${Media.MIN_PLAYED}번 이상 치른 1부 선수로 " + (if (first > 0) "${first}주차부터" else "시즌 초반부터") + " 나와요."

    fun weeks(name: String, n: Int) = "$name ${n}주"

    fun predictionTitle(league: String, season: Int) = "🔮 $league 시즌 $season 전망"

    fun versusTitle(league: String) = "🔮 전망 vs 지금 · $league"

    fun favourite(i: Int, name: String) = "$i $name"

    fun dark(name: String, predicted: Int?, pct: Int?, now: Int?) =
        listOfNotNull(name, predicted?.let { "예상 ${it}위" }, pct?.let { "4위 안 $it%" }, now?.let { "지금 ${it}위" }).joinToString(" · ")

    fun relegation(name: String, pct: Int?, now: Int?) = listOfNotNull(name, pct?.let { "$it%" }, now?.let { "지금 ${it}위" }).joinToString(" · ")

    /** "예상 3 · {name} · 지금 5위 ▼2" (= when it holds its place; no place before its first match). */
    fun order(i: Int, name: String, now: Int?) =
        "예상 $i · $name" + (now?.let { n -> " · 지금 ${n}위 " + if (n == i) "=" else rankMove(i - n) } ?: "")

    fun scorecardTitle(season: Int) = "📋 시즌 $season 예측 성적표"

    fun noScorecard(season: Int) = "예측 성적표는 ${Ko.i("시즌 $season")} 끝나면 나와요."

    fun scorecardLead(hits: Int, leagues: Int) = "우승 후보 1순위가 ${leagues}개 리그 가운데 ${hits}곳에서 우승했어요."

    fun scorecardLine(top3: Int, leagues: Int, relHits: Int, relPicks: Int, rho: Int) =
        "우승자가 후보 3명 안에 든 리그 $top3/$leagues · 강등 적중 $relHits/$relPicks · 순위 상관 ${rho(rho)}"

    fun over(name: String, predicted: Int, final: Int) = "가장 크게 예상을 뒤엎은 선수 $name (예상 ${predicted}위 → ${final}위)"

    fun flop(name: String, predicted: Int, final: Int) = "가장 아쉬웠던 선수 $name (예상 ${predicted}위 → ${final}위)"

    fun leagueScore(league: String, hit: Boolean, top3: Boolean, relHits: Int, rho: Int) =
        "$league · " + (if (hit) "✓ 우승 적중" else if (top3) "△ 후보 3명 안" else "✗ 빗나감") + " · 강등 적중 $relHits · 순위 상관 ${rho(rho)}"

    /** Spearman's ρ (× 100 as kept) as a number: "0.42", "-0.10". */
    fun rho(rho: Int): String = String.format(Locale.ROOT, "%.2f", rho / 100.0)

    fun voteTitle(season: Int, jury: Int) = "🎙 시즌 $season 기자단 투표 · 기자 ${jury}명"

    /** No vote to show: before the first ever ([first]), "…{시즌 s가} 끝나면 처음 열려요". */
    fun noVote(season: Int, first: Boolean) = if (first) "기자단 투표는 ${Ko.i("시즌 $season")} 끝나면 처음 열려요." else "기자단 투표는 시즌이 끝나면 열려요."

    fun points(points: Int, firsts: Int) = "${points}점" + if (firsts > 0) " · 1위 표 ${firsts}표" else ""
}

package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import yacht.app.core.AppController
import yacht.app.core.FeedItem
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.ResultsFilter
import yacht.app.core.Screen
import yacht.app.core.Sheet
import yacht.engine.explain.Ko
import yacht.engine.league.Comp
import yacht.engine.league.Competitions
import yacht.engine.league.Highlight
import yacht.engine.league.JobKind
import yacht.engine.league.LiveBoard
import yacht.engine.league.LiveFrame
import yacht.engine.league.byTeams

/** A match's report key ("season:compCode:id", the text form of a match ref); null for a friendly, which has none. */
internal fun matchKey(season: Int, comp: Comp, id: Int): String? =
    if (comp == Comp.FRIENDLY || comp == Comp.LEGEND || comp == Comp.LAB || id < 0) null else "$season:${comp.code}:$id"

/** True when [k] (a player, or a nation's team) is followed. */
internal fun followed(k: String, favourites: Set<String>): Boolean = k in favourites || nationFavourite(k) in favourites

/**
 * Watches a featured game (a top player against a rival, played afresh as a friendly): the league
 * screen's old 👀 경기 관전, kept on 경기 › 중계 until the broadcast channel replaces it.
 */
internal fun watchFeatured(c: AppController) {
    val lg = c.league
    val (a, b) = lg.featured(java.util.Random()) ?: return
    lg.startWatch(a, b) { c.aiPace.stepMs.coerceAtLeast(400L) }
    c.screen = Screen.WATCH
}

/**
 * 경기 › 결과: "필터 ▾" ([Sheet.RESULTS_FILTER]), the matches being played (folded to one line,
 * each opening its report), and the results, newest first.
 */
@Composable
internal fun FeedList(lg: LeagueController, v: LeagueView) {
    val cs = MaterialTheme.colorScheme
    val nav = lg.nav
    val filter = nav.results
    val favs = lg.favourites
    val shown = remember(v.feed, filter, favs) { v.feed.filter { resultShown(filter, it.comp, it.a, it.b, favs) } }
    // The matches in play under the same filter (★ 응원: only the followed players' own).
    val live = remember(v.live, filter, favs) { v.live.filter { resultShown(filter, it.comp, it.a, it.b, favs) } }
    var liveOpen by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { FilterLine("필터: ${filter.label}") { nav.sheet = Sheet.RESULTS_FILTER } }
        if (live.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { liveOpen = !liveOpen }, verticalAlignment = Alignment.CenterVertically) {
                            Text("⚔️ 지금 경기 중 ${live.size}", fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text(if (liveOpen) "접기" else "펼치기", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                        }
                        if (liveOpen) for (m in live) {
                            val team = m.comp.byTeams
                            fun side(k: String) = if (team) Competitions.teamLabel(k) else lg.name(k)
                            val key = v.clock?.let { matchKey(it.season, m.comp, m.id) }
                            Text(
                                "${stageName(m.comp, m.label, short = true)}  ${side(m.a)}  vs  ${side(m.b)}" + if (m.firstTo > 0) " · ${m.firstTo}선승" else "",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable(enabled = key != null) { key?.let { nav.open(Page.Match(it)) } }.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        if (v.feed.isEmpty()) {
            item { Text("아직 끝난 경기가 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        } else if (shown.isEmpty()) {
            item {
                Text(
                    if (filter == ResultsFilter.FOLLOWED) "응원하는 선수의 최근 경기 결과가 아직 없어요. 선수를 눌러 ‘☆ 응원하기’를 골라 보세요." else "고른 종류의 경기 결과가 아직 없어요.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
        }
        val clock = v.clock
        // 완승 보너스 only for this season's results (an older season's rule is not known here).
        val bonusSeason = v.season?.takeIf { it.format.sweepBonus }?.number
        items(shown, key = { "${it.time}-${it.a}-${it.b}" }) { FeedCard(lg, it, clock, bonusSeason != null && it.season == bonusSeason) }
    }
}

/** True when [filter] keeps a match of [c] (null: a result saved before the competitions were kept) between [a] and [b]. */
internal fun resultShown(filter: ResultsFilter, c: Comp?, a: String, b: String, favourites: Set<String>): Boolean = when (filter) {
    ResultsFilter.ALL -> true
    ResultsFilter.FOLLOWED -> followed(a, favourites) || followed(b, favourites)
    ResultsFilter.LEAGUE -> c == Comp.LEAGUE
    ResultsFilter.COMPS -> c != null && c != Comp.LEAGUE && c != Comp.FRIENDLY && c != Comp.LEGEND && c != Comp.LAB
    ResultsFilter.FRIENDLY -> c == Comp.FRIENDLY || c == Comp.LEGEND
}

/** A sub-view's filter line: what it shows now, with ▾ to change it. */
@Composable
internal fun FilterLine(text: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$text ▾", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

/** Where a result was played: "🏆 요트컵 64강 · 14주차", "📋 리그 12라운드"; null for results saved before. */
private fun whereLine(m: FeedItem, clock: yacht.app.core.ClockView?): String? {
    val c = m.comp ?: return null
    if (c == Comp.FRIENDLY) {
        return when (m.label) {
            "" -> "🤝 친선 경기"
            LeagueController.RANKED_LABEL -> "🎮 ${m.label}"
            else -> "🤝 ${m.label}"
        }
    }
    val week = if (c == Comp.LEAGUE || m.week <= 0) "" else weekLabel(m.week, m.season, clock, c).let { if (it.isEmpty()) "" else " · $it" }
    return stageName(c, m.label) + week
}

/**
 * One result. A series of more than two games shows the games won ("3-1", a drawn game half)
 * with the scores below; [bonus]: this season's rule gives a 2-0 sweep in a league or group
 * match the bonus point (완승 +1).
 */
@Composable
private fun FeedCard(lg: LeagueController, m: FeedItem, clock: yacht.app.core.ClockView?, bonus: Boolean) {
    val cs = MaterialTheme.colorScheme
    val aPts: Int = m.games.map { if (it.a > it.b) 2 else if (it.a == it.b) 1 else 0 }.sum()
    val bPts = m.games.size * 2 - aPts
    val series = m.games.size > 2
    // A league round or a champions league group match (one on one): "A조 3차전".
    val table = m.comp == Comp.LEAGUE || (m.comp == Comp.CHAMPIONS && m.label.endsWith("차전"))
    val sweep = bonus && table && m.games.size == 2
    val aSweep = sweep && yacht.engine.league.TieRule.sweptByA(m.games)
    val bSweep = sweep && yacht.engine.league.TieRule.sweptByB(m.games)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            whereLine(m, clock)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeedSide(m.aName, m.deltaA, aPts > bPts, Modifier.weight(1f), TextAlign.Start, if (aSweep) "완승 +1" else null)
                Text(
                    if (series) "${halfText(aPts / 2.0)}-${halfText(bPts / 2.0)}" else m.games.joinToString("  ") { "${it.a}:${it.b}" },
                    style = if (series) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                FeedSide(m.bName, m.deltaB, bPts > aPts, Modifier.weight(1f), TextAlign.End, if (bSweep) "완승 +1" else null)
            }
            if (series) {
                Text(
                    m.games.joinToString("  ") { "${it.a}:${it.b}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            for (n in m.notes) noteText(lg, n, m.games.size)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.secondary, fontWeight = FontWeight.Bold) }
            m.curve?.let { cv -> WinCurve(ByteArray(cv.size) { cv[it].toByte() }, "", "", height = 40.dp) }
        }
    }
}

@Composable
private fun FeedSide(name: String, delta: Int, won: Boolean, modifier: Modifier, align: TextAlign, tag: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = if (won) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
        if (delta != 0 || tag != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The tag sits on the inner side, next to the score.
                if (tag != null && align == TextAlign.End) SweepTag(tag)
                if (delta != 0) Text("%+d".format(delta), style = MaterialTheme.typography.labelSmall, color = if (delta > 0) gameColors.good else cs.error)
                if (tag != null && align != TextAlign.End) SweepTag(tag)
            }
        }
    }
}

/** A feed note ("kind|key|value") as a headline; [games] the games of the match. */
private fun noteText(lg: LeagueController, note: String, games: Int): String? {
    val p = note.split('|')
    if (p.size < 3) return null
    val who = lg.name(p[1])
    return when (p[0]) {
        "승급" -> "🎉 $who, ${p[2]} 승급!"
        "강등" -> "📉 $who, ${Ko.ro(p[2])} 강등"
        "배치 완료" -> "🎓 $who 배치 완료: ${p[2]}"
        "연승" -> "🔥 $who ${p[2]}연승 중!"
        "연패" -> "🥶 $who ${p[2]}연패…"
        "대기록" -> "🏅 $who ${p[2]}점 대기록!"
        "이변" -> "😱 이변! $who, 레이팅 ${p[2]}점 높은 상대를 " + when {
            games <= 1 -> "이겼어요"
            games == 2 -> "두 판 다 이겼어요"
            else -> "${games}판 모두 이겼어요"
        }
        else -> null
    }
}

/**
 * 경기 › 하이라이트: the season's most dramatic games or the all-time best, "필터 ▾"
 * ([Sheet.HIGHLIGHT_FILTER]); a card opens its match report, where it is played again.
 */
@Composable
internal fun Highlights(c: AppController, v: LeagueView) {
    val lg = c.league
    val nav = lg.nav
    val f = nav.highlights
    val favs = lg.favourites
    val list = (if (f.allTime) v.allTime else v.highlights).let { all -> if (f.followed) all.filter { followed(it.a, favs) || followed(it.b, favs) } else all }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { FilterLine("필터: " + (if (f.allTime) "역대 최고" else "이번 시즌") + if (f.followed) " · ★ 응원" else "") { nav.sheet = Sheet.HIGHLIGHT_FILTER } }
        if (list.isEmpty()) {
            item {
                Text(
                    if (f.followed) "응원하는 선수의 하이라이트가 아직 없어요." else "역전승, 막판 결정, 1점 차 승부처럼 극적인 경기가 쌓여요. 눌러서 다시 볼 수 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(list, key = { "hl-${it.id}" }) { h -> HighlightCard(lg.name(h.a), lg.name(h.b), h, "리포트 ›") { nav.open(Page.Match(h.id)) } }
    }
}

/** A highlight: where, the score, its drama tags and its win curve ([action]: what a tap does, at the top right). */
@Composable
internal fun HighlightCard(aName: String, bName: String, h: Highlight, action: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시즌 ${h.season} · ${h.caption}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(action, style = MaterialTheme.typography.labelMedium, color = cs.primary)
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

/**
 * 경기 › 중계: for now 👀 경기 관전 (a featured friendly to watch) and the 스코어보드, the newest
 * finished fixtures replayed turn by turn, six at a time; a card opens its match report.
 */
@Composable
internal fun LiveBoardView(c: AppController) {
    val lg = c.league
    DisposableEffect(Unit) {
        lg.watchLive(true)
        onDispose { lg.watchLive(false) }
    }
    val frames = lg.liveFrames
    val season = lg.view?.clock?.season
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Until the broadcast channel comes: a featured game to watch turn by turn, as the old 👀 경기 관전.
        item { LinkLine("👀 경기 관전", "상위 선수와 라이벌이 새로 두는 친선 경기를 턴마다 지켜봐요.") { watchFeatured(c) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("📺 스코어보드", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("막 끝난 경기를 턴마다 다시 보여 줘요. 누르면 경기 리포트가 열려요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (frames.isEmpty()) {
            item {
                Text(
                    if (lg.running) "경기가 끝나는 대로 여기서 턴마다 다시 보여 줘요…" else "리그를 시작하면 막 끝난 경기들이 여기서 턴마다 다시 펼쳐져요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(frames, key = { "live-${it.slot}" }) { f ->
            val key = season?.let { matchKey(it, f.fixture.comp, f.fixture.id) }
            LiveCard(c, f, key?.let { k -> { lg.nav.open(Page.Match(k)) } })
        }
    }
}

@Composable
private fun LiveCard(c: AppController, f: LiveFrame, onOpen: (() -> Unit)?) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    val fx = f.fixture
    val team = fx.kind == JobKind.TEAM
    // A series (선승제): games won "2-1", and no "/N판" (it would tell how long the finished series ran).
    val series = fx.firstTo > 0
    fun side(k: String) = if (team && fx.comp.byTeams) Competitions.teamLabel(k) else lg.name(k)
    Card(Modifier.fillMaxWidth().clickable(enabled = onOpen != null) { onOpen?.invoke() }, colors = CardDefaults.cardColors(containerColor = if (f.finished) cs.surface else cs.secondaryContainer.copy(alpha = 0.35f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // The board may still hold last season's matches: their week is named without this season's length.
                    compIcon(fx.comp) + " " + listOf(fx.comp.label, fx.label).filter { it.isNotBlank() }.distinct().joinToString(" ") +
                        (if (series) " · ${fx.firstTo}선승" else "") +
                        (if (fx.week > 0 && fx.comp != Comp.LEAGUE) " · " + weekLabel(fx.week, -1, null, fx.comp) else ""),
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                Text(if (f.finished) "종료" else "🔴 LIVE", style = MaterialTheme.typography.labelSmall, color = if (f.finished) cs.onSurfaceVariant else cs.error, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(side(fx.a), Modifier.weight(1f), fontWeight = if (f.winner == 1) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (series) "${halfText(f.scoreA)}-${halfText(f.scoreB)}" else "${halfText(f.scoreA)} : ${halfText(f.scoreB)}", fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp))
                Text(side(fx.b), Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = if (f.winner == -1) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            for (b in f.boards.take(5)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (team) Text(lg.name(b.a), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (series) "${b.game + 1}판째" else "${b.game + 1}/${b.games}판") + " ${b.halfTurn / 2}턴  ${b.totalA}:${b.totalB}",
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
            // A series' bar runs over the game in play: over the whole tie it would tell the length too.
            val bar = if (series && !f.finished) ((f.boards.firstOrNull()?.halfTurn ?: 0) / (LiveBoard.GAME_STEPS - 1).toFloat()).coerceIn(0f, 1f) else f.progress.toFloat()
            LinearProgressIndicator(progress = { bar }, modifier = Modifier.fillMaxWidth())
        }
    }
}

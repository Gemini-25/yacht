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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.FeedItem
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.ResultsFilter
import yacht.app.core.Screen
import yacht.engine.explain.Ko
import yacht.engine.league.Comp
import yacht.engine.league.Competitions
import yacht.engine.league.Highlight
import yacht.engine.league.JobKind
import yacht.engine.league.LiveBoard
import yacht.engine.league.LiveFrame
import yacht.engine.league.byTeams

/** Watches a featured game (a top AI against a rival). */
internal fun watchFeatured(c: AppController) {
    val lg = c.league
    val (a, b) = lg.featured(java.util.Random()) ?: return
    lg.startWatch(a, b) { c.aiPace.stepMs.coerceAtLeast(400L) }
    c.screen = Screen.WATCH
}

@Composable
internal fun FeedList(lg: LeagueController, v: LeagueView) {
    val cs = MaterialTheme.colorScheme
    val kind = FeedKind.of(lg.nav.results)
    val shown = remember(v.feed, kind) { v.feed.filter { kind.matches(it.comp) } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in FeedKind.entries) FilterChip(selected = kind == k, onClick = { lg.nav.results = k.filter }, label = { Text(k.label, fontSize = 12.sp) })
            }
        }
        if (v.live.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("⚔️ 지금 경기 중", fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                        for (m in v.live) {
                            val team = m.comp.byTeams
                            fun side(k: String) = if (team) yacht.engine.league.Competitions.teamLabel(k) else lg.name(k)
                            Text(
                                "${stageName(m.comp, m.label, short = true)}  ${side(m.a)}  vs  ${side(m.b)}" + if (m.firstTo > 0) " · ${m.firstTo}선승" else "",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (v.feed.isEmpty()) item { Text("아직 끝난 경기가 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        else if (shown.isEmpty()) item { Text("고른 종류의 경기 결과가 아직 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        val clock = v.clock
        // 완승 보너스 only for this season's results (an older season's rule is not known here).
        val bonusSeason = v.season?.takeIf { it.format.sweepBonus }?.number
        items(shown, key = { "${it.time}-${it.a}-${it.b}" }) { FeedCard(lg, it, clock, bonusSeason != null && it.season == bonusSeason) }
    }
}

/** Which results the feed shows: the chips of [ResultsFilter] the feed has ([filter]). */
private enum class FeedKind(val label: String, val filter: ResultsFilter) {
    ALL("전체", ResultsFilter.ALL),
    LEAGUE("📋 리그", ResultsFilter.LEAGUE),
    COMPS("🏆 대회", ResultsFilter.COMPS),
    FRIENDLY("🤝 친선", ResultsFilter.FRIENDLY);

    fun matches(c: Comp?): Boolean = when (this) {
        ALL -> true
        LEAGUE -> c == Comp.LEAGUE
        COMPS -> c != null && c != Comp.LEAGUE && c != Comp.FRIENDLY && c != Comp.LEGEND && c != Comp.LAB
        FRIENDLY -> c == Comp.FRIENDLY || c == Comp.LEGEND
    }

    companion object {
        /** [f]'s chip (★ 응원 has none here: every result). */
        fun of(f: ResultsFilter): FeedKind = entries.firstOrNull { it.filter == f } ?: ALL
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

/** 하이라이트: the season's most dramatic games and the all-time best; a tap replays one. */
@Composable
internal fun Highlights(c: AppController, v: LeagueView) {
    val lg = c.league
    val nav = lg.nav
    val allTime = nav.highlights.allTime
    val list = if (allTime) v.allTime else v.highlights
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !allTime, onClick = { nav.highlights = nav.highlights.copy(allTime = false) }, label = { Text("이번 시즌", fontSize = 12.sp) })
                FilterChip(selected = allTime, onClick = { nav.highlights = nav.highlights.copy(allTime = true) }, label = { Text("역대 최고", fontSize = 12.sp) })
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
internal fun LiveBoardView(c: AppController) {
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
    // A series (선승제): games won "2-1", and no "/N판" (it would tell how long the finished series ran).
    val series = fx.firstTo > 0
    fun side(k: String) = if (team && fx.comp.byTeams) Competitions.teamLabel(k) else lg.name(k)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (f.finished) cs.surface else cs.secondaryContainer.copy(alpha = 0.35f))) {
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

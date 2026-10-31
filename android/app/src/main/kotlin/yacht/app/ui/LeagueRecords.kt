package yacht.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.ProfileView
import yacht.app.core.RecordsView
import yacht.app.core.SeasonHistoryItem
import yacht.engine.league.Family
import yacht.engine.league.LeagueRecord
import yacht.engine.league.Origin
import yacht.engine.league.RecordGroup
import yacht.engine.game.label

/**
 * The profile part of an AI's page: origin, family tree and honours (loaded on open); [onOpen]
 * opens another player's page by key.
 */
@Composable
fun ProfileSection(lg: LeagueController, key: String, onOpen: (String) -> Unit) {
    LaunchedEffect(key) { lg.loadProfile(key) }
    val p = lg.profile?.takeIf { it.key == key } ?: return
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            listOfNotNull(
                p.origin.label,
                p.hometown.takeIf { it.isNotEmpty() }?.let { "$it 출신" },
                p.born?.let { "시즌 $it 데뷔" },
                p.retired?.let { "시즌 $it 은퇴" },
                if (p.legend) "🏛 레전드" else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = cs.secondary,
        )
        p.identity?.let { IdentityPart(it, alive = p.retired == null, onOpen = onOpen) }
        p.genes?.takeIf { p.identity?.style == null && (p.origin == Origin.GENERATED || p.origin == Origin.EVOLVED || p.origin == Origin.CREATED) }?.let {
            Text("스타일: $it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Honours(p)
        StatsPart(p)
        Growth(p)
        Genealogy(p)
    }
}

/** This season's and career numbers beyond the ladder's, and the boxes it is best and worst at. */
@Composable
private fun StatsPart(p: ProfileView) {
    val st = p.career ?: return
    if (st.games == 0) return
    val cs = MaterialTheme.colorScheme
    val season = p.season?.takeIf { it.games > 0 }
    Text("📈 기록 (통산${if (season != null) " · 이번 시즌" else ""})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    @Composable
    fun line(label: String, career: String, now: String?) =
        Text("$label  $career" + (now?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    fun rating(a: yacht.engine.league.AiStats) = if (a.avgRating.isNaN()) "–" else "%.2f".format(a.avgRating)
    line("평균 평점", rating(st), season?.let { rating(it) })
    line("경기(매치) 승-무-패", "${st.matchWins}-${st.matchDraws}-${st.matchLosses}", season?.let { "${it.matchWins}-${it.matchDraws}-${it.matchLosses}" })
    line("역전승 / 역전패", "${st.comebacks} / ${st.blownLeads}", season?.let { "${it.comebacks} / ${it.blownLeads}" })
    line("5점 차 이내 승 / 패", "${st.closeWins} / ${st.closeLosses}", season?.let { "${it.closeWins} / ${it.closeLosses}" })
    line("대어 사냥 (100점 이상 위)", "${st.giantKills}", season?.let { "${it.giantKills}" })
    line("300점 이상", "${st.over300}", season?.let { "${it.over300}" })
    if (st.derbyWins + st.derbyLosses > 0) line("더비 (같은 고향) 승/패", "${st.derbyWins} / ${st.derbyLosses}", null)
    // 주무기 / 약점: its box averages against the league's.
    val league = p.league
    if (league != null && st.carded >= 20) {
        val diffs = yacht.engine.Category.ALL.mapNotNull { c ->
            val mine = st.boxAverage(c)
            val avg = league.boxAverage(c)
            if (mine.isNaN() || avg.isNaN()) null else c to mine - avg
        }
        val strong = diffs.sortedByDescending { it.second }.take(2).filter { it.second > 0.3 }
        val weak = diffs.sortedBy { it.second }.take(2).filter { it.second < -0.3 }
        if (strong.isNotEmpty()) Text("💪 주무기: " + strong.joinToString(", ") { (c, d) -> "${c.label} %+.1f".format(d) }, style = MaterialTheme.typography.labelSmall, color = gameColors.good)
        if (weak.isNotEmpty()) Text("🩹 약점: " + weak.joinToString(", ") { (c, d) -> "${c.label} %+.1f".format(d) }, style = MaterialTheme.typography.labelSmall, color = cs.error)
    }
}

/** 성장 곡선: its rating at the end of each season, title seasons ringed, and the season lines. */
@Composable
private fun Growth(p: ProfileView) {
    val lines = p.lines
    if (lines.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Text("🌱 성장 곡선", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    // Seasons without a rating (the AI had none at the end) are left out of the curve.
    val rated = lines.filter { !it.rating.isNaN() }
    if (rated.size >= 2) {
        val titled = p.honours.filter { trophy(it.kind) == "👑" || trophy(it.kind) == "🥇" || trophy(it.kind) == "🏆" }.map { it.season }.toSet()
        LineChart(
            rated.map { it.rating }, labels = "시즌 ${rated.first().season}" to "시즌 ${rated.last().season}",
            mark = rated.withIndex().filter { it.value.season in titled }.map { it.index }.toSet(),
        )
    }
    for (l in lines.takeLast(8).reversed()) {
        Text(
            "시즌 ${l.season} · ${l.division + 1}부 ${l.position}위 · ${l.wins}승 ${l.draws}무 ${l.losses}패 · 레이팅 ${if (l.rating.isNaN()) "–" else "%.0f".format(l.rating)}" +
                if (l.avgRating.isNaN()) "" else " · 평점 ${"%.2f".format(l.avgRating)}",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    }
}

/** 시즌 결산: shown once when a season ends. */
@Composable
fun SeasonReviewDialog(lg: LeagueController, r: yacht.engine.league.SeasonReview) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { lg.dismissReview() },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { lg.dismissReview() }) { Text("확인") } },
        title = { Text("📰 시즌 ${r.season} 결산", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (line in r.sentences) Text(line, style = MaterialTheme.typography.bodyMedium)
                if (r.champions.isNotEmpty()) {
                    Text("🏆 우승", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    // A pyramid's group titles below 3부 in one line.
                    val shown = r.champions.filter { trophy(it.kind) != null }
                    val lower = shown.filter { h -> division(h.kind, "L")?.let { it > 2 } == true }
                    for (h in shown - lower.toSet()) Text("${trophy(h.kind)} ${honourLabel(h.kind)} ${honourWinner(lg, h.kind, h.key)}", style = MaterialTheme.typography.bodySmall)
                    if (lower.isNotEmpty()) Text("🥇 4부 이하 지역 리그 조 우승 ${lower.size}명", style = MaterialTheme.typography.bodySmall)
                }
                if (r.awards.isNotEmpty()) {
                    Text("🏅 시상식", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    for (h in r.awards) Text("${honourLabel(h.kind)} ${lg.name(h.key)}", style = MaterialTheme.typography.bodySmall)
                }
                if (r.risers.isNotEmpty()) {
                    Text("📈 가장 많이 오른 AI", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    for (m in r.risers) Text("${lg.name(m.key)} ${"%.0f".format(m.from)} → ${"%.0f".format(m.to)} (${"%+.0f".format(m.delta)})", style = MaterialTheme.typography.bodySmall, color = gameColors.good)
                }
                if (r.fallers.isNotEmpty()) {
                    Text("📉 가장 많이 내린 AI", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    for (m in r.fallers) Text("${lg.name(m.key)} ${"%.0f".format(m.from)} → ${"%.0f".format(m.to)} (${"%+.0f".format(m.delta)})", style = MaterialTheme.typography.bodySmall, color = cs.error)
                }
                if (r.records.isNotEmpty()) {
                    Text("📜 새 기록", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    for (b in r.records) Text("${b.record.kind.label}: ${b.record.kind.text(b.record.value)} ${lg.name(b.record.holder)}", style = MaterialTheme.typography.bodySmall)
                }
                if (r.meta.isNotEmpty()) {
                    Text("📊 메타 변화", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    for (m in r.meta) {
                        Text(
                            m.label + ": " + if (m.percent) "%.0f%% → %.0f%%".format(100 * m.before, 100 * m.after) else "%.1f → %.1f".format(m.before, m.after),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun Honours(p: ProfileView) {
    val cs = MaterialTheme.colorScheme
    val shown = p.honours.filter { trophy(it.kind) != null }
    if (shown.isEmpty()) return
    Text(if (p.titles > 0) "🏆 수상 · 우승 ${p.titles}회" else "🏆 수상", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    // Counts first ("요트컵 우승 ×3"), then the seasons.
    val byKind = shown.groupBy { it.kind }.entries.sortedByDescending { it.value.size }
    for ((kind, hs) in byKind.take(8)) {
        Text(
            "${trophy(kind)} ${honourLabel(kind)}" + (if (hs.size > 1) " ×${hs.size}" else "") + " · 시즌 " + hs.map { it.season }.sorted().joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun Genealogy(p: ProfileView) {
    if (p.ancestors.isEmpty() && p.descendants.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Text("🌳 사제 계보", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    val up = listOf("스승", "스승의 스승", "3대 위 스승", "4대 위 스승")
    for ((g, row) in p.ancestors.withIndex()) {
        val names = row.filterNotNull().distinct()
        if (names.isEmpty()) continue
        Text("${up.getOrElse(g) { "${g + 1}대 위" }}: ${names.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
    val down = listOf("제자", "제자의 제자", "3대 아래 제자", "4대 아래 제자")
    for ((g, row) in p.descendants.withIndex()) {
        Text("${down.getOrElse(g) { "${g + 1}대 아래" }} ${row.size}: ${row.take(8).joinToString(", ")}${if (row.size > 8) " 외" else ""}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

/**
 * 기록 (its chip row is the shell's): 리더 (for now this season's MVP race), 기록실 (the league's
 * records and milestones), 전당 (champions, the hall of fame, dynasties), 결산 (the seasons) and
 * 더 보기 (the pages of the rest).
 */
@Composable
fun RecordsTab(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit) {
    val view = lg.nav.records
    LaunchedEffect(view, v.season?.number) { if (view != RecordsView.MORE) lg.loadRecords() }
    val r = lg.records
    when (view) {
        RecordsView.LEADERS -> MvpRace(lg, r, onOpen)
        RecordsView.ROOM -> RecordsRoom(lg, r, onOpen)
        RecordsView.HALL -> HallOfFame(lg, v.season?.history.orEmpty(), r, onOpen)
        RecordsView.SEASONS -> SeasonArchive(lg, r)
        RecordsView.MORE -> MoreList(lg, v)
    }
}

/** 기록 › 리더: this season's best average match rating (the MVP race). */
@Composable
private fun MvpRace(lg: LeagueController, r: yacht.app.core.RecordsRoomView?, onOpen: (String) -> Unit) {
    if (r == null) {
        CenterText("기록을 불러오는 중…")
        return
    }
    if (r.mvpRace.isEmpty()) {
        CenterText("이번 시즌 경기가 쌓이면 평점이 높은 선수부터 MVP 레이스가 보여요.")
        return
    }
    // Bars from a little under the lowest shown, so small gaps are visible.
    val lo = r.mvpRace.minOf { it.second.avgRating } - 0.1
    val hi = r.mvpRace.maxOf { it.second.avgRating }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("⭐ MVP 레이스 · 이번 시즌 평균 평점", fontWeight = FontWeight.Bold) }
        item { Text("경기마다 매긴 평점의 평균이에요. 시즌이 끝나면 1위가 MVP를 받아요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(r.mvpRace, key = { "mvp-${it.first}" }) { (k, st) ->
            Box(Modifier.clickable { onOpen(k) }) { BarRow(lg.name(k), st.avgRating - lo, hi - lo, "%.2f".format(st.avgRating)) }
        }
    }
}

/** 기록 › 더 보기: the pages that are not a chip of their own. */
@Composable
private fun MoreList(lg: LeagueController, v: LeagueView) {
    val nav = lg.nav
    // 선수 비교 starts from the best-ranked followed player, else the best-ranked one.
    val first = v.rows.filter { it.rank != null && !it.isMe }.sortedBy { it.rank }.let { rows -> rows.firstOrNull { it.key in lg.favourites } ?: rows.firstOrNull() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        LinkLine("🌱 유망주", "22세 이하 선수를 잠재력 순으로") { nav.open(Page.Prospects) }
        LinkLine("🏛 레전드 보관소", "은퇴한 선수와 레전드") { nav.open(Page.Legends) }
        LinkLine("🧬 계열별", "AI 계열마다 선수와 계열 대 계열 전적") { nav.open(Page.Families) }
        LinkLine("📊 메타 리포트", "칸마다 리그 평균 점수와 0점 비율, 상위권의 성향") { nav.open(Page.Meta) }
        if (first != null) LinkLine("⚖ 선수 비교", "두 선수의 기록과 능력치를 나란히") { nav.open(Page.Compare(first.key)) }
        LinkLine("⚗ AI 연구소", "두 AI를 수천 번 붙여서 누가 얼마나 강한지 재요") { nav.open(Page.Lab) }
    }
}

/** 기록 › 전당: the hall of fame, dynasties, the champions season by season, and the legends' page. */
@Composable
private fun HallOfFame(lg: LeagueController, history: List<SeasonHistoryItem>, r: yacht.app.core.RecordsRoomView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val members = r?.hallOfFame.orEmpty()
    val dynasties = r?.dynasties.orEmpty()
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { LinkLine("🏛 레전드 보관소", "은퇴한 선수와 레전드") { lg.nav.open(Page.Legends) } }
        if (history.isEmpty()) item { Text("시즌이 끝나면 우승팀과 수상자가 여기에 쌓여요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
        if (members.isNotEmpty()) {
            item { Text("🏛 헌액 (우승 3회, MVP 2회, 또는 5,000판 승률 55%)", fontWeight = FontWeight.Bold) }
            items(members.reversed(), key = { "hof-${it.key}" }) { m ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(m.key) }.padding(vertical = 2.dp)) {
                    Text("시즌 ${m.season}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        "${m.name} · " + when (m.reason) {
                            "TITLES" -> "우승 ${m.titles}회"
                            "MVP" -> "MVP 2회"
                            else -> "${"%,d".format(m.games)}판 승률 ${"%.1f".format(100 * m.winRate)}%"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (dynasties.isNotEmpty()) {
            item { Text("👑 왕조·트레블", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(dynasties.reversed().take(30), key = { "dy-${it.key}-${it.kind}-${it.title}-${it.from}" }) { d ->
                Text(
                    "${lg.name(d.key)} · " + when (d.kind) {
                        "RUN" -> "${honourLabel(d.title)} ${d.count}연패 (시즌 ${d.from}–${d.to})"
                        "DOUBLE" -> "더블 (리그·요트컵, 시즌 ${d.to})"
                        "TREBLE" -> "트레블 (리그·요트컵·챔스, 시즌 ${d.to})"
                        "UNBEATEN" -> "${honourLabel(d.title).removeSuffix(" 우승")} 무패 시즌 (시즌 ${d.to})"
                        else -> d.kind
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable { onOpen(d.key) },
                )
            }
        }
        if (history.isNotEmpty()) item { Text("시즌별", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        item {
            val titles = history.mapNotNull { it.champions.firstOrNull() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
            if (titles.isNotEmpty()) {
                Text("최다 우승: " + titles.joinToString(" · ") { "${it.key} ${it.value}회" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        items(history, key = { "hall-${it.number}" }) { h ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("시즌 ${h.number}", fontWeight = FontWeight.Bold)
                    // A pyramid's champion per group: 1부 to 3부 by name, the rest counted.
                    val tierOf = h.championTiers.takeIf { it.size == h.champions.size } ?: h.champions.indices.toList()
                    val shown = h.champions.indices.filter { tierOf[it] <= 2 || h.champions.size <= 8 }
                    Text(
                        shown.joinToString("  ") { i -> "${if (tierOf[i] == 0) "👑" else "🥇"} ${tierOf[i] + 1}부 ${h.champions[i]}" } +
                            if (shown.size < h.champions.size) "  외 ${h.champions.size - shown.size}개 조" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    for ((kind, who) in h.titles) {
                        Text("${trophy(kind) ?: "·"} ${honourLabel(kind)} $who", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    val awards = listOfNotNull(h.topScorer?.let { "⚽ 득점왕 $it" }, h.yachtKing?.let { "🎲 최다 요트 $it" })
                    if (awards.isNotEmpty()) Text(awards.joinToString("  "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    fun more(n: Int, shown: Int) = if (n > shown) " 외 ${n - shown}명" else ""
                    if (h.promoted.isNotEmpty()) Text("⬆ 승격: ${h.promoted.joinToString(", ")}${more(h.promotedCount, h.promoted.size)}", style = MaterialTheme.typography.labelSmall, color = gameColors.good)
                    if (h.relegated.isNotEmpty()) Text("⬇ 강등: ${h.relegated.joinToString(", ")}${more(h.relegatedCount, h.relegated.size)}", style = MaterialTheme.typography.labelSmall, color = cs.error)
                }
            }
        }
    }
}

/** [Page.Legends]: retired players and legends. */
@Composable
internal fun Legends(lg: LeagueController, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { lg.loadPastPlayers() }
    val past = lg.pastPlayers
    if (past.isEmpty()) {
        CenterText("생성·진화 선수는 시즌이 끝날 때 레이팅이 낮으면 은퇴해요. 우승이나 수상 경력이 있으면 레전드로 남아요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(past, key = { "past-${it.key}-${it.retired}" }) { p ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(p.key) }, colors = CardDefaults.cardColors(containerColor = if (p.legend) cs.tertiaryContainer else cs.surface)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text((if (p.legend) "🏛 " else "") + p.name, fontWeight = FontWeight.Bold)
                    Text(
                        "${p.hometown} · 시즌 ${p.born}–${p.retired}" + (p.bestRating?.let { " · 최고 레이팅 $it" } ?: "") + if (p.titles > 0) " · 우승 ${p.titles}회" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    if (p.legend && p.honours != "-") {
                        Text(
                            p.honours.split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { (k, n) -> honourLabel(k) + if (n != "1") " ×$n" else "" } }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/** An AI no longer in the league (retired, or a legend): its profile alone. */
@Composable
fun PastProfileDialog(lg: LeagueController, key: String, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text(lg.name(key), fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { ProfileSection(lg, key, onOpen) } },
    )
}


/** 기록 › 기록실: the league's records by section, and the latest milestones. */
@Composable
private fun RecordsRoom(lg: LeagueController, r: yacht.app.core.RecordsRoomView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (r == null) {
        CenterText("기록을 불러오는 중…")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("📜 리그 기록", fontWeight = FontWeight.Bold) }
        if (r.records.isEmpty()) item { Text("공식 경기가 쌓이면 기록이 생겨요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        // By section (레이팅, 한 판·한 경기, 한 시즌, 통산, 우승·수상), each in its kinds' order.
        for (g in RecordGroup.entries) {
            val list = r.records.filter { it.kind.group == g }.sortedBy { it.kind.ordinal }
            if (list.isEmpty()) continue
            item(key = "rec-group-${g.name}") {
                Text(g.label, style = MaterialTheme.typography.labelLarge, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
            items(list, key = { "rec-${it.kind}" }) { rec -> RecordCard(lg, rec, onOpen) }
        }
        if (r.milestones.isNotEmpty()) {
            item { Text("🎯 마일스톤", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(r.milestones.take(30), key = { "ms-${it.season}-${it.keys}-${it.args}" }) { e ->
                Text("시즌 ${e.season} · ${lg.name(e.keys.firstOrNull() ?: "")} ${milestoneText(e.args)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One record: what it is, its value and holder, and when, against whom and where. */
@Composable
private fun RecordCard(lg: LeagueController, rec: LeagueRecord, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable { onOpen(rec.holder) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(rec.kind.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text("${rec.kind.text(rec.value)} · ${lg.name(rec.holder)}", fontWeight = FontWeight.Bold)
            Text(
                "시즌 ${rec.season}" + (rec.other?.let { " · 상대 ${lg.name(it)}" } ?: "") + (rec.comp?.let { " · ${it.label}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** 기록 › 결산: every finished season, newest first, with the average score season by season. */
@Composable
private fun SeasonArchive(lg: LeagueController, r: yacht.app.core.RecordsRoomView?) {
    val cs = MaterialTheme.colorScheme
    if (r == null || r.summaries.isEmpty()) {
        CenterText("시즌이 끝나면 시즌마다 우승팀·수상자·깨진 기록이 여기에 남아요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Oldest first on the chart (the list below is newest first).
        val seasons = r.summaries.sortedBy { it.season }
        if (seasons.size >= 2) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("시즌별 평균 점수", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        LineChart(seasons.map { it.avgScore }, labels = "시즌 ${seasons.first().season}" to "시즌 ${seasons.last().season}")
                    }
                }
            }
        }
        items(r.summaries, key = { "sum-${it.season}" }) { s ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("시즌 ${s.season}", fontWeight = FontWeight.Bold)
                    Text("${"%,d".format(s.games)}판 · 평균 ${"%.1f".format(s.avgScore)}점", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    for (h in s.honours.filter { trophy(it.kind) != null }) {
                        Text("${trophy(h.kind)} ${honourLabel(h.kind)} ${honourWinner(lg, h.kind, h.key)}", style = MaterialTheme.typography.bodySmall)
                    }
                    s.topScorer?.let { Text("⚽ 득점왕 ${lg.name(it)} (평균 ${"%.1f".format(s.topScore)}점)", style = MaterialTheme.typography.bodySmall) }
                    for (b in s.records) Text("📜 ${b.record.kind.label}: ${b.record.kind.text(b.record.value)} ${lg.name(b.record.holder)}", style = MaterialTheme.typography.labelSmall, color = cs.secondary)
                }
            }
        }
    }
}

/** [Page.Meta]: the boxes' league averages and the top players' tendencies, from the records room [r]. */
@Composable
internal fun MetaReportView(r: yacht.app.core.RecordsRoomView?) {
    val cs = MaterialTheme.colorScheme
    val m = r?.meta
    if (m == null || m.cards == 0) {
        CenterText("공식 경기가 쌓이면 칸마다 리그 평균 점수와 0점 비율, 상위권 생성 선수의 성향이 나와요.")
        return
    }
    val before = r.previousMeta?.takeIf { it.season != m.season }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item {
            Text("📊 칸별 리그 메타 (시즌 ${m.season}, 점수판 ${"%,d".format(m.cards)}개)", fontWeight = FontWeight.Bold)
            Text("평균 ${"%.1f".format(m.avgScore)}점" + (before?.let { " · 지난 시즌 ${"%.1f".format(it.avgScore)}점" } ?: ""), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                Text("칸", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("평균", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("0점", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("변화", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        items(yacht.engine.Category.ALL, key = { "meta-${it.name}" }) { c ->
            Row(Modifier.fillMaxWidth()) {
                Text(c.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("%.1f".format(m.boxAverage(c)), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text("%.0f%%".format(100 * m.zeroRate(c)), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                val d = m.boxChange(before, c)
                Text(if (d.isNaN()) "–" else "%+.1f".format(d), style = MaterialTheme.typography.labelSmall, color = if (d.isNaN() || kotlin.math.abs(d) < 0.05) cs.onSurfaceVariant else if (d > 0) gameColors.good else cs.error)
            }
        }
        val traits = (r.previousMeta ?: m).traits
        if (traits.isNotEmpty()) {
            item { Text("🧬 생성·진화 선수의 성향: 상위 20% vs 나머지", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(traits.sortedByDescending { kotlin.math.abs(it.top - it.rest) }.take(16), key = { "tr-${it.trait}-${it.value}" }) { t ->
                Text(
                    "${t.trait} · ${t.value}: 상위 ${"%.0f".format(100 * t.top)}% / 나머지 ${"%.0f".format(100 * t.rest)}%" +
                        if (t.change.isNaN()) "" else " (상위 ${"%+.0f".format(100 * t.change)}%p)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** A family card's row, from plain values: unchanged rows skip recomposing on a new snapshot. */
@Composable
private fun FamilyMember(key: String, rank: Int?, name: String, tier: String?, tierColor: androidx.compose.ui.graphics.Color?, rating: Int?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable { onOpen(key) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(rank?.let { "${it}위" } ?: "배치", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(36.dp))
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tier != null && tierColor != null) Text(tier, style = MaterialTheme.typography.labelSmall, color = tierColor, fontWeight = FontWeight.Bold)
        Text("  ${rating ?: "–"}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** [Page.Families]: every family's players, and the family against family matrix. */
@Composable
internal fun FamilyList(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val groups = Family.entries.map { f -> f to v.rows.filter { it.family == f } }
            .sortedByDescending { (_, rows) -> rows.mapNotNull { it.rating }.average().takeIf { !it.isNaN() } ?: -1.0 }
        item {
            var open by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                        Text("계열 대 계열 상대 전적", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (open) "접기" else "펼치기", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    }
                    if (open) {
                        val shown = groups.filter { it.second.isNotEmpty() }
                        androidx.compose.runtime.LaunchedEffect(shown.size) {
                            lg.loadMatrix("계열", shown.map { (_, rows) -> rows.map { it.key } }, shown.map { it.first.label })
                        }
                        val m = lg.h2h
                        if (m == null || m.title != "계열") Text("상대 전적을 모으는 중…", style = MaterialTheme.typography.bodySmall)
                        else H2hGrid(m)
                    }
                }
            }
        }
        items(groups, key = { it.first.name }) { (f, rows) ->
            val rated = rows.filter { it.rating != null }
            // A family of a league of thousands holds hundreds: its best ten, the rest on request.
            var all by remember(f) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            "${rows.size}개" + (rated.takeIf { it.isNotEmpty() }?.let { " · 평균 ${"%.0f".format(it.mapNotNull { r -> r.rating }.average())}" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    val sorted = rows.sortedBy { it.rank ?: Int.MAX_VALUE }
                    for (r in if (all) sorted.take(FAMILY_MAX) else sorted.take(FAMILY_TOP)) {
                        FamilyMember(r.key, r.rank, r.shown, r.tier?.label, r.tier?.name?.let { tierColor(it) }, r.rating, onOpen)
                    }
                    if (sorted.size > FAMILY_TOP) {
                        Text(
                            if (all) "접기" + (if (sorted.size > FAMILY_MAX) " (상위 ${FAMILY_MAX}명까지 보여요)" else "") else "더 보기 (${sorted.size - FAMILY_TOP}명)",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.primary,
                            modifier = Modifier.clickable { all = !all }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A family card's members at first, and at most when opened. */
private const val FAMILY_TOP = 10
private const val FAMILY_MAX = 200

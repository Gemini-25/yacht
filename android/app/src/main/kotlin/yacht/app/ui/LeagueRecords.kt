package yacht.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.ProfileView
import yacht.app.core.SeasonHistoryItem
import yacht.engine.league.Origin
import yacht.engine.game.label

/** An honour in Korean ("1부 우승", "요트컵 준우승", "신인왕", ...). */
fun honourLabel(kind: String): String {
    division(kind, "L")?.let { return "${it + 1}부 우승" }
    division(kind, "UP")?.let { return "${it + 1}부 승격" }
    division(kind, "DOWN")?.let { return "${it + 1}부 강등" }
    division(kind, "STAY")?.let { return "${it + 1}부 잔류" }
    return when (kind) {
        "REG" -> "정규리그 1위"
        "L0R" -> "우승 플레이오프 준우승"
        "CUP" -> "요트컵 우승"
        "CUP2" -> "요트컵 준우승"
        "CL" -> "챔피언스 리그 우승"
        "CL2" -> "챔피언스 리그 준우승"
        "SUPER" -> "슈퍼컵 우승"
        "ALLSTAR" -> "올스타전 승리"
        "FAMILY" -> "계열 대항전 우승"
        "FSQ" -> "계열 대표 선발"
        "CLUB" -> "클럽 리그 우승"
        "CLUB2" -> "클럽 리그 준우승"
        "MVP" -> "MVP"
        "ROOKIE" -> "신인왕"
        "MIP" -> "기량발전상"
        "YACHT" -> "최다 요트상"
        "SCORER" -> "득점왕"
        "WC" -> "요트 월드컵 우승"
        "WC2" -> "요트 월드컵 준우승"
        "WC4" -> "요트 월드컵 4강"
        "WCW" -> "요트 월드컵 우승 멤버"
        "WCMVP" -> "월드컵 MVP"
        "WCTOP" -> "월드컵 득점왕"
        "WCH" -> "요트 월드컵 개최"
        "CC" -> "대륙컵 우승"
        "CC2" -> "대륙컵 준우승"
        "CCW" -> "대륙컵 우승 멤버"
        "NSQ" -> "국가대표 선발"
        else -> kind
    }
}

private fun division(kind: String, prefix: String): Int? =
    if (kind.startsWith(prefix)) kind.removePrefix(prefix).toIntOrNull() else null

/** Honours worth a trophy icon (titles and awards, not moves or call-ups). */
private fun trophy(kind: String): String? = when {
    kind.startsWith("UP") || kind.startsWith("DOWN") || kind.startsWith("STAY") || kind == "FSQ" || kind == "NSQ" || kind == "WCH" -> null
    kind == "L0" -> "👑"
    division(kind, "L") != null -> "🥇"
    kind in setOf("CUP", "CL", "SUPER", "FAMILY", "CLUB", "WC", "WCW", "CC", "CCW") -> "🏆"
    kind in setOf("MVP", "ROOKIE", "MIP", "YACHT", "SCORER", "REG", "WCMVP", "WCTOP") -> "🏅"
    kind == "WC4" -> "🥉"
    kind.endsWith("2") || kind == "L0R" -> "🥈"
    else -> null
}

/** The profile part of an AI's page: origin, family tree and honours (loaded on open). */
@Composable
fun ProfileSection(lg: LeagueController, key: String) {
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
        p.identity?.let { IdentityPart(it, alive = p.retired == null) }
        p.genes?.takeIf { p.identity?.style == null && (p.origin == Origin.GENERATED || p.origin == Origin.EVOLVED) }?.let {
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
                    for (h in r.champions.filter { trophy(it.kind) != null }) Text("${trophy(h.kind)} ${honourLabel(h.kind)} ${honourWinner(lg, h.kind, h.key)}", style = MaterialTheme.typography.bodySmall)
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

private enum class RecordsMode(val label: String) {
    ROOM("기록실"),
    NATIONS("국가 랭킹"),
    PROSPECTS("유망주"),
    HALL("명예의 전당"),
    ARCHIVE("시즌 아카이브"),
    META("메타 리포트"),
    LEGENDS("레전드 보관소"),
    FAMILIES("계열별"),
}

/** The modes that show the records room's data. */
private val RECORDS_MODES = setOf(RecordsMode.ROOM, RecordsMode.HALL, RecordsMode.ARCHIVE, RecordsMode.META)

/** 기록: the hall of fame by season, the legends and retired players, and the families. */
@Composable
fun RecordsTab(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit, families: @Composable () -> Unit) {
    var mode by remember { mutableStateOf(RecordsMode.ROOM) }
    LaunchedEffect(mode) { if (mode in RECORDS_MODES) lg.loadRecords() }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in RecordsMode.entries) FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 12.sp) })
        }
        val r = lg.records
        when (mode) {
            RecordsMode.ROOM -> RecordsRoom(lg, r, onOpen)
            RecordsMode.ARCHIVE -> SeasonArchive(lg, r)
            RecordsMode.META -> MetaReportView(r)
            RecordsMode.HALL -> HallOfFame(v.season?.history.orEmpty(), r?.hallOfFame.orEmpty(), onOpen)
            RecordsMode.LEGENDS -> Legends(lg, onOpen)
            RecordsMode.NATIONS -> NationRanking(lg)
            RecordsMode.PROSPECTS -> Prospects(lg, onOpen)
            RecordsMode.FAMILIES -> families()
        }
    }
}

@Composable
private fun HallOfFame(history: List<SeasonHistoryItem>, members: List<yacht.engine.league.HallOfFameEntry>, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (history.isEmpty()) {
        CenterText("시즌이 끝나면 우승팀과 수상자가 여기에 쌓여요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            item { Text("시즌별", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        }
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
                    Text(h.champions.mapIndexed { i, n -> "${if (i == 0) "👑" else "🥇"} ${i + 1}부 $n" }.joinToString("  "), style = MaterialTheme.typography.bodySmall)
                    for ((kind, who) in h.titles) {
                        Text("${trophy(kind) ?: "·"} ${honourLabel(kind)} $who", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    val awards = listOfNotNull(h.topScorer?.let { "⚽ 득점왕 $it" }, h.yachtKing?.let { "🎲 최다 요트 $it" })
                    if (awards.isNotEmpty()) Text(awards.joinToString("  "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    if (h.promoted.isNotEmpty()) Text("⬆ 승격: ${h.promoted.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = gameColors.good)
                    if (h.relegated.isNotEmpty()) Text("⬇ 강등: ${h.relegated.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = cs.error)
                }
            }
        }
    }
}

@Composable
private fun Legends(lg: LeagueController, onOpen: (String) -> Unit) {
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
fun PastProfileDialog(lg: LeagueController, key: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("닫기") } },
        title = { Text(lg.name(key), fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { ProfileSection(lg, key) } },
    )
}


@Composable
private fun RecordsRoom(lg: LeagueController, r: yacht.app.core.RecordsView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (r == null) {
        CenterText("기록을 불러오는 중…")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("📜 리그 기록", fontWeight = FontWeight.Bold) }
        if (r.records.isEmpty()) item { Text("공식 경기가 쌓이면 기록이 생겨요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        items(r.records, key = { "rec-${it.kind}" }) { rec ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(rec.holder) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(10.dp)) {
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
        if (r.mvpRace.isNotEmpty()) {
            item { Text("⭐ 이번 시즌 평점 순위 (MVP 레이스)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            // Bars from a little under the lowest shown, so small gaps are visible.
            val lo = r.mvpRace.minOf { it.second.avgRating } - 0.1
            val hi = r.mvpRace.maxOf { it.second.avgRating }
            items(r.mvpRace, key = { "mvp-${it.first}" }) { (k, st) ->
                BarRow(lg.name(k), st.avgRating - lo, hi - lo, "%.2f".format(st.avgRating))
            }
        }
        if (r.dynasties.isNotEmpty()) {
            item { Text("👑 왕조·트레블", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(r.dynasties.reversed().take(30), key = { "dy-${it.key}-${it.kind}-${it.title}-${it.from}" }) { d ->
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
        if (r.milestones.isNotEmpty()) {
            item { Text("🎯 마일스톤", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(r.milestones.take(30), key = { "ms-${it.season}-${it.keys}-${it.args}" }) { e ->
                Text("시즌 ${e.season} · ${lg.name(e.keys.firstOrNull() ?: "")} ${milestoneText(e.args)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A milestone event's args (kind, value) in Korean. */
fun milestoneText(args: List<String>): String {
    val v = args.getOrNull(1)?.toIntOrNull()?.let { "%,d".format(it) } ?: args.getOrNull(1).orEmpty()
    return when (args.firstOrNull()) {
        "games" -> "통산 ${v}판"
        "wins" -> "통산 ${v}승"
        "yachts" -> "통산 요트 ${v}번"
        "300" -> if (v == "1") "첫 300점" else "통산 ${v}번째 300점 경기"
        "title" -> if (v == "1") "첫 우승" else "통산 ${v}번째 우승"
        else -> args.joinToString(" ")
    }
}

@Composable
private fun SeasonArchive(lg: LeagueController, r: yacht.app.core.RecordsView?) {
    val cs = MaterialTheme.colorScheme
    if (r == null || r.summaries.isEmpty()) {
        CenterText("시즌이 끝나면 시즌마다 우승팀·수상자·깨진 기록이 여기에 남아요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

/** An honour's winner: a family or all-star side by its label, a club or AI by name. */
fun honourWinner(lg: LeagueController, kind: String, key: String): String = when (kind) {
    "FAMILY", "ALLSTAR" -> yacht.engine.league.Competitions.teamLabel(key)
    else -> lg.name(key)
}

@Composable
private fun MetaReportView(r: yacht.app.core.RecordsView?) {
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

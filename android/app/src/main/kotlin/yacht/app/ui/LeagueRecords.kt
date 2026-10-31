package yacht.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import yacht.app.core.ChronicleView
import yacht.app.core.HallSeason
import yacht.app.core.HallView
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.RecordsView
import yacht.app.core.ReviewList
import yacht.app.core.RoomRecord
import yacht.app.core.RoomView
import yacht.engine.league.Family
import yacht.engine.league.LeagueRecord
import yacht.engine.league.RecordGroup
import yacht.engine.game.label

/**
 * 기록 (its chip row is the shell's): 리더 (the leaderboard hub), 기록실 (the records, compact, with
 * this season's new ones, the chases and record news), 전당 (champions by season, the hall of fame,
 * dynasties, the chronicle), 결산 (every season, a tap: its review) and 더 보기 (the other pages).
 */
@Composable
fun RecordsTab(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit) {
    val view = lg.nav.records
    val season = v.season?.number
    LaunchedEffect(view, season, v.clock?.week) {
        when (view) {
            RecordsView.ROOM -> lg.analytics.loadRoom()
            RecordsView.HALL -> lg.analytics.loadHall()
            RecordsView.SEASONS -> lg.analytics.loadReviews()
            else -> {}
        }
    }
    when (view) {
        RecordsView.LEADERS -> LeadersHub(lg, v)
        RecordsView.ROOM -> RecordsRoom(lg, lg.analytics.room(), onOpen)
        RecordsView.HALL -> HallOfFame(lg, lg.analytics.hall(), onOpen)
        RecordsView.SEASONS -> SeasonArchive(lg, lg.analytics.reviewList())
        RecordsView.MORE -> MoreList(lg, v)
    }
}

/** 기록 › 더 보기: the pages that are not a chip of their own. */
@Composable
private fun MoreList(lg: LeagueController, v: LeagueView) {
    val nav = lg.nav
    // 선수 비교 starts from the best-ranked followed player, else the best-ranked one.
    val first = v.rows.filter { it.rank != null && !it.isMe }.sortedBy { it.rank }.let { rows -> rows.firstOrNull { it.key in lg.favourites } ?: rows.firstOrNull() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        LinkLine("🔎 탐구", "가장 낮은 점수로 이긴 판, 천적 관계, 레이팅보다 잘한 선수, 리그 비교") { nav.open(Page.Facts()) }
        LinkLine("📜 연대기", "시즌마다 우승, MVP, 리그 랭킹 1위, 왕조") { nav.open(Page.Chronicle) }
        LinkLine("🌱 유망주", "22세 이하 선수를 잠재력 순으로") { nav.open(Page.Prospects) }
        LinkLine("🏛 레전드 보관소", "은퇴한 선수와 레전드") { nav.open(Page.Legends) }
        LinkLine("🧬 계열별", "AI 계열마다 선수와 계열 대 계열 전적") { nav.open(Page.Families) }
        LinkLine("📊 메타 리포트", "칸마다 리그 평균 점수와 0점 비율, 상위권의 성향") { nav.open(Page.Meta) }
        if (first != null) LinkLine("⚖ 선수 비교", "두 선수의 기록과 능력치를 나란히") { nav.open(Page.Compare(first.key)) }
        LinkLine("⚗ AI 연구소", "두 AI를 수천 번 붙여서 누가 얼마나 강한지 재요") { nav.open(Page.Lab) }
        if (v.season?.national == true || v.season?.nextNational == true) LinkLine("🏛 요트협회 기록", "안건과 결정, 시상의 역사") { nav.open(Page.Federation) }
    }
}

// ---- 기록실 -------------------------------------------------------------------------------------------

/**
 * 기록 › 기록실: this season's new records on top, then every record as one two-line row ("{label} ·
 * {value}" / "{holder} · 시즌 {n}"), grouped and foldable, its earlier holders on a tap; who chases
 * which record; the latest record news; the retired kinds folded (예전 기록).
 */
@Composable
private fun RecordsRoom(lg: LeagueController, r: RoomView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (r == null) {
        CenterText("기록을 불러오는 중…")
        return
    }
    val folded = remember { mutableStateOf(emptySet<RecordGroup>()) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (r.fresh.isNotEmpty()) {
            item(key = "fresh-head") { RoomHeading("🆕 이번 시즌 새 기록") }
            items(r.fresh, key = { "fresh-${it.record.kind}" }) { b ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(b.record.holder) }, colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("${b.record.kind.label} · ${b.record.kind.withUnit(b.record.value)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        // A count its own holder raised says where it stood; another holder's is the record beaten.
                        val before = b.previous?.let { p ->
                            if (p.holder == b.record.holder) " · 시즌 전 ${p.kind.withUnit(p.value)}에서 늘렸어요" else " · 종전 ${p.kind.withUnit(p.value)} (${lg.name(p.holder)})"
                        } ?: " · 첫 기록"
                        Text(lg.name(b.record.holder) + before, style = MaterialTheme.typography.labelSmall, color = cs.onTertiaryContainer)
                    }
                }
            }
        }
        if (r.groups.isEmpty()) item(key = "none") { Text("공식 경기가 쌓이면 기록이 생겨요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
        for ((g, list) in r.groups) {
            val open = g !in folded.value
            item(key = "group-${g.name}") {
                Row(
                    Modifier.fillMaxWidth().clickable { folded.value = if (open) folded.value + g else folded.value - g }.padding(top = 10.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.label, style = MaterialTheme.typography.labelLarge, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (open) "▴" else "${list.size}개 ▾", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
            if (open) items(list, key = { "rec-${it.record.kind}" }) { rec -> RecordRow(lg, rec, onOpen) }
        }
        if (r.chase.isNotEmpty()) {
            item(key = "chase-head") { RoomHeading("🏃 기록 추격") }
            items(r.chase, key = { "chase-${it.first}" }) { (kind, lines) ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text(kind.label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    for (l in lines) {
                        Row(Modifier.fillMaxWidth().clickable { onOpen(l.key) }.padding(vertical = 1.dp)) {
                            Text(lg.name(l.key), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(l.text(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = if (yacht.engine.league.RecordChase.near(l)) cs.primary else cs.onSurface)
                        }
                    }
                }
            }
        }
        if (r.news.isNotEmpty()) {
            item(key = "news-head") { RoomHeading("📰 최근 기록 뉴스") }
            items(r.news, key = { "news-${it.season}-${it.week}-${it.headline}" }) { a -> ArticleCard(a, lg.view?.clock) { k -> if (lg.isAi(k)) onOpen(k) } }
        }
        if (r.retired.isNotEmpty()) {
            item(key = "retired") {
                var open by remember { mutableStateOf(false) }
                Column {
                    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 10.dp, bottom = 2.dp)) {
                        Text("🗄 예전 기록", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (open) "▴" else "${r.retired.size}개 ▾", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    if (open) {
                        Text("더는 겨루지 않는 기록이에요. 기준이 바뀌었거나 다시 깰 수 없게 된 기록이라 보관만 해요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        for (rec in r.retired) RecordRow(lg, RoomRecord(rec, emptyList(), false), onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomHeading(text: String) = Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

/** One record in two lines; a tap shows its earlier holders (이전 기록), the holder's name opens its page. */
@Composable
private fun RecordRow(lg: LeagueController, r: RoomRecord, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val rec = r.record
    var open by remember(rec.kind) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(enabled = r.history.isNotEmpty()) { open = !open }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${rec.kind.label} · ${rec.kind.withUnit(rec.value)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (r.fresh) Text("NEW ", style = MaterialTheme.typography.labelSmall, color = cs.tertiary, fontWeight = FontWeight.Bold)
            if (r.history.isNotEmpty()) Text(if (open) "▴" else "이전 ${r.history.size} ▾", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Text(
            recordWho(lg, rec), style = MaterialTheme.typography.labelSmall, color = cs.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { if (lg.isAi(rec.holder)) onOpen(rec.holder) },
        )
        if (open) {
            for (h in r.history) {
                Text(
                    "이전 · ${h.kind.withUnit(h.value)} · ${recordWho(lg, h)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).clickable { if (lg.isAi(h.holder)) onOpen(h.holder) },
                )
            }
        }
    }
}

/** "엔리코 갈로 · 시즌 3 · vs 상대 · 요트컵". */
private fun recordWho(lg: LeagueController, r: LeagueRecord): String =
    "${lg.name(r.holder)} · 시즌 ${r.season}" + (r.other?.let { " · vs ${lg.name(it)}" } ?: "") + (r.comp?.let { " · ${it.label}" } ?: "")

// ---- 전당 and the chronicle ------------------------------------------------------------------------

/** 기록 › 전당: the chronicle and the legends, the most league titles, the hall of fame, dynasties, then the seasons' champions. */
@Composable
private fun HallOfFame(lg: LeagueController, h: HallView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column {
                LinkLine("📜 연대기", "시즌마다 우승, MVP, 리그 랭킹 1위, 왕조를 한눈에") { lg.nav.open(Page.Chronicle) }
                LinkLine("🏛 레전드 보관소", "은퇴한 선수와 레전드") { lg.nav.open(Page.Legends) }
            }
        }
        if (h == null) {
            item { Text("전당을 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
            return@LazyColumn
        }
        if (h.seasons.isEmpty()) item { Text("시즌이 끝나면 우승팀과 수상자가 여기에 쌓여요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
        // Most league titles, once someone has two.
        val most = h.most.filter { it.second >= 2 }
        if (most.isNotEmpty()) {
            item { Text("👑 리그 최다 우승: " + most.joinToString(" · ") { (k, n) -> "${lg.name(k)} ${n}회" }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        if (h.members.isNotEmpty()) {
            item { Text("🏛 헌액 (우승 3회, MVP 2회, 또는 5,000판 승률 55%)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            items(h.members.reversed(), key = { "hof-${it.key}" }) { m ->
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
        if (h.dynasties.isNotEmpty()) {
            item { Text("👑 왕조·트레블", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(h.dynasties.reversed().take(30), key = { "dy-${it.key}-${it.kind}-${it.title}-${it.from}" }) { d ->
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
        if (h.seasons.isNotEmpty()) item { Text("시즌별", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        items(h.seasons, key = { "hall-${it.season}" }) { s -> HallSeasonCard(lg, s, onOpen) }
    }
}

/** A season of the hall: its leagues' champions (the best-ranked first), the world titles, the awards and the moves counted. */
@Composable
private fun HallSeasonCard(lg: LeagueController, s: HallSeason, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("시즌 ${s.season}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("결산 ›", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.clickable { lg.nav.open(Page.Review(s.season)) })
            }
            for (ch in s.champions) {
                Text(
                    "${if (ch.tier == 0) "👑" else "🥇"} ${ch.label} ${lg.name(ch.key)}", style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { if (lg.isAi(ch.key)) onOpen(ch.key) },
                )
            }
            if (s.more > 0) Text(if (s.national) "외 ${s.more}개 리그 우승" else "외 ${s.more}개 조 우승", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            for (t in s.titles) {
                Text(
                    "${trophy(t.kind) ?: "🏆"} ${honourLabel(t.kind)} ${honourWinner(lg, t.kind, t.key)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.clickable { if (lg.isAi(t.key)) onOpen(t.key) },
                )
            }
            if (s.awards.isNotEmpty()) {
                Text(s.awards.joinToString("  ") { "${trophy(it.kind) ?: "🏅"} ${honourLabel(it.kind)} ${lg.name(it.key)}" }, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            if (s.promoted + s.relegated > 0) {
                Text("⬆ 승격 ${s.promoted}명 · ⬇ 강등 ${s.relegated}명", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** [Page.Chronicle]: the eras (the longest runs, the most titles, the best rating, the longest reign of a league) and every season's lines. */
@Composable
internal fun ChroniclePage(lg: LeagueController, v: LeagueView) {
    LaunchedEffect(v.season?.number) { lg.analytics.loadChronicle() }
    val c = lg.analytics.chronicle()
    if (c == null) {
        CenterText("연대기를 불러오는 중…")
        return
    }
    ChronicleBody(c) { k -> if (lg.isAi(k)) lg.nav.open(Page.Player(k)) }
}

/** The chronicle's body (the harness draws it with hand-made seasons too). */
@Composable
internal fun ChronicleBody(c: ChronicleView, onPlayer: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (c.seasons.isEmpty()) item { Text("시즌이 끝나면 시즌마다 우승과 MVP, 왕조가 여기에 이어져요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
        if (c.eras.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("🏛 시대를 만든 이름들", fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer)
                        for (l in c.eras) ChronicleText(l, cs.onSecondaryContainer, onPlayer)
                    }
                }
            }
        }
        items(c.seasons, key = { "chr-${it.season}" }) { s ->
            Row {
                Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("시즌", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text("${s.season}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                }
                Column(Modifier.weight(1f).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (l in s.lines) ChronicleText(l, cs.onSurface, onPlayer)
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ChronicleText(l: yacht.app.core.ChronicleLine, color: androidx.compose.ui.graphics.Color, onPlayer: (String) -> Unit) {
    Text(
        "${l.icon} ${l.text}", style = MaterialTheme.typography.bodySmall, color = color,
        modifier = Modifier.fillMaxWidth().clickable(enabled = l.key != null) { l.key?.let(onPlayer) },
    )
}

// ---- 결산 -------------------------------------------------------------------------------------------

/** 기록 › 결산: the average score season by season, then every season (newest first); a tap opens its review (a summary for one older than the kept reviews). */
@Composable
private fun SeasonArchive(lg: LeagueController, r: ReviewList?) {
    val cs = MaterialTheme.colorScheme
    if (r == null) {
        CenterText("결산을 불러오는 중…")
        return
    }
    if (r.items.isEmpty()) {
        CenterText("시즌이 끝나면 시즌마다 우승팀·수상자·깨진 기록이 여기에 남아요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Oldest first on the chart (the list below is newest first).
        val seasons = r.items.filter { !it.avgScore.isNaN() }.sortedBy { it.season }
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
        items(r.items, key = { "sum-${it.season}" }) { s ->
            Card(Modifier.fillMaxWidth().clickable { lg.nav.open(Page.Review(s.season)) }, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("시즌 ${s.season}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (s.kept) "📖 결산 ›" else "요약 ›", style = MaterialTheme.typography.labelMedium, color = if (s.kept) cs.primary else cs.onSurfaceVariant)
                    }
                    for (line in s.lines) Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.games > 0) Text("${"%,d".format(s.games)}판 · 평균 ${"%.1f".format(s.avgScore)}점", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
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

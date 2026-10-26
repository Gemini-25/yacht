@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.FeedItem
import yacht.app.core.LadderRow
import yacht.app.core.LeagueBackground
import yacht.app.core.LeagueController
import yacht.app.core.LeagueSpeed
import yacht.app.core.LeagueView
import yacht.app.core.Screen
import yacht.app.core.SeasonHistoryItem
import yacht.app.core.SeasonRow
import yacht.app.core.SeasonView
import yacht.engine.explain.Ko
import yacht.engine.league.Comp
import yacht.engine.league.Family
import yacht.engine.league.Ladder
import yacht.engine.league.Tier
import yacht.engine.league.TierName

private enum class LeagueTab(val label: String) {
    LADDER("순위표"),
    SCHEDULE("일정"),
    SEASON("리그"),
    COMPS("대회"),
    CLUBS("클럽"),
    NEWS("소식"),
    RECORDS("기록"),
}

private enum class LadderSort(val label: String) {
    RATING("레이팅"),
    WIN_RATE("승률"),
    SCORE("평균 점수"),
    GAMES("경기 수"),
    STREAK("연승"),
}

/** The AI league: start/stop, the live ladder, match news and families. */
@Composable
fun LeagueScreen(c: AppController) {
    val lg = c.league
    val v = lg.view
    val tab = lg.tab
    var resetAsk by remember { mutableStateOf(false) }
    var rankedAsk by remember { mutableStateOf(false) }
    var rulesAsk by remember { mutableStateOf(false) }
    var labAsk by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                title = {
                    Column {
                        Text("AI 리그", fontWeight = FontWeight.Bold)
                        Text(
                            "${lg.size}개 AI" + (v?.let { " · 총 ${"%,d".format(it.matches)}경기 (${"%,d".format(it.games)}판)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = { c.screen = Screen.HOME }) { Text("홈") } },
                actions = {
                    TextButton(onClick = { labAsk = true }) { Text("연구소") }
                    TextButton(onClick = { rulesAsk = true }) { Text("규칙") }
                    TextButton(onClick = { resetAsk = true }) { Text("초기화") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ControlCard(c, v) {
                lg.scheduleWeek = null
                lg.tab = LeagueTab.SCHEDULE.ordinal
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(onClick = { rankedAsk = true }, label = { Text("🎮 랭크전") })
                AssistChip(
                    onClick = { watchFeatured(c) },
                    label = { Text("👀 경기 관전") },
                )
                AssistChip(onClick = { c.editingAi = null; c.screen = Screen.BUILDER }, label = { Text("🛠 AI 만들기") })
            }
            lg.seasonNews?.let { SeasonNews(it) { lg.seasonNews = null } }
            val tabs = LeagueTab.entries
            val current = tabs.getOrElse(tab) { LeagueTab.LADDER }
            // Seven short tabs fit a phone's width side by side (a scrolling row hid the last ones):
            // without the default 16 dp padding on each side of a tab's text.
            TabRow(selectedTabIndex = current.ordinal, containerColor = cs.background) {
                for (t in tabs) {
                    Tab(selected = current == t, onClick = { lg.tab = t.ordinal }) {
                        Text(t.label, maxLines = 1, fontSize = 13.sp, softWrap = false, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
            if (v == null) {
                CenterText("리그 기록을 불러오는 중…")
            } else {
                when (current) {
                    LeagueTab.LADDER -> LadderList(lg, v) { lg.detail = it }
                    LeagueTab.SCHEDULE -> ScheduleTab(
                        lg, v,
                        onLeague = { lg.tab = LeagueTab.SEASON.ordinal },
                        onComp = { comp ->
                            if (comp == Comp.CLUB) {
                                lg.tab = LeagueTab.CLUBS.ordinal
                            } else {
                                lg.compFocus = comp
                                lg.tab = LeagueTab.COMPS.ordinal
                            }
                        },
                    )
                    LeagueTab.SEASON -> SeasonList(lg, v.season) { lg.detail = it }
                    LeagueTab.COMPS -> CompsTab(lg, v.comps, v.clock) { lg.detail = it }
                    LeagueTab.CLUBS -> ClubsTab(lg, v.clubs) { lg.detail = it }
                    LeagueTab.NEWS -> NewsTab(c, v) { FeedList(lg, v) }
                    LeagueTab.RECORDS -> RecordsTab(lg, v, { lg.detail = it }) { FamilyList(lg, v) { lg.detail = it } }
                }
            }
        }
    }
    val key = lg.detail
    val row = v?.rows?.firstOrNull { it.key == key }
    if (row != null) AiDetail(c, row) { lg.detail = null }
    else if (key != null && v != null) PastProfileDialog(lg, key) { lg.detail = null }
    if (rankedAsk) RankedStartDialog(c) { rankedAsk = false }
    if (rulesAsk) LeagueRulesDialog(lg) { rulesAsk = false }
    if (labAsk) LabDialog(lg) { labAsk = false }
    lg.preview?.let { PreviewDialog(lg, it) }
    lg.review?.let { if (lg.detail == null) SeasonReviewDialog(lg, it) }
    if (resetAsk) {
        AlertDialog(
            onDismissRequest = { resetAsk = false },
            confirmButton = { TextButton(onClick = { resetAsk = false; lg.reset() }) { Text("초기화") } },
            dismissButton = { TextButton(onClick = { resetAsk = false }) { Text("취소") } },
            title = { Text("리그를 처음부터 다시 할까요?") },
            text = { Text("모든 경기 기록과 레이팅이 지워져요.", style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

@Composable
private fun ControlCard(c: AppController, v: LeagueView?, onSchedule: () -> Unit) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (lg.running) cs.primaryContainer else cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val prep = lg.preparing
                    Text(
                        when {
                            prep != null -> prep
                            lg.running -> "🔴 리그 진행 중"
                            (v?.matches ?: 0) > 0 -> "⏸ 멈춤"
                            else -> "아직 경기가 없어요"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            lg.running && v != null -> "분당 ${"%.0f".format(lg.pace)}경기 · 지금 ${v.live.size}경기 진행 중"
                            lg.background == LeagueBackground.KEEP -> "앱을 나가도 계속 돌아요. 결과는 저장돼요"
                            else -> "앱을 나가면 멈추고, 돌아오면 이어서 해요"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                if (lg.preparing != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                if (lg.running) {
                    Button(onClick = { lg.stop() }, colors = ButtonDefaults.buttonColors(containerColor = cs.error)) { Text("■ 정지") }
                } else {
                    Button(onClick = { lg.start() }, enabled = v != null) { Text(if ((v?.matches ?: 0) > 0) "▶ 이어서" else "▶ 시작") }
                }
            }
            lg.stopReason?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
            v?.clock?.let { ClockLine(it, onSchedule) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in LeagueSpeed.entries) {
                    FilterChip(selected = lg.speed == s, onClick = { lg.changeSpeed(s) }, label = { Text(s.label, fontSize = 12.sp) })
                }
            }
            // What happens when the app is hidden: stop (battery) or keep playing.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("앱을 나가면", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                for (b in LeagueBackground.entries) {
                    FilterChip(selected = lg.background == b, onClick = { c.chooseLeagueBackground(b) }, label = { Text(b.label, fontSize = 12.sp) })
                }
            }
            if (lg.background == LeagueBackground.KEEP) {
                Text(
                    "진행 중에는 알림이 떠 있고, 화면을 꺼도 계속 돌아요(배터리를 많이 써요). 알림의 ‘정지’로 멈출 수 있어요. " +
                        "최근 앱 목록에서 앱을 지우면 멈추고, 다시 열면 이어서 해요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
            if (v != null && v.matches == 0L && lg.hasSeed && !lg.running) {
                OutlinedButton(onClick = { lg.loadSeed() }, modifier = Modifier.fillMaxWidth()) { Text("⚡ 미리 돌린 리그로 시작 (서버에서 둔 12만 경기)") }
            }
        }
    }
}

/** Watches a featured game (a top AI against a rival). */
private fun watchFeatured(c: AppController) {
    val lg = c.league
    val (a, b) = lg.featured(java.util.Random()) ?: return
    lg.startWatch(a, b) { c.aiPace.stepMs.coerceAtLeast(400L) }
    c.screen = Screen.WATCH
}

/** Banner of a season that just ended. */
@Composable
private fun SeasonNews(h: SeasonHistoryItem, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🏆 시즌 ${h.number} 종료! 우승 ${h.champions.firstOrNull() ?: "–"}", fontWeight = FontWeight.Bold, color = cs.onTertiaryContainer)
                Text(
                    listOfNotNull(
                        h.topScorer?.let { "득점왕 $it" },
                        h.yachtKing?.let { "요트왕 $it" },
                        h.promoted.takeIf { it.isNotEmpty() }?.let { "승격 ${it.size}팀" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onTertiaryContainer,
                )
            }
            TextButton(onClick = onDismiss) { Text("닫기") }
        }
    }
}

// ---- ladder ------------------------------------------------------------------------------------

@Composable
private fun LadderList(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit) {
    var family by remember { mutableStateOf<Family?>(null) }
    var sort by remember { mutableStateOf(LadderSort.RATING) }
    var onlyFavourites by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf<yacht.engine.league.Origin?>(null) }
    var query by remember { mutableStateOf("") }
    val favs = lg.favourites
    val rows = v.rows.filter {
        (family == null || it.family == family) && (!onlyFavourites || it.key in favs) && (origin == null || it.origin == origin) &&
            (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
    }.let { r ->
        when (sort) {
            LadderSort.RATING -> r
            LadderSort.WIN_RATE -> r.sortedByDescending { it.winRate }
            LadderSort.SCORE -> r.sortedByDescending { it.avgScore }
            LadderSort.GAMES -> r.sortedByDescending { it.games }
            LadderSort.STREAK -> r.sortedByDescending { it.streak }
        }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("AI 이름 검색", fontSize = 13.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = onlyFavourites, onClick = { onlyFavourites = !onlyFavourites }, label = { Text("★ 응원 ${favs.size}", fontSize = 12.sp) })
                for (o in yacht.engine.league.Origin.entries) {
                    val n = v.rows.count { it.origin == o }
                    if (n > 0) FilterChip(selected = origin == o, onClick = { origin = if (origin == o) null else o }, label = { Text("${o.label} $n", fontSize = 12.sp) })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = family == null, onClick = { family = null }, label = { Text("전체", fontSize = 12.sp) })
                for (f in Family.entries) {
                    FilterChip(selected = family == f, onClick = { family = if (family == f) null else f }, label = { Text(f.label, fontSize = 12.sp) })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("정렬", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (s in LadderSort.entries) {
                    FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label, fontSize = 12.sp) })
                }
            }
        }
        if (lg.clone == null) {
            item {
                Text(
                    "🧬 ‘혼자 두기’로 ${yacht.engine.league.CloneProfile.MIN_GAMES}판 이상 끝내면 내 실수 습관을 닮은 ‘내 분신’ AI가 리그에 들어와요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (v.matches == 0L) {
            item {
                Text(
                    "▶ 시작을 누르면 AI들이 비슷한 레이팅끼리 붙어요. 한 경기는 같은 주사위로 선공·후공을 바꿔 두 판이고, " +
                        "${Ladder.PLACEMENT}판을 두면 배치가 끝나 티어가 붙어요. 처음 몇십 판은 레이팅이 크게 흔들리고(± 표시), 판이 쌓일수록 자리를 잡아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        if (onlyFavourites && rows.isEmpty()) {
            item { Text("AI를 눌러 ‘☆ 응원하기’를 고르면 여기에 모여요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(rows, key = { it.key }) { r -> LadderItem(r, r.key in favs) { onOpen(r.key) } }
        item {
            Text(
                "레이팅: 모든 판을 브래들리-테리 모형(엘로와 같은 눈금)으로 맞춘 값, 기대점수 AI = 1500. ±는 95% 범위, 7점 ≈ 승률 1%p. " +
                    "순위는 레이팅 순서예요. 판이 적어 ± 범위가 넓은(±20 넘게) AI만 넘는 만큼의 3배를 내려서 매겨서, 운 좋은 출발로 꼭대기에 오르지 않아요. 판이 충분하면 판 수와 상관없이 레이팅만 봐요. " +
                    "티어는 레이팅으로 매겨요: 마스터 1490↑, 그랜드마스터 1500↑ 상위 10, 챌린저 1505↑ 상위 3. 그래서 판이 적은 AI는 티어가 높아도 아래에 놓일 수 있어요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LadderItem(r: LadderRow, favourite: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                r.isMe -> cs.primaryContainer
                r.playing -> cs.secondaryContainer.copy(alpha = 0.45f)
                else -> cs.surface
            },
        ),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(r.rank?.toString() ?: "–", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                RankChange(r.rankChange)
            }
            TierBadge(r.tier, r.games, Modifier.width(78.dp), if (r.isMe) Ladder.ME_PLACEMENT else Ladder.PLACEMENT)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (favourite) Text("★ ", color = cs.secondary, fontSize = 13.sp)
                    Text(
                        if (r.isMe) "🙋 나 (랭크전)" else r.name,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (r.playing) Text(" ⚔️", fontSize = 11.sp)
                    r.division?.let { Text(" ${it + 1}부", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                }
                Text(
                    "${r.wins}승 ${if (r.ties > 0) "${r.ties}무 " else ""}${r.losses}패 · ${"%.1f".format(100 * r.winRate)}% · ${"%.0f".format(r.avgScore)}점",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FormDots(r.recent)
                    StreakText(r.streak)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(r.rating?.toString() ?: "–", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                r.margin?.let { Text("±$it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                if (r.lastDelta != 0) {
                    Text(
                        "%+d".format(r.lastDelta),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (r.lastDelta > 0) gameColors.good else cs.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun RankChange(change: Int?) {
    val cs = MaterialTheme.colorScheme
    when {
        change == null || change == 0 -> Text("–", style = MaterialTheme.typography.labelSmall, color = cs.outline)
        change > 0 -> Text("▲$change", style = MaterialTheme.typography.labelSmall, color = gameColors.good, fontWeight = FontWeight.Bold)
        else -> Text("▼${-change}", style = MaterialTheme.typography.labelSmall, color = cs.error, fontWeight = FontWeight.Bold)
    }
}

/** Badge colours of each tier. */
private fun tierColor(t: TierName): Color = when (t) {
    TierName.IRON -> Color(0xFF6E6A6A)
    TierName.BRONZE -> Color(0xFF9C5B33)
    TierName.SILVER -> Color(0xFF8A9AA6)
    TierName.GOLD -> Color(0xFFC9971C)
    TierName.PLATINUM -> Color(0xFF2A9D8F)
    TierName.EMERALD -> Color(0xFF1F9D55)
    TierName.DIAMOND -> Color(0xFF4C6FD8)
    TierName.MASTER -> Color(0xFF9B4FC9)
    TierName.GRANDMASTER -> Color(0xFFD1403F)
    TierName.CHALLENGER -> Color(0xFFE0A100)
}

private val TierName.badge: String
    get() = when (this) {
        TierName.DIAMOND -> "다이아"
        TierName.EMERALD -> "에메랄드"
        TierName.PLATINUM -> "플래티넘"
        TierName.GRANDMASTER -> "그마"
        else -> label
    }

@Composable
private fun TierBadge(t: Tier?, games: Int, modifier: Modifier = Modifier, placement: Int = Ladder.PLACEMENT) {
    val bg = t?.let { tierColor(it.name) } ?: MaterialTheme.colorScheme.surfaceVariant
    val fg = if (t == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (t == null) {
            Text("배치 중", color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("$games/${placement}판", color = fg, fontSize = 10.sp)
        } else {
            Text(t.name.badge + (t.division?.let { " ${roman(it)}" } ?: ""), color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            Text("${t.lp} LP", color = fg.copy(alpha = 0.9f), fontSize = 10.sp)
        }
    }
}

private fun roman(d: Int) = when (d) {
    1 -> "I"
    2 -> "II"
    3 -> "III"
    else -> "IV"
}

@Composable
fun FormDots(recent: String) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (ch in recent.takeLast(10)) {
            val color = when (ch) {
                'W' -> gameColors.good
                'L' -> cs.error
                else -> cs.outline
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun StreakText(streak: Int) {
    when {
        streak >= 3 -> Text("  🔥${streak}연승", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        streak <= -3 -> Text("  🥶${-streak}연패", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

// ---- feed --------------------------------------------------------------------------------------

@Composable
private fun FeedList(lg: LeagueController, v: LeagueView) {
    val cs = MaterialTheme.colorScheme
    var kind by remember { mutableStateOf(FeedKind.ALL) }
    val shown = remember(v.feed, kind) { v.feed.filter { kind.matches(it.comp) } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in FeedKind.entries) FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label, fontSize = 12.sp) })
            }
        }
        if (v.live.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("⚔️ 지금 경기 중", fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                        for (m in v.live) {
                            val team = m.comp == Comp.ALL_STAR || m.comp == Comp.FAMILY
                            fun side(k: String) = if (team) yacht.engine.league.Competitions.teamLabel(k) else lg.name(k)
                            Text(
                                "${stageName(m.comp, m.label, short = true)}  ${side(m.a)}  vs  ${side(m.b)}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (v.feed.isEmpty()) item { Text("아직 끝난 경기가 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        else if (shown.isEmpty()) item { Text("고른 종류의 경기 결과가 아직 없어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        val weeks = v.clock?.leagueWeeks
        items(shown, key = { "${it.time}-${it.a}-${it.b}" }) { FeedCard(lg, it, weeks) }
    }
}

/** Which results the feed shows. */
private enum class FeedKind(val label: String) {
    ALL("전체"),
    LEAGUE("📋 리그"),
    COMPS("🏆 대회"),
    FRIENDLY("🤝 친선");

    fun matches(c: Comp?): Boolean = when (this) {
        ALL -> true
        LEAGUE -> c == Comp.LEAGUE
        COMPS -> c != null && c != Comp.LEAGUE && c != Comp.FRIENDLY && c != Comp.LEGEND && c != Comp.LAB
        FRIENDLY -> c == Comp.FRIENDLY || c == Comp.LEGEND
    }
}

/** Where a result was played: "🏆 요트컵 64강 · 14주차", "📋 리그 12라운드"; null for results saved before. */
private fun whereLine(m: FeedItem, leagueWeeks: Int?): String? {
    val c = m.comp ?: return null
    if (c == Comp.FRIENDLY) return "🤝 친선 경기"
    val week = if (c == Comp.LEAGUE || m.week <= 0) "" else weekName(m.week, leagueWeeks).let { if (it.isEmpty()) "" else " · $it" }
    return stageName(c, m.label) + week
}

@Composable
private fun FeedCard(lg: LeagueController, m: FeedItem, leagueWeeks: Int?) {
    val cs = MaterialTheme.colorScheme
    val aPts: Int = m.games.map { if (it.a > it.b) 2 else if (it.a == it.b) 1 else 0 }.sum()
    val bPts = m.games.size * 2 - aPts
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            whereLine(m, leagueWeeks)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeedSide(m.aName, m.deltaA, aPts > bPts, Modifier.weight(1f), TextAlign.Start)
                Text(
                    m.games.joinToString("  ") { "${it.a}:${it.b}" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                FeedSide(m.bName, m.deltaB, bPts > aPts, Modifier.weight(1f), TextAlign.End)
            }
            for (n in m.notes) noteText(lg, n)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.secondary, fontWeight = FontWeight.Bold) }
            m.curve?.let { cv -> WinCurve(ByteArray(cv.size) { cv[it].toByte() }, "", "", height = 40.dp) }
        }
    }
}

@Composable
private fun FeedSide(name: String, delta: Int, won: Boolean, modifier: Modifier, align: TextAlign) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = if (won) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
        if (delta != 0) {
            Text("%+d".format(delta), style = MaterialTheme.typography.labelSmall, color = if (delta > 0) gameColors.good else cs.error)
        }
    }
}

/** A feed note ("kind|key|value") as a headline. */
private fun noteText(lg: LeagueController, note: String): String? {
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
        "이변" -> "😱 이변! $who, 레이팅 ${p[2]}점 높은 상대를 두 판 다 이겼어요"
        else -> null
    }
}

// ---- season ------------------------------------------------------------------------------------

@Composable
private fun SeasonList(lg: LeagueController, season: SeasonView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (season == null || season.divisions.isEmpty()) {
        CenterText("▶ 리그를 시작하면 지금 순위대로 부가 나뉘고 시즌 1이 열려요.\n같은 부끼리 라운드마다 한 번씩 맞붙어 승점(승 3, 무 1, 한 판씩이면 합계 점수로 승부)을 쌓고, 시즌이 끝나면 위아래로 승강하고 승강 플레이오프를 치러요.")
        return
    }
    var division by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(SeasonMode.TABLE) }
    val d = division.coerceIn(0, season.divisions.size - 1)
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "시즌 ${season.number}" + if (season.matchdays > 0) " · ${minOf(season.currentMatchday + 1, season.matchdays)}주차 / ${season.matchdays}주" else "",
                            fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        Text("${season.played}/${season.total}경기", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    LinearProgressIndicator(
                        progress = { if (season.total == 0) 0f else season.played.toFloat() / season.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "부마다 라운드마다 한 번씩 맞붙어요(같은 주사위로 두 판). 승 3점·무 1점, 한 판씩 나눠 가지면 두 판 합계 점수로 승부. 🟢 승격 · 🔴 강등 · 흐린 색은 승강 플레이오프. " +
                            "순위표는 모든 경기로 매긴 실력 점수(레이팅)이고, 1~4부는 이번 시즌 리그 성적(승점)이라 둘이 달라요. 실력이 좋아도 아래 부에서 시작하면 승격으로 올라와야 해요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    if (season.newcomers.isNotEmpty()) {
                        Text(
                            "다음 시즌 ${season.divisions.size}부 합류 대기: ${season.newcomers.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.secondary,
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in season.divisions.indices) {
                    FilterChip(selected = d == i, onClick = { division = i }, label = { Text("${i + 1}부 (${season.divisions[i].size})", fontSize = 12.sp) })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in SeasonMode.entries) {
                    FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 12.sp) })
                }
            }
        }
        when (mode) {
            SeasonMode.LUCK -> {
                item { LuckHeader() }
                val rows = season.divisions[d].sortedWith(compareByDescending<SeasonRow> { it.aggPoints }.thenByDescending { it.diff })
                itemsIndexed(rows, key = { _, r -> "a-${r.key}" }) { i, r -> LuckRow(i, r, season.divisions[d].indexOf(r)) { onOpen(r.key) } }
            }
            SeasonMode.CALENDAR -> item { CalendarCard(lg, d, season.number, season.played, onOpen) }
            SeasonMode.CHART -> item { RankChartCard(lg, season, d) }
            SeasonMode.ODDS -> {
                item {
                    Text(
                        "남은 경기를 레이팅으로 2,000번 시뮬레이션한 확률이에요(시즌 10%마다 다시 계산).",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                }
                val rows = season.divisions[d]
                if (rows.none { it.odds != null }) item { Text("확률을 계산하는 중이에요. 리그가 돌아가면 곧 나와요.", style = MaterialTheme.typography.bodySmall) }
                else items(rows, key = { "o-${it.key}" }) { r -> OddsRow(r, d, season.divisions.size) { onOpen(r.key) } }
            }
            SeasonMode.H2H -> item {
                // The table's order when opened (it reorders with nearly every result); the
                // numbers come again every few seconds in the same order, keeping the cell picked.
                val keys = remember(d, season.number) { season.divisions[d].map { it.key } }
                androidx.compose.runtime.LaunchedEffect(keys, lg.running) {
                    lg.loadH2h("${d + 1}부", keys)
                    // Only while the league runs (it stops when the app is hidden).
                    while (lg.running) {
                        kotlinx.coroutines.delay(H2H_REFRESH_MS)
                        lg.loadH2h("${d + 1}부", keys)
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(10.dp)) {
                        val m = lg.h2h
                        if (m == null || m.title != "${d + 1}부") Text("상대 전적을 모으는 중…", style = MaterialTheme.typography.bodySmall)
                        else H2hGrid(m)
                    }
                }
            }
            SeasonMode.TABLE -> {}
        }
        if (mode == SeasonMode.TABLE) item {
            Row(Modifier.padding(horizontal = 8.dp)) {
                Text("#", Modifier.width(24.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("팀", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("경기", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("승-무-패", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("득실", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("승점", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
        if (mode == SeasonMode.TABLE) itemsIndexed(season.divisions[d], key = { _, r -> "s-${r.key}" }) { i, r ->
            val zone = when (r.zone) {
                1 -> gameColors.good
                -1 -> cs.error
                2 -> gameColors.good.copy(alpha = 0.4f)
                -2 -> cs.error.copy(alpha = 0.4f)
                else -> Color.Transparent
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable { onOpen(r.key) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(4.dp).height(30.dp).background(zone))
                Text("${i + 1}", Modifier.width(24.dp).padding(start = 4.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.played}", Modifier.width(30.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                Text("${r.wins}-${r.draws}-${r.losses}", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                Text("%+d".format(r.diff), Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("${r.points}", Modifier.width(36.dp).padding(end = 6.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
            }
        }
        if (season.history.isNotEmpty()) {
            item {
                Text("🏛 지난 시즌 우승팀과 수상자는 ‘기록’ 탭 명예의 전당에 있어요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** One AI's chances: the title (or a playoff place) at the top, promotion and relegation below. */
@Composable
private fun OddsRow(r: SeasonRow, division: Int, divisions: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val o = r.odds ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(r.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val cells = buildList {
            if (division == 0) {
                add("우승" to o.title)
                add("4강 PO" to o.playoff)
                add("챔스" to o.champions)
            } else {
                add("${division + 1}부 1위" to o.leader)
                add("승격" to o.promotion)
            }
            if (division < divisions - 1) add("강등" to o.relegation)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((label, p) in cells) {
                Text(
                    "$label ${oddsPct(p)}", style = MaterialTheme.typography.labelSmall,
                    color = when {
                        label == "강등" && p >= 0.5 -> cs.error
                        label != "강등" && p >= 0.5 -> gameColors.good
                        else -> cs.onSurfaceVariant
                    },
                    fontWeight = if (p >= 0.5) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun oddsPct(p: Double): String = when {
    p >= 0.995 -> "확정"
    p <= 0.0 -> "–"
    p < 0.01 -> "<1%"
    else -> "${"%.0f".format(100 * p)}%"
}

private enum class SeasonMode(val label: String) {
    TABLE("순위표"),
    LUCK("운 보정 순위"),
    CALENDAR("일정표"),
    CHART("순위 변동"),
    H2H("상대 전적"),
    ODDS("우승·승강 확률"),
}

@Composable
private fun LuckHeader() {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "같은 주사위로 두 판을 두니까, 두 판 합계 점수가 높은 쪽에 3점(같으면 1점)을 주면 판 운을 뺀 순위가 나와요. '운'은 실제 승점에서 운 보정 승점을 뺀 값이에요.",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            Text("#", Modifier.width(24.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text("팀", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text("실제", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
            Text("운", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
            Text("보정", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun LuckRow(i: Int, r: SeasonRow, realPlace: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val luck = r.points - r.aggPoints
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${i + 1}", Modifier.width(24.dp).padding(start = 8.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(r.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (realPlace != i) {
                Text("실제 ${realPlace + 1}위", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        Text("${r.points}", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        Text(
            "%+d".format(luck), Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End,
            color = if (luck > 0) gameColors.good else if (luck < 0) cs.error else cs.onSurfaceVariant,
        )
        Text("${r.aggPoints}", Modifier.width(40.dp).padding(end = 6.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
}

/** 일정표: one matchday of a division, played and to come. */
@Composable
private fun CalendarCard(lg: LeagueController, division: Int, season: Int, played: Int, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cal = lg.calendar
    // Again as the season moves on (results, games in progress), on the round shown; a new
    // season or division opens on its current round.
    // The round asked for here (null: the one being played), kept for the reloads.
    var wanted by remember(division, season) { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(division, season, played) { lg.loadCalendar(division, wanted) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (cal == null || cal.division != division) {
                Text("일정을 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            } else {
                CalendarBody(lg, cal, onOpen) { md ->
                    wanted = md
                    lg.loadCalendar(division, md)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CalendarBody(lg: LeagueController, cal: yacht.app.core.CalendarView, onOpen: (String) -> Unit, onRound: (Int?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onRound(cal.matchday - 1) }, enabled = cal.matchday > 0) { Text("◀") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${cal.matchday + 1}라운드 / ${cal.matchdays}", fontWeight = FontWeight.Bold)
            Text(
                when {
                    cal.matchday < cal.current -> "끝난 라운드"
                    cal.matchday == cal.current -> "진행 중인 라운드"
                    else -> "다가오는 라운드"
                },
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onRound(cal.matchday + 1) }, enabled = cal.matchday < cal.matchdays - 1) { Text("▶") }
    }
    if (cal.matchday != cal.current) {
        TextButton(onClick = { onRound(null) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("지금 라운드로") }
    }
    for (f in cal.items) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val g = f.games
            val aWon = g != null && yacht.engine.league.TieRule.match(g) > 0
            val bWon = g != null && yacht.engine.league.TieRule.match(g) < 0
            Text(
                f.aName, Modifier.weight(1f).clickable { onOpen(f.a) }, style = MaterialTheme.typography.bodySmall,
                fontWeight = if (aWon) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    g != null -> g.joinToString(" ") { "${it.a}:${it.b}" }
                    f.live -> "⚔️ 경기 중"
                    else -> "🔮 프리뷰"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (g != null) FontWeight.Bold else FontWeight.Normal,
                color = if (g == null) cs.primary else cs.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp).clickable { lg.loadPreview(f.a, f.b) },
            )
            Text(
                f.bName, Modifier.weight(1f).clickable { onOpen(f.b) }, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End,
                fontWeight = if (bWon) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 순위 변동 그래프: the division's top and bottom places over the matchdays. */
@Composable
private fun RankChartCard(lg: LeagueController, season: SeasonView, division: Int) {
    val cs = MaterialTheme.colorScheme
    val order = season.order.getOrNull(division).orEmpty()
    val positions = season.positions.getOrNull(division).orEmpty()
    val table = season.divisions.getOrNull(division).orEmpty()
    // The top 4 and the bottom 2 now, unless some are picked.
    var picked by remember(division) { mutableStateOf<List<String>>(emptyList()) }
    val shown = picked.ifEmpty { (table.take(4) + table.takeLast(2)).map { it.key }.distinct() }
    val series = shown.mapIndexedNotNull { i, k ->
        val at = order.indexOf(k)
        if (at < 0) null else RankSeries(lg.name(k), positions.map { it[at].toInt() }, chartColors[i % chartColors.size])
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("라운드마다 순위가 어떻게 바뀌었는지 보여줘요. 아래에서 팀을 눌러 골라 보세요 (최대 8팀).", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            RankChart(series, order.size)
            // Lazy: a division can hold dozens of AIs (hundreds with one division).
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(table, key = { "c-${it.key}" }) { r ->
                    val on = r.key in shown
                    FilterChip(
                        selected = on,
                        onClick = {
                            val base = picked.ifEmpty { shown }
                            picked = if (on) base - r.key else (base + r.key).takeLast(8)
                        },
                        label = { Text(r.name, fontSize = 11.sp, maxLines = 1) },
                    )
                }
            }
        }
    }
}

// ---- families ----------------------------------------------------------------------------------

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

@Composable
private fun FamilyList(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit) {
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
                    for (r in rows.sortedBy { it.rank ?: Int.MAX_VALUE }) {
                        FamilyMember(r.key, r.rank, r.name, r.tier?.label, r.tier?.name?.let { tierColor(it) }, r.rating, onOpen)
                    }
                }
            }
        }
    }
}

// ---- one AI ------------------------------------------------------------------------------------

@Composable
private fun AiDetail(c: AppController, r: LadderRow, onDismiss: () -> Unit) {
    val lg = c.league
    val cs = MaterialTheme.colorScheme
    // Read with the profile, off the main thread (the ladder's lock is busy while the league runs).
    androidx.compose.runtime.LaunchedEffect(r.key) { lg.loadProfile(r.key) }
    val profile = lg.profile?.takeIf { it.key == r.key }
    val best = profile?.best.orEmpty()
    val worst = profile?.worst.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TierBadge(r.tier, r.games, Modifier.width(78.dp), if (r.isMe) Ladder.ME_PLACEMENT else Ladder.PLACEMENT)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(r.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (r.rank?.let { "${it}위 · " } ?: "") + "레이팅 ${r.rating ?: "–"}" + (r.margin?.let { " ±$it" } ?: "") + (r.peak?.let { " · 최고 $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${r.family.label} · ${r.desc}" + if (r.heavy) " (승률 탐색을 써서 계산이 무거워요)" else "", style = MaterialTheme.typography.bodySmall)
                if (!r.isMe) ProfileSection(lg, r.key)
                if (r.key == yacht.engine.league.CustomAis.CLONE) {
                    lg.clone?.let { p ->
                        Text(
                            "혼자 둔 최근 ${p.games}판에서 남기기 ${p.keepDecisions}번 중 ${"%.0f".format(100 * p.keepSlip)}%, " +
                                "칸 고르기 ${p.boxDecisions}번 중 ${"%.0f".format(100 * p.boxSlip)}%를 추천과 다르게 뒀어요. 분신도 그만큼 차선을 둬요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.secondary,
                        )
                    }
                }
                if (!r.isMe) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val fav = r.key in lg.favourites
                        AssistChip(onClick = { lg.toggleFavourite(r.key) }, label = { Text(if (fav) "★ 응원 중" else "☆ 응원하기") })
                        AssistChip(onClick = { onDismiss(); c.newRankedGame(r.key) }, label = { Text("🎮 이 AI와 랭크전") })
                        AssistChip(
                            onClick = {
                                val rival = lg.rivalOf(r.key, java.util.Random())
                                if (rival != null) {
                                    onDismiss()
                                    lg.startWatch(r.key, rival) { c.aiPace.stepMs.coerceAtLeast(400L) }
                                    c.screen = Screen.WATCH
                                }
                            },
                            label = { Text("👀 관전") },
                        )
                        val mine = lg.customs.firstOrNull { it.key == r.key }
                        if (mine != null) {
                            AssistChip(onClick = { onDismiss(); c.editingAi = mine; c.screen = Screen.BUILDER }, label = { Text("🛠 고치기") })
                        }
                    }
                }
                StatGrid(
                    listOf(
                        "경기" to "${r.games}판",
                        "전적" to "${r.wins}승 ${r.ties}무 ${r.losses}패",
                        "승률" to "%.1f%%".format(100 * r.winRate),
                        "평균 점수" to "%.1f".format(r.avgScore),
                        "최고 점수" to "${r.best}",
                        "요트" to if (r.games > 0) "%.0f%%".format(100.0 * r.yachts / r.games) else "–",
                        "보너스" to if (r.games > 0) "%.0f%%".format(100.0 * r.bonuses / r.games) else "–",
                        "최다 연승" to "${r.bestStreak}",
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("최근 ${r.recent.length}판  ", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    FormDots(r.recent)
                    StreakText(r.streak)
                }
                if (r.history.size >= 2) {
                    Text("레이팅 흐름 (최근 ${r.history.size}경기)", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    RatingLine(r.history)
                }
                if (best.isNotEmpty()) {
                    Text("😊 잘 잡는 상대", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    for (m in best) MatchupLine(m.name, m.score, m.games)
                }
                if (worst.isNotEmpty()) {
                    Text("😣 천적", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    for (m in worst) MatchupLine(m.name, m.score, m.games)
                }
            }
        },
    )
}

@Composable
private fun StatGrid(cells: List<Pair<String, String>>) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in cells.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, v) in row) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MatchupLine(name: String, score: Double, games: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("%.0f%%".format(100 * score), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text("  ${games}판", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RatingLine(points: List<Int>) {
    val line = gameColors.me
    val grid = MaterialTheme.colorScheme.outlineVariant
    // At least 20 points tall, so a 1-point wobble does not look like a swing.
    val mid = (points.min() + points.max()) / 2
    val lo = minOf(points.min(), mid - 10)
    val hi = maxOf(points.max(), mid + 10)
    Column {
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
            val span = maxOf(1, hi - lo).toFloat()
            val step = size.width / (points.size - 1)
            fun y(v: Int) = size.height * (1f - (v - lo) / span) * 0.9f + size.height * 0.05f
            for (i in 1 until points.size) {
                drawLine(line, Offset(step * (i - 1), y(points[i - 1])), Offset(step * i, y(points[i])), strokeWidth = 4f)
            }
        }
        Row {
            Text("$lo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("$hi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How often an open 상대 전적 matrix reads the numbers again while the league runs. */
private const val H2H_REFRESH_MS = 10_000L

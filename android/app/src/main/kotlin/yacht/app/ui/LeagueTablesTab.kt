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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.LadderRow
import yacht.app.core.LadderSort
import yacht.app.core.LeagueController
import yacht.app.core.LeagueNav
import yacht.app.core.LeagueView
import yacht.app.core.SeasonRow
import yacht.app.core.SeasonView
import yacht.app.core.TableMode
import yacht.engine.league.Family
import yacht.engine.league.Ladder

/** Shows division [d] in the 리그 tab: its tier and group, at its current round with the chart's default players. */
internal fun LeagueNav.showDivision(tiers: yacht.engine.league.Tiers, d: Int) {
    tier = tiers.tier(d)
    group = tiers.group(d)
    round = null
    chartKeys = emptyList()
}

// ---- ladder ------------------------------------------------------------------------------------

@Composable
internal fun LadderList(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit) {
    val nav = lg.nav
    val filter = nav.ladderFilter
    val family = filter.family
    val sort = nav.ladderSort
    val onlyFavourites = filter.followed
    val origin = filter.origin
    val nation = filter.nation
    val query = nav.ladderQuery
    val favs = lg.favourites
    // A search also finds a nation by its name ("브라질").
    val q = query.trim()
    val byName = nationsMatching(q)
    val rows = v.rows.filter {
        // A league of generated players only shows the hand-made AIs under their own chip.
        (family == null || it.family == family) && (!onlyFavourites || it.key in favs) &&
            (if (origin == null) lg.config.baseAis || it.origin != yacht.engine.league.Origin.BASE else it.origin == origin) &&
            (nation == null || it.nation == nation) && (q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.nation in byName)
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
                onValueChange = { nav.ladderQuery = it },
                placeholder = { Text("선수 이름이나 나라 검색", fontSize = 13.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = onlyFavourites, onClick = { nav.ladderFilter = filter.copy(followed = !onlyFavourites) }, label = { Text("★ 응원 ${favs.count { !it.startsWith(NATION_PREFIX) }}", fontSize = 12.sp) })
                for (o in yacht.engine.league.Origin.entries) {
                    val n = v.rows.count { it.origin == o }
                    if (n > 0) FilterChip(selected = origin == o, onClick = { nav.ladderFilter = filter.copy(origin = if (origin == o) null else o) }, label = { Text("${o.label} $n", fontSize = 12.sp) })
                }
            }
        }
        // Nations of the league: followed nations first, then most players first.
        val nations = v.rows.filter { it.nation.isNotEmpty() }.groupingBy { it.nation }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { nationFavourite(it.key) in favs }.thenByDescending { it.value })
        if (nations.size > 1) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = nation == null, onClick = { nav.ladderFilter = filter.copy(nation = null) }, label = { Text("🌐 전체 국적", fontSize = 12.sp) })
                    for ((code, n) in nations) {
                        val nat = yacht.engine.league.Nations.of(code) ?: continue
                        FilterChip(selected = nation == code, onClick = { nav.ladderFilter = filter.copy(nation = if (nation == code) null else code) }, label = { Text((if (nationFavourite(code) in favs) "★" else "") + "${nat.flag} ${nat.short} $n", fontSize = 12.sp) })
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = family == null, onClick = { nav.ladderFilter = filter.copy(family = null) }, label = { Text("전체", fontSize = 12.sp) })
                for (f in Family.entries) {
                    FilterChip(selected = family == f, onClick = { nav.ladderFilter = filter.copy(family = if (family == f) null else f) }, label = { Text(f.label, fontSize = 12.sp) })
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("정렬", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (s in LadderSort.entries) {
                    FilterChip(selected = sort == s, onClick = { nav.ladderSort = s }, label = { Text(s.label, fontSize = 12.sp) })
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
                        if (r.isMe) "🙋 나 (랭크전)" else r.shown,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (r.playing) Text(" ⚔️", fontSize = 11.sp)
                    r.divisionLabel?.let { Text(" $it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
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

// ---- season ------------------------------------------------------------------------------------

@Composable
internal fun SeasonList(lg: LeagueController, season: SeasonView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (season == null || season.divisions.isEmpty()) {
        CenterText(
            "▶ 리그를 시작하면 지금 순위대로 부가 나뉘고 시즌 1이 열려요.\n같은 부끼리 라운드마다 한 번씩 맞붙어 승점을 쌓고, 시즌이 끝나면 위아래로 승강하고 승강 플레이오프를 치러요.\n" +
                matchRuleText(lg.config.matchFormat),
        )
        return
    }
    val tiers = season.tiers.takeIf { it.size == season.divisions.size } ?: yacht.engine.league.Tiers.linear(season.divisions.size)
    // A followed player's group first when a tier is picked (in a pyramid of dozens of groups, the one to watch).
    val favs = lg.favourites
    fun followed(range: IntRange) = range.firstOrNull { d -> season.divisions[d].any { it.key in favs } }
    val nav = lg.nav
    val mode = SeasonMode.of(nav.tableMode)
    val tier = nav.tier.coerceIn(0, tiers.count - 1)
    val d = tiers.groups(tier).let { g -> (g.first + nav.group).coerceIn(g.first, g.last) }
    val label = tiers.label(d)
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
                        "부마다 라운드마다 한 번씩 맞붙어요. ${matchRuleText(season.format)} 🟢 승격 · 🔴 강등 · 흐린 색은 승강 플레이오프. " +
                            "순위표는 모든 경기로 매긴 실력 점수(레이팅)이고, 부별 순위는 이번 시즌 리그 성적(승점)이라 둘이 달라요. 실력이 좋아도 아래 부에서 시작하면 승격으로 올라와야 해요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                    if (!tiers.linear) {
                        Text(
                            "🗺 선수가 많아 지역 리그 피라미드로 열려요: " + (0 until tiers.count).joinToString(" · ") { t ->
                                yacht.engine.league.Tiers.tierLabel(t) + if (tiers.groupCount(t) > 1) " ${tiers.groupCount(t)}개 조" else ""
                            } + ". 아래 부의 조마다 위 ${season.swap}명이 올라가고, 위 부에서는 그만큼 내려와요. 조는 시즌마다 지역별로 다시 짜요.",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.secondary,
                        )
                    }
                    if (season.newcomers.isNotEmpty()) {
                        Text(
                            "다음 시즌 ${tiers.count}부 합류 대기 ${season.newcomers.size}명: ${season.newcomers.take(10).joinToString(", ")}" +
                                if (season.newcomers.size > 10) " 외" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.secondary,
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in 0 until tiers.count) {
                    val groups = tiers.groups(t)
                    val n = groups.sumOf { season.divisions[it].size }
                    FilterChip(
                        selected = tiers.tier(d) == t,
                        onClick = { nav.showDivision(tiers, followed(groups) ?: groups.first) },
                        label = { Text(yacht.engine.league.Tiers.tierLabel(t) + if (groups.count() > 1) " ${groups.count()}개 조" else " ($n)", fontSize = 12.sp) },
                    )
                }
            }
        }
        if (tiers.groupCount(tiers.tier(d)) > 1) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (g in tiers.groups(tiers.tier(d))) {
                    val star = if (season.divisions[g].any { it.key in favs }) "★" else ""
                    FilterChip(
                        selected = d == g,
                        onClick = { nav.showDivision(tiers, g) },
                        label = { Text(star + tiers.groupLabel(g) + tiers.names[g].takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty(), fontSize = 12.sp) },
                    )
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in SeasonMode.entries) {
                    FilterChip(selected = mode == m, onClick = { nav.tableMode = m.mode }, label = { Text(m.label, fontSize = 12.sp) })
                }
            }
        }
        when (mode) {
            SeasonMode.LUCK -> {
                // 판 승점제: the 완승 bonus is in both columns (luck is only who took the level matches).
                val bonus = season.format.sweepBonus
                item { LuckHeader(bonus) }
                val rows = season.divisions[d].sortedWith(compareByDescending<SeasonRow> { fairPoints(it, bonus) }.thenByDescending { it.diff })
                itemsIndexed(rows, key = { _, r -> "a-${r.key}" }) { i, r -> LuckRow(i, r, season.divisions[d].indexOf(r), bonus) { onOpen(r.key) } }
            }
            SeasonMode.CALENDAR -> item { CalendarCard(lg, d, season.number, season.played, season.format.sweepBonus, onOpen) }
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
                else items(rows, key = { "o-${it.key}" }) { r -> OddsRow(r, tiers.tier(d), tiers.count, label) { onOpen(r.key) } }
            }
            SeasonMode.H2H -> item {
                // The table's order when opened (it reorders with nearly every result); the
                // numbers come again every few seconds in the same order, keeping the cell picked.
                val keys = remember(d, season.number) { season.divisions[d].map { it.key } }
                androidx.compose.runtime.LaunchedEffect(keys, lg.running) {
                    lg.loadH2h(label, keys)
                    // Only while the league runs (it stops when the app is hidden).
                    while (lg.running) {
                        kotlinx.coroutines.delay(H2H_REFRESH_MS)
                        lg.loadH2h(label, keys)
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(10.dp)) {
                        val m = lg.h2h
                        if (m == null || m.title != label) Text("상대 전적을 모으는 중…", style = MaterialTheme.typography.bodySmall)
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
                if (season.format.sweepBonus) Text("완승", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
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
                if (season.format.sweepBonus) Text("${r.sweeps}", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
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
private fun OddsRow(r: SeasonRow, tier: Int, tiers: Int, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val o = r.odds ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(r.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val cells = buildList {
            if (tier == 0) {
                add("우승" to o.title)
                add("4강 PO" to o.playoff)
                add("챔스" to o.champions)
            } else {
                add("$label 1위" to o.leader)
                add("승격" to o.promotion)
            }
            if (tier < tiers - 1) add("강등" to o.relegation)
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

/** The 리그 tab's views: the [TableMode]s it has ([mode]). */
private enum class SeasonMode(val label: String, val mode: TableMode) {
    TABLE("순위표", TableMode.TABLE),
    LUCK("운 보정 순위", TableMode.LUCK),
    CALENDAR("일정표", TableMode.ROUND),
    CHART("순위 변동", TableMode.CHART),
    H2H("상대 전적", TableMode.H2H),
    ODDS("우승·승강 확률", TableMode.ODDS);

    companion object {
        /** [m]'s view (최근 폼 has none here: the table). */
        fun of(m: TableMode): SeasonMode = entries.firstOrNull { it.mode == m } ?: TABLE
    }
}

/** 운 보정 승점: the two-game total's 3/1/0, plus the 완승 bonus points when the season gives them ([bonus]). */
private fun fairPoints(r: SeasonRow, bonus: Boolean): Int = r.aggPoints + if (bonus) r.sweeps else 0

@Composable
private fun LuckHeader(bonus: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "같은 주사위로 두 판을 두니까, 두 판 합계 점수가 높은 쪽에 3점(같으면 1점)을 주면 판 운을 뺀 순위가 나와요. " +
                (if (bonus) "판 승점제에서는 1승 1패가 무승부지만 여기서는 합계로 가려요. 2-0 완승 보너스는 실제와 보정 승점에 똑같이 더했어요. " else "") +
                "'운'은 실제 승점에서 운 보정 승점을 뺀 값이에요.",
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
private fun LuckRow(i: Int, r: SeasonRow, realPlace: Int, bonus: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fair = fairPoints(r, bonus)
    val luck = r.points - fair
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
        Text("$fair", Modifier.width(40.dp).padding(end = 6.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
}

/** 일정표: one matchday of a division, played and to come ([bonus]: the season's 2-0 sweeps earn a point). */
@Composable
private fun CalendarCard(lg: LeagueController, division: Int, season: Int, played: Int, bonus: Boolean, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cal = lg.calendar
    // Again as the season moves on (results, games in progress), on the round shown; a new
    // season or division opens on its current round.
    // The round asked for here (null: the one being played), kept for the reloads.
    val wanted = lg.nav.round
    androidx.compose.runtime.LaunchedEffect(division, season, played, wanted) { lg.loadRound(division, wanted) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (cal == null || cal.division != division) {
                Text("일정을 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            } else {
                CalendarBody(lg, cal, bonus, onOpen) { md -> lg.nav.round = md }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CalendarBody(lg: LeagueController, cal: yacht.app.core.CalendarView, bonus: Boolean, onOpen: (String) -> Unit, onRound: (Int?) -> Unit) {
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
            // Won and lost by the season's own rule (판 승점제: a 1-1 is a draw).
            val aWon = g != null && f.result == 1
            val bWon = g != null && f.result == -1
            val swept = bonus && g != null && ((aWon && yacht.engine.league.TieRule.sweptByA(g)) || (bWon && yacht.engine.league.TieRule.sweptByB(g)))
            Text(
                f.aName, Modifier.weight(1f).clickable { onOpen(f.a) }, style = MaterialTheme.typography.bodySmall,
                fontWeight = if (aWon) FontWeight.ExtraBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Column(Modifier.padding(horizontal = 6.dp).clickable { lg.loadPreview(f.a, f.b) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        g != null -> g.joinToString(" ") { "${it.a}:${it.b}" }
                        f.live -> "⚔️ 경기 중"
                        else -> "🔮 프리뷰"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (g != null) FontWeight.Bold else FontWeight.Normal,
                    color = if (g == null) cs.primary else cs.onSurface,
                )
                if (swept) SweepTag("완승")
            }
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
    val picked = lg.nav.chartKeys
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
                            lg.nav.chartKeys = if (on) base - r.key else (base + r.key).takeLast(8)
                        },
                        label = { Text(r.name, fontSize = 11.sp, maxLines = 1) },
                    )
                }
            }
        }
    }
}

/** How often an open 상대 전적 matrix reads the numbers again while the league runs. */
private const val H2H_REFRESH_MS = 10_000L

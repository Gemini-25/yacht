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
import yacht.app.core.Page
import yacht.app.core.RulesPart
import yacht.app.core.SeasonRow
import yacht.app.core.SeasonView
import yacht.app.core.Sheet
import yacht.app.core.TableMode
import yacht.engine.league.Comp
import yacht.engine.league.Family
import yacht.engine.league.Ladder

/**
 * Shows division [d] of [tiers] in the 리그 tab (a national season: [d] of the shown league, as
 * [SeasonView.divisions] counts them): its tier and group, at its current round with the chart's
 * default players. A division [tiers] does not have shows the 1부.
 */
internal fun LeagueNav.showDivision(tiers: yacht.engine.league.Tiers, d: Int) {
    val at = d.takeIf { it in 0 until tiers.size } ?: 0
    tier = if (tiers.size == 0) 0 else tiers.tier(at)
    group = if (tiers.size == 0) 0 else tiers.group(at)
    round = null
    chartKeys = emptyList()
}

/**
 * Shows the season's division [d] (its index among all of [season]'s divisions, as a player's
 * `LadderRow.division`) in the 리그 tab: in a national season its league at its tier.
 */
internal fun LeagueNav.showSeasonDivision(season: SeasonView, d: Int) {
    if (!season.national) return showDivision(season.tiers, d)
    val info = season.leagues.firstOrNull { d in it.first until it.first + it.tiers } ?: return
    selectLeague(info.id, d - info.first)
    chartKeys = emptyList()
}

// ---- ladder ------------------------------------------------------------------------------------

/**
 * 순위 › 레이팅: "선수 레이팅" with its ⓘ ([Sheet.RATING_INFO]), the search field and "필터 ▾"
 * ([Sheet.LADDER_FILTER]), then the players by rating (or the order the filter picks).
 */
@Composable
internal fun LadderList(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit) {
    val nav = lg.nav
    val cs = MaterialTheme.colorScheme
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("선수 레이팅", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { nav.sheet = Sheet.RATING_INFO }) { Text("ⓘ", fontSize = 16.sp) }
                Box(Modifier.weight(1f))
                Text("${"%,d".format(rows.size)}명", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { nav.ladderQuery = it },
                    placeholder = { Text("선수 이름이나 나라 검색", fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { nav.sheet = Sheet.LADDER_FILTER }) { Text("필터 ▾") }
            }
        }
        // What the filter keeps, when it keeps less than everyone.
        val parts = listOfNotNull(
            "★ 응원".takeIf { onlyFavourites },
            origin?.label,
            nation?.let { yacht.engine.league.Nations.of(it)?.let { n -> "${n.flag} ${n.short}" } ?: it },
            family?.label,
            "${sort.label} 순".takeIf { sort != LadderSort.RATING },
        )
        if (parts.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("필터: " + parts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = cs.secondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        nav.ladderFilter = yacht.app.core.LadderFilter()
                        nav.ladderSort = LadderSort.RATING
                    }) { Text("모두 보기", fontSize = 12.sp) }
                }
            }
        }
        if (onlyFavourites && rows.isEmpty()) {
            item { Text("선수를 눌러 ‘☆ 응원하기’를 고르면 여기에 모여요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        items(rows, key = { it.key }) { r -> LadderItem(r, r.key in favs, r.club?.let { v.badges[it] }) { onOpen(r.key) } }
    }
}

/** 순위 › 레이팅's filters: ★ 응원, origin, nation, family and the order (the chips of the 필터 sheet). */
@Composable
internal fun LadderFilterSheet(lg: LeagueController, v: LeagueView) {
    val nav = lg.nav
    val filter = nav.ladderFilter
    val favs = lg.favourites
    SheetHeading("응원·출신")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = filter.followed, onClick = { nav.ladderFilter = filter.copy(followed = !filter.followed) }, label = { Text("★ 응원 ${favs.count { !it.startsWith(NATION_PREFIX) }}", fontSize = 12.sp) })
        for (o in yacht.engine.league.Origin.entries) {
            val n = v.rows.count { it.origin == o }
            if (n > 0) FilterChip(selected = filter.origin == o, onClick = { nav.ladderFilter = filter.copy(origin = if (filter.origin == o) null else o) }, label = { Text("${o.label} $n", fontSize = 12.sp) })
        }
    }
    // Nations of the league: followed nations first, then most players first.
    val nations = remember(v.rows, favs) {
        v.rows.filter { it.nation.isNotEmpty() }.groupingBy { it.nation }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { nationFavourite(it.key) in favs }.thenByDescending { it.value })
    }
    if (nations.size > 1) {
        SheetHeading("국적")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter.nation == null, onClick = { nav.ladderFilter = filter.copy(nation = null) }, label = { Text("🌐 전체 국적", fontSize = 12.sp) })
            for ((code, n) in nations) {
                val nat = yacht.engine.league.Nations.of(code) ?: continue
                FilterChip(
                    selected = filter.nation == code,
                    onClick = { nav.ladderFilter = filter.copy(nation = if (filter.nation == code) null else code) },
                    label = { Text((if (nationFavourite(code) in favs) "★" else "") + "${nat.flag} ${nat.short} $n", fontSize = 12.sp) },
                )
            }
        }
    }
    SheetHeading("계열")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = filter.family == null, onClick = { nav.ladderFilter = filter.copy(family = null) }, label = { Text("전체", fontSize = 12.sp) })
        for (f in Family.entries) {
            FilterChip(selected = filter.family == f, onClick = { nav.ladderFilter = filter.copy(family = if (filter.family == f) null else f) }, label = { Text(f.label, fontSize = 12.sp) })
        }
    }
    SheetHeading("정렬")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in LadderSort.entries) {
            FilterChip(selected = nav.ladderSort == s, onClick = { nav.ladderSort = s }, label = { Text(s.label, fontSize = 12.sp) })
        }
    }
}

@Composable
private fun LadderItem(r: LadderRow, favourite: Boolean, badge: yacht.app.core.ClubBadge?, onClick: () -> Unit) {
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
                    ClubMark(badge, 14.dp)
                    Text(
                        if (r.isMe) "🙋 나 (랭크전)" else r.shown,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (r.playing) Text(" ⚔️", fontSize = 11.sp)
                    // "아시아 연합 2부" stays on one line (the name gives way).
                    r.divisionLabel?.let { Text(" $it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1) }
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

/**
 * The league selector's header (UX 1.7): "{icon} {league} ▾" with its rank, players and tiers; a
 * tap opens the selector ([Sheet.LEAGUES]), "정보 ›" the league's page. Nothing in a legacy
 * season (one league, no choice).
 */
@Composable
internal fun LeagueHeader(lg: LeagueController, season: SeasonView) {
    val info = season.shown?.takeIf { season.national } ?: return
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surface), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable { lg.nav.sheet = Sheet.LEAGUES }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("${info.icon} ${info.label} ▾", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.primary)
            Text(
                listOfNotNull(info.rank?.let { "리그 랭킹 ${it}위" }, "선수 ${"%,d".format(info.members)}명", "${info.tiers}개 부").joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        TextButton(onClick = { lg.nav.open(Page.League(info.id)) }) { Text("정보 ›", fontSize = 12.sp) }
    }
}

/**
 * 순위 › 리그: "리그 순위표", in a national season the shown league's header ([LeagueHeader]),
 * the tier chips with "보기 ▾" ([Sheet.TABLE_MODE]) at the end of the same row, a pyramid's group
 * chips, the division in the view picked, the zone legend with "ⓘ 규칙", and next season's
 * newcomers under the last tier.
 */
@Composable
internal fun SeasonList(lg: LeagueController, season: SeasonView?, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (season == null || season.divisions.isEmpty()) {
        // The rules the first season will open with (national leagues for a new league).
        val rules = lg.config
        val how = if (rules.national) {
            "▶ 리그를 시작하면 선수들이 나라별 국가 리그로 나뉘고 시즌 1이 열려요.\n리그마다 레이팅 순으로 부를 나누고, 같은 부끼리 " +
                (if (rules.doubleRoundRobin) "홈·원정 두 번씩" else "한 번씩") + " 맞붙어 승점을 쌓아요. 시즌이 끝나면 위아래로 승강" +
                (if (rules.promotionPlayoff) "하고 승강 플레이오프를 치러요.\n" else "해요.\n")
        } else {
            "▶ 리그를 시작하면 지금 순위대로 부가 나뉘고 시즌 1이 열려요.\n같은 부끼리 라운드마다 한 번씩 맞붙어 승점을 쌓고, 시즌이 끝나면 위아래로 승강하고 승강 플레이오프를 치러요.\n"
        }
        CenterText(how + matchRuleText(rules.matchFormat, rules.sweepPoints))
        return
    }
    val tiers = season.tiers.takeIf { it.size == season.divisions.size } ?: yacht.engine.league.Tiers.linear(season.divisions.size)
    // A followed player's group first when a tier is picked (in a pyramid of dozens of groups, the one to watch).
    val favs = lg.favourites
    fun followed(range: IntRange) = range.firstOrNull { d -> season.divisions[d].any { it.key in favs } }
    val nav = lg.nav
    // National clubs' badges by id (none in a legacy season).
    val badges = lg.view?.badges.orEmpty()
    val mode = nav.tableMode
    val tier = nav.tier.coerceIn(0, tiers.count - 1)
    val d = tiers.groups(tier).let { g -> (g.first + nav.group).coerceIn(g.first, g.last) }
    val label = tiers.label(d)
    // The loaders take the season's index of the division; a national division is named with its league ("한국 2부").
    val gd = season.firstDivision + d
    val national = season.national
    val info = season.shown
    val title = info?.takeIf { national }?.let { yacht.engine.league.LeagueIds.division(it.id, tiers.tier(d)) } ?: label
    // 최근 폼: read again as results come in (the played count moves on).
    if (mode == TableMode.FORM) androidx.compose.runtime.LaunchedEffect(gd, season.number, season.played) { lg.loadForm(gd) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // A national division plays its own rounds over the season's weeks (a lower tier can play fewer).
                val rounds = season.roundsOf(d)
                val now = if (national) minOf((season.positions.getOrNull(d)?.size ?: 0) + 1, rounds) else minOf(season.currentMatchday + 1, rounds)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("리그 순위표", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "시즌 ${season.number}" + (if (rounds > 0) " · $now/${rounds}라운드" else "") +
                            " · ${season.played}/${season.total}경기",
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { if (season.total == 0) 0f else season.played.toFloat() / season.total },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
                if (national) LeagueHeader(lg, season)
                else if (season.nextNational) {
                    // A small league is one ladder, not a regional pyramid.
                    Text(
                        "리그 · 이번 시즌은 ${if (tiers.linear) "한 리그로" else "지역 피라미드로"} 치러요. 다음 시즌부터 국가 리그로 나뉘어요.",
                        style = MaterialTheme.typography.labelSmall, color = cs.secondary,
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                TextButton(onClick = { nav.sheet = Sheet.TABLE_MODE }) { Text("${mode.label} ▾", fontSize = 12.sp, maxLines = 1) }
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
        when (mode) {
            TableMode.LUCK -> {
                // 판 승점제: the 완승 bonus is in both columns (luck is only who took the level matches).
                val bonus = season.bonus
                item { LuckHeader(bonus) }
                val rows = season.divisions[d].sortedWith(compareByDescending<SeasonRow> { fairPoints(it, bonus) }.thenByDescending { it.diff })
                itemsIndexed(rows, key = { _, r -> "a-${r.key}" }) { i, r -> LuckRow(i, r, season.divisions[d].indexOf(r), bonus) { onOpen(r.key) } }
            }
            TableMode.ROUND -> item { CalendarCard(lg, gd, season.number, season.played, season.bonus, onOpen) }
            TableMode.CHART -> item { RankChartCard(lg, season, d) }
            TableMode.ODDS -> {
                item {
                    Text(
                        "남은 경기를 레이팅으로 2,000번 시뮬레이션한 확률이에요(시즌 10%마다 다시 계산).",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                }
                val rows = season.divisions[d]
                if (rows.none { it.odds != null }) item { Text("확률을 계산하는 중이에요. 리그가 돌아가면 곧 나와요.", style = MaterialTheme.typography.bodySmall) }
                else items(rows, key = { "o-${it.key}" }) { r -> OddsRow(r, tiers.tier(d), tiers.count, label, national) { onOpen(r.key) } }
            }
            TableMode.H2H -> item {
                // The table's order when opened (it reorders with nearly every result); the
                // numbers come again every few seconds in the same order, keeping the cell picked.
                val keys = remember(gd, season.number, season.league) { season.divisions[d].map { it.key } }
                androidx.compose.runtime.LaunchedEffect(keys, lg.running) {
                    lg.loadH2h(title, keys)
                    // Only while the league runs (it stops when the app is hidden).
                    while (lg.running) {
                        kotlinx.coroutines.delay(H2H_REFRESH_MS)
                        lg.loadH2h(title, keys)
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(10.dp)) {
                        val m = lg.h2h
                        if (m == null || m.title != title) Text("상대 전적을 모으는 중…", style = MaterialTheme.typography.bodySmall)
                        else H2hGrid(m)
                    }
                }
            }
            TableMode.FORM -> {
                val form = lg.form?.takeIf { it.division == gd && it.season == season.number }
                if (form == null) item { Text("최근 폼을 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                else itemsIndexed(season.divisions[d], key = { _, r -> "f-${r.key}" }) { i, r -> FormRow(i, r, form.form[r.key].orEmpty()) { onOpen(r.key) } }
            }
            TableMode.TABLE -> {
                item {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        Text("#", Modifier.width(24.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text("팀", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text("경기", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                        Text("승-무-패", Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                        Text("득실", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                        if (season.bonus) Text("완승", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                        Text("승점", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                    }
                }
                itemsIndexed(season.divisions[d], key = { _, r -> "s-${r.key}" }) { i, r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable { onOpen(r.key) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(4.dp).height(30.dp).background(zoneColor(r.zone)))
                        Text("${i + 1}", Modifier.width(24.dp).padding(start = 4.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        ClubMark(r.club?.let { badges[it] }, 14.dp)
                        Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.played}", Modifier.width(30.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                        Text("${r.wins}-${r.draws}-${r.losses}", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                        Text("%+d".format(r.diff), Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                        if (season.bonus) Text("${r.sweeps}", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                        Text("${r.points}", Modifier.width(36.dp).padding(end = 6.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🟢 승격 · 🔴 강등 · 흐린 색은 승강 플레이오프", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { nav.open(Page.Rules(RulesPart.LEAGUE)) }) { Text("ⓘ 규칙", fontSize = 12.sp) }
            }
        }
        if (!tiers.linear) {
            item {
                Text(
                    "🗺 선수가 많아 지역 리그 피라미드로 열려요: " + (0 until tiers.count).joinToString(" · ") { t ->
                        yacht.engine.league.Tiers.tierLabel(t) + if (tiers.groupCount(t) > 1) " ${tiers.groupCount(t)}개 조" else ""
                    } + ". 아래 부의 조마다 위 ${season.swap}명이 올라가고, 위 부에서는 그만큼 내려와요. 조는 시즌마다 지역별로 다시 짜요.",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.secondary,
                )
            }
        }
        if (national && info != null) {
            item {
                val top = tiers.tier(d) == 0
                Text(
                    "🌍 나라마다 자기 리그에서 뛰고, 선수가 적은 나라는 연합 리그에 모여요. 리그마다 따로 승강해요." +
                        (if (top && info.cclSlots > 0) " 1부에는 다음 시즌 대륙 챔피언스 출전권 ${info.cclSlots}장이 걸려 있어요." else "") +
                        if (top && info.clDirect) " 우승하면 챔피언스 리그에 바로 나가요." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.secondary,
                )
            }
        }
        // Newcomers join the lowest tier (of their nation's league): their line goes under it.
        if (season.newcomers.isNotEmpty() && tiers.tier(d) == tiers.count - 1) {
            item {
                val where = info?.takeIf { national }?.let { yacht.engine.league.LeagueIds.division(it.id, tiers.count - 1) } ?: "${tiers.count}부"
                Text(
                    "다음 시즌 $where 합류 대기 ${season.newcomers.size}명: ${season.newcomers.take(10).joinToString(", ")}" +
                        if (season.newcomers.size > 10) " 외" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.secondary,
                )
            }
        }
        item {
            Text(
                "부별 순위는 이번 시즌 리그 성적(승점)이고, 선수 레이팅은 모든 경기로 매긴 실력 점수라 둘이 달라요. 실력이 좋아도 아래 부에서 시작하면 승격으로 올라와야 해요." +
                    if (season.history.isNotEmpty()) " 지난 시즌 우승팀과 수상자는 기록 › 전당에 있어요." else "",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A table row's zone stripe: promotion and relegation, paler for the playoff places. */
@Composable
private fun zoneColor(zone: Int): Color {
    val cs = MaterialTheme.colorScheme
    return when (zone) {
        1 -> gameColors.good
        -1 -> cs.error
        2 -> gameColors.good.copy(alpha = 0.4f)
        -2 -> cs.error.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
}

/** 최근 폼: a player's place, its last results in the division (oldest first) and its points. */
@Composable
private fun FormRow(i: Int, r: SeasonRow, form: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(30.dp).background(zoneColor(r.zone)))
        Text("${i + 1}", Modifier.width(24.dp).padding(start = 4.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (form.isEmpty()) Text("–", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) else FormDots(form)
        Text(
            "${form.count { it == 'W' }}승 ${form.count { it == 'D' }}무 ${form.count { it == 'L' }}패",
            Modifier.width(76.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End,
        )
        Text("${r.points}", Modifier.width(36.dp).padding(end = 6.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
}

/**
 * One AI's chances: the title (or a playoff place) at the top, promotion and relegation below. At
 * the top of a national league ([national]) the third chance is a place on next season's
 * international stage (its 대륙 챔피언스, or the 챔피언스 리그 straight away), not the 챔스 alone.
 */
@Composable
private fun OddsRow(r: SeasonRow, tier: Int, tiers: Int, label: String, national: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val o = r.odds ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surface).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(r.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val cells = buildList {
            if (tier == 0) {
                add("우승" to o.title)
                add("4강 PO" to o.playoff)
                add((if (national) "국제" else "챔스") to o.champions)
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
    // The division's own rounds; ◀ and ▶ go to the matchdays of the rounds before and after.
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onRound(cal.prev) }, enabled = cal.prev != null) { Text("◀") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${cal.round + 1}라운드 / ${cal.rounds}", fontWeight = FontWeight.Bold)
            Text(
                when {
                    cal.round < cal.currentRound -> "끝난 라운드"
                    cal.round == cal.currentRound -> "진행 중인 라운드"
                    else -> "다가오는 라운드"
                },
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onRound(cal.next) }, enabled = cal.next != null) { Text("▶") }
    }
    if (cal.round != cal.currentRound) {
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
            // The score (or the coming match) opens its report: the preview before it is played.
            Column(
                Modifier.padding(horizontal = 6.dp).clickable { matchKey(cal.season, Comp.LEAGUE, f.id)?.let { lg.nav.open(Page.Match(it)) } },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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

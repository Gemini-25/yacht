package yacht.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.IdentityView
import yacht.app.core.Page
import yacht.app.core.NationSort
import yacht.engine.league.Abilities
import yacht.engine.league.Tendencies

/**
 * Nations a search [q] names: the whole name or short name, or their start from two letters on
 * ("브라" finds 브라질; a family name like "이" finds no nation).
 */
fun nationsMatching(q: String): Set<String> {
    if (q.isEmpty()) return emptySet()
    return yacht.engine.league.Nations.ALL.filter { n ->
        q == n.name || q == n.short || (q.length >= 2 && (n.name.startsWith(q) || n.short.startsWith(q)))
    }.map { it.code }.toSet()
}

/** A grade for an ability (1–99): S from 90, A from 80, B from 70, C from 55, D from 40, else E. */
fun abilityGrade(v: Int): String = when {
    v >= 90 -> "S"
    v >= 80 -> "A"
    v >= 70 -> "B"
    v >= 55 -> "C"
    v >= 40 -> "D"
    else -> "E"
}

/**
 * Who a generated or evolved player is: nation, gender, age and stage, nickname, abilities with
 * this season's change, tendencies, style, its nation's school, rival, national team and
 * 이 주의 선수 count. [onOpen] opens a player's page by key, for the lines that name one.
 */
@Composable
fun IdentityPart(id: IdentityView, alive: Boolean, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.labelSmall
    Text(
        listOfNotNull(
            "${id.flag} ${id.nation}",
            id.gender.takeIf { it.isNotEmpty() },
            id.age?.let { if (alive) "${it}세" else "${it}세 은퇴" },
            id.stage,
            id.debutAge?.let { "${it}세 데뷔" },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
    )
    id.nickname?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = cs.tertiary, fontWeight = FontWeight.Bold) }
    id.abilities?.let { AbilityBars(id, it) }
    id.tendencies?.let { TendencyBars(it) }
    StyleCard(id)
    val lines = buildList {
        id.rival?.let { (name, p, g) -> add("⚔ 라이벌: $name · ${g}판 승점 ${"%.0f".format(100 * p / g)}%") }
        if (id.caps > 0) {
            add(
                "🎽 국가대표 ${id.caps}회 선발" + (if (id.capTitles > 0) " · 우승 ${id.capTitles}회" else "") +
                    (id.capBoards?.takeIf { (w, d, l) -> w + d + l > 0 }?.let { (w, d, l) -> " · ${w}승 ${d}무 ${l}패" } ?: ""),
            )
        }
        id.nationRank?.let { add("${id.flag} ${id.nation} 선수 ${id.nationPlayers}명 중 레이팅 ${it}위") }
        if (id.weeks > 0) add("⭐ 이 주의 선수 ${id.weeks}회")
    }
    for (l in lines) Text(l, style = small, color = cs.onSurfaceVariant)
}

@Composable
private fun AbilityBars(id: IdentityView, abl: List<Int>) {
    val cs = MaterialTheme.colorScheme
    var help by remember { mutableStateOf(false) }
    val overall = Abilities.of(abl).overall
    Text(
        "📊 능력치 · 종합 $overall (${abilityGrade(overall)})" + (id.potential?.let { " · 잠재력 $it" } ?: "") + " ⓘ",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable { help = !help }.padding(top = 4.dp),
    )
    id.outlook?.let { Text("🌱 $it", style = MaterialTheme.typography.labelSmall, color = gameColors.good) }
    for (i in abl.indices) {
        AbilityRow(Abilities.LABELS[i], abl[i], id.change.getOrNull(i) ?: 0, id.peak?.getOrNull(i))
        if (help) Text(Abilities.HELP[i], style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontSize = 11.sp)
    }
}

/** An ability bar (1–99) with its best so far marked and this season's change. */
@Composable
private fun AbilityRow(label: String, v: Int, change: Int, peak: Int?) {
    val cs = MaterialTheme.colorScheme
    val bar = gameColors.me
    val mark = cs.secondary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(0.24f))
        Box(Modifier.weight(0.5f).height(10.dp).clip(CircleShape).background(cs.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(v / 99f).height(10.dp).clip(CircleShape).background(bar))
            if (peak != null && peak > v) {
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    val x = size.width * peak / 99f
                    drawLine(mark, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4f)
                }
            }
        }
        Text("$v ${abilityGrade(v)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.14f).padding(start = 6.dp))
        Text(
            when {
                change > 0 -> "▲$change"
                change < 0 -> "▼${-change}"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (change > 0) gameColors.good else cs.error,
            modifier = Modifier.weight(0.12f),
        )
    }
}

/** 성향: two-ended bars from -50 to +50, the dot where the player stands. */
@Composable
fun TendencyBars(t: List<Int>) {
    val cs = MaterialTheme.colorScheme
    val dot = gameColors.me
    val mid = cs.outline
    Text("🧭 성향", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    for ((i, b) in Tendencies.BARS.withIndex()) {
        val v = t.getOrNull(i) ?: continue
        val (name, left, right) = b
        val ends = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.weight(0.2f))
            Text(left, style = ends, color = if (v < -10) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(0.22f).padding(end = 4.dp))
            Box(Modifier.weight(0.34f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.surfaceVariant)) {
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    drawLine(mid, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
                    val x = size.width * (v + 50) / 100f
                    drawCircle(dot, radius = size.height / 2, center = Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2))
                }
            }
            Text(right, style = ends, color = if (v > 10) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.24f).padding(start = 4.dp))
        }
    }
}

/** 스타일: its recipe (and second recipe and overlay) in words, its hidden genes as chips, its school. */
@Composable
private fun StyleCard(id: IdentityView) {
    val cs = MaterialTheme.colorScheme
    if (id.style == null && id.school == null) return
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        id.style?.let { Text("🎨 스타일: $it", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
        for (n in id.styleNotes) Text(n, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        if (id.chips.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in id.chips) {
                    Text(c, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(cs.surface).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        id.school?.let { Text("${id.flag} ${id.nation} 스타일: $it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
    }
}

/** A national team's best finish: 우승, 준우승, 4강, 8강, 본선, or null when it never played. */
fun finishLabel(place: Int): String? = when (place) {
    0 -> "우승"
    1 -> "준우승"
    2 -> "4강"
    3 -> "8강"
    yacht.engine.league.NationBook.NONE -> null
    else -> "본선"
}

/** The key a nation is followed by in the favourites. */
fun nationFavourite(code: String) = NATION_PREFIX + code

const val NATION_PREFIX = "nation:"

/** [Page.Nations] (국가대표 랭킹): every nation by its national team's titles, its players' strength or its numbers. */
@Composable
fun NationRanking(lg: yacht.app.core.LeagueController) {
    val cs = MaterialTheme.colorScheme
    // Read again each season (titles and squads change at a rollover).
    androidx.compose.runtime.LaunchedEffect(lg.view?.season?.number) { lg.loadNations() }
    val sort = lg.nav.nationSort
    val all = lg.nations
    if (all.isEmpty()) {
        CenterText("국가 기록을 불러오는 중…")
        return
    }
    val favs = lg.favourites
    val sorted = when (sort) {
        NationSort.CUPS -> all.sortedWith(
            compareByDescending<yacht.app.core.NationRow> { it.worldTitles }.thenByDescending { it.continentalTitles }
                .thenBy { it.worldBest }.thenBy { it.continentalBest }.thenByDescending { it.won }.thenByDescending { it.strength ?: 0 },
        )
        NationSort.STRENGTH -> all.sortedByDescending { it.strength ?: 0 }
        NationSort.PLAYERS -> all.sortedByDescending { it.players }
    }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in NationSort.entries) {
                    androidx.compose.material3.FilterChip(selected = sort == s, onClick = { lg.nav.nationSort = s }, label = { Text(s.label, fontSize = 12.sp) })
                }
            }
        }
        val mine = sorted.filter { nationFavourite(it.code) in favs }
        if (mine.isNotEmpty()) {
            item { Text("★ 응원 국가", fontWeight = FontWeight.Bold) }
            items(mine.size) { i -> NationItem(sorted.indexOf(mine[i]) + 1, mine[i], true) { lg.nav.open(Page.Nation(mine[i].code)) } }
            item { Text("전체", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
        }
        items(sorted.size) { i -> NationItem(i + 1, sorted[i], nationFavourite(sorted[i].code) in favs) { lg.nav.open(Page.Nation(sorted[i].code)) } }
        item {
            Text(
                "월드컵은 4시즌마다 (시즌 3, 7, 11…), 대륙컵은 그 사이 (시즌 5, 9, 13…) 열려요. 각 나라 레이팅 상위 5명이 대표로 뽑혀요.",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NationItem(rank: Int, n: yacht.app.core.NationRow, favourite: Boolean, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = if (favourite) cs.secondaryContainer else cs.surface),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            Text(n.flag, fontSize = 22.sp, modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text((if (favourite) "★ " else "") + n.name, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        n.confed,
                        "선수 ${n.players}명",
                        n.strength?.let { "전력 $it" },
                        n.best?.let { "에이스 $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val trophies = buildString {
                    if (n.worldTitles > 0) append("🌍×${n.worldTitles} ")
                    if (n.continentalTitles > 0) append("🗺×${n.continentalTitles}")
                }.trim()
                if (trophies.isNotEmpty()) Text(trophies, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                val best = finishLabel(n.worldBest)?.let { "월드컵 $it" } ?: finishLabel(n.continentalBest)?.let { "대륙컵 $it" }
                Text(best ?: if (n.editions == 0) "출전 전" else "본선", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** [Page.Nation]: a nation's school, squad, most capped, cups, records by nation and legends. */
@Composable
fun NationPage(lg: yacht.app.core.LeagueController, code: String, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.runtime.LaunchedEffect(code, lg.view?.season?.number) { lg.loadNation(code) }
    val s = lg.nationSheet?.takeIf { it.row.code == code }
    if (s == null) {
        CenterText("국가 기록을 불러오는 중…")
        return
    }
    val n = s.row
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${n.confed} · 선수 ${n.players}명 (여자 ${s.women}명)" + (n.strength?.let { " · 전력 $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = cs.secondary)
        if (s.motto.isNotEmpty()) Text("🎨 ${s.motto}", style = MaterialTheme.typography.bodySmall)
        Heading("🏆 국가대항전")
        Small(
            if (n.editions == 0) "아직 국가대항전에 나간 적이 없어요."
            else "${n.editions}회 출전 · ${yacht.engine.league.NationBook.record(n.won, n.drawn, n.lost)} · 월드컵 우승 ${n.worldTitles}회 · 대륙컵 우승 ${n.continentalTitles}회",
        )
        for (c in s.cups) Small(c)
        if (s.squad.isNotEmpty()) {
            Heading("🎽 지금의 주축 (레이팅 상위 5명이 대표)")
            for ((i, p) in s.squad.withIndex()) {
                val mark = when {
                    i == 0 -> "ⓒ"
                    i < 5 -> "•"
                    else -> "◦"
                }
                PlayerLine("$mark ${p.name}", p.note) { onOpen(p.key) }
            }
        }
        if (s.capped.isNotEmpty()) {
            Heading("🧢 최다 출전")
            for (p in s.capped) PlayerLine(p.name, p.note) { onOpen(p.key) }
        }
        if (s.honours.isNotEmpty()) {
            Heading("📜 국적별 기록")
            Small(s.honours.joinToString(" · ") { (k, c) -> "${honourLabel(k)} $c" })
        }
        if (s.ages.any { it.second > 0 }) {
            Heading("👥 나이 분포")
            val top = s.ages.maxOf { it.second }.coerceAtLeast(1)
            for ((label, c) in s.ages) BarRow(label, c.toDouble(), top.toDouble(), "$c")
        }
        if (s.legends.isNotEmpty()) {
            Heading("🏛 레전드")
            for (p in s.legends) PlayerLine(p.name, p.note) { onOpen(p.key) }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PlayerLine(name: String, note: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 유망주: players of 22 or younger by potential. */
@Composable
fun Prospects(lg: yacht.app.core.LeagueController, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.runtime.LaunchedEffect(lg.view?.season?.number) { lg.loadProspects() }
    val ps = lg.prospects
    if (ps.isEmpty()) {
        CenterText("22세 이하 선수가 리그에 들어오면 잠재력 순으로 여기에 보여요.")
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                "잠재력은 선수가 자랄 수 있는 한계예요. 22세 이하 선수를 잠재력 순으로 보여 줘요. 보통 28~30세에 전성기를 맞아요.",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        items(ps.size) { i ->
            val p = ps[i]
            androidx.compose.material3.Card(Modifier.fillMaxWidth().clickable { onOpen(p.key) }, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = cs.surface)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.shown, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull("${p.age}세", p.rating?.let { "레이팅 $it" }, p.outlook).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("잠재력 ${p.potential} ${abilityGrade(p.potential)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = gameColors.good)
                        Text("지금 ${p.overall} ${abilityGrade(p.overall)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** [Page.Compare] (선수 비교): without [Page.Compare.b] a search for the second player, then the two side by side. */
@Composable
fun ComparePage(lg: yacht.app.core.LeagueController, page: Page.Compare, rows: List<yacht.app.core.LadderRow>) {
    val cs = MaterialTheme.colorScheme
    val nav = lg.nav
    val b = page.b
    if (b == null) {
        var query by remember { mutableStateOf("") }
        val q = query.trim()
        val nations = nationsMatching(q)
        val picks = rows.filter { it.key != page.a && !it.isMe && (q.isEmpty() || q in it.name || it.nation in nations) }.take(40)
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Text("⚖ ${yacht.engine.explain.Ko.wa(lg.name(page.a))} 비교할 선수", fontWeight = FontWeight.Bold) }
            item {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("선수 이름이나 나라 검색") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            items(picks.size) { i ->
                val r = picks[i]
                Row(Modifier.fillMaxWidth().clickable { nav.replaceTop(Page.Compare(page.a, r.key)) }.padding(vertical = 8.dp)) {
                    Text(r.shown, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((r.rank?.let { "${it}위 · " } ?: "") + (r.rating?.let { "$it" } ?: "–"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        return
    }
    androidx.compose.runtime.LaunchedEffect(page.a, b) { lg.loadCompare(page.a, b) }
    val shown = lg.compare?.takeIf { it.a == page.a && it.b == b }
    if (shown == null) {
        CenterText("두 선수의 기록을 모으는 중…")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CompareBody(shown)
        androidx.compose.material3.TextButton(onClick = { nav.replaceTop(Page.Compare(page.a)) }) { Text("다른 선수와 비교") }
    }
}

@Composable
private fun CompareBody(c: yacht.app.core.CompareView) {
    val cs = MaterialTheme.colorScheme
    val good = gameColors.good
    @Composable
    fun header(name: String, id: IdentityView?, end: Boolean, mod: Modifier) = Column(mod, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text((id?.flag?.let { "$it " } ?: "") + name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(id?.age?.let { "${it}세" }, id?.stage, id?.style).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 2)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        header(c.nameA, c.idA, false, Modifier.weight(1f))
        Text("vs", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
        header(c.nameB, c.idB, true, Modifier.weight(1f))
    }
    c.h2h?.let { (p, g) ->
        Text("맞대결 ${g}판 · 승점 ${"%.0f".format(100 * p)}% : ${"%.0f".format(100 * (1 - p))}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(cs.error.copy(alpha = 0.6f))) {
            Box(Modifier.fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(gameColors.me))
        }
    } ?: Text("아직 맞대결이 없어요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    @Composable
    fun line(label: String, a: String, b: String, better: Int) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(a, style = MaterialTheme.typography.bodySmall, fontWeight = if (better > 0) FontWeight.Bold else FontWeight.Normal, color = if (better > 0) good else cs.onSurface, modifier = Modifier.weight(1f))
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Text(b, style = MaterialTheme.typography.bodySmall, fontWeight = if (better < 0) FontWeight.Bold else FontWeight.Normal, color = if (better < 0) good else cs.onSurface, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    Heading("📈 기록")
    for (l in c.lines) line(l.label, l.a, l.b, l.better)
    val aa = c.idA?.abilities
    val ab = c.idB?.abilities
    if (aa != null && ab != null) {
        Heading("📊 능력치")
        val oa = Abilities.of(aa).overall
        val ob = Abilities.of(ab).overall
        line("종합", "$oa ${abilityGrade(oa)}", "$ob ${abilityGrade(ob)}", oa.compareTo(ob))
        for (i in aa.indices) line(Abilities.LABELS[i], "${aa[i]}", "${ab[i]}", aa[i].compareTo(ab[i]))
        if (c.idA.potential != null && c.idB.potential != null) line("잠재력", "${c.idA.potential}", "${c.idB.potential}", c.idA.potential.compareTo(c.idB.potential))
    }
    val ta = c.idA?.tendencies
    val tb = c.idB?.tendencies
    if (ta != null && tb != null) {
        Heading("🧭 성향")
        for ((i, b) in Tendencies.BARS.withIndex()) {
            fun word(v: Int) = when {
                v <= -15 -> b.second
                v >= 15 -> b.third
                else -> "중간"
            }
            line(b.first, word(ta[i]), word(tb[i]), 0)
        }
    }
}

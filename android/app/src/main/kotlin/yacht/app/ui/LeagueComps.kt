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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.ClockView
import yacht.app.core.CompetitionPage
import yacht.app.core.CompetitionStage
import yacht.app.core.LeagueController
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.Sheet
import yacht.app.core.competitionStages
import yacht.app.core.roundInPlay
import yacht.engine.league.BracketRound
import yacht.engine.league.BracketSide
import yacht.engine.league.Comp
import yacht.engine.league.CompetitionNotes
import yacht.engine.league.CompetitionView
import yacht.engine.league.Confed
import yacht.engine.league.Nations
import yacht.engine.league.Competitions
import yacht.engine.league.GroupTable
import yacht.engine.league.MatchFormat
import yacht.engine.league.TieView
import yacht.engine.league.WeekStage

/** 순위 › 대회: a card per competition of the season (the post-season last); a card opens its page. */
@Composable
fun CompsTab(lg: LeagueController, v: LeagueView) {
    val comps = v.comps
    val clock = v.clock
    if (comps.isEmpty()) {
        CenterText(
            "이번 시즌에는 열린 대회가 없어요.\n리그가 시작되면 요트컵·챔피언스 리그·올스타전이 열리고, 시즌 2부터 슈퍼컵, " +
                "시즌 3부터 홀수 시즌마다 국가대항전(요트 월드컵·대륙컵 번갈아)이 열려요. 리그가 끝나면 우승·승강 플레이오프를 치러요. (규칙에서 끄고 켤 수 있어요)",
        )
        return
    }
    val stages = remember(clock?.weeks) { stagesByComp(clock) }
    val favs = lg.favourites
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(comps, key = { "comp-${it.first.code}" }) { (c, view) ->
            val mine = remember(view, favs) { keysOf(view).any { followed(it, favs) } }
            CompCard(c, view.title, cardStatus(c, stages[c].orEmpty(), clock), mine) { lg.nav.open(Page.Competition(c)) }
        }
        item {
            Text(
                "카드를 누르면 대진표와 조별 순위가 열려요. ★는 응원하는 선수가 나가는 대회예요.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Each competition's stages on the calendar, in order. */
private fun stagesByComp(clock: ClockView?): Map<Comp, List<WeekStage>> =
    clock?.weeks.orEmpty().flatMap { it.stages }.filter { it.comp != Comp.LEAGUE }.groupBy { it.comp }.mapValues { (_, l) -> l.sortedBy { it.stage } }

/** Everyone in a competition: the sides of its ties, its group tables and its squads. */
private fun keysOf(view: CompetitionView): Set<String> {
    val out = HashSet<String>()
    fun bracket(b: CompetitionView.Bracket?) = b?.rounds?.forEach { r -> r.ties.forEach { t -> t.a?.let(out::add); t.b?.let(out::add) } }
    fun groups(g: CompetitionView.Groups) {
        g.groups.forEach { t -> t.rows.forEach { out += it.key } }
        bracket(g.knockout)
        g.squads.values.forEach(out::addAll)
    }
    when (view) {
        is CompetitionView.Bracket -> bracket(view)
        is CompetitionView.Groups -> groups(view)
        is CompetitionView.Sections -> view.parts.forEach(::groups)
        else -> {}
    }
    view.squads.values.forEach(out::addAll)
    return out
}

/** A card's line: the stage in play and its matches, when it opens, or that it is over. */
private fun cardStatus(comp: Comp, stages: List<WeekStage>, c: ClockView?): String {
    if (c == null || stages.isEmpty()) return "일정이 정해지면 열려요"
    val cur = stages.firstOrNull { !it.done } ?: return "모든 경기가 끝났어요"
    val live = c.live.entries.filter { it.key.first == comp }.sumOf { it.value }
    // The super cup and the all-star game are one match: their stage is their name.
    val stage = if (cur.label == comp.label) null else shortStage(cur.label)
    val now = when {
        live > 0 -> "${live}경기 진행 중"
        cur.week > c.week && cur.played == 0 -> "${weekName(cur.week, c.leagueWeeks)}에 열려요"
        !cur.set || cur.total == 0 -> "앞 단계가 끝나면 대진이 정해져요"
        else -> "${cur.total}경기 중 ${cur.played}경기 끝났어요"
    }
    return (if (live > 0 || (cur.week <= c.week && cur.set && cur.total > 0)) "지금 " else "") + listOfNotNull(stage, now).joinToString(" · ")
}

@Composable
private fun CompCard(comp: Comp, title: String, status: String, followed: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(compIcon(comp), fontSize = 22.sp, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (followed) Text("★", color = cs.secondary, modifier = Modifier.padding(horizontal = 6.dp))
            Text("›", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** [Page.Competition]: loads the page of its competition at its stage, then draws it. */
@Composable
internal fun CompetitionScreen(lg: LeagueController, v: LeagueView, page: Page.Competition, onOpen: (String) -> Unit) {
    val view = v.comps.firstOrNull { it.first == page.comp }?.second
    if (view == null) {
        CenterText("이번 시즌에는 열리지 않는 대회예요.")
        return
    }
    // Again whenever the competition moved on (a new view) or another stage was picked.
    LaunchedEffect(view, page.stage) { lg.worldPages.loadCompetition(page.comp, page.stage) }
    val loaded = lg.worldPages.competitions[page.loadKey]
    if (loaded == null) {
        CenterText("대회를 불러오는 중…")
        return
    }
    // The same two functions for every snapshot (they read the newest squads), so rows whose
    // tie did not change skip recomposing.
    val squads by rememberUpdatedState(loaded.view.squads)
    val openAi by rememberUpdatedState(onOpen)
    val label: (String) -> String = remember(lg) { { k -> if (squads.containsKey(k)) Competitions.teamLabel(k) else lg.name(k) } }
    // A team (all-star side, family) has no page of its own: its squad is listed below.
    val open: (String) -> Unit = remember { { k -> if (!squads.containsKey(k)) openAi(k) } }
    CompetitionBody(loaded, label, lg::name, open, v.clock, v.season?.format) { lg.nav.sheet = Sheet.STAGE }
}

/**
 * A competition's page from its loaded [page]: "단계: … ▾" ([onStage], the stage sheet), where it
 * stands on the calendar, then the stage shown (groups or one bracket round), the national cups'
 * notes and the squads. [label] names a side, [name] a player.
 */
@Composable
internal fun CompetitionBody(
    page: CompetitionPage,
    label: (String) -> String,
    name: (String) -> String,
    onOpen: (String) -> Unit,
    clock: ClockView?,
    format: MatchFormat?,
    onStage: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = page.view
    val st = remember(view) { competitionStages(view) }.getOrNull(page.stage)
    val mine = remember(clock?.weeks, page.comp) { stagesByComp(clock)[page.comp].orEmpty() }
    val leagueWeeks = clock?.leagueWeeks ?: Int.MAX_VALUE
    // The promotion playoffs are one tie per boundary: no champion to crown.
    val podium = page.comp != Comp.PROMOTION_PLAYOFF
    val notes = when (view) {
        is CompetitionView.Groups -> view.notes
        is CompetitionView.Sections -> view.notes
        else -> null
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (page.stages.size > 1) item { FilterLine("단계: ${page.stages.getOrElse(page.stage) { "–" }}", onStage) }
        if (clock != null && mine.isNotEmpty()) item { StageStrip(mine, clock) }
        // A national cup's host, absentees and races, whatever stage is shown.
        notes?.let { n -> item { NotesCard(n, label, name, onOpen) } }
        // One part of the competition: its group tables, or one round of its bracket.
        fun LazyListScope.part(g: CompetitionView.Groups) {
            val round = st?.round ?: -1
            val k = g.knockout
            if (round < 0 || k == null) {
                items(g.groups, key = { "g-${it.label}" }) { t -> GroupCard(t, label, onOpen) }
                if (g.groups.isEmpty() && k != null) item { BracketCard(k, label, onOpen, roundWeeks(page.comp, mine, k.rounds, leagueWeeks), podium = podium, format = format) }
            } else {
                item { BracketCard(k, label, onOpen, roundWeeks(page.comp, mine, k.rounds, leagueWeeks), round, podium, format) }
            }
        }
        when (view) {
            is CompetitionView.Bracket -> item { BracketCard(view, label, onOpen, roundWeeks(page.comp, mine, view.rounds, leagueWeeks), st?.round, podium, format) }
            is CompetitionView.Groups -> part(view)
            is CompetitionView.Sections -> view.parts.getOrNull(st?.part ?: 0)?.let { p ->
                item { Text(sectionIcon(p.title) + " " + p.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
                part(p)
            }
            // A kind of view this screen does not draw yet.
            else -> {}
        }
        val squads = when (view) {
            is CompetitionView.Sections -> view.parts.getOrNull(st?.part ?: 0)?.squads ?: view.squads
            else -> view.squads
        }
        val captains = notes?.captains.orEmpty()
        if (squads.isNotEmpty()) {
            item { Text(if (captains.isEmpty()) "명단 (1번 보드가 에이스)" else "대표 명단 (1번 보드가 에이스 · ⓒ 주장)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            items(squads.entries.toList(), key = { "s-${it.key}" }) { (team, members) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(Competitions.teamLabel(team), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            members.mapIndexed { i, k -> "${i + 1}. ${name(k)}" + if (captains[team] == k) " ⓒ" else "" }.joinToString("  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The stage sheet of the competition page on top: every stage in one list, with how far it is and its week. */
@Composable
internal fun StageSheet(lg: LeagueController) {
    val nav = lg.nav
    val page = nav.page as? Page.Competition ?: return
    val loaded = lg.worldPages.competitions[page.loadKey] ?: return
    val clock = lg.view?.clock
    val stages = remember(loaded.view) { competitionStages(loaded.view) }
    val mine = remember(clock?.weeks, page.comp) { stagesByComp(clock)[page.comp].orEmpty() }
    LeagueSheet("단계 고르기", { nav.sheet = Sheet.NONE }) {
        for ((i, s) in stages.withIndex()) {
            SheetChoice(s.label, stageNote(page.comp, loaded.view, s, mine, clock), i == loaded.stage) {
                nav.replaceTop(page.copy(stage = i))
                nav.sheet = Sheet.NONE
            }
        }
    }
}

/** A stage's line in the stage sheet: played, in play or to come, and the week of a bracket round. */
private fun stageNote(comp: Comp, view: CompetitionView, s: CompetitionStage, calendar: List<WeekStage>, clock: ClockView?): String {
    val g = when (view) {
        is CompetitionView.Groups -> view
        is CompetitionView.Sections -> view.parts.getOrNull(s.part)
        else -> null
    }
    val rounds = if (view is CompetitionView.Bracket) view.rounds else g?.knockout?.rounds.orEmpty()
    if (s.round < 0) {
        val drawn = rounds.any { r -> r.ties.any { it.a != null || it.b != null } }
        return if (drawn) "✓ 끝났어요" else "조별 리그 진행 중"
    }
    val r = rounds.getOrNull(s.round) ?: return ""
    val week = roundWeeks(comp, calendar, rounds, clock?.leagueWeeks ?: Int.MAX_VALUE).getOrNull(s.round)
    val state = when {
        r.ties.all { it.a == null && it.b == null } -> "대진 미정"
        r.ties.all { it.winner != null || it.bye } -> "✓ 끝났어요"
        r.ties.any { it.played } -> "● 진행 중"
        else -> "곧 열려요"
    }
    return listOfNotNull(state, week).joinToString(" · ")
}

/**
 * The weeks of a bracket's rounds ("14주", "PO 1주"), in [rounds]' order: the bracket's stages are
 * the competition's last ones, and each round sits on its [BracketRound.stage] (a double
 * elimination plays a winners' and a losers' round in one stage). One stage for the promotion
 * playoffs; rounds without a stage go one per stage.
 */
private fun roundWeeks(comp: Comp, stages: List<WeekStage>, rounds: List<BracketRound>, leagueWeeks: Int): List<String> {
    if (stages.isEmpty() || rounds.isEmpty()) return emptyList()
    fun name(w: Int) = if (w > leagueWeeks) "PO ${w - leagueWeeks}주" else "${w}주"
    if (comp == Comp.PROMOTION_PLAYOFF) return List(rounds.size) { name(stages.first().week) }
    if (rounds.any { it.stage < 0 }) {
        val offset = stages.size - rounds.size
        return if (offset < 0) emptyList() else List(rounds.size) { name(stages[offset + it].week) }
    }
    val offset = stages.size - (rounds.maxOf { it.stage } + 1)
    return if (offset < 0) emptyList() else rounds.map { name(stages[offset + it.stage].week) }
}

/** Where the competition stands, and every stage with its week (✓ played, ● now). */
@Composable
private fun StageStrip(stages: List<WeekStage>, c: ClockView) {
    val cs = MaterialTheme.colorScheme
    val cur = stages.firstOrNull { !it.done }
    val weeks = c.leagueWeeks
    // A later week's stage may start early when nothing else is free.
    val started = cur != null && (cur.played > 0 || (c.live[cur.comp to cur.stage] ?: 0) > 0)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    cur == null -> "모든 경기가 끝났어요"
                    cur.week > c.week && !started -> "${cur.label} · ${weekName(cur.week, weeks)}에 열려요 (${cur.week - c.week}주 뒤)"
                    cur.week > c.week -> "${cur.label} · ${weekName(cur.week, weeks)} 경기를 먼저 치르고 있어요 · ${cur.total}경기 중 ${cur.played}경기 끝났어요"
                    !cur.set || cur.total == 0 -> "${cur.label} · ${weekName(cur.week, weeks)} · 앞 단계가 끝나면 대진이 정해져요"
                    else -> "${cur.label} · ${weekName(cur.week, weeks)} · ${cur.total}경기 중 ${cur.played}경기 끝났어요"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stages.joinToString("  ·  ") { s ->
                    val mark = when {
                        s.done -> " ✓"
                        s.week <= c.week -> " ●"
                        else -> ""
                    }
                    // No-break spaces keep each stage on one line when the list wraps.
                    "${shortStage(s.label)} ${if (s.week > weeks) "PO ${s.week - weeks}주" else "${s.week}주"}$mark".replace(' ', '\u00A0')
                },
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

fun compIcon(c: Comp): String = when (c) {
    Comp.CUP -> "🏆"
    Comp.CHAMPIONS -> "⭐"
    Comp.SUPER -> "🥇"
    Comp.ALL_STAR -> "🌟"
    Comp.FAMILY -> "🧬"
    Comp.WORLD_CUP -> "🌍"
    Comp.CONTINENTAL -> "🗺"
    Comp.CLUB -> "🛡"
    Comp.TITLE_PLAYOFF -> "👑"
    Comp.PROMOTION_PLAYOFF -> "↕"
    Comp.LEGEND -> "🏛"
    Comp.LAB -> "🧪"
    Comp.FRIENDLY -> "🤝"
    Comp.LEAGUE -> "📋"
    // A competition this screen does not know yet.
    else -> "🏅"
}

/** The icon of a continental tournament by its name ("아시아컵" 🌏). */
private fun sectionIcon(title: String): String = Confed.entries.firstOrNull { it.cup == title }?.icon ?: "🗺"

/**
 * A national cup's extras: the host, the nations that sit out, and the MVP (boards won) and
 * scorer (average score) races.
 */
@Composable
private fun NotesCard(n: CompetitionNotes, label: (String) -> String, name: (String) -> String, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            n.host?.let { Text("개최국 ${label(it)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall) }
            Text("나라마다 레이팅 상위 5명이 대표로 나서요. 1번 보드가 에이스예요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            if (n.absent.isNotEmpty()) {
                val shown = n.absent.take(8).joinToString(" · ") { (c, k) -> "${label(c)} (${k}명)" }
                val more = if (n.absent.size > 8) " 외 ${n.absent.size - 8}개국" else ""
                Text("선수가 5명이 안 되거나 대륙 팀이 모자라 불참: $shown$more", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            if (n.mvpRace.isNotEmpty()) {
                Text("MVP 경쟁 (보드 승점)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                for ((i, l) in n.mvpRace.withIndex()) {
                    Text(
                        "${i + 1}. ${name(l.key)} ${flagOf(l.team)} · ${points(l.boardPoints)}점 (${l.matches}경기)",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { onOpen(l.key) },
                    )
                }
            }
            if (n.scorerRace.isNotEmpty()) {
                Text("득점왕 경쟁 (평균 점수)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                for ((i, l) in n.scorerRace.withIndex()) {
                    Text(
                        "${i + 1}. ${name(l.key)} ${flagOf(l.team)} · 평균 ${String.format(java.util.Locale.ROOT, "%.1f", l.average)}점 · ${l.matches}경기",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { onOpen(l.key) },
                    )
                }
            }
        }
    }
}

private fun flagOf(code: String): String = Nations.of(code)?.flag ?: ""

private fun points(x: Double): String = if (x == Math.floor(x)) x.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", x)

/**
 * One round of a bracket ([round], an index of [CompetitionView.Bracket.rounds]; null: the round
 * in play), picked on the competition page's stage sheet (a 256-team cup has 128 ties in its first
 * round). [weeks] go with the rounds; [podium] off for brackets that crown nobody (the promotion
 * playoffs' one tie per boundary).
 */
@Composable
internal fun BracketCard(
    b: CompetitionView.Bracket,
    label: (String) -> String,
    onOpen: (String) -> Unit,
    weeks: List<String> = emptyList(),
    round: Int? = null,
    podium: Boolean = true,
    /** The season's match rule (a leg is one game under [MatchFormat.SINGLE]); null: two. */
    format: MatchFormat? = null,
) {
    val cs = MaterialTheme.colorScheme
    val rounds = b.rounds
    val double = rounds.any { it.side != BracketSide.WINNERS }
    val r = round?.takeIf { it in rounds.indices } ?: roundInPlay(rounds) ?: 0
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(b.title, fontWeight = FontWeight.Bold)
            if (rounds.isEmpty()) {
                Text("대진이 아직 정해지지 않았어요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            } else {
                Text(rounds[r].label + (weeks.getOrNull(r)?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.primary)
                roundCaption(rounds[r], final = r == rounds.lastIndex, podium = podium, format = format)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                if (double) Text("↓ 승자조에서 지면 패자조로 내려가요 · 패자조에서 지면 탈락", style = MaterialTheme.typography.labelSmall, color = cs.tertiary)
                RoundTies(rounds[r], double, label, onOpen)
                if (podium) Podium(rounds, double, label)
            }
        }
    }
}

/**
 * How [round] is played, from its own ties (an old season keeps its rules): a series' length, a
 * tie's matches, and what losing means; null when there is nothing to add (one match).
 */
private fun roundCaption(round: BracketRound, final: Boolean, podium: Boolean, format: MatchFormat?): String? {
    val firstTo = round.ties.maxOfOrNull { it.firstTo } ?: 0
    val legs = round.ties.maxOfOrNull { it.legs } ?: 1
    val grand = round.side == BracketSide.GRAND
    val parts = ArrayList<String>()
    if (firstTo > 0) {
        val then = when {
            !podium -> "먼저 ${firstTo}판을 이기는 쪽이 이겨요"
            grand || final -> "먼저 ${firstTo}판을 이기면 우승해요"
            else -> "먼저 ${firstTo}판을 이기면 올라가요"
        }
        parts += "${firstTo}선승"
        parts += "같은 주사위로 선공을 번갈아 두며 $then (무승부 판은 0.5승)"
    } else if (legs > 1) {
        parts += "${legs}경기" + if (format == MatchFormat.SINGLE) " (경기마다 한 판)" else " (경기마다 두 판)"
    }
    if (round.side == BracketSide.LOSERS) parts += "지면 탈락"
    if (grand) parts += (if (firstTo > 0) "승자조 우승과 패자조 우승이 한 시리즈로 겨뤄요" else "승자조 우승과 패자조 우승이 맞붙어요") + " · 어드밴티지 없음"
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** 우승 and 준우승 from the last round (the grand final with double elimination); 3위 from the losers' final. */
@Composable
private fun Podium(rounds: List<BracketRound>, double: Boolean, label: (String) -> String) {
    val cs = MaterialTheme.colorScheme
    fun other(t: TieView): String? = if (t.winner == t.a) t.b else t.a
    val final = rounds.last().ties.singleOrNull()?.takeIf { it.played && it.winner != null }
    val champion = final?.winner
    val runnerUp = final?.let(::other)
    val third = if (!double) null else rounds.lastOrNull { it.side == BracketSide.LOSERS }?.ties?.singleOrNull()
        ?.takeIf { it.played && it.winner != null }?.let(::other)
    if (champion != null) Text("🏆 우승 ${label(champion)}", fontWeight = FontWeight.ExtraBold, color = cs.secondary)
    val rest = listOfNotNull(runnerUp?.let { "🥈 준우승 ${label(it)}" }, third?.let { "🥉 3위 ${label(it)}" })
    if (rest.isNotEmpty()) Text(rest.joinToString("   "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun RoundTies(round: BracketRound, double: Boolean, label: (String) -> String, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Byes go on without a match; an empty losers' tie (both feeders byes) has no one to show.
    val byes = round.ties.count { it.bye && it.winner != null }
    if (round.ties.all { it.a == null && it.b == null }) {
        Text("앞 단계가 끝나면 대진이 정해져요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (byes > 0) Text("부전승 ${byes}팀", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        for (t in round.ties) {
            if (t.bye) continue
            TieLine(t.a, t.b, t.scoreA, t.scoreB, t.winner, t.played, t.legs, t.firstTo, round.side, double, t.fromWinners, label, onOpen)
        }
    }
}

/**
 * One tie, from plain values: a tie that did not change since the last snapshot skips recomposing.
 * A series ([firstTo] > 0) shows games won "2.5-1"; with double elimination ↓ marks the side
 * that drops from (or came down from) the winners' bracket.
 */
@Composable
private fun TieLine(
    a: String?,
    b: String?,
    scoreA: Double,
    scoreB: Double,
    winner: String?,
    played: Boolean,
    legs: Int,
    firstTo: Int,
    side: BracketSide,
    double: Boolean,
    fromWinners: String?,
    label: (String) -> String,
    onOpen: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val decided = played && winner != null
    // What each side's line says around its name.
    fun mark(key: String?): SideMark {
        if (key == null) return SideMark.NONE
        val lost = decided && key != winner
        return SideMark(
            from = side == BracketSide.LOSERS && key == fromWinners,
            drops = double && side == BracketSide.WINNERS && lost,
            out = side == BracketSide.LOSERS && lost,
            note = if (side != BracketSide.GRAND) null else if (fromWinners == null || key != fromWinners) "패자조 우승" else "승자조 우승",
        )
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Side(a, winner, mark(a), label, onOpen, Modifier.weight(1f), TextAlign.Start)
        Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    played && firstTo > 0 -> "${halfText(scoreA)}-${halfText(scoreB)}"
                    played -> "${halfText(scoreA)} : ${halfText(scoreB)}"
                    a == null || b == null -> "미정"
                    firstTo > 0 -> "${firstTo}선승"
                    legs > 1 -> "${legs}경기"
                    else -> "vs"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (played) FontWeight.Bold else FontWeight.Normal,
                color = if (played) cs.onSurface else cs.onSurfaceVariant,
            )
            if (played && firstTo > 0) Text("${firstTo}선승", fontSize = 9.sp, color = cs.onSurfaceVariant)
        }
        Side(b, winner, mark(b), label, onOpen, Modifier.weight(1f), TextAlign.End)
    }
}

/**
 * Marks of one side of a tie: [from] it came down from the winners' bracket (prefix ↓), [drops]
 * it lost a winners' round and goes down (suffix ↓), [out] it lost a losers' round (greyed),
 * [note] a small line under the name ("승자조 우승").
 */
private data class SideMark(val from: Boolean = false, val drops: Boolean = false, val out: Boolean = false, val note: String? = null) {
    companion object {
        val NONE = SideMark()
    }
}

@Composable
private fun Side(key: String?, winner: String?, mark: SideMark, label: (String) -> String, onOpen: (String) -> Unit, modifier: Modifier, align: TextAlign) {
    val cs = MaterialTheme.colorScheme
    val won = key != null && key == winner
    val start = align == TextAlign.Start
    Column(
        modifier.then(if (key != null) Modifier.clickable { onOpen(key) } else Modifier),
        horizontalAlignment = if (start) Alignment.Start else Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mark.from) Text("↓ ", style = MaterialTheme.typography.bodySmall, color = cs.tertiary, fontWeight = FontWeight.Bold)
            Text(
                key?.let(label) ?: "?",
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (won) FontWeight.ExtraBold else FontWeight.Normal,
                color = if (mark.out) cs.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = align,
            )
            if (mark.drops) Text(" ↓", style = MaterialTheme.typography.bodySmall, color = cs.tertiary, fontWeight = FontWeight.Bold)
        }
        mark.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1) }
    }
}

/** A group table; with [GroupTable.bonus] (판 승점제) its 2-0 sweeps, which earned a point each, get a column. */
@Composable
internal fun GroupCard(g: GroupTable, label: (String) -> String, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bonus = g.bonus
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(g.label, fontWeight = FontWeight.Bold)
            Row {
                Text("팀", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Text("경기", Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("승-무-패", Modifier.width(60.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("득실", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                if (bonus) Text("완승", Modifier.width(30.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                Text("승점", Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
            }
            HorizontalDivider()
            for ((i, row) in g.rows.withIndex()) {
                Row(Modifier.fillMaxWidth().clickable { onOpen(row.key) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(18.dp).background(if (i < 2) gameColors.good else androidx.compose.ui.graphics.Color.Transparent))
                    Text(" ${i + 1} ${label(row.key)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${row.played}", Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                    Text("${row.wins}-${row.draws}-${row.losses}", Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                    Text("%+d".format(row.diff), Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                    if (bonus) Text("${row.sweeps}", Modifier.width(30.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.End)
                    Text("${row.points}", Modifier.width(34.dp), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
                }
            }
            if (bonus) Text("승 3 · 무 1 · 2-0 완승 +1 · 동점이면 득실", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

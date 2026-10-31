package yacht.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import yacht.app.core.LeagueController
import yacht.app.core.RulesPart
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeaguePreset
import yacht.engine.league.MatchFormat

/**
 * [Page.Rules] (규칙 설정): the league's rules and presets, applied from the next season, opened at
 * [part] (the league's or the competitions' section); 저장 keeps the changes, both buttons close
 * the page ([onDone]).
 */
@Composable
fun RulesPage(lg: LeagueController, part: RulesPart, onDone: () -> Unit) {
    var cfg by remember { mutableStateOf(lg.config) }
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    // Where each section starts in the scrolled column, for opening at [part].
    val starts = remember { mutableStateMapOf<RulesPart, Int>() }
    LaunchedEffect(part) {
        // Once the layout has placed the section asked for, the column scrolls to it.
        if (part != RulesPart.TOP) snapshotFlow { starts[part] }.filterNotNull().first().let { scroll.scrollTo(it) }
    }
    fun Modifier.start(p: RulesPart) = onGloballyPositioned { starts[p] = it.positionInParent().y.toInt() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("바꾼 규칙은 다음 시즌부터 적용돼요. 지금 시즌은 그대로 끝까지 가요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                // The season in play keeps the rules it began with: say so when they read otherwise.
                val season = lg.view?.season
                val now = season?.rules
                if (season != null && now != null && rulesSummary(now) != rulesSummary(cfg)) {
                    Text(
                        "지금 시즌 ${season.number}의 규칙은 시작할 때 그대로예요: ${rulesSummary(now)}",
                        style = MaterialTheme.typography.labelSmall, color = cs.secondary,
                    )
                }
                // A season of the old single league still in play: it ends as it began, the next one is national.
                if (season != null && now != null && !now.national && cfg.national) {
                    Text(
                        "지금 시즌은 한 리그로 끝까지 치르고, 다음 시즌부터 나라별 국가 리그로 나뉘어요.",
                        style = MaterialTheme.typography.labelSmall, color = cs.secondary,
                    )
                }
                Text("프리셋", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                for (p in LeaguePreset.entries) {
                    val selected = cfg == p.config
                    Card(
                        Modifier.fillMaxWidth().clickable { cfg = p.config },
                        colors = CardDefaults.cardColors(containerColor = if (selected) cs.primaryContainer else cs.surfaceVariant),
                        border = if (selected) BorderStroke(2.dp, cs.primary) else null,
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(p.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(p.desc, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Text("리그", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.start(RulesPart.LEAGUE))
                Text(structureLine(cfg), style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                if (cfg.national) {
                    Text(
                        "선수 ${cfg.leagueMin}명 이상인 나라는 자기 리그를, 나머지는 대륙별 연합 리그를 열어요. 리그마다 부가 위아래로 이어지고, 부마다 같은 부끼리 리그를 치러요.",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                    Stepper("부 인원", cfg.divisionSize, 16..24, { "${it}명" }, step = 2) { cfg = cfg.copy(divisionSize = it) }
                    Stepper("자기 리그를 여는 나라의 선수 수", cfg.leagueMin, 20..60, { "${it}명" }, step = 4) { cfg = cfg.copy(leagueMin = it) }
                } else {
                    LegacyStructure(cfg) { cfg = it }
                }
                Toggle("홈·원정 두 번씩 (더블 라운드 로빈)", cfg.doubleRoundRobin) { cfg = cfg.copy(doubleRoundRobin = it) }
                Text("경기 방식", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((f, name) in FORMAT_CHIPS) {
                        FilterChip(selected = cfg.matchFormat == f, onClick = { cfg = cfg.copy(matchFormat = f) }, label = { Text(name, fontSize = 12.sp) })
                    }
                }
                Text(matchRuleText(cfg.matchFormat, cfg.sweepPoints), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                if (cfg.matchFormat.sweepBonus) Toggle("2:0 완승 보너스 (+1점)", cfg.sweepBonus) { cfg = cfg.copy(sweepBonus = it) }
                Stepper("자동 승강", cfg.autoSwap, 0..5, { "${it}명" }) { cfg = cfg.copy(autoSwap = it) }
                Toggle("승강 플레이오프", cfg.promotionPlayoff) { cfg = cfg.copy(promotionPlayoff = it) }
                Toggle("우승 플레이오프 (1부 상위 4명)", cfg.titlePlayoff) { cfg = cfg.copy(titlePlayoff = it) }
                LeaguePlayers(cfg) { cfg = it }
                CupsPart(lg, cfg, Modifier.start(RulesPart.CUPS), Modifier.start(RulesPart.CLUBS), Modifier.start(RulesPart.FEDERATION)) { cfg = it }
                OutlinedButton(onClick = { cfg = LeagueConfig.DEFAULT }, modifier = Modifier.fillMaxWidth()) { Text("기본값으로") }
            }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("취소") }
            TextButton(onClick = { lg.changeConfig(cfg); onDone() }, enabled = cfg != lg.config) { Text("저장") }
        }
    }
}

/** The single league's shape (a legacy config): the regional pyramid, its groups, the divisions. */
@Composable
private fun LegacyStructure(cfg: LeagueConfig, onChange: (LeagueConfig) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Toggle("지역 리그 피라미드 (선수가 많을 때)", cfg.pyramid) { onChange(cfg.copy(pyramid = it)) }
    Text(
        "조가 ${yacht.engine.league.Pyramid.MAX_LINEAR}개를 넘게 되면 1부 1개 조, 2부 2개 조, 3부 4개 조…로 나눠요. 몇 명이든 한 시즌이 조 인원과 비슷한 주 수라 금방 끝나요. 조는 시즌마다 지역별로 다시 짜요.",
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
    )
    if (cfg.pyramid) {
        Stepper("조 인원", cfg.groupSize, LeagueConfig.MIN_GROUP..LeagueConfig.MAX_GROUP, { "${it}명" }, step = 5) { onChange(cfg.copy(groupSize = it)) }
        Stepper("조마다 자동 승격", cfg.groupSwap, 1..8, { "${it}명" }) { onChange(cfg.copy(groupSwap = it)) }
    }
    Stepper("부 개수 (피라미드가 아닐 때)", cfg.divisions, 1..6, { "${it}부" }) { onChange(cfg.copy(divisions = it)) }
}

/** The players: how many, aging and pupils, the guests and the hand-made AIs, the filters. */
@Composable
private fun LeaguePlayers(cfg: LeagueConfig, onChange: (LeagueConfig) -> Unit) {
    val cs = MaterialTheme.colorScheme
    HorizontalDivider()
    Text("선수", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    Stepper("선수 수", cfg.generated, 0..LeagueConfig.MAX_PLAYERS, { "${it}명" }, step = 50) { onChange(cfg.copy(generated = it)) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in PLAYER_PRESETS) FilterChip(selected = cfg.generated == n, onClick = { onChange(cfg.copy(generated = n)) }, label = { Text("${n}명", fontSize = 12.sp) })
    }
    if (cfg.generated >= 2000) {
        Text(
            "선수가 많으면 휴대폰이 바빠져요. 한 시즌에 경기가 많아 시간이 오래 걸리고 저장 파일도 커져요.",
            style = MaterialTheme.typography.labelSmall, color = cs.error,
        )
    }
    Toggle("나이와 은퇴", cfg.aging) { onChange(cfg.copy(aging = it)) }
    Text(
        "선수들이 시즌마다 나이를 한 살씩 먹어요. 어린 선수는 성장하고 노장은 기량이 떨어지다 은퇴해요. 빈자리는 신인과 상위 선수의 제자가 채워요.",
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
    )
    if (cfg.aging) Stepper("빈자리 채우기: 제자 비율", cfg.childShare, 0..100, { "제자 ${it}% · 신인 ${100 - it}%" }, step = 10) { onChange(cfg.copy(childShare = it)) }
    Toggle("초청 AI 출전 (상대 맞춤 승률 AI)", cfg.guests) { onChange(cfg.copy(guests = it)) }
    Text(
        "상대 맞춤 승률 AI는 앱의 승률 AI와 같은 계산에, 상대 기록이 ${yacht.engine.OppModel.MIN_GAMES}판 이상 쌓이면 그 상대의 실제 점수 분포를 더해 둬요. 기본 AI와 달리 늘 리그에 나와요.",
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
    )
    Toggle("기본 AI 103명도 리그에 출전", cfg.baseAis) { onChange(cfg.copy(baseAis = it)) }
    Text(
        "끄면 기본 AI 103명은 리그에서 빠지고 랭크 게임과 AI 연구소 상대로만 남아요. 다음 시즌부터 적용돼요.",
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
    )
    Toggle("실수하는 선수만", cfg.onlyFlawed) { onChange(cfg.copy(onlyFlawed = it)) }
    Stepper("레이팅 상위만", cfg.onlyTop, 0..200, { if (it == 0) "모두" else "${it}명" }, step = 8) { onChange(cfg.copy(onlyTop = it)) }
}

/**
 * The competitions ([cups] marks where they start), the clubs ([clubs]) and the 요트협회
 * ([federation]): the international stage and the club cup of national leagues, a legacy
 * config's club league.
 */
@Composable
private fun CupsPart(lg: LeagueController, cfg: LeagueConfig, cups: Modifier, clubs: Modifier, federation: Modifier, onChange: (LeagueConfig) -> Unit) {
    val cs = MaterialTheme.colorScheme
    HorizontalDivider()
    Text("대회", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = cups)
    Text(
        if (cfg.national) {
            "시즌은 주 단위로 흘러가요. 리그 한 라운드가 한 주예요. 요트컵, 대륙 챔피언스, 챔피언스 리그는 시즌이 시작될 때 정해진 주에 열리고, " +
                "리그가 끝나면 포스트시즌에 플레이오프를 치르고 시즌을 마감해요. 일정은 경기 › 이번 주에서 볼 수 있어요."
        } else {
            "시즌은 주 단위로 흘러가요. 리그 한 라운드가 한 주예요. 요트컵과 챔피언스 리그는 시즌이 시작될 때 정해진 주에 하나씩 열리고, " +
                "클럽 리그는 몇 주마다 한 라운드씩 치러요. 리그가 끝나면 포스트시즌에 플레이오프를 치르고 시즌을 마감해요. 일정은 경기 › 이번 주에서 볼 수 있어요."
        },
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
    )
    Toggle("요트컵", cfg.cup) { onChange(cfg.copy(cup = it)) }
    if (cfg.national) {
        Toggle("대륙 챔피언스 (대륙마다 리그 상위 선수)", cfg.continentalCl) { onChange(cfg.copy(continentalCl = it)) }
    }
    Toggle("챔피언스 리그", cfg.champions) { onChange(cfg.copy(champions = it)) }
    if (cfg.national && cfg.champions) {
        Stepper("챔피언스 리그 직행 리그 수", cfg.clDirect, 0..12, { "${it}개" }) { onChange(cfg.copy(clDirect = it)) }
        Text(
            "리그 랭킹 상위 리그의 우승자는 챔피언스 리그에 바로 나가요. 나머지 자리는 대륙 챔피언스 성적으로 채워요.",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
    }
    Toggle("슈퍼컵", cfg.superCup) { onChange(cfg.copy(superCup = it)) }
    Toggle("긴 토너먼트 (라운드가 갈수록 길게)", cfg.longKnockouts) { onChange(cfg.copy(longKnockouts = it)) }
    Toggle("선승제 시리즈 (먼저 N판 이기면 진출)", cfg.seriesKnockouts) { onChange(cfg.copy(seriesKnockouts = it)) }
    Toggle("패자부활 (4명·4팀 이상 토너먼트)", cfg.doubleElimination) { onChange(cfg.copy(doubleElimination = it)) }
    Text(knockoutRuleText(cfg), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    Toggle("올스타전", cfg.allStar) { onChange(cfg.copy(allStar = it)) }
    Toggle("국가대항전 (요트 월드컵·대륙컵)", cfg.nationCup) { onChange(cfg.copy(nationCup = it)) }
    Text(
        "시즌 3부터 2시즌마다 나라별 레이팅 상위 5명이 대표팀을 꾸려 겨뤄요. 요트 월드컵과 대륙컵이 번갈아 열려요.",
        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
    )
    Toggle("계열 대항전 (3시즌마다 · 기본 AI가 출전할 때만)", cfg.familyCup) { onChange(cfg.copy(familyCup = it)) }
    if (cfg.national) {
        HorizontalDivider()
        Text("클럽", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = clubs)
        Text(
            "모든 선수는 자기가 뛰는 나라의 클럽에 속해요. 클럽 순위는 클럽마다 성적이 좋은 선수 6명의 리그 승점과 수상 점수로 매기고, 시즌이 끝나면 이적 시장이 열려요.",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
        Stepper("클럽 인원 (나라마다 이 인원쯤으로 클럽을 나눠요)", cfg.clubSize, 5..12, { "${it}명" }) { onChange(cfg.copy(clubSize = it)) }
        Stepper("해외 이적 (선수 1000명당 한 시즌)", cfg.transfers, 0..10, { "${it}명" }) { onChange(cfg.copy(transfers = it)) }
        Toggle("클럽 대항전 (2시즌마다)", cfg.clubCup) { onChange(cfg.copy(clubCup = it)) }
        HorizontalDivider()
        Text("요트협회", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = federation)
        Toggle("요트협회 총회 (시즌마다 규칙 안건)", cfg.federation) { onChange(cfg.copy(federation = it)) }
        Text(
            "총회가 지난 시즌 통계를 보고 대회 규칙(자동 승강, 우승 플레이오프, 완승 보너스, 부 인원, 긴 토너먼트, 챔스 직행)을 바꾸자는 안건을 올려요. " +
                "직접 찬성·반대할 수 있고, 고르지 않은 안건은 선수 위원회가 정해요. 주사위 점수 규칙은 바꾸지 않아요.",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
        // Only with nothing unsaved: the page would close over the edits.
        if (cfg == lg.config) LinkLine("🏛 요트협회", "총회 안건과 결정, 시상") { lg.nav.open(yacht.app.core.Page.Federation) }
    } else {
        Box(clubs) { Toggle("클럽 리그", cfg.clubs > 0) { onChange(cfg.copy(clubs = if (it) 16 else 0)) } }
        if (cfg.clubs > 0) {
            Stepper("클럽 수", cfg.clubs, 4..24, { "${it}개" }, step = 2) { onChange(cfg.copy(clubs = it)) }
            Stepper("클럽당 선수", cfg.squad, 5..8, { "${it}명" }) { onChange(cfg.copy(squad = it)) }
        }
    }
}

/** The match rule chips, the default first. */
private val FORMAT_CHIPS = listOf(
    MatchFormat.GAMES to "판 승점 (기본)",
    MatchFormat.MIRRORED to "두 판 합계",
    MatchFormat.BEST_OF_3 to "3판 2선승",
    MatchFormat.SINGLE to "단판",
)

/** Quick choices for the number of players. */
private val PLAYER_PRESETS = listOf(200, 500, 1000, 2000, 3000)

/**
 * What the rules make of the league: national leagues "국가 리그 · 부당 약 20명 · 홈·원정 두 번씩 ·
 * 리그 약 42주"; a single league "1부 1 · 2부 2개 조 · …, 조당 약 50명, 시즌 49주, 약 26,000경기".
 */
private fun structureLine(cfg: LeagueConfig): String {
    val n = (cfg.generated + if (cfg.baseAis) BASE_AIS else 0).let { if (cfg.onlyTop > 0) minOf(it, cfg.onlyTop) else it }
    if (n < 2) return "선수가 없어요."
    if (cfg.national) {
        // A division holds up to the size and its overflow: as many rounds, once or twice.
        val most = cfg.divisionSize + yacht.engine.league.NationalLeagues.OVER
        val weeks = (most - 1 + most % 2) * if (cfg.doubleRoundRobin) 2 else 1
        return "국가 리그 · 부당 약 ${cfg.divisionSize}명 · ${if (cfg.doubleRoundRobin) "홈·원정 두 번씩" else "한 번씩"} · 리그 약 ${weeks}주"
    }
    val shape = yacht.engine.league.Pyramid.shape(n, cfg)
    val sizes = if (shape != null) yacht.engine.league.Pyramid.sizes(n, shape) else {
        val d = cfg.divisions.coerceIn(1, maxOf(1, n / 2))
        List(d) { n / d + if (it < n % d) 1 else 0 }
    }
    val rounds = if (cfg.doubleRoundRobin) 2 else 1
    val matches = sizes.sumOf { it * (it - 1) / 2 } * rounds
    val weeks = (sizes.max() - 1 + sizes.max() % 2) * rounds
    val shapeText = shape?.withIndex()?.joinToString(" · ") { (t, g) -> "${t + 1}부" + if (g > 1) " ${g}개 조" else "" } ?: "${sizes.size}개 부"
    return "$shapeText · ${if (shape != null) "조" else "부"}당 약 ${sizes.max()}명 · 시즌 ${weeks}주 · 리그 약 ${"%,d".format(matches)}경기"
}

/** The hand-made AIs that can join the league. */
private const val BASE_AIS = 103

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, show: (Int) -> String, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
        Text(show(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
        TextButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
    }
}

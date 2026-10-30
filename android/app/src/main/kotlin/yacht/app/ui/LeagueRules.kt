package yacht.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.LeagueController
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeaguePreset
import yacht.engine.league.MatchFormat

/** 규칙·프리셋: the league's rules, applied from the next season. */
@Composable
fun LeagueRulesDialog(lg: LeagueController, onDismiss: () -> Unit) {
    var cfg by remember { mutableStateOf(lg.config) }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { lg.changeConfig(cfg); onDismiss() }, enabled = cfg != lg.config) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        title = { Text("리그 규칙", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("바꾼 규칙은 다음 시즌부터 적용돼요. 지금 시즌은 그대로 끝까지 가요.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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
                Text("리그", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(structureLine(cfg), style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                Toggle("지역 리그 피라미드 (선수가 많을 때)", cfg.pyramid) { cfg = cfg.copy(pyramid = it) }
                Text(
                    "조가 ${yacht.engine.league.Pyramid.MAX_LINEAR}개를 넘게 되면 1부 1개 조, 2부 2개 조, 3부 4개 조…로 나눠요. 몇 명이든 한 시즌이 조 인원과 비슷한 주 수라 금방 끝나요. 조는 시즌마다 지역별로 다시 짜요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                if (cfg.pyramid) {
                    Stepper("조 인원", cfg.groupSize, LeagueConfig.MIN_GROUP..LeagueConfig.MAX_GROUP, { "${it}명" }, step = 5) { cfg = cfg.copy(groupSize = it) }
                    Stepper("조마다 자동 승격", cfg.groupSwap, 1..8, { "${it}명" }) { cfg = cfg.copy(groupSwap = it) }
                }
                Stepper("부 개수 (피라미드가 아닐 때)", cfg.divisions, 1..6, { "${it}부" }) { cfg = cfg.copy(divisions = it) }
                Toggle("홈·원정 두 번씩 (더블 라운드 로빈)", cfg.doubleRoundRobin) { cfg = cfg.copy(doubleRoundRobin = it) }
                Text("경기 방식", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in MatchFormat.entries) {
                        FilterChip(selected = cfg.matchFormat == f, onClick = { cfg = cfg.copy(matchFormat = f) }, label = { Text(f.label, fontSize = 12.sp) })
                    }
                }
                Stepper("자동 승강", cfg.autoSwap, 0..5, { "${it}팀" }) { cfg = cfg.copy(autoSwap = it) }
                Toggle("승강 플레이오프", cfg.promotionPlayoff) { cfg = cfg.copy(promotionPlayoff = it) }
                Toggle("우승 플레이오프 (1부 상위 4팀)", cfg.titlePlayoff) { cfg = cfg.copy(titlePlayoff = it) }
                HorizontalDivider()
                Text("선수", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Stepper("선수 수", cfg.generated, 0..LeagueConfig.MAX_PLAYERS, { "${it}명" }, step = 50) { cfg = cfg.copy(generated = it) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in PLAYER_PRESETS) FilterChip(selected = cfg.generated == n, onClick = { cfg = cfg.copy(generated = n) }, label = { Text("${n}명", fontSize = 12.sp) })
                }
                if (cfg.generated >= 2000) {
                    Text(
                        "선수가 많으면 휴대폰이 바빠져요. 한 시즌에 경기가 많아 시간이 오래 걸리고 저장 파일도 커져요.",
                        style = MaterialTheme.typography.labelSmall, color = cs.error,
                    )
                }
                Toggle("나이와 은퇴", cfg.aging) { cfg = cfg.copy(aging = it) }
                Text(
                    "선수들이 시즌마다 나이를 한 살씩 먹어요. 어린 선수는 성장하고 노장은 기량이 떨어지다 은퇴해요. 빈자리는 신인과 상위 선수의 제자가 채워요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                if (cfg.aging) Stepper("빈자리 채우기: 제자 비율", cfg.childShare, 0..100, { "제자 ${it}% · 신인 ${100 - it}%" }, step = 10) { cfg = cfg.copy(childShare = it) }
                Toggle("초청 AI 출전 (상대 맞춤 승률 AI)", cfg.guests) { cfg = cfg.copy(guests = it) }
                Text(
                    "상대 맞춤 승률 AI는 앱의 승률 AI와 같은 계산에, 상대 기록이 ${yacht.engine.OppModel.MIN_GAMES}판 이상 쌓이면 그 상대의 실제 점수 분포를 더해 둬요. 기본 AI와 달리 늘 리그에 나와요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                Toggle("기본 AI 103명도 리그에 출전", cfg.baseAis) { cfg = cfg.copy(baseAis = it) }
                Text(
                    "끄면 기본 AI 103명은 리그에서 빠지고 랭크 게임과 AI 연구소 상대로만 남아요. 다음 시즌부터 적용돼요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                Toggle("실수하는 선수만", cfg.onlyFlawed) { cfg = cfg.copy(onlyFlawed = it) }
                Stepper("레이팅 상위만", cfg.onlyTop, 0..200, { if (it == 0) "모두" else "${it}명" }, step = 8) { cfg = cfg.copy(onlyTop = it) }
                HorizontalDivider()
                Text("대회", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    "시즌은 주 단위로 흘러가요. 리그 한 라운드가 한 주예요. 요트컵과 챔피언스 리그는 시즌이 시작될 때 정해진 주에 하나씩 열리고, " +
                        "클럽 리그는 몇 주마다 한 라운드씩 치러요. 리그가 끝나면 포스트시즌에 플레이오프를 치르고 시즌을 마감해요. 일정은 ‘일정’ 탭에서 볼 수 있어요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                Toggle("요트컵", cfg.cup) { cfg = cfg.copy(cup = it) }
                Toggle("챔피언스 리그", cfg.champions) { cfg = cfg.copy(champions = it) }
                Toggle("슈퍼컵", cfg.superCup) { cfg = cfg.copy(superCup = it) }
                Toggle("긴 토너먼트 (16강부터 경기 수 늘림)", cfg.longKnockouts) { cfg = cfg.copy(longKnockouts = it) }
                Text(
                    if (cfg.longKnockouts) "요트컵·챔스·우승 플레이오프의 16강·8강은 2경기(4판), 4강은 3경기(6판), 결승은 4경기(8판)로 겨뤄요. 한 경기는 같은 주사위로 선공·후공 두 판이에요. 슈퍼컵은 2경기예요."
                    else "토너먼트는 한 경기(두 판)로 끝나요. 챔스 8강·4강과 우승 플레이오프 4강만 2경기예요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                Toggle("올스타전", cfg.allStar) { cfg = cfg.copy(allStar = it) }
                Toggle("국가대항전 (요트 월드컵·대륙컵)", cfg.nationCup) { cfg = cfg.copy(nationCup = it) }
                Text(
                    "시즌 3부터 2시즌마다 나라별 레이팅 상위 5명이 대표팀을 꾸려 겨뤄요. 요트 월드컵과 대륙컵이 번갈아 열려요.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                Toggle("계열 대항전 (3시즌마다 · 기본 AI가 출전할 때만)", cfg.familyCup) { cfg = cfg.copy(familyCup = it) }
                Toggle("클럽 리그", cfg.clubs > 0) { cfg = cfg.copy(clubs = if (it) 16 else 0) }
                if (cfg.clubs > 0) {
                    Stepper("클럽 수", cfg.clubs, 4..24, { "${it}개" }, step = 2) { cfg = cfg.copy(clubs = it) }
                    Stepper("클럽당 선수", cfg.squad, 5..8, { "${it}명" }) { cfg = cfg.copy(squad = it) }
                }
                OutlinedButton(onClick = { cfg = LeagueConfig() }, modifier = Modifier.fillMaxWidth()) { Text("기본값으로") }
            }
        },
    )
}

/** Quick choices for the number of players. */
private val PLAYER_PRESETS = listOf(200, 500, 1000, 2000, 3000)

/** What the rules make of the league: "1부 1 · 2부 2개 조 · …, 조당 약 50명, 시즌 49주, 약 26,000경기". */
private fun structureLine(cfg: LeagueConfig): String {
    val n = (cfg.generated + if (cfg.baseAis) BASE_AIS else 0).let { if (cfg.onlyTop > 0) minOf(it, cfg.onlyTop) else it }
    if (n < 2) return "선수가 없어요."
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

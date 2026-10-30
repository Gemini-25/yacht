@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.LeagueBackground
import yacht.app.core.LeagueRunMode
import yacht.app.core.LeagueSpeed
import yacht.app.core.LeagueView
import yacht.app.core.Page
import yacht.app.core.ResultsFilter
import yacht.app.core.Screen
import yacht.app.core.Sheet
import yacht.app.core.TableMode
import yacht.engine.league.CloneProfile
import yacht.engine.league.Ladder

// The bottom sheets over the league screen, one per `Sheet` (`nav.sheet`): settings, the season's
// weeks, the table's views, the filters, the competition stage and the rating's explanation.

/** The sheet `nav.sheet` names, over the whole league screen ([onReset]: the reset confirmation). */
@Composable
internal fun LeagueSheets(c: AppController, v: LeagueView?, onReset: () -> Unit) {
    val lg = c.league
    val nav = lg.nav
    val close = { nav.sheet = Sheet.NONE }
    when (nav.sheet) {
        Sheet.NONE -> {}
        Sheet.SETTINGS -> LeagueSheet("리그 설정", close) { SettingsSheet(c, v, onReset) }
        Sheet.WEEKS -> v?.clock?.let { WeeksSheet(lg, it) }
        Sheet.TABLE_MODE -> LeagueSheet("보기", close) {
            for (m in TableMode.entries) {
                SheetChoice(m.label, tableModeText(m), nav.tableMode == m) {
                    nav.tableMode = m
                    close()
                }
            }
        }
        Sheet.RESULTS_FILTER -> LeagueSheet("결과 필터", close) {
            for (f in ResultsFilter.entries) {
                SheetChoice(f.label, resultsFilterText(f), nav.results == f) {
                    nav.results = f
                    close()
                }
            }
        }
        Sheet.HIGHLIGHT_FILTER -> LeagueSheet("하이라이트 필터", close) {
            val h = nav.highlights
            SheetChoice("이번 시즌", "이번 시즌의 가장 극적인 경기", !h.allTime) { nav.highlights = h.copy(allTime = false) }
            SheetChoice("역대 최고", "리그가 시작된 뒤 가장 극적이었던 경기", h.allTime) { nav.highlights = h.copy(allTime = true) }
            HorizontalDivider()
            SheetToggle("★ 응원 선수 경기만", h.followed) { nav.highlights = h.copy(followed = it) }
        }
        Sheet.LADDER_FILTER -> v?.let { LeagueSheet("선수 필터", close) { LadderFilterSheet(lg, it) } }
        Sheet.RATING_INFO -> LeagueSheet("레이팅과 티어", close) { RatingInfo() }
        Sheet.STAGE -> StageSheet(lg)
        // The league selector (S10), the leaderboards' and the ranking's filters, a player's actions: later.
        else -> {}
    }
}

/**
 * A bottom sheet drawn inside the league screen (so a screenshot renders it and back closes it
 * through `nav.back()`): a 32% black scrim that closes it when tapped, then a surface with 16 dp
 * top corners, at most 85% of the screen high: the title and 닫기 on top, the content below
 * scrolled with [scroll].
 */
@Composable
internal fun LeagueSheet(title: String, onDismiss: () -> Unit, scroll: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val most = maxHeight * 0.85f
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        // A Surface takes the touches on it, so a tap inside does not reach the scrim.
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = most),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = cs.surface,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("닫기") }
                }
                Column(Modifier.verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
            }
        }
    }
}

/** One choice of a sheet's list: its name, a line on it, and a check when it is the one chosen. */
@Composable
internal fun SheetChoice(label: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
        }
        if (selected) Text("✓", color = cs.primary, fontWeight = FontWeight.Bold)
    }
}

/** A line that opens something else (a page, a screen), in a sheet or a list. */
@Composable
internal fun LinkLine(label: String, sub: String? = null, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.SemiBold)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun SheetToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** A section heading of a sheet. */
@Composable
internal fun SheetHeading(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))

/** A sheet's small explanation. */
@Composable
internal fun SheetNote(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The players and matches of the league, as the settings sheet and the home's footer say them. */
internal fun countsLine(c: AppController, v: LeagueView?): String {
    val lg = c.league
    // A league of generated players: its players (the hand-made AIs left it).
    val who = if (lg.config.baseAis) "${lg.size}개 AI" else "선수 ${"%,d".format(v?.rows?.count { !it.isMe && it.origin != yacht.engine.league.Origin.BASE } ?: 0)}명"
    return who + (v?.let { " · 총 ${"%,d".format(it.matches)}경기" } ?: "")
}

/** 리그 설정: how ▶ goes, the speed, the app hidden, the rules, the makers and the lab, the seed and the reset. */
@Composable
private fun ColumnScope.SettingsSheet(c: AppController, v: LeagueView?, onReset: () -> Unit) {
    val lg = c.league
    val nav = lg.nav
    fun page(p: Page) {
        nav.sheet = Sheet.NONE
        nav.open(p)
    }
    fun screen(s: Screen) {
        nav.sheet = Sheet.NONE
        c.screen = s
    }
    Text(countsLine(c, v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SheetHeading("진행 방식")
    Segments(LeagueRunMode.entries.map { it.label }, lg.runMode.ordinal) { lg.changeRunMode(LeagueRunMode.entries[it]) }
    SheetNote(if (lg.runMode == LeagueRunMode.WEEK) "1주씩: ▶를 누를 때마다 한 주를 치르고 멈춰요." else "계속: ■를 누를 때까지 시즌을 이어가요.")
    SheetHeading("속도")
    Segments(LeagueSpeed.entries.map { it.label }, lg.speed.ordinal) { lg.changeSpeed(LeagueSpeed.entries[it]) }
    if (lg.running) SheetNote("지금 분당 ${"%,.0f".format(lg.pace)}경기")
    SheetHeading("앱을 나가면")
    Segments(LeagueBackground.entries.map { it.label }, lg.background.ordinal) { c.chooseLeagueBackground(LeagueBackground.entries[it]) }
    SheetNote(
        if (lg.background == LeagueBackground.KEEP) "나가도 계속하면 알림이 뜨고, 화면이 꺼져도 경기가 돌아가요. 배터리를 더 써요. 알림의 ‘정지’로 멈출 수 있어요."
        else "나가면 멈추고, 돌아오면 이어서 해요.",
    )
    SheetHeading("규칙")
    LinkLine("규칙 설정", "바꾼 규칙은 다음 시즌부터 적용돼요.") { page(Page.Rules()) }
    SheetHeading("선수와 AI")
    LinkLine("🧑 선수 만들기") {
        c.editingPlayer = null
        screen(Screen.MAKER)
    }
    LinkLine("🛠 AI 만들기") {
        c.editingAi = null
        screen(Screen.BUILDER)
    }
    LinkLine("⚗ AI 연구소", "두 AI를 수천 번 붙여서 누가 얼마나 강한지 재요.") { page(Page.Lab) }
    SheetHeading("리그")
    if (v != null && v.matches == 0L && lg.hasSeed && !lg.running) {
        LinkLine("⚡ 미리 돌린 리그로 시작해요", "서버에서 미리 둔 12만 경기의 레이팅으로 시작해요.") {
            nav.sheet = Sheet.NONE
            lg.loadSeed()
        }
    }
    LinkLine("🗑 리그 초기화", "모든 경기 기록과 레이팅이 지워져요.", color = MaterialTheme.colorScheme.error, onClick = onReset)
}

/** One choice of [labels] as segmented buttons ([selected] is lit). */
@Composable
private fun Segments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        for ((i, label) in labels.withIndex()) {
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                icon = {},
            ) { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** What each table view shows (the 보기 sheet). */
private fun tableModeText(m: TableMode): String = when (m) {
    TableMode.TABLE -> "승점 순위와 승강 구역"
    TableMode.FORM -> "선수마다 최근 5경기 결과"
    TableMode.LUCK -> "같은 주사위 두 판의 합계로 매긴, 운을 뺀 순위"
    TableMode.ROUND -> "라운드마다 대진과 결과"
    TableMode.CHART -> "라운드마다 순위가 어떻게 바뀌었는지"
    TableMode.H2H -> "부 안의 맞대결 승점 표"
    TableMode.ODDS -> "남은 경기를 시뮬레이션한 우승·승강 확률"
}

private fun resultsFilterText(f: ResultsFilter): String = when (f) {
    ResultsFilter.ALL -> "모든 경기"
    ResultsFilter.FOLLOWED -> "응원하는 선수가 뛴 경기"
    ResultsFilter.LEAGUE -> "리그 경기"
    ResultsFilter.COMPS -> "요트컵, 챔피언스 리그 같은 대회 경기"
    ResultsFilter.FRIENDLY -> "친선 경기와 랭크전"
}

/** The rating ladder's explanation: the rating, its range, the tiers and the placement. */
@Composable
private fun RatingInfo() {
    Text(
        "레이팅은 모든 판을 브래들리-테리 모형(엘로와 같은 눈금)으로 맞춘 값이에요. 기대점수 AI가 1500이고, 7점이 승률 1%p쯤이에요.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "±는 95% 범위예요. 판이 적어 범위가 넓은(±20 넘게) 선수는 넘는 만큼의 3배를 내려서 순위를 매겨서, 운 좋은 출발로 꼭대기에 오르지 않아요. 판이 충분하면 레이팅만 봐요.",
        style = MaterialTheme.typography.bodySmall,
    )
    SheetHeading("티어")
    Text("마스터 1490↑ · 그랜드마스터 1500↑ 상위 10명 · 챌린저 1505↑ 상위 3명", style = MaterialTheme.typography.bodySmall)
    SheetNote("티어는 레이팅으로 매겨요. 그래서 판이 적은 선수는 티어가 높아도 아래에 놓일 수 있어요.")
    SheetHeading("배치")
    Text(
        "${Ladder.PLACEMENT}판을 두면 배치가 끝나 티어가 붙어요. 처음 몇십 판은 레이팅이 크게 흔들리고, 판이 쌓일수록 자리를 잡아요. " +
            "‘혼자 두기’로 ${CloneProfile.MIN_GAMES}판 이상 끝내면 내 실수 습관을 닮은 ‘내 분신’ AI가 리그에 들어와요.",
        style = MaterialTheme.typography.bodySmall,
    )
}

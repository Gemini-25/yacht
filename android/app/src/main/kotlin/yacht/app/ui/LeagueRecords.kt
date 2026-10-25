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
        else -> kind
    }
}

private fun division(kind: String, prefix: String): Int? =
    if (kind.startsWith(prefix)) kind.removePrefix(prefix).toIntOrNull() else null

/** Honours worth a trophy icon (titles and awards, not moves or call-ups). */
private fun trophy(kind: String): String? = when {
    kind.startsWith("UP") || kind.startsWith("DOWN") || kind.startsWith("STAY") || kind == "FSQ" -> null
    kind == "L0" -> "👑"
    division(kind, "L") != null -> "🥇"
    kind in setOf("CUP", "CL", "SUPER", "FAMILY", "CLUB") -> "🏆"
    kind in setOf("MVP", "ROOKIE", "MIP", "YACHT", "SCORER", "REG") -> "🏅"
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
        p.genes?.takeIf { p.origin == Origin.GENERATED || p.origin == Origin.EVOLVED }?.let {
            Text("유전자: $it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Honours(p)
        Genealogy(p)
    }
}

@Composable
private fun Honours(p: ProfileView) {
    val cs = MaterialTheme.colorScheme
    val shown = p.honours.filter { trophy(it.kind) != null }
    if (shown.isEmpty()) return
    Text("🏆 수상 ${p.titles}회 우승", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
    Text("🌳 계보도", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    val up = listOf("부모", "조부모", "증조부모", "고조부모")
    for ((g, row) in p.ancestors.withIndex()) {
        val names = row.filterNotNull().distinct()
        if (names.isEmpty()) continue
        Text("${up.getOrElse(g) { "${g + 1}대 위" }}: ${names.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
    val down = listOf("자녀", "손주", "증손주", "고손주")
    for ((g, row) in p.descendants.withIndex()) {
        Text("${down.getOrElse(g) { "${g + 1}대 아래" }} ${row.size}: ${row.take(8).joinToString(", ")}${if (row.size > 8) " 외" else ""}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

private enum class RecordsMode(val label: String) {
    HALL("명예의 전당"),
    LEGENDS("레전드 보관소"),
    FAMILIES("계열별"),
}

/** 기록: the hall of fame by season, the legends and retired players, and the families. */
@Composable
fun RecordsTab(lg: LeagueController, v: LeagueView, onOpen: (String) -> Unit, families: @Composable () -> Unit) {
    var mode by remember { mutableStateOf(RecordsMode.HALL) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in RecordsMode.entries) FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 12.sp) })
        }
        when (mode) {
            RecordsMode.HALL -> HallOfFame(v.season?.history.orEmpty())
            RecordsMode.LEGENDS -> Legends(lg, onOpen)
            RecordsMode.FAMILIES -> families()
        }
    }
}

@Composable
private fun HallOfFame(history: List<SeasonHistoryItem>) {
    val cs = MaterialTheme.colorScheme
    if (history.isEmpty()) {
        CenterText("시즌이 끝나면 우승팀과 수상자가 여기에 쌓여요.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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


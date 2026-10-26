package yacht.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import yacht.engine.league.Abilities
import yacht.engine.league.Tendencies

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
 * 이 주의 선수 count.
 */
@Composable
fun IdentityPart(id: IdentityView, alive: Boolean) {
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
private fun TendencyBars(t: List<Int>) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in id.chips) {
                    Text(c, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(cs.surface).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        id.school?.let { Text("${id.flag} ${id.nation} 스타일: $it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
    }
}

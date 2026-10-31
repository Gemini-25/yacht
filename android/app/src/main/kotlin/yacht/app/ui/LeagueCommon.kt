package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.engine.league.Ladder
import yacht.engine.league.Tier
import yacht.engine.league.TierName

/** Badge colours of each tier. */
internal fun tierColor(t: TierName): Color = when (t) {
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
internal fun TierBadge(t: Tier?, games: Int, modifier: Modifier = Modifier, placement: Int = Ladder.PLACEMENT) {
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
internal fun RankChange(change: Int?) {
    val cs = MaterialTheme.colorScheme
    when {
        change == null || change == 0 -> Text("–", style = MaterialTheme.typography.labelSmall, color = cs.outline)
        change > 0 -> Text("▲$change", style = MaterialTheme.typography.labelSmall, color = gameColors.good, fontWeight = FontWeight.Bold)
        else -> Text("▼${-change}", style = MaterialTheme.typography.labelSmall, color = cs.error, fontWeight = FontWeight.Bold)
    }
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
internal fun StreakText(streak: Int) {
    when {
        streak >= 3 -> Text("  🔥${streak}연승", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        streak <= -3 -> Text("  🥶${-streak}연패", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** A small tag next to a result: "완승" (a 2-0 sweep, worth the bonus point under 판 승점제). */
@Composable
internal fun SweepTag(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = cs.onSecondaryContainer,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(4.dp)).background(cs.secondaryContainer).padding(horizontal = 4.dp),
    )
}

/** Games won with a drawn game as half: "3", "2.5". */
internal fun halfText(x: Double): String = if (x == Math.floor(x)) "%.0f".format(x) else "%.1f".format(x)

/** 평점 2.0's grades: 8.0 and above 월드클래스 (blue), 7.0 and above good (green), below 5.0 poor (red). */
internal enum class RatingGrade(val label: String?) { WORLD_CLASS("월드클래스"), GOOD(null), POOR(null) }

/** [r]'s grade; null for an ordinary 5.0-7.0, or no rating. */
internal fun ratingGrade(r: Double?): RatingGrade? = when {
    r == null || r.isNaN() -> null
    r >= 8.0 -> RatingGrade.WORLD_CLASS
    r >= 7.0 -> RatingGrade.GOOD
    r < 5.0 -> RatingGrade.POOR
    else -> null
}

/** The colour a 평점 is written in ([ratingGrade]); [Color.Unspecified] for an ordinary one. */
@Composable
internal fun ratingColor(r: Double?): Color = when (ratingGrade(r)) {
    RatingGrade.WORLD_CLASS -> Color(0xFF3D7BF0)
    RatingGrade.GOOD -> gameColors.good
    RatingGrade.POOR -> MaterialTheme.colorScheme.error
    null -> Color.Unspecified
}

/** A 평점 in its grade's colour with [digits] decimals, and "월드클래스" after it when [tagged]. */
@Composable
internal fun RatingText(r: Double, digits: Int = 1, tagged: Boolean = false, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium, bold: Boolean = true) {
    val label = ratingGrade(r)?.label
    Text(
        "%.${digits}f".format(r) + if (tagged && label != null) " $label" else "",
        style = style, color = ratingColor(r), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
    )
}

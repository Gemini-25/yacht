package yacht.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Distinct line colours for a few highlighted AIs (readable on light and dark backgrounds). */
val chartColors = listOf(
    Color(0xFF2E7DFF), Color(0xFFE5484D), Color(0xFF12A594), Color(0xFFF5A524),
    Color(0xFF8E4EC6), Color(0xFFD6409F), Color(0xFF6E7B8B), Color(0xFF3E9B4F),
)

/**
 * 승률 흐름: the first player's P(win) after each half-turn ([curve], percent), with the 50%
 * line; above the middle the first player leads.
 */
@Composable
fun WinCurve(curve: ByteArray, firstName: String, secondName: String, modifier: Modifier = Modifier, height: Dp = 80.dp) {
    if (curve.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val first = gameColors.me
    val second = gameColors.opp
    val grid = cs.outlineVariant
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(first)
            Text(" $firstName", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("$secondName ", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Dot(second)
        }
        Canvas(Modifier.fillMaxWidth().height(height).padding(vertical = 2.dp)) {
            val w = size.width
            val h = size.height
            drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            // Turn boundaries every 2 half-turns, faintly.
            val n = curve.size
            val step = w / n
            fun y(p: Int) = h * (1f - p / 100f)
            val path = Path().apply {
                moveTo(0f, y(50))
                for (i in 0 until n) lineTo(step * (i + 1), y(curve[i].toInt()))
            }
            // Fill above and below the middle in each side's colour.
            val fill = Path().apply {
                addPath(path)
                lineTo(w, h / 2)
                lineTo(0f, h / 2)
                close()
            }
            drawPath(fill, first.copy(alpha = 0.12f))
            drawPath(path, cs.onSurface.copy(alpha = 0.85f), style = Stroke(width = 3f))
            val last = curve.last().toInt()
            drawCircle(if (last >= 50) first else second, radius = 5f, center = Offset(w, y(last)))
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** One AI's line in [RankChart]: its place after each matchday (1 = top). */
data class RankSeries(val name: String, val places: List<Int>, val color: Color)

/** 순위 변동 그래프: places after each matchday, top at the top, for a few AIs. */
@Composable
fun RankChart(series: List<RankSeries>, teams: Int, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val cs = MaterialTheme.colorScheme
    val grid = cs.outlineVariant
    val matchdays = series.maxOfOrNull { it.places.size } ?: 0
    if (matchdays < 2 || teams < 2) {
        Text("라운드가 두 번 이상 끝나면 그래프가 그려져요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val h = size.height
            val dx = w / (matchdays - 1)
            fun y(place: Int) = h * (place - 1) / (teams - 1).toFloat()
            for (k in listOf(1, (teams + 1) / 2, teams)) drawLine(grid, Offset(0f, y(k)), Offset(w, y(k)), strokeWidth = 1f)
            for (s in series) {
                if (s.places.size < 2) continue
                val path = Path().apply {
                    moveTo(0f, y(s.places[0]))
                    for (i in 1 until s.places.size) lineTo(dx * i, y(s.places[i]))
                }
                drawPath(path, s.color, style = Stroke(width = 4f))
                drawCircle(s.color, radius = 6f, center = Offset(dx * (s.places.size - 1), y(s.places.last())))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            for (s in series.take(4)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Dot(s.color)
                    Text(" ${s.name} ${s.places.lastOrNull()?.let { "${it}위" } ?: ""}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (series.size > 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                for (s in series.drop(4).take(4)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Dot(s.color)
                        Text(" ${s.name} ${s.places.lastOrNull()?.let { "${it}위" } ?: ""}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * A simple line over seasons or matches (성장 곡선: rating per season), [labels] under the
 * first and last point; [mark] highlights points (e.g. title seasons) with a ring.
 */
@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, height: Dp = 90.dp, labels: Pair<String, String>? = null, mark: Set<Int> = emptySet()) {
    if (values.size < 2) return
    val line = gameColors.me
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ring = MaterialTheme.colorScheme.secondary
    val lo = values.min()
    val hi = values.max()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val span = maxOf(1e-9, hi - lo).toFloat()
            val step = size.width / (values.size - 1)
            fun y(v: Double) = size.height * (1f - ((v - lo) / span).toFloat()) * 0.9f + size.height * 0.05f
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1f)
            for (i in 1 until values.size) drawLine(line, Offset(step * (i - 1), y(values[i - 1])), Offset(step * i, y(values[i])), strokeWidth = 4f)
            for (i in mark) if (i in values.indices) drawCircle(ring, radius = 8f, center = Offset(step * i, y(values[i])), style = Stroke(width = 3f))
        }
        Row {
            Text(labels?.first ?: "%.0f".format(lo), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(labels?.second ?: "%.0f".format(hi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 점수 분포: games by score in bars of [binPoints] points ([bins], lowest first; the last bar takes
 * everything above), with [median] (a score, NaN for none) as a dashed line, and the scale under it.
 */
@Composable
fun ScoreHistogram(bins: IntArray, median: Double, binPoints: Int, modifier: Modifier = Modifier, height: Dp = 110.dp) {
    val top = bins.maxOrNull()?.takeIf { it > 0 } ?: return
    val cs = MaterialTheme.colorScheme
    val bar = gameColors.me
    val line = cs.tertiary
    val grid = cs.outlineVariant
    val span = bins.size * binPoints
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val h = size.height
            val slot = w / bins.size
            drawLine(grid, Offset(0f, h), Offset(w, h), strokeWidth = 1f)
            for ((i, n) in bins.withIndex()) {
                if (n == 0) continue
                val bh = h * n / top
                drawRect(bar, topLeft = Offset(slot * i + slot * 0.12f, h - bh), size = androidx.compose.ui.geometry.Size(slot * 0.76f, bh))
            }
            if (!median.isNaN()) {
                val x = w * (median / span).toFloat().coerceIn(0f, 1f)
                drawLine(line, Offset(x, 0f), Offset(x, h), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val small = MaterialTheme.typography.labelSmall
            Text("0", style = small, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${span / 2}", style = small, color = cs.onSurfaceVariant)
            Text("$span", style = small, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** Horizontal bars (e.g. P(win) per AI, box averages), value in 0..1 of [max]. */
@Composable
fun BarRow(label: String, value: Double, max: Double, text: String, color: Color = gameColors.me) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.38f))
        Box(Modifier.weight(0.47f).height(10.dp).clip(CircleShape).background(cs.surfaceVariant)) {
            val f = if (max <= 0) 0f else (value / max).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(f).height(10.dp).clip(CircleShape).background(color))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.15f).padding(start = 4.dp))
    }
}

/** Red (0) through grey (0.5) to green (1): a points share as a colour. */
fun shareColor(p: Double, neutral: Color): Color {
    if (p.isNaN()) return neutral
    val t = ((p - 0.5) * 2).coerceIn(-1.0, 1.0).toFloat()
    val good = Color(0xFF2E9D4A)
    val bad = Color(0xFFD9443A)
    val mid = Color(0xFFBFC5BC)
    return if (t >= 0) lerpColor(mid, good, t) else lerpColor(mid, bad, -t)
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/**
 * 상대 전적 매트릭스: row i's points share against column j as a coloured cell; tap a cell for
 * the numbers.
 */
@Composable
fun H2hGrid(m: yacht.app.core.H2hMatrix) {
    val cs = MaterialTheme.colorScheme
    val n = m.labels.size
    // Kept when the same matrix is read again (same title and AIs, newer numbers).
    var picked by androidx.compose.runtime.remember(m.title, m.labels) { androidx.compose.runtime.mutableStateOf<Pair<Int, Int>?>(null) }
    val cell = if (n <= 12) 24.dp else if (n <= 30) 16.dp else 11.dp
    val neutral = cs.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            picked?.let { (i, j) ->
                val g = m.games[i][j]
                val sc = m.score[i][j]
                if (g == 0) "${m.labels[i]} 대 ${m.labels[j]}: 아직 맞붙은 적이 없어요"
                else "${m.labels[i]} 대 ${m.labels[j]}: ${g}판에서 승점 ${"%.0f".format(100 * (if (sc.isNaN()) 0.5 else sc))}%"
            } ?: "칸을 누르면 두 AI의 상대 전적이 나와요. 초록일수록 행(왼쪽) AI가 강했어요.",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
        Row {
            Column {
                Box(Modifier.height(cell))
                for (i in 0 until n) {
                    Box(Modifier.height(cell).width(96.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            "${i + 1} ${m.labels[i]}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = if (n > 30) androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp) else MaterialTheme.typography.labelSmall.fontSize,
                        )
                    }
                }
            }
            Column(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                Row {
                    for (j in 0 until n) {
                        Box(Modifier.size(cell), contentAlignment = Alignment.Center) {
                            Text("${j + 1}", fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp), color = cs.onSurfaceVariant)
                        }
                    }
                }
                val px = cell
                Canvas(
                    Modifier.size(px * n, px * n).pointerInput(m) {
                        detectTapGestures { o ->
                            val s = size.width / n.toFloat()
                            val i = (o.y / s).toInt()
                            val j = (o.x / s).toInt()
                            if (i in 0 until n && j in 0 until n && i != j) picked = i to j
                        }
                    },
                ) {
                    val s = size.width / n
                    for (i in 0 until n) for (j in 0 until n) {
                        val c = if (i == j) neutral.copy(alpha = 0.4f) else shareColor(m.score[i][j], neutral)
                        drawRect(c, topLeft = Offset(j * s + 0.5f, i * s + 0.5f), size = androidx.compose.ui.geometry.Size(s - 1f, s - 1f))
                    }
                    picked?.let { (i, j) ->
                        drawRect(Color.Black, topLeft = Offset(j * s, i * s), size = androidx.compose.ui.geometry.Size(s, s), style = Stroke(width = 3f))
                    }
                }
            }
        }
    }
}

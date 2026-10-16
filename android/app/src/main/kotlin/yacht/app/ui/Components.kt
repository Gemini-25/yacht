package yacht.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.engine.Mode

// ---- formatting ------------------------------------------------------------------------------

fun pct(v: Double): String = "%.1f%%".format(v.coerceIn(0.0, 1.0) * 100)

fun fmtValue(mode: Mode, v: Double): String = if (mode == Mode.WIN) pct(v) else "%.1f점".format(v)

fun fmtDelta(mode: Mode, d: Double): String =
    if (mode == Mode.WIN) "%+.1f%%p".format(d * 100) else "%+.1f점".format(d)

// ---- dice ------------------------------------------------------------------------------------

private val PIPS = mapOf(
    1 to listOf(0.5f to 0.5f),
    2 to listOf(0.27f to 0.27f, 0.73f to 0.73f),
    3 to listOf(0.27f to 0.27f, 0.5f to 0.5f, 0.73f to 0.73f),
    4 to listOf(0.27f to 0.27f, 0.73f to 0.27f, 0.27f to 0.73f, 0.73f to 0.73f),
    5 to listOf(0.27f to 0.27f, 0.73f to 0.27f, 0.5f to 0.5f, 0.27f to 0.73f, 0.73f to 0.73f),
    6 to listOf(0.27f to 0.24f, 0.73f to 0.24f, 0.27f to 0.5f, 0.73f to 0.5f, 0.27f to 0.76f, 0.73f to 0.76f),
)

/** A die face that fills its box. */
@Composable
fun DieFace(face: Int, modifier: Modifier = Modifier, body: Color = gameColors.dieBody, pip: Color = gameColors.diePip) {
    val one = gameColors.dieOne
    Canvas(modifier.fillMaxSize().clip(RoundedCornerShape(18)).background(body)) {
        val r = size.minDimension * (if (face == 1) 0.13f else 0.095f)
        for ((x, y) in PIPS.getValue(face)) {
            drawCircle(if (face == 1) one else pip, r, Offset(size.width * x, size.height * y))
        }
    }
}

/** Small inline die, e.g. inside text rows. */
@Composable
fun MiniDie(face: Int, size: Dp = 18.dp) {
    Box(
        Modifier.size(size).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22)),
    ) { DieFace(face) }
}

@Composable
fun MiniDice(faces: List<Int>, size: Dp = 18.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (f in faces) MiniDie(f, size)
    }
}

/**
 * The five dice on the table. Held dice are lifted and framed; [suggested] marks dice the
 * advisor would hold (when they differ from the current holds).
 */
@Composable
fun DiceTray(
    dice: List<Int?>,
    held: List<Boolean>,
    suggested: List<Boolean>?,
    onDie: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (i in 0 until 5) {
            val face = dice[i]
            val isHeld = held[i]
            val lift by animateFloatAsState(if (isHeld) -8f else 0f, label = "lift")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).offset(y = lift.dp)
                        .shadow(if (face != null) (if (isHeld) 6.dp else 2.dp) else 0.dp, RoundedCornerShape(18))
                        .clip(RoundedCornerShape(18))
                        .background(if (face == null) cs.surfaceVariant else gameColors.dieBody)
                        .border(
                            width = if (isHeld) 3.dp else 1.dp,
                            color = when {
                                isHeld -> cs.primary
                                suggested?.get(i) == true -> gameColors.recommend
                                else -> cs.outlineVariant
                            },
                            shape = RoundedCornerShape(18),
                        )
                        .clickable(enabled = face != null) { onDie(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (face == null) Text("?", color = cs.outline, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    else Box(Modifier.padding(3.dp)) { DieFace(face) }
                }
                Text(
                    if (isHeld) "고정" else if (suggested?.get(i) == true) "추천" else " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHeld) cs.primary else gameColors.onRecommend.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ---- win bar ---------------------------------------------------------------------------------

/** Horizontal split bar: my share on the left in my colour, the rest in the opponent's. */
@Composable
fun WinBar(p: Double, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val frac by animateFloatAsState(p.coerceIn(0.0, 1.0).toFloat(), label = "win")
    val me = gameColors.me
    val opp = gameColors.opp
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))) {
        drawRect(opp.copy(alpha = 0.55f))
        drawRect(me, size = size.copy(width = size.width * frac))
        drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
    }
}

/** Simple line chart of values in 0..1 (e.g. win probability by turn). */
@Composable
fun ProbabilityChart(points: List<Double>, modifier: Modifier = Modifier) {
    val line = gameColors.me
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        for (k in 0..4) {
            val y = size.height * k / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = if (k == 2) 2f else 1f)
        }
        if (points.size < 2) return@Canvas
        val step = size.width / (points.size - 1)
        for (i in 1 until points.size) {
            val a = Offset(step * (i - 1), size.height * (1f - points[i - 1].toFloat().coerceIn(0f, 1f)))
            val b = Offset(step * i, size.height * (1f - points[i].toFloat().coerceIn(0f, 1f)))
            drawLine(line, a, b, strokeWidth = 5f)
        }
        for (i in points.indices) {
            drawCircle(line, 6f, Offset(step * i, size.height * (1f - points[i].toFloat().coerceIn(0f, 1f))))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

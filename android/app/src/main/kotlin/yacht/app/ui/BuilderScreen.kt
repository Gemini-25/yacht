@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.Screen
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomBase
import yacht.engine.league.CustomMistake
import yacht.engine.league.LeagueRoster
import java.util.Locale
import kotlin.math.roundToInt

/** Building (or editing) one of my own AIs for the league. */
@Composable
fun BuilderScreen(c: AppController) {
    val lg = c.league
    val editing = c.editingAi
    var spec by remember(editing) {
        mutableStateOf(editing ?: CustomAiSpec(System.currentTimeMillis().toString(36), "나만의 AI ${lg.customs.size + 1}"))
    }
    val cs = MaterialTheme.colorScheme
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                title = { Text(if (editing == null) "🛠 AI 만들기" else "🛠 AI 고치기", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { c.screen = Screen.LEAGUE }) { Text("리그") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = spec.name,
                onValueChange = { spec = spec.copy(name = it.take(20)) },
                label = { Text("이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle("기본 실력")
            Chips(CustomBase.entries.map { it to it.label }, spec.base) { b ->
                // "감으로" works on points only: other bases turn it into the matching slip rate.
                val n = spec.copy(base = b, belief = if (b == CustomBase.EV) spec.belief else null)
                spec = if (n.mistake == CustomMistake.FEEL && b != CustomBase.EV) n.copy(mistake = CustomMistake.SLIP, amount = n.effectiveAmount) else n
            }
            Text(
                when (spec.base) {
                    CustomBase.EV -> "평균 점수를 최대로 하는 정석. 아래 취향을 더할 수 있어요."
                    CustomBase.WIN -> "앱의 승률 AI처럼 이길 확률을 계산해요(계산이 무거워요). 취향은 쓰지 않아요."
                    CustomBase.RISK -> "큰 점수를 좋아하거나(+) 크게 망하는 걸 싫어하는(−) 성향. 취향은 쓰지 않아요."
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (spec.base == CustomBase.RISK) {
                Chips(LeagueRoster.THETAS.map { it to "θ=" + (if (it > 0) "+" else "") + it }, spec.theta) { spec = spec.copy(theta = it) }
            }
            SectionTitle("실수")
            val mistakes = if (spec.base == CustomBase.EV) CustomMistake.entries else listOf(CustomMistake.NONE, CustomMistake.SLIP)
            Chips(mistakes.map { it to it.label }, spec.mistake) {
                spec = spec.copy(mistake = it, amount = if (it == CustomMistake.FEEL) 0.5 else if (it == CustomMistake.SLIP) 0.05 else 0.0)
            }
            when (spec.mistake) {
                CustomMistake.SLIP -> LabeledSlider("판단마다 차선을 둘 확률", spec.amount, 0f..0.5f, { "${(it * 100).roundToInt()}%" }) { spec = spec.copy(amount = it) }
                CustomMistake.FEEL -> LabeledSlider("감(τ, 이만큼의 점수 차이는 헷갈림)", spec.amount, 0.1f..4f, { String.format(Locale.ROOT, "%.1f점", it) }) { spec = spec.copy(amount = it) }
                CustomMistake.NONE -> {}
            }
            SectionTitle("기질")
            Chips(yacht.engine.league.Temper.entries.map { it to it.label }, spec.temper) { spec = spec.copy(temper = it) }
            Text(spec.temper.desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (spec.base == CustomBase.EV) {
                SectionTitle("규칙 착각")
                Chips(listOf<String?>(null).map { it to "정확히 앎" } + CustomAiSpec.BELIEFS.map { (k, v) -> k to v }, spec.belief) { spec = spec.copy(belief = it) }
                SectionTitle("취향")
                LabeledSlider("요트 성공", spec.yacht, -30f..60f, { signed(it) }) { spec = spec.copy(yacht = it) }
                LabeledSlider("스트레이트 성공", spec.straight, -10f..20f, { signed(it) }) { spec = spec.copy(straight = it) }
                LabeledSlider("상단 1점마다 덤", spec.bonus, 0f..1.5f, { String.format(Locale.ROOT, "+%.1f", it) }) { spec = spec.copy(bonus = it) }
                LabeledSlider("0점 기록을 싫어함", spec.zero, 0f..30f, { if (it == 0.0) "괜찮음" else "−${it.roundToInt()}점" }) { spec = spec.copy(zero = it) }
                LabeledSlider("앞으로 받을 점수 반영", spec.future, 0.3f..1.5f, { "${(it * 100).roundToInt()}%" }) { spec = spec.copy(future = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = spec.saveChoice, onCheckedChange = { spec = spec.copy(saveChoice = it) })
                    Text("초이스는 마지막 3턴까지 아끼기", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("요약", style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer)
                    Text(spec.describe(), fontWeight = FontWeight.SemiBold, color = cs.onSecondaryContainer)
                    Text(
                        "저장하면 리그에 들어가고, 다음 시즌부터 가장 낮은 부에서 시작해요. 그 전까지는 친선전으로 레이팅을 쌓아요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSecondaryContainer,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                Button(onClick = {
                    lg.saveCustom(spec.copy(name = spec.name.trim().ifEmpty { "나만의 AI" }))
                    c.editingAi = null
                    c.screen = Screen.LEAGUE
                }) { Text("저장") }
                if (editing != null) {
                    OutlinedButton(onClick = {
                        lg.deleteCustom(editing.id)
                        c.editingAi = null
                        c.screen = Screen.LEAGUE
                    }) { Text("삭제") }
                }
            }
        }
    }
}

private fun signed(x: Double) = if (x == 0.0) "0" else (if (x > 0) "+" else "−") + "${kotlin.math.abs(x).roundToInt()}점"

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, label) in options) FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label, fontSize = 12.sp) })
    }
}

@Composable
private fun LabeledSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, show: (Double) -> String, onChange: (Double) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(show(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { v -> onChange(((v * 100).roundToInt() / 100.0)) },
            valueRange = range,
        )
    }
}

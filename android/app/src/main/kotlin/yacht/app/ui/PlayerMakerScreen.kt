@file:OptIn(ExperimentalMaterial3Api::class)

package yacht.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yacht.app.core.AppController
import yacht.app.core.Screen
import yacht.engine.league.Abilities
import yacht.engine.league.Confed
import yacht.engine.league.Gender
import yacht.engine.league.Nations
import yacht.engine.league.Origin
import yacht.engine.league.PlayStyles
import yacht.engine.league.PlayerDraft
import yacht.engine.league.PlayerMaker
import yacht.engine.league.Profiles
import yacht.engine.league.Temper
import yacht.engine.league.Tendencies
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 선수 만들기: a league player of my own, or one of the league's remade (선수 고치기): who it is
 * (name, gender, nation, hometown, age), its abilities and potential, how it errs, its temper and
 * its style, with a preview of what that makes of it.
 */
@Composable
fun PlayerMakerScreen(c: AppController) {
    val lg = c.league
    val key = c.editingPlayer
    // A new player starts drawn (every field already sensible); an edited one from what it is.
    var d by remember(key) { mutableStateOf(key?.let { lg.draftOf(it) } ?: lg.randomDraft()) }
    var problem by remember(key) { mutableStateOf<String?>(null) }
    val made = key != null && Origin.of(key) == Origin.CREATED
    val cs = MaterialTheme.colorScheme
    fun close() {
        c.editingPlayer = null
        c.screen = Screen.LEAGUE
    }
    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                title = { Text(if (key == null) "🧑 선수 만들기" else "✏️ 선수 고치기", fontWeight = FontWeight.Bold) },
                navigationIcon = { TextButton(onClick = { close() }) { Text("리그") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { d = d.copy(name = it.take(PlayerMaker.NAME_LENGTH)) },
                    label = { Text("이름") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (key == null) TextButton(onClick = { d = lg.randomDraft() }) { Text("🎲 무작위") }
            }
            Pick(Gender.entries.map { it to it.label }, d.gender) { d = d.copy(gender = it) }

            Title("국적")
            for (confed in Confed.entries) {
                val nations = Nations.ALL.filter { it.confed == confed }
                Pick(nations.map { it.code to "${it.flag} ${it.short}" }, d.nation) { code ->
                    val n = Nations.of(code)
                    // Its hometown follows the nation unless written by hand.
                    val town = if (n != null && Nations.of(d.nation)?.cities?.names?.contains(d.hometown) != false) n.cities.names.first() else d.hometown
                    d = d.copy(nation = code, hometown = town)
                }
            }
            Title("고향")
            val cities = Nations.of(d.nation)?.cities?.names.orEmpty().take(CITIES)
            Pick(cities.map { it to it }, d.hometown) { d = d.copy(hometown = it) }
            OutlinedTextField(
                value = d.hometown,
                onValueChange = { d = d.copy(hometown = it.take(PlayerMaker.NAME_LENGTH)) },
                label = { Text("고향 (직접 적기)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Title("나이")
            Stepped("나이", d.age, PlayerMaker.MIN_AGE..PlayerMaker.MAX_AGE, { "${it}세 · ${Profiles.stage(it)}" }) { d = d.copy(age = it) }
            Row(Modifier.fillMaxWidth().clickable { d = d.copy(keep = !d.keep) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("영원히 현역", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (d.keep) "나이와 능력치가 그대로이고 은퇴하지 않아요." else "다른 선수처럼 해마다 나이를 먹고, 자라다가 기량이 떨어지면 은퇴해요.",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                }
                Switch(checked = d.keep, onCheckedChange = { d = d.copy(keep = it) })
            }

            val abl = d.abilities.toList()
            Title("능력치 (1~99) · 종합 ${d.abilities.overall} ${abilityGrade(d.abilities.overall)}")
            for (i in abl.indices) {
                Stepped(Abilities.LABELS[i], abl[i], 1..99, { "$it ${abilityGrade(it)}" }) { v ->
                    d = d.copy(abilities = Abilities.of(abl.toMutableList().also { it[i] = v }))
                }
                Text(Abilities.HELP[i], style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Stepped("잠재력", d.potential, 1..99, { "$it ${abilityGrade(it)}" }) { d = d.copy(potential = it) }
            Text(
                "25세까지 이 값을 향해 자라요." + if (d.potential < d.abilities.overall) " 지금 종합보다 낮으면 더 자라지 않아요." else "",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )

            Title("실수")
            val loss = Profiles.drop(d.abilities.judgement)
            if (loss <= 0.0) {
                Text("판단력이 ${Profiles.PERFECT} 이상이라 실수하지 않아요.", style = MaterialTheme.typography.bodySmall, color = gameColors.good)
            } else {
                Pick(listOf(true to "🎲 감으로 두는 편", false to "🎯 가끔 실수"), d.feel) { d = d.copy(feel = it) }
                Text(
                    (if (d.feel) "비슷한 수 사이에서 헷갈려요. " else "판단할 때마다 가끔 차선을 둬요. ") +
                        "실수로 판마다 평균 ${String.format(Locale.ROOT, "%.0f", loss)}점쯤 잃어요.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            Title("기질")
            if (d.abilities.mental >= Profiles.CALM) {
                Text("멘탈이 ${Profiles.CALM} 이상이라 기질이 드러나지 않아요. 멘탈을 낮추면 아래 기질이 나와요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Pick(Temper.entries.filter { it != Temper.NONE }.map { it to it.label }, d.temper) { d = d.copy(temper = it) }
            Text(d.temper.desc + ".", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)

            StylePart(d) { d = it }

            Preview(d)

            problem?.let { Text("⚠ $it", color = cs.error, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                Button(onClick = {
                    problem = lg.savePlayer(key, d)
                    if (problem == null) close()
                }) { Text("저장") }
                OutlinedButton(onClick = { close() }) { Text("취소") }
                if (made) {
                    OutlinedButton(onClick = {
                        lg.deletePlayer(key!!)
                        close()
                    }) { Text("지우기") }
                }
            }
        }
    }
}

/** 플레이 스타일: the main style and its intensity, a second style, an overlay, and the habits it brings along. */
@Composable
private fun StylePart(d: PlayerDraft, onChange: (PlayerDraft) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var openMain by remember { mutableStateOf(false) }
    var openSecond by remember { mutableStateOf(false) }
    val g = d.play
    val main = PlayStyles.recipe(g.primary)
    Title("플레이 스타일")
    Choice("주 유형", main?.name ?: g.primary, main?.desc, openMain) { openMain = !openMain }
    if (openMain) {
        for (r in PlayStyles.RECIPES) {
            Option(r.name, r.desc, r.key == g.primary) {
                onChange(d.withPrimary(r.key))
                openMain = false
            }
        }
    }
    if (main?.scales == true) {
        Stepped("세기", (g.k * 100).roundToInt(), (PlayerMaker.MIN_K * 100).roundToInt()..(PlayerMaker.MAX_K * 100).roundToInt(), { v ->
            when {
                v < 90 -> "약하게"
                v <= 110 -> "보통"
                v <= 140 -> "강하게"
                else -> "아주 강하게"
            } + " ×" + String.format(Locale.ROOT, "%.2f", v / 100.0)
        }, step = 5) { onChange(d.copy(play = g.copy(k = it / 100.0))) }
    }
    val second = g.secondary?.let { PlayStyles.recipe(it) }
    Choice("보조 유형", second?.name ?: "없음", second?.desc?.let { "반쯤 섞여요: $it" }, openSecond) { openSecond = !openSecond }
    if (openSecond) {
        Option("없음", "주 유형만으로 둬요.", g.secondary == null) {
            onChange(d.copy(play = g.copy(secondary = null)))
            openSecond = false
        }
        for (r in PlayStyles.RECIPES.filter { it.scales && it.key != g.primary }) {
            Option(r.name, r.desc, r.key == g.secondary) {
                onChange(d.copy(play = g.copy(secondary = r.key)))
                openSecond = false
            }
        }
    }
    Text("상황 성향", style = MaterialTheme.typography.bodyMedium)
    Pick(listOf<String?>(null).map { it to "없음" } + PlayStyles.OVERLAYS.map { it.key to it.name }, g.overlay) { onChange(d.withOverlay(it)) }
    g.overlay?.let { PlayStyles.overlay(it) }?.let { Text(it.desc, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
    // Tastes and situational habits a drawn or edited player brings along (not set here).
    val notes = g.notes()
    if (notes.isNotEmpty()) {
        Text("이 선수만의 습관", style = MaterialTheme.typography.bodyMedium)
        for (n in notes) Text("· $n", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        TextButton(onClick = {
            onChange(d.copy(play = g.copy(taste = yacht.engine.league.Taste.NONE, margin = 0, ahead = null, behind = null, lateFrom = 0, late = null)))
        }) { Text("습관 지우기") }
    }
}

/** What the draft makes of the player: its tendencies, style and how it starts in the league. */
@Composable
private fun Preview(d: PlayerDraft) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val n = Nations.of(d.nation)
            Text(
                listOfNotNull(n?.let { "${it.flag} ${it.name}" }, d.gender.label, "${d.age}세", Profiles.stage(d.age)).joinToString(" · ") +
                    "  ·  종합 ${d.abilities.overall} ${abilityGrade(d.abilities.overall)}",
                fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer,
            )
            Text("🎨 ${d.play.label}", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
            TendencyBars(Tendencies.of(d.play.style()).toList())
            Text(
                "저장하면 리그에 들어와요. 이번 시즌에는 친선전으로 레이팅을 쌓고, 다음 시즌 가장 낮은 부에서 시작해 승격으로 올라가요.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun <T> Pick(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, label) in options) FilterChip(selected = v == selected, onClick = { onPick(v) }, label = { Text(label, fontSize = 12.sp) })
    }
}

/** A whole-number slider with − and + for one step. */
@Composable
private fun Stepped(label: String, value: Int, range: IntRange, show: (Int) -> String, step: Int = 1, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
            Text(show(value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
        }
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { v -> onChange((((v - range.first) / step).roundToInt() * step + range.first).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** A choice that opens a list: its label, what is chosen and a line about it. */
@Composable
private fun Choice(label: String, value: String, desc: String?, open: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).clickable(onClick = onToggle).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(if (open) "닫기" else "바꾸기", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
        Text(value, fontWeight = FontWeight.Bold)
        desc?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
    }
}

@Composable
private fun Option(name: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) cs.primaryContainer else cs.surface).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text((if (selected) "✓ " else "") + name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

/** Hometowns offered as chips (the rest can be written). */
private const val CITIES = 12

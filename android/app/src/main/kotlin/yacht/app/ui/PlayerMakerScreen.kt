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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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
    // Both come off the main thread: the league's lock may be held by a season's close.
    var loaded by remember(key) { mutableStateOf<PlayerDraft?>(null) }
    var gone by remember(key) { mutableStateOf(false) }
    var leaving by remember(key) { mutableStateOf(false) }
    var busy by remember(key) { mutableStateOf(false) }
    var problem by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) {
        if (key == null) loaded = lg.drawDraft()
        else {
            val x = lg.loadDraft(key)
            if (x == null) gone = true else loaded = x
            leaving = x != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { lg.isLeaving(key) }
        }
    }
    val made = key != null && Origin.of(key) == Origin.CREATED
    val cs = MaterialTheme.colorScheme
    fun close() {
        c.editingPlayer = null
        c.screen = Screen.LEAGUE
    }
    val draft = loaded
    if (draft == null) {
        Scaffold(containerColor = cs.background) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (gone) "이 선수는 이제 리그에 없어요." else "불러오는 중…", style = MaterialTheme.typography.bodyLarge)
                if (gone) OutlinedButton(onClick = { close() }) { Text("리그로") }
            }
        }
        return
    }
    val d = draft
    fun set(x: PlayerDraft) {
        loaded = x
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
                    onValueChange = { set(d.copy(name = it.take(PlayerMaker.NAME_LENGTH))) },
                    label = { Text("이름") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (key == null) TextButton(onClick = { scope.launch { set(lg.drawDraft()) } }) { Text("🎲 무작위") }
            }
            Pick(Gender.entries.map { it to it.label }, d.gender) { set(d.copy(gender = it)) }

            Title("국적")
            for (confed in Confed.entries) {
                val nations = Nations.ALL.filter { it.confed == confed }
                Pick(nations.map { it.code to "${it.flag} ${it.short}" }, d.nation) { code ->
                    val n = Nations.of(code)
                    // Its hometown follows the nation unless written by hand (the same nation again changes nothing).
                    if (code != d.nation) {
                        val town = if (n != null && Nations.of(d.nation)?.cities?.names?.contains(d.hometown) != false) n.cities.names.first() else d.hometown
                        set(d.copy(nation = code, hometown = town))
                    }
                }
            }
            Title("고향")
            val cities = Nations.of(d.nation)?.cities?.names.orEmpty().take(CITIES)
            Pick(cities.map { it to it }, d.hometown) { set(d.copy(hometown = it)) }
            OutlinedTextField(
                value = d.hometown,
                onValueChange = { set(d.copy(hometown = it.take(PlayerMaker.NAME_LENGTH))) },
                label = { Text("고향 (직접 적기)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Title("나이")
            // 40 and up only forever in its prime: an aging player that old retires at once.
            Stepped("나이", d.age, PlayerMaker.MIN_AGE..PlayerMaker.maxAge(d.keep), { "${it}세 · ${Profiles.stage(it)}" }) { set(d.copy(age = it)) }
            fun keep(on: Boolean) = set(d.copy(keep = on, age = d.age.coerceAtMost(PlayerMaker.maxAge(on))))
            Row(Modifier.fillMaxWidth().clickable { keep(!d.keep) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("영원히 현역", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (d.keep) "나이와 능력치가 그대로이고 은퇴하지 않아요. ${PlayerMaker.MAX_AGE}세까지 정할 수 있어요."
                        else "다른 선수처럼 해마다 나이를 먹고, 자라다가 기량이 떨어지면 은퇴해요. ${PlayerMaker.maxAge(false)}세까지 정할 수 있어요.",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    )
                }
                Switch(checked = d.keep, onCheckedChange = { keep(it) })
            }

            val abl = d.abilities.toList()
            Title("능력치 (1~99) · 종합 ${d.abilities.overall} ${abilityGrade(d.abilities.overall)}")
            for (i in abl.indices) {
                Stepped(Abilities.LABELS[i], abl[i], 1..99, { "$it ${abilityGrade(it)}" }) { v ->
                    set(d.copy(abilities = Abilities.of(abl.toMutableList().also { it[i] = v })))
                }
                Text(Abilities.HELP[i], style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Stepped("잠재력", d.potential, 1..99, { "$it ${abilityGrade(it)}" }) { set(d.copy(potential = it)) }
            Text(
                if (d.keep) "영원히 현역이라 능력치가 그대로예요. 잠재력은 끄면 자라는 목표가 돼요."
                else "25세까지 이 값을 향해 자라요." + if (d.potential < d.abilities.overall) " 지금 종합보다 낮으면 더 자라지 않아요." else "",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )

            Title("실수")
            val loss = Profiles.drop(d.abilities.judgement)
            if (loss <= 0.0) {
                Text("판단력이 ${Profiles.PERFECT} 이상이라 실수하지 않아요.", style = MaterialTheme.typography.bodySmall, color = gameColors.good)
            } else {
                Pick(listOf(true to "🎲 감으로 두는 편", false to "🎯 가끔 실수"), d.feel) { set(d.copy(feel = it)) }
                Text(
                    (if (d.feel) "비슷한 수 사이에서 헷갈려요. " else "판단할 때마다 가끔 차선을 둬요. ") +
                        "실수로 판마다 평균 ${String.format(Locale.ROOT, if (loss < 1) "%.1f" else "%.0f", loss)}점쯤 잃어요.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            Title("기질")
            if (d.abilities.mental >= Profiles.CALM) {
                Text("멘탈이 ${Profiles.CALM} 이상이라 기질이 드러나지 않아요. 멘탈을 낮추면 아래 기질이 나와요.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Pick(Temper.entries.filter { it != Temper.NONE }.map { it to it.label }, d.temper) { set(d.copy(temper = it)) }
            Text(d.temper.desc + ".", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)

            StylePart(d) { set(it) }

            Preview(d, editing = key != null)

            problem?.let { Text("⚠ $it", color = cs.error, style = MaterialTheme.typography.bodyMedium) }
            if (made && leaving) {
                Text("이번 시즌을 마치면 은퇴해요. 기록은 그대로 남아요.", style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        problem = lg.storePlayer(key, d)
                        busy = false
                        if (problem == null) close()
                    }
                }) { Text("저장") }
                OutlinedButton(onClick = { close() }) { Text("취소") }
                if (made) {
                    // Let go at the season's end (its national team and club keep it until then).
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            if (lg.retirePlayer(key!!, !leaving)) leaving = !leaving else problem = "이 선수는 이제 리그에 없어요."
                            busy = false
                        }
                    }) { Text(if (leaving) "은퇴 취소" else "은퇴시키기") }
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
            onChange(d.copy(play = g.copy(taste = yacht.engine.league.Taste.NONE, margin = 0, ahead = null, behind = null, lateFrom = 0, late = null, far = false)))
        }) { Text("습관 지우기") }
    }
}

/** What the draft makes of the player: its tendencies, style and how it starts in the league ([editing]: what stays). */
@Composable
private fun Preview(d: PlayerDraft, editing: Boolean) {
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
                if (editing) "저장해도 기록과 레이팅, 지금 부는 그대로예요. 다음 경기부터 바뀐 능력치와 스타일로 둬요."
                else "저장하면 리그에 들어와요. 이번 시즌에는 친선전으로 레이팅을 쌓고, 다음 시즌 가장 낮은 부에서 시작해 승격으로 올라가요.",
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

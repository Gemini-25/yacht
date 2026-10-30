package yacht.uicheck

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import yacht.app.core.AiPace
import yacht.app.core.AppController
import yacht.app.core.GameStorage
import yacht.app.core.LeagueTab
import yacht.app.core.Screen
import yacht.app.core.ThinkTime
import yacht.app.ui.AppRoot
import yacht.app.ui.YachtTheme
import yacht.engine.Category
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.game.DiceSource
import yacht.engine.game.PlayStyle
import yacht.engine.game.Side
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.Base64
import java.util.Random
import kotlin.system.exitProcess

/**
 * Drives the real AppController through games and renders the Compose UI to images.
 * Usage: uicheck <tables dir> <output dir>. With PRINT_SHOTS=1 the JPEGs are also printed as
 * base64 between SHOT-BEGIN/SHOT-END markers (to read them back from CI logs).
 */
fun main(args: Array<String>) {
    // The controller's worker threads would keep the JVM alive after a failure: always exit.
    val code = try {
        run(args)
        0
    } catch (e: Throwable) {
        e.printStackTrace()
        1
    }
    System.out.flush()
    exitProcess(code)
}

private fun run(args: Array<String>) {
    val tablesDir = File(args.getOrElse(0) { "../tables" })
    val out = File(args.getOrElse(1) { "build/shots" }).apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val c = AppController(
        scope, GameStorage(Files.createTempDirectory("yacht").toFile()), loadTables = { load(tablesDir) },
        loadAsset = { name -> File("android/app/src/main/assets", name).takeIf { it.exists() }?.readText() },
    )
    c.start()
    waitFor("tables") { c.tables != null }
    c.changeThinkTime(ThinkTime.FAST)
    if (!c.whyOpen) c.toggleWhy()
    shot(c, out, "01_home")

    c.newGame(DiceSource.MANUAL, Side.OPP, "친구 A")
    waitFor("outlook") { c.oppOutlook != null }
    shot(c, out, "02_opponent_turn")
    c.opponentScore(Category.FULL_HOUSE, 23)
    waitPlan(c)
    for (f in listOf(6, 6, 3, 6, 2)) c.enterFace(f)
    waitPlan(c)
    shot(c, out, "03_first_roll_advice")
    c.reroll()
    c.enterFace(6)
    shot(c, out, "04_entering_second_roll")

    // Play on with random dice, following the advice, to reach the end game.
    val rng = Random(5)
    val oppCats = Category.ALL.filter { it != Category.FULL_HOUSE }.shuffled(rng)
    var oppIdx = 0
    var shotEndgame = false
    var shotComment = false
    var shotNote = false
    while (!c.state!!.isOver) {
        val s = c.state!!
        if (s.toMove == Side.OPP) {
            val cat = oppCats[oppIdx++]
            c.opponentScore(cat, s.rules.score(List(5) { rng.nextInt(6) + 1 }, cat))
            continue
        }
        waitPlan(c)
        if (!shotComment && s.me.filledCount >= 6) {
            shotComment = true
            shot(c, out, "05a_turn_start_comment")
        }
        while (c.state!!.draft.dice.any { it == null }) c.enterFace(rng.nextInt(6) + 1)
        waitPlan(c)
        if (s.me.filledCount == 9 && !shotEndgame) {
            shotEndgame = true
            shot(c, out, "05_endgame_exact")
            shot(c, out, "06_endgame_dark", dark = true)
        }
        val a = c.advice ?: error("no advice")
        val second = a.categories.getOrNull(1)
        if (a.shouldStop && !shotNote && s.me.filledCount >= 1 && second != null && second.value < a.value - 0.002) {
            // Write into the second-best box once to show the note on a move that differs from the advice.
            shotNote = true
            c.score(second.category)
            shot(c, out, "05b_deviation_note")
            continue
        }
        if (a.shouldStop) c.score(a.bestCategory.category) else c.reroll()
    }
    shot(c, out, "07_game_over")
    c.openReview(c.state!!)
    c.state!!.decisions.filter { it.isMistake }.maxByOrNull { it.loss }?.let { d ->
        c.loadWhatIf(d)
        waitFor("what-if", 120_000) { d in c.whatIfs || d !in c.whatIfBusy }
    }
    shot(c, out, "08_review", heightDp = 2600)
    c.openHistory()
    waitFor("history") { c.screen == Screen.HISTORY }
    shot(c, out, "09_history", heightDp = 1400)

    c.openQuiz()
    waitFor("quiz", 120_000) { c.quiz != null }
    shot(c, out, "12_quiz")
    val q = c.quiz!!
    // Answer with the most points now (a common mistake the quiz is built around).
    val greedy = Category.ALL.filter { !q.me.isFilled(it) }.maxBy { q.me.rules.score(q.dice, it) }
    c.quizScore(greedy)
    shot(c, out, "13_quiz_result")
    c.closeQuiz()

    // Against the AI, moving second: the AI's turn plays back throw by throw, then my turn
    // starts with its summary and reasons.
    c.changeAiPace(AiPace.SLOW)
    c.newGame(DiceSource.RANDOM, Side.OPP)
    waitFor("ai playback", 120_000) { c.aiSteps.let { it != null && c.aiShown == it.size } }
    shot(c, out, "10a_ai_playback", heightDp = 1100)
    c.skipAiPlayback()
    waitFor("ai turn", 120_000) { c.state!!.toMove == Side.ME }
    waitPlan(c)
    shot(c, out, "10_vs_ai", heightDp = 1100)
    c.firstThrow()
    waitPlan(c)
    shot(c, out, "10b_vs_ai_roll")

    // Undo against the AI: a written box can be taken back before the AI's turn shows, a throw cannot.
    val beforeScore = c.state!!
    check(!c.canUndo) { "a throw must not be undoable" }
    c.score(c.advice!!.categories.last().category)
    check(c.canUndo && c.state!!.toMove == Side.OPP) { "box not undoable" }
    shot(c, out, "10c_vs_ai_undo")
    c.undo()
    check(c.state == beforeScore) { "undo did not restore the dice" }
    check(!c.aiThinking) { "AI still thinking after undo" }

    // Playing alone: no advice during the game.
    c.changeStyle(PlayStyle.SOLO)
    c.screen = Screen.HOME
    shot(c, out, "14_home_solo", heightDp = 1300)
    c.newGame(DiceSource.RANDOM, Side.ME)
    c.firstThrow()
    waitPlan(c)
    shot(c, out, "15_solo")
    c.changeStyle(PlayStyle.ASSISTED)

    // The AI league: from the shipped seed when there is one (else played from zero until
    // everyone is placed), then a short run so the season starts. At each point of the run the
    // league's shots are taken by the file of the stage that owns their screens (LEAGUE_SHOTS).
    c.screen = Screen.LEAGUE
    waitFor("league view") { c.league.view != null }
    leagueShots(c) { it.empty(c, out) }
    c.league.changeSpeed(yacht.app.core.LeagueSpeed.FULL)
    if (c.league.hasSeed) {
        c.league.loadSeed()
        waitFor("league seed") { (c.league.view?.matches ?: 0L) > 0L }
    }
    c.league.start()
    waitFor("league matches", 300_000) {
        // The hand-made AIs come placed from the seed; the generated players place over the
        // first matchdays.
        (c.league.view?.rows?.count { it.rank != null } ?: 0) >= minOf(c.league.size - 2, 110) && (c.league.view?.season?.played ?: 0) >= 300
    }
    leagueShots(c) { it.running(c, out) }
    c.league.stop()
    waitFor("league stopped", 60_000) { !c.league.running }
    // 1주씩: ▶ plays the week in play and stops by itself, saying what it played.
    c.league.changeRunMode(yacht.app.core.LeagueRunMode.WEEK)
    c.league.start()
    waitFor("week started") { c.league.running }
    waitFor("week played", 300_000) { !c.league.running }
    check(c.league.weekDone != null) { "the week stopped without its note: ${c.league.stopReason}" }
    leagueShots(c) { it.weekDone(c, out) }
    c.league.changeRunMode(yacht.app.core.LeagueRunMode.ON)
    println("league: ${c.league.view?.matches} matches; top: " + c.league.view?.rows?.take(5)?.joinToString { "${it.name} ${it.rating}" })

    // A ranked game against a league AI near 1400, played greedily to the end.
    c.changeAiPace(AiPace.INSTANT)
    check(c.newRankedGame()) { "no ranked opponent" }
    var shotRanked = false
    while (true) {
        waitFor("my ranked turn", 120_000) { c.state!!.isOver || (c.state!!.toMove == Side.ME && !c.aiThinking) }
        if (c.state!!.isOver) break
        // The AI's last step may still be landing on the main thread: throw until it takes.
        waitFor("my ranked throw", 30_000) {
            c.state!!.draft.dice.all { it != null } || run {
                c.firstThrow()
                false
            }
        }
        val d = c.state!!
        val dice = d.draft.dice.map { it!! }
        if (!shotRanked) {
            shotRanked = true
            shot(c, out, "20_ranked_game", heightDp = 1000)
        }
        c.score(Category.ALL.filter { !d.me.isFilled(it) }.maxBy { d.rules.score(dice, it) })
    }
    waitFor("rank result") { c.rankResult != null }
    shot(c, out, "21_ranked_result", heightDp = 1200)
    println("ranked: ${c.state!!.me.total}:${c.state!!.opp.total} vs ${c.state!!.opponent} -> ${c.rankResult}")
    c.screen = Screen.HOME
    shot(c, out, "22_home_ranked_backup", heightDp = 2400)

    // The league stopped: watching a game throw by throw, and the other shots of a stopped league.
    c.changeAiPace(AiPace.NORMAL)
    leagueShots(c) { it.stopped(c, out) }

    // Building my own AI.
    c.editingAi = null
    c.screen = Screen.BUILDER
    shot(c, out, "25_builder", heightDp = 1700)
    c.league.saveCustom(yacht.engine.league.CustomAiSpec("shot", "요트 사랑", yacht.engine.league.CustomBase.EV, yacht = 25.0, mistake = yacht.engine.league.CustomMistake.FEEL, amount = 0.6))
    c.editingAi = c.league.customs.first()
    c.screen = Screen.LEAGUE
    c.screen = Screen.BUILDER
    shot(c, out, "26_builder_edit", heightDp = 1700)
    c.editingAi = null

    // Making a league player, then editing it (its page and the maker on it).
    c.editingPlayer = null
    c.screen = Screen.MAKER
    shot(c, out, "27_player_maker", heightDp = 3600, settleMs = 1500)
    val draft = c.league.randomDraft().copy(name = "스크린샷 선수", nation = "SE", hometown = "스톡홀름", age = 22, keep = true)
    check(c.league.savePlayer(null, draft) == null) { "the made player was not saved" }
    waitFor("made player") { c.league.view?.rows?.any { it.name == "스크린샷 선수" } == true }
    val made = c.league.view!!.rows.first { it.name == "스크린샷 선수" }.key
    c.editingPlayer = made
    shot(c, out, "28_player_maker_edit", heightDp = 1300, settleMs = 1500)
    c.editingPlayer = null

    c.screen = Screen.VERIFY
    c.startVerify(4)
    waitFor("verify", 600_000) { !c.verifying }
    shot(c, out, "11_verify")

    val s = c.state!!
    println("verify: ${c.verify.games} games, diff ${c.verify.diff}, plan ${c.verify.avgPlanMs} ms; last game ${s.me.total}:${s.opp.total}")
    leagueShots(c) { it.last(c, out) }
    c.shutdown()
}

private fun waitPlan(c: AppController) =
    waitFor("plan") { c.plan != null && !c.refining && c.plan!!.me == c.state!!.me }

private fun load(dir: File): Tables {
    val rules = RuleSet.NINTENDO
    fun map(name: String) = RandomAccessFile(File(dir, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    val bonus = File(dir, "bonus.bin").takeIf { it.exists() }?.let { TableIO.readBonus(map(it.name), rules) }
    return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules), bonus = bonus)
}

internal fun waitFor(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
    println("waiting for $what")
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
        Thread.sleep(20)
    }
}

/**
 * One stage's league shots (one file per stage, so stages never share a file): each point of the
 * run calls the same function of every file, in [LEAGUE_SHOTS] order, each from the league
 * screen's 홈 tab with no page or sheet open. A function may wait for more of the season, and
 * leaves the league running or stopped as it found it. Every function does nothing unless the
 * file overrides it.
 */
internal interface LeagueShots {
    /** The league screen on an empty league, before the seed and ▶. */
    fun empty(c: AppController, out: File) {}

    /** The league running at FULL: 110 players ranked at least (all but two of a smaller league) and 300 fixtures of season 1 played. */
    fun running(c: AppController, out: File) {}

    /** The league stopped by itself after a 1주씩 week. */
    fun weekDone(c: AppController, out: File) {}

    /** The league stopped, after a ranked game against one of its players. */
    fun stopped(c: AppController, out: File) {}

    /** After every other shot: a pass of its own that may replace the league (a legacy league, say). */
    fun last(c: AppController, out: File) {}
}

/** The league's shot files, in the order each point of the run calls them. */
private val LEAGUE_SHOTS: List<LeagueShots> = listOf(ShotsLeague, ShotsPlayer, ShotsWorld, ShotsWatch, ShotsRecords, ShotsHome)

/** Calls [phase] on every file of [LEAGUE_SHOTS], each from the league screen's 홈 tab. */
private fun leagueShots(c: AppController, phase: (LeagueShots) -> Unit) {
    for (s in LEAGUE_SHOTS) {
        c.screen = Screen.LEAGUE
        c.league.nav.go(LeagueTab.HOME)
        phase(s)
    }
}

/** Renders [content] alone (a card of the league screens) like [shot]. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun shotOf(out: File, name: String, heightDp: Int, dark: Boolean = false, widthDp: Int = WIDTH_DP, content: @Composable () -> Unit) {
    render(out, name, widthDp, heightDp, settleMs = 0, what = name) {
        YachtTheme(dark = dark) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.padding(12.dp)) { content() }
            }
        }
    }
}

/** [settleMs]: real time for a screen that loads its data off the main thread before the picture. */
internal fun shot(c: AppController, out: File, name: String, dark: Boolean = false, heightDp: Int = 850, settleMs: Long = 0, widthDp: Int = WIDTH_DP) {
    Thread.sleep(200)
    render(out, name, widthDp, heightDp, settleMs, "$name, screen=${c.screen}") { YachtTheme(dark = dark) { AppRoot(c) } }
}

/** The phone width every shot is rendered at, unless a narrower one is asked for (360 dp). */
private const val WIDTH_DP = 392

@OptIn(ExperimentalComposeUiApi::class)
private fun render(out: File, name: String, widthDp: Int, heightDp: Int, settleMs: Long, what: String, content: @Composable () -> Unit) {
    val density = 2f
    val scene = ImageComposeScene(width = (widthDp * density).toInt(), height = (heightDp * density).toInt(), density = Density(density), content = content)
    scene.render(0)
    if (settleMs > 0) {
        Thread.sleep(settleMs)
        scene.render(1_000_000_000L)
        Thread.sleep(100)
    }
    val image = scene.render(2_000_000_000L)
    File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    val jpeg = image.encodeToData(EncodedImageFormat.JPEG, 72)!!.bytes
    File(out, "$name.jpg").writeBytes(jpeg)
    scene.close()
    if (System.getenv("PRINT_SHOTS") == "1") {
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        println("SHOT-BEGIN $name ${b64.length}")
        b64.chunked(2000).forEach { println("SHOT|$it") }
        println("SHOT-END $name")
    }
    println("wrote $what (${jpeg.size / 1024} KiB)")
}

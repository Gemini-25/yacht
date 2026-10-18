package yacht.uicheck

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import yacht.app.core.AppController
import yacht.app.core.GameStorage
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
    val c = AppController(scope, GameStorage(Files.createTempDirectory("yacht").toFile())) { load(tablesDir) }
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

    // Against the AI, moving second: my turn starts with the AI's turn and its reasons.
    c.newGame(DiceSource.RANDOM, Side.OPP)
    waitFor("ai turn", 120_000) { c.state!!.toMove == Side.ME }
    waitPlan(c)
    shot(c, out, "10_vs_ai", heightDp = 1100)
    c.firstThrow()
    waitPlan(c)
    shot(c, out, "10b_vs_ai_roll")

    // Playing alone: no advice during the game.
    c.changeStyle(PlayStyle.SOLO)
    shot(c, out, "14_home_solo")
    c.newGame(DiceSource.RANDOM, Side.ME)
    c.firstThrow()
    waitPlan(c)
    shot(c, out, "15_solo")
    c.changeStyle(PlayStyle.ASSISTED)

    c.screen = Screen.VERIFY
    c.startVerify(4)
    waitFor("verify", 600_000) { !c.verifying }
    shot(c, out, "11_verify")

    val s = c.state!!
    println("verify: ${c.verify.games} games, diff ${c.verify.diff}, plan ${c.verify.avgPlanMs} ms; last game ${s.me.total}:${s.opp.total}")
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

private fun waitFor(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
    println("waiting for $what")
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
        Thread.sleep(20)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun shot(c: AppController, out: File, name: String, dark: Boolean = false, heightDp: Int = 850) {
    Thread.sleep(200)
    val density = 2f
    val scene = ImageComposeScene(width = (392 * density).toInt(), height = (heightDp * density).toInt(), density = Density(density)) {
        YachtTheme(dark = dark) { AppRoot(c) }
    }
    scene.render(0)
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
    println("wrote $name (${jpeg.size / 1024} KiB, screen=${c.screen})")
}

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
import yacht.app.ui.AppRoot
import yacht.app.ui.YachtTheme
import yacht.engine.Category
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.game.DiceSource
import yacht.engine.game.Side
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.Base64
import java.util.Random

/**
 * Drives the real AppController through games and renders the Compose UI to images.
 * Usage: uicheck <tables dir> <output dir>. With PRINT_SHOTS=1 the JPEGs are also printed as
 * base64 between SHOT-BEGIN/SHOT-END markers (to read them back from CI logs).
 */
fun main(args: Array<String>) {
    val tablesDir = File(args.getOrElse(0) { "../tables" })
    val out = File(args.getOrElse(1) { "build/shots" }).apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val c = AppController(scope, GameStorage(Files.createTempDirectory("yacht").toFile())) { load(tablesDir) }
    c.start()
    waitFor("tables") { c.tables != null }
    shot(c, out, "01_home")

    c.newGame(DiceSource.MANUAL, Side.OPP)
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
    while (!c.state!!.isOver) {
        val s = c.state!!
        if (s.toMove == Side.OPP) {
            val cat = oppCats[oppIdx++]
            c.opponentScore(cat, s.rules.score(List(5) { rng.nextInt(6) + 1 }, cat))
            continue
        }
        waitPlan(c)
        while (c.state!!.draft.dice.any { it == null }) c.enterFace(rng.nextInt(6) + 1)
        waitPlan(c)
        if (s.me.filledCount == 9 && !shotEndgame) {
            shotEndgame = true
            shot(c, out, "05_endgame_exact")
            shot(c, out, "06_endgame_dark", dark = true)
        }
        val a = c.advice ?: error("no advice")
        if (a.shouldStop) c.score(a.bestCategory.category) else c.reroll()
    }
    shot(c, out, "07_game_over")
    c.openReview(c.state!!)
    shot(c, out, "08_review")
    c.openHistory()
    waitFor("history") { c.screen == Screen.HISTORY }
    shot(c, out, "09_history")

    c.newGame(DiceSource.RANDOM, Side.ME)
    c.firstThrow()
    waitPlan(c)
    shot(c, out, "10_vs_ai")

    c.screen = Screen.VERIFY
    c.startVerify(6)
    waitFor("verify", 600_000) { !c.verifying }
    shot(c, out, "11_verify")

    val s = c.state!!
    println("verify: ${c.verify.games} games, diff ${c.verify.diff}, plan ${c.verify.avgPlanMs} ms; last game ${s.me.total}:${s.opp.total}")
    c.shutdown()
    System.exit(0)
}

private fun waitPlan(c: AppController) =
    waitFor("plan") { c.plan != null && !c.refining && c.plan!!.me == c.state!!.me }

private fun load(dir: File): Tables {
    val rules = RuleSet.NINTENDO
    fun map(name: String) = RandomAccessFile(File(dir, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules))
}

private fun waitFor(what: String, timeoutMs: Long = 60_000, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
        Thread.sleep(20)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun shot(c: AppController, out: File, name: String, dark: Boolean = false) {
    Thread.sleep(200)
    val density = 2f
    val scene = ImageComposeScene(width = (392 * density).toInt(), height = (850 * density).toInt(), density = Density(density)) {
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

package yacht.uicheck

import androidx.compose.material3.MaterialTheme
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
import java.util.Random

/**
 * Drives the real AppController through a manual-entry game and renders the Compose UI to PNGs.
 * Usage: uicheck <tables dir> <output dir>
 */
fun main(args: Array<String>) {
    val tablesDir = File(args.getOrElse(0) { "../tables" })
    val out = File(args.getOrElse(1) { "build/shots" }).apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val c = AppController(scope, GameStorage(Files.createTempDirectory("yacht").toFile())) { load(tablesDir) }
    c.start()
    waitFor("tables") { c.tables != null }
    shot(c, out, "1_home")

    c.newGame(DiceSource.MANUAL, Side.OPP)
    shot(c, out, "2_opponent_turn")
    c.opponentScore(Category.FULL_HOUSE, 23)
    waitFor("plan") { c.plan != null && !c.refining }
    for (f in listOf(6, 6, 3, 6, 2)) c.enterFace(f)
    shot(c, out, "3_first_roll_advice")
    c.reroll()
    shot(c, out, "4_entering_second_roll")

    // Play on with random dice, following the advice, to reach the end game.
    val rng = Random(5)
    val oppCats = Category.ALL.filter { it != Category.FULL_HOUSE }.shuffled(rng)
    var oppIdx = 0
    var rounds = 0
    while (!c.state!!.isOver) {
        val s = c.state!!
        if (s.toMove == Side.OPP) {
            val cat = oppCats[oppIdx++]
            val pts = s.rules.score(List(5) { rng.nextInt(6) + 1 }, cat)
            c.opponentScore(cat, pts)
            continue
        }
        waitFor("plan") { c.plan != null && !c.refining && c.plan!!.me == c.state!!.me }
        if (s.me.filledCount == 9 && rounds == 0) {
            rounds++
            while (c.state!!.draft.dice.any { it == null }) c.enterFace(rng.nextInt(6) + 1)
            shot(c, out, "5_endgame_exact_search")
        }
        while (c.state!!.draft.dice.any { it == null }) c.enterFace(rng.nextInt(6) + 1)
        val a = c.advice ?: error("no advice")
        if (a.shouldStop) c.score(a.bestCategory.category) else c.reroll()
    }
    shot(c, out, "6_game_over")
    c.openReview(c.state!!)
    shot(c, out, "7_review")
    val s = c.state!!
    println("final ${s.me.total} : ${s.opp.total}, decisions ${s.decisions.size}")
    c.shutdown()
    System.exit(0)
}

private fun load(dir: File): Tables {
    val rules = RuleSet.NINTENDO
    fun map(name: String) = RandomAccessFile(File(dir, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules))
}

private fun waitFor(what: String, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 60_000
    while (!cond()) {
        check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
        Thread.sleep(20)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun shot(c: AppController, out: File, name: String) {
    Thread.sleep(150)
    val density = 2f
    val scene = ImageComposeScene(width = (400 * density).toInt(), height = (860 * density).toInt(), density = Density(density)) {
        MaterialTheme { AppRoot(c) }
    }
    scene.render(0)
    val image = scene.render(1_000_000_000L)
    File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    scene.close()
    println("wrote $name (screen=${c.screen})")
}

@Suppress("unused")
private val keepScreenImport = Screen.HOME

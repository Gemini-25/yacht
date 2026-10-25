package yacht.uicheck

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import yacht.app.core.AiPace
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
    // everyone is placed), then a short run so the season starts; then every tab.
    c.screen = Screen.LEAGUE
    waitFor("league view") { c.league.view != null }
    shot(c, out, "16a_league_empty")
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
    shot(c, out, "16_league_ladder", heightDp = 1500)
    c.league.tab = 1
    shot(c, out, "16b_league_season", heightDp = 1500)
    c.league.tab = 2
    shot(c, out, "16c_league_comps", heightDp = 1500)
    c.league.tab = 3
    shot(c, out, "17_league_feed", heightDp = 1100)
    c.league.tab = 4
    shot(c, out, "18_league_families", heightDp = 1500)
    c.league.tab = 0
    c.league.detail = "win"
    shot(c, out, "19_league_detail", heightDp = 1100)
    c.league.detail = null
    c.league.stop()
    waitFor("league stopped", 60_000) { !c.league.running }
    println("league: ${c.league.view?.matches} matches; top: " + c.league.view?.rows?.take(5)?.joinToString { "${it.name} ${it.rating}" })

    // A ranked game against a league AI near 1400, played greedily to the end.
    c.changeAiPace(AiPace.INSTANT)
    check(c.newRankedGame()) { "no ranked opponent" }
    var shotRanked = false
    while (true) {
        waitFor("my ranked turn", 120_000) { c.state!!.isOver || (c.state!!.toMove == Side.ME && !c.aiThinking) }
        if (c.state!!.isOver) break
        c.firstThrow()
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

    // Watching a league game throw by throw.
    c.changeAiPace(AiPace.NORMAL)
    c.screen = Screen.LEAGUE
    val rival = c.league.rivalOf("win", Random(3)) ?: "ev"
    c.league.startWatch("win", rival) { 300L }
    c.screen = Screen.WATCH
    waitFor("watch", 180_000) { (c.league.watch?.shown ?: 0) >= 8 }
    c.league.pauseWatch()
    shot(c, out, "23_watch", heightDp = 1300)
    waitFor("watch complete", 180_000) { c.league.watch?.complete == true }
    repeat(30) { c.league.skipWatchTurn() }
    shot(c, out, "24_watch_end", heightDp = 1300)
    c.league.stopWatch()

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

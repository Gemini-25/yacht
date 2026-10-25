package yacht.stress

import androidx.compose.runtime.StateWrites
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import yacht.app.core.AiPace
import yacht.app.core.AppController
import yacht.app.core.GameStorage
import yacht.app.core.LeagueSpeed
import yacht.app.core.Screen
import yacht.app.core.ThinkTime
import yacht.engine.Category
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.game.DiceSource
import yacht.engine.game.PlayStyle
import yacht.engine.game.Side
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomBase
import yacht.engine.league.CustomMistake
import yacht.engine.league.LeagueRoster
import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Drives the real AppController the way a person would, for minutes, with random actions
 * (games against the AI, real games, ranked games, the league at full speed, watching, my own
 * AIs, backups, going to the background) on a single "main" thread like Android's, under a
 * phone-sized heap. Reports every uncaught exception (each would close the app on a phone),
 * state written off the main thread, the peak heap, and stuck states.
 * Usage: stress <tables dir> <minutes> [seed]
 */
private val failures = ConcurrentLinkedQueue<String>()
private val offMain = ConcurrentHashMap<String, Int>()
private lateinit var mainThread: Thread
private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "main").also { mainThread = it } }
private val Main = mainExecutor.asCoroutineDispatcher()
@Volatile
private var peakHeap = 0L

fun main(args: Array<String>) {
    val code = try {
        run(args)
    } catch (e: Throwable) {
        e.printStackTrace()
        2
    }
    System.out.flush()
    exitProcess(code)
}

private fun record(where: String, e: Throwable) {
    val text = "$where: ${e.stackTraceToString().lines().take(14).joinToString("\n")}"
    failures += text
    System.err.println("FAILURE $text")
}

private fun run(args: Array<String>): Int {
    val tablesDir = File(args.getOrElse(0) { "../tables" })
    val minutes = args.getOrElse(1) { "5" }.toDouble()
    val seed = args.getOrElse(2) { "1" }.toLong()
    Thread.setDefaultUncaughtExceptionHandler { t, e -> record("thread ${t.name}", e) }
    mainExecutor.submit {}.get()
    StateWrites.observer = {
        if (Thread.currentThread() !== mainThread) {
            val at = Thread.currentThread().stackTrace.firstOrNull { it.className.startsWith("yacht.app") && !it.methodName.startsWith("set") }
                ?: Thread.currentThread().stackTrace.getOrNull(3)
            offMain.merge("${Thread.currentThread().name.substringBefore('-')} @ $at", 1, Int::plus)
        }
    }
    val heap = Thread {
        val mem = ManagementFactory.getMemoryMXBean()
        while (true) {
            peakHeap = maxOf(peakHeap, mem.heapMemoryUsage.used)
            Thread.sleep(200)
        }
    }.apply { isDaemon = true; start() }
    check(heap.isAlive)

    // Watchdog: how long the main thread takes to run an empty task, and what it was doing.
    Thread {
        while (true) {
            val t0 = System.nanoTime()
            val f = mainExecutor.submit {}
            var where: String? = null
            while (!f.isDone) {
                Thread.sleep(5)
                if (where == null && System.nanoTime() - t0 > 300_000_000L) {
                    where = mainThread.stackTrace.filter { it.className.startsWith("yacht.") && !it.className.startsWith("yacht.stress") }
                        .take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                        .ifEmpty { "(stress harness)" }
                }
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > 300 && where != null) blocked.merge(where, ms, ::maxOf)
            Thread.sleep(50)
        }
    }.apply { isDaemon = true; start() }

    val scope = CoroutineScope(SupervisorJob() + Main + CoroutineExceptionHandler { _, e -> record("coroutine", e) })
    val seedFile = File("../android/app/src/main/assets/league_seed.txt")
    val c = AppController(
        scope, GameStorage(Files.createTempDirectory("yacht-stress").toFile()), loadTables = { load(tablesDir) },
        loadAsset = { name -> if (name == "league_seed.txt" && seedFile.exists()) seedFile.readText() else null },
    )
    c.errorListener = { e -> record("caught by the app's safety net", e) }
    onMain { c.start() }
    waitFor("tables", 120_000) { c.tables != null }
    onMain { c.changeThinkTime(ThinkTime.FAST) }
    val rng = Random(seed)
    val deadline = System.currentTimeMillis() + (minutes * 60_000).toLong()
    var games = 0
    var actions = 0
    val leagueHeavy = rng.nextBoolean()
    println("stress: $minutes min, seed $seed, cores ${Runtime.getRuntime().availableProcessors()}, max heap ${Runtime.getRuntime().maxMemory() shr 20} MB")

    // The league from the seed, at full speed, in the background of everything else.
    onMain { c.screen = Screen.LEAGUE }
    waitFor("league view", 60_000) { c.league.view != null }
    onMain { c.league.loadSeed() }
    waitFor("seed", 60_000) { (c.league.view?.matches ?: 0L) > 0L }
    onMain {
        c.league.changeSpeed(if (leagueHeavy) LeagueSpeed.FULL else LeagueSpeed.NORMAL)
        c.league.start()
    }

    while (System.currentTimeMillis() < deadline && failures.isEmpty()) {
        actions++
        when (rng.nextInt(12)) {
            0, 1, 2 -> { playGame(c, rng, DiceSource.RANDOM); games++ }
            3 -> { playGame(c, rng, DiceSource.MANUAL); games++ }
            4, 5 -> {
                if (onMain { c.newRankedGame() }) { playGame(c, rng, null); games++ }
            }
            6 -> watch(c, rng)
            7 -> myAis(c, rng)
            8 -> {
                // Background and back, as when the phone is locked.
                onMain { c.onBackground() }
                Thread.sleep(rng.nextInt(1500).toLong())
                onMain {
                    c.onForeground()
                    if (rng.nextBoolean()) c.league.start()
                }
            }
            9 -> {
                var built: ByteArray? = null
                onMain { c.backup { _, b -> built = b } }
                waitFor("backup", 60_000) { built != null }
                var note: String? = null
                onMain { c.restore(built!!) { note = it } }
                waitFor("restore", 60_000) { note != null }
            }
            10 -> {
                // The lab now and then: an experiment between two AIs, sometimes stopped early.
                val keys = c.league.view?.rows?.filter { !it.isMe }?.map { it.key }.orEmpty()
                if (keys.size >= 2 && rng.nextBoolean()) {
                    val a = keys[rng.nextInt(keys.size)]
                    val b = keys.filter { it != a }[rng.nextInt(keys.size - 1)]
                    onMain { c.league.startLab(a, b, if (rng.nextBoolean()) 200 else 0) }
                    if (rng.nextInt(3) == 0) onMain { c.league.stopLab() }
                }
                // A preview and a scouting report off the main thread.
                if (keys.size >= 2) onMain { c.league.loadPreview(keys[0], keys[1]) }
                onMain { c.league.changeSpeed(LeagueSpeed.entries[rng.nextInt(LeagueSpeed.entries.size)]) }
                if (rng.nextInt(3) == 0) onMain { c.league.stop() }
                Thread.sleep(300)
                onMain { c.league.start() }
            }
            else -> review(c, rng)
        }
        if (actions % 5 == 0) {
            val v = c.league.view
            println(
                "  ${(deadline - System.currentTimeMillis()) / 1000}s left · $games games · league ${v?.matches} matches " +
                    "(season ${v?.season?.number}: ${v?.season?.played}/${v?.season?.total}) · running ${c.league.running} ${c.league.preparing ?: ""} · heap peak ${peakHeap shr 20} MB",
            )
        }
    }
    onMain { c.league.stop() }
    waitFor("league stop", 120_000) { !c.league.running }
    onMain { c.shutdown() }
    println("done: $actions actions, $games games, league ${c.league.view?.matches} matches, peak heap ${peakHeap shr 20} MB of ${Runtime.getRuntime().maxMemory() shr 20} MB")
    if (offMain.isNotEmpty()) {
        println("state written off the main thread:")
        for ((k, n) in offMain.entries.sortedByDescending { it.value }) println("  $n× $k")
    }
    if (slowMain.isNotEmpty()) {
        println("main thread held over 100 ms by an action (ms):")
        for ((k, ms) in slowMain.entries.sortedByDescending { it.value }) println("  $ms ms · $k")
    }
    if (blocked.isNotEmpty()) {
        println("main thread blocked (watchdog, ms):")
        for ((k, ms) in blocked.entries.sortedByDescending { it.value }.take(15)) println("  $ms ms · $k")
    }
    println(if (failures.isEmpty()) "NO FAILURES" else "${failures.size} FAILURES")
    return if (failures.isEmpty()) 0 else 1
}

/** One game to the end (or abandoned), with random detours. [source] null: the ranked game already started. */
private fun playGame(c: AppController, rng: Random, source: DiceSource?) {
    if (source != null) {
        onMain {
            c.changeStyle(if (rng.nextInt(3) == 0) PlayStyle.SOLO else PlayStyle.ASSISTED)
            c.changeAiPace(AiPace.entries[rng.nextInt(AiPace.entries.size)])
            c.newGame(source, if (rng.nextBoolean()) Side.ME else Side.OPP, if (source == DiceSource.MANUAL) "친구 ${rng.nextInt(3)}" else null)
        }
    } else {
        onMain { c.changeAiPace(AiPace.entries[rng.nextInt(AiPace.entries.size)]) }
    }
    val abandonAt = if (rng.nextInt(6) == 0) rng.nextInt(20) else Int.MAX_VALUE
    var steps = 0
    var aiWaitSince = 0L
    var undoAt = Long.MAX_VALUE
    val start = System.currentTimeMillis()
    while (true) {
        val s = c.state ?: return
        if (s.isOver) break
        if (steps++ == abandonAt) return
        check(System.currentTimeMillis() - start < 600_000) { "game stuck: $s" }
        if (s.source == DiceSource.RANDOM && s.toMove == Side.OPP) {
            // The AI's turn (with its playback): sometimes skip, sometimes undo my box before it shows.
            if (aiWaitSince == 0L) {
                aiWaitSince = System.currentTimeMillis()
                // Now and then a person takes back the box just written, once, before the AI's dice show.
                undoAt = if (rng.nextInt(8) == 0) aiWaitSince + rng.nextInt(1500) else Long.MAX_VALUE
            }
            if (System.currentTimeMillis() - aiWaitSince > 60_000) {
                record(
                    "AI never moved",
                    IllegalStateException(
                        "aiThinking=${c.aiThinking} aiSteps=${c.aiSteps?.size} shown=${c.aiShown} canUndo=${c.canUndo} screen=${c.screen} " +
                            "ranked=${s.rankedAi} turn=${s.myTurnNumber} last actions=${synchronized(lastActions) { lastActions.joinToString(" ") }}",
                    ),
                )
                return
            }
            if (System.currentTimeMillis() >= undoAt) {
                undoAt = Long.MAX_VALUE
                act("undo") { c.undo() }
            }
            if (rng.nextInt(3) == 0) act("skip") { c.skipAiPlayback() }
            Thread.sleep(50)
            continue
        }
        aiWaitSince = 0L
        if (c.aiSteps != null && c.aiShown < (c.aiSteps?.size ?: 0)) {
            act("skip2") { c.skipAiPlayback() }
            Thread.sleep(20)
            continue
        }
        if (s.toMove == Side.OPP) {
            // A real game: type the opponent's box.
            val open = Category.ALL.filter { !s.opp.isFilled(it) }
            val cat = open[rng.nextInt(open.size)]
            act("oppScore") { c.opponentScore(cat, s.rules.score(List(5) { rng.nextInt(6) + 1 }, cat)) }
            continue
        }
        detour(c, rng)
        val d = c.state ?: return
        if (d.isOver || d.toMove != Side.ME) continue
        if (d.draft.dice.any { it == null }) {
            if (d.source == DiceSource.RANDOM) act("throw") { c.firstThrow() } else act("face") { c.enterFace(rng.nextInt(6) + 1) }
            continue
        }
        // Mostly the advice when it is there (wait a little for it), else something random.
        if (d.style == PlayStyle.ASSISTED || d.style == PlayStyle.PEEKED) waitFor("plan", 30_000, quiet = true) { c.plan != null || c.state != d }
        if (c.state != d) continue
        val a = c.advice
        val open = Category.ALL.filter { !d.me.isFilled(it) }
        when {
            d.draft.rollsDone < 3 && a != null && !a.shouldStop && rng.nextInt(4) != 0 -> act("holdReroll") { c.holdRecommended(); c.reroll() }
            d.draft.rollsDone < 3 && rng.nextInt(3) == 0 -> act("randomReroll") {
                repeat(rng.nextInt(3)) { c.toggleHold(rng.nextInt(5)) }
                c.reroll()
            }
            a != null && rng.nextInt(4) != 0 -> act("scoreBest") { c.score(a.bestCategory.category) }
            else -> act("scoreRandom") { c.score(open[rng.nextInt(open.size)]) }
        }
        if (rng.nextInt(15) == 0) act("undoMine") { c.undo() }
    }
    if (rng.nextBoolean()) {
        onMain { c.openReview(c.state!!) }
        Thread.sleep(200)
    }
}

/** What a person does on the side mid-game: peek, open screens, go away and come back. */
private fun detour(c: AppController, rng: Random) {
    when (rng.nextInt(40)) {
        0 -> act("peek") { c.peek() }
        1 -> act("why") { c.toggleWhy() }
        2 -> {
            act("toLeague") { c.screen = Screen.LEAGUE }
            Thread.sleep(100)
            act("toGame") { c.screen = Screen.GAME }
        }
        3 -> {
            act("background") { c.onBackground() }
            Thread.sleep(200)
            act("foreground") { c.onForeground() }
        }
        4 -> act("objective") { c.changeObjective(if (rng.nextBoolean()) yacht.engine.Mode.WIN else yacht.engine.Mode.EXPECTED_SCORE) }
    }
}

private fun watch(c: AppController, rng: Random) {
    val pair = onMain { c.league.featured(Random(rng.nextLong())) } ?: return
    onMain {
        c.league.startWatch(pair.first, pair.second) { 30L }
        c.screen = Screen.WATCH
    }
    val until = System.currentTimeMillis() + rng.nextInt(20_000)
    while (System.currentTimeMillis() < until) {
        when (rng.nextInt(6)) {
            0 -> onMain { c.league.pauseWatch() }
            1 -> onMain { c.league.resumeWatch { 30L } }
            2 -> onMain { c.league.skipWatchTurn() }
        }
        Thread.sleep(300)
        if (c.league.watch?.complete == true && rng.nextBoolean()) break
    }
    onMain {
        if (rng.nextBoolean()) c.league.stopWatch()
        c.screen = Screen.LEAGUE
    }
}

private fun myAis(c: AppController, rng: Random) {
    val lg = c.league
    if (lg.customs.size > 3 || rng.nextInt(4) == 0) {
        lg.customs.randomOrNull()?.let { s -> onMain { lg.deleteCustom(s.id) } }
        return
    }
    val base = CustomBase.entries[rng.nextInt(CustomBase.entries.size)]
    val spec = CustomAiSpec(
        "s${rng.nextInt(1000)}", "테스트 ${rng.nextInt(100)}", base, LeagueRoster.THETAS[rng.nextInt(LeagueRoster.THETAS.size)],
        CustomMistake.entries[rng.nextInt(3)], rng.nextDouble() * 0.5, yacht = rng.nextDouble() * 40 - 10, zero = rng.nextDouble() * 10,
        saveChoice = rng.nextBoolean(), future = 0.5 + rng.nextDouble(),
    )
    onMain { lg.saveCustom(spec) }
    // Edit it right away sometimes (its results are dropped).
    if (rng.nextBoolean()) onMain { lg.saveCustom(spec.copy(amount = rng.nextDouble() * 0.3)) }
    if (rng.nextInt(3) == 0) {
        onMain { lg.detail = spec.key }
        Thread.sleep(100)
        onMain { lg.detail = null }
    }
}

private fun review(c: AppController, rng: Random) {
    onMain { c.openHistory() }
    Thread.sleep(300)
    val g = c.history.randomOrNull() ?: return
    onMain { c.openReview(g) }
    g.decisions.filter { it.isMistake }.randomOrNull()?.let { d ->
        onMain { c.loadWhatIf(d) }
        waitFor("what-if", 120_000, quiet = true) { d in c.whatIfs || d !in c.whatIfBusy }
    }
    onMain { c.luck(g); c.gameSummary(g) }
    if (rng.nextInt(3) == 0) {
        onMain { c.openQuiz() }
        waitFor("quiz", 120_000, quiet = true) { c.quiz != null }
        val q = c.quiz ?: return
        val open = Category.ALL.filter { !q.me.isFilled(it) }
        onMain { c.quizScore(open[rng.nextInt(open.size)]) }
        onMain { c.closeQuiz() }
    }
    onMain { c.screen = Screen.HOME }
}

/** Longest time each action held the main thread (over 1 s on this server is an ANR risk on a phone). */
private val slowMain = ConcurrentHashMap<String, Long>()

/** Main thread blocked over 300 ms, by what it was running. */
private val blocked = ConcurrentHashMap<String, Long>()

private fun <T> onMain(block: () -> T): T = runBlocking(Main) {
    val t0 = System.nanoTime()
    val r = block()
    val ms = (System.nanoTime() - t0) / 1_000_000
    if (ms > 100) {
        val name = synchronized(lastActions) { lastActions.lastOrNull() } ?: "?"
        slowMain.merge(name, ms, ::maxOf)
    }
    r
}

/** The last actions, for a failure report. */
private val lastActions = ArrayDeque<String>()

private fun <T> act(name: String, block: () -> T): T {
    synchronized(lastActions) {
        lastActions.addLast(name)
        if (lastActions.size > 25) lastActions.removeFirst()
    }
    return onMain(block)
}

private fun waitFor(what: String, timeoutMs: Long, quiet: Boolean = false, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!cond()) {
        if (System.currentTimeMillis() > deadline) {
            if (quiet) return
            error("timed out waiting for $what")
        }
        Thread.sleep(20)
    }
}

private fun load(dir: File): Tables {
    val rules = RuleSet.NINTENDO
    fun map(name: String) = RandomAccessFile(File(dir, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    val bonus = File(dir, "bonus.bin").takeIf { it.exists() }?.let { TableIO.readBonus(map(it.name), rules) }
    return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules), bonus = bonus)
}

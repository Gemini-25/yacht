package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import yacht.app.core.AppController
import yacht.app.core.GameStorage
import yacht.app.core.LeagueRunMode
import yacht.app.core.LeagueSpeed
import yacht.app.core.Screen
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.league.PlayerMaker
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Runs the app's real league controller (no UI) and says how it goes, to catch a league that
 * stops or stalls by itself.
 *
 * Usage: runcheck <tables dir> <minutes> [ECO|NORMAL|FULL] [seed|new] [make|week]
 * - every 5 s: running, matches, season and week, matches in flight, why it stopped;
 * - `seed` starts from the shipped seed league; `make` adds three made players while it runs;
 * - `week` presses ▶ in 1주씩 sixty times (each run must stop by itself one week on, with
 *   nothing left over), then plays on in 계속; `week lab` the same with an AI lab experiment
 *   running alongside (its games must not cut a week short).
 * Exits 1 when a check fails.
 */
fun main(args: Array<String>) {
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
    val minutes = args.getOrElse(1) { "5" }.toDouble()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val c = AppController(
        scope, GameStorage(Files.createTempDirectory("yacht").toFile()), loadTables = { load(tablesDir) },
        loadAsset = { name -> File("android/app/src/main/assets", name).takeIf { it.exists() }?.readText() },
    )
    c.start()
    while (c.tables == null) Thread.sleep(50)
    c.screen = Screen.LEAGUE
    while (c.league.view == null) Thread.sleep(50)
    c.league.changeSpeed(LeagueSpeed.valueOf(args.getOrElse(2) { "FULL" }))
    if (args.getOrElse(3) { "" } == "seed" && c.league.hasSeed) {
        c.league.loadSeed()
        while ((c.league.view?.matches ?: 0L) == 0L) Thread.sleep(50)
    }
    when (args.getOrElse(4) { "" }) {
        "week" -> weeks(c, lab = args.getOrElse(5) { "" } == "lab")
        else -> watch(c, minutes, make = args.getOrElse(4) { "" } == "make")
    }
}

/** 계속: plays [minutes] and fails when the league stops or stalls by itself. */
private fun watch(c: AppController, minutes: Double, make: Boolean) {
    val lg = c.league
    lg.start()
    val t0 = System.currentTimeMillis()
    val end = t0 + (minutes * 60_000).toLong()
    var lastMatches = -1L
    var still = 0
    var lastCrash: String? = null
    var made = 0
    while (System.currentTimeMillis() < end) {
        Thread.sleep(5_000)
        if (make && made < 3 && System.currentTimeMillis() - t0 > 15_000L * (made + 1)) {
            val d = PlayerMaker.random(java.util.Random(made.toLong()), emptySet()).copy(name = "만든 선수 $made", keep = made == 1)
            println("made: " + (lg.savePlayer(null, d) ?: "ok"))
            made++
        }
        val v = lg.view
        val ck = v?.clock
        val m = v?.matches ?: 0
        println(
            "t=${(System.currentTimeMillis() - t0) / 1000}s running=${lg.running} matches=$m " +
                "season=${ck?.season} week=${ck?.week}/${ck?.leagueWeeks} live=${v?.live?.size} stop=${lg.stopReason} " +
                "now=${v?.live?.take(4)?.joinToString { "${it.a}-${it.b}/${it.comp}/w${it.week}" }}",
        )
        c.lastCrash?.let { if (it != lastCrash) println("CRASH: " + it.take(3000)) }
        lastCrash = c.lastCrash
        still = if (m == lastMatches) still + 1 else 0
        lastMatches = m
        check(lg.running) { "the league stopped by itself: ${lg.stopReason}" }
        check(still < 6) { "no match for 30 s" }
    }
    lg.stop()
}

/** 1주씩: ▶ again and again; each run must stop by itself one week further on. */
private fun weeks(c: AppController, lab: Boolean) {
    val lg = c.league
    lg.changeRunMode(LeagueRunMode.WEEK)
    Thread.sleep(200)
    if (lab) {
        // Two league players, a long experiment: it plays alongside every week.
        val (a, b) = lg.view!!.rows.filter { it.key.startsWith("g") }.take(2).map { it.key }
        lg.startLab(a, b, 1_000_000)
        while (!lg.running) Thread.sleep(10)
        while (lg.running) Thread.sleep(20)
        Thread.sleep(400)
        println("lab week: ${lg.weekDone} late=${lg.view?.clock?.late?.size}")
        check(lg.view?.clock?.late.orEmpty().isEmpty()) { "the lab's first week left stages" }
    }
    for (i in 1..60) {
        val before = lg.view?.clock?.let { it.season to it.week }
        val t0 = System.currentTimeMillis()
        lg.start()
        while (!lg.running) Thread.sleep(10)
        var told = 0L
        while (lg.running) {
            Thread.sleep(20)
            val el = System.currentTimeMillis() - t0
            if (el > 30_000 && el - told > 10_000) {
                told = el
                val ck = lg.view?.clock
                println(
                    "STUCK ${el}ms: week=${ck?.week} live=${lg.view?.live?.joinToString { "${it.a}-${it.b}/${it.comp}/w${it.week}" }} " +
                        "row=${ck?.row(ck.week)?.stages?.joinToString { "${it.comp}/${it.label} ${it.played}/${it.total}" }}",
                )
            }
            check(el < 120_000) { "week $i did not stop" }
        }
        Thread.sleep(400)
        val ck = lg.view?.clock
        val after = ck?.let { it.season to it.week }
        val late = ck?.late.orEmpty()
        println("run $i: $before -> $after in ${System.currentTimeMillis() - t0}ms: ${lg.weekDone}")
        check(lg.stopReason == null) { "stopped with ${lg.stopReason}" }
        check(lg.weekDone != null) { "no note" }
        check(after != before) { "the week did not move" }
        check(late.isEmpty()) { "left over: " + late.joinToString { "${it.comp}/${it.label}/w${it.week} ${it.played}/${it.total}" } }
    }
    // 계속 from here: it plays on past the week.
    val from = lg.view?.clock?.let { it.season to it.week }
    lg.changeRunMode(LeagueRunMode.ON)
    lg.start()
    Thread.sleep(20_000)
    println("계속: $from -> ${lg.view?.clock?.let { it.season to it.week }}")
    check(lg.running) { "계속 stopped: ${lg.stopReason}" }
    lg.stop()
}

private fun load(dir: File): Tables {
    val rules = RuleSet.NINTENDO
    fun map(name: String) = RandomAccessFile(File(dir, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    val bonus = File(dir, "bonus.bin").takeIf { it.exists() }?.let { TableIO.readBonus(map(it.name), rules) }
    return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules), bonus = bonus)
}

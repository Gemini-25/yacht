package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import yacht.app.core.AppController
import yacht.app.core.GameStorage
import yacht.app.core.LeagueRunMode
import yacht.app.core.LeagueSpeed
import yacht.app.core.MatchCenter
import yacht.app.core.Screen
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.league.Comp
import yacht.engine.league.LeagueConfig
import yacht.engine.league.PlayerMaker
import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Runs the app's real league controller (no UI) and says how it goes, to catch a league that
 * stops or stalls by itself.
 *
 * Usage: runcheck <tables dir> <minutes> [ECO|NORMAL|FULL] [seed|new] [make|week|size|national]
 * - every 5 s: running, matches, season and week, matches in flight, why it stopped;
 * - `seed` starts from the shipped seed league; `make` adds three made players while it runs;
 * - `week` presses ▶ in 1주씩 sixty times (each run must stop by itself one week on, with
 *   nothing left over), then plays on in 계속; `week lab` the same with an AI lab experiment
 *   running alongside (its games must not cut a week short).
 * - `size [record|check] [players...]` (minutes and seed are not read): new leagues of 200 and
 *   1000 players (or the ones given) from a fixed seed play [SIZE_SEASONS] seasons; it prints
 *   every file's bytes after each season, the CPU seconds each season took and a reference
 *   workload's (the machine's speed). `record` writes them to
 *   uicheck/src/main/resources/size_baseline.txt (run from the repository); `check` fails when the
 *   folder, its 30-season projection or the CPU per season (by more than 5%, in seconds and per
 *   reference) is above that baseline (beyond the run-to-run noise, see [SIZE_MARGIN]).
 * - `national [shots dir|-] [players]` (minutes and seed are not read): a new league of national leagues
 *   ([NATIONAL_CONFIG]) plays one season and the rollover; it fails on any error the app is told
 *   of (every snapshot included), and draws every league's 순위 › 리그 in every view halfway
 *   through the season and again in the next one (into the shots dir, else a temporary one),
 *   each checked against its selector row.
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
    if (args.getOrElse(4) { "" } == "size") return sizes(tablesDir, LeagueSpeed.valueOf(args.getOrElse(2) { "FULL" }), args.drop(5))
    if (args.getOrElse(4) { "" } == "national") {
        return national(tablesDir, LeagueSpeed.valueOf(args.getOrElse(2) { "FULL" }), args.getOrNull(5)?.takeIf { it != "-" }?.let(::File), args.getOrNull(6)?.toInt() ?: NATIONAL_PLAYERS)
    }
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

// ---- size: the league folder and CPU per season ---------------------------------------------------

/** Seasons a size run plays, the seed of its league (the same league each time), and the most it may take. */
private const val SIZE_SEASONS = 3
private const val SIZE_SEED = 20261030L
private const val SIZE_MINUTES = 60L
private const val BASELINE = "size_baseline.txt"
private val BASELINE_SOURCE = File("uicheck/src/main/resources", BASELINE)

/** The files that grow every season and keep growing (the projection's 30 seasons). */
private val GROWING = listOf("archive.txt", "honours.txt", MatchCenter.LOGS)

/**
 * How far above the baseline a run may be and pass. The same tree measured again differed by up
 * to 0.5% in the folder and 2% in the projection (30 times one season's growth), so a strict
 * comparison fails by chance; the CPU per season differed by 1% at 1000 players but by up to 10%
 * at 200 (its share of placement friendlies varies), which the 5% margin does not cover. On the
 * shared build server the same run also took up to 45% more CPU seconds an hour later, so CPU
 * fails only when it is above both in seconds and in units of [referenceCpu] measured alongside
 * (whose own noise was up to 10%).
 */
private const val SIZE_MARGIN = 1.01
private const val PROJECTION_MARGIN = 1.03
private const val CPU_MARGIN = 1.05

/**
 * One measured league: [files] its folder after each season (bytes by name), [cpu] the CPU
 * seconds each season took, [ref] the machine's [referenceCpu] then (0: not measured).
 */
private data class SizeRun(val players: Int, val files: List<Map<String, Long>>, val cpu: List<Double>, val ref: Double = 0.0) {
    fun total(seasons: Int = files.size): Long = files[seasons - 1].values.sum()

    /** The folder after its seasons plus 30 more seasons of the last one's growth of [GROWING]. */
    val projected: Long
        get() = total() + 30 * GROWING.sumOf { (files.last()[it] ?: 0L) - (files.getOrNull(files.size - 2)?.get(it) ?: 0L) }

    val cpuPerSeason: Double get() = cpu.average()

    /** [cpuPerSeason] in units of [ref] (null without it). */
    val cpuRelative: Double? get() = if (ref > 0) cpuPerSeason / ref else null

    fun encode(): String = buildString {
        append("league $players\n")
        for ((i, f) in files.withIndex()) append("files ${i + 1} " + f.entries.joinToString(" ") { "${it.key}=${it.value}" } + "\n")
        for (i in files.indices) append("total ${i + 1} ${total(i + 1)}\n")
        append("projected $projected\n")
        for ((i, x) in cpu.withIndex()) append("cpu ${i + 1} ${"%.2f".format(java.util.Locale.ROOT, x)}\n")
        if (ref > 0) append("ref ${"%.2f".format(java.util.Locale.ROOT, ref)}\n")
    }

    companion object {
        fun decode(text: String): List<SizeRun> {
            val runs = ArrayList<SizeRun>()
            var players = -1
            val files = ArrayList<Map<String, Long>>()
            val cpu = ArrayList<Double>()
            var ref = 0.0
            fun flush() {
                if (players >= 0) runs += SizeRun(players, files.toList(), cpu.toList(), ref)
                files.clear()
                cpu.clear()
                ref = 0.0
            }
            for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
                val f = line.split(' ')
                when (f[0]) {
                    "league" -> {
                        flush()
                        players = f[1].toInt()
                    }
                    "files" -> files += f.drop(2).associate { kv -> kv.substringBefore('=') to kv.substringAfter('=').toLong() }
                    "cpu" -> cpu += f[2].toDouble()
                    "ref" -> ref = f[1].toDouble()
                }
            }
            flush()
            return runs
        }
    }
}

/** `size`: measures each league, then prints, records or checks against the baseline. */
private fun sizes(tablesDir: File, speed: LeagueSpeed, args: List<String>) {
    val mode = args.firstOrNull()?.takeIf { it == "record" || it == "check" }
    val players = args.drop(if (mode == null) 0 else 1).map { it.toInt() }.ifEmpty { listOf(200, 1000) }
    var cache: File? = null
    val dirs = ArrayList<File>()
    val runs = players.map { n ->
        val (run, folder) = measure(tablesDir, speed, n, cache)
        cache = folder
        dirs += folder.parentFile
        println(run.encode())
        run
    }
    for (d in dirs) d.deleteRecursively()
    when (mode) {
        "record" -> {
            BASELINE_SOURCE.parentFile.mkdirs()
            BASELINE_SOURCE.writeText(
                "# RunCheck size: new leagues under the app's default rules, from the seed $SIZE_SEED, at $speed, $SIZE_SEASONS seasons each.\n" +
                    "# files n: every file of the league folder after season n's close and its saves, in bytes (the hand-made AIs' table caches, *.bin, not counted).\n" +
                    "# total n: their sum; projected: the total after the last season plus 30 seasons of its growth of ${GROWING.joinToString()}.\n" +
                    "# cpu n: the CPU seconds of the whole process in season n, from its start to the stop right after its close (the first from its first match on).\n" +
                    "# ref: the CPU seconds of a fixed workload on as many threads (the machine's speed: the median of before, between the seasons and after).\n" +
                    runs.joinToString("") { it.encode() },
            )
            println("recorded $BASELINE_SOURCE")
        }
        "check" -> check(runs)
    }
}

/** Fails when a measured league is above the baseline (its folder, the 30-season projection, or the CPU per season by more than 5% in seconds and per reference) beyond the noise. */
private fun check(runs: List<SizeRun>) {
    // The source file when run from the repository (a baseline just recorded), else the one built in.
    val text = BASELINE_SOURCE.takeIf { it.exists() }?.readText() ?: object {}.javaClass.getResource("/$BASELINE")?.readText() ?: error("no $BASELINE")
    val baseline = SizeRun.decode(text).associateBy { it.players }
    val failed = ArrayList<String>()
    for (r in runs) {
        val b = baseline[r.players] ?: error("no baseline for ${r.players} players")
        println("${r.players} players, after ${r.files.size} seasons (baseline -> now):")
        for (name in (b.files.last().keys + r.files.last().keys).sorted()) {
            val was = b.files.last()[name] ?: 0L
            val now = r.files.last()[name] ?: 0L
            println("  %-16s %,10d -> %,10d (%+,d)".format(name, was, now, now - was))
        }
        println("  %-16s %,10d -> %,10d (%+,d)".format("total", b.total(), r.total(), r.total() - b.total()))
        println("  %-16s %,10d -> %,10d (%+,d)".format("30 seasons", b.projected, r.projected, r.projected - b.projected))
        println("  %-16s %10.1f -> %10.1f s".format("CPU a season", b.cpuPerSeason, r.cpuPerSeason))
        val was = b.cpuRelative
        val now = r.cpuRelative
        if (was != null && now != null) println("  %-16s %10.2f -> %10.2f (ref %.2f -> %.2f s)".format("CPU per ref", was, now, b.ref, r.ref))
        if (r.total() > b.total() * SIZE_MARGIN) failed += "${r.players} players: folder ${r.total()} > ${b.total()} + 1%"
        if (r.projected > b.projected * PROJECTION_MARGIN) failed += "${r.players} players: 30 seasons ${r.projected} > ${b.projected} + 3%"
        // Above in seconds and, when both have it, per reference too: a slower machine raises only
        // the first, the reference's own noise only the second, more work both.
        if (r.cpuPerSeason > b.cpuPerSeason * CPU_MARGIN && (was == null || now == null || now > was * CPU_MARGIN)) {
            failed += "${r.players} players: CPU a season ${"%.1f".format(r.cpuPerSeason)} > ${"%.1f".format(b.cpuPerSeason)} + 5%" +
                if (was != null && now != null) " (per ref ${"%.2f".format(now)} > ${"%.2f".format(was)} + 5%)" else ""
        }
    }
    check(failed.isEmpty()) { failed.joinToString("; ") }
    println("size: within the baseline")
}

/** The whole process's CPU seconds (every thread). */
private fun cpuSeconds(): Double = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime / 1e9

/** Random reads a [referenceCpu] thread makes, and its table's size (64 MB, beyond the caches as the AIs' tables are). */
private const val REF_READS = 40_000_000
private const val REF_TABLE = 1 shl 24

/**
 * The CPU seconds a fixed workload takes on [threads] threads: random reads in a 64 MB table, as
 * the AIs' table lookups are, measured with nothing else running. It does not use the engine, so
 * it stays the same whatever the stages change: it tells how fast the machine is right now.
 */
private fun referenceCpu(threads: Int): Double {
    val table = IntArray(REF_TABLE) { it * -0x61c88647 }
    val sums = LongArray(threads)
    val c0 = cpuSeconds()
    val workers = (0 until threads).map { t ->
        Thread {
            var x = t + 1L
            var sum = 0L
            repeat(REF_READS) {
                x = x * 6364136223846793005L + 1442695040888963407L
                sum += table[(x ushr 40).toInt() and (REF_TABLE - 1)]
            }
            sums[t] = sum
        }
    }
    workers.forEach { it.start() }
    workers.forEach { it.join() }
    val cpu = cpuSeconds() - c0
    check(sums.all { it != 0L })
    return cpu
}

/** The league folder's files, by name, without the table caches and unfinished saves. */
private fun folderSizes(folder: File): Map<String, Long> =
    folder.listFiles().orEmpty().filter { f -> f.isFile && !f.name.startsWith(".") && (!f.name.endsWith(".bin") || f.name == MatchCenter.LOGS) }
        .sortedBy { it.name }.associate { it.name to it.length() }

/** [folder]'s files (as [folderSizes]) once its saves have landed: none under way and nothing changed for two seconds. */
private fun settled(folder: File): Map<String, Long> {
    fun state() = folder.listFiles().orEmpty().map { Triple(it.name, it.length(), it.lastModified()) }.sortedBy { it.first }
    val end = System.currentTimeMillis() + 60_000
    var last = state()
    var still = 0
    while (still < 2) {
        Thread.sleep(1_000)
        val now = state()
        still = if (now == last && now.none { it.first.startsWith(".") && it.first.endsWith(".tmp") }) still + 1 else 0
        check(System.currentTimeMillis() < end) { "the league's files did not settle" }
        last = now
    }
    return folderSizes(folder)
}

/**
 * A new league of [players] (and the hand-made AIs, which sit out by default) under the app's
 * default rules, from [SIZE_SEED], played [SIZE_SEASONS] seasons at [speed] by the real
 * controller; [cache] a folder whose table caches it starts with (they are built at the first
 * start). Returns the run and its folder.
 */
private fun measure(tablesDir: File, speed: LeagueSpeed, players: Int, cache: File?): Pair<SizeRun, File> {
    val threads = speed.threads(Runtime.getRuntime().availableProcessors())
    // The machine's speed before, between the seasons and after (the median of them).
    val refs = arrayListOf(referenceCpu(threads))
    val dir = Files.createTempDirectory("yacht-size").toFile()
    val folder = File(dir, "league").apply { mkdirs() }
    cache?.listFiles()?.filter { it.name.endsWith(".bin") && it.name != MatchCenter.LOGS }?.forEach { it.copyTo(File(folder, it.name)) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // No seed ladder: a league from zero.
    val c = AppController(scope, GameStorage(dir), loadTables = { load(tablesDir) }, loadAsset = { null })
    val lg = c.league
    // The rules a new league gets (read before the league is), with this many players.
    File(folder, "config.txt").writeText(lg.config.copy(generated = players).encode())
    lg.leagueSeed = { SIZE_SEED }
    c.start()
    while (c.tables == null) Thread.sleep(50)
    c.screen = Screen.LEAGUE
    while (lg.view == null) Thread.sleep(50)
    lg.changeSpeed(speed)
    lg.start()
    // The tables of the AIs' rule beliefs come first (built once, then cached): not counted.
    while (!(lg.running && lg.preparing == null && lg.view?.clock != null)) {
        check(lg.stopReason == null) { "stopped: ${lg.stopReason}" }
        Thread.sleep(50)
    }
    val deadline = System.currentTimeMillis() + SIZE_MINUTES * 60_000
    fun waitUntil(what: String, cond: () -> Boolean) {
        while (!cond()) {
            check(lg.stopReason == null) { "$players players: stopped: ${lg.stopReason}" }
            check(System.currentTimeMillis() < deadline) { "$players players: $what, not in $SIZE_MINUTES minutes" }
            Thread.sleep(50)
        }
    }
    val files = ArrayList<Map<String, Long>>()
    val cpu = ArrayList<Double>()
    // Season by season: the run stops by itself right after the season's close, nothing of the
    // next season played, and the folder is read once that close's saves have landed (read while
    // the league plays on, it could still hold the finished season's save); then the next starts.
    for (season in 1..SIZE_SEASONS) {
        val t0 = System.currentTimeMillis()
        val cpu0 = cpuSeconds()
        val m0 = lg.view?.matches ?: 0L
        if (season > 1) lg.start()
        // The run sets its own week goal before its first match: moved only after that.
        waitUntil("season $season started") { lg.running && (lg.view?.matches ?: 0L) > m0 }
        lg.stopAfterSeason(season)
        waitUntil("season $season played") { !lg.running }
        cpu += cpuSeconds() - cpu0
        waitUntil("season $season closed") { (lg.view?.clock?.season ?: 0) > season }
        files += settled(folder)
        refs += referenceCpu(threads)
        println("$players players: season $season in ${(System.currentTimeMillis() - t0) / 1000} s, CPU ${"%.1f".format(cpu.last())} s, folder ${files.last().values.sum()} B")
    }
    c.shutdown()?.let { j -> kotlinx.coroutines.runBlocking { j.join() } }
    scope.cancel()
    refs += referenceCpu(threads)
    val ref = refs.sorted()[refs.size / 2]
    println("$players players: reference ${refs.joinToString(" ") { "%.2f".format(it) }} s")
    return SizeRun(players, files, cpu, ref) to folder
}

// ---- national: a league of national leagues end to end -----------------------------------------

/**
 * The rules of a `national` run: the national default, scaled to 300 players (as NationalSmokeTest:
 * its leagues are the four 연합 리그) or to the players asked for (1000 makes some nations' own
 * leagues and 대륙 챔피언스 sections).
 */
private fun nationalConfig(players: Int) = LeagueConfig.DEFAULT.copy(generated = players, leagueMin = 20, divisionSize = 16)
private const val NATIONAL_PLAYERS = 300
private const val NATIONAL_SEED = 20261031L
private const val NATIONAL_MINUTES = 40L

/**
 * `national`: a new league under [nationalConfig] (written as its saved rules, as a player who
 * picked them would have) from [NATIONAL_SEED], played at [speed] by the real controller through
 * its first season and the rollover. Every error the app is told of fails the run (a snapshot
 * that throws, a failing odds run, a stop). Halfway through season 1, and again once season 2 is
 * open, every league's 순위 › 리그 is drawn in every view ([ShotsWorld.everyLeague]) and the
 * screens a national season changes are shot ([ShotsWorld.nationalShots]); in season 1's
 * post-season the title playoffs' page and stage sheet ([ShotsWorld.competitionShots], "nat1po");
 * all into [shots] (a temporary folder, deleted, when null).
 */
private fun national(tablesDir: File, speed: LeagueSpeed, shots: File?, players: Int = NATIONAL_PLAYERS) {
    val dir = Files.createTempDirectory("yacht-national").toFile()
    val out = (shots ?: File(dir, "shots")).apply { mkdirs() }
    val folder = File(dir, "league").apply { mkdirs() }
    File(folder, "config.txt").writeText(nationalConfig(players).encode())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val c = AppController(scope, GameStorage(dir), loadTables = { load(tablesDir) }, loadAsset = { null })
    val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    c.errorListener = { e -> errors += e.also { it.printStackTrace() } }
    val lg = c.league
    lg.leagueSeed = { NATIONAL_SEED }
    c.start()
    while (c.tables == null) Thread.sleep(50)
    c.screen = Screen.LEAGUE
    while (lg.view == null) Thread.sleep(50)
    check(lg.config.national) { "the national rules were not read: ${lg.config}" }
    lg.changeSpeed(speed)
    val t0 = System.currentTimeMillis()
    val deadline = t0 + NATIONAL_MINUTES * 60_000
    fun waitUntil(what: String, cond: () -> Boolean) {
        var told = 0L
        while (!cond()) {
            check(errors.isEmpty()) { "${errors.size} errors, the first: ${errors.first()}" }
            check(lg.stopReason == null) { "stopped: ${lg.stopReason}" }
            check(System.currentTimeMillis() < deadline) { "$what, not in $NATIONAL_MINUTES minutes" }
            if (System.currentTimeMillis() - told > 10_000) {
                told = System.currentTimeMillis()
                val v = lg.view
                println("t=${(told - t0) / 1000}s $what: running=${lg.running} season=${v?.clock?.season} week=${v?.clock?.week} played=${v?.season?.played}/${v?.season?.total} leagues=${v?.season?.leagues?.size}")
            }
            Thread.sleep(100)
        }
    }
    lg.start()
    waitUntil("season 1 started") { lg.running && (lg.view?.matches ?: 0L) > 0L && lg.view?.season != null }
    val first = lg.view!!.season!!
    check(first.national && first.leagues.size > 1) { "season 1 is not national: ${first.leagues.map { it.id }}" }
    println("season 1: " + first.leagues.joinToString { "${it.id}(${it.sizes.joinToString("+")}, rank ${it.rank}, ccl ${it.cclSlots})" } + "; shown ${first.league}")
    // Halfway through the league stage: every league shown in turn while it plays on (the
    // snapshot keeps up with the picks), then stopped (a season of 300 plays in a minute or two,
    // faster than its views are drawn) for the views with their data.
    waitUntil("season 1 halfway") { lg.view?.season?.let { it.played * 2 >= it.total } == true }
    ShotsWorld.everyLeague(c, null, "nat1")
    lg.stop()?.let { j -> kotlinx.coroutines.runBlocking { j.join() } }
    val drawn1 = ShotsWorld.everyLeague(c, out, "nat1")
    ShotsWorld.nationalShots(c, out, "nat1")
    println("season 1: $drawn1 league table views drawn")
    check(lg.view?.season?.number == 1) { "season 1 moved on while stopped" }
    // The rest of the season, its close and the next season's opening, then the league stops;
    // on the way, once the post-season is on, stopped again for the title playoffs' page (a
    // bracket a league) and its stage sheet (a heading a league).
    fun goOn(what: String) {
        val m = lg.view?.matches ?: 0L
        lg.start()
        waitUntil(what) { lg.running && (lg.view?.matches ?: 0L) > m }
        lg.stopAfterSeason(1)
    }
    goOn("season 1 going on")
    waitUntil("season 1 post-season") { !lg.running || lg.view?.comps?.any { it.first == Comp.TITLE_PLAYOFF } == true }
    if (lg.running) lg.stop()?.let { j -> kotlinx.coroutines.runBlocking { j.join() } }
    if (lg.view?.season?.number == 1 && lg.view?.comps?.any { it.first == Comp.TITLE_PLAYOFF } == true) {
        println("season 1 post-season: ${ShotsWorld.competitionShots(c, out, "nat1po")} competition pages drawn")
        goOn("season 1 post-season going on")
    } else {
        println("season 1 post-season: over before it was seen")
    }
    waitUntil("season 1 played") { !lg.running }
    waitUntil("season 2 open") { (lg.view?.clock?.season ?: 0) >= 2 && lg.view?.season?.number == 2 }
    val second = lg.view!!.season!!
    check(second.national && second.leagues.size > 1) { "season 2 is not national: ${second.leagues.map { it.id }}" }
    println("season 2: " + second.leagues.joinToString { "${it.id}(${it.sizes.joinToString("+")}, rank ${it.rank}, ccl ${it.cclSlots})" } + "; shown ${second.league}")
    if (lg.review != null) lg.dismissReview()
    val drawn2 = ShotsWorld.everyLeague(c, out, "nat2")
    ShotsWorld.nationalShots(c, out, "nat2")
    println("season 2: $drawn2 league table views drawn")
    check(errors.isEmpty()) { "${errors.size} errors, the first: ${errors.first()}" }
    println("timings: ${lg.timings}")
    c.shutdown()?.let { j -> kotlinx.coroutines.runBlocking { j.join() } }
    scope.cancel()
    // The league and, without a shots folder of its own, the pictures.
    dir.deleteRecursively()
    println("national: green in ${(System.currentTimeMillis() - t0) / 1000} s")
}

private fun load(dir: File): Tables {
    val rules = RuleSet.NINTENDO
    fun map(name: String) = RandomAccessFile(File(dir, name), "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
    }
    val bonus = File(dir, "bonus.bin").takeIf { it.exists() }?.let { TableIO.readBonus(map(it.name), rules) }
    return Tables(rules, TableIO.readEv(map("ev.bin"), rules), TableIO.readDist(map("dist.bin"), rules), bonus = bonus)
}

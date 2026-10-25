package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yacht.engine.EvTable
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.league.Family
import yacht.engine.league.GameScore
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LeagueRoster
import yacht.engine.league.Matchmaker
import yacht.engine.league.LeagueTables
import yacht.engine.league.Tier
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** One AI on the ladder as the screen shows it. */
data class LadderRow(
    val key: String,
    val name: String,
    val family: Family,
    val desc: String,
    val heavy: Boolean,
    /** Null while still in placement. */
    val rank: Int?,
    val rating: Int?,
    /** 95% half-width of the rating. */
    val margin: Int?,
    val tier: Tier?,
    val games: Int,
    val wins: Int,
    val ties: Int,
    val losses: Int,
    val winRate: Double,
    val avgScore: Double,
    val best: Int,
    val yachts: Int,
    val bonuses: Int,
    val streak: Int,
    val bestStreak: Int,
    /** Last results, oldest first ('W', 'D', 'L'). */
    val recent: String,
    val history: List<Int>,
    val peak: Int?,
    /** Rating change in its last match. */
    val lastDelta: Int,
    /** Places gained since the league was started this time (positive = up). */
    val rankChange: Int?,
    val playing: Boolean,
)

data class FeedItem(
    val a: String,
    val aName: String,
    val b: String,
    val bName: String,
    val games: List<GameScore>,
    val deltaA: Int,
    val deltaB: Int,
    val time: Long,
    /** "kind|key|value" news, e.g. "승급|win|다이아몬드". */
    val notes: List<String>,
)

data class LeagueView(
    val rows: List<LadderRow>,
    val feed: List<FeedItem>,
    val matches: Long,
    val games: Long,
    /** Matches being played right now. */
    val live: List<Pair<String, String>>,
)

/** A matchup of one AI for its detail page: its points share against [other] in [games] games. */
data class Matchup(val other: String, val name: String, val score: Double, val games: Int)

/** How many cores the league may use (battery). */
enum class LeagueSpeed(val label: String) {
    ECO("절약 · 1코어"),
    NORMAL("보통 · 절반"),
    FULL("최대 · 전부");

    fun threads(cores: Int): Int = when (this) {
        ECO -> 1
        NORMAL -> maxOf(1, cores / 2)
        FULL -> maxOf(1, cores)
    }
}

/**
 * The AI league: dozens of AIs play mirrored matches in the background while the screen shows
 * the ladder live. Runs only while started and the app is visible; the ladder is saved to [dir].
 */
class LeagueController(private val scope: CoroutineScope, dir: File) {
    private val folder = File(dir, "league").apply { mkdirs() }
    private val file = File(folder, "ladder.txt")
    private val prefs = File(folder, "prefs.txt")
    private val cores = Runtime.getRuntime().availableProcessors()

    var view by mutableStateOf<LeagueView?>(null)
        private set
    var running by mutableStateOf(false)
        private set

    /** Progress text while the risk-attitude tables are built (first start only). */
    var preparing by mutableStateOf<String?>(null)
        private set
    var speed by mutableStateOf(LeagueSpeed.NORMAL)
        private set

    /** Matches per minute in this run. */
    var pace by mutableStateOf(0.0)
        private set

    /** Screen state: the tab shown (ladder, news, families) and the AI opened in detail. */
    var tab by mutableStateOf(0)
    var detail by mutableStateOf<String?>(null)

    private var tables: Tables? = null
    private var roster: List<LeagueAi> = emptyList()
    private var byKey: Map<String, LeagueAi> = emptyMap()
    private var extra: LeagueTables? = null
    private var ladder: Ladder? = null
    private val lock = Any()
    private val throttle = Any()
    private val busy = HashSet<String>()
    private val live = LinkedHashSet<Pair<String, String>>()
    private val lastDelta = HashMap<String, Int>()
    private var startRanks: Map<String, Int> = emptyMap()
    private var stop = AtomicBoolean(true)
    private var runJob: Job? = null
    private var sinceSave = 0
    private var runStart = 0L
    private var runMatches = 0
    private var lastPublish = 0L
    private var publishPending = false

    init {
        runCatching {
            prefs.takeIf { it.exists() }?.readText()?.trim()?.let { v -> LeagueSpeed.entries.firstOrNull { it.name == v }?.let { speed = it } }
        }
    }

    /** Number of AIs in the league. */
    val size: Int get() = roster.size

    /** Loads the roster and the saved ladder (once tables are there); cheap, no tables are built here. */
    fun attach(t: Tables) {
        if (tables != null) return
        tables = t
        val rules = t.rules
        val r = LeagueTables(
            rules, threads = cores,
            load = { key, believed -> tableFile(key).takeIf { it.exists() }?.let { TableIO.readEv(ByteBuffer.wrap(it.readBytes()), believed) } },
            save = { key, table: EvTable -> tableFile(key).outputStream().use { TableIO.writeEv(it, table.rules, table.values) } },
        )
        extra = r
        roster = LeagueRoster.build(t, r)
        byKey = roster.associateBy { it.key }
        scope.launch {
            val l = withContext(Dispatchers.IO) {
                runCatching { file.takeIf { it.exists() }?.readText()?.let { Ladder.decode(roster.map { it.key }, it) } }.getOrNull()
            } ?: Ladder(roster.map { it.key })
            synchronized(lock) { ladder = l }
            publish(force = true)
        }
    }

    private fun tableFile(key: String) = File(folder, "$key.bin")

    fun changeSpeed(s: LeagueSpeed) {
        speed = s
        scope.launch(Dispatchers.IO) { runCatching { prefs.writeText(s.name) } }
        if (running) {
            stop()
            start()
        }
    }

    /** Starts (or resumes) the league. */
    fun start() {
        val t = tables ?: return
        if (running || ladder == null) return
        running = true
        val flag = AtomicBoolean(false)
        stop = flag
        synchronized(lock) {
            val l = ladder!!
            startRanks = l.keys.mapNotNull { k -> l.rankOf(k)?.let { k to it } }.toMap()
        }
        runStart = System.currentTimeMillis()
        runMatches = 0
        pace = 0.0
        val threads = speed.threads(cores)
        runJob = scope.launch {
            val r = extra!!
            // Risk-attitude and rule-belief AIs need their tables: built once, then loaded from disk.
            val missing = LeagueRoster.tableSpecs(t.rules).filter { !r.has(it.key) }
            for ((i, th) in missing.withIndex()) {
                if (flag.get()) break
                preparing = "새 AI용 계산표 준비 중 ${i + 1}/${missing.size}"
                withContext(Dispatchers.Default) { r[th] }
            }
            preparing = null
            if (flag.get()) return@launch
            val pool = Executors.newFixedThreadPool(threads) { job -> Thread(job, "yacht-league").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
            val dispatcher = pool.asCoroutineDispatcher()
            try {
                val workers = (0 until threads).map { w -> launch(dispatcher) { work(t, flag, Random(System.nanoTime() + w)) } }
                workers.joinAll()
            } finally {
                pool.shutdown()
                dispatcher.close()
            }
        }
    }

    private suspend fun work(t: Tables, flag: AtomicBoolean, rng: Random) {
        while (!flag.get()) {
            val pair = synchronized(lock) {
                Matchmaker.pick(ladder!!, rng, busy)?.also {
                    busy += it.first
                    busy += it.second
                    live += it
                }
            }
            if (pair == null) {
                delay(50)
                continue
            }
            publish()
            try {
                val a = byKey.getValue(pair.first)
                val b = byKey.getValue(pair.second)
                val result = LeagueMatch.play(t.rules, a, b, rng.nextLong()) { flag.get() }
                val save = synchronized(lock) {
                    if (flag.get()) return@synchronized null
                    val rec = ladder!!.record(pair.first, pair.second, result)
                    lastDelta[rec.a] = rec.deltaA
                    lastDelta[rec.b] = rec.deltaB
                    runMatches++
                    sinceSave++
                    if (sinceSave >= SAVE_EVERY) {
                        sinceSave = 0
                        ladder!!.encode()
                    } else {
                        null
                    }
                }
                save?.let { text -> scope.launch(Dispatchers.IO) { runCatching { file.writeText(text) } } }
            } catch (e: CancellationException) {
                // stopped mid-match: the match is dropped
            } finally {
                synchronized(lock) {
                    busy -= pair.first
                    busy -= pair.second
                    live -= pair
                }
            }
            publish()
        }
    }

    /** Stops after abandoning the matches in progress, and saves. */
    fun stop() {
        if (!running) return
        stop.set(true)
        running = false
        preparing = null
        val job = runJob
        scope.launch {
            job?.join()
            save()
            publish(force = true)
        }
    }

    /** App hidden: no league in the background (battery). */
    fun onBackground() = stop()

    /** Forgets every result (the tables built for the risk AIs stay). */
    fun reset() {
        stop()
        scope.launch {
            runJob?.join()
            synchronized(lock) {
                ladder = Ladder(roster.map { it.key })
                lastDelta.clear()
                startRanks = emptyMap()
            }
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            publish(force = true)
        }
    }

    private suspend fun save() {
        val text = synchronized(lock) { ladder?.encode() } ?: return
        withContext(Dispatchers.IO) { runCatching { file.writeText(text) } }
    }

    /** At most a few screen updates per second, however fast matches finish. */
    private fun publish(force: Boolean = false) {
        synchronized(throttle) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPublish < PUBLISH_MS) {
                if (!publishPending) {
                    publishPending = true
                    scope.launch {
                        delay(PUBLISH_MS)
                        synchronized(throttle) { publishPending = false }
                        publish(force = true)
                    }
                }
                return
            }
            lastPublish = now
        }
        val v = synchronized(lock) { snapshot() } ?: return
        scope.launch {
            view = v
            if (running) {
                val minutes = (System.currentTimeMillis() - runStart) / 60_000.0
                pace = if (minutes > 0.05) runMatches / minutes else 0.0
            }
        }
    }

    private fun snapshot(): LeagueView? {
        val l = ladder ?: return null
        val ranking = l.ranking()
        val rows = ranking.map { k ->
            val ai = byKey.getValue(k)
            val e = l.entry(k)!!
            val rank = l.rankOf(k)
            val rating = l.rating(k)
            LadderRow(
                k, ai.name, ai.family, ai.desc, ai.heavy, rank,
                rating.takeIf { !it.isNaN() }?.roundToInt(),
                l.error(k).takeIf { !it.isNaN() }?.let { (1.96 * it).roundToInt() },
                l.tier(k), e.games, e.wins, e.ties, e.losses, e.winRate, e.avgScore, e.best, e.yachts, e.bonuses,
                e.streak, e.bestStreak, e.recent.joinToString(""), e.history.toList(), e.peak.takeIf { !it.isNaN() }?.roundToInt(),
                lastDelta[k] ?: 0, rank?.let { r -> startRanks[k]?.let { it - r } }, k in busy,
            )
        }
        val feed = l.feed.take(FEED).map { m ->
            FeedItem(m.a, byKey[m.a]?.name ?: m.a, m.b, byKey[m.b]?.name ?: m.b, m.games, m.deltaA, m.deltaB, m.time, m.notes)
        }
        return LeagueView(rows, feed, l.matches, l.games, live.toList())
    }

    fun name(key: String): String = byKey[key]?.name ?: key

    /** Best and worst opponents of [key] with at least [min] games against them. */
    fun matchups(key: String, min: Int = 4): Pair<List<Matchup>, List<Matchup>> = synchronized(lock) {
        val l = ladder ?: return emptyList<Matchup>() to emptyList()
        val all = l.keys.filter { it != key }.mapNotNull { o ->
            val (w, g) = l.headToHead(key, o)
            if (g < min) null else Matchup(o, name(o), w / g, g)
        }
        all.sortedByDescending { it.score }.take(3) to all.sortedBy { it.score }.take(3)
    }

    companion object {
        private const val SAVE_EVERY = 10
        private const val PUBLISH_MS = 300L
        private const val FEED = 60
    }
}

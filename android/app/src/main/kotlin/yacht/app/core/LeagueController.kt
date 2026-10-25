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
import yacht.engine.Advisor
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.EvTable
import yacht.engine.Mode
import yacht.engine.Player
import yacht.engine.TableIO
import yacht.engine.Tables
import yacht.engine.explain.AiStep
import yacht.engine.explain.Commentary
import yacht.engine.explain.OutlookAnalysis
import yacht.engine.league.CloneProfile
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomAis
import yacht.engine.league.Family
import yacht.engine.league.Season
import yacht.engine.league.SeasonSummary
import yacht.engine.league.TierName
import yacht.engine.league.WatchMatch
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
    /** The player's own line (ranked games). */
    val isMe: Boolean = false,
    /** Season division (0 = top), null when not in this season. */
    val division: Int? = null,
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
    val season: SeasonView? = null,
)

/** One team in a season table. */
data class SeasonRow(
    val key: String,
    val name: String,
    val played: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val points: Int,
    val diff: Int,
    val avgScore: Double,
    val yachts: Int,
    /** +1 promotion zone, -1 relegation zone, 0 otherwise. */
    val zone: Int,
)

data class SeasonHistoryItem(
    val number: Int,
    val champions: List<String>,
    val topScorer: String?,
    val topScore: Double,
    val yachtKing: String?,
    val yachts: Int,
    val promoted: List<String>,
    val relegated: List<String>,
)

data class SeasonView(
    val number: Int,
    val played: Int,
    val total: Int,
    val divisions: List<List<SeasonRow>>,
    /** AIs that join the lowest division next season. */
    val newcomers: List<String>,
    /** Latest first. */
    val history: List<SeasonHistoryItem>,
)

/** A ranked game counted: my rating and tier before and after. */
data class RankResult(
    val opponent: String,
    val won: Boolean,
    val draw: Boolean,
    val before: Int?,
    val after: Int?,
    val tierBefore: Tier?,
    val tierAfter: Tier?,
    val rank: Int?,
    val games: Int,
    val placementLeft: Int,
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

/** One shown step of a watched game: a throw of one side, with the cards after the turn when it ends. */
data class WatchStep(
    val turn: Int,
    /** 0 = first player. */
    val side: Int,
    val throwNo: Int,
    val dice: List<Int>,
    val held: List<Int>?,
    val category: yacht.engine.Category?,
    val points: Int,
    /** The first player's P(win) after this step. */
    val winFirst: Double?,
    val lines: List<yacht.engine.explain.Comment>,
    val first: yacht.engine.Board,
    val second: yacht.engine.Board,
)

/** A game being watched: the two AIs, the steps worked out so far, and the one shown. */
data class WatchView(
    val first: String,
    val second: String,
    val firstName: String,
    val secondName: String,
    val steps: List<WatchStep>,
    /** Steps the game has in all (two sides × 12 turns, one to three throws each); known once done. */
    val complete: Boolean,
    val shown: Int,
    val playing: Boolean,
)

/**
 * The AI league: dozens of AIs play mirrored matches in the background while the screen shows
 * the ladder and the season live. Runs only while started and the app is visible; everything is
 * saved under [dir]. Also rates the player's ranked games and plays games back for watching.
 */
class LeagueController(
    private val scope: CoroutineScope,
    dir: File,
    /** The seed ladder (a league played on a server), when the app ships one. */
    private val seed: () -> String? = { null },
) {
    private val folder = File(dir, "league").apply { mkdirs() }
    private val file = File(folder, "ladder.txt")
    private val seasonFile = File(folder, "season.txt")
    private val customFile = File(folder, "custom.txt")
    private val prefs = File(folder, "prefs.txt")
    private val cores = Runtime.getRuntime().availableProcessors()

    var view by mutableStateOf<LeagueView?>(null)
        private set
    var running by mutableStateOf(false)
        private set

    /** Progress text while the extra tables are built (first start only). */
    var preparing by mutableStateOf<String?>(null)
        private set
    var speed by mutableStateOf(LeagueSpeed.NORMAL)
        private set

    /** Matches per minute in this run. */
    var pace by mutableStateOf(0.0)
        private set

    /** Screen state: the tab shown (ladder, season, news, families) and the AI opened in detail. */
    var tab by mutableStateOf(0)
    var detail by mutableStateOf<String?>(null)

    /** The player's own AIs (saved) and clone profile. */
    var customs by mutableStateOf<List<CustomAiSpec>>(emptyList())
        private set
    var clone by mutableStateOf<CloneProfile?>(null)
        private set

    /** A season that just ended (shown until dismissed). */
    var seasonNews by mutableStateOf<SeasonHistoryItem?>(null)

    private var tables: Tables? = null
    private var baseRoster: List<LeagueAi> = emptyList()
    @Volatile
    private var roster: List<LeagueAi> = emptyList()
    @Volatile
    private var byKey: Map<String, LeagueAi> = emptyMap()
    private var extra: LeagueTables? = null
    private var ladder: Ladder? = null
    private var season: Season? = null
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
        customs = runCatching { customFile.takeIf { it.exists() }?.readLines()?.mapNotNull { CustomAiSpec.decode(it) } }.getOrNull().orEmpty()
    }

    /** Number of AIs in the league. */
    val size: Int get() = roster.size

    /** Loads the roster and the saved ladder and season (once tables are there); no tables are built here. */
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
        baseRoster = LeagueRoster.build(t, r)
        rebuildRoster()
        reload()
    }

    /** Reads the saved ladder and season again (after a restore). */
    fun reload() {
        scope.launch(rosterWork) {
            val keys = synchronized(lock) { ladderKeys() }
            val l = runCatching { file.takeIf { it.exists() }?.readText()?.let { Ladder.decode(keys, it) } }.getOrNull() ?: Ladder(keys)
            val s = runCatching { seasonFile.takeIf { it.exists() }?.readText()?.let { Season.decode(it, keys) } }.getOrNull()
            synchronized(lock) {
                ladder = l
                season = s
            }
            publish(force = true)
        }
    }

    private fun ladderKeys() = roster.map { it.key } + Ladder.ME

    /** Base roster plus the clone and the player's AIs; the ladder and season follow. */
    private fun rebuildRoster() {
        val t = tables ?: return
        val x = extra ?: return
        val mine = ArrayList<LeagueAi>()
        clone?.let { mine += CustomAis.clone(it, t) }
        for (c in customs) mine += CustomAis.build(c, t, x)
        val all = mine + baseRoster
        synchronized(lock) {
            roster = all
            byKey = all.associateBy { it.key }
            val keys = ladderKeys()
            ladder?.let { l -> if (l.keys != keys) ladder = Ladder.decode(keys, l.encode()) }
            season?.sync(keys)
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

    // ---- the player's AIs ----------------------------------------------------------------------

    /**
     * Roster changes and reloads run one at a time off the main thread: each re-reads the
     * ladder (hundreds of ms on a phone), and they must not overtake each other.
     */
    private val rosterWork = Dispatchers.Default.limitedParallelism(1)

    private fun rosterChanged(after: () -> Unit = {}) {
        scope.launch(rosterWork) {
            rebuildRoster()
            after()
            publish(force = true)
        }
    }

    /** Updates the clone from my finished games (null: not enough games yet). */
    fun updateClone(profile: CloneProfile?) {
        if (profile == clone) return
        clone = profile
        rosterChanged()
    }

    fun saveCustom(spec: CustomAiSpec) {
        val old = customs.firstOrNull { it.id == spec.id }
        customs = customs.filter { it.id != spec.id } + spec
        persistCustoms()
        // Changed play (not just the name): its old results no longer describe it.
        val replay = old != null && old.copy(name = spec.name) != spec
        rosterChanged { if (replay) forget(spec.key) }
    }

    /** Drops [key]'s ladder results so it is placed again. */
    private fun forget(key: String) {
        synchronized(lock) {
            val l = ladder ?: return
            val keys = ladderKeys()
            ladder = Ladder.decode(keys, Ladder.decode(keys - key, l.encode()).encode())
        }
        scope.launch { save() }
    }

    fun deleteCustom(id: String) {
        customs = customs.filter { it.id != id }
        persistCustoms()
        rosterChanged()
    }

    private fun persistCustoms() {
        val text = customs.joinToString("\n") { it.encode() }
        scope.launch(Dispatchers.IO) { runCatching { customFile.writeText(text) } }
    }

    // ---- running ---------------------------------------------------------------------------------

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
            if (season == null) season = Season.start(l.ranking())
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

    /** What a worker plays next: a season fixture, or a friendly (newcomers, or nothing else free). */
    private class Job2(val a: String, val b: String, val fixture: Pair<String, String>?)

    private fun nextJob(rng: Random): Job2? {
        val l = ladder ?: return null
        val s = season
        if (s != null && s.finished) {
            val news = historyItem(s.finish())
            scope.launch { seasonNews = news }
            saveSeasonAsync()
        }
        val newcomers = s?.newcomers?.filter { it !in busy && it in byKey }.orEmpty()
        if (newcomers.isNotEmpty() && rng.nextDouble() < FRIENDLY_SHARE) {
            Matchmaker.pick(l, rng, busy, among = newcomers)?.let { return Job2(it.first, it.second, null) }
        }
        s?.next(rng, busy)?.let { return Job2(it.first, it.second, it) }
        return Matchmaker.pick(l, rng, busy)?.let { Job2(it.first, it.second, null) }
    }

    private suspend fun work(t: Tables, flag: AtomicBoolean, rng: Random) {
        while (!flag.get()) {
            val job = synchronized(lock) {
                nextJob(rng)?.also {
                    busy += it.a
                    busy += it.b
                    live += it.a to it.b
                }
            }
            if (job == null) {
                delay(50)
                continue
            }
            publish()
            var recorded = false
            try {
                val a = byKey[job.a]
                val b = byKey[job.b]
                if (a != null && b != null) {
                    val result = LeagueMatch.play(t.rules, a, b, rng.nextLong()) { flag.get() }
                    val save = synchronized(lock) {
                        val l = ladder!!
                        if (flag.get() || l.entry(job.a) == null || l.entry(job.b) == null) return@synchronized null
                        val rec = l.record(job.a, job.b, result)
                        job.fixture?.let { season?.record(it, result) }
                        recorded = true
                        lastDelta[rec.a] = rec.deltaA
                        lastDelta[rec.b] = rec.deltaB
                        runMatches++
                        sinceSave++
                        if (sinceSave >= SAVE_EVERY) {
                            sinceSave = 0
                            l.encode() to season?.encode()
                        } else {
                            null
                        }
                    }
                    save?.let { (text, s) ->
                        scope.launch(Dispatchers.IO) {
                            runCatching { file.writeText(text) }
                            s?.let { runCatching { seasonFile.writeText(it) } }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // stopped mid-match: the match is dropped
            } finally {
                synchronized(lock) {
                    if (!recorded) job.fixture?.let { season?.release(it) }
                    busy -= job.a
                    busy -= job.b
                    live -= job.a to job.b
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
    fun onBackground() {
        stop()
        pauseWatch()
    }

    /** True when the app ships a seed ladder to start from. */
    val hasSeed: Boolean by lazy { seed() != null }

    /**
     * Starts from the seed ladder (tens of thousands of matches played on a server) instead of
     * from zero; only while this league has no games yet.
     */
    fun loadSeed() {
        if (running || (view?.matches ?: 0L) > 0L) return
        scope.launch {
            val text = withContext(Dispatchers.IO) { seed() } ?: return@launch
            withContext(rosterWork) {
                val l = Ladder.decode(synchronized(lock) { ladderKeys() }, text)
                synchronized(lock) {
                    ladder = l
                    season = null
                }
            }
            save()
            publish(force = true)
        }
    }

    /** Forgets every result and season (my AIs and the built tables stay). */
    fun reset() {
        stop()
        scope.launch {
            runJob?.join()
            synchronized(lock) {
                ladder = Ladder(ladderKeys())
                season = null
                lastDelta.clear()
                startRanks = emptyMap()
            }
            withContext(Dispatchers.IO) {
                runCatching { file.delete() }
                runCatching { seasonFile.delete() }
            }
            publish(force = true)
        }
    }

    private suspend fun save() = withContext(Dispatchers.Default) {
        val (text, s) = synchronized(lock) { ladder?.encode() to season?.encode() }
        withContext(Dispatchers.IO) {
            text?.let { runCatching { file.writeText(it) } }
            s?.let { runCatching { seasonFile.writeText(it) } }
        }
    }

    private fun saveSeasonAsync() {
        val s = season?.encode() ?: return
        scope.launch(Dispatchers.IO) { runCatching { seasonFile.writeText(s) } }
    }

    // ---- ranked games ----------------------------------------------------------------------------

    /** My rating, or null before my first ranked game. */
    fun myRating(): Double? = synchronized(lock) { ladder?.rating(Ladder.ME)?.takeIf { !it.isNaN() } }

    /**
     * An opponent for a ranked game: near my rating (1400 before I have one), or within [tier]
     * when given. Null when no AI is rated yet.
     */
    fun pickOpponent(rng: Random, tier: TierName? = null): String? = synchronized(lock) {
        val l = ladder ?: return null
        val among = if (tier == null) null else l.keys.filter { k -> k != Ladder.ME && l.tier(k)?.name == tier }
        if (among != null && among.isEmpty()) return null
        val r = when {
            tier != null -> among!!.mapNotNull { l.rating(it).takeIf { x -> !x.isNaN() } }.average()
            else -> l.rating(Ladder.ME).takeIf { !it.isNaN() } ?: START_RATING
        }
        Matchmaker.opponentFor(l, r, rng, among) ?: among?.randomOrNull(kotlin.random.Random(rng.nextLong()))
    }

    /** A fresh player for a ranked game against [key]. */
    fun player(key: String, rng: Random): Player? = byKey[key]?.make?.invoke(rng)

    fun tierOf(key: String): Tier? = synchronized(lock) { ladder?.tier(key) }

    /** Counts a finished ranked game in my rating. */
    fun recordMyGame(ai: String, me: Board, opp: Board): RankResult? {
        val result = synchronized(lock) {
            val l = ladder ?: return null
            if (l.entry(ai) == null) return null
            val before = l.rating(Ladder.ME).takeIf { !it.isNaN() }
            val tierBefore = l.tier(Ladder.ME)
            l.record(
                Ladder.ME, ai,
                listOf(GameScore(me.total, opp.total, (me[Category.YACHT] ?: 0) > 0, (opp[Category.YACHT] ?: 0) > 0, me.bonus > 0, opp.bonus > 0)),
            )
            l.fit()
            val e = l.entry(Ladder.ME)!!
            RankResult(
                name(ai), me.total > opp.total, me.total == opp.total, before?.roundToInt(),
                l.rating(Ladder.ME).takeIf { !it.isNaN() }?.roundToInt(), tierBefore, l.tier(Ladder.ME), l.rankOf(Ladder.ME),
                e.games, maxOf(0, Ladder.ME_PLACEMENT - e.games),
            )
        }
        scope.launch { save() }
        publish(force = true)
        return result
    }

    // ---- watching a game -------------------------------------------------------------------------

    var watch by mutableStateOf<WatchView?>(null)
        private set
    private var watchJob: Job? = null
    private var playJob: Job? = null

    /** A game worth watching: a top-20 AI (any AI before ratings) against a rival near its rating. */
    fun featured(rng: Random): Pair<String, String>? {
        val first = synchronized(lock) {
            val l = ladder ?: return null
            val top = l.ranking().filter { it != Ladder.ME && l.rankOf(it) != null }.take(20)
            top.ifEmpty { roster.map { it.key } }.randomOrNull(kotlin.random.Random(rng.nextLong()))
        } ?: return null
        return rivalOf(first, rng)?.let { first to it }
    }

    /** An opponent to watch [key] against: someone near its rating. */
    fun rivalOf(key: String, rng: Random): String? = synchronized(lock) {
        val l = ladder ?: return null
        val r = l.rating(key).takeIf { !it.isNaN() } ?: return byKey.keys.filter { it != key }.randomOrNull()
        Matchmaker.opponentFor(l, r, rng, l.keys.filter { it != key && it != Ladder.ME }) ?: byKey.keys.firstOrNull { it != key }
    }

    /**
     * Plays one game of [first] against [second] and shows it throw by throw at [pace] ms per
     * step, with the commentary of each throw and [first]'s P(win). The game counts on the ladder.
     */
    fun startWatch(first: String, second: String, pace: () -> Long) {
        val t = tables ?: return
        val a = byKey[first] ?: return
        val b = byKey[second] ?: return
        stopWatch()
        watch = WatchView(first, second, a.name, b.name, emptyList(), false, 0, true)
        val steps = ArrayList<WatchStep>()
        watchJob = scope.launch {
            val rng = Random()
            val turns = withContext(Dispatchers.Default) { WatchMatch.play(t.rules, a.make(Random(rng.nextLong())), b.make(Random(rng.nextLong())), rng) }
            val last = turns.last()
            withContext(Dispatchers.Default) {
                synchronized(lock) {
                    ladder?.let { l ->
                        if (l.entry(first) != null && l.entry(second) != null) {
                            l.record(
                                first, second,
                                listOf(GameScore(last.first.total, last.second.total, (last.first[Category.YACHT] ?: 0) > 0, (last.second[Category.YACHT] ?: 0) > 0, last.first.bonus > 0, last.second.bonus > 0)),
                            )
                        }
                    }
                }
            }
            publish(force = true)
            val advisor = Advisor(t.withoutRisk())
            val outlook = OutlookAnalysis(t)
            val talk = Commentary(t)
            var x = Board(t.rules)
            var y = Board(t.rules)
            for ((i, turn) in turns.withIndex()) {
                val (mover, other) = if (turn.side == 0) x to y else y to x
                val moverName = if (turn.side == 0) a.name else b.name
                val waitingName = if (turn.side == 0) b.name else a.name
                val made = withContext(Dispatchers.Default) {
                    runCatching {
                        val plan = advisor.planTurn(mover, other, Mode.WIN, depth = 1)
                        val otherWin = (1 - plan.startValue).coerceIn(0.0, 1.0)
                        val after = outlook.winAfter(other, mover, otherWin)
                        talk.aiSteps(
                            plan, mover, turn.throws, turn.held, turn.category, after?.let { w -> { c: Board -> w(c) } }, otherWin,
                            judge = true, who = moverName, viewer = waitingName,
                        )
                    }.getOrNull()
                } ?: turn.throws.mapIndexed { k, d ->
                    val end = k == turn.throws.size - 1
                    AiStep(k + 1, d.sorted(), turn.held.getOrNull(k), if (end) turn.category else null, if (end) turn.points else 0, null, emptyList())
                }
                for (st in made) {
                    // myWin is the waiting side's P(win): as the first player's.
                    val winFirst = st.myWin?.let { if (turn.side == 0) 1 - it else it }
                    val end = st.category != null
                    steps += WatchStep(
                        i / 2 + 1, turn.side, st.throwNo, st.dice, st.held, st.category, st.points, winFirst,
                        st.lines.filter { !it.tag.contains("승률") }, if (end) turn.first else x, if (end) turn.second else y,
                    )
                }
                x = turn.first
                y = turn.second
                watch = watch?.copy(steps = steps.toList(), complete = i == turns.size - 1)
            }
        }
        playJob = scope.launch { playLoop(pace) }
    }

    private suspend fun playLoop(pace: () -> Long) {
        while (true) {
            val w = watch ?: return
            if (!w.playing) return
            if (w.shown < w.steps.size) watch = w.copy(shown = w.shown + 1)
            else if (w.complete) {
                watch = w.copy(playing = false)
                return
            }
            delay(maxOf(300L, pace()))
        }
    }

    fun pauseWatch() {
        playJob?.cancel()
        watch = watch?.copy(playing = false)
    }

    fun resumeWatch(pace: () -> Long) {
        val w = watch ?: return
        if (w.playing) return
        watch = w.copy(playing = true)
        playJob = scope.launch { playLoop(pace) }
    }

    /** Jumps to the end of the current turn (or the next one). */
    fun skipWatchTurn() {
        val w = watch ?: return
        var i = w.shown
        while (i < w.steps.size && w.steps[i].category == null) i++
        watch = w.copy(shown = minOf(w.steps.size, i + 1))
    }

    fun stopWatch() {
        watchJob?.cancel()
        playJob?.cancel()
        watch = null
    }

    // ---- the screen ------------------------------------------------------------------------------

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

    fun name(key: String): String = if (key == Ladder.ME) "나" else byKey[key]?.name ?: key

    private fun snapshot(): LeagueView? {
        val l = ladder ?: return null
        // Matches only nudge the fit; the table shows it converged.
        l.fit()
        val s = season
        val ranking = l.ranking().filter { it != Ladder.ME || l.entry(it)!!.games > 0 }
        val rows = ranking.mapNotNull { k ->
            val me = k == Ladder.ME
            val ai = byKey[k]
            if (ai == null && !me) return@mapNotNull null
            val e = l.entry(k)!!
            val rank = l.rankOf(k)
            val rating = l.rating(k)
            LadderRow(
                k, name(k), ai?.family ?: Family.MINE, ai?.desc ?: "랭크전으로 매긴 내 레이팅", ai?.heavy ?: false, rank,
                rating.takeIf { !it.isNaN() }?.roundToInt(),
                l.error(k).takeIf { !it.isNaN() }?.let { (1.96 * it).roundToInt() },
                l.tier(k), e.games, e.wins, e.ties, e.losses, e.winRate, e.avgScore, e.best, e.yachts, e.bonuses,
                e.streak, e.bestStreak, e.recent.joinToString(""), e.history.toList(), e.peak.takeIf { !it.isNaN() }?.roundToInt(),
                lastDelta[k] ?: 0, rank?.let { r -> startRanks[k]?.let { it - r } }, k in busy, me, s?.divisionOf(k),
            )
        }
        val feed = l.feed.take(FEED).map { m -> FeedItem(m.a, name(m.a), m.b, name(m.b), m.games, m.deltaA, m.deltaB, m.time, m.notes) }
        return LeagueView(rows, feed, l.matches, l.games, live.toList(), s?.let { seasonView(it) })
    }

    private fun seasonView(s: Season): SeasonView {
        val divisions = s.divisions.indices.map { d ->
            val table = s.table(d)
            val swapUp = if (d > 0) minOf(Season.SWAP, table.size / 3, s.divisions[d - 1].size / 3) else 0
            val swapDown = if (d < s.divisions.size - 1) minOf(Season.SWAP, table.size / 3, s.divisions[d + 1].size / 3) else 0
            table.mapIndexed { i, st ->
                SeasonRow(
                    st.key, name(st.key), st.played, st.wins, st.draws, st.losses, st.points, st.diff, st.avgScore, st.yachts,
                    when {
                        i < swapUp -> 1
                        i >= table.size - swapDown -> -1
                        else -> 0
                    },
                )
            }
        }
        return SeasonView(
            s.number, s.fixturesTotal - s.fixturesLeft, s.fixturesTotal, divisions, s.newcomers.map { name(it) },
            s.history.reversed().map { historyItem(it) },
        )
    }

    private fun historyItem(h: SeasonSummary) = SeasonHistoryItem(
        h.number, h.champions.map { name(it) }, h.topScorer?.let { name(it) }, h.topScore, h.yachtKing?.let { name(it) }, h.yachts,
        h.moves.filter { it.third < it.second }.map { name(it.first) }, h.moves.filter { it.third > it.second }.map { name(it.first) },
    )

    /** Best and worst opponents of [key] with at least [min] games against them. */
    fun matchups(key: String, min: Int = 4): Pair<List<Matchup>, List<Matchup>> = synchronized(lock) {
        val l = ladder ?: return emptyList<Matchup>() to emptyList()
        val all = l.keys.filter { it != key }.mapNotNull { o ->
            val (w, g) = l.headToHead(key, o)
            if (g < min) null else Matchup(o, name(o), w / g, g)
        }
        all.sortedByDescending { it.score }.take(3) to all.sortedBy { it.score }.take(3)
    }

    /** The saved league files, for a backup. */
    fun files(): List<File> = listOf(file, seasonFile, customFile, prefs).filter { it.exists() }

    companion object {
        private const val SAVE_EVERY = 10
        private const val PUBLISH_MS = 300L
        private const val FEED = 60

        /** Share of matches given to AIs waiting for the next season, so they get a rating. */
        private const val FRIENDLY_SHARE = 0.2

        /** My assumed rating before my first ranked game (a solid amateur). */
        const val START_RATING = 1400.0
    }
}

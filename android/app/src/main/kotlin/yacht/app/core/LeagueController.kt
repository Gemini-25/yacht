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
import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Fixture
import yacht.engine.league.Honour
import yacht.engine.league.HonourBook
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.SeasonPlan
import yacht.engine.league.LeagueWorld
import yacht.engine.league.PlayerBook
import yacht.engine.league.leagueAi
import yacht.engine.league.WinEstimate
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
    /** +1 promotion zone, -1 relegation zone, +-2 the playoff places, 0 otherwise. */
    val zone: Int,
    /** 운 보정 승점: 3 for the higher two-game total on the same dice, 1 when equal. */
    val aggPoints: Int = 0,
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
    /** Places after each finished matchday per division, in [order] (순위 변동 그래프). */
    val positions: List<List<ByteArray>> = emptyList(),
    /** Each division's AIs in the order [positions] uses. */
    val order: List<List<String>> = emptyList(),
    val matchdays: Int = 0,
    val currentMatchday: Int = 0,
)

/** One fixture in the calendar (일정표): its games once played, or whether it is being played. */
data class CalendarItem(
    val id: Int,
    val a: String,
    val aName: String,
    val b: String,
    val bName: String,
    val games: List<GameScore>?,
    val live: Boolean,
)

/**
 * 상대 전적 매트릭스: [score][i][j] is row [i]'s points share against column [j] (ties half),
 * NaN with fewer than 2 games; [games][i][j] the games between them.
 */
class H2hMatrix(val title: String, val labels: List<String>, val score: Array<DoubleArray>, val games: Array<IntArray>)

/** A matchday of a division in the calendar. */
data class CalendarView(val division: Int, val matchday: Int, val matchdays: Int, val current: Int, val items: List<CalendarItem>)

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
    private val configFile = File(folder, "config.txt")
    private val customFile = File(folder, "custom.txt")
    private val prefs = File(folder, "prefs.txt")
    private val favouriteFile = File(folder, "favourites.txt")
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

    /** 응원 AI: the AIs the player follows (starred on the ladder, their news first). */
    var favourites by mutableStateOf<Set<String>>(emptySet())
        private set

    fun toggleFavourite(key: String) {
        favourites = if (key in favourites) favourites - key else favourites + key
        val text = favourites.sorted().joinToString("\n")
        scope.launch(fileWrites) { runCatching { favouriteFile.writeTextAtomically(text) } }
    }

    /** A season that just ended (shown until dismissed). */
    var seasonNews by mutableStateOf<SeasonHistoryItem?>(null)

    private var tables: Tables? = null
    private var baseRoster: List<LeagueAi> = emptyList()
    @Volatile
    private var roster: List<LeagueAi> = emptyList()
    @Volatile
    private var byKey: Map<String, LeagueAi> = emptyMap()
    private var heavyKeys: Set<String> = emptySet()
    private var extra: LeagueTables? = null
    /** The league's state (ladder, season, honours, players); null until the saved league is read. */
    private var world: LeagueWorld? = null
    private val ladder: Ladder? get() = world?.ladder
    private val plan: SeasonPlan? get() = world?.plan
    private val honours: HonourBook get() = world?.honours ?: HonourBook()
    private var estimate: WinEstimate? = null

    /** Average ms per game of each AI (cost meter: heavy fixtures first late in a season). */
    private val costMs = HashMap<String, Double>()

    /** Knockout and group jobs in flight (they may take at most all but one worker). */
    private var cupsInFlight = 0

    /** The league's rules (saved); structural changes apply from the next season. */
    var config by mutableStateOf(LeagueConfig())
        private set

    private val lock = Any()
    private val throttle = Any()
    private val busy = HashSet<String>()
    private val live = LinkedHashSet<Pair<String, String>>()
    private val lastDelta = HashMap<String, Int>()
    private var startRanks: Map<String, Int> = emptyMap()
    private var stop = AtomicBoolean(true)
    private var runJob: Job? = null
    /** The ladder and its version the last full fit was made for. */
    private var fittedLadder: Ladder? = null
    private var fittedVersion = -1L
    private var runStart = 0L
    private var runMatches = 0
    private var lastPublish = 0L
    private var publishPending = false
    private var snapshots = 0L
    private val snapshotRunning = AtomicBoolean(false)
    private val snapshotAgain = AtomicBoolean(false)
    private var shownSnapshot = 0L

    init {
        runCatching {
            prefs.takeIf { it.exists() }?.readText()?.trim()?.let { v -> LeagueSpeed.entries.firstOrNull { it.name == v }?.let { speed = it } }
        }
        customs = runCatching { customFile.takeIf { it.exists() }?.readLines()?.mapNotNull { CustomAiSpec.decode(it) } }.getOrNull().orEmpty()
        config = runCatching { configFile.takeIf { it.exists() }?.readText()?.let { LeagueConfig.decode(it) } }.getOrNull() ?: LeagueConfig()
        favourites = readFavourites()
    }

    private fun readFavourites(): Set<String> =
        runCatching { favouriteFile.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() }.getOrNull().orEmpty()

    /** Number of AIs in the league. */
    val size: Int get() = roster.size

    /** Loads the roster and the saved ladder and season (once tables are there); no tables are built here. */
    fun attach(t: Tables) {
        if (tables != null) return
        tables = t
        val rules = t.rules
        val r = LeagueTables(
            rules, threads = cores,
            // A table that cannot be read (cut off by an older version) is built again.
            load = { key, believed -> runCatching { tableFile(key).takeIf { it.exists() }?.let { TableIO.readEv(ByteBuffer.wrap(it.readBytes()), believed) } }.getOrNull() },
            save = { key, table: EvTable ->
                val out = java.io.ByteArrayOutputStream()
                TableIO.writeEv(out, table.rules, table.values)
                tableFile(key).writeAtomically(out.toByteArray())
            },
        )
        extra = r
        estimate = WinEstimate(t)
        baseRoster = LeagueRoster.build(t, r)
        rebuildRoster()
        reload()
    }

    /** Completed once the saved league has been read for the first time. */
    val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Matches on the loaded ladder (0 before it is loaded). */
    fun matchCount(): Long = synchronized(lock) { ladder?.matches ?: 0L }

    /** Reads the saved ladder and season again (after a restore). */
    fun reload() {
        scope.launch(rosterWork) {
            val texts = LeagueWorld.FILES.associateWith { name -> runCatching { File(folder, name).takeIf { it.exists() }?.readText() }.getOrNull() }
            val cfg = runCatching { configFile.takeIf { it.exists() }?.readText()?.let { LeagueConfig.decode(it) } }.getOrNull() ?: config
            val reserved = baseRoster.map { it.name }
            // The generated and evolved players first: they are AIs of the roster, so the
            // ladder and the season read with them.
            val players = texts[LeagueWorld.ROSTER]?.let { runCatching { PlayerBook.decode(it, reserved) }.getOrNull() } ?: PlayerBook(reserved)
            players.ensureGenerated(cfg.generated)
            synchronized(lock) { pendingPlayers = players }
            rebuildRoster()
            val w = synchronized(lock) { LeagueWorld.decode(texts, ladderKeys(), reserved) }
            w.players = players
            w.config = cfg
            synchronized(lock) {
                world = w
                pendingPlayers = null
                configureWorld(w)
            }
            loaded.complete(Unit)
            // A restore may have brought the rules and the favourites too.
            val favs = readFavourites()
            scope.launch {
                config = cfg
                favourites = favs
            }
            publish(force = true)
        }
    }

    private fun ladderKeys() = roster.map { it.key } + Ladder.ME

    /** The players book while the league is being read (before [world] exists). */
    private var pendingPlayers: PlayerBook? = null

    /**
     * The clone and the player's AIs, the hand-made AIs, then the generated and evolved players;
     * the ladder follows (and the league's members, see [members]). Safe under the lock.
     */
    private fun rebuildRoster() {
        val t = tables ?: return
        val x = extra ?: return
        synchronized(lock) {
            val mine = ArrayList<LeagueAi>()
            clone?.let { mine += CustomAis.clone(it, t) }
            for (c in customs) mine += CustomAis.build(c, t, x)
            val players = world?.players ?: pendingPlayers
            val generated = players?.alive.orEmpty().map { leagueAi(it, t, x) }
            val all = mine + baseRoster + generated
            roster = all
            byKey = all.associateBy { it.key }
            heavyKeys = all.filter { it.heavy }.map { it.key }.toSet()
            val keys = ladderKeys()
            world?.let { w ->
                if (w.ladder.keys != keys) w.ladder = Ladder.decode(keys, w.ladder.encode())
                configureWorld(w)
            }
        }
    }

    /** What the world takes from the roster and the rules: the members, families and config. */
    private fun configureWorld(w: LeagueWorld) {
        w.config = config
        w.family = { k -> byKey[k]?.family }
        w.members = members(w)
    }

    /**
     * Next season's AIs: everyone but me, or only the AIs with mistakes (대혼돈), or the best
     * rated (강자만); my own AIs always play.
     */
    private fun members(w: LeagueWorld): List<String> {
        val c = config
        var keys = roster.filter { ai ->
            !c.onlyFlawed || ai.family == Family.MINE || ai.family in LeagueWorld.FLAWED ||
                w.players[ai.key]?.spec?.flawed == true
        }.map { it.key }
        if (c.onlyTop > 0) {
            val mine = keys.filter { byKey[it]?.family == Family.MINE }
            keys = (keys.filter { it !in mine }.sortedByDescending { w.rating(it) }.take(c.onlyTop) + mine)
        }
        return keys
    }

    private fun tableFile(key: String) = File(folder, "$key.bin")

    fun changeSpeed(s: LeagueSpeed) {
        speed = s
        scope.launch(fileWrites) { runCatching { prefs.writeTextAtomically(s.name) } }
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

    /** League files are written one at a time, in order: two saves of a file never overtake each other. */
    private val fileWrites = Dispatchers.IO.limitedParallelism(1)

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
            val w = world ?: return
            val keys = ladderKeys()
            w.ladder = Ladder.decode(keys, Ladder.decode(keys - key, w.ladder.encode()).encode())
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
        scope.launch(fileWrites) { runCatching { customFile.writeTextAtomically(text) } }
    }

    // ---- running ---------------------------------------------------------------------------------

    /** Starts (or resumes) the league. */
    fun start() {
        val t = tables ?: return
        if (running || ladder == null) return
        running = true
        val flag = AtomicBoolean(false)
        stop = flag
        val previous = runJob
        runStart = System.currentTimeMillis()
        runMatches = 0
        pace = 0.0
        val threads = speed.threads(cores)
        runJob = scope.launch {
            // A restart (speed change) waits for the old workers; ranks and the first season
            // need the lock the workers hold: both off the main thread.
            previous?.join()
            withContext(Dispatchers.Default) {
                synchronized(lock) {
                    val w = world ?: return@synchronized
                    val l = w.ladder
                    startRanks = l.keys.mapNotNull { k -> l.rankOf(k)?.let { k to it } }.toMap()
                    if (w.plan == null) {
                        configureWorld(w)
                        w.openFirstSeason(System.nanoTime())
                    }
                }
            }
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
                val upkeep = launch(Dispatchers.Default) { upkeep(flag) }
                workers.joinAll()
                upkeep.cancel()
            } finally {
                pool.shutdown()
                dispatcher.close()
            }
        }
    }

    /**
     * What a worker plays next: the season's next fixture (ending the season and starting the
     * next when it is over), or a friendly (newcomers waiting for a season, or nothing free).
     */
    private fun nextJob(rng: Random): Fixture? {
        val w = world ?: return null
        val l = w.ladder
        // No season (a restore read an old season file while the league was starting): a
        // first one, or the workers would wait forever.
        if (w.plan == null) {
            configureWorld(w)
            w.openFirstSeason(System.nanoTime())
        }
        val p = w.plan ?: return null
        val inLeague = p.league.standings.keys
        val members = w.members
        val newcomers = members.filter { it !in inLeague && it !in busy }
        // Many newcomers (a new roster, a lot of generated players): place them quickly.
        val share = if (newcomers.size * 10 > members.size) FRIENDLY_RUSH else FRIENDLY_SHARE
        if (newcomers.isNotEmpty() && rng.nextDouble() < share) {
            Matchmaker.pick(l, rng, busy, among = newcomers)?.let { return friendly(it, rng) }
        }
        val cost = { f: yacht.engine.league.LeagueFixture -> (costMs[f.a] ?: 5.0) + (costMs[f.b] ?: 5.0) }
        val cap = maxOf(1, speed.threads(cores) - 1)
        w.next(busy, cupsInFlight, cap, cost)?.let { return it }
        if (w.seasonOver) {
            rollover(w)
            w.next(busy, cupsInFlight, cap, cost)?.let { return it }
        }
        // Fillers only between light members: a heavy one would burn a worker on a game that
        // does not move the season, and hold up its own fixtures.
        val idle = members.filter { it !in busy && it !in heavyKeys }
        return Matchmaker.pick(l, rng, busy + heavyKeys, among = idle)?.let { friendly(it, rng) }
    }

    /**
     * The season is over (under the lock): honours, evolution and new players, the AIs rebuilt,
     * then the next season. Saved at once.
     */
    private fun rollover(w: LeagueWorld) {
        // Next season's divisions and seeds from the converged ratings.
        w.ladder.fit()
        val closing = w.closeSeason()
        if (closing.rosterChanged) rebuildRoster()
        configureWorld(w)
        w.openSeason()
        val news = historyItem(closing.season, closing.honours)
        scope.launch { seasonNews = news }
        saveAsync()
    }

    private fun friendly(pair: Pair<String, String>, rng: Random) = Fixture(Comp.FRIENDLY, -1, pair.first, pair.second, rng.nextLong())

    /** A job that cannot be played (an AI was removed): the side still here wins, off the ladder. */
    private fun forfeit(job: Fixture): JobResult {
        val aHere = job.players.take(maxOf(1, job.lineupA.size)).all { byKey.containsKey(it) }
        val games = listOf(if (aHere) GameScore(1, 0) else GameScore(0, 1))
        val boards = if (job.kind == JobKind.TEAM) job.lineupA.zip(job.lineupB).map { (x, y) -> BoardResult(x, y, games, emptyList()) }
        else listOf(BoardResult(job.a, job.b, games, emptyList()))
        return JobResult(job.copy(rated = false), boards, if (aHere) 1 else -1, 0)
    }

    private suspend fun work(t: Tables, flag: AtomicBoolean, rng: Random) {
        while (!flag.get()) {
            val job = synchronized(lock) {
                nextJob(rng)?.also {
                    busy += it.players
                    live += it.a to it.b
                    if (isCup(it.comp)) cupsInFlight++
                }
            }
            if (job == null) {
                delay(50)
                continue
            }
            publish()
            var recorded = false
            try {
                val result = LeagueMatch.playJob(t.rules, job, { byKey[it] }, config.matchFormat, estimate) { flag.get() } ?: forfeit(job)
                synchronized(lock) {
                    val l = ladder!!
                    if (flag.get()) return@synchronized
                    if (result.fixture.rated) {
                        for (b in result.boards) {
                            if (l.entry(b.a) == null || l.entry(b.b) == null) continue
                            val rec = l.record(b.a, b.b, b.games)
                            lastDelta[rec.a] = rec.deltaA
                            lastDelta[rec.b] = rec.deltaB
                        }
                    }
                    if (job.comp != Comp.FRIENDLY) world?.record(result)
                    recorded = true
                    meter(result)
                    runMatches += result.boards.size
                }
            } catch (e: CancellationException) {
                // stopped mid-match: the match is dropped
            } finally {
                synchronized(lock) {
                    if (!recorded && job.comp != Comp.FRIENDLY) world?.release(job)
                    busy -= job.players.toSet()
                    live -= job.a to job.b
                    if (isCup(job.comp)) cupsInFlight--
                }
            }
            publish()
        }
    }

    /**
     * While the league runs: the full rating fit every [FIT_MS] (solved off the lock; a match
     * only nudges its two players) and a save every [CHECKPOINT_MS] (stop saves at once).
     */
    private suspend fun upkeep(flag: AtomicBoolean) {
        var lastSave = System.currentTimeMillis()
        while (!flag.get()) {
            delay(FIT_MS)
            refit()
            if (System.currentTimeMillis() - lastSave >= CHECKPOINT_MS && !flag.get()) {
                lastSave = System.currentTimeMillis()
                save()
            }
            publish()
        }
    }

    /** The full fit of the ladder when it changed since the last one; the lock is held only to copy and to apply. */
    private fun refit() {
        val (l, input) = synchronized(lock) {
            val l = ladder ?: return
            if (l === fittedLadder && l.version == fittedVersion) return
            if (l.matches == 0L) return
            l to l.fitInput()
        }
        val r = Ladder.solve(input)
        synchronized(lock) {
            if (ladder !== l) return
            l.applyFit(r)
            fittedLadder = l
            fittedVersion = l.version
        }
    }

    /** Knockout and group competitions (capped so the league always has a worker). */
    private fun isCup(c: Comp) = c == Comp.CUP || c == Comp.CHAMPIONS || c == Comp.SUPER || c == Comp.TITLE_PLAYOFF || c == Comp.PROMOTION_PLAYOFF

    /** Updates each AI's ms per game from a finished job (half of each game's time per side). */
    private fun meter(r: JobResult) {
        val games = r.boards.sumOf { it.games.size }
        if (games == 0 || r.nanos == 0L) return
        val perSide = r.nanos / 1e6 / games / 2
        for (k in r.fixture.players) costMs[k] = (costMs[k] ?: perSide) * 0.8 + perSide * 0.2
    }

    /** Changes the league's rules: saved now, applied from the next season. */
    fun changeConfig(c: LeagueConfig) {
        config = c
        val text = c.encode()
        scope.launch(fileWrites) { runCatching { configFile.writeTextAtomically(text) } }
    }

    /** Stops after abandoning the matches in progress, and saves. */
    fun stop() {
        if (!running) return
        stop.set(true)
        running = false
        preparing = null
        val job = runJob
        scope.launch {
            // Leaving the app stops the league and then clears the scope: the last save must
            // not be cancelled with it.
            withContext(kotlinx.coroutines.NonCancellable) {
                job?.join()
                save()
            }
            publish(force = true)
        }
    }

    /** App hidden: no league in the background (battery); it goes on when the app is back. */
    fun onBackground() {
        if (running) resumeOnForeground = true
        stop()
        pauseWatch()
    }

    /** App shown again: the league goes on if it was running when the app was hidden. */
    fun onForeground() {
        if (!resumeOnForeground) return
        resumeOnForeground = false
        start()
    }

    private var resumeOnForeground = false

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
                    world?.let { w ->
                        w.ladder = l
                        w.plan = null
                    }
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
            withContext(rosterWork) {
                // Fresh generated players too (the evolved ones go).
                val players = PlayerBook(baseRoster.map { it.name }).apply { ensureGenerated(config.generated) }
                synchronized(lock) {
                    world?.let { w ->
                        w.players = players
                        w.plan = null
                        w.honours = HonourBook()
                        w.events.clear()
                    }
                    rebuildRoster()
                    world?.let { w -> w.ladder = Ladder(ladderKeys()) }
                    lastDelta.clear()
                    startRanks = emptyMap()
                }
            }
            withContext(fileWrites) {
                for (name in LeagueWorld.FILES) runCatching { File(folder, name).delete() }
            }
            save()
            publish(force = true)
        }
    }

    private suspend fun save() = withContext(Dispatchers.Default) {
        val texts = synchronized(lock) { world?.encodeFiles() } ?: return@withContext
        withContext(fileWrites) { write(texts) }
    }

    /** Saves every league file (under the lock: the encoding needs it). */
    private fun saveAsync() {
        val texts = world?.encodeFiles() ?: return
        scope.launch(fileWrites) { write(texts) }
    }

    private fun write(texts: Map<String, String>) {
        for ((name, text) in texts) runCatching { File(folder, name).writeTextAtomically(text) }
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
            // The watched game is on the ladder: saved now, the league may not be running.
            save()
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
        // The snapshot refits and sorts the whole league (and waits for the lock while a match
        // is recorded): never on the main thread, and one at a time (a request while one runs
        // is folded into one more run after it).
        if (!snapshotRunning.compareAndSet(false, true)) {
            snapshotAgain.set(true)
            return
        }
        scope.launch(Dispatchers.Default) {
            val (v, seq) = try {
                synchronized(lock) { snapshot() to ++snapshots }
            } finally {
                snapshotRunning.set(false)
                if (snapshotAgain.getAndSet(false)) publish(force = true)
            }
            if (v == null) return@launch
            scope.launch {
                // Two snapshots can finish out of order: keep the newer.
                if (seq < shownSnapshot) return@launch
                shownSnapshot = seq
                view = v
                if (running) {
                    val minutes = (System.currentTimeMillis() - runStart) / 60_000.0
                    pace = if (minutes > 0.05) runMatches / minutes else 0.0
                }
            }
        }
    }

    fun name(key: String): String = if (key == Ladder.ME) "나" else byKey[key]?.name ?: key

    private fun snapshot(): LeagueView? {
        val l = ladder ?: return null
        val s = plan
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
                lastDelta[k] ?: 0, rank?.let { r -> startRanks[k]?.let { it - r } }, k in busy, me, s?.league?.divisionOf(k),
            )
        }
        val feed = l.feed.take(FEED).map { m -> FeedItem(m.a, name(m.a), m.b, name(m.b), m.games, m.deltaA, m.deltaB, m.time, m.notes) }
        return LeagueView(rows, feed, l.matches, l.games, live.toList(), s?.let { seasonView(it) })
    }

    private fun seasonView(p: SeasonPlan): SeasonView {
        val s = p.league
        val swap = p.config.autoSwap
        val divisions = s.divisions.indices.map { d ->
            val table = s.table(d)
            val up = if (d > 0) minOf(swap, table.size / 3) else 0
            val down = if (d < s.divisions.size - 1) minOf(swap, table.size / 3) else 0
            val playoff = p.config.promotionPlayoff
            table.mapIndexed { i, st ->
                SeasonRow(
                    st.key, name(st.key), st.played, st.wins, st.draws, st.losses, st.points, st.diff, st.avgScore, st.yachts,
                    when {
                        i < up -> 1
                        i >= table.size - down -> -1
                        playoff && d > 0 && i == up -> 2
                        playoff && d < s.divisions.size - 1 && i == table.size - down - 1 -> -2
                        else -> 0
                    },
                    st.aggPoints,
                )
            }
        }
        val inLeague = s.standings.keys
        return SeasonView(
            s.number, s.fixturesPlayed, s.fixturesTotal, divisions, roster.map { it.key }.filter { it !in inLeague }.map { name(it) },
            honours.seasons.reversed().map { historyItem(it, honours.of(it)) },
            s.positions.map { it.toList() }, s.divisions, s.matchdays, s.currentMatchday,
        )
    }

    /** The head-to-head matrix asked for last (read off the main thread). */
    var h2h by mutableStateOf<H2hMatrix?>(null)
        private set

    /** Loads the matrix between these AIs into [h2h]. */
    fun loadH2h(title: String, keys: List<String>) = loadMatrix(title, keys.map { listOf(it) }, keys.map { name(it) })

    /** Loads the matrix between these groups of AIs (e.g. families) into [h2h]. */
    fun loadMatrix(title: String, groups: List<List<String>>, labels: List<String>) {
        scope.launch(Dispatchers.Default) {
            val m = synchronized(lock) {
                val l = ladder ?: return@synchronized null
                val n = groups.size
                val score = Array(n) { DoubleArray(n) { Double.NaN } }
                val games = Array(n) { IntArray(n) }
                for (i in 0 until n) for (j in 0 until n) {
                    if (i == j) continue
                    var w = 0.0
                    var g = 0
                    for (a in groups[i]) for (b in groups[j]) {
                        if (a == b) continue
                        val (x, y) = l.headToHead(a, b)
                        w += x
                        g += y
                    }
                    games[i][j] = g
                    if (g >= 2) score[i][j] = w / g
                }
                H2hMatrix(title, labels, score, games)
            }
            scope.launch { h2h = m }
        }
    }

    /** The matchday shown in the calendar (일정표), read off the main thread on request. */
    var calendar by mutableStateOf<CalendarView?>(null)
        private set

    /** Loads [matchday] of [division] into [calendar] (null: the current matchday). */
    fun loadCalendar(division: Int, matchday: Int? = null) {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val s = plan?.league ?: return@synchronized null
                if (s.divisions.isEmpty()) return@synchronized null
                val d = division.coerceIn(0, s.divisions.size - 1)
                val md = (matchday ?: s.currentMatchday).coerceIn(0, maxOf(0, s.matchdays - 1))
                val items = s.matchday(md).filter { it.division == d }.map { f ->
                    CalendarItem(f.id, f.a, name(f.a), f.b, name(f.b), s.result(f.id), !s.isPlayed(f.id) && (f.a to f.b) in live)
                }
                CalendarView(d, md, s.matchdays, s.currentMatchday, items)
            }
            scope.launch { calendar = v }
        }
    }

    /** A past season for the hall of fame, from its honours. */
    private fun historyItem(season: Int, h: List<Honour>): SeasonHistoryItem {
        val champions = h.filter { DIVISION.matches(it.kind) }.sortedBy { it.kind }.map { name(it.key) }
        return SeasonHistoryItem(
            season, champions, h.firstOrNull { it.kind == "SCORER" }?.let { name(it.key) }, 0.0,
            h.firstOrNull { it.kind == "YACHT" }?.let { name(it.key) }, 0,
            h.filter { it.kind.startsWith("UP") }.map { name(it.key) }, h.filter { it.kind.startsWith("DOWN") }.map { name(it.key) },
        )
    }

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
    fun files(): List<File> = (LeagueWorld.FILES.map { File(folder, it) } + listOf(configFile, customFile, prefs, favouriteFile)).filter { it.exists() }

    companion object {
        private val DIVISION = Regex("L\\d")

        /** League files a backup restores (when this phone's league has no games yet). */
        val SAVED = LeagueWorld.FILES + listOf("config.txt", "favourites.txt")
        private const val FIT_MS = 2_000L
        private const val CHECKPOINT_MS = 30_000L
        private const val PUBLISH_MS = 300L
        private const val FEED = 60

        /** Share of matches given to AIs waiting for the next season, so they get a rating. */
        private const val FRIENDLY_SHARE = 0.2
        private const val FRIENDLY_RUSH = 0.6

        /** My assumed rating before my first ranked game (a solid amateur). */
        const val START_RATING = 1400.0
    }
}

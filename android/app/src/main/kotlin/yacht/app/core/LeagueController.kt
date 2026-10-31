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
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.EvTable
import yacht.engine.OppModel
import yacht.engine.Player
import yacht.engine.TableIO
import yacht.engine.Tables
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
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.LeagueWorld
import yacht.engine.league.CompetitionView
import yacht.engine.league.Competitions
import yacht.engine.league.NationBook
import yacht.engine.league.Nations
import yacht.engine.league.PlayerMaker
import yacht.engine.league.PlayerDraft
import yacht.engine.league.diff
import yacht.engine.league.Origin
import yacht.engine.league.Club
import yacht.engine.league.ClubLeague
import yacht.engine.league.SeasonReview
import yacht.engine.league.LiveFrame
import yacht.engine.league.BoxProfile
import yacht.engine.league.LabResult
import yacht.engine.league.MatchFormat
import yacht.engine.league.TieRule
import yacht.engine.league.MatchPreview
import yacht.engine.league.Odds
import yacht.engine.league.OddsTable
import yacht.engine.league.Preview
import yacht.engine.league.Scouting
import yacht.engine.league.ScoutingReport
import yacht.engine.league.boxProfile
import yacht.engine.league.PlayerBook
import yacht.engine.league.PlayerRecord
import yacht.engine.league.leagueAi
import yacht.engine.league.WinEstimate
import yacht.engine.league.TierName
import yacht.engine.league.GameScore
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueAi
import yacht.engine.league.LeagueMatch
import yacht.engine.league.LogSink
import yacht.engine.league.MatchRef
import yacht.engine.league.LeagueRoster
import yacht.engine.league.Matchmaker
import yacht.engine.league.LeagueTables
import yacht.engine.league.Tier
import yacht.engine.league.Coefficients
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueIds
import yacht.engine.league.LeagueRanking
import yacht.engine.league.MemoryCoefficients
import yacht.engine.league.Pyramid
import yacht.engine.league.Season
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** How many cores the league may use (battery). */
/** What the league does while the app is hidden. */
enum class LeagueBackground(val label: String) {
    /** It stops (battery) and goes on when the app is back. */
    PAUSE("나가면 멈춤"),

    /** It keeps playing: the app shows a notification and keeps the phone awake while it runs. */
    KEEP("나가도 계속"),
}

/** How far ▶ goes: the week in play, or on and on until ■. */
enum class LeagueRunMode(val label: String) {
    /** It plays the week in play (the league round and the week's cup matches) and stops. */
    WEEK("1주씩"),

    /** It plays on, week after week and season after season, until stopped. */
    ON("계속"),
}

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
    private val backgroundFile = File(folder, "background.txt")
    private val runModeFile = File(folder, "runmode.txt")

    /** Present while the league should be running: it goes on by itself the next time the app opens. */
    private val resumeFile = File(folder, "resume.txt")
    private val favouriteFile = File(folder, "favourites.txt")
    private val uiFile = File(folder, UiPrefs.FILE)
    private val logFile = File(folder, MatchCenter.LOGS)
    private val cores = Runtime.getRuntime().availableProcessors()

    var view by mutableStateOf<LeagueView?>(null)
        private set
    var running by mutableStateOf(false)
        private set

    /** What the league does while the app is hidden (saved). */
    var background by mutableStateOf(LeagueBackground.PAUSE)
        private set

    /** Why the league stopped by itself (errors), shown on its screen until the next start. */
    var stopReason by mutableStateOf<String?>(null)
        private set

    /** How far ▶ goes (saved). */
    var runMode by mutableStateOf(LeagueRunMode.ON)
        private set

    /** A 1주씩 run that played its week: what it played, shown until the next start. */
    var weekDone by mutableStateOf<String?>(null)
        private set

    /**
     * The season and week a 1주씩 run plays (null: it plays on): set under the lock by a ▶ or
     * a change of mode, kept by a restart that resumes (the app back, a speed change), cleared
     * by the player's ■.
     */
    @Volatile
    private var weekGoal: Pair<Int, Int>? = null

    /** Why a run stopped by itself on an error (shown in red); null otherwise. */
    @Volatile
    private var endedBy: String? = null

    /** What a 1주씩 run played when it stopped by itself; null otherwise. */
    @Volatile
    private var endedWeek: String? = null

    /** A stuck run was told to the app (once per run). */
    @Volatile
    private var stallTold = false

    /** Told about every error the league meets while it runs (the app logs it). */
    var onError: (Throwable) -> Unit = {}

    /** Errors in a row while running (reset by every recorded match). */
    private val failures = java.util.concurrent.atomic.AtomicInteger()

    /** Progress text while the extra tables are built (first start only). */
    var preparing by mutableStateOf<String?>(null)
        private set
    var speed by mutableStateOf(LeagueSpeed.NORMAL)
        private set

    /** Matches per minute in this run. */
    var pace by mutableStateOf(0.0)
        private set

    /**
     * What the league screens show (tabs, sheets, pages, filters): all of it, so the harness can
     * set any screen. Main thread only: [shownLeague] keeps a copy of its league for the snapshot.
     */
    val nav: LeagueNav = LeagueNav {
        shownLeague = nav.league
        publish(force = true)
        // The odds of a league picked now come at once (while the league runs, the upkeep keeps them).
        scope.launch(Dispatchers.Default) { runCatching { refreshOdds() } }
    }

    /** [nav]'s league, for the snapshot off the main thread. */
    @Volatile
    private var shownLeague: String? = null

    /**
     * The player whose page is on top, for the player dialogs until the pages replace them:
     * a key opens its page, null closes the top player page.
     */
    @Deprecated("A transition to the pages: use nav", ReplaceWith("nav.page"))
    var detail: String?
        get() = (nav.page as? Page.Player)?.key
        set(key) {
            if (key != null) nav.open(Page.Player(key)) else (nav.page as? Page.Player)?.let { nav.close(it) }
        }

    /** UI preferences saved in league/ui.txt (dismissed banners, the watch pace). */
    var uiPrefs by mutableStateOf(UiPrefs())
        private set

    /** Changes the UI preferences and saves them. */
    fun updateUiPrefs(change: (UiPrefs) -> UiPrefs) {
        val p = change(uiPrefs)
        if (p == uiPrefs) return
        uiPrefs = p
        val text = p.encode()
        // Not over a restore's files: the backup's preferences replace these.
        scope.launch(fileWrites) { if (!restoring) runCatching { uiFile.writeTextAtomically(text) } }
    }

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

    /** Time spent holding the lock, by what held it (for the stress harness and tuning). */
    val timings = LockTimings()

    /** Lab jobs in flight (up to half the workers). */
    private var labInFlight = 0

    /** Legends rebuilt for the lab (they are not in the roster). */
    private val legendAis = HashMap<String, LeagueAi>()

    /** A roster AI, or a legend rebuilt from its genes. */
    private fun aiOf(key: String): LeagueAi? = byKey[key] ?: synchronized(lock) {
        legendAis[key] ?: run {
            val t = tables ?: return@run null
            val x = extra ?: return@run null
            world?.players?.legend(key)?.let { leagueAi(it, t, x) }?.also { legendAis[key] = it }
        }
    }

    /** The league's rules (saved); structural changes apply from the next season. */
    var config by mutableStateOf(LeagueConfig())
        private set

    /** The seed a new league's first season is drawn from (fixed by the harness, to measure the same league again). */
    internal var leagueSeed: () -> Long = System::nanoTime

    private val lock = Any()
    private val throttle = Any()
    private val busy = HashSet<String>()
    /** Jobs being played, by their two sides. */
    private val live = LinkedHashMap<Pair<String, String>, Fixture>()
    private val lastDelta = HashMap<String, Int>()
    private var startRanks: Map<String, Int> = emptyMap()
    private var stop = AtomicBoolean(true)
    /**
     * [stop] and [running] change together under this: a run that ends (on a worker thread)
     * must not take the next run's [running] for its own (the run being set going meanwhile).
     */
    private val runGate = Any()
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

    /** What the helpers below reach the league by (read when called: some fields are set later). */
    private val host = object : LeagueHost {
        override val scope: CoroutineScope get() = this@LeagueController.scope
        override val lock: Any get() = this@LeagueController.lock
        override val folder: File get() = this@LeagueController.folder
        override fun world(): LeagueWorld? = world
        override fun tables(): Tables? = tables
        override fun extra(): LeagueTables? = extra
        override fun estimate(): WinEstimate? = estimate
        override fun ai(key: String): LeagueAi? = aiOf(key)

        override fun aiFrom(key: String, spec: CustomAiSpec): LeagueAi? {
            val t = tables ?: return null
            val x = extra ?: return null
            return leagueAi(PlayerRecord(key, name(key), "", Origin.of(key), 1, null, null, spec), t, x)
        }

        override fun adaptive(key: String): Boolean =
            key == LeagueRoster.ADAPTIVE || key == CustomAis.CLONE || key in DUEL_AIS || aiOf(key)?.adapts == true || customs.any { it.key == key && it.adapts }

        override fun name(key: String): String = this@LeagueController.name(key)

        override fun shown(key: String): String {
            val flag = synchronized(lock) { world?.players?.get(key)?.identity?.nation }?.let { Nations.of(it)?.flag }
            return if (flag == null) name(key) else "$flag ${name(key)}"
        }

        override fun badge(key: String): ClubBadge? = null

        // A national season's league by name ("한국 리그"); none in a legacy season (its divisions say "3부" alone).
        override fun leagueLabel(key: String): String? =
            synchronized(lock) { world?.plan?.league?.leagueOf(key) }?.takeIf { it.national }?.let { LeagueIds.label(it.id) }
        override fun favourites(): Set<String> = favourites
        override fun view(): LeagueView? = view
        override fun running(): Boolean = running
        override fun startOneWeek(): Unit = this@LeagueController.startOneWeek()

        override fun requestSave() {
            scope.launch { save() }
        }

        override fun oppModel(key: String): OppModel? {
            val t = tables ?: return null
            return synchronized(lock) { world?.oppModel(t, key) }
        }

        override fun members(): Int = synchronized(lock) { world?.members?.size ?: 0 }

        override fun leagueRank(id: String): Int = synchronized(lock) {
            val p = world?.plan ?: return 0
            if (!p.league.leagues.national) return 0
            selector(p).rank(id).let { if (it == Int.MAX_VALUE) it else it - 1 }
        }

        override fun publish() = this@LeagueController.publish(force = true)

        override fun preview(a: String, b: String, fixture: Fixture?): MatchPreview? = synchronized(lock) { world?.let { previewOf(it, a, b, fixture) } }

        override fun watchPace(): WatchPace = uiPrefs.watchPace

        override fun saveWatchPace(p: WatchPace) {
            scope.launch { updateUiPrefs { it.copy(watchPace = p) } }
        }
    }

    /** Watching real matches: reports, replays, the match log book and the broadcast channel. */
    val center: MatchCenter = MatchCenter(host)

    /** Player pages, leaderboards, reviews and match lists, on request. */
    val analytics: LeagueAnalytics = LeagueAnalytics(host)

    /** Competition, club, league, ranking and federation pages, on request. */
    val worldPages: WorldPages = WorldPages(host)

    /** The home dashboard. */
    val home: HomeFeed = HomeFeed(host)

    init {
        runCatching {
            prefs.takeIf { it.exists() }?.readText()?.trim()?.let { v -> LeagueSpeed.entries.firstOrNull { it.name == v }?.let { speed = it } }
        }
        runCatching {
            backgroundFile.takeIf { it.exists() }?.readText()?.trim()?.let { v -> LeagueBackground.entries.firstOrNull { it.name == v }?.let { background = it } }
        }
        runCatching {
            runModeFile.takeIf { it.exists() }?.readText()?.trim()?.let { v -> LeagueRunMode.entries.firstOrNull { it.name == v }?.let { runMode = it } }
        }
        customs = runCatching { customFile.takeIf { it.exists() }?.readLines()?.mapNotNull { CustomAiSpec.decode(it) } }.getOrNull().orEmpty()
        config = readConfig()
        favourites = readFavourites()
        uiPrefs = readUiPrefs()
    }

    /**
     * The saved rules, or the defaults. Rules saved before the pyramid with the old default of
     * 200 players take today's default (a league of thousands): the players come in at the
     * next season's close and the league becomes a pyramid. Rules saved before 판 승점제 move
     * to it and to series and double-elimination knockouts ([LeagueConfig.migrated]) from the
     * next season (the season in play keeps its own).
     */
    private fun readConfig(): LeagueConfig = runCatching {
        configFile.takeIf { it.exists() }?.readText()?.let { text ->
            val m = LeagueConfig.migrated(text)
            val c = if (!text.lines().any { it.startsWith("pyramid ") } && m.generated == OLD_DEFAULT_PLAYERS) m.copy(generated = LeagueConfig.DEFAULT_PLAYERS) else m
            // Moved on once: saved as version 2 (the season in play keeps its own copy).
            if (LeagueConfig.versionOf(text) < LeagueConfig.VERSION) runCatching { configFile.writeTextAtomically(c.encode()) }
            c
        }
    }.getOrNull() ?: LeagueConfig()

    private fun readFavourites(): Set<String> =
        runCatching { favouriteFile.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() }.getOrNull().orEmpty()

    /** league/ui.txt, or the defaults (missing or unreadable). */
    private fun readUiPrefs(): UiPrefs = UiPrefs.decode(runCatching { uiFile.takeIf { it.exists() }?.readText() }.getOrNull())

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
            try {
                readLeague()
            } finally {
                restoring = false
            }
        }
    }

    /** Reads the league files into a new world (on [rosterWork]). */
    private fun readLeague() {
        val texts = LeagueWorld.FILES.associateWith { name -> runCatching { File(folder, name).takeIf { it.exists() }?.readText() }.getOrNull() }
        // As at start-up: no rules file means the default rules (a restore removes this phone's).
        val cfg = readConfig()
        val reserved = baseRoster.map { it.name }
        // The generated and evolved players first: they are AIs of the roster, so the
        // ladder and the season read with them.
        val players = texts[LeagueWorld.ROSTER]?.let { runCatching { PlayerBook.decode(it, reserved) }.getOrNull() } ?: PlayerBook(reserved)
        // Players added mid-league debut in the season being played.
        val season = texts[LeagueWorld.SEASON]?.let { t -> Regex("(?m)^number (\\d+) ").find(t)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
        // Up to the setting (a raised count joins now), but not while a closed season waits to
        // be closed again after a kill: that close tops up with the rules it was first closed with.
        val closing = texts[LeagueWorld.SEASON]?.contains("\nclosecfg ") == true
        if (!closing && players.size < cfg.generated) players.ensureGenerated(cfg.generated, season, cfg.generated - players.size)
        synchronized(lock) { pendingPlayers = players }
        rebuildRoster()
        val w = synchronized(lock) { LeagueWorld.decode(texts, ladderKeys(), reserved) }
        w.players = players
        val logs = runCatching { logFile.takeIf { it.exists() }?.readBytes() }.getOrNull()
        synchronized(lock) {
            world = w
            pendingPlayers = null
            // The rules just read (the controller's own reach the screen below).
            configureWorld(w, cfg)
            legendAis.clear()
            oddsNow = emptyMap()
            // The match logs of this league (another league's, or none: an empty book).
            runCatching { center.load(logs) }
        }
        val first = !loaded.isCompleted
        loaded.complete(Unit)
        // Running when the app was last closed (or killed): it goes on by itself, once the app is
        // in view (hidden while loading, it waits for the app to come back: the 나가면 멈춤
        // choice, and Android does not let a hidden app start the 나가도 계속 service).
        if (first && resumeFile.exists()) scope.launch { if (visible) start(resume = true) else resumeOnForeground = true }
        // A restore may have brought the rules and the favourites too.
        val favs = readFavourites()
        val rv = synchronized(lock) { w.archive.pendingReview }
        // A restored league is shown from its own season (at start-up the screen is not open yet).
        val shown = if (first) null else synchronized(lock) { followedDivision(w, favs) }
        scope.launch {
            config = cfg
            favourites = favs
            review = rv
            seasonNews = null
            if (!first) {
                form = null
                nav.week = null
                showNewSeason(shown)
                // The channel's view went with the league replaced: asked for again at the next snapshot.
                pickedWeek = null
            }
        }
        publish(force = true)
    }

    /**
     * Takes a backup's league files (this league has no games): the league stops, the files
     * replace every saved one (a file the backup lacks is removed) and the league is read again.
     * Saves of the league being replaced that are still on their way are dropped.
     */
    suspend fun restore(files: Map<String, ByteArray>) {
        restoring = true
        try {
            stop()
            runJob?.join()
            withContext(fileWrites) {
                for (name in SAVED) {
                    val f = File(folder, name)
                    runCatching { files[name]?.let { f.writeAtomically(it) } ?: f.delete() }
                }
            }
            center.clear()
        } catch (e: Throwable) {
            restoring = false
            throw e
        }
        // The backup's UI preferences (the defaults when it has none).
        uiPrefs = readUiPrefs()
        reload()
    }

    /** True from a restore until its league is read: no save may write the old league over it. */
    @Volatile
    private var restoring = false

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
            // The book being read first (a restore reads a new one while the old world is here).
            val players = pendingPlayers ?: world?.players
            val generated = players?.alive.orEmpty().map { leagueAi(it, t, x) }
            val all = mine + baseRoster + generated
            roster = all
            byKey = all.associateBy { it.key }
            players?.let { b ->
                pastNames = (b.retired.map { it.key to it.name } + b.legends.map { it.key to it.name }).toMap()
                legendsNow = b.legends.map { it.key to it.name }
            }
            heavyKeys = all.filter { it.heavy }.map { it.key }.toSet()
            val keys = ladderKeys()
            world?.let { w ->
                if (w.ladder.keys != keys) w.ladder = w.ladder.rekey(keys)
                configureWorld(w)
            }
        }
    }

    /** What the world takes from the roster and the rules: the members, families and config. */
    private fun configureWorld(w: LeagueWorld, c: LeagueConfig = config) {
        w.config = c
        w.family = { k -> byKey[k]?.family }
        // An AI deleted mid-season (a custom AI, the clone gone) places nowhere at its end.
        w.present = { k -> k in byKey }
        w.name = { k -> name(k) }
        // What a highlight replay checks: an AI's play (its description and, for players, its genes).
        w.version = { k -> (byKey[k]?.desc ?: "") + (customs.firstOrNull { it.key == k }?.encode() ?: "") + (w.players[k]?.spec?.encode()?.substringAfter('|')?.substringAfter('|') ?: "") }
        w.members = members(w, c)
    }

    /**
     * Next season's AIs: everyone but me, or only the AIs with mistakes (대혼돈), or the best
     * rated (강자만); my own AIs and the players I made always play.
     */
    private fun members(w: LeagueWorld, c: LeagueConfig): List<String> {
        val made = { k: String -> w.players[k]?.origin == Origin.CREATED }
        var keys = roster.filter { ai ->
            val player = w.players[ai.key]
            // The invited AIs play when invited, whatever else the rules say.
            if (ai.key in LeagueRoster.GUESTS) return@filter c.guests
            // No generated players asked for: the book's players sit out (they stay on the ladder).
            // The hand-made AIs play only when asked (they stay for ranked games and the lab).
            made(ai.key) || (player == null || c.generated > 0) && (c.baseAis || Origin.of(ai.key) != Origin.BASE) &&
                (!c.onlyFlawed || ai.family == Family.MINE || ai.family in LeagueWorld.FLAWED || player?.spec?.flawed == true)
        }.map { it.key }
        if (c.onlyTop > 0) {
            val mine = keys.filter { byKey[it]?.family == Family.MINE || made(it) }
            keys = (keys.filter { it !in mine }.sortedByDescending { w.rating(it) }.take(c.onlyTop) + mine)
        }
        return keys
    }

    private fun tableFile(key: String) = File(folder, "$key.bin")

    fun changeSpeed(s: LeagueSpeed) {
        speed = s
        scope.launch(fileWrites) { runCatching { prefs.writeTextAtomically(s.name) } }
        if (running) {
            stop(keepResume = true)
            start(resume = true)
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
            w.ladder = w.ladder.rekey(keys - key).rekey(keys)
        }
        scope.launch { save() }
    }

    fun deleteCustom(id: String) {
        customs = customs.filter { it.id != id }
        persistCustoms()
        rosterChanged()
    }

    // ---- 선수 만들기 -------------------------------------------------------------------------------

    /** The season a player made now belongs to (its age is counted from it). */
    private fun seasonNow(w: LeagueWorld): Int = w.plan?.number ?: (w.players.evolvedSeason + 1)

    /** [key] (a league player: generated, evolved or made) as a draft to edit; null for any other AI. */
    fun draftOf(key: String): PlayerDraft? = synchronized(lock) {
        val w = world ?: return null
        val p = w.players[key] ?: return null
        PlayerMaker.of(p, seasonNow(w))
    }

    /** [draftOf] off the main thread (the lock may be held by a season's close for a while). */
    suspend fun loadDraft(key: String): PlayerDraft? = withContext(Dispatchers.Default) { draftOf(key) }

    /** True when made player [key] retires at this season's end (은퇴시키기). */
    fun isLeaving(key: String): Boolean = synchronized(lock) { world?.players?.get(key)?.leaving == true }

    /** A drawn player to start from (🎲 무작위), named apart from everyone. */
    fun randomDraft(): PlayerDraft {
        val taken = synchronized(lock) { world?.players?.names().orEmpty() }
        return PlayerMaker.random(java.util.Random(), taken)
    }

    /** [randomDraft] off the main thread. */
    suspend fun drawDraft(): PlayerDraft = withContext(Dispatchers.Default) { randomDraft() }

    /** [savePlayer] off the main thread. */
    suspend fun storePlayer(key: String?, draft: PlayerDraft): String? = withContext(Dispatchers.Default) { savePlayer(key, draft) }

    /**
     * Saves [draft]: a new league player ([key] null), or [key] remade (its records stay). It
     * plays friendlies until next season, which it starts in the lowest division like any
     * newcomer. Returns why it could not be saved (해요체), or null.
     */
    fun savePlayer(key: String?, draft: PlayerDraft): String? {
        synchronized(lock) {
            val w = world ?: return "리그를 불러오는 중이에요. 잠시 뒤에 다시 해 주세요."
            val b = w.players
            val own = key?.let { b[it]?.name }
            PlayerMaker.problem(draft, b.names() - setOfNotNull(own))?.let { return it }
            if (key == null) b.make(draft, seasonNow(w)) else b.edit(key, draft, seasonNow(w)) ?: return "이 선수는 이제 리그에 없어요."
        }
        rosterChanged()
        scope.launch { save() }
        return null
    }

    /**
     * Lets made player [key] go ([on], 은퇴시키기) or keeps it after all: it plays the season out
     * (its national team and club keep it) and retires at its end, its records kept. Off the
     * main thread; false when it is not a made player in the league.
     */
    suspend fun retirePlayer(key: String, on: Boolean = true): Boolean = withContext(Dispatchers.Default) {
        val done = synchronized(lock) { world?.players?.leave(key, on) == true }
        if (done) {
            save()
            publish(force = true)
        }
        done
    }

    private fun persistCustoms() {
        val text = customs.joinToString("\n") { it.encode() }
        scope.launch(fileWrites) { runCatching { customFile.writeTextAtomically(text) } }
    }

    // ---- running ---------------------------------------------------------------------------------

    /**
     * Starts (or resumes) the league. [resume]: it goes on by itself (the app back, a speed
     * change, a relaunch), so a 1주씩 run keeps the week it was playing.
     */
    fun start(resume: Boolean = false): Unit = start(resume, oneWeek = false)

    /** ▶ for one week whatever the run mode (which is not changed): the week in play, then it stops. */
    fun startOneWeek(): Unit = start(resume = false, oneWeek = true)

    private fun start(resume: Boolean, oneWeek: Boolean) {
        val t = tables ?: return
        val flag = AtomicBoolean(false)
        synchronized(runGate) {
            if (running || ladder == null || restoring) return
            stop = flag
            running = true
        }
        stopReason = null
        weekDone = null
        endedBy = null
        endedWeek = null
        stallTold = false
        failures.set(0)
        lastRecord = System.currentTimeMillis()
        scope.launch(fileWrites) { runCatching { resumeFile.writeTextAtomically("1") } }
        val previous = runJob
        runStart = System.currentTimeMillis()
        runMatches = 0
        pace = 0.0
        val threads = speed.threads(cores)
        runJob = scope.launch {
            try {
                // A restart (speed change) waits for the old workers; ranks and the first season
                // need the lock the workers hold: both off the main thread.
                previous?.join()
                withContext(Dispatchers.Default) {
                    synchronized(lock) {
                        val w = world ?: return@synchronized
                        val l = w.ladder
                        startRanks = l.keys.mapNotNull { k -> l.rankOf(k)?.let { k to it } }.toMap()
                        if (w.plan == null) openFirstSeason(w)
                        // A run going on by itself keeps its week (a one-week run in 계속 too).
                        weekGoal = when {
                            oneWeek -> w.plan?.let { it.number to it.week }
                            resume && weekGoal != null -> weekGoal
                            runMode != LeagueRunMode.WEEK -> null
                            else -> w.plan?.let { it.number to it.week }
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
                // A roomy stack: Android's default is small, and a StackOverflowError in one
                // match must not end the run.
                val pool = Executors.newFixedThreadPool(threads) { job -> Thread(null, job, "yacht-league", WORKER_STACK).apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
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
            } catch (e: CancellationException) {
                // A worker failed outside its guards (its error cancelled the run): say which.
                val cause = generateSequence(e.cause) { it.cause }.firstOrNull { it !is CancellationException }
                if (cause != null && endedBy == null) endedBy = reasonOf(cause)
                throw e
            } catch (e: Throwable) {
                // Preparing the tables or starting the season failed: shown, not a silent stop.
                withContext(kotlinx.coroutines.NonCancellable) { onError(e) }
                endedBy = reasonOf(e)
                clearResume()
            } finally {
                // A run that ended by itself (errors, its week played) must not leave the screen
                // showing a league running with no one playing, nor end without a word or a save.
                withContext(kotlinx.coroutines.NonCancellable) {
                    val ended = synchronized(runGate) {
                        (stop === flag && running).also { mine ->
                            if (mine) {
                                flag.set(true)
                                running = false
                            }
                        }
                    }
                    if (ended) {
                        preparing = null
                        // Why: an error, or its week played (said in green), else that it stopped.
                        val why = endedBy
                        val week = endedWeek
                        when {
                            why != null -> stopReason = why
                            week != null -> weekDone = week
                            else -> stopReason = "리그가 저절로 멈췄어요. ▶를 누르면 다시 해요."
                        }
                        scope.launch {
                            withContext(kotlinx.coroutines.NonCancellable) { save() }
                            publish(force = true)
                        }
                    }
                }
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
        if (w.plan == null) openFirstSeason(w)
        val p = w.plan ?: return null
        val inLeague = p.league.standings.keys
        val members = w.members
        // Newcomers still in placement (a new roster, a lot of generated players): placed
        // quickly, against light AIs (a heavy one would hold up its own league fixtures).
        val newcomers = members.filter { it !in inLeague && it !in busy && !l.placed(it) }
        val share = if (newcomers.size * 10 > members.size) FRIENDLY_RUSH else FRIENDLY_SHARE
        // 1주씩 past its week: only what is left of the week (no friendlies or lab games, which
        // would be given back and keep the week's own fixtures from being tried).
        val past = weekGoal?.let { (season, week) -> p.number != season || p.week > week } == true
        if (!past && newcomers.isNotEmpty() && rng.nextDouble() < share) {
            Matchmaker.pick(l, rng, busy + (heavyKeys - newcomers.toSet()), among = newcomers)?.let { return friendly(it, rng) }
        }
        // 기준 경기: without the hand-made AIs in the league, the max-expectation AI (1500 by
        // definition) plays one now and then against a member near 1500, so the scale stays put.
        if (!past && !w.config.baseAis && ANCHOR !in busy && rng.nextDouble() < CALIBRATION_SHARE) {
            val near = members.filter { it !in busy && l.placed(it) }
            Matchmaker.opponentFor(l, 1500.0, rng, among = near)?.let { return friendly(ANCHOR to it, rng, CALIBRATION_LABEL) }
        }
        // AI 연구소: up to half the workers on the experiment, alongside the league; a single
        // worker plays the two by turns.
        val labCap = speed.threads(cores) / 2
        if (!past && if (labCap > 0) labInFlight < labCap else rng.nextBoolean()) w.lab.next()?.let { return it }
        val cost = { f: yacht.engine.league.LeagueFixture -> (costMs[f.a] ?: 5.0) + (costMs[f.b] ?: 5.0) }
        val cap = maxOf(1, speed.threads(cores) - 1)
        w.next(busy, cupsInFlight, cap, cost, lastWeek(w))?.let { return it }
        if (w.seasonOver) {
            rollover(w)
            w.next(busy, cupsInFlight, cap, cost, lastWeek(w))?.let { return it }
        }
        // Nothing official is free: the worker waits. (Friendlies to fill the wait went to the
        // light AIs only, a heavy one holding up its own fixtures, so the light ones piled up
        // thousands more games than the heavy ones: not fair. Waiting also saves battery.)
        return null
    }

    /**
     * The season is over (under the lock): honours, evolution and new players, the AIs rebuilt,
     * then the next season. Saved at once.
     */
    private fun rollover(w: LeagueWorld) {
        // Next season's divisions and seeds from the converged ratings.
        val t0 = System.nanoTime()
        // The rules saved during the season apply from here (evolution, generated players).
        // A close replayed after a kill keeps the rules of the first one (saved with the
        // finished season), even if they were changed before the league resumed.
        val p = w.plan ?: return
        val rules = p.closing ?: config
        p.closing = rules
        configureWorld(w, rules)
        w.ladder.fit()
        // The finished season on disk first: killed while the close is saved, the league reads
        // the finished season again and closes it the same way (not a checkpoint from before).
        saveAsync()
        val closing = w.closeSeason()
        // The match logs: last season's finals, highlights and picks kept, its best games archived.
        runCatching { center.closing(w, p) }
        if (closing.rosterChanged) rebuildRoster()
        // The next season: members and clubs as closed (they must replay the same), its shape
        // (divisions, format, competitions) from the rules as they are now.
        configureWorld(
            w,
            config.copy(
                generated = rules.generated, aging = rules.aging, childShare = rules.childShare, baseAis = rules.baseAis, guests = rules.guests,
                onlyFlawed = rules.onlyFlawed, onlyTop = rules.onlyTop,
                clubs = rules.clubs, squad = rules.squad,
            ),
        )
        w.openSeason()
        timings.note("rollover", System.nanoTime() - t0)
        val news = historyItem(closing.season, closing.honours)
        val rv = w.archive.pendingReview
        val shown = followedDivision(w, favourites)
        scope.launch {
            seasonNews = news
            if (rv != null) review = rv
            showNewSeason(shown)
        }
        saveAsync()
    }

    /** The first season of a new league (under the lock); the 리그 tab then opens on it. */
    private fun openFirstSeason(w: LeagueWorld) {
        configureWorld(w)
        w.openFirstSeason(leagueSeed())
        val shown = followedDivision(w, favourites)
        scope.launch { showNewSeason(shown) }
    }

    /**
     * The division of [w]'s season a new season opens on (under the lock): in the league it shows
     * ([shownOf]: in a national season the one picked, else a followed player's), the first with
     * one of [favs], else its 1부; as its tier and group there, null without a season.
     */
    private fun followedDivision(w: LeagueWorld, favs: Set<String>): Pair<Int, Int>? {
        val p = w.plan ?: return null
        val s = p.league
        // The default league of these favourites (after a restore, the backup's, not the ones still in memory).
        val lg = shownOf(p, selector(p, favs))?.let { s.leagues.byId(it) } ?: return null
        if (lg.size == 0) return null
        val d = lg.divisions.firstOrNull { i -> s.divisions[i].any { it in favs } } ?: lg.first
        return lg.tiers.tier(d - lg.first) to lg.tiers.group(d - lg.first)
    }

    /**
     * The 리그 tab on a new season (main thread): [division] (tier, group; null: 1부) at its
     * current round, the chart with its default players, as the tab showed a season it had not
     * shown before.
     */
    private fun showNewSeason(division: Pair<Int, Int>?) {
        val (tier, group) = division ?: (0 to 0)
        nav.tier = tier
        nav.group = group
        nav.round = null
        nav.chartKeys = emptyList()
    }

    private fun friendly(pair: Pair<String, String>, rng: Random, label: String = "") =
        Fixture(Comp.FRIENDLY, -1, pair.first, pair.second, rng.nextLong(), label = label)

    /**
     * A job that failed twice (it would fail the same way every time: the deal is fixed): settled
     * level and off the ladder, so the season can go past it (a level knockout tie sends side A,
     * the better seed, through). No games: nothing is counted in the statistics.
     */
    private fun void(job: Fixture): JobResult {
        val games = listOf(GameScore(0, 0))
        val boards = if (job.kind == JobKind.TEAM) job.lineupA.zip(job.lineupB).map { (x, y) -> BoardResult(x, y, games, emptyList()) }
        else listOf(BoardResult(job.a, job.b, games, emptyList()))
        return JobResult(job.copy(rated = false), boards, 0, 0)
    }

    /** Failures per season fixture this run ("season:comp:id"), under the lock. */
    private val jobFailures = HashMap<String, Int>()

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
            var seasonFormat = config.matchFormat
            // 상대 맞춤 AIs read their opponents' records as they stand when the job is taken.
            var models: Map<String, OppModel?> = emptyMap()
            val job = try {
                synchronized(lock) { takeJob(rng) { f -> seasonFormat = f }?.also { models = scoutFor(it, t) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Even the next fixture failed (a season that cannot close): retried a few times,
                // then the league stops and says why.
                if (failed(e, flag)) break
                delay(ERROR_PAUSE_MS)
                continue
            }
            if (job == null) {
                if (weekPlayed(flag)) break
                delay(50)
                continue
            }
            publish()
            var recorded = false
            // Every decision of a season match, for the match logs (the lab's and friendlies' are not kept).
            val sink = if (job.comp == Comp.LAB || job.comp == Comp.FRIENDLY) null else LogSink()
            try {
                // The lab always plays mirrored matches (its statistics assume them).
                val format = if (job.comp == Comp.LAB) MatchFormat.MIRRORED else seasonFormat
                val played = LeagueMatch.playJob(t.rules, job, { aiOf(it) }, format, estimate, { flag.get() }, scout = { models[it] }, log = sink)
                // A lab AI that is gone ends the experiment (a forfeit would count as a result).
                if (played == null && job.comp == Comp.LAB) {
                    if (!flag.get()) {
                        synchronized(lock) { world?.lab?.stop() }
                        publishLab()
                    }
                    continue
                }
                val result = played ?: forfeit(job)
                synchronized(lock) {
                    val w = world ?: return@synchronized
                    if (flag.get()) return@synchronized
                    // Statistics, the ladder and the season, in that order (see LeagueWorld.recordJob).
                    val t0 = System.nanoTime()
                    val rec = w.recordJob(result, System.currentTimeMillis())
                    timings.note("record", System.nanoTime() - t0)
                    for (m in rec.matches) {
                        lastDelta[m.a] = m.deltaA
                        lastDelta[m.b] = m.deltaB
                    }
                    // Its refs for the feed (until the ladder keeps them), and its logs when it is worth keeping:
                    // extras of a match already recorded (a failure here must not give it back to the season).
                    if (played != null) runCatching { refsOf(result, rec.matches) }
                    if (played != null) runCatching { center.offer(result, sink) }.onFailure { e -> scope.launch { onError(e) } }
                    recorded = true
                    lastRecord = System.currentTimeMillis()
                    meter(result)
                    runMatches += result.boards.size
                }
                failures.set(0)
            } catch (e: CancellationException) {
                // stopped mid-match: the match is dropped
            } catch (e: Throwable) {
                // This match failed: it is given back to the season and the league goes on; the
                // second time (it fails the same way: its deal is fixed) it is settled level.
                if (job.comp != Comp.LAB && job.comp != Comp.FRIENDLY) synchronized(lock) {
                    val w = world
                    val key = "${w?.plan?.number}:${job.comp.code}:${job.id}"
                    val n = (jobFailures[key] ?: 0) + 1
                    jobFailures[key] = n
                    if (n >= 2 && w != null && !flag.get()) {
                        recorded = runCatching { w.recordJob(void(job), System.currentTimeMillis()) }.isSuccess
                        if (recorded) jobFailures.remove(key)
                    }
                }
                if (!failed(e, flag) && !recorded) delay(ERROR_PAUSE_MS)
            } finally {
                synchronized(lock) {
                    if (!recorded && job.comp != Comp.FRIENDLY) world?.release(job)
                    // A competition's view may have moved (see CompsKey).
                    if (recorded && job.comp != Comp.LEAGUE && job.comp != Comp.FRIENDLY && job.comp != Comp.LAB) compResults++
                    if (job.comp == Comp.LAB) labInFlight-- else {
                        busy -= job.players.toSet()
                        live.remove(job.a to job.b)
                    }
                    if (isCup(job.comp)) cupsInFlight--
                    if (job.kind == JobKind.TEAM) world?.let { w -> w.teamsInFlight = maxOf(0, w.teamsInFlight - 1) }
                }
            }
            publish()
        }
    }

    /**
     * The opponent models a job's 상대 맞춤 AIs play by (under the lock): every side's record
     * when one of them adapts, else none (the model is not worked out for nothing).
     */
    private fun scoutFor(job: Fixture, t: Tables): Map<String, OppModel?> {
        val w = world ?: return emptyMap()
        val keys = job.players
        if (keys.none { aiOf(it)?.adapts == true }) return emptyMap()
        return keys.associateWith { w.oppModel(t, it) }
    }

    /** The next job, marked as taken (under the lock); [format] gets the season's match format. */
    private fun takeJob(rng: Random, format: (MatchFormat) -> Unit): Fixture? =
        nextJob(rng)?.takeIf { f ->
            // 1주씩: a match of a later week (or of the next season) is given back.
            !pastGoal(f).also { past -> if (past) world?.release(f) }
        }?.also {
            // The season keeps the format it started with (a change applies next season); read
            // after nextJob, which may have opened the next season.
            world?.plan?.config?.matchFormat?.let(format)
            // Lab games may run while their AIs play league games: they take no one's slot.
            if (it.comp == Comp.LAB) labInFlight++ else {
                busy += it.players
                live[it.a to it.b] = it
            }
            if (isCup(it.comp)) cupsInFlight++
            if (it.kind == JobKind.TEAM) world?.let { w -> w.teamsInFlight++ }
        }

    /**
     * The run in progress plays season [season] to its end and stops by itself right after its
     * close, nothing of the next season played (the harness measures whole seasons this way).
     */
    internal fun stopAfterSeason(season: Int) {
        scope.launch(Dispatchers.Default) { synchronized(lock) { weekGoal = season to Int.MAX_VALUE } }
    }

    /** The last week a 1주씩 run plays in [w]'s season (none once the season is another). */
    private fun lastWeek(w: LeagueWorld): Int {
        val (season, week) = weekGoal ?: return Int.MAX_VALUE
        return if (w.plan?.number == season) week else 0
    }

    /** True (under the lock) when [f] is past the week a 1주씩 run plays. */
    private fun pastGoal(f: Fixture): Boolean {
        val (season, week) = weekGoal ?: return false
        val p = world?.plan ?: return false
        if (p.number != season) return true
        // Friendlies and lab games have no week: they go on while the week does.
        if (f.week == SeasonCalendar.NONE || f.comp == Comp.FRIENDLY || f.comp == Comp.LAB) return p.week > week
        return f.week > week
    }

    /**
     * 1주씩: true once the run's week is played (its league round done, nothing of it in flight);
     * the run then stops by itself and says what it played.
     */
    private fun weekPlayed(flag: AtomicBoolean): Boolean {
        val note = synchronized(lock) {
            val (season, week) = weekGoal ?: return false
            val w = world ?: return false
            val p = w.plan ?: return false
            if (p.number == season && p.week <= week) return false
            if (live.isNotEmpty() || labInFlight > 0) return false
            // Nothing in flight: a fixture of the week still free (a final a semi just unlocked)
            // is played first. Asked and given back at once, under this same lock.
            if (p.number == season) {
                val free = w.next(busy, cupsInFlight, maxOf(1, speed.threads(cores) - 1), null, week)
                if (free != null) {
                    w.release(free)
                    return false
                }
            }
            weekGoal = null
            if (p.number != season) "시즌 $season 일정을 다 마쳤어요. ▶를 누르면 시즌 ${p.number} 1주차를 해요."
            else "${week}주차를 다 치렀어요. ▶를 누르면 ${p.week}주차를 해요."
        }
        if (flag.getAndSet(true)) return true
        endedWeek = note
        scope.launch {
            weekDone = note
            // The player's stop: it does not go on by itself at the next launch.
            stop()
        }
        return true
    }

    /**
     * An error while running: told to the app; true (and the league stops, saying why) once
     * [MAX_FAILURES] came in a row.
     */
    private fun failed(e: Throwable, flag: AtomicBoolean): Boolean {
        // Told on the main thread (the app shows it).
        scope.launch { onError(e) }
        if (failures.incrementAndGet() < MAX_FAILURES) return false
        if (!flag.getAndSet(true)) {
            endedBy = reasonOf(e)
            scope.launch {
                stopReason = reasonOf(e)
                clearResume()
            }
        }
        return true
    }

    private fun reasonOf(e: Throwable): String =
        "리그가 오류로 멈췄어요: ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(120) } ?: ""}. ▶를 누르면 다시 해 봐요."

    private fun clearResume() {
        scope.launch(fileWrites) { runCatching { resumeFile.delete() } }
    }

    /**
     * While the league runs: the full rating fit every [FIT_MS] (solved off the lock; a match
     * only nudges its two players) and a save every [CHECKPOINT_MS] (stop saves at once).
     */
    private suspend fun upkeep(flag: AtomicBoolean) {
        var lastSave = System.currentTimeMillis()
        while (!flag.get()) {
            delay(FIT_MS)
            try {
                refit()
                refreshOdds()
                if (System.currentTimeMillis() - lastSave >= CHECKPOINT_MS && !flag.get()) {
                    lastSave = System.currentTimeMillis()
                    save()
                }
                unstall(flag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (failed(e, flag)) break
            }
            publish()
        }
    }

    /** When this run last recorded a match. */
    @Volatile
    private var lastRecord = 0L

    /**
     * A run with nothing in flight and no match recorded for [STALL_MS] is stuck (a slot or a
     * fixture still marked as taken by a match that is gone): the marks are rebuilt from the
     * matches really in flight, and the league goes on. Told to the app (crash log) when it finds
     * something.
     */
    private fun unstall(flag: AtomicBoolean) {
        if (flag.get() || System.currentTimeMillis() - lastRecord < STALL_MS) return
        val found = synchronized(lock) {
            if (live.isNotEmpty()) return
            val w = world ?: return
            val lostSlots = busy.size + cupsInFlight + w.teamsInFlight
            busy.clear()
            cupsInFlight = 0
            w.teamsInFlight = 0
            val lostFixtures = w.plan?.league?.reclaim(emptySet()) ?: 0
            lastRecord = System.currentTimeMillis()
            val p = w.plan
            val state = "season ${p?.number}, week ${p?.week}, left ${p?.league?.fixturesLeft}, over ${w.seasonOver}"
            when {
                lostSlots + lostFixtures > 0 -> "리그가 멈춰 있어 다시 풀었어요: busy $lostSlots, fixtures $lostFixtures ($state)"
                // Nothing to free: said once, so the crash log shows where it waits.
                !stallTold -> "리그가 다음 경기를 찾지 못하고 있어요 ($state)".also { stallTold = true }
                else -> return
            }
        }
        scope.launch { onError(IllegalStateException(found)) }
    }

    /**
     * The latest 우승·승강 확률 by league ("" for a legacy season's one league), each made off the
     * lock at most every 10% of the season: in a national season of at most [ODDS_LEAGUES] leagues,
     * the one shown first, then the followed players' by rank ([oddsLeagues]).
     */
    @Volatile
    private var oddsNow: Map<String, OddsTable> = emptyMap()

    /** [oddsNow] is replaced under this (a refresh's tables come in one by one). */
    private val oddsLock = Any()

    /** A refresh of [oddsNow] is running, and another was asked for meanwhile ([refreshOdds]). */
    private val oddsRunning = AtomicBoolean(false)
    private val oddsAgain = AtomicBoolean(false)

    /**
     * Brings [oddsNow] up to date, one refresh at a time: the upkeep and every league picked ask
     * for one, and a request while one runs makes it go round once more (for the leagues wanted
     * then) instead of running the same leagues' odds side by side.
     */
    private fun refreshOdds() {
        oddsAgain.set(true)
        while (oddsAgain.get() && oddsRunning.compareAndSet(false, true)) {
            try {
                while (oddsAgain.getAndSet(false)) refreshOddsOnce()
            } finally {
                oddsRunning.set(false)
            }
        }
    }

    private fun refreshOddsOnce() {
        var from: LeagueWorld? = null
        var wanted: List<String> = emptyList()
        val jobs = synchronized(lock) {
            val w = world ?: return
            val p = w.plan ?: return
            from = w
            wanted = oddsLeagues(p)
            val now = oddsNow
            wanted.mapNotNull { id ->
                val o = now[id]
                if (o != null && !o.stale(p.league)) return@mapNotNull null
                // From the season's seed and progress (and the league): the same league gives the same odds.
                val seed = p.seed xor p.league.fixturesPlayed.toLong() xor (if (id.isEmpty()) 0L else LeagueIds.code(id).toLong() shl 48)
                val input = if (p.league.leagues.national) Odds.input(p, { k -> w.rating(k) }, id) else Odds.input(p, { k -> w.rating(k) })
                Triple(id, seed, input)
            }
        }
        for ((id, seed, input) in jobs) {
            val table = runCatching { Odds.run(input, Odds.runsFor(Odds.left(input)), seed) }.getOrNull() ?: continue
            synchronized(oddsLock) {
                // Not over a league that replaced the one these are of (a reset, a restore).
                if (world !== from) return
                oddsNow = oddsNow.filterKeys { it in wanted } + (id to table)
            }
            // The shown league's first: it need not wait for the followed ones.
            publish()
        }
    }

    /**
     * The leagues whose odds are kept (under the lock): a legacy season's one; else the league shown,
     * then the followed players' leagues by rank, at most [ODDS_LEAGUES].
     */
    private fun oddsLeagues(p: SeasonPlan): List<String> {
        val s = p.league
        if (!s.leagues.national) return listOf(LeagueIds.LEGACY)
        val sel = selector(p)
        val shown = shownOf(p, sel) ?: return emptyList()
        val followed = favourites.mapNotNull { s.leagueOf(it)?.id }.distinct().sortedBy { sel.rank(it) }
        return (listOf(shown) + followed).distinct().take(ODDS_LEAGUES)
    }

    /** [p]'s odds table of league [id] (a legacy season's for any id), when it is of this season. */
    private fun oddsOf(p: SeasonPlan, id: String?): OddsTable? {
        val key = if (p.league.leagues.national) id ?: return null else LeagueIds.LEGACY
        return oddsNow[key]?.takeIf { it.season == p.number }
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
    private fun isCup(c: Comp) =
        c == Comp.CUP || c == Comp.CHAMPIONS || c == Comp.CONT_CHAMPIONS || c == Comp.SUPER || c == Comp.TITLE_PLAYOFF || c == Comp.PROMOTION_PLAYOFF

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
        // The season being played keeps its own copy (SeasonPlan.config); closing it reads these.
        scope.launch(Dispatchers.Default) { synchronized(lock) { world?.config = c } }
    }

    /**
     * Stops after abandoning the matches in progress, and saves. [keepResume]: it goes on by
     * itself later (the app hidden or closed, a speed change); a plain stop is the player's.
     */
    fun stop(keepResume: Boolean = false): Job? {
        if (!keepResume) {
            clearResume()
            weekGoal = null
        }
        synchronized(runGate) {
            if (!running) return null
            stop.set(true)
            running = false
        }
        preparing = null
        val job = runJob
        return scope.launch {
            // Leaving the app stops the league and then clears the scope: the last save must
            // not be cancelled with it.
            withContext(kotlinx.coroutines.NonCancellable) {
                job?.join()
                save()
            }
            publish(force = true)
        }
    }

    /**
     * App hidden: by default no league in the background (battery), and it goes on when the app
     * is back; with [LeagueBackground.KEEP] it keeps playing (the app keeps a notification up).
     */
    fun onBackground() {
        visible = false
        if (background == LeagueBackground.PAUSE) {
            if (running) resumeOnForeground = true
            stop(keepResume = true)
        }
        pauseWatch()
        if (liveJob?.isActive == true) {
            liveJob?.cancel()
            liveOnForeground = true
        }
    }

    /** App shown again: the league goes on if it was running when the app was hidden. */
    fun onForeground() {
        visible = true
        if (liveOnForeground) {
            liveOnForeground = false
            watchLive(true)
        }
        if (!resumeOnForeground) return
        resumeOnForeground = false
        start(resume = true)
    }

    private var resumeOnForeground = false
    private var liveOnForeground = false

    /** The app is in view (main thread); true until the app says otherwise. */
    private var visible = true

    /** Sets what the league does while the app is hidden (saved). */
    fun changeBackground(b: LeagueBackground) {
        background = b
        scope.launch(fileWrites) { runCatching { backgroundFile.writeTextAtomically(b.name) } }
    }

    /**
     * Sets how far ▶ goes (saved). Chosen while the league runs: 1주씩 finishes the week in play
     * and stops; 계속 plays on.
     */
    fun changeRunMode(m: LeagueRunMode) {
        if (m == runMode) return
        runMode = m
        scope.launch(fileWrites) { runCatching { runModeFile.writeTextAtomically(m.name) } }
        if (!running) return
        scope.launch(Dispatchers.Default) {
            synchronized(lock) {
                // The mode as it is when this runs (taps in a row may run out of order).
                weekGoal = when {
                    runMode != LeagueRunMode.WEEK -> null
                    weekGoal != null -> weekGoal
                    else -> world?.plan?.let { it.number to it.week }
                }
            }
        }
    }

    /** True when the app ships a seed ladder to start from. */
    val hasSeed: Boolean by lazy { seed() != null }

    /**
     * Starts from the seed ladder (tens of thousands of matches played on a server) instead of
     * from zero; only while this league has no games yet.
     */
    fun loadSeed() {
        if (running || (view?.matches ?: 0L) > 0L) return
        // In the run chain: a start tapped meanwhile waits for the new ladder.
        val previous = runJob
        runJob = scope.launch {
            previous?.join()
            val text = withContext(Dispatchers.IO) { seed() } ?: return@launch
            withContext(rosterWork) {
                val l = Ladder.decode(synchronized(lock) { ladderKeys() }, text)
                synchronized(lock) { renew(world?.players ?: return@synchronized, l) }
            }
            clearLoadedViews()
            save()
            publish(force = true)
        }
    }

    /**
     * A new world under the lock: [players] and [ladder] (a fresh one by default); the season,
     * clubs, stats, archive, news, highlights, the lab and the match logs start again.
     */
    private fun renew(players: PlayerBook, ladder: Ladder? = null) {
        world = null
        // The old league's logs: a new league's "1:L:123" must not find them.
        center.clear()
        scope.launch(fileWrites) { runCatching { logFile.delete() } }
        pendingPlayers = players
        rebuildRoster()
        val w = LeagueWorld(ladder ?: Ladder(ladderKeys()), config).also { it.players = players }
        if (w.ladder.keys != ladderKeys()) w.ladder = w.ladder.rekey(ladderKeys())
        world = w
        pendingPlayers = null
        configureWorld(w)
        lastDelta.clear()
        startRanks = emptyMap()
        legendAis.clear()
        oddsNow = emptyMap()
    }

    /** Forgets every result and season (my AIs and the built tables stay). */
    fun reset() {
        stop()
        // In the run chain: a start tapped meanwhile waits for the new world.
        val previous = runJob
        runJob = scope.launch {
            previous?.join()
            withContext(rosterWork) {
                // Fresh generated players too (the evolved ones go); the players I made stay, as new.
                val made = synchronized(lock) { world?.let { w -> Triple(w.players.alive.filter { it.origin == Origin.CREATED }, seasonNow(w), w.players.madeNext) } }
                val players = PlayerBook(baseRoster.map { it.name }).apply {
                    made?.let { (list, season, next) -> adopt(list, season, next) }
                    ensureGenerated(config.generated)
                }
                synchronized(lock) { renew(players) }
            }
            withContext(fileWrites) {
                for (name in LeagueWorld.FILES + MatchCenter.LOGS) runCatching { File(folder, name).delete() }
            }
            review = null
            seasonNews = null
            clearLoadedViews()
            save()
            publish(force = true)
        }
    }

    /** Views loaded on request (records, legends, a profile, ...) after the league was replaced: read again or dropped. */
    private fun clearLoadedViews() {
        // An open profile or matrix is read again from the new league (its screen does not ask twice).
        profile?.key?.let { loadProfile(it) }
        lastMatrix?.let { (title, groups, labels) -> loadMatrix(title, groups, labels) }
        calendar = null
        form = null
        preview = null
        // The channel's view went with the old league: asked for again at the next snapshot.
        pickedWeek = null
        // The new league from its start: its week in play, 1부 (its first season then opens on a
        // followed player's division), the current round, the chart's default players.
        nav.week = null
        showNewSeason(null)
        loadRecords()
        loadPastPlayers()
        // The new league's nations and prospects (an open list reads them again); a sheet or a
        // comparison of the old league closes.
        nationSheet = null
        closeCompare()
        for (p in nav.pages.filterIsInstance<Page.Compare>()) nav.close(p)
        nations = emptyList()
        prospects = emptyList()
        loadNations()
        loadProspects()
    }

    private suspend fun save() = withContext(Dispatchers.Default) {
        var from: LeagueWorld? = null
        var bytes: Map<String, ByteArray> = emptyMap()
        val texts = synchronized(lock) {
            val t0 = System.nanoTime()
            from = world
            bytes = center.files()
            world?.encodeFiles().also { timings.note("save", System.nanoTime() - t0) }
        } ?: return@withContext
        withContext(fileWrites) { write(texts, bytes, from) }
    }

    /** Saves every league file (under the lock: the encoding needs it). */
    private fun saveAsync() {
        val w = world ?: return
        val texts = w.encodeFiles()
        val bytes = center.files()
        scope.launch(fileWrites) { write(texts, bytes, w) }
    }

    /**
     * Writes [from]'s text files, then its binary ones, in order; nothing when that world was
     * replaced since (a reset or a restore) or a restore is writing its files.
     */
    private fun write(texts: Map<String, String>, bytes: Map<String, ByteArray>, from: LeagueWorld?) {
        for ((name, text) in texts) {
            if (restoring || world !== from) return
            runCatching { File(folder, name).writeTextAtomically(text) }
        }
        for ((name, data) in bytes) {
            if (restoring || world !== from) return
            runCatching { File(folder, name).writeAtomically(data) }
        }
    }

    /**
     * The league files as they are now (encoded under the lock), the match logs, the rules and the
     * UI preferences, for a backup.
     */
    fun backupFiles(): Map<String, ByteArray> {
        var bytes: Map<String, ByteArray> = emptyMap()
        val texts = synchronized(lock) {
            bytes = center.files()
            world?.encodeFiles()
        }.orEmpty()
        val out = LinkedHashMap<String, ByteArray>()
        for ((name, text) in texts) out[name] = text.toByteArray()
        out.putAll(bytes)
        // As they are now (a change may still be on its way to the file).
        out[UiPrefs.FILE] = uiPrefs.encode().toByteArray()
        // The files beside the league's as saved (the match logs only when none are in memory).
        for (f in listOf(configFile, customFile, prefs, favouriteFile, logFile)) {
            if (f.name !in out && f.exists()) runCatching { out[f.name] = f.readBytes() }
        }
        // Before the league is read: the files on disk.
        if (texts.isEmpty()) for (name in LeagueWorld.FILES) File(folder, name).takeIf { it.exists() }?.let { f -> runCatching { out[name] = f.readBytes() } }
        return out
    }

    // ---- ranked games ----------------------------------------------------------------------------

    /** My rating, or null before my first ranked game. */
    fun myRating(): Double? = synchronized(lock) { ladder?.rating(Ladder.ME)?.takeIf { !it.isNaN() } }

    /**
     * An opponent for a ranked game: near my rating (1400 before I have one), or within [tier]
     * when given. Null when no AI is rated yet.
     */
    fun pickOpponent(rng: Random, tier: TierName? = null): String? {
        // From the screen's snapshot: the lock may be held by a season's rollover for a while.
        val rows = view?.rows ?: return pickOpponentLocked(rng, tier)
        val placed = rows.filter { !it.isMe && it.rank != null && it.rating != null && it.key in byKey }
        val among = if (tier == null) placed else placed.filter { it.tier?.name == tier }
        if (among.isEmpty()) return null
        val r = when {
            tier != null -> among.map { it.rating!!.toDouble() }.average()
            else -> rows.firstOrNull { it.isMe }?.rating?.toDouble() ?: START_RATING
        }
        return Matchmaker.opponentFor(among.map { it.key to it.rating!!.toDouble() }, r, rng)
    }

    private fun pickOpponentLocked(rng: Random, tier: TierName?): String? = synchronized(lock) {
        val l = ladder ?: return null
        val among = if (tier == null) null else l.keys.filter { k -> k != Ladder.ME && l.tier(k)?.name == tier }
        if (among != null && among.isEmpty()) return null
        val r = when {
            tier != null -> among!!.mapNotNull { l.rating(it).takeIf { x -> !x.isNaN() } }.average()
            else -> l.rating(Ladder.ME).takeIf { !it.isNaN() } ?: START_RATING
        }
        Matchmaker.opponentFor(l, r, rng, among) ?: among?.randomOrNull(kotlin.random.Random(rng.nextLong()))
    }

    /** A fresh player for a ranked game against [key] (a 상대 맞춤 AI reads me by my ranked games). */
    fun player(key: String, rng: Random): Player? {
        val ai = byKey[key] ?: return null
        if (!ai.adapts) return ai.make(rng)
        val t = tables ?: return ai.make(rng)
        // My ranked games give my average only (no spread is kept for them).
        val me = synchronized(lock) { ladder?.entry(Ladder.ME)?.let { it.games to it.scoreSum } }
        val model = me?.let { (games, sum) -> if (games > 0) yacht.engine.OppModel.of(t, games, sum.toDouble() / games) else null }
        return ai.player(rng, model)
    }

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
                comp = Comp.FRIENDLY, label = RANKED_LABEL, season = plan?.number ?: 0,
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

    /** The watch screen's game ([MatchCenter.watch]). */
    val watch: WatchView? get() = center.watch

    /** An opponent to watch [key] against: someone near its rating (from the snapshot). */
    fun rivalOf(key: String, rng: Random): String? {
        val rows = view?.rows.orEmpty().filter { !it.isMe && it.key in byKey }
        val r = rows.firstOrNull { it.key == key }?.rating?.toDouble() ?: return byKey.keys.filter { it != key }.randomOrNull()
        val pool = rows.filter { it.key != key && it.rank != null && it.rating != null }.map { it.key to it.rating!!.toDouble() }
        return Matchmaker.opponentFor(pool, r, rng) ?: byKey.keys.firstOrNull { it != key }
    }

    /** Pauses the game watched (the app hidden). */
    fun pauseWatch() = center.pause()

    /** Leaves the watch screen. */
    fun stopWatch() = center.stop()

    // ---- the screen ------------------------------------------------------------------------------

    /** The week (season, week) the broadcast picks were last asked for (main thread). */
    private var pickedWeek: Pair<Int, Int>? = null

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
                synchronized(lock) {
                    val t0 = System.nanoTime()
                    (snapshot() to ++snapshots).also { timings.note("snapshot", System.nanoTime() - t0) }
                }
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
                // A new week in play: its broadcast picks (and next week's) for the channel.
                val week = v.clock?.let { it.season to it.week }
                if (week != pickedWeek) {
                    pickedWeek = week
                    center.refreshPicks()
                }
                if (running) {
                    val minutes = (System.currentTimeMillis() - runStart) / 60_000.0
                    pace = if (minutes > 0.05) runMatches / minutes else 0.0
                }
            }
        }
    }

    fun name(key: String): String = when {
        key == Ladder.ME -> "나"
        else -> byKey[key]?.name ?: pastNames[key] ?: clubNames[key] ?: Competitions.teamLabel(key)
    }

    /** Whether [key] is an AI, playing or retired (news and honours also name teams and clubs). */
    fun isAi(key: String): Boolean = key == Ladder.ME || key in byKey || key in pastNames

    /** Club names by id (news and honours name clubs by id). */
    @Volatile
    private var clubNames: Map<String, String> = emptyMap()

    /** Names of retired players and legends (news and records still mention them). */
    @Volatile
    private var pastNames: Map<String, String> = emptyMap()

    private fun snapshot(): LeagueView? {
        val l = ladder ?: return null
        val s = plan
        val ranking = l.ranking().filter { it != Ladder.ME || l.entry(it)!!.games > 0 }
        val ids = world?.players?.alive?.associate { it.key to it.identity }.orEmpty()
        val season = s?.number ?: 1
        val leagues = s?.league?.leagues
        val all = ranking.mapNotNull { k ->
            val me = k == Ladder.ME
            val ai = byKey[k]
            if (ai == null && !me) return@mapNotNull null
            val e = l.entry(k)!!
            val rank = l.rankOf(k)
            val rating = l.rating(k)
            val d = s?.league?.divisionOf(k)
            LadderRow(
                k, name(k), ai?.family ?: Family.MINE, ai?.desc ?: "랭크전으로 매긴 내 레이팅", ai?.heavy ?: false, rank,
                rating.takeIf { !it.isNaN() }?.roundToInt(),
                l.error(k).takeIf { !it.isNaN() }?.let { (1.96 * it).roundToInt() },
                l.tier(k), e.games, e.wins, e.ties, e.losses, e.winRate, e.avgScore, e.best, e.yachts, e.bonuses,
                // The rating history is read with the profile (thousands of rows, every snapshot).
                e.streak, e.bestStreak, e.recent.joinToString(""), emptyList(), e.peak.takeIf { !it.isNaN() }?.roundToInt(),
                lastDelta[k] ?: 0, rank?.let { r -> startRanks[k]?.let { it - r } }, k in busy, me, d,
                Origin.of(k), world?.hometown(k) ?: "",
                ids[k]?.nation ?: "", ids[k]?.let { Nations.of(it.nation)?.flag } ?: "", ids[k]?.let { id -> world?.players?.get(k)?.let { p -> id.age(season, p.born) } },
                divisionLabel = d?.let { leagues?.label(it) },
                league = d?.let { leagues?.of(it)?.id }.orEmpty(),
            )
        }
        // A league of generated players only: the hand-made AIs (still there for ranked games
        // and the lab) are ranked apart, and the league's players among themselves.
        val rows = if (config.baseAis) all else {
            var league = 0
            var founders = 0
            all.map { r -> if (r.rank == null) r else r.copy(rank = if (r.origin == Origin.BASE) ++founders else ++league) }
        }
        val curves = world?.live
        val feed = l.feed.take(FEED).map { m ->
            val ref = refOf(m)
            val curve = (ref?.let { curves?.curveOf(it) } ?: curves?.curves(m.a, m.b))?.firstOrNull()
            FeedItem(
                m.a, name(m.a), m.b, name(m.b), m.games, m.deltaA, m.deltaB, m.time, m.notes,
                curve?.let { c -> if (c.a == m.a) c.curveA() else IntArray(c.curveA().size) { 100 - c.curveA()[it] } },
                m.comp, m.label, m.week, m.season, ref?.encode(), ref != null && runCatching { center.canReplay(ref, m.a, m.b) }.getOrDefault(false),
            )
        }
        // Built again only when a competition can have moved (a result, a new week, a new stage);
        // a view equal to the last one is handed over as the same object, so the screen skips it.
        val compsKey = s?.let { CompsKey(it, compResults, it.week, it.comps.size, it.post.size, it.postStarted) }
        val comps = if (compsKey != null && compsKey == lastCompsKey) lastComps else {
            s?.let { runCatching { Competitions.views(it) }.getOrNull() }.orEmpty()
                .map { c -> lastComps.firstOrNull { it == c } ?: c }
                .let { if (it == lastComps) lastComps else it }
        }
        lastComps = comps
        lastCompsKey = compsKey
        val clubs = world?.clubs?.let { b ->
            val cl = s?.competition(Comp.CLUB) as? ClubLeague
            clubNames = b.clubs.associate { it.id to it.name }
            ClubsView(cl?.table().orEmpty(), b.clubs, b.history.takeLast(CLUB_MOVES).reversed(), cl?.matches().orEmpty(), cl?.lineups.orEmpty())
        }?.let { if (it == lastClubs) lastClubs else it }
        lastClubs = clubs
        val w = world
        w?.lab?.let { b -> val v = b.current?.results to b.history; scope.launch { lab = v } }
        return LeagueView(
            rows, feed, l.matches, l.games, live.values.map { LiveMatch(it.a, it.b, it.comp, it.label, it.week, it.firstTo, it.id) }, s?.let { seasonView(it) }, comps, clubs,
            w?.news?.all?.take(NEWS).orEmpty(), w?.highlights?.thisSeason.orEmpty(), w?.highlights?.allTime.orEmpty(),
            s?.let { runCatching { clockView(it) }.getOrNull() },
        )
    }

    /**
     * Refs of the feed's records (under the lock), by [feedKey]: those this run recorded, from their
     * job ([refsOf]), and the others' looked up once in this season's matches ("" when not found).
     * Records the ladder keeps with a ref ([MatchRecord.ref]) need neither.
     */
    private val recordRefs = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > RECORD_REFS
    }

    private fun feedKey(m: yacht.engine.league.MatchRecord) = "${m.time}|${m.a}|${m.b}"

    /** Notes the refs of [r]'s boards for their feed [records] (under the lock). */
    private fun refsOf(r: JobResult, records: List<yacht.engine.league.MatchRecord>) {
        val f = r.fixture
        val season = plan?.number ?: return
        if (f.comp == Comp.FRIENDLY || f.comp == Comp.LAB || f.id < 0) return
        for (m in records) {
            if (m.ref != null) continue
            val board = r.boards.indexOfFirst { it.a == m.a && it.b == m.b }.takeIf { it >= 0 } ?: continue
            recordRefs[feedKey(m)] = MatchRef(season, f.comp, f.id, if (f.kind == JobKind.TEAM) board else -1).encode()
        }
    }

    /** [m]'s match (under the lock): its own ref, the one its job gave, or this season's match of its two sides that week. */
    private fun refOf(m: yacht.engine.league.MatchRecord): MatchRef? {
        m.ref?.let { return it }
        val c = m.comp ?: return null
        if (c == Comp.FRIENDLY || c == Comp.LAB || c == Comp.LEGEND) return null
        val key = feedKey(m)
        recordRefs[key]?.let { return MatchRef.decode(it) }
        val p = plan ?: return null
        if (m.season != p.number) return null
        val found = runCatching { yacht.engine.league.SeasonMatches.find(p, c, m.week, m.a, m.b) }.getOrNull()?.ref
        recordRefs[key] = found?.encode().orEmpty()
        return found
    }

    /** The season's weeks; rows equal to the last snapshot's are handed over as the same objects. */
    private fun clockView(p: SeasonPlan): ClockView {
        val cal = p.calendar
        val byWeek = p.schedule().groupBy { it.week }
        val rows = (1..cal.lastWeek).map { w ->
            val r = WeekRow(w, byWeek[w].orEmpty())
            lastWeeks.getOrNull(w - 1)?.takeIf { it == r } ?: r
        }
        lastWeeks = rows
        // By stage: a short season can hold two stages of one competition in a week.
        val playing = HashMap<Pair<Comp, Int>, Int>()
        for (f in live.values) {
            if (f.week == SeasonCalendar.NONE) continue
            val stage = if (f.comp == Comp.LEAGUE) f.week - 1 else p.competition(f.comp)?.let { c -> runCatching { c.stageOf(f) }.getOrNull() } ?: -1
            if (stage >= 0) playing.merge(f.comp to stage, 1, Int::plus)
        }
        return ClockView(p.number, p.week, cal.leagueWeeks, cal.lastWeek, p.finished, rows, playing)
    }

    private var lastWeeks: List<WeekRow> = emptyList()

    /** The last snapshot's views (reused when equal: see [snapshot]); under the lock. */
    private var lastComps: List<Pair<Comp, CompetitionView>> = emptyList()
    private var lastCompsKey: CompsKey? = null

    /**
     * What a season's competition views move with: its results besides the league's ([compResults]),
     * its week (stages open with it) and its competitions (the post-season's are added at the end of
     * the league). The plan compares by identity.
     */
    private data class CompsKey(val plan: SeasonPlan, val results: Long, val week: Int, val comps: Int, val post: Int, val postStarted: Boolean)

    /** Results recorded for the competitions besides the league (friendlies and the lab not counted); under the lock. */
    private var compResults = 0L
    private var lastClubs: ClubsView? = null
    private var lastHistory: List<SeasonHistoryItem> = emptyList()
    private var historyFor = -1
    private var historyBook: HonourBook? = null

    /** The hall of fame's seasons, built again only when the honours change (a pyramid's seasons hold hundreds); under the lock. */
    private fun history(): List<SeasonHistoryItem> {
        val book = honours
        val n = book.all.size
        if (book !== historyBook || n != historyFor) {
            val bySeason = book.all.groupBy { it.season }
            lastHistory = book.seasons.reversed().map { historyItem(it, bySeason[it].orEmpty()) }
            historyFor = n
            historyBook = book
        }
        return lastHistory
    }

    /** [selector]'s last result and what it was built from (under the lock). */
    private var selectorBuilt: LeagueSelector.Built? = null
    private var selectorFor: Triple<Season, LeagueRanking, Set<String>>? = null

    /**
     * [p]'s league selector (under the lock) for favourites [favs]: its leagues, each nation's league
     * and the default, built again only when the season, its ranking or the favourites change (a few
     * dozen rows).
     */
    private fun selector(p: SeasonPlan, favs: Set<String> = favourites): LeagueSelector.Built {
        val was = selectorFor
        selectorBuilt?.let { b -> if (was != null && was.first === p.league && was.second === p.ranking && was.third == favs) return b }
        val w = world
        val b = LeagueSelector.build(
            p.league, p.ranking, p.config, favs,
            nation = { k -> w?.players?.get(k)?.identity?.nation },
            rating = { k -> w?.rating(k) ?: Double.NaN },
            clubNation = { id -> w?.clubs?.nationOf(id) },
        )
        selectorBuilt = b
        selectorFor = Triple(p.league, p.ranking, favs)
        return b
    }

    /** The league [p] shows (under the lock): the one picked while this season has it, else the default; null without leagues. */
    private fun shownOf(p: SeasonPlan, sel: LeagueSelector.Built = selector(p)): String? {
        val leagues = p.league.leagues
        if (!leagues.national) return leagues.list.firstOrNull()?.id
        return shownLeague?.takeIf { leagues.byId(it) != null } ?: sel.default.takeIf { leagues.byId(it) != null } ?: leagues.list.firstOrNull()?.id
    }

    /**
     * The season for 순위 › 리그 (under the lock): the shown league's tables only (a legacy season's
     * one league: every division), with the zones as the off-season will read them on today's
     * tables, its odds, its newcomers and the selector's leagues.
     */
    private fun seasonView(p: SeasonPlan): SeasonView {
        val s = p.league
        val sel = selector(p)
        val lg = shownOf(p, sel)?.let { s.leagues.byId(it) }
        val national = s.leagues.national
        val zone = HashMap<String, Int>()
        var swap = 0
        if (lg != null) for (t in 0 until lg.tiers.count - 1) {
            val c = Pyramid.crossing(s, p.config, lg, t)
            if (t == 0) swap = c.swap
            for (k in c.up) zone[k] = 1
            for (k in c.down) zone[k] = -1
            for ((u, l) in c.ties) {
                zone.putIfAbsent(u, -2)
                zone.putIfAbsent(l, 2)
            }
        }
        val book = world?.players
        fun shown(k: String) = book?.get(k)?.identity?.nation?.let { Nations.of(it)?.flag }?.let { "$it ${name(k)}" } ?: name(k)
        val odds = oddsOf(p, lg?.id)
        val range = lg?.divisions ?: IntRange.EMPTY
        val divisions = range.map { d ->
            s.table(d).map { st ->
                SeasonRow(
                    st.key, shown(st.key), st.played, st.wins, st.draws, st.losses, st.points, st.diff, st.avgScore, st.yachts,
                    zone[st.key] ?: 0,
                    st.aggPoints,
                    odds?.get(st.key),
                    st.sweeps,
                )
            }
        }
        val inLeague = s.standings.keys
        // Next season's newcomers: members not in this season (the rules keep the rest out); in a
        // national season those of the shown league's nations, who join its last tier.
        val newcomers = world?.members.orEmpty().filter { it !in inLeague }
            .filter { k -> !national || sel.leagueOfNation(book?.get(k)?.identity?.nation ?: Nations.KR.code) == lg?.id }
        // A national league's 1부 plays its own rounds over the season's weeks.
        val top = lg?.top
        return SeasonView(
            s.number, s.fixturesPlayed, s.fixturesTotal, divisions, newcomers.map { name(it) },
            history(),
            range.map { s.positions[it].toList() }, range.map { s.divisions[it] },
            if (national && top != null) s.roundsOf(top) else s.matchdays,
            if (national && top != null) minOf(s.positions[top].size, s.roundsOf(top)) else s.currentMatchday,
            lg?.tiers ?: yacht.engine.league.Tiers.linear(0), swap, s.format, p.config,
            leagues = sel.infos, league = lg?.id.orEmpty(), national = national, nextNational = config.national, bonus = s.bonus,
            firstDivision = lg?.first ?: 0,
            rounds = if (national) range.map { s.roundsOf(it) } else emptyList(),
        )
    }

    /** The profile asked for last (read off the main thread). */
    var profile by mutableStateOf<ProfileView?>(null)
        private set

    /** Loads [key]'s profile into [profile] (as the player page reads it, [profileOf]). */
    fun loadProfile(key: String) {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                profileOf(w, key, ::name)
            }
            scope.launch { profile = v }
        }
    }

    /** The records room, archive and meta, read on request. */
    var records by mutableStateOf<RecordsRoomView?>(null)
        private set

    fun loadRecords() {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val st = w.stats
                RecordsRoomView(
                    st.records.all, w.archive.dynasties.toList(), w.archive.hallOfFame.toList(),
                    w.archive.summaries.sortedByDescending { it.season }, st.milestones.reversed(),
                    runCatching { st.liveMeta() }.getOrNull(), st.previousMeta ?: st.meta,
                    st.top(10, career = false, min = { it.matches >= 10 && !it.avgRating.isNaN() }) { it.avgRating }.map { (k, a) -> k to a.copy() },
                )
            }
            scope.launch { records = v }
        }
    }

    /** The season review (시즌 결산) of the season that just ended, until the player closes it. */
    var review by mutableStateOf<SeasonReview?>(null)
        private set

    fun dismissReview() {
        review = null
        scope.launch(Dispatchers.Default) {
            synchronized(lock) { world?.archive?.reviewShown() }
            save()
        }
    }

    /** The 국가 랭킹, read on request. */
    var nations by mutableStateOf<List<NationRow>>(emptyList())
        private set

    /** The nation sheet opened last. */
    var nationSheet by mutableStateOf<NationSheet?>(null)
        private set

    fun closeNation() {
        nationSheet = null
    }

    /** Ratings of every player, keyed (under the lock). */
    private fun ratingsOf(w: LeagueWorld): Map<String, Double> =
        w.members.associateWith { w.ladder.rating(it) }.filterValues { !it.isNaN() }

    private fun nationRows(w: LeagueWorld, ratings: Map<String, Double>): List<NationRow> {
        val book = w.nations
        val byNation = w.players.alive.groupBy { it.identity?.nation ?: Nations.KR.code }
        return Nations.ALL.map { n ->
            val ps = byNation[n.code].orEmpty().filter { it.key in ratings }.sortedByDescending { ratings[it.key] }
            val r = book[n.code]
            NationRow(
                n.code, n.flag, n.name, n.confed.label, byNation[n.code]?.size ?: 0,
                ps.take(5).takeIf { it.size == 5 }?.map { ratings.getValue(it.key) }?.average()?.roundToInt(),
                ps.firstOrNull()?.name,
                r?.worldTitles ?: 0, r?.continentalTitles ?: 0, r?.worldBest ?: NationBook.NONE, r?.continentalBest ?: NationBook.NONE,
                r?.editions ?: 0, r?.won ?: 0, r?.drawn ?: 0, r?.lost ?: 0,
            )
        }
    }

    fun loadNations() {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                nationRows(w, ratingsOf(w))
            } ?: return@launch
            scope.launch { nations = v }
        }
    }

    fun loadNation(code: String) {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val ratings = ratingsOf(w)
                val row = nationRows(w, ratings).firstOrNull { it.code == code } ?: return@synchronized null
                val b = w.players
                val season = w.plan?.number ?: (b.evolvedSeason + 1)
                val mine = b.alive.filter { (it.identity?.nation ?: Nations.KR.code) == code }
                val squad = mine.filter { it.key in ratings }.sortedByDescending { ratings[it.key] }.take(8).map {
                    NationPlayer(it.key, it.name, "레이팅 ${ratings.getValue(it.key).roundToInt()} · ${it.age(season)}세")
                }
                val capped = w.nations.mostCapped(5, code).map { c ->
                    NationPlayer(c.key, name(c.key), "${c.caps}회 선발" + (if (c.titles > 0) " · 우승 ${c.titles}회" else "") + " · ${NationBook.record(c.won, c.drawn, c.lost)}")
                }
                val cups = w.nations.editions.reversed().mapNotNull { e ->
                    val cup = if (e.world) "요트 월드컵" else (e.podiums.firstOrNull { p -> p.winner == code || p.runnerUp == code || code in p.semis }?.confed?.cup ?: "대륙컵")
                    val place = e.podiums.firstNotNullOfOrNull { p ->
                        when (code) {
                            p.winner -> "우승"
                            p.runnerUp -> "준우승"
                            in p.semis -> "4강"
                            else -> null
                        }
                    }
                    place?.let { "시즌 ${e.season} $cup $it" + if (e.host == code) " (개최)" else "" }
                }.take(12)
                // 국적별 기록실: who is of this nation, alive, retired or a legend.
                val nationOf = HashMap<String, String>()
                for (r in b.retired) nationOf[r.key] = r.nation
                for (l in b.legends) nationOf[l.key] = l.identity?.nation ?: l.record.nation
                for (p in b.alive) nationOf[p.key] = p.identity?.nation ?: Nations.KR.code
                // A national league's title ("L0.KR") counts as a 1부 title ("L0"), whichever league it was won in.
                val honours = w.honours.all.filter { nationOf[it.key] == code && HonourKind.base(it.kind) in NATION_HONOURS }
                    .groupingBy { HonourKind.base(it.kind) }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
                val legends = b.legends.filter { nationOf[it.key] == code }.reversed().take(10).map { l ->
                    NationPlayer(l.key, l.name, "시즌 ${l.record.born}–${l.record.retired}" + if (l.record.titles > 0) " · 우승 ${l.record.titles}회" else "")
                }
                val ages = mine.map { it.age(season) }
                val bands = listOf("~21세" to (0..21), "22~25세" to (22..25), "26~31세" to (26..31), "32~35세" to (32..35), "36세~" to (36..99))
                NationSheet(
                    row, yacht.engine.league.Schools.of(code).motto, squad, capped, cups, honours, legends,
                    bands.map { (l, r) -> l to ages.count { it in r } }, mine.count { it.identity?.gender == yacht.engine.league.Gender.FEMALE },
                )
            } ?: return@launch
            scope.launch { nationSheet = v }
        }
    }

    /** 유망주: young players by potential. */
    var prospects by mutableStateOf<List<ProspectRow>>(emptyList())
        private set

    fun loadProspects() {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val season = w.plan?.number ?: (w.players.evolvedSeason + 1)
                w.players.alive.mapNotNull { p ->
                    val id = p.identity ?: return@mapNotNull null
                    val age = id.age(season, p.born)
                    if (age > PROSPECT_AGE) return@mapNotNull null
                    val nat = Nations.of(id.nation)
                    ProspectRow(
                        p.key, nat?.let { "${it.flag} ${p.name}" } ?: p.name, age, id.abilities.overall, maxOf(id.potential, id.abilities.overall),
                        yacht.engine.league.Profiles.outlook(id, age), w.ladder.rating(p.key).takeIf { !it.isNaN() }?.roundToInt(),
                    )
                }.sortedWith(compareByDescending<ProspectRow> { it.potential }.thenByDescending { it.overall }.thenBy { it.key }).take(60)
            } ?: return@launch
            scope.launch { prospects = v }
        }
    }

    /** 선수 비교: the pair opened last. */
    var compare by mutableStateOf<CompareView?>(null)
        private set

    fun closeCompare() {
        compareWanted = null
        compare = null
    }

    /** The pair asked for last: a result that comes back after the comparison closed is dropped. */
    private var compareWanted: Pair<String, String>? = null

    fun loadCompare(a: String, b: String) {
        compareWanted = a to b
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val w = world ?: return@synchronized null
                val l = w.ladder
                val st = w.stats
                fun num(x: Double, f: String) = if (x.isNaN()) "–" else f.format(x)
                fun better(x: Double, y: Double, high: Boolean = true) = when {
                    x.isNaN() || y.isNaN() || x == y -> 0
                    (x > y) == high -> 1
                    else -> -1
                }
                val ra = l.rating(a)
                val rb = l.rating(b)
                val ca = st.career(a)
                val cb = st.career(b)
                val rows = (view?.rows).orEmpty()
                val rowA = rows.firstOrNull { it.key == a }
                val rowB = rows.firstOrNull { it.key == b }
                val ta = w.honours.titles(a).toDouble()
                val tb = w.honours.titles(b).toDouble()
                val ma = w.honours.of(a).count { it.kind == "MVP" }.toDouble()
                val mb = w.honours.of(b).count { it.kind == "MVP" }.toDouble()
                val lines = listOf(
                    CompareLine("레이팅", num(ra, "%.0f"), num(rb, "%.0f"), better(ra, rb)),
                    CompareLine("순위", rowA?.rank?.let { "${it}위" } ?: "–", rowB?.rank?.let { "${it}위" } ?: "–", better((rowA?.rank ?: 0).toDouble().takeIf { it > 0 } ?: Double.NaN, (rowB?.rank ?: 0).toDouble().takeIf { it > 0 } ?: Double.NaN, high = false)),
                    CompareLine("판 수", "${rowA?.games ?: 0}", "${rowB?.games ?: 0}", 0),
                    CompareLine("승률", rowA?.let { "%.1f%%".format(100 * it.winRate) } ?: "–", rowB?.let { "%.1f%%".format(100 * it.winRate) } ?: "–", better(rowA?.winRate ?: Double.NaN, rowB?.winRate ?: Double.NaN)),
                    CompareLine("평균 점수", rowA?.let { "%.1f".format(it.avgScore) } ?: "–", rowB?.let { "%.1f".format(it.avgScore) } ?: "–", better(rowA?.avgScore ?: Double.NaN, rowB?.avgScore ?: Double.NaN)),
                    CompareLine("평균 평점", num(ca?.avgRating ?: Double.NaN, "%.2f"), num(cb?.avgRating ?: Double.NaN, "%.2f"), better(ca?.avgRating ?: Double.NaN, cb?.avgRating ?: Double.NaN)),
                    CompareLine("우승", "${ta.toInt()}회", "${tb.toInt()}회", better(ta, tb)),
                    CompareLine("MVP", "${ma.toInt()}회", "${mb.toInt()}회", better(ma, mb)),
                )
                val met = l.opponents(a).firstOrNull { it.first == b }?.let { (_, p, g) -> p / g to g }
                CompareView(
                    a, b, name(a), name(b),
                    runCatching { identityOf(w, a, ::name) }.getOrNull(), runCatching { identityOf(w, b, ::name) }.getOrNull(),
                    lines, met,
                )
            } ?: return@launch
            scope.launch { if (compareWanted == (a to b)) compare = v }
        }
    }

    /** Retired players and legends, newest first (레전드 보관소). */
    var pastPlayers by mutableStateOf<List<PastPlayer>>(emptyList())
        private set

    fun loadPastPlayers() {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val b = world?.players ?: return@synchronized emptyList()
                val legends = b.legends.map { l ->
                    val r = l.record
                    PastPlayer(r.key, r.name, r.born, r.retired, r.bestRating.takeIf { !it.isNaN() }?.roundToInt(), r.titles, true, l.honours, r.hometown)
                }
                val legendKeys = legends.map { it.key }.toSet()
                val retired = b.retired.filter { it.key !in legendKeys }.map { r ->
                    PastPlayer(r.key, r.name, r.born, r.retired, r.bestRating.takeIf { !it.isNaN() }?.roundToInt(), r.titles, false, "-", r.hometown)
                }
                (legends + retired).sortedByDescending { it.retired }
            }
            scope.launch { pastPlayers = v }
        }
    }

    /** The head-to-head matrix asked for last (read off the main thread). */
    var h2h by mutableStateOf<H2hMatrix?>(null)
        private set

    /** Loads the matrix between these AIs into [h2h]. */
    fun loadH2h(title: String, keys: List<String>) = loadMatrix(title, keys.map { listOf(it) }, keys.map { name(it) })

    /** Loads the matrix between these groups of AIs (e.g. families) into [h2h]. */
    /** The matrix asked for last (read again when the league is replaced). */
    private var lastMatrix: Triple<String, List<List<String>>, List<String>>? = null

    fun loadMatrix(title: String, groups: List<List<String>>, labels: List<String>) {
        lastMatrix = Triple(title, groups, labels)
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

    /** 라이브 스코어보드: the newest finished fixtures replayed turn by turn while the board is open. */
    var liveFrames by mutableStateOf<List<LiveFrame>>(emptyList())
        private set
    private var liveJob: Job? = null

    /** Opens (true) or closes the live scoreboard; while open it is redrawn four times a second. */
    fun watchLive(open: Boolean) {
        liveJob?.cancel()
        if (!open) {
            liveOnForeground = false
            return
        }
        val start = System.currentTimeMillis()
        liveJob = scope.launch(Dispatchers.Default) {
            while (true) {
                val frames = synchronized(lock) { world?.live?.window(System.currentTimeMillis(), start).orEmpty() }
                scope.launch { liveFrames = frames }
                delay(250)
            }
        }
    }

    /** The lab's current experiment and the newest finished ones (newest first). */
    var lab by mutableStateOf<Pair<LabResult?, List<LabResult>>>(null to emptyList())
        private set

    /**
     * Starts an experiment of [a] against [b] (AI 연구소): [target] mirrored matches, or 0 until
     * the win share is known to ±1%p. The league is started if it was not running.
     */
    fun startLab(a: String, b: String, target: Int) {
        scope.launch(Dispatchers.Default) {
            synchronized(lock) { world?.lab?.start(a, b, target, System.nanoTime(), name(a), name(b)) }
            publishLab()
            save()
        }
        if (!running) start()
    }

    fun stopLab() {
        scope.launch(Dispatchers.Default) {
            synchronized(lock) { world?.lab?.stop() }
            publishLab()
            save()
        }
    }

    private fun publishLab() {
        val v = synchronized(lock) { world?.lab?.let { b -> b.current?.results to b.history } } ?: return
        scope.launch { lab = v }
    }

    /** Legends the lab can field (they no longer play in the league): key and name. */
    fun legends(): List<Pair<String, String>> = legendsNow

    /** [legends], kept with the roster (read without the lock). */
    @Volatile
    private var legendsNow: List<Pair<String, String>> = emptyList()

    /** The match preview (경기 프리뷰) asked for last. */
    var preview by mutableStateOf<MatchPreview?>(null)
        private set

    fun closePreview() {
        preview = null
    }

    /** Builds the preview of [a] against [b] (a fixture of this season when [fixtureId] is given). */
    fun loadPreview(a: String, b: String) {
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) { world?.let { previewOf(it, a, b, null) } }
            scope.launch { preview = v }
        }
    }

    /** The preview of [a] against [b] in [w] (under the lock); [fixture] adds its competition and round. */
    private fun previewOf(w: LeagueWorld, a: String, b: String, fixture: Fixture?): MatchPreview {
        val season = w.plan?.league
        val profiles = profilesOf(w)
        // The odds of their league (a national season keeps a few leagues' odds); two sides
        // of two leagues (대륙 챔피언스) each with their own league's, where it is kept.
        val odds = w.plan?.let { p ->
            val tables = listOfNotNull(season?.leagueOf(a)?.id, season?.leagueOf(b)?.id).distinct().mapNotNull { oddsOf(p, it) }
            when {
                !p.league.leagues.national -> oddsOf(p, null)
                tables.size < 2 -> tables.firstOrNull()
                else -> tables[0].let { t -> OddsTable(t.season, t.played, t.total, t.runs, t.drawRate, tables.flatMap { it.rows }, t.league) }
            }
        }
        return Preview.of(
            a, b, w.ladder, season, odds, profiles[a], profiles[b],
            BoxProfile.league(profiles.values), { k -> name(k) }, fixture,
        )
    }

    /** Every member's box profile from its career statistics (under the lock). */
    private fun profilesOf(w: LeagueWorld): Map<String, BoxProfile> =
        w.members.mapNotNull { k -> w.stats.career(k)?.boxProfile()?.let { k to it } }.toMap()

    /** The scouting report (스카우팅 리포트) of [key] before a ranked game, or null. */
    fun scouting(key: String): ScoutingReport? = synchronized(lock) {
        val w = world ?: return null
        val ai = byKey[key] ?: return null
        val profiles = profilesOf(w)
        val spec = customs.firstOrNull { it.key == key } ?: w.players[key]?.spec
        runCatching { Scouting.report(ai.name, ai.family, spec, w.stats.career(key)?.boxProfile(), BoxProfile.league(profiles.values), ai.desc) }.getOrNull()
    }

    /** The matchday shown in the calendar (일정표), read off the main thread on request. */
    var calendar by mutableStateOf<CalendarView?>(null)
        private set

    /**
     * Loads [matchday] of [division] (the season's index) into [calendar] (null: the current
     * matchday). A division of a national season shows its own round on [matchday] or the next one
     * after it (the last when none; a division with fewer rounds than the season has matchdays plays
     * them spread over the weeks), and its own round in play (null: that one).
     */
    fun loadCalendar(division: Int, matchday: Int? = null) {
        // Only the newest request is shown (an older one can finish later).
        val request = calendarRequests.incrementAndGet()
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val s = plan?.league ?: return@synchronized null
                if (s.divisions.isEmpty()) return@synchronized null
                val d = division.coerceIn(0, s.divisions.size - 1)
                val n = s.roundsOf(d)
                fun items(md: Int) = s.matchday(md).filter { it.division == d }.map { f ->
                    val games = s.result(f.id)
                    CalendarItem(f.id, f.a, name(f.a), f.b, name(f.b), games, !s.isPlayed(f.id) && live.containsKey(f.a to f.b), games?.let { TieRule.match(it, s.format) })
                }
                // A national division (even one of the most rounds) by its own rounds, the round in play its own.
                if (!s.leagues.national || n == 0) {
                    val md = (matchday ?: s.currentMatchday).coerceIn(0, maxOf(0, s.matchdays - 1))
                    CalendarView(d, md, s.matchdays, s.currentMatchday, items(md), s.number)
                } else {
                    // Its rounds in order, and the first one still to play (all played: past the last).
                    val done = s.roundCounts(d)
                    val current = done.indexOfFirst { !it.done }.let { if (it < 0) n else it }
                    val round = matchday?.let { md -> (0 until n).firstOrNull { s.weekOf(d, it) >= md } ?: (n - 1) } ?: minOf(current, n - 1)
                    val md = s.weekOf(d, round)
                    CalendarView(
                        d, md, s.matchdays, s.currentMatchday, items(md), s.number, round, n, current,
                        prev = if (round > 0) s.weekOf(d, round - 1) else null,
                        next = if (round < n - 1) s.weekOf(d, round + 1) else null,
                    )
                }
            }
            scope.launch { if (request == calendarRequests.get()) calendar = v }
        }
    }

    private val calendarRequests = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Loads the round of [division] (the season's index) played on [matchday] into [calendar]
     * (null: the round being played); a legacy season plays round k on matchday k.
     */
    fun loadRound(division: Int, matchday: Int? = null): Unit = loadCalendar(division, matchday)

    /** The recent form (최근 폼) asked for last, read off the main thread on request. */
    var form by mutableStateOf<FormView?>(null)
        private set

    /** Loads [division]'s last [FORM_GAMES] results per player into [form]. */
    fun loadForm(division: Int) {
        val request = formRequests.incrementAndGet()
        scope.launch(Dispatchers.Default) {
            val v = synchronized(lock) {
                val s = plan?.league ?: return@synchronized null
                if (s.divisions.isEmpty()) return@synchronized null
                val d = division.coerceIn(0, s.divisions.size - 1)
                // Matchday by matchday: each player's results oldest first.
                val results = HashMap<String, StringBuilder>()
                for (md in 0 until s.matchdays) for (f in s.matchday(md)) {
                    if (f.division != d) continue
                    val games = s.result(f.id) ?: continue
                    val r = TieRule.match(games, s.format)
                    results.getOrPut(f.a) { StringBuilder() }.append(if (r > 0) 'W' else if (r < 0) 'L' else 'D')
                    results.getOrPut(f.b) { StringBuilder() }.append(if (r < 0) 'W' else if (r > 0) 'L' else 'D')
                }
                FormView(d, s.number, s.fixturesPlayed, s.divisions[d].associateWith { k -> results[k]?.takeLast(FORM_GAMES)?.toString().orEmpty() })
            }
            scope.launch { if (request == formRequests.get()) form = v }
        }
    }

    private val formRequests = java.util.concurrent.atomic.AtomicInteger()

    /** A past season for the hall of fame, from its honours. */
    private fun historyItem(season: Int, h: List<Honour>): SeasonHistoryItem {
        // A national season's champions: each league's (its 1부 after the playoff), in league order, named with their league.
        val national = h.filter { HonourKind.isTopTitle(it.kind) && HonourKind.leagueOf(it.kind) != null }
            .sortedBy { LeagueIds.order(HonourKind.leagueOf(it.kind)!!) }
        if (national.isNotEmpty()) {
            return SeasonHistoryItem(
                season, national.map { "${LeagueIds.short(HonourKind.leagueOf(it.kind)!!)} ${name(it.key)}" },
                h.firstOrNull { it.kind == "SCORER" }?.let { name(it.key) }, 0.0, h.firstOrNull { it.kind == "YACHT" }?.let { name(it.key) }, 0,
                emptyList(), emptyList(),
                h.filter { it.kind in SHOWN_TITLES }.sortedBy { SHOWN_TITLES.indexOf(it.kind) }.map { it.kind to teamOrName(it.kind, it.key) },
                national.map { 0 },
            )
        }
        val titles = h.filter { DIVISION.matches(it.kind) }.sortedBy { it.kind.drop(1).toIntOrNull() ?: 0 }
        // A pyramid's dozens of moves: those into 1부 and 2부 by name, the rest counted.
        val up = h.filter { it.kind.startsWith("UP") }
        val down = h.filter { it.kind.startsWith("DOWN") }
        fun named(list: List<Honour>, upper: Int) =
            if (list.size <= MOVES_LISTED) list else list.filter { (it.kind.dropWhile { c -> !c.isDigit() }.toIntOrNull() ?: 0) <= upper }
        return SeasonHistoryItem(
            season, titles.map { name(it.key) }, h.firstOrNull { it.kind == "SCORER" }?.let { name(it.key) }, 0.0,
            h.firstOrNull { it.kind == "YACHT" }?.let { name(it.key) }, 0,
            named(up, 1).map { name(it.key) }, named(down, 2).map { name(it.key) },
            h.filter { it.kind in SHOWN_TITLES }.sortedBy { SHOWN_TITLES.indexOf(it.kind) }.map { it.kind to teamOrName(it.kind, it.key) },
            titles.map { it.kind.drop(1).toIntOrNull() ?: 0 }, up.size, down.size,
        )
    }

    /** An honour's winner: a club, a family or an all-star side by its label, else the AI's name. */
    private fun teamOrName(kind: String, key: String): String = when (kind) {
        "FAMILY", "ALLSTAR", "WC", "WC2", "WC4", "CC", "CC2" -> Competitions.teamLabel(key)
        else -> name(key)
    }

    companion object {
        private val DIVISION = Regex("L\\d")

        /** Honours the season history lists after the division champions, in this order. */
        val SHOWN_TITLES = listOf("REG", "L0R", "CL", "CL2", "CUP", "CUP2", "SUPER", "CLUB", "FAMILY", "ALLSTAR", "WC", "WC2", "CC", "WCMVP", "MVP", "ROOKIE", "MIP")

        /** League files a backup restores (when this phone's league has no games yet); one the backup lacks is removed. */
        val SAVED = LeagueWorld.FILES + listOf("config.txt", "favourites.txt", UiPrefs.FILE, MatchCenter.LOGS)

        /** Hand-made AIs that play the end of a game by a table of the opponent's actual results (막판 대결표, 최소최대). */
        private val DUEL_AIS = setOf("duel", "minimax")

        /** The default number of players before the pyramid. */
        private const val OLD_DEFAULT_PLAYERS = 200

        /** Moves a season's history names (more: only those into 1부 and 2부). */
        const val MOVES_LISTED = 16

        /** Results a row's recent form holds. */
        const val FORM_GAMES = 5

        /** Oldest age listed among the 유망주. */
        const val PROSPECT_AGE = 22

        /** The honours counted in a nation's records (titles and awards its players won). */
        val NATION_HONOURS = setOf("L0", "CUP", "CL", "SUPER", "MVP", "ROOKIE", "MIP", "YACHT", "SCORER", "WCMVP", "WCTOP", "POTW")
        private const val FIT_MS = 2_000L
        private const val CHECKPOINT_MS = 30_000L
        private const val PUBLISH_MS = 300L
        private const val FEED = 60

        /** Feed records whose refs are remembered (more than the feed shows). */
        private const val RECORD_REFS = 200

        /** Leagues of a national season whose odds are kept (the one shown, then the followed players'). */
        private const val ODDS_LEAGUES = 4

        /** Share of matches given to AIs waiting for the next season, so they get a rating. */
        private const val CLUB_MOVES = 60
        private const val NEWS = 120
        private const val FRIENDLY_SHARE = 0.2

        /** The rating scale's anchor (1500) and the share of jobs it plays as 기준 경기. */
        private const val ANCHOR = "ev"
        private const val CALIBRATION_SHARE = 0.02
        private const val CALIBRATION_LABEL = "기준 경기"
        private const val FRIENDLY_RUSH = 0.6

        /** Errors in a row after which the league stops and says why. */
        private const val MAX_FAILURES = 12
        private const val ERROR_PAUSE_MS = 500L

        /** A run with nothing in flight this long without a match is stuck (see unstall). */
        private const val STALL_MS = 20_000L

        /** Stack of a league worker thread (bytes). */
        private const val WORKER_STACK = 16L shl 20

        /** Feed labels of the player's ranked games and of watched games (friendlies on the ladder). */
        const val RANKED_LABEL = "랭크전"
        const val WATCH_LABEL = "관전 경기"

        /** My assumed rating before my first ranked game (a solid amateur). */
        const val START_RATING = 1400.0
    }
}

/** Count, mean and worst of a few timed sections (ms), thread-safe. */
class LockTimings {
    private val sums = HashMap<String, DoubleArray>()

    @Synchronized
    fun note(what: String, nanos: Long) {
        val a = sums.getOrPut(what) { DoubleArray(3) }
        val ms = nanos / 1e6
        a[0] += 1.0
        a[1] += ms
        a[2] = maxOf(a[2], ms)
    }

    @Synchronized
    override fun toString(): String = sums.entries.sortedBy { it.key }.joinToString(" · ") { (k, a) ->
        "$k ${a[0].toInt()}× avg ${"%.2f".format(a[1] / a[0])} max ${"%.1f".format(a[2])} ms"
    }
}

/**
 * The league selector's data (UX 1.7) from a season: its leagues as [LeagueInfo] rows in league
 * order, the league each nation plays in, and the league shown when none is picked. Pure (the
 * controller calls it under its lock when the season, the ranking or the favourites change).
 */
internal object LeagueSelector {
    /** Favourites of a nation, a club and a league start with these; the others are players. */
    const val NATION = "nation:"
    const val CLUB = "club:"
    const val LEAGUE = "league:"

    /** A season's selector: [infos] in league order, [nationLeague] (nation code to the league most of its players play in), [default]. */
    class Built(val infos: List<LeagueInfo>, val nationLeague: Map<String, String>, val default: String) {
        private val byId = infos.associateBy { it.id }

        fun info(id: String): LeagueInfo? = byId[id]

        /** 1-based rank of league [id] ([Int.MAX_VALUE] when unranked or unknown). */
        fun rank(id: String): Int = byId[id]?.rank ?: Int.MAX_VALUE

        /** The league players of nation [code] play in ([leagueOf]); null in a season without leagues. */
        fun leagueOfNation(code: String): String? = leagueOf(code, nationLeague, byId.keys) ?: infos.firstOrNull()?.id
    }

    /**
     * Nation [code]'s league among [ids]: where most of its players play ([nationLeague]), else its
     * confederation's 연합 리그, else the 국제 연합 리그, else Korea's (where players without a nation
     * play); null when none of them is there.
     */
    private fun leagueOf(code: String, nationLeague: Map<String, String>, ids: Set<String>): String? =
        nationLeague[code]
            ?: Nations.of(code)?.confed?.let { LeagueIds.region(it) }?.takeIf { it in ids }
            ?: LeagueIds.WORLD.takeIf { it in ids }
            ?: nationLeague[Nations.KR.code]

    /**
     * [season]'s selector. [ranking] is the one the season is played under (EMPTY, or without a
     * row for a league: by strength, as the first national season's draws rank them); [config] the
     * season's rules (no 대륙 챔피언스 or 챔피언스 리그 places when it does not play them); [nation] a
     * player's nation code (null: none, so Korea's); [clubNation] a club's nation.
     */
    fun build(
        season: Season,
        ranking: LeagueRanking,
        config: LeagueConfig,
        favourites: Set<String>,
        nation: (String) -> String?,
        rating: (String) -> Double,
        clubNation: (String) -> String? = { null },
    ): Built {
        val leagues = season.leagues
        val keys = leagues.list.associate { lg -> lg.id to lg.divisions.flatMap { season.divisions[it] } }
        // Each nation's league: where most of its players play this season (a player abroad plays elsewhere).
        val counts = HashMap<String, HashMap<String, Int>>()
        for ((id, ks) in keys) for (k in ks) {
            val code = nation(k)?.takeIf { Nations.of(it) != null } ?: Nations.KR.code
            counts.getOrPut(code) { HashMap() }.merge(id, 1, Int::plus)
        }
        val order = leagues.list.withIndex().associate { (i, lg) -> lg.id to i }
        val nationLeague = counts.mapValues { (_, m) ->
            m.entries.minWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { order.getValue(it.key) }).key
        }
        val nationsIn = nationLeague.values.groupingBy { it }.eachCount()
        val strength = { id: String -> Coefficients.strength(keys[id].orEmpty(), rating) }
        val ranked = if (leagues.national) Coefficients.order(keys.keys, ranking, strength) else emptyList()
        // The places as the draws would give them by strength, for a league the ranking has no row for.
        val byStrength = if (leagues.national) Coefficients.ranking(MemoryCoefficients(), leagues, season.number - 1, strength, config) else null

        // Followed: a player's league, a club's and a nation's, and the leagues followed themselves.
        val players = favourites.filter { ':' !in it && season.divisionOf(it) != null }
        val clubs = favourites.filter { it.startsWith(CLUB) }.sorted()
            .mapNotNull { f -> clubNation(f.removePrefix(CLUB))?.let { leagueOf(it, nationLeague, keys.keys) } }
        val nations = favourites.filter { it.startsWith(NATION) }.map { it.removePrefix(NATION) }
            .sortedBy { c -> Nations.ALL.indexOfFirst { it.code == c } }.mapNotNull { leagueOf(it, nationLeague, keys.keys) }
        val followed = HashSet<String>()
        for (k in players) season.leagueOf(k)?.let { followed += it.id }
        followed += clubs
        followed += nations
        followed += favourites.filter { it.startsWith(LEAGUE) }.map { it.removePrefix(LEAGUE) }.filter { it in keys }

        val infos = leagues.list.map { lg ->
            val row = ranking.of(lg.id)
            val guess = byStrength?.of(lg.id)
            LeagueInfo(
                lg.id, LeagueIds.label(lg.id), LeagueIds.short(lg.id), LeagueIds.icon(lg.id), leagues.confedOf(lg.id), lg.first,
                (0 until lg.size).map { lg.tiers.label(it) }, lg.divisions.map { season.divisions[it].size }, keys[lg.id].orEmpty().size,
                ranked.indexOf(lg.id).takeIf { it >= 0 }?.plus(1),
                row?.coefficient,
                // Places only in what the season's rules play (as the odds count them, `Odds.input`).
                if (config.continentalCl) row?.cclSlots ?: guess?.cclSlots ?: 0 else 0,
                config.champions && (row?.clDirect ?: guess?.clDirect ?: false),
                lg.id in followed,
                nationsIn[lg.id] ?: 0,
            )
        }
        // The best-rated followed player's league, else a followed club's, a followed nation's, Korea's, the best-ranked.
        val best = players.maxWithOrNull(compareBy<String> { rating(it).takeIf { r -> !r.isNaN() } ?: Double.NEGATIVE_INFINITY }.thenByDescending { it })
        val default = best?.let { season.leagueOf(it)?.id }
            ?: clubs.firstOrNull()
            ?: nations.firstOrNull()
            ?: nationLeague[Nations.KR.code]
            ?: infos.minByOrNull { it.rank ?: Int.MAX_VALUE }?.id
            ?: LeagueIds.LEGACY
        return Built(infos, nationLeague, default)
    }

    /**
     * The selector sheet's sections, each in its order: ★ 응원 (the followed leagues by rank),
     * 국가 리그 (nations' own leagues by rank), 연합 리그 (XAS, XEU, XAM, XAF, then XWO). A legacy
     * season's one league makes none: there is nothing to pick.
     */
    fun sections(infos: List<LeagueInfo>): List<Pair<String, List<LeagueInfo>>> {
        if (infos.none { it.national }) return emptyList()
        val byRank = compareBy<LeagueInfo> { it.rank ?: Int.MAX_VALUE }.thenBy { LeagueIds.order(it.id) }
        return listOf(
            "★ 응원" to infos.filter { it.followed }.sortedWith(byRank),
            "국가 리그" to infos.filter { !it.followed && !it.union }.sortedWith(byRank),
            "연합 리그" to infos.filter { !it.followed && it.union }.sortedBy { LeagueIds.order(it.id) },
        ).filter { it.second.isNotEmpty() }
    }
}

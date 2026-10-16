package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yacht.engine.Advice
import yacht.engine.Advisor
import yacht.engine.AdvisorPlayer
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.DuelSearch
import yacht.engine.SearchTimeout
import yacht.engine.DiceStream
import yacht.engine.Duplicate
import yacht.engine.Match
import yacht.engine.Mode
import yacht.engine.PlannedTurn
import yacht.engine.Tables
import yacht.engine.game.DiceSource
import yacht.engine.game.Game
import yacht.engine.game.GameCodec
import yacht.engine.game.GameState
import yacht.engine.game.Side
import yacht.engine.game.label
import java.io.File
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Saved games and settings on disk. Plain java.io so it works on Android and the desktop UI check. */
class GameStorage(private val dir: File) {
    private val current = File(dir, "current.txt")
    private val settings = File(dir, "settings.txt")
    private val finished = File(dir, "games").apply { mkdirs() }

    fun loadCurrent(): String? = current.takeIf { it.exists() }?.readText()
    fun saveCurrent(text: String?) {
        if (text == null) current.delete() else current.writeText(text)
    }

    fun loadHistory(): List<String> =
        finished.listFiles()?.filter { it.name.endsWith(".txt") }?.map { it.readText() } ?: emptyList()

    fun saveFinished(id: Long, text: String) = File(finished, "$id.txt").writeText(text)

    fun loadSettings(): Map<String, String> = settings.takeIf { it.exists() }?.readLines()
        ?.mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }
        ?.toMap() ?: emptyMap()

    fun saveSettings(values: Map<String, String>) =
        settings.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" })
}

enum class Screen { HOME, GAME, HISTORY, REVIEW, VERIFY }

/** How long the app may think about one turn (the first answer is always immediate). */
enum class ThinkTime(val seconds: Double, val label: String) {
    FAST(1.0, "빠름 1초"),
    NORMAL(2.5, "보통 2.5초"),
    MAX(4.0, "최대 4초"),
}

/** Live result of the on-device verification (duplicate games against a max-expectation AI). */
data class VerifyStats(
    val games: Int = 0,
    val target: Int = 0,
    val winAi: Int = 0,
    val winEv: Int = 0,
    /** Sum and sum of squares of the paired difference (win AI - EV AI), per game-seat. */
    val diffSum: Double = 0.0,
    val diffSq: Double = 0.0,
    val scoreAi: Long = 0,
    val scoreEv: Long = 0,
    val planNanos: Long = 0,
    val plans: Int = 0,
) {
    private val n get() = games * 2
    val winRateAi get() = if (n == 0) 0.0 else winAi.toDouble() / n
    val winRateEv get() = if (n == 0) 0.0 else winEv.toDouble() / n
    val diff get() = if (n == 0) 0.0 else diffSum / n

    /** 95% half-width of the paired difference. */
    val diffCi: Double
        get() {
            if (n < 2) return 0.0
            val mean = diff
            val variance = (diffSq - n * mean * mean) / (n - 1)
            return 1.96 * sqrt(variance.coerceAtLeast(0.0) / n)
        }
    val avgPlanMs get() = if (plans == 0) 0.0 else planNanos / 1e6 / plans
}

/** All app state and actions; the Android ViewModel only owns its lifetime. */
class AppController(
    private val scope: CoroutineScope,
    private val storage: GameStorage,
    private val loadTables: () -> Tables,
) {
    var screen by mutableStateOf(Screen.HOME)
    var tables by mutableStateOf<Tables?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf<GameState?>(null)
        private set

    /** Plan of my current turn; null while computing. Improves while [refining]. */
    var plan by mutableStateOf<PlannedTurn?>(null)
        private set
    var refining by mutableStateOf(false)
        private set
    var objective by mutableStateOf(Mode.WIN)
        private set
    var thinkTime by mutableStateOf(ThinkTime.NORMAL)
        private set
    var showHints by mutableStateOf(true)
    var aiThinking by mutableStateOf(false)
        private set
    var history by mutableStateOf<List<GameState>>(emptyList())
        private set
    var reviewing by mutableStateOf<GameState?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var verify by mutableStateOf(VerifyStats())
        private set
    var verifying by mutableStateOf(false)
        private set

    private var advisor: Advisor? = null
    private val cores = Runtime.getRuntime().availableProcessors()
    private val pool = Executors.newFixedThreadPool(maxOf(2, cores)) { r ->
        Thread(r, "yacht-search").apply { isDaemon = true }
    }

    /** Low-priority threads for the one-off duel table, so turn planning stays responsive. */
    private val background = Executors.newFixedThreadPool(maxOf(1, cores - 1)) { r ->
        Thread(r, "yacht-duel").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val rng = Random()
    private val undoStack = ArrayDeque<GameState>()
    private var planKey: Triple<Board, Board, Mode>? = null
    private var planJob: Job? = null
    private var aiJob: Job? = null
    private var verifyJob: Job? = null

    /**
     * Exact duel table for the rest of the current game, built once in the background as soon as
     * it is affordable and then reused for every later turn (no per-turn search, less battery).
     */
    private var duelTable: DuelSearch? = null
    private var duelJob: Job? = null
    private var duelStop: AtomicBoolean? = null
    private var duelRoot: Pair<Board, Board>? = null

    /** Measured search speed of this device (operations per second), for the duel budget. */
    private var opsPerSecond = 2e8
    private var foreground = true

    /** Status for the UI: building the exact end-game table in the background. */
    var duelBuilding by mutableStateOf(false)
        private set

    /** Holds currently equal the advisor's recommendation (re-applied when the plan refines). */
    private var autoHeld = false

    fun start() {
        scope.launch {
            try {
                val t = withContext(Dispatchers.IO) { loadTables() }
                advisor = Advisor(t, pool)
                tables = t
                val settings = withContext(Dispatchers.IO) { storage.loadSettings() }
                settings["objective"]?.let { v -> Mode.entries.firstOrNull { it.name == v }?.let { objective = it } }
                settings["think"]?.let { v -> ThinkTime.entries.firstOrNull { it.name == v }?.let { thinkTime = it } }
                settings["hints"]?.let { showHints = it == "1" }
                val saved = withContext(Dispatchers.IO) {
                    storage.loadCurrent()?.let { runCatching { GameCodec.decode(it, t.rules) }.getOrNull() }
                }
                history = withContext(Dispatchers.IO) { readHistory() }
                if (saved != null) {
                    state = saved
                    if (!saved.isOver) {
                        screen = Screen.GAME
                        onStateChanged(persist = false)
                    }
                }
            } catch (e: Throwable) {
                loadError = e.message ?: e.toString()
            }
        }
    }

    fun shutdown() {
        verifyJob?.cancel()
        stopDuel()
        pool.shutdown()
        background.shutdown()
    }

    /** App visible again: resume background work that was paused. */
    fun onForeground() {
        foreground = true
        state?.let { maybeStartDuel(it) }
    }

    /** App hidden: stop background computation to save battery. */
    fun onBackground() {
        foreground = false
        stopDuel()
        verifyJob?.cancel()
    }

    private fun readHistory(): List<GameState> =
        storage.loadHistory().mapNotNull { runCatching { GameCodec.decode(it) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    private fun saveSettings() {
        val values = mapOf("objective" to objective.name, "think" to thinkTime.name, "hints" to if (showHints) "1" else "0")
        scope.launch(Dispatchers.IO) { storage.saveSettings(values) }
    }

    // ---- derived -----------------------------------------------------------------------------

    /** Advice for the dice on the table, if it is my decision and the plan is ready. */
    val advice: Advice?
        get() {
            val s = state ?: return null
            val p = plan ?: return null
            if (s.toMove != Side.ME || !s.draft.deciding || p.me != s.me) return null
            return p.advise(s.draft.rollsDone, s.draft.faces)
        }

    private val currentPlan: PlannedTurn? get() = plan?.takeIf { it.me == state?.me }

    /** P(win) or expected final score before my next throw. */
    val turnValue: Double? get() = currentPlan?.startValue

    /** Turns searched exactly by the current plan, and whether that reaches the end of the game. */
    val searchDepth: Int get() = currentPlan?.depth ?: 0
    val searchDuel: Boolean get() = currentPlan?.duel == true
    val searchExact: Boolean
        get() {
            val p = currentPlan ?: return false
            return p.mode == Mode.WIN && p.depth >= Category.COUNT - p.me.filledCount
        }

    // ---- settings ----------------------------------------------------------------------------

    fun changeObjective(mode: Mode) {
        objective = mode
        saveSettings()
        state?.let { if (!it.isOver && it.toMove == Side.ME) ensurePlan(it) }
    }

    fun changeThinkTime(t: ThinkTime) {
        thinkTime = t
        saveSettings()
    }

    fun toggleHints() {
        showHints = !showHints
        saveSettings()
    }

    // ---- game actions ------------------------------------------------------------------------

    fun newGame(source: DiceSource, first: Side) {
        val t = tables ?: return
        undoStack.clear()
        canUndo = false
        aiJob?.cancel()
        aiThinking = false
        stopDuel()
        duelTable = null
        state = GameState.new(source, first, t.rules)
        screen = Screen.GAME
        onStateChanged()
    }

    fun enterFace(face: Int) = act { Game.enterFace(it, face) }
    fun backspace() = act { Game.backspace(it) }
    fun clearSlot(slot: Int) = act { Game.clearSlot(it, slot) }

    fun toggleHold(slot: Int) {
        act { Game.toggleHold(it, slot) }
        autoHeld = false
    }

    fun holdRecommended() {
        val keep = advice?.bestKeep ?: return
        act { Game.holdFaces(it, keep.held) }
        autoHeld = true
    }

    fun reroll() = act { Game.reroll(it, advice, rng) }
    fun firstThrow() = act { Game.firstThrow(it, rng) }
    fun score(c: Category) = act { Game.score(it, c, advice) }
    fun opponentScore(c: Category, points: Int) = act { Game.opponentScore(it, c, points) }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        canUndo = undoStack.isNotEmpty()
        state = prev
        autoHeld = false
        onStateChanged()
    }

    fun openReview(game: GameState) {
        reviewing = game
        screen = Screen.REVIEW
    }

    fun openHistory() {
        scope.launch {
            history = withContext(Dispatchers.IO) { readHistory() }
            screen = Screen.HISTORY
        }
    }

    private inline fun act(f: (GameState) -> GameState) {
        val s = state ?: return
        val next = f(s)
        if (next == s) return
        if (s.source == DiceSource.MANUAL) {
            undoStack.addLast(s)
            if (undoStack.size > 200) undoStack.removeFirst()
            canUndo = true
        }
        val justThrown = next.draft.deciding && next.draft.rollsDone != s.draft.rollsDone
        state = next
        if (justThrown) autoHold()
        onStateChanged()
    }

    private fun onStateChanged(persist: Boolean = true) {
        val s = state ?: return
        if (persist) {
            val text = GameCodec.encode(s)
            scope.launch(Dispatchers.IO) {
                storage.saveCurrent(text)
                if (s.isOver) storage.saveFinished(s.createdAt, text)
            }
        }
        if (s.isOver) return
        val table = duelTable
        if (table != null && !table.contains(s.me, s.opp)) duelTable = null
        when (s.toMove) {
            Side.ME -> ensurePlan(s)
            Side.OPP -> if (s.source == DiceSource.RANDOM) runAi(s)
        }
        maybeStartDuel(s)
    }

    private fun stopDuel() {
        duelStop?.set(true)
        duelJob?.cancel()
        duelJob = null
        duelRoot = null
        duelBuilding = false
    }

    /**
     * Starts building the exact duel table from the current position when it is not covered yet
     * and the estimated work fits [DUEL_SECONDS] on this device.
     */
    private fun maybeStartDuel(s: GameState) {
        val adv = advisor ?: return
        val t = tables ?: return
        if (!foreground || objective != Mode.WIN || s.isOver || s.me.isComplete) return
        if (duelTable?.contains(s.me, s.opp) == true) return
        // A table being built from an earlier position of this game will cover this one too.
        val root = duelRoot
        if (root != null && s.me.mask and root.first.mask == root.first.mask &&
            s.opp.mask and root.second.mask == root.second.mask
        ) return
        val meToMove = s.toMove == Side.ME
        val ops = DuelSearch.estimateOps(t, s.me, s.opp, meToMove)
        if (ops == Long.MAX_VALUE || ops > opsPerSecond * DUEL_SECONDS) return
        stopDuel()
        val stop = AtomicBoolean(false)
        duelStop = stop
        duelRoot = s.me to s.opp
        duelBuilding = true
        duelJob = scope.launch {
            try {
                val table = withContext(Dispatchers.Default) {
                    adv.buildDuelTable(s.me, s.opp, meToMove, background, stop)
                }
                if (stop.get()) return@launch
                duelTable = table
                val cur = state
                // A plan from the table replaces the current turn's searched plan right away.
                if (cur != null && cur.toMove == Side.ME && !cur.isOver && table.after(cur.me, cur.opp) != null) {
                    planKey = null
                    ensurePlan(cur)
                }
            } catch (e: SearchTimeout) {
                // stopped (new game, undo, app hidden)
            } finally {
                if (duelStop === stop) {
                    duelBuilding = false
                    duelRoot = null
                }
            }
        }
    }

    /** After a throw, pre-select the recommended dice so "throw again" follows the advice. */
    private fun autoHold() {
        val s = state ?: return
        if (s.source == DiceSource.RANDOM && !showHints) return
        val a = advice ?: return
        val keep = a.bestKeep ?: return
        if (a.shouldStop) return
        state = Game.holdFaces(s, keep.held)
        autoHeld = true
    }

    private fun ensurePlan(s: GameState) {
        val adv = advisor ?: return
        val key = Triple(s.me, s.opp, objective)
        if (key == planKey) return
        planKey = key
        plan = null
        planJob?.cancel()
        val deadline = System.nanoTime() + (thinkTime.seconds * 1e9).toLong()
        planJob = scope.launch {
            refining = true
            try {
                withContext(Dispatchers.Default) {
                    if (objective == Mode.WIN) {
                        val t0 = System.nanoTime()
                        val table = duelTable
                        val p = adv.planTurnAnytime(s.me, s.opp, deadline, table = table) { p -> scope.launch { publish(key, p) } }
                        if (!p.duel || table == null) calibrate(s.me, s.opp, p.depth, System.nanoTime() - t0)
                    } else {
                        val p = adv.planTurn(s.me, s.opp, Mode.EXPECTED_SCORE)
                        scope.launch { publish(key, p) }
                    }
                }
            } finally {
                if (planKey == key) refining = false
            }
        }
    }

    /** Updates the device speed estimate from a finished search (used to budget the duel table). */
    private fun calibrate(me: Board, opp: Board, depth: Int, nanos: Long) {
        val t = tables ?: return
        if (nanos < 50_000_000L) return
        val ops = yacht.engine.WinSearch.estimateOps(t, me, depth, opp)
        if (ops <= 0) return
        val rate = ops / (nanos / 1e9)
        opsPerSecond = 0.7 * opsPerSecond + 0.3 * rate
    }

    private fun publish(key: Triple<Board, Board, Mode>, p: PlannedTurn) {
        if (planKey != key) return
        val first = plan == null
        plan = p
        val st = state ?: return
        if (st.draft.deciding && (autoHeld || (first && st.draft.held.none { it }))) autoHold()
    }

    private fun runAi(s: GameState) {
        val adv = advisor ?: return
        aiJob?.cancel()
        aiJob = scope.launch {
            aiThinking = true
            try {
                delay(350)
                val (c, points, log) = withContext(Dispatchers.Default) { aiTurn(adv, s) }
                if (state == s) {
                    state = Game.opponentScore(s, c, points, log)
                    onStateChanged()
                }
            } finally {
                aiThinking = false
            }
        }
    }

    private fun aiTurn(adv: Advisor, s: GameState): Triple<Category, Int, String> {
        val deadline = System.nanoTime() + (thinkTime.seconds * 1e9).toLong()
        val turn = adv.planTurnAnytime(s.opp, s.me, deadline)
        val throws = ArrayList<List<Int>>()
        var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
        throws += Dice.rollFaces(r)
        for (rollsDone in 1..2) {
            val k = turn.bestKeep(rollsDone, r)
            if (k == Dice.rollAsKeep[r]) break
            r = Match.throwDice(rng, k)
            throws += Dice.rollFaces(r)
        }
        val c = turn.bestCategory(r)
        val points = adv.tables.scores[r, c.ordinal]
        val text = "턴 ${s.opp.filledCount + 1}: " + throws.joinToString(" → ") { it.joinToString("") } +
            " ⇒ ${c.label} ${points}점"
        return Triple(c, points, text)
    }

    // ---- on-device verification ---------------------------------------------------------------

    /**
     * Plays [games] duplicate games: the win AI and the max-expectation AI each play the same
     * dice against the same max-expectation opponent, in both seats. The paired difference shows
     * how much the win policy adds, with a 95% interval.
     */
    fun startVerify(games: Int) {
        val t = tables ?: return
        verifyJob?.cancel()
        verify = VerifyStats(target = games)
        verifying = true
        val seed = System.currentTimeMillis()
        verifyJob = scope.launch {
            try {
                val plain = Advisor(t)
                for (g in 0 until games) {
                    if (!isActive) break
                    val result = withContext(Dispatchers.Default) { verifyGame(plain, seed, g) }
                    verify = result(verify)
                }
            } finally {
                verifying = false
            }
        }
    }

    fun stopVerify() {
        verifyJob?.cancel()
    }

    private fun verifyGame(adv: Advisor, seed: Long, g: Int): (VerifyStats) -> VerifyStats {
        val rules = adv.tables.rules
        val opp = Duplicate.independentGame(rules, AdvisorPlayer(adv, Mode.EXPECTED_SCORE), DiceStream(DiceStream.mix(seed, g.toLong(), 1)))
        val oppFinal = opp.last().total
        val myStream = DiceStream(DiceStream.mix(seed, g.toLong(), 0))
        var nanos = 0L
        var plans = 0
        val timed = object : yacht.engine.Player {
            val inner = AdvisorPlayer(adv, Mode.WIN, budgetOps = VERIFY_BUDGET)
            override val name = "win"
            override fun startTurn(me: Board, opp: Board) {
                val t0 = System.nanoTime()
                inner.startTurn(me, opp)
                nanos += System.nanoTime() - t0
                plans++
            }
            override fun chooseKeep(rollsDone: Int, r: Int) = inner.chooseKeep(rollsDone, r)
            override fun chooseCategory(r: Int) = inner.chooseCategory(r)
        }
        val ev = AdvisorPlayer(adv, Mode.EXPECTED_SCORE)
        var winAi = 0
        var winEv = 0
        var dSum = 0.0
        var dSq = 0.0
        var sAi = 0L
        var sEv = 0L
        for (seat in 0..1) {
            val a = Duplicate.playTurns(rules, timed, myStream) { turn -> opp[turn + seat] }.total
            val e = Duplicate.playTurns(rules, ev, myStream) { turn -> opp[turn + seat] }.total
            val wa = if (a > oppFinal) 1 else 0
            val we = if (e > oppFinal) 1 else 0
            winAi += wa
            winEv += we
            dSum += (wa - we).toDouble()
            dSq += ((wa - we) * (wa - we)).toDouble()
            sAi += a
            sEv += e
        }
        return { v ->
            v.copy(
                games = v.games + 1, winAi = v.winAi + winAi, winEv = v.winEv + winEv,
                diffSum = v.diffSum + dSum, diffSq = v.diffSq + dSq, scoreAi = v.scoreAi + sAi, scoreEv = v.scoreEv + sEv,
                planNanos = v.planNanos + nanos, plans = v.plans + plans,
            )
        }
    }

    companion object {
        /** Search budget of the win AI in verification games (deterministic, a few hundred ms per turn). */
        const val VERIFY_BUDGET = 100_000_000L

        /** Longest background computation allowed for the exact duel table (once per game). */
        const val DUEL_SECONDS = 25.0
    }
}

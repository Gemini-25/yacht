package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yacht.engine.Advice
import yacht.engine.Advisor
import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Dice
import yacht.engine.Match
import yacht.engine.Mode
import yacht.engine.PlannedTurn
import yacht.engine.Tables
import yacht.engine.WinSearch
import yacht.engine.game.DiceSource
import yacht.engine.game.Game
import yacht.engine.game.GameCodec
import yacht.engine.game.GameState
import yacht.engine.game.Side
import yacht.engine.game.label
import java.io.File
import java.util.Random
import java.util.concurrent.Executors

/** Saved games on disk. Plain java.io so it works on Android and the desktop UI check. */
class GameStorage(private val dir: File) {
    private val current = File(dir, "current.txt")
    private val finished = File(dir, "games").apply { mkdirs() }

    fun loadCurrent(): String? = current.takeIf { it.exists() }?.readText()
    fun saveCurrent(text: String?) {
        if (text == null) current.delete() else current.writeText(text)
    }

    fun loadHistory(): List<String> =
        finished.listFiles()?.filter { it.name.endsWith(".txt") }?.map { it.readText() } ?: emptyList()

    fun saveFinished(id: Long, text: String) = File(finished, "$id.txt").writeText(text)
}

enum class Screen { HOME, GAME, HISTORY, REVIEW }

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

    /** Plan of my current turn; null while computing. */
    var plan by mutableStateOf<PlannedTurn?>(null)
        private set
    var refining by mutableStateOf(false)
        private set
    var objective by mutableStateOf(Mode.WIN)
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

    private var advisor: Advisor? = null
    private val pool = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors()))
    private val rng = Random()
    private val undoStack = ArrayDeque<GameState>()
    private var planKey: Triple<Board, Board, Mode>? = null
    private var planJob: Job? = null
    private var aiJob: Job? = null

    /** Holds currently equal the advisor's recommendation (re-applied when the plan refines). */
    private var autoHeld = false

    fun start() {
        scope.launch {
            try {
                val t = withContext(Dispatchers.IO) { loadTables() }
                advisor = Advisor(t, pool)
                tables = t
                val saved = withContext(Dispatchers.IO) {
                    storage.loadCurrent()?.let { runCatching { GameCodec.decode(it, t.rules) }.getOrNull() }
                }
                history = withContext(Dispatchers.IO) { readHistory() }
                if (saved != null && !saved.isOver) {
                    state = saved
                    screen = Screen.GAME
                    onStateChanged(persist = false)
                }
            } catch (e: Throwable) {
                loadError = e.message ?: e.toString()
            }
        }
    }

    fun shutdown() = pool.shutdown()

    private fun readHistory(): List<GameState> =
        storage.loadHistory().mapNotNull { runCatching { GameCodec.decode(it) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    // ---- derived -----------------------------------------------------------------------------

    /** Advice for the dice on the table, if it is my decision and the plan is ready. */
    val advice: Advice?
        get() {
            val s = state ?: return null
            val p = plan ?: return null
            if (s.toMove != Side.ME || !s.draft.deciding || p.me != s.me) return null
            return p.advise(s.draft.rollsDone, s.draft.faces)
        }

    /** P(win) or expected final score before my next throw. */
    val turnValue: Double? get() = plan?.takeIf { it.me == state?.me }?.startValue

    // ---- game actions ------------------------------------------------------------------------

    fun newGame(source: DiceSource, first: Side) {
        val t = tables ?: return
        undoStack.clear()
        canUndo = false
        aiJob?.cancel()
        aiThinking = false
        state = GameState.new(source, first, t.rules)
        screen = Screen.GAME
        onStateChanged()
    }

    fun changeObjective(mode: Mode) {
        objective = mode
        state?.let { ensurePlan(it) }
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
        when (s.toMove) {
            Side.ME -> ensurePlan(s)
            Side.OPP -> if (s.source == DiceSource.RANDOM) runAi(s)
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
        planJob = scope.launch {
            val quick = withContext(Dispatchers.Default) { adv.planTurn(s.me, s.opp, objective, depth = 1) }
            if (planKey != key) return@launch
            plan = quick
            val st = state
            if (st != null && st.draft.deciding && (autoHeld || st.draft.held.none { it })) autoHold()
            if (objective != Mode.WIN) return@launch
            val t = tables ?: return@launch
            val d = WinSearch.chooseDepth(t, s.me, Advisor.DEFAULT_BUDGET)
            if (d <= 1) return@launch
            refining = true
            try {
                val full = withContext(Dispatchers.Default) { adv.planTurn(s.me, s.opp, objective, depth = d) }
                if (planKey == key) {
                    plan = full
                    if (autoHeld) autoHold()
                }
            } finally {
                if (planKey == key) refining = false
            }
        }
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
        val turn = adv.planTurn(s.opp, s.me, Mode.WIN)
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
}

package yacht.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import yacht.engine.CategoryOption
import yacht.engine.Dice
import yacht.engine.DuelSearch
import yacht.engine.SearchTimeout
import yacht.engine.DiceStream
import yacht.engine.Duplicate
import yacht.engine.KeepOption
import yacht.engine.Match
import yacht.engine.Mode
import yacht.engine.PlannedTurn
import yacht.engine.Tables
import yacht.engine.Outlook
import yacht.engine.explain.Explainer
import yacht.engine.explain.Explanation
import yacht.engine.explain.HabitReport
import yacht.engine.explain.Habits
import yacht.engine.explain.LuckAnalysis
import yacht.engine.explain.LuckReport
import yacht.engine.explain.OpponentOutlook
import yacht.engine.explain.OutlookAnalysis
import yacht.engine.explain.Quiz
import yacht.engine.explain.QuizQuestion
import yacht.engine.explain.QuizResult
import yacht.engine.explain.WhatIf
import yacht.engine.explain.WhatIfAnalysis
import yacht.engine.game.DecisionRecord
import yacht.engine.game.DecisionNote
import yacht.engine.game.DiceSource
import yacht.engine.game.Game
import yacht.engine.game.GameCodec
import yacht.engine.game.GameState
import yacht.engine.game.MoveRecord
import yacht.engine.game.PlayStyle
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

    /** A finished game reopened by undo is not finished any more. */
    fun deleteFinished(id: Long) = File(finished, "$id.txt").delete()

    fun loadSettings(): Map<String, String> = settings.takeIf { it.exists() }?.readLines()
        ?.mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }
        ?.toMap() ?: emptyMap()

    fun saveSettings(values: Map<String, String>) =
        settings.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" })
}

enum class Screen { HOME, GAME, HISTORY, REVIEW, VERIFY, QUIZ }

/** Practice record (saved). */
data class QuizStats(val answered: Int = 0, val correct: Int = 0, val lossSum: Double = 0.0)

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

    /** How the next game is played: with recommendations or alone (saved, last choice). */
    var nextStyle by mutableStateOf(PlayStyle.ASSISTED)
        private set

    /** Opponent tags used before, most recent first (saved). */
    var tags by mutableStateOf<List<String>>(emptyList())
        private set
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

    /** "왜?" details stay open across turns once opened (saved). */
    var whyOpen by mutableStateOf(false)
        private set

    /** Why the recommendation was better, for my last move when it differed; cleared by the next move. */
    var lastNote by mutableStateOf<String?>(null)
        private set

    /** Before the opponent's turn: how its result would move my P(win) (WIN mode). */
    var oppOutlook by mutableStateOf<OpponentOutlook?>(null)
        private set
    private var outlookKey: Pair<Board, Board>? = null
    private var outlookJob: Job? = null

    /** Review: "추천대로 뒀다면?" for decisions opened so far, and those still computing. */
    var whatIfs by mutableStateOf<Map<DecisionRecord, WhatIf>>(emptyMap())
        private set
    var whatIfBusy by mutableStateOf<Set<DecisionRecord>>(emptySet())
        private set

    private var advisor: Advisor? = null
    private var explainer: Explainer? = null
    private var quizEngine: Quiz? = null
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
                explainer = Explainer(t).also { quizEngine = Quiz(Advisor(t, pool), it) }
                tables = t
                val settings = withContext(Dispatchers.IO) { storage.loadSettings() }
                settings["objective"]?.let { v -> Mode.entries.firstOrNull { it.name == v }?.let { objective = it } }
                settings["think"]?.let { v -> ThinkTime.entries.firstOrNull { it.name == v }?.let { thinkTime = it } }
                settings["style"]?.let { v -> PlayStyle.entries.firstOrNull { it.name == v && it != PlayStyle.PEEKED }?.let { nextStyle = it } }
                settings["tags"]?.let { v -> tags = v.split(TAG_SEP).filter { it.isNotBlank() } }
                settings["why"]?.let { whyOpen = it == "1" }
                quizStats = QuizStats(
                    settings["quizAnswered"]?.toIntOrNull() ?: 0,
                    settings["quizCorrect"]?.toIntOrNull() ?: 0,
                    settings["quizLoss"]?.toDoubleOrNull() ?: 0.0,
                )
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
        prefetch?.cancel()
        prefetch = null
    }

    private fun readHistory(): List<GameState> =
        storage.loadHistory().mapNotNull { runCatching { GameCodec.decode(it) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    private fun saveSettings() {
        val values = mapOf(
            "objective" to objective.name,
            "think" to thinkTime.name,
            "style" to nextStyle.name,
            "tags" to tags.joinToString(TAG_SEP),
            "why" to if (whyOpen) "1" else "0",
            "quizAnswered" to quizStats.answered.toString(),
            "quizCorrect" to quizStats.correct.toString(),
            "quizLoss" to quizStats.lossSum.toString(),
        )
        scope.launch(Dispatchers.IO) { storage.saveSettings(values) }
    }

    // ---- derived -----------------------------------------------------------------------------

    /**
     * Advice for the dice on the table, if it is my decision and the plan is ready. Computed in
     * every game (for the record and the review); [shownAdvice] is what the player may see.
     */
    val advice: Advice?
        get() {
            val s = state ?: return null
            val p = plan ?: return null
            if (s.toMove != Side.ME || !s.draft.deciding || p.me != s.me) return null
            return p.advise(s.draft.rollsDone, s.draft.faces)
        }

    private val currentPlan: PlannedTurn? get() = plan?.takeIf { it.me == state?.me }

    /** AI information is hidden while playing alone, until the game is over. */
    val hintsHidden: Boolean get() = state?.hintsShown == false

    /** The advice when it may be shown. */
    val shownAdvice: Advice? get() = if (hintsHidden) null else advice

    /** P(win) or expected final score before my next throw. */
    val turnValue: Double? get() = currentPlan?.startValue

    /** Win/draw/loss right now in my turn (WIN mode), from the dice on the table when thrown. */
    val outlook: Outlook? get() = advice?.outlook ?: currentPlan?.startOutlook

    /** Turns searched exactly by the current plan, and whether that reaches the end of the game. */
    val searchDepth: Int get() = currentPlan?.depth ?: 0
    val searchDuel: Boolean get() = currentPlan?.duel == true
    val searchExact: Boolean
        get() {
            val p = currentPlan ?: return false
            return p.mode == Mode.WIN && p.depth >= Category.COUNT - p.me.filledCount
        }

    // ---- explanations ------------------------------------------------------------------------

    private val explainCache = object : LinkedHashMap<Any, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Any>?) = size > 64
    }

    /** Explanations are cheap (a few forward passes) but asked for on every recomposition: memoised. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: Any, make: () -> T?): T? = synchronized(explainCache) {
        explainCache[key]?.let { return it as T }
        val v = try {
            make()
        } catch (e: RuntimeException) {
            null
        } ?: return null
        explainCache[key] = v
        v
    }

    /** Why the current recommendation is the best move. */
    val adviceExplanation: Explanation?
        get() {
            val e = explainer ?: return null
            val s = state ?: return null
            val p = currentPlan ?: return null
            val a = advice ?: return null
            return cached(listOf("advice", p, a.rollsDone, a.dice)) { e.advice(p, s.opp, a) }
        }

    /** How keeping [k] compares with the recommendation. */
    fun keepExplanation(k: KeepOption): Explanation? {
        val e = explainer ?: return null
        val s = state ?: return null
        val p = currentPlan ?: return null
        val a = advice ?: return null
        return cached(listOf("keep", p, a.rollsDone, a.dice, k.held)) { e.keepOption(p, s.opp, a, k) }
    }

    /** How writing into [o] now compares with the recommendation. */
    fun categoryExplanation(o: CategoryOption): Explanation? {
        val e = explainer ?: return null
        val s = state ?: return null
        val p = currentPlan ?: return null
        val a = advice ?: return null
        return cached(listOf("category", p, a.rollsDone, a.dice, o.category)) { e.categoryOption(p, s.opp, a, o) }
    }

    /** The game from my side during my turn, with my current P(win) in WIN mode. */
    val situation: Explanation?
        get() {
            val e = explainer ?: return null
            val s = state ?: return null
            if (s.isOver || s.toMove != Side.ME) return null
            val p = currentPlan
            val win = if (p != null && p.mode == Mode.WIN) advice?.value ?: p.startValue else null
            if (objective == Mode.WIN && win == null) return null
            return cached(listOf("situation", s.me, s.opp, win)) { e.situation(s.me, s.opp, win) }
        }

    /** Comment on the opponent's last move, until my turn starts. */
    val opponentComment: String?
        get() {
            val e = explainer ?: return null
            val s = state ?: return null
            val last = s.moves.lastOrNull() ?: return null
            if (last.side != Side.OPP) return null
            val before = last.winBefore ?: s.moves.dropLast(1).lastOrNull { it.side == Side.ME }?.winAfter
            val who = if (s.source == DiceSource.RANDOM) "AI" else "상대"
            return cached(listOf("opponent", s.moves.size, last, before)) { e.opponentMove(s.opp, last, before, who) }
        }

    fun gameSummary(g: GameState): Explanation? {
        val e = explainer ?: return null
        return cached(listOf("summary", g.createdAt, g.moves.size, g.decisions.size)) { e.gameSummary(g) }
    }

    /** How lucky the game was for both sides (review). */
    fun luck(g: GameState): LuckReport? {
        val t = tables ?: return null
        if (!g.isOver) return null
        return cached(listOf("luck", g.createdAt, g.moves.size, g.decisions.size)) { LuckAnalysis(t).of(g) }
    }

    /** Starts computing "추천대로 뒀다면?" for [d] (review); the result lands in [whatIfs]. */
    fun loadWhatIf(d: DecisionRecord) {
        val adv = advisor ?: return
        val t = tables ?: return
        val me = d.me ?: return
        val opp = d.opp ?: return
        if (d in whatIfs || d in whatIfBusy) return
        whatIfBusy = whatIfBusy + d
        scope.launch {
            try {
                val w = withContext(Dispatchers.Default) {
                    runCatching {
                        val turn = adv.planTurn(me, opp, d.mode, budgetOps = REVIEW_BUDGET)
                        WhatIfAnalysis(t).of(turn, d)
                    }.getOrNull()
                }
                if (w != null) whatIfs = whatIfs + (d to w)
            } finally {
                whatIfBusy = whatIfBusy - d
            }
        }
    }

    /**
     * The opponent's last turn, at the start of mine: what it scored, how that moved my P(win),
     * and (vs AI) why the AI played so.
     */
    val lastOpponentMove: MoveRecord?
        get() {
            val s = state ?: return null
            if (s.toMove != Side.ME || s.draft.rollsDone != 0) return null
            return s.moves.lastOrNull()?.takeIf { it.side == Side.OPP }
        }

    /** Patterns in my recent mistakes (finished games). */
    val habits: HabitReport?
        get() {
            val h = history
            return cached(listOf("habits", h.size, h.firstOrNull()?.createdAt)) { Habits.analyze(h) }
        }

    fun toggleWhy() {
        whyOpen = !whyOpen
        saveSettings()
    }

    fun dismissNote() {
        lastNote = null
    }

    /** Note for holding the current dice and throwing again, if that differs from the advice. */
    private fun keepNote(): yacht.engine.explain.Deviation? {
        val e = explainer ?: return null
        val s = state ?: return null
        val p = currentPlan ?: return null
        val a = advice ?: return null
        val opt = a.keeps.firstOrNull { it.held == s.draft.heldFaces } ?: return null
        return runCatching { e.keepDeviation(p, s.opp, a, opt) }.getOrNull()
    }

    private fun scoreNote(c: Category): yacht.engine.explain.Deviation? {
        val e = explainer ?: return null
        val s = state ?: return null
        val p = currentPlan ?: return null
        val a = advice ?: return null
        val opt = a.categories.firstOrNull { it.category == c } ?: return null
        return runCatching { e.categoryDeviation(p, s.opp, a, opt) }.getOrNull()
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

    fun changeStyle(style: PlayStyle) {
        nextStyle = style
        saveSettings()
    }

    /** Playing alone: look at the AI's advice anyway; the game then counts as assisted. */
    fun peek() {
        val s = state ?: return
        if (s.style != PlayStyle.SOLO) return
        state = Game.peek(s)
        onStateChanged()
        if (state?.draft?.deciding == true && state?.draft?.held?.none { it } == true) autoHold()
    }

    // ---- game actions ------------------------------------------------------------------------

    /** [opponent]: optional tag for real games ("친구 A"); the style is [nextStyle]. */
    fun newGame(source: DiceSource, first: Side, opponent: String? = null) {
        val t = tables ?: return
        val tag = opponent?.trim()?.ifEmpty { null }
        if (tag != null) {
            tags = (listOf(tag) + tags.filter { it != tag }).take(MAX_TAGS)
            saveSettings()
        }
        undoStack.clear()
        canUndo = false
        aiJob?.cancel()
        aiThinking = false
        lastNote = null
        stopDuel()
        duelTable = null
        oppOutlook = null
        outlookKey = null
        state = GameState.new(source, first, t.rules, nextStyle, if (source == DiceSource.MANUAL) tag else null)
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

    fun reroll() {
        val note = keepNote()
        val before = state
        val v = state?.let { valueBeforeThrow(it) }
        act { Game.reroll(it, advice, rng, note?.let { n -> DecisionNote(n.reason, n.chosenSd, n.bestSd) }, v) }
        if (state !== before) lastNote = note?.full?.takeIf { !hintsHidden }
    }

    fun firstThrow() = act { Game.firstThrow(it, rng) }

    fun score(c: Category) {
        val note = scoreNote(c)
        val before = state
        val v = state?.let { valueBeforeThrow(it) }
        act { Game.score(it, c, advice, note?.let { n -> DecisionNote(n.reason, n.chosenSd, n.bestSd) }, v) }
        if (state !== before) lastNote = note?.full?.takeIf { !hintsHidden }
    }

    /**
     * Value of my position just before the throw now on the table, from the current plan: the
     * turn's start, or the dice held at my previous decision of this turn.
     */
    private fun valueBeforeThrow(s: GameState): Double? {
        val p = currentPlan ?: return null
        val d = s.draft
        if (!d.deciding) return null
        if (d.rollsDone == 1) return p.startValue
        val prev = s.decisions.lastOrNull()?.takeIf { it.turn == s.myTurnNumber && it.rollsDone == d.rollsDone - 1 } ?: return null
        val held = prev.chosenHeld ?: return null
        return runCatching { p.keepValue(d.rollsDone - 1, held) }.getOrNull()
    }

    /** My P(win) before the opponent's turn now on the table, when known. */
    private fun winBeforeOpponent(s: GameState): Double? =
        oppOutlook?.takeIf { outlookKey == (s.me to s.opp) }?.now
            ?: s.moves.lastOrNull()?.takeIf { it.side == Side.ME }?.winAfter

    fun opponentScore(c: Category, points: Int) {
        lastNote = null
        act { Game.opponentScore(it, c, points, winBefore = winBeforeOpponent(it)) }
    }

    /**
     * Back to the state before my last action. Against the AI only actions that revealed no new
     * dice can be undone (the history is cleared at every throw and at every AI turn), so undo
     * fixes a mis-tap but never buys a re-throw. A box written before the AI's turn can be taken
     * back while the AI is still thinking.
     */
    fun undo() {
        val cur = state ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        canUndo = undoStack.isNotEmpty()
        aiJob?.cancel()
        aiThinking = false
        if (cur.isOver && !prev.isOver) scope.launch(Dispatchers.IO) { storage.deleteFinished(cur.createdAt) }
        // A hint once seen stays seen: undo never turns a game back into one played alone.
        state = if (cur.style == PlayStyle.PEEKED) prev.copy(style = PlayStyle.PEEKED) else prev
        autoHeld = false
        lastNote = null
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
        // Against the AI a throw shows new dice: nothing before it can be undone any more.
        val revealed = s.source == DiceSource.RANDOM && next.draft.rollsDone > s.draft.rollsDone
        if (revealed) {
            undoStack.clear()
        } else {
            undoStack.addLast(s)
            if (undoStack.size > 200) undoStack.removeFirst()
        }
        canUndo = undoStack.isNotEmpty()
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
            Side.OPP -> {
                ensureOutlook(s)
                if (s.source == DiceSource.RANDOM) runAi(s)
            }
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
        // The table also tracks P(tie), which about doubles its work.
        val budget = (opsPerSecond * DUEL_SECONDS / 2).toLong()
        if (DuelSearch.estimateOps(t, s.me, s.opp, meToMove, limit = budget) == Long.MAX_VALUE) return
        stopDuel()
        val stop = AtomicBoolean(false)
        duelStop = stop
        duelRoot = s.me to s.opp
        duelBuilding = true
        duelJob = scope.launch {
            try {
                val table = withContext(Dispatchers.Default) {
                    adv.buildDuelTable(s.me, s.opp, meToMove, background, stop, ties = true)
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
        if (!s.hintsShown) return
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
                        var decided = t0
                        val table = duelTable
                        val p = adv.planTurnAnytime(s.me, s.opp, deadline, table = table, allowDuel = !duelBuilding, ties = true) { p ->
                            // The draw-chance pass at the end repeats the last search: not part of the speed sample.
                            if (!p.hasTies) decided = System.nanoTime()
                            scope.launch { publish(key, p) }
                        }
                        if (!p.duel || table == null) calibrate(s.me, s.opp, p.depth, decided - t0)
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
        var st = state ?: return
        // My P(win) right after the opponent's move (for its comment and the game summary).
        if (p.mode == Mode.WIN && p.me == st.me) {
            st = Game.noteWinAfterOpponent(st, p.startValue)
            state = st
        }
        if (st.draft.deciding && (autoHeld || (first && st.draft.held.none { it }))) autoHold()
    }

    private fun runAi(s: GameState) {
        val adv = advisor ?: return
        aiJob?.cancel()
        aiJob = scope.launch {
            aiThinking = true
            val started = System.currentTimeMillis()
            try {
                delay(350)
                val move = withContext(Dispatchers.Default) { aiTurn(adv, s) }
                // Leave time to take back a mis-tapped box before the AI's dice are shown.
                if (canUndo) delay(UNDO_GRACE_MS - (System.currentTimeMillis() - started))
                if (state == s) {
                    lastNote = null
                    undoStack.clear()
                    canUndo = false
                    state = Game.opponentScore(s, move.category, move.points, aiLog(s, move), winBeforeOpponent(s), move)
                    onStateChanged()
                }
            } finally {
                aiThinking = false
            }
        }
    }

    /** The AI plays its turn: every throw, the dice it held, its box, and why (from its own plan). */
    private fun aiTurn(adv: Advisor, s: GameState): MoveRecord {
        val deadline = System.nanoTime() + (thinkTime.seconds * 1e9).toLong()
        val turn = adv.planTurnAnytime(s.opp, s.me, deadline)
        val throws = ArrayList<List<Int>>()
        val held = ArrayList<List<Int>>()
        var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
        throws += Dice.rollFaces(r)
        for (rollsDone in 1..2) {
            val k = turn.bestKeep(rollsDone, r)
            if (k == Dice.rollAsKeep[r]) break
            held += Dice.keepFaces(k)
            r = Match.throwDice(rng, k)
            throws += Dice.rollFaces(r)
        }
        val c = turn.bestCategory(r)
        val points = adv.tables.scores[r, c.ordinal]
        val why = explainer?.let { e -> runCatching { e.aiTurn(turn, s.me, throws, held, c) }.getOrNull() }
        return MoveRecord(Side.OPP, c, points, throws = throws, held = held, why = why)
    }

    private fun aiLog(s: GameState, m: MoveRecord): String =
        "턴 ${s.opp.filledCount + 1}: " + m.throws.orEmpty().joinToString(" → ") { it.joinToString("") } +
            " ⇒ ${Game.scoreText(m.category, m.points)}"

    /**
     * Before the opponent's turn (WIN mode): my P(win) now and after each result it may get.
     * Computed even when playing alone, for the review.
     */
    private fun ensureOutlook(s: GameState) {
        val key = s.me to s.opp
        if (outlookKey == key) return
        outlookKey = key
        oppOutlook = null
        outlookJob?.cancel()
        val adv = advisor ?: return
        val t = tables ?: return
        if (objective != Mode.WIN || s.opp.isComplete || t.dist == null) return
        val lastMine = s.moves.lastOrNull()?.takeIf { it.side == Side.ME }?.winAfter
        val table = duelTable
        outlookJob = scope.launch {
            val o = withContext(Dispatchers.Default) {
                runCatching {
                    val analysis = OutlookAnalysis(t)
                    val now = table?.valueAt(s.me, s.opp)?.first ?: lastMine ?: when {
                        s.me.isComplete -> analysis.quick(s.me, s.opp)
                        else -> adv.planTurn(s.me, s.opp, Mode.WIN, depth = 1).startValue
                    }
                    analysis.opponentTurn(s.me, s.opp, now, table?.let { tb -> { o: Board -> tb.valueAt(s.me, o)?.first } })
                }.getOrNull()
            }
            if (outlookKey == key) oppOutlook = o
        }
    }

    // ---- practice quiz -------------------------------------------------------------------------

    var quiz by mutableStateOf<QuizQuestion?>(null)
        private set
    var quizHeld by mutableStateOf(List(5) { false })
        private set
    var quizResult by mutableStateOf<QuizResult?>(null)
        private set
    var quizLoading by mutableStateOf(false)
        private set
    var quizStats by mutableStateOf(QuizStats())
        private set

    /** Replaying my own past mistakes (with how many are left), or null for generated questions. */
    var quizReplayLeft by mutableStateOf<Int?>(null)
        private set

    private var quizPlan: PlannedTurn? = null
    private var quizJob: Job? = null
    private var prefetch: Deferred<Pair<QuizQuestion, PlannedTurn>?>? = null
    private var replays = ArrayDeque<QuizQuestion>()

    /** Alternates questions about holding dice and about choosing a box. */
    private var quizCount = 0

    private fun generateQuiz(engine: Quiz): Pair<QuizQuestion, PlannedTurn>? =
        engine.generate(Random(), wantKeep = quizCount++ % 2 == 0)

    /** Opens the quiz: generated questions, or a replay of [mistakes] from my games. */
    fun openQuiz(mistakes: List<DecisionRecord>? = null) {
        replays = ArrayDeque(mistakes?.let { Quiz.replays(it) } ?: emptyList())
        quizReplayLeft = if (mistakes != null) replays.size else null
        screen = Screen.QUIZ
        nextQuiz()
    }

    fun closeQuiz() {
        quizJob?.cancel()
        prefetch?.cancel()
        prefetch = null
        screen = Screen.HOME
    }

    fun nextQuiz() {
        val engine = quizEngine ?: return
        quizJob?.cancel()
        quizResult = null
        quiz = null
        quizLoading = true
        val replay = quizReplayLeft != null
        quizJob = scope.launch {
            val next = if (replay) {
                val q = replays.removeFirstOrNull()
                quizReplayLeft = replays.size
                q?.let { withContext(Dispatchers.Default) { it to engine.plan(it) } }
            } else {
                val ready = prefetch
                prefetch = null
                ready?.await() ?: withContext(Dispatchers.Default) { generateQuiz(engine) }
            }
            quizLoading = false
            if (next == null) return@launch
            quizPlan = next.second
            quizHeld = List(5) { false }
            quiz = next.first
            // Prepare the next generated question while this one is being answered.
            if (!replay && foreground) prefetch = scope.async(Dispatchers.Default) { generateQuiz(engine) }
        }
    }

    fun quizToggle(slot: Int) {
        val q = quiz ?: return
        if (quizResult != null || q.rollsDone >= 3) return
        quizHeld = quizHeld.toMutableList().also { it[slot] = !it[slot] }
    }

    /** Answer: hold the selected dice and throw the rest. */
    fun quizKeep() {
        val engine = quizEngine ?: return
        val q = quiz ?: return
        val p = quizPlan ?: return
        if (quizResult != null || q.rollsDone >= 3) return
        val held = q.dice.indices.filter { quizHeld[it] }.map { q.dice[it] }
        if (held.size == 5) return
        grade(engine.gradeKeep(p, q, held))
    }

    /** Answer: write the dice into [c] now. */
    fun quizScore(c: Category) {
        val engine = quizEngine ?: return
        val q = quiz ?: return
        val p = quizPlan ?: return
        if (quizResult != null || q.me.isFilled(c)) return
        grade(engine.gradeCategory(p, q, c))
    }

    private fun grade(r: QuizResult) {
        quizResult = r
        quizStats = quizStats.copy(
            answered = quizStats.answered + 1,
            correct = quizStats.correct + if (r.correct) 1 else 0,
            lossSum = quizStats.lossSum + r.loss,
        )
        saveSettings()
    }

    fun resetQuizStats() {
        quizStats = QuizStats()
        saveSettings()
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

        /** Shortest time between my box and the AI's turn being shown, so a mis-tap can be undone. */
        const val UNDO_GRACE_MS = 2_000L

        /** Search budget for replaying a decision in the review (under a second on a phone). */
        const val REVIEW_BUDGET = 100_000_000L

        private const val TAG_SEP = "\u001f"
        private const val MAX_TAGS = 8

        /**
         * Longest background computation allowed for the exact duel table (once per game). Kept
         * short: its win-rate gain over the per-turn exact search was not measurable (-0.20%p ±0.44), while
         * later turns then need no search at all (battery).
         */
        const val DUEL_SECONDS = 10.0
    }
}

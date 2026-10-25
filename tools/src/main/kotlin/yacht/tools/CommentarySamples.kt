package yacht.tools

import yacht.engine.Advisor
import yacht.engine.Dice
import yacht.engine.Match
import yacht.engine.Mode
import yacht.engine.explain.Comment
import yacht.engine.explain.Commentary
import yacht.engine.explain.Explainer
import yacht.engine.explain.OutlookAnalysis
import yacht.engine.game.DiceSource
import yacht.engine.game.Game
import yacht.engine.game.GameState
import yacht.engine.game.MoveRecord
import yacht.engine.game.Side
import java.io.File
import java.util.Random
import java.util.concurrent.Executors

/** Prints the live commentary of whole games against the AI, as the game screen would show it. */
fun commentarySamples(opts: Map<String, String>) {
    val tables = loadTables(File(opts["tables"] ?: "tables")).withoutRisk()
    val games = opts["games"]?.toInt() ?: 1
    val rng = Random(opts["seed"]?.toLong() ?: 5L)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val advisor = Advisor(tables, pool)
    val ex = Explainer(tables)
    val outlook = OutlookAnalysis(tables)
    val talk = Commentary(tables)
    fun show(prefix: String, lines: List<Comment>) = lines.forEach { println("$prefix${it.tag} [${it.tone}] ${it.text}") }
    try {
        repeat(games) { g ->
            println("==================== game ${g + 1} ====================")
            var s = GameState.new(DiceSource.RANDOM, if (g % 2 == 0) Side.ME else Side.OPP, tables.rules)
            var lastWin: Double? = null
            while (!s.isOver) {
                if (s.toMove == Side.OPP) {
                    val now = lastWin ?: if (s.me.isComplete) outlook.quick(s.me, s.opp) else advisor.planTurn(s.me, s.opp, Mode.WIN, depth = 1).startValue
                    val after = outlook.winAfter(s.me, s.opp, now)
                    val turn = advisor.planTurn(s.opp, s.me, Mode.WIN)
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
                    val reasons = throws.mapIndexed { k, d -> runCatching { ex.advice(turn, s.me, turn.advise(k + 1, d)).summary }.getOrNull() }
                    println("  --- AI turn (내 승률 ${"%.1f".format(100 * now)}%)")
                    for (st in talk.aiSteps(turn, s.opp, throws, held, c, after?.let { a -> { b -> a(b) } }, now, reasons)) {
                        val act = st.held?.let { Game.keepText(it) } ?: Game.scoreText(st.category!!, st.points)
                        println("    ${st.throwNo}: ${st.dice.joinToString(" ")} → $act" + (st.myWin?.let { "  (내 승률 ${"%.1f".format(100 * it)}%)" } ?: ""))
                        show("        ", st.lines)
                    }
                    val move = MoveRecord(Side.OPP, c, tables.scores[r, c.ordinal], throws = throws, held = held)
                    s = Game.opponentScore(s, c, tables.scores[r, c.ordinal], winBefore = now, turn = move)
                    show("    [결과] ", talk.opponentResult(s.opp, s.moves.last(), "AI"))
                    continue
                }
                val turn = advisor.planTurn(s.me, s.opp, Mode.WIN)
                s = Game.noteWinAfterOpponent(s, turn.startValue)
                println("  --- my turn ${s.myTurnNumber}: ${s.me.total}:${s.opp.total} 승률 ${"%.1f".format(100 * turn.startValue)}%")
                show("    [미리보기] ", talk.turnPreview(turn, s.opp, s.moves.mapNotNull { it.winAfter }))
                s = Game.firstThrow(s, rng)
                var heldBefore = emptyList<Int>()
                while (true) {
                    val a = turn.advise(s.draft.rollsDone, s.draft.faces)
                    println("    ${s.draft.rollsDone}: ${s.draft.faces.sorted().joinToString(" ")}")
                    show("        ", talk.afterThrow(turn, s.draft.rollsDone, s.draft.faces, heldBefore))
                    if (a.shouldStop) {
                        lastWin = a.bestCategory.value
                        s = Game.score(s, a.bestCategory.category, a)
                        println("       → ${Game.scoreText(a.bestCategory.category, a.bestCategory.points)}")
                        break
                    }
                    heldBefore = a.bestKeep!!.held
                    println("       → ${Game.keepText(heldBefore)}")
                    s = Game.holdFaces(s, heldBefore)
                    s = Game.reroll(s, a, rng)
                }
            }
            println("=== result ${s.me.total}:${s.opp.total}")
        }
    } finally {
        pool.shutdown()
    }
}

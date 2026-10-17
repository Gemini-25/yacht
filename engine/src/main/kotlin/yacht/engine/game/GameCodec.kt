package yacht.engine.game

import yacht.engine.Board
import yacht.engine.Category
import yacht.engine.Mode
import yacht.engine.RuleSet

/** Line-based text format for saving games. Unknown keys are ignored. */
object GameCodec {
    private const val VERSION = "yacht-game 1"

    fun encode(s: GameState): String = buildString {
        appendLine(VERSION)
        appendLine("source=${s.source}")
        appendLine("first=${s.first}")
        appendLine("toMove=${s.toMove}")
        appendLine("created=${s.createdAt}")
        appendLine("me=${board(s.me)}")
        appendLine("opp=${board(s.opp)}")
        appendLine("rolls=${s.draft.rollsDone}")
        appendLine("dice=${s.draft.dice.joinToString(",") { it?.toString() ?: "-" }}")
        appendLine("held=${s.draft.held.joinToString(",") { if (it) "1" else "0" }}")
        appendLine("entry=${s.draft.entryOrder.joinToString(",")}")
        for (d in s.decisions) {
            appendLine(
                "decision=" + listOf(
                    d.turn, d.rollsDone, d.dice.joinToString(","), d.kind, clean(d.chosen), clean(d.best), d.mode,
                    d.chosenValue, d.bestValue, d.chosenExpected, d.bestExpected, clean(d.note.orEmpty()),
                ).joinToString("|"),
            )
        }
        for (l in s.oppLog) appendLine("opplog=${clean(l)}")
        for (m in s.moves) appendLine("move=${m.side}|${m.category}|${m.points}|${m.winAfter ?: "-"}")
    }

    fun decode(text: String, rules: RuleSet = RuleSet.NINTENDO): GameState {
        val lines = text.lines()
        require(lines.firstOrNull() == VERSION) { "unknown save format" }
        val kv = HashMap<String, String>()
        val decisions = ArrayList<DecisionRecord>()
        val oppLog = ArrayList<String>()
        val moves = ArrayList<MoveRecord>()
        for (line in lines.drop(1)) {
            val i = line.indexOf('=')
            if (i < 0) continue
            val k = line.substring(0, i)
            val v = line.substring(i + 1)
            when (k) {
                "decision" -> decisions += decision(v)
                "opplog" -> oppLog += v
                "move" -> v.split('|').let { f ->
                    moves += MoveRecord(Side.valueOf(f[0]), Category.valueOf(f[1]), f[2].toInt(), f[3].toDoubleOrNull())
                }
                else -> kv[k] = v
            }
        }
        fun ints(v: String?) = v.orEmpty().split(',').filter { it.isNotEmpty() }
        val draft = TurnDraft(
            rollsDone = kv["rolls"]?.toInt() ?: 0,
            dice = ints(kv["dice"]).map { it.toIntOrNull() }.ifEmpty { List(5) { null } },
            held = ints(kv["held"]).map { it == "1" }.ifEmpty { List(5) { false } },
            entryOrder = ints(kv["entry"]).map { it.toInt() },
        )
        return GameState(
            source = DiceSource.valueOf(kv.getValue("source")),
            first = Side.valueOf(kv.getValue("first")),
            me = parseBoard(rules, kv.getValue("me")),
            opp = parseBoard(rules, kv.getValue("opp")),
            toMove = Side.valueOf(kv.getValue("toMove")),
            draft = draft,
            decisions = decisions,
            oppLog = oppLog,
            moves = moves,
            createdAt = kv["created"]?.toLong() ?: 0L,
        )
    }

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')

    private fun board(b: Board) = b.toList().joinToString(",") { it?.toString() ?: "-" }

    private fun parseBoard(rules: RuleSet, v: String): Board {
        val cells = v.split(',').map { it.toIntOrNull() }
        require(cells.size == Category.COUNT)
        return Board.of(rules, cells)
    }

    private fun decision(v: String): DecisionRecord {
        val f = v.split('|')
        return DecisionRecord(
            turn = f[0].toInt(),
            rollsDone = f[1].toInt(),
            dice = f[2].split(',').map { it.toInt() },
            kind = DecisionKind.valueOf(f[3]),
            chosen = f[4],
            best = f[5],
            mode = Mode.valueOf(f[6]),
            chosenValue = f[7].toDouble(),
            bestValue = f[8].toDouble(),
            chosenExpected = f[9].toDouble(),
            bestExpected = f[10].toDouble(),
            note = f.getOrNull(11)?.takeIf { it.isNotEmpty() },
        )
    }
}

package yacht.engine

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the exact end-game search against playing its own policy many times. */
class WinSearchTest {
    private val tables get() = SolverTest.tables

    private fun board(vararg filled: Pair<Category, Int>): Board =
        filled.fold(Board()) { b, (c, p) -> b.with(c, p) }

    /** Plays the remaining turns re-planning each turn (as the app does); returns P(win). */
    private fun simulate(me: Board, opp: Board, games: Int, seed: Long): Double {
        val advisor = Advisor(tables)
        val rng = java.util.Random(seed)
        val plans = HashMap<Board, PlannedTurn>()
        var wins = 0
        repeat(games) {
            var b = me
            while (!b.isComplete) {
                val turn = plans.getOrPut(b) { advisor.planTurn(b, opp, depth = Category.COUNT) }
                var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
                for (rolls in 1..2) {
                    val k = turn.bestKeep(rolls, r)
                    if (k == Dice.rollAsKeep[r]) break
                    r = Match.throwDice(rng, k)
                }
                val c = turn.bestCategory(r)
                b = b.with(c, tables.scores[r, c.ordinal])
            }
            if (b.total > opp.total) wins++
        }
        return wins.toDouble() / games
    }

    private fun check(me: Board, opp: Board) {
        val exact = Advisor(tables).planTurn(me, opp, depth = Category.COUNT).startValue
        val n = 200_000
        val sim = simulate(me, opp, n, seed = 42)
        val sd = sqrt(sim * (1 - sim) / n)
        // startValue includes the tiny expected-score tie-break term (< 4e-4).
        println("exact $exact vs simulated $sim (sd $sd)")
        assertTrue(kotlin.math.abs(exact - sim) < 4 * sd + 4e-4, "exact $exact vs simulated $sim (sd $sd)")
    }

    @Test
    fun bonusRaceWithTwoBoxesLeft() {
        // Upper sum 60 with Aces open: three aces earn the 35 bonus. Yacht also open.
        val me = board(
            Category.DEUCES to 8, Category.THREES to 12, Category.FOURS to 12, Category.FIVES to 10,
            Category.SIXES to 18, Category.CHOICE to 22, Category.FOUR_OF_A_KIND to 0,
            Category.FULL_HOUSE to 24, Category.SMALL_STRAIGHT to 15, Category.LARGE_STRAIGHT to 30,
        )
        val opp = Category.ALL.fold(Board()) { b, c -> b.with(c, if (c == Category.CHOICE) 190 else 0) }
        check(me, opp)
    }

    @Test
    fun threeBoxesLeftAgainstUnfinishedOpponent() {
        val me = board(
            Category.ACES to 3, Category.DEUCES to 6, Category.THREES to 9, Category.FOURS to 12,
            Category.FIVES to 15, Category.SIXES to 18, Category.CHOICE to 23, Category.FOUR_OF_A_KIND to 20,
            Category.SMALL_STRAIGHT to 15,
        )
        // Opponent still has Yacht left (scored with the max-expectation policy, as assumed).
        val opp = Category.ALL.filter { it != Category.YACHT }
            .fold(Board()) { b, c -> b.with(c, if (c == Category.CHOICE) 200 else 0) }
        val exact = Advisor(tables).planTurn(me, opp, depth = Category.COUNT).startValue
        val n = 200_000
        val advisor = Advisor(tables)
        val rng = java.util.Random(9)
        val plans = HashMap<Board, PlannedTurn>()
        val oppPlan = advisor.planTurn(opp, me, Mode.EXPECTED_SCORE)
        var wins = 0
        repeat(n) {
            // Opponent's last turn with the max-expectation policy, independent of my play.
            var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
            for (rolls in 1..2) {
                val k = oppPlan.bestKeep(rolls, r)
                if (k == Dice.rollAsKeep[r]) break
                r = Match.throwDice(rng, k)
            }
            val oppFinal = opp.total + tables.scores[r, Category.YACHT.ordinal]
            var b = me
            while (!b.isComplete) {
                val turn = plans.getOrPut(b) { advisor.planTurn(b, opp, depth = Category.COUNT) }
                var x = Match.throwDice(rng, Dice.EMPTY_KEEP)
                for (rolls in 1..2) {
                    val k = turn.bestKeep(rolls, x)
                    if (k == Dice.rollAsKeep[x]) break
                    x = Match.throwDice(rng, k)
                }
                val c = turn.bestCategory(x)
                b = b.with(c, tables.scores[x, c.ordinal])
            }
            if (b.total > oppFinal) wins++
        }
        val sim = wins.toDouble() / n
        val sd = sqrt(sim * (1 - sim) / n)
        println("exact $exact vs simulated $sim (sd $sd)")
        assertTrue(kotlin.math.abs(exact - sim) < 4 * sd + 4e-4, "exact $exact vs simulated $sim (sd $sd)")
    }
}

class DuelSearchTest {
    private val tables get() = SolverTest.tables

    /** Plays my remaining turns (re-planning with the duel search) against the max-expectation opponent. */
    private fun simulate(me0: Board, opp0: Board, meFirst: Boolean, games: Int, seed: Long): Double {
        val advisor = Advisor(tables)
        val rng = java.util.Random(seed)
        val plans = HashMap<Pair<Board, Board>, PlannedTurn>()
        val oppPlans = HashMap<Board, PlannedTurn>()
        fun play(turn: PlannedTurn, b: Board): Board {
            var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
            for (rolls in 1..2) {
                val k = turn.bestKeep(rolls, r)
                if (k == Dice.rollAsKeep[r]) break
                r = Match.throwDice(rng, k)
            }
            val c = turn.bestCategory(r)
            return b.with(c, tables.scores[r, c.ordinal])
        }
        var wins = 0
        repeat(games) {
            var me = me0
            var opp = opp0
            var myMove = true
            if (!meFirst) check(Category.COUNT - opp.filledCount == Category.COUNT - me.filledCount - 1)
            while (!me.isComplete || !opp.isComplete) {
                if (myMove && !me.isComplete) {
                    val p = plans.getOrPut(me to opp) { advisor.planTurn(me, opp, duel = true) }
                    me = play(p, me)
                } else if (!myMove && !opp.isComplete) {
                    val p = oppPlans.getOrPut(opp) { advisor.planTurn(opp, null, Mode.EXPECTED_SCORE) }
                    opp = play(p, opp)
                }
                myMove = !myMove
            }
            if (me.total > opp.total) wins++
        }
        return wins.toDouble() / games
    }

    private fun boards(): Pair<Board, Board> {
        // Two boxes left each: Yacht and Choice for me, Large Straight and Sixes for the opponent.
        var me = Board()
        for (c in Category.ALL) if (c != Category.YACHT && c != Category.CHOICE) me = me.with(c, if (c.isUpper) 3 * c.face else 0)
        var opp = Board()
        for (c in Category.ALL) if (c != Category.LARGE_STRAIGHT && c != Category.SIXES) opp = opp.with(c, if (c == Category.CHOICE) 100 else 0)
        return me to opp
    }

    @Test
    fun duelMatchesSimulationOfItsOwnPolicy() {
        val (me, opp) = boards()
        val turn = Advisor(tables).planTurn(me, opp, duel = true)
        assertTrue(turn.duel)
        val n = 100_000
        val sim = simulate(me, opp, meFirst = true, n, 3)
        val sd = sqrt(sim * (1 - sim) / n)
        println("duel exact ${turn.startValue} vs simulated $sim (sd $sd)")
        assertTrue(kotlin.math.abs(turn.startValue - sim) < 4 * sd + 1e-3, "duel ${turn.startValue} vs simulated $sim")
    }

    @Test
    fun duelIsAtLeastTheFixedOpponentSearch() {
        val (me, opp) = boards()
        val advisor = Advisor(tables)
        val fixed = advisor.planTurn(me, opp, depth = Category.COUNT).startValue
        val duel = advisor.planTurn(me, opp, duel = true).startValue
        println("fixed-opponent exact $fixed, duel $duel")
        assertTrue(duel >= fixed - 1e-3, "duel $duel < fixed $fixed")
    }
}

class WindowTest {
    private val tables get() = SolverTest.tables

    @Test
    fun decidedScoreShortcutDoesNotChangeValues() {
        val rng = java.util.Random(21)
        repeat(12) { i ->
            val filled = 7 + i % 4
            fun board(): Board {
                var b = Board()
                for (c in Category.ALL.shuffled(rng).take(filled)) b = b.with(c, tables.scores[Match.throwDice(rng, Dice.EMPTY_KEEP), c.ordinal])
                return b
            }
            val me = board()
            val opp = board()
            val curve = WinCurve.against(tables, opp)
            // Same curve but bounds that make every score look undecided: no shortcut.
            val full = WinCurve.Curve(curve.values, -10_000, 10_000)
            for (depth in listOf(1, 2, Category.COUNT)) {
                val fast = TurnPlan(me, WinSearch(tables, me, curve, depth)).startValue
                val slow = TurnPlan(me, WinSearch(tables, me, full, depth)).startValue
                // Same values up to single-precision kernel rounding (and the 1e-6 tie-break term,
                // which decided leaves take from the exact expected score).
                assertTrue(kotlin.math.abs(fast - slow) < 1e-6, "depth $depth: $fast vs $slow")
            }
        }
    }
}

class DuelTableReuseTest {
    private val tables get() = SolverTest.tables

    private fun play(advisor: Advisor, b: Board, opp: Board?, rng: java.util.Random): Board {
        val turn = advisor.planTurn(b, opp, if (opp == null) Mode.EXPECTED_SCORE else Mode.WIN)
        var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
        for (rolls in 1..2) {
            val k = turn.bestKeep(rolls, r)
            if (k == Dice.rollAsKeep[r]) break
            r = Match.throwDice(rng, k)
        }
        val c = turn.bestCategory(r)
        return b.with(c, tables.scores[r, c.ordinal])
    }

    private fun boards(rng: java.util.Random, mine: Int, theirs: Int): Pair<Board, Board> {
        fun board(n: Int): Board {
            var b = Board()
            for (c in Category.ALL.shuffled(rng).take(n)) b = b.with(c, tables.scores[Match.throwDice(rng, Dice.EMPTY_KEEP), c.ordinal])
            return b
        }
        return board(mine) to board(theirs)
    }

    @Test
    fun tableRootedAtMyTurnAnswersLaterTurns() {
        val rng = java.util.Random(5)
        val advisor = Advisor(tables)
        repeat(4) {
            var (me, opp) = boards(rng, 9, 9)
            val table = advisor.buildDuelTable(me, opp, meToMove = true)
            assertEquals(
                advisor.planTurn(me, opp, duel = true).startValue,
                advisor.planTurn(me, opp, table = table).startValue, 1e-12,
            )
            me = play(advisor, me, opp, rng)
            opp = play(advisor, opp, null, rng)
            val reused = advisor.planTurn(me, opp, table = table)
            assertTrue(reused.duel)
            val fresh = advisor.planTurn(me, opp, duel = true)
            assertEquals(fresh.startValue, reused.startValue, 1e-9)
        }
    }

    @Test
    fun tableRootedAtOpponentTurnAnswersMyNextTurn() {
        val rng = java.util.Random(6)
        val advisor = Advisor(tables)
        repeat(4) {
            // I just scored my 10th box; the opponent (9 boxes) is to move.
            var (me, opp) = boards(rng, 10, 9)
            val table = advisor.buildDuelTable(me, opp, meToMove = false)
            opp = play(advisor, opp, null, rng)
            val reused = advisor.planTurn(me, opp, table = table)
            assertTrue(reused.duel)
            val fresh = advisor.planTurn(me, opp, duel = true)
            assertEquals(fresh.startValue, reused.startValue, 1e-9)
            // A position not reachable from the root is refused.
            assertEquals(null, table.after(Board(), opp))
        }
    }
}

class EstimateSpeedTest {
    @Test
    fun duelEstimateIsCheapAtGameStart() {
        val tables = SolverTest.tables
        val t0 = System.nanoTime()
        repeat(100) { assertEquals(Long.MAX_VALUE, DuelSearch.estimateOps(tables, Board(), Board())) }
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(ms < 50, "estimate took $ms ms for 100 calls")
    }
}

class AdversarialDuelTest {
    private val tables get() = SolverTest.tables
    private val rules = RuleSet.NINTENDO

    @Test
    fun lastTurnsMatchTheOpponentsBestReply() {
        // I write my last box (Choice), then the opponent plays its last box (4 of a Kind) to beat me.
        val me = Board.of(rules, listOf(3, 6, 9, 12, 15, 18, null, 20, 25, 15, 30, 0))
        val opp = Board.of(rules, listOf(3, 6, 9, 12, 15, 18, 22, null, 25, 15, 30, 0))
        val adv = DuelSearch(tables, me, opp, true, adversarial = true)
        val ev = DuelSearch(tables, me, opp, true)
        for (points in listOf(10, 18, 22, 25, 30)) {
            val myFinal = me.total + points
            // The opponent's best chance to reach at least my total (a tie is not my win).
            val reply = TurnPlan(opp) { _, pts, bonus, _ -> if (opp.total + pts + bonus >= myFinal) 1.0 else 0.0 }
            val v = adv.value(Category.CHOICE.ordinal, points, 0, me.u)
            assertEquals(1 - reply.startValue, v, 5e-4, "Choice $points")
            assertTrue(v <= ev.value(Category.CHOICE.ordinal, points, 0, me.u) + 1e-6)
        }
    }

    @Test
    fun guaranteedValueNeverExceedsTheMaxExpectationModel() {
        val me = Board.of(rules, listOf(3, 6, null, 12, 15, 18, null, 20, 25, 15, null, 0))
        val opp = Board.of(rules, listOf(3, 6, 9, null, 15, 18, 22, null, 25, 15, 30, null))
        val adv = DuelSearch(tables, me, opp, true, adversarial = true)
        val ev = DuelSearch(tables, me, opp, true)
        val a = TurnPlan(me, adv).startValue
        val e = TurnPlan(me, ev).startValue
        assertTrue(a <= e + 1e-6 && a > 0.0, "adversarial $a vs max-expectation opponent $e")
    }
}

/** P(tie) carried along the searches' own decisions, checked against playing those decisions. */
class TieTest {
    private val tables get() = SolverTest.tables

    private fun play(turn: PlannedTurn, b: Board, rng: java.util.Random): Board {
        var r = Match.throwDice(rng, Dice.EMPTY_KEEP)
        for (rolls in 1..2) {
            val k = turn.bestKeep(rolls, r)
            if (k == Dice.rollAsKeep[r]) break
            r = Match.throwDice(rng, k)
        }
        val c = turn.bestCategory(r)
        return b.with(c, tables.scores[r, c.ordinal])
    }

    private fun card(open: Set<Category>, points: (Category) -> Int): Board =
        Category.ALL.filter { it !in open }.fold(Board()) { b, c -> b.with(c, points(c)) }

    private fun assertClose(exact: Double, sim: Double, n: Int, slack: Double, what: String) {
        val sd = sqrt(sim * (1 - sim) / n)
        println("$what exact $exact vs simulated $sim (sd $sd)")
        assertTrue(kotlin.math.abs(exact - sim) < 4 * sd + slack, "$what exact $exact vs simulated $sim (sd $sd)")
    }

    @Test
    fun tracingTiesDoesNotChangeTheSearch() {
        val me = card(setOf(Category.ACES, Category.CHOICE, Category.YACHT, Category.FULL_HOUSE)) { if (it.isUpper) 3 * it.face else 10 }
        val opp = card(setOf(Category.SIXES, Category.YACHT, Category.SMALL_STRAIGHT)) { if (it.isUpper) 3 * it.face else 12 }
        val advisor = Advisor(tables)
        for (depth in listOf(1, 2, 4)) {
            val plain = advisor.planTurn(me, opp, depth = depth)
            val traced = advisor.planTurn(me, opp, depth = depth, ties = true)
            assertEquals(plain.startValue, traced.startValue, 0.0, "depth $depth")
            val o = traced.startOutlook!!
            assertTrue(o.tie in 0.0..0.2 && o.win in 0.0..1.0, "depth $depth: $o")
            assertEquals(1.0, o.win + o.tie + o.loss, 1e-12)
            assertEquals(plain.startValue, o.win, 4e-4)
            val a = traced.advise(1, listOf(1, 1, 3, 5, 6))
            for (k in a.keeps) assertTrue(k.tie in 0.0..1.0, "keep tie ${k.tie}")
            for (c in a.categories) assertTrue(c.tie in 0.0..1.0, "category tie ${c.tie}")
        }
        val duel = advisor.planTurn(me, opp, duel = true)
        val duelTies = advisor.planTurn(me, opp, duel = true, ties = true)
        assertTrue(duelTies.duel)
        assertEquals(duel.startValue, duelTies.startValue, 0.0)
    }

    @Test
    fun exactSearchTieMatchesPlay() {
        // The opponent is done at 212; I need about a Choice's worth with Choice and Yacht left.
        val me = card(setOf(Category.CHOICE, Category.YACHT)) { if (it.isUpper) 3 * it.face else 23 }
        val opp = Category.ALL.fold(Board()) { b, c -> b.with(c, if (c == Category.CHOICE) 212 else 0) }
        check(me.total + 22 == opp.total) { "me ${me.total}" }
        val advisor = Advisor(tables)
        val turn = advisor.planTurn(me, opp, depth = Category.COUNT, ties = true)
        val o = turn.startOutlook!!
        val rng = java.util.Random(5)
        val plans = HashMap<Board, PlannedTurn>()
        val n = 200_000
        var ties = 0
        var wins = 0
        repeat(n) {
            var b = me
            while (!b.isComplete) b = play(plans.getOrPut(b) { advisor.planTurn(b, opp, depth = Category.COUNT) }, b, rng)
            if (b.total == opp.total) ties++
            if (b.total > opp.total) wins++
        }
        assertTrue(o.tie > 0.02, "tie ${o.tie}")
        assertClose(o.tie, ties.toDouble() / n, n, 1e-3, "tie")
        assertClose(o.win, wins.toDouble() / n, n, 1e-3, "win")
    }

    @Test
    fun duelTieMatchesPlay() {
        // Two boxes left each, two points apart.
        val me = card(setOf(Category.YACHT, Category.CHOICE)) { if (it.isUpper) 3 * it.face else 0 }
        val opp = card(setOf(Category.LARGE_STRAIGHT, Category.SIXES)) { if (it == Category.CHOICE) 100 else 0 }
        val advisor = Advisor(tables)
        val turn = advisor.planTurn(me, opp, duel = true, ties = true)
        assertTrue(turn.duel)
        val o = turn.startOutlook!!
        val rng = java.util.Random(11)
        val plans = HashMap<Pair<Board, Board>, PlannedTurn>()
        val oppPlans = HashMap<Board, PlannedTurn>()
        val n = 100_000
        var ties = 0
        var wins = 0
        repeat(n) {
            var a = me
            var b = opp
            var myMove = true
            while (!a.isComplete || !b.isComplete) {
                if (myMove && !a.isComplete) a = play(plans.getOrPut(a to b) { advisor.planTurn(a, b, duel = true) }, a, rng)
                else if (!myMove && !b.isComplete) b = play(oppPlans.getOrPut(b) { advisor.planTurn(b, null, Mode.EXPECTED_SCORE) }, b, rng)
                myMove = !myMove
            }
            if (a.total == b.total) ties++
            if (a.total > b.total) wins++
        }
        assertTrue(o.tie > 0.003, "tie ${o.tie}")
        assertClose(o.tie, ties.toDouble() / n, n, 1e-3, "duel tie")
        assertClose(o.win, wins.toDouble() / n, n, 1e-3, "duel win")
    }

    @Test
    fun opponentRootedTableGivesOutlookBeforeEachOfMyTurns() {
        val me = card(setOf(Category.YACHT, Category.CHOICE)) { if (it.isUpper) 3 * it.face else 0 }
        val opp = card(setOf(Category.LARGE_STRAIGHT, Category.SIXES, Category.ACES)) { if (it == Category.CHOICE) 100 else 0 }
        val advisor = Advisor(tables)
        val table = advisor.buildDuelTable(me, opp, meToMove = false, ties = true)
        val after = opp.with(Category.ACES, 2)
        val (w, t) = table.valueAt(me, after)!!
        val direct = advisor.planTurn(me, after, duel = true, ties = true).startOutlook!!
        assertEquals(direct.win, w, 1e-4)
        assertEquals(direct.tie, t, 1e-5)
    }
}

package yacht.engine

import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomAis
import yacht.engine.league.CustomBase
import yacht.engine.league.CustomMistake
import yacht.engine.league.LeagueTables
import yacht.engine.league.PlayGene
import yacht.engine.league.PlayStyles
import yacht.engine.league.Temper
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Styles of play: each recipe plays the way its name says, saves and replays the same. */
class PlayStylesTest {
    private val tables get() = SolverTest.tables
    private val extra by lazy { LeagueTables(tables.rules) }

    private fun spec(gene: PlayGene?, mistake: CustomMistake = CustomMistake.NONE, amount: Double = 0.0) =
        CustomAiSpec("t", "t", CustomBase.EV, 0.03, mistake, amount, 0.0, 0.0, 0.0, 0.0, false, 1.0, Temper.NONE, null, gene)

    /** Yacht share, bonus share, throws per turn and average score of [gene] over [games] games against max expectation. */
    private class Sig(val yacht: Double, val bonus: Double, val throws: Double, val avg: Double)

    private fun signature(gene: PlayGene?, games: Int = 240, seed: Long = 11): Sig {
        val ai = CustomAis.build(spec(gene), tables, extra)
        var yachts = 0
        var bonuses = 0
        var throws = 0
        var turns = 0
        var total = 0L
        val rng = Random(seed)
        repeat(games) {
            val inner = ai.make(Random(rng.nextLong()))
            var last: Board? = null
            val counting = object : Player {
                override val name = "p"
                override fun startTurn(me: Board, opp: Board) {
                    inner.startTurn(me, opp)
                    last = me
                    turns++
                    throws++
                }
                override fun chooseKeep(rollsDone: Int, r: Int): Int = inner.chooseKeep(rollsDone, r).also { if (it != Dice.rollAsKeep[r]) throws++ }
                override fun chooseCategory(r: Int) = inner.chooseCategory(r)
            }
            val scores = Match.play(tables.rules, counting, PlanPlayer("ev", shapedPlanner(tables.ev)), rng)
            total += scores[0]
            val b = last!!
            // The last board seen is before the final box: count from the final score instead.
            if ((b[Category.YACHT] ?: 0) > 0) yachts++
            if (b.bonus > 0) bonuses++
        }
        return Sig(yachts.toDouble() / games, bonuses.toDouble() / games, throws.toDouble() / turns, total.toDouble() / games)
    }

    @Test
    fun recipesPlayTheWayTheirNamesSay() {
        val ev = signature(PlayGene("ev"))
        val hunter = signature(PlayGene("hunter", 1.5))
        val purist = signature(PlayGene("purist", 1.5))
        val timid = signature(PlayGene("timid", 1.5))
        val reroller = signature(PlayGene("reroller", 1.5))
        val topdown = signature(PlayGene("topdown"))
        println("ev y${ev.yacht} b${ev.bonus} t${ev.throws} a${ev.avg}")
        println("hunter y${hunter.yacht} purist b${purist.bonus} timid t${timid.throws} reroller t${reroller.throws} topdown a${topdown.avg}")
        assertTrue(hunter.yacht > ev.yacht + 0.04, "${hunter.yacht} vs ${ev.yacht}")
        assertTrue(purist.bonus > ev.bonus + 0.06, "${purist.bonus} vs ${ev.bonus}")
        assertTrue(timid.throws < ev.throws - 0.2, "${timid.throws} vs ${ev.throws}")
        assertTrue(reroller.throws > ev.throws + 0.03, "${reroller.throws} vs ${ev.throws}")
        assertTrue(topdown.avg < ev.avg - 25, "${topdown.avg} vs ${ev.avg}")
        // The plain style plays like the max-expectation plan.
        assertTrue(Math.abs(ev.avg - 188.5) < 12, "${ev.avg}")
    }

    /** Win share of [gene] against plain max expectation over [deals] mirrored deals (both seats on the same dice). */
    private fun winShare(gene: PlayGene, deals: Int): Double {
        val ai = CustomAis.build(spec(gene), tables, extra)
        val ev = yacht.engine.league.LeagueAi("ev", "ev", yacht.engine.league.Family.EV, "") { PlanPlayer("ev", shapedPlanner(tables.ev)) }
        var wins = 0.0
        for (g in 0 until deals) for (seat in 0..1) {
            val deal = yacht.engine.league.GameDeal(g * 7919L + 1, g * 7919L + 2, g * 31L + 3, g * 31L + 4, seat == 0)
            val s = yacht.engine.league.LeagueMatch.playGame(tables.rules, deal, ai, ev).score
            wins += if (s.a > s.b) 1.0 else if (s.a == s.b) 0.5 else 0.0
        }
        return wins / (2 * deals)
    }

    @Test
    fun senseWinsMoreGames() {
        // 승부 감각 picks the box by the chance of winning among near-best ones: it wins more
        // (measured over 1500 deals: 50.0% -> 51.1% for plain play, 48.1% -> 50.5% for a hunter).
        val plain = winShare(PlayGene("ev"), 500)
        val sharp = winShare(PlayGene("ev", sense = 1.0), 500)
        println("sense: $plain -> $sharp")
        assertTrue(sharp > plain + 0.004, "$plain -> $sharp")
    }

    @Test
    fun genesSaveAndReplay() {
        for (g in listOf(
            PlayGene("hunter", 1.32), PlayGene("purist", 0.9, secondary = "timid", overlay = "gambler"),
            PlayGene("chameleon-host", 1.0, overlay = "chameleon", moods = listOf("hunter", "straight", "purist"), future = 0.85),
        )) {
            assertEquals(g, PlayGene.decode(g.encode()))
            val s = spec(g, CustomMistake.FEEL, 0.8).copy(temper = Temper.TILT, temperStrength = 0.7)
            assertEquals(s, CustomAiSpec.decode(s.encode()))
        }
        // A spec without a style keeps its old line.
        assertEquals(12, spec(null).encode().split('|').size)
        // The same seed plays the same game.
        val ai = CustomAis.build(spec(PlayGene("streaky-host", 1.0, overlay = "streaky"), CustomMistake.SLIP, 0.05), tables, extra)
        fun game(seed: Long) = Match.play(tables.rules, ai.make(Random(seed)), PlanPlayer("ev", shapedPlanner(tables.ev)), Random(seed + 1)).toList()
        assertEquals(game(5), game(5))
    }

    @Test
    fun everyRecipeAndOverlayPlaysWholeGames() {
        val rng = Random(2)
        for (r in PlayStyles.RECIPES) {
            for (o in listOf(null) + PlayStyles.OVERLAYS.map { it.key }) {
                val gene = PlayGene(r.key, 1.2, overlay = o, moods = if (o == "chameleon") listOf("hunter", "timid") else emptyList())
                val ai = CustomAis.build(spec(gene, CustomMistake.FEEL, 0.5).copy(temper = Temper.MOODY), tables, extra)
                val s = Match.play(tables.rules, ai.make(Random(rng.nextLong())), PlanPlayer("ev", shapedPlanner(tables.ev)), rng)
                assertTrue(s[0] in 0..400, "${r.key} $o ${s[0]}")
            }
        }
    }
}

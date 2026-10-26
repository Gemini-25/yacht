package yacht.engine

import yacht.engine.league.Abilities
import yacht.engine.league.CustomAiSpec
import yacht.engine.league.CustomAis
import yacht.engine.league.CustomBase
import yacht.engine.league.CustomMistake
import yacht.engine.league.Gender
import yacht.engine.league.Identity
import yacht.engine.league.Lean
import yacht.engine.league.LeagueTables
import yacht.engine.league.PlayGene
import yacht.engine.league.PlayStyles
import yacht.engine.league.Profiles
import yacht.engine.league.Taste
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
        // Genes of v2: tastes, a score reading, a late phase and far sight as tag:value parts.
        val v2 = PlayGene(
            "hunter", 1.21, "timid", "chameleon", listOf("purist", "straight"), 1.07, 0.42, PlayGene.V2, true,
            Taste(fullHouse = -4.0, fourKind = 6.0, choice = 3.0, snob = 1.5, face = 5, faceBias = 4.0, yacht = -16.0, straight = 3.0, bonus = 0.15, zero = 5.0, fill = 3, fillBias = 2.5, scrap = 11, scrapBias = 3.0, cost = -1.5),
            12, Lean.SAFE, Lean.BOLD, 4, Lean.NEAR,
        )
        assertEquals("hunter:1.21/s:timid/o:chameleon/m:purist.straight/f:1.07/c:0.42/v:2/fs:1/fh:-4/fk:6/ch:3/sn:1.5/fv:5x4/y:-16/st:3/b:0.15/z:5/fo:3x2.5/sx:11x3/tc:-1.5/rd:12SB/lt:4N", v2.encode())
        assertEquals(v2, PlayGene.decode(v2.encode()))
        assertEquals(v2.copy(behind = null), PlayGene.decode(v2.copy(behind = null).encode()))
        assertTrue(v2.notes().size >= 15, "${v2.notes()}")
        // A part of a newer version is skipped; the rest of the gene still reads.
        assertEquals(PlayGene("hunter", 1.2, v = PlayGene.V2, taste = Taste(choice = 3.0)), PlayGene.decode("hunter:1.2/v:2/zz:9/ch:3"))
        // The same seed plays the same game.
        val ai = CustomAis.build(spec(PlayGene("streaky-host", 1.0, overlay = "streaky"), CustomMistake.SLIP, 0.05), tables, extra)
        fun game(seed: Long) = Match.play(tables.rules, ai.make(Random(seed)), PlanPlayer("ev", shapedPlanner(tables.ev)), Random(seed + 1)).toList()
        assertEquals(game(5), game(5))
    }

    @Test
    fun everyRecipeAndOverlayPlaysWholeGames() {
        val rng = Random(2)
        val taste = Taste(fullHouse = 5.0, choice = -3.0, snob = 2.0, face = 6, faceBias = 5.0, straight = -3.0, zero = 4.0, fill = 1, fillBias = 2.0, scrap = 0, scrapBias = 4.0, cost = -2.0)
        for (r in PlayStyles.RECIPES) {
            for (o in listOf(null) + PlayStyles.OVERLAYS.map { it.key }) {
                val gene = PlayGene(r.key, 1.2, overlay = o, moods = if (o == "chameleon") listOf("hunter", "timid") else emptyList())
                for (g in listOf(gene, gene.copy(v = PlayGene.V2, far = true, taste = taste, margin = 8, ahead = Lean.FAR, behind = Lean.BOLD, lateFrom = 5, late = Lean.SAFE))) {
                    val ai = CustomAis.build(spec(g, CustomMistake.FEEL, 0.5).copy(temper = Temper.MOODY), tables, extra)
                    val s = Match.play(tables.rules, ai.make(Random(rng.nextLong())), PlanPlayer("ev", shapedPlanner(tables.ev)), rng)
                    assertTrue(s[0] in 0..400, "${g.encode()} ${s[0]}")
                }
            }
        }
    }

    @Test
    fun genesBeforeV2PlayAsTheyDid() {
        // Players saved before 생성 다양화 v2 keep their genes: they read, save and play exactly as
        // before (12 games against max expectation each, the totals measured before v2 was added).
        val before = mapOf(
            "hunter:1.32/f:0.85/c:0.4" to 2128,
            "purist:0.9/s:timid/o:gambler/f:0.8" to 1978,
            "ev:1.0/o:chameleon/m:hunter.straight.purist/f:0.9" to 2128,
            "noscratch:1.2/o:locker/f:0.95/c:0.2" to 2191,
            "hoarder:0.8/f:0.9" to 1964,
            "reroller:1.4/s:hunter/o:streaky" to 2240,
            "bigchoice:1.5/o:panic/f:0.75/c:0.5" to 2171,
        )
        for ((text, total) in before) {
            val g = PlayGene.decode(text)!!
            assertEquals(1, g.v)
            assertEquals(text, g.encode())
            val ai = CustomAis.build(spec(g, CustomMistake.FEEL, 0.5).copy(temper = Temper.TILT), tables, extra)
            val sum = (0 until 12).sumOf { s -> Match.play(tables.rules, ai.make(Random(1000L + s)), PlanPlayer("ev", shapedPlanner(tables.ev)), Random(5000L + s))[0] }
            assertEquals(total, sum, text)
        }
        // Their 예측력 keeps its steps of 0.05; a v2 gene's goes to the hundredth, on the far side for some.
        val id = Identity("KR", Gender.MALE, 22, Abilities(70, 47, 60, 60), 70, Temper.TILT, true)
        assertEquals(Profiles.future(47), Profiles.apply(spec(PlayGene("hunter", 1.1)), id).play!!.future)
        assertEquals(0.85, Profiles.future(47))
        assertEquals(0.86, Profiles.apply(spec(PlayGene("hunter", 1.1, v = PlayGene.V2)), id).play!!.future)
        assertEquals(1.07, Profiles.apply(spec(PlayGene("hunter", 1.1, v = PlayGene.V2, far = true)), id).play!!.future)
        assertEquals(1.0, Profiles.future2(99, true))
        assertEquals(0.7, Profiles.future2(1, false))
        assertEquals(1.15, Profiles.future2(1, true))
    }

    @Test
    fun v2IntensitiesKeepTheStyleAtOne() {
        // The rescaled intensities are the measured style at k = 1 (zero terms aside) and differ at the ends.
        for (r in PlayStyles.RECIPES.filter { it.scales }) {
            val one = r.style(1.0, PlayGene.V2)
            assertEquals(r.style(1.0), one.copy(like = one.like.filterValues { it != 0.0 }), r.key)
            assertTrue(r.style(0.7, PlayGene.V2) != r.style(1.7, PlayGene.V2), r.key)
        }
        assertEquals(PlayStyles.recipe("hunter")!!.style(1.5), PlayGene("hunter", 1.5).style())
        assertTrue(PlayStyles.recipe("hunter")!!.style(1.5).rerollCost == 0.0 && PlayGene("hunter", 1.5, v = PlayGene.V2).style().rerollCost < 0)
    }
}

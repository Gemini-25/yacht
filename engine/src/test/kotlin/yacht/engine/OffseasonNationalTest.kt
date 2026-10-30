package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.Competitions
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.LeagueConfig
import yacht.engine.league.MatchFormat
import yacht.engine.league.Nations
import yacht.engine.league.Offseason
import yacht.engine.league.SeasonPlan
import yacht.engine.league.SeasonStart
import yacht.engine.league.TieRule
import java.security.MessageDigest
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The off-season per league: honours, moves and the playoffs' swaps of a national season, and a
 * legacy season's off-season exactly as before national leagues (the hash was taken from the
 * code before this stage).
 */
class OffseasonNationalTest {
    private fun key(i: Int) = "p%04d".format(i)
    private fun index(k: String) = k.removePrefix("p").toInt()
    private fun rating(k: String) = 2000.0 - index(k)
    private fun nation(k: String) = Nations.ALL[(index(k) * 7) % Nations.ALL.size].code

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The better rated wins 70% of its matches (seeded); now and then a level match. */
    private fun play(f: Fixture, rng: Random): JobResult {
        val aBetter = index(f.a) < index(f.b)
        val aWins = (rng.nextDouble() < 0.7) == aBetter
        val level = f.kind != JobKind.TIE && rng.nextInt(10) == 0
        val n = if (f.kind == JobKind.TIE) 2 * f.legs else 2
        val g = List(n) { if (level) GameScore(200, 200) else if (aWins) GameScore(220, 180) else GameScore(180, 220) }
        val res = TieRule.match(g).let { if (f.kind == JobKind.TIE && it == 0) 1 else it }
        return JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), res, 0)
    }

    private fun drive(plan: SeasonPlan, rng: Random) {
        var guard = 0
        while (!plan.finished) {
            if (plan.league.finished) plan.startPost(Competitions.postFactories())
            val f = plan.next(emptySet(), 0, 1) ?: break
            plan.record(play(f, rng))
            check(++guard < 1_000_000)
        }
        assertTrue(plan.finished)
    }

    private fun context(keys: List<String>, config: LeagueConfig, gone: Set<String> = emptySet(), factories: List<(SeasonStart) -> yacht.engine.league.Competition?> = emptyList()) =
        Offseason.Context(keys, ::rating, { null }, { k -> "도시${index(k) % 9}" }, config, factories, gone = gone, nation = ::nation)

    /** What the next season's competitions are told, written out. */
    private fun describe(s: SeasonStart): String = buildString {
        append("start ${s.number} ${s.seed} ${s.ranking.joinToString(",")}\n")
        for (d in s.divisions) append("d ${d.joinToString(",")}\n")
        for (t in s.lastTables) append("t ${t.joinToString(",")}\n")
        append("h ${s.lastHonours.joinToString(",") { it.encode() }}\n")
        append("${s.tiers.encode()} | ${s.lastTiers.encode()}\n")
    }

    /** A closed season's moves, honours and playoffs, and the next season and what its competitions were told. */
    private fun close(plan: SeasonPlan, next: List<String>, gone: Set<String>): Pair<String, SeasonPlan> {
        val starts = StringBuilder()
        val ctx = context(next, plan.config, gone, listOf({ s -> starts.append(describe(s)); null }))
        val moves = Offseason.moves(plan, gone)
        val honours = Offseason.honours(plan, moves, gone)
        val (after, told) = Offseason.next(plan, ctx)
        val text = buildString {
            append(moves.joinToString(",") { "${it.key}:${it.from}>${it.to}" }).append('\n')
            append(honours.joinToString(",") { it.encode() }).append('\n')
            append(told.joinToString(",") { it.encode() }).append('\n')
            for (c in plan.post) StringBuilder().also { c.encode(it) }.let { append(it) }
            append(after.league.encode())
            append(starts)
        }
        return text to after
    }

    @Test
    fun aLegacySeasonsOffseasonIsAsBefore() {
        val out = StringBuilder()
        // A pyramid of 1000 with every playoff: ten of 1부 retire, forty newcomers, two deleted.
        val big = Offseason.first(42L, context((1..1000).map(::key), LeagueConfig()))
        drive(big, Random(7))
        val leaving = big.league.divisions[0].take(10).toSet()
        val (a, _) = close(big, (1..1000).map(::key).filter { it !in leaving } + (1001..1040).map(::key), setOf(key(3), key(400)))
        out.append(a)
        // A linear league that grows into a pyramid, then shrinks back to divisions.
        val small = Offseason.first(1L, context((1..203).map(::key), LeagueConfig()))
        drive(small, Random(3))
        val (b, grown) = close(small, (1..600).map(::key), emptySet())
        out.append(b)
        drive(grown, Random(4))
        val (c, _) = close(grown, (1..250).map(::key), emptySet())
        out.append(c)
        // Two divisions under the rules before series and double elimination.
        val rules = LeagueConfig(divisions = 2, matchFormat = MatchFormat.MIRRORED, seriesKnockouts = false, doubleElimination = false)
        val two = Offseason.first(3L, context((1..24).map(::key), rules))
        drive(two, Random(5))
        val (d, _) = close(two, (1..24).map(::key), emptySet())
        out.append(d)
        assertEquals(LEGACY, sha(out.toString()))
    }

    private companion object {
        const val LEGACY = "a72d759edb7b6d96b456c731a8347e48db69219be076c315044a85c7607f192b"
    }
}

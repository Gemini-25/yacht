package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.HonourKind
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueTitlePlayoffs
import yacht.engine.league.LeagueWorld
import yacht.engine.league.PromotionPlayoff
import yacht.engine.league.Season
import yacht.engine.league.TieRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * National seasons run through today's [LeagueWorld] (before it knows about them): the default
 * rules scaled down to 300 players play two national seasons and their rollovers, and every
 * file reads back as it was written at every step.
 */
class NationalSmokeTest {
    private val config = LeagueConfig.DEFAULT.copy(generated = 300, leagueMin = 20, divisionSize = 16)

    /** Made-up strength: the key's hash (no dice). */
    private fun strength(k: String): Int = k.hashCode() and 63

    private fun games(a: String, b: String, n: Int): List<GameScore> =
        List(n) { if (strength(a) >= strength(b)) GameScore(230, 190, aYacht = it == 0) else GameScore(190, 230) }

    private fun play(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f.a, f.b, 2).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f.a, f.b, 2 * f.legs).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games(x, y, 2), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    private fun members(w: LeagueWorld) = w.players.alive.map { it.key } + LeagueRoster.ADAPTIVE

    private fun world(): LeagueWorld {
        val w = LeagueWorld(Ladder(listOf(Ladder.ME)), config)
        w.players.ensureGenerated(config.generated)
        w.members = members(w)
        w.ladder = Ladder(w.members + Ladder.ME)
        w.family = { Family.EV }
        return w
    }

    /** Plays until [stop] or the end of the season. */
    private fun play(w: LeagueWorld, stop: (LeagueWorld) -> Boolean = { false }) {
        var guard = 0
        while (!w.seasonOver && !stop(w)) {
            val f = w.next(emptySet(), 0, 2) ?: error("season ${w.plan?.number} stuck at ${w.plan?.fixturesLeft} fixtures left")
            w.recordJob(play(f))
            check(++guard < 200_000)
        }
    }

    /** The app's rollover: close, rebuild the members from the players book, open. */
    private fun rollover(w: LeagueWorld): LeagueWorld.Closing {
        w.ladder.fit()
        val c = w.closeSeason()
        if (c.rosterChanged) {
            w.members = members(w)
            w.ladder = Ladder.decode(w.members + Ladder.ME, w.ladder.encode())
        }
        readsBack(w, "season ${w.plan!!.number} closed")
        w.openSeason()
        return c
    }

    /**
     * Every file of [w] reads back and is written again the same; the ladder refits its ratings
     * when read (as it always has), so its fitted fields (peak and strength) may move.
     */
    private fun readsBack(w: LeagueWorld, step: String) {
        val files = w.encodeFiles()
        val back = LeagueWorld.decode(files, w.ladder.keys, emptySet())
        val again = back.encodeFiles()
        assertEquals(files.keys, again.keys)
        fun unfitted(ladder: String) = ladder.lines().map { l -> if (l.startsWith("p ")) l.split(' ').let { it.take(13) + it[14] }.joinToString(" ") else l }
        for ((name, text) in files) {
            if (name == LeagueWorld.LADDER) assertEquals(unfitted(text), unfitted(again.getValue(name)), "$step: $name")
            else assertEquals(text, again[name], "$step: $name")
        }
        assertTrue(back.plan?.league?.leagues?.national != false, step)
    }

    @Test
    fun twoNationalSeasonsAndTheirRolloversRunThroughTheWorld() {
        val w = world()
        w.openFirstSeason(7L)
        repeat(2) { i ->
            val p = w.plan!!
            assertEquals(i + 1, p.number)
            val leagues = p.league.leagues
            assertTrue(leagues.national && leagues.list.size >= 2, leagues.toString())
            assertTrue(p.league.encode().startsWith(Season.NATIONAL_HEADER + "\n"))
            readsBack(w, "season ${p.number} opened")
            play(w) { it.plan!!.league.progress >= 0.5 }
            readsBack(w, "season ${p.number} halfway")
            play(w) { it.plan!!.post.any { c -> c.fixturesLeft in 1 until c.fixturesTotal } }
            readsBack(w, "season ${p.number} post-season")
            play(w)
            assertIs<LeagueTitlePlayoffs>(p.competition(Comp.TITLE_PLAYOFF))
            assertIs<PromotionPlayoff>(p.competition(Comp.PROMOTION_PLAYOFF))
            readsBack(w, "season ${p.number} over")
            val closing = rollover(w)
            // A title, a regular-season leader and a playoff runner-up per league; no moves as honours.
            for (lg in leagues.list) for (base in listOf("L0", "REG", "L0R")) {
                assertEquals(1, closing.honours.count { it.kind == HonourKind.of(base, lg.id) }, "${lg.id} $base")
            }
            assertTrue(closing.honours.none { it.kind.startsWith("UP") || it.kind.startsWith("DOWN") })
            assertTrue(w.honours.of(p.number).any { it.kind == "L1.${leagues.list.first().id}" })
            readsBack(w, "season ${p.number + 1} opened")
            assertTrue(w.plan!!.league.leagues.national)
            assertEquals(members(w).sorted(), w.plan!!.league.divisions.flatten().sorted())
        }
        assertEquals(3, w.plan!!.number)
        assertTrue(w.news.all.none { it.headline.contains('{') || it.body.contains('{') })
    }
}

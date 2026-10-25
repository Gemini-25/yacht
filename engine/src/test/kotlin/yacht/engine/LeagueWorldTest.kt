package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.Comp
import yacht.engine.league.Family
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueWorld
import yacht.engine.league.TieRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whole seasons through [LeagueWorld] with made-up results (no dice): competitions, rollover, evolution, saving. */
class LeagueWorldTest {
    private val base = listOf("ev") + (1..39).map { "b%02d".format(it) }
    private val families = Family.entries.filter { it != Family.MINE }

    /** Lower index is stronger; generated players sit in the middle. */
    private fun strength(k: String): Int = when {
        k == "ev" -> 100
        k.startsWith("b") -> 100 - k.drop(1).toInt()
        else -> 70 - (k.hashCode() and 15)
    }

    private fun games(a: String, b: String, n: Int): List<GameScore> =
        List(n) { if (strength(a) >= strength(b)) GameScore(230, 190, aYacht = true) else GameScore(190, 230) }

    private fun play(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f.a, f.b, 2).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f.a, f.b, 2 * f.legs).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).map { (x, y) -> BoardResult(x, y, games(x, y, 2), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    private fun world(config: LeagueConfig): LeagueWorld {
        val w = LeagueWorld(Ladder(base + Ladder.ME), config)
        w.players.ensureGenerated(config.generated)
        w.members = base + w.players.alive.map { it.key }
        w.ladder = Ladder(w.members + Ladder.ME)
        w.family = { k -> if (k.startsWith("b")) families[k.drop(1).toInt() % families.size] else Family.EV }
        return w
    }

    /** Plays until the season is over; returns the news. */
    private fun season(w: LeagueWorld): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        var guard = 0
        while (!w.seasonOver) {
            val f = w.next(emptySet(), 0, 2) ?: if (w.seasonOver) break else error("season ${w.plan?.number} stuck at ${w.plan?.fixturesLeft} fixtures left")
            out += w.recordJob(play(f)).events
            check(++guard < 100_000)
        }
        return out
    }

    /** The app's rollover: close, rebuild the members from the players book, open. */
    private fun rollover(w: LeagueWorld): LeagueWorld.Closing {
        w.ladder.fit()
        val c = w.closeSeason()
        if (c.rosterChanged) {
            w.members = base + w.players.alive.map { it.key }
            w.ladder = Ladder.decode(w.members + Ladder.ME, w.ladder.encode())
        }
        w.openSeason()
        return c
    }

    @Test
    fun seasonsRollOverWithCompetitionsEvolutionAndSaves() {
        val config = LeagueConfig(divisions = 3, generated = 20, evolution = 4, clubs = 8, squad = 5)
        val w = world(config)
        val opening = w.openFirstSeason(9L)
        assertTrue(opening.any { it.kind == "draw_cup" }, opening.map { it.kind }.toString())
        val comps = w.plan!!.comps.map { it.comp }.toSet()
        assertTrue(Comp.CUP in comps && Comp.CHAMPIONS in comps && Comp.ALL_STAR in comps && Comp.CLUB in comps, comps.toString())
        assertEquals(8, w.clubs!!.clubs.size)
        val news = season(w)
        assertTrue(news.any { it.kind == "final" }, news.map { it.kind }.distinct().toString())
        assertTrue(w.plan!!.post.any { it.comp == Comp.TITLE_PLAYOFF })
        val first = rollover(w)
        val kinds = first.honours.map { it.kind }.toSet()
        for (k in listOf("REG", "L0", "L0R", "L1", "L2", "CUP", "CUP2", "CL", "CL2", "CLUB", "CLUB2")) assertTrue(k in kinds, "$k missing from $kinds")
        // The clubs' window ran: retired members left, children may be drafted, every member is alive.
        val clubbed = w.clubs!!.clubs.flatMap { it.squad }
        assertTrue(clubbed.all { it in w.members }, "a club member left the league")
        assertEquals(clubbed.size, clubbed.toSet().size)
        assertTrue(first.events.any { it.kind == "promoted" } && first.events.any { it.kind == "relegated" })
        // Evolution: 4 retired, 4 born, and the children join the next season's lowest division.
        assertEquals(4, first.events.count { it.kind == "born" })
        val born = first.events.filter { it.kind == "born" }.map { it.keys.first() }
        assertTrue(born.all { it in w.plan!!.league.divisions.last() }, "children placed: $born")
        assertTrue(w.members.containsAll(born))
        assertEquals(2, w.plan!!.number)
        assertTrue(w.plan!!.comps.any { it.comp == Comp.SUPER })
        // Saved midway through season 2 and read back: the same fixtures left and the same honours.
        repeat(300) {
            val f = w.next(emptySet(), 0, 2) ?: return@repeat
            w.record(play(f))
        }
        val files = w.encodeFiles()
        val back = LeagueWorld.decode(files, w.members + Ladder.ME, emptyList())
        assertEquals(w.plan!!.fixturesLeft, back.plan!!.fixturesLeft)
        assertEquals(w.plan!!.comps.map { it.comp }, back.plan!!.comps.map { it.comp })
        assertEquals(w.honours.all, back.honours.all)
        assertEquals(w.players.alive.map { it.key }.sorted(), back.players.alive.map { it.key }.sorted())
        assertEquals(w.clubs!!.clubs, back.clubs!!.clubs)
        // Stats: season one's summary is archived (made-up results have no match ratings, so no MVP).
        assertTrue(w.archive.summary(1) != null)
        assertTrue(w.stats.career("ev") != null)
        assertEquals(w.stats.career("ev")!!.games, back.stats.career("ev")!!.games)
        // Two more seasons on the read-back world.
        back.config = config
        back.members = w.members
        back.family = w.family
        season(back)
        rollover(back)
        season(back)
        val third = rollover(back)
        assertEquals(3, third.season)
        // The family cup is due in season 2 and 5; season 3 had none.
        assertTrue(back.honours.of(2).any { it.kind == "FAMILY" }, back.honours.of(2).map { it.kind }.toString())
        assertEquals(4, back.plan!!.number)
    }

    @Test
    fun aSeasonFinishesWithEveryCompetitionOffOrOn() {
        for (config in listOf(
            LeagueConfig(divisions = 1, cup = false, champions = false, superCup = false, allStar = false, familyCup = false, titlePlayoff = false, promotionPlayoff = false, generated = 0, evolution = 0, clubs = 0),
            LeagueConfig(divisions = 6, generated = 30, evolution = 0, clubs = 0),
        )) {
            val w = world(config)
            w.openFirstSeason(3L)
            season(w)
            rollover(w)
            season(w)
            assertTrue(w.seasonOver)
        }
    }
}

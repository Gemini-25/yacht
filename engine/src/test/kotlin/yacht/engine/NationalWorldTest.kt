package yacht.engine

import yacht.engine.league.BoardResult
import yacht.engine.league.ClubBook
import yacht.engine.league.ClubMoveType
import yacht.engine.league.Comp
import yacht.engine.league.Federation
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueEvent
import yacht.engine.league.LeagueRanking
import yacht.engine.league.LeagueRoster
import yacht.engine.league.LeagueWorld
import yacht.engine.league.News
import yacht.engine.league.PlayerBook
import yacht.engine.league.Season
import yacht.engine.league.SeasonCalendar
import yacht.engine.league.TieRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A league run as the app runs it, with made-up results (no dice: the stronger side by the key's
 * hash wins more often, seeded by the fixture): the rollover with the closing rules kept, the
 * 요트협회's adopted changes applied to the rules (as the app does), the members rebuilt from
 * the players book.
 */
internal class WorldRun(var rules: LeagueConfig, players: Int = rules.generated, base: List<String> = emptyList()) {
    /** The AIs that are not generated players (they play as the app would add them). */
    private val base = base
    var w: LeagueWorld = LeagueWorld(Ladder(listOf(Ladder.ME)), rules)

    init {
        w.players.ensureGenerated(players)
        w.members = members()
        w.ladder = Ladder(w.members + Ladder.ME)
    }

    /** The members as the app picks them: the base AIs, the generated players (the best rated with 강자만), the guest. */
    fun members(world: LeagueWorld = w): List<String> =
        base + world.players.alive.map { it.key }.let { keys -> if (rules.onlyTop > 0) keys.sortedByDescending { world.rating(it) }.take(rules.onlyTop) else keys } +
            listOfNotNull(LeagueRoster.ADAPTIVE.takeIf { rules.guests })

    private fun strength(k: String): Int = k.hashCode() and 63

    private fun games(f: Fixture, a: String, b: String, n: Int, salt: Int): List<GameScore> {
        val rng = java.util.Random(f.seed * 31 + salt)
        val p = 1 / (1 + Math.pow(10.0, (strength(b) - strength(a)) / 100.0))
        return List(n) { if (rng.nextDouble() < p) GameScore(230, 190, aYacht = it == 0) else GameScore(190, 230) }
    }

    fun result(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f, f.a, f.b, 2, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f, f.a, f.b, 2 * f.legs, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).mapIndexed { i, (x, y) -> BoardResult(x, y, games(f, x, y, 2, i + 1), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }

    /** Plays until [stop] or the end of the season; returns the news. */
    fun play(stop: (LeagueWorld) -> Boolean = { false }): List<LeagueEvent> {
        val out = ArrayList<LeagueEvent>()
        var guard = 0
        while (!w.seasonOver && !stop(w)) {
            // Asking for the next fixture starts the post-season, which may be empty: then it is over.
            val f = w.next(emptySet(), 0, 2) ?: if (w.seasonOver) break else error("season ${w.plan?.number} stuck at ${w.plan?.fixturesLeft} fixtures left")
            out += w.recordJob(result(f)).events
            check(++guard < 400_000)
        }
        return out
    }

    /** What the app saves before it closes a finished season: the season with its closing rules. */
    fun beforeClose(): Map<String, String> {
        val p = w.plan!!
        p.closing = p.closing ?: rules
        w.config = p.closing!!
        w.ladder.fit()
        return w.encodeFiles()
    }

    /** The close and the open of a rollover (after [beforeClose]); [next] are the rules for the season after, before the 요트협회's changes. */
    fun rollover(next: LeagueConfig = rules): Pair<LeagueWorld.Closing, List<LeagueEvent>> {
        val c = w.closeSeason()
        if (c.rosterChanged) {
            w.members = members()
            w.ladder = Ladder.decode(w.members + Ladder.ME, w.ladder.encode())
        }
        rules = Federation.applyTo(next, c.ruleChanges).first
        w.config = rules
        w.members = members()
        val opened = w.openSeason()
        return c to opened
    }

    /** The league read back from [files], as the app reads it after a kill. */
    fun read(files: Map<String, String>): LeagueWorld {
        val players = PlayerBook.decode(files.getValue(LeagueWorld.ROSTER), emptyList())
        val keys = base + players.alive.map { it.key } + LeagueRoster.ADAPTIVE + Ladder.ME
        val back = LeagueWorld.decode(files, keys, emptyList(), rules)
        back.present = { it in keys }
        return back
    }
}

/**
 * The national world through [LeagueWorld] as the app runs it: a legacy league migrates (clubs
 * converted, national leagues, the 요트협회's first assembly), two national seasons follow with
 * the league ranking, transfers abroad and proposals, and a rollover replayed after a kill at any
 * file of its save gives the same files.
 */
class NationalWorldTest {
    private val legacy = LeagueConfig(generated = 420, clubs = 8, squad = 5, nationCup = false)
    private val national = LeagueConfig.DEFAULT.copy(generated = 420, leagueMin = 20, divisionSize = 16, transfers = 10, nationCup = false)

    /**
     * The files of the national world that come out of a replayed rollover exactly as out of the
     * first: the next season (its leagues, divisions and draws), the clubs, the 요트협회, the
     * honours. (The roster and the archive also carry ratings, which the ladder refits when it is
     * read back, as it always has: a player's peak abilities and season-line rating can move;
     * their players and their leagues and clubs are compared.)
     */
    private val replayed = listOf(LeagueWorld.SEASON, LeagueWorld.CLUBS, LeagueWorld.FEDERATION, LeagueWorld.HONOURS)

    private fun same(want: String, got: String, what: String) {
        val a = want.lines()
        val b = got.lines()
        val diff = (a - b.toSet()).take(4) + listOf("--- instead:") + (b - a.toSet()).take(4)
        assertTrue(a == b, "$what differs: ${diff.joinToString("\n")}")
    }

    /**
     * Killed after k files of the save that follows a rollover (all but the season file, which
     * would make it no replay), for every k: the league read back closes and opens again under the
     * same rules; its national files are those of the first rollover.
     */
    private fun replaysTheSame(
        run: WorldRun, before: Map<String, String>, after: Map<String, String>, rulesBefore: LeagueConfig, next: LeagueConfig, step: String, ranking: LeagueRanking,
    ) {
        val order = after.keys.toList()
        val rulesAfter = run.rules
        val first = run.read(after)
        for (k in 0 until order.size - 1) {
            val mixed = before + order.take(k).associateWith { after.getValue(it) }
            val again = WorldRun(rulesBefore, 0)
            again.w = again.read(mixed)
            again.w.family = run.w.family
            again.w.members = again.members()
            again.beforeClose()
            val (closed, _) = again.rollover(next)
            val at = "$step, k $k (${order.take(k)})"
            assertEquals(rulesAfter, again.rules, "$at: rules")
            // The ranking the close publishes (and the next season's places are drawn by), also
            // when the ladder read back has already aged.
            assertEquals(ranks(ranking), ranks(closed.ranking), "$at: ranking")
            val files = again.w.encodeFiles()
            for (name in replayed) same(after.getValue(name), files.getValue(name), "$at: $name")
            assertEquals(first.players.alive.map { it.key }, again.w.players.alive.map { it.key }, "$at: players")
            for (key in first.archive.lineKeys) {
                assertEquals(first.archive.lines(key).map { listOf(it.season, it.division, it.position, it.league, it.club) }, again.w.archive.lines(key).map { listOf(it.season, it.division, it.position, it.league, it.club) }, "$at: $key's lines")
            }
        }
    }

    private fun ranks(r: LeagueRanking) = r.rows.map { listOf(it.id, it.rank.toString(), it.change.toString(), it.cclSlots.toString()) }

    /** Each league's climb in the ranking after a season is counted from the ranking [played] the season was played under. */
    private fun climbsFrom(played: LeagueRanking, after: LeagueRanking) {
        assertTrue(after.rows.isNotEmpty())
        for (row in after.rows) assertEquals(played.rank(row.id) - row.rank, row.change, "${row.id}: ${ranks(played)} -> ${ranks(after)}")
    }

    @Test
    fun aLegacyLeagueMigratesAndPlaysTwoNationalSeasons() {
        val run = WorldRun(legacy)
        run.w.openFirstSeason(11L)
        assertEquals(ClubBook.CLUB_LEAGUE, run.w.clubs!!.format)
        run.play()
        // The migration: the legacy season closes under its rules, the next opens national.
        val before = run.beforeClose()
        val (close1, open1) = run.rollover(national)
        val after = run.w.encodeFiles()
        assertTrue(close1.ruleChanges.isEmpty() && close1.ranking.rows.isEmpty())
        val p2 = run.w.plan!!
        assertEquals(2, p2.number)
        assertTrue(p2.league.leagues.national && p2.league.encode().startsWith(Season.NATIONAL_HEADER + "\n"))
        val clubs = run.w.clubs!!
        assertEquals(ClubBook.AFFILIATION, clubs.format)
        // Every member in one club of the nation it plays in.
        for (k in run.w.members) {
            val lg = p2.league.leagueOf(k)!!.id
            assertTrue(clubs.clubOf(k) != null, k)
            assertTrue(lg in setOf(clubs.playNation(k), "XAS", "XEU", "XAM", "XAF", "XWO"), "$k plays in $lg, club in ${clubs.playNation(k)}")
        }
        val kinds1 = open1.map { it.kind }
        assertTrue("club_reform" in kinds1 && "national_reform" in kinds1 && "federation_assembly" in kinds1, kinds1.toString())
        // The ranking it is played under (strength order in the first national season), its best-ranked leagues kept.
        assertEquals(p2.league.leagues.list.map { it.id }.sorted(), p2.ranking.rows.map { it.id }.sorted())
        assertEquals(p2.ranking.rows.sortedBy { it.rank }.map { it.id }.take(Federation.TOP), run.w.federation.top(2))
        // The first assembly: structure-free kinds only, voted on in season 2.
        assertTrue(run.w.federation.of(1).all { it.kind.structureFree })
        assertTrue(run.w.federation.assembled == 1 && run.w.federation.resolved == 1)
        replaysTheSame(run, before, after, legacy, national, "migration", close1.ranking)

        // National season 2: the CCL and the world stage, then the first national close.
        run.play()
        val played2 = run.w.plan!!.ranking
        val before2 = run.beforeClose()
        val (close2, open2) = run.rollover(national)
        climbsFrom(played2, close2.ranking)
        val after2 = run.w.encodeFiles()
        assertTrue(close2.ranking.rows.isNotEmpty())
        assertTrue(run.w.federation.coefficientSeasons == listOf(2))
        val kinds2 = close2.events.map { it.kind }
        assertTrue("league_title" in kinds2 && "league_ranking" in kinds2 && "season_review" in kinds2, kinds2.toString())
        assertTrue(kinds2.none { it == "promoted" || it == "relegated" }, kinds2.toString())
        assertTrue(close2.events.filter { it.kind == "league_title" }.size <= LeagueWorld.LEAGUE_TITLES)
        // The proposals of the migration's assembly are decided at this close.
        assertTrue(run.w.federation.of(1).none { it.pending })
        assertEquals(run.w.federation.of(1).size, close2.events.count { it.kind == "federation_decision" })
        // Transfers abroad (and the second assembly, now with the structure kinds).
        val abroad = run.w.clubs!!.moved(2, ClubMoveType.ABROAD)
        assertTrue(abroad.isNotEmpty(), "no transfer abroad")
        for (k in abroad) assertEquals(0, run.w.plan!!.league.tierOf(k), "$k moved abroad plays in the 1부")
        assertTrue(open2.any { it.kind == "transfer_abroad" }, open2.map { it.kind }.toString())
        // The migration's proposals (every decided match a 2:0 here: SWEEP_OFF presses) were adopted and apply from season 3.
        assertTrue(run.w.federation.of(1).isNotEmpty(), "no proposals at the migration")
        for (x in run.w.federation.of(1).filter { it.adopted }) assertEquals(x.to, run.rules.valueOf(x.lever.key), x.toString())
        // Their levers changed: the second assembly does not table them again.
        assertTrue(run.w.federation.of(2).none { x -> run.w.federation.of(1).any { it.adopted && it.lever == x.lever } })
        // Season lines carry the club and the league.
        val lines = run.w.archive.lines(run.w.plan!!.league.divisions[0].first()).filter { it.season == 2 }
        assertTrue(lines.all { it.league.isNotEmpty() && it.club != null }, lines.toString())
        replaysTheSame(run, before2, after2, national, national, "season 2", close2.ranking)

        // National season 3: the club cup is due (an even season after a national one is 4; 3 has none).
        run.play()
        val played3 = run.w.plan!!.ranking
        run.beforeClose()
        val (close3, _) = run.rollover(national)
        climbsFrom(played3, close3.ranking)
        assertTrue(run.w.federation.coefficientSeasons == listOf(2, 3))
        assertTrue(run.w.federation.of(2).none { it.pending })
        assertTrue(close3.events.any { it.kind == "league_ranking" })
        val p4 = run.w.plan!!
        assertEquals(4, p4.number)
        assertTrue(p4.comps.any { it.comp == Comp.CLUB_CUP } || run.rules.clubCup.not(), p4.comps.map { it.comp }.toString())
        // Every article reads as Korean with no slot left.
        assertTrue(run.w.news.all.none { it.headline.contains('{') || it.body.contains('{') || it.headline == News.PLAIN_HEADLINE }, run.w.news.all.filter { it.headline == News.PLAIN_HEADLINE }.map { it.kind }.toString())
        // The close and the open of every rollover are on the season's two edges.
        assertTrue(close3.events.all { it.week == SeasonCalendar.CLOSING })
    }
}

package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import yacht.app.core.LeagueController
import yacht.app.core.rulesAfterClose
import yacht.engine.league.BoardResult
import yacht.engine.league.ClubBook
import yacht.engine.league.Fixture
import yacht.engine.league.GameScore
import yacht.engine.league.JobKind
import yacht.engine.league.JobResult
import yacht.engine.league.Ladder
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeagueWorld
import yacht.engine.league.PlayerBook
import yacht.engine.league.Proposal
import yacht.engine.league.Season
import yacht.engine.league.TieRule
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The flip to national leagues as the app takes it (plan S16, ENG 7.3): a league folder saved by
 * the app before it (a `config 2` and a running `season 4` of the single league with its club
 * league) is read by this one. The rules move on to national leagues once and are saved as
 * version 3, which is never moved on again; the season in play finishes under the rules it began
 * with; the next is national, with clubs for every member, federation.txt and the 요트협회's
 * proposals, and the changes the assembly after it adopts reach the rules the app saves and the
 * season after.
 */
class FlipTest {
    private val dirs = ArrayList<File>()

    @AfterTest
    fun clean() {
        for (d in dirs) d.deleteRecursively()
    }

    /** config.txt as the app wrote it before the flip, for a small league of 260 players with an 8-club league. */
    private val old = """
        config 2
        divisions 4
        double 0
        format GAMES
        autoSwap 2
        promotionPlayoff 1
        titlePlayoff 1
        pyramid 1
        groupSize 50
        groupSwap 3
        generated 260
        aging 1
        childShare 40
        baseAis 0
        guests 0
        nationCup 0
        clubs 8
        squad 5
        cup 1
        champions 1
        superCup 1
        allStar 1
        familyCup 1
        longKnockouts 1
        series 1
        doubleElim 1
        onlyFlawed 0
        onlyTop 0
    """.trimIndent() + "\n"

    @Test
    fun theRulesMoveOnOnceAndAreNotMigratedTwice() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val dir = Files.createTempDirectory("flip-a").toFile().also { dirs += it }
            val file = File(dir, "league/config.txt").apply { parentFile.mkdirs() }
            file.writeText(old)
            val a = LeagueController(scope, dir)
            // National leagues from the next season, the league's own settings kept.
            assertEquals(LeagueConfig.DEFAULT.copy(generated = 260, guests = false, nationCup = false, clubs = 8, squad = 5), a.config)
            // Saved once as version 3 ...
            val saved = file.readText()
            assertTrue(saved.startsWith("config ${LeagueConfig.VERSION}\n") && LeagueConfig.VERSION == 3, saved.lines().first())
            assertEquals(a.config, LeagueConfig.decode(saved))
            // ... and read as it is from then on: not migrated twice, the file untouched.
            val b = LeagueController(scope, dir)
            assertEquals(a.config, b.config)
            assertEquals(saved, file.readText())
            // A version 3 league's own choices (one round robin, no 요트협회) stay as they are.
            val mine = a.config.copy(doubleRoundRobin = false, federation = false)
            file.writeText(mine.encode())
            assertEquals(mine, LeagueController(scope, dir).config)
            assertEquals(mine.encode(), file.readText())
            // No rules file at all: a new league's.
            assertEquals(LeagueConfig.DEFAULT, LeagueController(scope, Files.createTempDirectory("flip-b").toFile().also { dirs += it }).config)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSeasonInPlayFinishesUnderItsRulesAndTheNextIsNational() {
        val legacy = LeagueConfig.decode(old)
        assertFalse(legacy.national)
        // The league before the flip: three seasons and their rollovers under the old rules (as
        // that app ran them, its rules never moved on), and season 4 half played.
        var w = LeagueWorld(Ladder(listOf(Ladder.ME)), legacy)
        w.players.ensureGenerated(legacy.generated)
        w.members = members(w)
        w.ladder = Ladder(w.members + Ladder.ME)
        w.openFirstSeason(20261031L)
        repeat(3) {
            play(w)
            rollover(w, legacy)
        }
        val p4 = assertNotNull(w.plan)
        assertEquals(4, p4.number)
        assertEquals(ClubBook.CLUB_LEAGUE, w.clubs!!.format)
        play(w) { it.plan!!.league.progress >= 0.5 }
        // Its folder as that app saved it: the season's own rules a version 2 copy, no federation.txt.
        val saved = w.encodeFiles().toMutableMap()
        saved.remove(LeagueWorld.FEDERATION)
        saved[LeagueWorld.SEASON] = saved.getValue(LeagueWorld.SEASON).replace("\ncfg config ${LeagueConfig.VERSION}\n", "\ncfg config 2\n")
        assertTrue("\n${Season.HEADER}\n" in saved.getValue(LeagueWorld.SEASON), "a season 4 file")
        assertTrue("\ncfg config 2\n" in saved.getValue(LeagueWorld.SEASON))

        // This app reads it: its rules move on (LeagueController.readConfig), the files are read under them.
        val rules = LeagueConfig.migrated(old)
        assertTrue(rules.national)
        w = read(saved, rules)
        val p = assertNotNull(w.plan)
        assertEquals(4, p.number)
        assertEquals(legacy, p.config, "the season in play keeps the rules it began with")
        assertFalse(p.league.leagues.national)
        assertTrue(w.federation.proposals.isEmpty())

        // It finishes as it began, closes as a legacy season and opens the first national one.
        play(w)
        val (closing, next) = rollover(w, rules)
        assertTrue(closing.honours.any { it.kind == "L0" }, "a legacy title: ${closing.honours.map { it.kind }.distinct()}")
        assertTrue(closing.ruleChanges.isEmpty())
        assertEquals(rules, next, "nothing adopted at a legacy close: the rules saved are the migrated ones")
        val p5 = assertNotNull(w.plan)
        assertEquals(5, p5.number)
        assertTrue(p5.league.leagues.national, p5.league.leagues.toString())
        assertTrue(p5.config.national)
        val book = assertNotNull(w.clubs)
        assertEquals(ClubBook.AFFILIATION, book.format)
        for (k in w.members) assertTrue(book.clubOf(k) != null, "$k has no club")
        val files = w.encodeFiles()
        assertTrue(files.getValue(LeagueWorld.FEDERATION).startsWith(yacht.engine.league.Federation.HEADER))
        assertTrue(files.getValue(LeagueWorld.CLUBS).isNotBlank())
        // The first assembly tabled proposals (only those that need no national season's numbers), to vote on in season 5.
        val tabled = w.federation.pending()
        assertTrue(tabled.isNotEmpty(), "no proposals at the migration")
        assertTrue(tabled.all { it.kind.structureFree && it.season == 4 && it.appliesFrom == 6 })

        // The player votes for every one (the vote is the app's, kept until the close); season 5 plays
        // and closes, the adopted changes go into the rules the app saves and season 6 is played by them.
        for (x in tabled) assertTrue(w.federation.choose(x.season, x.number, true))
        play(w)
        val (close5, next5) = rollover(w, rules)
        val adopted = close5.ruleChanges
        assertEquals(tabled.map { it.number }, adopted.map { it.number }, "the player's votes decided them")
        assertTrue(adopted.all { it.by == Proposal.USER })
        val p6 = assertNotNull(w.plan)
        for (x in adopted) {
            assertEquals(x.to, next5.valueOf(x.lever.key), "${x.title}: the rules saved")
            assertEquals(x.to, p6.config.valueOf(x.lever.key), "${x.title}: season 6's rules")
        }
        // Applied, not voided at the open, and applying them again (a replayed close) changes nothing.
        assertTrue(w.federation.of(4).filter { it.adopted }.map { it.number } == adopted.map { it.number })
        assertEquals(next5, rulesAfterClose(next5, rules, close5).first)
    }

    /** The members as the app picks them here: every generated player. */
    private fun members(w: LeagueWorld): List<String> = w.players.alive.map { it.key }

    /** The league read back from [files] under the app's [rules], as LeagueController.readLeague does. */
    private fun read(files: Map<String, String>, rules: LeagueConfig): LeagueWorld {
        val players = PlayerBook.decode(files.getValue(LeagueWorld.ROSTER), emptyList())
        val keys = players.alive.map { it.key } + Ladder.ME
        val w = LeagueWorld.decode(files, keys, emptyList(), rules)
        w.players = players
        w.present = { it in keys }
        w.members = members(w)
        return w
    }

    /**
     * The rollover as LeagueController.rollover does it: the close under the season's closing
     * rules (the app's [config] when none were kept), then the next season under [config] with
     * the 요트협회's adopted changes ([rulesAfterClose]). Returns the close and the rules the app
     * saves.
     */
    private fun rollover(w: LeagueWorld, config: LeagueConfig): Pair<LeagueWorld.Closing, LeagueConfig> {
        val p = w.plan!!
        val closingRules = p.closing ?: config
        p.closing = closingRules
        w.config = closingRules
        w.members = members(w)
        w.ladder.fit()
        val closing = w.closeSeason()
        if (closing.rosterChanged) w.ladder = Ladder.decode(members(w) + Ladder.ME, w.ladder.encode())
        val (next, opening) = rulesAfterClose(config, closingRules, closing)
        w.config = opening
        w.members = members(w)
        w.openSeason()
        return closing to next
    }

    /** Plays [w]'s season with made-up results (the stronger side by the key's hash wins more often) until [stop] or its end. */
    private fun play(w: LeagueWorld, stop: (LeagueWorld) -> Boolean = { false }) {
        var guard = 0
        while (!w.seasonOver && !stop(w)) {
            val f = w.next(emptySet(), 0, 2) ?: if (w.seasonOver) break else error("season ${w.plan?.number} stuck at ${w.plan?.fixturesLeft} left")
            w.recordJob(result(f))
            check(++guard < 400_000)
        }
    }

    private fun strength(k: String): Int = k.hashCode() and 63

    private fun games(f: Fixture, a: String, b: String, n: Int, salt: Int): List<GameScore> {
        val rng = java.util.Random(f.seed * 31 + salt)
        val p = 1 / (1 + Math.pow(10.0, (strength(b) - strength(a)) / 100.0))
        return List(n) { if (rng.nextDouble() < p) GameScore(230, 190, aYacht = it == 0) else GameScore(190, 230) }
    }

    private fun result(f: Fixture): JobResult = when (f.kind) {
        JobKind.MATCH -> games(f, f.a, f.b, 2, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g), 0) }
        JobKind.TIE -> games(f, f.a, f.b, 2 * f.legs, 0).let { g -> JobResult(f, listOf(BoardResult(f.a, f.b, g, emptyList())), TieRule.match(g).let { if (it == 0) 1 else it }, 0) }
        JobKind.TEAM -> f.lineupA.zip(f.lineupB).mapIndexed { i, (x, y) -> BoardResult(x, y, games(f, x, y, 2, i + 1), emptyList()) }.let { JobResult(f, it, TieRule.team(it), 0) }
    }
}

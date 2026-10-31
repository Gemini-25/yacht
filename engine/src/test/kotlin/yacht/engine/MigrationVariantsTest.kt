package yacht.engine

import yacht.engine.league.ClubBook
import yacht.engine.league.Comp
import yacht.engine.league.HonourKind
import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeaguePreset
import yacht.engine.league.LeagueWorld
import yacht.engine.league.MatchFormat
import yacht.engine.league.Nations
import yacht.engine.league.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ways a league comes to national leagues (design ENG 7.3): a 빠른 시즌 league without a club
 * book, a league of base AIs only, 강자만's 48, a season finished and saved under its old rules
 * (`closecfg config 2`), and a league started afresh. Each takes the national path from the
 * closing season and keeps playing.
 */
class MigrationVariantsTest {
    private val base = listOf("ev") + (1..39).map { "b%02d".format(it) }

    /** The rollover of [run]'s legacy season into [next]; then the national season is played and closed too. */
    private fun migrates(run: WorldRun, next: LeagueConfig, check: (WorldRun) -> Unit) {
        run.play()
        run.beforeClose()
        val (close, open) = run.rollover(next)
        assertTrue(close.honours.none { HonourKind.leagueOf(it.kind) != null }, "the legacy season closed as legacy: ${close.honours.map { it.kind }.distinct()}")
        val p = run.w.plan!!
        assertTrue(p.league.leagues.national, p.league.leagues.toString())
        assertTrue(p.league.encode().startsWith(Season.NATIONAL_HEADER + "\n"))
        assertEquals(ClubBook.AFFILIATION, run.w.clubs!!.format)
        assertEquals(run.w.members.sorted(), p.league.divisions.flatten().sorted())
        for (k in run.w.members) assertTrue(run.w.clubs!!.clubOf(k) != null, "$k has no club")
        assertTrue(open.any { it.kind == "national_reform" }, open.map { it.kind }.toString())
        check(run)
        // And on: the national season plays and closes with its league honours.
        run.play()
        run.beforeClose()
        val (c2, _) = run.rollover(run.rules)
        assertTrue(c2.honours.any { HonourKind.isTopTitle(it.kind) && HonourKind.leagueOf(it.kind) != null }, c2.honours.map { it.kind }.distinct().toString())
        assertTrue(run.w.plan!!.league.leagues.national)
        assertTrue(run.w.news.all.none { it.headline.contains('{') || it.body.contains('{') })
    }

    @Test
    fun aFastLeagueWithoutClubsFoundsThem() {
        val legacy = LeaguePreset.FAST.config.copy(generated = 260)
        assertEquals(0, legacy.clubs)
        val fast = LeagueConfig.DEFAULT.copy(
            generated = 260, leagueMin = 20, divisionSize = 16, doubleRoundRobin = false, matchFormat = MatchFormat.SINGLE, titlePlayoff = false,
            promotionPlayoff = false, longKnockouts = false, seriesKnockouts = false, doubleElimination = false, nationCup = false, familyCup = false,
            clubCup = false, federation = false,
        )
        val run = WorldRun(legacy)
        run.w.openFirstSeason(3L)
        assertNull(run.w.clubs)
        migrates(run, fast) { r ->
            // Founded, not converted; a single round robin; the 요트협회 is off: nothing tabled, no assembly news.
            assertTrue(r.w.clubs!!.clubs.all { it.founded == 2 })
            val league = r.w.plan!!.league
            for (d in league.divisions.indices) assertEquals(league.divisions[d].size.let { if (it % 2 == 0) it - 1 else it }, league.roundsOf(d), "division $d")
            assertTrue(r.w.federation.proposals.isEmpty())
        }
    }

    @Test
    fun aLeagueOfBaseAisOnlyBecomesOneKoreanLeague() {
        val legacy = LeagueConfig(generated = 0, aging = false, clubs = 4, squad = 5, nationCup = false)
        val national = LeagueConfig.DEFAULT.copy(generated = 0, aging = false, nationCup = false)
        val run = WorldRun(legacy, 0, base)
        run.w.openFirstSeason(5L)
        migrates(run, national) { r ->
            // No identity: every member plays in Korea, so one league, no 대륙 챔피언스, no transfers abroad.
            assertEquals(listOf("KR"), r.w.plan!!.league.leagues.list.map { it.id })
            assertTrue(r.w.plan!!.comps.none { it.comp == Comp.CONT_CHAMPIONS })
            assertTrue(r.w.clubs!!.clubs.all { it.nation == "KR" })
        }
    }

    @Test
    fun theStrongest48FormOneInternationalLeague() {
        val elite = LeaguePreset.ELITE.config.copy(generated = 200, nationCup = false)
        val national = LeagueConfig.DEFAULT.copy(generated = 200, onlyTop = 48, aging = false, guests = false, familyCup = false, nationCup = false)
        val run = WorldRun(elite)
        assertEquals(48, run.w.members.size)
        run.w.openFirstSeason(7L)
        migrates(run, national) { r ->
            val leagues = r.w.plan!!.league.leagues.list
            assertEquals(48, r.w.members.size)
            assertEquals(1, leagues.size, leagues.toString())
            assertEquals(3, leagues.single().tiers.count)
        }
    }

    @Test
    fun aSeasonFinishedUnderItsOldRulesClosesUnderThemAfterARestart() {
        val legacy = LeagueConfig(generated = 260, clubs = 8, squad = 5, nationCup = false)
        val national = LeagueConfig.DEFAULT.copy(generated = 260, leagueMin = 20, divisionSize = 16, nationCup = false)
        val run = WorldRun(legacy)
        run.w.openFirstSeason(9L)
        run.play()
        // Saved before the close with its closing rules (a version 2 config), then read back by an
        // app whose rules are national now.
        val files = run.beforeClose()
        assertTrue("\nclosecfg config 2\n" in files.getValue(LeagueWorld.SEASON), files.getValue(LeagueWorld.SEASON).lines().filter { it.startsWith("closecfg") }.toString())
        run.rules = national
        run.w = run.read(files)
        assertEquals(legacy, run.w.plan!!.closing)
        run.w.members = run.members()
        // The rollover as the app does it: the close under the season's closing rules, the open under the app's.
        run.w.config = run.w.plan!!.closing!!
        val c = run.w.closeSeason()
        assertTrue(c.honours.any { it.kind == "L0" } && c.honours.any { it.kind.startsWith("UP") }, c.honours.map { it.kind }.distinct().toString())
        assertTrue(c.events.any { it.kind == "season_review" } && c.events.none { it.kind == "league_title" })
        run.w.config = national
        run.w.members = run.members()
        run.w.openSeason()
        assertTrue(run.w.plan!!.league.leagues.national)
        assertEquals(ClubBook.AFFILIATION, run.w.clubs!!.format)
        assertEquals(national, run.w.plan!!.config)
    }

    @Test
    fun aLeagueStartedAfreshIsNationalFromItsFirstSeason() {
        val national = LeagueConfig.DEFAULT.copy(generated = 300, leagueMin = 20, divisionSize = 16, nationCup = false)
        val run = WorldRun(national)
        run.w.openFirstSeason(13L)
        val p = run.w.plan!!
        assertEquals(1, p.number)
        assertTrue(p.league.leagues.national)
        val clubs = run.w.clubs!!
        assertEquals(ClubBook.AFFILIATION, clubs.format)
        for (k in run.w.members) {
            val lg = p.league.leagueOf(k)!!.id
            assertTrue(lg == clubs.playNation(k) || Nations.of(lg) == null, "$k plays in $lg, its club in ${clubs.playNation(k)}")
        }
        assertEquals(p.league.leagues.list.map { it.id }.sorted(), p.ranking.rows.map { it.id }.sorted())
        assertTrue(run.w.federation.top(1) != null)
        // A new league's first season: no old season to migrate, the first assembly comes at its close.
        run.play()
        val key = p.league.divisions[0].first()
        val club = clubs.clubOf(key)
        run.beforeClose()
        val (c, open) = run.rollover(national)
        assertTrue(c.ranking.rows.isNotEmpty() && open.none { it.kind == "national_reform" })
        assertTrue(run.w.federation.assembled == 1)
        // The clubs of season 1 are in the season lines.
        assertEquals(club, run.w.archive.lines(key).single { it.season == 1 }.club)
        assertEquals(p.league.leagueOf(key)!!.id, run.w.archive.lines(key).single { it.season == 1 }.league)
    }
}

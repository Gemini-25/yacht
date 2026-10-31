package yacht.engine

import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeaguePreset
import yacht.engine.league.MatchFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules' keys for national leagues and the flip to them (VERSION 3): a config saved before
 * reads as it did (a season's own copy plays on as it began), and a league's own settings move on
 * once to national leagues; the new keys are written and read back.
 */
class ConfigV3Test {
    /** config.txt as 945de5e wrote it for the default rules. */
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
        generated 1000
        aging 1
        childShare 40
        baseAis 0
        guests 1
        nationCup 1
        clubs 16
        squad 6
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
    fun aConfigSavedBeforeNationalLeaguesReadsAsBeforeAndMovesOnOnce() {
        assertEquals(3, LeagueConfig.VERSION)
        // The app writes config.txt again when its version is below VERSION.
        assertTrue(LeagueConfig.versionOf(old) < LeagueConfig.VERSION)
        // Read as written (a season's own copy of these rules plays on as it began).
        val c = LeagueConfig.decode(old)
        assertEquals(LeagueConfig(), c)
        assertFalse(c.national)
        assertTrue(c.sweepBonus && c.sweepPoints)
        // The league's own settings move on: 945de5e's default rules are the national default.
        val m = LeagueConfig.migrated(old)
        assertEquals(LeagueConfig.DEFAULT, m)
        assertTrue(m.national && m.doubleRoundRobin)
        // Written again as version 3, and read back unchanged: never moved on twice.
        val text = m.encode()
        assertTrue(text.startsWith("config 3\n"))
        assertFalse(LeagueConfig.versionOf(text) < LeagueConfig.VERSION)
        assertEquals(m, LeagueConfig.migrated(text))
        // A version 3 league's own choices stay, even where a migration would set them.
        val mine = m.copy(doubleRoundRobin = false, federation = false, divisionSize = 18)
        assertEquals(mine, LeagueConfig.migrated(mine.encode()))
        // The old lines unchanged and first (bar the version), the new keys after them.
        assertTrue(c.encode().removePrefix("config 3\n").startsWith(old.removePrefix("config 2\n")))
        // Older still: version 1 moves on to version 2's rules, then to national leagues.
        val v1 = LeagueConfig.migrated("config 1\ndivisions 3\nformat MIRRORED\n")
        assertTrue(v1.national && v1.doubleRoundRobin)
        assertTrue(v1.sweepBonus)
        assertEquals(MatchFormat.GAMES, v1.matchFormat)
        assertFalse(LeagueConfig.decode("config 1\n").national)
    }

    @Test
    fun everyKindOfOldLeagueMovesOnToNationalLeagues() {
        // 945de5e's presets as it saved them: 빠른 시즌 (단판, no club league) plays one round robin.
        val fast = LeagueConfig.migrated(
            "config 2\ndivisions 2\nformat SINGLE\nclubs 0\nfamilyCup 0\nnationCup 0\ntitlePlayoff 0\npromotionPlayoff 0\nlongKnockouts 0\nseries 0\ndoubleElim 0\n",
        )
        assertTrue(fast.national)
        assertFalse(fast.doubleRoundRobin)
        assertEquals(MatchFormat.SINGLE, fast.matchFormat)
        assertFalse(fast.titlePlayoff || fast.promotionPlayoff || fast.nationCup)
        // 강자만: its 48 keep their filter (one 국제 연합 리그 of them).
        val elite = LeagueConfig.migrated("config 2\ndivisions 2\nonlyTop 48\nclubs 8\nfamilyCup 0\nnationCup 0\naging 0\nguests 0\n")
        assertTrue(elite.national && elite.doubleRoundRobin)
        assertEquals(48, elite.onlyTop)
        assertFalse(elite.aging || elite.guests)
        // A developer's config 2 with the new keys (a build before the flip): the national rules as the default has them.
        val dev = LeagueConfig.migrated("config 2\nnational 0\ndivisionSize 22\nsweepBonus 0\nfederation 0\nclDirect 3\ngenerated 300\n")
        assertEquals(LeagueConfig.DEFAULT.copy(generated = 300), dev)
        // The two-game total of a version 2 league stays (only version 1's moved to 판 승점제).
        assertEquals(MatchFormat.MIRRORED, LeagueConfig.migrated("config 2\nformat MIRRORED\n").matchFormat)
    }

    @Test
    fun theNewKeysAreWrittenAndReadBack() {
        val c = LeagueConfig(
            national = true, doubleRoundRobin = true, divisionSize = 22, leagueMin = 30, sweepBonus = false, transfers = 7, continentalCl = false,
            clDirect = 3, clubSize = 10, clubCup = false, federation = false,
        )
        val text = c.encode()
        assertTrue(text.startsWith("config 3\n"))
        for (line in listOf(
            "national 1", "divisionSize 22", "leagueMin 30", "sweepBonus 0", "transfers 7", "continentalCl 0", "clDirect 3", "clubSize 10",
            "clubCup 0", "federation 0",
        )) {
            assertTrue("\n$line\n" in text, line)
        }
        assertEquals(c, LeagueConfig.decode(text))
        // A version 3 config is read as written, not moved on.
        assertEquals(c, LeagueConfig.migrated(text))
        assertEquals(LeagueConfig.DEFAULT, LeagueConfig.decode(LeagueConfig.DEFAULT.encode()))
        // A value out of its range keeps the default, as any bad value does.
        val bad = LeagueConfig.decode("config 2\ndivisionSize 30\nleagueMin 5\ntransfers 11\nclDirect 13\nclubSize 4\nnational x\n")
        assertEquals(LeagueConfig(), bad)
    }

    @Test
    fun aRuleIsReadAndChangedByItsKey() {
        val c = LeagueConfig()
        assertEquals("20", c.valueOf("divisionSize"))
        assertEquals("2", c.valueOf("autoSwap"))
        assertEquals("1", c.valueOf("sweepBonus"))
        assertEquals("0", c.valueOf("national"))
        assertEquals("GAMES", c.valueOf("format"))
        assertNull(c.valueOf("config"), "the version line is not a rule")
        assertNull(c.valueOf("nope"))
        // Every rule set to its own value gives the same rules back.
        for (key in c.encode().lines().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(' ') }) {
            assertEquals(c, c.with(key, assertNotNull(c.valueOf(key), key)), key)
        }
        assertNull(c.with("divisionSize", "99"))
        assertEquals(22, assertNotNull(c.with("divisionSize", "22")).divisionSize)
        assertEquals(c.copy(clDirect = 9), c.with("clDirect", "9"), "only that rule changes")
        assertNull(c.with("divisionSize", "x"))
        assertNull(c.with("national", "2"))
        assertNull(c.with("nope", "1"))
        assertNull(c.with("config", "3"))
        assertNull(c.with("autoSwap", "1 2"))
        assertFalse(assertNotNull(c.with("sweepBonus", "0")).sweepPoints)
        // The federation's levers are all rules with a value.
        for (key in listOf("autoSwap", "titlePlayoff", "sweepBonus", "divisionSize", "longKnockouts", "clDirect")) assertNotNull(c.valueOf(key), key)
    }

    @Test
    fun theSweepPointNeedsTheFormatAndTheRule() {
        assertTrue(LeagueConfig(matchFormat = MatchFormat.GAMES).sweepPoints)
        assertFalse(LeagueConfig(matchFormat = MatchFormat.GAMES, sweepBonus = false).sweepPoints)
        for (f in MatchFormat.entries.filter { it != MatchFormat.GAMES }) assertFalse(LeagueConfig(matchFormat = f).sweepPoints, f.name)
    }

    @Test
    fun theDefaultAndEveryPresetAreValidRules() {
        val d = LeagueConfig.DEFAULT
        assertTrue(d.national && d.doubleRoundRobin)
        assertEquals(LeagueConfig().copy(national = true, doubleRoundRobin = true), d)
        assertEquals(d, LeagueConfig.decode(d.encode()))
        for (p in LeaguePreset.entries) {
            assertEquals(p.config, LeagueConfig.decode(p.config.encode()), p.name)
            assertEquals(p.config, LeagueConfig.migrated(p.config.encode()), p.name)
            assertTrue(p.config.national, "${p.name}: every preset is national leagues")
        }
        assertEquals(d, LeaguePreset.REAL.config)
        // 빠른 시즌: one round robin of single games, one loss out, none of the extras.
        val fast = LeaguePreset.FAST.config
        assertFalse(fast.doubleRoundRobin || fast.titlePlayoff || fast.promotionPlayoff || fast.longKnockouts || fast.seriesKnockouts || fast.doubleElimination)
        assertFalse(fast.nationCup || fast.clubCup || fast.familyCup || fast.federation)
        assertEquals(MatchFormat.SINGLE, fast.matchFormat)
        assertEquals(d.copy(onlyFlawed = true, childShare = 70, guests = false), LeaguePreset.CHAOS.config)
        assertEquals(48, LeaguePreset.ELITE.config.onlyTop)
    }
}

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
 * The rules' keys for national leagues, before the flip: VERSION stays 2, so a saved config reads
 * as it did and is never moved on to national leagues; the new keys are written and read back.
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
    fun aConfigSavedBeforeNationalLeaguesReadsAsBeforeAndIsNotRewritten() {
        assertEquals(2, LeagueConfig.VERSION)
        // The app writes config.txt again only when its version is below VERSION.
        assertFalse(LeagueConfig.versionOf(old) < LeagueConfig.VERSION)
        val c = LeagueConfig.decode(old)
        assertEquals(LeagueConfig(), c)
        assertFalse(c.national)
        assertTrue(c.sweepBonus && c.sweepPoints)
        assertEquals(c, LeagueConfig.migrated(old), "migrated() moves nothing on")
        // Written again: the old lines unchanged and first, the new keys after them.
        assertTrue(c.encode().startsWith(old))
        // Older still: version 1 moves on to version 2's rules only.
        val v1 = LeagueConfig.migrated("config 1\ndivisions 3\nformat MIRRORED\n")
        assertFalse(v1.national)
        assertTrue(v1.sweepBonus)
        assertEquals(MatchFormat.GAMES, v1.matchFormat)
        assertFalse(LeagueConfig.decode("config 1\n").national)
    }

    @Test
    fun theNewKeysAreWrittenAndReadBack() {
        val c = LeagueConfig(
            national = true, doubleRoundRobin = true, divisionSize = 22, leagueMin = 30, sweepBonus = false, transfers = 7, continentalCl = false,
            clDirect = 3, clubSize = 10, clubCup = false, federation = false,
        )
        val text = c.encode()
        assertTrue(text.startsWith("config 2\n"), "version 2 until the flip")
        for (line in listOf(
            "national 1", "divisionSize 22", "leagueMin 30", "sweepBonus 0", "transfers 7", "continentalCl 0", "clDirect 3", "clubSize 10",
            "clubCup 0", "federation 0",
        )) {
            assertTrue("\n$line\n" in text, line)
        }
        assertEquals(c, LeagueConfig.decode(text))
        // Before the flip a national config 2 (a developer's) is read as written, not moved on.
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
        for (p in LeaguePreset.entries) {
            assertEquals(p.config, LeagueConfig.decode(p.config.encode()), p.name)
            assertFalse(p.config.national, "${p.name}: no preset offers national leagues before the flip")
        }
        assertEquals(LeagueConfig(), LeaguePreset.REAL.config)
    }
}

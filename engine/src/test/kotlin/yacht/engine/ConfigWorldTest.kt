package yacht.engine

import yacht.engine.league.LeagueConfig
import yacht.engine.league.LeaguePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The club world's rule keys (design ENG 8.1): `clubWorld`, `worldEvents` and the three new
 * federation levers' values `ffp`, `solidarity`, `styleCap`, written after the older keys of
 * `config 3` where they differ from their defaults (no version bump; older versions skip them, and
 * a league that keeps the defaults writes what 3ce4b93 wrote) and read back; a file without them
 * reads the defaults.
 */
class ConfigWorldTest {
    @Test
    fun theKeysRoundTripWithTheirDefaults() {
        val d = LeagueConfig()
        assertTrue(d.clubWorld && d.worldEvents)
        assertEquals(1, d.ffp)
        assertEquals(5, d.solidarity)
        assertEquals(15, d.styleCap)
        val c = d.copy(clubWorld = false, worldEvents = false, ffp = 2, solidarity = 20, styleCap = 13)
        val text = c.encode()
        assertTrue(text.startsWith("config ${LeagueConfig.VERSION}\n"))
        for (line in listOf("clubWorld 0", "worldEvents 0", "ffp 2", "solidarity 20", "styleCap 13")) assertTrue("\n$line\n" in text, line)
        assertEquals(c, LeagueConfig.decode(text))
        assertEquals(c, LeagueConfig.migrated(text))
        // After every older key: the lines a 3ce4b93 build wrote come first, unchanged.
        val keys = text.lines().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(' ') }
        assertEquals(listOf("clubWorld", "worldEvents", "ffp", "solidarity", "styleCap"), keys.takeLast(5))
        assertEquals("federation", keys[keys.size - 6])
        // Only the keys away from their defaults are written.
        val one = LeagueConfig(ffp = 2)
        assertEquals(listOf("ffp 2"), one.encode().lines().filter { l -> listOf("clubWorld", "worldEvents", "ffp", "solidarity", "styleCap").any { l.startsWith("$it ") } })
        assertEquals(one, LeagueConfig.decode(one.encode()))
    }

    @Test
    fun aConfigWithoutTheKeysReadsTheDefaults() {
        // At their defaults the keys are not written: the file (and a season's cfg lines) is what 3ce4b93 wrote.
        val new = listOf("clubWorld", "worldEvents", "ffp", "solidarity", "styleCap")
        for (c in listOf(LeagueConfig(), LeagueConfig.DEFAULT) + LeaguePreset.entries.filter { it.config.worldEvents }.map { it.config }) {
            assertTrue(c.encode().lines().none { l -> new.any { l.startsWith("$it ") } }, c.toString())
        }
        assertEquals(listOf("worldEvents 0"), LeaguePreset.FAST.config.encode().lines().filter { l -> new.any { l.startsWith("$it ") } })
        assertEquals("federation 1", LeagueConfig.DEFAULT.encode().trimEnd().lines().last())
        // A config 3 as 3ce4b93 wrote it: every key it knew, none of the new ones.
        val old = LeagueConfig.DEFAULT.encode()
        assertFalse("clubWorld" in old)
        val c = LeagueConfig.decode(old)
        assertEquals(LeagueConfig.DEFAULT, c)
        assertEquals(LeagueConfig.DEFAULT, LeagueConfig.migrated(old))
        assertTrue(c.clubWorld && c.worldEvents && c.ffp == 1 && c.solidarity == 5 && c.styleCap == 15)
        // Values out of range keep the defaults, as any bad value does.
        val bad = LeagueConfig.decode("config 3\nffp 3\nsolidarity 7\nstyleCap 14\nffp x\n")
        assertEquals(LeagueConfig(), bad)
        assertEquals(LeagueConfig(solidarity = 0, styleCap = 17, ffp = 0), LeagueConfig.decode("config 3\nffp 0\nsolidarity 0\nstyleCap 17\n"))
    }

    @Test
    fun theLeversAreRulesWithAValue() {
        val c = LeagueConfig()
        assertEquals("1", c.valueOf("ffp"))
        assertEquals("5", c.valueOf("solidarity"))
        assertEquals("15", c.valueOf("styleCap"))
        assertEquals("1", c.valueOf("clubWorld"))
        assertEquals("1", c.valueOf("worldEvents"))
        for (v in 0..2) assertEquals(v, assertNotNull(c.with("ffp", "$v")).ffp)
        assertNull(c.with("ffp", "3"))
        assertNull(c.with("ffp", "-1"))
        for (v in listOf(0, 5, 10, 15, 20)) assertEquals(v, assertNotNull(c.with("solidarity", "$v")).solidarity)
        for (v in listOf("7", "25", "-5", "x")) assertNull(c.with("solidarity", v), v)
        for (v in LeagueConfig.STYLE_CAPS) assertEquals(v, assertNotNull(c.with("styleCap", "$v")).styleCap)
        for (v in listOf("12", "14", "15.0", "18")) assertNull(c.with("styleCap", v), v)
        assertEquals(c.copy(ffp = 2), c.with("ffp", "2"), "only that rule changes")
        // Each key set to its own value (also one left out of the file at its default) gives the same rules back.
        for (key in listOf("clubWorld", "worldEvents", "ffp", "solidarity", "styleCap")) assertEquals(c, c.with(key, assertNotNull(c.valueOf(key))), key)
        assertEquals(c, assertNotNull(c.copy(ffp = 0).with("ffp", "1")))
        assertFalse(assertNotNull(c.with("worldEvents", "0")).worldEvents)
        assertNull(c.with("clubWorld", "2"))
    }

    @Test
    fun fastAndEliteHaveNoWorldEvents() {
        for (p in LeaguePreset.entries) {
            assertEquals(p != LeaguePreset.FAST && p != LeaguePreset.ELITE, p.config.worldEvents, p.name)
            assertTrue(p.config.clubWorld, p.name)
            assertEquals(1, p.config.ffp, p.name)
            assertEquals(5, p.config.solidarity, p.name)
            assertEquals(15, p.config.styleCap, p.name)
            assertEquals(p.config, LeagueConfig.decode(p.config.encode()), p.name)
        }
        assertTrue(LeagueConfig.DEFAULT.worldEvents)
    }
}

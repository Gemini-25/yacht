package yacht.uicheck

import yacht.app.core.UiPrefs
import yacht.engine.league.LeagueWorld
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The story world (UX 15.1) that the story shots restore: built twice from its seed it gives the
 * same files across a season's close, but for the wall-clock times the ladder stamps on its recent
 * matches, which no screen shows (a story shot shows the same world every run); it stops at week
 * ⌈0.6 R⌉ + 4 of the season after its last close, [StoryWorld.seasonOf] reads its season as the
 * controller does, and a variant edits, adds and leaves out files.
 */
class StoryWorldTest {
    /** A small story world: one season closed, the next played to its default week. */
    private fun small() = StoryWorld.build(players = 120, seasons = 1)

    /**
     * [bytes] of file [name] without what the clock writes: the time the ladder stamps on each of
     * its recent matches (`m a b games deltaA deltaB time …`, the wall clock when it was recorded).
     */
    private fun timeless(name: String, bytes: ByteArray): String {
        val text = bytes.decodeToString()
        if (name != LeagueWorld.LADDER) return text
        return text.lines().joinToString("\n") { line ->
            if (!line.startsWith("m ")) line else line.split(' ').toMutableList().also { it[MATCH_TIME] = "t" }.joinToString(" ")
        }
    }

    @Test
    fun theSameSeedBuildsTheSameFiles() {
        val a = StoryWorld.files(small())
        val b = StoryWorld.files(small())
        assertEquals(a.keys, b.keys)
        for ((name, bytes) in a) assertEquals(timeless(name, bytes), timeless(name, b.getValue(name)), name)
        // Every league file a backup holds, the rules and the UI preferences.
        assertTrue(a.keys.containsAll(LeagueWorld.FILES), "${LeagueWorld.FILES - a.keys} missing")
        assertTrue("config.txt" in a && UiPrefs.FILE in a, a.keys.toString())
    }

    @Test
    fun itStopsPastTheWinterWeekOfTheSeasonAfterItsCloses() {
        val built = small()
        val p = built.world.plan!!
        assertEquals(2, p.number)
        assertEquals(ceil(0.6 * p.calendar.leagueWeeks).toInt() + 4, p.week)
        assertFalse(built.world.seasonOver)
        // The season the controller reads from season.txt.
        assertEquals(2, StoryWorld.seasonOf(StoryWorld.files(built)))
    }

    @Test
    fun aVariantEditsAddsAndLeavesOutFiles() {
        val files = StoryWorld.files(small())
        val v = StoryWorld.variant(files) {
            clubs = clubs + "# edited\n"
            world = "world 1\n"
            this[LeagueWorld.NEWS] = null
        }
        assertEquals(files.getValue(LeagueWorld.CLUBS).decodeToString() + "# edited\n", v.getValue(LeagueWorld.CLUBS).decodeToString())
        assertEquals("world 1\n", v.getValue(StoryFiles.WORLD).decodeToString())
        assertNull(v[LeagueWorld.NEWS])
        // The rest as they were (world.txt too, once the engine writes one).
        for (name in files.keys - LeagueWorld.CLUBS - LeagueWorld.NEWS - StoryFiles.WORLD) assertContentEquals(files.getValue(name), v.getValue(name), name)
    }

    private companion object {
        /** A ladder match line's time field. */
        const val MATCH_TIME = 6
    }
}

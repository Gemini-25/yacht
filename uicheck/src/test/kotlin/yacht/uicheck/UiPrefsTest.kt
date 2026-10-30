package yacht.uicheck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import yacht.app.core.LeagueController
import yacht.app.core.MatchCenter
import yacht.app.core.UiPrefs
import yacht.app.core.WatchPace
import yacht.engine.league.LeagueConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiPrefsTest {
    private val prefs = UiPrefs().dismiss(UiPrefs.REFORM, 3).dismiss(UiPrefs.COMING, 2).copy(watchPace = WatchPace.FAST)

    @Test
    fun roundTrip() {
        assertEquals(prefs, UiPrefs.decode(prefs.encode()))
        assertEquals(UiPrefs(), UiPrefs.decode(UiPrefs().encode()))
        assertTrue(prefs.dismissed(UiPrefs.REFORM, 3))
        assertFalse(prefs.dismissed(UiPrefs.REFORM, 4))
        assertEquals("ui 1\ndismissed coming 2\ndismissed reform 3\nwatchpace FAST\n", prefs.encode())
    }

    @Test
    fun aMissingOrBadFileGivesTheDefaults() {
        for (text in listOf(null, "", "garbage", "ui 2\nwatchpace FAST\n", "watchpace FAST\n", "ui\n", "\u0000\u0001")) {
            assertEquals(UiPrefs(), UiPrefs.decode(text), "$text")
        }
        // A line not understood is skipped; the rest is kept.
        assertEquals(
            UiPrefs().dismiss(UiPrefs.REFORM, 5),
            UiPrefs.decode("ui 1\ndismissed reform 5\nwatchpace TURBO\ndismissed coming x\nsomething else\n"),
        )
    }

    @Test
    fun backupAndRestoreCarryIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val a = LeagueController(scope, Files.createTempDirectory("ui-a").toFile().also { dirs += it })
            // No generated players to make when the restored league is read.
            a.changeConfig(LeagueConfig(generated = 0))
            a.updateUiPrefs { prefs }
            val aFile = File(dirs.last(), "league/${UiPrefs.FILE}")
            waitFor { aFile.exists() && File(dirs.last(), "league/config.txt").exists() }
            val files = a.backupFiles()
            assertEquals(prefs, UiPrefs.decode(files[UiPrefs.FILE]?.let { String(it) }))
            assertTrue(UiPrefs.FILE in LeagueController.SAVED && MatchCenter.LOGS in LeagueController.SAVED)

            val bDir = Files.createTempDirectory("ui-b").toFile().also { dirs += it }
            val b = LeagueController(scope, bDir)
            assertEquals(UiPrefs(), b.uiPrefs)
            runBlocking { b.restore(files) }
            assertEquals(prefs, b.uiPrefs)
            assertEquals(prefs, UiPrefs.decode(File(bDir, "league/${UiPrefs.FILE}").readText()))
            // A backup made before ui.txt: the defaults, and the file goes.
            runBlocking { b.restore(files - UiPrefs.FILE) }
            assertEquals(UiPrefs(), b.uiPrefs)
            assertFalse(File(bDir, "league/${UiPrefs.FILE}").exists())
        } finally {
            scope.cancel()
            for (d in dirs) d.deleteRecursively()
        }
    }

    private val dirs = ArrayList<File>()

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }
}

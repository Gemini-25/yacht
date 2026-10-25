package yacht.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import yacht.app.core.AppController
import yacht.app.core.GameStorage
import yacht.app.core.Screen
import yacht.app.ui.AppRoot
import yacht.app.ui.FilePort
import yacht.app.ui.LocalFilePort
import yacht.app.ui.YachtTheme
import yacht.engine.RuleSet
import yacht.engine.TableIO
import yacht.engine.Tables
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class GameViewModel(app: Application) : AndroidViewModel(app) {
    val controller = AppController(
        viewModelScope, GameStorage(app.filesDir), { loadTables(app) },
        loadAsset = { name -> runCatching { app.assets.open(name).use { String(it.readBytes()) } }.getOrNull() },
    )

    init {
        controller.start()
    }

    override fun onCleared() = controller.shutdown()
}

/** ev.bin is small and read into memory; dist.bin (~50 MB) is memory-mapped from the APK. */
private fun loadTables(context: Context): Tables {
    val rules = RuleSet.NINTENDO
    val ev = context.assets.open("ev.bin").use { ByteBuffer.wrap(it.readBytes()) }
    val dist = context.assets.openFd("dist.bin").use { fd ->
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }
    val bonus = runCatching {
        context.assets.open("bonus.bin").use { TableIO.readBonus(ByteBuffer.wrap(it.readBytes()), rules) }
    }.getOrNull()
    return Tables(rules, TableIO.readEv(ev, rules), TableIO.readDist(dist, rules), bonus = bonus)
}

/** A file request waiting for the system picker. */
private class PendingFiles {
    var save: Pair<ByteArray, (Boolean) -> Unit>? = null
    var open: ((ByteArray?) -> Unit)? = null
}

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        vm.controller.onForeground()
    }

    override fun onStop() {
        // Nothing runs in the background while the app is hidden (battery).
        vm.controller.onBackground()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Backups go through the system file picker; the request waits here for its answer.
            val pending = remember { PendingFiles() }
            val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                val p = pending.save
                pending.save = null
                // Cancelled: nothing to report.
                if (p != null && uri != null) {
                    val ok = runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(p.first) } }.isSuccess
                    p.second(ok)
                }
            }
            val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val cb = pending.open
                pending.open = null
                cb?.invoke(uri?.let { u -> runCatching { contentResolver.openInputStream(u)!!.use { it.readBytes() } }.getOrNull() })
            }
            val port = remember(saver, opener) {
                object : FilePort {
                    override fun save(name: String, bytes: ByteArray, done: (Boolean) -> Unit) {
                        pending.save = bytes to done
                        saver.launch(name)
                    }

                    override fun open(got: (ByteArray?) -> Unit) {
                        pending.open = got
                        opener.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                }
            }
            YachtTheme(dark = isSystemInDarkTheme()) {
                val c = vm.controller
                BackHandler(enabled = c.screen != Screen.HOME) {
                    if (c.screen == Screen.WATCH) c.league.stopWatch()
                    c.screen = when (c.screen) {
                        Screen.REVIEW -> Screen.HISTORY
                        Screen.WATCH, Screen.BUILDER -> Screen.LEAGUE
                        else -> Screen.HOME
                    }
                }
                CompositionLocalProvider(LocalFilePort provides port) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        AppRoot(c)
                    }
                }
            }
        }
    }
}
